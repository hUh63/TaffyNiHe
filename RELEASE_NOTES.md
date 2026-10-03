# TaffyNiHe v1.3.67

本版聚焦**分析引擎缺陷修复、内建工具扩充与界面收敛**。

## 🐞 修复：分析页伪 C / 引用 / 调用图（逐条定位到根因）

- **native 引擎无输出（伪 C 为空）**：本构建 rizin 未打包 `pdc/pdd/pdg`（缺 rz-ghidra 核心库）→ 已回退到**随包内置的 Exbin 原生反编译器**。
- **java / simple 引擎无输出**：根因是 rizin 0.9 的 `agf` 改为输出 `nodes/edges`，而 r2dec 需要 `blocks[].ops[]` → 已用 `afbj` + `pdfj` **现场拼装**出 r2dec 所需结构。
- **未取得全局调用图（降级为函数清单）/ 根节点无出边**：根因是 rizin 0.9 的 `agC` 是**格式参数型**命令（须 `agC json`），`agCj` 被当作非法格式而恒空 → 已改用 `agC json`，并保留小写 / `aac` 回退链。
- **无交叉引用**：`axt/axf` 需先做函数调用分析 → 已前置 `aac`。

## ✨ 虚表识别增强

- 新增 **`_ZTV<类名>` 符号级识别**（Itanium ABI，自动还原 `foo::Bar` 类名）；无重定位信息时兜底给出虚表起始地址。不再轻易报「未发现虚表」。

## 🧭 分析页导航：三层 → 两层

- 「域 → 工具 → 模式」压成「**域 → 工具**」，域数量由 5 收敛为 3（**代码 / 结构 / 工具**）；工具内的各视图改用**页内 chip** 切换 —— 入口更少，**能力不丢**。

## 🧮 新增：内置高精度计算器（`taffy_calculate`）

- 移植 calculate-mcp：算术 / 统计 / 三角、任意精度进制转换、位运算、端序、IEEE-754、MD5 / SHA / CRC32 / CRC16、模运算、Base64 / Hex / URL，支持链式批量 `steps`（`{"$step":0,"field":"resultHex"}` 引用）。
- 供 **MCP 调用**，分析页新增**计算器视图**。纯 JVM 实现，零外部进程。

## 📦 新增：iApp v3 解密（`taffy_iapp_decrypt`）

- 移植 aiysss/iapp-decrypt：解密 iApp 打包 APK 的 `assets/lib.so`（外层 AES-CBC 容器 + 内层按标记分隔的成员源码）。
- `action=extract` 从 APK **自动提取** sok（libygsiyu.so）/ 签名 DER / 清单包名·版本·应用名 / dex 常量 dek；`action=decrypt` 递归导出 `mian.iyu` 等**全部内层源码**。
- 覆盖 **current / legacy4 / transitional** 三套算法族；核心密码学原语（slky 哈希 + AES-CBC + 循环 XOR）附参考向量单测。

## ⬆️ 升级

- **frida-server 17.19.0 → 17.22.0**（构建期注入的版本常量已同步更新）。

## 🧹 界面收敛（强相关功能合并）

- 设置中心：**「运行统计」合并** —— 工具调用统计 + 隧道稳定性归入同一页。
- 移除与「工作区」页重复的「临时工作区」根入口（该页仍可从「工作区」进入）。

---

> 完整变更以仓库提交历史为准。塔菲逆核为 GPL-3.0-only 自由软件。
