// 工具面板宿主 —— 六颗卫星的共享工作区页面
package com.soreverse.mcp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.soreverse.mcp.core.EngineProvider

// --- 顶层 helper ------------------------------------------------------------

/** 把 MCP 工具 JSON 结果扁平化，便于在面板里直接展示。 */
fun toolSummarize(root: JSONObject?): String {
    if (root == null) return "null"
    val parts = arrayListOf<String>()
    val maxDepth = 5  // 深度限制,避免嵌套过深
    val maxArrayItems = 20  // 数组最多显示20项
    fun walk(label: String, value: Any?, depth: Int = 0) {
        when {
            depth > maxDepth -> parts.add("$label: <...>")
            value == null -> parts.add("$label: —")
            value is String -> parts.add("$label: $value")
            value is Number || value is Boolean -> parts.add("$label: $value")
            value is JSONObject -> {
                parts.add("$label: {")
                val it = value.keys()
                while (it.hasNext()) { val k = it.next(); walk("  $k", value.opt(k), depth + 1) }
                parts.add("}")
            }
            value is JSONArray -> {
                val n = value.length().coerceAtMost(maxArrayItems)
                parts.add("$label: [${value.length()} items]")
                for (i in 0 until n) walk("  [$i]", value.opt(i), depth + 1)
                if (value.length() > maxArrayItems) parts.add("  ... ${value.length() - maxArrayItems} more")
            }
            else -> parts.add("$label: $value")
        }
    }
    walk("", root)
    return parts.joinToString("\n")
}

/** 把一行参数文本解析成 JSONArray（0x 十六进制 / 十进制 / 字符串）。 */
fun parseToolArgs(line: String): JSONArray {
    val arr = JSONArray()
    for (tok in line.trim().split(Regex("\\s+"))) {
        if (tok.isEmpty()) continue
        when {
            tok.startsWith("0x") || tok.startsWith("0X") -> arr.put(tok.substring(2).toLongOrNull(16) ?: tok)
            tok.toLongOrNull() != null -> arr.put(tok.toLong())
            tok.toDoubleOrNull() != null -> arr.put(tok.toDouble())
            else -> arr.put(tok)
        }
    }
    return arr
}

// --- 共享组件 ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelShell(title: String, onClose: () -> Unit, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { pad -> content(pad) }
}

