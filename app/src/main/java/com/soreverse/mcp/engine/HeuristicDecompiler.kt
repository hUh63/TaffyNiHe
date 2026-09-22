package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核 · 启发式伪 C 反编译器（纯 Kotlin，对标 Exbin 的 r2dec "纯 Java 移植"引擎）。
 *
 * 不依赖任何 native 库，直接吃反汇编指令流，做四件事：
 *   1. 过程重建：prologue 求栈帧、识别整型/浮点参数寄存器、[sp/fp ± off] 归一为局部变量；
 *   2. 类型推断：整数宽度（u8/u16/u32/u64）、指针（被用作内存基址）、浮点（s/d 寄存器）、
 *      结构体字段（同一基址的多个偏移 → field_0xNN）；
 *   3. 语句翻译：算术/逻辑/移位/访存/调用/条件选择 → C 语句；
 *   4. 控制流结构化：正向条件跳转 → if 块；回边 → do-while（单回边）或 while(1)+continue
 *      （多回边）；跳出循环的条件跳转 → break；支持嵌套循环。
 *
 * 输出明确标注为启发式结果，避免与真正的编译器级反编译混淆。
 */
internal object HeuristicDecompiler {

    data class Insn(
        val addr: Long,
        val mnem: String,
        val ops: String,
        val jump: Long?,
        val raw: String,
    )

    private fun num(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> runCatching {
            if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
        }.getOrNull() ?: 0L
        else -> 0L
    }

    private fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    /** 解析 rizin `pdfj` 的 JSON 数组。 */
    fun parse(json: JSONArray): List<Insn> {
        val out = ArrayList<Insn>(json.length())
        for (i in 0 until json.length()) {
            val o = json.optJSONObject(i) ?: continue
            val disasm = o.optString("disasm").ifBlank { o.optString("opcode") }
            if (disasm.isBlank()) continue
            val parts = disasm.trim().split(Regex("\\s+"), limit = 2)
            val mnem = parts[0].lowercase().trimEnd(',')
            if (mnem.isBlank()) continue
            val jump = o.opt("jump")?.let { if (it == JSONObject.NULL) null else num(it) }?.takeIf { it != 0L }
            out.add(Insn(num(o.opt("offset")), mnem, parts.getOrNull(1)?.trim() ?: "", jump, disasm.trim()))
        }
        return out
    }

    private val COND = mapOf(
        "eq" to "==", "ne" to "!=", "gt" to ">", "ge" to ">=", "lt" to "<", "le" to "<=",
        "hi" to ">", "hs" to ">=", "lo" to "<", "ls" to "<=", "cc" to "<", "cs" to ">=",
        "mi" to "<0", "pl" to ">=0", "vs" to "overflow", "vc" to "!overflow",
    )

    private data class CmpCtx(val kind: String, val a: String, val b: String)
    private data class LoopRange(val head: Long, val tail: Long, val backEdges: List<Long>, val doWhile: Boolean)

    private val FLOAT_OPS = setOf(
        "fadd", "fsub", "fmul", "fdiv", "fmadd", "fmsub", "fnmadd", "fnmsub",
        "fneg", "fabs", "fsqrt", "fcmp", "fcsel", "fmov", "fcvt", "scvtf", "ucvtf",
        "fcvtzs", "fcvtzu", "frinta", "frintm", "frintn", "frintp", "frintz", "frinti",
        "vadd", "vsub", "vmul", "vdiv", "vsqrt", "vmov", "vcmp",
    )
    private val INT_OPS = setOf(
        "add", "sub", "adds", "subs", "adc", "sbc", "mul", "madd", "msub", "smull", "umull",
        "sdiv", "udiv", "and", "orr", "eor", "bic", "orn", "lsl", "lsr", "asr", "ror",
        "cmp", "cmn", "tst", "neg", "mvn", "mov", "movz", "movk", "movn", "csel", "cset",
        "clz", "rbit", "rev", "ubfx", "sbfx", "ubfiz", "sbfiz", "bfi", "bfxil", "extr",
    )

    private fun isCondBranch(mnem: String): Boolean =
        mnem in setOf("cbz", "cbnz", "tbz", "tbnz") ||
            (mnem.length > 1 && mnem[0] == 'b' && mnem.substring(1).split('.')[0] in COND)

