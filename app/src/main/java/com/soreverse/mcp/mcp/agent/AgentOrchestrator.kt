package com.soreverse.mcp.mcp.agent

import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多智能体编排器。
 *
 * 三段式，每段都可单独调用：
 *  1. [plan] —— 产出一个可审阅的 DAG（谁做什么、依赖谁），不花钱也不动文件
 *  2. [run]  —— 按 DAG 调度：无依赖的节点并行，有依赖的等前置结论当输入
 *  3. [record] —— 全量落盘（run.json + 各节点长报告），可回看
 *
 * 刻意与「一个万能 agent 从头做到尾」不同：分工的价值是每个角色只看自己需要的工具，
 * 上下文短、跑偏少；代价是节点之间的信息要显式传递——所以上游结论会作为 prompt 的一部分
 * 传下去，而不是靠共享对话历史。
 */
internal object AgentOrchestrator {

    /** 并发度：模型 API 与本地内存都扛不住太多并发。 */
    private const val CONCURRENCY = 2

    /**
     * 启发式计划。不调模型——计划本身要稳定可预期，模型该花在取证上。
     *
     * 依赖结构：recon 先跑（确定是 APK/SO、架构、有没有壳），然后字符串 / 代码 / 加固
     * 三路并行，控制流依赖代码层（需要先知道有哪些函数才看 CFG），最后汇总。
     */
    fun plan(goal: String, roles: List<String>? = null): List<AgentNode> {
        val wanted = roles
            ?.mapNotNull { AgentRoles.byId(it)?.id }
            ?.takeIf { it.isNotEmpty() }
        fun on(id: String) = wanted == null || wanted.contains(id)

        val nodes = ArrayList<AgentNode>()
        if (on("recon")) {
            nodes.add(AgentNode("n1-recon", "recon", "摸清目标形状：文件类型/架构/入口/段布局/依赖库/是否加壳。目标：$goal"))
        }
        val reconId = nodes.firstOrNull()?.id

        if (on("strings")) {
            nodes.add(
                AgentNode(
                    "n2-strings",
                    "strings",
                    "从字符串侧取证：可疑 URL/密钥/协议/路径、它们的交叉引用、加密字符串段、导入符号来源。",
                    dependsOn = listOfNotNull(reconId),
                )
            )
        }
        if (on("code")) {
            nodes.add(
                AgentNode(
                    "n3-code",
                    "code",
                    "在代码层定位关键实现：可疑函数、反汇编片段、伪 C 结构、函数签名、JNI 注册表。",
                    dependsOn = listOfNotNull(reconId),
                )
            )
        }
        if (on("packer")) {
            nodes.add(
                AgentNode(
                    "n4-packer",
                    "packer",
                    "判断加固方案与可脱壳路径：壳厂商与结构证据、反调试点、是否需要 dump 与修复。",
                    dependsOn = listOfNotNull(reconId),
                )
            )
        }
        if (on("flow")) {
            nodes.add(
                AgentNode(
                    "n5-flow",
                    "flow",
                    "从控制流侧判断：调用关系、CFG 形态、是否被平坦化/加假边、能否还原。",
                    dependsOn = listOfNotNull(nodes.firstOrNull { it.roleId == "code" }?.id ?: reconId),
                )
            )
        }
        nodes.add(
            AgentNode(
                "n9-report",
                "report",
                "交叉印证各子代理的证据，写成结论：能确定什么、不确定什么、下一步做什么。目标：$goal",
                dependsOn = nodes.map { it.id },
                aggregate = true,
            )
        )
        return nodes
    }

