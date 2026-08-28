package com.soreverse.mcp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.mcp.CaptureTools
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 抓包图形化界面：权限/环境检测 / 快捷采集（接口、连接、流量、DNS）/ tcpdump 抓包控制 / 输出查看。
 * 对标 taffy_capture MCP 工具。无特权时明确提示限制（连接/抓包需 Root 或 Shizuku）。
 */
@Composable
internal fun CapturePage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ifaceInput by remember { mutableStateOf("any") }
    var filterInput by remember { mutableStateOf("") }
    var sniffing by remember { mutableStateOf(false) }
    var sniffPath by remember { mutableStateOf("") }
    var tcpdumpOk by remember { mutableStateOf(false) }

    // 权限探测放 IO 线程（isRootAvailable 首次探测可能等待 su 超时，组合期调用会卡 UI）
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
    val captureServer = remember { com.soreverse.mcp.core.HttpCaptureServer(proxyPort) }
    var capturing by remember { mutableStateOf(false) }
    var captureEntries by remember { mutableStateOf<List<com.soreverse.mcp.core.HttpCaptureServer.Entry>>(emptyList()) }
    var tab by remember { mutableStateOf("http") }
    // 列表过滤（借鉴 ProxyPin #705 精确过滤诉求）
    var listFilter by remember { mutableStateOf("") }
    var methodFilter by remember { mutableStateOf("全部") }
    LaunchedEffect(Unit) {
        captureServer.addListener {
            captureEntries = captureServer.snapshot()
        }
    }
    val filteredEntries = captureEntries.filter { e ->
        (methodFilter == "全部" || (methodFilter == "HTTPS" && e.isHttps) || (methodFilter == "HTTP" && !e.isHttps) || e.method == methodFilter) &&
            (listFilter.isBlank() || e.url.contains(listFilter, ignoreCase = true) || e.host.contains(listFilter, ignoreCase = true) || e.status.contains(listFilter, ignoreCase = true))
    }
    // 详情弹窗 + 重放（明文 HTTP GET/HEAD；HTTPS 为加密隧道无法重放）
    var detailEntry by remember { mutableStateOf<com.soreverse.mcp.core.HttpCaptureServer.Entry?>(null) }
    var replaying by remember { mutableStateOf(false) }
    var replayResult by remember { mutableStateOf("") }
    fun replay(e: com.soreverse.mcp.core.HttpCaptureServer.Entry) {
        replaying = true
        replayResult = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(e.url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = e.method.uppercase()
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    // 复制原请求头（跳过宿主相关的）
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
                            val b = ByteArray(2048); val n = s.read(b); String(b, 0, if (n > 0) n else 0)
                        } ?: ""
                    }.getOrDefault("")
                    "HTTP $code · ${ms}ms\n" + (if (bodyPreview.isNotBlank()) "响应预览:\n" + bodyPreview.take(600) else "（无响应体）")
                }.getOrElse { "重放失败: ${it.message}" }
            }
            replaying = false
            replayResult = r
        }
    }
    fun toggleCapture() {
        if (capturing) {
            captureServer.stop()
            capturing = false
        } else {
            val ok = captureServer.start()
            capturing = ok
            if (!ok) Toast.makeText(context, if (zh) "代理启动失败（端口被占用?）" else "Proxy failed to start", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, if (zh) "抓包已启动，请把 WIFI 代理设为 127.0.0.1:$proxyPort" else "Capture started; set WIFI proxy to 127.0.0.1:$proxyPort", Toast.LENGTH_LONG).show()
        }
    }

    fun callTool(action: String, extra: JSONObject.() -> Unit = {}) {
        scope.launch {
            busy = true
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
                Toast.makeText(
                    context,
                    res.optJSONObject("error")?.optString("message") ?: (if (zh) "执行失败" else "Failed"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            busy = false
        }
    }

    fun probeEnv() {
        scope.launch {
            val probe = withContext(Dispatchers.IO) {
                val r = PermissionManager.exec("which tcpdump 2>/dev/null || ls /system/xbin/tcpdump /data/local/tmp/tcpdump 2>/dev/null", timeoutSec = 8)
                r.stdout.isNotBlank() || r.stderr.contains("tcpdump")
            }
            tcpdumpOk = probe
        }
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── tab 选择：HTTP 抓包 / 采集工具 / 教程 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tab == "http", onClick = { tab = "http" }, label = { Text(if (zh) "HTTP 抓包" else "HTTP capture", fontSize = 11.sp) })
            FilterChip(selected = tab == "tools", onClick = { tab = "tools" }, label = { Text(if (zh) "采集工具" else "Tools", fontSize = 11.sp) })
            FilterChip(selected = tab == "guide", onClick = { tab = "guide" }, label = { Text(if (zh) "教程" else "Guide", fontSize = 11.sp) })
        }
        if (tab == "guide") {
            SelectionContainer {
                Text(
                    CAPTURE_GUIDE,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color(0xFF0B0F14)).padding(12.dp),
                )
            }
        }
        if (tab == "http") {
            // ── HTTP 抓包（本地代理，无需 Root）──
            GlassGroup(title = if (zh) "HTTP 抓包控制" else "HTTP capture control") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { toggleCapture() }) {
                        Icon(if (capturing) Icons.Default.Stop else Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (capturing) (if (zh) "停止抓包" else "Stop") else (if (zh) "开始抓包" else "Start"),
                            color = if (capturing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        (if (zh) "代理: " else "Proxy: ") + "127.0.0.1:$proxyPort" + if (capturing) (if (zh) " · 抓包中" else " · capturing") else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (capturing) AppPalette.green else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (zh)
                        "使用方式：启动代理后，把设备的 WIFI 代理设为 127.0.0.1:$proxyPort（设置 → WLAN → 修改网络 → 代理 → 手动）。" +
                        "HTTPS 记录域名与流量大小（内容加密），HTTP 记录完整 URL 与状态码。"
                    else
                        "Usage: start the proxy, then set WIFI proxy to 127.0.0.1:$proxyPort (Settings → WLAN → modify network → proxy → manual). " +
                        "HTTPS shows host & bytes (encrypted); HTTP shows full URL & status.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 抓包列表（过滤 + 导出）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (zh) "抓包记录" else "Captures") + " · " + filteredEntries.size + "/" + captureEntries.size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val json = org.json.JSONArray().apply {
                        filteredEntries.forEach { e ->
                            put(org.json.JSONObject()
                                .put("time", e.time).put("method", e.method).put("url", e.url)
                                .put("host", e.host).put("path", e.path).put("status", e.status)
                                .put("bytes", e.bytes).put("elapsedMs", e.elapsedMs).put("https", e.isHttps))
                        }
                    }
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(json.toString(2)))
                    Toast.makeText(context, if (zh) "已复制 ${filteredEntries.size} 条（JSON）" else "Copied ${filteredEntries.size} entries (JSON)", Toast.LENGTH_SHORT).show()
                }) { Text(if (zh) "导出 JSON" else "Export JSON", fontSize = 11.sp) }
                TextButton(onClick = { captureServer.clear(); captureEntries = emptyList() }) { Text(if (zh) "清除" else "Clear", fontSize = 11.sp) }
            }
            // 过滤行：关键字 + 协议/方法 chips
            OutlinedTextField(
                value = listFilter,
                onValueChange = { listFilter = it },
                placeholder = { Text(if (zh) "过滤: 域名 / 路径 / 状态码…" else "Filter: host / path / status…", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "HTTPS", "HTTP", "GET", "POST", "CONNECT").forEach { m ->
                    FilterChip(selected = methodFilter == m, onClick = { methodFilter = m }, label = { Text(m, fontSize = 9.sp) })
                }
            }
            if (filteredEntries.isEmpty()) {
                Text(
                    if (zh) "暂无抓包记录\n启动代理并设置 WIFI 代理后，应用流量会出现在这里" else "No captures yet\nStart the proxy and set WIFI proxy; app traffic appears here",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filteredEntries.size) { i ->
                        val e = filteredEntries[filteredEntries.size - 1 - i]
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                .clickable { detailEntry = e; replayResult = "" }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    e.time + " " + e.method,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                                    color = when (e.method) {
                                        "GET" -> AppPalette.green
                                        "POST" -> AppPalette.orange
                                        "CONNECT" -> AppPalette.purple
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    e.url,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    (if (e.isHttps) "HTTPS" else "HTTP") + " · " + e.status.ifBlank { "—" } + " · " + (e.bytes / 1024) + " KB · " + e.elapsedMs + "ms",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
        // ── 环境检测卡片 ──
        GlassGroup(title = if (zh) "抓包环境" else "Capture environment") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(if (zh) "Root" else "Root", rootOk, Modifier.weight(1f))
                StatusPill("Shizuku", shizukuOk, Modifier.weight(1f))
                StatusPill("Dhizuku", dhizukuOk, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("tcpdump", tcpdumpOk, Modifier.weight(1f))
                TextButton(onClick = { probeEnv() }) { Text(if (zh) "检测" else "Probe", fontSize = 12.sp) }
            }
            Text(
                if (zh)
                    "连接列表与 tcpdump 抓包需要 Root 或 Shizuku 权限；接口/流量/DNS 无特权也可查看。抓包文件输出到 /data/local/tmp。"
                else
                    "Connections & tcpdump need Root or Shizuku; interfaces/traffic/DNS work without privilege. Captures go to /data/local/tmp.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── 快捷采集 ──
        GlassGroup(title = if (zh) "快捷采集" else "Quick collect") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = false, onClick = { callTool("info") }, label = { Text(if (zh) "接口信息" else "Interfaces", fontSize = 11.sp) }, enabled = !busy)
                FilterChip(selected = false, onClick = { callTool("conn") }, label = { Text(if (zh) "连接列表" else "Connections", fontSize = 11.sp) }, enabled = !busy && privOk)
                FilterChip(selected = false, onClick = { callTool("traffic") }, label = { Text(if (zh) "流量统计" else "Traffic", fontSize = 11.sp) }, enabled = !busy)
                FilterChip(selected = false, onClick = { callTool("dns") }, label = { Text("DNS", fontSize = 11.sp) }, enabled = !busy)
            }
        }

        // ── tcpdump 抓包 ──
        GlassGroup(title = if (zh) "tcpdump 抓包" else "tcpdump capture") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = ifaceInput,
                    onValueChange = { ifaceInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("interface", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = filterInput,
                    onValueChange = { filterInput = it },
                    modifier = Modifier.weight(2f),
                    placeholder = { Text(if (zh) "过滤表达式(如 tcp port 443)" else "filter (e.g. tcp port 443)", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sniffing) {
                    TextButton(onClick = { callTool("sniff_stop") }) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (zh) "停止抓包" else "Stop", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { callTool("sniff_start") { put("interface", ifaceInput); put("filter", filterInput) } }, enabled = privOk && !busy) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (zh) "开始抓包" else "Start", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { callTool("sniff_status") }, enabled = !busy) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (zh) "状态" else "Status", fontSize = 12.sp)
                }
                if (sniffPath.isNotBlank()) {
                    TextButton(onClick = {
                        callTool("sniff_pull")
                        // 拉取成功后输出里会带路径
                    }, enabled = !busy) {
                        Icon(Icons.Default.Cloud, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (zh) "拉取" else "Pull", fontSize = 12.sp)
                    }
                }
                if (sniffing) {
                    Text(
                        if (zh) "抓包中… $sniffPath" else "Sniffing… $sniffPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                }
            }
        }

        // ── 输出查看器 ──
        Text(
            if (zh) "输出" else "Output",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(androidx.compose.ui.graphics.Color(0xFF111111))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (output.isBlank()) {
                    Text(
                        if (zh) "点击上方按钮执行采集，结果输出在这里" else "Run a collect action above; output shows here",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = androidx.compose.ui.graphics.Color(0xFF888888),
                    )
                } else {
                    output.split("\n").forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                            color = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
                        )
                    }
                }
            }
            // 输出操作：复制 / 清空
            if (output.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(output))
                        Toast.makeText(context, if (zh) "已复制" else "Copied", Toast.LENGTH_SHORT).show()
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (zh) "复制" else "Copy", fontSize = 10.sp)
                    }
                    TextButton(onClick = { output = "" }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (zh) "清空" else "Clear", fontSize = 10.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ── 请求详情弹窗（点击记录打开）──
    detailEntry?.let { e ->
        AlertDialog(
            onDismissRequest = { detailEntry = null },
            title = { Text(e.method + " · " + e.status.ifBlank { if (e.isHttps) "隧道" else "—" }, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.url, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                    Text("${e.time} · ${e.bytes / 1024} KB · ${e.elapsedMs}ms · ${if (e.isHttps) "HTTPS(加密隧道)" else "HTTP"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (e.isWs) {
                        Text(
                            if (zh) "WebSocket 帧（↑客户端发 / ↓服务端回，最多 200 条）" else "WS frames (↑client / ↓server, max 200)",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        )
                        SelectionContainer {
                            Text(
                                e.frames.ifEmpty { listOf(if (zh) "（尚未捕获到帧——保持会话打开）" else "(no frames yet)") }.joinToString("\n"),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF0B0F14)).padding(8.dp),
                            )
                        }
                    }
                    if (e.reqHeaders.isNotBlank()) {
                        Text(if (zh) "请求头" else "Request headers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Text(e.reqHeaders, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF0B0F14)).padding(8.dp))
                        }
                    }
                    if (e.respHeaders.isNotBlank()) {
                        Text(if (zh) "响应头" else "Response headers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Text(e.respHeaders, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF0B0F14)).padding(8.dp))
                        }
                    }
                    if (e.isHttps) {
                        Text(if (zh) "ⓘ HTTPS 为加密隧道：无明文请求头，不可重放。解密方案见「教程」tab。" else "ⓘ HTTPS tunnel: no plaintext headers, not replayable. See Guide tab.", style = MaterialTheme.typography.labelSmall, color = AppPalette.orange, fontSize = 9.sp)
                    }
                    if (replayResult.isNotBlank()) {
                        Text(if (zh) "重放结果" else "Replay result", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Text(replayResult, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF0B0F14)).padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                if (e.replayable) {
                    TextButton(onClick = { replay(e) }, enabled = !replaying) { Text(if (replaying) "…" else if (zh) "重放请求" else "Replay") }
                }
            },
            dismissButton = { TextButton(onClick = { detailEntry = null }) { Text(if (zh) "关闭" else "Close") } },
        )
    }
}

