package com.soreverse.mcp.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 编辑器 AI 助手（可选，复用 AI 深度分析的端点配置）。
 * 双协议: OpenAI 兼容 chat/completions / Anthropic messages。
 * 非流式简单实现——把代码片段+问题发给模型，返回文本解释。
 */
object EditorAiHelper {

    data class AiConfig(val provider: String, val endpoint: String, val apiKey: String, val model: String, val temperature: Float)

    fun config(settings: SettingsStore): AiConfig =
        AiConfig(
            provider = settings.aiProvider,
            endpoint = settings.aiEndpoint.trimEnd('/'),
            apiKey = settings.aiApiKey,
            model = settings.aiModel,
            temperature = settings.aiTemperature,
        )

    fun isReady(c: AiConfig): Boolean = c.apiKey.isNotBlank() && c.endpoint.isNotBlank() && c.model.isNotBlank()

    /**
     * 提问。code 为当前编辑器代码（自动截断），question 为用户问题/指令。
     * 返回模型回复文本；失败抛异常（调用方展示消息）。
     */
    fun ask(c: AiConfig, code: String, question: String, lang: String): String {
        if (!isReady(c)) error("AI 未配置完整（设置 → AI 深度分析：端点/Key/模型）")
        val snippet = if (code.length > 6000) code.take(6000) + "\n... (truncated)" else code
        val sys = "你是嵌入式 Android 逆向工具里的编程助手。用户正在编辑 $lang 代码。" +
            "回答简洁、直接给可用的代码或结论，不要寒暄。用中文回答。"
        val userText = "$question\n\n```$lang\n$snippet\n```"

        return when (c.provider) {
            "anthropic" -> askAnthropic(c, sys, userText)
            else -> askOpenAi(c, sys, userText)
        }
    }

    private fun askOpenAi(c: AiConfig, sys: String, user: String): String {
        val url = if (c.endpoint.endsWith("/chat/completions")) c.endpoint else "${c.endpoint}/chat/completions"
        val body = JSONObject()
            .put("model", c.model)
            .put("temperature", c.temperature)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", user)))
        return post(url, body.toString().toByteArray(), mapOf(
            "Authorization" to "Bearer ${c.apiKey}",
            "Content-Type" to "application/json",
        )) { resp ->
            val choices = JSONObject(resp).optJSONArray("choices")
            choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
        }
    }

    private fun askAnthropic(c: AiConfig, sys: String, user: String): String {
        val url = if (c.endpoint.endsWith("/messages")) c.endpoint else "${c.endpoint}/v1/messages"
        val body = JSONObject()
            .put("model", c.model)
            .put("max_tokens", 2048)
            .put("temperature", c.temperature)
            .put("system", sys)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", user)))
        return post(url, body.toString().toByteArray(), mapOf(
            "x-api-key" to c.apiKey,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json",
        )) { resp ->
            val arr = JSONObject(resp).optJSONArray("content")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString("text", "") }.joinToString("")
        }
    }

    private inline fun post(url: String, body: ByteArray, headers: Map<String, String>, parse: (String) -> String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.doOutput = true
        conn.outputStream.use { it.write(body); it.flush() }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        if (code !in 200..299) error("HTTP $code: ${text.take(300)}")
        val out = parse(text)
        if (out.isBlank()) error("模型返回为空: ${text.take(300)}")
        return out
    }
}
