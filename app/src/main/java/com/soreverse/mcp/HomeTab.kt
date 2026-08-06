package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * 塔菲逆核 首页 — 小白向:一个大开关 + 状态 + 连接地址 + 三步引导。
 * 底层完全复用现有逻辑(McpForegroundService / SettingsStore / filteredEndpoints)。
 */
@Composable
internal fun HomeTab(
    t: UiText,
    settings: SettingsStore,
    onOpenTools: () -> Unit,
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

    val loopbackUrl = endpoints.firstOrNull { it.url.contains("127.0.0.1") }?.url
        ?: "http://127.0.0.1:${settings.port}/mcp"

    fun toggle() {
        if (running) {
            McpForegroundService.stop(context)
            running = false
        } else {
            runCatching { McpForegroundService.start(context) }
                .onSuccess {
                    Toast.makeText(context, if (t.zh) "服务启动中…" else "Starting…", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, if (t.zh) "启动失败" else "Failed to start", Toast.LENGTH_LONG).show()
                }
        }
    }

    PageScroll {
        ScreenHeader(
            title = if (t.zh) "塔菲逆核" else "Taffy NieHe",
            subtitle = if (t.zh) "本地逆向工具聚合 · 连一个地址即可用全部工具" else "Local reverse-engineering gateway",
            titleColor = Color(0xFFFF375F),
        )

        // ── 大启动开关 ──
        BigPowerCard(zh = t.zh, running = running, onToggle = { toggle() })

        // ── 连接地址(运行时显示,一键复制) ──
        GlassGroup(title = if (t.zh) "连接地址" else "Connection") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(AnnotatedString(loopbackUrl))
                        Toast.makeText(context, if (t.zh) "已复制,填到 AI 客户端的 MCP 地址" else "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        loopbackUrl,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (t.zh) "点这里复制,粘贴到 AI(如玄星)的 MCP 服务器地址" else "Tap to copy into your AI client's MCP server URL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
            }
        }

        // ── 工作目录(逆向要分析的文件放哪) ──
        GlassGroup(title = if (t.zh) "工作目录" else "Workspace") {
            NavRow(
                title = if (treeUri != null) (if (t.zh) "已设置目录" else "Directory set") else (if (t.zh) "选择工作目录" else "Choose directory"),
                subtitle = if (t.zh) "放要分析的 APK / SO / DEX 文件的文件夹" else "Folder with APK/SO/DEX to analyze",
                icon = Icons.Filled.FolderOpen,
                onClick = { pickTree.launch(null) },
            )
        }

        // ── 三步引导 ──
        GlassGroup(title = if (t.zh) "三步开始用" else "Get started") {
            StepRow(0, if (t.zh) "点上面的大按钮,启动服务" else "Tap the big button to start")
            GroupDivider()
            StepRow(1, if (t.zh) "复制上面的连接地址" else "Copy the connection URL")
            GroupDivider()
            StepRow(2, if (t.zh) "粘贴到 AI 客户端的 MCP 设置里,就能让 AI 调用逆向工具了" else "Paste into your AI client's MCP settings")
        }

        // ── 进工具页 ──
        GlassGroup {
            NavRow(
                title = if (t.zh) "查看全部工具" else "All tools",
                subtitle = if (t.zh) "反编译 / SO 分析 / APK 解析 / 更多" else "Decompile / SO / APK / more",
                icon = Icons.Filled.CheckCircle,
                onClick = onOpenTools,
            )
        }
    }
}

@Composable
private fun BigPowerCard(zh: Boolean, running: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(if (running) 1f else 0.98f, tween(220), label = "power-scale")
    val accent = MaterialTheme.colorScheme.primary
    val ringColor = if (running) accent else MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    if (running) listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.06f))
                    else listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface),
                ),
            )
            .clickable(onClick = onToggle)
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (running) accent else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                null,
                tint = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            if (running) (if (zh) "服务运行中" else "Running") else (if (zh) "已停止 · 点击启动" else "Stopped · tap to start"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (running) accent else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (running) (if (zh) "AI 现在可以调用逆向工具了" else "AI can call tools now")
            else (if (zh) "启动后 AI 才能连上" else "Start to let AI connect"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepRow(index: Int, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IndexedBadge(index)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
