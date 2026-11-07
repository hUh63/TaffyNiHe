package com.soreverse.mcp

/**
 * 从线性反汇编重建控制流图，并尽力解析 AArch64 的寄存器间接跳转。
 *
 * 为什么需要重建：rizin 有时把整段代码识别成**一个基本块**（函数边界没切、或 `af` 只做了线性扫描），
 * 于是 `agfj/rzCfg` 只返回 1 个块 —— 界面上就是"控制流图形化只有一个框"，
 * 而节点列表里那一个块却挂着上千条指令。
 *
 * 切块规则：
 *   · 块起点 = 函数入口 + 所有跳转目标 + 所有跳转指令的下一条
 *   · 块尾 = 跳转/返回指令
 *   · 边 = 顺序边（块尾不是无条件跳转/返回时）+ 跳转边
 * 跳转目标必须落在函数自己的指令地址集合里（跳出去的一律当外部调用，不建边），
 * 这样不会把相邻函数误并联进来。
 *
 * `br xN` 静态看不出目标，按三种编译器产物分别处理：
 *   1. **跳表**（switch）：严格匹配 `adrp`+`add`+`ldrsw`+`add`+`br` 五连，读表反推，见 [resolveJumpTable]
 *   2. **槽表尾跳**（PLT / 函数指针表）：`ldr xN, [xBase, #off]` + `br xN`，
 *      xBase 来自 `adrp`+`add`（常量地址）→ 该槽是 GOT/.data.rel.ro 项。
 *      把槽地址交给 [SlotHit] 解析器就能拿到**符号名**（`memcpy` / `__cxa_finalize` / 本地函数地址），
 *      见 [resolveIndirectTail]。实测 libpython3.12.so 的 1600 条 `br` 里 1353 条走这条路。
 *   3. **对象槽尾跳**（`ldr x1, [x9]` 其中 x9 来自对象字段）：静态无唯一解，
 *      但能把字段偏移标出来（`[obj+0x68]`），让用户知道"尾跳到哪个 slot"。
 */
internal object AnalysisCfgRebuild {

    /** 数据槽（GOT / .data.rel.ro / 跳表）解析结果。 */
    class SlotHit(val label: String, val targets: List<Long>)

    private val HEX = Regex("0x[0-9a-fA-F]+")

    /** AArch64 / ARM / x86 都适用的无条件跳转与条件跳转助记符。 */
    private val UNCOND = setOf("b", "br", "bx", "jmp", "j")
    private val COND_PREFIX = listOf(
        "cbz", "cbnz", "tbz", "tbnz", "b.", "beq", "bne", "bcs", "bcc", "blt", "bgt", "ble", "bge",
        "bmi", "bpl", "bvs", "bvc", "bhi", "bls", "jz", "jnz", "je", "jne", "ja", "jb", "jg", "jl",
    )
    private val RET_PREFIX = listOf("ret", "bx lr", "leave")

