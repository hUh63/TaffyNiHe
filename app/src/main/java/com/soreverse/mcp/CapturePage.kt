package com.soreverse.mcp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
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
        }
        Spacer(Modifier.height(8.dp))
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
