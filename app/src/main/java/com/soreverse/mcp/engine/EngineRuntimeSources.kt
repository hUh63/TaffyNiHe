package com.soreverse.mcp.engine

import android.net.Uri
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.nativecore.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal fun EngineRuntime.setWorkDirectory(uri: Uri) {
    if (workDirUri == uri && workDir != null) return
    workDirUri = uri
    workDir = WorkDirectory(context, uri)
    sources = emptyList()
    sourceFingerprint = emptyList()
    sourceSummaryCache.clear()
    workspaceBySourceKey.clear()
    pageStore.clear()
    searchCache.clear()
    AppLog.i("Work directory selected: ${WorkDirectory.displayPath(uri)}")
}

internal fun EngineRuntime.listAvailableSos(prefix: String = "", limit: Int = 50, cursor: String = ""): JSONObject = guarded {
    val dir = workDir ?: return@guarded err("SO_NOT_FOUND", "No work directory selected")
    val currentSources = ensureSources(dir)
    val boundedLimit = limit.coerceIn(1, 500)
    val start = cursor.removePrefix("source:").toIntOrNull()?.coerceAtLeast(0) ?: 0
    val filtered = currentSources.filter { prefix.isBlank() || it.path.startsWith(prefix) || it.name.startsWith(prefix) }
    val items = JSONArray()
    filtered.asSequence()
        .drop(start)
        .take(boundedLimit)
        .forEach { src ->
            items.put(JSONObject()
                .put("path", src.path)
                .put("filePath", src.path)
                .put("openPath", src.path)
                .put("source", src.source)
                .put("apkPath", src.apkPath)
                .put("apkEntry", src.apkEntry)
                .put("abi", src.abi ?: "")
                .put("size", src.size)
                .put("modified", src.modified)
                .put("architecture", JSONObject.NULL)
                .put("bits", 0)
                .put("endian", JSONObject.NULL)
                .put("soname", JSONObject.NULL)
                .put("hasDebugInfo", JSONObject.NULL)
                .put("stripped", JSONObject.NULL))
        }
    val nextOffset = start + items.length()
    val nextCursor = if (nextOffset < filtered.size) "source:$nextOffset" else null
    ok(JSONObject()
        .put("items", items)
        .put("usage", "Call so_open with path or filePath from any item. Use the returned workspaceId for the other tools.")
        .put("pagination", pagination(nextCursor != null, nextCursor, items.length(), boundedLimit, filtered.size)))
}

