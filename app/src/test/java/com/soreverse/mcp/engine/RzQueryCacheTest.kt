package com.soreverse.mcp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RzQueryCacheTest {

    @Before
    fun reset() {
        RzQueryCache.clear()
    }

    @Test
    fun putAndGetRoundTrip() {
        RzQueryCache.put("k1", "{\"a\":1}")
        assertEquals("{\"a\":1}", RzQueryCache.get("k1"))
        assertNull(RzQueryCache.get("missing"))
    }

    @Test
    fun clearDropsEverything() {
        RzQueryCache.put("k1", "v1")
        RzQueryCache.put("k2", "v2")
        assertEquals(2, RzQueryCache.stats().first)
        RzQueryCache.clear()
        assertEquals(0, RzQueryCache.stats().first)
        assertEquals(0, RzQueryCache.stats().second)
        assertNull(RzQueryCache.get("k1"))
    }

    /** 单条超过 1.5M 字符不缓存：宁可下次重算，也不让一份超大 JSON 常驻。 */
    @Test
    fun oversizeEntryIsNotCached() {
        val big = "x".repeat(1_500_001)
        RzQueryCache.put("big", big)
        assertNull(RzQueryCache.get("big"))
        assertEquals(0, RzQueryCache.stats().second)
    }

    /** 总预算 6M 字符：塞 5 条 1.5M 以内的条目，最早的必须被淘汰，且 total 不能虚高。 */
    @Test
    fun budgetEvictsOldest() {
        val chunk = "y".repeat(1_400_000)
        for (i in 0 until 5) RzQueryCache.put("k$i", chunk)
        assertNull("最早的条目应被淘汰", RzQueryCache.get("k0"))
        assertNotNull("最新的条目应保留", RzQueryCache.get("k4"))
        val total = RzQueryCache.stats().second
        assertTrue("总字符数应控制在预算内, 实际 $total", total <= 6_000_000)
    }

    /** 同一 key 重复 put 要替换而不是累加 total。 */
    @Test
    fun replaceDoesNotDoubleCount() {
        RzQueryCache.put("k", "a".repeat(1_000_000))
        RzQueryCache.put("k", "b".repeat(100))
        assertEquals(100, RzQueryCache.stats().second)
        assertEquals("b".repeat(100), RzQueryCache.get("k"))
    }

    @Test
    fun contentStampChangesWithContent() {
        val a = ByteArray(4096) { it.toByte() }
        val b = a.copyOf()
        assertTrue("相同内容必须同指纹（否则缓存永不命中）", rzContentStamp(a) == rzContentStamp(b))
        b[2000] = (b[2000] + 1).toByte()
        assertTrue("中间改一个字节也必须换指纹（等长原地补丁场景）", rzContentStamp(a) != rzContentStamp(b))
        val c = a.copyOf(a.size + 1)
        assertTrue("长度变化也必须换指纹", rzContentStamp(a) != rzContentStamp(c))
    }
}
