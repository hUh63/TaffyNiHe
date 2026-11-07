package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `br xN`（AArch64 PIC 跳表）解析的回归测试。
 *
 * 真实形态（switch）：
 * ```
 * cmp  w0, #2
 * b.hi 0x1100
 * adrp x8, 0x5000
 * add  x8, x8, #0x40      ; 表地址 = 0x5040
 * ldrsw x9, [x8, w0, uxtw #2]
 * add  x8, x8, x9
 * br   x8                 ; 目标 = 表基址 + 表项（有符号 32 位）
 * ```
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

    /** 表项是相对表基址的有符号偏移（PIC 跳表），全部为负。 */
    private fun tableBytes(): ByteArray = int32Le(
        (0x1020 - table).toInt(),
        (0x1030 - table).toInt(),
        (0x1040 - table).toInt(),
    )

    @Test
    fun jumpTableTargetsBecomeEdges() {
        val reader: (Long, Int) -> ByteArray? = { addr, n ->
            if (addr == table) tableBytes().copyOf(n.coerceAtMost(12)) else null
        }
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear(), memReader = reader)
        assertNotNull("跳表解析失败时不应整个放弃重建", g)
        val cfg = g!!
        // br x8 属于从 0x1008 起的那一块（b.hi 的落空分支），其出边应指向 3 个 case
        val brBlockIndex = cfg.blocks.indexOfFirst { b ->
            cfg.edges.any { e ->
                e.from == b.index && e.to in cfg.blocks.indices &&
                    cfg.blocks[e.to].addrValue.let { it == 0x1020L || it == 0x1030L || it == 0x1040L }
            }
        }
        assertTrue("没有块经 br x8 连到 case 块", brBlockIndex >= 0)
        val targets = cfg.edges
            .filter { it.from == brBlockIndex }
            .map { cfg.blocks[it.to].addrValue }
            .toSet()
        assertEquals(setOf(0x1020L, 0x1030L, 0x1040L), targets)
        // case 块都必须存在（br 的目标要成为块起点）
        assertTrue(cfg.blocks.any { it.addrValue == 0x1030L })
        assertTrue(cfg.blocks.any { it.addrValue == 0x1040L })
    }

    @Test
    fun withoutReaderIndirectJumpKeepsNoEdge() {
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear())
        assertNotNull(g)
        val cfg = g!!
        val targets = cfg.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue("没有 memReader 时不该凭空猜出 case 目标", 0x1030L !in targets)
        assertTrue(0x1040L !in targets)
    }

    @Test
    fun unreadableTableIsIgnored() {
        // rizin 读失败时回的是文本，不能被当成字节
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
