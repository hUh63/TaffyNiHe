@file:OptIn(ExperimentalMaterial3Api::class)

package com.soreverse.mcp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.SurfaceNode
import com.soreverse.mcp.core.SurfaceSpec
import com.soreverse.mcp.core.SurfaceTypes
import org.json.JSONArray
import org.json.JSONObject

/**
 * A2UI 渲染器：把 [SurfaceSpec]（模型输出的声明式界面描述）画成原生 M3 组件。
 *
 * 设计约束：
 * 1. 只用 M3 官方组件与主题 token（MaterialTheme.colorScheme / typography），不自造颜色；
 * 2. 所有文本都有 maxLines + Ellipsis，绝不把气泡撑爆；
 * 3. table / code 横向可滚动（手机上 monospace 长行必须能拖）；
 * 4. 未知 type 直接跳过（解析器已过滤，这里兜底），渲染器永不抛异常。
 *
 * 动作回传：button 点击 -> onAction(action, payload)，由宿主决定怎么处理（导出、重跑工具等）。
 */

/** 表格列宽启发式：按最长单元格字符数估宽（等宽字体下每字符约 8dp）。 */
private const val CELL_CHAR_DP: Int = 8
private const val CELL_MIN_DP: Int = 72
private const val CELL_MAX_DP: Int = 260
private const val CELL_MAX_CHARS: Int = 28
private const val ROW_LIMIT: Int = 200
private const val LIST_HEIGHT_MAX_DP: Int = 320

@Composable
fun A2UiRenderer(
    spec: SurfaceSpec,
    modifier: Modifier = Modifier,
    onAction: (String, JSONObject) -> Unit = { _, _ -> },
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val title = spec.title
        if (title != null && title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
        SurfaceNodeView(node = spec.root, onAction = onAction)
    }
}

