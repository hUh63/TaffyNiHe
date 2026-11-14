## 塔菲逆核 v1.5.69 —— AI 多通道 / 网关并发保护 / 脱壳链自动补 AES

### 1. AI 多通道（对齐 SysKit 的 AiChannelConfigManager）

此前塔菲只有一套 AI 配置：换中转站就得把协议/端点/key/模型全改一遍，
最容易出的错是「换了 key 忘了换端点」，只有发请求报错时才发现。

现在每条通道自带 provider / endpoint / key / model / 温度 / 自定义 headers+body，
在「设置 → AI 深度分析」顶部的通道卡片里切换、新建、重命名、删除（至少保留一条）。
系统提示词与工具调用策略仍是全局的——那是策略不是通道。

- **老配置无损迁移**：第一次读通道列表时把原来摊在 prefs 上的那套配置包成「默认通道」，
  key 从 `aiApiKey` 复制到 `aiChannelKey_default`，老 key 不删（回退旧版本还能用）。
- **单点写入**：`aiProvider` 等属性全部转发到「激活通道」，读写都只有一条路径，
  不会出现某个页面读到全局、另一个页面读到通道的两套值。
- **备份恢复后作废缓存**：恢复会直接重写 prefs，不清缓存就会出现「恢复了但 AI 还在用旧 key」。
- 本地 AI 网关跟着激活通道走。

### 2. 网关并发保护

转发是阻塞 IO，而 ktor CIO 的 worker 线程数只有 CPU 核数：并发一多请求就排在线程池外，
客户端看到的是「没响应」而不是错误，最难查。现在同时处理上限 8，超出立刻回 **429**
并给出明确文案，客户端可以据此退避。

### 3. 脱壳链 `action=auto`

`taffy_dex_unpack_chain` 新增 `auto`：一轮（明文/XOR）没解出任何 dex 时，就地补一轮 AES，
不用先看 `next` 再回来点第二次。

AES 一轮是「候选 key × 起点 × IV × 模式」全组合，单文件几 MB 探测段就要解近百 MB，
所以**只对没命中且 ≤8MB 的文件跑，最多 6 个**——否则一个 64 文件的 dump 目录会变成几十分钟。
两次的 stages 用 `(aes)` 后缀区分，DEX 扫描页的「跑完整链」默认就走 auto。

### 4. 其它

- 脱壳链目录为空时也给出 `next`（指向 `taffy_dex_unpack`），不再只返回一堆 0。
- `taffy_dex_unpack_chain` 的 `aesFilesTried` 如实反映实际尝试了几个文件。

### 5. 验证

新增 3 条单测：通道 JSON 往返、非法 provider 纠正、缺 id 拒绝。测试总数 520。

## 塔菲逆核 v1.5.68 —— 本地 AI 网关（OpenAI 兼容端点）

这一版补上 SysKit 里最后一个有实用价值的能力：把塔菲已经配好的 AI 渠道暴露成一个
**OpenAI 兼容的本地端点**，让电脑上的客户端直接用手机里的 key。

### 1. 本地 AI 网关

在「设置 → AI 深度分析」底部新增卡片：端口 + 访问 token + 启动/停止 + 用量统计。

端点：

| 端点 | 作用 |
|---|---|
| `POST /v1/chat/completions` | 原样转发到已配置渠道，支持流式（逐块转发，不整段缓冲） |
| `GET /v1/models` | 返回当前配置的模型 |
| `GET /health` | 最小健康状态 + 累计用量 |

几条刻意的设计约束：

- **只监听 127.0.0.1**。网关手里有 apiKey，绑 0.0.0.0 等于把带 key 的代理开放给同网段
  （抓包代理踩过这个坑）。要给电脑用就 `adb forward tcp:8787 tcp:8787`，或者走隧道。
- **只做 OpenAI 兼容透传**。provider 是 anthropic/gemini 时直接明确报错，而不是猜着转协议——
  协议转换半对时的表现是"回答变成半截/乱码"，比直接拒绝难查得多。
- **请求体只改 `model` 字段**。messages/temperature/tools 等一律原样透传，网关不悄悄改变客户端行为。
- **token 进 SecretStore**，不落明文 prefs（与 aiApiKey 同一套）。
- **用量可观测**：从非流式 JSON 与流式 SSE 尾部都能解析出 prompt/completion tokens 并累计，
  设置页可直接看请求数、token 总量、以及清零。

端口占用会明确报出来（ktor/CIO 的 bind 在后台协程里做，不自己探测就会出现
"显示运行中但端口没监听"）。

### 2. 脱壳链枚举上限

`taffy_dex_unpack_chain` 的目录枚举加了上限（`maxFiles × 128`，512 ~ 20 万），
避免把 `dir` 指到 `/sdcard` 这类目录时「一键脱壳」变成「一键卡死」；
触到上限会在 `list` 阶段明确标出并建议直接指向 dump 目录。

### 3. 验证

新增 10 条网关单测：model 改写保留其它字段、非法 JSON 回退、三种 provider 的认证头、
自定义头解析、token 校验（空 = 开放 / bearer 大小写 / 不匹配）、
usage 解析（非流式 JSON / 流式 SSE 尾部 / 无 usage）。测试总数 517。

## 塔菲逆核 v1.5.67 —— 一键脱壳链 / xorN 扫描越界修复

这一版把此前分散的三支脱壳工具串成一条链，并修掉一个让 xorN 扫描完全不可用的越界 bug。

### 1. `taffy_dex_unpack_chain` —— 一键脱壳链

`dump → 扫描 → 解密 → 修头 → 落盘 → 复核`，一条链跑完。给包名会先从运行中的进程 dump 内存
（需 root，且要先把目标 App 打开让壳解密 dex 进内存），也可以直接指一个已有 dump 目录。

链本身的难点不是"会不会解"，而是**脱不出来时判断断在哪一段**，所以返回值按段给结论：

| stage | 结论含义 |
|---|---|
| `dump` | 内存 dump 是否成功、pid、dump 出几个 dex |
| `list` | 枚举到多少文件、跳过了几个超大文件、是否触到上限 |
| `scan` | 扫了几个文件、命中几处（明文/XOR 与 AES 分别计数） |
| `recover` | 写出几个 dex 到哪 |
| `fix` | 对几个产物重算了 checksum/signature/file_size |
| `verify` | 写出后再扫一遍，强校验通过几个、仅结构合法几个 |

外加 `failures`（哪一步、哪个文件、为什么）与 `next`（下一步该干什么：先 dump / 换 key /
检查是否自定义 S-box / 直接喂反编译器）。

配套细节：

- **截断 dump 也能救**：dump 里 `file_size` 常大于实际可读数据，`extract` 会返回 null。
  链路退化为「按 key 解完剩余全部字节」再交给 `DexFixer` 按实际长度重算头。
- **XOR 全不中才试 AES**：AES 穷举贵，不该对每个文件无脑跑。
- **产物目录自动跳过**：输出目录默认在输入目录里（`dex-recovered/`），枚举时排除，
  否则每跑一次就会把自己的产物当输入、越滚越大。
- **同一偏移只留一条**：同一段数据被多种模式命中时解出来是同一份 dex，不去重会重复落盘。

UI 在「设置 → DEX 扫描」顶部：包名/目录二选一 + 「再试 AES」开关 + 一键运行，结果按段显示状态
（① 内存 dump ② 枚举 ③ 扫描 ④ 解密落盘 ⑤ 修头 ⑥ 复核），产物列表用 ✓/· 标出复核结果，
卡点单独列出。

### 2. 修 `xorN` 扫描必崩（ArrayIndexOutOfBounds）

`XorDexScanner` 的 xorN 分支用 `dex\n` 四个字节反推 key 的前 n 字节，但循环写的是 `n in 2..8`，
`keyBuf[j] = data[i + j] xor MAGIC[j]` 在 n≥5 时直接读 `MAGIC[4]` 越界。

后果不是"少扫一点"，而是**任何一次带 xorN 的扫描都会在第一个 n=2..4 都不匹配的位置抛异常**，
被上层 `runCatching` 吞成"扫描失败"——等于 2~8 字节 key 的能力从来没能用过。

修法是把 key 长度上限收到 magic 长度（4）：第 5 字节起无法从 magic 反推，硬取只能越界；
长度 5..8 的 key 需要靠 body 结构暴力验证，代价不值得。

### 3. 修 xor1 `key=0` 重复报

`key=00` 的"单字节异或"等价于没加密，plain 模式已经报过。此前同一段数据会同时出现
`plain` 与 `xor1 key=00` 两条候选（扫描工具里是噪音，链路里会重复落盘两份）。

### 4. 其它

- `taffy_dex_unpack_chain` 接入多智能体「加固与脱壳」角色的工具白名单
  （连同 `taffy_dex_xor_scan` / `taffy_dex_aes`），脱壳角色现在能自己走完这条链。
- 链路没做复核时不再把"写入数"当成"校验通过数"（报告里 `verifyRan` 区分"没检查"与"没通过"）。

### 5. 验证

新增 6 条单测：明文+XOR 混合目录还原、产物目录不递归、无命中时报出失败段落、
截断 dex 走退化路径修复、全模式扫明文只报一条、文件名消毒。测试总数 507。

## 塔菲逆核 v1.5.66 —— AES 加密 DEX 定向解密 / dex 头修复 / 远端 MCP 工具接入 AI

这一版把 SysKit 里最后一块硬骨头（AES + 自定义 AES 表）啃了，同时把上一版留的两处收紧。

### 1. `taffy_dex_aes` —— AES 加密 DEX 的定向解密

对的是加固壳里最难缠的一类：dex 不是简单 XOR，而是 AES 加密，甚至把 S-box 一起搬走/换成自定义表。
四个动作分开给，也可以直接 `auto`：

- **`sbox`**：在数据里找 AES 表（标准 S-box / 逆 S-box / Rcon 的 32 字节特征）。
  找到 → 说明是标准 AES，key 就藏在附近；只找到一部分 → 可能是自定义表或被切分存放，
  标准 AES 解不开，得先从运行时 dump 表。
- **`keys`**：从数据里挑"像 key"的 16/24/32 字节块。
- **`decrypt`**：给定 key(+iv/mode/unpad) 直接解，解完自动扫 dex、可选再 XOR 一层。
- **`auto`**：候选 key × 多种 IV × CBC/ECB × **密文起点偏移** 穷举，
  命中直接报 key/iv/mode，并把 dex 落盘。

两个实现细节值得说明，都是踩过坑才定下来的：

- **key 候选的排序不能按熵**：加密数据区里任意 16 字节都是满熵，按熵排会把它自己顶到前面，
  把真正的 key 挤出候选窗口。改成「不重复 → 16 字节优先 → 靠前偏移优先」，因为 key 通常在常量区/开头。
- **密文起点不确定**：key 后面可能紧跟密文，也可能隔着一小段 header/填充。
  所以对前 256 字节按 16 字节步进全试一遍起点，探测段只解 128KB（17 个起点 × 6 种组合，
  每个候选约 13MB 解密量，秒级），命中后再解全量。

### 2. `taffy_dex_fix`（并入 `taffy_dex_xor_scan` 的 `fix` / `find`）

脱壳出来的 dex 常常"内容对、头不对"：file_size 还是原始大小、adler32/signature 对不上、
endian_tag 被清零。修复策略是"以内容为准重算头"：

1. magic 不对 → 直接拒绝（不是 dex，别硬修）；
2. file_size 与实际不符 → 取较小值写回（多出来的当填充丢掉）；
3. header_size/endian_tag 非法 → 写成标准值；
4. **先重算 SHA-1(signature) 再重算 adler32(checksum)**（顺序不能反，checksum 覆盖 signature）。

另加 `find`：扫出数据里所有 dex 头偏移（magic + header_size + endian_tag 三条同时成立），
用来处理"带一截前缀 / 多个 dex 拼在一起 / 偏移错位"的 dump。
`taffy_dex_xor_scan(extract)` 和 `taffy_dex_aes` 落盘前都会自动修一遍头。

### 3. 远端 MCP 工具接入 AI（AI 直连）

上一版只能"人来调"远端工具。这一版把它们接进 AI 的工具清单：
已启用端点的工具会以 `mcp_<端点>_<工具>` 的名字出现在模型看到列表里，模型可以自己选、
按远端 `inputSchema` 填参数，塔菲负责转发。

- 接线两处：`AgentKernel.catalogTools`（多智能体各角色）与 `DeepAnalysisService.buildRikkaTools`（AI 深度分析）
- **默认关闭**（「MCP 客户端」页里的开关）：外部端点由用户自己配，混进工具清单会放大模型的行动面
- 每端点最多 20 个工具、总计 40 个；工具清单 60 秒缓存
- 整体 8 秒超时：`catalogTools` 是同步函数，远端握手慢不能把整个编排拖死
- 失败静默降级并写日志（远端掉线不该让 AI 会话失败）

### 4. SysKit 剩余项的处置

| SysKit 的东西 | 处置 |
|---|---|
| `aesCbc` / `KeyTables(SBox/InvSBox/Rcon)` | ✅ `AesDexDecryptor`（表识别 + CBC/ECB） |
| `keyCandidates` / `extractTables` | ✅ `keyCandidates` + `findTables` |
| `xorScan` 的"候选穷举 + 校验" | ✅ `autoDecrypt` |
| `quickCheck` / `finalCheck` 的 dex 校验 | ✅ `XorDexScanner` 的 magic+结构+adler32+SHA-1 |
| `fixDex` | ✅ `DexFixer` |
| `findHeaderOffsets` | ✅ `DexFixer.findDexHeaders` |
| `unpad` | ✅ 内置 PKCS7 |
| `payloadOffset` / `parsePayload` | ❌ 绑定它自己的资产格式，没有通用价值 |
| `axmlStrings` / `parseManifest` | ❌ 塔菲用 ARSCLib 更准 |
| `score` / `score3`（字符串可读性打分） | ❌ 只用于它自己的报告排序 |

### 5. 修复

- **`taffy_gadget_one_shot` 的 APK 路径参数改用 `apkPath`**：`apk` 没登记在
  `WorkspacePolicy.PATH_KEYS` 里，等于整条工具绕过工作区路径校验（这一项上一版的发布物里没有，本版补上）
- `FloatConsoleService.start` 返回 Boolean：Android 12+ 在后台调 `startForegroundService`
  会抛 `ForegroundServiceStartNotAllowedException`，以前会崩，现在给明确提示
- `taffy_dex_aes` 的读取上限收到 128MB（整段 AES 解密峰值是输入 2~3 倍）
- 新增 12 条单测（AES 表识别 / key 候选 / CBC 往返 / auto 端到端 / 修头 / 截断 / 找头）

## 塔菲逆核 v1.5.65 —— 加密 DEX 扫描 / MCP 客户端 / 一键注入闭环 / 悬浮控制台

延续上一版对 SysKit 的整合，把剩下三块补齐，并把 gadget 注入串成一条链。

### 1. `taffy_dex_xor_scan` —— 加密 DEX 扫描与还原

壳把 dex 藏起来时常见的三种形态一次扫全：

- `plain`：明文嵌在 so / assets / 内存 dump 里；
- `xor1`：单字节 XOR，256 种 key 全覆盖；
- `xorN`：2..8 字节重复 key，用 `dex\n035\0` 前 8 字节**反推** key 再验证。

判定不靠"看起来像"：magic → 版本号 → header_size=0x70 → endian_tag=0x12345678 →
file_size 区间 → 最后 **adler32 校验和 + SHA-1 签名**双校验。
双校验都过的标 ✓；结构合法但校验不过的单独标 △（脱壳后未修复的典型形态）。
`action=extract` 会把命中的 dex 落盘（按 file_size 截断、去掉填充）。
path 也支持 APK/zip，逐条目扫。

和 `taffy_dex_unpack` 的关系：那个负责"从运行中的进程 dump 内存"，
这个负责"拿到 blob 之后怎么把 dex 从里面认出来并还原"——两步串起来才是完整脱壳链。

### 2. `taffy_mcp_client` —— 塔菲也能当 MCP 客户端

塔菲本来就是 MCP 服务端（`/mcp` + `/sse`），这一版补反方向：
把 PC / 另一台设备 / 局域网里的 MCP 服务接进来。

- `endpoints` 列已存端点、`save` 保存（含 `K: V` 自定义 header，可放 Bearer token）
- `test` 测连通与延迟、`tools` 列远端工具（含参数名）、`call` 调远端工具
- 协议走 MCP 规范：`initialize` 拿 `Mcp-Session-Id` → `notifications/initialized`
  → `tools/list` / `tools/call`；session 过期自动重连一次
- 响应兼容纯 JSON 与 SSE（`data:` 多行、分块累积）

### 3. `taffy_gadget_one_shot` —— gadget 一键注入闭环

原来这条链要手动跑 5 步（decode → 放 gadget → 配 config/脚本 → 注入入口 → 回编 → 重签），
参数记错一步就得重来。现在一条调用跑完，并且**每一步都留痕**：
失败时返回已完成到哪一步、卡在哪一步、为何失败，可以只补那一步。

复用现成工具（不重复实现）：`taffy_apk_rebuild(decode/build)` +
`taffy_apk_frida_gadget(place/patch_entry)` + `taffy_apk_sign`；
gadget 从版本库里挑（按数字段比较版本，`17.10.0` 不会被 `17.9.10` 比下去）。

### 4. 悬浮控制台

在其他应用之上开一个可拖动小窗：实时日志流 + 一行命令（走 root/Shizuku/Dhizuku 通道）。
调壳、调注入时不用切回塔菲就能看输出——目标应用一被切走状态就变，这个窗口解决的就是这件事。

- `TYPE_APPLICATION_OVERLAY` + `specialUse` 前台服务（`dataSync` 有 24h 内 6h 上限）
- 折叠 / 关闭 / 标题栏拖动；日志订阅 `AppLog`，保留最近 300 行
- 刻意用原生 View 而不是 Compose：Service 里挂 ComposeView 要自己搭
  LifecycleOwner/SavedState 提供者，宿主 lifecycle 一变就容易崩

入口：「设置 → 逆向分析 → Frida」页签，带悬浮窗权限引导。

### 5. 其它

- 「设置 → 逆向分析」页签补到 9 项（Rizin / eDBG / 抓包 / 沙箱 / 工作流 / Blutter / Frida / DEX 扫描 / MCP）
- 新增两页：加密 DEX 扫描、MCP 客户端
- 新增 11 条单测（XOR 扫描的明文/单字节/多字节/误报/校验失败/偏移/key 编解码）
- 修：`SettingsDest` 新增两项后 `settingsIcon` 的 `when` 不再穷尽（编译期即被拦下）

## 塔菲逆核 v1.5.64 —— 整合 Frida 工具链（gadget 版本库 / 注入点 / 反分析扫描）

### 1. 三个新工具

- **`taffy_frida_gadget_kit`（Gadget 版本库）**
  `status / versions / download / import / remove / place`。
  官方只发布 `.so.xz`：下载后自动 XZ 解压，并校验 ELF 魔数 + `e_machine` 是否与目标 ABI 一致，
  架构不符当场拒绝——下错架构的 gadget 装进包里只会让目标启动即崩，不如提前挡住。
  本地库按 `<version>/<abi>/libfrida-gadget.so` 存放，`place` 直接落进 decode 目录的 `lib/<abi>/`，
  之后交给 `taffy_apk_frida_gadget` 配 config + 注入入口 + 重打包重签。

- **`taffy_apk_inject_points`（注入点扫描）**
  只读地列出一个 APK 能往哪儿塞代码：Application（进程第一个执行的业务类，最稳）、
  launcher Activity（无自定义 Application 时的退路）、带 `JNI_OnLoad` 的 native 库、
  可 hook 的 JNI 导出函数。每个点都带「为什么选它」和「用哪个工具落地」，
  同时报告 packageName / debuggable / extractNativeLibs / ABI 集合。

- **`taffy_guard_scan`（反分析扫描）**
  签名校验、完整性校验、反调试、模拟器、Hook 框架、Root、VMP、
  加固厂商（爱加密 / 梆梆 / 百度 / 乐固 / 360 / 阿里 / 易盾 / 顶象 / 娜迦）、
  资源加密、类名混淆度，每条命中都带处置建议。

「设置 → 逆向分析」新增 **Frida** 页签，gadget 库 / 注入点 / 守卫扫描三块放在一页——
它们本来就是同一条链上的前后步骤。新增依赖 `org.tukaani:xz:1.10`（纯 Java，无 native）。

### 2. 区分「用了 frida」和「在检测 frida」

拿真实 APK 验证特征库时发现一个设计缺陷：逆向工具/安全 SDK 自己就带 `frida`、`XposedBridge` 字样
（实测某注入工具 APK 里 `frida` 出现 1533 次），单关键词命中会把「内置 Hook 能力」误报成「检测注入」。
现在：

- 命中带出现次数；
- 新增组合规则，必须几个特征同时出现才算检测：
  `maps + frida`、`dl_iterate_phdr + frida`、`/proc/net/tcp + 27042`、
  `/proc/self/status + TracerPid`、`goldfish + ro.kernel.qemu`、
  `XposedBridge + ClassLoader`、`getPackageInfo + signatures`、`DexClassLoader + getDex`；
- 只有 hook 字样、没有任何组合命中时降级为「内置 Hook 能力」，不当作检测。

### 3. 修复

- **多智能体·加固脱壳角色少一个工具**：PACKER 角色声明的 `taffy_unpack` 并不存在
  （实际工具名是 `taffy_dex_unpack`），而 `catalogTools` 用 `mapNotNull` 静默丢弃未知名字，
  于是该角色运行时白少一个工具、AI 也看不到脱壳能力。
- 新增 13 条单测（特征库 / 组合规则 / 命中计数 / 混淆评分 / ARSC 判定 / 端口识别）。

## 塔菲逆核 v1.5.63 —— 收掉上轮遗留 + 第二轮排查（策略绕过与串数据）

### 1. 命令链分隔符补全（上轮遗留）

rizin 的命令链分隔符有三种：`;`、换行、回车。上一版只认 `;`，于是
`s 0x100\nwx 9090 @ 0x100` 这种写法两边都判不出来：

- Kotlin 侧分类器会把整条当成只读 → **写命令的结果被缓存**；
- native 侧的 `unsafe` 闸门同样只看第一个 `;` 段 → 写命令落到共享只读 core 上。

现在两边都按三种分隔符切段（`core/RzCommandClass.kt` + `rizin_core.cpp`），并补了
2 条单测（换行链的 MUTATING / READ_ONLY / UNKNOWN 分支）。

### 2. 三个路径参数没登记，等于整条工具绕过工作区策略

`WorkspacePolicy.PATH_KEYS` 是唯一校验「路径必须在工作区内」的地方，漏登记就等于该参数可以
指向任意路径。这轮对账全部工具的参数，补上三个真·路径键：

| 参数 | 工具 | 漏登记后果 |
|---|---|---|
| `mappingPath` | `taffy_dex_rename` | 可读任意文件（ProGuard mapping） |
| `saveDir` | `taffy_js_assets` | 可往任意目录写解包产物 |
| `logFile` | `taffy_native_exec` | 可往任意路径写守护进程日志 |

> 默认值（`filesDir/js-assets`、`cacheDir/native-exec-*.log`）本来就在允许根内，行为不变；
> 显式传 `/sdcard/...` 现在需要先开隧道（与其它路径参数的规则一致）。

新增 2 条回归单测：`WorkspacePolicyPathKeysTest`（防止以后再漏、也防止把 content/hex 这类
内容键误登记成路径键）。

### 3. 换文件/换工作区后，界面还显示上一个的扫描结果

这轮扫「Composable 里 `remember { mutableStateOf(...) }` 持有与某个 key 绑定的数据、但 key
没传给 remember」的模式，抓到 4 处（都是上轮尾跳列表那类 bug 的同类）：

| 位置 | 现象 |
|---|---|
| 字符串解密扫描（`StringCipherView`） | 换 SO 后仍显示上一个 SO 的 blob 列表与解密详情 |
| 混淆扫描（`ObfuscScanView`） | 换 SO 后仍显示上一个 SO 的函数评分表 |
| DEX 类搜索（`SearchPane`） | 换 dex 后仍显示上一个 dex 的命中项 |
| DEX 常量搜索（`ConstPane`） | 同上 |

修法统一为 `remember(workspaceId)` / `remember(path)`。另外上轮尾跳列表的 key 补了
`linear.size`：给函数打了补丁（指令变了）之后列表也会重新查，而不是拿着补丁前的结论。

### 4. 顺手核对过、确认没问题的

- 工具 schema 声明 ↔ `args` 读取的全量对账（上一版漏过 `skipImports` 那类）：这轮用
  「全局声明键集合 − 读取键集合」跑了一遍，剩余差异全部是 `"key".oneOf(...)` 写法造成的
  正则误报，没有真的漏声明。
