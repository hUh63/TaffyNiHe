package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.DeFlatBlock
import com.soreverse.mcp.engine.DeFlatPatch
import com.soreverse.mcp.engine.OllvmDeFlat
import com.soreverse.mcp.parseCfgGraph
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: OLLVM 控制流平坦化「去平坦化」。
 *
 * 控制流平坦化把每个真实块 b 的出口改成「写状态常量 S，跳回分发器 D」，由 D 按 S 分派。
 * 本工具把这些出口跳转**等长重定向**到真实后继 T（T 由 D 的 cmp 链解析得到），从而消掉分发器。
 *
 * 安全边界（宁可不做也不做错）：
 *  - 只处理「出口指向分发器 + 状态常量可识别 + 分发器能解析出唯一目标 + 可等长编码」的块；
 *  - 其余一律如实标注原因（未识别状态 / 跳表分派 / 越界 / 不支持编码），**绝不改写**；
 *  - dryRun 默认 true，先出计划；dryRun=false 才经编辑内核 editHexVa 写入（带快照/undo/redo）。
 *
 * 写入后，用返回的 editSessionId 再次查询 CFG / 伪 C 即可看到去平坦化结果
 * （引擎按编辑会话字节重新分析）。
 */
object SoDeFlattenTool {

    private fun machineToArch(machine: String): String = when (machine.trim().uppercase()) {
        "EM_AARCH64" -> "arm64"
        "EM_ARM" -> "arm32"
        "EM_X86_64" -> "x86_64"
        "EM_386" -> "x86"
        else -> ""
    }

    private fun parseHexOr(text: String, fallback: Long = -1L): Long {
        if (text.isBlank()) return fallback
        val t = text.trim()
        return if (t.startsWith("0x", true)) t.substring(2).toLongOrNull(16) ?: fallback
        else t.toLongOrNull() ?: fallback
    }

    private fun hex(va: Long): String = "0x" + java.lang.Long.toHexString(va)

    /** 回溯取「写下一状态」的常量：终止跳转之前最近的 mov 族指令的立即数。 */
    private val stateMnemonics = setOf(
        "mov", "movz", "movk", "movn", "orr", "add", "sub", "eor", "and", "mul", "lsl", "lsr",
        "leal", "push",
    )

    private fun stateOf(insns: List<com.soreverse.mcp.engine.DeFlatInsn>): Long? {
        // 末条为终止跳转，从倒数第二条开始往前找 mov 族
        for (i in insns.size - 2 downTo 0) {
            val d = insns[i].disasm
            if (d.isBlank()) continue
            val mn = d.substringBefore(' ').lowercase()
            if (mn in stateMnemonics) {
                OllvmDeFlat.lastImmediate(d)?.let { return it }
            }
            if (i < insns.size - 6) break
        }
        return null
    }

    private val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_deflatten",
            "【去平坦化(OLLVM)】检测控制流平坦化并生成/应用「出口跳转重定向」补丁：把每个真实块末尾跳回分发器的跳转，等长改写为直接跳到真实后继，从而消掉分发器。仅处理「出口指向分发器 + 状态可识别 + 分发器解析出唯一目标 + 可等长编码」的块，其余如实标注原因不写。dryRun 默认 true（只出计划）；dryRun=false 经编辑内核 editHexVa 等长写入（带快照/undo），返回 editSessionId 供后续重新分析。分发器支持 cmp 链与跳表(switch)两类；跳表解出的映射默认只读展示（需 allowTable=true 才写入）。",
            "De-flatten OLLVM control-flow flattening: retarget each block's tail jump (to the dispatcher) directly to its real successor (equal-length rewrite). Only blocks with a resolvable single successor and encodable branch are patched; everything else is reported, never written. Supports cmp-chain and jump-table dispatchers; jump-table-derived mappings are report-only unless allowTable=true. dryRun defaults to true (plan only); dryRun=false applies via the edit kernel (snapshot/undo).",
            "edit", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 id（必填）"
                "editSessionId" str "编辑会话 id（写入时留空自动新建；给出则写入该会话）"
                "locator" str "目标函数：符号名或 0xVA（必填）"
                "arch" str "架构 arm64/arm32/x86/x86_64（留空按 ELF e_machine 推断）"
                "maxInsns" str "每个函数最多反汇编的指令数（默认 3000）"
                "allowTable" str "true=允许写入跳表(switch)解出的重定向（默认 false，仅展示）"
                "dryRun" str "true=只返回计划（默认 true）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "workspaceId 必填")
            val locator = args.str("locator").trim()
            if (locator.isBlank()) return err("INVALID_ARGUMENT", "locator 必填")

            val eng = EngineProvider.get(ctx.context)
            var sid = args.str("editSessionId")
            val dryRun = args.str("dryRun", "true").toBoolean()
            val maxInsns = args.str("maxInsns").toIntOrNull()?.coerceIn(64, 20000) ?: 3000
            val arch = args.str("arch").ifBlank {
                machineToArch(runCatching { eng.readElf(ws, sid) }.getOrNull()?.optString("machine").orEmpty())
            }
            if (arch.isBlank()) return err("UNKNOWN_ARCH", "无法确定架构，请显式传 arch")

