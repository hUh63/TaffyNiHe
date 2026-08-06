package com.dsmcp.tool.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The core binary editing engine for DSMCP.
 *
 * Manages workspaces (opened .so/.dll files) and edit sessions (mutable
 * copies with undo/redo history). Delegates all format-specific parsing
 * and patching to [NativeBridge], which calls into the C++ native layer.
 *
 * Design borrowed from SOMCP's NativeSoEngine: workspaces are keyed by ID,
 * edit sessions are keyed by workspace+session ID, and every patching
 * operation goes through an edit session so the original file is never
 * modified in place.
 */
class BinaryEngine(
    private val context: Context,
    private val native: NativeBridge = NativeBridge(),
    initialWorkDirPath: String? = null,
) {

    private val workspaces = ConcurrentHashMap<String, Workspace>()
    private val sessions = ConcurrentHashMap<String, EditSession>()
    private val buildOutputs = mutableListOf<BuildOutput>()

    @Volatile
    private var _workDirPath: String? = initialWorkDirPath

    /** 当前工作目录路径（可为空，表示使用应用私有目录） */
    val workDirPath: String? get() = _workDirPath

    /** 输出目录：优先使用用户指定的工作目录，回退到应用私有目录 */
    @Volatile
    var outputDir: File = computeOutputDir()
        private set

    private fun computeOutputDir(): File {
        val dir = if (!_workDirPath.isNullOrBlank()) {
            File(_workDirPath, "output").apply { mkdirs() }
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "dsmcp_output").apply { mkdirs() }
        }
        Log.i(TAG, "Output directory: ${dir.absolutePath}")
        return dir
    }

    /** 更新工作目录路径（由 EngineProvider 在用户选择工作目录后调用） */
    fun updateWorkDirectory(path: String?) {
        _workDirPath = path
        outputDir = computeOutputDir()
        Log.i(TAG, "Work directory updated: ${path ?: "(default)"}")
    }

    fun nativeBridge(): NativeBridge = native

    // ── Workspace management ──

    fun open(path: String, temporary: Boolean = false): JSONObject {
        // 安全修复: 添加路径穿越校验，防止通过 ../ 访问任意文件
        val pathError = com.soreverse.mcp.core.PathSafety.validate(path, _workDirPath)
        if (pathError != null) {
            return err("PATH_UNSAFE", "Path validation failed: $pathError", "path" to path)
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return err("FILE_NOT_FOUND", "File not found: $path", "path" to path)
        }
        // 安全修复: 添加文件大小上限检查，防止 OOM
        val maxFileSize = 256L * 1024 * 1024 // 256MB
        if (file.length() > maxFileSize) {
            return err("FILE_TOO_LARGE", "File is too large (${file.length()} bytes, max ${maxFileSize} bytes). Use smaller files or split the binary.", "path" to path, "fileSize" to file.length())
        }
        val data = runCatching { file.readBytes() }.getOrElse {
            return err("READ_ERROR", "Cannot read file: ${it.message}", "path" to path)
        }
        if (data.size < 16) {
            return err("FILE_TOO_SMALL", "File is too small to be a valid binary (${data.size} bytes)")
        }
        val format = native.detectFormat(data)
        if (format == "unknown") {
            return err("UNSUPPORTED_FORMAT", "File format not recognized. Only ELF (.so) and PE (.dll) are supported.")
        }
        val id = generateId()
        val ws = Workspace(
            id = id,
            filePath = file.absolutePath,
            fileName = file.name,
            format = format,
            originalData = data,
            createdAt = System.currentTimeMillis(),
            temporary = temporary,
        )
        workspaces[id] = ws
        Log.i(TAG, "Opened ${file.name} as $format, workspace=$id, ${data.size} bytes")
        return ok(
            "workspaceId" to id,
            "fileName" to ws.fileName,
            "format" to format,
            "fileSize" to data.size.toLong(),
            "path" to file.absolutePath,
        )
    }

    fun close(workspaceId: String): JSONObject {
        val ws = workspaces.remove(workspaceId)
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        // Close all sessions for this workspace
        sessions.entries.removeIf { it.value.workspaceId == workspaceId }
        Log.i(TAG, "Closed workspace ${ws.fileName}")
        return ok("closed" to true, "workspaceId" to workspaceId)
    }

    fun listWorkspaces(): JSONObject {
        val arr = JSONArray()
        workspaces.values.sortedBy { it.createdAt }.forEach { ws ->
            val sessionCount = sessions.values.count { it.workspaceId == ws.id }
            arr.put(JSONObject()
                .put("workspaceId", ws.id)
                .put("fileName", ws.fileName)
                .put("format", ws.format)
                .put("fileSize", ws.originalData.size.toLong())
                .put("path", ws.filePath)
                .put("sessions", sessionCount)
                .put("temporary", ws.temporary)
            )
        }
        return ok("workspaces" to arr, "count" to arr.length())
    }

    // ── Edit session management ──

    fun openEditSession(workspaceId: String): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        val sessionId = generateId()
        val session = EditSession(
            id = sessionId,
            workspaceId = workspaceId,
            data = ws.originalData.copyOf(),
        )
        // Initial snapshot
        session.snapshots.add(Snapshot(generateId(), "initial", session.data.copyOf(), System.currentTimeMillis()))
        session.currentIndex = 0
        sessions[sessionId] = session
        Log.i(TAG, "Opened edit session $sessionId for workspace $workspaceId")
        return ok("editSessionId" to sessionId, "workspaceId" to workspaceId)
    }

    fun snapshot(workspaceId: String, editSessionId: String, label: String): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        // 安全修复: 加锁防止并发请求交叉修改同一编辑会话。
        synchronized(session.lock) {
            // 安全修复: 添加快照数量限制，防止内存爆炸。
            // 每个快照持有完整文件数据的副本，大文件下少量快照即可耗尽堆内存。
            val maxSnapshots = 20
            if (session.snapshots.size >= maxSnapshots) {
                // 移除最早的非当前快照（保留当前索引指向的快照）
                val removableIndex = (0 until session.snapshots.size)
                    .firstOrNull { it != session.currentIndex } ?: 0
                session.snapshots.removeAt(removableIndex)
                if (removableIndex < session.currentIndex) session.currentIndex--
            }
            // Truncate any redo history
            while (session.snapshots.size > session.currentIndex + 1) {
                session.snapshots.removeAt(session.snapshots.size - 1)
            }
            val snap = Snapshot(generateId(), label, session.data.copyOf(), System.currentTimeMillis())
            session.snapshots.add(snap)
            session.currentIndex = session.snapshots.size - 1
            return ok("snapshotId" to snap.id, "label" to label, "index" to session.currentIndex,
                "totalSnapshots" to session.snapshots.size)
        }
    }

    fun undo(workspaceId: String, editSessionId: String, count: Int = 1): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        synchronized(session.lock) {
            val newIndex = (session.currentIndex - count).coerceAtLeast(0)
            if (newIndex == session.currentIndex) {
                return ok("restored" to false, "reason" to "already at earliest snapshot")
            }
            session.currentIndex = newIndex
            session.data = session.snapshots[newIndex].data.copyOf()
            return ok("restored" to true, "index" to newIndex, "label" to session.snapshots[newIndex].label)
        }
    }

    fun redo(workspaceId: String, editSessionId: String, count: Int = 1): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        synchronized(session.lock) {
            val newIndex = (session.currentIndex + count).coerceAtMost(session.snapshots.size - 1)
            if (newIndex == session.currentIndex) {
                return ok("restored" to false, "reason" to "already at latest snapshot")
            }
            session.currentIndex = newIndex
            session.data = session.snapshots[newIndex].data.copyOf()
            return ok("restored" to true, "index" to newIndex, "label" to session.snapshots[newIndex].label)
        }
    }

    fun rollback(workspaceId: String, editSessionId: String, snapshotIndex: Int = -1): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        synchronized(session.lock) {
            val idx = if (snapshotIndex < 0) session.snapshots.size - 1 else snapshotIndex
            if (idx < 0 || idx >= session.snapshots.size) {
                return err("INVALID_ARGUMENT", "Invalid snapshot index: $idx", "snapshotIndex" to idx)
            }
            session.currentIndex = idx
            session.data = session.snapshots[idx].data.copyOf()
            return ok("rolledBack" to true, "index" to idx, "label" to session.snapshots[idx].label)
        }
    }

    fun reset(workspaceId: String, editSessionId: String): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        synchronized(session.lock) {
            session.data = ws.originalData.copyOf()
            session.snapshots.clear()
            session.snapshots.add(Snapshot(generateId(), "reset", session.data.copyOf(), System.currentTimeMillis()))
            session.currentIndex = 0
            return ok("reset" to true)
        }
    }

    fun sessionHistory(workspaceId: String, editSessionId: String): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        synchronized(session.lock) {
            val arr = JSONArray()
            session.snapshots.forEachIndexed { i, snap ->
                arr.put(JSONObject()
                    .put("index", i)
                    .put("id", snap.id)
                    .put("label", snap.label)
                    .put("timestamp", snap.timestamp)
                    .put("current", i == session.currentIndex)
                )
            }
            return ok("snapshots" to arr, "currentIndex" to session.currentIndex, "count" to arr.length())
        }
    }

    // ── Analysis ──

    fun analyze(workspaceId: String, editSessionId: String? = null): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = when (ws.format) {
            "elf" -> native.elfParse(data)
            "pe" -> native.peParse(data)
            else -> return err("UNSUPPORTED_FORMAT", "Cannot analyze format: ${ws.format}")
        }
        if (result.has("error")) {
            return err("PARSE_FAILED", result.getString("error"))
        }
        return ok("analysis" to result, "format" to ws.format, "workspaceId" to workspaceId)
    }

    // ── .NET analysis & editing ──

    // ── .NET operations ──

    /** 检测 .NET/Mono 程序集并返回诊断信息 */
    fun dotnetDetect(workspaceId: String, editSessionId: String? = null): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetDetect(data)
        // 直接返回诊断结果，不拦截 error
        val r = ok(
            "isDotNet" to result.optBoolean("isDotNet", false),
            "fileSize" to result.optLong("fileSize", 0),
            "hasMZHeader" to result.optBoolean("hasMZHeader", false),
            "hasPEHeader" to result.optBoolean("hasPEHeader", false),
            "peValid" to result.optBoolean("peValid", false),
            "hasClrDataDir" to result.optBoolean("hasClrDataDir", false),
            "clrRva" to result.optLong("clrRva", 0),
            "clrSize" to result.optLong("clrSize", 0),
            "bsjbFound" to result.optBoolean("bsjbFound", false),
            "workspaceId" to workspaceId,
            "format" to ws.format,
        )
        if (result.has("bsjbOffset")) r.put("bsjbOffset", result.getLong("bsjbOffset"))
        if (result.has("assemblyName")) r.put("assemblyName", result.getString("assemblyName"))
        if (result.has("typeCount")) r.put("typeCount", result.getInt("typeCount"))
        if (result.has("methodCount")) r.put("methodCount", result.getInt("methodCount"))
        if (result.has("error")) r.put("detail", result.getString("error"))
        // 流信息
        r.put("hasStringsStream", result.optBoolean("hasStringsStream", false))
        r.put("hasUsStream", result.optBoolean("hasUsStream", false))
        r.put("hasBlobStream", result.optBoolean("hasBlobStream", false))
        r.put("hasTablesStream", result.optBoolean("hasTablesStream", false))
        return r
    }

    fun dotnetListTypes(workspaceId: String, editSessionId: String? = null): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetListTypes(data)
        if (result.has("error")) {
            val msg = result.getString("error")
            return err("NOT_DOTNET", "$msg | 提示: 使用 dotnet_detect 获取详细诊断信息 | Hint: use dotnet_detect for diagnostics")
        }
        return ok("types" to (result.optJSONArray("types") ?: JSONArray()),
            "count" to result.optInt("count", 0), "workspaceId" to workspaceId)
    }

    fun dotnetListMethods(workspaceId: String, editSessionId: String? = null, typeFilter: Int = 0): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetListMethods(data, typeFilter)
        if (result.has("error")) {
            val msg = result.getString("error")
            return err("NOT_DOTNET", "$msg | 提示: 使用 dotnet_detect 获取详细诊断信息 | Hint: use dotnet_detect for diagnostics")
        }
        return ok("methods" to (result.optJSONArray("methods") ?: JSONArray()),
            "count" to result.optInt("count", 0), "workspaceId" to workspaceId,
            "typeFilter" to typeFilter)
    }

    fun dotnetListStrings(workspaceId: String, editSessionId: String? = null, maxCount: Int = 5000): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetListStrings(data, maxCount.coerceIn(1, 10000))
        if (result.has("error")) {
            return err("NOT_DOTNET", "${result.getString("error")} | Hint: use dotnet_detect for diagnostics")
        }
        return ok("strings" to (result.optJSONArray("strings") ?: JSONArray()),
            "count" to result.optInt("count", 0), "workspaceId" to workspaceId)
    }

    fun dotnetDumpIl(workspaceId: String, editSessionId: String? = null, methodToken: Int): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetDumpIl(data, methodToken)
        if (result.has("error")) {
            return err("DOTNET_IL_FAILED", result.getString("error"),
                "methodToken" to "0x${methodToken.toString(16)}")
        }
        return ok("il" to result, "methodToken" to "0x${methodToken.toString(16)}",
            "workspaceId" to workspaceId)
    }

    fun dotnetDisasm(workspaceId: String, editSessionId: String? = null, methodToken: Int, pseudoCode: Boolean = false): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetDisasm(data, methodToken, pseudoCode)
        if (result.has("error")) {
            return err("DOTNET_DISASM_FAILED", result.getString("error"),
                "methodToken" to "0x${methodToken.toString(16)}")
        }
        return ok("disasm" to result, "methodToken" to "0x${methodToken.toString(16)}",
            "pseudoCode" to pseudoCode, "workspaceId" to workspaceId)
    }

    fun dotnetResolveToken(workspaceId: String, editSessionId: String? = null, token: Int): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.dotnetResolveToken(data, token)
        if (result.has("error")) {
            return err("DOTNET_RESOLVE_FAILED", result.getString("error"),
                "token" to "0x${token.toString(16)}")
        }
        return ok("resolved" to result, "token" to "0x${token.toString(16)}",
            "workspaceId" to workspaceId)
    }

    fun dotnetEditIl(workspaceId: String, editSessionId: String, methodToken: Int,
                     ilOffset: Int, hexData: String, dryRun: Boolean = false): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        synchronized(session.lock) {
            val patch = native.fromHex(hexData)
            if (patch.isEmpty()) {
                return err("INVALID_HEX", "Invalid hex data: $hexData")
            }

            // Dump current IL to show old bytes for preview
            val oldIl = native.dotnetDumpIl(session.data, methodToken)
            val ilHex = oldIl.optString("ilHex", "")
            val ilBytes = native.fromHex(ilHex)
            val oldPreview = if (ilOffset < ilBytes.size) {
                val end = minOf(ilBytes.size, ilOffset + patch.size)
                native.toHex(ilBytes.copyOfRange(ilOffset, end), spaced = true)
            } else ""
            val newHex = native.toHex(patch, spaced = true)

            if (dryRun) {
                return ok("dryRun" to true, "methodToken" to "0x${methodToken.toString(16)}",
                    "ilOffset" to ilOffset, "oldHex" to oldPreview, "newHex" to newHex,
                    "patchSize" to patch.size)
            }

            val result = native.dotnetEditIl(session.data, methodToken, ilOffset, patch)
            if (result.isEmpty()) {
                return err("IL_PATCH_FAILED", "IL patch failed: method not found or offset out of range",
                    "methodToken" to "0x${methodToken.toString(16)}", "ilOffset" to ilOffset)
            }
            session.data = result
            addAudit(session, "dotnet_edit_il",
                "method=0x${methodToken.toString(16)} ilOffset=$ilOffset size=${patch.size}")
            return ok("applied" to true, "methodToken" to "0x${methodToken.toString(16)}",
                "ilOffset" to ilOffset, "oldHex" to oldPreview, "newHex" to newHex)
        }
    }

    fun dotnetEditString(workspaceId: String, editSessionId: String,
                         usOffset: Int, newStr: String, dryRun: Boolean = false): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "pe") {
            return err("UNSUPPORTED_FORMAT", ".NET operations require a PE (.dll) file, got: ${ws.format}")
        }
        synchronized(session.lock) {
            if (dryRun) {
                return ok("dryRun" to true, "usOffset" to usOffset,
                    "newStr" to newStr, "newStrLen" to newStr.length)
            }

            val result = native.dotnetEditString(session.data, usOffset, newStr)
            if (result.isEmpty()) {
                return err("STRING_PATCH_FAILED", "String patch failed: offset out of range or string too long",
                    "usOffset" to usOffset, "newStr" to newStr)
            }
            session.data = result
            addAudit(session, "dotnet_edit_string",
                "usOffset=$usOffset newLen=${newStr.length}")
            return ok("applied" to true, "usOffset" to usOffset, "newStr" to newStr)
        }
    }

    // ── Reading ──

    fun readHex(workspaceId: String, editSessionId: String? = null, offset: Int = 0, length: Int = 256): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val actualOffset = offset.coerceIn(0, data.size)
        val actualLength = length.coerceIn(1, 65536)
        val dump = native.hexDump(data, actualOffset, actualLength)
        return ok("hexDump" to dump, "offset" to actualOffset, "length" to actualLength,
            "format" to ws.format, "workspaceId" to workspaceId)
    }

    fun readSection(workspaceId: String, editSessionId: String? = null, sectionName: String): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val content = when (ws.format) {
            "elf" -> native.elfGetSectionContent(data, sectionName)
            "pe" -> native.peGetSectionContent(data, sectionName)
            else -> return err("UNSUPPORTED_FORMAT", "Cannot read section for format: ${ws.format}")
        }
        if (content.isEmpty()) {
            return err("SECTION_NOT_FOUND", "Section not found or empty: $sectionName", "section" to sectionName)
        }
        val hex = native.toHex(content, spaced = true)
        val preview = native.toHex(content.copyOfRange(0, minOf(256, content.size)), spaced = true)
        return ok("section" to sectionName, "size" to content.size.toLong(),
            "hexPreview" to preview, "fullHexAvailable" to true, "format" to ws.format)
    }

    fun searchBytes(workspaceId: String, editSessionId: String? = null, pattern: String): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.searchBytes(data, pattern)
        return ok("hits" to (result.optJSONArray("hits") ?: JSONArray()),
            "count" to result.optInt("count", 0), "pattern" to pattern)
    }

    fun searchStrings(workspaceId: String, editSessionId: String? = null, prefix: String = "", limit: Int = 200): JSONObject {
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "elf") {
            return err("UNSUPPORTED_OPERATION", "String extraction is currently only supported for ELF files")
        }
        val data = getSessionData(workspaceId, editSessionId) ?: ws.originalData
        val result = native.elfExtractStrings(data, limit.coerceIn(1, 10000))
        val allStrings = result.optJSONArray("strings") ?: JSONArray()
        val filtered = JSONArray()
        var count = 0
        for (i in 0 until allStrings.length()) {
            val s = allStrings.getJSONObject(i)
            val text = s.optString("text")
            if (prefix.isBlank() || text.contains(prefix, ignoreCase = true)) {
                filtered.put(s)
                count++
                if (count >= limit) break
            }
        }
        return ok("strings" to filtered, "count" to count, "prefix" to prefix)
    }

    // ── Editing ──

    fun editHex(workspaceId: String, editSessionId: String, offset: Long, hexData: String, dryRun: Boolean = false): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        // 安全修复: 加锁防止并发请求交叉修改同一编辑会话。
        synchronized(session.lock) {
            val patch = native.fromHex(hexData)
            if (patch.isEmpty()) {
                return err("INVALID_HEX", "Invalid hex data: $hexData")
            }
            // Preview old bytes
            val oldBytes = session.data.copyOfRange(
                offset.toInt().coerceIn(0, session.data.size),
                minOf(session.data.size, offset.toInt() + patch.size)
            )
            val oldHex = native.toHex(oldBytes, spaced = true)
            val newHex = native.toHex(patch, spaced = true)

            if (dryRun) {
                return ok("dryRun" to true, "offset" to offset, "oldHex" to oldHex, "newHex" to newHex, "size" to patch.size)
            }

            val result = when (ws.format) {
                "elf" -> native.elfPatchOffset(session.data, offset, patch)
                "pe" -> native.pePatchOffset(session.data, offset, patch)
                else -> return err("UNSUPPORTED_FORMAT", "Cannot edit format: ${ws.format}")
            }
            if (result.isEmpty()) {
                return err("PATCH_FAILED", "Patch failed: offset out of range or native error",
                    "offset" to offset, "size" to patch.size)
            }
            session.data = result
            addAudit(session, "edit_hex", "offset=$offset size=${patch.size}")
            return ok("applied" to true, "offset" to offset, "oldHex" to oldHex, "newHex" to newHex)
        }
    }

    fun editVa(workspaceId: String, editSessionId: String, va: Long, hexData: String, dryRun: Boolean = false): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        synchronized(session.lock) {
            val patch = native.fromHex(hexData)
            if (patch.isEmpty()) {
                return err("INVALID_HEX", "Invalid hex data: $hexData")
            }
            // Resolve VA to offset for preview
            val offset = when (ws.format) {
                "elf" -> native.elfVaToOffset(session.data, va)
                "pe" -> native.peVaToOffset(session.data, va)
                else -> -1L
            }
            if (offset < 0) {
                return err("OFFSET_OUT_OF_RANGE", "Virtual address 0x${va.toString(16)} does not map to a file offset",
                    "va" to "0x${va.toString(16)}")
            }
            val oldBytes = session.data.copyOfRange(
                offset.toInt().coerceIn(0, session.data.size),
                minOf(session.data.size, offset.toInt() + patch.size)
            )
            val oldHex = native.toHex(oldBytes, spaced = true)
            val newHex = native.toHex(patch, spaced = true)

            if (dryRun) {
                return ok("dryRun" to true, "va" to "0x${va.toString(16)}", "offset" to offset,
                    "oldHex" to oldHex, "newHex" to newHex, "size" to patch.size)
            }

            val result = when (ws.format) {
                "elf" -> native.elfPatchVa(session.data, va, patch)
                "pe" -> native.pePatchVa(session.data, va, patch)
                else -> return err("UNSUPPORTED_FORMAT", "Cannot edit format: ${ws.format}")
            }
            if (result.isEmpty()) {
                return err("PATCH_FAILED", "VA patch failed", "va" to "0x${va.toString(16)}")
            }
            session.data = result
            addAudit(session, "edit_va", "va=0x${va.toString(16)} offset=$offset size=${patch.size}")
            return ok("applied" to true, "va" to "0x${va.toString(16)}", "offset" to offset,
                "oldHex" to oldHex, "newHex" to newHex)
        }
    }

    fun editSection(workspaceId: String, editSessionId: String, sectionName: String, hexData: String, dryRun: Boolean = false): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        synchronized(session.lock) {
            val content = native.fromHex(hexData)
            if (content.isEmpty()) {
                return err("INVALID_HEX", "Invalid hex data")
            }
            if (dryRun) {
                return ok("dryRun" to true, "section" to sectionName,
                    "newSize" to content.size, "format" to ws.format)
            }
            val result = when (ws.format) {
                "elf" -> native.elfSetSectionContent(session.data, sectionName, content)
                "pe" -> native.peSetSectionContent(session.data, sectionName, content)
                else -> return err("UNSUPPORTED_FORMAT", "Cannot edit format: ${ws.format}")
            }
            if (result.isEmpty()) {
                return err("SECTION_NOT_FOUND", "Section not found or patch failed: $sectionName")
            }
            session.data = result
            addAudit(session, "edit_section", "section=$sectionName size=${content.size}")
            return ok("applied" to true, "section" to sectionName, "size" to content.size.toLong())
        }
    }

    fun editSymbol(workspaceId: String, editSessionId: String, op: String, name: String, newName: String = "", addr: Long = 0, dryRun: Boolean = false): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        if (ws.format != "elf") {
            return err("UNSUPPORTED_OPERATION", "Symbol editing is only supported for ELF files")
        }
        synchronized(session.lock) {
            if (dryRun) {
                return ok("dryRun" to true, "op" to op, "name" to name, "newName" to newName)
            }
            val result = when (op) {
                "rename" -> {
                    if (newName.length > name.length) {
                        return err("INVALID_ARGUMENT", "New name must be same length or shorter than original",
                            "oldLen" to name.length, "newLen" to newName.length)
                    }
                    native.elfRenameSymbol(session.data, name, newName)
                }
                "add" -> native.elfAddExportedFunction(session.data, addr, name)
                "remove" -> native.elfRemoveSymbol(session.data, name)
                else -> return err("UNKNOWN_ACTION", "Unknown symbol operation: $op", "op" to op)
            }
            if (result.isEmpty()) {
                return err("SYMBOL_NOT_FOUND", "Symbol operation failed: $op on '$name'")
            }
            session.data = result
            addAudit(session, "edit_symbol", "op=$op name=$name")
            return ok("applied" to true, "op" to op, "name" to name)
        }
    }

    // ── Build ──

    fun build(workspaceId: String, editSessionId: String, outputName: String = ""): JSONObject {
        val session = sessions[editSessionId]
            ?: return err("EDIT_SESSION_NOT_FOUND", "Edit session not found: $editSessionId")
        val ws = workspaces[workspaceId]
            ?: return err("WORKSPACE_NOT_FOUND", "Workspace not found: $workspaceId")
        // 安全修复: 加锁防止并发写入时读取到不一致的数据快照。
        val dataToWrite = synchronized(session.lock) { session.data.copyOf() }
        val name = if (outputName.isNotBlank()) outputName else "${ws.fileNameWithoutExt}_patched.${ws.fileExt}"
        val outFile = File(outputDir, name)
        // Avoid overwriting: append suffix if exists
        var finalName = name
        var counter = 1
        while (outFile.exists()) {
            val dotIdx = name.lastIndexOf('.')
            finalName = if (dotIdx > 0) "${name.substring(0, dotIdx)}_$counter${name.substring(dotIdx)}"
            else "${name}_$counter"
            counter++
            if (counter > 100) break
        }
        val finalFile = File(outputDir, finalName)
        finalFile.writeBytes(dataToWrite)
        val output = BuildOutput(finalName, finalFile.absolutePath, finalFile.length(), System.currentTimeMillis(), workspaceId)
        buildOutputs.add(output)
        Log.i(TAG, "Built ${finalName} (${dataToWrite.size} bytes)")
        return ok("outputPath" to finalFile.absolutePath, "outputName" to finalName,
            "size" to finalFile.length(), "workspaceId" to workspaceId)
    }

    fun listBuildOutputs(): JSONObject {
        val arr = JSONArray()
        buildOutputs.sortedByDescending { it.createdAt }.forEach { o ->
            arr.put(JSONObject()
                .put("fileName", o.fileName)
                .put("filePath", o.filePath)
                .put("sizeBytes", o.sizeBytes)
                .put("createdAt", o.createdAt)
                .put("workspaceId", o.workspaceId)
            )
        }
        return ok("outputs" to arr, "count" to arr.length())
    }

    // ── Health / status ──

    fun health(): JSONObject {
        return ok(
            "nativeAvailable" to native.available(),
            "nativeStatus" to native.loadStatus(),
            "openWorkspaces" to workspaces.size,
            "openSessions" to sessions.size,
            "buildOutputs" to buildOutputs.size,
            "outputDir" to outputDir.absolutePath,
            "workDir" to (workDirPath ?: outputDir.parent),
            "storagePermissionHint" to "如果无法访问文件，请确保已授予「所有文件访问」权限",
        )
    }

    // ── Helpers ──

    private fun getSessionData(workspaceId: String, editSessionId: String?): ByteArray? {
        if (editSessionId.isNullOrBlank()) return null
        return sessions[editSessionId]?.data ?: workspaces[workspaceId]?.originalData
    }

    private fun addAudit(session: EditSession, action: String, details: String) {
        session.auditLog.add(AuditEntry(System.currentTimeMillis(), action, details))
    }

    private fun generateId(): String = UUID.randomUUID().toString().take(12)

    private val Workspace.fileNameWithoutExt: String get() = fileName.substringBeforeLast('.')
    private val Workspace.fileExt: String get() = fileName.substringAfterLast('.', "so")

    companion object {
        private const val TAG = "BinaryEngine"
    }
}
