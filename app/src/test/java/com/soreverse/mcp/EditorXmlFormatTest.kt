package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XML 格式化单测。
 *
 * 这类格式化的失败方式是「静默地把文件改坏」——缩进错一层、把自闭合标签拆开、把 CDATA 里的
 * 内容当文本重排。所以每条规则都锁死，并且明确：结构不完整时返回 null（调用方不写回）。
 */
class EditorXmlFormatTest {

    @Test
    fun nestsByDepth() {
        val src = "<a><b><c/></b></a>"
        val out = EditorTextOps.formatXml(src)
        assertEquals("<a>\n  <b>\n    <c/>\n  </b>\n</a>", out)
    }

    @Test
    fun selfClosingDoesNotOpenDepth() {
        val out = EditorTextOps.formatXml("<root><item a=\"1\"/><item a=\"2\"/></root>")
        assertEquals("<root>\n  <item a=\"1\"/>\n  <item a=\"2\"/>\n</root>", out)
    }

    @Test
    fun keepsCommentAndCdata() {
        val out = EditorTextOps.formatXml("<a><!-- x --><![CDATA[ y < z ]]></a>")
        assertTrue(out!!.contains("<!-- x -->"))
        assertTrue(out.contains("<![CDATA[ y < z ]]>"))
    }

    @Test
    fun keepsDeclarationOnFirstLine() {
        val out = EditorTextOps.formatXml("<?xml version=\"1.0\"?>\n<a>\n<b/>\n</a>")
        assertEquals("<?xml version=\"1.0\"?>\n<a>\n  <b/>\n</a>", out)
    }

    @Test
    fun returnsNullWhenUnbalanced() {
        assertNull(EditorTextOps.formatXml("<a><b></a>"))
        assertNull(EditorTextOps.formatXml("<a><!-- x </a>"))
    }

    @Test
    fun keepsTextNodeInline() {
        val out = EditorTextOps.formatXml("<a>\n  hello\n</a>")
        assertEquals("<a>\n  hello\n</a>", out)
    }

    @Test
    fun collapsesWhitespaceInsideTag() {
        val out = EditorTextOps.formatXml("<a   b=\"1\"   c=\"2\" ><d/></a>")
        assertEquals("<a b=\"1\" c=\"2\">\n  <d/>\n</a>", out)
    }

    @Test
    fun alreadyFormattedIsStable() {
        val once = EditorTextOps.formatXml("<a><b/></a>")!!
        assertEquals(once, EditorTextOps.formatXml(once))
    }
}
