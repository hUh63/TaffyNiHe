package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.soreverse.mcp.core.EditorAiHelper
import com.soreverse.mcp.core.LspClient
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.WorkspacePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    "svc" to "服务控制", "input" to "注入输入", "su" to "切换 root", "which" to "命令路径",
)

/** 编辑模式行号 gutter（与文本行高 19sp 对齐；行数过多时省略，交由窗口化查看器）。 */
@Composable
private fun EditorGutter(lineCount: Int, currentLine: Int, onLineClick: (Int) -> Unit) {
    if (lineCount <= 0 || lineCount > 3000) return
    Column(Modifier.width(50.dp).padding(end = 6.dp)) {
        for (i in 1..lineCount) {
            val active = i == currentLine
            Text(
                i.toString(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppShape.xs))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
                    )
                    .clickable { onLineClick(i) },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = AppText.label, lineHeight = 19.sp,
                ),
                color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF607D8B),
                textAlign = TextAlign.End,
            )
        }
    }
}

/** 查找：返回 query 在 text 中所有匹配的起始下标（不重叠）。 */
private fun findAllOccurrences(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var i = text.indexOf(query)
    while (i >= 0) { out.add(i); i = text.indexOf(query, i + query.length) }
    return out
}

/** 正则查找：非法正则返回 null（供 UI 提示语法错误）。 */
private fun findAllRegex(text: String, pattern: String): List<Int>? {
    if (pattern.isEmpty()) return emptyList()
    return runCatching {
        Regex(pattern).findAll(text).map { it.range.first }.toList()
    }.getOrNull()
}

/** 编辑器多 tab 的标签快照（借鉴 Xed-Editor 多文件编辑）。 */
private data class EditorTab(
    val name: String,
    val path: String?,      // 工作区/最近文件完整路径（草稿为 null）
    val code: String,       // 快照内容
    val mode: CodeHighlighter.Lang,
    val untitled: Boolean = false,
)

/** 大纲条目（符号导航：类型 + 名称 + 行号）。 */
private data class OutlineItem(val kind: String, val name: String, val line: Int)

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

