package com.soreverse.mcp.engine

/**
 * 重型 rizin 查询结果缓存（[EngineRuntime.rzFunctions] / [EngineRuntime.rzCfg] / [EngineRuntime.rzXrefs]）。
 *
 * 这三个入口的 native 实现和 `rzCommand` 不一样：它们各自 `rz_core_new` → load SO →
 * `rz_core_analysis_all`，一个 6.7 MB 的 SO 要 3~10 秒（`rzCommand` 走共享 RzCore，只有第一次付这笔钱）。
 * 而分析页的真实操作路径会反复打这几个入口：
 *  - 打开函数详情 → 控制流页签 → 返回 → 再点同一个函数；
 *  - CFG 的「入口兜底」先 `rzFunctions` 再 `rzCfg` 验证；
 *  - 交叉引用页合并 native rzXrefs 兜底结果。
 *
 * 结果本身只依赖「字节内容 + 参数」，所以可以缓存。key 里带**全量内容指纹**：
 * 编辑内核是等长原位写入（节区表/符号表都不搬），首尾采样对中间补丁完全无感，
 * 用它做 key 会出现「补丁打了，CFG 还是旧的」这类极难查的问题。
 *
 * 预算按**字符数**算而不是条数：CFG JSON 单条可能就是几百 KB，按条数限制挡不住内存。
 */
internal object RzQueryCache {
    /** 总预算（字符数）。1 字符 ≈ 2 字节（UTF-16），6M 字符约 12 MB。 */
    private const val MAX_TOTAL_CHARS = 6_000_000

    /** 单条上限；超过就不缓存（重算比常驻一份大 JSON 更划算）。 */
    private const val MAX_ENTRY_CHARS = 1_500_000

    private val map = LinkedHashMap<String, String>(8, 0.75f, true)
    private var total = 0

    fun get(key: String): String? = synchronized(map) { map[key] }

    fun put(key: String, value: String) {
        if (value.length > MAX_ENTRY_CHARS) return
        synchronized(map) {
            map[key]?.let { total -= it.length }
            map[key] = value
            total += value.length
            val it = map.entries.iterator()
            while (total > MAX_TOTAL_CHARS && it.hasNext()) {
                total -= it.next().value.length
                it.remove()
            }
        }
    }

    fun clear() = synchronized(map) {
        map.clear()
        total = 0
    }

    /** 仅供单测/诊断：当前缓存条数与字符占用。 */
    fun stats(): Pair<Int, Int> = synchronized(map) { map.size to total }
}

/**
 * 全量内容指纹：CRC32（JDK 内部实现，10 MB 约 3~5 ms）+ 长度。
 *
 * 相比一次 3~10 秒的全量分析可以忽略；比 Kotlin 逐字节 FNV-1a 快一个数量级，
 * 而这里只需要「内容变了 key 必变」，不需要密码学强度。
 */
internal fun rzContentStamp(bytes: ByteArray): String {
    val crc = java.util.zip.CRC32()
    crc.update(bytes)
    return java.lang.Long.toHexString(crc.value) + ":" + bytes.size
}
