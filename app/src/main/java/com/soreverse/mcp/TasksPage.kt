package com.soreverse.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 任务页：围绕主文件（APK / SO）的持续分析记录 —— 当前任务 + 历史任务。
 *
 * 版式（v1.5.1 重做）：顶部一行标题 + 统计；每张任务卡是「类型图标 + 两行名称 + 状态胶囊 + 元信息条 + 底部操作条」，
 * 名称去掉 `picked_<hex>_` 前缀后完整展示（旧版把整串放进 maxLines=1 的标题里，只剩 `picked_a4323abd_libandr...`）。
 *
 * 「继续」的语义是**真的接着做**：重新打开任务记录里的主文件、重建共享工作区，再切回分析页，
 * 而不是只把状态改成 active。原文件被移动/删除，或 content:// 授权已过期时，卡片会直接给出
 * 原因并禁用「继续」，同时提供「重新选文件」入口。
 */
@Composable
internal fun TasksPage(
    t: UiText,
    state: WorkspaceState,
    resumingTaskId: String? = null,
    onContinueTask: (String) -> Unit,
    onGoAnalyze: () -> Unit = {},
) {
    val zh = t.zh
    val active = state.tasks.filter { it.status == "active" }
    val done = state.tasks.filter { it.status != "active" }
    var pendingClear by remember { mutableStateOf<TaskRecord?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (zh) "任务" else "Tasks",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (zh) "围绕同一个文件的分析记录，随时接着做"
                        else "Analysis records that you can resume anytime",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                CountPill(
                    text = if (zh) "进行中 ${active.size} · 历史 ${done.size}"
                    else "${active.size} active · ${done.size} history",
                )
            }
        }
        if (active.isEmpty() && done.isEmpty()) {
            item { EmptyTasks(zh, onGoAnalyze) }
        }
        if (active.isNotEmpty()) {
            item { SectionLabel(if (zh) "当前任务" else "Active", active.size) }
            items(active, key = { it.id }) { task ->
                TaskCard(
                    task = task, zh = zh, resuming = resumingTaskId == task.id,
                    onContinue = { onContinueTask(task.id) },
                    onClear = { pendingClear = task },
                    onGoAnalyze = onGoAnalyze,
                )
            }
        }
        if (done.isNotEmpty()) {
            item { SectionLabel(if (zh) "历史任务" else "History", done.size) }
            items(done, key = { it.id }) { task ->
                TaskCard(
                    task = task, zh = zh, resuming = resumingTaskId == task.id,
                    onContinue = { onContinueTask(task.id) },
                    onClear = { pendingClear = task },
                    onGoAnalyze = onGoAnalyze,
                )
            }
        }
    }

    pendingClear?.let { target ->
        ConfirmDialog(
            title = if (zh) "删除任务记录？" else "Delete task record?",
            message = if (zh) "将删除「${taskDisplayName(target)}」的任务记录，此操作不可恢复（不会动原文件）。"
            else "This permanently deletes the record of \"${taskDisplayName(target)}\". The original file is not touched.",
            confirmText = if (zh) "删除" else "Delete",
            onConfirm = { state.deleteTask(target.id); pendingClear = null },
            onDismiss = { pendingClear = null },
        )
    }
}