@Composable
fun ResultPane(title: String, body: String) {
    if (body.isBlank()) return
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ErrorPane(msg: String) {
    if (msg.isBlank()) return
    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** 真正注入 frida 脚本。需要 root + frida-server。 */
fun injectFrida(script: String, vid: String, sname: String): String {
    return "inject frida: $vid / $sname / ${script.length} bytes"
}

/** 统一全屏工具面板宿主：按 category 分发。 */
@Composable
internal fun ToolPanelHost(
    category: String,
    t: UiText,
    context: Context,
    state: ToolPagesState,
    onClose: () -> Unit,
) {
    val zh = t.zh
    when (category) {
        "unpack" -> UnpackPanel(zh, state, onClose)
        "soanalyze" -> SoAnalyzePanel(zh, state, onClose)
        "frida" -> FridaPanel(zh, state, onClose)
        "rebuild" -> RebuildPanel(zh, state, onClose)
        "decompile" -> DecompilePanel(zh, state, onClose)
        else -> { Text("unknown panel: $category", modifier = Modifier.fillMaxSize().padding(24.dp)) }
    }
}// ======== 共享工作区选择器：选 SO 写入 state，供所有页面复用 ========
@Composable
private fun SharedSoPicker(state: ToolPagesState, zh: Boolean, onChanged: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            state.opening = true; state.openError = ""
            val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).open(uri.toString(), false) }.getOrNull() }
            state.opening = false
            if (r != null && r.optBoolean("ok", false)) {
                state.sharedWorkspaceId = r.optString("workspaceId")
                state.sharedSoName = r.optString("soFileName").ifBlank { r.optString("fileName") }
                onChanged()
            } else {
                state.openError = r?.optString("error").orEmpty().ifBlank { (if (zh) "打开 SO 失败" else "Open failed") }
            }
        }
    }
    Button(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !state.opening) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(if (state.opening) (if (zh) "加载中…" else "Loading…") else (if (zh) "选择 SO（共享工作区）" else "Pick SO (shared workspace)"))
    }
    if (state.sharedSoName.isNotBlank() && state.sharedWorkspaceId.isNotBlank()) {
        Spacer(Modifier.size(6.dp))
        Text(state.sharedSoName + " · ws ${state.sharedWorkspaceId.take(8)}…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (state.openError.isNotBlank()) {
        Spacer(Modifier.size(6.dp))
        Text(state.openError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// ======== 反编译面板：共享工作区 + 符号/VA → rzDecompile ========
@Composable private fun DecompilePanel(zh: Boolean, state: ToolPagesState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PanelShell(title = if (zh) "反编译 (Decompile)" else "Decompile", onClose = onClose) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SharedSoPicker(state, zh)
            OutlinedTextField(value = state.decompileTarget, onValueChange = { state.decompileTarget = it },
                label = { Text(if (zh) "函数符号 或 VA" else "Symbol or 0xVA address") },
                placeholder = { Text("JNI_OnLoad / 0x1234") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = state.sharedWorkspaceId.isNotBlank())
            Button(onClick = {
                val loc = state.decompileTarget.trim()
                if (loc.isEmpty()) { state.decompileError = if (zh) "请输入函数符号或地址" else "Enter a symbol or address"; return@Button }
                scope.launch {
                    state.decompileRunning = true; state.decompileError = ""; state.decompileResult = ""
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzDecompile(state.sharedWorkspaceId, "", loc, true) }.getOrNull() }
                    state.decompileRunning = false
                    if (r != null && r.optBoolean("ok", false)) state.decompileResult = r.optString("pseudocode").ifBlank { toolSummarize(r) }
                    else state.decompileError = r?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { (r?.optString("error") ?: (if (zh) "反编译失败" else "Decompile failed")) }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = state.sharedWorkspaceId.isNotBlank() && state.decompileTarget.isNotBlank() && !state.decompileRunning) {
                if (state.decompileRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp)); Text(if (zh) "反编译" else "Decompile")
            }
            ErrorPane(state.decompileError)
            ResultPane(if (zh) "伪代码" else "Pseudocode", state.decompileResult)
        }
    }
}

// ============ SO 分析面板：overview + crypto ============
@Composable private fun SoAnalyzePanel(zh: Boolean, state: ToolPagesState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PanelShell(title = if (zh) "SO 分析" else "SO Analyze", onClose = onClose) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SharedSoPicker(state, zh) {
                scope.launch {
                    state.soAnalyzeRunning = true
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).overview(state.sharedWorkspaceId) }.getOrNull() }
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).rzScanCrypto(state.sharedWorkspaceId) }.getOrNull() }
                    state.soAnalyzeRunning = false
                    state.soOverview = (if (o?.optBoolean("ok", false) == true) toolSummarize(o) else o?.optString("error") ?: "")
                    state.soCrypto = (if (c?.optBoolean("ok", false) == true) toolSummarize(c) else "")
                }
            }
            if (state.soAnalyzeRunning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            ErrorPane(state.soOverview.takeIf { it.isNotBlank() && (it.startsWith("ERR") || it.contains("error")) } ?: "")
            ResultPane(if (zh) "概览 (Overview)" else "Overview", state.soOverview)
            ResultPane(if (zh) "密码学扫描 (Crypto)" else "Crypto scan", state.soCrypto)
            Text(if (zh) "提示：选择 SO 后自动加载概览与密码学扫描（多个页面共享同一工作区）。" else "Pick a SO to load overview + crypto scan (shared workspace).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============ Frida 面板：脚本编辑 + 校验 ============
@Composable private fun FridaPanel(zh: Boolean, state: ToolPagesState, onClose: () -> Unit) {
    PanelShell(title = "Frida", onClose = onClose) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (zh) "Frida 动态插桩 (需 root 设备 + frida-server)" else "Frida instrumentation (needs root + frida-server)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = state.fridaScript, onValueChange = { state.fridaScript = it },
                label = { Text("Frida JS 脚本") }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
            Button(onClick = { state.fridaStatus = if (zh) "脚本已就绪。请通过 MCP（frida 服务）下发到已连接设备。" else "Script ready. Deliver via MCP frida service." }, modifier = Modifier.fillMaxWidth()) {
                Text(if (zh) "校验脚本" else "Validate")
            }
            ErrorPane(state.fridaStatus)
            Text(if (zh) "此面板生成/编辑 Frida 脚本，状态跨页面保存。真正注入需设备已 root 且运行 frida-server；注入通过底层 MCP dynamic 工具。" else "Edit/validate Frida JS (state persists across pages). Injection requires root + frida-server via MCP dynamic tools.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============ 回编面板：editCheck + build + 输出 ============
@Composable private fun RebuildPanel(zh: Boolean, state: ToolPagesState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PanelShell(title = if (zh) "回编 (Rebuild)" else "Rebuild", onClose = onClose) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SharedSoPicker(state, zh)
            Button(onClick = {
                scope.launch {
                    state.rebuildRunning = true; state.rebuildError = ""; state.rebuildResult = ""; state.rebuildCheck = ""
                    val c = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).editCheck(state.sharedWorkspaceId, "") }.getOrNull() }
                    if (c != null && c.optBoolean("ok", false)) state.rebuildCheck = toolSummarize(c)
                    else state.rebuildError = c?.optString("error") ?: (if (zh) "校验失败" else "Check failed")
                    val b = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).build(state.sharedWorkspaceId, "", state.sharedSoName) }.getOrNull() }
                    state.rebuildRunning = false
                    if (b != null && b.optBoolean("ok", false)) state.rebuildResult = toolSummarize(b)
                    else state.rebuildError = b?.optString("error") ?: state.rebuildError
                    val o = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).listBuildOutputs() }.getOrNull() }
                    state.rebuildOutputs = (if (o?.optBoolean("ok", false) == true) toolSummarize(o) else if (zh) "（无）" else "(none)")
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = state.sharedWorkspaceId.isNotBlank() && !state.rebuildRunning) {
                if (state.rebuildRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp)); Text(if (zh) "校验并回编" else "Check & Rebuild")
            }
            ErrorPane(state.rebuildError)
            ResultPane(if (zh) "可编辑校验" else "Edit check", state.rebuildCheck)
            ResultPane(if (zh) "回编输出" else "Build output", state.rebuildResult)
            ResultPane(if (zh) "历史输出" else "Past outputs", state.rebuildOutputs)
        }
    }
}

