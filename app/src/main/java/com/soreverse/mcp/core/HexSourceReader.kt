package com.soreverse.mcp.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 按窗口随机读取文件内容，供地址查看器（hexdump）使用。
 *
 * 上一版是「先看大小、再决定读不读」：普通文件 128MiB 以内整份读进内存，`content://`
 * 则流式读满整个 URI。问题是地址查看器本身只显示 `len` 字节（默认 512、上限 65536），
 * 整份读意味着一次翻页就要为整个文件付内存与 IO —— 200MB 的 SO 翻 10 页就读 2GB，
 * 512MiB 的 APK 直接 OOM。所谓「128MiB 上限」只是把崩溃点往后推了一点，并不解决问题。
 *
 * 现在改成两件事：
 * ① 普通文件用 [RandomAccessFile] seek 到窗口起点，只读 [offset, offset+length)；
 * ② `content://` 先用 `openFileDescriptor` 走真正的随机访问（多数 provider 支持），
 *    不支持随机访问的（管道、云盘流）再落地一份本地副本按文件读 —— 副本进 cacheDir
 *    并做有界回收，不会随翻页无限增长。
 *
 * 只依赖 Android 基础 API，不 import engine 包（core 是第 0 层，不能反向依赖）。
 */
internal object HexSourceReader {

    /** 单次窗口上限：够一整屏 dump，又不会因为误操作一次读走半个 GB。 */
    const val MAX_WINDOW: Int = 4 * 1024 * 1024

    /** `content://` 落地副本的上限：超过直接拒绝，避免把用户存储塞满。 */
    private const val MAX_URI_COPY: Long = 3L * 1024 * 1024 * 1024

    /** 按 hash 复用的本地副本最多留几份（切回上一个文件时不用重新复制）。 */
    private const val MAX_COPY_ENTRIES = 3

    /**
     * 一个窗口的读取结果。
     *
     * 失败时 [data] 为 null、[error] 是可直接展示的原因；`offset` 超出文件末尾
     * 不是错误，返回空数组，调用方照常显示「已到文件末尾」。
     */
    data class Window(
        val data: ByteArray?,
        val offset: Long,
        val totalSize: Long,
        val error: String,
    ) {
        val ok: Boolean get() = data != null
    }

    fun readWindow(context: Context, path: String, offset: Long, length: Int): Window {
        val p = path.trim()
        if (p.isEmpty()) return Window(null, 0, 0, "path is empty")
        val from = if (offset < 0) 0L else offset
        val want = length.coerceIn(1, MAX_WINDOW)
        return if (p.startsWith("content://")) {
            readUriWindow(context, p, from, want)
        } else {
            readFileWindow(File(p), from, want)
        }
    }

    private fun readFileWindow(file: File, from: Long, want: Int): Window {
        if (!file.isFile) return Window(null, from, 0, "file not found: ${file.path}")
        val total = runCatching { file.length() }.getOrDefault(-1L)
        if (total < 0) return Window(null, from, 0, "cannot stat: ${file.path}")
        if (from >= total) return Window(ByteArray(0), total, total, "")
        val n = minOf(want.toLong(), total - from).toInt()
        val raf = runCatching { RandomAccessFile(file, "r") }.getOrNull()
            ?: return Window(null, from, total, "cannot open: ${file.path}")
        return try {
            raf.seek(from)
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = raf.read(buf, read, n - read)
                if (r <= 0) break
                read += r
            }
            Window(if (read == n) buf else buf.copyOf(read), from, total, "")
        } catch (e: Throwable) {
            // RandomAccessFile 在部分虚拟文件系统上会抛 Error（不是 Exception），一并接住。
            Window(null, from, total, "read failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            runCatching { raf.close() }
        }
    }

    private fun readUriWindow(context: Context, uriText: String, from: Long, want: Int): Window {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull()
            ?: return Window(null, from, 0, "invalid URI: $uriText")
        // 能随机访问就先随机访问：省掉为看一眼 512 字节而复制整个文件。
        readViaDescriptor(context, uri, from, want)?.let { return it }
        val copy = copyToCache(context, uri)
            ?: return Window(null, from, 0, "cannot read URI: $uriText")
        return readFileWindow(copy, from, want)
    }

    /**
     * 经 `ParcelFileDescriptor` + `FileChannel.position` 随机读。
     *
     * 返回 null 表示「这个 provider 不支持随机访问」，交给复制路径；返回 Window 表示
     * 已经得出结论（成功或可展示的失败）。
     */
    private fun readViaDescriptor(context: Context, uri: Uri, from: Long, want: Int): Window? {
        val pfd: ParcelFileDescriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull() ?: return null
        var fis: FileInputStream? = null
        return try {
            fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            val total = runCatching { channel.size() }.getOrDefault(-1L)
            if (total <= 0) return null
            if (from >= total) return Window(ByteArray(0), total, total, "")
            val n = minOf(want.toLong(), total - from).toInt()
            channel.position(from)
            val buf = ByteArray(n)
            val bb = ByteBuffer.wrap(buf)
            var read = 0
            while (bb.hasRemaining()) {
                val r = channel.read(bb)
                if (r <= 0) break
                read += r
            }
            Window(if (read == n) buf else buf.copyOf(read), from, total, "")
        } catch (e: Throwable) {
            // seek 失败 / provider 不支持 → 回落复制路径，不把它当成读取失败。
            null
        } finally {
            // 先关流再关 pfd：只关 pfd 会让 FileInputStream 变成泄漏对象（StrictMode 报资源未关闭）。
            runCatching { fis?.close() }
            runCatching { pfd.close() }
        }
    }

    /**
     * 把 `content://` 落地成 cacheDir 下的本地副本（按 URI hash 命名，复用已有副本）。
     *
     * 视频/云盘类 provider 的流不支持 seek，翻页时反复整份读会把时间全花在网络上，
     * 所以落地一次之后复用；目录按「最多 3 份、总大小有上限、最旧先删」回收。
     */
    private fun copyToCache(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, "hexview").apply { runCatching { mkdirs() } }
        val key = Integer.toHexString(uri.toString().hashCode())
        val target = File(dir, "src_$key.bin")
        if (target.isFile && target.length() > 0) {
            runCatching { target.setLastModified(System.currentTimeMillis()) }
            return target
        }
        val tmp = File(dir, "src_$key.tmp")
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        var total = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    total += r
                    if (total > MAX_URI_COPY) {
                        runCatching { tmp.delete() }
                        return null
                    }
                    out.write(buf, 0, r)
                }
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            return null
        } finally {
            runCatching { input.close() }
        }
        if (!runCatching { tmp.renameTo(target) }.getOrDefault(false)) {
            runCatching { tmp.delete() }
            return null
        }
        prune(dir, target)
        return target
    }

    private fun prune(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it.name.startsWith("src_") && it.isFile } ?: return
        val ordered = files.sortedBy { it.lastModified() }
        var excess = ordered.size - MAX_COPY_ENTRIES
        for (f in ordered) {
            if (f == keep) continue
            if (excess <= 0) break
            if (runCatching { f.delete() }.getOrDefault(false)) excess--
        }
    }
}
