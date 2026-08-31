package com.soreverse.mcp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置 → 帮助：塔菲功能教程 + 功能实现原理 + MCP Skill（可复制给 AI 的技能提示词）。
 * 全部内容内置离线可用——不再需要去网站看教程。
 */
@Composable
internal fun SettingsHelpPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    var tab by remember { mutableStateOf(0) }   // 0=功能教程 1=实现原理 2=MCP Skill

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (zh) "帮助 · 教程 / 原理 / MCP Skill" else "Help · Guide / Internals / MCP Skill",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(if (zh) "功能教程" else "Guide") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(if (zh) "实现原理" else "Internals") })
            FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text(if (zh) "MCP Skill" else "MCP Skill") })
        }
        if (tab == 2) {
            Text(
                if (zh) "↓ 复制这段 Skill 作为 AI 客户端的系统提示词，AI 就能正确驱动塔菲的全部 MCP 工具" else "↓ Copy this skill as a system prompt for any AI client",
                style = MaterialTheme.typography.labelSmall, color = AppPalette.orange,
            )
            Text(
                if (zh) "复制 Skill" else "Copy skill",
                style = MaterialTheme.typography.labelSmall, color = AppPalette.blue,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(MCP_SKILL))
                    Toast.makeText(context, if (zh) "MCP Skill 已复制" else "Skill copied", Toast.LENGTH_LONG).show()
                }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        SelectionContainer {
            Text(
                when (tab) { 0 -> HELP_GUIDE; 1 -> HELP_INTERNALS; 2 -> MCP_SKILL; else -> WORKFLOW_GUIDE },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                color = fg,
                modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(14.dp)).padding(12.dp),
            )
        }
    }
}

/** 功能教程（每项功能怎么用，详细页另有专属教程）。 */
private const val HELP_GUIDE = """═══ 塔菲逆核 · 功能教程 ═══

【首页 · 核心】
  服务目录 → 选一个目录作为 MCP 文件工作区
  电源按钮 → 启动/停止 MCP 服务（一切工具的前提）
  链接 → 展示 MCP 端点地址（本机/局域网/隧道）
  工具 → MCP 工具列表与测试调用

【设置 · 常用】
  服务配置   端口/目录/启动参数/AI 端点
  MCP 桥接   让 MT 管理器等外部 App 作为桥接端调用塔菲
  隧道       bore/Cloudflare 公网暴露（远程 AI 访问手机）
  AI 深度分析 配置 LLM 端点后可用对话式深度逆向
  外观       主题/强调色/语言/密度

【逆向分析】
  Rizin      静态分析：函数/字符串/反汇编/ESIL/反编译
             命令速查页有 afl/pdf/izz/axt 等常用命令
  eDBG       eBPF 动态调试：断点/寄存器/内存/反编译
  抓包       本地代理元数据抓包+WS 帧+重放（详情见抓包页教程）
  动态沙箱   安装/启动目标 App、查看运行日志与崩溃

【开发环境】
  Linux 环境 Alpine/Ubuntu rootfs（无 root 走 proot）
  终端执行   真会话终端 + taffy CLI（终端里调 MCP 工具）
  编辑器     Python/Shell/JSON 多模式 + LSP 补全 + 多 tab
  Git 仓库   工作区版本管理（init/commit/push，走 rootfs）
  扩展系统   Python 插件：创建/市场/Xed 转换/沙箱运行
  工作区     目录管理/临时工作区清理

【典型工作流】
  APK 分析:  首页选 apk → 引擎扫描 → Rizin/eDBG 交互
  SO 修改:   Rizin 定位 → taffy_edit_patch → 回写签名
  流量分析:  抓包启动 → 设置代理 → 导出 JSON 给 AI
  脚本自动化: 扩展系统写插件 → ext.mcp(...) 串联全工具

更多细节: 各功能页面内均有专属教程（离线）。
"""

