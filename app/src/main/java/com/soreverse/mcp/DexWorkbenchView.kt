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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.core.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEX 工作台 —— 分析页里的 DEX 一级入口。
 *
 * 之前 DEX 只有「DEX 查询」一个视图（[DexIndexView]，纯检索：类/方法/字符串搜出来复制走），
 * 而且它只能从顶栏「⋮ → 更多视图」进去，侧栏里根本没有。所以"整合到分析页"在真机上
 * 表现为：看不到入口、也改不了任何东西。
 *
 * 这一版把三件事接成一个闭环：
 *   1. 载入 APK（复制到 cache，避免直接改用户原文件）
 *   2. 按包名拉类列表 → 点类 → 拉方法/字段 + 完整 smali
 *   3. 改 smali → 保存（taffy_smali_edit replace：自动定位 DEX → 重编 → 写回 APK 并留 .bak）
 *
 * 全部走已有的 MCP 工具，不另造引擎；签名失效这件事在保存成功后直接写进提示里。
 */
@Composable
internal fun DexWorkbenchView(zh: Boolean, context: Context = LocalContext.current) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var srcFile by remember { mutableStateOf<File?>(null) }
    var srcName by remember { mutableStateOf("") }
    var srcSize by remember { mutableLongStateOf(0L) }
    var fileError by remember { mutableStateOf<String?>(null) }

    var pkgPrefix by rememberSaveable { mutableStateOf("") }
    var classes by remember { mutableStateOf<List<DexClass>>(emptyList()) }
    var loadingClasses by remember { mutableStateOf(false) }
    var classError by remember { mutableStateOf<String?>(null) }
    var classQuery by rememberSaveable { mutableStateOf("") }

    var openedClass by rememberSaveable { mutableStateOf("") }
    var methods by remember { mutableStateOf<List<DexMethod>>(emptyList()) }
    var fields by remember { mutableStateOf<List<DexField>>(emptyList()) }
    var superClass by rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var loadingSmali by remember { mutableStateOf(false) }
    var smaliError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    var saveOk by remember { mutableStateOf(false) }

    val dirty = openedClass.isNotEmpty() && editor.text != original

    fun copyToCache(uri: Uri, name: String) {
        fileError = null
        scope.safeLaunch {
            val out = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val dst = File(context.cacheDir, "dexbench_${System.nanoTime()}.apk")
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
            out.getOrNull()?.let { (f, size) ->
                srcFile?.delete()
                srcFile = f
                srcName = name
                srcSize = size
                classes = emptyList()
                openedClass = ""
                classError = null
                saveMsg = null
            } ?: run {
                fileError = out.exceptionOrNull()?.message ?: (if (zh) "读取失败" else "Read failed")
            }
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

    fun loadClasses() {
        val f = srcFile ?: return
        loadingClasses = true
        classError = null
        scope.safeLaunch {
            val r = dexTool(context, "taffy_smali_edit", JSONObject()
                .put("action", "list_classes")
                .put("path", f.absolutePath)
                .put("packagePrefix", pkgPrefix.trim())
                .put("limit", 2000))
            loadingClasses = false
            if (r == null) {
                classError = if (zh) "工具不可用（taffy_smali_edit 未注册）" else "Tool unavailable"
                return@safeLaunch
            }
            if (r.optBoolean("ok") == false || r.has("error")) {
                classError = r.optString("message").ifBlank { r.optString("error") }
                    .ifBlank { if (zh) "列举失败" else "Failed to list classes" }
                return@safeLaunch
            }
            val arr = r.optJSONArray("results") ?: JSONArray()
            classes = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("className")
                if (n.isBlank()) null else DexClass(n, o.optString("dex"))
            }
        }
    }

    fun openClass(name: String) {
        val f = srcFile ?: return
        openedClass = name
        methods = emptyList()
        fields = emptyList()
        superClass = ""
        smaliError = null
        saveMsg = null
        loadingSmali = true
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
            if (info != null && !info.has("error")) {
                superClass = info.optString("superClass")
                val ma = info.optJSONArray("methods") ?: JSONArray()
                methods = (0 until ma.length()).mapNotNull { i ->
                    val o = ma.optJSONObject(i) ?: return@mapNotNull null
                    val n = o.optString("name")
                    if (n.isBlank()) null else DexMethod(
                        name = n,
                        returnType = o.optString("returnType"),
                        params = (o.optJSONArray("parameters")?.let { pa ->
                            (0 until pa.length()).map { pa.optString(it) }
                        } ?: emptyList()),
                    )
                }
                val fa = info.optJSONArray("fields") ?: JSONArray()
                fields = (0 until fa.length()).mapNotNull { i ->
                    val o = fa.optJSONObject(i) ?: return@mapNotNull null
                    val n = o.optString("name")
                    if (n.isBlank()) null else DexField(n, o.optString("type"))
                }
            }
            if (code == null || code.has("error")) {
                smaliError = code?.let { it.optString("message").ifBlank { it.optString("error") } }
                    ?: (if (zh) "提取 smali 失败" else "Failed to extract smali")
                editor = TextFieldValue("")
                original = ""
                return@safeLaunch
            }
            val text = code.optString("smali")
            original = text
            editor = TextFieldValue(text)
        }
    }

    fun saveClass() {
        val f = srcFile ?: return
        if (openedClass.isEmpty() || !dirty) return
        saving = true
        saveMsg = null
        scope.safeLaunch {
            val r = dexTool(context, "taffy_smali_edit", JSONObject()
                .put("action", "replace")
                .put("path", f.absolutePath)
                .put("className", openedClass)
                .put("smali", editor.text))
            saving = false
            if (r == null || r.has("error")) {
                saveOk = false
                saveMsg = r?.let { it.optString("message").ifBlank { it.optString("error") } }
                    ?: (if (zh) "保存失败" else "Save failed")
                return@safeLaunch
            }
            saveOk = true
            original = editor.text
            val backup = r.optString("backupPath")
            saveMsg = if (zh) {
                "已重编并写回（${r.optInt("newDexSize")} 字节）。签名已失效，需要重新签名。" +
                    if (backup.isNotBlank()) "\n原包备份：$backup" else ""
            } else {
                "Recompiled and written back (${r.optInt("newDexSize")} bytes). Signature is now invalid." +
                    if (backup.isNotBlank()) "\nBackup: $backup" else ""
            }
        }
    }

    /** 跳到某个方法：把光标放到 `.method` 那一行，BasicTextField 会自己滚过去。 */
    fun jumpToMethod(m: DexMethod) {
        val lines = editor.text.split('\n')
        val idx = lines.indexOfFirst { it.contains(".method") && it.contains(m.name) }
        if (idx < 0) return
        var offset = 0
        for (i in 0 until idx) offset += lines[i].length + 1
        editor = editor.copy(selection = TextRange(offset))
    }

    val filtered = remember(classes, classQuery) {
        if (classQuery.isBlank()) classes
        else {
            val q = classQuery.trim().lowercase()
            classes.filter { it.name.lowercase().contains(q) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── 文件条 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FolderOpen, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (srcName.isBlank()) (if (zh) "未选择 APK" else "No APK selected") else srcName,
                    fontSize = AppText.bodyStrong,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (srcSize > 0) {
                    Text(
                        "${srcSize / 1024} KB" + if (classes.isNotEmpty()) " · ${classes.size} 个类" else "",
                        fontSize = AppText.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(if (zh) "选择 APK" else "Pick APK", fontSize = AppText.body)
            }
            if (srcFile != null) {
                IconButton(onClick = { loadClasses() }, enabled = !loadingClasses) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                }
            }
        }
        fileError?.let {
            Text(
                it,
                fontSize = AppText.body,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (openedClass.isNotEmpty()) {
            // ── 类编辑器 ──
            DexEditorPane(
                zh = zh,
                className = openedClass,
                superClass = superClass,
                methods = methods,
                fields = fields,
                editor = editor,
                onEditorChange = { editor = it },
                loading = loadingSmali,
                error = smaliError,
                dirty = dirty,
                saving = saving,
                saveMsg = saveMsg,
                saveOk = saveOk,
                onBack = { openedClass = ""; saveMsg = null },
                onCopy = {
                    clip.setText(AnnotatedString(editor.text))
                    saveMsg = if (zh) "已复制当前 smali" else "Smali copied"
                    saveOk = true
                },
                onRevert = { editor = TextFieldValue(original) },
                onSave = { saveClass() },
                onJump = { jumpToMethod(it) },
            )
        } else {
            // ── 类列表 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    DexTextField(
                        value = pkgPrefix,
                        onChange = { pkgPrefix = it },
                        placeholder = if (zh) "包名前缀（如 com.example，可留空）" else "Package prefix (optional)",
                    )
                }
                OutlinedButton(
                    onClick = { loadClasses() },
                    enabled = srcFile != null && !loadingClasses,
                ) {
                    if (loadingClasses) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (zh) "列类" else "List", fontSize = AppText.body)
                    }
                }
            }
            if (classes.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DexTextField(
                        value = classQuery,
                        onChange = { classQuery = it },
                        placeholder = if (zh) "在 ${classes.size} 个类里搜索" else "Filter ${classes.size} classes",
                    )
                }
            }
            classError?.let {
                Text(
                    it,
                    fontSize = AppText.body,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (classes.isEmpty() && classError == null) {
                DexEmpty(
                    title = if (zh) "选择 APK 后列类" else "Pick an APK to list classes",
                    hint = if (zh) {
                        "选一个含 classes.dex 的 APK，点右上「列类」。类可以按包名过滤，点进去能看到完整 smali 并直接改。"
                    } else {
                        "Pick an APK with classes.dex, then tap List. You can filter by package and edit smali in place."
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.name }) { c ->
                        DexClassRow(c) { openClass(c.name) }
                    }
                }
            }
        }
    }
}

