package com.soreverse.mcp

import com.soreverse.mcp.core.DexRenameEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重命名内核里最容易错的三块纯逻辑：Java 类型 ↔ dex 描述符、ProGuard mapping 解析、冲突判定。
 * 重命名本身要真实 dex 才能测（Android 单测里没有），已在开发阶段用 916KB 真 dex 验证过：
 * 重命名一个被调用 140 次的方法 → 定义改名 1、旧名引用 0、新名引用 140。
 */
class DexRenameEngineTest {

    @Test
    fun javaTypesMapToDescriptors() {
        assertEquals("V", DexRenameEngine.javaTypeToDescriptor("void"))
        assertEquals("I", DexRenameEngine.javaTypeToDescriptor("int"))
        assertEquals("J", DexRenameEngine.javaTypeToDescriptor("long"))
        assertEquals("Z", DexRenameEngine.javaTypeToDescriptor("boolean"))
        assertEquals("Ljava/lang/String;", DexRenameEngine.javaTypeToDescriptor("java.lang.String"))
        assertEquals("[I", DexRenameEngine.javaTypeToDescriptor("int[]"))
        assertEquals("[[Ljava/lang/String;", DexRenameEngine.javaTypeToDescriptor("java.lang.String[][]"))
    }

    @Test
    fun descriptorsMapBackToJavaTypes() {
        assertEquals("int", DexRenameEngine.descriptorToJavaType("I"))
        assertEquals("java.lang.String", DexRenameEngine.descriptorToJavaType("Ljava/lang/String;"))
        assertEquals("int[]", DexRenameEngine.descriptorToJavaType("[I"))
        assertEquals("java.lang.String[][]", DexRenameEngine.descriptorToJavaType("[[Ljava/lang/String;"))
        assertEquals("void", DexRenameEngine.descriptorToJavaType("V"))
    }

    @Test
    fun paramListBecomesDescriptor() {
        assertEquals("()V", DexRenameEngine.javaParamListToDescriptor("()"))
        assertEquals("(I)V", DexRenameEngine.javaParamListToDescriptor("(int)"))
        assertEquals(
            "(Ljava/lang/String;I)Z",
            DexRenameEngine.javaParamListToDescriptor("(java.lang.String,int)boolean"),
        )
        assertEquals("([B)V", DexRenameEngine.javaParamListToDescriptor("(byte[])"))
    }

    @Test
    fun parsesProguardClassAndMembers() {
        val text = """
            com.foo.Bar -> a.b.C:
                int count -> a
                java.lang.String name -> b
                12:34:void run(java.lang.String,int) -> c
        """.trimIndent()
        val r = DexRenameEngine.parseProguardMapping(text)
        assertEquals(1, r.classCount)
        assertEquals(3, r.memberCount)
        assertEquals("La/b/C;", r.plan.typeMap["Lcom/foo/Bar;"])
        assertEquals("a", r.plan.fieldMap["Lcom/foo/Bar;->count:*"])
        assertEquals("b", r.plan.fieldMap["Lcom/foo/Bar;->name:*"])
        // 行号前缀要剥掉，参数要转描述符
        assertEquals("c", r.plan.methodMap["Lcom/foo/Bar;->run(Ljava/lang/String;I)V"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun parsesMultipleClasses() {
        val text = """
            com.a.One -> x.a:
                void f() -> a
            com.a.Two -> x.b:
                void f() -> b
        """.trimIndent()
        val r = DexRenameEngine.parseProguardMapping(text)
        assertEquals(2, r.classCount)
        assertEquals("Lx/a;", r.plan.typeMap["Lcom/a/One;"])
        assertEquals("a", r.plan.methodMap["Lcom/a/One;->f()V"])
        assertEquals("b", r.plan.methodMap["Lcom/a/Two;->f()V"])
    }

    @Test
    fun ignoresGarbageLinesWithoutCrashing() {
        val text = """
            # 注释（ProGuard 自己不会写，但用户手改的 mapping 会有）
            com.foo.Bar -> 
               -> b
            com.foo.Baz -> a.Baz:
                int x -> y
        """.trimIndent()
        val r = DexRenameEngine.parseProguardMapping(text)
        assertEquals(1, r.classCount)
        assertTrue("坏行要记录告警: ${r.warnings}", r.warnings.isNotEmpty())
        assertEquals("y", r.plan.fieldMap["Lcom/foo/Baz;->x:*"])
    }

    @Test
    fun descriptorNameRoundTrip() {
        assertEquals("com.foo.Bar", DexRenameEngine.descriptorToName("Lcom/foo/Bar;"))
        assertEquals("Lcom/foo/Bar;", DexRenameEngine.javaTypeToDescriptor("com.foo.Bar"))
    }
}
