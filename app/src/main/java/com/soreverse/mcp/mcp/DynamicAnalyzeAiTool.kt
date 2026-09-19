package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.DynamicAnalysisService
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 独立动态分析 AI。
 *
 * 对 taffy_dynamic_api（unidbg 模拟 / Frida 真机）采集到的 dynamicRun 运行期证据做独立 AI 分析，
 * 输出结构化 Markdown 报告。走 App 内已有的 AI 配置通道（设置 → AI：provider/endpoint/apiKey/model…），
 * AI 可在分析过程中自行调用 taffy_dynamic_api / taffy_unidbg_api / taffy_so_open 等工具补充取证。
 *
 * 两条分析通道：
 *  1) App 内 AI（本工具）：在设备上直接产出报告，适合无 PC 客户端的场景；
 *  2) MCP 客户端侧 AI：直接调用 taffy_dynamic_api(action=analyze) 拿回 dynamicRun 信封，交给客户端 AI 分析
 *     （不消耗设备侧 AI 配置，证据原样返回）。
 */
object DynamicAnalyzeAiTool {

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dynamic_analyze_ai",
            "【动态分析 AI】对 unidbg 模拟 / Frida 真机采集到的 dynamicRun 运行期证据做独立 AI 分析，" +
                "输出结构化报告。走 App 内 AI 配置（设置→AI）；AI 会自行调用动态工具补充取证。" +
                "若已有 dynamicRun 可用 dynamicRun 参数直接传入（跳过重新采集）。" +
                "另：taffy_dynamic_api(action=analyze) 返回的 dynamicRun 也可直接交给 MCP 客户端侧 AI 分析。",
            "Run the standalone dynamic-analysis AI over runtime evidence (unidbg emulated or Frida " +
                "on-device) collected by taffy_dynamic_api, producing a structured Markdown report. Uses " +
                "the App's configured AI (Settings -> AI); the agent may call dynamic MCP tools for " +
                "follow-up evidence. Pass an existing dynamicRun to skip re-collection. Note: the " +
                "dynamicRun returned by taffy_dynamic_api(action=analyze) can also be handed directly to a " +
                "client-side AI.",
            "emulate", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 ID（由 taffy_so_open 返回）"
                "editSessionId" str "编辑会话 ID（可空）"
                "backend" str "执行后端：unidbg（模拟）或 frida（真机）"
                "targetFunction" str "要手动加载并执行的目标导出/函数名"
                "targetPath" str "报告中展示的目标 .so 路径（可空，默认用 workspaceId）"
                "args" arr "传给目标函数的位置参数"
                "trace" bool "是否开启指令 trace"
                "dumpSize" int "内存 dump 字节数"
                "dumpAddress" str "要 dump 的十六进制地址"
                "dynamicRun" str "已有的 dynamicRun 证据（JSON 字符串）；提供时跳过重新执行 analyze"
                "request" str "本轮分析的用户关注点"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val workspaceId = args.str("workspaceId")
            val editSessionId = args.str("editSessionId")
            val evidence = if (args.str("dynamicRun").isNotBlank()) {
                args.str("dynamicRun")
            } else {
                val params = JSONObject().apply {
                    args.str("backend").takeIf(String::isNotBlank)?.let { put("backend", it) }
                    args.str("targetFunction").takeIf(String::isNotBlank)?.let { put("targetFunction", it) }
                    args.optJSONArray("args")?.let { put("args", it) }
                    if (args.has("trace")) put("trace", args.optBoolean("trace"))
                    if (args.has("dumpSize")) put("dumpSize", args.optInt("dumpSize"))
                    args.str("dumpAddress").takeIf(String::isNotBlank)?.let { put("dumpAddress", it) }
                }
                val dispatch = ctx.engine.dynamicDispatch(
                    workspaceId,
                    editSessionId,
                    "analyze",
                    "",
                    JSONArray().put(params),
                )
                // 先把失败的 dispatch 拦下，避免把错误 JSON 当作有效证据喂给 AI。
                if (!dispatch.optBoolean("ok", true) || dispatch.has("error")) {
                    return err(
                        "DYNAMIC_DISPATCH_FAILED",
                        dispatch.optJSONObject("error")?.optString("message")
                            ?: ("could not collect dynamic evidence: " + dispatch.toString().take(400)),
                        "backend",
                        params.optString("backend"),
                    )
                }
                dispatch.toString()
            }
            if (evidence.isBlank()) {
                return err("NO_DYNAMIC_EVIDENCE", "No dynamic evidence was produced; check backend/workspace/targetFunction.", "workspaceId", workspaceId)
            }
            val result = runCatching {
                DynamicAnalysisService(ctx.context).analyzeSync(
                    evidence,
                    args.str("targetPath").ifBlank { workspaceId },
                    ctx.settings,
                    zh = true,
                    request = args.str("request"),
                ).getOrThrow()
            }
            return result.fold(
                onSuccess = { ok(JSONObject().put("report", it).put("evidence", evidence.take(8192))) },
                onFailure = {
                    err("DYNAMIC_AI_FAILED", it.message ?: "AI dynamic analysis failed", "workspaceId", workspaceId)
                },
            )
        }
    }

    val ALL: List<ToolHandler> = listOf(tool)
}
