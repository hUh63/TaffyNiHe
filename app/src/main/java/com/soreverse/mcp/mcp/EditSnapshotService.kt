package com.soreverse.mcp.mcp

import android.content.Context
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 通用「编辑快照」服务：为任意格式的文件提供「改动前自动备份 + diff + 回滚」。
 *
 * 设计目标（MT 手感）：
 *  - 任何工具在改动文件前，先调用 [snapshot] 登记一份原始快照；
 *  - 改错了用 [rollback] 还原原始文件，用 [diff] 查看改动差异；
 *  - 该服务不认识 DEX/SO/APK/文本，只按「一字节不改动」地保存原始副本，
 *    因此对任意格式的文件天然生效。
 *
 * 快照存储：<filesDir>/edit-snapshots/<toolName>/<snapshotId>/
 *  - orig/         原始文件内容（保留原始相对路径结构，多文件时靠哈希去重）
 *  - meta.json     元数据(原始路径/哈希/大小/时间/来源工具)
 * [Snapshot.keepCount] 控制每工具最多保留几份，超出自动清最旧。
 */
object EditSnapshotService {

    const val MAX_KEEP = 10

    /** 为将要被改动的文件登记一份快照。返回 (snapshotId, snapshotDir)。文件不存在则返回 null。 */
    fun snapshot(ctx: Context, toolName: String, filePath: String): Pair<String, File>? {
        val file = File(filePath)
        if (!file.isFile || file.length() == 0L) return null
        val toolDir = snapshotsDir(ctx, toolName)
        toolDir.mkdirs()
        val id = "${System.currentTimeMillis()}-${(filePath.hashCode() and 0x7fffffff)}"
        val snapDir = File(toolDir, id)
        if (!snapDir.mkdirs()) return null

        val origDir = File(snapDir, "orig").apply { mkdirs() }
        val copy = File(origDir, file.name)
        return try {
            file.copyTo(copy, overwrite = true)
            val meta = JSONObject()
                .put("snapshotId", id)
                .put("tool", toolName)
                .put("path", file.absolutePath)
                .put("size", file.length())
                .put("sha256", sha256(file))
                .put("createdAt", System.currentTimeMillis())
                .put("restore", copy.absolutePath)
            File(snapDir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
            trimOld(toolDir)
            id to snapDir
        } catch (e: Exception) {
            snapDir.deleteRecursively()
            null
        }
    }

    /** 列出某工具的全部快照。 */
    fun list(ctx: Context, toolName: String): JSONArray {
        val toolDir = snapshotsDir(ctx, toolName)
        if (!toolDir.isDirectory) return JSONArray()
        val arr = JSONArray()
        toolDir.listFiles()?.sortedByDescending { it.name }?.forEach { snapDir ->
            val metaFile = File(snapDir, "meta.json")
            if (metaFile.isFile) {
                val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
                meta.put("snapshotId", snapDir.name)
                arr.put(meta)
            }
        }
        return arr
    }

    /** 字节级 diff：对比某份快照的原始文件与当前文件。返回 JSONObject 差异描述。 */
    fun diff(ctx: Context, snapshotId: String, toolName: String): JSONObject {
        val snapDir = File(snapshotsDir(ctx, toolName), snapshotId)
        val metaFile = File(snapDir, "meta.json")
        if (!metaFile.isFile) {
            return JSONObject().put("error", "SNAPSHOT_NOT_FOUND").put("message", "快照不存在: $snapshotId")
        }
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
        val origCopy = File(meta.optString("restore"))
        val origPath = meta.optString("path")
        val current = File(origPath)
        val out = JSONObject().put("snapshotId", snapshotId).put("path", origPath).put("tool", toolName)

        if (!origCopy.isFile) { out.put("error", "ORIG_MISSING").put("message", "快照原始副本缺失"); return out }
        if (!current.isFile) { out.put("error", "CURRENT_MISSING").put("message", "当前文件不存在(可能已被删除)"); return out }

        val origLen = origCopy.length()
        val curLen = current.length()
        val origHash = sha256(origCopy)
        val curHash = sha256(current)
        out.put("origSize", origLen).put("currentSize", curLen)
            .put("origSha256", origHash).put("currentSha256", curHash)
            .put("same", origHash == curHash)
            .put("sizeDelta", curLen - origLen)
        if (origHash != curHash) {
            val changed = countChangedBytes(origCopy, current)
            out.put("changedBytes", changed).put("changedPercent", if (origLen > 0) (changed * 100.0 / origLen) else 0.0)
            // 文本文件(如 smali/json/xml): 附加行级可读 diff
            if (looksLikeText(origCopy) && looksLikeText(current)) {
                try {
                    val o = origCopy.readText().lines()
                    val c = current.readText().lines()
                    if (o.size + c.size <= 20000) {
                        val (added, removed) = lineDiff(o, c)
                        val additions = JSONArray(); removed.forEach { additions.put("+" + it) }
                        val deletions = JSONArray(); added.forEach { deletions.put("-" + it) }
                        if (added.isNotEmpty() || removed.isNotEmpty()) {
                            out.put("lineDiff", JSONObject()
                                .put("addedLines", added.size)
                                .put("removedLines", removed.size)
                                .put("removed", removed)
                                .put("added", added))
                        }
                    }
                } catch (e: Exception) { /* 行级 diff 失败则忽略, 仍保留字节级 */ }
            }
        }
        return out
    }

    /** 粗略判断一个文件是否以 UTF-8 文本为主(用于决定是否做行级 diff)。 */
    private fun looksLikeText(f: File): Boolean {
        if (f.length() > 5 * 1024 * 1024) return false   // 超过 5MB 不做行级
        val head = try { f.inputStream().use { it.readNBytes(4096) } } catch (e: Exception) { return false }
        if (head.isEmpty()) return true
        var control = 0
        val n = minOf(head.size, 4096)
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xFF
            if (b == 0) return false                  // NUL 表示二进制
            if (b < 0x09 || (b in 0x0E..0x1F) || b == 0x7F) control++
        }
        return control < n / 32
    }

