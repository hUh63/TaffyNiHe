package com.soreverse.mcp.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

sealed interface RikkaPart {
    data class Text(val text: String) : RikkaPart
    data class Reasoning(val text: String) : RikkaPart
    data class Tool(
        val id: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val index: Int = 0,
    ) : RikkaPart
}

internal data class RikkaMessage(
    val role: String,
    val parts: List<RikkaPart>,
)

internal data class RikkaTool(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val execute: suspend (JSONObject) -> String,
)

internal class RikkaAgentEngine(
    private val client: OkHttpClient,
    private val provider: String,
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Float,
    private val customHeaders: Map<String, String>,
    private val customBody: Map<String, JsonElement>,
    /**
     * 工具调用模式，对齐 AstrBot 的 `tool_schema_mode`：
     *
     *  - `full`（默认）：走 provider 原生 function calling，tools 数组每轮随请求重发；
     *  - `skills_like`：不发送原生 tools，把工具以 Markdown 文档写进 system prompt，模型用
     *    `<tool_call>{"tool":..,"args":{..}}</tool_call>` 发起调用，引擎从文本解析。适合
     *    「中转/小模型不支持 function calling」或「工具很多、schema 每轮重发太贵」。
     *
     * 非法值回落 `full`（与上游一致）。
     */
    private val toolSchemaMode: String = "full",
) {
    // 用字面量比较而非常量：属性初始化器对 companion 的前向引用在部分 Kotlin 版本上会踩
    // "unresolved reference"，而这里只差一个字符串，没必要赌编译器行为。
    private val useSkillsLike = toolSchemaMode.trim().lowercase() == "skills_like"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(
        systemPrompt: String,
        userPrompt: String,
        tools: List<RikkaTool>,
        maxSteps: Int,
        requiredTools: List<String> = emptyList(),
        initialMessages: List<RikkaMessage> = emptyList(),
        onCheckpoint: (suspend (List<RikkaMessage>) -> Unit)? = null,
        onParts: (List<RikkaPart>) -> Unit,
    ): String {
        val messages = if (initialMessages.isNotEmpty()) {
            initialMessages.toMutableList()
        } else {
            mutableListOf(
                RikkaMessage(
                    "system",
                    listOf(RikkaPart.Text(if (useSkillsLike) skillsLikeSystemPrompt(systemPrompt, tools) else systemPrompt)),
                ),
                RikkaMessage("user", listOf(RikkaPart.Text(userPrompt))),
            )
        }
        val visibleParts = mutableListOf<RikkaPart>()
        val executedTools = linkedSetOf<String>()
        // 断点续跑: 上一轮 assistant 里若还有没执行完的工具调用, 先把它们补完再请求模型。
        // 不这样做的话, 恢复后会带着「半截」的 assistant 消息再发一次请求, 模型会重复调用。
        if (messages.lastOrNull()?.role == "assistant" &&
            messages.last().parts.filterIsInstance<RikkaPart.Tool>().any { it.result == null }
        ) {
            executeCalls(messages, tools, executedTools, visibleParts, onParts, onCheckpoint)
        }
        repeat(maxSteps.coerceIn(1, 256)) {
            var assistant = RikkaMessage("assistant", emptyList())
            // skills_like 不发原生 tools：发了模型会改走原生 tool_calls，而提示词里已声明
            // 「用 <tool_call> 文本」，两套通道并存会让调用形态随机。
            stream(messages, if (useSkillsLike) emptyList() else tools).collect { delta ->
                assistant = assistant.copy(parts = mergeParts(assistant.parts, delta))
                onParts(visibleParts + assistant.parts)
            }
            messages += assistant
            val calls = if (useSkillsLike) {
                // 文本块解析为主；原生 tool_calls 仍作兜底 —— 部分模型即使没收到 tools 参数
                // 也会按训练习惯输出 tool_calls，直接丢掉等于空转一轮。
                parseTextToolCalls(assistant) +
                    assistant.parts.filterIsInstance<RikkaPart.Tool>().filter { it.result == null }
            } else {
                assistant.parts.filterIsInstance<RikkaPart.Tool>().filter { it.result == null }
            }
            if (calls.isEmpty()) {
                val missing = requiredTools.filterNot(executedTools::contains)
                if (missing.isNotEmpty()) {
                    visibleParts += assistant.parts
                    messages += RikkaMessage(
                        "user",
                        listOf(RikkaPart.Text("Continue the analysis. Complete these required tools before the final answer: ${missing.joinToString(" → ")}.")),
                    )
                    return@repeat
                }
                val text = assistant.parts.filterIsInstance<RikkaPart.Text>().joinToString("") { it.text }.trim()
                return if (useSkillsLike) stripToolCallBlocks(text) else text
            }
            if (useSkillsLike) {
                executeTextCalls(messages, calls, tools, executedTools, visibleParts, onParts, onCheckpoint)
            } else {
                executeCalls(messages, tools, executedTools, visibleParts, onParts, onCheckpoint)
                visibleParts += messages.last().parts
            }
        }
        error("Maximum tool steps exceeded")
    }

    /**
     * 执行当前 assistant 消息里所有未完成的工具调用, 每完成一个就回调一次检查点。
     *
     * 逐调用落盘的意义: 进程被杀时, 已经跑完的工具结果不会丢, 恢复后只会重跑没结果的那些。
     * 代价是写入类工具若在落盘前被杀会重放一次——所以子代理的角色白名单里刻意不放写入工具。
     */
    private suspend fun executeCalls(
        messages: MutableList<RikkaMessage>,
        tools: List<RikkaTool>,
        executedTools: MutableSet<String>,
        visibleParts: MutableList<RikkaPart>,
        onParts: (List<RikkaPart>) -> Unit,
        onCheckpoint: (suspend (List<RikkaMessage>) -> Unit)?,
    ) {
        val calls = messages.last().parts.filterIsInstance<RikkaPart.Tool>().filter { it.result == null }
        calls.forEach { call ->
            val result = runToolCall(call, tools, executedTools)
            val completed = call.copy(result = result)
            messages[messages.lastIndex] = messages.last().copy(
                parts = messages.last().parts.map { if (it is RikkaPart.Tool && it.id == call.id) completed else it },
            )
            onParts(visibleParts + messages.last().parts)
            onCheckpoint?.invoke(messages.toList())
        }
    }

    /**
     * 解析并执行一次工具调用，返回结果字符串。两种模式共用：名字解析（精确 → 忽略大小写 →
     * 短名）与错误负载（`unknown_tool` / `invalid_arguments`）必须一致，否则同一份提示词
     * 切换模式后行为会漂移。
     */
    private suspend fun runToolCall(
        call: RikkaPart.Tool,
        tools: List<RikkaTool>,
        executedTools: MutableSet<String>,
    ): String {
        val requestedName = call.name.trim()
        val shortName = requestedName.substringAfterLast('.').substringAfterLast('/')
        val tool = tools.firstOrNull { it.name == requestedName }
            ?: tools.firstOrNull { it.name.equals(requestedName, ignoreCase = true) }
            ?: tools.firstOrNull { it.name.equals(shortName, ignoreCase = true) }
        if (tool == null) {
            return JSONObject()
                .put("status", "error")
                .put("error", "unknown_tool")
                .put("tool", requestedName)
                .put("available_tools", tools.map { it.name })
                .toString()
        }
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse {
            return JSONObject()
                .put("status", "error")
                .put("error", "invalid_arguments")
                .put("tool", requestedName)
                .put("details", it.message.orEmpty())
                .toString()
        }
        return tool.execute(args).also { executedTools += tool.name }
    }

    /**
     * skills_like 模式的一个执行回合：模型侧没有原生 tool role，结果以 `<tool_response>`
     * 的 user 消息回灌，模型据此续写。检查点仍逐个调用落盘（与 full 模式语义一致）。
     */
    private suspend fun executeTextCalls(
        messages: MutableList<RikkaMessage>,
        calls: List<RikkaPart.Tool>,
        tools: List<RikkaTool>,
        executedTools: MutableSet<String>,
        visibleParts: MutableList<RikkaPart>,
        onParts: (List<RikkaPart>) -> Unit,
        onCheckpoint: (suspend (List<RikkaMessage>) -> Unit)?,
    ) {
        val rendered = StringBuilder()
        val resultParts = mutableListOf<RikkaPart>()
        for (call in calls) {
            val result = runToolCall(call, tools, executedTools)
            rendered
                .append("<tool_response>\n")
                .append(JSONObject().put("tool", call.name.trim()).put("result", result))
                .append("\n</tool_response>\n")
            resultParts += call.copy(name = call.name.trim(), result = result)
            onParts(visibleParts + resultParts)
        }
        visibleParts += resultParts
        messages += RikkaMessage("user", listOf(RikkaPart.Text(rendered.toString())))
        onCheckpoint?.invoke(messages.toList())
    }

    private fun stream(messages: List<RikkaMessage>, tools: List<RikkaTool>): Flow<List<RikkaPart>> =
        when (provider) {
            "anthropic" -> streamAnthropic(messages, tools)
            "gemini" -> streamGemini(messages, tools)
            else -> streamOpenAi(messages, tools)
        }

    private fun streamOpenAi(messages: List<RikkaMessage>, tools: List<RikkaTool>): Flow<List<RikkaPart>> = callbackFlow {
        val body = buildOpenAiBody(messages, tools)
        val request = requestBuilder(openAiUrl())
            .safeHeader("Authorization", "Bearer $apiKey")
            .applyCustomHeaders()
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val toolMetadata = mutableMapOf<Int, Pair<String, String>>()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                runCatching {
                    val root = json.parseToJsonElement(data).jsonObject
                    root["error"]?.let { error(it.toString()) }
                    val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                        ?: return@runCatching
                    val parts = buildList {
                        delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { add(RikkaPart.Reasoning(it)) }
                        delta["reasoning"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { add(RikkaPart.Reasoning(it)) }
                        delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { add(RikkaPart.Text(it)) }
                        delta["tool_calls"]?.jsonArray?.forEach { item ->
                            val tool = item.jsonObject
                            val index = tool["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val function = tool["function"]?.jsonObject ?: JsonObject(emptyMap())
                            val previous = toolMetadata[index]
                            val resolvedId = tool["id"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?: previous?.first
                                ?: "tool_call_$index"
                            val resolvedName = function["name"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?: previous?.second.orEmpty()
                            toolMetadata[index] = resolvedId to resolvedName
                            add(
                                RikkaPart.Tool(
                                    id = resolvedId,
                                    name = resolvedName,
                                    arguments = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    index = index,
                                ),
                            )
                        }
                    }
                    if (parts.isNotEmpty()) trySend(parts)
                }.onFailure { close(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(eventSourceFailure(t, response))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun streamAnthropic(messages: List<RikkaMessage>, tools: List<RikkaTool>): Flow<List<RikkaPart>> = callbackFlow {
        val request = requestBuilder(anthropicUrl())
            .safeHeader("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .applyCustomHeaders()
            .post(buildAnthropicBody(messages, tools).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val toolNames = mutableMapOf<Int, Pair<String, String>>()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching {
                    val root = json.parseToJsonElement(data).jsonObject
                    when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "content_block_start" -> {
                            val index = root["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val block = root["content_block"]?.jsonObject ?: return@runCatching
                            if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                toolNames[index] = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty() to block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val pair = toolNames[index] ?: return
                                trySend(listOf(RikkaPart.Tool(pair.first, pair.second, "", index = index)))
                            }
                        }
                        "content_block_delta" -> {
                            val index = root["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val delta = root["delta"]?.jsonObject ?: return@runCatching
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { trySend(listOf(RikkaPart.Text(it))) }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { trySend(listOf(RikkaPart.Reasoning(it))) }
                                "input_json_delta" -> toolNames[index]?.let { pair ->
                                    trySend(listOf(RikkaPart.Tool(pair.first, pair.second, delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty(), index = index)))
                                }
                            }
                        }
                        "message_stop" -> close()
                        "error" -> error(root["error"].toString())
                    }
                }.onFailure { close(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(eventSourceFailure(t, response))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    // ── Google Gemini 原生协议（streamGenerateContent?alt=sse）──
    // SSE data 行 = 与 generateContent 相同结构的增量：candidates[0].content.parts[]（text / thought / functionCall 完整对象）
    private fun streamGemini(messages: List<RikkaMessage>, tools: List<RikkaTool>): Flow<List<RikkaPart>> = callbackFlow {
        val request = requestBuilder(geminiUrl("streamGenerateContent") + "?alt=sse")
            .safeHeader("x-goog-api-key", apiKey)
            .applyCustomHeaders()
            .post(buildGeminiBody(messages, tools).toString().toRequestBody("application/json".toMediaType()))
            .build()
        // functionCall part 无 id，用递增序号生成稳定 id 供多轮工具回填
        var callSeq = 0
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching {
                    val root = json.parseToJsonElement(data).jsonObject
                    root["error"]?.let { error(it.toString()) }
                    val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching
                    val content = candidate["content"]?.jsonObject ?: return@runCatching
                    val parts = buildList {
                        content["parts"]?.jsonArray?.forEach { partEl ->
                            val part = partEl.jsonObject
                            when {
                                part["thought"]?.jsonPrimitive?.contentOrNull == "true" ->
                                    part["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { add(RikkaPart.Reasoning(it)) }
                                part["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) != null ->
                                    add(RikkaPart.Text(part["text"]!!.jsonPrimitive.content))
                                part["functionCall"] != null -> {
                                    val fn = part["functionCall"]!!.jsonObject
                                    val name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val args = fn["args"]?.jsonObject?.toString().orEmpty()
                                    val idx = callSeq++
                                    add(RikkaPart.Tool(id = "gemini_call_$idx", name = name, arguments = args, index = idx))
                                }
                            }
                        }
                    }
                    if (parts.isNotEmpty()) trySend(parts)
                }.onFailure { close(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(eventSourceFailure(t, response))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun eventSourceFailure(t: Throwable?, response: Response?): Throwable {        if (response == null) {
            return t ?: IllegalStateException("SSE connection failed without an HTTP response")
        }
        val body = response.body
        val metadata = buildList {
            body.contentType()?.let { add("content-type=$it") }
            body.contentLength().takeIf { it >= 0L }?.let { add("content-length=$it") }
        }.joinToString(", ")
        val message = buildString {
            append("SSE HTTP ${response.code}")
            response.message.takeIf(String::isNotBlank)?.let { append(" $it") }
            if (metadata.isNotEmpty()) append(" ($metadata)")
            t?.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
        }
        return IllegalStateException(message, t)
    }

    private fun mergeParts(current: List<RikkaPart>, deltas: List<RikkaPart>): List<RikkaPart> =
        deltas.fold(current) { parts, delta ->
            when (delta) {
                is RikkaPart.Text -> if (parts.lastOrNull() is RikkaPart.Text) parts.dropLast(1) + RikkaPart.Text((parts.last() as RikkaPart.Text).text + delta.text) else parts + delta
                is RikkaPart.Reasoning -> if (parts.lastOrNull() is RikkaPart.Reasoning) parts.dropLast(1) + RikkaPart.Reasoning((parts.last() as RikkaPart.Reasoning).text + delta.text) else parts + delta
                is RikkaPart.Tool -> {
                    val target = parts.indexOfLast {
                        it is RikkaPart.Tool && (it.index == delta.index || (delta.id.isNotBlank() && it.id == delta.id))
                    }
                    if (target < 0) parts + delta else parts.toMutableList().apply {
                        val old = this[target] as RikkaPart.Tool
                        this[target] = old.copy(
                            id = delta.id.ifBlank { old.id },
                            name = delta.name.ifBlank { old.name },
                            arguments = old.arguments + delta.arguments,
                            result = delta.result ?: old.result,
                        )
                    }
                }
            }
        }

    private fun buildOpenAiBody(messages: List<RikkaMessage>, tools: List<RikkaTool>) = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("temperature", temperature)
        putJsonArray("messages") {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.parts.filterIsInstance<RikkaPart.Text>().joinToString("") { it.text })
                    val calls = message.parts.filterIsInstance<RikkaPart.Tool>()
                    // skills_like 不发原生 tools；若仍写 tool_calls，消息里会引用不存在的工具定义
                    // （部分中转直接 400）。文本形态的调用已在系统提示词里声明。
                    if (message.role == "assistant" && calls.isNotEmpty() && !useSkillsLike) {
                        putJsonArray("tool_calls") {
                            calls.forEach { call -> add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
                            }) }
                        }
                    }
                })
                if (!useSkillsLike) {
                    message.parts.filterIsInstance<RikkaPart.Tool>().filter { it.result != null }.forEach { call ->
                        add(buildJsonObject { put("role", "tool"); put("tool_call_id", call.id); put("content", call.result.orEmpty()) })
                    }
                }
            }
        }
        // 空数组不能写：OpenAI 兼容中转对 "tools": [] 会直接 400（tools must not be empty），
        // 而 skills_like 走的正是「不发 tools」这条路。
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.name); put("description", tool.description)
                        put("parameters", json.parseToJsonElement(tool.schema.toString()))
                    })
                }) }
            }
        }
        customBody.forEach { (key, value) -> put(key, value) }
    }

    private fun buildAnthropicBody(messages: List<RikkaMessage>, tools: List<RikkaTool>) = buildJsonObject {
        put("model", model); put("stream", true); put("max_tokens", 16_384); put("temperature", temperature)
        put("system", messages.firstOrNull { it.role == "system" }?.parts?.filterIsInstance<RikkaPart.Text>()?.joinToString("") { it.text }.orEmpty())
        putJsonArray("messages") {
            messages.filter { it.role != "system" }.forEach { message ->
                if (message.role != "assistant") {
                    add(buildJsonObject {
                        put("role", message.role)
                        putJsonArray("content") {
                            message.parts.filterIsInstance<RikkaPart.Text>().forEach { part ->
                                add(buildJsonObject { put("type", "text"); put("text", part.text) })
                            }
                        }
                    })
                } else {
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            message.parts.forEach { part -> when (part) {
                                is RikkaPart.Text -> add(buildJsonObject { put("type", "text"); put("text", part.text) })
                                is RikkaPart.Reasoning -> Unit
                                is RikkaPart.Tool -> if (!useSkillsLike) {
                                    add(buildJsonObject {
                                        put("type", "tool_use"); put("id", part.id); put("name", part.name)
                                        put("input", runCatching { json.parseToJsonElement(part.arguments) }.getOrElse { JsonObject(emptyMap()) })
                                    })
                                } else {
                                    Unit
                                }
                            } }
                        }
                    })
                    val completed = message.parts.filterIsInstance<RikkaPart.Tool>().filter { it.result != null }
                    if (completed.isNotEmpty() && !useSkillsLike) {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                completed.forEach { part -> add(buildJsonObject {
                                    put("type", "tool_result"); put("tool_use_id", part.id); put("content", part.result.orEmpty())
                                }) }
                            }
                        })
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") { tools.forEach { tool -> add(buildJsonObject { put("name", tool.name); put("description", tool.description); put("input_schema", json.parseToJsonElement(tool.schema.toString())) }) } }
        }
        customBody.forEach { (key, value) -> put(key, value) }
    }

    private fun requestBuilder(url: String) = Request.Builder().url(url).header("Accept", "text/event-stream")

    private fun Request.Builder.applyCustomHeaders() = apply {
        customHeaders.forEach { (name, value) -> safeHeader(name, value) }
    }

    private fun openAiUrl(): String = endpoint.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
    private fun anthropicUrl(): String = endpoint.trimEnd('/').let { if (it.endsWith("/messages")) it else if (it.endsWith("/v1")) "$it/messages" else "$it/v1/messages" }

    // ── Gemini body/url ──
    private fun geminiUrl(action: String): String {
        val base = endpoint.trimEnd('/').let { if (it.contains("/v1beta") || it.contains("/v1alpha")) it else "$it/v1beta" }
        val modelPath = model.removePrefix("models/")
        return "$base/models/$modelPath:$action"
    }

    private fun buildGeminiBody(messages: List<RikkaMessage>, tools: List<RikkaTool>) = buildJsonObject {
        messages.firstOrNull { it.role == "system" }?.let { system ->
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", system.parts.filterIsInstance<RikkaPart.Text>().joinToString("") { it.text }) })
                }
            })
        }
        putJsonArray("contents") {
            messages.filter { it.role != "system" }.forEach { message ->
                add(buildJsonObject {
                    // Gemini 角色: user / model
                    put("role", if (message.role == "assistant") "model" else "user")
                    putJsonArray("parts") {
                        val toolCalls = message.parts.filterIsInstance<RikkaPart.Tool>()
                        message.parts.forEach { part ->
                            when (part) {
                                is RikkaPart.Text -> if (part.text.isNotEmpty()) add(buildJsonObject { put("text", part.text) })
                                is RikkaPart.Reasoning -> Unit
                                is RikkaPart.Tool ->
                                    if (message.role == "assistant" && part.result == null) {
                                        // model 发起的 functionCall
                                        add(buildJsonObject {
                                            put("functionCall", buildJsonObject {
                                                put("name", part.name)
                                                put("args", runCatching { json.parseToJsonElement(part.arguments.ifBlank { "{}" }) }.getOrElse { JsonObject(emptyMap()) })
                                            })
                                        })
                                    } else if (part.result != null) {
                                        // user 回填的 functionResponse
                                        add(buildJsonObject {
                                            put("functionResponse", buildJsonObject {
                                                put("name", part.name)
                                                put("response", buildJsonObject {
                                                    put("result", part.result.orEmpty().take(65_536))
                                                })
                                            })
                                        })
                                    }
                            }
                        }
                        // toolCalls 非空但 parts 循环里 result!=null 的分支已覆盖；无 parts 时保证非空
                        if (message.parts.none { it is RikkaPart.Text || it is RikkaPart.Tool }) {
                            add(buildJsonObject { put("text", " ") })
                        }
                    }
                })
            }
        }
        put("generationConfig", buildJsonObject {
            put("temperature", temperature)
            put("maxOutputTokens", 65_536)
        })
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", runCatching { json.parseToJsonElement(tool.schema.toString()) }.getOrElse { JsonObject(emptyMap()) })
                            })
                        }
                    }
                })
            }
        }
        customBody.forEach { (key, value) -> put(key, value) }
    }

    // ── skills_like：工具文档渲染 + 文本调用解析 ──

    /**
     * 从模型文本里抽取 `<tool_call>{"tool":..,"args":{..}}</tool_call>` 块。
     *
     * 额外接受「整段就是一个裸 JSON 对象」的退化输出（部分小模型会省掉标签），但只在该
     * 对象确实带工具名与参数键时才认；其余一切解析失败都被忽略——散文绝不能被当成调用。
     */
    private fun parseTextToolCalls(message: RikkaMessage): List<RikkaPart.Tool> {
        val text = message.parts.filterIsInstance<RikkaPart.Text>().joinToString("") { it.text }
        if (text.isBlank()) return emptyList()
        val payloads = TOOL_CALL_BLOCK_REGEX.findAll(text).map { it.groupValues[1].trim() } +
            listOfNotNull(bareToolCallPayload(text))
        return payloads.mapIndexedNotNull { index, payload ->
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@mapIndexedNotNull null
            val name = obj.optString("tool").ifBlank { obj.optString("name") }.trim()
            if (name.isBlank()) return@mapIndexedNotNull null
            val args = obj.optJSONObject("args")
                ?: obj.optJSONObject("arguments")
                ?: obj.optJSONObject("parameters")
                ?: JSONObject()
            RikkaPart.Tool(id = "skills_call_$index", name = name, arguments = args.toString(), index = index)
        }.toList()
    }

    private fun bareToolCallPayload(text: String): String? {
        val trimmed = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val hasName = obj.has("tool") || obj.has("name")
        val hasArgs = obj.optJSONObject("args") != null ||
            obj.optJSONObject("arguments") != null ||
            obj.optJSONObject("parameters") != null
        return if (hasName && hasArgs) trimmed else null
    }

    /** Anthropic-Skills 风格的 Markdown 工具文档，替代原生 tools 数组。 */
    private fun skillsLikeSystemPrompt(systemPrompt: String, tools: List<RikkaTool>): String = buildString {
        append(systemPrompt)
        append("\n\n# 工具使用（skills-like 模式）\n\n")
        append("工具以文档形式提供，不走 provider 的原生 function calling。需要调用时，输出一个 ")
        append("`<tool_call>` 块，块内是**恰好一个** JSON 对象：\n\n")
        append("```\n<tool_call>\n{\"tool\": \"<工具名>\", \"args\": {\"<参数>\": <值>}}\n</tool_call>\n```\n\n")
        append("规则：\n")
        append("- 一次调用多个工具就写多个块，按出现顺序执行；\n")
        append("- 参数名与类型必须与下方文档一致，未列出的参数不要臆造；\n")
        append("- 块内不要夹注释、Markdown 围栏或解释文字；\n")
        append("- 每次调用后结果会以 `<tool_response>` 回灌，据其继续，不要复述原文；\n")
        append("- 最终回答里不要保留 `<tool_call>` 块。\n\n")
        append("## 工具清单\n\n")
        tools.forEach { tool ->
            append("### ").append(tool.name).append("\n\n")
            append(tool.description.trim()).append("\n\n")
            append("签名: ").append(tool.name).append('(').append(schemaParams(tool.schema)).append(")\n\n")
            append(schemaTable(tool.schema))
        }
    }

    private fun schemaParams(schema: JSONObject): String {
        val properties = schema.optJSONObject("properties") ?: return ""
        val required = schema.optJSONArray("required")
        val keys = mutableListOf<String>()
        properties.keys().forEach { keys += it }
        return keys.joinToString(", ") { key ->
            val type = properties.optJSONObject(key)?.optString("type").orEmpty().ifBlank { "any" }
            val isRequired = required != null && (0 until required.length()).any { required.optString(it) == key }
            if (isRequired) "$key: $type" else "$key?: $type"
        }
    }

    private fun schemaTable(schema: JSONObject): String {
        val properties = schema.optJSONObject("properties") ?: return "参数: 无\n\n"
        val required = schema.optJSONArray("required")
        val sb = StringBuilder("参数:\n")
        val keys = mutableListOf<String>()
        properties.keys().forEach { keys += it }
        keys.forEach { key ->
            val def = properties.optJSONObject(key)
            val type = def?.optString("type").orEmpty().ifBlank { "any" }
            val isRequired = required != null && (0 until required.length()).any { required.optString(it) == key }
            sb.append("- ").append(key).append(" (").append(type)
                .append(if (isRequired) ", 必填" else ", 可选").append(')')
            val desc = def?.optString("description").orEmpty()
            if (desc.isNotBlank()) sb.append(": ").append(desc)
            sb.append('\n')
        }
        sb.append('\n')
        return sb.toString()
    }

    private fun stripToolCallBlocks(text: String): String = text
        .replace(TOOL_CALL_BLOCK_REGEX, "")
        .replace("\n{3,}".toRegex(), "\n\n")
        .trim()

    private companion object {
        const val TOOL_SCHEMA_SKILLS_LIKE = "skills_like"
        private val TOOL_CALL_BLOCK_REGEX = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>")
    }
}
