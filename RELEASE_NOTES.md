# TaffyNiHe v1.3.58

## 🚀 新增

- **Linux 环境诊断（健康检查）**：`设置 → Linux 环境 → 环境诊断` 一键逐项体检 —— 执行通道 / proot 运行时与可执行权限 / rootfs 是否解压 / DNS 配置 / 端到端 `echo` 探针 / 可用存储，每项给出 ✅/⚠️/❌ 与具体原因，支持「复制报告」贴给他人排查。（借鉴 Xed-Editor 的 Terminal Health Checks）
- **降级模式提示**：无 root/Shizuku 且内置 proot 未就绪时，不再只显示「不可用」，而是明确标注当前处于降级模式，并给出三条修复路径。
- **编辑器行排序命令**：`行排序 ↑ / ↓`（升序 / 降序），对选区覆盖的行排序，无选区则整篇；自动进入命令面板与自定义工具条候选。
- **编辑器「离开前确认」**：切换底部导航时若编辑器有未保存内容，先弹确认；开关位于编辑器「更多」菜单，持久化保存。
- **扩展兼容性校验**：`meta.json` 可声明 `minAppVersion`，低于要求时扩展卡片显示 ⚠️ 原因并禁用「运行」。
- **扩展设置项（声明式）**：`meta.json` 新增 `settings` 数组（`key/label/type/default`），扩展卡片出现「设置」按钮，开关与输入项会持久化，并在运行时注入为环境变量 `TAFFY_EXT_SET_<KEY>`，Python 侧直接读 `os.environ` 即可。

## ✨ 改进

- **发布资产名带版本号**：APK 从 `app-<abi>-release.apk` 改为 `taffy-<版本>-<abi>.apk`，下载后一眼能认出版本。
- **Release 正文改为分段 changelog**：按「新增 / 改进 / 修复 / 工程」分节，取代只有提交标题的自动 notes。

## 🐛 修复

- 修复切走编辑器后返回时的状态丢失相关交互（离开前确认，避免误以为内容丢失）。

## 🔧 工程 / 构建

- 多 ABI 发布流水线：资产在**收集阶段**即重命名，保证 `SHA256SUMS` 中的文件名与实际资产一致。
- 发布正文来源：仓库根 `RELEASE_NOTES.md`（存在即采用，否则回退 GitHub 自动 notes）。
- 新增 **`THIRD_PARTY_NOTICES.md`**：汇总随包分发的原生组件与 Java/Kotlin 依赖及其许可，并列出需人工复核的合规项（GPL-2.0 组件与 GPL-3.0 的组合、AGPL 依赖等）。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.57...v1.3.58
