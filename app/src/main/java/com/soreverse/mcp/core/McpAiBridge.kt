package com.soreverse.mcp.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 把远端 MCP 工具直接接进 AI 的工具清单。
 *
 * 和 `taffy_mcp_client` 的区别：那个是"人来调"（AI 要先知道自己有哪些远端工具、参数怎么填），
 * 这个是"AI 自己调"——远端工具会以 `mcp_<endpoint>_<tool>` 的名字出现在模型看到的工具列表里，
 * 模型可以直接选它、按它的 inputSchema 填参数，塔菲负责转发。
 *
 * 默认关闭：外部工具是用户自己配的网络端点，混进工具清单会放大模型的行动面，
 * 所以要求显式打开（「MCP 客户端」页里的开关）。
 */
object McpAiBridge {

    private const val PREFS = "taffy_mcp_client"
    private const val KEY_AI = "ai_expose"
    private const val MAX_PER_ENDPOINT = 20
    private const val MAX_TOTAL = 40
    private const val CACHE_TTL_MS = 60_000L

    private data class Entry(val builtAt: Long, val tools: List<RikkaTool>)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AI, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AI, enabled).apply()
        cache.clear()
    }

    /** 清缓存（端点增删改后调用）。 */
    fun invalidate() = cache.clear()

    /**
     * 构建外部工具。失败一律静默降级（远端掉线不该让整个 AI 会话失败），
     * 但要写日志，方便用户排查"为什么模型没看到我的工具"。
     */
    suspend fun buildTools(ctx: Context): List<RikkaTool> = withContext(Dispatchers.IO) {
        if (!isEnabled(ctx)) return@withContext emptyList()
        val endpoints = runCatching { McpClientStore.load(ctx) }.getOrDefault(emptyList())
            .filter { it.enabled }
        if (endpoints.isEmpty()) return@withContext emptyList()

        val out = ArrayList<RikkaTool>()
        for (ep in endpoints) {
            if (out.size >= MAX_TOTAL) break
            val cached = cache[ep.id]
            val tools = if (cached != null && System.currentTimeMillis() - cached.builtAt < CACHE_TTL_MS) {
                cached.tools
            } else {
                val built = runCatching { buildForEndpoint(ep) }.getOrElse { e ->
                    AppLog.w("McpAiBridge", "端点 ${ep.name} 工具列表拉取失败: ${e.message}")
                    emptyList()
                }
                cache[ep.id] = Entry(System.currentTimeMillis(), built)
                built
            }
            for (t in tools) {
                if (out.size >= MAX_TOTAL) break
                out.add(t)
            }
        }
        out
    }

    /**
     * 给非 suspend 的调用点（AgentKernel.catalogTools / DeepAnalysisService.buildRikkaTools）
     * 用的同步入口。缓存命中时 runBlocking 里几乎立刻返回；未命中才会真等一次网络。
     */
    fun buildToolsBlocking(ctx: Context): List<RikkaTool> =
        runCatching { runBlocking { buildTools(ctx) } }.getOrElse { e ->
            AppLog.w("McpAiBridge", "构建外部工具失败: ${e.message}")
            emptyList()
        }

    private fun buildForEndpoint(ep: McpClientEndpoint): List<RikkaTool> {
        val session = McpClientSession(ep)
        val tools = session.listTools() ?: run {
            AppLog.w("McpAiBridge", "端点 ${ep.name} 列工具失败: ${session.lastError}")
            return emptyList()
        }
        val out = ArrayList<RikkaTool>()
        val epTag = sanitize(ep.name.ifBlank { ep.id })
        for (i in 0 until tools.length()) {
            if (out.size >= MAX_PER_ENDPOINT) break
            val t = tools.optJSONObject(i) ?: continue
            val remoteName = t.optString("name")
            if (remoteName.isBlank()) continue
            val localName = ("mcp_" + epTag + "_" + sanitize(remoteName)).take(60)
            val desc = t.optString("description").ifBlank { "外部 MCP 工具 $remoteName" }
            val schema = t.optJSONObject("inputSchema") ?: JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
            out.add(
                RikkaTool(
                    name = localName,
                    description = "[外部 MCP · ${ep.name}] " + desc.take(600),
                    schema = schema,
                ) { args: JSONObject ->
                    withContext(Dispatchers.IO) {
                        val s = McpClientSession(ep)
                        val text = s.callTool(remoteName, args)
                        if (text == null) {
                            "ERROR: 调用 $remoteName 失败（${s.lastError}）"
                        } else {
                            text.take(200_000)
                        }
                    }
                }
            )
        }
        AppLog.i("McpAiBridge", "端点 ${ep.name} 注册 ${out.size} 个外部工具")
        return out
    }

    /** OpenAI 系工具名只允许字母数字下划线短横；顺便截断，避免超过 64 字符。 */
    private fun sanitize(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-') c else '_')
        }
        return sb.toString().take(40).ifBlank { "x" }
    }
}
