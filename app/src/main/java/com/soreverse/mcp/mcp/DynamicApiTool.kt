package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 独立动态分析网关（unidbg 模拟 + Frida 真机）。
 *
 * 手动把目标 .so/函数加载到执行上下文（unidbg 模拟内存，或 Frida 真机进程），执行后
 * 采集运行期证据（寄存器/内存/hook/回溯/trace），返回结构化 dynamicRun 供独立分析。
 *
 * 与 taffy_unidbg_api（纯 unidbg 网关）互补：本工具统一了 unidbg 会话与 Frida 真机会话，
 * 并提供 analyze 一键编排（加载→调用→采集）。
 *
 * 说明：Frida 侧需设备上运行 frida-server / frida-gadget（默认 127.0.0.1:27042）；
 * 不可达时会返回 FRIDA_UNAVAILABLE 并附原因，不影响 unidbg 侧的模拟能力。
 */
object DynamicApiTool {

    val tool: ToolHandler = EngineToolHandler(
        ToolMeta(
            "taffy_dynamic_api",
            "【独立动态分析网关】unidbg 模拟 + Frida 真机：手动加载 .so/函数到执行上下文→执行→" +
                "采集寄存器/内存/hook/回溯/trace，返回结构化 dynamicRun 供独立分析。" +
                "action=status/roots/methods/capabilities 做探测；action=dispatch 用 op 指定细粒度操作" +
                "（frida_status/sessions/open/close/hook/call/read/backtrace，unidbg_session_*）；" +
                "action=analyze 一键编排（backend=unidbg|frida）。Frida 需设备侧 frida-server 可达。",
            "Standalone dynamic-analysis gateway: manually load a target .so/function into an execution " +
                "context (Unidbg emulated memory or a live Frida process), run it, and collect runtime " +
                "evidence (registers/memory/hook/backtrace/trace) as a structured dynamicRun envelope. " +
                "action=status/roots/methods/capabilities probe; action=dispatch routes fine-grained ops " +
                "(frida_status/sessions/open/close/hook/call/read/backtrace, unidbg_session_*); " +
                "action=analyze orchestrates load->call->collect (backend=unidbg|frida).",
            "emulate", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "操作: status | roots | methods | capabilities | analyze | dispatch",
                    "status",
                    "roots",
                    "methods",
                    "capabilities",
                    "analyze",
                    "dispatch",
                )
                "workspaceId" str "工作区 ID（由 so_open 返回）"
                "editSessionId" str "编辑会话 ID（可空）"
                "op" str "dispatch 的子操作：frida_status | frida_sessions | frida_open | frida_close | " +
                    "frida_hook | frida_call | frida_read | frida_backtrace | unidbg_session_open | " +
                    "unidbg_session_call | unidbg_session_dump | unidbg_session_registers | " +
                    "unidbg_session_trace | unidbg_session_close"
                "method" str "子操作的第二参数（如符号名）"
                "args" str "位置参数（JSON 数组字符串）。analyze 时数组首元素为参数对象：" +
                    "{ backend, targetFunction, args, trace, dumpSize, dumpAddress, moduleName, fridaMode, fridaTarget/host/port }"
                "fridaTarget" str "Frida 守护进程连接参数（JSON：{ host, port, connectTimeoutMillis, readTimeoutMillis }）"
            })
        }
    ) { e, a, _ ->
        val workspaceId = a.str("workspaceId")
        val editSessionId = a.str("editSessionId")
        val action = a.str("action", "status")
        when (action) {
            "roots" -> e.dynamicDispatch(workspaceId, editSessionId, "roots", "", JSONArray())
            "methods" -> e.dynamicDispatch(workspaceId, editSessionId, "methods", "", JSONArray())
            "capabilities" -> e.dynamicDispatch(workspaceId, editSessionId, "capabilities", "", JSONArray())
            "status" -> e.dynamicDispatch(workspaceId, editSessionId, "status", "", positionalArgs(a))
            "analyze" -> {
                val params = a.optJSONObject("analyze") ?: JSONObject().apply {
                    a.str("backend").takeIf(String::isNotBlank)?.let { put("backend", it) }
                    a.str("targetFunction").takeIf(String::isNotBlank)?.let { put("targetFunction", it) }
                    positionalArgs(a).optJSONArray(0)?.let { put("args", it) }
                    if (a.has("trace")) put("trace", a.optBoolean("trace"))
                    if (a.has("dumpSize")) put("dumpSize", a.optInt("dumpSize"))
                    a.str("dumpAddress").takeIf(String::isNotBlank)?.let { put("dumpAddress", it) }
                }
                e.dynamicDispatch(workspaceId, editSessionId, "analyze", "", JSONArray().put(params))
            }
            else -> e.dynamicDispatch(
                workspaceId,
                editSessionId,
                a.str("op", "status"),
                a.str("method"),
                positionalArgs(a),
            )
        }
    }

    /** 兼容 MCP 客户端把参数传成 JSON 数组或 JSON 字符串两种形态。 */
    private fun positionalArgs(a: JSONObject): JSONArray {
        a.optJSONArray("args")?.let { return it }
        val raw = a.str("args")
        if (raw.isBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray().put(raw) }
    }

    val ALL: List<ToolHandler> = listOf(tool)
}
