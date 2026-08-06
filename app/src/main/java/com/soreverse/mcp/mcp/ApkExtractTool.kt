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
 * 塔菲逆核: APK/ZIP 资源提取(纯 Java zip)。
 *
 * 从 APK 里按路径/前缀/后缀把文件抠出来到工作目录, 方便单独分析。
 * 典型: 抠 lib 下的 so 出来喂给 so_open 或 rizin, 抠 assets 看配置, 抠 classes.dex 反编译。
 */
object ApkExtractTool {

    val extract: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "apk_extract",
            "【资源提取】从 APK/ZIP 里按条件把文件抠到工作目录。action=list 先列条目(可配 filter 过滤); action=extract 提取匹配的文件到 filesDir/extracted/<apk名>/。典型: 抠 .so 喂给 so_open 分析、抠 assets 看配置、抠 dex 反编译。filter 支持前缀(lib/)或后缀(.so)或包含关键字。",
            "Extract files from an APK/ZIP to the work dir. action=list to list entries (with optional filter); action=extract to pull matching files into filesDir/extracted/<apk>/. Typical: pull .so for so_open, assets for config, dex for decompile.",
            "workspace", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("list(列条目) | extract(提取)", "list", "extract")
                "path" str "APK/ZIP 绝对路径"
                "filePath" str "path 的别名"
                "filter" str "过滤: 前缀(如 lib/)、后缀(如 .so)、或包含的关键字(如 assets)。空=全部"
                "limit" int "list 最多列条数(默认 500) / extract 最多提取数(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            val action = args.str("action", "list").ifBlank { "list" }
            val filter = args.str("filter").trim()
            val limit = args.intValue("limit", if (action == "extract") 200 else 500).coerceIn(1, 20000)

            fun match(name: String): Boolean {
                if (filter.isEmpty()) return true
                return when {
                    filter.startsWith(".") -> name.endsWith(filter, ignoreCase = true) // 后缀
                    filter.endsWith("/") -> name.startsWith(filter, ignoreCase = true)  // 前缀目录
                    else -> name.contains(filter, ignoreCase = true)                    // 关键字
                }
            }

            return runCatching {
                ZipFile(input).use { zip ->
                    if (action == "list") {
                        val arr = JSONArray()
                        val entries = zip.entries()
                        var total = 0
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (e.isDirectory || !match(e.name)) continue
                            total++
                            if (arr.length() < limit) {
                                arr.put(JSONObject().put("name", e.name).put("size", e.size))
                            }
                        }
                        ok(JSONObject()
                            .put("tool", "apk_extract").put("action", "list")
                            .put("filter", filter).put("matched", total)
                            .put("returned", arr.length()).put("entries", arr))
                    } else {
                        val baseName = input.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        val outDir = File(ctx.context.filesDir, "extracted/$baseName").apply { mkdirs() }
                        val extracted = JSONArray()
                        val entries = zip.entries()
                        var count = 0
                        while (entries.hasMoreElements() && count < limit) {
                            val e = entries.nextElement()
                            if (e.isDirectory || !match(e.name)) continue
                            val safe = e.name.replace("../", "_").trimStart('/')
                            val outFile = File(outDir, safe)
                            outFile.parentFile?.mkdirs()
                            runCatching {
                                zip.getInputStream(e).use { input2 -> outFile.outputStream().use { input2.copyTo(it) } }
                                extracted.put(JSONObject().put("name", e.name).put("savedTo", outFile.absolutePath).put("size", outFile.length()))
                                count++
                            }
                        }
                        ok(JSONObject()
                            .put("tool", "apk_extract").put("action", "extract")
                            .put("filter", filter).put("extractedCount", count)
                            .put("outputDir", outDir.absolutePath).put("files", extracted)
                            .put("hint", "提取的 .so 可用 so_open 打开分析; dex 可用 jadx_decompile/baksmali_decode"))
                    }
                }
            }.getOrElse { e ->
                err("EXTRACT_FAILED", "提取失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }
}
