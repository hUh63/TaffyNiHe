package com.soreverse.mcp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
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
                        ) { row -> copyToClipboard(context, row.text.ifBlank { row.title }, zh) }

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
) {
    if (ws.isBlank() || target.isBlank()) return
    tools.viewLoading = key
    val r = withContext(Dispatchers.IO) {
        runCatching { EngineProvider.get(context).rzDecompile(ws, "", target, false) }.getOrNull()
    }
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
    val cacheKey = "search|$ws|$scope|$query"

    LaunchedEffect(cacheKey, tools.reloadTick) {
        if (query.isNotBlank()) loadListCache(context, tools, scope, ws, cacheKey, 120, query)
    }

    val rows = remember(tools.viewCache[cacheKey]) { rowsOf(tools.viewCache[cacheKey], scope) }
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
                    val err = errMessageOf(tools.viewCache[cacheKey])
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
            SmallAction(if (zh) "函数列表" else "Functions", onClick = onGoFunctions)
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
    val key = "pseudo|$ws|$target"

    LaunchedEffect(key, tools.reloadTick) {
        if (tools.pseudoKey != key || tools.pseudoJson.isBlank()) {
            fetchPseudo(context, tools, zh, ws, target, key)
        }
    }

    val body = tools.pseudoJson
    val err = errMessageOf(body)
    val obj = remember(body) { runCatching { JSONObject(body) }.getOrNull() }
    val pseudo = remember(body) { obj?.optString("pseudocode").orEmpty() }
    val bounds = remember(body) { obj?.optJSONObject("functionBounds") }
    val coverage = remember(body) { obj?.optJSONObject("pseudocodeCoverage") }
    val typeInf = remember(body) { obj?.optJSONObject("typeInference") }
    val warn = obj?.optString("boundaryWarning").orEmpty()

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
            SmallAction(
                label = if (zh) "复制全部" else "Copy",
                enabled = pseudo.isNotBlank(),
                onClick = { copyToClipboard(context, pseudo, zh) },
            )
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
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = EngineProvider.get(context)
                    var sid = sessionId
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
