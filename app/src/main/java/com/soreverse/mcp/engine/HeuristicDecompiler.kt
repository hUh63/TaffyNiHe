package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核 · 启发式伪 C 反编译器（纯 Kotlin，对标 Exbin 的 r2dec "纯 Java 移植"引擎）。
 *
 * 与 rizin-ghidra(pdg) / rizin 内置(pdc) 并列的第三套反编译实现：
 * 不依赖任何 native 库，直接吃反汇编指令流，做三件事：
 *   1. 过程重建：识别 prologue 求栈帧大小、识别参数寄存器（x0-x7 / r0-r3）、
 *      把 [sp/fp ± off] 归一成局部变量名；
 *   2. 语句翻译：把 mov/add/ldr/str/cmp/bl/条件跳转 等翻译成 C 语句；
 *   3. 控制流结构化：正向条件跳转恢复成 if 块（在目标地址处闭合），
 *      回跳输出 loop-back 注释 + goto，保证每条指令都有对应输出、不丢逻辑。
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

    /** 把「cmp/tst + 条件跳转」配对还原成真正的 C 比较表达式。 */
    private fun condFromCmp(cond: String, c: CmpCtx): String {
        if (c.kind == "tst") {
            return when (cond) {
                "eq" -> "(${c.a} & ${c.b}) == 0"
                "ne" -> "(${c.a} & ${c.b}) != 0"
                else -> "flag_$cond"
            }
        }
        val signed = when (cond) {
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
        return signed
    }

    /** 分支后缀（b.eq → eq）。 */
    private fun branchSuffix(mnem: String): String? {
        if (!mnem.startsWith("b") || mnem.length < 3 || mnem[1] != '.') return null
        return mnem.substring(2)
    }

    private fun isCondBranch(mnem: String): Boolean =
        mnem in setOf("cbz", "cbnz", "tbz", "tbnz") ||
            (mnem.length > 1 && mnem[0] == 'b' && mnem.substring(1).split('.')[0] in COND)

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

    /** 归一化操作数：寄存器别名、内存表达式。 */
    private fun normOp(op: String, stackVars: MutableMap<String, String>, base: String): String {
        var o = op.trim()
        // 内存访问 [Xn, #off] / [sp, #off]
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
            val name = "v_%x".format(if (offv < 0) -offv else offv)
            if (baseReg == base || baseReg == "fp") stackVars.getOrPut(name) { "/*stack ${if (offv < 0) "-" else "+"}${hex(if (offv < 0) -offv else offv)}*/" }
            val access = if (baseReg == base || baseReg == "fp") name else "*(u64*)($baseReg ${if (offv != 0L) "+ ${hex(offv)}" else ""})".trim()
            return access
        }
        if (o.startsWith("#")) return o.substring(1)
        return when (o.lowercase()) {
            "x29", "fp" -> "fp"
            "x30", "lr" -> "lr"
            "sp", "wsp" -> base
            "xzr", "wzr" -> "0"
            else -> o
        }
    }

    fun decompile(insns: List<Insn>, fnName: String, zh: Boolean): String {
        if (insns.isEmpty()) return ""
        val base = "sp"
        val stackVars = LinkedHashMap<String, String>()

        // 参数寄存器：出现在操作数里、且从未作为写入目标的 x0-x7 / r0-r3
        val read = LinkedHashSet<String>()
        insns.forEach { i ->
            val o = i.ops
            if (o.isBlank()) return@forEach
            val first = o.split(",").firstOrNull()?.trim() ?: return@forEach
            Regex("\\b([xr]\\d{1,2})\\b", RegexOption.IGNORE_CASE).findAll(o).forEach { read.add(it.groupValues[1].lowercase()) }
        }
        val writesX0 = insns.any { i ->
            val f = i.ops.split(",").firstOrNull()?.trim()?.lowercase()
            (f == "x0" || f == "w0") && i.mnem !in setOf("cmp", "cmn", "tst", "str", "strb", "strh", "stur", "stp", "b", "bl")
        }
        val headEnd = maxOf(6, insns.size / 3).coerceAtMost(insns.size)
        val headRead = LinkedHashSet<String>()
        insns.take(headEnd).forEach { i ->
            Regex("\\b([xr]\\d{1,2})\\b", RegexOption.IGNORE_CASE).findAll(i.ops).forEach { headRead.add(it.groupValues[1].lowercase()) }
        }
        val argRegs = headRead.filter { r ->
            val n = r.drop(1).toIntOrNull() ?: 99
            (r.startsWith("x") && n in 0..7) || (r.startsWith("r") && n in 0..3)
        }.sortedBy { it.drop(1).toIntOrNull() ?: 0 }.take(8)
        val argNames = argRegs.mapIndexed { i, r -> r to "a$i" }.toMap()

        fun op(o: String): String {
            val n = normOp(o, stackVars, base)
            return argNames[n.lowercase()] ?: n
        }

        // 分支目标集合（用于标签与 if 闭合）
        val targets = insns.mapNotNull { it.jump }.toSortedSet()
        val openIfs = ArrayDeque<Long>()
        var lastCmp: CmpCtx? = null
        // 简单循环识别：回边（条件跳转目标地址更小）；每个头恰好一条回边才做 do-while 还原
        val headCount = HashMap<Long, Int>()
        insns.forEach { i ->
            val t = i.jump
            if (t != null && t < i.addr && (isCondBranch(i.mnem) || i.mnem == "b")) {
                headCount[t] = (headCount[t] ?: 0) + 1
            }
        }
        val doHeads = headCount.filterValues { it == 1 }.keys.toHashSet()
        val sb = StringBuilder()
        var indent = 1
        fun emit(line: String) = sb.append("    ".repeat(indent)).append(line).append('\n')

        val header = StringBuilder()
        header.append("// ── ").append(if (zh) "塔菲启发式伪 C（taffy-java 引擎）" else "Taffy heuristic pseudo-C (taffy-java engine)").append(" ──\n")
        header.append("// ").append(if (zh) "由 ${insns.size} 条指令重建；非编译器级反编译，仅供理解逻辑" else "rebuilt from ${insns.size} insns; not a compiler-level decompilation").append('\n')
        header.append("// entry ").append(hex(insns.first().addr)).append('\n')

        val body = StringBuilder()
        body.append(if (writesX0) "u64 " else "void ").append(fnName).append("(")
        body.append(argNames.entries.sortedBy { it.value }.joinToString(", ") { "u64 ${it.value} /*${it.key}*/" })
        body.append(") {\n")

        insns.forEach { i ->
            val a = i.addr
            // 闭合已经结束的 if 块
            while (openIfs.isNotEmpty() && openIfs.last() <= a) {
                indent = maxOf(1, indent - 1); emit("}"); openIfs.removeLast()
            }
            if (a in doHeads) {
                emit("do {")
                indent += 1
            } else if (a in targets) emit("label_%x:".format(a))

            val m = i.mnem
            val o = i.ops
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
                    if (t != null && t < a && t in doHeads) {
                        indent = maxOf(1, indent - 1)
                        emit("} while ($c);")
                    } else if (t != null && t < a) {
                        emit("/* loop back -> ${hex(t)} */ goto label_%x;".format(t))
                    } else {
                        emit("if ($c) {")
                        indent += 1
                        if (t != null) openIfs.addLast(t) else indent = maxOf(1, indent - 1)
                    }
                }
                m == "b" && i.jump != null -> {
                    if (i.jump!! < a && i.jump in doHeads) {
                        indent = maxOf(1, indent - 1)
                        emit("} while (1);")
                    } else if (i.jump!! < a) emit("/* loop back */ goto label_%x;".format(i.jump))
                    else emit("goto label_%x;".format(i.jump))
                }
                m == "bl" || m == "blr" -> {
                    val target = o.removePrefix("0x").takeIf { o.startsWith("0x") }?.let { "sub_$it" } ?: o.replace(Regex("[^A-Za-z0-9_]"), "_")
                    val args = argNames.entries.sortedBy { it.value }.joinToString(", ") { it.value }
                    emit("$target($args);  /* call */")
                }
                m == "mov" || m == "movz" || m == "movk" || m == "movn" || m == "mvn" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0) ?: "?"} = ${op(p.getOrNull(1) ?: "?")};")
                }
                m == "add" || m == "adds" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = ${op(p.getOrNull(1) ?: "?")} + ${op(p.getOrNull(2) ?: "?")};")
                }
                m == "sub" || m == "subs" -> {
                    val p = o.split(",").map { it.trim() }
                    // prologue: sub sp, sp, #N → 不进语句（帧调整）
                    if (p.getOrNull(0)?.lowercase() in setOf("sp", "x29")) emit("/* frame adjust: $o */")
                    else emit("${p.getOrNull(0)} = ${op(p.getOrNull(1) ?: "?")} - ${op(p.getOrNull(2) ?: "?")};")
                }
                m == "cmp" || m == "cmn" || m == "tst" -> {
                    val p = o.split(",").map { it.trim() }
                    lastCmp = CmpCtx(m, op(p.getOrNull(0) ?: "?"), op(p.getOrNull(1) ?: "?"))
                    emit("/* $m ${p.joinToString(", ")} */")
                }
                m == "ldr" || m == "ldrb" || m == "ldrh" || m == "ldrsw" || m == "ldur" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = ${op(p.drop(1).joinToString(","))};  /* load */")
                }
                m == "str" || m == "strb" || m == "strh" || m == "stur" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${op(p.drop(1).joinToString(","))} = ${p.getOrNull(0)};  /* store */")
                }
                m == "stp" -> emit("/* push */ $o")
                m == "ldp" -> emit("/* pop */ $o")
                m == "adr" || m == "adrp" -> {
                    val p = o.split(",").map { it.trim() }
                    emit("${p.getOrNull(0)} = &${p.getOrNull(1) ?: "?"};")
                }
                m == "mul" || m == "madd" -> {
                    val p = o.split(",").map { it.trim() }
                    if (p.size >= 3) emit("${p[0]} = ${op(p[1])} * ${op(p[2])};") else emit("/* $o */")
                }
                m == "sdiv" || m == "udiv" -> {
                    val p = o.split(",").map { it.trim() }
                    if (p.size >= 3) emit("${p[0]} = ${op(p[1])} / ${op(p[2])};") else emit("/* $o */")
                }
                m == "and" || m == "orr" || m == "eor" -> {
                    val p = o.split(",").map { it.trim() }
                    val sym = when (m) { "and" -> "&"; "orr" -> "|"; else -> "^" }
                    if (p.size >= 3) emit("${p[0]} = ${op(p[1])} $sym ${op(p[2])};") else emit("/* $o */")
                }
                m == "lsl" || m == "lsr" || m == "asr" -> {
                    val p = o.split(",").map { it.trim() }
                    val sym = if (m == "lsl") "<<" else ">>"
                    if (p.size >= 3) emit("${p[0]} = ${op(p[1])} $sym ${op(p[2])};") else emit("/* $o */")
                }
                m == "csel" -> {
                    val p = o.split(",").map { it.trim() }
                    val cc = lastCmp
                    val cond = if (cc != null) condFromCmp("ne", cc) else "flags"
                    if (p.size >= 3) emit("${p[0]} = ($cond) ? ${op(p[1])} : ${op(p[2])};") else emit("/* $o */")
                }
                m == "cset" -> emit("${o.split(",").firstOrNull()?.trim()} = flags ? 1 : 0;")
                m == "br" -> emit("goto *$o;  /* indirect */")
                else -> emit("/* ${m} ${o} */")
            }
        }
        while (openIfs.isNotEmpty()) { indent = maxOf(1, indent - 1); emit("}"); openIfs.removeLast() }
        if (!insns.any { it.mnem == "ret" }) emit("return;")

        val vars = stackVars.keys.joinToString("") { "    u64 $it;\n" }
        return header.toString() + body.toString() + vars + sb.toString() + "}\n"
    }
}
