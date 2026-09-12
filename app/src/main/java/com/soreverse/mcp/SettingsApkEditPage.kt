package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ManifestEditTools
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 设置 → APK Manifest 编辑：把原本只能通过 MCP 工具(taffy_manifest_xml_edit)调用的
 * AndroidManifest 精确编辑做成图形化界面（对标 MT 管理器的 Manifest 编辑入口）。
 *
 * 底层完全复用 ManifestEditTools（ARSCLib AXML 精确解码/编码，非正则），
 * 本页只做可视化操作 + 结果展示，不重复实现任何编辑逻辑。
 */
@Composable
internal fun SettingsApkEditPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var versionName by remember { mutableStateOf("") }
    var versionCode by remember { mutableStateOf("") }
    var permissions by remember { mutableStateOf<List<String>>(emptyList()) }
    var xml by remember { mutableStateOf("") }
    var showXml by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    var newPackage by remember { mutableStateOf("") }
    var permInput by remember { mutableStateOf("") }
    var compType by remember { mutableStateOf("activity") }
    var compInput by remember { mutableStateOf("") }
    var metaName by remember { mutableStateOf("") }
    var metaValue by remember { mutableStateOf("") }

    fun handle() = ToolContext(
        context = context,
        settings = SettingsStore(context),
        engine = EngineProvider.get(context),
        binaryEngine = runCatching { EngineProvider.getBinaryEngine(context) }.getOrNull(),
    )

    fun parse(r: JSONObject) {
        packageName = r.optString("packageName")
        versionName = r.optString("versionName")
        versionCode = r.optString("versionCode")
        val arr = r.optJSONArray("usesPermissions")
        permissions = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
        xml = r.optString("manifest")
    }

    fun refresh(silent: Boolean) {
        if (path.isBlank()) { error = if (zh) "请先选择 APK 文件" else "pick an APK first"; return }
        loading = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { ManifestEditTools.manifestEdit.handle(handle(), JSONObject().put("action", "get").put("path", path)) }
            }
            loading = false
            res.onSuccess { r ->
                if (r.optBoolean("ok", true) && !r.has("error")) {
                    parse(r); error = ""
                    if (!silent) log = "✓ ${if (zh) "已读取 Manifest" else "manifest loaded"}\n" + log
                } else {
                    error = r.optJSONObject("error")?.optString("message").orEmpty().ifBlank { if (zh) "读取失败" else "load failed" }
                }
            }
            res.onFailure { error = it.message ?: "failed" }
        }
    }

    fun act(action: String, label: String, vararg pairs: Pair<String, String>) {
        if (path.isBlank()) { error = if (zh) "请先选择 APK 文件" else "pick an APK first"; return }
        loading = true; error = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val args = JSONObject().put("action", action).put("path", path)
                    pairs.forEach { (k, v) -> args.put(k, v) }
                    ManifestEditTools.manifestEdit.handle(handle(), args)
                }
            }
            loading = false
            res.onSuccess { r ->
                val okv = r.optBoolean("ok", true) && !r.has("error")
                if (okv) {
                    log = "✓ $label\n" + log
                    refresh(silent = true)
                } else {
                    val msg = r.optJSONObject("error")?.optString("message").orEmpty().ifBlank { if (zh) "操作失败" else "failed" }
                    error = msg
                    log = "✗ $label: $msg\n" + log
                }
            }
            res.onFailure {
                error = it.message ?: "failed"
                log = "✗ $label: ${it.message}\n" + log
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val dst = withContext(Dispatchers.IO) {
                    runCatching {
                        val f = File(context.cacheDir, "apk_edit_${System.currentTimeMillis()}.apk")
                        context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        f.absolutePath
                    }.getOrNull()
                }
                if (dst != null) { path = dst; refresh(silent = false) }
                else error = if (zh) "读取所选文件失败" else "failed to read picked file"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (zh) "APK Manifest 编辑" else "APK Manifest Editor", style = MaterialTheme.typography.titleSmall)
        Text(
            if (zh) "图形化编辑 AndroidManifest：包名 / 权限 / 组件 / debuggable / meta-data。底层用 ARSCLib 精确改写，非正则。"
            else "Graphical AndroidManifest editing: package / permissions / components / debuggable / meta-data, via ARSCLib.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = path, onValueChange = { path = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text(if (zh) "APK 路径" else "APK path", fontSize = 11.sp) },
            )
            OutlinedButton(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }) {
                Text(if (zh) "选择" else "Pick", fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh(silent = false) }, enabled = !loading && path.isNotBlank()) {
                Text(if (zh) "读取 Manifest" else "Load manifest", fontSize = 12.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.heightIn(max = 22.dp), strokeWidth = 2.dp)
        }

        if (error.isNotBlank()) {
            Text("⚠ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (packageName.isNotBlank() || xml.isNotBlank()) {
            HorizontalDivider()
            Text(
                "${if (zh) "包名" else "package"}: $packageName",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                "${if (zh) "版本" else "version"}: $versionName ($versionCode)",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                "${if (zh) "权限" else "permissions"} (${permissions.size})",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            permissions.take(40).forEach { p ->
                Text("· $p", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
            }
            if (permissions.size > 40) Text("… +${permissions.size - 40}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showXml = !showXml }) {
                Text((if (showXml) (if (zh) "收起 XML" else "Hide XML") else (if (zh) "查看 XML" else "View XML")), fontSize = 12.sp)
            }
            if (showXml) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(xml, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp))
                }
            }
        }

        HorizontalDivider()
        Text(if (zh) "① 修改包名" else "① Package name", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newPackage, onValueChange = { newPackage = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text(if (zh) "新包名" else "new package", fontSize = 11.sp) },
            )
            Button(onClick = { act("set_package", if (zh) "改包名" else "set package", "value" to newPackage) }, enabled = !loading && newPackage.isNotBlank()) {
                Text(if (zh) "应用" else "Apply", fontSize = 12.sp)
            }
        }

        HorizontalDivider()
        Text(if (zh) "② 权限" else "② Permissions", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = permInput, onValueChange = { permInput = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text("android.permission.XXX", fontSize = 11.sp) },
            )
            OutlinedButton(onClick = { act("add_perm", if (zh) "加权限" else "add perm", "value" to permInput) }, enabled = !loading && permInput.isNotBlank()) {
                Text(if (zh) "添加" else "Add", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { act("remove_perm", if (zh) "删权限" else "remove perm", "value" to permInput) }, enabled = !loading && permInput.isNotBlank()) {
                Text(if (zh) "移除" else "Remove", fontSize = 12.sp)
            }
        }

        HorizontalDivider()
        Text(if (zh) "③ 组件" else "③ Components", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("activity", "service", "receiver", "provider").forEach { ct ->
                FilterChip(selected = compType == ct, onClick = { compType = ct }, label = { Text(ct, fontSize = 10.sp) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = compInput, onValueChange = { compInput = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text(if (zh) "组件类名 (com.x.Y)" else "component class", fontSize = 11.sp) },
            )
            OutlinedButton(onClick = { act("add_component", if (zh) "加组件" else "add component", "value" to compInput, "componentType" to compType) }, enabled = !loading && compInput.isNotBlank()) {
                Text(if (zh) "添加" else "Add", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { act("remove_component", if (zh) "删组件" else "remove component", "value" to compInput, "componentType" to compType) }, enabled = !loading && compInput.isNotBlank()) {
                Text(if (zh) "移除" else "Remove", fontSize = 12.sp)
            }
        }

        HorizontalDivider()
        Text(if (zh) "④ debuggable / meta-data" else "④ debuggable / meta-data", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act("set_debuggable", "debuggable=true", "debuggable" to "true") }, enabled = !loading) {
                Text("debuggable = true", fontSize = 11.sp)
            }
            OutlinedButton(onClick = { act("set_debuggable", "debuggable=false", "debuggable" to "false") }, enabled = !loading) {
                Text("debuggable = false", fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = metaName, onValueChange = { metaName = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text("meta android:name", fontSize = 11.sp) },
            )
            OutlinedTextField(
                value = metaValue, onValueChange = { metaValue = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text(if (zh) "值" else "value", fontSize = 11.sp) },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act("add_meta", if (zh) "加 meta" else "add meta", "metaName" to metaName, "metaValue" to metaValue) }, enabled = !loading && metaName.isNotBlank()) {
                Text(if (zh) "添加 meta" else "Add meta", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { act("remove_meta", if (zh) "删 meta" else "remove meta", "metaName" to metaName) }, enabled = !loading && metaName.isNotBlank()) {
                Text(if (zh) "移除 meta" else "Remove meta", fontSize = 12.sp)
            }
        }

        if (log.isNotBlank()) {
            HorizontalDivider()
            Text(if (zh) "操作记录" else "Activity log", style = MaterialTheme.typography.labelMedium)
            Text(log, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp))
        }
    }
}
