package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DexSmaliAssist] 的纯 JVM 测试。
 *
 * 这些助手会直接改写用户的 smali 文本，算错一次就把代码改坏，所以：
 * 参数宽度、越界判定、扩充后的文本形状全部要有断言，不能只靠"看起来对"。
 */
class DexSmaliAssistTest {

    // ------------------------------------------------------------ 参数宽度

    @Test
    fun paramRegistersCountsWideTypesAsTwo() {
        // 实例方法：(I J Ljava/lang/String;)V → this + I + J(2) + ref = 5
        assertEquals(5, DexSmaliAssist.paramRegisterCount("IJLjava/lang/String;", isStatic = false))
    }

    @Test
    fun paramRegistersStaticHasNoThis() {
        assertEquals(4, DexSmaliAssist.paramRegisterCount("JD", isStatic = true))
    }

    @Test
    fun paramRegistersArrayCountsAsOne() {
        // [[I 是引用型数组，只占 1 个寄存器；[[D 也一样（数组本身不是宽类型）
        assertEquals(1, DexSmaliAssist.paramRegisterCount("[[I", isStatic = true))
        assertEquals(1, DexSmaliAssist.paramRegisterCount("[[D", isStatic = true))
        assertEquals(2, DexSmaliAssist.paramRegisterCount("[Ljava/lang/String;I", isStatic = true))
        assertEquals(2, DexSmaliAssist.paramRegisterCount("IF", isStatic = true))
    }

    @Test
    fun paramRegistersNoArgs() {
        assertEquals(0, DexSmaliAssist.paramRegisterCount("", isStatic = true))
        assertEquals(1, DexSmaliAssist.paramRegisterCount("", isStatic = false))
    }

    // ---------------------------------------------------------------- 分析

    private val okSmali = """
.method public static add(II)I
    .registers 3

    add-int v0, p0, p1

    return v0
.end method
"""

    @Test
    fun analyzeCleanMethodHasNoOverflow() {
        val r = DexSmaliAssist.analyze(okSmali)
        assertEquals(1, r.size)
        val m = r[0]
        assertEquals(3, m.totalRegs)
        assertEquals(2, m.paramRegs)
        assertEquals(0, m.maxV)
        assertEquals(1, m.maxP)
        assertEquals(0, m.overflow.size)
        assertTrue(m.ok)
    }

    @Test
    fun analyzeDetectsOverflowingVRegister() {
        val bad = """
.method public static bad()V
    .registers 2

    const/4 v5, 0x0

    return-void
.end method
"""
        val m = DexSmaliAssist.analyze(bad).single()
        assertEquals(1, m.overflow.size)
        assertTrue(m.overflow[0].contains("v5"))
        assertTrue(!m.ok)
    }

    @Test
    fun analyzeDetectsOverflowingPRegister() {
        val bad = """
.method public static bad2(I)V
    .registers 2

    move v0, p7

    return-void
.end method
"""
        val m = DexSmaliAssist.analyze(bad).single()
        assertEquals(1, m.paramRegs)
        assertEquals(1, m.overflow.size)
        assertTrue(m.overflow[0].contains("p7"))
    }

    @Test
    fun analyzeLocalsAddsParamWidthToTotal() {
        val s = """
.method public constructor <init>()V
    .locals 2

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method
"""
        val m = DexSmaliAssist.analyze(s).single()
        assertEquals("locals", m.declKind)
        assertEquals(2, m.declared)
        assertEquals(1, m.paramRegs)
        assertEquals(3, m.totalRegs)
        assertEquals(0, m.overflow.size)
    }

    @Test
    fun analyzeHandlesRangeRegisters() {
        val s = """
.method public static fill([I)V
    .registers 4

    const/4 v0, 0x0

    invoke-static {v0 .. v3}, Ljava/util/Arrays;->fill([II)V

    return-void
.end method
"""
        val m = DexSmaliAssist.analyze(s).single()
        assertEquals(3, m.maxV)
        assertEquals(0, m.overflow.size)
    }

