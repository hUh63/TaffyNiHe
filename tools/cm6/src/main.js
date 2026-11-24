// 塔菲逆核 · CodeMirror 6 编辑器内核
// 打包命令见 build.sh；产物 assets/editor/cm.js
import { EditorState, Compartment, StateEffect, StateField, RangeSetBuilder, Annotation } from "@codemirror/state"
import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection,
  highlightSpecialChars, dropCursor, rectangularSelection, crosshairCursor, Decoration, ViewPlugin,
  gutter, GutterMarker,
} from "@codemirror/view"
import {
  defaultKeymap, history, historyKeymap, indentWithTab, undo, redo, undoDepth, redoDepth,
  toggleComment, indentMore, indentLess, selectAll, deleteLine, moveLineUp, moveLineDown,
  copyLineUp, copyLineDown, cursorLineStart, cursorLineEnd,
} from "@codemirror/commands"
import {
  bracketMatching, foldGutter, foldKeymap, foldAll, unfoldAll, foldCode, unfoldCode, codeFolding,
  syntaxHighlighting, HighlightStyle, StreamLanguage, indentOnInput, foldService, ensureSyntaxTree,
} from "@codemirror/language"
import { searchKeymap, highlightSelectionMatches, openSearchPanel, closeSearchPanel, search, findNext, findPrevious } from "@codemirror/search"
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from "@codemirror/autocomplete"
import { tags as t } from "@lezer/highlight"
import { smaliWordKind, smaliFoldEnd, asmIsCommentStart, asmIsLabelLine, asmStartsLabel, asmWordKind, ASM_DIRECTIVES, indentFoldEnd, normalizeChanges } from "./lang.js"
import { java } from "@codemirror/lang-java"
import { cpp } from "@codemirror/lang-cpp"
import { python } from "@codemirror/lang-python"
import { json } from "@codemirror/lang-json"
import { xml } from "@codemirror/lang-xml"
import { markdown } from "@codemirror/lang-markdown"
import { javascript } from "@codemirror/lang-javascript"

// ---------------------------------------------------------------- 桥
const A = () => (window.AndroidBridge || null)
function post(kind, payload) {
  const b = A()
  if (!b) return
  try { b.post(kind, payload == null ? "" : String(payload)) } catch (e) {}
}
function log(m) { try { post("log", m) } catch (e) {} }

// ---------------------------------------------------------------- 大文件分级
// CM6 本身视口虚拟化，几百 KB 完全没问题；代价来自语法解析与逐行装饰。
// 分级阈值按实测：>1.2MB 关语法解析，>3MB 再关折叠槽与高亮匹配。
const SIZE_HEAVY = 1200000
const SIZE_HUGE = 3000000
let tier = 0 // 0 普通 1 重 2 超大

// ---------------------------------------------------------------- smali / asm 语言
// 判定逻辑放在 lang.js（纯函数，可在 node 里单测）；这里只做与 CM StreamLanguage 的适配。
const smaliLang = StreamLanguage.define({
  name: "smali",
  startState: () => ({}),
  token(stream) {
    if (stream.eatSpace()) return null
    if (stream.match("#")) { stream.skipToEnd(); return "comment" }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/)) return "string"
    if (stream.match(/^[0-9-][0-9a-fA-Fx.]*[tlsfd]?$/)) return "number"
    if (stream.match(/^[L\[]?[\w/$]+;/)) return "typeName"
    if (stream.match(/^:[\w$]+/)) return "labelName"
    if (stream.match(/^\.[\w-]+/)) return "keyword"
    if (stream.match(/^[\w$-]+/)) {
      if (stream.match(/^\(/)) return "function"
      return smaliWordKind(stream.current())
    }
    stream.next()
    return null
  },
  languageData: { commentTokens: { line: "#" } },
})

// 折叠：.method/.end method、.annotation、.array-data、switch 表；.catch 单独给一小段
const smaliFold = foldService.of((state, lineStart) => {
  const line = state.doc.lineAt(lineStart)
  const endRe = smaliFoldEnd(line.text)
  if (!endRe) {
    if (/^\.catch\b/.test(line.text.trim())) {
      return { from: line.to, to: Math.min(line.to + 200, state.doc.length) }
    }
    return null
  }
  for (let n = line.number + 1; n <= state.doc.lines; n++) {
    const l = state.doc.line(n)
    if (endRe.test(l.text.trim())) return { from: line.to, to: l.to }
  }
  return { from: line.to, to: state.doc.length }
})

