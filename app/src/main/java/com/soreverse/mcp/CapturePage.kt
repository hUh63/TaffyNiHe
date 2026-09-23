package com.soreverse.mcp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.HttpCaptureServer
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.CaptureTools
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抓包图形化页：三区（HTTP 抓包 / 采集工具 / 教程）。
 * 对标 taffy_capture MCP 工具。无特权时明确限制（连接/抓包需 Root 或 Shizuku）。
 */
@Composable
internal fun CapturePage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }
    var ifaceInput by remember { mutableStateOf("any") }
    var filterInput by remember { mutableStateOf("") }
    var sniffing by remember { mutableStateOf(false) }
    var sniffPath by remember { mutableStateOf("") }
    var tcpdumpOk by remember { mutableStateOf(false) }

    var rootOk by remember { mutableStateOf(false) }
    var shizukuOk by remember { mutableStateOf(false) }
    var dhizukuOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootOk = PermissionManager.isRootAvailable()
            shizukuOk = PermissionManager.isShizukuGranted()
            dhizukuOk = PermissionManager.isDhizukuAvailable()
        }
    }
    val privOk = rootOk || shizukuOk || dhizukuOk

    // ── HTTP 抓包（本地代理，无需 Root）──
    val proxyPort = 8888
    val captureServer = remember { HttpCaptureServer(proxyPort) }
    var capturing by remember { mutableStateOf(false) }
    var captureEntries by remember { mutableStateOf<List<HttpCaptureServer.Entry>>(emptyList()) }
    var pendingClearCapture by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("http") }
    var listFilter by remember { mutableStateOf("") }
    var methodFilter by remember { mutableStateOf("全部") }
    LaunchedEffect(Unit) {
        captureServer.addListener { captureEntries = captureServer.snapshot() }
    }
    val filteredEntries = captureEntries.filter { e ->
        (methodFilter == "全部" || (methodFilter == "HTTPS" && e.isHttps) || (methodFilter == "HTTP" && !e.isHttps) || e.method == methodFilter) &&
            (listFilter.isBlank() || e.url.contains(listFilter, ignoreCase = true) || e.host.contains(listFilter, ignoreCase = true) || e.status.contains(listFilter, ignoreCase = true))
    }

    var detailEntry by remember { mutableStateOf<HttpCaptureServer.Entry?>(null) }
    var replaying by remember { mutableStateOf(false) }
    var replayResult by remember { mutableStateOf("") }

    fun replay(e: HttpCaptureServer.Entry) {
        replaying = true
        replayResult = ""
        scope.launch {
            replayResult = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(e.url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = e.method.uppercase()
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    e.reqHeaders.lineSequence().forEach { h ->
                        val idx = h.indexOf(':')
                        if (idx > 0) {
                            val k = h.substring(0, idx).trim()
                            val v = h.substring(idx + 1).trim()
                            if (k.isNotBlank() && !k.equals("host", true) && !k.equals("connection", true) &&
                                !k.equals("content-length", true) && !k.equals("accept-encoding", true)) {
                                runCatching { conn.setRequestProperty(k, v) }
                            }
                        }
                    }
                    val t0 = System.currentTimeMillis()
                    val code = conn.responseCode
                    val ms = System.currentTimeMillis() - t0
                    val bodyPreview = runCatching {
                        (if (code in 200..399) conn.inputStream else conn.errorStream)?.use { s ->
                            val b = ByteArray(2048)
                            val n = s.read(b)
                            String(b, 0, if (n > 0) n else 0)
                        } ?: ""
                    }.getOrDefault("")
                    "HTTP $code · ${ms}ms\n" + (if (bodyPreview.isNotBlank()) "响应预览:\n" + bodyPreview.take(600) else "（无响应体）")
                }.getOrElse { "重放失败: ${it.message}" }
            }
            replaying = false
        }
    }

    fun toggleCapture() {
        if (capturing) {
            captureServer.stop()
            capturing = false
            Toast.makeText(context, if (zh) "抓包已停止——记得清除 WIFI 代理，否则会断网！" else "Capture stopped — clear the WIFI proxy or you will lose network!", Toast.LENGTH_LONG).show()
        } else {
            val ok = captureServer.start()
            capturing = ok
            if (!ok) {
                Toast.makeText(context, if (zh) "代理启动失败（端口被占用?）" else "Proxy failed to start", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, if (zh) "抓包已启动，请把 WIFI 代理设为 127.0.0.1:$proxyPort" else "Capture started; set WIFI proxy to 127.0.0.1:$proxyPort", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun callTool(action: String, extra: JSONObject.() -> Unit = {}) {
        scope.launch {
            busy = true
            busyMsg = action
            val res = withContext(Dispatchers.IO) {
                val ctx = ToolContext(context, settings, EngineProvider.get(context))
                CaptureTools.capture.handle(ctx, JSONObject().apply { put("action", action); extra() })
            }
            output = res.toString(2)
            if (action == "sniff_status") {
                sniffing = res.optBoolean("running")
                sniffPath = res.optString("pcapPath")
            }
            if (action == "sniff_start" && res.optString("status") == "sniffing") {
                sniffing = true
                sniffPath = res.optString("pcapPath")
            }
            if (action == "sniff_stop") sniffing = false
            if (!res.optBoolean("ok", true)) {
                Toast.makeText(context, res.optJSONObject("error")?.optString("message") ?: (if (zh) "执行失败" else "Failed"), Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    fun probeEnv() {
        scope.launch {
            tcpdumpOk = withContext(Dispatchers.IO) {
                val r = PermissionManager.exec("which tcpdump 2>/dev/null || ls /system/xbin/tcpdump /data/local/tmp/tcpdump 2>/dev/null", timeoutSec = 8)
                r.stdout.isNotBlank() || r.stderr.contains("tcpdump")
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tab == "http", onClick = { tab = "http" }, label = { Text(if (zh) "HTTP 抓包" else "HTTP capture") })
            FilterChip(selected = tab == "tools", onClick = { tab = "tools" }, label = { Text(if (zh) "采集工具" else "Tools") })
            FilterChip(selected = tab == "guide", onClick = { tab = "guide" }, label = { Text(if (zh) "教程" else "Guide") })
        }

        when (tab) {
            "guide" -> {
                GuideView(zh, Modifier.fillMaxWidth().weight(1f))
            }

            "http" -> {
                GlassGroup(title = if (zh) "HTTP 抓包控制" else "HTTP capture control") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryActionButton(
                            if (capturing) (if (zh) "停止抓包" else "Stop") else (if (zh) "开始抓包" else "Start"),
                            { toggleCapture() },
                            Modifier.weight(1f),
                            leading = if (capturing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            container = if (capturing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    DataRow(
                        title = "127.0.0.1:$proxyPort",
                        subtitle = if (capturing) (if (zh) "抓包中 · 访问流量实时出现在列表" else "Capturing · traffic appears live") else (if (zh) "未启动" else "Stopped"),
                        meta = if (zh) "把设备 WIFI 代理设为该地址" else "Set WIFI proxy to this address",
                        trailingText = if (capturing) "●" else "○",
                    )
                }

                OutlinedTextField(
                    value = listFilter,
                    onValueChange = { listFilter = it },
                    placeholder = { Text(if (zh) "过滤：域名 / 路径 / 状态码" else "Filter: host / path / status", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部", "HTTPS", "HTTP", "GET", "POST", "CONNECT").forEach { m ->
                        FilterChip(selected = methodFilter == m, onClick = { methodFilter = m }, label = { Text(m) })
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (if (zh) "抓包记录" else "Captures") + " · " + filteredEntries.size + "/" + captureEntries.size,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val json = JSONArray().apply {
                            filteredEntries.forEach { e ->
                                put(
                                    JSONObject()
                                        .put("time", e.time).put("method", e.method).put("url", e.url)
                                        .put("host", e.host).put("path", e.path).put("status", e.status)
                                        .put("bytes", e.bytes).put("elapsedMs", e.elapsedMs).put("https", e.isHttps),
                                )
                            }
                        }
                        copyToClipboard(context, json.toString(2))
                        Toast.makeText(context, if (zh) "已复制 ${filteredEntries.size} 条（JSON）" else "Copied ${filteredEntries.size} entries", Toast.LENGTH_SHORT).show()
                    }) { Text(if (zh) "导出 JSON" else "Export") }
                    TextButton(onClick = { pendingClearCapture = true }) { Text(if (zh) "清除" else "Clear", color = MaterialTheme.colorScheme.error) }
                }

                if (filteredEntries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        InlineHint(
                            if (captureEntries.isEmpty()) {
                                if (zh) "暂无抓包记录\n启动代理并设置 WIFI 代理后，应用流量会实时出现在这里" else "No captures yet\nStart the proxy and set WIFI proxy"
                            } else {
                                if (zh) "当前过滤条件下无匹配记录" else "No match for current filter"
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filteredEntries.size) { i ->
                            val e = filteredEntries[i]
                            GlassGroup {
                                DataRow(
                                    title = e.url,
                                    subtitle = (if (e.isHttps) "HTTPS" else "HTTP") + " · " + e.status.ifBlank { "—" } + " · " + (e.bytes / 1024) + " KB · " + e.elapsedMs + "ms",
                                    meta = e.time + "  " + e.method,
                                    leading = { TypeChip(e.method.ifBlank { "—" }, methodColor(e.method)) },
                                    onClick = { detailEntry = e; replayResult = "" },
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassGroup(title = if (zh) "抓包环境" else "Capture environment") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill("Root", rootOk, Modifier.weight(1f))
                            StatusPill("Shizuku", shizukuOk, Modifier.weight(1f))
                            StatusPill("Dhizuku", dhizukuOk, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill("tcpdump", tcpdumpOk, Modifier.weight(1f))
                            TextButton(onClick = { probeEnv() }) { Text(if (zh) "检测" else "Probe") }
                        }
                        InlineHint(
                            if (zh) "连接列表与 tcpdump 需要 Root 或 Shizuku；接口/流量/DNS 无特权也可查看。抓包文件输出到 /data/local/tmp。"
                            else "Connections & tcpdump need Root/Shizuku; interfaces/traffic/DNS work without privilege. Captures go to /data/local/tmp.",
                        )
                    }

                    GlassGroup(title = if (zh) "快捷采集" else "Quick collect") {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = false, onClick = { callTool("info") }, label = { Text(if (zh) "接口信息" else "Interfaces") }, enabled = !busy)
                            FilterChip(selected = false, onClick = { callTool("conn") }, label = { Text(if (zh) "连接列表" else "Connections") }, enabled = !busy && privOk)
                            FilterChip(selected = false, onClick = { callTool("traffic") }, label = { Text(if (zh) "流量统计" else "Traffic") }, enabled = !busy)
                            FilterChip(selected = false, onClick = { callTool("dns") }, label = { Text("DNS") }, enabled = !busy)
                        }
                    }

                    GlassGroup(title = if (zh) "tcpdump 抓包" else "tcpdump capture") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = ifaceInput,
                                onValueChange = { ifaceInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("interface", maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = filterInput,
                                onValueChange = { filterInput = it },
                                modifier = Modifier.weight(2f),
                                placeholder = { Text(if (zh) "过滤表达式（如 tcp port 443）" else "filter (e.g. tcp port 443)", maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (sniffing) {
                                TextButton(onClick = { callTool("sniff_stop") }) {
                                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (zh) "停止" else "Stop", color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                TextButton(onClick = { callTool("sniff_start") { put("interface", ifaceInput); put("filter", filterInput) } }, enabled = privOk && !busy) {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (zh) "开始" else "Start")
                                }
                            }
                            TextButton(onClick = { callTool("sniff_status") }, enabled = !busy) {
                                Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (zh) "状态" else "Status")
                            }
                            if (sniffPath.isNotBlank()) {
                                TextButton(onClick = { callTool("sniff_pull") }, enabled = !busy) {
                                    Icon(Icons.Default.Cloud, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (zh) "拉取" else "Pull")
                                }
                            }
                        }
                        if (sniffing) {
                            InlineHint(if (zh) "抓包中… $sniffPath" else "Sniffing… $sniffPath")
                        }
                    }

                    TerminalPane(
                        text = output,
                        title = if (zh) "输出" else "Output",
                        onClear = { output = "" },
                        placeholder = if (zh) "点击上方按钮执行采集，结果输出在这里" else "Run a collect action; output shows here",
                        maxHeight = 460.dp,
                    )
                }
            }
        }
    }

    // ── 请求详情弹窗 ──
    detailEntry?.let { e ->
        ReplayAwareDetail(
            zh = zh,
            e = e,
            replaying = replaying,
            replayResult = replayResult,
            onReplay = { replay(e) },
            onDismiss = { detailEntry = null },
        )
    }

    if (pendingClearCapture) {
        ConfirmDialog(
            title = if (zh) "清除抓包记录？" else "Clear captured entries?",
            message = if (zh) "将清空当前全部抓包记录（共 ${captureEntries.size} 条），此操作不可恢复。" else "Clears all ${captureEntries.size} captured entries. This cannot be undone.",
            confirmText = if (zh) "清除" else "Clear",
            destructive = true,
            onConfirm = { captureServer.clear(); captureEntries = emptyList() },
            onDismiss = { pendingClearCapture = false },
        )
    }

    BusyOverlay(visible = busy, message = busyMsg.ifBlank { if (zh) "处理中…" else "Working…" })
}

private fun methodColor(method: String) = when (method.uppercase()) {
    "GET" -> AppPalette.green
    "POST" -> AppPalette.orange
    "CONNECT" -> AppPalette.purple
    "WS" -> AppPalette.yellow
    else -> AppPalette.mono
}

/** 教程区：深色等宽 + 复制按钮（内容较长，独立滚动）。 */
@Composable
private fun GuideView(zh: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val metrics = LocalUiMetrics.current
    val shape = RoundedCornerShape(metrics.cardRadius)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (zh) "抓包教程" else "Capture guide",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copyToClipboard(context, CAPTURE_GUIDE) }) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (zh) "复制" else "Copy")
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(TerminalColors.bg)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    CAPTURE_GUIDE,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                    color = TerminalColors.fg,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** 请求详情弹窗（HTTP 头 / WS 帧 / 重放），长文本均用深色等宽块。 */
@Composable
private fun ReplayAwareDetail(
    zh: Boolean,
    e: HttpCaptureServer.Entry,
    replaying: Boolean,
    replayResult: String,
    onReplay: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.method + " · " + e.status.ifBlank { if (e.isHttps) "隧道" else "—" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoBlock(e.url)
                Text(
                    "${e.time} · ${e.bytes / 1024} KB · ${e.elapsedMs}ms · ${if (e.isHttps) "HTTPS(加密隧道)" else "HTTP"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e.isWs) {
                    MonoLabel(if (zh) "WebSocket 帧（↑客户端发 / ↓服务端回，最多 200 条）" else "WS frames (↑client / ↓server, max 200)")
                    MonoBlock(e.frames.ifEmpty { listOf(if (zh) "（尚未捕获到帧——保持会话打开）" else "(no frames yet)") }.joinToString("\n"))
                }
                if (e.reqHeaders.isNotBlank()) {
                    MonoLabel(if (zh) "请求头" else "Request headers")
                    MonoBlock(e.reqHeaders)
                }
                if (e.respHeaders.isNotBlank()) {
                    MonoLabel(if (zh) "响应头" else "Response headers")
                    MonoBlock(e.respHeaders)
                }
                if (e.isHttps) {
                    Text(
                        if (zh) "ⓘ HTTPS 为加密隧道：无明文请求头，不可重放。解密方案见「教程」。" else "ⓘ HTTPS tunnel: no plaintext headers, not replayable. See Guide.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPalette.orange,
                    )
                }
                if (replayResult.isNotBlank()) {
                    MonoLabel(if (zh) "重放结果" else "Replay result")
                    MonoBlock(replayResult)
                }
            }
        },
        confirmButton = {
            if (e.replayable) {
                TextButton(onClick = onReplay, enabled = !replaying) {
                    Text(if (replaying) "…" else if (zh) "重放请求" else "Replay")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (zh) "关闭" else "Close") } },
    )
}

@Composable
private fun MonoLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun MonoBlock(text: String) {
    val metrics = LocalUiMetrics.current
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 14.sp),
            color = TerminalColors.fg,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(metrics.controlRadius))
                .background(TerminalColors.bg)
                .padding(8.dp),
        )
    }
}

/** 状态小胶囊：可用=绿，不可用=红。 */
@Composable
private fun StatusPill(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    val metrics = LocalUiMetrics.current
    Row(
        modifier
            .clip(RoundedCornerShape(metrics.controlRadius))
            .background(if (ok) AppPalette.green.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "●" else "○", color = if (ok) AppPalette.green else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (ok) AppPalette.green else MaterialTheme.colorScheme.error, maxLines = 1)
    }
}

/** 内置完整教程（离线可用）——原理/证书专题/流程/过滤/FAQ/能力边界。 */
private const val CAPTURE_GUIDE = """═══ 塔菲抓包 · 完整教程 ═══

【1. 工作原理（先懂这个再抓包）】
塔菲抓包 = 本地 TCP 代理（127.0.0.1:8888）。
把设备 WiFi 代理指向它后，应用流量先经过塔菲：
  · HTTP  明文 → 记录完整 URL、状态码、大小、耗时
  · HTTPS 加密 → 记录域名（CONNECT 隧道）、大小、耗时
塔菲默认记录元数据（不解密内容）。这与 ProxyPin
开启 MITM 解密不同——不解密就没有证书烦恼，但也
看不到 HTTPS 请求体。

【2. HTTPS 证书专题（抓包第一大坑）】
为什么别人能看 HTTPS 内容而塔菲只显示域名？
  · 解密 HTTPS 需要 MITM：代理自签 CA 证书，
    并让应用信任它。
  · Android 7.0 起应用默认【不信任用户证书】，
    只认系统证书 —— 这是所有抓包工具共同的坎。
想在 Android 上解密 HTTPS，只有这些路：
  ① Root 后把 CA 装进系统证书目录
     /system/etc/security/cacerts/
     （Magisk 模块：MoveCertificate / ProxyPinCA 等）
  ② 目标 app 的 targetSdk < 24（已少见）
  ③ 修改 apk 的 networkSecurityConfig 信任用户证书
     （塔菲的 APK 编辑工具可改 manifest/重签）
  无 Root + 高版本 Android + 新版 app：
     目前无解，只能拿元数据（域名/大小/频率）。

【3. 使用流程】
  ① 「HTTP 抓包」tab → 开始抓包
  ② 设置 → WLAN → 修改网络 → 代理 → 手动
     主机 127.0.0.1  端口 8888
  ③ 打开目标 app 操作，记录实时出现在列表
  ④ 用过滤框输入域名/路径/状态码快速定位
  ⑤ 「导出 JSON」复制给 AI/MCP 继续分析
  ⑥ 用完【停止抓包】并【清除 WiFi 代理】，
     否则塔菲关闭后设备会断网！

【4. 过滤技巧】
  · 过滤框是子串匹配（域名/路径/状态码均可）
  · 「CONNECT」= 全部 HTTPS 隧道
  · 定位 API：输入 "api" 或 "/v2/" 这类路径片段
  · 定位某 app：输入它的主域名（如 "douyin"）

【5. 采集工具 tab（Root/Shizuku）】
  · 连接列表/流量统计/DNS：系统级视角
  · tcpdump：链路层抓包（pcap，Wireshark 可开），
    能看到 TLS SNI（域名），无需证书

【5b. WebSocket 明文抓取】
  ws://（明文）连接自动识别：条目 method 显示 WS，
  点开可看双向帧（↑客户端 / ↓服务端）。wss:// 无法
  旁路——同 HTTPS 限制。

【6. 与 ProxyPin 的差异（能力边界）】
  ✅ 塔菲: 零 root 元数据抓包 + tcpdump + 导出 JSON
     + 明文 HTTP 请求头捕获/详情/重放 + MCP/AI 联动
  ❌ 塔菲: 暂无 HTTPS MITM 解密 / 重写脚本

【7. 常见问题】
  Q: 开了抓包 App 全断网？
     A: 代理没配对（127.0.0.1:8888）或塔菲被杀。
        清除 WiFi 代理即恢复。
  Q: 某 App 完全没有记录？
     A: 它可能不走系统代理，用 tcpdump 抓链路层。
  Q: 应用分身/双开抓不到？
     A: 分身在独立用户空间，本地代理天然抓不到。
  Q: 长时间抓包会崩吗？
     A: 列表环形缓冲上限 500 条，自动淘汰最旧。
"""
