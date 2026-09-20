// 塔菲逆核: taffy_so_sync_original 的引擎实现——
// 把编辑会话的当前字节写回「工作区源文件本身」，并立即从磁盘回读做 sha256 校验。
//
// 设计要点：
//  - 默认 dryRun=true（只预览），backup=true（写前把原文件另存为 <原名>.bak）；
//  - 写入采用「临时文件 + rename」尽力保证原子性，rename 不可用时退化为直接写入；
//  - 写后必须从磁盘重新读取并对比 sha256，verified 字段如实反映校验结果；
//  - 源文件不可写（只读挂载 / 权限不足 / content URI / APK 内条目）时返回结构化错误，不抛异常。
package com.soreverse.mcp.engine

import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private fun EngineRuntime.syncDiffRanges(before: ByteArray, after: ByteArray, limit: Int): JSONArray {
    val out = JSONArray()
    val common = minOf(before.size, after.size)
    var i = 0
    while (i < common && out.length() < limit) {
        if (before[i] == after[i]) {
            i++
            continue
        }
        val start = i
        while (i < common && before[i] != after[i]) i++
        out.put(JSONObject().put("fileOffset", hex(start.toLong())).put("offset", start).put("length", i - start))
    }
    if (before.size != after.size && out.length() < limit) {
        out.put(
            JSONObject()
                .put("offset", common)
                .put("length", maxOf(before.size, after.size) - common)
                .put("kind", if (after.size > before.size) "appended-bytes" else "removed-bytes"),
        )
    }
    return out
}

