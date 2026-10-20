package com.soreverse.mcp.engine

import android.content.Context
import com.soreverse.mcp.core.ReadLimits
import com.soreverse.mcp.core.readTextCapped
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

internal class BlutterResultStore(context: Context) {
    companion object {
        private val JOB_ID = Regex("^blutter-[A-Za-z0-9_-]{8,128}$")
        private val PAGE_KINDS = setOf("libraries", "classes", "functions", "objects")
    }
    private val root = File(context.noBackupFilesDir, "blutter/v1").apply { mkdirs() }
    private val jobs = File(root, "jobs").apply { mkdirs() }
    private val results = File(root, "results").apply { mkdirs() }

    @Synchronized
    fun create(request: JSONObject): String {
        val id = "blutter-${UUID.randomUUID()}"
        val dir = File(jobs, id).apply { mkdirs() }
        write(File(dir, "state.json"), JSONObject().put("jobId", id).put("status", "queued").put("stage", "created").put("createdAt", System.currentTimeMillis()).put("updatedAt", System.currentTimeMillis()).put("request", request))
        return id
    }

    @Synchronized
    fun update(jobId: String, status: String, stage: String, error: JSONObject? = null, resultKey: String? = null) {
        requireValidJobId(jobId)
        val state = readState(jobId) ?: return
        state.put("status", status).put("stage", stage).put("updatedAt", System.currentTimeMillis())
        if (error != null) state.put("error", error) else state.remove("error")
        if (resultKey != null) state.put("resultKey", resultKey)
        write(File(File(jobs, jobId), "state.json"), state)
    }

    @Synchronized
    fun get(jobId: String): JSONObject? = readState(jobId)

    /** 上游 1.0.18 借鉴: 按内容指纹查找缓存结果（results/<key>/result.json） */
    @Synchronized
    fun lookup(key: String): JSONObject? {
        if (!key.matches(Regex("^[a-f0-9]{32,128}$"))) return null
        val file = File(File(results, key), "result.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readTextCapped(ReadLimits.RESULT_JSON_BYTES)) }.getOrNull()
    }

    @Synchronized
    fun commit(jobId: String, result: JSONObject, key: String): JSONObject {
        requireValidJobId(jobId)
        require(key.matches(Regex("^[a-f0-9]{32,128}$"))) { "Invalid result key" }
        val dir = File(results, key)
        if (!dir.exists()) dir.mkdirs()
        write(File(dir, "result.json"), result)
        if (cachedResultKey == key) {
            cachedResultKey = ""
            cachedResult = null
        }
        update(jobId, "succeeded", "committed", resultKey = key)
        return result
    }

    /** 最近一次解析的结果（同一 job 连续翻页只解析一次）。分页曾经每页都重读+重解析整份文件。 */
    private var cachedResultKey: String = ""
    private var cachedResult: JSONObject? = null

    @Synchronized
    fun result(jobId: String, kind: String?, cursor: String?, limit: Int): JSONObject? {
        requireValidJobId(jobId)
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        val state = readState(jobId) ?: return null
        val key = state.optString("resultKey")
        if (key.isBlank()) return JSONObject().put("jobId", jobId).put("status", state.optString("status"))
        val file = File(File(results, key), "result.json")
        if (!file.isFile) return null
        val result = if (cachedResultKey == key) {
            cachedResult ?: JSONObject(file.readTextCapped(ReadLimits.RESULT_JSON_BYTES)).also {
                cachedResultKey = key
                cachedResult = it
            }
        } else {
            JSONObject(file.readTextCapped(ReadLimits.RESULT_JSON_BYTES)).also {
                cachedResultKey = key
                cachedResult = it
            }
        }
        if (kind == null) return result.put("jobId", jobId)
        require(kind in PAGE_KINDS) { "Unsupported result kind" }
        val page = result.optJSONObject(kind) ?: return result.put("jobId", jobId)
        val items = page.optJSONArray("items") ?: JSONArray()
        val offset = decodeCursor(cursor, jobId, kind)
        val end = minOf(offset + limit, items.length())
        val selected = JSONArray()
        for (index in offset until end) selected.put(items.get(index))
        // 浅拷贝：JSONObject(result.toString()) 会把整份结果重新序列化再解析，
        // 每页请求都让峰值内存翻倍。共享已解析子对象是安全的（调用方只做序列化输出）。
        val response = JSONObject()
        val resultKeys = result.keys()
        while (resultKeys.hasNext()) {
            val name = resultKeys.next()
            response.put(name, result.get(name))
        }
        response.put("jobId", jobId)
        response.put(kind, JSONObject()
            .put("items", selected)
            .put("total", items.length())
            .put("hasMore", end < items.length())
            .put("nextCursor", if (end < items.length()) encodeCursor(jobId, kind, end) else JSONObject.NULL))
        return response
    }

    @Synchronized
    fun cancel(jobId: String): Boolean {
        val state = readState(jobId) ?: return false
        if (state.optString("status") in setOf("succeeded", "failed", "cancelled", "interrupted")) return false
        update(jobId, "cancelled", "cancelled")
        return true
    }

    @Synchronized
    fun prune(olderThanMillis: Long): JSONObject {
        val cutoff = System.currentTimeMillis() - olderThanMillis.coerceAtLeast(0)
        var removedJobs = 0
        jobs.listFiles()?.forEach { dir ->
            val state = readState(dir.name)
            val terminal = state?.optString("status") in setOf("succeeded", "failed", "cancelled", "interrupted")
            if (terminal && state?.optLong("updatedAt", Long.MAX_VALUE) ?: Long.MAX_VALUE < cutoff) {
                if (dir.deleteRecursively()) removedJobs++
            }
        }
        var removedResults = 0
        val referenced = jobs.listFiles().orEmpty().mapNotNull { readState(it.name)?.optString("resultKey")?.takeIf(String::isNotBlank) }.toSet()
        results.listFiles()?.filter { it.lastModified() < cutoff && it.name !in referenced }?.forEach { if (it.deleteRecursively()) removedResults++ }
        return JSONObject().put("removedJobs", removedJobs).put("removedResults", removedResults).put("cutoff", cutoff)
    }

    private fun readState(jobId: String): JSONObject? = runCatching { requireValidJobId(jobId); File(File(jobs, jobId), "state.json").takeIf { it.isFile }?.let { JSONObject(it.readTextCapped(ReadLimits.META_JSON_BYTES)) } }.getOrNull()
    private fun write(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        temp.writeText(value.toString())
        runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun requireValidJobId(jobId: String) { require(JOB_ID.matches(jobId)) { "Invalid Blutter job id" } }
    private fun encodeCursor(jobId: String, kind: String, offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$jobId|$kind|$offset".toByteArray())
    private fun decodeCursor(cursor: String?, jobId: String, kind: String): Int {
        if (cursor.isNullOrBlank()) return 0
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor)).split('|') }.getOrNull()
            ?: throw IllegalArgumentException("Invalid cursor")
        require(parts.size == 3 && parts[0] == jobId && parts[1] == kind) { "Cursor does not belong to this result" }
        return parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid cursor offset")
    }
}
