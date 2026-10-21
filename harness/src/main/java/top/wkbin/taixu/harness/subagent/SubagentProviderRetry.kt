package top.wkbin.taixu.harness.subagent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ChatResult
import top.wkbin.taixu.harness.LlmOutputLimitException
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.outputRecoveryModel

/** 子智能体单轮请求：网络重试 + 输出预算翻倍恢复。 */
internal suspend fun subagentChatWithRetry(
    providerClient: ProviderClient,
    model: ModelConfig,
    messages: List<ApiMessage>,
    text: StringBuilder,
): ChatResult {
    var requestModel = model
    var networkAttempts = 0
    var outputRecoveries = 0
    var lastFailure: IOException? = null
    while (true) {
        try {
            return providerClient.chatStream(requestModel, messages, onReasoning = {}) { text.append(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (limit: LlmOutputLimitException) {
            text.clear()
            // 子任务常产出较长方案；沿用主会话的翻倍恢复策略，避免直接以 length 失败。
            val recovered = if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                outputRecoveryModel(requestModel)
            } else {
                null
            }
            if (recovered == null) throw limit
            outputRecoveries++
            requestModel = recovered
        } catch (failure: IOException) {
            lastFailure = failure
            text.clear()
            networkAttempts++
            if (networkAttempts >= NETWORK_ATTEMPTS) throw failure
            delay(NETWORK_RETRY_DELAY_MS)
        }
    }
}

private const val NETWORK_ATTEMPTS = 2
private const val NETWORK_RETRY_DELAY_MS = 1_000L
private const val MAX_OUTPUT_RECOVERIES = 2
