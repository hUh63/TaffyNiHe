package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
 * 图形化要点：通道/发行版用卡片行、执行输出用统一终端框（可选中复制）、命令支持回车执行、
 * 键盘避让、删除发行版二次确认、耗时操作覆盖式进度。
 */
@Composable
internal fun SettingsLinuxPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    val metrics = LocalUiMetrics.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("alpine") }
    var cmd by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var execInfo by remember { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    val distros = remember(refreshTick) {
        LinuxRootfs.distros(context).map { d -> Triple(d.name, d.pkgMgr, LinuxRootfs.installed(context, d.name)) }
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
        output = ""
        execInfo = if (zh) "执行中…" else "Running…"
        busy = "exec"
        scope.launch {
            val r = withContext(Dispatchers.IO) { LinuxRootfs.exec(context, distro, script, timeoutSec = 60) }
            output = r?.output ?: (if (zh) "执行失败（$distro 未就绪，先安装）" else "failed ($distro not ready)")
            execInfo = r?.let { "exit=${it.code}  channel=${it.channel}" } ?: ""
            busy = null
        }
    }

    val quickCommands = remember(target) {
        when (target) {
            "ubuntu" -> listOf(
                "uname -a" to (if (zh) "系统信息" else "uname"),
                "cat /etc/os-release" to (if (zh) "版本" else "release"),
                "apt update" to (if (zh) "更新源" else "apt update"),
                "apt install -y python3" to (if (zh) "装 python3" else "install python3"),
                "apt list --installed | head -20" to (if (zh) "已装软件" else "installed"),
                "df -h /" to (if (zh) "磁盘" else "disk"),
            )
            else -> listOf(
                "uname -a" to (if (zh) "系统信息" else "uname"),
                "cat /etc/os-release" to (if (zh) "版本" else "release"),
                "apk update" to (if (zh) "更新源" else "apk update"),
                "apk add python3" to (if (zh) "装 python3" else "install python3"),
                "apk list --installed | head -20" to (if (zh) "已装软件" else "installed"),
                "df -h /" to (if (zh) "磁盘" else "disk"),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = metrics.pagePad)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
    ) {
        GlassGroup(
            title = if (zh) "执行通道" else "Execution Channel",
            footer = if (zh) "root/Shizuku → 原生 chroot（性能最优）；无 root → 内置 proot 用户态模拟，可正常 apk/apt 装包" else "chroot via root/Shizuku; built-in proot without root",
        ) {
            DataRow(
                title = when (channel) {
                    "chroot" -> if (zh) "chroot（root/Shizuku）" else "chroot (root/Shizuku)"
                    "proot" -> if (zh) "proot（无 root，内置）" else "proot (no root, built-in)"
                    else -> if (zh) "不可用" else "Unavailable"
                },
                subtitle = (if (zh) "root/Shizuku：" else "root/Shizuku: ") + (if (rootAvailable) (if (zh) "可用" else "yes") else (if (zh) "不可用" else "no")) +
                    "  ·  " + (if (zh) "内置 proot：" else "proot: ") + (if (prootOk) (if (zh) "就绪" else "ready") else (if (zh) "未就绪" else "not ready")),
                trailingText = if (zh) "刷新" else "Refresh",
                onClick = { refreshTick++ },
            )
        }

        GlassGroup(
            title = if (zh) "内置发行版" else "Built-in Distros",
            footer = if (zh) "资产随 APK 内置，首次使用解压；删除后重装即可" else "Bundled in APK, extracted on first use",
        ) {
            distros.forEachIndexed { i, (name, pkgMgr, installed) ->
                if (i > 0) GroupDivider()
                DataRow(
                    title = name,
                    subtitle = (if (zh) "包管理 " else "pkg ") + pkgMgr + "  ·  " +
                        if (installed) (if (zh) "已解压 ${LinuxRootfs.sizeMb(context, name)}MB" else "installed ${LinuxRootfs.sizeMb(context, name)}MB")
                        else (if (zh) "未解压" else "not extracted"),
                    trailingText = if (installed) (if (zh) "删除" else "Remove") else (if (zh) "安装" else "Install"),
                    onClick = {
                        if (installed) {
                            pendingRemove = name
                        } else {
                            launchBusy("in:$name") { LinuxRootfs.ensureExtracted(context, name) }
                        }
                    },
                )
            }
        }

        GlassGroup(
            title = if (zh) "Shell 执行" else "Shell",
            footer = if (zh) "选择执行目标后运行命令；脚本写文件后执行，规避引号问题" else "Pick target distro then run",
        ) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                distros.forEach { (name, _, installed) ->
                    FilterChip(
                        selected = target == name,
                        enabled = installed,
                        onClick = { target = name },
                        label = { Text(name) },
                    )
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickCommands.forEach { (script, label) ->
                    FilterChip(
                        selected = false,
                        onClick = { runIn(target, script) },
                        label = { Text(label) },
                        enabled = busy == null,
                    )
                }
            }
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text(if (zh) "输入命令，如 uname -a 或 apk add python3" else "e.g. uname -a or apk add python3", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                minLines = 1,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val c = cmd.trim()
                    if (c.isNotEmpty()) runIn(target, c)
                }),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(
                    (if (zh) "在 " else "Run in ") + target,
                    {
                        val c = cmd.trim()
                        if (c.isNotEmpty()) runIn(target, c)
                    },
                    Modifier.weight(1f),
                )
                SecondaryActionButton(
                    if (zh) "清空" else "Clear",
                    { output = ""; execInfo = "" },
                    Modifier.weight(1f),
                )
            }
            if (output.isNotEmpty() || execInfo.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    TerminalPane(
                        text = output,
                        title = if (execInfo.isNotEmpty()) execInfo else null,
                        onClear = { output = ""; execInfo = "" },
                        maxHeight = 460.dp,
                    )
                }
            }
        }

        GlassGroup(title = if (zh) "MCP 工具" else "MCP Tool", footer = "taffy_linux") {
            InlineHint(
                if (zh) "AI/脚本可用 taffy_linux 调用同能力：action=detect / install / shell / remove，distro 支持 alpine、ubuntu 及任意内置 rootfs。"
                else "AI/scripts can call taffy_linux: detect / install / shell / remove with distro=alpine|ubuntu.",
            )
        }
    }

    pendingRemove?.let { name ->
        ConfirmDialog(
            title = if (zh) "删除发行版？" else "Remove distro?",
            message = if (zh) "将删除已解压的 $name rootfs（约 ${LinuxRootfs.sizeMb(context, name)}MB），已安装的软件包会一并丢失，可重新安装。" else "Removes the extracted $name rootfs; installed packages are lost. Re-install is possible.",
            confirmText = if (zh) "删除" else "Remove",
            destructive = true,
            onConfirm = { launchBusy("rm:$name") { LinuxRootfs.remove(context, name) } },
            onDismiss = { pendingRemove = null },
        )
    }

    BusyOverlay(
        visible = busy != null,
        message = when {
            busy == null -> ""
            busy!!.startsWith("in:") -> if (zh) "正在解压发行版…（Ubuntu 约 96MB，请稍候）" else "Extracting…"
            busy!!.startsWith("rm:") -> if (zh) "正在删除…" else "Removing…"
            busy == "exec" -> if (zh) "正在执行命令…" else "Running command…"
            else -> if (zh) "处理中…" else "Working…"
        },
    )
}
