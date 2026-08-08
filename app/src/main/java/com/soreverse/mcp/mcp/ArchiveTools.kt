package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 塔菲逆核: 通用压缩/解压工具。
 *
 * ZIP: java.util.zip 内置支持。
 * TAR: 纯 Kotlin 实现 USTAR 格式读写。
 * TAR.GZ: TAR + GZip 组合。
 */
object ArchiveTools {

    // ── 工具函数 ──

    private fun detectFormat(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "tar.gz"
            lower.endsWith(".tar.bz2") -> "tar.bz2"
            lower.endsWith(".tar") -> "tar"
            lower.endsWith(".zip") -> "zip"
            lower.endsWith(".gz") -> "gz"
            lower.endsWith(".bz2") -> "bz2"
            lower.endsWith(".7z") -> "7z"
            else -> {
                // Try to detect by magic bytes
                try {
                    val magic = File(path).inputStream().use { it.readNBytes(4) }
                    when {
                        magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte() -> "zip"
                        magic.size >= 3 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> "gz"
                        magic.size >= 3 && magic[0] == 0x42.toByte() && magic[1] == 0x5A.toByte() && magic[2] == 0x68.toByte() -> "bz2"
                        magic.size >= 6 && magic[0] == 0x37.toByte() && magic[1] == 0x7A.toByte() && magic[2] == 0xBC.toByte() && magic[3] == 0xAF.toByte() && magic[4] == 0x27.toByte() && magic[5] == 0x1C.toByte() -> "7z"
                        // TAR magic at byte 257: "ustar"
                        magic.size >= 262 -> {
                            val m = File(path).inputStream().use { it.readNBytes(262) }
                            if (m.size >= 262 && m[257] == 0x75.toByte() && m[258] == 0x73.toByte() && m[259] == 0x74.toByte() && m[260] == 0x61.toByte() && m[261] == 0x72.toByte()) "tar"
                            else "unknown"
                        }
                        else -> "unknown"
                    }
                } catch (_: Exception) { "unknown" }
            }
        }
    }

    private fun formatName(format: String): String = when (format) {
        "zip" -> "ZIP"
        "tar" -> "TAR"
        "tar.gz", "tgz" -> "TAR.GZ"
        "tar.bz2" -> "TAR.BZ2"
        "gz" -> "GZip"
        "bz2" -> "BZip2"
        "7z" -> "7z"
        else -> format
    }

    // ── TAR 格式实现 (USTAR) ──

    private class TarHeader {
        var name: String = ""
        var mode: Int = 0x1A4 /* 0o644 oct = 420 dec */
        var uid: Int = 0
        var gid: Int = 0
        var size: Long = 0
        var mtime: Long = 0
        var typeflag: Byte = '0'.code.toByte()
        var linkname: String = ""
        var uname: String = ""
        var gname: String = ""
        var prefix: String = ""

        companion object {
            const val BLOCK_SIZE = 512
            const val MAGIC = "ustar"
            const val VERSION = "00"

            fun read(input: InputStream): TarHeader? {
                val buf = ByteArray(BLOCK_SIZE)
                var totalRead = 0
                while (totalRead < BLOCK_SIZE) {
                    val n = input.read(buf, totalRead, BLOCK_SIZE - totalRead)
                    if (n < 0) return null
                    totalRead += n
                }
                // Check if it's an end-of-archive marker (two zero blocks)
                if (buf.all { it == 0.toByte() }) return null

                val header = TarHeader()
                header.name = buf.readString(0, 100)
                header.mode = buf.readOctal(100, 8)
                header.uid = buf.readOctal(108, 8)
                header.gid = buf.readOctal(116, 8)
                header.size = buf.readOctalLong(124, 12)
                header.mtime = buf.readOctalLong(136, 12)
                // chksum at 148, 8 bytes - skip
                header.typeflag = buf[156]
                header.linkname = buf.readString(157, 100)
                // magic at 257, 6 bytes - skip verify
                // version at 263, 2 bytes
                header.uname = buf.readString(265, 32)
                header.gname = buf.readString(297, 32)
                header.prefix = buf.readString(345, 155)

                return header
            }

            fun write(output: OutputStream, header: TarHeader, data: ByteArray) {
                val buf = ByteArray(BLOCK_SIZE)
                val nameBytes = header.name.toByteArray(Charsets.UTF_8)
                val prefixBytes = header.prefix.toByteArray(Charsets.UTF_8)

                if (nameBytes.size <= 100 && prefixBytes.size <= 155) {
                    System.arraycopy(nameBytes, 0, buf, 0, minOf(nameBytes.size, 100))
                    buf.writeOctal(100, 8, header.mode)
                    buf.writeOctal(108, 8, header.uid)
                    buf.writeOctal(116, 8, header.gid)
                    buf.writeOctalLong(124, 12, header.size)
                    buf.writeOctalLong(136, 12, header.mtime)
                    // chksum at 148 - write later
                    buf[156] = header.typeflag
                    val linkBytes = header.linkname.toByteArray(Charsets.UTF_8)
                    System.arraycopy(linkBytes, 0, buf, 157, minOf(linkBytes.size, 100))
                    // magic
                    val magic = "ustar".toByteArray()
                    System.arraycopy(magic, 0, buf, 257, magic.size)
                    buf[263] = '0'.code.toByte()
                    buf[264] = '0'.code.toByte()
                    val unameBytes = header.uname.toByteArray(Charsets.UTF_8)
                    System.arraycopy(unameBytes, 0, buf, 265, minOf(unameBytes.size, 32))
                    val gnameBytes = header.gname.toByteArray(Charsets.UTF_8)
                    System.arraycopy(gnameBytes, 0, buf, 297, minOf(gnameBytes.size, 32))
                    System.arraycopy(prefixBytes, 0, buf, 345, minOf(prefixBytes.size, 155))
                }

                // Calculate checksum
                var chk: Long = 0
                for (i in buf.indices) {
                    if (i in 148..155) chk += ' '.code.toLong() else chk += (buf[i].toInt() and 0xFF).toLong()
                }
                buf.writeOctalLong(148, 7, chk)
                buf[155] = ' '.code.toByte()

                output.write(buf)
                output.write(data)
                // Pad to block size
                val pad = BLOCK_SIZE - (data.size % BLOCK_SIZE)
                if (pad < BLOCK_SIZE) output.write(ByteArray(pad))
            }

            fun writeEnd(output: OutputStream) {
                output.write(ByteArray(BLOCK_SIZE * 2))
            }
        }
    }

    private fun ByteArray.readString(offset: Int, maxLen: Int): String {
        val end = (offset until minOf(offset + maxLen, size)).firstOrNull { this[it] == 0.toByte() } ?: minOf(offset + maxLen, size)
        return String(this, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun ByteArray.readOctal(offset: Int, len: Int): Int {
        return readOctalLong(offset, len).toInt()
    }

    private fun ByteArray.readOctalLong(offset: Int, len: Int): Long {
        var value = 0L
        for (i in offset until minOf(offset + len, size)) {
            val c = this[i].toInt()
            if (c in 0x30..0x37) value = (value shl 3) or (c - 0x30).toLong()
            else if (c == 0) break
        }
        return value
    }

    private fun ByteArray.writeOctal(offset: Int, len: Int, value: Int) {
        writeOctalLong(offset, len, value.toLong())
    }

    private fun ByteArray.writeOctalLong(offset: Int, len: Int, value: Long) {
        var v = value
        for (i in (offset + len - 1) downTo offset) {
            this[i] = ('0'.code + (v % 8).toInt()).toByte()
            v /= 8
        }
    }

    // ── taffy_archive_list ──
    val list = EngineToolHandler(
        ToolMeta("taffy_archive_list",
            "【压缩包列表】列出压缩包内的文件清单。支持 ZIP、TAR、TAR.GZ、GZip 格式。自动检测格式。",
            "List contents of an archive. Supports ZIP, TAR, TAR.GZ, GZip. Auto-detects format by magic bytes.",
            "archive", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "压缩文件绝对路径"
                "format".oneOf("压缩格式（留空自动检测）", "", "zip", "tar", "tar.gz", "gz")
                "limit" int "最多返回条目数（默认 1000, 最大 100000）"
                "cursor" str "分页游标"
            })
        }
    ) { _, a, s ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)

        val format = a.str("format").ifBlank { detectFormat(path) }
        if (format == "unknown") return@EngineToolHandler err("UNKNOWN_FORMAT", "无法检测压缩格式, 请指定 format 参数", "path", path)
        if (format == "7z" || format == "bz2") return@EngineToolHandler err("UNSUPPORTED_FORMAT", "暂不支持 $format 格式", "format", format)

        val limit = a.intValue("limit", 1000).coerceIn(1, 100000)
        val cursor = a.str("cursor").toIntOrNull() ?: 0

        val entries = JSONArray()
        var count = 0
        var skipped = 0

        when (format) {
            "zip" -> {
                java.util.zip.ZipFile(file).use { zf ->
                    val allEntries = zf.entries().asSequence().toList()
                    val total = allEntries.size
                    allEntries.forEachIndexed { idx, entry ->
                        if (idx < cursor) { skipped++; return@forEachIndexed }
                        if (count >= limit) return@forEachIndexed
                        entries.put(JSONObject()
                            .put("name", entry.name)
                            .put("size", entry.size)
                            .put("compressedSize", entry.compressedSize)
                            .put("isDir", entry.isDirectory)
                            .put("crc", entry.crc)
                            .put("method", if (entry.method == ZipEntry.STORED) "stored" else "deflated")
                        )
                        count++
                    }
                    ok(listResult(path, format, entries, total, cursor, count, skipped, limit))
                }
            }
            "tar", "tar.gz" -> {
                val input: InputStream = if (format == "tar.gz") {
                    GZIPInputStream(FileInputStream(file))
                } else FileInputStream(file)

                input.buffered().use { bis ->
                    val totalEntries = mutableListOf<String>()
                    var totalSizes = mutableListOf<Long>()
                    while (true) {
                        val hdr = TarHeader.read(bis) ?: break
                        totalEntries.add(hdr.name)
                        totalSizes.add(hdr.size)
                        // Skip data
                        val skip = hdr.size
                        var remaining = skip
                        while (remaining > 0) {
                            val s = bis.skip(remaining)
                            if (s <= 0) break
                            remaining -= s
                        }
                        // Skip padding
                        val pad = (TarHeader.BLOCK_SIZE - (skip % TarHeader.BLOCK_SIZE)) % TarHeader.BLOCK_SIZE
                        var padRemaining = pad.toLong()
                        while (padRemaining > 0) {
                            val s = bis.skip(padRemaining)
                            if (s <= 0) break
                            padRemaining -= s
                        }
                    }
                    for (i in cursor until minOf(totalEntries.size, cursor + limit)) {
                        entries.put(JSONObject()
                            .put("name", totalEntries[i])
                            .put("size", totalSizes[i])
                            .put("isDir", totalEntries[i].endsWith("/"))
                        )
                    }
                    ok(listResult(path, format, entries, totalEntries.size, cursor, entries.length(), cursor, limit))
                }
            }
            "gz" -> {
                entries.put(JSONObject().put("name", file.name.replace(".gz", "")).put("size", file.length()).put("isDir", false))
                ok(listResult(path, format, entries, 1, 0, 1, 0, limit))
            }
            else -> err("UNSUPPORTED_FORMAT", "暂不支持 $format 格式", "format", format)
        }
    }

    private fun listResult(
        path: String, format: String, entries: JSONArray,
        total: Int, cursor: Int, returned: Int, skipped: Int, limit: Int,
    ): JSONObject = JSONObject()
        .put("path", path)
        .put("format", format)
        .put("total", total)
        .put("returned", returned)
        .put("entries", entries)
        .apply {
            val next = cursor + limit
            if (next < total) put("nextCursor", next.toString())
        }

    // ── taffy_archive_extract ──
    val extract = EngineToolHandler(
        ToolMeta("taffy_archive_extract",
            "【压缩包解压】解压压缩包到指定目录。支持 ZIP、TAR、TAR.GZ。自动检测格式。",
            "Extract archive to a directory. Supports ZIP, TAR, TAR.GZ. Auto-detects format.",
            "archive", ToolClass.CORE,
        ) {
            objectSchema(props {
                "path" str "压缩文件绝对路径"
                "outputDir" str "解压目标目录（自动创建）"
                "format".oneOf("压缩格式（留空自动检测）", "", "zip", "tar", "tar.gz")
                "overwrite" bool "是否覆盖已存在的文件（默认 true）"
                "filter" str "仅解压匹配的文件名模式（支持 * 通配符）"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)

        val format = a.str("format").ifBlank { detectFormat(path) }
        if (format == "unknown") return@EngineToolHandler err("UNKNOWN_FORMAT", "无法检测压缩格式", "path", path)
        if (format == "7z" || format == "bz2") return@EngineToolHandler err("UNSUPPORTED_FORMAT", "暂不支持 $format 格式", "format", format)

        val outputDir = File(a.str("outputDir"))
        outputDir.mkdirs()
        val overwrite = a.bool("overwrite", true)
        val filterStr = a.str("filter")

        val filterRegex = if (filterStr.isNotBlank()) {
            Regex(filterStr.replace(".", "\\.").replace("*", ".*").replace("?", "."), RegexOption.IGNORE_CASE)
        } else null

        var extracted = 0
        var skipped = 0
        val errors = JSONArray()

        when (format) {
            "zip" -> {
                ZipInputStream(FileInputStream(file)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && (filterRegex == null || filterRegex.matches(entry.name))) {
                            val outFile = File(outputDir, entry.name)
                            outFile.parentFile?.mkdirs()
                            if (!outFile.exists() || overwrite) {
                                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                outFile.setLastModified(entry.time)
                                extracted++
                            } else skipped++
                        } else if (entry.isDirectory) {
                            File(outputDir, entry.name).mkdirs()
                        } else skipped++
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            "tar", "tar.gz" -> {
                val input: InputStream = if (format == "tar.gz") {
                    GZIPInputStream(FileInputStream(file))
                } else FileInputStream(file)
                input.buffered().use { bis ->
                    while (true) {
                        val hdr = TarHeader.read(bis) ?: break
                        val data = ByteArray(hdr.size.toInt())
                        var totalRead = 0
                        while (totalRead < hdr.size) {
                            val n = bis.read(data, totalRead, (hdr.size - totalRead).toInt())
                            if (n < 0) break
                            totalRead += n
                        }
                        // Skip padding
                        val pad = (TarHeader.BLOCK_SIZE - (hdr.size % TarHeader.BLOCK_SIZE)) % TarHeader.BLOCK_SIZE
                        var padRemaining = pad
                        while (padRemaining > 0) { val s = bis.skip(padRemaining); if (s <= 0) break; padRemaining -= s }

                        val name = hdr.name
                        if (filterRegex == null || filterRegex.matches(name)) {
                            val outFile = File(outputDir, name)
                            if (hdr.typeflag == '5'.code.toByte() || name.endsWith("/")) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                if (!outFile.exists() || overwrite) {
                                    outFile.writeBytes(data)
                                    if (hdr.mtime > 0) outFile.setLastModified(hdr.mtime * 1000)
                                    extracted++
                                } else skipped++
                            }
                        }
                    }
                }
            }
            "gz" -> {
                val outName = file.nameWithoutExtension
                val outFile = File(outputDir, outName)
                if (!outFile.exists() || overwrite) {
                    GZIPInputStream(FileInputStream(file)).use { gz ->
                        outFile.outputStream().use { gz.copyTo(it) }
                    }
                    extracted++
                } else skipped++
            }
        }

        ok(JSONObject()
            .put("path", path)
            .put("format", format)
            .put("outputDir", outputDir.absolutePath)
            .put("extracted", extracted)
            .put("skipped", skipped)
            .apply { if (errors.length() > 0) put("errors", errors) })
    }

    // ── taffy_archive_create ──
    val create = EngineToolHandler(
        ToolMeta("taffy_archive_create",
            "【压缩包创建】创建新的压缩包。支持 ZIP、TAR、TAR.GZ 格式。可添加多个文件/目录, 目录递归添加。支持压缩级别设置。",
            "Create a new archive. Supports ZIP, TAR, TAR.GZ. Add multiple files/directories (recursive).",
            "archive", ToolClass.CORE,
        ) {
            objectSchema(props {
                "outputPath" str "输出的压缩文件绝对路径"
                "format".oneOf("压缩格式（从扩展名自动检测）", "", "zip", "tar", "tar.gz")
                "files" arr "要添加的文件/目录绝对路径列表"
                "baseDir" str "存储路径的基准目录（如设置, 压缩包内路径相对于此目录）"
                "compressionLevel" int "压缩级别 0-9（ZIP, 默认 6; TAR 无压缩）"
            })
        }
    ) { _, a, _ ->
        val outputPath = a.str("outputPath")
        val format = a.str("format").ifBlank { detectFormat(outputPath) }
        if (format == "unknown") return@EngineToolHandler err("UNKNOWN_FORMAT", "无法确定压缩格式, 请指定 format", "format", "")
        if (format == "7z" || format == "bz2") return@EngineToolHandler err("UNSUPPORTED_FORMAT", "暂不支持 $format 格式", "format", format)

        val files = a.optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return@EngineToolHandler err("NO_FILES", "请指定要打包的文件", "files", "")

        val baseDir = a.str("baseDir")
        val level = a.intValue("compressionLevel", 6).coerceIn(0, 9)

        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        var totalFiles = 0
        var totalBytes = 0L

        // Collect all files
        val entries = mutableListOf<Pair<String, File>>()
        for (i in 0 until files.length()) {
            val f = File(files.getString(i))
            if (!f.exists()) continue
            val entryName = if (baseDir.isNotBlank()) {
                f.absolutePath.removePrefix(File(baseDir).absolutePath + File.separator).removePrefix(File(baseDir).absolutePath)
            } else f.name
            if (f.isDirectory) {
                f.walkTopDown().forEach { sub ->
                    if (sub.isFile) entries.add(sub.absolutePath.removePrefix(f.parent + File.separator) to sub)
                }
            } else {
                entries.add(entryName to f)
            }
        }

        when (format) {
            "zip" -> {
                ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                    zos.setLevel(level)
                    for ((name, f) in entries) {
                        val safeName = name.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry(safeName))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        totalFiles++
                        totalBytes += f.length()
                    }
                }
            }
            "tar", "tar.gz" -> {
                val output: OutputStream = if (format == "tar.gz") {
                    GZIPOutputStream(FileOutputStream(outputFile))
                } else FileOutputStream(outputFile)
                output.buffered().use { bos ->
                    for ((name, f) in entries) {
                        val data = f.readBytes()
                        val safeName = name.replace(File.separatorChar, '/')
                        val hdr = TarHeader().apply {
                            this.name = safeName
                            size = data.size.toLong()
                            mtime = f.lastModified() / 1000
                            typeflag = '0'.code.toByte()
                        }
                        TarHeader.write(bos, hdr, data)
                        totalFiles++
                        totalBytes += data.size
                    }
                    TarHeader.writeEnd(bos)
                }
            }
        }

        ok(JSONObject()
            .put("outputPath", outputFile.absolutePath)
            .put("format", format)
            .put("totalFiles", totalFiles)
            .put("totalBytes", totalBytes)
            .put("archiveSize", outputFile.length()))
    }

    // ── ZIP 修改工具 ──

    // taffy_archive_add: add files to existing ZIP
    val add = EngineToolHandler(
        ToolMeta("taffy_archive_add",
            "【ZIP 添加文件】向已有的 ZIP 压缩包中添加新文件。如需更新已有文件请先 taffy_archive_delete 再 taffy_archive_add。",
            "Add new files to an existing ZIP archive. To update existing entries, delete first then add.",
            "archive", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "已有 ZIP 文件绝对路径（仅支持 ZIP）"
                "files" arr "要添加的文件绝对路径列表"
                "baseDir" str "存储路径的基准目录"
                "compressionLevel" int "压缩级别 0-9（默认 6）"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)
        val format = detectFormat(path)
        if (format != "zip") return@EngineToolHandler err("UNSUPPORTED_FORMAT", "仅支持 ZIP 格式的修改操作", "format", format)

        val files = a.optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return@EngineToolHandler err("NO_FILES", "请指定要添加的文件", "files", "")

        val baseDir = a.str("baseDir")
        val level = a.intValue("compressionLevel", 6).coerceIn(0, 9)

        val tmpFile = File(file.absolutePath + ".tmp")

        try {
            val existing = mutableMapOf<String, Pair<ZipEntry, ByteArray>>()
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val ba = zis.readBytes()
                        existing[entry.name] = entry to ba
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Collect new files
            val newEntries = mutableListOf<Pair<String, File>>()
            for (i in 0 until files.length()) {
                val f = File(files.getString(i))
                if (!f.isFile) continue
                val entryName = if (baseDir.isNotBlank()) {
                    f.absolutePath.removePrefix(File(baseDir).absolutePath + File.separator).removePrefix(File(baseDir).absolutePath)
                } else f.name
                newEntries.add(entryName to f)
            }

            ZipOutputStream(FileOutputStream(tmpFile)).use { zos ->
                zos.setLevel(level)
                // Write existing entries
                for ((name, pair) in existing) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(pair.second)
                    zos.closeEntry()
                }
                // Write new entries
                for ((name, f) in newEntries) {
                    val safeName = name.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(safeName))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            tmpFile.renameTo(file)
            ok(JSONObject()
                .put("path", path)
                .put("added", newEntries.size)
                .put("existingEntries", existing.size))
        } catch (e: Exception) {
            tmpFile.delete()
            err("ARCHIVE_ERROR", "添加文件失败: ${e.message}", "path", path)
        }
    }

    // taffy_archive_delete: remove entries from ZIP
    val delete = EngineToolHandler(
        ToolMeta("taffy_archive_delete",
            "【ZIP 删除条目】从 ZIP 压缩包中删除指定文件/目录条目。支持通配符匹配。",
            "Delete entries from a ZIP archive. Supports wildcard patterns (*, ?).",
            "archive", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "已有 ZIP 文件绝对路径（仅支持 ZIP）"
                "entries" arr "要删除的条目名称列表（支持 * 通配符, 如 *.dex images/*）"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)
        val format = detectFormat(path)
        if (format != "zip") return@EngineToolHandler err("UNSUPPORTED_FORMAT", "仅支持 ZIP 格式", "format", format)

        val patterns = a.optJSONArray("entries") ?: JSONArray()
        if (patterns.length() == 0) return@EngineToolHandler err("NO_ENTRIES", "请指定要删除的条目", "entries", "")

        val regexes = mutableListOf<Regex>()
        for (i in 0 until patterns.length()) {
            val p = patterns.getString(i)
            val re = p.replace(".", "\\.").replace("*", ".*").replace("?", ".")
            regexes.add(Regex(re))
        }

        val tmpFile = File(file.absolutePath + ".tmp")
        var deleted = 0
        var kept = 0

        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                ZipOutputStream(FileOutputStream(tmpFile)).use { zos ->
                    zos.setLevel(6)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val shouldDelete = regexes.any { it.matches(entry.name) }
                        if (entry.isDirectory) {
                            if (!shouldDelete) {
                                zos.putNextEntry(ZipEntry(entry.name))
                                zos.closeEntry()
                                kept++
                            } else deleted++
                        } else {
                            val data = zis.readBytes()
                            if (!shouldDelete) {
                                zos.putNextEntry(ZipEntry(entry.name))
                                zos.write(data)
                                zos.closeEntry()
                                kept++
                            } else deleted++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            tmpFile.renameTo(file)
            ok(JSONObject().put("path", path).put("deleted", deleted).put("kept", kept))
        } catch (e: Exception) {
            tmpFile.delete()
            err("ARCHIVE_ERROR", "删除条目失败: ${e.message}", "path", path)
        }
    }

    // taffy_archive_rename: rename entry inside ZIP
    val rename = EngineToolHandler(
        ToolMeta("taffy_archive_rename",
            "【ZIP 重命名条目】重命名 ZIP 压缩包中的条目（文件或目录）。支持单个条目重命名。",
            "Rename an entry inside a ZIP archive. Supports renaming individual files or directories.",
            "archive", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "已有 ZIP 文件绝对路径（仅支持 ZIP）"
                "oldName" str "原条目名称"
                "newName" str "新条目名称"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)
        val format = detectFormat(path)
        if (format != "zip") return@EngineToolHandler err("UNSUPPORTED_FORMAT", "仅支持 ZIP 格式", "format", format)

        val oldName = a.str("oldName")
        val newName = a.str("newName")
        if (oldName.isBlank() || newName.isBlank()) return@EngineToolHandler err("INVALID_ARGUMENTS", "oldName 和 newName 必填", "oldName", oldName)

        val tmpFile = File(file.absolutePath + ".tmp")
        var found = false

        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                ZipOutputStream(FileOutputStream(tmpFile)).use { zos ->
                    zos.setLevel(6)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val targetName = if (entry.name == oldName) {
                            found = true; newName
                        } else entry.name

                        if (entry.isDirectory) {
                            zos.putNextEntry(ZipEntry(targetName))
                            zos.closeEntry()
                        } else {
                            val data = zis.readBytes()
                            zos.putNextEntry(ZipEntry(targetName))
                            zos.write(data)
                            zos.closeEntry()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            if (!found) {
                tmpFile.delete()
                return@EngineToolHandler err("ENTRY_NOT_FOUND", "未找到条目: $oldName", "oldName", oldName)
            }
            tmpFile.renameTo(file)
            ok(JSONObject().put("path", path).put("oldName", oldName).put("newName", newName))
        } catch (e: Exception) {
            tmpFile.delete()
            err("ARCHIVE_ERROR", "重命名失败: ${e.message}", "path", path)
        }
    }
}
