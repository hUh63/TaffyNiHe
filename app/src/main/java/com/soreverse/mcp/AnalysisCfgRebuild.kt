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
 * AArch64 的 `br xN` 静态看不出目标，分三种情况处理：
 *   · 跳表（switch）—— 严格匹配编译器生成的固定指令链后反推目标，见 [resolveJumpTable]
 *   · 间接尾调用（`ldr x1,[x9]; br x1` / `br x16` 走 PLT）—— 静态不可解，
 *     在块摘要里标注"间接跳转"，不建边（不猜）
 *   · 其它计算跳转 —— 同上，标注不建边
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

    private val BR_X = Regex("""\bbr\s+(x\d+)""")
    private val ADRP = Regex("""adrp\s+(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|[0-9]+)""")
    private val ADD_REG_IMM = Regex("""add\s+(x\d+)\s*,\s*(x\d+)\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    private val ADD_REG_REG_REG = Regex("""add\s+(x\d+)\s*,\s*(x\d+)\s*,\s*(x\d+)""")
    private val CMP_IMM = Regex("""cmp\s+w\d+\s*,\s*#?(0x[0-9a-fA-F]+|\d+)""")
    /** 形态 A 的取表项：`ldrsw x9, [x8, w0, uxtw #2]`。 */
    private val LDR_SIGNED_INDEXED = Regex("""ldrs[whb]\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,[^\]]*\]""")
    /** 形态 B 的取表项：`ldr x9, [x8, x0, lsl #3]`（8 字节绝对地址表）。 */
    private val LDR_PTR_INDEXED = Regex("""ldr\s+(x\d+)\s*,\s*\[\s*(x\d+)\s*,[^\]]*lsl\s*#?\s*3[^\]]*\]""")

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

        // br xN：严格匹配跳表链才反推目标；匹配不上就当间接尾调用（保留"无边"语义）
        if (memReader != null) {
            infos = infos.mapIndexed { i, inf ->
                if (inf.mnem == "br" && inf.targets.isEmpty() && BR_X.containsMatchIn(inf.text)) {
                    val jt = resolveJumpTable(infos, i, memReader) ?: return@mapIndexed inf
                    // 只认真落在本函数指令流里的目标：表项如果是绝对地址（形态 B），
                    // 重定位后的值是 VA，这里能直接对上；对不上说明表不是本函数的跳表。
                    val valid = jt.targets.filter { it in addrSet }
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

        // 块尾：决定出边，也决定摘要里要不要标"间接跳转"
        val infoByAddr = infos.associateBy { it.addr }
        val tails = blocks.mapIndexed { bi, start ->
            val lastAddr = lines[bi].lastOrNull()?.let { t ->
                infos.lastOrNull { it.text == t && it.addr >= start }?.addr
            } ?: start
            infoByAddr[lastAddr]
        }

        val edges = LinkedHashSet<Triple<Int, Int, String>>()
        blocks.forEachIndexed { bi, _ ->
            val tail = tails[bi] ?: return@forEachIndexed
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
            // 块尾是 `br xN` 但没解析出目标 = 间接尾跳（函数指针 / PLT / 篡改过的跳表）：
            // 控制流在这里离开本函数，静态跟不了 —— 标出来，免得看着像"函数就这么结束了"。
            val tail = tails[i]
            val note = if (tail != null && tail.mnem == "br" && tail.targets.isEmpty()) {
                " · " + tail.text.take(24) + " 间接跳转"
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

    /**
     * 反推 AArch64 跳表（switch）。**严格匹配编译器生成的指令链，任何一环对不上就返回 null。**
     *
     * 形态 A（4 字节有符号偏移，相对表基址；clang 默认，switch 最常见）：
     * ```
     * adrp  x8, 0x12000            ; 页基址
     * add   x8, x8, #0x3c0         ; 表地址 = 页基址 + 页内偏移
     * ldrsw x9, [x8, w0, uxtw #2]  ; 取 4 字节有符号偏移
     * add   x8, x8, x9             ; 目标 = 表基址 + offset
     * br    x8
     * ```
     *
     * 形态 B（8 字节绝对地址表）：
     * ```
     * adrp x8, 0x12000
     * add  x8, x8, #0x40
     * ldr  x9, [x8, x0, lsl #3]    ; 直接取指针
     * br   x9
     * ```
     *
     * 为什么必须严格：实测 libpython3.12.so / libcrypto 这类真实 SO 里，`br xN` 的
     * 绝大多数是**间接尾调用**（`ldr x1, [x9]; br x1` 走函数指针，或 `br x16` 走 PLT），
     * 跳表一条都没有。只要放松成"窗口内有 adrp 就当跳表"，就会把随机内存读成目标
     * —— 目标过滤能挡住大部分，但仍会漏出假边。宁可跟不了，也不能乱连。
     *
     * 项数优先用前面的 `cmp wN, #imm`（imm+1），否则按 64 项试读，上限 512。
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
