# TaffyNiHe v1.3.114（翻页游标 · 资源关闭链路）

收尾第三轮：把上一轮明确留下、但没说清楚的三处「机制性问题」补完 —— 跨域搜索的游标
坐标不一致、引擎没有整体关闭入口（切换工作目录就漏一个 SQLite 句柄）、分页缓存只限页数
不限条数。

## 一、跨域搜索翻页（`taffy_apk_unified_search`）

- **游标语义错了**：原来把「累计匹配数」当游标写回 `nextCursor`，而四个 scope 里只有 `zip`
  拿它做偏移，`dex / native / resource` 完全不看游标 —— 第 2 页和第一页一样，`hasMore` 永远
  为真，客户端永远翻不到底。
- 现在统一成 `<scope>:<该 scope 内已消费的匹配数>`，四个 scope 共用同一套坐标：`all` 模式下
  已翻完的 scope 整个跳过，当前 scope 从游标位置继续，跨 scope 既不重复也不丢结果。
- `dex` 分支的 DexKit 查询改为按 `offset + limit + 1` 取，多取的那一条专门用来判断「后面还有
  没有」，避免「刚好满页」被误判为结束。
- 返回值补 `returned` 与 `cursorFormat`；`total` 保留但语义改为「本页返回条数」。
- 游标格式有变：旧游标（纯数字）会被当成第一个 scope 的偏移解析，客户端升级后请丢弃旧游标、
  从空游标重新开始翻页。

## 二、引擎资源关闭链路

- **工作目录泄漏 SQLite 句柄**：`WorkDirectory` 持有 `ScanCacheStore`（`SQLiteOpenHelper`），
  但没有任何关闭入口，每切换一次工作目录就漏一个句柄。现在 `WorkDirectory.close()` 负责释放，
  并在切换前关掉旧实例。
- **新增整体关闭**：`EngineRuntime.close()`（转发到 `NativeSoEngine.close()` /
  `EngineProvider.close()`）一次性释放模拟器会话（逐个 `closeSession`）、工作区字节、
  Frida 会话、分页与搜索缓存、工作目录句柄。
- 应用真正退出时（`MainActivity.onDestroy`，排除旋转等配置变更）调用，退出后不再滞留
  SO 副本与 native 会话。

## 三、分页缓存双层上限

- `PageStore` 原来只有 64 页上限，而每个条目持有一份完整 item 列表引用 —— 64 页大列表仍可能
  驻留几十万条 JSON。新增 `maxItems = 20000`，按「不同列表实例」去重后统计真实驻留条数，
  超限淘汰最旧页。

# TaffyNiHe v1.3.113（并发与状态机 · 第二轮深挖）

这一轮不再扫样式，而是盯着「两个操作同时发生」和「异常分支」找问题：30 条候选里核实出
21 个真缺陷，全部修掉。

## 一、工作区与编辑（会丢数据的地方）

- **淘汰会删掉正在用的工作区**：原来只按 `createdAt` 淘汰，不看是否正被另一个调用使用。
  现在记录 `lastAccessAt`，30 秒内被访问过的持久工作区跳过；`temporary` 工作区也纳入淘汰
  ——它们以前计入内存预算却永远不释放，预算一超标就把所有持久工作区全删光。
- **淘汰时不关模拟会话**：workspace 删了，但绑定的 unidbg VM 与整份 SO 副本还活着，
  淘汰等于没释放内存。现在一并关闭。
- **编辑与读取的撕裂**：`editHex / editAsm / editSymbol` 原来用 `System.arraycopy` 就地改写
  `session.data`，另一个线程的 hexdump / disasm / `lief.parse` 可能读到写了一半的 ELF 头。
  全部改成 copy-on-write（替换引用）。
- **补丁报告与产物不一致**：报告在锁外遍历 `patches/data`，并发编辑时要么 CME，要么生成
  一份和刚写出的 .so 对不上的取证报告。改为锁内生成。
- **unidbg 失败泄漏**：VM 建好之后 load SO 失败时不做清理，每次失败泄漏一份 emulator。
  现在失败路径释放 emulator 与临时 so；已关闭的会话也不再接受调用（对已释放的 native
  指针发调用会直接 SIGSEGV）。

## 二、APK 写回（会让包装不上的地方）

- **重建 ZIP 丢了压缩方式与对齐**：`apk_patch_bytes`、`smali_edit`（共三处）都用
  `ZipEntry(name)` 重建条目，默认 DEFLATED。`resources.arsc` 与 `lib/` 下的 `.so` 一旦被
  重新压缩，APK 直接装不上，而工具还返回「字节已写入」。新增 `core/ZipRepack`：保留原压缩
  方式、extra 与时间戳，并对这两类条目强制 STORED + 4 字节对齐。
- **CAS 校验读错字段**：`native_patch_string` 读 `output`（实际输出在 `stdout`）恒为空，
  带 `expectedText` 的调用 100% 报 CAS_MISMATCH；`native_patch_instructions` 同源问题，
  反而是校验恒通过。已统一走 `rzStdout()`。
- **静默假成功**：`apk_manifest_edit` 的 rename_package / add_permission / remove_permission
  在正则没命中时照样写回并报成功；现在返回 `NO_CHANGE` / `NOT_FOUND`。
- **dryRun 不生效**：`taffy_edit_symbol` 的 `op=add/remove` 直接把 dryRun 丢掉、真实改 SO，
  现在明确拒绝（`DRYRUN_UNSUPPORTED`）。
- **.bak 被覆盖**：连续两次补丁后 `.bak` 已不是原始 APK，回滚会回到「半改」状态。改为只在
  首次生成。
- `apk_rebuild` 的 `force=false` 不再先删除既有产物；`manifest_xml_edit` 的
  `debuggable/exportedValue` 不再把 `"False"`/`"0"` 静默当 true；`batch` 的 `transactional`
  只在真有快照时才是 true（新增 `transactionalSupported`）；`apk_patch_bytes read` 改为
  流式跳过 offset（原来为取 256 字节把整条读进内存）。

## 三、页面状态机（会卡死的地方）

- **抓包页永久「处理中…」**：`busy` 没有 try/finally，一次异常就永久卡住；配合没有
  `onCancel` 的遮罩，返回键也被 Dialog 吞掉，只能杀进程。已加 try/finally + 防重入 + onCancel。
- **BusyOverlay 兜底**：任何页面的 busy 残留，90 秒后可以单独关掉遮罩（任务继续在后台跑）。
- **智能体编排**：启动后有 1.2 秒的重复提交窗口（能同时起两个 run），已用 running 占位消除；
  轮询失败原来静默空转 30 分钟，现在连续 3 次失败即报错；`runId/status` 改用
  `rememberSaveable`，离开页面回来能继续看进度、也能中止。
- **快照回滚失败静默**：原来把 Result 丢掉只刷新列表，用户以为已回滚。现在成功/失败都有提示。
- **切文件不清搜索结果**：分析 A 的搜索结果会残留到 B，点进去跳到完全不相干的位置。已清空。
- **Unidbg 并发模拟**：`running` 在协程里才置 true，连点两次会并发跑两份 native 模拟。
- **权限页不刷新**：从系统设置授权回来仍显示「未授予」，现在 `ON_RESUME` 自动重查。
- 搜索增加 busy 守卫（慢请求后到会覆盖新结果，表头与列表不一致）。

# TaffyNiHe v1.3.112（pdg 单一 Rizin · 门禁不再闪退 · 转储环境检测）

## 一、pdg 伪 C 的真正修复

上一轮查到「APK 里没有 libcore_ghidra.so」，核实后是另一回事：这个 .so 一直在包内，
问题在于它和宿主用的是**两份 rizin**。

`tools/build-rz-ghidra-android.sh` 把共享 Rizin SDK 装到 `rizin-sdk/<abi>`，
而 `CMakeLists.txt` 找的是 `rizin-sdk/<abi>-wsl`（本地 WSL 流程留下的命名）。路径对不上，
`librz_native.so` 就一直走静态归档分支：宿主里是一份静态编译的 rizin，插件链接的却是动态
`librz_*.so`，同一个进程里存在两套 RzCore 与全局状态。pdg 在这种结构下只会崩溃或给出错误结果。

- `CMakeLists` 改为优先 `rizin-sdk/<abi>`，回退 `<abi>-wsl`
- CI 增加硬门禁：`readelf -d librz_native.so` 必须出现 `librz_core.so`，否则直接 fail
  （只验证"编译通过"发现不了这个问题，必须看动态依赖）

## 二、完整性门禁不再「无提示闪退」

签名不匹配时原来的行为是：弹窗 10 秒倒计时 → `exitProcess(173)`。用户没盯着屏幕，
看到的就是应用无缘无故消失。现在门禁页常驻，两个按钮：「仍然使用」与「退出应用」，
由用户显式决定（对齐上游 1.0.22 的做法）。所有失败原因照旧落日志与 `lastFailure`，可从弹窗查看。

## 三、新增运行时 ART/DEX 转储检测

上游 1.0.22 加的「ROM 级脱壳环境检测」，补进 `InjectionProbe`（设置 → Linux 环境 → 扫描注入痕迹）：

- `build.prop` 中的 `arkos / fart / dumpdex / youpk / blackdex` 标识
- 系统库目录里的转储框架 `.so`（`libfart* / libdexdump* / libyoupk*`）
- `/proc/self/maps` 中从可写目录（`/data/local/tmp`、`/sdcard`、`/storage/emulated`）加载的 `.so`

特征刻意保守：上游自己踩过坑——早期把 `gadget`、`lsposed` 这类子串放进 maps 特征表，
正常设备也会误报，最后整段删掉。这些结果只做告知，不进完整性判定、不杀进程、不阻断服务。

# TaffyNiHe v1.3.111（Token 默认关闭 · 风险显式化）

上一版把 MCP 鉴权改成默认开启，实际用起来反而挡路：多数 MCP 客户端不会在 URL 里带 token，
首次连接直接 401，用户得先翻设置页找 token 再手改客户端配置。这一版改回默认不启用。

安全的做法不是「默认开」，而是「开了就得对」：

- `authorized()` 依旧是 fail-closed —— 用户主动开启鉴权但 token 为空时拒绝，不会静默放行
- 服务配置页在「未启用 Token + 允许局域网」时，常驻一条 error 色警示，写明
  「同网段任何设备都能无凭证调用全部工具（含 root / shell）」，不再只是一句灰色建议
- Token 输入框在开关关闭时标注「当前不生效」，避免用户以为填了就生效
- `taffy_app_config` 仍强制脱敏，token 仍以 AndroidKeyStore AES-256-GCM 加密落盘

# TaffyNiHe v1.3.110（系统体检 · 安全收口 · 状态保活）

这一版没有新功能，是把整个工程从头到尾查了一遍之后的修补：312 个 Kotlin 文件、188 个 MCP 工具，
按安全、数据完整性、工具健壮性、界面与操作四条线走，该封的封、该保的保。

## 一、安全（最要紧的三处）

MCP 服务默认绑定 `0.0.0.0` 而鉴权默认关闭——这意味着同一 Wi-Fi 下的任何设备都能直接调用全部工具，
包括 shell。默认值改成：鉴权开启、token 为空一律拒绝（fail-closed）。`taffy_app_config` 也不再有
`maskSecrets=false` 这个后门，远程拿不到明文 token；token 与 AI API Key 改为 AndroidKeyStore
AES-256-GCM 加密落盘，旧明文自动迁移。插件运行时不再默认拿到宿主 token，需要 `meta.json`
里显式写 `mcpAccess: true`——否则一个插件就能绕过自身的 permissions 调用全部工具。

另外补了两处命令注入闸门：`taffy_sandbox` 的 packageName/activity/apkPath、`taffy_so_addr_map`
的 address（后者能借 rizin 的 `!` 前缀执行 shell）。工作区路径白名单补上了
`inputApk / outputApk / apkPath / dexPath / outDex` 这些一直缺席的键，不再整体绕过校验。
`BootReceiver` 改为 `exported=false`，MCP 会话表加了 512 上限，CORS 预检改为回显具体来源。

> 行为变更：新装用户首次启动服务时，URL 需要带上 `?token=`（首页与设置页本来就会自动拼接）。
> 老用户 prefs 里的设置不受影响。

## 二、数据完整性

备份的未加密分支原来直接写最终文件名，而 manifest 是最后才写进 zip 的——备份途中被杀就留下一个
「成功但缺 manifest」的包，恢复时才报错。现在全部走 `tmp + rename`；加密/解密加了 200MB 内存闸门
（整包读入 + Base64 字符串 + 密文约等于 3 倍内存）；取消恢复不再被当成失败去回滚。

`taffy_apk_replace_native` 的同文件判断从 `absolutePath` 改成 `canonicalPath`：原来传 `./a.apk`
就能绕过检查，然后把原 APK 截断成 0 字节。APK 产物改为原子写出；`taffy_manifest_xml_edit` 在无法
编码 AXML 时明确报错，不再返回「成功」却零改动。

## 三、工具与引擎

`taffy_apk_patch_bytes` 的 read 默认长度从「整个条目」收到 256 字节（原来一个 `classes.dex`
就能拼出数倍体积的 hex 串直接 OOM）。抓包代理补了 10 秒 connect 超时与 16KB 单行上限。
Blutter 的隔离 runner 加了无进度 watchdog，进程吊死时不会再永远停在 running。
dex/smali 的中间产物统一由 `TempJanitor` 回收，不再散落在源 APK 同目录。

`taffy_file_replace` 的描述去掉了从未实现的 `mode=lines`；`taffy_manifest_xml_edit` 的反射
setter 改为方法名白名单（原来按「名字含 set 且有 String 参数」随机挑，命中 `setPackageName`
就会把整段 XML 写进包名）。

## 四、界面与操作

最大的一条是状态保活：分析结果、AI 对话、当前工作区原来都挂在 `remember` 上，而 MainActivity
没有 `configChanges`——旋转一次屏幕就全清空，得重新选文件重跑。现在这些对象进了 ViewModel，
tab、设置子页、62 处输入框、抓包筛选条件改用 `rememberSaveable`。

