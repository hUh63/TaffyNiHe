package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多命令串联输出的切分。
 *
 * native 每次 `rzCommand` 都要重建 RzCore 并跑一遍 `rz_core_analysis_all`
 * （6.7MB 的 SO 要 3~10 秒），所以热路径把多条命令拼成 `cmd1; cmd2` 发一次。
 * 输出是几个 JSON 值首尾相接，这里负责切回单个 JSON。
 */
class RzOutputTest {

    @Test
    fun splitsTwoJsonValues() {
        val raw = "{\"a\":1}\n[{\"offset\":1,\"disasm\":\"ret\"}]\n"
        val c = splitJsonChunks(raw)
        assertEquals(2, c.size)
        assertEquals("{\"a\":1}", c[0])
        assertEquals("[{\"offset\":1,\"disasm\":\"ret\"}]", c[1])
    }

    @Test
    fun skipsErrorTextBeforeJson() {
        val raw = "ERROR: Cannot seek\n[]\n"
        val c = splitJsonChunks(raw)
        assertEquals(1, c.size)
        assertEquals("[]", c[0])
    }

    @Test
    fun ignoresBracesInsideStrings() {
        val raw = "[{\"disasm\":\"mov x0, {x1}\",\"n\":\"a]b\"}]"
        val c = splitJsonChunks(raw)
        assertEquals(1, c.size)
        assertEquals(raw, c[0])
    }

    @Test
    fun dropsTruncatedTail() {
        assertTrue(splitJsonChunks("{\"a\":[1,2").isEmpty())
    }

    @Test
    fun handlesNestedArrays() {
        val raw = "[[1,2],[3]]\n{\"x\":[{\"y\":1}]}"
        val c = splitJsonChunks(raw)
        assertEquals(2, c.size)
        assertEquals("[[1,2],[3]]", c[0])
        assertEquals("{\"x\":[{\"y\":1}]}", c[1])
    }
}
