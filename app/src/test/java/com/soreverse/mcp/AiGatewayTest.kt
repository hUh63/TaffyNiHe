package com.soreverse.mcp

import com.soreverse.mcp.core.AiChannel
import com.soreverse.mcp.core.AiGatewayServer
import com.soreverse.mcp.core.HeavyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 本地 AI 网关的纯逻辑测试（不启服务、不联网）。
 *
 * 覆盖的都是「网关悄悄改错客户端行为」的风险点：model 是否被换掉、认证头是否按 provider 正确、
 * 自定义头是否被解析、token 是否真的生效、以及 usage 能不能从非流式 JSON 和流式 SSE 里都捞到。
 */
class AiGatewayTest {

    // ───────── 请求体改写 ─────────

    @Test
    fun prepareBody_replacesModelAndKeepsEverythingElse() {
        val raw = """{"model":"gpt-4","messages":[{"role":"user","content":"hi"}],"temperature":0.3,"stream":true}"""
        val out = JSONObject(AiGatewayServer.prepareBody(raw, "deepseek-v3"))
        assertEquals("deepseek-v3", out.getString("model"))
        assertEquals(0.3, out.getDouble("temperature"), 0.0001)
        assertTrue(out.getBoolean("stream"))
        assertEquals("hi", out.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun prepareBody_invalidJsonFallsBackToRaw() {
        val raw = "not json at all"
        assertEquals(raw, AiGatewayServer.prepareBody(raw, "m"))
    }

    // ───────── 认证头 ─────────

    @Test
    fun authHeaders_followProvider() {
        assertEquals("Bearer k", AiGatewayServer.authHeaders("openai", "k").first().second)
        val anthropic = AiGatewayServer.authHeaders("anthropic", "k").toMap()
        assertEquals("k", anthropic["x-api-key"])
        assertTrue(anthropic.containsKey("anthropic-version"))
        assertEquals("k", AiGatewayServer.authHeaders("gemini", "k").toMap()["x-goog-api-key"])
    }

    @Test
    fun headerPairs_parsesAndSkipsBlankValues() {
        val pairs = AiGatewayServer.headerPairs("""{"x-a":"1","x-b":"","x-c":"3"}""").toMap()
        assertEquals("1", pairs["x-a"])
        assertEquals("3", pairs["x-c"])
        assertFalse(pairs.containsKey("x-b"))
        assertTrue(AiGatewayServer.headerPairs("{}").isEmpty())
        assertTrue(AiGatewayServer.headerPairs("oops").isEmpty())
    }

    // ───────── 鉴权 ─────────

    @Test
    fun authorized_blankTokenMeansOpen() {
        assertTrue(AiGatewayServer.authorized("", null))
        assertTrue(AiGatewayServer.authorized("", "Bearer whatever"))
    }

    @Test
    fun authorized_matchesBearerCaseInsensitively() {
        assertTrue(AiGatewayServer.authorized("t0ken", "Bearer t0ken"))
        assertTrue(AiGatewayServer.authorized("t0ken", "bearer t0ken"))
        assertTrue(AiGatewayServer.authorized("t0ken", "t0ken"))
        assertFalse(AiGatewayServer.authorized("t0ken", "Bearer other"))
        assertFalse(AiGatewayServer.authorized("t0ken", null))
    }

    // ───────── 用量 ─────────

    @Test
    fun usageOf_readsNonStreamJson() {
        val body = """{"id":"x","usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}"""
        val u = AiGatewayServer.usageOf(body)
        assertEquals(11L, u!!.first)
        assertEquals(7L, u.second)
        assertEquals(18L, u.third)
    }

    @Test
    fun usageOf_readsStreamSseTail() {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n")
            append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}\n\n")
            append("data: [DONE]\n\n")
        }
        val u = AiGatewayServer.usageOf(sse)
        assertEquals(3L, u!!.first)
        assertEquals(4L, u.second)
    }