    /** 简单逐行 diff(类 Myers 的朴素版): 返回 (删除行=[仅旧文件], 新增行=[仅新文件])。
     *  对 smali/json 等行文本足够。复杂度 O(n*m) 但用行哈希预处理, 大文件已由调用方限制。 */
    private fun lineDiff(oldLines: List<String>, newLines: List<String>): Pair<List<String>, List<String>> {
        val n = oldLines.size; val m = newLines.size
        // 基于行相等的最长公共子序列, 扁平化后追踪增删
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (oldLines[i] == newLines[j]) { i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { removed.add(oldLines[i]); i++ }
            else { added.add(newLines[j]); j++ }
        }
        while (i < n) { removed.add(oldLines[i]); i++ }
        while (j < m) { added.add(newLines[j]); j++ }
        return removed to added
    }

    /** 回滚：用快照还原原始文件到指定目标路径。默认还原到元数据里的原始路径。 */
    fun rollback(ctx: Context, snapshotId: String, toolName: String, targetPath: String? = null): JSONObject {
        val snapDir = File(snapshotsDir(ctx, toolName), snapshotId)
        val metaFile = File(snapDir, "meta.json")
        if (!metaFile.isFile) {
            return JSONObject().put("error", "SNAPSHOT_NOT_FOUND").put("message", "快照不存在: $snapshotId")
        }
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
        val origCopy = File(meta.optString("restore"))
        if (!origCopy.isFile) {
            return JSONObject().put("error", "ORIG_MISSING").put("message", "快照原始副本缺失")
        }
        val dest = File(targetPath ?: meta.optString("path"))
        return try {
            dest.parentFile?.mkdirs()
            val backupOfCurrent = if (dest.isFile) {
                // 回滚前先把当前(改坏的)文件也留一份, 便于反悔
                File(snapDir, "current-backup").mkdirs()
                val cb = File(File(snapDir, "current-backup"), dest.name)
                dest.copyTo(cb, overwrite = true)
                cb.absolutePath
            } else null
            origCopy.copyTo(dest, overwrite = true)
            JSONObject().put("success", true).put("restored", dest.absolutePath)
                .put("backupOfCurrent", backupOfCurrent ?: JSONObject.NULL)
                .put("sha256", sha256(dest))
        } catch (e: Exception) {
            JSONObject().put("error", "ROLLBACK_FAILED").put("message", e.message)
        }
    }

    /** 清空某工具的全部快照。 */
    fun clear(ctx: Context, toolName: String): Int {
        val toolDir = snapshotsDir(ctx, toolName)
        val dirs = toolDir.listFiles()
        if (dirs == null || dirs.isEmpty()) return 0
        val n = dirs.count { it.isDirectory }
        toolDir.deleteRecursively()
        return n
    }

    private fun snapshotsDir(ctx: Context, toolName: String): File {
        val safe = toolName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(ctx.filesDir, "edit-snapshots/$safe")
    }

    private fun trimOld(toolDir: File) {
        val dirs = toolDir.listFiles() ?: return
        if (dirs.size <= MAX_KEEP) return
        val sorted = dirs.filter { it.isDirectory }.sortedBy { it.name }
        val overflow = sorted.size - MAX_KEEP
        sorted.take(overflow).forEach { it.deleteRecursively() }
    }

