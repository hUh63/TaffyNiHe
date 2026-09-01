package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.soreverse.mcp.core.EditorAiHelper
import com.soreverse.mcp.core.LspClient
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.SettingsStore
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

/** 编辑器多 tab 的标签快照（借鉴 Xed-Editor 多文件编辑）。 */
private data class EditorTab(
    val name: String,
    val path: String?,      // 工作区/最近文件完整路径（草稿为 null）
    val code: String,       // 快照内容
    val mode: CodeHighlighter.Lang,
    val untitled: Boolean = false,
)

/** Python 本地即时补全词表（关键字 + 内置 + 常用模块成员，零延迟先显示）。 */
private val PYTHON_WORDS = listOf(
    "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
    "break", "continue", "pass", "try", "except", "finally", "raise", "with", "lambda", "yield",
    "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "None", "True", "False",
    "print", "len", "range", "enumerate", "zip", "map", "filter", "sorted", "reversed", "sum",
    "min", "max", "abs", "round", "int", "float", "str", "bytes", "bool", "list", "dict", "set",
    "tuple", "open", "input", "isinstance", "getattr", "setattr", "hasattr", "type", "repr",
    "format", "join", "split", "strip", "replace", "startswith", "endswith", "encode", "decode",
    "append", "extend", "insert", "pop", "remove", "keys", "values", "items", "get", "update",
    "os", "sys", "json", "re", "time", "struct", "hashlib", "base64", "socket", "subprocess",
    "threading", "pathlib", "ctypes", "traceback", "argparse", "logging",
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
    // ── 语法包（编辑器语言插件）：进入页面初始化，安装内置示范包 + 加载扩展目录 ──
    var packVersion by remember { mutableStateOf(0) }
    var showPackManager by remember { mutableStateOf(false) }
    remember(packVersion) {
        runCatching { com.soreverse.mcp.core.EditorSyntaxPacks.init(context) }
        CodeHighlighter.activePack = com.soreverse.mcp.core.EditorSyntaxPacks.packs.firstOrNull()
        true
    }
    var code by remember { mutableStateOf("") }
    var tf by remember { mutableStateOf(TextFieldValue("")) }             // 带光标状态（jedi 补全用）
    var completions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var completionVia by remember { mutableStateOf("") }
    var showCompletions by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf(false) }
    var lastInputAt by remember { mutableStateOf(0L) }                    // 防抖自动补全
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
                val detected = CodeHighlighter.Lang.fromExt(File(path).extension)
                mode = detected
                if (detected == CodeHighlighter.Lang.EXT) {
                    CodeHighlighter.activePack = com.soreverse.mcp.core.EditorSyntaxPacks.forExt(File(path).extension)
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

    // ── 多 tab（借鉴 Xed-Editor 多文件编辑：快照切换 / dirty 标记 / 关闭）──
    var tabs by remember {
        mutableStateOf(listOf(EditorTab(if (zh) "草稿" else "Draft", null, "", CodeHighlighter.Lang.PYTHON, true)))
    }
    var activeTab by remember { mutableIntStateOf(0) }

    // 自动草稿：进入页面时恢复上次未保存的全部标签；编辑中 2s 防抖落盘（防切页/杀进程丢稿）
    val autosaveFile = remember { File(context.filesDir, "editor_files/autosave.json") }
    LaunchedEffect(Unit) {
        val saved = runCatching { JSONArray(autosaveFile.readText()) }.getOrNull() ?: return@LaunchedEffect
        if (saved.length() == 0) return@LaunchedEffect
        val list = mutableListOf<EditorTab>()
        for (i in 0 until saved.length()) {
            val o = saved.optJSONObject(i) ?: continue
            val m = runCatching { CodeHighlighter.Lang.valueOf(o.optString("mode", "PYTHON")) }.getOrDefault(CodeHighlighter.Lang.PYTHON)
            list.add(EditorTab(o.optString("name", if (zh) "草稿" else "Draft"), o.optString("path").ifBlank { null }, o.optString("code"), m, o.optBoolean("untitled", true)))
        }
        if (list.isEmpty()) return@LaunchedEffect
        tabs = list
        activeTab = 0
        val first = list[0]
        code = first.code
        tf = TextFieldValue(first.code, selection = TextRange(first.code.length))
        mode = first.mode
        currentFile = if (first.untitled) null else first.name
        currentFilePath = first.path
        appendOut(if (zh) "\n[已恢复 ${list.size} 个未关闭标签的自动草稿]\n" else "\n[Restored ${list.size} autosaved tab(s)]\n")
    }
    LaunchedEffect(code, tabs.size) {
        delay(2000)
        if (activeTab < tabs.size) {
            tabs = tabs.toMutableList().also { it[activeTab] = it[activeTab].copy(code = code, mode = mode) }
        }
        runCatching {
            val arr = JSONArray()
            tabs.forEach { t ->
                arr.put(JSONObject().put("name", t.name).put("path", t.path ?: "").put("code", t.code)
                    .put("mode", t.mode.name).put("untitled", t.untitled))
            }
            autosaveFile.writeText(arr.toString())
        }
    }

    fun snapshotCurrent() {
        if (activeTab >= tabs.size) return
        tabs = tabs.toMutableList().also { it[activeTab] = it[activeTab].copy(code = code, mode = mode) }
    }

    fun switchTab(i: Int) {
        if (i == activeTab || i !in tabs.indices) return
        snapshotCurrent()
        activeTab = i
        val t = tabs[i]
        setCode(t.code)
        mode = t.mode
        currentFile = if (t.untitled) null else t.name
        currentFilePath = t.path
        showCompletions = false
    }

    fun closeTab(i: Int) {
        // 关闭前自动备份有内容的 tab（不打断操作流，防丢稿）
        val closing = tabs[i]
        val closingCode = if (i == activeTab) code else closing.code
        if (closingCode.isNotBlank()) {
            runCatching {
                val bakDir = File(context.filesDir, "editor_files/backup").apply { mkdirs() }
                File(bakDir, "${closing.name}.${System.currentTimeMillis()}.bak").writeText(closingCode)
                // 每个文件只留最近 5 份
                bakDir.listFiles { f -> f.name.startsWith(closing.name) }
                    ?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            }
        }
        val nt = tabs.toMutableList().also { it.removeAt(i) }
        val finalTabs = if (nt.isEmpty()) listOf(EditorTab(if (zh) "草稿" else "Draft", null, "", CodeHighlighter.Lang.PYTHON, true)) else nt
        val na = (if (i < activeTab) activeTab - 1 else if (i == activeTab) activeTab.coerceAtMost(finalTabs.size - 1) else activeTab)
            .coerceIn(0, finalTabs.size - 1)
        tabs = finalTabs
        activeTab = na
        val t = finalTabs[na]
        setCode(t.code)
        mode = t.mode
        currentFile = if (t.untitled) null else t.name
        currentFilePath = t.path
    }

    /** UI 层打开文件前调用：同路径已开 tab 直接切换，否则预登记新 tab（内容随后由 loadWsFile 填充）。 */
    fun ensureTab(name: String, path: String?) {
        if (path != null && tabs.any { it.path == path }) return
        snapshotCurrent()
        tabs = tabs + EditorTab(name, path, "", CodeHighlighter.Lang.PYTHON, path == null)
        activeTab = tabs.size - 1
    }

    /** 光标 → jedi 的 (line, col)，均 1-based。 */
    fun cursorLineCol(): Pair<Int, Int> {
        val s = tf.selection.start.coerceIn(0, tf.text.length)
        val before = tf.text.substring(0, s)
        val line = before.count { it == '\n' } + 1
        val col = s - (before.lastIndexOf('\n') + 1) + 1
        return line to col
    }

    fun showCompletionsFor(items: List<JSONObject>, via: String) {
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
        // 本地即时补全先行（零延迟）：词表 + 当前文档符号；LSP/jedi 结果到达后覆盖刷新
        if (kind == "complete" && tf.selection.start > 0) {
            val before = tf.text.substring(0, tf.selection.start)
            val word = Regex("[A-Za-z0-9_]+$").find(before)?.value.orEmpty()
            if (word.length >= 2) {
                val docSyms = Regex("\\b([A-Za-z_][A-Za-z0-9_]{2,})\\b").findAll(code).map { it.value }.toSet()
                val local = (PYTHON_WORDS + docSyms).filter { it.startsWith(word) && it != word }
                    .sorted().take(24)
                    .map { JSONObject().put("name", it).put("type", "local").put("doc", if (zh) "本地即时补全" else "instant local") }
                if (local.isNotEmpty()) {
                    completions = local
                    completionVia = if (zh) "补全 (本地词表)" else "Completion (local)"
                    showCompletions = true
                }
            }
        }
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

    /** LSP 诊断（publishDiagnostics 推送缓存）：拉取并显示到控制台。 */
    fun requestDiagnostics() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (!LspClient.ensureStarted(context)) return@runCatching false
                    LspClient.didChange(code)
                    // 诊断是推送制：didChange 后等一小段时间收推送
                    Thread.sleep(1200)
                    true
                }.getOrDefault(false)
            }
            val diags = if (ok) LspClient.takeDiagnostics() else emptyList()
            appendOut("\n── 诊断 (LSP) ──\n")
            if (diags.isEmpty()) appendOut("（无诊断问题）\n")
            diags.forEach { appendOut("[${it.severity}] 行 ${it.line}: ${it.message}\n") }
        }
    }

    /** 代码智能入口：按模式分流。 */
    fun requestCompletion(kind: String) {
        when (mode) {
            CodeHighlighter.Lang.PYTHON -> if (kind == "diag") requestDiagnostics() else requestPythonSmart(kind)
            CodeHighlighter.Lang.SHELL -> if (kind == "complete") showCompletionsFor(shellCompletions(), if (zh) "补全 (shell 命令表)" else "Completion (shell commands)")
            CodeHighlighter.Lang.JSON -> if (kind == "complete") showCompletionsFor(jsonKeyCompletions(), if (zh) "补全 (文档已有键)" else "Completion (keys in doc)")
            else -> {}
        }
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

    // 输入后 500ms 防抖自动补全（全模式：Python→LSP/jedi，Shell→命令表，JSON→已有键）
    LaunchedEffect(lastInputAt) {
        if (lastInputAt == 0L) return@LaunchedEffect
        delay(500)
        requestCompletion("complete")
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

    /** 控制台 shell 直发（非 Python 模式）：Root/Shizuku 走特权，否则普通进程降级。 */
    fun shellExec(cmd: String) {
        if (cmd.isBlank()) return
        replInput = ""
        appendOut("\n$ $cmd\n")
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (PermissionManager.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                    RootShell.exec(cmd, timeoutSec = 30)
                } else {
                    runCatching {
                        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
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
            else -> {
                // Smali（可交给 taffy_apk_rebuild 的 smali 汇编）/ C/C++ / Java / XML / Markdown：暂无本地运行器
                appendOut(
                    when (mode) {
                        CodeHighlighter.Lang.SMALI -> "\n[Smali] 语法检查请用 taffy_apk_rebuild(build) 的 smali 汇编；运行请在终端/工作区完成\n"
                        CodeHighlighter.Lang.C -> "\n[C/C++] 无本地编译器——可在终端（Linux 环境 rootfs 内 gcc/clang）或插件中编译\n"
                        CodeHighlighter.Lang.JAVA -> "\n[Java/Kt] 无本地编译器——可在终端（rootfs 内 javac/kotlinc）编译\n"
                        CodeHighlighter.Lang.XML -> "\n[XML] ${code.lines().size} 行（Manifest 修改请用 taffy_apk_manifest_edit）\n"
                        else -> "\n[Markdown] ${code.lines().size} 行 / ${code.length} 字符\n"
                    }
                )
            }
        }
    }

    // ── AI 助手（可选：复用 AI 深度分析的端点配置；未配置则按钮置灰）──
    val aiReady = remember { runCatching { EditorAiHelper.isReady(EditorAiHelper.config(SettingsStore(context))) }.getOrDefault(false) }
    var aiBusy by remember { mutableStateOf(false) }

    /** 🤖 编辑器 AI 助手：对当前代码提问（解释/找问题/补全建议），回答输出到控制台。 */
    fun requestAiAssist(question: String = "解释这段代码的功能、潜在问题，并给出改进建议（含代码）。") {
        if (code.isBlank()) { appendOut("\n[AI] 代码为空，先写点内容\n"); return }
        aiBusy = true
        appendOut("\n── 🤖 AI 助手 ──\n[发送中] $question\n")
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { EditorAiHelper.ask(EditorAiHelper.config(SettingsStore(context)), code, question, mode.name.lowercase()) }
            }
            aiBusy = false
            r.onSuccess { appendOut("$it\n") }
                .onFailure { appendOut("[AI 请求失败] ${it.message}\n（检查 AI 深度分析页的端点/Key/模型配置与网络）\n") }
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
                    val ext = mode.ext
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
                // 按扩展名推断模式（内置语言 / 语法包扩展语言）
                val detected = CodeHighlighter.Lang.fromExt(name.substringAfterLast('.', ""))
                mode = detected
                if (detected == CodeHighlighter.Lang.EXT) {
                    CodeHighlighter.activePack = com.soreverse.mcp.core.EditorSyntaxPacks.forExt(name.substringAfterLast('.', ""))
                }
            }
        }
    }

    LaunchedEffect(output) { runCatching { consoleScroll.scrollTo(consoleScroll.maxValue) } }

    val pageScroll = rememberScrollState()

    // 整页可滑动（编辑区/控制台/工作区文件小屏也能全部看到）+ 键盘弹出时补全面板不被遮挡
    Column(
        Modifier.fillMaxSize().verticalScroll(pageScroll).imePadding().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 模式选择（内置语言 + 语法包扩展语言：Python/Shell/JSON/Smali/C/Java/XML/Markdown/Rust/Go/Lua/SQL…）──
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodeHighlighter.Lang.entries.forEach { m ->
                val label = when (m) {
                    CodeHighlighter.Lang.PYTHON -> "Python"
                    CodeHighlighter.Lang.SHELL -> "Shell"
                    CodeHighlighter.Lang.JSON -> "JSON"
                    CodeHighlighter.Lang.SMALI -> "Smali"
                    CodeHighlighter.Lang.C -> "C/C++"
                    CodeHighlighter.Lang.JAVA -> "Java/Kt"
                    CodeHighlighter.Lang.XML -> "XML"
                    CodeHighlighter.Lang.MD -> "Markdown"
                    CodeHighlighter.Lang.EXT -> {
                        val pack = CodeHighlighter.activePack
                        if (pack != null) "✦ ${pack.name}" else if (zh) "扩展" else "Ext"
                    }
                    CodeHighlighter.Lang.TEXT -> if (zh) "文本" else "Text"
                }
                FilterChip(selected = mode == m, onClick = {
                    mode = m
                    if (m == CodeHighlighter.Lang.EXT) {
                        val ext = File(currentFilePath).extension
                        CodeHighlighter.activePack = com.soreverse.mcp.core.EditorSyntaxPacks.forExt(ext)
                            ?: com.soreverse.mcp.core.EditorSyntaxPacks.packs.firstOrNull()
                    }
                }, label = { Text(label, fontSize = 11.sp) })
            }
            FilterChip(
                selected = false,
                onClick = { showPackManager = true },
                label = { Text(if (zh) "📦 语法包" else "📦 Packs", fontSize = 11.sp) },
            )
        }

        // ── 编辑器（高亮预览切换）──
        // ── 多 tab 条（借鉴 Xed-Editor 多文件编辑）──
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, t ->
                val dirty = i == activeTab && t.code != code
                FilterChip(
                    selected = i == activeTab,
                    onClick = { switchTab(i) },
                    label = { Text((if (dirty) "• " else "") + (if (t.untitled) (if (zh) "草稿" else "Draft") else t.name), fontSize = 10.sp, maxLines = 1) },
                    trailingIcon = {
                        Text(
                            "×",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { closeTab(i) }.padding(start = 2.dp),
                        )
                    },
                )
            }
        }

        // ── 编辑区（实时高亮编辑：BasicTextField + VisualTransformation，输入即高亮）──
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 460.dp)
                    .background(bg, RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF1E2A36), RoundedCornerShape(14.dp))
                    .padding(4.dp),
            ) {
                BasicTextField(
                    value = tf,
                    onValueChange = {
                        tf = it; code = it.text
                        lastInputAt = System.currentTimeMillis()   // 全模式自动补全（按模式分流）
                    },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = fg),
                    cursorBrush = SolidColor(AppPalette.teal),
                    visualTransformation = VisualTransformation { text ->
                        androidx.compose.ui.text.input.TransformedText(
                            CodeHighlighter.highlight(text.text, mode),
                            androidx.compose.ui.text.input.OffsetMapping.Identity,
                        )
                    },
                    decorationBox = { inner ->
                        Box {
                            if (code.isEmpty()) {
                                Text(
                                    when (mode) {
                                        CodeHighlighter.Lang.PYTHON -> if (zh) "# Python 代码（变量跨运行保留）" else "# Python code (state kept)"
                                        CodeHighlighter.Lang.SHELL -> "# Shell 脚本"
                                        CodeHighlighter.Lang.JSON -> "{ \"key\": \"value\" }"
                                        else -> if (zh) "纯文本" else "plain text"
                                    },
                                    color = Color(0xFF607D8B), fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    modifier = Modifier.padding(6.dp),
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            // 补全面板：覆盖在编辑区底部的浮层（不推挤布局），条件放宽到全部模式
            if (showCompletions && (mode != CodeHighlighter.Lang.TEXT)) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 190.dp)
                        .background(Color(0xF20E141C), RoundedCornerShape(12.dp)),
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
        }

        // ── 操作栏 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryActionButton(
                when (mode) { CodeHighlighter.Lang.JSON -> if (zh) "校验" else "Validate"; CodeHighlighter.Lang.TEXT -> if (zh) "统计" else "Stats"; else -> if (zh) "运行" else "Run" },
                { runCode() }, Modifier.weight(1f).height(40.dp), leading = Icons.Default.PlayArrow,
            )
            IconButton(
                onClick = { requestAiAssist() },
                enabled = aiReady && !aiBusy,
            ) { Text(if (aiBusy) "…" else "🤖", fontSize = 15.sp) }
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
                FilterChip(
                    selected = false,
                    onClick = { requestCompletion("diag") },
                    label = { Text(if (zh) "⚠诊断" else "⚠Diag", fontSize = 10.sp) },
                    enabled = !completing,
                )
            }
            IconButton(onClick = { saveFile() }, enabled = code.isNotBlank()) { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { loadLauncher.launch(arrayOf("text/plain", "text/x-python", "application/json", "*/*")) }) { Icon(Icons.Default.FileOpen, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = {
                setCode(""); currentFile = null; currentFilePath = null; showCompletions = false
                tabs = tabs.toMutableList().also { it[activeTab] = EditorTab(if (zh) "草稿" else "Draft", null, "", mode, true) }
            }) { Icon(Icons.Default.CreateNewFolder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        onClick = { ensureTab(name, File(File(context.filesDir, "editor_files"), name).absolutePath); loadFile(name) },
                        label = { Text(name, fontSize = 10.sp) },
                    )
                }
            }
        }
        // ── 工作区文件树（经典文件树：目录展开/收起 + 文件点击编辑；整个面板可收起）──
        var wsPanelExpanded by remember { mutableStateOf(true) }
        var wsTreeExpanded by remember { mutableStateOf(setOf("")) }   // 已展开目录（相对路径，""=根）
        fun wsListDir(rel: String): List<Pair<String, Boolean>> = runCatching {
            val ws = WorkspacePolicy.workDirPath(context) ?: return@runCatching emptyList()
            val dir = File(ws, rel)
            if (!dir.isDirectory) emptyList() else {
                val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    ?.map { it.name to true }.orEmpty()
                val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
                    ?.sortedByDescending { it.lastModified() }?.map { it.name to false }.orEmpty()
                (dirs.sorted() + files).take(30)
            }
        }.getOrDefault(emptyList())

        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .clickable { wsPanelExpanded = !wsPanelExpanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (wsPanelExpanded) "▾" else "▸",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (zh) "工作区文件树（点目录展开，点文件编辑，保存写回并自动留备份）" else "Workspace tree (expand dirs, tap file to edit; save writes back + backup)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
            if (wsPanelExpanded) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        fun wsTreeEntry(rel: String, name: String, isDir: Boolean, depth: Int) {
                            val childRel = if (rel.isEmpty()) name else "$rel/$name"
                            val expanded = childRel in wsTreeExpanded
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            wsTreeExpanded = if (expanded) wsTreeExpanded - childRel else wsTreeExpanded + childRel
                                        } else {
                                            val ws = WorkspacePolicy.workDirPath(context)
                                            if (ws != null) {
                                                val target = File(ws, childRel).absolutePath
                                                ensureTab(name, target)
                                                loadWsFile(target)
                                            }
                                        }
                                    }
                                    .padding(start = (depth * 14 + 10).dp, top = 5.dp, bottom = 5.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (isDir) {
                                    Text(
                                        if (expanded) "▾" else "▸",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text("📁", fontSize = 12.sp)
                                } else {
                                    Text(" ", fontSize = 10.sp)
                                    Text("📄", fontSize = 12.sp)
                                }
                                Text(
                                    name,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isDir) MaterialTheme.colorScheme.primary else fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isDir && expanded) {
                                val children = wsListDir(childRel)
                                if (children.isEmpty()) {
                                    Text(
                                        if (zh) "（空目录）" else "(empty)",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = ((depth + 1) * 14 + 30).dp, top = 2.dp, bottom = 2.dp),
                                    )
                                } else {
                                    children.forEach { (n, d) -> wsTreeEntry(childRel, n, d, depth + 1) }
                                }
                            }
                        }
                        wsListDir("").forEach { (n, d) -> wsTreeEntry("", n, d, 0) }
                        if (wsListDir("").isEmpty()) {
                            Text(
                                if (zh) "（工作区为空）" else "(workspace empty)",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── 控制台（经典终端：黑底 + 提示符 + 输入行内嵌底部，全模式可用）──
        Column(
            Modifier.fillMaxWidth().height(260.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(8.dp),
        ) {
            // 标题行：控制台名 + 提示 + 清屏
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (if (zh) "console" else "console") + when {
                        sessionActive -> " · python"
                        mode == CodeHighlighter.Lang.PYTHON -> " · python"
                        else -> " · shell"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF7A8699),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (zh) "清屏" else "clear",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF7A8699),
                    modifier = Modifier.clickable { output = "" }.padding(horizontal = 4.dp),
                )
            }
            // 输出区
            Box(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(consoleScroll),
            ) {
                Text(
                    output.ifEmpty { if (zh) "（输出显示在这里——运行代码或输入命令）" else "(output appears here — run code or type a command)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    color = fg,
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                )
            }
            // 输入行（内嵌框底，经典终端样式：提示符 + 输入 + 发送）
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (mode == CodeHighlighter.Lang.PYTHON && sessionActive) ">>>" else "$",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    color = AppPalette.teal,
                )
                OutlinedTextField(
                    value = replInput,
                    onValueChange = { replInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (mode == CodeHighlighter.Lang.PYTHON && sessionActive) (if (zh) "Python 表达式（▶ 运行代码）" else "python expression") else (if (zh) "shell 命令（id / ls / pm list packages…）" else "shell command"),
                            color = Color(0xFF90A4AE),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = fg),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = bg, unfocusedContainerColor = bg, focusedBorderColor = AppPalette.teal.copy(alpha = 0.5f), unfocusedBorderColor = Color(0xFF1E2A36)),
                    shape = RoundedCornerShape(10.dp),
                )
                IconButton(
                    onClick = {
                        if (mode == CodeHighlighter.Lang.PYTHON && sessionActive) sendRepl(replInput) else shellExec(replInput)
                    },
                    enabled = replInput.isNotBlank() && !busy,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // ── 语法包管理（语言插件：导入/删除/恢复内置）──
        if (showPackManager) {
            var packJson by remember { mutableStateOf("") }
            var packMsg by remember { mutableStateOf("") }
            val packs = com.soreverse.mcp.core.EditorSyntaxPacks.packs
            AlertDialog(
                onDismissRequest = { showPackManager = false },
                title = { Text(if (zh) "语法包 · 语言插件" else "Syntax Packs", fontSize = 15.sp) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (zh)
                                "语法包用 JSON 描述一种语言（关键字/内置名/注释符/扩展名）。安装后编辑器即可高亮该语言，并按文件扩展名自动识别——无需改代码即可扩展编辑器语言。内置示范包：Rust / Go / Lua / SQL。"
                            else
                                "A syntax pack describes a language in JSON (keywords/builtins/comments/extensions). Installed packs get full highlighting and auto-detection by file extension. Built-ins: Rust / Go / Lua / SQL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        packs.forEach { p ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("✦ ${p.name}", Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = fg)
                                Text(p.extensions.joinToString(" "), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    com.soreverse.mcp.core.EditorSyntaxPacks.remove(context, p.id)
                                    if (CodeHighlighter.activePack?.id == p.id) CodeHighlighter.activePack = null
                                    packVersion++
                                }) { Text(if (zh) "删除" else "Del", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                            }
                        }
                        if (packs.isEmpty()) {
                            Text(if (zh) "（尚未安装语法包）" else "(no packs installed)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = packJson,
                            onValueChange = { packJson = it },
                            label = { Text(if (zh) "粘贴语法包 JSON 导入" else "Paste syntax pack JSON to import") },
                            minLines = 4,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = fg),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = bg, unfocusedContainerColor = bg),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (packMsg.isNotBlank()) Text(packMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val r = com.soreverse.mcp.core.EditorSyntaxPacks.save(context, packJson)
                            packMsg = r.fold(
                                { "${if (zh) "已安装" else "Installed"}: ${it.name} (${it.extensions.joinToString(", ")})" },
                                { "${if (zh) "导入失败" else "Failed"}: ${it.message}" },
                            )
                            packVersion++
                        },
                        enabled = packJson.isNotBlank(),
                    ) { Text(if (zh) "导入" else "Install") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            com.soreverse.mcp.core.EditorSyntaxPacks.restoreBuiltins(context)
                            packVersion++
                            packMsg = if (zh) "已恢复内置包" else "Built-ins restored"
                        }) { Text(if (zh) "恢复内置" else "Restore") }
                        TextButton(onClick = { showPackManager = false }) { Text(if (zh) "关闭" else "Close") }
                    }
                },
            )
        }
    }
}
