package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 → 动态沙箱：安装 → 启动 → 看门狗 → 日志/崩溃 → 停止/卸载 的 UI 入口。
 * 对应 MCP 工具 taffy_sandbox（install/launch/watch/logs/crash/stop/uninstall）。
 * 有 root 走 pm/am 特权命令，无 root 降级系统安装器/PackageManager。
 *
 * 图形化要点：破坏性操作（停止/卸载）二次确认、耗时操作覆盖式进度（看门狗可取消）、
 * 结果统一终端框、权限状态可刷新。
 */
@Composable
internal fun SettingsSandboxPage(t: UiText) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var watchSec by remember { mutableStateOf("10") }
    var watchInterval by remember { mutableStateOf("2") }
    var cancelWatch by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }

    val privileged = remember(refreshTick) { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        busyMsg = if (zh) "安装中…" else "Installing…"
        result = ""
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val dir = File(app.filesDir, "sandbox").apply { mkdirs() }
                    val dst = File(dir, "target_${System.currentTimeMillis()}.apk")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@withContext (if (zh) "无法读取所选文件" else "cannot read file")
                    if (privileged) {
                        val r = RootShell.exec("pm install -r \"${dst.absolutePath}\" 2>&1", timeoutSec = 120)
                        dst.delete()
                        if (r.stdout.contains("Success", ignoreCase = true)) (if (zh) "安装成功（pm）" else "installed (pm)") else "pm: ${r.stdout.trim().take(200)}"
                    } else {
                        val uriOut = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dst)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uriOut, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        app.startActivity(intent)
                        (if (zh) "已调起系统安装器（无 root 模式），安装完成后可手动启动" else "system installer opened (no-root)")
                    }
                } catch (e: Exception) {
                    (if (zh) "安装失败: " else "install failed: ") + (e.message ?: e.javaClass.simpleName)
                }
            }
            result = msg
            busy = false
        }
    }

    fun runOp(op: String) {
        val p = pkg.trim()
        if (p.isEmpty() || busy) return
        busy = true
        cancelWatch = false
        busyMsg = when (op) {
            "launch" -> if (zh) "启动中…" else "Launching…"
            "stop" -> if (zh) "停止中…" else "Stopping…"
            "uninstall" -> if (zh) "卸载中…" else "Uninstalling…"
            "logs" -> if (zh) "抓取日志…" else "Reading logs…"
            "crash" -> if (zh) "收集崩溃…" else "Collecting crashes…"
            "watch" -> if (zh) "看门狗运行中…" else "Watchdog running…"
            else -> if (zh) "执行中…" else "Working…"
        }
        result = ""
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    when (op) {
                        "launch" -> if (privileged) {
                            RootShell.exec("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p \"$p\" 2>&1", timeoutSec = 15).stdout.trim().take(150)
                        } else {
                            val i = app.packageManager.getLaunchIntentForPackage(p)
                            if (i != null) {
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                app.startActivity(i)
                                (if (zh) "已启动" else "launched")
                            } else {
                                (if (zh) "未找到启动入口" else "no launch intent")
                            }
                        }
                        "stop" -> if (privileged) {
                            RootShell.exec("am force-stop \"$p\" 2>&1", timeoutSec = 15).stdout.trim().take(150).ifEmpty { (if (zh) "已停止" else "stopped") }
                        } else {
                            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                            am.killBackgroundProcesses(p)
                            (if (zh) "已请求停止（无 root 仅杀后台）" else "kill requested (background only)")
                        }
                        "uninstall" -> if (privileged) {
                            RootShell.exec("pm uninstall \"$p\" 2>&1", timeoutSec = 30).stdout.trim().take(150)
                        } else {
                            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$p"))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            app.startActivity(i)
                            (if (zh) "已调起系统卸载器" else "system uninstaller opened")
                        }
                        "logs" -> if (privileged) {
                            RootShell.exec("logcat -d -t 300 2>&1 | grep -E \"\\b$p\\b|FATAL EXCEPTION\" | tail -100", timeoutSec = 20).stdout.take(20000)
                        } else {
                            (if (zh) "无 root 无法抓全系统日志；可查看应用内日志" else "no root: system logcat unavailable")
                        }
                        "crash" -> if (privileged) {
                            RootShell.exec("logcat -d -t 800 2>&1 | grep -A 20 -E 'FATAL EXCEPTION|ANR in |SIGSEGV|SIGABRT|Process: $p' | tail -150", timeoutSec = 20).stdout.take(20000)
                                .ifEmpty { (if (zh) "未捕获到崩溃/ANR（可先启动应用复现后抓取）" else "no crash/ANR captured") }
                        } else {
                            (if (zh) "无 root 无法收集崩溃日志" else "no root: crash log unavailable")
                        }
                        "watch" -> {
                            if (!privileged) {
                                (if (zh) "看门狗需 root/Shizuku（pidof/ps 轮询）" else "watch needs root/Shizuku")
                            } else {
                                val dur = watchSec.toIntOrNull()?.coerceIn(1, 120) ?: 10
                                val interval = watchInterval.toIntOrNull()?.coerceIn(1, 30) ?: 2
                                val lines = mutableListOf<String>()
                                val start = System.currentTimeMillis()
                                var alive = true
                                while (alive && (System.currentTimeMillis() - start) < dur * 1000L && !cancelWatch) {
                                    val out = RootShell.exec("pidof \"$p\" 2>/dev/null || ps -A 2>/dev/null | grep \"$p\" | grep -v grep | head -1", timeoutSec = 8).stdout.trim()
                                    alive = out.isNotBlank()
                                    val elapsed = (System.currentTimeMillis() - start) / 1000
                                    lines.add(
                                        (if (zh) "第 ${elapsed}s: " else "${elapsed}s: ") +
                                            if (alive) (if (zh) "进程存活 (pid=${out.split(' ').firstOrNull()})" else "alive (pid=${out.split(' ').firstOrNull()})")
                                            else (if (zh) "进程已退出/被杀" else "process exited/killed"),
                                    )
                                    if (alive && !cancelWatch) Thread.sleep(interval * 1000L)
                                }
                                if (cancelWatch) lines.add(if (zh) "（已手动取消）" else "(cancelled)")
                                lines.joinToString("\n")
                            }
                        }
                        else -> ""
                    }
                } catch (e: Exception) {
                    (if (zh) "操作失败: " else "failed: ") + (e.message ?: e.javaClass.simpleName)
                }
            }
            result = msg
            busy = false
        }
    }

    PageScroll {
        GlassGroup(
            title = if (zh) "沙箱通道" else "Sandbox Channel",
            footer = if (zh) "有 root/Shizuku 走 pm/am 特权命令；无 root 降级系统安装器与 PackageManager" else "pm/am privileged with root/Shizuku; system installer without",
        ) {
            DataRow(
                title = if (privileged) (if (zh) "特权模式" else "Privileged") else (if (zh) "降级模式（无 root）" else "Degraded (no root)"),
                subtitle = if (privileged) (if (zh) "root / Shizuku 可用" else "root / Shizuku available") else (if (zh) "仅系统安装器与 PackageManager" else "system installer only"),
                trailingText = if (zh) "刷新" else "Refresh",
                onClick = { refreshTick++ },
            )
        }

        GlassGroup(
            title = if (zh) "安装 APK" else "Install APK",
            footer = if (zh) "选择 APK 安装到设备（有 root 静默安装，无 root 调起系统安装器）" else "Pick an APK to install",
        ) {
            PrimaryActionButton(
                if (zh) "选择 APK 并安装" else "Pick APK & Install",
                { pickApk.launch("application/vnd.android.package-archive") },
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        GlassGroup(
            title = if (zh) "应用操作" else "App Ops",
            footer = if (zh) "输入已安装应用的包名后操作" else "Enter installed package name",
        ) {
            OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it },
                placeholder = { Text(if (zh) "包名，如 com.example.app" else "package name") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(if (zh) "启动" else "Launch", { runOp("launch") }, Modifier.weight(1f))
                SecondaryActionButton(if (zh) "停止" else "Stop", { pendingAction = "stop" }, Modifier.weight(1f))
                SecondaryActionButton(if (zh) "卸载" else "Uninstall", { pendingAction = "uninstall" }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton(if (zh) "日志" else "Logs", { runOp("logs") }, Modifier.weight(1f))
                SecondaryActionButton(if (zh) "崩溃收集" else "Crash", { runOp("crash") }, Modifier.weight(1f))
            }
        }

        GlassGroup(
            title = if (zh) "进程看门狗" else "Watchdog",
            footer = if (zh) "轮询指定时长，观察进程存活/被杀时刻（需 root/Shizuku，可取消）" else "Poll liveness for a duration (needs root/Shizuku, cancellable)",
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = watchSec,
                    onValueChange = { watchSec = it.filter { c -> c.isDigit() } },
                    label = { Text(if (zh) "时长(秒)" else "Seconds") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = watchInterval,
                    onValueChange = { watchInterval = it.filter { c -> c.isDigit() } },
                    label = { Text(if (zh) "间隔(秒)" else "Interval") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            PrimaryActionButton(
                if (zh) "开始看门狗" else "Start Watchdog",
                { runOp("watch") },
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        if (result.isNotEmpty()) {
            TerminalPane(
                text = result,
                title = if (zh) "结果" else "Result",
                onClear = { result = "" },
                maxHeight = 460.dp,
            )
        }

        GlassGroup(title = if (zh) "MCP 工具" else "MCP Tool", footer = "taffy_sandbox") {
            InlineHint(
                if (zh) "AI/脚本可用 taffy_sandbox 做完整闭环：install(apkPath) → launch(packageName) → watch(duration/interval) → logs/crash → stop/uninstall。"
                else "AI/scripts can use taffy_sandbox for the full loop: install → launch → watch → logs/crash → stop/uninstall.",
            )
        }
    }

    pendingAction?.let { action ->
        val isUninstall = action == "uninstall"
        ConfirmDialog(
            title = if (isUninstall) (if (zh) "卸载应用？" else "Uninstall app?") else (if (zh) "停止应用？" else "Stop app?"),
            message = if (isUninstall) {
                if (zh) "将卸载 $pkg。卸载会清除该应用数据，操作不可恢复。" else "Uninstalls $pkg. App data is removed; this cannot be undone."
            } else {
                if (zh) "将停止 $pkg 的运行。" else "Stops the running $pkg."
            },
            confirmText = if (isUninstall) (if (zh) "卸载" else "Uninstall") else (if (zh) "停止" else "Stop"),
            destructive = isUninstall,
            onConfirm = { runOp(action) },
            onDismiss = { pendingAction = null },
        )
    }

    BusyOverlay(
        visible = busy,
        message = busyMsg.ifBlank { if (zh) "执行中…" else "Working…" },
        onCancel = if (busyMsg.contains("看门狗") || busyMsg.contains("Watchdog")) ({ cancelWatch = true }) else null,
    )
}