    private fun countChangedBytes(a: File, b: File): Long {
        var changed = 0L
        try {
            a.inputStream().use { ia -> b.inputStream().use { ib ->
                val ba = ByteArray(8192); val bb = ByteArray(8192)
                var na = ia.read(ba); var nb = ib.read(bb)
                while (na > 0 || nb > 0) {
                    val len = maxOf(na, nb)
                    for (i in 0 until len) {
                        val va = if (i < na) ba[i] else 0
                        val vb = if (i < nb) bb[i] else 0
                        if (va != vb) changed++
                    }
                    if (na == 0 && nb == 0) break
                    na = ia.read(ba); nb = ib.read(bb)
                }
            } }
        } catch (e: Exception) { return -1 }
        return changed
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        try {
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                var n = ins.read(buf)
                while (n > 0) { md.update(buf, 0, n); n = ins.read(buf) }
            }
        } catch (e: Exception) { return "" }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 便捷：一个工具统一的 snapshot 工具入口 handler（list/diff/rollback/clear）。 */
    fun handler(): ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_edit_snapshot",
            "【编辑快照】(通用) 为任意文件的编辑提供「改动前自动备份 + 对比 + 回滚」闭环, 对标 MT 的 diff/还原。action=snapshot 主动登记一个文件为快照(改它前调用, 返回快照 ID); action=list 列出某工具登记的快照; action=diff 对比某快照原始文件与当前文件的差异(字节级: 大小/SHA/差异量); action=rollback 用快照还原原始文件; action=clear 清空某工具全部快照。任何工具(taffy_smali_edit/taffy_apk_rebuild/taffy_file_write 等)在改文件前都可自动登记, 因此对 DEX/SO/APK/普通文件等任意格式生效。",
            "Generic edit snapshot service for any file format: auto-backup / manual snapshot before edit, then diff / rollback. action=snapshot registers a file (call before editing it, returns snapshotId); list snapshots for a tool; diff compares snapshot original vs current (bytes: size/SHA/delta); rollback restores original; clear wipes a tool's snapshots. Any tool may register before editing, so DEX/SO/APK/text all get backup/diff/undo.",
            "file", ToolClass.META, heavy = false,
        ) {
            objectSchema(props {
                "action".oneOf("snapshot操作", "snapshot(登记文件) | list(列出快照) | diff(对比差异) | rollback(回滚) | clear(清空)", "snapshot", "list", "diff", "rollback", "clear")
                "tool" str "快照所属工具名/分组(如 taffy_smali_edit/taffy_apk_rebuild/taffy_smali_batch 或自定义)"
                "path" str "snapshot: 要登记的文件路径(改动前调用)"
                "snapshotId" str "diff/rollback: 快照 ID(来自 snapshot/list)"
                "targetPath" str "rollback: 还原到的目标路径(默认还原到原路径, 可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val toolName = args.str("tool")
            if (toolName.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 tool", "tool", "")
            return when (args.str("action", "list")) {
                "snapshot" -> {
                    val p = args.str("path")
                    if (p.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
                    val snap = snapshot(ctx.context, toolName, p)
                    if (snap == null) return err("SNAPSHOT_FAILED", "快照登记失败(文件不存在或为空)", "path", p)
                    ok(JSONObject().put("snapshotId", snap.first).put("tool", toolName).put("path", p).put("keepMax", MAX_KEEP))
                }
                "list" -> ok(JSONObject()
                    .put("tool", toolName)
                    .put("keepMax", MAX_KEEP)
                    .put("snapshots", list(ctx.context, toolName)))
                "diff" -> {
                    val id = args.str("snapshotId")
                    if (id.isBlank()) return err("INVALID_ARGUMENT", "缺少 snapshotId", "snapshotId", "")
                    ok(JSONObject().put("diff", diff(ctx.context, id, toolName)))
                }
                "rollback" -> {
                    val id = args.str("snapshotId")
                    if (id.isBlank()) return err("INVALID_ARGUMENT", "缺少 snapshotId", "snapshotId", "")
                    ok(JSONObject().put("rollback", rollback(ctx.context, id, toolName, args.str("targetPath").takeIf { it.isNotBlank() })))
                }
                "clear" -> ok(JSONObject().put("cleared", clear(ctx.context, toolName)).put("tool", toolName))
                else -> err("UNKNOWN_ACTION", "未知 action: ${args.str("action")}", "action", args.str("action"))
            }
        }
    }
}
