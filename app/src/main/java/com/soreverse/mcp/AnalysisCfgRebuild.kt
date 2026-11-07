package com.soreverse.mcp

/**
 * 从线性反汇编重建控制流图。
 *
 * 为什么需要：rizin 有时把整段代码识别成**一个基本块**（函数边界没切、或 `af` 只做了线性扫描），
 * 于是 `agfj/rzCfg` 只返回 1 个块 —— 界面上就是"控制流图形化只有一个框"，
 * 而节点列表里那一个块却挂着上千条指令。
 *
 * 这里用已有的线性指令列表（`pdfj` 的逐指令结果）自己切块：
 *   · 块起点 = 函数入口 + 所有跳转目标 + 所有跳转指令的下一条
 *   · 块尾 = 跳转/返回指令
 *   · 边 = 顺序边（块尾不是无条件跳转/返回时）+ 跳转边
 *
 * 只做"能确定的"：跳转目标必须落在函数自己的指令地址集合里（跳出去的一律当外部调用，
 * 不建边），这样不会把相邻函数误并联进来。
 *
 * AArch64 的 `br xN`（跳表/switch）静态看不出目标，靠 `resolveJumpTable` 反推：
 * `adrp x8, page` + `add x8, x8, #off` 得到表基址，表项是 4 字节有符号偏移，
 * 目标 = 表基址 + offset[i]。读表通过调用方给的 [memReader]（UI 侧用 rizin `p8`）。
 */
internal object AnalysisCfgRebuild {

    private val HEX = Regex("0x[0-9a-fA-F]+")

    /** AArch64 / ARM / x86 都适用的无条件跳转与条件跳转助记符。 */
    private val UNCOND = setOf("b", "br", "bx", "jmp", "j")
    private val COND_PREFIX = listOf(
        "cbz", "cbnz", "tbz", "tbnz", "b.", "beq", "bne", "bcs", "bcc", "blt", "bgt", "ble", "bge",
        "bmi", "bpl", "bvs", "bvc", "bhi", "bls", "jz", "jnz", "je", "jne", "ja", "jb", "jg", "jl",
    )
    private val RET_PREFIX = listOf("ret", "bx lr", "leave")

    private val ADRP = Regex("""adrp\s+(x\d+)\s*,\s*(0x[0-9a-fA-F]+)""")
    private val ADD_REG_IMM = Regex("""add\s+(x\d+)\s*,\s*(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    private val CMP_IMM = Regex("""cmp\s+w\d+\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    private val BR_REG = Regex("""\bbr\s+x\d+""")

    private data class Insn(
        val addr: Long,
        val text: String,
        val mnem: String,
        val targets: List<Long>,
        val isCond: Boolean,
        val isUncond: Boolean,
        val isRet: Boolean,
    )

    private fun hex(v: String): Long? =
        if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2).toLongOrNull(16) else v.toLongOrNull()