    private fun branchSuffix(mnem: String): String? {
        if (!mnem.startsWith("b") || mnem.length < 3 || mnem[1] != '.') return null
        return mnem.substring(2)
    }

    private fun condOf(mnem: String, ops: String): String? {
        if (mnem == "cbz" || mnem == "cbnz") {
            val r = ops.split(",").firstOrNull()?.trim() ?: return null
            return "$r ${if (mnem == "cbz") "==" else "!="} 0"
        }
        if (mnem == "tbz" || mnem == "tbnz") {
            val a = ops.split(",").map { it.trim() }
            val r = a.getOrNull(0) ?: return null
            val bit = (a.getOrNull(1) ?: "0").removePrefix("#")
            return "(($r >> $bit) & 1) ${if (mnem == "tbz") "==" else "!="} 0"
        }
        val suffix = mnem.substring(1).split('.').firstOrNull() ?: return null
        val op = COND[suffix] ?: return null
        return "flags $op"
    }

    /** 把「cmp/tst + 条件跳转」配对还原成真正的 C 比较表达式。 */
    private fun condFromCmp(cond: String, c: CmpCtx): String {
        if (c.kind == "tst") {
            return when (cond) {
                "eq" -> "(${c.a} & ${c.b}) == 0"
                "ne" -> "(${c.a} & ${c.b}) != 0"
                else -> "flag_$cond"
            }
        }
        return when (cond) {
            "eq" -> "${c.a} == ${c.b}"
            "ne" -> "${c.a} != ${c.b}"
            "gt" -> "${c.a} > ${c.b}"
            "ge" -> "${c.a} >= ${c.b}"
            "lt" -> "${c.a} < ${c.b}"
            "le" -> "${c.a} <= ${c.b}"
            "hi" -> "(u32)${c.a} > (u32)${c.b}"
            "hs", "cs" -> "(u32)${c.a} >= (u32)${c.b}"
            "lo", "cc" -> "(u32)${c.a} < (u32)${c.b}"
            "ls" -> "(u32)${c.a} <= (u32)${c.b}"
            "mi" -> "(${c.a} - ${c.b}) < 0"
            "pl" -> "(${c.a} - ${c.b}) >= 0"
            "vs" -> "(overflow)"
            "vc" -> "(!overflow)"
            else -> "flag_$cond"
        }
    }

    // ── 类型推断 ────────────────────────────────────────────────

    private fun isFloatReg(r: String): Boolean =
        r.length >= 2 && r[0] in "sdqv" && r.drop(1).all { it.isDigit() }

    private fun isIntReg(r: String): Boolean =
        r.length >= 2 && r[0] in "wxr" && r.drop(1).all { it.isDigit() }

    private fun regIndex(r: String): Int = r.drop(1).toIntOrNull() ?: 99

    /**
     * 推断寄存器用途类型：
     *   void*   被当作内存基址（[reg, ...]）
     *   float/double  出现在浮点指令的 s/d 寄存器
     *   u8/u16/u32/u64 (或 s8/s16/s32)  由访存宽度与算术宽度决定
     */
    private fun inferRegTypes(insns: List<Insn>): Map<String, String> {
        val t = HashMap<String, String>()
        fun bump(reg: String, ty: String) {
            val r = reg.lowercase()
            if (!isIntReg(r) && !isFloatReg(r)) return
            val cur = t[r]
            // 优先级：float > ptr > 更窄的整数（保留最具体）
            val rank = mapOf(
                "double" to 5, "float" to 5, "void*" to 4,
                "u8" to 3, "s8" to 3, "u16" to 3, "s16" to 3,
                "u32" to 2, "s32" to 2, "u64" to 1, "s64" to 1,
            )
            if (cur == null || (rank[ty] ?: 0) > (rank[cur] ?: 0)) t[r] = ty
        }
        insns.forEach { i ->
            val o = i.ops
            val m = i.mnem
            // 1) 浮点指令中的寄存器
            if (m in FLOAT_OPS) {
                Regex("\\b[sdqvh]\\d{1,2}\\b", RegexOption.IGNORE_CASE).findAll(o).forEach {
                    bump(it.groupValues[0], if (it.groupValues[0][0].lowercaseChar() == 'd') "double" else "float")
                }
            }
            // 2) 内存基址 → 指针
            Regex("\\[\\s*([a-z]\\d{1,2})", RegexOption.IGNORE_CASE).findAll(o).forEach {
                bump(it.groupValues[1], "void*")
            }
            // 3) 访存宽度
            Regex("\\b([a-z]\\d{1,2})\\b", RegexOption.IGNORE_CASE).findAll(o).forEach {
                val r = it.groupValues[1]
                when (m) {
                    "ldrb", "strb", "ldurb", "sturb" -> bump(r, "u8")
                    "ldrsb" -> bump(r, "s8")
                    "ldrh", "strh", "ldurh", "sturh" -> bump(r, "u16")
                    "ldrsh" -> bump(r, "s16")
                    "ldrsw" -> bump(r, "s32")
                    "ldr", "str", "ldur", "stur" -> bump(r, if (r.startsWith("w")) "u32" else "u64")
                    else -> if (m in INT_OPS) bump(r, if (r.startsWith("w")) "u32" else "u64")
                }
            }
            // 4) 浮点加载（ldr s0, ... / ldr d0, ...）
            if (m in setOf("ldr", "str", "ldur", "stur")) {
                Regex("\\b([sd])\\d{1,2}\\b", RegexOption.IGNORE_CASE).findAll(o).forEach {
                    bump(it.groupValues[0], if (it.groupValues[0][0].lowercaseChar() == 'd') "double" else "float")
                }
            }
        }
        return t
    }

