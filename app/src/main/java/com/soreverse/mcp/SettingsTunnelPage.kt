package com.soreverse.mcp

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.BoreTunnelService
import com.soreverse.mcp.core.CloudflareTunnelManager
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.service.McpForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsTunnelPage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tunnelType by remember { mutableStateOf(settings.tunnelType) }
    var tunnelMode by remember { mutableStateOf(settings.tunnelMode) }
    var tunnelAutoStart by remember { mutableStateOf(settings.tunnelAutoStart) }
    var tunnelPort by remember { mutableStateOf(settings.tunnelTargetPort.toString()) }
    var namedToken by remember { mutableStateOf(settings.tunnelNamedToken) }
    var namedPublicUrl by remember { mutableStateOf(settings.tunnelNamedPublicUrl) }
    var boreHost by remember { mutableStateOf(settings.boreHost) }
    var borePort by remember { mutableStateOf(settings.borePort.toString()) }
    var boreSecret by remember { mutableStateOf(settings.boreSecret) }
    var tunnelProtocol by remember { mutableStateOf(settings.tunnelProtocol) }
    var edgeIpVersion by remember { mutableStateOf(settings.tunnelEdgeIpVersion) }
    var tunnelLogLevel by remember { mutableStateOf(settings.tunnelLogLevel) }
    var tunnelReconnect by remember { mutableStateOf(settings.tunnelReconnect) }
    var tunnelKeepAlive by remember { mutableStateOf(settings.tunnelKeepAlive) }
    var keepaliveInterval by remember { mutableStateOf(settings.tunnelKeepaliveIntervalSec.toString()) }
    var reconnectBackoff by remember { mutableStateOf(settings.tunnelReconnectBackoffSec.toString()) }
    var historyEnabled by remember { mutableStateOf(settings.tunnelHistoryEnabled) }
    var history by remember { mutableStateOf(settings.tunnelHistoryUrls.split('\n').map { it.trim() }.filter { it.isNotBlank() }) }
    var cfStatus by remember { mutableStateOf<CloudflareTunnelManager.TunnelStatus?>(null) }
    var boreRunning by remember { mutableStateOf(BoreTunnelService.isRunning(context)) }
    var boreUrl by remember { mutableStateOf(BoreTunnelService.getTunnelUrl(context)) }
    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    // 状态轮询
    LaunchedEffect(tunnelType) {
        while (true) {
            if (tunnelType == "cloudflare") {
                cfStatus = tunnelStatusOf(context)
            } else if (tunnelType == "bore") {
                boreRunning = BoreTunnelService.isRunning(context)
                boreUrl = BoreTunnelService.getTunnelUrl(context)
            }
            delay(3_000)
        }
    }

    val isCfRunning = cfStatus?.state == CloudflareTunnelManager.State.RUNNING
    val isBoreRunning = boreRunning
    val isAnyRunning = if (tunnelType == "cloudflare") isCfRunning else isBoreRunning

    val stateColor = when {
        tunnelType == "cloudflare" -> when (cfStatus?.state) {
            CloudflareTunnelManager.State.RUNNING -> AppleColors.systemGreen
            CloudflareTunnelManager.State.STARTING -> AppleColors.systemOrange
            CloudflareTunnelManager.State.FAILED -> AppleColors.systemRed
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        tunnelType == "bore" -> if (isBoreRunning) AppleColors.systemGreen else MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    PageScroll {
        // 状态
        GlassGroup(title = if (t.zh) "状态" else "Status") {
            Text(
                "${if (t.zh) "隧道类型" else "Type"}: ${if (tunnelType == "bore") "Bore" else "Cloudflare"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
            Text(
                "${if (t.zh) "状态" else "State"}: ${
                    when {
                        tunnelType == "cloudflare" -> cfStatus?.state?.name ?: "STOPPED"
                        tunnelType == "bore" -> if (isBoreRunning) "RUNNING" else "STOPPED"
                        else -> "STOPPED"
                    }
                }",
                color = stateColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(14.dp)
            )
            if (tunnelType == "cloudflare" && cfStatus?.message?.isNotBlank() == true) {
                Text(cfStatus!!.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            if (tunnelType == "bore" && boreUrl != null) {
                GroupDivider()
                NavRow(boreUrl!!, if (t.zh) "点击复制公网地址" else "Tap to copy public URL", Icons.Default.Public, onClick = { copy(context, boreUrl!!, t.copied) })
            }
            if (tunnelType == "cloudflare") {
                if (cfStatus?.mode == CloudflareTunnelManager.Mode.NAMED && cfStatus?.state == CloudflareTunnelManager.State.RUNNING && cfStatus?.publicUrl.isNullOrBlank()) {
                    Text(if (t.zh) "永久隧道已连接，但需要在下方填写 Cloudflare 已发布应用的公网主机名/URL 才能显示可复制地址。" else "Named tunnel is connected, but enter the Cloudflare published application hostname/URL below to display a copyable public address.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                }
                if (settings.authEnabled && settings.accessToken.isNotBlank()) {
                    GroupDivider()
                    NavRow(if (t.zh) "复制当前访问 Token" else "Copy current access token", if (t.zh) "公网隧道必须携带 token 访问 /mcp" else "Public tunnel access must include this token for /mcp", Icons.Default.Link, onClick = { copy(context, settings.accessToken, t.copied) })
                }
                cfStatus?.publicUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    GroupDivider()
                    NavRow(url, if (t.zh) "点击复制公网地址" else "Tap to copy public URL", Icons.Default.Public, onClick = { copy(context, url, t.copied) })
                    if (settings.authEnabled && settings.accessToken.isNotBlank() && url.startsWith("https://")) {
                        GroupDivider()
                        val full = "$url/mcp?token=${settings.accessToken}"
                        NavRow(if (t.zh) "带 token 的 MCP 链接" else "MCP URL with token", full, Icons.Default.Link, onClick = { copy(context, full, t.copied) })
                    }
                }
            }
        }

        // 隧道类型选择
        GlassGroup(title = if (t.zh) "隧道类型" else "Tunnel type") {
            ChipRow(
                listOf("cloudflare" to "Cloudflare", "bore" to "Bore"),
                tunnelType,
            ) { tunnelType = it; settings.tunnelType = it }
        }

        // Cloudflare 配置
        if (tunnelType == "cloudflare") {
            GlassGroup(title = if (t.zh) "Cloudflare 模式" else "CF Mode", footer = if (t.zh) "临时隧道无需账号，URL 重启变化；永久隧道需 Cloudflare Tunnel token，并需在 Cloudflare 后台把公网域名路由到本机 MCP 端口。" else "Quick tunnel needs no account; named tunnel needs a Cloudflare token and a Cloudflare published application route to the local MCP port.") {
                ChipRow(
                    listOf("off" to if (t.zh) "关闭" else "Off", "quick" to if (t.zh) "临时隧道" else "Quick", "named" to if (t.zh) "永久隧道" else "Named"),
                    tunnelMode,
                ) { tunnelMode = it; settings.tunnelMode = it }
                if (tunnelMode == "named") {
                    OutlinedTextField(
                        value = namedToken,
                        onValueChange = { namedToken = it; settings.tunnelNamedToken = it },
                        label = { Text(if (t.zh) "Tunnel token" else "Tunnel token") },
                        supportingText = { Text(if (t.zh) "从 Cloudflare Tunnel 安装命令中复制 --token 后面的完整值。" else "Copy the full value after --token from the Cloudflare Tunnel install command.") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                    OutlinedTextField(
                        value = namedPublicUrl,
                        onValueChange = { namedPublicUrl = it; settings.tunnelNamedPublicUrl = it },
                        label = { Text(if (t.zh) "公网主机名或 URL" else "Public hostname or URL") },
                        supportingText = { Text(if (t.zh) "例如 mcp.example.com；必须先在 Cloudflare Tunnel Routes 中映射到 http://localhost:${settings.tunnelTargetPort}" else "For example mcp.example.com; first map it in Cloudflare Tunnel Routes to http://localhost:${settings.tunnelTargetPort}") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
            GlassGroup(title = if (t.zh) "传输" else "Transport") {
                NumberSettingRow(if (t.zh) "代理目标端口" else "Proxy target port", tunnelPort, { tunnelPort = it }, { settings.tunnelTargetPort = it }, if (t.zh) "端口" else "port")
                GroupDivider()
                Text(if (t.zh) "传输协议" else "Protocol", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipRow(listOf("auto" to "Auto", "http2" to "HTTP/2", "quic" to "QUIC"), tunnelProtocol) { tunnelProtocol = it; settings.tunnelProtocol = it }
                GroupDivider()
                Text(if (t.zh) "边缘 IP 版本" else "Edge IP version", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipRow(listOf("4" to "IPv4", "6" to "IPv6", "auto" to if (t.zh) "自动" else "Auto"), edgeIpVersion) { edgeIpVersion = it; settings.tunnelEdgeIpVersion = it }
                GroupDivider()
                Text(if (t.zh) "隧道日志级别" else "Tunnel log level", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipRow(listOf("info" to "Info", "debug" to "Debug", "warn" to "Warn", "error" to "Error"), tunnelLogLevel) { tunnelLogLevel = it; settings.tunnelLogLevel = it }
            }
        }

        // Bore 配置
        if (tunnelType == "bore") {
            GlassGroup(title = if (t.zh) "Bore 模式" else "Bore Mode", footer = if (t.zh) "临时隧道无需密码，URL 重启变化；永久隧道需自建 Bore 服务器并配置密码。" else "Quick bore needs no password, URL changes on restart; permanent bore needs a self-hosted server with a password.") {
                ChipRow(
                    listOf("off" to if (t.zh) "关闭" else "Off", "quick" to if (t.zh) "临时隧道" else "Quick", "named" to if (t.zh) "永久隧道" else "Permanent"),
                    tunnelMode,
                ) { tunnelMode = it; settings.tunnelMode = it }
                if (tunnelMode != "off") {
                    GroupDivider()
                    OutlinedTextField(
                        value = boreHost,
                        onValueChange = { boreHost = it; settings.boreHost = it },
                        label = { Text(if (t.zh) "Bore 服务器地址" else "Bore server") },
                        supportingText = { Text(if (t.zh) "临时隧道默认为 bore.pub；永久隧道填写你的自建服务器地址" else "Quick: bore.pub; Permanent: your own server") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                    GroupDivider()
                    NumberSettingRow(if (t.zh) "Bore 服务器端口" else "Server port", borePort, { borePort = it }, { settings.borePort = it }, if (t.zh) "端口" else "port")
                    GroupDivider()
                    NumberSettingRow(if (t.zh) "本地端口" else "Local port", tunnelPort, { tunnelPort = it }, { settings.tunnelTargetPort = it }, if (t.zh) "端口" else "port")
                    if (tunnelMode == "named") {
                        GroupDivider()
                        OutlinedTextField(
                            value = boreSecret,
                            onValueChange = { boreSecret = it; settings.boreSecret = it },
                            label = { Text(if (t.zh) "密码" else "Secret") },
                            supportingText = { Text(if (t.zh) "自建 Bore 服务器配置的认证密码" else "Password set on your self-hosted bore server") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            ),
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                    }
                }
            }
            // Bore 通用设置
            if (tunnelMode != "off") {
                GlassGroup {
                    ToggleRow(if (t.zh) "断线自动重连" else "Auto reconnect", tunnelReconnect) { tunnelReconnect = it; settings.tunnelReconnect = it }
                }
            }
        }

        // 通用设置
        GlassGroup {
            ToggleRow(if (t.zh) "随服务自动启动" else "Auto-start with service", tunnelAutoStart) { tunnelAutoStart = it; settings.tunnelAutoStart = it }
            GroupDivider()
            ToggleRow(if (t.zh) "断线自动重连" else "Auto reconnect", tunnelReconnect) { tunnelReconnect = it; settings.tunnelReconnect = it }
            GroupDivider()
            ToggleRow(if (t.zh) "隧道保活" else "Tunnel keepalive", tunnelKeepAlive) { tunnelKeepAlive = it; settings.tunnelKeepAlive = it }
            GroupDivider()
            NumberSettingRow(if (t.zh) "保活探测间隔" else "Probe interval", keepaliveInterval, { keepaliveInterval = it }, {
                settings.tunnelKeepaliveIntervalSec = it.coerceIn(5, 300)
                keepaliveInterval = settings.tunnelKeepaliveIntervalSec.toString()
            }, if (t.zh) "秒" else "s")
            GroupDivider()
            NumberSettingRow(if (t.zh) "重连退避" else "Reconnect backoff", reconnectBackoff, { reconnectBackoff = it }, {
                settings.tunnelReconnectBackoffSec = it.coerceIn(1, 60)
                reconnectBackoff = settings.tunnelReconnectBackoffSec.toString()
            }, if (t.zh) "秒" else "s")
        }

        // 启动/停止按钮
        GlassGroup {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                PrimaryActionButton(if (t.zh) "启动" else "Start", {
                    if (tunnelType == "cloudflare") {
                        startCfTunnel(context, settings, tunnelMode, namedToken, namedPublicUrl) { cfStatus = tunnelStatusOf(context) }
                    } else {
                        startBoreTunnel(context, settings, tunnelMode, boreHost, borePort, tunnelPort)
                    }
                })
                SecondaryActionButton(if (t.zh) "停止" else "Stop") {
                    if (tunnelType == "cloudflare") {
                        stopCfTunnel(context) { cfStatus = tunnelStatusOf(context) }
                    } else {
                        stopBoreTunnel(context) { boreRunning = BoreTunnelService.isRunning(context) }
                    }
                }
                SecondaryActionButton(if (t.zh) "刷新状态" else "Refresh") {
                    if (tunnelType == "cloudflare") cfStatus = tunnelStatusOf(context)
                    else { boreRunning = BoreTunnelService.isRunning(context); boreUrl = BoreTunnelService.getTunnelUrl(context) }
                }
                SecondaryActionButton(if (t.zh) "导出配置" else "Export") { showExport = true }
                SecondaryActionButton(if (t.zh) "导入配置" else "Import") { showImport = true; importText = "" }
            }
        }

        // 历史 URL
        GlassGroup(title = if (t.zh) "历史隧道 URL" else "History tunnel URLs") {
            ToggleRow(if (t.zh) "记录隧道 URL" else "Record tunnel URLs", historyEnabled) {
                historyEnabled = it
                settings.tunnelHistoryEnabled = it
            }
            if (history.isNotEmpty()) {
                GroupDivider()
                history.forEach { url ->
                    val finalUrl = url
                    Text(url, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).clickable { copy(context, finalUrl, t.copied) }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // 导出对话框
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(if (t.zh) "导出隧道配置" else "Export tunnel config") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val config = buildString {
                        appendLine("type: $tunnelType")
                        if (tunnelType == "cloudflare") {
                            appendLine("mode: $tunnelMode")
                            appendLine("protocol: $tunnelProtocol")
                            appendLine("edgeIpVersion: $edgeIpVersion")
                            appendLine("targetPort: $tunnelPort")
                            appendLine("publicUrl: $namedPublicUrl")
                            appendLine("logLevel: $tunnelLogLevel")
                            appendLine("autoStart: $tunnelAutoStart")
                            appendLine("reconnect: $tunnelReconnect")
                            appendLine("keepAlive: $tunnelKeepAlive")
                            appendLine("keepaliveIntervalSec: $keepaliveInterval")
                            appendLine("reconnectBackoffSec: $reconnectBackoff")
                            appendLine("token: ${maskToken(namedToken)}")
                        } else {
                            appendLine("boreHost: $boreHost")
                            appendLine("borePort: $borePort")
                            appendLine("targetPort: $tunnelPort")
                            appendLine("boreSecret: ${maskToken(boreSecret)}")
                            appendLine("autoStart: $tunnelAutoStart")
                        }
                    }
                    Text(config, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { copy(context, buildString {
                appendLine("type: $tunnelType")
                if (tunnelType == "cloudflare") {
                    appendLine("mode: $tunnelMode")
                    appendLine("protocol: $tunnelProtocol")
                    appendLine("edgeIpVersion: $edgeIpVersion")
                    appendLine("targetPort: $tunnelPort")
                    appendLine("publicUrl: $namedPublicUrl")
                    appendLine("logLevel: $tunnelLogLevel")
                    appendLine("autoStart: $tunnelAutoStart")
                    appendLine("reconnect: $tunnelReconnect")
                    appendLine("keepAlive: $tunnelKeepAlive")
                    appendLine("keepaliveIntervalSec: $keepaliveInterval")
                    appendLine("reconnectBackoffSec: $reconnectBackoff")
                } else {
                    appendLine("boreHost: $boreHost")
                    appendLine("borePort: $borePort")
                    appendLine("targetPort: $tunnelPort")
                    appendLine("autoStart: $tunnelAutoStart")
                }
            }, t.copied); showExport = false }) { Text(t.copied) } },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text(if (t.zh) "关闭" else "Close") } },
        )
    }

    // 导入对话框
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(if (t.zh) "导入隧道配置" else "Import tunnel config") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text(if (t.zh) "粘贴 YAML 配置" else "Paste YAML config") },
                        minLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applyTunnelConfigYaml(settings, importText)
                    tunnelType = settings.tunnelType
                    tunnelMode = settings.tunnelMode
                    tunnelPort = settings.tunnelTargetPort.toString()
                    boreHost = settings.boreHost
                    borePort = settings.borePort.toString()
                    boreSecret = settings.boreSecret
                    namedToken = settings.tunnelNamedToken
                    namedPublicUrl = settings.tunnelNamedPublicUrl
                    tunnelProtocol = settings.tunnelProtocol
                    edgeIpVersion = settings.tunnelEdgeIpVersion
                    tunnelLogLevel = settings.tunnelLogLevel
                    tunnelAutoStart = settings.tunnelAutoStart
                    tunnelReconnect = settings.tunnelReconnect
                    tunnelKeepAlive = settings.tunnelKeepAlive
                    keepaliveInterval = settings.tunnelKeepaliveIntervalSec.toString()
                    reconnectBackoff = settings.tunnelReconnectBackoffSec.toString()
                    showImport = false
                    Toast.makeText(context, if (t.zh) "配置已导入" else "Imported", Toast.LENGTH_SHORT).show()
                }) { Text(if (t.zh) "应用" else "Apply") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text(if (t.zh) "取消" else "Cancel") } },
        )
    }
}

private fun startCfTunnel(context: Context, settings: SettingsStore, tunnelMode: String, namedToken: String, namedPublicUrl: String, onDone: () -> Unit) {
    if (tunnelMode == "off") {
        Toast.makeText(context, if (settings.language == "zh") "请先选择 Cloudflare 隧道模式" else "Select a Cloudflare tunnel mode first", Toast.LENGTH_SHORT).show()
        return
    }
    val mode = if (tunnelMode == "named") CloudflareTunnelManager.Mode.NAMED else CloudflareTunnelManager.Mode.QUICK
    val tunnel = activeTunnel(context)
    if (tunnel == null) {
        Toast.makeText(context, if (settings.language == "zh") "请先启动 MCP 服务器总开关" else "Turn on the MCP server master switch first", Toast.LENGTH_SHORT).show()
        return
    }
    if (settings.authEnabled && settings.accessToken.isBlank()) {
        Toast.makeText(context, if (settings.language == "zh") "已开启鉴权但未设置访问 Token，请先设置 Token 或关闭鉴权后再启动隧道" else "Authentication is on but no access token is set. Set a token or turn auth off before starting the tunnel", Toast.LENGTH_LONG).show()
        return
    }
    if (mode == CloudflareTunnelManager.Mode.NAMED && namedPublicUrl.isBlank()) {
        Toast.makeText(context, if (settings.language == "zh") "建议先填写 Cloudflare 公网主机名/URL" else "Enter the Cloudflare public hostname/URL first", Toast.LENGTH_LONG).show()
    }
    if (!settings.authEnabled) {
        Toast.makeText(context, if (settings.language == "zh") "提示：隧道将以无鉴权方式公开暴露 MCP 服务。如需保护请在设置中开启鉴权。" else "Note: the tunnel will expose the MCP service publicly with no authentication. Enable auth in settings to protect it.", Toast.LENGTH_LONG).show()
    }
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        tunnel.start(settings.tunnelTargetPort, mode, namedToken)
        onDone()
    }
    Toast.makeText(context, if (settings.language == "zh") "隧道启动中…" else "Starting tunnel…", Toast.LENGTH_SHORT).show()
}

private fun stopCfTunnel(context: Context, onDone: () -> Unit) {
    val tunnel = activeTunnel(context)
    if (tunnel == null) {
        Toast.makeText(context, if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "zh") == "zh") "服务器未运行，无需停止隧道" else "Server not running, nothing to stop", Toast.LENGTH_SHORT).show()
        return
    }
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        tunnel.stop()
        onDone()
    }
}

private fun startBoreTunnel(context: Context, settings: SettingsStore, tunnelMode: String, host: String, port: String, localPort: String) {
    if (tunnelMode == "off") {
        Toast.makeText(context, if (settings.language == "zh") "请先选择 Bore 隧道模式" else "Select a bore tunnel mode first", Toast.LENGTH_SHORT).show()
        return
    }
    val hp = host.ifBlank { "bore.pub" }
    val bp = port.toIntOrNull() ?: 7835
    val lp = localPort.toIntOrNull() ?: 8080
    val intent = Intent(context, BoreTunnelService::class.java).apply {
        putExtra(BoreTunnelService.EXTRA_BORE_HOST, hp)
        putExtra(BoreTunnelService.EXTRA_LOCAL_PORT, lp)
    }
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Toast.makeText(context, if (settings.language == "zh") "Bore 隧道启动中…" else "Bore tunnel starting…", Toast.LENGTH_SHORT).show()
}

private fun stopBoreTunnel(context: Context, onDone: () -> Unit) {
    val intent = Intent(context, BoreTunnelService::class.java).setAction(BoreTunnelService.ACTION_STOP)
    context.startService(intent)
    onDone()
}

private fun activeTunnel(context: Context): CloudflareTunnelManager? =
    activeServer(context)?.tunnel

private fun tunnelStatusOf(context: Context): CloudflareTunnelManager.TunnelStatus =
    activeServer(context)?.tunnel?.status() ?: CloudflareTunnelManager.TunnelStatus()

private fun maskToken(token: String): String {
    if (token.length <= 8) return if (token.isBlank()) "(empty)" else "****"
    return token.take(4) + "…(" + token.length + ")…" + token.takeLast(4)
}

private fun applyTunnelConfigYaml(settings: SettingsStore, yaml: String) {
    val map = HashMap<String, String>()
    yaml.lines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return@forEach
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) map[key] = value
    }
    map["type"]?.let { if (it in setOf("cloudflare", "bore")) settings.tunnelType = it }
    map["mode"]?.let { if (it in setOf("off", "quick", "named")) settings.tunnelMode = it }
    map["protocol"]?.let { if (it in setOf("http2", "quic", "auto")) settings.tunnelProtocol = it }
    map["edgeIpVersion"]?.let { if (it in setOf("4", "6", "auto")) settings.tunnelEdgeIpVersion = it }
    map["targetPort"]?.toIntOrNull()?.let { settings.tunnelTargetPort = it }
    map["publicUrl"]?.let { settings.tunnelNamedPublicUrl = it }
    map["logLevel"]?.let { if (it in setOf("debug", "info", "warn", "error", "fatal")) settings.tunnelLogLevel = it }
    map["boreHost"]?.let { settings.boreHost = it }
    map["borePort"]?.toIntOrNull()?.let { settings.borePort = it }
    map["boreSecret"]?.takeIf { it.isNotBlank() && !it.contains("…") && it != "(empty)" }?.let { settings.boreSecret = it }
    map["autoStart"]?.lowercase()?.let { settings.tunnelAutoStart = it == "true" || it == "1" }
    map["reconnect"]?.lowercase()?.let { settings.tunnelReconnect = it == "true" || it == "1" }
    map["keepAlive"]?.lowercase()?.let { settings.tunnelKeepAlive = it == "true" || it == "1" }
    map["keepaliveIntervalSec"]?.toIntOrNull()?.let { settings.tunnelKeepaliveIntervalSec = it.coerceIn(5, 300) }
    map["reconnectBackoffSec"]?.toIntOrNull()?.let { settings.tunnelReconnectBackoffSec = it.coerceIn(1, 60) }
    map["token"]?.takeIf { it.isNotBlank() && !it.contains("…") && it != "(empty)" }?.let { settings.tunnelNamedToken = it }
}