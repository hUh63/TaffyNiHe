package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // 顶部栏：任务选择 + 工作区选择
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cur = state.currentTask()
            Text(if (zh) "任务" else "Task", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpenTask, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text((cur?.title ?: if (zh) "未选择" else "None").take(14), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            WorkspacePicker(tools, zh)
        }
        Spacer(Modifier.size(6.dp))
        // 工具切换条
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            toolDefs.forEach { def ->
                val sel = state.activeTool == def.key
                Button(
                    onClick = { state.activeTool = def.key },
                    colors = if (sel) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text(if (zh) def.labelZh else def.labelEn, style = MaterialTheme.typography.labelMedium, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.size(6.dp))
        // 主体：控制台(极紧凑) + 结果流(占剩余空间)
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
private fun WorkspacePicker(tools: ToolPagesState, zh: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            tools.opening = true; tools.openError = ""
            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).open(uri.toString(), false) }.getOrNull() }
            tools.opening = false
            if (r != null && r.optBoolean("ok", false)) {
                tools.sharedWorkspaceId = r.optString("workspaceId")
                tools.sharedSoName = r.optString("soFileName").ifBlank { r.optString("fileName") }
            } else {
                tools.openError = r?.optString("error").orEmpty().ifBlank { if (zh) "打开失败" else "Open failed" }
            }
        }
    }
    Column(Modifier.fillMaxWidth(0.5f)) {
        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            enabled = !tools.opening,
            shape = RoundedCornerShape(8.dp),
        ) {
            if (tools.opening) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.size(6.dp))
            Text((tools.sharedSoName.ifBlank { if (zh) "选文件" else "Open" }).take(14), style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
        }
        if (tools.openError.isNotBlank()) {
            Text(tools.openError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
        }
    }
}

