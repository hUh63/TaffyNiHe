package com.soreverse.mcp.core

import com.soreverse.mcp.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridge to multiple external "APK MCP" servers (MT Manager, NP Manager, etc.).
 *
 * Acts as an MCP gateway: discovers each remote server's tools via tools/list,
 * merges them under their native prefix (mt_apk_* or np_*) into our own
 * tools/list responses, and transparently forwards tools/call invocations
 * back to the correct remote server based on the tool name prefix.
 *
 * When all remotes are unreachable, tools are hidden so the local server behaves
 * as a standalone SO-only MCP. When any remote is reachable, the client gets a
 * combined SO+APK reverse-engineering toolset ("combo") without re-implementing
 * APK analysis from scratch.
 */
class ApkMcpBridge(private val settings: SettingsStore) {

    data class ToolDef(
        val name: String,
        val title: String?,
        val description: String?,
        val inputSchema: JSONObject?,
        val outputSchema: JSONObject?,
    )

    data class State(
        val name: String = "",
        val url: String = "",
        val online: Boolean = false,
        val lastError: String = "",
        val tools: List<ToolDef> = emptyList(),
        val toolPrefix: String = "",
        val configPrefix: String = "",
        val lastCheckedAt: Long = 0,
        val lastLatencyMs: Long = 0,
        val probes: Long = 0,
        val probeFailures: Long = 0,
        val totalLatencyMs: Long = 0,
        val maxLatencyMs: Long = 0,
    ) {
        fun avgLatencyMs(): Long = if (probes > 0) totalLatencyMs / probes else 0
        fun lossRate(): Double = if (probes == 0L) 0.0 else probeFailures.toDouble() / probes
    }

    /**
     * Internal representation of a single bridge connection.
     * name 唯一，url 允许重复（多实例指向同一桥接）。
     */
    private class BridgeConnection(val name: String, val url: String, val token: String, val configPrefix: String, val fallbackPrefix: String) {
        @Volatile var state: State = State(url = url)
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        @Volatile private var healthThread: Thread? = null
        @Volatile private var healthStop = false

        @Synchronized
        fun probe(timeoutMs: Int = 8000): State {
            try {
                val req = buildJsonRpc(url, "tools/list", JSONObject(), id = connIdCounter.incrementAndGet())
                val start = System.nanoTime()
                val resp = post(req)
                val latencyMs = (System.nanoTime() - start) / 1_000_000
                val parsed = parseTools(resp)
                // 配置的前缀优先；否则自动检测；检测不到则按桥接序号生成 MCP{n}_
                val prefix = configPrefix.ifBlank { detectPrefix(parsed) ?: fallbackPrefix }
                val prev = state
                val s = State(
                    name = name,
                    url = url,
                    online = true,
                    lastError = "",
                    tools = parsed,
                    toolPrefix = prefix,
                    configPrefix = configPrefix,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastLatencyMs = latencyMs,
                    probes = prev.probes + 1,
                    probeFailures = prev.probeFailures,
                    totalLatencyMs = prev.totalLatencyMs + latencyMs,
                    maxLatencyMs = maxOf(prev.maxLatencyMs, latencyMs),
                )
                state = s
                val label = prefixLabel(prefix)
                AppLog.i("apk-mcp bridge online: ${parsed.size} tools from $url ($label, ${latencyMs}ms)")
                return s
            } catch (e: Exception) {
                val prev = state
                val s = State(name = name, url = url, online = false, lastError = e.message ?: e.javaClass.simpleName,
                    configPrefix = configPrefix,
                    probes = prev.probes + 1, probeFailures = prev.probeFailures + 1,
                    totalLatencyMs = prev.totalLatencyMs, maxLatencyMs = prev.maxLatencyMs)
                state = s
                // 未运行时的日志保持 Warning 级别
                val msg = e.message ?: ""
                if (msg.contains("Failed to connect") || msg.contains("Connection refused") || msg.contains("ConnectException")) {
                    AppLog.w("apk-mcp not running: $url")
                } else {
                    AppLog.w("apk-mcp probe failed: $url $msg")
                }
                return s
            }
        }

