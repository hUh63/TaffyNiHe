package com.soreverse.mcp

/**
 * 从线性反汇编重建控制流图，并解析 AArch64 的寄存器间接跳转。
 *
 * 为什么需要重建：rizin 有时把整段代码识别成**一个基本块**（函数边界没切、或 `af` 只做了线性扫描），
 * 于是 `agfj/rzCfg` 只返回 1 个块 —— 界面上就是"控制流图形化只有一个框"，
 * 而节点列表里那一个块却挂着上千条指令。
 *
 * 切块规则：
 *   · 块起点 = 函数入口 + 所有跳转目标 + 所有跳转指令的下一条
 *   · 块尾 = 跳转/返回指令
 *   · 边 = 顺序边（块尾不是无条件跳转/返回时）+ 跳转边
 * 跳转目标必须落在函数自己的指令地址集合里，否则当外部调用不建边。
 *
 * `br xN` 的解析分两层：
 *
 * **① 函数内数据流**（[analyzeBranches]）：顺着指令顺序维护每个寄存器的抽象值
 * （常量地址 / 数据槽 / 对象字段 / 入口参数），并跟踪 `str/stp → [sp,#off]` 的栈槽。
 * 这样 `ldr x19,[sp,#0x10]; br x19`（epilogue 恢复后尾跳）这种跨几十条指令的情况也能追到源头。
 *
 * **② 抽象值落地**（[resolveBranch]）：
 *
 * | 抽象值 | 例子 | 结果 |
 * |---|---|---|
 * | 常量槽 | `adrp x8,got; ldr x16,[x8,#0x10]; br x16` | 重定位符号名（`memcpy`） |
 * | 常量表 | `ldr x9,[x8,x0,lsl #3]; br x9` | 逐项解析成多目标 |
 * | 对象字段 | `ldr x8,[x0,#8]; ldr x1,[x8,#0x68]; br x1` | 无解，标 `[obj+0x68]` |
 * | 入口参数 | 函数内从未改写的 x0~x7 | 标 `参数 x2（调用方传入）` |
 *
 * 另外单独处理 switch 跳表（[resolveJumpTable]）：严格匹配 `adrp`+`add`+`ldrsw`+`add`+`br` 五连。
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
    private val SUB_REG_IMM = Regex("""sub\s+(x\d+)\s*,\s*(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    private val ADD_REG_REG_REG = Regex("""add\s+(x\d+)\s*,\s*(x\d+)\s*,\s*(x\d+)""")
    private val CMP_IMM = Regex("""cmp\s+w\d+\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    /** 形态 A 的取表项：`ldrsw x9, [x8, w0, uxtw #2]`。 */
    private val LDR_SIGNED_INDEXED = Regex("""ldrs[whb]\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,[^\]]*\]""")
    /** 形态 B 的取表项：`ldr x9, [x8, x0, lsl #3]`（8 字节绝对地址表）。 */
    private val LDR_PTR_INDEXED = Regex("""ldr\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,[^\]]*lsl\s*#?\s*(2|3)[^\]]*\]""")

    // ── 数据流用的形态 ──
    private val FIRST_X = Regex("""^[a-z][a-z0-9.]*\s+(x\d+)""")
    private val FIRST_TWO_X = Regex("""^ldp(?:sw)?\s+(x\d+)\s*,\s*(x\d+)""")
    private val MOV_RR = Regex("""^mov\s+(x\d+)\s*,\s*(x\d+)\s*$""")
    private val ORR_RR = Regex("""^orr\s+(x\d+)\s*,\s*xzr\s*,\s*(x\d+)""")
    /** `ldr/ldur/ldrb/... xD, [xBase, #disp]`（无索引）。 */
    private val LD_DISP = Regex("""^ld(?:ur|rsw|rsh|rb|rh|r)?\s+(x\d+)\s*,\s*\[\s*(x\d+|sp)\s*(?:,\s*#?(0x[0-9a-fA-F]+|\d+))?\s*\]""")
    private val LD_INDEXED = Regex("""^ldr\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,\s*x\d+\s*,\s*lsl\s*#?\s*(2|3)[^\]]*\]""")
    private val ST_DISP = Regex("""^st(?:ur|rb|rh|r)?\s+(x\d+)\s*,\s*\[\s*sp\s*(?:,\s*#?(0x[0-9a-fA-F]+|\d+))?\s*\]""")
    private val STP_DISP = Regex("""^stp\s+(x\d+)\s*,\s*(x\d+)\s*,\s*\[\s*sp\s*(?:,\s*#?(0x[0-9a-fA-F]+|\d+))?\s*\]""")

    private val NO_WRITE = setOf(
        "str", "stur", "stp", "st1", "st2", "st3", "st4", "cmp", "cmn", "tst",
        "cbz", "cbnz", "tbz", "tbnz", "b", "br", "blr", "bl", "blx", "ret",
        "nop", "bti", "prfm", "dmb", "dsb", "isb",
    )

    private data class Insn(
        val addr: Long,
        val text: String,
        val mnem: String,
        val targets: List<Long>,
        val isCond: Boolean,
        val isUncond: Boolean,
        val isRet: Boolean,
    )

    /** 寄存器的抽象值。 */
    private data class Abs(val kind: Int, val v: Long = 0L, val scale: Int = 0, val name: String = "")

    private const val K_CONST = 0
    private const val K_SLOT = 1
    private const val K_OBJ = 2
    private const val K_PARAM = 3

    private val EMPTY: Set<Abs> = emptySet()

    private class JumpTable(val targets: List<Long>, val itemWidth: Int)

    private fun hex(v: String): Long? =
        if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2).toLongOrNull(16) else v.toLongOrNull()

    /**
     * @param funcName 函数名（进 CfgGraph）
     * @param funcVa 函数入口地址文本（进 CfgGraph）
     * @param linear `pdfj` 逐指令结果：地址 → 反汇编文本（按地址升序）—— **必须是完整函数体**，
     *               数据流靠它做栈槽配对与跨块传播
     * @param density 保留参数（历史兼容，当前布局不吃这个值）
     * @param memReader 读内存（地址, 长度）→ 字节；给跳表解析用
     * @param slotReader 解析数据槽（槽地址, 项宽）→ [SlotHit]
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

        // ① 函数内数据流：每条 br 处目标寄存器的可能来源
        val brStates = analyzeBranches(infos)

        // ② 落地：跳表优先，其次按抽象值解析
        val tailLabels = HashMap<Int, String>()
        for (i in infos.indices) {
            val inf = infos[i]
            if (inf.mnem != "br" || inf.targets.isNotEmpty()) continue
            val dst = BR_X.find(inf.text)?.groupValues?.get(1) ?: continue

            val jt = memReader?.let { resolveJumpTable(infos, i, it) }
            if (jt != null) {
                val valid = jt.targets.filter { it in addrSet }
                if (valid.isNotEmpty()) {
                    infos[i] = inf.copy(targets = valid)
                    continue
                }
            }
            val abs = brStates.getOrNull(i)?.get(dst) ?: EMPTY
            if (abs.isEmpty()) continue
            val resolved = resolveBranch(abs, slotReader)
            val inner = resolved.targets.filter { it in addrSet }
            if (inner.isNotEmpty()) infos[i] = inf.copy(targets = inner)
            if (resolved.label.isNotEmpty()) tailLabels[i] = resolved.label
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

    // ───────────────────────── 函数内数据流 ─────────────────────────

    /**
     * 顺序扫描整个函数体，维护 `寄存器 → 抽象值集合` 与 `[sp+off] 栈槽 → 抽象值集合`。
     * 在每条 `br` 处快照当前状态。
     *
     * 为什么用集合：分支汇合时同一个寄存器可能有多个来源（`csel`、if-else），
     * 集合天然表示"N 个候选"，不会因为取最后一个而丢掉分支。
     * 单点候选超过 8 个时视为无法确定，不再展开。
     */
    private fun analyzeBranches(infos: List<Insn>): Array<Map<String, Set<Abs>>> {
        val out = Array<Map<String, Set<Abs>>>(infos.size) { emptyMap() }
        val state = HashMap<String, Set<Abs>>()
        for (r in 0..7) state["x$r"] = setOf(Abs(K_PARAM, name = "x$r"))
        val stack = HashMap<Long, Set<Abs>>()

        for (i in infos.indices) {
            val inf = infos[i]
            if (inf.mnem == "br") {
                out[i] = HashMap(state)
                continue
            }
            // 栈写入：记录槽里存的是谁的当前值
            when (inf.mnem) {
                "str", "stur", "strb", "strh" -> ST_DISP.find(inf.text)?.let { m ->
                    val off = hex(m.groupValues[2]) ?: 0L
                    stack[off] = state[m.groupValues[1]] ?: EMPTY
                }
                "stp" -> STP_DISP.find(inf.text)?.let { m ->
                    val off = hex(m.groupValues[3]) ?: 0L
                    stack[off] = state[m.groupValues[1]] ?: EMPTY
                    stack[off + 8] = state[m.groupValues[2]] ?: EMPTY
                }
            }
            val writes = writesOf(inf)
            if (writes.isEmpty()) continue

            // ldp 从栈恢复两个寄存器：分别取两个槽
            if (inf.mnem == "ldp" || inf.mnem == "ldpsw") {
                val m = FIRST_TWO_X.find(inf.text)
                val ld = LD_DISP.find(inf.text)
                if (m != null && ld != null && ld.groupValues[2] == "sp") {
                    val off = hex(ld.groupValues[3]) ?: 0L
                    state[m.groupValues[1]] = stack[off] ?: EMPTY
                    state[m.groupValues[2]] = stack[off + 8] ?: EMPTY
                    continue
                }
            }
            val v = evalInsn(inf, state, stack)
            writes.forEach { state[it] = v }
        }
        return out
    }

    private fun writesOf(inf: Insn): List<String> {
        if (inf.mnem in NO_WRITE) return emptyList()
        if (inf.mnem == "ldp" || inf.mnem == "ldpsw") {
            val m = FIRST_TWO_X.find(inf.text) ?: return emptyList()
            return listOf(m.groupValues[1], m.groupValues[2])
        }
        val m = FIRST_X.find(inf.text) ?: return emptyList()
        return listOf(m.groupValues[1])
    }

    /** 算一条指令写出的抽象值。返回 [EMPTY] 表示"未知"（区别于"没写过"）。 */
    private fun evalInsn(
        inf: Insn,
        state: Map<String, Set<Abs>>,
        stack: Map<Long, Set<Abs>>,
    ): Set<Abs> {
        val t = inf.text
        ADRP.find(t)?.let { m ->
            val v = hex(m.groupValues[2]) ?: 0L
            return if (v != 0L) setOf(Abs(K_CONST, v)) else EMPTY
        }
        ADD_REG_IMM.find(t)?.let { m ->
            val base = state[m.groupValues[2]] ?: return EMPTY
            val imm = hex(m.groupValues[3]) ?: 0L
            val out = HashSet<Abs>()
            base.forEach { if (it.kind == K_CONST) out.add(Abs(K_CONST, it.v + imm)) }
            return if (out.isEmpty()) EMPTY else out
        }
        SUB_REG_IMM.find(t)?.let { m ->
            val base = state[m.groupValues[2]] ?: return EMPTY
            val imm = hex(m.groupValues[3]) ?: 0L
            val out = HashSet<Abs>()
            base.forEach { if (it.kind == K_CONST) out.add(Abs(K_CONST, it.v - imm)) }
            return if (out.isEmpty()) EMPTY else out
        }
        MOV_RR.find(t)?.let { m -> return state[m.groupValues[2]] ?: EMPTY }
        ORR_RR.find(t)?.let { m -> return state[m.groupValues[2]] ?: EMPTY }
        LD_INDEXED.find(t)?.let { m ->
            val base = state[m.groupValues[2]] ?: return EMPTY
            val scale = if (m.groupValues[3] == "2") 4 else 8
            val out = HashSet<Abs>()
            base.forEach { if (it.kind == K_CONST) out.add(Abs(K_SLOT, it.v, scale)) }
            return if (out.isEmpty()) EMPTY else out
        }
        LD_DISP.find(t)?.let { m ->
            val baseName = m.groupValues[2]
            val disp = hex(m.groupValues[3]) ?: 0L
            // 栈槽恢复：这就是 `ldr x19,[sp,#0x10]; ...; br x19` 能追到源头的原因
            if (baseName == "sp") return stack[disp] ?: EMPTY
            val base = state[baseName] ?: return EMPTY
            val out = HashSet<Abs>()
            base.forEach {
                when (it.kind) {
                    K_CONST -> out.add(Abs(K_SLOT, it.v + disp))
                    K_PARAM -> out.add(Abs(K_OBJ, disp))      // 从参数指向的结构体取字段
                    else -> out.add(Abs(K_OBJ, disp))
                }
            }
            return out
        }
        return EMPTY
    }

    /**
     * 抽象值 → 可展示目标。
     * 多个候选（分支汇合）时全部列出，标一条"多目标"。
     */
    private fun resolveBranch(
        abs: Set<Abs>,
        slotReader: ((Long, Int) -> SlotHit?)?,
    ): SlotHit {
        val targets = ArrayList<Long>()
        val labels = ArrayList<String>()
        var guard = 0
        for (a in abs) {
            if (guard++ >= 8) break
            when (a.kind) {
                K_CONST -> {
                    targets.add(a.v)
                    labels.add("0x" + java.lang.Long.toHexString(a.v))
                }
                K_SLOT -> {
                    val hit = slotReader?.invoke(a.v, a.scale)
                    if (hit != null) {
                        labels.add(hit.label)
                        targets.addAll(hit.targets)
                    } else {
                        labels.add("槽 0x" + java.lang.Long.toHexString(a.v))
                    }
                }
                K_OBJ -> labels.add("[obj+0x" + java.lang.Long.toHexString(a.v) + "]")
                K_PARAM -> labels.add("参数 " + a.name + "（调用方传入）")
            }
        }
        val label = when {
            labels.isEmpty() -> ""
            labels.size == 1 -> labels[0]
            else -> labels.take(3).joinToString(" / ") + if (labels.size > 3) " …" else ""
        }
        return SlotHit(label, targets.distinct())
    }

    // ───────────────────────── 跨调用点展开 ─────────────────────────

    /**
     * 找出函数内所有**参数尾跳**：`br xN` 且 xN 是本函数的入口参数（函数内从未被改写，
     * 或经栈中转后追回参数）。返回值是 (br 地址, 参数名)。
     *
     * 这类目标由调用方决定，单看本函数只能是"参数 x2"；展开要用 [evalParamAtCaller]。
     */
    fun paramTailJumps(linear: List<Pair<Long, String>>): List<Pair<Long, String>> {
        if (linear.size < 2) return emptyList()
        val infos = linear.sortedBy { it.first }.map { (addr, text) ->
            val body = text.trim()
            Insn(addr, body, body.substringBefore(' ').trim().lowercase(), emptyList(), false, false, false)
        }
        val states = analyzeBranches(infos)
        val out = ArrayList<Pair<Long, String>>()
        infos.forEachIndexed { i, inf ->
            if (inf.mnem != "br") return@forEachIndexed
            val dst = BR_X.find(inf.text)?.groupValues?.get(1) ?: return@forEachIndexed
            val abs = states.getOrNull(i)?.get(dst) ?: return@forEachIndexed
            if (abs.any { it.kind == K_PARAM }) out.add(inf.addr to dst)
        }
        return out
    }

    /**
     * 调用点前的指令里 [param] 被赋成了什么 —— 跨调用点展开的核心。
     *
     * 典型形态（libcrypto `0x212xxx` 调用一个 thunk）：
     * ```
     * adrp x2, 0x1e000
     * add  x2, x2, #0x8c0      ; x2 = 0x1e8c0 ← 真正被尾跳的函数
     * bl   0x2126a0
     * ```
     *
     * @param ctx 调用点之前的一段线性反汇编（按地址升序）
     * @return 描述文本（`0x1e8c0` / `[0x1e8d0]` / `[obj+0x68]` / `参数 x0`），空串表示没赋过值
     */
    fun evalParamAtCaller(ctx: List<Pair<Long, String>>, param: String): String {
        if (ctx.isEmpty()) return ""
        val infos = ctx.sortedBy { it.first }.map { (addr, text) ->
            val body = text.trim()
            Insn(addr, body, body.substringBefore(' ').trim().lowercase(), emptyList(), false, false, false)
        }
        val state = HashMap<String, Set<Abs>>()
        // 调用点里的 x0~x7 是**调用者函数**的参数，从这里往下推
        for (r in 0..7) state["x$r"] = setOf(Abs(K_PARAM, name = "x$r"))
        val stack = HashMap<Long, Set<Abs>>()
        infos.forEach { inf ->
            val writes = writesOf(inf)
            if (writes.isEmpty()) return@forEach
            val v = evalInsn(inf, state, stack)
            writes.forEach { state[it] = v }
        }
        val v = state[param] ?: EMPTY
        // 调用点没动过这个寄存器 → 目标仍由更上层决定，报空（不误导成"参数 x2 = 参数 x2"）
        if (v.all { it.kind == K_PARAM }) return ""
        return describeAbs(v)
    }

    private fun describeAbs(abs: Set<Abs>): String {
        if (abs.isEmpty()) return ""
        return abs.take(4).joinToString(" / ") {
            when (it.kind) {
                K_CONST -> "0x" + java.lang.Long.toHexString(it.v)
                K_SLOT -> "[0x" + java.lang.Long.toHexString(it.v) + "]"
                K_OBJ -> "[obj+0x" + java.lang.Long.toHexString(it.v) + "]"
                else -> "参数 " + it.name
            }
        }
    }

    // ───────────────────────── switch 跳表 ─────────────────────────

    /**
     * 反推 AArch64 跳表。**严格匹配编译器生成的指令链，任何一环对不上就返回 null。**
     *
     * 形态 A（4 字节有符号偏移，相对表基址）：
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
     * 放松成"窗口内有 adrp 就当跳表"会把随机内存读成目标 —— 目标过滤能挡住大部分，但仍会漏出假边。
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

    /** 形态 B：每项 8 字节无符号，值本身就是目标地址。 */
    private fun readAbsoluteTable(reader: (Long, Int) -> ByteArray?, table: Long, count: Int): List<Long>? {
        val bytes = reader(table, count * 8) ?: return null
        if (bytes.size < 8) return null
        val out = ArrayList<Long>(count)
        var i = 0
        while (i + 8 <= bytes.size) {
            var v = 0L
            for (b in 7 downTo 0) v = (v shl 8) or (bytes[i + b].toLong() and 0xFF)
            if (v > 0L) out.add(v)
            i += 8
        }
        return out.distinct().takeIf { it.isNotEmpty() }
    }

    // ───────────────────────── 数据槽解析（UI 侧用） ─────────────────────────

    /**
     * `irj`（重定位 JSON）→ 槽地址 → 符号名。
     * rizin 每项形如 `{"vaddr":5306880,"type":"R_AARCH64_JUMP_SLOT","name":"memcpy"}`；
     * RELATIVE 项没有名字，那些槽的内容本身就是目标地址。
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
     * 造一个带缓存的内存读取器（跳表解析用）。同一个地址只读一次 ——
     * native 每次 `rzCommand` 都要重跑全量分析，多读一次就多几秒。
     */
    fun memReaderOf(read: (String) -> String): (Long, Int) -> ByteArray? {
        val cache = HashMap<Long, ByteArray?>()
        return { addr, len ->
            cache.getOrPut(addr) {
                hexToBytes(read("p8 $len @ 0x" + java.lang.Long.toHexString(addr)))
            }
        }
    }

    /** 造一个带缓存的槽解析器：同一个槽被多个 `br` 引用时只读一次内存。 */
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
     * 槽内容 → 目标：有重定位符号名就用名字；否则读 8 字节当目标地址；
     * `scale > 0` 表示这是一张表，逐项解析成多目标。
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

    /** rizin `p8` 的十六进制输出 → 字节。解析失败返回 null。 */
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
