package com.soreverse.mcp.core

/**
 * rizin 命令链的静态分类。
 *
 * 为什么需要：只读命令的结果会被缓存，但**命令链**里可能夹着会改动分析数据库的
 * 命令（`s 0x1234; af; pdfj`），也可能整条就是一次分析（`aaaa`）。旧实现只看第一段，
 * 于是：
 *  - `aaaa` / `aaa` 被当成只读 → 分析跑完**不清缓存**，之后的 `aflj` / `agCj` 仍返回
 *    分析前的空结果（用户看到「分析了还是空的」）；
 *  - `s …; af; …` 被当成只读 → 整条结果被缓存，`af` 带来的状态变化不触发失效。
 *
 * 现在的规则：**逐段**看命令头
 *  - 任一段属于分析类（aa/af/ax/ab/ah/an/ap/ar/ae… 或 `e analysis` / `e asm` / `e graph`）
 *    → [RzCmdKind.MUTATING]：不缓存，且执行后清空缓存；
 *  - 全部段命中只读白名单 → [RzCmdKind.READ_ONLY]：可缓存；
 *  - 出现白名单外、又不像分析类的（`wx`、`?v`…）→ [RzCmdKind.UNKNOWN]：不缓存也不清空。
 *
 * 白名单是**偏保守**的：漏写只会少缓存（性能），不会返回错误结果。
 */
internal enum class RzCmdKind { READ_ONLY, MUTATING, UNKNOWN }

/** 只读命令头（不含参数）。漏加只是不缓存，不会出错。 */
private val RZ_READ_ONLY_HEADS = setOf(
    // 定位 / 配置 / 杂项
    "s", "k", "e", "?", "q",
    // 打印
    "pd", "pdj", "pDj", "pdfj", "pda", "pdc", "pdr", "px", "pxj", "ps", "p8", "p6",
    // 图
    "agf", "agfj", "agC", "agCj", "agc", "agcj", "agg", "aggj",
    // 分析结果查询（只读）
    "afl", "aflj", "afb", "afbj", "afi", "afij", "afv", "afvj", "afs", "afsj",
    "axt", "axtj", "axf", "axfj", "av", "avj",
    // 信息（i*）
    "ih", "ihj", "iS", "iSj", "iSSj", "iz", "izj", "izzj", "izq", "is", "isj",
    "ii", "iij", "il", "ilj", "ir", "irj", "iT", "iTj", "iV", "iVj", "ie", "iej",
    "iH", "iHj", "iB", "iBj", "iM", "iMj", "iE", "iEj",
)

/** 会改动分析数据库的命令头（`aaa` / `aaaa` 这类整词也在这里）。 */
private val RZ_MUTATING_HEADS = setOf(
    "aa", "aaa", "aaaa", "af", "ax", "ab", "ah", "an", "ap", "ar", "ae",
)

/** 分析类命令前缀（覆盖 `af+`、`afn`、`aac`、`aafs`、`axc` 等带后缀的写法）。 */
private val RZ_MUTATING_PREFIXES = listOf("aa", "af", "ax", "ab", "ah", "an", "ap", "ar", "ae")

/** 会改动分析状态的配置项（`e analysis.*` / `e asm.*` / `e graph.*` / `e bin.*`）。 */
private val RZ_MUTATING_CONFIG_PREFIXES = listOf("analysis", "asm", "graph", "bin.")

internal fun classifyRzCommand(command: String): RzCmdKind {
    var sawAny = false
    var sawUnknown = false
    // rizin 的命令链分隔符有 ';'、换行、回车三种，少认一种就等于漏检（`s 0x10\nwx 90 @ 0x10`）。
    for (raw in command.split(';', '\n', '\r')) {
        val seg = raw.trim()
        if (seg.isEmpty()) continue
        val head = seg.substringBefore(' ').substringBefore('\t').trim()
        if (head.isEmpty()) continue
        sawAny = true
        if (head == "e") {
            val rest = seg.removePrefix("e").trim()
            if (RZ_MUTATING_CONFIG_PREFIXES.any { rest.startsWith(it) }) return RzCmdKind.MUTATING
            continue
        }
        if (head in RZ_READ_ONLY_HEADS) continue
        if (head in RZ_MUTATING_HEADS || RZ_MUTATING_PREFIXES.any { head.startsWith(it) }) {
            return RzCmdKind.MUTATING
        }
        sawUnknown = true
    }
    if (!sawAny || sawUnknown) return RzCmdKind.UNKNOWN
    return RzCmdKind.READ_ONLY
}

/** 整条命令链都可以安全缓存。 */
internal fun isRzReadOnlyCommand(command: String): Boolean =
    classifyRzCommand(command) == RzCmdKind.READ_ONLY

/** 命令链会改动分析数据库（需要清空缓存）。 */
internal fun mutatesRzAnalysis(command: String): Boolean =
    classifyRzCommand(command) == RzCmdKind.MUTATING
