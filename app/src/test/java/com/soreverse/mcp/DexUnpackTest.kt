package com.soreverse.mcp

import com.soreverse.mcp.core.AesDexDecryptor
import com.soreverse.mcp.core.DexFixer
import com.soreverse.mcp.core.DexUnpackChain
import com.soreverse.mcp.core.XorDexScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.Adler32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DexUnpackTest {

    // ───────── 造数据 ─────────

    private fun buildDex(bodyLen: Int = 128): ByteArray {
        val size = 112 + bodyLen
        val b = ByteArray(size)
        b[0] = 0x64; b[1] = 0x65; b[2] = 0x78; b[3] = 0x0A
        b[4] = 0x30; b[5] = 0x33; b[6] = 0x35; b[7] = 0x00
        putU32(b, 32, size.toLong())
        putU32(b, 36, 0x70L)
        putU32(b, 40, 0x12345678L)
        for (i in 112 until size) b[i] = (i * 13 % 251).toByte()
        val sig = MessageDigest.getInstance("SHA-1").digest(b.copyOfRange(32, size))
        System.arraycopy(sig, 0, b, 12, 20)
        val adler = Adler32()
        adler.update(b, 12, size - 12)
        putU32(b, 8, adler.value)
        return b
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private val AES_KEY = byteArrayOf(
        0x2B, 0x7E.toByte(), 0x15, 0x16, 0x28, 0xAE.toByte(), 0xD2.toByte(), 0xA6.toByte(),
        0xAB.toByte(), 0xF7.toByte(), 0x15, 0x88.toByte(), 0x09, 0xCF.toByte(), 0x4F, 0x3C,
    )

    private fun aesEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(plain)
    }

    // ───────── AES 表识别 ─────────

    @Test
    fun findTables_detectsStandardSbox() {
        val sbox = byteArrayOf(
            0x63, 0x7C, 0x77, 0x7B, 0xF2.toByte(), 0x6B, 0x6F, 0xC5.toByte(),
            0x30, 0x01, 0x67, 0x2B, 0xFE.toByte(), 0xD7.toByte(), 0xAB.toByte(), 0x76,
            0xCA.toByte(), 0x82.toByte(), 0xC9.toByte(), 0x7D, 0xFA.toByte(), 0x59, 0x47, 0xF0.toByte(),
            0xAD.toByte(), 0xD4.toByte(), 0xA2.toByte(), 0xAF.toByte(), 0x9C.toByte(), 0xA4.toByte(), 0x72, 0xC0.toByte(),
        )
        val blob = ByteArray(64) + sbox
        val t = AesDexDecryptor.findTables(blob)
        assertTrue("应识别出 S-box", t.hasSbox)
        assertEquals(64, t.sboxOffset)
        assertTrue(t.sboxIsStandard)
        assertTrue(t.note.contains("标准"))
    }

    @Test
    fun findTables_reportsNothingForRandomData() {
        val noise = ByteArray(8192) { ((it * 41 + 7) % 256).toByte() }
        val t = AesDexDecryptor.findTables(noise)
        assertFalse(t.hasSbox)
        assertFalse(t.hasInvSbox)
        assertTrue(t.note.contains("没有标准 AES 表"))
    }

    // ───────── key 候选 ─────────

    @Test
    fun keyCandidates_findsEmbeddedKey() {
        val blob = AES_KEY + ByteArray(200) { 0x00 } + buildDex()
        val cands = AesDexDecryptor.keyCandidates(blob, 64)
        assertTrue("应至少给出一个候选", cands.isNotEmpty())
        assertTrue("嵌在开头的 key 应被选中", cands.any { it.hex == XorDexScanner.hex(AES_KEY) })
    }

    @Test
    fun keyCandidates_skipsLowEntropyBlocks() {
        val blob = ByteArray(4096) { 0x41 } // 全是 'A'，不像 key
        assertTrue(AesDexDecryptor.keyCandidates(blob, 64).isEmpty())
    }

    // ───────── 解密 ─────────

    @Test
    fun decryptCbc_roundTrips() {
        val plain = buildDex()
        val iv = ByteArray(16)
        val enc = aesEncrypt(plain, AES_KEY, iv)
        val dec = AesDexDecryptor.decryptCbc(enc, AES_KEY, iv, unpad = false)
        assertNotNull(dec)
        assertTrue(dec!!.contentEquals(plain))
    }

    @Test
    fun autoDecrypt_recoversDexFromAesBlob() {
        val dex = buildDex()
        val iv = ByteArray(16)
        val enc = aesEncrypt(dex, AES_KEY, iv)
        // key 嵌在加密数据前面：模拟"key 和密文放在一起"的常见形态
        val blob = AES_KEY.copyOf() + ByteArray(32) + enc
        val hits = AesDexDecryptor.autoDecrypt(blob, 12)
        assertTrue("应解出至少一个 dex", hits.isNotEmpty())
        val hit = hits.first()
        assertEquals(XorDexScanner.hex(AES_KEY), hit.keyHex)
        assertTrue("mode 应为 cbc（可能带 @起点）", hit.mode.startsWith("cbc"))
        assertTrue("解出的字节应是完整 dex", hit.bytes.size == dex.size)
    }

    // ───────── DexFixer ─────────

    @Test
    fun inspect_acceptsHealthyDex() {
        val r = DexFixer.inspect(buildDex())
        assertTrue(r.checksumOkBefore)
        assertTrue(r.signatureOkBefore)
        assertEquals("头自洽", r.note)
    }

    @Test
    fun fix_repairsBrokenChecksumAndSignature() {
        val dex = buildDex()
        dex[200] = (dex[200].toInt() xor 0x7F).toByte() // 改内容，头和内容不再匹配
        val broken = DexFixer.inspect(dex)
        assertFalse(broken.checksumOkBefore)
        assertFalse(broken.signatureOkBefore)

        val (fixed, report) = DexFixer.fix(dex)
        assertTrue(report.fixed)
        assertTrue("修复后应重算校验和", report.changes.any { it.contains("校验和") || it.contains("checksum") })
        val after = DexFixer.inspect(fixed)
        assertTrue("修复后校验和应正确", after.checksumOkBefore)
        assertTrue("修复后签名应正确", after.signatureOkBefore)
    }

    @Test
    fun fix_truncatesByFileSize() {
        val dex = buildDex()
        val padded = dex + ByteArray(64) { 0x55 }
        val (fixed, report) = DexFixer.fix(padded)
        assertTrue(report.fixed)
        assertEquals(dex.size, fixed.size)
        assertEquals("按 file_size 截断：${padded.size} → ${dex.size}", report.changes.first())
    }

    @Test
    fun fix_rejectsNonDex() {
        val junk = ByteArray(200) { 0x11 }
        val (out, report) = DexFixer.fix(junk)
        assertFalse(report.fixed)
        assertTrue(report.note.contains("magic"))
        assertEquals(200, out.size)
    }

    @Test
    fun findDexHeaders_skipsPrefixAndFindsMultiple() {
        val d1 = buildDex(64)
        val d2 = buildDex(96)
        val blob = ByteArray(37) { 0x22 } + d1 + d2
        val offsets = DexFixer.findDexHeaders(blob)
        assertEquals(2, offsets.size)
        assertEquals(37, offsets[0])
        assertEquals(37 + d1.size, offsets[1])
    }
    // ───────── 一键脱壳链 ─────────

    private fun tempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("chain-test").toFile()

    private fun xor(data: ByteArray, key: Int): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor key).toByte() }

    @Test
    fun chain_recoversPlainAndXorFromDumpDir() {
        val dir = tempDir()
        val plain = buildDex(128)
        val hidden = xor(buildDex(200), 0x5A)
        java.io.File(dir, "mem_0.bin").writeBytes(ByteArray(40) { 0x7 } + plain)
        java.io.File(dir, "mem_1.bin").writeBytes(ByteArray(16) { 0x3 } + hidden)
        java.io.File(dir, "junk.bin").writeBytes(ByteArray(4096) { ((it * 31 + 5) % 256).toByte() })

        val out = java.io.File(dir, "dex-recovered")
        val r = DexUnpackChain.run(dir, out, DexUnpackChain.Options())
        assertEquals(2, r.getInt("writtenCount"))
        assertEquals(2, r.getInt("verifiedCount"))
        assertEquals(3, r.getInt("scannedFiles"))
        assertEquals(2, r.getJSONArray("recovered").length())
        // 产物真的在盘上，且能被解析器接受
        for (i in 0 until r.getJSONArray("recovered").length()) {
            val f = java.io.File(r.getJSONArray("recovered").getJSONObject(i).getString("file"))
            assertTrue("产物应存在: $f", f.isFile)
            assertTrue(f.length() >= 112)
        }
        val stages = r.getJSONArray("stages")
        val names = (0 until stages.length()).map { stages.getJSONObject(it).getString("name") }
        assertTrue(names.contains("scan"))
        assertTrue(names.contains("verify"))
    }

    @Test
    fun chain_skipsItsOwnOutputDirOnRerun() {
        val dir = tempDir()
        java.io.File(dir, "dump.bin").writeBytes(buildDex(128))
        val out = java.io.File(dir, "dex-recovered")
        val first = DexUnpackChain.run(dir, out, DexUnpackChain.Options())
        assertEquals(1, first.getInt("writtenCount"))
        // 第二次跑：产物目录在输入目录里，必须被跳过。原文件仍会被重新处理并覆盖同名产物，
        // 但绝不能「把上一轮产物当输入」导致产物数翻倍。
        val second = DexUnpackChain.run(dir, out, DexUnpackChain.Options())
        assertEquals(1, second.getInt("writtenCount"))
        assertEquals("产物数不应翻倍", 1, out.listFiles()?.size ?: 0)
    }

    @Test
    fun chain_reportsStageWhereItBroke() {
        val dir = tempDir()
        java.io.File(dir, "noise.bin").writeBytes(ByteArray(8192) { ((it * 17 + 3) % 256).toByte() })
        val r = DexUnpackChain.run(dir, java.io.File(dir, "out"), DexUnpackChain.Options())
        assertEquals(0, r.getInt("writtenCount"))
        val stages = r.getJSONArray("stages")
        var scanStatus = ""
        for (i in 0 until stages.length()) {
            if (stages.getJSONObject(i).getString("name") == "scan") scanStatus = stages.getJSONObject(i).getString("status")
        }
        assertEquals("failed", scanStatus)
        assertTrue("应给出下一步建议", r.getString("next").contains("taffy_dex_unpack"))
    }

    @Test
    fun chain_repairsTruncatedDexViaFallback() {
        val dir = tempDir()
        // file_size 被写成比实际数据大 → XorDexScanner.extract 会返回 null，链要走退化路径 + 修头
        val dex = buildDex(96)
        putU32(dex, 32, (dex.size + 4096).toLong())
        java.io.File(dir, "truncated.bin").writeBytes(dex)
        val r = DexUnpackChain.run(dir, java.io.File(dir, "out"), DexUnpackChain.Options())
        assertEquals(1, r.getInt("writtenCount"))
        val item = r.getJSONArray("recovered").getJSONObject(0)
        assertTrue("应被修过头", item.getBoolean("fixed"))
        assertEquals(0, item.getInt("offset"))
    }

    @Test
    fun sanitize_keepsSafeName() {
        assertEquals("mem_0", DexUnpackChain.sanitize("mem_0.bin"))
        assertEquals("a_b", DexUnpackChain.sanitize("a/b.dex"))
        assertEquals("dump", DexUnpackChain.sanitize(""))
    }
}
