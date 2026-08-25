package com.soreverse.mcp

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
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 终端执行：内置 Python（零依赖，无 root 可用）与 Termux 运行时的检测与执行。
 * 对应 MCP 工具 taffy_terminal_exec（detect/run）。
 */
@Composable
internal fun SettingsTerminalPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    var refreshTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var execInfo by remember { mutableStateOf("") }

    // 运行时检测
    val builtinPyPath = remember(refreshTick) { PythonRuntime.pythonPath(context) }
    val builtinPyVersion = remember(refreshTick) {
        if (builtinPyPath != null) {
            PythonRuntime.run(context, "import sys; print(sys.version.split()[0])", timeoutSec = 15).output.trim()
        } else ""
    }
    val privileged = remember(refreshTick) { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }
    val termuxRuntimes = remember(refreshTick) {
        if (!privileged) emptyList() else {
            val prefix = "/data/data/com.termux/files/usr"
            listOf("python3", "node", "busybox", "bash", "sh").filter { bin ->
                RootShell.exec("test -x \"$prefix/bin/$bin\" && echo Y || echo N", timeoutSec = 8).stdout.trim() == "Y"
            }
        }
    }

    fun run() {
        val s = script.trim()
        if (s.isEmpty() || busy) return
        busy = true
        output = if (zh) "执行中…" else "Running…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                // 内置 Python 优先（零依赖，无需 root）
                val wantPy = builtinPyPath != null &&
                    (s.lineSequence().firstOrNull()?.contains("python") == true ||
                        s.startsWith("import ") || s.startsWith("print(") || s.startsWith("#!/usr/bin/env python"))
                if (wantPy) {
                    val res = PythonRuntime.run(context, s, timeoutSec = 60)
                    Triple(res.code, res.output, "内置 Python")
                } else if (privileged) {
                    // Termux sh
                    val r = RootShell.exec("env PREFIX=/data/data/com.termux/files/usr PATH=/data/data/com.termux/files/usr/bin:/system/bin HOME=/data/data/com.termux/files/home /data/data/com.termux/files/usr/bin/sh -c \"$s\" 2>&1", timeoutSec = 60)
                    Triple(r.code, r.stdout, "Termux sh")
                } else {
                    Triple(-1, if (zh) "无可用运行时（无 root 且不是 Python 代码）" else "no runtime available", "none")
                }
            }
            output = r.second.take(20000)
            execInfo = "exit=${r.first} runtime=${r.third}"
            busy = false
        }
    }

    PageScroll {
        // ── 运行时状态 ──
        GlassGroup(
            title = if (zh) "运行时" else "Runtimes",
            footer = if (zh) "内置 Python 零依赖、无 root 可用；Termux 运行时需 root/Shizuku" else "Built-in Python needs no root; Termux runtimes require root/Shizuku",
        ) {
            Text(
                if (zh) "内置 Python 3（零依赖）：${if (builtinPyPath != null) "就绪 ${builtinPyVersion}" else "未就绪"}" else "Built-in Python 3: ${builtinPyPath?.let { "ready $builtinPyVersion" } ?: "not ready"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
                fontWeight = FontWeight.SemiBold,
            )
            GroupDivider()
            Text(
                (if (zh) "Termux 运行时（需权限）：" else "Termux runtimes (privileged): ") +
                    (if (termuxRuntimes.isEmpty()) (if (zh) "未检测到（安装 Termux 后 pkg install python nodejs）" else "none detected (pkg install python nodejs)") else termuxRuntimes.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        // ── 执行 ──
        GlassGroup(
            title = if (zh) "执行" else "Run",
            footer = if (zh) "Python 代码自动走内置 Python（无 root 可用）；其他命令在 root/Shizuku 下走 Termux sh" else "Python goes to built-in runtime (no-root OK); other commands go to Termux sh with privileges",
        ) {
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                placeholder = { Text(if (zh) "输入 Python 代码或 shell 命令，如:\nprint('hi') 或 uname -a" else "Python code or shell command, e.g.\nprint('hi') or uname -a") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                minLines = 2,
                maxLines = 8,
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
