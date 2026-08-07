package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun TasksPage(
    t: UiText,
    state: WorkspaceState,
    onContinueTask: (String) -> Unit,
) {
    val zh = t.zh
    val active = state.tasks.filter { it.status == "active" }
    val done = state.tasks.filter { it.status == "completed" }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (zh) "当前任务" else "Active tasks", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(6.dp))
        }
        if (active.isEmpty()) {
            item { Text(if (zh) "暂无进行中的任务" else "No active tasks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(active, key = { it.id }) { task ->
            TaskCard(task = task, zh = zh, onContinue = { onContinueTask(task.id) }, onClear = { state.deleteTask(task.id) })
        }
        item {
            Spacer(Modifier.size(12.dp))
            Text(if (zh) "历史任务" else "History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(6.dp))
        }
        if (done.isEmpty()) {
            item { Text(if (zh) "暂无历史任务" else "No history", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(done, key = { it.id }) { task ->
            TaskCard(task = task, zh = zh, onContinue = { onContinueTask(task.id) }, onClear = { state.deleteTask(task.id) })
        }
    }
}

@Composable
private fun TaskCard(task: TaskRecord, zh: Boolean, onContinue: () -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(when (task.kind) {
                    "apk" -> "APK"
                    "so" -> "SO"
                    else -> "MIX"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(task.mainName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (zh) "更新于 ${formatTaskTime(task.updatedAt)} · ${if (task.status == "active") "进行中" else "已完成"}" else "Updated ${formatTaskTime(task.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(if (zh) "继续" else "Continue") }
                OutlinedButton(onClick = onClear, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(if (zh) "清除" else "Clear") }
            }
        }
    }
}

private fun formatTaskTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}