package com.soreverse.mcp.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 有界读取工具：把「最多读多少」变成调用方决定的硬上限，而不是依赖 `length()`。
 *
 * 起因是编辑器的 8 MiB 闸门：它用 `openAssetFileDescriptor(...).length` 预检，
 * 但 SAF / 云盘 / 部分 `content://` provider 经常返回 -1（未知长度），闸门就静默失效，
 * 紧接着 `readBytes()` 把整份内容读进内存 —— 选一个 1 GB 的日志文件就是 OOM。
 *
 * 这里改成「先按上限读，超了就放弃」，与 length 是否可信无关；超限返回 null，
 * 调用方可以给「超过上限」的提示，而不是让进程崩掉。
 */
internal object StreamLimits {

    /** 读到的最大字节数（默认 8 MiB，与编辑器上限一致）。 */
    const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024

    /**
     * 读最多 [maxBytes] 字节；超过上限返回 null（超限内容不留在内存里）。
     *
     * 用 64 KiB 缓冲逐块读：既不假设 length 可信，也不会因为一次 read 返回部分数据就截断。
     */
    fun readBytes(input: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray? {
        if (maxBytes <= 0) return null
        // 初始容量别按 maxBytes 分配：8 MiB 的预分配对「几百字节的小文件」是纯浪费。
        val initial = minOf(maxBytes, 256L * 1024).toInt()
        val out = ByteArrayOutputStream(initial)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            // 读异常必须与 EOF 区分：吞成 -1 会把「流中途断开」当成读完，
            // 返回半截内容 —— 调用方（编辑器）可能拿它覆盖原文件。
            val n = try {
                input.read(buf)
            } catch (e: java.io.IOException) {
                return null
            }
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 同 [readBytes]，按 UTF-8 解码成文本。 */
    fun readText(input: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): String? =
        readBytes(input, maxBytes)?.toString(Charsets.UTF_8)
}