const asmLang = StreamLanguage.define({
  name: "asm",
  startState: () => ({}),
  token(stream) {
    if (stream.eatSpace()) return null
    if (asmIsCommentStart(stream.string, stream.pos)) { stream.skipToEnd(); return "comment" }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/)) return "string"
    if (stream.match(/^0[xX][0-9a-fA-F]+|^\d+/)) return "number"
    if (stream.match(/^[A-Za-z_.][\w.$]*:/)) return "labelName"
    if (stream.match(/^\.[A-Za-z_][\w.]*/)) {
      return ASM_DIRECTIVES.has(stream.current().toLowerCase()) ? "keyword" : "meta"
    }
    if (stream.match(/^%?[A-Za-z][\w.]*/)) {
      const w = stream.current()
      if (stream.match(/^:/)) return "labelName"
      // 行首词按助记符处理：`bl` 在 arm64 是分支指令，在 x86 是 8 位寄存器别名
      const atLineStart = stream.string.slice(0, stream.start).trim().length === 0
      return asmWordKind(w, atLineStart)
    }
    if (stream.match(/^[[\]{},]/)) return "punctuation"
    stream.next()
    return null
  },
  languageData: { commentTokens: { line: ";" } },
})

// 折叠：按标签分段（到下一个标签）
const asmFold = foldService.of((state, lineStart) => {
  const line = state.doc.lineAt(lineStart)
  if (!asmIsLabelLine(line.text)) return null
  for (let n = line.number + 1; n <= state.doc.lines; n++) {
    const l = state.doc.line(n)
    if (asmStartsLabel(l.text)) return { from: line.to, to: l.from - 1 }
  }
  return { from: line.to, to: state.doc.length }
})

// 缩进兜底折叠：纯文本、未识别语言、语法树不可用时都能折
const indentFold = foldService.of((state, lineStart) => {
  const line = state.doc.lineAt(lineStart)
  const end = indentFoldEnd(state.doc.lines, (i) => state.doc.line(i + 1).text, line.number - 1)
  return end < 0 ? null : { from: line.to, to: state.doc.line(end + 1).to }
})

const LANG = {
  plain: () => [indentFold],
  java: () => java(),
  smali: () => [smaliLang, smaliFold],
  asm: () => [asmLang, asmFold],
  cpp: () => cpp(),
  python: () => python(),
  json: () => json(),
  xml: () => xml(),
  markdown: () => markdown(),
  javascript: () => javascript(),
}

// ---------------------------------------------------------------- 主题
const darkTheme = EditorView.theme({
  "&": { color: "var(--fg)", backgroundColor: "var(--bg)", height: "100%" },
  ".cm-content": { caretColor: "var(--caret)", padding: "6px 0", fontFamily: "var(--mono)", fontSize: "var(--fs)" },
  ".cm-scroller": { fontFamily: "var(--mono)", lineHeight: "1.45", overflow: "auto" },
  "&.cm-focused": { outline: "none" },
  ".cm-gutters": { backgroundColor: "var(--gut)", color: "var(--gutfg)", border: "none", paddingRight: "2px", fontSize: "var(--fs)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--gutcur)", color: "var(--accent)" },
  ".cm-activeLine": { backgroundColor: "var(--curline)" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection": { backgroundColor: "var(--sel)" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--caret)", borderLeftWidth: "2px" },
  ".cm-foldGutter span": { color: "var(--gutfg)", cursor: "pointer", padding: "0 2px" },
  ".cm-foldGutter span:hover": { color: "var(--accent)" },
  ".cm-foldPlaceholder": { backgroundColor: "var(--foldbg)", color: "var(--accent)", border: "none", borderRadius: "3px", padding: "0 4px" },
  ".cm-searchMatch": { backgroundColor: "var(--findmatch)", outline: "1px solid var(--accent)" },
  ".cm-searchMatch.cm-searchMatch-selected": { backgroundColor: "var(--findsel)" },
  ".cm-selectionMatch": { backgroundColor: "var(--selmatch)" },
  ".cm-panels": { backgroundColor: "var(--panel)", color: "var(--fg)" },
  ".cm-panels.cm-panels-top": { borderBottom: "1px solid var(--border)" },
  ".cm-panel.cm-search": { padding: "6px 8px", fontFamily: "var(--mono)", fontSize: "12px" },
  ".cm-panel input, .cm-panel button, .cm-panel label": { fontSize: "12px", fontFamily: "var(--mono)" },
  ".cm-panel input[type=text]": { backgroundColor: "var(--input)", color: "var(--fg)", border: "1px solid var(--border)", borderRadius: "4px", padding: "3px 6px" },
  ".cm-panel button": { backgroundColor: "var(--input)", color: "var(--fg)", border: "1px solid var(--border)", borderRadius: "4px", padding: "2px 8px", cursor: "pointer" },
  ".cm-panel button[name=close]": { color: "var(--accent)" },
  ".cm-tooltip": { backgroundColor: "var(--panel)", color: "var(--fg)", border: "1px solid var(--border)", borderRadius: "6px" },
  ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "var(--accent)", color: "var(--accentfg)" },
  ".cm-diagnostic-error": { borderLeft: "3px solid var(--err)" },
  ".cm-diagnostic-warning": { borderLeft: "3px solid var(--warn)" },
  ".cm-diagnostic-info": { borderLeft: "3px solid var(--info)" },
  ".cm-line": { padding: "0 4px 0 6px" },
}, { dark: true })

