package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/** 单条解析后的日志行。 */
private data class LogLine(
    val time: String,
    val pid: String,
    val tid: String,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String,
)

/** 级别着色：对标 LogFox 桌面版配色。 */
private fun levelColor(level: String): Color = when (level) {
    "V" -> Color(0xFF9E9E9E)
    "D" -> Color(0xFF64B5F6)
    "I" -> Color(0xFF66BB6A)
    "W" -> Color(0xFFFFB74D)
    "E" -> Color(0xFFEF5350)
    "F" -> Color(0xFFAB47BC)
    else -> Color(0xFF9E9E9E)
}

private fun parseLogLine(line: String): LogLine? {
    val parts = line.split(" ")
    // logcat -v time: "MM-DD HH:MM:SS.mmm  PID  TID  LEVEL TAG : msg"
    var idx = 0
    while (idx < parts.size && parts[idx].isBlank()) idx++
    if (parts.size - idx < 6) return null
    val time = "${parts[idx]} ${parts[idx + 1]}"
    val pid = parts[idx + 2]
    val tid = parts[idx + 3]
    val level = parts[idx + 4]
    val rest = parts.subList(idx + 5, parts.size).joinToString(" ")
    val colon = rest.indexOf(": ")
    val tag = if (colon > 0) rest.substring(0, colon) else rest
    val message = if (colon > 0) rest.substring(colon + 2) else ""
    if (level.length != 1 || level[0] !in "VDIWEF") return null
    return LogLine(time, pid, tid, level, tag, message, line)
}

/**
 * Logcat 查看器（对标 LogFox 的 GUI 交互）：
 * 实时日志流 + 级别过滤 + 多 tag 过滤 + 关键字高亮 + 级别着色 + 暂停/清空。
 */
@Composable
internal fun LogcatViewerPage(t: UiText) {
    val zh = t.zh
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<LogLine>() }
    val listState = rememberLazyListState()

    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var levelSet by remember { mutableStateOf(setOf("V", "D", "I", "W", "E", "F")) }
    var process by remember { mutableStateOf<Process?>(null) }
    var dropped by remember { mutableStateOf(0) }

    fun start() {
        if (running) return
        running = true
        paused = false
        val proc = ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
        process = proc
        scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null && running) {
                    if (!paused) {
                        parseLogLine(line)?.let { parsed ->
                            logs.add(parsed)
                            while (logs.size > 2000) {
                                logs.removeAt(0); dropped++
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }

    fun stop() {
        running = false
        process?.destroy()
        process = null
    }

    // 页面进入自动开始采集
    LaunchedEffect(Unit) { start() }
    // 页面退出停止
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stop() }
    }

    // 可见日志：级别 + tag + 关键字过滤
    val visible = remember(logs.size, tagFilter, keyword, levelSet, paused) {
        val tags = tagFilter.split(',').map { it.trim() }.filter { it.isNotBlank() }
        logs.filter { line ->
            levelSet.contains(line.level) &&
                (tags.isEmpty() || tags.any { line.tag.contains(it, ignoreCase = true) }) &&
                (keyword.isBlank() || line.raw.contains(keyword, ignoreCase = true))
        }.takeLast(600)
    }

    // 新日志自动滚动到底部
    LaunchedEffect(visible.size) {
        if (!paused && visible.isNotEmpty()) {
            runCatching { listState.scrollToItem(visible.size - 1) }
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 工具栏：级别 chips ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("V", "D", "I", "W", "E", "F").forEach { lv ->
                FilterChip(
                    selected = lv in levelSet,
                    onClick = {
                        levelSet = if (lv in levelSet) levelSet - lv else levelSet + lv
                    },
                    label = {
                        Text(lv, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = levelColor(lv))
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (running) stop() else start()
            }) {
                Icon(
                    if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (zh) (if (running) "暂停" else "开始") else (if (running) "Pause" else "Start"),
                )
            }
            IconButton(onClick = {
                paused = !paused
            }) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (zh) "暂停/恢复" else "Pause/Resume",
                    tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = {
                logs.clear(); dropped = 0
            }) {
                Icon(Icons.Default.Refresh, contentDescription = if (zh) "清空" else "Clear")
            }
        }

        // ── 过滤输入 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tagFilter,
                onValueChange = { tagFilter = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (zh) "Tag 过滤（逗号分隔多 tag）" else "Tag filter (comma separated)", maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (zh) "关键字搜索（高亮）" else "Keyword search (highlight)", maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
        }

        // ── 状态栏 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (zh) "共 ${logs.size} 行" + (if (dropped > 0) "（已丢弃 $dropped 行）" else "") + if (paused) " · 已暂停" else ""
                else "${logs.size} lines" + (if (dropped > 0) " ($dropped dropped)" else "") + if (paused) " · paused" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 日志列表 ──
        androidx.compose.foundation.layout.Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), MaterialTheme.shapes.medium),
        ) {
            if (visible.isEmpty()) {
                Text(
                    if (zh) "暂无匹配日志…（logcat 无权限时只能看到本应用日志，全系统日志需 Root/Shizuku 或 ADB）" else "No matching logs… (without privilege only this app's logs are visible; system-wide logs need Root/Shizuku/ADB)",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    items(visible, key = { it.raw.hashCode() }) { line ->
                        SelectionContainer {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 10.sp)) {
                                        append(line.time + " ")
                                    }
                                    withStyle(SpanStyle(color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 10.sp)) {
                                        append(line.pid + " ")
                                    }
                                    withStyle(SpanStyle(color = levelColor(line.level), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 10.sp)) {
                                        append(line.level + " ")
                                    }
                                    withStyle(SpanStyle(color = Color(0xFF4DD0E1), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 10.sp)) {
                                        append("${line.tag} ")
                                    }
                                    // 消息（关键字高亮）
                                    val msg = line.message
                                    if (keyword.isNotBlank()) {
                                        var idx = msg.indexOf(keyword, ignoreCase = true)
                                        var last = 0
                                        while (idx >= 0) {
                                            append(msg.substring(last, idx))
                                            withStyle(SpanStyle(background = Color(0x66FFEB3B), fontWeight = FontWeight.Bold)) {
                                                append(msg.substring(idx, idx + keyword.length))
                                            }
                                            last = idx + keyword.length
                                            idx = msg.indexOf(keyword, last, ignoreCase = true)
                                        }
                                        append(msg.substring(last))
                                    } else {
                                        append(msg)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
