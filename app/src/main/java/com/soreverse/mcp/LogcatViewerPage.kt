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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    val context = LocalContext.current
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

    // ── LogFox 样式扩展状态 ──
    // 采集模式：auto=自动选择 / adb=普通进程 / root / shizuku
    var mode by remember { mutableStateOf("auto") }
    // 当前标签页：log / filter / crash / record
    var tab by remember { mutableStateOf("log") }
    // 崩溃记录（从实时流中收集）
    val crashLogs = remember { mutableStateListOf<LogLine>() }
    // 录制
    var recording by remember { mutableStateOf(false) }
    var recordName by remember { mutableStateOf("") }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var recordWriter by remember { mutableStateOf<FileWriter?>(null) }
    // 崩溃/ANR 通知去重签名
    var lastCrashSig by remember { mutableStateOf("") }

    fun notifyCrash(line: LogLine) {
        val crashKeys = listOf("FATAL EXCEPTION", "Process: ", "ANR in ", "SIGSEGV", "SIGABRT", "*** *** ***")
        if (crashKeys.none { line.raw.contains(it, ignoreCase = true) }) return
        val sig = (line.tag + line.message).take(120)
        if (sig == lastCrashSig) return
        lastCrashSig = sig
        runCatching {
            val channelId = "logcat_crash"
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(
                    channelId, if (zh) "Logcat 崩溃" else "Logcat crash",
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                )
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.createNotificationChannel(channel)
            }
            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(if (zh) "检测到崩溃/ANR" else "Crash/ANR detected")
                .setContentText("${line.level} ${line.tag}: ${line.message.take(80)}")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(line.raw.take(500)))
                .setAutoCancel(true)
            androidx.core.app.NotificationManagerCompat.from(context).notify(
                (System.currentTimeMillis() % 100000).toInt(),
                builder.build(),
            )
        }
    }

    fun exportZip(visibleLines: List<LogLine>) {
        runCatching {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val zip = File(context.filesDir, "logcat_export_$stamp.zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                // 设备信息
                val deviceInfo = buildString {
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("Kernel: ${System.getProperty("os.version", "")}")
                    appendLine("Time: $stamp")
                    appendLine("Filter: tags=[$tagFilter] keyword=[$keyword] levels=$levelSet")
                }
                zos.putNextEntry(ZipEntry("device_info.txt"))
                zos.write(deviceInfo.toByteArray())
                zos.closeEntry()
                // 日志
                val logText = visibleLines.joinToString("\n") { it.raw }
                zos.putNextEntry(ZipEntry("logcat.txt"))
                zos.write(logText.toByteArray())
                zos.closeEntry()
            }
            // 分享
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zip,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(share, if (zh) "导出 Logcat 日志" else "Export logcat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { e ->
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun start() {
        if (running) return
        running = true
        paused = false
        // 按采集模式选择通道：root→su 提权、shizuku→Shizuku 流、adb/默认→普通进程（仅本应用日志）
        val proc = when (mode) {
            "root" -> PermissionManager.startPrivilegedStream("logcat", listOf("-v", "time"))
                ?: ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
            "shizuku" -> PermissionManager.startPrivilegedStream("logcat", listOf("-v", "time"))
                ?: ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
            else -> {
                if (mode == "auto" && (PermissionManager.isRootAvailable() || PermissionManager.isShizukuGranted() || PermissionManager.isDhizukuAvailable())) {
                    PermissionManager.startPrivilegedStream("logcat", listOf("-v", "time"))
                        ?: ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
                } else {
                    ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
                }
            }
        }
        process = proc
        scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null && running) {
                        if (!paused) {
                            parseLogLine(line)?.let { parsed ->
                                synchronized(logs) {
                                    logs.add(parsed)
                                    while (logs.size > 2000) {
                                        logs.removeAt(0); dropped++
                                    }
                                }
                                // 崩溃/ANR 行收集到崩溃标签页
                                val crashKeys = listOf("FATAL EXCEPTION", "Process: ", "ANR in ", "SIGSEGV", "SIGABRT", "*** *** ***")
                                if (crashKeys.any { line.contains(it, ignoreCase = true) }) {
                                    synchronized(crashLogs) { crashLogs.add(parsed) }
                                }
                                notifyCrash(parsed)
                            }
                        }
                        if (recording) {
                            runCatching { recordWriter?.write(line + "\n") }
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // destroy()/stop() 会关闭流导致 readLine 抛 InterruptedIOException，
                // 这是正常停止路径，静默忽略；仅非正常退出才记录
                if (running) com.soreverse.mcp.core.AppLog.e("Logcat reader ended: ${e.message}")
            } catch (e: Exception) {
                if (running) com.soreverse.mcp.core.AppLog.e("Logcat reader failed: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
        if (recording) {
            recording = false
            runCatching { recordWriter?.flush() }
            runCatching { recordWriter?.close() }
            recordWriter = null
        }
    }

    /** 开始录制到文件（与实时流同步写入）。 */
    fun startRecord() {
        if (recording) return
        val name = recordName.trim().ifBlank { "logcat_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}" }
        val f = File(context.filesDir, "logcat_record_${name.replace(Regex("[^\\w\\-.]"), "_")}.log")
        recordFile = f
        recordWriter = runCatching { FileWriter(f, false) }.getOrNull()
        recording = recordWriter != null
        if (!recording) Toast.makeText(context, "录制启动失败", Toast.LENGTH_SHORT).show()
    }

    fun stopRecord() {
        recording = false
        runCatching { recordWriter?.flush() }
        runCatching { recordWriter?.close() }
        recordWriter = null
    }

    /** 录制文件列表（filesDir 下 logcat_record_*.log）。 */
    fun recordFiles(): List<File> =
        context.filesDir.listFiles { f -> f.name.startsWith("logcat_record_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

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
    val clipboard = LocalClipboardManager.current

    // 新日志自动滚动到底部
    LaunchedEffect(visible.size) {
        if (!paused && visible.isNotEmpty()) {
            runCatching { listState.scrollToItem(visible.size - 1) }
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 状态统计卡片（LogFox 样式）──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox(
                if (zh) "状态" else "Status",
                when {
                    running && paused -> if (zh) "已暂停" else "Paused"
                    running -> if (zh) "采集中" else "Running"
                    else -> if (zh) "待机" else "Standby"
                },
                Modifier.weight(1f),
            )
            StatBox(if (zh) "总行" else "Total", logs.size.toString(), Modifier.weight(1f))
            StatBox(if (zh) "崩溃" else "Crash", crashLogs.size.toString(), Modifier.weight(1f))
        }

        // ── 采集模式选择（自动 / ADB默认 / Root / Shizuku）──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "auto" to (if (zh) "自动" else "Auto"),
                "adb" to (if (zh) "ADB/默认" else "ADB/Default"),
                "root" to "Root",
                "shizuku" to "Shizuku",
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = {
                        if (m != mode) {
                            if (running) { stop(); mode = m; start() } else mode = m
                        }
                    },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }

        // ── Shizuku 授权提示（LogFox 样式）──
        if (PermissionManager.isShizukuServiceRunning() && !PermissionManager.isShizukuGranted()) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (zh) "Shizuku 已连接，等待应用授权" else "Shizuku connected, waiting for authorization",
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { runCatching { rikka.shizuku.Shizuku.requestPermission(10086) } }) {
                    Text(if (zh) "授权" else "Grant")
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            `package` = "moe.shizuku.privileged.api"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }) { Text(if (zh) "打开" else "Open") }
            }
        }

        // ── 标签页：日志 / 过滤器 / 崩溃 / 录制 ──
        val tabs = listOf("log", "filter", "crash", "record")
        val tabIndex = tabs.indexOf(tab).coerceAtLeast(0)
        TabRow(selectedTabIndex = tabIndex, containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)) {
            listOf(
                if (zh) "日志" else "Logs",
                if (zh) "过滤器" else "Filters",
                if (zh) "崩溃" else "Crashes",
                if (zh) "录制" else "Record",
            ).forEachIndexed { i, label ->
                Tab(selected = tabIndex == i, onClick = { tab = tabs[i] }, text = { Text(label, fontSize = 12.sp) })
            }
        }

        when (tab) {
            "filter" -> {
                // ── 过滤器：级别 + tag + 关键字 ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("V", "D", "I", "W", "E", "F").forEach { lv ->
                        FilterChip(
                            selected = lv in levelSet,
                            onClick = { levelSet = if (lv in levelSet) levelSet - lv else levelSet + lv },
                            label = { Text(lv, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = levelColor(lv)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = tagFilter,
                    onValueChange = { tagFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (zh) "Tag 过滤（逗号分隔多 tag）" else "Tag filter (comma separated)", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (zh) "关键字搜索（高亮）" else "Keyword search (highlight)", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
            "crash" -> {
                // ── 崩溃记录 ──
                if (crashLogs.isEmpty()) {
                    Text(
                        if (zh) "没有崩溃记录\n捕获结果会显示在这里" else "No crash records\nCaptured results will show here",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(crashLogs.size) { i ->
                            val cl = crashLogs[crashLogs.size - 1 - i]
                            Text(
                                cl.raw,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }
            "record" -> {
                // ── 录制：名称 + 录制/停止 + 文件列表 ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = recordName,
                        onValueChange = { recordName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (zh) "录制名称" else "Record name", maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                    if (recording) {
                        TextButton(onClick = { stopRecord() }) { Text(if (zh) "停止" else "Stop", color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = { startRecord() }, enabled = running) { Text(if (zh) "录制" else "Record") }
                    }
                }
                val files = remember(recording, logs.size) { recordFiles() }
                if (files.isEmpty()) {
                    Text(
                        if (zh) "没有录制文件\n录制文件保存在应用私有目录" else "No recorded files\nRecordings are kept in the app-private directory",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files.size) { i ->
                            val f = files[i]
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${f.length() / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            else -> {
                // ── 日志页：搜索 + 工具栏 + 列表 ──
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (zh) "搜索" else "Search", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("V", "D", "I", "W", "E", "F").forEach { lv ->
                        FilterChip(
                            selected = lv in levelSet,
                            onClick = { levelSet = if (lv in levelSet) levelSet - lv else levelSet + lv },
                            label = { Text(lv, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = levelColor(lv)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (running) stop() else start() }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (zh) (if (running) "停止" else "开始") else (if (running) "Stop" else "Start"),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { paused = !paused }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (zh) "暂停/恢复" else "Pause/Resume",
                            tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { logs.clear(); dropped = 0 }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = if (zh) "清空" else "Clear", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(visible.joinToString("\n") { it.raw }))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = if (zh) "复制" else "Copy", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { exportZip(visible) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.FileDownload, contentDescription = if (zh) "导出" else "Export", modifier = Modifier.size(18.dp))
                    }
                }
                // ── 状态栏 ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (zh) "共 ${logs.size} 行" + (if (dropped > 0) "（已丢弃 $dropped 行）" else "") + if (paused) " · 已暂停" else "" + if (recording) " · 录制中" else ""
                        else "${logs.size} lines" + (if (dropped > 0) " ($dropped dropped)" else "") + if (paused) " · paused" else "" + if (recording) " · recording" else "",
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
    }
}

/** LogFox 样式状态统计小卡片。 */
@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
