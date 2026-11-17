package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun redactsPlainAssignment() {
        val out = SecretRedactor.redact("password=hunter2 api_key=abcdef123456")
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abcdef123456"))
        assertTrue(out.contains("password=[REDACTED]"))
    }

    @Test
    fun redactsQuotedAndJsonAssignment() {
        val out = SecretRedactor.redact("password=\"hunter2\"")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("password=\"[REDACTED]\""))

        val json = SecretRedactor.redact("""{"api_key":"sk-1234567890abcdefghij","name":"x"}""")
        assertFalse(json.contains("sk-1234567890"))
        assertTrue(json.contains("\"name\":\"x\""))
    }

    @Test
    fun redactsBearerToken() {
        val out = SecretRedactor.redact("Authorization: Bearer abcdefghijklmnop.qrstuv")
        assertFalse(out.contains("abcdefghijklmnop"))
        assertTrue(out.contains("Bearer [REDACTED]"))
    }

    @Test
    fun redactsUrlCredential() {
        val out = SecretRedactor.redact("https://host/path?token=abcd1234efgh&page=2")
        assertFalse(out.contains("abcd1234efgh"))
        assertTrue(out.contains("page=2"))
    }

    @Test
    fun redactsPrivateKeyBlock() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val out = SecretRedactor.redact("cert:\n$pem\ndone")
        assertFalse(out.contains("MIIEowIBAAKCAQEA"))
        assertTrue(out.contains("[REDACTED PRIVATE KEY]"))
    }

    @Test
    fun redactsJwt() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = SecretRedactor.redact("token=$jwt")
        assertFalse(out.contains("dozjgNryP4J3"))
        assertTrue(out.contains("[REDACTED JWT]"))
    }

    @Test
    fun redactsPhoneAndHomePath() {
        val out = SecretRedactor.redact("call 13800138000 from /home/alice/app/build.gradle")
        assertFalse(out.contains("13800138000"))
        assertTrue(out.contains("[PHONE_REDACTED]"))
        assertFalse(out.contains("alice"))
    }

    @Test
    fun keepsNormalText() {
        val text = "fun main() { println(\"hello\") }\n版本 1.5.85，耗时 1234 ms\n"
        assertEquals(text, SecretRedactor.redact(text))
    }

    @Test
    fun redactKnownRemovesExplicitSecrets() {
        val out = SecretRedactor.redactKnown("using token s3cr3t-value-here now", listOf("s3cr3t-value-here"))
        assertFalse(out.contains("s3cr3t-value-here"))
        // 太短的值不处理，避免误伤普通词
        val keep = SecretRedactor.redactKnown("abc def", listOf("abc"))
        assertEquals("abc def", keep)
    }
}