        @Synchronized
        fun ping(): State {
            return try {
                val req = buildJsonRpc(url, "initialize", JSONObject().put("client", "somcp-ping"), id = connIdCounter.incrementAndGet())
                val start = System.nanoTime()
                post(req)
                val latencyMs = (System.nanoTime() - start) / 1_000_000
                val prev = state
                val s = if (!prev.online) {
                    prev.copy(lastLatencyMs = latencyMs, lastCheckedAt = System.currentTimeMillis(),
                        probes = prev.probes + 1, lastError = "", online = false, tools = prev.tools)
                } else {
                    State(url = url, online = true, lastError = "", tools = prev.tools,
                        lastCheckedAt = System.currentTimeMillis(), lastLatencyMs = latencyMs,
                        probes = prev.probes + 1, probeFailures = prev.probeFailures,
                        totalLatencyMs = prev.totalLatencyMs + latencyMs, maxLatencyMs = maxOf(prev.maxLatencyMs, latencyMs))
                }
                state = s
                s
            } catch (e: Exception) {
                val prev = state
                val s = State(url = url, online = false, lastError = e.message ?: e.javaClass.simpleName,
                    probes = prev.probes + 1, probeFailures = prev.probeFailures + 1,
                    totalLatencyMs = prev.totalLatencyMs, maxLatencyMs = prev.maxLatencyMs)
                state = s
                s
            }
        }

        fun callTool(name: String, arguments: JSONObject): JSONObject {
            val st = state
            if (!st.online || st.url.isBlank()) {
                return errorResult(name, "APK MCP $url is offline or not configured")
            }
            val params = JSONObject().put("name", name).put("arguments", arguments)
            return try {
                val req = buildJsonRpc(url, "tools/call", params, id = connIdCounter.incrementAndGet())
                val resp = post(req)
                parseToolResult(resp)
            } catch (e: Exception) {
                errorResult(name, "forward failed: ${e.message}")
            }
        }

        fun startHealthMonitor(intervalMs: Long = 30_000) {
            stopHealthMonitor()
            healthStop = false
            healthThread = Thread({
                while (!healthStop && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(intervalMs)
                    } catch (_: InterruptedException) { break }
                    if (healthStop) break
                    try {
                        val req = buildJsonRpc(url, "tools/list", JSONObject(), id = connIdCounter.incrementAndGet())
                        val resp = post(req)
                        val parsed = parseTools(resp)
                        val prefix = detectPrefix(parsed)
                        if (prefix != null || configPrefix.isNotBlank()) {
                            val cur = state
                            val effectivePrefix = configPrefix.ifBlank { prefix ?: fallbackPrefix }
                            state = State(name = name, url = url, online = true, lastError = "", tools = parsed, toolPrefix = effectivePrefix, configPrefix = configPrefix, lastCheckedAt = System.currentTimeMillis())
                            if (!cur.online) AppLog.i("apk-mcp health: $url back online (${parsed.size} tools, prefix=$prefix)")
                        }
                    } catch (e: Exception) {
                        val cur = state
                        if (cur.online) {
                            state = State(url = url, online = false, lastError = e.message ?: e.javaClass.simpleName, tools = emptyList(), lastCheckedAt = System.currentTimeMillis())
                            AppLog.w("apk-mcp health: $url marked offline (${e.message})")
                        }
                    }
                }
            }, "apk-mcp-health-$url").apply { isDaemon = true; start() }
        }

        fun stopHealthMonitor() {
            healthStop = true
            healthThread?.interrupt()
            healthThread = null
        }

        private fun buildJsonRpc(url: String, method: String, params: JSONObject, id: Int): Request {
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
                .toString()
            val builder = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            return builder.build()
        }

