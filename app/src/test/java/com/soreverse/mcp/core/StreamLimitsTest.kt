package com.soreverse.mcp.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 编辑器打开 `content://` 时的闸门依赖 `openAssetFileDescriptor().length`，而 SAF/云盘
 * provider 常返回 -1。这条测试锁住「上限由流本身保证，与 length 是否可信无关」。
 */
class StreamLimitsTest {

    @Test
    fun readsUnderLimit() {
        val data = ByteArray(1000) { (it and 0xFF).toByte() }
        assertArrayEquals(data, StreamLimits.readBytes(ByteArrayInputStream(data), 1000))
    }

    @Test
    fun rejectsOverLimit() {
        val data = ByteArray(1001) { 7 }
        assertNull(StreamLimits.readBytes(ByteArrayInputStream(data), 1000))
    }

    @Test
    fun emptyStreamIsEmptyArray() {
        val out = StreamLimits.readBytes(ByteArrayInputStream(ByteArray(0)), 1024)
        assertEquals(0, out?.size)
    }

    @Test
    fun decodesText() {
        val bytes = "{\"k\":\"值\"}".toByteArray(Charsets.UTF_8)
        assertEquals("{\"k\":\"值\"}", StreamLimits.readText(ByteArrayInputStream(bytes), 1024))
    }

    @Test
    fun nonPositiveLimitReadsNothing() {
        assertNull(StreamLimits.readBytes(ByteArrayInputStream(ByteArray(8)), 0))
        assertNull(StreamLimits.readBytes(ByteArrayInputStream(ByteArray(8)), -1))
    }
}
