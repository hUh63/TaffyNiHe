package com.soreverse.mcp

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 权限管理页（设置 → 诊断与关于 → 权限管理）。
 * 统一管理塔菲逆核的高级权限通道：Root / Shizuku / Dhizuku。
 * eDBG、eBPF dex、增强 logcat 等功能依赖这里的通道授权。
 */
@Composable
internal fun SettingsPermissionsPage(t: UiText) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var probeText by remember { mutableStateOf("") }
    val zh = t.zh

    // Shizuku 授权结果监听：授权完成后立即刷新状态
    val permissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh++ }
    }
    DisposableEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        onDispose { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }
    @Suppress("UNUSED_EXPRESSION") refresh

    val rootOk = PermissionManager.isRootAvailable()
    val shizukuService = PermissionManager.isShizukuServiceRunning()
    val shizukuGranted = PermissionManager.isShizukuGranted()
    val shizukuApp = PermissionManager.isShizukuAppInstalled(context)
    val dhizukuOk = PermissionManager.isDhizukuAvailable()
    val dhizukuApp = PermissionManager.isDhizukuAppInstalled(context)
    val best = PermissionManager.bestChannel()

    fun runProbe() {
        probing = true; probeText = ""
        scope.launch {
            val text = withContext(Dispatchers.IO) { PermissionManager.selfTest().toString(2) }
            probeText = text; probing = false
        }
    }

    PageScroll {
        // ── 总览 ──
        GlassGroup(title = if (zh) "特权通道" else "Privileged channels") {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (zh) "当前最高可用通道" else "Best available channel", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (best) {
                            PermissionManager.Channel.NONE -> if (zh) "无（普通应用权限）" else "None (normal app)"
                            PermissionManager.Channel.SHIZUKU -> "Shizuku (adb shell, uid 2000)"
                            PermissionManager.Channel.ROOT -> "Root (uid 0)"
                            PermissionManager.Channel.DHIZUKU -> "Dhizuku (device owner)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GroupDivider()
            Text(
                if (zh) "eDBG 附加调试、eBPF dex 追踪、进程级操作需要 Root 或 Shizuku；Dhizuku 提供设备所有者级能力。下方可逐项检测与授权。"
                else "eDBG attach, eBPF dex tracing and process-level ops need Root or Shizuku; Dhizuku provides device-owner capabilities. Check and grant below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // ── Root ──
        GlassGroup(title = if (zh) "Root 权限" else "Root access") {
            NavRow(
                title = if (zh) "Root 可用性" else "Root availability",
                subtitle = if (rootOk) (if (zh) "已授权（Magisk / KernelSU 等）" else "Granted (Magisk / KernelSU etc.)") else (if (zh) "未检测到 Root" else "Root not detected"),
                icon = Icons.Default.GppGood,
                iconTint = if (rootOk) AppPalette.green else MaterialTheme.colorScheme.error,
                trailing = if (rootOk) "uid=0" else null,
            )
            GroupDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    PermissionManager.invalidateCaches()
                    refresh++
                }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (zh) "重新检测" else "Re-check")
                }
            }
            Text(
                if (zh) "首次使用需在 Magisk 超级用户 或 KernelSU 授权弹窗中允许本应用。若已授予仍检测不到，请打开授权管理确认。"
                else "Allow this app in Magisk superuser or KernelSU when prompted. If still not detected after granting, check the su manager.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // ── Shizuku ──
        GlassGroup(title = "Shizuku", footer = if (zh) "Shizuku 通过 adb 或无线调试授权，提供 shell 级权限，无需 Root。" else "Shizuku grants shell-level privileges via adb or wireless debugging, no root needed.") {
            NavRow(
                title = if (zh) "Shizuku 应用" else "Shizuku app",
                subtitle = if (shizukuApp) (if (zh) "已安装" else "Installed") else (if (zh) "未安装" else "Not installed"),
                icon = Icons.Default.VerifiedUser,
                iconTint = if (shizukuApp) AppPalette.indigo else MaterialTheme.colorScheme.error,
            )
            GroupDivider()
            NavRow(
                title = if (zh) "Shizuku 服务" else "Shizuku service",
                subtitle = if (shizukuService) (if (zh) "运行中" else "Running") else (if (zh) "未运行（请在 Shizuku 应用中启动）" else "Not running (start it in the Shizuku app)"),
                icon = Icons.Default.AdminPanelSettings,
                iconTint = if (shizukuService) AppPalette.green else MaterialTheme.colorScheme.error,
            )
            GroupDivider()
            NavRow(
                title = if (zh) "授权状态" else "Grant status",
                subtitle = when {
                    shizukuGranted -> if (zh) "已授权，可执行 shell" else "Granted, shell available"
                    shizukuService -> if (zh) "服务运行中，未授权" else "Service running, not granted"
                    else -> if (zh) "等待服务启动" else "Waiting for service"
                },
                icon = Icons.Default.Lock,
                iconTint = if (shizukuGranted) AppPalette.green else MaterialTheme.colorScheme.error,
                trailing = if (shizukuGranted) "uid=2000" else null,
            )
            GroupDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    enabled = shizukuService && !shizukuGranted,
                    onClick = { runCatching { Shizuku.requestPermission(10086) } },
                ) {
                    Text(if (zh) "请求授权" else "Request permission")
                }
                TextButton(onClick = { refresh++ }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (zh) "刷新" else "Refresh")
                }
            }
            Text(
                if (zh) "未安装时请先安装 Shizuku（GitHub: RikkaApps/Shizuku），再按应用内引导用 adb 或无线调试启动。"
                else "Install Shizuku (GitHub: RikkaApps/Shizuku) first, then start it via adb or wireless debugging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // ── Dhizuku ──
        GlassGroup(title = "Dhizuku", footer = if (zh) "Dhizuku 将本应用设为设备所有者，可获得系统级能力。激活需要 Root。" else "Dhizuku sets this app as device owner for system-level capabilities. Activation requires Root.") {
            NavRow(
                title = if (zh) "Dhizuku 应用" else "Dhizuku app",
                subtitle = if (dhizukuApp) (if (zh) "已安装" else "Installed") else (if (zh) "未安装" else "Not installed"),
                icon = Icons.Default.AdminPanelSettings,
                iconTint = if (dhizukuApp) AppPalette.orange else MaterialTheme.colorScheme.error,
            )
            GroupDivider()
            NavRow(
                title = if (zh) "设备所有者状态" else "Device owner status",
                subtitle = if (dhizukuOk) (if (zh) "已激活" else "Active") else (if (zh) "未激活" else "Not active"),
                icon = Icons.Default.GppGood,
                iconTint = if (dhizukuOk) AppPalette.green else MaterialTheme.colorScheme.error,
            )
            Text(
                if (zh) "激活步骤：安装 Dhizuku 应用 → 在 Dhizuku 内授予 Root → 点击激活为设备所有者。注意：设置设备所有者前需移除已有账户/工作资料，激活后部分系统功能受限。"
                else "Steps: install Dhizuku → grant Root inside it → activate as device owner. Note: remove existing accounts/work profile first; some system features become restricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // ── 自检 ──
        GlassGroup(title = if (zh) "通道自检" else "Channel self-test") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                TextButton(enabled = !probing, onClick = { runProbe() }) {
                    if (probing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (zh) "探测中…" else "Probing…")
                    } else {
                        Text(if (zh) "运行自检（探测各通道 uid）" else "Run self-test (probe uid per channel)")
                    }
                }
            }
            if (probeText.isNotBlank()) {
                Text(
                    probeText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.9f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // ── 功能对应 ──
        GlassGroup(title = if (zh) "权限与功能对应" else "Capability mapping") {
            Text(
                if (zh) {
                    "• 增强 logcat（LogFox 级）：无需特权\n" +
                        "• eDBG 附加调试：Root 或 Shizuku\n" +
                        "• eBPF dex 追踪：Root（需内核支持 BPF）\n" +
                        "• 文件级操作：Shizuku 或 Root（配合 MANAGE_EXTERNAL_STORAGE）"
                } else {
                    "• Enhanced logcat (LogFox-grade): no privilege needed\n" +
                        "• eDBG attach debugging: Root or Shizuku\n" +
                        "• eBPF dex tracing: Root (kernel BPF required)\n" +
                        "• File-level ops: Shizuku or Root (with MANAGE_EXTERNAL_STORAGE)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
