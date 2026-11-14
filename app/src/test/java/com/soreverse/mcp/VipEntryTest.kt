package com.soreverse.mcp

import com.soreverse.mcp.core.VipEntryScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会员入口扫描的纯逻辑测试：token 化、关键词匹配、类型转换、生成物。
 *
 * 不构造真 dex —— 解析走 dexlib2，那是库的行为；这里只钉住「我们自己的判断规则」，
 * 因为这些规则一改，候选排序就整体变了。
 */
class VipEntryTest {

    // ───────── token 化 ─────────

    @Test
    fun tokens_splitsCamelAndSnake() {
        assertEquals(listOf("is", "vip"), VipEntryScanner.tokens("isVip"))
        assertEquals(listOf("is", "vip", "expired"), VipEntryScanner.tokens("is_vip_expired"))
        assertEquals(listOf("get", "vip", "expire", "time"), VipEntryScanner.tokens("getVipExpireTime"))
        // smali 的 L 前缀与大写首字母连在一起，拆出来是 "lcom" 而不是 "lc"+"com"
        assertEquals(listOf("lcom", "x", "vip", "manager"), VipEntryScanner.tokens("Lcom/x/VipManager;"))
    }

    // ───────── 关键词匹配 ─────────

    @Test
    fun matchWords_doesNotMatchSubstringsInsideOtherWords() {
        // payload 里含 pay，但它不是支付；裸 contains 会误报
        assertTrue(VipEntryScanner.matchWords(VipEntryScanner.tokens("payload")).isEmpty())
        assertTrue(VipEntryScanner.matchWords(VipEntryScanner.tokens("displayMode")).isEmpty())
    }

    @Test
    fun matchWords_hitsMembershipWords() {
        assertTrue(VipEntryScanner.matchWords(VipEntryScanner.tokens("isVip")).contains("vip"))
        assertTrue(VipEntryScanner.matchWords(VipEntryScanner.tokens("purchaseState")).contains("purchase"))
        assertTrue(VipEntryScanner.matchWords(VipEntryScanner.tokens("expireTime")).contains("expire"))
    }

    @Test
    fun matchWords_collectsCrossGroupHits() {
        val hits = VipEntryScanner.matchWords(VipEntryScanner.tokens("isVipExpired"))
        assertTrue(hits.contains("vip"))
        assertTrue(hits.contains("expired"))
        assertTrue("跨组至少两个词", hits.size >= 2)
    }

    // ───────── 类型转换 ─────────

    @Test
    fun smaliToJavaType_handlesObjectsPrimitivesAndArrays() {
        assertEquals("com.x.VipManager", VipEntryScanner.smaliToJavaType("Lcom/x/VipManager;"))
        assertEquals("boolean", VipEntryScanner.smaliToJavaType("Z"))
        assertEquals("void", VipEntryScanner.smaliToJavaType("V"))
        assertEquals("java.lang.String[]", VipEntryScanner.smaliToJavaType("[Ljava/lang/String;"))
        assertEquals("int[][]", VipEntryScanner.smaliToJavaType("[[I"))
    }

    // ───────── 生成物 ─────────

    private fun methodFinding(sig: String, javaSig: String, score: Int) = VipEntryScanner.Finding(
        kind = "METHOD",
        category = "会员标记",
        owner = "Lcom/x/VipManager;",
        name = "isVip",
        smaliSig = sig,
        javaSig = javaSig,
        hits = listOf("vip"),
        score = score,
    )

    @Test
    fun buildHookScript_emitsTargetsWithoutTemplateArtifacts() {
        val script = VipEntryScanner.buildHookScript(
            "com.example.app",
            listOf(
                methodFinding("Lcom/x/VipManager;->isVip()Z", "boolean isVip()", 6),
                methodFinding("Lcom/x/VipManager;->isVip(I)Z", "boolean isVip(int)", 4),
            ),
        )
        assertTrue(script.contains("var TARGETS = ["))
        assertTrue(script.contains("Lcom/x/VipManager;->isVip()Z"))
        assertTrue(script.contains("frida -U -f com.example.app"))
        assertTrue("脚本里不该残留 Kotlin 模板残留", !script.contains("${'$'}{"))
        assertFalse(script.contains("\$"))
    }

    @Test
    fun buildHookScript_onlyTakesMethodsAndRespectsLimit() {
        val field = VipEntryScanner.Finding("FIELD", "凭证", "Lcom/x/A;", "vipToken", "", "java.lang.String", listOf("vip"), 5)
        val bits = (1..50).map { methodFinding("Lcom/x/A;->isVip$it()Z", "boolean isVip$it()", it) }
        val script = VipEntryScanner.buildHookScript("p", listOf(field) + bits, limit = 3)
        assertTrue("字段不该进 hook 目标", !script.contains("vipToken"))
        assertEquals(3, Regex("Lcom/x/A;->isVip\\d+\\(\\)Z").findAll(script).count())
    }

    @Test
    fun buildReport_showsCallersWhenPresent() {
        val withCaller = methodFinding("Lcom/x/VipManager;->isVip()Z", "boolean isVip()", 6)
            .copy(callers = listOf("Lcom/x/MainActivity;->onCreate()V"))
        val result = VipEntryScanner.ScanResult(
            findings = listOf(withCaller),
            dexCount = 1, classCount = 1, stringCount = 1, elapsedMs = 1, truncated = false,
        )
        val md = VipEntryScanner.buildReport("com.example.app", result)
        assertTrue("报告要写明谁在调用，否则只有命名证据", md.contains("MainActivity;->onCreate()V"))
        assertTrue(md.contains("被谁调用"))
    }

    @Test
    fun buildHookScript_annotatesCallers() {
        val withCaller = methodFinding("Lcom/x/VipManager;->isVip()Z", "boolean isVip()", 6)
            .copy(callers = listOf("Lcom/x/PayActivity;->check()V", "Lcom/x/Settings;->onResume()V"))
        val script = VipEntryScanner.buildHookScript("com.example.app", listOf(withCaller))
        assertTrue(script.contains("←"))
        assertTrue(script.contains("Lcom/x/PayActivity;->check()V"))
    }

    @Test
    fun buildReport_groupsByCategoryAndCarriesDisclaimer() {
        val result = VipEntryScanner.ScanResult(
            findings = listOf(methodFinding("Lcom/x/VipManager;->isVip()Z", "boolean isVip()", 6)),
            dexCount = 1,
            classCount = 10,
            stringCount = 100,
            elapsedMs = 12,
            truncated = false,
        )
        val md = VipEntryScanner.buildReport("com.example.app", result)
        assertTrue(md.contains("# VIP / 会员入口分析报告"))
        assertTrue(md.contains("## 会员标记"))
        assertTrue(md.contains("isVip"))
        assertTrue("必须说明分数只是排序", md.contains("候选排序"))
        assertTrue(md.contains("com.example.app"))
    }
}
