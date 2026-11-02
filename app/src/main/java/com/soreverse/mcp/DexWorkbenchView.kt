package com.soreverse.mcp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.DexJavaDecompiler
import com.soreverse.mcp.core.DexSmaliAssist
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.runCatchingCancellable
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.engine.DexIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEX 编辑器 —— 对齐 MT 管理器「Dex编辑器++」的四页签形态。
 *
 *   浏览：包 → 类 两级树（点包折叠，点类进编辑器）
 *   最近：本次会话打开过的类
 *   搜索：类/方法/字段/字符串，走本地索引 [DexIndex]（纯 dexlib2，不启动引擎）
 *   常量：字符串常量
 *
 * 编辑器：行号 + smali 语法高亮 + 底部符号栏 + 行操作菜单，右上可切「Java」（jadx 反编译）。
 *
 * 输入既能是 APK 也能是单个 .dex（后端 taffy_smali_edit 两种都支持；.dex 直接原子覆盖，
 * 不需要签名）。文件先复制到 cache 再操作，不动用户的原始文件。
 */
@Composable
internal fun DexWorkbenchView(zh: Boolean, context: Context = LocalContext.current) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var srcFile by remember { mutableStateOf<File?>(null) }
    var srcName by remember { mutableStateOf("") }
    var srcSize by remember { mutableLongStateOf(0L) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    var classes by remember { mutableStateOf<List<String>>(emptyList()) }
    // list_classes 的 total：可能远大于已返回条数（超大 APK 会截断），要如实告诉用户
    var classTotal by remember { mutableStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }

    var opened by rememberSaveable { mutableStateOf("") }
    var methods by remember { mutableStateOf<List<String>>(emptyList()) }
    var editor by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var javaCode by remember { mutableStateOf("") }
    var showJava by remember { mutableStateOf(false) }
    var javaLoading by remember { mutableStateOf(false) }
    var loadingSmali by remember { mutableStateOf(false) }
    var paneError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    // 寄存器分析报告 / 寄存器扩充弹窗 / 指令补全弹窗
    var regReport by remember { mutableStateOf<String?>(null) }
    var expandOpen by remember { mutableStateOf(false) }
    var insertOpen by remember { mutableStateOf(false) }
    // 大文件（>300KB 文本）默认只读浏览：BasicTextField 对几万行每次按键都复制整串，必然卡
    var forceEditBig by remember { mutableStateOf(false) }

    val dirty = opened.isNotEmpty() && !showJava && editor.text != original
    val bigText = editor.text.length > 300_000
    val isDexInput = srcName.endsWith(".dex", true)

    fun loadClasses(f: File) {
        loading = true
        paneError = null
        scope.safeLaunch {
            val r = dexTool(context, "taffy_smali_edit", JSONObject()
                .put("action", "list_classes")
                .put("path", f.absolutePath)
                .put("limit", 12000))
            loading = false
            if (r == null || r.has("error")) {
                paneError = r?.let { it.optString("message").ifBlank { it.optString("error") } }
                    ?: (if (zh) "列举类失败" else "Failed to list classes")
                return@safeLaunch
            }
            val arr = r.optJSONArray("results") ?: JSONArray()
            classTotal = r.optInt("total", arr.length())
            classes = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("className")?.takeIf { it.isNotBlank() }
            }.sorted()
        }
    }

    fun copyToCache(uri: Uri, name: String) {
        fileError = null
        loading = true
        scope.safeLaunch {
            val out = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val dst = File(context.cacheDir, "dexedit_${System.nanoTime()}_$name")
                    var total = 0L
                    val limit = 512L * 1024 * 1024
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > limit) error(if (zh) "文件超过 512 MiB" else "File exceeds 512 MiB")
                                o.write(buf, 0, n)
                            }
                        }
                    } ?: error(if (zh) "无法打开文件" else "Cannot open file")
                    dst to total
                }
            }
            loading = false
            out.getOrNull()?.let { (f, size) ->
                srcFile?.delete()
                srcFile = f
                srcName = name
                srcSize = size
                classes = emptyList()
                expanded = emptySet()
                opened = ""
                showJava = false
                statusMsg = null
                paneError = null
                loadClasses(f)
            } ?: run {
                fileError = out.exceptionOrNull()?.message ?: (if (zh) "读取失败" else "Read failed")
            }
        }
    }

    fun openClass(name: String) {
        val f = srcFile ?: return
        opened = name
        showJava = false
        javaCode = ""
        paneError = null
        statusMsg = null
        loadingSmali = true
        forceEditBig = false
        if (name !in recent) recent = (listOf(name) + recent).take(40)
        scope.safeLaunch {
            val info = dexTool(context, "taffy_smali_edit", JSONObject()
                .put("action", "list_methods")
                .put("path", f.absolutePath)
                .put("className", name))
            val code = dexTool(context, "taffy_smali_edit", JSONObject()
                .put("action", "extract")
                .put("path", f.absolutePath)
                .put("className", name))
            loadingSmali = false
            methods = run {
                val ma = info?.optJSONArray("methods") ?: JSONArray()
                (0 until ma.length()).mapNotNull { i -> ma.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }
            }
            val text = code?.optString("smali").orEmpty()
            if (code == null || code.has("error") || text.isEmpty()) {
                paneError = code?.let { it.optString("message").ifBlank { it.optString("error") } }
                    ?: (if (zh) "提取 smali 失败" else "Failed to extract smali")
                editor = TextFieldValue("")
                original = ""
            } else {
                original = text
                editor = TextFieldValue(text)
            }
            // 预热：点开类之后很可能会看「转成 Java」。延迟 1.2s 再在后台把 DEX 分析完，
            // 既不抢 smali 首屏的 CPU，又能让真正点 Java 时少等一大截。
            scope.safeLaunch {
                delay(1200)
                withContext(Dispatchers.IO) {
                    runCatchingCancellable { DexJavaDecompiler.warmUp(f.absolutePath) }
                }
            }
        }
    }

    fun saveClass() {
        val f = srcFile ?: return
        if (opened.isEmpty() || !dirty) return
        saving = true
        statusMsg = null
        scope.safeLaunch {
            val r = dexTool(context, "taffy_smali_edit", JSONObject()
                .put("action", "replace")
                .put("path", f.absolutePath)
                .put("className", opened)
                .put("smali", editor.text))
            saving = false
            if (r == null || r.has("error")) {
                statusOk = false
                statusMsg = r?.let { it.optString("message").ifBlank { it.optString("error") } }
                    ?: (if (zh) "保存失败" else "Save failed")
                return@safeLaunch
            }
            statusOk = true
            original = editor.text
            // 内容已经变了：之前反编译出来的 Java 与 jadx 里那份已 load 的图全部作废，
            // 否则用户改完 smali 再点「转成 Java」看到的是旧代码（这是最容易被误当成"没保存"的坑）
            javaCode = ""
            runCatching { DexJavaDecompiler.close() }
            val backup = r.optString("backupPath")
            statusMsg = if (zh) {
                val head = if (isDexInput) "已重编并覆盖 .dex" else "已重编并写回 APK（签名已失效，需要重新签名）"
                "$head · ${r.optInt("newDexSize")} 字节" + if (backup.isNotBlank()) "\n备份：$backup" else ""
            } else {
                val head = if (isDexInput) "Recompiled and overwrote the .dex" else "Recompiled and written back (signature invalid)"
                "$head · ${r.optInt("newDexSize")} bytes" + if (backup.isNotBlank()) "\nBackup: $backup" else ""
            }
        }
    }

    fun jumpToMethod(m: String) {
        val lines = editor.text.split('\n')
        val idx = lines.indexOfFirst { it.trimStart().startsWith(".method") && it.contains(m) }
        if (idx < 0) return
        var offset = 0
        for (i in 0 until idx) offset += lines[i].length + 1
        editor = editor.copy(selection = TextRange(offset))
    }

    fun decompileJava() {
        val f = srcFile ?: return
        if (opened.isEmpty()) return
        showJava = true
        if (javaCode.isNotEmpty()) return
        javaLoading = true
        paneError = null
        scope.safeLaunch {
            val r = dexTool(context, "taffy_dex_to_java", JSONObject()
                .put("action", "class")
                .put("path", f.absolutePath)
                .put("className", opened))
            javaLoading = false
            if (r == null || r.has("error")) {
                paneError = r?.let { it.optString("message").ifBlank { it.optString("error") } }
                    ?: (if (zh) "反编译失败" else "Decompile failed")
                showJava = false
                return@safeLaunch
            }
            javaCode = r.optJSONObject("result")?.optString("code")?.takeIf { it.isNotBlank() }
                ?: r.optString("code")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "input.apk"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                }
            }
        }
        copyToCache(uri, name)
    }

    Column(Modifier.fillMaxSize()) {
        // ── 顶栏：← 标题 / 副标题 + 保存 + 更多 ──
        DexTopBar(
            zh = zh,
            title = if (opened.isEmpty()) (if (zh) "DEX 编辑器" else "DEX editor") else opened.substringAfterLast('.'),
            subtitle = when {
                srcName.isBlank() -> if (zh) "未选择文件" else "no file"
                opened.isNotEmpty() -> srcName
                else -> "$srcName · ${classes.size} " + (if (zh) "个类" else "classes")
            },
            onBack = if (opened.isEmpty()) null else ({ opened = ""; showJava = false }),
            dirty = dirty,
            saving = saving,
            onSave = { saveClass() },
            actions = { isOpen, closeMenu ->
                DexEditorMenu(
                    zh = zh,
                    expanded = isOpen,
                    hasFile = srcFile != null,
                    inEditor = opened.isNotEmpty(),
                    showJava = showJava,
                    onDismiss = closeMenu,
                    onPickFile = { picker.launch(arrayOf("*/*")) },
                    onReloadClasses = { srcFile?.let { loadClasses(it) } },
                    onToggleJava = { if (showJava) showJava = false else decompileJava() },
                    onCopyAll = {
                        clip.setText(AnnotatedString(if (showJava) javaCode else editor.text))
                        statusOk = true
                        statusMsg = if (zh) "已复制" else "Copied"
                    },
                    onRevert = { editor = TextFieldValue(original) },
                    onLineOp = { op ->
                        val r = applyLineOp(editor, op)
                        if (r != null) editor = r
                    },
                )
            },
        )

        if (srcName.isBlank()) {
            DexEmpty(
                title = if (zh) "选一个 APK 或 .dex" else "Pick an APK or .dex",
                hint = if (zh) {
                    "支持 APK（自动找 classes*.dex）与单个 .dex。文件会先复制到缓存再改，原始文件不动。"
                } else {
                    "APK (auto-locates classes*.dex) or a single .dex. The file is copied to cache before editing."
                },
            ) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(if (zh) "选择文件" else "Pick file", fontSize = AppText.body)
                }
            }
            return@Column
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        if (opened.isEmpty()) {
            // ── 四页签 ──
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                listOf(
                    if (zh) "浏览" else "Browse",
                    if (zh) "最近" else "Recent",
                    if (zh) "搜索" else "Search",
                    if (zh) "常量" else "Const",
                ).forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = AppText.body) },
                    )
                }
            }
            paneError?.let {
                Text(
                    it,
                    fontSize = AppText.body,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (tab) {
                0 -> BrowsePane(
                    zh = zh,
                    classes = classes,
                    classTotal = classTotal,
                    filter = filter,
                    onFilter = { filter = it },
                    expanded = expanded,
                    onToggle = { p -> expanded = if (p in expanded) expanded - p else expanded + p },
                    onOpen = { openClass(it) },
                )
                1 -> RecentPane(zh = zh, recent = recent, onOpen = { openClass(it) })
                2 -> SearchPane(zh = zh, path = srcFile!!.absolutePath, tmpDir = File(context.cacheDir, "dexidx")) {
                    if (it.isNotBlank()) openClass(it)
                }
                else -> ConstPane(
                    zh = zh,
                    path = srcFile!!.absolutePath,
                    tmpDir = File(context.cacheDir, "dexidx"),
                    onOpenClass = { openClass(it) },
                )
            }
            return@Column
        }

        // ── 编辑器 ──
        EditorPane(
            zh = zh,
            className = opened,
            methods = methods,
            editor = editor,
            onEditorChange = { editor = it },
            javaCode = javaCode,
            showJava = showJava,
            javaLoading = javaLoading,
            loading = loadingSmali,
            error = paneError,
            statusMsg = statusMsg,
            statusOk = statusOk,
            saving = saving,
            dirty = dirty,
            onBack = { opened = ""; showJava = false },
            onToggleJava = { if (showJava) showJava = false else decompileJava() },
            onJump = { jumpToMethod(it) },
            onRevert = { editor = TextFieldValue(original) },
            onSave = { saveClass() },
            bigReadOnly = bigText && !forceEditBig,
            onEnableEdit = { forceEditBig = true },
            onInsertOpen = { insertOpen = true },
            onRegAnalysis = { regReport = DexSmaliAssist.report(editor.text) },
            onExpandRegs = { expandOpen = true },
        )

        regReport?.let { txt ->
            AlertDialog(
                onDismissRequest = { regReport = null },
                title = { Text(if (zh) "寄存器分析" else "Register analysis", fontSize = AppText.title) },
                text = {
                    Text(
                        txt,
                        fontSize = AppText.label,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { regReport = null }) {
                        Text(if (zh) "关闭" else "Close", fontSize = AppText.body)
                    }
                },
            )
        }

        if (expandOpen) {
            ExpandRegsDialog(
                zh = zh,
                onConfirm = { delta ->
                    val r = DexSmaliAssist.expandRegisters(editor.text, editor.selection.start, delta)
                    expandOpen = false
                    if (r == null) {
                        statusOk = false
                        statusMsg = if (zh) "扩充失败：没有找到 .method 段落" else "Expand failed: no .method block"
                    } else {
                        editor = TextFieldValue(r.first, editor.selection)
                        statusOk = true
                        statusMsg = r.second
                    }
                },
                onDismiss = { expandOpen = false },
            )
        }

        if (insertOpen) {
            InsertInstructionDialog(
                zh = zh,
                onInsert = { snippet ->
                    insertOpen = false
                    val sel = editor.selection
                    val text = editor.text
                    val start = sel.start.coerceIn(0, text.length)
                    val end = sel.end.coerceIn(0, text.length)
                    editor = TextFieldValue(
                        text.substring(0, start) + snippet + text.substring(end),
                        TextRange(start + snippet.length),
                    )
                },
                onDismiss = { insertOpen = false },
            )
        }
    }
}