const lightTheme = EditorView.theme({
  "&": { color: "var(--fg)", backgroundColor: "var(--bg)", height: "100%" },
  ".cm-content": { caretColor: "var(--caret)", padding: "6px 0", fontFamily: "var(--mono)" },
  ".cm-scroller": { fontFamily: "var(--mono)", lineHeight: "1.45", overflow: "auto" },
  "&.cm-focused": { outline: "none" },
  ".cm-gutters": { backgroundColor: "var(--gut)", color: "var(--gutfg)", border: "none", paddingRight: "2px" },
  ".cm-activeLineGutter": { backgroundColor: "var(--gutcur)", color: "var(--accent)" },
  ".cm-activeLine": { backgroundColor: "var(--curline)" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection": { backgroundColor: "var(--sel)" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--caret)", borderLeftWidth: "2px" },
  ".cm-foldGutter span": { color: "var(--gutfg)", cursor: "pointer", padding: "0 2px" },
  ".cm-foldGutter span:hover": { color: "var(--accent)" },
  ".cm-foldPlaceholder": { backgroundColor: "var(--foldbg)", color: "var(--accent)", border: "none", borderRadius: "3px", padding: "0 4px" },
  ".cm-searchMatch": { backgroundColor: "var(--findmatch)", outline: "1px solid var(--accent)" },
  ".cm-searchMatch.cm-searchMatch-selected": { backgroundColor: "var(--findsel)" },
  ".cm-selectionMatch": { backgroundColor: "var(--selmatch)" },
  ".cm-panels": { backgroundColor: "var(--panel)", color: "var(--fg)" },
  ".cm-panels.cm-panels-top": { borderBottom: "1px solid var(--border)" },
  ".cm-panel.cm-search": { padding: "6px 8px", fontFamily: "var(--mono)", fontSize: "12px" },
  ".cm-panel input, .cm-panel button, .cm-panel label": { fontSize: "12px", fontFamily: "var(--mono)" },
  ".cm-panel input[type=text]": { backgroundColor: "var(--input)", color: "var(--fg)", border: "1px solid var(--border)", borderRadius: "4px", padding: "3px 6px" },
  ".cm-panel button": { backgroundColor: "var(--input)", color: "var(--fg)", border: "1px solid var(--border)", borderRadius: "4px", padding: "2px 8px", cursor: "pointer" },
  ".cm-tooltip": { backgroundColor: "var(--panel)", color: "var(--fg)", border: "1px solid var(--border)", borderRadius: "6px" },
  ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "var(--accent)", color: "var(--accentfg)" },
  ".cm-diagnostic-error": { borderLeft: "3px solid var(--err)" },
  ".cm-diagnostic-warning": { borderLeft: "3px solid var(--warn)" },
  ".cm-diagnostic-info": { borderLeft: "3px solid var(--info)" },
  ".cm-line": { padding: "0 4px 0 6px" },
}, { dark: false })

