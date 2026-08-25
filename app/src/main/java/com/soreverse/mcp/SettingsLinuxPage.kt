package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
import com.soreverse.mcp.core.LinuxRootfs
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → Linux 环境：内置 Alpine/Ubuntu rootfs 的状态、安装/删除与 shell 执行。
 * 对应 MCP 工具 taffy_linux（detect/install/shell/remove），无 root 走内置 proot。
 */
@Composable
internal fun SettingsLinuxPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var cmd by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var execInfo by remember { mutableStateOf("") }

    // 状态快照（每次 refreshTick 变化重读）
    val distros = remember(refreshTick) {
        LinuxRootfs.distros(context).map { d ->
            Triple(
                d.name,
                d.pkgMgr,
                LinuxRootfs.installed(context, d.name),
            )
        }
    }
    val channel = remember(refreshTick) {
        LinuxRootfs.channel(context) ?: "none"
    }
    val rootAvailable = remember(refreshTick) {
        RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()
    }
    val prootOk = remember(refreshTick) { LinuxRootfs.prootReady(context) }

    fun launchBusy(tag: String, block: () -> Unit) {
        if (busy != null) return
        busy = tag
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            busy = null
            refreshTick++
        }
    }

    PageScroll {
        // ── 通道状态 ──
        GlassGroup(
            title = if (zh) "执行通道" else "Execution Channel",
            footer = if (zh) "有 root/Shizuku 走原生 chroot（性能最优）；无 root 走内置 proot 用户态模拟，可正常 apk/apt 装包" else "chroot via root/Shizuku for best performance; built-in proot user-mode simulation without root (apk/apt work)",
        ) {
            Text(
                if (zh) "当前通道：${when (channel) {
                    "chroot" -> "chroot（root/Shizuku）"
                    "proot" -> "proot（无 root，内置）"
                    else -> "不可用"
                }}" else "Channel: $channel",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
                fontWeight = FontWeight.SemiBold,
            )
            GroupDivider()
            Text(
                (if (zh) "root/Shizuku：${if (rootAvailable) "可用" else "不可用"}　内置 proot：${if (prootOk) "就绪" else "未就绪"}" else "root/Shizuku: $rootAvailable  proot: $prootOk"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        // ── 发行版列表 ──
        GlassGroup(
            title = if (zh) "内置发行版" else "Built-in Distros",
            footer = if (zh) "资产随 APK 内置（assets/linux/*.tar.gz），首次使用解压；删除后重装即可" else "Bundled in APK (assets/linux/*.tar.gz), extracted on first use; remove then re-install",
        ) {
            distros.forEachIndexed { i, (name, pkgMgr, installed) ->
                if (i > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (zh) "包管理: ${pkgMgr}　状态: ${if (installed) "已解压(${LinuxRootfs.sizeMb(context, name)}MB)" else "未解压"}" else "$pkgMgr  ${if (installed) "installed ${LinuxRootfs.sizeMb(context, name)}MB" else "not extracted"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (installed) {
                        SecondaryActionButton(
                            if (zh) "删除" else "Remove",
                            { launchBusy("rm:$name") { LinuxRootfs.remove(context, name) } },
                            Modifier.height(40.dp),
                        )
                    } else {
                        PrimaryActionButton(
                            if (zh) "安装" else "Install",
                            { launchBusy("in:$name") { LinuxRootfs.ensureExtracted(context, name) } },
                            Modifier.height(40.dp),
                        )
                    }
                }
            }
            if (busy != null) {
                Text(
                    if (zh) "处理中…（Ubuntu 首次解压约 96MB，请稍候）" else "Working… (Ubuntu ~96MB on first extract)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }

        // ── Shell 执行 ──
        GlassGroup(
            title = if (zh) "Shell 执行" else "Shell",
            footer = if (zh) "在 rootfs 内执行命令（默认 Alpine）。脚本写文件后执行，规避引号问题；建议先: apk update" else "Run commands inside rootfs (default Alpine). Script is written to file then executed; try: apk update first",
        ) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text(if (zh) "输入命令，如: uname -a 或 apk add python3" else "e.g. uname -a or apk add python3") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                minLines = 1,
                maxLines = 4,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(
                    if (zh) "在 Alpine 执行" else "Run in Alpine",
                    {
                        val c = cmd.trim()
                        if (c.isEmpty()) return@PrimaryActionButton
                        output = if (zh) "执行中…" else "Running…"
                        execInfo = ""
                        launchBusy("exec") {
                            val r = LinuxRootfs.exec(context, "alpine", c, timeoutSec = 60)
                            output = r?.output ?: (if (zh) "执行失败（发行版未就绪）" else "exec failed (distro not ready)")
                            execInfo = r?.let { "exit=${it.code} channel=${it.channel}" } ?: ""
                        }
                    },
                    Modifier.weight(1f),
                )
                SecondaryActionButton(
                    if (zh) "清空" else "Clear",
                    { output = ""; execInfo = "" },
                    Modifier.weight(0.5f),
                )
            }
            if (output.isNotEmpty()) {
                GroupDivider()
                Text(
                    output.take(20000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(),
                )
                if (execInfo.isNotEmpty()) {
                    Text(
                        execInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // ── MCP 对应工具 ──
        GlassGroup(
            title = if (zh) "MCP 工具" else "MCP Tool",
            footer = "taffy_linux",
        ) {
            Text(
                if (zh) "AI/脚本可用 taffy_linux 调用同能力：action=detect / install / shell / remove，distro 支持 alpine、ubuntu 及任意内置 rootfs。" else "AI/scripts can call taffy_linux: detect / install / shell / remove with distro=alpine|ubuntu|any bundled rootfs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}
