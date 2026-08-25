package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 → 动态沙箱：安装 APK → 启动 → 停止 → 卸载 的 UI 入口。
 * 对应 MCP 工具 taffy_sandbox（install/launch/watch/logs/crash/stop/uninstall），
 * 有 root 走 pm/am 命令，无 root 降级系统安装器/PackageManager。
 */
@Composable
internal fun SettingsSandboxPage(t: UiText) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    val privileged = remember(refreshTick) { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        result = if (zh) "安装中…" else "Installing…"
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
                        // 无 root: 系统安装器
                        val uriOut = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dst)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uriOut, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        app.startActivity(intent)
                        (if (zh) "已调起系统安装器（无 root 模式），安装完成后可手动启动" else "system installer opened (no-root); launch manually after install")
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
        result = if (zh) "执行中…" else "Working…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    when (op) {
                        "launch" -> if (privileged) {
                            RootShell.exec("am start -n ${p}/.MainActivity 2>&1 || am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p \"$p\" 2>&1", timeoutSec = 15).stdout.trim().take(150)
                        } else {
                            val i = app.packageManager.getLaunchIntentForPackage(p)
                            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); app.startActivity(i); (if (zh) "已启动" else "launched") } else (if (zh) "未找到启动入口" else "no launch intent")
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
                            RootShell.exec("logcat -d -t 300 2>&1", timeoutSec = 20).stdout.take(20000)
                        } else (if (zh) "无 root 无法抓全系统日志；可查看应用内日志" else "no root: system logcat unavailable")
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
        // ── 通道状态 ──
        GlassGroup(
            title = if (zh) "沙箱通道" else "Sandbox Channel",
            footer = if (zh) "有 root/Shizuku 走 pm/am 特权命令；无 root 降级系统安装器与 PackageManager" else "pm/am privileged commands with root/Shizuku; system installer & PackageManager without",
        ) {
            Text(
                (if (zh) "当前模式：" else "Mode: ") + if (privileged) (if (zh) "特权（root/Shizuku）" else "privileged") else (if (zh) "降级（无 root）" else "degraded (no root)"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ── 安装 APK ──
        GlassGroup(
            title = if (zh) "安装 APK" else "Install APK",
            footer = if (zh) "选择 APK 文件安装到设备（有 root 静默安装，无 root 调起系统安装器）" else "Pick an APK to install (silent with root, system installer without)",
        ) {
            PrimaryActionButton(
                if (zh) "选择 APK 并安装" else "Pick APK & Install",
                { pickApk.launch("application/vnd.android.package-archive") },
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // ── 应用操作 ──
        GlassGroup(
            title = if (zh) "应用操作" else "App Ops",
            footer = if (zh) "输入已安装应用的包名后操作；watch/crash 全量闭环请用 MCP 工具 taffy_sandbox" else "Enter the installed package name; full watch/crash loop via MCP taffy_sandbox",
        ) {
            OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it },
                placeholder = { Text(if (zh) "包名，如 com.example.app" else "package name, e.g. com.example.app") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                minLines = 1,
                maxLines = 2,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(if (zh) "启动" else "Launch", { runOp("launch") }, Modifier.weight(1f))
                SecondaryActionButton(if (zh) "停止" else "Stop", { runOp("stop") }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton(if (zh) "日志" else "Logs", { runOp("logs") }, Modifier.weight(1f))
                SecondaryActionButton(if (zh) "卸载" else "Uninstall", { runOp("uninstall") }, Modifier.weight(1f))
            }
        }

        // ── 输出 ──
        if (result.isNotEmpty()) {
            GlassGroup(title = if (zh) "结果" else "Result", footer = "") {
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                )
            }
        }

        // ── MCP 对应工具 ──
        GlassGroup(title = if (zh) "MCP 工具" else "MCP Tool", footer = "taffy_sandbox") {
            Text(
                if (zh) "AI/脚本可用 taffy_sandbox 做完整闭环：install(apkPath) → launch(packageName) → watch(进程存活) → logs/crash → stop/uninstall。" else "AI/scripts can use taffy_sandbox for the full loop: install(apkPath) → launch(packageName) → watch → logs/crash → stop/uninstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}
