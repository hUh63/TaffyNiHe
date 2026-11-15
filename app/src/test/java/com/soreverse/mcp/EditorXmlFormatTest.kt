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
    fun splitsLongTagIntoOneAttributePerLine() {
        // APK 里解出来的 manifest 就是这个形态：属性多，压成一行反而难读
        val src = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "package=\"com.x.app\" android:versionCode=\"3\">\n<application/>\n</manifest>"
        val out = EditorTextOps.formatXml(src)!!
        assertTrue("长标签要拆行", out.contains("<manifest\n"))
        assertTrue(out.contains("  xmlns:android="))
        assertTrue(out.contains("  package=\"com.x.app\""))
        assertTrue("最后一个属性行要以 > 收尾", out.contains("android:versionCode=\"3\">"))
        assertTrue(out.contains("\n  <application/>"))
    }

    @Test
    fun keepsShortTagOnOneLine() {
        val out = EditorTextOps.formatXml("<a b=\"1\"><c/></a>")
        assertEquals("<a b=\"1\">\n  <c/>\n</a>", out)
    }

    @Test
    fun selfClosingLongTagEndsWithSlash() {
        val out = EditorTextOps.formatXml(
            "<item android:name=\"a.very.long.attribute.value.here\" android:value=\"true\"/>"
        )!!
        assertTrue(out.contains("<item\n"))
        assertTrue("自闭合标签最后一行要是 />", out.trimEnd().endsWith("/>"))
    }

    @Test
    fun keepsQuotedSpacesInsideAttribute() {
        val out = EditorTextOps.formatXml("<a b=\"x y\" c=\"z\"/>")
        assertEquals("<a b=\"x y\" c=\"z\"/>", out)
    }

    @Test
    fun keepsGreaterThanInsideAttribute() {
        // 属性值里出现 '>' 是合法的；按 indexOf('>') 切标签会把它切成半截
        val out = EditorTextOps.formatXml("<a b=\"x > y\"><c/></a>")
        assertEquals("<a b=\"x > y\">\n  <c/>\n</a>", out)
    }

    @Test
    fun alreadyFormattedIsStable() {
        val once = EditorTextOps.formatXml("<a><b/></a>")!!
        assertEquals(once, EditorTextOps.formatXml(once))
    }
}