internal fun EngineRuntime.soSyncOriginal(
    workspaceId: String,
    editSessionId: String,
    dryRun: Boolean = true,
    backup: Boolean = true,
): JSONObject = guarded {
    val ws = workspaces[workspaceId]
        ?: return@guarded err("WORKSPACE_NOT_FOUND", "Workspace not found. Call taffy_so_open first.", "workspaceId", workspaceId)
    val session = ws.edits[editSessionId]
        ?: return@guarded err("EDIT_SESSION_NOT_FOUND", "Edit session not found. Call taffy_session_open (action=open) first.", "editSessionId", editSessionId)
    val source = ws.source
    val sourcePath = source.path
    if (!source.apkPath.isNullOrBlank()) {
        return@guarded err(
            "SYNC_UNSUPPORTED_SOURCE",
            "工作区源文件来自 APK 归档（${source.apkPath}::${source.apkEntry ?: ""}），无法就地写回。请先把 SO 解包到工作目录后用 taffy_so_open 重新打开，或用 taffy_apk_rebuild 重打包 APK。",
            "editSessionId",
            editSessionId,
        )
    }
    if (sourcePath.isBlank() || sourcePath.startsWith("content://")) {
        return@guarded err(
            "SYNC_UNSUPPORTED_SOURCE",
            "工作区源文件不是可直接写入的本地路径（${sourcePath.ifBlank { "(empty)" }}）。SAF content URI / 流式来源无法在无用户确认的情况下覆写；请把 SO 复制到工作目录（应用私有目录或工作目录的真实路径）后重新打开。",
            "editSessionId",
            editSessionId,
        )
    }
    val file = File(sourcePath)
    if (!file.isFile) {
        return@guarded err(
            "SOURCE_NOT_FOUND",
            "工作区源文件在磁盘上不存在或不是普通文件：$sourcePath",
            "editSessionId",
            editSessionId,
            "resolvedPath" to file.absolutePath,
        )
    }
    synchronized(session.lock) {
        val onDisk = runCatching { file.readBytes() }.getOrElse { e ->
            return@guarded err(
                "SOURCE_UNREADABLE",
                "读取源文件失败：${e.message ?: e.javaClass.simpleName}",
                "editSessionId",
                editSessionId,
                "sourcePath" to sourcePath,
            )
        }
        val payload = session.data.copyOf()
        val shaBefore = sha256(onDisk)
        val shaAfter = sha256(payload)
        val changed = shaBefore != shaAfter
        val base = JSONObject()
            .put("workspaceId", workspaceId)
            .put("editSessionId", editSessionId)
            .put("sourcePath", file.absolutePath)
            .put("sourceKind", source.source)
            .put("sourceName", source.name)
            .put("dryRun", dryRun)
            .put("backupRequested", backup)
            .put("sha256Before", shaBefore)
            .put("sha256After", shaAfter)
            .put("bytesOnDiskBefore", onDisk.size)
            .put("bytesToWrite", payload.size)
            .put("sizeDelta", payload.size - onDisk.size)
            .put("changed", changed)
            .put("revision", session.revision)
            .put("patchCount", session.patches.size)
            .put("diffRangeCount", syncDiffRanges(onDisk, payload, 100).length())
            .put("diffRanges", syncDiffRanges(onDisk, payload, 100))
        if (!changed) {
            return@guarded ok(JSONObject(base.toString())
                .put("written", false)
                .put("verified", true)
                .put("bytesWritten", 0)
                .put("sha256OnDisk", shaBefore)
                .put("backupPath", JSONObject.NULL)
                .put("note", "编辑会话字节与磁盘文件完全一致（sha256 相同），未做任何写入。"))
        }
        if (dryRun) {
            return@guarded ok(JSONObject(base.toString())
                .put("written", false)
                .put("verified", false)
                .put("bytesWritten", 0)
                .put("plannedBackupPath", if (backup) "$sourcePath.bak" else JSONObject.NULL)
                .put("backupPath", JSONObject.NULL)
                .put("note", "dryRun=true：仅预览差异，未写入磁盘。确认后以 dryRun=false 再次调用即执行写回。"))
        }
        var backupPath: String? = null
        if (backup) {
            val bak = File("$sourcePath.bak")
            val backupError = try {
                bak.writeBytes(onDisk)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (backupError != null) {
                return@guarded err(
                    "BACKUP_FAILED",
                    "写前备份失败：$backupError（未做任何写入）",
                    "editSessionId",
                    editSessionId,
                    "backupPath" to bak.absolutePath,
                )
            }
            backupPath = bak.absolutePath
        }
        val writeError = runCatching {
            val dir = file.parentFile ?: File(".")
            val tmp = File(dir, "${file.name}.taffy-tmp")
            FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.writeBytes(payload)
                tmp.delete()
            }
        }.exceptionOrNull()
        if (writeError != null) {
            return@guarded err(
                "SOURCE_NOT_WRITABLE",
                "写回源文件失败（只读挂载 / 权限不足 / SELinux 限制？）：${writeError.message ?: writeError.javaClass.simpleName}",
                "editSessionId",
                editSessionId,
                "sourcePath" to sourcePath,
                "backupPath" to (backupPath ?: "none"),
            )
        }
        val readBack = runCatching { file.readBytes() }.getOrNull()
        val shaOnDisk = readBack?.let { sha256(it) }
        val verified = readBack != null && shaOnDisk == shaAfter && readBack.size == payload.size
        AppLog.i("taffy_so_sync_original wrote ${payload.size} bytes -> $sourcePath verified=$verified")
        val done = JSONObject(base.toString())
            .put("written", true)
            .put("verified", verified)
            .put("bytesWritten", payload.size)
            .put("sha256OnDisk", shaOnDisk ?: JSONObject.NULL)
            .put("bytesOnDiskAfter", readBack?.size ?: JSONObject.NULL)
            .put("backupPath", backupPath ?: JSONObject.NULL)
            .put("note", "工作区基线（ws.data）仍保持原始版本，便于 taffy_edit_check 继续与原始字节对比；如需以磁盘内容为新基线请重新 taffy_so_open。")
        if (!verified) done.put("warning", "回读校验失败：磁盘字节与会话字节的 sha256 不一致，可能被其它进程改写或写入未落盘，请重新读取确认。")
        ok(done)
    }
}
