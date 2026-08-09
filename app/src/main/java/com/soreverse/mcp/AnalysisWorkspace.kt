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
        Column(Modifier.fillMaxSize()) {
            if (state.activeTool.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) { ToolConsole(state, zh) }
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

@Composable
private fun WorkspacePicker(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            tools.opening = true; tools.openError = ""
            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).open(uri.toString(), false) }.getOrNull() }
            if (r != null && r.optBoolean("ok", false)) {
                tools.sharedWorkspaceId = r.optString("workspaceId"); tools.sharedSoName = r.optString("soFileName").ifBlank { r.optString("fileName") }
                val fname = tools.sharedSoName.ifBlank { uri.lastPathSegment ?: "file" }
                state.createTask(fname, if (fname.endsWith(".apk", true)) "apk" else "so", uri.toString(), fname)
                tools.opening = false; tools.clearTabs()
            } else { tools.opening = false; tools.openError = r?.optString("error").orEmpty().ifBlank { if (zh) "打开失败" else "Open failed" } }
        }
    }
    Button(onClick = { picker.launch(arrayOf("application/octet-stream","application/zip","application/vnd.android.package-archive","*/*")) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), enabled = !tools.opening, shape = RoundedCornerShape(6.dp)) {
        if (tools.opening) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(4.dp)); Text(if (zh) "加载中…" else "Loading…", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) }
        else { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.size(4.dp)); Text((tools.sharedSoName.ifBlank { if (zh) "选文件" else "Open" }).take(12), style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
    }
    if (tools.openError.isNotBlank()) Text(tools.openError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
}

/** 引擎调用辅助函数 */
private fun <T> engineCall(scope: kotlinx.coroutines.CoroutineScope, block: suspend () -> T, onResult: (T) -> Unit) {
    scope.launch { onResult(withContext(Dispatchers.IO) { block() }) }
}