- `Action` 枚举 ↔ `when` 分支对账：差异全部来自网关转发（ToolCatalog 把 action 直接下发给
  底层工具）与同一文件里的多工具，无实际漏项。
- 文件句柄（`FileOutputStream` / `RandomAccessFile` / `ZipFile`）扫描：命中均为 `use {}`
  包裹或短生命周期，无泄漏。

## 塔菲逆核 v1.5.62 —— 一轮针对性 bug 排查（7 个真实缺陷）

这轮把全仓源码（380 个 kt + 62 个 java + 26 个 cpp，main 4d4b3732）过了一遍反模式扫描，
再对 v1.5.56~58 新加的三块（尾跳展开、命令缓存、共享 RzCore）逐行核实。
查出 7 个真实缺陷，全都是「用户能看见的错数据」，不是洁癖式重构。

### 1. 打了补丁，反汇编还是旧的（三处叠加）

v1.5.57 的 Kotlin 命令缓存和 v1.5.58 的 native 共享 RzCore 都靠「内容指纹」判断要不要失效：

```kotlin
// 旧：只取首尾各 2KB + 长度
val k = if (n < 2048) n else 2048
```

```cpp
// 旧：只取首尾各 4KB
const size_t k = n < 4096 ? n : 4096;
```

而编辑内核是**等长原位写入**（`editHex` / `editAsm` / `editSymbol` 都是 `copyOf()` 后
`arraycopy`，节区头、符号表一概不搬）——中间改几个字节既不改长度也不动首尾，指纹一字不变：

- native 侧：共享 RzCore 不重建，仍用临时文件里的**旧字节**，`pdfj` / `agfj` / `axtj` 全返回补丁前内容；
- Kotlin 侧：`rzInvalidateCommandCache()` 写好了却**从没被任何地方调用**，缓存直接命中旧结果。

修法：native 指纹改成**全文件** FNV-1a（6.7MB 约几毫秒，且只在真正走 native 时付一次）；
Kotlin 侧把失效接到 10 处字节变更点（hex / hexAt / hexVa / asm / symbol 补丁，undo / redo /
rollback / append / reset），统一走 `invalidateRizinAfterEdit()`（清缓存 + `rzReleaseShared()`）。

### 2. 「完整分析」跑完，列表还是空的

判断「这条命令会不会改分析库」时**只看第一段**，而且不认 `aaa` / `aaaa`：

```kotlin
val head = first.substringBefore(' ')
return head == "aa" || head == "af" || head == "aac" || head == "aar" || …
```

`"aaaa" == "aa"` 不成立，于是「完整分析」被当成只读：结果被缓存、**也不清旧缓存**，
之后的 `aflj` / `agCj` 继续返回分析前的空结果。

修法：新增 `core/RzCommandClass.kt`，按 `;` 逐段分类：

- 任一段属于分析类（`aa*` / `af*` / `ax*` / `ab*` / `ah*` / `an*` / `ap*` / `ar*` / `ae*`，
  或 `e analysis|asm|graph|bin.`）→ **MUTATING**：不缓存 + 执行后清缓存；
- 全部段命中只读白名单 → **READ_ONLY**：可缓存；
- 其余 → **UNKNOWN**：不缓存也不清空（保守）。

白名单偏保守：漏写只会少缓存，不会返回错结果。附 7 条单测（`RzCommandClassTest`）。

### 3. 两个同源 SO 的缓存互相串味

缓存 key 是 `内容指纹|editSessionId|命令`，**没有 workspaceId**。两个只差中间代码段的同源库
（同一项目的不同构建）首尾 4KB 完全一致、`editSessionId` 大多为空 → B 的反汇编直接显示成 A 的。
key 里补上 workspaceId。

### 4. `unsafe` 闸门能被命令链绕过

native 侧只在**命令开头**匹配 `w` / `o` / `!` 等危险前缀，于是
`s 0x100; wx 9090 @ 0x100` 一路放行：写命令落在共享**只读** core 上（不重建、还会被 Kotlin
当成只读结果缓存），此后所有只读查询都被污染。改成按 `;` 逐段检查。

### 5. 共享 RzCore 永不释放

`rzReleaseShared` 在 native 侧写好了，Kotlin 侧连声明都没有，关工作区 / 清缓存 / 引擎关闭
都不放。6.7MB 的 SO 连同分析数据库会一直挂在内存里（可达数十 MB）。补上
`NativeEngine.releaseSharedAnalysis()` 并在三处接线。

> 旧版 `librz_native.so`（arm64 快速包用的那份静态库）没有这个导出，`runCatching` 会兜住
> `UnsatisfiedLinkError`：只影响释放，不影响功能。

### 6. 尾跳调用点列表会串函数

`TailExpandButton` 的 `rows` 用 `remember { }` 存、没带 `funcVa`：看完 A 函数的调用点，
切到 B 函数再点开，显示的还是 A 的列表（而且 `rows` 非空不会再查）。改成
`remember(funcVa, ws)`。

### 验证

- 新增 7 条单测（命令链分类），进两条流水线的单测门；
- 4 ABI 全量构建 + Dex2C + 打包通过后发布。

## 塔菲逆核 v1.5.61 —— Capstone 真升到 6.0.0-Alpha11 了

v1.5.60 时我判断「6.0 是 API 重写，自研胶水得整份重写」。这次把它做完了，结论是
**不用重写：一个兼容宏 + 7 处字段迁移就够**。

### 根因（从 CI 日志里捞出来的）

四个 ABI 全挂在「编译 unidbg 原生库 → libdisassembler.so」，报错集中在 7 行：

```
disassembler.c:23:   error: use of undeclared identifier 'CS_ARCH_ARM64'
disassembler.c:123:  error: unknown type name 'cs_arm64'
reg_mapping.h:465:   error: unknown type name 'arm64_reg'
```

capstone 6.0 没删 ARM64 这套名字，而是把它们从公共头挪进了**兼容头**，
要 `-DCAPSTONE_AARCH64_COMPAT_HEADER` 才展开：

```c
#ifdef CAPSTONE_AARCH64_COMPAT_HEADER
    CS_ARCH_ARM64 = 1,
#else
    CS_ARCH_AARCH64 = 1,
#endif
```

打开这个宏，编译错误从 40+ 条掉到 7 条。

### 剩下 7 条的字段迁移

| 5.0.9 | 6.0 | 处理 |
|---|---|---|
| `arm64.writeback` / `arm.writeback` | 提到 `cs_detail.writeback`（指令级） | 改读 `detail->writeback`，语义不变 |
| `arm64_op.pstate/sys/prefetch/barrier` | 收进 `sysop = {reg/imm/alias/sub_type}` | 补 0 |
| `arm_op.mem.lshift` | 删除（新增 `align`） | 补 0 |

补 0 的影响面：只有 `mrs/msr/isb/dmb/prfm` 这类系统操作数的**枚举值**拿不到，
这些指令的文本（`mrs x0, TPIDR_EL0`）仍由 capstone 自己输出的 `op_str` 给出，
反汇编、CFG、寄存器分析都不受影响。

### 验证：没等 CI，先在本地把整条 Java 绑定链路跑通了

沙箱里用 host 编译 capstone 6 + 胶水，把仓库里的 `capstone.jni.*` / `capstone.api.*`
全量 javac，用真 JNI 反汇编：

```
0x1000  stp   x29, x30, [sp, #-0x10]!   wb=true   ops=3  [x29][x30][mem sp,-16]
0x1004  ldr   w1, [x2, #4]              ops=2    [w1][mem x2,+4]
0x1008  br    x8                        ops=1
0x100c  mrs   x0, TPIDR_EL0             ops=2
0x1010  bl    0x2244                    0x1014  adrp x0, 0x1000
```

`stp …, [sp,#-16]!` 的 `writeback = true`（迁移后语义正确）；ARM32 路径
（`mvnsmi`/`mov`/`bx`）同样正常。

### 其它

- Java 侧 `capstone-5.0.9-android-patched.jar` 一行没动：JNI 方法签名与 5.x 完全
  一致，这轮只换 C 引擎。
- CMake 沿用 v1.5.60 适配好的选项名（`CAPSTONE_BUILD_SHARED_LIBS` 等）并摘掉
  上游硬编码的 `-Werror`；SOVERSION 仍然去掉（Android 只打包 `libcapstone.so`）。

## 塔菲逆核 v1.5.60 —— JGit 升到 7.8.1；Capstone 6.0 查清了为什么不能直接升

### JGit 6.10 → 7.8.1 ✅

跨了一个大版本（6 → 7），编译、单测、R8、打包全过。

### Capstone 6.0.0-Alpha11 ❌ 不是版本号能解决的

按你说的试了（改 clone 分支 + 适配 CMake 选项名），四个 ABI 全部在
`编译 unidbg 原生库` 这一步挂掉。翻 6.0 的头文件确认了原因：

```c
// capstone 6.0: include/capstone/arm64.h
typedef cs_aarch64       cs_arm64;   // 5.x 的 cs_arm64 结构整个换掉了
typedef cs_aarch64_op    cs_arm64_op;
#define NUM_ARM64_OPS 16
```

而且 `op_count` 这个字段在 6.0 的 arm64 头里**一次都搜不到** —— 说明 6.0 是
**API 重写**（`cs_arm64` 只是 `cs_aarch64` 的别名，操作数结构、detail 布局全变）。

我们自己的 JNI 胶水 `third_party/capstone-java/native/disassembler.c`（342 行）是按 5.x
写的：

```c
cs_detail *detail = insn->detail;
jobjectArray operands = (*env)->NewObjectArray(env, arm64.op_count, cArm64Operand, NULL);
for (uint8_t m = 0; m < arm64.op_count; m++) { ... arm64.operands[m].type ... }
```

这套字段在 6.0 下不存在，必须**整份重写**（还要重编 Java 侧的 `Capstone` 绑定），
不是"换个 branch + 改几个 CMake 变量"能完成的。硬塞进这次升级只会让 4 个 ABI
的 native 流水线全红。

所以这版把 Capstone 退回 5.0.9（保持可用），6.0 单独作为一项任务：
重写胶水 → 用新结构体（`cs_aarch64`）→ 再切分支。那需要一轮专门的改动 + 验证。

> 补充：`scripts/build-unidbg-native.sh` 里顺手加的 CMake 6.x 兼容（`-Werror` 摘除、
> `CAPSTONE_BUILD_SHARED_LIBS` / `LEGACY_TESTS` 新选项名）都留着 —— 对 5.0.9 无害，
> 将来切 6.0 时能直接用。

## 塔菲逆核 v1.5.59 —— 依赖升级到当前最新

把版本目录里每一项都对了一遍远端仓库（Maven Central / Google Maven），能升的都升了。

### 升了什么

| 依赖 | 旧 | 新 |
|---|---|---|
| Gradle | 9.8.0 | **9.9.0** |
| AGP | 9.4.1 | **9.5.1** |
| KSP | 2.3.11 | 2.3.13 |
| Ktor（server / client） | 3.6.0 | 3.6.1 |
| OkHttp | 5.5.0 | 5.5.1 |
| kotlinx-serialization-json | 1.11.0 | 1.12.0 |
| AndroidX Navigation3 | 1.2.0 | 1.2.1 |
| AndroidX DataStore | 1.2.1 | 1.3.0 |
| AndroidX WebKit | 1.17.1 | 1.18.0 |
| annotation-experimental | 1.5.1 | 1.6.0 |
| androidx.test.uiautomator | 2.4.0 | 2.4.1 |
| ASM | 9.11 | 9.11.1 |
| Rhino | 1.9.1 | 1.9.2 |
| zstd-jni | 1.5.7-23 | 1.5.7-26 |

### 已经是新的（没动）

Kotlin 2.4.21、Compose BOM 2026.09.00、Room 2.8.5、Lifecycle 2.11.0、
WorkManager 2.12.0、Media3 1.11.1、Coil 3.6.3、ML Kit translate 17.0.3、
Coroutines 1.11.0、Koin 4.2.2、Robolectric 4.17、core-ktx 1.19.1 —— 都是当前最新稳定版。

### 特意没升

- **JGit 6.10 → 7.8**：跨大版本，7.x 调整了不少 Repository/FileRepositoryBuilder 相关 API。
  仓库里 JGit 只被 Git 相关页面用到，本地没法编译验证，等单独一轮带上具体用例再动。
- **material3 1.5.0-beta01 → 1.4.0**：不升。1.4.0 虽是「最新稳定」，但拿不到 LoadingIndicator /
  波浪进度 / ButtonGroup 这些 M3 Expressive 组件，现在钉的是比它更新的 beta。
- **Capstone 5.0.9 → 6.0.0-Alpha11**：还是 alpha，native 引擎不动。
- **Rizin v0.9.1 / LIEF 1.0.0 / Keystone 0.9.2 / unidbg v0.9.9**：都是当前最新 release。

### 验证

Gradle 9.9.0 + AGP 9.5.1 这一组合跑通了完整链路：Gradle 配置、Kotlin 编译、
单元测试（含 `RzOutputTest` / `AnalysisCfgRebuildTest` 等）、R8、APK 打包。

## 塔菲逆核 v1.5.58 —— 性能二段：native 侧共享 RzCore

上一版把「命令条数」压到了最少（串联 + 缓存），但每次 `rzCommand` 在 native 里还是：

```
写临时文件（整个 SO）→ rz_core_new → open_load → rz_core_analysis_all（3~10 秒）→ free
```

这版直接把这一步也砍掉：**只读命令复用同一个 RzCore**。

### 怎么做的

```
struct SharedCore { RzCore* core; std::string tmpPath; size_t bytes; uint64_t fp; ... }
```

- 按「字节长度 + 首尾各 4KB 的内容指纹」判定是不是同一个文件：命中就复用，不命中就重建
- 写命令（`unsafe`，含 `wx`）**仍走独立的临时 core** —— 它要读回文件算 patch，不能和共享态混
- 写命令执行完会主动丢掉共享 core，保证编辑后立刻能看到新内容
- 共享 core 拿不到（open 失败）时**自动回落**到原来的独立 core 路径，功能不受影响
- 单独导出了 `rzReleaseShared()`，可用于关工作区时释放内存（当前还没挂到 UI）

具体指令环：`rzCommand` 的只读分支在 `writeTempSo` **之前**就返回，
所以命中时连「写 6.7MB 临时文件」这一步也省了。

### 收益

| 操作 | v1.5.57 | v1.5.58 |
|---|---|---|
| 首次打开某个 SO 的第一条命令 | 3~10 秒 | 3~10 秒（无法避免） |
| 同一 SO 的**后续每条**只读命令 | 3~10 秒 | **毫秒** |
| 切页签、点函数、翻 xref | 每次几秒 | 毫秒 |

配合上一版的命令串联与结果缓存，典型流程从「每个动作等几秒」变成「只有第一次分析慢」。

### 风险与边界

- 共享 core 只在**只读**命令上用；`rz_core_cmd_str` 在长驻 core 上反复调用是 rizin REPL 的标准用法
- 换文件（字节变了）会自动重建，不会串数据
- 内存：一个 SO 的 RzCore 常驻（几十 MB 量级）；换文件时旧的会被释放
- 这个改动在 native 层，**发布用的多 ABI APK 会重新编译** `librz_native.so`；
  单 ABI 的 CI 验证包用的是仓库里预编译的 so，不受影响

> 说明：CI 环境这轮踩了两个坑（CMake 4.x 拒绝 keystone 内嵌 LLVM 3.9 的两处老写法），
> 已在 `scripts/build-unidbg-native.sh` 里就地修正（抬 `cmake_minimum_required` 到 3.5、
> `CMP0051` 由 OLD 改 NEW），否则 native 流水线出不了包。

## 塔菲逆核 v1.5.57 —— 性能：分析次数砍掉一个数量级

### 根因

native 的 `rzCommand` 每调用一次都做这四件事：

```
写临时文件（整个 SO，6.7MB / 大文件 200MB）
rz_core_new() + applyGhidraConfig()
rz_core_file_open_load() + rz_core_analysis_all()   ← 3~10 秒
执行命令 → rz_core_free()
```

而一个普通操作要连发好几条命令：打开函数控制流 2~3 条、展开尾跳调用点 25 条、
看分析档位统计 4 条。所以「点一下等十几秒」不是 UI 慢，是**重复做了很多次全量分析**。

### 改动一：命令串联（一次分析跑完一批）

rizin 支持 `cmd1; cmd2`，一次调用就能跑完，输出首尾相接。新增 `splitJsonChunks`
按 JSON 结构把它切回单个值（字符串里的花括号、嵌套数组、被截断的尾巴都处理了；
非 JSON 的报错文本会跳过）。

| 场景 | 改前 | 改后 |
|---|---|---|
| 打开函数控制流（`agf json` + `pdfj`） | 2~3 次分析 | **1 次** |
| 尾跳调用点展开（`axtj` + 24×`pDj`） | 25 次分析 | **2 次** |
| 分析档位统计（`aflj`/`izj`/`isj`/`axtj`） | 4 次分析 | **1 次** |

尾跳展开那次最明显：原来 24 个调用点各发一条命令，等于把同一个 SO 从头分析 24 遍。

### 改动二：只读命令结果缓存

切页签再切回来、退出页面再进来、点同一个节点——发的命令一模一样，结果也不会变，
但这些操作每次都在重跑全量分析。现在按「内容指纹 + 工作区 + 命令」缓存：

- 指纹 = 字节长度 + 首尾各 2KB，编辑后字节变了自动换 key，**不需要手动失效**
- 会改分析状态的命令（`aa`/`af`/`aac`/`ax`/`ab`/`ah`/`an`/`e analysis`…）**不缓存**，
  执行完还会把整个缓存清掉（否则 `aaa` 之后查函数列表会拿到旧结果）
- `rzAnalyze`（重新分析）入口也清空
- 单条超过 1.5MB 不缓存（`izj` 那种大输出不占内存），最多 16 条

### 效果

典型操作序列（打开 SO → 函数列表 → 点函数看控制流 → 切节点列表 → 回函数列表）：

- 改前：每个动作 3~10 秒，累计几十秒
- 改后：首次分析之后全部毫秒级

### 还有空间

真正的天花板在 native：如果让同一个 SO 的 RzCore **跨命令复用**（只分析一次，
之后每条命令都是毫秒级），能再快 10 倍以上。但那需要改 C++ 并重新编译
`librz_native.so` 回填仓库（一次 30 分钟的 native 流水线），而且共享 core 的
生命周期得小心（历史上踩过 plugin teardown 的 use-after-free）。
这一版先把不需要动 native 的部分做满。

新增 `RzOutputTest`：两个 JSON 拼接、报错文本跳过、字符串里的花括号、截断尾巴、嵌套数组。

## 塔菲逆核 v1.5.56 —— 尾跳调用点按需展开

上一版把 `br x2` 这类参数尾跳标成了 `尾跳 → 参数 x2（调用方传入）`，但目标还是没答案。
这版补上**跨调用点展开**：控制流页页签行右侧新增一个按钮（双向箭头图标），点一下就会去查
"到底谁调用了这个函数、每次传的是哪个函数"。

### 怎么算的

1. `axtj` 拿本函数入口的所有调用点（`bl` 到这里的地址）
2. 每个调用点往回取 20 条指令（80 字节，AArch64 定长）
3. 用和 CFG 同一套数据流（[AnalysisCfgRebuild.evalParamAtCaller]）算：调用前 xN 被赋成了什么
4. 结果按调用点列出：

```
bl 0x1f3a20
  x2 = 0x1e8c0
bl 0x1f3b04
  x2 = 0x1e910
```

那个 `0x1e8c0` 就是真正被尾跳的函数地址 —— 很多情况下是 `adrp x2, page` + `add x2, x2, #off` 两条算出来的，
也可能来自 `ldr x2, [x8]`（这时显示 `[0x...]`）或 `[obj+0x68]`。

### 为什么做成按钮而不是自动

一次展开 = 1 次 `axtj` + N 次 `pDj`（N = 调用点数，上限 24）。对这种 thunk 函数，调用点常常十几个，
自动跑会把每次 CFG 重建都拖慢十几秒。做成按需：想看才点。

### 边界

- 调用点超过 24 个只取前 24 个（按地址排序）
- 经 GOT/PLT 间接调用的查不到（`axtj` 看不到跨 SO 的调用），会明确提示「没有找到调用点」
- 调用点没给该参数赋值时显示 `?`（目标由更上层决定）

新增单测：`paramTailJumpsFindsParameterBranches` / `paramTailJumpsIgnoresLoadedRegisters` /
`evalParamAtCallerResolvesAdrpAdd` / `evalParamAtCallerReportsObjectSlot` / `evalParamAtCallerBlankWhenUntouched`。

> 顺带：build-apk 流水线的 frida-server 下载改成**失败不阻断**（沿用仓库内已有的
> libfrida_server.so）。GitHub release CDN 今天大面积 504，外部网络问题不该让发版卡住。

## 塔菲逆核 v1.5.55 —— 剩余 `br xN` 的成因与处理

上一版解出了 libpython 85% 的 `br`，剩下那些到底是什么？把 libcrypto 的 458 条全部归类了一遍：

| 形态 | 条数 | 说明 |
|---|---|---|
| GOT 槽（已解） | 124 | `memcpy`/`strlen` 这类 |
| 对象字段 | 95 | `ldr x1,[x9]; br x1` |
| **`br x1`~`br x6`** | **300+** | 目标寄存器是**参数**，函数里从未被改写 |

这些是**回调/分发器尾跳**。真实例子（libcrypto `0x2128a4`）：

```
cmp  x0, x1
b.eq 0x2128b4
ldp  x29, x30, [sp], #0x10
br   x2               ; x2 = 第三个参数，调用方传进来的函数指针
```

以前这里只会显示"间接跳转"，用户看不出到底是什么。

### 这版加了函数内数据流

原来是"往前 40 条窗口回溯"，跨块 / 经栈保存的情况一律失败。现在改成**顺序扫描整个函数体**，维护
`寄存器 → 抽象值集合`（常量地址 / 数据槽 / 对象字段 / 入口参数），并跟踪 `str/stp → [sp,#off]`
写进栈槽的值。于是：

- **`str x19,[sp,#8] ... ldr x19,[sp,#8]; br x19`** → 顺着栈槽追回源头（epilogue 恢复后尾跳，之前全部失败）
- **`mov x16, x0; br x16`** → 认出是参数 x0
- **分支汇合**（if-else 各给不同来源）→ 抽象值是**集合**，多个候选全部列出，不会被最后一个覆盖

### 现在能给出的描述

| 情况 | 节点摘要 |
|---|---|
| 回调尾跳 | `尾跳 → 参数 x2（调用方传入）` |
| 对象方法槽 | `尾跳 → [obj+0x68]` |
| GOT / PLT | `尾跳 → memcpy` |
| 常量槽指向本函数内 | 直接连边（真边） |
| 多分支候选 | `尾跳 → 0x1234 / 0x5678 …` |

新增测试：`parameterTailCallIsLabeled`（`br x2` 标参数且不造边）、
`stackRestoredTailCallTracesBackToParameter`（栈恢复追回 x0）。

### 为什么"参数尾跳"仍然不画边

目标由**调用方**决定。同一个函数在不同调用点可能传不同的回调 —— 画一条边就是错的。
要做到"跟上"需要**跨函数的调用点分析**（找所有 `bl` 本函数的点，看 x2 传了什么）；
一个函数几十个调用点时，这个分析要额外几十次反汇编，代价远大于收益。
如果确实需要，可以把它做成"按需展开"的动作。

> 结论：能静态确定的（GOT 符号、常量槽、跳表、本函数内 label）全部连上；
> 依赖运行时数据的（参数、对象字段）给出**精确来源描述**，不猜目标。

## 塔菲逆核 v1.5.54 —— 间接尾跳真的跟上了

上一版说"尾调用静态无解"，那话只对了一半：**目标确实不知道，但"跳到谁"很多时候能查出来**。
这版做的是数据流溯源 + 数据槽解析。

### 怎么做

对每条 `br xN`，往前回溯 xN 的来源（含 `mov`/`add`/`sub` 传递，深度 4、窗口 40 条），
拿到"从哪个数据地址取的指针"，再按槽的类型三条路：

| 形态 | 机器码 | 解析结果 |
|---|---|---|
| GOT / PLT 槽 | `adrp x8,got; ldr x16,[x8,#0x10]; br x16` | 查重定位表 → **符号名**（`memcpy`、`__cxa_finalize`） |
| 本地指针槽 | `adrp x8,tbl; ldr x9,[x8,#off]; br x9` | 读 8 字节 → **本 SO 内的函数地址** |
| 表（多目标） | `ldr x9,[x8,x0,lsl #3]; br x9` | 逐项解析成多目标 |
| 对象槽 | `ldr x8,[x0,#8]; ldr x1,[x8,#0x68]; br x1` | 静态无解，但标出 **`[obj+0x68]`** |

