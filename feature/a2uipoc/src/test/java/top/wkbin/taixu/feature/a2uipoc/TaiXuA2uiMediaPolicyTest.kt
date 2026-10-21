package top.wkbin.taixu.feature.a2uipoc

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaiXuA2uiMediaPolicyTest {

    @Test
    fun `http and https urls are accepted and other schemes are rejected`() {
        assertTrue(TaiXuA2uiMediaPolicy.httpUrlOrNull("https://example.com/a.png")!!.startsWith("https://example.com/"))
        assertTrue(TaiXuA2uiMediaPolicy.httpUrlOrNull("http://example.com/v.mp4")!!.startsWith("http://"))
        assertNull(TaiXuA2uiMediaPolicy.httpUrlOrNull("javascript:alert(1)"))
        assertNull(TaiXuA2uiMediaPolicy.httpUrlOrNull("file:///sdcard/a.png"))
        assertNull(TaiXuA2uiMediaPolicy.httpUrlOrNull("data:text/html,hi"))
        assertNull(TaiXuA2uiMediaPolicy.httpUrlOrNull("/relative/path"))
        assertNull(TaiXuA2uiMediaPolicy.httpUrlOrNull("https://user:pass@example.com/a"))
        assertNull(TaiXuA2uiMediaPolicy.httpUrlOrNull("https://example.com/a b"))
    }

    @Test
    fun `plural template picks one or other and ignores extra arguments`() {
        val pattern = "{count, plural, one {# item} other {# items}}"
        assertEquals(
            "1 item",
            TaiXuA2uiMediaPolicy.formatMessage(pattern, Locale.ENGLISH, mapOf("count" to 1.0, "extra" to "x")),
        )
        assertEquals(
            "3 items",
            TaiXuA2uiMediaPolicy.formatMessage(pattern, Locale.ENGLISH, mapOf("count" to 3)),
        )
    }

    @Test
    fun `missing plural argument fails and a literal pattern is returned`() {
        val pattern = "{count, plural, other {# items}}"
        val error = runCatching {
            TaiXuA2uiMediaPolicy.formatMessage(pattern, Locale.ENGLISH, emptyMap())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("plain", TaiXuA2uiMediaPolicy.formatMessage("plain", Locale.ENGLISH, emptyMap()))
    }
}
