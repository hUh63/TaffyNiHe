package com.soreverse.mcp.engine

import com.exbin.app.elf.ControlFlowAnalyzer
import com.exbin.app.elf.DisassembledInstruction
import com.exbin.app.elf.pseudoc.PseudoCConverter
import com.exbin.app.elf.pseudoc.r2dec.R2DecPseudoC
import org.json.JSONArray
import org.json.JSONObject

/**
 * r2dec 纯 Java 反编译引擎（自 Exbin / r2dec plus 整体移植）的接入层。
 *
 * 输入 rizin `agfj`（函数 CFG + 每块反汇编），输出结构化伪 C 文本。
 * 控制流图经 [PseudoCConverter.PseudoCContext.prebuiltCfg] 注入，
 * 从而绕开 native CFA 依赖（[com.exbin.app.nativebridge.NativeBridge] 为降级桩）。
 */
internal object R2DecEngine {

    private const val EM_ARM = 40
    private const val EM_AARCH64 = 183

    private val RE_ARM64_REG = Regex("\\b[wx]\\d{1,2}\\b")
    private val RE_ARM32_REG = Regex("\\b[rs]\\d{1,2}\\b")

    /** 依据操作数寄存器风格推断 ELF e_machine（默认 AArch64）。 */
    private fun machineOf(insns: List<DisassembledInstruction>): Int {
        var arm64 = 0
        var arm32 = 0
        for (ins in insns) {
            val op = ins.opStr ?: ""
            if (RE_ARM64_REG.containsMatchIn(op)) arm64++
            else if (RE_ARM32_REG.containsMatchIn(op)) arm32++
        }
        return if (arm32 > arm64) EM_ARM else EM_AARCH64
    }

    private fun numOf(v: Any?): Long = when (v) {
        null, JSONObject.NULL -> -1L
        is Number -> v.toLong()
        is String -> {
            val s = v.trim()
            when {
                s.isEmpty() -> -1L
                s.startsWith("0x", true) -> s.substring(2).toLongOrNull(16) ?: -1L
                else -> s.toLongOrNull() ?: (s.toLongOrNull(16) ?: -1L)
            }
        }
        else -> -1L
    }

    /** 由 rizin `agfj` 构建 r2dec 上下文（含注入的 prebuiltCfg）。 */
    private fun buildCtx(agfjText: String, fnNameIn: String, isThumb: Boolean): PseudoCConverter.PseudoCContext? {
        if (agfjText.isBlank()) return null
        val arr = JSONArray(agfjText)
        val fn = arr.optJSONObject(0) ?: return null
        val blocks = fn.optJSONArray("blocks") ?: return null
        val all = ArrayList<DisassembledInstruction>()
        val cfg = ControlFlowAnalyzer.CFG()
        val jumpOf = HashMap<Long, Long>()
        val failOf = HashMap<Long, Long>()
        var funcAddr = numOf(fn.opt("offset")).takeIf { it > 0 } ?: -1L
        var funcEnd = if (funcAddr > 0) funcAddr else 0L

        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val start = numOf(b.opt("offset") ?: b.opt("addr"))
            if (start <= 0) continue
            if (funcAddr <= 0) funcAddr = start
            val blk = ControlFlowAnalyzer.Block(start)
            blk.blockId = i
            var last = start
            b.optJSONArray("ops")?.let { ops ->
                for (j in 0 until ops.length()) {
                    val op = ops.optJSONObject(j) ?: continue
                    val a = numOf(op.opt("offset") ?: op.opt("addr"))
                    if (a <= 0) continue
                    val disasm = op.optString("disasm").ifBlank { op.optString("opcode") }.trim()
                    if (disasm.isEmpty()) continue
                    val hasMn = op.has("mnemonic") && op.optString("mnemonic").isNotBlank()
                    val mn = if (hasMn) op.optString("mnemonic") else disasm.substringBefore(' ')
                    val opsStr = if (hasMn) disasm else disasm.substringAfter(' ', "")
                    val di = DisassembledInstruction(a, null, mn, opsStr)
                    blk.instructions.add(di)
                    all.add(di)
                    last = a
                }
            }
            blk.startAddr = start
            blk.endAddr = last
            blk.isEntry = (i == 0)
            if (last > funcEnd) funcEnd = last
            cfg.blocks.add(blk)
            cfg.byAddr[start] = blk
            val jj = numOf(b.opt("jump"))
            if (jj > 0) jumpOf[start] = jj
            val ff = numOf(b.opt("fail"))
            if (ff > 0) failOf[start] = ff
        }
        if (all.isEmpty()) return null