@Composable
private fun ToolConsole(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools; val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    val curDef = toolDefs.firstOrNull { it.key == state.activeTool }
    val tl = curDef?.let { if (zh) it.labelZh else it.labelEn } ?: ""

    Row(Modifier.fillMaxWidth().height(28.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        val bm = Modifier.height(22.dp); val bp = PaddingValues(horizontal = 4.dp, vertical = 0.dp)

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
                SmBtn(if (zh) "函数" else "Fns", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "functions", "", 30) } { tools.addTab(tl, if (zh) "函数" else "Functions", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Disasm", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).disasm(tools.sharedWorkspaceId, "", "", 20, "", 0, 0, 4096, tools.disasmAddr.ifBlank { "main" }, null, "auto") } { tools.addTab(tl, "Disasm", it?.toString() ?: if (zh) "失败" else "failed") } }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.disasmAddr.isNotBlank())
                // 把编辑下的 Hex 移到这里
                SmBtn("Hex", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, 0, 256) } { tools.addTab(tl, "Hex", it?.toString() ?: if (zh) "失败" else "failed") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "unpack" -> {
                SmBtn(if (zh) "分析" else "Analyze", bm, bp, { scope.launch { tools.unpackRunning = true
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                    tools.unpackRunning = false; tools.addTab(tl, if (zh) "分析" else "Analyze", r?.toString() ?: if (zh) "无" else "none")
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, loading = tools.unpackRunning)
                SmBtn("SO", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", ".so", 60) } { tools.addTab(tl, "SO", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("DEX", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "classes", "", 30) } { tools.addTab(tl, "DEX", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 脱壳功能：提取SO
                SmBtn(if (zh) "提取SO" else "Extract", bm, bp, { scope.launch { tools.unpackRunning = true
                    // 调用 extractSo 或 unpack 真正脱壳
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", ".so", 60) }.getOrNull() }
                    val result = JSONObject()
                    if (r != null) {
                        val items = r.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i) ?: continue
                                val name = item.optString("name")
                                val path = item.optString("path", item.optString("filePath", ""))
                                if (name.endsWith(".so", true)) {
                                    result.append("soFiles", JSONObject().put("name", name).put("path", path))
                                }
                            }
                        }
                    }
                    result.put("note", if (zh) "以上为可提取的SO文件列表，需root或MT管理器提取" else "SO files listed above, requires root or MT Manager to extract")
                    tools.unpackRunning = false; tools.addTab(tl, if (zh) "提取SO" else "Extract", result.toString(2))
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, loading = tools.unpackRunning)
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
                SmBtn("Sec", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "sections", "", 60) } { tools.addTab(tl, if (zh) "节区" else "Sections", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Imp", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "imports", "", 60) } { tools.addTab(tl, if (zh) "导入" else "Imports", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Exp", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "dynsyms", "", 60) } { tools.addTab(tl, if (zh) "导出" else "Exports", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("CFG", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).rzCfg(tools.sharedWorkspaceId, "", tools.decompileTarget.ifBlank { "main" }) } { tools.addTab(tl, "CFG", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
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
                SmBtn(if (zh) "寄存器" else "Regs", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", tools.emulateSymbol.ifBlank { "JNI_OnLoad" }, org.json.JSONArray(), true) } { tools.addTab(tl, if (zh) "寄存器" else "Registers", it?.toString() ?: (if (zh) "先模拟" else "emu first")) } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Mem", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).dumpMemory(tools.sharedWorkspaceId, "", 0L, 256) } { tools.addTab(tl, "Mem", it?.toString() ?: (if (zh) "先模拟" else "emu first")) } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "frida" -> {
                SmBtn(if (zh) "校验" else "Valid", bm, bp, { scope.launch {
                    val script = tools.fridaScript.trim()
                    val errors = mutableListOf<String>()
                    if (script.isBlank()) errors.add(if (zh) "脚本为空" else "empty script")
                    if (script.contains("Java.perform") && !script.contains("function")) errors.add(if (zh) "Java.perform 缺少回调" else "perform missing callback")
                    if (script.count { it == '{' } != script.count { it == '}' }) errors.add(if (zh) "花括号不匹配" else "brace mismatch")
                    if (script.count { it == '(' } != script.count { it == ')' }) errors.add(if (zh) "括号不匹配" else "paren mismatch")
                    val status = if (errors.isEmpty()) (if (zh) "✅ 脚本语法通过" else "✅ Script valid") else "❌ ${errors.joinToString("; ")}"
                    tools.fridaStatus = status
                    // 结构化输出校验结果
                    val result = JSONObject()
                    result.put("status", if (errors.isEmpty()) "✅ OK" else "❌ Error")
                    result.put("errors", if (errors.isEmpty()) JSONArray() else JSONArray(errors))
                    result.put("scriptLength", script.length)
                    result.put("scriptPreview", script.take(200))
                    if (zh) result.put("tip", "连接设备需 root + frida-server 运行") else result.put("tip", "attach device requires root + frida-server")
                    tools.addTab(tl, if (zh) "校验" else "Validate", result.toString(2))
                } }, enabled = true)
                SmBtn(if (zh) "连接" else "Attach", bm, bp, {
                    val result = JSONObject()
                    result.put("status", if (zh) "⚠ 需要 root 权限" else "⚠ root required")
                    result.put("requirements", if (zh) "需要: root权限 + frida-server 运行在设备上" else "requires: root + frida-server running on device")
                    result.put("steps", if (zh) "1. 确保设备已 root\n2. adb push frida-server /data/local/tmp/\n3. adb shell chmod 755 /data/local/tmp/frida-server\n4. adb shell /data/local/tmp/frida-server &" else "1. root device\n2. adb push frida-server /data/local/tmp/\n3. adb shell chmod 755 /data/local/tmp/frida-server\n4. adb shell /data/local/tmp/frida-server &")
                    tools.addTab(tl, if (zh) "连接" else "Attach", result.toString(2))
                }, enabled = true)
                SmBtn("Hook", bm, bp, {
                    val result = JSONObject()
                    result.put("status", if (zh) "⚠ 需先连接设备" else "⚠ attach first")
                    result.put("note", if (zh) "连接成功后，在此输入要 Hook 的类名和方法" else "after attach, enter class name and method to hook")
                    tools.addTab(tl, "Hook", result.toString(2))
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
                SmBtn(if (zh) "补丁" else "Patch", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).editAudit(tools.sharedWorkspaceId, "") } { tools.addTab(tl, if (zh) "补丁" else "Patch", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "符号" else "Sym", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "symbols", "", 60) } { tools.addTab(tl, if (zh) "符号" else "Symbols", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "字符串" else "Str", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 20) } { tools.addTab(tl, if (zh) "字符串" else "Strings", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "editor" -> {
                OutlinedTextField(value = tools.disasmAddr, onValueChange = { tools.disasmAddr = it },
                    label = { Text(if (zh) "地址" else "Addr") }, singleLine = true,
                    modifier = Modifier.width(55.dp).height(22.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), shape = RoundedCornerShape(4.dp))
                // 编辑工具：文本查看（读取字符串或指定地址内容）
                SmBtn(if (zh) "文本" else "Text", bm, bp, { engineCall(scope) { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 30) } { tools.addTab(tl, if (zh) "文本" else "Text", it?.toString() ?: if (zh) "无" else "none") } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 编辑功能：读取地址数据，展示可编辑格式
                SmBtn(if (zh) "编辑" else "Edit", bm, bp, { scope.launch {
                    val addr = tools.disasmAddr.ifBlank { "0x0" }
                    val read = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr, 0, 64) }.getOrNull() }
                    val hexData = read?.optString("hex") ?: read?.optString("data") ?: read?.optString("result") ?: ""
                    val edit = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editHex(tools.sharedWorkspaceId, "", addr, org.json.JSONArray(), false) }.getOrNull() }
                    val result = JSONObject()
                    result.put("address", addr)
                    result.put("hexData", hexData)
                    result.put("ascii", hexData.split(" ").filter { it.length == 2 }.map {
                        val c = it.toInt(16).toChar(); if (c.isISOControl() || c.code > 127) '.' else c.toString()
                    }.joinToString(""))
                    if (edit != null) result.put("editResult", edit)
                    result.put("editMode", if (zh) "编辑模式" else "Edit Mode")
                    result.put("instruction", if (zh) "在地址栏输入目标地址，查看数据后输入十六进制字节修改" else "enter address to view data, then enter hex bytes to modify")
                    tools.addTab(tl, if (zh) "编辑" else "Edit", result.toString(2))
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                // 对比功能：选择与当前文件对比的目标
                SmBtn(if (zh) "对比" else "Diff", bm, bp, { scope.launch {
                    val addr1 = tools.disasmAddr.ifBlank { "0x0" }
                    val addr2 = tools.decompileTarget.ifBlank { "0x100" }
                    val r1 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr1, 0, 64) }.getOrNull() }
                    val r2 = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", addr2, 0, 64) }.getOrNull() }
                    val hex1 = r1?.optString("hex") ?: r1?.optString("data") ?: ""
                    val hex2 = r2?.optString("hex") ?: r2?.optString("data") ?: ""
                    val result = JSONObject()
                    result.put("addr1", addr1); result.put("addr2", addr2)
                    result.put("note", if (zh) "对比地址1(地址栏)和地址2(符号栏)的数据差异" else "diff between addr1(addr field) and addr2(symbol field)")
                    val diffBytes = mutableListOf<String>()
                    val bytes1 = hex1.replace(" ", "").replace("\n", "")
                    val bytes2 = hex2.replace(" ", "").replace("\n", "")
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
                    tools.addTab(tl, if (zh) "对比" else "Diff", result.toString(2))
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
            // 内容
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
// 简洁模式：纯文字描述，无省略号，无问号
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

    // 2. items 数组 → 列表（用 name 或 address 展示，不显示问号）
    val items = json.optJSONArray("items")
    if (items != null && items.length() > 0) {
        if (zh) sb.append("共 ${items.length()} 项:\n") else sb.append("${items.length()} items:\n")
        for (i in 0 until items.length()) {
            val item = items.opt(i)
            when (item) {
                is JSONObject -> {
                    val name = item.optString("name", "")
                    val addr = item.optString("address", item.optString("vaddr", item.optString("offset", "")))
                    val type = item.optString("type", "")
                    // 优先显示 name，没有 name 显示 address，都没有显示 type
                    val display = when {
                        name.isNotBlank() -> name
                        addr.isNotBlank() -> "@$addr"
                        type.isNotBlank() -> "[$type]"
                        else -> (if (zh) "第${i+1}项" else "item $i")
                    }
                    sb.append("$pad• $display")
                    if (addr.isNotBlank() && name.isNotBlank()) sb.append("  @ $addr")
                    sb.append("\n")
                }
                is String -> sb.append("$pad• $item\n")
                else -> {
                    val s = item.toString()
                    sb.append("$pad• ${if (s.isBlank()) (if (zh) "第${i+1}项" else "item $i") else s}\n")
                }
            }
        }
        return
    }

    // 3. cryptoFindings / findings / scans
    val cryptoArr = json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings") ?: json.optJSONArray("scans")
    if (cryptoArr != null && cryptoArr.length() > 0) {
        if (zh) sb.append("发现 ${cryptoArr.length()} 项加密特征:\n") else sb.append("${cryptoArr.length()} crypto findings:\n")
        for (i in 0 until cryptoArr.length()) {
            val c = cryptoArr.optJSONObject(i) ?: continue
            val name = c.optString("name", c.optString("algorithm", c.optString("type", "")))
            val count = c.optInt("count", c.optInt("matches", 0))
            // 不显示问号
            val displayName = if (name.isNotBlank()) name else (if (zh) "特征#${i+1}" else "feature#${i+1}")
            sb.append("$pad• $displayName")
            if (count > 0) sb.append(" ×$count")
            sb.append("\n")
        }
        return
    }

    // 4. 通用键值
    json.keys().asSequence().filter { it != "ok" }.forEach { k ->
        val v = json.opt(k)
        when (v) {
            is JSONObject -> { sb.append("$pad$k:\n"); describeJson(v, sb, zh, indent + 1) }
            is JSONArray -> { sb.append("$pad$k: ${v.length()} 项\n") }
            else -> {
                val vStr = v?.toString() ?: ""
                sb.append("$pad${if (k.isNotBlank()) k else "?"}: ${if (vStr.isNotBlank()) vStr else "-"}\n")
            }
        }
    }
}

/** 描述 overview 对象 */
private fun describeOverview(ov: JSONObject, json: JSONObject?, sb: StringBuilder, zh: Boolean) {
    sb.append(if (zh) "文件: " else "File: ").append(ov.optString("fileName", "-")).append("\n")
    sb.append(if (zh) "架构: " else "Arch: ").append(ov.optString("architecture", "-"))
    if (ov.has("bits")) sb.append("/${ov.optInt("bits")}bit")
    sb.append("\n")
    sb.append(if (zh) "大小: " else "Size: ").append(fmtBytes(ov.optLong("size", 0L))).append("\n")
    sb.append(if (zh) "类型: " else "Type: ").append(ov.optString("elfType", "-")).append("\n")
    sb.append(if (zh) "入口: " else "Entry: ").append(ov.optString("entryPoint", "0x0")).append("\n")
    sb.append(if (zh) "字节序: " else "Endian: ").append(ov.optString("endian", "-")).append("\n")
    val sha256 = ov.optString("sha256", "")
    if (sha256.isNotBlank()) sb.append("SHA256: $sha256\n")
    val compiler = ov.optString("compiler", "")
    if (compiler.isNotBlank()) sb.append(if (zh) "编译器: $compiler\n" else "Compiler: $compiler\n")
    val packer = ov.optString("packer", "")
    if (packer.isNotBlank()) sb.append(if (zh) "加壳: $packer\n" else "Packer: $packer\n")

    // 结构计数
    val countLabels = listOf("sectionCount" to (if (zh) "节区" else "Sections"), "functionCount" to (if (zh) "函数" else "Functions"),
        "symbolCount" to (if (zh) "符号" else "Symbols"), "stringCount" to (if (zh) "字符串" else "Strings"),
        "importCount" to (if (zh) "导入" else "Imports"), "exportCount" to (if (zh) "导出" else "Exports"))
    val counts = countLabels.filter { ov.has(it.first) && ov.optInt(it.first, 0) > 0 }
    if (counts.isNotEmpty()) {
        if (zh) sb.append("\n结构分析:\n") else sb.append("\nStructure:\n")
        counts.forEach { (k, label) -> sb.append("  $label: ${ov.optInt(k, 0)}\n") }
    }

    // 安全特性
    val sec = ov.optJSONArray("securityFeatures")
    if (sec != null && sec.length() > 0) {
        if (zh) sb.append("\n安全特性:\n") else sb.append("\nSecurity:\n")
        for (i in 0 until sec.length()) {
            val s = sec.optJSONObject(i) ?: continue
            val label = s.optString("label", s.optString("id", ""))
            val active = s.optBoolean("active", false)
            val desc = s.optString("description", "")
            val displayLabel = if (label.isNotBlank()) label else (if (zh) "特性#${i+1}" else "feature#${i+1}")
            sb.append("  $displayLabel: ${if (active) (if (zh) "启用" else "Yes") else (if (zh) "未启用" else "No")}\n")
            if (desc.isNotBlank()) sb.append("    $desc\n")
        }
    }

    // 加密特征（overview 内部的）
    val crypto = ov.optJSONArray("cryptoFindings") ?: ov.optJSONArray("findings")
    if (crypto != null && crypto.length() > 0) {
        if (zh) sb.append("\n加密特征:\n") else sb.append("\nCrypto:\n")
        for (i in 0 until crypto.length()) {
            val c = crypto.optJSONObject(i) ?: continue
            val name = c.optString("name", c.optString("algorithm", ""))
            val count = c.optInt("count", c.optInt("matches", 0))
            val displayName = if (name.isNotBlank()) name else (if (zh) "特征#${i+1}" else "feature#${i+1}")
            sb.append("  $displayName")
            if (count > 0) sb.append(" ×$count")
            sb.append("\n")
        }
    }

    // 顶层加密特征（从外部 json 传入）
    val rootCrypto = json?.optJSONArray("cryptoFindings")
    if (rootCrypto != null && rootCrypto.length() > 0) {
        if (zh) sb.append("\n加密特征:\n") else sb.append("\nCrypto:\n")
        for (i in 0 until rootCrypto.length()) {
            val c = rootCrypto.optJSONObject(i) ?: continue
            val name = c.optString("name", c.optString("algorithm", ""))
            val count = c.optInt("count", c.optInt("matches", 0))
            val displayName = if (name.isNotBlank()) name else (if (zh) "特征#${i+1}" else "feature#${i+1}")
            sb.append("  $displayName")
            if (count > 0) sb.append(" ×$count")
            sb.append("\n")
        }
    }
}

// ============================================================
// 详细模式：结构化数据展示
// ============================================================

@Composable
private fun StructuredJsonView(text: String, zh: Boolean) {
    if (text.isBlank()) return
    val json = runCatching { JSONObject(text) }.getOrNull()
    if (json == null) { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 16.sp); return }

    val ov = json.optJSONObject("overview")
    if (ov != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionCard(if (zh) "📦 基础属性" else "📦 Basics") { JsonKeyValues(ov, zh, listOf("fileName","size","architecture","bits","elfType","entryPoint","endian","sha256","sha1","md5","compiler","packer")) }
            val countKeys = listOf("sectionCount","functionCount","symbolCount","stringCount","importCount","exportCount").filter { ov.has(it) }
            if (countKeys.isNotEmpty()) { SectionCard(if (zh) "📊 结构与规模" else "📊 Structure") { MetricRowFull(*countKeys.map { k -> (if (zh) k.replace("Count","") else k.replace("Count","")) to ov.optInt(k, 0).toString() }.toTypedArray()) } }
            val sec = ov.optJSONArray("securityFeatures")
            if (sec != null && sec.length() > 0) {
                SectionCard(if (zh) "🔒 安全特性" else "🔒 Security") {
                    for (i in 0 until sec.length()) {
                        val s = sec.optJSONObject(i) ?: continue
                        val label = s.optString("label", s.optString("id", "?"))
                        val active = s.optBoolean("active", false); val desc = s.optString("description", "")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (active) (if (zh) "✓ 启用" else "✓ Yes") else (if (zh) "✗ 未启用" else "✗ No"), style = MaterialTheme.typography.bodySmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        if (desc.isNotBlank()) Text("  $desc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
            val crypto = ov.optJSONArray("cryptoFindings")
            if (crypto != null && crypto.length() > 0) {
                SectionCard(if (zh) "🔐 加密特征" else "🔐 Crypto") {
                    for (i in 0 until crypto.length()) {
                        val c = crypto.optJSONObject(i) ?: continue; val name = c.optString("name", c.optString("algorithm", "?"))
                        val count = c.optInt("count", c.optInt("matches", 0))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface); if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            val shown = setOf("fileName","size","architecture","bits","elfType","entryPoint","endian","sha256","sha1","md5","compiler","packer","sectionCount","functionCount","symbolCount","stringCount","importCount","exportCount","securityFeatures","cryptoFindings","findings","difficulty","ok","items")
            val extra = ov.keys().asSequence().filter { it !in shown && !ov.isNull(it) }.toList()
            if (extra.isNotEmpty()) { SectionCard(if (zh) "📋 其他信息" else "📋 Others") { JsonKeyValues(ov, zh, extra) } }
        }
        val extCrypto = json.optJSONArray("cryptoFindings")
        if (extCrypto != null && extCrypto.length() > 0) { StructuredCryptoCard(extCrypto, zh) }
        return
    }

    val items = json.optJSONArray("items")
    if (items != null && items.length() > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (zh) "共 ${items.length()} 项" else "${items.length()} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (i in 0 until items.length()) {
                val item = items.opt(i)
                val line = when (item) {
                    is JSONObject -> {
                        val name = item.optString("name"); val addr = item.optString("address", item.optString("vaddr", item.optString("offset", "")))
                        if (name.isNotBlank() && addr.isNotBlank()) "$name  @ $addr" else if (name.isNotBlank()) name else if (addr.isNotBlank()) "@$addr" else item.optString("type", "?")
                    }
                    is org.json.JSONArray -> "[${item.length()}] ${(0 until item.length()).joinToString(", ") { i -> item.opt(i).toString() }.take(120)}"
                    else -> item.toString()
                }
                Text("• $line", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        return
    }

    val cryptoArr = json.optJSONArray("cryptoFindings") ?: json.optJSONArray("findings") ?: json.optJSONArray("scans")
    if (cryptoArr != null && cryptoArr.length() > 0) { StructuredCryptoCard(cryptoArr, zh); return }

    val keys = json.keys().asSequence().filter { it != "ok" && it != "pagination" }.toList()
    if (keys.isEmpty()) { Text(json.toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface); return }
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

@Composable
private fun StructuredCryptoCard(cryptoArr: JSONArray, zh: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (zh) "共 ${cryptoArr.length()} 项发现" else "${cryptoArr.length()} findings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (i in 0 until cryptoArr.length()) {
            val c = cryptoArr.optJSONObject(i) ?: continue; val name = c.optString("name", c.optString("algorithm", c.optString("type", "?")))
            val count = c.optInt("count", c.optInt("matches", 0)); val detail = c.optString("detail", c.optString("description", ""))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("• $name", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface); if (count > 0) Text("×$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (detail.isNotBlank()) Text("  $detail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun JsonKeyValues(json: JSONObject, zh: Boolean, keys: List<String>) {
    keys.filter { json.has(it) && !json.isNull(it) }.forEach { k ->
        val v = json.opt(k)
        val label = when (k) { "fileName" -> if (zh) "名称" else "Name"; "size" -> if (zh) "大小" else "Size"; "architecture" -> if (zh) "架构" else "Arch"; "bits" -> if (zh) "位数" else "Bits"; "elfType" -> if (zh) "类型" else "Type"; "entryPoint" -> if (zh) "入口" else "Entry"; "endian" -> if (zh) "字节序" else "Endian"; "sha256" -> "SHA256"; "sha1" -> "SHA1"; "md5" -> "MD5"; "compiler" -> if (zh) "编译器" else "Compiler"; "packer" -> if (zh) "加壳" else "Packer"; else -> k }
        val vStr = when (v) { is Number -> { if (k == "size") fmtBytes(v.toLong()) else v.toString() }; is Boolean -> if (v) (if (zh) "是" else "Yes") else (if (zh) "否" else "No"); else -> v.toString() }
        Kv(label, vStr)
    }
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