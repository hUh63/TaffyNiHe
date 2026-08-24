# 依赖原则（零依赖优先）

塔菲逆核的核心原则：**核心逆向能力自包含（零外部依赖），设备已有工具作为「可选增强」保留调用权利，非必需、可优雅降级。**

## 依赖分层

### 1. 内置核心（编译进 APK，零外部依赖）
这些能力完全自包含，不依赖任何外部 app / 运行时：

| 能力 | 实现 |
|---|---|
| SO/ELF 分析 | `librz_native.so`（rizin 0.10 + LIEF + ghidra 反编译器全静态） |
| Unidbg 模拟 | capstone/keystone/unicorn（内置 so + jar） |
| dex→java 反编译 | jadx-core（内置 jar） |
| dex↔smali | smali/baksmali/dexlib2（内置 jar） |
| APK 解码/回编 | ARSCLib + APKEditor（内置 jar） |
| APK 签名/验证 | apksig + SignatureVerifier（native 完整性校验） |
| 脱壳 | eBPFDexDumper / DexKit（内置） |
| 抓包 | HttpCaptureServer + tcpdump 通道 |
| 日志 | LogcatTools（内置采集/录制/过滤） |
| 动态沙箱 | taffy_sandbox（安装/启动/看门狗/日志/清理，无 root 可降级） |
| Frida | libfrida_server.so（内置） |
| Cloudflare 隧道 | libcloudflared.so（内置） |

### 2. 可选增强（探测式，外部可用则用，不可用则降级提示）
这些是「保留调用设备已有工具的权利」，**不是必需依赖**；缺失时核心能力不受影响，仅该增强功能降级：

| 增强 | 依赖的外部 | 降级行为 |
|---|---|---|
| `taffy_compile`（方案 A） | Termux clang/NDK | detect 返回「未检测到编译器，请安装 Termux」 |
| `taffy_terminal_exec` | Termux python3/node/busybox | detect 返回「未检测到 Termux 运行时」 |
| APK MCP 桥接 | MT/NP Manager 的 MCP 服务器 | 远程不可达时隐藏桥接工具，本地 standalone |

### 3. 系统提权（不可内置，属设备能力）
| 通道 | 说明 |
|---|---|
| Root | `su` 通道，系统级 |
| Shizuku | 官方/分支 Shizuku app，adb 级 |
| Dhizuku | 设备所有者提权 |
| READ_LOGS | adb 授予的系统权限 |

这些是「提权手段」，非「依赖」；无 root/Shizuku 时大量功能已有无权限降级路径（沙箱、日志、编译探测等）。

## 降级原则

1. **核心能力永不因外部依赖缺失而失效** —— 若某个核心功能必须依赖外部，则应内置（如引擎 so、jadx、smali）。
2. **可选增强优先探测** —— 外部工具用 `detect` 探测，缺失时返回明确提示而非崩溃。
3. **无 root 优先降级** —— 有特权通道用 shell（pm/am/ps），无特权用 Android 系统 API（PackageInstaller/ActivityManager/startActivity）。

## 边界判断

新增一个能力时，先判断它属于哪一层：
- **核心逆向能力**（分析/反编译/签名/脱壳/模拟）→ 必须内置（jar/so 编译进 APK）。
- **通用工具链**（编译器/脚本运行时）→ 体积大（clang 60MB / node 35MB / python 10MB），默认走「探测外部 + 降级」，除非明确要求内置。
- **其他逆向工具**（MT/NP）→ 桥接探测，作为补充。
