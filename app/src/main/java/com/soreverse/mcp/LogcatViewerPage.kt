package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** 是否为崩溃段起始行（LogFox 风格：JAVA / NATIVE / ANR 三分类）。 */
private fun isCrashStart(raw: String): Boolean {
    val r = raw.uppercase()
    return r.contains("FATAL EXCEPTION") ||
        r.contains("FATAL SIGNAL") ||
        r.contains("ANR IN") ||
        r.contains("*** *** ***") ||
        (r.contains("PROCESS:") && r.contains("PID:")) ||
        r.contains("SIGSEGV") ||
        r.contains("SIGABRT")
}

/** 崩溃类型：JAVA / NATIVE / ANR。 */
private fun crashType(raw: String): String = when {
    raw.uppercase().contains("FATAL SIGNAL") || raw.contains("SIGSEGV") || raw.contains("SIGABRT") || raw.contains("*** *** ***") -> "NATIVE"
    raw.uppercase().contains("ANR IN") -> "ANR"
    else -> "JAVA"
}

/** 崩溃标题（LogFox 样式）。 */
private fun crashTitle(type: String, zh: Boolean): String = when (type) {
    "NATIVE" -> if (zh) "Native 崩溃: Fatal signal" else "Native crash: Fatal signal"
    "ANR" -> if (zh) "ANR 无响应" else "ANR"
    else -> if (zh) "AndroidRuntime 错误" else "AndroidRuntime error"
}

/** 从日志流中提取崩溃段落（起始行 → 下一个起始行/结尾，截断 300 行）。 */
private fun groupCrashesFromLogs(logs: List<LogLine>): List<CrashGroup> {
    val starts = mutableListOf<Int>()
    logs.forEachIndexed { i, l -> if (isCrashStart(l.raw)) starts.add(i) }
    if (starts.isEmpty()) return emptyList()
    val groups = mutableListOf<CrashGroup>()
    starts.forEachIndexed { idx, s ->
        val e = if (idx + 1 < starts.size) starts[idx + 1] else logs.size
        val seg = logs.subList(s, e).take(300)
        val type = crashType(seg.first().raw)
        val pkg = seg.firstNotNullOfOrNull { l ->
            Regex("Process: ([\\w.]+)").find(l.raw)?.groupValues?.get(1)
                ?: Regex("Cmdline: ([\\w.]+)").find(l.raw)?.groupValues?.get(1)
                ?: Regex("\\[([\\w.]+)\\]").find(l.raw)?.groupValues?.get(1)
        } ?: ""
        groups.add(CrashGroup(type, pkg, seg.first().time, seg.toList()))
    }
    return groups.takeLast(50)
}