    @Test
    fun usageOf_returnsNullWhenAbsent() {
        assertNull(AiGatewayServer.usageOf(""))
        assertNull(AiGatewayServer.usageOf("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertNull(AiGatewayServer.usageOf("data: [DONE]"))
    }
    // ───────── 多通道数据结构 ─────────

    @Test
    fun aiChannel_roundTripsThroughJson() {
        val ch = AiChannel(
            id = "ch_1",
            name = "中转站",
            provider = "openai",
            endpoint = "https://relay.example/v1",
            model = "deepseek-v3",
            temperature = 0.6f,
            headersJson = """{"x-a":"1"}""",
            bodyJson = """{"top_p":0.9}""",
        )
        val back = AiChannel.fromJson(ch.toJson())!!
        assertEquals(ch.id, back.id)
        assertEquals("中转站", back.name)
        assertEquals("https://relay.example/v1", back.endpoint)
        assertEquals("deepseek-v3", back.model)
        assertEquals(0.6f, back.temperature, 0.0001f)
        assertEquals("""{"x-a":"1"}""", back.headersJson)
        assertEquals("""{"top_p":0.9}""", back.bodyJson)
    }

    @Test
    fun aiChannel_repairsInvalidProviderAndBlankFields() {
        val o = JSONObject().put("id", "x").put("provider", "mistral").put("name", "").put("headersJson", "")
        val ch = AiChannel.fromJson(o)!!
        assertEquals("openai", ch.provider)
        assertEquals("x", ch.name) // 名字空时回落到 id
        assertEquals("{}", ch.headersJson)
    }

    @Test
    fun aiChannel_missingIdIsRejected() {
        assertNull(AiChannel.fromJson(JSONObject().put("name", "no id")))
    }
    // ───────── 网关按通道分流 ─────────

    private fun ch(id: String, name: String, model: String, provider: String = "openai") =
        AiChannel(id = id, name = name, provider = provider, model = model)

    @Test
    fun pickRoute_withoutPrefixUsesActiveChannel() {
        val a = ch("a", "主力", "gpt-4o")
        val b = ch("b", "备用", "deepseek-v3")
        val r = AiGatewayServer.pickRoute("whatever-model", listOf(a, b), a)
        assertEquals("a", r.channel.id)
        // 没写通道前缀时保留客户端指定的模型（网关只选通道，不改模型选择）
        assertEquals("whatever-model", r.model)
        assertFalse(r.viaPrefix)
        assertNull(r.unknownPrefix)
    }

    @Test
    fun pickRoute_matchesChannelByNameOrId() {
        val a = ch("a", "主力", "gpt-4o")
        val b = ch("b", "relay-b", "deepseek-v3")
        val byName = AiGatewayServer.pickRoute("relay-b/deepseek-r1", listOf(a, b), a)
        assertEquals("b", byName.channel.id)
        assertEquals("deepseek-r1", byName.model)
        assertTrue(byName.viaPrefix)
        val byId = AiGatewayServer.pickRoute("B/kimi-k2", listOf(a, b), a)
        assertEquals("b", byId.channel.id)
        assertEquals("kimi-k2", byId.model)
    }

    @Test
    fun pickRoute_emptyRestUsesChannelModel() {
        val a = ch("a", "主力", "gpt-4o")
        val b = ch("b", "备用", "deepseek-v3")
        val r = AiGatewayServer.pickRoute("备用/", listOf(a, b), a)
        assertEquals("b", r.channel.id)
        assertEquals("deepseek-v3", r.model)
    }

    @Test
    fun pickRoute_unknownPrefixFallsBackToActive() {
        val a = ch("a", "主力", "gpt-4o")
        val r = AiGatewayServer.pickRoute("typo/gpt-4o", listOf(a), a)
        assertEquals("a", r.channel.id)
        // 前缀没命中时原样透传：model 里的 `/` 可能是真实模型名（OpenRouter 的 vendor/model）
        assertEquals("typo/gpt-4o", r.model)
        assertEquals("typo", r.unknownPrefix)
        assertFalse(r.viaPrefix)
    }

    @Test
    fun heavyGate_statsExposeLeaseWindow() {
        val st = HeavyGate.stats()
        assertTrue(st.has("oldestLeaseSec"))
        assertTrue(st.has("nearestExpirySec"))
    }
}
