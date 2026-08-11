package com.soreverse.mcp

import android.content.Context
import com.soreverse.mcp.core.DeepAnalysisEvent
import com.soreverse.mcp.core.DeepAnalysisService
import com.soreverse.mcp.core.DeepReportStore
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.RikkaPart
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.service.McpForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun launchSoScan(
    context: Context,
    settings: SettingsStore,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    zh: Boolean,
): Job? {
    if (state.scanning) return null
    state.scanning = true
    state.message = if (zh) "正在扫描 SO 文件…" else "Scanning SO files…"
    return scope.launch {
        try {
            val loaded = withContext(Dispatchers.IO) { loadSoSources(context.applicationContext, settings.defaultLimit, zh) }
            state.soSources = loaded.first
            state.message = loaded.second
            state.perSoDetail = state.perSoDetail.filterKeys { path -> loaded.first.any { it.path == path } }
            state.expandedSoPath = state.expandedSoPath?.takeIf { path -> loaded.first.any { it.path == path } }
            state.scannedTreeUri = settings.treeUri?.toString() ?: "default"
            // 扫描完成后刷新工作区列表，确保手动打开和 MCP 工具打开的工作区都显示
            state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
        } finally {
            state.scanning = false
        }
    }
}

internal fun launchBasicAnalysis(
    context: Context,
    path: String,
    name: String,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    zh: Boolean,
): Job? {
    if (state.analyzingSoPath != null || state.deepAnalyzingPath != null) return null
    state.analyzingSoPath = path
    state.message = if (zh) "正在分析 $name…" else "Analyzing $name…"
    return scope.launch {
        try {
            val detail = withContext(Dispatchers.IO) { openSoForUi(context.applicationContext, path, zh) }
            state.message = detail.second
            val opened = detail.first
            if (opened != null) {
                state.perSoDetail = state.perSoDetail + (path to opened)
                state.expandedSoPath = path
                // 基础分析使用持久工作区，完成后刷新已打开的工作区列表使其立即显示
                state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
            } else {
                state.expandedSoPath = null
            }
        } finally {
            state.analyzingSoPath = null
        }
    }
}

/**
 * 从 APK 中提取 SO 条目并进行程序基础分析。
 *
 * 流程：提取 SO 到缓存 -> 打开 ELF 工作区 -> 分析 -> 关闭工作区 -> 存储结果。
 * 提取后的缓存路径会存入 [AnalyzeUiState.extractedSoPaths]，便于后续 AI 深度分析。
 */
internal fun launchBasicAnalysisFromApk(
    context: Context,
    apkPath: String,
    entryName: String,
    libName: String,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    zh: Boolean,
): Job? {
    if (state.analyzingSoPath != null || state.deepAnalyzingPath != null) return null
    val analysisKey = "$apkPath!$entryName"
    state.analyzingSoPath = analysisKey
    state.message = if (zh) "正在提取并分析 $libName…" else "Extracting and analyzing $libName…"
    state.detailMessage = if (zh) "正在提取并分析 $libName…" else "Extracting and analyzing $libName…"
    return scope.launch {
        try {
            val cachePath = withContext(Dispatchers.IO) {
                extractSoFromApkEntry(context.applicationContext, apkPath, entryName)
            }
            if (cachePath == null) {
                val errorMsg = if (zh) "无法从 APK 中提取 $libName" else "Cannot extract $libName from APK"
                state.message = errorMsg
                state.detailMessage = errorMsg
                return@launch
            }
            state.extractedSoPaths = state.extractedSoPaths + (analysisKey to cachePath)
            val detail = withContext(Dispatchers.IO) { openSoForUi(context.applicationContext, cachePath, zh) }
            state.message = detail.second
            state.detailMessage = ""
            val opened = detail.first
            if (opened != null) {
                state.perSoDetail = state.perSoDetail + (cachePath to opened)
                state.expandedSoPath = cachePath
                // 基础分析使用持久工作区，完成后刷新已打开的工作区列表使其立即显示
                state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
            } else {
                state.detailMessage = detail.second
            }
        } finally {
            state.analyzingSoPath = null
        }
    }
}

