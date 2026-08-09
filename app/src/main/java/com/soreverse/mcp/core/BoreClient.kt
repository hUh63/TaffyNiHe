package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bore 隧道客户端 - 严格遵循 ekzhang/bore 协议
 *
 * 协议:
 * 1. 连接控制端口 (默认 7835)
 * 2. 客户端发送 {"Hello":本地端口}\0
 * 3. 服务器响应 {"Hello":分配端口}\0 或 {"Error":"消息"}\0
 * 4. 控制通道接收: {"Heartbeat":null}\0 / {"Connection":"uuid"}\0 / {"Error":"消息"}\0
 * 5. 每个 Connection: 新建数据连接 → 发送 {"Accept":"连接ID"}\0 → 双向转发
 * 6. 可选认证: {"Challenge":"uuid"}\0 → {"Authenticate":"密码"}\0
 */
class BoreClient(
    private val boreHost: String = "bore.pub",
    private val borePort: Int = 7835,
    private val localPort: Int = 8080,
    private val secret: String? = null,
) {
    interface BoreListener {
        fun onConnected(publicUrl: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onBytesTransferred(bytes: Long)
        fun onConnectionEvent(event: String)
    }

    private var listener: BoreListener? = null
    private var tunnelThread: Thread? = null
    @Volatile private var running = false
    private var dataExecutor: ExecutorService? = null
    private val connectionThreads = ConcurrentHashMap<String, Thread>()
    @Volatile private var assignedPort = -1
    @Volatile private var autoReconnect = true
    @Volatile private var stopRequested = false
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectThread: Thread? = null
    private val generation = AtomicInteger(0)
    private var connectTimeoutThread: Thread? = null

    private companion object {
        const val DEFAULT_BORE_PORT = 7835
        const val CONNECT_TIMEOUT_MS = 10000
        const val LOCAL_CONNECT_TIMEOUT_MS = 5000
        const val CONTROL_READ_TIMEOUT_MS = 20000
        const val MAX_CONSECUTIVE_TIMEOUTS = 3
        const val MAX_RECONNECT_ATTEMPTS = 100
        const val RECONNECT_DELAY_MS = 1000
        const val CONNECT_TIMEOUT_TOTAL_MS = 60000
        const val MAX_FRAME_LENGTH = 65536
    }

    fun setListener(l: BoreListener?) { listener = l }
    fun setAutoReconnect(ar: Boolean) { autoReconnect = ar }

    private fun fireEvent(event: String) {
        listener?.onConnectionEvent(event)
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    @Synchronized
    fun start() {
        if (running) return
        if (dataExecutor?.isShutdown != false) {
            dataExecutor = Executors.newCachedThreadPool()
        }
        stopRequested = false
        generation.incrementAndGet()
        running = true
        reconnectAttempts.set(0)
        val runGeneration = generation.get()

        tunnelThread = Thread {
            var controlSocket: Socket? = null
            try {
                fireEvent("${now()} 正在连接 ${boreHost}:${borePort}...")
                controlSocket = Socket()
                controlSocket.connect(InetSocketAddress(boreHost, borePort), CONNECT_TIMEOUT_MS)
                controlSocket.soTimeout = 0
                val controlIn = controlSocket.getInputStream()
                val controlOut = controlSocket.getOutputStream()
                val assigned = handshake(controlIn, controlOut)
                if (assigned <= 0) {
                    running = false
                    listener?.onError("handshake failed")
                    return@Thread
                }
                assignedPort = assigned
                val publicUrl = "http://${boreHost}:${assignedPort}"
                listener?.onConnected(publicUrl)
                fireEvent("${now()} 隧道已建立: ${publicUrl}")
                startConnectTimeoutChecker(runGeneration)
                controlLoop(controlIn, controlOut, runGeneration, controlSocket)
            } catch (e: InterruptedException) {
                // 正常停止
            } catch (e: Exception) {
                if (!stopRequested && generation.get() == runGeneration) {
                    listener?.onError(if (e.message != null) "Bore: ${e.message}" else "Bore: ${e.javaClass.simpleName}")
                }
            } finally {
                running = false
                assignedPort = -1
                try { controlSocket?.close() } catch (_: Exception) {}
                listener?.onDisconnected()
                if (autoReconnect && !stopRequested && generation.get() == runGeneration) {
                    scheduleReconnect(runGeneration)
                }
            }
        }.apply {
            isDaemon = true
            name = "bore-tunnel"
        }
        tunnelThread?.start()
    }

    private fun handshake(controlIn: InputStream, controlOut: OutputStream): Int {
        val hello = JSONObject().apply { put("Hello", localPort) }.toString().toByteArray(StandardCharsets.UTF_8)
        controlOut.write(hello)
        controlOut.write(0x00)
        controlOut.flush()
        val resp = readFrame(controlIn)
            ?: return -1
        val json = JSONObject(String(resp, StandardCharsets.UTF_8))
        if (json.has("Hello")) {
            val port = json.getInt("Hello")
            if (secret != null) {
                val authMsg = JSONObject().apply { put("Authenticate", secret) }.toString()
                controlOut.write(authMsg.toByteArray(StandardCharsets.UTF_8))
                controlOut.write(0x00)
                controlOut.flush()
                val authResp = readFrame(controlIn)
                if (authResp != null) {
                    val authJson = JSONObject(String(authResp, StandardCharsets.UTF_8))
                    if (authJson.has("Error")) {
                        listener?.onError("Auth failed: ${authJson.getString("Error")}")
                        return -1
                    }
                }
            }
            return port
        } else if (json.has("Error")) {
            listener?.onError("Server: ${json.getString("Error")}")
            return -1
        }
        return -1
    }

    private fun controlLoop(controlIn: InputStream, controlOut: OutputStream, runGeneration: Int, controlSocket: Socket) {
        var consecutiveTimeouts = 0
        while (!stopRequested && generation.get() == runGeneration) {
            try {
                controlSocket.soTimeout = CONTROL_READ_TIMEOUT_MS
                val frame = readFrame(controlIn)
                if (frame == null) {
                    if (stopRequested || generation.get() != runGeneration) break
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                        fireEvent("${now()} 连接超时，准备重连")
                        break
                    }
                    continue
                }
                consecutiveTimeouts = 0
                val json = JSONObject(String(frame, StandardCharsets.UTF_8))
                if (json.has("Heartbeat")) {
                    continue
                }
                if (json.has("Connection")) {
                    val connId = json.optString("Connection", json.opt("Connection").toString())
                    handleDataConnection(connId, runGeneration)
                    continue
                }
                if (json.has("Error")) {
                    listener?.onError("Server: ${json.getString("Error")}")
                    break
                }
                if (json.has("Challenge")) {
                    val challenge = json.getString("Challenge")
                    if (secret != null) {
                        val authMsg = JSONObject().apply { put("Authenticate", secret) }.toString()
                        controlOut.write(authMsg.toByteArray(StandardCharsets.UTF_8))
                        controlOut.write(0x00)
                        controlOut.flush()
                    }
                    continue
                }
            } catch (e: SocketTimeoutException) {
                consecutiveTimeouts++
                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    fireEvent("${now()} 连接超时，准备重连")
                    break
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!stopRequested) fireEvent("${now()} 控制连接异常: ${e.message}")
                break
            }
        }
    }

    private fun handleDataConnection(connId: String, runGeneration: Int) {
        val t = Thread {
            var dataSocket: Socket? = null
            var localSocket: Socket? = null
            try {
                fireEvent("${now()} 收到新连接: ${connId}")
                dataSocket = Socket()
                dataSocket.connect(InetSocketAddress(boreHost, borePort), CONNECT_TIMEOUT_MS)
                dataSocket.soTimeout = 0
                val dataOut = dataSocket.getOutputStream()
                val accept = JSONObject().apply { put("Accept", connId) }.toString()
                dataOut.write(accept.toByteArray(StandardCharsets.UTF_8))
                dataOut.write(0x00)
                dataOut.flush()
                localSocket = Socket()
                localSocket.connect(InetSocketAddress("127.0.0.1", localPort), LOCAL_CONNECT_TIMEOUT_MS)
                localSocket.soTimeout = 0
                val localIn = localSocket.getInputStream()
                val localOut = localSocket.getOutputStream()
                val dataIn = dataSocket.getInputStream()
                val forwarder1 = Thread {
                    try {
                        pipe(dataIn, localOut)
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; name = "bore-pipe-c2l-$connId" }
                val forwarder2 = Thread {
                    try {
                        pipe(localIn, dataOut)
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; name = "bore-pipe-l2c-$connId" }
                connectionThreads[connId] = forwarder1
                forwarder1.start()
                forwarder2.start()
                forwarder1.join()
                forwarder2.join(5000)
            } catch (e: InterruptedException) {
                // 正常停止
            } catch (e: Exception) {
                fireEvent("${now()} 数据连接异常: ${e.message}")
            } finally {
                connectionThreads.remove(connId)
                try { dataSocket?.close() } catch (_: Exception) {}
                try { localSocket?.close() } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "bore-data-$connId"
        }
        connectionThreads[connId] = t
        t.start()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        while (!stopRequested) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val bos = ByteArrayOutputStream()
        var last: Int
        while (true) {
            last = input.read()
            if (last < 0) return null
            if (last == 0x00) break
            bos.write(last)
            if (bos.size() > MAX_FRAME_LENGTH) return null
        }
        return if (bos.size() == 0) null else bos.toByteArray()
    }

    private fun startConnectTimeoutChecker(runGeneration: Int) {
        connectTimeoutThread = Thread {
            try {
                Thread.sleep(CONNECT_TIMEOUT_TOTAL_MS)
                if (running && generation.get() == runGeneration && assignedPort < 0) {
                    fireEvent("${now()} 连接超时(${CONNECT_TIMEOUT_TOTAL_MS/1000}s)")
                    stop()
                }
            } catch (_: InterruptedException) {}
        }.apply {
            isDaemon = true
            name = "bore-connect-timeout"
        }
        connectTimeoutThread?.start()
    }

    private fun cancelConnectTimeout() {
        connectTimeoutThread?.interrupt()
        connectTimeoutThread = null
    }

    private fun scheduleReconnect(runGeneration: Int) {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            listener?.onError("max reconnect attempts reached")
            return
        }
        reconnectThread = Thread {
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
                if (stopRequested || generation.get() != runGeneration) return@Thread
                reconnectAttempts.incrementAndGet()
                fireEvent("${now()} 重连中 (${reconnectAttempts.get()})...")
                start()
            } catch (_: InterruptedException) {}
        }.apply {
            isDaemon = true
            name = "bore-reconnect"
        }
        reconnectThread?.start()
    }

    fun requestStop() {
        stopRequested = true
        generation.incrementAndGet()
        autoReconnect = false
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    @Synchronized
    fun stop() {
        autoReconnect = false
        running = false
        stopRequested = true
        generation.incrementAndGet()
        cancelConnectTimeout()
        reconnectThread?.interrupt()
        reconnectThread = null
        tunnelThread?.interrupt()
        tunnelThread = null
        connectionThreads.values.forEach { it.interrupt() }
        connectionThreads.clear()
        dataExecutor?.shutdownNow()
        dataExecutor = null
    }

    fun isRunning(): Boolean = running

    companion object {
        fun parseHost(hostPort: String): String {
            var raw = hostPort.trim()
            if (raw.startsWith("https://")) raw = raw.substring(8)
            else if (raw.startsWith("http://")) raw = raw.substring(7)
            val colonIdx = raw.indexOf(':')
            return if (colonIdx > 0) raw.substring(0, colonIdx) else raw
        }
        fun parsePort(hostPort: String, defaultPort: Int = DEFAULT_BORE_PORT): Int {
            var raw = hostPort.trim()
            if (raw.startsWith("https://")) raw = raw.substring(8)
            else if (raw.startsWith("http://")) raw = raw.substring(7)
            val colonIdx = raw.indexOf(':')
            return if (colonIdx > 0 && colonIdx < raw.length - 1) {
                raw.substring(colonIdx + 1).toIntOrNull() ?: defaultPort
            } else defaultPort
        }
        fun parseSecret(hostPort: String): String? {
            var raw = hostPort.trim()
            if (raw.startsWith("https://")) raw = raw.substring(8)
            else if (raw.startsWith("http://")) raw = raw.substring(7)
            val firstColon = raw.indexOf(':')
            if (firstColon <= 0) return null
            val secondColon = raw.indexOf(':', firstColon + 1)
            return if (secondColon > 0 && secondColon < raw.length - 1) {
                raw.substring(secondColon + 1)
            } else null
        }
    }
}