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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
                modifier = Modifier.width(160.dp).fillMaxHeight(),
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
            "decompile" -> ToolInfo(if (zh) "反编译" else "Decompile", if (zh) "输入函数符号/VA，反编译为伪代码" else "Enter symbol/VA to decompile to pseudocode")
            "unpack" -> ToolInfo(if (zh) "脱壳" else "Unpack", if (zh) "分析包体结构，提取SO，打包APK" else "Analyze package, extract SO, pack APK")
            "soanalyze" -> ToolInfo(if (zh) "SO分析" else "SO Analyze", if (zh) "概览/加密/段/导入导出" else "Overview, crypto, sections, imports/exports")
            "emulate" -> ToolInfo(if (zh) "模拟" else "Emulate", if (zh) "Unidbg 模拟执行 + 寄存器/内存查看" else "Unidbg emulate, regs, mem dump")
            "frida" -> ToolInfo("Frida", if (zh) "编辑Frida脚本（下发需root）" else "Edit Frida script (needs root)")
            "rebuild" -> ToolInfo(if (zh) "回编" else "Rebuild", if (zh) "校验回编，Hex补丁，重命名函数" else "Check build, hex patch, rename function")
            else -> ToolInfo("", "")
        }
        Text(toolInfo.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(toolInfo.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.size(4.dp))

        // 通用小按钮样式
        val btnMod = Modifier.fillMaxWidth()
        val btnPad = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)

        when (state.activeTool) {
            "decompile" -> {
                // 反编译主功能
                OutlinedTextField(value = tools.decompileTarget, onValueChange = { tools.decompileTarget = it },
                    label = { Text(if (zh) "符号/VA" else "Sym/VA") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("JNI_OnLoad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) })
                Button(onClick = {
                    val loc = tools.decompileTarget.trim()
                    if (loc.isEmpty()) { tools.decompileError = if (zh) "输入符号或地址" else "Enter sym/VA"; return@Button }
                    scope.launch { tools.decompileRunning = true; tools.decompileError = ""; tools.decompileResult = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzDecompile(tools.sharedWorkspaceId, "", loc, true) }.getOrNull() }
                        tools.decompileRunning = false
                        if (r != null && r.optBoolean("ok", false)) tools.decompileResult = r.optString("pseudocode").ifBlank { toolSummarize(r) }
                        else tools.decompileError = r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { r?.optString("error") ?: if (zh) "反编译失败" else "Failed" }
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.decompileTarget.isNotBlank() && !tools.decompileRunning, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    if (tools.decompileRunning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "反编译" else "Decompile", style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
                }
                if (tools.decompileError.isNotBlank()) Text(tools.decompileError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(4.dp))
                // 额外功能：函数列表 + 搜索函数 + 反汇编
                Button(onClick = {
                    scope.launch { tools.decompileExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                        tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无数据" else "none"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "📋 文件列表" else "📋 Files", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = {
                    // 用 rzFunctions 获取函数列表
                    scope.launch { tools.decompileExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "functions", "", 30) }.getOrNull() }
                        tools.decompileExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无函数" else "no functions"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🔍 函数列表" else "🔍 Functions", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
            }
            "unpack" -> {
                Button(onClick = {
                    scope.launch { tools.unpackRunning = true
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                        tools.unpackRunning = false
                        tools.unpackInfo = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无数据" else "none"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.unpackRunning, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    if (tools.unpackRunning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(if (zh) "📦 分析包体结构" else "📦 Analyze pkg", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                // 额外功能
                Button(onClick = {
                    scope.launch { tools.unpackExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "files", ".so", 60) }.getOrNull() }
                        tools.unpackExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无SO" else "no SO"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🔧 提取 SO" else "🔧 Extract SO", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Text(if (zh) "需要 root + frida-server 才能脱壳" else "Needs root + frida-server for unpack", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            "soanalyze" -> {
                Button(onClick = {
                    scope.launch { tools.soAnalyzeRunning = true
                        val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).overview(tools.sharedWorkspaceId) }.getOrNull() }
                        val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzScanCrypto(tools.sharedWorkspaceId) }.getOrNull() }
                        tools.soAnalyzeRunning = false
                        tools.soOverview = if (o?.optBoolean("ok", false) == true) toolSummarize(o) else o?.optString("error") ?: ""
                        tools.soCrypto = if (c?.optBoolean("ok", false) == true) toolSummarize(c) else ""
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.soAnalyzeRunning, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    if (tools.soAnalyzeRunning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(if (zh) "📊 概览+加密扫描" else "📊 Overview+Crypto", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                // 额外功能：段 + 导入 + 导出
                Button(onClick = {
                    scope.launch { tools.soExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "sections", "", 60) }.getOrNull() }
                        tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无段信息" else "no sections"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "📑 段信息" else "📑 Sections", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = {
                    scope.launch { tools.soExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "imports", "", 60) }.getOrNull() }
                        tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无导入表" else "no imports"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🔗 导入表" else "🔗 Imports", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = {
                    scope.launch { tools.soExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "dynsyms", "", 60) }.getOrNull() }
                        tools.soExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无导出表" else "no exports"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "📤 导出表" else "📤 Exports", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
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
                    scope.launch { tools.emulateRunning = true; tools.emulateError = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", sym, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateRunning = false
                        if (r != null && r.optBoolean("ok", false)) tools.emulateResult = toolSummarize(r)
                        else tools.emulateError = r?.optString("error") ?: if (zh) "模拟失败" else "Failed"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank() && tools.emulateSymbol.isNotBlank() && !tools.emulateRunning, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    if (tools.emulateRunning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.size(4.dp)) }
                    Text(if (zh) "模拟执行" else "Emulate", style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
                }
                if (tools.emulateError.isNotBlank()) Text(tools.emulateError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(4.dp))
                // 额外功能
                Button(onClick = {
                    scope.launch { tools.emulateExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).emulate(tools.sharedWorkspaceId, "", tools.emulateSymbol.ifBlank { "JNI_OnLoad" }, org.json.JSONArray(), true) }.getOrNull() }
                        tools.emulateExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else if (zh) "先执行模拟" else "Run emulate first"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "📟 寄存器查看" else "📟 Registers", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = {
                    scope.launch { tools.emulateExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).dumpMemory(tools.sharedWorkspaceId, "", 0L, 256) }.getOrNull() }
                        tools.emulateExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else if (zh) "先执行模拟" else "Run emulate first"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "💾 内存 Dump" else "💾 Mem Dump", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
            }
            "frida" -> {
                OutlinedTextField(value = tools.fridaScript, onValueChange = { tools.fridaScript = it },
                    label = { Text("Frida JS") }, modifier = Modifier.fillMaxWidth().height(80.dp),
                    textStyle = MaterialTheme.typography.bodySmall, shape = RoundedCornerShape(8.dp))
                // 主按钮：校验脚本
                Button(onClick = { tools.fridaStatus = if (zh) "脚本已就绪，通过MCP下发" else "Script ready, deliver via MCP" }, modifier = btnMod, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "✅ 校验脚本" else "✅ Validate", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Text(if (zh) "Frida 功能需 root + frida-server" else "Frida needs root + frida-server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Spacer(Modifier.size(2.dp))
                // 扩展功能(需root)
                Button(onClick = { tools.fridaStatus = if (zh) "需 root 设备 + 运行 frida-server" else "Needs root + frida-server" }, modifier = btnMod, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🔗 连接设备" else "🔗 Attach", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = { tools.fridaStatus = if (zh) "需 root 设备 + 运行 frida-server" else "Needs root + frida-server" }, modifier = btnMod, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "📤 加载脚本" else "📤 Load Script", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = { tools.fridaStatus = if (zh) "需 root 设备 + 运行 frida-server" else "Needs root + frida-server" }, modifier = btnMod, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🔍 Hook 类" else "🔍 Hook Class", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = { tools.fridaStatus = if (zh) "需 root 设备 + 运行 frida-server" else "Needs root + frida-server" }, modifier = btnMod, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🛡️ SSL Pinning 绕过" else "🛡️ SSL Bypass", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
            }
            "rebuild" -> {
                // 主功能
                Button(onClick = {
                    scope.launch { tools.rebuildRunning = true; tools.rebuildError = ""; tools.rebuildResult = ""
                        val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(tools.sharedWorkspaceId, "") }.getOrNull() }
                        if (c != null && c.optBoolean("ok", false)) tools.rebuildCheck = toolSummarize(c)
                        else tools.rebuildError = c?.optString("error") ?: if (zh) "校验失败" else "Check failed"
                        val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(tools.sharedWorkspaceId, "", tools.sharedSoName) }.getOrNull() }
                        val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                        tools.rebuildRunning = false
                        if (b != null && b.optBoolean("ok", false)) tools.rebuildResult = toolSummarize(b)
                        else tools.rebuildError = b?.optString("error") ?: tools.rebuildError
                        tools.rebuildOutputs = if (o?.optBoolean("ok", false) == true) toolSummarize(o) else if (zh) "无" else "none"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank() && !tools.rebuildRunning, shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    if (tools.rebuildRunning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(if (zh) "🔨 校验并回编" else "🔨 Check & Build", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                if (tools.rebuildError.isNotBlank()) Text(tools.rebuildError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(4.dp))
                // 额外功能
                Button(onClick = {
                    scope.launch { tools.rebuildExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editAudit(tools.sharedWorkspaceId, "") }.getOrNull() }
                        tools.rebuildExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无补丁" else "no patches"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "🩹 Hex 补丁" else "🩹 Hex Patch", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
                Button(onClick = {
                    scope.launch { tools.rebuildExtra = ""
                        val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(tools.sharedWorkspaceId, "", "symbols", "", 60) }.getOrNull() }
                        tools.rebuildExtra = if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: if (zh) "无符号" else "no symbols"
                    }
                }, modifier = btnMod, enabled = tools.sharedWorkspaceId.isNotBlank(), shape = RoundedCornerShape(8.dp), contentPadding = btnPad) {
                    Text(if (zh) "✏️ 符号列表" else "✏️ Symbols", style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ResultStream(tools: ToolPagesState, zh: Boolean) {
    var detailMode by androidx.compose.runtime.remember { mutableStateOf(false) }
    // 结果标签页：收集所有非空的结果
    val resultTabs = remember(tools) {
        listOfNotNull(
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
    }
    var selectedTab by androidx.compose.runtime.remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        // 顶部：标题 + 简洁/详细切换 + 标签页数量
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "输出结果" else "Results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selectedTab + 1}/${resultTabs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            androidx.compose.foundation.horizontalScroll(rememberScrollState()).let { scrollMod ->
                Row(Modifier.fillMaxWidth().then(scrollMod), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }
            Spacer(Modifier.size(6.dp))
            // 当前选中标签页的内容
            val current = resultTabs.getOrNull(selectedTab) ?: return
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (detailMode) {
                    DetailCard(current.label, current.text, zh)
                } else {
                    DataCard(current.label, current.text)
                }
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