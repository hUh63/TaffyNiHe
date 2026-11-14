package com.soreverse.mcp.core

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通道连通性探测：发一个「最小但真实」的请求，看延迟与错误。
 *
 * 为什么不用 `/models`：模型列表接口在很多中转站上是免鉴权/缓存的，测出来"通"但实际对话仍会 401/余额不足。
 * 这里直接发一条 `max_tokens = 1` 的对话请求，代价接近 0，但能验证：网络可达、key 有效、
 * 模型名存在、余额/配额没被拦。
 */
internal object AiProbe {

    data class Result(
        val ok: Boolean,
        val latencyMs: Long,
        val httpCode: Int,
        val message: String,
    )

    private const val MAX_BODY = 64 * 1024

    suspend fun probe(channel: AiChannel, key: String): Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probeBlocking(channel, key) }

    fun probeBlocking(channel: AiChannel, key: String): Result {
        val started = System.currentTimeMillis()
        if (channel.endpoint.isBlank()) return Result(false, 0, 0, "通道没有配端点")
        if (key.isBlank()) return Result(false, 0, 0, "通道没有配 API key")
        val target = endpointFor(channel)
        val body = bodyFor(channel)
        return runCatching {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("Content-Type", "application/json")
            headersFor(channel, key).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream: InputStream? = runCatching {
                if (code in 200..299) conn.inputStream else conn.errorStream
            }.getOrNull()
            val text = stream?.let { readLimited(it, MAX_BODY) }?.toString(Charsets.UTF_8).orEmpty()
            runCatching { stream?.close() }
            runCatching { conn.disconnect() }
            val latency = System.currentTimeMillis() - started
            if (code in 200..299) {
                Result(true, latency, code, "OK")
            } else {
                // 取错误体里的 message 更有用：401 是 key 错，404 是模型/端点错，429 是限流/欠费
                Result(false, latency, code, describeError(code, text))
            }
        }.getOrElse {
            Result(false, System.currentTimeMillis() - started, 0, it.message ?: it.javaClass.simpleName)
        }
    }

    // ───────────── 按协议构造最小请求 ─────────────

    private fun endpointFor(channel: AiChannel): String {
        val base = channel.endpoint.trimEnd('/')
        return when (channel.provider) {
            "anthropic" -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            "gemini" -> {
                val b = if (base.contains("/v1beta")) base else "$base/v1beta"
                "$b/models/${channel.model}:generateContent"
            }
            else -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
    }

    private fun bodyFor(channel: AiChannel): String = when (channel.provider) {
        "anthropic" -> JSONObject()
            .put("model", channel.model)
            .put("max_tokens", 1)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .toString()
        "gemini" -> JSONObject()
            .put(
                "contents",
                org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "ping"))),
                ),
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1))
            .toString()
        else -> JSONObject()
            .put("model", channel.model)
            .put("max_tokens", 1)
            .put("stream", false)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .toString()
    }

    private fun headersFor(channel: AiChannel, key: String): List<Pair<String, String>> {
        val base = when (channel.provider) {
            "anthropic" -> listOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            "gemini" -> listOf("x-goog-api-key" to key)
            else -> listOf("Authorization" to "Bearer $key")
        }
        val custom = runCatching {
            if (channel.headersJson.isBlank()) emptyList()
            else {
                val o = JSONObject(channel.headersJson)
                o.keys().asSequence().map { it to o.optString(it) }.filter { it.second.isNotBlank() }.toList()
            }
        }.getOrDefault(emptyList())
        return base + custom
    }

    private fun describeError(code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { o.optString("message") }
        }.getOrNull().orEmpty().ifBlank { body.take(200) }
        val hint = when (code) {
            401, 403 -> "（key 无效或没权限）"
            404 -> "（端点或模型名不对）"
            429 -> "（限流或余额不足）"
            in 500..599 -> "（上游故障）"
            else -> ""
        }
        return "HTTP $code $hint ${detail.take(200)}".trim()
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