槽地址有重定位符号名时直接用名字（`irj`）；否则读槽内容（`p8`，8 字节小端）当目标地址。
同一个槽被多条 `br` 引用时只读一次内存。

### 三个样本实测

| SO | `br` 总数 | 解出符号名 | 对象槽（报字段偏移） | 其它 |
|---|---|---|---|---|
| libpython3.12.so | 1,600 | **1,353**（85%） | 105 | 142 |
| libcrypto_python.so | 458 | 124 | 95 | 239 |
| numpy `_simd.so`（网上取的第三方样本） | 61 | **58**（95%） | 0 | 3 |

libpython 里那些 `br x17` 现在都能说出目标：

```
br@0x48c2cc  → __cxa_finalize
br@0x48c2fc  → sincos
br@0x48c30c  → atan2
br@0x48c31c  → hypot
```

numpy 的 `_simd` 同理：`memcpy` / `PyUnicode_FromFormat` / `PyList_New` / `PyDict_SetItemString` / `strtok`。

### 界面上

块摘要后面直接跟目标：

```
adrp x8, 0x5000 · 尾跳 → memcpy
ldr x1, [x8, 0x68] · 尾跳 → [obj+0x68]
```

如果槽内容指向**本函数内**的地址（computed goto 的本地 label 表），就直接连边 —— 那种情况是真边。

新增测试：`gotTailCallIsNamed` / `objectSlotTailCallReportsFieldOffset` /
`slotPointingInsideFunctionBecomesEdge` / `parseRelocNamesReadsVaddrAndName` / `slotHitReadsPointerValue`。

> 剩下的 27%~15% 是 `br x0`/`br x8` 直接从参数或返回值跳（虚调用、回调），
> 寄存器值在运行时才定 —— 这部分静态确实没有答案，仍按"间接跳转"标注不建边。

## 塔菲逆核 v1.5.53 —— 用两个真实 SO 校正 `br xN`

拿 `libpython3.12.so`（6.4MB，aarch64）和 `libcrypto_python.so`（3.5MB）实测了一遍，
结论和上一版实现差得有点远，所以这版主要是**纠错**。

### 实测数据（完整反汇编，不是抽样）

| SO | 指令 | `br` | `blr`（调用） | PLT 尾跳 `br x16/17` | 函数指针尾跳 | 其它计算跳转 | **跳表** |
|---|---|---|---|---|---|---|---|
| libpython3.12 | 584,721 | 1,600 | 1,495 | 1,355 | 103 | 142 | **0** |
| libcrypto | 438,592 | 458 | 1,318 | 125 | 173 | 160 | **0** |

真实 SO 里的 `br xN` 几乎全是**间接尾调用**：

```
ldr x8, [x0, #8]      ; 取 struct
ldr x9, [x8, #0x68]   ; 取 slot
ldr x1, [x9]          ; 取函数指针
br  x1                ; 尾调用走了
```

—— 目标在堆上、由运行时决定，静态**没有**唯一答案。跳表一条都没有
（都是 `adrp`/`add` 后紧跟 `ldrsw`+`add`+`br` 的标准链，被编译器换成了
cmp 链或 `csel`）。

### 上一版的 bug

v1.5.52 的判定是"`br xN` 前 14 条里能找到 `adrp`+`add` 就当跳表"，对 libpython
的 1,600 条 `br` 有 1,458 条"解析成功"（91%）—— **全是误判**：它把窗口里随便一条
`adrp` 当表基址，读出来的是字符串/重定位数据，解出的目标 1,458 个里有 1,446 个
根本不在代码段内。虽然目标过滤（必须落在本函数地址集合里）挡住了绝大部分假边，
但仍是拿巧合当结果。

### 这版改成严格匹配

必须完整对上编译器生成的指令链，缺一环就放弃：

形态 A（4 字节有符号偏移，相对表基址）：
```
adrp  x8, page
add   x8, x8, #off
ldrsw x9, [x8, w0, uxtw #2]   ; 必须是被 add 回填的那个索引寄存器
add   x8, x8, x9
br    x8                       ; 必须是同一个寄存器
```
形态 B（8 字节绝对指针表）：
```
adrp x8, page
add  x8, x8, #off
ldr  x9, [x8, x0, lsl #3]
br   x9
```

两个样本按这个严格判定跑：**0 条命中** —— 与真实情况一致。

### 间接跳转不再"看起来像函数结束了"

块尾是解析不出目标的 `br xN` 时，块摘要后面会标 `· br x1 间接跳转`：

- 用户能看出控制流在这里**离开了本函数**（尾调用），而不是"这函数就到这儿为止"
- 依然不建边 —— 不猜

新增测试：`indirectTailCallIsNotMistakenForJumpTable`（窗口内有 `adrp`+`add`，
但 `br` 的寄存器来自 `mov`，即使读者返回真数据也不许解析）、
`absolutePointerTableIsAlsoResolved`（形态 B）、`hexToBytesRejectsErrorText`。

> 想真正跟这类尾调用，需要的是**数据流分析**（追 `ldr x1,[x9]` 里 `x9` 从哪来，
> 静态能确定的只有"从某个 struct slot 取"），或者跑起来记录实际目标。
> 前者工程量在另一个量级，后者要在设备上执行。

## 塔菲逆核 v1.5.52 —— 寄存器间接跳转 `br xN` 跟上

AArch64 的 switch 走 `br xN`，目标在运行时才算得出来，静态反汇编里那条指令**没有任何 0x 地址**，
所以上一版的切块逻辑拿到的是空 targets —— 整个 switch 的所有分支既不成块、也不成边，
图上看就是主线走到底、case 全丢。

现在按 PIC 跳表的标准形态反推：

```
cmp  w0, #7
b.hi default
adrp x8, page           ; 页基址
add  x8, x8, #off       ; 表地址 = 页基址 + 页内偏移
ldrsw x9, [x8, w0, uxtw #2]
add  x8, x8, x9
br   x8                 ; 目标 = 表基址 + 表项
```

- 在 `br xN` 前 14 条指令内找 `adrp` + `add`，算出表基址；项数优先取前面的 `cmp wN, #imm`（imm+1），
  取不到按 64 项试读，上限 512 项
- 读表走 rizin `p8`（`AnalysisCfgRebuild.hexToBytes` 解析，读失败回的错误文本会先被挡掉，
  不会被当成 `0xCA...` 之类的垃圾字节）
- 表项按 4 字节有符号偏移解释，目标 = 表基址 + offset[i]；只有落在本函数指令地址集合里的目标才连边
  （跳到别的函数的是 tail-call，不该并入本图）
- 每个表地址只读一次（UI 侧按地址缓存），一个函数最多几次 `rzCommand`
- 解析不出来时不猜：`br xN` 保持无边，和以前一样

新增 `AnalysisCfgRebuildTest`：真实 switch 布局的表项（负偏移）→ 三个 case 块都被建出来且三条跳转边到位；
没给 reader / 读失败时不会凭空造边。

> 局限：`br xN` 走的是函数指针表（`ldr x8, [x8]` 取绝对地址、或运行时改写过的跳表），
> 静态仍然跟不了 —— 这类目标本来就不在函数体内。

## 塔菲逆核 v1.5.51 —— 控制流"只有一个框"与相关的一串问题

用户反馈：控制流图形化只有一个框、节点列表里那一个块挂着 1000 条指令、根下钻只有一个节点、
点「完整分析」卡死。四个问题在同一片代码里，逐个查了根因。

### 1. 控制流图形化只有一个框
`rzCfg` 有时把整段代码识别成**一个基本块**（函数边界没切开，或者 `af` 只做了线性扫描），
`agfj` 就只返回 1 块 —— 界面上就是一个框。

新增 `AnalysisCfgRebuild`：用 `pdfj` 的逐指令结果**自己切块**。
- 块起点 = 函数入口 + 所有跳转目标 + 每条跳转指令的下一条
- 块尾 = 跳转 / 返回
- 边 = 顺序边（块尾不是无条件跳转/返回）+ 跳转边（条件跳转分别记 true/false）

只认**落在函数自身地址集合里**的跳转目标，跳出去的一律当外部调用、不建边 ——
否则会把相邻函数并联进来，图会大得离谱。
仅在「块数 ≤1 且线性指令 >8」时启用，重建后块内指令按新区间重新归并。

### 2. 节点列表一个块 1000 条指令
`mergeLinearInsns` 靠块的 `endText` 做上界，单块场景没有 `endText` →
线性反汇编一路吃到下一个函数。退化成「**遇到 ret 就封口**」：ret 是块末尾，
之后不可能还属于这个块。

### 3. 根下钻默认只剩根节点
`buildDrillDag` 固定 `depth=5`，调用链一深就只剩根。改为展开**全部可达节点**
（`Int.MAX_VALUE`），仍受 `cap=1500` 与 omitted 计数保护。

### 4. 完整分析卡死
直接发 `aaaa` 等于在同一份已全量分析的结果上再算一遍，大 SO 上要几分钟，界面像卡死。
改成两段：
- 第一次只 `clearViewCaches + reloadTick`（秒级），提示"若仍为空再点一次"
- 仍为空时第二次才真跑 `aaaa`，并明确提示"可能需要几分钟"

### 伪 C 的现状（这轮没改）
截图里伪 C 只有 8 行 + 警告「Pseudocode references 1 address(es) at/after declared boundary
(first=0x1bdc, boundary=0xd7c)」—— 这是 rz-ghidra 认为函数只到 0xd7c（80 字节），
而指令里有跳到 0x1bdc 的 jumptable 目标。**这不是渲染问题，是函数边界识别问题**：
0xd2c 处的 `entry0` 本身是个很小的 `.init` 桩，反编译出 8 行是对的；越界警告是
ghidra 在提示"这段代码引用了函数外的地址"。要让伪 C 更完整，得从函数识别入手
（rizin 的 `af` 边界），不是画布能解决的。

---

## 塔菲逆核 v1.5.50 —— 类改名先预检 + 全功能结构性排查

### 类重命名加了"预检"（Dex 浏览器）
原来点「重命名」直接调 `taffy_dex_rename`，会立刻写盘并留 `.bak`。两个真实风险：

1. **新名与已有类重名** → dex 里出现两个同名类，写出可能失败或装上直接崩，而用户已经丢了原文件
2. **oldName 写错 / 目标类不在该 dex** → `totalRenamed=0`，但工具仍返回 `ok=true`，UI 显示"OK · 重命名 0 处"

现在点一次按钮是三步：
① `taffy_dex_rename_mapping action=plan` 冲突预检（新名是否已被占用）→ 有冲突直接中止并显示第一条
② `taffy_dex_rename dryRun=true` 拿命中数 → 0 命中则不写盘，把工具给的 `hint`（为什么没命中）显示出来
③ 都通过才真正执行，并把 dryRun 的命中数作为"影响 N 处"显示

预检失败时对话框保持打开、改完可再试；成功才关对话框并刷新成员列表。

### 全功能结构性排查（三类对账，全部通过）
这轮把"功能能不能被用户走到"这类结构性问题全部对账了一遍：

| 对账项 | 覆盖 | 结果 |
|---|---|---|
| **工具分类**：每个工具的 `category` 是否在 `categoryDescriptions` 里 | 228 个工具 / 21 个分类 | ✅ 0 个缺失 —— 缺了的话该工具会在「全部工具」里**彻底消失** |
| **设置页导航**：`SettingsDest` 枚举 / `settingsTitle` 分支 / `when(dest)` 渲染 / 入口行 | 43 个页面 | ✅ 三处完全对齐，每个 dest 至少 3 处引用，无孤儿页面 |
| **分析页视图**：`analysisOverflowViews`（⋮ 菜单入口）vs `when(view)` 渲染分支 | 31 个溢出视图 | ✅ 全部有渲染分支，不存在"点了菜单出空白" |

这三类问题的共同点是"编译能过、测试能过、但用户点不到或看到空白"，只能靠对账发现。

### 关于"对每个功能都排查"的边界
结构性对账（可达性、分类、契约字段）可以脚本化，这轮做完了。
**语义正确性**（算得对不对、流程顺不顺）没法靠静态检查 —— 需要真实的失败路径。
已经做过的：7 处 UI 直调工具的字段契约逐个核对（`path/action/oldName/newName` ↔ `totalRenamed/ok`）、
dex 重命名用真实 916KB dex 验证（140 次调用全部跟着改）。

---

## 塔菲逆核 v1.5.49 —— 批量重命名 / ProGuard mapping 导入

### 新增 `taffy_dex_rename_mapping`
已有的 `taffy_dex_rename` 适合改**一两个**符号：每改一个就重写一次 dex（`DexRenameApi`）。
但反混淆的真实场景是"一次应用几百条改名"（jadx / R8 导出的 mapping），
逐个调用等于几百次全量重写，手机上要几分钟。

新工具走 dexlib2 的 `DexRewriter`：**一次遍历应用全部改名**。

- `action=export_mapping`：导出符号骨架（类 + 成员），人工改右边的名字
- `action=import_mapping`：读 ProGuard mapping（自动剥 `12:34:` 行号前缀、区分方法/字段、
  Java 参数列表转描述符）或 `renames` 数组（class / method / field / package）
- `action=plan`：dryRun，返回将改多少项 + **冲突列表**（两个类改成一个名字、
  同类里两个方法改成同名同签名 —— 后者会让 dex 写出失败或调用错方法）
- `action=apply`：输入 APK 时额外用 `ZipRepack.copyAll(replace=...)` 产出
  `<name>-renamed.apk`（保留 method/extra 与对齐，空替换集就不报产物），并提示需要重签

映射 key 按「所属类 + 名称 + 签名」精确匹配 —— 只按名字映射会把所有 `toString` 一起改。
方法不填 descriptor 时落 `类->名字:*` 通配（用户多半不知道该填 `(I)V`）。

**开发期验证**：用真实 916KB dex 跑重命名，一个被调用 140 次的方法 → 定义改名 1 个、
旧名引用 0 个、新名引用 140 个。单测覆盖类型转换（`int[]` → `[I` 这种基本类型数组，
第一版写成 `[Lint;` 被单测抓到）、mapping 解析、坏行容错。

### 一个必须说明的失误
第一版我把这个能力做成了**同名**的 `taffy_dex_rename`，直接覆盖了 main 上已有的实现
（`engine/DexRenameApi` + 4 个 Renamer，而且 `SettingsDexExplorerPage` 已经在调用它）。
后果是重复注册导致 `ToolCatalog.ALL` 与 `names` 数量不一致、单测红，原实现被顶掉。
已完整回滚（恢复原版文件、删除重复实现），这一版改成互补定位的独立工具。

教训：**新增工具前先 grep 工具名**，光看 UI 有没有入口不够 —— 那个工具可能只被 MCP 客户端用。

---

## 塔菲逆核 v1.5.48 —— 代码块在白页上"消失"了

v1.5.44~45 把几处深色代码块改成了跟随主题，浅色主题下用 `surfaceContainerHigh`。
但这个色在浅色方案里是 **l=0.965**，而页面底 `background` 是 **0.971** —— 差 0.006，
等于白页上嵌一块白块，只剩圆角能看出边界。

改用 `surfaceVariant`（**l=0.945**，差 0.026），这也是 M3 里 chip / 输入框用同一档底色的原因。

涉及三处：日志内容块、抓包页的等宽块（教程 / 请求头 / WS 帧）、帮助页的代码块。

深色主题不受影响（那里仍用终端深底）。

---

## 塔菲逆核 v1.5.47 —— 新工具：包使用统计 + 修掉日志列表的"看不见"

### 新工具 `taffy_dex_package_usage`（包使用统计）
逆向里最常见的一个问题：**这个 SDK / 库还被用着吗？删掉安不安全？**

给它一个包名（如 `com.umeng`），它扫全部 DEX 的指令，统计**其它包**对它的引用：
实例化（`new-instance`）、静态调用（`invoke-static`）、方法调用、字段读写 ——
按类汇总，再给成员明细（哪个方法/字段被引用得最多），可选 Markdown 表格。

**关键设计：包内互引要排除。** 一个内部调用很多的库，如果不排除自引用，会被算成
"被重度使用"，结论直接反。实现里先扫一遍把包内类记下来，扫描时整类跳过。

零引用时返回 `warning: 可作为死代码候选`。

`inPackage()`（类描述符是否属于某包）抽成 internal 纯函数并配了 6 组单测 ——
最容易错的是 `com/xy` 不算 `com/x`（前缀字符串直接比会误判）、嵌套类 `Foo$Bar`、
数组描述符 `[Lcom/x/Foo;`。

> 能力参考自 Dex 编辑器 1.021（它的 6 个 `java.bak` 快照正好记录了这条功能从表格 →
> 懒加载 adapter → 统计排名 → 成员跳转的迭代过程）。我们只取统计语义，UI 走自己的
> 工具目录，不搬它的 Activity。

### 日志列表在浅色主题下几乎看不见（真 bug）
v1.5.44 我只改了「崩溃分组」的日志块，**漏了实时日志列表本身**：

| 元素 | 原来 | 浅色主题对比度 |
|---|---|---|
| 时间 / pid | `#90A4AE` 写死 | 2.6:1 |
| tag | `#4DD0E1` 写死 | **1.7:1** |
| 级别 D | `#64B5F6` | 2.2:1 |
| 级别 I | `#66BB6A` | 2.4:1 |
| 级别 W | `#FFB74D` | **1.9:1** |
| 级别 E | `#EF5350` | 3.3:1 |

这些颜色都是按深色日志底设计的，落到浅色主题的白底上等于没印出来。
`levelColor` 现在收 `dark` 参数分两套，浅色那套对白底 5.1~7.1:1；
时间/pid 改 `onSurfaceVariant`，tag 深色用亮青、浅色用 primary。
级别筛选 chip 和日志行共用一个函数，一起修好。

顺手：扩展页"字节码无法自动翻译"的提示原来用 `AppPalette.orange`（3.15:1），改 `statusWarning`。

---

## 塔菲逆核 v1.5.46 —— 编辑器配色可以单独钉住了

v1.5.44 让编辑区跟着 App 主题走了。但"跟随"不总是想要的：
有人在浅色 App 里也喜欢深色代码区（就像 VS Code 深色主题），有人反过来喜欢浅色代码区。

**设置 → 外观 → 编辑器 → 代码区配色**：跟随主题 / 固定深色 / 固定浅色，默认「跟随主题」。

选「固定深色」时，语法高亮也跟着切回 VSCode-Dark 那套（关键字紫、字符串绿、注释灰），
不会出现"深色底 + One Light 浅色关键字"的错配 —— 底色、正文、行号、光标、语法色是一整组一起切的。

### 接线
`SettingsStore.editorTheme`（`auto` / `dark` / `light`，默认 `auto`）四处都补了：
属性本身、`snapshot()` 导出、`applyStr` 恢复、`flatKeys` + `applyPatch` 的 key 分支。
这个工程的设置项有三条通道（UI / 备份恢复 / `taffy_app_config` 远程 patch），
少接一条就会出现"备份恢复后设置丢失"或"远程 patch 无效"这种很难查的问题。

`auto` 仍然按 `colorScheme.background.luminance()` 判定，所以 Material You 动态取色和纯黑模式都算得准。

---

## 塔菲逆核 v1.5.45 —— 把「深色代码块」收干净

v1.5.44 把编辑器和日志块改成跟随主题后，剩下几处"写死终端深底"的代码块就显得格格不入：

| 位置 | 原来 | 现在（浅色主题） |
|---|---|---|
| 抓包页 · 教程正文 | `#0B0F14` + 近白字 | `surfaceContainerHigh` + `onSurface` |
| 抓包页 · 请求头 / WS 帧（`MonoBlock`） | 同上 | 同上 |
| 帮助页 · 功能教程 / 实现原理 / MCP Skill | 同上 | 同上 |

深色主题一律保持原来的终端配色不变 —— 那里本来就是深色界面。

判定逻辑与编辑器、日志页完全一致（`colorScheme.background.luminance() < 0.5f`），
所以 Material You 动态取色、纯黑模式都能算对。

### 有意没动的两处
- **终端面板**（`:terminal-view` 的 `TerminalScreen`）：它就是终端，深色才对。
- **设置 → Python 的代码输入区**：它自带整套深色配色（底色、边框、占位符、输出区），
  内部自洽 —— 深色输入框在白页里是「IDE 风格」而不是「看不清」，
  与编辑器那种"高亮跟着主题变、背景没变"的不自洽性质不同。

---

## 塔菲逆核 v1.5.44 —— 编辑器与日志块跟着主题走了

### 一个一直存在、但只在浅色主题下才发作的 bug
语法高亮其实早就按主题切了两套配色（深色 VSCode-Dark / 浅色 One Light），
但**编辑区本身是写死的终端配色**：底色 `#0B0F14`、正文 `#D6E2F0`、光标青色、行号深灰。

于是浅色主题下：
- One Light 的默认文字 `#383A42` 写在 `#0B0F14` 上 → **深灰压深黑，代码基本看不清**
- 注释 `#A0A1A7`、字符串 `#50A14F` 这些浅色系的颜色也是按白底设计的

现在编辑区直接跟 `colorScheme` 走：

| | 深色主题 | 浅色主题 |
|---|---|---|
| 底色 | `TerminalColors.bg`（#0B0F14） | `surfaceContainerLowest`（纯白） |
| 正文 | `TerminalColors.fg` | `onSurface` |
| 行号 | `TerminalColors.dim` | `onSurfaceVariant` |
| 光标 | teal | `primary` |
| 描边 | 10% 白 | `outlineVariant` |

判定用的是 `colorScheme.background.luminance()`，而不是"设置里的 themeMode + 系统暗色"各判一遍 ——
这样 Material You 动态取色和纯黑模式都算得准。

**顺手修掉的相关问题**
- `visualTransformation` 的缓存 key 只有语言，切主题后会复用旧配色的 `AnnotatedString` → key 加主题
- 文件树里**非目录文件名**用的是 `TerminalColors.fg`（近白色）→ 浅色主题的白底面板上根本看不见
- 编辑器底部状态栏的 `±N` / 语言 / 未保存 / 匹配数 / 星标 / 大纲 kind 都是 `AppPalette` 原色，
  浅色下 3.2~3.9:1（黄只有 2.44）→ 改用 `statusSuccess / statusWarning / AppPalette.ink`
- 语法高亮的**浅色调色板**按 AA 重新定过：注释 `#A0A1A7 → #6C7178`（2.6 → 4.9）、
  字符串 `#50A14F → #3F813E`（3.4 → 4.8）、函数/JSON 键 `#4078F2 → #2D63D6`（4.4 → 5.4）；
  深色注释也提到 `#7E8894`（3.9 → 5.3）。现在两套调色板全部 ≥4.7:1。

### 日志页同一套处理
崩溃日志内容块原来是写死的 `#111111` 黑底 + `#E0E0E0` 文字，白页里嵌一块纯黑。
现在深色主题保持 LogFox 黑底，浅色主题跟 `surfaceContainerHigh` 走；
状态点、授权状态、崩溃类型图标一并改用语义色（浅色下自动压暗）。

终端面板和"输出预览条"仍是深色 —— 那就是终端，保持原样才对。

---

## 塔菲逆核 v1.5.43 —— 把配色对比度钉成单测（426 个测试，改色就拦）

v1.5.42 是「量了一遍、改了」。这版把尺子留在仓库里 —— 以后谁再调配色，
不合规 CI 直接红，不用等有人截图说"看不清"。

### 1. `ThemeContrastTest`（新增）
10 个强调色 × 明暗两套 × 每套
- 5 个文字槽（primary / secondary / tertiary / onSurface / onSurfaceVariant）
- × 8 个表面（background / surface / ContainerLowest / Low / Container / High / Highest / surfaceVariant）
- 再加 4 组「容器配文字」和 3 组「实心填充配文字」

全部断言 **≥4.5:1**（WCAG AA 正文标准）；另加两条：高对比模式不得比普通模式差、
1dp 描边对每层表面 ≥1.15:1（低于这个「有线等于没线」）。

### 2. 「文字色阈值」按最暗的表面算（0.16 → 0.15）
`inkOf()` 的压暗目标原来按 `surfaceContainerHigh` 反算，
但 **`surfaceVariant` 才是最低的浅色表面**（l=0.945）——
chip 底上的字实测差 0.05~0.15：蓝 / 靛 / 紫 / 红 / 粉都只有 **4.34~4.48:1**，差一口气。
收紧到 0.15 后这 5 个色补上，复算 320 项最低 **4.59:1**。

### 3. 描边真的看得见了
单测第一次跑就抓到：浅色 `outlineVariant` 对 `surfaceVariant` 只有 **1.083:1** ——
chip 里的 1dp 分割线等于不存在。深色也只有 1.16:1。

现在浅色 0.910 → **0.870**、深色 0.200 → **0.216**，20 个组合最差 **1.19:1**。
留这么多余量是因为 Compose 的 `Color` 内部是**半精度浮点**（F16），
卡在 1.149/1.151 这种边界上算出来会晃，测不准 —— 这条也写进注释了。

