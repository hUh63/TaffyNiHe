package com.soreverse.mcp.mcp

import com.reandroid.apkeditor.compile.BuildOptions
import com.reandroid.apkeditor.decompile.DecompileOptions
import com.reandroid.apkeditor.merge.MergerOptions
import com.reandroid.apkeditor.refactor.RefactorOptions
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: APKEditor 完整 APK 反编译/回编/合并/去混淆(纯 Java, aapt 无关, 基于 ARSCLib)。
 * 补齐 MT 管理器"改完完整回编成 APK"的最后一环 —— smali_assemble 只出 dex, 这个能出完整 APK。
 *
 *  action:
 *   - decode:   APK → 可读目录(资源 json/xml + smali dex)。可编辑后再 build 回去。
 *   - build:    decode 出的目录 → 回编成完整 APK。
 *   - merge:    多个拆分包(xapk/apks/apkm/目录) → 合并成单个可安装 APK。
 *   - refactor: 去混淆重构(还原被混淆的资源名)。
 *
 * 完整链路: apk_rebuild(decode) → 改资源/smali → apk_rebuild(build) → apk_sign(签名) → 安装。
 * split 应用直接: apk_rebuild(merge) → apk_sign。
 */
object ApkEditorTool {

    val rebuild: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "apk_rebuild",
            "【APK 完整回编/合并/去混淆】APKEditor 引擎(纯 Java,aapt 无关)。action=decode 把 APK 拆成可读可改的目录(资源+dex/smali); action=build 把改好的目录回编成完整 APK; action=merge 把拆分包(xapk/apks/apkm)合并成单个可安装 APK; action=refactor 还原被混淆的资源名。回编后记得用 apk_sign 签名再装。",
            "Full APK decode/build/merge/refactor via APKEditor (pure Java, aapt-independent). action=decode splits an APK into an editable dir (resources + dex/smali); action=build recompiles that dir back into a full APK; action=merge combines split bundles (xapk/apks/apkm) into a single installable APK; action=refactor restores obfuscated resource names. Sign the output with apk_sign before installing.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "decode(APK→目录) | build(目录→APK) | merge(拆分包→单APK) | refactor(去混淆)",
                    "decode", "build", "merge", "refactor",
                )
                "path" str "输入路径:APK 文件(decode/merge/refactor)或 decode 出的目录(build)"
                "filePath" str "path 的别名"
                "output" str "输出路径(可选)。不填则自动放到 filesDir/apkeditor-out/ 下"
                "type".oneOf(
                    "decode/build 的资源格式:json(默认,可回编) | xml(仅未混淆 APK,只读) | raw",
                    "json", "xml", "raw",
                )
                "dex" bool "decode 时是否同时反编译 dex→smali(默认 false,只解资源;true 会连 smali 一起出,更慢)"
                "force" bool "输出已存在时是否覆盖(默认 true)"
                "cleanMeta" bool "merge/refactor 时清理 META-INF 旧签名(默认 true,方便重签)"
                "fixTypeNames" bool "refactor 时修正资源类型名(默认 false)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "decode")
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) {
                return err("INVALID_ARGUMENT", "缺少参数 path(输入 APK 或目录)", "path", "")
            }
            val input = File(inputPath)
            if (!input.exists()) {
                return err("FILE_NOT_FOUND", "输入不存在: $inputPath", "path", inputPath)
            }

            val outRoot = File(ctx.context.filesDir, "apkeditor-out").apply { mkdirs() }
            val baseName = input.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val force = args.bool("force", true)

            return runCatching {
                when (action) {
                    "decode" -> {
                        val out = resolveOutput(args, outRoot, "${baseName}_decode")
                        val opt = DecompileOptions().apply {
                            inputFile = input
                            outputFile = out
                            this.force = force
                            type = args.str("type", "json")
                            dex = args.bool("dex", false)
                        }
                        opt.newCommandExecutor().runCommand()
                        val fileCount = if (out.isDirectory) out.walkTopDown().count { it.isFile } else 0
                        result("decode", out, "已反编译到目录,可编辑资源/smali 后用 action=build 回编。",
                            JSONObject().put("files", fileCount).put("dexDecoded", opt.dex))
                    }

                    "build" -> {
                        if (!input.isDirectory) {
                            return@runCatching err("INVALID_ARGUMENT", "build 的 path 必须是 decode 出的目录", "path", inputPath)
                        }
                        val out = resolveOutput(args, outRoot, "${baseName}_rebuilt.apk")
                        val opt = BuildOptions().apply {
                            inputFile = input
                            outputFile = out
                            this.force = force
                            type = args.str("type", "json")
                        }
                        opt.newCommandExecutor().runCommand()
                        result("build", out, "已回编成完整 APK。必须用 apk_sign 签名后才能安装。", null)
                    }

                    "merge" -> {
                        val out = resolveOutput(args, outRoot, "${baseName}_merged.apk")
                        val opt = MergerOptions().apply {
                            inputFile = input
                            outputFile = out
                            this.force = force
                            cleanMeta = args.bool("cleanMeta", true)
                        }
                        opt.newCommandExecutor().runCommand()
                        result("merge", out, "已把拆分包合并成单个 APK。用 apk_sign 签名后可安装。", null)
                    }

                    "refactor" -> {
                        val out = resolveOutput(args, outRoot, "${baseName}_refactored.apk")
                        val opt = RefactorOptions().apply {
                            inputFile = input
                            outputFile = out
                            this.force = force
                            cleanMeta = args.bool("cleanMeta", true)
                            fixTypeNames = args.bool("fixTypeNames", false)
                        }
                        opt.newCommandExecutor().runCommand()
                        result("refactor", out, "已还原混淆的资源名。", null)
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e ->
                err("APKEDITOR_FAILED", "APKEditor $action 失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }

        private fun resolveOutput(args: JSONObject, outRoot: File, defaultName: String): File {
            val custom = args.str("output")
            val out = if (custom.isNotBlank()) File(custom) else File(outRoot, defaultName)
            if (out.exists()) {
                if (out.isDirectory) out.deleteRecursively() else out.delete()
            }
            out.parentFile?.mkdirs()
            return out
        }

        private fun result(action: String, out: File, hint: String, extra: JSONObject?): JSONObject {
            val body = JSONObject()
                .put("tool", "apk_rebuild")
                .put("action", action)
                .put("output", out.absolutePath)
                .put("outputKind", if (out.isDirectory) "directory" else "file")
                .put("sizeBytes", if (out.isFile) out.length() else 0L)
                .put("hint", hint)
            extra?.keys()?.forEach { body.put(it, extra.get(it)) }
            return ok(body)
        }
    }
}
