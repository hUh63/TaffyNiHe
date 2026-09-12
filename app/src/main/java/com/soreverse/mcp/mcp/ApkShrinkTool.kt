package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: APK 体积分析与瘦身建议（对标 MT/NP 的「APK 体积分析/优化」）。
 *
 * 只读分析: 解包后按类别(dex/res/lib/assets/meta/签名/其他)聚合体积, 列出最大条目,
 * 并给出可操作瘦身建议(多 ABI 裁剪、资源压缩、冗余 dex、去 v1 签名等)。
 * 不做实际删除/重打包 —— 给出结论后由用户用现有编辑工具执行。
 */
object ApkShrinkTool {

    private data class Ent(val name: String, val size: Long, val compressed: Long)

    private fun categorize(name: String): String = when {
        name.matches(Regex("classes\\d*\\.dex")) -> "dex"
        name.startsWith("res/") -> "res"
        name.startsWith("lib/") -> "lib"
        name.startsWith("assets/") -> "assets"
        name == "resources.arsc" || name.startsWith("AndroidManifest") -> "meta"
        name.startsWith("META-INF/") -> "signature"
        else -> "other"
    }

    private fun human(v: Long): String {
        if (v < 1024) return "$v B"
        val kb = v / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.2f MB".format(kb / 1024.0)
    }

    val shrink: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_shrink",
            "【APK 体积分析/瘦身建议】只读分析 APK 各条目/类别体积占用(dex/res/lib/assets/签名/其他), 列出最大条目并给出可操作瘦身建议(多 ABI 裁剪/资源压缩/冗余 dex/去 v1 签名)。",
            "Read-only APK size analysis: aggregate entry sizes by category (dex/res/lib/assets/signature/other), list the largest entries and give actionable slimming suggestions (ABI trimming, resource compression, redundant dex, dropping v1 signature).",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径"
                "topN" int "每类最多列出的最大条目数(默认 20)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path(APK)", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val topN = args.intValue("topN", 20).coerceIn(1, 500)

            return runCatching {
                val catBytes = HashMap<String, Long>()
                val catCount = HashMap<String, Int>()
                val abiBytes = HashMap<String, Long>()
                val all = ArrayList<Ent>()
                var rawTotal = 0L
                var compTotal = 0L
                ZipFile(file).use { zf ->
                    val e = zf.entries()
                    while (e.hasMoreElements()) {
                        val entry = e.nextElement()
                        if (entry.isDirectory) continue
                        val size = entry.size.coerceAtLeast(0L)
                        val comp = entry.compressedSize.coerceAtLeast(0L)
                        rawTotal += size
                        compTotal += comp
                        val name = entry.name
                        val cat = categorize(name)
                        catBytes[cat] = (catBytes[cat] ?: 0L) + size
                        catCount[cat] = (catCount[cat] ?: 0) + 1
                        if (cat == "lib") {
                            val abi = name.removePrefix("lib/").substringBefore('/')
                            abiBytes[abi] = (abiBytes[abi] ?: 0L) + size
                        }
                        all.add(Ent(name, size, comp))
                    }
                }
                val categories = JSONArray()
                catBytes.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    categories.put(JSONObject().put("category", k).put("bytes", v).put("human", human(v)).put("entries", catCount[k] ?: 0))
                }
                val abis = JSONArray()
                abiBytes.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    abis.put(JSONObject().put("abi", k).put("bytes", v).put("human", human(v)))
                }
                val largest = JSONArray()
                all.sortedByDescending { it.size }.take(topN).forEach {
                    largest.put(JSONObject().put("name", it.name).put("bytes", it.size).put("human", human(it.size)).put("compressed", it.compressed))
                }

                val suggestions = JSONArray()
                if (abiBytes.size > 1) suggestions.put("包含 ${abiBytes.size} 个 ABI(原生库)。若非必须多架构, 保留目标架构、删除其余 lib/<abi>/ 可显著减小体积。")
                val resBytes = catBytes["res"] ?: 0L
                if (resBytes > 0) suggestions.put("资源(res/)占 ${human(resBytes)}。可检查 PNG 冗余/未压缩、重复图片、无用多语言 resources.arsc。")
                val dexCount = catCount["dex"] ?: 0
                val dexBytes = catBytes["dex"] ?: 0L
                if (dexCount > 2 || dexBytes > 8L * 1024 * 1024) suggestions.put("存在 $dexCount 个 dex(${human(dexBytes)})。确认是否已开启 R8/ProGuard 去未用代码; 多 dex 可合并减少冗余。")
                val sigBytes = catBytes["signature"] ?: 0L
                if (sigBytes > 0) suggestions.put("签名(META-INF)占 ${human(sigBytes)}; 若已用 v2/v3, 可去除 v1 签名条目(META-INF/*.SF/.RSA/.MF)。")
                if (suggestions.length() == 0) suggestions.put("未发现明显可优化项; 体积分布均匀。")

                ok(JSONObject()
                    .put("path", path)
                    .put("rawTotal", rawTotal).put("compressedTotal", compTotal)
                    .put("rawHuman", human(rawTotal)).put("compressedHuman", human(compTotal))
                    .put("entryCount", all.size)
                    .put("categories", categories)
                    .put("abis", abis)
                    .put("largest", largest)
                    .put("suggestions", suggestions))
            }.getOrElse { e ->
                err("APK_ANALYZE_FAILED", "体积分析失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    val ALL = listOf(shrink)
}