### 4. 抓包页的方法标签
HTTP 方法色（GET 绿 / POST 橙 / WS 黄）与 HTTPS 提示是 11sp 小字，
比正文更需要对比，改取 `AppPalette.ink`；状态胶囊改用 `statusSuccess / statusWarning`。

---

## 塔菲逆核 v1.5.42 —— 强调色当文字用的可读性审计（260 项全过 AA）

这版没加功能，只做一件事：**把「强调色当文字」的对比度全部量了一遍，把不过的修掉。**

### 怎么测的
把 10 个强调色 × 明暗两套 × 13 组真实配对（按钮文字 / 胶囊文字 / 正文 / 三级文字 /
强调文字 / 互补文字 …）展开成 **260 项**，按 WCAG 相对亮度算对比度，正文标准 4.5:1。

修前 **22~27 项不合格**，最差的地方：

| 位置 | 色 | 对比度 |
|---|---|---|
| 浅色 · 黄色当文字 | `#C99A16` on 页底 | **2.44:1** |
| 浅色 · 橙色当文字 | `#C97A16` | **3.15:1** |
| 浅色 · 绿色当文字 | `#1E9E5A` | **3.25:1** |
| 浅色 · 青色当文字 | `#0E8C7F` | **3.91:1** |
| 浅色 · 互补色当文字 | `#97932B` | **3.00:1** |

M3 里 `primary` / `tertiary` 主要是**给文字和图标用**的（TextButton、链接、选中标签、
图例、状态点），不是只做填充。所以「选黄色主题 → 黄色小字看不清」是真问题。

### 怎么修的
1. **新增 `inkOf()`**：只降 HSL 的 lightness、保留色相与饱和度，把色压到
   相对亮度 ≤0.16 —— 这个 0.16 是按最亮的卡片面 `surfaceContainerHigh` 反算的，
   不是按页底（页底比卡片亮，按页底算会漏掉卡片上的字）。
2. **浅色 `primary` 取 ink 版**：黄 `#C99A16 → #89690F`、绿 `#1E9E5A → #177C46`、
   橙 `#C97A16 → #9B5E11`、青 `#0E8C7F → #0C796E`；粉/靛/紫本来就够，几乎不动。
   原色仍留给图表色块、进度条、填充。这与 Material 3 动态取色把浅色 primary
   定在 tone 40 是同一个道理。
3. **互补色（tertiary）同理**：`#97932B → #627722`，3.00 → 4.58:1。
4. **`onAccent` 判定的临界点修正**：按「白字 vs 深字谁更高」反算是 L=0.194，
   之前用 0.179 偏低 —— 红色 `#D93A34`（L=0.180）被判成深字只有 4.05:1，
   而白字其实是 4.56:1 已经过 AA。深字同时从 `#11151C` 压到 `#0A0D11`，
   让 L≈0.20 附近的青/绿也能过线。
5. **语义状态色压暗**：`statusSuccess / statusError / statusWarning` 在浅色下
   几乎只当文字用（"成功""失败"、状态点、日志级别），原来绿 3.25 / 橙 3.15 读不清。
6. **抓包页顺手改**：HTTP 方法标签（GET 绿 / POST 橙 / WS 黄）与 HTTPS 提示
   是 11sp 小字，比正文更需要对比 —— 改取 `AppPalette.ink`，
   状态胶囊改用 `statusSuccess`。

修完 **260 项全部 ≥4.5:1**，最低 4.58（紫主题的互补色）。

### 说明
这是纯软件可验证的量化审计：`chk3/audit2.py` 复刻了 Kotlin 的派生算法，
改一次跑一次，不靠肉眼在手机上比对。

---

## 塔菲逆核 v1.5.41 —— 深色界面整片同色系

### 深色底色与卡片原来跟强调色是两套色
v1.5.40 把 `outline` 掺进了主色相，但**页底和三层容器仍是中性格**：
`background #0B0D11`、`Lowest #08090C`、`Low #10131A`、`Container #141821`。
而卡片用的 `surfaceVariant` / `surfaceContainerHigh` 早就带色相了 ——
结果是深色界面像「一片灰底上嵌着几张彩色卡片」，色相断层就在卡片边缘。

现在四档中性灰全部由强调色派生，**亮度逐一持平原档位**（0.055 / 0.039 / 0.082 / 0.104），
饱和度只给 0.10（约 ±3/255 的色偏）—— 层次、对比度、可见度一点不动，只是整片底带上了同一个色相。

浅色同理（背景 0.971 / Low 0.984，饱和度 0.16~0.18）；
`Lowest` 与 `Container` 在浅色本来就是纯白，所以浅色实际只动了背景和 Low 两档。

纯黑模式（pureBlack）仍然压到 `#000000`，卡片继续走原石墨档位 —— 纯黑下刻意不掺色，
否则整屏会浮一层彩色雾。

### 改动位置
`AccentPalette` 新增 `tintedBackground / tintedLowest / tintedLow / tintedBase`，
`appLightColors` / `appDarkColors` 接上。换强调色时页底、卡片、描边、容器一起走，
Material You 动态取色下也一样（都从实际 `primary` 反推）。

---

## 塔菲逆核 v1.5.40 —— 主色上的文字对比度 + 描边同色系 + 分析页统计上色

### 一、主色上的文字：橙 / 青 / 绿底原本看不清
`onAccent`（也就是 M3 的 `onPrimary`）原来按 **HSL 的 lightness > 0.64** 判「深色字还是白字」。
HSL 亮度跟人眼亮度不是一回事，实测：

| 强调色 | 白字对比 | 深色字对比 | 旧判定 | 现在 |
|---|---|---|---|---|
| 橙 `#C97A16` | 3.34:1 | 6.28:1 | 白字 ❌ | 深色字 |
| 青 `#0E8C7F` | 4.14:1 | 5.08:1 | 白字 ❌ | 深色字 |
| 绿 `#1E9E5A` | 3.45:1 | 6.09:1 | 白字 ❌ | 深色字 |
| 粉 `#D6336C` | 4.62:1 | 4.55:1 | 白字 ✓ | 白字 |

改成按**相对亮度**判（白/黑对比相等的临界点 L = 0.179），三种色的按钮文字现在都过 AA。

### 二、1dp 描边不再是"中性灰"
`outline` / `outlineVariant` 原来固定 `#C7CDD6` / `#E3E7ED`（深色 `#4B5360` / `#262C36`），
跟卡片、容器、chip 是两套色系。现在从强调色派生：**亮度与原灰阶持平**（描边的可见度不变），
只把色相掺进来 —— 整页的线、底、容器终于是一套颜色。

### 三、分析页的统计行上色
调用图头部那排统计 chip（函数 / 调用边 / 入口 / 显示）原来全灰。现在每个取一个派生色
（主色 / 互补 / 三角 / 邻近），一排指标一眼分得开，换强调色时整套跟着变。

### 四、两处状态保持
- 函数详情的四个页签（汇编 / 控制流 / 伪 C / 交叉引用）与交叉引用的四个页签改成 `rememberSaveable` ——
  切走再回来不再重置到第一个页签
- 「整套配色」预览条在开启动态取色时隐藏（实际配色来自壁纸，预览会误导）

---

## 塔菲逆核 v1.5.39 —— 主题一致性回归 + 几处体验补齐

### 一、换主题后仍然"没跟着变"的地方（上一版漏的）
- **更新页的「当前版本」卡**：写死了深色渐变 `#1E2026→#15171D` + 白色 8% 描边 + `#9AA0AF` 文字 ——
  浅色主题下是一块突兀的深色卡。改成强调色软底 + 主题描边 + `onSurfaceVariant` 文字。
  同页上面那个 84dp 的图标圆盘也是写死深色渐变，改成强调色容器。
- **统计页**的保活开关状态用了写死的 `#8E8E93`，改成 `onSurfaceVariant`。
- **Markdown 链接**写死 `#007AFF`，改成主题主色。
  （注意：`inlineHtml` 不是 `@Composable`、读不到 `MaterialTheme`，所以链接色改成从调用方传参进来。）

### 二、设置 → 外观
- 强调色的**色点跟随深浅主题**：深色下用亮版强调色，否则色点在深底上糊成一团
- 新增**整套配色预览条**：六个色块就是派生出来的 「主 / 互补 / 三角 120°·240° / 邻近 / 中性」，
  选色时直接看到这套配色，不用回首页试
- 开启动态取色（Material You）时，在强调色上方提示「配色由壁纸决定，下面的选择暂不生效」——
  之前用户选了粉却看不出变化，会以为是 bug

### 三、DEX 编辑器
- 补上截图里的**「转到指定行」**（⋮ 菜单 → 输入行号 → 光标跳到行首，状态栏显示「已跳到第 N 行（共 M 行）」）
- 单 `.dex` 保存成功后补上**备份路径提示**（`<文件名>.bak`）：写回分支在 core 里不返回路径，界面按约定推断

### 四、jadx 反编译的并发上限
单类反编译 25 秒超时后线程并不会停（jadx 是纯 CPU 计算）。连续点五个类就是五个线程各跑二十秒、
手机直接发烫。现在同时最多 3 个，超了明确拒绝并提示「上一个可能卡在难解的类上」。

### 五、这轮排查做了什么 / 排除了什么
扫了根包 315 个 `.kt`（5.8MB），跑了硬编码颜色、无上限 `readBytes`、共享 `SimpleDateFormat`、
4 KiB 边界解码、`runBlocking`、空 `catch`、`Lazy` 嵌套 `verticalScroll`、`Color.White` 文字等规则。
- **真问题**：上面四条（已修）
- **误报，已确认不改**：
  - `Lazy` 与 `verticalScroll` 的 7 处命中，逐个数过都是不同 composable 里的独立区域（不是嵌套）
  - `LinuxRootfs` 的 `String(header, 157, 100, UTF_8)` 是 tar 头的固定偏移字段（ASCII 文件名），不是流截断
  - `ScriptTool` 的按字节截断已经有 UTF-8 首字节回退逻辑，不会切出半个汉字
  - `readBytes` 的 45 处大多读的是自己刚写的小文件

---

## 塔菲逆核 v1.5.38 —— 默认粉色 + 整套派生配色（不只是按钮变色）

### 一、问题在哪
上一版 `appLightColors(accent)` 只把传入的颜色**塞进 primary**，其余槽位全是写死的：
`primaryContainer` 固定浅蓝 `#DCE6FF`、`secondary` 固定灰蓝、`tertiary` 固定紫、
`surfaceVariant` 固定冷灰。于是选「粉」之后只有按钮是粉的 —— 卡片底、chip、选中底、
容器描边、连页面底色都还是蓝灰，看起来像"没换主题"。设置里的强调色列表里也**没有粉**。

### 二、现在怎么做的
新增 `AccentPalette` / `accentPaletteOf()`：以强调色的**色相**为基准用 HSL 派生整套色板。

| 槽位 | 派生规则 | 用途 |
|---|---|---|
| `primary` | 强调色本体 | 主操作、选中态 |
| `primaryContainer` | 同色相、高亮、降饱和 | 选中底、主 CTA 浅底、状态胶囊 |
| `secondaryContainer` | **邻近色**（色相 −30°） | 次级容器、页签底、进度底 |
| `tertiary` / `tertiaryContainer` | **互补色**（色相 +180°） | 对比强调、图表第二色 |
| `surfaceVariant` / `surfaceContainerHigh` | 表面色微带主色相（3~8% 饱和） | 卡片底、分组底 |
| `chart[6]` | 主色 / 互补 / 三角 120°·240° / 邻近 / 冷灰 | 多分类可视化 |

文字槽（`secondary`、`onSurfaceVariant`）仍保持**近乎中性**、只带一点色相 ——
否则"次要文字"会变成粉字，满屏像链接。

### 三、默认粉 + 设置里能选
- 默认强调色 **粉**（浅色 `#D6336C`，深色 `#FF8FBC` —— 比原来的 `#C2417F` 更鲜、和紫能分开）
- 设置 → 外观 → 强调色：**粉放回第一位**，并且每一项都带**实际色块**（只有文字的话用户点完不知道长什么样）；
  选中项用该色自己的浅色容器做底，所以选粉是浅粉底、选青是浅青底

### 四、页面跟着变（不只是按钮）
- **首页状态卡**：引擎运行中整张卡切成「强调色软底 + 强调色描边」，停止时回中性
- **首页入口宫格**：图标底取派生六色循环（主 / 互补 / 三角 / 邻近 / 冷灰），卡片底仍中性 ——
  只有图标这一小块带色，既不花，又能一眼看出当前主题是粉还是青
- **整页背景**：主色 + 互补色双段渐变 wash（原来只有单色，"换了色也只剩淡淡的灰"）
- **统计页**：工具数用互补色（和总调用数的主色形成对比）
- 帮助页 / DEX 页 / 扩展页里写死的蓝色全部改为主题主色
- 其余 70+ 页面走 M3 token，容器/chip/页签/选中底自动跟着派生色板变

开了 Material You（动态取色）时，派生色板从**屏幕实际的 primary** 取色，不会出现"设置里的色"与"屏幕上的色"两套。

---

## 塔菲逆核 v1.5.37 —— DEX 编辑器补齐三件 + 上游（SOMCP / 太墟）对齐

### 一、smali 辅助三件套（截图里那三项，之前只有入口没有实现）
- **寄存器分析**：逐个方法解析 `.registers` / `.locals`，按方法签名算参数宽度（long/double 占 2、数组占 1、非 static 加 this），
  统计用到的最大 v/p 与不同 v 个数，越界逐条给行号（`v9 ≥ 总数 8`）。全部是纯 Kotlin，11 条单测覆盖参数宽度、越界、locals、range、扩充。
- **寄存器扩充**：给光标所在方法加寄存器。只允许**增大** —— p 寄存器按符号寻址，增大不改变映射；
  减小会把参数错位，工具直接拒绝。没有声明行时补一条。
- **指令补全**：常量 / 字段 / invoke / 跳转 / 返回 五类模板，点一下插到光标处。

### 二、大文件不再硬扛
超过 300KB 的 smali 默认进**只读虚拟化浏览**（LazyColumn，几万行只组合可见的几十行），
点「进入编辑」才回到 `BasicTextField`。之前只能"关高亮 + 提示"，编辑本身照样卡。

### 三、jadx 单类反编译加超时
`cls.code` 是纯 CPU 计算、不吃协程取消。现在扔到 cached 线程池并限时 25 秒，超时给明确提示，
界面不再无限转圈；同时区分「超时」与「纯接口正常返回空」。

### 四、上游对齐结论
**SOMCP v1.0.22 → v1.0.23**（2026-11-01）：逐文件比对后**无需整合**——
- 新增的 25 个文件（AuditTools 那批：JniMangle / StringCensus / CryptoFingerprint / NativeProfile / PatchAudit /
  ElfAudit / ApkSigTools / CompromisedCaBlocklist / EngineRuntimeAudit…）塔菲在更早的版本里已经全部具备（工具名一一对应）。
- 依赖比上游新：okhttp 5.5.0（上游 5.4.0）、jna 5.19.1（上游 5.10.0）、fastjson 已换 2.0.65 兼容层（上游 1.2.84）。
- 「同版本号不再提示更新」：塔菲的实现按 tag 记录已提醒时间，首次同版本不会提示，还能发现真正的重发布 —— 比"一律不提示"更准，不改。

**太墟 v1.8.0**（2026-10-31）：vendored 代码已接近 v1.8.0（流式下载、技能导入 id 冲突等修复都已在），
本次补上两项缺的：
- `ManualCompactionBoundary`：手动压缩按完整用户轮次保留尾部，**跨边界的工具调用与结果成对保留**
  （否则随后落库的结果会失去原调用），配单测。
- `ApiMessageProjector` 历史图片 8MB 总预算：之前每轮都把全部历史图重发，请求体随会话线性膨胀；
  现在按最新优先发额度，超出的旧图改成一行说明（UI 里原图仍在）。

---

## 塔菲逆核 v1.5.36 —— 常量页跳转 + 临时文件回收 + 大包上限口径

v1.5.35 发布后接着排查的三处，都是「用起来别扭」而不是编译错误：

### 一、常量页点一行现在会跳到定义处
原来点整行只是把值复制走 —— 但常量页的用途是「找这个值在哪用」，不是剪贴板。
现在点行直接打开它所在的类；要复制值点右侧图标（加大到 32dp 触控区）。

### 二、临时文件回收补齐
单 .dex 字符串替换新增了 `rstr_dex_` / `rstr_out_`，单 dex 写回新增了 `dex_patched_`，
这些前缀没纳入 `TempJanitor` 的启动回收列表 —— 进程被杀时会残留。已补齐。

### 三、大 APK 不再被「转 Java」一刀切拒绝
上限原来按**文件体积**判定，一个 200MB 的包会被直接拒绝，即使它的代码只有 30MB
（jadx 本来就只解析 dex，不碰资源）。现在 APK 输入按 `classes*.dex` 的总大小算，
单个 dex 仍按文件大小。

---

## 塔菲逆核 v1.5.35 —— 清 DEX 编辑器遗留边界 + 一轮排查

v1.5.34 发布时列了四条已知边界，这次全部清掉，并在排查中又挖出几个真 bug。

### 一、单 .dex 现在能改字符串、能改单条指令
之前 `.dex` 模式下 `patch_instruction` / `replace_string` 会直接报「暂不支持」，等于只能整体替换类。
现在写回链路统一走 `writeCompiledDexBack`：单文件走「快照 + .bak + 原子覆盖」，APK 还是原来的重打包路线。
`replace_string` 对单 dex 单独实现（反汇编 → 按 scope 选 smali → 替换 → 重编 → 写回），支持 method / class 两个范围；
`global` 范围在 >40MB 的 dex 上会直接拒绝并说明原因（反汇编整个文件会把磁盘和内存一起吃掉）。

### 二、「转成 Java」不再干等
- 打开类 1.2 秒后在后台预热 jadx，等真正点「转成 Java」时通常已经分析完了
- 超过 120MB 的输入直接拒绝，并提示改用搜索定位类或 smali 视图（jadx 会把整个 DEX 展开进内存，再大必 OOM）
- 结果截断点回退到非代理对位置，不再出现孤立 surrogate 导致的乱码

### 三、常量页从「只能搜字符串」变成真常量页
- 新增 **字符串 / 整数** 类型切换
- 关键字留空时「列全部」（上限 800），不必先知道搜什么才能看到东西
- 整数类型支持不限值列举（DexIndex 新增 `.*` 哨兵语义）

### 四、排查出的真 bug
1. **常量页会崩**：列表用 `className + detail` 当 Compose key，列全部整数时同一类里的相同常量值会出现多次 → 重复 key → `IllegalArgumentException` 直接崩界面。已去掉 key。
2. **`list_classes` 截断不可见**：`total` 被当成「已返回条数」，且达到 limit 就 `break` —— 多 DEX 的包里 `classes2/3/4.dex` 的类**一个都不出现**，用户还以为是包里有没这些类。改为 `total`=真实命中数 / `returned` / `truncated`，上限 2000→20000，不再提前退出；界面如实显示「已加载 N / 共 M 个类」并引导用过滤缩小。
3. **改完 smali 再看 Java 是旧代码**：保存成功后没有让已反编译的 Java 与 jadx 里那份已 load 的图失效，容易被误判成「没保存成功」。现在保存成功即清缓存。
4. **包树 O(n²)**：每层都递归统计子树类数，几千个类时过滤框每敲一个字都要重算。改为建树时累加。

### 五、编辑体验
- smali 语法高亮从 `visualTransformation` 内部每帧重算改为 `remember(editor.text)` 只算一次（打字卡顿的主要来源）
- 超过 8000 行自动关闭高亮，并在行数旁注明「文件较大，已关闭高亮」
- 搜索页加了 `.*` 正则开关：原来 placeholder 写着「支持 . * 前缀后缀」，但没开 regex 时 `.` `*` 只是普通字符，输入 `*Activity` 实际在搜字面量 —— 文案不再骗人，且给了明确开关
- 切换搜索/常量的类型时清空上一次命中，不再显示另一种类型的旧结果

---

## 塔菲逆核 v1.5.34 —— DEX 编辑器：四页签形态 + 单 .dex + 转 Java（jadx 移植）

按你发的截图把 DEX 编辑器重做了，同时补上「单独的 .dex 文件」和「转成 Java」。

### 一、页面按截图重排

**顶栏**：← 返回 / 标题（类名或「DEX 编辑器」）/ 副标题（文件名 · 类数）/ 有改动时出现保存按钮 / ⋮ 菜单。

**四个页签**：

| 页签 | 内容 |
|---|---|
| 浏览 | 包 → 类 两级树，点包折叠展开（▾/▸ + 文件夹），右边是包内类数；点类直接进编辑器。顶部有类名过滤 |
| 最近 | 本次会话打开过的类，最多 40 条 |
| 搜索 | 类名 / 方法名 / 字段名 / 字符串，四种类型，走本地索引（纯 dexlib2，不启动引擎）。结果按 C/M/F/S 标类型，点一下进对应类 |
| 常量 | DEX 字符串池搜索，点条目复制 |

**编辑器**：

- 左侧行号，与正文共用一个 ScrollState，行高严格对齐（18sp 等宽）
- smali 语法高亮：指令（`.method`/`.field`/…）用主色、类型描述符 `Lx/y;` 绿色、字符串橙色、注释灰、修饰符紫
- 顶部方法芯片行，点一下跳到对应 `.method`
- 底部符号栏：`→ / + - * = < > ; " ' ( ) { } ,`（插到光标处，省得为个分号切键盘）
- 右上「smali / Java」切换；⋮ 菜单里有：选择文件、重新列类、转成 Java、复制全部、还原改动、复制行、删除行、切换注释、增加缩进、减少缩进

### 二、单独的 .dex 文件

`taffy_smali_edit` 原来的定位逻辑只会从 APK 里找 `classes*.dex`。现在 `path` 直接给 `.dex` 也行：

- `list_classes` / `extract` / `list_methods` 直接读该文件
- `replace` 重编后**原子覆盖原 .dex**（留一份 `xxx.dex.bak`），不需要签名
- `patch_instruction` / `replace_string` 在 .dex 模式下**明确拒绝**并说明原因：它们的写回路径依赖重建 APK，硬走会在中途失败

选择文件时 `*/*` 都收，APK 与 .dex 都行；文件先复制到 cache 再改，原始文件不会被就地修改（除非你改的就是 cache 里那份）。

### 三、转成 Java（jadx 移植）

新增 `taffy_dex_to_java`：

- `action=classes` 列出可反编译的类
- `action=class` 反编译指定类（`a.b.C` 或 `a/b/C` 都认）
- `action=search` 按关键字过滤类名

实现要点：

- jadx 1.5.0（`jadx-core` + `jadx-dex-input`），class 版本 55，d8 可处理；用到的 `java.nio.file` 在 minSdk 29 上齐全
- **缓存已 load 的 decompiler**：`load()` 会把整个 DEX 走一遍分析（真实样本 549 个类约 20 秒，手机上更慢），之后单个类很快。同一个文件反复看不同类不会重复付这笔成本；文件变了（size/mtime）才重建
- `skipResources=true`：只反编译代码，不走 aapt2 资源解析
- proguard 保留 `jadx.**` 与 `com.google.protobuf.**` —— jadx 的输入插件走 `ServiceLoader` 注册，R8 一旦重命名，运行时只会静默返回空结果（不报错，最难查）

### 四、验证

新增单测 `DexJavaDecompilerTest`：现场用 smali 汇编一个真 dex（`const/16 v0, 0x2a` + 一个字符串常量），再用 `DexJavaDecompiler` 反编译回来，断言类声明、方法名、返回值（42）、字符串常量都在；另有两条例外路径（不存在的类、不存在的文件）。这条测试跑在 CI 的 `:app:testDebugUnitTest` 里，随每次构建执行。

### 已知边界

- `.dex` 模式不支持 `patch_instruction` / `replace_string`（要改单条指令请用 smali 整体替换）
- 第一次「转成 Java」要等整个 DEX 分析完（大包几十秒），期间界面转圈；之后同文件切类很快
- 「常量」页搜的是**字符串池**，不是完整的常量表（整数/类型常量请用「搜索」页）
- 大 DEX（几十 MB）在手机上反编译可能触发内存压力，建议先用「搜索」定位类再反编译

## 塔菲逆核 v1.5.33 —— 分析页新增 DEX 工作台（侧栏一级入口）

你问的"DEX 编辑器的功能整合到哪去了"，查下来是这样：**MCP 工具全都在，界面没接上**。

