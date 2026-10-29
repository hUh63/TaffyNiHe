package com.soreverse.mcp

import com.soreverse.mcp.engine.DBusMessage
import com.soreverse.mcp.engine.FridaBridge
import com.soreverse.mcp.engine.Gvariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frida spawn 链路上唯一没法靠真机兜底的部分：body 字节编码与 mode 判定。
 *
 * spawn 的 D-Bus 调用是 `Spawn(s program, a{sv} options)`（来自 frida-core
 * `src/host-session-service.vala`），编码格式与已跑通的 `Attach(ua{sv})` 同一套
 * GVariant 写入器。这里把期望字节钉死，避免以后有人改 Gvariant.W 时悄悄改掉对齐。
 */
class FridaSpawnTest {

    @Test
    fun spawnModeAcceptsCommonSpellings() {
        assertTrue(FridaBridge.isSpawnMode("spawn"))
        assertTrue(FridaBridge.isSpawnMode("SPAWN"))
        assertTrue(FridaBridge.isSpawnMode(" spawn "))
        assertTrue(FridaBridge.isSpawnMode("spawn-suspended"))
        assertTrue(FridaBridge.isSpawnMode("spawn_suspended"))
        assertFalse(FridaBridge.isSpawnMode("attach"))
        assertFalse(FridaBridge.isSpawnMode(""))
        assertFalse(FridaBridge.isSpawnMode("attached"))
    }

    @Test
    fun spawnBodyEncodesStringThenEmptyDictionary() {
        val body = FridaBridge.spawnBody("com.a")
        // s "com.a"  → u32 len=5 + "com.a" + NUL（长度字段本身就对齐，无前置填充）
        // a{sv} 空   → 补到 4 对齐后 4 字节 0
        val expected = byteArrayOf(
            5, 0, 0, 0,
            'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), '.'.code.toByte(), 'a'.code.toByte(), 0,
            0, 0,                 // padding to 4-byte boundary
            0, 0, 0, 0,
        )
        assertEquals(expected.toList(), body.toList())
    }

    @Test
    fun spawnBodyPadsBeforeEmptyDictionary() {
        // 长度 1 的 program：str 占 4+1+1=6 字节 → 补 2 字节 → 再 4 字节 0
        val body = FridaBridge.spawnBody("x")
        assertEquals(12, body.size)
        assertEquals(1, body[0].toInt())
        assertEquals('x'.code.toByte(), body[4])
        assertEquals(0, body[5].toInt())
        for (i in 8 until 12) assertEquals(0, body[i].toInt())
    }

    @Test
    fun bodyUintReadsLittleEndianPid() {
        // 1337 = 0x539 → 39 05 00 00
        val msg = DBusMessage(2, 0, 1, emptyMap(), byteArrayOf(0x39, 0x05, 0, 0))
        assertEquals(1337, FridaBridge.bodyUint(msg))
    }

    @Test
    fun bodyUintRejectsShortBody() {
        val msg = DBusMessage(2, 0, 1, emptyMap(), byteArrayOf(1, 2))
        assertEquals(-1, FridaBridge.bodyUint(msg))
    }

    @Test
    fun gvariantStringKeepsFourByteAlignment() {
        val w = Gvariant.W()
        w.u32(7)
        w.str("ab")
        // 1) u32(7)            → 4 字节
        // 2) str("ab") 前面 pad(4) → 已在 4 对齐，不补；再 u32 len=2 + "ab" + NUL
        assertEquals(4 + 4 + 2 + 1, w.b.size())
        val b = w.b.toByteArray()
        assertEquals(2, b[4].toInt())
        assertEquals('a'.code.toByte(), b[8])
        assertEquals(0, b[10].toInt())
    }
}
