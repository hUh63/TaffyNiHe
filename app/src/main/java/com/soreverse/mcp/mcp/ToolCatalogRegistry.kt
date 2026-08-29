package com.soreverse.mcp.mcp

class ToolCatalogRegistry(handlers: List<ToolHandler>) {
    // 防崩: 工具名重复时不抛异常（<clinit> 抛出会以 EIIE 拖垮整个应用），
    // 而是丢弃后续重复项并告警，保证目录可构建。
    private val unique: LinkedHashMap<String, ToolHandler> = linkedMapOf<String, ToolHandler>().apply {
        val dups = mutableListOf<String>()
        handlers.forEach { h ->
            val n = h.meta.name
            if (containsKey(n)) dups.add(n) else put(n, h)
        }
        if (dups.isNotEmpty()) com.soreverse.mcp.core.AppLog.e("ToolCatalogRegistry: 重复工具名已去重: $dups")
    }
    val handlers: List<ToolHandler> = unique.values.toList()
    val byName: Map<String, ToolHandler> = unique
    val names: List<String> = unique.keys.toList()
    val heavyNames: Set<String> = unique.values.filter { it.meta.heavy }.mapTo(linkedSetOf()) { it.meta.name }

    fun leanNames(popularity: Map<String, Long>? = null, promotionSlots: Int = 5): List<String> {
        val base = handlers
            .filter { it.meta.cls == ToolClass.CORE || it.meta.cls == ToolClass.META || it.meta.category == "lowlevel" }
            .mapTo(linkedSetOf()) { it.meta.name }
        if (popularity.isNullOrEmpty() || promotionSlots <= 0) return base.toList()
        val promoted = handlers.withIndex()
            .filter { it.value.meta.cls == ToolClass.EXTRA && popularity.containsKey(it.value.meta.name) }
            .sortedWith(compareByDescending<IndexedValue<ToolHandler>> { popularity.getValue(it.value.meta.name) }.thenBy { it.index })
            .take(promotionSlots)
            .map { it.value.meta.name }
        base.addAll(promoted)
        return base.toList()
    }

    fun description(name: String, zh: Boolean): String =
        byName[name]?.let { if (zh) it.meta.zh else it.meta.en } ?: name

    fun categoryOf(name: String): String? = byName[name]?.meta?.category
}
