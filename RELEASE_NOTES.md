## 塔菲逆核 v1.5.13 —— Frida 写入/替换/枚举/脚本 + 设置页去重

### 一、Frida 动态侧补齐四个 op（`taffy_dynamic_api`）

| op | 作用 | 位置参数 |
|---|---|---|
| `frida_modules` | 枚举目标进程已加载模块（name / base / size / path） | `[sessionId]` |
| `frida_write` | 十六进制写内存 | `[sessionId, address, hex]`（hex 奇数位直接拒） |
| `frida_replace` | `Interceptor.replace`，替换体固定返回 int | `[sessionId, target, retValue]`，target 支持 `0x` 地址或导出名 |
| `frida_script` | 在已注入的 agent 内执行任意 JS 表达式/语句块 | `[sessionId, source]` |

`frida_call` 的参数类型也修了：原来 `args.map(n => n | 0)` 把所有参数截成 32 位整数，
**凡是要 `char*` 的导出都调不通**（字符串传进去变成 0）。现在按形态推导：

- 数字 → `int`
- `"0x1234"` → `pointer`
- 其他字符串 → `allocUtf8String` + `pointer`（agent 侧保活，函数返回后才释放）
- 对象可显式写 `{"string":…}` / `{"pointer":…}` / `{"int":…}` / `{"uint64":…}`

另外新增可选的第 4 个参数 `returnType`（`int` / `pointer` / `int64` / `uint64`），
64 位返回值不再被强转成 int 而丢高位。

### 二、顺手修掉 agent 里的一个必崩 bug

`resolveExport` 的模块回退扫描写的是：

```js
for (const i = 0; i !== mods.length; i++) {  // i++ 对 const 赋值 → TypeError
```

第一次在目标进程里解析不到导出时就会抛，`hookFunction` / `callFunction` 全部失败。
改成 `let i`。这个 bug 从上游一路带过来，因为平时 hook 的都是能在指定模块里直接命中的导出，
走不到回退分支。

### 三、设置页去重与重设计

**发现并修掉的重复**：

1. **工作区（默认目录 / 缓存上限）在两条路径上都能进** —— 根页「应用与设备」一组，
   开发环境二级页又有一组「工作区」。同一个设置项出现在两处，用户会怀疑两边写入的
   不是同一个值。开发环境页删掉该组，改成一行指路文案。
2. **服务配置页的「网页测试」是独立一张卡，只有一个「打开测试」按钮** —— 而同一页
   每个地址卡片都有自己的「测试」按钮，是同一功能的第二个入口。删掉该组，把
   「全部地址」与「客户端配置」合并成一张「访问入口」卡（6 组 → 4 组）。
3. **外观页 7 张单行卡** —— 语言、主题、强调色、密度、圆角、字号、动效各占一张卡，
   每张只有一行 chip，整页像七个标签贴纸。按语义合成两张：
   「外观」（语言 / 主题 / 强调色）+「布局与文字」（密度 / 圆角 / 字号 / 动效），
   卡内用字段小标题 + 分隔线分组（7 卡 → 4 卡，含预设与开关）。
4. **「诊断与关于」一张 8 行卡** —— 排障时要往下扫过 4 条法务/致谢条目才能找到
   「运行统计」。拆成「诊断」（完整性自检 / 运行统计 / 日志 / 更新）与
   「关于」（帮助 / 致谢 / 免责 / 版本）两张卡。

**新增的局部组件** `ChipFieldLabel`：分组卡内部的字段小标题（12sp / SemiBold /
onSurfaceVariant / start 16dp），与 `ChipRow` 的 14dp 内边距对齐。原来多张单行卡
合并后，卡内需要区分「这一段是哪个设置」，没有这层小标题会分不清。

### 四、排查记录

- **agent JS 的 `for (const i…)` 自增**（见上，必崩）
- **`callFunction` 的 `n | 0` 截断**（见上，字符串参数全废）
- 新 op 的参数必须显式列进 `rpcArgs` 的 `when` 分支：走 `else` 分支是按
  `JSONObject.names()` 顺序排的，而它不保证顺序 —— 参数一错位就是把模板地址当长度用。

### 五、仍需真机验证

这批 op 的失败形态是「目标进程静默崩溃」或「hook 装上了但没生效」，沙箱里没有可复现的
注入目标。代码路径已按 Frida 官方 API 写（`Memory.writeByteArray` 收 `Uint8Array`、
`Interceptor.replace` 的回调必须 JS 侧持引用防 GC），但**没有在真机上跑过**。
第一次使用时建议先用 `frida_modules` 确认会话通、再用 `frida_script("Process.arch")`
确认 eval 通道，最后才碰 `frida_write` / `frida_replace`。

## 塔菲逆核 v1.5.12 —— 工具调用模式 + 设置页重做

### 一、工具调用模式：`full` / `skills_like`

借鉴 SOMCP v1.0.23（PR #174）对齐 AstrBot 的 `tool_schema_mode`。设置 → AI 深度分析 →
「工具调用」里切换，**AI 深度分析、子代理、`dynamic_analyze_ai`、`static_analyze_ai`
四条链路同时生效**（`AgentKernel` 单点透传；两个 Service 自己 new 引擎的地方也补了参数，
漏一处就等于设置形同虚设）。

- **原生调用（`full`，默认）**：走 provider 的 function calling，tools schema 每轮随请求
  重发。约束最强，行为与之前完全一致。
- **Skills 风格（`skills_like`）**：不发原生 tools，把工具渲染成 Markdown 文档塞进 system
  prompt（工具名 + 描述 + `签名` + 参数表），模型用
  `<tool_call>{"tool":..,"args":{..}}</tool_call>` 发起调用，引擎从文本里解析，结果以
  `<tool_response>` 的 user 消息回灌。

适合两类场景：中转/小模型不支持 function calling（此前只能整轮失败）；工具很多、
schema 每轮重发太贵（一次性文档随轮数摊薄）。

**边界如实说**：`skills_like` 依赖模型遵循 `<tool_call>` 约定，约束力弱于 API 级
function calling。模型如果把调用写成散文，引擎只会忽略解析不了的块，**不会虚构调用**；
这种情况请切回原生。

顺带修掉两个会让 `skills_like` 直接 400 的真问题（上游版本也有）：

1. `buildOpenAiBody` / `buildAnthropicBody` 无条件写 `"tools": [...]`，空数组时部分
   OpenAI 兼容中转直接报 `tools must not be empty` —— 而 `skills_like` 走的正是
   「不发 tools」这条路。现在 `tools.isNotEmpty()` 才写（Gemini 那边本来就有判断）。
2. 同一模式下消息体仍会写 `tool_calls` / `tool_use` / `tool_result`，引用不存在的工具
   定义。现在这三类字段只在 `full` 模式写。

### 二、SOMCP 12 项扩容逐一对比

| # | 上游工具 | 塔菲原状 | 本轮 |
|---|---|---|---|
| 1 | `workspace_overview` | engine 有 `overview()`，无工具 | ✅ 新增 `taffy_workspace_overview` |
| 2 | `analyze_outline` | engine 有 `outline()`，无工具 | ✅ 新增 `taffy_analyze_outline` |
| 3 | `analyze_string_xrefs` | 已有 `taffy_string_xref` | — |
| 4 | `cache_clear` | engine 有 `clearCaches()`，无工具 | ✅ 新增 `taffy_cache_clear` |
| 5 | `workspace_export` | engine 有 `snapshotBytes()`，无工具 | ✅ 新增 `taffy_workspace_export`（base64 + SHA-256 + 架构，32 MiB 硬上限） |
| 6 | `system_logs` | 已有 `taffy_logcat` / `_capture` / `_collect` / `_crash` | — |
| 7 | `frida_write` | 无 | ⏸ 见下 |
| 8 | `frida_replace` | 无 | ⏸ 见下 |
| 9 | `frida_modules` | 无 | ⏸ 见下 |
| 10 | `frida_script` | 无 | ⏸ 见下 |
| 11 | `frida_call` 参数类型（int / 指针 / 字符串） | 待核 | ⏸ 见下 |
| 12 | `static_analyze_ai` | 只有 `taffy_dynamic_analyze_ai` | ✅ 新增，`DeepAnalysisService` 补 `analyzeSync` 阻塞入口 |

**为什么第 7–11 项没做**：Frida 侧四个新 op 全部要在**已 root 的真机上**验证才有意义
（写内存、`Interceptor.replace`、agent 内 eval 任意 JS）。沙箱里既没有 root 设备也没有
可复现的注入目标，写出来的代码只能靠"看起来对"交付 —— 而这类操作的失败形态是
「目标进程静默崩溃」或「hook 装上了但没生效」，事后极难定位。塔菲现有的
`taffy_dynamic_api(op=frida_hook)` 已经允许传入 JS 片段驱动 `Interceptor/Module/Memory`，
`frida_script` 想要的能力基本能用它表达。等有真机验证窗口时再补，并在描述里标注需要 root。

### 三、设置页重做（覆盖全部 42 个子页）

问题不在某一页，而在**两套基座**：

| 基座 | 被谁用 | 原问题 | 现在 |
|---|---|---|---|
| `GlassGroup` + `NavRow` + `GroupDivider`（`UiComponents.kt`） | 30 个页面的主体内容 | ①卡片色 `surfaceContainer` 与页面底色几乎无差，深色下整页像一张纸；②圆角 `extraLarge`(28dp) 过大，卡片显得臃肿；③组标题 `padding start = 20dp`，比卡内正文（16dp）更靠里，整页看着是歪的；④分隔线 0.5dp + alpha 0.22，几乎看不见，行与行糊成一片；⑤`NavRow` 64dp 高 + 16sp 标题，一屏只能放 6 行 | 卡片 `surfaceContainerHigh` + 圆角 20dp；组标题 12sp/600 且与正文左对齐；分隔线 1dp `outlineVariant`、缩进 16dp；`NavRow` 56dp / 15sp / 图标 22dp |
| `SettingsScaffold` + `SettingsSection` + `PreferenceSurface` + 行组件（`SettingsKit.kt`） | 15 个页面 | ①`LargeTopAppBar` 大标题未滚动时白占约 96dp；②组内中间行留 4dp 圆角，行与行之间四个小缺口，拼起来不像一张卡而像一堆小方块；③行高 80/96dp（iOS 大行高），设置项一多就翻页翻到手酸 | 顶栏收到 56dp / 标题 20sp 常驻；组内中间行圆角 0（首行上圆 20、末行下圆 20）；行高 54dp（分类 68dp）、标题 15sp / 副标题 12sp |

两套基座的底色与圆角现在一致（`surfaceContainerHigh` / 20dp），所以从 Hub 页点进
任何子页都不会有"换了套皮肤"的割裂感。

没有页面同时用这两套组件（已核），也没有页面直接引用 `SettingsDensity` 的行高常量，
所以这次换值不会把某个页面的布局挤坏。

### 四、排查收获

- **`skills_like` 的两个 400 陷阱**（见上，已修）
- **`static_analyze_ai` 的 `EngineToolHandler` 第三参是 `SettingsStore` 不是 `ToolContext`**
  —— 第一版照 `dynamic_analyze_ai` 的写法抄了 `ctx.settings`，编译即报
  `Unresolved reference 'settings' on receiver of type 'SettingsStore'`。两个 Handler 形态
  不同：`object : ToolHandler` 拿 `ToolContext`，`EngineToolHandler` 只拿 `(engine, args, settings)`。
- **`DynamicAnalysisService` / `DeepAnalysisService` 各自 new 引擎**，`toolSchemaMode`
  必须在两处补传；只改 `AgentKernel` 会让「Agent 链路生效、Service 链路失效」，
  用户切了模式发现一半功能没变。

