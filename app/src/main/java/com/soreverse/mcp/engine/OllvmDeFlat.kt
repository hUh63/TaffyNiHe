package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: OLLVM 控制流平坦化「去平坦化」规划内核（纯逻辑，无 Android / 无引擎依赖）。
 *
 * 思路：控制流平坦化把每个真实块 B 的出口改成「写状态常量 S，跳到分发器 D」，
 * 由 D 按 S 分派到真正的后继 T。去平坦化 = 把这些出口跳转**等长重定向**到 T，
 * 从而消掉分发器（D 及其分派逻辑变成死代码，不影响语义）。
 *
 * 本文件只负责「算」，不负责「取数」与「写盘」：
 *  - 取数（反汇编 / 基本块）在调用方（工具层用 rizin 命令）；
 *  - 写盘走编辑内核 editHexVa（等长原位改写，带快照/undo）。
 *
 * 之所以独立成纯逻辑，是为了能在 JVM 单测里对指令编码、安全判定、分发器解析做真断言
 * （编码错一位就转错地方，属于必须测的部分）。
 */

/** 终止跳转指令的种类（决定重定向时的编码方式）。 */
internal enum class DeFlatKind {
    ARM64_B,
    ARM64_BL,
    ARM32_B,
    X86_JMP_REL32,
    X86_JMP_REL8,
    UNSUPPORTED,
}

/** 一条终止跳转（基本块的出口）：insnAddr 为指令虚拟地址，target 为其跳转目标。 */
internal class DeFlatTerminator(
    val addr: Long,
    val bytes: ByteArray,
    val kind: DeFlatKind,
    val target: Long,
)

/**
 * 一个基本块。
 * @param nextState 该块写出的「下一状态」常量（未识别到为 null）。
 */
internal class DeFlatBlock(
    val index: Int,
    val start: Long,
    val end: Long,
    val terminator: DeFlatTerminator?,
    val nextState: Long?,
)

/**
 * 一条跳转重定向计划。
 * @param safe true=可安全等长重定向；false=不可用（reason 说明原因，newBytes 等于原文）。
 */
internal class DeFlatPatch(
    val blockIndex: Int,
    val insnAddr: Long,
    val oldTarget: Long,
    val newTarget: Long,
    val oldBytes: ByteArray,
    val newBytes: ByteArray,
    val kind: DeFlatKind,
    val safe: Boolean,
    val reason: String,
)

/** 反汇编出来的一条指令（rizin pdj/pdbj 的最小字段集）。 */
internal class DeFlatInsn(
    val offset: Long,
    val size: Int,
    val bytes: ByteArray,
    val disasm: String,
)

/** 跳表（switch）分发器规格：状态值经表得到真实目标。 */
internal class DeFlatTableSpec(
    val tableVa: Long,
    val entrySize: Int,
    val signed: Boolean,
    val relative: Boolean,
    val indexBase: Long,
    val count: Int,
)

/** 去平坦化后的 CFG 形状（供对比图使用）。 */
internal class DeFlatGraph(
    val keptBlocks: Int,
    val removed: Int,
    val edges: List<Pair<Int, Int>>,
    val added: List<Pair<Int, Int>>,
)

internal object OllvmDeFlat {

    // ───────────────────────── 指令编解码 ─────────────────────────

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    /** 架构名归一化：arm64 / arm32 / x86（x86 与 x86_64 在跳转编码上一致，都用 rel32）。 */
    internal fun normArch(arch: String): String = when (arch.trim().lowercase()) {
        "arm64", "arm64e", "aarch64", "arm64-v8a" -> "arm64"
        "arm", "arm32", "armv7", "armv7a", "armeabi-v7a" -> "arm32"
        "thumb", "thumb2" -> "thumb"
        "x86", "x86_64", "i386", "i686", "amd64", "x64" -> "x86"
        else -> arch.trim().lowercase()
    }

    /** arm64 分支：imm26（字节偏移，按 4 对齐）。返回 null 表示越界/不对齐。 */
    private fun encodeArm64(opBase: Int, insnAddr: Long, target: Long): ByteArray? {
        val delta = target - insnAddr
        if (delta % 4L != 0L) return null
        val imm = delta / 4L
        if (imm < -(1L shl 25) || imm > (1L shl 25) - 1L) return null
        val word = opBase or (imm.toInt() and 0x03FFFFFF)
        return leBytes(word)
    }