// 语法色按明暗两套内置（Kotlin 侧只传界面色，不必关心十几条语法色槽）
const SYNTAX_DARK = {
  "--c-comment": "#7A8290", "--c-keyword": "#C792EA", "--c-string": "#A5D6A7", "--c-number": "#F2A65A",
  "--c-type": "#7FD1E8", "--c-func": "#82AAFF", "--c-prop": "#B8C99A", "--c-var": "#E6E6EA",
  "--c-op": "#A0A6B0", "--c-tag": "#7FD1E8", "--c-heading": "#82AAFF", "--c-link": "#6FA8FF",
  "--c-meta": "#C792EA", "--c-label": "#E0A030",
}
const SYNTAX_LIGHT = {
  "--c-comment": "#6E7781", "--c-keyword": "#8250DF", "--c-string": "#0A7B34", "--c-number": "#B3541E",
  "--c-type": "#1F6F8B", "--c-func": "#1A56DB", "--c-prop": "#5A6B2E", "--c-var": "#24292F",
  "--c-op": "#57606A", "--c-tag": "#1F6F8B", "--c-heading": "#1A56DB", "--c-link": "#1A56DB",
  "--c-meta": "#8250DF", "--c-label": "#8A5B00",
}
function applySyntaxVars(dark) {
  const set = dark ? SYNTAX_DARK : SYNTAX_LIGHT
  const root = document.documentElement
  for (const k of Object.keys(set)) root.style.setProperty(k, set[k])
}

// 配色映射到 CSS 变量，Kotlin 侧只需给一组颜色
function buildHighlight(dark) {
  const c = (v) => `var(${v})`
  return HighlightStyle.define([
    { tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: c("--c-comment"), fontStyle: "italic" },
    { tag: [t.keyword, t.controlKeyword, t.moduleKeyword, t.operatorKeyword, t.definitionKeyword], color: c("--c-keyword") },
    { tag: [t.string, t.special(t.string), t.regexp], color: c("--c-string") },
    { tag: [t.number, t.bool, t.null, t.integer, t.float], color: c("--c-number") },
    { tag: [t.typeName, t.className, t.namespace, t.definition(t.typeName)], color: c("--c-type") },
    { tag: [t.function(t.variableName), t.function(t.propertyName), t.definition(t.function(t.variableName))], color: c("--c-func") },
    { tag: [t.propertyName, t.attributeName], color: c("--c-prop") },
    { tag: [t.variableName, t.definition(t.variableName)], color: c("--c-var") },
    { tag: [t.operator, t.punctuation, t.separator, t.bracket, t.paren, t.squareBracket, t.brace], color: c("--c-op") },
    { tag: [t.tagName, t.angleBracket], color: c("--c-tag") },
    { tag: [t.heading, t.heading1, t.heading2, t.heading3], color: c("--c-heading"), fontWeight: "600" },
    { tag: [t.emphasis], fontStyle: "italic" },
    { tag: [t.strong], fontWeight: "700" },
    { tag: [t.link, t.url], color: c("--c-link"), textDecoration: "underline" },
    { tag: [t.meta, t.annotation, t.processingInstruction], color: c("--c-meta") },
    { tag: [t.labelName], color: c("--c-label") },
    { tag: [t.invalid], color: c("--err") },
  ])
}

// ---------------------------------------------------------------- 差异行 / 诊断装饰
const diffEffect = StateEffect.define()
const diffField = StateField.define({
  create: () => Decoration.none,
  update(deco, tr) {
    for (const e of tr.effects) {
      if (e.is(diffEffect)) {
        const b = new RangeSetBuilder()
        const marks = e.value // [{line, kind}]
        const total = tr.state.doc.lines
        for (const m of marks) {
          if (m.line < 1 || m.line > total) continue
          const l = tr.state.doc.line(m.line)
          const cls = m.kind === "add" ? "cm-diffAdd" : m.kind === "del" ? "cm-diffDel" : "cm-diffMod"
          b.add(l.from, l.from, Decoration.line({ class: cls }))
        }
        return b.finish()
      }
    }
    if (tr.docChanged) return deco.map(tr.changes) // 编辑后按位置跟随，避免每次重算
    return deco
  },
  provide: (f) => EditorView.decorations.from(f),
})

