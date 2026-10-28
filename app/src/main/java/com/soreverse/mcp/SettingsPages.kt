package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Memory
import org.json.JSONObject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.SettingsStore
import kotlinx.coroutines.launch
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.core.safeLaunch

/**
 * 设置根页 / 设置全局搜索 / 开发环境二级页（布局对齐太墟 taixu 个人化设置）。
 *
 * 三者的共同点：都只有「分组卡片 + 行摘要」，没有自绘胶囊按钮；点击行为一律是
 * 整行可点，右侧 chevron 只作方向提示（不是独立按钮）。
 */

/** 设置搜索索引的一格：标题/说明/关键词/归属分组/去处。 */
internal data class SettingsEntry(
    val dest: SettingsDest,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val group: String,
)

/** 全部设置项的扁平索引（搜索与「热门」都从这里取，避免两处漂移）。 */
internal fun settingsIndex(zh: Boolean): List<SettingsEntry> {
    fun e(dest: SettingsDest, title: String, subtitle: String, keywords: String, group: String) =
        SettingsEntry(dest, title, subtitle, keywords, group)
    return listOf(
        // 服务与连接
        e(SettingsDest.ServiceConfig, if (zh) "MCP 服务配置" else "MCP service", if (zh) "端口、绑定地址、访问令牌、工具开关" else "Port, bind host, token, tool toggle", "mcp port host token 端口 绑定 令牌 服务", if (zh) "服务与连接" else "Service"),
        e(SettingsDest.ApkBridge, if (zh) "MCP 桥接" else "MCP bridge", if (zh) "把工作区与分析能力暴露给外部客户端" else "Expose workspace and analysis to clients", "bridge 桥接 客户端 apk mcp", if (zh) "服务与连接" else "Service"),
        e(SettingsDest.Tunnel, if (zh) "内网穿透隧道" else "Tunnel", if (zh) "Cloudflare / bore 公网访问" else "Cloudflare / bore public access", "tunnel bore cloudflare 隧道 公网 穿透", if (zh) "服务与连接" else "Service"),
        e(SettingsDest.Probe, if (zh) "外部探针" else "External probe", if (zh) "连通性自检地址" else "Connectivity self-check URL", "probe url 探针 连通 自检", if (zh) "服务与连接" else "Service"),
        e(SettingsDest.AiDeep, if (zh) "AI 深度分析" else "AI deep analysis", if (zh) "大模型端点、密钥、模型与提示词" else "Endpoint, key, model, prompt", "ai llm openai 大模型 密钥 提示词 深度", if (zh) "服务与连接" else "Service"),
        // 逆向分析
        e(SettingsDest.Reverse, if (zh) "逆向分析（合并页）" else "Reverse analysis (combined)", if (zh) "反汇编、调试、抓包、沙箱、编排、Flutter 一页切换" else "Disasm, debug, capture, sandbox, workflow, Flutter in one page", "逆向 reverse 分析 反汇编 调试 抓包 沙箱 工作流 flutter 合并", if (zh) "逆向分析" else "Reverse"),
        e(SettingsDest.Rizin, "Rizin", if (zh) "反汇编、函数与图分析引擎" else "Disassembly and graph engine", "rizin 反汇编 引擎 图 cfg", if (zh) "逆向分析" else "Reverse"),
        e(SettingsDest.Edbg, "eDBG", if (zh) "ELF 调试器与断点" else "ELF debugger and breakpoints", "edbg 调试 debugger 断点 elf", if (zh) "逆向分析" else "Reverse"),
        e(SettingsDest.Capture, if (zh) "抓包" else "Capture", if (zh) "HTTP(S) 代理与请求记录" else "HTTP(S) proxy and capture", "capture proxy http 抓包 代理 证书", if (zh) "逆向分析" else "Reverse"),
        e(SettingsDest.Sandbox, if (zh) "动态沙箱" else "Sandbox", if (zh) "Unidbg / 模拟执行环境" else "Unidbg emulation", "unidbg emulation 沙箱 模拟 执行", if (zh) "逆向分析" else "Reverse"),
        e(SettingsDest.Workflow, if (zh) "逆向工作流图" else "Reverse workflow", if (zh) "把分析步骤串成可复用流程" else "Chain analysis steps", "workflow 工作流 流程 编排", if (zh) "逆向分析" else "Reverse"),
        e(SettingsDest.Blutter, "Blutter", if (zh) "Flutter / Dart AOT 快照分析" else "Flutter / Dart AOT analysis", "blutter flutter dart aot snapshot", if (zh) "逆向分析" else "Reverse"),
        // 编辑与产物
        e(SettingsDest.ApkEdit, if (zh) "APK Manifest 编辑" else "APK manifest editor", if (zh) "包名、权限、组件与调试位" else "Package, permissions, components", "apk manifest 包名 权限 组件 debuggable", if (zh) "编辑与产物" else "Editing"),
        e(SettingsDest.DexExplorer, if (zh) "DEX / APK 浏览器" else "DEX / APK explorer", if (zh) "类、方法与字符串检索" else "Classes, methods, strings", "dex apk 类 方法 字符串 浏览", if (zh) "编辑与产物" else "Editing"),
        e(SettingsDest.ApkSign, if (zh) "APK 签名" else "APK signing", if (zh) "v1/v2/v3 签名与密钥来源" else "v1/v2/v3 and key source", "apk sign 签名 keystore v1 v2 v3 密钥", if (zh) "编辑与产物" else "Editing"),
        e(SettingsDest.Snapshots, if (zh) "编辑快照 / 回滚" else "Snapshots", if (zh) "每次改动前的文件快照与回滚" else "Snapshot and rollback", "snapshot rollback 快照 回滚 撤销", if (zh) "编辑与产物" else "Editing"),
        e(SettingsDest.Audit, if (zh) "校验与审计" else "Audit", if (zh) "编辑前后校验、审计日志" else "Pre/post validation, audit log", "audit 审计 校验 日志 严格", if (zh) "编辑与产物" else "Editing"),
        e(SettingsDest.Export, if (zh) "导出" else "Export", if (zh) "分析结果导出目录与格式" else "Export directory and format", "export 导出 目录 格式", if (zh) "编辑与产物" else "Editing"),
        e(SettingsDest.Limits, if (zh) "返回数量上限" else "Result limits", if (zh) "列表、反汇编、字符串的默认条数" else "Default list/disasm/string caps", "limit 上限 条数 反汇编 字符串", if (zh) "编辑与产物" else "Editing"),
        // 开发环境
        e(SettingsDest.Linux, if (zh) "Linux 环境" else "Linux environment", if (zh) "发行版、根文件系统与启动参数" else "Distro, rootfs, boot args", "linux rootfs proot 发行版 根文件系统", if (zh) "开发环境" else "Dev"),
        e(SettingsDest.Terminal, if (zh) "终端执行" else "Terminal", if (zh) "软键、字号与配色" else "Keys, font size, colors", "terminal 终端 命令行 shell 字号", if (zh) "开发环境" else "Dev"),
        e(SettingsDest.NativeExec, if (zh) "本地可执行" else "Native executables", if (zh) "在私有目录直接运行 PIE 可执行文件" else "Run PIE binaries from app dir", "exec native pie 可执行 二进制 运行", if (zh) "开发环境" else "Dev"),
        e(SettingsDest.Python, if (zh) "编辑器" else "Editor", if (zh) "多标签代码编辑、行号、搜索" else "Multi-tab editor", "editor 编辑器 代码 行号 搜索", if (zh) "开发环境" else "Dev"),
        e(SettingsDest.Git, if (zh) "Git 仓库" else "Git repository", if (zh) "克隆、提交、分支与推送" else "Clone, commit, branch, push", "git 仓库 提交 分支 推送 clone", if (zh) "开发环境" else "Dev"),
        e(SettingsDest.Extensions, if (zh) "扩展系统" else "Extensions", if (zh) "插件安装、权限与运行审计" else "Plugins, permissions, audit", "extension plugin 扩展 插件 权限 py", if (zh) "开发环境" else "Dev"),
        // 权限与应用
        e(SettingsDest.Permissions, if (zh) "权限管理" else "Permissions", if (zh) "存储、Shizuku、Root 能力状态" else "Storage, Shizuku, root status", "permission shizuku root 权限 存储", if (zh) "权限与应用" else "App"),
        e(SettingsDest.KeepAlive, if (zh) "后台保活" else "Keep alive", if (zh) "前台服务、唤醒锁与电池白名单" else "Foreground service, wake lock", "keepalive 保活 前台服务 唤醒锁 电池", if (zh) "权限与应用" else "App"),
        e(SettingsDest.Appearance, if (zh) "外观与语言" else "Appearance", if (zh) "主题、强调色、圆角、字号、动效" else "Theme, accent, corners, text scale", "appearance theme 主题 语言 强调色 圆角 字号 动效", if (zh) "权限与应用" else "App"),
        e(SettingsDest.Workspace, if (zh) "工作区" else "Workspace", if (zh) "默认目录、SO/APK 扫描范围" else "Default dir and scan scope", "workspace 工作区 目录 扫描", if (zh) "权限与应用" else "App"),
        e(SettingsDest.TempWorkspace, if (zh) "临时工作区" else "Temp workspaces", if (zh) "缓存上限与清理" else "Cache limit and cleanup", "temp cache 临时 缓存 清理", if (zh) "权限与应用" else "App"),
        e(SettingsDest.BackupRestore, if (zh) "备份与恢复" else "Backup & restore", if (zh) "设置快照导入导出（密钥脱敏）" else "Settings snapshot import/export", "backup restore 备份 恢复 导入 导出", if (zh) "权限与应用" else "App"),
        // 诊断与关于
        e(SettingsDest.SelfCheck, if (zh) "自检" else "Self check", if (zh) "签名完整性结论与运行环境探针（只报告）" else "Integrity verdict and runtime probes", "selfcheck 自检 完整性 签名 威胁 调试器 hook", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.ToolStats, if (zh) "运行统计" else "Runtime stats", if (zh) "工具调用次数、耗时与失败率" else "Tool calls, latency, failures", "stats 统计 调用 耗时 失败", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.LogcatViewer, "Logcat", if (zh) "日志抓取与过滤" else "Log capture and filter", "logcat 日志 log 过滤 抓取", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.Updates, if (zh) "版本更新" else "Update", if (zh) "检查更新、通道与校验和" else "Update check, channel, checksum", "update 更新 版本 通道 校验", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.Help, if (zh) "帮助" else "Help", if (zh) "工具说明与常见问题" else "Tool docs and FAQ", "help 帮助 说明 文档 常见问题", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.Credits, if (zh) "开源致谢" else "Credits", if (zh) "上游与本项目依赖声明" else "Upstream and dependency notices", "credits 致谢 开源 license", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.Disclaimer, if (zh) "免责声明" else "Disclaimer", if (zh) "仅限授权范围使用" else "Authorised use only", "disclaimer 免责 声明 授权", if (zh) "诊断与关于" else "Diagnostics"),
        e(SettingsDest.About, if (zh) "关于" else "About", if (zh) "版本、构建与项目信息" else "Version and build info", "about 关于 版本 构建", if (zh) "诊断与关于" else "Diagnostics"),
    )
}

