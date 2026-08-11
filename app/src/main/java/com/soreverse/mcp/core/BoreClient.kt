package com.soreverse.mcp.core

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bore 隧道客户端 - 严格遵循 ekzhang/bore 协议
 *
 * 优化点:
 * 1. readFrame 缓冲区提前 break 防止恶意数据
 * 2. readFrame 返回 null 立即断开（不再算超时）
 * 3. 指数退避重连（1s→2s→4s→8s→最大30s）
 * 4. 数据连接用 CountDownLatch 避免线程阻塞
 * 5. 流量统计
 * 6. 保活探测（控制连接 idle 检测）
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
    private val totalBytesTransferred = AtomicLong(0)
    @Volatile private var lastHeartbeatMs = 0L

    companion object {
        const val DEFAULT_BORE_PORT = 7835
        const val CONNECT_TIMEOUT_MS = 10000
        const val LOCAL_CONNECT_TIMEOUT_MS = 5000
        const val CONTROL_READ_TIMEOUT_MS = 20000
        const val MAX_CONSECUTIVE_TIMEOUTS = 3
        const val MAX_RECONNECT_ATTEMPTS = 100
        const val RECONNECT_DELAY_MS = 1000
        const val RECONNECT_MAX_DELAY_MS = 30000
        const val CONNECT_TIMEOUT_TOTAL_MS = 60000
        const val MAX_FRAME_LENGTH = 65536

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

    fun setListener(l: BoreListener?) { listener = l }
    fun setAutoReconnect(ar: Boolean) { autoReconnect = ar }

    /** 用户手动启动，重置重连计数 */
    fun startTunnel() {
        reconnectAttempts.set(0)
        start()
    }

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
        val runGeneration = generation.get()

        tunnelThread = Thread {
            var controlSocket: Socket? = null
            try {
                fireEvent("${now()} ▶ 正在连接 ${boreHost}:${borePort}...")
                controlSocket = Socket()
                controlSocket.connect(InetSocketAddress(boreHost, borePort), CONNECT_TIMEOUT_MS)
                fireEvent("${now()} ✓ TCP 连接已建立")
                // 先设为无限阻塞，握手时再设超时
                controlSocket.soTimeout = 0
                // TCP keepalive 防止连接异常断开
                runCatching { controlSocket.keepAlive = true }
                // 禁用 Nagle 算法减少延迟
                runCatching { controlSocket.tcpNoDelay = true }
                val controlIn = controlSocket.getInputStream()
                val controlOut = controlSocket.getOutputStream()
                fireEvent("${now()} → 发送 Hello(localPort=$localPort)")
                val assigned = handshake(controlIn, controlOut, controlSocket)
                if (assigned <= 0) {
                    running = false
                    listener?.onError("handshake failed")
                    return@Thread
                }
                assignedPort = assigned
                val publicUrl = "http://${boreHost}:${assignedPort}"
                fireEvent("${now()} ✓ 握手成功，分配端口: $assigned")
                listener?.onConnected(publicUrl)
                fireEvent("${now()} ✓ 隧道已建立: ${publicUrl}")
                lastHeartbeatMs = System.currentTimeMillis()
                cancelConnectTimeout()
                controlLoop(controlIn, controlOut, runGeneration, controlSocket)
            } catch (e: InterruptedException) {
                fireEvent("${now()} ⏹ 隧道线程被中断")
            } catch (e: Exception) {
                if (!stopRequested && generation.get() == runGeneration) {
                    val msg = if (e.message != null) "Bore: ${e.message}" else "Bore: ${e.javaClass.simpleName}"
                    fireEvent("${now()} ✗ 异常: $msg")
                    listener?.onError(msg)
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

    private fun handshake(controlIn: InputStream, controlOut: OutputStream, controlSocket: Socket): Int {
        // bore 协议: Hello 的值是期望的远程公网端口，0 = 让服务器自动分配
        // 注意: 不是本地端口！本地端口仅用于收到连接后转发
        val hello = "{\"Hello\":0}\u0000".toByteArray(StandardCharsets.UTF_8)
        controlOut.write(hello)
        controlOut.flush()
        fireEvent("${now()} → 发送 Hello(0=自动分配端口)")
        // 握手阶段设 10 秒超时
        controlSocket?.soTimeout = 10000
        val resp = readFrame(controlIn)
        if (resp == null) {
            fireEvent("${now()} ✗ 服务器无响应（连接已关闭）")
            return -1
        }
        var respStr = String(resp, StandardCharsets.UTF_8)
        fireEvent("${now()} ← 收到: $respStr")

        // 处理可能的 Challenge 认证（在 Hello 之前）
        if (respStr.contains("\"Challenge\"")) {
            if (secret.isNullOrEmpty()) {
                fireEvent("${now()} ✗ 服务器需要认证但未提供密码")
                listener?.onError("Server requires authentication but no secret provided")
                return -1
            }
            fireEvent("${now()} 🔐 服务器要求认证")
            val authMsg = "{\"Authenticate\":\"${escapeJson(secret)}\"}\u0000".toByteArray(StandardCharsets.UTF_8)
            controlOut.write(authMsg)
            controlOut.flush()
            fireEvent("${now()} → 发送认证响应")
            // 认证后读取 Hello 响应
            val afterAuth = readFrame(controlIn)
            if (afterAuth == null) {
                fireEvent("${now()} ✗ 认证后无响应")
                return -1
            }
            respStr = String(afterAuth, StandardCharsets.UTF_8)
            fireEvent("${now()} ← 收到: $respStr")
        }

        // 解析 Hello 响应（用字符串解析，避免 JSONObject 兼容问题）
        if (respStr.contains("\"Hello\"")) {
            val port = parseHelloResponse(respStr)
            if (port > 0) {
                fireEvent("${now()} ✓ 服务器分配端口: $port")
                return port
            }
        }
        if (respStr.contains("\"Error\"")) {
            val err = parseStringValue(respStr, "Error") ?: "unknown"
            fireEvent("${now()} ✗ 服务器错误: $err")
            listener?.onError("Server: $err")
            return -1
        }
        // 兼容非标准服务器：可能直接发 Connection
        if (respStr.contains("\"Connection\"")) {
            fireEvent("${now()} ⚠ 收到 Connection 而非 Hello，视为已连接")
            return 1
        }
        fireEvent("${now()} ⚠ 未知响应: ${respStr.take(80)}")
        return -1
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun parseHelloResponse(json: String): Int {
        val keyIdx = json.indexOf("\"Hello\"")
        if (keyIdx < 0) return -1
        val colonIdx = json.indexOf(':', keyIdx)
        if (colonIdx < 0) return -1
        var startIdx = colonIdx + 1
        while (startIdx < json.length && json[startIdx] == ' ') startIdx++
        var endIdx = startIdx
        while (endIdx < json.length && json[endIdx] in '0'..'9') endIdx++
        return if (startIdx == endIdx) -1 else json.substring(startIdx, endIdx).toIntOrNull() ?: -1
    }

    private fun parseStringValue(json: String, key: String): String? {
        val keyStr = "\"$key\""
        val keyIdx = json.indexOf(keyStr)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx)
        if (colonIdx < 0) return null
        var startIdx = colonIdx + 1
        while (startIdx < json.length && json[startIdx] == ' ') startIdx++
        if (startIdx < json.length && json[startIdx] == '"') {
            val endIdx = json.indexOf('"', startIdx + 1)
            return if (endIdx > 0) json.substring(startIdx + 1, endIdx) else null
        }
        return null
    }

    private fun controlLoop(controlIn: InputStream, controlOut: OutputStream, runGeneration: Int, controlSocket: Socket) {
        var consecutiveTimeouts = 0
        fireEvent("${now()} ↻ 进入控制循环，等待服务器消息...")
        while (!stopRequested && generation.get() == runGeneration) {
            try {
                controlSocket.soTimeout = CONTROL_READ_TIMEOUT_MS
                val frame = readFrame(controlIn)
                if (frame == null) {
                    // readFrame 返回 null 说明流已关闭（EOF），立即断开
                    if (!stopRequested && generation.get() == runGeneration) {
                        fireEvent("${now()} ✗ 控制连接已断开（EOF）")
                    }
                    break
                }
                consecutiveTimeouts = 0
                lastHeartbeatMs = System.currentTimeMillis()
                val frameStr = String(frame, StandardCharsets.UTF_8)
                if (frameStr.contains("\"Heartbeat\"")) {
                    continue
                }
                if (frameStr.contains("\"Connection\"")) {
                    fireEvent("${now()} ⇄ 收到新连接通知")
                    val json = JSONObject(frameStr)
                    val connId = json.optString("Connection", json.opt("Connection")?.toString() ?: "")
                    if (connId.isNotBlank()) {
                        fireEvent("${now()} ⇄ 连接 ID: $connId")
                        handleDataConnection(connId, runGeneration)
                    }
                    continue
                }
                if (frameStr.contains("\"Error\"")) {
                    val json = JSONObject(frameStr)
                    val err = json.optString("Error", "unknown")
                    fireEvent("${now()} ✗ 服务器错误: $err")
                    listener?.onError("Server: $err")
                    break
                }
                if (frameStr.contains("\"Challenge\"")) {
                    fireEvent("${now()} 🔐 收到挑战认证请求")
                    if (secret != null) {
                        fireEvent("${now()} → 发送认证响应")
                        val authMsg = JSONObject().apply { put("Authenticate", secret) }.toString()
                        controlOut.write(authMsg.toByteArray(StandardCharsets.UTF_8))
                        controlOut.write(0x00)
                        controlOut.flush()
                    }
                    continue
                }
                fireEvent("${now()} ⚠ 未知消息: ${frameStr.take(100)}")
            } catch (e: SocketTimeoutException) {
                // 超时不一定是断开，检查是否超过最大连续超时次数
                consecutiveTimeouts++
                val idleSec = if (lastHeartbeatMs > 0) (System.currentTimeMillis() - lastHeartbeatMs) / 1000 else 0
                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    fireEvent("${now()} ⏱ 连续 ${MAX_CONSECUTIVE_TIMEOUTS} 次超时（空闲 ${idleSec}s），准备重连")
                    break
                }
                fireEvent("${now()} ⏱ 读超时 #${consecutiveTimeouts}（空闲 ${idleSec}s）")
            } catch (e: InterruptedException) {
                fireEvent("${now()} ⏹ 控制循环被中断")
                break
            } catch (e: Exception) {
                fireEvent("${now()} ✗ 控制连接异常: ${e.message}")
                break
            }
        }
        fireEvent("${now()} ⏹ 控制循环结束")
    }

    private fun handleDataConnection(connId: String, runGeneration: Int) {
        val t = Thread {
            var dataSocket: Socket? = null
            var localSocket: Socket? = null
            try {
                fireEvent("${now()} ⇄ 处理数据连接: $connId")
                dataSocket = Socket()
                dataSocket.connect(InetSocketAddress(boreHost, borePort), CONNECT_TIMEOUT_MS)
                fireEvent("${now()} ⇄ 数据连接已建立")
                dataSocket.soTimeout = 0
                dataSocket.tcpNoDelay = true
                val dataOut = dataSocket.getOutputStream()
                val accept = JSONObject().apply { put("Accept", connId) }.toString()
                fireEvent("${now()} → 发送 Accept")
                dataOut.write(accept.toByteArray(StandardCharsets.UTF_8))
                dataOut.write(0x00)
                dataOut.flush()
                localSocket = Socket()
                localSocket.connect(InetSocketAddress("127.0.0.1", localPort), LOCAL_CONNECT_TIMEOUT_MS)
                fireEvent("${now()} ⇄ 已连接本地 127.0.0.1:$localPort")
                localSocket.soTimeout = 0
                localSocket.tcpNoDelay = true
                val localIn = localSocket.getInputStream()
                val localOut = localSocket.getOutputStream()
                val dataIn = dataSocket.getInputStream()
                fireEvent("${now()} ⇄ 开始双向转发")
                // 用 CountDownLatch 替代 join，避免线程阻塞
                val latch = CountDownLatch(2)
                val forwarder1 = Thread {
                    try {
                        pipe(dataIn, localOut)
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }.apply { isDaemon = true; name = "bore-pipe-c2l-$connId" }
                val forwarder2 = Thread {
                    try {
                        pipe(localIn, dataOut)
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }.apply { isDaemon = true; name = "bore-pipe-l2c-$connId" }
                connectionThreads[connId] = forwarder1
                forwarder1.start()
                forwarder2.start()
                // 等待任一方向结束，最多等 30 秒
                latch.await(30, TimeUnit.SECONDS)
                // 强制关闭两端 socket 以中断另一方向的 pipe
                runCatching { dataSocket.close() }
                runCatching { localSocket.close() }
                latch.await(2, TimeUnit.SECONDS)
                fireEvent("${now()} ⇄ 数据连接 $connId 关闭 (已传输 ${totalBytesTransferred.get()} 字节)")
            } catch (e: InterruptedException) {
                fireEvent("${now()} ⇄ 数据连接 $connId 被中断")
            } catch (e: Exception) {
                fireEvent("${now()} ✗ 数据连接异常: ${e.message}")
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
        val buf = ByteArray(16384)
        while (!stopRequested) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
            val total = totalBytesTransferred.addAndGet(n.toLong())
            listener?.onBytesTransferred(total)
        }
    }

    /**
     * 读取一个 \0 结尾的 JSON 帧。
     * 优化: 检查 MAX_FRAME_LENGTH 后立即返回 null，防止恶意数据导致内存无限增长。
     * 如果 input.read() 返回 -1（流关闭），立即返回 null。
     */
    private fun readFrame(input: InputStream): ByteArray? {
        val bos = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == 0x00) break
            bos.write(b)
            if (bos.size() > MAX_FRAME_LENGTH) {
                fireEvent("${now()} ⚠ 帧超过最大长度 ${MAX_FRAME_LENGTH}，丢弃")
                return null
            }
        }
        return if (bos.size() == 0) null else bos.toByteArray()
    }

    private fun startConnectTimeoutChecker(runGeneration: Int) {
        connectTimeoutThread = Thread {
            try {
                Thread.sleep(CONNECT_TIMEOUT_TOTAL_MS.toLong())
                if (running && generation.get() == runGeneration && assignedPort < 0) {
                    fireEvent("${now()} ⏱ 连接总超时(${CONNECT_TIMEOUT_TOTAL_MS/1000}s)")
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

    /**
     * 指数退避重连: 1s → 2s → 4s → 8s → 16s → 30s (上限)
     */
    private fun scheduleReconnect(runGeneration: Int) {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            listener?.onError("max reconnect attempts reached")
            return
        }
        val attempt = reconnectAttempts.incrementAndGet()
        val delay = Math.min(RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(5)), RECONNECT_MAX_DELAY_MS.toLong())
        reconnectThread = Thread {
            try {
                fireEvent("${now()} ↻ ${delay/1000}秒后重连 (第${attempt}次，上限${MAX_RECONNECT_ATTEMPTS}次)...")
                Thread.sleep(delay)
                if (stopRequested || generation.get() != runGeneration) return@Thread
                fireEvent("${now()} ↻ 开始第${attempt}次重连...")
                start()
            } catch (_: InterruptedException) {
                fireEvent("${now()} ⏹ 重连被取消")
            }
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
        reconnectAttempts.set(0)
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    @Synchronized
    fun stop() {
        fireEvent("${now()} ⏹ 停止隧道...")
        autoReconnect = false
        running = false
        stopRequested = true
        generation.incrementAndGet()
        cancelConnectTimeout()
        reconnectThread?.interrupt()
        reconnectThread = null
        tunnelThread?.interrupt()
        tunnelThread = null
        val count = connectionThreads.size
        connectionThreads.values.forEach { it.interrupt() }
        connectionThreads.clear()
        dataExecutor?.shutdownNow()
        dataExecutor = null
        val totalBytes = totalBytesTransferred.get()
        fireEvent("${now()} ⏹ 隧道已停止（中断 $count 个数据连接，总传输 ${totalBytes} 字节）")
    }

    fun isRunning(): Boolean = running
    fun getAssignedPort(): Int = assignedPort
    fun getTotalBytesTransferred(): Long = totalBytesTransferred.get()
}