internal fun EngineRuntime.open(path: String, temporary: Boolean): JSONObject = guarded {
    if (path.isBlank()) return@guarded err("INVALID_ARGUMENT", "Missing SO path. Pass path or filePath from so_open (action=list).", "path", path)
    // 处理 content:// URI（SAF 文件选择器返回）：将文件复制到应用缓存目录后以本地路径打开。
    val effectivePath = if (path.startsWith("content://")) {
        try {
            val uri = Uri.parse(path)
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
                ?: "temp_${System.currentTimeMillis()}.so"
            val safeName = if (fileName.endsWith(".so", ignoreCase = true)) fileName else "$fileName.so"
            val tempFile = File(context.cacheDir, "picked_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return@guarded err("SO_NOT_FOUND", "Cannot open content URI: $path", "path", path)
            tempFile.absolutePath
        } catch (e: Exception) {
            AppLog.e("Failed to copy content URI: ${e.message}")
            return@guarded err("SO_NOT_FOUND", "Failed to read file from content URI: ${e.message}", "path", path)
        }
    } else {
        path
    }
    // 安全修复: 添加路径穿越校验，防止通过 ../ 访问设备上的任意文件。
    // 仅对本地文件路径（不含 ! 形式的 APK 条目路径）进行校验。
    val localPath = effectivePath.substringBefore('!')
    if (localPath.startsWith("/")) {
        val pathError = com.soreverse.mcp.core.PathSafety.validate(localPath, null)
        if (pathError != null) {
            return@guarded err("PATH_UNSAFE", "Path validation failed: $pathError", "path", path)
        }
    }
    val ws = openWorkspace(effectivePath, temporary)
    val elf = ws.elf
    val src = ws.source
    val symbolFunctions = (elf.symbols + elf.dynSymbols).filter { it.type == "FUNC" && !it.imported }.distinctBy { it.name to it.value }
    val exportedFunctions = elf.dynSymbols.filter { it.type == "FUNC" && !it.imported && it.value > 0 }.distinctBy { it.name to it.value }
    val analyzedFunctions = if (NativeEngine.active().available()) runCatching { JSONArray(NativeEngine.active().functions(ws.data, elf.architecture)).length() }.getOrDefault(symbolFunctions.size) else symbolFunctions.size
    val pltStubs = elf.relocations.count { it.section.contains("plt", true) }
    ok(JSONObject()
        .put("workspaceId", ws.id)
        .put("temporary", temporary)
        .put("soFileName", src.name)
        .put("source", src.source)
        .put("inputPath", src.path)
        .put("apkPath", src.apkPath)
        .put("apkEntry", src.apkEntry)
        .put("abi", src.abi)
        .put("architecture", elf.architecture)
        .put("bits", elf.bits)
        .put("endian", elf.endian)
        .put("elfType", "ET_${elf.type}")
        .put("machine", elf.machineName)
        .put("entryPoint", hex(elf.entry))
        .put("analysisInput", JSONObject().put("source", ws.analysisInputSource).put("originalSha256", ws.originalSha256).put("analysisSha256", sha256(ws.data)).put("structureRecovery", ws.structureRecovery))
        .put("counts", JSONObject().put("sections", elf.sections.size).put("symbols", elf.symbols.size).put("dynsyms", elf.dynSymbols.size).put("relocations", elf.relocations.size).put("functions", symbolFunctions.size).put("functionsMeaning", "symbolFunctions").put("symbolFunctions", symbolFunctions.size).put("exportedFunctions", exportedFunctions.size).put("analyzedFunctions", analyzedFunctions).put("pltStubs", pltStubs).put("strings", elf.strings.size))
        .put("capabilities", JSONObject().put("canDisassemble", true).put("canEditAsm", true).put("canEditHex", true).put("canResolveRelocs", elf.relocations.isNotEmpty()).put("hasPltGot", elf.sections.any { it.name in setOf(".plt", ".got") }).put("canSearchStrings", elf.strings.isNotEmpty()).put("hasDebugInfo", elf.sections.any { it.name.startsWith(".debug") }).put("hasEhFrame", elf.sections.any { it.name in setOf(".eh_frame", ".ARM.exidx") }))
        .put("checksums", checksums(ws.data)))
}

internal fun EngineRuntime.analyzeApk(path: String, entryLimit: Int = 500): JSONObject = guarded {
    if (path.isBlank()) return@guarded err("INVALID_ARGUMENT", "APK path is required", "path", path)
    val local = File(path)
    // 上游 1.0.18 借鉴: 禁止分析自身 Artifact（签名匹配 → 拦截，防误操作/自我分析）
    if (local.isFile && com.soreverse.mcp.nativecore.SignatureVerifier.isSelfSignedApk(local.absolutePath)) {
        return@guarded err("SELF_ANALYSIS_FORBIDDEN", "塔菲逆核不能分析自身 APK（签名匹配），请选择其他 APK", "path", path)
    }
    if (local.isFile && local.length() > ApkAnalyzer.MAX_INPUT_BYTES) return@guarded err("APK_LIMIT_EXCEEDED", "APK exceeds ${ApkAnalyzer.MAX_INPUT_BYTES / 1024 / 1024} MiB input limit", "path", path)
    // 上游 1.0.20 借鉴: MemoryGuard——APK 读入前估算堆余量
    if (local.isFile) {
        com.soreverse.mcp.core.MemoryGuard.ensureAnalysisMemory(local.length(), "apk_analyze(${local.name})")
    }
    val bytes = try {
        if (local.isFile) local.readBytes() else (workDir ?: return@guarded err("WORK_DIRECTORY_NOT_SELECTED", "APK path is not a local file and no work directory is selected", "path", path)).readFile(path, ApkAnalyzer.MAX_INPUT_BYTES)
    } catch (error: ApkAnalysisLimitException) {
        return@guarded err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
    }
    if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return@guarded err("APK_INVALID", "Input is not a ZIP/APK file", "path", path)
    try {
        ok(ApkAnalyzer.analyze(bytes, path, entryLimit))
    } catch (error: ApkAnalysisLimitException) {
        err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
    }
}

internal fun EngineRuntime.openUrl(url: String, outputName: String = "", temporary: Boolean = false): JSONObject = guarded {
    val dir = workDir ?: return@guarded err("WORK_DIRECTORY_NOT_SELECTED", "A work directory must be selected before downloading a SO URL")
    val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return@guarded err("INVALID_ARGUMENT", "url must be a valid http(s) URL", "url", url)
    if (parsed.protocol !in setOf("http", "https")) return@guarded err("UNSUPPORTED_URL_SCHEME", "Only http and https URLs are supported", "url", url)
    val timeout = SettingsStore(context).requestTimeoutMs
    val conn = (parsed.openConnection() as HttpURLConnection).apply { connectTimeout = timeout.coerceAtMost(30_000); readTimeout = timeout; instanceFollowRedirects = true; requestMethod = "GET" }
    val status = conn.responseCode
    if (status !in 200..299) return@guarded err("DOWNLOAD_FAILED", "HTTP download failed with status $status", "url", url)
    // 上游 1.0.18 借鉴: 下载上限按进程堆内存与存储空间动态调整（而非固定 256MiB）
    val maxBytes = soDownloadMaxBytes()
    val maxMiB = maxBytes / (1024L * 1024L)
    if (conn.contentLengthLong > maxBytes) return@guarded err("DOWNLOAD_TOO_LARGE", "SO download exceeds $maxMiB MiB dynamic limit (heap/storage)", "contentLength", conn.contentLengthLong)
    val bytes = conn.inputStream.use { input -> java.io.ByteArrayOutputStream().apply { val buf = ByteArray(64 * 1024); var total = 0L; while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > maxBytes) return@guarded err("DOWNLOAD_TOO_LARGE", "SO download exceeds $maxMiB MiB dynamic limit (heap/storage)", "url", url); write(buf, 0, n) } }.toByteArray() }
    if (bytes.size < 4 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return@guarded err("NOT_ELF_SO", "Downloaded file is not an ELF/SO file", "url", url)
    val rawName = outputName.ifBlank { parsed.path.substringAfterLast('/').substringBefore('?').ifBlank { "downloaded.so" } }
    val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').let { if (it.endsWith(".so", ignoreCase = true)) it else "$it.so" }
    val source = dir.writeRootFile(safeName, bytes)
    sources = (sources.filterNot { it.path == source.path } + source).sortedBy { it.path }
    sourceFingerprint = emptyList()
    sourceSummaryCache.clear()
    open(source.path, temporary).put("download", JSONObject().put("url", url).put("savedAs", source.path).put("size", bytes.size).put("sha256_16", sha256(bytes).take(16)))
}

