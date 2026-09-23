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
import androidx.compose.material.icons.filled.Description
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
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

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
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
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
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
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
        scope.launch {
            loading = true
            error = ""
            val r = withContext(Dispatchers.IO) {
                runCatching {
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
        Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 8.dp),
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
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                DataRow(
                    title = if (zh) "返回类列表" else "Back to class list",
                    trailingText = "‹",
                    onClick = { selected = null; members = emptyList() },
                )
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
                val filtered = classes.filter { it.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered, key = { it }) { c ->
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

    BusyOverlay(visible = loading, message = if (zh) "解析中…" else "Parsing…")
}