    /** 收集「基址寄存器 → 偏移集合」，用于结构体字段命名。 */
    private fun inferStructFields(insns: List<Insn>): Map<String, Set<Long>> {
        val out = HashMap<String, MutableSet<Long>>()
        insns.forEach { i ->
            Regex("\\[\\s*([a-z]\\d{1,2})\\s*,\\s*#?(-?0x[0-9a-f]+|-?\\d+)", RegexOption.IGNORE_CASE).findAll(i.ops).forEach { m ->
                val reg = m.groupValues[1].lowercase()
                val off = runCatching {
                    val s = m.groupValues[2]
                    if (s.startsWith("-")) -s.substring(1).toLong(16) else s.toLong(16)
                }.getOrNull() ?: 0L
                out.getOrPut(reg) { linkedSetOf() }.add(off)
            }
        }
        return out
    }

    // ── 循环分析 ────────────────────────────────────────────────

    /** 分析循环区间：按回边目标分组，取区间尾为回边最大地址；单回边且在最末 → do-while。 */
    private fun analyzeLoops(insns: List<Insn>): Map<Long, LoopRange> {
        val byHead = LinkedHashMap<Long, MutableList<Long>>()
        insns.forEach { i ->
            val t = i.jump
            if (t != null && t < i.addr && (isCondBranch(i.mnem) || i.mnem == "b")) {
                byHead.getOrPut(t) { mutableListOf() }.add(i.addr)
            }
        }
        val out = LinkedHashMap<Long, LoopRange>()
        byHead.forEach { (h, backs) ->
            val sorted = backs.sorted()
            val tail = sorted.last()
            val doWhile = sorted.size == 1
            // 嵌套时外层 tail 需要收缩：取「不被内层循环包含」的最大回边地址
            val headIdx = insns.indexOfFirst { it.addr == h }
            val innerHeads = byHead.keys.filter { it > h && it <= tail }
            val outerTail = if (innerHeads.isEmpty()) tail else {
                val innerMax = innerHeads.maxOf { hh -> byHead[hh]!!.max() }
                val outerBacks = sorted.filter { b ->
                    insns.indexOfFirst { it.addr == b } > headIdx &&
                        innerHeads.none { hh -> hh < b && b <= innerMax }
                }
                outerBacks.maxOrNull() ?: tail
            }
            out[h] = LoopRange(h, outerTail, sorted, doWhile)
        }
        return out
    }

    // ── 主流程 ──────────────────────────────────────────────────

