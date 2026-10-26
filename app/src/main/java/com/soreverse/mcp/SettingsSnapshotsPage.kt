package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.mcp.EditSnapshotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.core.runCatchingCancellable

/**
 * 设置 → 编辑快照：把 taffy_edit_snapshot（编辑前自动快照 / diff / 回滚）图形化。
 * 对应「对话回退 + 快照安全网」借鉴点——所有写操作都可在此可视化查看差异并一键回滚。
 *
 * 底层复用 EditSnapshotService（按工具分桶存于 filesDir/edit-snapshots/<tool>/），
 * 本页只做枚举 + 展示 + 调用，不重复实现快照逻辑。
 */
@Composable
internal fun SettingsSnapshotsPage(t: UiText, onBack: () -> Unit) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    var loading by remember { mutableStateOf(false) }
    var snaps by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var detail by remember { mutableStateOf("") }
    var detailTitle by remember { mutableStateOf("") }
    var rollbackMsg by remember { mutableStateOf("") }
    var pendingRollback by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun refresh() {
        loading = true
        scope.safeLaunch {
            val res = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val root = File(context.filesDir, "edit-snapshots")
                    val out = ArrayList<JSONObject>()
                    root.listFiles()?.filter { it.isDirectory }?.forEach { toolDir ->
                        val arr = EditSnapshotService.list(context, toolDir.name)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            o.put("tool", toolDir.name)
                            out.add(o)
                        }
                    }
                    out.sortedByDescending { it.optLong("createdAt") }
                }
            }
            loading = false
            snaps = res.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun showDiff(tool: String, id: String) {
        scope.safeLaunch {
            val r = withContext(Dispatchers.IO) { runCatchingCancellable { EditSnapshotService.diff(context, id, tool) }.getOrNull() }
            detailTitle = "$tool / $id"
            detail = r?.toString(2) ?: (if (zh) "差异读取失败" else "diff failed")
        }
    }

    fun doRollback(tool: String, id: String) {
        scope.safeLaunch {
            // 原来把 Result 丢掉、只 refresh 列表：回滚失败时用户看到"什么都没发生"，
            // 会误以为已经回滚，继续在错误的状态上改文件。
            val outcome = withContext(Dispatchers.IO) {
                runCatchingCancellable { EditSnapshotService.rollback(context, id, tool) }
            }
            rollbackMsg = outcome.fold(
                onSuccess = { if (zh) "已回滚到该快照" else "Rolled back" },
                onFailure = { (if (zh) "回滚失败: " else "Rollback failed: ") + (it.message ?: it.javaClass.simpleName) },
            )
            refresh()
        }
    }

    if (rollbackMsg.isNotBlank()) {
        InlineHint(rollbackMsg)
    }

    SettingsScaffold(
        title = if (zh) "编辑快照 / 回滚" else "Edit snapshots",
        onBack = onBack,
    ) {
        item("content") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (zh) "编辑快照 / 回滚" else "Edit Snapshots / Rollback", style = MaterialTheme.typography.titleSmall)
        Text(
            if (zh) "写操作（smali / manifest / 归档等）改动前会自动登记快照；此处可查看差异并一键回滚。"
            else "Write ops auto-snapshot before changing files; review diffs and roll back here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh() }, enabled = !loading) { Text(if (zh) "刷新" else "Refresh", fontSize = AppText.body) }
            if (loading) CircularProgressIndicator(Modifier.heightIn(max = 22.dp), strokeWidth = 2.dp)
            Text(
                "${snaps.size} ${if (zh) "份快照" else "snapshots"}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(snaps, key = { it.optString("tool") + "/" + it.optString("snapshotId") }) { s ->
                val tool = s.optString("tool")
                val id = s.optString("snapshotId")
                val path = s.optString("path")
                val at = s.optLong("createdAt")
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("$tool  ·  $id", style = MaterialTheme.typography.labelMedium)
                    Text(path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label), maxLines = 2)
                    if (at > 0) Text(fmt.format(Date(at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showDiff(tool, id) }) { Text(if (zh) "差异" else "Diff", fontSize = AppText.label) }
                        TextButton(onClick = { pendingRollback = tool to id }) { Text(if (zh) "回滚" else "Rollback", fontSize = AppText.label) }
                    }
                }
                HorizontalDivider()
            }
        }

        if (detail.isNotBlank()) {
            Text(detailTitle, style = MaterialTheme.typography.labelMedium)
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label))
            }
            OutlinedButton(onClick = { detail = "" }) { Text(if (zh) "收起差异" else "Hide diff", fontSize = AppText.body) }
        }
    
            }
        }
    }

    val pr = pendingRollback
    if (pr != null) {
        AlertDialog(
            onDismissRequest = { pendingRollback = null },
            title = { Text(if (zh) "确认回滚？" else "Rollback?") },
            text = { Text(if (zh) "将把文件恢复到快照状态，当前改动会丢失：\n${pr.first} / ${pr.second}" else "Restore file to snapshot; current changes lost:\n${pr.first} / ${pr.second}") },
            confirmButton = { TextButton(onClick = { doRollback(pr.first, pr.second); pendingRollback = null }) { Text(if (zh) "回滚" else "Rollback") } },
            dismissButton = { TextButton(onClick = { pendingRollback = null }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
}
