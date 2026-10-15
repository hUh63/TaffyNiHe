# Exbin（Explorer So）UI 风格规范 —— 塔菲分析页适配基线

> 依据：`exbin_src` 的 `res/layout/*.xml`、`res/values/colors.xml|themes.xml`、
> `ui/funcdetail/*.java`、`ui/sodetail/*.java`。**凡本规范未覆盖处，一律优先 M3 官方组件而非自绘。**

## 1. 页面骨架（每个分析页都长这样）

```
Column
├─ TopAppBar            高 56dp；navigationIcon=☰；title=TitleMedium；actions=IconButton(刷新/⋮)
├─ TabLayout / TabRow   固定或可滚动；indicator=primary；选中文字=primary；未选=onSurfaceVariant
├─ HorizontalDivider    1dp outlineVariant（Exbin: MaterialDivider）
└─ 内容区 weight(1f)
```
Exbin 实证：
- `activity_func_detail.xml` = Toolbar(?attr/actionBarSize) + TabLayout(fixed) + ViewPager2(weight 1)。
- `activity_so_detail.xml` = PushDrawerLayout + NavigationRailView(menuGravity=top, labeled, bg=colorSurface)
  + [MaterialToolbar + TabLayout(scrollable) + MaterialDivider + 内容]。

## 2. 组件选型（**禁止自绘**）

| 用途 | 组件 | 备注 |
|---|---|---|
| 分类切换（页签） | `TabRow` / `ScrollableTabRow` | 不是 chip 行 |
| 分段单选（模式/方向/排序） | M3 `SegmentedButton`（或 MaterialButtonToggleGroup 等价） | 不是自绘 pill |
| 筛选 / 开关 | `FilterChip` | 选中=primaryContainer |
| 主要动作 | `Button`（整宽或 pill） | 一屏最多一个主按钮 |
| 次要动作 | `OutlinedButton` / `TextButton` | 不用自绘 Surface |
| 列表项 | `ListItem` 或 `Card + Row` | Exbin `item_list_card` |
| 键值行 | label 100dp(LabelMedium/onSurfaceVariant) + value(weight1, monospace, BodyMedium) | Exbin `item_base_row`，padding 12dp |
| 排序 | `DropdownMenu` + 触发文本 | Exbin 用 Spinner |
| 更多动作 | `IconButton(⋮)` + `DropdownMenu`（带分组 header） | Exbin `menu_so_detail_addr` |
| 空/错 | 页内空态（图标 + 标题 + 说明 + 一个主按钮） | 不跳文本视图 |

## 3. 尺寸与排版（Exbin 实测）

- Toolbar 高 **56dp**；TabLayout 高 48dp；列表项 padding **12dp**；卡片 margin **8dp / 3dp**。
- 卡片：圆角 **10–12dp**、elevation 0、stroke 0、bg **surfaceVariant**（`item_detail.xml`）。
- 搜索框：圆角 **28dp**、高 44dp、padding 16/10/16/10、bg surfaceVariant、hint 一句话（`fragment_list_tab.xml` / XRefTabFragment）。
- 字号（M3 textAppearance）：TitleMedium 16 / TitleSmall 14 / BodyMedium 14 / BodySmall 12 / LabelMedium 12 / LabelSmall 11。
- 等宽字体：函数名 / 地址 / 指令 / 伪 C（`monospace`）。

## 4. 颜色

只用 M3 token：`primary / onPrimary / primaryContainer / onPrimaryContainer / secondaryContainer /
surface / surfaceVariant / surfaceContainer{Low,High} / onSurface / onSurfaceVariant /
outline / outlineVariant / error`。**不得自造色**（塔菲历史遗留 `AppPalette.orange` 等应替换为 tertiary/error）。

## 5. 交互原则（"人类操作审美"）

1. **一个动作一个触发点**，且点击区 ≥ 36dp；行不做"整行+局部"两个互相抢的手势。
2. **点击 = 主行为**（进入/切换/展开）；⋮ = 次要；长按少用。
3. **展开/收起**：三角只管层级；"看细节"用**独立按钮或独立页**，两者不共用手势。
4. **重活给明确主按钮**（"重新分析"/"重新构建"），执行中显示进度并禁用重复点击。
5. 状态永远可见：列表上方一行状态文案（Exbin：「找到 N 个外部引用 (调用者)」）。
6. 空/错不换页：页内空态 + 一个重试按钮。

---

## 6. 塔菲迁移映射（本批已落地 / 待办）

| 塔菲自绘 | 迁移到 | 状态 |
|---|---|---|
| `TabChip`（自绘 pill） | `FilterChip` | ✅ 本批 |
| `SmallAction`（自绘 Surface） | `OutlinedButton` / `FilledTonalButton` | ✅ 本批 |
| `XrefTabButton` 行（4 个自绘） | `TabRow` + `Tab` | ✅ 本批 |
| 节点列表自绘 Row | `ListItem`（leading 三角 IconButton / headline 等宽标题 / supporting 指令数 / trailing 「指令」TextButton） | ✅ 本批 |
| `AnalysisRail`（自绘 80dp） | M3 `NavigationRail` + `NavigationRailItem` | ⏳ 下一批 |
| `AnalysisAppBar`（自绘 Row） | `TopAppBar` | ⏳ 下一批（当前已接近 Toolbar 观感） |
| 各工具页自绘卡/按钮 | 同 §2 表格 | ⏳ 后续批次 |