@Composable
private fun ToolConsole(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
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
                        if (r != null && r.optBoolean("ok", false)) tools.decompileResult = r.optString("pseudocode").ifBlank { toolSummarize(r) }
                        else tools.decompileError = r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { r?.optString("error") ?: if (zh) "反编译失败" else "Failed" }
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.decompileTarget.isNotBlank() && !tools.decompileRunning, loading = tools.decompileRunning)
                SmBtn(if (zh) "函数" else "Fns", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "functions", "", 30) }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Disasm", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).disasm(tools.sharedWorkspaceId, "", "", 20, "", 0, 0, 4096, tools.disasmAddr.ifBlank { "main" }, null, "auto") }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "失败" else "failed"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.disasmAddr.isNotBlank())
                SmBtn("Hex", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, 0, 256) }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "失败" else "failed"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "unpack" -> {
                SmBtn(if (zh) "分析" else "Analyze", bm, bp, { scope.launch { tools.unpackRunning = true
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                    tools.unpackRunning = false; tools.unpackInfo = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, loading = tools.unpackRunning)
                SmBtn("SO", bm, bp, { scope.launch { tools.unpackExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", ".so", 60) }.getOrNull() }
                    tools.unpackExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("DEX", bm, bp, { scope.launch { tools.unpackExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "classes", "", 30) }.getOrNull() }
                    tools.unpackExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "soanalyze" -> {
                SmBtn(if (zh) "概览" else "Ovw", bm, bp, { scope.launch { tools.soAnalyzeRunning = true
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).overview(tools.sharedWorkspaceId) }.getOrNull() }
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzScanCrypto(tools.sharedWorkspaceId) }.getOrNull() }
                    tools.soAnalyzeRunning = false; tools.soOverview = if (o?.optBoolean("ok", false) == true) toolSummarize(o) else o?.optString("error") ?: ""
                    tools.soCrypto = if (c?.optBoolean("ok", false) == true) toolSummarize(c) else ""
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.soAnalyzeRunning, loading = tools.soAnalyzeRunning)
                SmBtn("Sec", bm, bp, { scope.launch { tools.soExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "sections", "", 60) }.getOrNull() }
                    tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Imp", bm, bp, { scope.launch { tools.soExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "imports", "", 60) }.getOrNull() }
                    tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Exp", bm, bp, { scope.launch { tools.soExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "dynsyms", "", 60) }.getOrNull() }
                    tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("CFG", bm, bp, { scope.launch { tools.soExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzCfg(tools.sharedWorkspaceId, "", tools.decompileTarget.ifBlank { "main" }) }.getOrNull() }
                    tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "emulate" -> {
                OutlinedTextField(value = tools.emulateSymbol, onValueChange = { tools.emulateSymbol = it },
                    label = { Text(if (zh) "符号" else "Sym") }, singleLine = true,
                    modifier = Modifier.width(60.dp).height(22.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), shape = RoundedCornerShape(4.dp))
                SmBtn(if (zh) "模拟" else "Emu", bm, bp, { val sym = tools.emulateSymbol.trim(); if (sym.isEmpty()) return@SmBtn
                    scope.launch { tools.emulateRunning = true; tools.emulateError = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", sym, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateRunning = false; if (r != null && r.optBoolean("ok", false)) tools.emulateResult = toolSummarize(r)
                        else tools.emulateError = r?.optString("error") ?: if (zh) "失败" else "Failed"
                    }
                }, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.emulateSymbol.isNotBlank() && !tools.emulateRunning, loading = tools.emulateRunning)
                SmBtn(if (zh) "寄存器" else "Regs", bm, bp, { scope.launch { tools.emulateExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", tools.emulateSymbol.ifBlank { "JNI_OnLoad" }, org.json.JSONArray(), true) }.getOrNull() }
                    tools.emulateExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else if (zh) "先模拟" else "emu first"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn("Mem", bm, bp, { scope.launch { tools.emulateExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).dumpMemory(tools.sharedWorkspaceId, "", 0L, 256) }.getOrNull() }
                    tools.emulateExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else if (zh) "先模拟" else "emu first"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "frida" -> {
                SmBtn(if (zh) "校验" else "Valid", bm, bp, { tools.fridaStatus = if (zh) "脚本已就绪" else "Ready" }, enabled = true)
                SmBtn(if (zh) "连接" else "Attach", bm, bp, { tools.fridaStatus = if (zh) "需root" else "need root" }, enabled = true)
                SmBtn("Hook", bm, bp, { tools.fridaStatus = if (zh) "需root" else "need root" }, enabled = true)
            }
            "rebuild" -> {
                SmBtn(if (zh) "回编" else "Build", bm, bp, { scope.launch { tools.rebuildRunning = true; tools.rebuildError = ""; tools.rebuildResult = ""
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                    if (c != null && c.optBoolean("ok", false)) tools.rebuildCheck = toolSummarize(c)
                    else tools.rebuildError = c?.optString("error") ?: if (zh) "失败" else "fail"
                    val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                    tools.rebuildRunning = false; if (b != null && b.optBoolean("ok", false)) tools.rebuildResult = toolSummarize(b)
                    else tools.rebuildError = b?.optString("error") ?: tools.rebuildError
                    tools.rebuildOutputs = if (o?.optBoolean("ok", false) == true) toolSummarize(o) else if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.rebuildRunning, loading = tools.rebuildRunning)
                SmBtn(if (zh) "补丁" else "Patch", bm, bp, { scope.launch { tools.rebuildExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editAudit(tools.sharedWorkspaceId, "") }.getOrNull() }
                    tools.rebuildExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "符号" else "Sym", bm, bp, { scope.launch { tools.rebuildExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "symbols", "", 60) }.getOrNull() }
                    tools.rebuildExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "字符串" else "Str", bm, bp, { scope.launch { tools.rebuildExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 20) }.getOrNull() }
                    tools.rebuildExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
            "editor" -> {
                OutlinedTextField(value = tools.disasmAddr, onValueChange = { tools.disasmAddr = it },
                    label = { Text(if (zh) "地址" else "Addr") }, singleLine = true,
                    modifier = Modifier.width(55.dp).height(22.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), shape = RoundedCornerShape(4.dp))
                SmBtn("Hex", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).hexdump(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, 0, 256) }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "失败" else "failed"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "文本" else "Text", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "strings", "", 30) }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "编辑" else "Edit", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editHex(tools.sharedWorkspaceId, "", tools.disasmAddr.ifBlank { "0x0" }, org.json.JSONArray(), false) }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "失败" else "failed"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
                SmBtn(if (zh) "对比" else "Diff", bm, bp, { scope.launch { tools.decompileExtra = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "sections", "", 30) }.getOrNull() }
                    tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无" else "none"
                } }, enabled = tools.sharedWorkspaceId.isNotBlank())
            }
        }
    }
}

@Composable
private fun SmBtn(label: String, modifier: Modifier, padding: PaddingValues, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled && !loading, shape = RoundedCornerShape(4.dp), contentPadding = padding) {
        if (loading) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        else Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}
@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    var detailMode by androidx.compose.runtime.remember { mutableStateOf(false) }
    // 结果标签页：收集所有非空的结果
    val resultTabs = listOfNotNull(
            R2Tab(if (zh) "反编译" else "Decompile", tools.decompileResult),
            R2Tab(if (zh) "反编译·额外" else "Decompile·Extra", tools.decompileExtra),
            R2Tab(if (zh) "模拟" else "Emulate", tools.emulateResult),
            R2Tab(if (zh) "模拟·额外" else "Emulate·Extra", tools.emulateExtra),
            R2Tab(if (zh) "SO概览" else "Overview", tools.soOverview),
            R2Tab(if (zh) "加密扫描" else "Crypto", tools.soCrypto),
            R2Tab(if (zh) "SO·额外" else "SO·Extra", tools.soExtra),
            R2Tab(if (zh) "回编校验" else "Rebuild Check", tools.rebuildCheck),
            R2Tab(if (zh) "回编输出" else "Rebuild Output", tools.rebuildResult),
            R2Tab(if (zh) "回编历史" else "Rebuild History", tools.rebuildOutputs),
            R2Tab(if (zh) "回编·额外" else "Rebuild·Extra", tools.rebuildExtra),
            R2Tab(if (zh) "包体结构" else "Package Files", tools.unpackInfo),
            R2Tab(if (zh) "脱壳·额外" else "Unpack·Extra", tools.unpackExtra),
            R2Tab("Frida", tools.fridaStatus),
        ).filter { it.text.isNotBlank() }
    var selectedTab by androidx.compose.runtime.remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // 顶部：标题
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "输出结果" else "Results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (resultTabs.isNotEmpty()) {
                    Text("${selectedTab + 1}/${resultTabs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { detailMode = !detailMode },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                ) { Text(if (detailMode) (if (zh) "简洁" else "Simple") else (if (zh) "详细" else "Detail"), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp) }
            }
        }
        Spacer(Modifier.size(4.dp))
        // 标签页导航条
        if (resultTabs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                    resultTabs.forEachIndexed { idx, tab ->
                        val sel = idx == selectedTab
                        Button(
                            onClick = { selectedTab = idx },
                            colors = if (sel) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) { Text(tab.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1) }
                    }
                }
            Spacer(Modifier.size(6.dp))
            // 当前选中标签页的内容（占剩余空间）
            val current = resultTabs.getOrNull(selectedTab) ?: return
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (detailMode) {
                    DetailCard(current.label, current.text, zh)
                } else {
                    DataCard(current.label, current.text)
                }
            }
            // 底部导航：上一页 / 页码 / 下一页
            Spacer(Modifier.size(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (selectedTab > 0) selectedTab-- },
                    enabled = selectedTab > 0,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                ) { Text("◀ " + if (zh) "上一页" else "Prev", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) }
                Spacer(Modifier.size(12.dp))
                Text("${selectedTab + 1} / ${resultTabs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = { if (selectedTab < resultTabs.size - 1) selectedTab++ },
                    enabled = selectedTab < resultTabs.size - 1,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                ) { Text(if (zh) "下一页" else "Next" + " ▶", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (zh) "暂无结果，请执行分析工具" else "No results yet, run a tool", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class R2Tab(val label: String, val text: String)

@Composable
internal fun DataCard(title: String, text: String) {
    if (text.isBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(6.dp))
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json != null) {
                JsonOverview(json, text)
            } else {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
            }
        }
    }
}