/** 搜索框胶囊（根页第一屏的入口，点击进入独立搜索页）。 */
@Composable
private fun SettingsSearchPill(t: UiText, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (t.zh) "搜索设置与功能" else "Search settings",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 根页顶部的运行态摘要：服务端口 / 工具数 / 鉴权。 */
/**
 * 纯展示状态标签 —— 不接点击。
 *
 * 之前这张卡用 AssistChip(onClick = {}) 承载「端口 / 工具数 / 鉴权」，用户看得见水波纹、
 * 点下去什么都不发生；信息型内容不该长成按钮。warning 用于需要被看见的负向状态（鉴权关闭）。
 */
@Composable
private fun StatusPill(text: String, warning: Boolean = false) {
    val container = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SettingsStatusCard(settings: SettingsStore, t: UiText) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (t.zh) "塔菲逆核 · 运行态" else "TaffyNiHe · runtime",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (t.zh) "MCP 服务、工具清单与安全开关的当前状态" else "MCP service, tooling and safety switches",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 这三条是纯信息，原来用 AssistChip(onClick = {}) 是「有水波纹、点了什么都不发生」的假按钮。
                StatusPill(if (t.zh) "端口 ${settings.port}" else "Port ${settings.port}")
                StatusPill(if (t.zh) "${ToolCatalog.ALL.size} 个工具" else "${ToolCatalog.ALL.size} tools")
                StatusPill(
                    if (settings.authEnabled) (if (t.zh) "鉴权已开启" else "Auth on") else (if (t.zh) "鉴权关闭" else "Auth off"),
                    warning = !settings.authEnabled,
                )
            }
        }
    }
}

