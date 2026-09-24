package com.soreverse.mcp.termview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 终端配色（与 app 侧 `GraphicKit.TerminalColors` 保持同值，便于逐步收敛到本模块）。
 */
object TerminalTheme {
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    val dim = Color(0xFF90A4AE)
    val prompt = Color(0xFF4DD0E1)
    val ok = Color(0xFF30D158)
    val err = Color(0xFFFF453A)
    val border = Color(0xFF1E2A36)
}

/**
 * 终端面板（对标 Xed-Editor 的 `terminal-view` 模块）：标题栏 + 等宽输出区（自动滚底、可选中复制）
 * + 内嵌输入行。
 *
 * 用法约束：**调用方必须给它一个有界高度**（例如 `Modifier.height(320.dp)`，或放在有高度约束的
 * Box 里）。它内部用 `weight(1f)` 分配输出区，放进垂直滚动容器会失去约束而报错。
 *
 * 纯展示组件：不持有进程会话；会话请在 `:terminal-emulator` 的 [com.soreverse.mcp.termemu.TerminalSession]
 * 里跑，把输出文本交给本组件渲染。
 */
@Composable
fun TerminalScreen(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    placeholder: String = "—",
    inputValue: String = "",
    onInputChange: (String) -> Unit = {},
    prompt: String = "$",
    inputPlaceholder: String = "",
    inputEnabled: Boolean = true,
    busy: Boolean = false,
    outputMaxHeight: Dp = 380.dp,
    onSend: () -> Unit = {},
    onClear: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        runCatching { scroll.scrollTo(scroll.maxValue) }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TerminalTheme.bg)
            .border(1.dp, TerminalTheme.fg.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null || onClear != null || onCopy != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TerminalTheme.dim,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onCopy != null) {
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTheme.dim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = text.isNotBlank()) { onCopy() }
                            .padding(horizontal = 5.dp),
                    )
                }
                if (onClear != null) {
                    Text(
                        "清屏",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTheme.dim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = text.isNotBlank()) { onClear() }
                            .padding(horizontal = 5.dp),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 64.dp, max = outputMaxHeight)
                .verticalScroll(scroll),
        ) {
            if (text.isBlank()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TerminalTheme.dim,
                )
            } else {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                        color = TerminalTheme.fg,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (inputEnabled) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    color = TerminalTheme.prompt,
                )
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            inputPlaceholder,
                            color = TerminalTheme.dim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TerminalTheme.fg,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TerminalTheme.bg,
                        unfocusedContainerColor = TerminalTheme.bg,
                        focusedBorderColor = TerminalTheme.prompt.copy(alpha = 0.5f),
                        unfocusedBorderColor = TerminalTheme.border,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
                IconButton(onClick = onSend, enabled = inputValue.isNotBlank() && !busy) {
                    Icon(Icons.Default.PlayArrow, null, tint = TerminalTheme.prompt)
                }
            }
        }
    }
}

/** 单行输出预览条（点击展开完整终端）。 */
@Composable
fun TerminalPreviewLine(
    lastLine: String,
    modifier: Modifier = Modifier,
    hint: String = "展开",
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TerminalTheme.bg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TerminalTheme.prompt,
        )
        Text(
            lastLine,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TerminalTheme.fg,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = TerminalTheme.dim,
        )
    }
}

/** 忙碌指示（终端内联用，避免和 app 的 BusyOverlay 重复）。 */
@Composable
fun TerminalBusyDot(modifier: Modifier = Modifier, label: String = "running…") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(TerminalTheme.ok))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TerminalTheme.dim,
        )
    }
}
