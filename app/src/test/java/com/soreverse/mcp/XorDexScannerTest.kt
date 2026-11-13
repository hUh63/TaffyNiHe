package com.soreverse.mcp

import com.soreverse.mcp.core.XorDexScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.Adler32

class XorDexScannerTest {

    /** 造一个 header 完全合法、校验和与签名都对得上的 dex（只有 header + 少量 body）。 */
    private fun buildDex(bodyLen: Int = 128): ByteArray {
        val size = 112 + bodyLen
        val b = ByteArray(size)
        // magic "dex\n035\0"
        b[0] = 0x64; b[1] = 0x65; b[2] = 0x78; b[3] = 0x0A
        b[4] = 0x30; b[5] = 0x33; b[6] = 0x35; b[7] = 0x00
        putU32(b, 32, size.toLong())      // file_size
        putU32(b, 36, 0x70L)              // header_size
        putU32(b, 40, 0x12345678L)        // endian_tag
        for (i in 112 until size) b[i] = (i * 7 % 251).toByte()
        // signature = sha1(body[32..size])
        val sig = MessageDigest.getInstance("SHA-1").digest(b.copyOfRange(32, size))
        System.arraycopy(sig, 0, b, 12, 20)
        // checksum = adler32(body[12..size])
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

    private fun xorWith(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    @Test
    fun scan_findsPlainDexWithStrongValidation() {
        val dex = buildDex()
        val hits = XorDexScanner.scan(dex, setOf("plain"))
        assertEquals(1, hits.size)
        assertTrue("明文 dex 应通过双校验", hits[0].strongValid)
        assertEquals("plain", hits[0].mode)
        assertEquals("035", hits[0].version)
        assertEquals(0, hits[0].offset)
    }

    @Test
    fun scan_allModes_doesNotDoubleReportKeyZero() {
        // 全模式扫明文 dex 时，xor1 的 k0 会是 0 —— 那是「key=00 异或」等价于没加密，
        // 必须由 plain 独占，否则同一段数据报两次、链路里也落两份产物。
        val dex = buildDex()
        val hits = XorDexScanner.scan(dex, setOf("plain", "xor1", "xorn"))
        assertEquals(1, hits.size)
        assertEquals("plain", hits[0].mode)
    }

    @Test
    fun scan_findsSingleByteXor() {
        val dex = buildDex()
        val enc = xorWith(dex, byteArrayOf(0x5A))
        val hits = XorDexScanner.scan(enc, setOf("xor1"))
        assertEquals(1, hits.size)
        assertEquals("xor1", hits[0].mode)
        assertEquals("5a", hits[0].keyHex)
        assertTrue(hits[0].strongValid)
        val back = XorDexScanner.extract(enc, hits[0])
        assertNotNull(back)
        assertTrue(back!!.contentEquals(dex))
    }

    @Test
    fun scan_findsMultiByteXor() {
        val dex = buildDex()
        val key = byteArrayOf(0x13, 0x37, 0x0B)
        val enc = xorWith(dex, key)
        val hits = XorDexScanner.scan(enc, setOf("xorn"))
        assertEquals(1, hits.size)
        assertEquals("xorN", hits[0].mode)
        assertEquals("13370b", hits[0].keyHex)
        assertTrue(hits[0].strongValid)
    }

    @Test
    fun scan_ignoresRandomData() {
        val noise = ByteArray(4096) { ((it * 37 + 11) % 256).toByte() }
        assertTrue(XorDexScanner.scan(noise).isEmpty())
    }

    @Test
    fun scan_flagsStructureOnlyHitWhenChecksumBroken() {
        val dex = buildDex()
        dex[200] = (dex[200].toInt() xor 0xFF).toByte() // 改 body，校验和与签名都失效
        val hits = XorDexScanner.scan(dex, setOf("plain"))
        assertEquals(1, hits.size)
        assertTrue("结构合法但双校验不过：不应算 strongValid", !hits[0].strongValid)
        assertTrue(hits[0].note.contains("校验和"))
    }

    @Test
    fun scan_respectsOffsetInLargerBuffer() {
        val dex = buildDex()
        val blob = ByteArray(64) { 0x00 } + dex + ByteArray(64) { 0x11 }
        val hits = XorDexScanner.scan(blob, setOf("plain"))
        assertEquals(1, hits.size)
        assertEquals(64, hits[0].offset)
    }

    @Test
    fun hexHelpersRoundTrip() {
        val key = byteArrayOf(0x00, 0x1F, 0xA5.toByte(), 0xFF.toByte())
        val h = XorDexScanner.hex(key)
        assertEquals("001fa5ff", h)
        assertTrue(XorDexScanner.hexToBytes(h).contentEquals(key))
        assertEquals(0, XorDexScanner.hexToBytes("zz").size)
        assertEquals(0, XorDexScanner.hexToBytes("abc").size)
    }
}
