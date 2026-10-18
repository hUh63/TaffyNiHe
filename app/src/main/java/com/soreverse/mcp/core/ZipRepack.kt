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
 *  - `extractNativeLibs=false` 时 `lib/*.so` 必须 STORED 且页对齐。
 * 改一个字节就把它们重新压缩，安装器直接拒装（INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION）
 * 或原生库加载失败，而工具还返回「字节已写入」。
 *
 * 这里保留原压缩方式与 extra，并对上述两类条目强制 STORED + 4 字节对齐。
 */
internal object ZipRepack {

    /** 需要保证未压缩且 4 字节对齐的条目。 */
    private fun requiresStore(name: String): Boolean =
        name == "resources.arsc" || (name.startsWith("lib/") && name.endsWith(".so"))

    /**
     * 逐条复制 [input] 到 [output]，[replace] 返回非 null 时用其替换该条目的内容。
     * 调用方负责保证 [output] 与 [input] 不是同一个文件。
     */
    fun copyAll(input: File, output: File, replace: (String) -> ByteArray?) {
        SavedOffsetOutput(BufferedOutputStream(output.outputStream())).use { counting ->
            ZipOutputStream(counting).use { zos ->
                ZipFile(input).use { zf ->
                    for (e in zf.entries()) {
                        val override = replace(e.name)
                        val store = e.method == ZipEntry.STORED || requiresStore(e.name)
                        val entry = ZipEntry(e.name).apply {
                            time = e.time
                            comment = e.comment
                        }
                        if (store) {
                            // STORED 必须先知道 size/crc，因此需要整条内容
                            val data = override ?: zf.getInputStream(e).use { it.readBytes() }
                            val crc = CRC32().apply { update(data) }
                            entry.method = ZipEntry.STORED
                            entry.size = data.size.toLong()
                            entry.compressedSize = data.size.toLong()
                            entry.crc = crc.value
                            var extra = e.extra ?: ByteArray(0)
                            if (requiresStore(e.name)) {
                                // local header 起始偏移 + 30 + nameLen + extraLen 需为 4 的倍数
                                zos.flush()
                                val nameLen = e.name.toByteArray(Charsets.UTF_8).size
                                val pad = (4 - ((counting.count + 30 + nameLen + extra.size) % 4).toInt() % 4) % 4
                                if (pad > 0) extra += ByteArray(pad)
                            }
                            entry.extra = extra
                            zos.putNextEntry(entry)
                            zos.write(data)
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
