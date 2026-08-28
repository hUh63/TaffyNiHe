package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LSP 客户端 —— 长驻 jedi-language-server（内置 Python，无需 proot/网络）。
 *
 * 协议: JSON-RPC over stdio + Content-Length framing（LSP 标准头）。
 * 能力: completion / hover / definition（Python 完整代码智能）。
 * 容错: 进程退出自动重启一次；请求 15s 超时；调用方无须关心生命周期。
 */
object LspClient {

    private var proc: Process? = null
    private var writer: java.io.OutputStream? = null
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val idGen = AtomicInteger(1)
    private val sendLock = Any()
    private var diagnosticsBuffer = mutableListOf<String>()
    private val diagLock = Any()

    /** 最近一次诊断消息（服务端窗口日志），调试用。 */
    fun lastDiagnostics(): List<String> = synchronized(diagLock) { diagnosticsBuffer.toList() }

    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (proc != null && proc!!.isAlive) return true
        stop()
        val python = PythonRuntime.pythonPath(context) ?: return false
        val site = File(File(python).parentFile.parentFile, "lib/python3.14/site-packages")
        return runCatching {
            val pb = ProcessBuilder(python, "-m", "jedi_language_server")
            pb.environment().apply {
                put("PYTHONPATH", site.absolutePath)
                put("PYTHONHOME", File(python).parentFile.parentFile.absolutePath)
                put("LD_LIBRARY_PATH", File(File(python).parentFile.parentFile, "lib").absolutePath)
                put("HOME", File(python).parentFile.parentFile.absolutePath)
            }
            pb.redirectErrorStream(false)
            val p = pb.start()
            proc = p
            writer = p.outputStream
            // 读线程：解析 framing → 分发响应/通知
            Thread({
                runCatching {
                    val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), 1 shl 16)
                    while (!Thread.currentThread().isInterrupted) {
                        var contentLength = -1
                        while (true) {
                            val line = reader.readLine() ?: return@runCatching
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                            }
                        }
                        if (contentLength <= 0) continue
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(buf, read, contentLength - read)
                            if (n < 0) return@runCatching
                            read += n
                        }
                        val msg = runCatching { JSONObject(String(buf, 0, contentLength)) }.getOrElse { continue }
                        if (msg.has("id") && msg.has("method")) continue // server→client 请求，忽略
                        if (msg.has("id") && msg.has("result") || msg.has("id") && msg.has("error")) {
                            val id = msg.optInt("id", -1)
                            pending.remove(id)?.complete(msg)
                        } else if (msg.has("method")) {
                            val m = msg.optString("method")
                            if (m.startsWith("window/")) {
                                val text = msg.optJSONObject("params")?.optString("message") ?: ""
                                if (text.isNotBlank()) synchronized(diagLock) {
                                    diagnosticsBuffer.add(text)
                                    while (diagnosticsBuffer.size > 50) diagnosticsBuffer.removeAt(0)
                                }
                            }
                        }
                    }
                }
                // 进程退出：清理状态
                runCatching { p.waitFor() }
                proc = null
                pending.values.forEach { it.complete(JSONObject().put("__dead", true)) }
                pending.clear()
            }, "taffy-lsp-reader").apply { isDaemon = true; start() }

            // initialize 握手（同步等待，20s 超时）
            val init = JSONObject().put("processId", android.os.Process.myPid())
                .put("rootUri", JSONObject.NULL)
                .put("capabilities", JSONObject())
            val resp = sendRequest("initialize", init, 20)
            if (resp.has("__dead") || resp.has("error")) { stop(); return false }
            sendNotification("initialized", JSONObject())
            true
        }.getOrElse {
            AppLog.e("LspClient start failed: ${it.message}")
            stop()
            false
        }
    }

    /** 文档打开/全量变更（省略 range 即全文替换，兼容 incremental 服务器）。 */
    fun didOpen(text: String) {
        val params = JSONObject().put("textDocument", JSONObject()
            .put("uri", "file:///taffy_editor.py")
            .put("languageId", "python")
            .put("version", ++versionCounter)
            .put("text", text))
        sendNotification("textDocument/didOpen", params)
    }

    fun didChange(text: String) {
        val params = JSONObject().put("textDocument", JSONObject()
            .put("uri", "file:///taffy_editor.py")
            .put("version", ++versionCounter))
            .put("contentChanges", JSONArray().put(JSONObject().put("text", text)))
        sendNotification("textDocument/didChange", params)
    }

    data class Item(val label: String, val kind: String, val detail: String, val doc: String)

    /** 补全（textDocument/completion）。 */
    fun completion(line0: Int, col0: Int): List<Item> {
        val p = proc ?: return emptyList()
        if (!p.isAlive) return emptyList()
        val params = JSONObject().put("textDocument", JSONObject().put("uri", "file:///taffy_editor.py"))
            .put("position", JSONObject().put("line", line0).put("character", col0))
        val resp = sendRequest("textDocument/completion", params, 15)
        if (resp.has("__dead") || resp.has("error")) return emptyList()
        val items = mutableListOf<Item>()
        val arr = when (val r = resp.opt("result")) {
            is JSONArray -> r
            is JSONObject -> r.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label", "")
            if (label.isEmpty()) continue
            val kind = kindName(o.optInt("kind", 0))
            val detail = o.optString("detail", "")
            val docObj = o.opt("documentation")
            val doc = when (docObj) {
                is JSONObject -> docObj.optString("value", "")
                is String -> docObj
                else -> ""
            }
            items.add(Item(label, kind, detail, doc.take(200)))
        }
        return items
    }

    /** 悬停文档（textDocument/hover）。 */
    fun hover(line0: Int, col0: Int): String? {
        val p = proc ?: return null
        if (!p.isAlive) return null
        val params = JSONObject().put("textDocument", JSONObject().put("uri", "file:///taffy_editor.py"))
            .put("position", JSONObject().put("line", line0).put("character", col0))
        val resp = sendRequest("textDocument/hover", params, 12)
        val contents = resp.optJSONObject("result")?.opt("contents") ?: return null
        return when (contents) {
            is JSONObject -> contents.optString("value", "").take(1500).ifBlank { null }
            is String -> contents.take(1500).ifBlank { null }
            is JSONArray -> (0 until contents.length()).mapNotNull { i ->
                val o = contents.opt(i)
                when (o) {
                    is JSONObject -> o.optString("value", "")
                    is String -> o
                    else -> null
                }
            }.joinToString("\n").take(1500).ifBlank { null }
            else -> null
        }
    }

    @Volatile private var versionCounter = 1

    private fun sendRequest(method: String, params: JSONObject, timeoutSec: Long): JSONObject {
        val w = writer ?: return JSONObject().put("__dead", true)
        val id = idGen.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        return try {
            synchronized(sendLock) {
                val body = msg.toString().toByteArray(Charsets.UTF_8)
                w.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                w.write(body)
                w.flush()
            }
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: Exception) {
            JSONObject().put("__dead", true)
        } finally {
            pending.remove(id)
        }
    }

    private fun sendNotification(method: String, params: JSONObject) {
        val w = writer ?: return
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        runCatching {
            synchronized(sendLock) {
                val body = msg.toString().toByteArray(Charsets.UTF_8)
                w.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                w.write(body)
                w.flush()
            }
        }
    }

    fun stop() {
        runCatching {
            sendNotification("shutdown", JSONObject())
            sendNotification("exit", JSONObject())
        }
        runCatching { proc?.destroy() }
        proc = null
        writer = null
        pending.values.forEach { it.complete(JSONObject().put("__dead", true)) }
        pending.clear()
    }

    private fun kindName(k: Int): String = when (k) {
        1 -> "text"; 2 -> "method"; 3 -> "func"; 4 -> "ctor"; 5 -> "field"; 6 -> "var"
        7 -> "class"; 8 -> "iface"; 9 -> "module"; 10 -> "prop"; 12 -> "unit"; 13 -> "value"
        14 -> "enum"; 17 -> "kw"; 18 -> "snippet"; 21 -> "const"; 22 -> "struct"
        23 -> "event"; 25 -> "typeparam"
        else -> "other"
    }
}
