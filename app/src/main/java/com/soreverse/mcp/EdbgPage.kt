package com.soreverse.mcp

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.EdbgTools
import com.soreverse.mcp.mcp.JadxTool
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 数值比较内核版本（旧代码用字符串比较，"5.9" 会被判为 ≥"5.10"，属逻辑错误）。 */
private fun versionAtLeast(value: String, min: String): Boolean {
    fun parts(s: String) = s.trim().split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
    val a = parts(value)
    if (a.isEmpty()) return false
    val b = parts(min)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}

/** 环境状态小方块（Exbin 风：圆角 + 无阴影 + 状态色）。 */
@Composable
private fun StatusTile(label: String, value: String, modifier: Modifier = Modifier, ok: Boolean) {
    val metrics = LocalUiMetrics.current
    Column(
        modifier
            .padding(8.dp)
            .padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) AppPalette.green else MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * eDBG 图形化页：环境卡（Root/内核/二进制）→ 调试 / 反编译 分区 → 卡片表单 + 终端框。
 * 需要 Root + 内核 5.10+（eBPF）。对标 taffy_edbg MCP 工具。
 */
@Composable
internal fun EdbgPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var env by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }
    var pkgInput by remember { mutableStateOf("") }
    var libInput by remember { mutableStateOf("") }
    var brkInput by remember { mutableStateOf("") }
    var cmdInput by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var sessionActive by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("session") }

    var decompileName by remember { mutableStateOf("") }
    var classList by remember { mutableStateOf<List<String>>(emptyList()) }
    var classSource by remember { mutableStateOf("") }
    var classQuery by remember { mutableStateOf("") }

    val decompileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            busyMsg = if (zh) "正在反编译…" else "Decompiling…"
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("read failed")
                    val f = File(context.cacheDir, "edbg_decompile_input")
                    f.writeBytes(input)
                    decompileName = f.name
                    val ctx = ToolContext(context, settings, EngineProvider.get(context))
                    JadxTool.decompile.handle(ctx, JSONObject().put("action", "list").put("path", f.absolutePath).put("limit", 2000))
                }
            }
            val r = res.getOrElse { JSONObject().put("error", it.message ?: "decompile failed") }
            val list = r.optJSONArray("classes")
            classList = if (list != null) (0 until list.length()).map { list.optString(it) } else emptyList()
            if (classList.isEmpty() && r.has("error")) output = r.optString("error")
            classSource = ""
            classQuery = ""
            busy = false
        }
    }

    fun probe() {
        scope.launch {
            busy = true
            busyMsg = if (zh) "检测环境…" else "Probing…"
            env = withContext(Dispatchers.IO) {
                val ctx = ToolContext(context, settings, EngineProvider.get(context))
                EdbgTools.edbg.handle(ctx, JSONObject().put("action", "probe"))
            }
            busy = false
        }
    }

    fun runAction(action: String) {
        scope.launch {
            busy = true
            busyMsg = when (action) {
                "launch" -> if (zh) "启动调试…" else "Launching…"
                "install" -> if (zh) "部署 eDBG…" else "Installing…"
                else -> if (zh) "执行中…" else "Working…"
            }
            val args = JSONObject().put("action", action)
            if (action == "launch") {
                args.put("package", pkgInput.trim())
                if (libInput.isNotBlank()) args.put("lib", libInput.trim())
                if (brkInput.isNotBlank()) args.put("break", brkInput.trim())
            }
            if (action == "cmd") args.put("cmd", cmdInput.trim()).put("timeoutSec", 2)
            val res = withContext(Dispatchers.IO) {
                val ctx = ToolContext(context, settings, EngineProvider.get(context))
                EdbgTools.edbg.handle(ctx, args)
            }
            when (action) {
                "cmd" -> res.optString("output").takeIf { it.isNotBlank() }?.let { output = it }
                "launch" -> {
                    sessionActive = res.optBoolean("started", false)
                    res.optString("output").takeIf { it.isNotBlank() }?.let { output = it }
                    if (!sessionActive) Toast.makeText(context, res.optString("message", res.toString()), Toast.LENGTH_SHORT).show()
                }
                "stop" -> sessionActive = false
                "install" -> {
                    val ok = res.optBoolean("installed", false)
                    Toast.makeText(context, if (ok) (if (zh) "部署成功" else "Installed") else res.optString("message", res.toString()), Toast.LENGTH_SHORT).show()
                }
            }
            busy = false
        }
    }

    fun viewClass(cls: String) {
        scope.launch {
            busy = true
            busyMsg = if (zh) "反编译 $cls" else "Decompiling $cls"
            classSource = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "edbg_decompile_input")
                val ctx = ToolContext(context, settings, EngineProvider.get(context))
                val res = JadxTool.decompile.handle(ctx, JSONObject().put("action", "class").put("path", f.absolutePath).put("className", cls))
                res.optString("source", res.optString("message", res.toString()))
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { probe() }

    val envJson = env
    val rootOk = envJson?.optBoolean("root") == true
    val kernelVersion = envJson?.optString("kernelVersion").orEmpty()
    val kernelOk = envJson?.optBoolean("kernel") == true || versionAtLeast(kernelVersion, "5.10")
    val deployed = envJson?.optBoolean("deployed") == true

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 环境卡 ──
        item {
            GlassGroup(title = if (zh) "运行环境" else "Environment") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatusTile("Root", if (rootOk) "✓" else "✗", Modifier.weight(1f), rootOk)
                    StatusTile(if (zh) "内核" else "Kernel", kernelVersion.ifBlank { "-" }, Modifier.weight(1f), kernelOk)
                    StatusTile(if (zh) "二进制" else "Binary", if (deployed) "✓" else "✗", Modifier.weight(1f), deployed)
                }
                val reason = envJson?.optString("reason").orEmpty()
                if (reason.isNotBlank()) InlineHint(reason)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton(if (zh) "检测环境" else "Probe", { probe() }, Modifier.weight(1f), leading = Icons.Default.BugReport)
                    PrimaryActionButton(
                        if (zh) "部署 eDBG" else "Install",
                        { runAction("install") },
                        Modifier.weight(1f),
                        leading = Icons.Default.Download,
                        container = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        // ── 分区 Tab ──
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = tab == "session",
                    onClick = { tab = "session" },
                    label = { Text(if (zh) "调试" else "Debug") },
                )
                FilterChip(
                    selected = tab == "decompile",
                    onClick = { tab = "decompile" },
                    label = { Text(if (zh) "反编译" else "Decompile") },
                )
            }
        }

        if (tab == "decompile") {
            item {
                GlassGroup {
                    DataRow(
                        title = decompileName.ifBlank { if (zh) "选择 APK / DEX / JAR" else "Pick APK / DEX / JAR" },
                        subtitle = if (classList.isEmpty()) (if (zh) "图形化浏览类 / 方法，点类看源码" else "Browse classes, tap to view source")
                        else (if (zh) "${classList.size} 个类" else "${classList.size} classes"),
                        leading = { Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                        trailingText = if (zh) "选择文件" else "Pick",
                        onClick = { decompileLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/java-archive", "*/*")) },
                    )
                }
            }
            if (classSource.isNotBlank()) {
                item {
                    TerminalPane(
                        text = classSource,
                        title = decompileName,
                        onClear = { classSource = "" },
                        maxHeight = 520.dp,
                    )
                }
                item {
                    PrimaryActionButton(if (zh) "返回类列表" else "Back to list", { classSource = "" }, Modifier.fillMaxWidth())
                }
            } else if (classList.isNotEmpty()) {
                item {
                    SearchCountBar(
                        query = classQuery,
                        onQueryChange = { classQuery = it },
                        shown = classList.count { it.lowercase().contains(classQuery.trim().lowercase()) },
                        total = classList.size,
                        placeholder = if (zh) "筛选类名" else "Filter class",
                    )
                }
                items(classList.filter { it.lowercase().contains(classQuery.trim().lowercase()) }) { cls ->
                    GlassGroup {
                        DataRow(
                            title = cls.substringAfterLast('.').ifBlank { cls },
                            subtitle = cls,
                            onClick = { viewClass(cls) },
                            onLongClick = { viewClass(cls) },
                        )
                    }
                }
            }
        } else {
            item {
                GlassGroup(title = if (zh) "目标进程" else "Target process") {
                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        placeholder = { Text(if (zh) "目标包名" else "Target package", maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = libInput,
                        onValueChange = { libInput = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        placeholder = { Text(if (zh) "库名（可选）" else "Library (optional)", maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = brkInput,
                        onValueChange = { brkInput = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        placeholder = { Text(if (zh) "断点偏移（如 0x1234）" else "Break offset (e.g. 0x1234)", maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        if (sessionActive) {
                            PrimaryActionButton(
                                if (zh) "停止调试" else "Stop",
                                { runAction("stop") },
                                Modifier.fillMaxWidth(),
                                container = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            PrimaryActionButton(
                                if (zh) "启动调试" else "Launch",
                                { runAction("launch") },
                                Modifier.fillMaxWidth(),
                                leading = Icons.Default.PlayArrow,
                            )
                        }
                    }
                }
            }
            item {
                GlassGroup(title = if (zh) "快捷命令" else "Quick commands") {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("c", "s", "finish", "run", "bt").forEach { quick ->
                            FilterChip(
                                selected = false,
                                onClick = { if (sessionActive) { cmdInput = quick; runAction("cmd") } },
                                enabled = sessionActive && !busy,
                                label = { Text(quick, fontFamily = FontFamily.Monospace) },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { if (sessionActive && brkInput.isNotBlank()) { cmdInput = "b ${brkInput.trim()}"; runAction("cmd") } },
                            enabled = sessionActive && !busy && brkInput.isNotBlank(),
                            label = { Text("b ${brkInput.ifBlank { "0x…" }}", fontFamily = FontFamily.Monospace) },
                        )
                        listOf("hbreak", "watch", "info regs", "x/16gx \$pc").forEach { quick ->
                            FilterChip(
                                selected = false,
                                onClick = { if (sessionActive) { cmdInput = quick; runAction("cmd") } },
                                enabled = sessionActive && !busy,
                                label = { Text(quick, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
            }
            item {
                GlassGroup(title = if (zh) "调试命令" else "Debug command") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cmdInput,
                            onValueChange = { cmdInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("b 地址 / hbreak / watch / examine…", maxLines = 1) },
                            singleLine = true,
                            enabled = sessionActive && !busy,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (cmdInput.isNotBlank()) runAction("cmd") }),
                        )
                        IconButton(onClick = { if (cmdInput.isNotBlank()) runAction("cmd") }, enabled = sessionActive && !busy && cmdInput.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, if (zh) "发送" else "Send", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    TerminalPane(
                        text = output,
                        title = if (zh) "输出" else "Output",
                        onClear = { output = "" },
                        placeholder = if (zh) "启动后等待断点命中，再发调试命令" else "Wait for breakpoint, then send commands",
                        maxHeight = 420.dp,
                    )
                }
            }
        }
    }

    BusyOverlay(visible = busy, message = busyMsg.ifBlank { if (zh) "执行中…" else "Working…" })
}
