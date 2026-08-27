package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
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
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 设置 → 编辑器：多模式编辑器 + 控制台（借鉴 Xed-Editor 的多语言编辑理念）。
 *
 * 模式: Python（REPL 会话 + exec 注入，状态保留）/ Shell（sh 执行）/ JSON（校验+格式化）/ 文本
 * 高亮: 轻量语法高亮（CodeHighlighter），编辑/预览切换
 * 文件: 新建/保存/加载，filesDir/editor_files/ 下按模式扩展名存储
 * 控制台: Python/Shell 输出，JSON 校验结果
 */
@Composable
internal fun SettingsEditorPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    val marker = "__TAFFY_END__"

    var mode by remember { mutableStateOf(CodeHighlighter.Lang.PYTHON) }
    var code by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }   // 高亮预览开关
    var output by remember { mutableStateOf("") }
    var sessionProc by remember { mutableStateOf<Process?>(null) }
    var sessionActive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var replInput by remember { mutableStateOf("") }
    var currentFile by remember { mutableStateOf<String?>(null) }
    var recentFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val consoleScroll = rememberScrollState()

    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text != null) { code = text; currentFile = null }
        }
    }

    fun appendOut(s: String) { output = (output + s).takeLast(20000) }

    fun refreshRecent() {
        recentFiles = runCatching {
            val dir = File(context.filesDir, "editor_files")
            dir.listFiles { f -> f.isFile && f.name.endsWith(".py") || f.name.endsWith(".sh") || f.name.endsWith(".json") || f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }?.map { it.name }?.take(6).orEmpty()
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refreshRecent() }

    // ── Python REPL 会话 ──
    fun stopSession() {
        val p = sessionProc
        sessionProc = null
        sessionActive = false
        runCatching { p?.destroy() }
    }
    fun startSession() {
        stopSession()
        val python = PythonRuntime.pythonPath(context) ?: return
        val proc = runCatching {
            ProcessBuilder(python, "-i").redirectErrorStream(true).apply {
                environment()["PYTHONUNBUFFERED"] = "1"
                environment()["HOME"] = File(python).parentFile.parentFile.absolutePath
            }.start()
        }.getOrNull() ?: return
        sessionProc = proc
        sessionActive = true
        Thread {
            val buf = ByteArray(4096)
            try {
                while (sessionProc === proc) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    val text = String(buf, 0, n, Charsets.UTF_8).replace("$marker\n", "").replace(marker, "")
                    output = output + text
                    if (output.length > 20000) output = output.takeLast(20000)
                }
            } catch (_: Exception) {
            } finally {
                if (sessionProc === proc) { sessionActive = false; sessionProc = null }
            }
        }.apply { isDaemon = true }.start()
    }
    fun writeToSession(cmd: String) {
        val proc = sessionProc ?: return
        try {
            proc.outputStream.write((cmd + "\n").toByteArray(Charsets.UTF_8)); proc.outputStream.flush()
            proc.outputStream.write(("print('$marker')\n").toByteArray(Charsets.UTF_8)); proc.outputStream.flush()
        } catch (_: Exception) { sessionActive = false; sessionProc = null }
    }
    fun sendRepl(cmd: String) {
        if (cmd.isBlank() || !sessionActive) return
        replInput = ""
        appendOut(">>> $cmd\n")
        writeToSession(cmd)
    }

    // ── 运行（按模式）──
    fun runCode() {
        if (code.isBlank()) return
        when (mode) {
            CodeHighlighter.Lang.PYTHON -> {
                if (!sessionActive) startSession()
                val proc = sessionProc ?: run { appendOut("\n[内置 Python 不可用]\n"); return }
                busy = true
                scope.launch {
                    val path = withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "editor_files").apply { mkdirs() }
                        val f = File(dir, "_run_${System.currentTimeMillis()}.py")
                        f.writeText(code); f.absolutePath
                    }
                    appendOut("\n── 运行 Python ──\n")
                    writeToSession("exec(open(r'${path.replace("'", "\\'")}').read())")
                    busy = false
                }
            }
            CodeHighlighter.Lang.SHELL -> {
                busy = true
                appendOut("\n── 运行 Shell ──\n")
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        if (PermissionManager.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                            RootShell.exec(code, timeoutSec = 30)
                        } else {
                            runCatching {
                                val p = ProcessBuilder("/system/bin/sh", "-c", code).redirectErrorStream(true).start()
                                val out = p.inputStream.readBytes().decodeToString()
                                p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                                RootShell.Result(p.exitValue(), out, "")
                            }.getOrElse { RootShell.Result(-1, "", it.message ?: "执行失败") }
                        }
                    }
                    appendOut(r.stdout.ifEmpty { "(无输出, exit=${r.code})" } + "\n")
                    busy = false
                }
            }
            CodeHighlighter.Lang.JSON -> {
                // 校验 + 格式化
                val res = runCatching {
                    val obj = JSONObject(code)
                    obj.toString(2)
                }
                res.onSuccess { appendOut("\n✅ JSON 有效，已格式化：\n$it\n") }
                    .onFailure { appendOut("\n❌ JSON 无效：${it.message}\n") }
            }
            CodeHighlighter.Lang.TEXT -> {
                appendOut("\n[文本模式] ${code.lines().size} 行 / ${code.length} 字符\n")
            }
        }
    }

    fun saveFile() {
        if (code.isBlank()) return
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "editor_files").apply { mkdirs() }
                val ext = when (mode) { CodeHighlighter.Lang.PYTHON -> "py"; CodeHighlighter.Lang.SHELL -> "sh"; CodeHighlighter.Lang.JSON -> "json"; else -> "txt" }
                val name = currentFile ?: "file_${System.currentTimeMillis()}.$ext"
                val f = File(dir, name)
                f.writeText(code)
                f.absolutePath
            }
            currentFile = File(path).name
            appendOut("\n[已保存: $path]\n")
            refreshRecent()
        }
    }

    fun loadFile(name: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { File(File(context.filesDir, "editor_files"), name).readText() }.getOrNull()
            }
            if (text != null) {
                code = text
                currentFile = name
                // 按扩展名推断模式
                mode = when (name.substringAfterLast('.', "")) {
                    "py" -> CodeHighlighter.Lang.PYTHON
                    "sh" -> CodeHighlighter.Lang.SHELL
                    "json" -> CodeHighlighter.Lang.JSON
                    else -> CodeHighlighter.Lang.TEXT
                }
            }
        }
    }

    LaunchedEffect(output) { runCatching { consoleScroll.scrollTo(consoleScroll.maxValue) } }

    val highlighted = remember(code, mode, preview) { CodeHighlighter.highlight(code, mode) }

    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 模式选择 ──
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                CodeHighlighter.Lang.PYTHON to "Python",
                CodeHighlighter.Lang.SHELL to "Shell",
                CodeHighlighter.Lang.JSON to "JSON",
                CodeHighlighter.Lang.TEXT to if (zh) "文本" else "Text",
            ).forEach { (m, label) ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label, fontSize = 11.sp) })
            }
        }

        // ── 编辑器（高亮预览切换）──
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            if (preview) {
                // 高亮只读预览
                SelectionContainer {
                    Text(
                        highlighted,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                        color = fg,
                        modifier = Modifier.fillMaxSize().background(bg, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { Text(
                        when (mode) {
                            CodeHighlighter.Lang.PYTHON -> if (zh) "# Python 代码（变量跨运行保留）" else "# Python code (state kept)"
                            CodeHighlighter.Lang.SHELL -> "# Shell 脚本"
                            CodeHighlighter.Lang.JSON -> "{ \"key\": \"value\" }"
                            else -> if (zh) "纯文本" else "plain text"
                        }, color = Color(0xFF607D8B), fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = fg),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = bg, unfocusedContainerColor = bg,
                        focusedBorderColor = AppPalette.teal.copy(alpha = 0.5f), unfocusedBorderColor = Color(0xFF1E2A36),
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }

        // ── 操作栏 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryActionButton(
                when (mode) { CodeHighlighter.Lang.JSON -> if (zh) "校验" else "Validate"; CodeHighlighter.Lang.TEXT -> if (zh) "统计" else "Stats"; else -> if (zh) "运行" else "Run" },
                { runCode() }, Modifier.weight(1f).height(40.dp), leading = Icons.Default.PlayArrow,
            )
            IconButton(onClick = { preview = !preview }, enabled = mode != CodeHighlighter.Lang.TEXT) {
                Icon(Icons.Default.Visibility, null, tint = if (preview) AppPalette.teal else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { saveFile() }, enabled = code.isNotBlank()) { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { loadLauncher.launch(arrayOf("text/plain", "text/x-python", "application/json", "*/*")) }) { Icon(Icons.Default.FileOpen, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { code = ""; currentFile = null }) { Icon(Icons.Default.CreateNewFolder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { output = "" }, enabled = output.isNotBlank()) { Icon(Icons.Default.Clear, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // ── 最近文件 ──
        if (recentFiles.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recentFiles.forEach { name ->
                    FilterChip(
                        selected = currentFile == name,
                        onClick = { loadFile(name) },
                        label = { Text(name, fontSize = 10.sp) },
                    )
                }
            }
        }

        // ── 控制台 ──
        Column(Modifier.fillMaxWidth().height(200.dp)) {
            Text(
                (if (zh) "控制台" else "Console") + if (sessionActive) " · Python REPL" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                    .background(bg, RoundedCornerShape(14.dp)).verticalScroll(consoleScroll),
            ) {
                Text(
                    output.ifEmpty { if (zh) "（输出显示在这里）" else "(output here)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    color = fg,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
            }
            // REPL 输入行（仅 Python 模式）
            if (mode == CodeHighlighter.Lang.PYTHON) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replInput,
                        onValueChange = { replInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (zh) ">>> 表达式（Enter 前先点 ▶）" else ">>> expression", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = fg),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = bg, unfocusedContainerColor = bg, focusedBorderColor = AppPalette.teal.copy(alpha = 0.5f), unfocusedBorderColor = Color(0xFF1E2A36)),
                        shape = RoundedCornerShape(12.dp),
                    )
                    IconButton(onClick = { sendRepl(replInput) }, enabled = sessionActive) {
                        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
