package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.service.McpForegroundService
import kotlinx.coroutines.delay

/**
 * 塔菲逆核 · 首页（v1.3.24 iOS 风格重做）。
 *
 * 设计语言（iOS / iPadOS 设置页）：
 *  · 大标题（Large Title）+ 副标题，左对齐
 *  · Inset Grouped 卡片：圆角 12dp、无描边、底色与页面背景形成层次
 *  · 每行「彩色圆角方形图标 + 主标题 + 右侧 chevron」，行高 44dp
 *  · 分隔线从图标右侧开始内缩（不做通栏线）
 *  · 分组标题为小号灰色 label
 *
 * 底层逻辑与 v1.3.7 完全一致：McpForegroundService（启停）/ SettingsStore /
 * filteredEndpoints / EngineProvider / activeBridge / Tunnel 状态轮询。
 *
 * @param onNavigate 功能行点击 —— 交给 MainActivity 切换到对应 MainTab 或工具清单（category=null = 全部工具）
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
    val zh = t.zh
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
                .onSuccess { Toast.makeText(context, if (zh) "服务启动中…" else "Starting…", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, if (zh) "启动失败" else "Failed to start", Toast.LENGTH_LONG).show() }
        }
    }

    val sats = remember(zh) {
        listOf(
            Satellite(Icons.Default.Code, if (zh) "反编译" else "Decompile", MainTab.Home, "decompile", 0),
            Satellite(Icons.Default.LockOpen, if (zh) "脱壳" else "Unpack", MainTab.Home, "unpack", 1),
            Satellite(Icons.Default.Memory, if (zh) "SO 分析" else "SO Analysis", MainTab.Home, "soanalyze", 2),
            Satellite(Icons.Default.FlashOn, if (zh) "模拟执行" else "Emulate", MainTab.Home, "emulate", 3),
            Satellite(Icons.Default.MyLocation, "Frida", MainTab.Home, "frida", 4),
            Satellite(Icons.Default.Inventory2, if (zh) "回编" else "Rebuild", MainTab.Home, "rebuild", 5),
            Satellite(Icons.Default.RssFeed, if (zh) "日志" else "Logs", MainTab.Home, "logs", 6),
            Satellite(Icons.Default.Settings, if (zh) "设置" else "Settings", MainTab.Settings, null, 7),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(bottom = 24.dp),
    ) {
        // ── Large Title 区 ──
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (zh) "塔菲逆核" else "Taffy NieHe",
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (zh) "命令中枢" else "Command Hub",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ConnPill(running = running, zh = zh)
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── 引擎卡片 ──
        IosGroup {
            IosRow(
                icon = Icons.Filled.PowerSettingsNew,
                iconTint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                iconBg = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                title = if (running) (if (zh) "引擎运行中" else "Engine running")
                else (if (zh) "引擎已停止" else "Engine stopped"),
                subtitle = if (running) (if (zh) "MCP 服务在线 · 点按停止" else "MCP online · tap to stop")
                else (if (zh) "点按启动 MCP 服务" else "Tap to start the MCP service"),
                showChevron = false,
                onClick = { toggle() },
            )
        }

        // ── 工作台 ──
        IosGroupHeader(if (zh) "工作台" else "Workbench")
        IosGroup {
            sats.forEachIndexed { i, sat ->
                IosRow(
                    icon = sat.icon,
                    iconTint = satelliteColor(sat.accent),
                    iconBg = satelliteColor(sat.accent),
                    title = sat.label,
                    onClick = { onNavigate(sat.tab, sat.toolCategory) },
                )
                if (i != sats.lastIndex) IosDivider()
            }
            IosDivider()
            IosRow(
                icon = Icons.Filled.Analytics,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBg = MaterialTheme.colorScheme.tertiary,
                title = if (zh) "全部工具" else "All tools",
                subtitle = "${ToolCatalog.ALL.size}",
                onClick = { onNavigate(MainTab.Home, null) },
            )
        }

        // ── 服务状态 ──
        IosGroupHeader(if (zh) "服务" else "Service")
        IosGroup {
            ServiceStatusContent(
                zh = zh,
                settings = settings,
                onNavigateSettings = onNavigateSettings,
                onAnalyze = { onNavigate(MainTab.Tools, null) },
            )
        }

        // ── 连接 ──
        IosGroupHeader(if (zh) "连接地址" else "Endpoint")
        IosGroup {
            IosRow(
                icon = Icons.Filled.ContentCopy,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBg = MaterialTheme.colorScheme.primary,
                title = if (running) loopbackUrl else (if (zh) "未启动" else "Offline"),
                titleMono = true,
                subtitle = if (running) (if (zh) "点按复制到 AI 客户端" else "Tap to copy for your MCP client") else null,
                showChevron = false,
                titleColor = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                trailing = if (running) null else {
                    { Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp)) }
                },
                onClick = {
                    if (running) {
                        clipboard.setText(AnnotatedString(loopbackUrl))
                        Toast.makeText(context, if (zh) "已复制，填到 AI 客户端 MCP 地址" else "Copied", Toast.LENGTH_SHORT).show()
                    } else {
                        try { pickTree.launch(null) }
                        catch (e: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, if (zh) "系统未提供文件选择器（SAF），请在分析页用文件路径打开" else "No SAF picker on this device", Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
    }
}

private data class Satellite(
    val icon: ImageVector,
    val label: String,
    val tab: MainTab,
    val toolCategory: String? = null,
    val accent: Int = 0,
)

/** iOS 风格分组卡片：圆角 12dp、无描边、页面横向 16dp 边距。 */
@Composable
private fun IosGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
}