### 五、关于"把功能整合起来"

塔菲的工具目录已有网关形态（`taffy_rizin_api` / `lief_api` / `unidbg_api` / `dynamic_api` /
`xanso_api` / `jni_api` …），本轮新增的 35 个工具**没有再套一层网关**，理由：

- 网关把 N 个工具的 schema 压成一个 `when(action)`，AI 需要先知道 action 名才能调用，
  发现成本反而上升；独立工具名（`taffy_elf_byte_ledger`）本身就是自解释的。
- 网关的收益是"少占上下文"。但本轮 35 个工具全部是 `ToolClass.EXTRA`，默认精简清单
  不含它们 —— 上下文只有真正需要时才付出。

网关留给「同一个底层能力的不同参数形态」（如 rizin 的 20 多个命令），而不是「同一主题
下互不相关的一堆分析」。

## 塔菲逆核 v1.5.11 —— 借鉴上游 T1 审计批（30 个只读工具）+ 归档工具收口

### 一、新增 30 个只读审计工具（借鉴 SOMCP v1.0.23 的 T1 批）

这批工具全部是「已有 engine 数据 + 纯逻辑计算」，不新增 native、不改动任何现有工具
行为，统一以 `taffy_` 前缀注册，且全部落在 `ToolClass.EXTRA`（默认精简清单不变，
AI 客户端的启动上下文不受影响）。按用途分五组：

| 组 | 工具 |
|---|---|
| ELF 结构与链接审计 | `elf_abi_profile` `elf_byte_ledger` `elf_section_anomaly` `elf_phdr_invariants` `elf_dynamic_tags` `elf_dt_flags` `elf_hash_tables` `elf_reloc_census` `dt_needed_resolve` `symbol_visibility_audit` |
| JNI / NDK 画像 | `jni_mangle_codec` `android_ident_note` `ndk_toolchain_fingerprint` `libcxx_variant` |
| 字符串与加密常量 | `str_encoding_census` `str_literal_classes` `crypto_const_fingerprint` `crypto_hash_constants` `net_endpoint_extract` `file_path_roots` `env_property_keys` |
| 补丁可行性（只读） | `code_padding_caves` `patch_cave_finder` `patch_length_guard` `patch_apply_verify` |
| APK 签名 / ZIP / ABI | `apk_sig_verify` `apk_cert_info` `apk_zip_anomaly` `apk_abi_coverage` |
| 证据 | `evidence_manifest` |

几个实际用法：

- `elf_byte_ledger`：把文件按区间算出「这一段被哪个节/段消费」，直接列出空洞、重叠、
  越界。加壳样本里「节区表声称的大小」和「真实被映射的字节」不一致，一眼能看出来。
- `elf_phdr_invariants`：`filesz > memsz`、`PT_LOAD` 重叠、W+X 段、`p_align` 非法——
  这些畸形在上游是加载器崩溃的原因，在这里是加壳/篡改的指纹。
- `crypto_const_fingerprint`：按字节精确匹配 AES/SM4 S-box、ChaCha sigma、Blowfish P、
  MD5 T 表、SHA-256 K/IV、CRC-32、FNV、xxHash、Murmur 常量，命中给出所在节与偏移。
  弱指标（如单个 S 表）会明确标注为弱，不下「就是 AES」的结论。
- `patch_cave_finder`：找可写代码洞，但每一条都带 `inExecLoad`（在可执行 PT_LOAD 内）、
  `relocTargetCovered`（没有重定位指向它）、`holdsEntryPoint`（不含入口点）三个判定，
  只有全过才 `safeForPatch=true`。**它是只读的**，真正写入仍走 `edit_hex` / `edit_asm`。
- `apk_zip_anomaly`：从中央目录做 ZIP 异常检查（重复条目、名字含 `..`、零长度、
  STORED 但带压缩大小、越界偏移）。配合上一版修的对齐问题，重打包前后各跑一次即可
  确认产物没被写坏。
- `evidence_manifest`：把输入 sha256、分析范围、调用方声明的 claims 和整份 JSON 的
  自校验哈希打成一份清单，便于事后复现「这个结论是在哪个文件上得出的」。

### 二、`ElfParser` 补上 program header 与 dynamic 解析

这一条是上面那批的**前置**，也是一个独立的真缺陷：塔菲的纯 Kotlin 解析路径此前
**从不填** `ElfFile.programHeaders` 与 `dynamicEntries`，只有 LIEF 后端会填。结果是
「依赖库列表」「PT_DYNAMIC 定位」「W+X 段判定」在无 native 可用时（未支持的架构、
native 加载失败、部分裁剪构建）全部退化成空——工具不报错，只是安静地给出「没有数据」。

现在两段都在 Kotlin 侧解析，并做了两处防护：`e_phentsize` 只在 32..256 之间才采用
（否则退回架构默认宽度），`e_phnum` 夹到 4096 条；dynamic 区间按文件长度夹住，
防伪造 `p_filesz` 把读取推出数组外。

### 三、归档工具收口：`add` / `delete` / `rename` 不再自己重打包

`taffy_archive_add`、`taffy_archive_delete`、`taffy_archive_rename` 此前各自写了一遍
`ZipOutputStream` + `new ZipEntry(name)`：

- method 是 -1，`putNextEntry` 会设成 DEFLATED —— 拿它改 APK，`resources.arsc` 与
  `lib/*.so` 就此失去 STORED 与对齐（与上一版修的两处完全同源）
- `add` 先把整包解压进 `Map<String, ByteArray>`，一个几百 MB 的包直接把进程顶到 OOM

现在三者都走 `core/ZipRepack.copyAll`：保留原条目的 method / extra / 时间戳，
`lib/*.so` 与 `resources.arsc` 强制 STORED + 4096/4 对齐，内容按流式写。

`ZipRepack` 为此新增两个能力：

- `appendFiles`：按文件流式追加新条目（`append` 只接受 `ByteArray`，加一个大 `.so`
  就必然 OOM）。新条目的 method 与对齐同样按名字规则推导。
- `level`：把调用方指定的压缩级别传下去（`taffy_archive_add` 的 `level` 参数此前
  在改造后会丢失；实现上必须在写第一条之前 `setLevel`）。

另外顺手补了两处：

- `add` 的条目名现在会被规整（去前导 `/`）并拒绝 `..` 段。旧逻辑在文件不在 `baseDir`
  之下时会把绝对路径写进条目名，Android 侧解包直接失败；返回里新增 `rejectedNames`。
- `delete` 在没有条目命中时直接返回 `unchanged`，不再无条件重打包整包——旧行为等于
  把没改动的包也降级成 DEFLATED，纯属自伤。

### 四、`apk_sig_verify` 标记为重活

它要按分块把整包读一遍做两级摘要（`0xa5` 分块树 → `0x5a` 顶层），几百 MB 的 APK
就是几百 MB 的 SHA-256。此前没占用重活槽，会在并发上限内一直让别人排队。

### 五、上游核对结论（没抄的部分）

| 上游 | 内容 | 结论 |
|---|---|---|
| SOMCP #181 | 「最新版用户被永久提示有更新」| **塔菲不受影响**。上游的判据是 `published_at > BuildConfig.RELEASE_PUBLISHED_AT`，而该基线由 release 任务在构建**开始**时注入、发布在**结束**时进行，恒为真。塔菲用的是「同版本 + 发布时间晚于本地忽略记录」，且塔菲根本没有 `RELEASE_PUBLISHED_AT` 这条注入链。 |
| SOMCP #174 | 工具调用模式 `full` / `skills_like`（工具暴露策略） | 塔菲已有 `ToolClass.LEAN` / `EXTRA` 两层暴露，与本批 30 个工具全部落在 EXTRA 一致，无需再叠一层。 |
| SOMCP #173 | 12 项工具扩容（Frida 动态侧 + AI 对等入口） | 塔菲对应的动态侧入口（`frida_control` / `dynamic_*`）已存在，本轮未重复。 |
| taixu v1.6.0 | 网关服务按实例管理 + 端口孤儿回收 | **未采纳**。taixu 回收的是 PRoot 沙箱内的进程树，Android 应用层没有权限去杀「占用端口的其它进程」；塔菲的 MCP 服务在同一进程内 `stop()` 时已释放监听，跨进程残留属无 root 无解场景，只做了错误提示。 |
| taixu v1.5.0 | 会话版本化 / 审批审计 / 轮改动卡片 | 塔菲对应能力在 `session_audit`、`EditSnapshotService` 里，形态不同，未强行对齐。 |

## 塔菲逆核 v1.5.10 —— 三条「工具说改好了、APK 却装不上」的链路

这一轮查的是**产物正确性**：工具返回 success + 字节数，但打出来的 APK 装不上、甚至直接
损坏。三处都在 zip 结构层面，读代码时不显眼，把产物跑一遍立刻现形。

### 1. `taffy_apk_replace_native`：回填 SO 之后的 APK 装不上

这是回填 patched .so 的标准链路（`taffy_so_inject_code` → `replace_native` → `sign`），
但它自己又写了一遍 ZipOutputStream 循环：

```kotlin
zos.putNextEntry(java.util.zip.ZipEntry(e.name))   // method = -1 → DEFLATED，extra 被丢掉
```

`new ZipEntry(name)` 的 method 是 -1，`putNextEntry` 会把它设成 DEFLATED。于是整包每个
条目的压缩方式都被重写：

- `resources.arsc` 变成 DEFLATED —— Android 要求它必须是 STORED，安装直接
  `INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`
- `lib/*.so` 变成 DEFLATED 且失去页对齐 —— `extractNativeLibs=false` 的包装不上，
  或运行期 mmap 失败

同仓库的 `core/ZipRepack` 早就有正确实现（保留 method/extra、对这两类条目强制 STORED +
4/4096 对齐、写合法的 0xd935 填充块），`taffy_apk_patch_bytes` 与 `taffy_smali_edit` 都在用
—— 只有这条链路漏了。

沙箱复刻，同一输入 APK 替换同一个 .so：

| 产物 | resources.arsc | lib/arm64-v8a/liba.so |
|---|---|---|
| 旧 | **DEFLATED** | **DEFLATED**，off%4096=208 |
| 新 | STORED，4 对齐 | STORED，off%4096=0 |

### 2. `taffy_smali_batch` 的 rebuild：同一个根因的第二处

smali 批量重编后把 dex 写回 APK 时，同样是自己写 ZipOutputStream + `ZipEntry(e.name)`，
后果一致。更糟的是 `wantSign=false` 时它还会把原 APK 的 v2/v3 签名块复制到这份被改坏的
zip 上（`copyV2V3Blocks`），签名必然校验失败、且报错完全不指向真因。

改为 `ZipRepack.copyAll` + `replaceAtomically`，条目数校验用返回的 applied 集合。

### 3. 保留 V2/V3 签名块时 EOCD 偏移写错 → 产物 zip 直接损坏

`ApkSigningPolicy.copyV2V3Blocks` 把签名块插到中央目录之前，再修正 EOCD 里的
「中央目录偏移」字段 —— 但它写的是**插入前**的 EOCD 位置：

```kotlin
val eocd = locateEocd(out)          // 插入前的 EOCD 偏移
insertBytes(out, cdOffset, block)   // 在 cdOffset 处插入 → EOCD 整体后移 block.size
writeUInt32(out, eocd + 16, ...)    // ← 实际改的是中央目录里的字节
```

沙箱复刻：对一个正常 zip 走旧逻辑，产物报
`BadZipFile: Bad magic number for central directory`；改成 `eocd + block.size + 16` 之后
解析正常、内容正确。

### 4. `ZipRepack.copyAll` 增加 skip / append