        for (k in cfg.blocks.indices) {
            val blk = cfg.blocks[k]
            val j = jumpOf[blk.startAddr]
            val f = failOf[blk.startAddr]
            when {
                j != null && f != null -> {
                    blk.successors.add(ControlFlowAnalyzer.Edge(blk.startAddr, j, ControlFlowAnalyzer.EdgeKind.TRUE_BRANCH, "true"))
                    blk.successors.add(ControlFlowAnalyzer.Edge(blk.startAddr, f, ControlFlowAnalyzer.EdgeKind.FALSE_BRANCH, "false"))
                }
                j != null -> {
                    val back = j <= blk.startAddr
                    blk.successors.add(
                        ControlFlowAnalyzer.Edge(
                            blk.startAddr, j,
                            if (back) ControlFlowAnalyzer.EdgeKind.BACK_EDGE else ControlFlowAnalyzer.EdgeKind.UNCONDITIONAL,
                            if (back) "loop" else "uncond",
                        ),
                    )
                }
                else -> {
                    val nxt = cfg.blocks.getOrNull(k + 1)
                    if (nxt != null) {
                        blk.successors.add(ControlFlowAnalyzer.Edge(blk.startAddr, nxt.startAddr, ControlFlowAnalyzer.EdgeKind.UNCONDITIONAL, "fallthrough"))
                    }
                }
            }
        }

        val depth = HashMap<Long, Int>()
        val q = ArrayDeque<Long>()
        if (funcAddr > 0) {
            depth[funcAddr] = 0
            q.addLast(funcAddr)
        }
        var guard = 0
        while (q.isNotEmpty() && guard++ < 200000) {
            val a = q.removeFirst()
            val d = depth[a] ?: 0
            cfg.byAddr[a]?.successors?.forEach { e ->
                if (depth[e.to] == null && cfg.byAddr.containsKey(e.to)) {
                    depth[e.to] = d + 1
                    q.addLast(e.to)
                }
            }
        }
        var maxD = 0
        for (blk in cfg.blocks) {
            blk.depth = depth[blk.startAddr] ?: 0
            if (blk.depth > maxD) maxD = blk.depth
            if (blk.successors.isEmpty()) {
                blk.isExit = true
                cfg.exitBlockIds.add(blk.blockId)
            }
        }
        cfg.maxDepth = maxD
        cfg.instructions = all
        cfg.entryBlockId = 0

        val fnName = fnNameIn.ifBlank { fn.optString("name") }
            .ifBlank { "sub_" + java.lang.Long.toHexString(funcAddr) }
        val size = (funcEnd - funcAddr + 4).coerceAtLeast(4L)
        val ctx = PseudoCConverter.PseudoCContext(fnName, funcAddr, size, machineOf(all), isThumb, all)
        ctx.prebuiltCfg = cfg
        return ctx
    }

    /**
     * 整函数伪 C。
     * @param agfjText rizin `agfj` 的 JSON（函数数组）
     * @return 伪 C 文本；无法解析或空结果时返回 null
     */
    fun decompile(agfjText: String, fnNameIn: String, isThumb: Boolean = false): String? {
        return try {
            val ctx = buildCtx(agfjText, fnNameIn, isThumb) ?: return null
            val text = R2DecPseudoC().convert(ctx).joinToString("\n")
            if (text.isBlank()) null else text
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * SimplePseudoC 结构化伪 C（Exbin `SimplePseudoC` v2.1.4 引擎）。
     *
     * 与 r2dec 引擎共用同一 CFG 构建（prebuiltCfg 注入），但走 Exbin 的
     * 结构化转换器：支配关系 + 循环识别 + 空分支裁剪 + goto 统一。
     * 适合作为 r2dec 之外的第二视角 / 交叉验证。
     */
    fun decompileSimple(agfjText: String, fnNameIn: String, isThumb: Boolean = false): String? {
        return try {
            val ctx = buildCtx(agfjText, fnNameIn, isThumb) ?: return null
            val text = com.exbin.app.elf.pseudoc.SimplePseudoC().convert(ctx).joinToString("\n")
            if (text.isBlank()) null else text
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 整函数伪 C + 块首行映射（对标 Exbin `BlockPseudoCProvider`）。
     * @return (body 行列表, 块入口地址 → 首行下标)；失败返回 null
     */
    fun decompileBlocks(agfjText: String, fnNameIn: String, isThumb: Boolean = false): Pair<List<String>, Map<Long, Int>>? {
        return try {
            val ctx = buildCtx(agfjText, fnNameIn, isThumb) ?: return null
            val res = R2DecPseudoC().convertWithBlockMap(ctx) ?: return null
            @Suppress("UNCHECKED_CAST")
            val body = res.getOrNull(0) as? List<String> ?: return null
            @Suppress("UNCHECKED_CAST")
            val map = res.getOrNull(1) as? Map<Long, Int> ?: emptyMap()
            if (body.isEmpty()) null else (body to map)
        } catch (t: Throwable) {
            null
        }
    }
}
