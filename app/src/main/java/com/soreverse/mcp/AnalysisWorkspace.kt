package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

@Composable
internal fun AnalysisWorkspace(
    t: UiText,
    state: WorkspaceState,
    context: android.content.Context,
    onOpenTask: () -> Unit,
) {
    val zh = t.zh
    val tools = state.tools
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
        var toolsExpanded by remember { mutableStateOf(false) }
        // 顶部栏：任务选择 + 工具展开按钮 + 工作区选择（一行紧凑）
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
            // 工具展开/收回按钮（显示当前工具名）
            val curTool = toolDefs.firstOrNull { it.key == state.activeTool }
            Button(onClick = { toolsExpanded = !toolsExpanded },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                shape = RoundedCornerShape(6.dp)) {
                Text(if (zh) (curTool?.labelZh ?: "工具") else (curTool?.labelEn ?: "Tools"), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
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
        // 主体：控制台 + 结果流
        Column(Modifier.fillMaxSize()) {
            // 控制台（极紧凑水平条）
            Surface(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                ToolConsole(state, zh)
            }
            Spacer(Modifier.size(4.dp))
            // 结果流（占剩余空间）
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            ) {
                ResultStream(tools, zh)
            }
        }
    }
}

@Composable
private fun WorkspacePicker(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            tools.opening = true; tools.openError = ""
            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).open(uri.toString(), false) }.getOrNull() }
            if (r != null && r.optBoolean("ok", false)) {
                tools.sharedWorkspaceId = r.optString("workspaceId")
                tools.sharedSoName = r.optString("soFileName").ifBlank { r.optString("fileName") }
                val fname = tools.sharedSoName.ifBlank { uri.lastPathSegment ?: "file" }
                val isApk = fname.endsWith(".apk", true) || uri.lastPathSegment?.endsWith(".apk", true) == true
                state.createTask(fname, if (isApk) "apk" else "so", uri.toString(), fname)
                tools.opening = false
                tools.clearTabs()
            } else {
                tools.opening = false
                tools.openError = r?.optString("error").orEmpty().ifBlank { if (zh) "打开失败" else "Open failed" }
            }
        }
    }
    Button(
        onClick = { picker.launch(arrayOf("application/octet-stream", "application/zip", "application/vnd.android.package-archive", "*/*")) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        enabled = !tools.opening,
        shape = RoundedCornerShape(6.dp),
    ) {
        if (tools.opening) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.size(4.dp))
            Text(if (zh) "加载中…" else "Loading…", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
        } else {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.size(4.dp))
        Text((tools.sharedSoName.ifBlank { if (zh) "选文件" else "Open" }).take(12), style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
    }
    if (tools.openError.isNotBlank()) {
        Text(tools.openError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
    }
}

/** 获取当前工具的中文/英文标签名 */
private fun currentToolLabel(tools: ToolPagesState, zh: Boolean): String {
    val cur = toolDefs.firstOrNull { it.key == (tools.resultTabs.firstOrNull()?.id?.substringBefore("_") ?: "") }
    return if (zh) (cur?.labelZh ?: "工具") else (cur?.labelEn ?: "Tool")
}

@Composable
private fun ToolConsole(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toolLabel = { def: ToolDef -> if (zh) def.labelZh else def.labelEn }
    val curDef = toolDefs.firstOrNull { it.key == state.activeTool }
    val tl = curDef?.let { if (zh) it.labelZh else it.labelEn } ?: ""

    Row(Modifier.fillMaxWidth().height(28.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        val bm = Modifier.height(22.dp)
        val bp = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        when (state.activeTool) {
            "decompile" -> {
                OutlinedTextField(value = tools.decompileTarget, onValueChange = { tools.decompileTarget = it },
                    label = { Text(if (zh) "符号" else "Sym") }, singleLine = true,
                    modifier = Modifier.width(70.dp).height(22.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), shape = RoundedCornerShape(4.dp))
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
            }
            "unpack" -> {
                SmBtn(if (zh) "分析" else "Analyze", bm, bp, { scope.launch { tools.unpackRunning = true
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                    tools.unpackRunning = false
                    tools.addTab(tl, if (zh) "分析" else "Analyze", r?.toString() ?: if (zh) "无" else "none")
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
                    val ovText = if (o?.optBoolean("ok", false) == true) o.toString() else ""
                    val cryptoText = if (c?.optBoolean("ok", false) == true) c.toString() else ""
                    tools.addTab(tl, if (zh) "概览" else "Overview", ovText)
                    if (cryptoText.isNotBlank()) tools.addTab(tl, if (zh) "加密" else "Crypto", cryptoText)
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
            }
            "emulate" -> {
                OutlinedTextField(value = tools.emulateSymbol, onValueChange = { tools.emulateSymbol = it },
                    label = { Text(if (zh) "符号" else "Sym") }, singleLine = true,
                    modifier = Modifier.width(60.dp).height(22.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), shape = RoundedCornerShape(4.dp))
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
                SmBtn(if (zh) "校验" else "Valid", bm, bp, { scope.launch { tools.fridaStatus = if (zh) "校验中..." else "Validating..."
                    // 先校验脚本语法（JSON 格式检查 + 基本 JS 语法检查）
                    val script = tools.fridaScript.trim()
                    val errors = mutableListOf<String>()
                    if (script.isBlank()) errors.add(if (zh) "脚本为空" else "empty script")
                    if (script.contains("Java.perform") && !script.contains("function")) errors.add(if (zh) "Java.perform 缺少回调" else "perform missing callback")
                    if (script.count { it == '{' } != script.count { it == '}' }) errors.add(if (zh) "花括号不匹配" else "brace mismatch")
                    if (script.count { it == '(' } != script.count { it == ')' }) errors.add(if (zh) "括号不匹配" else "paren mismatch")
                    tools.fridaStatus = if (errors.isEmpty()) {
                        if (zh) "✅ 脚本语法通过" else "✅ Script valid"
                    } else {
                        "❌ ${errors.joinToString("; ")}"
                    }
                    tools.addTab(tl, if (zh) "校验" else "Validate", tools.fridaStatus)
                } }, enabled = true)
                SmBtn(if (zh) "连接" else "Attach", bm, bp, {
                    tools.fridaStatus = if (zh) "⚠ 需 root + frida-server 运行" else "⚠ need root + frida-server"
                    tools.addTab(tl, if (zh) "连接" else "Attach", tools.fridaStatus)
                }, enabled = true)
                SmBtn("Hook", bm, bp, {
                    tools.fridaStatus = if (zh) "⚠ 需先连接设备" else "⚠ attach first"
                    tools.addTab(tl, "Hook", tools.fridaStatus)
                }, enabled = true)
            }
            "rebuild" -> {
                SmBtn(if (zh) "回编" else "Build", bm, bp, { scope.launch { tools.rebuildRunning = true; tools.rebuildError = ""; tools.rebuildResult = ""
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                    val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                    tools.rebuildRunning = false
                    if (c != null) tools.addTab(tl, if (zh) "检查" else "Check", c.toString())
                    if (b != null) tools.addTab(tl, if (zh) "回编" else "Build", b.toString())
                    if (o != null) tools.addTab(tl, if (zh) "输出" else "Outputs", o.toString())
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
                OutlinedTextField(value = tools.disasmAddr, onValueChange = { tools.disasmAddr = it },
                    label = { Text(if (zh) "地址" else "Addr") }, singleLine = true,
                    modifier = Modifier.width(55.dp).height(22.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), shape = RoundedCornerShape(4.dp))
                SmBtn("Hex", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, 0, 256) }.getOrNull() }
                    tools.editorHexResult = r?.toString() ?: if (zh) "失败" else "failed"
                    tools.addTab(tl, "Hex", tools.editorHexResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "文本" else "Text", bm, bp, { scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 30) }.getOrNull() }
                    tools.editorTextResult = r?.toString() ?: if (zh) "无" else "none"
                    tools.addTab(tl, if (zh) "文本" else "Text", tools.editorTextResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "编辑" else "Edit", bm, bp, { scope.launch {
                    // 编辑功能：用 editHex 先读取该地址处的数据，然后模拟编辑操作
                    val addr = tools.disasmAddr.ifBlank { "0x0" }
                    // 先读取
                    val read = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr, 0, 32) }.getOrNull() }
                    val hexData = read?.optString("hex") ?: read?.optString("data") ?: read?.optString("result") ?: ""
                    // 基于读取的数据构造一个可编辑的展示
                    val edit = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editHex(tools.sharedWorkspaceId, "", addr, org.json.JSONArray(), false) }.getOrNull() }
                    val result = JSONObject()
                    result.put("address", addr)
                    result.put("readData", hexData.ifBlank { (if (zh) "无数据" else "no data") })
                    if (edit != null) result.put("editResult", edit)
                    result.put("note", if (zh) "在地址处输入要修改的十六进制字节（如 AA BB CC）" else "enter hex bytes to patch (e.g. AA BB CC)")
                    tools.editorEditResult = result.toString(2)
                    tools.addTab(tl, if (zh) "编辑" else "Edit", tools.editorEditResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "对比" else "Diff", bm, bp, { scope.launch {
                    // 对比功能：读取两个地址处的数据并进行对比展示
                    val addr1 = tools.disasmAddr.ifBlank { "0x0" }
                    val addr2 = tools.decompileTarget.ifBlank { "0x100" }
                    val r1 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr1, 0, 64) }.getOrNull() }
                    val r2 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr2, 0, 64) }.getOrNull() }
                    val hex1 = r1?.optString("hex") ?: r1?.optString("data") ?: r1?.optString("result") ?: ""
                    val hex2 = r2?.optString("hex") ?: r2?.optString("data") ?: r2?.optString("result") ?: ""
                    val result = JSONObject()
                    result.put("addr1", addr1)
                    result.put("addr2", addr2)
                    // 对比：逐字节找出不同
                    val diffBytes = mutableListOf<String>()
                    val bytes1 = hex1.replace(" ", "").replace("\n", "")
                    val bytes2 = hex2.replace(" ", "").replace("\n", "")
                    val minLen = minOf(bytes1.length, bytes2.length)
                    for (i in 0 until minLen / 2) {
                        if (bytes1.length >= i*2+2 && bytes2.length >= i*2+2) {
                            val b1 = bytes1.substring(i*2, i*2+2)
                            val b2 = bytes2.substring(i*2, i*2+2)
                            if (b1 != b2) diffBytes.add("0x${i.toString(16).padStart(4, '0')}: $b1 → $b2")
                        }
                    }
                    result.put("diffCount", diffBytes.size)
                    val diffArr = JSONArray()
                    diffBytes.take(30).forEach { diffArr.put(it) }
                    result.put("diffs", diffArr)
                    if (bytes1 != bytes2) result.put("match", false) else result.put("match", true)
                    tools.editorDiffResult = result.toString(2)
                    tools.addTab(tl, if (zh) "对比" else "Diff", tools.editorDiffResult)
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
        }
    }
}

@Composable
private fun SmBtn(label: String, modifier: Modifier, padding: PaddingValues, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        contentPadding = padding,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp))
        }
    }
}

