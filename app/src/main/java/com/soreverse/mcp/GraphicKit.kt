package com.soreverse.mcp

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 图形化组件地基 —— **全部基于 Material 3 官方组件**。
 *
 * 设计原则（详见 docs/exbin-ui-spec.md）：页面只允许使用 M3 官方组件，
 * 不再自绘外壳（禁止 `Box + background + border + clickable` 拼按钮/卡片/行）。
 * 本文件只保留 M3 尚无直接对应物的三样东西：
 *  - TerminalPane  终端/命令输出面板（深色底是终端语义，用 M3 [Surface] 承载）
 *  - MonoRow       键值行（等宽对齐，Exbin item_base_row 同款）
 *  - InlineHint    空态/错误态页内说明
 * 其余一律映射到官方组件：
 *  - TypeChip  → [AssistChip]
 *  - ToolChip  → [FilterChip]
 *  - DataRow   → [ListItem]
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
 * 终端输出面板：M3 [Surface] 承载深色等宽输出区 + 顶部"复制/清空"。
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = maxHeight),
            shape = shape,
            color = TerminalColors.bg,
            border = BorderStroke(1.dp, TerminalColors.fg.copy(alpha = 0.10f)),
        ) {
            Box(Modifier.fillMaxWidth().padding(10.dp)) {
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
}

/** 左侧类型/标签徽标 —— M3 [AssistChip]（无边框、容器着色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypeChip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.14f),
            labelColor = color,
        ),
        border = null,
    )
}

/** 筛选/切换 chip —— M3 [FilterChip]。 */
@Composable
internal fun ToolChip(
    label: String,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected || accent,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, null, modifier = Modifier.size(16.dp)) } },
        label = {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        },
    )
}

/**
 * 统一数据行 —— M3 [ListItem]。点击 = 主动作，长按 = 次级动作（复制/更多）。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val cs = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick?.let { cb -> { cb() } },
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = leading,
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (!subtitle.isNullOrBlank() || !meta.isNullOrBlank()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!meta.isNullOrBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else null,
        trailingContent = if (!trailingText.isNullOrBlank()) {
            {
                Text(
                    trailingText,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else null,
    )
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

/** 破坏性操作二次确认对话框（M3 [AlertDialog]）。 */
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
                    ListItem(
                        modifier = Modifier.clickable { onPick(i); onDismiss() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                opt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
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

/**
 * 空态插图（对齐太墟的空态插图设计）：双层柔光圆底 + 主题色图标。
 * 用于替代干巴巴的一行文字，让空态有"被设计过"的观感。
 */
@Composable
internal fun EmptyArt(
    icon: ImageVector = Icons.Filled.Info,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(88.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = cs.primary.copy(alpha = 0.75f), modifier = Modifier.size(30.dp))
        }
    }
}

/** 空态/错误态占位（页内覆盖说明，不跳转到别处）。 */
@Composable
internal fun InlineHint(
    text: String,
    modifier: Modifier = Modifier,
    tone: HintTone = HintTone.Neutral,
    icon: ImageVector? = null,
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
        if (icon != null) EmptyArt(icon = icon)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

internal enum class HintTone { Neutral, Error, Ok }

/** 小尺寸等宽信息行（用于详情里的键值对，Exbin item_base_row 同款）。 */
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

/**
 * 按键式「按住」修饰符（借鉴 Xed-Editor 的 `Modifier.holdable`）。
 *
 * 语义：
 *  - 点一下 → [onClick]
 *  - 按住超过 [initialDelayMillis] → [onLongClick]（返回 true 表示已消费，不再触发 onClick）
 *  - [repeatOnHold] = true 时，长按后按 [repeatDelayMillis] 间隔反复触发 [onClick]（方向键/退格连续移动）
 *
 * 用 `awaitEachGesture` 手写手势，避免 `combinedClickable` 无法做"按住连发"的限制。
 */
internal fun Modifier.holdable(
    enabled: Boolean = true,
    repeatOnHold: Boolean = false,
    initialDelayMillis: Long = 420L,
    repeatDelayMillis: Long = 70L,
    onLongClick: () -> Boolean = { false },
    onClick: () -> Unit = {},
): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    pointerInput(enabled, repeatOnHold) {
        if (!enabled) return@pointerInput
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var longTriggered = false
                var repeated = false
                val job = launch {
                    delay(initialDelayMillis)
                    longTriggered = currentOnLongClick()
                    if (repeatOnHold) {
                        repeated = true
                        while (true) {
                            currentOnClick()
                            delay(repeatDelayMillis)
                        }
                    }
                }
                // Main pass：一旦父级（如横向滚动的 LazyRow）消费了拖动，这里返回 null（手势取消），
                // 此时不再补一次单击 —— 修复“滑动软键行时会误输入”。
                val up = waitForUpOrCancellation()
                job.cancel()
                if (up != null && !longTriggered && !repeated) currentOnClick()
            }
        }
    }
}
