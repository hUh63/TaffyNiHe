package com.soreverse.mcp.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 塔菲逆核: 本地 HTTP 抓包代理服务器。
 *
 * 监听本地端口，转发 HTTP 流量并记录请求/响应摘要：
 *  - HTTP: 解析请求行(METHOD URL) 与响应状态码，转发数据
 *  - CONNECT(HTTPS): 建立隧道并双向转发，记录 host 与流量大小
 *
 * 使用方式: 启动后把设备 WIFI 代理设为 127.0.0.1:<port>，流量即被记录。
 * 无需 Root；HTTPS 内容为密文(仅记录 host/大小)，明文 HTTP 记录完整 URL。
 */
class HttpCaptureServer(
    private val port: Int,
) {
    data class Entry(
        val time: String,
        val method: String,
        val host: String,
        val path: String,
        val status: String,
        val bytes: Long,
        val elapsedMs: Long,
        val isHttps: Boolean,
    ) {
        val url: String get() = (if (isHttps) "https://" else "http://") + host + path
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(Entry) -> Unit>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun isRunning(): Boolean = running

    fun start(): Boolean {
        if (running) return true
        return try {
            serverSocket = ServerSocket(port)
            running = true
            thread(name = "capture-proxy", isDaemon = true) { acceptLoop() }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun addListener(l: (Entry) -> Unit) {
        synchronized(lock) { listeners.add(l) }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun entryCount(): Int = synchronized(lock) { entries.size }

    private fun acceptLoop() {
        while (running) {
            val client = try { serverSocket?.accept() } catch (e: Exception) { null }
            if (client == null) { if (!running) break; continue }
            thread(name = "capture-conn", isDaemon = true) { handle(client) }
        }
    }

    private fun record(method: String, host: String, path: String, status: String, bytes: Long, elapsedMs: Long, isHttps: Boolean) {
        val e = Entry(formatter.format(Date()), method, host, path, status, bytes, elapsedMs, isHttps)
        synchronized(lock) {
            entries.addLast(e)
            while (entries.size > 500) entries.removeFirst()
            val snapshot = listeners.toList()
            snapshot.forEach { runCatching { it(e) } }
        }
    }

    private fun handle(client: Socket) {
        val t0 = System.currentTimeMillis()
        try {
            client.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val target = parts[1]

            if (method.equals("CONNECT", ignoreCase = true)) {
                // HTTPS 隧道：连接目标并双向转发
                val hostPort = target
                val hp = hostPort.lastIndexOf(':')
                val host = if (hp > 0) hostPort.substring(0, hp) else hostPort
                val port = if (hp > 0) hostPort.substring(hp + 1).toIntOrNull() ?: 443 else 443
                // 跳过 CONNECT 的 headers
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val upstream = try { Socket(host, port) } catch (e: Exception) {
                    runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) }
                    return
                }
                upstream.soTimeout = 30000
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                val clientOut = client.getOutputStream()
                val upstreamIn = upstream.getInputStream()
                // 转发 upstream -> client，同时统计字节
                val relay = thread(name = "capture-relay", isDaemon = true) {
                    runCatching {
                        val buf = ByteArray(8192)
                        while (running) {
                            val n = upstreamIn.read(buf)
                            if (n < 0) break
                            clientOut.write(buf, 0, n)
                            clientOut.flush()
                        }
                    }
                }
                // 转发 client -> upstream
                runCatching {
                    val buf = ByteArray(8192)
                    var sent = 0L
                    while (running) {
                        val n = client.getInputStream().read(buf)
                        if (n < 0) break
                        upstream.getOutputStream().write(buf, 0, n)
                        upstream.getOutputStream().flush()
                        sent += n
                    }
                    relay.join(2000)
                    record(method, host, "", "", sent, System.currentTimeMillis() - t0, isHttps = true)
                }
                runCatching { upstream.close() }
                return
            }

            // HTTP：解析完整 URL
            val isHttps = false
            val url = try { java.net.URI(target) } catch (e: Exception) { null }
            val host = url?.host ?: ""
            val path = if (url?.path.isNullOrEmpty()) "/" else url.path + (if (url.query.isNullOrEmpty()) "" else "?" + url.query)
            val port = if (url?.port ?: -1 > 0) url.port else 80
            if (host.isBlank()) return

            // 读取并转发 headers + body
            val headerLines = mutableListOf<String>()
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
                headerLines.add(l)
            }
            val upstream = try { Socket(host, port) } catch (e: Exception) { return }
            upstream.soTimeout = 30000
            val upOut = upstream.getOutputStream()
            val sb = StringBuilder()
            sb.append(requestLine).append("\r\n")
            headerLines.forEach { sb.append(it).append("\r\n" ) }
            sb.append("Connection: close\r\n\r\n")
            upOut.write(sb.toString().toByteArray())
            upOut.flush()

            // 读响应：解析状态码
            val upReader = BufferedReader(InputStreamReader(upstream.getInputStream()))
            val statusLine = upReader.readLine() ?: runCatching { upstream.close() }.let { return }
            val status = statusLine.split(" ").getOrNull(1) ?: ""
            val respHeaders = mutableListOf<String>()
            var contentLength = -1L
            while (true) {
                val l = upReader.readLine() ?: break
                if (l.isEmpty()) break
                respHeaders.add(l)
                if (l.startsWith("Content-Length:", ignoreCase = true)) contentLength = l.substringAfter(':').trim().toLongOrNull() ?: -1
            }
            val respHeaderText = statusLine + "\r\n" + respHeaders.joinToString("\r\n") + "\r\n\r\n"
            client.getOutputStream().write(respHeaderText.toByteArray())
            client.getOutputStream().flush()

            // 转发 body
            var total = respHeaderText.length.toLong()
            val buf = ByteArray(8192)
            var n: Int
            var sent = 0L
            while (upstream.getInputStream().read(buf).also { n = it } > 0) {
                client.getOutputStream().write(buf, 0, n)
                client.getOutputStream().flush()
                sent += n
                total += n
                if (contentLength > 0 && sent >= contentLength) break
            }
            record(method, host, path, status, total, System.currentTimeMillis() - t0, isHttps)
            runCatching { upstream.close() }
        } catch (e: Exception) {
            // 连接中断等：忽略，不影响代理继续运行
        } finally {
            runCatching { client.close() }
        }
    }
}
