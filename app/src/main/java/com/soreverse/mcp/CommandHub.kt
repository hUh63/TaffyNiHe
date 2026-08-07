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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Analytics
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
            Satellite("📖", if (zh) "反编译" else "Decompile", MainTab.Tools, "decompile"),
            Satellite("🔓", if (zh) "脱壳" else "Unpack", MainTab.Tools, "unpack"),
            Satellite("🧬", if (zh) "SO 分析" else "SO", MainTab.Tools, "soanalyze"),
            Satellite("⚡", if (zh) "模拟" else "Emulate", MainTab.Tools, "emulate"),
            Satellite("🎯", "Frida", MainTab.Tools, "frida"),
            Satellite("📦", if (zh) "回编" else "Rebuild", MainTab.Tools, "rebuild"),
            Satellite("📡", if (zh) "日志" else "Logs", MainTab.Tools, "logs"),
            Satellite("⚙️", if (zh) "设置" else "Settings", MainTab.Settings),
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部品牌
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Spacer(Modifier.height(24.dp)) // 下移一行半
                Text(
                    if (zh) "塔菲逆核" else "Taffy NieHe",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFF375F),
                )
                Text(
                    if (zh) "聚合式逆向 · 命令中枢" else "Reverse Command Hub",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 连接状态点
            Spacer(Modifier.height(24.dp)) // 下移一行半
            ConnDot(running = running, zh = zh)
        }

        // 中央星系（星核 + 卫星环绕）
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            SatelliteSystem(
                running = running,
                sats = sats,
                onCore = { toggle() },
                onSat = { onNavigate(it.tab, it.toolCategory) },
            )
        }

        // 服务状态行：目录 / 桥接 / 保活（根据配置状态显示）
        ServiceStatusRow(
            zh = zh,
            settings = settings,
            onNavigateSettings = onNavigateSettings,
            onAnalyze = { onNavigate(MainTab.Tools, null) },
        )

        // 工具列表入口文本
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                if (zh) "工具列表" else "Tools",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onNavigate(MainTab.Tools, null) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // 快捷统计
        val engineCount = remember(context) {
            runCatching { context.applicationInfo.nativeLibraryDir?.let { java.io.File(it).listFiles { f -> f.isFile && f.name.endsWith(".so") }?.size } ?: 0 }
                .getOrDefault(0)
                .coerceAtLeast(0)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
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

        // 连接地址条
        val addrShape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(addrShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (running) 0.9f else 0.5f))
                .clickable(enabled = running) {
                    clipboard.setText(AnnotatedString(loopbackUrl))
                    Toast.makeText(context, if (zh) "已复制，填到 AI 客户端 MCP 地址" else "Copied", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) loopbackUrl else (if (zh) "等待启动引擎…" else "Engine offline"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (running) (if (zh) "点击复制连接地址" else "Tap to copy") else (if (zh) "点中央星核启动" else "Tap the core to start"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { pickTree.launch(null) })
        }
        Spacer(Modifier.height(18.dp))
    }
}

private data class Satellite(val icon: String, val label: String, val tab: MainTab, val toolCategory: String? = null)

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
            style = MaterialTheme.typography.titleMedium,
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
    LaunchedEffect(Unit) {
        while (true) {
            tunnelStatus = activeServer(context)?.tunnel?.status()
            delay(2000)
        }
    }
    val tunnelRunning = tunnelStatus?.state == com.soreverse.mcp.core.CloudflareTunnelManager.State.RUNNING
    val tunnelStarting = tunnelStatus?.state == com.soreverse.mcp.core.CloudflareTunnelManager.State.STARTING
    val tunnelColor = if (tunnelRunning) Color(0xFF34C759) else if (tunnelStarting) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant
    val tunnelText = when {
        tunnelRunning -> (if (zh) "公网隧道 · 运行中" else "Tunnel · Running") + (tunnelStatus?.publicUrl?.let { " $it" } ?: "")
        tunnelStarting -> if (zh) "公网隧道 · 连接中…" else "Tunnel · Connecting…"
        else -> if (zh) "公网隧道 · 未开启" else "Tunnel · Off"
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
                .clip(RoundedCornerShape(10.dp))
                .background(tunnelColor.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onNavigateSettings(SettingsDest.Tunnel) }
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        // 三个状态卡片 — 使用 IntrinsicSize.Min 保证等高
        Row(
            Modifier.weight(1f).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ServiceStatusItem(
                label = if (zh) "目录" else "Directory",
                statusText = if (dirConfigured) (if (zh) "已设置" else "Set") else (if (zh) "未设置" else "Not Set"),
                configured = dirConfigured,
                onClick = { onNavigateSettings(SettingsDest.ServiceConfig) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ServiceStatusItem(
                label = if (zh) "桥接" else "Bridge",
                statusText = if (bridgeOnline) (if (zh) "已连接" else "Linked") else if (bridgeConfigured) (if (zh) "未连接" else "Offline") else (if (zh) "未配置" else "None"),
                configured = bridgeOnline,
                onClick = { onNavigateSettings(SettingsDest.ApkBridge) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ServiceStatusItem(
                label = if (zh) "保活" else "KeepAlive",
                statusText = if (keepAliveReady) (if (zh) "已就绪" else "Ready") else (if (zh) "未就绪" else "Off"),
                configured = keepAliveReady,
                onClick = { onNavigateSettings(SettingsDest.KeepAlive) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        // 右侧独立分析按钮
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onAnalyze() }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Filled.Analytics,
                    contentDescription = if (zh) "分析" else "Analyze",
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                if (zh) "分析" else "Analyze",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
            )
        }
        }
    }
}

@Composable
private fun ServiceStatusItem(
    label: String,
    statusText: String,
    configured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = Color(0xFF34C759)
    val gray = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(12.dp)
    val dotColor = if (configured) green else gray

    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (configured) green else MaterialTheme.colorScheme.onSurfaceVariant,
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
                StarCore(running = running, onClick = onCore)
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
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .size(width = 66.dp, height = 66.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(sat.icon, fontSize = 24.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            sat.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun StarCore(running: Boolean, onClick: () -> Unit) {
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClick() },
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
                if (running) "运行中" else "点击启动",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (running) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
