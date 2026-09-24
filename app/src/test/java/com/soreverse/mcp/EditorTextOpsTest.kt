package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EditorTextOps 纯逻辑单测（不碰 Android API）。 */
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
        val src = "1\n2\n3"
        val (up, _) = EditorTextOps.moveLines(src, 2, 2, -1)      // 移动第 2 行
        assertEquals("2\n1\n3", up)
        val (down, _) = EditorTextOps.moveLines(src, 0, 0, 1)
        assertEquals("2\n1\n3", down)
        // 已在顶部再上移 → 原样返回
        val (noop, _) = EditorTextOps.moveLines(src, 0, 0, -1)
        assertEquals(src, noop)
        // 已在底部再下移 → 原样返回
        val (noop2, _) = EditorTextOps.moveLines(src, 2, 2, 1)
        assertEquals(src, noop2)
    }

    @Test
    fun duplicateLines_insertsBelow() {
        val (t, _) = EditorTextOps.duplicateLines("a\nb\nc", 0, 0)
        assertEquals("a\na\nb\nc", t)
    }

    @Test
    fun deleteLines_keepsAtLeastOneEmptyLine() {
        assertEquals("b\nc", EditorTextOps.deleteLines("a\nb\nc", 0, 0).first)
        assertEquals("", EditorTextOps.deleteLines("only", 0, 0).first)
        assertEquals("a", EditorTextOps.deleteLines("a\nb\nc", 1, 2).first)
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
    fun formatJson_objectAndArray_andInvalid() {
        val obj = EditorTextOps.formatJson("{\"a\":1}")
        assertNotNull(obj)
        assertTrue(obj!!.contains("\n"))
        val arr = EditorTextOps.formatJson("[1]")
        assertNotNull(arr)
        assertTrue(arr!!.contains("\n"))
        assertNull(EditorTextOps.formatJson("{oops}"))
    }

    @Test
    fun trimTrailing_onlyTrimsLineEnds() {
        assertEquals("a\nb", EditorTextOps.trimTrailing("a  \nb\t"))
    }
}
