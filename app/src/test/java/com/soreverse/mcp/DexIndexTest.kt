package com.soreverse.mcp

import com.soreverse.mcp.engine.DexIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DexIndex 纯函数测试。
 *
 * 故意不测整 dex 遍历：单测跑在 mockable android.jar 上，没有真实 dex 可用，
 * 遍历逻辑靠 CI 里的真实 APK 手工验证 + 这里的解析/匹配规则保证正确性。
 */
class DexIndexTest {

    @Test
    fun normalizeTypeAcceptsEnglishAndChinese() {
        assertEquals("class", DexIndex.normalizeType("class"))
        assertEquals("class", DexIndex.normalizeType("类名"))
        assertEquals("string", DexIndex.normalizeType("STR"))
        assertEquals("int", DexIndex.normalizeType("整数"))
        assertEquals("method_call", DexIndex.normalizeType("方法调用"))
        assertEquals("method_call", DexIndex.normalizeType("call"))
        assertEquals("new_instance", DexIndex.normalizeType("实例化"))
        assertEquals("field_put", DexIndex.normalizeType("field-write"))
        assertEquals("class", DexIndex.normalizeType(""))
    }

    @Test
    fun normalizeTypeFallsBackToRawLowercase() {
        assertEquals("whatever", DexIndex.normalizeType("Whatever"))
    }

    @Test
    fun parseLiteralHandlesHexNegativeAndSmaliHash() {
        assertEquals(255L, DexIndex.parseLiteral("255"))
        assertEquals(255L, DexIndex.parseLiteral("0xff"))
        assertEquals(255L, DexIndex.parseLiteral("0XFF"))
        assertEquals(255L, DexIndex.parseLiteral("#0xff"))
        assertEquals(-1L, DexIndex.parseLiteral("-1"))
        assertEquals(-255L, DexIndex.parseLiteral("-0xff"))
        assertEquals(2131361810L, DexIndex.parseLiteral("0x7f0a0012"))
        assertEquals(1_000_000L, DexIndex.parseLiteral("1_000_000"))
        assertNull(DexIndex.parseLiteral("abc"))
        assertNull(DexIndex.parseLiteral(""))
        assertNull(DexIndex.parseLiteral("0xzz"))
    }

    @Test
    fun excludePackagesMatchesPrefixOnly() {
        val pkgs = listOf("com.android.", "kotlin.", "com.Example.*")
        assertTrue(DexIndex.isExcluded("Lcom/android/internal/Foo;", pkgs))
        assertTrue(DexIndex.isExcluded("Lkotlin/collections/List;", pkgs))
        assertTrue(DexIndex.isExcluded("Lcom/Example/Bar;", pkgs))
        // 前缀必须按「包」边界，不能被半个词命中
        assertFalse(DexIndex.isExcluded("Lcom/androidx/Foo;", pkgs))
        assertFalse(DexIndex.isExcluded("Lcom/example/Bar;", pkgs))
        assertFalse(DexIndex.isExcluded("Lcom/foo/Bar;", emptyList()))
    }

    @Test
    fun dexEntrySortKeyUsesNumericOrder() {
        assertTrue(DexIndex.isDexEntryName("classes.dex"))
        assertTrue(DexIndex.isDexEntryName("classes12.dex"))
        assertFalse(DexIndex.isDexEntryName("classes.dex.bak"))
        assertFalse(DexIndex.isDexEntryName("classes_v2.dex"))

        assertEquals(1, DexIndex.dexEntrySortKey("classes.dex"))
        assertEquals(2, DexIndex.dexEntrySortKey("classes2.dex"))
        assertEquals(10, DexIndex.dexEntrySortKey("classes10.dex"))
        // 字典序会把 classes10 排在 classes2 前面，数字序必须纠正这一点
        assertTrue(
            DexIndex.dexEntrySortKey("classes2.dex") < DexIndex.dexEntrySortKey("classes10.dex")
        )
        assertEquals(Int.MAX_VALUE, DexIndex.dexEntrySortKey("nope"))
    }

    @Test
    fun typeNamesAndReadableTypes() {
        assertEquals("com.foo.Bar", DexIndex.typeToName("Lcom/foo/Bar;"))
        assertEquals("com.foo.Bar", DexIndex.typeToName("[Lcom/foo/Bar;"))
        assertEquals("I", DexIndex.typeToName("I"))
        assertEquals("", DexIndex.typeToName(null))

        assertEquals("int", DexIndex.readableType("I"))
        assertEquals("int[]", DexIndex.readableType("[I"))
        assertEquals("java.lang.String[][]", DexIndex.readableType("[[Ljava/lang/String;"))
        assertEquals("void", DexIndex.readableType("V"))
        assertEquals("?", DexIndex.readableType(null))
    }

    @Test
    fun formatLiteralShowsHexForSmallValues() {
        assertEquals("255(0xff)", DexIndex.formatLiteral(255))
        assertEquals("-1", DexIndex.formatLiteral(-1))
        assertEquals("100000", DexIndex.formatLiteral(100000))
    }

    @Test
    fun escapeKeepsOneLine() {
        assertEquals("a\\nb", DexIndex.escape("a\nb"))
        assertEquals("a\\\"b", DexIndex.escape("a\"b"))
        assertEquals("a\\u0001b", DexIndex.escape("a" + 1.toChar() + "b"))
        assertTrue(DexIndex.escape("x".repeat(1000)).endsWith("…"))
    }

    @Test
    fun keywordMatcherSubstringAndCase() {
        val ci = DexIndex.KeywordMatcher("sign", caseSensitive = false, regex = false)
        assertTrue(ci.matches("checkSignature"))
        assertTrue(ci.matches("SIGN"))
        assertFalse(ci.matches(""))

        val cs = DexIndex.KeywordMatcher("Sign", caseSensitive = true, regex = false)
        assertTrue(cs.matches("doSign"))
        assertFalse(cs.matches("dosign"))
    }

    @Test
    fun keywordMatcherRegexCompilesOnce() {
        val m = DexIndex.KeywordMatcher("^get[A-Z]", caseSensitive = true, regex = true)
        assertTrue(m.matches("getToken"))
        assertFalse(m.matches("xgetToken"))
        // 忽略大小写时正则应能命中 gettoken 之外的大小写形态
        val m2 = DexIndex.KeywordMatcher("^get[a-z]", caseSensitive = false, regex = true)
        assertTrue(m2.matches("GETTOKEN"))
    }

    @Test
    fun keywordRequiredSetMatchesDesign() {
        // 引用型不需要 keyword（可列全部），名字/字面量型必须要
        assertTrue("string" in DexIndex.KEYWORD_REQUIRED)
        assertTrue("int" in DexIndex.KEYWORD_REQUIRED)
        assertFalse("new_instance" in DexIndex.KEYWORD_REQUIRED)
        assertFalse("method_override" in DexIndex.KEYWORD_REQUIRED)
    }
}
