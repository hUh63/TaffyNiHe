package com.soreverse.mcp.core

/**
 * 敏感信息脱敏（源自太墟 `core/security/SecretRedactor`）。
 *
 * 崩溃报告与日志会被导出、贴到 issue、发到群里，里面经常带着：
 * MCP access token、AI API key、`Authorization: Bearer ...`、带凭据的 URL、
 * 手机号、私钥块、JWT。上报前过一遍这里。
 *
 * 设计取舍：
 * - 正则**全部预编译**（上游修过的点：原先写在函数里，每写一行日志编译一次；
 *   崩溃路径本身就在极端环境下执行，不能在这里再分配一堆 Pattern）；
 * - 只做「保守命中」：宁可漏掉奇怪写法，也不要把正常文本大面积打成 `[REDACTED]`；
 * - 纯 Kotlin，无 Android 依赖，可单测。
 */
object SecretRedactor {

    /** 这些键名后面跟的值一律视为凭据。 */
    private const val KEY_NAMES =
        "password|passwd|pwd|passcode|secret|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|authorization|cookie|session[_-]?token"

    /** `{"api_key": "xxx"}` / `{"password": 'xxx'}` —— JSON/JS 对象写法，键带引号。 */
    private val JSON_VALUE = Regex("""(?i)(["'](?:$KEY_NAMES)["']\s*:\s*)(["'])(.*?)(\2)""")

    /** `password="xxx"` / `Authorization: 'xxx'` —— 带引号的值整体替换，保留引号。 */
    private val QUOTED_VALUE = Regex("""(?i)((?:$KEY_NAMES)\s*[:=]\s*)(["'])(.*?)(\2)""")

    /** `password=xxx` / `"api_key": xxx` —— 裸值替换到分隔符为止。 */
    private val PLAIN_VALUE = Regex("""(?i)((?:$KEY_NAMES)\s*[:=]\s*)([^\s,;&}\]\["']+)""")

    /** `Authorization: Bearer <token>`（以及 Basic / Digest / Token / Negotiate）。 */
    private val AUTH_HEADER = Regex(
        """(?i)(authorization\s*[:=]\s*)(Bearer|Basic|Digest|Token|Negotiate)\s+[^\s,;&}\]\["']+""",
    )

    /** 裸 `Bearer <token>`（没有 Authorization 前缀时）。 */
    private val BEARER = Regex("""(?i)Bearer\s+[A-Za-z0-9._~+/=-]{8,}""")

    /** 鉴权方案名本身不是凭据：`Authorization: Bearer [REDACTED]` 不能被二次替换掉。 */
    private val AUTH_SCHEMES = setOf("bearer", "basic", "digest", "token", "negotiate", "ntlm")

    /** 常见平台的 key 前缀。 */
    private val KNOWN_KEY = Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|sk-ant-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|gh[pousr]_[A-Za-z0-9]{20,})""")

    /** URL 查询串里的凭据参数。 */
    private val URL_CREDENTIAL = Regex("""(?i)([?&](?:token|key|secret|password|access_token|refresh_token|code)=)[^&\s"']+""")

    /** JWT。 */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    /** 私钥块（PEM）。 */
    private val PRIVATE_KEY = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")

    /** 中国大陆手机号。 */
    private val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")

    /** 桌面系统的主目录用户名（Android 上少见，但崩溃栈里可能带构建机路径）。 */
    private val HOME_PATH = Regex("""(/home/|/Users/)[^/\s"']+""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = s.replace(PRIVATE_KEY, "[REDACTED PRIVATE KEY]")
        // 必须排在裸值规则之前：否则 `Authorization: Bearer xxx` 只会把 scheme 当成值脱掉，
        // token 本身留下来。这套顺序是单测（redactsBearerToken）守住的。
        s = s.replace(AUTH_HEADER) { m -> "${m.groupValues[1]}${m.groupValues[2]} [REDACTED]" }
        s = s.replace(JSON_VALUE) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED]${m.groupValues[2]}"
        }
        s = s.replace(QUOTED_VALUE) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED]${m.groupValues[2]}"
        }
        s = s.replace(PLAIN_VALUE) { m ->
            val value = m.groupValues[2]
            if (value.lowercase() in AUTH_SCHEMES) m.value else "${m.groupValues[1]}[REDACTED]"
        }
        s = s.replace(BEARER, "Bearer [REDACTED]")
        s = s.replace(KNOWN_KEY, "[REDACTED]")
        s = s.replace(URL_CREDENTIAL) { m -> "${m.groupValues[1]}[REDACTED]" }
        s = s.replace(JWT, "[REDACTED JWT]")
        s = s.replace(PHONE, "[PHONE_REDACTED]")
        s = s.replace(HOME_PATH) { m -> "${m.groupValues[1]}<user>" }
        return s
    }

    /** 已知密钥值（例如配置里读到的 token）额外整体抹掉；短于 6 字符的值不处理，避免误伤正常词。 */
    fun redactKnown(text: String, secrets: Collection<String>): String {
        var s = redact(text)
        secrets.asSequence()
            .filter { it.length >= 6 }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { s = s.replace(it, "[REDACTED]") }
        return s
    }
}
