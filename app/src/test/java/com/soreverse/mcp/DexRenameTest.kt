package com.soreverse.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.soreverse.mcp.engine.DexRenameApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * dex 符号重命名的端到端守护。
 *
 * 不用 mock：真的用 smali 汇编出 dex、真的跑 Rewriter、真的用 DexPool 写回再读一遍 ——
 * 这条链路任何一环坏掉（dexlib2 版本升级改了 rewriter 行为、DexPool 写出丢指令等）都会在这里红。
 *
 * 移植来源：Dex Editor 的 net.dexedit.dex.DexRenameApi（只换了 dexlib2 包名）。
 */
class DexRenameTest {

    private val classA = """
        .class public La/B;
        .super Ljava/lang/Object;
        .field public static f:Lc/D;
        .method public static main()V
            .locals 1
            invoke-static {}, Lc/D;->run()V
            return-void
        .end method
    """.trimIndent()

    private val classB = """
        .class public Lc/D;
        .super Ljava/lang/Object;
        .method public static run()V
            .locals 0
            return-void
        .end method
    """.trimIndent()

    private fun assemble(dir: File): File {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "a.smali").writeText(classA)
        File(src, "b.smali").writeText(classB)
        val out = File(dir, "classes.dex")
        val opts = SmaliOptions().apply { outputDexFile = out.absolutePath }
        Smali.assemble(opts, src.absolutePath)
        return out
    }

    @Test
    fun renameClassCascadesTypeReferences() {
        val dir = File.createTempFile("dexrename", "").let { it.delete(); it.mkdirs(); it }
        try {
            val dex = DexFileFactory.loadDexFile(assemble(dir), Opcodes.getDefault())
            val result = DexRenameApi.renameClass(dex, "c.D", "x.Y", null)
            // 类定义 + 字段类型 + invoke 指令，至少 3 处
            assertTrue("hits=${result.renamedCount}", result.renamedCount >= 3)
            val types = result.dexFile.classes.map { it.type }.toSet()
            assertEquals(setOf("La/B;", "Lx/Y;"), types)
            val field = result.dexFile.classes.first { it.type == "La/B;" }.fields.first()
            assertEquals("Lx/Y;", field.type)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renamePackageMovesWholePrefix() {
        val dir = File.createTempFile("dexrename", "").let { it.delete(); it.mkdirs(); it }
        try {
            val dex = DexFileFactory.loadDexFile(assemble(dir), Opcodes.getDefault())
            val result = DexRenameApi.renamePackage(dex, "a", "z", null)
            assertTrue("hits=${result.renamedCount}", result.renamedCount >= 1)
            val types = result.dexFile.classes.map { it.type }.toSet()
            assertTrue("types=$types", types.contains("Lz/B;"))
            assertTrue("types=$types", !types.contains("La/B;"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renameFieldKeepsTypeAndCascadesDefinition() {
        val dir = File.createTempFile("dexrename", "").let { it.delete(); it.mkdirs(); it }
        try {
            val dex = DexFileFactory.loadDexFile(assemble(dir), Opcodes.getDefault())
            val result = DexRenameApi.renameField(dex, "a.B", "f", "c.D", "g", null)
            assertEquals(1, result.renamedCount)
            val cls = result.dexFile.classes.first { it.type == "La/B;" }
            assertEquals("g", cls.fields.first().name)
            assertEquals("Lc/D;", cls.fields.first().type)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readableTypesConvertToDescriptors() {
        assertEquals("Lcom/foo/Bar;", DexRenameApi.toType("com.foo.Bar"))
        assertEquals("I", DexRenameApi.toSmaliType("int"))
        assertEquals("Ljava/lang/String;", DexRenameApi.toSmaliType("java.lang.String"))
        assertEquals("[I", DexRenameApi.toSmaliType("int[]"))
        assertEquals("V", DexRenameApi.toSmaliType(null))
    }
}