/** 推荐工作流（原设置页"使用说明"已合并至此）。 */
private const val WORKFLOW_GUIDE = """═══ 塔菲逆核 · 推荐工作流 ═══

【MCP 基础接入】
  1. 选择包含 .so / .apk 的工作目录（首页 → 服务目录）
  2. 启动服务后复制 MCP 链接到 AI 客户端
  3. 先调用 mt_so_list_available_sos，再用 mt_so_open
     打开目标，后续工具使用返回的 workspaceId

【SO 深度分析流】
  启动 SO MCP → Cloudflare Tunnel（公网）
             或 adb forward tcp:8000 tcp:8000（本机）
  → 客户端配置 MCP 端点
  → taffy_so_open → analyze_* → taffy_read_disasm / search_*
  → 修改前 taffy_session_open → dryRun 预览 → patch
  → taffy_build_so 导出

【AI 深度分析】
  设置 → AI 深度分析：填端点 / Key / 模型
  支持 OpenAI 兼容（DeepSeek/Moonshot/Kimi/GLM/Groq）
  Anthropic 兼容（中转站）· Gemini 原生 · OpenRouter · Grok
  回到 AI 深度分析页即可对话式深度逆向（AI 自动调工具）

【远程访问】
  电脑 → 手机:  隧道页启动 Cloudflare / bore，或 adb forward
  手机 → 外网:  Linux 环境 / 终端执行内正常联网工作
"""

/** 实现原理（每个功能背后是怎么做的）。 */
private const val HELP_INTERNALS = """═══ 塔菲逆核 · 实现原理 ═══

【MCP 服务】
  自实现 HTTP JSON-RPC 服务端（McpHttpServer）：
  initialize / tools/list / tools/call 标准方法，
  Bearer token 访问控制，工具结果统一 wrap 为
  {isError, content:[{type:text}]}。外部 AI 客户端
  或桥接 App 用标准 MCP 协议即可驱动。

【逆向引擎】
  Native 模式: 内置 rizin（librizin 绑定）做反汇编/
  符号/ESIL 模拟，rizin-ghidra 插件出反编译。
  Standalone 模式: 纯 Java 解析 ELF（ElfParser，
  section/dynsym/字符串），不依赖 native。
  Blutter: Flutter/Dart 产物离线分析（内置快照解析）。

【eDBG 动态调试】
  eBPF uprobe/tracepoint 挂接目标进程，用户态
  collector 汇聚事件 → 图形化断点/寄存器/内存视图。

【抓包代理】
  本地 TCP 代理（HttpCaptureServer）：解析 CONNECT
  隧道（HTTPS 元数据）与明文 HTTP（完整头+响应），
  WebSocket 帧旁路解析（RFC6455 状态机去 mask），
  500 条环形缓冲防 OOM；tcpdump 通道走链路层。

【Linux rootfs】
  assets 内置 Alpine/Ubuntu tar.gz，手写 tar 解压器
  解压到私有目录；root/Shizuku 走 chroot（bind 挂载
  proc/dev/sys），无 root 走内置 proot 用户态模拟；
  exec 时把工作区 bind 进 rootfs /mnt/ws。

【Python 运行时】
  内置 Termux cpython 3.14（解压即用，含
  site-packages: jedi/pygls/jedi-language-server）。
  LSP: 长驻 jedi-language-server 子进程 + 自实现
  JSON-RPC/Content-Length 客户端（LspClient）。

【扩展系统】
  插件 = plugin.py + meta.json；runner 注入 taffy_ext
  API 并加 PEP578 审计钩子沙箱（禁外网/禁子进程/
  写白名单）。Xed 转换: dexlib2 解析扩展 dex 提取
  元数据/资源/调用面 → 生成骨架 + AI 转换 prompt。

【安全防护】
  自校验（v2/v3 签名块）、内存守卫、Tombstone 收集、
  Unidbg 泄漏抑制；APK 工具链自带密钥签名。

【MCP 桥接】
  外部 App（MT 管理器）注册为桥接端：塔菲把工具
  调用转发给桥接 App 执行，结果回传——扩展能力
  无需重编译主程序。
"""

