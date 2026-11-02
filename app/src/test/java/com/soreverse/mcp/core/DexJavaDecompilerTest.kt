// SPDX-License-Identifier: GPL-3.0-or-later
//
// jadx 移植的端到端验证：现场用 smali 汇编一个真 dex，再用 DexJavaDecompiler 反编译回来。
// 只断言"能出 Java 源码且包含预期逻辑"，不比对整段文本（jadx 版本升级会改格式）。
package com.soreverse.mcp.core

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DexJavaDecompilerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildDex(): File {
        val src = tmp.newFolder("smali_src")
        val smaliFile = File(src, "a/B.smali")
        smaliFile.parentFile?.mkdirs()
        smaliFile.writeText(
            """
            .class public La/B;
            .super Ljava/lang/Object;

            .method public static answer()I
                .registers 1
                const/16 v0, 0x2a
                return v0
            .end method

            .method public static greet()Ljava/lang/String;
                .registers 1
                const-string v0, "hello-jadx"
                return-object v0
            .end method
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val dex = File(tmp.root, "out.dex")
        val opts = SmaliOptions().apply {
            outputDexFile = dex.absolutePath
            apiLevel = 34
            jobs = 1
        }
        val ok = Smali.assemble(opts, listOf(src.absolutePath))
        assertTrue("smali 汇编失败，测试样本没生成", ok)
        assertTrue("dex 没生成", dex.isFile && dex.length() > 0)
        return dex
    }

    @Test
    fun listsClassesFromRealDex() {
        val dex = buildDex()
        val r = DexJavaDecompiler.listClasses(dex.absolutePath, limit = 50)
        assertFalse("不应有错误：" + r.optString("error"), r.has("error"))
        val names = r.getJSONArray("classes").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue("应当列出 a.B，实际：$names", names.any { it == "a.B" })
    }

    @Test
    fun decompilesClassToJava() {
        val dex = buildDex()
        val r = DexJavaDecompiler.decompileClass(dex.absolutePath, "a.B")
        assertFalse("不应有错误：" + r.optString("error"), r.has("error"))
        val code = r.optString("code")
        assertTrue("源码为空", code.isNotEmpty())
        assertTrue("缺少类声明：$code", code.contains("class B"))
        assertTrue("缺少方法 answer", code.contains("answer"))
        // jadx 会把 const/16 折叠成字面量，42 或 0x2a 都可能
        assertTrue("看不到返回值：$code", code.contains("42") || code.contains("0x2a"))
        assertTrue("缺少字符串常量", code.contains("hello-jadx"))
    }

    @Test
    fun unknownClassReportsError() {
        val dex = buildDex()
        val r = DexJavaDecompiler.decompileClass(dex.absolutePath, "a.NotThere")
        assertTrue("不存在的类应当报错", r.has("error"))
    }

    @Test
    fun rejectsMissingFile() {
        val r = DexJavaDecompiler.decompileClass("/definitely/not/here.dex", "a.B")
        assertTrue(r.has("error"))
        assertEquals(false, r.optBoolean("ok", false))
    }
}
