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
















