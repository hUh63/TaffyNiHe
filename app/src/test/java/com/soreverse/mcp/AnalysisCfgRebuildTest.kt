package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `br xN` 解析的回归测试。
 *
 * 真实形态（clang，switch 跳表）：见 [AnalysisCfgRebuildTest.jumpTableTargetsBecomeEdges]。
 * 真实 SO 里更常见的形态是**间接尾调用**（`ldr x1,[x9]; br x1` / `br x16` 走 PLT），
 * 这类必须不产生任何边 —— 见 [indirectTailCallIsNotMistakenForJumpTable]。
 */
class AnalysisCfgRebuildTest {

    private fun int32Le(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, v ->
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun int64Le(vararg values: Long): ByteArray {
        val out = ByteArray(values.size * 8)
        values.forEachIndexed { i, v ->
            for (b in 0 until 8) out[i * 8 + b] = ((v ushr (8 * b)) and 0xFF).toByte()
        }
        return out
    }

    private val table = 0x5040L

    private fun linear(): List<Pair<Long, String>> = listOf(
        0x1000L to "cmp w0, #2",
        0x1004L to "b.hi 0x1100",
        0x1008L to "adrp x8, 0x5000",
        0x100cL to "add x8, x8, #0x40",
        0x1010L to "ldrsw x9, [x8, w0, uxtw #2]",
        0x1014L to "add x8, x8, x9",
        0x1018L to "br x8",
        0x1020L to "mov w0, #0",
        0x1024L to "ret",
        0x1030L to "mov w0, #1",
        0x1034L to "ret",
        0x1040L to "mov w0, #2",
        0x1044L to "ret",
        0x1100L to "mov w0, #3",
        0x1104L to "ret",
    )

    @Test
    fun jumpTableTargetsBecomeEdges() {
        // 表项是相对表基址的有符号偏移（PIC 跳表），全部为负
        val bytes = int32Le(
            (0x1020 - table).toInt(),
            (0x1030 - table).toInt(),
            (0x1040 - table).toInt(),
        )
        val reader: (Long, Int) -> ByteArray? = { addr, n ->
            if (addr == table) bytes.copyOf(n.coerceAtMost(bytes.size)) else null
        }
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear(), memReader = reader)
        assertNotNull("跳表解析失败时不应整个放弃重建", g)
        val cfg = g!!
        val brBlock = cfg.blocks.firstOrNull { b ->
            cfg.edges.any { e ->
                e.from == b.index && e.to in cfg.blocks.indices &&
                    cfg.blocks[e.to].addrValue.let { it == 0x1020L || it == 0x1030L || it == 0x1040L }
            }
        }
        assertNotNull("没有块经 br x8 连到 case 块", brBlock)
        val targets = cfg.edges.filter { it.from == brBlock!!.index }
            .map { cfg.blocks[it.to].addrValue }.toSet()
        assertEquals(setOf(0x1020L, 0x1030L, 0x1040L), targets)
        assertTrue(cfg.blocks.any { it.addrValue == 0x1030L })
        assertTrue(cfg.blocks.any { it.addrValue == 0x1040L })
        // 跳表块不该被标成"间接跳转"（目标已解析出来）
        assertFalse(brBlock.summary.contains("间接跳转"))
    }

    @Test
    fun absolutePointerTableIsAlsoResolved() {
        // 形态 B：ldr x9, [x8, x0, lsl #3] —— 8 字节绝对地址表
        val lin = listOf(
            0x1000L to "cmp w0, #2",
            0x1004L to "b.hi 0x1100",
            0x1008L to "adrp x8, 0x5000",
            0x100cL to "add x8, x8, #0x40",
            0x1010L to "ldr x9, [x8, x0, lsl #3]",
            0x1014L to "br x9",
            0x1020L to "mov w0, #0",
            0x1024L to "ret",
            0x1030L to "mov w0, #1",
            0x1034L to "ret",
            0x1040L to "mov w0, #2",
            0x1044L to "ret",
            0x1100L to "mov w0, #3",
            0x1104L to "ret",
        )
        val bytes = int64Le(0x1020L, 0x1030L, 0x1040L)
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", lin) { addr, n ->
            if (addr == table) bytes.copyOf(n.coerceAtMost(bytes.size)) else null
        }
        assertNotNull(g)
        val cfg = g!!
        val targets = cfg.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue(0x1030L in targets)
        assertTrue(0x1040L in targets)
    }

    /**
     * 真实 SO 的绝大多数 `br xN`：窗口里有 `adrp`+`add`（给别的东西用的），
     * 但 `br` 的寄存器来自 `mov`/`ldr`，不是跳表。
     * 这类必须**一条边都不出**，只标"间接跳转"。
     */
    @Test
    fun indirectTailCallIsNotMistakenForJumpTable() {
        val lin = listOf(
            0x1000L to "cbz x0, 0x1020",
            0x1004L to "adrp x8, 0x5000",
            0x1008L to "add x8, x8, #0x40",
            0x100cL to "mov x16, x0",
            0x1010L to "br x16",
            0x1020L to "ret",
        )
        // 就算读者全给真数据，也不能把它当跳表读
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", lin) { _, _ ->
            int64Le(0x1020L, 0x1030L, 0x1040L)
        }
        assertNotNull(g)
        val cfg = g!!
        val brBlock = cfg.blocks.firstOrNull { it.summary.contains("间接跳转") }
        assertNotNull("br x16 块没有标成间接跳转", brBlock)
        assertTrue("br x16 块不该有任何出边", cfg.edges.none { it.from == brBlock!!.index })
    }

    @Test
    fun withoutReaderIndirectJumpKeepsNoEdge() {
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear())
        assertNotNull(g)
        val cfg = g!!
        val targets = cfg.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue("没有 memReader 时不该凭空猜出 case 目标", 0x1030L !in targets)
        assertTrue(0x1040L !in targets)
        // 也没解析出目标 → 要标间接跳转
        assertTrue(cfg.blocks.any { it.summary.contains("间接跳转") })
    }

    @Test
    fun unreadableTableIsIgnored() {
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear()) { _, _ -> null }
        assertNotNull(g)
        val targets = g!!.edges.map { g.blocks[it.to].addrValue }.toSet()
        assertTrue(0x1030L !in targets)
    }

    @Test
    fun hexToBytesRejectsErrorText() {
        assertNull(AnalysisCfgRebuild.hexToBytes("Cannot read memory"))
        assertEquals(0x48, AnalysisCfgRebuild.hexToBytes("48 65")!![0].toInt())
        assertEquals(2, AnalysisCfgRebuild.hexToBytes("4865")!!.size)
        assertNull(AnalysisCfgRebuild.hexToBytes(""))
    }
}
