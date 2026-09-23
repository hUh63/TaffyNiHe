package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
 * 设置 → APK Manifest 编辑：图形化编辑 AndroidManifest
 * （包名 / 权限 / 组件 / debuggable / meta-data）。
 *
 * 底层完全复用 ManifestEditTools（ARSCLib AXML 精确解码/编码，非正则），本页只做可视化 + 结果展示。
 * 图形化要点：分区卡片、操作记录用统一终端框、破坏性删除二次确认。
 */
@Composable
internal fun SettingsApkEditPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
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

    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingMsg by remember { mutableStateOf("") }

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
        if (path.isBlank()) {
            error = if (zh) "请先选择 APK 文件" else "pick an APK first"
            return
        }
        loading = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { ManifestEditTools.manifestEdit.handle(handle(), JSONObject().put("action", "get").put("path", path)) }
            }
            loading = false
            res.onSuccess { r ->
                if (r.optBoolean("ok", true) && !r.has("error")) {
                    parse(r)
                    error = ""
                    if (!silent) log = "✓ ${if (zh) "已读取 Manifest" else "manifest loaded"}\n" + log
                } else {
                    error = r.optJSONObject("error")?.optString("message").orEmpty().ifBlank { if (zh) "读取失败" else "load failed" }
                }
            }
            res.onFailure { error = it.message ?: "failed" }
        }
    }

    fun act(action: String, label: String, vararg pairs: Pair<String, String>) {
        if (path.isBlank()) {
            error = if (zh) "请先选择 APK 文件" else "pick an APK first"
            return
        }
        loading = true
        error = ""
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
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            val dst = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "input.apk"
                    val f = File(context.cacheDir, "apk_edit_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    f.absolutePath to name
                }.getOrNull()
            }
            if (dst != null) {
                path = dst.first
                displayName = dst.second
                refresh(silent = false)
            } else {
                error = if (zh) "读取所选文件失败" else "failed to read picked file"
            }
            loading = false
        }
    }

    val hasManifest = packageName.isNotBlank() || xml.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 文件卡 ──
        GlassGroup {
            DataRow(
                title = displayName.ifBlank { if (zh) "未选择 APK" else "No APK" },
                subtitle = if (hasManifest) "$packageName  ·  $versionName ($versionCode)" else (if (zh) "选择 APK 后图形化编辑 Manifest" else "Pick an APK to edit manifest"),
                meta = if (path.isBlank()) null else path,
                leading = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                trailingText = if (zh) "选择" else "Pick",
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
            )
        }

        if (error.isNotBlank()) {
            GlassGroup { InlineHint("⚠ $error", tone = HintTone.Error) }
        }

        // ── Manifest 概览 ──
        if (hasManifest) {
            GlassGroup(title = if (zh) "Manifest 概览" else "Manifest overview") {
                MonoRow(if (zh) "包名" else "package", packageName.ifBlank { "-" })
                MonoRow(if (zh) "版本" else "version", "$versionName ($versionCode)")
                MonoRow(if (zh) "权限数" else "permissions", permissions.size.toString())
                permissions.take(40).forEach { p ->
                    Text(
                        "· $p",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp),
                    )
                }
                if (permissions.size > 40) {
                    Text(
                        "… +${permissions.size - 40}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                DataRow(
                    title = if (showXml) (if (zh) "收起 XML" else "Hide XML") else (if (zh) "查看 XML" else "View XML"),
                    trailingText = if (showXml) "▴" else "▾",
                    onClick = { showXml = !showXml },
                )
                if (showXml && xml.isNotBlank()) {
                    TerminalPane(text = xml, placeholder = "-", maxHeight = 300.dp)
                }
            }
        }

        // ── ① 包名 ──
        GlassGroup(title = if (zh) "① 修改包名" else "① Package name") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newPackage,
                    onValueChange = { newPackage = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(if (zh) "新包名" else "new package") },
                )
                PrimaryActionButton(
                    if (zh) "应用" else "Apply",
                    { act("set_package", if (zh) "改包名" else "set package", "value" to newPackage) },
                    Modifier.weight(1f),
                )
            }
        }

        // ── ② 权限 ──
        GlassGroup(title = if (zh) "② 权限" else "② Permissions") {
            OutlinedTextField(
                value = permInput,
                onValueChange = { permInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text("android.permission.XXX") },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    if (zh) "添加" else "Add",
                    { act("add_perm", if (zh) "加权限" else "add perm", "value" to permInput) },
                    Modifier.weight(1f),
                )
                SecondaryActionButton(
                    if (zh) "移除" else "Remove",
                    {
                        pendingMsg = if (zh) "将移除权限：$permInput" else "Remove permission: $permInput"
                        pendingConfirm = { act("remove_perm", if (zh) "删权限" else "remove perm", "value" to permInput) }
                    },
                    Modifier.weight(1f),
                )
            }
        }

        // ── ③ 组件 ──
        GlassGroup(title = if (zh) "③ 组件" else "③ Components") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("activity", "service", "receiver", "provider").forEach { ct ->
                    FilterChip(selected = compType == ct, onClick = { compType = ct }, label = { Text(ct) })
                }
            }
            OutlinedTextField(
                value = compInput,
                onValueChange = { compInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text(if (zh) "组件类名 (com.x.Y)" else "component class") },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    if (zh) "添加" else "Add",
                    { act("add_component", if (zh) "加组件" else "add component", "value" to compInput, "componentType" to compType) },
                    Modifier.weight(1f),
                )
                SecondaryActionButton(
                    if (zh) "移除" else "Remove",
                    {
                        pendingMsg = if (zh) "将移除组件（$compType）：$compInput" else "Remove $compType: $compInput"
                        pendingConfirm = { act("remove_component", if (zh) "删组件" else "remove component", "value" to compInput, "componentType" to compType) }
                    },
                    Modifier.weight(1f),
                )
            }
        }

        // ── ④ debuggable / meta-data ──
        GlassGroup(title = if (zh) "④ debuggable / meta-data" else "④ debuggable / meta-data") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    "debuggable = true",
                    { act("set_debuggable", "debuggable=true", "debuggable" to "true") },
                    Modifier.weight(1f),
                )
                SecondaryActionButton(
                    "debuggable = false",
                    { act("set_debuggable", "debuggable=false", "debuggable" to "false") },
                    Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = metaName,
                    onValueChange = { metaName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("meta android:name") },
                )
                OutlinedTextField(
                    value = metaValue,
                    onValueChange = { metaValue = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(if (zh) "值" else "value") },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    if (zh) "添加 meta" else "Add meta",
                    { act("add_meta", if (zh) "加 meta" else "add meta", "metaName" to metaName, "metaValue" to metaValue) },
                    Modifier.weight(1f),
                )
                SecondaryActionButton(
                    if (zh) "移除 meta" else "Remove meta",
                    {
                        pendingMsg = if (zh) "将移除 meta-data：$metaName" else "Remove meta: $metaName"
                        pendingConfirm = { act("remove_meta", if (zh) "删 meta" else "remove meta", "metaName" to metaName) }
                    },
                    Modifier.weight(1f),
                )
            }
        }

        // ── 操作记录 ──
        if (log.isNotBlank()) {
            TerminalPane(
                text = log,
                title = if (zh) "操作记录" else "Log",
                onClear = { log = "" },
                maxHeight = 260.dp,
            )
        }
    }

    pendingConfirm?.let { run ->
        ConfirmDialog(
            title = if (zh) "确认操作？" else "Confirm?",
            message = pendingMsg,
            confirmText = if (zh) "确认" else "Confirm",
            destructive = true,
            onConfirm = run,
            onDismiss = { pendingConfirm = null },
        )
    }

    BusyOverlay(visible = loading, message = if (zh) "处理中…" else "Working…")
}
