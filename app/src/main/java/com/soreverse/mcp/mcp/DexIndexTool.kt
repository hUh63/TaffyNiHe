package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.DexIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: taffy_dex_index —— DEX 索引式查询（一次遍历，13 种类型）。
 *
 * 与 taffy_dex_xref(单方法 to/from)、taffy_dex_search(DexKit 特征反查) 互补：
 * 这里回答的是「全包里谁 new 了这个类 / 谁读了写了这个字段 / 谁重写了这个方法」，
 * 纯 dexlib2，不需要 native，也不需要把 APK 反编译成 smali 树。
 */
object DexIndexTool {

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_index",
            "【DEX 索引查询】一次遍历扫描全部 dex，按 13 种类型精确反查谁引用了什么。" +
                "action=search 的 type: class(类名) | method(方法名) | field(字段名) | string(字符串,含字段初值/注解/.source) | " +
                "int(整数常量,支持 0x 与负数) | code(代码片段,如 \"invoke-virtual\" 或 \"const-string\") | " +
                "new_instance(谁 new 了类) | field_get(字段读) | field_put(字段写) | field_any(字段任意访问) | " +
                "method_call(方法调用) | method_override(方法重写) | class_use(类使用/check-cast/const-class)。" +
                "引用型(new_instance/field_*/method_call/method_override/class_use)可省略 keyword = 列全部。支持 exclude 排除包、regex、limit/offset 分页。" +
                "用法示例: 定位加固校验类→ type=string keyword=signature 看哪些类引用; 查资源 id→ type=int keyword=0x7f0a0012; " +
                "查谁调用了某方法→ type=method_call keyword=checkSign。action=types 列出全部类型。",
            "【DEX index query】One pass over all dex files, 13 query types. " +
                "type: class | method | field | string (incl. field initial values / annotations / .source) | " +
                "int (constants, accepts 0x and negatives) | code (smali-ish snippet) | " +
                "new_instance | field_get | field_put | field_any | method_call | method_override | class_use. " +
                "Reference types may omit keyword to list everything. Supports exclude (package prefixes), regex, and limit/offset paging. " +
                "Pure dexlib2 — no native, no full decompile. action=types lists all types.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("search(执行查询) | types(列出查询类型) | stats(只统计规模)", "search", "types", "stats")
                "path" str "APK 或 DEX 文件路径"
                "type" str "查询类型, 见描述(默认 class); 也接受中文: 类名/方法名/字段名/字符串/整数/代码/实例化/字段读/字段写/字段引用/方法调用/方法重写/类使用"
                "keyword" str "关键字; int 类型支持 123 / -1 / 0x7f0a0001; code 类型是 smali 片段"
                "exclude" str "排除包前缀, 逗号或换行分隔, 如 com.android.,kotlin."
                "caseSensitive" bool "区分大小写(默认 false)"
                "regex" bool "keyword 按正则解释(默认 false)"
                "limit" int "本页最多返回条数(默认 100, 上限 2000)"
                "offset" int "跳过前 N 条(分页用, 默认 0)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "search").ifBlank { "search" }
            if (action == "types") {
                val arr = JSONArray()
                for ((k, v) in DexIndex.TYPE_LABELS_ZH) {
                    arr.put(JSONObject().put("type", k).put("label", v)
                        .put("needKeyword", k in DexIndex.KEYWORD_REQUIRED))
                }
                return ok(JSONObject()
                    .put("action", "types")
                    .put("types", arr)
                    .put("hint", "type 传左列英文键即可；引用型不需要 keyword"))
            }

            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")

            if (action == "stats") {
                // 独立轻量路径：只数类/方法/指令，不构造 Hit
                val r = DexIndex.stats(path, File(ctx.context.cacheDir, "dexindex"))
                if (r.error != null) return err("DEX_SCAN_FAILED", r.error, "path", path)
                return ok(JSONObject()
                    .put("action", "stats")
                    .put("dexCount", r.dexCount)
                    .put("classes", r.scannedClasses)
                    .put("methods", r.scannedMethods)
                    .put("instructions", r.scannedInstructions))
            }

            if (action != "search") {
                return err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
            }

            val type = args.str("type", "class")
            val keyword = args.str("keyword")
            val exclude = splitList(args.str("exclude"))
            val limit = args.intValue("limit", 100).let { if (it <= 0) 100 else it }
            val offset = args.intValue("offset", 0)

            val r = DexIndex.search(
                path = path,
                type = type,
                keyword = keyword,
                excludePackages = exclude,
                caseSensitive = args.optBoolean("caseSensitive", false),
                regex = args.optBoolean("regex", false),
                limit = limit,
                offset = offset,
                tmpDir = File(ctx.context.cacheDir, "dexindex"),
            )
            if (r.error != null) {
                return err("DEX_QUERY_FAILED", r.error, "type", type)
            }

            val hits = JSONArray()
            for (h in r.hits) {
                hits.put(JSONObject()
                    .put("kind", h.kind)
                    .put("class", h.className)
                    .put("member", h.member)
                    .put("detail", h.detail)
                    .put("dex", h.dex))
            }
            val payload = JSONObject()
                .put("action", "search")
                .put("type", type)
                .put("keyword", keyword)
                .put("matched", r.matched)
                .put("returned", r.hits.size)
                .put("offset", r.offset)
                .put("limit", r.limit)
                .put("hasMore", r.hasMore)
                .put("hits", hits)
                .put("scanned", JSONObject()
                    .put("dexCount", r.dexCount)
                    .put("classes", r.scannedClasses)
                    .put("methods", r.scannedMethods)
                    .put("instructions", r.scannedInstructions))
            if (r.hasMore) {
                payload.put("nextOffset", r.offset + r.hits.size)
                payload.put("hint", "还有未返回结果，用 offset=${r.offset + r.hits.size} 继续")
            }
            return ok(payload)
        }

        private fun splitList(raw: String): List<String> =
            raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    val ALL = listOf(tool)
}
