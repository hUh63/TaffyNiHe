package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soreverse.mcp.core.DeepAnalysisService
import com.soreverse.mcp.core.DeepReportStore
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AnalyzeTab(
    t: UiText,
    settings: SettingsStore,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    deepService: DeepAnalysisService,
    backProgress: Float,
    onLeaveDeepReport: () -> Unit,
) {
    val context = LocalContext.current
    val deepChatListState = rememberLazyListState()
    var followDeepOutput by remember { mutableStateOf(true) }
    val deepAtBottom by remember {
        derivedStateOf {
            val info = deepChatListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index >= info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 2
        }
    }
    var showWorkspaces by remember { mutableStateOf(false) }
    var manualSoPath by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf("") }
    var manualInfo by remember { mutableStateOf("") }
    // settings.treeUri 由 SharedPreferences 支撑，不是 Compose 可观察状态，直接作为 LaunchedEffect
    // 的 key 不会在选择目录后可靠触发重组。这里镜像成快照状态，确保一选目录就立刻重新扫描。
    var treeUriKey by remember { mutableStateOf(settings.treeUri?.toString()) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            settings.treeUri = uri
            settings.useDefaultWorkDir = false
            EngineProvider.setWorkDirectory(context, uri)
            state.scannedTreeUri = null
            treeUriKey = uri.toString()
            // 立即触发扫描，不依赖 LaunchedEffect 重新组合
            launchSoScan(context, settings, state, scope, t.zh)
        }
    }
    val pickSoFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val path = uri.toString()
            val isApk = path.substringBefore('?').endsWith(".apk", ignoreCase = true)
            manualSoPath = path
            manualError = ""
            manualInfo = ""
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (isApk) {
                            analyzeApkForUi(context.applicationContext, path, t.zh)
                        } else {
                            // 直接将 content:// URI 传给引擎，引擎内部会复制到缓存目录再以本地路径打开
                            val engine = EngineProvider.get(context)
                            engine.open(path, temporary = false)
                        }
                    }
                }
                val opened = result.getOrNull()
                if (opened != null && opened.optBoolean("ok", true)) {
                    if (isApk) {
                        // APK 分析结果存入 state，同时显示在工作区和分析页
                        val entryCount = opened.optInt("entryCount", 0)
                        val nativeLibs = opened.optJSONArray("nativeLibraries")?.length() ?: 0
                        val dexFiles = opened.optJSONArray("dexFiles")?.length() ?: 0
                        val apkName = path.substringAfterLast("%2F").substringAfterLast('/').substringBefore('?').ifBlank { "apk" }
                        val summary = if (t.zh)
                            "$entryCount 条目, $nativeLibs 原生库, $dexFiles DEX"
                        else
                            "$entryCount entries, $nativeLibs libs, $dexFiles DEX"
                        state.apkResults = state.apkResults + (path to opened)
                        manualInfo = if (t.zh)
                            "APK 分析完成：$summary"
                        else
                            "APK analyzed: $summary"
                        manualSoPath = ""
                        manualError = ""
                    } else {
                        // SO 文件成功打开工作区：关闭对话框、刷新列表、自动进行基础分析
                        val workspaceId = opened.optString("workspaceId")
                        val soName = opened.optString("soFileName", "lib.so")
                        manualSoPath = ""
                        manualError = ""
                        manualInfo = ""
                        showWorkspaces = false
                        state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
                        launchWorkspaceAnalysis(context, workspaceId, soName, state, scope, t.zh)
                    }
                } else {
                    val msg = opened?.optJSONObject("error")?.optString("message")
                        ?: result.exceptionOrNull()?.message
                        ?: (if (t.zh) "打开失败" else "Open failed")
                    manualError = msg
                    state.message = msg
                }
            }
        }
    }
    val pickSoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            settings.treeUri = uri
            settings.useDefaultWorkDir = false
            EngineProvider.setWorkDirectory(context, uri)
            state.scannedTreeUri = null
            manualSoPath = uri.toString()
            manualError = ""
            manualInfo = ""
            showWorkspaces = false
            treeUriKey = uri.toString()
            // 立即触发扫描，不依赖 LaunchedEffect 重新组合
            launchSoScan(context, settings, state, scope, t.zh)
        }
    }

    fun openManualPath() {
        val path = manualSoPath.trim()
        if (path.isBlank()) {
            manualError = if (t.zh) "请输入或选择 SO/APK 文件路径" else "Please enter or pick a SO/APK file path"
            return
        }
        val isApk = path.substringBefore('?').endsWith(".apk", ignoreCase = true)
        manualInfo = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isApk) {
                        analyzeApkForUi(context.applicationContext, path, t.zh)
                    } else {
                        // 直接将路径传给引擎，引擎会处理 content:// URI 和本地路径
                        val engine = EngineProvider.get(context)
                        engine.open(path, temporary = false)
                    }
                }
            }
            val opened = result.getOrNull()
            if (opened != null && opened.optBoolean("ok", true)) {
                if (isApk) {
                    val entryCount = opened.optInt("entryCount", 0)
                    val nativeLibs = opened.optJSONArray("nativeLibraries")?.length() ?: 0
                    val dexFiles = opened.optJSONArray("dexFiles")?.length() ?: 0
                    val summary = if (t.zh)
                        "$entryCount 条目, $nativeLibs 原生库, $dexFiles DEX"
                    else
                        "$entryCount entries, $nativeLibs libs, $dexFiles DEX"
                    state.apkResults = state.apkResults + (path to opened)
                    manualInfo = if (t.zh) "APK 分析完成：$summary" else "APK analyzed: $summary"
                    manualSoPath = ""
                    manualError = ""
                } else {
                    // SO 文件成功打开工作区：关闭对话框、刷新列表、自动进行基础分析
                    val workspaceId = opened.optString("workspaceId")
                    val soName = opened.optString("soFileName", "lib.so")
                    manualSoPath = ""
                    manualError = ""
                    manualInfo = ""
                    showWorkspaces = false
                    state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
                    launchWorkspaceAnalysis(context, workspaceId, soName, state, scope, t.zh)
                }
            } else {
                val msg = opened?.optJSONObject("error")?.optString("message")
                    ?: result.exceptionOrNull()?.message
                    ?: (if (t.zh) "打开失败" else "Open failed")
                manualError = msg
                state.message = msg
            }
        }
    }

    fun startDeepAnalysis(path: String, request: String = "") {
        launchDeepAnalysis(context, path, request, settings, state, scope, deepService, t.zh)
    }

    LaunchedEffect(treeUriKey, settings.defaultLimit) {
        val treeKey = treeUriKey
        if (treeKey == null) {
            state.message = if (t.zh) "尚未选择目录，请先选择工作目录" else "No directory selected. Choose a work directory first."
        } else if (state.scannedTreeUri != treeKey && !state.scanning) {
            settings.treeUri?.let { EngineProvider.setWorkDirectory(context, it) }
            launchSoScan(context, settings, state, scope, t.zh)
        }
    }

    LaunchedEffect(deepChatListState.isScrollInProgress, deepAtBottom) {
        when {
            deepAtBottom -> followDeepOutput = true
            deepChatListState.isScrollInProgress && deepChatListState.lastScrolledBackward -> followDeepOutput = false
        }
    }

    LaunchedEffect(state.showDeepReport) {
        if (state.showDeepReport) followDeepOutput = true
    }

    val latestDeepMessage = state.deepMessages.lastOrNull()
    LaunchedEffect(
        state.deepMessages.size,
        latestDeepMessage?.text?.length,
        latestDeepMessage?.parts,
        latestDeepMessage?.events?.size,
        latestDeepMessage?.error,
        followDeepOutput,
    ) {
        if (state.showDeepReport && state.deepMessages.isNotEmpty() && followDeepOutput) {
            deepChatListState.scrollToItem(state.deepMessages.size)
        }
    }

    LaunchedEffect(deepChatListState, state.showDeepReport, followDeepOutput) {
        snapshotFlow {
            deepChatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                Triple(it.index, it.offset, it.size)
            }
        }.distinctUntilChanged().collect {
            if (state.showDeepReport && followDeepOutput && !deepAtBottom && state.deepMessages.isNotEmpty()) {
                deepChatListState.scrollToItem(state.deepMessages.size)
            }
        }
    }

    LaunchedEffect(showWorkspaces) {
        if (showWorkspaces) state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (t.zh) "分析" else "Analyze",
            subtitle = if (t.zh) "SO 文件程序基础分析" else "Program-level SO analysis",
            trailing = {
                Row {
                    IconButton(
                        enabled = !state.scanning && state.analyzingSoPath == null && state.deepAnalyzingPath == null,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val engine = EngineProvider.get(context)
                                    engine.clearCaches()
                                    engine.flutterBlutter(org.json.JSONObject().put("action", "prune").put("olderThanMillis", 0))
                                }
                                state.workspaces = emptyList()
                                state.soSources = emptyList()
                                state.perSoDetail = emptyMap()
                                state.expandedSoPath = null
                                state.scannedTreeUri = null
                                state.message = if (t.zh) "缓存、工作区和已结束的 Blutter 结果已清理" else "Caches, workspaces, and completed Blutter results cleared"
                            }
                        },
                    ) {
                        Icon(Icons.Default.DeleteSweep, if (t.zh) "清理分析资源" else "Clear analysis resources")
                    }
                    TextButton(onClick = { showWorkspaces = true }) {
                        Icon(Icons.Default.Storage, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (t.zh) "工作区" else "Workspaces")
                    }
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                Row(Modifier.padding(horizontal = LocalUiMetrics.current.pagePad, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.scanning) (if (t.zh) "扫描中…" else "Scanning…") else if (state.soSources.isNotEmpty() || state.apkResults.isNotEmpty()) (if (t.zh) "共计 ${state.soSources.size} 个 SO${if (state.apkResults.isNotEmpty()) "，${state.apkResults.size} 个 APK" else ""}" else "${state.soSources.size} SO${if (state.apkResults.isNotEmpty()) ", ${state.apkResults.size} APK" else ""}") else if (state.scannedTreeUri != null && state.message.isNotBlank()) state.message else (if (t.zh) "选择工作区后自动扫描" else "Choose a workspace to scan"),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (settings.treeUri == null) {
                        TextButton(onClick = { pickTree.launch(null) }) { Text(if (t.zh) "选择目录" else "Choose") }
                    }
                }
            }
            if (state.scanning) {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = LocalUiMetrics.current.pagePad))
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (state.analyzingSoPath != null) {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                    Text(
                        state.message.ifBlank { if (t.zh) "正在进行程序基础分析…" else "Running program analysis…" },
                        modifier = Modifier.padding(horizontal = LocalUiMetrics.current.pagePad, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (state.soSources.isEmpty() && state.apkResults.isEmpty()) {
                item {
                    Text(
                        state.message.ifBlank { if (t.zh) "未找到 SO 文件" else "No SO files found" },
                        modifier = Modifier.padding(horizontal = LocalUiMetrics.current.pagePad, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                itemsIndexed(state.soSources, key = { _, src -> src.path }) { idx, src ->
                    if (idx > 0) GroupDivider()
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = LocalUiMetrics.current.pagePad, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IndexedBadge(idx)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(src.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${if (t.zh) (if (src.source == "filesystem") "文件系统" else src.source) else src.source} ${src.abi} ${src.architecture}/${src.bits} ${formatBytes(src.size)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(src.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(
                                    enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null,
                                    onClick = {
                                        if (state.perSoDetail[src.path] != null) {
                                            state.expandedSoPath = src.path
                                        } else {
                                            launchBasicAnalysis(context, src.path, src.name, state, scope, t.zh)
                                        }
                                    },
                                ) {
                                    Text(
                                        when {
                                            state.analyzingSoPath == src.path -> if (t.zh) "分析中" else "Analyzing"
                                            else -> if (t.zh) "程序基础分析" else "Basic analysis"
                                        },
                                    )
                                }
                                TextButton(
                                    enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null,
                                    onClick = { startDeepAnalysis(src.path) },
                                ) {
                                    Text(
                                        if (state.deepAnalyzingPath == src.path) {
                                            if (t.zh) "AI 分析中" else "AI analyzing"
                                        } else {
                                            if (t.zh) "AI 深度分析" else "AI deep analysis"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // APK 分析结果条目
                state.apkResults.entries.forEachIndexed { idx, (apkPath, apkData) ->
                    item(key = "apk_$apkPath") {
                        GroupDivider()
                        val apkName = apkPath.substringAfterLast("%2F").substringAfterLast('/').substringBefore('?').ifBlank { "apk" }
                        val entryCount = apkData.optInt("entryCount", 0)
                        val nativeLibs = apkData.optJSONArray("nativeLibraries")?.length() ?: 0
                        val dexFiles = apkData.optJSONArray("dexFiles")?.length() ?: 0
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = LocalUiMetrics.current.pagePad, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IndexedBadge(state.soSources.size + idx)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(apkName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("APK ${entryCount} ${if (t.zh) "条目" else "entries"}, $nativeLibs ${if (t.zh) "原生库" else "libs"}, $dexFiles DEX", style = MaterialTheme.typography.labelSmall, color = AppPalette.orange)
                                Text(apkPath, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = { state.expandedSoPath = apkPath }) {
                                    Text(if (t.zh) "查看分析" else "View")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // SO 详情面板
    state.expandedSoPath?.let { expandedPath ->
        // APK 分析详情
        state.apkResults[expandedPath]?.let { apkData ->
            val apkName = expandedPath.substringAfterLast("%2F").substringAfterLast('/').substringBefore('?').ifBlank { "apk" }
            Surface(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * backProgress
                    alpha = 1f - 0.12f * backProgress
                },
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(
                        title = apkName,
                        subtitle = "APK ${if (t.zh) "分析" else "Analysis"}",
                        showBack = true,
                        onBack = { state.expandedSoPath = null },
                    )
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = LocalUiMetrics.current.pagePad).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val entryCount = apkData.optInt("entryCount", 0)
                        val nativeLibs = apkData.optJSONArray("nativeLibraries")
                        val dexFiles = apkData.optJSONArray("dexFiles")
                        SurfacePanel {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (t.zh) "概览" else "Overview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("${if (t.zh) "条目数" else "Entries"}: $entryCount", style = MaterialTheme.typography.bodyMedium)
                                Text("${if (t.zh) "原生库" else "Native libraries"}: ${nativeLibs?.length() ?: 0}", style = MaterialTheme.typography.bodyMedium)
                                Text("${if (t.zh) "DEX 文件" else "DEX files"}: ${dexFiles?.length() ?: 0}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        // 分析进度和消息（提取 SO 或基础分析时显示）
                        val apkAnalyzing = state.analyzingSoPath != null && state.analyzingSoPath!!.contains(expandedPath)
                        if (apkAnalyzing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (apkAnalyzing || state.detailMessage.isNotBlank()) {
                            Text(
                                state.detailMessage.ifBlank { if (t.zh) "正在分析…" else "Analyzing…" },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (apkAnalyzing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        // 原生库列表（可点击提取并分析）
                        if (nativeLibs != null && nativeLibs.length() > 0) {
                            Text(if (t.zh) "原生库（点击分析）" else "Native Libraries (tap to analyze)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            SurfacePanel {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    (0 until nativeLibs.length()).forEach { i ->
                                        val lib = nativeLibs.optJSONObject(i)
                                        val libName = lib?.optString("name", "?") ?: "?"
                                        val libAbi = lib?.optString("abi", "?") ?: "?"
                                        val libSize = formatBytes(lib?.optLong("size", 0L) ?: 0L)
                                        val libEntry = lib?.optString("entry", "") ?: ""
                                        // 用 entry 路径作为唯一 key（同一 APK 内不同 ABI 的同名 SO）
                                        val analysisKey = if (libEntry.isNotBlank()) "$expandedPath!$libEntry" else ""
                                        val extractedPath = state.extractedSoPaths[analysisKey]
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("  $libName ($libAbi, $libSize)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                            TextButton(
                                                enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null && analysisKey.isNotBlank(),
                                                onClick = {
                                                    if (extractedPath != null && state.perSoDetail[extractedPath] != null) {
                                                        state.expandedSoPath = extractedPath
                                                    } else if (extractedPath != null) {
                                                        launchBasicAnalysis(context, extractedPath, libName, state, scope, t.zh)
                                                    } else {
                                                        launchBasicAnalysisFromApk(context, expandedPath, libEntry, libName, state, scope, t.zh)
                                                    }
                                                },
                                            ) {
                                                Text(
                                                    when {
                                                        state.analyzingSoPath == analysisKey -> if (t.zh) "分析中" else "Analyzing"
                                                        extractedPath != null && state.perSoDetail.containsKey(extractedPath) -> if (t.zh) "查看" else "View"
                                                        else -> if (t.zh) "分析" else "Analyze"
                                                    },
                                                )
                                            }
                                            TextButton(
                                                enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null && analysisKey.isNotBlank(),
                                                onClick = {
                                                    if (extractedPath != null) {
                                                        startDeepAnalysis(extractedPath)
                                                    } else {
                                                        scope.launch {
                                                            val extracted = withContext(Dispatchers.IO) {
                                                                extractSoFromApkEntry(context.applicationContext, expandedPath, libEntry)
                                                            }
                                                            if (extracted != null) {
                                                                state.extractedSoPaths = state.extractedSoPaths + (analysisKey to extracted)
                                                                startDeepAnalysis(extracted)
                                                            }
                                                        }
                                                    }
                                                },
                                            ) {
                                                Text(if (t.zh) "AI 深度分析" else "AI deep analysis")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // DEX 文件列表
                        if (dexFiles != null && dexFiles.length() > 0) {
                            Text("DEX", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            SurfacePanel {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    (0 until dexFiles.length()).forEach { i ->
                                        val dex = dexFiles.optJSONObject(i)
                                        Text("  ${dex?.optString("name", "classes.dex")} (${formatBytes(dex?.optLong("size", 0L) ?: 0L)})", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } ?: state.perSoDetail[expandedPath]?.let { selectedDetail ->
            // ELF SO 详情面板
            Surface(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * backProgress
                    alpha = 1f - 0.12f * backProgress
                },
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(
                        title = selectedDetail.name,
                        subtitle = if (t.zh) "ELF 程序基础分析" else "ELF basic analysis",
                        showBack = true,
                        onBack = { state.expandedSoPath = null },
                        trailing = {
                            TextButton(
                                enabled = state.deepAnalyzingPath == null,
                                onClick = { startDeepAnalysis(expandedPath) },
                            ) {
                                Text(if (t.zh) "AI 深度分析" else "AI deep analysis")
                            }
                        },
                    )
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = LocalUiMetrics.current.pagePad).padding(bottom = 12.dp),
                    ) {
                        ElfOverviewPanel(detail = selectedDetail, zh = t.zh, onCopy = { text -> copy(context, text, t.copied) })
                    }
                }
            }
        }
    }
    if (state.showDeepReport) {
        Surface(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = size.width * backProgress
                alpha = 1f - 0.12f * backProgress
            },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(
                    title = state.deepTargetPath.substringAfterLast('/').ifBlank { if (t.zh) "AI 深度分析" else "AI Deep Analysis" },
                    subtitle = if (state.deepAnalyzingPath != null) (if (t.zh) "正在生成" else "Generating") else settings.aiModel,
                    showBack = true,
                    onBack = onLeaveDeepReport,
                    trailing = {
                        if (state.deepMessages.isNotEmpty()) {
                            IconButton(onClick = {
                                state.deepMessages.lastOrNull { it.role == DeepChatRole.ASSISTANT }?.text?.let { copy(context, it, t.copied) }
                            }) {
                                Icon(Icons.Default.ContentCopy, if (t.zh) "复制最新回复" else "Copy latest reply")
                            }
                        }
                    },
                )
                LazyColumn(
                    state = deepChatListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = LocalUiMetrics.current.pagePad, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(state.deepMessages, key = { it.id }) { message ->
                        DeepChatMessageItem(message = message, zh = t.zh)
                    }
                    item(key = "deep-output-bottom") {
                        Spacer(Modifier.height(1.dp))
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                    tonalElevation = 1.dp,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = state.deepInput,
                            onValueChange = { state.deepInput = it },
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp, max = 132.dp),
                            placeholder = { Text(if (t.zh) "继续提问" else "Ask a follow-up", maxLines = 1) },
                            shape = RoundedCornerShape(18.dp),
                            maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            ),
                        )
                        FilledIconButton(
                            onClick = {
                                if (state.deepAnalyzingPath != null) {
                                    state.deepJob?.cancel(CancellationException("Stopped by user"))
                                } else {
                                    val input = state.deepInput.trim()
                                    if (input.isNotBlank() && state.deepTargetPath.isNotBlank()) {
                                        state.deepInput = ""
                                        startDeepAnalysis(state.deepTargetPath, input)
                                    }
                                }
                            },
                            enabled = state.deepAnalyzingPath != null || state.deepInput.isNotBlank(),
                            modifier = Modifier.size(50.dp),
                            shape = CircleShape,
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (state.deepAnalyzingPath != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ),
                        ) {
                            Icon(
                                if (state.deepAnalyzingPath != null) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                if (state.deepAnalyzingPath != null) (if (t.zh) "停止" else "Stop") else (if (t.zh) "发送" else "Send"),
                            )
                        }
                    }
                }
            }
        }
    }
    }
    if (showWorkspaces) {
        Dialog(
            onDismissRequest = { showWorkspaces = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 标题栏 + 关闭按钮
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (t.zh) "工作区" else "Workspaces",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { showWorkspaces = false }) {
                            Icon(Icons.Default.Close, contentDescription = if (t.zh) "关闭" else "Close")
                        }
                    }
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 手动建立工作区
                        Text(
                            if (t.zh) "手动建立工作区" else "Create workspace manually",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = manualSoPath,
                            onValueChange = { manualSoPath = it; manualError = ""; manualInfo = "" },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(if (t.zh) "输入 SO/APK 文件路径或文件夹路径" else "Enter SO/APK file or folder path", maxLines = 1) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            isError = manualError.isNotBlank(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { openManualPath() },
                            ),
                            supportingText = if (manualError.isNotBlank()) {
                                { Text(manualError, color = MaterialTheme.colorScheme.error) }
                            } else if (manualInfo.isNotBlank()) {
                                { Text(manualInfo, color = MaterialTheme.colorScheme.primary) }
                            } else null,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { pickSoFile.launch(arrayOf("*/*")) }) {
                                Text(if (t.zh) "选择文件" else "Pick file")
                            }
                            TextButton(onClick = { pickSoFolder.launch(null) }) {
                                Text(if (t.zh) "选择文件夹" else "Pick folder")
                            }
                        }
                        androidx.compose.material3.HorizontalDivider()
                        // 当前工作目录信息
                        val dirDisplay = settings.treeUri?.let { uri ->
                            uri.toString().substringAfterLast("%2F")
                                .substringAfterLast('/').substringBefore('?')
                                .ifBlank { uri.toString().takeLast(30) }
                        }
                        if (dirDisplay != null) {
                            Text(
                                if (t.zh) "当前目录：$dirDisplay（${state.soSources.size} 个 SO 可分析）" else "Directory: $dirDisplay (${state.soSources.size} SO available)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 可用 SO 文件（扫描到但尚未打开为工作区的）
                        val openedPaths = state.workspaces.map { it.path }.toSet()
                        val availableSoSources = state.soSources.filter { it.path !in openedPaths }
                        if (availableSoSources.isNotEmpty()) {
                            Text(
                                if (t.zh) "可用 SO 文件 (${availableSoSources.size})" else "Available SO (${availableSoSources.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            availableSoSources.take(15).forEach { src ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(src.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${src.abi} ${src.architecture}/${src.bits} ${formatBytes(src.size)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(
                                        enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null,
                                        onClick = {
                                            showWorkspaces = false
                                            openSoAndAnalyze(context, src.path, state, scope, t.zh)
                                        },
                                    ) { Text(if (t.zh) "打开" else "Open") }
                                }
                            }
                            if (availableSoSources.size > 15) {
                                Text(
                                    if (t.zh) "…还有 ${availableSoSources.size - 15} 个，请在分析页查看" else "…${availableSoSources.size - 15} more, see Analyze tab",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 已打开的工作区
                        val totalWorkspaces = state.workspaces.size + state.apkResults.size
                        Text(
                            if (t.zh) "已打开的工作区 ($totalWorkspaces)" else "Open workspaces ($totalWorkspaces)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (totalWorkspaces == 0) {
                            Text(
                                if (t.zh) "暂无工作区。选择文件或点击上方「打开」创建工作区。" else "No workspaces. Pick a file or tap 'Open' above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (totalWorkspaces > 0) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                state.workspaces.forEach { ws ->
                                                    EngineProvider.get(context).close(ws.id)
                                                    DeepReportStore.remove(context.applicationContext, ws.id)
                                                }
                                            }
                                            state.apkResults = emptyMap()
                                            state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
                                        }
                                    }) { Text(if (t.zh) "全部清除" else "Clear all") }
                                }
                            }
                            // SO 工作区列表
                            state.workspaces.forEach { ws ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(ws.name, fontWeight = FontWeight.SemiBold)
                                        Text("${ws.architecture}/${ws.bits} ${ws.abi}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null,
                                        onClick = {
                                            showWorkspaces = false
                                            launchWorkspaceAnalysis(context, ws.id, ws.name, state, scope, t.zh)
                                        },
                                    ) { Text(if (t.zh) "基础分析" else "Basic") }
                                    TextButton(
                                        enabled = state.analyzingSoPath == null && state.deepAnalyzingPath == null,
                                        onClick = {
                                            showWorkspaces = false
                                            startDeepAnalysis(ws.path)
                                        },
                                    ) { Text(if (t.zh) "AI 分析" else "AI") }
                                    if (ws.hasLocalAiReport) {
                                        TextButton(onClick = {
                                            DeepReportStore.load(context.applicationContext, ws.id)?.let { snapshot ->
                                                restoreDeepReport(state, snapshot)
                                                showWorkspaces = false
                                            }
                                        }) { Text(if (t.zh) "报告" else "Report") }
                                    }
                                    TextButton(onClick = {
                                        EngineProvider.get(context).close(ws.id)
                                        DeepReportStore.remove(context.applicationContext, ws.id)
                                        scope.launch {
                                            state.workspaces = withContext(Dispatchers.IO) { loadWorkspaces(context.applicationContext) }
                                        }
                                    }) { Text(if (t.zh) "关闭" else "Close") }
                                }
                            }
                            // APK 分析结果列表
                            state.apkResults.forEach { (apkPath, apkData) ->
                                val apkName = apkPath.substringAfterLast("%2F").substringAfterLast('/').substringBefore('?').ifBlank { "apk" }
                                val entryCount = apkData.optInt("entryCount", 0)
                                val nativeLibs = apkData.optJSONArray("nativeLibraries")?.length() ?: 0
                                val dexFiles = apkData.optJSONArray("dexFiles")?.length() ?: 0
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(apkName, fontWeight = FontWeight.SemiBold)
                                        Text("APK ${entryCount} ${if (t.zh) "条目" else "entries"}, $nativeLibs ${if (t.zh) "原生库" else "libs"}, $dexFiles DEX", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = {
                                        // 查看 APK 分析详情（含原生库进一步分析）
                                        state.expandedSoPath = apkPath
                                        showWorkspaces = false
                                    }) { Text(if (t.zh) "查看分析" else "Analyze") }
                                    TextButton(onClick = {
                                        state.apkResults = state.apkResults - apkPath
                                    }) { Text(if (t.zh) "关闭" else "Close") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
