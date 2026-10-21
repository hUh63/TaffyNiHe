package top.wkbin.taixu.harness

import java.text.Normalizer

/**
 * 解析用户消息中的 @提及 名称（技能 / MCP 服务 / 子智能体触发）。
 *
 * ## 为什么需要 [knownNames]
 * 早期实现只用 `@([^\s@,，:：\n]+)` 这种"空白/少数标点即断"的正则，对**含空格的技能名**完全失效：
 * `@Git 敏捷工作流` 只会解析出 `Git`，导致该技能静默不生效（而 UI 侧因支持全名高亮，用户以为已识别）。
 * 传入 [knownNames] 后启用**已知名单最长优先匹配**，可正确吞掉技能名内部的空格。
 *
 * ## 归一化
 * 统一做 NFKC 折半角（全角 `＠`/字母/数字 → 半角）后再匹配，避免 `@Ｇｉｔ` 这类全角输入失配。
 * 返回的名称一律 lowercase，便于与技能 id / name / triggerCommand 做大小写无关比较。
 */
object MentionExtractor {

    /**
     * 显式终止符：中英文标点、括号、引号、命令分隔等，出现即截断。
     * 注意 `[` `]` 必须转义——未转义的 `]` 会提前终结正则字符类，使后续终止符全部失效。
     * 聊天 UI 的 @提及 高亮必须复用同一字符类，避免「高亮了但后端没解析」的口径漂移。
     */
    const val MENTION_HARD_BOUNDARY =
        "\\s@,，:：;；!！?？。、()（）\\[\\]【】{}<>《》\"'“”‘’`|/\\\\"

    private val BOUNDARY_CHARS = setOf(
        '@', ',', '，', ':', '：', ';', '；', '!', '！', '?', '？', '。', '、',
        '(', ')', '（', '）', '[', ']', '【', '】', '{', '}', '<', '>', '《', '》',
        '"', '\'', '“', '”', '‘', '’', '`', '|', '/', '\\'
    )

    /** 是否属于提及词元的边界字符（空白、@ 或标点符号） */
    fun isBoundaryChar(c: Char): Boolean = c.isWhitespace() || c in BOUNDARY_CHARS

    private val GENERIC_REGEX = Regex("""@([^$MENTION_HARD_BOUNDARY]+)""")

    /**
     * @param text 用户原始消息
     * @param knownNames 已知实体名（技能 name / id / triggerCommand 去掉前导 `/`、MCP 服务名等）。
     *                  传入后启用最长优先匹配，可正确处理名称内含空格的情况；传空则退化为通用解析。
     */
    fun parse(text: String, knownNames: Collection<String> = emptyList()): Set<String> {
        if (!text.contains("@") && !text.contains("＠")) return emptySet()
        val normalized = normalize(text)
        val result = linkedSetOf<String>()

        // 用可变字符数组「擦除」已识别的片段，避免重叠匹配与二次拆分。
        val buffer = StringBuilder(normalized)

        // 第一轮：已知名单最长优先匹配（解决名称含空格 / 与正文边界难分的问题）。
        val candidates = knownNames
            .asSequence()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("/").trim() }
            .filter { it.isNotBlank() }
            .map { normalize(it).lowercase() }
            .distinct()
            .sortedByDescending { it.length }
            .toList()

        for (candidate in candidates) {
            // 边界保护：@ 之后必须是「终止符或文本结束」，避免 "Git" 误吞 "GitHub" 的前缀；
            // @ 之前必须不是词字符，避免邮箱 user@host.com 里的 @host 被当成提及。
            val pattern = Regex(
                """(?<![\w.+-])@${Regex.escape(candidate)}(?=$|[$MENTION_HARD_BOUNDARY])""",
                RegexOption.IGNORE_CASE,
            )
            var match = pattern.find(buffer)
            var guard = 0
            while (match != null && guard++ < MAX_MENTIONS_PER_MESSAGE) {
                val start = match.range.first
                val end = match.range.last + 1
                // 擦除：整段替换为等长空格，防止通用轮把这些字符再拆出来。
                for (i in start until end) buffer.setCharAt(i, ' ')
                result += candidate
                match = pattern.find(buffer)
            }
        }

        // 第二轮：通用兜底（覆盖未登记的实体），此时剩余的 @xxx 都不会再被误拆。
        for (match in GENERIC_REGEX.findAll(buffer.toString())) {
            // 邮箱/标识符保护：@ 前一个字符是词字符时不视为提及（如 user@host.com）。
            val atIndex = match.range.first
            if (atIndex > 0 && isWordChar(buffer[atIndex - 1])) continue
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty()) result += name.lowercase()
        }
        return result
    }

    /** 判断是否词字符（字母/数字/下划线/点/加号/减号）；用于邮箱与标识符保护。 */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '.' || c == '+' || c == '-'

    /** NFKC 归一：全角字符折半角，兼容全角 ＠ 与全角字母数字。 */
    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)

    /** 单条消息的最大提及数，防病态输入导致 O(n·m) 退化。 */
    private const val MAX_MENTIONS_PER_MESSAGE = 64
}
