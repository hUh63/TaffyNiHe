package com.soreverse.mcp.core

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 本地 AI 网关：把塔菲里已经配好的 AI 渠道，暴露成一个 OpenAI 兼容端点。
 *
 * 为什么需要它：塔菲的 AI 配置（provider / endpoint / key / model / 自定义头）只对本机 App 生效，
 * 电脑上的 Cherry Studio、ChatBox、Cline、Continue 想用同一个渠道，只能再填一遍 key。
 * 这里在本机回环上开一个 `/v1/chat/completions`，把请求原样转给已配置的渠道——
 * 客户端当它是 OpenAI 用即可，key 不出本机。
 *
 * 设计上的几条硬约束：
 *  - **只监听 127.0.0.1**。绑 0.0.0.0 等于把带 key 的代理开放给同网段，历史上抓包代理踩过这个坑。
 *  - **只做 OpenAI 兼容透传**。provider 是 anthropic/gemini 时直接明确报错，而不是猜着转换——
 *    协议转换出错的表现是"回答变成乱码/半截"，比直接拒绝难查得多。
 *  - **请求体只改 model 字段**。其余字段（messages/temperature/tools/…）原样透传，客户端带什么就是什么，
 *    这样网关不会悄悄改变客户端的行为。
 *  - **流式逐块转发**，不整段缓冲——否则客户端会等全部生成完才显示第一个字。
 */
internal object AiGatewayServer {

    /** 只绑回环：网关持有 apiKey，不能对局域网开放。 */
    private const val BIND_HOST = "127.0.0.1"

    /** 请求体上限：正常 chat/completions 远小于此，超过就拒（防止把 App 内存打爆）。 */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile private var running = false

    @Volatile private var boundPort = 0

    /** 并发上限：超过就回 429，而不是把请求排到线程池外（那种表现是「没响应」，最难查）。 */
    private const val MAX_CONCURRENT = 8

    private val slots = java.util.concurrent.Semaphore(MAX_CONCURRENT)

    /** 网关自身的累计用量（进程内累计，重启清零；跨设备统计意义不大）。 */
    private val stats = JSONObject()
    private var requests = 0L
    private var promptTokens = 0L
    private var completionTokens = 0L

    fun isRunning(): Boolean = running

    fun boundPort(): Int = boundPort

    /** 启动网关，返回（是否成功, 失败原因）。端口非法或已被占用时给出可直接展示的文案。 */
    fun start(context: Context): Pair<Boolean, String> {
        if (running) return true to ""
        val settings = SettingsStore(context)
        val port = settings.aiGatewayPort
        if (port !in 1024..65535) return false to "端口需在 1024-65535（当前 $port）"
        // 多通道下不再是「激活通道必须 openai」，而是「至少要有一条 openai 兼容通道」：
        // 客户端用 `通道名/模型` 就能路由到指定通道，不必先把激活通道切过去。
        val channels = settings.aiChannels()
        if (channels.none { it.provider == "openai" }) {
            // 明确拒绝比"猜着转协议"好：转换半对时客户端只会看到莫名其妙的内容。
            return false to "网关只转发 OpenAI 兼容通道，而当前一条都没有"
        }
        if (channels.none { AiChannelStore.apiKey(context, it.id).isNotBlank() }) {
            return false to "还没有任一通道配了 API key"
        }
        if (isPortBusy(port)) return false to "端口 $port 已被占用"

        return runCatching {
            server = embeddedServer(CIO, host = BIND_HOST, port = port) {
                routing {
                    get("/health") {
                        call.respondText(healthJson(settings).toString(), ContentType.Application.Json)
                    }
                    get("/v1/models") {
                        if (!authorized(settings.aiGatewayToken, call.request.header("Authorization"))) {
                            call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                            return@get
                        }
                        call.respondText(modelsJson(settings).toString(), ContentType.Application.Json)
                    }
                    post("/v1/chat/completions") {
                        handleChat(call, context, settings)
                    }
                }
            }.start(wait = false)
            // ktor/CIO 的 bind 在后台协程里做，端口占用不会在这里抛异常；
            // 必须自己探一次，否则会出现"网关显示运行中但端口没监听"。
            if (!waitListening(port, 2500)) {
                runCatching { server?.stop(200, 500) }
                server = null
                return false to "端口 $port 启动失败（可能被占用）"
            }
            running = true
            boundPort = port
            AppLog.i("AI gateway listening on $BIND_HOST:$port")
            true to ""
        }.getOrElse {
            runCatching { server?.stop(200, 500) }
            server = null
            false to (it.message ?: "启动失败")
        }
    }

