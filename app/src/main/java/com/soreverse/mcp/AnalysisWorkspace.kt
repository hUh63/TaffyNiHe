package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ToolDef(val key: String, val labelZh: String, val labelEn: String)

internal val toolDefs = listOf(
    ToolDef("decompile", "反编译", "Decompile"),
    ToolDef("unpack", "脱壳", "Unpack"),
    ToolDef("soanalyze", "SO分析", "SO"),
    ToolDef("emulate", "模拟", "Emulate"),
    ToolDef("frida", "Frida", "Frida"),
    ToolDef("rebuild", "回编", "Rebuild"),
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
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 顶部：任务 + 工作区选择 + 工具切换条
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cur = state.currentTask()
            Text(if (zh) "任务" else "Task", style = MaterialTheme.typography.labelMedium)
            Button(onClick = onOpenTask, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Text((cur?.title ?: if (zh) "未选择" else "None").take(14), style = MaterialTheme.typography.labelMedium)
            }
            WorkspacePicker(tools, zh)
        }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            toolDefs.forEach { def ->
                val sel = state.activeTool == def.key
                Button(
                    onClick = { state.activeTool = def.key },
                    colors = if (sel) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        else androidx.compose.material3.ButtonDefaults.buttonColors(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(if (zh) def.labelZh else def.labelEn, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.size(10.dp))
        // 主体：左控制台 + 右结果流
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.width(190.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                ToolConsole(state, zh)
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            ) {
                ResultStream(tools, zh)
            }
        }
    }
}

