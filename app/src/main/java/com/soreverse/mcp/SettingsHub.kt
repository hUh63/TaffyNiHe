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

internal fun settingsTitle(t: UiText, dest: SettingsDest): String = when (dest) {
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
    SettingsDest.ToolStats -> if (t.zh) "运行统计" else "Runtime Stats"
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
    SettingsDest.NativeExec -> if (t.zh) "本地可执行" else "Native Executables"
    SettingsDest.Sandbox -> if (t.zh) "动态沙箱" else "Sandbox"
    SettingsDest.Python -> if (t.zh) "编辑器" else "Editor"
    SettingsDest.Git -> if (t.zh) "Git 仓库" else "Git Repository"
    SettingsDest.Extensions -> if (t.zh) "扩展系统" else "Extensions"
    SettingsDest.DexExplorer -> if (t.zh) "DEX / APK 浏览器" else "DEX / APK Explorer"
    SettingsDest.ApkEdit -> if (t.zh) "APK Manifest 编辑" else "APK Manifest Editor"
    SettingsDest.Snapshots -> if (t.zh) "编辑快照 / 回滚" else "Edit Snapshots"
    SettingsDest.Workflow -> if (t.zh) "逆向工作流图" else "Reverse Workflow"
    SettingsDest.Help -> if (t.zh) "帮助" else "Help"
    SettingsDest.Search -> if (t.zh) "搜索设置" else "Search settings"
    SettingsDest.DevEnv -> if (t.zh) "开发环境" else "Dev environment"
    SettingsDest.Reverse -> if (t.zh) "逆向分析" else "Reverse analysis"
    SettingsDest.SelfCheck -> if (t.zh) "自检" else "Self check"
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
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
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
        SettingsRootPage(
            t = t,
            settings = settings,
            onDest = onDest,
            onSearch = { onDest(SettingsDest.Search) },
            onBack = onHome,
        )
        if (dest != SettingsDest.Root) {
    Surface(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * backProgress
            alpha = 1f - 0.12f * backProgress
        },
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(Modifier.fillMaxSize()) {
        // 每个二级页自己拥有 Scaffold + 折叠大标题（太墟架构）；hub 只负责路由与转场。
        Box(Modifier.fillMaxSize()) {
        when (dest) {
            SettingsDest.ServiceConfig -> SettingsServiceConfigPage(t, settings, onBack)
            SettingsDest.Appearance -> SettingsAppearancePage(t, language, onLanguage, themeMode, onTheme, accentColor, onAccent, pureBlackDark, onPureBlack, uiDensity, onDensity, cornerStyle, onCorner, motionMode, onMotion, showAdvancedHome, onShowAdvancedHome, highContrast, onHighContrast, dynamicColor, onDynamicColor, textScale, onTextScale, predictiveBack, onPredictiveBack, onBack)
            SettingsDest.KeepAlive -> SettingsKeepAlivePage(t, settings, onBack)
            SettingsDest.Limits -> SettingsLimitsPage(t, settings, onBack)
            SettingsDest.Export -> SettingsExportPage(t, settings, onBack)
            SettingsDest.Audit -> SettingsAuditPage(t, settings, onBack)
            SettingsDest.Blutter -> SettingsBlutterPage(t, onBack)
            SettingsDest.Tunnel -> SettingsTunnelPage(t, settings, onBack)
            SettingsDest.ApkBridge -> SettingsApkBridgePage(t, settings, onBack)
            SettingsDest.AiDeep -> SettingsAiDeepPage(t, settings, onBack)
            SettingsDest.Updates -> SettingsUpdatesPage(t, settings, updateManager, availableRelease, onRelease, onBack)
            SettingsDest.Probe -> SettingsProbePage(t, settings, onBack)
            SettingsDest.ApkSign -> SettingsApkSignPage(t, settings, onBack)
            SettingsDest.TempWorkspace -> SettingsTempWorkspacePage(t, settings, onBack)
            SettingsDest.Workspace -> SettingsWorkspacePage(t, settings, { onDest(SettingsDest.ServiceConfig) }, { onDest(SettingsDest.TempWorkspace) }, onBack)
            SettingsDest.Permissions -> SettingsPermissionsPage(t, onBack)
            SettingsDest.LogcatViewer -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { LogcatViewerPage(t) }
            SettingsDest.Edbg -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { EdbgPage(t) }
            SettingsDest.DexExplorer -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsDexExplorerPage(t) }
            SettingsDest.ApkEdit -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsApkEditPage(t) }
            SettingsDest.Snapshots -> SettingsSnapshotsPage(t, onBack)
            SettingsDest.Workflow -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsWorkflowPage(t, onDest) }
            SettingsDest.Rizin -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { RizinPage(t) }
            SettingsDest.Capture -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { CapturePage(t) }
            SettingsDest.Linux -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsLinuxPage(t) }
            SettingsDest.Terminal -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsTerminalPage(t) }
            SettingsDest.NativeExec -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsNativeExecPage(t) }
            SettingsDest.Git -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsGitPage(t) }
            SettingsDest.Extensions -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsExtensionsPage(t, onDest) }
            SettingsDest.Help -> SettingsHelpPage(t, onBack)
            SettingsDest.Sandbox -> ToolPageScaffold(settingsTitle(t, dest), onBack = onBack) { SettingsSandboxPage(t) }
            // 编辑器已是顶层独立页面：子页路由到此处时直接切到 Editor tab（覆盖扩展页 / 工作流页的跳转）
            SettingsDest.Python -> LaunchedEffect(Unit) { onOpenEditor() }
            SettingsDest.Search -> SettingsSearchPage(t, onDest, onBack)
            SettingsDest.SelfCheck -> SettingsSelfCheckPage(t, onBack)
            SettingsDest.DevEnv -> SettingsDevEnvPage(t, onDest, onOpenEditor, onBack)
            SettingsDest.Reverse -> SettingsReversePage(t, onDest, onBack)
            SettingsDest.BackupRestore -> SettingsBackupRestorePage(t, settings, onBack)
            SettingsDest.ToolStats -> SettingsScaffold(
                title = if (t.zh) "运行统计" else "Runtime stats",
                onBack = onBack,
            ) {
                item("content") {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                // 合并强相关入口：工具调用统计 + 隧道稳定性 同属「运行时统计」，合并到同一页。
                GlassGroup { Column(Modifier.padding(12.dp)) { ToolStatsSection(t, settings) } }
                GlassGroup { Column(Modifier.padding(12.dp)) { TunnelStatsSection(t) } }
            
                    }
                }
            }
            SettingsDest.TunnelStats -> SettingsScaffold(
                title = if (t.zh) "隧道稳定性" else "Tunnel stability",
                onBack = onBack,
            ) {
                item("content") {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { GlassGroup { Column(Modifier.padding(12.dp)) { TunnelStatsSection(t) } } 
                    }
                }
            }
            SettingsDest.Credits -> SettingsCreditsPage(t, onBack)
            SettingsDest.Disclaimer -> SettingsScaffold(
                title = t.disclaimer,
                onBack = onBack,
            ) {
                item("content") {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                }
            }
            SettingsDest.About -> {
                val aboutContext = LocalContext.current
                SettingsScaffold(
                    title = t.about,
                    onBack = onBack,
                ) {
                    item("content") {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
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

/** 设置项图标：首页入口 / 设置分组 / 搜索结果共用一个来源，保证视觉一致。 */
internal fun settingsIcon(dest: SettingsDest): ImageVector = when (dest) {
    SettingsDest.Search -> Icons.Default.Search
    SettingsDest.DevEnv -> Icons.Default.Build
    SettingsDest.Reverse -> Icons.Default.BugReport
    SettingsDest.SelfCheck -> Icons.Default.Security
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
    SettingsDest.NativeExec -> Icons.Default.PlayArrow
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

