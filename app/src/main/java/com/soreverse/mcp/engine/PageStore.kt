// 塔菲逆核 v1.3.54: 分页缓存改为有界（上游 SOMCP 1.0.22 / PR #44 借鉴）。
// 无界 map 在客户端丢弃游标或反复请求第一页时会把整份 JSON 一直留在内存里。
package com.soreverse.mcp.engine

import java.util.LinkedHashMap
import java.util.UUID
import org.json.JSONObject

internal class PageStore {
    internal data class PageSlice(
        val field: String,
        val items: List<JSONObject>,
        val hasMore: Boolean,
        val nextCursor: String?,
        val returnedCount: Int,
        val limit: Int,
        val totalCount: Int
    )

    private data class PageState(val field: String, val items: List<JSONObject>, val offset: Int, val limit: Int)

    /**
     * 有界、按插入顺序的分页缓存（最多 [maxPages] 页，超出后淘汰最旧一项）。
     * 每个条目持有完整 item 列表（最多 limit 项），无界 map 会在客户端放弃游标时泄漏。
     */
    private val lock = Any()
    private val maxPages = 64
    /**
     * 所有缓存页实际持有的 item 引用数上限。
     * [maxPages] 只限页数，64 页各自持有一份大列表时仍会驻留几十万条 JSON；
     * 这里按「不同列表实例」的真实条数再兜一层。
     */
    private val maxItems = 20_000
    private val pages = object : LinkedHashMap<String, PageState>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageState>?): Boolean {
            if (size > maxPages) return true
            // items 在多页间是共享引用，按实例去重后才等于真实驻留量
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<List<JSONObject>, Boolean>())
            var total = 0
            for (v in values) {
                if (seen.add(v.items)) total += v.items.size
                if (total > maxItems) return true
            }
            return false
        }
    }

    fun first(field: String, items: List<JSONObject>, limit: Int): PageSlice = synchronized(lock) {
        slice(PageState(field, items, 0, limit.coerceIn(1, 5000)))
    }

    fun consume(cursor: String): PageSlice? = synchronized(lock) {
        pages.remove(cursor)?.let(::slice)
    }

    fun clear() {
        synchronized(lock) { pages.clear() }
    }

    private fun slice(state: PageState): PageSlice {
        val chunk = state.items.drop(state.offset).take(state.limit)
        val nextOffset = state.offset + chunk.size
        val nextCursor = if (nextOffset < state.items.size) {
            "page:${UUID.randomUUID()}".also { pages[it] = state.copy(offset = nextOffset) }
        } else {
            null
        }
        return PageSlice(
            state.field,
            chunk,
            nextCursor != null,
            nextCursor,
            chunk.size,
            state.limit,
            state.items.size
        )
    }
}
