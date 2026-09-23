package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 塔菲逆核: SO 伪 C 反编译专用工具。
 *
 * 之前拿伪 C 只能走泛入口 `taffy_rizin_api`(action=decompile)，拿不到函数边界/越界诊断等信息。
 * 本工具包装 `engine.rzDecompile(ws, "", locator, strict)`，把引擎返回的全部有价值诊断字段透出：
 *  - pseudocode / pseudocodeLineCount
 *  - functionBounds（startAddr/endAddr/size/rawSize/nextBoundary/resizedToBoundary/anchorCount/seededNeighbors/source）
 *  - pseudocodeCoverage（声明区间 vs 内容区间、越界地址）
 *  - boundaryCrossing / boundaryCrossingReasons / boundaryWarning
 *  - typeInference + typeConfidenceNote
 *  - pseudocodePolicy
 * 另外支持「只传 path 免 taffy_so_open」：与 analyze_elf / read_disasm / read_hexdump 一致，
 * 内部自动 open(path, temporary=true) 取 workspaceId，并在返回体里带上 autoOpenedWorkspaceId。
 */
object SoDecompileTool {

    val decompile: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_decompile",
            "【SO 伪 C 反编译】把指定函数反编译为伪 C（rizin-ghidra；原生后端缺失时自动降级为纯 Java AArch64 反汇编兜底）。可只传 path 自动打开临时工作区，无需先 taffy_so_open。返回 pseudocode 及全部诊断字段：functionBounds(函数边界/是否被扩展到下一个符号边界)、boundaryCrossing/boundaryCrossingReasons/boundaryWarning(伪代码是否越界引用了相邻函数)、pseudocodeCoverage、typeInference(返回值类型推断)、pseudocodePolicy、pseudocodeLineCount。失败时透传引擎错误码并给出替代方案(taffy_so_pseudoc_batch 批量 / taffy_rizin_api action=decompile 手动)。",
            "Decompile a function into pseudo-C (rizin-ghidra; falls back to pure-Java AArch64 disassembly when the native backend is unavailable). Pass only path to auto-open a temporary workspace — no prior taffy_so_open needed. Returns pseudocode plus diagnostics: functionBounds, boundaryCrossing(+reasons/warning), pseudocodeCoverage, typeInference, pseudocodePolicy, pseudocodeLineCount. On failure the engine error code is propagated together with alternatives (taffy_so_pseudoc_batch, taffy_rizin_api action=decompile).",
            "decompile",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区（免 taffy_so_open）"
                "filePath" str "path 的别名"
                "locator" str "函数定位符：函数名/符号/0x 十六进制线性地址；留空时由引擎回退到 ELF 入口点"
                "strict" bool "true（默认）：Ghidra 不可用时返回错误；false：尽量降级返回可用结果"
                "engine".oneOf("反编译引擎: auto(自动降级) | ghidra(rizin-ghidra pdg) | native(rizin 内置 pdc) | java(Exbin r2dec 纯 Java 引擎，缺失时降级启发式) | simple(Exbin SimplePseudoC 结构化引擎) | exbin(Exbin 自研 microcode+SSA 反编译器)", "auto", "ghidra", "native", "java", "simple", "exbin")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val engine = EngineProvider.get(ctx.context)
            val explicitWs = args.str("workspaceId").trim()
            val workspaceId: String
            val autoOpened: Boolean
            if (explicitWs.isNotBlank()) {
                workspaceId = explicitWs
                autoOpened = false
            } else {
                val p = args.str("path").ifBlank { args.str("filePath") }.trim()
                if (p.isBlank()) {
                    return err(
                        "INVALID_ARGUMENT",
                        "缺少 workspaceId 或 path（可只传 path 自动打开临时工作区）",
                        "workspaceId",
                        "",
                    )
                }
                val opened = engine.open(p, true)
                if (!opened.optBoolean("ok", false)) return opened
                val id = opened.optString("workspaceId")
                if (id.isBlank()) return opened
                workspaceId = id
                autoOpened = true
            }

