package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iApp 解密密码学原语的参考向量测试。
 * 期望值由原版 aiysss/iapp-decrypt（Python）跑出，用于校验 Kotlin 移植的正确性。
 */
class IappCryptoTest {

    @Test
    fun signedMath() {
        assertEquals(-56, IappCrypto.toSigned(200))
        assertEquals(-128, IappCrypto.toSigned(128))
        assertEquals(-3, IappCrypto.signedDiv(-7, 2))
        assertEquals(-1, IappCrypto.signedMod(-7, 2))
        assertEquals(-3, IappCrypto.signedDiv(7, -2))
        assertEquals(1, IappCrypto.signedMod(7, -2))
    }

    @Test
    fun slkyVectors() {
        assertEquals("401690536475661028c98dc0e8ad25f7", IappCrypto.slky("abc".toByteArray(), "def".toByteArray(), null).toHex())
        assertEquals("3e01feeddc9b8a74f831acec74206140", IappCrypto.slky("abc".toByteArray(), ByteArray(0), null).toHex())
        assertEquals(
            "6071f98def814db98e91577119295938",
            IappCrypto.slky("hello".toByteArray(), IappDecrypt.MAGIC_STRING, IappDecrypt.MAGIC_STRING).toHex(),
        )
        assertEquals(
            "40efee848ec3bad998ad706a7d79bf0f",
            IappCrypto.slky("hello".toByteArray(), "world".toByteArray(), IappDecrypt.BURDEN_XOR_KEY).toHex(),
        )
    }

    @Test
    fun cyclicXorAndAes() {
        assertEquals("030015070a", IappCrypto.cyclicXor("hello".toByteArray(), "key".toByteArray()).toHex())
        val key = "0123456789abcdef".toByteArray()
        val ct = IappCrypto.hexToBytes("92117b7f2e48d1f42ca53ae43d4c93be")
        assertEquals("78545e5f5b155f764849404342", IappCrypto.aesCbcThenXorDecrypt(ct, key).toHex())
        assertEquals("48656c6c6f2069417070212121", IappCrypto.aesCbcDecryptOnly(ct, key).toHex())
    }

    @Test
    fun md5HexVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", IappCrypto.md5Hex("abc"))
    }

    @Test
    fun sokExtraction() {
        val so = ByteArray(20) { 0x41 } + "ABCDEFGHIJQQQQQQQQQQ123456789012XYZ".toByteArray() + ByteArray(5)
        assertEquals("ABCDEFGHIJQQQQQQQQQQ123456789012", IappDecrypt.extractSokFromSo(so))
    }

    @Test
    fun looksLikeIappPlain() {
        assertTrue(IappDecrypt.looksLikeIappPlain("function main()\ncall(a, 'x.yul')\n<View></View>".toByteArray()))
    }
}
