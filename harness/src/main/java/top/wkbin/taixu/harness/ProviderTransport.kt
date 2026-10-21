package top.wkbin.taixu.harness

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.wkbin.taixu.harness.diagnostics.withRequestDiagnostics

/** Request policy shared by every wire API; independent of persisted model selection. */
internal class ProviderTransport(
    client: OkHttpClient,
    json: Json,
    private val adapters: (OkHttpClient) -> LlmApiRegistry = { LlmApiRegistry.builtIn(it, json) },
) {
    private val keys = ApiKeyScheduler()
    // Total timeout for ordinary requests; SSE uses read inactivity and first-event watchdogs.
    private val httpClient = client.newBuilder()
        .addInterceptor(ProviderRequestBudget())
        .callTimeout(5 * 60 * 1_000L, TimeUnit.MILLISECONDS)
        .readTimeout(ProviderClient.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val streamClient = client.newBuilder()
        .addInterceptor(ProviderRequestBudget())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(REASONING_STREAM_WAIT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val ordinaryApis = adapters(httpClient)
    private val streamApis = adapters(streamClient)

    suspend fun chat(
        model: ModelConfig,
        messages: List<ApiMessage>,
        sessionKey: String? = null,
    ): ChatResult {
        val transcript = sanitizeApiTranscript(messages)
        return request(model, sessionKey) { selected ->
            ordinaryApis.forModel(selected).chat(selected, transcript)
        }.also(::requireContent)
    }

    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onToolProgress: (ToolCallStreamProgress) -> Unit = {},
        onRequest: ((String, String, Long, Collection<String>) -> Unit)? = null,
        sessionKey: String? = null,
        onDelta: (String) -> Unit,
    ): ChatResult {
        val timing = ReasoningTimingTracker()
        val timedReasoning: (String) -> Unit = { chunk ->
            timing.onReasoningChunk()
            onReasoning(chunk)
        }
        val timedDelta: (String) -> Unit = { chunk ->
            timing.onContentChunk()
            onDelta(chunk)
        }
        val transcript = sanitizeApiTranscript(messages)
        val registry = if (onRequest == null) streamApis else adapters(streamClient.withRequestDiagnostics(onRequest))
        val result = request(model, sessionKey) { selected ->
            registry.forModel(selected).chatStream(selected, transcript, timedReasoning, onToolProgress, timedDelta)
        }
        requireContent(result)
        val reasoningMs = timing.finish()
        return if (reasoningMs != null) result.copy(reasoningMs = reasoningMs) else result
    }

    private fun requireContent(result: ChatResult) {
        if (result.isBlankResponse) throw LlmEmptyResponseException(ProviderClient.EMPTY_RESPONSE_MESSAGE)
    }

    private suspend fun request(
        model: ModelConfig,
        sessionKey: String?,
        send: suspend (ModelConfig) -> ChatResult,
    ): ChatResult = try {
        executeWithRotatedApiKey(model, keys, sessionKey, send).also { currentCoroutineContext().ensureActive() }
    } catch (failure: Exception) {
        // Closing a cancelled blocking socket can throw IOException before withContext resumes.
        // Preserve cancellation, instead of exposing a socket failure or retrying a stopped turn.
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
