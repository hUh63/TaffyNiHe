package com.soreverse.mcp.mcp.agent

import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.RikkaAgentEngine
import com.soreverse.mcp.core.RikkaPart
import com.soreverse.mcp.mcp.ToolContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 单个子代理的执行器。
 *
 * 一次 run 只做一件事：给定角色和任务，在自己的工具白名单里取证，产出带 EVIDENCE 的结论。
 * 所有跨节点的调度、并行、预算共享都在 [AgentOrchestrator] 里，这里刻意不管——这样单测和
 * 排查都能只看一个节点。
 */
internal class SubAgentRunner(
    private val ctx: ToolContext,
    private val engine: RikkaAgentEngine,
    private val zh: Boolean,
    private val budget: AgentBudget,
    private val runId: String,
    private val onEvent: (AgentEvent) -> Unit,
) {

    /** 单个节点的硬超时上限（还要再受全局 deadline 约束）。 */
    private val nodeTimeoutCapMs = 5 * 60_000L

    suspend fun run(
        node: AgentNode,
        workspaceId: String,
        upstream: String,
        maxSteps: Int,
    ): NodeResult {
        val started = System.currentTimeMillis()
        val role = AgentRoles.byId(node.roleId)
            ?: return NodeResult(
                nodeId = node.id,
                role = node.roleId,
                task = node.task,
                status = "failed",
                text = "",
                error = "unknown role: ${node.roleId}",
                ms = 0L,
            )

        if (!budget.healthy()) {
            return NodeResult(
                nodeId = node.id,
                role = role.id,
                task = node.task,
                status = "skipped_budget",
                text = "",
                error = if (budget.cancelled()) "cancelled" else "budget exhausted",
                ms = 0L,
            )
        }
        if (!budget.tryConsumeAgent()) {
            return NodeResult(
                nodeId = node.id,
                role = role.id,
                task = node.task,
                status = "skipped_budget",
                text = "",
                error = "sub-agent quota reached (${budget.maxSubAgents})",
                ms = 0L,
            )
        }

        onEvent(AgentEvent.NodeStarted(node.id, role.id, node.task))

        val used = java.util.Collections.synchronizedList(ArrayList<String>())
        val toolSet = AgentKernel.catalogTools(
            ctx = ctx,
            names = role.toolNames,
            zh = zh,
            nodeId = node.id,
            budget = budget,
            workspaceId = { workspaceId },
        ) { ev ->
            if (ev is AgentEvent.ToolCalled) used.add(ev.tool)
            onEvent(ev)
        }

        val system = AgentRoles.systemPrompt(role, zh, workspaceId)
        val user = AgentRoles.taskPrompt(role, node.task, zh, upstream)
        val steps = maxSteps.coerceIn(2, role.suggestedSteps.coerceAtLeast(2))

        val remaining = (budget.wallClockMs - budget.elapsedMs()).coerceAtLeast(1_000L)
        val timeoutMs = minOf(nodeTimeoutCapMs, remaining)

        var streamed = ""
        val produced: String? = try {
            withTimeoutOrNull(timeoutMs) {
                engine.run(
                    systemPrompt = system,
                    userPrompt = user,
                    tools = toolSet.tools,
                    maxSteps = steps,
                ) { parts ->
                    val text = parts.filterIsInstance<RikkaPart.Text>().joinToString("") { it.text }
                    if (text.length > streamed.length) {
                        onEvent(AgentEvent.TextDelta(node.id, text.substring(streamed.length)))
                        streamed = text
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: Throwable) {
            AppLog.e("agent[$runId/${node.id}] run failed", e)
            onEvent(AgentEvent.NodeFinished(node.id, "failed", System.currentTimeMillis() - started))
            return NodeResult(
                nodeId = node.id,
                role = role.id,
                task = node.task,
                status = "failed",
                text = streamed,
                toolsUsed = used.toList(),
                unresolvedTools = toolSet.unresolved,
                ms = System.currentTimeMillis() - started,
                error = e.message ?: e.javaClass.simpleName,
            )
        }

        val ms = System.currentTimeMillis() - started
        if (produced == null) {
            onEvent(AgentEvent.NodeFinished(node.id, "timeout", ms))
            return NodeResult(
                nodeId = node.id,
                role = role.id,
                task = node.task,
                status = "timeout",
                text = streamed,
                evidence = AgentEvidenceParser.parse(streamed),
                toolsUsed = used.toList(),
                unresolvedTools = toolSet.unresolved,
                ms = ms,
                error = "node timeout after ${timeoutMs}ms",
            )
        }

        val body = AgentEvidenceParser.body(produced)
        val evidence = AgentEvidenceParser.parse(produced)
        val reportPath = persist(node.id, role.id, node.task, produced)
        onEvent(AgentEvent.NodeFinished(node.id, "ok", ms))
        return NodeResult(
            nodeId = node.id,
            role = role.id,
            task = node.task,
            status = "ok",
            text = body,
            evidence = evidence,
            toolsUsed = used.toList(),
            unresolvedTools = toolSet.unresolved,
            ms = ms,
            reportPath = reportPath,
        )
    }

    /**
     * 长报告落盘，只在结果里保留路径。
     *
     * 目的是让编排器的上下文不被单个节点的长篇推理撑爆——后续节点需要细节时按路径取，
     * 而汇总节点只需要结论。
     */
    private fun persist(nodeId: String, roleId: String, task: String, text: String): String {
        if (text.length < 8_000) return ""
        return runCatching {
            val dir = File(File(ctx.context.filesDir, "agent-runs"), runId)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${nodeId}.md")
            file.writeText(
                buildString {
                    appendLine("# $nodeId · $roleId")
                    appendLine()
                    appendLine(task)
                    appendLine()
                    appendLine("---")
                    appendLine()
                    appendLine(text)
                },
                Charsets.UTF_8,
            )
            file.absolutePath
        }.getOrElse {
            AppLog.w("agent[$runId] persist report failed: ${it.message}")
            ""
        }
    }

    companion object {
        fun summarize(results: List<NodeResult>, zh: Boolean): String {
            if (results.isEmpty()) return ""
            return buildString {
                results.forEach { r ->
                    appendLine("### ${r.role} · ${r.nodeId} (${r.status})")
                    if (r.task.isNotBlank()) appendLine("任务：${r.task}")
                    val body = r.text.trim()
                    appendLine(if (body.length > 1200) body.take(1200) + "…" else body)
                    if (r.evidence.isNotEmpty()) {
                        appendLine("证据：")
                        r.evidence.take(12).forEach { e ->
                            appendLine("- ${e.claim} @ ${e.where} [${e.confidence}]")
                        }
                    }
                    if (r.error.isNotBlank()) appendLine("错误：${r.error}")
                    appendLine()
                }
            }.trimEnd()
        }

        fun emptyBudgetJson(): JSONObject = JSONObject().put("note", "no budget")
    }
}
