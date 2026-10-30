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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.service.McpForegroundService
import kotlinx.coroutines.delay

/**
 * 塔菲逆核 · 首页。
 *
 * 设计目标（v1.5.8）：**一屏装完，不需要滚动**。
 *  · 标题和连接状态并成一行，去掉副标题（"MCP 命令中枢 · 逆向工作台"属于自我介绍，
 *    每开一次首页看一次没有信息量）。
 *  · 引擎卡从「状态行 + 分隔线 + 端点行（两行文字）」压成「状态行 + 一行端点/目录」。
 *  · 工作台保持 2 列（4 列会让副标题被省略号吃掉），但每格压到 48dp，图标 30dp。
 *  · 「全部工具」从独占一行的 TextButton 挪进「工作台」标题行右侧。
 *  · 服务区从 5 行 ListItem（每行 44dp）改成 2×2 状态格 + 一行分析台入口。
 *
 * 底层逻辑未变：McpForegroundService 启停 / SettingsStore / filteredEndpoints /
 * EngineProvider / activeBridge / Tunnel 轮询。
 *
 * @param onNavigate 功能行点击 —— 交给 MainActivity 切换到对应 MainTab（category=null = 全部工具）
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

    val sats = remember(zh) { workbenchTiles(zh) }
    // 工作目录只显示末段名字，完整路径在设置里看
    val dirName = remember(treeUri) {
        // Uri.getLastPathSegment() 对 opaque URI 会抛 UnsupportedOperationException。SAF 正常返回
        // tree URI（hierarchical）不受影响，但首页不该因为一个目录名崩掉。
        runCatching { treeUri?.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        // ── 标题行：名称 + 连接状态（一行）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (zh) "塔菲逆核" else "Taffy NieHe",
                fontSize = 21.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ConnPill(running = running, zh = zh)
        }

        // ── 引擎卡：状态 + 启停 + 端点/目录（两行，无分隔线）──
        Card(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp).fillMaxWidth(),
            shape = RoundedCornerShape(AppShape.lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val accent = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                        Text(
                            if (running) (if (zh) "MCP 引擎运行中" else "MCP engine running")
                            else (if (zh) "MCP 引擎已停止" else "MCP engine stopped"),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (running) (if (zh) "AI 客户端现在可以连接" else "AI clients can connect now")
                            else (if (zh) "启动后 AI 客户端才能调用工具" else "Start it so clients can call tools"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { toggle() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
                        shape = RoundedCornerShape(AppShape.sm),
                    ) {
                        Text(
                            if (running) (if (zh) "停止" else "Stop") else (if (zh) "启动" else "Start"),
                            fontSize = 12.sp,
                        )
                    }
                }
                // 第二行：运行中 = MCP 地址（点按复制）；停止中 = 工作目录（点按选择）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp)
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
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (running) Icons.Filled.Link else Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        if (running) loopbackUrl
                        else if (dirName != null) (if (zh) "工作目录：$dirName · 点按更换" else "Work dir: $dirName · tap to change")
                        else (if (zh) "点按选择工作目录" else "Tap to pick a work dir"),
                        modifier = Modifier.weight(1f),
                        fontSize = if (running) 10.5.sp else 11.sp,
                        fontFamily = if (running) FontFamily.Monospace else FontFamily.Default,
                        color = if (running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (running) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        // ── 工作台（标题行右侧带「全部工具」入口）──
        SectionHeader(
            if (zh) "工作台" else "Workbench",
            action = if (zh) "全部工具" else "All tools",
            onAction = { onNavigate(MainTab.Home, null) },
        )
        WorkbenchGrid(sats, onNavigate)

        // ── 服务 ──
        SectionHeader(if (zh) "服务" else "Service")
        ServiceArea(
            zh = zh,
            settings = settings,
            onNavigateSettings = onNavigateSettings,
            onAnalyze = { onNavigate(MainTab.Tools, null) },
        )
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

/**
 * 8 个功能区。副标题按「2 列卡片宽度内能完整显示」重写 —— 首页没有省略号截断的余地，
 * 原来的「符号 / CFG / 交叉引用」在 2 列里必然变成「符号 / CFG / 交叉...」。
 */
