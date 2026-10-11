package com.soreverse.mcp.engine

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.parseCfgGraph
import org.json.JSONArray
import org.json.JSONObject

/**
 * OLLVM 去平坦化的「取数 + 规划」引擎实现（无 UI / 无工具依赖）。
 *
 * 供 taffy_so_deflatten 工具（再叠加写入）与分析报告（批量扫描）共用：
 *  - deflatAnalyze：单个函数 → 分发器识别 / 状态解析（cmp 链或跳表）/ 重定向计划 / 前后对比
 *  - deflatScan：批量函数 → 汇总「哪些函数被平坦化、各能重定向多少处」
 *
 * 写入（editHexVa）不在这里：由工具层决定是否落盘，保持引擎只读职责清晰。
 */

private val DEFLAT_STATE_MNEMONICS = setOf(
    "mov", "movz", "movk", "movn", "orr", "add", "sub", "eor", "and", "mul", "lsl", "lsr", "leal",
)

private fun deflatMachineToArch(machine: String): String = when (machine.trim().uppercase()) {
    "EM_AARCH64" -> "arm64"
    "EM_ARM" -> "arm32"
    "EM_X86_64" -> "x86_64"
    "EM_386" -> "x86"
    else -> ""
}

private fun deflatParseHexOr(text: String, fallback: Long = -1L): Long {
    if (text.isBlank()) return fallback
    val t = text.trim()
    return if (t.startsWith("0x", true)) t.substring(2).toLongOrNull(16) ?: fallback else t.toLongOrNull() ?: fallback
}

/** 回溯取「写下一状态」的常量：终止跳转之前最近的 mov 族指令的立即数。 */
private fun deflatStateOf(insns: List<DeFlatInsn>): Long? {
    for (i in insns.size - 2 downTo 0) {
        val d = insns[i].disasm
        if (d.isBlank()) continue
        val mn = d.substringBefore(' ').lowercase()
        if (mn in DEFLAT_STATE_MNEMONICS) OllvmDeFlat.lastImmediate(d)?.let { return it }
        if (i < insns.size - 6) break
    }
    return null
}