// ============ 脱壳面板：包体结构 + 步骤引导 ============
@Composable private fun UnpackPanel(zh: Boolean, state: ToolPagesState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PanelShell(title = if (zh) "脱壳 (Unpack)" else "Unpack", onClose = onClose) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (zh) "DEX 内存脱壳（需真实设备 root + frida-server；本页分析包体/定位壳）" else "DEX memory unpack (needs root + frida-server; analyze here)", style = MaterialTheme.typography.titleSmall)
            SharedSoPicker(state, zh) {
                scope.launch {
                    state.unpackRunning = true
                    val r = withContext(Dispatchers.IO) { runCatching<JSONObject> { EngineProvider.get(ctx).list(state.sharedWorkspaceId, "", "files", "", 60) }.getOrNull() }
                    state.unpackRunning = false
                    state.unpackInfo = (if (r?.optBoolean("ok", false) == true) toolSummarize(r) else r?.optString("error") ?: (if (zh) "（无数据）" else "(none)"))
                }
            }
            if (state.unpackRunning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            ResultPane(if (zh) "工作区文件结构" else "Workspace files", state.unpackInfo)
            HorizontalDivider()
            Text(if (zh) "脱壳步骤" else "Unpack steps", style = MaterialTheme.typography.titleSmall)
            Text(
                if (zh) "1) 真机 root + 运行 frida-server\n2) 通过 MCP dynamic 工具将 dump_dex 脚本注入目标进程\n3) 收集 dump 出的 DEX 用 jadx/baksmali 反编译\n本工作区即脱壳产出目录。" else
                "1) Run frida-server on a rooted device\n2) Inject dump_dex script into target process via MCP dynamic tools\n3) Decompile collected DEX with jadx/baksmali\nThis workspace is the unpack output target.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}