工具侧早就是齐的 —— `taffy_smali_edit`（list_classes / extract / replace / list_methods /
patch_instruction / replace_string 六种 action）、`taffy_dex_index`、`taffy_dex_class_outline`、
`taffy_dex_method_code`、`taffy_dex_xref`、`taffy_dex_incremental_rebuild`、`taffy_dex_deodex`、
`taffy_dex_rename`、`taffy_dex_dump`、`taffy_dexkit`、`LocalDexInspect`。问题出在分析页：

1. 只有一个**只读**的「DEX 查询」视图（类/方法/字符串搜出来复制走，不能改）；
2. 侧栏 `analysisRailItems` 写死了 7 项，**不含 DEX**，所以它只能从顶栏「⋮ → 更多视图」进去。

真机表现就是：看不到入口，也改不了任何东西。

### 新增：DEX 工作台

侧栏第二项「DEX」（图标在 home 之后，位置固定，不用再翻菜单）。整条链路：

1. **载入 APK** —— 选择后复制到 cache 再操作，**不会动你原来的文件**；显示大小。
2. **按包名列类** —— 包名前缀可留空，最多 2000 个类；列表出来后可以在本地二次过滤（不重复扫描）。
3. **点类进入编辑** —— 一次拿回方法/字段清单 + 完整 smali；顶部方法芯片点一下就跳到对应
   `.method` 行；等宽字体、可全选复制。
4. **保存并重编** —— 走 `taffy_smali_edit action=replace`：自动定位类在哪个 DEX → baksmali
   整个 DEX → 替换该类的 .smali → smali 重编 → 按原压缩方式/对齐写回 APK。原包会留一份
   `xxx.bak.apk`，保存成功后提示里直接写明**签名已失效，需要重新签名**。
5. 改动未保存时底部显示「未保存」，可「还原」回加载时的内容。

空态和错误都在页内说明（工具缺失 / 类不在任何 DEX / smali 重编语法错 / APK 没落到产物上），
不跳别处。

### 实现说明

- 全部走已注册的 MCP 工具，没有另造一套 DEX 引擎。
- `AnalysisWorkspace.kt` 只改两行（when 分支换实现、侧栏列表加一项），行数不变 —— 那个文件
  有 13755 行的架构基线，增长即 CI 报错，所以新界面单独放在 `DexWorkbenchView.kt`。
- 标签从「DEX查询」改为「DEX」：它现在是"进得去、改得了"的工作台，不只是查询。

### 已知边界

- 只吃 APK（`taffy_smali_edit` 的定位逻辑按 APK 内的 `classes*.dex` 走）；单独的 `.dex` 文件
  还不行，下一个版本再补。
- 「保存并重编」会重编整个 DEX，大包上要等几秒到几十秒，期间按钮转圈、不可重复点。
- 反编译到 Java 源码这块目前没有（塔菲走的是伪 C + smali）；上游那个 DEX 编辑器的 Java 视图
  依赖 jadx，移植进来是另一个量级的事。

## 塔菲逆核 v1.5.32 —— 主题与版式整体重做

前面几轮都在"修页面"，这一轮回头看了设计系统本身。问题不在某个页面丑，而是三处在打架：

1. **深浅两套性格**。浅色是蓝主色（#1A6EF0），深色却是**深绿底 + 绿主色**（#0F1F15 / #66D39A）—— 从 Exbin 照搬来的一套，但逆向工具的主界面是地址、十六进制、伪 C 和日志，墨绿底会把代码的中性灰衬得发闷；更要命的是**屏幕上的绿太多**：主色是绿、状态"运行中"是绿、成功也是绿，颜色不再承载信息。
2. **六档字号挤成三档**。11 / 12 / 14 / 14 / 16 / 22 —— `title` 和 `bodyStrong` 都是 14，`label` 与 `body` 差 1sp。一屏里几乎全是小字，层级只能靠颜色硬撑，看着就闷。
3. **三套圆角 + 两个旋钮**。`AppShape` 一套、`uiMetrics` 一套、各页面还有硬编码的 12 / 20 / 24。加上「密度」「圆角」两个设置项，能组合出 9 种版式 —— 其中"24dp 圆角 + 12dp 页边距 + 8dp 行内边距"这类组合，怎么调都不好看。

### 新的设计系统：Graphite

| | 浅色 | 深色 |
|---|---|---|
| 背景 | `#F6F7F9` 纸面 | `#0B0D11` 石墨 |
| 卡片 | `#FFFFFF` + 1dp 描边 | `#141821` → `#191E27` 逐级提亮 |
| 主色 | `#2C6BED` | `#8FB4FF` |
| 层级 | 只靠底色深浅 + 1dp 描边，**不用阴影** | 同 |

- **主色只有一个蓝**，且只给主操作和选中态；成功/警告/危险只在状态点、错误提示上出现。
- **字号**：11 / 13 / 14 / 16 / 18 / 26（`label` / `body` / `bodyStrong` / `title` / `headline` / `display`），档距拉到 1.2~1.4 倍。
- **间距**：页边距 16、组间 24、行高 56，全部是 4 的倍数。
- **圆角**：控件 10 / 卡片 14 / 弹层 20，就这三档。
- **去掉「布局密度」和「圆角」两个设置**，版式不再可调（字号缩放保留）。
- 强调色从 10 个收敛到 6 个：红和黄是状态语义，不该拿来当主色；灰做主色会让"主操作"看不出是操作。
- 历史遗留的 `AppleColors`（iOS 灰阶）一并映射到新灰阶 —— 之前它会跟 M3 token 在同一屏里形成"两套灰"。

### 布局重排

**首页**原本是「主状态卡 + 8 个四列小宫格 + 服务卡 + 大 CTA + 统计」，一屏塞满且 8 个入口权重完全相同。现在：

- 8 个 40dp 图标 + 单词标签的宫格 → **6 个两列卡片**（32dp 图标 + 名称 + 一句人话说明）。宫格里"伪代码"只是一个词，点进去才知道是什么；卡片里直接写"把函数还原成可读的 C 代码"。
- 「设置」从宫格里拿掉 —— 它是全局目的地，顶栏已经有入口。
- 引擎运行中时，主状态卡套一圈主色描边：一屏只有一个元素该带颜色，就是"服务在跑"这件事。

**底部导航**：M3 默认 80dp + 32dp 指示器，在 6 寸屏上占了将近 1/8 屏高，而它承载的信息只有 5 个词 —— 压到 64dp；顶部加 1dp 分隔线（原来只靠底色差，深色下滚到底分不清哪是内容哪是导航）；选中态只给图标上色，文字保持次级色。

**设置页**行度量统一：行高 56、卡内 16、组间 24、圆角 14（原来 22 / 14 / 54 / 12 混着用，单看没问题，放在一起就"对不齐"）。

### 说明

这一轮改的是**系统层**（主题 token + 组件基座 + 首页 + 导航 + 外观页），79 个页面里凡是用 `AppText` / `AppShape` / `UiMetrics` / `GlassGroup` / `NavRow` 的地方都会跟着变。分析页里那批专业视图（CFG 画布、交叉引用、伪 C、终端、日志流）保留各自的深色专业配色 —— 那些地方的颜色是给代码和图形用的，不该被主题统一掉。

## 塔菲逆核 v1.5.31 —— .NET 补齐写入能力，libdsmcp 从预编译二进制换成源码自建

上一版把 .NET 的**只读**能力搬到了 Kotlin，写操作还挂在 `libdsmcp_native.so` 上。这一版把最后三块补上，同时把那个来路不明的 `.so` 彻底换成源码自建 —— 16 KiB 页的遗留到此清零。

### 一、.NET 补上三个 Kotlin 实现

`DotNetReader` 新增：

- **`resolveToken`** —— 令牌解析。TypeRef / TypeDef / Field / MethodDef / MemberRef / ModuleRef / TypeSpec / MethodSpec / 用户字符串（0x70）都能给出可读名字，方法还会回溯到所属类型（`System.SR::get_ResourceManager` 这种形态）。
- **`editIl`** —— 在方法体内按 IL 偏移等长覆盖写入。tiny / fat 两种头都认，越界、非方法令牌、空补丁一律明确拒绝。
- **`editString`** —— 改 `#US` 堆里的用户字符串。

两个写操作都只做**等长或更短**的改写。原因写进了错误消息里：变长会挪动 `#US` 堆后面的所有条目，而 `ldstr` 指令里存的是堆内偏移，改了就全指错地方。新串更短时用 0 填满原 blob 尾部，堆总长度不变，后续条目偏移不受影响。

### 二、`libdsmcp_native.so`：从"来路不明的二进制"变成源码自建

这个库原本是上游更早的预编译产物 —— SOMCP 现在的 `cpp/` 里只有 rizin / blutter / xanso 三个 target，没有它。顺着 JNI 符号反查，源码其实在 `zigou2162/DSMCP`（GPL-3.0，与本项目同许可），整份 vendored 进 `app/src/main/cpp/dsmcp/`。

原来那份二进制有两个问题：

1. `PT_LOAD p_align` 是 `0x1000` —— Android 15 起 16 KiB 页设备上 bionic 直接拒绝加载，PE/.NET 的结构视图整块静默消失。
2. 更麻烦的是 `parse_metadata_root` 把 Length 字段读在 `metaOff+16`（规范是 `+12`，`+16` 才是版本串起点），**任何真实程序集都会报 `truncated version string`**。也就是说它在 arm64 上"能加载"，但 .NET 结果恒错 —— 这也是为什么上一版之后 .NET 全部改走 Kotlin。

现在用 NDK 从源码重编，**4 个 ABI 全齐**（原来只有 arm64 有这份库，其它三个 ABI 上 .NET 工具一直是不可用的）：

| ABI | 大小 | PT_LOAD | p_align |
|---|---|---|---|
| arm64-v8a | 300 KB | 3 | 0x4000 |
| armeabi-v7a | 228 KB | 3 | 0x4000 |
| x86 | 278 KB | 3 | 0x4000 |
| x86_64 | 294 KB | 3 | 0x4000 |

`tools/elf-align-baseline.json` 的 `known_mismatch` 现在是空数组 —— 仓库里已经没有不满足 16 KiB 页加载要求的 `.so`。

顺手修掉源码里另外三个真 bug（都用真实程序集验证过）：

- **表号枚举整组错位 4**：`ManifestResource / NestedClass / GenericParam / MethodSpec / GenericParamConstraint` 写成 `0x24..0x28`，实际是 `0x28..0x2C`。后果是这几张表行宽恒为 0，表流偏移累计少算，之后所有表都从上一张表的中间开始读 —— 实测 Assembly 名字被读成了 `DefaultArrayPool`1`（某个 TypeName）。
- **`il_disasm` 的 `"IL_%0404X"`** 是笔误（宽度 404 零填充），每行 IL 标签后面跟着 400 个 0。
- 补上 `ClassLayout / FieldLayout / FieldMarshal / DeclSecurity` 四张表的行宽；遇到算不出行宽的表改为**报错**而不是静默 `size=0` —— 原来那样会带着错误的偏移继续往下读，产出垃圾却不报错。

### 三、测试用真实程序集，不再只有手算样本

新增 `System.Buffers.dll`（21,376 字节，MIT，来自 nuget.org 的 `System.Buffers` 4.5.1 包）作为测试资源。上一个版本的最小样本是手算的 1024 字节 PE + 三张表各一行 —— 它能证明"表流能走通"，但证明不了 case 表宽度、coded index、`#US` 堆边界这些真实世界才会踩到的地方。

11 条测试覆盖：真实版本号、10 个 TypeDef、35 个 MethodDef、`#US` 偏移、IL 反汇编、令牌解析、IL 等长改写（只允许 1 个字节差异）、超长字符串拒绝、UTF-16 代理对。**期望值由一份独立的 Python 解析器在同一文件上算出**，不是从被测代码里抄的。

### 四、排查：把新引入的 C++ 当成不可信输入来打

vendored 源码是全新的攻击面 —— 它在进程内解析用户给的 `.dll` / `.so`。所以：

- **解析层**：ASAN + UBSAN 编译，3000 次变异（随机截断 / 字节翻转 / 0xFF 填充）灌进 `parse_elf` / `parse_pe` / `parse_dotnet`，零崩溃、零越界。
- **JNI 层**：200 个变异样本 × 15 个 native 调用（含 JSON 生成与数组回填），JVM 零崩溃、零异常。

上面那三个 native bug 就是这一路查出来的。

### 仍然存在的边界

- `editString` 只能等长或更短。想加长字符串需要重建 `#US` 堆并把所有 `ldstr` 的偏移重映射，那已经属于重写程序集范畴。
- ELF / PE 的 parse 与 patch 仍走 native（Kotlin 侧只接管了 .NET）。这条链路在 4 个 ABI 上都可用，但它的正确性目前只有 fuzz 与结构化对照背书，没有大样本回归。

## 塔菲逆核 v1.5.30 —— .NET 工具摆脱 native 依赖 + 技能导入的 id 冲突

### 一、.NET 只读能力改用纯 Kotlin 实现

上一版把「`libdsmcp_native.so` 的 16 KiB 对齐」列为已知未修，理由是"没有源码，只能自己
重写 .NET metadata 解析（约 1500 行，且没有样本可验证）"。这次把它做掉了。

先说结论：这个库在 SOMCP 上游**确实没有源码** —— 上游 `app/src/main/cpp/CMakeLists.txt`
只构建 `rz_native` / `blutter_bridge` / `xanso_native` 三个 target，它是更早的预编译产物。
所以只能重写，而不是重编。

新增两个文件：

- **`DotNetReader`**：PE 头 → `DataDirectory[14]`（CLR runtime header）→ COR20 → metadata
  root（`BSJB`）→ streams（`#~` / `#Strings` / `#US` / `#GUID` / `#Blob`）。核心难点是表流的
  **行宽**：一列可能是定长、heap 索引（宽度由 `HeapSizes` 位决定）、表索引（目标表行数
  超过 0xFFFF 用 4 字节）或 coded index（宽度由 tag 位数与目标表最大行数决定）。
  要跳过前置表就必须把 ECMA-335 II.22 的 45 张表全部列出来 —— 这也是代码大半体积的来源。
  如果表流里出现 schema 之外的表（PDB 才有的 0x30+），**直接放弃解析**而不是按 0 宽度硬跳：
  后者会让后面每张表的偏移都是错的，读出来的是一堆看起来正常的垃圾。
- **`IlDecoder`**：CIL opcode 表（单字节 + `0xFE` 前缀）与操作数宽度，分支目标输出 `IL_xxxx`
  标签；遇到未知 opcode 按 `.byte` 继续单步前进，不整段放弃（被混淆过的 IL 很常见）。

`BinaryEngine` 的 6 个只读 dotnet 方法改成 **native 优先、Kotlin 兜底**：
`detect` / `listTypes` / `listMethods` / `listStrings` / `dumpIl` / `disasm`。
`dotnetResolveToken` 与两个写操作（改 IL、改用户字符串）仍只走 native，不可用时给出带
`loadStatus` 的明确错误 —— 写操作改的是文件字节，没有样本验证就不该硬来。

单测用 `make_minimal_assembly.py` **手算**出来的最小程序集（1024 字节，只有
Module / TypeDef / MethodDef 各 1 行），不依赖 .NET 工具链；断言精确到
`My.Namespace.MyType` / `.ctor` / RVA；另有逐段截断与字节破坏用例，要求返回
`null`/`error` 而不是抛异常。

写测试时踩到自己埋的坑：`Meta.read(table, row, col)` 一开始按 0-based 算偏移，
而调用方传的是 1-based 行号，于是每个类型名都读到下一行的位置、全是空串，
`listTypes` 静默返回 0 条 —— 正是"看起来成功其实什么都没解析出来"的那种失败。
现在 `read()` 统一 1-based，并对行号做边界检查。

### 二、技能导入：没声明 id 的技能包会互相覆盖

抄自太墟 v1.8.0（`fix(skills): 修复技能导入标识冲突`）。原来的 `inspectZipBytes`
默认 `fallbackId = "custom_skill"`，调用处还硬编码成 `"custom_zip"` —— 两个都没写
`id` 的技能包会共用同一个标识，第二个覆盖第一个（或被直接判成重复导入）。
社区 Agent Skills 常常只声明 `name` 不声明 `id`，正好命中。

- `fallbackId` 默认值改成 `skill_${UUID.randomUUID()}`（每次唯一）；
- 解析时增加 `name` 作为 `id` 的备选（同一份技能重新导入仍是同一条目）；
- 调用处去掉硬编码的 `"custom_zip"`。

### 三、太墟 v1.8.0 的整体升级：核过了，收益为零

把塔菲集成的太墟代码（v1.2.1）与上游 v1.8.0 做了三方比对：

- 两个版本之间 **196 个提交**，`v1.3.0 → v1.8.0` 六个大版本；
- 变更集中在 **chat UI / settings / a2ui / plugins / website**；
- 塔菲与 v1.2.1 的共同路径有 **2320 个文件**，而这些**几乎全是塔菲不使用的层** ——
  塔菲的界面是自己的（`com.soreverse.mcp` 下 1004 个文件），AI 对话走自研的
  `RikkaAgentEngine`，不经过太墟的 chat / harness 会话编排。

所以"整体替换到 v1.8.0"除了引入编译风险，拿不到对应功能。逐条核过之后真正有用的只有
三条：webchat 流式下载（v1.5.29 已抄）、图片请求体预算、技能导入 id 冲突（本条）。
前两条对塔菲没有调用方，第二条适用，已修。`AgentSkillEntity` 的目录发现重构
（按主入口停止下钻）需要改造数据库同步逻辑，而塔菲的技能功能没有入口，暂不动。

SOMCP 侧同样核过：v1.0.23 之后 App 代码层只有 Blutter 退出码表一条（v1.5.29 已抄），
其余全是 CI 与依赖版本。

### 四、仍然存在的限制

`libdsmcp_native.so` 依然不在基线之外：它的 `.so` 还是 0x1000 对齐，16 KiB 页设备上
加载失败 —— 但现在失败只会影响**两个写操作**（改 IL / 改用户字符串），只读能力由
Kotlin 实现接管。门禁会继续提示它，但不再是"整块功能不可用"。

## 塔菲逆核 v1.5.29 —— 把 16 KiB 的遗留收尾，并抄了上游三处

### 一、`libtermux.so` 真正重编了（16 KiB 页对齐）

上一版把它记进基线，理由是「没有源码」。其实源码一直在 `termux-app` 仓库里 ——
现在收进 `termux-terminal-emulator/src/main/cpp/termux.c`，配一个手动 workflow
（`build-termux-native.yml`）用 NDK 重新编译：

- 两个 `PT_LOAD` 的 `p_align` 从 `0x1000` 变成 `0x4000`；
- 更关键的是**段不再共享 16 KiB 页**。旧版 R 段 `[0,0x1aa8)` 与 RW 段 `[0x1cd0,…)`
  挤在同一个 4 KiB 页里，按 16 KiB 映射时一页只能有一种保护属性，后映射的会覆盖前一个；
  新版由链接器按 16K 边界排开（`0x0` 与 `0x5950`），互不重叠；
- 5 个 JNI 符号齐全（`createSubprocess` / `setPtyWindowSize` / `setPtyUTF8Mode` /
  `waitFor` / `close`），产物回填 `jniLibs`，`termux-native.properties` 的 sha256 同步更新；
- `elf-align-baseline.json` 里删掉这一条（基线只允许收缩），现在只剩 `libdsmcp_native.so`。

`libdsmcp_native.so` 仍然做不了：它在 SOMCP 上游也没有源码 —— 上游 `cpp/` 里只有
`rz_native` / `blutter_bridge` / `xanso_native` 三个 target，这个库是更早的预编译产物。
所以改成**让它不再骗人**（见下一条）。

### 二、PE/.NET 工具不再静默返回空

`libdsmcp_native.so` 加载失败时，`BinaryEngine` 的各方法会安静地返回 `unknown` / 空数组，
用户看到的是「这个 dll 不是 .NET 程序集」，而不是「解析器根本不可用」。
现在 `BinaryEngineToolHandler` 先查 `nativeBridge().available()`，不可用就直接报错、
带上 `loadStatus()`，并点明 16 KiB 页设备这个最常见成因。

### 三、抄上游

**SOMCP：Blutter 退出码不再全部塌成一个 `RUNNER_FAILED`**
隔离进程对所有非零退出只报一个粗粒度的 `RUNNER_FAILED`，于是「15 MB `libapp.so` 在隔离进程里
把 native 堆撑爆」「被取消」「写结果失败」在界面上完全一样，用户只能看到一个光秃秃的
`exited with code 6`。补了退出码表（3 → `RUNNER_CANCELLED`、5 → `RUNNER_OUTPUT_FAILED`、
6 → `RUNNER_NATIVE_FAILED` 并说明 tight-heap 与快照指纹的排查方向），并让它在服务侧
兜底码之上生效。

**太墟：工作区下载改流式，避免整份读进内存**
`/api/workspace/download` 原来是 `file.readBytes()`，而工作区产物上限 128 MB 且没有下载闸门 ——
手机上点一次「下载」就可能 OOM。改成 `sendFileResponse`（64 KiB 定长缓冲流式回写 socket）。

**SOMCP：CA 黑名单** —— 核对过，25 条指纹与上游完全一致，无需移植。

### 四、archive 的临时文件名唯一化

`taffy_archive_add` / `delete` / `rename` 都不是 heavy 工具，两个请求可以并发操作同一个包。
临时文件名固定成 `<file>.tmp` 时，两侧会在同一个文件里交错写入，最后
`replaceAtomically` 把一个字节混着两份的包换成正式文件 —— zip 直接损坏。
改成带 `nanoTime` 的唯一名。

### 看过但没抄的

- 太墟 v1.8.0（塔菲集成的是 v1.2.1）的 harness 改动：`ApiMessageProjector` 的图片 8 MB 预算、
  `ManualCompactionBoundary` 的按轮次压缩 —— 塔菲 App 里的 AI 对话走自研的 `RikkaAgentEngine`，
  不经过 harness 的 projector，移植过来没有调用方。
- SOMCP 这半个多月的提交绝大部分在 CI 与依赖：fastjson → 1.2.84、jsoup → 1.23.2、
  jna 的 artifact 选择、unidbg patched jar 去重。塔菲的依赖树不同，不适用。
- 太墟整体升到 v1.8.0 不现实（跨 6 个大版本，chat / settings / a2ui / plugins 全面重构）。

### 已知仍未修

`libdsmcp_native.so` 的 16 KiB 对齐：没有源码，只能等上游重新发布，或者自己重写 .NET
metadata 解析（ECMA-335 表流 + IL 反汇编，约 1500 行，且沙箱里没有 .NET 样本可以验证 ——
盲写一个二进制解析器的风险远高于收益）。`tools/check-elf-align.py` 会持续提示。

## 塔菲逆核 v1.5.28 —— 16 KiB 页对齐的实测与门禁 + 畸形包能把工具打断

上一版把「16 KiB 页兼容」列为已知风险没做，这次做了：先做**实测**，再决定怎么处置。

### 一、21 个 .so 的 p_align 实测：19 个达标，2 个不达标

Android 15 起有 16 KiB page size 的设备，bionic 要求 APK 里每个 `.so` 的每个 `PT_LOAD`
`p_align >= 16384`，否则 `dlopen` 直接拒绝（表现是功能静默不可用，不崩、不报错）。

挨个读了 arm64-v8a 下全部 native 库的 program header：

- **达标**：`libcloudflared.so` / `libjnidispatch.so`（0x10000），`librz_native.so` /
  `libc++_shared.so` / `libcapstone.so` / `libkeystone.so` / `libunicorn.so` /
  `libdemumble.so` / `libxanso_native.so` / `libdisassembler.so` / `libblutter_*.so` /
  `libfrida_server.so` / taixu 的 `libproot*.so` 等（0x4000，NDK r30 默认）。
- **不达标**：`libdsmcp_native.so`（.NET/PE 分析工具）、`libtermux.so`（终端 JNI），都是 0x1000。

两个不达标的库**本仓库没有源码**（前者是上游 SOMCP 的预编译产物，后者来自
`termux-app` 0.118.0 的 AAR），没法用 NDK 重新链接。

顺带把「手工改 p_align 就行」这条路堵死了：拿真实 .so 做重排实验后验证，
老链接器按 4 KiB 排段（R 段 `[0,0x22b0)`、R+X 段从 `0x3000` 开始），
两段会落在**同一个 16 KiB 页**里 —— 一页只能有一个保护属性，后映射的覆盖前一个。
所以只改头部字段必然出问题，必须重新链接。这条判据已写进工具。

### 二、新增 16 KiB 对齐门禁

`tools/check-elf-align.py`：遍历 `jniLibs/*/` 下所有 `.so`，检查 `p_align`、
`p_offset % p_align == p_vaddr % p_align`，并统计「被两个以上 `PT_LOAD` 共同覆盖的
16 KiB 页」（后者就是必须重链接的信号）。接在架构门禁之后跑：

- 新引入的不达标 → **阻塞构建**；
- 存量两个记在 `tools/elf-align-baseline.json`，只允许收缩（修好一个删一条）。

