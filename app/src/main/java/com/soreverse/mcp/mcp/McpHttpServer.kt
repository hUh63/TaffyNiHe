package com.soreverse.mcp.mcp

import android.content.Context
import com.soreverse.mcp.BuildConfig
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.ApkMcpBridge
import com.soreverse.mcp.core.CloudflareTunnelManager
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.obj
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.ToolStats
import com.soreverse.mcp.nativecore.NativeEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class McpHttpServer(private val context: Context, private val port: Int, private val host: String) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default
    private val startedAt = System.currentTimeMillis()
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile private var heavyPermits = 1
    private var heavyGate: Semaphore = Semaphore(1)

    // v2.1.0: Session management for Streamable HTTP transport
    private val sessions = ConcurrentHashMap<String, Long>() // sessionId -> createdAt
    private val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Per-tool sliding-window rate limiter. A 60s deque of call timestamps
     * per tool name; entries older than 60s are evicted lazily on each probe.
     * When `SettingsStore.toolCallRateLimitPerMin > 0`, callTool rejects new
     * calls for that tool once the in-window count reaches the limit. The
     * limiter is independent of the heavy concurrency gate so that even
     * lightweight tools (meta_info help/tools/describe) can be throttled.
     */
    private object RateLimiter {
        private val window = 60_000L
        private val lock = Any()
        private val byTool = HashMap<String, ArrayDeque<Long>>()
        fun tryAcquire(name: String, limit: Int): Boolean = synchronized(lock) {
            if (limit <= 0) return@synchronized true
            val now = System.currentTimeMillis()
            val timestamps = byTool.getOrPut(name) { ArrayDeque() }
            while (timestamps.firstOrNull()?.let { now - it > window } == true) timestamps.removeFirst()
            if (timestamps.size >= limit) return@synchronized false
            timestamps.addLast(now)
            true
        }
        fun reset(name: String? = null) = synchronized(lock) {
            if (name == null) byTool.clear() else byTool.remove(name)
        }
    }

    /**
     * Reconfigure the heavy-tool concurrency gate. Lower permits ⇒ stricter
     * serialization (default 1 — one big analysis/patch at a time, same
     * conservative behavior as before). Bumping permits lets a host pipeline
     * parallel read/inspect calls while one heavy edit runs. Caller must
     * reconfigure to current settings on every start.
     */
    fun reconfigureHeavyPermits(permits: Int) {
        val p = permits.coerceIn(1, 16)
        if (p == heavyPermits) return
        heavyPermits = p
        heavyGate = Semaphore(p)
        AppLog.i("heavy tool gate permits=$p")
    }

    val apkBridge: ApkMcpBridge get() = bridgeHolder ?: ApkMcpBridge(SettingsStore(context)).also { bridgeHolder = it }
    private var bridgeHolder: ApkMcpBridge? = null
    val tunnel: CloudflareTunnelManager get() = tunnelHolder ?: CloudflareTunnelManager(context, SettingsStore(context)).also { tunnelHolder = it }
    private var tunnelHolder: CloudflareTunnelManager? = null

    fun ensureBridgeProbed() {
        val s = SettingsStore(context)
        if (!s.apkMcpAutoProbe) return
        val configs = s.apkMcpConfigs
        if (configs.isNotEmpty()) {
            val st = apkBridge.state()
            if (!st.online) Thread { apkBridge.probe() }.start()
        } else {
            Thread { apkBridge.autoDiscover(ApkMcpBridge.DEFAULT_PORT) }.start()
        }
    }

    fun start() {
        if (engine != null) return
        NativeEngine.select(SettingsStore(context).nativeBackend)
        engine = embeddedServer(CIO, host = host, port = port) {
            routing {
                // v2.1.0: CORS preflight OPTIONS handler
                options("/mcp") {
                    call.response.headers.apply {
                        append("Access-Control-Allow-Origin", "*")
                        append("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
                        append("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id, Accept")
                        append("Access-Control-Max-Age", "86400")
                    }
                    call.respondText("", status = HttpStatusCode.NoContent)
                }

                // 安全修复: 所有端点均需认证，防止服务器信息泄露。
                get("/") {
                    if (!call.authorized()) {
                        call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    call.respondText(serverDiscovery().toString(), ContentType.Application.Json)
                }
                get("/.well-known/mcp") {
                    if (!call.authorized()) {
                        call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    call.respondText(serverDiscovery().toString(), ContentType.Application.Json)
                }
                get("/health") {
                    // /health 端点仅返回最小化健康状态，不暴露服务器信息
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }
                // GET /mcp - SSE if Accept: text/event-stream, otherwise return status page for browser testing
                get("/mcp") {
                    if (!call.authorized()) {
                        call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val accept = call.request.header("Accept").orEmpty()
                    if (accept.contains("text/event-stream")) {
                        // SSE long connection with heartbeat
                        handleSseConnection(call)
                    } else {
                        // Browser test: return a simple HTML status page
                        val html = browserStatusHtml()
                        call.respondText(html, ContentType.Text.Html)
                    }
                }
                get("/sse") {
                    if (!call.authorized()) {
                        call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    // v2.1.0: SSE long connection with heartbeat
                    handleSseConnection(call)
                }
                // v2.1.0: POST /mcp with session management
                post("/mcp") {
                    handleJsonRpcPost(call)
                }
                post("/rpc") {
                    handleJsonRpcPost(call)
                }
                post("/messages") {
                    handleJsonRpcPost(call)
                }
                // v2.1.0: DELETE /mcp for session termination
                delete("/mcp") {
                    if (!call.authorized()) {
                        call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                        return@delete
                    }
                    val sessionId = call.request.header("Mcp-Session-Id")
                    if (sessionId != null) {
                        sessions.remove(sessionId)
                        AppLog.i("MCP session terminated: $sessionId")
                    }
                    call.respondText("", status = HttpStatusCode.NoContent)
                }
            }
        }.start(wait = false)
        val settings = SettingsStore(context)
        reconfigureHeavyPermits(settings.maxConcurrentTools)
        ToolStats.setPersistEnabled(settings.toolStatsPersist)
        AppLog.i("Ktor MCP server listening on $host:$port/mcp (permits=${settings.maxConcurrentTools})")
        ensureBridgeProbed()
        apkBridge.startHealthMonitor()
    }

    fun stop() {
        apkBridge.stopHealthMonitor()
        engine?.stop(gracePeriodMillis = 400, timeoutMillis = 1_500)
        engine = null
        AppLog.i("Ktor MCP server stopped")
    }

    private suspend fun handleSseConnection(call: ApplicationCall) {
        // v2.1.0: SSE long connection with 15s heartbeat
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = System.currentTimeMillis()
        AppLog.i("SSE connection established: session=$sessionId")

        call.response.headers.apply {
            append("Cache-Control", "no-cache")
            append("Connection", "keep-alive")
            append("Mcp-Session-Id", sessionId)
        }

        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            // Send initial SSE hello with session ID
            val endpoint = JSONObject().put("uri", "/mcp").put("method", "POST")
            writeStringUtf8("event: endpoint\ndata: $endpoint\n\n: Taffy ready (session=$sessionId)\n\n")
            flush()

            // Keep connection alive with heartbeats until timeout
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SESSION_TIMEOUT_MS) {
                delay(15_000) // 15s heartbeat
                val sessionAge = System.currentTimeMillis() - (sessions[sessionId] ?: 0)
                if (sessionAge > SESSION_TIMEOUT_MS) {
                    sessions.remove(sessionId)
                    AppLog.i("SSE session expired: $sessionId")
                    break
                }
                try {
                    writeStringUtf8(": heartbeat\n\n")
                    flush()
                } catch (e: Exception) {
                    AppLog.w("SSE heartbeat write failed: ${e.message}")
                    break
                }
            }
        }

        sessions.remove(sessionId)
        AppLog.i("SSE client disconnected: session=$sessionId")
    }

    private suspend fun handleJsonRpcPost(call: ApplicationCall) {
        // 先读取请求体，以便在鉴权失败时也能返回带正确 id 的错误响应
        val settings = SettingsStore(context)
        val maxBytes = settings.maxRequestKb * 1024
        val contentLength = call.request.header("Content-Length")?.toLongOrNull()
        if (contentLength != null && contentLength > maxBytes) {
            call.respondText(requestTooLarge(maxBytes).toString(), ContentType.Application.Json, status = HttpStatusCode.PayloadTooLarge)
            return
        }
        val body = call.receiveText()
        if (body.toByteArray(Charsets.UTF_8).size > maxBytes) {
            call.respondText(requestTooLarge(maxBytes).toString(), ContentType.Application.Json, status = HttpStatusCode.PayloadTooLarge)
            return
        }

        if (!call.authorized()) {
            // 解析请求 id，返回带正确 id 的错误响应，避免客户端无法匹配
            val reqId = try { JSONObject(body).opt("id") } catch (_: Throwable) { JSONObject.NULL }
            val errResp = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", reqId ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", -32001).put("message", "Unauthorized: missing or invalid Taffy token"))
            call.response.headers.append("WWW-Authenticate", "Bearer")
            call.respondText(errResp.toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
            return
        }

        // v2.1.0: Session management - create or validate session
        val existingSessionId = call.request.header("Mcp-Session-Id")
        val sessionId = existingSessionId ?: UUID.randomUUID().toString()
        sessions[sessionId] = System.currentTimeMillis()
        call.response.headers.append("Mcp-Session-Id", sessionId)

        // Clean up expired sessions
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value > SESSION_TIMEOUT_MS }

        val response = dispatchBody(body)
        // Notifications produce no response body: reply 202 Accepted with an
        // empty body per the MCP streamable-HTTP spec. Sending a JSON-RPC
        // response (or an id=null result) for a notification breaks strict
        // clients such as rmcp.
        if (response === NoResponse) {
            call.respondText("", ContentType.Application.Json, status = HttpStatusCode.Accepted)
            return
        }
        val accept = call.request.header("Accept").orEmpty()
        if (accept.contains("text/event-stream")) {
            call.respondText("event: message\ndata: $response\n\n", ContentType.Text.EventStream)
        } else {
            call.respondText(response.toString(), ContentType.Application.Json)
        }
    }

    private fun serverDiscovery(): JSONObject = JSONObject()
        .put("ok", true)
        .put("name", "Taffy")
        .put("protocol", "MCP JSON-RPC 2.0")
        .put("endpoint", "/mcp")
        .put("sseEndpoint", "/sse")
        .put("messagesEndpoint", "/messages")
        .put("methods", JSONArray(listOf("initialize", "notifications/initialized", "ping", "tools/list", "tools/call", "resources/list", "prompts/list")))
        .put("hint", "POST JSON-RPC to /mcp. GET /mcp with Accept: text/event-stream returns an SSE compatibility hello.")

    private fun browserStatusHtml(): String {
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        val bridge = apkBridge.state()
        val bridgeStatus = if (bridge.online) "Online (${bridge.tools.size} tools)" else "Offline"
        val tunnelUrl = tunnel.status().publicUrl?.takeIf { it.isNotBlank() } ?: "Not configured"

        // Build tool catalog grouped by category
        val cats = ToolCatalogPresentation.categoryDescriptions(false)
        val grouped = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        cats.forEach { (cat, _) -> grouped[cat] = mutableListOf() }
        ToolCatalog.ALL.forEach { e -> grouped[e.meta.category]?.add(e.meta.name to e.meta.en) }
        if (bridge.online) {
            grouped["apk-bridge"] = bridge.tools.map { it.name to (it.description ?: it.name) }.toMutableList()
        }

        val totalTools = ToolCatalog.ALL.size + if (bridge.online) bridge.tools.size else 0
        val toolCards = StringBuilder()
        grouped.forEach { (cat, tools) ->
            if (tools.isEmpty()) return@forEach
            val catLabel = cats.firstOrNull { it.first == cat }?.second ?: cat
            toolCards.append("<div class='cat-group'>")
            toolCards.append("<div class='cat-header'><span class='cat-name'>$catLabel</span><span class='cat-count'>${tools.size}</span></div>")
            toolCards.append("<div class='cat-tools'>")
            tools.forEach { (name, desc) ->
                val shortDesc = desc.take(60)
                toolCards.append("<div class='tool-item'><code>$name</code><span class='tool-desc'>$shortDesc</span></div>")
            }
            toolCards.append("</div></div>")
        }

        // Build tool call history from ToolStats
        val stats = ToolStats.snapshot()
        val totalCalls = stats.optLong("totalCalls", 0)
        val totalOk = stats.optLong("totalOk", 0)
        val totalFailed = stats.optLong("totalFailed", 0)
        val distinctTools = stats.optInt("distinctTools", 0)
        val toolsArr = stats.optJSONArray("tools")
        val historyRows = StringBuilder()
        if (toolsArr != null && toolsArr.length() > 0) {
            val entries = (0 until toolsArr.length()).mapNotNull { i ->
                val obj = toolsArr.optJSONObject(i) ?: return@mapNotNull null
                Triple(obj.optString("tool"), obj.optLong("calls"), obj.optLong("failed"))
            }.sortedByDescending { it.second }
            entries.take(20).forEach { (name, calls, failed) ->
                val status = if (failed > 0) "has-fail" else "all-ok"
                val failInfo = if (failed > 0) " <span class='fail-badge'>$failed fail</span>" else ""
                historyRows.append("<div class='row $status'><span class='label'><code>$name</code></span><span class='value'>$calls calls$failInfo</span></div>")
            }
        } else {
            historyRows.append("<p class='hint'>No tool calls recorded yet.</p>")
        }

        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Taffy MCP Status</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;max-width:680px;margin:40px auto;padding:0 20px;background:#f5f5f5;color:#333}
.card{background:#fff;border-radius:12px;padding:24px;box-shadow:0 1px 3px rgba(0,0,0,.1);margin-bottom:16px}
h1{font-size:24px;margin:0 0 8px;color:#FF375F}
h2{font-size:18px;margin:0 0 12px;color:#333}
.status{display:inline-block;padding:4px 12px;border-radius:20px;font-size:13px;font-weight:600}
.ok{background:#e8f5e9;color:#2e7d32}
.off{background:#fbe9e7;color:#c62828}
.row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #eee;align-items:center}
.row:last-child{border:none}
.label{color:#666;font-size:14px}
.value{font-weight:500;font-size:14px}
code{background:#f0f0f0;padding:2px 6px;border-radius:4px;font-size:13px}
.hint{font-size:13px;color:#999;margin-top:16px}
.stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:16px}
.stat-box{text-align:center;padding:12px;border-radius:8px;background:#f8f8f8}
.stat-num{font-size:24px;font-weight:700;color:#FF375F}
.stat-label{font-size:12px;color:#999;margin-top:4px}
.cat-group{margin-bottom:16px}
.cat-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;padding-bottom:4px;border-bottom:2px solid #f0f0f0}
.cat-name{font-weight:600;font-size:14px;color:#333}
.cat-count{background:#FF375F;color:#fff;border-radius:12px;padding:2px 10px;font-size:12px;font-weight:600}
.cat-tools{display:flex;flex-direction:column;gap:4px}
.tool-item{display:flex;gap:8px;align-items:baseline;padding:4px 0}
.tool-item code{min-width:180px;font-size:12px}
.tool-desc{font-size:12px;color:#888;flex:1}
.fail-badge{background:#fbe9e7;color:#c62828;padding:1px 6px;border-radius:4px;font-size:11px}
.row.has-fail{background:#fff5f5}
</style></head><body>
<div class="card">
<h1>Taffy MCP Server</h1>
<span class="status ok">Running</span>
<div class="row"><span class="label">Endpoint</span><span class="value"><code>$host:$port/mcp</code></span></div>
<div class="row"><span class="label">Uptime</span><span class="value">${uptime}s</span></div>
<div class="row"><span class="label">APK Bridge</span><span class="value">$bridgeStatus</span></div>
<div class="row"><span class="label">Tunnel</span><span class="value">$tunnelUrl</span></div>
</div>
<div class="card">
<div class="row"><span class="label">POST /mcp</span><span class="value">JSON-RPC 2.0</span></div>
<div class="row"><span class="label">GET /mcp (SSE)</span><span class="value">Accept: text/event-stream</span></div>
<div class="row"><span class="label">GET /sse</span><span class="value">SSE connection</span></div>
<div class="row"><span class="label">GET /health</span><span class="value">Health check</span></div>
</div>
<div class="card">
<h2>Tool Catalog ($totalTools tools)</h2>
$toolCards
</div>
<div class="card">
<h2>Tool Call History</h2>
<div class="stat-grid">
<div class="stat-box"><div class="stat-num">$totalCalls</div><div class="stat-label">Total Calls</div></div>
<div class="stat-box"><div class="stat-num" style="color:#2e7d32">$totalOk</div><div class="stat-label">Success</div></div>
<div class="stat-box"><div class="stat-num" style="color:#c62828">$totalFailed</div><div class="stat-label">Failed</div></div>
<div class="stat-box"><div class="stat-num" style="color:#5E5CE6">$distinctTools</div><div class="stat-label">Tools Used</div></div>
</div>
$historyRows
</div>
<p class="hint">This page confirms the server is running. Use an MCP client to interact with the tools. Stats update on page refresh.</p>
</body></html>"""
    }

    private fun sseHello(): String {
        val endpoint = JSONObject().put("uri", "/messages").put("method", "POST")
        return "event: endpoint\ndata: $endpoint\n\n: Taffy ready\n\n"
    }

    private fun dispatchBody(body: String): Any {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return jsonRpcError(JSONObject.NULL, -32700, "Parse error")
        if (trimmed.startsWith("[")) {
            val arr = try {
                JSONArray(trimmed)
            } catch (_: JSONException) {
                return jsonRpcError(JSONObject.NULL, -32700, "Parse error")
            }
            val out = JSONArray()
            if (arr.length() == 0) return jsonRpcError(JSONObject.NULL, -32600, "Invalid Request")
            for (i in 0 until arr.length()) {
                val req = arr.optJSONObject(i)
                val res = if (req == null) jsonRpcError(JSONObject.NULL, -32600, "Invalid Request") else dispatch(req)
                // Skip notifications (NoResponse) — a batch of only notifications
                // yields an empty array, which the caller turns into 202 No body.
                if (res !== NoResponse) out.put(res)
            }
            return if (out.length() == 0) NoResponse else out
        }
        val req = try {
            JSONObject(trimmed)
        } catch (_: JSONException) {
            return jsonRpcError(JSONObject.NULL, -32700, "Parse error")
        }
        return dispatch(req)
    }

    private fun dispatch(req: JSONObject): JSONObject {
        val id = req.opt("id")
        val method = req.optString("method")
        if (!req.has("jsonrpc") || req.optString("jsonrpc") != "2.0" || method.isBlank()) {
            return jsonRpcError(id ?: JSONObject.NULL, -32600, "Invalid Request")
        }
        // JSON-RPC notifications (no "id") MUST NOT receive a response. This
        // covers notifications/initialized and any other notifications/* the
        // client sends after the handshake. Returning a response object here
        // (previously an id=null result) makes strict clients such as rmcp
        // reject the stream with an "initialized notification" protocol error.
        // We signal "no response" with NoResponse and let the caller answer 202.
        val isNotification = !req.has("id") || method.startsWith("notifications/")
        if (isNotification) return NoResponse
        val params = req.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject()
                    .put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "Taffy").put("version", BuildConfig.VERSION_NAME))
                .put("_meta", JSONObject()
                    .put("builtInToolsAlwaysAdvertised", true)
                    .put("fullToolCount", ToolCatalog.ALL.size)
                    .put("toolUsageGuide", toolUsageGuide())
                    .put("hint", "tools/list advertises the complete built-in catalog. IMPORTANT: Always route SO tasks to built-in tools (so_open + analyze_* + edit_*), NOT bridged APK tools."))
            "ping" -> JSONObject().put("ok", true)
            "resources/list" -> JSONObject().put("resources", JSONArray())
            "prompts/list" -> JSONObject().put("prompts", JSONArray())
            "tools/list" -> {
                val advertised = advertisedTools()
                JSONObject()
                .put("tools", advertised)
                .put("_meta", JSONObject()
                    .put("builtInToolsAlwaysAdvertised", true)
                    .put("returnedCount", advertised.length())
                    .put("totalCatalogCount", ToolCatalog.ALL.size)
                    .put("toolUsageGuide", toolUsageGuide())
                    .put("hint", "IMPORTANT: so_open + analyze_* + edit_* + build_so are the built-in SO reverse engineering tools. Bridged APK tools are for APK-layer tasks only. Always route SO tasks to built-in tools."))
            }
            "tools/call" -> callTool(params)
            else -> return jsonRpcError(id ?: JSONObject.NULL, -32601, "Method not found")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    // Sentinel meaning "this request was a notification; emit no JSON-RPC
    // response". Compared by identity (===). Never serialized to a client.
    private val NoResponse: JSONObject = JSONObject().put("__noResponse", true)

    private fun toolUsageGuide(): JSONObject = JSONObject()
        .put("so_analysis", "For SO/native library reverse engineering, use built-in tools: so_open -> analyze_* / edit_* -> build_so. Do NOT use bridged APK tools for SO tasks.")
        .put("apk_tasks", "Bridged APK tools (mt_apk_* or np_*) are only for APK-level operations such as APK opening, signing, smali, and AXML editing.")
        .put("deep_analysis", "For an AI-driven deep analysis of a SO: call analyze_guide (path or workspaceId) to receive a forensics guide tailored to the target, then follow its steps by calling analyze_functions/analyze_cfg/analyze_xrefs/search_strings/read_disasm/analyze_crypto/emulate_call as instructed and synthesize the report yourself. Set includeEvidence=true on analyze_guide to also get a full local evidence snapshot (ELF sections, functions, strings, crypto) without extra tool calls. analyze_guide itself performs NO AI inference — you are the reasoner.")
        .put("file_tools", "file_list/file_read/file_write/file_search/file_replace/file_diff/file_rename/file_copy/file_delete/file_batch_rename are general file & text utilities for reading/writing/editing/comparing/batch-renaming files on the device; useful to view or prepare analysis artifacts.")
        .put("archive_tools", "archive_list/archive_extract/archive_create/archive_add/archive_delete/archive_rename operate on ZIP/TAR/TAR.GZ archives: list, extract, create, or modify ZIP entries.")
        .put("arsc", "arsc_analyze parses Android binary resource tables (.arsc / resources.arsc, or extract from an APK) to enumerate packages/types/entries.")
        .put("workflow", "so_open (action=list) -> session_open -> analyze_*/edit_* -> build_so. Use bridged APK tools only for the outer APK layer.")
        .put("common_mistake", "Do not call bridged APK tools for SO analysis; use so_open.")

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.str("name")
        val args = params.obj("arguments")
        return wrapToolResult(callToolWithPolicy(name, args))
    }

    private fun callToolWithPolicy(name: String, args: JSONObject): JSONObject {
        val settings = SettingsStore(context)
        if (name.isNotEmpty() && isToolDisabled(settings, name)) {
            return err("TOOL_DISABLED", "Tool $name is disabled by server policy (settings.disabledTools).")
        }
        val rateLimit = settings.toolCallRateLimitPerMin
        if (rateLimit > 0 && !RateLimiter.tryAcquire(name, rateLimit)) {
            return err("RATE_LIMITED", "Tool $name hit the per-minute rate limit ($rateLimit/min). Retry shortly.")
        }
        val heavy = name in ToolCatalog.heavyNames
        val acquiredGate = heavyGate
        if (heavy && !acquiredGate.tryAcquire()) {
            val busy = err("SERVER_BUSY", "Another analysis task is running. Retry the same call shortly.")
            busy.getJSONObject("error").put("retrySameArguments", true).put("retryAfterMillis", 750)
            busy.put("nextActions", JSONArray(listOf("Retry the exact same tool call after a short delay.")))
            return busy
        }
        return try {
            callToolPayload(name, args)
        } finally {
            if (heavy) acquiredGate.release()
        }
    }

    private fun isToolDisabled(settings: SettingsStore, name: String): Boolean {
        val raw = settings.disabledTools
        if (raw.isBlank()) return false
        return raw.split(',').any { it.trim() == name }
    }

    private fun toolsCount(): JSONObject {
        val settings = SettingsStore(context)
        val total = ToolCatalog.ALL.size
        val advertised = advertisedTools()
        val advertisedCount = advertised.length()
        val perCategory = JSONObject()
        ToolCatalog.categoryDescriptions(false).forEach { (cat, _) ->
            perCategory.put(cat, JSONObject()
                .put("total", ToolCatalog.ALL.count { it.meta.category == cat })
                .put("advertised", advertisedCountOf(advertised, cat)))
        }
        val apkPrefixes = apkBridge.allPrefixes().toSet()
        val apkBridged = (0 until advertised.length()).count {
            val name = advertised.getJSONObject(it).optString("name")
            apkPrefixes.any { name.startsWith(it) }
        }
        return ok(JSONObject()
            .put("totalCatalogCount", total)
            .put("advertisedCount", advertisedCount)
            .put("builtInToolsAlwaysAdvertised", true)
            .put("apkBridgeAutoCompaction", true)
            .put("apkBridgedAdvertised", apkBridged)
            .put("perCategory", perCategory)
            .put("hint", "Use meta_info (action=tools/describe) to fetch schemas for tools not in the advertised list."))
    }

    private fun advertisedCountOf(arr: JSONArray, category: String): Int {
        var count = 0
        val apkPrefixes = apkBridge.allPrefixes()
        for (i in 0 until arr.length()) {
            val name = arr.getJSONObject(i).optString("name")
            if (ToolCatalog.categoryOf(name) == category || (category == "apk-bridge" && apkPrefixes.any { name.startsWith(it) })) count++
        }
        return count
    }

    private fun batchTool(args: JSONObject): JSONObject {
        return BatchExecutor(
            executeTool = ::callToolWithPolicy,
            ensureSnapshot = ::ensureBatchSnapshot,
            rollbackSnapshots = ::rollbackBatchSnapshots,
        releaseSnapshots = ::releaseBatchSnapshots,
        ).execute(args)
    }

    private fun ensureBatchSnapshot(args: JSONObject, snapshots: MutableMap<String, String>): JSONObject? {
        val workspaceId = args.optString("workspaceId")
        val editSessionId = args.optString("editSessionId")
        if (workspaceId.isBlank() || editSessionId.isBlank()) return null
        val key = "$workspaceId::$editSessionId"
        if (snapshots.containsKey(key)) return null
        val result = EngineProvider.get(context).editSnapshot(workspaceId, editSessionId, "batch-transaction-${System.currentTimeMillis()}")
        if (!result.optBoolean("ok", false)) return result
        snapshots[key] = result.getString("snapshotId")
        return null
    }

    private fun rollbackBatchSnapshots(snapshots: Map<String, String>): JSONArray {
        val out = JSONArray()
        val engine = EngineProvider.get(context)
        for ((key, snapshotId) in snapshots.entries.toList().asReversed()) {
            val parts = key.split("::", limit = 2)
            val workspaceId = parts.getOrNull(0).orEmpty()
            val editSessionId = parts.getOrNull(1).orEmpty()
            val result = engine.editRollbackById(workspaceId, editSessionId, snapshotId)
            out.put(JSONObject().put("workspaceId", workspaceId).put("editSessionId", editSessionId).put("result", result))
        }
        return out
    }

    private fun releaseBatchSnapshots(snapshots: Map<String, String>) {
        val engine = EngineProvider.get(context)
        snapshots.forEach { (key, snapshotId) ->
            val parts = key.split("::", limit = 2)
            if (parts.size == 2) engine.editDropSnapshotById(parts[0], parts[1], snapshotId)
        }
    }

    private fun callToolPayload(name: String, args: JSONObject): JSONObject {
        val native = EngineProvider.get(context)
        val settings = SettingsStore(context)
        ToolStats.setEnabled(settings.collectToolStats)
        ToolStats.setPersistEnabled(settings.toolStatsPersist)
        val started = System.nanoTime()
        val ctx = HookedContext(
            context = context,
            settings = settings,
            engine = native,
            binaryEngine = runCatching { EngineProvider.getBinaryEngine(context) }.getOrNull(),
            healthHook = { health() },
            statsHook = { ToolStats.snapshot() },
            resetStatsHook = { ToolStats.reset() },
            toolsCountHook = { toolsCount() },
            helpHook = { help() },
            listToolsHook = { cat, q -> listTools(cat, q) },
            describeToolsHook = { names -> describeTools(names) },
            workflowsHook = { workflows() },
            suggestHook = { args -> suggestions(args) },
            errorsHook = { errorCatalog() },
            reportHook = { args -> EngineProvider.get(context).analysisReport(args.str("workspaceId"), args.str("editSessionId"), args.bool("writeToFile", true)) },
            capabilitiesHook = { ok(EngineProvider.get(context).capabilityRegistry()) },
            batchHook = { batchArgs -> batchTool(batchArgs) },
            continueHook = { cursor -> native.continuePage(cursor) },
            sysStatusHook = { probe -> sysStatus(probe) },
            tunnelStatusHook = { ok(tunnel.snapshotJson()) },
            tunnelStatsHook = { reset -> if (reset) tunnel.resetTunnelStats(); ok(tunnel.tunnelStats()) },
            tunnelStartHook = { mode, port, token, publicUrl ->
                val resolvedMode = if (mode == "named") CloudflareTunnelManager.Mode.NAMED else CloudflareTunnelManager.Mode.QUICK
                val targetPort = if (port > 0) port else settings.tunnelTargetPort
                val tok = if (token.isNotBlank()) token else settings.tunnelNamedToken
                if (resolvedMode == CloudflareTunnelManager.Mode.NAMED && !publicUrl.isNullOrBlank()) {
                    settings.tunnelNamedPublicUrl = publicUrl
                }
                val ts = tunnel.start(targetPort, resolvedMode, tok)
                ok(tunnel.snapshotJson().put("message", ts.message).put("publicUrl", ts.publicUrl ?: JSONObject.NULL))
            },
            tunnelStopHook = { tunnel.stop(); ok(JSONObject().put("stopped", true)) },
            apkStatusHook = { probe -> if (probe) apkBridge.probe(); ok(apkBridge.snapshotJson()) },
            apkProbeHook = { val st = apkBridge.probe(); ok(apkBridge.snapshotJson().put("tools", JSONArray().apply { st.tools.forEach { put(it.name) } })) },
            apkPingHook = { apkBridge.ping(); ok(apkBridge.snapshotJson()) },
        )
        val handler = ToolCatalog.byName[name]
        val payload = if (handler != null) {
            handler.handle(ctx, args)
        } else if (apkBridge.isBridgedTool(name)) {
            apkBridge.callTool(name, args)
        } else {
            JSONObject().put("ok", false).put("error", JSONObject().put("code", "TOOL_NOT_FOUND").put("message", name))
        }
        val elapsedMicros = (System.nanoTime() - started) / 1000
        val isOk = payload.optBoolean("ok", true)
        val errMsg = payload.optJSONObject("error")?.optString("message").orEmpty()
        ToolStats.record(name, isOk, elapsedMicros, errMsg)
        AppLog.i("Tool call $name -> ok=$isOk (${elapsedMicros / 1000.0}ms)")
        return payload
    }

    private fun wrapToolResult(payload: JSONObject): JSONObject {
        val settings = SettingsStore(context)
        val payloadText = payload.toString().replace("\\/", "/")
        val cap = settings.toolResultMaxChars
        val rendered = if (cap > 0 && payloadText.length > cap) {
            err("RESULT_TRUNCATED", "Tool result exceeded configured character limit", "limit", cap)
                .put("truncated", true)
                .put("originalLength", payloadText.length)
                .put("preview", payloadText.substring(0, cap))
                .toString()
        } else payloadText
        return JSONObject()
            .put("isError", payload.optBoolean("ok", true).not())
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", rendered)))
    }

    private fun io.ktor.server.application.ApplicationCall.authorized(): Boolean {
        val settings = SettingsStore(context)
        if (!settings.authEnabled) return true
        val token = settings.accessToken
        if (token.isBlank()) return true
        // 优先检查 Authorization: Bearer <token> 头部（最安全）
        val auth = request.header("Authorization").orEmpty()
        val bearer = auth.removePrefix("Bearer").trim()
        if (constantTimeEquals(bearer, token)) return true
        // 兼容回退: 部分 MCP 客户端（如 Kotlin SDK）通过 URL 查询参数传递 token。
        // 应用自身生成的 MCP URL 也包含 ?token=xxx，若不接受会导致客户端无法鉴权。
        // 对于本地/局域网场景，URL token 泄露风险可接受；公网暴露场景建议仅用 Bearer。
        val queryToken = request.queryParameters["token"].orEmpty()
        if (queryToken.isNotBlank() && constantTimeEquals(queryToken, token)) return true
        return false
    }

    private fun constantTimeEquals(candidate: String, secret: String): Boolean = tokenConstantTimeEquals(candidate, secret)

    private fun authError(): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL).put("error", JSONObject().put("code", -32001).put("message", "Unauthorized: missing or invalid Taffy token"))

    private fun requestTooLarge(maxBytes: Int): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL).put("error", JSONObject().put("code", -32002).put("message", "Request body is larger than configured Taffy limit").put("data", JSONObject().put("maxBytes", maxBytes)))

    /**
     * Full tools/list payload built from `ToolCatalog.ALL`. Each entry's
     * input schema is built lazily via `ToolCatalog.toolDescriptor` from the
     * inline `SchemaBuilder` DSL in the catalog, eliminating the historical
     * duplication between this server's `tools()` literal and the catalog
     * metadata. APK-merged tools are appended when the bridge is configured.
     */
    private fun tools(): JSONArray {
        val settings = SettingsStore(context)
        val includeCategory = settings.includeCategoryInSchema
        val out = JSONArray()
        ToolCatalog.ALL.forEach { handler -> out.put(ToolCatalog.toolDescriptor(handler, includeCategory)) }
        if (settings.apkMcpMergeTools) {
            val merged = apkBridge.mergedTools()
            // If no cached tools, try probing
            val toolsToShow = if (merged.isNotEmpty()) merged
                else if (settings.apkMcpAutoProbe) apkBridge.probe().let { apkBridge.mergedTools() }
                else emptyList()
            toolsToShow.forEach { td ->
                val schema = td.inputSchema ?: JSONObject().put("type", "object").put("properties", JSONObject())
                val obj = JSONObject()
                    .put("name", td.name)
                    .put("description", "[APK ONLY — NOT for SO/native files] ${td.description ?: td.title ?: "APK MCP tool"} Use so_open + analyze_* + edit_* for SO file tasks.")
                    .put("inputSchema", schema)
                if (includeCategory) obj.put("category", "apk-bridge")
                if (td.outputSchema != null) obj.put("outputSchema", td.outputSchema)
                out.put(obj)
            }
        }
        return out
    }

    private fun health(): JSONObject = ok(JSONObject()
        .put("status", "ok")
        .put("server", "niehe")
        .put("runtime", runtimeInfo())
        .put("toolCount", advertisedTools().length())
        .put("totalCatalogCount", ToolCatalog.ALL.size)
        .put("builtInToolsAlwaysAdvertised", true)
        .put("collectToolStats", SettingsStore(context).collectToolStats)
        .put("uptimeMillis", System.currentTimeMillis() - startedAt)
        .put("nativeBackends", nativeBackendStatus())
        .put("hint", "Call meta_info (action=stats) for per-tool call counts and latency. Call system_control (action=status) to check SO+APK combo, tunnel state, and native backend status."))

    private fun nativeBackendStatus(): JSONObject {
        val rizin = NativeEngine.active()
        val nativeEngine = com.soreverse.mcp.core.EngineProvider.get(context)
        val lief = nativeEngine.lief
        val settings = SettingsStore(context)
        return JSONObject()
            .put("rizin", JSONObject().put("available", rizin.available()).put("loadStatus", rizin.loadStatus()))
            .put("lief", JSONObject().put("available", lief.available()).put("loadStatus", lief.loadStatus()))
            .put("emulation", nativeEngine.emulationStatus().put("enabled", settings.emulationEnabled))
    }

    private fun sysStatus(probe: Boolean): JSONObject {
        if (probe) apkBridge.probe()
        val s = SettingsStore(context)
        val snapshot = apkBridge.snapshotJson()
        val onlineBridges = snapshot.optJSONArray("bridges")
        val onlineBridgeCount = snapshot.optInt("onlineCount")
        val onlinePrefixes = apkBridge.allPrefixes()
        val integrationOnline = onlinePrefixes.isNotEmpty()
        val integrationHint = when {
            onlineBridgeCount == 0 -> "APK MCP is offline. Install MT Manager or NP Manager, enable the APK MCP feature, keep it running in background, then set its /mcp URL in settings and call system_control (action=apk_probe)."
            onlineBridgeCount == 1 -> {
                val prefix = apkBridge.bridgedPrefix()
                val label = ApkMcpBridge.prefixLabel(prefix)
                if (prefix == ApkMcpBridge.MT_PREFIX)
                    "MT Manager APK MCP is online. Use MT Manager's mt_apk_* capabilities for APK open / smali+axml edit / signed APK build, and use this app as the SO assistant for so_open/analyze_*/edit_* on embedded lib/*/*.so. Workflow: mt_apk_open -> mt_apk_list (lib/<abi>) -> so_open -> analyze_*/edit_* -> build_so."
                else
                    "$label APK MCP is online. Use ${prefix}* capabilities for APK open / smali+axml edit / signed APK build, and use this app as the SO assistant for so_open/analyze_*/edit_* on embedded lib/*/*.so. Workflow: ${prefix}open -> ${prefix}list (lib/<abi>) -> so_open -> analyze_*/edit_* -> build_so."
            }
            else -> {
                val labels = onlinePrefixes.joinToString(" + ") { ApkMcpBridge.prefixLabel(it) }
                val workflowLines = onlinePrefixes.map { p ->
                    val label = ApkMcpBridge.prefixLabel(p)
                    "  $label (${p}*): ${p}open -> ${p}list (lib/<abi>) -> so_open -> analyze_*/edit_* -> build_so"
                }.joinToString("\n")
                "$onlineBridgeCount APK MCP bridges online ($labels). Each bridge exposes its own tool prefix and routes independently. Use the right tool prefix for each APK. Workflows:\n$workflowLines"
            }
        }
        return ok(JSONObject()
            .put("soMcp", JSONObject().put("running", engine != null).put("host", host).put("port", port))
            .put("runtime", runtimeInfo())
            .put("nativeBackends", nativeBackendStatus())
            .put("apkMcp", snapshot)
            .put("tunnel", tunnel.snapshotJson())
            .put("integration", JSONObject()
                .put("online", integrationOnline)
                .put("onlineCount", onlineBridgeCount)
                .put("prefixes", JSONArray(onlinePrefixes))
                .put("labels", JSONArray(onlinePrefixes.map { ApkMcpBridge.prefixLabel(it) }))
                .put("apkMcpUrl", s.apkMcpUrl)
                .put("hint", integrationHint))
            .put("cloudflaredAvailable", tunnel.binary()?.exists() == true)
        )
    }

    private fun runtimeInfo(): JSONObject {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val appInfo = context.applicationInfo
        val nativeDir = appInfo.nativeLibraryDir.orEmpty()
        val rz = File(nativeDir, "librz_native.so")
        val cxx = File(nativeDir, "libc++_shared.so")
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", pkg?.versionName ?: "")
            .put("versionCode", if (android.os.Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode ?: 0L else @Suppress("DEPRECATION") (pkg?.versionCode?.toLong() ?: 0L))
            .put("supportedAbis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            .put("nativeLibraryDir", nativeDir)
            .put("librzNative", nativeFileInfo(rz))
            .put("libcxxShared", nativeFileInfo(cxx))
    }

    private fun nativeFileInfo(file: File): JSONObject = JSONObject()
        .put("exists", file.exists())
        .put("path", file.absolutePath)
        .put("sizeBytes", if (file.exists()) file.length() else 0L)
        .put("lastModified", if (file.exists()) file.lastModified() else 0L)
        .put("sha256_16", if (file.exists()) sha256Prefix(file, 16) else "")

    private fun sha256Prefix(file: File, chars: Int): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(chars.coerceIn(1, 64))
    }.getOrDefault("")

    private fun help(): JSONObject {
        val settings = SettingsStore(context)
        val catMap = JSONObject()
        ToolCatalog.categoryDescriptions(false).forEach { (cat, desc) ->
            val toolsInCat = ToolCatalog.ALL.filter { it.meta.category == cat }
            val names = JSONArray()
            toolsInCat.forEach { e ->
                val entry = JSONObject()
                    .put("name", e.meta.name)
                    .put("cls", e.meta.cls.name.lowercase())
                    .put("advertised", true)
                if (e.meta.heavy) entry.put("heavy", true)
                names.put(entry)
            }
            catMap.put(cat, JSONObject().put("description", desc).put("tools", names))
        }
        if (settings.apkMcpMergeTools && apkBridge.state().online) {
            val apkNames = JSONArray()
            apkBridge.mergedTools().forEach {
                apkNames.put(JSONObject().put("name", it.name).put("cls", "apk").put("advertised", true))
            }
            catMap.put("apk-bridge", JSONObject()
                .put("description", "Bridged APK MCP tools (${bridgeLabel()}) — ONLY for APK-layer tasks, NOT for SO/native files")
                .put("tools", apkNames))
        }
        return JSONObject()
            .put("usage", "Use so_open (action=list to discover), then read/analyze tools with the returned workspaceId. Pass pagination.nextCursor to meta_info (action=continue) when hasMore=true.")
            .put("toolRouting", JSONObject()
                .put("rule", "IMPORTANT: Route SO/native library tasks to built-in tools. ONLY route APK-layer tasks to bridged tools.")
                .put("use_so_open_for", JSONArray(listOf("Open SO files", "List available SO files", "Download SO from URL", "Any .so/ELF file task")))
                .put("use_bridged_tools_only_for", JSONArray(listOf("Open APK packages", "List lib/ directories inside APK", "Smali/AXML editing", "Signed APK build")))
                .put("never_do", JSONArray(listOf("Use bridged APK tools to open SO files", "Use bridged tools to list SO files", "Use bridged APK tools for anything related to .so/.elf files")))
                .put("workflow", "so_open (action=list) -> analyze_*/edit_* -> build_so [for SO tasks]\nsystem_control (action=apk_probe) -> ${apkBridge.bridgedPrefix()}open -> ${apkBridge.bridgedPrefix()}list -> ... -> ${apkBridge.bridgedPrefix()}build [for APK tasks]"))
            .put("auth", "If token auth is enabled, send Authorization: Bearer <token> header. URL query parameter ?token=xxx is also accepted for client compatibility.")
            .put("exposure", JSONObject()
                .put("builtInToolsAlwaysAdvertised", true)
                .put("advertisedCount", advertisedTools().length())
                .put("totalCatalogCount", ToolCatalog.ALL.size)
                .put("discoveryHint", "tools/list advertises the complete built-in catalog; meta_info action=describe/tools remains available for focused schemas and search."))
            .put("categories", catMap)
            .put("workflows", JSONArray()
                .put(JSONObject().put("name", "standard patch").put("steps", listOf("so_open (action=list first)", "session_open", "edit_asm (dryRun=true first)", "session_history (action=check)", "build_so")))
                .put(JSONObject().put("name", "safe patch with rollback").put("steps", listOf("so_open", "session_history (action=snapshot)", "edit_asm", "session_history (action=check)", "session_history (action=undo) on failure", "session_history (action=rollback) on disaster", "build_so")))
                .put(JSONObject().put("name", "triage").put("steps", listOf("so_open", "analyze_elf (view=stats)", "analyze_functions")))
                .put(JSONObject().put("name", "deep analysis").put("steps", listOf("so_open", "analyze_functions", "analyze_cfg", "analyze_xrefs", "analyze_crypto")))
                .put(JSONObject().put("name", "audit recovery").put("steps", listOf("session_audit (action=persist)", "<process restart>", "session_audit (action=list)", "session_audit (action=load)")))
                .put(JSONObject().put("name", "APK+SO bridge (needs APK MCP bridge online)").put("steps", listOf(
                    "system_control (action=apk_probe)",
                    "${apkBridge.bridgedPrefix()}open",
                    "${apkBridge.bridgedPrefix()}list view=lib/<abi>",
                    "so_open (path from apk list)",
                    "analyze_functions",
                    "edit_asm (dryRun first)",
                    "session_history (action=check)",
                    "build_so",
                    "${apkBridge.bridgedPrefix()}edit_open",
                    "${apkBridge.bridgedPrefix()}build",
                )))
                .put(JSONObject().put("name", "emulation verify").put("steps", listOf("so_open", "session_open", "edit_asm", "emulate_call (symbolName=JNI_OnLoad)", "emulate_dump (addr=0x...)")))
                .put(JSONObject().put("name", "section rebuild (xAnSo)").put("steps", listOf("so_open", "edit_fix_sections", "analyze_elf")))
                .put(JSONObject().put("name", "public expose").put("steps", listOf("system_control (action=tunnel_start, mode=quick)", "read publicUrl from result", "client connects to publicUrl/mcp"))))
            .put("tips", JSONArray()
                .put("edits[] items schema is fully exposed via meta_info (action=describe) - no need to guess field names.")
                .put("dryRun=true on edit_asm/edit_hex previews oldHex/newHex without writing.")
                .put("session_history (action=check) detects claimed-but-unapplied patches - always run it before build_so.")
                .put("analyze_esil does instruction-level emulation tracing via Rizin ESIL VM - lighter than emulate_call for quick semantic checks.")
                .put("edit_fix_sections rebuilds stripped section headers (xAnSo) - essential for NDK SOs that IDA/Ghidra cannot parse."))
    }

    private fun workflows(): JSONObject = ok(JSONObject()
        .put("templates", JSONArray()
            .put(workflow("triage", "Open and summarize a SO", "so_open", "analyze_elf stats", "analyze_elf sections/dynsyms", "search_strings", "analyze_crypto", "meta_info suggest"))
            .put(workflow("packed_or_stripped", "When functions are missing", "so_open", "analyze_elf sections", "read_disasm addr=<.text.virtualAddr>", "search_strings prefix=<keyword>", "edit_fix_sections if sections are missing"))
            .put(workflow("safe_patch", "Atomic patch workflow", "so_open", "session_open", "session_history snapshot", "edit_hex/edit_asm dryRun=true", "edit_* dryRun=false", "session_history check", "session_audit persist", "build_so writeReport=true"))
            .put(workflow("emulation_verify", "Validate JNI/export behavior", "so_open", "analyze_elf dynsyms", "emulate_call symbolName=JNI_OnLoad", "emulate_call trace=true for small exported functions", "emulate_dump addr=0x..."))
            .put(workflow("full_report", "Persist complete analysis", "so_open", "meta_info report writeToFile=true", "read reportPath from result"))
            .put(workflow("deep_analysis_guide", "AI-driven deep analysis (you reason, no relay)", "analyze_guide path=<path> (or workspaceId)", "follow guide: analyze_functions / analyze_cfg / analyze_xrefs / search_strings / read_disasm / analyze_crypto", "emulate_call for JNI/export verification", "analysis_report for engine-side summary", "synthesize final report yourself"))
            .put(workflow("deep_analysis_evidence", "Grab full local evidence at once (no relay)", "analyze_guide workspaceId=<id> includeEvidence=true", "use returned evidence to synthesize report", "optionally enrich with the guide steps")))
        .put("batchPattern", JSONObject()
            .put("tool", "meta_info")
            .put("arguments", JSONObject().put("action", "batch").put("stopOnError", true).put("steps", JSONArray()
                .put(JSONObject().put("tool", "so_open").put("arguments", JSONObject().put("path", "<path>")).put("resultKey", "open"))
                .put(JSONObject().put("tool", "analyze_elf").put("arguments", JSONObject().put("workspaceId", "\${open.workspaceId}").put("view", "stats")).put("resultKey", "stats"))
                .put(JSONObject().put("tool", "meta_info").put("arguments", JSONObject().put("action", "suggest").put("workspaceId", "\${open.workspaceId}")))))))

    private fun workflow(name: String, description: String, vararg steps: String): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("steps", JSONArray(steps.toList()))

    private fun suggestions(args: JSONObject): JSONObject {
        val workspaceId = args.str("workspaceId")
        val native = EngineProvider.get(context)
        val base = JSONArray()
            .put("Use meta_info(action=describe, tools=[...]) before calling unfamiliar tools")
            .put("Use dryRun=true before edit_hex/edit_asm, then session_history(action=check)")
            .put("Use meta_info(action=report, writeToFile=true) before final handoff")
        if (workspaceId.isBlank()) return ok(JSONObject().put("nextActions", base).put("workflow", "triage"))
        val stats = native.readStats(workspaceId, args.str("editSessionId"))
        val next = JSONArray()
        for (i in 0 until base.length()) next.put(base.get(i))
        val counts = stats.optJSONObject("counts") ?: JSONObject()
        if (counts.optInt("functions", 0) == 0) next.put("No functions were detected: use analyze_elf(view=list, subView=sections) then read_disasm(addr=<.text.virtualAddr>)")
        if (counts.optInt("sections", 0) == 0) next.put("No section headers were detected: try edit_fix_sections before section-based patching")
        next.put("For JNI behavior validation, inspect dynsyms then call emulate_call(symbolName=JNI_OnLoad or Java_*)")
        return ok(JSONObject().put("workspaceId", workspaceId).put("stats", stats).put("nextActions", next))
    }

    private fun errorCatalog(): JSONObject = ok(JSONObject()
        .put("codes", JSONArray(listOf(
            "INVALID_ARGUMENT", "UNKNOWN_ACTION", "UNKNOWN_TOOL", "BAD_REQUEST", "TOO_MANY_STEPS", "INVALID_CURSOR",
            "SO_NOT_FOUND", "WORKSPACE_NOT_FOUND", "EDIT_SESSION_NOT_FOUND", "SECTION_NOT_FOUND", "FUNCTION_NOT_FOUND",
            "INVALID_LOCATOR", "OFFSET_OUT_OF_RANGE", "INVALID_HEX", "PATCH_TOO_LARGE", "ASM_SYNTAX_ERROR", "SIZE_MISMATCH",
            "UNSUPPORTED_OPERATION", "ELF_PARSE_FAILED", "ELF_CORRUPTED", "DUMP_ERROR", "EMULATION_DISABLED", "EMULATION_ERROR", "EMULATOR_UNAVAILABLE", "NATIVE_UNAVAILABLE",
            "RIZIN_UNAVAILABLE", "RIZIN_CFG_FAILED", "RIZIN_SEARCH_FAILED", "RIZIN_COMMAND_FAILED", "LIEF_UNAVAILABLE", "PATCH_FAILED",
            "SYMBOL_NOT_FOUND", "MEMORY_MAP_ERROR", "MEMORY_WRITE_ERROR", "MEMORY_PROTECT_ERROR", "MEMORY_UNMAP_ERROR", "SERVER_BUSY", "RATE_LIMITED", "TOOL_DISABLED"
        )))
        .put("contract", JSONObject()
            .put("success", "All tool payloads include ok=true unless they are raw JSON-RPC transport errors")
            .put("failure", "Tool failures include ok=false and error.code/error.message plus argument/badValue when applicable")
            .put("recovery", "Call meta_info(action=suggest, workspaceId=...) after an error for next actions")))

    private fun listTools(category: String, query: String): JSONObject {
        val q = query.trim().lowercase()
        val hasQuery = q.isNotEmpty()
        val settings = SettingsStore(context)
        val grouped = JSONObject()
        var matched = 0
        ToolCatalog.ALL.forEach { e ->
            if (category.isNotBlank() && e.meta.category != category) return@forEach
            val desc = e.meta.en
            if (hasQuery) {
                val hay = (e.meta.name + "\n" + desc + "\n" + e.meta.category).lowercase()
                if (!hay.contains(q)) return@forEach
            }
            if (!grouped.has(e.meta.category)) grouped.put(e.meta.category, JSONArray())
            grouped.getJSONArray(e.meta.category).put(JSONObject().put("name", e.meta.name).put("description", desc))
            matched++
        }
        if (settings.apkMcpMergeTools && (category.isBlank() || category == "apk-bridge") && apkBridge.state().online) {
            val qLower = q
            apkBridge.mergedTools()
                .filter { !hasQuery || (it.name + "\n" + (it.description ?: "")).lowercase().contains(qLower) }
                .forEach { td ->
                    if (!grouped.has("apk-bridge")) grouped.put("apk-bridge", JSONArray())
                    grouped.getJSONArray("apk-bridge").put(JSONObject().put("name", td.name).put("description", "[APK ONLY] ${td.description ?: (td.title ?: "APK MCP tool")} — NOT for SO files; use so_open for SO tasks."))
                    matched++
                }
        }
        val res = JSONObject()
            .put("categories", grouped)
            .put("totalCount", ToolCatalog.ALL.size)
            .put("filtered", category.isNotBlank() || hasQuery)
            .put("matchedCount", matched)
        if (hasQuery) res.put("query", query).put("hint", "Use meta_info (action=describe) to fetch full schema for any matched tool.")
        return ok(res)
    }

    private fun describeTools(names: List<String>): JSONObject {
        val includeCategory = SettingsStore(context).includeCategoryInSchema
        val found = JSONArray()
        val missing = JSONArray()
        names.forEach { n ->
            val handler = ToolCatalog.byName[n]
            if (handler != null) found.put(ToolCatalog.toolDescriptor(handler, includeCategory)) else missing.put(n)
        }
        val res = JSONObject()
            .put("tools", found)
            .put("descriptions", found)
            .put("foundCount", found.length())
        if (missing.length() > 0) res.put("missing", missing).put("missingCount", missing.length())
        return ok(res)
    }

    /**
     * Advertise the full built-in catalog by default so modern MCP clients see every
     * available tool and advanced SO / APK / file workflows stay discoverable.
     * The exact count no longer matters: when the total is within a small margin of the
     * built-in catalog size we advertise everything as-is. Lean exposure only kicks in for
     * oversized catalogs — typically once dynamic APK-bridge tools (mt_apk_star/np_star) are
     * merged — where the response would otherwise bloat beyond what clients handle well.
     */
    private fun advertisedTools(): JSONArray {
        val full = tools()
        if (full.length() <= ToolCatalog.ALL.size + 64) return full
        return leanAdvertisedTools(full)
    }

    private fun leanAdvertisedTools(full: JSONArray): JSONArray {
        val out = JSONArray()
        val apkPrefixes = apkBridge.allPrefixes()
        for (i in 0 until full.length()) {
            val t = full.getJSONObject(i)
            val name = t.optString("name")
            if (!apkPrefixes.any { name.startsWith(it) } || out.length() < ToolCatalog.ALL.size + 64) out.put(t)
        }
        return out
    }

    /** Returns a human-readable label for all online APK MCP bridges. */
    private fun bridgeLabel(): String {
        val prefixes = apkBridge.allPrefixes()
        if (prefixes.isEmpty()) return "MT/NP Manager"
        return prefixes.joinToString(" + ") {
            ApkMcpBridge.prefixLabel(it)
        }
    }
}

// Constant-time token comparison, extracted as a pure function so it is unit
// testable. Uses MessageDigest.isEqual which does not short-circuit on the
// first differing byte, preventing timing side-channels on the access token.
internal fun tokenConstantTimeEquals(candidate: String, secret: String): Boolean {
    if (candidate.isEmpty() || secret.isEmpty()) return false
    return java.security.MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))
}