/** 崩溃分组（LogFox 崩溃记录卡片的数据模型）。 */
private data class CrashGroup(
    val type: String,
    val packageName: String,
    val time: String,
    val lines: List<LogLine>,
)

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
    // 整页滚动状态（整个页面含控件与日志内容一体上下滚动）
    val pageScroll = rememberScrollState()

    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var levelSet by remember { mutableStateOf(setOf("V", "D", "I", "W", "E", "F")) }
    // ── LogFox 实时过滤增强 ──
    var pkgFilter by remember { mutableStateOf("") }
    var pidFilter by remember { mutableStateOf("") }
    var regexEnabled by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var crashOnly by remember { mutableStateOf(false) }
    var process by remember { mutableStateOf<Process?>(null) }
    var dropped by remember { mutableStateOf(0) }
    // 当前采集通道说明（无特权时明确提示，避免"静默空日志"）
    var channelInfo by remember { mutableStateOf("") }
    // 启动自检: 权限通道是否真实可用
    var channelError by remember { mutableStateOf("") }
    // 应用日志兜底模式: 无任何系统权限(无 Root/Shizuku/READ_LOGS)时，
    // Android 11+ 连本应用 logcat 都读不到，降级显示本应用 AppLog（至少能看到软件自己的日志）
    var appLogMode by remember { mutableStateOf(false) }
    val appLogLines = remember { mutableStateListOf<String>() }
    val appLogListener = remember {
        { line: String ->
            if (appLogMode) synchronized(appLogLines) {
                appLogLines.add(line)
                while (appLogLines.size > 2000) appLogLines.removeAt(0)
            }
        }
    }
    DisposableEffect(Unit) {
        AppLog.addListener(appLogListener)
        onDispose { AppLog.removeListener(appLogListener) }
    }
    // Shizuku 授权结果监听：授权成功后立即刷新通道并重启采集（页面状态及时更新）
    DisposableEffect(Unit) {
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scope.launch(Dispatchers.IO) {
                    if (running) stop()
                    start() // 重新探测通道：Shizuku 已授权，auto/shizuku 模式生效
                }
            }
        }
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
        onDispose { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener) }
    }

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
    // ── READ_LOGS 授权状态（访问所有设备日志）──
    var readLogsGranted by remember { mutableStateOf(false) }
    // ── 过滤器预设（保存/管理）──
    val filterPrefs = remember { context.getSharedPreferences("logcat_filters", Context.MODE_PRIVATE) }
    var filterPresets by remember { mutableStateOf(filterPrefs.all.keys.filter { it.startsWith("preset_") }.sorted()) }
    var presetNameInput by remember { mutableStateOf("") }
    // 实时过滤面板折叠状态（LogFox: 默认折叠，展开显示完整过滤条件）
    var filterExpanded by remember { mutableStateOf(false) }
    // READ_LOGS 授权引导对话框
    var showReadLogsDialog by remember { mutableStateOf(false) }
    // ── 应用 tab（LogFox 样式：应用列表按包名过滤日志）──
    var appSearch by remember { mutableStateOf("") }
    var appList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // (label, packageName)
    // ── 设置 tab（LogFox 样式设置项，SharedPreferences 持久化）──
    val settingsPrefs = remember { context.getSharedPreferences("logcat_settings", Context.MODE_PRIVATE) }
    var crashNotify by remember { mutableStateOf(settingsPrefs.getBoolean("crash_notify", true)) }
    var bgCollect by remember { mutableStateOf(settingsPrefs.getBoolean("bg_collect", false)) }
    var bootRestore by remember { mutableStateOf(settingsPrefs.getBoolean("boot_restore", false)) }
    var exportDeviceInfo by remember { mutableStateOf(settingsPrefs.getBoolean("export_device_info", true)) }
    fun saveSettings() {
        settingsPrefs.edit()
            .putBoolean("crash_notify", crashNotify)
            .putBoolean("bg_collect", bgCollect)
            .putBoolean("boot_restore", bootRestore)
            .putBoolean("export_device_info", exportDeviceInfo)
            .apply()
    }

    fun notifyCrash(line: LogLine) {
        if (!crashNotify) return
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
                // 设备信息（设置项「导出设备信息」控制）
                if (exportDeviceInfo) {
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
                }
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
        channelError = ""
        // 按采集模式选择通道。关键: 无特权通道时普通进程在 Android 8.0+ 读不到系统日志,
        // 必须明确告知用户而不是静默显示空列表(LogFox 通过 Shizuku/ADB/Root 提权才能看全系统日志)。
        val priv = PermissionManager.startPrivilegedStream("logcat", listOf("-v", "time"))
        // READ_LOGS（adb 授予）可让普通进程读全系统日志，无需 Root/Shizuku
        val hasReadLogs = PermissionManager.hasReadLogs(context)
        val proc = when {
            priv != null -> priv
            hasReadLogs -> ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
            mode == "root" -> {
                channelError = if (PermissionManager.isRootAvailable()) "Root 通道启动失败，已降级普通进程" else "Root 不可用"
                ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
            }
            mode == "shizuku" -> {
                channelError = if (PermissionManager.isShizukuGranted()) {
                    "Shizuku 通道启动失败: ${PermissionManager.lastShizukuServiceError()}"
                } else "Shizuku 未授权"
                ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
            }
            else -> {
                // auto / adb 模式: 无特权通道时降级普通进程并提示
                if (!PermissionManager.isRootAvailable() && !PermissionManager.isShizukuGranted() && !PermissionManager.isDhizukuAvailable()) {
                    channelError = "无 Root/Shizuku 权限，普通进程读不到系统日志（Android 8.0+）。可用 adb 授权: adb shell pm grant com.taffynihe android.permission.READ_LOGS"
                } else if (channelError.isBlank()) {
                    val diag = PermissionManager.lastShizukuServiceError()
                    channelError = if (diag.isNotBlank()) "特权通道不可用: $diag" else "特权通道不可用，已降级普通进程"
                }
                ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
            }
        }
        channelInfo = when {
            priv != null -> when {
                PermissionManager.isRootAvailable() -> if (zh) "Root 通道" else "Root channel"
                PermissionManager.isShizukuGranted() -> "Shizuku 通道"
                PermissionManager.isDhizukuAvailable() -> "Dhizuku 通道"
                else -> if (zh) "特权通道" else "Privileged channel"
            }
            hasReadLogs -> "READ_LOGS"
            else -> if (zh) "普通进程" else "Normal process"
        }
        // 无任何系统权限时降级为应用日志：Android 11+ 普通进程连本应用 logcat 都读不到，
        // 显示 AppLog（本软件运行日志）让用户至少能看到自己的日志
        appLogMode = priv == null && !hasReadLogs
        if (appLogMode) {
            synchronized(appLogLines) {
                appLogLines.clear()
                appLogLines.addAll(AppLog.snapshot())
            }
            channelInfo = if (zh) "应用日志（无系统权限）" else "App logs (no system permission)"
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

    // ── 过滤器预设（保存/管理）──
    fun currentFilterSpec(): String = listOf(
        keyword, tagFilter, pkgFilter, pidFilter,
        levelSet.sorted().joinToString(","),
        regexEnabled, caseSensitive, crashOnly,
    ).joinToString("|")

    fun savePreset(name: String): Boolean {
        val n = name.trim()
        if (n.isBlank()) return false
        filterPrefs.edit().putString("preset_$n", currentFilterSpec()).apply()
        filterPresets = filterPrefs.all.keys.filter { it.startsWith("preset_") }.sorted()
        presetNameInput = ""
        return true
    }

    fun applyPreset(key: String) {
        val spec = filterPrefs.getString(key, "") ?: return
        val parts = spec.split("|")
        if (parts.size >= 8) {
            keyword = parts[0]; tagFilter = parts[1]; pkgFilter = parts[2]; pidFilter = parts[3]
            levelSet = parts[4].split(",").filter { it.isNotBlank() }.toSet()
            regexEnabled = parts[5] == "true"
            caseSensitive = parts[6] == "true"
            crashOnly = parts[7] == "true"
        }
    }

    fun deletePreset(key: String) {
        filterPrefs.edit().remove(key).apply()
        filterPresets = filterPrefs.all.keys.filter { it.startsWith("preset_") }.sorted()
    }

    // ── READ_LOGS（访问所有设备日志）──
    fun refreshReadLogs() {
        readLogsGranted = PermissionManager.hasReadLogs(context)
    }

    /** 尝试通过特权通道自动授予 READ_LOGS（pm grant）；返回是否成功。 */
    fun tryGrantReadLogs(): Boolean {
        val cmd = "pm grant ${context.packageName} android.permission.READ_LOGS"
        val r = PermissionManager.exec(cmd, timeoutSec = 15)
        if (r.code == 0) {
            refreshReadLogs()
            return readLogsGranted
        }
        return false
    }

    // ── 保存 .log ──
    fun saveLogFile() {
        runCatching {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val f = File(context.filesDir, "logcat_saved_$stamp.log")
            f.writeText(if (appLogMode) appLogLines.joinToString("\n") else synchronized(logs) { logs.joinToString("\n") { it.raw } })
            Toast.makeText(context, (if (zh) "已保存: " else "Saved: ") + f.absolutePath, Toast.LENGTH_LONG).show()
        }.onFailure { e -> Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    // 页面进入自动开始采集
    // 必须放 IO 线程: startPrivilegedStream 内 UserService 绑定会阻塞等待(await 2s),
    // 主线程执行会导致 Input dispatching timeout (ANR)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { start() } }
    // 页面退出停止
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stop() }
    }

    // 可见日志：级别 + tag + 关键字 + 包名/PID/正则/崩溃过滤
    val visible = remember(logs.size, tagFilter, keyword, levelSet, paused, pkgFilter, pidFilter, regexEnabled, caseSensitive, crashOnly) {
        val tags = tagFilter.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val kw = keyword
        val pkg = pkgFilter.trim()
        val pid = pidFilter.trim()
        val re = if (regexEnabled) runCatching { Regex(kw, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull() else null
        logs.filter { line ->
            if (crashOnly && !isCrashStart(line.raw)) return@filter false
            levelSet.contains(line.level) &&
                (tags.isEmpty() || tags.any { line.tag.contains(it, ignoreCase = true) }) &&
                (pkg.isBlank() || line.raw.contains(" $pkg ", ignoreCase = true) || line.raw.contains("/$pkg:", ignoreCase = true)) &&
                (pid.isBlank() || line.pid == pid) &&
                (kw.isBlank() || (re?.containsMatchIn(line.raw) ?: line.raw.contains(kw, ignoreCase = !caseSensitive)))
        }.takeLast(300)
    }
    val clipboard = LocalClipboardManager.current

    // 新日志自动滚动到页面底部（整页滚动模式）
    LaunchedEffect(appLogMode, visible.size, appLogLines.size) {
        if (!paused) {
            runCatching {
                if (pageScroll.maxValue > 0) pageScroll.scrollTo(pageScroll.maxValue)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp).verticalScroll(pageScroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            StatBox(if (zh) "总行" else "Total", if (appLogMode) appLogLines.size.toString() else logs.size.toString(), Modifier.weight(1f))
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
                            // start() 内含特权绑定(可能 await)，须在 IO 线程
                            scope.launch(Dispatchers.IO) {
                                if (running) { stop(); mode = m; start() } else mode = m
                            }
                        }
                    },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }

        // ── 访问所有设备日志（READ_LOGS 授权按钮）──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LaunchedEffect(readLogsGranted) { refreshReadLogs() }
            TextButton(
                onClick = {
                    if (readLogsGranted) {
                        Toast.makeText(context, if (zh) "已授予 READ_LOGS" else "READ_LOGS granted", Toast.LENGTH_SHORT).show()
                    } else {
                        showReadLogsDialog = true
                    }
                },
            ) {
                Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (zh) "访问所有设备日志" else "Read all device logs", fontSize = 11.sp)
            }
            Text(
                if (readLogsGranted) (if (zh) "已授权 ✓" else "Granted ✓") else (if (zh) "未授予（adb: pm grant READ_LOGS）" else "Not granted"),
                style = MaterialTheme.typography.labelSmall,
                color = if (readLogsGranted) AppPalette.green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 录制控制：名称 + 录制/停止（LogFox: 放在采集通道下）──
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
        // 录制操作四按钮 + 录制状态文本
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { logs.clear(); dropped = 0 }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(if (zh) "清除" else "Clear", fontSize = 10.sp)
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(if (appLogMode) appLogLines.joinToString("\n") else visible.joinToString("\n") { it.raw }))
            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(if (zh) "复制" else "Copy", fontSize = 10.sp)
            }
            TextButton(onClick = { saveLogFile() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(if (zh) "保存.log" else "Save .log", fontSize = 10.sp)
            }
            TextButton(onClick = { exportZip(visible) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Archive, null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(if (zh) "保存.zip" else "Save .zip", fontSize = 10.sp)
            }
            Text(
                when {
                    recording && recordFile != null -> (if (zh) "录制中 → ${recordFile!!.name}" else "Recording → ${recordFile!!.name}")
                    recording -> if (zh) "录制中…" else "Recording…"
                    else -> if (zh) "未录制" else "Not recording"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

        // ── 通道状态提示（无权限时明确告知，LogFox 模式状态行）──
        if (channelError.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    channelError + (if (zh) "。" else ". "),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (!PermissionManager.isShizukuGranted()) {
                    TextButton(onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_MAIN).apply {
                                `package` = "moe.shizuku.privileged.api"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    }) { Text(if (zh) "打开 Shizuku" else "Open Shizuku") }
                    TextButton(onClick = { runCatching { rikka.shizuku.Shizuku.requestPermission(10086) } }) {
                        Text(if (zh) "授权" else "Grant")
                    }
                }
            }
        }
        if (channelInfo.isNotBlank()) {
            Text(
                (if (zh) "采集通道: " else "Channel: ") + channelInfo,
                style = MaterialTheme.typography.labelSmall,
                color = if (channelInfo.contains("Root") || channelInfo.contains("Shizuku") || channelInfo.contains("Dhizuku") || channelInfo.contains("READ_LOGS") || channelInfo.contains("特权"))
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (channelInfo.contains("Root") || channelInfo.contains("Shizuku") || channelInfo.contains("Dhizuku") || channelInfo.contains("READ_LOGS") || channelInfo.contains("特权"))
                    FontWeight.SemiBold else FontWeight.Normal,
            )
        }

        // ── 标签页：日志 / 过滤器 / 崩溃 / 录制 / 应用 / 设置 ──
        val tabs = listOf("log", "filter", "crash", "record", "app", "settings")
        val tabIndex = tabs.indexOf(tab).coerceAtLeast(0)
        // 应用列表加载（IO 线程，PackageManager 查询）
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    val apps = pm.getInstalledApplications(0)
                    appList = apps.mapNotNull { ai ->
                        runCatching {
                            val label = pm.getApplicationLabel(ai).toString()
                            label to ai.packageName
                        }.getOrNull()
                    }.sortedBy { it.first.lowercase() }
                }
            }
        }
        TabRow(selectedTabIndex = tabIndex, containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)) {
            listOf(
                if (zh) "日志" else "Logs",
                if (zh) "过滤器" else "Filters",
                if (zh) "崩溃" else "Crashes",
                if (zh) "录制" else "Record",
                if (zh) "应用" else "Apps",
                if (zh) "设置" else "Settings",
            ).forEachIndexed { i, label ->
                Tab(selected = tabIndex == i, onClick = { tab = tabs[i] }, text = { Text(label, fontSize = 12.sp) })
            }
        }

        when (tab) {
            "filter" -> {
                // ── 过滤器管理：保存当前过滤条件 / 管理已保存预设（实时过滤已在日志页）──
                Text(
                    if (zh) "保存过滤器" else "Save filter",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (zh) "过滤器名称" else "Filter name", maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                    TextButton(
                        onClick = {
                            if (!savePreset(presetNameInput)) {
                                Toast.makeText(context, if (zh) "请输入过滤器名称" else "Enter a filter name", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, if (zh) "已保存过滤器" else "Filter saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = presetNameInput.isNotBlank(),
                    ) { Text(if (zh) "保存" else "Save") }
                }
                Text(
                    (if (zh) "管理过滤器" else "Manage filters") + " (${filterPresets.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (filterPresets.isEmpty()) {
                    Text(
                        if (zh) "没有已保存的过滤器\n在日志页设置过滤条件后，在这里命名保存" else "No saved filters\nSet filter conditions on the Logs tab, then name & save here",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    filterPresets.forEach { key ->
                        val name = key.removePrefix("preset_")
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { applyPreset(key) }.padding(6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { applyPreset(key) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (zh) "应用" else "Apply", fontSize = 11.sp)
                            }
                            TextButton(onClick = { deletePreset(key) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (zh) "删除" else "Delete", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            "crash" -> {
                // ── 崩溃记录（LogFox 卡片样式：JAVA / NATIVE / ANR）──
                val groups = remember(logs.size) { groupCrashesFromLogs(logs) }
                var expandedIdx by remember { mutableStateOf(-1) }
                Text(
                    (if (zh) "崩溃记录" else "Crash records") + " · " + groups.size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (groups.isEmpty()) {
                    Text(
                        if (zh) "没有崩溃记录\n捕获结果会显示在这里" else "No crash records\nCaptured results will show here",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    groups.asReversed().forEachIndexed { i, g ->
                        val expanded = expandedIdx == i
                            Column(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // 标题行（LogFox: 图标 + 标题 + 展开）
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        when (g.type) {
                                            "NATIVE" -> Icons.Default.Memory
                                            "ANR" -> Icons.Default.Warning
                                            else -> Icons.Default.BugReport
                                        },
                                        null,
                                        tint = when (g.type) {
                                            "NATIVE" -> AppPalette.red
                                            "ANR" -> AppPalette.orange
                                            else -> AppPalette.indigo
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        crashTitle(g.type, zh),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = { expandedIdx = if (expanded) -1 else i }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = if (zh) "展开" else "Expand",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                // 包名 + 时间 · 类型 · 行数
                                Text(
                                    (if (g.packageName.isNotBlank()) g.packageName + " · " else "") + g.time + " · " + g.type + " · " + g.lines.size + " " + (if (zh) "行" else "lines"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 操作按钮（LogFox: 复制 / 删除）
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(g.lines.joinToString("\n") { it.raw }))
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (zh) "复制" else "Copy", fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = {
                                            synchronized(logs) { logs.removeAll(g.lines.toSet()) }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (zh) "删除" else "Delete", fontSize = 11.sp)
                                    }
                                }
                                // 日志内容（黑色背景，LogFox 终端样式）
                                val showLines = if (expanded) g.lines else g.lines.take(6)
                                Column(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                        .background(Color(0xFF111111))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    showLines.forEach { l ->
                                        Text(
                                            l.raw,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                                            color = Color(0xFFE0E0E0),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (!expanded && g.lines.size > 6) {
                                        Text(
                                            if (zh) "… 共 ${g.lines.size} 行，点击展开" else "… ${g.lines.size} lines, tap to expand",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF888888),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "record" -> {
                // ── 录制文件列表（录制控制已移到采集通道区下方；本页只显示文件与保存目录）──
                var refreshTick by remember { mutableStateOf(0) }
                val files = remember(recording, logs.size, refreshTick) { recordFiles() }
                Text(
                    (if (zh) "录制文件" else "Recordings") + " · " + files.size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 保存目录详细
                Text(
                    (if (zh) "保存目录: " else "Save dir: ") + context.filesDir.absolutePath,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (files.isEmpty()) {
                    Text(
                        if (zh) "没有录制文件\n在上方输入录制名称后点击「录制」开始，日志会保存到应用私有目录" else "No recorded files\nEnter a record name above and tap Record; logs are saved to the app-private directory",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    files.forEach { f ->
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(f.lastModified()))
                            Column(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        f.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (zh) "删除" else "Delete",
                                        modifier = Modifier.clip(MaterialTheme.shapes.small)
                                            .clickable {
                                                runCatching { f.delete() }
                                                refreshTick++
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text(
                                    "$stamp · ${f.length() / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(f.absolutePath))
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (zh) "复制路径" else "Copy path", fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    context, "${context.packageName}.fileprovider", f,
                                                )
                                                val share = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(share, if (zh) "分享录制文件" else "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (zh) "分享" else "Share", fontSize = 11.sp)
                                    }
                                }
                                Text(
                                    f.absolutePath,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                }
            }
            "app" -> {
                // ── 应用列表（LogFox 样式：搜索应用，点击设包名过滤并跳转日志页）──
                Text(
                    (if (zh) "应用" else "Apps") + " · " + appList.size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (zh) "应用名或包名" else "App name or package", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                val filtered = remember(appList, appSearch) {
                    if (appSearch.isBlank()) appList
                    else appList.filter { it.first.contains(appSearch, true) || it.second.contains(appSearch, true) }
                }
                if (filtered.isEmpty()) {
                    Text(
                        if (zh) "没有匹配的应用" else "No matching apps",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    filtered.take(100).forEach { (label, pkg) ->
                        Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        pkgFilter = pkg
                                        tab = "log"
                                        filterExpanded = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    if (zh) "过滤" else "Filter",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                    }
                }
            }
            "settings" -> {
                // ── 设置（LogFox 样式：开关 + 状态提示）──
                Text(
                    if (zh) "Logcat 设置" else "Logcat settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                SettingSwitchRow(if (zh) "崩溃通知" else "Crash notifications", crashNotify) { crashNotify = it; saveSettings() }
                SettingSwitchRow(if (zh) "后台采集" else "Background collect", bgCollect) { bgCollect = it; saveSettings() }
                SettingSwitchRow(if (zh) "开机恢复" else "Restore on boot", bootRestore) { bootRestore = it; saveSettings() }
                SettingSwitchRow(if (zh) "导出设备信息" else "Export device info", exportDeviceInfo) { exportDeviceInfo = it; saveSettings() }
                Text(
                    (if (zh) "Root " else "Root ") +
                        if (PermissionManager.isRootAvailable()) (if (zh) "可用" else "available") else (if (zh) "不可用" else "unavailable") +
                        " · Shizuku " +
                        if (PermissionManager.isShizukuGranted()) (if (zh) "可用" else "available") else (if (zh) "不可用" else "unavailable") +
                        "\n" + channelInfo + (if (zh) " · 缓冲上限 2000 行" else " · buffer 2000 lines"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            else -> {
                // ── 日志页：搜索 + 实时过滤（可折叠）+ 工具栏 + 列表 ──
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (zh) "搜索" else "Search", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                // 实时过滤折叠面板（LogFox: 默认折叠，展开显示包名/PID/Tag/开关）
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .clickable { filterExpanded = !filterExpanded }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(if (zh) "实时过滤" else "Live filters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (filterExpanded) (if (zh) "收起" else "Collapse") else (if (zh) "展开" else "Expand"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        if (filterExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (filterExpanded) {
                    // 包名 + PID
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = pkgFilter,
                            onValueChange = { pkgFilter = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(if (zh) "包名" else "Package", maxLines = 1) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        )
                        OutlinedTextField(
                            value = pidFilter,
                            onValueChange = { pidFilter = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("PID", maxLines = 1) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        )
                    }
                    // Tag
                    OutlinedTextField(
                        value = tagFilter,
                        onValueChange = { tagFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (zh) "Tag 过滤（逗号分隔多 tag）" else "Tag filter (comma separated)", maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    )
                    // 开关行
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = regexEnabled, onClick = { regexEnabled = !regexEnabled }, label = { Text(if (zh) "正则" else "Regex", fontSize = 10.sp) })
                        FilterChip(selected = caseSensitive, onClick = { caseSensitive = !caseSensitive }, label = { Text(if (zh) "区分大小写" else "Case", fontSize = 10.sp) })
                        FilterChip(selected = crashOnly, onClick = { crashOnly = !crashOnly }, label = { Text(if (zh) "仅崩溃与ANR" else "Crashes", fontSize = 10.sp) })
                    }
                }
                // 级别 + 工具栏
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("V", "D", "I", "W", "E", "F").forEach { lv ->
                        FilterChip(
                            selected = lv in levelSet,
                            onClick = { levelSet = if (lv in levelSet) levelSet - lv else levelSet + lv },
                            label = { Text(lv, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = levelColor(lv)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        if (running) stop()
                        else scope.launch(Dispatchers.IO) { start() }
                    }, modifier = Modifier.size(32.dp)) {
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
                        if (appLogMode) {
                            if (zh) "应用日志 · ${appLogLines.size} 行" else "App logs · ${appLogLines.size} lines"
                        } else if (zh) "共 ${logs.size} 行" + (if (dropped > 0) "（已丢弃 $dropped 行）" else "") + if (paused) " · 已暂停" else "" + if (recording) " · 录制中" else ""
                        else "${logs.size} lines" + (if (dropped > 0) " ($dropped dropped)" else "") + if (paused) " · paused" else "" + if (recording) " · recording" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

        // ── 日志列表（最小高度保障：顶部控件挤压时列表仍可滚动）──
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), MaterialTheme.shapes.medium),
        ) {
            if (appLogMode) {
                // 应用日志模式：无系统权限时的兜底（Android 11+ 普通进程 logcat 为空）
                if (appLogLines.isEmpty()) {
                    Text(
                        if (zh) "暂无应用日志\n（软件运行日志会记录在这里，如 MCP 调用、错误等）" else "No app logs yet\n(Runtime logs such as MCP calls and errors appear here)",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    appLogLines.takeLast(500).forEach { l ->
                        Text(
                            l,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
            } else if (visible.isEmpty()) {
                Text(
                    if (zh) "暂无匹配日志…（无权限时只能看到本应用日志。全系统日志需 Root/Shizuku，或 adb 授权: adb shell pm grant com.taffynihe android.permission.READ_LOGS）" else "No matching logs… (without privilege only this app's logs are visible. System-wide logs need Root/Shizuku or adb: adb shell pm grant com.taffynihe android.permission.READ_LOGS)",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visible.forEach { line ->
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
        } // ── when(tab) 结束 ──
    }

    // ── READ_LOGS 授权引导对话框（系统保护权限无法运行时弹窗，提供自动授权/复制 adb 命令）──
    if (showReadLogsDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReadLogsDialog = false },
            title = { Text(if (zh) "访问所有设备日志" else "Read all device logs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (zh)
                            "READ_LOGS 是系统保护权限，系统不会弹出授权窗口，需要通过 adb 或特权通道授予。\n\n当前状态：${
                                if (readLogsGranted) "已授予 ✓" else "未授予"
                            }"
                        else
                            "READ_LOGS is a protected permission - no runtime dialog. Grant via adb or a privileged channel.\n\nStatus: ${
                                if (readLogsGranted) "Granted ✓" else "Not granted"
                            }",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReadLogsDialog = false
                    scope.launch(Dispatchers.IO) {
                        val granted = tryGrantReadLogs()
                        if (granted && running) { stop(); start() }
                        withContext(Dispatchers.Main) {
                            if (granted) {
                                Toast.makeText(context, if (zh) "READ_LOGS 授予成功，已可读全系统日志" else "READ_LOGS granted", Toast.LENGTH_LONG).show()
                                refreshReadLogs()
                            } else {
                                clipboard.setText(AnnotatedString("adb shell pm grant ${context.packageName} android.permission.READ_LOGS"))
                                Toast.makeText(
                                    context,
                                    if (zh) "自动授权失败（无可用特权通道）。adb 命令已复制到剪贴板" else "Auto-grant failed (no privileged channel). adb command copied",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }) { Text(if (zh) "自动授权" else "Auto-grant") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReadLogsDialog = false
                    clipboard.setText(AnnotatedString("adb shell pm grant ${context.packageName} android.permission.READ_LOGS"))
                    Toast.makeText(context, if (zh) "adb 命令已复制，电脑上执行即可" else "adb command copied", Toast.LENGTH_SHORT).show()
                }) { Text(if (zh) "复制 adb 命令" else "Copy adb cmd") }
            },
        )
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

/** LogFox 样式设置开关行（标签 + Switch）。 */
@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
