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
import com.soreverse.mcp.core.safeLaunch

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
            .put("workspaceId", workspaceId)
            .put("status", "running")
            .put("startedAt", System.currentTimeMillis())
            .put("plan", JSONArray())
            .put("nodes", JSONObject())
            .put("notes", JSONArray())
        live[runId] = snapshot

        val job = scope.safeLaunch {
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
                    // 必须把自己的 runId 传下去：否则落盘目录用的是另一套 id，
                    // 客户端拿到的 id 去 resume 永远 NOT_FOUND
                    runId = runId,
                ) { ev -> applyEvent(snapshot, ev, runId) }
                snapshot
                    .put("status", record.status)
                    .put("finishedAt", record.finishedAt)
                    .put("conclusion", record.conclusion)
                    .put("record", record.toJson())
            } catch (e: kotlinx.coroutines.CancellationException) {
                snapshot.put("status", "cancelled").put("finishedAt", System.currentTimeMillis())
                // 取消也要落盘：否则已跑完的节点结论全丢、历史里也看不到这次运行
                AgentOrchestrator.persistCancelled(
                    ctx,
                    runId,
                    snapshot.optString("goal"),
                    snapshot.optString("workspaceId"),
                    snapshot.optLong("startedAt"),
                    snapshot,
                )
                throw e
            } catch (e: Throwable) {
                AppLog.e("agent[$runId] orchestration failed", e)
                snapshot
                    .put("status", "failed")
                    .put("finishedAt", System.currentTimeMillis())
                    .put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                jobs.remove(runId)
                live[runId]?.let { s -> synchronized(s) { s.remove("record") } }
                pruneFinished()
            }
        }
        jobs[runId] = job
        return JSONObject().put("ok", true).put("runId", runId).put("status", "running")
    }

    /**
     * 从落盘记录续跑一次已中断的运行。
     *
     * 已有节点的结果先回填到进度快照里（UI 一进入就能看到「哪些已经做完」），
     * 然后只跑没做完的；同名节点若留有消息历史，子代理会带着历史继续。
     */
    fun resume(
        ctx: ToolContext,
        runId: String,
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
                        .put("message", "AI 未配置：请填写 endpoint / apiKey / model（缺 ${missing.joinToString()}）")
                        .put("missing", JSONArray(missing))
                )
        }
        val saved = AgentOrchestrator.loadRun(ctx, runId)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", JSONObject().put("code", "NOT_FOUND").put("message", "找不到该运行：$runId"))
        if (jobs.containsKey(runId)) {
            return JSONObject()
                .put("ok", false)
                .put("error", JSONObject().put("code", "ALREADY_RUNNING").put("message", "该运行仍在进行中"))
        }

        val snapshot = JSONObject()
            .put("runId", runId)
            .put("goal", saved.optString("goal"))
            .put("workspaceId", saved.optString("workspaceId"))
            .put("status", "running")
            .put("resumed", true)
            .put("startedAt", saved.optLong("startedAt", System.currentTimeMillis()))
            .put("notes", JSONArray())
        // 回填已完成节点
        val nodes = JSONObject()
        saved.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optString("nodeId")
                if (id.isBlank()) continue
                nodes.put(id, r)
            }
        }
        snapshot.put("nodes", nodes)
        live[runId] = snapshot

        val job = scope.safeLaunch {
            try {
                val record = AgentOrchestrator.resume(
                    ctx = ctx,
                    runId = runId,
                    maxSubAgents = maxSubAgents,
                    maxToolCalls = maxToolCalls,
                    wallClockMs = wallClockMs,
                    maxStepsPerNode = maxStepsPerNode,
                    zh = zh,
                ) { ev -> applyEvent(snapshot, ev, runId) }
                snapshot
                    .put("status", record.status)
                    .put("finishedAt", record.finishedAt)
                    .put("conclusion", record.conclusion)
                    .put("record", record.toJson())
            } catch (e: kotlinx.coroutines.CancellationException) {
                snapshot.put("status", "cancelled").put("finishedAt", System.currentTimeMillis())
                // 取消也要落盘：否则已跑完的节点结论全丢、历史里也看不到这次运行
                AgentOrchestrator.persistCancelled(
                    ctx,
                    runId,
                    snapshot.optString("goal"),
                    snapshot.optString("workspaceId"),
                    snapshot.optLong("startedAt"),
                    snapshot,
                )
                throw e
            } catch (e: Throwable) {
                AppLog.e("agent[$runId] resume failed", e)
                snapshot
                    .put("status", "failed")
                    .put("finishedAt", System.currentTimeMillis())
                    .put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                jobs.remove(runId)
                live[runId]?.let { s -> synchronized(s) { s.remove("record") } }
                pruneFinished()
            }
        }
        jobs[runId] = job
        return JSONObject().put("ok", true).put("runId", runId).put("status", "running").put("resumed", true)
    }

    /** 事件 → 进度快照。节点状态在快照里增量更新，UI 轮询即可看到实时进度。 */
    private fun applyEvent(snapshot: JSONObject, ev: AgentEvent, runId: String) {
        // 并行节点会同时回写同一份快照：JSONObject 非线程安全，读改写会互相覆盖（正文缺段、工具丢失）
        synchronized(snapshot) {
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
    }

    fun status(runId: String): JSONObject? {
        val s = live[runId] ?: return null
        // 与 applyEvent 的写共用同一把锁：并发 toString 会抛 ConcurrentModificationException
        return synchronized(s) { runCatching { JSONObject(s.toString()) }.getOrNull() }
    }

    fun cancel(runId: String): Boolean {
        val job = jobs[runId] ?: return false
        job.cancel()
        live[runId]?.let { s -> synchronized(s) { s.put("status", "cancelled") } }
        return true
    }

    /** 终态快照只保留状态：record 里是全部节点正文（几 MB～几十 MB），不能长期驻留。 */
    private fun pruneFinished() {
        val now = System.currentTimeMillis()
        live.entries.removeAll { (_, s) ->
            val st = synchronized(s) { s.optString("status") }
            st != "running" && now - s.optLong("finishedAt", now) > 10 * 60_000L
        }
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
