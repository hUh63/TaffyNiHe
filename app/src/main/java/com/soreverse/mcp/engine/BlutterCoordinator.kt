package com.soreverse.mcp.engine

import android.content.Context
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

internal class BlutterCoordinator(private val context: Context, private val store: BlutterResultStore = BlutterResultStore(context), private val registry: BlutterRunnerRegistry = BlutterRunnerRegistry(context)) {
    private val embedded = BlutterEmbeddedBackend(context, store)
    fun handle(args: JSONObject, workDirectory: WorkDirectory? = null): JSONObject = when (args.str("action", "inspect")) {
        "inspect" -> inspect(args, workDirectory)
        "analyze" -> analyze(args, workDirectory)
        "status" -> status(args.str("jobId"))
        "result" -> result(args.str("jobId"), args.optString("kind").takeIf { it.isNotBlank() }, args.optString("cursor").takeIf { it.isNotBlank() }, args.optInt("limit", 1000))
        "cancel" -> cancel(args.str("jobId"))
        "packages" -> ok(registry.capabilities())
        "prune" -> ok(store.prune(args.optLong("olderThanMillis", 7L * 24 * 60 * 60 * 1000)))
        "strings" -> scanStrings(args)
        else -> err("UNKNOWN_ACTION", "Unsupported flutter_blutter action", "action", args.str("action"))
    }

