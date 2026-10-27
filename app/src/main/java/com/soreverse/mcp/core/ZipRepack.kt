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
 * 这里保留原压缩方式与 extra，并对上述两类条目强制 STORED + 4/4096 字节对齐。
 * 所有需要「重写整个 APK」的工具都应该走这里，不要再自己写一遍 ZipOutputStream 循环。
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

    /** 数据起始偏移要满足 [align]，返回需要追加的填充字节数（0 = 已对齐）。 */
    private fun paddingFor(count: Long, nameLen: Int, extraLen: Int, align: Int): Int {
        val base = count + 30 + nameLen + extraLen
        var pad = ((align - (base % align).toInt()) % align)
        // 填充块本身最少 6 字节（4 字节头 + 至少 2 字节体），1..5 会写出非法 extra
        if (pad in 1..5) pad += align
        return pad
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
     *
     * @param skip 命中则整条丢弃（典型用途：丢掉失效的旧 v1 签名条目，即 META-INF 下的
     *   .SF / .RSA / .DSA / .EC / .MF）。
     * @param append 需要新增的条目（原包里没有的）。新增的 native 库与 resources.arsc
     *   同样按 STORED + 对齐写，否则新加的库在 `extractNativeLibs=false` 的包里加载失败。
     * @param replace 必须放在**最后一个**参数：现有调用方全都在用 trailing lambda
     *   （`copyAll(a, b) { name -> ... }`），Kotlin 的尾随 lambda 只对应最后一个形参，
     *   中间夹默认参数会让 lambda 绑到 append 上。
     * @return 真正落到产物上的条目名（被替换的 + 被新增的）。
     */
    fun copyAll(
        input: File,
        output: File,
        skip: (String) -> Boolean = { false },
        append: List<Pair<String, ByteArray>> = emptyList(),
        appendFiles: List<Pair<String, File>> = emptyList(),
        level: Int = -1,
        replace: (String) -> ByteArray?,
    ): Set<String> {
        // 返回真正被替换的条目名：调用方据此判断"修改是否落到产物上"。
        // 旧实现无返回值，entryName 与 zip 条目名一旦不一致就整包原样复制，
        // 工具仍返回 success=true + 新字节数（报告与产物不一致）。
        val applied = HashSet<String>()
        val written = HashSet<String>()
        SavedOffsetOutput(BufferedOutputStream(output.outputStream())).use { counting ->
            ZipOutputStream(counting).use { zos ->
                // 调用方显式要求压缩级别时（archive_add 的 level 参数）必须在写第一条之前设置
                if (level in 0..9) zos.setLevel(level)
                ZipFile(input).use { zf ->
                    for (e in zf.entries()) {
                        if (skip(e.name)) continue
                        val override = replace(e.name)
                        if (override != null) applied.add(e.name)
                        written.add(e.name)
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
                                val pad = paddingFor(counting.count, nameLen, extra.size, align)
                                // 填充必须是合法的 alignment extra 块（id 0xd935），裸追加
                                // 字节会让严格解析器判定 extra 结构损坏
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
                // 追加原包里不存在的条目（如回填一条新的 lib/<abi>/x.so）
                for ((name, bytes) in append) {
                    if (name in written) continue
                    writeFresh(zos, counting, name, bytes)
                    written.add(name)
                    applied.add(name)
                }
                // 按文件追加：内容留在磁盘上，逐条流式写入。归档工具加一个几十 MB 的 .so
                // 时，把整份读进 ByteArray 会把重打包进程顶上 OOM（APK 里 lib/*.so 就是这种尺寸）。
                for ((name, source) in appendFiles) {
                    if (name in written) continue
                    writeFreshFile(zos, counting, name, source)
                    written.add(name)
                    applied.add(name)
                }
            }
        }
        return applied
    }

    /** 写一个「原包里不存在」的新条目：method 与对齐按名字规则推导。 */
    private fun writeFresh(zos: ZipOutputStream, counting: SavedOffsetOutput, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        var extra = ByteArray(0)
        if (requiresStore(name)) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
            val align = if (isNativeLib(name)) 4096 else 4
            zos.flush()
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val pad = paddingFor(counting.count, nameLen, extra.size, align)
            if (pad > 0) extra += alignmentExtra(pad)
        }
        if (extra.isNotEmpty()) entry.extra = extra
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    /**
     * 写一个「原包里不存在」的新条目，内容来自磁盘文件（不整份载入内存）。
     *
     * STORED 条目要求 size/crc 在 putNextEntry 之前就绪，所以先扫一遍算 CRC；
     * 非 STORED 走单遍流式写入。对齐规则与 [writeFresh] 一致。
     */
    private fun writeFreshFile(zos: ZipOutputStream, counting: SavedOffsetOutput, name: String, source: File) {
        val entry = ZipEntry(name)
        entry.time = source.lastModified()
        var extra = ByteArray(0)
        if (requiresStore(name)) {
            entry.method = ZipEntry.STORED
            val size = source.length()
            val crc = CRC32()
            source.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            entry.size = size
            entry.compressedSize = size
            entry.crc = crc.value
            val align = if (isNativeLib(name)) 4096 else 4
            zos.flush()
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val pad = paddingFor(counting.count, nameLen, extra.size, align)
            if (pad > 0) extra += alignmentExtra(pad)
        }
        if (extra.isNotEmpty()) entry.extra = extra
        zos.putNextEntry(entry)
        source.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
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