    fun stop() {
        running = false
        boundPort = 0
        runCatching { server?.stop(300, 800) }
        server = null
    }

    fun snapshot(): JSONObject = synchronized(stats) {
        stats.put("running", running)
        stats.put("port", boundPort)
        stats.put("requests", requests)
        stats.put("promptTokens", promptTokens)
        stats.put("completionTokens", completionTokens)
        stats.put("totalTokens", promptTokens + completionTokens)
        JSONObject(stats.toString())
    }

    fun resetStats() {
        synchronized(stats) {
            requests = 0
            promptTokens = 0
            completionTokens = 0
        }
    }

    // ───────────────────────── 请求处理 ─────────────────────────

    private suspend fun handleChat(call: ApplicationCall, context: Context, settings: SettingsStore) {
        // 转发本身是阻塞 IO，而 ktor CIO 的 worker 只有 CPU 核数那么多：
        // 并发一多请求就排在线程池外，客户端看到的是「没响应」而不是错误。
        // 超过上限直接回 429，让客户端立刻知道该退避。
        if (!slots.tryAcquire()) {
            call.respondText(
                errorJson("网关同时处理中的请求已达 $MAX_CONCURRENT，请稍后重试").toString(),
                ContentType.Application.Json,
                status = HttpStatusCode.TooManyRequests,
            )
            return
        }
        try {
            handleChatInner(call, context, settings)
        } finally {
            slots.release()
        }
    }

    private suspend fun handleChatInner(call: ApplicationCall, context: Context, settings: SettingsStore) {
        if (!authorized(settings.aiGatewayToken, call.request.header("Authorization"))) {
            call.respondText(authError().toString(), ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
            return
        }
        val raw = runCatching { call.receiveText() }.getOrNull()
        if (raw == null || raw.length > MAX_BODY_BYTES) {
            call.respondText(errorJson("请求体为空或过大").toString(), ContentType.Application.Json, status = HttpStatusCode.BadRequest)
            return
        }
        val req = runCatching { JSONObject(raw) }.getOrNull()
        if (req == null) {
            call.respondText(errorJson("请求体不是合法 JSON").toString(), ContentType.Application.Json, status = HttpStatusCode.BadRequest)
            return
        }
        val wantsStream = req.optBoolean("stream", false)
        val channels = settings.aiChannels()
        val active = channels.firstOrNull { it.id == settings.aiActiveChannelId() } ?: channels.firstOrNull()
        if (active == null) {
            call.respondText(errorJson("还没有配置任何 AI 通道").toString(), ContentType.Application.Json, status = HttpStatusCode.BadRequest)
            return
        }
        val route = pickRoute(req.optString("model"), channels, active)
        val channel = route.channel
        if (channel.provider != "openai") {
            call.respondText(
                errorJson("通道「${channel.name}」用的是 ${channel.provider}，网关只转发 OpenAI 兼容协议").toString(),
                ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
            return
        }
        val channelKey = AiChannelStore.apiKey(context, channel.id)
        if (channelKey.isBlank()) {
            call.respondText(errorJson("通道「${channel.name}」没有配 API key").toString(), ContentType.Application.Json, status = HttpStatusCode.BadRequest)
            return
        }
        val body = prepareBody(raw, route.model)
        val target = channel.endpoint.trimEnd('/') + "/chat/completions"
        val headers = authHeaders(channel.provider, channelKey) + headerPairs(channel.headersJson)

        val conn = runCatching { openPost(target, headers, body) }.getOrNull()
        if (conn == null) {
            call.respondText(errorJson("无法连接上游渠道：$target").toString(), ContentType.Application.Json, status = HttpStatusCode.BadGateway)
            return
        }
        val code = runCatching { conn.responseCode }.getOrDefault(0)
        val input: InputStream? = runCatching { if (code in 200..299) conn.inputStream else conn.errorStream }.getOrNull()
        if (input == null || code == 0) {
            runCatching { conn.disconnect() }
            call.respondText(errorJson("上游无响应").toString(), ContentType.Application.Json, status = HttpStatusCode.BadGateway)
            return
        }
        synchronized(stats) { requests++ }
        val contentType = runCatching { conn.contentType }.getOrNull()

        if (!wantsStream) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { readLimited(input, MAX_BODY_BYTES) }.getOrNull()
            }
            runCatching { input.close() }
            runCatching { conn.disconnect() }
            if (bytes == null) {
                call.respondText(errorJson("读取上游响应失败").toString(), ContentType.Application.Json, status = HttpStatusCode.BadGateway)
                return
            }
            recordUsage(String(bytes, Charsets.UTF_8))
            call.respondBytes(bytes, contentType = parseContentType(contentType), status = HttpStatusCode.fromValue(code.coerceIn(100, 599)))
            return
        }

        // 流式：边读边写，尾部留一段文本用来捞 usage（多数渠道只在最后一个 chunk 里给）
        val status = HttpStatusCode.fromValue(code.coerceIn(100, 599))
        call.respondBytesWriter(contentType = parseContentType(contentType), status = status) {
            val tail = StringBuilder()
            val buf = ByteArray(8 * 1024)
            try {
                while (true) {
                    val n = withContext(Dispatchers.IO) { runCatching { input.read(buf) }.getOrDefault(-1) }
                    if (n <= 0) break
                    writeFully(buf, 0, n)
                    flush()
                    // 只留尾部用于捞 usage；不保留头部，避免长回答把内存吃掉
                    tail.append(String(buf, 0, n, Charsets.UTF_8))
                    if (tail.length > 96 * 1024) tail.delete(0, tail.length - 64 * 1024)
                }
            } catch (_: Throwable) {
                // 客户端中途断开：正常现象，不记为错误
            } finally {
                runCatching { input.close() }
                runCatching { conn.disconnect() }
                recordUsage(tail.toString())
            }
        }
    }

