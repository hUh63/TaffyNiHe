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

