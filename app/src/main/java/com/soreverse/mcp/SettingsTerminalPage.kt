package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.WorkspacePolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 终端执行：真正的终端体验（对应 taffy_terminal_exec）。
 *
 * - 持久会话终端：特权通道启动 `sh -i`（root/Shizuku），无 root 用内置 Python `-i`，
 *   cd/环境变量等状态跨命令保持——不再是"每次新进程"的伪终端。
 * - 黑底等宽终端风 UI：输出自动滚动、过滤分隔标记、输入行固定底部。
 * - 命令历史：↑↓ 键切换。
 * - 快捷命令 chips（uname/pwd/ls/apk 等）。
 */
@Composable
internal fun SettingsTerminalPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    // 终端配色
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    val promptColor = Color(0xFF4DD0E1)
    val marker = "__TAFFY_END__"

    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sessionProc by remember { mutableStateOf<Process?>(null) }
    var sessionOutput by remember { mutableStateOf("") }
    var sessionChannel by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var histIdx by remember { mutableStateOf(-1) }
    val outputScroll = rememberScrollState()

    val builtinPyPath = remember { PythonRuntime.pythonPath(context) }
    val privileged = remember { RootShell.isRootAvailable() || PermissionManager.isShizukuGranted() }
    val sessionActive = sessionProc != null

    /** 直接写命令到会话（不追加历史/提示符）——用于启动时的自动 cd 等内部命令。
     *  必须定义在 startSession/send 之前（Kotlin 局部函数先声明后使用）。 */
    fun writeToSession(proc: Process, cmd: String) {
        try {
            val os = proc.outputStream
            os.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            os.flush()
            // 分隔标记（python 交互用 print）
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
            // 附加换行提示
            sessionOutput = sessionOutput.ifEmpty { "" } + "\n[会话已结束]\n"
        }
        sessionChannel = ""
    }

    /** 确保 taffy_cli.py（MCP CLI）已复制到内置 Python 目录，返回其路径。 */
    fun ensureTaffyCli(): String? {
        val dir = PythonRuntime.ensureExtracted(context) ?: return null
        val cli = File(dir, "taffy_cli.py")
        if (!cli.isFile) {
            runCatching {
                context.assets.open("terminal/taffy_cli.py").use { input ->
                    cli.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }
        return if (cli.isFile) cli.absolutePath else null
    }

    /** 会话注入 taffy 命令所需的 MCP 连接信息（URL + token）。 */
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
                    // 内置 python 会话：注入 MCP 连接信息（taffy CLI 使用）
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
        sessionOutput = if (zh) "── Taffy 终端会话已启动 ──\n通道: ${sessionChannel}\n输入命令开始（↑↓ 历史）\n" else "── Taffy terminal session started ──\nchannel: ${sessionChannel}\ntype commands (↑↓ history)\n"
        // 自动切换到工作区（workDirPath 为真实路径；无 root 时内置 python 受限于 app 权限）
        val wsPath = runCatching { WorkspacePolicy.workDirPath(context) }.getOrNull()
        if (!wsPath.isNullOrBlank()) {
            val cd = if (sessionChannel.startsWith("python")) "import os; os.chdir(r'$wsPath'); os.getcwd()" else "cd '$wsPath' && pwd"
            writeToSession(proc, cd)
            sessionOutput = sessionOutput + (if (zh) "\n# 工作区: $wsPath（已自动 cd）\n" else "\n# workspace: $wsPath (auto-cd)\n")
        }
        // ── 注入 taffy 命令：终端里直接调用塔菲 MCP 工具 ──
        val cli = ensureTaffyCli()
        val (tUrl, tToken) = taffyEnv()
        val tokenB64 = java.util.Base64.getEncoder().encodeToString(tToken.toByteArray(Charsets.UTF_8))
        if (cli != null) {
            if (sessionChannel.startsWith("python")) {
                // python 会话：taffy(tool, **kwargs) 函数（单行定义，python -i 可执行）
                val pyInj = "import subprocess as _s; _C=r'$cli'; " +
                    "taffy=lambda *a,**kw: _s.call([_C]+list(a)+['%s=%s'%(k,v) for k,v in kw.items()])"
                writeToSession(proc, pyInj)
                sessionOutput = sessionOutput + (if (zh) "\n# taffy 已就绪: taffy('taffy_so_open', path='/sdcard/…') 或 taffy('tools')\n" else "\n# taffy ready: taffy('taffy_so_open', path='/sdcard/…') or taffy('tools')\n")
            } else {
                // sh 会话：taffy() 函数（调内置 python + cli；token 用 base64 避免引号破坏）
                // 同时注入 python3 命令：终端里可直接运行内置 Python（任意代码/脚本/REPL）
                val pythonBin = File(File(cli).parentFile, "bin/python3").absolutePath
                val shInj = "export TAFFY_URL='$tUrl'; export TAFFY_TOKEN=\$(printf '%s' '$tokenB64' | base64 -d 2>/dev/null); " +
                    "taffy() { '$pythonBin' '$cli' \"\$@\"; }; " +
                    "python3() { '$pythonBin' \"\$@\"; }; py() { '$pythonBin' \"\$@\"; }; " +
                    "alias py=python3"
                writeToSession(proc, shInj)
                sessionOutput = sessionOutput + (if (zh) "\n# taffy 已就绪: taffy taffy_so_open path=/sdcard/… 或 taffy tools\n# python3 已就绪: python3 script.py / python3（进入 REPL）\n" else "\n# taffy ready: taffy taffy_so_open path=/sdcard/… or taffy tools\n# python3 ready: python3 script.py / python3 (REPL)\n")
            }
        } else {
            sessionOutput = sessionOutput + (if (zh) "\n# 警告: taffy CLI 初始化失败（内置 Python 未就绪）\n" else "\n# warn: taffy CLI unavailable\n")
        }
        // 读线程：持续读取输出，过滤分隔标记，限制显示长度
        val sb = StringBuilder(sessionOutput)
        Thread {
            val buf = ByteArray(4096)
            try {
                while (sessionProc === proc) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    var text = String(buf, 0, n, Charsets.UTF_8)
                    // 过滤分隔标记行
                    text = text.replace("$marker\n", "").replace(marker, "")
                    synchronized(sb) {
                        sb.append(text)
                        // 限制保留最近 12000 字符（滚动窗口）
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
        sessionOutput = sessionOutput + (if (zh) "\n$ " else "\n$ ") + c + "\n"
    }

    // 输出自动滚动到底部
    LaunchedEffect(sessionOutput) {
        runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
    }

    // 快捷命令（按通道自适应 + 工作区联动）
    val wsPath = remember { runCatching { WorkspacePolicy.workDirPath(context) }.getOrNull() }
    val quick = remember(sessionChannel, wsPath) {
        val base = if (sessionChannel.startsWith("python")) {
            mutableListOf(
                "print('hi')" to "Py Hello",
                "import os; os.getcwd()" to "CWD",
                "import platform; platform.platform()" to "平台",
            )
        } else {
            mutableListOf(
                "uname -a" to "uname", "pwd" to "pwd", "ls -la" to "ls", "df -h /" to "磁盘",
                "ps -A | head" to "进程", "apk update" to "apk update", "id" to "id", "echo \$HOME" to "HOME",
            )
        }
        // 工作区入口：查看（ls）与编辑提示（vi/cat 视环境支持）
        if (!wsPath.isNullOrBlank()) {
            if (sessionChannel.startsWith("python")) {
                base.add(0, "import os; os.chdir(r'$wsPath'); os.listdir('.')[:20]" to (if (zh) "工作区" else "Workspace"))
            } else {
                base.add(0, "cd '$wsPath' && ls -la" to (if (zh) "工作区" else "Workspace"))
                base.add(1, "cd '$wsPath' && ls -la | head" to (if (zh) "工作区浏览" else "WS list"))
            }
        }
        // taffy CLI 入口（终端控制塔菲 MCP 工具）；python 会话是函数调用语法
        val isPy = sessionChannel.startsWith("python")
        base.add(0, (if (isPy) "taffy('tools')" else "taffy tools") to "taffy 工具")
        base.add(1, (if (isPy) "taffy('taffy_linux', action='shell', distro='alpine', command='uname -a')" else "taffy --help") to "taffy 示例")
        base
    }

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 会话控制条 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(8.dp).height(8.dp).background(
                    if (sessionActive) AppPalette.green else AppPalette.mono,
                    RoundedCornerShape(4.dp),
                ),
            )
            Text(
                if (sessionActive) (if (zh) "会话中 · " else "Session · ") + sessionChannel else if (zh) "会话未启动" else "No session",
                style = MaterialTheme.typography.labelMedium,
                color = if (sessionActive) AppPalette.green else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sessionActive) {
                SecondaryActionButton(if (zh) "重启" else "Restart", { startSession() }, Modifier.width(84.dp).height(38.dp))
                SecondaryActionButton(if (zh) "结束" else "Stop", { stopSession() }, Modifier.width(84.dp).height(38.dp))
            } else {
                PrimaryActionButton(if (zh) "启动会话" else "Start Session", { startSession() }, Modifier.width(120.dp).height(38.dp))
            }
        }

        // ── 终端显示区 ──
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(bg, RoundedCornerShape(14.dp))
                .verticalScroll(outputScroll),
        ) {
            Text(
                sessionOutput.ifEmpty { if (zh) "启动会话后在此显示终端输出…" else "Terminal output appears here after starting…" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                color = fg,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }

        // ── 快捷命令 ──
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { (c, label) ->
                FilterChip(selected = false, onClick = { send(c) }, label = { Text(label, fontSize = 10.sp) }, enabled = sessionActive)
            }
        }

        // ── 输入行（终端风格）──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { ev ->
                    when {
                        ev.key == Key.DirectionUp && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                            if (history.isNotEmpty()) {
                                histIdx = (histIdx + 1).coerceAtMost(history.size - 1)
                                input = history[histIdx]
                            }
                            true
                        }
                        ev.key == Key.DirectionDown && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                            if (histIdx > 0) { histIdx--; input = history[histIdx] }
                            else if (histIdx == 0) { histIdx = -1; input = "" }
                            true
                        }
                        ev.key == Key.Enter && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                            send(input)
                            true
                        }
                        else -> false
                    }
                },
                placeholder = { Text(if (zh) "输入命令，Enter 发送（↑↓ 历史）" else "Type command, Enter to send (↑↓ history)", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = fg),
                shape = RoundedCornerShape(12.dp),
            )
            IconButton(onClick = { send(input) }, enabled = sessionActive) {
                Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
