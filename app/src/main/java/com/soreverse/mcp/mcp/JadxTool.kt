package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.intValue
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.impl.NoOpCodeCache
import jadx.api.impl.SimpleCodeWriter
import jadx.core.plugins.files.IJadxFilesGetter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Path

/**
 * 塔菲逆核: jadx dex→Java 反编译工具。
 *
 * 纯 Java 反编译引擎(io.github.skylot:jadx-core + jadx-dex-input),作为 MCP 工具直接内置,
 * 无需外部进程。输入 apk/dex/jar,输出可读 Java 源码。
 *
 * 目标用户为逆向场景高配机型,jadx 官方虽标注"不适合 Android",但 minSdk 26 + 充足内存可用。
 */
object JadxTool {

    /**
     * 把 jadx 的临时/缓存/配置目录重定向到 app 私有目录,避开 Android 上不可写的系统 temp。
     */
    private fun buildArgs(input: File, outDir: File, appCache: File): JadxArgs {
        return JadxArgs().apply {
            setInputFile(input)
            this.outDir = outDir
            // 降低内存/加速:简单代码写出 + 不缓存(单次 dump 场景)
            codeWriterProvider = java.util.function.Function { jadxArgs -> SimpleCodeWriter(jadxArgs) }
            codeCache = NoOpCodeCache()
            // 重定向 jadx 的临时/缓存/配置目录到 app 私有目录(Android 系统 temp 不可写)
            setFilesGetter(object : IJadxFilesGetter {
                override fun getConfigDir(): Path = File(appCache, "jadx-config").apply { mkdirs() }.toPath()
                override fun getCacheDir(): Path = File(appCache, "jadx-cache").apply { mkdirs() }.toPath()
                override fun getTempDir(): Path = File(appCache, "jadx-tmp").apply { mkdirs() }.toPath()
            })
        }
    }

    val decompile: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "jadx_decompile",
            "【DEX→Java 反编译】用 jadx 把 APK/DEX/JAR 反编译成可读 Java 源码。action=save 导出全部到目录并返回路径; action=class 反编译单个类(className)直接返回源码; action=list 列出所有类名。",
            "Decompile APK/DEX/JAR to readable Java using jadx. action=save exports everything to a directory; action=class returns source of one class (className); action=list returns all class names.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("save (default) | class | list", "save", "class", "list")
                "path" str "APK/DEX/JAR 的绝对路径"
                "filePath" str "path 的别名"
                "className" str "完整类名(action=class),如 com.example.Foo"
                "limit" int "最多返回的类名数量(action=list, 默认 500)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) {
                return err("INVALID_ARGUMENT", "缺少参数 path(APK/DEX/JAR 路径)", "path", "")
            }
            val input = File(inputPath)
            if (!input.isFile) {
                return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)
            }
            val appCache = ctx.context.cacheDir
            val action = args.str("action", "save").ifBlank { "save" }

            return runCatching {
                when (action) {
                    "list" -> {
                        val limit = args.intValue("limit", 500).coerceIn(1, 20000)
                        val outDir = File(appCache, "jadx-scan").apply { mkdirs() }
                        JadxDecompiler(buildArgs(input, outDir, appCache)).use { jadx ->
                            jadx.load()
                            val names = JSONArray()
                            var count = 0
                            for (cls in jadx.classes) {
                                if (count >= limit) break
                                names.put(cls.fullName)
                                count++
                            }
                            ok(JSONObject()
                                .put("action", "list")
                                .put("totalClasses", jadx.classes.size)
                                .put("returned", count)
                                .put("classes", names))
                        }
                    }

                    "class" -> {
                        val className = args.str("className")
                        if (className.isBlank()) {
                            return@runCatching err("INVALID_ARGUMENT", "action=class 需要 className", "className", "")
                        }
                        val outDir = File(appCache, "jadx-scan").apply { mkdirs() }
                        JadxDecompiler(buildArgs(input, outDir, appCache)).use { jadx ->
                            jadx.load()
                            val cls = jadx.classes.firstOrNull { it.fullName == className || it.name == className }
                                ?: return@use err("CLASS_NOT_FOUND", "未找到类: $className", "className", className)
                            ok(JSONObject()
                                .put("action", "class")
                                .put("className", cls.fullName)
                                .put("code", cls.code))
                        }
                    }

                    else -> { // save
                        val baseName = input.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        val outDir = File(ctx.context.filesDir, "jadx-out/$baseName").apply {
                            if (exists()) deleteRecursively()
                            mkdirs()
                        }
                        JadxDecompiler(buildArgs(input, outDir, appCache)).use { jadx ->
                            jadx.load()
                            val total = jadx.classes.size
                            jadx.save()
                            ok(JSONObject()
                                .put("action", "save")
                                .put("outputDir", outDir.absolutePath)
                                .put("totalClasses", total)
                                .put("hint", "反编译源码已导出到 outputDir,可用文件工具读取具体 .java 文件"))
                        }
                    }
                }
            }.getOrElse { e ->
                err("JADX_FAILED", "jadx 反编译失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }
}