/** 读内存/文件字节（rizin p8，失败退回 pxj 字节数组）。null 表示读不到。 */
private fun EngineRuntime.deflatReadMem(workspaceId: String, editSessionId: String, va: Long, len: Int): ByteArray? {
    if (len <= 0) return null
    val r = runCatching { rzCommand(workspaceId, editSessionId, "p8 $len @ " + hex(va), false) }.getOrNull() ?: return null
    if (r.optJSONObject("error") == null) {
        val hexs = r.optString("text").replace(Regex("[^0-9a-fA-F]"), "")
        if (hexs.length >= 2) {
            val cut = hexs.length - (hexs.length % 2)
            return runCatching { hexs.substring(0, cut).chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
        }
    }
    val r2 = runCatching { rzCommand(workspaceId, editSessionId, "pxj $len @ " + hex(va), false) }.getOrNull() ?: return null
    val arr = runCatching { JSONArray(r2.optString("text").trim()) }.getOrNull() ?: return null
    if (arr.length() == 0) return null
    return ByteArray(arr.length()) { arr.optInt(it, 0).toByte() }
}

private fun deflatPatchJson(p: DeFlatPatch, forceUnsafe: Boolean): JSONObject = JSONObject()
    .put("block", p.blockIndex)
    .put("insn", "0x" + java.lang.Long.toHexString(p.insnAddr))
    .put("oldTarget", "0x" + java.lang.Long.toHexString(p.oldTarget))
    .put("newTarget", "0x" + java.lang.Long.toHexString(p.newTarget))
    .put("kind", p.kind.name)
    .put("safe", p.safe && !forceUnsafe)
    .put("reason", if (forceUnsafe && p.safe) p.reason + "（跳表解出，需 allowTable=true 才写入）" else p.reason)
    .put("oldHex", p.oldBytes.joinToString("") { "%02x".format(it) })
    .put("newHex", p.newBytes.joinToString("") { "%02x".format(it) })

/**
 * 对单个函数做去平坦化分析。返回值（扁平 + ok=true）：
 *   flattened / dispatcher / dispatcherInDegree / dispatchMode(cmp-chain|jump-table|none) /
 *   blockCount / dispatcherStates / patchable / unpatchable /
 *   patches[](safe) / skipped[](原因) / compare{ before/after 块边数 } / afterCfg(去平坦化后的 CFG JSON)
 *
 * @param allowTable true 时跳表解出的重定向才计入可写集合（默认只读展示）。
 */
internal fun EngineRuntime.deflatAnalyze(
    workspaceId: String,
    editSessionId: String = "",
    locator: String,
    arch: String = "",
    maxInsns: Int = 3000,
    allowTable: Boolean = false,
): JSONObject = guarded {
    if (workspaceId.isBlank()) return@guarded err("INVALID_ARGUMENT", "workspaceId 必填")
    if (locator.isBlank()) return@guarded err("INVALID_ARGUMENT", "locator 必填")
    val a = arch.ifBlank {
        deflatMachineToArch(runCatching { readElf(workspaceId, editSessionId) }.getOrNull()?.optString("machine").orEmpty())
    }
    if (a.isBlank()) return@guarded err("UNKNOWN_ARCH", "无法确定架构，请显式传 arch")

    val cfg = runCatching { rzCfg(workspaceId, editSessionId, locator) }.getOrNull()
        ?: return@guarded err("CFG_UNAVAILABLE", "引擎无响应（rzCfg 失败）")
    cfg.optJSONObject("error")?.let { return@guarded it }
    val g = parseCfgGraph(cfg.toString())
    val n = g.blocks.size
    if (n < 3) return@guarded ok(JSONObject().put("flattened", false).put("blockCount", n).put("reason", "基本块过少（$n）"))

    val indeg = IntArray(n)
    g.edges.forEach { e -> if (e.to in 0 until n) indeg[e.to]++ }
    var disp = -1
    var best = 0
    for (i in 0 until n) if (indeg[i] > best) { best = indeg[i]; disp = i }
    if (disp < 0 || best < 3) {
        return@guarded ok(JSONObject().put("flattened", false).put("maxInDegree", best).put("blockCount", n)
            .put("reason", "未找到明显的分发器（最大入度 $best）"))
    }
    val dispVa = g.blocks[disp].addrValue

    val starts = g.blocks.map { it.addrValue }.filter { it >= 0 }
    val fnStart = starts.minOrNull() ?: return@guarded err("CFG_UNAVAILABLE", "基本块地址无法解析")
    val cmd = "pdj $maxInsns @ " + hex(fnStart)
    val r = runCatching { rzCommand(workspaceId, editSessionId, cmd, false) }.getOrNull()
        ?: return@guarded err("DISASM_FAILED", "反汇编命令无响应")
    r.optJSONObject("error")?.let { return@guarded it }
    val insns = OllvmDeFlat.parseInsns(r.optString("text").ifBlank { r.optString("stdout") })
    if (insns.isEmpty()) return@guarded err("DISASM_FAILED", "反汇编输出为空或无法解析（$cmd）")

    val bounds = g.blocks.map { b ->
        val end = deflatParseHexOr(b.endText, if (b.addrValue >= 0) b.addrValue else -1L)
        Triple(b.addrValue, end, ArrayList<DeFlatInsn>())
    }
    for (ins in insns) {
        for (i in 0 until n) {
            val (s, e, list) = bounds[i]
            if (s >= 0 && ins.offset >= s && (e <= 0 || ins.offset < e)) { list.add(ins); break }
        }
    }

    val dBlocks = ArrayList<DeFlatBlock>(n)
    for (i in 0 until n) {
        val (s, e, list) = bounds[i]
        if (list.isEmpty()) { dBlocks.add(DeFlatBlock(i, s, e, null, null)); continue }
        val last = list.last()
        dBlocks.add(DeFlatBlock(i, s, e, OllvmDeFlat.decodeTerminator(last.bytes, last.offset, a), deflatStateOf(list)))
    }

    val dispInsns = bounds.getOrNull(disp)?.third ?: emptyList()
    var stateToTarget = OllvmDeFlat.decodeCmpChainDispatcher(dispInsns)
    var dispatchMode = if (stateToTarget.isNotEmpty()) "cmp-chain" else "none"
    var tableDerived = false
    if (stateToTarget.isEmpty()) {
        val outTargets = g.edges.filter { it.from == disp && it.to != disp }
            .mapNotNull { g.blocks.getOrNull(it.to)?.addrValue?.takeIf { v -> v >= 0 } }.toSet()
        val spec = OllvmDeFlat.detectTableSpec(dispInsns)
        if (spec != null && outTargets.size >= 2) {
            val count = outTargets.size + 1
            val tb = deflatReadMem(workspaceId, editSessionId, spec.tableVa, count * spec.entrySize)
            if (tb != null && tb.isNotEmpty()) {
                val decoded = OllvmDeFlat.decodeJumpTable(tb, spec, count)
                val valid = OllvmDeFlat.validateTableTargets(decoded, outTargets)
                if (valid.isNotEmpty()) { stateToTarget = valid; dispatchMode = "jump-table"; tableDerived = true }
            }
        }
    }

    val forceUnsafeTable = tableDerived && !allowTable
    val patches = OllvmDeFlat.plan(dBlocks, dispVa, stateToTarget, a)
    val safeP = if (forceUnsafeTable) emptyList() else patches.filter { it.safe }
    val unsafeP = if (forceUnsafeTable) patches else patches.filter { !it.safe }

    val addrToIndex = HashMap<Long, Int>()
    for (i in 0 until n) { val v = g.blocks[i].addrValue; if (v >= 0) addrToIndex[v] = i }
    val retarget = HashMap<Int, Int>()
    for (p in safeP) addrToIndex[p.newTarget]?.let { t -> retarget[p.blockIndex] = t }
    val dg = OllvmDeFlat.deFlattenGraph(n, g.edges.map { it.from to it.to }, disp, retarget)

    val afterIdx = (0 until n).filter { it != disp }
    val afterCfg = JSONObject()
        .put("functionName", g.functionName)
        .put("functionVa", g.functionVa)
        .put("basicBlocks", JSONArray(afterIdx.map { i ->
            JSONObject().put("addr", g.blocks[i].addrText.ifBlank { hex(g.blocks[i].addrValue) }).put("endAddr", g.blocks[i].endText)
        }))
        .put("edges", JSONArray(dg.edges.map { (f, t) ->
            JSONObject().put("from", hex(g.blocks[f].addrValue)).put("to", hex(g.blocks[t].addrValue))
        }))

    ok(JSONObject()
        .put("dryRun", true)
        .put("flattened", true)
        .put("arch", a)
        .put("dispatcher", hex(dispVa))
        .put("dispatcherInDegree", best)
        .put("blockCount", n)
        .put("instructionCount", insns.size)
        .put("dispatcherStates", stateToTarget.size)
        .put("dispatchMode", dispatchMode)
        .put("tableDerived", tableDerived)
        .put("patchable", safeP.size)
        .put("unpatchable", unsafeP.size)
        .put("compare", JSONObject()
            .put("beforeBlocks", n).put("afterBlocks", dg.keptBlocks)
            .put("beforeEdges", g.edges.size).put("afterEdges", dg.edges.size)
            .put("removedBlock", dg.removed)
            .put("added", JSONArray(dg.added.map { JSONArray().put(it.first).put(it.second) })))
        .put("afterCfg", afterCfg)
        .put("patches", JSONArray(safeP.map { deflatPatchJson(it, false) }))
        .put("skipped", JSONArray(unsafeP.map { deflatPatchJson(it, forceUnsafeTable) })))
}

/** 批量扫描：对前 maxFunctions 个函数各跑一次 deflatAnalyze，汇总被平坦化的函数与可重定向数。 */
internal fun EngineRuntime.deflatScan(
    workspaceId: String,
    editSessionId: String = "",
    maxFunctions: Int = 10,
): JSONObject = guarded {
    val funcs = runCatching { rzFunctions(workspaceId, editSessionId, maxFunctions) }.getOrNull()
        ?.optJSONArray("functions") ?: JSONArray()
    val limit = minOf(funcs.length(), maxFunctions)
    val items = JSONArray()
    var flattened = 0
    var patchable = 0
    for (i in 0 until limit) {
        val f = funcs.optJSONObject(i) ?: continue
        val va = f.optString("startAddr").ifBlank { f.optString("addr") }
        if (va.isBlank()) continue
        val r = runCatching { deflatAnalyze(workspaceId, editSessionId, va, "", 1200, false) }.getOrNull() ?: continue
        if (r.optJSONObject("error") != null) continue
        if (!r.optBoolean("flattened", false)) continue
        val p = r.optInt("patchable", 0)
        flattened++
        patchable += p
        items.put(JSONObject()
            .put("name", f.optString("name").ifBlank { va })
            .put("va", va)
            .put("patchable", p)
            .put("unpatchable", r.optInt("unpatchable", 0))
            .put("dispatchMode", r.optString("dispatchMode"))
            .put("dispatcher", r.optString("dispatcher"))
            .put("compare", r.optJSONObject("compare") ?: JSONObject()))
    }
    ok(JSONObject().put("scanned", limit).put("flattened", flattened).put("patchableTotal", patchable).put("functions", items))
}
