package com.soreverse.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import com.soreverse.mcp.mcp.SchemaBuilder
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolClass
import com.soreverse.mcp.mcp.ToolHandler

// ============================================================
// 共享元数据：分类映射 / 分类标签 / 分类图标与配色
// 这些成员同时被 AnalysisWorkspace 的工具弹层复用（同包 internal）。
// ============================================================

/** 卫星分类 → MCP 工具分类的映射 */
internal val categoryMap = mapOf(
    "decompile" to setOf("decompile", "analyze", "read"),
    "unpack" to setOf("apk", "dynamic", "workspace", "search"),
    "soanalyze" to setOf("soanalyze", "analyze", "read", "workspace"),
    "emulate" to setOf("emulate"),
    "frida" to setOf("dynamic", "device"),
    "rebuild" to setOf("build", "edit", "session", "apk"),
    "logs" to null, // 全部
)

/** 分类中文名 */
internal val categoryLabelZh = mapOf(
    "workspace" to "工作区", "analyze" to "分析", "read" to "读取",
    "edit" to "编辑", "emulate" to "模拟", "search" to "搜索",
    "build" to "构建", "session" to "会话", "apk" to "APK",
    "system" to "系统", "meta" to "元信息", "lowlevel" to "底层",
    "diff" to "对比", "dynamic" to "动态", "mcp" to "通用",
    "file" to "文件", "archive" to "压缩包", "soanalyze" to "SO分析",
    "decompile" to "反编译", "dotnet" to "DotNET", "device" to "设备",
    "utility" to "工具",
)

/** 分类英文名 */
internal val categoryLabelEn = mapOf(
    "workspace" to "Workspace", "analyze" to "Analyze", "read" to "Read",
    "edit" to "Edit", "emulate" to "Emulate", "search" to "Search",
    "build" to "Build", "session" to "Session", "apk" to "APK",
    "system" to "System", "meta" to "Meta", "lowlevel" to "Low-level",
    "diff" to "Diff", "dynamic" to "Dynamic", "mcp" to "General",
    "file" to "File", "archive" to "Archive", "soanalyze" to "SO Analyze",
    "decompile" to "Decompile", "dotnet" to "DotNET", "device" to "Device",
    "utility" to "Utility",
)

internal fun categoryLabel(cat: String, zh: Boolean): String =
    (if (zh) categoryLabelZh[cat] else categoryLabelEn[cat]) ?: cat

/** 分类 → 图标（全部使用仓库既有 material-icons-extended 图标）。 */
internal fun mcpCategoryIcon(cat: String): ImageVector = when (cat) {
    "workspace" -> Icons.Filled.FolderOpen
    "analyze", "decompile", "soanalyze" -> Icons.Filled.Code
    "read", "file" -> Icons.Filled.Description
    "archive" -> Icons.Filled.FolderZip
    "edit" -> Icons.Filled.Edit
    "emulate", "lowlevel", "device", "dynamic" -> Icons.Filled.Memory
    "search" -> Icons.Filled.Search
    "build" -> Icons.Filled.Build
    "session" -> Icons.Filled.Link
    "apk" -> Icons.Filled.Inventory2
    "system" -> Icons.Filled.Settings
    "meta", "mcp" -> Icons.Filled.Info
    "diff" -> Icons.Filled.CompareArrows
    "dotnet" -> Icons.Filled.DataObject
    "utility" -> Icons.Filled.Terminal
    else -> Icons.Filled.Build
}

/** 分类 → 主题强调色（跟随深浅色主题）。 */
@Composable
internal fun mcpCategoryTint(cat: String): Color {
    val dark = isSystemInDarkTheme()
    val name = when (cat) {
        "analyze", "read", "decompile" -> "teal"
        "workspace", "build" -> "indigo"
        "search" -> "blue"
        "apk", "archive" -> "orange"
        "dynamic", "device", "emulate", "lowlevel" -> "purple"
        "edit", "diff" -> "yellow"
        "soanalyze", "file" -> "green"
        "system", "meta", "mcp", "utility", "session" -> "mono"
        else -> "blue"
    }
    return AppPalette.accent(name, dark)
}

// ============================================================
// 共享列表模型与渲染（MCP 工具页 + 工作台工具弹层共用）
// ============================================================

/**
 * 统一的工具列表项模型。
 *
 * [iconKey] 为空时按 [category] 取图标与配色；以 `tool:` 开头时使用工作台 console
 * 工具的专属图标（用前缀避免与同名分类冲突）。
 */
internal data class ToolListEntry(
    val id: String,
    val category: String,
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val iconKey: String = "",
    val keywords: String = "",
    val trailingIcon: ImageVector? = null,
) {
    val searchBlob: String
        get() = (id + " " + title + " " + (subtitle ?: "") + " " + (meta ?: "") + " " + category + " " + keywords).lowercase()
}

