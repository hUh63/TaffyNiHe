package com.soreverse.mcp.core

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 外部 MCP 服务端客户端（Streamable HTTP + SSE 兼容）。
 *
 * 用途：把一个跑在别处（PC / 另一台设备 / 局域网）的 MCP 服务接进来，
 * 列它的工具、调它的工具——塔菲自己既是 MCP 服务端，也能当客户端。
 *
 * 协议按 MCP 规范走：`initialize` 拿 `Mcp-Session-Id` → `notifications/initialized`
 * → `tools/list` / `tools/call`。服务端返回 SSE（text/event-stream）时按 `data:` 行解析。
 */
data class McpClientEndpoint(
    val id: String,
    val name: String,
    val url: String,
    val headers: String = "",
    val enabled: Boolean = true,
)

/** 端点持久化（SharedPreferences 里存一个 JSON 数组）。 */
object McpClientStore {
    private const val PREFS = "taffy_mcp_client"
    private const val KEY = "endpoints"

    fun load(ctx: Context): MutableList<McpClientEndpoint> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val out = ArrayList<McpClientEndpoint>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                out.add(
                    McpClientEndpoint(
                        id = o.optString("id").ifBlank { url },
                        name = o.optString("name").ifBlank { url },
                        url = url,
                        headers = o.optString("headers"),
                        enabled = o.optBoolean("enabled", true),
                    )
                )
            }
        }
        return out
    }

    fun save(ctx: Context, list: List<McpClientEndpoint>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject()
                .put("id", e.id)
                .put("name", e.name)
                .put("url", e.url)
                .put("headers", e.headers)
                .put("enabled", e.enabled))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, ep: McpClientEndpoint) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == ep.id || (ep.url.isNotBlank() && it.url == ep.url) }
        if (idx >= 0) list[idx] = ep else list.add(ep)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String): Boolean {
        val list = load(ctx)
        val removed = list.removeAll { it.id == id || it.url == id }
        if (removed) save(ctx, list)
        return removed
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean): Boolean {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == id || it.url == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(enabled = enabled)
        save(ctx, list)
        return true
    }

    fun resolve(ctx: Context, key: String): McpClientEndpoint? {
        val list = load(ctx)
        val direct = list.firstOrNull { it.id == key || it.url == key || it.name == key }
        if (direct != null) return direct
        if (key.isBlank()) return list.firstOrNull { it.enabled }
        return null
    }
}

/** 一次客户端会话：握手 + 工具列表 + 工具调用。 */
class McpClientSession(private val endpoint: McpClientEndpoint) {

    var sessionId: String? = null
        private set

    /** 最近一次失败原因（成功时为空）。调用方在拿到 null 时读它。 */
    var lastError: String = ""
        private set

    private var nextId = 1

    fun initialize(): Boolean {
        lastError = ""
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "taffy-nihe").put("version", "1.0"))
        val body = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", "initialize").put("params", params)
        val (json, headers) = post(body) ?: return false
        if (json.optJSONObject("error") != null) {
            lastError = json.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "initialize 返回错误" }
            return false
        }
        sessionId = headers["Mcp-Session-Id"] ?: headers["mcp-session-id"]
        // 规范要求握手后发一条 initialized 通知；失败不致命
        runCatching {
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        }
        return true
    }

    fun listTools(): JSONArray? {
        if (sessionId == null && !initialize()) return null
        lastError = ""
        var result = rpc("tools/list", JSONObject())
        if (result == null) {
            // session 过期：重连一次
            sessionId = null
            if (!initialize()) return null
            result = rpc("tools/list", JSONObject())
        }
        if (result == null) return null
        return result.optJSONObject("result")?.optJSONArray("tools")
    }

    /** 调用工具，返回拼接后的文本（MCP 的 content[] 里 type=text 的 text）。 */
    fun callTool(name: String, arguments: JSONObject): String? {
        if (sessionId == null && !initialize()) return null
        lastError = ""
        val params = JSONObject().put("name", name).put("arguments", arguments)
        var result = rpc("tools/call", params)
        if (result == null) {
            sessionId = null
            if (!initialize()) return null
            result = rpc("tools/call", params)
        }
        if (result == null) return null
        val err = result.optJSONObject("error")
        if (err != null) {
            lastError = err.optString("message").ifBlank { "工具返回 error" }
            return null
        }
        val res = result.optJSONObject("result") ?: run {
            lastError = "响应里没有 result 字段"
            return null
        }
        val content = res.optJSONArray("content")
        if (content == null) {
            val text = res.optString("text")
            if (text.isNotBlank()) return text
            lastError = "响应里没有 content 字段"
            return null
        }
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val type = item.optString("type")
            if (type == "text" || type.isEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(item.optString("text"))
            }
        }
        return sb.toString()
    }

    /** 只做握手，用来测连通性与延迟。 */
    fun ping(): Boolean = initialize()

    private fun rpc(method: String, params: JSONObject): JSONObject? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", method).put("params", params)
        return post(body)?.first
    }

    private fun post(body: JSONObject): Pair<JSONObject, Map<String, String>>? {
        return runCatching {
            val builder = Request.Builder().url(endpoint.url)
                .post(body.toString().toRequestBody(JSON))
            for ((k, v) in parseHeaders(endpoint.headers)) builder.header(k, v)
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            CLIENT.newCall(builder.build()).execute().use { resp ->
                val headers = LinkedHashMap<String, String>()
                for (name in resp.headers.names()) {
                    val v = resp.header(name)
                    if (v != null) headers[name] = v
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = "HTTP ${resp.code}" + if (text.isNotBlank()) "：" + text.take(300) else ""
                    return@use null
                }
                val json = extractJson(text) ?: run {
                    lastError = if (text.isBlank()) "服务端返回空响应" else "响应不是 JSON：" + text.take(200)
                    null
                } ?: return@use null
                json to headers
            }
        }.getOrElse {
            lastError = "连接失败：" + (it.message ?: it.javaClass.simpleName)
            null
        }
    }

    /** 兼容两种响应：纯 JSON，或 SSE（data: {...} 多行，取最后一个能解析的完整 JSON）。 */
    private fun extractJson(text: String): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        var last: JSONObject? = null
        var buf = StringBuilder()
        for (line in trimmed.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("data:")) {
                val payload = l.removePrefix("data:").trim()
                if (payload == "[DONE]") continue
                // 单行完整 JSON 或分块累积
                val direct = runCatching { JSONObject(payload) }.getOrNull()
                if (direct != null) {
                    last = direct
                    buf = StringBuilder()
                } else {
                    buf.append(payload)
                    val acc = runCatching { JSONObject(buf.toString()) }.getOrNull()
                    if (acc != null) {
                        last = acc
                        buf = StringBuilder()
                    }
                }
            } else if (l.startsWith("event:") || l.isEmpty()) {
                continue
            } else if (buf.isNotEmpty()) {
                buf.append(l)
                val acc = runCatching { JSONObject(buf.toString()) }.getOrNull()
                if (acc != null) {
                    last = acc
                    buf = StringBuilder()
                }
            }
        }
        return last
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val idx = t.indexOf(':')
            if (idx <= 0) continue
            val k = t.substring(0, idx).trim()
            val v = t.substring(idx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val PROTOCOL_VERSION = "2025-06-18"

        private val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(150, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
