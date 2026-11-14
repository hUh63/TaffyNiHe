package com.soreverse.mcp.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条 AI 通道：provider + endpoint + key + model + 采样参数 + 自定义 headers/body。
 *
 * 之所以把这些打包成一个对象，是因为它们在生活中是一起变的：换一个中转站就要同时换
 * endpoint/key/model，分开存就会出现「换了 key 忘了换 endpoint」这种只在报错时才发现的错配。
 * 系统提示词、工具调用模式这些是**策略**不是通道，仍留在 [SettingsStore] 里全局共享。
 */
internal data class AiChannel(
    val id: String,
    val name: String,
    val provider: String = "openai",
    val endpoint: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val temperature: Float = 0.2f,
    val headersJson: String = "{}",
    val bodyJson: String = "{}",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("provider", provider)
        .put("endpoint", endpoint)
        .put("model", model)
        .put("temperature", temperature.toDouble())
        .put("headersJson", headersJson)
        .put("bodyJson", bodyJson)

    companion object {
        const val DEFAULT_ID = "default"

        fun fromJson(o: JSONObject): AiChannel? {
            val id = o.optString("id").trim()
            if (id.isEmpty()) return null
            val provider = o.optString("provider", "openai").let {
                if (it in setOf("openai", "anthropic", "gemini")) it else "openai"
            }
            return AiChannel(
                id = id,
                name = o.optString("name").ifBlank { id },
                provider = provider,
                endpoint = o.optString("endpoint", "https://api.openai.com/v1"),
                model = o.optString("model", "gpt-4.1-mini"),
                temperature = o.optDouble("temperature", 0.2).toFloat(),
                headersJson = o.optString("headersJson", "{}").ifBlank { "{}" },
                bodyJson = o.optString("bodyJson", "{}").ifBlank { "{}" },
            )
        }
    }
}

/**
 * 多通道存储。
 *
 * 关键约束：**老配置必须无损迁移**。塔菲此前是单通道（provider/endpoint/key/model 直接摊在
 * prefs 里），升级后第一次读通道列表时把那份配置原样包成 `default` 通道，key 从
 * `aiApiKey` 复制到 `aiChannelKey_default`。迁移只做一次（`aiChannelsJson` 有内容就跳过），
 * 老 key 保留不删——万一降级回旧版本还能用。
 *
 * 读走进程内缓存：AI 请求频率低但 `aiProvider` 这类属性在拼接请求时会被反复读，
 * 每次都解析一次 JSON 没必要。所有写路径统一清缓存，不会读到旧值。
 */
internal object AiChannelStore {

    private const val CHANNELS_KEY = "aiChannelsJson"
    private const val ACTIVE_KEY = "aiActiveChannelId"

    @Volatile private var cache: List<AiChannel>? = null

    fun channels(context: Context, prefs: SharedPreferences): List<AiChannel> {
        cache?.let { return it }
        val raw = prefs.getString(CHANNELS_KEY, "").orEmpty()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { AiChannel.fromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            cache = parsed
            return parsed
        }
        val migrated = migrateFromLegacy(context, prefs)
        persist(prefs, migrated)
        prefs.edit().putString(ACTIVE_KEY, migrated.first().id).apply()
        return migrated
    }

