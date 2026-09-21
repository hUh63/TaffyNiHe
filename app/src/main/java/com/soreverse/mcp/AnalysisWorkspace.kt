package com.soreverse.mcp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CoroutineScope
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 选择文件的 ActivityResultContract：与 OpenDocument 相同，但额外请求
 * 读写权限（FLAG_GRANT_READ|WRITE|PERSISTABLE），以便引擎对所选文件进行读写。
 * 同包共享（AnalyzeTab 也使用），故为 internal。
 */
internal class OpenDocumentReadWriteContract : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

internal data class ToolDef(val key: String, val labelZh: String, val labelEn: String)

internal val toolDefs = listOf(
    ToolDef("decompile", "反编译", "Decompile"),
    ToolDef("unpack", "脱壳", "Unpack"),
    ToolDef("soanalyze", "SO分析", "SO"),
    ToolDef("emulate", "模拟", "Emulate"),
    ToolDef("frida", "Frida", "Frida"),
    ToolDef("rebuild", "回编", "Rebuild"),
    ToolDef("editor", "编辑", "Editor"),
)

/** 需要地址栏的工具功能列表 */
private val addrNeededTools = setOf("decompile", "emulate", "editor")

/**
 * 塔菲逆核 · 分析页（仿 Explorer So / Exbin 的信息架构重构）
 *
 * 布局（与 Exbin 的 NavigationRail + 内容区 一致）：
 * ```
 * ┌────┬──────────────────────────────────────────────┐
 * │ 导 │ 顶部条：当前函数名(可点换) + 地址 + 刷新/对象树/输出 │
 * │ 航 ├──────────────────────────────────────────────┤
 * │ 栏 │               当前视图内容区                    │
 * │56dp│                                              │
 * └────┴──────────────────────────────────────────────┘
 * ```
 *
 * 交互主线：左侧 56dp 图标导航切视图 → 「函数」列表里选中函数（全局唯一选中态）→
 * 「反汇编 / 伪C / CFG」三个视图都以该选中函数为目标。
 * 结果标签页（addTab / ResultStream）与工具控制台（ToolConsole）的原有机制不变，
 * 分别挂在「结果」「工具」两个导航项上；对象树与输出面板并入对应视图与顶部条，能力不丢。
 */
@Composable
internal fun AnalysisWorkspace(
    t: UiText,
    state: WorkspaceState,
    context: android.content.Context,
    onOpenTask: () -> Unit,
    /** AI 深度分析入口（对当前任务主文件发起，MainActivity 挂载聊天页）。 */
    onAiAnalyze: (String) -> Unit,
) {
    val zh = t.zh
    val tools = state.tools

    // ── 弹层状态 ──
    var showToolPicker by remember { mutableStateOf(false) }
    var showTree by remember { mutableStateOf(false) }
    var showString by remember { mutableStateOf<AnalysisRow?>(null) }

    // 对象树状态（沿用原 loadTree / extractNames 逻辑，只是入口搬到顶部条 + 函数视图）
    val treeScope = rememberCoroutineScope()
    var treeOpen by remember { mutableStateOf(setOf<String>()) }
    var treeChildren by remember { mutableStateOf(mapOf<String, List<String>>()) }
    var treeLoading by remember { mutableStateOf("") }
    var treeSel by remember { mutableStateOf("") }

    fun loadTree(key: String) {
        val ws = tools.sharedWorkspaceId
        if (ws.isBlank()) return
        treeScope.launch {
            treeLoading = key
            val res = withContext(Dispatchers.IO) {
                runCatching { EngineProvider.get(context).list(ws, "", key, "", 200) }.getOrNull()
            }
            treeChildren = treeChildren + (key to extractNames(res))
            treeLoading = ""
        }
    }

    fun pickName(name: String) {
        treeSel = name
        copyToClipboard(context, name, zh)
    }

    /** 全局唯一的“当前函数”选中：反汇编 / 伪C / CFG 全部跟随它。 */
    fun selectFunction(name: String, va: String) {
        tools.selectedFunctionName = name
        tools.selectedFunctionVa = va
        tools.decompileTarget = name
        tools.disasmAddr = va.ifBlank { name }
        tools.analysisView = "disasm"
    }

    val refreshAll: () -> Unit = {
        tools.clearViewCaches()
        tools.reloadTick = tools.reloadTick + 1
    }

    val view = tools.analysisView

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ── 左侧图标导航栏（Exbin NavigationRail 的等价物） ──
            AnalysisNavRail(current = view, zh = zh) { tools.analysisView = it }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                // ── 顶部条：当前函数（可点换）+ 地址 + 该视图相关操作 ──
                AnalysisTopBar(
                    state = state,
                    tools = tools,
                    zh = zh,
                    view = view,
                    onPickFunction = { tools.analysisView = "functions" },
                    onRefresh = refreshAll,
                    onOpenTree = { showTree = true },
                    onOpenOutput = { tools.analysisView = "results" },
                    onOpenTask = onOpenTask,
                )
                GroupDivider()

                // ── 当前视图内容区 ──
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    when (view) {
                        "functions" -> FunctionsView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll, onOpenTree = { showTree = true },
                        ) { n, v -> selectFunction(n, v) }

                        "search" -> SearchView(
                            tools = tools, zh = zh, context = context,
                        ) { n, v -> selectFunction(n, v) }

                        "disasm" -> DisasmView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll,
                            onGoFunctions = { tools.analysisView = "functions" },
                        )

                        "pseudo" -> PseudoView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll,
                            onGoFunctions = { tools.analysisView = "functions" },
                        )

                        "cfg" -> CfgView(
                            tools = tools, zh = zh, context = context,
                            onGoFunctions = { tools.analysisView = "functions" },
                        )

                        "strings" -> StringsView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll,
                        ) { row -> showString = row }

                        "symbols" -> SymbolsView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll,
                        ) { row -> copyToClipboard(context, row.title, zh) }

                        "imports" -> ImportsView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll,
                        ) { row -> copyToClipboard(context, row.title, zh) }

                        "sections" -> SectionsView(
                            tools = tools, zh = zh, context = context,
                            onRefresh = refreshAll,
                        ) { row -> copyToClipboard(context, row.title, zh) }

                        "demangle" -> DemangleView(zh = zh, context = context)

                        "base" -> BaseConvertView(zh = zh, context = context)

                        "asm" -> AsmEditorView(tools = tools, zh = zh, context = context)

                        "elfhdr" -> ElfHeaderView(tools, zh, context, refreshAll)

                        "segments" -> SegmentsView(tools, zh, context, refreshAll)

                        "relocs" -> RelocsView(tools, zh, context, refreshAll)

                        "dynamic" -> DynamicView(tools, zh, context, refreshAll)

                        "libraries" -> LibrariesView(tools, zh, context, refreshAll)

                        "hashes" -> HashesView(tools, zh, context, refreshAll)

                        "versions" -> VersionsView(tools, zh, context, refreshAll)

                        "entries" -> EntriesView(tools, zh, context, refreshAll)

                        "hex" -> AppCard(Modifier.fillMaxSize()) { HexPane(state, zh) }

                        "results" -> ResultsPane(tools, zh)

                        "tools" -> ToolsPane(
                            state = state, tools = tools, zh = zh,
                            onAiAnalyze = onAiAnalyze,
                            onPickTool = { showToolPicker = true },
                            onShowCfg = { tools.analysisView = "cfg" },
                        )

                        "hub" -> ToolsHubView(zh = zh) { key -> tools.analysisView = key }

                        "xor" -> XorDecryptView(zh = zh, context = context)

                        "regs" -> ArmRegisterView(zh = zh, context = context)

                        "strdec" -> StringDecodeView(zh = zh, context = context)

                        "bytediff" -> ByteDiffView(zh = zh, context = context)

                        "insnexp" -> InsnExplainView(zh = zh, context = context)

                        "asm2c" -> AsmToPseudoCView(zh = zh, context = context)

                        "asm2flow" -> AsmToFlowChartView(zh = zh, context = context)

                        "vtable" -> VtableView(tools, zh, context, refreshAll)

                        "xrefs" -> XRefsView(tools, zh, context, refreshAll)

                        "addrview" -> AddrViewerView(tools, zh, context, refreshAll)

                        "funcsig" -> FuncSigView(tools, zh, context, refreshAll)

                        "jnireg" -> JniRegView(tools, zh, context, refreshAll)

                        "hardening" -> HardeningView(tools, zh, context, refreshAll)

                        "callgraph" -> CallGraphView(tools, zh, context, refreshAll)

                        "export" -> ExportView(tools, zh, context, refreshAll)

                        "unpack" -> UnpackView(tools, zh, context, refreshAll)

                        "data" -> DataView(tools, zh, context, refreshAll)

                        "funcinfo" -> FuncInfoView(tools, zh, context, refreshAll)

                        "comments" -> CommentsView(tools, zh, context, refreshAll)

                        "analyze" -> AnalyzeModeView(tools, zh, context, refreshAll)

                        "rootdrill" -> RootDrillView(tools, zh, context, refreshAll)

                        "edit" -> EditCenterView(tools, zh, context, refreshAll)

                        "globalc" -> GlobalPseudoCView(tools, zh, context, refreshAll)

                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (zh) "未知视图" else "Unknown view",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    showString?.let { row ->
        StringDetailDialog(row = row, ws = tools.sharedWorkspaceId, zh = zh, context = context) { showString = null }
    }

    // ── 工具选择弹层（沿用原 ConsoleToolPickerDialog） ──
    if (showToolPicker) {
        ConsoleToolPickerDialog(
            zh = zh,
            onDismiss = { showToolPicker = false },
            onPick = { key ->
                state.activeTool = key
                tools.analysisView = "tools"
                showToolPicker = false
            },
        )
    }

    // ── 对象树弹层（沿用原 ObjectTreeDialog） ──
    if (showTree) {
        ObjectTreeDialog(
            zh = zh,
            workspaceId = tools.sharedWorkspaceId,
            treeOpen = treeOpen,
            treeChildren = treeChildren,
            treeLoading = treeLoading,
            treeSel = treeSel,
            onToggle = { key ->
                val open = key in treeOpen
                treeOpen = if (open) treeOpen - key else treeOpen + key
                if (!open && !treeChildren.containsKey(key)) loadTree(key)
            },
            onPickName = { name -> pickName(name) },
            onDismiss = { showTree = false },
        )
    }
}

// ============================================================
// 工作台工具选择弹层：全宽 Dialog + 搜索 + 按分类分组的 CardRow 列表
// ============================================================

private val consoleToolDescZh = mapOf(
    "decompile" to "伪代码反编译 / 函数列表 / 反汇编 / 十六进制转储",
    "unpack" to "APK 结构分析 / 提取 SO 与 DEX / 文件列表",
    "soanalyze" to "SO 概览 / 段与符号 / 导入导出表 / 加密特征",
    "emulate" to "Unidbg 模拟执行 / 寄存器 / 内存 / 校验",
    "frida" to "动态插桩：连接设备与 Hook",
    "rebuild" to "回编 SO / 十六进制补丁 / 批量重命名",
    "editor" to "十六进制编辑 / 文本编辑 / 地址对比",
)

private val consoleToolDescEn = mapOf(
    "decompile" to "Pseudocode / functions / disassembly / hex dump",
    "unpack" to "APK structure / extract SO and DEX / file list",
    "soanalyze" to "SO overview / sections & symbols / import-export / crypto",
    "emulate" to "Unidbg emulation / registers / memory / validation",
    "frida" to "Dynamic instrumentation: attach & hook",
    "rebuild" to "Rebuild SO / hex patch / bulk rename",
    "editor" to "Hex edit / text edit / address diff",
)

/** 工作台 console 工具 → 统一列表项（复用 McpToolListView.kt 的 categoryMap / 图标 / 配色）。 */
private fun consoleToolEntries(zh: Boolean): List<ToolListEntry> = toolDefs.map { def ->
    val cat = categoryMap[def.key]?.firstOrNull() ?: "utility"
    val dzh = consoleToolDescZh[def.key].orEmpty()
    val den = consoleToolDescEn[def.key].orEmpty()
    ToolListEntry(
        id = def.key,
        category = cat,
        title = if (zh) "${def.labelZh} / ${def.labelEn}" else "${def.labelEn} / ${def.labelZh}",
        subtitle = listOf(dzh, den).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { null },
        meta = "${categoryLabel(cat, zh)} · ${def.key}",
        iconKey = "tool:${def.key}",
        keywords = "${def.labelZh} ${def.labelEn} ${def.key} $dzh $den",
        trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    )
}

@Composable
private fun ConsoleToolPickerDialog(
    zh: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = remember(zh) { consoleToolEntries(zh) }
    val shown = filterToolEntries(all, query)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 56.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (zh) "选择工具" else "Pick a tool",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (zh) "共 ${shown.size} 个工具" else "${shown.size} tools",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = AppText.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = if (zh) "关闭" else "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ToolSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Spacer(Modifier.size(6.dp))
                ToolEntryList(
                    entries = shown,
                    query = query,
                    zh = zh,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 18.dp),
                    onPick = { e -> onPick(e.id) },
                )
            }
        }
    }
}

// ============================================================
// 对象树弹层（原 132dp 停靠面板迁移：卡片列表项 + 展开后叶子列表）
// ============================================================

private fun treeCatIcon(key: String): ImageVector = when (key) {
    "sections" -> Icons.Filled.Inventory2
    "symbols" -> Icons.Filled.Code
    "functions" -> Icons.Filled.Memory
    "strings" -> Icons.Filled.Description
    "imports" -> Icons.Filled.Link
    else -> Icons.Filled.ListAlt
}