@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    val tabs = tools.resultTabs
    val selectedTab = tools.selectedTabIndex

    if (tabs.isNotEmpty()) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            // 标签导航栏（可横向滚动，支持关闭）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selectedTab + 1}/${tabs.size}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(2.dp))
                tabs.forEachIndexed { idx, tab ->
                    val isSel = idx == selectedTab
                    Surface(
                        modifier = Modifier,
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(Modifier.clickable { tools.selectedTabIndex = idx }.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(2.dp))
                            IconButton(
                                onClick = { tools.closeTab(idx) },
                                modifier = Modifier.size(14.dp),
                            ) { Icon(Icons.Filled.Close, contentDescription = "close", modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                // 清除所有标签按钮
                IconButton(
                    onClick = { tools.clearTabs() },
                    modifier = Modifier.size(14.dp),
                ) { Text("×", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.size(4.dp))
            // 当前选中的标签页内容
            val current = tabs.getOrNull(selectedTab) ?: return
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                DetailCardFull(current.label, current.text, zh)
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (zh) "暂无结果，请执行分析工具" else "No results yet, run a tool", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 完整展示结果：可滚动全文，无省略号 */
@Composable
private fun DetailCardFull(title: String, text: String, zh: Boolean) {
    if (text.isBlank()) return
    val json = runCatching { JSONObject(text) }.getOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(6.dp))
            if (json == null) {
                // 纯文本：完整显示，无省略号
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
            } else {
                // JSON → 结构化展示，无省略号
                StructuredJsonView(json, zh)
            }
        }
    }
}

/** 结构化 JSON 展示：完整展示所有字段，无省略号 */
@Composable
private fun StructuredJsonView(json: JSONObject, zh: Boolean) {
    // 1. overview 对象 → 卡片式展示
    val ov = json.optJSONObject("overview")
    if (ov != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 基础属性
            SectionCard(if (zh) "📦 基础属性" else "📦 Basics") {
                JsonKeyValues(ov, zh, listOf("fileName", "size", "architecture", "bits", "elfType", "entryPoint", "endian", "sha256", "sha1", "md5", "compiler", "packer"))
            }
            // 计数指标
            val counts = listOf("sectionCount", "functionCount", "symbolCount", "stringCount", "importCount", "exportCount")
                .filter { ov.has(it) }
            if (counts.isNotEmpty()) {
                SectionCard(if (zh) "📊 结构与规模" else "📊 Structure") {
                    MetricRowFull(*counts.map { k -> (if (zh) k.replace("Count","") else k.replace("Count","")) to ov.optInt(k, 0).toString() }.toTypedArray())
                }
            }
            // 安全特性
            val sec = ov.optJSONArray("securityFeatures")
            if (sec != null && sec.length() > 0) {
                SectionCard(if (zh) "🔒 安全特性" else "🔒 Security") {
                    for (i in 0 until sec.length()) {
                        val s = sec.optJSONObject(i) ?: continue
                        val label = s.optString("label", s.optString("id", "?"))
                        val active = s.optBoolean("active", false)
                        val desc = s.optString("description", "")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (active) (if (zh) "✓ 启用" else "✓ Yes") else (if (zh) "✗ 未启用" else "✗ No"),
                                style = MaterialTheme.typography.bodySmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        if (desc.isNotBlank()) {
                            Text("  $desc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
            }
            // 加密特征
            val crypto = ov.optJSONArray("cryptoFindings") ?: json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings")
            if (crypto != null && crypto.length() > 0) {
                SectionCard(if (zh) "🔐 加密特征" else "🔐 Crypto") {
                    for (i in 0 until crypto.length()) {
                        val c = crypto.optJSONObject(i) ?: continue
                        val name = c.optString("name", c.optString("algorithm", "?"))
                        val count = c.optInt("count", c.optInt("matches", 0))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            // 其他未展示的字段
            val shown = setOf("fileName", "size", "architecture", "bits", "elfType", "entryPoint", "endian", "sha256", "sha1", "md5", "compiler", "packer",
                "sectionCount", "functionCount", "symbolCount", "stringCount", "importCount", "exportCount",
                "securityFeatures", "cryptoFindings", "findings", "difficulty", "ok", "items")
            val extra = ov.keys().asSequence().filter { it !in shown }.toList()
            if (extra.isNotEmpty()) {
                SectionCard(if (zh) "📋 其他信息" else "📋 Others") {
                    JsonKeyValues(ov, zh, extra)
                }
            }
        }
        return
    }

    // 2. items 数组 → 列表展示，完整显示
    val items = json.optJSONArray("items")
    if (items != null && items.length() > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (zh) "共 ${items.length()} 项" else "${items.length()} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (i in 0 until items.length()) {
                val item = items.opt(i)
                val line = when (item) {
                    is JSONObject -> {
                        val name = item.optString("name")
                        val addr = item.optString("address")
                        if (name.isNotBlank() && addr.isNotBlank()) "$name  @ $addr"
                        else if (name.isNotBlank()) name
                        else item.optString("address", item.toString())
                    }
                    is org.json.JSONArray -> "[${item.length()}] ${item.joinToString(", ").take(120)}"
                    else -> item.toString()
                }
                Text("• $line", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        return
    }

    // 3. cryptoFindings/findings/scans 数组
    val cryptoArr = json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings") ?: json.optJSONArray("scans")
    if (cryptoArr != null && cryptoArr.length() > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (zh) "共 ${cryptoArr.length()} 项发现" else "${cryptoArr.length()} findings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (i in 0 until cryptoArr.length()) {
                val c = cryptoArr.optJSONObject(i) ?: continue
                val name = c.optString("name", c.optString("algorithm", c.optString("type", "?")))
                val count = c.optInt("count", c.optInt("matches", 0))
                val detail = c.optString("detail", c.optString("description", ""))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("• $name", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (detail.isNotBlank()) {
                    Text("  $detail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
        return
    }

    // 4. 通用：所有键值完整展示
    val keys = json.keys().asSequence().filter { it != "ok" && it != "pagination" }.toList()
    if (keys.isEmpty()) {
        Text(json.toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        keys.forEach { k ->
            val v = json.opt(k)
            val vStr = when (v) {
                is JSONObject -> "{${v.length()} fields}\n${v.toString(2).take(500)}"
                is org.json.JSONArray -> "[${v.length()} items]\n${(0 until v.length()).joinToString("\n") { i -> "  [$i] ${v.opt(i)}" }.take(500)}"
                null -> "—"
                else -> v.toString()
            }
            Kv(k, vStr)
        }
    }
}

/** 从 JSON 对象中提取指定键值对 */
@Composable
private fun JsonKeyValues(json: JSONObject, zh: Boolean, keys: List<String>) {
    keys.filter { json.has(it) && !json.isNull(it) }.forEach { k ->
        val v = json.opt(k)
        val label = when (k) {
            "fileName" -> if (zh) "名称" else "Name"
            "size" -> if (zh) "大小" else "Size"
            "architecture" -> if (zh) "架构" else "Arch"
            "bits" -> if (zh) "位数" else "Bits"
            "elfType" -> if (zh) "类型" else "Type"
            "entryPoint" -> if (zh) "入口" else "Entry"
            "endian" -> if (zh) "字节序" else "Endian"
            "sha256" -> "SHA256"
            "sha1" -> "SHA1"
            "md5" -> "MD5"
            "compiler" -> if (zh) "编译器" else "Compiler"
            "packer" -> if (zh) "加壳" else "Packer"
            else -> k
        }
        val vStr = when (v) {
            is Number -> {
                if (k == "size") formatBytes(v.toLong()) else v.toString()
            }
            is Boolean -> if (v) (if (zh) "是" else "Yes") else (if (zh) "否" else "No")
            else -> v.toString()
        }
        Kv(label, vStr)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 8, overflow = TextOverflow.Visible)
    }
}

@Composable
private fun MetricRowFull(vararg pairs: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pairs.forEach { (label, value) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}

private fun formatBytes(v: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var u = 0
    var value = v.toDouble()
    while (value >= 1024 && u < units.size - 1) { value /= 1024; u++ }
    return "%.1f %s".format(value, units[u])
}