顺带修了几处看得见的小毛病：深色模式下状态栏仍是浅灰（XML 写死且代码从未动态改写）、
App 字号设置对显式 `fontSize` 的文本完全不生效、智能体编排动作行在窄屏被挤出屏幕、
结果卡片关闭按钮只有 22dp、AI 对话输入框被软键盘遮住、编辑器语法高亮跟的是系统主题而不是 App 主题。

# TaffyNiHe v1.3.109（运行恢复 · MCP 传输层保护 · 统一插件平台）

上一版把编排做出来了，这一版补的是「跑起来之后」的三件事：中断了能不能接上、
超大响应会不会把客户端弄崩、插件到底装成了什么样。

## 一、运行恢复（断点续跑）

以前进程被杀，那次编排就只能从头重跑。

- **消息历史逐调用落盘**：引擎每执行完一个工具调用就回调一次检查点，
  `filesDir/agent-runs/<runId>/nodes/<nodeId>.history.json` 里存完整的
  `role + parts(text/reasoning/tool 四元组)`；写盘走 `tmp + rename`，被杀不会留半截 JSON
- **恢复不重放已完成的部分**：续跑时如果最后一轮 assistant 还有没跑完的工具调用，
  先补完它们再请求模型——否则会把同一个请求重发给模型，导致重复调用
- **节点级跳过**：`resume` 只跳过 `status == ok` 的节点，失败/超时/预算跳过的一律重跑。
  续跑的意义是把没做完的做完，不是把失败结果固化下来
- **入口**：`taffy_agent_run action=resume runId=...`；编排页在运行结束后出现「继续上次」按钮，
  历史列表里点开任意一次也能续跑

> 边界说清楚：工具调用是逐条落盘的，所以「已经执行完的只读工具」不会重跑；但如果在
> **落盘之前**被杀，那一条会重放一次。子代理的角色白名单里刻意没有写入类工具，正是为此。

## 二、MCP 传输层保护 + stdio 桥

- **单条响应尺寸闸门**：`/mcp`、SSE、legacy 三条通道统一检查，单条响应超过上限
  （默认 150 万字符，可由 `toolResultMaxChars` 抬高）时把整条响应落盘，
  只回一条**结构合法**的 JSON-RPC 摘要 + `x-taffy-spilled(savedTo/originalLength/cap)`。
  工具级落盘默认是关的，这一层是兜底——否则某个工具直接吐几 MB，客户端解析就崩了
- **stdio 桥**（`assets/mcp/stdio_bridge.py`，设置 → 服务 → 「导出 stdio 桥」）：
  让只支持 stdio 的 MCP 宿主（Claude Desktop 等）用上塔菲。桥跑在**电脑侧**，
  把 stdin 的 JSON-RPC 行转发到手机 HTTP 端点，自动识别 SSE 的 `data:` 行，
  单条响应超阈值同样落盘，日志只走 stderr。

  ```
  python3 stdio_bridge.py --url http://127.0.0.1:8765/mcp
  ```

  为什么桥不在 APK 里：Android 应用没有可被外部连接的 stdin/stdout，桥放电脑侧是唯一稳的形态。

## 三、统一插件平台

以前插件逻辑全在设置页里，UI 与实现绑死，MCP 侧完全看不到插件。

- **`core/PluginManager`** 成为单一事实来源：
  - `list` 扫 `filesDir/plugins`，解析 `meta.json` → 统一清单（id/名称/版本/入口/权限/设置项数/问题列表）
  - `diagnose` 健康统计 + 逐条 issue + 权限直方图：缺入口、版本不兼容、meta 损坏、权限未声明，一次列全
  - `checkRunAllowed` 运行前硬校验；`recordRun`/`audit` 记录每次运行的成败与摘要（保留 40 条）
- **权限模型**：`meta.json` 可声明 `permissions: ["tools:taffy_so_*", "files:read", "net:outbound"]`，
  匹配支持通配；**未声明一律不放行工具调用**（老插件按只读文件对待，只提示不阻断）
- **`taffy_plugin`**（action=list/diagnose/check/audit）：只读，不安装不卸载——装什么由用户决定
- 设置页插件 tab：运行前自动校验、运行后自动写审计、新增「诊断」按钮把结论直接打到输出区

## 四、单测

- `PluginPlatformTest`：版本比较（数字而非字典序、缺失段按 0 视为相等）、
  权限通配与**默认拒绝**、清单序列化
- `AgentOrchestrationTest` 扩到 14 例：历史编解码往返（未完成的调用必须保持未完成）、
  畸形条目跳过、`run.json` 计划与结果反向解析

---

# TaffyNiHe v1.3.108（多智能体编排 · 状态栏避让修复）

这一版加的是**编排**，不是又塞一个万能 agent。

以前的「AI 深度分析」是一个 agent 从头做到尾：上下文越跑越长，工具面越用越杂，跑到一半
就开始遗忘前面的证据。多智能体编排换了个思路——把目标拆给几个专职子代理，每个只拿到
自己那组工具，各干一段，最后汇总。

## 一、六个专职角色

| 角色 | 职责 | 工具白名单 |
|---|---|---|
| `recon` | 摸清形状：类型/架构/入口/段布局/依赖/是否加壳 | so_open / analyze_elf / read_stats / apk_analyze / meta_info |
| `strings` | 字符串侧取证：可疑字符串、交叉引用、密文段、导入归因 | search_strings / string_xref / so_string_decrypt / so_import_trace |
| `code` | 代码层：可疑函数、反汇编、伪 C、签名、JNI 注册表 | analyze_functions / read_disasm / so_decompile / so_func_sig / so_jni_register |
| `flow` | 控制流：调用关系、CFG 形态、平坦化、能否还原 | analyze_cfg / analyze_xrefs / so_obfusc_scan / so_deflatten |
| `packer` | 加固与脱壳：厂商与证据、反调试点、dump 与修复 | so_packer_fingerprint / so_anti_debug / apk_shell_check / unpack |
| `report` | 汇总：交叉印证、写结论 | （无工具） |

白名单是**硬约束**，在工具打包处过滤——模型根本看不到白名单外的工具，不是靠提示词劝它别乱用。

## 二、编排机制

- **计划先行**：`action=plan` 只出 DAG（谁做什么、依赖谁），不花钱不动文件，可以先看再跑
- **并行调度**：拓扑分层，无依赖的节点并发（并发度 2）；`flow` 依赖 `code`（要先知道有哪些函数才看 CFG）；`report` 独占最后一层，输入是全部前置结论
- **上游结论下传**：每个节点拿到的不是共享对话历史，而是前置节点的「结论 + 证据条目」，上下文短且可控
- **证据契约**：子代理必须输出 `EVIDENCE: [{claim, where, confidence}]`，`where` 要求是地址/符号/段名/字段路径。解析失败就退化成文本摘要，不假装成功
- **预算三件套**：子代理数（≤8）/ 工具调用数（≤400）/ 总时长（≤30 分钟）。超限的节点标 `skipped_budget` 并在结果里说明，不静默截断
- **异步运行**：`run` 立刻返回 `runId`，`status` 轮询增量进度（每节点状态/工具调用/流式输出），`cancel` 真能停——不会被 MCP 客户端超时掐断后任务还在后台跑
- **全量落盘**：`filesDir/agent-runs/<runId>/` 下写 `run.json`（计划、每节点状态/耗时/工具/证据）与 `conclusion.md`；单节点报告 > 8KB 时单独落 `nodeId.md`，结果里只回路径

## 三、界面

分析页 ⋮「塔菲工具」→ **多智能体**：

- 目标输入 + 角色多选（FilterChip）+ 预算切换
- 运行区按节点一张卡：角色 / 状态 / 耗时 / 工具调用序列（失败标 ✗）/ 实时输出尾部
- 结论区用 Markdown 渲染
- 历史区列出以往运行，点开回看

**没有原始 JSON 出口**——排查看的是执行过程，不是读字段。

## 四、修复：状态栏重叠

`targetSdk=36` 起系统强制 edge-to-edge，但主 Scaffold 被显式设成
`contentWindowInsets = WindowInsets(0, 0, 0, 0)`，等于把避让责任推给每个页面——实际只有
分析页、编辑器、首页零星加了 `statusBarsPadding` / `safeDrawingPadding`，任务页、设置、
日志、工具清单全都顶到状态栏下面。

现在改为 M3 默认（`systemBars`），由 Scaffold 的 `innerPadding` 统一给出状态栏与导航条避让，
同时移除各页面自己的 padding（避免双重间距）。全 App 一致，不会再出现某页漏掉。

## 五、单测

`AgentOrchestrationTest`：计划结构（recon 先行 / flow 依赖 code / report 聚合 / 角色过滤 /
未知角色忽略）、证据解析（正常 / 垃圾输入 / 缺 claim 丢弃）、预算（额度、超时、取消）、
角色定义与提示词契约。

## 关于太墟 v1.0.0（2026-10-17）

对照其发布说明，本版采纳了这几点设计：

- **上下文预算与产物引用恢复** → `AgentBudget` + 报告落盘 + 结果里带 `reportPath`
- **交付证据机制** → `EVIDENCE` 契约（每条结论必须能定位）
- **取消标记** → `cancel` 走协程取消 + 预算取消双通道（太墟踩过「取消标记粘死」的坑，这里改成每次运行独立 budget 实例，不复用标记）

暂未采纳：

- **运行恢复（进程被杀后续跑）**：塔菲已支持事后回看 `run.json`，但中途续跑需要把每个节点的消息历史持久化，会让落盘体积翻几倍；等真有人跑长任务再上
- **mcp STDIO 超长行落盘**：塔菲的 MCP 服务端已有工具大输出落盘，通道不同但目的相同
- **统一插件平台**：塔菲的扩展系统已经覆盖（Xed 格式兼容 + 声明式设置 + minAppVersion）
- **移除液态玻璃主题**：塔菲本来就只有 M3

---

# TaffyNiHe v1.3.107（静态深挖：加密字符串 · JNI 注册表 · 导入归因 · 加固结构指纹）

这一版把逆向现场「只能靠猜」的三件事变成了「可以直接查」，另外把加固判定从
「字符串像不像」推进到「ELF 结构哪里不对」。所有新能力都是纯静态、只读工作区、
不执行目标代码。

## 一、加密字符串辅助解密（`taffy_so_string_decrypt`）
字符串被 OLLVM `struc` / 商业壳 / 手写 XOR 桩加密后，`.rodata` 里只剩乱码，过去只能靠动态调试读内存。

- **扫描**：遍历已分配、非可执行的段，挑出「既不是可打印文本、也不是零填充」的字节段，按熵排序（熵只用于排序，不做过滤 —— 短字符串即使加密熵也低，按熵过滤恰会丢掉最该救的那几条）
- **恢复**：穷举全部 256 个单字节 XOR / 加 / 减 / 循环移位，外加调用方提供的多字节 XOR 与 RC4 密钥；用「可打印率 ≥ 0.75 且像语言比率 ≥ 0.70」双闸门判真
- **置信度**：high（调用方给的密钥通过闸门）/ medium（唯一最优且样本 ≥ 32 字节）/ low（通过闸门但有并列）/ none；并列数一并返回，不把「碰巧可打印」当成答案
- **边界直说**：运行时派生的密钥、AES/白盒、表驱动 S 盒这三类证不出来，`hints` 会说明该转到动态读内存或 unidbg

## 二、JNI 注册表恢复（`taffy_so_jni_register`）
`RegisterNatives` 之后函数表没有符号，过去只能看到一串 `sub_xxxx`。

- 在只读数据段里按 `{name*, sig*, fnPtr}` 三元组恢复 `JNINativeMethod` 表，再用 `Java_*` 符号反推所属类
- 输出「Java 方法 → (签名, 函数地址, 所属类)」，支持按方法名 / 类名 / 地址反查
- 明确标注：`symbolExact`（本身就有 `Java_*` 符号，无需注册）、`thumb`（arm32 低位标记）、无符号覆盖的条目只能按地址访问
- 运行时才拼装的表、加密的方法名字符串会标注为「恢复不到」而不是猜一个

## 三、导入归因（`taffy_so_import_trace`）
`DT_NEEDED` 只说明依赖哪些库，不说明符号来自哪个库。

- 读 `.gnu.version` / `.gnu.version_r`（`DT_VERNEED`），把每个 dynsym 的版本索引映射回版本名与提供库，再给每个库贴角色（liblog=日志、libssl=网络加密、libc++_static=C++ 运行时…）
- 按能力分组（调试/反调试、网络、加密、JNI、动态加载、文件 IO、进程线程），「这个 SO 会不会联网 / 会不会 dlopen 自解密 / 带不带反调试」一眼可答
- 版本表缺失时退化为「按 `DT_NEEDED` + 符号名推断」，每条记录标明来源模式，不把推断当确证

## 四、加固结构指纹（`taffy_so_packer_fingerprint`）
与文件层特征扫描互补：那个回答「像不像某壳」，这个回答「根据哪些结构特征、确定性多高」。

- 从段名、节名异常、符号命名风格、导入符号组合、代码段熵分布打分，输出厂商 + 置信度 + 逐条证据
- 加固报告页新增「结构层判定」卡片：不看字符串，只看 ELF 结构；字符串层与结构层结论并列，互相印证

## 五、混淆度批量扫描（`taffy_so_obfusc_scan`）
不必再猜哪几个函数被平坦化。

- 按符号体积取最大 N 个函数，逐个做 CFG 形态分析：分发器块（入度达阈值且本体极薄、排除入口块）、平坦化因子、假边比例、薄块比例
- 三档判定 `clean` / `suspected` / `flattened`；证据不足时上限就是 suspected（编译器生成的公共跳转目标会像分发器）
- 分析页新增「混淆扫描」视图：结论统计 + 逐函数指标，可逐步扩大扫描范围（40 → 80 → 120）