// ── 顶栏 ──

@Composable
private fun DexTopBar(
    zh: Boolean,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    dirty: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    actions: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = AppText.title,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    fontSize = AppText.label,
                    fontFamily = if (subtitle.contains('.')) FontFamily.Monospace else FontFamily.Default,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (dirty) {
                IconButton(onClick = onSave, enabled = !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Save, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions(menu) { menu = false }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
    }
}

@Composable
private fun DexEditorMenu(
    zh: Boolean,
    expanded: Boolean,
    hasFile: Boolean,
    inEditor: Boolean,
    showJava: Boolean,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onReloadClasses: () -> Unit,
    onToggleJava: () -> Unit,
    onCopyAll: () -> Unit,
    onRevert: () -> Unit,
    onLineOp: (LineOp) -> Unit,
) {
    // 菜单项在 expanded 为 false 时也要渲染（DropdownMenu 内部会控制可见性），
    // 所以这里不按 expanded 提前 return。
    if (!expanded) return
    DropdownMenuItem(
        text = { Text(if (zh) "选择文件…" else "Pick file…", fontSize = AppText.body) },
        onClick = { onDismiss(); onPickFile() },
    )
    DropdownMenuItem(
        text = { Text(if (zh) "重新列类" else "Reload classes", fontSize = AppText.body) },
        onClick = { onDismiss(); onReloadClasses() },
        enabled = hasFile,
    )
    if (inEditor) {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (showJava) (if (zh) "看 smali" else "Show smali") else (if (zh) "转成 Java" else "To Java"), fontSize = AppText.body) },
            onClick = { onDismiss(); onToggleJava() },
        )
        DropdownMenuItem(
            text = { Text(if (zh) "复制全部" else "Copy all", fontSize = AppText.body) },
            onClick = { onDismiss(); onCopyAll() },
        )
        DropdownMenuItem(
            text = { Text(if (zh) "还原改动" else "Revert", fontSize = AppText.body) },
            onClick = { onDismiss(); onRevert() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (zh) "复制当前行" else "Duplicate line", fontSize = AppText.body) },
            onClick = { onDismiss(); onLineOp(LineOp.Duplicate) },
        )
        DropdownMenuItem(
            text = { Text(if (zh) "删除当前行" else "Delete line", fontSize = AppText.body) },
            onClick = { onDismiss(); onLineOp(LineOp.Delete) },
        )
        DropdownMenuItem(
            text = { Text(if (zh) "切换注释" else "Toggle comment", fontSize = AppText.body) },
            onClick = { onDismiss(); onLineOp(LineOp.Comment) },
        )
        DropdownMenuItem(
            text = { Text(if (zh) "增加缩进" else "Indent", fontSize = AppText.body) },
            onClick = { onDismiss(); onLineOp(LineOp.Indent) },
        )
        DropdownMenuItem(
            text = { Text(if (zh) "减少缩进" else "Outdent", fontSize = AppText.body) },
            onClick = { onDismiss(); onLineOp(LineOp.Outdent) },
        )
    }
}