/** MCP Skill：可复制给 AI 客户端的系统提示词。 */
private const val MCP_SKILL = """# Skill: taffy-reverse（塔菲逆核 MCP 驱动指南）

你是接入"塔菲逆核"MCP 服务器的逆向工程助手。塔菲是
Android 逆向工具箱（SO/APK 分析、动态调试、抓包、补丁）。
工具调用统一走 MCP tools/call；结果为文本 content。

## 核心原则
1. 先列工具: 调 tools/list 确认当前可用工具与参数（以返回为准）
2. 文件操作一律在"工作区"内（用户在塔菲里选定的目录）
3. 大输出先查摘要字段，再按需取明细；分页/限制参数见工具 schema
4. 危险操作（写文件/回写 SO/签名）执行前向用户确认

## 工具族速查（名称以 tools/list 实际返回为准）
- 工作区: taffy_workspace (action=list/read/write/mkdir...)
- 终端:   taffy_terminal_exec (command=..., 也可用 taffy CLI)
- Linux:  taffy_linux (action=shell, distro=alpine|ubuntu, 在 rootfs 内执行)
- Rizin:  taffy_rz (command=afl/pdf @函数/izz/axt...) 静态分析
          taffy_build_so 回写并签名 SO
- eDBG:   taffy_edbg_* 附加进程/断点/寄存器/内存读写
- 抓包:   taffy_capture_* (start/list/export) HTTP/WS 元数据与明文
- APK:    taffy_apk_* (info/extract/edit/manifest/sign/...)
- 日志:   taffy_logcat_* (实时过滤、崩溃分组)
- 沙箱:   taffy_sandbox_* (安装/启动目标应用/查崩溃)
- Blutter: Flutter/Dart 产物分析
- AI:     塔菲内置 AI 深度分析（对话式逆向，见 taffy_ai_*）

## 典型工作流
【分析一个 SO】
 taffy_workspace(action=list) → 找到目标 .so →
 taffy_rz(command="iI") 基本信息 → taffy_rz(command="afl")
 函数列表 → 对关键函数 taffy_rz(command="pdf @sym.xxx")
 → 需要时 taffy_rz(command="izz") 字符串交叉定位 →
 汇总结论给用户（含偏移地址）。

【修改 SO 并回签】
 定位目标字节 → taffy_edit_patch（按偏移写 hex）→
 taffy_build_so 回写并签名 → 告知用户输出路径与校验值。

【APK 静态分析】
 taffy_apk_info → taffy_apk_extract（提 dex/so/manifest）→
 taffy_manifest_edit（改权限/debuggable）→ taffy_apk_sign。
 Flutter 应用用 Blutter 输出类/方法结构。

【Frida 免 root 内置打包（装上即 hook）】
 taffy_apk_decode → taffy_apk_frida_gadget(action=place,
   gadget=<frida-gadget.so 路径或下载直链>, script=<hook.js>)
   → taffy_apk_frida_gadget(action=patch_entry, dir=...)
   → taffy_apk_rebuild(build) → taffy_apk_sign。
 原理: gadget/配置/脚本伪装成 so 放进 lib/<abi>/，入口类
 注入 System.loadLibrary("frida-gadget")，安装即自动加载。
 gadget 下载: github.com/frida/frida/releases 选对应 abi。

【动态分析】
 taffy_sandbox 启动目标 App → taffy_edbg_attach →
 下断点（onLoad/目标偏移）→ 读寄存器/内存 →
 taffy_logcat 抓运行日志与崩溃栈。

【流量分析】
 taffy_capture_start → 用户操作 App →
 taffy_capture_list → 域名/路径/频率统计 →
 导出 JSON 继续分析 → taffy_capture_stop。
 注意: HTTPS 只有元数据（域名/大小），明文 HTTP 才有完整内容。

【终端自动化】
 taffy_terminal_exec 里可用 taffy CLI（taffy tools /
 taffy <tool> k=v）与 python3（内置 3.14，可用 jedi）；
 复杂脚本放工作区用 python3 执行。

## 输出要求
- 逆向结论必须带证据：偏移/函数名/字符串原文
- SO 分析给 arm64-v8a 与 armeabi-v7a 区分（若有多架构）
- 修改类操作完成后给出：改动位置、原始值→新值、产物路径
- 用户没说清楚目标时，先 list 再问，不要猜
"""
