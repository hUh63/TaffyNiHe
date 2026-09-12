package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * 设置 → DEX / APK 浏览器：可视化浏览 APK/DEX 的类与方法（对标 MT 管理器的 DEX 查看入口）。
 *
 * 只读浏览（类列表可搜索、点类看字段/方法）；实际编辑仍走对应 MCP 工具 / 编辑器页。
 * 补齐「APK/DEX 工具只有命令行/JSON 交互、没有可视化操作界面」的空白。
 */
@Composable
internal fun SettingsDexExplorerPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var classes by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<String>>(emptyList()) }

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
        loading = true; error = ""; classes = emptyList(); selected = null; members = emptyList()
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val names = ArrayList<String>()
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
        selected = type; members = emptyList()
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val list = ArrayList<String>()
                    dexFilesOf(path).forEach { d ->
                        DexFileFactory.loadDexFile(d, Opcodes.getDefault()).classes.firstOrNull { it.type == type }?.let { cd ->
                            cd.fields.forEach { f -> list.add("field  ${f.type} ${f.name}") }
                            cd.methods.forEach { m ->
                                list.add("method ${m.name}(${m.parameterTypes.joinToString("") { it.toString() }})${m.returnType}")
                            }
                        }
                    }
                    list
                }
            }
            res.onSuccess { members = it }
            res.onFailure { members = emptyList() }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { path = uri.toString(); load(uri.toString()) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (zh) "DEX / APK 浏览器" else "DEX / APK Explorer", style = MaterialTheme.typography.titleSmall)
        Text(
            if (zh) "浏览 APK/DEX 的类与方法（只读）。编辑请走对应 MCP 工具或编辑器页。" else "Browse classes & methods of an APK/DEX (read-only).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("APK / DEX path") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedButton(onClick = { pick.launch("*/*") }) { Text(if (zh) "选择" else "Pick") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { load(path) }, enabled = path.isNotBlank() && !loading) { Text(if (zh) "加载" else "Load") }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)

        val sel = selected
        if (sel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sel, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = null; members = emptyList() }) { Text(if (zh) "返回类列表" else "Back") }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(members, key = { it }) { m ->
                    Text(m, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                }
            }
        } else {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(if (zh) "搜索类" else "Search class") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val filtered = remember(classes, query) { if (query.isBlank()) classes else classes.filter { it.contains(query, ignoreCase = true) } }
            Text(if (zh) "共 ${filtered.size} / ${classes.size} 个类" else "${filtered.size} / ${classes.size} classes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it }) { c ->
                    Text(c, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { openClass(c) }.padding(vertical = 2.dp))
                }
            }
        }
    }
}
