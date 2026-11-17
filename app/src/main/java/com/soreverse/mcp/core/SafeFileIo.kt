package com.soreverse.mcp.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** 数据流超过上限且策略为 ABORT 时抛出。 */
class PayloadTooLargeException(
    val maxBytes: Long,
    val observedBytes: Long,
) : IOException("数据流超过上限（允许 ${maxBytes / 1024} KiB，已读到 ${observedBytes / 1024} KiB）")

/**
 * 有界流复制 + 不跟随符号链接的目录操作（源自太墟 `BoundedStreamCopy` / `SafeFileTree`）。
 *
 * 解决两类真实故障：
 * 1. **无界复制**：备份导出、解压、导入都是 `input.copyTo(output)`，一个 2 GiB 的条目
 *    直接把堆打爆（Android 上就是整进程被杀，用户只看到闪退）；
 * 2. **符号链接逃逸**：`File.isDirectory` / `deleteRecursively` 都会**跟随**符号链接。
 *    备份目录里只要有一个指向 `/sdcard` 或 `/data` 的链接，备份就会把外部文件一起打包；
 *    恢复失败回滚时 `deleteRecursively` 甚至会顺着链接删到应用目录之外。
 *
 * 纯 Kotlin + java.nio，无 Android 依赖，可单测。
 */
object SafeFileIo {

    const val DEFAULT_BUFFER_BYTES = 16 * 1024

    /** 通用复制上限：512 MiB（APK / 大 .so 够用，又能挡住失控流）。 */
    const val DEFAULT_MAX_COPY_BYTES = 512L * 1024 * 1024

    enum class OverflowPolicy {
        /** 超限立即中断并抛 [PayloadTooLargeException]（已写入的目标文件由调用方清理）。 */
        ABORT,

        /** 超限平滑停止，返回 truncated = true，不抛异常。 */
        TRUNCATE,
    }

    data class CopyResult(
        val bytesCopied: Long,
        val truncated: Boolean,
        val reachedEof: Boolean,
    )

    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = DEFAULT_MAX_COPY_BYTES,
        bufferSize: Int = DEFAULT_BUFFER_BYTES,
        policy: OverflowPolicy = OverflowPolicy.ABORT,
        onProgress: ((Long) -> Unit)? = null,
    ): CopyResult {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        require(bufferSize > 0) { "bufferSize must be > 0" }
        val buffer = ByteArray(bufferSize)
        var written = 0L
        while (true) {
            val remaining = maxBytes - written
            if (remaining <= 0) {
                // 恰好到上限：探测一下是不是正好 EOF（否则就是被截断）
                if (input.read() == -1) {
                    output.flush()
                    return CopyResult(written, false, true)
                }
                if (policy == OverflowPolicy.ABORT) {
                    throw PayloadTooLargeException(maxBytes, written + 1)
                }
                output.flush()
                return CopyResult(written, true, false)
            }
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read == -1) {
                output.flush()
                return CopyResult(written, false, true)
            }
            if (read == 0) continue
            output.write(buffer, 0, read)
            written += read
            onProgress?.invoke(written)
        }
    }

    fun copyFile(
        source: File,
        target: File,
        maxBytes: Long = DEFAULT_MAX_COPY_BYTES,
        policy: OverflowPolicy = OverflowPolicy.ABORT,
    ): CopyResult {
        target.parentFile?.mkdirs()
        return source.inputStream().use { input ->
            target.outputStream().use { output ->
                copy(input, output, maxBytes, policy = policy)
            }
        }
    }

    /** 是否为符号链接本身（不是它指向的东西）。 */
    fun isSymbolicLink(file: File): Boolean = runCatching {
        Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    /**
     * 列出目录下**真实**的子项，符号链接一律排除。
     * 目录可能因为权限返回 null —— 返回空列表，调用方按「没有内容」处理。
     */
    fun childrenNoFollow(dir: File): List<File> =
        dir.listFiles()?.filterNot { isSymbolicLink(it) }.orEmpty()

    /**
     * 不跟随符号链接地递归删除。
     * @return 删除失败的条目数（0 表示全部删掉或本来就不存在）
     */
    fun deleteTreeNoFollow(root: File): Int {
        if (!root.exists() && !isSymbolicLink(root)) return 0
        // 顺序很重要：符号链接本身的 isDirectory 会跟随，必须先判链接。
        if (isSymbolicLink(root) || !root.isDirectory) {
            return if (root.delete()) 0 else 1
        }
        var failures = 0
        root.listFiles()?.forEach { child ->
            failures += deleteTreeNoFollow(child)
        }
        if (!root.delete()) failures++
        return failures
    }

    /**
     * 不跟随符号链接地复制目录树。符号链接**跳过不复制**（记入 [skipped]），
     * 因为「把指向任意路径的链接原样恢复到应用目录」本身就是风险。
     *
     * @return (复制的文件数, 跳过的条目数)
     */
    fun copyTreeNoFollow(
        source: File,
        target: File,
        skipped: MutableList<String> = mutableListOf(),
    ): Pair<Int, Int> {
        var copied = 0
        var skip = 0
        fun walk(src: File, dst: File) {
            if (isSymbolicLink(src)) {
                skipped += src.absolutePath
                skip++
                return
            }
            if (src.isDirectory) {
                dst.mkdirs()
                childrenNoFollow(src).forEach { child ->
                    walk(child, File(dst, child.name))
                }
                return
            }
            if (!src.isFile) {
                skipped += src.absolutePath
                skip++
                return
            }
            runCatching {
                copyFile(src, dst)
                copied++
            }.onFailure {
                skipped += "${src.absolutePath} (${it.javaClass.simpleName})"
                skip++
            }
        }
        walk(source, target)
        return copied to skip
    }

    /** 目录树里是否存在符号链接（用于备份前提示）。 */
    fun containsSymbolicLink(root: File, maxDepth: Int = 6): Boolean {
        fun walk(dir: File, depth: Int): Boolean {
            if (depth > maxDepth) return false
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return false
            for (c in children) {
                if (isSymbolicLink(c)) return true
                if (!isSymbolicLink(c) && c.isDirectory && walk(c, depth + 1)) return true
            }
            return false
        }
        return isSymbolicLink(root) || (root.isDirectory && walk(root, 0))
    }
}