/** 语言显示名。 */
private fun langLabel(m: CodeHighlighter.Lang, zh: Boolean): String = when (m) {
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

/** 语言的注释前缀（行注释）。 */
private fun commentPrefixOf(m: CodeHighlighter.Lang): String = when (m) {
    CodeHighlighter.Lang.PYTHON, CodeHighlighter.Lang.SHELL, CodeHighlighter.Lang.SMALI -> "# "
    CodeHighlighter.Lang.XML -> "<!-- "
    else -> "// "
}

/**
 * 从源码提取符号大纲（跨语言启发式正则，借鉴 Xed-Editor 的符号/大纲导航）。
 * 返回 (类型, 名称, 行号)，行号 1-based。
 */
private fun outlineOf(src: String, m: CodeHighlighter.Lang): List<OutlineItem> {
    val rules: List<Pair<String, Regex>> = when (m) {
        CodeHighlighter.Lang.PYTHON -> listOf(
            "def" to Regex("^\\s*def\\s+(\\w+)"),
            "class" to Regex("^\\s*class\\s+(\\w+)"),
        )
        CodeHighlighter.Lang.SHELL -> listOf(
            "func" to Regex("^\\s*(?:function\\s+)?(\\w+)\\s*\\(\\)\\s*\\{"),
        )
        CodeHighlighter.Lang.C, CodeHighlighter.Lang.JAVA -> listOf(
            "class" to Regex("^\\s*(?:public|private|protected|internal|open|abstract|final|static|data|sealed|enum|interface|object)\\s+(?:class|interface|object|enum\\s+class)\\s+(\\w+)"),
            "func" to Regex("^\\s{0,8}(?!if|for|while|switch|catch|return|else|do|case|new)\\w[\\w:<>,\\[\\]\\s\\*&]*\\s+(\\w+)\\s*\\([^;]*\\)\\s*\\{"),
            "struct" to Regex("^\\s*struct\\s+(\\w+)"),
        )
        CodeHighlighter.Lang.SMALI -> listOf(
            "method" to Regex("^\\s*\\.method[^\\n]*?\\s([\\w$<>]+)\\("),
            "class" to Regex("^\\s*\\.class[^\\n]*?L([\\w/$]+);"),
        )
        CodeHighlighter.Lang.XML -> listOf(
            "id" to Regex("android:id=\"@\\+id/([\\w.]+)\""),
        )
        CodeHighlighter.Lang.MD -> listOf(
            "h" to Regex("^(#{1,3})\\s+(.+)$"),
        )
        else -> listOf(
            "sym" to Regex("^\\s*(?:def|function|func|fn|fun|class|struct|interface|object|const|let|var)\\s+(\\w+)"),
        )
    }
    val out = ArrayList<OutlineItem>()
    src.lineSequence().forEachIndexed { idx, raw ->
        val line = raw.trimEnd()
        if (line.isBlank()) return@forEachIndexed
        for ((kind, re) in rules) {
            val mt = re.find(line) ?: continue
            val name = (mt.groupValues.getOrNull(2) ?: mt.groupValues.getOrNull(1)).orEmpty().trim()
            if (name.isNotBlank() && name.length <= 60) out.add(OutlineItem(kind, name, idx + 1))
            break
        }
        if (out.size >= 600) return@forEachIndexed
    }
    return out
}

/** 小工具条按钮（文字标签，模仿 Xed-Editor 的 TextIcon 命令按钮）。 */
@Composable
private fun MiniAction(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val bgc = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
        accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fgc = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(bgc)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = AppText.label,
            color = fgc,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 键盘上方的符号/命令键帽（对应 Xed-Editor 的 ExtraKeys）：
 * 32dp 方块、圆角、触感反馈、长按重复。
 */
@Composable
private fun KeyCap(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    repeatOnHold: Boolean = false,
    onKey: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val jobRef = remember { arrayOfNulls<Job>(1) }
    val base = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        Modifier
            .size(width = 40.dp, height = 32.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(if (enabled) base else base.copy(alpha = 0.35f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        onKey()
                        if (repeatOnHold) {
                            jobRef[0] = scope.launch {
                                delay(420)
                                while (true) { onKey(); delay(80) }
                            }
                        }
                        tryAwaitRelease()
                        jobRef[0]?.cancel()
                        jobRef[0] = null
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = AppText.bodyStrong,
            fontFamily = FontFamily.Monospace,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                accent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

/** 面板（控制台/文件/大纲）分页。 */
private enum class EditorPanel { CONSOLE, FILES, OUTLINE }

/**
 * 设置 → 编辑器：Xed-Editor 式图形化代码编辑器。
 *
 * 交互范式（借鉴 Xed-Editor）：
 *  - 固定布局：命令工具条（LazyRow，可横滑 + 更多菜单）+ 占满剩余高度的编辑区 + 键盘上方符号键行（ExtraKeys）+ 状态条
 *  - 行内编辑：编辑区顶部常驻「内联动作条」（缩进/注释/行移动/复制行/删除行/大小写/格式化）
 *  - 跳转：行号、十六进制字节偏移（0x…）、符号名 三种目标；行号 gutter 可点击选中整行
 *  - 面板：控制台 / 工作区文件树 / 符号大纲（Dialog 承载，不挤压编辑区）
 *
 * 能力保留：多 tab + 自动草稿、语法高亮（含语法包扩展语言）、jedi/LSP 代码智能、
 * Python REPL 会话、Shell 执行、AI 助手、快照备份与回滚、语法包管理。
 */
@Composable
internal fun SettingsEditorPage(t: UiText) {
    // 语法高亮配色跟随系统主题（浅色/深色两套）
    CodeHighlighter.useDarkPalette = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    val marker = "__TAFFY_END__"
    val density = LocalDensity.current

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
    var savedCode by remember { mutableStateOf("") }                      // 已落盘内容（dirty 判定）
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
    val consoleScroll = rememberScrollState()
    // ── 查找/替换 ──
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var findRegex by remember { mutableStateOf(false) }
    var findCaseSensitive by remember { mutableStateOf(true) }
    var replaceExpanded by remember { mutableStateOf(false) }
    // ── 大文件窗口化只读查看器（LazyColumn 仅渲染可见行；>200KB 自动启用）──
    var viewerMode by remember { mutableStateOf(false) }
    val viewerListState = rememberLazyListState()
    // ── 编辑模式行号 / 跳转行 ──
    val editorScroll = rememberScrollState()
    val editorLineCount = remember(code) { code.count { c -> c == '\n' } + 1 }
    var showJump by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var showOutlineGoto by remember { mutableStateOf(true) }
    // ── 视图开关 ──
    var showLineNumbers by remember { mutableStateOf(true) }
    var readOnly by remember { mutableStateOf(false) }
    var showExtraKeys by remember { mutableStateOf(true) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // ── 面板（Dialog 承载：控制台/文件/大纲）──
    var panel by remember { mutableStateOf<EditorPanel?>(null) }
    var wsPanelExpanded by remember { mutableStateOf(true) }
    var wsTreeExpanded by remember { mutableStateOf(setOf("")) }
    var editorViewport by remember { mutableStateOf(0) }
    LaunchedEffect(code) { if (code.length > 200_000) viewerMode = true }

    // ── 撤销/重做（按键分组，600ms 内的连续输入合并为一步）──
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }
    var lastEditAt by remember { mutableStateOf(0L) }
    fun recordUndo(prev: String) {
        val now = System.currentTimeMillis()
        if (now - lastEditAt > 600L) {
            undoStack.add(prev)
            if (undoStack.size > 400) undoStack.removeAt(0)
            redoStack.clear()
        }
        lastEditAt = now
    }

    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text != null) {
                recordUndo(tf.text)
                code = text; tf = TextFieldValue(text); savedCode = text; currentFile = null; currentFilePath = null
            }
        }
    }

    fun appendOut(s: String) { output = (output + s).takeLast(20000) }

    fun refreshRecent() {
        recentFiles = runCatching {
            val dir = File(context.filesDir, "editor_files")
            dir.listFiles { f -> f.isFile && f.name.endsWith(".py") || f.name.endsWith(".sh") || f.name.endsWith(".json") || f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }?.map { it.name }?.take(8).orEmpty()
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
            if (f.length() > 256L * 1024 * 1024) return   // 超大文件跳过备份，避免整份读入内存
            f.inputStream().use { ins -> bak.outputStream().use { outs -> ins.copyTo(outs) } }
            // 只保留最近 5 份同名备份
            bakDir.listFiles { it -> it.name.startsWith(f.name) }
                ?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        }
    }

    /** 加载工作区文件（任意路径，写回原文件）。 */
    fun loadWsFile(path: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(path)
                    if (f.length() > 64L * 1024 * 1024) null else f.readText()   // 上限防 OOM
                }.getOrNull()
            }
            if (text != null) {
                snapshotIfChanged(path)
                undoStack.clear(); redoStack.clear()
                code = text
                tf = TextFieldValue(text)
                savedCode = text
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

    /** 设置文本并保持指定光标（行操作/撤销用）。 */
    fun setCodeAt(text: String, caret: Int) {
        code = text
        tf = TextFieldValue(text, selection = TextRange(caret.coerceIn(0, text.length)))
    }

    /** 当前匹配列表（支持正则/大小写开关）。 */
    fun currentMatches(): List<Int>? {
        val text = tf.text
        return if (findRegex) findAllRegex(text, findQuery)
        else if (findCaseSensitive) findAllOccurrences(text, findQuery)
        else findAllOccurrences(text.lowercase(), findQuery.lowercase())
    }

    // ── 查找/替换：在 tf 上做光标跳转与替换，复用底层 TextFieldValue ──
    fun jumpToMatch(dir: Int) {
        val starts = currentMatches().orEmpty()
        if (starts.isEmpty()) return
        val cur = tf.selection.start
        val target = if (dir >= 0) starts.firstOrNull { it > cur } ?: starts.first()
        else starts.lastOrNull { it < cur } ?: starts.last()
        tf = TextFieldValue(tf.text, selection = TextRange(target, (target + findQuery.length).coerceAtMost(tf.text.length)))
    }

    fun replaceCurrentMatch() {
        val starts = currentMatches().orEmpty()
        if (starts.isEmpty()) return
        val text = tf.text
        val cur = tf.selection
        val at = starts.firstOrNull { it == cur.start && it + findQuery.length == cur.end } ?: starts.first()
        recordUndo(text)
        val newText = text.substring(0, at) + replaceQuery + text.substring(at + findQuery.length)
        setCodeAt(newText, at + replaceQuery.length)
    }

    fun replaceAllMatches() {
        if (findQuery.isEmpty()) return
        val text = tf.text
        recordUndo(text)
        val newText = if (findRegex) {
            runCatching { Regex(findQuery).replace(text, replaceQuery) }.getOrDefault(text)
        } else {
            if (findCaseSensitive) text.replace(findQuery, replaceQuery)
            else Regex(Regex.escape(findQuery), RegexOption.IGNORE_CASE).replace(text, replaceQuery)
        }
        setCode(newText)
    }

    /** 跳转到第 n 行（1-based）：把光标移动到该行行首并滚动到可见。 */
    fun jumpToLine(n: Int) {
        val text = tf.text
        if (text.isEmpty()) return
        var idx = 0
        var line = 1
        while (line < n) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) { idx = text.length; break }
            idx = nl + 1
            line++
        }
        tf = TextFieldValue(text, selection = TextRange(idx))
        scope.launch { delay(30); followCursor() }
    }

    /** 把当前光标行滚动到视口内（编辑区固定高度，BasicTextField 不会自己跟随）。 */
    suspend fun followCursor() {
        if (viewerMode || editorViewport <= 0) return
        val text = tf.text
        val before = text.substring(0, tf.selection.start.coerceIn(0, text.length))
        val lineIdx = before.count { it == '\n' }
        val lineH = with(density) { 19.sp.toPx() }
        val y = lineIdx * lineH
        val vp = editorViewport.toFloat()
        val cur = editorScroll.value.toFloat()
        if (y < cur + lineH || y > cur + vp - lineH * 2f) {
            runCatching { editorScroll.scrollTo((y - vp * 0.4f).toInt().coerceAtLeast(0)) }
        }
    }

    // ── 行级编辑操作（行内编辑核心：缩进/注释/移动/复制/删除/大小写/格式化）──
    fun caretLine(): Int {
        val s = tf.selection.start.coerceIn(0, tf.text.length)
        return tf.text.substring(0, s).count { it == '\n' } + 1
    }

    /** 选区覆盖的行号范围（1-based，含端点）。 */
    fun selectionLineRange(): Pair<Int, Int> {
        val text = tf.text
        val a = tf.selection.min.coerceIn(0, text.length)
        val b = tf.selection.max.coerceIn(0, text.length)
        val from = text.substring(0, a).count { it == '\n' } + 1
        val to = text.substring(0, b).count { it == '\n' } + 1
        return from to to
    }

    /** 对指定行区间做变换，光标停留在原行首。 */
    fun transformLines(from: Int, to: Int, f: (String) -> String) {
        val text = tf.text
        val lines = text.split('\n').toMutableList()
        val a = (from - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val b = (to - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        if (lines.isEmpty()) return
        for (i in a..b) lines[i] = f(lines[i])
        val nt = lines.joinToString("\n")
        val caret = lineStartOffset(nt, caretLine())
        recordUndo(text)
        setCodeAt(nt, caret)
    }

    fun moveLines(dy: Int) {
        val text = tf.text
        val lines = text.split('\n').toMutableList()
        val (from, to) = selectionLineRange()
        val a = from - 1
        val b = to - 1
        if (a < 0 || b >= lines.size) return
        if (dy < 0) {
            if (a == 0) return
            val moved = lines.removeAt(b)
            lines.add(a - 1, moved)
        } else {
            if (b >= lines.size - 1) return
            val moved = lines.removeAt(a)
            lines.add(b, moved)
        }
        val nt = lines.joinToString("\n")
        recordUndo(text)
        setCodeAt(nt, lineStartOffset(nt, (from + dy).coerceAtLeast(1)))
    }

    fun duplicateLines() {
        val text = tf.text
        val lines = text.split('\n').toMutableList()
        val (from, to) = selectionLineRange()
        val slice = lines.subList((from - 1).coerceAtLeast(0), to.coerceAtMost(lines.size)).toList()
        lines.addAll(to.coerceAtMost(lines.size), slice)
        val nt = lines.joinToString("\n")
        recordUndo(text)
        setCodeAt(nt, lineStartOffset(nt, from))
    }

    fun deleteLines() {
        val text = tf.text
        val lines = text.split('\n').toMutableList()
        val (from, to) = selectionLineRange()
        val a = (from - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val b = (to - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        repeat(b - a + 1) { if (a < lines.size) lines.removeAt(a) }
        if (lines.isEmpty()) lines.add("")
        val nt = lines.joinToString("\n")
        recordUndo(text)
        setCodeAt(nt, lineStartOffset(nt, from))
    }

    fun toggleComment() {
        val p = commentPrefixOf(mode)
        val (from, to) = selectionLineRange()
        val text = tf.text
        val seg = text.split('\n').drop(from - 1).take(to - from + 1)
        val allCommented = seg.isNotEmpty() && seg.all { it.trimStart().startsWith(p.trim()) }
        transformLines(from, to) { line -> if (allCommented) line.replaceFirst(p.trim(), "").let { if (it.startsWith(" ")) it.substring(1) else it } else p + line }
    }

    fun indentSelected(outdent: Boolean) {
        val (from, to) = selectionLineRange()
        transformLines(from, to) { line ->
            if (outdent) {
                when {
                    line.startsWith("\t") -> line.substring(1)
                    line.startsWith("    ") -> line.substring(4)
                    line.startsWith("  ") -> line.substring(2)
                    line.startsWith(" ") -> line.substring(1)
                    else -> line
                }
            } else if (mode == CodeHighlighter.Lang.SHELL || mode == CodeHighlighter.Lang.SMALI) "\t$line" else "    $line"
        }
    }

    fun toggleCase(upper: Boolean) {
        val text = tf.text
        val s = tf.selection.min.coerceIn(0, text.length)
        val e = tf.selection.max.coerceIn(0, text.length)
        if (s == e) return
        val seg = text.substring(s, e)
        val rep = if (upper) seg.uppercase() else seg.lowercase()
        recordUndo(text)
        setCodeAt(text.substring(0, s) + rep + text.substring(e), s + rep.length)
    }

    /** 格式化：JSON 美化；其余做「去行尾空白 + 规整缩进占位」。 */
    fun formatCode() {
        val text = tf.text
        recordUndo(text)
        if (mode == CodeHighlighter.Lang.JSON) {
            val r = runCatching { JSONObject(text).toString(2) }
            r.onSuccess { setCode(it); appendOut("\n[已格式化 JSON]\n") }
                .onFailure {
                    runCatching { JSONArray(text).toString(2) }
                        .onSuccess { arr -> setCode(arr); appendOut("\n[已格式化 JSON 数组]\n") }
                        .onFailure { e -> appendOut("\n[JSON 无效: ${e.message}]\n") }
                }
        } else {
            val nt = text.split('\n').joinToString("\n") { it.trimEnd() }
            setCode(nt)
            appendOut("\n[已清理行尾空白]\n")
        }
    }

    /** 在光标处插入文本（符号键行用）。 */
    fun insertText(s: String) {
        val text = tf.text
        val a = tf.selection.min.coerceIn(0, text.length)
        val b = tf.selection.max.coerceIn(0, text.length)
        recordUndo(text)
        val nt = text.substring(0, a) + s + text.substring(b)
        setCodeAt(nt, a + s.length)
    }

    /** 光标左右/上下/行首行尾移动。 */
    fun moveCaret(delta: Int) {
        val text = tf.text
        val p = (tf.selection.start + delta).coerceIn(0, text.length)
        tf = TextFieldValue(text, TextRange(p))
    }

    fun moveCaretVertical(dy: Int) {
        val text = tf.text
        val s = tf.selection.start.coerceIn(0, text.length)
        val before = text.substring(0, s)
        val lineIdx = before.count { it == '\n' }
        val col = s - (before.lastIndexOf('\n') + 1)
        val lines = text.split('\n')
        val target = (lineIdx + dy).coerceIn(0, lines.size - 1)
        var off = 0
        for (i in 0 until target) off += lines[i].length + 1
        off += col.coerceAtMost(lines[target].length)
        tf = TextFieldValue(text, TextRange(off.coerceIn(0, text.length)))
    }

    fun moveCaretEdge(toEnd: Boolean) {
        val text = tf.text
        val s = tf.selection.start.coerceIn(0, text.length)
        val ls = text.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val le = text.indexOf('\n', s).let { if (it < 0) text.length else it }
        tf = TextFieldValue(text, TextRange(if (toEnd) le else ls))
    }

    fun selectAllText() {
        val text = tf.text
        tf = TextFieldValue(text, TextRange(0, text.length))
    }

    fun clipboardCopy(cut: Boolean = false) {
        val text = tf.text
        val s = tf.selection.min
        val e = tf.selection.max
        if (s == e) return
        val seg = text.substring(s, e)
        copyToClipboard(context, seg)
        if (cut) {
            recordUndo(text)
            setCodeAt(text.substring(0, s) + text.substring(e), s)
        }
    }

    fun clipboardPaste() {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val item = cm.primaryClip?.getItemAt(0) ?: return
        val seg = item.coerceToText(context).toString()
        if (seg.isNotEmpty()) insertText(seg)
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
        savedCode = first.code
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
            tabs.forEach { tb ->
                arr.put(JSONObject().put("name", tb.name).put("path", tb.path ?: "").put("code", tb.code)
                    .put("mode", tb.mode.name).put("untitled", tb.untitled))
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
        val tb = tabs[i]
        setCode(tb.code)
        savedCode = tb.code
        mode = tb.mode
        currentFile = if (tb.untitled) null else tb.name
        currentFilePath = tb.path
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
        val tb = finalTabs[na]
        setCode(tb.code)
        savedCode = tb.code
        mode = tb.mode
        currentFile = if (tb.untitled) null else tb.name
        currentFilePath = tb.path
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
        val text = tf.text
        val s = tf.selection.start.coerceIn(0, text.length)
        val wordStart = Regex("[A-Za-z0-9_]*$").find(text.substring(0, s))?.range?.first ?: s
        recordUndo(text)
        val newText = text.substring(0, wordStart) + name + text.substring(s)
        setCodeAt(newText, wordStart + name.length)
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
        panel = EditorPanel.CONSOLE
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
        panel = EditorPanel.CONSOLE
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
            savedCode = code
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
                undoStack.clear(); redoStack.clear()
                setCode(text)
                savedCode = text
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

    fun rollbackLatest() {
        val name = currentFile
        if (name == null) { appendOut("\n[先打开或保存一个文件]\n"); return }
        val bakDir = File(context.filesDir, "editor_files/backup")
        val latest = bakDir.listFiles { it -> it.name.startsWith("$name.") && it.name.endsWith(".bak") }
            ?.maxByOrNull { it.lastModified() }
        if (latest == null) { appendOut("\n[没有可回滚的备份]\n") } else {
            scope.launch {
                val text = withContext(Dispatchers.IO) { runCatching { latest.readText() }.getOrNull() }
                if (text != null) { recordUndo(tf.text); setCode(text); appendOut("\n[已回滚到备份: ${latest.name}]\n") }
            }
        }
    }

    fun newFile() {
        snapshotCurrent()
        undoStack.clear(); redoStack.clear()
        setCode(""); savedCode = ""; currentFile = null; currentFilePath = null; showCompletions = false
        tabs = tabs.toMutableList().also { it[activeTab] = EditorTab(if (zh) "草稿" else "Draft", null, "", mode, true) }
    }

    LaunchedEffect(output) { runCatching { consoleScroll.scrollTo(consoleScroll.maxValue) } }

    // ── 跳转：行号 / 十六进制字节偏移 / 符号名（借鉴 Exbin 地址跳转 + Xed 跳行）──
    fun performJump(raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        // ① 十六进制/十进制字节偏移
        val off = when {
            s.startsWith("0x", true) -> s.substring(2).toLongOrNull(16)
            s.startsWith("@") -> s.substring(1).toLongOrNull(16)
            s.matches(Regex("^\\d+$")) && s.length >= 4 -> s.toLongOrNull()
            else -> null
        }
        if (off != null) {
            val text = tf.text
            val bytes = text.toByteArray(Charsets.UTF_8)
            val targetByte = off.coerceIn(0L, bytes.size.toLong()).toInt()
            var consumed = 0
            var lineNo = 1
            var charIdx = 0
            while (charIdx < text.length && consumed < targetByte) {
                if (text[charIdx] == '\n') lineNo++
                consumed += text[charIdx].toString().toByteArray(Charsets.UTF_8).size
                charIdx++
            }
            tf = TextFieldValue(text, TextRange(charIdx.coerceIn(0, text.length)))
            appendOut("\n[跳转 偏移 0x${off.toString(16)} → 行 $lineNo, 列 ${charIdx - (text.lastIndexOf('\n', (charIdx - 1).coerceAtLeast(0)) + 1) + 1}]\n")
            scope.launch { delay(30); followCursor() }
            return
        }
        // ② 行号（1 / :12 / L12）
        val lineNo = s.removePrefix(":").removePrefix("L").removePrefix("l").toIntOrNull()
        if (lineNo != null && lineNo > 0) { jumpToLine(lineNo); return }
        // ③ 符号名 → 大纲里找
        val hit = outlineOf(tf.text, mode).firstOrNull { it.name.equals(s, true) }
            ?: outlineOf(tf.text, mode).firstOrNull { it.name.contains(s, true) }
        if (hit != null) { jumpToLine(hit.line); appendOut("\n[跳转符号 ${hit.name} @ 行 ${hit.line}]\n") }
        else appendOut("\n[无法解析跳转目标: $s]\n")
    }

    // ══════════════════════════ UI ══════════════════════════
    val dirty = code != savedCode
    val (curLn, curCol) = cursorLineCol()
    val outline = remember(code, mode) { outlineOf(code, mode) }
    val matchCount = remember(findQuery, code, findRegex, findCaseSensitive) {
        if (!showFind || findQuery.isEmpty()) emptyList() else (currentMatches() ?: emptyList())
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── 模式选择（横滑，内置语言 + 语法包扩展语言）──
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(CodeHighlighter.Lang.entries.toList()) { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = {
                        mode = m
                        if (m == CodeHighlighter.Lang.EXT) {
                            val ext = File(currentFilePath).extension
                            CodeHighlighter.activePack = com.soreverse.mcp.core.EditorSyntaxPacks.forExt(ext)
                                ?: com.soreverse.mcp.core.EditorSyntaxPacks.packs.firstOrNull()
                        }
                    },
                    label = { Text(langLabel(m, zh), fontSize = AppText.label, maxLines = 1) },
                )
            }
        }

        // ── 多 tab 条（点标签切换，点 × 关闭，• 表示未保存）──
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tabs.size) { i ->
                val tabItem = tabs[i]
                val tabDirty = (i == activeTab && tabItem.code != code) || (i != activeTab && tabItem.code != tabItem.name)
                FilterChip(
                    selected = i == activeTab,
                    onClick = { switchTab(i) },
                    label = {
                        Text(
                            (if (tabDirty) "• " else "") + (if (tabItem.untitled) (if (zh) "草稿" else "Draft") else tabItem.name),
                            fontSize = AppText.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Text(
                            "×",
                            fontSize = AppText.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppShape.xs))
                                .clickable { closeTab(i) }
                                .padding(horizontal = 3.dp),
                        )
                    },
                )
            }
        }

        // ── 命令工具条（LazyRow 横滑 + 更多菜单）──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("undo", "redo", "indent", "outdent", "comment", "save", "find", "run")) { id ->
                    when (id) {
                        "undo" -> MiniAction("↶ " + (if (zh) "撤销" else "Undo"), undoStack.isNotEmpty()) { undoStack.removeLastOrNull()?.let { p -> redoStack.add(tf.text); setCode(p) } }
                        "redo" -> MiniAction("↷ " + (if (zh) "重做" else "Redo"), redoStack.isNotEmpty()) { redoStack.removeLastOrNull()?.let { n -> undoStack.add(tf.text); setCode(n) } }
                        "indent" -> MiniAction("⇥ " + (if (zh) "缩进" else "Indent")) { indentSelected(false) }
                        "outdent" -> MiniAction("⇤ " + (if (zh) "取消缩进" else "Outdent")) { indentSelected(true) }
                        "comment" -> MiniAction((if (zh) "注释" else "Comment")) { toggleComment() }
                        "save" -> MiniAction("💾 " + (if (zh) "保存" else "Save"), code.isNotBlank()) { saveFile() }
                        "find" -> MiniAction("🔍 " + (if (zh) "查找" else "Find"), accent = showFind) { showFind = !showFind }
                        "run" -> MiniAction("▶ " + (if (zh) "运行" else "Run"), code.isNotBlank()) { runCode() }
                    }
                }
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert, if (zh) "更多" else "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    DropdownMenuItem(text = { Text(if (zh) "运行输出 / 控制台" else "Console") }, onClick = { showMoreMenu = false; panel = EditorPanel.CONSOLE })
                    DropdownMenuItem(text = { Text(if (zh) "工作区文件" else "Files") }, onClick = { showMoreMenu = false; panel = EditorPanel.FILES })
                    DropdownMenuItem(text = { Text(if (zh) "符号大纲 (${outline.size})" else "Outline (${outline.size})") }, onClick = { showMoreMenu = false; panel = EditorPanel.OUTLINE })
                    DropdownMenuItem(text = { Text(if (zh) "跳转（行/偏移/符号）" else "Go to (line/offset/symbol)") }, onClick = { showMoreMenu = false; showJump = true })
                    DropdownMenuItem(text = { Text(if (zh) "注释选中行" else "Toggle comment") }, onClick = { showMoreMenu = false; toggleComment() })
                    DropdownMenuItem(text = { Text(if (zh) "上移行" else "Move line up") }, onClick = { showMoreMenu = false; moveLines(-1) })
                    DropdownMenuItem(text = { Text(if (zh) "下移行" else "Move line down") }, onClick = { showMoreMenu = false; moveLines(1) })
                    DropdownMenuItem(text = { Text(if (zh) "复制行" else "Duplicate line") }, onClick = { showMoreMenu = false; duplicateLines() })
                    DropdownMenuItem(text = { Text(if (zh) "删除行" else "Delete line") }, onClick = { showMoreMenu = false; deleteLines() })
                    DropdownMenuItem(text = { Text(if (zh) "格式化 / 美化" else "Format") }, onClick = { showMoreMenu = false; formatCode() })
                    DropdownMenuItem(text = { Text((if (showLineNumbers) "隐藏" else "显示") + (if (zh) "行号" else " line numbers")) }, onClick = { showMoreMenu = false; showLineNumbers = !showLineNumbers })
                    DropdownMenuItem(text = { Text((if (readOnly) (if (zh) "可编辑" else "Editable") else (if (zh) "只读" else "Read only"))) }, onClick = { showMoreMenu = false; readOnly = !readOnly })
                    DropdownMenuItem(text = { Text(if (zh) "窗口化查看（大文件）" else "Windowed view") }, onClick = { showMoreMenu = false; viewerMode = !viewerMode })
                    DropdownMenuItem(text = { Text(if (zh) "打开文件" else "Open file") }, onClick = { showMoreMenu = false; loadLauncher.launch(arrayOf("text/plain", "text/x-python", "application/json", "*/*")) })
                    DropdownMenuItem(text = { Text(if (zh) "新建文件" else "New file") }, onClick = { showMoreMenu = false; newFile() })
                    DropdownMenuItem(text = { Text(if (zh) "回滚最近备份" else "Restore backup") }, enabled = currentFile != null, onClick = { showMoreMenu = false; rollbackLatest() })
                    DropdownMenuItem(text = { Text(if (zh) "语法包管理" else "Syntax packs") }, onClick = { showMoreMenu = false; showPackManager = true })
                    DropdownMenuItem(text = { Text("🤖 " + (if (zh) "AI 助手" else "AI assist"), color = if (aiReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }, enabled = aiReady && !aiBusy, onClick = { showMoreMenu = false; requestAiAssist() })
                    DropdownMenuItem(text = { Text((if (showExtraKeys) (if (zh) "隐藏符号键行" else "Hide keys row") else (if (zh) "显示符号键行" else "Show keys row"))) }, onClick = { showMoreMenu = false; showExtraKeys = !showExtraKeys })
                }
            }
            IconButton(onClick = { saveFile() }, enabled = code.isNotBlank(), modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Save, if (zh) "保存" else "Save", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // ── 代码智能快捷条（Python：补全/文档/定义/诊断）──
        if (mode == CodeHighlighter.Lang.PYTHON) {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { MiniAction("⚡ " + (if (zh) "补全" else "Complete"), !completing) { requestCompletion("complete") } }
                item { MiniAction("? " + (if (zh) "文档" else "Doc"), !completing) { requestCompletion("hover") } }
                item { MiniAction("→ " + (if (zh) "定义" else "Def"), !completing) { requestCompletion("defs") } }
                item { MiniAction("⚠ " + (if (zh) "诊断" else "Diag"), !completing) { requestCompletion("diag") } }
            }
        }

        // ── 查找/替换栏 ──
        if (showFind) {
            val regexErr = findRegex && findAllRegex(code, findQuery) == null
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = regexErr,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
                        label = { Text(if (zh) "查找" else "Find", fontSize = AppText.label) },
                    )
                    Text(
                        if (regexErr) (if (zh) "正则错误" else "bad regex")
                        else if (findQuery.isEmpty()) "" else "${matchCount.size} ${if (zh) "处" else "hits"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (regexErr) MaterialTheme.colorScheme.error else AppPalette.teal,
                    )
                    MiniAction("Aa", accent = findCaseSensitive, enabled = !findRegex) { findCaseSensitive = !findCaseSensitive }
                    MiniAction(".*", accent = findRegex) { findRegex = !findRegex }
                    MiniAction("↑", matchCount.isNotEmpty()) { jumpToMatch(-1) }
                    MiniAction("↓", matchCount.isNotEmpty()) { jumpToMatch(1) }
                    MiniAction("⇄", accent = replaceExpanded) { replaceExpanded = !replaceExpanded }
                    MiniAction("✕") { showFind = false }
                }
                if (replaceExpanded) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body),
                            label = { Text(if (zh) "替换为" else "Replace with", fontSize = AppText.label) },
                        )
                        MiniAction(if (zh) "替换" else "Replace", matchCount.isNotEmpty()) { replaceCurrentMatch() }
                        MiniAction(if (zh) "全部" else "All", matchCount.isNotEmpty()) { replaceAllMatches() }
                    }
                }
            }
        }

        // ── 编辑区（占满剩余高度）──
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 180.dp)
                .clip(RoundedCornerShape(AppShape.lg))
                .background(TerminalColors.bg)
                .border(1.dp, TerminalColors.fg.copy(alpha = 0.10f), RoundedCornerShape(AppShape.lg)),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 内联动作条（行内编辑：光标即可动手，不必上下找按钮）
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    item { MiniAction(if (zh) "复制" else "Copy", tf.selection.min != tf.selection.max) { clipboardCopy() } }
                    item { MiniAction(if (zh) "剪切" else "Cut", tf.selection.min != tf.selection.max) { clipboardCopy(true) } }
                    item { MiniAction(if (zh) "粘贴" else "Paste") { clipboardPaste() } }
                    item { MiniAction(if (zh) "全选" else "All") { selectAllText() } }
                    item { MiniAction(if (zh) "复制行" else "Dup line") { duplicateLines() } }
                    item { MiniAction("↑行") { moveLines(-1) } }
                    item { MiniAction("↓行") { moveLines(1) } }
                    item { MiniAction(if (zh) "删行" else "Del line") { deleteLines() } }
                    item { MiniAction(if (zh) "注释" else "Comment") { toggleComment() } }
                    item { MiniAction(if (zh) "大写" else "UPPER", tf.selection.min != tf.selection.max) { toggleCase(true) } }
                    item { MiniAction(if (zh) "小写" else "lower", tf.selection.min != tf.selection.max) { toggleCase(false) } }
                    item { MiniAction(if (zh) "格式化" else "Format") { formatCode() } }
                    item { MiniAction(if (zh) "撤销" else "Undo", undoStack.isNotEmpty()) { undoStack.removeLastOrNull()?.let { p -> redoStack.add(tf.text); setCode(p) } } }
                    item { MiniAction(if (zh) "重做" else "Redo", redoStack.isNotEmpty()) { redoStack.removeLastOrNull()?.let { n -> undoStack.add(tf.text); setCode(n) } } }
                    item { MiniAction("Ln") { showJump = true } }
                }
                Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { editorViewport = it.height }) {
                    if (viewerMode) {
                        val vLines = remember(code) { code.lineSequence().take(200_001).toList() }
                        LazyColumn(state = viewerListState, modifier = Modifier.fillMaxSize().padding(4.dp)) {
                            itemsIndexed(vLines) { idx, line ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        (idx + 1).toString(),
                                        modifier = Modifier.width(50.dp).padding(end = 6.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                                        color = if (idx + 1 == curLn) AppPalette.teal else Color(0xFF607D8B),
                                        textAlign = TextAlign.End,
                                    )
                                    Text(
                                        line.ifEmpty { " " },
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong, lineHeight = 19.sp),
                                        color = TerminalColors.fg,
                                    )
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().verticalScroll(editorScroll).padding(vertical = 6.dp)) {
                            if (showLineNumbers) EditorGutter(editorLineCount, curLn) { n ->
                                tf = TextFieldValue(tf.text, TextRange(lineStartOffset(tf.text, n)))
                                scope.launch { delay(20); followCursor() }
                            }
                            BasicTextField(
                                value = tf,
                                onValueChange = {
                                    recordUndo(tf.text)
                                    tf = it; code = it.text
                                    lastInputAt = System.currentTimeMillis()   // 全模式自动补全（按模式分流）
                                },
                                modifier = Modifier.weight(1f),
                                readOnly = readOnly,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong, lineHeight = 19.sp, color = TerminalColors.fg),
                                cursorBrush = SolidColor(AppPalette.teal),
                                visualTransformation = remember(mode) {
                                    // 高亮结果按"原文"缓存：VisualTransformation 每次布局/光标移动都会调用，
                                    // 缓存可避免逐字符状态机在主线程反复全文重算（大文件编辑卡顿主因）。
                                    val cache = HashMap<String, androidx.compose.ui.text.AnnotatedString>()
                                    VisualTransformation { text ->
                                        val src = text.text
                                        val ann = cache[src] ?: CodeHighlighter.highlight(src, mode).also {
                                            if (cache.size > 32) cache.clear()
                                            cache[src] = it
                                        }
                                        androidx.compose.ui.text.input.TransformedText(
                                            ann,
                                            androidx.compose.ui.text.input.OffsetMapping.Identity,
                                        )
                                    }
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
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = AppText.bodyStrong),
                                                modifier = Modifier.padding(6.dp),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                    }

                    // 只读徽标
                    if (readOnly) {
                        Text(
                            if (zh) "只读" else "read-only",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppPalette.orange,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(AppShape.xs))
                                .background(Color(0xCC0B0F14))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    // 补全面板：覆盖在编辑区底部的浮层（不推挤布局）
                    if (showCompletions && mode != CodeHighlighter.Lang.TEXT) {
                        Column(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .heightIn(max = 190.dp)
                                .background(Color(0xF20E141C), RoundedCornerShape(AppShape.md)),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (completionVia.ifBlank { if (zh) "补全" else "Completion" }) + if (completing) " · 分析中…" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppPalette.teal,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (zh) "关闭" else "close",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalColors.dim,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppShape.xs))
                                        .clickable { showCompletions = false }
                                        .padding(horizontal = 6.dp),
                                )
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 158.dp)) {
                                items(completions.size) { i ->
                                    val c = completions[i]
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { insertCompletion(c.optString("name")) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TypeChip(c.optString("type"))
                                        Text(
                                            c.optString("name"),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = TerminalColors.fg,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            c.optString("doc").lineSequence().firstOrNull().orEmpty(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TerminalColors.dim,
                                            modifier = Modifier.weight(1.2f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 输出预览条（不挤占编辑区：点击展开完整控制台）──
        if (output.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppShape.sm))
                    .background(TerminalColors.bg)
                    .clickable { panel = EditorPanel.CONSOLE }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$", color = TerminalColors.prompt, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                Text(
                    output.lineSequence().lastOrNull { it.isNotBlank() } ?: "",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TerminalColors.fg,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (zh) "展开 ⌃" else "open ⌃", style = MaterialTheme.typography.labelSmall, color = TerminalColors.dim)
            }
        }

        // ── 键盘上方符号/命令键行（ExtraKeys）──
        if (showExtraKeys) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { KeyCap("Tab") { insertText(if (mode == CodeHighlighter.Lang.SHELL || mode == CodeHighlighter.Lang.SMALI) "\t" else "    ") } }
                    item { KeyCap("←", repeatOnHold = true) { moveCaret(-1) } }
                    item { KeyCap("→", repeatOnHold = true) { moveCaret(1) } }
                    item { KeyCap("↑", repeatOnHold = true) { moveCaretVertical(-1) } }
                    item { KeyCap("↓", repeatOnHold = true) { moveCaretVertical(1) } }
                    item { KeyCap("Home") { moveCaretEdge(false) } }
                    item { KeyCap("End") { moveCaretEdge(true) } }
                    item {
                        KeyCap("⌫", repeatOnHold = true) {
                            val text = tf.text
                            val a = tf.selection.min.coerceIn(0, text.length)
                            val b = tf.selection.max.coerceIn(0, text.length)
                            recordUndo(text)
                            if (a != b) setCodeAt(text.substring(0, a) + text.substring(b), a)
                            else if (a > 0) setCodeAt(text.substring(0, a - 1) + text.substring(a), a - 1)
                        }
                    }
                    item { KeyCap("⏎") { insertText("\n") } }
                    item { KeyCap("↶", accent = true, enabled = undoStack.isNotEmpty()) { undoStack.removeLastOrNull()?.let { p -> redoStack.add(tf.text); setCode(p) } } }
                    item { KeyCap("↷", enabled = redoStack.isNotEmpty()) { redoStack.removeLastOrNull()?.let { n -> undoStack.add(tf.text); setCode(n) } } }
                    item { KeyCap("💾", accent = true) { saveFile() } }
                }
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(
                        "{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", "=", "+", "-", "*", "/", "\\",
                        "|", "&", "!", "?", "#", "@", "_", ";", ":", ",", ".", "$", "%", "~", "^", "`", "€",
                    )) { s ->
                        KeyCap(s) { insertText(s) }
                    }
                }
            }
        }

        // ── 状态条 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Ln $curLn : Col $curCol",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "$editorLineCount ${if (zh) "行" else "L"} · ${code.length} ${if (zh) "字符" else "ch"}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                langLabel(mode, zh),
                style = MaterialTheme.typography.labelSmall,
                color = AppPalette.teal,
            )
            Text(
                (if (dirty) "● " else "✓ ") + (if (zh) "未保存" else "unsaved"),
                style = MaterialTheme.typography.labelSmall,
                color = if (dirty) AppPalette.orange else AppPalette.green,
            )
        }
    }

    // ══════════════════════════ 面板（控制台 / 文件 / 大纲）══════════════════════════
    if (panel != null) {
        Dialog(onDismissRequest = { panel = null }) {
            Surface(shape = RoundedCornerShape(AppShape.lg), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniAction(if (zh) "控制台" else "Console", accent = panel == EditorPanel.CONSOLE) { panel = EditorPanel.CONSOLE }
                        MiniAction(if (zh) "文件" else "Files", accent = panel == EditorPanel.FILES) { panel = EditorPanel.FILES }
                        MiniAction((if (zh) "大纲" else "Outline") + " ${outline.size}", accent = panel == EditorPanel.OUTLINE) { panel = EditorPanel.OUTLINE }
                        Spacer(Modifier.weight(1f))
                        MiniAction(if (zh) "关闭" else "Close") { panel = null }
                    }
                    when (panel) {
                        EditorPanel.CONSOLE -> {
                            // 控制台：黑底 + 提示符 + 内嵌输入行 + 自动滚底
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 200.dp, max = 380.dp)
                                    .clip(RoundedCornerShape(AppShape.md))
                                    .background(TerminalColors.bg)
                                    .padding(10.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "console" + when {
                                            sessionActive -> " · python"
                                            mode == CodeHighlighter.Lang.PYTHON -> " · python"
                                            else -> " · shell"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = TerminalColors.dim,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (zh) "复制" else "copy",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerminalColors.dim,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppShape.xs))
                                            .clickable { if (output.isNotBlank()) copyToClipboard(context, output) }
                                            .padding(horizontal = 5.dp),
                                    )
                                    Text(
                                        if (zh) "清屏" else "clear",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerminalColors.dim,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppShape.xs))
                                            .clickable { output = "" }
                                            .padding(horizontal = 5.dp),
                                    )
                                }
                                Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(consoleScroll)) {
                                    SelectionContainer {
                                        Text(
                                            output.ifEmpty { if (zh) "（输出显示在这里——运行代码或输入命令）" else "(output appears here)" },
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.body, lineHeight = 17.sp),
                                            color = TerminalColors.fg,
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        if (mode == CodeHighlighter.Lang.PYTHON && sessionActive) ">>>" else "$",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong),
                                        color = TerminalColors.prompt,
                                    )
                                    OutlinedTextField(
                                        value = replInput,
                                        onValueChange = { replInput = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = {
                                            Text(
                                                if (mode == CodeHighlighter.Lang.PYTHON && sessionActive) (if (zh) "Python 表达式" else "python expression")
                                                else (if (zh) "shell 命令（id / ls / pm list packages…）" else "shell command"),
                                                color = TerminalColors.dim,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = AppText.body,
                                            )
                                        },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.bodyStrong, color = TerminalColors.fg),
                                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = TerminalColors.bg, unfocusedContainerColor = TerminalColors.bg, focusedBorderColor = AppPalette.teal.copy(alpha = 0.5f), unfocusedBorderColor = Color(0xFF1E2A36)),
                                        shape = RoundedCornerShape(AppShape.md),
                                    )
                                    IconButton(
                                        onClick = { if (mode == CodeHighlighter.Lang.PYTHON && sessionActive) sendRepl(replInput) else shellExec(replInput) },
                                        enabled = replInput.isNotBlank() && !busy,
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        EditorPanel.FILES -> {
                            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp)) {
                                // 最近文件
                                if (recentFiles.isNotEmpty()) {
                                    Text(if (zh) "最近文件" else "Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(recentFiles) { name ->
                                            FilterChip(
                                                selected = currentFile == name,
                                                onClick = {
                                                    ensureTab(name, File(File(context.filesDir, "editor_files"), name).absolutePath)
                                                    loadFile(name)
                                                    panel = null
                                                },
                                                label = { Text(name, fontSize = AppText.label, maxLines = 1) },
                                            )
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (zh) "工作区" else "Workspace",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    MiniAction(if (wsPanelExpanded) (if (zh) "收起" else "collapse") else (if (zh) "展开" else "expand")) { wsPanelExpanded = !wsPanelExpanded }
                                    Spacer(Modifier.width(6.dp))
                                    MiniAction("↻") { wsTreeExpanded = wsTreeExpanded }
                                }
                                if (wsPanelExpanded) {
                                    val rows = remember(wsTreeExpanded, panel) {
                                        val acc = ArrayList<Triple<String, Boolean, Int>>()
                                        flattenTree(context, "", 0, wsTreeExpanded, acc)
                                        acc
                                    }
                                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                        items(rows.size) { i ->
                                            val (rel, isDir, depth) = rows[i]
                                            val name = rel.substringAfterLast('/')
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (isDir) {
                                                            wsTreeExpanded = if (rel in wsTreeExpanded) wsTreeExpanded - rel else wsTreeExpanded + rel
                                                        } else {
                                                            val ws = WorkspacePolicy.workDirPath(context)
                                                            if (ws != null) {
                                                                val target = File(ws, rel).absolutePath
                                                                ensureTab(name, target)
                                                                loadWsFile(target)
                                                                panel = null
                                                            }
                                                        }
                                                    }
                                                    .padding(start = (depth * 14 + 8).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    if (isDir) (if (rel in wsTreeExpanded) "▾" else "▸") else "·",
                                                    fontSize = AppText.label,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    name,
                                                    fontSize = AppText.label,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (isDir) MaterialTheme.colorScheme.primary else TerminalColors.fg,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        EditorPanel.OUTLINE -> {
                            if (outline.isEmpty()) {
                                InlineHint(if (zh) "未解析到符号（支持 def/class/fun/func/fn/struct/.method 等）" else "No symbols found")
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp)) {
                                    items(outline.size) { i ->
                                        val it0 = outline[i]
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { panel = null; jumpToLine(it0.line) }
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            TypeChip(text = it0.kind, color = AppPalette.indigo)
                                            Text(
                                                it0.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                ":${it0.line}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    // ── 跳转对话框（行号 / 0x偏移 / 符号名）──
    if (showJump) {
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text(if (zh) "跳转" else "Go to", fontSize = AppText.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (zh) "支持三种目标：行号（12 或 :12）、十六进制字节偏移（0x1F2A）、符号名（函数/类名）。"
                        else "Line (12 / :12), hex byte offset (0x1F2A), or symbol name.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = jumpText,
                        onValueChange = { jumpText = it },
                        singleLine = true,
                        label = { Text(if (zh) "行 / 偏移 / 符号" else "Line / offset / symbol", fontSize = AppText.label) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (showOutlineGoto && outline.isNotEmpty()) {
                        Text(if (zh) "符号列表" else "Symbols", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            items(outline.take(120).size) { i ->
                                val it0 = outline.take(120)[i]
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { jumpToLine(it0.line); showJump = false }
                                        .padding(vertical = 5.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TypeChip(it0.kind, AppPalette.indigo)
                                    Text(it0.name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(":${it0.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { performJump(jumpText); showJump = false }) { Text(if (zh) "跳转" else "Go") } },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }

    // ── 语法包管理（语言插件：导入/删除/恢复内置）──
    if (showPackManager) {
        var packJson by remember { mutableStateOf("") }
        var packMsg by remember { mutableStateOf("") }
        val packs = com.soreverse.mcp.core.EditorSyntaxPacks.packs
        AlertDialog(
            onDismissRequest = { showPackManager = false },
            title = { Text(if (zh) "语法包 · 语言插件" else "Syntax Packs", fontSize = AppText.title) },
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
                            Text("✦ ${p.name}", Modifier.weight(1f), fontSize = AppText.body, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            Text(p.extensions.joinToString(" "), fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = {
                                com.soreverse.mcp.core.EditorSyntaxPacks.remove(context, p.id)
                                if (CodeHighlighter.activePack?.id == p.id) CodeHighlighter.activePack = null
                                packVersion++
                            }) { Text(if (zh) "删除" else "Del", color = MaterialTheme.colorScheme.error, fontSize = AppText.label) }
                        }
                    }
                    if (packs.isEmpty()) {
                        Text(if (zh) "（尚未安装语法包）" else "(no packs installed)", fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = packJson,
                        onValueChange = { packJson = it },
                        label = { Text(if (zh) "粘贴语法包 JSON 导入" else "Paste syntax pack JSON to import") },
                        minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = AppText.label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (packMsg.isNotBlank()) Text(packMsg, fontSize = AppText.label, color = MaterialTheme.colorScheme.primary)
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

    // 忙碌反馈（AI / 运行）
    BusyOverlay(visible = aiBusy, message = if (zh) "AI 分析中…" else "AI working…")
}

/** 行首字符偏移（1-based 行号）。 */
private fun lineStartOffset(text: String, line: Int): Int {
    if (line <= 1) return 0
    var idx = 0
    var n = 1
    while (n < line) {
        val nl = text.indexOf('\n', idx)
        if (nl < 0) return text.length
        idx = nl + 1
        n++
    }
    return idx
}

/** 工作区目录展开树 → 扁平列表（供 LazyColumn 渲染）。 */
private fun flattenTree(
    context: android.content.Context,
    rel: String,
    depth: Int,
    expanded: Set<String>,
    out: MutableList<Triple<String, Boolean, Int>>,
) {
    if (depth > 6 || out.size > 600) return
    val ws = WorkspacePolicy.workDirPath(context) ?: return
    val dir = File(ws, rel)
    if (!dir.isDirectory) return
    val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.map { it.name }?.sorted().orEmpty()
    val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty()
    dirs.take(60).forEach { n ->
        val childRel = if (rel.isEmpty()) n else "$rel/$n"
        out.add(Triple(childRel, true, depth))
        if (childRel in expanded) flattenTree(context, childRel, depth + 1, expanded, out)
    }
    files.take(120).forEach { n ->
        val childRel = if (rel.isEmpty()) n else "$rel/$n"
        out.add(Triple(childRel, false, depth))
    }
}
