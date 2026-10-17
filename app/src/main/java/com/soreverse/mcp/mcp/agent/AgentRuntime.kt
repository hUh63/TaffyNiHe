package com.soreverse.mcp.mcp.agent

import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.mcp.ToolContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 编排运行时的进程内注册表。
 *
 * 之所以把 run 做成「后台跑 + 轮询」而不是「一个同步调用跑到底」：一次编排动辄几分钟、
 * 几十次工具调用，同步调用会被 MCP 客户端超时掐断，而超时后任务还在跑，状态就丢了。
 * 现在 run 立刻返回 runId，status 随时可查，cancel 真的能停。
 */
internal object AgentRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 进行中的运行：runId → 可变进度快照。 */
    private val live = ConcurrentHashMap<String, JSONObject>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val seq = AtomicInteger(0)

    fun start(
        ctx: ToolContext,
        goal: String,
        workspaceId: String,
        path: String,
        roles: List<String>?,
        maxSubAgents: Int,
        maxToolCalls: Int,
        wallClockMs: Long,
        maxStepsPerNode: Int,
        zh: Boolean,
    ): JSONObject {
        if (!AgentKernel.isConfigured(ctx.settings)) {
            val missing = AgentKernel.missingConfig(ctx.settings)
            return JSONObject()
                .put("ok", false)
                .put(
                    "error",
                    JSONObject()
                        .put("code", "AI_NOT_CONFIGURED")
                        .put("message", "AI 未配置：请在设置里填写 endpoint / apiKey / model（缺 ${missing.joinToString()}）")
                        .put("missing", JSONArray(missing))
                )
        }
        val runId = "run-" + System.currentTimeMillis() + "-" + seq.incrementAndGet()
        val snapshot = JSONObject()
            .put("runId", runId)
            .put("goal", goal)
            .put("status", "running")
            .put("startedAt", System.currentTimeMillis())
            .put("plan", JSONArray())
            .put("nodes", JSONObject())
            .put("notes", JSONArray())
        live[runId] = snapshot

        val job = scope.launch {
            try {
                val record = AgentOrchestrator.run(
                    ctx = ctx,
                    goal = goal,
                    workspaceId = workspaceId,
                    path = path,
                    roles = roles,
                    maxSubAgents = maxSubAgents,
                    maxToolCalls = maxToolCalls,
                    wallClockMs = wallClockMs,
                    maxStepsPerNode = maxStepsPerNode,
                    zh = zh,
                ) { ev -> applyEvent(snapshot, ev) }
                snapshot
                    .put("status", record.status)
                    .put("finishedAt", record.finishedAt)
                    .put("conclusion", record.conclusion)
                    .put("record", record.toJson())
            } catch (e: kotlinx.coroutines.CancellationException) {
                snapshot.put("status", "cancelled").put("finishedAt", System.currentTimeMillis())
                throw e
            } catch (e: Throwable) {
                AppLog.e("agent[$runId] orchestration failed", e)
                snapshot
                    .put("status", "failed")
                    .put("finishedAt", System.currentTimeMillis())
                    .put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                jobs.remove(runId)
            }
        }
        jobs[runId] = job
        return JSONObject().put("ok", true).put("runId", runId).put("status", "running")
    }

    /** 事件 → 进度快照。节点状态在快照里增量更新，UI 轮询即可看到实时进度。 */
    private fun applyEvent(snapshot: JSONObject, ev: AgentEvent) {
        runCatching {
            val nodes = snapshot.optJSONObject("nodes") ?: JSONObject().also { snapshot.put("nodes", it) }
            when (ev) {
                is AgentEvent.NodeStarted -> nodes.put(
                    ev.nodeId,
                    JSONObject()
                        .put("nodeId", ev.nodeId)
                        .put("role", ev.role)
                        .put("task", ev.task)
                        .put("status", "running")
                        .put("startedAt", System.currentTimeMillis())
                        .put("text", "")
                        .put("tools", JSONArray())
                )
                is AgentEvent.NodeFinished -> nodes.optJSONObject(ev.nodeId)?.apply {
                    put("status", ev.status)
                    put("ms", ev.ms)
                    put("finishedAt", System.currentTimeMillis())
                }
                is AgentEvent.ToolCalled -> nodes.optJSONObject(ev.nodeId)?.apply {
                    val tools = optJSONArray("tools") ?: JSONArray().also { put("tools", it) }
                    tools.put(JSONObject().put("name", ev.tool).put("ok", ev.ok))
                }
                is AgentEvent.TextDelta -> nodes.optJSONObject(ev.nodeId)?.apply {
                    put("text", optString("text") + ev.text)
                }
                is AgentEvent.Note -> {
                    val notes = snapshot.optJSONArray("notes") ?: JSONArray().also { snapshot.put("notes", it) }
                    notes.put(ev.message)
                }
            }
        }
    }

    fun status(runId: String): JSONObject? {
        live[runId]?.let { return JSONObject(it.toString()) }
        return null
    }

    fun cancel(runId: String): Boolean {
        val job = jobs[runId] ?: return false
        job.cancel()
        live[runId]?.put("status", "cancelled")
        return true
    }

    fun runningIds(): List<String> = jobs.keys.toList()

    /** 进行中 + 已落盘的运行合并列表（最新在前）。 */
    fun list(ctx: ToolContext, limit: Int): JSONArray {
        val out = JSONArray()
        live.values
            .sortedByDescending { it.optLong("startedAt") }
            .forEach { s ->
                out.put(
                    JSONObject()
                        .put("runId", s.optString("runId"))
                        .put("goal", s.optString("goal"))
                        .put("status", s.optString("status"))
                        .put("startedAt", s.optLong("startedAt"))
                        .put("live", true)
                )
            }
        val history = AgentOrchestrator.listRuns(ctx, limit)
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            if (live.containsKey(o.optString("runId"))) continue
            out.put(o)
        }
        return out
    }
}
