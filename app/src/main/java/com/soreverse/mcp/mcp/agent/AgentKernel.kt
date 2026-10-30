package com.soreverse.mcp.mcp.agent

import com.soreverse.mcp.core.withCompromisedCaBlocklist
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.RikkaAgentEngine
import com.soreverse.mcp.core.RikkaTool
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.mcp.SchemaBuilder
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import okhttp3.OkHttpClient
import com.soreverse.mcp.core.runCatchingCancellable

/** 一次编排运行的预算。超限即停止派生/停止工具调用，而不是无限跑下去。 */
internal class AgentBudget(
    val maxSubAgents: Int = 5,
    val maxToolCalls: Int = 80,
    val wallClockMs: Long = 8 * 60_000L,
) {
    private val agents = AtomicInteger(0)
    private val tools = AtomicInteger(0)
    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var cancelledFlag = false

    fun tryConsumeAgent(): Boolean = agents.incrementAndGet() <= maxSubAgents

    fun tryConsumeTool(): Boolean = tools.incrementAndGet() <= maxToolCalls

    fun agentsUsed(): Int = agents.get()
    fun toolsUsed(): Int = tools.get()

    fun elapsedMs(): Long = System.currentTimeMillis() - startedAt

    fun expired(): Boolean = elapsedMs() > wallClockMs

    fun cancel() {
        cancelledFlag = true
    }

    fun cancelled(): Boolean = cancelledFlag

    /** 是否还能继续：没取消、没超时、还有代理额度。 */
    fun healthy(): Boolean = !cancelled() && !expired()

    fun toJson(): JSONObject = JSONObject()
        .put("maxSubAgents", maxSubAgents)
        .put("maxToolCalls", maxToolCalls)
        .put("wallClockMs", wallClockMs)
        .put("agentsUsed", agentsUsed())
        .put("toolsUsed", toolsUsed())
        .put("elapsedMs", elapsedMs())
        .put("cancelled", cancelled())
        .put("expired", expired())
}

/** 编排事件（给 UI 实时刷新用）。 */
internal sealed interface AgentEvent {
    data class NodeStarted(val nodeId: String, val role: String, val task: String) : AgentEvent
    data class NodeFinished(val nodeId: String, val status: String, val ms: Long) : AgentEvent
    data class ToolCalled(val nodeId: String, val tool: String, val ok: Boolean) : AgentEvent
    data class TextDelta(val nodeId: String, val text: String) : AgentEvent
    data class Note(val message: String) : AgentEvent
}

/** 打包好的工具集：真正可用的 + 声明了但目录里没有的（后者必须显式报告，不能静默丢）。 */
internal class AgentToolSet(
    val tools: List<RikkaTool>,
    val unresolved: List<String>,
)

/**
 * 智能体内核：把「AI 引擎 + MCP 工具」接起来的最小胶水。
 *
 * 只做四件有分歧的事：
 *  1. 从 SettingsStore 读同一套 AI 配置（与分析页的深度分析共用，用户不用配两遍）
 *  2. 按角色白名单把 catalog handler 包成模型可调用的工具
 *  3. 工具失败转成结构化错误返回给模型（不让一次失败中断整条链）
 *  4. 工作区继承 + 结果截断（子代理不必重新 open 目标，长结果不撑爆上下文）
 */
internal object AgentKernel {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun client(): OkHttpClient = OkHttpClient.Builder().withCompromisedCaBlocklist()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun engine(settings: SettingsStore): RikkaAgentEngine = RikkaAgentEngine(
        client = client(),
        provider = settings.aiProvider,
        endpoint = settings.aiEndpoint,
        apiKey = settings.aiApiKey,
        model = settings.aiModel,
        temperature = settings.aiTemperature,
        customHeaders = stringMap(settings.aiCustomHeadersJson),
        customBody = additionalBody(settings),
        // 单点透传：AI 深度分析、子代理、dynamic_analyze_ai 三条链路同时生效
        toolSchemaMode = settings.aiToolSchemaMode,
    )