/** 状态小胶囊：可用=绿，不可用=红。 */
@Composable
private fun StatusPill(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(if (ok) AppPalette.green.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (ok) "●" else "○",
            color = if (ok) AppPalette.green else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) AppPalette.green else MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}

/** 内置完整教程（离线可用，无需去官网）——原理/证书专题/流程/过滤/FAQ/能力边界。 */
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
    只认系统证书 —— 这是所有抓包工具共同的坎
    （ProxyPin 上游一半 issues 都是它）。
想在 Android 上解密 HTTPS，只有这些路：
  ① Root 后把 CA 装进系统证书目录
     /system/etc/security/cacerts/
     （Magisk 模块：MoveCertificate / ProxyPinCA 等）
  ② 目标 app 的 targetSdk < 24（已少见）
  ③ 修改 apk 的 networkSecurityConfig 信任用户证书
     （塔菲的 APK 编辑工具可改 manifest/重签）
  无 Root + 高版本 Android + 新版 app：
     目前无解，只能拿元数据（域名/大小/频率）。
  排查提示：装了 Shamiko/隐藏模块的系统，Magisk
  模块挂载可能失效（上游 #200/#741），先关隐藏再试。

【3. 使用流程】
  ① 「HTTP 抓包」tab → 开始抓包
  ② 设置 → WLAN → 修改网络 → 代理 → 手动
     主机 127.0.0.1  端口 8888
  ③ 打开目标 app 操作，记录实时出现在列表
  ④ 用过滤框输入域名/路径/状态码快速定位
     （方法/协议 chips：HTTPS/HTTP/GET/POST/CONNECT）
  ⑤ 「导出 JSON」复制给 AI/MCP 继续分析
  ⑥ 用完【停止抓包】并【清除 WiFi 代理】，
     否则塔菲关闭后设备会断网（代理指向已死的端口）！

