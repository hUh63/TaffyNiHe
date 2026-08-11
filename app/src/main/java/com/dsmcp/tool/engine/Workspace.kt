package com.dsmcp.tool.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Data models for the binary editing engine.
 *
 * A [Workspace] holds the original file data and metadata. An [EditSession]
 * holds a mutable copy with undo/redo snapshot history. All patching
 * operations go through an edit session so the original file is never
 * modified in place — the user explicitly builds an output file when done.
 */

data class Workspace(
    val id: String,
    val filePath: String,
    val fileName: String,
    val format: String,       // "elf" | "pe" | "unknown"
    val originalData: ByteArray,
    val createdAt: Long,
    val temporary: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}

data class EditSession(
    val id: String,
    val workspaceId: String,
    var data: ByteArray,
    val snapshots: MutableList<Snapshot> = mutableListOf(),
    var currentIndex: Int = -1,
    val auditLog: MutableList<AuditEntry> = mutableListOf(),
) {
    /**
     * 安全修复: 每会话独立锁，防止并发 MCP 请求交叉修改同一编辑会话导致数据损坏。
     * 所有修改 session.data / snapshots / currentIndex / auditLog 的操作都必须持有此锁。
     */
    val lock: Any = Any()

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}

data class Snapshot(
    val id: String,
    val label: String,
    val data: ByteArray,
    val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}

data class AuditEntry(
    val timestamp: Long,
    val action: String,
    val details: String,
)

data class BuildOutput(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val workspaceId: String,
)

// ── JSON response helpers (compatible with SOMCP's JsonUtil format) ──

/**
 * Builds a success response with `ok: true`, the given key-value pairs,
 * and an empty `nextActions` array — matching SOMCP's [com.soreverse.mcp.core.ok] format.
 */
fun ok(vararg pairs: Pair<String, Any?>): JSONObject {
    val obj = JSONObject()
    obj.put("ok", true)
    obj.put("nextActions", JSONArray())
    pairs.forEach { (k, v) -> when (v) {
        is Boolean -> obj.put(k, v)
        is Int -> obj.put(k, v)
        is Long -> obj.put(k, v)
        is String -> obj.put(k, v)
        is JSONObject -> obj.put(k, v)
        is JSONArray -> obj.put(k, v)
        is ByteArray -> obj.put(k, v.size)
        else -> if (v != null) obj.put(k, v.toString())
    }}
    return obj
}

/**
 * Builds an error response with `ok: false`, a structured `error` object
 * (code, message, severity, recoverable, retrySameArguments, diagnostics),
 * and an empty `nextActions` array — matching SOMCP's [com.soreverse.mcp.core.err] format.
 */
fun err(code: String, message: String, vararg details: Pair<String, Any?>): JSONObject {
    val diagnostics = JSONObject()
    details.forEach { (k, v) -> diagnostics.put(k, when (v) {
        is Boolean -> v
        is Int -> v
        is Long -> v
        is String -> v
        is JSONObject -> v
        is JSONArray -> v
        else -> v?.toString() ?: JSONObject.NULL
    })}
    val errorObj = JSONObject()
        .put("code", code)
        .put("message", message)
        .put("severity", "error")
        .put("recoverable", true)
        .put("retrySameArguments", false)
        .put("diagnostics", diagnostics)
    return JSONObject()
        .put("ok", false)
        .put("error", errorObj)
        .put("nextActions", JSONArray())
}

// JSON helpers for extracting typed values with defaults.
fun JSONObject.str(key: String, default: String = ""): String = optString(key, default)
fun JSONObject.bool(key: String, default: Boolean = false): Boolean = optBoolean(key, default)
fun JSONObject.intValue(key: String, default: Int = 0): Int = optInt(key, default)
fun JSONObject.longValue(key: String, default: Long = 0L): Long = optLong(key, default)
fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