    fun missingConfig(settings: SettingsStore): List<String> = buildList {
        if (settings.aiEndpoint.isBlank()) add("aiEndpoint")
        if (settings.aiApiKey.isBlank()) add("aiApiKey")
        if (settings.aiModel.isBlank()) add("aiModel")
    }

    fun isConfigured(settings: SettingsStore): Boolean = missingConfig(settings).isEmpty()

    private fun stringMap(raw: String): Map<String, String> {
        val obj = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next().trim()
                if (key.isNotBlank()) put(key, obj.optString(key))
            }
        }
    }

    private fun additionalBody(settings: SettingsStore): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val body = runCatching { JSONObject(settings.aiCustomBodyJson.ifBlank { "{}" }) }.getOrNull()
            ?: return out
        val keys = body.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            if (key.isBlank()) continue
            val value = body.opt(key)
            out[key] = runCatching {
                Json.parseToJsonElement(
                    when (value) {
                        null, JSONObject.NULL -> "null"
                        is String -> JSONObject.quote(value)
                        else -> value.toString()
                    }
                )
            }.getOrElse { JsonPrimitive(value?.toString().orEmpty()) }
        }
        return out
    }

    private fun truncate(text: String, limit: Int): String =
        if (limit <= 0 || text.length <= limit) text else text.take(limit) + "…[truncated ${text.length - limit} chars]"

    /**
     * 把白名单里的工具包成 [RikkaTool]。
     *
     * 模型看到的只有白名单内的工具——这是角色边界的强制实现，不是提示词层面的建议。
     */
    fun catalogTools(
        ctx: ToolContext,
        names: List<String>,
        zh: Boolean,
        nodeId: String,
        budget: AgentBudget,
        workspaceId: () -> String,
        onEvent: (AgentEvent) -> Unit,
    ): AgentToolSet {
        val unresolved = names.filterNot { ToolCatalog.byName.containsKey(it) }
        val tools = names.mapNotNull { name ->
            val handler = ToolCatalog.byName[name] ?: return@mapNotNull null
            RikkaTool(
                name = name,
                description = if (zh) handler.meta.zh else handler.meta.en,
                schema = handler.meta.schemaBuilder.invoke(SchemaBuilder),
            ) { args ->
                if (!budget.tryConsumeTool()) {
                    err("BUDGET_EXHAUSTED", "工具调用额度已用完（${budget.maxToolCalls} 次），请基于已有证据收尾", "node", nodeId)
                        .toString()
                } else {
                    val effective = inheritWorkspace(name, args, workspaceId())
                    AppLog.i("agent[$nodeId] tool $name args=${effective.toString().take(400)}")
                    val payload = withContext(Dispatchers.IO) {
                        runCatchingCancellable { handler.handle(ctx, effective) }
                            .getOrElse { error ->
                                AppLog.e("agent[$nodeId] tool failed $name", error)
                                err("TOOL_EXECUTION_FAILED", error.message ?: error.javaClass.simpleName, "tool", name)
                            }
                    }
                    onEvent(AgentEvent.ToolCalled(nodeId, name, payload.optBoolean("ok", true)))
                    AppLog.i("agent[$nodeId] tool $name done ok=${payload.optBoolean("ok", true)}")
                    truncate(payload.toString(), ctx.settings.toolResultMaxChars)
                }
            }
        }
        return AgentToolSet(tools, unresolved)
    }

    /** 子代理不必重复打开目标：缺 workspaceId 时自动补上编排器的工作区。 */
    private fun inheritWorkspace(name: String, args: JSONObject, current: String): JSONObject {
        if (name == "taffy_so_open" || current.isBlank()) return args
        if (args.optString("workspaceId").isNotBlank()) return args
        return JSONObject(args.toString()).apply { put("workspaceId", current) }
    }

    /** 从 taffy_so_open 的结果里取工作区 id（编排器需要，以便后续节点复用）。 */
    fun workspaceOf(payload: JSONObject): String =
        payload.optString("workspaceId").ifBlank { payload.optJSONObject("workspace")?.optString("id").orEmpty() }
}
