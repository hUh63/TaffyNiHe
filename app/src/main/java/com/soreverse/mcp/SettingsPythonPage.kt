package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.PythonRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 → Python：编辑器 + 控制台 一体（对应 taffy_terminal_exec 的 python 能力 + 内置 Python）。
 *
 * - 编辑器（上半）：深色等宽代码输入，支持多行缩进，手机端大按钮操作
 * - 控制台（下半）：黑底终端输出 + REPL 输入行，输出自动滚动
 * - 运行 = 编辑器代码通过 exec 注入持久 REPL 会话（变量状态保留，可多段调试）
 * - 保存/加载：脚本存 filesDir/python_scripts/，加载任意 .py 文本
 * - 零依赖：内置 Python 3，无 root 可用
 */
@Composable
internal fun SettingsPythonPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    val marker = "__TAFFY_END__"

    var code by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var sessionProc by remember { mutableStateOf<Process?>(null) }
    var sessionActive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var replInput by remember { mutableStateOf("") }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    val consoleScroll = rememberScrollState()

    // 文件加载（.py 文本）
    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text != null) code = text else output = output + "\n[加载失败]\n"
        }
    }

    fun appendOut(s: String) {
        output = (output + s).takeLast(20000)
    }

    fun stopSession() {
        val p = sessionProc
        sessionProc = null
        sessionActive = false
        runCatching { p?.destroy() }
        appendOut("\n[会话已结束]\n")
    }

    fun startSession() {
        stopSession()
        output = if (zh) "── Python REPL 会话 ──\n输入表达式或代码，Enter 执行；「运行」按钮执行编辑器代码（状态保留）\n" else "── Python REPL ──\ntype expressions or code; Run button executes the editor (state kept)\n"
        val python = PythonRuntime.pythonPath(context) ?: run {
            appendOut(if (zh) "\n[内置 Python 不可用]" else "\n[built-in python unavailable]")
            return
        }
        val proc = runCatching {
            ProcessBuilder(python, "-i").redirectErrorStream(true).apply {
                environment()["PYTHONUNBUFFERED"] = "1"
                environment()["HOME"] = File(python).parentFile.parentFile.absolutePath
            }.start()
        }.getOrNull()
        if (proc == null) {
            appendOut(if (zh) "\n[启动失败]" else "\n[start failed]")
            return
        }
        sessionProc = proc
        sessionActive = true
        val sb = StringBuilder(output)
        Thread {
            val buf = ByteArray(4096)
            try {
                while (sessionProc === proc) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    val text = String(buf, 0, n, Charsets.UTF_8).replace("$marker\n", "").replace(marker, "")
                    synchronized(sb) { sb.append(text); if (sb.length > 20000) sb.delete(0, sb.length - 20000) }
                    output = sb.toString()
                }
            } catch (_: Exception) {
            } finally {
                if (sessionProc === proc) { sessionActive = false; sessionProc = null }
            }
        }.apply { isDaemon = true; name = "python-repl-reader" }.start()
    }

    fun writeToSession(cmd: String) {
        val proc = sessionProc ?: return
        try {
            val os = proc.outputStream
            os.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            os.flush()
            os.write(("print('$marker')\n").toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (_: Exception) {
            appendOut("\n[写入失败，会话已断开]\n")
            sessionActive = false
            sessionProc = null
        }
    }

    fun sendRepl(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        replInput = ""
        appendOut(">>> $c\n")
        writeToSession(c)
    }

    /** 运行编辑器代码：写入临时文件，通过 exec 注入会话（状态保留）。 */
    fun runEditor() {
        if (code.isBlank()) return
        if (!sessionActive) startSession()
        val proc = sessionProc ?: return
        busy = true
        scope.launch {
            val scriptPath = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "python_scripts").apply { mkdirs() }
                val f = File(dir, "editor_run_${System.currentTimeMillis()}.py")
                f.writeText(code)
                f.absolutePath
            }
            appendOut("\n── 运行编辑器代码 ──\n")
            // exec 注入：状态保留，异常显示
            val escaped = scriptPath.replace("'", "\\'")
            writeToSession("exec(open(r'$escaped').read())")
            busy = false
        }
    }

    fun saveScript() {
        if (code.isBlank()) return
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "python_scripts").apply { mkdirs() }
                val f = File(dir, "script_${System.currentTimeMillis()}.py")
                f.writeText(code)
                f.absolutePath
            }
            lastSaved = name
            appendOut("\n[已保存: $name]\n")
        }
    }

    // 进入页面自动启动 REPL 会话
    LaunchedEffect(Unit) { startSession() }
    // 输出自动滚动
    LaunchedEffect(output) { runCatching { consoleScroll.scrollTo(consoleScroll.maxValue) } }

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 标题行：状态灯 + 会话控制 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(8.dp).height(8.dp).background(
                    if (sessionActive) AppPalette.green else AppPalette.mono,
                    RoundedCornerShape(4.dp),
                ),
            )
            Text(
                (if (zh) "内置 Python 3" else "Built-in Python 3") + (if (sessionActive) (if (zh) " · REPL 会话中" else " · REPL active") else ""),
                style = MaterialTheme.typography.labelMedium,
                color = if (sessionActive) AppPalette.green else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (sessionActive) {
                SecondaryActionButton(if (zh) "重启" else "Restart", { startSession() }, Modifier.width(72.dp).height(36.dp))
                SecondaryActionButton(if (zh) "结束" else "Stop", { stopSession() }, Modifier.width(72.dp).height(36.dp))
            }
        }

        // ── 编辑器（上半）──
        Column(Modifier.fillMaxWidth().weight(1.25f)) {
            Text(
                (if (zh) "编辑器" else "Editor") + (if (busy) " · 运行中…" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                placeholder = { Text(if (zh) "# 在此编写 Python 代码\nprint('hello')\n\n# 变量会在多次运行间保留" else "# write Python here\nprint('hello')\n\n# variables persist across runs", color = Color(0xFF607D8B), fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = fg),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = bg,
                    unfocusedContainerColor = bg,
                    focusedBorderColor = AppPalette.teal.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color(0xFF1E2A36),
                ),
                shape = RoundedCornerShape(14.dp),
            )
            // 操作按钮行
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryActionButton(if (zh) "运行" else "Run", { runEditor() }, Modifier.weight(1f).height(40.dp), leading = Icons.Default.PlayArrow)
                IconButton(onClick = { saveScript() }, enabled = code.isNotBlank()) {
                    Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { loadLauncher.launch(arrayOf("text/plain", "text/x-python", "*/*")) }) {
                    Icon(Icons.Default.FileOpen, null, tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { code = "" }, enabled = code.isNotBlank()) {
                    Icon(Icons.Default.Clear, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── 控制台（下半）──
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                (if (zh) "控制台" else "Console") + (lastSaved?.let { if (zh) " · 已保存" else " · saved" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                    .background(bg, RoundedCornerShape(14.dp))
                    .verticalScroll(consoleScroll),
            ) {
                Text(
                    output.ifEmpty { if (zh) "（控制台输出显示在这里）" else "(console output here)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    color = fg,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
            }
            // REPL 输入行
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replInput,
                    onValueChange = { replInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (zh) ">>> 输入表达式（Enter 执行）" else ">>> expression (Enter to run)", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = fg),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = bg,
                        unfocusedContainerColor = bg,
                        focusedBorderColor = AppPalette.teal.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color(0xFF1E2A36),
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                IconButton(onClick = { sendRepl(replInput) }, enabled = sessionActive) {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
