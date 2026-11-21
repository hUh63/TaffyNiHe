package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorLexer 单测：只验证「该标的标对、不该标的不误标」。
 *
 * 之所以这么细，是因为词法着色一旦错标，整段代码会串成一色 —— 那比没有高亮更难读，
 * 而这种问题在 CI 里除了单测没有任何别的办法提前发现（沙箱没有 Android 渲染）。
 */
class EditorLexerTest {

    private fun tokens(text: String, syntax: EditorLexer.Syntax) =
        EditorLexer.tokenize(text, syntax)

    /** 找出正好覆盖 [needle] 的第一次出现位置的 token。 */
    private fun tokenAt(text: String, needle: String, syntax: EditorLexer.Syntax): EditorLexer.Token? {
        val idx = text.indexOf(needle)
        if (idx < 0) return null
        return tokens(text, syntax).firstOrNull { it.start == idx && it.end == idx + needle.length }
    }

    private fun kindOf(text: String, needle: String, syntax: EditorLexer.Syntax): EditorLexer.Kind? =
        tokenAt(text, needle, syntax)?.kind

    @Test
    fun pythonBasics() {
        val src = """
            # heading comment
            def foo(x):
                s = "text"
                n = 0x1F
                if x is None:
                    return True
        """.trimIndent()
        assertEquals(EditorLexer.Kind.COMMENT, kindOf(src, "# heading comment", EditorLexer.Syntax.PYTHON))
        assertEquals(EditorLexer.Kind.KEYWORD, kindOf(src, "def", EditorLexer.Syntax.PYTHON))
        assertEquals(EditorLexer.Kind.FUNC, kindOf(src, "foo", EditorLexer.Syntax.PYTHON))
        assertEquals(EditorLexer.Kind.STRING, kindOf(src, "\"text\"", EditorLexer.Syntax.PYTHON))
        assertEquals(EditorLexer.Kind.NUMBER, kindOf(src, "0x1F", EditorLexer.Syntax.PYTHON))
        assertEquals(EditorLexer.Kind.CONST, kindOf(src, "None", EditorLexer.Syntax.PYTHON))
        assertEquals(EditorLexer.Kind.CONST, kindOf(src, "True", EditorLexer.Syntax.PYTHON))
        // def 后面的 foo 是函数名，不应再被当成关键字
        assertFalse(tokens(src, EditorLexer.Syntax.PYTHON).any { it.kind == EditorLexer.Kind.KEYWORD && it.start == src.indexOf("foo") })
    }

    @Test
    fun pythonTripleQuoteSpansLines() {
        val src = "x = \"\"\"line1\nline2\"\"\"\ny = 1"
        val ts = tokens(src, EditorLexer.Syntax.PYTHON)
        val first = src.indexOf("\"\"\"")
        // 三引号字符串跨行：按行切成多段，但每段都是 STRING（渲染无差别）
        val head = ts.firstOrNull { it.start == first }
        assertTrue("三引号起始段应是字符串", head != null && head.kind == EditorLexer.Kind.STRING)
        assertEquals(src.indexOf('\n'), head!!.end)
        val second = ts.firstOrNull { it.start == src.indexOf("line2") }
        assertTrue("第二行仍在字符串内", second != null && second.kind == EditorLexer.Kind.STRING)
        // 三引号闭合后 y 不应再被当成字符串
        val y = src.indexOf("y = 1")
        assertFalse(ts.any { it.start <= y && it.end > y })
    }

    @Test
    fun shellCommentNeedsBoundary() {
        // $# 是参数个数，不是注释
        val src = "echo \$#\n# real comment\necho 'a b' \$HOME"
        assertEquals(EditorLexer.Kind.COMMENT, kindOf(src, "# real comment", EditorLexer.Syntax.SHELL))
        val firstHash = src.indexOf("$#")
        assertFalse(
            "紧跟在 \$ 后的 # 不是注释",
            tokens(src, EditorLexer.Syntax.SHELL).any { it.start == firstHash + 1 && it.kind == EditorLexer.Kind.COMMENT },
        )
        assertEquals(EditorLexer.Kind.CONST, kindOf(src, "\$HOME", EditorLexer.Syntax.SHELL))
        assertEquals(EditorLexer.Kind.STRING, kindOf(src, "'a b'", EditorLexer.Syntax.SHELL))
    }