/**
 * 打开 SO 文件为非临时工作区，刷新工作区列表，然后自动进行基础分析。
 *
 * 适用于从工作区对话框的"可用 SO 文件"列表中点击"打开"的场景：
 * 先以 temporary=false 打开（使工作区持久化、显示在工作区列表中），
 * 再调用 [launchWorkspaceAnalysis] 进行分析并展示详情面板。
 */
internal fun openSoAndAnalyze(
    context: Context,
    path: String,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    zh: Boolean,
): Job? {
    if (state.analyzingSoPath != null || state.deepAnalyzingPath != null) return null
    state.analyzingSoPath = path
    state.message = if (zh) "正在打开 ${path.substringAfterLast('/')}…" else "Opening ${path.substringAfterLast('/')}…"
    return scope.launch {
        var analysisStarted = false
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = EngineProvider.get(context)
                    engine.open(path, temporary = false)
                }
            }
            val opened = result.getOrNull()
            if (opened != null && opened.optBoolean("ok", true)) {
                val workspaceId = opened.optString("workspaceId")
                val soName = opened.optString("soFileName", "lib.so")
                state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
                state.analyzingSoPath = null
                analysisStarted = true
                launchWorkspaceAnalysis(context, workspaceId, soName, state, scope, zh)
            } else {
                val msg = opened?.optJSONObject("error")?.optString("message")
                    ?: result.exceptionOrNull()?.message
                    ?: (if (zh) "打开失败" else "Open failed")
                state.message = msg
            }
        } catch (e: Exception) {
            state.message = e.message ?: (if (zh) "打开失败" else "Open failed")
        } finally {
            if (!analysisStarted) state.analyzingSoPath = null
        }
    }
}

/**
 * 对已打开的工作区进行程序基础分析（不会关闭工作区）。
 */
internal fun launchWorkspaceAnalysis(
    context: Context,
    workspaceId: String,
    name: String,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    zh: Boolean,
): Job? {
    if (state.analyzingSoPath != null || state.deepAnalyzingPath != null) return null
    state.analyzingSoPath = workspaceId
    state.message = if (zh) "正在分析 $name…" else "Analyzing $name…"
    return scope.launch {
        try {
            val detail = withContext(Dispatchers.IO) { analyzeWorkspaceForUi(context.applicationContext, workspaceId, zh) }
            state.message = detail.second
            val opened = detail.first
            if (opened != null) {
                state.perSoDetail = state.perSoDetail + (opened.path to opened)
                state.expandedSoPath = opened.path
            } else {
                state.expandedSoPath = null
            }
        } finally {
            state.analyzingSoPath = null
        }
    }
}