/** 一条分类入口（标题走 settingsTitle，图标走 settingsIcon，值走调用方给的摘要）。 */
@Composable
private fun CategoryEntry(
    t: UiText,
    dest: SettingsDest,
    subtitle: String,
    value: String? = null,
    onDest: (SettingsDest) -> Unit,
) {
    SettingsCategoryRow(
        icon = settingsIcon(dest),
        title = settingsTitle(t, dest),
        subtitle = subtitle,
        value = value,
        onClick = { onDest(dest) },
    )
}

@Composable
private fun ItemEntry(
    t: UiText,
    dest: SettingsDest,
    subtitle: String,
    value: String? = null,
    onDest: (SettingsDest) -> Unit,
) {
    SettingsItemRow(
        icon = settingsIcon(dest),
        title = settingsTitle(t, dest),
        subtitle = subtitle,
        value = value,
        onClick = { onDest(dest) },
    )
}

/** 设置根页：搜索胶囊 + 运行态卡 + 六个分组（每组一张「拼接卡」）。 */
@Composable
internal fun SettingsRootPage(
    t: UiText,
    settings: SettingsStore,
    onDest: (SettingsDest) -> Unit,
    onSearch: () -> Unit,
    onBack: (() -> Unit)?,
) {
    SettingsScaffold(
        title = t.settings,
        onBack = onBack,
        statusText = if (t.zh) "服务 / 分析 / 编辑 / 开发 / 诊断" else "Service / analysis / editing / dev / diagnostics",
    ) {
        item("search") { SettingsSearchPill(t, onSearch) }
        item("status") { SettingsStatusCard(settings, t) }

        item("service") {
            SettingsSection(if (t.zh) "服务与连接" else "Service & connections") {
                PreferenceSurface(first = true, last = false) {
                    CategoryEntry(t, SettingsDest.ServiceConfig, if (t.zh) "端口、绑定地址、访问令牌与工具开关" else "Port, bind host, token, tool switches", ":${settings.port}", onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.ApkBridge, if (t.zh) "把工作区与分析能力暴露给外部客户端" else "Expose workspace and analysis to clients", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Tunnel, if (t.zh) "Cloudflare / bore 公网访问" else "Cloudflare / bore public access", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Probe, if (t.zh) "连通性自检地址" else "Connectivity self-check URL", null, onDest)
                }
                PreferenceSurface(first = false, last = true) {
                    CategoryEntry(t, SettingsDest.AiDeep, if (t.zh) "大模型端点、密钥与提示词" else "LLM endpoint, key, prompt", null, onDest)
                }
            }
        }

        item("reverse") {
            SettingsSection(if (t.zh) "逆向分析" else "Reverse engineering") {
                // 原来这里是 6 个箭头（Rizin / eDBG / 抓包 / 沙箱 / 工作流 / Blutter），点进去还要再点一次。
                // 现在 6 项在同一个页面的 6 个 pane 里直接切，根页只留一个入口。
                PreferenceSurface(first = true, last = true) {
                    CategoryEntry(t, SettingsDest.Reverse, if (t.zh) "反汇编 · 调试 · 抓包 · 沙箱 · 编排 · Flutter" else "Disasm · debug · capture · sandbox · workflow · Flutter", if (t.zh) "6 项" else "6 items", onDest)
                }
            }
        }

        item("editing") {
            SettingsSection(if (t.zh) "编辑与产物" else "Editing & artifacts") {
                PreferenceSurface(first = true, last = false) {
                    CategoryEntry(t, SettingsDest.ApkEdit, if (t.zh) "包名、权限、组件与调试位" else "Package, permissions, components", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.DexExplorer, if (t.zh) "类、方法与字符串检索" else "Classes, methods, strings", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.ApkSign, if (t.zh) "v1/v2/v3 签名与密钥来源" else "v1/v2/v3 and key source", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Snapshots, if (t.zh) "每次改动前的快照与回滚" else "Snapshots and rollback", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Audit, if (t.zh) "编辑前后校验与审计日志" else "Validation and audit log", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Export, if (t.zh) "导出目录与格式" else "Export directory and format", null, onDest)
                }
                PreferenceSurface(first = false, last = true) {
                    CategoryEntry(t, SettingsDest.Limits, if (t.zh) "列表、反汇编与字符串的默认条数" else "Default list/disasm/string caps", "${settings.defaultLimit}", onDest)
                }
            }
        }

        item("devenv") {
            SettingsSection(if (t.zh) "开发环境" else "Dev environment") {
                PreferenceSurface(first = true, last = true) {
                    SettingsCategoryRow(
                        icon = settingsIcon(SettingsDest.Linux),
                        title = if (t.zh) "开发环境" else "Dev environment",
                        subtitle = if (t.zh) "Linux 发行版 · 终端 · 本地可执行 · 编辑器 · Git · 扩展" else "Linux · terminal · native exec · editor · git · extensions",
                        value = if (t.zh) "6 项" else "6 items",
                        onClick = { onDest(SettingsDest.DevEnv) },
                    )
                }
            }
        }

        item("app") {
            SettingsSection(if (t.zh) "权限与应用" else "Permissions & app") {
                PreferenceSurface(first = true, last = false) {
                    CategoryEntry(t, SettingsDest.Permissions, if (t.zh) "存储、Shizuku、Root 能力状态" else "Storage, Shizuku, root status", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.KeepAlive, if (t.zh) "前台服务、唤醒锁与电池白名单" else "Foreground service, wake lock", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Appearance, if (t.zh) "主题、强调色、圆角、字号与动效" else "Theme, accent, corners, text scale", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Workspace, if (t.zh) "默认目录与 SO/APK 扫描范围" else "Default dir and scan scope", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.TempWorkspace, if (t.zh) "缓存上限与清理" else "Cache limit and cleanup", null, onDest)
                }
                PreferenceSurface(first = false, last = true) {
                    CategoryEntry(t, SettingsDest.BackupRestore, if (t.zh) "设置快照导入导出（密钥脱敏）" else "Settings snapshot import/export", null, onDest)
                }
            }
        }

        // 原来是「诊断与关于」一张 8 行卡：排障时要往下扫过 4 条法务/致谢条目，
        // 找东西全靠眼力。拆成两张卡——「诊断」是日常会用到的，「关于」是低频只读。
        item("diagnostics") {
            SettingsSection(if (t.zh) "诊断" else "Diagnostics") {
                PreferenceSurface(first = true, last = false) {
                    CategoryEntry(t, SettingsDest.SelfCheck, if (t.zh) "完整性结论与运行环境探针" else "Integrity verdict and runtime probes", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.ToolStats, if (t.zh) "工具调用次数、耗时与失败率" else "Tool calls, latency, failures", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.LogcatViewer, if (t.zh) "日志抓取与过滤" else "Log capture and filter", null, onDest)
                }
                PreferenceSurface(first = false, last = true) {
                    CategoryEntry(t, SettingsDest.Updates, if (t.zh) "检查更新、通道与校验和" else "Update check, channel, checksum", null, onDest)
                }
            }
        }

        item("about") {
            SettingsSection(if (t.zh) "关于" else "About") {
                PreferenceSurface(first = true, last = false) {
                    CategoryEntry(t, SettingsDest.Help, if (t.zh) "工具说明与常见问题" else "Tool docs and FAQ", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Credits, if (t.zh) "上游与本项目依赖声明" else "Upstream and dependency notices", null, onDest)
                }
                PreferenceSurface(first = false, last = false) {
                    CategoryEntry(t, SettingsDest.Disclaimer, if (t.zh) "仅限授权范围使用" else "Authorised use only", null, onDest)
                }
                PreferenceSurface(first = false, last = true) {
                    CategoryEntry(t, SettingsDest.About, if (t.zh) "版本、构建与项目信息" else "Version and build info", null, onDest)
                }
            }
        }

        item("footer") {
            Text(
                "${com.soreverse.mcp.core.Provenance.PROJECT} · ${com.soreverse.mcp.core.Provenance.LICENSE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/**
 * 逆向分析融合页 —— 根页「逆向分析」组原来 6 个箭头，每一项点进去才见到内容。
 *
 * 现在 Rizin / eDBG / 抓包 / 沙箱 / 工作流 / Blutter 在同一个页面的 6 个 pane 里切换，
 * 反汇编、挂断点、看抓包、跑模拟、编排流程都是就地完成；Blutter 的版本矩阵属于低频细节，
 * 给它一个详情入口而不是把两张表塞进页签。
 */
@Composable
internal fun SettingsReversePage(
    t: UiText,
    onDest: (SettingsDest) -> Unit,
    onBack: () -> Unit,
) {
    val zh = t.zh
    var pane by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(
        "Rizin",
        "eDBG",
        if (zh) "抓包" else "Capture",
        if (zh) "沙箱" else "Sandbox",
        if (zh) "工作流" else "Workflow",
        "Blutter",
    )
    ToolPageScaffold(
        title = if (zh) "逆向分析" else "Reverse engineering",
        subtitle = if (zh) "反汇编 · 调试 · 抓包 · 沙箱 · 编排 · Flutter"
        else "Disasm · debug · capture · sandbox · workflow · Flutter",
        onBack = onBack,
    ) {
        Column(Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = pane,
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.background,
                divider = {},
            ) {
                labels.forEachIndexed { i, label ->
                    Tab(
                        selected = pane == i,
                        onClick = { pane = i },
                        text = { Text(label, fontSize = 13.sp, maxLines = 1) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Box(Modifier.fillMaxSize()) {
                when (pane) {
                    0 -> RizinPage(t)
                    1 -> EdbgPage(t)
                    2 -> CapturePage(t)
                    3 -> SettingsSandboxPage(t)
                    4 -> SettingsWorkflowPage(t, onDest)
                    else -> ReverseBlutterPane(t, onDest)
                }
            }
        }
    }
}

/**
 * Blutter pane —— 版本矩阵与覆盖统计是低频细节（两屏表格），页签里只放「装了几套、能覆盖哪些」
 * 和去详情页的入口；高频的「分析一个 APK」在分析页的 Flutter 视图里做。
 */
@Composable
private fun ReverseBlutterPane(t: UiText, onDest: (SettingsDest) -> Unit) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    val manifest = remember {
        runCatching {
            JSONObject(context.assets.open("blutter/runners.json").bufferedReader().use { it.readText() })
        }.getOrNull()
    }
    val runnerCount = manifest?.optJSONArray("runners")?.length() ?: 0
    val aliasCount = manifest?.optJSONArray("snapshotAliases")?.length() ?: 0
    val matrix = manifest?.optString("matrixVersion").orEmpty().ifBlank { "?" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassGroup(title = if (zh) "Flutter / Dart AOT" else "Flutter / Dart AOT") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (zh) {
                        "内置 $runnerCount 套 Runner（矩阵 $matrix）与 $aliasCount 条快照别名，用于解析 libapp.so 的 Dart AOT 快照。真要分析文件，走「分析」页的 Flutter 视图。"
                    } else {
                        "$runnerCount embedded runners (matrix $matrix) and $aliasCount snapshot aliases for Dart AOT snapshots. Run an analysis from the Flutter view on the Analyse tab."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GlassGroup(title = if (zh) "Runner 矩阵" else "Runner matrix") {
            NavRow(
                if (zh) "版本与覆盖详情" else "Versions and coverage",
                if (zh) "$runnerCount 套 Runner · $aliasCount 条别名 · 未收录原因" else "$runnerCount runners · $aliasCount aliases · unsupported reasons",
                Icons.Default.Memory,
                onClick = { onDest(SettingsDest.Blutter) },
            )
        }
    }
}

/**
 * 开发环境融合页 —— 把原先 6 个「进入」箭头融合成一页内的 6 个 pane。
 *
 * 之前改一次终端字号要点三次（设置 → 开发环境 → 终端执行），中间两层都只是目录，
 * 每一层除了箭头没有别的信息。现在 Linux / 终端 / 可执行 / 编辑器 / Git / 扩展 在同一页
 * 用页签直接切；重活（装发行版、开会话、跑可执行文件、提交推送、装插件）仍在各自 pane
 * 里就地完成，不再有第三层。
 */
@Composable
internal fun SettingsDevEnvPage(
    t: UiText,
    onDest: (SettingsDest) -> Unit,
    onOpenEditor: () -> Unit,
    onBack: () -> Unit,
) {
    val zh = t.zh
    var pane by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(
        "Linux",
        if (zh) "终端" else "Terminal",
        if (zh) "可执行" else "Native",
        if (zh) "编辑器" else "Editor",
        "Git",
        if (zh) "扩展" else "Ext",
    )
    ToolPageScaffold(
        title = if (zh) "开发环境" else "Dev environment",
        subtitle = if (zh) "Linux · 终端 · 可执行 · 编辑器 · Git · 扩展"
        else "Linux · terminal · native · editor · Git · extensions",
        onBack = onBack,
        actions = {
            if (pane == 3) {
                IconButton(onClick = onOpenEditor) {
                    Icon(
                        Icons.Default.Code,
                        if (zh) "打开编辑器" else "Open editor",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = pane,
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.background,
                divider = {},
            ) {
                labels.forEachIndexed { i, label ->
                    Tab(
                        selected = pane == i,
                        onClick = { pane = i },
                        text = { Text(label, fontSize = 13.sp, maxLines = 1) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Box(Modifier.fillMaxSize()) {
                when (pane) {
                    0 -> SettingsLinuxPage(t)
                    1 -> SettingsTerminalPage(t)
                    2 -> SettingsNativeExecPage(t)
                    3 -> DevEnvEditorPane(t, onOpenEditor)
                    4 -> SettingsGitPage(t)
                    else -> SettingsExtensionsPage(t, onDest)
                }
            }
        }
    }
}

/**
 * 编辑器 pane —— 编辑器本体是顶栏独立全屏页（多标签、行号、搜索），塞进页签只会两层工具栏，
 * 所以这里放它真正需要的两件事：入口与常驻偏好。
 */
@Composable
private fun DevEnvEditorPane(t: UiText, onOpenEditor: () -> Unit) {
    val zh = t.zh
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("taffy_editor_prefs", android.content.Context.MODE_PRIVATE)
    }
    var confirmExit by remember { mutableStateOf(prefs.getBoolean("editor_confirm_exit", true)) }
    var orderCount by remember {
        mutableIntStateOf(runCatching { EditorCommands.order(context).size }.getOrDefault(0))
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassGroup(title = if (zh) "编辑器" else "Editor") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (zh) {
                        "多标签代码编辑器是顶栏上的独立页面：行号、搜索替换、跳转、格式化、批量缩进都在那边。这里只留常驻偏好。"
                    } else {
                        "The multi-tab editor is a full screen of its own. These are its persistent preferences."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryActionButton(
                    if (zh) "打开编辑器" else "Open editor",
                    onOpenEditor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        GlassGroup(title = if (zh) "偏好" else "Preferences") {
            ToggleRow(
                if (zh) "离开前确认" else "Confirm before leaving",
                confirmExit,
            ) { checked ->
                confirmExit = checked
                prefs.edit().putBoolean("editor_confirm_exit", checked).apply()
            }
            GroupDivider()
            NavRow(
                if (zh) "重置工具条顺序" else "Reset toolbar order",
                if (zh) "当前 $orderCount 项，恢复默认排序" else "$orderCount items, restore default order",
                Icons.Default.Restore,
                onClick = {
                    runCatching { EditorCommands.resetToolbar(context) }
                    orderCount = runCatching { EditorCommands.order(context).size }.getOrDefault(orderCount)
                },
            )
        }
    }
}

/** 设置全局搜索页：输入框 + 热门标签 + 命中列表（标题/说明/分组面包屑）。 */
@Composable
internal fun SettingsSearchPage(
    t: UiText,
    onDest: (SettingsDest) -> Unit,
    onBack: () -> Unit,
) {
    val index = remember(t.zh) { settingsIndex(t.zh) }
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query, index) {
        val q = query.trim()
        if (q.isEmpty()) index
        else index.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.subtitle.contains(q, ignoreCase = true) ||
                it.keywords.contains(q, ignoreCase = true)
        }
    }
    SettingsScaffold(title = if (t.zh) "搜索设置" else "Search settings", onBack = onBack) {
        item("field") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text(if (t.zh) "设置项、功能名或关键词，如「签名」「隧道」" else "Setting, feature or keyword", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
        }
        if (query.isBlank()) {
            item("popular") {
                SettingsSection(if (t.zh) "热门搜索" else "Popular") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            if (t.zh) "签名" else "sign",
                            "MCP",
                            if (t.zh) "隧道" else "tunnel",
                            if (t.zh) "抓包" else "capture",
                            "Flutter",
                            if (t.zh) "扩展" else "extension",
                            if (t.zh) "快照" else "snapshot",
                            if (t.zh) "主题" else "theme",
                        ).forEach { tag ->
                            AssistChip(onClick = { query = tag }, label = { Text(tag, style = MaterialTheme.typography.labelMedium) })
                        }
                    }
                }
            }
        }
        item("count") {
            Text(
                if (hits.isEmpty()) (if (t.zh) "没有匹配的设置项" else "No matching setting")
                else (if (t.zh) "找到 ${hits.size} 项" else "${hits.size} results"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        // 命中项按分组收拢，组内行连成一张卡
        val groups = hits.groupBy { it.group }.toList()
        groups.forEachIndexed { groupIndex, (groupName, entries) ->
            item("group-$groupIndex") {
                SettingsSection(groupName) {
                    entries.forEachIndexed { i, entry ->
                        PreferenceSurface(first = i == 0, last = i == entries.lastIndex) {
                            SettingsItemRow(
                                icon = settingsIcon(entry.dest),
                                title = entry.title,
                                subtitle = entry.subtitle,
                                value = null,
                                onClick = { onDest(entry.dest) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置 → 自检：把完整性结论与「运行环境威胁清单」摆出来。
 *
 * 这些探针（调试器 / tracer / FART-ROM / 进程内 hook 帧 / CREATOR 换类 / maps 特征）
 * 只报告、不阻断——本产品面向逆向场景，用户常开 root 与调试器；但完全不展示又会让
 * 「为什么弹完整性提示」无从解释，所以给它们一个独立、可重跑的查看页。
 */
@Composable
internal fun SettingsSelfCheckPage(t: UiText, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var result by remember { androidx.compose.runtime.mutableStateOf<com.soreverse.mcp.core.IntegrityGuard.Result?>(null) }
    var threats by remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    suspend fun runCheck() {
        busy = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            result = com.soreverse.mcp.core.IntegrityGuard.verify(context)
            threats = com.soreverse.mcp.core.IntegrityGuard.runtimeThreats(context, force = true)
        }
        busy = false
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCheck() }

    SettingsScaffold(
        title = if (t.zh) "自检" else "Self check",
        onBack = onBack,
        statusText = if (t.zh) "完整性结论 + 运行环境探针（只报告，不阻断）" else "Integrity result + runtime probes (report only)",
    ) {
        item("integrity") {
            SettingsSection(if (t.zh) "安装包完整性" else "Package integrity") {
                val r = result
                PreferenceSurface(first = true, last = true) {
                    SettingsItemRow(
                        icon = settingsIcon(SettingsDest.SelfCheck),
                        title = when {
                            r == null -> if (t.zh) "检测中…" else "Checking…"
                            r.trusted -> if (t.zh) "签名与内容一致" else "Signature and content verified"
                            else -> if (t.zh) "未通过" else "Not verified"
                        },
                        subtitle = r?.reason ?: (if (t.zh) "正在校验签名与 APK 完整性" else "Verifying signature and APK integrity"),
                        value = r?.let { if (it.trusted) "OK" else "FAIL" },
                        onClick = { scope.safeLaunch { runCheck() } },
                    )
                }
            }
        }
        item("runtime") {
            SettingsSection(if (t.zh) "运行环境" else "Runtime environment") {
                if (threats.isEmpty()) {
                    PreferenceSurface(first = true, last = true) {
                        SettingsItemRow(
                            icon = settingsIcon(SettingsDest.Permissions),
                            title = if (t.zh) "未发现异常特征" else "No anomalies detected",
                            subtitle = if (t.zh) "未命中调试器、tracer、脱壳 ROM、hook 帧或过签框架特征" else "No debugger, tracer, unpacking ROM, hook frames or signing-bypass markers",
                            value = null,
                            onClick = { scope.safeLaunch { runCheck() } },
                        )
                    }
                } else {
                    threats.forEachIndexed { index, threat ->
                        PreferenceSurface(first = index == 0, last = index == threats.lastIndex) {
                            SettingsItemRow(
                                icon = settingsIcon(SettingsDest.SelfCheck),
                                title = threat,
                                subtitle = if (t.zh) "该项只作提示：不影响签名校验结论" else "Informational only: does not change the signature verdict",
                                value = null,
                                onClick = { scope.safeLaunch { runCheck() } },
                            )
                        }
                    }
                }
            }
        }
        item("retry") {
            androidx.compose.material3.FilledTonalButton(
                onClick = { scope.safeLaunch { runCheck() } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) (if (t.zh) "检测中…" else "Checking…") else (if (t.zh) "重新检测" else "Run again"))
            }
        }
    }
}
