package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
        Spacer(Modifier.size(8.dp))
        // 主体：B 布局
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 左控制台
            Surface(
                modifier = Modifier.width(190.dp).fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                ToolConsole(state, zh)
            }
            // 右结果流
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 工具名称 + 说明
        val toolInfo = when (state.activeTool) {
            "decompile" -> ToolInfo(if (zh) "反编译" else "Decompile", if (zh) "输入函数符号或VA地址，反编译为伪代码" else "Enter symbol/VA to decompile to pseudocode")
            "unpack" -> ToolInfo(if (zh) "脱壳" else "Unpack", if (zh) "分析包体结构，定位加固壳" else "Analyze package structure, detect packer")
            "soanalyze" -> ToolInfo(if (zh) "SO分析" else "SO Analyze", if (zh) "概览 + 密码学扫描" else "Overview + crypto scan")
            "emulate" -> ToolInfo(if (zh) "模拟" else "Emulate", if (zh) "Unidbg 模拟执行函数" else "Unidbg emulate function")
            "frida" -> ToolInfo("Frida", if (zh) "编辑Frida脚本（下发需root）" else "Edit Frida script (needs root)")
            "rebuild" -> ToolInfo(if (zh) "回编" else "Rebuild", if (zh) "校验编辑 → 回编 → 查看输出" else "Edit check → Build → Outputs")
            else -> ToolInfo("", "")
        }
        Text(toolInfo.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(toolInfo.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.size(4.dp))

        when (state.activeTool) {
            "decompile" -> {
                OutlinedTextField(value = tools.decompileTarget, onValueChange = { tools.decompileTarget = it },
                    label = { Text(if (zh) "符号/VA" else "Sym/VA") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("JNI_OnLoad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) })
                Button(onClick = {
                    val loc = tools.decompileTarget.trim()
                    if (loc.isEmpty()) { tools.decompileError = if (zh) "输入符号或地址" else "Enter sym/VA"; return@Button }
                    scope.launch {
                        tools.decompileRunning = true; tools.decompileError = ""; tools.decompileResult = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzDecompile(tools.sharedWorkspaceId, "", loc, true) }.getOrNull() }
                        tools.decompileRunning = false
                        if (r != null && r.optBoolean("ok", false)) tools.decompileResult = r.optString("pseudocode").ifBlank { toolSummarize(r) }
                        else tools.decompileError = r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { r?.optString("error") ?: if (zh) "反编译失败" else "Failed" }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && tools.decompileTarget.isNotBlank() && !tools.decompileRunning,
                    shape = RoundedCornerShape(8.dp)) {
                    if (tools.decompileRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "执行" else "Run")
                }
                if (tools.decompileError.isNotBlank()) Text(tools.decompileError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            "unpack" -> {
                Button(onClick = {
                    scope.launch {
                        tools.unpackRunning = true
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                        tools.unpackRunning = false
                        tools.unpackInfo = (if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无数据" else "none")
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, shape = RoundedCornerShape(8.dp)) {
                    if (tools.unpackRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "分析包体结构" else "Analyze package")
                }
                Text(if (zh) "需要 root + frida-server 才能脱壳" else "Needs root + frida-server for unpack", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.soAnalyzeRunning, shape = RoundedCornerShape(8.dp)) {
                    if (tools.soAnalyzeRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "概览+加密扫描" else "Overview+Crypto")
                }
            }
            "emulate" -> {
                OutlinedTextField(value = tools.emulateSymbol, onValueChange = { tools.emulateSymbol = it },
                    label = { Text(if (zh) "函数符号" else "Symbol") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("JNI_OnLoad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) })
                Button(onClick = {
                    val sym = tools.emulateSymbol.trim()
                    if (sym.isEmpty()) { tools.emulateError = if (zh) "输入函数符号" else "Enter symbol"; return@Button }
                    scope.launch {
                        tools.emulateRunning = true; tools.emulateError = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", sym, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateRunning = false
                        if (r != null && r.optBoolean("ok", false)) tools.emulateResult = toolSummarize(r)
                        else tools.emulateError = r?.optString("error") ?: if (zh) "模拟失败" else "Failed"
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && tools.emulateSymbol.isNotBlank() && !tools.emulateRunning, shape = RoundedCornerShape(8.dp)) {
                    if (tools.emulateRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "执行" else "Run")
                }
                if (tools.emulateError.isNotBlank()) Text(tools.emulateError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            "frida" -> {
                OutlinedTextField(value = tools.fridaScript, onValueChange = { tools.fridaScript = it },
                    label = { Text("Frida JS") }, modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = MaterialTheme.typography.bodySmall, shape = RoundedCornerShape(8.dp))
                Button(onClick = { tools.fridaStatus = if (zh) "脚本已就绪，通过MCP下发" else "Script ready, deliver via MCP" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Text(if (zh) "校验脚本" else "Validate")
                }
            }
            "rebuild" -> {
                Button(onClick = {
                    scope.launch {
                        tools.rebuildRunning = true; tools.rebuildError = ""; tools.rebuildResult = ""
                        val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                        if (c != null && c.optBoolean("ok", false)) tools.rebuildCheck = toolSummarize(c)
                        else tools.rebuildError = c?.optString("error") ?: if (zh) "校验失败" else "Check failed"
                        val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                        val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                        tools.rebuildRunning = false
                        if (b != null && b.optBoolean("ok", false)) tools.rebuildResult = toolSummarize(b)
                        else tools.rebuildError = b?.optString("error") ?: tools.rebuildError
                        tools.rebuildOutputs = (if (o?.optBoolean("ok", false) == true) toolSummarize(o) else if (zh) "无" else "none")
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.rebuildRunning, shape = RoundedCornerShape(8.dp)) {
                    if (tools.rebuildRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "校验并回编" else "Check & Build")
                }
                if (tools.rebuildError.isNotBlank()) Text(tools.rebuildError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (zh) "输出结果" else "Results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        ResultBlock(if (zh) "反编译" else "Decompile", tools.decompileResult)
        ResultBlock(if (zh) "模拟" else "Emulate", tools.emulateResult)
        ResultBlock(if (zh) "SO概览" else "Overview", tools.soOverview)
        ResultBlock(if (zh) "加密扫描" else "Crypto", tools.soCrypto)
        ResultBlock(if (zh) "回编校验" else "Rebuild Check", tools.rebuildCheck)
        ResultBlock(if (zh) "回编输出" else "Rebuild Output", tools.rebuildResult)
        ResultBlock(if (zh) "回编历史" else "Rebuild History", tools.rebuildOutputs)
        ResultBlock(if (zh) "包体结构" else "Package Files", tools.unpackInfo)
        ResultBlock("Frida", tools.fridaStatus)
    }
}

@Composable
internal fun ResultBlock(title: String, text: String) {
    if (text.isBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp,
            )
        }
    }
}

private data class ToolInfo(val title: String, val desc: String)