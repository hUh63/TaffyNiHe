package com.soreverse.mcp

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.BinaryManager
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.NativeSoEngine
import com.soreverse.mcp.mcp.EdbgTools
import com.soreverse.mcp.mcp.JadxTool
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * eDBG 图形化界面：环境检测 / 部署 / 会话管理 / 调试命令面板 / 反编译（jadx）。
 * 需要 Root + 内核 5.10+（eBPF）。对标 taffy_edbg MCP 工具的操作。
 */
@Composable
internal fun EdbgPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var env by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pkgInput by remember { mutableStateOf("") }
    var libInput by remember { mutableStateOf("") }
    var brkInput by remember { mutableStateOf("") }
    var cmdInput by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var sessionActive by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("session") }

    // 反编译状态
    var decompileName by remember { mutableStateOf("") }
    var classList by remember { mutableStateOf<List<String>>(emptyList()) }
    var classSource by remember { mutableStateOf("") }
    val decompileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (input == null) err("READ_FAILED", "无法读取所选文件")
                    else {
                        val f = File(context.cacheDir, "edbg_decompile_input")
                        f.writeBytes(input)
                        decompileName = f.name
                        val ctx = ToolContext(context, settings, EngineProvider.get(context))
                        JadxTool.decompile.handle(ctx, JSONObject().put("action", "list").put("path", f.absolutePath).put("limit", 2000))
                    }
                }.getOrElse { e -> err("DECOMPILE_FAILED", e.message ?: "反编译失败") }
            }
            val list = res.optJSONArray("classes")
            classList = if (list != null) (0 until list.length()).map { list.optString(it) } else emptyList()
            classSource = ""
            busy = false
        }
    }

    fun probe() {
        scope.launch {
            busy = true
            env = withContext(Dispatchers.IO) {
                val ctx = ToolContext(context, settings, EngineProvider.get(context))
                EdbgTools.edbg.handle(ctx, JSONObject().put("action", "probe"))
            }
            busy = false
        }
    }

    fun runAction(action: String, after: (JSONObject) -> Unit = {}) {
        scope.launch {
            busy = true
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
            if (action == "cmd") {
                val o = res.optString("output")
                if (o.isNotBlank()) output = o
            } else if (action == "launch") {
                sessionActive = res.optBoolean("started", false)
                val o = res.optString("output")
                if (o.isNotBlank()) output = o
                if (!sessionActive) Toast.makeText(context, res.optString("message", res.toString()), Toast.LENGTH_SHORT).show()
            } else if (action == "stop") {
                sessionActive = false
            } else if (action == "install") {
                val ok = res.optBoolean("installed", false)
                Toast.makeText(context, if (ok) (if (zh) "部署成功" else "Installed") else res.optString("message", res.toString()), Toast.LENGTH_SHORT).show()
            }
            after(res)
            busy = false
        }
    }

    fun viewClass(cls: String) {
        scope.launch {
            busy = true
            classSource = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "edbg_decompile_input")
                val ctx = ToolContext(context, settings, EngineProvider.get(context))
                val res = JadxTool.decompile.handle(ctx, JSONObject().put("action", "class").put("path", f.absolutePath).put("className", cls))
                res.optString("source", res.optString("message", res.toString()))
            }
            busy = false
        }
    }

    // 进入页面自动 probe
    androidx.compose.runtime.LaunchedEffect(Unit) { probe() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 环境状态卡片 ──
        val envJson = env
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val rootOk = envJson?.optBoolean("root") == true
            val kernelOk = envJson?.optBoolean("kernel") == true || (envJson?.optString("kernelVersion").orEmpty().isNotBlank() && (envJson?.optString("kernelVersion").orEmpty() >= "5.10"))
            val deployed = envJson?.optBoolean("deployed") == true
            EnvBox(if (zh) "Root" else "Root", if (rootOk) "✓" else "✗", Modifier.weight(1f), rootOk)
            EnvBox(if (zh) "内核" else "Kernel", envJson?.optString("kernelVersion")?.take(12) ?: "-", Modifier.weight(1f), kernelOk)
            EnvBox(if (zh) "二进制" else "Binary", if (deployed) "✓" else "✗", Modifier.weight(1f), deployed)
        }
        envJson?.let { e ->
            val reason = e.optString("reason")
            if (reason.isNotBlank()) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { probe() }, enabled = !busy) {
                Icon(Icons.Default.BugReport, null, Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp)); Text(if (zh) "检测环境" else "Probe")
            }
            TextButton(onClick = { runAction("install") }, enabled = !busy) {
                Icon(Icons.Default.Download, null, Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp)); Text(if (zh) "部署 eDBG" else "Install")
            }
        }

        // ── 标签页：调试 / 反编译 ──
        val tabs = listOf(if (zh) "调试" else "Debug", if (zh) "反编译" else "Decompile")
        TabRow(selectedTabIndex = if (tab == "decompile") 1 else 0, containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = (tab == "decompile") == (i == 1), onClick = { tab = if (i == 1) "decompile" else "session" }, text = { Text(label, fontSize = 12.sp) })
            }
        }

        if (tab == "decompile") {
            // ── 反编译（jadx）：APK / DEX → 类列表 → 源码 ──
            TextButton(onClick = { decompileLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/java-archive")) }, enabled = !busy) {
                Icon(Icons.Default.Code, null, Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp)); Text(if (zh) "选择 APK / DEX / JAR" else "Pick APK / DEX / JAR")
            }
            if (decompileName.isNotBlank()) {
                Text(if (zh) "已加载：$decompileName（${classList.size} 个类）" else "Loaded: $decompileName (${classList.size} classes)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (classSource.isNotBlank()) {
                SelectionContainer {
                    Text(classSource, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                }
                TextButton(onClick = { classSource = "" }) { Text(if (zh) "返回类列表" else "Back to list") }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(classList) { cls ->
                        Text(
                            cls,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .clickable { viewClass(cls) }
                                .padding(8.dp),
                        )
                    }
                }
            }
        } else {
            // ── 调试会话 ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pkgInput,
                    onValueChange = { pkgInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (zh) "目标包名" else "Target package", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                if (sessionActive) {
                    TextButton(onClick = { runAction("stop") }) { Text(if (zh) "停止" else "Stop", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { runAction("launch") }, enabled = !busy && pkgInput.isNotBlank()) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.width(14.dp))
                        Spacer(Modifier.width(4.dp)); Text(if (zh) "启动调试" else "Launch")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = libInput,
                    onValueChange = { libInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (zh) "库名(可选)" else "Library (optional)", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = brkInput,
                    onValueChange = { brkInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("偏移 0x0", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
            // ── 命令面板：执行控制 / 断点内存 两组快捷 ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (zh) "执行" else "Run", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("c", "s", "finish", "run", "bt").forEach { quick ->
                    FilterChip(
                        selected = false,
                        onClick = { if (sessionActive) { cmdInput = quick; runAction("cmd") } },
                        enabled = sessionActive && !busy,
                        label = { Text(quick, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (zh) "断点" else "BP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 用偏移输入框的值直接下断点
                FilterChip(
                    selected = false,
                    onClick = { if (sessionActive && brkInput.isNotBlank()) { cmdInput = "b ${brkInput.trim()}"; runAction("cmd") } },
                    enabled = sessionActive && !busy && brkInput.isNotBlank(),
                    label = { Text("b ${brkInput.ifBlank { "0x…" }}", fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                )
                listOf("hbreak", "watch", "info regs", "x/16gx \$pc").forEach { quick ->
                    FilterChip(
                        selected = false,
                        onClick = { if (sessionActive) { cmdInput = quick; runAction("cmd") } },
                        enabled = sessionActive && !busy,
                        label = { Text(quick, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (zh) "调试命令（b 地址 / hbreak / watch / examine…）" else "Debug command (b addr / hbreak / watch / examine…)", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { if (cmdInput.isNotBlank()) runAction("cmd") }, enabled = sessionActive && !busy && cmdInput.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.width(14.dp))
                    Spacer(Modifier.width(4.dp)); Text(if (zh) "发送" else "Send")
                }
            }
            // ── 输出 ──
            SelectionContainer {
                Text(
                    output.ifBlank { if (zh) "（eDBG 输出显示在这里。启动后先等待断点命中，再发调试命令）" else "(eDBG output appears here. Wait for the breakpoint, then send commands.)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                        .padding(10.dp),
                )
            }
            if (busy) Text(if (zh) "执行中…" else "Working…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 环境状态小方块。 */
@Composable
private fun EnvBox(label: String, value: String, modifier: Modifier = Modifier, ok: Boolean) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
