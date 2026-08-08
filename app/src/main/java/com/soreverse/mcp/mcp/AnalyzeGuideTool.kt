package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 深度分析取证攻略工具。
 *
 * 给外部 AI 客户端（玄星等）提供"对某个 SO 做深度分析"的标准操作攻略与可选证据包。
 * 本工具【不调用任何 AI 中转站】——推理完全由外部 AI 客户端自己完成。
 *
 * 两种返回模式:
 *  - 默认 (B): 只返回针对该 SO 的取证攻略（guide），引导外部 AI 按步骤调用本机 MCP 取证工具
 *    （taffy_so_open → 分析 → 交叉引用 → 搜索 → 模拟执行 → report）收集证据，然后由外部 AI 自行综合报告。
 *  - includeEvidence=true (A): 额外基于引擎本地数据打包该 SO 的完整证据快照（ELF 结构、函数、
 *    字符串、密码学、安全特征等），一次性返回给外部 AI 直接用于分析，无需逐条取证。
 */
object AnalyzeGuideTool {

    val guide: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_analyze_guide",
            "【深度分析取证攻略】为指定 SO 生成深度逆向分析的取证攻略（guide），引导 AI 按步骤调用 MCP 取证工具（taffy_so_open → taffy_analyze_cfg → taffy_analyze_xrefs → taffy_search_strings → taffy_read_disasm → taffy_analyze_crypto → taffy_emulate_call → taffy_analysis_report）收集证据，再由 AI 自行综合为报告。本工具不调用任何 AI 中转站，推理由调用方（外部 AI）自己完成。可传 path（SO 路径）或 workspaceId（已打开工作区）。设置 includeEvidence=true 时，额外打包返回该 SO 的完整证据快照供直接分析。",
            "Generate a deep-reverse-engineering forensics guide for a given SO, guiding the AI to collect evidence via MCP tools (taffy_so_open → taffy_analyze_cfg → taffy_analyze_xrefs → taffy_search_strings → taffy_read_disasm → taffy_analyze_crypto → taffy_emulate_call → taffy_analysis_report) and then synthesize a report itself. This tool calls no AI relay — reasoning is done by the caller (external AI). Pass path (SO path) or workspaceId (an opened workspace). Set includeEvidence=true to also return the SO's full evidence snapshot gathered locally.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "SO 文件绝对路径或 content:// URI（与 workspaceId 二选一）"
                "filePath" str "path 的别名"
                "workspaceId" str "已打开的工作区 ID（与 path 二选一，优先使用已打开的工作区）"
                "includeEvidence" bool "是否额外返回本地证据快照（默认 false）"
                "maxEvidenceChars" int "证据快照的最大字符数（可选，默认 20000）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path").ifBlank { args.str("filePath") }
            val workspaceIdArg = args.str("workspaceId")
            val engine = ctx.engine

            // 解析工作区
            val workspaceId: String
            val soName: String
            if (workspaceIdArg.isNotBlank()) {
                // 使用已打开的工作区
                val ws = engine.listWorkspaces().optJSONArray("items")?.let { arr ->
                    (0 until arr.length()).map { arr.optJSONObject(it) }
                        .firstOrNull { it.optString("workspaceId") == workspaceIdArg }
                }
                if (ws == null) {
                    return err("WORKSPACE_NOT_FOUND", "工作区不存在: $workspaceIdArg", "workspaceId", workspaceIdArg)
                }
                workspaceId = workspaceIdArg
                soName = ws.optString("soFileName", "lib.so")
            } else if (path.isNotBlank()) {
                // 打开 SO 为持久工作区（复用 taffy_so_open 持久化逻辑）
                val opened = engine.open(path, temporary = false)
                if (!opened.optBoolean("ok", true)) {
                    return err("OPEN_FAILED", opened.optJSONObject("error")?.optString("message") ?: "SO 打开失败", "path", path)
                }
                workspaceId = opened.optString("workspaceId")
                soName = opened.optString("soFileName", "lib.so")
            } else {
                return err("INVALID_ARGUMENT", "请提供 path（SO 路径）或 workspaceId（已打开工作区）之一", "path", "", "workspaceId" to "")
            }

            val includeEvidence = args.optBoolean("includeEvidence", false)
            val maxEvidenceChars = args.optInt("maxEvidenceChars", 20000).coerceIn(1, 200000)

            // 攻略主体（针对该 SO 生成）
            val guide = buildGuide(engine, workspaceId, soName, ctx, includeEvidence)

            val result = JSONObject()
                .put("tool", "taffy_analyze_guide")
                .put("workspaceId", workspaceId)
                .put("soName", soName)
                .put("guide", guide)

            // A: 可选返回本地证据快照（不调 AI，纯引擎取数）
            if (includeEvidence) {
                val report = engine.analysisReport(workspaceId, "", writeToFile = false).optJSONObject("report")
                if (report != null) {
                    val text = report.toString()
                    val finalText = if (text.length > maxEvidenceChars) {
                        text.take(maxEvidenceChars) + "\n…[证据已截断，可逐项调用对应工具获取完整数据]"
                    } else text
                    result.put("evidence", finalText)
                    result.put("evidenceReturned", true)
                } else {
                    result.put("evidenceReturned", false)
                    result.put("evidenceHint", "证据包生成失败，请改用引导中的取证工具逐项收集")
                }
            }

            return ok(result)
        }

        private fun buildGuide(
            engine: com.soreverse.mcp.engine.NativeSoEngine,
            workspaceId: String,
            soName: String,
            ctx: ToolContext,
            includeEvidence: Boolean,
        ): String {
            val zh = ctx.settings.language == "zh" || (ctx.settings.language == "system" && java.util.Locale.getDefault().language == "zh")

            // 尽力收集当前已知特征，使攻略更贴合目标（数据不足时用通用步骤兜底）
            var arch = "unknown"
            var statsHint = ""
            try {
                engine.analyze(workspaceId, "")
                val listing = engine.listWorkspaces().optJSONArray("items")?.let { arr ->
                    (0 until arr.length()).map { arr.optJSONObject(it) }.firstOrNull { it.optString("workspaceId") == workspaceId }
                }
                arch = listing?.optString("architecture", "unknown") ?: "unknown"
                val r = engine.readStats(workspaceId, "").optJSONObject("dynamic") ?: JSONObject()
                statsHint = " ELF结构/节区/符号可经 taffy_analyze_elf 获取"
            } catch (e: Throwable) { com.soreverse.mcp.core.AppLog.w("silent-catch: ${e.message}") }

            val wsPrefix = workspaceId
            val step = { n: Int, tool: String, arg: String, why: String ->
                "$n. 调 [$tool] $arg — $why"
            }

            return if (zh) {
                buildString {
                    append("对「$soName」的深度逆向分析取证攻略（架构: $arch）。请逐段调用以下 MCP 工具收集证据，再基于收集到的证据自行综合成最终分析报告。不要虚构数据，每个结论都要有对应的工具结果支撑。\n\n")
                    append("【第 0 步 · 确认目标】\n")
                    append("  目标已打开，workspaceId=$wsPrefix。后续每个非 taffy_so_open 工具都要带上 workspaceId=$wsPrefix。\n\n")
                    append("【第 1 步 · 静态结构】\n")
                    append(step(1, "taffy_analyze_elf", "workspaceId=$wsPrefix view=full", "获取 ELF 节区、程序头、符号、重定位、动态段，了解文件的整体布局和导入导出")).append("\n")
                    append(step(2, "taffy_read_stats", "workspaceId=$wsPrefix", "确认统计概览：节区/符号/字符串数量，判断是否 strip")).append("\n\n")
                    append("【第 2 步 · 函数与控制流】\n")
                    append(step(3, "taffy_analyze_functions", "workspaceId=$wsPrefix", "列出 Rizin 发现的所有函数及地址/大小，定位关键函数")).append("\n")
                    append(step(4, "taffy_analyze_cfg", "workspaceId=$wsPrefix locator=<函数>", "对关键函数生成控制流图，理解执行逻辑与分支")).append("\n\n")
                    append("【第 3 步 · 交叉引用与调用关系】\n")
                    append(step(5, "taffy_analyze_xrefs", "workspaceId=$wsPrefix locator=<符号/函数> direction=to", "查关键函数的调用来源，追本溯源")).append("\n\n")
                    append("【第 4 步 · 字符串与可信特征】\n")
                    append(step(6, "taffy_search_strings", "workspaceId=$wsPrefix prefix=<关键词>", "搜 URL/域名/密钥/错误提示等，识别加密或网络行为")).append("\n")
                    append(step(7, "taffy_search_bytes", "workspaceId=$wsPrefix pattern=<十六进制>", "按机器码特征定位函数或用例")).append("\n\n")
                    append("【第 5 步 · 反汇编与密码学】\n")
                    append(step(8, "taffy_read_disasm", "workspaceId=$wsPrefix locator=<函数>", "反汇编关键函数，必要时开启 Ghidra 伪代码")).append("\n")
                    append(step(9, "taffy_analyze_crypto", "workspaceId=$wsPrefix", "扫描 AES/RSA/ECC 常量与高熵区，识别加密逻辑")).append("\n\n")
                    append("【第 6 步 · 动态验证（如需）】\n")
                    append(step(10, "taffy_emulate_call / unidbg_*", "workspaceId=$wsPrefix symbolName=<导出函数>", "模拟执行 JNI_OnLoad / Java_* / 关键导出，验证行为")).append("\n\n")
                    append("【第 7 步 · 综合报告】\n")
                    append(step(11, "taffy_analysis_report", "workspaceId=$wsPrefix", "生成引擎侧的综合报告（与本攻略互补）")).append(" 然后你基于以上所有证据，输出最终深度分析报告。\n")
                    if (includeEvidence) append("\n（本次已通过 includeEvidence=true 返回证据快照，可直接作为分析基础，也可按上述步骤补充取证。）\n")
                }
            } else {
                buildString {
                    append("Deep-reverse-engineering forensics guide for \"$soName\" (arch: $arch). Collect evidence step by step via the MCP tools below, then synthesize your own final report. Do not fabricate data — every claim must be backed by a tool result.\n\n")
                    append("[Step 0 · Confirm target]\n")
                    append("  Target opened, workspaceId=$wsPrefix. Pass workspaceId=$wsPrefix on every tool except taffy_so_open.\n\n")
                    append("[Step 1 · Static structure]\n")
                    append(step(1, "taffy_analyze_elf", "workspaceId=$wsPrefix view=full", "sections, program headers, symbols, relocations, dynamic entries; understand layout, imports/exports")).append("\n")
                    append(step(2, "taffy_read_stats", "workspaceId=$wsPrefix", "overview counts; detect stripped")).append("\n\n")
                    append("[Step 2 · Functions & control flow]\n")
                    append(step(3, "taffy_analyze_functions", "workspaceId=$wsPrefix", "list Rizin functions with addresses; locate key logic")).append("\n")
                    append(step(4, "taffy_analyze_cfg", "workspaceId=$wsPrefix locator=<fn>", "control-flow graph of key functions")).append("\n\n")
                    append("[Step 3 · Cross-references]\n")
                    append(step(5, "taffy_analyze_xrefs", "workspaceId=$wsPrefix locator=<sym> direction=to", "find callers of key functions; trace roots")).append("\n\n")
                    append("[Step 4 · Strings & search]\n")
                    append(step(6, "taffy_search_strings", "workspaceId=$wsPrefix prefix=<keyword>", "URLs, domains, keys, messages; detect network/crypto")).append("\n")
                    append(step(7, "taffy_search_bytes", "workspaceId=$wsPrefix pattern=<hex>", "locate code/uses by byte signature")).append("\n\n")
                    append("[Step 5 · Disasm & crypto]\n")
                    append(step(8, "taffy_read_disasm", "workspaceId=$wsPrefix locator=<fn>", "disassemble key functions; use Ghidra pseudocode if needed")).append("\n")
                    append(step(9, "taffy_analyze_crypto", "workspaceId=$wsPrefix", "scan AES/RSA/ECC constants & high-entropy regions")).append("\n\n")
                    append("[Step 6 · Dynamic (optional)]\n")
                    append(step(10, "taffy_emulate_call / unidbg_*", "workspaceId=$wsPrefix symbolName=<export>", "emulate JNI_OnLoad / Java_* / key exports to verify")).append("\n\n")
                    append("[Step 7 · Final report]\n")
                    append(step(11, "taffy_analysis_report", "workspaceId=$wsPrefix", "engine-side full report, complementary to this guide")).append(" Then produce your final deep-analysis report from all evidence above.\n")
                    if (includeEvidence) append("\n(Evidence snapshot was returned via includeEvidence=true; use it as a base and enrich with the steps above.)\n")
                }
            }
        }
    }
}