private fun workbenchTiles(zh: Boolean): List<Satellite> = listOf(
    Satellite(Icons.Default.Code, if (zh) "反编译" else "Decompile", MainTab.Home, "decompile", 0,
        if (zh) "伪代码 / 反编译" else "pseudo-C / decompile"),
    Satellite(Icons.Default.LockOpen, if (zh) "脱壳" else "Unpack", MainTab.Home, "unpack", 1,
        if (zh) "DEX / VMP 抽取" else "DEX / VMP dump"),
    Satellite(Icons.Default.Memory, if (zh) "SO 分析" else "SO Analysis", MainTab.Home, "soanalyze", 2,
        if (zh) "符号 / CFG / 交叉" else "symbols / CFG / xref"),
    Satellite(Icons.Default.FlashOn, if (zh) "模拟执行" else "Emulate", MainTab.Home, "emulate", 3,
        if (zh) "unidbg 跑 native" else "native in unidbg"),
    Satellite(Icons.Default.MyLocation, "Frida", MainTab.Home, "frida", 4,
        if (zh) "动态 Hook / 注入" else "dynamic hook"),
    Satellite(Icons.Default.Inventory2, if (zh) "回编" else "Rebuild", MainTab.Home, "rebuild", 5,
        if (zh) "重打包 + 签名" else "repack + sign"),
    Satellite(Icons.Default.RssFeed, if (zh) "日志" else "Logs", MainTab.Home, "logs", 6,
        if (zh) "logcat / 崩溃" else "logcat / crash"),
    Satellite(Icons.Default.Settings, if (zh) "设置" else "Settings", MainTab.Settings, null, 7,
        if (zh) "引擎 / 沙箱 / 外观" else "engine / sandbox"),
)