@Composable
private fun CountPill(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(AppShape.pill),
        color = cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            fontSize = 10.5.sp,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(AppShape.pill),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                "$count",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyTasks(zh: Boolean, onGoAnalyze: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📭", style = MaterialTheme.typography.displaySmall)
        Text(if (zh) "还没有任务" else "No tasks yet", style = MaterialTheme.typography.titleMedium)
        Text(
            if (zh) "在分析页选择一个 APK / SO 就会自动生成任务记录，之后可以随时回到这里继续。"
            else "Pick an APK / SO in the analysis page to create a task record; come back here anytime to continue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        PrimaryActionButton(if (zh) "去分析页选文件" else "Pick a file", onClick = onGoAnalyze)
    }
}

/** `picked_a4323abd_libandroidx.graphics.path.so` → `libandroidx.graphics.path.so`。 */
private fun taskDisplayName(task: TaskRecord): String {
    val raw = task.title.ifBlank { task.mainName }
    val cleaned = raw.replace(Regex("^picked_[0-9a-fA-F]{6,12}_"), "")
    return cleaned.ifBlank { raw }
}

@Composable
private fun TaskCard(
    task: TaskRecord,
    zh: Boolean,
    resuming: Boolean,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onGoAnalyze: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val info = remember(task.mainPath) { taskFileInfo(task.mainPath, zh) }
    val display = remember(task.id, task.title, task.mainName) { taskDisplayName(task) }
    val kindIcon: ImageVector = when (task.kind) {
        "apk" -> Icons.Filled.Inventory2
        "so" -> Icons.Filled.Memory
        else -> Icons.Filled.Description
    }
    val kindLabel = when (task.kind) {
        "apk" -> "APK"
        "so" -> "SO"
        else -> "MIX"
    }
    val kindColor: Color = when (task.kind) {
        "apk" -> cs.primary
        "so" -> cs.tertiary
        else -> cs.secondary
    }
    val isActive = task.status == "active"

    Surface(
        shape = RoundedCornerShape(AppShape.md),
        color = cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 类型图标块
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(kindColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kindIcon, contentDescription = null, tint = kindColor, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        display,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (task.mainPath.startsWith("content://") || task.mainPath.startsWith("http"))
                            (if (zh) "外部文档（URI 引用）" else "External document (URI)")
                        else task.mainPath.substringAfterLast('/').ifBlank { task.mainPath },
                        fontSize = 10.5.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(
                    text = if (isActive) (if (zh) "进行中" else "Active") else (if (zh) "已完成" else "Done"),
                    active = isActive,
                )
            }
            // 元信息条
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(kindLabel, kindColor)
                MetaChip(info.size)
                MetaChip(info.stateText)
                MetaChip(if (zh) "更新 ${formatTaskTime(task.updatedAt)}" else "upd ${formatTaskTime(task.updatedAt)}")
            }
            info.hint?.let {
                Box(Modifier.padding(top = 8.dp)) {
                    InlineHint(it, tone = if (info.resumable) HintTone.Neutral else HintTone.Error)
                }
            }
            HorizontalDivider(
                Modifier.padding(top = 9.dp),
                color = cs.outlineVariant.copy(alpha = 0.45f),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(AppShape.sm),
                ) { Text(if (zh) "清除" else "Clear", fontSize = 12.sp) }
                if (!info.resumable) {
                    OutlinedButton(
                        onClick = onGoAnalyze,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(AppShape.sm),
                    ) { Text(if (zh) "重新选文件" else "Re-pick", fontSize = 12.sp) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onContinue,
                    enabled = info.resumable && !resuming,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(AppShape.sm),
                ) {
                    if (resuming) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(if (zh) "继续分析" else "Continue", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(AppShape.pill),
        color = if (active) cs.primary.copy(alpha = 0.14f) else cs.surfaceContainerHighest,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) cs.primary else cs.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun MetaChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (text.isBlank()) return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 任务主文件的可继续性判定：绝对路径按文件系统核对，URI 交给引擎（授权过期时会在打开时报错）。 */
private class TaskFileInfo(
    val size: String,
    val stateText: String,
    val resumable: Boolean,
    val hint: String?,
)

private fun taskFileInfo(path: String, zh: Boolean): TaskFileInfo {
    val p = path.trim()
    if (p.isBlank()) {
        return TaskFileInfo("--", if (zh) "无路径" else "no path", true,
            if (zh) "该任务未记录主文件路径，继续后请在分析页重新选择文件" else "No recorded main file; pick one after continuing")
    }
    if (p.startsWith("content://") || p.startsWith("http")) {
        return TaskFileInfo("--", if (zh) "URI 引用" else "URI", true, null)
    }
    val f = runCatching { File(p) }.getOrNull()
    if (f == null || !f.exists() || !f.isFile) {
        return TaskFileInfo("--", if (zh) "文件已失效" else "missing", false,
            if (zh) "原文件已不存在，无法继续；可用「重新选文件」指定新位置" else "The original file is gone; use Re-pick to point at a new one")
    }
    return TaskFileInfo(taskSizeText(f.length()), if (zh) "文件就绪" else "ready", true, null)
}

private fun taskSizeText(n: Long): String = when {
    n >= (1L shl 20) -> "%.1f MB".format(n / 1048576.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

private fun formatTaskTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}
