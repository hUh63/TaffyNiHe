// 纯逻辑：smali / asm 的词法判定与折叠块匹配。
// 抽成独立模块是为了能在 node 里单测（CM6 的 View 需要真实 DOM，沙箱里跑不了）；
// 这里任何一个判定出错，表现都是「高亮不对 / 折叠块划错」，所以必须可测。

export const SMALI_KEYWORDS = new Set((
  ".class .super .implements .source .field .method .end .annotation .subannotation " +
  ".param .prologue .epilogue .line .locals .local .registers .catch .catchall .packed-switch " +
  ".sparse-switch .array-data .end-array-data .restart .limit .enum"
).split(" "))

export const SMALI_OPS = new Set((
  "invoke-virtual invoke-super invoke-direct invoke-static invoke-interface invoke-virtual/range " +
  "invoke-static/range invoke-direct/range invoke-interface/range invoke-polymorphic move move/from16 " +
  "move/16 move-wide move-object move-result move-result-wide move-result-object move-exception " +
  "return return-void return-wide return-object const const/4 const/16 const/high16 const-wide " +
  "const-string const-string/jumbo const-class new-instance new-array filled-new-array goto goto/16 " +
  "goto/32 if-eq if-ne if-lt if-ge if-gt if-le if-eqz if-nez if-ltz if-gez if-gtz if-lez packed-switch " +
  "sparse-switch cmpl-float cmpg-float cmpl-double cmpg-double cmp-long aget aput aget-wide aput-wide " +
  "aget-object aput-object aget-boolean aput-boolean aget-byte aput-byte aget-char aput-char aget-short " +
  "aput-short iget iput iget-wide iput-wide iget-object iput-object iget-boolean iput-boolean iget-byte " +
  "iput-byte iget-char iput-char iget-short iput-short sget sput sget-wide sput-wide sget-object " +
  "sput-object sget-boolean sput-boolean sget-byte sput-byte sget-char sput-char sget-short sput-short " +
  "add-int sub-int mul-int div-int rem-int and-int or-int xor-int shl-int shr-int ushr-int add-long " +
  "sub-long mul-long div-long rem-long and-long or-long xor-long shl-long shr-long ushr-long add-float " +
  "sub-float mul-float div-float rem-float add-double sub-double mul-double div-double rem-double " +
  "int-to-long int-to-float int-to-double long-to-int long-to-float long-to-double float-to-int " +
  "float-to-long float-to-double double-to-int double-to-long double-to-float int-to-byte int-to-char " +
  "int-to-short neg-int neg-long neg-float neg-double not-int not-long not-float not-double throw " +
  "monitor-enter monitor-exit check-cast instance-of array-length fill-array-data nop " +
  "execute-inline invoke-custom const-method-handle const-method-type"
).split(" "))

export const ASM_DIRECTIVES = new Set((
  ".text .data .bss .section .global .globl .type .size .align .p2align .byte .short .word .long " +
  ".quad .asciz .string .space .zero .equ .set .include .intel_syntax .arm .thumb .syntax .arch " +
  ".cpu .fpu .thumb_func .code .ltorg .pool .extern .local .file .ident .subsection .previous"
).split(" "))

const SMALI_REG = /^[vp]\d+$/
export const ASM_REG = /^(?:[xw](?:[0-9]|[12][0-9]|30)|sp|bp|lr|pc|xzr|wzr|r[0-9]{1,2}|e[a-d]x|e[sd]i|e[sb]p|r[a-d]x|r[sd]i|r[sb]p|rax|rbx|rcx|rdx|rsi|rdi|rbp|rsp|al|bl|cl|dl|ah|bh|ch|dh|ax|bx|cx|dx|si|di|bp)$/

/** smali: 一个裸词（不含 . # " : 等前导符）的类别 */
export function smaliWordKind(w) {
  if (SMALI_OPS.has(w)) return "keyword"
  if (SMALI_REG.test(w)) return "variableName"
  return "variableName"
}

