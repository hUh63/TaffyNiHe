package com.soreverse.mcp

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 图形化组件地基（对标 Explorer So 的交互范式）。
 *
 * 目的：把原先散落在各页面的"命令输入框 + 大段文本输出 / 各写各的卡片"收敛为
 * 一套可复用的图形化原语，供 12 个工具页统一使用：
 *  - TerminalPane  统一终端输出面板（原先有 0xFF111111 / 0xFF0B0F14 / 半透明 surface 三套）
 *  - DataRow       统一数据行卡片（Exbin 的 item_detail 模板：chip + 主副标题 + 等宽元信息）
 *  - SearchCountBar 实时搜索 + "当前显示 / 总数" 计数条
 *  - ConfirmDialog 破坏性操作二次确认
 *  - ChoiceDialog  过滤器单选对话框
 *  - BusyOverlay   可取消的耗时操作覆盖层
 *  - TypeChip      左侧类型/标签徽标
 */
internal object TerminalColors {
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    val dim = Color(0xFF90A4AE)
    val prompt = Color(0xFF4DD0E1)
    val ok = Color(0xFF30D158)
    val err = Color(0xFFFF453A)
}

/** 复制到系统剪贴板（避免使用已废弃的 Compose Clipboard API）。 */
internal fun copyToClipboard(context: Context, text: String, label: String = "taffy") {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

/**
 * 统一终端输出面板：深色底 + 等宽字 + 可选中复制 + 顶部"复制/清空"。
 * 替代原先三套互不统一的终端框实现。
 */
@Composable
internal fun TerminalPane(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onClear: (() -> Unit)? = null,
    placeholder: String = "—",
    maxHeight: Dp = 320.dp,
) {
    val context = LocalContext.current
    val metrics = LocalUiMetrics.current
    val shape = RoundedCornerShape(metrics.controlRadius)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null || onClear != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(
                    onClick = { copyToClipboard(context, text) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (onClear != null) {
                    IconButton(
                        onClick = onClear,
                        enabled = text.isNotBlank(),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "清空",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = maxHeight)
                .clip(shape)
                .background(TerminalColors.bg)
                .border(1.dp, TerminalColors.fg.copy(alpha = 0.10f), shape)
                .padding(10.dp),
        ) {
            if (text.isBlank()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TerminalColors.dim,
                )
            } else {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TerminalColors.fg,
                    )
                }
            }
        }
    }
}

/** 左侧类型/标签徽标（对应 Exbin 列表项左端的返回类型 chip）。 */
@Composable
internal fun TypeChip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .clip(RoundedCornerShape(AppShape.xs))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 统一数据行卡片内容（放在 [GlassGroup] / [AppCard] 里使用）。
 * 点击 = 主动作，长按 = 次级动作（复制/更多），与 Exbin 一致。
 */
@Composable
internal fun DataRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val metrics = LocalUiMetrics.current
    val clickable = onClick != null || onLongClick != null
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (clickable) {
                    Modifier.pointerInput(onClick, onLongClick) {
                        detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { onLongClick?.invoke() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = metrics.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!meta.isNullOrBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Text(
                trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 实时搜索框 + "当前显示 / 总数" 计数条（对应 Exbin 的 et_search + tv_count）。 */
@Composable
internal fun SearchCountBar(
    query: String,
    onQueryChange: (String) -> Unit,
    shown: Int,
    total: Int,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                    }
                }
            },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "$shown / $total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
    }
}

/** 破坏性操作二次确认对话框（卸载/删除/覆盖/推送等）。 */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 过滤器单选对话框（对应 Exbin 的 setSingleChoiceItems 过滤）。 */
@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int? = null,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEachIndexed { i, opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppShape.sm))
                            .pointerInput(i) { detectTapGestures { onPick(i); onDismiss() } }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            opt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 可取消的耗时操作覆盖层（对应 Exbin 的 Md3ProgressDialog）。 */
@Composable
internal fun BusyOverlay(
    visible: Boolean,
    message: String,
    onCancel: (() -> Unit)? = null,
) {
    if (!visible) return
    Dialog(onDismissRequest = { onCancel?.invoke() }) {
        Surface(
            shape = RoundedCornerShape(AppShape.xl),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

/** 空态/错误态占位（页内覆盖说明，不跳转到别处）。 */
@Composable
internal fun InlineHint(
    text: String,
    modifier: Modifier = Modifier,
    tone: HintTone = HintTone.Neutral,
) {
    val color = when (tone) {
        HintTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        HintTone.Error -> MaterialTheme.colorScheme.error
        HintTone.Ok -> AppPalette.green
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

internal enum class HintTone { Neutral, Error, Ok }

/** 小尺寸等宽信息行（用于详情里的键值对）。 */
@Composable
internal fun MonoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
