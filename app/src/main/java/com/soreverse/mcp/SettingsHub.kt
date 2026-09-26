package com.soreverse.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.GitHubRelease
import com.soreverse.mcp.core.GitHubUpdateManager
import com.soreverse.mcp.core.SettingsStore

private fun settingsTitle(t: UiText, dest: SettingsDest): String = when (dest) {
    SettingsDest.Root -> t.settings
    SettingsDest.ServiceConfig -> if (t.zh) "服务配置" else "Service Configuration"
    SettingsDest.Appearance -> if (t.zh) "外观与语言" else "Appearance"
    SettingsDest.KeepAlive -> t.keepAlive
    SettingsDest.Limits -> if (t.zh) "返回数量" else "Result Limits"
    SettingsDest.Export -> if (t.zh) "导出" else "Export"
    SettingsDest.Audit -> if (t.zh) "编辑校验与审计" else "Edit & Audit"
    SettingsDest.Blutter -> "Blutter"
    SettingsDest.Tunnel -> if (t.zh) "隧道" else "Tunnel"
    SettingsDest.ApkBridge -> if (t.zh) "MCP 桥接" else "MCP Bridge"
    SettingsDest.AiDeep -> if (t.zh) "AI 深度分析" else "AI Deep Analysis"
    SettingsDest.Updates -> if (t.zh) "版本更新" else "Software Update"
    SettingsDest.Probe -> t.externalProbe
    SettingsDest.ToolStats -> if (t.zh) "工具调用统计" else "Tool Call Stats"
    SettingsDest.TunnelStats -> if (t.zh) "隧道稳定性" else "Tunnel Stability"
    SettingsDest.Credits -> if (t.zh) "开源致谢" else "Credits"
    SettingsDest.Disclaimer -> t.disclaimer
    SettingsDest.About -> t.about
    SettingsDest.BackupRestore -> t.backupRestore
    SettingsDest.ApkSign -> if (t.zh) "APK 签名设置" else "APK Signing"
    SettingsDest.TempWorkspace -> if (t.zh) "临时工作区" else "Temp Workspaces"
    SettingsDest.Workspace -> if (t.zh) "工作区" else "Workspace"
    SettingsDest.Permissions -> if (t.zh) "权限管理" else "Permissions"
    SettingsDest.LogcatViewer -> if (t.zh) "Logcat 查看器" else "Logcat Viewer"
    SettingsDest.Edbg -> "eDBG"
    SettingsDest.Rizin -> "Rizin"
    SettingsDest.Capture -> if (t.zh) "抓包" else "Capture"
    SettingsDest.Linux -> if (t.zh) "Linux 环境" else "Linux Environment"
    SettingsDest.Terminal -> if (t.zh) "终端执行" else "Terminal"
    SettingsDest.Sandbox -> if (t.zh) "动态沙箱" else "Sandbox"
    SettingsDest.Python -> if (t.zh) "编辑器" else "Editor"
    SettingsDest.Git -> if (t.zh) "Git 仓库" else "Git Repository"
    SettingsDest.Extensions -> if (t.zh) "扩展系统" else "Extensions"
    SettingsDest.DexExplorer -> if (t.zh) "DEX / APK 浏览器" else "DEX / APK Explorer"
    SettingsDest.ApkEdit -> if (t.zh) "APK Manifest 编辑" else "APK Manifest Editor"
    SettingsDest.Snapshots -> if (t.zh) "编辑快照 / 回滚" else "Edit Snapshots"
    SettingsDest.Workflow -> if (t.zh) "逆向工作流图" else "Reverse Workflow"
    SettingsDest.Help -> if (t.zh) "帮助" else "Help"
}