// ── 浏览：包树 ──

private class PkgNode(val label: String, val path: String) {
    val children = sortedMapOf<String, PkgNode>()
    val classes = sortedSetOf<String>()

    /** 子树（含自己）的类数，构建时累加 —— 逐层递归统计在几千个类时是 O(n²)。 */
    var total = 0
}

private data class BrowseRow(
    val label: String,
    val path: String,
    val depth: Int,
    val isClass: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
    val count: Int,
)

private fun buildRows(classes: List<String>, expandedSet: Set<String>, filter: String): List<BrowseRow> {
    val root = PkgNode("", "")
    val list = if (filter.isBlank()) classes else {
        val q = filter.trim().lowercase()
        classes.filter { it.lowercase().contains(q) }
    }
    for (full in list) {
        val parts = full.split('.')
        var node = root
        root.total++
        for (i in 0 until parts.size - 1) {
            val seg = parts[i]
            val child = node.children.getOrPut(seg) {
                PkgNode(seg, if (node.path.isEmpty()) seg else node.path + "." + seg)
            }
            node = child
            child.total++
        }
        node.classes.add(full)
    }
    val rows = ArrayList<BrowseRow>()
    fun walk(node: PkgNode, depth: Int) {
        for ((_, child) in node.children) {
            val isOpen = child.path in expandedSet || filter.isNotBlank()
            rows.add(BrowseRow(child.label, child.path, depth, false, true, isOpen, child.total))
            if (isOpen) walk(child, depth + 1)
        }
        for (cls in node.classes) {
            rows.add(BrowseRow(cls.substringAfterLast('.'), cls, depth, true, false, false, 0))
        }
    }
    walk(root, 0)
    return rows
}