/** smali: 折叠块起始行 → 结束行正则；不是块起始返回 null */
export function smaliFoldEnd(text) {
  const txt = text.trim()
  if (/^\.method\b/.test(txt)) return /^\.end method\b/
  if (/^\.annotation\b/.test(txt)) return /^\.end annotation\b/
  if (/^\.subannotation\b/.test(txt)) return /^\.end subannotation\b/
  if (/^\.array-data\b/.test(txt)) return /^\.end array-data\b/
  if (/^\.packed-switch\b/.test(txt)) return /^\.end packed-switch\b/
  if (/^\.sparse-switch\b/.test(txt)) return /^\.end sparse-switch\b/
  return null
}

/** asm: 整行就是一个标签（`name:` 后面没有指令），可作为折叠块起点 */
export function asmIsLabelLine(text) {
  return /^[A-Za-z_.][\w.$]*:\s*$/.test(text)
}

/** asm: 行首是标签（可有指令跟随），用于判断上一个块到哪里结束 */
export function asmStartsLabel(text) {
  return /^[A-Za-z_.][\w.$]*:/.test(text)
}

/**
 * asm: 裸词类别。
 * 行首词一定是助记符 —— arm64 的 `bl`/`al` 与 x86 的 8 位寄存器别名同名，
 * 只看词本身会打架，位置信息才是判据。
 */
export function asmWordKind(w, atLineStart) {
  if (atLineStart) return "keyword"
  if (ASM_REG.test(w)) return "variableName"
  return "keyword"
}

/** asm: 注释起始（; // @ # 但 # 后面跟数字是立即数） */
export function asmIsCommentStart(s, i) {
  const c = s[i]
  if (c === ";" || c === "@") return true
  if (c === "/" && s[i + 1] === "/") return true
  if (c === "#" && !/[0-9]/.test(s[i + 1] || "")) return true
  return false
}

/**
 * 缩进折叠：返回可折叠到的结束行下标（0 基），无块返回 -1。
 * 空行跳过；遇到缩进 <= 基准行缩进即结束。
 * lineCount / getLine 做成参数，是因为 CM 的 doc 不适合整篇展开成数组（大文件）。
 */
export function indentFoldEnd(lineCount, getLine, start) {
  const first = getLine(start)
  if (!first.trim()) return -1
  const base = (/^\s*/.exec(first) || [""])[0].length
  let end = -1
  for (let i = start + 1; i < lineCount; i++) {
    const t = getLine(i)
    if (!t.trim()) continue
    const ind = (/^\s*/.exec(t) || [""])[0].length
    if (ind <= base) break
    end = i
  }
  return end
}

/** Kotlin 侧发来的 diff 位置计算：求最小替换区间（前缀/后缀裁剪） */
export function minDiff(oldText, newText) {
  let p = 0
  const ol = oldText.length, nl = newText.length
  const max = Math.min(ol, nl)
  while (p < max && oldText.charCodeAt(p) === newText.charCodeAt(p)) p++
  let s = 0
  while (s < max - p && oldText.charCodeAt(ol - 1 - s) === newText.charCodeAt(nl - 1 - s)) s++
  return { prefix: p, suffix: s, insert: newText.substring(p, nl - s) }
}

/**
 * 把 CM 的 iterChanges 结果归一化成「按顺序应用」的坐标。
 * iterChanges 给的是**变更前**的坐标，Kotlin 侧要按顺序往 StringBuilder 上应用，
 * 所以第 n 段的坐标必须加上前 n-1 段的长度变化，否则粘贴多行/批量替换时镜像会错位。
 */
export function normalizeChanges(raw) {
  let delta = 0
  const out = []
  for (const c of raw) {
    const ins = c.insert == null ? "" : String(c.insert)
    out.push({ from: c.fromA + delta, to: c.toA + delta, insert: ins })
    delta += ins.length - (c.toA - c.fromA)
  }
  return out
}

/** 按 normalizeChanges 的结果顺序应用到旧文本（Kotlin 侧 EditorDoc 的等价实现，用于对拍）。 */
export function applyChanges(text, changes) {
  let out = text
  for (const c of changes) {
    out = out.substring(0, c.from) + c.insert + out.substring(c.to)
  }
  return out
}
