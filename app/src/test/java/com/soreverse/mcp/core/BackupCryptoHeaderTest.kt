package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoHeaderTest {

    private fun header(version: Int, salt: ByteArray, nonce: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("SOMCP_ENC".toByteArray())
        out.write(version)
        out.write(salt.size)
        out.write(salt)
        out.write(nonce.size)
        out.write(nonce)
        out.write(ByteArray(32) { 7 }) // 假密文
        return out.toByteArray()
    }

    @Test
    fun parsesV1Header() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val h = BackupCrypto.parseHeader(header(1, salt, nonce))
        assertEquals(1, h?.version)
        assertTrue(salt.contentEquals(h!!.salt))
        assertTrue(nonce.contentEquals(h.nonce))
        assertEquals("SOMCP_ENC".length + 1 + 1 + 16 + 1 + 12, h.headerBytes)
    }

    @Test
    fun parsesV2Header() {
        val h = BackupCrypto.parseHeader(header(2, ByteArray(16), ByteArray(12)))
        assertEquals(2, h?.version)
    }

    @Test
    fun rejectsUnknownVersion() {
        assertNull(BackupCrypto.parseHeader(header(9, ByteArray(16), ByteArray(12))))
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = header(2, ByteArray(16), ByteArray(12))
        bytes[0] = 'X'.code.toByte()
        assertNull(BackupCrypto.parseHeader(bytes))
    }

    @Test
    fun rejectsTruncatedHeader() {
        val full = header(2, ByteArray(16), ByteArray(12))
        assertNull(BackupCrypto.parseHeader(full.copyOfRange(0, 10)))
        assertNull(BackupCrypto.parseHeader(ByteArray(0)))
    }

    @Test
    fun isEncryptedDetectsMagicOnly() {
        val bytes = header(2, ByteArray(16), ByteArray(12))
        assertTrue(BackupCrypto.isEncrypted(bytes))
        assertTrue(BackupCrypto.isEncryptedStream(bytes.inputStream()))
        assertTrue(!BackupCrypto.isEncrypted("PK\u0003\u0004rest".toByteArray()))
        assertTrue(!BackupCrypto.isEncryptedStream(ByteArray(4).inputStream()))
    }
}
