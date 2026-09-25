package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.termview.TerminalScreen
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.WorkspacePolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 终端执行：持久会话终端（对应 taffy_terminal_exec）。
 *
 * - 特权通道启动 `sh -i`（root/Shizuku），无 root 用内置 Python `-i`，状态跨命令保持。
 * - 黑底等宽终端风；输出可选中复制；可开关“跟随底部”，上翻查看历史时不再被拽回。
 * - 命令历史 ↑↓；快捷命令 chips；软键盘回车发送。
 */
@Composable
internal fun SettingsTerminalPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val zh = t.zh
    val marker = "__TAFFY_END__"

    var input by remember { mutableStateOf("") }
    var sessionProc by remember { mutableStateOf<Process?>(null) }
    var sessionOutput by remember { mutableStateOf("") }
    var sessionChannel by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var histIdx by remember { mutableStateOf(-1) }
    var follow by remember { mutableStateOf(true) }
    val outputScroll = rememberScrollState()

    val builtinPyPath = remember { PythonRuntime.pythonPath(context) }
    val privileged = remember { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }
    val sessionActive = sessionProc != null

    fun writeToSession(proc: Process, cmd: String) {
        try {
            val os = proc.outputStream
            os.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            os.flush()
            val markerCmd = if (sessionChannel.startsWith("python")) "print('$marker')\n" else "echo $marker\n"
            os.write(markerCmd.toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            sessionOutput = sessionOutput + "\n[写入失败: ${e.message}]\n"
            sessionProc = null
            sessionChannel = ""
        }
    }

    fun stopSession() {
        val p = sessionProc
        sessionProc = null
        runCatching { p?.destroy() }
        if (!sessionChannel.startsWith("python")) {
            sessionOutput = sessionOutput + "\n[会话已结束]\n"
        }
        sessionChannel = ""
    }

    fun ensureTaffyCli(): String? {
        val dir = PythonRuntime.ensureExtracted(context) ?: return null
        val cli = File(dir, "taffy_cli.py")
        if (!cli.isFile) {
            runCatching {
                context.assets.open("terminal/taffy_cli.py").use { i -> cli.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        return if (cli.isFile) cli.absolutePath else null
    }

    fun taffyEnv(): Pair<String, String> {
        val settings = com.soreverse.mcp.core.SettingsStore(context)
        return "http://127.0.0.1:${settings.port}/mcp" to settings.accessToken
    }

    fun startSession() {
        stopSession()
        sessionOutput = ""
        val proc = when {
            privileged -> PermissionManager.startPrivilegedStream("/system/bin/sh", listOf("-i"))
            builtinPyPath != null -> runCatching {
                ProcessBuilder(builtinPyPath, "-i").redirectErrorStream(true).apply {
                    val (url, token) = taffyEnv()
                    environment()["TAFFY_URL"] = url
                    environment()["TAFFY_TOKEN"] = token
                }.start()
            }.getOrNull()
            else -> null
        }
        if (proc == null) {
            sessionOutput = if (zh) "[无法启动会话：无特权且内置 Python 不可用]" else "[cannot start session]"
            return
        }
        sessionProc = proc
        sessionChannel = if (privileged) "sh -i (特权)" else "python3 -i (内置)"
        sessionOutput = if (zh) "── Taffy 终端会话已启动 ──\n通道: $sessionChannel\n输入命令开始（↑↓ 历史）\n" else "── Taffy terminal session started ──\nchannel: $sessionChannel\n"
        val wsPath = runCatching { WorkspacePolicy.workDirPath(context) }.getOrNull()
        if (!wsPath.isNullOrBlank()) {
            val cd = if (sessionChannel.startsWith("python")) "import os; os.chdir(r'$wsPath'); os.getcwd()" else "cd '$wsPath' && pwd"
            writeToSession(proc, cd)
            sessionOutput = sessionOutput + (if (zh) "\n# 工作区: $wsPath（已自动 cd）\n" else "\n# workspace: $wsPath (auto-cd)\n")
        }
        val cli = ensureTaffyCli()
        val (tUrl, tToken) = taffyEnv()
        val tokenB64 = java.util.Base64.getEncoder().encodeToString(tToken.toByteArray(Charsets.UTF_8))
        if (cli != null) {
            if (sessionChannel.startsWith("python")) {
                val pyInj = "import subprocess as _s; _C=r'$cli'; " +
                    "taffy=lambda *a,**kw: _s.call([_C]+list(a)+['%s=%s'%(k,v) for k,v in kw.items()])"
                writeToSession(proc, pyInj)
                sessionOutput = sessionOutput + (if (zh) "\n# taffy 已就绪: taffy('taffy_so_open', path='/sdcard/…')\n" else "\n# taffy ready\n")
            } else {
                val pythonBin = File(File(cli).parentFile, "bin/python3").absolutePath
                val shInj = "export TAFFY_URL='$tUrl'; export TAFFY_TOKEN=\$(printf '%s' '$tokenB64' | base64 -d 2>/dev/null); " +
                    "taffy() { '$pythonBin' '$cli' \"\$@\"; }; " +
                    "python3() { '$pythonBin' \"\$@\"; }; py() { '$pythonBin' \"\$@\"; }; " +
                    "alias py=python3"
                writeToSession(proc, shInj)
                sessionOutput = sessionOutput + (if (zh) "\n# taffy 已就绪: taffy taffy_so_open path=/sdcard/…\n# python3 已就绪: python3 script.py\n" else "\n# taffy / python3 ready\n")
            }
        } else {
            sessionOutput = sessionOutput + (if (zh) "\n# 警告: taffy CLI 初始化失败\n" else "\n# warn: taffy CLI unavailable\n")
        }
        val sb = StringBuilder(sessionOutput)
        Thread {
            val buf = ByteArray(4096)
            try {
                while (sessionProc === proc) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    var text = String(buf, 0, n, Charsets.UTF_8)
                    text = text.replace("$marker\n", "").replace(marker, "")
                    synchronized(sb) {
                        sb.append(text)
                        if (sb.length > 12000) sb.delete(0, sb.length - 12000)
                    }
                    sessionOutput = sb.toString()
                }
            } catch (_: Exception) {
            } finally {
                if (sessionProc === proc) {
                    sessionOutput = sb.toString() + (if (zh) "\n[会话已断开]\n" else "\n[disconnected]\n")
                    sessionProc = null
                    sessionChannel = ""
                }
            }
        }.apply { isDaemon = true; name = "taffy-terminal-reader" }.start()
    }

    fun send(cmdRaw: String) {
        val proc = sessionProc ?: run {
            sessionOutput = if (zh) "[会话未启动，先点「启动会话」]" else "[start session first]"
            return
        }
        val c = cmdRaw.trim()
        if (c.isEmpty()) return
        history = (listOf(c) + history.filter { it != c }).take(60)
        histIdx = -1
        input = ""
        writeToSession(proc, c)
        sessionOutput = sessionOutput + "\n$ " + c + "\n"
    }

    LaunchedEffect(sessionOutput) {
        if (follow) runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
    }

    val wsPath = remember { runCatching { WorkspacePolicy.workDirPath(context) }.getOrNull() }
    val quick = remember(sessionChannel, wsPath) {
        if (sessionChannel.startsWith("python")) {
            mutableListOf(
                "dir()" to "dir()",
                "import os; os.getcwd()" to "pwd",
                "import platform; platform.platform()" to (if (zh) "平台" else "platform"),
                "exit()" to (if (zh) "退出" else "exit"),
            )
        } else {
            mutableListOf(
                "ls -la" to "ls",
                "pwd" to "pwd",
                "id" to "id",
                "uname -a" to "uname",
                "df -h /" to (if (zh) "磁盘" else "disk"),
                "ps -A | head -20" to (if (zh) "进程" else "ps"),
                "clear" to "clear",
            )
        }.also {
            if (!wsPath.isNullOrBlank()) {
                it.add(0, ("cd '$wsPath' && ls -la" to (if (zh) "工作区" else "Workspace")))
            }
        }
    }

    fun terminalKey(action: String) {
        when (action) {
            "clear" -> sessionOutput = ""
            "interrupt" -> {
                stopSession(); startSession()
            }
            "up" -> if (history.isNotEmpty()) {
                histIdx = (histIdx + 1).coerceAtMost(history.size - 1)
                input = history[histIdx]
            }
            "down" -> if (histIdx >= 0) {
                histIdx--
                input = if (histIdx >= 0) history[histIdx] else ""
            }
        }
    }

    val termKeys = listOf(
        "clear" to (if (zh) "清屏" else "Clear"),
        "interrupt" to (if (zh) "重置会话" else "Reset"),
        "up" to "↑",
        "down" to "↓",
    )

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 会话控制条 ──
        GlassGroup {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (sessionActive) AppPalette.green else AppPalette.mono, RoundedCornerShape(AppShape.xs)),
                )
                Text(
                    if (sessionActive) (if (zh) "会话中 · " else "Session · ") + sessionChannel else if (zh) "会话未启动" else "No session",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sessionActive) AppPalette.green else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FilterChip(
                    selected = follow,
                    onClick = { follow = !follow },
                    label = { Text(if (zh) "跟随" else "Follow") },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sessionActive) {
                    SecondaryActionButton(if (zh) "重启" else "Restart", { startSession() }, Modifier.weight(1f))
                    SecondaryActionButton(if (zh) "结束" else "Stop", { stopSession() }, Modifier.weight(1f))
                } else {
                    PrimaryActionButton(if (zh) "启动会话" else "Start Session", { startSession() }, Modifier.fillMaxWidth())
                }
            }
        }

        // ── 终端显示区（渲染统一走 :terminal-view 模块的 TerminalScreen；本页自管输入行与软键）──
        TerminalScreen(
            text = sessionOutput,
            modifier = Modifier.fillMaxWidth().weight(1f),
            title = if (sessionActive) "terminal · $sessionChannel" else "terminal",
            placeholder = if (zh) "启动会话后在此显示终端输出…" else "Terminal output appears here after starting…",
            inputEnabled = false,
            autoScroll = follow,
            onCopy = { if (sessionOutput.isNotBlank()) copyToClipboard(context, sessionOutput) },
            onClear = { sessionOutput = "" },
        )

        // ── 终端软键 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            termKeys.forEach { (k, label) ->
                FilterChip(
                    selected = false,
                    onClick = { terminalKey(k) },
                    label = { Text(label, fontFamily = FontFamily.Monospace) },
                )
            }
        }

        // ── 快捷命令 ──
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { (c, label) ->
                FilterChip(selected = false, onClick = { send(c) }, label = { Text(label) }, enabled = sessionActive)
            }
        }

        // ── 输入行 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { ev ->
                    when {
                        ev.key == Key.DirectionUp && ev.type == KeyEventType.KeyDown -> {
                            if (history.isNotEmpty()) {
                                histIdx = (histIdx + 1).coerceAtMost(history.size - 1)
                                input = history[histIdx]
                            }
                            true
                        }
                        ev.key == Key.DirectionDown && ev.type == KeyEventType.KeyDown -> {
                            if (histIdx > 0) {
                                histIdx--
                                input = history[histIdx]
                            } else if (histIdx == 0) {
                                histIdx = -1
                                input = ""
                            }
                            true
                        }
                        ev.key == Key.Enter && ev.type == KeyEventType.KeyDown -> {
                            send(input)
                            true
                        }
                        else -> false
                    }
                },
                placeholder = { Text(if (zh) "输入命令，回车发送（↑↓ 历史）" else "Type command, Enter to send (↑↓ history)", color = TerminalColors.dim, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TerminalColors.fg),
                shape = RoundedCornerShape(AppShape.md),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            IconButton(onClick = { send(input) }, enabled = sessionActive) {
                Icon(Icons.AutoMirrored.Filled.Send, if (zh) "发送" else "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