// 顶部：选择 SO/APK 文件作为当前工作区
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
                tools.openError = r?.optString("error").orEmpty().ifBlank { (if (zh) "打开失败" else "Open failed") }
            }
        }
    }
    Button(
        onClick = { picker.launch(arrayOf("*/*")) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        enabled = !tools.opening,
    ) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text((if (tools.opening) (if (zh) "加载…" else "…") else (tools.sharedSoName.ifBlank { if (zh) "选文件" else "Open" })).take(12), style = MaterialTheme.typography.labelMedium)
    }
    if (tools.openError.isNotBlank()) {
        Text(tools.openError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

// 左控制台：当前工具的控制输入 + 执行按钮（真实调用引擎）
@Composable
private fun ToolConsole(state: WorkspaceState, zh: Boolean) {
    val tools = state.tools
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.activeTool) {
            "decompile" -> {
                OutlinedTextField(value = tools.decompileTarget, onValueChange = { tools.decompileTarget = it },
                    label = { Text(if (zh) "符号/VA" else "Sym/VA") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val loc = tools.decompileTarget.trim()
                    if (loc.isEmpty()) { tools.decompileError = if (zh) "请输入函数符号或地址" else "Enter sym/VA"; return@Button }
                    scope.launch {
                        tools.decompileRunning = true; tools.decompileError = ""; tools.decompileResult = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzDecompile(tools.sharedWorkspaceId, "", loc, true) }.getOrNull() }
                        tools.decompileRunning = false
                        if (r != null && r.optBoolean("ok", false)) tools.decompileResult = r.optString("pseudocode").ifBlank { toolSummarize(r) }
                        else tools.decompileError = r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { (r?.optString("error") ?: (if (zh) "反编译失败" else "Decompile failed")) }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && tools.decompileTarget.isNotBlank() && !tools.decompileRunning) {
                    if (tools.decompileRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp)); Text(if (zh) "反编译" else "Decompile")
                }
                if (tools.decompileError.isNotBlank()) Text(tools.decompileError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            "unpack" -> {
                Text(if (zh) "DEX 内存脱壳（需 root+frida）" else "DEX unpack (root+frida)", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    scope.launch {
                        tools.unpackRunning = true
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                        tools.unpackRunning = false
                        tools.unpackInfo = (if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: (if (zh) "（无数据）" else "(none)"))
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning) {
                    if (tools.unpackRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp)); Text(if (zh) "分析包体" else "Analyze pkg")
                }
            }
            "soanalyze" -> {
                Button(onClick = {
                    scope.launch {
                        tools.soAnalyzeRunning = true
                        val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).overview(tools.sharedWorkspaceId) }.getOrNull() }
                        val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzScanCrypto(tools.sharedWorkspaceId) }.getOrNull() }
                        tools.soAnalyzeRunning = false
                        tools.soOverview = (if (o?.optBoolean("ok", false) == true) toolSummarize(o) else o?.optString("error") ?: "")
                        tools.soCrypto = (if (c?.optBoolean("ok", false) == true) toolSummarize(c) else "")
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.soAnalyzeRunning) {
                    if (tools.soAnalyzeRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp)); Text(if (zh) "概览+加密扫描" else "Overview+Crypto")
                }
            }
            "emulate" -> {
                OutlinedTextField(value = tools.emulateSymbol, onValueChange = { tools.emulateSymbol = it },
                    label = { Text(if (zh) "函数符号" else "Symbol") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val sym = tools.emulateSymbol.trim()
                    if (sym.isEmpty()) { tools.emulateError = if (zh) "请输入函数符号" else "Enter symbol"; return@Button }
                    scope.launch {
                        tools.emulateRunning = true; tools.emulateError = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", sym, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateRunning = false
                        if (r != null && r.optBoolean("ok", false)) tools.emulateResult = toolSummarize(r)
                        else tools.emulateError = r?.optString("error") ?: (if (zh) "模拟失败" else "Emulate failed")
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && tools.emulateSymbol.isNotBlank() && !tools.emulateRunning) {
                    if (tools.emulateRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp)); Text(if (zh) "模拟执行" else "Emulate")
                }
                if (tools.emulateError.isNotBlank()) Text(tools.emulateError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            "frida" -> {
                OutlinedTextField(value = tools.fridaScript, onValueChange = { tools.fridaScript = it },
                    label = { Text("Frida JS") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { tools.fridaStatus = if (zh) "脚本已就绪。下发需 root+frida-server（走 MCP dynamic）" else "Script ready. Delivery needs root+frida-server (via MCP)." }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (zh) "校验" else "Validate")
                }
            }
            "rebuild" -> {
                Button(onClick = {
                    scope.launch {
                        tools.rebuildRunning = true; tools.rebuildError = ""; tools.rebuildResult = ""
                        val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                        if (c != null && c.optBoolean("ok", false)) tools.rebuildCheck = toolSummarize(c)
                        else tools.rebuildError = c?.optString("error") ?: (if (zh) "校验失败" else "Check failed")
                        val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                        val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                        tools.rebuildRunning = false
                        if (b != null && b.optBoolean("ok", false)) tools.rebuildResult = toolSummarize(b)
                        else tools.rebuildError = b?.optString("error") ?: tools.rebuildError
                        tools.rebuildOutputs = (if (o?.optBoolean("ok", false) == true) toolSummarize(o) else if (zh) "（无）" else "(none)")
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.rebuildRunning) {
                    if (tools.rebuildRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp)); Text(if (zh) "校验并回编" else "Check&Build")
                }
                if (tools.rebuildError.isNotBlank()) Text(tools.rebuildError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// 右结果流：累积所有工具的结果
@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (zh) "结果 / 数据流" else "Results", style = MaterialTheme.typography.titleSmall)
        ResultBlock(if (zh) "反编译" else "Decompile", tools.decompileResult)
        ResultBlock(if (zh) "模拟" else "Emulate", tools.emulateResult)
        ResultBlock(if (zh) "SO 分析·概览" else "SO Overview", tools.soOverview)
        ResultBlock(if (zh) "SO 分析·加密" else "SO Crypto", tools.soCrypto)
        ResultBlock(if (zh) "回编·校验" else "Rebuild Check", tools.rebuildCheck)
        ResultBlock(if (zh) "回编·输出" else "Rebuild Output", tools.rebuildResult)
        ResultBlock(if (zh) "回编·历史" else "Rebuild History", tools.rebuildOutputs)
        ResultBlock(if (zh) "脱壳·结构" else "Unpack Files", tools.unpackInfo)
        ResultBlock("Frida", tools.fridaStatus)
    }
}

@Composable
internal fun ResultBlock(title: String, text: String) {
    if (text.isBlank()) return
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}