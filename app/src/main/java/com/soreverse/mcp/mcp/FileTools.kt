package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import kotlin.math.min

/**
 * 塔菲逆核: 通用文件与文本工具。
 *
 * 补齐逆向工作流中的基础文件操作: 浏览、读写、搜索替换、差异对比、批量重命名。
 * 纯 Kotlin 实现, 无外部依赖。
 */
object FileTools {

    // ── file_list ──
    val list = EngineToolHandler(
        ToolMeta(
            "file_list",
            "【文件列表】列出目录内容（文件名/大小/修改时间/是目录还是文件）。支持 limit/cursor 分页和 filter 过滤。",
            "List directory contents: name, size, modification time, type. Supports pagination and filtering.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "目录绝对路径"
                "filter" str "文件名过滤关键词（支持 * 通配符, 如 *.dex *.so）"
                "sortBy".oneOf("排序字段", "name", "size", "time")
                "desc" bool "是否降序（默认 false）"
                "limit" int "最多返回条数（默认 200, 最大 5000）"
                "cursor" str "分页游标（上一次返回的 nextCursor）"
            })
        }
    ) { _, a, s ->
        val dir = File(a.str("path"))
        if (!dir.isDirectory) return@EngineToolHandler err("NOT_DIR", "路径不是目录或不存在", "path", a.str("path"))
        val limit = a.intValue("limit", 200).coerceIn(1, 5000)
        val cursor = a.str("cursor")
        val cursorIdx = cursor.toIntOrNull() ?: 0

        var files = dir.listFiles()?.toList() ?: return@EngineToolHandler err("IO_ERROR", "无法读取目录", "path", a.str("path"))

        // Filter
        val filter = a.str("filter")
        if (filter.isNotBlank()) {
            val regex = filter
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
                .toRegex(RegexOption.IGNORE_CASE)
            files = files.filter { regex.matches(it.name) }
        }

        // Sort
        val desc = a.bool("desc", false)
        val cmp = when (a.str("sortBy", "name")) {
            "size" -> compareBy<File> { it.length() }
            "time" -> compareBy<File> { it.lastModified() }
            else -> compareBy<File> { it.name.lowercase() }
        }
        files = if (desc) files.sortedWith(cmp.reversed()) else files.sortedWith(cmp)

        // Paginate
        val total = files.size
        val page = files.drop(cursorIdx).take(limit)
        val nextCursor = if (cursorIdx + limit < total) (cursorIdx + limit).toString() else ""

        val entries = JSONArray()
        page.forEach { f ->
            entries.put(JSONObject()
                .put("name", f.name)
                .put("path", f.absolutePath)
                .put("isDir", f.isDirectory)
                .put("size", f.length())
                .put("lastModified", f.lastModified())
            )
        }

        ok(JSONObject()
            .put("path", dir.absolutePath)
            .put("total", total)
            .put("returned", entries.length())
            .put("entries", entries)
            .apply { if (nextCursor.isNotBlank()) put("nextCursor", nextCursor) }
        )
    }

    // ── file_read ──
    val read = EngineToolHandler(
        ToolMeta(
            "file_read",
            "【文件读取】读文件内容。文字模式按行范围返回; base64 模式把文件以 base64 编码返回（用于二进制/图片/so 等）; hex 模式返回十六进制转储。",
            "Read file content. Text mode (default) returns lines in range; base64 mode returns base64-encoded bytes; hex mode returns a hex dump.",
            "file", ToolClass.CORE,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "mode".oneOf("读取模式", "text", "base64", "hex")
                "encoding" str "文本编码（默认 UTF-8）"
                "offset" int "起始行号（text 模式, 从 1 开始, 默认 1）"
                "limit" int "最多返回行数（text 模式, 默认 500, 最大 10000）"
                "maxBytes" int "最多读取字节数（base64/hex 模式, 默认 1MB）"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)
        val mode = a.str("mode", "text")

        when (mode) {
            "base64" -> {
                val maxBytes = a.intValue("maxBytes", 1_000_000).coerceIn(1, 100_000_000)
                val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
                val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                ok(JSONObject()
                    .put("mode", "base64")
                    .put("path", path)
                    .put("fileSize", file.length())
                    .put("readBytes", bytes.size)
                    .put("truncated", file.length() > maxBytes)
                    .put("content", b64))
            }
            "hex" -> {
                val maxBytes = a.intValue("maxBytes", 4096).coerceIn(1, 1_000_000)
                val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
                val sb = StringBuilder()
                var i = 0
                while (i < bytes.size) {
                    val end = min(i + 16, bytes.size)
                    sb.append("%08X  ".format(i))
                    for (j in i until end) sb.append("%02X ".format(bytes[j]))
                    if (end - i < 16) sb.append("   ".repeat(16 - (end - i)))
                    sb.append(" |")
                    for (j in i until end) {
                        val c = bytes[j].toInt() and 0xFF
                        sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
                    }
                    sb.append("|\n")
                    i = end
                }
                ok(JSONObject()
                    .put("mode", "hex")
                    .put("path", path)
                    .put("fileSize", file.length())
                    .put("readBytes", bytes.size)
                    .put("truncated", file.length() > maxBytes)
                    .put("content", sb.toString()))
            }
            else -> { // text
                val encoding = a.str("encoding", "UTF-8").ifBlank { "UTF-8" }
                val offset = (a.intValue("offset", 1) - 1).coerceAtLeast(0)
                val limit = a.intValue("limit", 500).coerceIn(1, 10000)
                val lines = file.readLines(Charset.forName(encoding))
                val totalLines = lines.size
                val page = lines.drop(offset).take(limit)
                ok(JSONObject()
                    .put("mode", "text")
                    .put("path", path)
                    .put("fileSize", file.length())
                    .put("totalLines", totalLines)
                    .put("offset", offset + 1)
                    .put("returned", page.size)
                    .put("lines", JSONArray(page))
                    .apply {
                        val next = offset + limit
                        if (next < totalLines) put("nextOffset", next + 1)
                    })
            }
        }
    }

    // ── file_write ──
    val write = EngineToolHandler(
        ToolMeta(
            "file_write",
            "【文件写入】写入或追加内容到文件。自动创建不存在的父目录。mode=append 追加到文件末尾; mode=overwrite 覆盖; mode=create 仅新建（已存在报错）。支持 text/base64 两种输入格式。",
            "Write or append text/base64 content to a file. Auto-creates parent directories. mode=append appends; overwrite replaces; create only writes to new files.",
            "file", ToolClass.CORE,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "content" str "写入内容（text 模式为文本, base64 模式为 base64 编码）"
                "inputFormat".oneOf("输入格式", "text", "base64")
                "mode".oneOf("写入模式", "overwrite", "append", "create")
                "encoding" str "文本编码（text 格式, 默认 UTF-8）"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val content = a.str("content")
        val inputFormat = a.str("inputFormat", "text")
        val writeMode = a.str("mode", "overwrite")
        val encoding = a.str("encoding", "UTF-8").ifBlank { "UTF-8" }

        val file = File(path)
        if (writeMode == "create" && file.exists()) {
            return@EngineToolHandler err("FILE_EXISTS", "文件已存在, 如需覆盖请用 overwrite 模式", "path", path)
        }
        file.parentFile?.mkdirs()

        val bytes = if (inputFormat == "base64") {
            java.util.Base64.getDecoder().decode(content)
        } else {
            content.toByteArray(Charset.forName(encoding))
        }

        when (writeMode) {
            "append" -> file.appendBytes(bytes)
            else -> file.writeBytes(bytes)
        }

        ok(JSONObject()
            .put("path", path)
            .put("mode", writeMode)
            .put("bytesWritten", bytes.size)
            .put("fileSize", file.length()))
    }

    // ── file_search ──
    val search = EngineToolHandler(
        ToolMeta(
            "file_search",
            "【文件内容搜索】在文本文件中搜索匹配 pattern 的行。支持正则和纯文本匹配, 可限制搜索范围和结果数量。",
            "Search text files for lines matching a pattern. Supports regex and plain text matching.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "pattern" str "搜索模式（regex=true 时为正则, 否则为纯文本）"
                "regex" bool "是否作为正则表达式匹配（默认 false）"
                "ignoreCase" bool "忽略大小写（默认 false）"
                "limit" int "最多返回条数（默认 100, 最大 5000）"
                "encoding" str "文本编码（默认 UTF-8）"
            })
        }
    ) { _, a, s ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)
        val pattern = a.str("pattern")
        if (pattern.isBlank()) return@EngineToolHandler err("NO_PATTERN", "请输入搜索模式", "pattern", "")

        val encoding = a.str("encoding", "UTF-8").ifBlank { "UTF-8" }
        val limit = a.intValue("limit", 100).coerceIn(1, 5000)
        val ignoreCase = a.bool("ignoreCase", false)

        val regex = if (a.bool("regex", false)) {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } else null

        val query = if (ignoreCase) pattern.lowercase() else pattern

        val results = JSONArray()
        var count = 0
        file.useLines(Charset.forName(encoding)) { lines ->
            lines.forEachIndexed { idx, line ->
                if (count >= limit) return@forEachIndexed
                val matched = if (regex != null) regex.containsMatchIn(line)
                    else if (ignoreCase) line.lowercase().contains(query)
                    else line.contains(query)
                if (matched) {
                    results.put(JSONObject().put("lineNumber", idx + 1).put("content", line))
                    count++
                }
            }
        }

        ok(JSONObject()
            .put("path", path)
            .put("pattern", pattern)
            .put("matched", results.length())
            .put("results", results))
    }

    // ── file_replace ──
    val replace = EngineToolHandler(
        ToolMeta(
            "file_replace",
            "【文件内容替换】在文本文件中查找并替换文本。支持正则捕获组替换（如 $1）。mode=all 替换所有匹配; mode=first 替换第一个; mode=lines 只替换指定行号。",
            "Find and replace text in a file. Supports regex capture groups ($1). mode=all replaces all matches; mode=first replaces only the first; mode=lines replaces on specific line numbers.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "find" str "要查找的文本（regex=true 时为正则）"
                "replace" str "替换文本（支持 $1 等正则捕获组引用）"
                "regex" bool "是否作为正则表达式（默认 false）"
                "ignoreCase" bool "忽略大小写（默认 false）"
                "mode".oneOf("替换模式", "all", "first")
                "encoding" str "文本编码（默认 UTF-8）"
                "backup" bool "是否创建 .bak 备份文件（默认 true）"
            })
        }
    ) { _, a, _ ->
        val path = a.str("path")
        val file = File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)
        val find = a.str("find")
        if (find.isBlank()) return@EngineToolHandler err("NO_FIND", "请指定要查找的文本", "find", "")

        val encoding = a.str("encoding", "UTF-8").ifBlank { "UTF-8" }
        val replaceText = a.str("replace")
        val mode = a.str("mode", "all")
        val ignoreCase = a.bool("ignoreCase", false)
        val backup = a.bool("backup", true)

        val text = file.readText(Charset.forName(encoding))

        val resultText = if (a.bool("regex", false)) {
            val regex = if (ignoreCase) Regex(find, RegexOption.IGNORE_CASE) else Regex(find)
            if (mode == "first") regex.replaceFirst(text, replaceText) else regex.replace(text, replaceText)
        } else {
            if (mode == "first") {
                val q = if (ignoreCase) text.lowercase() else text
                val f = if (ignoreCase) find.lowercase() else find
                val idx = q.indexOf(f)
                if (idx < 0) return@EngineToolHandler err("NOT_FOUND", "未找到匹配文本", "find", find)
                text.substring(0, idx) + replaceText + text.substring(idx + find.length)
            } else {
                if (ignoreCase) {
                    text.replace(Regex(Regex.escape(find), RegexOption.IGNORE_CASE), replaceText)
                } else {
                    text.replace(find, replaceText)
                }
            }
        }

        if (resultText == text) return@EngineToolHandler err("NOT_FOUND", "未找到匹配文本", "find", find)

        if (backup) {
            val bak = File("${path}.bak")
            file.copyTo(bak, overwrite = true)
        }
        file.writeText(resultText, Charset.forName(encoding))

        ok(JSONObject()
            .put("path", path)
            .put("replaced", true)
            .put("hasBackup", backup))
    }

    // ── file_diff ──
    private class MyersDiff(
        private val a: List<String>,
        private val b: List<String>,
    ) {
        private val frontier = mutableMapOf<Int, Int>()
        private val history = mutableListOf<Map<Int, Int>>()

        data class Edit(val type: Char, val lineA: Int?, val lineB: Int?, val text: String)

        fun compute(): List<Edit> {
            val n = a.size
            val m = b.size
            val max = n + m

            frontier[1] = 0
            history.add(HashMap(frontier))

            for (d in 0..max) {
                for (k in (-d..d) step 2) {
                    var x = if (k == -d || (k != d && frontier.getOrDefault(k - 1, -1) < frontier.getOrDefault(k + 1, -1))) {
                        frontier.getOrDefault(k + 1, 0)
                    } else {
                        frontier.getOrDefault(k - 1, 0) + 1
                    }
                    var y = x - k

                    while (x < n && y < m && a[x] == b[y]) { x++; y++ }

                    frontier[k] = x
                    if (x >= n && y >= m) {
                        // Backtrack to build edit list
                        return backtrack(d)
                    }
                }
                history.add(HashMap(frontier))
            }
            return emptyList()
        }

        private fun backtrack(endD: Int): List<Edit> {
            val edits = mutableListOf<Edit>()
            var x = a.size
            var y = b.size
            for (d in endD downTo 1) {
                val v = history[d + 1] ?: continue
                val k = x - y
                val prevV = history[d]
                val goDown = k == -d || (k != d && prevV?.getOrDefault(k - 1, -1) ?: -1 < prevV?.getOrDefault(k + 1, -1) ?: -1)

                val prevK = if (goDown) k + 1 else k - 1
                val prevX = prevV?.get(prevK) ?: 0
                val prevY = prevX - prevK

                while (x > prevX && y > prevY) {
                    x--
                    y--
                    edits.add(Edit(' ', x, y, a[x]))
                }

                if (goDown) {
                    y--
                    edits.add(Edit('+', null, y, b[y]))
                } else {
                    x--
                    edits.add(Edit('-', x, null, a[x]))
                }
            }
            return edits.reversed()
        }
    }

    val diff = EngineToolHandler(
        ToolMeta(
            "file_diff",
            "【文本差异对比】对比两个文件的差异, 输出格式类似 diff -u。支持文本和 base64 两种输入方式。",
            "Compare two text files. Output format similar to diff -u. Supports file paths and direct text input.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "pathA" str "文件 A（与 pathB 二选一提供路径）"
                "pathB" str "文件 B"
                "textA" str "文本 A 直接输入（与 pathA 二选一）"
                "textB" str "文本 B 直接输入"
                "contextLines" int "上下文行数（默认 3）"
                "encoding" str "文本编码（默认 UTF-8）"
            })
        }
    ) { _, a, _ ->
        val encoding = a.str("encoding", "UTF-8").ifBlank { "UTF-8" }
        val contextLines = a.intValue("contextLines", 3).coerceIn(0, 50)

        val linesA = if (a.has("pathA") && a.str("pathA").isNotBlank()) {
            File(a.str("pathA")).readLines(Charset.forName(encoding))
        } else a.str("textA").lines()

        val linesB = if (a.has("pathB") && a.str("pathB").isNotBlank()) {
            File(a.str("pathB")).readLines(Charset.forName(encoding))
        } else a.str("textB").lines()

        val labelA = if (a.has("pathA")) a.str("pathA") else "textA"
        val labelB = if (a.has("pathB")) a.str("pathB") else "textB"

        val edits = MyersDiff(linesA, linesB).compute()

        // Generate unified diff format
        val sb = StringBuilder()
        sb.append("--- $labelA\n+++ $labelB\n")

        // Group edits into hunks with context
        val hunkGroups = mutableListOf<MutableList<MyersDiff.Edit>>()
        var currentHunk = mutableListOf<MyersDiff.Edit>()
        var lastLineNum = -1
        for (edit in edits) {
            val lineNum = edit.lineA ?: edit.lineB ?: 0
            if (currentHunk.isNotEmpty() && lineNum - lastLineNum > contextLines * 2) {
                // Close current hunk, start new one
                hunkGroups.add(currentHunk)
                currentHunk = mutableListOf()
            }
            currentHunk.add(edit)
            lastLineNum = lineNum
        }
        if (currentHunk.isNotEmpty()) hunkGroups.add(currentHunk)

        val unified = JSONArray()
        for (hunk in hunkGroups) {
            val startA = hunk.firstOrNull { it.lineA != null }?.lineA?.plus(1)?.coerceAtLeast(1) ?: 1
            val startB = hunk.firstOrNull { it.lineB != null }?.lineB?.plus(1)?.coerceAtLeast(1) ?: 1
            val countA = hunk.count { it.type == ' ' || it.type == '-' }
            val countB = hunk.count { it.type == ' ' || it.type == '+' }
            sb.append("@@ -$startA,$countA +$startB,$countB @@\n")

            val hunkLines = JSONArray()
            for (edit in hunk) {
                val line = when (edit.type) {
                    '+' -> "+${edit.text}"
                    '-' -> "-${edit.text}"
                    else -> " ${edit.text}"
                }
                sb.append(line).append('\n')
                hunkLines.put(JSONObject()
                    .put("type", edit.type.toString())
                    .put("lineA", edit.lineA?.plus(1))
                    .put("lineB", edit.lineB?.plus(1))
                    .put("text", edit.text))
            }
            unified.put(JSONObject()
                .put("startA", startA)
                .put("startB", startB)
                .put("countA", countA)
                .put("countB", countB)
                .put("lines", hunkLines))
        }

        ok(JSONObject()
            .put("diff", sb.toString())
            .put("hunks", unified)
            .put("totalEdits", edits.size)
            .put("added", edits.count { it.type == '+' })
            .put("removed", edits.count { it.type == '-' }))
    }

    // ── file_rename ──
    val rename = EngineToolHandler(
        ToolMeta(
            "file_rename",
            "【文件重命名/移动】重命名或移动文件/目录。如果目标路径在不同目录则执行移动。",
            "Rename or move a file/directory. Moving across directories is supported.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "source" str "源文件/目录绝对路径"
                "target" str "目标路径（新名称或新位置）"
                "overwrite" bool "如果目标已存在是否覆盖（默认 false）"
            })
        }
    ) { _, a, _ ->
        val src = File(a.str("source"))
        if (!src.exists()) return@EngineToolHandler err("NOT_FOUND", "源文件/目录不存在", "source", a.str("source"))
        val dst = File(a.str("target"))
        if (dst.exists() && !a.bool("overwrite", false)) return@EngineToolHandler err("TARGET_EXISTS", "目标已存在（设置 overwrite=true 覆盖）", "target", a.str("target"))
        if (dst.exists()) dst.deleteRecursively()
        dst.parentFile?.mkdirs()
        val ok_ = src.renameTo(dst)
        if (!ok_) return@EngineToolHandler err("RENAME_FAILED", "重命名失败", "source", a.str("source"))
        ok(JSONObject().put("source", src.absolutePath).put("target", dst.absolutePath).put("isDir", src.isDirectory))
    }

    // ── file_copy ──
    val copy = EngineToolHandler(
        ToolMeta(
            "file_copy",
            "【文件复制】复制文件或目录（目录递归复制）。",
            "Copy a file or directory (recursive for directories).",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "source" str "源文件/目录绝对路径"
                "target" str "目标路径"
                "overwrite" bool "是否覆盖已存在的目标（默认 false）"
            })
        }
    ) { _, a, _ ->
        val src = File(a.str("source"))
        if (!src.exists()) return@EngineToolHandler err("NOT_FOUND", "源文件/目录不存在", "source", a.str("source"))
        val dst = File(a.str("target"))
        if (dst.exists()) {
            if (!a.bool("overwrite", false)) return@EngineToolHandler err("TARGET_EXISTS", "目标已存在", "target", a.str("target"))
            dst.deleteRecursively()
        }
        dst.parentFile?.mkdirs()
        src.copyRecursively(dst, overwrite = true)
        ok(JSONObject().put("source", src.absolutePath).put("target", dst.absolutePath).put("isDir", src.isDirectory))
    }

    // ── file_delete ──
    val delete = EngineToolHandler(
        ToolMeta(
            "file_delete",
            "【文件删除】删除文件或空目录。目录非空时需设置 recursive=true。",
            "Delete a file or empty directory. Set recursive=true for non-empty directories.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "要删除的文件/目录绝对路径"
                "recursive" bool "是否递归删除目录（默认 false）"
            })
        }
    ) { _, a, _ ->
        val file = File(a.str("path"))
        if (!file.exists()) return@EngineToolHandler err("NOT_FOUND", "文件/目录不存在", "path", a.str("path"))
        if (file.isDirectory && !a.bool("recursive", false) && file.list()?.isNotEmpty() == true) {
            return@EngineToolHandler err("DIR_NOT_EMPTY", "目录非空, 设置 recursive=true 递归删除", "path", a.str("path"))
        }
        file.deleteRecursively()
        ok(JSONObject().put("deleted", true).put("path", a.str("path")))
    }

    // ── file_batch_rename ──
    val batchRename = EngineToolHandler(
        ToolMeta(
            "file_batch_rename",
            "【批量重命名】按替换规则或正则批量重命名目录中的文件。支持前后缀添加、文本替换、正则替换、序号填充。dryRun=true 预览结果不执行。",
            "Batch rename files in a directory. Supports prefix/suffix, text replacement, regex replacement, and sequence numbering. dryRun previews without executing.",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "目标目录绝对路径"
                "find" str "要替换的文本（mode=replace/regex 时使用）"
                "replace" str "替换为（mode=replace/regex, 支持 $1 正则捕获组）"
                "prefix" str "添加到文件名前的文本（mode=prefix）"
                "suffix" str "添加到文件名后的文本（在扩展名前, mode=suffix）"
                "mode".oneOf("重命名模式",
                    "replace(文本替换) | regex(正则替换) | prefix(加前缀) | suffix(加后缀) | number(序号)",
                    "replace", "regex", "prefix", "suffix", "number")
                "filter" str "文件名过滤（支持 * 通配符, 如 *.txt *.dex）"
                "startFrom" int "序号起始值（mode=number, 默认 1）"
                "padWidth" int "序号补零宽度（mode=number, 默认 2 即 01,02）"
                "nameFormat" str "序号命名格式（mode=number, 如 \"file_%d\" 或 \"%d\", 默认 \"%d\"）"
                "dryRun" bool "仅预览不实际执行（默认 true）"
                "caseSensitive" bool "文件名匹配是否大小写敏感（默认 false）"
            })
        }
    ) { _, a, _ ->
        val dir = File(a.str("path"))
        if (!dir.isDirectory) return@EngineToolHandler err("NOT_DIR", "路径不是目录", "path", a.str("path"))

        val mode = a.str("mode", "replace")
        val dryRun = a.bool("dryRun", true)

        var files = dir.listFiles()?.toList() ?: return@EngineToolHandler err("IO_ERROR", "无法读取目录", "path", a.str("path"))

        // Filter
        val filter = a.str("filter")
        if (filter.isNotBlank()) {
            val regex = filter.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                .toRegex(if (a.bool("caseSensitive", true)) setOf() else setOf(RegexOption.IGNORE_CASE))
            files = files.filter { !it.isDirectory && regex.matches(it.name) }
        } else {
            files = files.filter { !it.isDirectory }
        }

        val operations = JSONArray()
        val errors = JSONArray()

        files.forEachIndexed { idx, f ->
            val oldName = f.name
            val dotIdx = oldName.lastIndexOf('.')
            val baseName = if (dotIdx > 0) oldName.substring(0, dotIdx) else oldName
            val ext = if (dotIdx > 0) oldName.substring(dotIdx) else ""

            val newName = when (mode) {
                "prefix" -> {
                    val pfx = a.str("prefix")
                    "$pfx$oldName"
                }
                "suffix" -> {
                    val sfx = a.str("suffix")
                    "$baseName$sfx$ext"
                }
                "replace" -> {
                    val find = a.str("find")
                    val repl = a.str("replace")
                    if (find.isBlank()) oldName
                    else if (a.bool("caseSensitive", false)) oldName.replace(find, repl)
                    else oldName.replace(Regex(Regex.escape(find), RegexOption.IGNORE_CASE), repl)
                }
                "regex" -> {
                    val find = a.str("find")
                    val repl = a.str("replace")
                    if (find.isBlank()) oldName
                    else Regex(find).replace(oldName, repl)
                }
                "number" -> {
                    val start = a.intValue("startFrom", 1)
                    val pad = a.intValue("padWidth", 2).coerceIn(1, 10)
                    val fmt = a.str("nameFormat", "%d")
                    val num = if (fmt == "%d") String.format("%%0%dd".format(pad), start + idx)
                    else String.format(fmt, start + idx)
                    "$num$ext"
                }
                else -> oldName
            }

            if (newName != oldName && newName.isNotBlank()) {
                val newFile = File(dir, newName)
                val op = JSONObject()
                    .put("oldName", oldName)
                    .put("newName", newName)
                    .put("oldPath", f.absolutePath)
                    .put("newPath", newFile.absolutePath)
                if (!dryRun) {
                    if (newFile.exists()) {
                        errors.put("跳过: $newName 已存在")
                        op.put("skipped", true)
                    } else {
                        f.renameTo(newFile)
                        op.put("done", true)
                    }
                }
                operations.put(op)
            }
        }

        ok(JSONObject()
            .put("mode", mode)
            .put("dryRun", dryRun)
            .put("totalFiles", files.size)
            .put("toRename", operations.length())
            .put("operations", operations)
            .apply { if (errors.length() > 0) put("errors", errors) })
    }
}
