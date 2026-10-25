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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    // 端点只在「运行状态 / 端口」变化时重算。旧实现每秒 `NetworkInterface.getNetworkInterfaces()`
    // 枚举一次网卡再把结果写进 State —— 首页因此每秒重组一次，且主线程多一次 syscall，纯浪费。
    val endpoints = remember(running, settings.port) {
        if (running) filteredEndpoints(context, settings, settings.port) else emptyList()
    }

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
            Satellite(Icons.Default.Code, if (zh) "反编译" else "Decompile", MainTab.Home, "decompile", 0,
                if (zh) "伪代码 / 反编译视图" else "Pseudo-C / decompile"),
            Satellite(Icons.Default.LockOpen, if (zh) "脱壳" else "Unpack", MainTab.Home, "unpack", 1,
                if (zh) "DEX / VMP 抽取" else "DEX / VMP dump"),
            Satellite(Icons.Default.Memory, if (zh) "SO 分析" else "SO Analysis", MainTab.Home, "soanalyze", 2,
                if (zh) "符号 / CFG / 交叉引用" else "symbols / CFG / xrefs"),
            Satellite(Icons.Default.FlashOn, if (zh) "模拟执行" else "Emulate", MainTab.Home, "emulate", 3,
                if (zh) "unidbg 跑 native" else "run native in unidbg"),
            Satellite(Icons.Default.MyLocation, "Frida", MainTab.Home, "frida", 4,
                if (zh) "动态 Hook / 注入" else "dynamic hook"),
            Satellite(Icons.Default.Inventory2, if (zh) "回编" else "Rebuild", MainTab.Home, "rebuild", 5,
                if (zh) "重打包 + 签名" else "repack + sign"),
            Satellite(Icons.Default.RssFeed, if (zh) "日志" else "Logs", MainTab.Home, "logs", 6,
                if (zh) "logcat / 崩溃记录" else "logcat / crash"),
            Satellite(Icons.Default.Settings, if (zh) "设置" else "Settings", MainTab.Settings, null, 7,
                if (zh) "引擎 / 沙箱 / 外观" else "engine / sandbox / theme"),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
    ) {
        // ── 标题区 ──
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (zh) "塔菲逆核" else "Taffy NieHe",
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (zh) "MCP 命令中枢 · 逆向工作台" else "MCP command hub · reverse workbench",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnPill(running = running, zh = zh)
        }

        // ── 引擎卡：状态 + 启停 + 端点（合成一张卡，旧版分成「引擎 / 连接地址」两张，重复且占屏幕）──
        Spacer(Modifier.height(10.dp))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            shape = RoundedCornerShape(AppShape.lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val accent = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) (if (zh) "MCP 引擎运行中" else "MCP engine running")
                            else (if (zh) "MCP 引擎已停止" else "MCP engine stopped"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (running) (if (zh) "AI 客户端现在可以连接" else "AI clients can connect now")
                            else (if (zh) "启动后 AI 客户端才能调用工具" else "Start it so clients can call tools"),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { toggle() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(AppShape.sm),
                    ) {
                        Text(
                            if (running) (if (zh) "停止" else "Stop") else (if (zh) "启动" else "Start"),
                            fontSize = 12.sp,
                        )
                    }
                }
                HorizontalDivider(
                    Modifier.padding(top = 11.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp)
                        .clip(RoundedCornerShape(AppShape.sm))
                        .clickable {
                            if (running) {
                                clipboard.setText(AnnotatedString(loopbackUrl))
                                Toast.makeText(
                                    context,
                                    if (zh) "已复制，填到 AI 客户端 MCP 地址" else "Copied",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                try {
                                    pickTree.launch(null)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    Toast.makeText(
                                        context,
                                        if (zh) "系统未提供文件选择器（SAF），请在分析页用文件路径打开"
                                        else "No SAF picker on this device",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) loopbackUrl
                            else (if (zh) "未启动 · 点按选择工作目录" else "Offline · tap to pick a workdir"),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (running) (if (zh) "点按复制，填到 AI 客户端的 MCP 地址" else "Tap to copy for your MCP client")
                            else (if (zh) "尚未选择工作目录" else "No working directory yet"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        if (running) Icons.Filled.ContentCopy else Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // ── 工作台（2 列卡：图标 + 名称 + 副标题，比 4 列小宫格可读得多）──
        IosGroupHeader(if (zh) "工作台" else "Workbench")
        WorkbenchGrid(sats, onNavigate)
        TextButton(
            onClick = { onNavigate(MainTab.Home, null) },
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 0.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                if (zh) "全部工具" else "All tools",
                fontSize = AppText.label,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
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
    }
}

private data class Satellite(
    val icon: ImageVector,
    val label: String,
    val tab: MainTab,
    val toolCategory: String? = null,
    val accent: Int = 0,
    val hint: String = "",
)

/** iOS 风格分组卡片：圆角 12dp、无描边、页面横向 16dp 边距。 */
@Composable
private fun IosGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(AppShape.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}

/** 工作台网格：2 列卡（图标 + 名称 + 副标题）。窄屏不挤压，整块一眼可扫。 */
@Composable
private fun WorkbenchGrid(
    items: List<Satellite>,
    onNavigate: (MainTab, String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { sat ->
                    val accent = satelliteColor(sat.accent)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigate(sat.tab, sat.toolCategory) },
                        shape = RoundedCornerShape(AppShape.md),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(sat.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sat.label,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (sat.hint.isNotBlank()) {
                                    Text(
                                        sat.hint,
                                        fontSize = 9.5.sp,
                                        color = cs.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IosGroupHeader(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 3.dp),
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
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
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
        },
        headlineContent = {
            Text(
                title,
                fontSize = if (titleMono) 12.sp else AppText.bodyStrong,
                fontFamily = if (titleMono) FontFamily.Monospace else FontFamily.Default,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    subtitle,
                    fontSize = AppText.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
        },
    )
}

/** iOS 内缩分隔线：从图标右侧（14 + 28 + 12 = 54dp）开始。 */
@Composable
private fun IosDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 顶部连接状态胶囊（M3 AssistChip：小圆点 leadingIcon + 状态文字）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnPill(running: Boolean, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dot = if (running) cs.primary else cs.outline
    AssistChip(
        onClick = {},
        label = {
            Text(
                if (running) (if (zh) "在线" else "Online") else (if (zh) "离线" else "Offline"),
                fontSize = AppText.label,
                fontWeight = FontWeight.Medium,
                color = if (running) cs.primary else cs.onSurfaceVariant,
            )
        },
        leadingIcon = { Box(Modifier.size(7.dp).clip(CircleShape).background(dot)) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = if (running) cs.primary else cs.onSurfaceVariant,
        ),
    )
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
