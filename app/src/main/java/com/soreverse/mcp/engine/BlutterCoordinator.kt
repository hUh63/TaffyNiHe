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

        val hits = JSONArray()
        var scanned = 0
        // 扫描 ASCII 字符串与 UTF-16LE 字符串
        val ascii = extractAscii(libapp, minLen)
        val utf16 = extractUtf16(libapp, minLen)
        val all = LinkedHashSet<String>()
        all.addAll(ascii); all.addAll(utf16)
        for (s in all) {
            if (keyword.isNotBlank() && !s.lowercase().contains(keyword)) continue
            if (scanned >= limit) break
            hits.put(JSONObject().put("value", s))
            scanned++
        }

        return ok(JSONObject()
            .put("action", "strings")
            .put("library", path)
            .put("matched", scanned)
            .put("totalUnique", all.size)
            .put("hits", hits)
            .put("hint", "扫描到 libapp.so 中的可读字符串(类名/函数名/URL/文案)。配合 taffy_flutter_blutter action=analyze 反编译定位逻辑"))
    }

    private fun extractAscii(data: ByteArray, minLen: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            val printable = c in 0x20..0x7E
            if (printable) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= minLen) out.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.length >= minLen) out.add(cur.toString())
        return out
    }

    private fun extractUtf16(data: ByteArray, minLen: Int): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val n = data.size - 1
        val cur = StringBuilder()
        while (i < n) {
            val low = data[i].toInt() and 0xFF
            val high = data[i + 1].toInt() and 0xFF // LE: 低位在前; high 通常 0(ASCII), 否则需 utf8
            val code = (high shl 8) or low
            if (high == 0 && low in 0x20..0x7E) {
                cur.append(low.toChar())
            } else {
                if (cur.length >= minLen) out.add(cur.toString())
                cur.setLength(0)
            }
            i += 2
        }
        if (cur.length >= minLen) out.add(cur.toString())
        return out
    }
}