@Composable
private fun BrowsePane(
    zh: Boolean,
    classes: List<String>,
    classTotal: Int,
    filter: String,
    onFilter: (String) -> Unit,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val rows = remember(classes, expanded, filter) { buildRows(classes, expanded, filter) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            DexField(
                value = filter,
                onChange = onFilter,
                placeholder = if (zh) "过滤类名（如 activity）" else "Filter classes",
            )
        }
        if (classTotal > classes.size) {
            Text(
                if (zh) "已加载 ${classes.size} / 共 $classTotal 个类（超出部分未加载，用过滤框缩小范围）"
                else "Loaded ${classes.size} of $classTotal classes — narrow it down with the filter",
                fontSize = AppText.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (classes.isEmpty()) {
            DexEmpty(
                title = if (zh) "没有类" else "No classes",
                hint = if (zh) "这个文件里没解析出类，可能不是有效的 dex/apk" else "No classes parsed",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { (if (it.isClass) "c:" else "p:") + it.path }) { row ->
                    BrowseRowItem(row, zh, onToggle, onOpen)
                }
            }
        }
    }
}

@Composable
private fun BrowseRowItem(row: BrowseRow, zh: Boolean, onToggle: (String) -> Unit, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (row.isClass) onOpen(row.path) else onToggle(row.path) }
            .padding(start = (12 + row.depth * 16).dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.expandable) {
            Text(
                if (row.expanded) "▾" else "▸",
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
                modifier = Modifier.width(16.dp),
            )
            Icon(
                Icons.Filled.Folder, null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("C", fontSize = 9.sp, color = cs.primary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.label,
            fontSize = AppText.body,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!row.isClass && row.count > 0) {
            Text("${row.count}", fontSize = AppText.label, color = cs.onSurfaceVariant)
        }
    }
}

// ── 最近 ──

