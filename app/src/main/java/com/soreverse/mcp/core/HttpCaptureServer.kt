package com.soreverse.mcp.core

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
        val reqHeaders: String = "",
        val respHeaders: String = "",
        val isWs: Boolean = false,
    ) {
        val url: String
            get() = (if (isWs) (if (isHttps) "wss" else "ws") else if (isHttps) "https" else "http") + "://" + host + path
        val replayable: Boolean get() = !isHttps && !isWs && (method.equals("GET", true) || method.equals("HEAD", true))
        /** WebSocket 明文帧（↑客户端发 / ↓服务端回），旁路解析填充。 */
        val frames: MutableList<String> = mutableListOf()
    }

    /**
     * WebSocket 帧旁路解析器（RFC6455）：透传的同时按帧切分，提取文本消息。
     * 客户端→服务端帧带 mask（需异或还原），服务端→客户端无 mask。
     */
    class WsFrameParser(private val sink: (String) -> Unit) {
        private var buf = ByteArray(0)
        private var fragmented = false

        fun feed(data: ByteArray, len: Int) {
            if (len <= 0) return
            buf = if (buf.isEmpty()) data.copyOf(len) else buf + data.copyOf(len)
            parse()
            if (buf.size > 1 shl 20) buf = ByteArray(0) // 异常保护：缓冲上限 1MB
        }

        private fun parse() {
            var pos = 0
            while (true) {
                val avail = buf.size - pos
                if (avail < 2) break
                val b0 = buf[pos].toInt() and 0xFF
                val b1 = buf[pos + 1].toInt() and 0xFF
                val opcode = b0 and 0x0F
                val maskBit = b1 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                var hdr = 2
                if (len == 126L) {
                    if (avail < 4) break
                    len = ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
                    hdr = 4
                } else if (len == 127L) {
                    if (avail < 10) break
                    len = 0
                    for (i in 0..7) len = (len shl 8) or (buf[pos + 2 + i].toInt() and 0xFF)
                    hdr = 10
                }
                if (len > 4 shl 20) { // 单帧上限 4MB，超出视为攻击/异常，丢弃缓冲
                    buf = ByteArray(0); return
                }
                val maskLen = if (maskBit) 4 else 0
                val total = hdr + maskLen + len
                if (avail < total) break
                val payload = buf.copyOfRange(pos + hdr + maskLen, pos + total)
                if (maskBit) {
                    val mask = buf.copyOfRange(pos + hdr, pos + hdr + 4)
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
                }
                val tag = when (opcode) {
                    0x0 -> if (fragmented) "cont" else "cont"
                    0x1 -> "text"; 0x2 -> "bin"
                    0x8 -> "close"; 0x9 -> "ping"; 0xA -> "pong"
                    else -> "op$opcode"
                }
                if (opcode == 0x0) fragmented = true else if (opcode in 0x1..0x2 || opcode >= 0x8) fragmented = false
                val text = when (opcode) {
                    0x1 -> String(payload, Charsets.UTF_8).take(200)
                    0x0 -> String(payload, Charsets.UTF_8).take(200)
                    else -> "${payload.size}B"
                }
                sink("[$tag] $text")
                pos += total
            }
            if (pos > 0) buf = buf.copyOfRange(pos, buf.size)
        }
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

    /** 字节级 CRLF 行读取（不预读缓冲，保证后续 body/WS 帧字节不丢失）。 */
    private fun readLineRaw(input: java.io.InputStream): String? {
        val baos = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (baos.size() == 0) null else baos.toString("UTF-8")
            if (b == 10) return baos.toString("UTF-8").trimEnd('\r')
            baos.write(b)
        }
    }

    private fun record(method: String, host: String, path: String, status: String, bytes: Long, elapsedMs: Long, isHttps: Boolean, reqHeaders: String = "", respHeaders: String = "", isWs: Boolean = false) {
        val e = Entry(formatter.format(Date()), method, host, path, status, bytes, elapsedMs, isHttps, reqHeaders, respHeaders, isWs)
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
            val clientIn = client.getInputStream()
            val requestLine = readLineRaw(clientIn) ?: return
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
                while (true) { val l = readLineRaw(clientIn) ?: break; if (l.isEmpty()) break }
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
            val path = if (url?.path.isNullOrEmpty()) "/" else (url?.path ?: "") + (if (url?.query.isNullOrEmpty()) "" else "?" + url?.query)
            val port = if (url != null && url.port > 0) url.port else 80
            if (host.isBlank()) return

            // 读取并转发 headers + body（字节级行读取）
            val headerLines = mutableListOf<String>()
            while (true) {
                val l = readLineRaw(clientIn) ?: break
                if (l.isEmpty()) break
                headerLines.add(l)
            }
            // WebSocket 升级检测 / h2c 检测（明文可见；TLS 内的 h2 无法旁路）
            val isWsUpgrade = headerLines.any { it.startsWith("Upgrade:", ignoreCase = true) && it.substringAfter(':').trim().equals("websocket", ignoreCase = true) }
            val isH2c = requestLine.startsWith("PRI * HTTP/2.0") || headerLines.any { it.startsWith("Upgrade:", ignoreCase = true) && it.substringAfter(':').trim().equals("h2c", ignoreCase = true) }
            val methodTag = when {
                isWsUpgrade -> "WS"
                isH2c -> "H2C"
                else -> method
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

            // 读响应头（字节级行读取，避免 BufferedReader 预读吞掉 body/WS 帧）
            val upIn = upstream.getInputStream()
            val statusLine = readLineRaw(upIn) ?: run { runCatching { upstream.close() }; return }
            val status = statusLine.split(" ").getOrNull(1) ?: ""
            val respHeaders = mutableListOf<String>()
            var contentLength = -1L
            while (true) {
                val l = readLineRaw(upIn) ?: break
                if (l.isEmpty()) break
                respHeaders.add(l)
                if (l.startsWith("Content-Length:", ignoreCase = true)) contentLength = l.substringAfter(':').trim().toLongOrNull() ?: -1L
            }
            val respHeaderText = statusLine + "\r\n" + respHeaders.joinToString("\r\n") + "\r\n\r\n"
            val clientOut = client.getOutputStream()
            clientOut.write(respHeaderText.toByteArray())
            clientOut.flush()
            val total0 = respHeaderText.length.toLong()

            if (isWsUpgrade && status == "101") {
                // ── WebSocket：握手成功 → 双向持久透传 + 旁路帧解析 ──
                val t0ws = System.currentTimeMillis()
                val entryWs = Entry(formatter.format(Date()), "WS", host, path, "101", 0L, 0L, false,
                    reqHeaders = headerLines.joinToString("\n"), respHeaders = statusLine + "\n" + respHeaders.joinToString("\n"), isWs = true)
                synchronized(lock) {
                    entries.addLast(entryWs)
                    while (entries.size > 500) entries.removeFirst()
                    listeners.toList().forEach { runCatching { it(entryWs) } }
                }
                val clientParser = WsFrameParser { s -> synchronized(entryWs.frames) { if (entryWs.frames.size < 200) entryWs.frames.add("↑ $s") } }
                val serverParser = WsFrameParser { s -> synchronized(entryWs.frames) { if (entryWs.frames.size < 200) entryWs.frames.add("↓ $s") } }
                val c2s = thread(name = "ws-c2s", isDaemon = true) {
                    runCatching {
                        val buf = ByteArray(8192)
                        while (running) {
                            val n = client.getInputStream().read(buf)
                            if (n < 0) break
                            clientParser.feed(buf, n)
                            upOut.write(buf, 0, n); upOut.flush()
                        }
                    }
                    runCatching { upstream.close() }
                    runCatching { client.close() }
                }
                runCatching {
                    val buf = ByteArray(8192)
                    while (running) {
                        val n = upIn.read(buf)
                        if (n < 0) break
                        serverParser.feed(buf, n)
                        clientOut.write(buf, 0, n); clientOut.flush()
                    }
                }
                runCatching { c2s.interrupt() }
                entryWs.frames.takeIf { it.isNotEmpty() }?.add("── 会话结束 ──")
                return
            }

            // ── 普通 HTTP：body 透传（CL 精确 / 否则到流结束）──
            var total = total0
            val buf = ByteArray(8192)
            var n: Int
            var sent = 0L
            while (upIn.read(buf).also { n = it } > 0) {
                clientOut.write(buf, 0, n)
                clientOut.flush()
                sent += n
                total += n
                if (contentLength > 0 && sent >= contentLength) break
            }
            record(methodTag, host, path, status, total, System.currentTimeMillis() - t0, isHttps,
                reqHeaders = headerLines.joinToString("\n"),
                respHeaders = statusLine + "\n" + respHeaders.joinToString("\n"))
            runCatching { upstream.close() }
        } catch (e: Exception) {
            // 连接中断等：忽略，不影响代理继续运行
        } finally {
            runCatching { client.close() }
        }
    }
}
