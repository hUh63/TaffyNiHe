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

## 版本锁定与冻结清单

「能升就升」不等于「全都升」。有一组依赖的版本由上游 **unidbg 0.9.9 的 pom 强制锁定**，
单独升级会破坏编译期契约或运行时反射，因此冻结并登记在此，避免后人误升：

| 依赖 | 冻结版本 | 为什么不能升 |
|---|---|---|
| `com.alibaba:fastjson` | 1.2.83 | unidbg 的 `McpTools.dispatchTool(String, com.alibaba.fastjson.JSONObject)` 签名要求 1.2.x 的类名（`UnidbgEmulator.sessionNativeToolCall` 反射调用它）。升 2.x 类名变成 `com.alibaba.fastjson2.*`，反射直接失败。1.2.83 已是 1.2 系列末版；本项目只用它构造对象传给 unidbg，不做反序列化解析。 |
| `capstone`（本地 patched jar） | 3.1.8 | unidbg-api:0.9.9 的 pom 指定 3.1.8；官方 capstone 已到 6.0.0-alpha，但与 unidbg 的 JNI/绑定不兼容。 |
| `keystone`（本地 patched jar） | 0.9.7 | 同 capstone；且 0.9.7 已是上游 Maven 上的最新版（项目已停更）。 |
| `unicorn` | 1.0.15 | unidbg-api:0.9.9 pom 指定，JNI 桥与之配套。 |
| `commons-codec` / `commons-collections4` / `commons-io` | 1.21.0 / 4.5.0 / 2.21.0 | unidbg-api pom 指定。 |
| `demumble` / `apk-parser` | 1.0.4 / 2.6.10 | unidbg 及其传递依赖指定。 |

> ⚠️ 关键点：本项目的 unidbg 用**本地 patched jar**（`app/libs/unidbg-*-0.9.9-android-patched.jar`），
> 不参与 Gradle 版本解析 —— 它的传递依赖必须在 `build.gradle.kts` 里**手工对齐**。
> 任何一项被 Gradle 升到更高版本，都可能在运行时抛 `NoSuchMethodError`。

## 工具链与关键依赖版本（2026-09）

| 项 | 版本 | 备注 |
|---|---|---|
| AGP | 9.4.1 | AGP 9 起 Kotlin 支持内置（不要再应用 `org.jetbrains.kotlin.android`） |
| Kotlin / Gradle | 2.4.20 / 9.8.0 | |
| compileSdk / targetSdk / minSdk | 37(minor 2) / 36 / 26 | compose 1.12.x 要求 compileSdk ≥ 37，平台是 `platforms;android-37.2` |
| NDK | 30.0.16248370 | 28.2 → 30；4 ABI 原生库（rizin+LIEF、unidbg、xAnSo）全部重新构建通过 |
| LIEF | 1.0.0 | 0.16.1 → 1.0.0：`LIEF::to_json` 在 1.0 拆到 `LIEF::ELF::to_json`，用 `LIEF_VERSION_MAJOR` 宏兼容两代；补丁集同步换代 |
| rizin / unidbg | v0.9.1 / 0.9.9 | 均为上游最新（rizin main 仍有编译 bug，故 pin tag） |
| cloudflared / frida-server | 2026.9.3 / 17.19.0 | CI 注入，版本常量在 `build-multiabi.yml` |

## 升级作业规范（升级依赖时的检查清单）

1. **先查上游 pom**：unidbg 配套依赖一律以 `unidbg-api:<版本>` 的 pom 为准，不单独升级。
2. **提前预检资源冲突**：升级前把新 jar/aar 的 ZIP 条目列出来比对一遍，一次性算出 `packaging.resources` 需要新增的 `excludes` / `pickFirsts` / `merges`（否则每轮 CI 只暴露一个冲突，一轮 8~15 分钟）。
3. **缓存必须感知输入**：CI 里 native 构建缓存 key 必须包含 LIEF 版本与 NDK 版本，否则旧产物会让升级「看起来成功其实没生效」。
4. **补丁按版本选择**：上游补丁要绑定源码版本，并让「应用失败」只告警不中断（上游修好后旧补丁失配，不该把流水线弄红）。
5. **API 变化的库要在代码里兼容或补依赖**（如 LIEF 1.0 的 `to_json` 拆分、ELK 0.12 需要 xtext runtime），不要用 R8 `dontwarn` 掩盖。