            // 1) 取 CFG（基本块 + 边）
            val cfg = runCatching { eng.rzCfg(ws, sid, locator) }.getOrNull()
                ?: return err("CFG_UNAVAILABLE", "引擎无响应（rzCfg 失败）")
            cfg.optJSONObject("error")?.let { return it }
            val g = parseCfgGraph(cfg.toString())
            val n = g.blocks.size
            if (n < 3) return ok(JSONObject().put("flattened", false).put("reason", "基本块过少（$n）"))

            // 2) 分发器 = 入度最大的块
            val indeg = IntArray(n)
            g.edges.forEach { e -> if (e.to in 0 until n) indeg[e.to]++ }
            var disp = -1
            var best = 0
            for (i in 0 until n) if (indeg[i] > best) { best = indeg[i]; disp = i }
            if (disp < 0 || best < 3) {
                return ok(JSONObject().put("flattened", false).put("maxInDegree", best).put("blockCount", n)
                    .put("reason", "未找到明显的分发器（最大入度 $best）"))
            }
            val dispVa = g.blocks[disp].addrValue

            // 3) 反汇编整个函数，按块区间归位
            val starts = g.blocks.map { it.addrValue }.filter { it >= 0 }
            if (starts.isEmpty()) return err("CFG_UNAVAILABLE", "基本块地址无法解析")
            val fnStart = starts.minOrNull() ?: return err("CFG_UNAVAILABLE", "基本块地址无法解析")
            val cmd = "pdj $maxInsns @ " + hex(fnStart)
            val r = runCatching { eng.rzCommand(ws, sid, cmd, false) }.getOrNull()
                ?: return err("DISASM_FAILED", "反汇编命令无响应")
            r.optJSONObject("error")?.let { return it }
            val insns = OllvmDeFlat.parseInsns(r.optString("text").ifBlank { r.optString("stdout") })
            if (insns.isEmpty()) return err("DISASM_FAILED", "反汇编输出为空或无法解析（$cmd）")

            // 每个块 [start,end) 的指令
            val bounds = g.blocks.map { b ->
                val end = parseHexOr(b.endText, if (b.addrValue >= 0) b.addrValue else -1L)
                Triple(b.addrValue, end, ArrayList<com.soreverse.mcp.engine.DeFlatInsn>())
            }
            for (ins in insns) {
                for (i in 0 until n) {
                    val (s, e, list) = bounds[i]
                    if (s >= 0 && ins.offset >= s && (e <= 0 || ins.offset < e)) { list.add(ins); break }
                }
            }

            // 4) 构建块模型（终止跳转 + 下一状态）并解析分发器 cmp 链
            val dBlocks = ArrayList<DeFlatBlock>(n)
            for (i in 0 until n) {
                val (s, e, list) = bounds[i]
                if (list.isEmpty()) { dBlocks.add(DeFlatBlock(i, s, e, null, null)); continue }
                val last = list.last()
                val term = OllvmDeFlat.decodeTerminator(last.bytes, last.offset, arch)
                val st = stateOf(list)
                dBlocks.add(DeFlatBlock(i, s, e, term, st))
            }
            val dispInsns = bounds.getOrNull(disp)?.third ?: emptyList()
            var stateToTarget = OllvmDeFlat.decodeCmpChainDispatcher(dispInsns)
            var dispatchMode = if (stateToTarget.isNotEmpty()) "cmp-chain" else "none"
            var tableDerived = false

            // 4b) cmp 链解析不到 → 尝试跳表（switch）：解表 + 用分发器出边集合校验
            if (stateToTarget.isEmpty()) {
                val outTargets = g.edges.filter { it.from == disp && it.to != disp }
                    .mapNotNull { g.blocks.getOrNull(it.to)?.addrValue?.takeIf { v -> v >= 0 } }
                    .toSet()
                val spec = OllvmDeFlat.detectTableSpec(dispInsns)
                if (spec != null && outTargets.size >= 2) {
                    val count = outTargets.size + 1
                    val tableBytes = readMem(eng, ws, sid, spec.tableVa, count * spec.entrySize)
                    if (tableBytes != null && tableBytes.isNotEmpty()) {
                        val decoded = OllvmDeFlat.decodeJumpTable(tableBytes, spec, count)
                        val valid = OllvmDeFlat.validateTableTargets(decoded, outTargets)
                        if (valid.isNotEmpty()) {
                            stateToTarget = valid
                            dispatchMode = "jump-table"
                            tableDerived = true
                        }
                    }
                }
            }