internal fun EngineRuntime.listWorkspaces(): JSONObject = guarded {
    val items = JSONArray()
    workspaces.values.sortedBy { it.source.path }.forEach { ws -> items.put(JSONObject().put("workspaceId", ws.id).put("path", ws.source.path).put("filePath", ws.source.path).put("soFileName", ws.source.name).put("source", ws.source.source).put("apkPath", ws.source.apkPath).put("apkEntry", ws.source.apkEntry).put("abi", ws.source.abi).put("architecture", ws.elf.architecture).put("bits", ws.elf.bits).put("temporary", ws.temporary)) }
    ok(JSONObject().put("items", items).put("count", items.length()))
}

internal fun EngineRuntime.close(workspaceId: String): JSONObject = guarded {
    workspaces.remove(workspaceId)
    pageStore.clear()
    searchCache.clear()
    AppLog.i("Closed $workspaceId")
    ok(JSONObject().put("success", true))
}

internal fun EngineRuntime.clearCaches() {
    emulatorSessions.values.forEach { session -> session.live?.let(unidbg::closeSession) }
    emulatorSessions.clear()
    workspaces.clear()
    sources = emptyList()
    sourceFingerprint = emptyList()
    sourceSummaryCache.clear()
    workspaceBySourceKey.clear()
    pageStore.clear()
    searchCache.clear()
    workDir?.clearPersistentCache()
    AppLog.i("Index caches cleared")
}