## 六、ELF 层反调试点定位（`taffy_so_anti_debug`）
字节层工具回答「命中哪些特征」，这个回答「命中的是哪条路径 / 哪个字符串 / 哪个跳板」。

- 三类证据：导入符号（ptrace / getppid / inotify / frida 相关）、字符串常量（`/proc/self/status`、`TracerPid`、`gum-js-loop`…）、宿主导出函数被 hook 的跳板特征
- 每条带分类 + 技术名 + 证据内容，动调前可逐点绕过

## 七、分析页新视图「密文恢复」
左栏密文段（地址 / 长度 / 熵 / 头部 hex），点开右栏给出排名候选（算法 / 密钥 / 可打印率 / 语言相似度）与明文，可一键复制。
**没有 JSON 兜底出口** —— 数据是什么形状就怎么渲染。

## 八、修复
- **分析完莫名退出（堆崩溃）**：`rzAnalyze` 在统计完函数个数后对 `rz_analysis_function_list()` 的返回值调了 `rz_list_free()`，但那是 `RzAnalysis` 内部链表（`analysis->fcns`）的借用引用，不是新分配的链表 —— 随后的 `rz_core_free → rz_analysis_free` 会二次释放同一链表，Scudo 报 `corrupted chunk header` 后 SIGABRT。现在只读长度、不再释放（同文件其余 `rz_list_free` 释放的都是新分配链表，写法本来就对）
- **更新包下错 ABI**：`selectApk` 原来把所有 ABI 名铺平后对资产列表取第一个命中项，等于「资产上传顺序 > 设备优先级」，且 `x86` 子串会命中 `x86_64`。改为按 `Build.SUPPORTED_ABIS` 优先级逐个匹配、用 `-<abi>.` 整段判断
- **R8 水平合并**：给 `IntegrityGuard` / `SelfArtifactGuard` / `NativeProbe` 加 `-keep`，避免被并进相邻类后 mapping 里没有独立条目
- **架构门禁**：`AnalysisWorkspace.kt` 已登记在基线里、只允许收缩。本次把加固报告的纯逻辑（`HardFinding` / 特征表 / `hardMatch` / `shannonEntropy` / `hardVerdict` / `hardIntegrity` / `hardSteps`）迁到 `AnalysisDeepViews.kt`，文件从 13823 行降到 13706 行

## 九、单测
新增 6 份 JVM 单测（合成 ELF 镜像，用真实字节布局而不是 mock）：字符串解密 / JNI 注册表 / 导入归因 / 加固指纹 / 混淆度 / 反调试点。

## 关于本版没有采纳的上游改动
同源的 SOMCP v1.0.22 里还有两项本版**故意不做**：

- **「检测到清风 / metk.hub 等过签工具即闪退」**：那是二改分发版的防扩散手段。塔菲面向逆向工作者，机器上装着 LSPatch / 过签工具是常态，做成闪退会误伤正主
- **子代理派生（agent_api 网关）**：需要先把多轮任务编排的收益算清楚，不在这一版草率塞进工具面

---

# TaffyNiHe v1.3.106（会话恢复 · 脚本引擎 · A2UI · 注入探针）

## 一、编辑器会话恢复（借鉴太墟 SessionFork / checkpoint / rewind）

以前编辑器只有单档 `.bak`，改错了只能靠撤销栈，关掉就没了。

- **checkpoint 栈**：文本停止变化 8 秒后自动压一份、手动保存/另存各压一份，`filesDir/editor-checkpoints/<路径哈希>/`；内容相同不重复存，环形保留 20 份 / 总 4MB 上限
- **rewind**：工具条新增 `⟲` 历史按钮，点任一份即回退；回退前先把当前内容压一份「回退前」，可以再前进回来
- **外部修改冲突检测**：打开文件时记录 `(mtime, size, sha256)`；保存前比对，若被 MCP 工具 / 终端 / git 改过，弹「文件已被外部修改」对话框，提供 **放弃 / 另存 / 覆盖** 三选一（另存写到 `editor_files/<名>-<时间戳>.<ext>`，原文件一字不动）
- **崩溃恢复**：未保存的标签正文写 `autosave.json`（单标签 > 200KB 只留标题并标注）、元信息写 `editor_session` prefs；下次进编辑器弹「恢复上次编辑会话 / 忽略」

三者与原有 `.bak` 并存，职责分开：`.bak` 是 diff 基线的「最近一档」，checkpoint 是「多历史点栈」，autosave 是「未保存正文」。

## 二、codemode 脚本引擎 `taffy_script`（借鉴太墟）

以前多步分析要模型逐个 tool call 往返。现在可以一段 JS 编排：

```js
const list = toolJson('taffy_so_close', { action: 'list' });
const names = (list.workspaces || []).map(w => w.name);
log('工作区 ' + names.length + ' 个');
return names.filter(n => n.endsWith('.so'));
```

- **沙箱**：Rhino 1.7.15（纯 JVM，1.4MB）+ `initSafeStandardObjects` + `ClassShutter{false}` + 解释模式。`java / Packages / getClass / load / readFile / XMLHttpRequest / setTimeout` 全部 `undefined`，原型链逃逸也拿不到宿主对象 —— 脚本唯一的能力就是通过 `tool()` 借用已注册工具
- **API**：`tool(name, args)` / `toolJson(name, args)` / `log(...)` / 全局 `args`；用 `return` 返回结果
- **配额**：默认 8s 超时（上限 30s，线程 join + 指令计数观察者双保险，超时用 `Error` 子类硬中断）、默认 50 次工具调用（上限 200）、logs 64KB / result 256KB 截断
- **失败不外抛**：工具级失败 → 脚本内可判断的 `{"ok":false,"error":{code}}` 字符串；脚本级错误 → `SCRIPT_ERROR` 带行号；递归调用 `taffy_script` 被拒绝

## 三、A2UI 声明式界面（借鉴太墟 `render_surface`）

