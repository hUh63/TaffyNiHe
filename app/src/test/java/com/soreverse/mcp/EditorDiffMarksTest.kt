package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** EditorDiffMarks 行级标记单测（纯逻辑）。 */
class EditorDiffMarksTest {

    @Test
    fun noBaseline_returnsEmpty() {
        assertTrue(EditorDiffMarks.diff(null, "a\nb").isEmpty())
    }

    @Test
    fun identical_returnsEmpty() {
        assertTrue(EditorDiffMarks.diff("a\nb\nc", "a\nb\nc").isEmpty())
    }

    @Test
    fun pureAppend_marksAdded() {
        val m = EditorDiffMarks.diff("a\nb", "a\nb\nc\nd")
        assertEquals(EditorDiffMarks.Mark.ADDED, m[3])
        assertEquals(EditorDiffMarks.Mark.ADDED, m[4])
        assertEquals(2, m.size)
    }

    @Test
    fun pureDelete_marksDeletionPoint() {
        val m = EditorDiffMarks.diff("a\nb\nc", "a")
        assertEquals(EditorDiffMarks.Mark.DELETED, m[1])
    }

    @Test
    fun replacedLine_marksModified() {
        val m = EditorDiffMarks.diff("a\nB\nc", "a\nb\nc")
        assertEquals(EditorDiffMarks.Mark.MODIFIED, m[2])
        assertEquals(1, m.size)
    }

    @Test
    fun insertionInside_marksOnlyInsertedLine() {
        val m = EditorDiffMarks.diff("a\nc", "a\nb\nc")
        assertEquals(EditorDiffMarks.Mark.ADDED, m[2])
        assertEquals(1, m.size)
    }

    @Test
    fun mixedChange_isBoundedAndMarksMiddle() {
        val old = (1..20).joinToString("\n") { "line$it" }
        val new = (1..20).joinToString("\n") { if (it == 10) "CHANGED" else "line$it" }
        val m = EditorDiffMarks.diff(old, new)
        assertEquals(EditorDiffMarks.Mark.MODIFIED, m[10])
        assertEquals(1, m.size)
    }
}
