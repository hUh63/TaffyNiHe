package com.soreverse.mcp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 去平坦化规划内核的单测：指令重编码（编错一位就转错地方）、安全判定、分发器解析。
 */
class OllvmDeFlatTest {

    private fun leBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun arm64B(from: Long, to: Long): ByteArray {
        val imm = ((to - from) / 4L).toInt() and 0x03FFFFFF
        return leBytes(0x14000000 or imm)
    }

    // ── 编码 / 解码 ──

    @Test
    fun arm64_b_roundtrip() {
        val orig = arm64B(0x1000, 0x2000)
        val t = OllvmDeFlat.decodeTerminator(orig, 0x1000, "arm64")
        assertNotNull(t)
        assertEquals(DeFlatKind.ARM64_B, t!!.kind)
        assertEquals(0x2000L, t.target)

        val re = OllvmDeFlat.encodeBranch(DeFlatKind.ARM64_B, 0x1000, 0x3000)
        assertNotNull(re)
        assertEquals(4, re!!.size)
        val t2 = OllvmDeFlat.decodeTerminator(re, 0x1000, "arm64")
        assertEquals(0x3000L, t2!!.target)
    }

    @Test
    fun arm64_out_of_range_is_null() {
        assertNull(OllvmDeFlat.encodeBranch(DeFlatKind.ARM64_B, 0L, 1L shl 30))
    }

    @Test
    fun arm64_misaligned_is_null() {
        assertNull(OllvmDeFlat.encodeBranch(DeFlatKind.ARM64_B, 0x1000L, 0x1002L))
    }

    @Test
    fun arm64_backward_branch_decodes_signed() {
        // 0x2000 -> 0x1000（向后 0x1000）
        val orig = arm64B(0x2000, 0x1000)
        val t = OllvmDeFlat.decodeTerminator(orig, 0x2000, "arm64")
        assertEquals(0x1000L, t!!.target)
    }

    @Test
    fun x86_jmp_rel32_roundtrip() {
        val from = 0x1000L
        val to = 0x2000L
        val rel = (to - (from + 5)).toInt()
        val orig = byteArrayOf(
            0xE9.toByte(), (rel and 0xFF).toByte(), ((rel ushr 8) and 0xFF).toByte(),
            ((rel ushr 16) and 0xFF).toByte(), ((rel ushr 24) and 0xFF).toByte(),
        )
        val t = OllvmDeFlat.decodeTerminator(orig, from, "x86_64")
        assertNotNull(t)
        assertEquals(DeFlatKind.X86_JMP_REL32, t!!.kind)
        assertEquals(to, t.target)

        val re = OllvmDeFlat.encodeBranch(DeFlatKind.X86_JMP_REL32, from, 0x3000)
        assertEquals(0x3000L, OllvmDeFlat.decodeTerminator(re!!, from, "x86_64")!!.target)
    }

    @Test
    fun x86_rel8_range_checked() {
        assertNull(OllvmDeFlat.encodeBranch(DeFlatKind.X86_JMP_REL8, 0x1000L, 0x1000L + 2 + 200))
        assertNotNull(OllvmDeFlat.encodeBranch(DeFlatKind.X86_JMP_REL8, 0x1000L, 0x1000L + 2 + 100))
    }

    // ── 规划 ──

    private fun blk(i: Int, start: Long, term: DeFlatTerminator?, state: Long?) =
        DeFlatBlock(i, start, start + 16, term, state)

    @Test
    fun plan_retargets_single_successor_blocks() {
        val d = 0x5000L
        val t0 = OllvmDeFlat.decodeTerminator(arm64B(0x1000, d), 0x1000, "arm64")!!
        val t1 = OllvmDeFlat.decodeTerminator(arm64B(0x2000, d), 0x2000, "arm64")!!
        val blocks = listOf(blk(0, 0x1000, t0, 1L), blk(1, 0x2000, t1, 2L))
        val map = mapOf(1L to 0x3000L, 2L to 0x4000L)
        val out = OllvmDeFlat.plan(blocks, d, map, "arm64")
        assertEquals(2, out.size)
        assertTrue(out.all { it.safe })
        assertEquals(0x3000L, out[0].newTarget)
        assertEquals(0x4000L, out[1].newTarget)
        // 新字节解回来必须指向新目标
        assertEquals(0x3000L, OllvmDeFlat.decodeTerminator(out[0].newBytes, 0x1000, "arm64")!!.target)
    }

