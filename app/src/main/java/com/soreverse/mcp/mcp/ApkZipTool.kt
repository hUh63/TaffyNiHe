package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 塔菲逆核: APK zip 级条目替换。
 *
 * 不解包整个 APK，直接流式重写 zip 并替换单个条目（典型用途：把 patch 后的
 * lib/<abi>/ 下的 .so 回填进 APK）。旧 META-INF 签名（v1）会被丢弃 —— 因为 APK 内容
 * 变了，必须重新签名；v2/v3 签名块不在 zip 条目里，重写时天然被丢弃。
 *
 * 典型流程：
 *   taffy_so_inject_code(注入, 写编辑会话) → 落盘 patched .so
 *   → taffy_apk_replace_native(替换进 APK) → taffy_apk_sign(v1/v2/v3 重签) → 可安装
 */
object ApkZipTool {

    data class ReplaceResult(val replaced: Boolean, val added: Boolean, val entryCount: Int, val newBytes: Int)

    /** 纯逻辑：流式重写 zip，替换 [entry] 为 [newBytes]，丢弃旧签名条目。可单测。 */
    fun replaceEntry(
        inputApk: File,
        outputApk: File,
        entry: String,
        newBytes: ByteArray,
        addIfMissing: Boolean,
    ): ReplaceResult {
        require(inputApk.isFile) { "input apk not found: ${inputApk.path}" }
        var replaced = false
        var added = false
        var count = 0
        ZipFile(inputApk).use { zip ->
            FileOutputStream(outputApk).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for (e in zip.entries()) {
                        val name = e.name
                        if (isSignatureEntry(name)) continue
                        count++
                        if (name == entry) {
                            write(zos, name, e.time, newBytes)
                            replaced = true
                        } else {
                            zos.putNextEntry(entryOf(name, e.time))
                            zip.getInputStream(e).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    if (!replaced && addIfMissing) {
                        write(zos, entry, -1L, newBytes)
                        replaced = true
                        added = true
                        count++
                    }
                }
            }
        }
        return ReplaceResult(replaced, added, count, newBytes.size)
    }

    private fun entryOf(name: String, time: Long): ZipEntry {
        val oe = ZipEntry(name)
        if (time >= 0L) oe.time = time
        return oe
    }

    private fun write(zos: ZipOutputStream, name: String, time: Long, bytes: ByteArray) {
        zos.putNextEntry(entryOf(name, time))
        zos.write(bytes)
        zos.closeEntry()
    }

    /** 旧 v1 签名文件（重写后必须删除，否则与新签名冲突）。 */
    private fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
            upper.endsWith(".EC") || upper.endsWith(".MF")
    }

    private val replaceNative: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_replace_native",
            "【APK 条目替换】zip 级替换 APK 里的单个条目（如 lib/<abi>/*.so），不解包整个 APK；自动丢弃旧 META-INF 签名(v1)，v2/v3 签名块也随重写失效 —— 因此替换后必须用 taffy_apk_sign 重新签名才能安装。",
            "Replace a single zip entry (e.g. lib/<abi>/*.so) inside an APK without unpacking it. Old v1 (META-INF) signatures are dropped and the v2/v3 block is invalidated, so re-sign with taffy_apk_sign afterwards.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "inputApk" str "原 APK 路径"
                "outputApk" str "输出 APK 路径（不能与 inputApk 相同）"
                "entry" str "要替换的条目名（如 lib/arm64-v8a/libfoo.so）"
                "source" str "新内容来源文件路径（如 build 出来的 patched .so）"
                "addIfMissing" str "true=条目不存在时新增（默认 false）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inPath = args.str("inputApk")
            val outPath = args.str("outputApk")
            val entry = args.str("entry")
            val srcPath = args.str("source")
            if (inPath.isBlank() || outPath.isBlank() || entry.isBlank() || srcPath.isBlank()) {
                return err("INVALID_ARGUMENT", "inputApk / outputApk / entry / source 均必填")
            }
            val inFile = File(inPath)
            if (!inFile.isFile) return err("FILE_NOT_FOUND", "原 APK 不存在: $inPath")
            // 用 canonicalPath 比较：absolutePath 会被 "./"、"../"、软链接绕过，
            // 一旦同文件，replaceEntry 会先截断输出流，把原 APK 清成 0 字节。
            val outFile = File(outPath)
            val sameFile = runCatching { outFile.canonicalPath == inFile.canonicalPath }.getOrDefault(false)
            if (sameFile) {
                return err("INVALID_ARGUMENT", "outputApk 不能与 inputApk 相同")
            }
            val srcFile = File(srcPath)
            if (!srcFile.isFile) return err("FILE_NOT_FOUND", "新内容来源不存在: $srcPath")
            val r = runCatching {
                replaceEntry(inFile, outFile, entry, srcFile.readBytes(), args.str("addIfMissing", "false").toBoolean())
            }.getOrElse { return err("REPLACE_FAILED", it.message ?: "替换失败") }
            if (!r.replaced) return err("ENTRY_NOT_FOUND", "APK 里没有条目 $entry（如需新增请传 addIfMissing=true）")
            return ok(JSONObject()
                .put("outputApk", outPath)
                .put("entry", entry)
                .put("replaced", r.replaced)
                .put("added", r.added)
                .put("entryCount", r.entryCount)
                .put("newBytes", r.newBytes)
                .put("signed", false)
                .put("hint", "旧签名已失效：请调用 taffy_apk_sign 重新签名(v1/v2/v3)后再安装"))
        }
    }

    val ALL = listOf(replaceNative)
}
