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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
 * 首页（命令台）。
 *
 * 版式对齐 Exbin / Material 3 的「工具栏 + 分组内容」结构，不再是「一堆彩色圆角卡片」：
 *  1. 顶栏只放标题（Exbin 的 MaterialToolbar 就是纯标题，56dp、TitleMedium）；
 *  2. 主状态用一张卡（surfaceVariant、12dp 圆角、无描边、elevation 0），状态点 + 一行标题
 *     + 一行说明 + 一个实心按钮，右侧不再堆第二个平级按钮；
 *  3. 工作台是 4 列图标网格（图标 40dp 底 + 11sp 标签），比 2 列大卡的信息密度高一倍，
 *     且全部用中性色 —— 8 个入口各配一个色会让整页变成调色板；
 *  4. 服务是卡内 4 行同宽列表（行高 44dp、行间 1dp 分隔线），状态文字用主色/次要色区分，
 *     不再用 2×2 格子（格子里的值经常折行，读起来比列表累）；
 *  5. 底部只有一个主 CTA（primaryContainer），是整页唯一的「大色块」。
 *
 * 字号全部走 [AppText] 档位（11/12/14/16），页边距/分区间距走 [LocalUiMetrics]，
 * 这样设置里的「密度 / 字号」才会真的改变首页，而不是只改别的页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        val u = treeUri
        if (u == null) (if (zh) "点按选择工作目录" else "Tap to pick a folder")
        else runCatching { u.lastPathSegment }
            .getOrNull()
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: (if (zh) "已设置" else "set")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            // 外层 MainActivity 的 Scaffold 已给过状态栏 padding，这里再叠一次会多出一截空白
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = {
                Text(
                    if (zh) "塔菲逆核" else "Taffy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            actions = {
                // 设置是「全局目的地」而不是功能入口，放顶栏比塞进 8 格网格更符合直觉
                androidx.compose.material3.IconButton(onClick = { onNavigate(MainTab.Settings, null) }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = if (zh) "设置" else "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        val m = LocalUiMetrics.current
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = m.pagePad),
        ) {
            Spacer(Modifier.height(4.dp))

            EngineCard(
                running = running,
                loopbackUrl = loopbackUrl,
                dirName = dirName,
                onToggle = { toggle() },
                onCopy = {
                    clipboard.setText(AnnotatedString(loopbackUrl))
                    Toast.makeText(
                        context,
                        if (zh) "已复制，填到 AI 客户端 MCP 地址" else "Copied",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onPickDir = {
                    try {
                        pickTree.launch(null)
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(context, if (zh) "没有可用的文件选择器" else "No file picker", Toast.LENGTH_LONG).show()
                    }
                },
                zh = zh,
            )

            SectionTitle(
                text = if (zh) "工作台" else "Workbench",
                action = if (zh) "全部工具" else "All tools",
                onAction = { onNavigate(MainTab.Home, null) },
            )
            WorkbenchGrid(items = sats, onNavigate = onNavigate)

            SectionTitle(if (zh) "服务" else "Services")
            ServiceCard(zh = zh, settings = settings, onNavigateSettings = onNavigateSettings)

            Spacer(Modifier.height(m.sectionGap))
            AnalyzeCta(zh = zh) { onNavigate(MainTab.Tools, null) }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ══════════════════════════ 主状态卡 ══════════════════════════

@Composable
private fun EngineCard(
    running: Boolean,
    loopbackUrl: String,
    dirName: String,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onPickDir: () -> Unit,
    zh: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppShape.md),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (running) cs.primary else cs.outline),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) (if (zh) "MCP 引擎运行中" else "Engine running")
                        else (if (zh) "MCP 引擎已停止" else "Engine stopped"),
                        fontSize = AppText.bodyStrong,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (running) (if (zh) "AI 客户端现在可以连接" else "Clients can connect now")
                        else (if (zh) "启动后 AI 客户端才能调用工具" else "Start so clients can call tools"),
                        fontSize = AppText.label,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = onToggle,
                    shape = RoundedCornerShape(AppShape.sm),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        if (running) (if (zh) "停止" else "Stop") else (if (zh) "启动" else "Start"),
                        fontSize = AppText.body,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = cs.outlineVariant)
            Spacer(Modifier.height(4.dp))

            // 一行信息条：运行中给端点（点按复制），停止时给工作目录（点按选择）
            if (running) {
                InfoRow(
                    icon = Icons.Filled.ContentCopy,
                    text = loopbackUrl,
                    monospace = true,
                    trailingChevron = false,
                    onClick = onCopy,
                )
            } else {
                InfoRow(
                    icon = Icons.Filled.FolderOpen,
                    text = dirName,
                    monospace = false,
                    trailingChevron = true,
                    onClick = onPickDir,
                )
            }
        }
    }
}

/**
 * 卡内信息条。高度固定 40dp、水平内边距 4dp（卡片已给 16dp），点按整行生效。
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    text: String,
    monospace: Boolean,
    trailingChevron: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontSize = AppText.body,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            if (trailingChevron) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.ContentCopy,
            contentDescription = null,
            tint = cs.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ══════════════════════════ 分区标题 ══════════════════════════

/**
 * 分组标题：11sp、字重 Medium、onSurfaceVariant、字距 0.8sp。
 * 用主色写分组标题是 iOS「彩色小节」的做法，一页里出现三次就变调色板 —— Exbin 的
 * 分组标题一律是中性次要色，只有右侧动作是主色。
 */
