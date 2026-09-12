package com.soreverse.mcp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 选择文件的 ActivityResultContract：与 OpenDocument 相同，但额外请求
 * 读写权限（FLAG_GRANT_READ|WRITE|PERSISTABLE），以便引擎对所选文件进行读写。
 * 同包共享（AnalyzeTab 也使用），故为 internal。
 */
internal class OpenDocumentReadWriteContract : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

internal data class ToolDef(val key: String, val labelZh: String, val labelEn: String)

internal val toolDefs = listOf(
    ToolDef("decompile", "反编译", "Decompile"),
    ToolDef("unpack", "脱壳", "Unpack"),
    ToolDef("soanalyze", "SO分析", "SO"),
    ToolDef("emulate", "模拟", "Emulate"),
    ToolDef("frida", "Frida", "Frida"),
    ToolDef("rebuild", "回编", "Rebuild"),
    ToolDef("editor", "编辑", "Editor"),
)

/** 需要地址栏的工具功能列表 */
private val addrNeededTools = setOf("decompile", "emulate", "editor")

@Composable
internal fun AnalysisWorkspace(
    t: UiText,
    state: WorkspaceState,
    context: android.content.Context,
    onOpenTask: () -> Unit,
    /** AI 深度分析入口（对当前任务主文件发起，MainActivity 挂载聊天页）。 */
    onAiAnalyze: (String) -> Unit,
) {
    val zh = t.zh
    val tools = state.tools
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
        var toolsExpanded by remember { mutableStateOf(false) }
        // 顶部栏：任务 + 工具切换 + 选文件
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cur = state.currentTask()
            Text(if (zh) "任务" else "Task", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpenTask,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                shape = RoundedCornerShape(6.dp)) {
                Text((cur?.title ?: if (zh) "未选择" else "None").take(12), style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
            }
            if (cur != null && tools.sharedWorkspaceId.isBlank()) {
                Text(if (zh) "⚠ 需重选文件" else "⚠ Re-pick", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { toolsExpanded = !toolsExpanded },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                shape = RoundedCornerShape(6.dp)) {
                Text(if (zh) "工具" else "Tools", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                Spacer(Modifier.size(3.dp))
                Text(if (toolsExpanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
            }
            WorkspacePicker(state, zh)
        }
        // 可折叠工具切换条
        if (toolsExpanded) {
            Spacer(Modifier.size(4.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                toolDefs.forEach { def ->
                    val sel = state.activeTool == def.key
                    Button(
                        onClick = { state.activeTool = def.key; toolsExpanded = false },
                        colors = if (sel) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text(if (zh) def.labelZh else def.labelEn, style = MaterialTheme.typography.labelSmall, fontSize = 12.sp) }
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        // 地址栏：放在工作区选择下面，仅在需要地址的工具中显示
        if (state.activeTool in addrNeededTools) {
            AddrBar(state, zh)
            Spacer(Modifier.size(4.dp))
        }
        // 主体
        Column(Modifier.fillMaxSize()) {
            if (state.activeTool.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) { ToolConsole(state, zh, onAiAnalyze) }
                Spacer(Modifier.size(4.dp))
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            ) { ResultStream(tools, zh) }
        }
    }
}

/** 地址栏组件：统一放在选文件下面，控制台上面 */
@Composable
private fun AddrBar(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (zh) "地址" else "Addr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        OutlinedTextField(
            value = tools.disasmAddr,
            onValueChange = { tools.disasmAddr = it },
            singleLine = true,
            modifier = Modifier.weight(1f).height(28.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            shape = RoundedCornerShape(4.dp),
            placeholder = { Text(if (zh) "地址或符号" else "addr or sym", style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        // 根据当前工具显示不同的辅助输入
        when (state.activeTool) {
            "decompile" -> {
                Text(if (zh) "符号" else "Sym", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                OutlinedTextField(
                    value = tools.decompileTarget,
                    onValueChange = { tools.decompileTarget = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(28.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    shape = RoundedCornerShape(4.dp),
                    placeholder = { Text(if (zh) "函数名" else "func name", style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            "emulate" -> {
                Text(if (zh) "符号" else "Sym", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                OutlinedTextField(
                    value = tools.emulateSymbol,
                    onValueChange = { tools.emulateSymbol = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(28.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    shape = RoundedCornerShape(4.dp),
                    placeholder = { Text(if (zh) "函数名" else "func name", style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            "editor" -> {
                Text(if (zh) "对比地址" else "Diff", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                OutlinedTextField(
                    value = tools.decompileTarget,
                    onValueChange = { tools.decompileTarget = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(28.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    shape = RoundedCornerShape(4.dp),
                    placeholder = { Text(if (zh) "对比地址" else "diff addr", style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}

@Composable
private fun WorkspacePicker(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf("") }

    fun openWorkspaceFile(path: String, fallbackName: String) {
        scope.launch {
            tools.opening = true; tools.openError = ""
            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).open(path, false) }.getOrNull() }
            if (r != null && r.optBoolean("ok", false)) {
                tools.sharedWorkspaceId = r.optString("workspaceId"); tools.sharedSoName = r.optString("soFileName").ifBlank { r.optString("fileName") }
                val fname = tools.sharedSoName.ifBlank { fallbackName }
                state.createTask(fname, if (fname.endsWith(".apk", true)) "apk" else "so", path, fname)
                tools.opening = false; tools.clearTabs()
            } else { tools.opening = false; tools.openError = r?.optString("error").orEmpty().ifBlank { if (zh) "打开失败" else "Open failed" } }
        }
    }

    val picker = rememberLauncherForActivityResult(OpenDocumentReadWriteContract()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        showDialog = false
        openWorkspaceFile(uri.toString(), uri.lastPathSegment ?: "file")
    }

    fun submitManual() {
        val path = manualPath.trim()
        if (path.isBlank()) {
            manualError = if (zh) "请输入文件路径" else "Please enter a file path"
            return
        }
        showDialog = false
        openWorkspaceFile(path, path.substringAfterLast('/').ifBlank { "file" })
    }

    Button(onClick = { showDialog = true; manualPath = ""; manualError = "" },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), enabled = !tools.opening, shape = RoundedCornerShape(6.dp)) {
        if (tools.opening) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(4.dp)); Text(if (zh) "加载中…" else "Loading…", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) }
        else { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.size(4.dp)); Text((tools.sharedSoName.ifBlank { if (zh) "选文件" else "Open" }).take(12), style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
    }
    if (tools.openError.isNotBlank()) Text(tools.openError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (zh) "选文件" else "Pick file",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { showDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = if (zh) "关闭" else "Close")
                        }
                    }
                    Text(
                        if (zh) "选择要分析的文件（APK、SO 等），文件可读写访问。"
                        else "Select a file to analyze (APK, SO, etc.). Read-write access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            // 借鉴上游 #78（上游未修复，塔菲做得更好）：部分国产 ROM 无 SAF 文件提供方，
                            // picker.launch 会抛 ActivityNotFoundException——捕获后引导使用下方手动路径输入
                            try {
                                picker.launch(arrayOf("application/octet-stream", "application/zip", "application/vnd.android.package-archive", "*/*"))
                            } catch (e: android.content.ActivityNotFoundException) {
                                manualError = if (zh) "系统未提供文件选择器（SAF），请在下方直接输入文件路径" else "No file picker (SAF) on this device; type the path below"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (zh) "选择文件" else "Pick file")
                    }
                    androidx.compose.material3.HorizontalDivider()
                    Text(
                        if (zh) "手动输入路径" else "Manual path",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it; manualError = "" },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (zh) "输入文件路径" else "Enter file path", maxLines = 1) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        isError = manualError.isNotBlank(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { submitManual() },
                        ),
                        supportingText = if (manualError.isNotBlank()) {
                            { Text(manualError, color = MaterialTheme.colorScheme.error) }
                        } else null,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showDialog = false }) {
                            Text(if (zh) "取消" else "Cancel")
                        }
                        TextButton(
                            enabled = manualPath.isNotBlank(),
                            onClick = { submitManual() },
                        ) { Text(if (zh) "打开" else "Open") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolConsole(state: WorkspaceState, zh: Boolean, onAiAnalyze: (String) -> Unit) {
    val tools = state.tools; val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    val curDef = toolDefs.firstOrNull { it.key == state.activeTool }
    val tl = curDef?.let { if (zh) it.labelZh else it.labelEn } ?: ""

    Row(Modifier.fillMaxWidth().height(28.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        val bm = Modifier.height(22.dp); val bp = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        when (state.activeTool) {
            "decompile" -> {
                SmBtn(if (zh) "反编译" else "Dec", bm, bp, {
                    val loc = tools.decompileTarget.trim(); if (loc.isEmpty()) return@SmBtn
                    scope.launch { tools.decompileRunning = true; tools.decompileError = ""; tools.decompileResult = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzDecompile(tools.sharedWorkspaceId, "", loc, true) }.getOrNull() }
                        tools.decompileRunning = false
                        val text = if (r != null && r.optBoolean("ok", false)) r.optString("pseudocode").ifBlank { r.toString() }
                            else r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { r?.optString("error") ?: if (zh) "反编译失败" else "Failed" }
                        tools.addTab(tl, if (zh) "反编译" else "Decompile", text)
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.decompileTarget.isNotBlank() && !tools.decompileRunning, loading = tools.decompileRunning)
                SmBtn(if (zh) "函数" else "Fns", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "functions", "", 30) }.getOrNull() }
                    tools.addTab(tl, if (zh) "函数" else "Functions", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Disasm", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).disasm(tools.sharedWorkspaceId, "", "", 20, "", 0, 0, 4096, tools.disasmAddr.ifBlank { "main" }, null, "auto") }.getOrNull() }
                    tools.addTab(tl, "Disasm", r?.toString() ?: if (zh) "失败" else "failed")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.disasmAddr.isNotBlank())
                SmBtn("Hex", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, 0, 256) }.getOrNull() }
                    tools.addTab(tl, "Hex", r?.toString() ?: if (zh) "失败" else "failed")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "unpack" -> {
                SmBtn(if (zh) "分析" else "Analyze", bm, bp, { scope.launch { tools.unpackRunning = true
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                    tools.unpackRunning = false; tools.addTab(tl, if (zh) "分析" else "Analyze", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, loading = tools.unpackRunning)
                SmBtn("SO", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", ".so", 60) }.getOrNull() }
                    tools.addTab(tl, "SO", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("DEX", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "classes", "", 30) }.getOrNull() }
                    tools.addTab(tl, "DEX", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "soanalyze" -> {
                SmBtn(if (zh) "概览" else "Ovw", bm, bp, { scope.launch { tools.soAnalyzeRunning = true
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).overview(tools.sharedWorkspaceId) }.getOrNull() }
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzScanCrypto(tools.sharedWorkspaceId) }.getOrNull() }
                    tools.soAnalyzeRunning = false
                    val merged = JSONObject()
                    if (o != null) merged.put("overview", o)
                    if (c != null) merged.put("cryptoFindings", c.optJSONArray("cryptoFindings") ?: c.optJSONArray("findings") ?: c.optJSONArray("scans"))
                    tools.addTab(tl, if (zh) "概览" else "Overview", if (merged.length() > 0) merged.toString() else if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.soAnalyzeRunning, loading = tools.soAnalyzeRunning)
                SmBtn("Sec", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "sections", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "节区" else "Sections", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Imp", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "imports", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "导入" else "Imports", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Exp", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "dynsyms", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "导出" else "Exports", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("CFG", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzCfg(tools.sharedWorkspaceId, "", tools.decompileTarget.ifBlank { "main" }) }.getOrNull() }
                    tools.addTab(tl, "CFG", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // AI 深度分析：跳转 AI 对话页（MainActivity 挂载 DeepAiChatScreen 进行对话）
                SmBtn(if (zh) "AI 深度" else "AI Deep", bm, bp, {
                    val taskPath = state.currentTask()?.mainPath
                    if (taskPath.isNullOrBlank()) {
                        tools.addTab(tl, if (zh) "AI" else "AI", if (zh) "请先从文件/任务入口选择工作区后再发起 AI 深度分析" else "pick a workspace first")
                    } else {
                        onAiAnalyze(taskPath)
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "emulate" -> {
                SmBtn(if (zh) "模拟" else "Emu", bm, bp, { val sym = tools.emulateSymbol.trim(); if (sym.isEmpty()) return@SmBtn
                    scope.launch { tools.emulateRunning = true; tools.emulateError = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", sym, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateRunning = false
                        tools.addTab(tl, if (zh) "模拟" else "Emulate", r?.toString() ?: (tools.emulateError.ifBlank { if (zh) "失败" else "Failed" }))
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.emulateSymbol.isNotBlank() && !tools.emulateRunning, loading = tools.emulateRunning)
                SmBtn(if (zh) "寄存器" else "Regs", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", tools.emulateSymbol.ifBlank { "JNI_OnLoad" }, org.json.JSONArray(), true) }.getOrNull() }
                    tools.addTab(tl, if (zh) "寄存器" else "Registers", r?.toString() ?: (if (zh) "先模拟" else "emu first"))
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Mem", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).dumpMemory(tools.sharedWorkspaceId, "", 0L, 256) }.getOrNull() }
                    tools.addTab(tl, "Mem", r?.toString() ?: (if (zh) "先模拟" else "emu first"))
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "frida" -> {
                SmBtn(if (zh) "校验" else "Valid", bm, bp, { scope.launch {
                    val script = tools.fridaScript.trim()
                    val errors = mutableListOf<String>()
                    if (script.isBlank()) errors.add(if (zh) "脚本为空" else "empty script")
                    if (script.contains("Java.perform") && !script.contains("function")) errors.add(if (zh) "Java.perform 缺少回调" else "perform missing callback")
                    if (script.count { it == '{' } != script.count { it == '}' }) errors.add(if (zh) "花括号不匹配" else "brace mismatch")
                    if (script.count { it == '(' } != script.count { it == ')' }) errors.add(if (zh) "括号不匹配" else "paren mismatch")
                    val result = JSONObject()
                    if (errors.isEmpty()) { result.put("status", "✅ OK"); result.put("errors", JSONArray()) }
                    else { result.put("status", "❌ Error"); val e = JSONArray(); errors.forEach { e.put(it) }; result.put("errors", e) }
                    result.put("scriptLength", script.length); result.put("scriptPreview", script.take(200))
                    if (zh) result.put("tip", "连接设备需 root + frida-server 运行") else result.put("tip", "attach device requires root + frida-server")
                    tools.fridaStatus = result.toString(2); tools.addTab(tl, if (zh) "校验" else "Validate", tools.fridaStatus)
                } }, enabled = true)
                SmBtn(if (zh) "连接" else "Attach", bm, bp, {
                    val result = JSONObject()
                    result.put("status", if (zh) "⚠ 需要 root 权限" else "⚠ root required")
                    result.put("requirements", if (zh) "需要: root权限 + frida-server 运行在设备上" else "requires: root + frida-server running on device")
                    val steps = JSONArray()
                    if (zh) { steps.put("1. 确保设备已 root"); steps.put("2. adb push frida-server /data/local/tmp/"); steps.put("3. adb shell chmod 755 /data/local/tmp/frida-server"); steps.put("4. adb shell /data/local/tmp/frida-server &") }
                    else { steps.put("1. root device"); steps.put("2. adb push frida-server /data/local/tmp/"); steps.put("3. adb shell chmod 755 /data/local/tmp/frida-server"); steps.put("4. adb shell /data/local/tmp/frida-server &") }
                    result.put("steps", steps); tools.fridaStatus = result.toString(2); tools.addTab(tl, if (zh) "连接" else "Attach", tools.fridaStatus)
                }, enabled = true)
                SmBtn("Hook", bm, bp, {
                    val result = JSONObject(); result.put("status", if (zh) "⚠ 需先连接设备" else "⚠ attach first")
                    result.put("note", if (zh) "连接成功后，在此输入要 Hook 的类名和方法" else "after attach, enter class name and method to hook")
                    tools.fridaStatus = result.toString(2); tools.addTab(tl, "Hook", tools.fridaStatus)
                }, enabled = true)
            }
            "rebuild" -> {
                SmBtn(if (zh) "回编" else "Build", bm, bp, { scope.launch { tools.rebuildRunning = true
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                    val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                    tools.rebuildRunning = false
                    val merged = JSONObject()
                    if (c != null) merged.put("editCheck", c); if (b != null) merged.put("build", b); if (o != null) merged.put("outputs", o)
                    tools.addTab(tl, if (zh) "回编" else "Build", merged.toString())
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.rebuildRunning, loading = tools.rebuildRunning)
                SmBtn(if (zh) "补丁" else "Patch", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editAudit(tools.sharedWorkspaceId, "") }.getOrNull() }
                    tools.addTab(tl, if (zh) "补丁" else "Patch", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "符号" else "Sym", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "symbols", "", 60) }.getOrNull() }
                    tools.addTab(tl, if (zh) "符号" else "Symbols", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "字符串" else "Str", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 20) }.getOrNull() }
                    tools.addTab(tl, if (zh) "字符串" else "Strings", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "editor" -> {
                // 编辑工具：文本查看（读取字符串列表）
                SmBtn(if (zh) "文本" else "Text", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 30) }.getOrNull() }
                    tools.editorTextResult = r?.toString() ?: if (zh) "无" else "none"
                    tools.addTab(tl, if (zh) "文本" else "Text", tools.editorTextResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 编辑：读取地址数据展示可编辑格式
                SmBtn(if (zh) "编辑" else "Edit", bm, bp, { scope.launch {
                    val addr = tools.disasmAddr.ifBlank { "0x0" }
                    val read = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr, 0, 64) }.getOrNull() }
                    val hexData = read?.optString("hex") ?: read?.optString("data") ?: read?.optString("result") ?: ""
                    val edit = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editHex(tools.sharedWorkspaceId, "", addr, org.json.JSONArray(), false) }.getOrNull() }
                    val result = JSONObject()
                    result.put("address", addr); result.put("hexData", hexData)
                    val ascii = hexData.split(" ").filter { it.length == 2 }.map {
                        val c = it.toInt(16).toChar(); if (c.isISOControl() || c.code > 127) '.' else c.toString()
                    }.joinToString("")
                    result.put("ascii", ascii)
                    if (edit != null) result.put("editResult", edit)
                    result.put("instruction", if (zh) "在地址栏输入目标地址查看数据，输入十六进制字节修改" else "enter address to view data, enter hex bytes to modify")
                    tools.editorEditResult = result.toString(2); tools.addTab(tl, if (zh) "编辑" else "Edit", tools.editorEditResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 对比：对比当前文件两个地址的数据
                SmBtn(if (zh) "对比" else "Diff", bm, bp, { scope.launch {
                    val addr1 = tools.disasmAddr.ifBlank { "0x0" }
                    val addr2 = tools.decompileTarget.ifBlank { "0x100" }
                    val r1 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr1, 0, 64) }.getOrNull() }
                    val r2 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr2, 0, 64) }.getOrNull() }
                    val hex1 = r1?.optString("hex") ?: r1?.optString("data") ?: ""; val hex2 = r2?.optString("hex") ?: r2?.optString("data") ?: ""
                    val result = JSONObject()
                    result.put("对比地址1(地址栏)", addr1); result.put("对比地址2(对比地址栏)", addr2)
                    // 读取 hex1 和 hex2 的原始数据文本
                    val text1 = r1?.optString("text") ?: r1?.optString("ascii") ?: ""
                    val text2 = r2?.optString("text") ?: r2?.optString("ascii") ?: ""
                    result.put("data1", hex1); result.put("data2", hex2)
                    if (text1.isNotBlank()) result.put("text1", text1)
                    if (text2.isNotBlank()) result.put("text2", text2)
                    val diffBytes = mutableListOf<String>()
                    val bytes1 = hex1.replace(" ", "").replace("\n", ""); val bytes2 = hex2.replace(" ", "").replace("\n", "")
                    val minLen = minOf(bytes1.length, bytes2.length)
                    for (i in 0 until minLen / 2) {
                        if (bytes1.length >= i*2+2 && bytes2.length >= i*2+2) {
                            val b1 = bytes1.substring(i*2, i*2+2); val b2 = bytes2.substring(i*2, i*2+2)
                            if (b1 != b2) diffBytes.add("0x${i.toString(16).padStart(4, '0')}: $b1 → $b2")
                        }
                    }
                    result.put("diffCount", diffBytes.size)
                    val diffArr = JSONArray(); diffBytes.forEach { diffArr.put(it) }; result.put("diffs", diffArr)
                    result.put("match", bytes1 == bytes2)
                    tools.editorDiffResult = result.toString(2); tools.addTab(tl, if (zh) "对比" else "Diff", tools.editorDiffResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
        }
    }
}

@Composable
private fun SmBtn(label: String, modifier: Modifier, padding: PaddingValues, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !loading, contentPadding = padding, modifier = modifier, shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        if (loading) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp))
    }
}

@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    val tabs = tools.resultTabs; val selectedTab = tools.selectedTabIndex
    var detailMode by remember { mutableStateOf(false) }

    if (tabs.isNotEmpty()) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            // 顶栏：标签 + 简洁/详细切换
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedTab + 1}/${tabs.size}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(2.dp))
                    tabs.forEachIndexed { idx, tab ->
                        val isSel = idx == selectedTab
                        Surface(shape = RoundedCornerShape(4.dp), color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.clickable { tools.selectedTabIndex = idx }.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.size(2.dp))
                                IconButton(onClick = { tools.closeTab(idx) }, modifier = Modifier.size(14.dp)) { Icon(Icons.Filled.Close, contentDescription = "close", modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    IconButton(onClick = { tools.clearTabs() }, modifier = Modifier.size(14.dp)) { Text("×", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.size(4.dp))
                Surface(onClick = { detailMode = !detailMode }, shape = RoundedCornerShape(4.dp), color = if (detailMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(if (detailMode) (if (zh) "详细" else "Detail") else (if (zh) "简洁" else "Simple"), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = if (detailMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(4.dp))
            val current = tabs.getOrNull(selectedTab) ?: return
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (detailMode) StructuredJsonView(current.text, zh)
                else TextSummary(current.text, zh)
            }
            // 底部导航
            Spacer(Modifier.size(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (selectedTab > 0) tools.selectedTabIndex-- }, enabled = selectedTab > 0, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), shape = RoundedCornerShape(6.dp)) { Text("◀ " + if (zh) "上一页" else "Prev", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) }
                Spacer(Modifier.size(12.dp))
                Text("${selectedTab + 1} / ${tabs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.size(12.dp))
                Button(onClick = { if (selectedTab < tabs.size - 1) tools.selectedTabIndex++ }, enabled = selectedTab < tabs.size - 1, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), shape = RoundedCornerShape(6.dp)) { Text(if (zh) "下一页" else "Next" + " ▶", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) }
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (zh) "暂无结果，请执行分析工具" else "No results yet, run a tool", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================
// 简洁模式：纯文字完整描述
// ============================================================

@Composable
private fun TextSummary(text: String, zh: Boolean) {
    if (text.isBlank()) return
    val json = runCatching { JSONObject(text) }.getOrNull()
    if (json == null) { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp); return }
    val sb = StringBuilder()
    describeJson(json, sb, zh, 0)
    Text(sb.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
}

/** 将任意 JSON 描述为文字，不产生问号或省略号 */
private fun describeJson(json: JSONObject, sb: StringBuilder, zh: Boolean, indent: Int) {
    val pad = "  ".repeat(indent)

    // 1. overview → 文字描述
    val ov = json.optJSONObject("overview")
    if (ov != null) { describeOverview(ov, json, sb, zh); return }

    // 2. items 数组 → 列表
    val items = json.optJSONArray("items")
    if (items != null && items.length() > 0) {
        ItemsTable(items, zh)
        return
    }
    val cryptoArr = json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings") ?: json.optJSONArray("scans")
    if (cryptoArr != null && cryptoArr.length() > 0) { StructuredCryptoCard(cryptoArr, zh); return }
    val keys = json.keys().asSequence().filter { it != "ok" && it != "pagination" }.toList()
    if (keys.isEmpty()) { Text(json.toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface); return }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        keys.forEach { k -> val v = json.opt(k); val vStr = when (v) { is JSONObject -> "{${v.length()} fields}\n${v.toString(2).take(500)}"; is org.json.JSONArray -> "[${v.length()} items]\n${(0 until v.length()).joinToString("\n") { i -> "  [$i] ${v.opt(i)}" }.take(500)}"; null -> "—"; else -> v.toString() }; Kv(k, vStr) }
    }
}

@Composable
private fun ItemsTable(items: JSONArray, zh: Boolean) {
    // 收集标量列(排除嵌套对象/数组), 最多 8 列
    val columns = remember(items.length()) {
        val cols = LinkedHashSet<String>()
        for (i in 0 until minOf(items.length(), 50)) {
            val o = items.optJSONObject(i) ?: continue
            val ks = o.keys()
            while (ks.hasNext() && cols.size < 8) {
                val k = ks.next()
                val v = o.opt(k)
                if (v != null && v !is JSONObject && v !is org.json.JSONArray) cols.add(k)
            }
            if (cols.size >= 8) break
        }
        cols.toList()
    }
    if (columns.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until minOf(items.length(), 300)) {
                val item = items.opt(i)
                val line = when (item) {
                    is JSONObject -> item.toString()
                    is org.json.JSONArray -> "[${item.length()} sub-items]"
                    else -> item.toString()
                }
                Text("• $line", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val colW = 130.dp
    val rows = minOf(items.length(), 300)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(if (zh) "共 ${items.length()} 项 · 表格视图" else "${items.length()} items · table", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
        Row(Modifier.fillMaxWidth()) {
            columns.forEach { c -> Text(c, modifier = Modifier.width(colW).padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        androidx.compose.material3.HorizontalDivider()
        for (i in 0 until rows) {
            val o = items.optJSONObject(i)
            if (o == null) {
                Text("• ${items.opt(i)}", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                continue
            }
            Row(Modifier.fillMaxWidth()) {
                columns.forEach { c ->
                    val v = o.opt(c)
                    Text(v?.toString() ?: "—", modifier = Modifier.width(colW).padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (items.length() > rows) Text(if (zh) "… 仅显示前 $rows 项（共 ${items.length()} 项）" else "… showing first $rows of ${items.length()}", modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StructuredCryptoCard(cryptoArr: JSONArray, zh: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (zh) "共 ${cryptoArr.length()} 项发现" else "${cryptoArr.length()} findings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (i in 0 until cryptoArr.length()) {
            val c = cryptoArr.optJSONObject(i) ?: continue; val name = c.optString("name", c.optString("algorithm", c.optString("type", "?"))); val count = c.optInt("count", c.optInt("matches", 0)); val detail = c.optString("detail", c.optString("description", ""))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("• $name", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface); if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (detail.isNotBlank()) Text("  $detail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun JsonKeyValues(json: JSONObject, zh: Boolean, keys: List<String>) {
    keys.filter { json.has(it) && !json.isNull(it) }.forEach { k -> val v = json.opt(k); val label = when (k) { "fileName" -> if (zh) "名称" else "Name"; "size" -> if (zh) "大小" else "Size"; "architecture" -> if (zh) "架构" else "Arch"; "bits" -> if (zh) "位数" else "Bits"; "elfType" -> if (zh) "类型" else "Type"; "entryPoint" -> if (zh) "入口" else "Entry"; "endian" -> if (zh) "字节序" else "Endian"; "sha256" -> "SHA256"; "sha1" -> "SHA1"; "md5" -> "MD5"; "compiler" -> if (zh) "编译器" else "Compiler"; "packer" -> if (zh) "加壳" else "Packer"; else -> k }; val vStr = when (v) { is Number -> { if (k == "size") fmtBytes(v.toLong()) else v.toString() }; is Boolean -> if (v) (if (zh) "是" else "Yes") else (if (zh) "否" else "No"); else -> v.toString() }; Kv(label, vStr) }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f))) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary); content() }
    }
}

@Composable
private fun Kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun MetricRowFull(vararg pairs: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { pairs.forEach { (label, value) -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) } } }
}

private fun fmtBytes(v: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB"); var u = 0; var value = v.toDouble()
    while (value >= 1024 && u < units.size - 1) { value /= 1024; u++ }
    return "%.1f %s".format(value, units[u])
}