    /**
     * @param funcName 函数名（进 CfgGraph）
     * @param funcVa 函数入口地址文本（进 CfgGraph）
     * @param linear `pdfj` 逐指令结果：地址 → 反汇编文本（按地址升序）
     * @param density 保留参数（历史兼容，当前布局不吃这个值）
     * @param memReader 读进程内存（地址, 长度）→ 字节；为 null 时不做跳表解析
     * @return 重建结果；无法重建时返回 null，调用方保留原图
     */
    @Suppress("UNUSED_PARAMETER")
    fun rebuild(
        funcName: String,
        funcVa: String,
        linear: List<Pair<Long, String>>,
        density: Float = 1f,
        memReader: ((Long, Int) -> ByteArray?)? = null,
    ): CfgGraph? {
        if (linear.size < 4) return null
        val sorted = linear.sortedBy { it.first }
        val addrs = sorted.map { it.first }
        val addrSet = addrs.toHashSet()

        var infos = sorted.map { (addr, text) ->
            val body = text.trim()
            val mnem = body.substringBefore(' ').trim().lowercase()
            val targets = HEX.findAll(body).mapNotNull { m ->
                m.value.removePrefix("0x").toLongOrNull(16)
            }.filter { it != addr && it in addrSet }.toList()
            val isRet = RET_PREFIX.any { mnem.startsWith(it) } || mnem == "ret"
            val isUncond = (mnem in UNCOND || mnem == "b") && !isRet
            val isCond = !isUncond && !isRet && COND_PREFIX.any { mnem.startsWith(it) } && targets.isNotEmpty()
            Insn(addr, body, mnem, if (isCond || isUncond) targets else emptyList(), isCond, isUncond, isRet)
        }

        // br xN：静态没有目标，用跳表反推补上（只对能确定的项，解析失败就保持原样）
        if (memReader != null) {
            infos = infos.mapIndexed { i, inf ->
                if (inf.mnem == "br" && inf.targets.isEmpty() && BR_REG.containsMatchIn(inf.text)) {
                    val jt = resolveJumpTable(infos, i, memReader) ?: return@mapIndexed inf
                    val valid = jt.filter { it in addrSet }
                    if (valid.isEmpty()) inf else inf.copy(targets = valid)
                } else inf
            }
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

        val edges = LinkedHashSet<Triple<Int, Int, String>>()
        val infoByAddr = infos.associateBy { it.addr }
        blocks.forEachIndexed { bi, start ->
            val lastAddr = lines[bi].lastOrNull()?.let { t ->
                infos.lastOrNull { it.text == t && it.addr >= start }?.addr
            } ?: start
            val tail = infoByAddr[lastAddr] ?: return@forEachIndexed
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
            CfgBlock(
                index = i,
                addrText = "0x" + java.lang.Long.toHexString(a),
                addrValue = a,
                // 块上界 = 下一块起点：mergeLinearInsns 靠它把线性指令归到正确的块
                endText = if (i + 1 < blocks.size) "0x" + java.lang.Long.toHexString(blocks[i + 1]) else "",
                summary = lines[i].firstOrNull().orEmpty().take(80),
            )
        }
        val cfgEdges = edges.map { CfgEdge(it.first, it.second, it.third) }
        return CfgGraph(funcName, funcVa, cfgBlocks, cfgEdges)
    }

    /**
     * 反推 AArch64 跳表（PIC 布局）：
     *
     * ```
     * cmp  w0, #7
     * b.hi default
     * adrp x8, 0x12000            ; 页基址
     * add  x8, x8, #0x3c0         ; 表地址 = 页基址 + 页内偏移
     * ldrsw x9, [x8, w0, uxtw #2] ; 取 4 字节有符号偏移
     * add  x8, x8, x9
     * br   x8
     * ```
     *
     * 表项 = 相对**表基址**的 32 位有符号偏移。项数优先用前面的 `cmp wN, #imm`（imm+1），
     * 否则按 64 项试读；读到的目标如果全都不在函数内，由调用方丢弃。
     */
    private fun resolveJumpTable(
        infos: List<Insn>,
        brIndex: Int,
        reader: (Long, Int) -> ByteArray?,
    ): List<Long>? {
        val from = maxOf(0, brIndex - 14)
        var pageReg: String? = null
        var pageBase = 0L
        var addOff = 0L
        var hasAdd = false
        var count = 0

        for (k in from until brIndex) {
            val t = infos[k].text
            ADRP.find(t)?.let { m ->
                pageReg = m.groupValues[1]
                pageBase = hex(m.groupValues[2]) ?: 0L
            }
            ADD_REG_IMM.find(t)?.let { m ->
                if (pageReg != null && m.groupValues[1] == pageReg && m.groupValues[2] == pageReg) {
                    addOff = hex(m.groupValues[3]) ?: 0L
                    hasAdd = true
                }
            }
            CMP_IMM.find(t)?.let { m ->
                val v = hex(m.groupValues[1]) ?: 0L
                if (v in 1..1023) count = (v + 1).toInt()
            }
        }
        if (pageReg == null || pageBase == 0L) return null
        val table = if (hasAdd) pageBase + addOff else pageBase
        if (table <= 0L) return null

        val n = if (count > 0) count.coerceAtMost(512) else 64
        val bytes = reader(table, n * 4) ?: return null
        if (bytes.size < 4) return null

        val out = ArrayList<Long>(n)
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

    /** rizin `p8` 的十六进制输出 → 字节。解析失败返回 null（不抛，跳表解析是可选的）。 */
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