            val locator = args.str("locator").trim()
            val strict = args.bool("strict", true)
            val wantEngine = args.str("engine", "auto").ifBlank { "auto" }

            // ── 引擎分发：ghidra(pdg) / native(pdc) / java(纯 Kotlin 启发式) / auto 依次降级 ──
            fun runNativePdc(): JSONObject? {
                val r = engine.rzCommand(workspaceId, "", "s $locator; pdc")
                val txt = r.optString("stdout").ifBlank { r.optString("text") }.trim()
                if (txt.isBlank() || txt.startsWith("ERROR")) return null
                return JSONObject().put("ok", true).put("pseudocode", txt).put("engine", "native-pdc")
                    .put("engineNote", "rizin 内置 pdc（native 管线，非 Ghidra SLEIGH）")
            }

            fun runJavaHeuristic(): JSONObject? {
                // 1) 优先：移植自 Exbin 的 r2dec 纯 Java 引擎（rizin agfj 提供 CFG + 指令）
                runCatching {
                    val ag = engine.rzCommand(workspaceId, "", "s $locator; agfj")
                    val agTxt = ag.optString("stdout").ifBlank { ag.optString("text") }.trim()
                    if (agTxt.startsWith("[")) {
                        val code = com.soreverse.mcp.engine.R2DecEngine.decompile(agTxt, "")
                        if (!code.isNullOrBlank()) {
                            return JSONObject().put("ok", true).put("pseudocode", code)
                                .put("engine", "java-r2dec")
                                .put("engineNote", "r2dec 纯 Java 移植（Exbin 引擎；CFG 由 rizin agfj 注入）")
                        }
                    }
                }
                // 2) 兜底：纯 Kotlin 启发式引擎
                val rj = engine.rzCommand(workspaceId, "", "s $locator; pdfj")
                val txt = rj.optString("stdout").ifBlank { rj.optString("text") }.trim()
                if (txt.isBlank() || !txt.startsWith("[")) return null
                val arr = runCatching { org.json.JSONArray(txt) }.getOrNull() ?: return null
                val insns = com.soreverse.mcp.engine.HeuristicDecompiler.parse(arr)
                if (insns.isEmpty()) return null
                val fnName = "sub_" + java.lang.Long.toHexString(insns.first().addr)
                val code = com.soreverse.mcp.engine.HeuristicDecompiler.decompile(insns, fnName, true)
                if (code.isBlank()) return null
                return JSONObject().put("ok", true).put("pseudocode", code).put("engine", "java-heuristic")
                    .put("instructionCount", insns.size)
                    .put("engineNote", "纯 Kotlin 启发式引擎（对标 r2dec 纯 Java 移植思路）；非编译器级反编译")
            }

            fun runExbinSimple(): JSONObject? {
                return runCatching {
                    val ag = engine.rzCommand(workspaceId, "", "s $locator; agfj")
                    val agTxt = ag.optString("stdout").ifBlank { ag.optString("text") }.trim()
                    if (!agTxt.startsWith("[")) return@runCatching null
                    val code = com.soreverse.mcp.engine.R2DecEngine.decompileSimple(agTxt, "")
                    if (code.isNullOrBlank()) null
                    else JSONObject().put("ok", true).put("pseudocode", code)
                        .put("engine", "java-simple")
                        .put("engineNote", "SimplePseudoC 结构化伪 C（Exbin v2.1.4 引擎；CFG 由 rizin agfj 注入）")
                }.getOrNull()
            }

            fun runExbinDecomp(): JSONObject? {
                return runCatching {
                    val code = engine.exbinDecompile(workspaceId, "", locator)
                    if (code.isNullOrBlank()) null
                    else JSONObject().put("ok", true).put("pseudocode", code)
                        .put("engine", "exbin-decomp")
                        .put(
                            "engineNote",
                            "Exbin 自研 microcode+SSA 反编译器（libexbin_decomp.so；" +
                                "MicrocodeEmitter→SSA→Optimize→CFGStructure→CTree→CPrinter）",
                        )
                }.getOrNull()
            }

