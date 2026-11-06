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
 */
internal object AnalysisCfgRebuild {

    private val HEX = Regex("0x[0-9a-fA-F]+")

    /** AArch64 / ARM / x86 都适用的无条件跳转与条件跳转助记符前缀。 */
    private val UNCOND = setOf("b", "br", "bx", "jmp", "j")
    private val COND_PREFIX = listOf("cbz", "cbnz", "tbz", "tbnz", "b.", "beq", "bne", "bcs", "bcc", "blt", "bgt", "ble", "bge", "bmi", "bpl", "bvs", "bvc", "bhi", "bls", "jz", "jnz", "je", "jne", "ja", "jb", "jg", "jl")
    private val RET_PREFIX = listOf("ret", "bx lr", "leave")

    /**
     * @param funcName 函数名（进 CfgGraph）
     * @param funcVa 函数入口地址文本（进 CfgGraph）
     * @param linear `pdfj` 逐指令结果：地址 → 反汇编文本（按地址升序）
     * @return 重建结果；无法重建（指令太少 / 没有可识别的跳转）时返回 null，调用方保留原图
     */
    fun rebuild(
        funcName: String,
        funcVa: String,
        linear: List<Pair<Long, String>>,
        density: Float = 1f,
    ): CfgGraph? {
        if (linear.size < 4) return null
        val sorted = linear.sortedBy { it.first }
        val addrs = sorted.map { it.first }
        val addrSet = addrs.toHashSet()

        data class Info(val addr: Long, val text: String, val mnem: String, val targets: List<Long>, val isCond: Boolean, val isUncond: Boolean, val isRet: Boolean)

        val infos = sorted.map { (addr, text) ->
            val body = text.trim()
            val mnem = body.substringBefore(' ').trim().lowercase()
            val targets = HEX.findAll(body).mapNotNull { m ->
                m.value.removePrefix("0x").toLongOrNull(16)
            }.filter { it != addr && it in addrSet }.toList()
            val isRet = RET_PREFIX.any { mnem.startsWith(it) } || mnem == "ret"
            val isUncond = (mnem in UNCOND || mnem == "b") && !isRet
            val isCond = !isUncond && !isRet && COND_PREFIX.any { mnem.startsWith(it) } && targets.isNotEmpty()
            Info(addr, body, mnem, if (isCond || isUncond) targets else emptyList(), isCond, isUncond, isRet)
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
        // 每个地址属于哪个块
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

        // 边：块尾决定
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
            // 顺序边：非无条件跳转、非返回 → 落到下一个块
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
}