- `skip`：整条丢弃（用于丢掉失效的 META-INF v1 签名条目）
- `append`：新增原本不存在的条目，新增的 native 库同样按 STORED + 页对齐写
- `replace` 保持在**最后一个**参数位（现有调用方全部使用尾随 lambda，Kotlin 的尾随
  lambda 只对应最后一个形参）

对齐算法抽成 `paddingFor` 供「复制已有条目」与「新增条目」两条路径复用。

## 塔菲逆核 v1.5.9 —— 描边和分隔线一直在被容器底色吃掉；一批「看着能点、点了没反应」的行

### 1. outline / outlineVariant 和容器底色完全同色（全局视觉）

上一版修的是 `secondary` 撞色，顺手把整张 token 表核了一遍，发现描边槽有同样的问题：

```
深色  outline        = 0xFF3A3A3C   surfaceContainerHighest = 0xFF3A3A3C   ← 一字不差
深色  outlineVariant = 0xFF2C2C2E   surfaceContainerHigh    = 0xFF2C2C2E   ← 一字不差
浅色  outlineVariant = 0xFFE5E5EA   surfaceContainerHighest = 0xFFE5E5EA   ← 一字不差
```

这套 UI 的视觉底就是「1dp 描边 + 细分隔线」，而这两组颜色画在对应容器上等于没画 ——
所有卡片描边、`GroupDivider`、输入框边框、任务卡描边（`BorderStroke(1.dp, outlineVariant)`
×多）全都淡到看不见。改成拉开档位：

| 槽 | 浅色 | 深色 |
|---|---|---|
| `outline` | `C6C6C8` → `B0B0B6` | `3A3A3C` → `5A5A60` |
| `outlineVariant` | `E5E5EA` → `C6C6CE` | `2C2C2E` → `4A4A52` |

### 2. `DataRow` 无论有没有动作都挂了点击

```kotlin
// 之前：onClick 为 null 也照样注册
modifier.combinedClickable(onClick = { onClick?.invoke() }, ...)
```

于是所有传 `onClick = null` 的纯展示行（键值行、诊断结果行）按下去都有水波纹，
但什么都不会发生 —— 看起来就是「这个 App 点不动」。改成只有真的传了 onClick /
onLongClick 才挂手势。

### 3. 七处「看着能点、点了没反应」的展示行

`NavRow(onClick = {})` / `DataRow(onClick = {})` 这种写法会把行渲染成可点击（带 chevron
和水波纹），点下去是空实现：

- 设置 → MCP 工具：当前占用统计（2 行）、工作目录状态、访问策略、允许访问的根路径
- 设置 → Linux 环境：诊断结果行、注入/转储探针结果行（各 1 处）
- 设置 → Blutter：`索引 N 个版本` / `已打包 N` 两个状态 chip

全部改成不传 onClick（`NavRow`/`DataRow` 的 onClick 本来就是可空的），Blutter 的
两个 chip 改 `enabled = false`。

### 4. `TypeChip` 是徽标却带点击

`TypeChip`（分析页到处在用的类型徽标）内部是 `AssistChip(onClick = {})` —— 可点、
有水波纹、无动作。改为 `enabled = false`，并把 `disabledContainerColor` /
`disabledLabelColor` 显式指回原来的配色，外观不变但不再假装能点。

## 塔菲逆核 v1.5.8 —— 首页一屏装完；「图标缺失」其实是一个配色 bug

### 1. 首页重排：一屏显示全部，不用滑

原来要滑两屏：标题占一整块、引擎卡里叠了「状态行 + 分隔线 + 两行端点/目录」、
工作台 4 行卡片每行 54dp、「全部工具」独占一行按钮、服务区 5 行 ListItem 每行 44dp。
现在：

- **标题行**：名称 + 连接状态胶囊并成一行，删掉副标题「MCP 命令中枢 · 逆向工作台」
  —— 每次开首页都看一遍自我介绍，没有任何信息量。
- **引擎卡**：两行。运行中第二行是 MCP 地址（点按复制），停止时是工作目录名（点按更换）。
  分隔线去掉，两行文字的间距本来就够分隔。
- **工作台**：仍是 2 列（4 列会把副标题压成省略号），每格 54→48dp、图标 34→30dp，
  副标题按 2 列实际宽度重写（「符号 / CFG / 交叉引用」→「符号 / CFG / 交叉」），
  不再出现 `...` 截断。
- **全部工具**：从独占一行的 TextButton 挪进「工作台」标题行右侧，省一行。
- **服务区**：5 行 × 44dp 的 ListItem 改成 2×2 状态格（隧道 / 工作目录 / APK 桥接 /
  后台保活）+ 一行「进入分析台」。同时把原来三个独立的 1s/2s/3s 轮询循环并成一个 2s 循环。

### 2. 「SO 分析」「回编」两个图标看不见 —— 不是图标丢了，是同色

```
深色：secondary = 0xFF2C2C2E   surfaceContainerHigh = 0xFF2C2C2E   ← 一字不差
浅色：secondary = 0xFFE8E8ED   background = 0xFFF2F2F7             ← 几乎同色
```

首页 8 个功能格的图标按 `primary / tertiary / secondary` 轮转，落在这个槽上的两格
（SO 分析、回编）就只剩一个同样隐形的淡色方块 —— 图标画了，但和卡片背景一个颜色。
eDBG 页的「部署 eDBG」按钮（`container = secondary`）在深色下同样几乎不可见。

M3 里 secondary 是**次要强调色槽**，不是「次级背景色」。这里把 iOS 的
`secondarySystemBackground` 灰映射进来用错了槽。按 M3 语义改：
深色 `0xFFA8B3C7`、浅色 `0xFF56607A`，并同步 `onSecondary` 保证文字对比度。

### 3. 顺带修的两处

- **SSE 会话残留**：`/sse` 与 `GET /mcp` 的会话清理写在 `respondBytesWriter` 之后，
  客户端直接断开时它会抛异常，清理被跳过 —— 这条会话就白占 `sessions`（上限 512）
  的槽位直到 30 分钟超时。移到 `finally`。
- **首页目录名**：`Uri.getLastPathSegment()` 对 opaque URI 会抛
  `UnsupportedOperationException`，加兜底（SAF 正常返回 tree URI 不受影响，但首页
  不该因为一个目录名崩）。

## 塔菲逆核 v1.5.7 —— ELF 解析的正确性与健壮性（用真实 SO 逐字段对账找出的一批）

拿 `libandroidx.graphics.path.so`（10KB / 24 节区 / 带 .gnu.version）当探针，把引擎每一条
解析链与 `readelf` 逐字段对账，加上对畸形 ELF 的变异测试，这一轮修的是**数据正确性**，
不是界面。

### 1. 所有重定位的符号名都错位一格（影响导入表、PLT 标注、交叉引用）

ELF 的 `.dynsym[0]` 一定是空符号。两条解析路径都按「名字为空就跳过」收集符号，于是列表
整体前移一格 —— 而重定位的 `symIndex`、`.gnu.version` 的下标，用的都是**原始符号表**下标。

实测后果：
- `.rela.dyn` 里每条 `R_AARCH64_RELATIVE`（本身不引用符号）都被标成 `__cxa_finalize`；
- `.rela.plt` 里 `free` 被标成 `JNI_OnLoad`，全部往后串一位；
- 反汇编里「导入符号地址提示」（`; __cxa_atexit` 这类注释）跟着一起错。

修法：额外维护 `rawSymbols` / `rawDynSymbols` 两份按原始下标对齐的表，只给重定位与版本
解析用，展示列表照旧过滤空名。

### 2. 符号版本在主路径恒为空

`symbolVersions` 只有手写 `ElfParser` 会填，LIEF 主路径完全不带 —— 于是
`taffy_so_versions` 的输在正常设备上永远是空表。新增 `engine/ElfGnuVersions.kt`，
按原始 `.dynsym` 表下标解析 `.gnu.version` / `.gnu.version_r` / `.gnu.version_d`，
两条路径共用同一份实现（顺带把 old 实现里同样的错位问题一并解决）。

### 3. 概览里的「PLT 桩」计数在主路径永远是 0

native 侧 `build_reloc_json` 的 `section` 字段一直传空串，而 `pltStubs` 的判据是
`relocations.count { it.section.contains("plt") }`。改为 LIEF 的 `reloc.section()->name()`
（重定位**作用于**的节，PLT 重定位落在 `.got.plt`）；手写路径同步改成同样的语义
（按 `r_offset` 落在哪个节区间反查），两条路径的「节」列从此一致。

### 4. 负的 addend 被写成 18 位数

`addend` 是 `int64_t`，原来按 `uint64_t` 输出，负数变成 `18446744073709551612`，Kotlin 端
`optLong` 再回绕成另一个值（org.json 对大整数会先转 Double）。改有符号输出。

### 5. 畸形 ELF 能把解析打崩

`sectionBytes` / `RawSection.bytes` 里 `start + size` 用的是 Int 加法，两侧都能接近
`Int.MAX_VALUE`（例如 `sh_offset=1000`、`sh_size=0x7FFFFF00`），相加回绕成负数后
`copyOfRange(start, 负数)` 直接抛 `IllegalArgumentException`。改用 Long 运算并夹到文件长度。
变异测试：对 400 个畸形头部样本，旧实现 104 个抛异常，新实现 0 个。

### 6. APK 里 >64MiB 的 SO 打不开（而同一个 SO 解到本地能开）

`readSource` 的 APK 分支上限是 `maxMemory/8`（多数机器 32~64MiB），`libil2cpp.so`
（Unity）、`libUE4.so`（UE）都远超这个数，直接从 APK 选就失败。本地文件路径却允许到
512MiB —— 同一个文件不该因为「从哪进」而一半能开一半不能开。改成统一按当前堆余量
（headroom/2，下限 16MiB、上限 512MiB），超限提示给出 MiB 数与「先解到本地再打开」的建议。

### 7. 大文件读取吃掉双份内存，且 OOM 时整个进程会崩

`readBytesChannel` 先 `allocateDirect(size)` 再拷一份到 `ByteArray`，峰值翻倍；更麻烦的是
direct 分配失败抛 `OutOfMemoryError`（`Error` 不是 `Exception`），原来的
`catch (Exception)` 捕不到，会直接带崩进程。改为直接读进堆上 `ByteArray`，捕获改为
`Throwable`，并挡掉 `size<=0` / `size>Int.MAX` 的入参。

## 塔菲逆核 v1.5.6 —— 退出界面时不要把在跑的 MCP 服务一起废掉

v1.5.3 把引擎资源释放点从「塔菲 MainActivity.onDestroy」挪到了 `Application` 的
`ActivityLifecycleCallbacks`（因为壳模式那条老路径永远走不到）。但如果 **MCP 前台服务还在运行**，
用户只是退出界面，引擎也被一起关掉了 —— 服务里后续的工具调用全部失败。

现在只在「最后一条 Activity 结束 **且** MCP 前台服务未运行」时才释放；服务在跑就保留引擎，
等用户主动停服务（或进程结束）再回收。

## 塔菲逆核 v1.5.5 —— 排查第四轮：MCP 工具参数与路径边界

这一轮用脚本把 167 个工具的 **schema 声明** 与 **代码实际读取的参数** 做了一次全量对账，又用路径白名单反查写文件类参数。
大部分命中是「别名兼容（如 path/dir/filePath 都认）」的误报，真问题 3 个：

### 1. 🟠 `taffy_apk_rebuild` 的 `output` 能把产物写到任意位置
旧实现直接 `File(custom)`：MCP 客户端传一个绝对路径就能把产物写到工作区之外（绕过 `WorkspacePolicy`）；
传相对名又会在 Android 上被解析成 `/<name>`，根本写不进去 —— 这个参数实际上只有「绝对路径」一种用法是通的。
改为**必须落在输出目录内**（绝对路径若不在 outRoot 下则退化为取文件名，相对名按 outRoot 解析）。

