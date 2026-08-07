package com.soreverse.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 卫星分类 → MCP 工具分类的映射 */
private val categoryMap = mapOf(
    "decompile" to setOf("analyze", "read"),
    "unpack" to setOf("build", "workspace", "search", "dynamic"),
    "soanalyze" to setOf("analyze", "read", "workspace"),
    "emulate" to setOf("emulate"),
    "frida" to setOf("dynamic"),
    "rebuild" to setOf("build", "edit", "session"),
    "logs" to null, // 全部
)

/** 分类中文名 */
private val categoryLabelZh = mapOf(
    "workspace" to "工作区", "analyze" to "分析", "read" to "读取",
    "edit" to "编辑", "emulate" to "模拟", "search" to "搜索",
    "build" to "构建", "session" to "会话", "apk" to "APK",
    "system" to "系统", "meta" to "元信息", "lowlevel" to "底层",
    "diff" to "对比", "dynamic" to "动态", "mcp" to "通用",
)

private val categoryLabelEn = mapOf(
    "workspace" to "Workspace", "analyze" to "Analyze", "read" to "Read",
    "edit" to "Edit", "emulate" to "Emulate", "search" to "Search",
    "build" to "Build", "session" to "Session", "apk" to "APK",
    "system" to "System", "meta" to "Meta", "lowlevel" to "Low-level",
    "diff" to "Diff", "dynamic" to "Dynamic", "mcp" to "General",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpToolListView(zh: Boolean, category: String?, onClose: () -> Unit) {
    // 获取所有 MCP 工具
    val allTools = ToolCatalog.ALL
    val filteredTools = if (category == null) allTools else {
        val allowedCategories = categoryMap[category]
        if (allowedCategories == null) allTools
        else allTools.filter { it.meta.category in allowedCategories }
    }
    // 按分类分组
    val grouped = filteredTools.groupBy { it.meta.category }.toSortedMap()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (category == null) (if (zh) "MCP 工具列表" else "MCP Tools")
                        else (if (zh) "${categoryLabelZh[category] ?: category} 工具" else "${categoryLabelEn[category] ?: category} Tools"),
                    )
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            grouped.forEach { (cat, tools) ->
                item {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        (if (zh) categoryLabelZh[cat] else categoryLabelEn[cat]) ?: cat,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(2.dp))
                }
                items(tools, key = { it.meta.name }) { handler ->
                    ToolCard(handler = handler, zh = zh)
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun ToolCard(handler: ToolHandler, zh: Boolean) {
    val meta = handler.meta
    val tag = when (meta.cls) {
        ToolClass.CORE -> if (zh) "核心" else "CORE"
        ToolClass.EXTRA -> if (zh) "扩展" else "EXTRA"
        ToolClass.META -> if (zh) "元" else "META"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(meta.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(2.dp))
            Text(
                if (zh) meta.zh else meta.en,
                style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
            )
        }
    }
}