package com.soreverse.mcp.engine

/**
 * 去平坦化的「分发器动态验证」：只解释验证所需指令子集的极简寄存器机。
 *
 * 目的：不靠「状态值 == 表索引」这类**假设**，而是**真的把分发器跑一遍**——给定一个状态值，
 * 看它实际跳到哪里。把模拟结果与静态解出的映射逐条比对：
 *  - 完全一致 → 该映射从「假设」变成「证据」，跳表那一路才允许自动写入；
 *  - 有任何不一致 → 整份映射作废（保守，绝不猜）。
 *
 * 只支持验证所需的子集（mov/movz/add/sub/adrp/ldr/cmp/条件分支/br/lea/movsxd/jmp…）；
 * 遇到不认识的指令或内存读失败 → 直接失败（返回 null），保持保守。
 *
 * 纯逻辑，无 Android / 无引擎依赖，可单测。
 */
internal class DeFlatEmu(
    insns: List<DeFlatInsn>,
    private val readMem: (Long, Int) -> ByteArray?,
) {
    private val byAddr: Map<Long, DeFlatInsn> = insns.associateBy { it.offset }
    private val regs = HashMap<String, Long>()
    private var flagN = false
    private var flagZ = false
    private var flagC = false
    private var flagV = false

    /** 失败原因（供调用方诊断）。 */
    var lastError: String = ""
        private set

    /** 已执行步数。 */
    var steps: Int = 0
        private set

    fun setReg(name: String, value: Long) = write(name, value)

    fun getReg(name: String): Long = read(name)

    // ── 寄存器规范化（arm64 的 wN 与 x86 的 eax/r8d 都映射到 64 位宿主寄存器） ──

    private fun canon(name: String): String {
        val r = name.trim().lowercase().removePrefix("$").trim('[', ']', ' ')
        if (r.isEmpty()) return r
        if (r == "wsp") return "sp"
        if (r == "wzr") return "xzr"
        // arm64：wN 与 xN 共用同一物理寄存器
        if (r.length in 2..3 && r[0] == 'w' && r.substring(1).toIntOrNull() != null) return "x" + r.substring(1)
        val x86 = mapOf(
            "eax" to "rax", "ebx" to "rbx", "ecx" to "rcx", "edx" to "rdx",
            "esi" to "rsi", "edi" to "rdi", "ebp" to "rbp", "esp" to "rsp",
        )
        x86[r]?.let { return it }
        if (r.length == 3 && r[0] == 'r' && r.endsWith("d") && r.substring(1, 2).toIntOrNull() != null) return "r" + r.substring(1, 2)
        return r
    }

    private fun is32(name: String): Boolean {
        val r = name.trim().lowercase().removePrefix("$").trim('[', ']', ' ')
        if (r == "wzr" || r == "wsp") return true
        if (r.startsWith("w") && r.length in 2..3 && r.substring(1).toIntOrNull() != null) return true
        if (r in setOf("eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp")) return true
        if (r.length == 3 && r[0] == 'r' && r.endsWith("d") && r.substring(1, 2).toIntOrNull() != null) return true
        return false
    }

    private fun read(name: String): Long {
        val c = canon(name)
        val v = regs[c] ?: 0L
        return if (is32(name) && c != "xzr") v and 0xFFFFFFFFL else v
    }

    private fun write(name: String, value: Long) {
        val c = canon(name)
        if (c == "xzr") return
        regs[c] = if (is32(name)) value and 0xFFFFFFFFL else value
    }

    // ── 取值解析 ──

    private fun immOf(s: String): Long? {
        val t = s.trim().removePrefix("#").trim()
        if (t.isEmpty()) return null
        val neg = t.startsWith("-")
        val body = if (neg) t.substring(1) else t
        val v = if (body.startsWith("0x", true)) body.substring(2).toLongOrNull(16) else body.toLongOrNull()
        return v?.let { if (neg) -it else it }
    }

    private fun isRegToken(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t[0].isDigit() || t.startsWith("#") || t.startsWith("-")) return false
        return Regex("^[a-z][a-z0-9]*$", RegexOption.IGNORE_CASE).matches(t)
    }

    /** 求内存操作数地址：支持 [Xn, Xm, lsl #k] / [Xn, #imm] / [rip + imm] / [rax + rdx*4] / [rax*8 + disp]。 */
    private fun evalMem(expr: String, insnOffset: Long, insnSize: Int): Long? {
        val parts = expr.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        if (parts.size >= 3 && parts[2].lowercase().startsWith("lsl")) {
            val base = read(parts[0])
            val idx = read(parts[1])
            val sh = immOf(parts[2].substringAfter('#', "0").trim()) ?: return null
            return base + (idx shl sh.toInt())
        }
        if (parts.size == 2) {
            val base = read(parts[0])
            val second = parts[1]
            if (isRegToken(second)) return base + read(second)
            val d = immOf(second) ?: return null
            return base + d
        }
        // 单操作数：可能是 "x0" / "rip + 0x1234" / "rax*8 + 0x1000"
        val single = parts[0]
        if (!single.contains('+') && !single.contains('*')) {
            return if (isRegToken(single)) read(single) else immOf(single)
        }
        var addr = 0L
        for (termRaw in single.split('+')) {
            val term = termRaw.trim()
            if (term.isEmpty()) continue
            if (term.contains('*')) {
                val lhs = term.substringBefore('*').trim()
                val rhs = term.substringAfter('*').trim()
                val scale = immOf(rhs) ?: return null
                addr += read(lhs) * scale
            } else if (term.equals("rip", true)) {
                addr += insnOffset + insnSize
            } else if (isRegToken(term)) {
                addr += read(term)
            } else {
                addr += immOf(term) ?: return null
            }
        }
        return addr
    }

    private fun memLoad(addr: Long, size: Int, signed: Boolean): Long? {
        val b = readMem(addr, size) ?: return null
        if (b.size < size) return null
        var v = 0L
        for (k in 0 until size) v = v or ((b[k].toLong() and 0xFF) shl (8 * k))
        if (signed && size < 8) {
            val signBit = 1L shl (8 * size - 1)
            if ((v and signBit) != 0L) v -= (1L shl (8 * size))
        }
        return v
    }

    private fun setFlagsSub(a: Long, b: Long) {
        val r = a - b
        flagN = r < 0
        flagZ = r == 0L
        flagC = java.lang.Long.compareUnsigned(a, b) >= 0
        val sa = a < 0
        val sb = b < 0
        val sr = r < 0
        flagV = (sa != sb) && (sa != sr)
    }

    private fun condHolds(cond: String): Boolean {
        val c = cond.lowercase()
        val n = flagN; val z = flagZ; val cf = flagC; val v = flagV
        return when (c) {
            "eq" -> z
            "ne" -> !z
            "hi" -> cf && !z
            "hs", "cs" -> cf
            "lo", "cc" -> !cf
            "ls" -> !cf || z
            "gt" -> !z && (n == v)
            "ge" -> n == v
            "lt" -> n != v
            "le" -> z || (n != v)
            "mi" -> n
            "pl" -> !n
            "vs" -> v
            "vc" -> !v
            "al" -> true
            else -> false
        }
    }

    /**
     * 从 start 执行到「离开分发器」的跳转，返回目标地址；失败返回 null（原因见 lastError）。
     * 条件分支若已跳离已给的指令集合，则视为成功终止（那正是我们要的真实后继）。
     */
    fun run(start: Long, maxSteps: Int = 128): Long? {
        var pc = start
        steps = 0
        while (steps < maxSteps) {
            val ins = byAddr[pc]
            if (ins == null) {
                lastError = "pc " + "0x" + java.lang.Long.toHexString(pc) + " 不在分发器指令集合内"
                return null
            }
            steps++
            val d = ins.disasm.trim()
            if (d.isEmpty()) { lastError = "空指令"; return null }
            val mn = d.substringBefore(' ').lowercase()
            val ops = d.substringAfter(' ', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val next = pc + ins.size

            when {
                mn == "nop" -> pc = next
                mn == "mov" || mn == "movz" || mn == "movn" || mn == "movk" -> {
                    if (ops.size < 2) return fail("mov 操作数不足")
                    var v = if (isRegToken(ops[1])) read(ops[1]) else immOf(ops[1]) ?: return fail("mov 立即数无法解析")
                    if (mn == "movn") v = v.inv()
                    val shiftTok = ops.getOrNull(2)?.lowercase().orEmpty()
                    if (shiftTok.startsWith("lsl")) {
                        val sh = immOf(shiftTok.substringAfter('#', "0")) ?: 0L
                        v = v shl sh.toInt()
                    }
                    write(ops[0], v)
                    pc = next
                }
                mn == "adr" -> { write(ops[0], immOf(ops[1].trim()) ?: return fail("adr 立即数")); pc = next }
                mn == "adrp" -> { write(ops[0], immOf(ops[1].trim()) ?: return fail("adrp 立即数")); pc = next }
                mn == "lea" -> {
                    val br = d.indexOf('[')
                    if (br < 0) return fail("lea 无内存操作数")
                    val addr = evalMem(d.substring(br + 1, d.length - 1), ins.offset, ins.size) ?: return fail("lea 地址")
                    write(ops[0], addr)
                    pc = next
                }
                mn == "add" || mn == "sub" -> {
                    // arm64：add xD, xN, xM/#imm[, lsl #k]；x86：add r, r/#imm（两操作数，目标即第一源）
                    if (ops.size < 2) return fail("add/sub 操作数不足")
                    val dst = ops[0]
                    val a: Long
                    val bRaw: Long
                    var shiftKind = ""
                    var shift = 0
                    if (ops.size == 2) {
                        a = read(dst)
                        bRaw = if (isRegToken(ops[1])) read(ops[1]) else immOf(ops[1]) ?: return fail("add/sub 源")
                    } else {
                        a = if (ops[1].equals("sp", true)) read("sp")
                        else if (isRegToken(ops[1])) read(ops[1])
                        else immOf(ops[1]) ?: return fail("add/sub 源")
                        bRaw = if (ops[2].equals("sp", true)) read("sp")
                        else if (isRegToken(ops[2])) read(ops[2])
                        else immOf(ops[2]) ?: return fail("add/sub 第二源")
                        val shOp = ops.getOrNull(3)?.lowercase().orEmpty()
                        if (shOp == "lsl" || shOp == "lsr" || shOp == "asr") {
                            shiftKind = shOp
                            shift = (immOf(ops.getOrNull(4) ?: "") ?: 0L).toInt()
                        }
                    }
                    val b = when (shiftKind) {
                        "lsl" -> bRaw shl shift
                        "lsr" -> bRaw ushr shift
                        "asr" -> bRaw shr shift
                        else -> bRaw
                    }
                    write(dst, if (mn == "add") a + b else a - b)
                    pc = next
                }
                mn == "cmp" || mn == "cmn" -> {
                    if (ops.size < 2) return fail("cmp 操作数不足")
                    val a = if (isRegToken(ops[0])) read(ops[0]) else immOf(ops[0]) ?: return fail("cmp 左")
                    val b = if (isRegToken(ops[1])) read(ops[1]) else immOf(ops[1]) ?: return fail("cmp 右")
                    if (mn == "cmp") setFlagsSub(a, b) else setFlagsSub(a, -b)
                    pc = next
                }
                mn.startsWith("ldr") || mn.startsWith("ldur") || mn == "movsxd" || mn == "movsx" || mn == "movzx" -> {
                    val br = d.indexOf('[')
                    if (br < 0) {
                        // ldr xD, =imm / ldr 伪指令 —— 不支持
                        return fail("ldr 非内存形式")
                    }
                    val addr = evalMem(d.substring(br + 1, d.length - 1), ins.offset, ins.size) ?: return fail("ldr 地址")
                    val size = when {
                        mn == "ldrb" -> 1
                        mn == "ldrh" || mn == "ldrsh" -> 2
                        mn == "ldrsw" || mn == "movsxd" || mn == "movsx" -> 4
                        mn == "movzx" -> 2
                        else -> if (Regex("\\bw\\d+\\s*,").containsMatchIn(d)) 4 else 8
                    }
                    val signed = mn == "ldrsw" || mn == "ldrsh" || mn == "movsxd" || mn == "movsx"
                    val v = memLoad(addr, size, signed) ?: return fail("内存读失败 @ 0x" + java.lang.Long.toHexString(addr))
                    write(ops[0], v)
                    pc = next
                }
                mn == "br" -> {
                    if (ops.isEmpty()) return fail("br 无操作数")
                    val t = read(ops[0])
                    return t
                }
                mn == "bx" -> {
                    if (ops.isEmpty()) return fail("bx 无操作数")
                    return read(ops[0]) and -2L
                }
                mn == "ret" -> return fail("ret（非分发器转移）")
                mn == "jmp" -> {
                    val s = d.substringAfter(' ', "").trim()
                    if (s.startsWith("[")) {
                        val addr = evalMem(s.substring(1, s.length - 1), ins.offset, ins.size) ?: return fail("jmp 内存地址")
                        // 索引跳表：目标是 8 字节表项（绝对）或 4 字节相对 —— 先按 8 字节绝对取值，失败再按 4 字节相对
                        memLoad(addr, 8, false)?.let { return it }
                        memLoad(addr, 4, true)?.let { return ins.offset + it }
                        return fail("jmp 表项读取失败")
                    }
                    if (ops.isNotEmpty() && isRegToken(ops[0])) return read(ops[0])
                    immOf(s)?.let { return it }
                    return fail("jmp 目标无法解析")
                }
                mn.startsWith("b.") -> {
                    val cond = mn.substring(2)
                    val t = immOf(ops.lastOrNull() ?: "") ?: return fail("条件分支目标")
                    if (condHolds(cond)) return t
                    pc = next
                }
                mn == "b" -> {
                    val t = immOf(ops.lastOrNull() ?: "") ?: return fail("b 目标")
                    return t
                }
                mn == "cbz" || mn == "cbnz" -> {
                    if (ops.size < 2) return fail("cbz 操作数不足")
                    val v = read(ops[0])
                    val t = immOf(ops[1]) ?: return fail("cbz 目标")
                    val taken = if (mn == "cbz") v == 0L else v != 0L
                    if (taken) return t
                    pc = next
                }
                mn == "tbz" || mn == "tbnz" -> {
                    if (ops.size < 3) return fail("tbz 操作数不足")
                    val v = read(ops[0])
                    val bit = immOf(ops[1]) ?: return fail("tbz 位号")
                    val t = immOf(ops[2]) ?: return fail("tbz 目标")
                    val set = ((v ushr bit.toInt()) and 1L) != 0L
                    val taken = if (mn == "tbz") !set else set
                    if (taken) return t
                    pc = next
                }
                mn.startsWith("j") && mn != "jmp" -> {
                    // x86 条件跳转 jcc
                    val cond = when (mn) {
                        "je" , "jz" -> "eq"
                        "jne", "jnz" -> "ne"
                        "ja" -> "hi"
                        "jae", "jnb", "jnc" -> "hs"
                        "jb", "jnae", "jc" -> "lo"
                        "jbe", "jna" -> "ls"
                        "jg", "jnle" -> "gt"
                        "jge", "jnl" -> "ge"
                        "jl", "jnge" -> "lt"
                        "jle", "jng" -> "le"
                        "js" -> "mi"
                        "jns" -> "pl"
                        else -> null
                    } ?: return fail("未知条件跳转 $mn")
                    val t = lastTargetOf(d) ?: return fail("$mn 目标")
                    if (condHolds(cond)) return t
                    pc = next
                }
                else -> return fail("不支持的指令: $d")
            }
        }
        lastError = "超过步数上限（$maxSteps）"
        return null
    }

    private fun lastTargetOf(d: String): Long? {
        var last: Long? = null
        Regex("(0x[0-9a-fA-F]+)").findAll(d).forEach { m -> m.groupValues[1].substring(2).toLongOrNull(16)?.let { last = it } }
        return last
    }

    private fun fail(msg: String): Long? {
        lastError = msg
        return null
    }
}
