package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSniffTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun elfIsBinary() {
        val head = bytes(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00) + ByteArray(64)
        assertEquals(FileSniff.Kind.ELF, FileSniff.sniff(head))
        assertTrue(FileSniff.looksBinary(head))
    }

    @Test
    fun dexAndZipAndPeAndImage() {
        assertEquals(FileSniff.Kind.DEX, FileSniff.sniff(bytes(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)))
        assertEquals(FileSniff.Kind.ZIP, FileSniff.sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertEquals(FileSniff.Kind.PE, FileSniff.sniff(bytes(0x4D, 0x5A, 0x90, 0x00)))
        assertEquals(FileSniff.Kind.IMAGE, FileSniff.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals(FileSniff.Kind.PDF, FileSniff.sniff("%PDF-1.7".toByteArray()))
    }

    @Test
    fun chineseUtf8IsText() {
        val text = "这是一个普通的 Kotlin 源文件\nfun main() { println(\"你好\") }\n".toByteArray(Charsets.UTF_8)
        assertEquals(FileSniff.Kind.TEXT, FileSniff.sniff(text))
        assertFalse(FileSniff.looksBinary(text))
    }

    @Test
    fun bomIsText() {
        val utf8 = bytes(0xEF, 0xBB, 0xBF) + "hello".toByteArray()
        val utf16le = bytes(0xFF, 0xFE) + byteArrayOf(0x68, 0x00, 0x69, 0x00)
        assertEquals(FileSniff.Kind.TEXT, FileSniff.sniff(utf8))
        assertEquals(FileSniff.Kind.TEXT, FileSniff.sniff(utf16le))
    }

    @Test
    fun nulByteIsBinary() {
        val data = ByteArray(128) { 'a'.code.toByte() }
        data[40] = 0
        assertEquals(FileSniff.Kind.BINARY, FileSniff.sniff(data))
    }

    @Test
    fun manyControlCharsIsBinary() {
        val data = ByteArray(100) { 0x01 }
        assertEquals(FileSniff.Kind.BINARY, FileSniff.sniff(data))
    }

    @Test
    fun emptyIsText() {
        assertEquals(FileSniff.Kind.TEXT, FileSniff.sniff(ByteArray(0)))
        assertEquals(FileSniff.Kind.TEXT, FileSniff.sniff(ByteArray(16), 0))
    }

    @Test
    fun lengthHonoursEffectiveSize() {
        // 数组里有 ELF 魔数，但有效长度只有 2 —— 不应判定为 ELF
        val data = bytes(0x7F, 0x45, 0x4C, 0x46)
        assertEquals(FileSniff.Kind.TEXT, FileSniff.sniff(data, 2))
        assertEquals(FileSniff.Kind.ELF, FileSniff.sniff(data, 4))
    }

    @Test
    fun hintsMentionTargetPages() {
        assertTrue(FileSniff.hint(FileSniff.Kind.ELF, zh = true).contains("分析"))
        assertTrue(FileSniff.hint(FileSniff.Kind.DEX, zh = true).contains("DEX"))
        assertTrue(FileSniff.hint(FileSniff.Kind.IMAGE, zh = false).isNotEmpty())
    }
}
