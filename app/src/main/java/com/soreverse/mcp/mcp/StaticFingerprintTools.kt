package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.NativeSoEngine
import org.json.JSONObject

/**
 * 塔菲逆核: SO 静态指纹三件套 —— 加固指纹、混淆度、反调试点。
 *
 * 与既有的文件层工具分工明确:
 *  - `taffy_apk_shell_check` / `taffy_anti_debug_check` 在**字节层**按特征子串匹配整个 APK
 *    （能回答「这个包像不像某家的壳」），但答不出「哪个段符合、熵是多少、证据权重几成」；
 *  - 本文件的三个工具在 **ELF 结构层**做判定: 段名/节名/导入符号/符号命名/CFG 形态/熵分布,
 *    每条结论都带证据列表与置信度, 能回答「凭什么说是它」。
 *
 * 三者互补, 不替代: 文件层先粗筛（要不要深挖）, 结构层再定量（是哪家、多确定、卡在哪）。
 */
object StaticFingerprintTools {

    private class WsRef(val workspaceId: String, val error: JSONObject?)

    private fun workspaceOf(engine: NativeSoEngine, args: JSONObject): WsRef {
        val explicit = args.str("workspaceId").trim()
        if (explicit.isNotBlank()) return WsRef(explicit, null)
        val p = args.str("path").ifBlank { args.str("filePath") }.trim()
        if (p.isBlank()) {
            return WsRef(
                "",
                err(
                    "INVALID_ARGUMENT",
                    "缺少 workspaceId 或 path（可只传 path 自动打开临时工作区）",
                    "workspaceId",
                    ""
                )
            )
        }
        val opened = engine.open(p, true)
        if (!opened.optBoolean("ok", false)) return WsRef("", opened)
        val id = opened.optString("workspaceId")
        if (id.isBlank()) {
            return WsRef("", err("OPEN_FAILED", "打开文件后引擎未返回 workspaceId", "path", p))
        }
        return WsRef(id, null)
    }

    val packerFingerprint: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_packer_fingerprint",
            "【SO 加固指纹】在 ELF 结构层判定这个 .so 用了哪家加固/虚拟化/字符串加密，并给出置信度与逐条证据（命中的段名、节名异常、导入符号组合、符号命名风格、代码段熵）。与文件层特征扫描 taffy_apk_shell_check 互补：那个回答「像不像某壳」，这个回答「根据哪些结构特征、确定性多高、还能从哪验证」。纯静态只读，不执行目标代码。",
            "Identify which protector / virtualizer / string-encryption product a .so uses, judged at the ELF structural level with a confidence grade and itemised evidence (matched section names, abnormal section layout, import-symbol combinations, symbol naming style, code-segment entropy). Complements the byte-level signature scan taffy_apk_shell_check: that answers 'does it look like X', this answers 'on which structural evidence, how certain, and where to verify further'. Read-only, never executes target code.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区"
                "filePath" str "path 的别名"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val engine = EngineProvider.get(ctx.context)
            val ws = workspaceOf(engine, args)
            ws.error?.let { return it }
            return engine.packerScan(ws.workspaceId, "")
        }
    }

    val obfuscScan: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_obfusc_scan",
            "【控制流混淆扫描】按函数批量判定是否被 OLLVM 控制流平坦化/虚假控制流处理：看 CFG 形态（分发器块 = 入度≥阈值且本体极薄、排除入口块），算平坦化因子与薄块比例，给出 clean / suspected / flattened 三档判定与全量统计。不需要先猜哪几个函数可疑——按符号表里最大的 N 个函数扫。判定用结构证据而非符号名启发式；编译器生成的公共跳转目标可能像分发器，所以证据不足时上限就是 suspected。要真正还原函数体用 taffy_so_deflatten。",
            "Batch-classify functions as OLLVM control-flow-flattened / bogus-control-flow processed by CFG shape: dispatcher blocks (fan-in above threshold with a very thin body, entry block excluded), flattening factor and thin-block ratio, yielding clean / suspected / flattened verdicts plus aggregate stats. No need to guess which functions are suspicious — the largest N functions by symbol size are scanned. Verdicts rest on structural evidence rather than symbol-name heuristics; compiler-generated shared jump targets can resemble a dispatcher, so 'suspected' is the ceiling short of unambiguous evidence. Use taffy_so_deflatten to actually recover a function body.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区"
                "filePath" str "path 的别名"
                "limit" int "最多扫描多少个函数（按体积从大到小，默认 40，上限 200）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val engine = EngineProvider.get(ctx.context)
            val ws = workspaceOf(engine, args)
            ws.error?.let { return it }
            return engine.obfuscScan(ws.workspaceId, "", args.intValue("limit", 40))
        }
    }

    val antiDebugScan: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_anti_debug",
            "【SO 反调试点定位】在 ELF 结构层列出这个 .so 里的反调试/反注入/环境检测点：按导入符号（ptrace / getppid / inotify / frida 相关）、字符串常量（/proc/self/status、TracerPid、gum-js-loop 等）、以及宿主导出函数被 hook 的跳板特征三条证据分类，每条都标出「证据类型 + 证据内容 + 分类」，便于在动调前逐点绕过。与字节层 taffy_anti_debug_check 的区别：那个给的是「命中哪些特征」，这个给的是「命中的是哪条调用路径/哪个字符串/哪个跳板」，可直接定位到地址。",
            "List anti-debug / anti-injection / environment-check sites inside a .so at the ELF structural level, classified by three evidence types — imported symbols (ptrace / getppid / inotify / frida-related), string constants (/proc/self/status, TracerPid, gum-js-loop...), and hook trampolines patched into host exports — each row carrying evidence class, evidence content and category, so each check can be bypassed point by point before dynamic analysis. Differs from the byte-level taffy_anti_debug_check: that reports which signatures hit, this reports which call path / string / trampoline hit and where.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区"
                "filePath" str "path 的别名"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val engine = EngineProvider.get(ctx.context)
            val ws = workspaceOf(engine, args)
            ws.error?.let { return it }
            return engine.antiDebugScan(ws.workspaceId, "")
        }
    }

    val ALL: List<ToolHandler> = listOf(packerFingerprint, obfuscScan, antiDebugScan)
}