internal fun EngineRuntime.openWorkspace(path: String, temporary: Boolean): Workspace {
    val archiveEntry = path.substringAfterLast('!', "")
    if (archiveEntry.isNotBlank() && !archiveEntry.endsWith(".so", ignoreCase = true)) error("NOT_ELF_INPUT: $path is an APK/JAR entry, not an ELF SO file. Use apk_analyze or an APK MCP tool.")
    val keyFallback = "local:$path"
    val src = findSource(path) ?: resolveLocalSoSource(path) ?: error("SO path not found: $path")
    // 上游 1.0.18 借鉴: 禁止分析自身 Artifact（包名/签名匹配 → 拦截，防误操作/自我分析）
    if (src.source == "apk" && src.apkPath != null && com.soreverse.mcp.nativecore.SignatureVerifier.isSelfSignedApk(src.apkPath)) {
        error("SELF_ANALYSIS_FORBIDDEN: 塔菲逆核不能分析自身 APK 内嵌的 SO ${src.apkPath}")
    }
    val key = sourceKey(src).ifBlank { keyFallback }
    workspaceBySourceKey[key]?.let { existingId -> workspaces[existingId]?.let { return it } }
    // 上游 1.0.20 借鉴: MemoryGuard——本地文件读取前估算堆余量, 不足提前拒绝而非 OOM 崩溃
    if (src.source == "build_output" || src.source == "local_file") {
        runCatching { File(src.path).length() }.getOrDefault(0L).takeIf { it > 0 }?.let { fileSize ->
            com.soreverse.mcp.core.MemoryGuard.ensureAnalysisMemory(fileSize, "so_open(${src.name})")
        }
    }
    val original = when (src.source) { "build_output", "local_file" -> runCatching { File(src.path).readBytes() }.getOrElse { error("SO path not found: $path") }; else -> (workDir ?: error("No work directory selected")).readSource(src) }
    require(original.size >= 4 && original[0] == 0x7f.toByte() && original[1] == 'E'.code.toByte() && original[2] == 'L'.code.toByte() && original[3] == 'F'.code.toByte()) { "NOT_ELF_INPUT: ${src.path} is not an ELF SO file. Use apk_analyze or an APK MCP tool." }
    val prepared = prepareAnalysisInput(original)
    val ws = Workspace("so-ws-${UUID.randomUUID()}", src, prepared.data, prepared.elf, temporary, sha256(original), prepared.source, prepared.facts)
    workspaces[ws.id] = ws
    workspaceBySourceKey[key] = ws.id
    AppLog.i("Opened ${src.path} as ${ws.id}")
    return ws
}

internal data class AnalysisInput(
    val data: ByteArray,
    val source: String,
    val facts: JSONObject,
    val elf: ElfFile,
)

internal fun EngineRuntime.prepareAnalysisInput(original: ByteArray): AnalysisInput {
    val before = lief.parse(original)
    val facts = JSONObject().put("attempted", false).put("changed", false).put("sectionsBefore", before.sections.size).put("programHeadersBefore", before.programHeaders.size).put("symbolsBefore", before.symbols.size).put("dynSymbolsBefore", before.dynSymbols.size).put("functionSymbolsRecovered", false)
    if (before.sections.isNotEmpty()) return AnalysisInput(original, "original", facts.put("reason", "section_table_present"), before)
    if (original.size < 5 || !xanso.available()) return AnalysisInput(original, "original", facts.put("reason", if (original.size < 5) "invalid_elf_ident" else "xanso_unavailable"), before)
    facts.put("attempted", true)
    val recovered = when (original[4].toInt() and 0xff) { 1 -> xanso.buildSections(original); 2 -> xanso.recoverElf64Sections(original)?.let { lief.fixSections(it) }; else -> null }
    if (recovered == null || recovered.isEmpty()) return AnalysisInput(original, "original", facts.put("reason", "xanso_recovery_failed"), before)
    val after = lief.parse(recovered)
    if (after.sections.isEmpty()) return AnalysisInput(original, "original", facts.put("reason", "recovered_section_table_not_parseable"), before)
    facts.put("changed", !recovered.contentEquals(original)).put("reason", "missing_section_table").put("recoveryMode", if ((original[4].toInt() and 0xff) == 1) "xanso32_section_fix" else "xanso64_section_recovery_lief_finalize").put("sectionsAfter", after.sections.size).put("programHeadersAfter", after.programHeaders.size).put("symbolsAfter", after.symbols.size).put("dynSymbolsAfter", after.dynSymbols.size).put("functionSymbolsRecovered", after.symbols.count { it.type == "FUNC" } > before.symbols.count { it.type == "FUNC" })
    return AnalysisInput(recovered, "xanso_recovered_sections", facts, after)
}

internal fun EngineRuntime.resolveLocalSoSource(rawPath: String): SoSource? {
    if (rawPath.isBlank()) return null
    val file = File(rawPath)
    if (!file.exists() || !file.isFile) return null
    val extDir = context.getExternalFilesDir(null)?.canonicalPath
    val intDir = context.filesDir.canonicalPath
    val cacheDir = context.cacheDir.canonicalPath
    val canonical = runCatching { file.canonicalPath }.getOrDefault(rawPath)
    if (listOfNotNull(extDir, intDir, cacheDir).none { canonical.startsWith(it) } || !file.name.endsWith(".so", ignoreCase = true)) return null
    return SoSource(canonical, "build_output", file.name, file.length(), file.lastModified(), null)
}

