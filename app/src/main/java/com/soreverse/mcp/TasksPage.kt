package com.soreverse.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 任务页：围绕主文件（APK / SO）的持续分析记录 —— 当前任务 + 历史任务。
 *
 * 「继续」的语义是**真的接着做**：重新打开任务记录里的主文件、重建共享工作区，再切回分析页，
 * 而不是只把状态改成 active。原文件被移动/删除，或 content:// 授权已过期时，卡片会直接给出
 * 原因并禁用「继续」，同时提供「重新选文件」入口，避免点了之后没有任何反应。
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
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (zh) "任务" else "Tasks",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (zh) "进行中 ${active.size} · 历史 ${done.size}" else "${active.size} active · ${done.size} history",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            message = if (zh) "将删除「${target.title.ifBlank { target.mainName }}」的任务记录，此操作不可恢复（不会动原文件）。"
            else "This permanently deletes the record of \"${target.title.ifBlank { target.mainName }}\". The original file is not touched.",
            confirmText = if (zh) "删除" else "Delete",
            onConfirm = { state.deleteTask(target.id); pendingClear = null },
            onDismiss = { pendingClear = null },
        )
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyTasks(zh: Boolean, onGoAnalyze: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 20.dp, end = 20.dp),
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
    Surface(
        shape = RoundedCornerShape(AppShape.md),
        color = cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    task.title.ifBlank { task.mainName },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TypeChip(when (task.kind) {
                    "apk" -> "APK"
                    "so" -> "SO"
                    else -> "MIX"
                })
            }
            Text(
                task.mainName,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val statusText = if (task.status == "active") (if (zh) "进行中" else "active") else (if (zh) "已完成" else "done")
            Text(
                "${info.size} · ${info.stateText} · $statusText",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                if (zh) "更新于 ${formatTaskTime(task.updatedAt)}" else "updated ${formatTaskTime(task.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.85f),
            )
            info.hint?.let { InlineHint(it, tone = if (info.resumable) HintTone.Neutral else HintTone.Error) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onContinue,
                    enabled = info.resumable && !resuming,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(AppShape.sm),
                ) {
                    if (resuming) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(if (zh) "继续" else "Continue", style = MaterialTheme.typography.labelMedium)
                }
                if (!info.resumable) {
                    OutlinedButton(
                        onClick = onGoAnalyze,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(AppShape.sm),
                    ) { Text(if (zh) "重新选文件" else "Re-pick", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(AppShape.sm),
                ) { Text(if (zh) "清除" else "Clear", style = MaterialTheme.typography.labelMedium) }
            }
        }
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