模型不再只能吐大段 Markdown —— 可以在回复里用 ```` ```surface ```` 围栏返回一份界面描述，客户端渲染成**原生 Compose 卡片**：

```surface
{ "version": 1, "root": { "type": "card", "props": {"title":"扫描结果"}, "children": [
  { "type": "keyvalue", "props": {"rows": [["架构","arm64"],["大小","1.2MB"]]} },
  { "type": "table", "props": {"columns":["名字","地址"],"rows":[["libfoo.so","0x1000"]]} },
  { "type": "alert", "props": {"tone":"warn","text":"检测到加固"} }
] } }
```

- **11 种组件**：card / text / keyvalue / table / code / chips / alert / progress / button / list / divider，全部映射到 M3 官方组件（`Card`/`ListItem`/`AssistChip`/`LinearProgressIndicator`/`OutlinedButton`…），只用主题 token
- **安全限额**：节点 ≤ 200、深度 ≤ 8、单字符串 ≤ 4000、每层 children ≤ 50，超限直接失败而不是给半截界面；未知组件跳过并累积 warning
- **降级**：解析失败 / 流式中（JSON 没写完）一律回退 Markdown，绝不黑屏
- **工具**：`taffy_render_surface` 可让模型先校验 spec 再输出，错误带 `path` 便于自我修正

## 四、注入痕迹探针（对齐上游 SOMCP v1.0.22）

设置 → Linux 环境诊断页新增「注入痕迹探针（只读）」：

- **Xposed 系**：`/proc/self/maps` 匹配 lsposed/edxposed/xposed/sandhook/yuki/riru/zygisk（只记映射文件名，不贴整行），以及 `XposedBridge` / `XposedHelpers` / LSPatch `HookBridge` 等类的可加载性
- **LSPatch / 二次打包**：自身 APK 的 ZIP 条目里查 `assets/lspatch/`、`META-INF/xposed/`、`assets/xposed_init`；`sourceDir` 落在 `/data/local/tmp`、`/sdcard` 等异常路径
- **标记位**：debuggable / allowBackup / testOnly

**只记录、不阻断**：结果**不参与**完整性门禁、不影响任何功能、不杀进程 —— 因为本 App 的用户就是要开 root / LSPosed / Frida，误伤比漏报更糟。刻意**不**把 `frida` / `gadget` 写进关键字表。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.105...v1.3.106

---

# TaffyNiHe v1.3.105（响应式三态 · 全量 ZIP 备份 · 架构门禁）

## 一、WindowSizeClass 三态响应式布局（借鉴太墟）

以前手机、折叠屏展开、平板一律用底部导航，宽屏浪费一半空间。

| 宽度尺寸类 | 导航 | 内容 |
|---|---|---|
| **Compact**（手机竖屏） | `NavigationBar` 底部导航（与改前**逐像素一致**） | 原样 |
| **Medium**（折叠屏展开/小平板） | **`NavigationRail`** 左侧垂直导航栏（图标 + 常显文字） | 占满其余宽度 |
| **Expanded**（平板横屏/大折叠） | 同上 | 额外 `widthIn(max = 1200.dp)` 居中，避免超宽屏文字拉成一条线 |

导航项与 AppBottomNav 完全一致（首页/分析/任务/编辑器/设置）；tab 切换、编辑器常驻保活、离开确认、子页自动隐藏导航等逻辑**一行未改**。

## 二、全量 ZIP 备份 / 恢复（借鉴太墟）

以前只能备份**设置 JSON**。现在新增「全量备份」：

- **打包内容**：`prefs/`（SharedPreferences，**敏感 key 一律跳过**）、`tasks/`、`extensions/`、`edit-snapshots/`、`workspaces/`（含 `cacheDir/picked_*`），加一份 `manifest.json`（格式版本/时间/条目统计/跳过清单）
- **脱敏**：key 名含 token / secret / password / key / credential / cookie / session 的**整条跳过**（日志与摘要里只有 key 名，绝无 value）
- **加密可选**：复用现有 `BackupCrypto`（AES-256-GCM + Argon2id），输出 `.bak.enc`
- **恢复**：先校验 manifest → **自动生成 `pre-restore-<ts>.zip` 快照** → 两遍流式恢复（prefs → 其它 → workspaces）→ **失败自动回滚**并提示回滚文件路径
- **安全**：`safeResolve` + `canonicalPath` 前缀校验防 ZipSlip；单文件 > 20MB / 条目 > 20000 自动跳过并报告

## 三、架构门禁（policy 驱动，借鉴太墟）

新增 `tools/arch-policy.py`，接在 CI 的单元测试之后：

1. **文件尺寸棘轮**：> 4000 行 WARN，> 8000 行 ERROR；存量超限文件登记在 `tools/arch-baseline.json`，**只允许收缩不允许扩张**（变胖即 ERROR）
2. **包依赖无环**：解析本项目 import 建包级有向图，DFS 检出环，基线内的存量环豁免、**新增环即 ERROR**
3. **分层方向**：`core < engine < mcp < ui`，低层不得 import 高层，基线例外可登记
4. **禁止项**：单文件 TODO/FIXME > 20 处、应用代码里直接 `println(`

首次运行自动自举基线（历史债务不红 CI），`--update-baseline` 可重新登记。

> 顺带发现两个存量问题（已进基线/降级为 WARN）：`AnalysisWorkspace.kt` 单文件 **13823 行**（全仓最大）、`ToolPagesState.kt` 有一处 `println(`。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.104...v1.3.105

---

# TaffyNiHe v1.3.104（工具大输出落盘）

## 大工具输出不再"一断之了"（借鉴太墟）

以前当工具返回的内容超过 `toolResultMaxChars` 时，服务端直接回一个
`RESULT_TRUNCATED` **错误**，调用方除了前 N 个字符**什么都拿不到** —— 大反汇编、
大函数列表、大 ELF 概览这类结果基本等于白跑。

现在改为**落盘 + 预览 + 路径**：

- 完整结果写入 `filesDir/tool-results/result-<时间戳>.json`（自动保留最近 20 份）
- 响应体变为成功结构，带：
  - `savedTo`：落盘文件的绝对路径
  - `readHint`：提示用 `taffy_file_read(path=...)` 读取全文
  - `preview`：前 `toolResultMaxChars` 个字符（AI 可先看开头判断要不要取全文）
  - `originalLength` / `shownLength` / `truncated`

这样"结果太大"从一个失败变成一个**可继续的动作**。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.103...v1.3.104

---

# TaffyNiHe v1.3.103（内置 rz-ghidra：伪 C 的 Ghidra 反编译器后端终于可用）

## 内置 rz-ghidra 反编译器插件（libcore_ghidra.so）

此前 APK 里**没有** `libcore_ghidra.so`：`rizin_core.cpp` 会去 `nativeLibraryDir` 找它，
找不到就只在日志里留一句 `ghidra plugin missing at .../lib/<abi>/libcore_ghidra.so`，
伪 C 的 **`pdg`（Ghidra 反编译器）后端永远不可用**，只能回退到 Exbin 自研反编译器。

这一版把它补上了：

- 新增 `tools/build-rz-ghidra-android.sh`：为**四个 ABI**各自构建
  1) 一份 **SHARED Rizin SDK**（rz-ghidra 的 CMake 走 `find_package(Rizin)`，
     而 `build-native-android.sh` 只产静态归档，所以必须单独装一份带 `RizinConfig.cmake` 的）
  2) **rz-ghidra v0.9.0**（与仓库钉的 rizin **v0.9.1** API 对齐；用 master 会报
     `requires 2 arguments, but 3 were provided`）
  3) 把 core 插件以固定名 `libcore_ghidra.so` 放进 `jniLibs/<abi>/`，并把它依赖的
     `librz_*.so` 一并打包（否则运行时 dlopen 失败）
- CI `prep` job 接线 + Rizin SDK 按 ABI 缓存

**踩到的三个坑（都已修）**：
1. bionic 下共享库默认不链接 libm → `librz_bin.so` 报 `undefined symbol: modf`（cross 模板补 `-lm`）
2. rizin 的 `pf_parser.c` 用了 `ldexpf()`（float 版），API<30 的 bionic 不导出（改成 double 形式）
3. rz-ghidra 版本必须与 rizin 对齐（master ✗ → v0.9.0 ✓）

打包 sleigh 规格（`assets/rizin/plugins/rz_ghidra_sleigh`）本就在仓库里，
`applyGhidraConfig()` 运行时会设置 `SLEIGHHOME`，现在 `pdg` 应可直接产出 Ghidra 伪 C。

> APK 体积会相应增加（每个 ABI 多一份 `libcore_ghidra.so` + 共享 `librz_*.so`）。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.102...v1.3.103

---

# TaffyNiHe v1.3.102（按键全部学 Exbin · 加固特征库 56 条 · 上游对齐）

## 一、按键 UI 全部对齐 Exbin（Explorer So）

用户反馈「按键很奇怪，不知道干什么，就是很 AI」。根因：Exbin 每个按钮都是
**「图标 + 短动词」**（`GlowButton` 带 `gb_icon`），而塔菲大量按键是纯文字、且堆在一起无主次。

- **`SmallAction` / `BigActionButton` 增加图标槽**；分析页 **37 处**同类按键统一配图标
  （刷新⟳ / 复制⧉ / 清空🗑 / 删除🗑 / 生成▶ / 全量分析⚡ / 前往→ / 打开📂 / 保存💾 / 重置⟳）
- **`ToolChip` 增加图标槽**；编辑器 **37 条命令全部配图标**（工具条 / 命令面板 / 更多菜单）
- **工具页全面补图标**：Rizin、eDBG、抓包、Git、终端、DEX 浏览、APK 编辑、沙箱、Linux、
  扩展、Blutter、MCP 工具、本地可执行、APK 桥接 —— 主操作按钮与功能 chip 一律「图标 + 文字」
- **文案去噪**：「生成去平坦化计划」→「生成计划」、「扫描整个 SO」→「扫描 SO」、
  「全量分析 (aaaa)」→「全量分析」（内部代号不该给用户看）

## 二、对齐上游 SOMCP v1.0.22

- **完整性守卫消除误报**：`/proc/self/maps` 特征表删掉 `lsposed` / `gadget` / `frida` / `xposed`
  子串（只保留 `gum-js-loop` 这一 Frida 运行时强特征）；删除 **Frida 默认端口 27042/27043 探活**；
  签名一致时**不再展示「期望 / 实际」指纹行**（只在不一致时显示差异）
  —— 正常设备（含装了 LSPosed 的）不再误报「完整性校验失败」
- **更新检查更可靠**：正式版通道由「信任 `/releases/latest`」改为**枚举 releases 取版本号最高的一条**
  （逐段语义化比较，`1.3.101 > 1.3.99`）；新增**双通道取最高**入口与**同版本重新发布也能提醒**；
  新增 `checkLatest()` / `ignoreVersion()`

## 三、加固特征库 7 → 56 条

`taffy_apk_shell_check` 的壳特征表从 7 条扩到 **56 条**，覆盖 360 / 腾讯乐固 / 爱加密 / 梆梆 /
娜迦 / 阿里聚安全 / 阿里云 mPaaS / 百度 / 几维 / 腾讯御安全 / 网易易盾 / 顶象 / 通付盾 / 盛大 /
瑞星 / 启明星辰 / 海云安 / Virbox / AppSealing / LIAPP / DexGuard / DexProtector / VMP 等
**40+ 厂商**；新增 `detectedLibs` 直接列出 `lib/*.so` 文件名，便于人工复核。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.101...v1.3.102

---

# TaffyNiHe v1.3.101（自绘控件收口：页面里不再有手搓的按钮/chip/卡片）

v1.3.100 把 UI **基座**换成了 M3，这一版把散落在各页面里的**残留自绘控件**
逐个收干净 —— 现在页面里不再有「手搓 Box + 圆角 + 背景 + clickable」拼出来的
按钮 / chip / 分段 / 开关 / 卡片。

## 一、设置与工具页
- **更新页**：版本徽标、频道分段（正式版/测试版）→ `AssistChip` / `SingleChoiceSegmentedButtonRow`
- **备份页**：开关行 → `ListItem + Switch`（替换自绘轨道+滑块），动作卡 → `Card`，「恢复」→ `TextButton`
- **服务配置页**：端点卡 → `Card`，「测试」→ `TextButton`
- **日志页签**：动作按钮 → `FilledTonalButton`，底部工具条 → `Surface`

## 二、首页 Command Hub
- 顶部「在线/离线」状态胶囊 → `AssistChip`（leadingIcon 放状态圆点）
- 「全部工具」入口 → `TextButton`

## 三、Markdown 渲染
- 引用块 / 表格容器 → `Surface`
- 分隔线 → `HorizontalDivider`

## 四、分析页（最大单文件）
- 左侧导航栏 → M3 `NavigationRail` + `NavigationRailItem`
- 分段按钮（热点/根展开/完整、完整数据/仅循环）→ `FilterChip`
- 当前任务小条 → `AssistChip`

## 五、日志查看页
- 状态条 / Shizuku 提示 / 通道错误条 / 崩溃记录卡 / 录制文件卡 → `Surface`
- 过滤预设、应用列表行、「删除」→ `AssistChip` / `Surface(onClick)`
- 「实时过滤」折叠头 → `Surface(onClick)`

## 保留自绘（专业视图，M3 无对应组件）
编辑器（行号槽 / 键帽 / 补全面板 / 语法高亮）、控制流画布（节点/边/缩放平移）、
日志正文（等宽着色流 + 深色画布）、终端面板 —— 这些是**为各自数据类型专门设计的视图**，
按既定原则保留；它们的外壳与工具条已统一走 M3 组件。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.100...v1.3.101

---

# TaffyNiHe v1.3.100（全局 Material 3 化：所有页面统一到官方组件）

用户反馈「自研的交互太烂、不符合人类审美」—— 根因是**页面里到处是自绘控件**
（自绘胶囊 chip、自绘 Surface 小按钮、自绘按钮行、自绘列表行、自绘卡片）。
这一版把**整个 App 的 UI 基座换成 Material 3 官方组件**，所有页面随之统一。

## 一、UI 基座全量 M3 化（一处改动，全局页面生效）

| 基座组件（原本自绘） | 现在实现 | 影响范围 |
|---|---|---|
| `GlassGroup` / `AppCard` | **`Card`**（圆角 + 0dp elevation + 1dp 描边） | 24 个文件 · 105 处 |
| `NavRow` | **`ListItem`** | 8 个文件 · 29 处 |
| `ToggleRow` | **`ListItem` + `Switch`**（M3 默认配色） | 10 个文件 · 39 处 |
| `CardRow` / `DataRow` | **`ListItem`** | 16 个文件 · 27 处 |
| `ScreenHeader` | **`TopAppBar`** | 5 个文件 |
| `ToolPageScaffold` | **`Scaffold` + `TopAppBar`** | 3 个文件 · 22 处 |
| `ToolChip` | **`FilterChip`** | 13 个文件 · 41 处 |
| `TypeChip` | **`AssistChip`** | 10 个文件 · 15 处 |
| `GroupDivider` | **`HorizontalDivider`** | 20 个文件 · 150 处 |

## 二、分析页（最大单文件）容器/按钮换芯
`ResultCard` / `KeyValueCard` / `ExbinResultCard` → **`Card`**；
`BigActionButton` → **`Button`**；
`TonalMiniButton` / `ExbinFunctionButton` → **`FilledTonalButton` / `OutlinedButton`**；
`ExbinCheckRow` → **`Checkbox`**。

## 三、首页 Command Hub
`IosGroup` / 工作台宫格 → **`Card`**；`IosRow` → **`ListItem`**；细分隔线 → **`HorizontalDivider`**。

## 四、底部导航
已是 M3 **`NavigationBar`** + `NavigationBarItem`（核对确认）。

## 五、新增《Exbin UI 规范》文档（docs/exbin-ui-spec.md）
把骨架（`TopAppBar` + `TabRow` + 内容）、组件选型表（什么场景用哪个 M3 组件）、
尺寸（56dp 工具栏 / 12dp 行距 / 10–12dp 卡片圆角）、配色（只用 M3 token）、
交互原则（一个动作一个触发点 / 点击=主行为 / 重活给主按钮）固化为基线，
后续所有页面按它对齐。

> 编辑器 / 日志 / 控制流画布 / 终端这类**专业视图**保留其专用自绘界面
> （文本编辑的 gutter、日志的等宽流、画布的缩放平移），因为它们本就没有通用的 M3 对应物。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.99...v1.3.100

---

# TaffyNiHe v1.3.99（分析页整体换用 Material 3 官方组件，去掉自研控件）

之前分析页的控件是**自绘**的（自绘胶囊 chip、自绘 Surface 小按钮、自绘按钮行、自绘列表行），观感与交互都和
Exbin（Explorer So）不一样 —— 这就是"不符合人类审美"的根源。这一批把分析页的控件层**整体换成 M3 官方组件**：

| 旧（自绘） | 新（M3 官方） | 效果 |
|---|---|---|
| `TabChip` 自绘胶囊（主色 15% 底 + 描边） | **`FilterChip`** | 筛选/切换有标准触感与选中态 |
| `SmallAction` 自绘 Surface + 11sp | **`OutlinedButton` / `FilledTonalButton`** | 按钮有标准内边距与最小高度，**文字不再被压成省略号** |
| 交叉引用 4 个自绘按钮行 | **`TabRow` + `Tab`**（Exbin 这页本就是 TabLayout） | 页签有指示条、点击热区、滚动手感 |
| 控制流节点列表自绘 Row | **`ListItem`**（leading 折叠三角 / headline 等宽标题 / supporting 指令数 / trailing「指令」按钮） | 列表项有标准间距与触控反馈 |

另外新增 **《Exbin UI 规范》** 文档（`docs/exbin-ui-spec.md`）：把 Exbin 的骨架（Toolbar + TabLayout + Divider）、
组件选型（哪种场景用哪个 M3 组件）、尺寸（56dp 工具栏 / 12dp 行距 / 10–12dp 卡片圆角）、配色（只用 M3 token）、
以及**交互原则**（一个动作一个触发点、点击=主行为、重活给主按钮）固化成基线，后续所有页面按它对齐。

> 这一批是「组件层」；下一批处理侧栏 / 顶栏 / 各工具页的自绘部分。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.98...v1.3.99

---

# TaffyNiHe v1.3.98（节点列表交互重做 + 树结构对齐 Exbin + 图形化控件换行）

## 1️⃣ 节点列表：展开/收起终于「讲得通」
之前一行上的手势太挤：整行可点（展开指令）+ 三角太小（折叠子节点），两个动作互相抢，就会出现「点了没反应 / 收不干净」。现在每个动作都有**明确的、够大的**触发点：

| 你想做的事 | 点哪里 |
|---|---|
| 折叠 / 展开这个节点的**子节点** | 左侧 **36dp 的 ▶ / ▼** |
| 看这个块的**每一条指令** | 行尾的 **「指令 / 收起」按钮** |

- 行本身不再可点（不会再误触）；
- 点 ▶ 折叠时，顺手收起该行的指令明细，不留视觉残渣。

## 2️⃣ 树结构与 Exbin 对齐（这是「看着不像树」的根因）
Exbin 的 `CfgTreeNodeListFragment.buildTreeNodes` 是**每条出边生成一个子行**：同一个块被多条边指向时会出现**多行**，重复的那份标 `(ref)`。塔菲旧实现是「一块一行」，所以层级、缩进、`(ref)` 的位置都和 Exbin 不一样。
现在完全照 Exbin 来：
- **每条边一行**（重复块标 `(ref)`，用唯一 `rowId` 作 key）；
- 子边顺序 `true → false → next`；
- 折叠按 `rowId`（每个分支独立折叠）；
- 未被任何路径访问的块，补成顶层根（同 Exbin）。

## 3️⃣ 交叉引用·图形化：控件不再挤成一行
「我调用的 / 调用者 / − / 深度 N / + / 重置 / 交叉边」原来全塞一行 —— 窄屏上「交叉边」直接被截掉看不见。现在拆成两行：
1. **我调用的 ｜ 调用者**（等宽两段，一眼看清当前方向）
2. **− 深度 N + ｜ 重置 ｜ 交叉边**

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.97...v1.3.98

---

# TaffyNiHe v1.3.97（节点列表可看每条指令 + 指令计数修正 + 数据引用）

## 1️⃣ 节点列表：点一行就能看到这个块的**每一条指令**
之前点行会直接跳到图形化，指令详情无处可看。现在：
- **点行** → 在行下方**内联展开该块的全部指令**（等宽字体、逐条编号），再点一次收起；
- 三角仍然只管**折叠/展开子节点**（与 Exbin 一致）；
- 不在函数体内、`pdfj` 覆盖不到的块（例如跨函数跳转目标），展开时会**按需拉一次** `pdj` 补上，不再永远显示「0 条指令」。

## 2️⃣ 「N 条指令」的数字不准
根因：`agf json_disasm` 的 `body` 是**按块渲染的文本**，行数不等于真实指令数（可能多算/少算注释、空行）。
现在**优先用 `pdfj`（逐指令，一条一项）**做计数，`agf` 只用来补 pdfj 拿不到的块。

## 3️⃣ 数据引用看不到
`axtj/axfj` 通常分析不出数据/字符串引用（返回空）。现在补第三个来源：**`pdfj` 每条指令自带的 `ptr`（该指令引用的地址）**——
指令里引用了哪个地址，就会出现在「数据引用」里。只在其它来源为空时才拉，不多付分析代价。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.96...v1.3.97

---

# TaffyNiHe v1.3.96（节点列表折叠 / ANSI 泄漏 / 交叉引用页照 Exbin 重排）

这一版对着你发来的截图逐条修。

## 1️⃣ 节点列表「收起 / 展开」还是有 bug
上一版只把**直接子节点**加入隐藏集合 —— 折叠一个节点时，它的**孙节点照样显示**（只藏一层）。
现在改成**父链判断**（对齐 Exbin `isParentExpanded` 的递归语义）：某行可见 ⟺ 它所有祖先都没被折叠。

## 2️⃣ 界面漏出 `[31m[37m` 这种乱码
rizin 的 disasm 输出**带 ANSI 颜色码**（`0x1B + '[' + 数字`），Compose 不认控制符 → 残渣直接漏到界面上。
现在：
- 取指令的命令前面加 `e scr.color=0`（从源头关色）；
- Kotlin 侧再做一次**字符级剥离**（不依赖转义/正则，任何残留都能清掉）。

## 3️⃣ 部分节点还是「0 条指令」
`agf json` 并非每个块都给 `body`。现在 **`agf json_disasm` 与 `pdfj` 线性指令按块区间归并「取并集」**：
agf 有内容就用 agf，没有的用 pdfj 补齐。顺带把 rizin 混进 body 的**注释行**（`; sym.xxx(...)`）剔除，
指令计数与首行摘要都干净了。

## 4️⃣ 交叉引用页「太丑」+「没法看到调用」
按 Exbin `XRefTabFragment` 原版重排（它整页是代码构建的，没有 XML 可抄，已从源码逐项对齐）：
- **页签**：4 个**等宽圆角按钮**（高 36dp，选中 primaryContainer），不再是流式 chip + 行尾按钮的挤压排布；
- **默认页签 = 外部引用（调用者）**（对齐 `currentTab = TAB_EXTERNAL`），不再默认落在空荡荡的图形化页；
- **搜索框**圆角 28dp，文案「搜索函数名称或地址」，仅列表页显示（图形化页隐藏）；
- **状态行**文案照抄 Exbin：「找到 N 个外部引用 (调用者)」/「找到 N 个内部引用 (被调用)」/「找到 N 个数据/字符串引用」；
- **图形化页控件行**顺序照 Exbin：`我调用的 / 调用者` → `− 深度 N +` → `重置` → `交叉边`，且只在图形化页出现；
- **邻域画布的根节点匹配修好了**：旧实现拿 `0x16c8`（地址）去比函数名 → 永远匹配不上 →「无调用关系」空屏。
  现在按**地址**匹配图节点（`offset == 值`），图形化能正常展开邻域。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.95...v1.3.96

---

# TaffyNiHe v1.3.95（控制流节点列表 / 交叉引用 / 按钮布局修复）

这一版修用户实测反馈的 5 个问题，全部对着 Explorer So 原版源码逐条核对。

## 1️⃣ 控制流「节点列表」收不回去、一点就跳走
- **树序错误**：树行原先按**块下标**输出，而折叠靠顺序向下传播「隐藏」——父块下标大于子块下标时折叠就失效。
  现改为 **BFS 序输出**（父一定在子之前），折叠/展开全部即时生效。
- **点击语义**：原先整行一个 `clickable`，点哪都「折叠 + 跳图形化」。按 Exbin `CfgNodeListFragment`
  改成 —— **点三角只折叠/展开**，**点行才跳图形化并高亮**（`ViewHolder.toggleView` 与 `rootItem` 是两个独立点击区）。

## 2️⃣ 「N 条指令」恒为 0
根因：rizin 的 **`pdfj` 顶层是线性指令数组**（`[{"offset":..,"disasm":..}]`），**没有 `blocks` 字段**；
而旧解析按 `{blocks:[…]}` 取值，永远解析不出东西。现在改用 **`agf json`**（块级图，节点带 `body` 反汇编）
一次拿齐「块 → 指令」，并用 `pdfj` 线性数组**按块区间归并**作为兜底。

> 顺带纠一个长期误解：rizin 的 `agf`/`agC` 是**格式参数型**命令（`agf json`），**不存在 `agfj` 后缀写法**。

## 3️⃣ 交叉引用仍「没有找到调用关系」
- **主来源换成全局调用图**：`axtj/axfj` 读的是当前 core 的 xref 分析库，`rzCommand` 每次都是全新 core、
  分析状态不稳定；现在优先用 **`agC json`（FUNCALL 图，native 侧自带 `rz_core_analysis_all`）**
  推导 callers/callees —— 这是最稳的一条路。
- **native 方向过滤**：native 同时返回 incoming/outgoing，原先不区分，会把「我自己」混进调用者列表；
  现在按方向严格过滤。
- **地址 hex 化**：`axtj/axfj` 只有 `from/to`（十进制数字），旧代码直接把数字当名字显示；现在统一转 `0x…`。

## 4️⃣ 「重新分析」按钮文字显示不全
子页签行改成**可横向滚动**，右侧改用**固定尺寸的图标按钮**（对齐 Exbin 工具栏 `btn_*` 用 IconButton 的写法），
不再被 4 个子页签挤成省略号。

## 5️⃣ 布局/操作细节
同上，节点列表缩进、三角/标题/副标题、交叉引用工具栏均按 Exbin 原版重新核对。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.94...v1.3.95

---

# TaffyNiHe v1.3.94（调用图跨模式切换：第二次切完整模式不再卡住）

上一版完整模式「能控制了」，但只在**第一次**切换后成立 —— 这次把根因修掉。

## 🔁 为什么第一次不卡、第二次卡
切换模式时三个重活全都会重跑一遍，而它们都没有任何缓存：

| 重活 | 位置 | 成本 |
|---|---|---|
| 调用图 → CFG JSON 拼接 | UI 线程 | 1500 节点级 JSON 字符串 |
| JSON → 图结构解析 | UI 线程 | 几 MB `JSONObject` 解析 |
| 分层布局 | 后台线程 | 大图可达数秒 |

同时，旧的布局任务是**纯计算、没有挂起点**，`LaunchedEffect` 重启并不能让它停下来 —— 它会把
`Dispatchers.Default` 占住跑完。第一次只有一个任务，感觉还行；第二次切换时是「重算 + 旧任务仍在跑」，
就卡住了。

## ✅ 这次的修法（三条一起上）
1. **跨模式结果缓存（LRU）**：解析结果与布局结果都按「引擎 / 方向 / 块行数 / JSON 指纹」缓存，
   切回已算过的模式**直接命中**：0 解析、0 布局。命中时状态同步就位，不闪加载态、也不残留上一个模式的图。
2. **布局可协作式取消 + 代次校验**：布局函数每 64 个节点检查一次 Job 状态，切走后立刻退出、不再占线程；
   结果带代次，只有最新一代允许写回状态。取消异常不再被 `runCatching` 吞掉（旧写法会把布局置空 → 白屏）。
3. **渲染降级补漏**：网格点加屏幕空间最小间距（16px）—— 中等缩放下原先每帧要画 1~2 万个圆点。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.93...v1.3.94

---

# TaffyNiHe v1.3.93（画布可用性第二轮：完整模式可交互 / TB·LR 不再重叠 / 交叉引用可用）

上一版修了一半、这一版把剩下三个问题修到底。

## 🖐 完整模式终于「控制得了」—— 这次是渲染成本，不是布局
- 上一版把 **布局** 挪到了后台并加了 ELK 闸门，但完整模式仍然卡手 —— 因为真正的瓶颈在**绘制**：
  完整模式是**全量图**（几千节点 / 上万条边），Compose 画布每帧要把每条边走一遍路径计算 + 箭头，
  一帧几十毫秒 → 手势被彻底压死。
- 对策：
  - 交互画布把完整模式上限收到 **1500 节点**（Exbin 用的是自绘 View + dotMode 才敢开 50000）；
    统计行的「显示 N / 总数」会如实标出被截断的情况，选项里显式填更大的值则按其值（最多 5000）。
  - `drawCfgScene` 增加渲染降级：边 > 1200 条时**只画线不画箭头**，> 3000 条时**整段跳过边**
    （缩到 0.42 以下本来就已降级为圆点，边也看不清）。
- 布局 + 缩放 + JSON 缓存等上一版的修复继续生效。

## 🔁 TB/LR 与缩放按钮：这次做了精确避让
- 按钮从外部 `Box` 移进画布内部（与右下角工具条**同一坐标系**），据此做**精确避让**：
  可用高度 < 工具条高（竖排 304dp / 降级两列 184dp）+ 44dp + 24dp 时，TB/LR 让位到左上角。
- 也就是说：两者**在几何上不可能再重叠**（工具条自身也仍会在矮画布上降级为两列）。

## 🔗 交叉引用「没有找到调用关系」—— 是两个真 bug
1. `axtj` / `axfj` 读的是**分析库**里的交叉引用，没跑过函数 & 调用分析时**恒为空**
   → 查询前先补一次 `s <loc>; af; aac`；
2. 更致命的是类型过滤：native 侧 `xrefTypeStr` 把**函数调用**记成 `call`、把分支记成 `code`，
   而过滤器只认 `CODE` → **所有调用都被过滤掉了**，改成 `CODE | CALL`；
3. 另外把原生 **BL/B 扫描**（`rzXrefs`，自带完整分析 + 对 PIC 代码直接回填分支目标）的
   调用方/被调方结果一并合并去重，作为兜底来源。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.92...v1.3.93

---

# TaffyNiHe v1.3.92（画布交互修复：ELK 卡死 / 模式与上限对齐 Exbin / 根下钻偏移 / 汇编与节点列表）

本版是**画布交互与 Exbin 对齐**的集中修复：调用图「切到完整模式后图控制不了」、
根下钻「跳转后整图跑到右下角」、控制流「节点列表 0 条指令」、汇编视图列错乱，全部定位到根因并修掉。

## 🚀 画布不再卡死：ELK 规模闸门 + 布局移出 UI 线程
- **根因**：ELK（layered + 正交路由）此前在 composition（**UI 线程**）里同步执行，而其耗时随图规模**超线性爆炸**。
  实测（工程内 ELK 0.9.1，复刻 `ExbinElk.layout` 参数）：120 节点/200 边 ≈ 0.5s，120/600 ≈ 2.5s，
  **120/1500 ≈ 19s，300/800 ≈ 5s，800/2000 ≈ 86s**（边数是主因）。热点模式取的是「度数最高的节点」
  （彼此多半不相连 → 稀疏），完整模式取的是「节点序列前 N 个」（通常整段落在一个紧密调用簇里 → 稠密），
  所以只有切到完整模式才会卡死 —— 表现就是**整页收不到任何手势，连切回热点都点不动**。
- **修法**：新增规模闸门 `ELK_MAX_NODES=150 / ELK_MAX_EDGES=400`，超限自动回退自研线性分层布局（O(n+e)）；
  布局整体移到 `Dispatchers.Default` 后台计算，期间显示「正在布局…」而不是白屏/僵死。
- 调用图的 CFG JSON 改为缓存（此前每次重组都重新拼字符串，大图下手势直接卡死）。
- 画布缩放钳位下限 `0.12 → 0.02`（fit / 手势 / 缩放按钮三处），大图也能一次看全。

## 🎛 调用图模式与上限：按 Exbin 重写
- 模式改为**单选互斥**（对齐 Exbin `modeSeg` 的 `singleSelection="true"`）。此前做成了「多选并集」，
  且并集还会被 `take(cap)` 截断 —— 切「完整模式」时 hot 的节点其实被丢掉了。
- 上限语义对齐 Exbin `readOptions()`：**完整模式默认全量展示（护栏 50000）**，热点 / 根展开默认 120、最大 2000。
- 选项面板按 `updateModeUi()` 联动：完整模式隐藏「深度 / 上限」，非根展开隐藏「根函数」。

## 🎯 定位与布局按钮
- **根下钻居中**：`XRefDagCanvas.fit()` 的 pan 公式多减了半个视口（按「绝对偏移」约定算，而绘制/命中用的是
  「视口中心 + pan」）→ 每次从 SCC 鸟瞰点节点跳过来，整图都被推到**右下角**。已改为 `pan = -图中心*scale`。
- **TB/LR 与缩放按钮重叠**：右下角工具条竖排 5×48dp（+12dp 间距 +16dp 边距）= 304dp 高，
  画布矮时会顶到画布顶部撞上 TB/LR。现在画布 <304dp 时工具条自动降级为两列（184dp），
  <250dp 时 TB/LR 让位到左上角。
- 大图回退自研布局后「布局方向 LR」不再失效（补了 TB→LR 转置）。

## 🧾 控制流节点列表 & 汇编视图
- **节点列表**：进入控制流页（或切换函数）时自动按当前函数重建 CFG —— 此前会沿用上一位目标留下的数据
  （典型症状：函数详情 0x16c8 的控制流却显示别的块）；块级指令（`pdfj`）改为三种内容模式都取，
  修掉「节点列表恒显 0 条指令」。
- **汇编视图**：native `rzDisassemble` 只取 `code->assembly` 首行（个别插件会把多条指令拼进一行，
  导致「一行里字节列与助记符粘连、后续行丢失地址列」）+ 步长护栏（异常长度时按架构默认指令长度推进）；
  Kotlin 侧合并续行；三列均加省略号裁剪，杜绝列间溢出。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.91...v1.3.92

---

# TaffyNiHe v1.3.91（OLLVM 去平坦化全链路 + 变长代码注入通道）

本版把 **OLLVM 控制流平坦化**从「只读检测」打通到「**可验证改写**」的完整链路，
并备齐底层**变长代码注入通道**（ELF 注入器 + 编辑内核 + APK 回填重签）；另含分析页对齐 Exbin 的收尾项。

## 🧩 变长代码注入通道（底层能力）
- **ELF 注入器**：文件末尾页对齐追加机器码，新增 `PT_LOAD(R+X)` 与 `.taffy.inject` 节区，`phdr / shdr / shstrtab` 表整体搬到文件末尾 —— **不动任何既有字节**；支持 x86_64 / x86 / arm64 / arm32 的入口 trampoline 编码，自动适配 16K 页设备。
- **编辑内核支持变长**：快照 / undo / redo / check 兼容「末尾追加」，新增 `editAppend`（只允许文件末尾追加，不重排既有节区）。
- **工具**：`taffy_so_inject_code`（注入机器码 + 可选 trampoline，dryRun 先出计划）、`taffy_apk_replace_native`（zip 级替换 APK 内 `.so`，自动丢弃旧 v1 签名）。
- 端到端单测：gcc 编 `.so` → 注入 → readelf 断言新 R E 段 → C harness `dlopen` 真返回 42。

## 🧬 OLLVM 去平坦化（新能力）

**检测与计划**
- 「去平坦化」视图：识别控制流平坦化分发器，恢复「状态 → 基本块」映射。
- 采用「**出口跳转等长重定向**」：把真实块末尾跳回分发器的跳转改指到真实后继，分发器随之成为死代码。
- 覆盖 arm64（`B/BL` imm26）、arm32（A32 `B` imm24）、x86（`jmp` rel32/rel8）的**同指令族等长重编码**，越界自动拒绝。

**工具 `taffy_so_deflatten`**
- `dryRun` 默认只出计划；`dryRun=false` 经编辑内核 `editHexVa` **等长写入**（带快照 / undo / redo）。
- 支持 **cmp 链**与**跳表（switch）**两类分发器；跳表解表后用「分发器出边集合」校验，不一致即整体弃用。
- `action=scan`：批量扫描函数，汇总哪些被平坦化、各能重定向多少处。
- 只处理「出口指向分发器 + 状态可识别 + 目标唯一 + 可等长编码」的块，其余如实标注原因、**绝不改写**。

**动态验证（把假设变成证据）**
- 内置**分发器模拟器**（arm64 / x86 指令子集）：把分发器**真跑一遍**，用执行结果校对静态映射。
- 一致（verified）→ 跳表那一路**放开自动写**；与执行结果**矛盾** → 整份映射作废；模拟跑不动 → 保持只读（需 `allowTable=true`）。

**视图**
- **前后 CFG 对比图**：原控制流 vs 去平坦化后（分发器已移除）并排渲染，附「块 N→M · 边 X→Y」。
- **指令级 diff**：每条改写指令的 before / after（`-` / `+`）与十六进制字节。
- 分析报告新增 `deflat` 段，生成报告时自动扫描一遍。

## 🎨 分析页对齐 Exbin（收尾）
- 侧边栏新增「设置」页；重定位 / ELF 头改为单列卡片；SCC 鸟瞰支持「完整数据 / 仅循环」；函数详情顶栏 ⋮ 菜单；汇编页 `noreturn` 灰化 + 角标与多选模式；搜索页补「代码·指令」「立即数」数据源。
- 地址查看器补地址输入框 + 前往 / ← / → 与指针解读；⋮ 菜单加「导出 CFG JSON」。

## 🔐 其它
- Frida server 支持自定义监听端口。
- 崩溃报告新增 Channel 与匿名设备标识（带隐私闸门，默认关闭）。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.90...v1.3.91

---

# TaffyNiHe v1.3.90（SCC 鸟瞰完整分析 + 搜索页 / 虚表页对齐 Exbin）

## 1. SCC 鸟瞰：从「只看得到一角」到「完整分析」
之前 SCC 鸟瞰看起来不完整，根因有三处，本版全部修掉：

- **取数前先补调用关系**：全局调用图命令改为 `aa; aac; agC json`（先做函数调用分析），避免 rizin 只给出「有名字没边」的半成品图；
  状态行现在同时列出 **调用边数 · 全部函数数 · SCC 总数（非平凡环 N 个 · 最大 M 个函数）**，一眼看出数据是否完整；
  若出现「函数很多但调用边为 0」会明确提示「调用关系为空，点『完整分析』重跑 aaaa」。
- **新增「完整分析」动作**：一键跑 rizin `aaaa`（全量分析）后重建调用图，SCC / 交叉引用都基于最完整的调用关系。
- **大图布局与缩放**：组件数 > 240 时改用**紧凑网格布局**（按层序 sqrt 列优先填充），不再把上千个组件堆成一列十几万 dp 的长条；
  画布缩放下探到 **0.02**（双击适配整幅图），`scale<0.42` 自动降级为**圆点**（半径 9/6dp，屏幕空间固定）——
  几千个组件的完整图现在能一次看全，缩放 / 拖拽查看细节。

## 2. 搜索页：完全对齐 Exbin `SearchTabFragment`
- 标题「功能」+ **功能条四项**（发起新搜索 / 在当前结果中搜索 / 撤销搜索 / 清除搜索），Exbin 样式：13sp、全宽、圆角 8dp、padding 16×11，主按钮 primaryContainer / 其余 surfaceContainer，禁用态自动置灰。
- **「发起新搜索」弹搜索窗体**（对齐 Exbin 对话框）：搜索内容 + **搜索类型 8 种**（代码 / 符号名 / 立即数 / 类名 / 引用字符串 / 引用常量 / 引用全局变量 / 引用函数）+ **搜索方式**（随类型联动，如符号名的 函数地址 / 函数名 / 函数参数 / 函数返回类型）+ **区分大小写 / 正则表达式**。
- 结果标题「搜索结果 ("q") — N 个」+ **Exbin 结果卡片**（等宽签名 12sp / 元信息 10sp / 展开提示 11sp primary，圆角 8dp、padding 14×10、条目间距 12×3）。
- 「在当前结果中搜索」「撤销搜索」与 Exbin 行为一致（含「没有当前结果，请先发起新搜索」提示）；引擎没有对应数据源的类型会**明确说明**而不是给假结果；「快速跳转」带过来的预置查询进入即按函数名搜索（对齐 Exbin `setPendingQuery`）。

## 3. 虚表页：完全对齐 Exbin `VtableSearchFragment`
- 标题「虚表搜索」+ 功能条三项（发起新搜索 / 显示全部虚表 / 清除搜索）+ **扫描方式行**（11sp tertiary，按来源统计「RTTI·重定位(N) 符号扫描(M) …」，无条目时「扫描方式: 未发现虚表」）。
- **虚表卡片**：类名（等宽 12sp）+ 地址 · 来源标签（RTTI·重定位 / RTTI·回退 / 符号扫描 / DWARF，按来源着色）· N 个虚函数 + 展开提示（11sp primary）；点击展开虚函数槽位（#序号 / 名称 / 地址）。
- 「生成头文件 / 复制全部」保留为小字动作（Exbin 无此按钮，不占功能条）。

---
# TaffyNiHe v1.3.89（调用图画布补上 Exbin 的「布局方向 TB/LR」按钮）

## 补上 Exbin GlobalCfg 画布右上角的布局方向按钮（`fragment_global_cfg.xml` `btnRankDir`）
上一版把 CFG 画布本体对齐到 Exbin 后，还差 GlobalCfg（调用图）画布右上角那颗 44dp 的布局方向按钮，本版补齐：

- **按钮**：调用图画布**右上角**、44dp、`top|end` + 12dp 边距、1dp outline 描边、surfaceContainerHigh 底、居中的方向图标；
  点击在 **TB ↔ LR** 之间切换并 Toast「布局方向: TB/LR」——行为与 Exbin 的 `btnRankDir` 一致。
- **真正生效**：`CfgDisplayState.rankDir` 一路透传到布局引擎 —— ELK Layered 用 `CoreOptions.DIRECTION`（DOWN / RIGHT），
  Dagre 用 `rankdir`；切换后重新布局，节点/边/小地图同步更新。
- ⋮ 菜单「控制流画布」也加了「布局方向」项（在控制流页也能切；Exbin 只有调用图页那颗按钮）。
- 底层的 `ExbinElk.layout` 之前把 `Direction.DOWN` 写死，本版改成可传 `direction`（DOWN / RIGHT），顺带让其它布局调用也能选方向。

---
# TaffyNiHe v1.3.88（分析页 CFG 画布悬浮控件对齐 Exbin + 函数签名还原）

## 1. CFG 画布悬浮控件完全对齐 Exbin CfgCanvasView
- 删除自造的「左上角可折叠 chip 控制面板」与「底部信息条」；画布只保留 Exbin 的悬浮控件：
  - **右下角 5 个 48dp 圆角 12dp 工具按钮**（间距 12dp / 边距 16dp / 1dp 12% 描边 / 3dp 阴影 / 图标 + − ⟳ 边样式 ⬇）：
    放大 ×1.25、缩小 ×0.80、⟳ 适应窗口、边样式循环（正交 → 折线 → 样条）、⬇ 导出 PNG。
  - **左下角 140×100dp 小地图**（圆角 8dp、边距 12dp；入口块暖黄、边 40% 灰、视口 = primary 20% 填充 + 1.5dp 描边）。
- 点击基本块改为**画布内浮层**显示块详情（地址范围 + 摘要 + 后继），对标 Exbin `drawBlockPopup`。
- 布局引擎 / 块内容 / 背景形状 / 边样式 / 拖动节点 / 小地图开关收进分析页 **⋮ 菜单「控制流画布」**（含「重新生成控制流图」），
  不再占用画布空间；调用图页复用同一套参数。

## 2. 函数签名还原（Exbin FunctionSignatureAnalyzer 落地为可见功能）
- **函数列表行副标题直接显示还原签名**：进入函数列表后后台批量还原前 40 个函数（Exbin FSA：JNI 特判 + 寄存器 read-before-write 反汇编），
  结果缓存复用，计数行显示「还原签名 12/40」进度。
- **函数详情工具栏**在函数名下方显示还原签名（对标 Exbin FuncDetailActivity）。
- **签名页**（⋮ → 签名）渲染 FSA 结构化结果：返回类型 chip、参数表（类型 + 寄存器 + 推断依据）、参数区间 [min, max]、
  局部变量表（sp 偏移 / 访问次数 / 大小）、置信度、notes；FSA 不可用时降级 rizin afvj 并明确标注 evidence。
- FSA 输出新增 `paramInfos` / `locals` 结构化字段，两条路径统一为 `args[]` 模型，UI 不再出现「参数为空」。

---
# TaffyNiHe v1.3.87（修 CFG 画布右下角小地图伪影）

控制流画布右下角的小地图此前在「单节点 / 内容比视口还小」时会显示成一整块主色空白矩形（单节点红点 + 满图视口框）。
现在只在**值得导航**（≥2 个真实节点）时显示，且视口已覆盖整幅图时不画导航框。

---
# TaffyNiHe v1.3.86（函数列表 / 交叉引用 / 控制流 / 汇编 / 伪C 五页逐项对齐 Explorer So）

把上次没抄齐的分析页逐项补齐，并修掉 SCC 鸟瞰的空白画布。

## 🧩 本次
**① 函数列表页 = Explorer So `FuncListTabFragment`**
- 新增三个**固定等分子页签**：**全部 / 符号表 / 线性扫描**（符号表 = 排除 `sub_*`，线性扫描 = 仅 `sub_*`）。
- 每行补上：**返回类型 chip**（左侧 `primaryContainer` 胶囊，如 `i / v / P`）+ **来源 chip**（右侧 `Symbol` / `LinearSweep`）+ **签名副标题**（内置常见函数签名表：JNI_OnLoad / malloc / memcpy / dlopen / pthread_create …；未知则回退「N 字节」）+ 元信息 `0x…  ·  N B`（与 Exbin 同样的两空格格式）。
- 新增 `tv_status` 状态行（仅在筛选 / 排序非默认时以主色显示，如「符号表 · 按大小 ↓」）。

**② 交叉引用页 = Explorer So `GlobalXRefFragment`（修 SCC 鸟瞰空白画布）**
- 页面结构改为 Exbin 原样：**四页签（入口概览 / 根下钻 / SCC 鸟瞰 / 导出）+ 搜索行（搜索函数名 / 地址…＋「重置」）+ 一行状态文案 + 单一内容区**；删掉自加的统计卡、枢纽 chip 行、根函数/深度/上限参数区。
- **SCC 鸟瞰不再被空态挤没**：画布恒占满内容区，无环时每个函数各成单成员组件照样出节点（Exbin 行为）；状态行显示「完整数据 N 条 · SCC M 个 · 枢纽高亮（入度 Top5）」。
- 入口概览 = **入口候选列表**（入口点 → JNI_OnLoad → 入度 0），行样式对齐 `XRefListAdapter`（卡片 16dp 圆角 / `surfaceContainer`，名称 14sp 粗体 + 地址 11sp 等宽 + 「调用 N · 被调 M」）。
- 点节点：函数 → 重新下钻；**SCC 超级节点 → 成员表**（「SCC 组件 N 个函数（互相递归）」）；**长按 → 直接进函数详情**；「导出」→ PNG（当前画布）/ JSON / CSV。

**③ 控制流页 = Explorer So `ControlFlowTabFragment`**
- 结构改为 Exbin 原样：**两个等分页签「图形化 / 节点列表」**（`tabMode=fixed` + `tabGravity=fill`），各自全屏。
- 图形化 = 整屏 CFG 画布（**删掉自加的状态条与提示条**）。
- 节点列表 = 搜索（「搜索节点地址、指令…」）+ 排序（树序 / 地址升序 / 地址降序 / 指令数升序 / 指令数降序）+ **缩进树行**（toggle `▼/▶/●`、标题 `loc_xxxx[Entry]/ (ref)` 等宽、副标题「N 条指令 | 助记符 操作数」，最小宽 360dp）；**点行 → 切回图形化并高亮该块**。

**④ 汇编页 = Explorer So `fragment_asm_code_tab.xml`**
- 删掉塔菲特有操作行（「更多指令 +400」「重新加载」「语义注解」），计数行改为 Exbin 的「**N 条指令**」（语义注解默认开启，与 Exbin 一致）。

**⑤ 伪C 页 = Explorer So `fragment_pseudo_c.xml`**
- 删掉塔菲特有引擎切换行（Exbin 本页没有引擎控件，引擎来自全局设置）。
- meta 行改为「**伪 C  ·  引擎  ·  N 行  ·  起始-结束**」，右侧「复制全部 / 导出」+ 加载转圈。

---
# TaffyNiHe v1.3.85（分析页图形化 + 导航完全复刻 Explorer So）

本轮两件事：把「根下钻 / SCC 鸟瞰 / 调用图」的图形化换成 Explorer So 的分层画布与 CFG 画布；把分析页侧栏与工具分类完全对齐 Explorer So 的 SoDetailActivity。

## 🧩 本次
**① 调用图 = GlobalCfgView（复用 CFG 画布）**
- `GlobalCfgView extends CfgCanvasView`：全局调用图不再自绘，改为把调用子图（模式 / 根 / 深度 / 上限）转成 CFG 画布 JSON（`basicBlocks` + `edges`，节点摘要「调用 N · 被调 M」）交给 `CfgCanvas` 渲染，白拿 ELK 布局、小地图、背景图案、缩放平移全套能力。
- 修掉一个遗留 bug：搜索框旁的 ‹ › 上一/下一匹配此前只改计数文本、画布不聚焦 —— 现在把命中集合与当前焦点传进画布，命中节点橙色高亮并自动居中（对齐 Explorer So 的 `setSearchMatches` + `focusNode`）。

**② 根下钻 / SCC 鸟瞰 = XRefDagView（共用分层画布 `XRefDagCanvas`）**
- 节点规格：圆角 8dp、`surfaceContainer` 底、类型色描边与文字（13sp sans-serif-medium）、高 32dp、宽 clamp(72dp, 文本+18dp)，文本中段省略（18 字）。
- 边规格：1.5dp 目标色（alpha 70%）+ 8dp 箭头；回边 primary 2.2dp 虚线（7,5）+ 中点垂直偏移 36dp 的二次曲线。
- 交互：缩放 0.5–4、双击适配（边距 32dp，clamp 0.5–3）、scale<0.42 自动降级为圆点；空态「暂无分层数据」。
- 根下钻：BFS 逐层（每节点 TopN 12、总数上限）转分层数据；SCC 鸟瞰：Tarjan 强连通折叠成超级节点（「首名 (N 个函数)」，tertiary 色）+ DAG 最长路分层 + 入度 Top5 枢纽高亮（对齐 `XRefScc`）。
- 顺带删掉被替换掉的自研 SCC 画布死代码。

**③ 侧栏完全复刻 Explorer So 的 NavigationRail**
- 侧栏只有六项：**主页 / 搜索 / 虚表 / 调用图 / 交叉引用 / 返回**（对应 `menu_so_detail_nav`），宽 80dp、labelVisibilityMode=labeled、选中 pill = `secondaryContainer`、tint 选中 `primary` / 未选 `onSurfaceVariant`、推送式推开内容；「返回」直接离开分析页（对齐 `nav_back → finish()`）。

**④ 主页页签 = Exbin DetailPagerAdapter 的 9 项**
- 函数 / 节区 / 符号 / 导入 / 依赖库 / 重定位 / 字符串 / 数据 / **ELF 头**。
- 程序段、动态表、版本需求、入口点、哈希按 Explorer So 的做法并入「ELF 头」一页：一个搜索框 + 分组键值表，六个分区的字段与值全量可搜（对齐 `HeaderTabFragment` 的单列表元数据页）。

**⑤ Explorer So 没有的分析页收进顶栏 ⋮ 溢出菜单**
- ⋮ 菜单分组：**SO 详情**（地址查看 / 快速跳转 / 全量分析 / 全局伪 C / 静态数据流追踪 —— 逐项对标 `menu_so_detail_addr.xml`）、**当前目标**（换函数 / 对象树 / 输出结果 / 当前任务）、**塔菲工具**（加固检测 / 脱壳 / 导出 / Flutter 分析 / AI 分析 / 编辑中心 / 计算器 / iApp / 十六进制 / 结果 / 工具台 / 函数信息 / 注释 / 签名 / 汇编器 / 指令解释 / 寄存器 / 汇编→C / 汇编→框图 / 进制转换 / C++ 反修饰 / 字符串解码 / XOR / 字节差分）。
- 文件 / 任务选择从侧栏移到顶栏下方小条（Explorer So 在进入详情前已选好文件，属功能必需）。
- 删除「域 › 工具 › 模式」三级导航与 47 视图抽屉：Exbin 没有单列的分析页一律不在侧栏/页签里单列。

---
# TaffyNiHe v1.3.84（函数详情 / 控制流页签 / 指令行 / 通用列表卡 对齐 Explorer So）

分析页逐项对齐收尾：把 Explorer So 剩余的四份布局规格全部落到代码里。

## 🧩 本次
**① 函数详情（`activity_func_detail.xml` / `fragment_func_detail.xml`）**
- 顶栏改为 **MaterialToolbar 形态**：高 56dp（`?attr/actionBarSize`）、左侧返回导航（回到函数列表）、等宽字体标题（TitleMedium）。
- 页签由滚动式改为 **TabLayout `tabMode=fixed`**：4 个页签等分，指示器与选中色 `colorPrimary`，未选中 `colorOnSurfaceVariant`。

**② 控制流页签（`fragment_control_flow_tab.xml`）**
- 函数详情「控制流」页签改为 **状态条 → 画布（weight 1）→ 底部提示条** 三段式：
  - 状态条：padding 10dp / bg `surfaceVariant` / 等宽 12sp「控制流 · N 块 · M 边」（块边数直接复用 `parseCfgGraph`）。
  - 提示条：padding 8dp / 居中 11sp / bg `surfaceVariant`「双指缩放 · 单指拖动 · 双击重置」。
- 分析页的 CFG 蓝图页保持原样（整页画布 + 悬浮操作条）。

**③ 指令行（`item_insn.xml` / `AsmAdapter` 实机参数）**
- 汇编列表行：内容内边距 **10 / 6 / 12 / 6**，**地址 100dp、机器码 140dp**、指令自适应；三者均等宽 **12sp**，颜色 地址/机器码 `onSurfaceVariant`、指令 `onSurface`。
- 结果页的单条指令行（`DisasmLine`）与汇编注解（`disasmInstrAnnotated`）同步从 11sp 提到 **12sp**。

**④ 通用列表卡（`item_list_card.xml`）**
- `CardRow` 改为卡片形态：**圆角 12dp、无描边、bg `surfaceVariant`、外边距 4dp、内边距 14dp**。
- 图标 36dp；标题 `titleSmall`、副标题 `bodySmall`（单行省略）、说明 `labelSmall` 且用 **`primary`** 色。工具列表 / 各设置列表同步生效。

---

# TaffyNiHe v1.3.83（调用图匹配定位 + 列表/汇编/伪C/字符串逐项对齐）

继续把 Explorer So 的布局 XML 逐项对到分析页代码里。

## 🧩 本次
**调用图（`fragment_global_cfg.xml`）**
- 搜索框右侧补上 **‹ › 上一个 / 下一个匹配** 按钮（44×48，M3 OutlinedButton，iconPadding 0，marginStart 6 / 4）。
- 匹配定位对齐 Explorer So `runSearch` / `stepMatch`：按**节点名 / 地址**匹配，循环跳转并平移画布聚焦命中节点；无匹配时禁用按钮。
- 匹配计数对齐 `tvMatch`：`第 k / N 个匹配`（tertiary 色）／无匹配显示「无匹配节点」（error 色）；无关键字时不显示。
- 去掉调用图外层重复的搜索行与统计行（头部卡已含，避免两个搜索框）。

**列表（`item_detail.xml`）**
- 去掉列表卡片的**选中高亮**（`item_detail` 无选中态，底色恒为 surfaceVariant）。

**汇编页（`fragment_asm_code_tab.xml`）**
- 顺序改为 **计数行 → 搜索框 → 分隔线 → 列表**；计数行 padding 12dp / labelSmall。
- 搜索框 marginH 12 / marginBottom 8、4dp 圆角、56dp 高、16sp。
- 列表行改为 **monospace 12sp**、行 padding 8dp，去掉圆角卡片与表头（Explorer So 行即三列：地址 | 机器码 | 指令）。

**伪 C 页（`fragment_pseudo_c.xml`）**
- 新增顶部 **meta 行**（padding 8dp / bg `surfaceVariant` / labelSmall）：转换器 · 行数 · 范围 · 越界 · 类型推断 + **「复制全部」「导出」** 两个 tonal 按钮（12sp，导出 `.c` 到 exports 并分享）。
- 代码区改为 **行号槽（bg surfaceVariant / monospace 13sp / 右对齐 / padding start12·end8）+ 代码（monospace 13sp / onSurface）**，行距 +2dp，去掉圆角卡片。

**字符串页（`fragment_strings_tab.xml`）**
- 新增 **过滤按钮行（编码 / 节区 / 长度）**，位于计数行与搜索框之间；按钮点击弹选项，选中后显示「编码: X」并高亮。
- `AnalysisRow` 增加 编码 / 节区 / 长度 字段，过滤在关键字过滤之后叠加；计数行追加「显示 N」。

---

# TaffyNiHe v1.3.82（逐像素核对 Explorer So 布局）

把 Explorer So 的布局 XML 尺寸逐项对到分析页代码里。

## 🧩 本次
**`item_detail.xml`（列表行卡片）**
- 卡片间距：去掉多余的 `spacedBy(6dp)`／底部 padding，只保留 `marginV 3dp`（与 Explorer So 一致，共 6dp）。
- 返回类型 chip 移到**标题左侧**（对齐 `tv_ret_type`），LabelSmall / bold / **minWidth 22dp** / paddingH6·V2 / 居中。
- meta 行改用 `textAppearanceLabelSmall`（11sp）+ monospace + marginTop 2dp。

**`fragment_list_tab.xml`（列表页）**
- 顺序改为 **计数行 → 搜索框 → 分隔线 → 列表**；计数行 padding **12dp** / LabelSmall。
- 搜索框 **marginH 12dp / marginBottom 8dp**，OutlinedBox 圆角 4dp、高度 56dp、字号 16sp（bodyLarge）。

**`fragment_global_cfg.xml`（调用图）** 整体重排为三层：
- **头部卡**：marginH 10 / marginTop 6，圆角 **16dp**、**1dp outlineVariant 描边**、底色 `surfaceContainer`，内边距 H12·T10·B8；内含搜索行（圆角 **12dp**、**13.5sp**）、匹配计数（11sp）、模式分段（**minHeight 36dp / paddingH 16dp / 12sp**：热点模式·根展开·完整模式）+ **「选项」折叠**（根函数 / 深度 / 上限 / 重新构建 44dp）。
- **统计行**：marginH 12 / T6 / B4，chip 间距 6dp，来源文字 10.5sp。
- **画布卡**：weight 1、marginH 10 / marginBottom 10，圆角 16dp、底色 `surfaceContainerHigh`；右下角 **44×44 布局方向按钮**（对齐 `btnRankDir`）。

**`fragment_func_tab.xml`（函数页）**
- 工具栏 **marginH 12 / T8 / B4**：搜索框 weight1（56dp / 4dp 圆角 / 16sp）+ **过滤（排序下拉）+ 导出** 两个图标按钮。
- 计数行 paddingH 14 / top 2 / bottom 6；列表前加分隔线。
- 函数列表改用 `item_detail` 卡片（与其余列表页统一）。

---

# TaffyNiHe v1.3.81（交叉引用页签重做 + 塔菲特有页面统一）

## 🧩 本次
- **函数详情的「交叉引用」页签重做**（对齐 Explorer So `XRefTabFragment`）：4 个子页签 **外部引用 / 内部引用 / 数据引用 / 图形化**（默认图形化）；列表页带**搜索 + 状态行**（"找到 N 个外部引用 (调用者)"），图形化页带 **我调用的 / 调用者 / 双向 + 深度 − N + / 重置 + 邻域画布**。此前该页签直接复用了全局交叉引用页（套两层页签）。
  - 数据来自 rizin `axtj`/`axfj`（按 `type=CODE` 分调用/数据引用）+ 全局调用图 `agC json`。
- **塔菲特有页面视觉 + 布局统一**：结果 / 工具台 / 分析档位 / 编辑中心 统一加上与工具页一致的**标题行（名称 + 说明）**；编辑中心的模式切换 chip 统一为 `TabChip`。（Flutter 页此前已是 GlassGroup 卡片 + ToolChip，视觉已一致。）

---

# TaffyNiHe v1.3.80（工具页视觉收尾）

## 🧩 本次
- **iApp v3 解密页**迁移到统一的工具页外壳（`ToolPageScaffold`：标题 + 说明 + 操作条）：「解密导出」改为 Explorer So `GlowButton` 式**整宽 56dp 主按钮**，「提取参数 / 复制结果」为次要小按钮。
- 至此，分析页所有工具页（计算器 / 符号还原 / 进制 / XOR / 字符串解码 / 字节差分 / 汇编器 / iApp …）统一到同一套 Explorer So 视觉语言。

---

# TaffyNiHe v1.3.79（新增「函数详情」独立页）

补齐与 Explorer So 最大的结构差：函数详情不再散落在各工具里。

## 🧩 本次
- **新增「函数详情」页**（对齐 Explorer So `FuncDetailActivity`）：顶部**函数签名 + 地址**，下方 **4 页签 —— 汇编 / 控制流 / 伪C / 交叉引用**。
- **点函数列表 / 搜索结果里的函数，直接进入函数详情**（此前是跳到「汇编」页）。
- 汇编 / 伪C 页在容器内不再重复显示自己的函数头（由容器统一显示）；各工具页仍可从侧栏「更多视图」单独进入。
- 「函数信息」与「函数详情」名称区分（原两者都叫“函数详情”）。

---

# TaffyNiHe v1.3.78（分析页 · 工具页操作方式对齐）

在 v1.3.77（外壳换成 Explorer So 结构）基础上的收尾。

## 🧩 本次
- **工具页主操作按钮 → Explorer So `GlowButton` 式大按钮**：整宽 56dp、主色填充。用于 **计算器**（计算）、**C++ 符号还原**（还原）、**汇编工作台**（写入编辑会话）；次要动作（清空 / 复制 / 预览）降为小按钮。
- **空态文案修正**：分析页移除顶栏「选文件」条后，空态提示改为「先点左上角 ≡ 打开侧栏，选择文件」，不再指向已不存在的顶栏按钮。

---

# TaffyNiHe v1.3.77（分析页外壳对齐 Explorer So）

之前几版是把各视图「换皮」，分析页最外层的壳仍是塔菲自己的（自绘顶栏 + 文件选择条 + 全屏文字抽屉）。本版把**外壳**也换成 Explorer So `SoDetailActivity` 的真实结构。

## 🧩 本次
- **顶栏 → MaterialToolbar 形态**：`≡ + SO 详情标题 + 当前视图副标题 + 刷新 + ⋮`（对齐 Explorer So `MaterialToolbar("SO 详情")`），去掉塔菲原来的「当前函数大标题 + 文件/任务选择条」。
- **全屏文字抽屉 → 左侧 NavigationRail（推挤内容）**：对齐 Explorer So `NavigationRailView`，条目为 主页 / 搜索 / 虚表 / 调用图 / 交叉引用 / 加固 / 脱壳 / 地址 / 导出 / Flutter + 更多视图（打开全量索引）。
- **文件 / 任务选择移入侧栏头部**（Explorer So 由外部选好文件，塔菲需要在此选）。
- 主页页签保持 Explorer So `DetailPagerAdapter` 顺序：函数 / 节区 / 符号 / 导入 / 依赖库 / 重定位 / 字符串 / 数据 / ELF 头 …

> 说明：上一批（v1.3.73–76）已完成列表行 `item_detail`、搜索卡片、地址查看器底部地址栏、函数详情 4 页签、数据页子页签、调用图 GlobalCfg、交叉引用 4 页签等**内部**对齐；本版补齐**外层框架**。

---

# TaffyNiHe v1.3.76（分析页对齐 Explorer So · 第四批）

继续按 Explorer So 逐个对齐，本批为工具类视图。

## 🧩 本次
- **汇编器**：架构选择（AArch64 / ARM / x86 / x86_64）改为 Explorer So `AssemblerFragment` 式**页签**。
- **进制转换**：输入框加清除按钮。

> 累计到本版，分析页主结构（框架 / 列表 / 搜索 / 地址查看器 / 函数详情 / 调用图 / 交叉引用 / 数据页）与工具类视图均按 Explorer So 视觉语言统一。

---

# TaffyNiHe v1.3.75（分析页对齐 Explorer So · 第三批）

继续按 Explorer So 逐个对齐。

## 🧩 本次
- **数据页**：`常量 / 全局变量` 分级改为 Explorer So `DataTabFragment` 式**子页签**，结果表改为 `item_detail` 卡片列表，过滤框加清除与计数行。
- **注释页 / C++ 还原**：输入框加清除按钮，统一视觉与交互。

> 累计：框架（顶部可滚动 TabLayout）、列表页（`item_detail`）、搜索、地址查看器（底部地址栏）、函数详情（4 页签 + 函数头）、调用图（GlobalCfg）、交叉引用（GlobalXRef）、数据页（子页签 + 卡片）、JNI（卡片）、加固/脱壳（Report/DumpStrategy）均已按 Explorer So 重排。

---

# TaffyNiHe v1.3.74（分析页对齐 Explorer So · 第二批）

继续按 Explorer So 逐个对齐分析页。

## 🧩 本次
- **反汇编 / 伪 C**：加入 Explorer So 式**函数头**（函数名 + 地址 · 指令数 / 范围）。
- **函数详情**：汇编 / 控制流 / 伪C / 交叉引用 改为 Explorer So 式页签。
- **JNI 方法表**：结果列表改为 `item_detail` 卡片（粗体方法名 + 地址 chip + 等宽签名），搜索框加清除。
- **搜索 / 虚表**：结果/搜索框样式统一（卡片 + 清除）。

> 累计到本版：框架（顶部可滚动 TabLayout）、列表页（`item_detail`）、搜索、地址查看器（底部地址栏）、函数详情页签、调用图（GlobalCfg）、交叉引用（GlobalXRef）均已按 Explorer So 重排。

---

# TaffyNiHe v1.3.73（分析页对齐 Explorer So · 预览）

本版把**分析页按 Explorer So 重排**（第一批），并修复地址查看器数据模式。

## 🧭 框架：照 Explorer So 的 TabLayout
- 主页结构视图（函数 / 节区 / 符号 / 导入 / 依赖库 / 重定位 / 字符串 / 数据 / ELF 头 / 程序段 / 动态 / 版本 / 入口 / 哈希 / HEX）平铺为**顶部可滚动 TabLayout**，取代原「域 › 工具」下拉菜单 + chips。
- 其余模式 / 工具经左侧 ≡ 抽屉进入。

## 📋 列表页：照 item_detail
- 行卡改为 Explorer So 版式：卡片（`surfaceVariant` 底、10dp 圆角、内外边距照 item_detail）+ **粗体标题** + 右侧**地址 chip** + 等宽 meta 行。
- 搜索框加**清除按钮**；函数 / 符号 / 字符串 / 节区 / 导入 / 依赖库 / 重定位 / 哈希 / 版本 / 入口等列表统一。

## 🔎 搜索 / 地址查看器 / 函数详情
- **搜索结果**改为 item_detail 卡片；搜索框加清除。
- **地址查看器**：信息栏 + 行列表 + 底部 `← | 地址 | 前往 | →`；并修复数据模式（rizin `pxj` 输出为字节数值数组）误报“地址无法解析”。
- **函数详情**：汇编 / 控制流 / 伪C / 交叉引用 改为 Explorer So 式页签。

> 分批推进：以上为第一批；剩余视图（虚表 / 加固 / 脱壳 / 导出 / 工具类等）继续按 Explorer So 逐个对齐。

---

# TaffyNiHe v1.3.72

本版继续对照 **Explorer So 源码**把分析页「抄明白」，并修复地址查看器的数据模式。

## 🐞 修复：地址查看器「数据模式」误报“地址无法解析”

- **现象**：地址查看器切到「数据模式」时提示“读取失败：地址无法解析（试试 0x 开头的虚拟地址）”，即使输入的是合法虚拟地址。
- **根因**：数据模式用 rizin `pxj` 取字节，但 `pxj` 输出的是**字节数值数组**（`[127,69,76,70,…]`），而旧代码用「对象数组」解析器 `parseRzArray` 去解析 → 恒为空 → 误判成“该地址无可读内容”。
- **修复**：改为按**字节数值数组**解析 `pxj`，再自行渲染成 16 字节/行的 hexdump（偏移 / 字节 / ASCII）；保留 `0x` 前缀容错。

## 🧭 分析页对齐 Explorer So：调用图 / 交叉引用

- **调用图**：去掉旧的多页签外壳，改为 **Explorer So `GlobalCfgFragment` 形态**——顶部搜索框（函数名 / 地址）+ 统计行 + 调用图画布；画布含 **热点模式 / 根展开 / 完整模式**、根函数 / 深度 / 上限、布局方向（上下 / 左右）、边路由（折线 / 曲线 / 直线）。
- **交叉引用**：对齐 **Explorer So `GlobalXRefFragment`**——**入口概览 / 根下钻 / SCC 鸟瞰 / 导出** 四个页签；入口概览默认以列表列出入口函数，可下钻、看 SCC 枢纽、导出 PNG/JSON/CSV。

---

# TaffyNiHe v1.3.71

本版修复 **Dex2C 发布加固**，恢复 v1.3.70 里丢失的加固层（BackupCrypto native 化）。

## 🔧 根因与修复

- **现象**：`build-multiabi` 的 Dex2C 加固步骤失败（`InvalidInstruction for 0x100`），4 个 ABI 全部回落为未加固。
- **定位**：在 CI 里用 dcc 自带的 androguard 对 release dex **逐方法**解析，唯一失败的方法是三方库
  **`com.alibaba.fastjson2.JSONReaderUTF8.of([B I I JSONReader$Context)`**（内含大 `packed-switch`）。
- **根因**：dcc 内置的旧 androguard 解析 `packed-switch` / `sparse-switch` payload 时**越界读取**
  （`max_size = len(buff) - idx - 8` 计算错误），且 `get_instruction_payload` 会把 `struct.error`
  转成 `InvalidInstruction`，外层 `except struct.error` 抓不到 → 整个 dex 分析崩溃、加固失败。
- **修复**：加固步骤在 dcc 源码里给 androguard 打补丁，把 payload 读取边界收紧为「实际可用字节数」
  （仅解析健壮性修复，不改变目标类的逻辑）。
- **验证**：同一次构建中，打补丁前 `classes.dex fail=1`，打补丁后 `classes.dex fail=0`。

> 说明：`v1.3.70` 的功能改动（分析页列表统一、调用图按源码校正、CFG 节点列表、加固页/函数详情对齐）一并包含在本版中。

---

# TaffyNiHe v1.3.70

本版对照 **Explorer So 源码**（`so逆向工具-开源.zip`）继续把分析页「抄明白」：全局交叉引用/调用图按源码校正，加固与函数详情对齐源码结构，分析页各列表工具统一成 Explorer So 的页面范式。

## 🔧 对照源码校正调用图 / 交叉引用

- **全局交叉引用页回到源码的 4 个页签**（`入口概览` / `根下钻` / `SCC 鸟瞰` / `导出`）——对齐 `GlobalXRefFragment`。
- **邻域视图按 `XRefEgoModel` 重做**：方向 **`我调用的` / `调用者` / `双向`**、深度 **1–5**，状态行显示 **`树边 N · 交叉边 M`**（对齐源码的边分类）。
- **边路由风格改为源码 `CfgCanvasView` 的三种**：**`折线` / `曲线` / `直线`**（原来误做成了树边/交叉边/聚合），并在画布上真实绘制正交折线、贝塞尔曲线与直线。

## ✨ 代码域：CFG 新增「节点列表」

- 对标 `CfgNodeListFragment`：CFG 画布新增 **`节点列表`** 开关，弹出可**搜索节点地址/指令**的块清单，点击即定位到对应块。

## ✨ 判定域：加固 / 函数详情对齐源码

- **加固页对齐 `HardeningReport`**：新增 **`结论`**（疑似强加固 / 疑似混淆·加壳 / 存在异常 / 未见明显加固）、**`结构完整性 N/100`**、**`命中厂商`**、**`需内存 Dump`**，并给出 **`推荐处理流程`**（对齐 `HardeningAnalyzer.buildRecommendations`）。
- **函数详情**新增 **`汇编` / `控制流` / `伪 C` / `交叉引用`** 四个跳转页签（对齐 `FuncDetailActivity` 的 tab 结构）。

## 🧹 收尾：列表页统一 Explorer So 式

- 所有列表类工具（函数/字符串/符号/导入/段节/程序段/重定位/动态/依赖库/哈希/版本/入口点/ELF 头/虚表/JNI/数据/注释）统一为 **顶部统计行 + 带说明的搜索框 + 卡片列表**。
- 去掉了直接暴露给用户的**内部命令**（如 `命令: irj`），搜索说明统一为 **`搜索X（按 …）`** 的可读文案。

---

# TaffyNiHe v1.3.69

本版把**调用图 / 全局交叉引用**的界面**对齐 Explorer So**——按它的实现方式重做控件，去掉一堆看不懂的缩写按钮。

## 🔧 看懂问题出在哪

之前调用图上的控件是 `-上限` `+上限` `-层` `深 2` `+层` `TB → LR` 这种**缩写 chip**，含义全靠猜。
Explorer So 的做法完全不同：**参数用带标题的输入框**，**动作只有一个主按钮**，**顶部一行说明文字说清当前状态**。

## ✨ 本次改动

**调用图（`CallGraphGraphPane`）**
- 模式改为 **`热点模式` / `根展开` / `完整模式`** 三个明确页签（原来叫 `热点 / 根展开 / 完整`，现在带「模式」二字）。
- 新增**参数区**：`根函数`（带「函数名 或 0x 地址」提示的输入框）、`深度`、`上限`——数字直接输入，不再用 `+/-`。
- 新增**主按钮「⟳ 重新构建」**（原位置是一个没有任何作用的 `深 2` 占位按钮）。
- `TB → LR` 改写为 **`布局：上下 / 左右`** 两个明确页签。
- 右上角常驻 **`显示 X / Y 节点`** 提示。

**根下钻**
- 旧的 `开始下钻` / `JNI_OnLoad` / `取当前函数` / `复制树` 四个并列按钮 → 改为：`根函数`（输入）+ `深度` + `上限` **参数区** + 一个主按钮 **`⟳ 重新构建`**。
- 「候选根函数」以页签形式列出（`JNI_OnLoad`、当前选中函数、各入口函数），点一下即填。
- 结果头部改为一行说明：**`根下钻 <func> ｜ 深度 N ｜ 当前显示 X / 上限 Y 节点`**。

**全局交叉引用说明行（对齐 Explorer So 文案）**
- 概览：`全局交叉引用 · 完整数据 N 条 · 入口 N 个 · 函数 N 个 · 枢纽高亮（入度 Top5）`
- SCC 鸟瞰：`全局交叉引用 · 完整数据 N 条 · SCC N 个 · 环状簇 N 个 · 枢纽高亮（入度 Top5）`
- 页签右侧 `刷新` 改名为 **`重新分析`**（它其实是重跑全量分析，名字要配得上动作）。

---

# TaffyNiHe v1.3.68

- 虚表识别再深化：原始字节指针表扫描兜底 + `_ZTI` RTTI 配对 + 槽位函数名。
- iApp 解密增强：从原生库提取 ELF 密钥候选 + 分析页 iApp 视图。
- 反编译缓存（SHA-256 输入指纹 LRU）。
- Material You 动态取色。

# TaffyNiHe v1.3.67

- iApp v3 解密工具、frida-server 升级、若干修复。

# TaffyNiHe v1.3.66

- 分析页导航重构（域 → 工具 → 模式三级）、6 项缺陷修复。




