    fun decompile(insns: List<Insn>, fnName: String, zh: Boolean): String {
        if (insns.isEmpty()) return ""
        val base = "sp"
        val stackVars = LinkedHashMap<String, String>()

        val regTypes = inferRegTypes(insns)
        val structFields = inferStructFields(insns)

        // 参数寄存器：入口附近被读的 x0-x7 / r0-r3（含浮点 d0-d7/s0-s7）
        val headEnd = maxOf(6, insns.size / 3).coerceAtMost(insns.size)
        val headRead = LinkedHashSet<String>()
        insns.take(headEnd).forEach { i ->
            Regex("\\b([wxrsdq]\\d{1,2})\\b", RegexOption.IGNORE_CASE).findAll(i.ops).forEach { headRead.add(it.groupValues[1].lowercase()) }
        }
        val intArgs = headRead.filter { r ->
            val n = regIndex(r)
            (r.startsWith("x") && n in 0..7) || (r.startsWith("r") && n in 0..3)
        }.sortedBy { regIndex(it) }.take(8)
        val floatArgs = headRead.filter { r ->
            val n = regIndex(r)
            isFloatReg(r) && n in 0..7
        }.sortedBy { regIndex(it) }.take(8)
        val argRegs = intArgs + floatArgs
        val argNames = argRegs.mapIndexed { i, r -> r to "a$i" }.toMap()

        fun typeOf(reg: String): String = regTypes[reg.lowercase()] ?: if (reg.startsWith("w")) "u32" else "u64"

        val loops = analyzeLoops(insns)
        val targets = insns.mapNotNull { it.jump }.toSortedSet()

        fun normOp(op: String): String {
            var o = op.trim()
            if (o.startsWith("[")) {
                val inner = o.trimStart('[').trimEnd(']', '!').trim()
                val parts = inner.split(",").map { it.trim().replace("#", "") }.filter { it.isNotEmpty() }
                val reg = parts.getOrNull(0) ?: "?"
                val off = parts.getOrNull(1)
                val baseReg = when (reg.lowercase()) {
                    "sp", "wsp" -> base
                    "x29", "fp" -> "fp"
                    else -> reg
                }
                val offv = off?.let { runCatching { if (it.startsWith("0x")) it.substring(2).toLong(16) else it.toLong() }.getOrNull() } ?: 0L
                if (baseReg == base || baseReg == "fp") {
                    val abs = if (offv < 0) -offv else offv
                    val name = "v_%x".format(abs)
                    stackVars.getOrPut(name) { "/*stack ${if (offv < 0) "-" else "+"}${hex(abs)}*/" }
                    return name
                }
                // 结构体字段：同一基址有多个偏移 → base->field_0xNN
                val fields = structFields[baseReg.lowercase()] ?: emptySet()
                val useField = fields.size >= 2
                val b = argNames[baseReg.lowercase()] ?: baseReg
                return if (useField) {
                    "$b->field_%x".format(offv)
                } else {
                    "*(u64*)($b" + (if (offv != 0L) " + " + hex(offv) else "") + ")"
                }
            }
            if (o.startsWith("#")) return o.substring(1)
            return when (o.lowercase()) {
                "x29", "fp" -> "fp"
                "x30", "lr" -> "lr"
                "sp", "wsp" -> base
                "xzr", "wzr" -> "0"
                else -> argNames[o.lowercase()] ?: o
            }
        }

        val sb = StringBuilder()
        var indent = 1
        fun emit(line: String) = sb.append("    ".repeat(indent)).append(line).append('\n')

        val writesX0 = insns.any { i ->
            val f = i.ops.split(",").firstOrNull()?.trim()?.lowercase()
            (f == "x0" || f == "w0") && i.mnem !in setOf("cmp", "cmn", "tst", "str", "strb", "strh", "stur", "stp", "b", "bl")
        }
        val writesF0 = insns.any { i ->
            val f = i.ops.split(",").firstOrNull()?.trim()?.lowercase()
            f == "d0" || f == "s0"
        }

        val header = StringBuilder()
        header.append("// ── ").append(if (zh) "塔菲启发式伪 C（taffy-java 引擎）" else "Taffy heuristic pseudo-C").append(" ──\n")
        header.append("// ").append(if (zh) "由 ${insns.size} 条指令重建；非编译器级反编译，仅供理解逻辑" else "rebuilt from ${insns.size} insns; heuristic").append('\n')
        if (regTypes.isNotEmpty()) {
            header.append("// ").append(if (zh) "推断类型：" else "inferred types: ")
                .append(argRegs.joinToString(", ") { "${argNames[it]}($it:${typeOf(it)})" }).append('\n')
        }
        header.append("// entry ").append(hex(insns.first().addr)).append('\n')

        val body = StringBuilder()
        val retTy = when {
            writesF0 -> if (insns.any { i -> i.ops.split(",").firstOrNull()?.trim()?.lowercase() == "d0" }) "double" else "float"
            writesX0 -> "u64"
            else -> "void"
        }
        body.append(retTy).append(' ').append(fnName).append("(")
        body.append(argRegs.mapIndexed { i, r ->
            val ty = when {
                isFloatReg(r) -> if (typeOf(r) == "double") "double" else "float"
                else -> typeOf(r)
            }
            "$ty a$i /*$r*/"
        }.joinToString(", "))
        body.append(") {\n")

        val openIfs = ArrayDeque<Long>()
        val loopStack = ArrayDeque<LoopRange>()
        var lastCmp: CmpCtx? = null

        fun closeIf() { indent = maxOf(1, indent - 1); emit("}"); openIfs.removeLast() }
        fun closeLoop() { indent = maxOf(1, indent - 1); emit("}"); loopStack.removeLast() }

        insns.forEach { i ->
            val a = i.addr
            // 先闭合已结束的 if / loop（靠内的先闭）
            var guard = 0
            while (guard++ < 64) {
                val ifT = openIfs.lastOrNull()
                val lp = loopStack.lastOrNull()
                if (ifT != null && ifT <= a && (lp == null || ifT <= lp.tail)) { closeIf(); continue }
                if (lp != null && lp.tail < a) { closeLoop(); continue }
                break
            }

            // 进入新的循环头
            val entered = loops[a]
            if (entered != null) {
                emit(if (entered.doWhile) "do {" else "while (1) {")
                loopStack.addLast(entered)
                indent += 1
            } else if (a in targets) {
                emit("label_%x:".format(a))
            }

            val m = i.mnem
            val o = i.ops
            val curLoop = loopStack.lastOrNull()
            when {
                m == "ret" -> emit("return;")
                m == "nop" -> emit("/* nop */")
                isCondBranch(m) -> {
                    val suf = branchSuffix(m)
                    val cc = lastCmp
                    val c = when {
                        suf != null && cc != null -> condFromCmp(suf, cc)
                        else -> condOf(m, o) ?: "cond"
                    }
                    if (suf != null) lastCmp = null
                    val t = i.jump
                    when {
                        t == null -> emit("/* if ($c) -> unknown target */")
                        t < a && t in loops -> {
                            // 回边
                            val lp = loops[t]!!
                            if (lp.doWhile && loopStack.lastOrNull()?.head == t) {
                                indent = maxOf(1, indent - 1)
                                loopStack.removeLast()
                                emit("} while ($c);")
                            } else {
                                emit("if ($c) continue;   /* -> ${hex(t)} */")
                            }
                        }
                        curLoop != null && t > curLoop.tail -> emit("if ($c) break;   /* -> ${hex(t)} */")
                        else -> {
                            emit("if ($c) {")
                            indent += 1
                            openIfs.addLast(t)
                        }
                    }
                }
                m == "b" && i.jump != null -> {
                    val t = i.jump!!
                    if (t < a) {
                        if (loopStack.lastOrNull()?.head == t) {
                            val lp = loopStack.last()
                            if (lp.doWhile) {
                                indent = maxOf(1, indent - 1)
                                loopStack.removeLast()
                                emit("} while (1);")
                            } else emit("/* loop */ continue;")
                        } else emit("/* loop back */ goto label_%x;".format(t))
                    } else emit("goto label_%x;".format(t))
                }
                m == "bl" || m == "blr" -> {
                    val target = if (o.startsWith("0x")) "sub_" + o.removePrefix("0x") else o.replace(Regex("[^A-Za-z0-9_]"), "_")
                    val args = argRegs.mapIndexed { idx, _ -> "a$idx" }.joinToString(", ")
                    emit("$target($args);  /* call */")
                }
                m == "mov" || m == "movz" || m == "movk" || m == "movn" || m == "mvn" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = ${normOp(p.getOrNull(1) ?: "?")};")
                }
                m == "add" || m == "adds" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = ${normOp(p.getOrNull(1) ?: "?")} + ${normOp(p.getOrNull(2) ?: "?")};")
                }
                m == "sub" || m == "subs" -> {
                    val p = o.split(",").map { it.trim() }
                    if (p.getOrNull(0)?.lowercase() in setOf("sp", "x29")) emit("/* frame adjust: $o */")
                    else emit("${p.getOrNull(0)} = ${normOp(p.getOrNull(1) ?: "?")} - ${normOp(p.getOrNull(2) ?: "?")};")
                }
                m == "cmp" || m == "cmn" || m == "tst" -> {
                    val p = o.split(",").map { it.trim() }
                    lastCmp = CmpCtx(m, normOp(p.getOrNull(0) ?: "?"), normOp(p.getOrNull(1) ?: "?"))
                    emit("/* $m ${p.joinToString(", ")} */")
                }
                m in setOf("ldr", "ldrb", "ldrh", "ldrsw", "ldur", "ldrsb", "ldrsh") -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = ${normOp(p.drop(1).joinToString(","))};  /* load */")
                }
                m in setOf("str", "strb", "strh", "stur") -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${normOp(p.drop(1).joinToString(","))} = ${p.getOrNull(0)};  /* store */")
                }
                m == "stp" -> emit("/* push */ $o")
                m == "ldp" -> emit("/* pop */ $o")
                m == "adr" || m == "adrp" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = &${p.getOrNull(1) ?: "?"};")
                }
                m in FLOAT_OPS -> {
                    val p = o.split(",").map { it.trim() }
                    val expr = when (m) {
                        "fadd" -> "${normOp(p.getOrNull(1) ?: "?")} + ${normOp(p.getOrNull(2) ?: "?")}"
                        "fsub" -> "${normOp(p.getOrNull(1) ?: "?")} - ${normOp(p.getOrNull(2) ?: "?")}"
                        "fmul" -> "${normOp(p.getOrNull(1) ?: "?")} * ${normOp(p.getOrNull(2) ?: "?")}"
                        "fdiv" -> "${normOp(p.getOrNull(1) ?: "?")} / ${normOp(p.getOrNull(2) ?: "?")}"
                        "fneg" -> "-${normOp(p.getOrNull(1) ?: "?")}"
                        "fabs" -> "fabs(${normOp(p.getOrNull(1) ?: "?")})"
                        "fsqrt" -> "sqrt(${normOp(p.getOrNull(1) ?: "?")})"
                        else -> null
                    }
                    if (expr != null && p.size >= 2) emit("${p[0]} = $expr;")
                    else emit("/* $m $o */")
                }
                m == "mul" || m == "madd" -> {
                    val p = o.split(",").map { it.trim() }
                    if (p.size >= 3) emit("${p[0]} = ${normOp(p[1])} * ${normOp(p[2])};") else emit("/* $o */")
                }
                m == "sdiv" || m == "udiv" -> {
                    val p = o.split(",").map { it.trim() }
                    if (p.size >= 3) emit("${p[0]} = ${normOp(p[1])} / ${normOp(p[2])};") else emit("/* $o */")
                }
                m == "and" || m == "orr" || m == "eor" -> {
                    val p = o.split(",").map { it.trim() }
                    val sym = when (m) { "and" -> "&"; "orr" -> "|"; else -> "^" }
                    if (p.size >= 3) emit("${p[0]} = ${normOp(p[1])} $sym ${normOp(p[2])};") else emit("/* $o */")
                }
                m == "lsl" || m == "lsr" || m == "asr" -> {
                    val p = o.split(",").map { it.trim() }
                    val sym = if (m == "lsl") "<<" else ">>"
                    if (p.size >= 3) emit("${p[0]} = ${normOp(p[1])} $sym ${normOp(p[2])};") else emit("/* $o */")
                }
                m == "csel" -> {
                    val p = o.split(",").map { it.trim() }
                    val cc = lastCmp
                    val cond = if (cc != null) condFromCmp("ne", cc) else "flags"
                    if (p.size >= 3) emit("${p[0]} = ($cond) ? ${normOp(p[1])} : ${normOp(p[2])};") else emit("/* $o */")
                }
                m == "cset" -> emit("${o.split(",").firstOrNull()?.trim()} = flags ? 1 : 0;")
                m == "br" -> emit("goto *$o;  /* indirect */")
                else -> emit("/* ${m} ${o} */")
            }
        }
        var g2 = 0
        while ((openIfs.isNotEmpty() || loopStack.isNotEmpty()) && g2++ < 64) {
            if (openIfs.isNotEmpty()) closeIf() else closeLoop()
        }
        if (!insns.any { it.mnem == "ret" }) emit("return;")

        val argSet = argNames.keys
        val vars = stackVars.entries.filter { it.key !in argSet }.joinToString("") { (k, v) ->
            "    u64 $k;  $v\n"
        }
        return header.toString() + body.toString() + vars + sb.toString() + "}\n"
    }
}