### 2. 🟠 `taffy_compile` 的 `output` 可路径穿越
`File(outDir, outName)` 里 `outName` 若含 `../` 就能把产物写到 `compiled/` 之外。改为只取文件名部分。

### 3. 🟡 `taffy_so_pseudoc_batch` 的 `skipImports` 参数没声明
工具描述里写着「可用 `skipImports=false` 关闭」，但 schema 里没有这个字段 —— 客户端传了也会被丢弃，等于这个开关不存在。
补上声明。

### 对账结论（这些**不是** bug）
- `taffy_apk_sign_kill` 读 `dir`/`filePath`、`taffy_compile` 读 `path`：都是旧参数名的兼容回退（新名优先），不影响使用
- `taffy_smali_batch` 的 `targetPath`：属于 `rollback` 的共享读取，`path` 已在 schema 里
- `WorkspacePolicy.PATH_KEYS` 已覆盖 30+ 路径参数名并递归下探嵌套对象/数组，未发现其它绕过点

## 塔菲逆核 v1.5.4 —— 排查第三轮：协程异常与取消语义 + 会话校验 + 伪 C 虚拟化

上一版结尾挂着的四条，这次全部落地。

### 1. 🟠 155 处 `scope.launch` 没有异常兜底
`rememberCoroutineScope()` / `lifecycleScope` 都没有 `CoroutineExceptionHandler` —— 块里漏一个 catch，
异常就直接走 `Thread.uncaughtExceptionHandler`，用户看到的是**闪退**。而工具调用、文件读写、JSON 解析
全在这些块里。新增 `core/safeLaunch`（异常记日志、不吞 `CancellationException`），
29 个文件 155 处调用点全部换过去；块内 `return@launch` 标签同步改为 `return@safeLaunch`。

### 2. 🟠 87 处 `withContext(IO){ runCatching { … } }` 吞掉取消
`runCatching` 把 `CancellationException` 也 catch 成 `Result.failure`：页面切走 / `LaunchedEffect` 重启后
协程本该立刻取消，却被当成「业务失败」继续往下跑 —— 继续调 native、继续写已销毁的 UI 状态。
新增 `core/runCatchingCancellable`（只 catch 真异常，取消继续上抛），87 处全部替换，标签一并同步。

### 3. 🟠 伪 C 代码区 3000 行一次性组合
超大函数进伪C 页要把最多 3000 个 `Row`+`Text` 全部组合出来，卡好几秒且全驻内存。
改为 `LazyColumn` 虚拟化：行号槽固定、代码区共享一个横向滚动 state（Lazy 只组合可见的十几行，
所以「共享 state」不会带来多组件争抢的开销）。

### 4. 🟠 `Mcp-Session-Id` 任意值都被接受
旧实现把客户端带来的任意字符串当成有效会话写进表里，等于会话隔离形同虚设。
按规范改为：带了 `Mcp-Session-Id` 但服务端不认识（伪造 / 过期 / 服务重启）→ **404**，让客户端重新 `initialize`。

## 塔菲逆核 v1.5.3 —— 排查第二轮：安全策略收敛 + 数据原子写 + 资源泄漏

这一轮把上一版结尾列的「已知遗留」逐条翻源码核实了一遍 —— 其中 3 条在更早的版本里其实已经修掉了，
真正还在的 1 条补上；另外又挖出 3 个新问题。

### 1. 🔴 脚本引擎绕过了服务端的全部安全策略（唯一真正遗留的那条）
`taffy_script` 里的 `tool(name, args)` 只走了 heavy 闸门，其余策略**全部绕过**：
`disabledTools`（设置里禁用的工具形同虚设）、`blockDestructiveTools`（破坏性拦截失效）、
每分钟限频，以及**自身 artifact 守卫** —— 也就是脚本可以读写塔菲自己的 APK 与内置 SO，直接破坏完整性校验。
新增 `mcp/ToolPolicy.kt` 把「禁用 / 破坏性 / 限频 / 自身 artifact」收敛成一处，服务端与脚本共用同一套。

### 2. 🟠 编辑器保存会截断用户文件
`File.writeText` 先 `open(TRUNC)` 把目标截断成 0 字节再逐块写：大文件保存到一半被系统杀进程 / 存储写满，
原文件就只剩 0 字节或半截内容（快照只是兜底，不是不丢）。
新增 `core/AtomicWrite`（同目录临时文件 → `Files.move(ATOMIC_MOVE)`，不支持时逐级回退），
编辑器「保存 / 另存」与分析页导出都改走它。

### 3. 🟠 壳模式下引擎资源永不释放
释放逻辑写在塔菲 `MainActivity.onDestroy`，但壳跑在**它自己的 Activity** 里，塔菲 MainActivity 启动壳后
早就 `finish()` 了 —— 这条路径在壳模式下永远不会执行，SO 副本 / SQLite 句柄 / 模拟器与 Frida 会话
会一直被持有到进程被杀。改为在 `Application` 注册 `ActivityLifecycleCallbacks`，最后一条 Activity
真正结束（`isFinishing` 且非配置变更）时释放；按 Home 进后台不释放。

### 4. 🟠 画布手势降级反而更慢（v1.5.2 引入的回归）
上一版用「每次手势事件都 `gestureTick++`」实现降级计时，等于拖动时每帧写 State → **整页每帧重组**。
改为 `AtomicLong` 时间戳 + 120ms 低频轮询：只在「开始 / 结束交互」各写一次 `interacting`，
中间不产生重组；而且它只在 draw 阶段被读取，触发的是重绘而不是重组。

### 核实后确认**早已修掉**的三条（不必再动）
- **heavy 许可永久锁死**：`HeavyGate` 早已是「租约 + 看门狗」，租约（调用超时 + 120s）到期即回收槽位并计 `leaked`
- **备份恢复不清空目标目录**：`FullBackup.restore` 早已 `stageTargetDirs()` 把整目录挪开再恢复，成功后丢弃旧目录
- **抓包服务 stop 不关连接**：`HttpCaptureServer` 早已维护 `activeSockets`，`stop()` 里逐个 close

## 塔菲逆核 v1.5.2 —— 全仓缺陷排查：修 4 个真 bug

本轮做了一遍全仓扫描（290 个自有 Kotlin 文件，主线程阻塞 / 资源泄漏 / 异常吞咽 / 索引越界 / 状态错位 / 非空断言逐项过），
确认并修掉下面 4 个真实缺陷，其余命中项经人工核查为安全写法。

### 1. 🔴 Safe Mode 在「布局阶段崩溃」时完全失效
首帧判定用 `withFrameNanos { }`，它在 `doFrame` **开始**时就恢复协程 —— 此时 measure/layout/draw 都还没跑。
而之前的启动崩溃正是崩在 `onMeasure` 阶段 → `pending` 已经被清掉 → 下次启动不会把壳判为不可用 → 陷入崩溃循环。
改为 `ViewTreeObserver.OnDrawListener`：这一帧 draw 结束才回调；若这帧崩在组合/测量/绘制，回调不会发生。这才是「首帧真正画完」。

### 2. 🔴 壳崩一次就永久弃用，且没有任何恢复入口
`MAX_FAILS = 1` 且失败计数只增不减 → 用户杀进程/系统回收时正好在首帧前，就永久回到塔菲界面，再也用不到壳。
新增 **24 小时自动重试**：距上次失败超过 24h 自动清计数再试一次（系统更新/重装后壳可能已经正常）。

### 3. 🟠 画布边路径缓存的并发写
`CfgLayoutResult.edgePathCache` 是普通 `HashMap`，而 PNG 导出会**在后台线程**用同一个 layout 对象再走一遍 `drawCfgScene`
→ 与 UI 线程并发写 HashMap（可能死循环/丢更新）。改 `ConcurrentHashMap` + `putIfAbsent`。

### 4. 🟠 首页每秒枚举一次网卡
`while (true) { delay(1000) }` 里每秒调 `NetworkInterface.getNetworkInterfaces()` 并把结果写进 State
—— 等于首页每秒重组一次 + 主线程一次 syscall，纯浪费。改为只在「运行状态 / 端口」变化时重算端点。

### 顺带
- 画布手势降级改为「每次手势事件都刷新计时」（旧写法持续拖动时每 180ms 会闪回一次完整重绘，手感忽轻忽重）

### 核查过但**不是** bug 的高频告警
- 63 处 `!!`：逐条核对，全部在 `if (x == null) return` / `x?.let` 之后，安全
- `first()/last()` 42 处：均在 `isNotEmpty()` 判断或常量列表上
- 空 `catch`、`runBlocking`、无上限 `readBytes`：自有代码中 0 处

## 塔菲逆核 v1.5.1 —— 六个页面缺陷集中修（首页重设计 / 调用图拖动 / 任务页 / 交叉引用 / 伪C / 节点列表）

### 1. 首页重新设计
- 标题区（塔菲逆核 · MCP 命令中枢）+ 在线胶囊
- 「引擎卡」把原来的「引擎状态」和「连接地址」两张卡合成一张：状态 + 启动/停止 + 端点（点按复制）
- 「工作台」从 4 列小宫格改为 **2 列卡**（图标 + 名称 + 一句话说明），窄屏不再挤成四个小方块
- 全部工具入口与服务状态分组保留

### 2. 调用图「完整模式」拖不动（按钮却还有反应）
根因是**渲染成本**：旧实现每帧给每条边 new 一个 `Path` + new 一个 `List<Offset>`，完整模式上千条边时
每帧几十到上百毫秒 → 手势事件全被丢掉，只有点击类按钮还能响应。
- 边按颜色合并成 3 条世界坐标 Path（缓存在布局对象上），绘制时用 canvas 变换一次到位
- 手势进行中临时降级（不画文字/箭头），松手 180ms 后自动恢复
- 节点 > 400 个时不画节点文字；简化视图不画背景图案
- 完整模式默认上限 1500 → 800（统计行「显示 N/总数」照实标出）

### 3. 任务页
- 卡片重做：类型图标块 + 去掉 `picked_<hex>_` 前缀的完整文件名（最多两行）+ 状态胶囊
- 元信息改成一行 chips（类型 / 大小 / 状态 / 更新时间），不再出现 `-- · URI 引用 · 进行中` 这种半中半英长句
- 底部操作条：清除 / 重新选文件 / 继续分析（右对齐主按钮）；顶部统计改胶囊，去掉孤零零的数字

### 4. 交叉引用页文字显示不完整
- 4 个页签由等分 `TabRow` 改为 `ScrollableTabRow`（13sp、不换行），不再显示成「外部引…」

### 5. 伪 C「报错」
- `// WARNING:` 注释（rz-ghidra 的变量未映射 / 调用约定未知等）从代码里抽出来，收进可折叠的「反编译警告 N 条」面板
- `boundaryWarning` 不再压在 3 行 Ellipsis 的红色横幅里，改为面板内完整展示

### 6. 节点列表「收起/展开错位」+「0 条指令」
- 行上加了 `widthIn(min = 360.dp)`，窄屏横向溢出 → 标题被切、指令被切、展开的指令看起来「挂到别的节点下面」；改为整宽自绘行
- 三角独立点击折叠子树，整行点按展开该块全部指令（指令改为换行显示，不再单行省略）
- 「0 条指令」根因：`pdfj` 只覆盖**已被 af 分析**的函数，未分析时返回空数组 → 退化为 `pDj` 线性反汇编，并按块的 `endText` 过滤越界指令
- 首行摘要不再把 `mov w0, #0` 截成 `mov w0,`