private val consoleIconAliases: Map<String, ImageVector> = mapOf(
    "tool:decompile" to Icons.Filled.Code,
    "tool:unpack" to Icons.Filled.LockOpen,
    "tool:soanalyze" to Icons.Filled.Memory,
    "tool:emulate" to Icons.Filled.FlashOn,
    "tool:frida" to Icons.Filled.MyLocation,
    "tool:rebuild" to Icons.Filled.Inventory2,
    "tool:editor" to Icons.Filled.Edit,
)

private fun entryIcon(e: ToolListEntry): ImageVector =
    consoleIconAliases[e.iconKey] ?: mcpCategoryIcon(e.category)

/** 按关键词过滤（匹配工具名、中英说明、分类、别名）；关键词为空时原样返回。 */
internal fun filterToolEntries(all: List<ToolListEntry>, query: String): List<ToolListEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return all
    return all.filter { it.searchBlob.contains(q) }
}

/** 顶部搜索框（圆角 16dp，占位「搜索工具 / Search tools」）。 */
@Composable
internal fun ToolSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(AppShape.lg),
        placeholder = {
            Text(
                "搜索工具 / Search tools",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/** 分组小标题：图标 + 分类名 + 数量，下接 GroupDivider。 */
@Composable
private fun CategoryHeader(cat: String, count: Int, zh: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                mcpCategoryIcon(cat),
                contentDescription = null,
                tint = mcpCategoryTint(cat),
                modifier = Modifier.size(14.dp),
            )
            Text(
                categoryLabel(cat, zh),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "· $count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun EntryTrailingIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 列表项：36dp 圆角图标 + 标题 + 中英说明 + meta，整行可点。 */
@Composable
private fun ToolRowCard(e: ToolListEntry, onPick: ((ToolListEntry) -> Unit)?) {
    val pick = onPick
    var rowClick: (() -> Unit)? = null
    if (pick != null) rowClick = { pick(e) }
    CardRow(
        title = e.title,
        subtitle = e.subtitle,
        meta = e.meta,
        icon = entryIcon(e),
        iconTint = mcpCategoryTint(e.category),
        trailing = { EntryTrailingIcon(e.trailingIcon) },
        onClick = rowClick,
    )
}

/**
 * 分组列表：无关键词时按分类分组（组标题行 + GroupDivider，隐藏空分组）；
 * 有关键词时平铺过滤结果。
 */
@Composable
internal fun ToolEntryList(
    entries: List<ToolListEntry>,
    query: String,
    zh: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onPick: ((ToolListEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (zh) "没有匹配的工具" else "No matching tools",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val searching = query.isNotBlank()
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = contentPadding) {
        if (searching) {
            items(entries, key = { it.id }) { e -> ToolRowCard(e, onPick) }
            item(key = "search-tail") { Spacer(Modifier.height(12.dp)) }
        } else {
            entries.groupBy { it.category }.toSortedMap().forEach { (cat, list) ->
                if (list.isNotEmpty()) {
                    item(key = "hdr-$cat") { CategoryHeader(cat, list.size, zh) }
                    items(list, key = { it.id }) { e ->
                        ToolRowCard(e, onPick)
                        GroupDivider()
                    }
                }
            }
            item(key = "grouped-tail") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** ToolHandler → 列表项。 */
internal fun toolEntryOf(handler: ToolHandler, zh: Boolean): ToolListEntry {
    val meta = handler.meta
    val clsLabel = when (meta.cls) {
        ToolClass.CORE -> if (zh) "核心" else "CORE"
        ToolClass.EXTRA -> if (zh) "扩展" else "EXTRA"
        ToolClass.META -> if (zh) "元信息" else "META"
    }
    val metaLine = buildString {
        append(categoryLabel(meta.category, zh))
        append(" · ")
        append(clsLabel)
        if (meta.heavy) {
            append(" · ")
            append(if (zh) "重型" else "heavy")
        }
    }
    val desc = listOf(meta.zh, meta.en).filter { it.isNotBlank() }.joinToString("  ·  ")
    return ToolListEntry(
        id = meta.name,
        category = meta.category,
        title = meta.name,
        subtitle = desc.ifBlank { null },
        meta = metaLine,
        keywords = "${meta.zh} ${meta.en} ${meta.name}",
        trailingIcon = Icons.Filled.ContentCopy,
    )
}

// ============================================================
// 分类筛选 / 参数表 / 工具详情
// ============================================================

/** 分类筛选 chips（含「全部」），单选。 */
@Composable
private fun CategoryFilterRow(
    selected: String?,
    zh: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(if (zh) "全部" else "All", fontSize = 11.sp) },
        )
        categoryMap.keys.forEach { key ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(if (selected == key) null else key) },
                label = { Text(categoryLabel(key, zh), fontSize = 11.sp) },
            )
        }
    }
}

/** 把工具 schema 的 properties 渲染成参数表。 */
@Composable
private fun ParamTable(schema: JSONObject, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val props = schema.optJSONObject("properties") ?: JSONObject()
    val required: Set<String> = schema.optJSONArray("required")?.let { arr ->
        (0 until arr.length()).map { arr.optString(it) }.toSet()
    } ?: emptySet()

    if (props.length() == 0) {
        Text(
            if (zh) "无参数" else "No parameters",
            style = MaterialTheme.typography.bodySmall,
            fontSize = AppText.label,
            color = cs.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        props.keys().forEach { key ->
            val p = props.optJSONObject(key) ?: return@forEach
            val type = p.optString("type").ifBlank { if (p.has("oneOf")) "oneOf" else "any" }
            val desc = p.optString("description").ifBlank { p.optString("desc") }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        key,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontSize = AppText.label,
                        color = cs.primary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        type,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = cs.onSurfaceVariant,
                    )
                    if (key in required) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (zh) "必填" else "required",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = cs.error,
                        )
                    }
                }
                if (desc.isNotBlank()) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = AppText.label,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 工具详情弹层：完整描述 + 参数表 + 复制工具名。 */
@Composable
private fun ToolDetailDialog(handler: ToolHandler, zh: Boolean, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val meta = handler.meta
    val schema = remember(handler) {
        runCatching { meta.schemaBuilder(SchemaBuilder) }.getOrNull() ?: JSONObject()
    }
    val clsLabel = when (meta.cls) {
        ToolClass.CORE -> if (zh) "核心" else "CORE"
        ToolClass.EXTRA -> if (zh) "扩展" else "EXTRA"
        ToolClass.META -> if (zh) "元信息" else "META"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                meta.name,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = AppText.title,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${categoryLabel(meta.category, zh)} · $clsLabel" + if (meta.heavy) (if (zh) " · 重型" else " · heavy") else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = AppText.label,
                    color = cs.onSurfaceVariant,
                )
                if (meta.zh.isNotBlank()) {
                    Text(meta.zh, style = MaterialTheme.typography.bodySmall, fontSize = AppText.label)
                }
                if (meta.en.isNotBlank()) {
                    Text(meta.en, style = MaterialTheme.typography.bodySmall, fontSize = AppText.label, color = cs.onSurfaceVariant)
                }
                GroupDivider()
                Text(
                    if (zh) "参数" else "Parameters",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppText.label,
                )
                ParamTable(schema, zh)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("taffy-tool", meta.name))
                    Toast.makeText(context, if (zh) "已复制：${meta.name}" else "Copied: ${meta.name}", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) { Text(if (zh) "复制工具名" else "Copy name") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (zh) "关闭" else "Close") }
        },
    )
}