另附 `docs/16k-page-alignment.md` 记录实测表、影响面与正确的修复路径。

### 三、畸形 APK 能把签名分析整条打断

`apk_sig_verify` / `apk_cert_info` / `apk_zip_anomaly` 对**任意选中的 APK** 都会跑，
而签名块里的每个长度字段都来自文件内容：

- `parseSigner` 里 `sigsLen` / `dLen` / `cLen` / `pkLen` 用 `u32().toInt()` 取，
  值 ≥ 0x80000000 时变**负数** → `copyOfRange(s+4, s+4+负数)` 的 `from > to`
  → `IllegalArgumentException`。脱壳工具改坏签名块的样本非常常见，正好命中；
- `u64()` 没有边界检查；`sizeFooter` 直接 `.toInt()` 参与相减，截断后 `blockStart`
  可能落在文件内但语义完全错位，也可能越过文件尾。

全部加了范围判定。`ApkSigToolsTest` 补了 3 条畸形输入用例（其中一条在旧实现里
必然抛异常，是真正的回归测试）。

### 四、畸形的 `sh_size` 也能崩两个扫描器

`AntiDebugScanner` 与 `PackerFingerprint` 算节内容长度时写的是
`minOf(data.size - start, sec.size.toInt())` —— `sh_size` 是 `Long`，
大于 `Int.MAX_VALUE` 的畸形值转 `Int` 变负数，`start + len < start`，
`copyOfRange` 直接抛。改成用 `Long` 参与 `minOf` 再转 `Int`。

### 五、MCP 服务：启动失败会漏一套线程池，停止后不能重启

- `start()` 走端口探测失败的分支时只停了 ktor engine，**新建的工具线程池与协程作用域没回收**。
  「改端口 → 启动 → 发现被占用 → 再改」循环几轮就能在进程里堆出好几套 8 线程的池子。
  抽出 `releaseResources()`，`stop()` 与启动失败路径共用。
- `toolExecutor.shutdownNow()` 与 `scopeJob.cancel()` **都是不可逆的**。同一实例
  `stop()` 之后再 `start()`（改端口时很自然的使用路径）会照常起监听，但所有工具调用
  都被 `RejectedExecutionException` 拒掉，上层还把它报成「tool executor saturated」——
  完全误导。两个资源都改成可重建，`start()` 时确保它们在活的状态上。

### 六、导出产物改走统一的原子写

`EngineRuntimeBuild.writeAtomic` 自己实现了一份，有两个洞：临时文件名固定成
`.<name>.tmp`（两个请求并发导出同一 `outputName` 时，两侧同时打开同一个临时文件写，
rename 出来的是两份字节交错的 `.so`）；兜底分支直接 `target.writeBytes()`，先截断再写、
非原子 —— 正好是它自己注释里说要避免的「0 字节产物」。改成复用 `core/AtomicWrite`
（临时名带 nanoTime 后缀、`ATOMIC_MOVE`、失败兜底是流式 copy）。

### 已知未修

- `libdsmcp_native.so` / `libtermux.so` 的 16 KiB 对齐：需要拿到源码用 NDK 重编
  （`libtermux.so` 的源码是 `termux-app` 的 `terminal-emulator/src/main/jni/termux.c`）。
  在 16 KiB 设备上这两个功能会静默不可用，门禁会持续提示。
- `ArchiveTools` 的 `add / delete / rename` 仍用固定名 `<file>.tmp`。那里每处都有
  `replaceAtomically` + 失败清理，固定名是「下次覆盖」式的自清理，改成唯一名反而会
  制造残留，暂不动；但两个操作并发打同一个 zip 会丢更新。

## 塔菲逆核 v1.5.27 —— 新系统上的硬坑：前台服务类型、16 KiB 页、Java 9 API

这一轮把「新 Android 版本会静默打死我们的地方」逐个查了一遍。三个问题都会让功能在某些机型上**整个不可用**，但都不会崩、不会报错，最难查。

### 一、前台服务类型写错，常驻服务在 Android 15 上活不过 6 小时

`McpForegroundService` 和 `BoreTunnelService` 都声明了 `foregroundServiceType="dataSync"`。Android 15 对 `dataSync` 类型有两条硬规则：

1. **24 小时内累计 6 小时上限**，到点系统回调 `onTimeout()`，本代码在里面 `stopSelf()` —— 也就是「MCP 服务挂一整天」这件事在新系统上不可能，隧道和服务器会被硬停；
2. **禁止从 `BOOT_COMPLETED` 拉起 `dataSync` 前台服务**，而 `BootReceiver` 开机自启走的正是这条路 → `startForeground` 抛异常 → catch 吞掉 → `stopSelf`，用户只看到「开机自启没用」。

两者都改成 `specialUse`（并补 `FOREGROUND_SERVICE_SPECIAL_USE` 权限与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 子类型声明）。`specialUse` 不受 6 小时限制，也不在 BOOT_COMPLETED 的禁止列表里——它本来就是给「本机服务型常驻」用的。

### 二、重打包的 native 库只按 4 KiB 对齐，16 KiB 页的设备装不上

`ZipRepack` 给 `lib/*.so` 强制 STORED + **4096** 字节对齐，这是老的 `zipalign -p 4` 语义。Android 15 起有 16 KiB page size 的设备（部分 Pixel / 新骁龙），系统按 **16 KiB** 粒度 mmap APK 里的 .so —— 条目起始偏移不是 16384 的倍数时直接加载失败。

`taffy_apk_rebuild` / `taffy_apk_replace_native` / `taffy_so_inject_code` 的产物都走这条路径，所以「重打包完装上去，一打开就崩/找不到库」在 16 KiB 设备上是必然的。
改成 16384（`zipalign -P 16` 语义），同时满足 4 KiB 设备，代价只是每个 .so 最多多 12 KiB 填充。

### 三、`InputStream.readNBytes` 是 Java 9 API，Android 33 才有

四处直调（`taffy_file_read` 的 base64 与 hex 模式、`taffy_logcat` 的无特权兜底分支、编辑快照的文本判定、ARSC 提取用的 `readAllBytes`）。在 Android 10~12 上抛 `NoSuchMethodError`——这是 `LinkageError`，`catch (Exception)` 抓不住，所以：

- `taffy_file_read mode=base64/hex` 完全不可用（读 so、图片、任意二进制都走这里）；
- logcat 兜底分支抛错后会**跳过后面的 `waitFor`/`destroy`**，把 logcat 子进程永久遗留；
- 编辑快照的文本判定崩掉（`taffy_edit_snapshot` 全链路）。

仓库里其实早有 `core/StreamCompat.readCapped` 就是为了这个坑写的，`NativeExec`/`PythonRuntime`/`ShizukuUserService` 都已改过，这四处是漏改。全部换掉，并且把 logcat 那处放进「异常也 destroy」的结构。

### 四、三处会话子进程离开页面不回收

设置→终端、设置→Python、编辑器里的 REPL，`sessionProc` 都只存在 `remember` 里，**没有任何 `DisposableEffect`**。组件离开 composition 时唯一的持有者被丢弃，`destroy()` 永远没人调：

- 终端页的 `su -c sh -i`（root/Shizuku 通道）会常驻到 App 进程被杀，反复进出还会累积多个；
- `python3 -i` 同理，每次进出多一个。

三处统一加 `DisposableEffect { onDispose { stopSession() } }`。

### 五、logcat 特权流两处丢弃分支

`LogcatViewerPage` 绑定 Shizuku/Root 特权流最长要等约 2 秒，这段窗口里如果用户点了「停止」或者已经降级为轮询，代码直接 `return`/走 else 分支——**那个特权 `logcat` 进程没人接管、也从来没被 destroy**。用户以为已经停了，实际一条特权 logcat（外加一个 root/Shizuku shell）在后台持续吃 CPU。两处都补上 `priv.destroy()`。

### 六、`smali_edit` / `dex_analysis` 的 4 处 fd 泄漏

`zf.getInputStream(dex).use { it.copyTo(tempDex.outputStream()) }` —— 源流关了，目标流没关。紧接着 `tempDex.delete()` 是在 fd 仍打开时 unlink，空间要等 GC finalize 才回来；批量处理多个 dex 能把进程 fd 耗到 "Too many open files"（那时连 `ZipFile` 都打不开）。四处统一改成两层 `use`。

### 七、首页重写引入的三个回归（上一版我自己的锅）

- **「模拟执行」分类键写成 `unidbg`，而 `categoryMap` 里的键是 `emulate`** → 未知键在这个文件里的语义是「全部」，于是点「模拟执行」打开的是上百个工具的全量列表；
- **端点与工作目录被写成 `if/else`** → 引擎一般是常开的，运行时这行显示端点，工作目录就整行消失，想换目录必须先停服务（会断掉已连上的客户端）。改成两行都渲染；
- **只展示 loopback 端点** → 手机开服务、AI 客户端在电脑上时，复制到 `127.0.0.1` 根本连不上。局域网那行加回来；同时端点计算从「组合期主线程」挪到 IO 线程（`NetworkInterface.getNetworkInterfaces()` 是实打实的 syscall）。

### 八、日志页两处

- 「复制 / 导出」用的是节流后的快照（最多落后 200ms），日志刷屏时点复制会少最后几行 → 改成从 `AppLog.snapshot()` 现取；
- 重新进入页面只回填 120 行，而运行期上限是 `settings.logMaxLines` → 「刷满、切走、切回」历史会瞬间缩水成 120 行，看起来像日志被吞了。两边统一。

### 九、分析页顶栏 56 → 64dp

其它页面的 `TopAppBar` 是 64dp，分析页自绘顶栏是 56dp，两套高度在页面切换时会「跳一下」。统一到 64dp（视觉参数已与 TopAppBar 一致，但组件本体仍是自绘 Row —— 那个文件有架构行数基线，换容器要连带调整 25 处缩进，留到下一轮）。

### 未做 / 已知风险

- **第三方预编译二进制**（`libcloudflared.so`、`libfrida_server.so`、`libblutter_*.so`）是别人编译的，如果它们的 `PT_LOAD p_align` 是 4096，在 16 KiB 页设备上同样加载不了。我们自己编译的 `librz_native.so` 等用 NDK r30（默认 16 KiB 对齐），不受影响。这几个二进制要等上游或重新交叉编译，本轮无法在沙箱内验证。
- `McpHttpServer.start()` 若抛异常，新建的实例（含线程池）没有 `stop()`；实际路径里 engine 已回滚，泄漏面主要是 executor，留待单独处理。

## 塔菲逆核 v1.5.26 —— UI 真的换组件了（不只是换颜色）

上一版我把「统一成 Exbin」做成了换配色：色值换对了（`#0F1F15` / `#2A3A2E` / `#66D39A`），但页面还是原来那套堆叠——2 列彩色大卡、圆底彩图标、彩色分组标题、每页各写各的字号。这次动的是**版式与组件**。

### 一、首页整页重写

旧首页是「一堆同权重的圆角块」：MCP 状态卡 → 工作台 2×4 大卡（每格 56dp，副标题经常被截成「符号 / CFG / 交叉...」）→ 服务 2×2 格子（值折行）→ 分析台横条。信息没有主次，第一眼不知道看哪里。

新结构（从上到下，只有 4 个层次）：

1. **顶栏**只放标题 + 设置图标。设置是全局目的地，塞进功能网格里会和「SO 分析」抢位置；
2. **一张状态卡**：状态点 + 一行标题 + 一行说明 + 一个实心按钮；运行中时下半行直接是 MCP 地址（点按复制），停止时是工作目录（点按选择）—— 原来这是两个平级按钮加一行小字，读起来要猜哪个是主操作；
3. **工作台改成 4 列图标网格**：40dp 圆角图标底 + 11sp 单行标签，8 个入口一屏放下，全部中性色。原来 2 列大卡给每个入口配一个主题色（primary/tertiary/secondary 轮转），8 个色块铺满一屏就是调色板；
4. **服务改成卡内 4 行列表**：44dp 行高、行间 1dp 分隔线、右侧状态文字（在线用主色、离线用次要色）。原来 2×2 格子只有半屏宽，值一律折成两行；
5. 底部**只有一个主 CTA**（primaryContainer 的「进入分析台」），是整页唯一的大色块。

### 二、顶栏全站统一

- **设置 40 多个子页的顶栏**是 20sp + 半粗的大标题，这是太墟的「大标题」语言，跟 Exbin 的 MaterialToolbar（TitleMedium 16sp、**常规字重**）不是一回事。`SettingsScaffold` 一处改动，全站设置页统一到 16sp 常规；
- 分析页顶栏标题同样从 14sp 半粗改成 16sp 常规，刷新图标从主色改成次要色（主色只留给「选中」和主按钮）；
- **修掉了页面顶部那截多出来的空白**：外层 `MainActivity` 的 Scaffold 已经把状态栏高度作为 padding 给了内容区，而页面里的 `TopAppBar` / 内层 `Scaffold` 默认还会各自再算一次系统栏 inset —— 两层叠加，每页顶部都多一截，而且不同页面高度还不一样。`ScreenHeader` / `ToolPageScaffold` / `SettingsScaffold` / 首页顶栏统一置零。

### 三、分组标题不再用主色

设置页 `SettingsSection`、`GlassGroup`、首页分节标题，原来是「主色 + 半粗 + 12sp」。一页出现六七个分组就是彩色标签墙，还跟右侧的状态值抢注意力。统一成 **11sp / Medium / onSurfaceVariant / 0.8sp 字距** —— Exbin 的分组标题一律中性色，主色只出现在动作和状态上。

### 四、卡片统一

- 设置页组卡圆角 20dp → **12dp**（和 Exbin 的 `item_detail` 一致；20dp 是太墟语言，一个 App 里不该有两套圆角）；
- 页面级卡片底色 `surfaceContainerHigh` → **`surfaceVariant`**，与背景拉开一档；
- 任务卡、计数胶囊去掉 1dp 描边（Exbin 的卡片没有描边，靠底色分层）；**卡内**的小块（类型图标块、元信息）改成 `surface`（比卡片更暗一档，形成凹陷），而不是叠 16% / 75% 透明度；
- 任务状态胶囊从「主色 14% 透明」改成 `primaryContainer` / `onPrimaryContainer` 语义色；
- `CardRow` 的水波纹现在贴着 8dp 圆角走（原来是一个矩形高亮压在圆角行上），元信息文字从主色改成中性色。

### 五、字号收敛到 6 档

全站原来散着 `9 / 9.5 / 10 / 10.5 / 11 / 11.5 / 12 / 12.5 / 13 / 13.5 / 14 / 16 / 20 / 21sp` 十四种值，一页里混排三个近似档位，视觉上就是「乱」。统一收敛到 Exbin 的 M3 textAppearance 六档：**11 / 12 / 14 / 14 / 16 / 22**（`AppText.label / body / bodyStrong / title / headline / display`），共改动 120 处、17 个文件。走 `AppText` 之后，设置里的「字号」开关也终于对所有页面生效（原来只影响 typography，显式写 `fontSize = 12.sp` 的文本完全不动）。

### 六、底部导航选中态

指示器从「主色 16% 透明」改成 M3 规范：`secondaryContainer` 底 + `onSecondaryContainer` 图标，选中文字用 `onSurface`。原来深色主题下指示器和背景糊成一片，而「选中 = 主色文字」在 5 个 tab 里过于抢眼。

### 七、上一轮「已知但未动」的 4 项全部做掉

- **分析页改常驻、AI 深聊作为覆盖层**：原来是 `if/else` 互斥挂载，进出一次深聊会把 `AnalysisWorkspace` 整棵子树销毁重建，选中的函数、当前子视图、搜索词、滚动位置全丢；
- **日志页重组限流 200ms**：原来每来一行日志就触发一次全表过滤 + 逐行本地化（全在主线程），日志密集时明显掉帧。写入仍然实时，只是重组被合并成 5 次/秒；
- **抓包列表过滤加 `remember`**：每来一条记录就整体重新过滤上千条目，现在只在条目或筛选条件变化时重算；
- **隧道日志改单协程**：原来是「自增 key 的 `LaunchedEffect` + `while(true)`」，每秒销毁重建一次协程，而且是**在组合期（主线程）同步读日志文件**。改成单个协程 + `Dispatchers.IO` 读取。

### 说明

- 配色沿用 v1.5.24 引入的 Exbin token（深色 `#0F1F15` / `#2A3A2E` / `#66D39A`，浅色 `#FEFBFF` / `#E0E2EC` / `#1A6EF0`），这轮没有改色值 —— 改的是用在哪、用多大、用什么形态；
- 分析页顶栏仍是自绘 `Row`（56dp、16sp、左右 4dp），视觉参数已与 `TopAppBar` 对齐，但没有换成 `TopAppBar` 组件本身：那个文件有架构行数基线，换容器要连带调整 25 处缩进，留到下一轮单独做；
- 首页 4 列网格是按手机竖屏（可用宽约 360dp）算的：每格约 78dp，40dp 图标 + 11sp 单行标签放得下；窄屏或超大字号下标签会省略号，这是刻意的（副标题已经收进「全部工具」页）。

## 塔菲逆核 v1.5.25 —— 继续排查：14 个真缺陷（含一个 Android 12+ 必崩）

这一轮把全仓 311 个自有 .kt + 55 个 .java + 原生/构建配置拉下来做规则扫描，再分「工具与引擎层」「UI 层」两路逐文件深读，只保留能指出具体行号、具体触发路径、具体后果的条目。下面每一条都回读过源码确认，误报已丢弃。

### 一、会在 Android 12+ 直接崩的

**无 root 安装 / 卸载 APK 100% 失败。** `PendingIntent.getBroadcast(...)` 传的是 `flags = 0` —— targetSdk 36 下，Android 12 起创建 PendingIntent 必须显式声明可变性，否则构造时就抛 `IllegalArgumentException`。报错跟安装本身毫无关系，所以表现成「点了安装，什么都没发生」。
这里也只能用 `FLAG_MUTABLE`：系统要往这个 PendingIntent 里回填安装状态 extras，用 IMMUTABLE 会把系统填充的内容丢掉，`SandboxInstallReceiver` 收不到结果。

### 二、会静默改错的

**`taffy_apk_manifest_edit` 三个动作「说改了，其实没改」。** `add_component` / `remove_component` / `set_exported` 都没做「改动前后是否一致」的判断，没命中照样写回并返回成功。其中 `remove_component` 的正则只匹配自闭合的 `<activity ... />`，而真实 App 里组件绝大多数写成 `<activity ...> <intent-filter/> </activity>` —— 于是「删组件」永远删不掉，用户拿着这个包去签名安装，还以为已经删了。现在两个写法都支持，且三处统一在无变化时返回 `NOT_FOUND` / `NO_CHANGE`。

**`set_exported` 会把字面 `$1` 写进属性名。** 旧代码用 `replace(xml, "\\$1android:exported=...")` —— Kotlin 里 `\$` 是美元符转义，正则 Replacement 的 `\$` 又表示字面 `$`，两层叠加后插入的是文本 `$1`，产出 `$1android:exported="false"` 这种非法属性名，aapt 直接编译失败。改成用 lambda 拼字符串 / 手工拼接，零转义歧义。

**`taffy_smali_batch(rebuild)` 第二次会把原始包覆盖掉。** 备份写作 `copyTo(backup, overwrite = true)`，而这个备份正是「回退用」和「保留 v2/v3 签名块」的来源。apkPath 是就地改写目标，第二次 rebuild 时它已经是改过的产物 —— 备份被污染，用户回退拿到半改包；开着 `apkKeepV2V3WhenNoSign` 时还会把污染包当原始签名块来源，产物必然验签失败。改成只在备份不存在时创建。

**`taffy_apk_inject_so` 的 `abi` 参数可路径穿越。** schema 里写了 oneOf 枚举，但服务端只取值不校验，传 `abi="../../xxx"` 就能把 SO 写到 decode 目录之外；而 `abi` 不是 `WorkspacePolicy` 登记的路径键（只有 `dir` / `so` 是），等于整体绕过工作区白名单。现在白名单校验 + 规范化路径必须落在 decode 目录内，双保险。

**快照 id 可以跨目录读。** `snapshotId` 直接拼进 `edit-snapshots/<tool>/<id>`，传 `../` 就能读到别处的 `meta.json`。现在只允许 `[A-Za-z0-9._-]{1,80}`。

### 三、会 OOM / 卡死的

**`taffy_dex_rename` 的大包内存峰值是 dex 总量的 2 倍。** 上一版声称改成了「逐个读 → 改 → 存结果 → 丢原始」，但实际代码是 `apkDexEntries(file)` 先把所有 `classesN.dex` 一次性读进 `List<Pair<String, ByteArray>>`，再另存全部重写结果 —— 注释和实现相反。真实的大包（10 个 dex）峰值能到几百 MB，被 LMK 杀掉时连带整个进程里的工作区、编辑会话、Frida 会话一起没了。现在改成 `Sequence` 惰性逐条读，峰值降到「单个 dex + 结果集」。

**打包 tar 会把整个文件读进堆。** 同一个函数的 ZIP 分支是流式的，只有 tar 分支 `f.readBytes()` —— 而打包对象通常是塞满 SO/APK 的工作目录。改成 `header.size = f.length()` + `inputStream().copyTo()` 流式写。

**打开工作区大文本的上限是 64 MiB，比 SAF 路径宽 8 倍。** 同一页对系统选择器已经限死 8 MiB 并写明「几十 MB 的文本塞进 BasicTextField 是整个界面卡死」，工作区文件树却放宽到 64 MiB 且直接 `TextFieldValue(text)`。统一到 8 MiB，超限给出明确提示。

**Python 页加载脚本完全没有上限**，`readBytes().decodeToString()` 选到 200 MB 的包就是 OOM。改成先查 `openAssetFileDescriptor().length` 再决定。

**`taffy_archive_extract` 遇到负 size 直接抛 `NegativeArraySizeException`。** tar 的 base-256 编码 size 会被 `readOctalLong` 解析成负数，`ByteArray(负数)` 把整次解压中断在半成品状态。现在明确报「size 非法(负数)」。

### 四、会丢状态 / 卡住界面的

**编辑器的「从扩展页点编辑」从第二次起失效。** 编辑器页是常驻挂载的（用 offset 移出屏幕保活、不销毁），它的 `LaunchedEffect(Unit)` 只跑一次；而 `EditorBridge.pendingPath` 是普通 `var`，改了既不触发重组也没有第二次消费机会。现在改成 Compose 状态 + 以路径本身为 key。顺带修掉了「下次启动突然打开上次那个文件」。

**logcat 崩溃列表在缓冲满 2000 行后永久冻结。** `remember(logs.size)` 在封顶后 size 恒定 → 永不失效，之后真崩了也看不到。同一文件里 `visible` 早就因为这个原因改成带首尾元素做 key 了，这里漏改。

**分析页「编辑中心」的输入一切视图就归零。** `tab` / `symLocator` / `strAddr` / `strNew` / `strOld` / `strPad` / `insAddr` / `insAsm` / `insOld` / `insThumb` 全是 `remember`，而 `EditCenterView` 挂在 `when (view)` 分支里 —— 打开侧栏看一眼函数列表再回来，抄好的地址和长串 hex 全没了。输入类状态改成 `rememberSaveable`。

**`taffy_logcat_capture(start)` 的竞态会永久泄漏一个 logcat 进程。** 「captureProcess 为空吗」与 `ProcessBuilder.start()` 之间有窗口，UI 按钮和 MCP 请求同时 start 会起两个，字段只记住后一个，前一个永远没人 destroy（持续吃 CPU、往已删的文件里写）。start / stop 串行化，`destroy()` 后补 `waitFor` 回收僵尸。

**导出 logcat 在主线程打包。** 2000 行 `joinToString` + zip 压缩 + 磁盘写全在主线程，现在下沉到 IO，只有分享动作回主线程。

**`taffy_script` 结果截断会切在汉字中间。** 按字节截断后直接解码，最后一个汉字变成 U+FFFD。改成回退到 UTF-8 首字节边界。

**`taffy_smali_batch(init)` 每个 dex 漏一个 fd。** `copyTo(tempDex.outputStream())` 的目标流没有 `use`，接着 `tempDex.delete()` 在该 fd 仍打开时 unlink —— 空间要等 GC finalize 才回来，批量 init 能把 fd 耗到 "Too many open files"。目标流补 `use`。

### 五、查过但确认不是问题的（省得下次重复怀疑）

`UnidbgEmulator` 的 `!!`（只在 `!isSuccess` 分支）、`Storage.readSource` 的 `runBlocking`（同步 API 内部并行）、`WorkspacePolicy` 的路径校验、`ZipRepack` 的 zipalign 语义、`EngineProvider` 的双检锁、`EngineRuntime` 的 ConcurrentHashMap、`ArchiveTools` 的 Zip Slip / Tar Slip 校验 —— 都已确认正确。