    private val BR_X = Regex("""\bbr\s+(x\d+)""")
    private val ADRP = Regex("""adrp\s+(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|[0-9]+)""")
    private val ADD_REG_IMM = Regex("""add\s+(x\d+)\s*,\s*(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    private val ADD_REG_REG_REG = Regex("""add\s+(x\d+)\s*,\s*(x\d+)\s*,\s*(x\d+)""")
    private val CMP_IMM = Regex("""cmp\s+w\d+\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    /** 形态 A 的取表项：`ldrsw x9, [x8, w0, uxtw #2]`。 */
    private val LDR_SIGNED_INDEXED = Regex("""ldrs[whb]\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,[^\]]*\]""")
    /** 形态 B 的取表项：`ldr x9, [x8, x0, lsl #3]`（8 字节绝对地址表）。 */
    private val LDR_PTR_INDEXED = Regex("""ldr\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,[^\]]*lsl\s*#?\s*3[^\]]*\]""")

    // ── 数据流溯源用的形态 ──
    /** 目标寄存器（第一条操作数是 xN）。 */
    private val FIRST_X = Regex("""^[a-z][a-z0-9.]*\s+(x\d+)""")
    private val MOV_RR = Regex("""^mov\s+(x\d+)\s*,\s*(x\d+)\s*$""")
    private val ORR_RR = Regex("""^orr\s+(x\d+)\s*,\s*xzr\s*,\s*(x\d+)""")
    /** `ldr/ldur/ldrsb/... xD, [xBase, #disp]`（无索引）。 */
    private val LD_DISP = Regex("""^ld(?:ur|rsw|rsh|rb|rh|r)?\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*(?:,\s*#?(0x[0-9a-fA-F]+|\d+))?\s*\]""")
    /** `ldr xD, [xBase, xIdx, lsl #3]`（索引取表）。 */
    private val LD_INDEXED = Regex("""^ldr\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,\s*x\d+\s*,\s*lsl\s*#?\s*(2|3)[^\]]*\]""")
    private val SUB_INST = Regex("""^sub\s+(x\d+)\s*,\s*(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")

    /** 这些助记符的首个寄存器操作数**不是**写（读/比较/存）。 */
    private val NO_WRITE = setOf(
        "str", "stur", "stp", "st1", "st2", "st3", "st4", "cmp", "cmn", "tst",
        "cbz", "cbnz", "tbz", "tbnz", "b", "br", "blr", "bl", "blx", "ret",
        "nop", "bti", "prfm", "dmb", "dsb", "isb",
    )
    private val BOUNDARY = setOf("b", "br", "blr", "bl", "blx", "ret", "cbz", "cbnz", "tbz", "tbnz")

    private data class Insn(
        val addr: Long,
        val text: String,
        val mnem: String,
        val targets: List<Long>,
        val isCond: Boolean,
        val isUncond: Boolean,
        val isRet: Boolean,
    )

    /** 跳表反推结果。 */
    private class JumpTable(val targets: List<Long>, val itemWidth: Int)

    /** 寄存器溯源结果。 */
    private sealed class Traced {
        class Const(val v: Long) : Traced()
        class Slot(val addr: Long, val scale: Int) : Traced()
        class ObjField(val off: Long) : Traced()
    }

    private fun hex(v: String): Long? =
        if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2).toLongOrNull(16) else v.toLongOrNull()

    /**
     * @param funcName 函数名（进 CfgGraph）
     * @param funcVa 函数入口地址文本（进 CfgGraph）
     * @param linear `pdfj` 逐指令结果：地址 → 反汇编文本（按地址升序）
     * @param density 保留参数（历史兼容，当前布局不吃这个值）
     * @param memReader 读内存（地址, 长度）→ 字节；给跳表解析用
     * @param slotReader 解析数据槽（槽地址, 项宽）→ [SlotHit]；给间接尾跳符号化用
     * @return 重建结果；无法重建时返回 null，调用方保留原图
     */
    @Suppress("UNUSED_PARAMETER")
    fun rebuild(
        funcName: String,
        funcVa: String,
        linear: List<Pair<Long, String>>,
        density: Float = 1f,
        memReader: ((Long, Int) -> ByteArray?)? = null,
        slotReader: ((Long, Int) -> SlotHit?)? = null,
    ): CfgGraph? {
        if (linear.size < 4) return null
        val sorted = linear.sortedBy { it.first }
        val addrs = sorted.map { it.first }
        val addrSet = addrs.toHashSet()

        val infos = sorted.map { (addr, text) ->
            val body = text.trim()
            val mnem = body.substringBefore(' ').trim().lowercase()
            val targets = HEX.findAll(body).mapNotNull { m ->
                m.value.removePrefix("0x").toLongOrNull(16)
            }.filter { it != addr && it in addrSet }.toList()
            val isRet = RET_PREFIX.any { mnem.startsWith(it) } || mnem == "ret"
            val isUncond = (mnem in UNCOND || mnem == "b") && !isRet
            val isCond = !isUncond && !isRet && COND_PREFIX.any { mnem.startsWith(it) } && targets.isNotEmpty()
            Insn(addr, body, mnem, if (isCond || isUncond) targets else emptyList(), isCond, isUncond, isRet)
        }.toMutableList()

        // br xN：① 严格匹配跳表链 ② 槽表尾跳符号化 ③ 对象槽标字段偏移
        val tailLabels = HashMap<Int, String>()
        for (i in infos.indices) {
            val inf = infos[i]
            if (inf.mnem != "br" || inf.targets.isNotEmpty() || !BR_X.containsMatchIn(inf.text)) continue

            val jt = memReader?.let { resolveJumpTable(infos, i, it) }
            if (jt != null) {
                val valid = jt.targets.filter { it in addrSet }
                if (valid.isNotEmpty()) {
                    infos[i] = inf.copy(targets = valid)
                    continue
                }
            }
            val hit = slotReader?.let { resolveIndirectTail(infos, i, it) } ?: continue
            tailLabels[i] = hit.label
            val inner = hit.targets.filter { it in addrSet }
            if (inner.isNotEmpty()) infos[i] = inf.copy(targets = inner)
        }

        // 块起点
        val starts = sortedSetOf(addrs.first())
        infos.forEachIndexed { i, inf ->
            if (inf.isCond || inf.isUncond) inf.targets.forEach { starts.add(it) }
            if ((inf.isCond || inf.isUncond) && i + 1 < addrs.size) starts.add(addrs[i + 1])
        }
        val blocks = starts.filter { it in addrSet }.toList()
        if (blocks.size < 2) return null   // 切不出多块，别硬造

        val indexOf = blocks.withIndex().associate { it.value to it.index }

        fun owner(addr: Long): Int {
            var lo = 0
            var hi = blocks.size - 1
            var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (blocks[mid] <= addr) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }

        val lines = Array(blocks.size) { mutableListOf<String>() }
        infos.forEach { lines[owner(it.addr)].add(it.text) }

        // 块尾：决定出边，也决定摘要里要不要标"尾跳 → 目标"
        val infoByAddr = infos.associateBy { it.addr }
        val tailIdx = blocks.mapIndexed { bi, start ->
            val lastAddr = lines[bi].lastOrNull()?.let { t ->
                infos.lastOrNull { it.text == t && it.addr >= start }?.addr
            } ?: start
            infos.indexOfFirst { it.addr == lastAddr }
        }
        val tails = tailIdx.map { if (it >= 0) infos[it] else infoByAddr[blocks[0]] }

        val edges = LinkedHashSet<Triple<Int, Int, String>>()
        blocks.forEachIndexed { bi, _ ->
            val tail = tails.getOrNull(bi) ?: return@forEachIndexed
            if (tail.isRet) return@forEachIndexed
            tail.targets.forEach { t ->
                val ti = indexOf[t] ?: return@forEach
                edges.add(Triple(bi, ti, if (tail.isCond) "false" else "jump"))
            }
            if (!tail.isUncond && !tail.isRet && bi + 1 < blocks.size) {
                edges.add(Triple(bi, bi + 1, if (tail.isCond) "true" else "next"))
            }
        }
        if (edges.isEmpty()) return null

        val cfgBlocks = blocks.mapIndexed { i, a ->
            val tail = tails.getOrNull(i)
            val note = if (tail != null && tail.mnem == "br" && tail.targets.isEmpty()) {
                val lbl = tailLabels[tailIdx[i]]
                if (lbl != null) " · 尾跳 → " + lbl else " · " + tail.text.take(24) + " 间接跳转"
            } else ""
            CfgBlock(
                index = i,
                addrText = "0x" + java.lang.Long.toHexString(a),
                addrValue = a,
                // 块上界 = 下一块起点：mergeLinearInsns 靠它把线性指令归到正确的块
                endText = if (i + 1 < blocks.size) "0x" + java.lang.Long.toHexString(blocks[i + 1]) else "",
                summary = lines[i].firstOrNull().orEmpty().take(maxOf(0, 80 - note.length)) + note,
            )
        }
        val cfgEdges = edges.map { CfgEdge(it.first, it.second, it.third) }
        return CfgGraph(funcName, funcVa, cfgBlocks, cfgEdges)
    }

    // ───────────────────────── br xN 解析 ─────────────────────────

    /**
     * 尾跳符号化：从 `br xN` 往前溯源 xN 的来源。
     *
     * | 形态 | 例子 | 结果 |
     * |---|---|---|
     * | 常量槽 | `adrp x8,got; ldr x16,[x8,#0x10]; br x16` | → `SlotHit("memcpy", [])`（重定位符号名） |
     * | 常量表 | `adrp x8,tbl; ldr x9,[x8,x0,lsl #3]; br x9` | → `SlotHit("N 项", [目标...])` |
     * | 对象字段 | `ldr x8,[x0,#8]; ldr x1,[x8,#0x68]; br x1` | → `SlotHit("[obj+0x68]", [])` |
     * | 直接地址 | `adrp x9,f; add x9,x9,#off; br x9` | → `SlotHit("0x...", [地址])` |
     */
    private fun resolveIndirectTail(
        infos: List<Insn>,
        brIndex: Int,
        slotReader: (Long, Int) -> SlotHit?,
    ): SlotHit? {
        val dst = BR_X.find(infos[brIndex].text)?.groupValues?.get(1) ?: return null
        return when (val t = traceReg(infos, brIndex, dst, 4)) {
            is Traced.Slot -> slotReader(t.addr, t.scale)
            is Traced.ObjField -> SlotHit("[obj+0x" + java.lang.Long.toHexString(t.off) + "]", emptyList())
            is Traced.Const -> SlotHit("0x" + java.lang.Long.toHexString(t.v), listOf(t.v))
            null -> null
        }
    }

    /** 近似数据流：往前找最后一次写 [reg] 的指令，回溯它的来源（不含跨基本块的 phi）。 */
    private fun traceReg(infos: List<Insn>, fromIndex: Int, reg: String, depth: Int): Traced? {
        if (depth <= 0) return null
        var k = fromIndex - 1
        var steps = 0
        while (k >= 0 && steps < 40) {
            val ins = infos[k]
            if (!writesReg(ins, reg)) {
                if (ins.mnem in BOUNDARY || ins.mnem.startsWith("b.")) return null
                k--; steps++
                continue
            }
            val t = ins.text
            ADRP.find(t)?.let { m ->
                if (m.groupValues[1] == reg) return hex(m.groupValues[2])?.let { Traced.Const(it) }
            }
            ADD_REG_IMM.find(t)?.let { m ->
                if (m.groupValues[1] == reg) {
                    val base = traceReg(infos, k, m.groupValues[2], depth - 1) as? Traced.Const
                    val imm = hex(m.groupValues[3]) ?: 0L
                    return base?.let { Traced.Const(it.v + imm) }
                }
            }
            SUB_INST.find(t)?.let { m ->
                if (m.groupValues[1] == reg) {
                    val base = traceReg(infos, k, m.groupValues[2], depth - 1) as? Traced.Const
                    val imm = hex(m.groupValues[3]) ?: 0L
                    return base?.let { Traced.Const(it.v - imm) }
                }
            }
            MOV_RR.find(t)?.let { m ->
                if (m.groupValues[1] == reg) return traceReg(infos, k, m.groupValues[2], depth - 1)
            }
            ORR_RR.find(t)?.let { m ->
                if (m.groupValues[1] == reg) return traceReg(infos, k, m.groupValues[2], depth - 1)
            }
            LD_INDEXED.find(t)?.let { m ->
                if (m.groupValues[1] == reg) {
                    val base = traceReg(infos, k, m.groupValues[2], depth - 1) as? Traced.Const
                    val scale = m.groupValues[3].toIntOrNull() ?: 3
                    return base?.let { Traced.Slot(it.v, if (scale == 2) 4 else 8) }
                }
            }
            LD_DISP.find(t)?.let { m ->
                if (m.groupValues[1] == reg) {
                    val disp = hex(m.groupValues[3]) ?: 0L
                    return when (val b = traceReg(infos, k, m.groupValues[2], depth - 1)) {
                        is Traced.Const -> Traced.Slot(b.v + disp, 0)
                        is Traced.Slot -> Traced.ObjField(disp)
                        is Traced.ObjField -> Traced.ObjField(disp)
                        null -> Traced.ObjField(disp)
                    }
                }
            }
            return null      // 写了但不是可识别的形态
        }
        return null
    }

    private fun writesReg(ins: Insn, reg: String): Boolean {
        if (ins.mnem in NO_WRITE) return false
        val m = FIRST_X.find(ins.text) ?: return false
        return m.groupValues[1] == reg
    }

    /**
     * 反推 AArch64 跳表（switch）。**严格匹配编译器生成的指令链，任何一环对不上就返回 null。**
     *
     * 形态 A（4 字节有符号偏移，相对表基址；clang 默认）：
     * ```
     * adrp  x8, 0x12000
     * add   x8, x8, #0x3c0
     * ldrsw x9, [x8, w0, uxtw #2]
     * add   x8, x8, x9
     * br    x8
     * ```
     * 形态 B（8 字节绝对地址表）：
     * ```
     * adrp x8, 0x12000
     * add  x8, x8, #0x40
     * ldr  x9, [x8, x0, lsl #3]
     * br   x9
     * ```
     *
     * 为什么必须严格：实测 libpython3.12.so / libcrypto 里 `br xN` 绝大多数是间接尾调用，
     * 跳表一条都没有。放松成"窗口内有 adrp 就当跳表"会把随机内存读成目标
     * —— 目标过滤能挡住大部分，但仍会漏出假边。
     */
    private fun resolveJumpTable(
        infos: List<Insn>,
        brIndex: Int,
        reader: (Long, Int) -> ByteArray?,
    ): JumpTable? {
        val dst = BR_X.find(infos[brIndex].text)?.groupValues?.get(1) ?: return null
        val count = run {
            var c = 0
            for (k in maxOf(0, brIndex - 16) until brIndex) {
                CMP_IMM.find(infos[k].text)?.let { m ->
                    val v = hex(m.groupValues[1]) ?: 0L
                    if (v in 1..1023) c = (v + 1).toInt()
                }
            }
            if (c > 0) c.coerceAtMost(512) else 64
        }

        // ── 形态 A：add xD, xB, xK → ldrsw xK, [xB, idx] → adrp xB ──
        for (j in (brIndex - 1) downTo maxOf(0, brIndex - 4)) {
            val add = ADD_REG_REG_REG.find(infos[j].text) ?: continue
            val d = add.groupValues[1]
            val b = add.groupValues[2]
            val k = add.groupValues[3]
            if (d != dst && b != dst) continue
            if (d == k || b == k) continue

            val ldrIdx = ((j - 1) downTo maxOf(0, j - 5)).firstOrNull { p ->
                LDR_SIGNED_INDEXED.find(infos[p].text)?.let { lm ->
                    lm.groupValues[1] == k && lm.groupValues[2] == b
                } ?: false
            } ?: continue

            val page = ((ldrIdx - 1) downTo maxOf(0, ldrIdx - 7)).firstNotNullOfOrNull { p ->
                ADRP.find(infos[p].text)?.let { am ->
                    if (am.groupValues[1] == b) hex(am.groupValues[2]) else null
                }
            } ?: continue

            var pageOff = 0L
            for (p in (ldrIdx - 1) downTo maxOf(0, ldrIdx - 7)) {
                val am = ADD_REG_IMM.find(infos[p].text) ?: continue
                if (am.groupValues[1] == b && am.groupValues[2] == b) {
                    pageOff = hex(am.groupValues[3]) ?: 0L
                    break
                }
            }
            val table = page + pageOff
            if (table <= 0L) continue
            val targets = readRelativeTable(reader, table, count) ?: continue
            return JumpTable(targets, 4)
        }

        // ── 形态 B：ldr xK, [xB, idx, lsl #3] → adrp xB（K 就是 br 的寄存器）──
        for (j in (brIndex - 1) downTo maxOf(0, brIndex - 5)) {
            val ldr = LDR_PTR_INDEXED.find(infos[j].text) ?: continue
            if (ldr.groupValues[1] != dst) continue
            val b = ldr.groupValues[2]
            val page = ((j - 1) downTo maxOf(0, j - 7)).firstNotNullOfOrNull { p ->
                ADRP.find(infos[p].text)?.let { am ->
                    if (am.groupValues[1] == b) hex(am.groupValues[2]) else null
                }
            } ?: continue
            var pageOff = 0L
            for (p in (j - 1) downTo maxOf(0, j - 7)) {
                val am = ADD_REG_IMM.find(infos[p].text) ?: continue
                if (am.groupValues[1] == b && am.groupValues[2] == b) {
                    pageOff = hex(am.groupValues[3]) ?: 0L
                    break
                }
            }
            val table = page + pageOff
            if (table <= 0L) continue
            val targets = readAbsoluteTable(reader, table, count) ?: continue
            return JumpTable(targets, 8)
        }
        return null
    }

    /** 形态 A：每项 4 字节有符号，目标 = 表基址 + offset。 */
    private fun readRelativeTable(reader: (Long, Int) -> ByteArray?, table: Long, count: Int): List<Long>? {
        val bytes = reader(table, count * 4) ?: return null
        if (bytes.size < 4) return null
        val out = ArrayList<Long>(count)
        var i = 0
        while (i + 4 <= bytes.size) {
            var v = (bytes[i].toLong() and 0xFF)
            v = v or ((bytes[i + 1].toLong() and 0xFF) shl 8)
            v = v or ((bytes[i + 2].toLong() and 0xFF) shl 16)
            v = v or ((bytes[i + 3].toLong() and 0xFF) shl 24)
            val off = if (v >= 0x80000000L) v - 0x100000000L else v
            val tgt = table + off
            if (tgt > 0L) out.add(tgt)
            i += 4
        }
        return out.distinct().takeIf { it.isNotEmpty() }
    }

    /** 形态 B：每项 8 字节无符号，值本身就是目标地址（R_AARCH64_RELATIVE 的 addend 已是 VA）。 */
    private fun readAbsoluteTable(reader: (Long, Int) -> ByteArray?, table: Long, count: Int): List<Long>? {
        val bytes = reader(table, count * 8) ?: return null
        if (bytes.size < 8) return null
        val out = ArrayList<Long>(count)
        var i = 0
        while (i + 8 <= bytes.size) {
            var v = 0L
            for (b in 7 downTo 0) {
                v = (v shl 8) or (bytes[i + b].toLong() and 0xFF)
            }
            if (v > 0L) out.add(v)
            i += 8
        }
        return out.distinct().takeIf { it.isNotEmpty() }
    }

    // ───────────────────────── 数据槽解析（UI 侧用） ─────────────────────────

    /**
     * `irj`（重定位 JSON）→ 槽地址 → 符号名。
     *
     * rizin 的每项形如 `{"vaddr":5306880,"type":"R_AARCH64_JUMP_SLOT","name":"memcpy"}`；
     * RELATIVE 项没有名字（原本就是本地地址），这里跳过 —— 那些槽的内容本身就是目标地址，
     * 由 [slotHitOf] 读 8 字节得到。
     */
    fun parseRelocNames(json: String): Map<Long, String> {
        val out = HashMap<Long, String>()
        if (json.isBlank()) return out
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = o.optLong("vaddr", 0L)
            val n = o.optString("name", "")
            if (v > 0L && n.isNotBlank()) out[v] = n
        }
        return out
    }

    /**
     * 造一个带缓存的槽解析器。同一个槽被多个 `br` 引用时只读一次内存。
     *
     * @param readMem (地址, 长度) → 字节（UI 侧用 rizin `p8`）
     * @param relocNames [parseRelocNames] 的结果
     */
    fun makeSlotReader(
        readMem: (Long, Int) -> ByteArray?,
        relocNames: Map<Long, String>,
    ): (Long, Int) -> SlotHit? {
        val cache = HashMap<Long, ByteArray?>()
        return { addr, scale ->
            relocNames[addr]?.let { SlotHit(it, emptyList()) } ?: run {
                val len = if (scale > 0) 16 * scale else 8
                slotHitOf(addr, cache.getOrPut(addr) { readMem(addr, len) }, scale, relocNames)
            }
        }
    }

    /**
     * 槽内容 → 目标。
     *
     * · 有重定位符号名 → 外部函数（`memcpy`），无本地目标
     * · 槽内容落在本 SO 代码段 → 本地函数地址（调用方再过滤"是否在本函数内"决定建不建边）
     * · `scale > 0` 表示这是一张表（`ldr x,[base,idx,lsl #3]`），逐项解析成多目标
     */
    fun slotHitOf(addr: Long, bytes: ByteArray?, scale: Int, relocNames: Map<Long, String>): SlotHit? {
        relocNames[addr]?.let { return SlotHit(it, emptyList()) }
        if (bytes == null) return null
        if (scale <= 0) {
            val v = readLe64(bytes, 0) ?: return null
            if (v == 0L) return null
            val name = relocNames[v] ?: ("0x" + java.lang.Long.toHexString(v))
            return SlotHit(name, listOf(v))
        }
        val out = ArrayList<Long>()
        var i = 0
        while (i + scale <= bytes.size) {
            if (scale == 8) {
                val v = readLe64(bytes, i)
                if (v != null && v != 0L) out.add(v)
            } else if (i + 4 <= bytes.size) {
                val v = readLe32Signed(bytes, i)
                if (v != 0L) out.add(addr + v)
            }
            i += scale
        }
        if (out.isEmpty()) return null
        return SlotHit(out.size.toString() + " 项", out)
    }

    private fun readLe64(b: ByteArray, at: Int): Long? {
        if (at + 8 > b.size) return null
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun readLe32Signed(b: ByteArray, at: Int): Long {
        if (at + 4 > b.size) return 0L
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return if (v >= 0x80000000L) v - 0x100000000L else v
    }

    /** rizin `p8` 的十六进制输出 → 字节。解析失败返回 null（不抛，解析是可选的）。 */
    fun hexToBytes(hexText: String): ByteArray? {
        val clean = hexText.filter { !it.isWhitespace() }
        if (clean.length < 2) return null
        // 读失败时 rizin 回的是 "Cannot read ..." 之类文本，必须先挡掉再按 2 字符切，
        // 否则 "Ca" 会被当成字节 0xCA。
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val n = clean.length / 2
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            val v = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = v.toByte()
            i++
        }
        return out
    }
}