## 塔菲逆核 v1.5.0 —— 壳层架构重构（修启动崩溃的根因）

### 根因（架构级，不是某个字段的问题）
早期我把太墟壳「精简」成一个可嵌入的 Composable（只取 `TaiXuTheme` + `TaiXuNavHost`），
塞进塔菲自己的 `MainActivity` 渲染。但太墟整套布局是按 **edge-to-edge + WindowInsets** 设计的：

| | 太墟 MainActivity | 旧的嵌入式壳 |
| :-- | :-- | :-- |
| `installSplashScreen` | ✅ | ❌ |
| `enableEdgeToEdge()` | ✅ | ❌ |

缺少这套预处理 → 布局在 **measure 阶段**读到异常 insets → NPE。
（真机崩溃栈经 R8 横向类合并后类名不可读，已用 CI 的成员级 mapping 证实 `jmh` 类是「垃圾桶类」，
里面装的其实是 `GraphicKitKt.BusyOverlay`、`BasicSettingsPagesKt.ChipRow` 等业务代码。）

### 重构内容
- **太墟壳回到它自己的 Activity**（`top.wkbin.taixu.MainActivity`）：splash、edge-to-edge、主题、
  onboarding 全链路原样运行，不再由宿主手工复刻；
- **塔菲 `MainActivity` 退化为「启动器 + 兜底」**：判断壳是否可用 → `startActivity(壳)` 并 finish；
  不可用/启动失败则直接渲染塔菲原界面；
- **启动守卫迁入壳模块**（`TaixuShellGuard`），由壳自己的首帧回调（`withFrameNanos`）驱动：
  首帧画出才算启动成功，否则下次判定壳不可用；
- 退役 `TaiXuEmbeddedRoot`（嵌入式壳）；
- 「逆向」中枢仍通过 `HostWorkspaceRegistry` 把塔菲工作区注入太墟主导航。

### 其它
- 恢复 R8（`isMinifyEnabled`/`isShrinkResources` = true）；
- 继承 v1.4.4 的全部修复：ML Kit 惰性容错、系统自检页、主题归一。

> 已知遗留：ML Kit 初始化 provider 未随包落地，翻译显示「受限」，不影响其它功能。

---

## 塔菲逆核 v1.4.5-diag —— 诊断版（不混淆）

**这不是正式版本。** 唯一目的：拿到**可读的真实崩溃堆栈**。

v1.4.3 / v1.4.4 在真机首次布局（`onMeasure`）阶段崩溃，但 release 包经 R8 处理后会发生
**横向类合并**——把大量不相干的方法塞进同一个空壳类，堆栈里的类名因此完全不可信
（已确认 `mlkit_common.zzou -> jmh` 里实际装的是 `GraphicKitKt.BusyOverlay`、`BasicSettingsPagesKt.ChipRow` 等代码）。

本版关闭 `isMinifyEnabled` / `isShrinkResources`：

- 安装包明显变大（正常现象）；
- 签名与正式版一致，**可直接覆盖安装，数据不丢**；
- 崩溃堆栈将显示真实类名与方法名。

请用本版复现一次，把新的崩溃报告发回；定位后立即恢复 R8 出正式版。

> 其余行为与 v1.4.4 一致：太墟壳启动守卫（首帧前崩过一次后，再启动自动回退塔菲界面）、系统自检页。

---

## 塔菲逆核 v1.4.4 —— 启动守卫收紧（崩一次即自动回退）

### 🛡 Safe Mode 收紧
1.4.3 在真机上仍会在**首次布局测量**阶段崩溃（栈被 R8 方法合并后不可读，正在定位）。在根因修掉之前，本版把启动守卫收紧为：

- 进入太墟壳前写 `pending` 标记；
- 壳层**首帧真正绘制完成**才清除标记；
- 若上次在首帧前退出 → 本次**直接改用塔菲原界面**（阈值由 2 次降为 1 次）。

也就是说：**装上后第一次启动若闪退，再打开一次就会进入塔菲原界面**，逆向工具链全部可用；太墟壳可在修好后自动恢复（成功后失败计数会归零）。

### 🔧 诊断
- CI 构建日志新增 R8 成员级映射打印（定位 R8 horizontal class merging 导致的可读栈丢失）。

> 已知问题：太墟壳首次布局崩溃未修完；ML Kit 初始化 provider 仍未随包落地（翻译显示「受限」，不影响其它功能）。

---

## 塔菲逆核 v1.4.3 —— 彻底修掉 ML Kit 启动崩溃（含 Safe Mode）

### 🐛 崩溃修复
v1.4.2 只修了 `TranslationManager` 里「后半段」字段，漏掉最靠前的 `translatorOptions`：

```kotlin
private val translatorOptions = TranslatorOptions.Builder()
    .setSourceLanguage(TranslateLanguage.ENGLISH)   // 构造期读取 → 触发 ML Kit 类初始化 → NPE
```

`TranslateLanguage` / `TranslatorOptions` / `Translation` 都是 ML Kit 的类，只要在构造期读到就会触发其静态初始化；而本包内 ML Kit 的初始化 provider 未就绪，内部 `Preconditions` 直接 NPE。R8 内联后崩点落在 `mlkit_common.zzou.x`（已用 CI 打印 mapping 反混淆确认）。

本版把 `translatorOptions` 一并移入 `lazy`，**`TranslationManager` 构造期不再触碰任何 ML Kit 类**；所有 ML Kit 访问都在 `runCatching` 内，失败退化为「翻译组件不可用」。

### 🛡 新增：太墟壳启动守卫（Safe Mode）
- 进入壳层前写 `pending` 标记；壳层**首帧真正绘制完成**后清除，并把失败计数归零；
- 下次启动若发现 `pending` 仍在，说明上次在首帧前就崩了 → 失败计数 +1；
- **连续 2 次失败自动改用塔菲原界面**（逆向工具链始终可用），并在日志留痕。

这样即使再出现同类 UI 崩溃，也不会出现「装上就进不去」的情况。

> 遗留：ML Kit 初始化 provider 仍未随包落地，翻译入口会提示不可用（不影响对话 / 工作流 / 终端 / 工坊 / 逆向），自检页可见该项状态。

---

## 塔菲逆核 v1.4.2 —— 修复启动崩溃

### 🐛 崩溃修复（1.4.1 装机即闪退）
真机（OnePlus PLC110 / Android 16）反馈打开即崩，堆栈反混淆后定位为：

```
ChatViewModel(Factory) → … → TranslationManager(Singleton) 构造 NPE
  at TranslationManager.<init>   ← ML Kit 未初始化
```

两处问题叠加：
1. `TranslationManager` 把 ML Kit 客户端（`RemoteModelManager.getInstance()` / `TranslatorOptions`）写在**构造函数字段初始化**里，ML Kit 不可用时直接 NPE；
2. 合并后的 Manifest 中**缺少 ML Kit 初始化 provider**（`MlKitInitProvider`，4 份 merged manifest 均已核对），进程内 ML Kit 从未初始化。

本版修法：
- `TranslationManager` 的 ML Kit 相关对象全部改为**惰性 + 容错**（`by lazy { runCatching { … } }`），不可用时退化为「模型未下载 / 翻译组件不可用」，**构造函数永不再抛**；
- 壳层就绪检测从「Koin 容器存在」升级为「**试解析壳层渲染必需的关键依赖**」（`AppearancePreferences` + `TranslationManager`），任一失败即回退塔菲原界面，不再把异常留到 UI 线程；
- 自检页新增「离线翻译（ML Kit）」项，装机后可直接看到该能力是否就绪。

> 遗留：ML Kit 初始化 provider 尚未随包落地，翻译入口会提示不可用（**不影响对话 / 工作流 / 终端 / 工坊 / 逆向工具**），该项在自检页可见，后续单独修复。

---

## 塔菲逆核 v1.4.1 —— 同步太墟 v1.4.1 + 移植收尾

### ✨ 同步上游（wkbin/taixu v1.4.1，25 个提交 / 77 个文件）
- **MCP**：服务器支持自定义请求头鉴权，凭据加密存储（Room schema 74 + 迁移）
- **运行时**：容器后端改为显式实验开关，新增 Droidspaces 后端（默认关闭）
- **群聊**：分块流式正文 + 增量 Markdown 渲染（`ChunkedText` / `IncrementalMarkdown` / `MarkdownParser`）
- **修复**：旧备份恢复兼容与错误细分、更新日志版本校验、会话切换未选模型时的配置解析、工具中心入口点击无反馈、cron 夏令时回拨

### ✨ 新增：系统自检
乾坤 → 宿主集成 → **系统自检**，逐项检测并支持点击重测：
- 设备 ABI（非 arm64 时明确标注哪些能力不可用）
- 依赖注入装配（Koin）
- 会话与记忆数据库（Room，schema 74）
- PRoot 运行时载荷、原生 PTY 终端、逆向引擎 `librz_native.so`、已安装发行版数量

失败项分「致命 / 受限」两级：致命项（Koin、Room）会让整块功能不可用，受限项只让单个能力退级。

### ✨ 主题归一
逆向中枢嵌在太墟壳里时不再自带 `MaterialTheme`，直接沿用 `TaiXuTheme`，消除「太墟壳 + 塔菲主题」双层叠加导致颜色/字号与实际渲染不一致的问题。

> 注意：因此在**嵌入模式**下，塔菲设置里的「外观与语言」只影响独立运行（Koin 装配失败时自动回退的那套界面）；日常外观请在乾坤里配置。

### ⚠️ 兼容性（继承 v1.4.0）
- minSdk 29（Android 10+）
- Linux 运行时与 PTY 仅 arm64-v8a；其它 ABI 由自检页明确提示，其余功能不受影响

### 🔧 工程
- 修正上游同步中的同名文件串位（`McpHttpHeaders` / `KoinModule` / `strings` 各有两份同名文件）
- `feature:settings` 补 `room-runtime`，`SelfCheckScreen` 跨模块公开

---

## 塔菲逆核 v1.4.0 —— 太墟（TaiXu）整树合并

