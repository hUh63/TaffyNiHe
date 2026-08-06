package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ToolCatalog

/**
 * 塔菲逆核 工具页 — 把所有内置 MCP 工具按分类列给用户看,小白能看懂每个工具干嘛。
 * 数据来源 ToolCatalog.ALL(每个工具的中文/英文说明就是 meta.zh / meta.en)。
 *
 * @param toolCategory 若非 null，滚动定位到该分类位置（不过滤，仍显示所有工具）。
 */
@Composable
internal fun ToolsTab(t: UiText, settings: SettingsStore, toolCategory: String? = null, onBack: (() -> Unit)? = null) {
    val listState = rememberLazyListState()
    val metrics = LocalUiMetrics.current

    // 分类 → 中文标题 + 图标(顺序即展示顺序;未列出的分类归到"更多")
    val categoryMeta: Map<String, Pair<String, ImageVector>> = linkedMapOf(
        "workspace" to ((if (t.zh) "打开文件 / APK" else "Open / APK") to Icons.Filled.DataObject),
        "decompile" to ((if (t.zh) "反编译 / 反汇编" else "Decompile") to Icons.Filled.Code),
        "read" to ((if (t.zh) "查看 / 读取" else "Read") to Icons.Filled.Code),
        "search" to ((if (t.zh) "搜索" else "Search") to Icons.Filled.Memory),
        "analyze" to ((if (t.zh) "分析" else "Analysis") to Icons.Filled.Memory),
        "emulate" to ((if (t.zh) "模拟执行 (unidbg)" else "Emulate") to Icons.Filled.Memory),
        "dynamic" to ((if (t.zh) "动态插桩 (Frida)" else "Dynamic (Frida)") to Icons.Filled.Bolt),
        "edit" to ((if (t.zh) "补丁 / 编辑" else "Patch / Edit") to Icons.Filled.Bolt),
        "build" to ((if (t.zh) "构建 / 导出" else "Build") to Icons.Filled.Bolt),
        "session" to ((if (t.zh) "会话 / 事务" else "Session") to Icons.Filled.Bolt),
        "system" to ((if (t.zh) "系统 / 网关" else "System") to Icons.Filled.Extension),
        "meta" to ((if (t.zh) "工具信息" else "Meta") to Icons.Filled.Extension),
        "diff" to ((if (t.zh) "差异对比" else "Diff") to Icons.Filled.CompareArrows),
        "lowlevel" to ((if (t.zh) "底层 API" else "Low-level") to Icons.Filled.Terminal),
        "dotnet" to ((if (t.zh) "PE / .NET" else "PE / .NET") to Icons.Filled.Code),
        "file" to ((if (t.zh) "文件 / 文本" else "File / Text") to Icons.Filled.Folder),
        "archive" to ((if (t.zh) "压缩 / 解压" else "Archive") to Icons.Filled.FolderZip),
    )

    // 按 category 归组,顺序按 categoryMeta 出现顺序,其余归"更多"
    val grouped = ToolCatalog.ALL.groupBy { it.meta.category }

    // 构建所有可见分类列表（不做过滤，全部展示）
    val allCategories = mutableListOf<String>()
    val shownCategories = mutableSetOf<String>()
    for ((cat, _) in categoryMeta) {
        if (grouped.containsKey(cat)) {
            allCategories.add(cat)
            shownCategories.add(cat)
        }
    }
    val rest = grouped.filterKeys { it !in shownCategories }
    for ((cat, _) in rest) {
        allCategories.add(cat)
    }

    // 分类 → LazyColumn item index 映射（item 0 = header, 之后依次是各分类）
    val categoryToIndex = allCategories.mapIndexed { index, _ -> index + 1 }

    // 从首页卫星跳转时，滚动到对应分类位置
    LaunchedEffect(toolCategory, allCategories) {
        if (toolCategory != null) {
            val catIndex = allCategories.indexOf(toolCategory)
            if (catIndex >= 0) {
                listState.animateScrollToItem(catIndex + 1)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = metrics.pagePad, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
    ) {
        // 标题
        item(key = "header") {
            ScreenHeader(
                title = if (t.zh) "工具" else "Tools",
                subtitle = if (toolCategory != null) {
                    if (t.zh) "从首页跳转 · 滑动查看全部 ${ToolCatalog.ALL.size} 个工具" else "Jumped from home · scroll for all ${ToolCatalog.ALL.size} tools"
                } else {
                    if (t.zh) "共 ${ToolCatalog.ALL.size} 个工具 · AI 通过 MCP 自动调用" else "${ToolCatalog.ALL.size} tools · called by AI via MCP"
                },
                showBack = onBack != null,
                onBack = onBack,
            )
        }

        // 所有分类（不过滤）
        for (cat in allCategories) {
            item(key = "cat-$cat") {
                val meta = categoryMeta[cat]
                val title = meta?.first ?: cat
                val icon = meta?.second ?: Icons.Filled.Extension
                val tools = grouped[cat] ?: emptyList()
                ToolCategoryGroup(cat = cat, title = title, icon = icon, tools = tools, zh = t.zh)
            }
        }
    }
}

@Composable
private fun ToolCategoryGroup(
    cat: String,
    title: String,
    icon: ImageVector,
    tools: List<com.soreverse.mcp.mcp.ToolHandler>,
    zh: Boolean,
) {
    GlassGroup(title = title) {
        tools.forEachIndexed { i, tool ->
            if (i > 0) GroupDivider()
            ToolRow(
                name = tool.meta.name,
                desc = if (zh) tool.meta.zh else tool.meta.en,
                icon = icon,
            )
        }
    }
}

@Composable
private fun ToolRow(name: String, desc: String, icon: ImageVector) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
