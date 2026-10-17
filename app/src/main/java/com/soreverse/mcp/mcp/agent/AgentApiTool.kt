package com.soreverse.mcp.mcp.agent

import com.soreverse.mcp.mcp.ToolClass
import com.soreverse.mcp.mcp.ToolContext
import com.soreverse.mcp.mcp.ToolHandler
import com.soreverse.mcp.mcp.ToolMeta

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 多智能体编排网关。
 *
 * 与「AI 深度分析」的区别：那是一个 agent 从头做到尾，上下文越跑越长、工具面越用越杂；
 * 这里是「派一组专职子代理」，每个角色只拿到自己的工具白名单，各干一段，最后汇总。
 *
 * 运行是异步的：action=run 立刻返回 runId，进度用 action=status 轮询，随时可 cancel。
 * 这样不会被 MCP 客户端超时掐断，也不会因为一次长任务把调用方挂住。
 */
object AgentApiTool {

    private fun rolesJson(zh: Boolean): JSONArray {
        val arr = JSONArray()
        AgentRoles.ALL.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name(zh))
                    .put("goal", r.goal(zh))
                    .put("tools", JSONArray(r.toolNames))
                    .put("suggestedSteps", r.suggestedSteps)
            )
        }
        return arr
    }

    val run: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_agent_run",
            "【多智能体编排】把一次逆向取证目标拆成多个专职子代理并行/串行执行，最后汇总成结论。子代理角色：recon(侦察形状) / strings(字符串与常量) / code(代码层) / flow(控制流) / packer(加固脱壳) / report(汇总)。每个角色只被授予自己的工具白名单，上下文短、不易跑偏；上游结论会作为下游节点的输入。action=roles 看角色清单，plan 只出计划不执行，run 启动后台运行并立刻返回 runId，status 轮询进度（含每个节点状态/工具调用/流式输出），get 取完整记录，list 列历史，cancel 中止。预算可设（子代理数/工具调用数/总时长），超限即停并标注 skipped_budget。",
            "【Multi-agent orchestration】Split one reverse-engineering goal across dedicated sub-agents that run in parallel/series, then synthesise a conclusion. Roles: recon / strings / code / flow / packer / report. Each role is hard-restricted to its own tool whitelist, so context stays short and focused; upstream findings are fed into downstream nodes. action=roles lists roles, plan returns the DAG without running, run starts a background orchestration and returns a runId immediately, status polls progress (per-node state, tool calls, streaming text), get loads the full record, list shows history, cancel aborts. Budgets (sub-agents / tool calls / wall clock) are enforced and exceeded nodes are marked skipped_budget.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(props {
                "action" str "roles(默认)=角色清单 | plan=只出计划 | run=启动 | status=查进度 | get=完整记录 | list=历史 | cancel=中止"
                "goal" str "run/plan 必填：这次要查清什么（如「这个 so 有没有反调试、字符串是否加密、壳是哪家」）"
                "workspaceId" str "已打开的工作区 ID（与 path 二选一）"
                "path" str "目标文件绝对路径：未给 workspaceId 时自动打开临时工作区"
                "roles" str "只跑这些角色（逗号分隔，如 recon,packer）；留空=按默认计划全跑"
                "maxSubAgents" int "子代理上限（默认 5，1~8）"
                "maxToolCalls" int "工具调用总上限（默认 80，10~400）"
                "wallClockMs" int "总时长上限毫秒（默认 480000=8 分钟，60000~1800000）"
                "maxSteps" int "单个子代理的最大工具步数（默认 14）"
                "runId" str "status/get/cancel 必填：run 返回的运行 ID"
                "limit" int "list 返回条数（默认 20）"
                "zh" bool "true=中文提示词与输出（默认 true）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "roles").ifBlank { "roles" }.lowercase()
            val zh = args.bool("zh", true)
            return when (action) {
                "roles" -> ok(
                    JSONObject()
                        .put("roles", rolesJson(zh))
                        .put("note", "每个角色的工具白名单是硬约束：模型看不到白名单外的工具。")
                )

                "plan" -> {
                    val goal = args.str("goal").trim()
                    if (goal.isBlank()) {
                        err("INVALID_ARGUMENT", "plan 需要 goal（这次要查清什么）", "goal", "")
                    } else {
                        val nodes = AgentOrchestrator.plan(goal, roleIds(args))
                        val arr = JSONArray()
                        nodes.forEach { n ->
                            arr.put(
                                JSONObject()
                                    .put("id", n.id)
                                    .put("role", n.roleId)
                                    .put("task", n.task)
                                    .put("dependsOn", JSONArray(n.dependsOn))
                                    .put("aggregate", n.aggregate)
                            )
                        }
                        ok(JSONObject().put("goal", goal).put("nodeCount", nodes.size).put("plan", arr))
                    }
                }

                "run" -> {
                    val goal = args.str("goal").trim()
                    if (goal.isBlank()) {
                        err("INVALID_ARGUMENT", "run 需要 goal（这次要查清什么）", "goal", "")
                    } else {
                        AgentRuntime.start(
                            ctx = ctx,
                            goal = goal,
                            workspaceId = args.str("workspaceId").trim(),
                            path = args.str("path").trim(),
                            roles = roleIds(args),
                            maxSubAgents = args.intValue("maxSubAgents", 5),
                            maxToolCalls = args.intValue("maxToolCalls", 80),
                            wallClockMs = args.intValue("wallClockMs", 8 * 60_000).toLong(),
                            maxStepsPerNode = args.intValue("maxSteps", 14),
                            zh = zh,
                        )
                    }
                }

                "status" -> {
                    val runId = args.str("runId").trim()
                    if (runId.isBlank()) {
                        err("INVALID_ARGUMENT", "status 需要 runId", "runId", "")
                    } else {
                        val live = AgentRuntime.status(runId)
                        if (live != null) {
                            ok(live)
                        } else {
                            val saved = AgentOrchestrator.loadRun(ctx, runId)
                            if (saved == null) err("NOT_FOUND", "找不到该运行：$runId", "runId", runId)
                            else ok(saved)
                        }
                    }
                }

                "get" -> {
                    val runId = args.str("runId").trim()
                    if (runId.isBlank()) {
                        err("INVALID_ARGUMENT", "get 需要 runId", "runId", "")
                    } else {
                        val live = AgentRuntime.status(runId)
                            ?: AgentOrchestrator.loadRun(ctx, runId)
                        if (live == null) err("NOT_FOUND", "找不到该运行：$runId", "runId", runId) else ok(live)
                    }
                }

                "list" -> ok(
                    JSONObject()
                        .put("runs", AgentRuntime.list(ctx, args.intValue("limit", 20)))
                        .put("running", JSONArray(AgentRuntime.runningIds()))
                )

                "cancel" -> {
                    val runId = args.str("runId").trim()
                    if (runId.isBlank()) {
                        err("INVALID_ARGUMENT", "cancel 需要 runId", "runId", "")
                    } else if (AgentRuntime.cancel(runId)) {
                        ok(JSONObject().put("runId", runId).put("status", "cancelled"))
                    } else {
                        err("NOT_RUNNING", "该运行不在进行中（可能已结束）：$runId", "runId", runId)
                    }
                }

                else -> err(
                    "INVALID_ARGUMENT",
                    "未知 action: $action（可选 roles / plan / run / status / get / list / cancel）",
                    "action",
                    action
                )
            }
        }
    }

    private fun roleIds(args: JSONObject): List<String>? {
        val raw = args.str("roles").trim()
        if (raw.isBlank()) return null
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    val ALL: List<ToolHandler> = listOf(run)
}
