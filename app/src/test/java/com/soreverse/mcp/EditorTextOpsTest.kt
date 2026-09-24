package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EditorTextOps 纯逻辑单测（不碰 Android API，也不依赖 org.json）。 */
class EditorTextOpsTest {

    private val text = "alpha\nbeta\ngamma\ndelta"

    @Test
    fun lineOf_and_columnOf() {
        assertEquals(1, EditorTextOps.lineOf(text, 0))
        assertEquals(2, EditorTextOps.lineOf(text, 6))     // 'b' of beta
        assertEquals(4, EditorTextOps.lineOf(text, text.length))
        assertEquals(1, EditorTextOps.columnOf(text, 0))
        assertEquals(2, EditorTextOps.columnOf(text, 1))
        assertEquals(1, EditorTextOps.columnOf(text, 6))   // beta 行首
    }

    @Test
    fun lineStartOffset_bounds() {
        assertEquals(0, EditorTextOps.lineStartOffset(text, 1))
        assertEquals(6, EditorTextOps.lineStartOffset(text, 2))
        assertEquals(11, EditorTextOps.lineStartOffset(text, 3))
        assertEquals(text.length, EditorTextOps.lineStartOffset(text, 99))
    }

    @Test
    fun selectionLineRange_coversBothEnds() {
        assertEquals(2 to 2, EditorTextOps.selectionLineRange(text, 6, 10))
        assertEquals(1 to 3, EditorTextOps.selectionLineRange(text, 0, 12))
        assertEquals(4 to 4, EditorTextOps.selectionLineRange(text, text.length, text.length))
    }

    @Test
    fun indent_addsFourSpaces() {
        val (t, caret) = EditorTextOps.indent("a\nb", 0, 3, outdent = false, useTab = false)
        assertEquals("    a\n    b", t)
        assertEquals(0, caret)
    }

    @Test
    fun indent_usesTabForShell() {
        val (t, _) = EditorTextOps.indent("echo hi", 0, 0, outdent = false, useTab = true)
        assertEquals("\techo hi", t)
    }

    @Test
    fun outdent_removesOneUnit_onlyWhenPresent() {
        val src = "    a\n    b\nc"
        val (t, _) = EditorTextOps.indent(src, 0, src.length, outdent = true, useTab = false)
        assertEquals("a\nb\nc", t)
    }

    @Test
    fun toggleComment_addsThenRemoves() {
        val src = "x = 1\ny = 2"
        val (commented, _) = EditorTextOps.toggleComment(src, 0, src.length, "# ")
        assertEquals("# x = 1\n# y = 2", commented)
        val (restored, _) = EditorTextOps.toggleComment(commented, 0, commented.length, "# ")
        assertEquals(src, restored)
    }

    @Test
    fun moveLines_upAndDown_withBoundary() {
        // "1\n2\n3"：行首偏移分别是 0 / 2 / 4
        val src = "1\n2\n3"
        // 第 2 行上移 → 1 与 2 交换
        assertEquals("2\n1\n3", EditorTextOps.moveLines(src, 2, 2, -1).first)
        // 第 1 行下移 → 1 与 2 交换
        assertEquals("2\n1\n3", EditorTextOps.moveLines(src, 0, 0, 1).first)
        // 已在顶部再上移 → 原样返回
        assertEquals(src, EditorTextOps.moveLines(src, 0, 0, -1).first)
        // 已在底部再下移 → 原样返回
        assertEquals(src, EditorTextOps.moveLines(src, 4, 4, 1).first)
        // 跨两行整体上移（第 2~3 行 → 顶部）
        assertEquals("2\n3\n1", EditorTextOps.moveLines(src, 2, 4, -1).first)
    }

    @Test
    fun duplicateLines_insertsBelow() {
        val (t, _) = EditorTextOps.duplicateLines("a\nb\nc", 0, 0)
        assertEquals("a\na\nb\nc", t)
    }

    @Test
    fun deleteLines_keepsAtLeastOneEmptyLine() {
        // 删第 1 行
        assertEquals("b\nc", EditorTextOps.deleteLines("a\nb\nc", 0, 0).first)
        // 只剩一行时删掉 → 留一个空行
        assertEquals("", EditorTextOps.deleteLines("only", 0, 0).first)
        // 删第 2~3 行（offset 2..4）→ 只剩 a
        assertEquals("a", EditorTextOps.deleteLines("a\nb\nc", 2, 4).first)
    }

    @Test
    fun changeCase_returnsOriginalWhenNoSelection() {
        val (t, caret) = EditorTextOps.changeCase("abc", 1, 1, upper = true)
        assertEquals("abc", t)
        assertEquals(1, caret)
        assertEquals("ABC", EditorTextOps.changeCase("abc", 0, 3, upper = true).first)
        assertEquals("abc", EditorTextOps.changeCase("ABC", 0, 3, upper = false).first)
    }

    @Test
    fun insertAndDeleteBackward() {
        assertEquals("aXb", EditorTextOps.insert("ab", 1, 1, "X").first)
        assertEquals("ab", EditorTextOps.insert("aZZb", 1, 3, "").first)
        assertEquals("ab", EditorTextOps.deleteBackward("acb", 2, 2).first)
        assertEquals("c", EditorTextOps.deleteBackward("abc", 0, 2).first)
        assertEquals("abc", EditorTextOps.deleteBackward("abc", 0, 0).first)
    }

    @Test
    fun moveCaretVertical_keepsColumnWhenPossible() {
        val src = "abcd\nx\nabcdef"
        // 第 2 行第 2 列上移 → 第 1 行第 2 列（offset 1）
        assertEquals(1, EditorTextOps.moveCaretVertical(src, 6, -1))
        // 已在顶部再上移 → 不动
        assertEquals(0, EditorTextOps.moveCaretVertical(src, 0, -1))
    }

    @Test
    fun moveCaretEdge_homeAndEnd() {
        val src = "hello\nworld"
        assertEquals(0, EditorTextOps.moveCaretEdge(src, 3, toEnd = false))
        assertEquals(5, EditorTextOps.moveCaretEdge(src, 3, toEnd = true))
        assertEquals(6, EditorTextOps.moveCaretEdge(src, 8, toEnd = false))
    }

    @Test
    fun formatJson_prettyAndInvalid() {
        assertEquals("{\n  \"a\": 1\n}", EditorTextOps.formatJson("{\"a\":1}"))
        assertEquals("[\n  1\n]", EditorTextOps.formatJson("[1]"))
        assertEquals("{\n  \"a\": {\n    \"b\": [\n      1,\n      2\n    ]\n  }\n}",
            EditorTextOps.formatJson("{\"a\":{\"b\":[1,2]}}"))
        assertEquals("{}", EditorTextOps.formatJson("{}"))
        assertNull(EditorTextOps.formatJson("{oops}"))
        assertNull(EditorTextOps.formatJson(""))
        assertNull(EditorTextOps.formatJson("{\"a\":}"))
        // 字符串里的括号/冒号不能影响结构
        val obj = EditorTextOps.formatJson("{\"k\":\"a:{b}\"}")
        assertNotNull(obj)
        assertTrue(obj!!.contains("\"a:{b}\""))
    }

    @Test
    fun trimTrailing_onlyTrimsLineEnds() {
        assertEquals("a\nb", EditorTextOps.trimTrailing("a  \nb\t"))
    }
}
