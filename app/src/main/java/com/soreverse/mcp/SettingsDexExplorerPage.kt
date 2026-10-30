package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import org.json.JSONObject
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.core.runCatchingCancellable

/**
 * 设置 → DEX / APK 浏览器：可视化浏览 APK/DEX 的类与方法（只读）。
 *
 * 修复：旧实现把 content:// URI 当文件路径用，选文件后必然报“文件不存在”；
 * 现改为先落地到 cacheDir 再解析。列表用卡片行 + 实时搜索 + 稳定 key。
 */
@Composable
internal fun SettingsDexExplorerPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var classes by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var memberLoading by remember { mutableStateOf(false) }
    var memberError by remember { mutableStateOf("") }
    // 重命名（taffy_dex_rename）：这是本页唯一的写操作，其余都是只读浏览，
    // 所以单独一组状态 + 二次确认，避免误触把工作副本改掉。
    var renameOpen by remember { mutableStateOf(false) }
    var renameTo by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameMsg by remember { mutableStateOf("") }

    fun dexFilesOf(p: String): List<File> {
        val f = File(p)
        require(f.exists()) { if (zh) "文件不存在: $p" else "file not found: $p" }
        if (f.name.lowercase().endsWith(".dex")) return listOf(f)
        val out = mutableListOf<File>()
        ZipFile(f).use { zf ->
            zf.entries().toList().filter { it.name.endsWith(".dex") && !it.isDirectory }.forEach { e ->
                val o = File(context.cacheDir, "dexx_${e.name.replace('/', '_')}")
                zf.getInputStream(e).use { i -> o.outputStream().use { i.copyTo(it) } }
                out.add(o)
            }
        }
        return out
    }

    fun load(p: String) {
        if (p.isBlank()) return
        loading = true
        error = ""
        classes = emptyList()
        selected = null
        members = emptyList()
        scope.safeLaunch {
            val res = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val names = LinkedHashSet<String>()
                    dexFilesOf(p).forEach { d ->
                        DexFileFactory.loadDexFile(d, Opcodes.getDefault()).classes.forEach { cd -> names.add(cd.type) }
                    }
                    names.sorted()
                }
            }
            loading = false
            res.onSuccess { classes = it }
            res.onFailure { error = it.message ?: (if (zh) "加载失败" else "load failed") }
        }
    }

    fun openClass(type: String) {
        if (path.isBlank()) return
        selected = type
        members = emptyList()
        memberError = ""
        memberLoading = true
        scope.safeLaunch {
            val res = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val list = ArrayList<Pair<String, String>>()
                    var found = false
                    dexFilesOf(path).forEach { d ->
                        DexFileFactory.loadDexFile(d, Opcodes.getDefault()).classes.firstOrNull { it.type == type }?.let { cd ->
                            found = true
                            cd.fields.forEach { f -> list.add("field" to "${f.type} ${f.name}") }
                            cd.methods.forEach { m ->
                                list.add("method" to "${m.name}(${m.parameterTypes.joinToString("") { it.toString() }})${m.returnType}")
                            }
                        }
                    }
                    if (!found) throw IllegalStateException(if (zh) "未找到该类" else "class not found")
                    list
                }
            }
            memberLoading = false
            res.onSuccess { members = it }
            res.onFailure { memberError = it.message ?: if (zh) "读取成员失败" else "load members failed" }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.safeLaunch {
            loading = true
            error = ""
            val r = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "input.dex"
                    val f = File(context.cacheDir, "dexx_input_${System.currentTimeMillis()}_$name")
                    context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        ?: throw IllegalStateException(if (zh) "无法读取所选文件" else "read failed")
                    f.absolutePath to name
                }
            }
            r.onSuccess { pair ->
                path = pair.first
                displayName = pair.second
                load(pair.first)
            }.onFailure {
                error = it.message ?: "failed"
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassGroup {
            DataRow(
                title = displayName.ifBlank { if (zh) "未选择文件" else "No file" },
                subtitle = if (path.isBlank()) (if (zh) "选择一个 APK / DEX 浏览类与方法（只读）" else "Pick an APK / DEX to browse (read-only)")
                else (if (zh) "${classes.size} 个类" else "${classes.size} classes"),
                meta = if (path.isBlank()) null else path,
                leading = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                trailingText = if (zh) "选择" else "Pick",
                onClick = { pick.launch("*/*") },
            )
        }

        if (error.isNotBlank()) {
            GlassGroup { InlineHint(error, tone = HintTone.Error) }
        }

        val sel = selected
        if (sel != null) {
            GlassGroup {
                Text(
                    sel.replace('/', '.'),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                DataRow(
                    title = if (zh) "重命名此类" else "Rename this class",
                    subtitle = if (zh) "级联全部类型引用；可写出到副本" else "Cascades every type reference",
                    leading = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    trailingText = "›",
                    onClick = { renameTo = sel.removePrefix("L").removeSuffix(";").replace('/', '.'); renameMsg = ""; renameOpen = true },
                )
                DataRow(
                    title = if (zh) "返回类列表" else "Back to class list",
                    leading = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    trailingText = "‹",
                    onClick = { selected = null; members = emptyList() },
                )
                if (renameMsg.isNotBlank()) {
                    InlineHint(renameMsg, tone = if (renameMsg.startsWith("OK")) HintTone.Ok else HintTone.Error)
                }
            }
            when {
                memberLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (zh) "读取成员…" else "Loading members…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                memberError.isNotBlank() -> InlineHint(memberError, tone = HintTone.Error)
                members.isEmpty() -> InlineHint(if (zh) "该类无字段/方法" else "No fields/methods")
                else -> {
                    Text(
                        if (zh) "${members.size} 个成员" else "${members.size} members",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(members) { m ->
                            GlassGroup {
                                DataRow(
                                    title = m.second,
                                    leading = { TypeChip(m.first, if (m.first == "field") AppPalette.orange else AppPalette.blue) },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            SearchCountBar(
                query = query,
                onQueryChange = { query = it },
                shown = classes.count { it.contains(query.trim(), ignoreCase = true) },
                total = classes.size,
                placeholder = if (zh) "搜索类" else "Search class",
            )
            if (classes.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    InlineHint(
                        if (loading) (if (zh) "解析中…" else "Parsing…")
                        else (if (zh) "选择 APK / DEX 后显示类列表" else "Pick an APK / DEX to list classes"),
                    )
                }
            } else {
                // 包树：dex 是「包 → 类」的两级结构，平铺一列几万个类名只能靠搜索找。
                // 按包分组后可折叠，搜索时自动展开命中项（否则命中被折叠的包里也看不到）。
                val q = query.trim()
                val groups = remember(classes, q) {
                    val filtered = if (q.isEmpty()) classes else classes.filter { it.contains(q, ignoreCase = true) }
                    filtered.groupBy { cls ->
                        val body = cls.removePrefix("L").removeSuffix(";")
                        val idx = body.lastIndexOf('/')
                        if (idx < 0) "" else body.substring(0, idx).replace('/', '.')
                    }.toSortedMap()
                }
                var collapsed by remember { mutableStateOf(emptySet<String>()) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { (pkg, list) ->
                        val isCollapsed = q.isEmpty() && pkg in collapsed
                        item(key = "pkg:" + pkg) {
                            GlassGroup {
                                DataRow(
                                    title = pkg.ifBlank { if (zh) "(默认包)" else "(default)" },
                                    subtitle = if (zh) "${list.size} 个类" else "${list.size} classes",
                                    leading = {
                                        Icon(
                                            if (isCollapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    trailingText = if (isCollapsed) (if (zh) "展开" else "open") else (if (zh) "折叠" else "close"),
                                    onClick = {
                                        collapsed = if (isCollapsed) collapsed - pkg else collapsed + pkg
                                    },
                                )
                            }
                        }
                        if (!isCollapsed) {
                            items(list, key = { "cls:" + it }) { c ->
                                Column(Modifier.padding(start = 14.dp)) {
                                    GlassGroup {
                                        DataRow(
                                            title = c.substringAfterLast('/').removePrefix("L").removeSuffix(";").ifBlank { c },
                                            subtitle = c,
                                            onClick = { openClass(c) },
                                            onLongClick = { openClass(c) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renameOpen) {
        val src = selected
        AlertDialog(
            onDismissRequest = { if (!renameBusy) renameOpen = false },
            title = { Text(if (zh) "重命名类" else "Rename class", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        src?.replace('/', '.') ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameTo,
                        onValueChange = { renameTo = it },
                        singleLine = true,
                        label = { Text(if (zh) "新类名（点分）" else "New class name (dotted)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        if (zh) "会级联字段类型/方法签名/指令/注解；字符串常量里的反射类名不改。"
                        else "Cascades field types / signatures / instructions / annotations. Reflective class names in string constants are untouched.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !renameBusy && renameTo.isNotBlank() && src != null,
                    onClick = {
                        val cls = src ?: return@TextButton
                        val inputPath = path
                        renameBusy = true
                        scope.safeLaunch {
                            val res = withContext(Dispatchers.IO) {
                                runCatchingCancellable {
                                    val handler = ToolCatalog.byName["taffy_dex_rename"]
                                        ?: throw IllegalStateException("taffy_dex_rename not registered")
                                    val args = JSONObject()
                                        .put("path", inputPath)
                                        .put("action", "class")
                                        .put("oldName", cls.removePrefix("L").removeSuffix(";").replace('/', '.'))
                                        .put("newName", renameTo.trim())
                                    handler.handle(
                                        ToolContext(
                                            context,
                                            SettingsStore(context),
                                            EngineProvider.get(context),
                                            runCatching { EngineProvider.getBinaryEngine(context) }.getOrNull(),
                                        ),
                                        args,
                                    )
                                }
                            }
                            renameBusy = false
                            res.onSuccess { r ->
                                val total = r.optInt("totalRenamed", 0)
                                renameMsg = if (r.optBoolean("ok", false)) {
                                    "OK · ${if (zh) "重命名 $total 处，已写回" else "renamed $total hits, written back"}"
                                } else {
                                    r.optString("error").ifBlank { if (zh) "重命名失败" else "rename failed" }
                                }
                                renameOpen = false
                                if (r.optBoolean("ok", false)) {
                                    selected = null
                                    members = emptyList()
                                    load(inputPath)
                                }
                            }.onFailure {
                                renameMsg = it.message ?: "failed"
                                renameOpen = false
                            }
                        }
                    },
                ) { Text(if (renameBusy) (if (zh) "处理中…" else "Working…") else (if (zh) "重命名" else "Rename")) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }, enabled = !renameBusy) {
                    Text(if (zh) "取消" else "Cancel")
                }
            },
        )
    }

    BusyOverlay(visible = loading, message = if (zh) "解析中…" else "Parsing…")
}
