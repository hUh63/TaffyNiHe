package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.LinuxRootfs
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → Linux 环境：内置 Alpine/Ubuntu rootfs 的管理与 shell 执行。
 * 对应 MCP 工具 taffy_linux（detect/install/shell/remove）。
 *
 * 功能：通道状态 / 发行版安装删除 / 执行目标切换（Alpine⇄Ubuntu）/
 * 快捷命令（按发行版自适应 apk/apt）/ 自由命令执行。
 */
@Composable
internal fun SettingsLinuxPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("alpine") }   // 执行目标发行版
    var cmd by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var execInfo by remember { mutableStateOf("") }

    // 状态快照（refreshTick 变化重读）
    val distros = remember(refreshTick) {
        LinuxRootfs.distros(context).map { d ->
            Triple(d.name, d.pkgMgr, LinuxRootfs.installed(context, d.name))
        }
    }
    val channel = remember(refreshTick) { LinuxRootfs.channel(context) ?: "none" }
    val rootAvailable = remember(refreshTick) { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }
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

    fun runIn(distro: String, script: String) {
        if (busy != null) return
        output = if (zh) "执行中…" else "Running…"
        execInfo = ""
        busy = "exec"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                LinuxRootfs.exec(context, distro, script, timeoutSec = 60)
            }
            output = r?.output ?: (if (zh) "执行失败（$distro 未就绪，先安装）" else "failed ($distro not ready)")
            execInfo = r?.let { "exit=${it.code} channel=${it.channel}" } ?: ""
            busy = null
        }
    }

    // 按发行版自适应的快捷命令
    val quickCommands = remember(target) {
        when (target) {
            "ubuntu" -> listOf(
                "uname -a" to "系统信息",
                "cat /etc/os-release" to "版本",
                "apt update" to "更新源",
                "apt install -y python3" to "装 python3",
                "apt list --installed | head -20" to "已装软件",
                "df -h /" to "磁盘",
            )
            else -> listOf(
                "uname -a" to "系统信息",
                "cat /etc/os-release" to "版本",
                "apk update" to "更新源",
                "apk add python3" to "装 python3",
                "apk list --installed | head -20" to "已装软件",
                "df -h /" to "磁盘",
            )
        }
    }

    PageScroll {
        // ── 执行通道 ──
        GlassGroup(
            title = if (zh) "执行通道" else "Execution Channel",
            footer = if (zh) "root/Shizuku → 原生 chroot（性能最优）；无 root → 内置 proot 用户态模拟，可正常 apk/apt 装包" else "chroot via root/Shizuku; built-in proot user-mode without root (apk/apt work)",
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

        // ── 内置发行版 ──
        GlassGroup(
            title = if (zh) "内置发行版" else "Built-in Distros",
            footer = if (zh) "资产随 APK 内置（assets/linux/*.tar.gz），首次使用解压；删除后重装即可" else "Bundled in APK, extracted on first use; remove then re-install",
        ) {
            distros.forEachIndexed { i, (name, pkgMgr, installed) ->
                if (i > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (zh) "包管理: ${pkgMgr}　状态: ${if (installed) "已解压(${LinuxRootfs.sizeMb(context, name)}MB)" else "未解压"}" else "$pkgMgr  ${if (installed) "installed ${LinuxRootfs.sizeMb(context, name)}MB" else "not extracted"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
            footer = if (zh) "选择执行目标（Alpine/Ubuntu）后运行命令；脚本写文件后执行，规避引号问题" else "Pick target distro then run; script written to file to avoid quoting issues",
        ) {
            // 执行目标切换
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                distros.forEach { (name, _, installed) ->
                    FilterChip(
                        selected = target == name,
                        enabled = installed,
                        onClick = { target = name },
                        label = { Text(name, fontSize = 12.sp) },
                    )
                }
                Text(
                    if (zh) "（未安装的发行版不可选）" else "(uninstalled distros disabled)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                )
            }
            // 快捷命令
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickCommands.forEach { (script, label) ->
                    FilterChip(
                        selected = false,
                        onClick = { runIn(target, script) },
                        label = { Text(label, fontSize = 10.sp) },
                        enabled = busy == null,
                    )
                }
            }
            // 命令输入
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
                    (if (zh) "在 " else "Run in ") + target,
                    {
                        val c = cmd.trim()
                        if (c.isEmpty()) return@PrimaryActionButton
                        runIn(target, c)
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
        GlassGroup(title = if (zh) "MCP 工具" else "MCP Tool", footer = "taffy_linux") {
            Text(
                if (zh) "AI/脚本可用 taffy_linux 调用同能力：action=detect / install / shell / remove，distro 支持 alpine、ubuntu 及任意内置 rootfs。" else "AI/scripts can call taffy_linux: detect / install / shell / remove with distro=alpine|ubuntu|any bundled rootfs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}
