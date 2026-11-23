# 塔菲逆核 · 编辑器内核（CodeMirror 6）

APK 里跑的是 `app/src/main/assets/editor/`，那份产物就是从这里的源码打出来的。

## 为什么不用现成的 Android 编辑器库

sora-editor 0.24.x 的代码折叠是**空壳**：`FoldingRegion` 带 `setCollapsed`，但全库零调用方；
`CodeBlock` 只被渲染层用来画块连接线；布局层（`LineBreakLayout` / `WordWrapLayout`）
以「逻辑行号 == 视觉行号」为硬前提，没有隐藏行的概念。也就是说折不了。

换 CM6 之后：折叠、搜索替换、多光标、撤销栈、括号匹配都是库自带且有实际使用者的能力，
渲染按视口虚拟化，大文件不卡。

## 构建

```bash
npm i
npm run build     # 产出 dist/cm.js（约 820 KB，minified）
npm test          # lang.js 的纯逻辑单测（node --test）
cp dist/cm.js  dist/index.html ../../app/src/main/assets/editor/
```

`index.html` 就在本目录，改样式改它。

## 文件

- `src/main.js` — CM6 装配：语言、主题、折叠槽、差异行/诊断装饰、分级性能开关、`window.CM` 桥
- `src/lang.js` — smali / asm 的词法与折叠判定（纯函数，单测覆盖）
- `src/index.html` — 页面骨架与 CSS 变量（明暗两套）
- `test/lang.test.mjs` — 6 组单测

## 与 Kotlin 的接口

Kotlin 侧是 `com.soreverse.mcp.CodeMirrorHandle`，双向只走两条通道：

**Kotlin → JS**（`evaluateJavascript`）
- `CM.init(json)` / `CM.setText` 分块：`beginDoc(n)` → `chunk(b64)...` → `endDoc(caret)`
- `CM.applyDiff(prefix, suffix, b64Insert)` — 只传最小差异，进同一套撤销栈
- `CM.setSel(a,b,scroll)` / `CM.scrollToLine(n)` / `CM.foldAll()` / `CM.unfoldAll()`
- `CM.cmd(name[, arg])` — 缩进、注释、复制行、上/下移行、搜索面板…
- `CM.setDiff(json)` / `CM.setDiagnostics(json)` — 行级装饰
- `CM.setTheme(json)` / `CM.setFont(px)` / `CM.setLang(name)` / `CM.setGutter(b)` / `CM.setWrap(b)`

**JS → Kotlin**（`AndroidBridge.post(kind, payload)`）
- `boot` / `ready` — 启动握手
- `change` — `[{from,to,insert}]`（已按顺序归一化坐标）
- `sel` — `"a,b,line,col,lines,len"`
- `state` — `{undo,redo,lines,len,folded}`
- `log` — JS 异常与关键日志（不再静默）

## 性能分级

按文本长度三档（`SIZE_HEAVY = 1.2 MB`、`SIZE_HUGE = 3 MB`）：

| 档 | 语法解析 | 高亮行 | 折叠槽 | 补全 | 行号 |
|----|---------|-------|-------|------|------|
| 普通 | 全开 | ✓ | ✓ | ✓ | ✓ |
| 重（>1.2MB） | 全开 | ✓ | ✓ | ✗ | ✓ |
| 超大（>3MB） | 关 | ✗ | ✗ | ✗ | ✗ |

`progDispatch` 用 `Annotation` 标记程序化事务 —— Kotlin 写进来的内容不回传成"用户编辑"，
否则两侧会互相覆盖。
