package com.soreverse.mcp.engine

import com.exbin.app.nativebridge.NativeBridge
import com.soreverse.mcp.engine.standalone.Arm64Disasm

/**
 * Exbin [com.exbin.app.elf.FunctionSignatureAnalyzer] 的签名还原后端。
 *
 * Exbin 原本用 soide-native（Capstone + 寄存器 read-before-write 类型启发式）还原签名；
 * 塔菲没有该 native 库，这里用自带的纯 Java AArch64 反汇编器 [Arm64Disasm] 实现
 * 等价思路：对函数区间反汇编 → 跟踪 x0-x7 的首次读/写 → 在读写前被读取的参数寄存器
 * 决定参数个数，输出 `int name(int, int, ...)` 风格的 native 兼容签名串，
 * 交由 [com.exbin.app.elf.FunctionSignatureAnalyzer] 解析为结构化 Result。
 *
 * 未注册时 [NativeBridge.isSigSupported] 为 false，FSA 自动降级（不伪造结论）。
 */
internal object ExbinSignatureBackend : NativeBridge.SignatureBackend {

    private const val EM_ARM = 40
    private const val EM_AARCH64 = 183
    private const val MAX_INSNS = 512

    @Volatile
    private var registered = false

    /** 把本后端注册进 NativeBridge（幂等）。 */
    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (!registered) {
                runCatching { NativeBridge.setSignatureBackend(this) }
                registered = true
            }
        }
    }

    override fun available(): Boolean = true

    override fun demangleBatch(names: Array<String>?): Array<String> {
        if (names == null) return emptyArray()
        return Array(names.size) { NativeBridge.demangle(names[it]) }
    }

    override fun restoreSignatures(
        soData: ByteArray?,
        addrs: LongArray?,
        sizes: IntArray?,
        thumbFlags: IntArray?,
        machine: Int,
        names: Array<String>?,
    ): Array<String> {
        if (soData == null || addrs == null || sizes == null) return emptyArray()
        val out = Array(addrs.size) { "" }
        for (i in addrs.indices) {
            val off = addrs[i]
            val rawSize = sizes.getOrElse(i) { 256 }.let { if (it <= 0) 256 else it }
            if (off < 0 || off >= soData.size) continue
            val len = minOf(rawSize, 8192, soData.size - off.toInt())
            if (len < 4) continue
            val slice = soData.copyOfRange(off.toInt(), off.toInt() + len)
            val name = names?.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: ("sub_" + java.lang.Long.toHexString(off))
            val argc = if (machine == EM_AARCH64) inferArm64ArgCount(slice) else -1
            out[i] = buildSig(name, argc)
        }
        return out
    }

    /** 塔菲不使用 native handle 模式（始终走 soData 通道）。 */
    override fun restoreSignaturesByHandle(
        handle: Long,
        addrs: LongArray?,
        sizes: IntArray?,
        thumbFlags: IntArray?,
        machine: Int,
        names: Array<String>?,
    ): Array<String> = emptyArray()

    // ============================================================
    // 参数个数推断
    // ============================================================

    /** 会写第一个操作数寄存器的助记符（保守集合）。 */
    private val WRITE_MN = setOf(
        "mov", "movz", "movk", "movn", "add", "adds", "sub", "subs", "adrp", "adr",
        "ldr", "ldp", "ldur", "ldrb", "ldrh", "ldrsb", "ldrsh", "ldrsw",
        "and", "ands", "orr", "eor", "orn", "bic", "lsl", "lsr", "asr", "ror",
        "mul", "madd", "msub", "csel", "csinc", "csinv", "csneg", "cset", "csetm",
        "sxtw", "sxtb", "sxth", "uxtw", "uxtb", "uxth", "ubfx", "sbfx", "bfi", "bfxil",
        "rev", "rev16", "rev32", "clz", "cls", "rbit", "xtn", "xtn2", "scvtf", "ucvtf",
        "fcvtzs", "fcvtzu", "fmov", "mvn", "neg", "negs", "extr", "umulh", "smulh",
    )

    /** 明确不写第一个操作数（比较 / 存储 / 分支 / 返回）。 */
    private val NO_WRITE_MN = setOf(
        "cmp", "cmn", "tst", "ccmp", "ccmn", "str", "stp", "stur", "strb", "strh", "st1", "st",
        "b", "br", "blr", "ret", "cbz", "cbnz", "tbz", "tbnz", "bl",
    )

    private val REG_TOKEN = Regex("\\b([xw])(\\d{1,2})\\b")

    /**
     * 在“首次写入之前被读取”的 x0-x7 前缀决定参数个数。
     * 无法反汇编返回 -1（调用方按 0 参数处理）。
     */
    private fun inferArm64ArgCount(code: ByteArray): Int {
        val insns = try {
            Arm64Disasm(code, 0L).disassemble(code.size / 4)
        } catch (t: Throwable) {
            return -1
        }
        if (insns.isEmpty()) return -1
        val written = HashSet<Int>()
        var maxArg = -1
        val limit = minOf(insns.size, MAX_INSNS)
        for (k in 0 until limit) {
            val ins = insns[k]
            val mn = ins.mnemonic.lowercase()
            val ops = ins.operands ?: ""
            val parts = ops.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            // 目的寄存器 = 第一个操作数；判断该指令是否写它
            val dst = regIndex(parts[0])
            val writesDst = dst != null && mn !in NO_WRITE_MN &&
                (mn in WRITE_MN || parts.size == 1)
            for (pi in parts.indices) {
                val idx = regIndex(parts[pi]) ?: continue
                if (pi == 0 && writesDst) {
                    written.add(idx)
                    continue
                }
                if (idx in 0..7 && idx !in written) maxArg = maxOf(maxArg, idx)
            }
        }
        return maxArg + 1
    }

    /** 从操作数片段里取寄存器索引（支持 `[x0, #16]` / `x0!` / `w3`）。 */
    private fun regIndex(token: String): Int? {
        val m = REG_TOKEN.find(token) ?: return null
        val n = m.groupValues[2].toIntOrNull() ?: return null
        return if (n <= 30) n else null
    }

    /** 生成 native 兼容签名串（类型列表形式，供 FSA.parseNativeSig 解析）。 */
    private fun buildSig(name: String, argc: Int): String {
        if (argc <= 0) return "int $name()"
        val sb = StringBuilder("int ").append(name).append("(")
        for (i in 0 until argc) {
            if (i > 0) sb.append(", ")
            sb.append("int")
        }
        sb.append(")")
        return sb.toString()
    }
}