    /** 当前激活通道；id 找不到（被删/被改）时回落到第一个，绝不返回空。 */
    fun active(context: Context, prefs: SharedPreferences): AiChannel {
        val list = channels(context, prefs)
        val id = prefs.getString(ACTIVE_KEY, "").orEmpty()
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun setActive(context: Context, prefs: SharedPreferences, id: String) {
        val list = channels(context, prefs)
        if (list.none { it.id == id }) return
        prefs.edit().putString(ACTIVE_KEY, id).apply()
    }

    fun activeId(context: Context, prefs: SharedPreferences): String {
        val id = prefs.getString(ACTIVE_KEY, "").orEmpty()
        if (id.isNotBlank() && channels(context, prefs).any { it.id == id }) return id
        return active(context, prefs).id
    }

    /** 更新激活通道（getter/setter 都靠它，保证单点写入）。 */
    fun updateActive(context: Context, prefs: SharedPreferences, transform: (AiChannel) -> AiChannel) {
        val current = active(context, prefs)
        upsert(context, prefs, transform(current))
    }

    fun upsert(context: Context, prefs: SharedPreferences, channel: AiChannel) {
        val list = channels(context, prefs).toMutableList()
        val idx = list.indexOfFirst { it.id == channel.id }
        if (idx >= 0) list[idx] = channel else list.add(channel)
        persist(prefs, list)
    }

    /** 删除通道；最后一个不允许删（否则 active 无处可取）。 */
    fun remove(context: Context, prefs: SharedPreferences, id: String): Boolean {
        val list = channels(context, prefs)
        if (list.size <= 1) return false
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        persist(prefs, next)
        if (prefs.getString(ACTIVE_KEY, "") == id) prefs.edit().putString(ACTIVE_KEY, next.first().id).apply()
        return true
    }

    fun create(context: Context, prefs: SharedPreferences, name: String): AiChannel {
        val n = channels(context, prefs).size + 1
        val ch = AiChannel(
            id = "ch_" + System.currentTimeMillis().toString(36) + "_" + n,
            name = name.ifBlank { "通道 $n" },
        )
        upsert(context, prefs, ch)
        prefs.edit().putString(ACTIVE_KEY, ch.id).apply()
        return ch
    }

    fun apiKey(context: Context, channelId: String): String =
        runCatching { SecretStore.get(context, keyName(channelId)) }.getOrNull().orEmpty()

    fun setApiKey(context: Context, channelId: String, key: String) {
        runCatching { SecretStore.put(context, keyName(channelId), key.trim()) }
    }

    private fun keyName(channelId: String) = "aiChannelKey_" + channelId

    /**
     * 作废进程内缓存。
     *
     * 备份恢复会直接重写 prefs 里的 `aiChannelsJson`，绕过了本对象的写路径；
     * 不清缓存的话恢复完还按旧通道发请求（用户看到的是"恢复了但 AI 还在用旧 key"）。
     */
    fun invalidate() {
        cache = null
    }

    /** 从老的单通道配置迁出一条 `default` 通道。只读 prefs 原始 key，不经过本对象（避免递归）。 */
    private fun migrateFromLegacy(context: Context, prefs: SharedPreferences): List<AiChannel> {
        val provider = prefs.getString("aiProvider", "openai").let {
            if (it in setOf("openai", "anthropic", "gemini")) it ?: "openai" else "openai"
        }
        val endpoint = prefs.getString("aiEndpoint", "https://api.openai.com/v1").orEmpty()
            .ifBlank { "https://api.openai.com/v1" }
        val model = prefs.getString("aiModel", "gpt-4.1-mini").orEmpty().ifBlank { "gpt-4.1-mini" }
        val temp = prefs.getFloat("aiTemperature", 0.2f)
        val headers = prefs.getString("aiCustomHeadersJson", "{}").orEmpty().ifBlank { "{}" }
        val body = prefs.getString("aiCustomBodyJson", "{}").orEmpty().ifBlank { "{}" }
        // key：新位置没有才从老位置搬，老位置可能是 SecretStore 也可能是明文 prefs（更早的版本）
        val existing = apiKey(context, AiChannel.DEFAULT_ID)
        if (existing.isBlank()) {
            val legacy = runCatching { SecretStore.get(context, "aiApiKey") }.getOrNull()
                ?: prefs.getString("aiApiKey", "").orEmpty()
            if (legacy.isNotBlank()) setApiKey(context, AiChannel.DEFAULT_ID, legacy)
        }
        return listOf(
            AiChannel(
                id = AiChannel.DEFAULT_ID,
                name = "默认通道",
                provider = provider,
                endpoint = endpoint,
                model = model,
                temperature = temp,
                headersJson = headers,
                bodyJson = body,
            ),
        )
    }

    private fun persist(prefs: SharedPreferences, list: List<AiChannel>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(CHANNELS_KEY, arr.toString()).apply()
        cache = list
    }
}