            // 5) 规划
            val allowTable = args.str("allowTable", "false").toBoolean()
            val forceUnsafeTable = tableDerived && !allowTable
            val patches = OllvmDeFlat.plan(dBlocks, dispVa, stateToTarget, arch)
            val safeP = if (forceUnsafeTable) emptyList() else patches.filter { it.safe }
            val unsafeP = if (forceUnsafeTable) patches else patches.filter { !it.safe }

            // 5b) 去平坦化后的 CFG 形状（供对比图/报告）
            val addrToIndex = HashMap<Long, Int>()
            for (i in 0 until n) {
                val v = g.blocks[i].addrValue
                if (v >= 0) addrToIndex[v] = i
            }
            val retarget = HashMap<Int, Int>()
            for (p in safeP) addrToIndex[p.newTarget]?.let { t -> retarget[p.blockIndex] = t }
            val dg = OllvmDeFlat.deFlattenGraph(n, g.edges.map { it.from to it.to }, disp, retarget)
            val compare = JSONObject()
                .put("beforeBlocks", n)
                .put("afterBlocks", dg.keptBlocks)
                .put("beforeEdges", g.edges.size)
                .put("afterEdges", dg.edges.size)
                .put("removedBlock", dg.removed)
                .put("added", JSONArray(dg.added.map { JSONArray().put(it.first).put(it.second) }))

            fun patchJson(p: DeFlatPatch, forceUnsafe: Boolean = false): JSONObject = JSONObject()
                .put("block", p.blockIndex)
                .put("insn", hex(p.insnAddr))
                .put("oldTarget", hex(p.oldTarget))
                .put("newTarget", hex(p.newTarget))
                .put("kind", p.kind.name)
                .put("safe", p.safe && !forceUnsafe)
                .put("reason", if (forceUnsafe && p.safe) p.reason + "（跳表解出，需 allowTable=true 才写入）" else p.reason)
                .put("oldHex", p.oldBytes.joinToString("") { "%02x".format(it) })
                .put("newHex", p.newBytes.joinToString("") { "%02x".format(it) })

            val planJson = JSONObject()
                .put("flattened", true)
                .put("arch", arch)
                .put("dispatcher", hex(dispVa))
                .put("dispatcherInDegree", best)
                .put("blockCount", n)
                .put("instructionCount", insns.size)
                .put("dispatcherStates", stateToTarget.size)
                .put("dispatchMode", dispatchMode)
                .put("tableDerived", tableDerived)
                .put("patchable", safeP.size)
                .put("unpatchable", unsafeP.size)
                .put("compare", compare)
                .put("patches", JSONArray(safeP.map { patchJson(it) }))
                .put("skipped", JSONArray(unsafeP.map { patchJson(it, forceUnsafeTable) }))

            if (dryRun || safeP.isEmpty()) return ok(planJson.put("dryRun", true))

            // 6) 写入：等长原位改写（VA -> 文件偏移）
            if (sid.isBlank()) {
                val eo = eng.editOpen(ws)
                sid = eo.optString("editSessionId")
                    .ifBlank { eo.optJSONObject("data")?.optString("editSessionId").orEmpty() }
            }
            if (sid.isBlank()) return err("EDIT_SESSION_OPEN_FAILED", "无法创建编辑会话")

            val applied = JSONArray()
            for (p in safeP) {
                val res = runCatching { eng.editHexVa(ws, sid, p.insnAddr, p.newBytes, false) }.getOrNull()
                    ?: return err("PATCH_FAILED", "写入失败 @ " + hex(p.insnAddr))
                res.optJSONObject("error")?.let { return it }
                applied.put(patchJson(p))
            }

            return ok(planJson
                .put("dryRun", false)
                .put("editSessionId", sid)
                .put("applied", applied.length())
                .put("appliedPatches", applied)
                .put("hint", "已写入编辑会话；用该 editSessionId 重新查询 CFG / 伪 C 即看去平坦化结果，确认后 build 落盘。"))
        }
    }

    /** 读内存/文件字节（rizin p8，失败退回 pxj 字节数组）。null 表示读不到。 */
    private fun readMem(
        eng: com.soreverse.mcp.engine.NativeSoEngine,
        ws: String,
        sid: String,
        va: Long,
        len: Int,
    ): ByteArray? {
        if (len <= 0) return null
        val r = runCatching { eng.rzCommand(ws, sid, "p8 $len @ " + hex(va), false) }.getOrNull() ?: return null
        if (r.optJSONObject("error") == null) {
            val hexs = r.optString("text").replace(Regex("[^0-9a-fA-F]"), "")
            if (hexs.length >= 2) {
                val cut = hexs.length - (hexs.length % 2)
                return runCatching {
                    hexs.substring(0, cut).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                }.getOrNull()
            }
        }
        val r2 = runCatching { eng.rzCommand(ws, sid, "pxj $len @ " + hex(va), false) }.getOrNull() ?: return null
        val arr = runCatching { org.json.JSONArray(r2.optString("text").trim()) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        return ByteArray(arr.length()) { arr.optInt(it, 0).toByte() }
    }

    val ALL = listOf(tool)
}
