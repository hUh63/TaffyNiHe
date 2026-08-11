package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 塔菲逆核: taffy_dex_inspect —— 本地 dex/APK 静态分析（无需 root / eBPF / Shizuku）。
 *
 * 定位：作为 eBPFDexDumper（需 root + eBPF）的非 root 替代品。基于 dexlib2 静态解析
 * APK/dex 文件，输出类/方法/字段/字符串摘要。普通手机用户也能用。
 */
object LocalDexInspectTools {

    /** 把 content:// URI / 绝对路径 / APK / dex 路径统一处理为 dex 文件列表（APK 会解压提取所有 classes*.dex）。 */
    private data class InspectSources(val dexFiles: List<File>, val originLabel: String, val fromUri: Boolean)

    private fun resolveSources(ctx: android.content.Context, path: String): InspectSources {
        // content:// URI → 复制到缓存
        if (path.startsWith("content://")) {
            val tmp = File(ctx.cacheDir, "dexinspect_${System.currentTimeMillis()}.bin")
            ctx.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot open content URI: $path")
            val label = path
            return resolveFromFile(tmp, label, fromUri = true)
        }
        return resolveFromFile(File(path), path, fromUri = false)
    }

    private fun resolveFromFile(file: File, label: String, fromUri: Boolean): InspectSources {
        if (!file.exists()) throw IllegalStateException("文件不存在: $label")
        val name = file.name.lowercase()
        if (name.endsWith(".dex")) return InspectSources(listOf(file), label, fromUri)
        if (name.endsWith(".apk") || name.endsWith(".jar") || name.endsWith(".zip")) {
            val dexFiles = mutableListOf<File>()
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList().filter { it.name.endsWith(".dex") && !it.isDirectory }
                for (e in entries) {
                    val out = File(file.parentFile ?: file.absoluteFile.parentFile ?: file.absoluteFile, "_dx_${e.name.replace('/', '_')}")
                    zip.getInputStream(e).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    dexFiles.add(out)
                }
            }
            if (dexFiles.isEmpty()) throw IllegalStateException("APK/JAR 中未发现 dex 文件")
            return InspectSources(dexFiles, label, fromUri)
        }
        // 兜底：当作 dex 文件尝试
        return InspectSources(listOf(file), label, fromUri)
    }

    private fun loadDexFiles(sources: List<File>): List<DexFile> {
        val out = mutableListOf<DexFile>()
        for (f in sources) {
            try {
                out.add(DexFileFactory.loadDexFile(f, Opcodes.getDefault()))
            } catch (e: Exception) {
                // 跳过坏 dex 继续
            }
        }
        return out
    }

    private fun summaryFor(dexes: List<DexFile>, label: String, limit: Int): JSONObject {
        val totalClasses = dexes.sumOf { it.classes.count() }
        val totalMethods = dexes.sumOf { d -> d.classes.sumOf { c -> c.virtualMethods.count() + c.directMethods.count() } }
        val totalFields = dexes.sumOf { d -> d.classes.sumOf { c -> c.fields.count() } }
        val totalStrings = dexes.sumOf { it.strings?.toList()?.size ?: 0 }
        val topClasses = mutableListOf<String>()
        var n = 0
        for (dex in dexes) {
            for (c in dex.classes) {
                if (n >= limit) break
                topClasses.add(c.type)
                n++
            }
            if (n >= limit) break
        }
        return JSONObject()
            .put("origin", label)
            .put("dexCount", dexes.size)
            .put("classCount", totalClasses)
            .put("methodCount", totalMethods)
            .put("fieldCount", totalFields)
            .put("stringCount", totalStrings)
            .put("topClasses", JSONArray().apply { topClasses.forEach { put(it) } })
            .put("hint", "无需 root / eBPF / Shizuku；普通 APK/dex 静态解析（替代 eBPFDexDumper 的非 root fallback）。")
    }

    private fun classesFor(dexes: List<DexFile>, limit: Int, filter: String): JSONObject {
        val re = if (filter.isBlank()) null else runCatching { Regex(filter) }.getOrNull()
        val arr = JSONArray()
        var count = 0
        for (dex in dexes) {
            for (c in dex.classes) {
                val type = c.type
                if (re != null && !re.containsMatchIn(type)) continue
                val methods = c.virtualMethods.count() + c.directMethods.count()
                arr.put(JSONObject()
                    .put("type", type)
                    .put("super", c.superclass ?: "")
                    .put("access", c.accessFlags)
                    .put("methodCount", methods)
                    .put("fieldCount", c.fields.count()))
                count++
                if (count >= limit) break
            }
            if (count >= limit) break
        }
        return JSONObject().put("count", count).put("truncated", count >= limit).put("classes", arr)
    }

    private fun stringsFor(dexes: List<DexFile>, limit: Int, filter: String): JSONObject {
        val re = if (filter.isBlank()) null else runCatching { Regex(filter, RegexOption.IGNORE_CASE) }.getOrNull()
        val arr = JSONArray()
        var count = 0
        for (dex in dexes) {
            for (s in dex.strings.toList()) {
                if (re != null && !re.containsMatchIn(s)) continue
                arr.put(s)
                count++
                if (count >= limit) break
            }
            if (count >= limit) break
        }
        return JSONObject().put("count", count).put("truncated", count >= limit).put("strings", arr)
    }

    private fun methodsFor(dexes: List<DexFile>, className: String, limit: Int): JSONObject {
        val target = className.removePrefix("L").removeSuffix(";")
        val targetType = "L$target;"
        val arr = JSONArray()
        var count = 0
        for (dex in dexes) {
            val klass = dex.classes.firstOrNull { it.type == targetType || it.type.endsWith(className) } ?: continue
            val all = klass.virtualMethods + klass.directMethods
            for (m in all) {
                val params = m.parameters.joinToString(", ") { it.type ?: "" }
                arr.put(JSONObject()
                    .put("name", m.name)
                    .put("returnType", m.returnType ?: "")
                    .put("params", params)
                    .put("access", m.accessFlags))
                count++
                if (count >= limit) break
            }
            if (count >= limit) break
        }
        return JSONObject().put("class", className).put("count", count).put("truncated", count >= limit).put("methods", arr)
    }

    private fun searchStrings(dexes: List<DexFile>, pattern: String, limit: Int): JSONObject {
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return JSONObject().put("error", "正则无效: $pattern").put("hits", JSONArray())
        val arr = JSONArray()
        var count = 0
        for (dex in dexes) {
            for (s in dex.strings.toList()) {
                if (!re.containsMatchIn(s)) continue
                arr.put(s)
                count++
                if (count >= limit) break
            }
            if (count >= limit) break
        }
        return JSONObject().put("pattern", pattern).put("hits", count).put("truncated", count >= limit).put("strings", arr)
    }

    val dexInspect: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dex_inspect",
            "【DEX 静态分析（无需 root）】本地解析 APK/dex 文件，输出类/方法/字段/字符串摘要。eBPFDexDumper 的非 root 替代品，普通手机用户可用。action=summary 总体摘要; action=classes 列出类(可正则); action=strings 提取字符串(可正则); action=methods 列出指定类的方法(-c 类名); action=search 正则搜索字符串(-p)。",
            "Local DEX/APK static analysis (no root/eBPF needed). Non-root alternative to eBPFDexDumper. summary/classes(strings(methods(class=str filter)))/search(pattern).",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("summary | classes | strings | methods | search", "summary", "classes", "strings", "methods", "search")
                "path" str "APK 或 dex 文件路径（content:// 或绝对路径）"
                "limit" int "结果数量上限（默认 500）"
                "filter" str "classes/strings 用的正则过滤"
                "class" str "methods 用的类名（FQN，如 com.example.Foo）"
                "pattern" str "search 用的正则"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "summary")
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "需要 path 参数（APK 或 dex 路径）", "action", action)
            val limit = args.intValue("limit", 500).coerceIn(1, 10000)
            return runCatching {
                val sources = resolveSources(ctx.context, path)
                val dexes = loadDexFiles(sources.dexFiles)
                if (dexes.isEmpty()) return err("NO_DEX", "未能加载任何 dex 文件", "action", action)
                val out = JSONObject()
                    .put("action", action)
                    .put("origin", sources.originLabel)
                    .put("fromUri", sources.fromUri)
                    .put("dexLoaded", dexes.size)
                when (action) {
                    "summary" -> out.put("result", summaryFor(dexes, sources.originLabel, limit))
                    "classes" -> out.put("result", classesFor(dexes, limit, args.str("filter")))
                    "strings" -> out.put("result", stringsFor(dexes, limit, args.str("filter")))
                    "methods" -> {
                        val cls = args.str("class")
                        if (cls.isBlank()) return err("INVALID_ARGUMENT", "需要 class 参数", "action", action)
                        out.put("result", methodsFor(dexes, cls, limit))
                    }
                    "search" -> {
                        val pat = args.str("pattern")
                        if (pat.isBlank()) return err("INVALID_ARGUMENT", "需要 pattern 参数", "action", action)
                        out.put("result", searchStrings(dexes, pat, limit))
                    }
                    else -> return err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
                out.put("ok", true)
                out
            }.getOrElse { e -> err("DEX_INSPECT_FAILED", "解析失败: ${e.message}", "action", action) }
        }
    }

    val ALL = listOf(dexInspect)
}