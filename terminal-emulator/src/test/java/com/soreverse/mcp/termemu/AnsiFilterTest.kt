package com.soreverse.mcp.termemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AnsiFilter 单测（纯字符串逻辑）。 */
class AnsiFilterTest {

    @Test
    fun stripsColors() {
        assertEquals("hello", AnsiFilter.strip("\u001B[1;32mhello\u001B[0m"))
        assertEquals("aB", AnsiFilter.strip("a\u001B[31mB\u001B[39m"))
    }

    @Test
    fun stripsOscTitle() {
        // ESC ] 0 ; title BEL
        assertEquals("done", AnsiFilter.strip("\u001B]0;my title\u0007done"))
        // ESC ] ... ESC \
        assertEquals("done", AnsiFilter.strip("\u001B]2;t\u001B\\done"))
    }

    @Test
    fun handlesCarriageReturn() {
        // 进度条覆盖写：\r 后的内容覆盖前者（我们直接丢弃 \r 保留后续）
        assertEquals("100%", AnsiFilter.strip("1%\r100%"))
        assertEquals("a\nb", AnsiFilter.strip("a\r\nb"))
    }

    @Test
    fun handlesBackspace() {
        assertEquals("ab", AnsiFilter.strip("abc\u0008"))
        assertEquals("", AnsiFilter.strip("\u0008"))
    }

    @Test
    fun dropsBellAndKeepsPlainText() {
        assertEquals("ok", AnsiFilter.strip("ok\u0007"))
        assertEquals("中文 OK", AnsiFilter.strip("中文 OK"))
    }

    @Test
    fun lastNonEmptyLine_picksTail() {
        assertEquals("last", AnsiFilter.lastNonEmptyLine("a\n\nlast\n  \n"))
        assertEquals("", AnsiFilter.lastNonEmptyLine("\n \n"))
    }

    @Test
    fun normalizeNewlines_convertsCrLf() {
        assertEquals("a\nb\nc", AnsiFilter.normalizeNewlines("a\r\nb\rc"))
    }

    @Test
    fun strip_isStableOnPlainInput() {
        val plain = "line1\nline2\n"
        assertTrue(AnsiFilter.strip(plain) == plain)
    }
}
