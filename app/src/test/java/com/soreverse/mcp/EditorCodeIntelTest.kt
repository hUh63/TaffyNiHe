package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器输入智能层的单测。
 *
 * 这些行为直接决定「打字手感」：自动配对多补一个字符、回车缩进错一级，用户第一分钟就会发现。
 * 所以每条规则都在这里锁死，而不是靠人肉点。
 */
class EditorCodeIntelTest {

    private val unit = "    "

    /** 便利封装：模拟在 caret 处输入 typed。 */
    private fun type(text: String, caret: Int, typed: String) =
        EditorCodeIntel.applyTyped(text, caret, caret, typed, unit)

    @Test
    fun autoPair_insertsClosingCharAndStaysInside() {
        val r = type("foo", 3, "(")
        assertNotNull(r)
        assertEquals("foo()", r!!.text)
        assertEquals(4, r.caret)
    }

    @Test
    fun autoPair_doesNotDoubleInsertWhenClosedAlready() {
        // 已有 "()"，光标在中间，再输 ')' 应该只是跳过
        val r = type("()", 1, ")")
        assertNotNull(r)
        assertEquals("()", r!!.text)
        assertEquals(2, r.caret)
    }

    @Test
    fun autoPair_doesNotBreakExistingExpression() {
        // 后面紧跟着标识符时不补右括号（否则 (a+b) 里输入 '(' 会变成 (()a+b))
        assertNull(type("a+b)", 0, "("))
    }

    @Test
    fun autoPair_wrapsSelection() {
        val r = EditorCodeIntel.applyTyped("call x", 5, 6, "(", unit)
        assertNotNull(r)
        assertEquals("call (x)", r!!.text)
        assertEquals(8, r.caret)
    }

    @Test
    fun autoPair_doesNotWrapHugeSelection() {
        // 全选（整篇）后输入 `(` 的本意是替换，不能把整个文件包起来
        val big = (1..300).joinToString("\n") { "line $it" }
        assertNull(EditorCodeIntel.applyTyped(big, 0, big.length, "(", unit))
    }

    @Test
    fun autoPair_wrapsMultilineButSmallSelection() {
        val text = "a\nb"
        val r = EditorCodeIntel.applyTyped(text, 0, text.length, "{", unit)
        assertNotNull(r)
        assertEquals("{a\nb}", r!!.text)
    }

    @Test
    fun enter_keepsIndentAndAddsLevelAfterBrace() {
        val r = EditorCodeIntel.applyTyped("    if (a) {", 12, 12, "\n", unit)
        assertNotNull(r)
        assertEquals("    if (a) {\n        ", r!!.text)
        assertEquals(r.text.length, r.caret)
    }

    @Test
    fun enter_insideEmptyBraceExpandsToThreeLines() {
        val r = EditorCodeIntel.applyTyped("if () {}", 7, 7, "\n", unit)
        assertNotNull(r)
        assertEquals("if () {\n    \n}", r!!.text)
        // 光标落在中间那行
        assertEquals("if () {\n    ".length, r.caret)
    }

    @Test
    fun closingBrace_dedentsWhenLineIsBlank() {
        val r = EditorCodeIntel.applyTyped("if () {\n        ", 16, 16, "}", unit)
        assertNotNull(r)
        assertEquals("if () {\n    }", r!!.text)
    }

    @Test
    fun multiCharInputIsLeftToDefaultBehaviour() {
        assertNull(EditorCodeIntel.applyTyped("", 0, 0, "abc", unit))
        assertNull(EditorCodeIntel.applyTyped("", 0, 0, "(", unit, autoPair = false, autoIndent = false))
    }

    @Test
    fun matchBracket_worksFromBothSides() {
        val text = "f(a, [1, 2])"
        val m1 = EditorCodeIntel.matchBracket(text, 2) // 紧跟在 '(' 之后
        assertNotNull(m1)
        assertEquals(1, m1!!.open)
        assertEquals(11, m1!!.close)

        val m2 = EditorCodeIntel.matchBracket(text, 11) // 光标贴在 ')' 之前
        assertNotNull(m2)
        assertEquals(1, m2!!.open)
    }

    @Test
    fun matchBracket_returnsNullOutsideBrackets() {
        assertNull(EditorCodeIntel.matchBracket("abc", 1))
    }

    @Test
    fun diagnoseJson_flagsUnclosedString() {
        val diags = EditorCodeIntel.diagnose("{\n  \"a\": \"x\n}", "JSON")
        assertTrue("未闭合字符串要报出来", diags.any { it.message.contains("字符串未闭合") })
    }

    @Test
    fun diagnoseJson_flagsMismatchedBracket() {
        val diags = EditorCodeIntel.diagnose("{\"a\": [1, 2}", "JSON")
        assertTrue(diags.isNotEmpty())
    }

    @Test
    fun diagnoseJson_cleanTextHasNoDiags() {
        val diags = EditorCodeIntel.diagnose("{\n  \"a\": [1, 2]\n}", "JSON")
        assertEquals(0, diags.size)
    }

    @Test
    fun diagnoseXml_flagsTagMismatch() {
        val diags = EditorCodeIntel.diagnose("<a><b></a>", "XML")
        assertTrue(diags.isNotEmpty())
        assertTrue(diags.first().message.contains("标签不匹配") || diags.first().message.contains("未闭合"))
    }

    @Test
    fun diagnoseXml_handlesGreaterThanInAttribute() {
        // `>` 出现在属性值里不该被当成标签结束
        val diags = EditorCodeIntel.diagnose("<a b=\"x > y\"><c/></a>", "XML")
        assertEquals(0, diags.size)
    }

    @Test
    fun diagnoseXml_ignoresCommentsAndSelfClosing() {
        val diags = EditorCodeIntel.diagnose("<a><!-- <b> --><c/></a>", "XML")
        assertEquals(0, diags.size)
    }

    @Test
    fun diagnoseGeneric_flagsUnclosedParen() {
        val diags = EditorCodeIntel.diagnose("call(1, 2", "JAVA")
        assertTrue(diags.any { it.message.contains("未闭合") })
    }

    @Test
    fun diagnoseReportsLineNumbers() {
        val diags = EditorCodeIntel.diagnose("a\nb\ncall(1", "JAVA")
        assertEquals(3, diags.first().line)
    }

    @Test
    fun enclosingBlock_findsSurroundingBraces() {
        val text = "fun x() {\n    body()\n}"
        val blk = EditorCodeIntel.enclosingBlock(text, text.indexOf("body"))
        assertNotNull(blk)
        assertEquals(text.indexOf('{'), blk!!.first)
        assertEquals(text.lastIndexOf('}'), blk.second)
    }

    @Test
    fun enclosingBlock_returnsNullAtTopLevel() {
        assertNull(EditorCodeIntel.enclosingBlock("fun x() = 1", 4))
    }
}