@Composable
private fun RecentPane(zh: Boolean, recent: List<String>, onOpen: (String) -> Unit) {
    if (recent.isEmpty()) {
        DexEmpty(
            title = if (zh) "还没有打开过类" else "Nothing opened yet",
            hint = if (zh) "从「浏览」里点一个类，它会出现在这里" else "Open a class from Browse",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(recent, key = { it }) { name ->
            SimpleClassRow(name, onOpen)
        }
    }
}

@Composable
private fun SimpleClassRow(name: String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(name) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = AppText.body,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── 搜索 ──

private val searchTypes = listOf(
    "class" to "类",
    "method" to "方法",
    "field" to "字段",
    "string" to "字符串",
)

@Composable
private fun SearchPane(
    zh: Boolean,
    path: String,
    tmpDir: File,
    onOpenClass: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf("class") }
    var keyword by rememberSaveable { mutableStateOf("") }
    // 之前 placeholder 写着"支持 . * 前缀后缀"，但没开 regex 时 . 和 * 只是普通字符，
    // 用户输 *Activity 会搜字面量 —— 现在给一个明确的开关，文案也不再骗人
    var useRegex by rememberSaveable { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<DexIndex.Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    fun run() {
        if (keyword.isBlank()) return
        busy = true
        error = null
        scope.safeLaunch {
            val r = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    DexIndex.search(
                        path = path,
                        type = type,
                        keyword = keyword.trim(),
                        regex = useRegex,
                        limit = 300,
                        tmpDir = tmpDir,
                    )
                }.getOrNull()
            }
            busy = false
            searched = true
            if (r == null) error = if (zh) "索引失败" else "Index failed"
            else if (r.error != null) error = r.error
            else hits = r.hits
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                DexField(
                    value = keyword,
                    onChange = { keyword = it },
                    placeholder = if (zh) (if (useRegex) "正则表达式" else "关键字（包含匹配）") else if (useRegex) "Regex" else "Keyword",
                )
            }
            OutlinedButton(onClick = { run() }, enabled = keyword.isNotBlank() && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(if (zh) "搜索" else "Go", fontSize = AppText.body)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            searchTypes.forEach { (k, label) ->
                val sel = type == k
                Row(
                    Modifier
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(AppShape.pill))
                        .background(if (sel) cs.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (sel) Color.Transparent else cs.outlineVariant, RoundedCornerShape(AppShape.pill))
                        .clickable {
                            if (type != k) {
                                type = k
                                hits = emptyList()
                                searched = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (zh) label else k,
                        fontSize = AppText.label,
                        color = if (sel) cs.onSecondaryContainer else cs.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier
                    .heightIn(min = 30.dp)
                    .clip(RoundedCornerShape(AppShape.pill))
                    .background(if (useRegex) cs.secondaryContainer else Color.Transparent)
                    .border(
                        1.dp,
                        if (useRegex) Color.Transparent else cs.outlineVariant,
                        RoundedCornerShape(AppShape.pill),
                    )
                    .clickable {
                        useRegex = !useRegex
                        hits = emptyList()
                        searched = false
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    ".*",
                    fontSize = AppText.label,
                    fontFamily = FontFamily.Monospace,
                    color = if (useRegex) cs.onSecondaryContainer else cs.onSurfaceVariant,
                )
            }
        }
        error?.let {
            Text(it, fontSize = AppText.body, color = cs.error, modifier = Modifier.padding(12.dp))
        }
        if (busy) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (!busy && searched && hits.isEmpty() && error == null) {
            DexEmpty(
                title = if (zh) "没有匹配" else "No match",
                hint = if (zh) "换个关键字或类型再试" else "Try another keyword",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits, key = { it.kind + it.className + it.member + it.detail }) { h ->
                val cs2 = MaterialTheme.colorScheme
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenClass(h.className) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                            .background(cs2.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (h.kind) {
                                "class" -> "C"
                                "method" -> "M"
                                "field" -> "F"
                                "string" -> "S"
                                else -> "·"
                            },
                            fontSize = 9.sp,
                            color = cs2.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (h.member.isNotBlank()) h.member else h.className.substringAfterLast('.'),
                            fontSize = AppText.body,
                            fontFamily = FontFamily.Monospace,
                            color = cs2.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (h.detail.isNotBlank()) h.detail else h.className,
                            fontSize = AppText.label,
                            fontFamily = FontFamily.Monospace,
                            color = cs2.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── 常量 ──

@Composable
private fun ConstPane(zh: Boolean, path: String, tmpDir: File, onOpenClass: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var kind by rememberSaveable { mutableStateOf("string") }
    var keyword by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<DexIndex.Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    fun run() {
        busy = true
        error = null
        scope.safeLaunch {
            // 空关键字 = 列全部：用 .* 走正则匹配，不必给索引层加"允许空"的特例
            val raw = keyword.trim()
            val useRegex = raw.isEmpty()
            val q = if (useRegex) ".*" else raw
            val r = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    DexIndex.search(path = path, type = kind, keyword = q, regex = useRegex, limit = 800, tmpDir = tmpDir)
                }.getOrNull()
            }
            busy = false
            searched = true
            if (r == null) error = if (zh) "索引失败" else "Index failed"
            else if (r.error != null) error = r.error
            else hits = r.hits
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                DexField(
                    value = keyword,
                    onChange = { keyword = it },
                    placeholder = if (zh) "字符串常量关键字" else "String constant",
                )
            }
            OutlinedButton(onClick = { run() }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(if (zh) (if (keyword.isBlank()) "列全部" else "查找") else (if (keyword.isBlank()) "All" else "Find"), fontSize = AppText.body)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("string" to (if (zh) "字符串" else "String"), "int" to (if (zh) "整数" else "Int")).forEach { (k, label) ->
                val sel = kind == k
                Row(
                    Modifier
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(AppShape.pill))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (sel) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppShape.pill))
                        .clickable {
                            if (kind != k) {
                                kind = k
                                hits = emptyList()
                                searched = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        label,
                        fontSize = AppText.label,
                        color = if (sel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        error?.let {
            Text(it, fontSize = AppText.body, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        if (!busy && searched && hits.isEmpty() && error == null) {
            DexEmpty(
                title = if (zh) "没有匹配的常量" else "No constants",
                hint = if (zh) "换关键字再试，或留空点「列全部」" else "Try another keyword or list all",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // 不要用 className+detail 当 key：列全部整数时同一类里同一个值会出现多次，
            // Compose 遇到重复 key 会抛 IllegalArgumentException 直接崩界面
            items(hits) { h ->
                Row(
                    // 点一行是「去它所在的类」—— 常量页是用来找用法的，不是复制板；
                    // 要复制值点右侧图标（原来点整行只复制，跳不过去）
                    Modifier.fillMaxWidth().clickable {
                        if (h.className.isNotBlank()) onOpenClass(h.className)
                        else clip.setText(AnnotatedString(h.detail.ifBlank { h.member }))
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            h.detail.ifBlank { h.member },
                            fontSize = AppText.body,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (h.className.isNotBlank()) {
                            Text(
                                h.className,
                                fontSize = AppText.label,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.ContentCopy, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(AppShape.sm))
                            .clickable { clip.setText(AnnotatedString(h.detail.ifBlank { h.member })) }
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

// ── 编辑器 ──

@Composable
private fun EditorPane(
    zh: Boolean,
    className: String,
    methods: List<String>,
    editor: TextFieldValue,
    onEditorChange: (TextFieldValue) -> Unit,
    javaCode: String,
    showJava: Boolean,
    javaLoading: Boolean,
    loading: Boolean,
    error: String?,
    statusMsg: String?,
    statusOk: Boolean,
    saving: Boolean,
    dirty: Boolean,
    onBack: () -> Unit,
    onToggleJava: () -> Unit,
    onJump: (String) -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    bigReadOnly: Boolean = false,
    onEnableEdit: () -> Unit = {},
    onInsertOpen: () -> Unit = {},
    onRegAnalysis: () -> Unit = {},
    onExpandRegs: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val lines = editor.text.count { it == '\n' } + 1
            Text(
                (if (zh) "$lines 行" else "$lines lines") + if (lines > 8000) (if (zh) " · 文件较大，已关闭高亮" else " · large file, highlight off") else "",
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // smali / Java 切换
            Row(
                Modifier
                    .clip(RoundedCornerShape(AppShape.pill))
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.pill)),
            ) {
                listOf("smali", "Java").forEach { label ->
                    val sel = (label == "Java") == showJava
                    Text(
                        label,
                        fontSize = AppText.label,
                        color = if (sel) cs.onSecondaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppShape.pill))
                            .background(if (sel) cs.secondaryContainer else Color.Transparent)
                            .clickable { if (sel != showJava) onToggleJava() }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (methods.isNotEmpty() && !showJava) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(methods.size) { i ->
                    val m = methods[i]
                    Row(
                        Modifier
                            .heightIn(min = 28.dp)
                            .clip(RoundedCornerShape(AppShape.pill))
                            .background(cs.surfaceVariant)
                            .clickable { onJump(m) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(m, fontSize = AppText.label, fontFamily = FontFamily.Monospace, color = cs.onSurface, maxLines = 1)
                    }
                }
            }
        }

        if (!showJava) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onInsertOpen, enabled = !bigReadOnly) {
                    Text(if (zh) "指令补全" else "Insert", fontSize = AppText.label, color = cs.primary)
                }
                TextButton(onClick = onRegAnalysis) {
                    Text(if (zh) "寄存器分析" else "Registers", fontSize = AppText.label, color = cs.primary)
                }
                TextButton(onClick = onExpandRegs, enabled = !bigReadOnly) {
                    Text(if (zh) "寄存器扩充" else "Expand regs", fontSize = AppText.label, color = cs.primary)
                }
            }
        }

        when {
            loading || javaLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            error != null -> DexEmpty(title = if (zh) "打不开" else "Cannot open", hint = error)
            showJava -> Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(AppShape.sm))
                    .background(cs.surfaceVariant)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.sm))
                    .padding(10.dp),
            ) {
                Text(
                    javaCode,
                    fontSize = AppText.body,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
            bigReadOnly -> LargeSmaliViewer(
                zh = zh,
                text = editor.text,
                onEnableEdit = onEnableEdit,
                modifier = Modifier.weight(1f),
            )
            else -> CodeArea(editor, onEditorChange, Modifier.weight(1f))
        }

        if (!showJava) SymbolBar(onInsert = { s ->
            val sel = editor.selection
            val text = editor.text
            val start = sel.start.coerceIn(0, text.length)
            val end = sel.end.coerceIn(0, text.length)
            val newText = text.substring(0, start) + s + text.substring(end)
            onEditorChange(TextFieldValue(newText, TextRange(start + s.length)))
        })

        if (statusMsg != null) {
            Text(
                statusMsg,
                fontSize = AppText.label,
                lineHeight = 15.sp,
                color = if (statusOk) cs.onSurfaceVariant else cs.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showJava) (if (zh) "jadx 反编译 · 只读" else "jadx · read-only")
                else if (dirty) (if (zh) "未保存" else "Unsaved") else (if (zh) "无改动" else "No changes"),
                fontSize = AppText.label,
                color = if (dirty && !showJava) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRevert, enabled = dirty && !saving) {
                Text(if (zh) "还原" else "Revert", fontSize = AppText.body)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = onSave, enabled = dirty && !saving) {
                if (saving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else {
                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (zh) "保存并重编" else "Save", fontSize = AppText.body, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 行号 + 语法高亮的编辑区：行号列与正文共用一个 ScrollState，行高严格一致。 */
@Composable
private fun CodeArea(
    editor: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val lineCount = remember(editor.text) { editor.text.count { it == '\n' } + 1 }
    // 几万行的 smali 每帧重算高亮会直接卡死；超过阈值就退回纯文本（编辑体验优先）
    val tooLargeForHighlight = lineCount > 8000
    val shown = remember(editor.text, tooLargeForHighlight) {
        if (tooLargeForHighlight) AnnotatedString(editor.text) else highlightSmali(editor.text, cs)
    }
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.sm)),
    ) {
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text(
                numbers,
                fontSize = AppText.body,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.width(38.dp).padding(top = 10.dp, bottom = 10.dp, start = 2.dp, end = 6.dp),
            )
            BasicTextField(
                value = editor,
                onValueChange = onChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = AppText.body,
                    lineHeight = 18.sp,
                    color = cs.onSurface,
                ),
                cursorBrush = SolidColor(cs.primary),
                // 用上面 remember 好的结果：visualTransformation 每帧都会被调用，
                // 在里面重新算一遍高亮是典型的"打字卡顿"来源。
                visualTransformation = { TransformedText(shown, OffsetMapping.Identity) },
                modifier = Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            )
        }
    }
}

/** smali 语法高亮：指令关键字 / 类型描述符 / 字符串 / 注释 / 数字。 */
private fun highlightSmali(text: String, cs: androidx.compose.material3.ColorScheme): AnnotatedString = buildAnnotatedString {
    append(text)
    val kw = cs.primary
    val typeColor = Color(0xFF7BC47F)
    val strColor = Color(0xFFE6A96B)
    val commentColor = cs.onSurfaceVariant.copy(alpha = 0.7f)
    val numColor = Color(0xFFC79BE8)

    var lineStart = 0
    while (lineStart <= text.length) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trimStart()

        if (trimmed.startsWith("#")) {
            addStyle(SpanStyle(color = commentColor), lineStart, lineEnd)
        } else if (trimmed.startsWith(".")) {
            val dot = lineStart + (line.length - trimmed.length)
            val endOfDirective = dot + 1 + trimmed.drop(1).takeWhile { !it.isWhitespace() }.length
            addStyle(SpanStyle(color = kw, fontWeight = FontWeight.Medium), dot, endOfDirective)
            // 修饰符
            listOf("public", "private", "protected", "static", "final", "abstract", "interface", "synthetic", "constructor").forEach { w ->
                var idx = line.indexOf(w, (endOfDirective - lineStart))
                while (idx >= 0) {
                    addStyle(SpanStyle(color = numColor), lineStart + idx, lineStart + idx + w.length)
                    idx = line.indexOf(w, idx + w.length)
                }
            }
        }
        // 类型描述符 Lxxx/yyy;
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == 'L' && (i == 0 || !line[i - 1].isLetterOrDigit())) {
                val semi = line.indexOf(';', i)
                if (semi > i && semi - i < 200 && line.substring(i + 1, semi).contains('/')) {
                    addStyle(SpanStyle(color = typeColor), lineStart + i, lineStart + semi + 1)
                    i = semi
                }
            } else if (c == '"') {
                val close = line.indexOf('"', i + 1)
                if (close > i) {
                    addStyle(SpanStyle(color = strColor), lineStart + i, lineStart + close + 1)
                    i = close
                }
            } else if (c == '#') {
                addStyle(SpanStyle(color = commentColor), lineStart + i, lineEnd)
                i = lineEnd
            }
            i++
        }
        lineStart = lineEnd + 1
    }
}

/** 底部符号栏：常用 smali 标点，免得为了一个分号切键盘。 */
@Composable
private fun SymbolBar(onInsert: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val symbols = listOf("→", "/", "+", "-", "*", "=", "<", ">", ";", "\"", "'", "(", ")", "{", "}", ",")
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(symbols, key = { it }) { s ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(AppShape.xs))
                    .background(cs.surfaceVariant)
                    .clickable { onInsert(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(s, fontSize = AppText.body, fontFamily = FontFamily.Monospace, color = cs.onSurface)
            }
        }
    }
}

// ── 行操作 ──

private enum class LineOp { Duplicate, Delete, Comment, Indent, Outdent }

private fun applyLineOp(v: TextFieldValue, op: LineOp): TextFieldValue? {
    val text = v.text
    val caret = v.selection.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEndIdx = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
    // 光标在行首且行非空时，lastIndexOf 会落到上一行，这里修正一次
    val realStart = if (caret == lineStart && lineStart > 0 && text.getOrNull(lineStart - 1) == '\n') lineStart else lineStart
    val line = text.substring(realStart, lineEndIdx)
    return when (op) {
        LineOp.Duplicate -> {
            val newText = text.substring(0, lineEndIdx) + "\n" + line + text.substring(lineEndIdx)
            TextFieldValue(newText, TextRange(lineEndIdx + 1 + line.length))
        }
        LineOp.Delete -> {
            val from = if (realStart > 0) realStart - 1 else 0
            val to = lineEndIdx
            val newText = text.removeRange(from, to)
            TextFieldValue(newText, TextRange(from.coerceIn(0, newText.length)))
        }
        LineOp.Comment -> {
            val isCommented = line.trimStart().startsWith("#")
            val newLine = if (isCommented) line.replaceFirst("# ", "").replaceFirst("#", "")
            else "# " + line
            val newText = text.substring(0, realStart) + newLine + text.substring(lineEndIdx)
            TextFieldValue(newText, TextRange(realStart + newLine.length))
        }
        LineOp.Indent -> {
            val newText = text.substring(0, realStart) + "    " + text.substring(realStart)
            TextFieldValue(newText, TextRange(caret + 4))
        }
        LineOp.Outdent -> {
            val stripped = line.removePrefix("    ").removePrefix("\t")
            val newText = text.substring(0, realStart) + stripped + text.substring(lineEndIdx)
            TextFieldValue(newText, TextRange((caret - (line.length - stripped.length)).coerceIn(0, newText.length)))
        }
    }
}

// ── 通用小件 ──

@Composable
private fun DexField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.sm))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = AppText.body, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = AppText.body, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DexEmpty(title: String, hint: String, action: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Description, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = AppText.bodyStrong, color = cs.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(hint, fontSize = AppText.body, lineHeight = 18.sp, color = cs.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

private suspend fun dexTool(context: Context, name: String, args: JSONObject): JSONObject? =
    withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val handler = com.soreverse.mcp.mcp.ToolCatalog.byName[name]
                ?: return@runCatchingCancellable null
            val tcx = com.soreverse.mcp.mcp.ToolContext(
                context,
                SettingsStore(context),
                EngineProvider.get(context),
                null,
            )
            handler.handle(tcx, args)
        }.getOrNull()
    }

/**
 * 大文件的只读浏览：LazyColumn 虚拟化，几万行也只组合可见的几十行。
 * 编辑仍然回退到 BasicTextField —— 那是 Compose 的固有成本，只能让用户显式选择。
 */
@Composable
private fun LargeSmaliViewer(
    zh: Boolean,
    text: String,
    onEnableEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val lines = remember(text) { text.split('\n') }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.sm)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (zh) "${lines.size} 行 · 只读浏览（长行会被裁切，需要横向看请进编辑）"
                else "${lines.size} lines · read-only (long lines clipped)",
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnableEdit) {
                Text(if (zh) "进入编辑" else "Edit", fontSize = AppText.label, color = cs.primary)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(lines) { idx, line ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        (idx + 1).toString(),
                        fontSize = AppText.label,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.width(44.dp).padding(end = 6.dp, top = 5.dp, bottom = 5.dp),
                    )
                    Text(
                        line.ifEmpty { " " },
                        fontSize = AppText.body,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f).padding(top = 5.dp, bottom = 5.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

/** 指令补全：按分类列出常用 smali 指令模板，点一下插到光标处。 */
@Composable
private fun InsertInstructionDialog(zh: Boolean, onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (zh) "指令补全" else "Insert instruction", fontSize = AppText.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                DexSmaliAssist.templates.forEach { (group, rows) ->
                    item(key = "g-" + group) {
                        Text(
                            group,
                            fontSize = AppText.label,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(rows.size) { i ->
                        val row = rows[i]
                        Text(
                            row.first,
                            fontSize = AppText.body,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppShape.xs))
                                .clickable { onInsert(row.second) }
                                .padding(horizontal = 4.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (zh) "关闭" else "Close", fontSize = AppText.body) }
        },
    )
}

/** 寄存器扩充：只允许增大。增大不会改变 p 寄存器语义，所以是安全的。 */
@Composable
private fun ExpandRegsDialog(zh: Boolean, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var deltaText by remember { mutableStateOf("8") }
    val delta = deltaText.trim().toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (zh) "寄存器扩充" else "Expand registers", fontSize = AppText.title) },
        text = {
            Column {
                Text(
                    if (zh) "给光标所在方法增加寄存器数量（.registers / .locals）。只能加大 —— 加大不改变 p 寄存器的映射，减小会把参数错位。"
                    else "Add registers to the method at the caret. Increase only: shrinking breaks p-register mapping.",
                    fontSize = AppText.label,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DexField(
                    value = deltaText,
                    onChange = { deltaText = it.filter { c -> c.isDigit() }.take(3) },
                    placeholder = if (zh) "增加数量（如 8）" else "How many",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (delta > 0) onConfirm(delta) }, enabled = delta > 0) {
                Text(if (zh) "扩充" else "Expand", fontSize = AppText.body)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (zh) "取消" else "Cancel", fontSize = AppText.body) }
        },
    )
}