/** 分组标题行；[action] 不为空时右侧带一个「文字 + chevron」入口（省掉独占一行的按钮）。 */
@Composable
private fun SectionHeader(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, top = 13.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (action != null && onAction != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(AppShape.sm))
                    .clickable { onAction() }
                    .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** 工作台：2 列 × 4 行，每格 48dp（图标 30dp + 名称 + 副标题）。 */
@Composable
private fun WorkbenchGrid(
    items: List<Satellite>,
    onNavigate: (MainTab, String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { sat ->
                    val accent = satelliteColor(sat.accent)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable { onNavigate(sat.tab, sat.toolCategory) },
                        shape = RoundedCornerShape(AppShape.md),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(sat.icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sat.label,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (sat.hint.isNotBlank()) {
                                    Text(
                                        sat.hint,
                                        fontSize = 9.5.sp,
                                        lineHeight = 12.sp,
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
        border = null,
    )
}

/** 功能图标配色轮转（primary / tertiary / secondary）。 */
@Composable
private fun satelliteColor(idx: Int): Color = when (idx % 3) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/**
 * 服务区：4 项状态压成 2×2 格（隧道 / 目录 / 桥接 / 保活），下面单独一行分析台入口。
 * 状态读取逻辑与旧版 ServiceStatusContent 完全一致，只是把 5 行 ListItem 换成格子。
 */
@Composable
private fun ServiceArea(
    zh: Boolean,
    settings: SettingsStore,
    onNavigateSettings: (SettingsDest) -> Unit,
    onAnalyze: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dirConfigured = settings.treeUri != null || settings.useDefaultWorkDir
    var bridgeOnline by remember { mutableStateOf(activeBridge(context).state().online) }
    val bridgeConfigured = settings.apkMcpConfigs.isNotEmpty() || settings.apkMcpUrl.isNotBlank()
    var tunnelStatus by remember { mutableStateOf(activeServer(context)?.tunnel?.status()) }
    var boreRunning by remember { mutableStateOf(com.soreverse.mcp.core.BoreTunnelService.isRunning(context)) }
    var boreConnecting by remember { mutableStateOf(com.soreverse.mcp.core.BoreTunnelService.isConnecting(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            bridgeOnline = activeBridge(context).state().online
            tunnelStatus = activeServer(context)?.tunnel?.status()
            boreRunning = com.soreverse.mcp.core.BoreTunnelService.isRunning(context)
            boreConnecting = com.soreverse.mcp.core.BoreTunnelService.isConnecting(context)
            delay(2000)
        }
    }
    val keepAliveReady = settings.wakeLockEnabled && settings.floatingEnabled &&
        settings.tunnelKeepAlive && settings.bootAutoStart
    val cfRunning = tunnelStatus?.state == com.soreverse.mcp.core.CloudflareTunnelManager.State.RUNNING
    val cfStarting = tunnelStatus?.state == com.soreverse.mcp.core.CloudflareTunnelManager.State.STARTING
    val anyTunnelRunning = cfRunning || boreRunning
    val anyTunnelStarting = (cfStarting || boreConnecting) && !anyTunnelRunning
    val tunnelValue = when {
        cfRunning -> if (zh) "Cloudflare 运行中" else "CF running"
        boreRunning -> if (zh) "Bore 运行中" else "Bore running"
        cfStarting -> if (zh) "连接中…" else "connecting…"
        boreConnecting -> if (zh) "连接中…" else "connecting…"
        else -> if (zh) "未开启" else "off"
    }
    val tunnelColor = when {
        anyTunnelRunning -> cs.primary
        anyTunnelStarting -> cs.tertiary
        else -> cs.outline
    }

    val cells = listOf(
        ServiceCell(
            name = if (zh) "隧道" else "Tunnel",
            value = tunnelValue,
            dot = tunnelColor,
            highlight = anyTunnelRunning || anyTunnelStarting,
            onClick = { onNavigateSettings(SettingsDest.Tunnel) },
        ),
        ServiceCell(
            name = if (zh) "工作目录" else "Work dir",
            value = if (dirConfigured) (if (zh) "已设置" else "set") else (if (zh) "未设置" else "none"),
            dot = if (dirConfigured) cs.primary else cs.outline,
            highlight = dirConfigured,
            onClick = { onNavigateSettings(SettingsDest.ServiceConfig) },
        ),
        ServiceCell(
            name = if (zh) "APK 桥接" else "APK bridge",
            value = if (bridgeOnline) (if (zh) "已连接" else "linked")
            else if (bridgeConfigured) (if (zh) "离线" else "offline") else (if (zh) "未配置" else "none"),
            dot = if (bridgeOnline) cs.primary else cs.outline,
            highlight = bridgeOnline,
            onClick = { onNavigateSettings(SettingsDest.ApkBridge) },
        ),
        ServiceCell(
            name = if (zh) "后台保活" else "Keep-alive",
            value = if (keepAliveReady) (if (zh) "就绪" else "ready") else (if (zh) "未就绪" else "off"),
            dot = if (keepAliveReady) cs.primary else cs.outline,
            highlight = keepAliveReady,
            onClick = { onNavigateSettings(SettingsDest.KeepAlive) },
        ),
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { cell ->
                    Card(
                        modifier = Modifier.weight(1f).clickable { cell.onClick() },
                        shape = RoundedCornerShape(AppShape.md),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(cell.dot))
                                Text(
                                    cell.name,
                                    fontSize = 11.5.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                cell.value,
                                modifier = Modifier.padding(start = 12.dp),
                                fontSize = 10.5.sp,
                                lineHeight = 13.sp,
                                color = if (cell.highlight) cs.primary else cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
        }
        // 分析台入口（原来是服务列表的最后一行）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppShape.md),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { onAnalyze() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(cs.tertiary))
                Text(
                    if (zh) "进入分析台" else "Analysis workspace",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.outline,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

private data class ServiceCell(
    val name: String,
    val value: String,
    val dot: Color,
    val highlight: Boolean,
    val onClick: () -> Unit,
)