// ── 类编辑器 ──

@Composable
private fun DexEditorPane(
    zh: Boolean,
    className: String,
    superClass: String,
    methods: List<DexMethod>,
    fields: List<DexField>,
    editor: TextFieldValue,
    onEditorChange: (TextFieldValue) -> Unit,
    loading: Boolean,
    error: String?,
    dirty: Boolean,
    saving: Boolean,
    saveMsg: String?,
    saveOk: Boolean,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    onJump: (DexMethod) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    className,
                    fontSize = AppText.bodyStrong,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        if (superClass.isNotBlank()) append("extends ${superClass.replace('/', '.')} · ")
                        append("${methods.size} ").append(if (zh) "方法" else "methods")
                        append(" · ").append(fields.size).append(if (zh) " 字段" else " fields")
                        if (dirty) append(if (zh) " · 已修改" else " · modified")
                    },
                    fontSize = AppText.label,
                    color = if (dirty) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
            }
        }

        if (methods.isNotEmpty()) {
            LazyRowMethods(methods, zh, onJump)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            error != null -> DexEmpty(
                title = if (zh) "这个类打不开" else "Cannot open this class",
                hint = error,
            )
            else -> Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(AppShape.sm))
                    .background(cs.surfaceVariant)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.sm))
                    .padding(10.dp),
            ) {
                BasicTextField(
                    value = editor,
                    onValueChange = onEditorChange,
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = AppText.body,
                        lineHeight = 18.sp,
                        color = cs.onSurface,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                )
            }
        }

        if (saveMsg != null) {
            Text(
                saveMsg,
                fontSize = AppText.label,
                lineHeight = 15.sp,
                color = if (saveOk) cs.onSurfaceVariant else cs.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (dirty) (if (zh) "未保存" else "Unsaved") else (if (zh) "无改动" else "No changes"),
                fontSize = AppText.label,
                color = if (dirty) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRevert, enabled = dirty && !saving) {
                Text(if (zh) "还原" else "Revert", fontSize = AppText.body)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = onSave, enabled = dirty && !saving) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (zh) "保存并重编" else "Save & rebuild", fontSize = AppText.body, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun LazyRowMethods(methods: List<DexMethod>, zh: Boolean, onJump: (DexMethod) -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(methods.size) { i ->
            val m = methods[i]
            Row(
                Modifier
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(AppShape.pill))
                    .background(cs.surfaceVariant)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.pill))
                    .clickable { onJump(m) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    m.name,
                    fontSize = AppText.label,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface,
                    maxLines = 1,
                )
            }
            if (i == methods.lastIndex) Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun DexClassRow(c: DexClass, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(AppShape.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val dot = c.name.lastIndexOf('.')
            Text(
                if (dot > 0) c.name.substring(dot + 1) else c.name,
                fontSize = AppText.bodyStrong,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (dot > 0) c.name.substring(0, dot) else "") + if (c.dex.isNotBlank()) "  ·  ${c.dex}" else "",
                fontSize = AppText.label,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "smali",
            fontSize = AppText.label,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun DexEmpty(title: String, hint: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = AppText.bodyStrong, color = cs.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            fontSize = AppText.body,
            lineHeight = 18.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun DexTextField(value: String, onChange: (String) -> Unit, placeholder: String) {
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

// ── 数据 ──

private data class DexClass(val name: String, val dex: String)
private data class DexMethod(val name: String, val returnType: String, val params: List<String>)
private data class DexField(val name: String, val type: String)

/**
 * 调一个已注册的 MCP 工具。
 *
 * 与 AnalysisWorkspace 里的 callMcpTool 同构（那边是 private，跨文件用不了）。
 * 返回 null 表示工具不存在或调用抛异常 —— UI 需要区分"工具缺失"和"工具报错"。
 */
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