const diagEffect = StateEffect.define()
const diagField = StateField.define({
  create: () => Decoration.none,
  update(deco, tr) {
    for (const e of tr.effects) {
      if (e.is(diagEffect)) {
        const b = new RangeSetBuilder()
        const total = tr.state.doc.lines
        for (const d of e.value) {
          if (d.line < 1 || d.line > total) continue
          const l = tr.state.doc.line(d.line)
          const cls = d.sev === 0 ? "cm-diagErr" : d.sev === 1 ? "cm-diagWarn" : "cm-diagInfo"
          b.add(l.from, l.from, Decoration.line({ class: cls, attributes: { title: d.msg || "" } }))
        }
        return b.finish()
      }
    }
    if (tr.docChanged) return deco.map(tr.changes)
    return deco
  },
  provide: (f) => EditorView.decorations.from(f),
})

// 程序化事务标记：Kotlin 侧写进来的内容不能再作为「用户编辑」回传，
// 否则两侧会互相覆盖（Kotlin 应用 diff → JS 回传同一 diff → Kotlin 二次应用）。
const Prog = Annotation.define()

// ---------------------------------------------------------------- 状态
let view = null
let gutterComp = new Compartment()
let wrapComp = new Compartment()
let curGutter = true
let curWrap = false
let langComp = new Compartment()
let themeComp = new Compartment()
let roComp = new Compartment()
let heavyComp = new Compartment()
let curLang = "plain"
let curDark = true
let pendingInit = null // 分块推送缓冲
let docLen = 0

const emitThrottle = { text: 0, sel: 0 }

function selInfo(st) {
  const r = st.selection.main
  const line = st.doc.lineAt(r.head)
  return { a: r.from, b: r.to, line: line.number, col: r.head - line.from + 1, lines: st.doc.lines, len: st.doc.length }
}

function emitSel(force) {
  if (!view) return
  const now = Date.now()
  if (!force && now - emitThrottle.sel < 90) return
  emitThrottle.sel = now
  const i = selInfo(view.state)
  post("sel", `${i.a},${i.b},${i.line},${i.col},${i.lines},${i.len}`)
}

function emitState() {
  if (!view) return
  post("state", JSON.stringify({
    undo: undoDepth(view.state), redo: redoDepth(view.state),
    lines: view.state.doc.lines, len: view.state.doc.length,
    folded: foldCount(),
  }))
}

// 已折叠区间数量（CM6 把折叠状态放在 codeFolding facet 里）
function foldCount() {
  if (!view) return 0
  try { return (view.state.facet(codeFolding) || []).length } catch (e) { return 0 }
}

function progDispatch(spec) {
  if (!view) return
  spec.annotations = Prog.of(true)
  view.dispatch(spec)
}

// 变更回传：用 diff，避免每次按键回传整篇文本
const changePlugin = ViewPlugin.fromClass(class {
  constructor(v) { this.v = v }
  update(u) {
    if (!u.docChanged) return
    for (const tr of u.transactions) { if (tr.annotation(Prog)) return }
    // 合并所有变更区间（Android 侧按 StringBuilder 应用，倒序即可）
    const list = []
    const raw = []
    u.changes.iterChanges((fromA, toA, fromB, toB, ins) => {
      raw.push({ fromA: fromA, toA: toA, insert: ins.toString() })
    })
    for (const c of normalizeChanges(raw)) list.push(c)
    if (list.length) {
      // 一次按键通常只有一段；多段（粘贴、批量替换）逐段说明
      post("change", JSON.stringify(list))
    }
    emitSel(true)
    emitState()
  }
})

function docOf() { return view ? view.state.doc.toString() : "" }

// base64 → UTF-8 文本（一次性解码，供分块推送用）
function b64ToText(b64) {
  const bin = atob(b64 || "")
  const len = bin.length
  if (len === 0) return ""
  const bytes = new Uint8Array(len)
  for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i)
  return new TextDecoder("utf-8").decode(bytes)
}

// 分级扩展：普通文件功能全开，重文件去掉高亮行/补全，超大文件连折叠槽也去掉
function heavyExts(tv) {
  if (tv === 0) return [highlightActiveLine(), highlightActiveLineGutter(),
    foldGutter({ openText: "▾", closedText: "▸" }), autocompletion({ activateOnTyping: true, maxRenderedOptions: 30 })]
  if (tv === 1) return [highlightActiveLine(), foldGutter({ openText: "▾", closedText: "▸" })]
  return []
}

// 行号槽 / 自动换行：默认行号开、换行关（换行会让行号与可视行错位，代码类文件默认不折）
function gutterExt() { return curGutter ? lineNumbers() : [] }
function wrapExt() { return curWrap ? EditorView.lineWrapping : [] }