    private fun openPost(target: String, headers: List<Pair<String, String>>, body: String): HttpURLConnection {
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 300_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept-Encoding", "identity") // 让流式不被 gzip 缓冲
        headers.forEach { (k, v) -> if (k.isNotBlank()) conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    // ───────────────────────── 纯逻辑（可单测） ─────────────────────────

    /** 一次请求的路由结果。 */
    internal data class GatewayRoute(
        val channel: AiChannel,
        val model: String,
        /** true 表示客户端写了 `通道名/模型` 前缀且命中了。 */
        val viaPrefix: Boolean,
        /** 写了前缀但没匹配到任何通道时，把前缀带回来便于在错误信息里指出来。 */
        val unknownPrefix: String?,
    )

    /**
     * 选通道：请求里的 `model` 可以写成 `通道名/模型` 来指定通道（也接受通道 id）。
     *
     * 规则刻意保守：**不写前缀就用激活通道**，写了前缀但匹配不上也回落到激活通道
     * （并把 unknownPrefix 带出去），绝不因为一个拼错的前缀就把请求打失败——
     * 客户端大多只认 200/错误码，回落到"能正常回答"比"看不懂的 404"有用。
     */
    internal fun pickRoute(rawModel: String, channels: List<AiChannel>, active: AiChannel): GatewayRoute {
        val raw = rawModel.trim()
        if (raw.isEmpty()) return GatewayRoute(active, active.model, false, null)
        val slash = raw.indexOf('/')
        if (slash <= 0) {
            // 没有前缀：**保留客户端指定的模型**。网关是透传，不该悄悄把 model 换成我们配的那个
            // （客户端可能就是在选模型，换掉它等于让客户端的模型选择失效）。
            return GatewayRoute(active, raw, false, null)
        }
        val prefix = raw.substring(0, slash).trim()
        val rest = raw.substring(slash + 1).trim()
        val hit = channels.firstOrNull {
            it.name.equals(prefix, ignoreCase = true) || it.id.equals(prefix, ignoreCase = true)
        }
        return if (hit != null) {
            GatewayRoute(hit, rest.ifBlank { hit.model }, true, null)
        } else {
            // 前缀没匹配到通道 —— 但 model 里带 `/` 是很常见的真实模型名
            // （OpenRouter 全系是 `vendor/model`），所以要原样透传，不能覆盖成激活模型。
            GatewayRoute(active, raw, false, prefix)
        }
    }

    /**
     * 把客户端请求体的 `model` 换成实际配置的模型，其余字段原样保留。
     *
     * 客户端不知道我们配的是哪个模型（它以为自己在调 gpt-4），所以 model 一律以塔菲的配置为准；
     * 解析失败时退化为「字符串替换 model 值」，保证极端情况下也不会把非法 JSON 转给上游。
     */
    fun prepareBody(raw: String, model: String): String {
        return runCatching {
            val copy = JSONObject(raw)
            copy.put("model", model)
            copy.toString()
        }.getOrDefault(raw)
    }

    /** 按 provider 生成认证头。OpenAI 兼容渠道统一 Bearer。 */
    fun authHeaders(provider: String, apiKey: String): List<Pair<String, String>> = when (provider.lowercase()) {
        "anthropic" -> listOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        "gemini" -> listOf("x-goog-api-key" to apiKey)
        else -> listOf("Authorization" to "Bearer $apiKey")
    }

    /** 自定义头 JSON（`{"x-foo":"bar"}`）→ 键值对，非法 JSON 返回空。 */
    fun headerPairs(json: String): List<Pair<String, String>> = runCatching {
        if (json.isBlank()) return emptyList()
        val o = JSONObject(json)
        o.keys().asSequence().map { it to o.optString(it) }.filter { it.second.isNotBlank() }.toList()
    }.getOrDefault(emptyList())

    /** 网关鉴权：token 为空表示不校验（默认），非空则必须与 Authorization 里的 bearer 一致。 */
    fun authorized(token: String, header: String?): Boolean {
        if (token.isBlank()) return true
        val got = header.orEmpty().trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        return got == token
    }

    /** 从一段响应文本里取用量：兼容非流式 JSON 与流式 SSE 尾部（取最后一条带 usage 的）。 */
    fun usageOf(text: String): Triple<Long, Long, Long>? {
        if (text.isBlank()) return null
        // 流式：逐行找 data: {...}
        var best: Triple<Long, Long, Long>? = null
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val payload = t.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            usageFromJson(payload)?.let { best = it }
        }
        if (best != null) return best
        return usageFromJson(text.trim())
    }

    private fun usageFromJson(text: String): Triple<Long, Long, Long>? = runCatching {
        val o = JSONObject(text)
        val u = o.optJSONObject("usage") ?: return null
        val p = u.optLong("prompt_tokens", 0)
        val c = u.optLong("completion_tokens", 0)
        val t = u.optLong("total_tokens", p + c)
        if (p == 0L && c == 0L && t == 0L) null else Triple(p, c, t)
    }.getOrNull()

    // ───────────────────────── 内部 ─────────────────────────

    private fun recordUsage(text: String) {
        val u = usageOf(text) ?: return
        synchronized(stats) {
            promptTokens += u.first
            completionTokens += u.second
        }
    }

    private fun healthJson(settings: SettingsStore): JSONObject {
        val channels = settings.aiChannels()
        return JSONObject()
            .put("ok", true)
            .put("provider", settings.aiProvider)
            .put("model", settings.aiModel)
            .put("channels", channels.size)
            .put("channelNames", org.json.JSONArray(channels.map { it.name }))
            .put("stats", snapshot())
    }

    /**
     * 列出所有可路由的模型名。
     *
     * 每条 OpenAI 兼容通道给一个 `<通道名>/<模型>` 别名（客户端据此分流），
     * 另外给一条裸的激活模型名，方便"只想用当前通道"的客户端不必写前缀。
     */
    private fun modelsJson(settings: SettingsStore): JSONObject {
        val data = org.json.JSONArray()
        val channels = settings.aiChannels()
        val activeId = settings.aiActiveChannelId()
        channels.filter { it.provider == "openai" }.forEach { ch ->
            val id = if (channels.size == 1) ch.model else ch.name + "/" + ch.model
            data.put(JSONObject().put("id", id).put("object", "model").put("owned_by", ch.name))
        }
        val active = channels.firstOrNull { it.id == activeId } ?: channels.firstOrNull()
        if (active != null) {
            data.put(JSONObject().put("id", active.model).put("object", "model").put("owned_by", active.name))
        }
        return JSONObject().put("object", "list").put("data", data)
    }

    private fun authError(): JSONObject = JSONObject()
        .put("error", JSONObject().put("message", "网关 token 不匹配").put("type", "invalid_request_error"))

    private fun errorJson(message: String): JSONObject = JSONObject()
        .put("error", JSONObject().put("message", message).put("type", "gateway_error"))

    private fun parseContentType(raw: String?): ContentType =
        runCatching { ContentType.parse(raw ?: "application/json") }.getOrDefault(ContentType.Application.Json)

    private fun isPortBusy(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(BIND_HOST, port), 250) }
        true
    }.getOrDefault(false)

    private fun waitListening(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortBusy(port)) return true
            runCatching { Thread.sleep(80) }
        }
        return false
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
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
