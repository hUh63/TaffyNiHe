package top.wkbin.taixu.harness.plugins

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.wkbin.taixu.core.tools.ExternalProviderProbe
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ApiProtocol
import top.wkbin.taixu.harness.core.LlmApi

class ExternalProviderConnectionProbe(private val plugins: HarnessPluginRegistry,
    private val client: OkHttpClient, private val json: Json) : ExternalProviderProbe {
    override suspend fun test(providerId: String, model: String) {
        val adapter = plugins.providerOverrides(client, json).entries.singleOrNull { it.key.second == providerId }?.value
            ?: error("外部 Provider 不存在或尚未安装")
        val config = ModelConfig(providerId, providerId, model, "https://plugin.invalid", null,
            protocol = if (adapter.api == LlmApi.ANTHROPIC_MESSAGES) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI,
            responseApiEnabled = adapter.api == LlmApi.OPENAI_RESPONSES)
        val result = adapter.chat(config, listOf(ApiMessage("user", "Reply with OK")))
        check(!result.isBlankResponse) { "外部 Provider 未返回有效结果" }
    }
}
