package com.soreverse.mcp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分发器模拟器单测：cmp 链 / 跳表 / x86 三种真实形态，以及「不认识就失败」的保守性。
 */
class DeFlatEmuTest {

    private fun ins(addr: Long, size: Int, disasm: String) =
        DeFlatInsn(addr, size, ByteArray(size), disasm)

    private fun mem(vararg pairs: Pair<Long, ByteArray>): (Long, Int) -> ByteArray? {
        val m = pairs.toMap()
        return { addr, size -> m[addr]?.let { if (it.size >= size) it.copyOf(size) else null } }
    }

    private fun le64(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }
    private fun le32(v: Int) = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

    @Test
    fun cmp_chain_arm64() {
        val insns = listOf(
            ins(0x1000L, 4, "cmp w8, #1"),
            ins(0x1004L, 4, "b.eq 0x3000"),
            ins(0x1008L, 4, "cmp w8, #2"),
            ins(0x100cL, 4, "b.eq 0x4000"),
            ins(0x1010L, 4, "b 0x5000"),
        )
        val e1 = DeFlatEmu(insns) { _, _ -> null }
        e1.setReg("w8", 1L)
        assertEquals(0x3000L, e1.run(0x1000L))

        val e2 = DeFlatEmu(insns) { _, _ -> null }
        e2.setReg("w8", 2L)
        assertEquals(0x4000L, e2.run(0x1000L))

        val e3 = DeFlatEmu(insns) { _, _ -> null }
        e3.setReg("w8", 3L)
        assertEquals(0x5000L, e3.run(0x1000L))
    }

    @Test
    fun jump_table_arm64() {
        val insns = listOf(
            ins(0x2000L, 4, "cmp w8, #3"),
            ins(0x2004L, 4, "b.hi 0x6000"),
            ins(0x2008L, 4, "adrp x9, 0x10000"),
            ins(0x200cL, 4, "add x9, x9, #0x40"),
            ins(0x2010L, 4, "ldr x10, [x9, x8, lsl #3]"),
            ins(0x2014L, 4, "br x10"),
        )
        val e1 = DeFlatEmu(insns, mem(0x10048L to le64(0x4000)))
        e1.setReg("w8", 1L)
        assertEquals(0x4000L, e1.run(0x2000L))

        // 越界状态 → 走 b.hi 默认分支
        val e2 = DeFlatEmu(insns, mem(0x10048L to le64(0x4000)))
        e2.setReg("w8", 9L)
        assertEquals(0x6000L, e2.run(0x2000L))
    }

    @Test
    fun jump_table_x86_rip_relative() {
        val insns = listOf(
            ins(0x1000L, 7, "lea rax, [rip + 0x1234]"),
            ins(0x1007L, 4, "movsxd rcx, [rax + rdx*4]"),
            ins(0x100bL, 3, "add rax, rcx"),
            ins(0x100eL, 2, "jmp rax"),
        )
        // rax = 0x1007 + 0x1234 = 0x223b；表项（有符号 4 字节）= 0x1000 → 目标 0x323b
        val emu = DeFlatEmu(insns, mem(0x223bL to le32(0x1000)))
        emu.setReg("rdx", 0L)
        assertEquals(0x323bL, emu.run(0x1000L))
    }

    @Test
    fun ldr_with_displacement() {
        val insns = listOf(
            ins(0x3000L, 4, "ldr x0, [x1, #0x10]"),
            ins(0x3004L, 4, "br x0"),
        )
        val emu = DeFlatEmu(insns, mem(0x5010L to le64(0x7777)))
        emu.setReg("x1", 0x5000L)
        assertEquals(0x7777L, emu.run(0x3000L))
    }

    @Test
    fun unknown_instruction_fails() {
        val insns = listOf(ins(0x4000L, 4, "fmla v0.4s, v1.4s, v2.4s"))
        val emu = DeFlatEmu(insns) { _, _ -> null }
        assertNull(emu.run(0x4000L))
    }

    @Test
    fun missing_memory_fails() {
        val insns = listOf(
            ins(0x5000L, 4, "ldr x0, [x1, #0x10]"),
            ins(0x5004L, 4, "br x0"),
        )
        val emu = DeFlatEmu(insns) { _, _ -> null }
        emu.setReg("x1", 0x9000L)
        assertNull(emu.run(0x5000L))
        assertNotNull(emu.lastError)
    }

    @Test
    fun mov_and_cbz() {
        val insns = listOf(
            ins(0x6000L, 4, "mov w8, #0"),
            ins(0x6004L, 4, "cbz w8, 0x7000"),
            ins(0x6008L, 4, "b 0x8000"),
        )
        val emu = DeFlatEmu(insns) { _, _ -> null }
        assertEquals(0x7000L, emu.run(0x6000L))
    }
}
