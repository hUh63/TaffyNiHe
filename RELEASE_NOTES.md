# TaffyNiHe v1.3.58

## 🚀 新增

- **Flutter / Dart AOT 分析视图**：`分析页 → 分析域 → Flutter`。识别 APK 指纹（Dart 版本 / ABI / snapshot hash / engine revision / 压缩指针 / 指纹置信度）、自动匹配 APK 内置 Runner、提交作业并实时显示阶段，结果按 **库 / 类 / 函数 / 对象** 结构化浏览（含警告与产物清单），可随时取消。全本地，不上传 `libapp.so` / `libflutter.so`，不需要 Python / ADB / 网络。
- **内置 Runner 清单可视化**：`设置 → Blutter` 直接读 `assets/blutter/runners.json` 渲染矩阵版本、4 套 Runner（Dart 3.11.5 / 3.12.2 / 3.13.0 / 3.13.1，arm64-v8a，压缩指针，snapshot 别名，sha256）、覆盖统计与未收录原因。
- **原生可执行通道（execve + jniLibs）**：`设置 → 本地可执行`。Android 10 起应用私有目录禁止 `execve`，但 APK 安装解压出的 `nativeLibraryDir` 仍可执行 —— 把 Android PIE 可执行文件命名成 `lib<name>.so` 放进 `jniLibs/<abi>/` 即可在设备上直接运行，**免 root、免 proot、免 rootfs**。页面列出全部候选并给出 ELF 判定 / ABI 匹配 / 权限状态，支持前台执行（参数 + 超时 + 输出上限）、常驻后台（日志落盘 + pid）、进程管理与日志查看。参数直接作为 argv 传递，不经 shell。
- **MCP 工具 `taffy_native_exec`**：上述通道的机器接口（`list / probe / run / daemon / ps / kill / log`），新增 `runtime` 工具类别。
- **Linux 环境诊断（健康检查）**：`设置 → Linux 环境 → 环境诊断` 一键逐项体检 —— 执行通道 / proot 运行时与可执行权限 / rootfs 是否解压 / DNS 配置 / 端到端 `echo` 探针 / 可用存储，每项给出 ✅/⚠️/❌ 与具体原因，支持「复制报告」。（借鉴 Xed-Editor 的 Terminal Health Checks）
- **降级模式提示**：无 root/Shizuku 且内置 proot 未就绪时，明确标注当前处于降级模式并给出修复路径。
- **编辑器行排序命令**：`行排序 ↑ / ↓`，对选区覆盖的行排序（无选区则整篇），自动进入命令面板与自定义工具条候选。
- **编辑器「离开前确认」**：切换底部导航时若有未保存内容先弹确认，开关持久化。
- **扩展兼容性校验**：`meta.json` 可声明 `minAppVersion`，低于要求时显示 ⚠️ 原因并禁用「运行」。
- **扩展设置项（声明式）**：`meta.json` 新增 `settings` 数组，扩展卡片出现「设置」按钮，持久化并注入为环境变量 `TAFFY_EXT_SET_<KEY>`。

## ✨ 改进

- **分析页工具聚合（不再零散）**：原先 47 个视图平铺成一行横向滚动的 chip，同类数据（ELF 的九张表、交叉引用的五种视角、反汇编的多个变体）混在一起。现改为 **域 → 工具 → 模式** 三级：5 个域（函数 / 代码 / 结构 / 分析 / 工具）、18 个工具、47 个模式一一对应，模式只有 1 个时不出模式行。切回同一个工具会回到上次停留的模式，抽屉改为三级索引。
- **任务页「继续」真正接着做**：以前只切换状态、不重开文件，点了没反应；现在会重新打开任务主文件、重建共享工作区并回到分析页。原文件失效或 URI 授权过期时给出明确原因，并提供「重新选文件」。选择文件时持久化 URI 授权（应用重启后仍可继续历史任务）。
- **任务页样式统一**：按 Exbin 风格重做（圆角卡片 + 1dp 描边 + 分组计数 + 状态徽标）。
- **发布资产名带版本号**：APK 从 `app-<abi>-release.apk` 改为 `taffy-<版本>-<abi>.apk`。
- **Release 正文改为分段 changelog**：按「新增 / 改进 / 修复 / 工程」分节。

## 🐛 修复

- 修复「历史任务无法继续」：根因是恢复逻辑只改状态、不重新打开工作区。
- 修复 `content://` 打开的文件在应用重启后无法再次打开（缺 `takePersistableUriPermission`）。
- 修正与能力清单脱节的陈旧文案：工具描述与「不支持」报错里写死的 `Flutter 3.44.x / Dart 3.12.2` 改为从 `runners.json` 动态生成。
- 修复编辑器未保存内容相关交互（离开前确认，避免误以为内容丢失）。

## 🔧 工程 / 构建

- 多 ABI 发布流水线：资产在**收集阶段**即重命名，保证 `SHA256SUMS` 与资产一致。
- 发布正文来源：仓库根 `RELEASE_NOTES.md`（存在即采用，否则回退 GitHub 自动 notes）。
- 新增 **`THIRD_PARTY_NOTICES.md`**：汇总随包分发的原生组件与 Java/Kotlin 依赖及其许可，并列出需人工复核的合规项。
- 新增 **`docs/native-exec.md`**：原生可执行通道的架构、新增可执行文件的方法与安全边界。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.57...v1.3.58