            if (wantEngine == "native") {
                val r = runNativePdc()
                if (r == null) return err("DECOMPILER_UNAVAILABLE", "native(pdc) 引擎无输出（该 rizin 构建可能未启用 pdc）", "engine", "native")
                return ok(r.put("workspaceId", workspaceId).put("locator", locator))
            }
            if (wantEngine == "java") {
                val r = runJavaHeuristic()
                if (r == null) return err("DECOMPILER_UNAVAILABLE", "java 引擎无输出（地址无法反汇编或指令为空）", "engine", "java")
                return ok(r.put("workspaceId", workspaceId).put("locator", locator))
            }
            if (wantEngine == "simple") {
                val r = runExbinSimple()
                if (r == null) return err("DECOMPILER_UNAVAILABLE", "SimplePseudoC 引擎无输出（地址无法反汇编或指令为空）", "engine", "simple")
                return ok(r.put("workspaceId", workspaceId).put("locator", locator))
            }
            if (wantEngine == "exbin") {
                val r = runExbinDecomp()
                if (r == null) {
                    return err(
                        "DECOMPILER_UNAVAILABLE",
                        "Exbin 反编译器无输出（该 ABI 未打包 libexbin_decomp.so / 地址无法反汇编 / 指令为空）",
                        "engine", "exbin",
                    )
                }
                return ok(r.put("workspaceId", workspaceId).put("locator", locator))
            }

            var raw = engine.rzDecompile(workspaceId, "", locator, strict)
            var usedEngine = "ghidra-pdg"
            if (wantEngine == "auto" && !raw.optBoolean("ok", false)) {
                val alt = runNativePdc() ?: runJavaHeuristic()
                if (alt != null) { raw = alt; usedEngine = alt.optString("engine") }
            }

            if (!raw.optBoolean("ok", false)) {
                val e = raw.optJSONObject("error")
                val code = e?.optString("code").orEmpty().ifBlank { "DECOMPILE_FAILED" }
                val message = e?.optString("message").orEmpty().ifBlank { "反编译失败" }
                return err(
                    code,
                    message,
                    "locator",
                    locator.ifBlank { null },
                    "alternatives" to "taffy_so_pseudoc_batch(批量伪 C, 适合多函数) / taffy_rizin_api(action=decompile, 手动) / taffy_read_disasm(先看汇编, pseudocode 字段)",
                    "suggestedWorkspaceId" to if (autoOpened) workspaceId else null,
                    "autoOpenedWorkspaceId" to if (autoOpened) workspaceId else null,
                    "engineError" to (e ?: JSONObject.NULL),
                )
            }

            val pseudo = raw.optString("pseudocode")
            val lineCount = if (pseudo.isBlank()) 0 else pseudo.trimEnd().lineSequence().count()
            val out = JSONObject(raw.toString())
            out.put("tool", "taffy_so_decompile")
            if (!out.has("engine")) out.put("engine", usedEngine)
            out.put("workspaceId", workspaceId)
            out.put("pseudocodeLineCount", lineCount)
            for (k in DIAGNOSTIC_KEYS) {
                if (!out.has(k) || out.isNull(k)) out.put(k, JSONObject.NULL)
            }
            if (autoOpened) {
                out.put("autoOpenedWorkspaceId", workspaceId)
                out.put(
                    "autoOpenHint",
                    "已按 path 自动打开临时工作区(temporary)；如需复用请传 workspaceId=$workspaceId",
                )
            }
            if (pseudo.isBlank()) {
                out.put(
                    "hint",
                    "引擎未返回伪代码内容：可尝试 strict=false 或显式指定 locator；批量场景用 taffy_so_pseudoc_batch，手工场景用 taffy_rizin_api(action=decompile)。",
                )
            }
            return ok(out)
        }
    }

    private val DIAGNOSTIC_KEYS = listOf(
        "pseudocode",
        "functionBounds",
        "pseudocodeCoverage",
        "boundaryCrossing",
        "boundaryCrossingReasons",
        "boundaryWarning",
        "typeInference",
        "pseudocodePolicy",
    )
}
