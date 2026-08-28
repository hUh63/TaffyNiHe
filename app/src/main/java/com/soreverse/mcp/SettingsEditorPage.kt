package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.LspClient
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.WorkspacePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shell 内置命令表（补全用，覆盖常用 unix/Android/塔菲语境命令）。 */
private val SHELL_CMDS: List<Pair<String, String>> = listOf(
    "ls" to "列目录", "cd" to "切换目录", "pwd" to "当前目录", "cat" to "查看文件", "head" to "前几行",
    "tail" to "后几行", "grep" to "文本搜索", "find" to "查找文件", "echo" to "输出", "export" to "环境变量",
    "env" to "环境变量", "ps" to "进程列表", "top" to "进程监控", "kill" to "结束进程", "chmod" to "修改权限",
    "chown" to "修改属主", "cp" to "复制", "mv" to "移动/改名", "rm" to "删除", "mkdir" to "创建目录",
    "rmdir" to "删除目录", "touch" to "创建文件", "tar" to "打包解包", "unzip" to "解压 zip", "gzip" to "压缩",
    "curl" to "HTTP 客户端", "wget" to "下载", "ping" to "连通测试", "netstat" to "网络状态", "ifconfig" to "网卡配置",
    "ip" to "网络配置", "ss" to "套接字统计", "df" to "磁盘空间", "du" to "目录大小", "free" to "内存",
    "uname" to "系统信息", "id" to "用户身份", "whoami" to "当前用户", "sed" to "流编辑", "awk" to "文本处理",
    "sort" to "排序", "uniq" to "去重", "wc" to "计数", "xargs" to "参数传递", "tee" to "分流输出",
    "sleep" to "等待", "date" to "时间", "ln" to "链接", "stat" to "文件信息", "md5sum" to "MD5",
    "sha256sum" to "SHA256", "base64" to "Base64 编解码", "strings" to "提取字符串", "xxd" to "十六进制",
    "od" to "八进制转储", "file" to "文件类型", "dd" to "块拷贝", "sync" to "刷盘", "mount" to "挂载",
    "umount" to "卸载", "sh" to "shell", "python" to "Python", "python3" to "Python 3", "pip" to "PyPI 包管理",
    "git" to "版本管理", "taffy" to "塔菲 MCP CLI", "proot" to "用户态 root", "apk" to "Alpine 包管理",
    "apt" to "Debian 包管理", "am" to "Android 组件", "pm" to "Android 包管理", "dumpsys" to "系统服务",
    "logcat" to "系统日志", "settings" to "系统设置", "getprop" to "系统属性", "setprop" to "设置属性",
    "svc" to "服务控制", "input" to "注入输入", "screencap" to "截屏", "screenrecord" to "录屏", "wm" to "窗口管理",
)

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
    var tf by remember { mutableStateOf(TextFieldValue("")) }             // 带光标状态（jedi 补全用）
    var completions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var completionVia by remember { mutableStateOf("") }
    var showCompletions by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf(false) }
    var lastInputAt by remember { mutableStateOf(0L) }                    // 防抖自动补全
    var preview by remember { mutableStateOf(false) }   // 高亮预览开关
    var output by remember { mutableStateOf("") }
    var sessionProc by remember { mutableStateOf<Process?>(null) }
    var sessionActive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var replInput by remember { mutableStateOf("") }
    var currentFile by remember { mutableStateOf<String?>(null) }
    var currentFilePath by remember { mutableStateOf<String?>(null) }   // 工作区文件完整路径（写回用）
    var recentFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var wsFiles by remember { mutableStateOf<List<String>>(emptyList()) } // 工作区当前目录条目（📁/ 前缀=子目录）
    var wsDir by remember { mutableStateOf("") }                          // 工作区浏览当前相对目录（""=根）
    val consoleScroll = rememberScrollState()

    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text != null) { code = text; tf = TextFieldValue(text); currentFile = null }
        }
    }

    fun appendOut(s: String) { output = (output + s).takeLast(20000) }

    fun refreshRecent() {
        recentFiles = runCatching {
            val dir = File(context.filesDir, "editor_files")
            dir.listFiles { f -> f.isFile && f.name.endsWith(".py") || f.name.endsWith(".sh") || f.name.endsWith(".json") || f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }?.map { it.name }?.take(6).orEmpty()
        }.getOrDefault(emptyList())
        // 工作区文件浏览（借鉴 Xed-Editor 项目管理理念：目录导航 + 文件点开）
        wsFiles = runCatching {
            val ws = WorkspacePolicy.workDirPath(context) ?: return@runCatching emptyList()
            val dir = File(ws, wsDir)
            if (!dir.isDirectory) emptyList() else {
                val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    ?.map { "📁/${it.name}" }.orEmpty()
                val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
                    ?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty()
                (dirs.sorted() + files).take(16)
            }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refreshRecent() }

    /** 快照：保存/运行前，若文件已存在且内容变化，备份到 editor_files/backup/（借鉴 Git 版本管理简化版）。 */
    fun snapshotIfChanged(path: String) {
        runCatching {
            val f = File(path)
            if (!f.isFile) return
            val bakDir = File(context.filesDir, "editor_files/backup").apply { mkdirs() }
            val bak = File(bakDir, "${f.name}.${System.currentTimeMillis()}.bak")
            bak.writeBytes(f.readBytes())
            // 只保留最近 5 份同名备份
            bakDir.listFiles { it -> it.name.startsWith(f.name) }
                ?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        }
    }

    /** 加载工作区文件（任意路径，写回原文件）。 */
    fun loadWsFile(path: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() }
            if (text != null) {
                snapshotIfChanged(path)
                code = text
                tf = TextFieldValue(text)
                currentFilePath = path
                currentFile = File(path).name
                mode = when (File(path).extension.lowercase()) {
                    "py" -> CodeHighlighter.Lang.PYTHON
                    "sh" -> CodeHighlighter.Lang.SHELL
                    "json" -> CodeHighlighter.Lang.JSON
                    else -> CodeHighlighter.Lang.TEXT
                }
            } else {
                appendOut("\n[无法读取: $path]\n")
            }
        }
    }

    // ── jedi 代码智能（完整版：补全 / 悬停文档 / 跳转定义）──
    fun setCode(text: String) {
        code = text
        tf = TextFieldValue(text, selection = TextRange(text.length))
    }

    /** 光标 → jedi 的 (line, col)，均 1-based。 */
    fun cursorLineCol(): Pair<Int, Int> {
        val s = tf.selection.start.coerceIn(0, tf.text.length)
        val before = tf.text.substring(0, s)
        val line = before.count { it == '\n' } + 1
        val col = s - (before.lastIndexOf('\n') + 1) + 1
        return line to col
    }

    fun requestCompletion(kind: String) {
        when (mode) {
            CodeHighlighter.Lang.PYTHON -> requestPythonSmart(kind)
            CodeHighlighter.Lang.SHELL -> if (kind == "complete") showCompletionsFor(shellCompletions(), if (zh) "补全 (shell 命令表)" else "Completion (shell commands)")
            CodeHighlighter.Lang.JSON -> if (kind == "complete") showCompletionsFor(jsonKeyCompletions(), if (zh) "补全 (文档已有键)" else "Completion (keys in doc)")
            else -> {}
        }
    }

    private fun showCompletionsFor(items: List<JSONObject>, via: String) {
        completions = items
        completionVia = via
        showCompletions = items.isNotEmpty()
    }

    /** Python 代码智能：长驻 jedi-language-server（LSP）优先，失败回退 jedi 直连。 */
    fun requestPythonSmart(kind: String) {
        val script = PythonRuntime.supportScript(context, "completion.py")
        if (kind == "complete" && tf.selection.start > 0) {
            val prev = tf.text[tf.selection.start - 1]
            if (prev == '\n' || prev == '\t' || prev == ' ' || prev == '(') { showCompletions = false; return }
        }
        val (ln, col) = cursorLineCol()
        completing = true
        scope.launch {
            val items = mutableListOf<JSONObject>()
            var via = "jedi"
            // 1) LSP 优先
            val lspOk = withContext(Dispatchers.IO) {
                runCatching {
                    if (!LspClient.ensureStarted(context)) return@runCatching false
                    LspClient.didChange(code)
                    when (kind) {
                        "complete" -> {
                            via = "LSP"
                            LspClient.completion(ln - 1, col - 1).forEach {
                                items.add(JSONObject().put("name", it.label).put("type", it.kind)
                                    .put("doc", if (it.detail.isNotBlank()) "${it.detail} · ${it.doc}" else it.doc))
                            }
                            true
                        }
                        "hover" -> {
                            via = "LSP"
                            LspClient.hover(ln - 1, col - 1)?.let {
                                items.add(JSONObject().put("name", "hover").put("type", "LSP").put("doc", it))
                            }
                            true
                        }
                        else -> false // defs 走 jedi 直连
                    }
                }.getOrDefault(false)
            }
            // 2) jedi 直连兜底 / defs
            if (!lspOk || kind == "defs") {
                val script2 = script
                if (script2 == null) {
                    completing = false
                    appendOut("\n[代码智能不可用：内置 Python 未就绪]\n")
                    return@launch
                }
                via = if (kind == "defs") "jedi" else "jedi (LSP 回退)"
                val payload = JSONObject().put("code", code).put("line", ln).put("col", col).put("kind", kind)
                val r = withContext(Dispatchers.IO) {
                    PythonRuntime.run(context, payload.toString(), args = listOf(script2), timeoutSec = 30)
                }
                runCatching {
                    val out = r.output.trim()
                    val start = out.indexOf('[')
                    val end = out.lastIndexOf(']')
                    if (start >= 0 && end > start) {
                        val arr = JSONArray(out.substring(start, end + 1))
                        for (i in 0 until arr.length()) items.add(arr.getJSONObject(i))
                    }
                }
            }
            completing = false
            when (kind) {
                "complete" -> {
                    completions = items.filter { it.optString("name") != "__error__" }
                    completionVia = "补全 ($via)"
                    showCompletions = completions.isNotEmpty()
                    val err = items.firstOrNull { it.optString("name") == "__error__" }
                    if (err != null) appendOut("\n[jedi] ${err.optString("doc")}\n")
                }
                else -> {
                    appendOut("\n── 代码智能 ($via) ──\n")
                    if (items.isEmpty()) appendOut("(无结果)\n")
                    items.forEach { c -> appendOut("${c.optString("name")} [${c.optString("type")}] ${c.optString("doc").lineSequence().firstOrNull().orEmpty()}\n") }
                }
            }
        }
    }

    /** Shell 内置命令表补全（无需外部进程）。 */
    fun shellCompletions(): List<JSONObject> {
        val before = tf.text.substring(0, tf.selection.start.coerceIn(0, tf.text.length))
        val word = Regex("[A-Za-z0-9_.\\-]+$").find(before)?.value.orEmpty()
        return SHELL_CMDS.filter { it.first.startsWith(word) && it.first != word }
            .take(30)
            .map { JSONObject().put("name", it.first).put("type", "cmd").put("doc", it.second) }
    }

    /** JSON 键补全（收集文档中已有 key）。 */
    fun jsonKeyCompletions(): List<JSONObject> {
        val s = tf.selection.start.coerceIn(0, tf.text.length)
        val before = tf.text.substring(0, s)
        val word = Regex("([A-Za-z0-9_\\-.]+)\"?$").find(before)?.groupValues?.get(1).orEmpty()
        val keys = Regex("\"([A-Za-z0-9_\\-.]+)\"\\s*:").findAll(tf.text).map { it.groupValues[1] }.distinct().toList()
        return keys.filter { it.startsWith(word) && it != word }.take(30)
            .map { JSONObject().put("name", it).put("type", "key").put("doc", if (zh) "文档中已有的键" else "existing key") }
    }

    fun insertCompletion(name: String) {
        val t = tf.text
        val s = tf.selection.start.coerceIn(0, t.length)
        val wordStart = Regex("[A-Za-z0-9_]*$").find(t.substring(0, s))?.range?.first ?: s
        val newText = t.substring(0, wordStart) + name + t.substring(s)
        tf = TextFieldValue(newText, selection = TextRange(wordStart + name.length))
        code = newText
        showCompletions = false
    }

    // 输入后 800ms 防抖自动补全（仅 Python 模式）
    LaunchedEffect(lastInputAt) {
        if (lastInputAt == 0L) return@LaunchedEffect
        delay(800)
        if (mode == CodeHighlighter.Lang.PYTHON && !preview) requestCompletion("complete")
    }

    // 扩展页/外部跳转打开指定文件
    LaunchedEffect(Unit) {
        EditorBridge.pendingPath?.let { p ->
            EditorBridge.pendingPath = null
            loadWsFile(p)
        }
    }

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
            val result = withContext(Dispatchers.IO) {
                // 工作区文件：快照 + 写回原路径（借鉴 Git 版本管理：改前留备份）
                val target = currentFilePath
                if (target != null && File(target).isFile) {
                    snapshotIfChanged(target)
                    val ok = runCatching { File(target).writeText(code); true }.getOrDefault(false)
                    if (ok) "已保存到工作区: $target" else "写回失败（无权限？用 root/Termux 或改为另存）"
                } else {
                    val dir = File(context.filesDir, "editor_files").apply { mkdirs() }
                    val ext = when (mode) { CodeHighlighter.Lang.PYTHON -> "py"; CodeHighlighter.Lang.SHELL -> "sh"; CodeHighlighter.Lang.JSON -> "json"; else -> "txt" }
                    val name = currentFile ?: "file_${System.currentTimeMillis()}.$ext"
                    val f = File(dir, name)
                    f.writeText(code)
                    "已保存: ${f.absolutePath}"
                }
            }
            appendOut("\n[$result]\n")
            refreshRecent()
        }
    }

    fun loadFile(name: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { File(File(context.filesDir, "editor_files"), name).readText() }.getOrNull()
            }
            if (text != null) {
                setCode(text)
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
                    value = tf,
                    onValueChange = {
                        tf = it; code = it.text
                        if (mode == CodeHighlighter.Lang.PYTHON) lastInputAt = System.currentTimeMillis()
                    },
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

        // ── jedi 补全面板（Python 模式）──
        if (showCompletions && mode == CodeHighlighter.Lang.PYTHON) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 190.dp)
                    .background(Color(0xFF0E141C), RoundedCornerShape(12.dp)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (completionVia.ifBlank { if (zh) "补全 (jedi)" else "Completion (jedi)" }) + if (completing) " · 分析中…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPalette.teal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✕",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showCompletions = false }.padding(4.dp),
                    )
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    completions.forEach { c ->
                        val name = c.optString("name")
                        val type = c.optString("type")
                        val doc = c.optString("doc").lineSequence().firstOrNull().orEmpty()
                        Column(
                            Modifier.fillMaxWidth().clickable { insertCompletion(name) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = fg, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(type, style = MaterialTheme.typography.labelSmall, color = AppPalette.blue, fontSize = 9.sp)
                            }
                            if (doc.isNotBlank()) {
                                Text(doc, style = MaterialTheme.typography.labelSmall, color = Color(0xFF607D8B), fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
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
            if (mode == CodeHighlighter.Lang.PYTHON) {
                // jedi 代码智能：补全 / 悬停文档 / 跳转定义
                FilterChip(
                    selected = false,
                    onClick = { requestCompletion("complete") },
                    label = { Text(if (zh) "⚡补全" else "⚡Complete", fontSize = 10.sp) },
                    enabled = !completing,
                )
                FilterChip(
                    selected = false,
                    onClick = { requestCompletion("hover") },
                    label = { Text(if (zh) "?文档" else "?Doc", fontSize = 10.sp) },
                    enabled = !completing,
                )
                FilterChip(
                    selected = false,
                    onClick = { requestCompletion("defs") },
                    label = { Text(if (zh) "→定义" else "→Def", fontSize = 10.sp) },
                    enabled = !completing,
                )
            }
            IconButton(onClick = { saveFile() }, enabled = code.isNotBlank()) { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { loadLauncher.launch(arrayOf("text/plain", "text/x-python", "application/json", "*/*")) }) { Icon(Icons.Default.FileOpen, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { setCode(""); currentFile = null; currentFilePath = null; showCompletions = false }) { Icon(Icons.Default.CreateNewFolder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = {
                // 从最近备份回滚（快照管理）
                val name = currentFile
                if (name == null) { appendOut("\n[先打开或保存一个文件]\n"); return@IconButton }
                val bakDir = File(context.filesDir, "editor_files/backup")
                val latest = bakDir.listFiles { it -> it.name.startsWith("$name.") && it.name.endsWith(".bak") }
                    ?.maxByOrNull { it.lastModified() }
                if (latest == null) { appendOut("\n[没有可回滚的备份]\n") } else {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { runCatching { latest.readText() }.getOrNull() }
                        if (text != null) { setCode(text); appendOut("\n[已回滚到备份: ${latest.name}]\n") }
                    }
                }
            }, enabled = currentFile != null) {
                Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        // ── 工作区文件（借鉴 Xed-Editor 项目管理：目录导航→编辑→写回）──
        if (wsFiles.isNotEmpty() || wsDir.isNotEmpty()) {
            Text(
                (if (zh) "工作区（点目录进入，点文件编辑，保存写回并自动留备份）" else "Workspace (tap dir to enter, tap file to edit; save writes back + backup)"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 面包屑：当前位置 + 返回上级
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (wsDir.isNotEmpty()) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            wsDir = wsDir.substringBeforeLast('/', "").also { refreshRecent() }
                        },
                        label = { Text("← ..", fontSize = 10.sp) },
                    )
                }
                Text(
                    "/" + wsDir,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                wsFiles.forEach { entry ->
                    val isDir = entry.startsWith("📁/")
                    val name = if (isDir) entry.removePrefix("📁/") else entry
                    FilterChip(
                        selected = !isDir && currentFile == name,
                        onClick = {
                            val ws = WorkspacePolicy.workDirPath(context)
                            if (ws != null) {
                                if (isDir) {
                                    wsDir = if (wsDir.isEmpty()) name else "$wsDir/$name"
                                    refreshRecent()
                                } else {
                                    loadWsFile(File(ws, if (wsDir.isEmpty()) name else "$wsDir/$name").absolutePath)
                                }
                            }
                        },
                        label = { Text(if (isDir) "\uD83D\uDCC1 $name" else name, fontSize = 10.sp, maxLines = 1) },
                    )
                }
                if (wsFiles.isEmpty() && wsDir.isNotEmpty()) {
                    Text(if (zh) "（空目录）" else "(empty dir)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
