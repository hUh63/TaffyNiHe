package com.soreverse.mcp.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 按「先看大小、再决定读不读」的顺序取文件内容。
 *
 * 起因：地址查看器原来是 `readBytes()` 之后才判断「超过 128MiB 就报错」——那段判断永远轮不到执行，
 * 因为 2GB 的 SO/APK 在读取阶段就已经 OOM，用户看到的是崩溃而不是错误提示。
 *
 * 这里把三件事收在一处：普通文件先 `length()` 预检；`content://` 走流式受限读取；
 * 超限返回结构化结果让调用方原样展示，而不是抛异常或返回一个分不清原因的 null。
 */
internal object HexSourceReader {

    /** hexdump 视图愿意接受的最大字节数。 */
    const val MAX_BYTES: Long = 128L * 1024 * 1024

    data class Result(val data: ByteArray?, val tooLarge: Boolean, val error: String) {
        val ok: Boolean get() = data != null
    }

    fun read(context: Context, path: String): Result {
        val p = path.trim()
        if (p.isEmpty()) return Result(null, false, "path is empty")
        if (p.startsWith("content://")) return readUri(context, p)
        val file = File(p)
        if (!file.isFile) return Result(null, false, "file not found: $p")
        val length = runCatching { file.length() }.getOrDefault(-1L)
        if (length > MAX_BYTES) {
            return Result(null, true, "file too large for hex view: " + length + " bytes (max 128MiB)")
        }
        val data = runCatching { file.readBytes() }.getOrNull()
            ?: return Result(null, false, "failed to read: $p")
        return Result(data, false, "")
    }

    private fun readUri(context: Context, uriText: String): Result {
        val input: InputStream? = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriText))
        }.getOrNull()
        if (input == null) return Result(null, false, "cannot open URI: $uriText")
        val bytes = runCatching { input.use { readAtMost(it, MAX_BYTES) } }.getOrNull()
        return when {
            bytes == null -> Result(null, true, "URI larger than 128MiB or unreadable: $uriText")
            else -> Result(bytes, false, "")
        }
    }

    /** 读最多 [max] 字节；超过就放弃并返回 null（不把超限内容留在内存里）。 */
    private fun readAtMost(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
