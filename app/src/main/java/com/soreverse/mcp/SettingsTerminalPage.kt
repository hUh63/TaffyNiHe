package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 终端执行：内置 Python（零依赖，无 root 可用）与 Termux 运行时。
 * 对应 MCP 工具 taffy_terminal_exec（detect/run）。
 *
 * 功能：运行时检测与手动选择 / 常用脚本模板 / 执行输出。
 */
@Composable
internal fun SettingsTerminalPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var runtime by remember { mutableStateOf("builtin-python") }
    var script by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var execInfo by remember { mutableStateOf("") }

    val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    // 运行时检测
    val builtinPyPath = remember(refreshTick) { PythonRuntime.pythonPath(context) }
    val builtinPyVersion = remember(refreshTick) {
        if (builtinPyPath != null) {
            PythonRuntime.run(context, "import sys; print(sys.version.split()[0])", timeoutSec = 15).output.trim()
        } else ""
    }
    val privileged = remember(refreshTick) { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }
    data class Rt(val key: String, val label: String, val bin: String, val available: Boolean, val desc: String)
    val runtimes = remember(refreshTick, privileged) {
        val list = mutableListOf(
            Rt("builtin-python", if (zh) "内置 Python" else "Built-in Py", "builtin", builtinPyPath != null,
                if (zh) "零依赖 · 无 root 可用" else "no-root, zero-dep"),
        )
        if (privileged) {
            list += listOf(
                Rt("termux-python", "Termux Python", "$TERMUX_PREFIX/bin/python3",
                    RootShell.exec("test -x \"$TERMUX_PREFIX/bin/python3\" && echo Y || echo N", timeoutSec = 8).stdout.trim() == "Y", "pkg install python"),
                Rt("termux-node", "Termux Node", "$TERMUX_PREFIX/bin/node",
                    RootShell.exec("test -x \"$TERMUX_PREFIX/bin/node\" && echo Y || echo N", timeoutSec = 8).stdout.trim() == "Y", "pkg install nodejs"),
                Rt("termux-busybox", "Termux BusyBox", "$TERMUX_PREFIX/bin/busybox",
                    RootShell.exec("test -x \"$TERMUX_PREFIX/bin/busybox\" && echo Y || echo N", timeoutSec = 8).stdout.trim() == "Y", "pkg install busybox"),
                Rt("termux-bash", "Termux Bash", "$TERMUX_PREFIX/bin/bash",
                    RootShell.exec("test -x \"$TERMUX_PREFIX/bin/bash\" && echo Y || echo N", timeoutSec = 8).stdout.trim() == "Y", "pkg install bash"),
                Rt("termux-sh", "Termux sh", "$TERMUX_PREFIX/bin/sh",
                    RootShell.exec("test -x \"$TERMUX_PREFIX/bin/sh\" && echo Y || echo N", timeoutSec = 8).stdout.trim() == "Y", "Termux 自带"),
            )
        }
        list
    }

    // 常用模板
    val templates = listOf(
        "print('hello from taffy')" to (if (zh) "Python Hello" else "Py Hello"),
        "import platform; print(platform.platform()); print(platform.machine())" to (if (zh) "系统信息" else "Sys info"),
        "import urllib.request; print(urllib.request.urlopen('https://www.baidu.com', timeout=5).status)" to (if (zh) "网络请求" else "HTTP GET"),
        "uname -a" to (if (zh) "内核信息" else "Kernel"),
        "node -v && npm -v" to "Node 版本",
    )

    fun run() {
        val s = script.trim()
        if (s.isEmpty() || busy) return
        busy = true
        output = if (zh) "执行中…" else "Running…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val rt = runtimes.firstOrNull { it.key == runtime } ?: runtimes.first()
                when {
                    rt.key == "builtin-python" -> {
                        val res = PythonRuntime.run(context, s, timeoutSec = 60)
                        Triple(res.code, res.output, "内置 Python")
                    }
                    rt.available -> {
                        val env = "PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:/system/bin HOME=/data/data/com.termux/files/home"
                        val bin = rt.bin
                        // 脚本写临时文件执行，规避引号问题
                        val tmp = "/data/local/tmp/taffy_ui_${System.currentTimeMillis()}"
                        val b64 = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
                        val r = PermissionManager.exec("echo $b64 | base64 -d > $tmp && env $env \"$bin\" $tmp 2>&1; rm -f $tmp", timeoutSec = 60)
                        Triple(r.code, r.stdout, rt.label)
                    }
                    else -> Triple(-1, (if (zh) "该运行时不可用（未安装或需权限）" else "runtime unavailable"), rt.label)
                }
            }
            output = r.second.take(20000)
            execInfo = "exit=${r.first} runtime=${r.third}"
            busy = false
        }
    }

    PageScroll {
        // ── 运行时 ──
        GlassGroup(
            title = if (zh) "运行时" else "Runtimes",
            footer = if (zh) "内置 Python 零依赖无 root；Termux 运行时需 root/Shizuku。点选切换执行目标" else "Built-in Python needs no root; Termux runtimes need privileges. Tap to switch target",
        ) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                runtimes.forEach { rt ->
                    FilterChip(
                        selected = runtime == rt.key,
                        enabled = rt.available,
                        onClick = { runtime = rt.key },
                        label = {
                            Text(
                                rt.label + (if (rt.available) "" else " (✗)"),
                                fontSize = 11.sp,
                            )
                        },
                    )
                }
            }
            GroupDivider()
            runtimes.firstOrNull { it.key == runtime }?.let { rt ->
                Text(
                    (if (zh) "当前: " else "Now: ") + rt.label +
                        (if (rt.key == "builtin-python") (if (zh) " ${builtinPyVersion}" else " $builtinPyVersion") else "") +
                        "　" + rt.desc + (if (!rt.available && privileged) "（未安装）" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }

        // ── 执行 ──
        GlassGroup(
            title = if (zh) "执行" else "Run",
            footer = if (zh) "输入代码或命令后执行；脚本写临时文件运行，规避引号与命令长度问题" else "Script written to temp file to avoid quoting issues",
        ) {
            // 模板快捷
            Text(
                (if (zh) "模板: " else "Templates: "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach { (code, label) ->
                    FilterChip(
                        selected = false,
                        onClick = { script = code },
                        label = { Text(label, fontSize = 10.sp) },
                    )
                }
            }
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                placeholder = { Text(if (zh) "输入 Python 代码或 shell 命令…" else "Python code or shell command…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                minLines = 2,
                maxLines = 10,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(if (zh) "执行" else "Run", { run() }, Modifier.weight(1f))
                SecondaryActionButton(if (zh) "清空" else "Clear", { output = ""; execInfo = "" }, Modifier.weight(0.5f))
            }
            if (output.isNotEmpty()) {
                GroupDivider()
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(),
                )
                Text(
                    execInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }
        }

        // ── MCP 对应工具 ──
        GlassGroup(title = if (zh) "MCP 工具" else "MCP Tool", footer = "taffy_terminal_exec") {
            Text(
                if (zh) "AI/脚本可用 taffy_terminal_exec：action=detect 探测；action=run 传 script（按 shebang 自动选运行时）或 command。" else "AI/scripts can call taffy_terminal_exec: detect probes; run takes script (auto runtime by shebang) or command.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}