    private fun inspect(args: JSONObject, workDirectory: WorkDirectory?): JSONObject {
        val path = args.str("path")
        if (path.isBlank()) return err("INPUT_REQUIRED", "path is required for inspect", "path", path)
        val file = File(path)
        return try {
            if (file.isDirectory) inspectDirectory(file, path, args.str("abi", "auto")) else if (file.isFile) {
                // 上游 1.0.20 借鉴: MemoryGuard——APK 读入前估算堆余量
                com.soreverse.mcp.core.MemoryGuard.ensureAnalysisMemory(file.length(), "flutter_inspect(${file.name})")
                val bytes = file.readBytes()
                if (!file.extension.equals("apk", true)) return err("UNSUPPORTED_INPUT", "inspect currently accepts an APK or a libapp/libflutter directory", "path", path)
                val inventory = FlutterArtifactInspector.inspectApk(bytes, path, args.str("abi", "auto"))
                val selected = inventory.optJSONObject("selected")
                if (selected == null) ok(inventory) else {
                    val detailed = FlutterArtifactInspector.inspectLibraries(FlutterArtifactInspector.extractLibraries(bytes, path, args.str("abi", "auto")))
                    ok(inventory.put("selectedAnalysis", detailed))
                }
            } else if (workDirectory != null) {
                val bytes = workDirectory.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES)
                val inventory = FlutterArtifactInspector.inspectApk(bytes, path, args.str("abi", "auto"))
                val selected = inventory.optJSONObject("selected")
                if (selected == null) ok(inventory) else ok(inventory.put("selectedAnalysis", FlutterArtifactInspector.inspectLibraries(FlutterArtifactInspector.extractLibraries(bytes, path, args.str("abi", "auto")))))
            } else err("INPUT_NOT_FOUND", "Input path does not exist and no work directory is selected", "path", path)
        } catch (error: com.soreverse.mcp.core.InsufficientMemoryException) {
            err("INSUFFICIENT_MEMORY", error.message ?: "heap headroom too low", "path", path)
        } catch (error: Exception) { err(error.message?.substringBefore(':')?.takeIf { it in setOf("INPUT_LIMIT_EXCEEDED", "APK_INVALID", "UNSUPPORTED_ELF") } ?: "FLUTTER_INSPECT_FAILED", error.message ?: "Flutter inspection failed", "path", path) }
    }

    private fun analyze(args: JSONObject, workDirectory: WorkDirectory?): JSONObject {
        val inspection = inspect(args, workDirectory)
        if (!inspection.optBoolean("ok", false)) return inspection
        val jobId = store.create(args)
        val analysis = inspection.optJSONObject("selectedAnalysis") ?: inspection
        val flutter = analysis.optJSONObject("flutter") ?: JSONObject()
        val requirement = BlutterRunnerRequirement(
            engineRevision = flutter.optJSONArray("engineIds")?.optString(0)?.takeIf { it.isNotBlank() },
            dartVersion = flutter.optString("dartVersion").takeIf { it.isNotBlank() },
            abi = analysis.optString("abi", args.str("abi", "arm64-v8a")),
            compressedPointers = flutter.optBoolean("compressedPointers", false),
            analysis = !args.optBoolean("noAnalysis", false),
        )
        val runner = registry.select(requirement)
        if (runner != null) {
            return runCatching {
                val libraries = resolveLibraries(args, workDirectory)
                // 上游 1.0.18 借鉴: Flutter 分析缓存 —— 相同输入(libapp/libflutter/runner/options)直接复用上次结果
                val cacheKey = blutterCacheKey(libraries.libapp, libraries.libflutter, runner.sha256, args.toString())
                store.lookup(cacheKey)?.let { cached ->
                    store.update(jobId, "succeeded", "cache_lookup", resultKey = cacheKey)
                    return@runCatching ok(JSONObject()
                        .put("jobId", jobId).put("status", "succeeded").put("backend", "cache")
                        .put("cacheHit", true).put("runner", runner.toJson())
                        .put("message", "Reused identical previous analysis result (cache hit)"))
                }
                embedded.start(jobId, runner, libraries, args)
                ok(JSONObject().put("jobId", jobId).put("status", "running").put("backend", "embedded").put("runner", runner.toJson()))
            }.getOrElse { error ->
                val problem = JSONObject().put("code", "INPUT_RESOLUTION_FAILED").put("message", error.message ?: "Cannot resolve Flutter libraries").put("recoverable", false).put("stage", "resolving_input")
                store.update(jobId, "failed", "resolving_input", problem)
                ok(JSONObject().put("jobId", jobId).put("status", "failed").put("error", problem))
            }
        }
        val required = JSONObject().put("engineRevision", requirement.engineRevision ?: JSONObject.NULL).put("dartVersion", requirement.dartVersion ?: JSONObject.NULL).put("abi", requirement.abi).put("compressedPointers", requirement.compressedPointers).put("analysis", requirement.analysis)
        val error = JSONObject().put("code", "FLUTTER_VERSION_NOT_SUPPORTED").put("message", "This release embeds only the Flutter 3.44.x / Dart 3.12.2 arm64-v8a Blutter runner. The target APK does not match that exact snapshot compatibility key.").put("recoverable", false).put("stage", "runner_selection").put("supportedFlutter", "3.44.x").put("supportedDart", "3.12.2").put("required", required)
        store.update(jobId, "failed", "runner_selection", error)
        return ok(JSONObject().put("jobId", jobId).put("status", "failed").put("inspection", inspection).put("requiredRunner", required).put("error", error).put("nextActions", JSONArray().put("use a Flutter 3.44.x APK built with Dart 3.12.2").put("inspect the APK fingerprint without running analysis")))
    }

    private fun status(jobId: String): JSONObject = store.get(jobId)?.let { ok(it) } ?: err("JOB_NOT_FOUND", "Blutter job was not found", "jobId", jobId)
    private fun result(jobId: String, kind: String?, cursor: String?, limit: Int): JSONObject = runCatching { store.result(jobId, kind, cursor, limit)?.let { ok(it) } ?: err("RESULT_NOT_FOUND", "Blutter result is not available", "jobId", jobId) }.getOrElse { err("INVALID_RESULT_REQUEST", it.message ?: "Invalid result request", "jobId", jobId) }
    private fun cancel(jobId: String): JSONObject {
        embedded.cancel(jobId)
        return if (store.cancel(jobId)) ok(JSONObject().put("jobId", jobId).put("status", "cancelled")) else err("JOB_NOT_CANCELLABLE", "Job was not found or already finished", "jobId", jobId)
    }

    private fun resolveLibraries(args: JSONObject, workDirectory: WorkDirectory?): FlutterLibraries {
        val path = args.str("path")
        val file = File(path)
        if (file.isDirectory) {
            val app = file.resolve("libapp.so").takeIf { it.isFile } ?: file.resolve("App").takeIf { it.isFile } ?: error("FLUTTER_LIBS_NOT_FOUND")
            val flutter = file.resolve("libflutter.so").takeIf { it.isFile } ?: file.resolve("Flutter").takeIf { it.isFile } ?: error("FLUTTER_LIBS_NOT_FOUND")
            return FlutterLibraries(file.name, "arm64-v8a", app.readBytes(), flutter.readBytes(), app.name, flutter.name)
        }
        val bytes = if (file.isFile) file.readBytes() else workDirectory?.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES) ?: error("INPUT_NOT_FOUND")
        return FlutterArtifactInspector.extractLibraries(bytes, path, args.str("abi", "arm64-v8a"))
    }

    private fun inspectDirectory(dir: File, path: String, requestedAbi: String): JSONObject {
        val app = dir.resolve("libapp.so").takeIf { it.isFile } ?: dir.resolve("App").takeIf { it.isFile }
        val flutter = dir.resolve("libflutter.so").takeIf { it.isFile } ?: dir.resolve("Flutter").takeIf { it.isFile }
        if (app == null || flutter == null) return err("FLUTTER_LIBS_NOT_FOUND", "Directory must contain libapp.so and libflutter.so", "path", path)
        val abi = if (requestedAbi == "auto") "arm64-v8a" else requestedAbi
        return ok(FlutterArtifactInspector.inspectLibraries(FlutterLibraries(dir.name, abi, app.readBytes(), flutter.readBytes(), app.name, flutter.name)))
    }

    /**
     * 扫描 Flutter 快照(libapp.so)里的可读字符串(ASCII + UTF-16LE)。
     * Dart AOT 的类/函数名、URL、文案、接口路径等通常以可读字符串形式存在于 ROData 段, 这是 Flutter 逆向第一抓手。
     */
    private fun scanStrings(args: JSONObject): JSONObject {
        val path = args.str("path")
        if (path.isBlank()) return err("INPUT_REQUIRED", "path is required for strings", "path", path)
        val file = File(path)
        val keyword = args.optString("keyword", "").trim().lowercase()
        val minLen = args.optInt("minLength", 4).coerceIn(2, 48)
        val limit = args.optInt("limit", 500).coerceIn(1, 5000)

        val libapp: ByteArray = try {
            when {
                file.isDirectory -> {
                    (file.resolve("libapp.so").takeIf { it.isFile } ?: file.resolve("App").takeIf { it.isFile })
                        ?.let { it.readBytes() }
                        ?: return err("FLUTTER_LIBS_NOT_FOUND", "Directory must contain libapp.so", "path", path)
                }
                file.isFile -> {
                    val bytes = file.readBytes()
                    if (!file.extension.equals("apk", true)) {
                        // 直接是 so 文件? 可能是 libapp.so 本身
                        if (file.name.contains("libapp") || file.extension.equals("so", true)) bytes
                        else return err("UNSUPPORTED_INPUT", "strings accepts an APK or a libapp/libflutter directory", "path", path)
                    } else {
                        FlutterArtifactInspector.extractLibraries(bytes, path, args.str("abi", "auto")).libapp
                    }
                }
                else -> return err("INPUT_NOT_FOUND", "Input path does not exist", "path", path)
            }
        } catch (error: Exception) {
            return err("STRING_SCAN_FAILED", "读取 libapp.so 失败: ${error.message ?: error.javaClass.simpleName}", "path", path)
        }

        // 边扫边过滤、边计数、到达 limit 提前终止, 避免对大快照全量构建字符串集合(内存/时间开销)。
        val hits = JSONArray()
        val scanned = scanStrings(libapp, keyword, minLen, limit) { hits.put(JSONObject().put("value", it)) }
        val truncated = scanned >= limit

        return ok(JSONObject()
            .put("action", "strings")
            .put("library", path)
            .put("matched", scanned)
            .put("truncated", truncated)
            .put("hits", hits)
            .put("hint", "扫描到 libapp.so 中的可读字符串(类名/函数名/URL/文案)。配合 taffy_flutter_blutter action=analyze 反编译定位逻辑"))
    }

    /**
     * 流式扫描 libapp 可读字符串(ASCII + UTF-16LE), 命中 keyword(可选)且去重后写入 [emit],
     * 达到 [limit] 提前终止。返回实际收集到的字符串数。
     */
    private fun scanStrings(data: ByteArray, keyword: String, minLen: Int, limit: Int, emit: (String) -> Unit): Int {
        val seen = LinkedHashSet<String>()
        val n = data.size
        var i = 0
        var count = 0
        while (i < n && count < limit) {
            // 先尝试 ASCII 片段
            if (data[i].toInt() and 0xFF in 0x20..0x7E) {
                val start = i
                while (i < n && data[i].toInt() and 0xFF in 0x20..0x7E) i++
                if (i - start >= minLen) {
                    val s = String(data, start, i - start, Charsets.US_ASCII)
                    if ((keyword.isBlank() || s.lowercase().contains(keyword)) && seen.add(s)) { emit(s); count++ }
                }
            } else if (i + 1 < n) {
                // 尝试 UTF-16LE 片段(一次取两个字节)
                val hi = data[i + 1].toInt() and 0xFF
                val lo = data[i].toInt() and 0xFF
                val start = i
                if (lo in 0x20..0x7E && hi == 0) { i += 2; continue } // 单字节可打印, ascii 分支已处理
                while (i + 1 < n) {
                    val h = data[i + 1].toInt() and 0xFF
                    val l = data[i].toInt() and 0xFF
                    val cp = (h shl 8) or l
                    if (isUtf16Char(cp)) i += 2 else break
                }
                if (i - start >= minLen * 2 && i - start >= 2) {
                    val s = String(data, start, i - start, Charsets.UTF_16LE)
                    if ((keyword.isBlank() || s.lowercase().contains(keyword)) && seen.add(s)) { emit(s); count++ }
                }
                if (i == start) i += 2 // 防死循环: 未消费任何字节则前进 2
            } else i++
        }
        return count
    }

    /** 判断是否为可作为 UTF-16LE 字符串内容的合法码元(白名单区间)。 */
    private fun isUtf16Char(cp: Int): Boolean = when {
        cp in 0x20..0x7E -> true // ASCII 可打印
        cp in 0x00A0..0x00FF -> true // 拉丁-1 补充(à/é/ñ/ö 等)
        cp in 0x2010..0x206F -> true // 常用标点/符号(– — ‘ ’ “ ” … ™)
        cp in 0x2E80..0x303F -> true // CJK 部首/注音/标点
        cp in 0x3040..0x30FF -> true // 日文假名
        cp in 0x3400..0x9FFF -> true // CJK 统一表意(含扩展 A)
        cp in 0xAC00..0xD7AF -> true // 韩文音节
        cp in 0xFF00..0xFFEF -> true // 全角形式
        cp in 0xD800..0xDFFF -> false // 孤立代理(需配对, 不单独当作字符串内容)
        else -> false
    }
}

/** 上游 1.0.18 借鉴: Flutter 分析缓存键 —— libapp+libflutter+runner+options 的 SHA-256 */
private fun blutterCacheKey(libapp: ByteArray, libflutter: ByteArray, runnerSha256: String, options: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(libapp)
    digest.update(libflutter)
    digest.update(runnerSha256.toByteArray())
    digest.update(options.toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }
}
