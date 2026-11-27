package com.soreverse.mcp.core

/**
 * 工具调用的轻量性能账本。
 *
 * 目的是回答一个具体问题：**哪个工具在拖慢整条链路**。
 * 塔菲的 MCP 服务端把每次工具调用丢进线程池执行，客户端只看到总耗时，
 * 分不清「工具本身慢」和「在排队」——这里只统计**真正执行**的那段时间。
 *
 * 设计取舍：
 * - 每个工具一条聚合记录（调用次数/总耗时/峰值/最近一次/错误数），不做直方图：
 *   手机内存有限，P50/P95 需要保留样本，收益不值这个成本。
 * - 只存在内存里，重启清零；`taffy_perf` 可以读、可以重置。
 * - 记录本身是 O(1) 的两次原子加，不做字符串拼接（不能因为这个统计让调用变慢）。
 */
internal object ToolPerf {

    /** 上限：工具目录 160+，留一倍余量；超出后不再新增（避免被动态工具名打爆）。 */
    private const val MAX_TOOLS = 400

    private class Stat {
        var calls = 0L
        var totalMs = 0L
        var maxMs = 0L
        var lastMs = 0L
        var errors = 0L
    }

    private val stats = java.util.concurrent.ConcurrentHashMap<String, Stat>()

    fun record(name: String, elapsedMs: Long, failed: Boolean) {
        val ms = if (elapsedMs < 0) 0 else elapsedMs
        val s = stats[name] ?: run {
            if (stats.size >= MAX_TOOLS) return
            val fresh = Stat()
            // putIfAbsent 返回「原先的值」或 null（插入成功）；不能用临时实例，否则统计会丢
            stats.putIfAbsent(name, fresh) ?: fresh
        }
        synchronized(s) {
            s.calls++
            s.totalMs += ms
            s.lastMs = ms
            if (ms > s.maxMs) s.maxMs = ms
            if (failed) s.errors++
        }
    }

    fun reset() = stats.clear()

    fun distinctTools(): Int = stats.size

    /** 按总耗时降序；top<=0 表示全部。 */
    fun snapshot(top: Int = 0): org.json.JSONArray {
        val rows = stats.map { (name, s) ->
            val calls: Long
            val total: Long
            val max: Long
            val last: Long
            val errors: Long
            synchronized(s) {
                calls = s.calls
                total = s.totalMs
                max = s.maxMs
                last = s.lastMs
                errors = s.errors
            }
            org.json.JSONObject()
                .put("tool", name)
                .put("calls", calls)
                .put("totalMs", total)
                .put("avgMs", if (calls > 0) total / calls else 0L)
                .put("maxMs", max)
                .put("lastMs", last)
                .put("errors", errors)
        }.sortedByDescending { it.optLong("totalMs") }
        val arr = org.json.JSONArray()
        val n = if (top > 0) minOf(top, rows.size) else rows.size
        for (i in 0 until n) arr.put(rows[i])
        return arr
    }
}