    @Test
    fun plan_flags_unknown_state_as_unsafe() {
        val d = 0x5000L
        val t0 = OllvmDeFlat.decodeTerminator(arm64B(0x1000, d), 0x1000, "arm64")!!
        val out = OllvmDeFlat.plan(listOf(blk(0, 0x1000, t0, null)), d, emptyMap(), "arm64")
        assertEquals(1, out.size)
        assertTrue(!out[0].safe)
        assertTrue(out[0].reason.contains("未识别"))
    }

    @Test
    fun plan_flags_missing_target_as_unsafe() {
        val d = 0x5000L
        val t0 = OllvmDeFlat.decodeTerminator(arm64B(0x1000, d), 0x1000, "arm64")!!
        val out = OllvmDeFlat.plan(listOf(blk(0, 0x1000, t0, 9L)), d, mapOf(1L to 0x3000L), "arm64")
        assertTrue(!out[0].safe)
        assertTrue(out[0].reason.contains("未解析"))
    }

    @Test
    fun plan_ignores_blocks_not_targeting_dispatcher() {
        val d = 0x5000L
        val t0 = OllvmDeFlat.decodeTerminator(arm64B(0x1000, 0x9000), 0x1000, "arm64")!!
        val out = OllvmDeFlat.plan(listOf(blk(0, 0x1000, t0, 1L)), d, mapOf(1L to 0x3000L), "arm64")
        assertTrue(out.isEmpty())
    }

    // ── 解析 ──

    @Test
    fun parse_insns_from_pdbj() {
        val json = """[
            {"offset":4096,"size":4,"bytes":"1f2003d5","disasm":"nop"},
            {"offset":4100,"size":4,"bytes":"1f000071","disasm":"cmp w0, #3"},
            {"offset":4104,"size":4,"bytes":"00000054","disasm":"b.eq 0x2000"}
        ]"""
        val insns = OllvmDeFlat.parseInsns(json)
        assertEquals(3, insns.size)
        assertEquals(4096L, insns[0].offset)
        assertEquals("b.eq 0x2000", insns[2].disasm)
    }

    @Test
    fun decode_cmp_chain_dispatcher() {
        val json = """[
            {"offset":20480,"size":4,"bytes":"1f000071","disasm":"cmp w0, #3"},
            {"offset":20484,"size":4,"bytes":"01000054","disasm":"b.eq 0x2000"},
            {"offset":20488,"size":4,"bytes":"1f040071","disasm":"cmp w0, #5"},
            {"offset":20492,"size":4,"bytes":"01000054","disasm":"b.eq 0x3000"}
        ]"""
        val map = OllvmDeFlat.decodeCmpChainDispatcher(OllvmDeFlat.parseInsns(json))
        assertEquals(mapOf(3L to 0x2000L, 5L to 0x3000L), map)
    }

    @Test
    fun decode_jump_table_dispatcher_returns_empty() {
        val json = """[
            {"offset":20480,"size":4,"bytes":"00000014","disasm":"b 0x6000"},
            {"offset":20484,"size":4,"bytes":"00000036","disasm":"tbnz w8, #2, 0x5000"}
        ]"""
        assertTrue(OllvmDeFlat.decodeCmpChainDispatcher(OllvmDeFlat.parseInsns(json)).isEmpty())
    }

    @Test
    fun apply_patches_writes_in_place() {
        val base = ByteArray(16)
        val out = OllvmDeFlat.applyPatches(base, listOf(4 to byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
        assertEquals(0xAA.toByte(), out[4])
        assertEquals(0xBB.toByte(), out[5])
        assertEquals(0.toByte(), base[4]) // 原容器不变
    }
}