### 六、已知但本轮未动

- `MainActivity` 里 AI 深聊页与分析页是互斥挂载，进出深聊会让分析工作区整体重建（选中的函数、滚动位置丢失）。要修得把分析页也做成常驻 + 覆盖层，属于结构改动，留到下一轮。
- `LogsTab` 每来一行日志就对全表重做过滤 + 逐行本地化（日志密集时掉帧）、`CapturePage` 的抓包条目列表未 `remember`、`SettingsTunnelPage` 用「自增 key 的 LaunchedEffect + while(true)」做轮询（每秒销毁重建协程）。这三处是性能问题不是功能缺陷，本轮未改。

## 塔菲逆核 v1.5.24 —— 留白清零 · 全站 UI 对齐 Exbin

### 一、三项留白做完

**1）重命名的字符串常量级联（`renameStrings`）**

上一版留的尾巴是「不处理字符串常量里的反射类名」。现在补上了：`const-string` / `const-string/jumbo` 里与旧类名**完全相等**的两种写法（`Lcom/foo/Bar;` 与 `com.foo.Bar`）会一起改掉。

刻意不做子串替换——`"I love com.foo.Bar"` 这种日志文案不该被动。沙箱端到端验证：

```
BEFORE  "Lc/D;"   "c.D"   "prefix Lc/D; suffix"
AFTER   "Lx/Y;"   "x.Y"   "prefix Lc/D; suffix"   ← 第三项不动
```

**2）DEX 浏览器加了重命名表单**

类详情页新增「重命名此类」入口：点开输入新类名（点分），确认后原子写回并留 `.bak.apk`，完成后自动重载类树。这是本页唯一的写操作，所以单独做了二次确认 + 处理中禁用。

**3）`frida_selftest` 一站式自检**

之前只能靠「失败信息」反推，现在 `dynamic_analyze` 的 `op=frida_selftest` 会把 attach/spawn 之前的每一关单独跑一遍并计时：

```
connect   → TCP + D-Bus 连接
transport → D-Bus AUTH/HELLO
ping      → HostSession.Ping
negotiate → 17 / 16 / 无后缀 三种接口名逐个试，报告哪个通、能看到多少进程
spawn     → 可选：给一个包名就做真实 spawn 探测（随即 kill 回收，不留挂起进程）
```

哪一关卡住一目了然。这也间接把「spawn 真机验证」的留白填掉了——没有设备无法替你跑，但你现在可以在设备上一条命令跑完并拿到逐步结论。

### 二、全站 UI 统一到 Exbin

配色和基座组件都换成 Exbin（com.exbin.app）的精确值：

| 项 | 之前（iOS / 太墟风） | 现在（Exbin） |
|---|---|---|
| 浅色主色 | `#0A84FF` | `#1A6EF0`（`preset_blue_primary`） |
| 浅色底 / 卡 | `#F2F2F7` / `#F7F7FA` | `#FEFBFF` / **`#E0E2EC`（surfaceVariant）** |
| 深色主色 | `#0A84FF` | **`#66D39A`（Exbin 深色是绿）** |
| 深色底 / 卡 | `#0B0B0D` / `#2C2C2E` | `#0F1F15` / `#2A3A2E` |
| 卡片圆角 | 20dp | **12dp**（`item_detail` 10–12dp） |
| 卡片描边 | 1dp | **无**（Exbin stroke 0 / elevation 0） |
| 顶栏标题 | 24sp 大标题（太墟式） | **TitleMedium 16sp 常规字重**（MaterialToolbar 同款） |
| 列表行标题 | BodyLarge 16sp | **TitleSmall 14sp** |
| 字号档位 | 11/12/13/15/18/22 | 11/12/**14**/**14**/**16**/22（对齐 M3 textAppearance） |

表面看只是换色，实际改动最大的是 **surfaceContainer\* 全套重新给了 Exbin 色调的档位**：之前深色下 `outline` 与 `surfaceContainerHighest` 一字不差，1dp 描边等于不存在；现在 `outline #7E8E80` / `outlineVariant #3D4D40` 与任何容器都有可见色差。

首页也一起换了：卡片从 `surfaceContainerHigh` 改 `surfaceVariant`，分组标题从 `onSurfaceVariant` 改 `primary`（Exbin 的组标题就是主色），工作台磁贴 48→56dp。

### 三、继续排查：三个新问题

**1）`taffy_dex_rename` 在大 APK 上的内存峰值**
旧实现先把 APK 里所有 `classesN.dex` 读进内存，改完再把所有结果留着——峰值是 dex 总量的 **两倍**。10 个 dex × 30MB 就是 600MB，必 OOM。改成「读一个 → 改 → 存结果 → 丢原始」。

**2）重命名后没有提示签名失效**
改完 dex 的 APK 装不上（v1/v2 签名全废），但工具没告诉用户。现在返回体带 `signature` 字段，明确写「装到设备前必须用 `taffy_apk_sign` 重签」。

**3）`frida_selftest` 的阶段记错**
`connect` 之后的任何异常（transport / ping / 协商）都被 catch 记成「connect 失败」，排查方向直接跑偏。改成用 stage 变量记录真实阶段。

顺带修了一个编译期问题：`ListItem` 没有 `contentPadding` 参数（原本想把行内边距压到 14dp，最后放弃——为 2dp 差异自绘不值得）。

---

### 已知未做

- 导航架构仍是底部 `NavigationBar`（Exbin 用 80dp `NavigationRail` 侧栏）。手机上一只手够不到侧栏，这条按「样式统一但架构保留」处理；要连架构一起换请单说。
- 编辑器 / 日志流 / CFG 画布 / 终端面板保留各自的专业配色（Exbin 的代码区也是硬编码 VSCode 配色，本来就一致）。

---

## 塔菲逆核 v1.5.23 —— Dex Editor 整合收尾 · 三项留白清零

这一版把「Dex Editor 到底整合完整没有」这个问用源码逐个对了一遍，补掉唯一的实质缺口（dex 符号重命名），顺手把上一版列的三个留白全部清掉。

---

### 一、Dex Editor 源码对照：只差「重命名」这一族

这次拿到的是完整源码（`net.dexedit.*`，55 个 Java 文件），可以逐类对账，而不是拿反编译的 `.java.bak` 猜。

| Dex Editor | 塔菲 | 状态 |
|---|---|---|
| `DexlibParser`（类/方法/字段解析） | `taffy_dex_inspect` / `taffy_dex_index` | 早有 |
| `DexlibSmaliGenerator`（dex → smali） | `taffy_baksmali_decode` / `smali_edit action=extract` | 早有 |
| `SmaliAssembler`（smali → dex） | `taffy_smali_assemble` / `smali_edit action=replace` | 早有 |
| `DexStringReplacer`（改字符串常量） | `smali_edit action=replace_string`（preserve / recompile 双模式） | 早有 |
| `DexCodeReplacer`（替换方法代码） | `smali_edit action=patch_instruction` | 早有 |
| `JavaDecompiler`（jadx） | `taffy_jadx_decompile` | 早有 |
| `DexCache` / `DexEditSession` / `DexHistory` | `EditSnapshotService`（快照/diff/rollback）+ `.bak` | 等价 |
| `TreeAdapter` / `PackageTreeBuilder`（包树） | 本轮补：DEX 浏览器改包树 | **本轮** |
| `DexClassRenamer` / `DexMemberRenamer` / `DexParamRenamer` / `DexLocalRenamer` / `DexRenameApi` | 本轮补：`taffy_dex_rename` | **本轮** |

**为什么重命名是真实缺口**：塔菲其它 dex 工具要么是「读」（索引 / 交叉引用 / 反编译），要么是「改字节」（smali 替换 / 字符串替换）。少了「按符号重命名并级联所有引用」这一层——混淆前后对账、把 `a.a.a` 改成可读名、整包搬家，都做不了。

`taffy_dex_rename` 的六个动作与 Dex Editor 一一对应：

- `class` — 重命名单个类，**含内部类** `Outer$Inner`
- `package` — 整包前缀搬家（含子包）
- `field` / `method` — 重命名成员；方法按**返回类型 + 参数类型**精确匹配重载
- `param` / `local` — 改参数名与局部变量名（只动调试信息）

级联范围由 dexlib2 的 Rewriter 保证：字段类型、方法签名、指令、注解、枚举、数组全部跟着改。**不会**改字符串常量里的反射类名（这点与 Dex Editor 一致，工具描述里也写明）。

移植方式：5 个 Java 文件原样搬进 `engine/` 包，只换了 dexlib2 的包名（`org.jf.dexlib2` → `com.android.tools.smali.dexlib2`）。沙箱里做了端到端验证——用 smali 汇编出真 dex，跑三层重命名（类 → 字段 → 包），再用 `DexPool` 写回、重新读出来核引用：

```
BEFORE  La/B;  La/B;->f:Lc/D;  Lc/D;->run()V
AFTER   Lz/B;  Lz/B;->g:Lx/Y;  Lx/Y;->run()V      ← 无残留，类型/成员/包全部级联
```

同一套断言也进了单测（`DexRenameTest`），真的汇编、真的重写、真的读回，不用 mock。

写回 APK 走 `ZipRepack.copyAll`（保留条目压缩方式、resources.arsc 与 so 的对齐），一次重打包替换全部受影响的 `classesN.dex`，并留 `.bak.apk`。

### 二、留白清零

**1）`aux` 支持嵌套字典**
上一版嵌套对象是 json 序列化后当字符串带的，frida 侧拿到的是文本。现在按真实类型递归写 `a{sv}`（最深 4 层）。嵌套两层的字节形状用 GLib golden 钉死（`aux{cfg:{k:v}}` 82B、`aux{deep:{cfg:{k:v}}}` 106B、混合值类型 122B）。

**2）CVE 黑名单可扩展**
拉黑表以前只能随发版更新，而泄漏公告是按天走的。现在应用私有目录放一份 `tls-blocklist-extra.json` 即可补充，命中逻辑与内置表完全一致（仍按 SPKI 公钥哈希）：`{"spkiSha256":["AB..."]}` 或带说明的 `{"keys":[{"subjectCn":"...","spkiSha256Hex":"..."}]}`。非法条目丢弃，文件缺失/解析失败只是退化成内置表，不影响任何连接。

**3）spawn 失败按阶段给可操作提示**
`spawn` / `attach` / `script` / `resume` 的失败现象都是「连不上」，但排查方向完全不同。现在异常带上阶段前缀，并给出该阶段的针对性提示：

- `[spawn]` → frida-server 是否以 root 运行、包名是否真的装了、设备上有没有弹 root 授权框
- `[attach]` → spawn 出来的进程可能在注入前就自杀了（壳的完整性校验）
- `[script]` → 先 `frida_check` 校验语法，或用空脚本跑通链路
- `[resolve]` → attach 模式要求目标进程**已经在跑**

> 真机端到端仍然没做——手上没有设备。这一项能做的是「让失败自己说清楚」，剩下的必须你插上设备跑一次。

### 三、DEX 浏览器改成包树

原来是几万个类名平铺一列，只能靠搜索找。现在按包分组、可折叠，搜索时自动展开命中项（否则命中被折叠的包里就看不见了），标题显示包名 + 类数。

---

### 已知未做

- `taffy_dex_rename` 目前只有 MCP 工具入口，**没有专门的 UI 表单**（可由 AI / MCP 客户端直接调用）。要不要在 DEX 浏览器里加「重命名」按钮，取决于你实际使用频率。
- 重命名不处理字符串常量里的反射类名（与 Dex Editor 行为一致）。
- `param` / `local` 只影响调试信息，目标被 strip 过就无从改起。

---

## 塔菲逆核 v1.5.22 —— 内置 cloudflared 跟进 · 元数据额度 · 泄漏 CA 拉黑

这一版的一半内容来自「检查上游 + 检查内置二进制」时发现的真问题：一个让工具目录凭空消失，一个让服务假装在跑。

---

### 一、内置 cloudflared 2026.9.1 → 2026.10.6

塔菲内置的 cloudflared 停在 2026.9.1，最新是 2026.10.6（10-29 发布），跨 7 个版本、65 个提交。值得跟的几条：

- **Quick Tunnel 鉴权链路整体改造**（TUN-10800 / 10801 / 10803）：broker JWKS 缓存、assertion 校验、callback 授权、recipient policy。塔菲的隧道正是走 quick tunnel，这条直接影响隧道侧。
- `chore: Limit response size`、`Protect headers before flush`、`Avoid logging request body` —— 一批加固。
- **Go 升到 1.27.2 修 GO-2026-6600**，另有 `VULN-176088`（metrics server 的 debug 端点收敛）。
- `TUN-10994`: 修复「大于缓冲区末尾的消息被截断」，`TUN-10808`: 请求体关闭时取消 QUIC 读。

> 取法说明：GitHub release 的 37MB 二进制在这个环境里被限速截断（多次尝试只拿到几 MB），改走 **Cloudflare 官方 apt 仓库**取 arm64 deb（18MB），解包后 `sha256` 与仓库 `Packages` 元数据逐字一致，再校验 ELF 是 aarch64、静态链接、且二进制内含 `2026.10.6`。

**副作用提醒**：quick tunnel 的鉴权改了实现，理论上前台行为（`--url` 起隧道、返回 `*.trycloudflare.com`）不变，但这条链路没法在这里跑真机验证。更新后第一次起隧道时留意一下返回的地址是否还能访问。

### 二、真 bug：tools/list 会被「工具结果上限」砍掉

`toolResultMaxChars` 是给**工具结果**用的（用户调小它是为了别让一条文件内容撑爆客户端）。但传输层的落盘闸门对**所有**响应生效，包括 `tools/list`。

工具目录随工具数量线性增长，现在已是数百 KB 量级。一旦把结果上限调到 400k，`tools/list` 就会判超限 → 落盘 → 回一条 `content: [{text: "Response of N chars exceeded..."}]`。那不是合法的 tools/list 响应（**没有 tools 数组**），严格客户端直接当成「工具目录为空 / 探测失败」。

现在：

- 协议元数据（`tools/list` / `initialize` / `server/discover` / `resources/list` / `prompts/list` / `ping`）走**独立额度**（4M 字符），不再读 `toolResultMaxChars`；
- 万一真的超了，也**不换形状** —— `tools/list` 按条数裁剪，保留 `tools` 数组，并在 `_meta` 里写明 `truncated` / `returnedCount` / `totalCatalogCount` 和「用 `taffy_meta_info action=tools` 按名取」的提示；
- 工具结果（`tools/call`）仍按用户配的上限落盘，语义没变。

### 三、安全：拉黑 CVE-2026-111943 泄漏的中间 CA

eMudhra 2025-04-30 生成的一批中间 CA，其 P-384 私钥熵不足 256 位，2026-10-17 起可被还原。持有者能给任意主机名签证书，而设备默认信任链仍接受 —— 这条对塔菲是实打实的攻击面：quick tunnel 注册、隧道公网可达性探测（被劫持时会给出「可访问」的假绿灯）、GitHub 更新与 APK 校验、以及 AI 引擎的全部出网请求。

实现口径刻意保守：**包装平台 TrustManager**，先原样跑平台校验，再扫链拒绝 —— 只减不加信任，不改 hostname 校验、不裁剪锚点、不做 pinning，正常证书零影响。按**公钥**（SPKI SHA-256）匹配而不是按证书 DER，因为同一把密钥还有 AAA 根签发的交叉签名副本，只认证书会被绕过。

接入点：`ApkMcpBridge` / `BinaryManager` / `CloudflareTunnelManager` / `DeepAnalysisService`×3 / `DynamicAnalysisService` / `GitHubUpdateManager` / `PublicReachability` / `AgentKernel`，共 9 个出网 OkHttp 客户端。

单测用真实证书材料钉住（含 AAA 交叉签名副本与那份 875 字节的重编码版本），并断言「AAA 根自己不被误杀」。

### 四、Frida spawn：`HostSpawnOptions` 六个字段补齐

上一版只做了 argv / envp / cwd / stdio。这一版补上 **env**（追加环境变量，与 envp 的「替换」区分）和 **aux**（`a{sv}`，值支持字符串 / 整数 / 字符串数组）。

`aux` 是唯一的嵌套结构，它的 variant 值还要再套一层 framing，所以单独用 GLib 生成 4 个 case（字符串 / 整数 / 字符串数组 / 空字典）+ 一个六字段全发（210 字节）作为 golden，全部逐字节对账后钉进单测。

### 五、真 bug：服务在跑，但端口没人在听

`McpHttpServer.start()` 用的是 ktor 的 `start(wait = false)` —— bind 交到后台协程，**失败时这里收不到异常**。于是端口被占用（或绑定地址非法）时：前台服务照常进前台、通知写「MCP 运行中」、`isRunning()` 返回 true，而端口根本没监听。用户侧只看到「客户端连不上 / 连接被拒绝」，完全无从判断。

现在启动后显式探测一次（2s 内轮询），失败就回滚 engine 并抛出可读异常：

- 端口被占用 →「端口 8799 已被其他进程占用（绑定地址 0.0.0.0），MCP 服务未启动；换一个端口或关掉占用方后重试」
- 未就绪 →「MCP 服务未能在 0.0.0.0:8799 上开始监听（2s 内未就绪）」

服务侧接住这个异常后停掉自己，并留一条**可滑走**的失败通知（不是那种 ongoing 的、停服务后还赖着不走的），把原因直接写出来。

---

### 上游核对结论（这轮没抄的部分）

- **SOMCP v1.0.23 的 30 个只读审计工具**（T1 批次）：塔菲**已经全有**且版本更大 —— `ElfAudit` / `CryptoFingerprint` / `JniMangle` / `NativeProfile` / `ApkSigTools` / `PatchAudit` / `StringCensus` 七个模块都在，逐文件比 SOMCP 的对应文件还多约 10%。不需要搬。
- **SOMCP v1.0.23 的「最新版仍提示有更新」修复**：塔菲不中招。那个 bug 的成因是把发布时刻嵌进 APK 再与 release 的 `published_at` 比，而发布在构建之后 → `published_at` 恒大于基线。塔菲用的是「同 tag 被重发才二次提醒」，没有构建期时钟耦合。
- **bore**：最新 tag 仍是 v0.6.0（2025-06-09），main 上只多了一个服务端依赖的越界修复，客户端协议没变。塔菲的 Kotlin 客户端已对齐 v0.6.0 的半关闭转发。
- **太墟 v1.6.0 的「端口孤儿回收 / 常驻通知」**：对应能力已按上面的第五节落地；「网关服务按实例管理」在塔菲是单实例模型，不适用。

### 已知未做

- spawn 真机端到端、隧道更新后的真机连通性 — 都没有设备，只有字节级与编译级证据。
- `aux` 只支持一层（值是字符串 / 整数 / 字符串数组）；嵌套对象按字符串携带。
- TOFU 之外没有 pinning：CVE 拉黑只看公钥哈希，若上游日后又泄漏新的 CA，需要补表。

---

## 塔菲逆核 v1.5.21 —— spawn 的 options 编到位，GVariant 对齐修正

上一版留的「HostSpawnOptions 只传空字典」，这次做掉了。过程中揪出**一个会让 spawn 直接失败的真 bug**。

---

### 一、spawn 现在支持 argv / envp / cwd / stdio

```
op=frida_open  args=[{
  "mode": "spawn",
  "targetIdentifier": "com.target.app",
  "argv": ["com.target.app", "--flag"],
  "envp": ["PATH=/sbin"],
  "cwd": "/data/local/tmp",
  "stdio": 0
}]
```

以前只能传空 options（= 全默认）。现在这四个字段都能带，返回里会列出实际生效的 `spawnOptions`。

### 二、真 bug：空 options 的 8 字节对齐

之前手写的 `a{sv}` 编码是「`s` + u32(0)」。看起来没问题，实际上 **`a{sv}` 的数据起点必须 8 对齐**（`{sv}` 元素对齐 8），而 `u32(0)` 只补到 4。

后果：`4 + len + 1` 决定总长，**大约一半的包名**编码出来会短 4 字节 —— frida 侧解出来的 body 是错的，spawn 直接失败或拿到一个不存在的 pid。而 `com.a` 这种长度恰好落在 16 字节上是对的，所以"随手测一个能过"。

现在按 GLib 的权威格式生成，并用 **16 个 golden 案例**钉在单测里（见下）。

### 三、怎么确认编码是对的：拿 GLib 当裁判

这一类字节编码没法靠"看起来对"判断，所以换了做法 —— 用 `g_dbus_message_to_blob()` 取 GLib 真正发出去的 body 段，作为权威 golden：

```
spawn("com.a", {})                → 05 00 00 00 63 6f 6d 2e 61 00 00 00 00 00 00 00
spawn("com.example.app", {})      → 0f 00 00 00 ... 00 00 00 00 00  （24B，含两层对齐）
spawn("com.a", {cwd:"abc"})       → ... 14 00 00 00 | 03 00 00 00 63 77 64 00 | 01 73 00 00 | 03 00 00 00 | 61 62 63 00
```

十六条 golden 覆盖：不同长度包名的对齐、`s`/`as`/`u` 三种值、空数组、多 entry 组合。单测在 CI 里跑，谁改 `Gvariant.W` 都会立刻红。

顺带把踩到的三条 GVariant 规则写进了注释：

| 规则 | 说明 |
|---|---|
| 数组数据起点 8 对齐 | 漏了它 → 短包名少 4 字节 |
| 变长值有 framing 槽 | `s` 报**不含 NUL** 的长度，`as` 报**含各元素 NUL** 的总字节 |
| 定长值没有 framing 槽 | 多写 4 个 0 会让后续 dict entry 整体错位 |

### 四、老版本 frida 也能连了

接口名里的数字是 frida 的**接口版本**，与 frida 主版本不是一回事；更老的版本干脆没有后缀（`re.frida.HostSession`）。原来硬编码 `HostSession17`，上一版加了 17→16，这一版：

- 后缀改成字符串，候选 **17 → 16 → 无后缀**
- 新增 `fridaInterface` 参数，可以直接指定后缀（`""` 表示无后缀）—— 遇到未覆盖的版本不用等我发版
- 协商失败时，错误里列出所有试过的完整接口名

另外两处"同源"的编码也统一了：`CreateScript(s source, a{sv})` 与 `Spawn` 同构，直接复用同一个编码器；`EnumerateProcesses` 的空 `a{sv}` 补上 8 对齐（只发 4 字节在真机上也能跑，说明 frida 对空数组宽松，但按规范补全更稳）。

---

### 五、排查修掉的三处

**1. SO 对比：越界的函数被当成"相同"**
函数区间落到文件外（节区被裁剪、符号 size 虚高）时，`firstDiffOffset` 返回 null，而调用处把 null 当"比完一致" → `same++`。等于把"没法比较"说成"比过一样"，是最容易骗过自己的那种错。
现在单独计 `unreadable`，不再混进 same。

**2. SO 对比：一侧被 strip 时结论失真**
完整符号表的版本 vs strip 版，函数集合差异巨大 → 结果"上千个函数新增/删除"。现在会判断两侧是否都有函数符号，没有就标 `comparable=false` 并给出说明，而不是甩一屏假差异。

**3. DEX 查询页：翻页等于重新全量扫描**
索引查询没有"跳过"成本，offset 之前的部分照扫不误 —— 翻 5 页就是全量扫 5 遍，大包上每页几秒。
改成一次取回 2000 条、之后"继续显示"只在本地展开；到上限时提示缩小关键字而不是继续骗人。

顺带修了个不显眼但危险的东西：这个视图源码里被写进了**裸 0x01 控制字符**（本意是字符串分隔符），已改回转义写法 `\u0001`。它能编译纯属侥幸，但任何编辑器/工具链碰到都可能出问题。

---

### 已知未做

- spawn 的真机端到端仍未验证（没有设备）。现在能保证的是：字节编码有 16 条 GLib golden 单测、协议签名有 frida-core 源码依据、失败路径会 Kill 掉挂起的进程、错误信息能指出卡在 Spawn / Attach / Resume 哪一步。
- `HostSpawnOptions` 只覆盖 argv/envp/cwd/stdio 四个字段，其余（如 `aux`）未做。
- 函数级差分只按符号名对齐；两侧符号都 strip 时只能看节区级结果。

---

## 塔菲逆核 v1.5.20 —— Frida spawn 模式（挂起启动 → 注入 → 放行）

上一版明确留白的那件事，这次做完了。

---

### 一、spawn：能 hook 到 Application 初始化之前

attach 模式的根本限制是**只能附加到已经在跑的进程**——等你 attach 上去，`Application.onCreate`、
壳的 `attachBaseContext`、完整性校验、反调试探测全都跑完了。spawn 模式解决的就是这个：

```
Device.Spawn(program)  → 进程在入口挂起（还没执行任何 Java 代码）
  → HostSession.Attach(pid)
  → CreateScript + LoadScript（agent 就位）
  → Device.Resume(pid)  → 进程才真正开始跑
```