    @Test
    fun cBlockCommentSpansLines() {
        val src = "int a = 1; /* open\nstill comment */ int b = 2;"
        val ts = tokens(src, EditorLexer.Syntax.C)
        val open = src.indexOf("/*")
        // 块注释按行切段：第一段到行尾，第二段从行首到 */
        val head = ts.firstOrNull { it.start == open }
        assertTrue(head != null && head.kind == EditorLexer.Kind.COMMENT)
        assertEquals(src.indexOf('\n'), head!!.end)
        val cont = ts.firstOrNull { it.start == src.indexOf("still") }
        assertTrue(cont != null && cont.kind == EditorLexer.Kind.COMMENT)
        assertEquals(src.indexOf("*/") + 2, cont!!.end)
        assertEquals(EditorLexer.Kind.KEYWORD, kindOf(src, "int", EditorLexer.Syntax.C))
        assertEquals(EditorLexer.Kind.NUMBER, kindOf(src, "1", EditorLexer.Syntax.C))
        // 注释后的代码必须恢复正常着色
        assertEquals(EditorLexer.Kind.NUMBER, kindOf(src, "2", EditorLexer.Syntax.C))
    }

    @Test
    fun jsonLiterals() {
        val src = """{"k": true, "n": 12, "s": "v"}"""
        assertEquals(EditorLexer.Kind.CONST, kindOf(src, "true", EditorLexer.Syntax.JSON))
        assertEquals(EditorLexer.Kind.NUMBER, kindOf(src, "12", EditorLexer.Syntax.JSON))
        assertEquals(EditorLexer.Kind.STRING, kindOf(src, "\"v\"", EditorLexer.Syntax.JSON))
    }

    @Test
    fun xmlTagAttrAndString() {
        val src = """<manifest xmlns:android="http://x">"""
        assertEquals(EditorLexer.Kind.TAG, kindOf(src, "<manifest", EditorLexer.Syntax.XML))
        assertEquals(EditorLexer.Kind.ATTR, kindOf(src, "xmlns:android", EditorLexer.Syntax.XML))
        assertEquals(EditorLexer.Kind.STRING, kindOf(src, "\"http://x\"", EditorLexer.Syntax.XML))
    }

    @Test
    fun smaliDirectiveAndRegister() {
        val src = """
            .method public static main()V
                const/4 v0, 0x1
                # comment
            .end method
        """.trimIndent()
        assertEquals(EditorLexer.Kind.DIRECTIVE, kindOf(src, ".method", EditorLexer.Syntax.SMALI))
        assertEquals(EditorLexer.Kind.TYPE, kindOf(src, "v0", EditorLexer.Syntax.SMALI))
        assertEquals(EditorLexer.Kind.COMMENT, kindOf(src, "# comment", EditorLexer.Syntax.SMALI))
        assertEquals(EditorLexer.Kind.NUMBER, kindOf(src, "0x1", EditorLexer.Syntax.SMALI))
    }

    @Test
    fun assemblyImmediateIsNotComment() {
        // ARM 汇编里 # 是立即数前缀，不能当注释；注释是 ; 与 //
        val src = "mov x0, #0x10 ; set return value"
        assertEquals(EditorLexer.Kind.KEYWORD, kindOf(src, "mov", EditorLexer.Syntax.ASSEMBLY))
        assertEquals(EditorLexer.Kind.TYPE, kindOf(src, "x0", EditorLexer.Syntax.ASSEMBLY))
        assertEquals(EditorLexer.Kind.NUMBER, kindOf(src, "#0x10", EditorLexer.Syntax.ASSEMBLY))
        assertEquals(EditorLexer.Kind.COMMENT, kindOf(src, "; set return value", EditorLexer.Syntax.ASSEMBLY))
    }

    @Test
    fun markdownFenceSpansLines() {
        val src = "# Title\n```kotlin\nval x = 1\n```\ntail"
        val ts = tokens(src, EditorLexer.Syntax.MARKDOWN)
        assertEquals(EditorLexer.Kind.KEYWORD, kindOf(src, "# Title", EditorLexer.Syntax.MARKDOWN))
        val codeStart = src.indexOf("val x = 1")
        assertEquals(EditorLexer.Kind.CODE, ts.firstOrNull { it.start == codeStart }?.kind)
        // 栅栏闭合后不再是代码
        val tail = src.indexOf("tail")
        assertFalse(ts.any { it.start == tail && it.kind == EditorLexer.Kind.CODE })
    }

    @Test
    fun plainReturnsNothing() {
        assertTrue(tokens("anything at all 123 # x", EditorLexer.Syntax.PLAIN).isEmpty())
    }

    @Test
    fun tokenRangesAreMonotonicAndInsideText() {
        val src = "# a\ndef f(x):\n    return \"s\" + str(1)  # c\n"
        val ts = tokens(src, EditorLexer.Syntax.PYTHON)
        var last = -1
        for (t in ts) {
            assertTrue("区间必须递增且不重叠：$t", t.start >= last)
            assertTrue("区间必须落在文本内：$t", t.start >= 0 && t.end <= src.length && t.end > t.start)
            last = t.end
        }
    }
}