@Composable
internal fun SettingsHub(
    modifier: Modifier = Modifier,
    backProgress: Float,
    t: UiText,
    settings: SettingsStore,
    updateManager: GitHubUpdateManager,
    availableRelease: GitHubRelease?,
    onRelease: (GitHubRelease?) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    themeMode: String,
    onTheme: (String) -> Unit,
    accentColor: String,
    onAccent: (String) -> Unit,
    pureBlackDark: Boolean,
    onPureBlack: (Boolean) -> Unit,
    uiDensity: String,
    onDensity: (String) -> Unit,
    cornerStyle: String,
    onCorner: (String) -> Unit,
    motionMode: String,
    onMotion: (String) -> Unit,
    showAdvancedHome: Boolean,
    onShowAdvancedHome: (Boolean) -> Unit,
    highContrast: Boolean,
    onHighContrast: (Boolean) -> Unit,
    textScale: String,
    onTextScale: (String) -> Unit,
    predictiveBack: Boolean,
    onPredictiveBack: (Boolean) -> Unit,
    dest: SettingsDest,
    onDest: (SettingsDest) -> Unit,
    onLogs: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = t.settings,
                subtitle = if (t.zh) "服务 / 分析 / 编辑 / 开发 / 诊断（可搜索）" else "Service / analysis / dev / engine / diagnostics (searchable)",
                showBack = onHome != null,
                onBack = onHome,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LocalUiMetrics.current.pagePad)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var settingsQuery by remember { mutableStateOf("") }
                SettingsSearchField(t, settingsQuery) { settingsQuery = it }
                if (settingsQuery.isBlank()) {
                    SettingsGroup(if (t.zh) "服务与连接" else "Service & connection") {
                        SettingsNavRow(t, SettingsDest.ServiceConfig, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.ApkBridge, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Tunnel, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.AiDeep, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Probe, onDest)
                    }
                    SettingsGroup(if (t.zh) "逆向分析" else "Reverse analysis") {
                        SettingsNavRow(t, SettingsDest.Rizin, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Edbg, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.LogcatViewer, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Capture, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Sandbox, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Workflow, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Blutter, onDest)
                    }
                    SettingsGroup(if (t.zh) "编辑与产物" else "Editing & artifacts") {
                        SettingsNavRow(t, SettingsDest.ApkEdit, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.DexExplorer, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.ApkSign, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Snapshots, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Audit, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Export, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Limits, onDest)
                    }
                    SettingsGroup(if (t.zh) "开发环境" else "Dev environment") {
                        SettingsNavRow(t, SettingsDest.Linux, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Terminal, onDest); GroupDivider()
                        NavRow(settingsTitle(t, SettingsDest.Python), null, settingsIcon(SettingsDest.Python), onClick = onOpenEditor); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Git, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Workspace, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.TempWorkspace, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Extensions, onDest)
                    }
                    SettingsGroup(if (t.zh) "外观与保活" else "Appearance & keep-alive") {
                        SettingsNavRow(t, SettingsDest.Appearance, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.KeepAlive, onDest)
                    }
                    SettingsGroup(if (t.zh) "诊断与关于" else "Diagnostics & about") {
                        SettingsNavRow(t, SettingsDest.ToolStats, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.TunnelStats, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Permissions, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Updates, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.BackupRestore, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Help, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Credits, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.Disclaimer, onDest); GroupDivider()
                        SettingsNavRow(t, SettingsDest.About, onDest)
                    }
                } else {
                    SettingsSearchResults(t, settingsQuery, onDest)
                }
                // License footer
                Text(
                    "${com.soreverse.mcp.core.Provenance.PROJECT} · ${com.soreverse.mcp.core.Provenance.LICENSE}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).align(Alignment.CenterHorizontally),
                )
            }
        }
        if (dest != SettingsDest.Root) {
    Surface(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * backProgress
            alpha = 1f - 0.12f * backProgress
        },
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = settingsTitle(t, dest),
            showBack = true,
            onBack = onBack,
        )
        Box(Modifier.fillMaxSize()) {
        when (dest) {
            SettingsDest.ServiceConfig -> SettingsServiceConfigPage(t, settings)
            SettingsDest.Appearance -> SettingsAppearancePage(t, language, onLanguage, themeMode, onTheme, accentColor, onAccent, pureBlackDark, onPureBlack, uiDensity, onDensity, cornerStyle, onCorner, motionMode, onMotion, showAdvancedHome, onShowAdvancedHome, highContrast, onHighContrast, textScale, onTextScale, predictiveBack, onPredictiveBack)
            SettingsDest.KeepAlive -> SettingsKeepAlivePage(t, settings)
            SettingsDest.Limits -> SettingsLimitsPage(t, settings)
            SettingsDest.Export -> SettingsExportPage(t, settings)
            SettingsDest.Audit -> SettingsAuditPage(t, settings)
            SettingsDest.Blutter -> SettingsBlutterPage(t)
            SettingsDest.Tunnel -> SettingsTunnelPage(t, settings)
            SettingsDest.ApkBridge -> SettingsApkBridgePage(t, settings)
            SettingsDest.AiDeep -> SettingsAiDeepPage(t, settings)
            SettingsDest.Updates -> SettingsUpdatesPage(t, settings, updateManager, availableRelease, onRelease)
            SettingsDest.Probe -> SettingsProbePage(t, settings)
            SettingsDest.ApkSign -> SettingsApkSignPage(t, settings)
            SettingsDest.TempWorkspace -> SettingsTempWorkspacePage(t, settings)
            SettingsDest.Workspace -> SettingsWorkspacePage(t, settings, { onDest(SettingsDest.ServiceConfig) }, { onDest(SettingsDest.TempWorkspace) })
            SettingsDest.Permissions -> SettingsPermissionsPage(t)
            SettingsDest.LogcatViewer -> LogcatViewerPage(t)
            SettingsDest.Edbg -> EdbgPage(t)
            SettingsDest.DexExplorer -> SettingsDexExplorerPage(t)
            SettingsDest.ApkEdit -> SettingsApkEditPage(t)
            SettingsDest.Snapshots -> SettingsSnapshotsPage(t)
            SettingsDest.Workflow -> SettingsWorkflowPage(t, onDest)
            SettingsDest.Rizin -> RizinPage(t)
            SettingsDest.Capture -> CapturePage(t)
            SettingsDest.Linux -> SettingsLinuxPage(t)
            SettingsDest.Terminal -> SettingsTerminalPage(t)
            SettingsDest.Git -> SettingsGitPage(t)
            SettingsDest.Extensions -> SettingsExtensionsPage(t, onDest)
            SettingsDest.Help -> SettingsHelpPage(t)
            SettingsDest.Sandbox -> SettingsSandboxPage(t)
            // 编辑器已是顶层独立页面：子页路由到此处时直接切到 Editor tab（覆盖扩展页 / 工作流页的跳转）
            SettingsDest.Python -> LaunchedEffect(Unit) { onOpenEditor() }
            SettingsDest.BackupRestore -> SettingsBackupRestorePage(t, settings)
            SettingsDest.ToolStats -> PageScroll { GlassGroup { Column(Modifier.padding(12.dp)) { ToolStatsSection(t, settings) } } }
            SettingsDest.TunnelStats -> PageScroll { GlassGroup { Column(Modifier.padding(12.dp)) { TunnelStatsSection(t) } } }
            SettingsDest.Credits -> SettingsCreditsPage(t)
            SettingsDest.Disclaimer -> PageScroll {
                GlassGroup {
                    Text(t.disclaimerBody, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
                GlassGroup(title = if (t.zh) "开源许可" else "License") {
                    Text(
                        if (t.zh) {
                            "塔菲逆核是 GPL-3.0-only 自由软件。任何再分发（含修改、改名、二次打包版本）必须保留本版权与许可声明、继续以 GPL-3.0-only 授权，并向每一位接收者提供完整对应源代码。"
                        } else {
                            "TaffyNiHe is GPL-3.0-only free software. Any redistribution must retain this notice, remain under GPL-3.0-only, and provide complete corresponding source code."
                        },
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsDest.About -> {
                val aboutContext = LocalContext.current
                PageScroll {
                    GlassGroup {
                        Text(t.aboutBody, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.padding(14.dp)) {
                            PrimaryActionButton(t.joinQqGroup, { joinQqGroup(aboutContext, t.zh) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    GlassGroup(title = if (t.zh) "开源许可与来源" else "License & origin") {
                        NavRow(
                            "玄星逆核·GPL-3.0",
                            "mt论坛 最强AI选手",
                            Icons.Default.Info,
                            onClick = { copy(aboutContext, "https://bbs.binmt.cc/home.php?mod=space&uid=124277&do=profile&view=me&from=space", t.copied) },
                        )
                        GroupDivider()
                        NavRow(
                            "${com.soreverse.mcp.core.Provenance.PROJECT} · ${com.soreverse.mcp.core.Provenance.LICENSE}",
                            com.soreverse.mcp.core.Provenance.COPYRIGHT,
                            Icons.Default.Info,
                            onClick = { copy(aboutContext, com.soreverse.mcp.core.Provenance.LICENSE, t.copied) },
                        )
                        GroupDivider()
                        NavRow(
                            if (t.zh) "上游开源仓库（唯一官方来源）" else "Upstream source (only official origin)",
                            com.soreverse.mcp.core.Provenance.UPSTREAM,
                            Icons.Default.Info,
                            onClick = { copy(aboutContext, com.soreverse.mcp.core.Provenance.UPSTREAM, t.copied) },
                        )
                        GroupDivider()
                        Text(
                            if (t.zh) {
                                "本软件为 GPL-3.0 自由软件，受《中华人民共和国著作权法》《计算机软件保护条例》保护。任何再分发（含修改、改名、二次打包版本）必须：保留本版权与许可声明、继续以 GPL-3.0 授权、向每一位接收者提供完整对应源代码。\n\n" +
                                    "闭源分发、抹除署名、改名冒充原创即构成侵权。依据《著作权法》第五十二条、第五十三条，权利人可要求停止侵害、消除影响、赔礼道歉并赔偿损失；情节严重的可按《著作权法》第五十四条主张惩罚性赔偿。GPL 作为授权合同在中国司法实践中已被确认有效并可强制执行（参见北京高院\u201c数字天堂诉柚子科技\u201d、\u201c罗盒诉风灵\u201d等 GPL/开源协议案）。\n\n" +
                                    "侵权者将被记录（含运行时溯源指纹）并可能面临平台下架、公开通报及民事索赔。"
                            } else {
                                com.soreverse.mcp.core.Provenance.REDISTRIBUTION_NOTICE
                            },
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SettingsDest.Root -> Unit
        }
        }
    }
    }
    }
}
}

@Composable
private fun SettingsSearchField(t: UiText, query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(AppShape.md),
        placeholder = { Text(if (t.zh) "搜索设置…" else "Search settings…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun SettingsSearchResults(t: UiText, query: String, onDest: (SettingsDest) -> Unit) {
    val hits = remember(query, t.zh) {
        SettingsDest.values()
            .filter { it != SettingsDest.Root }
            .filter { settingsTitle(t, it).contains(query.trim(), ignoreCase = true) }
    }
    if (hits.isEmpty()) {
        Text(
            if (t.zh) "没有匹配的设置项" else "No matching settings",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        SurfacePanel {
            hits.forEachIndexed { i, dest ->
                if (i > 0) GroupDivider()
                NavRow(settingsTitle(t, dest), null, settingsIcon(dest), onClick = { onDest(dest) })
            }
        }
    }
}

@Composable
private fun SettingsNavRow(t: UiText, dest: SettingsDest, onDest: (SettingsDest) -> Unit) {
    NavRow(settingsTitle(t, dest), null, settingsIcon(dest), onClick = { onDest(dest) })
}

/** 单列分组卡片：组标题 + 一张圆角面板（行之间用 GroupDivider 分隔）。 */
@Composable
private fun SettingsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
        val shape = RoundedCornerShape(AppShape.lg)
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)), shape),
            content = rows,
        )
    }
}

/** 设置项图标：首页入口 / 设置分组 / 搜索结果共用一个来源，保证视觉一致。 */
private fun settingsIcon(dest: SettingsDest): ImageVector = when (dest) {
    SettingsDest.ServiceConfig -> Icons.Default.Settings
    SettingsDest.ApkBridge -> Icons.Default.Link
    SettingsDest.Tunnel -> Icons.Default.Cloud
    SettingsDest.AiDeep -> Icons.Default.Memory
    SettingsDest.Probe -> Icons.Default.Search
    SettingsDest.Rizin -> Icons.Default.Build
    SettingsDest.Edbg -> Icons.Default.BugReport
    SettingsDest.LogcatViewer -> Icons.Default.Description
    SettingsDest.Capture -> Icons.Default.Cloud
    SettingsDest.Sandbox -> Icons.Default.PlayArrow
    SettingsDest.Workflow -> Icons.Default.AccountTree
    SettingsDest.Blutter -> Icons.Default.Memory
    SettingsDest.ApkEdit -> Icons.Default.Create
    SettingsDest.DexExplorer -> Icons.Default.Code
    SettingsDest.ApkSign -> Icons.Default.Security
    SettingsDest.Snapshots -> Icons.Default.Restore
    SettingsDest.Audit -> Icons.Default.Tune
    SettingsDest.Export -> Icons.Default.Storage
    SettingsDest.Limits -> Icons.Default.Analytics
    SettingsDest.Linux -> Icons.Default.Terminal
    SettingsDest.Terminal -> Icons.Default.Terminal
    SettingsDest.Python -> Icons.Default.Code
    SettingsDest.Git -> Icons.Default.AccountTree
    SettingsDest.Workspace -> Icons.Default.FolderOpen
    SettingsDest.TempWorkspace -> Icons.Default.FolderOpen
    SettingsDest.Extensions -> Icons.Default.Extension
    SettingsDest.Appearance -> Icons.Default.Tune
    SettingsDest.KeepAlive -> Icons.Default.PowerSettingsNew
    SettingsDest.ToolStats -> Icons.Default.Analytics
    SettingsDest.TunnelStats -> Icons.Default.Cloud
    SettingsDest.Permissions -> Icons.Default.Security
    SettingsDest.Updates -> Icons.Default.Restore
    SettingsDest.BackupRestore -> Icons.Default.Restore
    SettingsDest.Help -> Icons.Default.Info
    SettingsDest.Credits -> Icons.Default.Info
    SettingsDest.Disclaimer -> Icons.Default.Info
    SettingsDest.About -> Icons.Default.Info
    SettingsDest.Root -> Icons.Default.Settings
}