// ============================================================
// MCP 工具列表页
// ============================================================

/**
 * MCP 工具列表页：顶部搜索 + 按分类分组的 Exbin 圆角卡片列表。
 * 点击一行复制工具名（便于粘贴到 MCP 客户端）。
 */
@Composable
internal fun McpToolListView(zh: Boolean, category: String?, onClose: () -> Unit) {
    val metrics = LocalUiMetrics.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // 分类筛选：从外部传入的卫星分类初始化，用户可在页内切换；null = 全部
    var picked by remember(category) { mutableStateOf(category) }
    // 点击一行 → 打开工具详情（参数表），不再是"点一下只复制个名字"
    var detail by remember { mutableStateOf<ToolHandler?>(null) }

    val base = remember(picked) {
        val all = ToolCatalog.ALL
        if (picked == null) all
        else {
            val allowed = categoryMap[picked]
            if (allowed == null) all else all.filter { it.meta.category in allowed }
        }
    }
    val entries = remember(base, zh) { base.map { toolEntryOf(it, zh) } }
    val shown = filterToolEntries(entries, query)

    val title = if (picked == null) {
        if (zh) "MCP 工具列表" else "MCP Tools"
    } else {
        if (zh) "${categoryLabelZh[picked] ?: picked} 工具" else "${categoryLabelEn[picked] ?: picked} Tools"
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = title,
            subtitle = if (zh) "共 ${shown.size} 个工具" else "${shown.size} tools",
            showBack = true,
            onBack = onClose,
        )
        ToolSearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(horizontal = metrics.pagePad),
        )
        Spacer(Modifier.height(6.dp))
        CategoryFilterRow(
            selected = picked,
            zh = zh,
            onSelect = { picked = it },
            modifier = Modifier.padding(horizontal = metrics.pagePad),
        )
        Spacer(Modifier.height(6.dp))
        ToolEntryList(
            entries = shown,
            query = query,
            zh = zh,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = metrics.pagePad, end = metrics.pagePad, bottom = 24.dp),
            onPick = { e -> detail = ToolCatalog.byName[e.id] },
        )
    }

    detail?.let { h -> ToolDetailDialog(h, zh) { detail = null } }
}
