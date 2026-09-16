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
 * 塔菲逆核 · 命令中枢首页（创新1布局，彻底脱离 SOMCP 原版底部导航范式）。
 *
 * 交互隐喻：中央"星核"= 引擎启动开关；8 个功能像卫星环绕四周，点击卫星进入对应功能。
 * 底层完全复用现有逻辑：McpForegroundService（启停）/ SettingsStore / filteredEndpoints / EngineProvider。
 *
 * @param onNavigate 卫星点击回调 —— 交给 MainActivity 切换到对应 MainTab（工具/日志/设置）。
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

    // 8 个卫星节点（顺时针，从正上方开始）
    // 反编译、脱壳、SO分析、模拟、Frida、回编跳转到工具页对应位置（滚动定位，不过滤）
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
        // 顶部品牌（紧凑单行，去掉原「下移一行半」的 24dp 空转）
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (zh) "塔菲逆核" else "Taffy NieHe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (zh) "聚合式逆向 · 命令中枢" else "Reverse Command Hub",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConnDot(running = running, zh = zh)
        }

        // 引擎电源条（紧凑：小圆 + 状态两行 + 启停按钮）
        val powerShape = RoundedCornerShape(AppShape.lg)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(powerShape)
                .background(
                    if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = if (running) (if (zh) "停止引擎" else "Stop engine") else (if (zh) "启动引擎" else "Start engine"),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) (if (zh) "引擎运行中" else "Engine running") else (if (zh) "引擎已停止" else "Engine stopped"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (running) (if (zh) "MCP 在线，AI 客户端可连接" else "MCP online — clients can connect")
                    else (if (zh) "点击启动以暴露 MCP 服务" else "Tap Start to expose the MCP server"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(AppShape.sm))
                    .background(if (running) MaterialTheme.colorScheme.error.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary)
                    .clickable { toggle() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (running) (if (zh) "停止" else "Stop") else (if (zh) "启动" else "Start"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 功能入口（紧凑行列表：一行 ≈34dp；末行 = 全部 MCP 工具）
        Text(
            if (zh) "功能" else "Workbench",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 2.dp),
        )
        val fnShape = RoundedCornerShape(AppShape.lg)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(fnShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)), fnShape),
        ) {
            sats.forEachIndexed { idx, sat ->
                if (idx > 0) HubDivider()
                HubRow(sat.icon, sat.label) { onNavigate(sat.tab, sat.toolCategory) }
            }
            HubDivider()
            // 「全部工具」入口：打开完整 MCP 工具清单（v1.3.5 简洁化时误删，现恢复）
            HubRow(
                Icons.Default.Analytics,
                if (zh) "全部工具 · ${ToolCatalog.ALL.size}" else "All tools · ${ToolCatalog.ALL.size}",
                tint = MaterialTheme.colorScheme.tertiary,
            ) { onNavigate(MainTab.Home, null) }
        }

        // 服务状态行：目录 / 桥接 / 保活（根据配置状态显示）
        ServiceStatusRow(
            zh = zh,
            settings = settings,
            onNavigateSettings = onNavigateSettings,
            onAnalyze = { onNavigate(MainTab.Tools, null) },
        )

        // 快捷统计（紧凑）
        val engineCount = remember(context) {
            runCatching { context.applicationInfo.nativeLibraryDir?.let { java.io.File(it).listFiles { f -> f.isFile && f.name.endsWith(".so") }?.size } ?: 0 }
                .getOrDefault(0)
                .coerceAtLeast(0)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QuickStat(
                ToolCatalog.ALL.size.toString(),
                if (zh) "工具" else "Tools",
            )
            QuickStat(
                engineCount.toString(),
                if (zh) "引擎" else "Engines",
            )
            QuickStat(
                if (running) (if (zh) "在线" else "On") else (if (zh) "离线" else "Off"),
                if (zh) "状态" else "State",
                highlight = running,
            )
        }

        // 连接地址条（单行紧凑，点按复制）
        val addrShape = RoundedCornerShape(AppShape.md)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(addrShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (running) 0.9f else 0.5f))
                .clickable(enabled = running) {
                    clipboard.setText(AnnotatedString(loopbackUrl))
                    Toast.makeText(context, if (zh) "已复制，填到 AI 客户端 MCP 地址" else "Copied", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (running) loopbackUrl else (if (zh) "等待启动引擎…" else "Engine offline"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).clickable {
                        // 无 SAF 提供方设备（部分国产 ROM）会抛 ActivityNotFoundException——捕获并提示手动路径
                        try { pickTree.launch(null) }
                        catch (e: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, if (t.zh) "系统未提供文件选择器（SAF），请先启动服务后在分析页用文件路径打开" else "No SAF picker on this device; open files by path from the analyze tab", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private data class Satellite(val icon: ImageVector, val label: String, val tab: MainTab, val toolCategory: String? = null)

/** 首页紧凑行：26dp 图标 + 标签，行高 ≈34dp（替代原 NavRow 的 58dp）。 */
@Composable
private fun HubRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val resolvedTint = tint ?: MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(AppShape.sm))
                .background(resolvedTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = resolvedTint, modifier = Modifier.size(15.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 首页紧凑分隔线（左侧让出图标宽度）。 */
@Composable
private fun HubDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
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
private fun QuickStat(value: String, label: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 服务状态行：目录 / APK MCP / 保活 + 右侧独立分析按钮。
 * 根据配置状态显示绿/灰指示灯 + 状态文字，卡片可点击跳转到对应设置页。
 * 分析按钮单独放在整行最右侧，点击打开分析页。
 */
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
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 公网隧道状态文本
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppShape.md))
                .background(tunnelColor.copy(alpha = 0.10f))
                .clickable { onNavigateSettings(SettingsDest.Tunnel) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
            Box(
                Modifier
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(accent.copy(alpha = 0.14f))
                    .clickable { onAnalyze() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Filled.Analytics,
                    contentDescription = if (zh) "分析" else "Analyze",
                    tint = accent,
                    modifier = Modifier.size(16.dp),
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
            .clip(RoundedCornerShape(AppShape.md))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
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

/**
 * 星系：外圈旋转轨道 + 8 卫星环绕 + 中央星核。
 * 卫星用极坐标（半径 * cos/sin）绝对定位，容器是正方形。
 */
@Composable
private fun SatelliteSystem(
    running: Boolean,
    sats: List<Satellite>,
    onCore: () -> Unit,
    onSat: (Satellite) -> Unit,
    zh: Boolean,
    maxDiameter: Dp = 320.dp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val infinite = rememberInfiniteTransition(label = "orbit")
    val spin by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = maxDiameter)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // 轨道环（铺底）
        Canvas(Modifier.fillMaxSize()) {
            val r1 = size.minDimension / 2f - 40.dp.toPx()
            val r2 = r1 - 26.dp.toPx()
            drawCircle(color = outline.copy(alpha = 0.25f), radius = r1, style = Stroke(width = 1.2.dp.toPx()))
            rotate(spin) {
                drawCircle(
                    color = accent.copy(alpha = if (running) 0.4f else 0.15f),
                    radius = r2,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        // 用自定义 Layout 统一测量并按容器真实尺寸把星核放中央、卫星放圆周。
        // 顺序：measurables[0]=星核，之后依次是各卫星。
        Layout(
            content = {
                StarCore(running = running, onClick = onCore, zh = zh)
                sats.forEach { sat ->
                    SatelliteNode(sat = sat, running = running, onClick = { onSat(sat) })
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            // 卫星圆周半径：容器一半再留出卫星尺寸的边距
            val satHalf = (placeables.getOrNull(1)?.width ?: 0) / 2
            val radius = (minOf(w, h) / 2f) - satHalf - 6.dp.roundToPx()
            layout(w, h) {
                // 星核居中
                placeables.firstOrNull()?.let { core ->
                    core.place(w / 2 - core.width / 2, h / 2 - core.height / 2)
                }
                // 卫星环绕（从正上方开始顺时针均分）
                val satCount = placeables.size - 1
                for (i in 0 until satCount) {
                    val p = placeables[i + 1]
                    val angle = Math.toRadians(-90.0 + i * (360.0 / satCount))
                    val cx = w / 2f + radius * cos(angle)
                    val cy = h / 2f + radius * sin(angle)
                    p.place((cx - p.width / 2).toInt(), (cy - p.height / 2).toInt())
                }
            }
        }
    }
}

@Composable
private fun SatelliteNode(
    sat: Satellite,
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppShape.xl)
    val sizeDp = 56.dp
    Column(
        modifier
            .size(width = sizeDp, height = sizeDp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(sat.icon, contentDescription = sat.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(1.dp))
        Text(
            sat.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun StarCore(running: Boolean, onClick: () -> Unit, zh: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (running) 1f else 0.94f, tween(300), label = "core-scale")
    val infinite = rememberInfiniteTransition(label = "core")
    val pulse by infinite.animateFloat(
        initialValue = 0.9f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(128.dp)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        // 运行时脉冲光晕
        if (running) {
            Box(
                Modifier
                    .size(128.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
            )
        }
        Column(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        if (running) listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f))
                        else listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface),
                    ),
                )
                .drawBehind {
                    drawCircle(
                        color = if (running) accent else accent.copy(alpha = 0.3f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                .clickable { onClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = if (running) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (running) (if (zh) "运行中" else "Running") else (if (zh) "点击启动" else "Tap to start"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (running) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