【4. 过滤技巧】
  · 过滤框是子串匹配（域名/路径/状态码均可）
  · 「CONNECT」= 全部 HTTPS 隧道
  · 定位 API：输入 "api" 或 "/v2/" 这类路径片段
  · 定位某 app：输入它的主域名（如 "douyin"）

【5. 采集工具 tab（Root/Shizuku）】
  · 连接列表/流量统计/DNS：系统级视角
  · tcpdump：链路层抓包（pcap，Wireshark 可开），
    能看到 TLS SNI（域名），无需证书

【5b. WebSocket 明文抓取（新）】
  ws://（明文）连接自动识别：条目 method 显示 WS，
  点开可看双向帧（↑客户端 / ↓服务端），文本帧直接
  显示内容（text/close/ping/pong 分类，客户端帧自动
  去 mask 还原）。wss://（TLS 内 WS）无法旁路——同
  HTTPS 限制。HTTP/2：明文 h2c 会标记 H2C（二进制
  分帧不解析）；真正的 h2 都走 TLS，需 MITM 才能看。

【6. 与 ProxyPin 的差异（能力边界）】
  ✅ 塔菲: 零 root 元数据抓包 + tcpdump + 导出 JSON
     + 明文 HTTP 请求头捕获/详情/重放
     + 与 MCP/AI 联动（可直接让 AI 分析流量结构）
  ❌ 塔菲: 暂无 HTTPS MITM 解密 / 重写脚本
  需要"看 HTTPS 明文请求体"时：先按第 2 节把证书
  装成系统证书的思路解决信任问题，再选工具。
  逆向场景下更推荐：塔菲 eDBG/动态沙箱直接看行为。

【7. 常见问题】
  Q: 开了抓包 App 全断网？
     A: 代理没配对（127.0.0.1:8888）或塔菲被杀。
        清除 WiFi 代理即恢复。
  Q: 某 App 完全没有记录？
     A: 它可能不走系统代理（硬编码直连/自建通道）。
        用「采集工具」的 tcpdump 通道抓链路层。
  Q: 应用分身/双开抓不到？
     A: 分身在独立用户空间，本地代理方式天然抓不到
        （上游 #636 同款问题），需 VpnService 方案。
  Q: 长时间抓包会崩吗？
     A: 塔菲列表环形缓冲上限 500 条，自动淘汰最旧，
        不会像 ProxyPin 那样长跑 OOM（上游 #456）。
"""
