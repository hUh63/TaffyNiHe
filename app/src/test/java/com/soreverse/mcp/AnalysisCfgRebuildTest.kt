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
 * 三种形态（都是 clang 真实产物）：
 *   · 跳表（switch）—— 见 [jumpTableTargetsBecomeEdges] / [absolutePointerTableIsAlsoResolved]
 *   · 槽表尾跳（PLT / GOT）—— 见 [gotTailCallIsNamed]
 *   · 对象槽尾跳 —— 见 [objectSlotTailCallReportsFieldOffset]
 *
 * 真实 SO（libpython3.12.so / libcrypto）里第 2 种占绝大多数：1600 条 `br` 里 1353 条
 * 都是 `adrp`+`ldr`+`br`（GOT 项），能解出 `memcpy` / `__cxa_finalize` 这类符号名。
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

    // ── 跳表 ──

    @Test
    fun jumpTableTargetsBecomeEdges() {
        val bytes = int32Le(
            (0x1020 - table).toInt(),
            (0x1030 - table).toInt(),
            (0x1040 - table).toInt(),
        )
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear(), memReader = { addr, n ->
            if (addr == table) bytes.copyOf(n.coerceAtMost(bytes.size)) else null
        })
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
        assertFalse(brBlock!!.summary.contains("间接跳转"))
    }

    @Test
    fun absolutePointerTableIsAlsoResolved() {
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
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", lin, memReader = { addr, n ->
            if (addr == table) bytes.copyOf(n.coerceAtMost(bytes.size)) else null
        })
        assertNotNull(g)
        val cfg = g!!
        val targets = cfg.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue(0x1030L in targets)
        assertTrue(0x1040L in targets)
    }

    // ── 槽表尾跳（真实 SO 的主力形态）──

    /**
     * `adrp x8, got; ldr x16, [x8, #0x10]; br x16` —— 槽有重定位符号名，
     * 块摘要要能显示"尾跳 → memcpy"。
     */
    @Test
    fun gotTailCallIsNamed() {
        val lin = listOf(
            0x1000L to "cbz x0, 0x1020",
            0x1004L to "adrp x8, 0x5000",
            0x1008L to "ldr x16, [x8, 0x10]",
            0x100cL to "br x16",
            0x1020L to "ret",
        )
        val relocs = mapOf(0x5010L to "memcpy")
        val cfg = AnalysisCfgRebuild.rebuild(
            "f", "0x1000", lin,
            density = 1f,
            memReader = null,
            slotReader = { addr, scale -> AnalysisCfgRebuild.slotHitOf(addr, null, scale, relocs) },
        )
        assertNotNull(cfg)
        val note = cfg!!.blocks.firstOrNull { it.summary.contains("尾跳") }
        assertNotNull("没有标出尾跳目标", note)
        assertTrue("尾跳目标应是 memcpy，实际：" + note!!.summary, note.summary.contains("memcpy"))
    }

    /**
     * `ldr x8,[x0,#8]; ldr x1,[x8,#0x68]; br x1` —— 从对象 slot 取函数指针，
     * 静态无解，但要能报出字段偏移。
     */
    @Test
    fun objectSlotTailCallReportsFieldOffset() {
        val lin = listOf(
            0x1000L to "cbz x0, 0x1020",
            0x1004L to "ldr x8, [x0, 0x8]",
            0x1008L to "ldr x1, [x8, 0x68]",
            0x100cL to "br x1",
            0x1020L to "ret",
        )
        val cfg = AnalysisCfgRebuild.rebuild(
            "f", "0x1000", lin,
            density = 1f,
            memReader = null,
            slotReader = { addr, scale -> AnalysisCfgRebuild.slotHitOf(addr, null, scale, emptyMap()) },
        )
        assertNotNull(cfg)
        val note = cfg!!.blocks.firstOrNull { it.summary.contains("尾跳") }
        assertNotNull("没有标出对象槽尾跳", note)
        assertTrue("应报字段偏移 0x68，实际：" + note!!.summary, note.summary.contains("obj+0x68"))
    }

    /** 槽内容指向本函数内的地址时，要真的连边（这才是"跟上"）。 */
    @Test
    fun slotPointingInsideFunctionBecomesEdge() {
        val lin = listOf(
            0x1000L to "adrp x8, 0x5000",
            0x1004L to "ldr x9, [x8, 0x10]",
            0x1008L to "br x9",
            0x1010L to "b 0x1000",
        )
        val bytes = int64Le(0x1010L)
        val cfg = AnalysisCfgRebuild.rebuild(
            "f", "0x1000", lin,
            density = 1f,
            memReader = null,
            slotReader = { addr, scale -> AnalysisCfgRebuild.slotHitOf(addr, bytes, scale, emptyMap()) },
        )
        assertNotNull(cfg)
        val targets = cfg!!.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue("槽内容指向 0x1010，应连上这条边", 0x1010L in targets)
    }

    /** 窗口里有 adrp 但不是跳表链 → 一条边都不许出（防误判）。 */
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
        val cfg = AnalysisCfgRebuild.rebuild("f", "0x1000", lin, memReader = { _, _ ->
            int64Le(0x1020L, 0x1030L, 0x1040L)
        })
        assertNotNull(cfg)
        val brBlock = cfg!!.blocks.firstOrNull { it.summary.contains("间接跳转") }
        assertNotNull("br x16 块没有标成间接跳转", brBlock)
        assertTrue("br x16 块不该有任何出边", cfg.edges.none { it.from == brBlock!!.index })
    }

    @Test
    fun withoutReadersNoTargetsInvented() {
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear())
        assertNotNull(g)
        val cfg = g!!
        val targets = cfg.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue(0x1030L !in targets)
        assertTrue(0x1040L !in targets)
        assertTrue(cfg.blocks.any { it.summary.contains("间接跳转") })
    }

    @Test
    fun unreadableTableIsIgnored() {
        val g = AnalysisCfgRebuild.rebuild("f", "0x1000", linear(), memReader = { _, _ -> null })
        assertNotNull(g)
        val cfg = g!!
        val targets = cfg.edges.map { cfg.blocks[it.to].addrValue }.toSet()
        assertTrue(0x1030L !in targets)
    }

    // ── 工具函数 ──

    @Test
    fun parseRelocNamesReadsVaddrAndName() {
        val json = """[{"vaddr":20496,"type":"R_AARCH64_JUMP_SLOT","name":"memcpy"},
            {"vaddr":20504,"type":"R_AARCH64_RELATIVE","name":""}]"""
        val m = AnalysisCfgRebuild.parseRelocNames(json)
        assertEquals("memcpy", m[20496L])
        assertNull(m[20504L])
        assertTrue(AnalysisCfgRebuild.parseRelocNames("not json").isEmpty())
    }

    @Test
    fun slotHitReadsPointerValue() {
        val bytes = int64Le(0x1234L)
        val hit = AnalysisCfgRebuild.slotHitOf(0x5000L, bytes, 0, emptyMap())
        assertNotNull(hit)
        assertEquals(listOf(0x1234L), hit!!.targets)
        // 有重定位名时优先用名字
        val named = AnalysisCfgRebuild.slotHitOf(0x5000L, bytes, 0, mapOf(0x5000L to "strlen"))
        assertEquals("strlen", named!!.label)
    }

    @Test
    fun hexToBytesRejectsErrorText() {
        assertNull(AnalysisCfgRebuild.hexToBytes("Cannot read memory"))
        assertEquals(0x48, AnalysisCfgRebuild.hexToBytes("48 65")!![0].toInt())
        assertEquals(2, AnalysisCfgRebuild.hexToBytes("4865")!!.size)
        assertNull(AnalysisCfgRebuild.hexToBytes(""))
    }
}