    /** arm32 (A32) 分支：PC = insnAddr + 8，imm24（字节偏移按 4 对齐）。 */
    private fun encodeArm32(insnAddr: Long, target: Long): ByteArray? {
        val delta = target - (insnAddr + 8L)
        if (delta % 4L != 0L) return null
        val imm = delta / 4L
        if (imm < -(1L shl 23) || imm > (1L shl 23) - 1L) return null
        val word = 0xEA000000.toInt() or (imm.toInt() and 0x00FFFFFF)
        return leBytes(word)
    }

    private fun encodeX86Rel32(insnAddr: Long, target: Long): ByteArray? {
        val d = target - (insnAddr + 5L)
        if (d < Int.MIN_VALUE.toLong() || d > Int.MAX_VALUE.toLong()) return null
        val v = d.toInt()
        return byteArrayOf(
            0xE9.toByte(),
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
    }

    private fun encodeX86Rel8(insnAddr: Long, target: Long): ByteArray? {
        val d = target - (insnAddr + 2L)
        if (d < -128L || d > 127L) return null
        return byteArrayOf(0xEB.toByte(), (d.toInt() and 0xFF).toByte())
    }

    /**
     * 按「与原文同种类」重编码到新目标：长度恒等于原文（同指令族），越界/不支持返回 null。
     * 重编码前不必再校验 opcode —— 调用方用 decodeTerminator 得到 kind，kind 即代表原文 opcode 已核对过。
     */
    internal fun encodeBranch(kind: DeFlatKind, insnAddr: Long, target: Long): ByteArray? = when (kind) {
        DeFlatKind.ARM64_B -> encodeArm64(0x14000000.toInt(), insnAddr, target)
        DeFlatKind.ARM64_BL -> encodeArm64(0x94000000.toInt(), insnAddr, target)
        DeFlatKind.ARM32_B -> encodeArm32(insnAddr, target)
        DeFlatKind.X86_JMP_REL32 -> encodeX86Rel32(insnAddr, target)
        DeFlatKind.X86_JMP_REL8 -> encodeX86Rel8(insnAddr, target)
        DeFlatKind.UNSUPPORTED -> null
    }

    /** 从字节解码终止跳转（含 opcode 校验）；无法识别返回 null。 */
    internal fun decodeTerminator(bytes: ByteArray, addr: Long, arch: String): DeFlatTerminator? {
        when (normArch(arch)) {
            "arm64" -> {
                if (bytes.size < 4) return null
                val w = leInt(bytes, 0)
                val op = w and 0xFC000000.toInt()
                val bOp = 0x14000000
                val blOp = 0x94000000.toInt()
                if (op == bOp || op == blOp) {
                    val imm26 = w and 0x03FFFFFF
                    val raw = (imm26.toLong() shl 2)
                    val signed = if ((imm26 and 0x02000000) != 0) raw - (1L shl 28) else raw
                    val kind = if (op == bOp) DeFlatKind.ARM64_B else DeFlatKind.ARM64_BL
                    return DeFlatTerminator(addr, bytes.copyOf(4), kind, addr + signed)
                }
                return null
            }
            "arm32" -> {
                if (bytes.size < 4) return null
                val w = leInt(bytes, 0)
                if ((w and 0xFF000000.toInt()) == 0xEA000000.toInt()) {
                    val imm24 = w and 0x00FFFFFF
                    val raw = (imm24.toLong() shl 2)
                    val signed = if ((imm24 and 0x00800000) != 0) raw - (1L shl 26) else raw
                    return DeFlatTerminator(addr, bytes.copyOf(4), DeFlatKind.ARM32_B, addr + 8L + signed)
                }
                return null
            }
            "x86" -> {
                if (bytes.isEmpty()) return null
                val b0 = bytes[0].toInt() and 0xFF
                if (b0 == 0xE9 && bytes.size >= 5) {
                    val rel = leInt(bytes, 1)
                    return DeFlatTerminator(addr, bytes.copyOf(5), DeFlatKind.X86_JMP_REL32, addr + 5L + rel)
                }
                if (b0 == 0xEB && bytes.size >= 2) {
                    val rel = bytes[1].toInt() // signed
                    return DeFlatTerminator(addr, bytes.copyOf(2), DeFlatKind.X86_JMP_REL8, addr + 2L + rel)
                }
                return null
            }
            else -> return null
        }
    }

    // ───────────────────────── 规划 ─────────────────────────

    private fun unsafe(b: DeFlatBlock, t: DeFlatTerminator, reason: String): DeFlatPatch =
        DeFlatPatch(b.index, t.addr, t.target, t.target, t.bytes, t.bytes, t.kind, false, reason)

    /**
     * 生成去平坦化计划。
     *
     * @param blocks      函数的基本块（含终止跳转与写出的状态常量）
     * @param dispatcher  分发器地址（VA）
     * @param stateToTarget 状态常量 -> 真实目标块地址（VA，由分发器解析得到）
     * @param arch        架构（arm64 / arm32 / x86 …）
     */
    internal fun plan(
        blocks: List<DeFlatBlock>,
        dispatcher: Long,
        stateToTarget: Map<Long, Long>,
        arch: String,
    ): List<DeFlatPatch> {
        val out = ArrayList<DeFlatPatch>()
        for (b in blocks) {
            val t = b.terminator ?: continue
            if (t.target != dispatcher) continue          // 出口已是直连，无需处理
            val s = b.nextState
            if (s == null) {
                out += unsafe(b, t, "未识别到该块写出的状态常量（状态可能由寄存器间接计算）")
                continue
            }
            val nt = stateToTarget[s]
            if (nt == null) {
                out += unsafe(b, t, "分发器未解析出状态 0x" + java.lang.Long.toHexString(s) + " 的目标（可能是跳表分派或状态被混淆）")
                continue
            }
            if (nt == t.target) continue
            val nb = encodeBranch(t.kind, t.addr, nt)
            if (nb == null) {
                out += unsafe(b, t, "无法编码到目标 0x" + java.lang.Long.toHexString(nt) + "（超出指令跳转范围或指令种类不支持）")
                continue
            }
            if (nb.size != t.bytes.size) {
                out += unsafe(b, t, "重编码长度与原文不一致（" + nb.size + " vs " + t.bytes.size + "）")
                continue
            }
            out += DeFlatPatch(b.index, t.addr, t.target, nt, t.bytes, nb, t.kind, true, "可安全等长重定向")
        }
        return out
    }

    // ───────────────────────── 写入（供单测/工具复用） ─────────────────────────

    /** 把 [offsets] 指向的补丁写进容器副本（越界抛 IllegalArgumentException）。 */
    internal fun applyPatches(container: ByteArray, offsets: List<Pair<Int, ByteArray>>): ByteArray {
        val out = container.copyOf()
        for ((off, bytes) in offsets) {
            require(off >= 0 && off + bytes.size <= out.size) { "patch at $off (+${bytes.size}) out of range" }
            System.arraycopy(bytes, 0, out, off, bytes.size)
        }
        return out
    }

    // ───────────────────────── rizin 输出解析（pdbj / pdj） ─────────────────────────

    /** 解析 rizin `pdj` / `pdbj` 指令数组。字节字段优先取 "bytes"，退回 "opcode"。 */
    internal fun parseInsns(json: String): List<DeFlatInsn> {
        if (json.isBlank()) return emptyList()
        val arr = runCatching {
            val t = json.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("instructions")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<DeFlatInsn>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val off = o.optLong("offset", -1L)
            val size = o.optInt("size", 0)
            val hex = o.optString("bytes").ifBlank { o.optString("opcode") }.replace(Regex("[\\s,>]"), "")
            val bytes = runCatching {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrDefault(ByteArray(0))
            out += DeFlatInsn(off, size, bytes, o.optString("disasm").trim())
        }
        return out
    }

    /** 取文本里最后一个 `#立即数`（arm64 用 #，x86 用裸立即数）。 */
    internal fun lastImmediate(disasm: String): Long? {
        var last: Long? = null
        Regex("#(0x[0-9a-fA-F]+|[0-9]+)").findAll(disasm).forEach { m ->
            val t = m.groupValues[1]
            val v = if (t.startsWith("0x", true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull()
            if (v != null) last = v
        }
        return last
    }

    /** 取文本里最后一个十六进制/十进制地址。 */
    internal fun lastAddress(disasm: String): Long? {
        var last: Long? = null
        Regex("(0x[0-9a-fA-F]+|(?<![0-9a-zA-Z_])[0-9]+)").findAll(disasm).forEach { m ->
            val t = m.groupValues[1]
            val v = if (t.startsWith("0x", true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull()
            if (v != null) last = v
        }
        return last
    }

    private val condBranchRe = Regex(
        "^(b\\.[a-z0-9]+|beq|bne|bgt|blt|bge|ble|bhi|bls|bcc|bcs|bmi|bpl|bvs|bvc|cbz|cbnz|tbz|tbnz|" +
            "je|jne|jz|jnz|jg|jl|jge|jle|ja|jb|jae|jbe|js|jns|jo|jno)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 解析「cmp 链」式分发器：`cmp x, #S` 之后紧接的 `b.<cond> T` 即建立 S -> T。
     * 跳表式分发器（ldr/br）无法在此解析，返回空表（调用方据此标注「不支持自动展开」）。
     */
    internal fun decodeCmpChainDispatcher(insns: List<DeFlatInsn>): Map<Long, Long> {
        val map = LinkedHashMap<Long, Long>()
        var lastCmp: Long? = null
        for (ins in insns) {
            val d = ins.disasm
            if (d.isEmpty()) continue
            val mnem = d.substringBefore(' ').lowercase()
            if (mnem == "cmp" || mnem == "cmn" || mnem == "subs" || mnem == "sub") {
                lastImmediate(d)?.let { lastCmp = it }
                continue
            }
            if (condBranchRe.containsMatchIn(d)) {
                val tgt = lastAddress(d)
                if (tgt != null && lastCmp != null) map[lastCmp!!] = tgt
                lastCmp = null
            }
        }
        return map
    }

    // ───────────────────────── 跳表（switch）分发器 ─────────────────────────

    private fun hexOrDec(t: String): Long {
        val s = t.removePrefix("#").trim()
        return if (s.startsWith("0x", true)) s.substring(2).toLongOrNull(16) ?: 0L else s.toLongOrNull() ?: 0L
    }

    private fun firstHex(d: String): Long? {
        val m = Regex("0x[0-9a-fA-F]+").find(d) ?: return null
        return m.value.substring(2).toLongOrNull(16)
    }

    private fun loadEntrySize(mn: String, disasm: String): Int = when (mn) {
        "ldrb", "ldurb" -> 1
        "ldrh", "ldurh", "ldrsh", "ldursh", "movzx" -> 2
        "ldrsw", "ldursw", "movsxd", "movsx" -> 4
        else -> if (Regex("\\bw\\d+\\s*,").containsMatchIn(disasm)) 4 else 8
    }

    /**
     * 从分发器指令里识别「跳表」规格：arm64 用 adrp+add 得到表基址 + 带缩放索引的 ldr；
     * x86 用 lea rX,[rip+off] 得到表基址 + [reg+reg*N]。识别不出返回 null。
     *
     * 只负责「识别形态」，**不做正确性保证**：真目标集合由 validateTableTargets 用分发器出边校验。
     */
    internal fun detectTableSpec(insns: List<DeFlatInsn>): DeFlatTableSpec? {
        var tableVa: Long? = null
        var entrySize = 0
        var scale = 0
        var signed = false
        var adrpReg: String? = null
        var adrpVal = 0L
        for (ins in insns) {
            val d = ins.disasm
            if (d.isBlank()) continue
            val mn = d.substringBefore(' ').lowercase()
            when (mn) {
                "adrp" -> {
                    val m = Regex("^adrp\\s+(\\w+)\\s*,\\s*#?(0x[0-9a-fA-F]+)").find(d)
                    if (m != null) {
                        adrpReg = m.groupValues[1]
                        adrpVal = m.groupValues[2].substring(2).toLongOrNull(16) ?: 0L
                    }
                }
                "add" -> {
                    val m = Regex("^add\\s+(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*#(0x[0-9a-fA-F]+|\\d+)").find(d)
                    if (m != null && adrpReg != null && m.groupValues[1] == adrpReg && m.groupValues[2] == adrpReg) {
                        tableVa = adrpVal + hexOrDec(m.groupValues[3])
                    }
                }
                "lea" -> {
                    val rip = Regex("\\[\\s*rip\\s*\\+\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\]").find(d)
                    tableVa = if (rip != null) ins.offset + ins.size + hexOrDec(rip.groupValues[1]) else firstHex(d)
                }
                "ldr", "ldrb", "ldrh", "ldrsw", "ldrsh", "ldur" -> {
                    val m = Regex("\\[\\s*\\w+\\s*,\\s*\\w+\\s*,\\s*lsl\\s*#(\\d+)\\s*\\]").find(d)
                    if (m != null) {
                        scale = 1 shl (m.groupValues[1].toIntOrNull() ?: 0)
                        entrySize = loadEntrySize(mn, d)
                        signed = mn == "ldrsw" || mn == "ldrsh"
                    } else if (Regex("\\[\\s*\\w+\\s*,\\s*\\w+\\s*\\]").containsMatchIn(d)) {
                        scale = 1
                        entrySize = loadEntrySize(mn, d)
                        signed = mn == "ldrsw" || mn == "ldrsh"
                    }
                }
                "movsxd", "movsx", "movzx", "mov" -> {
                    val m = Regex("\\[\\s*(\\w+)\\s*\\+\\s*(\\w+)\\s*\\*\\s*(\\d+)\\s*\\]").find(d)
                    if (m != null) {
                        scale = m.groupValues[3].toIntOrNull() ?: 0
                        entrySize = loadEntrySize(mn, d)
                        signed = mn == "movsxd" || mn == "movsx"
                    }
                }
                "jmp" -> {
                    val m = Regex("\\[\\s*(\\w+)\\s*\\*\\s*(\\d+)\\s*\\+\\s*(0x[0-9a-fA-F]+)\\s*\\]").find(d)
                    if (m != null) {
                        scale = m.groupValues[2].toIntOrNull() ?: 0
                        entrySize = 8
                        tableVa = m.groupValues[3].substring(2).toLongOrNull(16)
                    }
                }
            }
        }
        val base = tableVa ?: return null
        if (entrySize <= 0) entrySize = 8
        // 缩放与项宽一致（scale == entrySize）说明索引是「第 i 项」；否则按比例归一。
        val relative = signed
        return DeFlatTableSpec(base, entrySize, signed, relative, 0L, -1)
    }

    private fun readLe(b: ByteArray, off: Int, size: Int, signed: Boolean): Long {
        var v = 0L
        for (k in 0 until size) v = v or ((b[off + k].toLong() and 0xFF) shl (8 * k))
        if (signed && size < 8) {
            val signBit = 1L shl (8 * size - 1)
            if ((v and signBit) != 0L) v -= (1L shl (8 * size))
        }
        return v
    }

    /** 读表：map[状态值] = 目标 VA。count<=0 时按字节长度取满。假设 状态值 == 表索引（OLLVM 默认）。 */
    internal fun decodeJumpTable(bytes: ByteArray, spec: DeFlatTableSpec, count: Int): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>()
        val n = if (count > 0) count else bytes.size / spec.entrySize.coerceAtLeast(1)
        for (i in 0 until n) {
            val off = i * spec.entrySize
            if (off + spec.entrySize > bytes.size) break
            val v = readLe(bytes, off, spec.entrySize, spec.signed)
            val target = if (spec.relative) spec.tableVa + v else v
            out[spec.indexBase + i.toLong()] = target
        }
        return out
    }

    /**
     * 用「分发器出边目标集合」校验解出的表映射：解出的目标必须基本落在该集合内
     * （允许多至 1 个 default / 越界项）。不满足则返回空表——宁可不写也不写错。
     */
    internal fun validateTableTargets(decoded: Map<Long, Long>, outTargets: Set<Long>): Map<Long, Long> {
        if (decoded.size < 2 || outTargets.isEmpty()) return emptyMap()
        val ok = decoded.filterValues { it in outTargets }
        if (ok.size < 2) return emptyMap()
        if (decoded.size - ok.size > 1) return emptyMap()
        return ok
    }

    // ───────────────────────── 去平坦化后的 CFG 形状（对比图） ─────────────────────────

    /**
     * 计算去平坦化后的 CFG：删掉分发器，把「b -> dispatcher」边换成「b -> 真实后继 T」。
     * @param retarget 块 index -> 新目标块 index（仅来自 safe 补丁）
     */
    internal fun deFlattenGraph(
        blockCount: Int,
        edges: List<Pair<Int, Int>>,
        dispatcher: Int,
        retarget: Map<Int, Int>,
    ): DeFlatGraph {
        val out = ArrayList<Pair<Int, Int>>()
        val added = ArrayList<Pair<Int, Int>>()
        val seen = HashSet<String>()
        fun add(f: Int, t: Int) {
            if (f == dispatcher || t == dispatcher) return
            if (seen.add("$f>$t")) out.add(f to t)
        }
        for ((f, t) in edges) add(f, t)
        for ((f, t) in retarget) {
            if (f == dispatcher) continue
            val before = out.size
            add(f, t)
            if (out.size > before) added.add(f to t)
        }
        return DeFlatGraph((blockCount - 1).coerceAtLeast(0), dispatcher, out, added)
    }
}
