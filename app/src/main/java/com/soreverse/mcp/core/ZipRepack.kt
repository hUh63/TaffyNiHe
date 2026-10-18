package com.soreverse.mcp.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK/ZIP 逐条重打包。
 *
 * 背景：多个工具（`taffy_apk_patch_bytes`、`taffy_smali_edit` 的 dex 写回）原来都用
 * `ZipEntry(e.name)` 重建条目——默认 DEFLATED，且丢弃原条目的 extra 字段与时间戳。
 * 这对 APK 是致命的：
 *  - `resources.arsc` 必须 STORED 且 4 字节对齐；
 *  - `extractNativeLibs=false` 时 `lib/` 下的 .so 必须 STORED 且页对齐。
 * 改一个字节就把它们重新压缩，安装器直接拒装（INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION）
 * 或原生库加载失败，而工具还返回「字节已写入」。
 *
 * 这里保留原压缩方式与 extra，并对上述两类条目强制 STORED + 4 字节对齐。
 */
internal object ZipRepack {

    /** 需要保证未压缩且 4 字节对齐的条目。 */
    private fun requiresStore(name: String): Boolean =
        name == "resources.arsc" || isNativeLib(name)

    /** native 库：未压缩时必须 4096 页对齐。 */
    private fun isNativeLib(name: String): Boolean =
        name.startsWith("lib/") && name.endsWith(".so")

    /**
     * ZIP 对齐填充块（zipalign 使用的 0xd935 extra），总长 [total] 必须 >= 6。
     * 直接往 extra 尾部追加裸 0 字节会让 extra 结构里出现残缺的 (id,size) 头。
     */
    private fun alignmentExtra(total: Int): ByteArray {
        val out = ByteArray(total)
        out[0] = 0x35
        out[1] = 0xd9.toByte()
        val size = total - 4
        out[2] = (size and 0xFF).toByte()
        out[3] = ((size shr 8) and 0xFF).toByte()
        return out
    }

    /**
     * 用 [src] 原子替换 [dst]（同目录 rename）。旧实现用 copyTo(overwrite=true)，
     * 写回中途被杀/磁盘满会把用户的原 APK 变成半截文件。
     */
    fun replaceAtomically(src: File, dst: File) {
        runCatching {
            java.nio.file.Files.move(
                src.toPath(), dst.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            src.copyTo(dst, overwrite = true)
            runCatching { src.delete() }
        }
    }

    /**
     * 逐条复制 [input] 到 [output]，[replace] 返回非 null 时用其替换该条目的内容。
     * 调用方负责保证 [output] 与 [input] 不是同一个文件。
     */
    fun copyAll(input: File, output: File, replace: (String) -> ByteArray?): Set<String> {
        // 返回真正被替换的条目名：调用方据此判断"修改是否落到产物上"。
        // 旧实现无返回值，entryName 与 zip 条目名一旦不一致就整包原样复制，
        // 工具仍返回 success=true + 新字节数（报告与产物不一致）。
        val applied = HashSet<String>()
        SavedOffsetOutput(BufferedOutputStream(output.outputStream())).use { counting ->
            ZipOutputStream(counting).use { zos ->
                ZipFile(input).use { zf ->
                    for (e in zf.entries()) {
                        val override = replace(e.name)
                        if (override != null) applied.add(e.name)
                        val store = e.method == ZipEntry.STORED || requiresStore(e.name)
                        val entry = ZipEntry(e.name).apply {
                            time = e.time
                            comment = e.comment
                        }
                        if (store) {
                            entry.method = ZipEntry.STORED
                            // 原本就是 STORED 且无需替换：size/crc 已存在，直接流式复制。
                            // 旧实现无条件 readBytes()，重打包多 ABI 大 APK 时把几十 MB 灌进堆（OOM 风险）。
                            val streaming = override == null && e.method == ZipEntry.STORED &&
                                e.size >= 0 && e.crc >= 0
                            val data: ByteArray? = if (streaming) {
                                null
                            } else {
                                override ?: zf.getInputStream(e).use { it.readBytes() }
                            }
                            if (streaming) {
                                entry.size = e.size
                                entry.compressedSize = e.size
                                entry.crc = e.crc
                            } else {
                                val d = data ?: ByteArray(0)
                                entry.size = d.size.toLong()
                                entry.compressedSize = d.size.toLong()
                                entry.crc = CRC32().apply { update(d) }.value
                            }
                            var extra = e.extra ?: ByteArray(0)
                            // resources.arsc 需 4 字节对齐；lib/ 下的 .so 需 4096 页对齐
                            // （zipalign -p 语义）——旧实现两者都只对齐到 4，未压缩的 so 会失去
                            // 页对齐，安装器改为解压存放或 mmap 失败。
                            val align = when {
                                isNativeLib(e.name) -> 4096
                                requiresStore(e.name) -> 4
                                else -> 0
                            }
                            if (align > 0) {
                                zos.flush()
                                val nameLen = e.name.toByteArray(Charsets.UTF_8).size
                                val base = counting.count + 30 + nameLen + extra.size
                                var pad = ((align - (base % align).toInt()) % align)
                                // 填充必须是合法的 alignment extra 块（id 0xd935），裸追加
                                // 字节会让严格解析器判定 extra 结构损坏
                                if (pad in 1..5) pad += align
                                if (pad > 0) extra += alignmentExtra(pad)
                            }
                            entry.extra = extra
                            zos.putNextEntry(entry)
                            if (data != null) zos.write(data) else zf.getInputStream(e).use { it.copyTo(zos) }
                        } else {
                            entry.method = ZipEntry.DEFLATED
                            if (e.extra != null) entry.extra = e.extra
                            zos.putNextEntry(entry)
                            if (override != null) {
                                zos.write(override)
                            } else {
                                zf.getInputStream(e).use { it.copyTo(zos) }
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
        return applied
    }

    private class SavedOffsetOutput(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