@Composable
private fun SectionTitle(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 0.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontSize = AppText.label,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            color = cs.onSurfaceVariant,
        )
        if (action != null && onAction != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(AppShape.sm))
                    .clickable { onAction() }
                    .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, fontSize = AppText.label, color = cs.primary)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ══════════════════════════ 工作台 4 列网格 ══════════════════════════

/**
 * 4 列图标网格：图标底 40dp（12dp 圆角、surfaceVariant）+ 11sp 单行标签。
 * 全部中性色 —— 8 个入口若各配一个主题色，整页会像调色板；Exbin 里彩色只用在
 * 状态（在线/运行中）与主按钮上。
 */
@Composable
private fun WorkbenchGrid(items: List<Satellite>, onNavigate: (MainTab, String?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { sat ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(AppShape.md))
                            .clickable { onNavigate(sat.tab, sat.toolCategory) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(AppShape.md))
                                .background(cs.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                sat.icon,
                                contentDescription = null,
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            sat.label,
                            fontSize = AppText.label,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ══════════════════════════ 服务列表 ══════════════════════════

private data class ServiceCell(
    val name: String,
    val value: String,
    val highlight: Boolean,
    val onClick: () -> Unit,
)

/**
 * 服务状态卡：卡内 4 行同宽列表。旧版是 2×2 格子，格子宽度一半、值经常折行成两行，
 * 而且每格还要塞状态点 + 箭头，读起来比列表累。
 */
@Composable
private fun ServiceCard(
    zh: Boolean,
    settings: SettingsStore,
    onNavigateSettings: (SettingsDest) -> Unit,
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
        cfStarting || boreConnecting -> if (zh) "连接中…" else "connecting…"
        else -> if (zh) "未开启" else "off"
    }

    val cells = listOf(
        ServiceCell(
            name = if (zh) "隧道" else "Tunnel",
            value = tunnelValue,
            highlight = anyTunnelRunning || anyTunnelStarting,
            onClick = { onNavigateSettings(SettingsDest.Tunnel) },
        ),
        ServiceCell(
            name = if (zh) "工作目录" else "Work dir",
            value = if (dirConfigured) (if (zh) "已设置" else "set") else (if (zh) "未设置" else "none"),
            highlight = dirConfigured,
            onClick = { onNavigateSettings(SettingsDest.ServiceConfig) },
        ),
        ServiceCell(
            name = if (zh) "APK 桥接" else "APK bridge",
            value = if (bridgeOnline) (if (zh) "已连接" else "linked")
            else if (bridgeConfigured) (if (zh) "离线" else "offline") else (if (zh) "未配置" else "none"),
            highlight = bridgeOnline,
            onClick = { onNavigateSettings(SettingsDest.ApkBridge) },
        ),
        ServiceCell(
            name = if (zh) "后台保活" else "Keep-alive",
            value = if (keepAliveReady) (if (zh) "就绪" else "ready") else (if (zh) "未就绪" else "off"),
            highlight = keepAliveReady,
            onClick = { onNavigateSettings(SettingsDest.KeepAlive) },
        ),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppShape.md),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = cs.outlineVariant)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable { cell.onClick() }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (cell.highlight) cs.primary else cs.outline),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        cell.name,
                        modifier = Modifier.weight(1f),
                        fontSize = AppText.bodyStrong,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        cell.value,
                        fontSize = AppText.label,
                        color = if (cell.highlight) cs.primary else cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = cs.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ══════════════════════════ 主 CTA ══════════════════════════

/** 整页唯一的大色块：进分析台。 */
@Composable
private fun AnalyzeCta(zh: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppShape.md)).clickable { onClick() },
        shape = RoundedCornerShape(AppShape.md),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Code, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (zh) "进入分析台" else "Open workbench",
                    fontSize = AppText.bodyStrong,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (zh) "选择 SO / APK 开始逆向" else "Pick an SO / APK to start",
                    fontSize = AppText.label,
                    color = cs.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ══════════════════════════ 数据 ══════════════════════════

private data class Satellite(
    val icon: ImageVector,
    val label: String,
    val tab: MainTab,
    val toolCategory: String? = null,
)

/** 8 个入口。4 列网格只放主标题，副标题留给「全部工具」页。 */
private fun workbenchTiles(zh: Boolean): List<Satellite> = listOf(
    Satellite(Icons.Default.Code, if (zh) "伪代码" else "Decompile", MainTab.Home, "decompile"),
    Satellite(Icons.Default.LockOpen, if (zh) "脱壳" else "Unpack", MainTab.Home, "unpack"),
    Satellite(Icons.Default.Memory, if (zh) "SO 分析" else "SO", MainTab.Tools, null),
    Satellite(Icons.Default.FlashOn, if (zh) "模拟执行" else "Emulate", MainTab.Home, "unidbg"),
    Satellite(Icons.Default.MyLocation, if (zh) "Frida" else "Frida", MainTab.Home, "frida"),
    Satellite(Icons.Default.Inventory2, if (zh) "回编" else "Rebuild", MainTab.Home, "rebuild"),
    Satellite(Icons.Default.RssFeed, if (zh) "日志" else "Logs", MainTab.Home, "logs"),
    Satellite(Icons.Filled.Settings, if (zh) "设置" else "Settings", MainTab.Settings, null),
)
