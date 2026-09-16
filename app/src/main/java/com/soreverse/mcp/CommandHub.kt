package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.service.McpForegroundService
import com.soreverse.mcp.mcp.ToolCatalog
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * 塔菲逆核 · 命令中枢首页（v1.3.7 扁平简洁风）。
 *
 * 布局：品牌行 → 引擎启停行 → 功能列表（8 项 + 全部工具）→ 服务状态行 → 连接地址行。
 * 全部为无卡片背景的扁平行 + 细分隔线；不放大卡片、不做环绕/星核动效。
 * 底层完全复用现有逻辑：McpForegroundService（启停）/ SettingsStore / filteredEndpoints / EngineProvider。
 *
 * @param onNavigate 功能行点击回调 —— 交给 MainActivity 切换到对应 MainTab 或工具清单（category=null 时为全部工具）。
 */
@Composable
internal fun CommandHubScreen(
    t: UiText,
    settings: SettingsStore,
    onNavigate: (MainTab, String?) -> Unit,
    onNavigateSettings: (SettingsDest) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var running by remember { mutableStateOf(McpForegroundService.isRunning()) }
    var treeUri by remember { mutableStateOf(settings.treeUri) }
    var endpoints by remember { mutableStateOf(filteredEndpoints(context, settings, settings.port)) }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            settings.treeUri = uri
            settings.useDefaultWorkDir = false
            treeUri = uri
            EngineProvider.setWorkDirectory(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        treeUri?.let { EngineProvider.setWorkDirectory(context, it) }
        while (true) {
            running = McpForegroundService.isRunning()
            endpoints = filteredEndpoints(context, settings, settings.port)
            delay(1000)
        }
    }

    val loopbackUrl = (endpoints.firstOrNull { it.url.contains("127.0.0.1") }?.url
        ?: "http://127.0.0.1:${settings.port}/mcp").let {
            if (settings.authEnabled && settings.accessToken.isNotBlank()) "$it?token=${settings.accessToken}" else it
        }

    fun toggle() {
        if (running) {
            McpForegroundService.stop(context)
            running = false
        } else {
            runCatching { McpForegroundService.start(context) }
                .onSuccess { Toast.makeText(context, if (t.zh) "服务启动中…" else "Starting…", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, if (t.zh) "启动失败" else "Failed to start", Toast.LENGTH_LONG).show() }
        }
    }

    // 8 个功能入口：反编译 / 脱壳 / SO 分析 / 模拟 / Frida / 回编 → 打开对应分类的 MCP 工具清单；
    // 日志 → 日志页；设置 → 设置页。
    val zh = t.zh
    val sats = remember(zh) {
        listOf(
            Satellite(Icons.Default.Code, if (zh) "反编译" else "Decompile", MainTab.Home, "decompile"),
            Satellite(Icons.Default.LockOpen, if (zh) "脱壳" else "Unpack", MainTab.Home, "unpack"),
            Satellite(Icons.Default.Memory, if (zh) "SO 分析" else "SO", MainTab.Home, "soanalyze"),
            Satellite(Icons.Default.FlashOn, if (zh) "模拟" else "Emulate", MainTab.Home, "emulate"),
            Satellite(Icons.Default.MyLocation, "Frida", MainTab.Home, "frida"),
            Satellite(Icons.Default.Inventory2, if (zh) "回编" else "Rebuild", MainTab.Home, "rebuild"),
            Satellite(Icons.Default.RssFeed, if (zh) "日志" else "Logs", MainTab.Home, "logs"),
            Satellite(Icons.Default.Settings, if (zh) "设置" else "Settings", MainTab.Settings),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        // 品牌 + 连接状态（一行）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (zh) "塔菲逆核" else "Taffy NieHe",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (zh) "命令中枢" else "Command Hub",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            ConnDot(running = running, zh = zh)
        }

        Spacer(Modifier.height(10.dp))

        // 引擎启停：40dp 圆形电源键（保留一点「星核」记忆 —— 细环 + 运行时柔和内圆，但不做卡片底）
        val engineRing = if (running) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { toggle() }
                    .drawBehind {
                        if (running) drawCircle(color = engineRing.copy(alpha = 0.16f))
                        drawCircle(color = engineRing, style = Stroke(width = 1.5.dp.toPx()))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = if (running) (if (zh) "停止引擎" else "Stop engine") else (if (zh) "启动引擎" else "Start engine"),
                    tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) (if (zh) "引擎运行中 · MCP 在线" else "Engine running · MCP online")
                    else (if (zh) "引擎已停止" else "Engine stopped"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (running) (if (zh) "点按电源键停止" else "Tap the key to stop")
                    else (if (zh) "点按电源键启动" else "Tap the key to start"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HubDivider()

        // 功能（扁平列表）
        SectionLabel(if (zh) "功能" else "Workbench")
        sats.forEach { sat ->
            HubRow(sat.icon, sat.label) { onNavigate(sat.tab, sat.toolCategory) }
        }
        HubRow(
            Icons.Default.Analytics,
            if (zh) "全部工具 · ${ToolCatalog.ALL.size}" else "All tools · ${ToolCatalog.ALL.size}",
            tint = MaterialTheme.colorScheme.tertiary,
        ) { onNavigate(MainTab.Home, null) }

        Spacer(Modifier.height(8.dp))
        HubDivider()

        // 服务状态（一行小条 + 分析入口）
        ServiceStatusRow(
            zh = zh,
            settings = settings,
            onNavigateSettings = onNavigateSettings,
            onAnalyze = { onNavigate(MainTab.Tools, null) },
        )

        HubDivider()

        // 连接地址（一行，点按复制）
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = running) {
                    clipboard.setText(AnnotatedString(loopbackUrl))
                    Toast.makeText(context, if (zh) "已复制，填到 AI 客户端 MCP 地址" else "Copied", Toast.LENGTH_SHORT).show()
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (running) loopbackUrl else (if (zh) "未启动" else "Offline"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = if (zh) "复制地址" else "Copy address",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = if (zh) "选择工作目录" else "Pick work dir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).clickable {
                        // 无 SAF 提供方设备（部分国产 ROM）会抛 ActivityNotFoundException——捕获并提示
                        try { pickTree.launch(null) }
                        catch (e: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, if (t.zh) "系统未提供文件选择器（SAF），请在分析页用文件路径打开" else "No SAF picker on this device; open files by path from Analyze", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

private data class Satellite(val icon: ImageVector, val label: String, val tab: MainTab, val toolCategory: String? = null)

/** 首页扁平行：18dp 图标 + 标签，无卡片背景（简洁风）。 */
@Composable
private fun HubRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.sm))
            .clickable { onClick() }
            .padding(horizontal = 2.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 细分隔线（简洁风：整行一条 0.5dp 淡线）。 */
@Composable
private fun HubDivider() {
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    )
}

/** 小节标题（功能 / 状态 …）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ConnDot(running: Boolean, zh: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        )
        Text(
            if (running) (if (zh) "在线" else "Online") else (if (zh) "离线" else "Offline"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceStatusRow(
    zh: Boolean,
    settings: SettingsStore,
    onNavigateSettings: (SettingsDest) -> Unit,
    onAnalyze: () -> Unit,
) {
    val context = LocalContext.current
    val dirConfigured = settings.treeUri != null || settings.useDefaultWorkDir
    // 桥接状态：使用 activeServer 的单例桥接，确保与探测全部使用同一实例
    var bridgeOnline by remember { mutableStateOf(activeBridge(context).state().online) }
    val bridgeConfigured = settings.apkMcpConfigs.isNotEmpty() || settings.apkMcpUrl.isNotBlank()
    LaunchedEffect(Unit) {
        while (true) {
            bridgeOnline = activeBridge(context).state().online
            delay(3000)
        }
    }
    // 保活就绪：wakeLock + floating + tunnelKeepAlive + bootAutoStart 全部开启
    val keepAliveReady = settings.wakeLockEnabled && settings.floatingEnabled &&
        settings.tunnelKeepAlive && settings.bootAutoStart

    // 公网隧道状态
    var tunnelStatus by remember { mutableStateOf(activeServer(context)?.tunnel?.status()) }
    var boreRunning by remember { mutableStateOf(com.soreverse.mcp.core.BoreTunnelService.isRunning(context)) }
    var boreConnecting by remember { mutableStateOf(com.soreverse.mcp.core.BoreTunnelService.isConnecting(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            tunnelStatus = activeServer(context)?.tunnel?.status()
            boreRunning = com.soreverse.mcp.core.BoreTunnelService.isRunning(context)
            boreConnecting = com.soreverse.mcp.core.BoreTunnelService.isConnecting(context)
            delay(2000)
        }
    }
    val cfRunning = tunnelStatus?.state == com.soreverse.mcp.core.CloudflareTunnelManager.State.RUNNING
    val cfStarting = tunnelStatus?.state == com.soreverse.mcp.core.CloudflareTunnelManager.State.STARTING
    val anyTunnelRunning = cfRunning || boreRunning
    val anyTunnelStarting = (cfStarting || boreConnecting) && !anyTunnelRunning
    val tunnelColor = if (anyTunnelRunning) MaterialTheme.colorScheme.primary else if (anyTunnelStarting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    val tunnelText = when {
        cfRunning -> (if (zh) "隧道 · Cloudflare 运行中" else "Tunnel · CF Running") + (tunnelStatus?.publicUrl?.let { " $it" } ?: "")
        boreRunning -> (if (zh) "隧道 · Bore 运行中" else "Tunnel · Bore Running") + (com.soreverse.mcp.core.BoreTunnelService.getTunnelUrl(context)?.let { " $it" } ?: "")
        cfStarting -> if (zh) "隧道 · Cloudflare 连接中…" else "Tunnel · CF Connecting…"
        boreConnecting -> if (zh) "隧道 · Bore 连接中…" else "Tunnel · Bore Connecting…"
        else -> if (zh) "隧道 · 未开启" else "Tunnel · Off"
    }

    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 公网隧道状态文本
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.sm))
                .clickable { onNavigateSettings(SettingsDest.Tunnel) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(tunnelColor),
            )
            Text(
                tunnelText,
                style = MaterialTheme.typography.labelSmall,
                color = tunnelColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (zh) "配置" else "Config",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        // 服务状态：三条紧凑小条 + 分析入口
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(
                label = if (zh) "目录" else "Dir",
                statusText = if (dirConfigured) (if (zh) "已设置" else "Set") else (if (zh) "未设" else "None"),
                configured = dirConfigured,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateSettings(SettingsDest.ServiceConfig) },
            )
            StatusChip(
                label = if (zh) "桥接" else "Bridge",
                statusText = if (bridgeOnline) (if (zh) "已连" else "Linked") else if (bridgeConfigured) (if (zh) "离线" else "Offline") else (if (zh) "未配" else "None"),
                configured = bridgeOnline,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateSettings(SettingsDest.ApkBridge) },
            )
            StatusChip(
                label = if (zh) "保活" else "Keep",
                statusText = if (keepAliveReady) (if (zh) "就绪" else "Ready") else (if (zh) "未就绪" else "Off"),
                configured = keepAliveReady,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateSettings(SettingsDest.KeepAlive) },
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(AppShape.sm))
                    .clickable { onAnalyze() }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.Analytics,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (zh) "分析" else "Go",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 紧凑状态小条：● 标签 状态（一行，点按跳设置）。 */
@Composable
private fun StatusChip(
    label: String,
    statusText: String,
    configured: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dotColor = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        modifier
            .clip(RoundedCornerShape(AppShape.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
