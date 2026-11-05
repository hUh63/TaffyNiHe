package com.soreverse.mcp

import com.soreverse.mcp.mcp.DexAnalysisTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 包归属判定（[DexAnalysisTools.inPackage]）—— `taffy_dex_package_usage` 的统计范围就靠它，
 * 判错了会直接把「SDK 是否死代码」的结论算反。
 */
class DexPackageUsageTest {

    @Test
    fun matchesClassesDirectlyInPackage() {
        assertTrue(DexAnalysisTools.inPackage("Lcom/x/Foo;", "com/x"))
        assertTrue(DexAnalysisTools.inPackage("Lcom/x/Foo\$Bar;", "com/x"))
        assertTrue(DexAnalysisTools.inPackage("Lcom/x/Foo\$1;", "com/x"))
    }

    @Test
    fun matchesSubpackagesByDefault() {
        assertTrue(DexAnalysisTools.inPackage("Lcom/x/sub/Foo;", "com/x"))
        assertTrue(DexAnalysisTools.inPackage("Lcom/x/sub/deep/Foo;", "com/x"))
    }

    @Test
    fun doesNotMatchSiblingPackageWithSharedPrefix() {
        // com/xy 不属于 com/x —— 前缀匹配不按字符串 startsWith 直接比
        assertFalse(DexAnalysisTools.inPackage("Lcom/xy/Foo;", "com/x"))
        assertFalse(DexAnalysisTools.inPackage("Lcom/xish/Foo;", "com/x"))
    }

    @Test
    fun excludesSubpackagesWhenAsked() {
        assertTrue(DexAnalysisTools.inPackage("Lcom/x/Foo;", "com/x", includeSub = false))
        assertFalse(DexAnalysisTools.inPackage("Lcom/x/sub/Foo;", "com/x", includeSub = false))
    }

    @Test
    fun ignoresNonClassDescriptors() {
        assertFalse(DexAnalysisTools.inPackage("Ljava/lang/String;", "com/x"))
        assertFalse(DexAnalysisTools.inPackage("[Lcom/x/Foo;", "com/x"))   // 数组描述符
        assertFalse(DexAnalysisTools.inPackage("com/x/Foo", "com/x"))      // 缺 L..;
        assertFalse(DexAnalysisTools.inPackage("[I", "com/x"))
    }

    @Test
    fun handlesDefaultPackage() {
        assertFalse(DexAnalysisTools.inPackage("LFoo;", ""))
    }
}