// ---------------------------------------------------------------- 对外 API
const api = {
  init(optsJson) {
    const o = optsJson ? JSON.parse(optsJson) : {}
    curLang = o.lang || "plain"
    curDark = !!o.dark
    const ro = !!o.readOnly
    const text = o.text || ""
    tier = text.length > SIZE_HUGE ? 2 : text.length > SIZE_HEAVY ? 1 : 0

    const exts = [
      gutterComp.of(gutterExt()),
      wrapComp.of(wrapExt()),
      highlightSpecialChars(),
      history({ minDepth: 200 }),
      drawSelection(),
      dropCursor(),
      EditorState.allowMultipleSelections.of(true),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      rectangularSelection(),
      crosshairCursor(),
      search({ top: true }),
      highlightSelectionMatches(),
      diffField,
      diagField,
      changePlugin,
      keymap.of([
        ...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap,
        ...foldKeymap, ...completionKeymap, indentWithTab,
      ]),
      langComp.of(LANG[curLang] ? LANG[curLang]() : []),
      themeComp.of(curDark ? [darkTheme, syntaxHighlighting(buildHighlight(true))] : [lightTheme, syntaxHighlighting(buildHighlight(false))]),
      roComp.of([EditorState.readOnly.of(ro), EditorView.editable.of(!ro)]),
      heavyComp.of(heavyExts(tier)),
      EditorView.updateListener.of((u) => { if (u.selectionSet) emitSel(false) }),
    ]

    view = new EditorView({
      state: EditorState.create({ doc: text, extensions: exts }),
      parent: document.getElementById("root"),
    })
    document.body.setAttribute("data-tier", String(tier))
    applySyntaxVars(curDark)
    emitSel(true)
    emitState()
    post("ready", JSON.stringify({ tier, lines: view.state.doc.lines, len: view.state.doc.length }))
    // 不主动 focus：一进页面就弹软键盘会挡住半屏，用户点一下代码区再输入更自然
    view.requestMeasure()
  },

  // 分块推送全文（base64），避免单次 JSBridge 传超大字符串
  beginDoc(total) { pendingInit = { parts: [], total: Number(total) || 0 } },

  chunk(b64) {
    if (!pendingInit) return
    // 只存 base64：分块独立解码会把跨块的多字节 UTF-8 字符切坏
    pendingInit.parts.push(b64)
  },

  endDoc(caret) {
    if (!view) return
    const txt = pendingInit ? b64ToText(pendingInit.parts.join("")) : ""
    const n = Number(caret)
    pendingInit = null
    tier = txt.length > SIZE_HUGE ? 2 : txt.length > SIZE_HEAVY ? 1 : 0
    const spec = {
      selection: { anchor: n >= 0 ? Math.min(n, txt.length) : txt.length },
      effects: [
        langComp.reconfigure(tier === 2 ? [] : (LANG[curLang] ? LANG[curLang]() : [])),
        heavyComp.reconfigure(heavyExts(tier)),
      ],
    }
    if (txt !== view.state.doc.toString()) spec.changes = { from: 0, to: view.state.doc.length, insert: txt }
    progDispatch(spec)
    document.body.setAttribute("data-tier", String(tier))
    emitSel(true); emitState()
  },

  // 差量替换：保留 prefix/suffix 之外的最小改动，天然进 CM 撤销栈
  applyDiff(prefixLen, suffixLen, b64) {
    if (!view) return
    const p = Number(prefixLen) || 0
    const s = Number(suffixLen) || 0
    const ins = b64ToText(b64)
    const len = view.state.doc.length
    const from = Math.min(p, len)
    const to = Math.max(from, len - s)
    progDispatch({ changes: { from, to, insert: ins } })
    emitSel(true); emitState()
  },

  // 字号（CSS 变量，行号与正文一起变）
  setFont(px) {
    const n = Number(px)
    if (!(n > 0)) return
    document.documentElement.style.setProperty("--fs", n + "px")
    if (view) view.requestMeasure()
  },

  setGutter(b) {
    curGutter = !!b
    if (view) progDispatch({ effects: gutterComp.reconfigure(gutterExt()) })
  },

  setWrap(b) {
    curWrap = !!b
    if (view) { progDispatch({ effects: wrapComp.reconfigure(wrapExt()) }); view.requestMeasure() }
  },

  setReadOnly(b) {
    if (!view) return
    const ro = !!b
    progDispatch({ effects: roComp.reconfigure([EditorState.readOnly.of(ro), EditorView.editable.of(!ro)]) })
  },

  setLang(name) {
    if (!view || !LANG[name]) return
    curLang = name
    progDispatch({ effects: langComp.reconfigure(tier === 2 ? [] : LANG[name]()) })
  },

  setTheme(json) {
    if (!view) return
    const o = JSON.parse(json)
    const root = document.documentElement
    for (const k of Object.keys(o)) {
      if (k === "dark") continue
      root.style.setProperty("--" + k, o[k])
    }
    if (typeof o.dark !== "undefined") {
      curDark = !!o.dark
      document.body.classList.toggle("dark", curDark)
      applySyntaxVars(curDark)
      progDispatch({ effects: themeComp.reconfigure(curDark ?
        [darkTheme, syntaxHighlighting(buildHighlight(true))] :
        [lightTheme, syntaxHighlighting(buildHighlight(false))]) })
    }
  },

  setSel(a, b, scroll) {
    if (!view) return
    const len = view.state.doc.length
    const from = Math.max(0, Math.min(Number(a) || 0, len))
    const to = Math.max(from, Math.min(Number(b) || 0, len))
    view.dispatch({ selection: { anchor: from, head: to }, scrollIntoView: scroll !== false })
    emitSel(true)
  },

  getSel() { return JSON.stringify(selInfo(view.state)) },

  undo() { if (!view) return; undo(view); emitSel(true); emitState() },
  redo() { if (!view) return; redo(view); emitSel(true); emitState() },

  // 折叠：库原生能力，Android 侧要的「折叠全部 / 展开全部」直接可用
  foldAll() { if (!view) return; foldAll(view); emitState() },
  unfoldAll() { if (!view) return; unfoldAll(view); emitState() },
  foldAtCursor() { if (!view) return; foldCode(view) },
  // 当前块：已折叠则展开，否则折叠（一个按钮两种状态）
  toggleFold() {
    if (!view) return
    const before = foldCount()
    unfoldCode(view)
    if (foldCount() === before) foldCode(view)
    emitState()
  },
  unfoldAtCursor() { if (!view) return; unfoldCode(view) },

  // 块导航：按折叠块首行跳转（替代上一版的 Kotlin 侧块表）
  blockLines() {
    if (!view) return "[]"
    const out = []
    const st = view.state
    const tree = ensureSyntaxTree(st, st.doc.length, 200) // 超时 200ms 不阻塞
    if (tree) {
      const seen = new Set()
      tree.iterate({
        enter: (n) => {
          // 只取「块级」节点：有子节点、跨多行
          if (n.node.firstChild) {
            const line = st.doc.lineAt(n.from).number
            if (!seen.has(line)) { seen.add(line); out.push(line) }
          }
        },
      })
    }
    // smali / asm / 纯文本：用正则兜底
    if (out.length === 0) {
      for (let n = 1; n <= st.doc.lines; n++) {
        const txt = st.doc.line(n).text
        if (/^\s*(\.method|\.annotation|\.array-data|\.packed-switch|\.sparse-switch)\b/.test(txt)) out.push(n)
        else if (/^[A-Za-z_.][\w.$]*:\s*$/.test(txt)) out.push(n)
      }
    }
    out.sort((x, y) => x - y)
    return JSON.stringify(out)
  },

  jumpBlock(dir) {
    if (!view) return
    const lines = JSON.parse(api.blockLines())
    if (!lines.length) return
    const cur = view.state.doc.lineAt(view.state.selection.main.head).number
    let target = 0
    if (dir > 0) target = lines.find((l) => l > cur) || lines[0]
    else {
      for (let i = lines.length - 1; i >= 0; i--) { if (lines[i] < cur) { target = lines[i]; break } }
      if (!target) target = lines[lines.length - 1]
    }
    const off = view.state.doc.line(target).from
    view.dispatch({ selection: { anchor: off }, scrollIntoView: true })
    emitSel(true)
  },

  scrollToLine(line) {
    if (!view) return
    const n = Math.max(1, Math.min(Number(line) || 1, view.state.doc.lines))
    const off = view.state.doc.line(n).from
    view.dispatch({ selection: { anchor: off }, scrollIntoView: true })
    emitSel(true)
  },

  setDiff(json) {
    if (!view) return
    const marks = JSON.parse(json || "[]")
    view.dispatch({ effects: diffEffect.of(marks) })
  },

  setDiagnostics(json) {
    if (!view) return
    const ds = JSON.parse(json || "[]")
    view.dispatch({ effects: diagEffect.of(ds) })
  },

  // 文本按需取回：分块，避免单次传 1MB+
  docLen() { return String(view ? view.state.doc.length : 0) },
  chunkOf(start, len) {
    if (!view) return ""
    const from = Math.max(0, Number(start) || 0)
    const n = Math.max(0, Number(len) || 0)
    const sub = view.state.doc.sliceString(from, Math.min(from + n, view.state.doc.length))
    return btoa(String.fromCharCode.apply(null, new TextEncoder().encode(sub)))
  },

  // 命令：把文本变换下沉到 JS，Kotlin 侧不必持有全文做拼接
  cmd(name, arg) {
    if (!view) return
    switch (name) {
      case "indent": indentMore(view); break
      case "outdent": indentLess(view); break
      case "comment": toggleComment(view); break
      case "duplicate": copyLineDown(view); break
      case "moveup": moveLineUp(view); break
      case "movedown": moveLineDown(view); break
      case "deleteline": deleteLine(view); break
      case "selectall": selectAll(view); break
      case "linestart": cursorLineStart(view); break
      case "lineend": cursorLineEnd(view); break
      case "search": openSearchPanel(view); break
      case "findnext": findNext(view); break
      case "findprev": findPrevious(view); break
      case "closesearch": closeSearchPanel(view); break
      case "replaceAll": {
        // 交给 CM 的搜索面板完成，这里只用于「外部给定 q → 全部替换」
        if (!arg) break
        const parts = JSON.parse(arg) // [q, with, regex, caseSensitive]
        const re = new RegExp(parts[2] ? parts[0] : parts[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), parts[3] ? "g" : "gi")
        const txt = view.state.doc.toString()
        const changed = txt.replace(re, parts[1])
        if (changed !== txt) progDispatch({ changes: { from: 0, to: txt.length, insert: changed } })
        break
      }
      default: break
    }
    emitSel(true); emitState()
  },

  // 光标处插入（补全、片段）
  insert(text) {
    if (!view) return
    const r = view.state.selection.main
    progDispatch({
      changes: { from: r.from, to: r.to, insert: text },
      selection: { anchor: r.from + text.length },
    })
    emitSel(true); emitState()
  },

  focus() { if (view) view.focus() },
  blur() { if (view) view.contentDOM.blur() },

  // 供 Kotlin 侧排版需要：把光标行滚动到视口中间
  centerCursor() {
    if (!view) return
    const pos = view.state.selection.main.head
    const coords = view.coordsAtPos(pos)
    const sc = view.scrollDOM
    if (!coords) return
    const target = sc.scrollTop + coords.top - sc.getBoundingClientRect().top - sc.clientHeight / 2
    sc.scrollTop = Math.max(0, target)
  },
}