这是把 [wkbin/taixu](https://github.com/wkbin/taixu)（Android 无 Root Linux 运行时 · Agent Harness · 可视化工作流 · PTY 终端 · 工作区与工具生态）**整树移植**进塔菲逆核的第一个版本：2013 个 Kotlin 文件、37 个 Gradle 模块原样入库，UI 与布局直接沿用上游实现。

### ✨ 新特性
- **主界面换成太墟壳**：首页（太墟）· 智枢（AI 结对）· 机器人 · 工坊（工作区）· **逆向** · 乾坤（设置）
- **「逆向」中枢 = 塔菲原工作区**：首页 / 分析 / 任务 / 编辑器 / 设置 原样嵌入，经 `HostWorkspaceRegistry` 注入，不再另开一套底部导航
- **Linux Runtime**：PRoot 无 Root 运行 ARM64 Linux，RootFS 按 OCI 镜像安装，支持多发行版、绑定挂载与共享存储
- **Agent Harness**：OpenAI Chat Completions / Responses、Anthropic Messages、Gemini 三种协议，流式正文 + reasoning + 工具调用 + 视觉输入 + token 账本 + 上下文压缩
- **子智能体与规划**：多子智能体调度、token 预算、写路径租约、Planner/Executor 分离、AI 圆桌
- **可视化工作流**：可编辑 DAG（条件边 / 并行 / 人工审批 / 运行历史 / WorkManager 定时）
- **原生 PTY 终端**：`openpty + fork + setsid + TIOCSCTTY + execve` 真控制终端，支持作业控制与多会话
- **工坊与构建**：新建 / ZIP 导入 / Git 克隆 / 构建 / APK 交付闭环，JGit 分支管理
- **MCP**：STDIO / Streamable HTTP / Legacy SSE / OAuth PKCE，内置 Browser / SQLite / Git / APK 审计等预设
- **插件平台**：原生插件、PROCESS 包（v1/v2 + SHA-256 收据）、WEB 包（能力授权 + 独立 WebView 来源）
- **浏览器 / 群聊机器人 / A2UI / 统计账本 / 局域网 WebChat** 随模块树一并可用

### ⚠️ 兼容性变化
- **minSdk 26 → 29**（Android 10+）：太墟模块统一按 API 29 设计，低版本设备将无法安装
- 插件与依赖版本统一到太墟版本目录：AGP 9.4.1 / Kotlin 2.4.21 / Compose BOM 2026.09
- **Linux 运行时与 PTY 仅 arm64-v8a 可用**（PRoot / libpty_native / libtermux 均为 ARM64 产物）；其它 ABI 上这两项不可用，其余功能正常
- 质量门调整为 `:app:testDebugUnitTest` + `:core:model:test`（上游 Robolectric 测试需按模块补环境后再放开）

### 🔧 工程
- 引入 `build-logic` convention 插件、版本目录与本地 AAR 仓库（termux terminal-emulator / terminal-view）
- 太墟 FileProvider 改用独立 authority（`.taixu.fileprovider`），与宿主 `.fileprovider` 并存
- 太墟 release keep 规则以 `consumer-rules.pro` 传递给宿主 R8（serialization / Shizuku / Shower / JGit）
- 宿主 `SoReverseApplication` 继承 `TaiXuApplication` 完成双栈装配，太墟初始化异常被隔离，不影响逆向工具链

---

# TaffyNiHe v1.3.121（页面架构对齐：每个二级页自持脚手架 + 运行环境探针接线）

上一版把「顶栏」换成了折叠大标题，但页面架构还是「hub 画标题 + 页面自己滚一段 Column」。
这一版把架构真正对齐太墟：**每个二级页自己拥有 Scaffold + LazyColumn + 折叠大标题**，hub 只负责
路由与转场；同时把此前只写不用的运行环境探针接进结果，并给它们一个可查看的位置。

## 一、页面架构对齐（18 个二级页一次换完）

- 每个二级页现在长这样（与 taixu `PersonalizationScaffold` 一致）：
  `SettingsScaffold(title, onBack) { item / 分组 }`，标题随自身列表滚动折叠；
- hub 不再绘制二级页标题，也不再需要「把滚动量递出去」的 CompositionLocal 方案
  （上一版的 `LocalPageScrollState` / `SettingsSubPageHeader` 已删除，`PageScroll` 回归朴素实现）；
- 服务配置 / 外观 / 保活 / 返回数量 / 导出 / 审计 / Blutter / 隧道 / 桥接 / AI / 更新 / 探针 /
  APK 签名 / 临时工作区 / 工作区 / 权限 / 快照 / 致谢 / 帮助 / 备份恢复 / 统计 / 免责 / 关于
  全部改为「页面自持脚手架」；日志查看器按工具页处理（紧凑沉浸顶栏，日志流需要垂直空间）；
- 页面内容的水平边距改由 `SettingsScaffold` 的 16dp contentPadding 统一，二级页不再各自设内边距。

## 二、运行环境探针：从「只写不用」到「有结论、有出处」

`IntegrityGuard` 里那批探针（调试器 / tracer / FART-ROM / 进程内 hook 帧 / `PackageInfo.CREATOR`
换类 / maps 特征）此前只定义、没有任何调用点，等于死代码。这一版：

- `verify()` 会把探针结果并入 `Result.threats`（**不改 `trusted`**——签名结论仍只由签名与内容决定，
  避免把逆向场景常开的 root/调试器做成硬门禁）；
- 探针自带 30s 缓存：`Thread.getAllStackTraces()` 与 `/proc` 读取不能跟着 UI 的 3s 轮询跑；
- 补上上游剩余两项判据：`appComponentFactory` 白名单（未声明 / androidx 合并的
  `CoreComponentFactory` / 本应用包前缀，其余视为被 DPatch 一类框架改写）与「代理 Application」
  （实例类名必须是 manifest 声明的 `SoReverseApplication`）；新增 2 条单测钉住白名单边界。

## 三、新增「设置 → 自检」

- 位置：设置根页「诊断与关于」第一项（快捷搜索也收录了「自检 / 完整性 / 威胁」等关键词）；
- 内容：安装包完整性结论（通过 / 未通过 + 原因）+ 运行环境威胁清单（逐条说明「只作提示」）+
  「重新检测」按钮；空态给出「未命中调试器、tracer、脱壳 ROM、hook 帧或过签框架」的明确说明。

## 验证

- JVM 单元测试（完整性规则用例 8 条）。
- Debug Kotlin/Java 编译、Release APK 构建与签名校验。

# TaffyNiHe v1.3.120（设置子页全部换折叠大标题 + 完整性判据补齐）

上一版把设置**根页**换成了太墟体系，二级页还留着旧的固定顶栏；这一版把所有二级设置页
一次性换成太墟的「折叠大标题」，并把上游 SOMCP v1.0.22 里最后两项低误报完整性判据补齐。

## 一、设置子页：34sp 大标题 → 滚动收成 22sp

太墟（taixu）二级页的观感来自「大标题随内容滚动收缩」，而塔菲的设置子页各有各的滚动容器
（`PageScroll` / 自绘 `Column`），直接改成 `LazyColumn` 架构要动 20 个页面。

改为让**滚动源自己把位置递出来**：

- `PageScroll` 新增 `LocalPageScrollState`（CompositionLocal 暴露 `ScrollState`）；
- 新增 `SettingsSubPageHeader`：标题字号 34sp→22sp、行高 40sp→28sp、顶栏高度 96dp→64dp，
  随滚动偏移线性收缩；只有它自己读 `scroll.value`，不会让整页每帧重组；
- `SettingsHub` 的子页外壳把 `ScreenHeader` 换成 `SettingsSubPageHeader`——**一次接线，
  服务配置 / 外观 / 返回数量 / 导出 / 审计 / 隧道 / 桥接 / AI / 更新 / 探针 / 致谢 /
  权限 / 快照 / Blutter / 工作区 / 统计 / 免责 / 关于 全部生效**；
- 备份恢复页与帮助页原本各自复刻了一份 `PageScroll` 的 `Column`，并入 `PageScroll`
  （顺带获得折叠行为）；编辑快照页因内含独立 `LazyColumn` 列表，保留大标题不折叠（避免嵌套滚动）；
- `SurfacePanel`（外观 / 权限等页自绘的 `clip + background + border` 伪卡片）改为 M3 `Card`：
  `surfaceContainer` + `extraLarge` 圆角 + 零描边 + 16dp 内边距。

## 二、完整性校验：补齐上游最后两项低误报判据

- **进程内 hook 帧**：栈帧里出现 `de.robv.android.xposed.` / `io.github.libxposed.` 包前缀即判定
  注入（只比包前缀，仿冒包名不会命中）。注意这只在**本进程真被 hook** 时触发——设备上装了
  LSPosed 但没勾选本应用不会误报，所以这条可以安全启用。
- **`PackageInfo.CREATOR` 被换成外来类**：一键过签框架（SoLab/DPatch/LSPatch）换 Creator 来改写
  签名。判据是 `CREATOR.classLoader === PackageInfo.classLoader` 的身份比较，而不是常见的
  「classLoader 为 null」——后者在 ART 模块 / APEX / 定制 framework 的机器上会把正版判成被改。
- 新增 3 条单测（模块帧命中、普通应用帧不命中、前缀边界）。
- 仍然**不取**：同类分发版检测（上游自己的分发纠纷）与通用 `appComponentFactory` 检测
  （合并后的 manifest 可能带三方 factory，误报代价大于收益）。

## 三、上游核对

- **SOMCP**：v1.0.22（2026-10-19）之后无新提交，`#170` Flutter 链路、`317178e` ROM 脱壳检测、
  `#172` 等均已对齐，本次再补齐 hook 帧 / CREATOR 两项；
- **太墟 taixu**：最新发布 v1.3.0（2026-10-20），设置体系源码（`SettingsScreen` / `SettingsDensity` /
  `SettingsPreferenceRows` / `LinuxEnvironmentSettingsScreen` / `SettingsSearchScreen`）最后改动
  仍是 10-16～10-19，本版采用的即该版本；v1.3.0 的更新集中在群聊机器人、自定义迭代与统计账本，
  与设置布局无关。

## 验证

- JVM 单元测试（新增 3 条完整性规则用例）。
- Debug Kotlin/Java 编译、Release APK 构建与签名校验。

# TaffyNiHe v1.3.119（上游 SOMCP 完整对齐 + 设置页换太墟体系）

这一版把两件事做到「完整」：SOMCP 侧凡是能安全落地的改进全部对齐（Flutter 分析链路整条重写、
ROM 级脱壳检测），设置界面整体换成太墟（taixu）的布局与操作体系（根页拼接卡 + 设置全局搜索 +
开发环境二级页），并把页面基座一次性换到同一套规格，后续新页面无需再单独调样式。

## 一、Flutter 分析链路：按上游 #170 整条重做（映射优先 + 流式落盘）

旧实现的瓶颈在内存：`libapp.so` / `libflutter.so` 先从 APK 解成 `ByteArray`，再写进任务目录，
一份库在堆里同时存在两份副本；体积大的 Flutter 包（两个库各上百 MB）在手机上直接顶到 OOM。

- **库文件直落盘**：`FlutterArtifactInspector.extractLibrariesTo()` 把两个库从 APK 直接流式写进
  `blutter/v1/jobs/<id>/input/`，边写边算大小与 SHA-256，堆里不再有库的副本；结果缓存 key
  与原实现字节级一致（文件摘要 = 内容摘要），历史缓存继续命中。
- **run 阶段传入文件而不是字节**：`BlutterEmbeddedBackend` 用任务目录里的文件直接 open 成
  `ParcelFileDescriptor` 交给 runner 进程，中间少一次全量拷贝。
- **新增 `FlutterByteScan`**：在字节层面扫 snapshot 指纹（compressed pointers / 版本特征），
  与「符号/映射优先」的判定互证；判定顺序改为**映射优先、字节兜底**，版本误判明显减少。
- **runner 匹配加权**：`BlutterRunnerMatcher` 以 snapshot hash 为主证据、engine revision 与
  Flutter 版本为次证据排序，`coverageHint` 给出「最接近的几个 runner」而不是只回一句不支持。
- **内存守卫保留并前移**：读结果前用 `MemoryGuard` 估算（结果解析要文本 + 语法树两份），
  余量不足返回 `INSUFFICIENT_MEMORY`，不再让 `org.json` 抛 `OutOfMemoryError`；结果分页
  保留单份解析缓存 + 浅拷贝，翻页不再重复解析整份文档。
- 配套单测：`FlutterArtifactInspectorTest`（流式抽取 / ABI 选择 / 指纹判定）、
  `BlutterRunnerMatcherTest`（权重与版本序）。

## 二、ROM 级 ART/DEX 转储检测（对齐上游 `317178e`）

FART / Ark 系脱壳机不改我们的进程，改的是平台本身：`dalvik.system.DexFile` 多出
`setFartDumpEnabled` 开关、`android.app.ActivityThread` 多出 `runFartDump` 等整条管线、
`libart.so` 导出 `DumpFartDexFileDirect`，并把 DEX 拉到 `/sdcard/Ark脱壳机/`。这些成员全在
boot classpath 上，**原版平台一个都不声明**，所以本进程反射即可判定，不需要权限、不需要 hook。

- 规则拆成两个纯函数（`fartDumpThreat` / `arkUnpackRomThreat`）并加 6 条单测，钉住
  「原版平台的 dump 面（`handleDumpActivity` 等）不误判」「空清单不误报」「按成员名精确匹配」；
- 反射用 `initialize = false`：只看声明的成员表，不触发类初始化；
- 只取这两条**低误报**判据。上游的「进程内 Xposed 帧探针」与「同类分发版检测」刻意不取：
  前者会把装了 LSPosed 的正常设备判成被篡改，后者是上游自己的分发纠纷，与本产品无关。

## 三、设置页换成太墟（taixu）体系

参考 `wkbin/taixu` 的 `feature/settings`，把设置从「Exbin 风分组面板」改成太墟的
「大标题 + 搜索 + 拼接卡」：

- **根页**：`LargeTopAppBar` 大标题（滚动收起）+ 圆形搜索胶囊 + 运行态卡（端口 / 工具数 /
  鉴权）+ 六个分组；每组是一张「拼接卡」——顶行上方外圆角、中间行小圆角、底行下方外圆角，
  视觉上连成一张卡但不做嵌套；分类入口行 96dp、48dp `secondaryContainer` 图标瓦片、
  右侧是**状态摘要**（如 `:8080`、`36 项`）而不是开关。
- **开发环境二级页**（新增）：原来根页里 7 行摊平成三组——运行环境（Linux / 终端 / 本地可执行）、
  开发工具（编辑器 / Git / 扩展）、工作区（默认目录 / 临时工作区）。
- **设置全局搜索**（新增）：39 项索引（标题 / 说明 / 关键词 / 归属分组），空查询时给 8 个热门
  标签，命中按分组收拢成拼接卡，点行直达目标页。
- **基座统一**：`GlassGroup` / `AppCard` / `NavRow` / `ScreenHeader` 一次性换到太墟规格
  （`surfaceContainer` + `extraLarge` 圆角 + 无描边；组标题在卡外用 `primary`；行高 64dp、
  标题 `titleMedium`；`NavRow` 新增 `value` 参数承载状态摘要；设置子页标题改 24sp 常规字重）。
  这一步让 60+ 个既有页面在零改动的情况下同步换上新观感。

## 四、说明

- 上游 `AppListGuard`（同类分发版检测）与 Xposed 帧探针**未移植**，理由见第二节；
- 上游 CI/VMP 壳相关提交（`#169`、`#163`、`cebfd86b` 等）属发布流程与壳加固，与本项目流水线无关。

## 验证

- JVM 单元测试（含新增 Flutter 与完整性规则测试）。
- Debug Kotlin/Java 编译、四 ABI 原生编译、Release APK 构建与签名校验。

# TaffyNiHe v1.3.118（收尾三项 + 借鉴上游）

把上一版留下的三条「物理上最难」的问题收掉，同时把上游 SOMCP #170 里能安全摘出来的一片
（结果分页内存）移植过来。

## 一、heavy 闸门：从「软租约」到「可观测 + 可恢复」

`future.cancel` 打不断 native 调用，这是平台限制；所以上一版的租约只是保证「不会永久锁死」。
这一版把「发生过什么」和「怎么恢复」补完整：

- 租约超期被回收时置 `poisoned = true` 并累加 `leaked`，`/health` 新增 `heavy` 字段
  （`permits / inFlight / leaked / poisoned`），客户端与状态页都能看到「有任务可能仍在后台跑」。
- 设置 → 服务与连接 新增 **「重建引擎（清理僵尸任务）」**：释放引擎持有的工作区/模拟器会话/
  Frida 会话/SQLite 句柄并清除告警（`EngineProvider.close` + `HeavyGate.clearPoisoned`）。
- `HeavyGate` 从 `mcp` 包移到 `core` 包：UI 与 core 层都能访问它（原来只能被 MCP 层引用，
  想加恢复入口都做不到）。
- 新任务在 `poisoned` 状态下仍然放行（否则功能等于永久不可用），只是串行 + 告警，避免
  与僵尸任务并发写同一份产物。

## 二、DexKit 搜索不再复制整个 dex

`taffy_dex_search action=method_strings` 原来把 APK 里每个 `classes*.dex` 整份复制成临时文件、
再用 `loadDexFile` 载入 —— 200MB 级多 dex 包会在磁盘和堆上各翻一倍。现在改用
`DexFileFactory.loadDexContainer` 直接按条目定位，读哪个 dex 由容器负责，不再落临时文件。

## 三、抓包代理的流控

- **并发连接上限 32**：每条连接一个线程 + 30s soTimeout，超限直接回 `503` 并关闭，不再为
  每条新连接开线程（连接风暴会把线程数打爆）。
- **单连接双向各 512MB 转身上限**：`CONNECT` 隧道、WebSocket 双向、以及没有
  `Content-Length` 的流式响应都必须有终点，否则一条连接可以把代理拖到天荒地老。

## 四、借鉴上游 SOMCP #170（Flutter 内存）

- **结果分页不再每页重解析整份 `result.json`**：新增单份结果缓存（同一 job 连续翻页只解析
  一次），并把 `JSONObject(result.toString())` 换成浅拷贝 —— 后者会把整份结果重新序列化再
  解析，每翻一页峰值内存翻一倍。
- 上游 #170 的主体（Flutter 解析改「映射优先 + 流式落盘」，6 个文件 ~900 行）与 native 交互
  耦合很深，单独一轮做更稳，不在本版塞入。

## 五、上游核对结论（本次一并核过，无需改）

- `Fix update download picking the wrong ABI APK`：塔菲早已用「-<abi>.」整段匹配 + 按
  `SUPPORTED_ABIS` 优先序选包，不会出现 `x86` 命中 `x86_64`、`armeabi` 命中 `armeabi-v7a`。
- `完整性守卫类加 -keep`：`IntegrityGuard` / `SelfArtifactGuard` / `NativeProbe` / `BackupCrypto`
  都已在 `proguard-rules.pro` 里 keep。
- `删除壳的 frida_maps 探针`：塔菲的 `InjectionProbe` 刻意不含 `frida`/`gadget` 关键字，
  不会因为文件里出现 "frida" 字样而误杀。
- 太墟（taixu）v1.2.1 的更新集中在 bot 群聊/团队协作、任务调度时区、ADB 候选回退与插件 v2
  授权——与塔菲（逆向工具）没有交集；其插件授权模型塔菲已用 `PluginManager` 权限位覆盖。

# TaffyNiHe v1.3.117（收尾 4 项 · 渲染与挂载）

紧接 v1.3.116 把剩下几处低优先但真实可感知的问题收掉。

## 一、Markdown 渲染不再卡首帧

- 旧实现用 `remember { parseMarkdown(content) }`：**在组合线程（主线程）**对整段文本做
  `buildMarkdownTreeFromString` + `HtmlGenerator` + `Jsoup.parse`，而调用方喂进来的常是
  几万字的子代理报告或流式推理正文 —— 打开历史报告时明显卡顿，文本更大直接 ANR；
  且 `LaunchedEffect(Unit)` 又对同一初始值再解析一遍（首次进入解析两次）。
- 现在首帧只渲染纯文本，解析全部在后台完成后再替换（`parsed` 可空 + 空态降级）。

## 二、chroot 的挂载/卸载拆开

- 原来 `mount proc/sys/dev` → `chroot` 执行 → `umount` 全在**同一条 su 命令串**里。超时或被杀时
  `umount` 段不会执行，挂载点永久残留在 rootfs 内，之后 `umount` busy、重解 rootfs 失败，
  多次执行还会累积。
- 现在执行与卸载是两次独立调用，卸载放在 `finally`（超时/异常也会跑），并带 15s 上限。

## 三、日志行数统计不再全量扫描

- `status` / `stop` 会对录制文件做一次 `useLines { count() }`。一个跑了几小时的
  `*:V` 录制文件几十 MB，每次查询都是 O(n) 磁盘 IO（UI 卡一下）。
- 现在 ≤8MB 精确统计，超过则按平均行宽估算（`bytes / 90`）。

## 四、偏好恢复的写入结果参与成败

- `applyPrefs` 里 `editor.commit()` 的返回值被丢弃，磁盘满导致写失败时仍报「恢复成功」。
  现在 commit 失败即视为该次恢复失败（走回滚路径）。

---

上一版（v1.3.116）的完整清单见下一节。

# TaffyNiHe v1.3.116（第五轮深挖 · 连上一轮留下的四条一起补完）

这一轮先补上一轮明确留下的 4 条已知风险，再往下查了三个没审过的域（多智能体 / blutter
隔离进程 / 权限与 ELF 回退解析），共修 30 处。其中 2 处会让整个功能在 Android 8–12 上
直接崩，1 处让「续跑」100% 失效。

## 一、上一轮留下的 4 条（已全部修掉）

- **heavy 许可会被永不返回的 native 调用永久锁死**：`future.cancel` 打不断 native，
  许可由任务自身在「真正结束」时释放，一旦真的阻塞就再也回不来（所有重工具恒
  `SERVER_BUSY`，只能重启）。现在改为进程级 `HeavyGate`：每次获取登记租约，看门狗每 15s
  回收超期槽位并计入 `leaked`；任务之后若真的返回，release 不会重复释放（许可不会凭空变多）。
- **脚本绕过并发限制**：`taffy_script` 里的工具调用原来直接调 handler，与外部调用并发
  操作同一份引擎状态。现在与服务端共用同一个 `HeavyGate`（等不到就返回 `SERVER_BUSY`）。
- **备份恢复不清空目标目录**：只做覆盖写会让「备份里已删除的文件」在恢复后复活，中断时目标
  目录还会停在「新旧混合」状态。现在恢复前把受影响的目录整体挪到 `<name>.restore-old-*`，
  成功丢弃、取消/失败还原（回滚失败时保留原目录并在错误里给出路径）。
- **抓包 stop 不关已建立连接**：`ServerSocket` 关了但连接线程还在转发/记录。现在维护
  `activeSockets`，停止时逐个关闭。

## 二、Android 8–12 上整条提权通道直接崩（新发现，最严重）

- `InputStream.readNBytes(int)` 是 Java 9 API，Android **直到 API 33** 才提供，而 minSdk 26。
  代码里有 7 处调用（Shizuku 执行、LinuxRootfs、内置 Python、解包魔数、抓包输出、字节补丁
  读取）—— 在 Android 8–12 上抛 `NoSuchMethodError`（是 `Error`，`catch (Exception)` 抓不住），
  等于无 root 提权、解包、Python、抓包一起失效。统一改为 `StreamCompat.readCapped`，
  Shizuku 的 catch 也改为 `Throwable`。

## 三、多智能体编排（5 处，含「续跑永远失败」）

- **两套 runId**：返回给客户端的 id 与落盘目录的 id 是两个不同来源 → `resume` 必然
  `NOT_FOUND`，「继续上次」100% 不可用；`list` 里同一次运行还会出现两条。现在只有一个 id 来源。
- **取消/进程被杀时整次编排记录丢失**：`run.json` 只在正常结束时写，取消后已跑完的节点结论
  全丢、历史里也看不到。现在取消也落盘（`status=cancelled`，已完成节点可续跑）。
- **`run.json` 非原子写**：写到一半被杀会留下截断 JSON，`loadRun` 静默返回 null。改 tmp+rename。
- **事件并发写同一个 `JSONObject`**：并行节点同时追加正文/工具列表会互相覆盖，`status()`
  并发序列化还可能抛 `ConcurrentModificationException`。读写共用同一把锁。
- **默认预算 5 < 计划 6 个节点**：汇总（report）节点必然 `skipped_budget`，「交叉印证 + 结论」
  一次都跑不到。预算改为至少覆盖全部节点。
- 终态快照会把整份节点正文（几 MB～几十 MB）长期留在内存，现在收尾丢掉 `record`、10 分钟后清理。

## 四、解析与权限

- **ELF 回退解析没有边界检查**：LIEF 解析失败（畸形/加固 SO）才会走这里，而它每个
  `bytes[o]` 都无边界、重定位循环也不判越界 → 崩溃；伪造 `entsize=1,size=0x7FFFFFFF`
  还能让循环跑 21 亿次（CPU 占满、不抛异常）。现在访问器全部有界返回、循环数按文件大小与
  100 万条双重封顶、`sectionBytes` 的 `size.toInt()` 负数也做了保护。
- **Shizuku 剥引号不反转义**：`quoteArg` 转义过的 `\$`/`\"` 被原样交给 sh，变量不展开、
  退出码判断走错（LinuxRootfs 的清理分支因此失效）。现在剥引号后按 `\ \$ \" \`` 还原。
- **root 判定失败会缓存整个进程生命周期**：开机早期首次 `id` 超时后，之后所有 root 操作
  都静默降级到 Shizuku。失败结果现在只缓存 30s，且 `exec` 命中 `Permission denied` 时立刻失效。
- **`/data/local/tmp` 无条件进白名单**：无隧道/无 root 时它是全局可写投放点。现在只在开隧道
  或有 root 时放行；应用私有目录根统一 `canonicalPath`（`/data/data` ↔ `/data/user/0` 不再误拒）。

## 五、工具层

- **logcat 网关完全不可用**：schema 里 `action` 被 put 两次（枚举被覆盖），且 `action=capture`
  被原样透传给子处理器（必然 `UNKNOWN_ACTION`）。现在拆成 `op` / `captureAction`，也接受
  直接子动作。
- **logcat / root 输出无上限**：`logcat -d *:V` 能产出几十 MB，raw + 行列表 + 结果同时驻留
  → OOM。root 通道改为有界 sink（4MB 后继续排空但丢弃，标记 truncated），logcat 侧再兜一层。
- **后台录制文件无限增长**：启动时清理更早的会话文件（保留最近 3 个）。
- **终端命令被双层解释**：`-c "$command"` 里的引号会提前闭合、`$变量`/反引号被本层 shell
  展开；内置 Python 分支还会因命令里的引号直接语法错误。统一 base64 → 临时文件 → 执行文件。
- **frida 注入的 `abi` 未校验**：直接拼进 `lib/<abi>`，传 `../..` 可在 APK 目录外建目录落文件。
  加 ABI 白名单；manifest 改写改为「命中校验 + 原子写」（原来直写、替换失败也报成功）。
- **`taffy_web_download` 中途失败留半截文件**：改为 `.part` + rename，失败删除。
- **`taffy_stop_app` 恒报成功**：无 root 时 `killBackgroundProcesses` 实际无效却报成功。
  现在有特权走 `am force-stop`，否则标 `best-effort` 并说明。
- **`taffy_dex_search` 的 limit 与文档不符**（内部 `limit*2`）→ 按文档返回。
- **blutter 取消不回调**：排队中被取消的任务体永不执行，调用方永远停在 running、10% 不动。
  现在取消/早退都主动回调并 `stopSelf`。

## 六、界面与安全

- **Markdown 链接未校验 scheme**：模型输出 `intent://` / `file://` 时点一下直接崩 App
  （`ActivityNotFoundException` / `FileUriExposedException`）。只放行 http/https/mailto。
- **A2UI chip 是空点击**：有涟漪、有可点击语义，点下去毫无反应；列表项也没 key。已回传
  `onAction("chip", …)` 并补稳定 key。
- **完整性门禁的 fail-open 死分支**：注释写 fail-closed，代码是 `trusted = true`。改为拒绝。
- **签名密钥库口令明文存 SharedPreferences**：与 accessToken 同等级敏感，改走 `SecretStore`
  加密并迁移旧值。
- **native 探针 `matches()` 缺 loaded 守卫**：`.so` 加载失败时直接调 JNI 会抛
  `UnsatisfiedLinkError`（Error，`catch (Exception)` 拦不住）。改走带守卫的入口。

# TaffyNiHe v1.3.115（第四轮深挖 · 会毁文件的那一类）

这一轮换了审法：把「写回用户文件」和「跨线程共享状态」两条路径逐行重读。查出 26 处真缺陷，
其中 3 处会直接损坏用户的 APK、4 处会让报告与产物不一致、2 处是安全暴露面。

## 一、会毁掉原文件（最优先）

- **manifest 编辑直接就地重写原 APK**：ARSCLib 的 `writeApk` 是先截断再写，磁盘满或被杀
  就让用户的 APK 变成半截，而且没有任何副本。现在先写同目录临时文件 + `.bak.apk`，成功后
  原子替换（`ATOMIC_MOVE`）。
- **smali / 字节补丁写回是非原子的**：4 个写回点都走 `copyTo(overwrite=true)`，
  `replace_string` 更是「先 delete 原包再 copy」——中途失败原包直接消失。全部改为原子替换。
- **快照 id 会撞车**：`${currentTimeMillis}-${path.hashCode}` 在同一毫秒对同一路径做第二次
  快照时 `mkdirs()` 返回 false，静默返回 null（调用方全都不看返回值），那次修改没有任何快照
  可回滚。id 加随机后缀，快照与回滚也改原子写。
- **备份恢复逐条目直接覆盖**：恢复中断会留下半截文件；且单条目解压没有上限，畸形备份包
  （解压炸弹）能写满磁盘。现在逐条目临时文件 + 原子替换，并按 zip 声明大小设上限。
- **回滚「已回滚」是假保证**：`rollbackOk` 只看 `recoverFrom` 有没有抛异常，而它对每一条
  写入失败只记 `skipped` 不抛；manifest 读不出来时更是一个文件都不恢复仍报成功。现在按
  manifest 是否可读 + revert 是否有关键失败来判定。

## 二、重打包 / 假成功

- **`lib/` 下的 .so 只对齐到 4 字节**（注释写的是页对齐）：改一个字节后未压缩的 so 失去
  4096 页对齐，安装器改为解压存放或 mmap 失败。另外 padding 是裸追加字节，会让 extra 结构
  出现残缺的 (id,size) 头。现在 .so 用 4096、padding 写成合法的 `0xd935` 对齐块。
- **STORED 条目整条读进堆**：原本就是 STORED 且无替换的条目（多 ABI 的 so、arsc）无需重算
  CRC，改为流式复制，重打包大 APK 不再多占几十 MB 堆。
- **`copyAll` 没有「是否命中」的返回值**：条目名对不上时整包原样复制，工具仍报
  `success=true` + 新字节数。现在返回命中集合，4 个调用点断言，不命中直接
  `PATCH_NOT_APPLIED` 并删除临时产物。

## 三、跨线程共享状态

- **unidbg 会话关闭检查只做在 1 个入口**：其余 28 个会话操作（dump/寄存器/内存写/模块/
  trace/hook/断点/反射…）直接拿着已释放的 native VM 调用 —— `runCatching` 捕不到 SIGSEGV，
  整个 App 崩溃。全部补 `SESSION_CLOSED` 检查。
- **工作区并发 open 会留孤儿**：`workspaceBySourceKey` 的 check-then-act 非原子，两个调用
  同时 miss 时各建一份，后写者覆盖索引，先建的再也关不掉。改为锁内双检登记。
- **淘汰会删掉正在用的临时工作区**：`temporary` 工作区没有「最近使用」保护，长跑分析中途
  被另一次 open 淘汰 → `WORKSPACE_NOT_FOUND` 且模拟会话被关。现在所有工作区统一 30s 保护。
- **模糊匹配会命中错误的 SO**：`findSource` 末尾有 `path.contains(path)` 兜底，
  请求 `libfoo.so` 会命中 `libmyfoo.so`；而 `build(writeToWorkDir)` 复用它定位已存在文件后
  以截断模式覆盖 —— 用户的原始文件被改写成别的文件内容。去掉子串兜底 + 写回改精确匹配。
- **APK 扫描缓存永不命中**：APK 内 SO 的 `modified` 传的是 `System.currentTimeMillis()`、
  size 固定 0，缓存 key 每次扫描都不同；`source_summaries` 只 insert 不删旧行，表随使用
  无限膨胀。改用 walk 拿到的真实 size/mtime，并按 path 先删旧行。
- **批处理早退不释放事务快照**：非法步骤 / 快照失败 / 首错中止三条路径都直接返回，
  `batch-transaction-*` 快照（每份是整份 .so 字节）留在内存里，反复失败即渐进 OOM。
  改为 `released()` 统一释放，取消异常也重新抛出。
- **签名分析的 `setSoPath` 是进程级静态状态**：两个工作区并发分析会互相覆盖，A 的签名按
  B 的文件算出来。串行化这一小段。
- **content:// 输入写固定文件名**：不同 APK 里的 `libfoo.so` 落到同一路径，之后按路径回读
  （写回原文件 / 签名分析）操作到另一个文件的字节。改为随机前缀 + `.part` 原子落盘。

## 四、安全暴露面

- **抓包代理绑 0.0.0.0**：界面文案写的就是 `127.0.0.1:8888`，但监听所有网卡等于把手机变成
  局域网开放代理，还能借它访问手机自身的 127.0.0.1 服务与内网段。改为只绑回环。
- **沙箱标识符白名单放行 `$`**：命令是未加引号拼进 `sh -c`/`su -c` 的，`$IFS` 会被展开成
  空格，可向 root shell 注入额外参数。去掉 `$`。

## 五、服务与界面

- **MCP 服务停止不收口**：`stop()` 不 `shutdownNow` 工具线程池、不 cancel 作用域，且为了
  「停止」反而 new 出一个从未启动的 APK 桥。现已全部回收。
- **停止后立刻启动会绑端口失败**：拆除在后台线程做（grace 400ms + timeout 1500ms），
  服务会「启动一下就自己消失」。现在重启前等拆除线程（最多 2s）。
- **「我关了 MCP」会被 START_STICKY 自己拉起来**：以 null intent 重投时无条件 startServer。
  现在把用户意图持久化，null intent 且意图为关 → 直接 stopSelf。
- **隧道启动静默挂起永不超时**：30s 超时判定写在「读到输出行」的回调里，cloudflared 既不
  输出也不退出时一次都不执行。加独立看门狗线程。
- **日志页满 2000 行后永久冻结**：`remember(logs.size, …)` 的 key 不再变化，新日志一条都
  看不到；自动滚底也停在 300 行。改用首尾元素参与 key；导出改为导全量（原来只导 300 行）。
- **编辑器大文件「只读模式」只进不出**：打开过一次 200KB+ 文件后所有文件都不可编辑，
  界面上也没有常规退出入口。改为双向判断。
- **CFG 请求竞态**：切函数时旧请求晚到会把 A 的控制流写进 B 的页面，而且「是否需要重取」
  的判定还会因此成立而不再自愈。加请求令牌与 `ws|target` 键。
- **打开新文件不重置分析状态**：选中的函数名 / CFG / 签名缓存残留，新文件里遇到同名
  （`sub_xxxx`、`JNI_OnLoad`）会直接显示上一个文件的数据。打开后统一重置。
- **Rizin / Git / Linux 页 busy 没有 try/finally**：引擎或 proot 异常后整页按钮永久禁用
  （异常还可能直接崩进程）。全部补 try/catch/finally，取消重抛。
- **编排页目标与预算切 tab 就丢**（只有 runId 被保存），改为 `rememberSaveable`。
- **签名批量还原进度不复位**：被打断后计数行永久停在 `x/40`，用 finally 复位。

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




