        private fun post(req: Request): String {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                return body
            }
        }

        private fun parseTools(body: String): List<ToolDef> {
            val root = JSONObject(body)
            val result = root.opt("result") as? JSONObject ?: return emptyList()
            val tools = result.optJSONArray("tools") ?: return emptyList()
            val out = ArrayList<ToolDef>(tools.length())
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                out.add(
                    ToolDef(
                        name = t.optString("name"),
                        title = t.optString("title").takeIf { it.isNotBlank() },
                        description = t.optString("description").takeIf { it.isNotBlank() },
                        inputSchema = t.optJSONObject("inputSchema"),
                        outputSchema = t.optJSONObject("outputSchema"),
                    )
                )
            }
            return out
        }

        private fun parseToolResult(body: String): JSONObject {
            val root = JSONObject(body)
            val result = root.opt("result")
            return (result as? JSONObject) ?: JSONObject().put("raw", body)
        }

        private fun errorResult(name: String, msg: String): JSONObject {
            return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "APK MCP error [$name]: $msg")))
                .put("isError", true)
                .put("source", "apk-mcp-bridge")
        }

        private fun detectPrefix(tools: List<ToolDef>): String? {
            tools.firstOrNull { it.name.startsWith(MT_PREFIX) }?.let { return MT_PREFIX }
            tools.firstOrNull { it.name.startsWith(NP_PREFIX) }?.let { return NP_PREFIX }
            return null
        }

        private fun prefixLabel(prefix: String?): String = when {
            prefix == MT_PREFIX -> "MT Manager"
            prefix == NP_PREFIX -> "NP Manager"
            prefix != null && prefix.startsWith("MCP") -> "MCP Bridge"
            else -> "Unknown"
        }
    }

    /** 未配置且无法从工具名检测时的兜底前缀：与已有桥接数量关联（第 N 个桥接默认 MCP{n}_）。 */
    private fun fallbackPrefixFor(name: String): String {
        val idx = settings.apkMcpConfigs.indexOfFirst { it.name == name }
        return "MCP${(idx + 1).coerceAtLeast(1)}_"
    }

    private val connections = CopyOnWriteArrayList<BridgeConnection>()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        syncConnectionsFromSettings()
    }

    /**
     * Register a listener to be notified when bridge state changes.
     * Returns a function to unregister the listener.
     */
    fun addStateListener(listener: () -> Unit): () -> Unit {
        stateListeners.add(listener)
        return { stateListeners.remove(listener) }
    }

    private fun notifyStateListeners() {
        stateListeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                AppLog.w("State listener failed: ${e.message}")
            }
        }
    }

    /**
     * Return a merged State representing the first online bridge,
     * or the first configured bridge if none are online.
     * This is used for backward compatibility with caller code that
     * expects a single-bridge view.
     */
    fun state(): State {
        val firstOnline = connections.firstOrNull { it.state.online }
        if (firstOnline != null) return firstOnline.state
        val first = connections.firstOrNull()
        if (first != null) return first.state
        return State()
    }

    /** Sync the internal connection list from settings. */
    private fun syncConnectionsFromSettings() {
        val configs = settings.apkMcpConfigs
        // Remove connections whose name is no longer in config
        val activeNames = configs.map { it.name }.toSet()
        connections.removeAll { it.name !in activeNames }
        // Add new connections by name（同名桥接不会被合并；同 URL 不同 name 允许）
        val existingNames = connections.map { it.name }.toSet()
        for (config in configs) {
            if (config.name !in existingNames) {
                connections.add(BridgeConnection(config.name, config.url, config.token, config.prefix, fallbackPrefixFor(config.name)))
            } else {
                // 更新已存在桥接的 url/token/prefix（用户在弹窗中改了）
                val idx = connections.indexOfFirst { it.name == config.name }
                if (idx >= 0) {
                    val old = connections[idx]
                    if (old.url != config.url || old.token != config.token || old.configPrefix != config.prefix) {
                        connections[idx] = BridgeConnection(config.name, config.url, config.token, config.prefix, old.fallbackPrefix)
                    }
                }
            }
        }
    }

    /** Ensure a connection exists for the given name, adding it if new. */
    private fun ensureConnection(name: String, url: String, token: String = "", prefix: String = ""): BridgeConnection {
        syncConnectionsFromSettings()
        return connections.firstOrNull { it.name == name } ?: run {
            val conn = BridgeConnection(name, url, token, prefix, fallbackPrefixFor(name))
            connections.add(conn)
            conn
        }
    }

    fun configured(): Boolean = settings.apkMcpConfigs.isNotEmpty() || settings.apkMcpUrl.isNotBlank()

    /**
     * Auto-discover APK MCP servers on the standard ports.
     * Returns the state of the first discovered server (for backward compatibility),
     * but adds all discovered servers to the connection list.
     */
    @Synchronized
    fun autoDiscover(port: Int = DEFAULT_PORT): State {
        syncConnectionsFromSettings()
        val allPorts = listOf(port, NP_PORT).distinct()
        var firstState: State? = null
        for (p in allPorts) {
            if (connections.any { it.url.contains(":$p/") }) continue
            val candidates = listOf(
                "http://127.0.0.1:$p/mcp",
                "http://localhost:$p/mcp",
            )
            for (url in candidates) {
                try {
                    val autoName = "桥接 ${connections.size + 1}"
                    val conn = BridgeConnection(autoName, url, "", "", "MCP${connections.size + 1}_")
                    val st = conn.probe()
                    if (st.online) {
                        connections.add(conn)
                        val configs = settings.apkMcpConfigs.toMutableList()
                        if (configs.none { it.url == url }) {
                            configs.add(SettingsStore.BridgeConfig(name = autoName, url = url, token = "", prefix = ""))
                            settings.apkMcpConfigs = configs
                        }
                        if (firstState == null) firstState = st
                        AppLog.i("apk-mcp auto-discovered ${prefixLabel(st.toolPrefix)} at $url (${st.tools.size} tools)")
                        break
                    }
                } catch (_: Exception) { /* try next */ }
            }
        }
        if (firstState == null) {
            AppLog.i("apk-mcp auto-discovery: no APK MCP found on ports $allPorts")
        }
        return firstState ?: State()
    }

    /** Probe all configured bridge connections in parallel. Returns the state of the first connection (backward compat). */
    @Synchronized
    fun probe(): State {
        syncConnectionsFromSettings()
        if (connections.isEmpty()) return State()
        // Probe all bridges concurrently so multi-bridge users see both
        // bridges come up at the same time, not one-after-another.
        val exec = java.util.concurrent.Executors.newFixedThreadPool(
            connections.size.coerceIn(1, 4)
        )
        try {
            val results = connections.map { conn ->
                exec.submit<State> { conn.probe() }
            }
            results.forEach { it.get() }
            return connections.firstOrNull()?.state ?: State()
        } finally {
            exec.shutdown()
        }
    }

    /**
     * Probe a specific URL (used when user adds a new bridge URL from UI).
     * Adds the connection if successful.
     */
    @Synchronized
    fun probeUrl(name: String, url: String, token: String = "", prefix: String = ""): State {
        val conn = ensureConnection(name, url, token, prefix)
        val st = conn.probe()
        // Always save to settings so the bridge appears in the UI list
        val configs = settings.apkMcpConfigs.toMutableList()
        if (configs.none { it.name == name }) {
            configs.add(SettingsStore.BridgeConfig(name = name, url = url, token = token, prefix = prefix))
            settings.apkMcpConfigs = configs
        }
        return st
    }

    /** 兼容旧调用：按 url 自动生成 name 并探测。 */
    fun probeUrl(url: String, token: String = ""): State {
        val name = "桥接 ${connections.size + 1}"
        return probeUrl(name, url, token, "")
    }

    /**
     * Remove a bridge connection by URL.
     */
    @Synchronized
    fun removeBridge(name: String) {
        connections.removeAll { it.name == name }
        val configs = settings.apkMcpConfigs.toMutableList()
        configs.removeAll { it.name == name }
        settings.apkMcpConfigs = configs
    }

    /** 兼容旧调用：按 URL 匹配移除（删除同名 URL 中第一个匹配项）。 */
    fun removeBridgeByUrl(url: String) {
        val conn = connections.firstOrNull { it.url == url } ?: return
        removeBridge(conn.name)
    }

    /** Lightweight liveness ping for all connections. */
    @Synchronized
    fun ping(): State {
        syncConnectionsFromSettings()
        if (connections.isEmpty()) return State()
        val exec = java.util.concurrent.Executors.newFixedThreadPool(
            connections.size.coerceIn(1, 4)
        )
        try {
            val results = connections.map { conn -> exec.submit<State> { conn.ping() } }
            results.forEach { it.get() }
        } finally {
            exec.shutdown()
        }
        return connections.firstOrNull()?.state ?: State()
    }

    /** Collect all tools from all online bridge connections. 前缀由本应用附加：裸工具名暴露为 {prefix}{name}。 */
    fun mergedTools(): List<ToolDef> {
        val all = mutableListOf<ToolDef>()
        for (conn in connections) {
            val st = conn.state
            if (st.online && st.toolPrefix.isNotBlank()) {
                all.addAll(st.tools.map { td ->
                    // 工具名已带该前缀（MT/NP 管理器自带 mt_apk_/np_）→ 原样；
                    // 否则在原始工具名前面加上前缀暴露（如 read_file → MCP1_read_file）
                    if (td.name.startsWith(st.toolPrefix)) td
                    else td.copy(name = st.toolPrefix + td.name)
                })
            }
        }
        return all
    }

    /** Check if a tool name is handled by any bridged connection. */
    fun isBridgedTool(name: String): Boolean {
        for (conn in connections) {
            val st = conn.state
            if (st.online && st.toolPrefix.isNotBlank() && name.startsWith(st.toolPrefix)) return true
        }
        return false
    }

    /** Returns the prefix of the first online bridge, or the first connection's prefix. */
    fun bridgedPrefix(): String {
        for (conn in connections) {
            val st = conn.state
            if (st.online) return st.toolPrefix
        }
        return connections.firstOrNull()?.state?.toolPrefix ?: ""
    }

    /** Get all known prefixes from all connections (both online and offline). */
    fun allPrefixes(): List<String> {
        val prefixes = mutableSetOf<String>()
        for (conn in connections) {
            val st = conn.state
            if (st.online) prefixes.add(st.toolPrefix)
        }
        return prefixes.toList()
    }

    /**
     * Call a tool on the correct bridge connection based on the tool name prefix.
     */
    fun callTool(name: String, arguments: JSONObject): JSONObject {
        // Find the connection whose prefix matches this tool name
        for (conn in connections) {
            val st = conn.state
            if (st.online && st.toolPrefix.isNotBlank() && name.startsWith(st.toolPrefix)) {
                // 若该名字是"本应用附加前缀"的暴露名（前缀+原始名），转发时剥掉前缀；
                // 若桥接工具名本身就带该前缀（MT/NP 管理器），则原样转发。
                val stripped = name.removePrefix(st.toolPrefix)
                val forward = if (st.tools.any { it.name == stripped }) stripped else name
                return conn.callTool(forward, arguments)
            }
        }
        // If no online connection matches, try finding any connection that was configured for this prefix
        val offlineMsg = StringBuilder("APK MCP bridge is offline. Configured: ")
        for (conn in connections) {
            offlineMsg.append("${conn.url} (${conn.state.online}) ")
        }
        if (connections.isEmpty()) offlineMsg.append("(none)")
        return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", offlineMsg.toString())))
            .put("isError", true)
            .put("source", "apk-mcp-bridge")
    }

    @Synchronized
    fun startHealthMonitor(intervalMs: Long = 30_000) {
        for (conn in connections) {
            conn.startHealthMonitor(intervalMs)
        }
    }

    @Synchronized
    fun stopHealthMonitor() {
        for (conn in connections) {
            conn.stopHealthMonitor()
        }
    }

    fun snapshotJson(): JSONObject {
        val configs = settings.apkMcpConfigs
        val bridges = JSONArray()
        for (config in configs) {
            val conn = connections.firstOrNull { it.name == config.name }
            val st = conn?.state ?: State(name = config.name, url = config.url, configPrefix = config.prefix)
            bridges.put(JSONObject()
                .put("name", config.name)
                .put("url", config.url)
                .put("online", st.online)
                .put("toolPrefix", st.toolPrefix)
                .put("configPrefix", config.prefix)
                .put("toolCount", st.tools.size)
                .put("lastError", st.lastError)
                .put("lastCheckedAt", st.lastCheckedAt)
                .put("lastLatencyMs", st.lastLatencyMs)
                .put("avgLatencyMs", st.avgLatencyMs())
                .put("maxLatencyMs", st.maxLatencyMs)
                .put("probes", st.probes)
                .put("probeFailures", st.probeFailures)
                .put("lossRate", st.lossRate())
                .put("tools", JSONArray().apply { st.tools.forEach { tool -> put(JSONObject()
                    // 管理页显示与 /mcp 一致的暴露名（裸工具名加前缀）
                    .put("name", if (tool.name.startsWith(st.toolPrefix)) tool.name else st.toolPrefix + tool.name)
                    .put("title", tool.title ?: "")
                    .put("description", tool.description ?: "")
                ) } })
            )
        }
        val firstOnline = connections.firstOrNull { it.state.online }
        val first = connections.firstOrNull()
        return JSONObject().apply {
            put("configured", configs.isNotEmpty())
            put("bridgeCount", configs.size)
            put("onlineCount", connections.count { it.state.online })
            put("bridges", bridges)
            // Backward compat fields
            put("url", first?.url ?: "")
            put("online", firstOnline?.state?.online == true)
            put("toolPrefix", firstOnline?.state?.toolPrefix ?: first?.state?.toolPrefix ?: "")
            put("toolCount", firstOnline?.state?.tools?.size ?: 0)
            put("lastError", firstOnline?.state?.lastError ?: first?.state?.lastError ?: "")
            put("lastCheckedAt", firstOnline?.state?.lastCheckedAt ?: first?.state?.lastCheckedAt ?: 0)
            put("lastLatencyMs", firstOnline?.state?.lastLatencyMs ?: first?.state?.lastLatencyMs ?: 0)
            put("avgLatencyMs", firstOnline?.state?.avgLatencyMs() ?: first?.state?.avgLatencyMs() ?: 0)
            put("maxLatencyMs", firstOnline?.state?.maxLatencyMs ?: first?.state?.maxLatencyMs ?: 0)
            put("probes", firstOnline?.state?.probes ?: first?.state?.probes ?: 0)
            put("probeFailures", firstOnline?.state?.probeFailures ?: first?.state?.probeFailures ?: 0)
            put("lossRate", firstOnline?.state?.lossRate() ?: first?.state?.lossRate() ?: 0.0)
            put("tools", JSONArray().apply {
                (firstOnline?.state?.tools ?: first?.state?.tools ?: emptyList()).forEach { put(it.name) }
            })
        }
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val NP_PORT = 8788
        const val MT_PREFIX = "mt_apk_"
        const val NP_PREFIX = "np_"
        val KNOWN_PREFIXES = listOf(MT_PREFIX, NP_PREFIX)
        private val connIdCounter = AtomicInteger(1000)

        fun prefixLabel(prefix: String?): String = when {
            prefix == MT_PREFIX -> "MT Manager"
            prefix == NP_PREFIX -> "NP Manager"
            prefix != null && prefix.startsWith("MCP") -> "MCP Bridge"
            else -> "Unknown"
        }
    }
}

private fun String?.ifNotBlank(block: (String) -> Unit) {
    if (this != null && isNotBlank()) block(this)
}