    @Test
    fun analyzeIgnoresLabelsAndDirectives() {
        val s = """
.method public static lab()V
    .registers 1

    :cond_0
    return-void
.end method
"""
        val m = DexSmaliAssist.analyze(s).single()
        assertEquals(0, m.overflow.size)
        assertEquals(-1, m.maxV)
    }

    @Test
    fun reportMentionsMethodAndCount() {
        val text = DexSmaliAssist.report(okSmali + "\n" + okSmali.replace("add", "sub"))
        assertTrue(text.contains("共 2 个方法"))
        assertTrue(text.contains("add"))
        assertTrue(text.contains("sub"))
    }

    // ------------------------------------------------------------ 寄存器扩充

    private val expandable = """
.method public onCreate(Landroid/os/Bundle;)V
    .registers 5

    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    return-void
.end method
"""

    @Test
    fun expandRegistersGrowsTotal() {
        val r = DexSmaliAssist.expandRegisters(expandable, caret = 30, delta = 3)
        assertNotNull(r)
        val (text, note) = r!!
        assertTrue(text.contains(".registers 8"))
        assertTrue(!text.contains(".registers 5"))
        assertTrue(note.contains("5 → 8"))
        // p 寄存器是按符号寻址的，扩张后不该去动指令
        assertTrue(text.contains("invoke-super {p0, p1}"))
    }

    @Test
    fun expandRegistersPrefersLocationOfCaret() {
        val two = expandable + "\n" + expandable.replace("onCreate", "onResume")
        // 光标放在第二个方法里
        val caret = two.indexOf("onResume")
        val (text, note) = DexSmaliAssist.expandRegisters(two, caret, delta = 1)!!
        assertEquals(1, Regex("\\.registers 6").findAll(text).count())
        assertEquals(1, Regex("\\.registers 5").findAll(text).count())
        assertTrue(note.contains("第 12 行") || note.contains("→ 6"))
    }

    @Test
    fun expandRegistersHandlesLocals() {
        val s = """
.method public static f()V
    .locals 1

    return-void
.end method
"""
        val (text, _) = DexSmaliAssist.expandRegisters(s, 0, delta = 2)!!
        assertTrue(text.contains(".locals 3"))
    }

    @Test
    fun expandRegistersInsertsWhenMissing() {
        val s = """
.method public static g()V
    return-void
.end method
"""
        val pair = DexSmaliAssist.expandRegisters(s, 0, delta = 4, preferLocals = true)!!
        assertTrue(pair.first.contains(".locals 4"))
        assertTrue(pair.second.contains("新增"))
    }

    @Test
    fun expandRegistersRejectsShrink() {
        assertNull(DexSmaliAssist.expandRegisters(expandable, 0, delta = 0))
        assertNull(DexSmaliAssist.expandRegisters(expandable, 0, delta = -2))
    }

    @Test
    fun lineOfOffsetIsOneBased() {
        val t = "a\nbb\nccc"
        assertEquals(1, DexSmaliAssist.lineOfOffset(t, 0))
        assertEquals(2, DexSmaliAssist.lineOfOffset(t, 2))
        assertEquals(3, DexSmaliAssist.lineOfOffset(t, 6))
        assertEquals(3, DexSmaliAssist.lineOfOffset(t, 999))
    }

    // ------------------------------------------------------------- 补全模板

    @Test
    fun templatesAreNonEmptyAndWellFormed() {
        assertTrue(DexSmaliAssist.templates.isNotEmpty())
        for ((group, items) in DexSmaliAssist.templates) {
            assertTrue(group.isNotBlank())
            assertTrue(items.isNotEmpty())
            for ((label, insert) in items) {
                assertTrue(label.isNotBlank())
                assertTrue(insert.isNotBlank())
            }
        }
    }
}
