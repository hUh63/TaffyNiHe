package com.soreverse.mcp.mcp

import com.reandroid.apk.ApkModule
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.intValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: 内置静态逆向工具(纯 Java 依赖, 无需外部进程)。
 *  - baksmali_decode: DEX → smali 反汇编 (com.android.tools.smali)
 *  - apk_decode:      APK 资源/清单解析 (ARSCLib, aapt 无关)
 */
object StaticTools {

    /** DEX → smali 反汇编。 */
    val baksmali: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "baksmali_decode",
            "【DEX→smali 反汇编】用 baksmali 把 dex 反汇编成 smali 源码,导出到目录。适合看/改字节码逻辑。",
            "Disassemble a DEX file to smali source with baksmali, exported to a directory.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "DEX 文件绝对路径(classes.dex 等)"
                "filePath" str "path 的别名"
                "apiLevel" int "smali api level(默认 0=自动)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(DEX 路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            return runCatching {
                val api = args.intValue("apiLevel", 0)
                val opcodes = if (api > 0) Opcodes.forApi(api) else Opcodes.getDefault()
                val dexFile = DexFileFactory.loadDexFile(input, opcodes)
                val baseName = input.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val outDir = File(ctx.context.filesDir, "smali-out/$baseName").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                val options = BaksmaliOptions()
                val jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                val okDone = Baksmali.disassembleDexFile(dexFile, outDir, jobs, options)
                val smaliCount = outDir.walkTopDown().count { it.isFile && it.extension == "smali" }
                ok(JSONObject()
                    .put("tool", "baksmali_decode")
                    .put("success", okDone)
                    .put("outputDir", outDir.absolutePath)
                    .put("smaliFiles", smaliCount)
                    .put("hint", "smali 源码已导出到 outputDir,可用文件工具读取 .smali 文件"))
            }.getOrElse { e ->
                err("BAKSMALI_FAILED", "baksmali 反汇编失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }

    /** APK 资源/清单解析(ARSCLib)。 */
    val apkDecode: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "apk_decode",
            "【APK 清单/资源解析】用 ARSCLib 读取 APK 的包名、版本、权限、Manifest 与资源文件清单(不依赖 aapt)。action=manifest 看清单概览; action=resources 列资源文件; action=xml 解码指定 XML(如 AndroidManifest.xml)。",
            "Parse APK manifest/resources with ARSCLib (no aapt). action=manifest for package/version/permissions overview; action=resources to list resource files; action=xml to decode a specific binary XML.",
            "workspace", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("manifest (default) | resources | xml", "manifest", "resources", "xml")
                "path" str "APK 文件绝对路径"
                "filePath" str "path 的别名"
                "xmlPath" str "要解码的 XML 条目路径(action=xml),如 AndroidManifest.xml"
                "limit" int "资源文件数量上限(action=resources, 默认 300)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(APK 路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            return runCatching {
                val apk = ApkModule.loadApkFile(input)
                when (args.str("action", "manifest").ifBlank { "manifest" }) {
                    "resources" -> {
                        val limit = args.intValue("limit", 300).coerceIn(1, 20000)
                        val files = apk.listResFiles()
                        val arr = JSONArray()
                        files.take(limit).forEach { arr.put(it.filePath) }
                        ok(JSONObject()
                            .put("tool", "apk_decode")
                            .put("action", "resources")
                            .put("totalResFiles", files.size)
                            .put("returned", minOf(limit, files.size))
                            .put("resFiles", arr))
                    }

                    "xml" -> {
                        val xmlPath = args.str("xmlPath").ifBlank { "AndroidManifest.xml" }
                        val doc = apk.decodeXMLFile(xmlPath)
                        ok(JSONObject()
                            .put("tool", "apk_decode")
                            .put("action", "xml")
                            .put("xmlPath", xmlPath)
                            .put("xml", doc.toText(true, false)))
                    }

                    else -> { // manifest
                        val manifest = apk.androidManifest
                        val perms = JSONArray()
                        runCatching { manifest.usesPermissions.forEach { perms.put(it) } }
                        ok(JSONObject()
                            .put("tool", "apk_decode")
                            .put("action", "manifest")
                            .put("packageName", runCatching { manifest.packageName }.getOrNull() ?: "")
                            .put("versionName", runCatching { manifest.versionName }.getOrNull() ?: "")
                            .put("versionCode", runCatching { manifest.versionCode }.getOrNull() ?: 0)
                            .put("mainActivity", runCatching { manifest.mainActivityClassName }.getOrNull() ?: "")
                            .put("usesPermissions", perms))
                    }
                }
            }.getOrElse { e ->
                err("APK_DECODE_FAILED", "APK 解析失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }
}
