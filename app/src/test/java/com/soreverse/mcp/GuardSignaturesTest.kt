package com.soreverse.mcp

import com.soreverse.mcp.core.GuardSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSignaturesTest {

    @Test
    fun scanText_findsAntiDebugAndHook() {
        val text = "读取 TracerPid 后调用 ptrace(PTRACE_TRACEME)；同时检查 XposedBridge 与 frida 痕迹"
        val hits = GuardSignatures.scanText(text)
        val cats = hits.map { it.category }.toSet()
        assertTrue("应命中反调试", cats.contains("anti_debug"))
        assertTrue("应命中 Hook 检测", cats.contains("hook"))
        assertTrue("每条命中都要带建议", hits.all { it.advice.isNotBlank() })
    }

    @Test
    fun scanBytes_worksOnBinaryWithoutDecoding() {
        // 二进制里混入明文特征串（so 常见形态），中间夹杂 0x00
        val raw = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x00) +
            "libfrida".toByteArray() + byteArrayOf(0x00) +
            "27042".toByteArray()
        val hits = GuardSignatures.scanBytes(raw)
        val ev = hits.map { it.evidence }
        assertTrue("应命中 libfrida", ev.contains("libfrida"))
        assertFalse("空输入不应崩溃", GuardSignatures.scanBytes(ByteArray(0)).isNotEmpty())
    }

    @Test
    fun detectPackers_matchesKnownAssets() {
        val names = listOf(
            "AndroidManifest.xml",
            "assets/ijiami.dat",
            "assets/0OO00l111l1l",
            "lib/arm64-v8a/libfoo.so",
            "classes.dex",
        )
        val found = GuardSignatures.detectPackers(names)
        val packers = found.map { it.first }.toSet()
        assertTrue("应识别爱加密", packers.contains("爱加密"))
        assertTrue("应识别腾讯乐固", packers.contains("腾讯乐固"))
        assertFalse("不应误报其他厂商", packers.contains("百度加固"))
    }

    @Test
    fun obfuscationScore_zeroForFrameworkOnlyClasses() {
        val s = GuardSignatures.obfuscationScore(
            listOf("Ljava/lang/String;", "Landroidx/core/app/Activity;", "Lkotlin/collections/ListsKt;")
        )
        assertEquals("框架类不应被计入", 0, s.classCount)
        assertEquals(0, s.score)
        assertEquals("none", s.level)
    }

    @Test
    fun obfuscationScore_highForJunkNames() {
        // 全是 a/b/c/a1/b2 这类重命名后的类名
        val names = listOf(
            "La/a;", "La/b;", "La/c;", "La/d;", "La/e;", "La/f;",
            "Lb/a;", "Lb/b;", "Lb/c;", "Lb/d;", "Lb/e;", "Lb/f;",
            "Lc/a1;", "Lc/b2;", "Lc/c3;", "Lc/d4;",
        )
        val s = GuardSignatures.obfuscationScore(names)
        assertEquals(16, s.classCount)
        assertTrue("短名占比应为 1.0，实际 ${s.shortRatio}", s.shortRatio > 0.99)
        assertTrue("评分应达到 high，实际 ${s.score}", s.score >= 70)
        assertEquals("high", s.level)
        assertTrue("应给出样例", s.sample.isNotEmpty())
    }

    @Test
    fun obfuscationScore_ignoresBusinessNamedClasses() {
        val names = listOf(
            "Lcom/example/app/MainActivity;",
            "Lcom/example/app/net/ApiClient;",
            "Lcom/example/app/data/UserRepository;",
        )
        val s = GuardSignatures.obfuscationScore(names)
        assertEquals(3, s.classCount)
        assertEquals(0, s.shortRatio.toInt())
        assertEquals("none", s.level)
    }

    @Test
    fun isPlainArsc_acceptsValidHeaderAndRejectsGarbage() {
        val ok = byteArrayOf(
            0x02, 0x00, 0x0C, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        assertTrue(GuardSignatures.isPlainArsc(ok))

        val broken = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertFalse(GuardSignatures.isPlainArsc(broken))
        assertFalse("太短也不应崩", GuardSignatures.isPlainArsc(byteArrayOf(1, 2)))
    }

    @Test
    fun looksEncrypted_distinguishesPlainFromHighEntropy() {
        val zeros = ByteArray(128)
        assertFalse(GuardSignatures.looksEncrypted(zeros))
        val varied = ByteArray(128) { (it * 7 % 256).toByte() }
        assertTrue(GuardSignatures.looksEncrypted(varied))
    }

    @Test
    fun countOf_countsOccurrencesWithCap() {
        assertEquals(3, GuardSignatures.countOf("frida frida frida", "frida"))
        assertEquals(0, GuardSignatures.countOf("nothing", "frida"))
        assertEquals(5, GuardSignatures.countOf("aaaaaaaaaa", "a", cap = 5))
    }

    @Test
    fun scanCombos_requiresAllFeaturesPresent() {
        // 只出现一个词：不算检测（逆向工具自己就带这个字样）
        assertTrue(GuardSignatures.scanCombos("这里有 frida 字样，但没有 maps 或端口检测").isEmpty())
        // 两个特征同时出现：判定为 Frida 检测
        val hits = GuardSignatures.scanCombos("先读 /proc/self/maps，再在里面搜 frida")
        assertEquals(1, hits.size)
        assertEquals("detect", hits[0].category)
        assertTrue(hits[0].label.contains("Frida"))
    }

    @Test
    fun scanCombos_detectsSignatureCheck() {
        val hits = GuardSignatures.scanCombos("pm.getPackageInfo(pkg, GET_SIGNATURES) 后取 signatures 比对")
        assertTrue(hits.any { it.label.contains("签名") })
    }

    @Test
    fun fridaPortHits_matchesHexForm() {
        val net = " 0: 0100007F:69A2 00000000:0000 0A ...\n 1: 0100007F:69A3 00000000:0000 0A ..."
        val ports = GuardSignatures.fridaPortHits(net)
        assertTrue(ports.contains("27042"))
        assertTrue(ports.contains("27043"))
        assertTrue(GuardSignatures.fridaPortHits("nothing here").isEmpty())
    }
}