internal fun EngineRuntime.findSource(rawPath: String): SoSource? {
    if (rawPath.isBlank()) return null
    val path = rawPath.trim().removePrefix("/")
    workDir?.let { ensureSources(it) }
    val apkUri = rawPath.trim().removePrefix("content://apk/")
    if (apkUri != rawPath.trim() && apkUri.isNotBlank()) {
        val separator = apkUri.indexOf('/')
        if (separator > 0) sources.firstOrNull { it.source == "apk" && it.apkPath?.substringAfterLast('/') == apkUri.substring(0, separator) && it.apkEntry == apkUri.substring(separator + 1) }?.let { return it }
    }
    return sources.firstOrNull { it.path == rawPath || it.path == path } ?: sources.firstOrNull { it.name == rawPath || it.name == path } ?: sources.firstOrNull { it.apkEntry == rawPath || it.apkEntry == path } ?: sources.firstOrNull { it.path.endsWith("/$path") || it.path.contains(path) }
}

internal fun EngineRuntime.ensureSources(dir: WorkDirectory): List<SoSource> {
    val settings = SettingsStore(context)
    val options = scanOptions(settings)
    if (!settings.indexCacheEnabled) {
        sources = dir.listSos(options)
        sourceFingerprint = sources.map { FileFingerprint(it.path, it.size, it.modified) }
        return sources
    }
    // Single-pass scan: get sources AND fingerprint in one directory walk
    val (scanned, fingerprint) = dir.listSosWithFingerprint(options)
    if (sources.isNotEmpty() && fingerprint == sourceFingerprint) return sources
    sources = scanned
    sourceFingerprint = fingerprint
    pageStore.clear()
    AppLog.i("Scanned ${sources.size} SO entries")
    return sources
}

internal fun EngineRuntime.scanOptions(settings: SettingsStore): ScanOptions = ScanOptions(settings.scanApks, settings.scanSubdirectories, settings.maxScanDepth, settings.skipFilesLargerThanMb.toLong() * 1024L * 1024L)

internal fun EngineRuntime.sourceSummary(dir: WorkDirectory, src: SoSource): SourceSummary {
    if (!SettingsStore(context).parseMetadataInList) return SourceSummary("unknown", 0, "little", false, false)
    return sourceSummaryCache.getOrPut(sourceKey(src)) {
        dir.cachedSummary(src)?.let { return@getOrPut SourceSummary(it.architecture, it.bits, it.endian, it.hasDebugInfo, it.stripped) }

        // Fast path: for filesystem SOs, parse ELF header via seeking (reads only ~few KB
        // instead of the full SO file, which can be tens of MB for libapp.so)
        if (src.source == "filesystem" && src.treeDocumentUri != null) {
            dir.readElfSummary(src.treeDocumentUri)?.let { summary ->
                if (summary.architecture != "unknown") {
                    dir.putCachedSummary(src, CachedSourceSummary(summary.architecture, summary.bits, summary.endian, summary.hasDebugInfo, summary.stripped))
                    return@getOrPut summary
                }
            }
        }

        // Fallback: read full file and parse with LIEF
        runCatching { lief.parse(dir.readSource(src)).let { elf -> SourceSummary(elf.architecture, elf.bits, elf.endian, elf.sections.any { it.name.startsWith(".debug") }, elf.symbols.isEmpty()).also { dir.putCachedSummary(src, CachedSourceSummary(it.architecture, it.bits, it.endian, it.hasDebugInfo, it.stripped)) } } }.getOrElse { SourceSummary("unknown", 0, "little", false, true) }
    }
}

internal fun EngineRuntime.sourceKey(src: SoSource): String = "${src.path}|${src.size}|${src.modified}"

/**
 * 上游 1.0.18 借鉴: 单个 SO 下载的动态上限，由进程最大堆内存与工作目录剩余空间推导。
 * 下载的 ELF 随后整体读入内存（另有 LIEF/xanso 解析副本），大内存设备上限随之放大，
 * 低内存设备优雅降级，避免 OutOfMemoryError 崩溃。
 */
internal fun EngineRuntime.soDownloadMaxBytes(): Long {
    val heapMaxMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
    // 解析约需一半堆余量；用 ~50% 最大堆做安全上限
    val heapCapMiB = (heapMaxMiB * 5) / 10
    // 应用私有目录所在存储的剩余空间（SAF 工作目录无路径，用 filesDir 所在分区估算）
    val storageFreeMiB = runCatching {
        val free = android.os.StatFs(context.filesDir.absolutePath).availableBytes / (1024L * 1024L)
        (free - 16L).coerceAtLeast(0L) // 磁盘保留余量
    }.getOrDefault(heapCapMiB)
    val capMiB = minOf(heapCapMiB, storageFreeMiB).coerceIn(64L, 2048L)
    return capMiB * 1024L * 1024L
}