顺序是硬约束：**先 resume 就白搭了**，脚本必须在放行之前就位。

`taffy_dynamic_api` 的用法：

```
op=frida_open  args=[{ "mode": "spawn", "targetIdentifier": "com.target.app" }]
```

`mode=spawn` 时 `targetIdentifier` 的含义变了——不是 pid/进程名，而是**要启动的包名或可执行路径**。
返回里会带 `pid / spawned / resumed / agentPath / scriptLoaded`，能直接确认走到哪一步。

DBus 侧签名不是我猜的，取自 frida-core `src/host-session-service.vala`：

```vala
public abstract async uint spawn (string program, HostSpawnOptions options, Cancellable?)
```

也就是 `Spawn(s program, a{sv} options) → u`，与已经在真机上跑通的 `Attach(ua{sv})` 同一套
GVariant 写入器。`options` 传空字典 = 全部默认（argv 只有 program、stdio 继承 daemon），
这是 Android 上最常见的形式：program 给包名，交给系统起进程。

**两个必须做的收尾**，否则会留下"看不见的故障"：

- spawn 成功但 attach/注入/resume 任一步失败 → **Kill 掉那个挂起的进程**。不杀的话设备上会留一个
  卡在入口、没有界面的进程，用户只会看到"应用无响应"，根本不会想到是 frida 留下的。
- `closeSession` 时若 `spawnedPid > 0 && !resumed` → 先 Kill 再关连接。连接一关就再也发不出去 Kill 了。

### 二、spawn 之后不能立刻 hook

spawn 只是把进程叫起来，"目标 .so 还没 dlopen"是常态。`action=analyze backend=frida fridaMode=spawn`
原来会立刻 `hookFunction`，结果必然是 `export-not-found`——这不是用户用错了，是工具的顺序不对。

现在 analyze 在 spawn 分支里先轮询 `enumerateModules` 等目标模块出现（最多 20s），
返回 `moduleWaited`；等不到会给出明确提示而不是抛一个难懂的错误。

---

### 三、本轮排查修掉的四个真问题

**1. HostSession 接口版本写死 17 → frida-server 16.x 完全失联**
frida 的 D-Bus 接口名带主版本号（`re.frida.HostSession16` / `HostSession17`），代码里硬编码 17。
跑 16.x 的设备上**每一次调用**都撞 `UnknownInterface`，对外表现成"frida 不可用"——
而 server 明明在跑、root 也有，用户根本无从判断是版本名对不上。
现在按 17 → 16 依次用 `EnumerateProcesses` 做一次真实握手，成功后缓存，稳态不额外付 RTT。

**2. `frida_scan` 必然超时**
RPC 超时是全局 15s，而扫 256 MiB 只在小进程里才 15s 内跑完。大 App 上会稳定报"超时"，
实际扫描还在继续——用户看到的是"扫描失败"。现在 RPC 支持按操作覆盖超时，`frida_scan` 用 60s。

**3. 动态分析的 frida 端口不跟配置走**
`taffy_frida_control action=port` 可以把 frida-server 改到别的端口，但 `fridaTargetFromArgs` 的默认端口
写死 27042 → 改完端口后动态分析永远连旧的，表现还是"server 在跑却报不可用"。
现在默认值读 `FridaManager.currentPort()`。

**4. `taffy_dex_index action=stats` 白建几百万个对象**
"统计规模"原来是拿 `class_use + limit=1` 复用 search 跑的：命中判定仍然会为**每条指令** new 一个 `Hit`
再丢掉，几十万方法的大包就是几百万个临时对象，只为拿四个数字。
改成独立轻量路径，只数不建。

---

### 四、spawn 的可验证部分做了单测

spawn 唯一没有真机兜底的环节是 GVariant body 编码（`s` + 空 `a{sv}`）和 mode 判定。
新增 `FridaSpawnTest`：钉死字节序列（含 4 字节对齐填充）、`bodyUint` 的小端读取与短 body 保护、
`isSpawnMode` 的各种写法。顺带把这三个纯函数从类里提到文件顶层——
`FridaBridge` 是 class（构造要 Android Context），单测没法实例化它。

---

### 已知未做

- `HostSpawnOptions` 只传了空字典。要指定 `argv` / `env` / `cwd` / `stdio` 需要写非空 `a{sv}`
  （variant 还有一层自己的嵌套编码），Android 上 spawn 包名用不到，等有真实需求再做。
- spawn 在真机上的端到端链路我没有设备验证，只保证了协议签名有源码依据、字节编码有单测、
  失败路径有回收与明确错误信息。如果真机仍失败，返回的错误会指出是 Spawn / Attach / Resume 哪一步。
- `frida-server` 16.x 的接口协商只有 17→16 两个候选；更老的版本（15.x 及以前）接口名相同但
  方法集不同，未覆盖。

---

## 塔菲逆核 v1.5.19 —— DEX 索引查询 · SO 函数级差分 · Frida 内存扫描

这一版把三件事做进去了：一个「不依赖 native 的 DEX 全量查询」、把 SO 对比从「结构变了」
推进到「哪个函数变了」，以及 Frida 侧补上面向真实目标的内存特征扫描。

---

### 一、`taffy_dex_index` + 分析页「DEX查询」

以前查 DEX 只有两条路：`taffy_dex_xref` 必须先给出 className+methodName（只能回答单个方法的
to/from），`taffy_dex_search`(DexKit) 走 native 且只按特征字符串反查。**缺的是"全量索引式查询"**：
一次遍历，回答「谁 new 了这个类 / 谁读写了这个字段 / 谁重写了这个方法」。

新增 13 种查询类型：

| 类别 | 类型 |
|---|---|
| 声明 | `class` `method` `field` |
| 字面量 | `string`（含字段初值 / 注解元素 / `.source`） `int`（支持 `0x` 与负数） `code`（smali 片段） |
| 引用 | `new_instance` `field_get` `field_put` `field_any` `method_call` `method_override` `class_use` |

引用型可以**省略关键字 = 列全部**（看实例化热点、看谁实现了某接口方法）。配套：
`exclude` 排除包前缀、`regex` / `caseSensitive`、`limit`/`offset` 分页（返回真实 `matched` 总数与 `nextOffset`）。

`method_override` 需要祖先链，所以这一类会额外建一次全类表，沿 superclass 走最多 32 层、
并检查祖先与自己实现的接口——不是简单的"同名即重写"。

分析页侧栏新增「DEX查询」：选 APK/DEX → 类型下拉（引用型标注「可空」）→ 关键字 →
结果按「类名 / 成员 / 命中细节 / 所在 dex」四行显示，点一行复制签名，超过一页给「继续加载」。
没有 JSON/文本兜底出口。

### 二、SO 对比：从「结构变了」到「哪个函数变了」

`taffy_so_diff` 原来只能说 `.text` 变了。现在多了**函数级差分**：

- `added` / `removed` / `resized` / `changed`（内容变）/ `same`，另计 `unknownSize`
  （strip 过的库里 `st_size == 0` 的符号无法安全界定边界，单列计数而不是猜一个长度——
  猜错会把相邻函数的差异算到它头上）；
- `changed` 里带 `firstDiff`：首个不同字节相对函数起点的偏移，可以直接拿去 HEX 视图定位。

分工也就明确了：**本工具回答"哪个函数"，`taffy_byte_diff` 回答"哪些字节"**。
分析页 SO 对比多了「函数」页签。

### 三、Frida：`frida_scan`（进程内字节特征扫描）

`taffy_dynamic_api` 的 op 增加 `frida_scan=[sessionId, pattern, maxResults?]`，
pattern 用 Frida 语法（`48 8b ?? ??`，`??` 通配），扫只读页、预算 256 MiB，
返回 `address / module / rangesScanned / bytesScanned / truncated`。

预算不是摆设：真实 App 上 `Process.enumerateRanges('r--')` 动辄几百个 range 上百 MB，
无上限扫会把目标进程按住几秒到几十秒——而用户只会觉得"hook 卡死了"。
所以扫不完明确标 `truncated`，而不是悄悄截断或拖死。

---

### 四、本轮排查修掉的两个真问题

**1. `sodiff` 视图存在但菜单点不到（上一版引入）**
分析页的「更多视图」只渲染 `analysisOverflowViews` 里登记的 key。上一版加了导航项和
`when(view)` 分支，却漏了这个列表——视图代码在、编译通过、测试通过，**用户就是点不进去**。
本轮补登记，并把「视图加了三处要改」这件事写进注释。

**2. `taffy_dex_inspect` 并发下互相删文件**
工作目录是固定的 `dexinspect_work`，每次进入先 `deleteRecursively()`。两个客户端同时调用时，
后进来的那次会把先进来那次已经解压好的 dex 删掉，先来的随即在 `loadDexFiles` 报"解析失败"——
一个只在并发下出现、单机永远复现不了的随机故障。
改成每次一个唯一子目录、各删各的（`finally` 显式清理，崩溃残留交给 `TempJanitor` 按过期时间扫）。

顺带修了 DEX 视图自己的三个边界：改完关键字再点「继续加载」会把两次查询结果拼在一起（加查询指纹）、
扫描中清空按钮会把正在读的文件删掉（busy 时禁用）、缓存文件固定名可能覆盖仍在被读的旧文件（唯一命名）。

---

### 已知未做

- `frida_scan` 只扫只读页（`r--`）；扫 `rw-` 堆/匿名映射需要用户显式承担卡顿风险，留作显式开关。
- Frida `spawn` 模式（挂起启动后 attach，用于 hook `Application.onCreate` 之前的逻辑）
  仍未接——它需要改 DBus Device 接口调用链，而这一层在当前环境无法做真机验证，
  不愿意在没有实测的情况下往里塞协议代码。
- SO 函数级差分只按符号名对齐；两侧符号被 strip 掉时只能靠节区级结果。

---

## 塔菲逆核 v1.5.18 —— 按路径取文件不再把整棵工作区树扫一遍

排查时顺手发现的一处性能缺陷（不影响正确性，但会让人误以为"打开文件卡住了"）。

### 症状与根因

`Storage.readFile(relativePath)` 与 `Storage.fileSize(relativePath)` 都是**先全树递归 walk，
再在里面比较路径**。也就是说：

- 工作区里放一个解包过的 APK（几千个文件），按路径取其中**一个**文件，也要做几千次
  SAF `DocumentsContract` query；
- 更糟的是命中之后**不会停**：`found = uri` 之后 walk 继续把剩下的目录走完；
- 而 `Blutter` / APK 审计 / 打开 APK 的路径都是 **先 `fileSize` 再 `readFile`** —— 同一棵树走两遍。

用户感受就是：明明只是打开一个文件，进度条却磨了很久，还看不出在等什么。

### 修法

`walk()` 改成返回 `Boolean`，`onFile` 也返回 `Boolean`：

```kotlin
private fun walk(..., onFile: (Uri, String, String, Long, Long) -> Boolean): Boolean
```

- `readFile` / `fileSize`：找到目标路径就 `false`，整个遍历立刻向上返回；
- 子目录递归：`if (!keepScanning) return false`，不再继续兄弟目录；
- `fingerprint` / `listSos` / `listSosWithFingerprint` 这类"要全部结果"的扫描：
  lambda 末尾返回 `true`，行为与之前完全一致。

顺带确认一个语义点：工作区里的 `relativePath` 是 `prefix/name` 的全路径，本身唯一，
所以"命中即停"不会改变最终取到哪个文件——原来那种"遍历到最后一次赋值"的行为等价。

---

## 塔菲逆核 v1.5.17 —— SO 结构对比（MCP 工具 + 分析页视图）

把「SO 对比」那类小工具的能力整合进来，但走塔菲自己的解析器，不是再写一份。

### 一、`taffy_so_diff`：结构级差分

| 维度 | 内容 |
|---|---|
| 概览 | 文件大小、`.text` 大小、导出/导入数量、是否逐字节相同、ABI 是否一致 |
| 节区 | 新增 / 删除 / 变化（size、type、flags 三项，变化项按 \|Δ\| 排序） |
| 导出符号 | 新增 / 删除 / 变化（size、type、bind）+ 相同计数 |
| 导入符号 | 新增 / 删除 + 相同计数 |
| 依赖库 | DT_NEEDED 新增 / 删除 |
| 符号版本 | `.gnu.version*` 里的版本名新增 / 删除 |

并给一个判定，避免「看起来一样」被误读：

| verdict | 含义 |
|---|---|
| `identical` | 逐字节相同（SHA-256 相等） |
| `same-structure` | 结构完全一致，只有内容字节变了 |
| `code-only` | 只有 `.text` 变化，导出接口没动 |
| `structure-changed` | 节区/符号/依赖发生了变化 |
| `abi-mismatch` | bits / machine / endianness 不同，两个文件本来就不该直接比 |

参数：`pathA`、`pathB`（工作区路径或 `content://`）、`limit`（每类最多返回条数，默认 200）。
单文件读取上限 192 MiB——超过就报错而不是悄悄截断（截断的 ELF 解析结果全是垃圾）。
**只比结构不比字节**，字节级用 `taffy_read_hexdump`。

### 二、分析页「SO 结构对比」视图（view key `sodiff`）

- 两个文件卡：从系统选择器选 ELF／SO，显示文件名与大小，可「重选 / 交换 A/B / 清空」
- 摘要条：判定徽章 + `大小 / .text / 导出 / 导入` 四个增量（±N）
- 四个页签：节区 / 导出 / 导入 / 依赖，每行按 `+ 新增 / − 删除 / ~ 变化` 三色标注，右侧给 Δ 大小
- 顶部搜索框按名称过滤
- 没有「原始 JSON / 文本」兜底出口——每个页签都是为它自己的数据类型排的版

### 三、路径策略补 `pathA` / `pathB`

`WorkspacePolicy.PATH_KEYS` 是工具参数的白名单：键名不在里面，参数就**整体不走工作区校验**。
「两输入」类工具的第二个路径（`pathA`/`pathB`）此前没有登记位置，这类新工具一加就会绕过策略。
现在补上了，注释也写清楚为什么必须加。

### 四、架构：导航模型抽出 `AnalysisNavModel.kt`

`AnalysisWorkspace.kt` 已经 13.8k 行，`tools/arch-policy.py` 对存量文件的规则是「行数增长即 ERROR」。
这次把 `AnalysisNavItem` + `analysisNavItems`（58 行）抽到独立文件并改成 `internal`，
新增的 `sodiff` 条目直接加在新文件里——AnalysisWorkspace 净减 58 行，新增 1 行 when 分支。
以后再加分析页视图，改 `AnalysisNavModel.kt`，不要往那个 1.3 万行的文件里塞。

---

## 塔菲逆核 v1.5.16 —— 签名摘要对账实测，外加两处并发小修

这一版主体是**验证**：把挂了几轮的「`apk_sig_verify` 的 content digest 与 apksig 是否一致」
落到实处，顺带修掉排查中撞见的两处并发缺陷。

### 一、`apk_sig_verify` 的 content digest 与 apksig 实测一致（挂账项关闭）

做法：用 apksig 库对一个真实 APK 签 v2+v3，再按 spec 独立重算 content digest，
与签名块里存的 digest 逐字节比对。

```
signed.apk: size=8395 eocd=8373 cd=8192 blockStart=4096
  pairs: {0x7109871a: 1377, 0xf05368c0: 1377, 0x42726577: 1274}   ← v2 / v3 / source-stamp
  v2 sha256: chunks=3 signed=b55b4857… computed=b55b4857… MATCH=True
  v3 sha256: chunks=3 signed=b55b4857… computed=b55b4857… MATCH=True
```

然后把 entries 区第 100 字节翻转一位再算：

```
  v2 sha256: chunks=3 signed=b55b4857… computed=b34bf5b6… MATCH=False
```

结论：

- 三段落划分（`[0, blockStart)` / 中央目录 / 打了补丁的 EOCD）与 spec 一致；
- `blockStart = cdOffset - 8 - sizeFooter` 与块头尾 size 自校验一致；
- 单字节篡改能被检出。

也就是说「内容与签名不一致」这个信号本来就是可信的，不需要改实现。

### 二、大结果落盘会并发撞名（修）

工具输出超过 `toolResultMaxChars` 时会落盘到 `filesDir/tool-results/`，文件名原来只有
毫秒时间戳：`result-<millis>.json`。MCP 允许并发调用非 heavy 工具，**同一毫秒内落盘两个大结果时，
后写的直接覆盖前一个** —— 客户端拿着 `savedTo` 去读，读到的是别人的结果。

改成 `result-<millis>-<nanos>.json`，并且走 `AtomicWrite`（客户端可能在我们写完之前就按路径读）。

### 三、工具调用漏捕 `InterruptedException`（修）

`future.get(timeout)` 的 catch 链里有 Timeout / Execution，唯独没有 `InterruptedException`。
服务端 `stop()` 或进程收尾中断工作线程时，异常会直接抛给 Ktor，客户端看到的是**连接中断**
而不是结构化错误；同时中断标志也被吞掉。

现在返回 `TOOL_INTERRUPTED` 并 `Thread.currentThread().interrupt()` 恢复中断状态。

### 四、顺手核实过、确认没问题的（不改代码）

| 项 | 结论 |
|---|---|
| 路径策略覆盖 | 上一版新增的 30 个审计工具 + 5 个 workspace 工具，读的参数只有 `path`/`filePath`（都在 `PATH_KEYS` 里），其余是 `workspaceId`/`locator`/`editSessionId`/`pathHint`/`request`（非路径键）。没有绕过 `WorkspacePolicy`。 |
| 工具异常链 | `ExecutionException` → `TOOL_ERROR`，`TimeoutException` → `TOOL_TIMEOUT`。heavy 许可是由任务自身在 `finally` 里释放，不会因调用方超时而泄漏（另一个 heavy 工具也就进不来）。 |
| 取消传播 | `FullBackup`、`AgentOrchestrator`、`SubAgentRunner` 都是先 `catch (CancellationException)` 再 rethrow，取消不会被当成"失败节点"。 |
| `ToolStats` 增长 | 按工具名聚合（上限 = 工具目录大小），不会随调用次数膨胀；落盘 15s 节流。 |

---

## 塔菲逆核 v1.5.15 —— 并发竞争、中文乱码与无上限读取

这一版全是排查出来的既有缺陷，没有新功能。三类问题，都能在真机上把 App 弄崩或弄花。

### 一、共享 `SimpleDateFormat` 的并发竞争（2 处）

`SimpleDateFormat` 不是线程安全的，两个线程同时 `format` 会抛
`ArrayIndexOutOfBoundsException` / 输错得离谱的时间戳。

| 位置 | 症状 |
|---|---|
| `AppLog` | 全局日志设施，任何线程都可能同时写；`format` 调用还在锁**外**。并发时该条日志直接丢失（异常被上层吞掉），日志时间也可能错乱。 |
| `HttpCaptureServer` | 普通请求路径加了 `synchronized(formatter)`，**WebSocket 101 那条路径漏了**——WS 记录会偶发丢失。 |

两处都换成不可变、线程安全的 `java.time.format.DateTimeFormatter`，不再需要任何锁。

### 二、子进程输出按字节切再解码 → 中文乱码（4 处）

`proc.inputStream.read(ByteArray(4096))` 之后 `String(buf, 0, n, UTF_8)`：4 KiB 的字节边界
一旦正好落在某个 3 字节汉字中间，两块各解出半个字符，输出的中文就变成一串 `�`。

终端页、Python REPL、编辑器控制台、本地可执行日志（`NativeExec.drain`）都有这个问题。
统一改成 `InputStreamReader`（内部 CharsetDecoder 会跨块保留未完成的字节），抽到
`core/StreamText.kt` 的 `readChildText()` 一处实现。

顺带修了终端/Python 页的另一处竞争：`sb.append()` 在锁内、`sb.toString()` 在锁外——UI 线程
可能读到 StringBuilder 的半成品（轻则显示错乱，重则 `StringIndexOutOfBoundsException`）。现在快照在锁内取。

### 三、无上限读取（4 处，都能 OOM）

| 位置 | 原来的行为 | 现在 |
|---|---|---|
| 编辑器「打开文件」 | 系统选择器选到 1 GB 的 SO，`readBytes()` 一次读进内存 → 崩 | 先查大小，超过 8 MiB 明确提示「大文件请用分析页或终端」 |
| 编辑器控制台执行命令 | `p.inputStream.readBytes()`，`cat /dev/zero`、递归 `find /` 直接 OOM；且读不完就一直挂着等进程退出 | 4 MiB 截断并提示；`waitFor` 超时后 `destroyForcibly()` |
| 设置备份导入 | 误选一个大视频也整块读进内存 | 16 MiB 上限（设置 JSON 含密钥也只有几百 KB） |
| `AtomicWrite` 兜底路径 | 三种 move 都失败时 `target.writeBytes(tmp.readBytes())` 整块读 | 64 KiB 流式复制 |

### 四、Blutter 详情内联进逆向分析页签

上一版把「逆向分析」融成 6 个页签后，Blutter 页签还留了一个「版本与覆盖详情」的跳转——
点进去再按返回，会直接退回设置根页而不是回到融合页（`settingsDest` 是单值，没有栈）。

现在 `SettingsBlutterPage` 支持 `embedded = true`：矩阵与覆盖统计直接内联在页签内，
不再出现第二层顶栏，也不再有一次"跳走就回不来"的操作。

---

## 塔菲逆核 v1.5.14 —— 设置子页融合：两层纯目录变成一页六页签

这一版没有加功能，改的是「找东西的成本」。

### 一、问题：设置树里有两层是纯目录

改一次终端字号，过去要点三次：

```
设置 → 开发环境（6 个箭头，除了箭头没有别的信息）→ 终端执行（终于见到内容）
```

「开发环境」和「逆向分析」这两个二级页，每一行的用途都是「再点一次」。它们不承载信息，
也没有任何本页可做的操作 —— 只是把根页那张卡里的 6 项原样再列一遍。

### 二、开发环境：6 个子页融合成一页 6 个页签

| 页签 | 内容 |
|---|---|
| Linux | 发行版安装 / 根文件系统 / proot 状态 / 诊断 / 注入探针 / 脚本执行 |
| 终端 | 交互会话、软键盘、历史、跟随输出 |
| 可执行 | PIE 探测、白名单运行、进程列表、日志、终止 |
| 编辑器 | 打开编辑器 + 离开前确认 + 工具条顺序重置 |
| Git | 发行版环境、克隆/提交/分支/推送 |
| 扩展 | 插件列表、市场、教程 / API |

编辑器页签不嵌入编辑器本体 —— 它是顶栏上的独立全屏页（多标签、行号、搜索替换），
塞进页签只会变成两层工具栏。这里给入口和它的两条常驻偏好。

### 三、逆向分析：6 个子页融合成一页 6 个页签

| 页签 | 内容 |
|---|---|
| Rizin | 反汇编、函数、图分析 |
| eDBG | ELF 调试器与断点 |
| 抓包 | HTTP(S) 代理与请求记录 |
| 沙箱 | Unidbg 模拟执行 |
| 工作流 | 分析步骤编排 |
| Blutter | 内置 Runner 摘要 + 版本矩阵详情入口 |

Blutter 的版本矩阵与覆盖统计是两屏表格的低频细节，不是每次都要看的东西；页签里只放
「装了几套、矩阵是什么版本」，要看完整列表再进详情 —— 高频与低频分开，而不是全都堆在一屏。

### 四、页签来回切不再丢输入

融合页用 `rememberSaveableStateHolder` 托管每个页签的状态：填了一半的路径、展开的列表、
选好的发行版在切走再切回后都还在（此前会被重置成初始值）。

### 五、顺带修掉的「假按钮」

设置根页运行态卡上的「端口 8080 / 221 个工具 / 鉴权关闭」三条，之前是
`AssistChip(onClick = {})` —— 按下去有水波纹、什么都不发生。信息型内容不该长成按钮：
改成纯展示的 `StatusPill`，其中「鉴权关闭」用 error 配色显式警示（默认不开鉴权是产品选择，
但风险要在界面上被看见）。

### 六、`frida_script` 的说明与实现对齐

原注释写「表达式和语句块都支持」，实现是 `new Function('return (' + src + ')')` ——
对 `var x=1; x+1` 这种语句块是语法错误。现在明确只接受**单个表达式**，多语句请写
IIFE（`(function(){ …; return x })()`），报错时返回 `hint` 说明。

### 层级变化

| | 改前 | 改后 |
|---|---|---|
| 改终端字号 | 设置 → 开发环境 → 终端执行 | 设置 → 开发环境[终端] |
| 看抓包记录 | 设置 → 逆向分析 → 抓包 | 设置 → 逆向分析[抓包] |
| 进编辑器 | 设置 → 开发环境 → 编辑器 → 跳转 | 设置 → 开发环境[编辑器] → 打开 |

---

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

---

更早的版本说明见 GitHub Releases 历史（v1.5.11 及以前）。