/** 详细模式：结构化卡片，类似 ElfOverviewPanel 风格 */
@Composable
private fun DetailCard(title: String, text: String, zh: Boolean) {
    if (text.isBlank()) return
    val json = runCatching { JSONObject(text) }.getOrNull()
    if (json == null) {
        DataCard(title, text)
        return
    }
    // 如果有 overview 子对象，用它来渲染结构化视图
    val ov = json.optJSONObject("overview") ?: json
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

            // 基础属性
            SectionCard(if (zh) "📦 基础属性" else "📦 Basics") {
                Kv(if (zh) "名称" else "Name", ov.optString("fileName", "—"))
                Kv(if (zh) "大小" else "Size", fmtBytes(ov.optLong("size", 0L)))
                val arch = ov.optString("architecture", "—")
                val bits = ov.optInt("bits", 0)
                Kv(if (zh) "架构" else "Arch", if (bits > 0) "$arch/$bits" else arch)
                Kv(if (zh) "入口" else "Entry", ov.optString("entryPoint", "0x0"))
                Kv(if (zh) "类型" else "Type", ov.optString("elfType", "—"))
                Kv(if (zh) "字节序" else "Endian", ov.optString("endian", "—"))
            }

            // 结构与规模
            SectionCard(if (zh) "📊 结构与规模" else "📊 Structure") {
                MetricRow(
                    (if (zh) "节区" else "Sections") to ov.optInt("sectionCount", 0).toString(),
                    (if (zh) "程序段" else "Segments") to ov.optInt("segmentCount", 0).toString(),
                    (if (zh) "函数" else "Fns") to ov.optInt("functionCount", 0).toString(),
                )
                MetricRow(
                    (if (zh) "符号" else "Symbols") to ov.optInt("symbolCount", 0).toString(),
                    (if (zh) "字符串" else "Strings") to ov.optInt("stringCount", 0).toString(),
                )
            }

            // 安全特性
            val sec = ov.optJSONArray("securityFeatures") ?: org.json.JSONArray()
            if (sec.length() > 0) {
                SectionCard(if (zh) "🔒 安全特性" else "🔒 Security") {
                    for (i in 0 until sec.length()) {
                        val s = sec.optJSONObject(i) ?: continue
                        val name = s.optString("name", "?")
                        val present = s.optBoolean("present", false)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (present) (if (zh) "有" else "Yes") else (if (zh) "无" else "No"),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 原始 JSON（收起）
            Text(if (zh) "原始数据" else "Raw", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8)
        }
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
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MetricRow(vararg pairs: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pairs.forEach { (label, value) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun fmtBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return "%.1f %s".format(v, units[u])
}

@Composable
private fun JsonOverview(json: JSONObject, raw: String) {
    val keys = json.keys().asSequence().filter { it != "ok" }.toList()
    if (keys.isEmpty()) { Text(raw, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface); return }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEach { k ->
            val v = json.opt(k)
            when (v) {
                is JSONObject -> {
                    Text("$k:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    JsonOverview(v, v.toString())
                }
                is org.json.JSONArray -> {
                    Text("$k: [${v.length()} items]", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    for (i in 0 until v.length().coerceAtMost(10)) {
                        val item = v.opt(i)
                        Text("  [$i] $item", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (v.length() > 10) Text("  ... ${v.length() - 10} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Row(Modifier.fillMaxWidth()) {
                        Text("$k: ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

private data class ToolInfo(val title: String, val desc: String)