window.CM = api

// 启动自检：Kotlin 会按名字调这些方法，缺一个就是「点了没反应」，
// 与其在真机上猜，不如自报上来
const REQUIRED_API = ["init", "beginDoc", "chunk", "endDoc", "applyDiff", "setSel", "undo", "redo",
  "foldAll", "unfoldAll", "foldAtCursor", "unfoldAtCursor", "jumpBlock", "scrollToLine", "setDiff",
  "setDiagnostics", "setTheme", "setFont", "setLang", "setGutter", "setWrap", "setReadOnly",
  "cmd", "insert", "focus"]
const missingApi = REQUIRED_API.filter((k) => typeof api[k] !== "function")
if (missingApi.length) post("log", "api-missing: " + missingApi.join(","))

// 页面级别：禁掉长按菜单里的「网页」项、确保软键盘不与内容重叠
document.addEventListener("contextmenu", (e) => { e.preventDefault() })
window.addEventListener("resize", () => { if (view) view.requestMeasure() })

// 全局异常也要回传，否则 WebView 里出错在 Android 侧完全看不到
window.addEventListener("error", (e) => log("js-error: " + (e.message || "") + " @" + (e.lineno || 0)))
window.addEventListener("unhandledrejection", (e) => log("js-reject: " + ((e.reason && e.reason.message) || e.reason)))

post("boot", "core-loaded")