    /**
     * 执行一条编排。
     *
     * @param workspaceId 已打开的工作区（为空时若给了 path 会先自动打开）
     * @param path 目标文件路径（workspaceId 为空时用）
     */
    suspend fun run(
        ctx: ToolContext,
        goal: String,
        workspaceId: String = "",
        path: String = "",
        roles: List<String>? = null,
        maxSubAgents: Int = 5,
        maxToolCalls: Int = 80,
        wallClockMs: Long = 8 * 60_000L,
        maxStepsPerNode: Int = 14,
        zh: Boolean = true,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentRunRecord {
        val runId = newRunId()
        val budget = newBudget(maxSubAgents, maxToolCalls, wallClockMs)

        // 工作区：优先用传入的；否则用 path 打开（避免让模型去 open，浪费一轮）
        var ws = workspaceId
        if (ws.isBlank() && path.isNotBlank()) {
            ws = openWorkspace(ctx, path)
            if (ws.isNotBlank()) onEvent(AgentEvent.Note("workspace opened: $ws"))
        }

        return execute(
            ctx = ctx,
            runId = runId,
            goal = goal,
            ws = ws,
            startedAt = System.currentTimeMillis(),
            nodes = plan(goal, roles),
            preDone = emptyMap(),
            budget = budget,
            zh = zh,
            maxStepsPerNode = maxStepsPerNode,
            onEvent = onEvent,
        )
    }

    /**
     * 从落盘的 run.json 续跑。
     *
     * 只跳过 `status == "ok"` 的节点：失败/超时/预算跳过的节点会被重跑——续跑的意义是把没做完的
     * 做完，而不是把失败结果原样固化。若某节点上次留下了消息历史（进程被杀时），
     * [SubAgentRunner] 会自动带着历史继续，不会从头再来。
     */
    suspend fun resume(
        ctx: ToolContext,
        runId: String,
        maxSubAgents: Int = 5,
        maxToolCalls: Int = 80,
        wallClockMs: Long = 8 * 60_000L,
        maxStepsPerNode: Int = 14,
        zh: Boolean = true,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentRunRecord {
        val saved = loadRun(ctx, runId)
            ?: return AgentRunRecord(
                runId = runId,
                goal = "",
                workspaceId = "",
                startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(),
                status = "failed",
                conclusion = "找不到运行记录：$runId",
                plan = emptyList(),
                results = emptyList(),
                budget = JSONObject(),
            )
        val nodes = AgentRecordCodec.decodePlan(saved)
        val preDone = AgentRecordCodec.decodeResults(saved)
            .filter { it.status == "ok" }
            .associateBy { it.nodeId }
        onEvent(AgentEvent.Note("resume $runId: ${preDone.size}/${nodes.size} nodes already completed"))
        return execute(
            ctx = ctx,
            runId = runId,
            goal = saved.optString("goal"),
            ws = saved.optString("workspaceId"),
            startedAt = saved.optLong("startedAt", System.currentTimeMillis()),
            nodes = nodes,
            preDone = preDone,
            budget = newBudget(maxSubAgents, maxToolCalls, wallClockMs),
            zh = zh,
            maxStepsPerNode = maxStepsPerNode,
            onEvent = onEvent,
        )
    }

    private fun newRunId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(4)

    private fun newBudget(maxSubAgents: Int, maxToolCalls: Int, wallClockMs: Long): AgentBudget = AgentBudget(
        maxSubAgents = maxSubAgents.coerceIn(1, 8),
        maxToolCalls = maxToolCalls.coerceIn(10, 400),
        wallClockMs = wallClockMs.coerceIn(60_000L, 30 * 60_000L),
    )

    private suspend fun execute(
        ctx: ToolContext,
        runId: String,
        goal: String,
        ws: String,
        startedAt: Long,
        nodes: List<AgentNode>,
        preDone: Map<String, NodeResult>,
        budget: AgentBudget,
        zh: Boolean,
        maxStepsPerNode: Int,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRunRecord {
        val engine = AgentKernel.engine(ctx.settings)
        val runner = SubAgentRunner(ctx, engine, zh, budget, runId, onEvent)
        onEvent(AgentEvent.Note("plan: ${nodes.size} nodes, ${preDone.size} already completed"))

        val results = LinkedHashMap<String, NodeResult>()
        results.putAll(preDone)
        val pending = LinkedHashMap<String, AgentNode>()
        nodes.forEach { if (!results.containsKey(it.id)) pending[it.id] = it }

        // 拓扑分层执行
        while (pending.isNotEmpty()) {
            val ready = pending.values.filter { node -> node.dependsOn.all { results.containsKey(it) } }
            if (ready.isEmpty()) {
                // 依赖成环或缺前置：剩余节点标记跳过，不静默丢弃
                pending.values.forEach { node ->
                    results[node.id] = NodeResult(
                        nodeId = node.id,
                        role = node.roleId,
                        task = node.task,
                        status = "skipped_dependency",
                        text = "",
                        error = "unresolved dependencies: ${node.dependsOn.joinToString()}",
                    )
                }
                break
            }
            // 汇总节点不与其他节点并行：它要读全部前置结论
            val batch = if (ready.size == 1 && ready.first().aggregate) ready else ready.filterNot { it.aggregate }.ifEmpty { ready }

            coroutineScope {
                batch.map { node ->
                    async(Dispatchers.Default) {
                        val upstream = upstreamFor(node, results)
                        try {
                            runner.run(node, ws, upstream, maxStepsPerNode)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            AppLog.e("agent[$runId] node ${node.id} crashed", e)
                            NodeResult(
                                nodeId = node.id,
                                role = node.roleId,
                                task = node.task,
                                status = "failed",
                                text = "",
                                error = e.message ?: e.javaClass.simpleName,
                            )
                        }
                    }
                }.awaitAll()
            }.forEach { r ->
                results[r.nodeId] = r
                pending.remove(r.nodeId)
            }

            if (!budget.healthy() && pending.isNotEmpty()) {
                pending.values.forEach { node ->
                    results[node.id] = NodeResult(
                        nodeId = node.id,
                        role = node.roleId,
                        task = node.task,
                        status = if (budget.cancelled()) "cancelled" else "skipped_budget",
                        text = "",
                        error = if (budget.cancelled()) "cancelled" else "wall clock budget exhausted",
                    )
                }
                pending.clear()
                break
            }
        }

        // 结论：优先用 report 节点产出；它失败就用本地汇总兜底（不空手而归）
        val ordered = nodes.mapNotNull { results[it.id] }
        val reportNode = ordered.firstOrNull { it.role == "report" }
        val conclusion = reportNode?.text?.takeIf { it.isNotBlank() }
            ?: SubAgentRunner.summarize(ordered.filterNot { it.role == "report" }, zh)

        val record = AgentRunRecord(
            runId = runId,
            goal = goal,
            workspaceId = ws,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            status = if (ordered.any { it.status == "ok" }) "completed" else "failed",
            conclusion = conclusion,
            plan = nodes,
            results = ordered,
            budget = budget.toJson(),
        )
        withContext(Dispatchers.IO) { persistRecord(ctx, record) }
        return record
    }

    private fun upstreamFor(node: AgentNode, results: Map<String, NodeResult>): String {
        if (node.dependsOn.isEmpty()) return ""
        val sb = StringBuilder()
        node.dependsOn.forEach { id ->
            val r = results[id] ?: return@forEach
            sb.append("### ").append(r.role).append(" / ").append(r.nodeId).append(" (").append(r.status).append(")\n")
            val body = r.text.trim()
            sb.append(if (body.length > 1500) body.take(1500) + "…" else body).append("\n")
            if (r.evidence.isNotEmpty()) {
                r.evidence.take(10).forEach { e ->
                    sb.append("- ").append(e.claim).append(" @ ").append(e.where)
                        .append(" [").append(e.confidence).append("]\n")
                }
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun openWorkspace(ctx: ToolContext, path: String): String {
        return runCatching {
            val handler = ToolCatalog.byName["taffy_so_open"] ?: return ""
            val payload = handler.handle(
                ctx,
                JSONObject().put("path", path).put("temporary", true)
            )
            if (!payload.optBoolean("ok", false)) "" else AgentKernel.workspaceOf(payload)
        }.getOrDefault("")
    }

    private fun persistRecord(ctx: ToolContext, record: AgentRunRecord) {
        runCatching {
            val dir = File(File(ctx.context.filesDir, "agent-runs"), record.runId)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "run.json").writeText(record.toJson().toString(2), Charsets.UTF_8)
            File(dir, "conclusion.md").writeText(record.conclusion, Charsets.UTF_8)
        }.onFailure { AppLog.w("agent[${record.runId}] persist record failed: ${it.message}") }
    }

    /** 列出历史运行（按目录名倒序，最新在前）。 */
    fun listRuns(ctx: ToolContext, limit: Int = 20): JSONArray {
        val out = JSONArray()
        val root = File(ctx.context.filesDir, "agent-runs")
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return out
        dirs.take(limit.coerceIn(1, 100)).forEach { dir ->
            val runFile = File(dir, "run.json")
            val o = runCatching { JSONObject(runFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return@forEach
            out.put(
                JSONObject()
                    .put("runId", o.optString("runId", dir.name))
                    .put("goal", o.optString("goal"))
                    .put("status", o.optString("status"))
                    .put("startedAt", o.optLong("startedAt"))
                    .put("finishedAt", o.optLong("finishedAt"))
                    .put("nodes", o.optJSONArray("results")?.length() ?: 0)
                    .put("conclusion", o.optString("conclusion").take(400))
            )
        }
        return out
    }

    /** 读一次历史运行的完整记录。 */
    fun loadRun(ctx: ToolContext, runId: String): JSONObject? {
        val safe = runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(File(ctx.context.filesDir, "agent-runs"), "$safe/run.json")
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }
}