/** iOS 分组标题：小号灰色，左侧 32dp 对齐（与卡片内文字列对齐）。 */
@Composable
private fun IosGroupHeader(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 20.dp, bottom = 7.dp),
    )
}

/** iOS 列表行：彩色圆角方形图标 + 主/副标题 + 可选尾部 + chevron。 */
@Composable
private fun IosRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String? = null,
    titleMono: Boolean = false,
    titleColor: Color? = null,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 彩色圆角方形图标（iOS Settings 图标语言）
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = if (titleMono) 12.sp else AppText.bodyStrong,
                fontFamily = if (titleMono) FontFamily.Monospace else FontFamily.Default,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = AppText.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** iOS 内缩分隔线：从图标右侧（14 + 28 + 12 = 54dp）开始。 */
@Composable
private fun IosDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 54.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** 顶部连接状态胶囊（iOS 风格小圆点 + 文字）。 */
@Composable
private fun ConnPill(running: Boolean, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dot = if (running) cs.primary else cs.outline
    Row(
        Modifier
            .clip(RoundedCornerShape(AppShape.pill))
            .background(if (running) cs.primary.copy(alpha = 0.12f) else cs.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(
            if (running) (if (zh) "在线" else "Online") else (if (zh) "离线" else "Offline"),
            fontSize = AppText.label,
            fontWeight = FontWeight.Medium,
            color = if (running) cs.primary else cs.onSurfaceVariant,
        )
    }
}

/** 功能图标配色轮转（iOS 多彩图标）：primary / tertiary / secondary 循环。 */
@Composable
private fun satelliteColor(idx: Int): Color = when (idx % 3) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/**
 * 服务状态内容（iOS 行：隧道 / 目录 / 桥接 / 保活 / 分析入口）。
 * 状态读取逻辑与 v1.3.7 完全一致。
 */
@Composable
private fun ServiceStatusContent(
    zh: Boolean,
    settings: SettingsStore,
    onNavigateSettings: (SettingsDest) -> Unit,
    onAnalyze: () -> Unit,
) {
    val context = LocalContext.current
    val dirConfigured = settings.treeUri != null || settings.useDefaultWorkDir
    var bridgeOnline by remember { mutableStateOf(activeBridge(context).state().online) }
    val bridgeConfigured = settings.apkMcpConfigs.isNotEmpty() || settings.apkMcpUrl.isNotBlank()
    LaunchedEffect(Unit) {
        while (true) {
            bridgeOnline = activeBridge(context).state().online
            delay(3000)
        }
    }
    val keepAliveReady = settings.wakeLockEnabled && settings.floatingEnabled &&
        settings.tunnelKeepAlive && settings.bootAutoStart

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
    val tunnelText = when {
        cfRunning -> (if (zh) "隧道 · Cloudflare 运行中" else "Tunnel · CF running") + (tunnelStatus?.publicUrl?.let { " $it" } ?: "")
        boreRunning -> (if (zh) "隧道 · Bore 运行中" else "Tunnel · Bore running") + (com.soreverse.mcp.core.BoreTunnelService.getTunnelUrl(context)?.let { " $it" } ?: "")
        cfStarting -> if (zh) "隧道 · Cloudflare 连接中…" else "Tunnel · CF connecting…"
        boreConnecting -> if (zh) "隧道 · Bore 连接中…" else "Tunnel · Bore connecting…"
        else -> if (zh) "隧道 · 未开启" else "Tunnel · off"
    }
    val tunnelColor = when {
        anyTunnelRunning -> MaterialTheme.colorScheme.primary
        anyTunnelStarting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    // 隧道路由行（可点进设置）
    StatusLine(
        dotColor = tunnelColor,
        title = tunnelText,
        value = if (zh) "配置" else "Config",
        valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { onNavigateSettings(SettingsDest.Tunnel) },
    )
    IosDivider()
    StatusLine(
        dotColor = if (dirConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        title = if (zh) "工作目录" else "Work dir",
        value = if (dirConfigured) (if (zh) "已设置" else "Set") else (if (zh) "未设置" else "None"),
        valueColor = if (dirConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { onNavigateSettings(SettingsDest.ServiceConfig) },
    )
    IosDivider()
    StatusLine(
        dotColor = if (bridgeOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        title = if (zh) "APK 桥接" else "APK bridge",
        value = if (bridgeOnline) (if (zh) "已连接" else "Linked")
        else if (bridgeConfigured) (if (zh) "离线" else "Offline") else (if (zh) "未配置" else "None"),
        valueColor = if (bridgeOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { onNavigateSettings(SettingsDest.ApkBridge) },
    )
    IosDivider()
    StatusLine(
        dotColor = if (keepAliveReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        title = if (zh) "后台保活" else "Keep-alive",
        value = if (keepAliveReady) (if (zh) "就绪" else "Ready") else (if (zh) "未就绪" else "Off"),
        valueColor = if (keepAliveReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { onNavigateSettings(SettingsDest.KeepAlive) },
    )
    IosDivider()
    StatusLine(
        dotColor = MaterialTheme.colorScheme.tertiary,
        title = if (zh) "进入分析台" else "Analysis workspace",
        value = null,
        valueColor = MaterialTheme.colorScheme.tertiary,
        showChevron = true,
        onClick = onAnalyze,
    )
}

/** iOS 状态行：左侧小圆点 + 标题，右侧为状态值（可选 chevron）。 */
@Composable
private fun StatusLine(
    dotColor: Color,
    title: String,
    value: String?,
    valueColor: Color,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = AppText.bodyStrong,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!value.isNullOrBlank()) {
            Text(
                value,
                fontSize = AppText.label,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