internal fun launchDeepAnalysis(
    context: Context,
    path: String,
    request: String,
    settings: SettingsStore,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    deepService: DeepAnalysisService,
    zh: Boolean,
): Job? {
    if (state.deepAnalyzingPath != null) return null
    val turnRequest = buildDeepTurnRequest(
        request = request,
        messages = state.deepMessages,
        historySoftLimit = settings.aiHistorySoftLimit,
    )
    if (request.isBlank()) state.deepMessages = emptyList()
    state.deepTargetPath = path
    state.restoreDeepReportOnAnalyzeEntry = false
    state.showDeepPanel = false
    state.showDeepReport = true
    if (!McpForegroundService.isRunning()) {
        state.deepMessages = state.deepMessages + DeepChatMessage(
            id = System.currentTimeMillis(),
            role = DeepChatRole.ASSISTANT,
            text = "",
            error = if (zh) "请先开启 MCP 服务后再进行 AI 深度分析" else "Start the MCP service before AI deep analysis",
        )
        return null
    }
    if (settings.aiApiKey.isBlank() || settings.aiEndpoint.isBlank() || settings.aiModel.isBlank()) {
        state.deepMessages = state.deepMessages + DeepChatMessage(
            id = System.currentTimeMillis(),
            role = DeepChatRole.ASSISTANT,
            text = "",
            error = if (zh) "请先在设置页配置 AI 端点、API Key 和模型" else "Configure AI endpoint, API key and model in Settings first",
        )
        return null
    }
    val userText = request.ifBlank {
        if (zh) "请对 ${path.substringAfterLast('/')} 进行 AI 深度分析" else "Deeply analyze ${path.substringAfterLast('/')}"
    }
    val assistantId = System.currentTimeMillis() + 1
    state.deepMessages = state.deepMessages +
        DeepChatMessage(System.currentTimeMillis(), DeepChatRole.USER, userText) +
        DeepChatMessage(assistantId, DeepChatRole.ASSISTANT, "", streaming = true)
    state.deepAnalyzingPath = path
    state.deepEvents = emptyList()
    state.deepReport = ""
    state.deepEvidencePreview = ""
    state.deepError = ""
    deepService.resetReportDraft(resetWorkspace = request.isBlank())
    return scope.launch {
        val collector = launch {
            deepService.events.collect { event ->
                if (event.kind != DeepAnalysisEvent.Kind.DONE && event.kind != DeepAnalysisEvent.Kind.TEXT) {
                    state.deepMessages = state.deepMessages.map { message ->
                        if (message.id == assistantId) message.copy(events = (message.events + event).takeLast(100)) else message
                    }
                }
            }
        }
        val draftCollector = launch {
            deepService.partsDraft.collect { parts ->
                if (parts.isNotEmpty()) {
                    val draft = parts.filterIsInstance<RikkaPart.Text>().joinToString("") { it.text }
                    state.deepReport = draft
                    state.deepMessages = state.deepMessages.map { message ->
                        if (message.id == assistantId) message.copy(text = draft, parts = parts) else message
                    }
                }
            }
        }
        try {
            deepService.analyze(path, settings, zh, turnRequest)
                .onSuccess { report ->
                    state.deepReport = report
                    state.deepMessages = state.deepMessages.map { message ->
                        if (message.id == assistantId) message.copy(text = report, streaming = false) else message
                    }
                    deepService.workspaceId.value.takeIf(String::isNotBlank)?.let { workspaceId ->
                        DeepReportStore.save(
                            context.applicationContext,
                            workspaceId,
                            deepReportSnapshot(path, settings.aiModel, state.deepMessages),
                        )
                    }
                }
                .onFailure { error ->
                    val messageText = error.message ?: if (zh) "AI 深度分析失败" else "AI deep analysis failed"
                    state.deepMessages = state.deepMessages.map { message ->
                        if (message.id == assistantId) message.copy(streaming = false, error = messageText) else message
                    }
                }
        } catch (_: CancellationException) {
            state.deepMessages = state.deepMessages.map { message ->
                if (message.id == assistantId) message.copy(streaming = false) else message
            }
        } finally {
            collector.cancel()
            draftCollector.cancel()
            state.deepAnalyzingPath = null
            state.deepJob = null
        }
    }.also { state.deepJob = it }
}

internal fun buildDeepTurnRequest(
    request: String,
    messages: List<DeepChatMessage>,
    historySoftLimit: Int,
): String {
    if (request.isBlank()) return request
    val history = messages
        .takeLast(6)
        .joinToString("\n\n") { message ->
            val role = if (message.role == DeepChatRole.USER) "用户" else "AI"
            "$role: ${message.text}"
        }
        .takeLast(historySoftLimit.coerceAtLeast(4_000))
    return if (history.isBlank()) request else """以下是最近对话上下文：
$history

用户本轮问题：$request"""
}
