package com.soreverse.mcp.mcp

import com.reandroid.apkeditor.compile.BuildOptions
import com.reandroid.apkeditor.decompile.DecompileOptions
import com.reandroid.apkeditor.merge.MergerOptions
import com.reandroid.apkeditor.refactor.RefactorOptions
import com.soreverse.mcp.core.ApkSigningPolicy
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: APKEditor 完整 APK 反编译/回编/合并/去混淆(纯 Java, aapt 无关, 基于 ARSCLib)。
 * 补齐 MT 管理器"改完完整回编成 APK"的最后一环 —— taffy_smali_assemble 只出 dex, 这个能出完整 APK。
 *
 *  action:
 *   - decode:   APK → 可读目录(资源 json/xml + smali dex)。可编辑后再 build 回去。
 *   - build:    decode 出的目录 → 回编成完整 APK。
 *   - merge:    多个拆分包(xapk/apks/apkm/目录) → 合并成单个可安装 APK。
 *   - refactor: 去混淆重构(还原被混淆的资源名)。
 *
 * 完整链路: taffy_apk_rebuild(decode) → 改资源/smali → taffy_apk_rebuild(build) → taffy_apk_sign(签名) → 安装。
 * split 应用直接: taffy_apk_rebuild(merge) → taffy_apk_sign。
 */
object ApkEditorTool {

    val rebuild: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_rebuild",
            "【APK 完整回编/合并/去混淆】APKEditor 引擎(纯 Java,aapt 无关)。action=decode 把 APK 拆成可读可改的目录(资源+dex/smali); action=build 把改好的目录回编成完整 APK; action=merge 把拆分包(xapk/apks/apkm)合并成单个可安装 APK; action=refactor 还原被混淆的资源名。回编后记得用 taffy_apk_sign 签名再装。",
            "Full APK decode/build/merge/refactor via APKEditor (pure Java, aapt-independent). action=decode splits an APK into an editable dir (resources + dex/smali); action=build recompiles that dir back into a full APK; action=merge combines split bundles (xapk/apks/apkm) into a single installable APK; action=refactor restores obfuscated resource names. Sign the output with taffy_apk_sign before installing.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "decode(需path: APK) | build(需path: decode目录) | merge(需path: 拆分包) | refactor(需path: APK) | decode(needs path=APK) | build(needs path=decoded dir) | merge(needs path=split bundle) | refactor(needs path=APK)",
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
                        // 设置页「修改APK后自动签名」开启时，回编后直接按签名策略签名
                        if (out.isFile && SettingsStore(ctx.context).apkAutoSign) {
                            val signed = signApk(ctx, out, null)
                            if (signed != null) {
                                return@runCatching result("build", out, "已回编并自动签名，可直接安装。签名输出: $signed",
                                    JSONObject().put("signedApk", signed).put("autoSign", true))
                            }
                            return@runCatching result("build", out, "已回编，但自动签名失败，请用 taffy_apk_sign 手动签名。",
                                JSONObject().put("signed", false))
                        }
                        result("build", out, "已回编成完整 APK。必须用 taffy_apk_sign 签名后才能安装。", null)
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
                        result("merge", out, "已把拆分包合并成单个 APK。用 taffy_apk_sign 签名后可安装。", null)
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

        /** 按签名策略（密钥来源/方案/V1 文件名）签名，成功返回签名后路径，失败 null。 */
        private fun signApk(ctx: ToolContext, apk: File, outPath: String?): String? {
            return try {
                val signer = ApkSigningPolicy.resolveSigner(ctx.context) ?: return null
                val dest = if (outPath != null) File(outPath)
                    else File(apk.parentFile, "${apk.nameWithoutExtension}-signed.apk")
                val (v1, v2, v3) = ApkSigningPolicy.schemeFlags(ctx.context)
                val v1Name = ApkSigningPolicy.v1SignerName(ctx.context)
                val cfgBuilder = com.android.apksig.ApkSigner.SignerConfig.Builder(v1Name, signer.first, listOf(signer.second))
                runCatching {
                    cfgBuilder.javaClass.getMethod("setV1SignerName", String::class.java).invoke(cfgBuilder, v1Name)
                }
                com.android.apksig.ApkSigner.Builder(listOf(cfgBuilder.build()))
                    .setInputApk(apk)
                    .setOutputApk(dest)
                    .setV1SigningEnabled(v1)
                    .setV2SigningEnabled(v2)
                    .setV3SigningEnabled(v3)
                    .build()
                    .sign()
                dest.absolutePath
            } catch (e: Exception) { null }
        }

        private fun result(action: String, out: File, hint: String, extra: JSONObject?): JSONObject {
            val body = JSONObject()
                .put("tool", "taffy_apk_rebuild")
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