/** 单节点渲染。递归深度由 SurfaceParser 的 maxDepth 兜住。 */
@Composable
internal fun SurfaceNodeView(node: SurfaceNode, onAction: (String, JSONObject) -> Unit) {
    val props = node.props
    when (node.type) {
        SurfaceTypes.CARD -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val cardTitle = props.txt("title")
                if (cardTitle.isNotBlank()) {
                    Text(
                        text = cardTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                for (child in node.children) {
                    SurfaceNodeView(node = child, onAction = onAction)
                }
            }
        }

        SurfaceTypes.TEXT -> Text(
            text = props.txt("value"),
            style = MaterialTheme.typography.bodyMedium,
            color = toneColor(props.txt("tone")),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 20,
            overflow = TextOverflow.Ellipsis,
        )

        SurfaceTypes.KEYVALUE -> {
            val rows = props.optJSONArray("rows") ?: JSONArray()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until rows.length().coerceAtMost(ROW_LIMIT)) {
                    val row = rows.optJSONArray(i)
                    if (row != null && row.length() > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = cellText(row, 0),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 64.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = cellText(row, 1),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        SurfaceTypes.TABLE -> {
            val columns = props.optJSONArray("columns") ?: JSONArray()
            val rows = props.optJSONArray("rows") ?: JSONArray()
            val widths = remember(node) { columnWidths(columns, rows) }
            val hScroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                Column {
                    Row(Modifier.padding(vertical = 4.dp)) {
                        for (c in widths.indices) {
                            Text(
                                text = cellText(columns, c),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(widths[c].dp).padding(end = 14.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    for (r in 0 until rows.length().coerceAtMost(ROW_LIMIT)) {
                        val row = rows.optJSONArray(r)
                        if (row != null) {
                            Row(Modifier.padding(vertical = 4.dp)) {
                                for (c in widths.indices) {
                                    Text(
                                        text = cellText(row, c),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.width(widths[c].dp).padding(end = 14.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SurfaceTypes.CODE -> {
            val hScroll = rememberScrollState()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(Modifier.fillMaxWidth().horizontalScroll(hScroll).padding(10.dp)) {
                    Text(
                        text = props.txt("value"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 60,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SurfaceTypes.CHIPS -> {
            val items = props.optJSONArray("items") ?: JSONArray()
            val hScroll = rememberScrollState()
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(hScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until items.length().coerceAtMost(50)) {
                    AssistChip(
                        onClick = { },
                        label = {
                            Text(
                                text = cellText(items, i),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        SurfaceTypes.ALERT -> {
            val tone = props.txt("tone")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = alertContainer(tone),
            ) {
                Text(
                    text = props.txt("text"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = toneColor(tone),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        SurfaceTypes.PROGRESS -> {
            val raw = props.optDouble("value", 0.0)
            val fraction = (if (raw > 1.0) raw / 100.0 else raw).coerceIn(0.0, 1.0).toFloat()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = props.txt("label")
                if (label.isNotBlank()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SurfaceTypes.BUTTON -> {
            val action = props.txt("action")
            val payload = props.optJSONObject("payload") ?: JSONObject()
            val label = props.txt("label").ifBlank { action.ifBlank { "action" } }
            OutlinedButton(onClick = { onAction(action, payload) }) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        SurfaceTypes.LIST -> {
            val items = props.optJSONArray("items") ?: JSONArray()
            val rows = mutableListOf<JSONObject>()
            for (i in 0 until items.length().coerceAtMost(ROW_LIMIT)) {
                val item = items.optJSONObject(i)
                if (item != null) rows.add(item)
            }
            if (rows.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_HEIGHT_MAX_DP.dp)) {
                    items(rows.size) { index ->
                        val item = rows[index]
                        val subtitle = item.txt("subtitle")
                        val meta = item.txt("meta")
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = item.txt("title"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            trailingContent = {
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        SurfaceTypes.DIVIDER -> HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        else -> Unit
    }
}

/** tone -> 前景色：只映射到主题 token。 */
@Composable
private fun toneColor(tone: String): Color = when (tone.trim().lowercase()) {
    "muted", "dim" -> MaterialTheme.colorScheme.onSurfaceVariant
    "warn", "warning", "error" -> MaterialTheme.colorScheme.error
    "ok", "success" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

/** alert 的容器色：同样只用主题 token。 */
@Composable
private fun alertContainer(tone: String): Color = when (tone.trim().lowercase()) {
    "warn", "warning", "error" -> MaterialTheme.colorScheme.errorContainer
    "ok", "success" -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

/** 取 props 里的任意标量为字符串（数字/布尔转字符串，null/缺失为空串）。 */
private fun JSONObject.txt(name: String): String {
    val value = opt(name)
    return if (value == null || value == JSONObject.NULL) "" else value.toString()
}

/** 取数组元素的字符串形式（表头/单元格/芯片）。 */
private fun cellText(array: JSONArray, index: Int): String {
    if (index < 0 || index >= array.length()) return ""
    val value = array.opt(index)
    return if (value == null || value == JSONObject.NULL) "" else value.toString()
}

/**
 * 列宽：等宽字体下按最长内容估宽，保证同一列所有行对齐（各 Row 用同一份宽度），
 * 同时内容短的列不会被撑得过宽。
 */
private fun columnWidths(columns: JSONArray, rows: JSONArray): IntArray {
    var cols = columns.length()
    val rowCount = rows.length().coerceAtMost(ROW_LIMIT)
    for (r in 0 until rowCount) {
        val row = rows.optJSONArray(r)
        if (row != null && row.length() > cols) cols = row.length()
    }
    if (cols <= 0) return IntArray(0)
    val widths = IntArray(cols)
    for (c in 0 until cols) {
        var maxChars = cellText(columns, c).length
        for (r in 0 until rowCount) {
            val row = rows.optJSONArray(r)
            if (row != null) {
                val len = cellText(row, c).length
                if (len > maxChars) maxChars = len
            }
        }
        val estimate = maxChars.coerceAtMost(CELL_MAX_CHARS) * CELL_CHAR_DP + 24
        widths[c] = estimate.coerceIn(CELL_MIN_DP, CELL_MAX_DP)
    }
    return widths
}