@Composable
private fun ObjectTreeDialog(
    zh: Boolean,
    workspaceId: String,
    treeOpen: Set<String>,
    treeChildren: Map<String, List<String>>,
    treeLoading: String,
    treeSel: String,
    onToggle: (String) -> Unit,
    onPickName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 56.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.ListAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (zh) "对象树" else "Object tree",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (zh) "点分类展开，点条目复制" else "Tap a group to expand, tap an item to copy",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = AppText.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = if (zh) "关闭" else "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (workspaceId.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (zh) "未打开工作区，请先选择文件" else "No workspace opened — pick a file first",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        treeCats.forEach { cat ->
                            val open = cat.key in treeOpen
                            item(key = "cat-${cat.key}") {
                                Box(Modifier.padding(horizontal = 6.dp)) {
                                    CardRow(
                                        title = if (zh) cat.zh else cat.en,
                                        subtitle = if (open) {
                                            if (zh) "已展开，点击收起" else "Expanded — tap to collapse"
                                        } else {
                                            if (zh) "点击展开" else "Tap to expand"
                                        },
                                        meta = if (treeLoading == cat.key) {
                                            if (zh) "加载中…" else "Loading…"
                                        } else {
                                            "${treeChildren[cat.key]?.size ?: 0} ${if (zh) "项" else "items"}"
                                        },
                                        icon = treeCatIcon(cat.key),
                                        trailing = {
                                            if (treeLoading == cat.key) {
                                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(
                                                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        },
                                        onClick = { onToggle(cat.key) },
                                    )
                                }
                            }
                            if (open) {
                                val kids = treeChildren[cat.key]
                                if (kids != null) {
                                    items(kids.take(300)) { name ->
                                        TreeLeafRow(name, treeSel == name) { onPickName(name) }
                                    }
                                    if (kids.size > 300) {
                                        item(key = "more-${cat.key}") {
                                            Text(
                                                if (zh) "… 共 ${kids.size} 项" else "… ${kids.size} total",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 62.dp, bottom = 6.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "div-${cat.key}") { GroupDivider() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeLeafRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 58.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
        Text(
            name,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** 地址栏组件：统一放在选文件下面，控制台上面 */
@Composable
private fun AddrBar(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (zh) "地址" else "Addr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label)
        OutlinedTextField(
            value = tools.disasmAddr,
            onValueChange = { tools.disasmAddr = it },
            singleLine = true,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            shape = RoundedCornerShape(AppShape.xs),
            placeholder = { Text(if (zh) "地址或符号" else "addr or sym", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        // 根据当前工具显示不同的辅助输入
        when (state.activeTool) {
            "decompile" -> {
                Text(if (zh) "符号" else "Sym", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label)
                OutlinedTextField(
                    value = tools.decompileTarget,
                    onValueChange = { tools.decompileTarget = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                    shape = RoundedCornerShape(AppShape.xs),
                    placeholder = { Text(if (zh) "函数名" else "func name", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            "emulate" -> {
                Text(if (zh) "符号" else "Sym", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label)
                OutlinedTextField(
                    value = tools.emulateSymbol,
                    onValueChange = { tools.emulateSymbol = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                    shape = RoundedCornerShape(AppShape.xs),
                    placeholder = { Text(if (zh) "函数名" else "func name", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            "editor" -> {
                Text(if (zh) "对比地址" else "Diff", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label)
                OutlinedTextField(
                    value = tools.decompileTarget,
                    onValueChange = { tools.decompileTarget = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                    shape = RoundedCornerShape(AppShape.xs),
                    placeholder = { Text(if (zh) "对比地址" else "diff addr", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}

@Composable
private fun WorkspacePicker(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf("") }

    fun openWorkspaceFile(path: String, fallbackName: String) {
        scope.launch {
            tools.opening = true; tools.openError = ""
            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).open(path, false) }.getOrNull() }
            if (r != null && r.optBoolean("ok", false)) {
                tools.sharedWorkspaceId = r.optString("workspaceId"); tools.sharedSoName = r.optString("soFileName").ifBlank { r.optString("fileName") }
                val fname = tools.sharedSoName.ifBlank { fallbackName }
                state.createTask(fname, if (fname.endsWith(".apk", true)) "apk" else "so", path, fname)
                tools.opening = false; tools.clearTabs()
            } else { tools.opening = false; tools.openError = r?.optString("error").orEmpty().ifBlank { if (zh) "打开失败" else "Open failed" } }
        }
    }

    val picker = rememberLauncherForActivityResult(OpenDocumentReadWriteContract()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        showDialog = false
        openWorkspaceFile(uri.toString(), uri.lastPathSegment ?: "file")
    }

    fun submitManual() {
        val path = manualPath.trim()
        if (path.isBlank()) {
            manualError = if (zh) "请输入文件路径" else "Please enter a file path"
            return
        }
        showDialog = false
        openWorkspaceFile(path, path.substringAfterLast('/').ifBlank { "file" })
    }

    Button(onClick = { showDialog = true; manualPath = ""; manualError = "" },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), enabled = !tools.opening, shape = RoundedCornerShape(AppShape.sm)) {
        if (tools.opening) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(4.dp)); Text(if (zh) "加载中…" else "Loading…", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label) }
        else { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.size(4.dp)); Text((tools.sharedSoName.ifBlank { if (zh) "选文件" else "Open" }).take(12), style = MaterialTheme.typography.labelSmall, fontSize = AppText.label)
    }
    if (tools.openError.isNotBlank()) Text(tools.openError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(AppShape.xl),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (zh) "选文件" else "Pick file",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { showDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = if (zh) "关闭" else "Close")
                        }
                    }
                    Text(
                        if (zh) "选择要分析的文件（APK、SO 等），文件可读写访问。"
                        else "Select a file to analyze (APK, SO, etc.). Read-write access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            // 借鉴上游 #78（上游未修复，塔菲做得更好）：部分国产 ROM 无 SAF 文件提供方，
                            // picker.launch 会抛 ActivityNotFoundException——捕获后引导使用下方手动路径输入
                            try {
                                picker.launch(arrayOf("application/octet-stream", "application/zip", "application/vnd.android.package-archive", "*/*"))
                            } catch (e: android.content.ActivityNotFoundException) {
                                manualError = if (zh) "系统未提供文件选择器（SAF），请在下方直接输入文件路径" else "No file picker (SAF) on this device; type the path below"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppShape.md),
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (zh) "选择文件" else "Pick file")
                    }
                    androidx.compose.material3.HorizontalDivider()
                    Text(
                        if (zh) "手动输入路径" else "Manual path",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it; manualError = "" },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (zh) "输入文件路径" else "Enter file path", maxLines = 1) },
                        singleLine = true,
                        shape = RoundedCornerShape(AppShape.md),
                        isError = manualError.isNotBlank(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { submitManual() },
                        ),
                        supportingText = if (manualError.isNotBlank()) {
                            { Text(manualError, color = MaterialTheme.colorScheme.error) }
                        } else null,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showDialog = false }) {
                            Text(if (zh) "取消" else "Cancel")
                        }
                        TextButton(
                            enabled = manualPath.isNotBlank(),
                            onClick = { submitManual() },
                        ) { Text(if (zh) "打开" else "Open") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolConsole(state: WorkspaceState, zh: Boolean, onAiAnalyze: (String) -> Unit, onShowCfg: () -> Unit) {
    val tools = state.tools; val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    val curDef = toolDefs.firstOrNull { it.key == state.activeTool }
    val tl = curDef?.let { if (zh) it.labelZh else it.labelEn } ?: ""

    FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val bm = Modifier.heightIn(min = 40.dp); val bp = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        when (state.activeTool) {
            "decompile" -> {
                SmBtn(if (zh) "反编译" else "Dec", bm, bp, {
                    val loc = tools.decompileTarget.trim(); if (loc.isEmpty()) return@SmBtn
                    scope.launch { tools.decompileRunning = true; tools.decompileError = ""; tools.decompileResult = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzDecompile(tools.sharedWorkspaceId, "", loc, true) }.getOrNull() }
                        tools.decompileRunning = false
                        val text = if (r != null && r.optBoolean("ok", false)) r.optString("pseudocode").ifBlank { r.toString() }
                            else r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { r?.optString("error") ?: if (zh) "反编译失败" else "Failed" }
                        tools.addTab(tl, if (zh) "反编译" else "Decompile", text)
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.decompileTarget.isNotBlank() && !tools.decompileRunning, loading = tools.decompileRunning)
                SmBtn(if (zh) "函数" else "Fns", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "functions", "", 30) }.getOrNull() }
                    tools.addTab(tl, if (zh) "函数" else "Functions", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Disasm", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).disasm(tools.sharedWorkspaceId, "", "", 20, "", 0, 0, 4096, tools.disasmAddr, null, "auto") }.getOrNull() }
                    tools.addTab(tl, "Disasm", r?.toString() ?: if (zh) "失败" else "failed")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.disasmAddr.isNotBlank())
                SmBtn("Hex", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, 0, 256) }.getOrNull() }
                    tools.addTab(tl, "Hex", r?.toString() ?: if (zh) "失败" else "failed")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "unpack" -> {
                SmBtn(if (zh) "分析" else "Analyze", bm, bp, { scope.launch { tools.unpackRunning = true
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                    tools.unpackRunning = false; tools.addTab(tl, if (zh) "分析" else "Analyze", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, loading = tools.unpackRunning)
                SmBtn("SO", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", ".so", 60) }.getOrNull() }
                    tools.addTab(tl, "SO", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("DEX", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "classes", "", 30) }.getOrNull() }
                    tools.addTab(tl, "DEX", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "soanalyze" -> {
                SmBtn(if (zh) "概览" else "Ovw", bm, bp, { scope.launch { tools.soAnalyzeRunning = true
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).overview(tools.sharedWorkspaceId) }.getOrNull() }
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzScanCrypto(tools.sharedWorkspaceId) }.getOrNull() }
                    tools.soAnalyzeRunning = false
                    val merged = JSONObject()
                    if (o != null) merged.put("overview", o)
                    if (c != null) merged.put("cryptoFindings", c.optJSONArray("cryptoFindings") ?: c.optJSONArray("findings") ?: c.optJSONArray("scans"))
                    tools.addTab(tl, if (zh) "概览" else "Overview", if (merged.length() > 0) merged.toString() else if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.soAnalyzeRunning, loading = tools.soAnalyzeRunning)
                SmBtn("Sec", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "sections", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "节区" else "Sections", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Imp", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "imports", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "导入" else "Imports", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Exp", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "dynsyms", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "导出" else "Exports", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("CFG", bm, bp, {
                    // CFG 目标必须来自用户显式选择（函数列表选中 / 顶部条），不再有 "main" 这类魔法默认。
                    // 未选中函数时只切到 CFG 视图，由视图自身给出「请先选择函数 / 入口点兜底」空态。
                    val cfgTarget = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }.ifBlank { tools.decompileTarget.trim() }
                    tools.cfgVisible = true
                    onShowCfg()
                    if (cfgTarget.isNotBlank()) {
                        scope.launch {
                            tools.cfgLoading = true
                            tools.cfgTarget = cfgTarget
                            tools.cfgJson = ""
                            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzCfg(tools.sharedWorkspaceId, "", cfgTarget) }.getOrNull() }
                            tools.cfgLoading = false
                            tools.cfgJson = r?.toString() ?: JSONObject().put("ok", false)
                                .put("error", JSONObject().put("code", "TAFFY_UI_ERROR")
                                    .put("message", if (zh) "CFG 查询失败：引擎无响应" else "CFG query failed: engine did not respond")).toString()
                        }
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // AI 深度分析：跳转 AI 对话页（MainActivity 挂载 DeepAiChatScreen 进行对话）
                SmBtn(if (zh) "AI 深度" else "AI Deep", bm, bp, {
                    val taskPath = state.currentTask()?.mainPath
                    if (taskPath.isNullOrBlank()) {
                        tools.addTab(tl, if (zh) "AI" else "AI", if (zh) "请先从文件/任务入口选择工作区后再发起 AI 深度分析" else "pick a workspace first")
                    } else {
                        onAiAnalyze(taskPath)
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "emulate" -> {
                SmBtn(if (zh) "模拟" else "Emu", bm, bp, { val sym = tools.emulateSymbol.trim(); if (sym.isEmpty()) return@SmBtn
                    scope.launch { tools.emulateRunning = true; tools.emulateError = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", sym, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateRunning = false
                        tools.addTab(tl, if (zh) "模拟" else "Emulate", r?.toString() ?: (tools.emulateError.ifBlank { if (zh) "失败" else "Failed" }))
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.emulateSymbol.isNotBlank() && !tools.emulateRunning, loading = tools.emulateRunning)
                SmBtn(if (zh) "寄存器" else "Regs", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", tools.emulateSymbol.ifBlank { "JNI_OnLoad" }, org.json.JSONArray(), true) }.getOrNull() }
                    tools.addTab(tl, if (zh) "寄存器" else "Registers", r?.toString() ?: (if (zh) "先模拟" else "emu first"))
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Mem", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).dumpMemory(tools.sharedWorkspaceId, "", 0L, 256) }.getOrNull() }
                    tools.addTab(tl, "Mem", r?.toString() ?: (if (zh) "先模拟" else "emu first"))
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "frida" -> {
                SmBtn(if (zh) "校验" else "Valid", bm, bp, { scope.launch {
                    val script = tools.fridaScript.trim()
                    val errors = mutableListOf<String>()
                    if (script.isBlank()) errors.add(if (zh) "脚本为空" else "empty script")
                    if (script.contains("Java.perform") && !script.contains("function")) errors.add(if (zh) "Java.perform 缺少回调" else "perform missing callback")
                    if (script.count { it == '{' } != script.count { it == '}' }) errors.add(if (zh) "花括号不匹配" else "brace mismatch")
                    if (script.count { it == '(' } != script.count { it == ')' }) errors.add(if (zh) "括号不匹配" else "paren mismatch")
                    val result = JSONObject()
                    if (errors.isEmpty()) { result.put("status", "✅ OK"); result.put("errors", JSONArray()) }
                    else { result.put("status", "❌ Error"); val e = JSONArray(); errors.forEach { e.put(it) }; result.put("errors", e) }
                    result.put("scriptLength", script.length); result.put("scriptPreview", script.take(200))
                    if (zh) result.put("tip", "连接设备需 root + frida-server 运行") else result.put("tip", "attach device requires root + frida-server")
                    tools.fridaStatus = result.toString(2); tools.addTab(tl, if (zh) "校验" else "Validate", tools.fridaStatus)
                } }, enabled = true)
                SmBtn(if (zh) "连接" else "Attach", bm, bp, {
                    val result = JSONObject()
                    result.put("status", if (zh) "⚠ 需要 root 权限" else "⚠ root required")
                    result.put("requirements", if (zh) "需要: root权限 + frida-server 运行在设备上" else "requires: root + frida-server running on device")
                    val steps = JSONArray()
                    if (zh) { steps.put("1. 确保设备已 root"); steps.put("2. adb push frida-server /data/local/tmp/"); steps.put("3. adb shell chmod 755 /data/local/tmp/frida-server"); steps.put("4. adb shell /data/local/tmp/frida-server &") }
                    else { steps.put("1. root device"); steps.put("2. adb push frida-server /data/local/tmp/"); steps.put("3. adb shell chmod 755 /data/local/tmp/frida-server"); steps.put("4. adb shell /data/local/tmp/frida-server &") }
                    result.put("steps", steps); tools.fridaStatus = result.toString(2); tools.addTab(tl, if (zh) "连接" else "Attach", tools.fridaStatus)
                }, enabled = true)
                SmBtn("Hook", bm, bp, {
                    val result = JSONObject(); result.put("status", if (zh) "⚠ 需先连接设备" else "⚠ attach first")
                    result.put("note", if (zh) "连接成功后，在此输入要 Hook 的类名和方法" else "after attach, enter class name and method to hook")
                    tools.fridaStatus = result.toString(2); tools.addTab(tl, "Hook", tools.fridaStatus)
                }, enabled = true)
            }
            "rebuild" -> {
                SmBtn(if (zh) "回编" else "Build", bm, bp, { scope.launch { tools.rebuildRunning = true
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                    val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                    tools.rebuildRunning = false
                    val merged = JSONObject()
                    if (c != null) merged.put("editCheck", c); if (b != null) merged.put("build", b); if (o != null) merged.put("outputs", o)
                    tools.addTab(tl, if (zh) "回编" else "Build", merged.toString())
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.rebuildRunning, loading = tools.rebuildRunning)
                SmBtn(if (zh) "补丁" else "Patch", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editAudit(tools.sharedWorkspaceId, "") }.getOrNull() }
                    tools.addTab(tl, if (zh) "补丁" else "Patch", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "符号" else "Sym", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "symbols", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "符号" else "Symbols", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "字符串" else "Str", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 20) }.getOrNull() }
                    tools.addTab(tl, if (zh) "字符串" else "Strings", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "editor" -> {
                // 编辑工具：文本查看（读取字符串列表）
                SmBtn(if (zh) "文本" else "Text", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 30) }.getOrNull() }
                    tools.editorTextResult = r?.toString() ?: if (zh) "无" else "none"
                    tools.addTab(tl, if (zh) "文本" else "Text", tools.editorTextResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 编辑：读取地址数据展示可编辑格式
                SmBtn(if (zh) "编辑" else "Edit", bm, bp, { scope.launch {
                    val addr = tools.disasmAddr.ifBlank { "0x0" }
                    val read = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr, 0, 64) }.getOrNull() }
                    val hexData = read?.optString("hex") ?: read?.optString("data") ?: read?.optString("result") ?: ""
                    val edit = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editHex(tools.sharedWorkspaceId, "", addr, org.json.JSONArray(), false) }.getOrNull() }
                    val result = JSONObject()
                    result.put("address", addr); result.put("hexData", hexData)
                    val ascii = hexData.split(" ").filter { it.length == 2 }.map {
                        val c = it.toInt(16).toChar(); if (c.isISOControl() || c.code > 127) '.' else c.toString()
                    }.joinToString("")
                    result.put("ascii", ascii)
                    if (edit != null) result.put("editResult", edit)
                    result.put("instruction", if (zh) "在地址栏输入目标地址查看数据，输入十六进制字节修改" else "enter address to view data, enter hex bytes to modify")
                    tools.editorEditResult = result.toString(2); tools.addTab(tl, if (zh) "编辑" else "Edit", tools.editorEditResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 对比：对比当前文件两个地址的数据
                SmBtn(if (zh) "对比" else "Diff", bm, bp, { scope.launch {
                    val addr1 = tools.disasmAddr.ifBlank { "0x0" }
                    val addr2 = tools.decompileTarget.ifBlank { "0x100" }
                    val r1 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr1, 0, 64) }.getOrNull() }
                    val r2 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr2, 0, 64) }.getOrNull() }
                    val hex1 = r1?.optString("hex") ?: r1?.optString("data") ?: ""; val hex2 = r2?.optString("hex") ?: r2?.optString("data") ?: ""
                    val result = JSONObject()
                    result.put("对比地址1(地址栏)", addr1); result.put("对比地址2(对比地址栏)", addr2)
                    // 读取 hex1 和 hex2 的原始数据文本
                    val text1 = r1?.optString("text") ?: r1?.optString("ascii") ?: ""
                    val text2 = r2?.optString("text") ?: r2?.optString("ascii") ?: ""
                    result.put("data1", hex1); result.put("data2", hex2)
                    if (text1.isNotBlank()) result.put("text1", text1)
                    if (text2.isNotBlank()) result.put("text2", text2)
                    val diffBytes = mutableListOf<String>()
                    val bytes1 = hex1.replace(" ", "").replace("\n", ""); val bytes2 = hex2.replace(" ", "").replace("\n", "")
                    val minLen = minOf(bytes1.length, bytes2.length)
                    for (i in 0 until minLen / 2) {
                        if (bytes1.length >= i*2+2 && bytes2.length >= i*2+2) {
                            val b1 = bytes1.substring(i*2, i*2+2); val b2 = bytes2.substring(i*2, i*2+2)
                            if (b1 != b2) diffBytes.add("0x${i.toString(16).padStart(4, '0')}: $b1 → $b2")
                        }
                    }
                    result.put("diffCount", diffBytes.size)
                    val diffArr = JSONArray(); diffBytes.forEach { diffArr.put(it) }; result.put("diffs", diffArr)
                    result.put("match", bytes1 == bytes2)
                    tools.editorDiffResult = result.toString(2); tools.addTab(tl, if (zh) "对比" else "Diff", tools.editorDiffResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
        }
    }
}

@Composable
private fun SmBtn(label: String, modifier: Modifier, padding: PaddingValues, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !loading, contentPadding = padding, modifier = modifier, shape = RoundedCornerShape(AppShape.xs),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        if (loading) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong))
    }
}

@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    val tabs = tools.resultTabs; val selectedTab = tools.selectedTabIndex
    var viewMode by remember { mutableStateOf(0) }  // 0=简洁 1=详细 2=汇编
    val current = tabs.getOrNull(selectedTab)
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("${selectedTab + 1}/${tabs.size}", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Surface(onClick = { viewMode = (viewMode + 1) % 3 }, shape = RoundedCornerShape(AppShape.xs), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    when (viewMode) { 0 -> (if (zh) "简洁" else "Simple"); 1 -> (if (zh) "详细" else "Detail"); else -> (if (zh) "汇编" else "Asm") },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, fontSize = AppText.label,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (current == null) {
                Text(if (zh) "暂无结果，请执行分析工具" else "No results yet, run a tool", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (viewMode == 2) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(current.text.split("\n")) { line -> DisasmLine(line) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (viewMode == 1) StructuredJsonView(current.text, zh) else TextSummary(current.text, zh)
                }
            }
        }
    }
}

// ============================================================
// 简洁模式：纯文字完整描述
// ============================================================

@Composable
private fun TextSummary(text: String, zh: Boolean) {
    if (text.isBlank()) return
    val json = runCatching { JSONObject(text) }.getOrNull()
    if (json == null) { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.body, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp); return }
    val sb = StringBuilder()
    describeJson(json, sb, zh, 0)
    Text(sb.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.body, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
}

/** 将任意 JSON 描述为文字，不产生问号或省略号 */
private fun describeJson(json: JSONObject, sb: StringBuilder, zh: Boolean, indent: Int) {
    val pad = "  ".repeat(indent)

    // 1. overview → 文字描述
    val ov = json.optJSONObject("overview")
    if (ov != null) { describeOverview(ov, json, sb, zh); return }

    // 2. items 数组 → 列表
    val items = json.optJSONArray("items")
    if (items != null && items.length() > 0) {
        if (zh) sb.append("共 ${items.length()} 项:\n") else sb.append("${items.length()} items:\n")
        // 先检查 items 中的字段名，确定用哪个字段展示
        val firstItem = items.optJSONObject(0)
        val hasName = firstItem?.has("name") == true
        val hasAddr = firstItem?.has("address") == true || firstItem?.has("vaddr") == true || firstItem?.has("addr") == true
        val hasOrdn = firstItem?.has("ordinal") == true
        for (i in 0 until items.length()) {
            val item = items.opt(i)
            if (item is JSONObject) {
                val name = item.optString("name", item.optString("symbol", item.optString("function", "")))
                val addr = item.optString("address", item.optString("vaddr", item.optString("addr", item.optString("offset", ""))))
                val ordn = if (hasOrdn) item.optString("ordinal", "") else ""
                val typ = item.optString("type", "")
                val display = when {
                    name.isNotBlank() -> name
                    addr.isNotBlank() -> "@$addr"
                    ordn.isNotBlank() -> "[$ordn]"
                    typ.isNotBlank() -> "[$typ]"
                    else -> (if (zh) "第${i+1}项" else "item $i")
                }
                sb.append("$pad• $display")
                if (addr.isNotBlank() && name.isNotBlank()) sb.append("  @ $addr")
                if (ordn.isNotBlank()) sb.append("  #$ordn")
                sb.append("\n")
            } else if (item is String) {
                sb.append("$pad• $item\n")
            } else {
                val s = item.toString()
                if (s.isNotBlank() && s != "null") sb.append("$pad• $s\n")
            }
        }
        return
    }

    // 3. cryptoFindings / findings / scans
    val cryptoArr = json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings") ?: json.optJSONArray("scans")
    if (cryptoArr != null && cryptoArr.length() > 0) {
        if (zh) sb.append("发现 ${cryptoArr.length()} 项加密特征:\n") else sb.append("${cryptoArr.length()} crypto findings:\n")
        for (i in 0 until cryptoArr.length()) {
            val c = cryptoArr.optJSONObject(i) ?: continue
            val name = c.optString("name", c.optString("algorithm", c.optString("type", "")))
            val count = c.optInt("count", c.optInt("matches", 0))
            val displayName = if (name.isNotBlank()) name else (if (zh) "特征#${i+1}" else "feature#${i+1}")
            sb.append("$pad• $displayName")
            if (count > 0) sb.append(" ×$count")
            sb.append("\n")
        }
        return
    }

    // 4. 通用键值
    json.keys().asSequence().filter { it != "ok" }.forEach { k ->
        val v = json.opt(k)
        when (v) {
            is JSONObject -> { sb.append("$pad$k:\n"); describeJson(v, sb, zh, indent + 1) }
            is JSONArray -> { sb.append("$pad$k: ${v.length()} 项\n") }
            else -> {
                val vStr = v?.toString() ?: ""
                sb.append("$pad${if (k.isNotBlank()) k else "?"}: ${if (vStr.isNotBlank()) vStr else "-"}\n")
            }
        }
    }
}

/** 用中文/英文描述 overview 对象 */
private fun describeOverview(ov: JSONObject, json: JSONObject?, sb: StringBuilder, zh: Boolean) {
    if (zh) {
        sb.append("文件: ").append(ov.optString("fileName", "-")).append("\n")
        sb.append("架构: ").append(ov.optString("architecture", "-"))
        if (ov.has("bits")) sb.append("/${ov.optInt("bits")}bit")
        sb.append("\n")
        sb.append("大小: ").append(fmtBytes(ov.optLong("size", 0L))).append("\n")
        sb.append("类型: ").append(ov.optString("elfType", "-")).append("\n")
        sb.append("入口点: ").append(ov.optString("entryPoint", "0x0")).append("\n")
        sb.append("字节序: ").append(ov.optString("endian", "-")).append("\n")
        val sha256 = ov.optString("sha256", "")
        if (sha256.isNotBlank()) sb.append("SHA256: $sha256\n")
        val compiler = ov.optString("compiler", "")
        if (compiler.isNotBlank()) sb.append("编译器: $compiler\n")
        val packer = ov.optString("packer", "")
        if (packer.isNotBlank()) sb.append("加壳工具: $packer\n")
    } else {
        sb.append("File: ").append(ov.optString("fileName", "-")).append("\n")
        sb.append("Arch: ").append(ov.optString("architecture", "-"))
        if (ov.has("bits")) sb.append("/${ov.optInt("bits")}bit")
        sb.append("\n")
        sb.append("Size: ").append(fmtBytes(ov.optLong("size", 0L))).append("\n")
        sb.append("Type: ").append(ov.optString("elfType", "-")).append("\n")
        sb.append("Entry: ").append(ov.optString("entryPoint", "0x0")).append("\n")
        sb.append("Endian: ").append(ov.optString("endian", "-")).append("\n")
        val sha256 = ov.optString("sha256", "")
        if (sha256.isNotBlank()) sb.append("SHA256: $sha256\n")
        val compiler = ov.optString("compiler", "")
        if (compiler.isNotBlank()) sb.append("Compiler: $compiler\n")
        val packer = ov.optString("packer", "")
        if (packer.isNotBlank()) sb.append("Packer: $packer\n")
    }

    // 结构计数
    val countLabels = listOf("sectionCount" to (if (zh) "节区" else "Sections"), "functionCount" to (if (zh) "函数" else "Functions"),
        "symbolCount" to (if (zh) "符号" else "Symbols"), "stringCount" to (if (zh) "字符串" else "Strings"),
        "importCount" to (if (zh) "导入" else "Imports"), "exportCount" to (if (zh) "导出" else "Exports"))
    val counts = countLabels.filter { ov.has(it.first) && ov.optInt(it.first, 0) > 0 }
    if (counts.isNotEmpty()) {
        if (zh) sb.append("结构分析:\n") else sb.append("Structure:\n")
        counts.forEach { (k, label) -> sb.append("  $label: ${ov.optInt(k, 0)}\n") }
    }

    // 安全特性
    val sec = ov.optJSONArray("securityFeatures")
    if (sec != null && sec.length() > 0) {
        if (zh) sb.append("安全特性:\n") else sb.append("Security:\n")
        for (i in 0 until sec.length()) {
            val s = sec.optJSONObject(i) ?: continue
            val label = s.optString("label", s.optString("id", ""))
            val active = s.optBoolean("active", false)
            val desc = s.optString("description", "")
            val displayLabel = if (label.isNotBlank()) label else (if (zh) "特性#${i+1}" else "feature#${i+1}")
            sb.append("  $displayLabel: ${if (active) (if (zh) "启用" else "Yes") else (if (zh) "未启用" else "No")}\n")
            if (desc.isNotBlank()) sb.append("    $desc\n")
        }
    }

    // 加密特征
    val crypto = ov.optJSONArray("cryptoFindings") ?: ov.optJSONArray("findings")
    if (crypto != null && crypto.length() > 0) {
        if (zh) sb.append("加密特征:\n") else sb.append("Crypto:\n")
        for (i in 0 until crypto.length()) {
            val c = crypto.optJSONObject(i) ?: continue
            val name = c.optString("name", c.optString("algorithm", ""))
            val count = c.optInt("count", c.optInt("matches", 0))
            val displayName = if (name.isNotBlank()) name else (if (zh) "特征#${i+1}" else "feature#${i+1}")
            sb.append("  $displayName")
            if (count > 0) sb.append(" ×$count")
            sb.append("\n")
        }
    }

    // 顶层加密特征
    val rootCrypto = json?.optJSONArray("cryptoFindings")
    if (rootCrypto != null && rootCrypto.length() > 0) {
        if (zh) sb.append("额外加密特征:\n") else sb.append("Extra Crypto:\n")
        for (i in 0 until rootCrypto.length()) {
            val c = rootCrypto.optJSONObject(i) ?: continue
            val name = c.optString("name", c.optString("algorithm", ""))
            val count = c.optInt("count", c.optInt("matches", 0))
            val displayName = if (name.isNotBlank()) name else (if (zh) "特征#${i+1}" else "feature#${i+1}")
            sb.append("  $displayName")
            if (count > 0) sb.append(" ×$count")
            sb.append("\n")
        }
    }

    // 其他字段：结构化字段用易懂中文文字描述（参考安全特性风格），避免倾倒原始 JSON
    describeOverviewExtras(ov, sb, zh)
}

/**
 * 用易懂中文描述 overview 中未在主要分区展示的字段。
 * 结构化对象/数组（段统计、攻击面、依赖库、熵、逆向难度、FLAGS）逐个用文字解释，
 * 标量字段走 fieldLabelCh 中文映射，避免把原始 JSON 直接倒给用户(那正是"imp 全是问号"的成因)。
 */
private fun describeOverviewExtras(ov: JSONObject, sb: StringBuilder, zh: Boolean) {
    val pad = "  "
    val zhHead = if (zh) "其他信息:\n" else "Others:\n"
    var wroteHeader = false
    fun header() { if (!wroteHeader) { sb.append(zhHead); wroteHeader = true } }

    // 依赖库（需要可读：库名 + 含义）
    val libs = ov.optJSONArray("neededLibraries")
    if (libs != null && libs.length() > 0) {
        header()
        sb.append("${pad}${if (zh) "依赖库" else "Dependencies"} (${libs.length()}):\n")
        for (i in 0 until libs.length()) {
            val l = libs.optJSONObject(i) ?: continue
            val name = l.optString("name", "")
            val desc = l.optString("description", "")
            if (name.isNotBlank()) {
                sb.append("$pad  • $name")
                if (desc.isNotBlank()) sb.append(" — $desc")
            } else if (desc.isNotBlank()) {
                sb.append("$pad  • $desc")
            } else {
                val v = l.optString("name", l.opt("text")?.toString() ?: (if (zh) "库#${i+1}" else "lib#${i+1}"))
                sb.append("$pad  • $v")
            }
            sb.append("\n")
        }
    }

    // 攻击面（attackItem 已拼好 text，直接用）
    val attack = ov.optJSONArray("attackSurface")
    if (attack != null && attack.length() > 0) {
        header()
        sb.append("${pad}${if (zh) "攻击面分析" else "Attack Surface"}:\n")
        for (i in 0 until attack.length()) {
            val a = attack.optJSONObject(i) ?: continue
            val text = a.optString("text", "")
            val title = a.optString("title", "")
            if (text.isNotBlank()) sb.append("$pad  • $text\n")
            else if (title.isNotBlank()) sb.append("$pad  • $title\n")
        }
    }

    // 逆向难度
    val diff = ov.optJSONObject("difficulty")
    if (diff != null) {
        header()
        val level = diff.optString("level", "")
        val score = diff.optString("score", diff.optString("scoreValue", ""))
        val summary = diff.optString("summary", "")
        val label = if (level.isNotBlank() && score.isNotBlank()) "$level (评分 $score/10)" else (if (level.isNotBlank()) level else score)
        sb.append("${pad}${if (zh) "逆向难度" else "Difficulty"}: $label\n")
        if (summary.isNotBlank()) sb.append("$pad  $summary\n")
        val factors = diff.optJSONArray("factors")
        if (factors != null && factors.length() > 0) {
            sb.append("$pad  ${if (zh) "关键因素" else "Key factors"}:\n")
            for (i in 0 until factors.length()) {
                val f = factors.optJSONObject(i) ?: continue
                val t = f.optString("title", f.optString("text", ""))
                val d = f.optString("detail", "")
                sb.append("$pad    - $t")
                if (d.isNotBlank()) sb.append("：$d")
                sb.append("\n")
            }
        }
    }

    // 熵分析
    val entropy = ov.optJSONObject("entropy")
    if (entropy != null) {
        header()
        val head = entropy.opt("head64k")?.toString()
        val gbl = entropy.opt("globalSample")?.toString()
        val lv = entropy.optString("level", "")
        sb.append("${pad}${if (zh) "熵分析" else "Entropy"}:\n")
        if (gh(head)) sb.append("$pad  ${if (zh) "头部64K" else "Head64K"}: $head\n")
        if (gh(gbl)) sb.append("$pad  ${if (zh) "全局抽样" else "Global"}: $gbl\n")
        if (lv.isNotBlank()) sb.append("$pad  ${if (zh) "级别" else "Level"}: $lv\n")
    }

    // 段分类统计
    val segClass = ov.optJSONObject("segmentClass")
    if (segClass != null) {
        header()
        val execC = segClass.optInt("execCount", 0)
        val readC = segClass.optInt("readCount", 0)
        val writeC = segClass.optInt("writeCount", 0)
        val otherC = segClass.optInt("otherCount", 0)
        val execP = segClass.opt("execPct")?.toString()
        val readP = segClass.opt("readPct")?.toString()
        val writeP = segClass.opt("writePct")?.toString()
        sb.append("${pad}${if (zh) "段类型分类" else "Segment classes"}:\n")
        sb.append("$pad  ${if (zh) "可执行" else "Exec"}: $execC 段${if (gh(execP)) " (${execP}%)" else ""}\n")
        sb.append("$pad  ${if (zh) "可读" else "Read"}: $readC 段${if (gh(readP)) " (${readP}%)" else ""}\n")
        sb.append("$pad  ${if (zh) "可写" else "Write"}: $writeC 段${if (gh(writeP)) " (${writeP}%)" else ""}\n")
        sb.append("$pad  ${if (zh) "其他" else "Other"}: $otherC 段\n")
    }

    // 段权限
    val segPerm = ov.optJSONObject("segmentPermissions")
    if (segPerm != null) {
        header()
        val loadable = segPerm.optInt("loadable", 0)
        val readable = segPerm.optInt("readable", 0)
        val writable = segPerm.optInt("writable", 0)
        val executable = segPerm.optInt("executable", 0)
        sb.append("${pad}${if (zh) "段权限" else "Segment permissions"}:\n")
        sb.append("$pad  ${if (zh) "可加载" else "Loadable"}: $loadable, ${if (zh) "可读" else "Read"}: $readable, ${if (zh) "可写" else "Write"}: $writable, ${if (zh) "可执行" else "Exec"}: $executable\n")
    }

    // FLAGS 一键安全开关汇总（relro 是字符串，其余是布尔）
    val flags = ov.optJSONObject("flags")
    if (flags != null) {
        header()
        val pieces = mutableListOf<String>()
        fun addBool(key: String, zhLabel: String) {
            if (flags.has(key)) {
                val v = flags.opt(key)
                val on = when (v) {
                    is Boolean -> v
                    is String -> v.equals("true", true)
                    else -> false
                }
                if (on) pieces.add(zhLabel)
            }
        }
        val relro = flags.optString("relro", "")
        if (relro.isNotBlank()) pieces.add("RELRO:$relro")
        addBool("pie", if (zh) "PIE" else "PIE")
        addBool("nx", if (zh) "NX" else "NX")
        addBool("canary", if (zh) "Canary" else "Canary")
        addBool("fortify", if (zh) "FORTIFY" else "FORTIFY")
        addBool("cfi", if (zh) "CFI" else "CFI")
        addBool("antiDebug", if (zh) "反调试" else "anti-debug")
        addBool("rootDetect", if (zh) "Root检测" else "root-det")
        addBool("emulatorDetect", if (zh) "模拟器检测" else "emulator")
        addBool("dynLoad", if (zh) "动态加载" else "dlopen")
        addBool("sslPinning", if (zh) "SSL固定" else "SSL-pin")
        addBool("ollvm", if (zh) "OLLVM" else "OLLVM")
        addBool("strEncrypt", if (zh) "字符串加密" else "str-encrypt")
        addBool("initArray", if (zh) ".init_array" else ".init_array")
        addBool("textRel", if (zh) "TEXTREL" else "TEXTREL")
        sb.append("${pad}${if (zh) "安全标志汇总" else "Flag summary"}:\n")
        sb.append("$pad  " + (if (pieces.isEmpty()) (if (zh) "无" else "none") else pieces.joinToString(", ")) + "\n")
    }

    // 其余标量字段（非对象/数组）：中文映射 + 布尔化值
    val structuredKeys = setOf("neededLibraries", "attackSurface", "difficulty", "entropy",
        "segmentClass", "segmentPermissions", "flags")
    val shown = setOf("fileName", "size", "architecture", "bits", "elfType", "entryPoint",
        "endian", "sha256", "sha1", "md5", "compiler", "packer",
        "sectionCount", "functionCount", "symbolCount", "stringCount", "importCount",
        "exportCount", "securityFeatures", "cryptoFindings", "findings", "difficulty", "ok", "items")
    ov.keys().asSequence()
        .filter { it !in shown && it !in structuredKeys && !ov.isNull(it) }
        .filter { ov.opt(it) !is JSONObject && ov.opt(it) !is JSONArray }
        .forEach { k ->
            header()
            val v = ov.opt(k)
            val label = if (zh) fieldLabelCh(k) else k
            val vStr = when (v) {
                is Boolean -> if (v) (if (zh) "是" else "Yes") else (if (zh) "否" else "No")
                is Number -> if (k == "size") fmtBytes(v.toLong()) else v.toString()
                else -> v?.toString() ?: "-"
            }
            sb.append("$pad$label: $vStr\n")
        }
}

/** 判断字符串是否非空 */
private fun gh(s: String?): Boolean = !s.isNullOrBlank()

/** 组装 overview "其他信息" 的可读文字（供详细模式卡片显示；简洁模式直接写入 StringBuilder） */
private fun overviewExtrasText(ov: JSONObject, zh: Boolean): String {
    val sb = StringBuilder()
    describeOverviewExtras(ov, sb, zh)
    return sb.toString()
}

@Composable
private fun ExtraInfoText(ov: JSONObject, zh: Boolean) {
    val text = remember(ov, zh) { overviewExtrasText(ov, zh) }
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 字段名中文映射 */
private fun fieldLabelCh(k: String): String = when (k) {
    "timestamp" -> "时间戳"
    "compilerVersion" -> "编译器版本"
    "linkerVersion" -> "链接器版本"
    "debugInfo" -> "调试信息"
    "relocations" -> "重定位"
    "tls" -> "线程局部存储"
    "notes" -> "注释"
    "flags" -> "标志"
    "osAbi" -> "操作系统ABI"
    "abiVersion" -> "ABI版本"
    "machine" -> "机器类型"
    "linkFlags" -> "链接标志"
    "segmentCount" -> "段数量"
    "programHeaders" -> "程序头"
    "sections" -> "节区"
    "segments" -> "段"
    "libraries" -> "依赖库"
    "runpaths" -> "运行路径"
    "soname" -> "SO名称"
    "interpreter" -> "解释器"
    "baseAddr" -> "基址"
    "buildId" -> "Build ID"
    "stripped" -> "是否剥离符号"
    "totallyStripped" -> "符号几乎全无"
    "hasDebugInfo" -> "含调试信息"
    "hasJniOnLoad" -> "含JNI_OnLoad"
    "hasOriginalSectionHeaders" -> "保留节区头"
    "neededCount" -> "依赖库数"
    "dynsymCount" -> "动态符号数"
    "visibilityPct" -> "符号可见比例"
    "namedFunctionCount" -> "具名函数数"
    "jniCount" -> "JNI函数数"
    "elfTypeCode" -> "ELF类型编码"
    "architectureCode" -> "架构编码"
    "endianCode" -> "字节序编码"
    "hasDwarf" -> "含DWARF调试"
    "hasTextRel" -> "代码段可写"
    else -> k
}

// ============================================================
// 详细模式：结构化数据展示
// ============================================================

@Composable
private fun StructuredJsonView(text: String, zh: Boolean) {
    if (text.isBlank()) return
    val json = runCatching { JSONObject(text) }.getOrNull()
    if (json == null) { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface, lineHeight = 16.sp); return }

    val ov = json.optJSONObject("overview")
    if (ov != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionCard(if (zh) "📦 基础属性" else "📦 Basics") { JsonKeyValues(ov, zh, listOf("fileName","size","architecture","bits","elfType","entryPoint","endian","sha256","sha1","md5","compiler","packer")) }
            val countKeys = listOf("sectionCount","functionCount","symbolCount","stringCount","importCount","exportCount").filter { ov.has(it) }
            if (countKeys.isNotEmpty()) { SectionCard(if (zh) "📊 结构与规模" else "📊 Structure") { MetricRowFull(*countKeys.map { k -> (if (zh) k.replace("Count","") else k.replace("Count","")) to ov.optInt(k, 0).toString() }.toTypedArray()) } }
            val sec = ov.optJSONArray("securityFeatures")
            if (sec != null && sec.length() > 0) {
                SectionCard(if (zh) "🔒 安全特性" else "🔒 Security") {
                    for (i in 0 until sec.length()) {
                        val s = sec.optJSONObject(i) ?: continue; val label = s.optString("label", s.optString("id", "?"))
                        val active = s.optBoolean("active", false); val desc = s.optString("description", "")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface); Text(if (active) (if (zh) "✓ 启用" else "✓ Yes") else (if (zh) "✗ 未启用" else "✗ No"), style = MaterialTheme.typography.bodySmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        if (desc.isNotBlank()) Text("  $desc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label)
                    }
                }
            }
            val crypto = ov.optJSONArray("cryptoFindings")
            if (crypto != null && crypto.length() > 0) {
                SectionCard(if (zh) "🔐 加密特征" else "🔐 Crypto") {
                    for (i in 0 until crypto.length()) {
                        val c = crypto.optJSONObject(i) ?: continue; val name = c.optString("name", c.optString("algorithm", "?")); val count = c.optInt("count", c.optInt("matches", 0))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface); if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            val shown = setOf("fileName","size","architecture","bits","elfType","entryPoint","endian","sha256","sha1","md5","compiler","packer","sectionCount","functionCount","symbolCount","stringCount","importCount","exportCount","securityFeatures","cryptoFindings","findings","difficulty","ok","items")
            val extra = ov.keys().asSequence().filter { it !in shown && !ov.isNull(it) }.toList()
            if (extra.isNotEmpty()) { SectionCard(if (zh) "📋 其他信息" else "📋 Others") { ExtraInfoText(ov, zh) } }
        }
        val extCrypto = json.optJSONArray("cryptoFindings")
        if (extCrypto != null && extCrypto.length() > 0) { StructuredCryptoCard(extCrypto, zh) }
        return
    }
    val items = json.optJSONArray("items")
    if (items != null && items.length() > 0) {
        ItemsTable(items, zh)
        return
    }
    val cryptoArr = json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings") ?: json.optJSONArray("scans")
    if (cryptoArr != null && cryptoArr.length() > 0) { StructuredCryptoCard(cryptoArr, zh); return }
    val keys = json.keys().asSequence().filter { it != "ok" && it != "pagination" }.toList()
    if (keys.isEmpty()) { Text(json.toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface); return }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        keys.forEach { k -> val v = json.opt(k); val vStr = when (v) { is JSONObject -> "{${v.length()} fields}\n${v.toString(2).take(500)}"; is org.json.JSONArray -> "[${v.length()} items]\n${(0 until v.length()).joinToString("\n") { i -> "  [$i] ${v.opt(i)}" }.take(500)}"; null -> "—"; else -> v.toString() }; Kv(k, vStr) }
    }
}

@Composable
private fun ItemsTable(items: JSONArray, zh: Boolean) {
    // 收集标量列(排除嵌套对象/数组), 最多 8 列
    val columns = remember(items.length()) {
        val cols = LinkedHashSet<String>()
        for (i in 0 until minOf(items.length(), 50)) {
            val o = items.optJSONObject(i) ?: continue
            val ks = o.keys()
            while (ks.hasNext() && cols.size < 8) {
                val k = ks.next()
                val v = o.opt(k)
                if (v != null && v !is JSONObject && v !is org.json.JSONArray) cols.add(k)
            }
            if (cols.size >= 8) break
        }
        cols.toList()
    }
    if (columns.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until minOf(items.length(), 300)) {
                val item = items.opt(i)
                val line = when (item) {
                    is JSONObject -> item.toString()
                    is org.json.JSONArray -> "[${item.length()} sub-items]"
                    else -> item.toString()
                }
                Text("• $line", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val colW = 130.dp
    val rows = minOf(items.length(), 300)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(if (zh) "共 ${items.length()} 项 · 表格视图" else "${items.length()} items · table", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
        Row(Modifier.fillMaxWidth()) {
            columns.forEach { c -> Text(c, modifier = Modifier.width(colW).padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        androidx.compose.material3.HorizontalDivider()
        for (i in 0 until rows) {
            val o = items.optJSONObject(i)
            if (o == null) {
                Text("• ${items.opt(i)}", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                continue
            }
            Row(Modifier.fillMaxWidth()) {
                columns.forEach { c ->
                    val v = o.opt(c)
                    Text(v?.toString() ?: "—", modifier = Modifier.width(colW).padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (items.length() > rows) Text(if (zh) "… 仅显示前 $rows 项（共 ${items.length()} 项）" else "… showing first $rows of ${items.length()}", modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StructuredCryptoCard(cryptoArr: JSONArray, zh: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (zh) "共 ${cryptoArr.length()} 项发现" else "${cryptoArr.length()} findings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (i in 0 until cryptoArr.length()) {
            val c = cryptoArr.optJSONObject(i) ?: continue; val name = c.optString("name", c.optString("algorithm", c.optString("type", "?"))); val count = c.optInt("count", c.optInt("matches", 0)); val detail = c.optString("detail", c.optString("description", ""))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("• $name", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface); if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (detail.isNotBlank()) Text("  $detail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label)
        }
    }
}

@Composable
private fun JsonKeyValues(json: JSONObject, zh: Boolean, keys: List<String>) {
    keys.filter { json.has(it) && !json.isNull(it) }.forEach { k -> val v = json.opt(k); val label = when (k) { "fileName" -> if (zh) "名称" else "Name"; "size" -> if (zh) "大小" else "Size"; "architecture" -> if (zh) "架构" else "Arch"; "bits" -> if (zh) "位数" else "Bits"; "elfType" -> if (zh) "类型" else "Type"; "entryPoint" -> if (zh) "入口" else "Entry"; "endian" -> if (zh) "字节序" else "Endian"; "sha256" -> "SHA256"; "sha1" -> "SHA1"; "md5" -> "MD5"; "compiler" -> if (zh) "编译器" else "Compiler"; "packer" -> if (zh) "加壳" else "Packer"; else -> k }; val vStr = when (v) { is Number -> { if (k == "size") fmtBytes(v.toLong()) else v.toString() }; is Boolean -> if (v) (if (zh) "是" else "Yes") else (if (zh) "否" else "No"); else -> v.toString() }; Kv(label, vStr) }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AppShape.sm), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f))) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary); content() }
    }
}

@Composable
private fun Kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun MetricRowFull(vararg pairs: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { pairs.forEach { (label, value) -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppText.label) } } }
}

private fun fmtBytes(v: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB"); var u = 0; var value = v.toDouble()
    while (value >= 1024 && u < units.size - 1) { value /= 1024; u++ }
    return "%.1f %s".format(value, units[u])
}

/** 工作台顶层标签（IDA 风格）：可选带关闭按钮。 */
@Composable
private fun WbTab(label: String, selected: Boolean, onClose: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(AppShape.sm), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Row(
            Modifier.clickable(onClick = onClick).padding(start = 8.dp, end = if (onClose != null) 2.dp else 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "close", modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun monoStyle() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label)

/**
 * 十六进制视图：按偏移转储（纯 Java hexdump 工具，等宽显示，支持分页/跳转）。
 * 目标文件取当前任务主文件；若是 content:// 或为空，则提供文件路径输入。
 */
@Composable
private fun HexPane(state: WorkspaceState, zh: Boolean) {
    val ctx = LocalContext.current
    val task = state.currentTask()
    val scope = rememberCoroutineScope()
    var path by remember(task?.id) { mutableStateOf(task?.mainPath.orEmpty()) }
    var offsetText by remember { mutableStateOf("0") }
    var lengthText by remember { mutableStateOf("512") }
    var dump by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun parseOffset(s: String): Int = runCatching {
        val v = s.trim()
        if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
    }.getOrDefault(0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun load(off: Int) {
        val p = path.trim()
        if (p.isBlank()) { error = if (zh) "请先选择文件或填入绝对路径" else "Pick a file or enter an absolute path"; return }
        scope.launch {
            loading = true; error = ""
            val len = (lengthText.trim().toIntOrNull() ?: 512).coerceIn(16, 65536)
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    // v1.3.18: 原独立 hexdump 工具已收敛删除，这里直接用内置纯 Java HexDump。
                    val file = java.io.File(p)
                    if (!file.isFile) {
                        JSONObject().put("error", "file not found: " + p)
                    } else {
                        val size = file.length()
                        if (size > 128L * 1024 * 1024) {
                            JSONObject().put("error", "file too large for hex view: " + size + " bytes (max 128MiB)")
                        } else {
                            val data = file.readBytes()
                            val from = off.coerceIn(0, data.size)
                            val actual = len.coerceAtMost(data.size - from)
                            JSONObject()
                                .put("file", file.name)
                                .put("fileSize", data.size)
                                .put("offset", from)
                                .put("length", actual)
                                .put("hexdump", com.soreverse.mcp.engine.standalone.HexDump.dump(data, 0L, from, actual))
                        }
                    }
                }.getOrElse { e -> JSONObject().put("error", e.message ?: "hexdump failed") }
            }
            val e = res.optString("error")
            if (e.isNotBlank()) {
                error = e; dump = ""
            } else {
                dump = res.optString("hexdump")
                info = res.optString("file") + " · size=" + res.optInt("fileSize") + " · off=" + res.optInt("offset") + " · len=" + res.optInt("length")
                offsetText = "0x" + Integer.toHexString(res.optInt("offset"))
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = offsetText, onValueChange = { offsetText = it }, label = { Text("offset", fontSize = AppText.label) }, singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label))
            OutlinedTextField(value = lengthText, onValueChange = { lengthText = it }, label = { Text("len", fontSize = AppText.label) }, singleLine = true, modifier = Modifier.width(76.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label))
            TextButton(onClick = { load(parseOffset(offsetText)) }, enabled = !loading) { Text(if (zh) "转储" else "Dump", fontSize = AppText.label) }
        }
        if (path.isBlank() || path.startsWith("content://")) {
            OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text(if (zh) "文件绝对路径" else "Absolute file path", fontSize = AppText.label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label))
        }
        Spacer(Modifier.size(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val step = (lengthText.trim().toIntOrNull() ?: 512).coerceIn(16, 65536)
            TextButton(onClick = { load((parseOffset(offsetText) - step).coerceAtLeast(0)) }, enabled = !loading) { Text("◀", fontSize = AppText.label) }
            TextButton(onClick = { load(0) }, enabled = !loading) { Text(if (zh) "顶部" else "Top", fontSize = AppText.label) }
            TextButton(onClick = { load(parseOffset(offsetText) + step) }, enabled = !loading) { Text("▶", fontSize = AppText.label) }
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
            Text(info, style = monoStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(4.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(AppShape.sm), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)) {
            if (dump.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (zh) "点「转储」加载数据" else "Tap Dump to load", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(6.dp)) {
                    items(dump.split("\n")) { line -> HexLine(line) }
                }
            }
        }
    }
}

/** 单行 hexdump：偏移 / 字节 / ASCII 三段分色（优先走语法高亮）。 */
@Composable
private fun HexLine(line: String) {
    if (line.isBlank()) { Spacer(Modifier.height(2.dp)); return }
    val hl = highlightHexLine(line)
    if (hl != null) { Text(hl, style = monoStyle()); return }
    val parts = line.split(Regex("\\s{2,}"), limit = 3)
    Row(Modifier.fillMaxWidth()) {
        Text(parts.getOrElse(0) { "" }, style = monoStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (parts.size > 1) { Spacer(Modifier.size(8.dp)); Text(parts[1], style = monoStyle(), color = MaterialTheme.colorScheme.primary) }
        if (parts.size > 2) { Spacer(Modifier.size(8.dp)); Text(parts[2], style = monoStyle(), color = MaterialTheme.colorScheme.onSurface) }
    }
}

/** 单行 hexdump 的等宽分色（偏移 / 字节 / ASCII）；不像 hexdump 行则返回 null。 */
@Composable
private fun highlightHexLine(line: String): AnnotatedString? {
    val parts = line.split(Regex("\\s{2,}"), limit = 3)
    if (parts.size < 2 || !parts[0].matches(Regex("[0-9a-fA-F]{4,16}"))) return null
    val offColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hexColor = MaterialTheme.colorScheme.primary
    val ascColor = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        withStyle(SpanStyle(color = offColor, fontFamily = FontFamily.Monospace)) { append(parts[0]) }
        append("  ")
        withStyle(SpanStyle(color = hexColor, fontFamily = FontFamily.Monospace)) { append(parts[1]) }
        if (parts.size > 2) {
            append("  ")
            withStyle(SpanStyle(color = ascColor, fontFamily = FontFamily.Monospace)) { append(parts[2]) }
        }
    }
}

private data class TreeCat(val key: String, val zh: String, val en: String)

private val treeCats = listOf(
    TreeCat("sections", "节区", "Sections"),
    TreeCat("symbols", "符号", "Symbols"),
    TreeCat("functions", "函数", "Functions"),
    TreeCat("strings", "字符串", "Strings"),
    TreeCat("imports", "导入", "Imports"),
)

/** 容错提取列表类返回里的名字/地址。 */
private fun extractNames(o: JSONObject?): List<String> {
    if (o == null) return emptyList()
    val arr = o.optJSONArray("items") ?: o.optJSONArray("values") ?: o.optJSONArray("list")
        ?: o.optJSONArray("entries") ?: o.optJSONArray("data") ?: o.optJSONArray("result") ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is String -> out.add(v)
            is JSONObject -> {
                val n = v.optString("name").ifBlank { v.optString("va").ifBlank { v.optString("address").ifBlank { v.optString("id") } } }
                if (n.isNotBlank()) out.add(n)
            }
            else -> {}
        }
    }
    return out
}

/** 反汇编单行：地址 / 助记符 / 寄存器 / 立即数 / 注释 分色（IDA 风格）。 */
@Composable
private fun DisasmLine(line: String) {
    if (line.isBlank()) { Spacer(Modifier.height(2.dp)); return }
    val cs = MaterialTheme.colorScheme
    val out = buildAnnotatedString {
        val ci = line.indexOf(';')
        val code = if (ci >= 0) line.substring(0, ci) else line
        val comment = if (ci >= 0) line.substring(ci) else ""
        var first = true
        val tokens = code.split(" ")
        tokens.forEachIndexed { ti, tk ->
            if (ti > 0) append(" ")
            if (tk.isEmpty()) return@forEachIndexed
            val isAlpha = tk.matches(Regex("[a-z][a-z0-9.]*"))
            val color = when {
                tk.matches(Regex("(0x)?[0-9a-fA-F]{4,16}:?")) -> cs.onSurfaceVariant
                tk.startsWith("#") || tk.matches(Regex("0x[0-9a-fA-F]+")) -> cs.secondary
                tk.matches(Regex("(x|w|q|d|s|v)[0-9]{1,2}|sp|lr|pc|wzr|xzr|fp|rax|rbx|rcx|rdx|rsi|rdi")) -> cs.tertiary
                first && isAlpha -> cs.primary
                else -> cs.onSurface
            }
            withStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = AppText.label, fontWeight = if (first && isAlpha) FontWeight.SemiBold else FontWeight.Normal)) { append(tk) }
            if (isAlpha) first = false
        }
        if (comment.isNotEmpty()) {
            withStyle(SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace, fontSize = AppText.label)) { append(comment) }
        }
    }
    Text(out, style = monoStyle())
}

// ============================================================
// 分析页 · 仿 Exbin 布局的导航栏 / 顶部条 / 各视图
// ============================================================

/** 左侧导航项：key 用英文短 id，short 是 56dp 栏里的短标签。 */
private data class AnalysisNavItem(val key: String, val short: String, val en: String, val icon: ImageVector)

/** 列表行（由引擎 JSON 归一化而来，供 CardRow 渲染）。 */
private data class AnalysisRow(val key: String, val title: String, val meta: String, val va: String, val text: String)

private val analysisNavItems = listOf(
    AnalysisNavItem("functions", "函数", "Fns", Icons.Filled.Memory),
    AnalysisNavItem("search", "搜索", "Find", Icons.Filled.Search),
    AnalysisNavItem("disasm", "汇编", "Asm", Icons.Filled.Code),
    AnalysisNavItem("pseudo", "伪C", "Pseudo", Icons.Filled.Description),
    AnalysisNavItem("cfg", "CFG", "CFG", Icons.Filled.CompareArrows),
    AnalysisNavItem("strings", "字符串", "Str", Icons.Filled.DataObject),
    AnalysisNavItem("symbols", "符号", "Sym", Icons.Filled.ListAlt),
    AnalysisNavItem("imports", "导入", "Imp", Icons.Filled.Link),
    AnalysisNavItem("sections", "段节", "Sec", Icons.Filled.Inventory2),
    AnalysisNavItem("elfhdr", "ELF头", "ELF", Icons.Filled.Info),
    AnalysisNavItem("segments", "程序段", "Seg", Icons.Filled.Inventory2),
    AnalysisNavItem("relocs", "重定位", "Rel", Icons.Filled.Link),
    AnalysisNavItem("dynamic", "动态", "Dyn", Icons.Filled.FlashOn),
    AnalysisNavItem("libraries", "依赖库", "Lib", Icons.Filled.FolderOpen),
    AnalysisNavItem("hashes", "哈希", "Hash", Icons.Filled.LockOpen),
    AnalysisNavItem("versions", "版本", "Ver", Icons.Filled.Refresh),
    AnalysisNavItem("entries", "入口点", "Ent", Icons.Filled.MyLocation),
    AnalysisNavItem("hex", "HEX", "Hex", Icons.Filled.Storage),
    AnalysisNavItem("demangle", "C++", "C++", Icons.Filled.Transform),
    AnalysisNavItem("base", "进制", "Base", Icons.Filled.Calculate),
    AnalysisNavItem("asm", "汇编器", "Asm+", Icons.Filled.SwapHoriz),
    AnalysisNavItem("results", "结果", "Out", Icons.Filled.Terminal),
    AnalysisNavItem("tools", "工具", "Tools", Icons.Filled.Build),
    AnalysisNavItem("hub", "工具集", "Hub", Icons.Filled.Build),
    AnalysisNavItem("xor", "XOR", "XOR", Icons.Filled.LockOpen),
    AnalysisNavItem("regs", "寄存器", "Reg", Icons.Filled.Memory),
    AnalysisNavItem("strdec", "解码", "Dec", Icons.Filled.DataObject),
    AnalysisNavItem("bytediff", "差分", "Diff", Icons.Filled.CompareArrows),
    AnalysisNavItem("insnexp", "指令", "Insn", Icons.Filled.Description),
    AnalysisNavItem("asm2c", "译C", "→C", Icons.Filled.Transform),
    AnalysisNavItem("asm2flow", "框图", "Flow", Icons.Filled.Inventory2),
    AnalysisNavItem("vtable", "虚表", "Vtab", Icons.Filled.Transform),
    AnalysisNavItem("xrefs", "引用", "XRef", Icons.Filled.Link),
    AnalysisNavItem("addrview", "地址", "Addr", Icons.Filled.MyLocation),
    AnalysisNavItem("funcsig", "签名", "Sig", Icons.Filled.ListAlt),
    AnalysisNavItem("jnireg", "JNI", "JNI", Icons.Filled.DataObject),
    AnalysisNavItem("hardening", "加固", "Hard", Icons.Filled.Warning),
    AnalysisNavItem("callgraph", "调用图", "Calls", Icons.Filled.CompareArrows),
    AnalysisNavItem("export", "导出", "Export", Icons.Filled.Terminal),
    AnalysisNavItem("unpack", "脱壳", "Unpk", Icons.Filled.LockOpen),
    AnalysisNavItem("data", "数据", "Data", Icons.Filled.Inventory2),
    AnalysisNavItem("funcinfo", "函数详情", "Detail", Icons.Filled.Description),
    AnalysisNavItem("comments", "注释", "Note", Icons.Filled.Description),
    AnalysisNavItem("analyze", "分析", "Ana", Icons.Filled.FlashOn),
    AnalysisNavItem("rootdrill", "根下钻", "Root", Icons.Filled.Transform),
    AnalysisNavItem("edit", "编辑", "Edit", Icons.Filled.Build),
    AnalysisNavItem("globalc", "全局伪C", "GpC", Icons.Filled.Description),
)

private fun analysisViewLabel(view: String, zh: Boolean): String =
    analysisNavItems.firstOrNull { it.key == view }?.let { if (zh) it.short else it.en } ?: view

private fun analysisViewIcon(view: String): ImageVector =
    analysisNavItems.firstOrNull { it.key == view }?.icon ?: Icons.Filled.ListAlt

// ───────────────────────── 通用小工具 ─────────────────────────

/** 小号动作按钮（Exbin 风：圆角 + 1dp 描边 + 无阴影）。 */
@Composable
private fun SmallAction(
    label: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(AppShape.sm),
        color = if (active) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (active) cs.primary.copy(alpha = 0.45f) else cs.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 2.dp)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                maxLines = 1,
                color = if (enabled) cs.primary else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalysisLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun AnalysisEmptyState(
    title: String,
    hint: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontSize = AppText.title,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                fontSize = AppText.body,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (primaryLabel != null && onPrimary != null) SmallAction(primaryLabel, onClick = onPrimary)
                if (secondaryLabel != null && onSecondary != null) SmallAction(secondaryLabel, onClick = onSecondary)
            }
        }
    }
}

/** 错误横幅（把引擎 error.message 明确显示出来，而不是只留空白）。 */
@Composable
private fun AnalysisErrorBanner(message: String) {
    if (message.isBlank()) return
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.sm))
            .background(cs.errorContainer.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = cs.error, modifier = Modifier.size(15.dp))
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            fontSize = AppText.label,
            color = cs.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MonoScreen(text: String) {
    Box(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 17.sp,
        )
    }
}

private fun copyToClipboard(context: android.content.Context, text: String, zh: Boolean) {
    if (text.isBlank()) return
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("taffy", text))
    }
    val shown = text.take(60)
    Toast.makeText(context, if (zh) "已复制：$shown" else "Copied: $shown", Toast.LENGTH_SHORT).show()
}

private fun errJson(message: String): String = JSONObject()
    .put("ok", false)
    .put("error", JSONObject().put("code", "TAFFY_UI_ERROR").put("message", message))
    .toString()

/** 从引擎返回的 JSON 里取出 error.message（无错返回空串）。 */
private fun errMessageOf(json: String?): String {
    if (json.isNullOrBlank()) return ""
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return ""
    if (o.optBoolean("ok", true)) return ""
    return o.optJSONObject("error")?.optString("message").orEmpty()
        .ifBlank { o.optJSONObject("error")?.optString("code").orEmpty() }
        .ifBlank { o.optString("message") }
}

private fun buildMeta(addr: String, size: Long, kind: String): String {
    val parts = ArrayList<String>(3)
    if (addr.isNotBlank()) parts.add(addr)
    if (size >= 0L) parts.add("$size B")
    if (kind.isNotBlank()) parts.add(kind)
    return parts.joinToString(" · ")
}

/** 引擎 JSON → 归一化列表行（兼容 items / functions 两种数组字段）。 */
private fun rowsOf(json: String?, view: String): List<AnalysisRow> {
    if (json.isNullOrBlank()) return emptyList()
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val arr = o.optJSONArray("items") ?: o.optJSONArray("functions") ?: return emptyList()
    val out = ArrayList<AnalysisRow>(arr.length())
    for (i in 0 until arr.length()) {
        val it = arr.optJSONObject(i) ?: continue
        val loc = it.optString("locator")
        when (view) {
            "functions" -> out.add(
                AnalysisRow(
                    key = "f$i|$loc",
                    title = it.optString("name").ifBlank { it.optString("startAddr") }.ifBlank { "func $i" },
                    meta = buildMeta(
                        it.optString("startAddr").ifBlank { it.optString("addr") },
                        it.optLong("size", -1L),
                        it.optString("kind").ifBlank { it.optString("section") },
                    ),
                    va = it.optString("startAddr").ifBlank { it.optString("addr") },
                    text = it.optString("name"),
                ),
            )
            "strings" -> out.add(
                AnalysisRow(
                    key = "s$i|$loc",
                    title = it.optString("value").ifBlank { "(空)" },
                    meta = buildMeta(
                        it.optString("offset"),
                        it.optLong("length", -1L),
                        it.optString("encoding").ifBlank { it.optString("section") },
                    ),
                    va = it.optString("offset"),
                    text = it.optString("value"),
                ),
            )
            "sections" -> out.add(
                AnalysisRow(
                    key = "sec$i|$loc",
                    title = it.optString("name").ifBlank { "section $i" },
                    meta = buildMeta(
                        it.optString("addr").ifBlank { it.optString("virtualAddr") },
                        it.optLong("size", -1L),
                        it.optString("flags").ifBlank { it.optString("type") },
                    ),
                    va = it.optString("addr").ifBlank { it.optString("virtualAddr") },
                    text = it.optString("name"),
                ),
            )
            "imports" -> out.add(
                AnalysisRow(
                    key = "i$i|$loc",
                    title = it.optString("symbol").ifBlank { it.optString("name") },
                    meta = buildMeta(
                        "",
                        -1L,
                        listOf(it.optString("type"), it.optString("bind"), it.optString("section"))
                            .filter { s -> s.isNotBlank() }.joinToString(" / "),
                    ),
                    va = "",
                    text = it.optString("symbol"),
                ),
            )
            else -> out.add(
                AnalysisRow(
                    key = "g$i|$loc",
                    title = it.optString("name").ifBlank { it.optString("symbol") }
                        .ifBlank { it.optString("value") }.ifBlank { "item ${i + 1}" },
                    meta = buildMeta(
                        it.optString("value").ifBlank { it.optString("startAddr") },
                        it.optLong("size", -1L),
                        it.optString("type").ifBlank { it.optString("bind") }
                            .ifBlank { it.optString("visibility") },
                    ),
                    va = it.optString("value").ifBlank { it.optString("startAddr") },
                    text = it.optString("name").ifBlank { it.optString("symbol") },
                ),
            )
        }
    }
    return out
}

/** CardRow 列表（12dp 圆角行 + 选中态高亮 + 分隔线）。 */
@Composable
private fun AnalysisRowList(
    rows: List<AnalysisRow>,
    zh: Boolean,
    icon: ImageVector,
    selectedTitle: String = "",
    onPick: (AnalysisRow) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(rows, key = { r -> r.key }) { row ->
            val selected = selectedTitle.isNotBlank() && row.title == selectedTitle
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent),
            ) {
                CardRow(
                    title = row.title,
                    meta = row.meta.ifBlank { null },
                    icon = icon,
                    iconTint = if (selected) cs.primary else cs.onSurfaceVariant,
                    trailing = {
                        if (selected) {
                            Text(
                                if (zh) "已选" else "on",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = AppText.label,
                                color = cs.primary,
                            )
                        }
                    },
                    onClick = { onPick(row) },
                )
            }
            GroupDivider()
        }
    }
}

// ───────────────────────── 取数（均在工作区内，失败一律落成 error JSON） ─────────────────────────

private fun rawListCall(
    context: android.content.Context,
    ws: String,
    view: String,
    prefix: String,
    limit: Int,
): JSONObject = when (view) {
    "functions" -> EngineProvider.get(context).rzFunctions(ws, "", limit)
    else -> EngineProvider.get(context).list(ws, "", view, prefix, limit)
}

/** 列表视图统一取数（带 viewCache 去重：已有缓存则不发请求）。 */
private suspend fun loadListCache(
    context: android.content.Context,
    tools: ToolPagesState,
    view: String,
    ws: String,
    cacheKey: String,
    limit: Int,
    prefix: String = "",
) {
    if (ws.isBlank()) return
    if (tools.viewCache.containsKey(cacheKey)) return
    tools.viewLoading = cacheKey
    val r = withContext(Dispatchers.IO) {
        runCatching { rawListCall(context, ws, view, prefix, limit) }.getOrNull()
    }
    tools.viewLoading = ""
    tools.cacheView(
        cacheKey,
        r?.toString() ?: errJson("失败：引擎无响应"),
    )
}

private suspend fun fetchDisasm(
    context: android.content.Context,
    tools: ToolPagesState,
    zh: Boolean,
    ws: String,
    target: String,
    key: String,
    limit: Int,
) {
    if (ws.isBlank() || target.isBlank()) return
    tools.viewLoading = key
    val r = withContext(Dispatchers.IO) {
        runCatching {
            EngineProvider.get(context).disasm(ws, "", target, limit, "", 0, 0, 65536, "", null, "auto")
        }.getOrNull()
    }
    tools.viewLoading = ""
    tools.disasmKey = key
    tools.disasmJson = r?.toString()
        ?: errJson(if (zh) "反汇编失败：引擎无响应" else "disassembly failed: engine did not respond")
}

private suspend fun fetchPseudo(
    context: android.content.Context,
    tools: ToolPagesState,
    zh: Boolean,
    ws: String,
    target: String,
    key: String,
    engineMode: String = "auto",
) {
    if (ws.isBlank() || target.isBlank()) return
    tools.viewLoading = key
    // 统一走 taffy_so_decompile 工具：支持 ghidra(pdg) / native(pdc) / java(纯 Kotlin 启发式) / auto 降级
    val r = callMcpTool(context, "taffy_so_decompile", JSONObject()
        .put("workspaceId", ws)
        .put("locator", target)
        .put("strict", engineMode == "ghidra")
        .put("engine", engineMode))
    tools.viewLoading = ""
    tools.pseudoKey = key
    tools.pseudoJson = r?.toString()
        ?: errJson(if (zh) "反编译失败：引擎无响应" else "decompile failed: engine did not respond")
}

/** CFG 查询：locator 必须是函数入口（符号名或 hex VA）。错误 JSON 会原样透传给画布。 */
private fun loadCfg(
    context: android.content.Context,
    tools: ToolPagesState,
    zh: Boolean,
    scope: CoroutineScope,
    target: String,
) {
    val ws = tools.sharedWorkspaceId
    if (ws.isBlank() || target.isBlank()) return
    tools.cfgTarget = target
    tools.cfgVisible = true
    scope.launch {
        tools.cfgLoading = true
        tools.cfgJson = ""
        val r = withContext(Dispatchers.IO) {
            runCatching { EngineProvider.get(context).rzCfg(ws, "", target) }.getOrNull()
        }
        tools.cfgLoading = false
        tools.cfgJson = r?.toString()
            ?: errJson(if (zh) "CFG 查询失败：引擎无响应" else "CFG query failed: engine did not respond")
    }
}

private fun funcVaAt(arr: JSONArray?, i: Int): String {
    val o = arr?.optJSONObject(i) ?: return ""
    return o.optString("startAddr").ifBlank { o.optString("addr") }
}

private fun funcNameAt(arr: JSONArray?, i: Int): String {
    val o = arr?.optJSONObject(i) ?: return ""
    return o.optString("name").ifBlank { funcVaAt(arr, i) }
}

/**
 * 入口点兜底：优先「ELF 入口点恰好是某个函数入口 → 该函数」，
 * 其次「ELF 入口点本身能被 rzCfg 接受」，再次「函数列表第一个」，最后「list(functions) 第一项」。
 * 保证用户一键就能看到一张图，而不是空画布。
 */
private suspend fun resolveCfgEntry(
    context: android.content.Context,
    tools: ToolPagesState,
): Pair<String, String>? = withContext<Pair<String, String>?>(Dispatchers.IO) {
    val ws = tools.sharedWorkspaceId
    if (ws.isBlank()) return@withContext null
    val engine = EngineProvider.get(context)
    val funcs = runCatching { engine.rzFunctions(ws, "", 160) }.getOrNull()?.optJSONArray("functions")
    val entry = runCatching { engine.readElf(ws, "") }.getOrNull()?.optString("entryPoint").orEmpty()

    if (entry.isNotBlank() && funcs != null) {
        for (i in 0 until funcs.length()) {
            val va = funcVaAt(funcs, i)
            if (va.isNotBlank() && va.equals(entry, ignoreCase = true)) {
                return@withContext (funcNameAt(funcs, i) to va)
            }
        }
    }
    if (entry.isNotBlank()) {
        val r = runCatching { engine.rzCfg(ws, "", entry) }.getOrNull()
        if (r != null && r.optBoolean("ok", false)) return@withContext (entry to entry)
    }
    if (funcs != null && funcs.length() > 0) {
        val va = funcVaAt(funcs, 0)
        if (va.isNotBlank()) return@withContext (funcNameAt(funcs, 0) to va)
    }
    val item = runCatching { engine.list(ws, "", "functions", "", 1) }.getOrNull()
        ?.optJSONArray("items")?.optJSONObject(0)
    if (item != null) {
        val va = item.optString("startAddr")
        return@withContext (item.optString("name").ifBlank { va } to va)
    }
    null
}

// ───────────────────────── 左侧导航栏 ─────────────────────────

@Composable
private fun AnalysisNavRail(current: String, zh: Boolean, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(cs.surface.copy(alpha = 0.55f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        analysisNavItems.forEach { item ->
            val selected = item.key == current
            Box(
                Modifier.fillMaxWidth().height(48.dp).clickable { onPick(item.key) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                            .background(cs.primary),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = if (zh) item.short else item.en,
                        tint = if (selected) cs.primary else cs.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        if (zh) item.short else item.en,
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = if (selected) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

// ───────────────────────── 顶部条 ─────────────────────────

@Composable
private fun AnalysisTopBar(
    state: WorkspaceState,
    tools: ToolPagesState,
    zh: Boolean,
    view: String,
    onPickFunction: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTree: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenTask: () -> Unit,
) {
    val metrics = LocalUiMetrics.current
    val cs = MaterialTheme.colorScheme
    val fnName = tools.selectedFunctionName
    val fnVa = tools.selectedFunctionVa
    val chipShape = RoundedCornerShape(AppShape.md)
    val busy = tools.viewLoading.isNotBlank() || tools.cfgLoading

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = metrics.pagePad, end = 6.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 当前函数（点一下 → 函数列表换一个）
            Row(
                Modifier.weight(1f).clip(chipShape)
                    .background(cs.surfaceContainerHigh)
                    .border(BorderStroke(1.dp, cs.outlineVariant), chipShape)
                    .clickable(onClick = onPickFunction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Memory, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (fnName.isBlank()) (if (zh) "未选择函数" else "No function") else fnName,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = AppText.bodyStrong,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (fnName.isBlank()) cs.primary else cs.onSurface,
                    )
                    Text(
                        if (fnName.isBlank()) {
                            if (zh) "点这里去函数列表" else "tap to open function list"
                        } else {
                            "${fnVa.ifBlank { "--" }} · ${analysisViewLabel(view, zh)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = AppText.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = if (zh) "刷新" else "Refresh", tint = cs.primary)
            }
            IconButton(onClick = onOpenTree) {
                Icon(Icons.Filled.ListAlt, contentDescription = if (zh) "对象树" else "Objects", tint = cs.onSurfaceVariant)
            }
            IconButton(onClick = onOpenOutput) {
                Icon(Icons.Filled.Terminal, contentDescription = if (zh) "输出" else "Output", tint = cs.onSurfaceVariant)
            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = metrics.pagePad),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WorkspacePicker(state, zh)
            TaskChip(state, zh, onOpenTask)
        }
        Spacer(Modifier.size(6.dp))
    }
}

/** 当前任务小条（点击 → 任务页）。 */
@Composable
private fun TaskChip(state: WorkspaceState, zh: Boolean, onOpenTask: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val task = state.currentTask()
    val shape = RoundedCornerShape(AppShape.sm)
    Row(
        Modifier.clip(shape)
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onOpenTask)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Filled.FolderOpen, null, tint = cs.primary, modifier = Modifier.size(13.dp))
        Text(
            (task?.title ?: (if (zh) "选择任务" else "Pick task")).take(14),
            style = MaterialTheme.typography.labelSmall,
            fontSize = AppText.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (task != null) cs.onSurface else cs.primary,
        )
        if (task != null && state.tools.sharedWorkspaceId.isBlank()) {
            Text("⚠", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.error)
        }
    }
}

// ───────────────────────── 函数列表（默认视图 / 页面入口） ─────────────────────────

/** 函数行的结构化数据（直接来自引擎 functions 数组，不再经 meta 字符串拆解）。 */
private data class FnItem(val addr: String, val name: String, val size: Long, val kind: String)

private fun parseFunctions(json: String?): List<FnItem> {
    if (json.isNullOrBlank()) return emptyList()
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val arr = o.optJSONArray("functions") ?: o.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<FnItem>(arr.length())
    for (i in 0 until arr.length()) {
        val it = arr.optJSONObject(i) ?: continue
        val addr = it.optString("startAddr").ifBlank { it.optString("addr") }
        val name = it.optString("name").ifBlank { it.optString("locator") }.ifBlank { "sub_$addr" }
        out.add(FnItem(addr, name, it.optLong("size", -1L), it.optString("kind").ifBlank { it.optString("type") }))
    }
    return out
}

/** 十六进制地址 → Long（排序用；解析不了按 0）。 */
private fun hexVal(s: String): Long = runCatching {
    val v = s.trim()
    if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
}.getOrDefault(0L)

/** 表头单元格：可点排序，当前排序列显示箭头。 */
@Composable
private fun SortHeader(
    label: String,
    active: Boolean,
    asc: Boolean,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.clickable { onClick() }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = AppText.label,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) cs.primary else cs.onSurfaceVariant,
            maxLines = 1,
        )
        if (active) {
            Icon(
                if (asc) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null,
                tint = cs.primary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 函数表：地址(等宽，点击复制) | 名称 + 类型 | 大小；表头可点排序。 */
@Composable
private fun FunctionsView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onOpenTree: () -> Unit,
    onSelect: (String, String) -> Unit,
) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val cacheKey = "functions|$ws|"

    LaunchedEffect(cacheKey, tools.reloadTick) {
        loadListCache(context, tools, "functions", ws, cacheKey, 300)
    }

    val all = remember(tools.viewCache[cacheKey]) { parseFunctions(tools.viewCache[cacheKey]) }
    val query = tools.functionQuery
    var sortBy by remember { mutableStateOf("addr") }
    var asc by remember { mutableStateOf(true) }

    val shown = remember(all, query, sortBy, asc) {
        val fl = if (query.isBlank()) all
        else all.filter { it.name.contains(query, true) || it.addr.contains(query, true) }
        val sorted = when (sortBy) {
            "name" -> fl.sortedBy { it.name.lowercase() }
            "size" -> fl.sortedBy { it.size }
            else -> fl.sortedBy { hexVal(it.addr) }
        }
        if (asc) sorted else sorted.reversed()
    }

    fun toggleSort(key: String) {
        if (sortBy == key) asc = !asc else { sortBy = key; asc = !(key == "size") }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { tools.functionQuery = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant) },
            placeholder = {
                Text(
                    if (zh) "按函数名 / 地址过滤" else "filter by name / address",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                    color = cs.onSurfaceVariant,
                )
            },
        )
        Spacer(Modifier.size(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
            SmallAction(if (zh) "对象树" else "Objects", onClick = onOpenTree)
            Text(
                if (zh) "${shown.size} 个函数" else "${shown.size} functions",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ws.isBlank() -> AnalysisEmptyState(
                    title = if (zh) "未打开工作区" else "No workspace",
                    hint = if (zh) "先用顶部「选文件」打开一个 SO / APK" else "Open a SO / APK from the top bar first",
                )
                tools.viewLoading == cacheKey && all.isEmpty() -> AnalysisLoading()
                all.isEmpty() && !tools.viewCache.containsKey(cacheKey) -> {
                    val err = errMessageOf(tools.viewCache[cacheKey])
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnalysisErrorBanner(err.ifBlank { if (zh) "函数列表加载失败" else "failed to load functions" })
                        Text(
                            if (zh) "点「刷新」重试" else "Tap Refresh to retry",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = AppText.body,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                shown.isEmpty() -> AnalysisEmptyState(
                    title = if (zh) "无匹配函数" else "No match",
                    hint = if (zh) "换个关键字，或点「刷新」重新拉取" else "Try another keyword, or Refresh",
                    primaryLabel = if (zh) "刷新" else "Refresh",
                    onPrimary = onRefresh,
                )
                else -> Column(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHigh)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
                ) {
                    // 表头
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SortHeader(if (zh) "地址" else "ADDR", sortBy == "addr", asc, modifier = Modifier.width(90.dp)) { toggleSort("addr") }
                        SortHeader(if (zh) "名称" else "NAME", sortBy == "name", asc, modifier = Modifier.weight(1f)) { toggleSort("name") }
                        SortHeader(if (zh) "大小" else "SIZE", sortBy == "size", asc, alignEnd = true, modifier = Modifier.width(58.dp)) { toggleSort("size") }
                    }
                    GroupDivider()
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(shown, key = { r -> r.addr + "|" + r.name }) { r ->
                            val selected = r.name == tools.selectedFunctionName
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
                                    .clickable { onSelect(r.name, r.addr) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    r.addr.ifBlank { "--" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                    color = if (selected) cs.primary else cs.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.width(90.dp).clickable { copyToClipboard(context, r.addr, zh) },
                                )
                                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                                    Text(
                                        r.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) cs.primary else cs.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (r.kind.isNotBlank()) {
                                        Text(
                                            r.kind,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = AppText.label,
                                            color = cs.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Text(
                                    if (r.size >= 0L) "${r.size}" else "--",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(58.dp),
                                )
                            }
                            GroupDivider()
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 搜索 ─────────────────────────

private val searchScopes = listOf(
    "functions" to ("函数" to "Fns"),
    "symbols" to ("符号" to "Sym"),
    "strings" to ("字符串" to "Str"),
    "imports" to ("导入" to "Imp"),
    "sections" to ("节区" to "Sec"),
    "relocs" to ("重定位" to "Rel"),
    "dynamic" to ("动态" to "Dyn"),
    "entries" to ("入口点" to "Ent"),
)

/** 全库搜索：输入框 + 范围切换 + 结果表格（地址/内容两列，按范围自适应列名）。 */
@Composable
private fun SearchView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onSelect: (String, String) -> Unit,
) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = tools.searchScope
    val query = tools.searchQuery
    var regexOn by remember { mutableStateOf(false) }
    var caseOn by remember { mutableStateOf(false) }
    val cacheKey = if (regexOn) "search|$ws|$scope|__all__" else "search|$ws|$scope|$query"

    LaunchedEffect(cacheKey, tools.reloadTick) {
        if (query.isNotBlank()) {
            if (regexOn) loadListCache(context, tools, scope, ws, cacheKey, 3000, "")
            else loadListCache(context, tools, scope, ws, cacheKey, 120, query)
        }
    }

    val rawRows = remember(tools.viewCache[cacheKey]) { rowsOf(tools.viewCache[cacheKey], scope) }
    val regexErr = remember(query, regexOn, caseOn) {
        if (!regexOn || query.isBlank()) ""
        else runCatching { Regex(query, if (caseOn) emptySet() else setOf(RegexOption.IGNORE_CASE)); "" }
            .getOrElse { it.message ?: "regex error" }
    }
    val rows = remember(rawRows, query, regexOn, caseOn, regexErr) {
        when {
            query.isBlank() -> emptyList()
            regexOn -> {
                if (regexErr.isNotBlank()) emptyList()
                else {
                    val re = runCatching { Regex(query, if (caseOn) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
                    if (re == null) emptyList() else rawRows.filter { re.containsMatchIn(it.title) || re.containsMatchIn(it.text) }
                }
            }
            caseOn -> rawRows.filter { it.title.contains(query) || it.text.contains(query) }
            else -> rawRows
        }
    }
    val secondCol = when (scope) {
        "strings" -> if (zh) "内容" else "VALUE"
        "imports" -> if (zh) "名称" else "NAME"
        else -> if (zh) "名称" else "NAME"
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { tools.searchQuery = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant) },
            placeholder = {
                Text(
                    if (zh) "在函数 / 符号 / 字符串 / 导入里搜索" else "search across functions / symbols / strings / imports",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        Spacer(Modifier.size(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            searchScopes.forEach { (key, labels) ->
                val active = key == scope
                SmallAction(if (zh) labels.first else labels.second, active = active) { tools.searchScope = key }
            }
            SmallAction(".*", active = regexOn) { regexOn = !regexOn }
            SmallAction("Aa", active = caseOn) { caseOn = !caseOn }
            if (rows.isNotEmpty()) {
                Text(
                    if (zh) "${rows.size} 条" else "${rows.size} hits",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ws.isBlank() -> AnalysisEmptyState(
                    title = if (zh) "未打开工作区" else "No workspace",
                    hint = if (zh) "先用顶部「选文件」打开一个 SO / APK" else "Open a SO / APK from the top bar first",
                )
                query.isBlank() -> AnalysisEmptyState(
                    title = if (zh) "输入关键字开始搜索" else "Type a keyword to search",
                    hint = if (zh) "范围：${analysisViewLabel(scope, zh)}" else "Scope: ${analysisViewLabel(scope, zh)}",
                )
                tools.viewLoading == cacheKey && rows.isEmpty() -> AnalysisLoading()
                rows.isEmpty() -> {
                    val err = if (regexErr.isNotBlank()) (if (zh) "正则表达式错误：$regexErr" else "regex error: $regexErr")
                        else errMessageOf(tools.viewCache[cacheKey])
                    if (err.isNotBlank()) AnalysisErrorBanner(err)
                    else AnalysisEmptyState(
                        title = if (zh) "无匹配结果" else "No results",
                        hint = if (zh) "换个关键字或切换范围" else "Try another keyword or scope",
                    )
                }
                else -> Column(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHigh)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (zh) "地址" else "ADDR",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = AppText.label,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.width(90.dp),
                        )
                        Text(
                            secondCol,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = AppText.label,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GroupDivider()
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(rows, key = { r -> r.key }) { row ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (scope == "functions") onSelect(row.title, row.va)
                                        else copyToClipboard(context, row.text.ifBlank { row.title }, zh)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    row.va.ifBlank { "--" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.width(90.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.text.ifBlank { row.title },
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                                        color = cs.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (row.meta.isNotBlank()) {
                                        Text(
                                            row.meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = AppText.label,
                                            color = cs.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            GroupDivider()
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 反汇编 ─────────────────────────

/** 拆一行反汇编：地址 / 机器码 / 指令。机器码判定保守（连续十六进制字节）。 */
private fun splitDisasmLine(line: String): Triple<String, String, String> {
    val trimmed = line.trimStart()
    if (trimmed.isEmpty()) return Triple("", "", "")
    val m = Regex("^(0x[0-9a-fA-F]+)\\s+(.*)$").find(trimmed)
    if (m == null) return Triple("", "", trimmed)
    val addrOut = m.groupValues[1]
    var rest = m.groupValues[2]
    // 机器码：形如 "e0030091" 或 "e0 03 00 91"（≥2 个字节才认为是指令编码，避免把 1 字节误吸）
    val bytesRe = Regex("^([0-9a-fA-F]{2}(?:\\s+[0-9a-fA-F]{2})*)\\s+(.*)$")
    val joined = Regex("^([0-9a-fA-F]{6,})\\s+(.*)$").find(rest)
    if (joined != null && joined.groupValues[1].length % 2 == 0) {
        return Triple(addrOut, joined.groupValues[1].chunked(2).joinToString(" "), joined.groupValues[2])
    }
    val bm = bytesRe.find(rest)
    if (bm != null && bm.groupValues[1].split(Regex("\\s+")).size >= 2) {
        return Triple(addrOut, bm.groupValues[1], bm.groupValues[2])
    }
    return Triple(addrOut, "", rest)
}

/** 指令文本按 token 分色（助记符 primary / 寄存器 tertiary / 立即数 secondary / 注释弱化）。 */
private fun disasmInstrAnnotated(instr: String, cs: androidx.compose.material3.ColorScheme): AnnotatedString {
    val ci = instr.indexOf(';')
    val code = if (ci >= 0) instr.substring(0, ci) else instr
    val comment = if (ci >= 0) instr.substring(ci) else ""
    return buildAnnotatedString {
        var first = true
        code.split(" ").forEachIndexed { ti, tk ->
            if (ti > 0) append(" ")
            if (tk.isEmpty()) return@forEachIndexed
            val isAlpha = tk.matches(Regex("[a-z][a-z0-9.]*"))
            val color = when {
                tk.startsWith("#") || tk.matches(Regex("0x[0-9a-fA-F]+")) -> cs.secondary
                tk.matches(Regex("(x|w|q|d|s|v)[0-9]{1,2}|sp|lr|pc|wzr|xzr|fp|rax|rbx|rcx|rdx|rsi|rdi")) -> cs.tertiary
                first && isAlpha -> cs.primary
                else -> cs.onSurface
            }
            withStyle(
                SpanStyle(
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = AppText.label,
                    fontWeight = if (first && isAlpha) FontWeight.SemiBold else FontWeight.Normal,
                ),
            ) { append(tk) }
            if (isAlpha) first = false
        }
        if (comment.isNotEmpty()) {
            withStyle(SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace, fontSize = AppText.label)) { append(comment) }
        }
    }
}

/** 反汇编：三列对齐（地址可点复制 | 机器码 | 指令与操作数），整体可横滚不换行。 */
@Composable
private fun DisasmView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onGoFunctions: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val ws = tools.sharedWorkspaceId
    val target = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
    val key = "disasm|$ws|$target"

    LaunchedEffect(key, tools.reloadTick) {
        if (tools.disasmKey != key || tools.disasmJson.isBlank()) {
            fetchDisasm(context, tools, zh, ws, target, key, 120)
        }
    }

    val body = tools.disasmJson
    val err = errMessageOf(body)
    val obj = remember(body) { runCatching { JSONObject(body) }.getOrNull() }
    val text = remember(body) { obj?.optJSONObject("textWindow")?.optString("text").orEmpty() }
    val lines = remember(text) { if (text.isBlank()) emptyList() else text.split("\n").filter { it.isNotBlank() } }
    val addr = obj?.optString("addr").orEmpty()
    val count = obj?.optInt("instructionCount", lines.size) ?: lines.size
    val parsed = remember(lines) { lines.map { splitDisasmLine(it) } }
    val hs = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(
                label = if (zh) "更多指令" else "More",
                enabled = ws.isNotBlank() && target.isNotBlank(),
                loading = tools.viewLoading == key,
                onClick = { scope.launch { fetchDisasm(context, tools, zh, ws, target, key, 400) } },
            )
            SmallAction(if (zh) "重新加载" else "Reload", onClick = onRefresh)
            if (addr.isNotBlank()) {
                Text(
                    "$addr · $count",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        if (ws.isBlank() || target.isBlank()) {
            AnalysisEmptyState(
                title = if (zh) "请先在函数列表里选择一个函数" else "Pick a function first",
                hint = if (zh) "反汇编以当前选中函数为目标" else "Disassembly follows the selected function",
                primaryLabel = if (zh) "去函数列表" else "Function list",
                onPrimary = onGoFunctions,
            )
        } else if (tools.viewLoading == key && lines.isEmpty()) {
            AnalysisLoading()
        } else if (err.isNotBlank()) {
            AnalysisErrorBanner(err)
        } else if (lines.isEmpty()) {
            AnalysisEmptyState(
                title = if (zh) "无指令输出" else "No instructions",
                hint = if (zh) "该地址可能不是可执行代码，换个函数或点「重新加载」" else "Address may not be executable code",
                primaryLabel = if (zh) "函数列表" else "Function list",
                onPrimary = onGoFunctions,
            )
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(cs.surfaceContainerHigh)
                    .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
            ) {
                // 表头
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(hs).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (zh) "地址" else "ADDR", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant, modifier = Modifier.width(96.dp))
                    Text(if (zh) "机器码" else "BYTES", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant, modifier = Modifier.width(150.dp))
                    Text(if (zh) "指令" else "INSTRUCTION", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
                }
                GroupDivider()
                // 横滚 + 竖滚：Column 用 IntrinsicSize.Max 让所有行同宽，列起点据此对齐。
                val vs = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(hs)) {
                    Column(
                        Modifier.fillMaxHeight().verticalScroll(vs).width(IntrinsicSize.Max)
                            .padding(bottom = 10.dp),
                    ) {
                        parsed.forEach { (a, bytes, instr) ->
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    a,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.width(96.dp).clickable { if (a.isNotBlank()) copyToClipboard(context, a, zh) },
                                )
                                Text(
                                    bytes.ifBlank { " " },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                    color = cs.primary.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.width(150.dp),
                                )
                                Text(disasmInstrAnnotated(instr, cs), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 伪 C ─────────────────────────

/** C 风格伪代码的轻量高亮（关键字 / 类型 / 字符串 / 数字 / 注释 / 函数调用）。 */
private val cKeywords = setOf(
    "if", "else", "while", "for", "do", "return", "switch", "case", "default", "break",
    "continue", "goto", "sizeof", "static", "const", "extern", "struct", "union", "enum",
    "typedef", "register", "volatile", "inline", "null", "NULL", "true", "false",
)
private val cTypes = setOf(
    "void", "char", "short", "int", "long", "float", "double", "unsigned", "signed", "bool",
    "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t", "int32_t", "int64_t",
    "size_t", "ssize_t", "byte", "word", "qword",
)

private fun highlightPseudo(line: String, cs: androidx.compose.material3.ColorScheme): AnnotatedString {
    val ci = line.indexOf("//")
    val code = if (ci >= 0) line.substring(0, ci) else line
    val comment = if (ci >= 0) line.substring(ci) else ""
    val mono = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = AppText.label)
    return buildAnnotatedString {
        val re = Regex("(\"(?:\\\\.|[^\"\\\\])*\")|('(?:\\\\.|[^'\\\\])*')|(0x[0-9a-fA-F]+)|(\\b\\d+\\b)|([A-Za-z_][A-Za-z0-9_]*)")
        var last = 0
        for (m in re.findAll(code)) {
            if (m.range.first > last) {
                withStyle(mono.copy(color = cs.onSurface)) { append(code.substring(last, m.range.first)) }
            }
            val tok = m.value
            val color = when {
                tok.startsWith("\"") || tok.startsWith("'") -> cs.secondary
                tok.startsWith("0x") -> cs.secondary
                tok[0].isDigit() -> cs.secondary
                tok in cKeywords -> cs.primary
                tok in cTypes -> cs.tertiary
                else -> {
                    val after = code.substring(m.range.last + 1)
                    if (after.trimStart().startsWith("(")) cs.primary else cs.onSurface
                }
            }
            val weight = if (tok in cKeywords) FontWeight.SemiBold else FontWeight.Normal
            withStyle(mono.copy(color = color, fontWeight = weight)) { append(tok) }
            last = m.range.last + 1
        }
        if (last < code.length) {
            withStyle(mono.copy(color = cs.onSurface)) { append(code.substring(last)) }
        }
        if (comment.isNotEmpty()) {
            withStyle(mono.copy(color = cs.onSurfaceVariant.copy(alpha = 0.6f))) { append(comment) }
        }
    }
}

/** 伪 C 代码视图：行号槽 + 轻量高亮；顶部显示签名/范围/覆盖率等元信息。 */
@Composable
private fun PseudoView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onGoFunctions: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ws = tools.sharedWorkspaceId
    val target = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
    var engineMode by remember { mutableStateOf("auto") }
    val key = "pseudo|$ws|$target|$engineMode"

    LaunchedEffect(key, tools.reloadTick) {
        fetchPseudo(context, tools, zh, ws, target, key, engineMode)
    }

    val body = tools.pseudoJson
    val err = errMessageOf(body)
    val obj = remember(body) { runCatching { JSONObject(body) }.getOrNull() }
    val pseudo = remember(body) { obj?.optString("pseudocode").orEmpty() }
    val bounds = remember(body) { obj?.optJSONObject("functionBounds") }
    val coverage = remember(body) { obj?.optJSONObject("pseudocodeCoverage") }
    val typeInf = remember(body) { obj?.optJSONObject("typeInference") }
    val warn = obj?.optString("boundaryWarning").orEmpty()
    val usedEngine = obj?.optString("engine").orEmpty()

    val allLines = remember(pseudo) { if (pseudo.isBlank()) emptyList() else pseudo.split("\n") }
    val truncated = allLines.size > 3000
    val lines = remember(allLines) { if (truncated) allLines.take(3000) else allLines }
    val hs = rememberScrollState()
    val vs = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(
                label = if (zh) "重新生成" else "Re-run",
                enabled = ws.isNotBlank() && target.isNotBlank(),
                loading = tools.viewLoading == key,
                onClick = onRefresh,
            )
            SmallAction(if (zh) "函数列表" else "Functions", onClick = onGoFunctions)
            listOf("auto" to "Auto", "ghidra" to "Ghidra", "native" to "Native", "java" to "Java").forEach { (k, l) ->
                SmallAction(l, active = engineMode == k) { engineMode = k }
            }
            SmallAction(
                label = if (zh) "复制全部" else "Copy",
                enabled = pseudo.isNotBlank(),
                onClick = { copyToClipboard(context, pseudo, zh) },
            )
            if (usedEngine.isNotBlank()) TypeBadge(usedEngine, cs.primary)
        }
        Spacer(Modifier.size(6.dp))
        if (ws.isBlank() || target.isBlank()) {
            AnalysisEmptyState(
                title = if (zh) "请先在函数列表里选择一个函数" else "Pick a function first",
                hint = if (zh) "伪代码以当前选中函数为目标" else "Pseudocode follows the selected function",
                primaryLabel = if (zh) "去函数列表" else "Function list",
                onPrimary = onGoFunctions,
            )
        } else if (tools.viewLoading == key && pseudo.isBlank()) {
            AnalysisLoading()
        } else if (err.isNotBlank()) {
            AnalysisErrorBanner(err)
        } else if (pseudo.isBlank()) {
            AnalysisEmptyState(
                title = if (zh) "无伪代码输出" else "No pseudocode",
                hint = if (zh) "该地址可能不是函数入口，换个函数或点「重新生成」" else "Address may not be a function entry",
                primaryLabel = if (zh) "函数列表" else "Function list",
                onPrimary = onGoFunctions,
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 元信息：范围 / 声明 / 越界 / 类型推断 —— 以 chip 形式固定在上方（不随代码滚动）
                val meta = ArrayList<String>(4)
                if (bounds != null) {
                    val s = bounds.optString("startAddr")
                    val e = bounds.optString("endAddr")
                    val sz = bounds.optLong("size", -1L)
                    if (s.isNotBlank()) meta.add("${if (zh) "范围" else "range"} $s-$e")
                    if (sz >= 0L) meta.add("$sz B")
                }
                if (coverage != null) {
                    val d0 = coverage.optString("declaredStart")
                    val d1 = coverage.optString("declaredEnd")
                    val oob = coverage.optJSONArray("outOfBoundsAddrs")?.length() ?: 0
                    if (d0.isNotBlank()) meta.add("${if (zh) "声明" else "declared"} $d0-$d1")
                    if (oob > 0) meta.add(if (zh) "越界地址 $oob" else "oob $oob")
                }
                if (typeInf != null) {
                    val keys = typeInf.keys()
                    val parts = ArrayList<String>()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        parts.add("$k=${typeInf.opt(k)}")
                    }
                    if (parts.isNotEmpty()) meta.add(parts.joinToString(" · "))
                }
                if (meta.isNotEmpty() || lines.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        meta.forEach { m ->
                            Text(
                                m,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = AppText.label,
                                fontFamily = FontFamily.Monospace,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppShape.sm))
                                    .background(cs.surfaceContainerHigh)
                                    .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.sm))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (warn.isNotBlank()) AnalysisErrorBanner(warn)

                Box(
                    Modifier.weight(1f).fillMaxWidth().horizontalScroll(hs)
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHigh)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
                ) {
                    Column(
                        Modifier.fillMaxHeight().verticalScroll(vs).width(IntrinsicSize.Max).padding(vertical = 8.dp),
                    ) {
                        lines.forEachIndexed { idx, line ->
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                Text(
                                    "${idx + 1}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                    color = cs.onSurfaceVariant.copy(alpha = 0.5f),
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.width(40.dp).padding(end = 8.dp),
                                )
                                Text(highlightPseudo(line, cs), maxLines = 1, softWrap = false)
                            }
                        }
                        if (truncated) {
                            Text(
                                if (zh) "… 已截断，仅显示前 3000 行（完整内容点「复制全部」）"
                                else "… truncated to first 3000 lines (use Copy for full text)",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = AppText.label,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── CFG ─────────────────────────

@Composable
private fun CfgView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onGoFunctions: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val ws = tools.sharedWorkspaceId
    val target = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
    val err = errMessageOf(tools.cfgJson)
    val hasGraph = tools.cfgJson.isNotBlank() && err.isBlank()
    val fnLabel = tools.selectedFunctionName.ifBlank { tools.cfgTarget }

    // 兜底：一键定位入口点（ELF 入口 → 首个函数 → 列表第一项）
    fun useEntryPoint() {
        scope.launch {
            tools.cfgLoading = true
            val p = resolveCfgEntry(context, tools)
            tools.cfgLoading = false
            if (p != null) {
                tools.selectedFunctionName = p.first
                tools.selectedFunctionVa = p.second
                tools.decompileTarget = p.first
                tools.disasmAddr = p.second.ifBlank { p.first }
                loadCfg(context, tools, zh, scope, p.second.ifBlank { p.first })
            } else {
                tools.cfgJson = errJson(
                    if (zh) "无法自动定位入口点：未找到任何函数" else "Could not auto-locate an entry point",
                )
            }
        }
    }

    // 蓝图页：控制流图独占整页，操作条悬浮其上（不占布局高度）。
    // 刻意不提供任何「文本 / 原始 JSON」出口——CFG 只以图形呈现；
    // 出错与为空时都用页内覆盖态说明，不跳到别处。
    Box(Modifier.fillMaxSize()) {
        when {
            ws.isBlank() -> AnalysisEmptyState(
                title = if (zh) "未打开工作区" else "No workspace",
                hint = if (zh) "先用顶部「选文件」打开一个 SO / APK" else "Open a SO / APK first",
            )
            target.isBlank() -> AnalysisEmptyState(
                title = if (zh) "请先在函数列表里选择一个函数" else "Pick a function first",
                hint = if (zh) "控制流图以选中函数的入口地址为目标；也可以直接点「入口点」自动定位"
                    else "CFG targets the selected function entry; or tap Entry",
                primaryLabel = if (zh) "去函数列表" else "Function list",
                onPrimary = onGoFunctions,
                secondaryLabel = if (zh) "入口点" else "Entry",
                onSecondary = { useEntryPoint() },
            )
            tools.cfgLoading -> AnalysisLoading()
            tools.cfgJson.isBlank() -> AnalysisEmptyState(
                title = if (zh) "尚未生成控制流图" else "No CFG yet",
                hint = if (zh) "点「重新生成」为当前函数生成控制流图" else "Tap Rebuild to generate a CFG for the current function",
                primaryLabel = if (zh) "重新生成" else "Rebuild",
                onPrimary = { loadCfg(context, tools, zh, scope, target) },
            )
            !hasGraph -> AnalysisEmptyState(
                title = if (zh) "无法生成控制流图" else "CFG unavailable",
                hint = err.ifBlank { if (zh) "引擎未返回可用的基本块数据" else "No usable basic blocks were returned" },
                primaryLabel = if (zh) "入口点" else "Entry",
                onPrimary = { useEntryPoint() },
                secondaryLabel = if (zh) "重试" else "Retry",
                onSecondary = { loadCfg(context, tools, zh, scope, target) },
            )
            else -> CfgCanvas(tools.cfgJson, zh, Modifier.fillMaxSize())
        }

        if (hasGraph) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, cs.outlineVariant),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = if (fnLabel.isBlank()) (if (zh) "控制流图" else "Control Flow Graph") else fnLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontSize = AppText.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        SmallAction(
                            label = if (zh) "重新生成" else "Rebuild",
                            enabled = ws.isNotBlank() && target.isNotBlank(),
                            loading = tools.cfgLoading,
                            onClick = { loadCfg(context, tools, zh, scope, target) },
                        )
                        SmallAction(
                            label = if (zh) "入口点" else "Entry",
                            enabled = ws.isNotBlank(),
                            loading = tools.cfgLoading,
                            onClick = { useEntryPoint() },
                        )
                        SmallAction(if (zh) "换函数" else "Functions", onClick = onGoFunctions)
                    }
                }
            }
        }
    }
}

// ───────────────────────── 列表视图（字符串 / 符号 / 导入 / 段节） ─────────────────────────

/** 列表视图共用的小表头（各视图自定义列）. */
@Composable
private fun ListCols(vararg cols: Pair<String, androidx.compose.ui.unit.Dp?>) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEach { (label, w) ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                modifier = if (w == null) Modifier.weight(1f) else Modifier.width(w),
            )
        }
    }
}

/** 列表视图共用的外壳：表头 + 分割线 + 行内容（内部横滚不换行）。 */
@Composable
private fun ListShell(
    headers: List<Pair<String, androidx.compose.ui.unit.Dp?>>,
    rows: kotlin.collections.List<AnalysisRow>,
    expandedKey: String?,
    onPick: (AnalysisRow) -> Unit,
    cell: @Composable (AnalysisRow) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
    ) {
        ListCols(*headers.toTypedArray())
        GroupDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(rows, key = { r -> r.key }) { row ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (row.key == expandedKey) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onPick(row) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    cell(row)
                }
                GroupDivider()
            }
        }
    }
}

/** 列表视图通用外壳（取数 + 状态分支）。 */
@Composable
private fun ListScaffold(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    view: String,
    limit: Int,
    onRefresh: () -> Unit,
    headers: List<Pair<String, androidx.compose.ui.unit.Dp?>>,
    rowsFromJson: (String?) -> kotlin.collections.List<AnalysisRow>,
    onPick: (AnalysisRow) -> Unit,
    cell: @Composable (AnalysisRow) -> Unit,
) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val cacheKey = "$view|$ws|"

    LaunchedEffect(cacheKey, tools.reloadTick) {
        loadListCache(context, tools, view, ws, cacheKey, limit)
    }
    val rows = remember(tools.viewCache[cacheKey]) { rowsFromJson(tools.viewCache[cacheKey]) }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
            SmallAction(if (zh) "函数列表" else "Functions") { tools.analysisView = "functions" }
            Text(
                if (zh) "${rows.size} 项" else "${rows.size} items",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ws.isBlank() -> AnalysisEmptyState(
                    title = if (zh) "未打开工作区" else "No workspace",
                    hint = if (zh) "先用顶部「选文件」打开一个 SO / APK" else "Open a SO / APK from the top bar first",
                )
                tools.viewLoading == cacheKey && rows.isEmpty() -> AnalysisLoading()
                rows.isEmpty() && !tools.viewCache.containsKey(cacheKey) -> AnalysisErrorBanner(
                    errMessageOf(tools.viewCache[cacheKey]).ifBlank {
                        if (zh) "加载失败，点「刷新」重试" else "Load failed, tap Refresh"
                    },
                )
                rows.isEmpty() -> AnalysisEmptyState(
                    title = if (zh) "暂无数据" else "No data",
                    hint = if (zh) "该文件可能不含这一类内容" else "This file may not contain this kind of data",
                    primaryLabel = if (zh) "刷新" else "Refresh",
                    onPrimary = onRefresh,
                )
                else -> ListShell(
                    headers = headers,
                    rows = rows,
                    expandedKey = null,
                    onPick = onPick,
                    cell = cell,
                )
            }
        }
    }
}

/** 字符串：地址 | 长度 | 内容（点行复制内容）。 */
@Composable
private fun StringsView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onPick: (AnalysisRow) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListScaffold(
        tools = tools, zh = zh, context = context, view = "strings", limit = 400,
        onRefresh = onRefresh,
        headers = listOf(
            (if (zh) "地址" else "ADDR") to 86.dp,
            (if (zh) "长度" else "LEN") to 46.dp,
            (if (zh) "内容" else "VALUE") to null,
        ),
        rowsFromJson = { json ->
            val arr = itemsArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val off = it.optString("offset").ifBlank { it.optString("addr") }
                val len = it.optLong("length", -1L)
                val enc = it.optString("encoding").ifBlank { it.optString("section") }
                val value = it.optString("value")
                AnalysisRow(
                    key = "s$i|$off",
                    title = value,
                    meta = listOf("${if (len >= 0) len else "-"}", enc).filter { t -> t.isNotBlank() }.joinToString(" · "),
                    va = off,
                    text = value,
                )
            }
        },
        onPick = onPick,
    ) { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.va.ifBlank { "--" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(86.dp),
            )
            Text(
                row.meta.substringBefore(" · ").ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(46.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val enc = row.meta.substringAfter(" · ", "")
                if (enc.isNotBlank()) {
                    Text(enc, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** 符号：地址 | 类型 | 绑定 | 名称（demangled 优先）。 */
@Composable
private fun SymbolsView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onPick: (AnalysisRow) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListScaffold(
        tools = tools, zh = zh, context = context, view = "symbols", limit = 400,
        onRefresh = onRefresh,
        headers = listOf(
            (if (zh) "地址" else "ADDR") to 86.dp,
            (if (zh) "类型" else "TYPE") to 58.dp,
            (if (zh) "名称" else "NAME") to null,
        ),
        rowsFromJson = { json ->
            val arr = itemsArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val va = it.optString("value").ifBlank { it.optString("addr") }.ifBlank { it.optString("startAddr") }
                val name = it.optString("demangled").ifBlank { it.optString("name") }.ifBlank { it.optString("symbol") }
                val type = it.optString("type")
                val bind = it.optString("bind").ifBlank { it.optString("visibility") }
                val size = it.optLong("size", -1L)
                AnalysisRow(
                    key = "y$i|$va|$name",
                    title = name,
                    meta = listOf(type, bind, if (size >= 0) "$size B" else "").filter { t -> t.isNotBlank() }.joinToString(" · "),
                    va = va,
                    text = name,
                )
            }
        },
        onPick = onPick,
    ) { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.va.ifBlank { "--" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(86.dp),
            )
            Text(
                row.meta.substringBefore(" · ").ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.tertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(58.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val rest = row.meta.substringAfter(" · ", "")
                if (rest.isNotBlank()) {
                    Text(rest, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 导入：名称 | 类型 | 绑定 | 库/节。 */
@Composable
private fun ImportsView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onPick: (AnalysisRow) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListScaffold(
        tools = tools, zh = zh, context = context, view = "imports", limit = 400,
        onRefresh = onRefresh,
        headers = listOf(
            (if (zh) "类型" else "TYPE") to 58.dp,
            (if (zh) "名称" else "NAME") to null,
        ),
        rowsFromJson = { json ->
            val arr = itemsArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = it.optString("symbol").ifBlank { it.optString("name") }
                val type = it.optString("type")
                val bind = it.optString("bind")
                val lib = it.optString("library").ifBlank { it.optString("section") }
                AnalysisRow(
                    key = "m$i|$name",
                    title = name,
                    meta = listOf(type, bind, lib).filter { t -> t.isNotBlank() }.joinToString(" · "),
                    va = it.optString("value").ifBlank { it.optString("addr") },
                    text = name,
                )
            }
        },
        onPick = onPick,
    ) { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.meta.substringBefore(" · ").ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.tertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(58.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val rest = row.meta.substringAfter(" · ", "")
                if (rest.isNotBlank()) {
                    Text(rest, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 段节：名称 | 地址 | 大小 | 权限徽标。 */
@Composable
private fun SectionsView(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit,
    onPick: (AnalysisRow) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListScaffold(
        tools = tools, zh = zh, context = context, view = "sections", limit = 400,
        onRefresh = onRefresh,
        headers = listOf(
            (if (zh) "名称" else "NAME") to 120.dp,
            (if (zh) "地址" else "ADDR") to 86.dp,
            (if (zh) "大小" else "SIZE") to 62.dp,
            (if (zh) "权限" else "PERM") to 56.dp,
        ),
        rowsFromJson = { json ->
            val arr = itemsArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = it.optString("name")
                val addr = it.optString("addr").ifBlank { it.optString("virtualAddr") }
                val size = it.optLong("size", -1L)
                val flags = it.optString("flags").ifBlank { it.optString("perm") }
                AnalysisRow(
                    key = "c$i|$name",
                    title = name,
                    meta = listOf(if (size >= 0) "$size" else "-", flags).joinToString(" · "),
                    va = addr,
                    text = name,
                )
            }
        },
        onPick = onPick,
    ) { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(120.dp),
            )
            Text(
                row.va.ifBlank { "--" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(86.dp),
            )
            Text(
                row.meta.substringBefore(" · ").ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier.width(62.dp),
            )
            val perm = row.meta.substringAfter(" · ", "")
            Row(Modifier.width(56.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                permChars(perm).forEach { (ch, on) ->
                    Text(
                        ch,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = AppText.label,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

private fun permChars(perm: String): List<Pair<String, Boolean>> {
    val p = perm.uppercase()
    return listOf(
        "R" to (p.contains("R")),
        "W" to (p.contains("W")),
        "X" to (p.contains("X")),
    )
}

/** 从列表 JSON 里取数组（items 优先，functions 兼容）。 */
private fun itemsArray(json: String?): JSONArray {
    if (json.isNullOrBlank()) return JSONArray()
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return JSONArray()
    return o.optJSONArray("items") ?: o.optJSONArray("functions") ?: JSONArray()
}

// ───────────────────────── 结果 / 工具 ─────────────────────────

/** 结果视图：保留 addTab / ResultStream 机制，补回标签切换与关闭。 */
@Composable
private fun ResultsPane(tools: ToolPagesState, zh: Boolean) {
    Column(Modifier.fillMaxSize()) {
        if (tools.resultTabs.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tools.resultTabs.forEachIndexed { idx, tb ->
                    WbTab(
                        label = tb.label,
                        selected = tools.selectedTabIndex == idx,
                        onClose = { tools.closeTab(idx) },
                    ) { tools.selectedTabIndex = idx }
                }
                WbTab(if (zh) "清空" else "Clear", false) { tools.clearTabs() }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ResultStream(tools, zh)
        }
    }
}

/** 工具视图：保留工具控制台（ToolConsole）与工具选择弹层。 */
@Composable
private fun ToolsPane(
    state: WorkspaceState,
    tools: ToolPagesState,
    zh: Boolean,
    onAiAnalyze: (String) -> Unit,
    onPickTool: () -> Unit,
    onShowCfg: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val curDef = toolDefs.firstOrNull { it.key == state.activeTool }
    val title = curDef?.let { if (zh) it.labelZh else it.labelEn } ?: ""

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(
                label = if (state.activeTool.isBlank()) {
                    if (zh) "选择工具" else "Pick tool"
                } else {
                    (if (zh) "换工具 · " else "Switch · ") + title
                },
                onClick = onPickTool,
            )
            if (state.activeTool.isNotBlank()) {
                SmallAction(if (zh) "结果" else "Results") { tools.analysisView = "results" }
                SmallAction(if (zh) "清空结果" else "Clear") { tools.clearTabs() }
            }
        }
        Spacer(Modifier.size(6.dp))

        if (state.activeTool.isBlank()) {
            AnalysisEmptyState(
                title = if (zh) "未选择工具" else "No tool selected",
                hint = if (zh) "从工具列表里挑一个：反编译 / 脱壳 / SO 分析 / 模拟 / Frida / 回编 / 编辑" else "Pick one: decompile / unpack / SO / emulate / frida / rebuild / editor",
                primaryLabel = if (zh) "选择工具" else "Pick tool",
                onPrimary = onPickTool,
            )
        } else {
            // 需要地址的工具：地址栏（与原实现一致）
            if (state.activeTool in addrNeededTools) {
                AddrBar(state, zh)
                Spacer(Modifier.size(6.dp))
            }
            AppCard(Modifier.fillMaxWidth()) {
                ToolConsole(state, zh, onAiAnalyze, onShowCfg)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                if (zh) "工具输出会进入「结果」标签页" else "Tool output goes to the Results tabs",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// ───────────────────────── C++ 符号（demangle） ─────────────────────────

/** C++ 符号：输入 Itanium mangled 名 → 还原可读签名；也可扫描当前工作区批量还原。 */
@Composable
private fun DemangleView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun run() {
        val sym = input.trim()
        if (sym.isBlank()) return
        scope.launch {
            working = true
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val handler = com.soreverse.mcp.mcp.ToolCatalog.byName["taffy_so_demangle"]
                        ?: return@runCatching null
                    val tcx = com.soreverse.mcp.mcp.ToolContext(
                        context,
                        com.soreverse.mcp.core.SettingsStore(context),
                        EngineProvider.get(context),
                        null,
                    )
                    handler.handle(tcx, JSONObject().put("symbol", sym))
                }.getOrNull()
            }
            working = false
            if (res == null) {
                original = sym
                result = ""
                note = if (zh) "引擎未就绪（native 库未加载）" else "engine not ready (native lib not loaded)"
            } else {
                original = res.optString("mangled").ifBlank { sym }
                result = res.optString("demangled")
                note = res.optString("note")
                if (res.optBoolean("ok", false) && result == original) {
                    note = if (zh) "已是最可读形式（可能不是 Itanium 编码）" else "already readable (not Itanium?)"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Transform, null, modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant) },
            placeholder = {
                Text(
                    if (zh) "粘贴 mangled 符号，如 _ZN4Test3fooEi" else "paste a mangled symbol, e.g. _ZN4Test3fooEi",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        Spacer(Modifier.size(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(
                label = if (zh) "还原" else "Demangle",
                enabled = input.isNotBlank(),
                loading = working,
                onClick = { run() },
            )
            SmallAction(
                label = if (zh) "清空" else "Clear",
                enabled = input.isNotBlank() || result.isNotBlank(),
                onClick = { input = ""; result = ""; original = ""; note = "" },
            )
            SmallAction(
                label = if (zh) "复制结果" else "Copy",
                enabled = result.isNotBlank(),
                onClick = { copyToClipboard(context, result, zh) },
            )
        }
        Spacer(Modifier.size(8.dp))
        if (result.isBlank() && original.isBlank()) {
            AnalysisEmptyState(
                title = if (zh) "C++ 符号还原" else "C++ symbol demangle",
                hint = if (zh) "输入 Itanium ABI 的 mangled 名（通常以 _Z 开头），还原成可读的类名/函数名/参数表。"
                    else "Enter an Itanium ABI mangled name (usually starting with _Z) to get a readable signature.",
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultCard(if (zh) "Mangled（输入）" else "Mangled (input)") { original }
                if (result.isNotBlank()) ResultCard(if (zh) "Demangled（还原）" else "Demangled") { result }
                if (note.isNotBlank()) {
                    AnalysisErrorBanner(note)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(label: String, mono: Boolean = true, value: () -> String) {
    val cs = MaterialTheme.colorScheme
    val v = value()
    if (v.isBlank()) return
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
        Text(
            v,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body)
            else MaterialTheme.typography.bodyMedium.copy(fontSize = AppText.bodyStrong),
            color = cs.onSurface,
        )
    }
}

// ───────────────────────── 进制转换 ─────────────────────────

/** 把任意进制的输入解析成 BigInteger（去前缀/下划线/空格；自动识别 0x/0b/0o）。 */
private fun parseBaseValue(raw: String, base: Int): java.math.BigInteger? {
    var t = raw.trim().replace("_", "").replace(" ", "")
    if (t.isEmpty()) return null
    var neg = false
    if (t.startsWith("-")) { neg = true; t = t.substring(1) }
    var b = base
    if (b == 0) {
        val low = t.lowercase()
        b = when {
            low.startsWith("0x") -> { t = t.substring(2); 16 }
            low.startsWith("0b") -> { t = t.substring(2); 2 }
            low.startsWith("0o") -> { t = t.substring(2); 8 }
            else -> 10
        }
    } else {
        val low = t.lowercase()
        if (b == 16 && low.startsWith("0x")) t = t.substring(2)
        if (b == 2 && low.startsWith("0b")) t = t.substring(2)
        if (b == 8 && low.startsWith("0o")) t = t.substring(2)
    }
    val v = runCatching { java.math.BigInteger(t, b) }.getOrNull() ?: return null
    return if (neg) v.negate() else v
}

/** 进制转换：数值 ↔ 2/8/10/16 进制 + ASCII + 常见字节序。 */
@Composable
private fun BaseConvertView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(0) } // 0=自动
    val parsed = remember(input, base) { parseBaseValue(input, base) }

    val baseLabels = listOf(0 to "Auto", 16 to "HEX", 10 to "DEC", 8 to "OCT", 2 to "BIN")

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Calculate, null, modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant) },
            placeholder = {
                Text(
                    if (zh) "输入数值，如 0x1F / 255 / 0b1111" else "value, e.g. 0x1F / 255 / 0b1111",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        Spacer(Modifier.size(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            baseLabels.forEach { (b, label) ->
                SmallAction(label, active = base == b) { base = b }
            }
        }
        Spacer(Modifier.size(8.dp))
        if (parsed == null) {
            AnalysisEmptyState(
                title = if (zh) "进制转换" else "Base converter",
                hint = if (zh) "输入一个数值，选择来源进制（或自动识别 0x / 0o / 0b 前缀），即可得到 HEX / DEC / OCT / BIN 以及字节序表示。"
                    else "Enter a value and pick its base (auto-detects 0x / 0o / 0b) to get HEX / DEC / OCT / BIN and endianness views.",
            )
        } else {
            val v = parsed
            val hex = v.toString(16)
            val bits = (v.bitLength() + 7) / 8
            val bytes = ((bits + 7) / 8) * 8 / 8
            val beHex = padBytes(hex, bytes, bigEndian = true)
            val leHex = padBytes(hex, bytes, bigEndian = false)
            val ascii = hexToAscii(hex, bytes)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueCard(zh, listOf(
                    ("HEX" to "0x" + hex.uppercase()),
                    ("DEC" to v.toString(10)),
                    ("OCT" to "0o" + v.toString(8)),
                    ("BIN" to "0b" + v.toString(2)),
                ))
                KeyValueCard(zh, listOf(
                    (if (zh) "字节数" else "bytes") to "$bytes",
                    (if (zh) "大端" else "BE") to ("0x" + beHex.uppercase()),
                    (if (zh) "小端" else "LE") to ("0x" + leHex.uppercase()),
                    ("ASCII" to ascii),
                ))
                SmallAction(if (zh) "复制 HEX" else "Copy HEX") { copyToClipboard(context, "0x" + hex, zh) }
            }
        }
    }
}

/** 把 hex 串按字节数左侧补零（可选字节序翻转）。 */
private fun padBytes(hex: String, byteCount: Int, bigEndian: Boolean): String {
    var h = hex.lowercase()
    if (h.length % 2 == 1) h = "0$h"
    val padded = h.padStart(byteCount * 2, '0')
    if (bigEndian) return padded
    return padded.chunked(2).reversed().joinToString("")
}

/** hex（按字节）→ 可打印 ASCII，非可打印显示 '.'。 */
private fun hexToAscii(hex: String, byteCount: Int): String {
    var h = hex.lowercase()
    if (h.length % 2 == 1) h = "0$h"
    val padded = h.padStart(byteCount * 2, '0')
    val sb = StringBuilder()
    padded.chunked(2).forEach { b ->
        val code = runCatching { b.toInt(16) }.getOrDefault(0)
        sb.append(if (code in 32..126) code.toChar() else '.')
    }
    return sb.toString()
}

@Composable
private fun KeyValueCard(zh: Boolean, pairs: List<Pair<String, String>>) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pairs.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    k,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.width(64.dp),
                )
                Text(
                    v,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ───────────────────────── 汇编工作台（实时编码 + 写回编辑会话） ─────────────────────────

private val asmArchs = listOf("arm64" to "AArch64", "arm32" to "ARM", "x86" to "x86", "x86_64" to "x86_64")

/** 汇编工作台：本地实时编码预览（不依赖工作区）+ 把汇编写入工作区编辑会话（可预览/校验/导出）。 */
@Composable
private fun AsmEditorView(tools: ToolPagesState, zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val ws = tools.sharedWorkspaceId

    var asm by remember { mutableStateOf("") }
    var arch by remember { mutableStateOf("arm64") }
    var addrText by remember { mutableStateOf("0x1000") }

    // 本地编码预览（实时）
    var outHex by remember { mutableStateOf("") }
    var outSize by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    // 写回编辑会话
    var locator by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var patchMsg by remember { mutableStateOf("") }
    var patchErr by remember { mutableStateOf("") }
    var patching by remember { mutableStateOf(false) }

    val addr = remember(addrText) { hexVal(addrText.ifBlank { "0" }) }

    // 实时预览：文本/架构/地址变化 400ms 后自动编码
    LaunchedEffect(asm, arch, addr) {
        if (asm.isBlank()) { outHex = ""; outSize = -1; status = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        val src = asm.trim()
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val eng = com.soreverse.mcp.nativecore.NativeEngine.active()
                if (!eng.available()) return@runCatching null
                eng.assemble(src, arch, addr, false)
            }.getOrNull()
        }
        if (r == null) {
            outHex = ""; outSize = -1
            status = if (zh) "native 汇编引擎不可用" else "native assembler unavailable"
        } else if (r.isEmpty()) {
            outHex = ""; outSize = 0
            status = if (zh) "无法编码该指令（检查架构 / 语法）" else "could not encode (check arch / syntax)"
        } else {
            outHex = r.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
            outSize = r.size
            status = ""
        }
    }

    fun applyToSession(dryRun: Boolean) {
        if (ws.isBlank()) { patchErr = if (zh) "需先打开工作区才能写回" else "open a workspace first"; return }
        if (asm.isBlank() || locator.isBlank()) { patchErr = if (zh) "需要汇编内容与目标地址" else "need assembly + target address"; return }
        scope.launch {
            patching = true
            patchErr = ""; patchMsg = ""
            var sid = sessionId
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = EngineProvider.get(context)
                    if (sid.isBlank()) {
                        val eo = eng.editOpen(ws)
                        sid = eo.optString("editSessionId")
                            .ifBlank { eo.optJSONObject("data")?.optString("editSessionId").orEmpty() }
                        if (sid.isNotBlank()) sessionId = sid
                    }
                    if (sid.isBlank()) return@runCatching JSONObject()
                        .put("error", JSONObject().put("message", if (zh) "无法创建编辑会话" else "could not open edit session"))
                    val edit = JSONObject()
                        .put("writeAsm", asm.trim())
                        .put("mode", "replace_instructions")
                        .put("instructionIndex", 0)
                    eng.editAsm(ws, sid, locator.trim(), JSONArray().put(edit), dryRun)
                }.getOrElse { JSONObject().put("error", JSONObject().put("message", it.message ?: "failed")) }
            }
            patching = false
            val err = errMessageOf(r.toString())
            if (err.isNotBlank()) {
                patchErr = err
            } else {
                val cnt = r.optJSONArray("previews")?.length()
                    ?: r.optJSONArray("patches")?.length()
                    ?: r.optInt("patchCount", -1)
                val applied = r.optBoolean("dryRun", dryRun) == false
                patchMsg = buildString {
                    append(if (dryRun) (if (zh) "预览通过" else "preview ok") else (if (zh) "已写入编辑会话" else "written to edit session"))
                    if (cnt >= 0) append(" · $cnt ${if (zh) "处改动" else "edits"}")
                    if (sid.isNotBlank()) append("\nsession: $sid")
                }
                tools.clearViewCaches()
                tools.reloadTick = tools.reloadTick + 1
                if (!dryRun && applied) Unit
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            asmArchs.forEach { (key, label) -> SmallAction(label, active = arch == key) { arch = key } }
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = asm,
                    onValueChange = { asm = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    label = { Text(if (zh) "汇编指令（分号或换行分隔）" else "assembly (';' or newline)", fontSize = AppText.label) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
                    shape = RoundedCornerShape(AppShape.sm),
                    placeholder = {
                        Text(
                            if (zh) "mov x0, x1" else "mov x0, x1",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                            color = cs.onSurfaceVariant,
                        )
                    },
                )
                OutlinedTextField(
                    value = addrText,
                    onValueChange = { addrText = it },
                    singleLine = true,
                    label = { Text(if (zh) "编码地址" else "encode addr", fontSize = AppText.label) },
                    modifier = Modifier.width(160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    shape = RoundedCornerShape(AppShape.sm),
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        // 实时编码结果
        if (outHex.isNotBlank()) {
            ResultCard(if (zh) "机器码（实时）" else "Machine code (live)") { "$outHex" }
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (outSize >= 0) "$outSize ${if (zh) "字节" else "bytes"}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                )
                SmallAction(if (zh) "复制机器码" else "Copy bytes") { copyToClipboard(context, outHex, zh) }
            }
        } else if (status.isNotBlank()) {
            AnalysisErrorBanner(status)
        } else if (asm.isBlank()) {
            AnalysisEmptyState(
                title = if (zh) "汇编工作台" else "Assembly workshop",
                hint = if (zh) "输入汇编即实时看到机器码；打开工作区后还能把改动写回（先预览、再写入编辑会话）。"
                    else "Type assembly to see machine code live; with a workspace open you can also write the change back (preview first).",
            )
        }

        Spacer(Modifier.size(10.dp))
        GroupDivider()
        Spacer(Modifier.size(10.dp))

        // 写回编辑会话
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (zh) "写回工作区" else "Write back to workspace",
                style = MaterialTheme.typography.labelMedium,
                fontSize = AppText.label,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
            )
            OutlinedTextField(
                value = locator,
                onValueChange = { locator = it },
                singleLine = true,
                enabled = ws.isNotBlank(),
                label = { Text(if (zh) "目标函数/地址（如 sub_1234 或 0x1234）" else "target function / address", fontSize = AppText.label) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                shape = RoundedCornerShape(AppShape.sm),
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallAction(
                    label = if (zh) "预览改动" else "Preview",
                    enabled = ws.isNotBlank() && asm.isNotBlank() && locator.isNotBlank(),
                    loading = patching,
                    onClick = { applyToSession(true) },
                )
                SmallAction(
                    label = if (zh) "写入编辑会话" else "Apply",
                    enabled = ws.isNotBlank() && asm.isNotBlank() && locator.isNotBlank(),
                    loading = patching,
                    onClick = { applyToSession(false) },
                )
                if (sessionId.isNotBlank()) {
                    Text(
                        "session ${sessionId.take(12)}…",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = AppText.label,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            if (ws.isBlank()) {
                Text(
                    if (zh) "未打开工作区：编码预览仍可用，写回不可用。" else "No workspace: encode preview works, write-back disabled.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                )
            }
            if (patchErr.isNotBlank()) AnalysisErrorBanner(patchErr)
            if (patchMsg.isNotBlank()) {
                Text(
                    patchMsg,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                    color = cs.primary,
                )
            }
        }
    }
}

// ───────────────────────── rizin 结构视图（ELF 头 / 段 / 重定位 / 动态 / 依赖库 / 哈希 / 版本 / 入口） ─────────────────────────
//
// 这些视图统一由 rizin 的只读 JSON 命令驱动（rzCommand），不依赖 native 侧 list(view) 扩展：
//   ihj  → 二进制字段(ELF 头)      iSSj → 段(programs)     irj  → 重定位
//   iHj  → 结构化数据(动态)        ilj  → 依赖库           iTj  → 文件哈希
//   iVj  → 版本信息                iej  → 入口点

/** rizin 表格列定义（width=null 表示弹性列）。 */
private data class RzCol(
    val key: String,
    val zh: String,
    val en: String,
    val width: androidx.compose.ui.unit.Dp?,
    val end: Boolean = false,
)

/** 跑一条 rizin 只读命令并解析成 JSON（返回 值 to 错误）。 */
private suspend fun rzFetch(
    context: android.content.Context,
    ws: String,
    cmd: String,
): Pair<Any?, String> = withContext(Dispatchers.IO) {
    runCatching {
        val r = EngineProvider.get(context).rzCommand(ws, "", cmd)
        val err = errMessageOf(r.toString())
        if (err.isNotBlank()) return@runCatching (null as Any?) to err
        val out = r.optString("stdout").ifBlank { r.optString("text") }.trim()
        if (out.isBlank()) return@runCatching (null as Any?) to ""
        val parsed: Any = runCatching {
            if (out.startsWith("[")) JSONArray(out) as Any else JSONObject(out) as Any
        }.getOrElse { return@runCatching (null as Any?) to "输出不是 JSON：${out.take(120)}" }
        parsed to ""
    }.getOrElse { (null as Any?) to (it.message ?: "command failed") }
}

/** JSON 值 → 展示字符串（数组/对象做紧凑处理）。 */
private fun jsonScalar(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is String -> v
    is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    is JSONArray -> (0 until v.length()).joinToString(", ") { jsonScalar(v.opt(it)) }
    else -> v.toString()
}

/** 从行对象取列值（支持 "a.b" 点路径）。 */
private fun jsonField(o: JSONObject, key: String): String {
    if (!key.contains('.')) return jsonScalar(o.opt(key))
    var cur: Any? = o
    for (part in key.split('.')) {
        cur = (cur as? JSONObject)?.opt(part) ?: return ""
    }
    return jsonScalar(cur)
}

/** 通用 rizin 视图外壳：取数 + 状态分支 + 表格 / 键值 / 列表。 */
@Composable
private fun RzViewScaffold(
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
    view: String,
    cmd: String,
    cols: List<RzCol>,
    onRefresh: () -> Unit,
) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val tick = tools.reloadTick
    var data by remember(view, ws, tick) { androidx.compose.runtime.mutableStateOf<Any?>(null) }
    var error by remember(view, ws, tick) { androidx.compose.runtime.mutableStateOf("") }
    var loading by remember(view, ws, tick) { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(view, ws, tick) {
        if (ws.isBlank()) return@LaunchedEffect
        loading = true
        val (d, e) = rzFetch(context, ws, cmd)
        data = d
        error = e
        loading = false
    }

    val rows: List<JSONObject> = remember(data) {
        when (val d = data) {
            is JSONArray -> (0 until d.length()).mapNotNull { d.optJSONObject(it) }
            else -> emptyList()
        }
    }
    val strList: List<String> = remember(data) {
        when (val d = data) {
            is JSONArray -> (0 until d.length()).mapNotNull { i ->
                when (val v = d.opt(i)) {
                    is String -> v
                    is JSONObject -> jsonScalar(v.opt("name")).ifBlank { v.toString() }
                    else -> jsonScalar(v).ifBlank { null }
                }
            }
            else -> emptyList()
        }
    }
    val kv: JSONObject? = remember(data) { data as? JSONObject }
    val count = if (rows.isNotEmpty()) rows.size else strList.size

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
            SmallAction(if (zh) "函数列表" else "Functions") { tools.analysisView = "functions" }
            if (count > 0) {
                Text(
                    if (zh) "$count 项" else "$count items",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(
                cmd,
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ws.isBlank() -> AnalysisEmptyState(
                    title = if (zh) "未打开工作区" else "No workspace",
                    hint = if (zh) "先用顶部「选文件」打开一个 SO / APK" else "Open a SO / APK from the top bar first",
                )
                loading -> AnalysisLoading()
                error.isNotBlank() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalysisErrorBanner(error)
                    Text(
                        if (zh) "点「刷新」重试" else "Tap Refresh to retry",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = AppText.body,
                        color = cs.onSurfaceVariant,
                    )
                }
                data == null -> AnalysisEmptyState(
                    title = if (zh) "无数据" else "No data",
                    hint = if (zh) "引擎未返回内容，点「刷新」重试" else "Engine returned nothing; tap Refresh",
                    primaryLabel = if (zh) "刷新" else "Refresh",
                    onPrimary = onRefresh,
                )
                kv != null -> RzKeyValueTable(kv, zh)
                strList.isNotEmpty() && rows.isEmpty() -> RzStringList(strList)
                rows.isNotEmpty() -> RzObjectTable(rows, cols, zh, context)
                else -> AnalysisEmptyState(
                    title = if (zh) "空清单" else "Empty list",
                    hint = if (zh) "该文件可能不含这一类内容" else "This file may not contain this kind of data",
                )
            }
        }
    }
}

/** 键/值表（ELF 头、哈希等）。 */
@Composable
private fun RzKeyValueTable(obj: JSONObject, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val keys = remember(obj) {
        val ks = ArrayList<String>()
        val it = obj.keys()
        while (it.hasNext()) ks.add(it.next())
        ks.sorted()
    }
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(if (zh) "字段" else "FIELD", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant, modifier = Modifier.width(150.dp))
            Text(if (zh) "值" else "VALUE", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
        }
        GroupDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(keys) { k ->
                val v = jsonScalar(obj.opt(k))
                if (v.isBlank()) return@items
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.width(150.dp),
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                        color = cs.onSurface,
                    )
                }
                GroupDivider()
            }
        }
    }
}

/** 字符串列表（依赖库等）。 */
@Composable
private fun RzStringList(items0: List<String>) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items0) { s ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Link, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Text(
                        s,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GroupDivider()
            }
        }
    }
}

/** 对象表格（列由各视图指定）。 */
@Composable
private fun RzObjectTable(rows: List<JSONObject>, cols: List<RzCol>, zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            cols.forEach { c ->
                Text(
                    if (zh) c.zh else c.en,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = if (c.end) TextAlign.End else TextAlign.Start,
                    modifier = if (c.width == null) Modifier.weight(1f) else Modifier.width(c.width),
                )
            }
        }
        GroupDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(rows) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    cols.forEach { c ->
                        val v = jsonField(row, c.key)
                        Text(
                            v.ifBlank { "--" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                            color = if (c.key == "perm" || c.key == "type") cs.tertiary else cs.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (c.end) TextAlign.End else TextAlign.Start,
                            modifier = if (c.width == null) Modifier.weight(1f) else Modifier.width(c.width),
                        )
                    }
                }
                GroupDivider()
            }
        }
    }
}

// ── 各视图（列定义即页面的“字段设计”，互不共用） ──

@Composable
private fun ElfHeaderView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(tools, zh, context, "elfhdr", "ihj", emptyList(), onRefresh)

@Composable
private fun SegmentsView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(
        tools, zh, context, "segments", "iSSj",
        listOf(
            RzCol("name", "名称", "NAME", 110.dp),
            RzCol("vaddr", "虚拟地址", "VADDR", 86.dp),
            RzCol("size", "大小", "SIZE", 64.dp, end = true),
            RzCol("perm", "权限", "PERM", 52.dp),
            RzCol("align", "对齐", "ALIGN", null),
        ),
        onRefresh,
    )

@Composable
private fun RelocsView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(
        tools, zh, context, "relocs", "irj",
        listOf(
            RzCol("vaddr", "地址", "VADDR", 88.dp),
            RzCol("type", "类型", "TYPE", 66.dp),
            RzCol("name", "符号", "SYMBOL", null),
        ),
        onRefresh,
    )

@Composable
private fun DynamicView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(tools, zh, context, "dynamic", "iHj", emptyList(), onRefresh)

@Composable
private fun LibrariesView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(tools, zh, context, "libraries", "ilj", emptyList(), onRefresh)

@Composable
private fun HashesView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(tools, zh, context, "hashes", "iTj", emptyList(), onRefresh)

@Composable
private fun VersionsView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(
        tools, zh, context, "versions", "iVj",
        listOf(
            RzCol("name", "名称", "NAME", null),
            RzCol("version", "版本", "VERSION", 90.dp),
        ),
        onRefresh,
    )

@Composable
private fun EntriesView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) =
    RzViewScaffold(
        tools, zh, context, "entries", "iej",
        listOf(
            RzCol("vaddr", "虚拟地址", "VADDR", 88.dp),
            RzCol("type", "类型", "TYPE", 66.dp),
            RzCol("name", "名称", "NAME", null),
        ),
        onRefresh,
    )

// ═══════════════════════════════════════════════════════════════════════════
//  逆向工程工具集（复刻 Exbin ToolsHubFragment 的 12 项）
//  本文件所有页面均为纯客户端实现：不打开工作区也能用（与「进制转换/汇编器」一致）。
// ═══════════════════════════════════════════════════════════════════════════

private data class ToolEntry(
    val key: String,
    val zh: String,
    val en: String,
    val zhDesc: String,
    val enDesc: String,
)

private val toolHubItems = listOf(
    ToolEntry("base", "进制转换", "Base", "BIN / OCT / DEC / HEX 互转 + 字节序 + ASCII", "BIN / OCT / DEC / HEX + endianness + ASCII"),
    ToolEntry("strdec", "字符串解码", "Decode", "Hex / Base64 / UTF-8 / ASCII / URL 解码与编码", "Hex / Base64 / UTF-8 / ASCII / URL decode &amp; encode"),
    ToolEntry("asm", "汇编器", "Assembler", "汇编 → 机器码（多架构，可视化预览与写回）", "Assemble → machine code (multi-arch)"),
    ToolEntry("xor", "XOR 解密", "XOR", "对 hex 数据用 key 做 XOR，输出 hex + ASCII", "XOR hex data with key → hex + ASCII"),
    ToolEntry("elfhdr", "ELF 头解析", "ELF Header", "解析 ELF 头字段、程序段与节表结构", "Parse ELF header, program &amp; section tables"),
    ToolEntry("regs", "寄存器速查", "Registers", "ARM64 / ARM32 寄存器用途与调用约定", "ARM64 / ARM32 registers &amp; calling convention"),
    ToolEntry("hex", "十六进制查看器", "Hex Viewer", "按偏移查看当前文件的十六进制内容", "Hex view of the current file"),
    ToolEntry("bytediff", "字节差分对比", "Byte Diff", "对比两段 hex 数据，逐字节输出差异行", "Diff two hex blobs byte by byte"),
    ToolEntry("insnexp", "指令含义", "Insn Explain", "ARM / ARM64 汇编指令中文语义查询", "Explain ARM / ARM64 instructions in Chinese"),
    ToolEntry("asm2c", "汇编转伪C", "Asm → C", "把多条汇编指令翻译成可读的伪 C 代码", "Translate assembly into readable pseudo-C"),
    ToolEntry("asm2flow", "汇编转流程图", "Asm → Flow", "按基本块切分汇编，输出 ASCII 框图", "Split asm into basic blocks → ASCII flowchart"),
    ToolEntry("demangle", "符号解码", "Demangle", "Itanium C++ mangled 符号解码", "Decode Itanium mangled C++ symbols"),
)

@Composable
private fun ToolsHubView(zh: Boolean, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Build, null, tint = cs.primary, modifier = Modifier.size(15.dp))
            Text(
                if (zh) "逆向工程工具集 · 共 ${toolHubItems.size} 项" else "Reverse Engineering Toolkit · ${toolHubItems.size} tools",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
            )
        }
        toolHubItems.forEach { item ->
            Surface(
                onClick = { onOpen(item.key) },
                shape = RoundedCornerShape(AppShape.md),
                color = cs.surfaceContainerHigh,
                border = BorderStroke(1.dp, cs.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(cs.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(toolHubIcon(item.key), null, tint = cs.primary, modifier = Modifier.size(15.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (zh) item.zh else item.en,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = AppText.bodyStrong,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface,
                        )
                        Text(
                            if (zh) item.zhDesc else item.enDesc,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = AppText.label,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun toolHubIcon(key: String): ImageVector = when (key) {
    "base" -> Icons.Filled.Calculate
    "strdec" -> Icons.Filled.DataObject
    "asm" -> Icons.Filled.SwapHoriz
    "xor" -> Icons.Filled.LockOpen
    "elfhdr" -> Icons.Filled.Info
    "regs" -> Icons.Filled.Memory
    "hex" -> Icons.Filled.Storage
    "bytediff" -> Icons.Filled.CompareArrows
    "insnexp" -> Icons.Filled.Description
    "asm2c" -> Icons.Filled.Transform
    "asm2flow" -> Icons.Filled.Inventory2
    else -> Icons.Filled.ListAlt
}

// ───────────────────────── 通用小工具 ─────────────────────────

/** 宽松地把一串 hex（允许空格/逗号/0x 前缀）解析成字节数组；非法返回 null。 */
private fun hexToBytesLoose(raw: String): ByteArray? {
    val t = raw.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    if (t.isEmpty() || t.length % 2 != 0) return null
    return runCatching { ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
}

/** 标准 hex dump：偏移 + 16 字节 hex（中点分隔）+ ASCII。 */
private fun hexDumpBytes(bytes: ByteArray, base: Long = 0L): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val n = minOf(16, bytes.size - i)
        sb.append("%08x  ".format(base + i))
        for (j in 0 until 16) {
            if (j < n) sb.append("%02x ".format(bytes[i + j].toInt() and 0xff)) else sb.append("   ")
            if (j == 7) sb.append(' ')
        }
        sb.append(' ')
        for (j in 0 until n) {
            val c = bytes[i + j].toInt() and 0xff
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        sb.append('\n')
        i += 16
    }
    return sb.toString().trimEnd('\n')
}

/** 可打印 ASCII 表示（非可打印用 '.'）。 */
private fun bytesToAscii(bytes: ByteArray): String =
    bytes.map { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar() else '.' }.joinToString("")

/** 工具页统一外壳：标题行 + 动作行 + 内容区。 */
@Composable
private fun ToolPageScaffold(
    title: String,
    hint: String,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontSize = AppText.bodyStrong,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { actions() }
        Spacer(Modifier.size(8.dp))
        content()
    }
}

/** 工具页的等宽多行输入框。 */
@Composable
private fun ToolMonoField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    minHeight: androidx.compose.ui.unit.Dp = 96.dp,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        label = { Text(label, fontSize = AppText.label) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
        shape = RoundedCornerShape(AppShape.sm),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                color = cs.onSurfaceVariant,
            )
        },
    )
}

/** 结果块：标题 + 等宽内容（可横向滚动）。 */
@Composable
private fun ToolResultBlock(title: String, body: String, zh: Boolean = true, onCopy: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onCopy != null) TextButton(onClick = onCopy) { Text(if (zh) "复制" else "Copy", fontSize = AppText.label) }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
                color = cs.onSurface,
                lineHeight = 16.sp,
            )
        }
    }
}

// ───────────────────────── 1. XOR 解密 ─────────────────────────

private fun xorTransform(data: ByteArray, key: ByteArray, scheme: String): ByteArray =
    ByteArray(data.size) { i ->
        val k = when (scheme) {
            "single" -> key[0].toInt() and 0xff
            "inc" -> (key[0].toInt() and 0xff) + i
            "dec" -> (key[0].toInt() and 0xff) - i
            else -> key[i % key.size].toInt() and 0xff
        }
        ((data[i].toInt() and 0xff) xor (k and 0xff)).toByte()
    }

@Composable
private fun XorDecryptView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var keyMode by remember { mutableStateOf("hex") }
    var scheme by remember { mutableStateOf("repeat") }

    val data = remember(input) { hexToBytesLoose(input) }
    val key = remember(keyText, keyMode) {
        if (keyMode == "text") keyText.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
        else hexToBytesLoose(keyText)
    }
    val out = remember(data, key, scheme) {
        if (data == null || key == null || key.isEmpty()) null else xorTransform(data, key, scheme)
    }

    ToolPageScaffold(
        title = if (zh) "XOR 解密" else "XOR decrypt",
        hint = if (zh) "对 hex 数据用 key 做 XOR，输出 hex + ASCII" else "XOR hex data with key → hex + ASCII",
        actions = {
            SmallAction("HEX key", active = keyMode == "hex") { keyMode = "hex" }
            SmallAction("TEXT key", active = keyMode == "text") { keyMode = "text" }
            SmallAction(if (zh) "循环" else "Repeat", active = scheme == "repeat") { scheme = "repeat" }
            SmallAction(if (zh) "单字节" else "Single", active = scheme == "single") { scheme = "single" }
            SmallAction(if (zh) "递增" else "Inc", active = scheme == "inc") { scheme = "inc" }
            SmallAction(if (zh) "递减" else "Dec", active = scheme == "dec") { scheme = "dec" }
            SmallAction(if (zh) "清空" else "Clear", enabled = input.isNotBlank() || keyText.isNotBlank()) {
                input = ""; keyText = ""
            }
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMonoField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (zh) "hex 数据" else "hex data",
                    placeholder = if (zh) "48 65 6c 6c 6f  或  0x48656c6c6f" else "48 65 6c 6c 6f",
                    minHeight = 110.dp,
                )
                ToolMonoField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = if (zh) "key" else "key",
                    placeholder = if (keyMode == "hex") (if (zh) "hex，如 6b6579" else "hex, e.g. 6b6579")
                        else (if (zh) "文本，如 secret" else "text, e.g. secret"),
                    minHeight = 52.dp,
                )
                when {
                    data == null -> AnalysisEmptyState(
                        title = if (zh) "XOR 解密" else "XOR decrypt",
                        hint = if (zh) "在「hex 数据」里粘贴偶数长度的十六进制字节，在「key」里给一个 hex（或切到 TEXT key 用文本），选择循环/单字节/递增/递减后即可解密。"
                            else "Paste an even-length hex blob and a key (hex, or TEXT mode), then pick repeat/single/inc/dec.",
                    )
                    key == null -> AnalysisErrorBanner(if (zh) "key 非法：hex 模式需要偶数长度的十六进制" else "invalid key: hex mode needs even-length hex")
                    else -> {
                        val o = out!!
                        ToolResultBlock(
                            if (zh) "解密结果（hex dump）" else "Result (hex dump)",
                            hexDumpBytes(o),
                            zh = zh,
                            onCopy = { copyToClipboard(context, o.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }, zh) },
                        )
                        ToolResultBlock(
                            "ASCII",
                            bytesToAscii(o).ifBlank { "—" },
                            zh = zh,
                            onCopy = { copyToClipboard(context, bytesToAscii(o), zh) },
                        )
                        KeyValueCard(zh, listOf(
                            (if (zh) "长度" else "length") to "${data.size} ${if (zh) "字节" else "bytes"}",
                            ("key") to (if (keyMode == "hex") key.joinToString(" ") { "%02x".format(it.toInt() and 0xff) } else keyText),
                            (if (zh) "模式" else "scheme") to scheme,
                        ))
                    }
                }
            }
        },
    )
}

// ───────────────────────── 2. 寄存器速查 ─────────────────────────

private data class RegRow(val name: String, val alias: String, val usage: String)

private val arm64GeneralRegs = listOf(
    RegRow("x0", "参数1 / 返回值", "第 1 个参数；函数返回时存放返回值（w0 为低 32 位）"),
    RegRow("x1–x7", "参数 2–8", "第 2 到第 8 个整型/指针参数"),
    RegRow("x8", "间接结果寄存器", "返回大对象时存放返回缓冲区指针（与 Apple ABI 相关）"),
    RegRow("x9–x15", "临时寄存器", "调用者保存（caller-saved），子函数可随意破坏"),
    RegRow("x16 (IP0)", "过程内临时 0", "链接器修补/长跳转（PLT）中间寄存器，勿作长期存储"),
    RegRow("x17 (IP1)", "过程内临时 1", "同 x16，用于长跳转"),
    RegRow("x18", "平台寄存器", "Android/Linux 保留，用户代码不应使用"),
    RegRow("x19–x28", "被调用者保存", "callee-saved：子函数若要使用必须先保存、返回前恢复"),
    RegRow("x29 (FP)", "帧指针", "指向当前栈帧，用于回溯与调试"),
    RegRow("x30 (LR)", "链接寄存器", "存放 bl/blr 的返回地址（调用返回地址）"),
    RegRow("SP", "栈指针", "指向当前栈顶（16 字节对齐）"),
    RegRow("PC", "程序计数器", "下一条指令地址（ARM64 不能直接 mov 到 PC）"),
    RegRow("XZR / WZR", "零寄存器", "读取恒为 0，写入被丢弃（常用作丢弃结果的占位）"),
    RegRow("W0–W30", "x0–x30 低 32 位", "写入 wN 会把 xN 的高 32 位清零"),
)

private val arm64SpecialRegs = listOf(
    RegRow("NZCV", "条件标志", "N 负 / Z 零 / C 进位 / V 溢出，由 cbz、cmp(b) 等设置"),
    RegRow("V0–V31", "SIMD/FP 寄存器", "128 位向量寄存器；Q=128b / D=64b / S=32b / H=16b / B=8b"),
    RegRow("FPCR / FPSR", "浮点控制/状态", "舍入模式、异常标志等"),
    RegRow("TPIDR_EL0", "线程指针", "TLS 基址（Android 上指向线程控制块）"),
    RegRow("SP_EL0 / SP_ELx", "各异常级栈指针", "EL0 用户态栈（应用层基本只见 SP_EL0）"),
)

private val arm32Regs = listOf(
    RegRow("r0", "参数1 / 返回值", "第 1 个参数；返回时存返回值；也作临时寄存器"),
    RegRow("r1–r3", "参数 2–4", "第 2–4 个参数（调用者保存）"),
    RegRow("r4–r8", "通用变量寄存器", "被调用者保存（callee-saved）"),
    RegRow("r9", "平台寄存器", "Android 上常保留（SB 静态基址），一般不使用"),
    RegRow("r10 (SL)", "栈界限", "栈限制寄存器，部分平台保留"),
    RegRow("r11 (FP)", "帧指针", "指向当前栈帧"),
    RegRow("r12 (IP)", "过程内临时", "长跳转/链接器修补中间寄存器"),
    RegRow("r13 (SP)", "栈指针", "指向当前栈顶"),
    RegRow("r14 (LR)", "链接寄存器", "存放 bl/blx 的返回地址"),
    RegRow("r15 (PC)", "程序计数器", "下一条指令地址（可读，赋值语义特殊）"),
    RegRow("CPSR", "状态寄存器", "N/Z/C/V/Q 标志 + 中断位 + 模式位（r15 之外的全局状态）"),
    RegRow("S0–S31 / D0–D15", "VFP/NEON", "浮点与 SIMD 寄存器（S=单精度 / D=双精度）"),
)

@Composable
private fun ArmRegisterView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    var arch by remember { mutableStateOf("arm64") }
    val general = if (arch == "arm64") arm64GeneralRegs else arm32Regs
    val special = if (arch == "arm64") arm64SpecialRegs else arm32Regs.drop(11)

    ToolPageScaffold(
        title = if (zh) "寄存器速查" else "Registers",
        hint = if (zh) "ARM64 / ARM32 寄存器用途与调用约定" else "ARM64 / ARM32 registers & calling convention",
        actions = {
            SmallAction("AArch64", active = arch == "arm64") { arch = "arm64" }
            SmallAction("ARM (A32)", active = arch == "arm32") { arch = "arm32" }
            SmallAction(if (zh) "复制全部" else "Copy all") {
                val sb = StringBuilder()
                sb.append(if (arch == "arm64") "AArch64\n" else "ARM (A32)\n")
                general.forEach { sb.append("%-12s %-18s %s\n".format(it.name, it.alias, it.usage)) }
                special.forEach { sb.append("%-12s %-18s %s\n".format(it.name, it.alias, it.usage)) }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RegSection(if (zh) "通用寄存器" else "General purpose", general)
                RegSection(if (zh) "特殊 / 状态寄存器" else "Special / status", special)
                val conv = if (arch == "arm64") listOf(
                    ("参数" to "x0–x7（超出部分走栈）"),
                    ("返回值" to "x0（大对象用 x8 指针）"),
                    ("调用者保存" to "x0–x18"),
                    ("被调用者保存" to "x19–x28, x29(FP), SP"),
                    ("栈对齐" to "16 字节"),
                ) else listOf(
                    ("参数" to "r0–r3（超出部分走栈）"),
                    ("返回值" to "r0（64 位用 r0:r1）"),
                    ("调用者保存" to "r0–r3, r12(IP)"),
                    ("被调用者保存" to "r4–r11, r13(SP)"),
                    ("栈对齐" to "8 字节（部分 ABI 16）"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        if (zh) "调用约定（AAPCS）" else "Calling convention (AAPCS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = AppText.label,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurfaceVariant,
                    )
                    KeyValueCard(zh, conv)
                }
            }
        },
    )
}

@Composable
private fun RegSection(title: String, rows: List<RegRow>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontSize = AppText.label,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurfaceVariant,
        )
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontSize = AppText.body,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.primary,
                        )
                        Text(
                            r.alias,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = AppText.label,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Text(
                        r.usage,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = AppText.label,
                        color = cs.onSurface,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

// ───────────────────────── 3. 字符串解码（Hex / Base64 / UTF-8 / ASCII / URL） ─────────────────────────

private fun decodeHexToText(s: String): String? {
    val b = hexToBytesLoose(s) ?: return null
    if (b.isEmpty()) return null
    return runCatching { String(b, Charsets.UTF_8) }.getOrNull()
}

private fun decodeBase64ToText(s: String): String? {
    val t = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    if (t.length < 4) return null
    return runCatching {
        val padded = t.padEnd(((t.length + 3) / 4) * 4, '=')
        String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun decodeUrl(s: String): String? = runCatching {
    java.net.URLDecoder.decode(s, "UTF-8")
}.getOrNull()

private fun decodeUnicodeEscapes(s: String): String? {
    if (!s.contains("\\u")) return null
    val re = Regex("\\\\u([0-9a-fA-F]{4})")
    if (!re.containsMatchIn(s)) return null
    return re.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
}

@Composable
private fun StringDecodeView(zh: Boolean, context: android.content.Context) {
    var input by remember { mutableStateOf("") }

    val trimmed = input.trim()
    val hexText = remember(trimmed) { if (trimmed.isNotBlank()) decodeHexToText(trimmed) else null }
    val b64Text = remember(trimmed) { if (trimmed.isNotBlank()) decodeBase64ToText(trimmed) else null }
    val urlText = remember(trimmed) { if (trimmed.isNotBlank() && (trimmed.contains('%') || trimmed.contains('+'))) decodeUrl(trimmed) else null }
    val uniText = remember(trimmed) { if (trimmed.isNotBlank()) decodeUnicodeEscapes(trimmed) else null }
    val hexOfInput = remember(trimmed) {
        if (trimmed.isBlank()) null else trimmed.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
    }
    val b64OfInput = remember(trimmed) {
        if (trimmed.isBlank()) null else runCatching {
            android.util.Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }
    val urlOfInput = remember(trimmed) {
        if (trimmed.isBlank()) null else runCatching { java.net.URLEncoder.encode(trimmed, "UTF-8") }.getOrNull()
    }

    ToolPageScaffold(
        title = if (zh) "字符串解码" else "String decoder",
        hint = if (zh) "Hex / Base64 / UTF-8 / ASCII / URL / Unicode 转义" else "Hex / Base64 / UTF-8 / ASCII / URL / \\u escapes",
        actions = {
            SmallAction(if (zh) "清空" else "Clear", enabled = input.isNotBlank()) { input = "" }
            SmallAction(if (zh) "复制输入" else "Copy input", enabled = trimmed.isNotBlank()) { copyToClipboard(context, trimmed, zh) }
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMonoField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (zh) "输入" else "input",
                    placeholder = if (zh) "粘贴 hex / base64 / URL 编码串，或普通文本" else "hex / base64 / URL-encoded, or plain text",
                    minHeight = 100.dp,
                )
                if (trimmed.isBlank()) {
                    AnalysisEmptyState(
                        title = if (zh) "字符串解码" else "String decoder",
                        hint = if (zh) "粘贴任意字符串，自动尝试 Hex、Base64、URL、Unicode 转义解码，并给出 Hex / Base64 / URL 编码结果。"
                            else "Paste any string; it auto-tries hex, base64, URL and \\u escapes, and also shows hex/base64/URL encodings.",
                    )
                } else {
                    if (hexText != null) ToolResultBlock("HEX → Text", hexText, zh = zh, onCopy = { copyToClipboard(context, hexText, zh) })
                    if (b64Text != null) ToolResultBlock("Base64 → Text", b64Text, zh = zh, onCopy = { copyToClipboard(context, b64Text, zh) })
                    if (urlText != null && urlText != trimmed) ToolResultBlock("URL → Text", urlText, zh = zh, onCopy = { copyToClipboard(context, urlText, zh) })
                    if (uniText != null && uniText != trimmed) ToolResultBlock(if (zh) "Unicode 转义 → Text" else "\\u escape → Text", uniText, zh = zh, onCopy = { copyToClipboard(context, uniText, zh) })
                    if (hexOfInput != null) ToolResultBlock("Text → HEX", hexOfInput, zh = zh, onCopy = { copyToClipboard(context, hexOfInput, zh) })
                    if (b64OfInput != null) ToolResultBlock("Text → Base64", b64OfInput, zh = zh, onCopy = { copyToClipboard(context, b64OfInput, zh) })
                    if (urlOfInput != null) ToolResultBlock("Text → URL", urlOfInput, zh = zh, onCopy = { copyToClipboard(context, urlOfInput, zh) })
                }
            }
        },
    )
}

// ───────────────────────── 4. 字节差分对比 ─────────────────────────

@Composable
private fun ByteDiffView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    var aText by remember { mutableStateOf("") }
    var bText by remember { mutableStateOf("") }

    val a = remember(aText) { if (aText.isBlank()) null else hexToBytesLoose(aText) }
    val b = remember(bText) { if (bText.isBlank()) null else hexToBytesLoose(bText) }
    val diffs = remember(a, b) {
        if (a == null || b == null) null
        else {
            val n = maxOf(a.size, b.size)
            (0 until n).mapNotNull { i ->
                val x = if (i < a.size) a[i] else null
                val y = if (i < b.size) b[i] else null
                if (x != y) Triple(i, x, y) else null
            }
        }
    }

    ToolPageScaffold(
        title = if (zh) "字节差分对比" else "Byte diff",
        hint = if (zh) "对比两段 hex 数据，逐字节输出差异行" else "Diff two hex blobs byte by byte",
        actions = {
            SmallAction(if (zh) "清空" else "Clear", enabled = aText.isNotBlank() || bText.isNotBlank()) { aText = ""; bText = "" }
            SmallAction(if (zh) "交换 A/B" else "Swap A/B", enabled = aText.isNotBlank() || bText.isNotBlank()) {
                val t = aText; aText = bText; bText = t
            }
            SmallAction(if (zh) "复制差异" else "Copy diff", enabled = !diffs.isNullOrEmpty()) {
                val sb = StringBuilder()
                diffs?.forEach { (i, x, y) ->
                    sb.append("%08x  A:%s  B:%s\n".format(i, x?.let { "%02x".format(it.toInt() and 0xff) } ?: "--", y?.let { "%02x".format(it.toInt() and 0xff) } ?: "--"))
                }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMonoField(aText, { aText = it }, "A (hex)", if (zh) "原始数据的十六进制" else "original hex", 84.dp)
                ToolMonoField(bText, { bText = it }, "B (hex)", if (zh) "对比数据的十六进制" else "patched hex", 84.dp)
                when {
                    a == null || b == null || diffs == null -> AnalysisEmptyState(
                        title = if (zh) "字节差分对比" else "Byte diff",
                        hint = if (zh) "在 A / B 里分别粘贴两段 hex 数据（偶数长度），逐字节比较并列出所有差异偏移。"
                            else "Paste two even-length hex blobs into A / B; every differing byte is listed with its offset.",
                    )
                    diffs.isEmpty() -> KeyValueCard(zh, listOf(
                        (if (zh) "结果" else "result") to (if (zh) "两段数据完全一致" else "identical"),
                        (if (zh) "长度" else "length") to "${a.size} / ${b.size}",
                    ))
                    else -> {
                        val dl = diffs ?: emptyList()
                        KeyValueCard(zh, listOf(
                            (if (zh) "差异" else "diffs") to "${dl.size} ${if (zh) "字节" else "bytes"}",
                            (if (zh) "长度" else "length") to "${a.size} / ${b.size}",
                        ))
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppShape.md))
                                .background(cs.surfaceContainerHigh)
                                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                listOf(("OFFSET" to 84.dp), ("A" to 0.dp), ("B" to 0.dp)).forEachIndexed { idx, (t, w) ->
                                    Text(
                                        t,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = AppText.label,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cs.onSurfaceVariant,
                                        modifier = if (idx == 0) Modifier.width(w) else Modifier.weight(1f),
                                    )
                                }
                            }
                            dl.take(400).forEach { (i, x, y) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        "%08x".format(i),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        fontSize = AppText.label,
                                        color = cs.onSurfaceVariant,
                                        modifier = Modifier.width(84.dp),
                                    )
                                    Text(
                                        x?.let { "%02x".format(it.toInt() and 0xff) } ?: "--",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        fontSize = AppText.body,
                                        color = cs.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        y?.let { "%02x".format(it.toInt() and 0xff) } ?: "--",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        fontSize = AppText.body,
                                        color = cs.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (dl.size > 400) {
                                Text(
                                    if (zh) "… 仅显示前 400 处差异" else "… showing first 400 diffs",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = AppText.label,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

// ───────────────────────── 5. 指令含义（ARM / ARM64 中文语义查询） ─────────────────────────

private val insnTable: Map<String, String> = mapOf(
    // AArch64 数据传送
    "mov" to "传送（寄存器 → 寄存器，或别名 movz/movn 的简写）",
    "movz" to "把 16 位立即数移入寄存器的某一段，其余位清零",
    "movk" to "把 16 位立即数移入寄存器的某一段，其余位保持不变",
    "movn" to "把 16 位立即数取反后移入寄存器",
    "mvn" to "按位取反后传送",
    "ldr" to "加载（从内存地址读 32/64 位到寄存器，或加载字面量常量）",
    "ldrb" to "加载字节（8 位，零扩展）",
    "ldrh" to "加载半字（16 位，零扩展）",
    "ldrsb" to "加载字节并符号扩展",
    "ldrsh" to "加载半字并符号扩展",
    "ldrsw" to "加载字（32 位）并符号扩展到 64 位",
    "ldur" to "加载（非对齐/未缩放偏移，偏移范围更小）",
    "str" to "存储（把寄存器的 32/64 位写入内存）",
    "strb" to "存储字节（8 位）",
    "strh" to "存储半字（16 位）",
    "stur" to "存储（未缩放偏移）",
    "ldp" to "成对加载（一次从内存读两个寄存器，常用于恢复 FP/LR）",
    "stp" to "成对存储（一次把两个寄存器压栈，常用于保存 FP/LR）",
    "ldar" to "带获取语义的原子加载（acquire）",
    "stlr" to "带释放语义的原子存储（release）",
    // AArch64 算术
    "add" to "加法（不带标志）",
    "adds" to "加法并更新标志位（NZCV）",
    "adc" to "带进位加法",
    "adcs" to "带进位加法并更新标志位",
    "sub" to "减法",
    "subs" to "减法并更新标志位（常用于比较）",
    "sbc" to "带借位减法",
    "neg" to "取负（0 − 操作数）",
    "negs" to "取负并更新标志位",
    "mul" to "乘法（低 64 位结果）",
    "madd" to "乘加（a×b + c）",
    "msub" to "乘减（c − a×b）",
    "smull" to "有符号长乘法（64 位结果）",
    "umull" to "无符号长乘法（64 位结果）",
    "sdiv" to "有符号除法",
    "udiv" to "无符号除法",
    "inc" to "自增（伪指令，等价 add #1）",
    "dec" to "自减（伪指令，等价 sub #1）",
    // AArch64 逻辑 / 移位
    "and" to "按位与",
    "ands" to "按位与并更新标志位",
    "orr" to "按位或",
    "eor" to "按位异或",
    "bic" to "按位清零（a AND NOT b）",
    "orn" to "按位或非（a OR NOT b）",
    "eon" to "按位同或非（a XOR NOT b）",
    "lsl" to "逻辑左移",
    "lsr" to "逻辑右移（补 0）",
    "asr" to "算术右移（补符号位）",
    "ror" to "循环右移",
    "tst" to "按位测试（AND 后丢弃结果，仅更新标志）",
    "cmp" to "比较（做减法只更新标志，不写回）",
    "cmn" to "比较取负（把操作数取负后相加，更新标志）",
    "cset" to "条件置位（条件成立写 1，否则 0）",
    "csel" to "条件选择（根据条件在两个寄存器间二选一）",
    "csinc" to "条件选择并自增（不成立侧 +1，用于实现 cset/cinc）",
    "csinv" to "条件选择并按位取反",
    "csneg" to "条件选择并取负",
    "ccmp" to "条件比较（满足条件才比较）",
    // AArch64 跳转 / 分支
    "b" to "无条件跳转（相对偏移 ±128MB）",
    "bl" to "带链接跳转（调用函数，返回地址写入 x30/LR）",
    "br" to "按寄存器跳转（间接跳转，如跳转表）",
    "blr" to "按寄存器调用（返回地址写入 x30/LR，用于间接调用）",
    "ret" to "函数返回（默认跳到 x30/LR）",
    "cbz" to "比较寄存器为 0 则跳转",
    "cbnz" to "比较寄存器非 0 则跳转",
    "tbz" to "测试某一位为 0 则跳转",
    "tbnz" to "测试某一位为 1 则跳转",
    "adr" to "把相对地址（±1MB）加载到寄存器",
    "adrp" to "把相对页地址加载到寄存器（4KB 页对齐，常与 ADD 配合取符号地址）",
    // AArch64 系统 / 其它
    "nop" to "空操作（不改变任何状态）",
    "hlt" to "停机/断点指令（异常）",
    "brk" to "断点（软件断点，进入调试异常）",
    "svc" to "系统调用（Supervisor Call，进入内核）",
    "hvc" to "超级调用（进入 Hypervisor）",
    "mrs" to "读系统寄存器到通用寄存器",
    "msr" to "把通用寄存器写入系统寄存器",
    "dmb" to "数据内存屏障（保证内存访问顺序）",
    "dsb" to "数据同步屏障（等待内存访问完成）",
    "isb" to "指令同步屏障（刷新流水线）",
    "crc32b" to "CRC32 校验（按字节）",
    "crc32w" to "CRC32 校验（按字）",
    "ubfx" to "无符号位域提取",
    "sbfx" to "有符号位域提取",
    "ubfiz" to "无符号位域插入",
    "sbfiz" to "有符号位域插入",
    "bfi" to "位域插入",
    "bfxil" to "位域提取并插入低位",
    "extr" to "提取寄存器位段（移位拼接）",
    // 浮点 / SIMD
    "fmov" to "浮点传送（寄存器/立即数 → 浮点寄存器）",
    "fadd" to "浮点加法",
    "fsub" to "浮点减法",
    "fmul" to "浮点乘法",
    "fdiv" to "浮点除法",
    "fmadd" to "浮点乘加（a×b + c）",
    "fmsub" to "浮点乘减",
    "fneg" to "浮点取负",
    "fabs" to "浮点绝对值",
    "fsqrt" to "浮点平方根",
    "fcmp" to "浮点比较（更新标志）",
    "fcsel" to "浮点条件选择",
    "scvtf" to "有符号整数 → 浮点",
    "ucvtf" to "无符号整数 → 浮点",
    "fcvtzs" to "浮点 → 有符号整数（向零截断）",
    "fcvtzu" to "浮点 → 无符号整数（向零截断）",
    "fcvt" to "浮点精度转换（如 double ↔ float）",
    "ld1" to "SIMD 单结构加载",
    "st1" to "SIMD 单结构存储",
    "dup" to "向量的元素广播（复制）",
    "ins" to "向量元素插入",
    "umov" to "从向量元素移动到通用寄存器",
    "addv" to "把向量所有元素相加得到一个标量",
    "cnt" to "统计向量各字节中 1 的个数",
    "movi" to "加载立即数到向量",
    // ARM32 常见
    "rsb" to "反向减法（imm − Rn）",
    "rsc" to "带借位的反向减法",
    "bx" to "按寄存器跳转（常用于返回或跳转表）",
    "blx" to "按寄存器调用（可切换 ARM/Thumb 状态）",
    "push" to "压栈（等价 STMDB sp!, {regs}）",
    "pop" to "出栈（等价 LDMIA sp!, {regs}）",
    "ldm" to "多寄存器加载（批量读内存）",
    "stm" to "多寄存器存储（批量写内存）",
    "swi" to "软件中断（老式系统调用）",
    "bkpt" to "断点指令",
    "ite" to "If-Then-Else 条件执行块（Thumb）",
    "it" to "If-Then 条件执行块（Thumb）",
    "tbb" to "跳转表字节查表分支",
    "tbh" to "跳转表半字查表分支",
    "vldr" to "VFP 加载（浮点从内存到 S/D 寄存器）",
    "vstr" to "VFP 存储（浮点到内存）",
    "vmov" to "在通用寄存器与 VFP 寄存器之间传送数据",
    "teq" to "按位异或测试（仅更新标志）",
    "rrx" to "带扩展的循环右移",
    "clz" to "统计前导零个数",
    "rbit" to "按位反转",
    "rev" to "字节序反转",
)

/** 从一行汇编里剥离地址/字节前缀（如 "0x1000  mov x0, x1" / "1000:  mov ..."），返回助记符与操作数。 */
private fun splitInsn(line: String): Pair<String, String>? {
    var s = line.trim()
    if (s.isEmpty() || s.startsWith(";") || s.startsWith("//") || s.startsWith("//")) return null
    // 去掉行首地址：0x1234: / 1234: / 0x1234 后跟若干 hex 字节
    s = s.replace(Regex("^0x[0-9a-fA-F]+\\s*:?\\s*"), "")
    s = s.replace(Regex("^[0-9a-fA-F]{4,16}\\s*:?\\s*"), "")
    // 去掉行内注释
    s = s.substringBefore(";").substringBefore("//").trim()
    if (s.isEmpty()) return null
    val parts = s.split(Regex("\\s+"), limit = 2)
    val mnem = parts[0].lowercase().trimEnd(',')
    if (mnem.isEmpty()) return null
    return mnem to (parts.getOrNull(1) ?: "")
}

/** 把操作数字符串解析出「目标 / 源」的粗粒度语义。 */
private fun explainOperands(ops: String): List<Pair<String, String>> {
    if (ops.isBlank()) return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    ops.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { idx, raw ->
        val role = if (idx == 0) "目标" else "源$idx"
        out += role to describeOperand(raw)
    }
    return out
}

private fun describeOperand(op: String): String {
    val o = op.trim()
    return when {
        o.startsWith("[") -> "内存 " + o.replace("[", "[").replace("]", "]")
            .replace("#", "").let { "访问 $it" }
        o.startsWith("#") -> "立即数 ${o.substring(1)}"
        o.matches(Regex("^[wx]\\d{1,2}$")) -> "通用寄存器 $o"
        o.matches(Regex("^[wW]zr$|^[xX]zr$")) -> "零寄存器 $o"
        o.equals("sp", true) || o.equals("wsp", true) -> "栈指针"
        o.matches(Regex("^(lr|x30)$", RegexOption.IGNORE_CASE)) -> "链接寄存器（返回地址）"
        o.matches(Regex("^(fp|x29)$", RegexOption.IGNORE_CASE)) -> "帧指针"
        o.matches(Regex("^(pc|x15)$", RegexOption.IGNORE_CASE)) -> "程序计数器"
        o.matches(Regex("^[vqds]\\d{1,2}$", RegexOption.IGNORE_CASE)) -> "SIMD/浮点寄存器 $o"
        o.matches(Regex("^\\{.*\\}$")) -> "寄存器列表 $o"
        o.startsWith("0x") || o.matches(Regex("^\\d+$")) -> "常量／偏移 $o"
        else -> "符号／标签 $o"
    }
}

@Composable
private fun InsnExplainView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("mov x0, x1\nldr x2, [x0, #0x8]\nbl 0x1234") }

    val lines = remember(input) { input.split("\n").map { it.trim() }.filter { it.isNotEmpty() } }
    val explained = remember(input) {
        lines.mapNotNull { raw ->
            val sp = splitInsn(raw) ?: return@mapNotNull null
            val (mnem, ops) = sp
            val desc = insnTable[mnem]
                ?: insnTable[mnem.removeSuffix("s")]?.let { "$it（带标志位变体）" }
                ?: insnTable[strBase(mnem)]?.let { it }
            Triple(raw, mnem, (desc ?: "未收录的指令（请核实拼写或查阅 ARM 手册）") to explainOperands(ops))
        }
    }

    ToolPageScaffold(
        title = if (zh) "指令含义" else "Instruction explain",
        hint = if (zh) "ARM / ARM64 汇编指令中文语义查询（逐行）" else "Explain ARM / ARM64 instructions line by line",
        actions = {
            SmallAction(if (zh) "清空" else "Clear", enabled = input.isNotBlank()) { input = "" }
            SmallAction(if (zh) "复制说明" else "Copy", enabled = explained.isNotEmpty()) {
                val sb = StringBuilder()
                explained.forEach { (raw, mnem, pair) ->
                    sb.append("$raw\n  [$mnem] ${pair.first}\n")
                    pair.second.forEach { (r, d) -> sb.append("    $r: $d\n") }
                }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            Text(
                if (zh) "已收录 ${insnTable.size} 条" else "${insnTable.size} mnemonics",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
            )
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMonoField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (zh) "汇编指令（每行一条）" else "assembly (one per line)",
                    placeholder = "mov x0, x1\nldr x2, [x0, #0x8]",
                    minHeight = 110.dp,
                )
                if (explained.isEmpty()) {
                    AnalysisEmptyState(
                        title = if (zh) "指令含义" else "Instruction explain",
                        hint = if (zh) "逐行粘贴汇编指令（可带地址前缀或分号注释），得到每条助记符的中文语义与操作数解读。"
                            else "Paste assembly lines (address prefixes / comments allowed) to get mnemonic semantics and operand roles.",
                    )
                } else {
                    explained.forEach { (raw, mnem, pair) ->
                        val (desc, opList) = pair
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppShape.md))
                                .background(cs.surfaceContainerHigh)
                                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.body,
                                color = cs.primary,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    mnem,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    fontSize = AppText.label,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(cs.primary.copy(alpha = 0.14f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = AppText.body,
                                    color = cs.onSurface,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (opList.isNotEmpty()) {
                                opList.forEach { (role, d) ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            role,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = AppText.label,
                                            color = cs.onSurfaceVariant,
                                            modifier = Modifier.width(38.dp),
                                        )
                                        Text(
                                            d,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = AppText.label,
                                            color = cs.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** 去掉常见后缀变体，尝试回到基础助记符（subs → sub、ldrb → ldr）。 */
private fun strBase(mnem: String): String {
    val cands = listOf("b", "s", "h", "w", "n", "sb", "sh", "sw", "z", "t")
    for (c in cands) {
        if (mnem.endsWith(c) && mnem.length > c.length + 1) {
            val base = mnem.dropLast(c.length)
            if (insnTable.containsKey(base)) return base
        }
    }
    return mnem
}

// ───────────────────────── 6. 汇编转伪 C ─────────────────────────

private data class AsmLine(val addr: String, val mnem: String, val ops: String, val raw: String)

private fun parseAsmLines(input: String): List<AsmLine> {
    return input.split("\n").mapNotNull { rawLine ->
        val raw = rawLine.trim()
        if (raw.isEmpty() || raw.startsWith(";") || raw.startsWith("//")) return@mapNotNull null
        var addr = ""
        var rest = raw
        val m = Regex("^(0x[0-9a-fA-F]+|[0-9a-fA-F]{4,16})\\s*:?\\s+").find(raw)
        if (m != null) {
            addr = m.groupValues[1]
            rest = raw.substring(m.value.length).trim()
        }
        rest = rest.substringBefore(";").substringBefore("//").trim()
        if (rest.isEmpty()) return@mapNotNull null
        val parts = rest.split(Regex("\\s+"), limit = 2)
        AsmLine(addr, parts[0].lowercase().trimEnd(','), parts.getOrNull(1)?.trim() ?: "", raw)
    }
}

/** 启发式把汇编翻译成可读的伪 C。 */
private fun asmToPseudoC(lines: List<AsmLine>, zh: Boolean): String {
    if (lines.isEmpty()) return ""
    val sb = StringBuilder()
    val fnName = "sub_" + (lines.firstOrNull()?.addr?.removePrefix("0x").orEmpty().ifBlank { "unknown" })
    sb.append("// ${if (zh) "由汇编启发式翻译（仅供理解，非真实源码）" else "heuristic translation (for reading only)"}\n")
    sb.append("// ${lines.size} ${if (zh) "条指令" else "instructions"}\n\n")
    sb.append("void $fnName(void) {\n")
    var indent = "    "
    var emittedRet = false
    lines.forEach { l ->
        val m = l.mnem
        val o = l.ops
        val comment = if (l.addr.isNotEmpty()) "  // ${l.addr}" else ""
        when {
            m == "ret" -> {
                sb.append("${indent}return; /* $o */$comment\n")
                emittedRet = true
            }
            m == "bl" || m == "blr" -> {
                val target = o.ifBlank { "?" }
                sb.append("${indent}sub_${target.removePrefix("0x")}();$comment\n")
            }
            m == "b" -> {
                sb.append("${indent}goto ${o.ifBlank { "L_unknown" }};$comment\n")
            }
            m == "cbz" || m == "cbnz" -> {
                val regs = o.split(",").map { it.trim() }
                val reg = regs.getOrNull(0) ?: "?"
                val tgt = regs.getOrNull(1) ?: "?"
                val cond = if (m == "cbz") "== 0" else "!= 0"
                sb.append("${indent}if ($reg $cond) goto $tgt;$comment\n")
            }
            m == "tbz" || m == "tbnz" -> {
                sb.append("${indent}if (bit($o) ${if (m == "tbz") "== 0" else "!= 0"}) goto ?;$comment\n")
            }
            m == "cmp" || m == "cmn" || m == "tst" -> {
                sb.append("${indent}flags = ($o);$comment\n")
            }
            m == "mov" || m == "movz" || m == "movk" || m == "movn" || m == "mvn" -> {
                val regs = o.split(",").map { it.trim() }
                val dst = regs.getOrNull(0) ?: "?"
                val src = regs.getOrNull(1) ?: "?"
                sb.append("${indent}$dst = $src;$comment\n")
            }
            m == "add" || m == "adds" -> {
                val regs = o.split(",").map { it.trim() }
                sb.append("${indent}${regs.getOrNull(0) ?: "?"} = ${regs.getOrNull(1) ?: "?"} + ${regs.getOrNull(2) ?: "?"};$comment\n")
            }
            m == "sub" || m == "subs" -> {
                val regs = o.split(",").map { it.trim() }
                sb.append("${indent}${regs.getOrNull(0) ?: "?"} = ${regs.getOrNull(1) ?: "?"} - ${regs.getOrNull(2) ?: "?"};$comment\n")
            }
            m == "mul" || m == "madd" -> {
                val regs = o.split(",").map { it.trim() }
                sb.append("${indent}${regs.getOrNull(0) ?: "?"} = ${regs.getOrNull(1) ?: "?"} * ${regs.getOrNull(2) ?: "?"};$comment\n")
            }
            m == "ldr" || m == "ldrb" || m == "ldrh" || m == "ldrsw" || m == "ldur" -> {
                val regs = o.split(",").map { it.trim() }
                val dst = regs.getOrNull(0) ?: "?"
                val mem = memExpr(regs.drop(1).joinToString(","))
                sb.append("${indent}$dst = $mem;  /* load */$comment\n")
            }
            m == "str" || m == "strb" || m == "strh" || m == "stur" -> {
                val regs = o.split(",").map { it.trim() }
                val src = regs.getOrNull(0) ?: "?"
                val mem = memExpr(regs.drop(1).joinToString(","))
                sb.append("${indent}$mem = $src;  /* store */$comment\n")
            }
            m == "stp" -> {
                sb.append("${indent}push($o);$comment\n")
            }
            m == "ldp" -> {
                sb.append("${indent}pop($o);$comment\n")
            }
            m == "adr" || m == "adrp" -> {
                val regs = o.split(",").map { it.trim() }
                sb.append("${indent}${regs.getOrNull(0) ?: "?"} = &${regs.getOrNull(1) ?: "?"};$comment\n")
            }
            m == "nop" -> sb.append("${indent}/* nop */$comment\n")
            m == "br" -> sb.append("${indent}goto *$o;$comment\n")
            else -> sb.append("${indent}/* ${m} ${o} */$comment\n")
        }
    }
    if (!emittedRet) {
        sb.append("${indent}return; /* fallthrough */\n")
    }
    sb.append("}\n")
    return sb.toString()
}

private fun memExpr(s: String): String {
    val t = s.trim().removePrefix("[").removeSuffix("]").trim()
    if (t.isEmpty()) return "mem"
    val parts = t.split(",").map { it.trim().replace("#", "") }
    val base = parts.getOrNull(0) ?: return "mem"
    val off = parts.getOrNull(1)
    return if (off.isNullOrBlank()) "*(uint64_t*)$base" else "*(uint64_t*)($base + $off)"
}

@Composable
private fun AsmToPseudoCView(zh: Boolean, context: android.content.Context) {
    var input by remember {
        mutableStateOf(
            "0x1000  stp x29, x30, [sp, #-16]!\n" +
                "0x1004  mov x29, sp\n" +
                "0x1008  mov w0, #0x0\n" +
                "0x100c  ldr x1, [x0, #0x8]\n" +
                "0x1010  bl 0x2000\n" +
                "0x1014  ldp x29, x30, [sp], #16\n" +
                "0x1018  ret",
        )
    }
    val lines = remember(input) { parseAsmLines(input) }
    val pseudo = remember(input) { asmToPseudoC(lines, zh) }

    ToolPageScaffold(
        title = if (zh) "汇编转伪C" else "Asm → Pseudo-C",
        hint = if (zh) "把多条汇编指令翻译成可读的伪 C 代码" else "Translate assembly into readable pseudo-C",
        actions = {
            SmallAction(if (zh) "清空" else "Clear", enabled = input.isNotBlank()) { input = "" }
            SmallAction(if (zh) "复制伪C" else "Copy C", enabled = pseudo.isNotBlank()) { copyToClipboard(context, pseudo, zh) }
            Text(
                if (zh) "${lines.size} 条指令" else "${lines.size} insns",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMonoField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (zh) "汇编（每行一条，可带地址）" else "assembly (one per line, address optional)",
                    placeholder = "0x1000  mov x0, #1",
                    minHeight = 130.dp,
                )
                if (pseudo.isBlank()) {
                    AnalysisEmptyState(
                        title = if (zh) "汇编转伪C" else "Asm → Pseudo-C",
                        hint = if (zh) "粘贴一段汇编（可带地址前缀），启发式翻译成函数框架 + 语句级伪 C，便于快速理解逻辑。"
                            else "Paste assembly (address prefixes allowed) to heuristically translate it into a pseudo-C function body.",
                    )
                } else {
                    ToolResultBlock(if (zh) "伪 C（启发式）" else "Pseudo-C (heuristic)", pseudo)
                }
            }
        },
    )
}

// ───────────────────────── 7. 汇编转流程图 ─────────────────────────

private fun isBranch(mnem: String): Boolean =
    mnem == "b" || mnem == "bl" || mnem == "br" || mnem == "blr" || mnem == "ret" ||
        mnem == "cbz" || mnem == "cbnz" || mnem == "tbz" || mnem == "tbnz" ||
        mnem.startsWith("b.") || mnem.startsWith("beq") || mnem.startsWith("bne") ||
        mnem.startsWith("bgt") || mnem.startsWith("blt") || mnem.startsWith("bge") ||
        mnem.startsWith("ble") || mnem.startsWith("bhi") || mnem.startsWith("bls") ||
        mnem.startsWith("bcs") || mnem.startsWith("bcc") || mnem.startsWith("bmi") ||
        mnem.startsWith("bpl") || mnem.startsWith("bvs") || mnem.startsWith("bvc")

private fun isCondBranch(mnem: String): Boolean =
    mnem in setOf("cbz", "cbnz", "tbz", "tbnz") || (mnem.startsWith("b") && mnem != "b" && mnem != "bl" && mnem != "br" && mnem != "blr")

private fun asmToFlowChart(lines: List<AsmLine>, zh: Boolean): String {
    if (lines.isEmpty()) return ""
    // 按「分支指令作为块结尾」切分基本块
    data class Block(val label: String, val insns: List<AsmLine>)
    val blocks = mutableListOf<Block>()
    var cur = mutableListOf<AsmLine>()
    var idx = 0
    lines.forEach { l ->
        cur.add(l)
        if (isBranch(l.mnem)) {
            blocks.add(Block("B" + idx, cur)); idx++; cur = mutableListOf()
        }
    }
    if (cur.isNotEmpty()) { blocks.add(Block("B" + idx, cur)); idx++ }

    val sb = StringBuilder()
    sb.append("// ${if (zh) "按基本块切分的 ASCII 流程图" else "ASCII flowchart by basic block"}（${blocks.size} blocks）\n\n")
    val boxW = 52
    val top = "┌" + "─".repeat(boxW) + "┐"
    val bot = "└" + "─".repeat(boxW) + "┘"
    blocks.forEachIndexed { i, b ->
        val head = " ${b.label}  @ ${b.insns.firstOrNull()?.addr?.ifBlank { "—" } ?: "—"}"
        sb.append(top).append('\n')
        sb.append("│").append(head.take(boxW).padEnd(boxW)).append("│\n")
        sb.append("├").append("─".repeat(boxW)).append("┤\n")
        b.insns.forEach { ins ->
            val txt = " ${ins.addr.ifBlank { "" }} ${ins.mnem} ${ins.ops}".trim()
            sb.append("│").append(txt.take(boxW).padEnd(boxW)).append("│\n")
        }
        sb.append(bot).append('\n')
        val last = b.insns.lastOrNull()
        if (i < blocks.size - 1) {
            val label = when {
                last == null -> ""
                last.mnem in setOf("cbz", "cbnz", "tbz", "tbnz") -> {
                    val regs = last.ops.split(",").map { it.trim() }
                    if (zh) "条件(${regs.getOrNull(0) ?: "?"}) 成立 → ${regs.getOrNull(1) ?: "?"}"
                    else "cond(${regs.getOrNull(0) ?: "?"}) true → ${regs.getOrNull(1) ?: "?"}"
                }
                isCondBranch(last.mnem) -> (if (zh) "条件跳转 " else "branch ") + last.ops
                last.mnem == "b" -> (if (zh) "无条件跳转 " else "goto ") + last.ops
                last.mnem == "ret" -> if (zh) "返回" else "return"
                else -> (if (zh) "顺序执行" else "fallthrough")
            }
            sb.append("        │ ").append(label).append('\n')
            sb.append("        ▼\n")
        }
    }
    return sb.toString()
}

@Composable
private fun AsmToFlowChartView(zh: Boolean, context: android.content.Context) {
    var input by remember {
        mutableStateOf(
            "0x1000  mov x0, #0\n" +
                "0x1004  cmp x0, #5\n" +
                "0x1008  b.ge 0x1020\n" +
                "0x100c  add x0, x0, #1\n" +
                "0x1010  b 0x1004\n" +
                "0x1020  ret",
        )
    }
    val lines = remember(input) { parseAsmLines(input) }
    val flow = remember(input, zh) { asmToFlowChart(lines, zh) }

    ToolPageScaffold(
        title = if (zh) "汇编转流程图" else "Asm → Flowchart",
        hint = if (zh) "按基本块切分汇编，输出 ASCII 框图" else "Split asm into basic blocks → ASCII flowchart",
        actions = {
            SmallAction(if (zh) "清空" else "Clear", enabled = input.isNotBlank()) { input = "" }
            SmallAction(if (zh) "复制框图" else "Copy", enabled = flow.isNotBlank()) { copyToClipboard(context, flow, zh) }
            Text(
                if (zh) "${lines.count { isBranch(it.mnem) }} 个分支" else "${lines.count { isBranch(it.mnem) }} branches",
                style = MaterialTheme.typography.labelSmall,
                fontSize = AppText.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMonoField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (zh) "汇编（每行一条，建议带地址）" else "assembly (one per line, address recommended)",
                    placeholder = "0x1000  cmp x0, #5\n0x1004  b.eq 0x1010",
                    minHeight = 130.dp,
                )
                if (flow.isBlank()) {
                    AnalysisEmptyState(
                        title = if (zh) "汇编转流程图" else "Asm → Flowchart",
                        hint = if (zh) "粘贴一段汇编（建议带地址），按分支指令切分基本块并用 ASCII 框图呈现控制流。"
                            else "Paste assembly (address recommended); basic blocks are split on branch instructions and drawn as an ASCII flowchart.",
                    )
                } else {
                    ToolResultBlock(if (zh) "控制流框图" else "Control-flow chart", flow)
                }
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════════════════════
//  深度分析页（对齐 Exbin：虚表 / 交叉引用 / 地址查看器 / 函数签名 /
//  JNI 注册还原 / 加固·反调试报告）
//  数据来源：MCP 工具（taffy_so_vtable 等）+ rizin 只读命令（rzCommand）。
// ═══════════════════════════════════════════════════════════════════════════

/** 在 IO 线程调用一个塔菲 MCP 工具；失败/无 handler 返回 null。 */
private suspend fun callMcpTool(
    context: android.content.Context,
    name: String,
    args: JSONObject,
): JSONObject? = withContext(Dispatchers.IO) {
    runCatching {
        val h = com.soreverse.mcp.mcp.ToolCatalog.byName[name] ?: return@runCatching null
        val tcx = com.soreverse.mcp.mcp.ToolContext(
            context,
            com.soreverse.mcp.core.SettingsStore(context),
            EngineProvider.get(context),
            null,
        )
        h.handle(tcx, args)
    }.getOrNull()
}

/** 分析页通用“需要先打开 SO”占位。 */
@Composable
private fun NeedWorkspace(zh: Boolean) {
    AnalysisEmptyState(
        title = if (zh) "尚未打开 SO" else "No SO opened",
        hint = if (zh) "请先在「函数」页或顶部选择文件打开一个 ELF/.so，本页依赖工作区里的已解析数据。"
            else "Open an ELF/.so first (Functions page or the top bar); this page reads the opened workspace.",
    )
}

/** 类型徽章（返回类型 / 类型标签）。 */
@Composable
private fun TypeBadge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        fontSize = AppText.label,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 等宽可复制行。 */
@Composable
private fun MonoLine(text: String, color: Color = MaterialTheme.colorScheme.onSurface, size: androidx.compose.ui.unit.TextUnit = AppText.body) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        fontSize = size,
        color = color,
        lineHeight = 16.sp,
    )
}

// ───────────────────────── 1. 虚表（C++ vtable / RTTI） ─────────────────────────

@Composable
private fun VtableView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val tick = tools.reloadTick
    var data by remember(ws, tick) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(ws, tick) { mutableStateOf(false) }
    var error by remember(ws, tick) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var header by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(ws, tick) {
        if (ws.isBlank()) return@LaunchedEffect
        loading = true; error = ""
        val r = callMcpTool(context, "taffy_so_vtable",
            JSONObject().put("workspaceId", ws).put("action", "detail").put("limit", 300))
        loading = false
        data = r
        if (r == null) error = if (zh) "引擎未就绪（native 库未加载）" else "engine not ready"
        else if (!r.optBoolean("ok", true)) error = r.optString("error").ifBlank { r.optString("note") }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    val all = remember(data) {
        val a = data?.optJSONArray("vtables") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    val shown = remember(all, query) {
        if (query.isBlank()) all else all.filter {
            it.optString("className").contains(query, true) || it.optString("vtableAddr").contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallAction(if (zh) "刷新" else "Refresh", loading = loading, onClick = onRefresh)
            SmallAction(
                if (zh) "生成头文件" else "Header",
                enabled = all.isNotEmpty(), loading = exporting,
                onClick = {
                    scope.launch {
                        exporting = true
                        val r = callMcpTool(context, "taffy_so_vtable",
                            JSONObject().put("workspaceId", ws).put("action", "export")
                                .put("className", query).put("limit", 100))
                        exporting = false
                        header = r?.optString("header").orEmpty()
                        if (header.isBlank()) header = if (zh) "// 未生成内容（无虚表或引擎无输出）" else "// nothing generated"
                    }
                },
            )
            if (all.isNotEmpty()) {
                SmallAction(if (zh) "复制全部" else "Copy all", onClick = {
                    val sb = StringBuilder()
                    all.forEach { v ->
                        sb.append(v.optString("className")).append("  @ ").append(v.optString("vtableAddr"))
                            .append("  slots=").append(v.optInt("slotCount")).append('\n')
                        v.optJSONArray("slots")?.let { s ->
                            for (i in 0 until s.length()) {
                                val o = s.optJSONObject(i) ?: continue
                                sb.append("    #").append(o.optInt("index")).append(' ').append(o.optString("name"))
                                    .append("  ").append(o.optString("addr")).append('\n')
                            }
                        }
                    }
                    copyToClipboard(context, sb.toString().trimEnd(), zh)
                })
                Text(
                    if (zh) "${all.size} 个虚表" else "${all.size} vtables",
                    style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(15.dp), tint = cs.onSurfaceVariant) },
            placeholder = { Text(if (zh) "过滤类名 / 虚表地址" else "filter class / vtable addr", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.label), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        Spacer(Modifier.size(8.dp))
        when {
            loading && data == null -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            all.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "未发现虚表" else "No vtables",
                hint = if (zh) "该 SO 可能未使用 C++ 虚函数，或 RTTI 已被 strip / 编译时关闭（-fno-rtti）。底层为 rizin avj。"
                    else "No C++ vtables/RTTI found (stripped or -fno-rtti). Backed by rizin avj.",
                primaryLabel = if (zh) "重新分析" else "Re-analyze", onPrimary = onRefresh,
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (header.isNotBlank()) {
                    ToolResultBlock(if (zh) "生成的 vtable 头文件" else "Generated vtable header", header, zh = zh,
                        onCopy = { copyToClipboard(context, header, zh) })
                }
                shown.forEach { v ->
                    val cls = v.optString("className").ifBlank { "(未命名类)" }
                    val vAddr = v.optString("vtableAddr")
                    val slots = v.optJSONArray("slots") ?: JSONArray()
                    val key = vAddr.ifBlank { cls }
                    val isOpen = key in expanded
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(AppShape.md))
                            .background(cs.surfaceContainerHigh)
                            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (isOpen) expanded - key else expanded + key },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Text(cls, style = MaterialTheme.typography.bodySmall, fontSize = AppText.bodyStrong,
                                fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TypeBadge("${slots.length()}", cs.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MonoLine("vtable $vAddr", cs.onSurfaceVariant, AppText.label)
                            if (v.optString("typeinfoAddr").isNotBlank()) MonoLine("rtti ${v.optString("typeinfoAddr")}", cs.onSurfaceVariant, AppText.label)
                            if (v.optString("confidence").isNotBlank()) MonoLine("conf ${v.optString("confidence")}", cs.onSurfaceVariant, AppText.label)
                        }
                        if (isOpen) {
                            GroupDivider()
                            for (i in 0 until slots.length()) {
                                val s = slots.optJSONObject(i) ?: continue
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MonoLine("#${s.optInt("index")}", cs.onSurfaceVariant, AppText.label, )
                                    MonoLine(s.optString("addr"), cs.primary, AppText.label)
                                    Text(s.optString("name").ifBlank { "slot_${s.optInt("index")}" },
                                        style = MaterialTheme.typography.bodySmall, fontSize = AppText.label,
                                        color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 2. 交叉引用（含方向） ─────────────────────────

@Composable
private fun XRefsView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    var locator by remember { mutableStateOf("") }
    var inRefs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var outRefs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var ran by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ws) {
        if (locator.isBlank()) locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
    }

    fun go() {
        val loc = locator.trim()
        if (loc.isBlank() || ws.isBlank()) return
        scope.launch {
            loading = true; error = ""; ran = true
            val rs = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = EngineProvider.get(context)
                    val axt = eng.rzCommand(ws, "", "s $loc; axtj")
                    val axf = eng.rzCommand(ws, "", "s $loc; axfj")
                    parseRzArray(axt) to parseRzArray(axf)
                }.getOrElse { (null as List<JSONObject>?) to (null as List<JSONObject>?) }
            }
            loading = false
            val (a, b) = rs
            if (a == null && b == null) error = if (zh) "定位失败：请检查符号名或地址（支持 0x… / 函数名）" else "failed to locate symbol/address"
            inRefs = a ?: emptyList()
            outRefs = b ?: emptyList()
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "分析" else "Analyze", loading = loading, enabled = locator.isNotBlank(), onClick = { go() })
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionVa.isNotBlank() || tools.selectedFunctionName.isNotBlank()) {
                locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
            SmallAction(if (zh) "导出 CSV" else "CSV", enabled = inRefs.isNotEmpty() || outRefs.isNotEmpty()) {
                val sb = StringBuilder("dir,from,to,type,opcode\n")
                inRefs.forEach { sb.append("in,").append(it.optString("from")).append(',').append(locator.trim()).append(',').append(it.optString("type")).append(',').append(it.optString("opcode")).append('\n') }
                outRefs.forEach { sb.append("out,").append(locator.trim()).append(',').append(it.optString("to")).append(',').append(it.optString("type")).append(',').append(it.optString("opcode")).append('\n') }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            Text(
                if (zh) "入 ${inRefs.size} · 出 ${outRefs.size}" else "in ${inRefs.size} · out ${outRefs.size}",
                style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = locator, onValueChange = { locator = it }, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "函数名 / 地址" else "symbol / addr", fontSize = AppText.label) },
                placeholder = { Text("JNI_OnLoad 或 0x1234", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
            )
        }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            !ran -> AnalysisEmptyState(
                title = if (zh) "交叉引用" else "Cross references",
                hint = if (zh) "输入函数名或地址后点「分析」，得到该位置的入引用（谁调用/引用它）与出引用（它引用了谁）。"
                    else "Enter a symbol or address, then Analyze, to list incoming and outgoing references.",
                primaryLabel = if (zh) "取当前函数" else "Current fn",
                onPrimary = { locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }; go() },
            )
            inRefs.isEmpty() && outRefs.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "无交叉引用" else "No references",
                hint = if (zh) "该位置没有显式引用记录（可能是叶子函数，或未被 rizin 分析到）。"
                    else "No references recorded here (leaf, or not analyzed by rizin).",
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RefBlock(if (zh) "入引用（谁引用它）" else "Incoming", inRefs, "from", cs.primary, zh, context)
                RefBlock(if (zh) "出引用（它引用谁）" else "Outgoing", outRefs, "to", cs.tertiary, zh, context)
            }
        }
    }
}

/** axtj/axfj 的 JSON 数组解析（rzCommand 返回体里取 stdout）。 */
private fun parseRzArray(res: JSONObject?): List<JSONObject>? {
    if (res == null) return null
    val out = res.optString("stdout").ifBlank { res.optString("text") }.trim()
    if (out.isBlank()) return emptyList()
    return runCatching {
        val a = JSONArray(out)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrNull()
}

@Composable
private fun RefBlock(
    title: String,
    refs: List<JSONObject>,
    peerKey: String,
    color: Color,
    zh: Boolean,
    context: android.content.Context,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (refs.isEmpty()) MonoLine(if (zh) "（无）" else "(none)", cs.onSurfaceVariant, AppText.label)
            refs.take(300).forEach { r ->
                Column(Modifier.fillMaxWidth().clickable {
                    val p = r.optString(peerKey)
                    if (p.isNotBlank()) copyToClipboard(context, p, zh)
                }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonoLine(r.optString(peerKey), color, AppText.label)
                        val ty = r.optString("type")
                        if (ty.isNotBlank()) TypeBadge(ty, cs.onSurfaceVariant)
                    }
                    val op = r.optString("opcode")
                    if (op.isNotBlank()) MonoLine(op, cs.onSurfaceVariant, AppText.label)
                }
            }
            if (refs.size > 300) MonoLine(if (zh) "… 仅显示前 300 条" else "… showing first 300", cs.onSurfaceVariant, AppText.label)
        }
    }
}

// ───────────────────────── 3. 地址查看器（代码 / 数据 双模式） ─────────────────────────

@Composable
private fun AddrViewerView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    var addr by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("code") }
    var lines by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ws) {
        if (addr.isBlank()) addr = tools.selectedFunctionVa.ifBlank { tools.disasmAddr }
    }

    fun load() {
        val a = addr.trim()
        if (a.isBlank() || ws.isBlank()) return
        scope.launch {
            loading = true; error = ""; info = ""
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = EngineProvider.get(context)
                    val cmd = if (mode == "code") "s $a; pdj 64" else "s $a; pxj 256"
                    val sec = eng.rzCommand(ws, "", "iSj")
                    parseRzArray(eng.rzCommand(ws, "", cmd)) to sectionOf(sec, a)
                }.getOrElse { (null as List<JSONObject>?) to "" }
            }
            loading = false
            val (l, s) = res
            if (l == null) error = if (zh) "读取失败：地址无法解析（试试 0x 开头的虚拟地址）" else "failed to read at address"
            lines = l ?: emptyList()
            info = s
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "代码模式" else "Code", active = mode == "code") { mode = "code"; if (lines.isNotEmpty()) load() }
            SmallAction(if (zh) "数据模式" else "Data", active = mode == "data") { mode = "data"; if (lines.isNotEmpty()) load() }
            SmallAction(if (zh) "前往" else "Go", loading = loading, enabled = addr.isNotBlank(), onClick = { load() })
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionVa.isNotBlank()) { addr = tools.selectedFunctionVa }
            SmallAction(if (zh) "复制" else "Copy", enabled = lines.isNotEmpty()) {
                val sb = StringBuilder()
                lines.forEach { o -> sb.append(if (mode == "code") "${o.optString("offset")}  ${o.optString("bytes")}  ${o.optString("disasm")}" else "${o.optString("offset")}  ${o.optString("bytes")}  ${o.optString("ascii")}").append('\n') }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = addr, onValueChange = { addr = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            label = { Text(if (zh) "前往地址（0x… 虚拟地址）" else "go to address", fontSize = AppText.label) },
            placeholder = { Text("0x1234", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
        )
        if (info.isNotBlank()) {
            Spacer(Modifier.size(6.dp))
            MonoLine(info, cs.primary, AppText.label)
        }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            lines.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "地址查看器" else "Address viewer",
                hint = if (zh) "输入虚拟地址，用「代码模式」看反汇编、用「数据模式」看 hex+ASCII；上方显示所在节区与地址范围。"
                    else "Enter a VA: Code mode disassembles, Data mode shows hex+ASCII; the section is shown above.",
                primaryLabel = if (zh) "取当前函数" else "Current fn", onPrimary = { addr = tools.selectedFunctionVa; load() },
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { o ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        MonoLine(o.optString("offset"), cs.primary, AppText.label)
                        if (mode == "code") {
                            MonoLine(o.optString("bytes"), cs.onSurfaceVariant, AppText.label)
                            Text(o.optString("disasm"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.body, color = cs.onSurface, modifier = Modifier.weight(1f), lineHeight = 16.sp)
                        } else {
                            Text(o.optString("bytes"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.body, color = cs.onSurface, modifier = Modifier.weight(1f), lineHeight = 16.sp)
                            MonoLine(o.optString("ascii"), cs.onSurfaceVariant, AppText.body)
                        }
                    }
                }
            }
        }
    }
}

/** 从 iSj 输出里找出包含给定地址的节区，返回 "addr 节区(范围)"。 */
private fun sectionOf(res: JSONObject?, addr: String): String {
    if (res == null) return ""
    val out = res.optString("stdout").ifBlank { res.optString("text") }.trim()
    if (out.isBlank()) return ""
    val want = parseHex64(addr) ?: return ""
    val arr = runCatching { JSONArray(out) }.getOrNull() ?: return ""
    for (i in 0 until arr.length()) {
        val s = arr.optJSONObject(i) ?: continue
        val vaddr = s.opt("vaddr")?.let { hexOrLong(it) } ?: continue
        val vsize = s.opt("vsize")?.let { hexOrLong(it) } ?: 0L
        if (want >= vaddr && want < vaddr + vsize) {
            val name = s.optString("name")
            val range = "0x" + java.lang.Long.toHexString(vaddr) + "–0x" + java.lang.Long.toHexString(vaddr + vsize)
            return "$name  $range"
        }
    }
    return ""
}

private fun parseHex64(s: String): Long? {
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return runCatching { java.lang.Long.parseLong(t, 16) }.getOrNull()
}

private fun hexOrLong(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    is String -> parseHex64(v)
    else -> null
}

// ───────────────────────── 4. 函数签名还原 ─────────────────────────

@Composable
private fun FuncSigView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    var locator by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("12") }
    var sigs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var ran by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ws) {
        if (locator.isBlank()) locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
    }

    fun go() {
        if (ws.isBlank()) return
        scope.launch {
            loading = true; error = ""; ran = true
            val r = callMcpTool(context, "taffy_so_func_sig",
                JSONObject().put("workspaceId", ws).put("locator", locator.trim())
                    .put("count", count.toIntOrNull()?.coerceIn(1, 50) ?: 12))
            loading = false
            if (r == null) { error = if (zh) "引擎未就绪" else "engine not ready" }
            else if (!r.optBoolean("ok", true)) error = r.optString("error").ifBlank { r.optString("note") }
            else {
                val a = r.optJSONArray("signatures") ?: JSONArray()
                sigs = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                if (sigs.isEmpty()) error = r.optString("note").ifBlank { if (zh) "未还原出签名" else "no signatures" }
            }
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "还原" else "Recover", loading = loading, onClick = { go() })
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionVa.isNotBlank() || tools.selectedFunctionName.isNotBlank()) {
                locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
            }
            SmallAction(if (zh) "复制全部" else "Copy all", enabled = sigs.isNotEmpty()) {
                val sb = StringBuilder()
                sigs.forEach { s -> sb.append(sigLine(s, zh)).append('\n') }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = locator, onValueChange = { locator = it }, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "函数（留空=批量）" else "fn (blank=batch)", fontSize = AppText.label) },
            )
            OutlinedTextField(
                value = count, onValueChange = { count = it }, singleLine = true,
                modifier = Modifier.width(84.dp).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "个数" else "count", fontSize = AppText.label) },
            )
        }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() && sigs.isEmpty() -> AnalysisErrorBanner(error)
            sigs.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "函数签名还原" else "Signature recovery",
                hint = if (zh) "还原函数的参数列表（基于 rizin 的 afvj 局部变量/参数寄存器启发式）。留空函数名可批量还原前 N 个函数。"
                    else "Recover argument lists heuristically from rizin afvj. Leave the name blank to batch the first N functions.",
                primaryLabel = if (zh) "批量还原" else "Batch", onPrimary = { locator = ""; go() },
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sigs.forEach { s -> SigCard(s, zh, context, cs) }
            }
        }
    }
}

private fun sigLine(s: JSONObject, zh: Boolean): String {
    val args = s.optJSONArray("args") ?: JSONArray()
    val params = (0 until args.length()).joinToString(", ") { i ->
        val a = args.optJSONObject(i) ?: return@joinToString ""
        (a.optString("type").ifBlank { "?" }) + " " + (a.optString("name").ifBlank { a.optString("reg") })
    }
    return "${s.optString("addr")}  ${s.optString("returnType", "unknown")} ${s.optString("function")}($params)   [${s.optString("confidence")}]"
}

@Composable
private fun SigCard(s: JSONObject, zh: Boolean, context: android.content.Context, cs: androidx.compose.material3.ColorScheme) {
    val args = s.optJSONArray("args") ?: JSONArray()
    val locals = s.optJSONArray("locals") ?: JSONArray()
    val conf = s.optString("confidence")
    val confColor = when (conf) { "medium" -> cs.primary; "low" -> cs.onSurfaceVariant; else -> cs.outline }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.optString("function"), style = MaterialTheme.typography.bodySmall, fontSize = AppText.bodyStrong,
                fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            MonoLine(s.optString("addr"), cs.primary, AppText.label)
            TypeBadge(conf.ifBlank { "?" }, confColor)
        }
        MonoLine(sigLine(s, zh), cs.onSurface, AppText.label)
        if (args.length() > 0) {
            GroupDivider()
            Text(if (zh) "参数" else "args", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
            for (i in 0 until args.length()) {
                val a = args.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoLine("#${i + 1}", cs.onSurfaceVariant, AppText.label)
                    MonoLine(a.optString("reg"), cs.primary, AppText.label)
                    MonoLine(a.optString("type"), cs.onSurfaceVariant, AppText.label)
                    Text(a.optString("name").ifBlank { a.optString("reg") }, style = MaterialTheme.typography.bodySmall,
                        fontSize = AppText.label, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (locals.length() > 0) {
            MonoLine(if (zh) "局部变量 ${locals.length()} 个" else "${locals.length()} locals", cs.onSurfaceVariant, AppText.label)
        }
        val note = s.optString("typeConfidenceNote")
        if (note.isNotBlank()) MonoLine(note, cs.onSurfaceVariant.copy(alpha = 0.8f), AppText.label)
    }
}

// ───────────────────────── 5. JNI RegisterNatives 还原 ─────────────────────────

@Composable
private fun JniRegView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val tick = tools.reloadTick
    var data by remember(ws, tick) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(ws, tick) { mutableStateOf(false) }
    var error by remember(ws, tick) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(ws, tick) {
        if (ws.isBlank()) return@LaunchedEffect
        loading = true; error = ""
        val r = callMcpTool(context, "taffy_so_jni_reg", JSONObject().put("workspaceId", ws).put("limit", 800))
        loading = false
        data = r
        if (r == null) error = if (zh) "引擎未就绪" else "engine not ready"
        else if (!r.optBoolean("ok", true)) error = r.optString("error").ifBlank { r.optString("note") }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    val pairs = remember(data) {
        val a = data?.optJSONArray("pairs") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    val shown = remember(pairs, query) {
        if (query.isBlank()) pairs else pairs.filter {
            it.optString("javaName").contains(query, true) || it.optString("signature").contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "刷新" else "Refresh", loading = loading, onClick = onRefresh)
            SmallAction(if (zh) "复制全部" else "Copy all", enabled = pairs.isNotEmpty()) {
                val sb = StringBuilder()
                pairs.forEach { p -> sb.append(p.optString("javaName")).append(' ').append(p.optString("signature")).append("  @").append(p.optString("sigAddr")).append('\n') }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            if (data != null) {
                val onLoad = data!!.optBoolean("hasJniOnLoad", false)
                val reg = data!!.optBoolean("hasRegisterNatives", false)
                TypeBadge(if (onLoad) "JNI_OnLoad ✓" else "JNI_OnLoad ✗", if (onLoad) cs.primary else cs.outline)
                TypeBadge(if (reg) "RegisterNatives ✓" else "RegisterNatives ✗", if (reg) cs.primary else cs.outline)
            }
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(15.dp), tint = cs.onSurfaceVariant) },
            placeholder = { Text(if (zh) "过滤 Java 方法名 / 签名" else "filter method / signature", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.label), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        Spacer(Modifier.size(8.dp))
        when {
            loading && data == null -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            pairs.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "未发现 JNI 方法表" else "No JNI table",
                hint = if (zh) "未在字符串中扫到 JNI 方法描述符（形如 (Landroid/…;)V）。可能是静态注册（Java_xxx 符号）或已加密。"
                    else "No JNI descriptors found in strings — may be statically registered or encrypted.",
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoLine(if (zh) "${shown.size} / ${pairs.size} 条配对" else "${shown.size} / ${pairs.size} pairs", cs.onSurfaceVariant, AppText.label)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHigh)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    shown.take(600).forEach { p ->
                        Column(Modifier.fillMaxWidth().clickable {
                            copyToClipboard(context, p.optString("javaName") + " " + p.optString("signature"), zh)
                        }, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.optString("javaName").ifBlank { "(未命名)" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    fontSize = AppText.bodyStrong, color = cs.onSurface, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MonoLine(p.optString("sigAddr"), cs.primary, AppText.label)
                                TypeBadge(p.optString("confidence").ifBlank { "?" },
                                    if (p.optString("confidence") == "medium") cs.primary else cs.onSurfaceVariant)
                            }
                            MonoLine(p.optString("signature"), cs.onSurfaceVariant, AppText.label)
                        }
                    }
                    if (shown.size > 600) MonoLine(if (zh) "… 仅显示前 600 条" else "… first 600", cs.onSurfaceVariant, AppText.label)
                }
            }
        }
    }
}

// ───────────────────────── 6. 加固 / 反调试 报告 ─────────────────────────

private data class HardFinding(val category: String, val title: String, val detail: String, val severity: String)

/** 壳 / 加固厂商特征（文件名/字符串级）。 */
private val packerSignatures: List<Triple<String, String, String>> = listOf(
    Triple("libjiagu", "360 加固保", "high"),
    Triple("libmobisec", "阿里聚安全", "high"),
    Triple("libsgmain", "阿里 SecurityGuard", "high"),
    Triple("libsgsecuritybody", "阿里 SecurityGuard", "high"),
    Triple("libDexHelper", "梆梆企业版", "high"),
    Triple("libijiami", "爱加密", "high"),
    Triple("libexecmain", "爱加密", "high"),
    Triple("libshella", "通用加固壳", "medium"),
    Triple("libshellx", "通用加固壳", "medium"),
    Triple("libnesec", "网易易盾", "high"),
    Triple("libSecShell", "娜迦", "high"),
    Triple("libsecexe", "娜迦", "high"),
    Triple("libsecmain", "娜迦", "high"),
    Triple("libtprt", "腾讯乐固/梆梆", "high"),
    Triple("libreloc", "腾讯乐固", "medium"),
    Triple("libbaiduprotect", "百度加固", "high"),
    Triple("libbprotect", "百度加固", "high"),
    Triple("libkwscmm", "几维安全", "medium"),
    Triple("libkwscr", "几维安全", "medium"),
    Triple("libVMProtect", "VMProtect", "high"),
    Triple("libedog", "易遁加固", "medium"),
)

/** 运行环境检测 / 混淆标记特征。 */
private val envSignatures: List<Pair<String, String>> = listOf(
    "frida" to "Frida 检测（反调试/反注入）",
    "gum-js-loop" to "Frida 线程检测",
    "re.frida.server" to "Frida server 检测",
    "xposed" to "Xposed 检测",
    "substrate" to "Substrate 检测",
    "magisk" to "Magisk / Root 检测",
    "/system/bin/su" to "Root 检测（su 路径）",
    "/system/xbin/su" to "Root 检测（su 路径）",
    "goldfish" to "模拟器检测（goldfish）",
    "qemu" to "模拟器检测（qemu）",
    "ranchu" to "模拟器检测（ranchu）",
    "/proc/self/status" to "反调试（读取 TracerPid）",
    "TracerPid" to "反调试（TracerPid）",
    "ptrace" to "反调试（ptrace）",
    "ollvm" to "OLLVM 混淆标记",
    "__vmp" to "VMP 虚拟化保护标记",
    "VMHandler" to "VMP Handler 标记",
    "soinfo" to "自定义 Linker（soinfo）",
    "linker_init" to "自定义 Linker（linker_init）",
    "__dl_" to "自定义 Linker（__dl_ 符号）",
)

/** 基于字符串集合做特征匹配。 */
private fun hardMatch(strings: List<String>): List<HardFinding> {
    val out = mutableListOf<HardFinding>()
    packerSignatures.forEach { (sig, name, sev) ->
        val ev = strings.firstOrNull { it.contains(sig, true) }
        if (ev != null) out += HardFinding("加固壳", name, "命中特征「$sig」：${ev.take(160)}", sev)
    }
    envSignatures.forEach { (sig, desc) ->
        val ev = strings.firstOrNull { it.contains(sig, true) }
        if (ev != null) {
            val sev = if (desc.contains("Frida") || desc.contains("反调试")) "high" else "medium"
            out += HardFinding("反调试/环境", desc, "命中「$sig」：${ev.take(160)}", sev)
        }
    }
    if (strings.any { it.matches(Regex("^sub_[0-9A-Fa-f]{4,}$")) }) {
        out += HardFinding("符号剥离", "符号剥离 (sub_ 命名)", "大量函数名为 sub_xxxx，符号表可能被剥离。", "low")
    }
    return out
}

/** Shannon 熵（比特/字节）。 */
private fun shannonEntropy(bytes: ByteArray): Double {
    if (bytes.isEmpty()) return 0.0
    val freq = IntArray(256)
    bytes.forEach { freq[it.toInt() and 0xff]++ }
    var h = 0.0
    val n = bytes.size.toDouble()
    freq.forEach { c -> if (c > 0) { val p = c / n; h -= p * kotlin.math.log2(p) } }
    return h
}

@Composable
private fun HardeningView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val tick = tools.reloadTick
    val cs = MaterialTheme.colorScheme
    var findings by remember(ws, tick) { mutableStateOf<List<HardFinding>>(emptyList()) }
    var entropy by remember(ws, tick) { mutableStateOf(-1.0) }
    var textCount by remember(ws, tick) { mutableStateOf(0) }
    var loading by remember(ws, tick) { mutableStateOf(false) }
    var error by remember(ws, tick) { mutableStateOf("") }
    var ran by remember(ws, tick) { mutableStateOf(false) }

    LaunchedEffect(ws, tick) {
        if (ws.isBlank()) return@LaunchedEffect
        loading = true; error = ""; ran = true
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val eng = EngineProvider.get(context)
                // 1) 字符串清单（quiet，逐行；限流避免大 SO 内存爆）
                val strRes = eng.rzCommand(ws, "", "izq")
                val txt = strRes.optString("stdout").ifBlank { strRes.optString("text") }
                val strs = txt.split('\n').map { it.trim() }.filter { it.length in 3..300 }.take(50000)
                // 2) .text 头部 4KB 熵
                val hexRes = eng.rzCommand(ws, "", "p8 4096 @ .text")
                val hx = hexRes.optString("stdout").ifBlank { hexRes.optString("text") }.trim()
                val bytes = runCatching {
                    val t = hx.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                    ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                }.getOrNull() ?: ByteArray(0)
                Triple(strs, shannonEntropy(bytes), bytes.size)
            }.getOrNull()
        }
        loading = false
        if (res == null) { error = if (zh) "扫描失败（工作区或引擎异常）" else "scan failed" ; return@LaunchedEffect }
        val (strs, ent, n) = res
        textCount = strs.size
        entropy = if (n > 0) ent else -1.0
        findings = hardMatch(strs)
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "重新扫描" else "Rescan", loading = loading, onClick = onRefresh)
            SmallAction(if (zh) "复制报告" else "Copy report", enabled = ran) {
                copyToClipboard(context, hardReport(findings, entropy, textCount, zh), zh)
            }
            if (entropy >= 0) {
                val hi = entropy >= 7.0
                TypeBadge(".text 熵 %.2f".format(entropy), if (hi) cs.error else cs.primary)
            }
        }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueCard(zh, listOf(
                    (if (zh) "字符串数" else "strings") to "$textCount",
                    (if (zh) "命中项" else "findings") to "${findings.size}",
                    ("\u200b.text 熵") to if (entropy < 0) "—" else "%.3f / 8.0".format(entropy),
                ))
                if (entropy >= 7.0) {
                    AnalysisErrorBanner(if (zh) "代码段熵值异常偏高（%.2f，接近 8.0），高度疑似加密或压缩——磁盘静态数据可能不是真实指令，通常需要内存 Dump。" else "Very high .text entropy — likely encrypted/compressed; memory dump may be required.")
                }
                if (findings.isEmpty()) {
                    AnalysisEmptyState(
                        title = if (zh) "未检出常见加固特征" else "No hardening found",
                        hint = if (zh) "未命中已知壳厂商 / 反调试 / 混淆标记。若确为加固目标，可结合 taffy_apk_shell_check（APK 级）与动态分析确认。"
                            else "No known packer/anti-debug/obfuscation fingerprints. Cross-check with taffy_apk_shell_check for APKs.",
                    )
                } else {
                    val byCat = findings.groupBy { it.category }
                    byCat.forEach { (cat, list) ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("$cat · ${list.size}", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                            list.forEach { f ->
                                val col = when (f.severity) { "high" -> cs.error; "medium" -> cs.tertiary; else -> cs.onSurfaceVariant }
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(AppShape.md))
                                        .background(cs.surfaceContainerHigh)
                                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(f.title, style = MaterialTheme.typography.bodySmall, fontSize = AppText.bodyStrong, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
                                        TypeBadge(f.severity, col)
                                    }
                                    MonoLine(f.detail, cs.onSurfaceVariant, AppText.label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hardReport(findings: List<HardFinding>, entropy: Double, strings: Int, zh: Boolean): String {
    val sb = StringBuilder()
    sb.append("=== ${if (zh) "加固 / 混淆分析报告" else "Hardening report"} ===\n")
    sb.append("${if (zh) "字符串数" else "strings"}: $strings\n")
    sb.append(".text entropy: ").append(if (entropy < 0) "n/a" else "%.3f".format(entropy)).append("\n")
    sb.append("${if (zh) "命中" else "findings"}: ${findings.size}\n\n")
    findings.forEach { sb.append("- [").append(it.severity).append("] ").append(it.category).append(" / ").append(it.title).append('\n').append("    ").append(it.detail).append('\n') }
    if (findings.isEmpty()) sb.append(if (zh) "（未检出常见特征）\n" else "(none)\n")
    return sb.toString()
}

// ═══════════════════════════════════════════════════════════════════════════
//  全局调用图 + 导出中心
//  对齐 Exbin §2.3 GlobalCfgView（全局调用图）与 §5.3 导出能力。
// ═══════════════════════════════════════════════════════════════════════════

/** rzCommand 的 stdout/text 取正文。 */
private fun rzText(res: JSONObject?): String =
    if (res == null) "" else res.optString("stdout").ifBlank { res.optString("text") }.trim()

/** 取 JSON 数组（来自 rzCommand 文本）。 */
private fun rzArray(res: JSONObject?): List<JSONObject> {
    val t = rzText(res)
    if (t.isBlank()) return emptyList()
    val a = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

/** 数字（hex 字符串或 int）→ Long。 */
private fun numOf(v: Any?): Long = when (v) {
    is Number -> v.toLong()
    is String -> runCatching {
        if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
    }.getOrNull() ?: 0L
    else -> 0L
}

private fun hexAddr(v: Any?): String {
    val n = numOf(v)
    return if (n <= 0) "" else "0x" + java.lang.Long.toHexString(n)
}

// ───────────────────────── 全局调用图 ─────────────────────────

@Composable
private fun CallGraphView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val tick = tools.reloadTick
    val cs = MaterialTheme.colorScheme
    var nodes by remember(ws, tick) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var edges by remember(ws, tick) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember(ws, tick) { mutableStateOf(false) }
    var error by remember(ws, tick) { mutableStateOf("") }
    var note by remember(ws, tick) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sccMode by remember { mutableStateOf(false) }

    LaunchedEffect(ws, tick) {
        if (ws.isBlank()) return@LaunchedEffect
        loading = true; error = ""; note = ""
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val eng = EngineProvider.get(context)
                val g = rzArray(eng.rzCommand(ws, "", "agCj"))
                if (g.isNotEmpty()) Triple(g, emptyList<Pair<String, String>>(), "")
                else {
                    // 降级：函数列表（含各自规模），无全局边
                    val fns = rzArray(eng.rzCommand(ws, "", "aflj"))
                    Triple(fns, emptyList(), if (fns.isEmpty())
                        (if (zh) "rizin 未返回调用图（agC 不可用或未分析），也无法列出函数" else "no call graph / functions from rizin")
                    else (if (zh) "全局调用图 (agC) 不可用，已降级为函数清单" else "agC unavailable; fell back to function list"))
                }
            }.getOrNull()
        }
        loading = false
        if (res == null) { error = if (zh) "引擎未就绪或命令失败" else "engine/command failed"; return@LaunchedEffect }
        val (n, _, nt) = res
        val (parsedNodes, parsedEdges) = parseCallGraph(n)
        nodes = parsedNodes
        edges = parsedEdges
        note = nt
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    val indeg = remember(edges) {
        edges.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }.take(5)
    }
    val shown = remember(nodes, query) {
        if (query.isBlank()) nodes else nodes.filter {
            it.optString("name").contains(query, true) || it.optString("id").contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "刷新" else "Refresh", loading = loading, onClick = onRefresh)
            SmallAction(if (zh) "复制节点" else "Copy nodes", enabled = nodes.isNotEmpty()) {
                val sb = StringBuilder()
                nodes.forEach { n -> sb.append(n.optString("name").ifBlank { n.optString("id") }).append('\t').append(hexAddr(n.opt("offset") ?: n.opt("id"))).append('\n') }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            SmallAction(if (zh) "复制边" else "Copy edges", enabled = edges.isNotEmpty()) {
                copyToClipboard(context, edges.joinToString("\n") { "${it.first} -> ${it.second}" }, zh)
            }
            SmallAction("SCC", active = sccMode, enabled = nodes.isNotEmpty()) { sccMode = !sccMode }
            SmallAction(if (zh) "导出 JSON" else "JSON", enabled = nodes.isNotEmpty()) {
                val o = JSONObject()
                o.put("nodes", JSONArray(nodes.map { it.toString() }))
                o.put("edges", JSONArray(edges.map { JSONObject().put("from", it.first).put("to", it.second) }))
                copyToClipboard(context, o.toString(2), zh)
            }
            Text(if (zh) "${nodes.size} 节点 · ${edges.size} 边" else "${nodes.size} nodes · ${edges.size} edges",
                style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(15.dp), tint = cs.onSurfaceVariant) },
            placeholder = { Text(if (zh) "过滤函数名" else "filter function", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.label), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        if (note.isNotBlank()) { Spacer(Modifier.size(6.dp)); MonoLine(note, cs.onSurfaceVariant, AppText.label) }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            nodes.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "无调用图数据" else "No call graph",
                hint = if (zh) "rizin 未返回全局调用图。可先在「工具」页跑一次全量分析（aaaa），或改用「CFG」页看单函数控制流。"
                    else "rizin returned no call graph. Run full analysis (aaaa) first, or use the CFG page for per-function control flow.",
                primaryLabel = if (zh) "重新分析" else "Re-analyze", onPrimary = onRefresh,
            )
            sccMode -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sccs = remember(nodes, edges) {
                    if (nodes.isEmpty()) emptyList()
                    else tarjanScc(nodes.map { it.optString("name").ifBlank { it.optString("id") } }, edges)
                        .sortedByDescending { it.size }
                }
                val cyclic = sccs.filter { it.size > 1 }
                KeyValueCard(zh, listOf(
                    (if (zh) "强连通分量" else "SCCs") to "${sccs.size}",
                    (if (zh) "环状簇(>1)" else "cyclic (>1)") to "${cyclic.size}",
                    (if (zh) "最大簇" else "largest") to "${sccs.firstOrNull()?.size ?: 0}",
                ))
                if (sccs.isNotEmpty()) {
                    SccGraphCanvas(
                        comps = sccs,
                        edges = edges,
                        zh = zh,
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                    )
                }
                if (cyclic.isEmpty()) {
                    AnalysisEmptyState(
                        title = if (zh) "无环状调用簇" else "No cyclic clusters",
                        hint = if (zh) "所有函数的调用关系无环（DAG），可直接按调用顺序阅读。" else "The call graph is acyclic (DAG).",
                    )
                } else {
                    Text(if (zh) "环状簇（互相调用，建议整体理解）" else "Cyclic clusters", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(AppShape.md))
                            .background(cs.surfaceContainerHigh)
                            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cyclic.take(60).forEachIndexed { idx, comp ->
                            Column(Modifier.fillMaxWidth().clickable { copyToClipboard(context, comp.joinToString("\n"), zh) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TypeBadge("#$idx · ${comp.size}", cs.tertiary)
                                    MonoLine(if (zh) "点按复制该簇" else "tap to copy", cs.onSurfaceVariant, AppText.label)
                                }
                                comp.take(12).forEach { nm -> MonoLine(nm, cs.onSurface, AppText.label) }
                                if (comp.size > 12) MonoLine(if (zh) "… 共 ${comp.size} 个" else "… ${comp.size} total", cs.onSurfaceVariant, AppText.label)
                            }
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (indeg.isNotEmpty()) {
                    Text(if (zh) "枢纽（入度 Top5）" else "Hubs (indegree Top5)", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        indeg.forEach { (name, c) -> TypeBadge("$name  ×$c", cs.primary) }
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHigh)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    shown.take(500).forEach { n ->
                        val nm = n.optString("name").ifBlank { n.optString("id") }
                        Row(Modifier.fillMaxWidth().clickable { copyToClipboard(context, nm, zh) }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MonoLine(hexAddr(n.opt("offset") ?: n.opt("id")), cs.primary, AppText.label)
                            Text(nm, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontSize = AppText.body,
                                color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sz = numOf(n.opt("size"))
                            if (sz > 0) MonoLine("$sz", cs.onSurfaceVariant, AppText.label)
                        }
                    }
                    if (shown.size > 500) MonoLine(if (zh) "… 仅显示前 500 个节点" else "… first 500 nodes", cs.onSurfaceVariant, AppText.label)
                }
                if (edges.isNotEmpty()) {
                    Text(if (zh) "调用边（前 400）" else "Edges (first 400)", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(AppShape.md))
                            .background(cs.surfaceContainerHigh)
                            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        edges.take(400).forEach { (f, t) ->
                            MonoLine("$f  →  $t", cs.onSurface, AppText.label)
                        }
                    }
                }
            }
        }
    }
}

/** agCj 输出既可能是 {nodes,edges} 也可能是 [{name,imports|out}] 数组。 */
private fun parseCallGraph(items: List<JSONObject>): Pair<List<JSONObject>, List<Pair<String, String>>> {
    if (items.isEmpty()) return emptyList<JSONObject>() to emptyList<Pair<String, String>>()
    // 形式一：单个对象含 nodes/edges
    val first = items.firstOrNull()
    if (items.size == 1 && first != null && (first.has("nodes") || first.has("edges"))) {
        val ns = mutableListOf<JSONObject>()
        first.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { ns.add(it) } }
        val es = mutableListOf<Pair<String, String>>()
        first.optJSONArray("edges")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val f = e.optString("from").ifBlank { e.optString("src") }
                val t = e.optString("to").ifBlank { e.optString("dst") }
                if (f.isNotBlank() && t.isNotBlank()) es.add(f to t)
            }
        }
        return ns to es
    }
    // 形式二：数组，每项是一个函数节点（可能含 imports/out 列表）
    val nodes = items
    val edges = mutableListOf<Pair<String, String>>()
    items.forEach { o ->
        val nm = o.optString("name").ifBlank { hexAddr(o.opt("offset")) }
        listOf("imports", "out", "calls", "children").forEach { key ->
            o.optJSONArray(key)?.let { a ->
                for (i in 0 until a.length()) {
                    val t = when (val v = a.opt(i)) {
                        is String -> v
                        is JSONObject -> v.optString("name").ifBlank { hexAddr(v.opt("offset")) }
                        else -> ""
                    }
                    if (nm.isNotBlank() && t.isNotBlank()) edges.add(nm to t)
                }
            }
        }
    }
    return nodes to edges
}

// ───────────────────────── 导出中心 ─────────────────────────

private data class ExportKind(val key: String, val zh: String, val en: String, val cmd: String, val fields: List<Triple<String, String, String>>)

/** fields: (jsonKey, 中文列名, 英文列名) */
private val exportKinds = listOf(
    ExportKind("functions", "函数列表", "Functions", "aflj", listOf(
        Triple("offset", "地址", "ADDR"), Triple("size", "大小", "SIZE"),
        Triple("nbbs", "基本块", "BBS"), Triple("name", "名字", "NAME"))),
    ExportKind("strings", "字符串", "Strings", "izj", listOf(
        Triple("vaddr", "地址", "VADDR"), Triple("type", "类型", "TYPE"),
        Triple("length", "长度", "LEN"), Triple("string", "内容", "VALUE"))),
    ExportKind("symbols", "符号", "Symbols", "isj", listOf(
        Triple("vaddr", "地址", "VADDR"), Triple("type", "类型", "TYPE"),
        Triple("bind", "绑定", "BIND"), Triple("name", "名字", "NAME"))),
    ExportKind("imports", "导入", "Imports", "iij", listOf(
        Triple("plt", "PLT", "PLT"), Triple("type", "类型", "TYPE"), Triple("name", "名字", "NAME"))),
    ExportKind("sections", "节区", "Sections", "iSj", listOf(
        Triple("vaddr", "虚地址", "VADDR"), Triple("paddr", "文件偏移", "OFF"),
        Triple("size", "大小", "SIZE"), Triple("name", "名称", "NAME"))),
    ExportKind("segments", "程序段", "Segments", "iSSj", listOf(
        Triple("vaddr", "虚地址", "VADDR"), Triple("paddr", "文件偏移", "OFF"),
        Triple("vsize", "内存大小", "VMSIZE"), Triple("type", "类型", "TYPE"))),
    ExportKind("relocs", "重定位", "Relocations", "irj", listOf(
        Triple("vaddr", "地址", "VADDR"), Triple("type", "类型", "TYPE"), Triple("name", "名字", "NAME"))),
    ExportKind("entries", "入口点", "Entrypoints", "iej", listOf(
        Triple("vaddr", "虚地址", "VADDR"), Triple("type", "类型", "TYPE"), Triple("name", "名称", "NAME"))),
)

@Composable
private fun ExportView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("functions") }
    var tabSep by remember { mutableStateOf(true) }
    var limit by remember { mutableStateOf("2000") }
    var text by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }

    val spec = exportKinds.firstOrNull { it.key == kind } ?: exportKinds.first()

    fun generate() {
        if (ws.isBlank()) return
        val lim = limit.toIntOrNull()?.coerceIn(1, 100000) ?: 2000
        scope.launch {
            loading = true; error = ""; text = ""; count = 0; saved = ""
            val res = withContext(Dispatchers.IO) {
                runCatching { rzArray(EngineProvider.get(context).rzCommand(ws, "", spec.cmd)) }.getOrNull()
            }
            loading = false
            if (res == null) { error = if (zh) "命令失败（${spec.cmd}）" else "command failed (${spec.cmd})"; return@launch }
            val rows = res.take(lim)
            count = rows.size
            val sep = if (tabSep) "\t" else ","
            val sb = StringBuilder()
            if (spec.key == "functions") sb.append("# TaffyNiHe 函数列表导出\t# SO 函数列表导出\n")
            else sb.append("# TaffyNiHe ${if (zh) spec.zh else spec.en} ${if (zh) "导出" else "export"}\n")
            sb.append("# ").append(spec.fields.joinToString(sep) { if (zh) it.second else it.third }).append('\n')
            rows.forEach { o ->
                sb.append(spec.fields.joinToString(sep) { f ->
                    val v = o.opt(f.first)
                    when (v) {
                        null, JSONObject.NULL -> ""
                        is String -> v
                        else -> v.toString()
                    }
                }).append('\n')
            }
            text = sb.toString()
        }
    }

    fun saveToWorkspace() {
        if (text.isBlank()) return
        scope.launch {
            saved = ""
            val name = "taffy_${spec.key}_${System.currentTimeMillis()}.${if (tabSep) "tsv" else "csv"}"
            // 写到外部私有目录（无需额外权限），路径稳定可被文件管理/分享访问
            val dir = java.io.File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val out = java.io.File(dir, name)
            val okW = withContext(Dispatchers.IO) { runCatching { out.writeText(text); true }.getOrDefault(false) }
            saved = if (okW) (if (zh) "已保存：${out.absolutePath}" else "saved: ${out.absolutePath}")
                else (if (zh) "保存失败" else "save failed")
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            exportKinds.forEach { k ->
                SmallAction(if (zh) k.zh else k.en, active = k.key == kind) { kind = k.key; text = ""; count = 0 }
            }
        }
        Spacer(Modifier.size(6.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "生成" else "Generate", loading = loading, onClick = { generate() })
            SmallAction("TSV", active = tabSep) { tabSep = true }
            SmallAction("CSV", active = !tabSep) { tabSep = false }
            SmallAction(if (zh) "复制" else "Copy", enabled = text.isNotBlank()) { copyToClipboard(context, text, zh) }
            SmallAction(if (zh) "保存到文件" else "Save file", enabled = text.isNotBlank()) { saveToWorkspace() }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
            if (count > 0) Text(if (zh) "$count 行" else "$count rows", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = limit, onValueChange = { limit = it }, singleLine = true,
            modifier = Modifier.width(120.dp).heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            label = { Text(if (zh) "行数上限" else "limit", fontSize = AppText.label) },
        )
        if (saved.isNotBlank()) { Spacer(Modifier.size(6.dp)); MonoLine(saved, cs.primary, AppText.label) }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            text.isBlank() -> AnalysisEmptyState(
                title = if (zh) "导出中心" else "Export center",
                hint = if (zh) "选择数据类型（函数/字符串/符号/导入/节区/程序段/重定位/入口点）后点「生成」，得到 TSV/CSV；可复制或保存到文件。"
                    else "Pick a data type, then Generate to get TSV/CSV; copy it or save to a file.",
                primaryLabel = if (zh) "生成" else "Generate", onPrimary = { generate() },
            )
            else -> ToolResultBlock(
                if (zh) "${spec.zh} · ${if (tabSep) "TSV" else "CSV"} 预览" else "${spec.en} · ${if (tabSep) "TSV" else "CSV"} preview",
                text.take(6000), zh = zh, onCopy = { copyToClipboard(context, text, zh) },
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  脱壳工作台（对齐 Exbin §6.7 DumpStrategy：Frida 内存 Dump 脚本 + ELF 修复清单）
//  纯模板/本地实现；检测部分复用 rizin 字符串特征 + .text 熵。
// ═══════════════════════════════════════════════════════════════════════════

/** 生成 Frida 内存 dump 脚本（按模块名参数化）。 */
private fun buildDumpScript(moduleName: String, outPath: String, dumpAll: Boolean): String {
    val mod = moduleName.ifBlank { "libtarget.so" }
    val out = outPath.ifBlank { "/data/local/tmp" }
    val sb = StringBuilder()
    sb.append("// ── 塔菲逆核 · Frida 内存 Dump 脚本（脱壳用） ──\n")
    sb.append("// 用法: frida -U -f <包名> -l dump.js --no-pause\n")
    sb.append("// 说明: 壳在运行时解密 .text 后才会有真实指令, 静态打开看到的是密文;\n")
    sb.append("//       本脚本把解密后的内存镜像 dump 到文件, 再用 SoFixer/修复工具重建 ELF 结构。\n\n")
    if (dumpAll) {
        sb.append("// 模式: dump 全部已加载模块\n")
        sb.append("var OUT = '").append(out).append("';\n\n")
        sb.append("function sanitize(name) { return name.replace(/[^A-Za-z0-9_.-]/g, '_'); }\n\n")
        sb.append("Process.enumerateModulesSync().forEach(function (m) {\n")
        sb.append("    try {\n")
        sb.append("        var base = m.base;\n")
        sb.append("        if (base.isNull()) return;\n")
        sb.append("        var bytes = base.readByteArray(m.size);\n")
        sb.append("        var path = OUT + '/' + sanitize(m.name) + '.dump';\n")
        sb.append("        var f = new File(path, 'wb');\n")
        sb.append("        f.write(bytes); f.flush(); f.close();\n")
        sb.append("        console.log('[dump] ' + m.name + '  size=' + m.size + '  -> ' + path);\n")
        sb.append("    } catch (e) { console.log('[skip] ' + m.name + ' : ' + e); }\n")
        sb.append("});\n\n")
        sb.append("console.log('[done] all modules dumped to ' + OUT);\n")
    } else {
        sb.append("var MODULE_NAME = '").append(mod).append("';\n")
        sb.append("var OUT = '").append(out).append("';\n\n")
        sb.append("var base = Module.findBaseAddress(MODULE_NAME);\n")
        sb.append("if (!base) { console.log('[!] module not loaded: ' + MODULE_NAME); }\n")
        sb.append("else {\n")
        sb.append("    Process.enumerateModulesSync().forEach(function (m) {\n")
        sb.append("        if (m.name !== MODULE_NAME) return;\n")
        sb.append("        var bytes = m.base.readByteArray(m.size);\n")
        sb.append("        var path = OUT + '/' + MODULE_NAME + '.dump';\n")
        sb.append("        var f = new File(path, 'wb');\n")
        sb.append("        f.write(bytes); f.flush(); f.close();\n")
        sb.append("        console.log('[dump] ' + MODULE_NAME + '  base=' + m.base + '  size=' + m.size);\n")
        sb.append("        console.log('[dump] saved to ' + path);\n")
        sb.append("    });\n")
        sb.append("}\n")
    }
    sb.append("\n// ── 进阶: 若目标模块在 dump 时尚未解密完毕, 可挂到 JNI_OnLoad 之后再 dump ──\n")
    sb.append("// var onLoad = Module.findExportByName('").append(mod).append("', 'JNI_OnLoad');\n")
    sb.append("// if (onLoad) Interceptor.attach(onLoad, { onLeave: function () { /* 在此 dump */ } });\n")
    return sb.toString()
}

/** ELF 修复流程（对齐 Exbin FIX_STEPS）。 */
private val fixSteps: List<Pair<String, String>> = listOf(
    "1. 内存 Dump" to "root 后用 Frida 脚本枚举模块并把解密后的内存镜像写到 /data/local/tmp（本页可生成脚本）。",
    "2. 修复 ELF 头" to "校正 e_shoff / e_shnum / e_shstrndx，恢复被抹除的节区头表（工具：taffy_edit_fix_sections）。",
    "3. 重建 Section Headers" to "依据动态表重建 .dynsym / .dynstr / .rela.dyn / .rela.plt / .init_array（工具：taffy_edit_fix_sections）。",
    "4. 反混淆" to "对高跳转密度函数做控制流平坦化还原（不透明谓词识别 + 调度器状态机重建）。",
    "5. 动态辅助与校验" to "Unidbg / Frida Stalker 记录执行路径；最后用 readelf -a / IDA 重新加载比对。",
)

@Composable
private fun UnpackView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var module by remember { mutableStateOf("") }
    var outPath by remember { mutableStateOf("/data/local/tmp") }
    var dumpAll by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var findings by remember(ws) { mutableStateOf<List<HardFinding>>(emptyList()) }
    var entropy by remember(ws) { mutableStateOf(-1.0) }
    var scanning by remember(ws) { mutableStateOf(false) }
    var scanned by remember(ws) { mutableStateOf(false) }

    // 默认模块名取当前 SO
    LaunchedEffect(ws) {
        if (module.isBlank()) module = tools.sharedSoName.ifBlank { "libtarget.so" }
    }
    // 自动扫一次加固特征（判断是否需要脱壳）
    LaunchedEffect(ws) {
        if (ws.isBlank() || scanned) return@LaunchedEffect
        scanning = true
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val eng = EngineProvider.get(context)
                val txt = eng.rzCommand(ws, "", "izq").let { r -> r.optString("stdout").ifBlank { r.optString("text") } }
                val strs = txt.split('\n').map { it.trim() }.filter { it.length in 3..300 }.take(50000)
                val hx = eng.rzCommand(ws, "", "p8 4096 @ .text").let { r -> r.optString("stdout").ifBlank { r.optString("text") } }.trim()
                val bytes = runCatching {
                    val t = hx.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                    ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                }.getOrNull() ?: ByteArray(0)
                hardMatch(strs) to (if (bytes.isNotEmpty()) shannonEntropy(bytes) else -1.0)
            }.getOrNull()
        }
        scanning = false; scanned = true
        if (res != null) { findings = res.first; entropy = res.second }
    }

    fun gen() {
        script = buildDumpScript(module.trim(), outPath.trim(), dumpAll)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (zh) "脱壳工作台" else "Unpack workbench", style = MaterialTheme.typography.bodySmall, fontSize = AppText.bodyStrong, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            MonoLine(if (zh) "Frida 内存 Dump + ELF 修复流程" else "Frida memory dump + ELF repair", cs.onSurfaceVariant, AppText.label)
            if (scanning) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        }

        // ── 是否需要脱壳的判定 ──
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(if (zh) "① 脱壳判定" else "① Need unpack?", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            when {
                ws.isBlank() -> MonoLine(if (zh) "未打开 SO" else "no SO opened", cs.onSurfaceVariant, AppText.label)
                scanning -> MonoLine(if (zh) "正在扫描特征…" else "scanning…", cs.onSurfaceVariant, AppText.label)
                else -> {
                    val need = findings.isNotEmpty() || entropy >= 7.0
                    MonoLine(
                        if (need) (if (zh) "⚠ 检出加固/混淆特征，建议先脱壳再静态分析" else "⚠ hardening detected — unpack first")
                        else (if (zh) "✓ 未检出常见加固特征，通常可直接分析" else "✓ no common hardening — analyze directly"),
                        if (need) cs.error else cs.primary, AppText.label,
                    )
                    if (entropy >= 0) MonoLine(".text entropy: %.3f / 8.0".format(entropy) + (if (entropy >= 7.0) (if (zh) "  （偏高，疑似加密）" else "  (likely encrypted)") else ""), cs.onSurfaceVariant, AppText.label)
                    findings.take(6).forEach { f -> MonoLine("· [${f.severity}] ${f.title}", cs.onSurfaceVariant, AppText.label) }
                    if (findings.size > 6) MonoLine(if (zh) "… 共 ${findings.size} 项（见「加固」页）" else "… ${findings.size} total (see Hardening)", cs.onSurfaceVariant, AppText.label)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallAction(if (zh) "重新扫描" else "Rescan", onClick = onRefresh)
                        SmallAction(if (zh) "查看加固报告" else "Hardening") { tools.analysisView = "hardening" }
                    }
                }
            }
        }

        // ── 脚本生成 ──
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (zh) "② Frida 内存 Dump 脚本" else "② Frida dump script", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = module, onValueChange = { module = it }, singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(AppShape.sm),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                    label = { Text(if (zh) "目标模块名" else "module", fontSize = AppText.label) },
                    placeholder = { Text("libtarget.so", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
                )
                OutlinedTextField(
                    value = outPath, onValueChange = { outPath = it }, singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(AppShape.sm),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                    label = { Text(if (zh) "输出目录" else "out dir", fontSize = AppText.label) },
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction(if (zh) "单模块" else "Single", active = !dumpAll) { dumpAll = false; script = "" }
                SmallAction(if (zh) "全部模块" else "All modules", active = dumpAll) { dumpAll = true; script = "" }
                SmallAction(if (zh) "生成脚本" else "Generate", onClick = { gen() })
                SmallAction(if (zh) "复制脚本" else "Copy", enabled = script.isNotBlank()) { copyToClipboard(context, script, zh) }
            }
            OutlinedTextField(
                value = pkg, onValueChange = { pkg = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "目标包名（生成运行命令）" else "package", fontSize = AppText.label) },
                placeholder = { Text("com.example.app", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
            )
            if (script.isNotBlank()) {
                MonoLine(
                    "frida -U -f ${pkg.ifBlank { "<包名>" }} -l dump.js --no-pause",
                    cs.primary, AppText.label,
                )
                ToolResultBlock(if (zh) "生成结果" else "Generated", script, zh = zh, onCopy = { copyToClipboard(context, script, zh) })
            } else {
                SmallAction(if (zh) "生成脚本" else "Generate", onClick = { gen() })
            }
        }

        // ── 修复流程 ──
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (zh) "③ Dump 后修复流程" else "③ Post-dump repair", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            fixSteps.forEach { (title, detail) ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, style = MaterialTheme.typography.bodySmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text(detail, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurface, lineHeight = 15.sp)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction(if (zh) "复制流程" else "Copy steps") {
                    copyToClipboard(context, fixSteps.joinToString("\n") { "${it.first}\n  ${it.second}" }, zh)
                }
                SmallAction(if (zh) "复制脚本+流程" else "Copy all") {
                    val all = buildDumpScript(module.trim(), outPath.trim(), dumpAll) + "\n\n" +
                        fixSteps.joinToString("\n") { "${it.first}\n  ${it.second}" }
                    copyToClipboard(context, all, zh)
                }
            }
        }
    }
}

// ───────────────────────── 数据段（常量 / 全局变量） ─────────────────────────

@Composable
private fun DataView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val tick = tools.reloadTick
    val cs = MaterialTheme.colorScheme
    var action by remember { mutableStateOf("all") }
    var data by remember(ws, tick, action) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(ws, tick, action) { mutableStateOf(false) }
    var error by remember(ws, tick, action) { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(ws, tick, action) {
        if (ws.isBlank()) return@LaunchedEffect
        loading = true; error = ""
        val r = callMcpTool(context, "taffy_so_data",
            JSONObject().put("workspaceId", ws).put("action", action).put("limit", 600))
        loading = false
        data = r
        if (r == null) error = if (zh) "引擎未就绪（native 库未加载）" else "engine not ready"
        else if (!r.optBoolean("ok", true)) error = r.optString("error").ifBlank { r.optString("note") }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    val entries = remember(data) {
        val a = data?.optJSONArray("entries") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    val shown = remember(entries, filter) {
        if (filter.isBlank()) entries else entries.filter {
            it.optString("type").contains(filter, true) || it.optString("value").contains(filter, true) ||
                it.optString("addr").contains(filter, true) || it.optString("target").contains(filter, true)
        }
    }
    val byType = remember(data) {
        val o = data?.optJSONObject("byType")
        if (o == null) emptyList() else o.keys().asSequence().map { it to o.optInt(it) }.sortedByDescending { it.second }.toList()
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "全部" else "All", active = action == "all") { action = "all" }
            SmallAction(if (zh) "常量" else "Constants", active = action == "constants") { action = "constants" }
            SmallAction(if (zh) "全局变量" else "Globals", active = action == "globals") { action = "globals" }
            SmallAction(if (zh) "刷新" else "Refresh", loading = loading, onClick = onRefresh)
            SmallAction(if (zh) "复制" else "Copy", enabled = shown.isNotEmpty()) {
                val sb = StringBuilder("addr\toffset\tsize\ttype\tvalue\ttarget\n")
                shown.forEach { e -> sb.append(e.optString("addr")).append('\t').append(e.optString("offset")).append('\t')
                    .append(e.optString("size")).append('\t').append(e.optString("type")).append('\t')
                    .append(e.optString("value")).append('\t').append(e.optString("target")).append('\n') }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            byType.forEach { (t, c) -> TypeBadge("$t $c", cs.primary) }
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(15.dp), tint = cs.onSurfaceVariant) },
            placeholder = { Text(if (zh) "过滤类型 / 值 / 地址 / 目标" else "filter type / value / addr / target", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.label), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        Spacer(Modifier.size(8.dp))
        when {
            loading && data == null -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            entries.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "无数据段条目" else "No data entries",
                hint = if (zh) "未在 .rodata/.data/.got 等节区里识别出常量或全局变量。可换 action 或确认 SO 未被 strip/加密。"
                    else "No constants/globals recognized in .rodata/.data/.got. Try another action, or the SO may be stripped/encrypted.",
                primaryLabel = if (zh) "重新扫描" else "Rescan", onPrimary = onRefresh,
            )
            else -> Column(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(cs.surfaceContainerHigh)
                    .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                    listOf(("ADDR" to 84.dp), ("TYPE" to 56.dp), ("VALUE" to 0.dp), ("TARGET" to 0.dp)).forEach { (t, w) ->
                        Text(t, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant,
                            modifier = if (w != 0.dp) Modifier.width(w) else Modifier.weight(1f))
                    }
                }
                GroupDivider()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(shown.size, key = { it }) { idx ->
                        val e = shown[idx]
                        Row(Modifier.fillMaxWidth().clickable {
                            copyToClipboard(context, e.optString("value"), zh)
                        }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e.optString("addr"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.label, color = cs.primary, modifier = Modifier.width(84.dp), maxLines = 1)
                            Text(e.optString("type"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.label, color = cs.tertiary, modifier = Modifier.width(56.dp), maxLines = 1)
                            Text(e.optString("value"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.label, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.optString("target").ifBlank { "--" }, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = AppText.label, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 函数详情（对齐 Exbin FuncDetailActivity / FuncSignatureDialog） ─────────────────────────

/** 本地 Tarjan 强连通分量（用于调用图 SCC 鸟瞰）。 */
private fun tarjanScc(nodes: List<String>, edges: List<Pair<String, String>>): List<List<String>> {
    val adj = HashMap<String, MutableList<String>>()
    nodes.forEach { adj[it] = mutableListOf() }
    edges.forEach { (f, t) -> if (adj.containsKey(f) && adj.containsKey(t)) adj[f]!!.add(t) }
    val index = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val onStack = HashSet<String>()
    val stack = ArrayDeque<String>()
    var counter = 0
    val out = mutableListOf<List<String>>()
    // 迭代式 Tarjan（避免深递归栈溢出）
    nodes.forEach { start ->
        if (index.containsKey(start)) return@forEach
        val work = ArrayDeque<Pair<String, Int>>()
        work.addLast(start to 0)
        while (work.isNotEmpty()) {
            val (v, pi) = work.removeLast()
            if (pi == 0) {
                index[v] = counter; low[v] = counter; counter++
                stack.addLast(v); onStack.add(v)
            }
            var recursed = false
            val neighbors = adj[v] ?: emptyList()
            var i = pi
            while (i < neighbors.size) {
                val w = neighbors[i]
                if (!index.containsKey(w)) {
                    work.addLast(v to (i + 1))
                    work.addLast(w to 0)
                    recursed = true
                    break
                } else if (onStack.contains(w)) {
                    low[v] = minOf(low[v] ?: 0, index[w] ?: 0)
                }
                i++
            }
            if (recursed) continue
            if ((low[v] ?: 0) == (index[v] ?: 0)) {
                val comp = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLastOrNull() ?: break
                    onStack.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                out.add(comp)
            }
            // 回填父节点 low
            work.lastOrNull()?.let { (pv, _) ->
                if (low.containsKey(pv) && low.containsKey(v)) low[pv] = minOf(low[pv] ?: 0, low[v] ?: 0)
            }
        }
    }
    return out
}

@Composable
private fun FuncInfoView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    var locator by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var ran by remember { mutableStateOf(false) }
    var sig by remember { mutableStateOf<JSONObject?>(null) }
    var callers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var callees by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ws) {
        if (locator.isBlank()) locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }
    }

    fun load() {
        val loc = locator.trim().ifBlank { tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName } }
        if (loc.isBlank() || ws.isBlank()) return
        scope.launch {
            loading = true; error = ""; ran = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val sigR = callMcpTool(context, "taffy_so_func_sig",
                        JSONObject().put("workspaceId", ws).put("locator", loc).put("count", 1))
                    val s = sigR?.optJSONArray("signatures")?.optJSONObject(0)
                    val eng = EngineProvider.get(context)
                    val inR = eng.rzCommand(ws, "", "s $loc; axtj")
                    val outR = eng.rzCommand(ws, "", "s $loc; axfj")
                    Triple(s, parseRzArray(inR) ?: emptyList(), parseRzArray(outR) ?: emptyList())
                }.getOrNull()
            }
            loading = false
            if (r == null) { error = if (zh) "取数失败（定位失败或引擎异常）" else "failed" ; return@launch }
            sig = r.first; callers = r.second; callees = r.third
            if (sig == null && callers.isEmpty() && callees.isEmpty()) error = if (zh) "无数据：请检查函数名/地址" else "no data"
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "加载" else "Load", loading = loading, onClick = { load() })
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionVa.isNotBlank() || tools.selectedFunctionName.isNotBlank()) {
                locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }; load()
            }
            SmallAction(if (zh) "复制摘要" else "Copy", enabled = sig != null || callers.isNotEmpty() || callees.isNotEmpty()) {
                val s = sig
                val sb = StringBuilder()
                sb.append(if (s != null) sigLine(s, zh) else locator).append('\n')
                sb.append(if (zh) "调用者" else "callers").append(" (${callers.size}): ").append(callers.joinToString(", ") { it.optString("from") }).append('\n')
                sb.append(if (zh) "被调用" else "callees").append(" (${callees.size}): ").append(callees.joinToString(", ") { it.optString("to") })
                copyToClipboard(context, sb.toString(), zh)
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = locator, onValueChange = { locator = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            label = { Text(if (zh) "函数名 / 地址" else "symbol / addr", fontSize = AppText.label) },
            placeholder = { Text("JNI_OnLoad 或 0x1234", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
        )
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() && sig == null && callers.isEmpty() && callees.isEmpty() -> AnalysisErrorBanner(error)
            !ran -> AnalysisEmptyState(
                title = if (zh) "函数详情" else "Function detail",
                hint = if (zh) "输入函数名或地址，查看还原签名 / 参数列表 / 调用者 / 被调用者，并可直接跳到汇编、伪 C、CFG。"
                    else "Enter a symbol or address to see signature, params, callers/callees, with jump to disasm/pseudo-C/CFG.",
                primaryLabel = if (zh) "取当前函数" else "Current fn",
                onPrimary = { locator = tools.selectedFunctionVa.ifBlank { tools.selectedFunctionName }; load() },
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = sig
                if (s != null) {
                    SigCard(s, zh, context, cs)
                } else {
                    KeyValueCard(zh, listOf((if (zh) "函数" else "fn") to locator.ifBlank { tools.selectedFunctionName }))
                }
                RefBlock(if (zh) "调用者（谁调用它）" else "Callers", callers, "from", cs.primary, zh, context)
                RefBlock(if (zh) "被调用（它调用谁）" else "Callees", callees, "to", cs.tertiary, zh, context)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction(if (zh) "查看汇编" else "Disasm") {
                        tools.selectedFunctionVa = if (locator.startsWith("0x")) locator else tools.selectedFunctionVa
                        tools.selectedFunctionName = if (!locator.startsWith("0x")) locator else tools.selectedFunctionName
                        tools.disasmAddr = locator
                        tools.analysisView = "disasm"
                    }
                    SmallAction(if (zh) "查看伪C" else "Pseudo-C") {
                        tools.decompileTarget = locator
                        tools.analysisView = "pseudo"
                    }
                    SmallAction("CFG") {
                        tools.cfgTarget = locator
                        tools.analysisView = "cfg"
                    }
                    SmallAction(if (zh) "看引用" else "XRefs") {
                        tools.analysisView = "xrefs"
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  注释存储 + 分析档位（快速/全量）+ 交叉引用根下钻
//  对齐 Exbin：CommentStore（§5.1 注释）、showAnalysisModeDialog（§3.2 双档分析）、
//  GlobalXRefFragment 的「根下钻」（§6.5）。
// ═══════════════════════════════════════════════════════════════════════════

/** 函数/地址注释的本地存储（SharedPreferences，仅落本地注释库，不写 SO）。 */
private object AnalysisComments {
    private const val PREF = "taffy_analysis_comments"
    private const val KEY = "items"

    data class Entry(val key: String, val text: String)

    fun load(context: android.content.Context): List<Entry> {
        val sp = context.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val k = o.optString("key")
            if (k.isBlank()) null else Entry(k, o.optString("text"))
        }
    }

    fun save(context: android.content.Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("key", it.key).put("text", it.text)) }
        context.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun put(context: android.content.Context, key: String, text: String): List<Entry> {
        val list = load(context).filter { it.key != key }.toMutableList()
        if (text.isNotBlank()) list.add(0, Entry(key, text))
        save(context, list)
        return list
    }

    fun remove(context: android.content.Context, key: String): List<Entry> {
        val list = load(context).filter { it.key != key }
        save(context, list)
        return list
    }
}

@Composable
private fun CommentsView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var items by remember { mutableStateOf(AnalysisComments.load(context)) }
    var target by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(tools.reloadTick) { items = AnalysisComments.load(context) }
    LaunchedEffect(tools.sharedWorkspaceId) {
        if (target.isBlank()) target = tools.selectedFunctionName.ifBlank { tools.selectedFunctionVa }
    }

    val shown = remember(items, filter) {
        if (filter.isBlank()) items else items.filter { it.key.contains(filter, true) || it.text.contains(filter, true) }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionName.isNotBlank() || tools.selectedFunctionVa.isNotBlank()) {
                target = tools.selectedFunctionName.ifBlank { tools.selectedFunctionVa }
            }
            SmallAction(if (zh) "复制全部" else "Copy all", enabled = items.isNotEmpty()) {
                copyToClipboard(context, items.joinToString("\n") { "${it.key}\t${it.text}" }, zh)
            }
            SmallAction(if (zh) "清空全部" else "Clear all", enabled = items.isNotEmpty()) {
                AnalysisComments.save(context, emptyList()); items = emptyList()
            }
            Text(if (zh) "${items.size} 条注释" else "${items.size} comments",
                style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = target, onValueChange = { target = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            label = { Text(if (zh) "目标（函数名 / 地址）" else "target (fn / addr)", fontSize = AppText.label) },
            placeholder = { Text("sub_1234 或 0x1234", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
        )
        Spacer(Modifier.size(6.dp))
        ToolMonoField(
            value = body, onValueChange = { body = it },
            label = if (zh) "注释内容" else "comment",
            placeholder = if (zh) "记录你的分析结论（只落本地，不写入 SO）" else "your note (local only)",
            minHeight = 64.dp,
        )
        Spacer(Modifier.size(6.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "保存注释" else "Save", enabled = target.isNotBlank() && body.isNotBlank()) {
                items = AnalysisComments.put(context, target.trim(), body.trim())
                body = ""
            }
            SmallAction(if (zh) "删除该条" else "Delete", enabled = target.isNotBlank()) {
                items = AnalysisComments.remove(context, target.trim())
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(15.dp), tint = cs.onSurfaceVariant) },
            placeholder = { Text(if (zh) "过滤目标 / 内容" else "filter", style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.label), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        Spacer(Modifier.size(8.dp))
        when {
            shown.isEmpty() -> AnalysisEmptyState(
                title = if (zh) "暂无注释" else "No comments",
                hint = if (zh) "给函数或地址写一条注释（例如「这里是签名校验」），只保存在本地注释库，不会修改 SO 文件。"
                    else "Write a note for a function/address; stored locally only, the SO is never modified.",
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { e ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(AppShape.md))
                            .background(cs.surfaceContainerHigh)
                            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MonoLine(e.key, cs.primary, AppText.label)
                            Spacer(Modifier.weight(1f))
                            SmallAction(if (zh) "编辑" else "Edit") { target = e.key; body = e.text }
                            SmallAction(if (zh) "删除" else "Del") { items = AnalysisComments.remove(context, e.key) }
                        }
                        Text(e.text, style = MaterialTheme.typography.bodySmall, fontSize = AppText.body, color = cs.onSurface, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

// ───────────────────────── 分析档位（快速 / 全量） ─────────────────────────

@Composable
private fun AnalyzeModeView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf("") }
    var stats by remember(ws) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var log by remember(ws) { mutableStateOf("") }

    fun collectStats() {
        if (ws.isBlank()) return
        scope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = EngineProvider.get(context)
                    val fns = rzArray(eng.rzCommand(ws, "", "aflj")).size
                    val strs = rzArray(eng.rzCommand(ws, "", "izj")).size
                    val syms = rzArray(eng.rzCommand(ws, "", "isj")).size
                    val xrs = rzArray(eng.rzCommand(ws, "", "axtj @ entry0")).size
                    listOf(
                        (if (zh) "函数" else "functions") to "$fns",
                        (if (zh) "字符串" else "strings") to "$strs",
                        (if (zh) "符号" else "symbols") to "$syms",
                        ("entry0 xrefs") to "$xrs",
                    )
                }.getOrNull() ?: emptyList()
            }
            stats = s
        }
    }

    fun run(mode: String) {
        if (ws.isBlank()) return
        scope.launch {
            running = mode
            log = ""
            val cmd = if (mode == "full") "aaaa" else "aa"
            val r = withContext(Dispatchers.IO) {
                runCatching { EngineProvider.get(context).rzCommand(ws, "", cmd) }.getOrNull()
            }
            running = ""
            log = if (r == null) (if (zh) "分析命令失败" else "analysis failed")
                else (if (zh) "已执行 $cmd（${if (mode == "full") "全量" else "快速"}分析）" else "ran $cmd")
            collectStats()
        }
    }

    LaunchedEffect(ws) { if (ws.isNotBlank()) collectStats() }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (zh) "分析模式" else "Analysis mode", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            MonoLine(
                if (zh) "快速分析：只做 ELF 结构 / 函数 / 符号（aa），大文件内存紧张时用。"
                else "Quick: ELF structure / functions / symbols only (aa).",
                cs.onSurface, AppText.label,
            )
            MonoLine(
                if (zh) "全量分析：额外做字符串 / 匿名函数 / 交叉引用 / 签名（aaaa），更慢更全。"
                else "Full: also strings / anonymous functions / xrefs / signatures (aaaa).",
                cs.onSurface, AppText.label,
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction(if (zh) "快速分析 (aa)" else "Quick (aa)", loading = running == "quick", onClick = { run("quick") })
                SmallAction(if (zh) "全量分析 (aaaa)" else "Full (aaaa)", loading = running == "full", onClick = { run("full") })
                SmallAction(if (zh) "刷新统计" else "Refresh", onClick = { collectStats(); onRefresh() })
            }
            if (log.isNotBlank()) MonoLine(log, cs.primary, AppText.label)
        }
        if (stats.isNotEmpty()) {
            KeyValueCard(zh, stats)
        }
        AnalysisEmptyState(
            title = if (zh) "分析档位" else "Analysis depth",
            hint = if (zh) "打开 SO 后默认已做基础分析；数据不全时可在此补跑全量分析，再回到各页刷新。"
                else "Basic analysis runs on open; run full analysis here when data looks incomplete.",
        )
    }
}

// ───────────────────────── 交叉引用：根下钻 ─────────────────────────

@Composable
private fun RootDrillView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("2") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var levels by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    var ran by remember { mutableStateOf(false) }

    LaunchedEffect(ws) {
        if (root.isBlank()) root = "JNI_OnLoad"
    }

    fun drill() {
        val r0 = root.trim()
        if (r0.isBlank() || ws.isBlank()) return
        val maxDepth = depth.toIntOrNull()?.coerceIn(1, 4) ?: 2
        scope.launch {
            loading = true; error = ""; ran = true; levels = emptyList()
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = EngineProvider.get(context)
                    val out = mutableListOf<Pair<String, List<String>>>()
                    var frontier = listOf(r0)
                    val seen = HashSet<String>(frontier)
                    for (d in 0 until maxDepth) {
                        val next = LinkedHashSet<String>()
                        frontier.forEach { node ->
                            val arr = parseRzArray(eng.rzCommand(ws, "", "s $node; axfj")) ?: return@forEach
                            arr.forEach { e ->
                                val t = e.optString("to")
                                if (t.isNotBlank() && seen.add(t)) next.add(t)
                            }
                        }
                        out.add((if (zh) "第 ${d + 1} 层" else "level ${d + 1}") to next.toList())
                        if (next.isEmpty()) break
                        frontier = next.toList()
                    }
                    out
                }.getOrNull()
            }
            loading = false
            if (res == null) error = if (zh) "下钻失败（根节点无法解析）" else "drill failed"
            levels = res ?: emptyList()
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "开始下钻" else "Drill", loading = loading, onClick = { drill() })
            SmallAction("JNI_OnLoad") { root = "JNI_OnLoad"; drill() }
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionName.isNotBlank()) {
                root = tools.selectedFunctionName; drill()
            }
            SmallAction(if (zh) "复制树" else "Copy", enabled = levels.isNotEmpty()) {
                val sb = StringBuilder()
                levels.forEach { (lvl, list) -> sb.append("$lvl (${list.size})\n"); list.forEach { sb.append("  $it\n") } }
                copyToClipboard(context, sb.toString().trimEnd(), zh)
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = root, onValueChange = { root = it }, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "根节点" else "root", fontSize = AppText.label) },
                placeholder = { Text("JNI_OnLoad", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
            )
            OutlinedTextField(
                value = depth, onValueChange = { depth = it }, singleLine = true,
                modifier = Modifier.width(80.dp).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "层数" else "depth", fontSize = AppText.label) },
            )
        }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            !ran -> AnalysisEmptyState(
                title = if (zh) "根下钻" else "Root drill",
                hint = if (zh) "从一个根节点（如 JNI_OnLoad）出发，逐层展开它调用的函数，快速摸清主流程。"
                    else "From a root (e.g. JNI_OnLoad), expand callees level by level to map the main flow.",
                primaryLabel = if (zh) "从 JNI_OnLoad 开始" else "From JNI_OnLoad",
                onPrimary = { root = "JNI_OnLoad"; drill() },
            )
            levels.isEmpty() || levels.all { it.second.isEmpty() } -> AnalysisEmptyState(
                title = if (zh) "无调用关系" else "No calls",
                hint = if (zh) "根节点没有出边（可能是叶子函数，或需先跑全量分析）。" else "Root has no outgoing edges (leaf, or run full analysis first).",
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLine((if (zh) "根：$root" else "root: $root"), cs.primary, AppText.bodyStrong)
                levels.forEach { (lvl, list) ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$lvl · ${list.size}", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppShape.md))
                                .background(cs.surfaceContainerHigh)
                                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (list.isEmpty()) MonoLine(if (zh) "（无）" else "(none)", cs.onSurfaceVariant, AppText.label)
                            list.take(80).forEach { nm ->
                                MonoLine("→ $nm", cs.onSurface, AppText.label)
                            }
                            if (list.size > 80) MonoLine(if (zh) "… 共 ${list.size} 个" else "… ${list.size} total", cs.onSurfaceVariant, AppText.label)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  编辑中心（对齐 Exbin §5.1「能改什么」）
//    · 函数符号重命名  → taffy_edit_symbol(op=rename)
//    · 字符串内容替换  → taffy_native_patch_string
//    · 单条指令重汇编  → taffy_native_patch_instructions
//  一律先 dryRun / CAS 校验，破坏性写入前二次确认。
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun EditCenterView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("symbol") }

    // 函数改名
    var symLocator by remember { mutableStateOf("") }
    var symNew by remember { mutableStateOf("") }
    var symOut by remember { mutableStateOf("") }

    // 字符串
    var strAddr by remember { mutableStateOf("") }
    var strNew by remember { mutableStateOf("") }
    var strOld by remember { mutableStateOf("") }
    var strPad by remember { mutableStateOf("null_pad") }
    var strOut by remember { mutableStateOf("") }

    // 指令
    var insAddr by remember { mutableStateOf("") }
    var insAsm by remember { mutableStateOf("") }
    var insOld by remember { mutableStateOf("") }
    var insThumb by remember { mutableStateOf(false) }
    var insOut by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(ws) {
        if (symLocator.isBlank()) symLocator = tools.selectedFunctionName.ifBlank { tools.selectedFunctionVa }
        if (insAddr.isBlank()) insAddr = tools.selectedFunctionVa.ifBlank { tools.disasmAddr }
    }

    fun run(block: suspend () -> JSONObject?, set: (String) -> Unit) {
        scope.launch {
            busy = true
            val r = block()
            busy = false
            set(r?.toString(2) ?: (if (zh) "调用失败" else "call failed"))
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "函数改名" else "Rename", active = tab == "symbol") { tab = "symbol" }
            SmallAction(if (zh) "字符串" else "String", active = tab == "string") { tab = "string" }
            SmallAction(if (zh) "指令" else "Insn", active = tab == "insn") { tab = "insn" }
            SmallAction(if (zh) "取当前函数" else "Current fn", enabled = tools.selectedFunctionVa.isNotBlank() || tools.selectedFunctionName.isNotBlank()) {
                symLocator = tools.selectedFunctionName.ifBlank { tools.selectedFunctionVa }
                insAddr = tools.selectedFunctionVa.ifBlank { tools.disasmAddr }
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
            if (busy) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.size(8.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalysisErrorBanner(if (zh) "写入会修改工作区副本；请先「dryRun 预览」确认，再执行实际写入。破坏性操作会弹二次确认。" else "Writes modify the workspace copy. Preview with dryRun first; destructive ops ask for confirmation.")

            when (tab) {
                "symbol" -> {
                    ToolMonoField(symLocator, { symLocator = it }, if (zh) "符号 / 函数名" else "symbol", "sub_1234", 46.dp)
                    ToolMonoField(symNew, { symNew = it }, if (zh) "新名称" else "new name", "check_license", 46.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallAction("Dry run", enabled = symLocator.isNotBlank() && symNew.isNotBlank(), loading = busy) {
                            run({
                                callMcpTool(context, "taffy_edit_symbol", JSONObject()
                                    .put("workspaceId", ws).put("op", "rename").put("locator", symLocator.trim())
                                    .put("name", symNew.trim()).put("dryRun", true))
                            }) { symOut = it }
                        }
                        SmallAction(if (zh) "执行改名" else "Apply", enabled = symLocator.isNotBlank() && symNew.isNotBlank()) {
                            pending = {
                                run({
                                    callMcpTool(context, "taffy_edit_symbol", JSONObject()
                                        .put("workspaceId", ws).put("op", "rename").put("locator", symLocator.trim())
                                        .put("name", symNew.trim()).put("dryRun", false))
                                }) { symOut = it }
                            }
                        }
                    }
                    if (symOut.isNotBlank()) ToolResultBlock(if (zh) "结果" else "Result", symOut, zh = zh, onCopy = { copyToClipboard(context, symOut, zh) })
                }
                "string" -> {
                    ToolMonoField(strAddr, { strAddr = it }, if (zh) "字符串地址 (0x…)" else "string addr", "0x1234", 46.dp)
                    ToolMonoField(strOld, { strOld = it }, if (zh) "当前字符串（CAS 校验，可空）" else "expected (CAS)", "旧内容", 46.dp)
                    ToolMonoField(strNew, { strNew = it }, if (zh) "新字符串" else "new text", "新内容", 46.dp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("null_pad" to (if (zh) "NUL 填充" else "NUL pad"), "space_pad" to (if (zh) "空格填充" else "space pad"), "none" to (if (zh) "不填充" else "none")).forEach { (k, l) ->
                            SmallAction(l, active = strPad == k) { strPad = k }
                        }
                    }
                    SmallAction(if (zh) "写入字符串" else "Apply", enabled = strAddr.isNotBlank() && strNew.isNotBlank()) {
                        pending = {
                            run({
                                callMcpTool(context, "taffy_native_patch_string", JSONObject()
                                    .put("workspaceId", ws).put("address", strAddr.trim()).put("newText", strNew)
                                    .put("expectedText", strOld).put("padMode", strPad))
                            }) { strOut = it }
                        }
                    }
                    if (strOut.isNotBlank()) ToolResultBlock(if (zh) "结果" else "Result", strOut, zh = zh, onCopy = { copyToClipboard(context, strOut, zh) })
                }
                else -> {
                    ToolMonoField(insAddr, { insAddr = it }, if (zh) "指令地址 (0x…)" else "insn addr", "0x1234", 46.dp)
                    ToolMonoField(insOld, { insOld = it }, if (zh) "当前字节 hex（CAS，强烈建议填）" else "expected hex (CAS)", "D2800540", 46.dp)
                    ToolMonoField(insAsm, { insAsm = it }, if (zh) "新汇编（; 分隔多条）" else "new asm", "mov x0, #0; ret", 64.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallAction("Thumb", active = insThumb) { insThumb = !insThumb }
                        SmallAction(if (zh) "NOP 填充" else "NOP fill", enabled = insAddr.isNotBlank()) {
                            pending = {
                                run({
                                    callMcpTool(context, "taffy_native_patch_instructions", JSONObject()
                                        .put("workspaceId", ws).put("address", insAddr.trim()).put("asm", "nop")
                                        .put("expectedHex", insOld).put("thumb", insThumb))
                                }) { insOut = it }
                            }
                        }
                        SmallAction(if (zh) "重汇编并写入" else "Apply", enabled = insAddr.isNotBlank() && insAsm.isNotBlank()) {
                            pending = {
                                run({
                                    callMcpTool(context, "taffy_native_patch_instructions", JSONObject()
                                        .put("workspaceId", ws).put("address", insAddr.trim()).put("asm", insAsm)
                                        .put("expectedHex", insOld).put("thumb", insThumb))
                                }) { insOut = it }
                            }
                        }
                    }
                    if (insOut.isNotBlank()) ToolResultBlock(if (zh) "结果" else "Result", insOut, zh = zh, onCopy = { copyToClipboard(context, insOut, zh) })
                }
            }

            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(cs.surfaceContainerHigh)
                    .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(if (zh) "写入约束" else "Write constraints", style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                MonoLine(if (zh) "· 改名：优先复用字符串表内同名/等长覆盖/尾部空闲区追加，布局不允许时返回 UNSUPPORTED_LAYOUT。" else "· rename: reuse/shorten/in-place append; UNSUPPORTED_LAYOUT otherwise.", cs.onSurface, AppText.label)
                MonoLine(if (zh) "· 字符串：新内容更长会覆盖后续数据，请先确认空间（参照 Exbin 提示）。" else "· string: longer text overwrites following data — verify space first.", cs.onSurface, AppText.label)
                MonoLine(if (zh) "· 指令：重汇编后按 CAS 校验原字节，不匹配则拒绝写入，避免破坏后续指令。" else "· insn: CAS-checked; mismatched bytes are refused.", cs.onSurface, AppText.label)
            }
        }
    }

    val p = pending
    if (p != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (zh) "确认写入？" else "Confirm write?") },
            text = { Text(if (zh) "该操作会修改工作区中的 SO 副本，可能不可逆。确认继续？" else "This modifies the workspace SO copy and may be irreversible. Continue?") },
            confirmButton = { TextButton(onClick = { p(); pending = null }) { Text(if (zh) "写入" else "Write", color = cs.error) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
}

// ───────────────────────── 全局伪 C（批量反编译） ─────────────────────────

@Composable
private fun GlobalPseudoCView(tools: ToolPagesState, zh: Boolean, context: android.content.Context, onRefresh: () -> Unit) {
    val ws = tools.sharedWorkspaceId
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf("5") }
    var startIndex by remember { mutableStateOf("0") }
    var filter by remember { mutableStateOf("") }
    var outFile by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var okCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }

    fun gen() {
        if (ws.isBlank()) return
        scope.launch {
            loading = true; error = ""; text = ""
            val r = callMcpTool(context, "taffy_so_pseudoc_batch", JSONObject()
                .put("workspaceId", ws)
                .put("count", count.toIntOrNull()?.coerceIn(1, 30) ?: 5)
                .put("startIndex", startIndex.toIntOrNull()?.coerceIn(0, 100000) ?: 0)
                .put("filter", filter.trim())
                .put("outFile", outFile.trim()))
            loading = false
            if (r == null) { error = if (zh) "引擎未就绪" else "engine not ready"; return@launch }
            if (!r.optBoolean("ok", true)) { error = r.optString("error").ifBlank { r.optString("note") }; return@launch }
            okCount = r.optInt("succeeded", r.optInt("okCount", 0))
            failCount = r.optInt("failed", r.optInt("failCount", 0))
            text = r.optString("combined").ifBlank { r.optString("text") }.ifBlank {
                r.optString("outFile").let { if (it.isNotBlank()) (if (zh) "已写入文件：$it" else "written to $it") else "" }
            }
            if (text.isBlank()) error = if (zh) "未生成内容（可能全部函数失败）" else "nothing generated"
        }
    }

    if (ws.isBlank()) return NeedWorkspace(zh)

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(if (zh) "生成伪C" else "Generate", loading = loading, onClick = { gen() })
            SmallAction(if (zh) "复制" else "Copy", enabled = text.isNotBlank()) { copyToClipboard(context, text, zh) }
            if (okCount > 0 || failCount > 0) {
                TypeBadge(if (zh) "成功 $okCount" else "ok $okCount", cs.primary)
                if (failCount > 0) TypeBadge(if (zh) "失败 $failCount" else "fail $failCount", cs.error)
            }
            SmallAction(if (zh) "刷新" else "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = count, onValueChange = { count = it }, singleLine = true,
                modifier = Modifier.width(84.dp).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "个数" else "count", fontSize = AppText.label) },
            )
            OutlinedTextField(
                value = startIndex, onValueChange = { startIndex = it }, singleLine = true,
                modifier = Modifier.width(84.dp).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "起始" else "start", fontSize = AppText.label) },
            )
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(AppShape.sm),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                label = { Text(if (zh) "函数名过滤" else "filter", fontSize = AppText.label) },
                placeholder = { Text("JNI_", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
            )
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = outFile, onValueChange = { outFile = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(AppShape.sm),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
            label = { Text(if (zh) "输出文件名（可选，写入 pseudoc-out/）" else "out file (optional)", fontSize = AppText.label) },
            placeholder = { Text("all_functions.c", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), color = cs.onSurfaceVariant) },
        )
        Spacer(Modifier.size(8.dp))
        when {
            loading -> AnalysisLoading()
            error.isNotBlank() -> AnalysisErrorBanner(error)
            text.isBlank() -> AnalysisEmptyState(
                title = if (zh) "全局伪 C" else "Global pseudo-C",
                hint = if (zh) "批量把多个函数反编译为伪 C（默认前 5 个，上限 30），用于快速通读 SO 主要逻辑；可用函数名子串过滤，或指定文件名把结果合并落盘。"
                    else "Batch-decompile functions to pseudo-C (default 5, max 30) to read a SO quickly; filter by name or write merged output to a file.",
                primaryLabel = if (zh) "生成前 5 个" else "First 5", onPrimary = { gen() },
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolResultBlock(if (zh) "合并伪 C" else "Merged pseudo-C", text, zh = zh, onCopy = { copyToClipboard(context, text, zh) })
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHigh)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MonoLine(if (zh) "· 单个函数失败不会中断整体（结果里会标注失败项）。" else "· A failing function does not abort the batch.", cs.onSurface, AppText.label)
                    MonoLine(if (zh) "· 需要 rizin-ghidra 后端支持 pdg；不可用时个别函数会失败。" else "· Requires the rizin-ghidra (pdg) backend.", cs.onSurface, AppText.label)
                }
            }
        }
    }
}

// ───────────────────────── 字符串详情弹窗（对齐 Exbin §4.6 dialog_string_detail） ─────────────────────────

@Composable
private fun StringDetailDialog(
    row: AnalysisRow,
    ws: String,
    zh: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var refs by remember(row) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loadingRefs by remember(row) { mutableStateOf(false) }

    LaunchedEffect(row, ws) {
        if (ws.isBlank() || row.va.isBlank()) return@LaunchedEffect
        loadingRefs = true
        refs = withContext(Dispatchers.IO) {
            runCatching {
                parseRzArray(EngineProvider.get(context).rzCommand(ws, "", "s ${row.va}; axtj")) ?: emptyList()
            }.getOrDefault(emptyList())
        }
        loadingRefs = false
    }

    val fullText = row.text
    val hexBytes = remember(row) {
        fullText.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(AppShape.lg))
                .background(cs.surfaceContainerHigh)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.lg))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.DataObject, null, tint = cs.primary, modifier = Modifier.size(15.dp))
                Text(if (zh) "字符串详情" else "String detail", style = MaterialTheme.typography.bodySmall, fontSize = AppText.bodyStrong, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(if (zh) "关闭" else "Close", fontSize = AppText.label) }
            }
            MonoLine(row.va.ifBlank { "--" } + (row.meta.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), cs.onSurfaceVariant, AppText.label)

            DetailBlock(if (zh) "完整字符串" else "Full string", fullText.ifBlank { "—" })
            DetailBlock(if (zh) "原始字节 (hex)" else "Raw bytes (hex)", hexBytes.ifBlank { "—" })

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (loadingRefs) (if (zh) "被引用位置（加载中…）" else "References (loading…)")
                    else (if (zh) "被引用位置 (${refs.size})" else "References (${refs.size})"),
                    style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant,
                )
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(AppShape.md))
                        .background(cs.surfaceContainerHighest)
                        .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (refs.isEmpty()) MonoLine(if (zh) "（无引用 / 未分析）" else "(none)", cs.onSurfaceVariant, AppText.label)
                    refs.take(10).forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            MonoLine(r.optString("from"), cs.primary, AppText.label)
                            val ty = r.optString("type")
                            if (ty.isNotBlank()) TypeBadge(ty, cs.onSurfaceVariant)
                            val op = r.optString("opcode")
                            if (op.isNotBlank()) Text(op, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), fontSize = AppText.label, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction(if (zh) "复制字符串" else "Copy string", onClick = { copyToClipboard(context, fullText, zh) })
                SmallAction(if (zh) "复制 hex" else "Copy hex", onClick = { copyToClipboard(context, hexBytes, zh) })
                SmallAction(if (zh) "复制地址" else "Copy addr", enabled = row.va.isNotBlank(), onClick = { copyToClipboard(context, row.va, zh) })
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontSize = AppText.label, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(cs.surfaceContainerHighest)
                .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(body, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontSize = AppText.label, color = cs.onSurface, lineHeight = 15.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SCC 图形画布（自绘，对齐 Exbin §6.5 GlobalXRefFragment 的「SCC 鸟瞰」）
//  缩点 → 分层布局 → Compose Canvas 绘制；支持拖动/缩放/点选。
// ═══════════════════════════════════════════════════════════════════════════

private data class SccNode(val id: Int, val members: List<String>, val level: Int, var x: Float = 0f, var y: Float = 0f)

@Composable
private fun SccGraphCanvas(
    comps: List<List<String>>,
    edges: List<Pair<String, String>>,
    zh: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // 1) 缩点：节点名 → 分量 id
    val owner = HashMap<String, Int>()
    comps.forEachIndexed { i, c -> c.forEach { owner[it] = i } }
    val nodes = comps.mapIndexed { i, c -> SccNode(i, c, 0) }
    // 2) 缩点边（去自环、去重）
    val ceSet = LinkedHashSet<Pair<Int, Int>>()
    edges.forEach { (f, t) ->
        val a = owner[f] ?: return@forEach
        val b = owner[t] ?: return@forEach
        if (a != b) ceSet.add(a to b)
    }
    val ce = ceSet.toList()
    // 3) 拓扑分层（Kahn）
    run {
        val indeg = HashMap<Int, Int>()
        nodes.forEach { indeg[it.id] = 0 }
        ce.forEach { indeg[it.second] = (indeg[it.second] ?: 0) + 1 }
        val level = HashMap<Int, Int>()
        nodes.forEach { level[it.id] = 0 }
        var guard = 0
        var changed = true
        while (changed && guard++ < comps.size + 5) {
            changed = false
            ce.forEach { (a, b) ->
                val la = level[a] ?: 0
                val lb = level[b] ?: 0
                if (lb < la + 1 && (indeg[b] ?: 0) > 0) { level[b] = la + 1; changed = true }
            }
        }
        nodes.forEach { it.level = minOf(level[it.id] ?: 0, 12) }
    }
    // 4) 布局：层内水平等距，层间垂直等距
    val byLevel = nodes.groupBy { it.level }.toSortedMap()
    val cellW = 170f
    val cellH = 96f
    byLevel.forEach { (lvl, list) ->
        list.forEachIndexed { i, n -> n.x = i * cellW; n.y = lvl * cellH }
    }
    val maxCols = byLevel.values.maxOfOrNull { it.size } ?: 1
    val contentW = maxCols * cellW
    val contentH = (byLevel.keys.maxOrNull() ?: 0) * cellH + cellH

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf(-1) }
    val tm = rememberTextMeasurer()

    val nodeColor = cs.primary
    val cyclic = remember(comps) { comps.map { it.size > 1 } }

    Box(
        modifier
            .clip(RoundedCornerShape(AppShape.md))
            .background(cs.surfaceContainerHigh)
            .border(BorderStroke(1.dp, cs.outlineVariant), RoundedCornerShape(AppShape.md))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.4f, 3.0f)
                    offset += pan
                }
            }
            .pointerInput(nodes, scale, offset) {
                detectTapGestures { pos ->
                    val px = (pos.x - offset.x) / scale
                    val py = (pos.y - offset.y) / scale
                    val hit = nodes.firstOrNull { n ->
                        px >= n.x + 8f && px <= n.x + cellW - 26f && py >= n.y + 12f && py <= n.y + cellH - 34f
                    }
                    selected = hit?.id ?: -1
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ox = offset.x + 16f
            val oy = offset.y + 16f
            fun nx(x: Float) = ox + x * scale
            fun ny(y: Float) = oy + y * scale

            // 边
            ce.forEach { (a, b) ->
                val na = nodes[a]; val nb = nodes[b]
                if (na == null || nb == null) return@forEach
                val sx = nx(na.x + (cellW - 34f) * scale / 2f)
                val sy = ny(na.y + (cellH * scale) - 26f * scale)
                val ex = nx(nb.x + (cellW - 34f) * scale / 2f)
                val ey = ny(nb.y + 12f * scale)
                val path = Path().apply {
                    moveTo(sx, sy)
                    cubicTo(sx, (sy + ey) / 2f, ex, (sy + ey) / 2f, ex, ey)
                }
                drawPath(path, cs.onSurfaceVariant.copy(alpha = 0.42f), style = Stroke(width = 1.4f * scale))
                // 箭头
                val ang = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                val ax = ex - (kotlin.math.cos(ang) * 7.0 * scale).toFloat()
                val ay = ey - (kotlin.math.sin(ang) * 7.0 * scale).toFloat()
                val p2 = Path().apply {
                    moveTo(ex, ey)
                    lineTo(ax - (kotlin.math.sin(ang) * 4.0 * scale).toFloat(), ay + (kotlin.math.cos(ang) * 4.0 * scale).toFloat())
                    lineTo(ax + (kotlin.math.sin(ang) * 4.0 * scale).toFloat(), ay - (kotlin.math.cos(ang) * 4.0 * scale).toFloat())
                    close()
                }
                drawPath(p2, cs.onSurfaceVariant.copy(alpha = 0.6f))
            }
            // 节点
            nodes.forEach { n ->
                val x = nx(n.x + 8f)
                val y = ny(n.y + 12f)
                val w = (cellW - 34f) * scale
                val h = (cellH - 44f) * scale
                val sel = n.id == selected
                val cyc = cyclic.getOrElse(n.id) { false }
                val fill = when {
                    sel -> cs.primary.copy(alpha = 0.28f)
                    cyc -> cs.tertiary.copy(alpha = 0.20f)
                    else -> cs.surfaceContainerHighest
                }
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale, 10f * scale),
                )
                drawRoundRect(
                    color = if (sel) cs.primary else if (cyc) cs.tertiary else cs.outlineVariant,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale, 10f * scale),
                    style = Stroke(width = if (sel) 2f else 1f),
                )
                val label = "#${n.id} · ${n.members.size}"
                val tl = tm.measure(label, TextStyle(fontSize = (11 * scale).coerceIn(7f, 20f).sp, color = cs.onSurface))
                drawText(tl, topLeft = Offset(x + 7f * scale, y + 5f * scale))
                val first = n.members.firstOrNull()?.take(14) ?: ""
                if (first.isNotBlank()) {
                    val t2 = tm.measure(first, TextStyle(fontSize = (9 * scale).coerceIn(6f, 16f).sp, color = cs.onSurfaceVariant))
                    drawText(t2, topLeft = Offset(x + 7f * scale, y + 20f * scale))
                }
                if (cyc) {
                    val t3 = tm.measure("SCC", TextStyle(fontSize = (8 * scale).coerceIn(6f, 14f).sp, color = cs.tertiary))
                    drawText(t3, topLeft = Offset(x + w - 30f * scale, y + 5f * scale))
                }
            }
        }
        // 图例
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLine(if (zh) "拖动平移 · 双指缩放 · 点按选中" else "drag / pinch / tap", cs.onSurfaceVariant, AppText.label)
        }
    }
    LaunchedEffect(contentW, contentH) { /* 尺寸变化时保留当前手势状态 */ }
}
