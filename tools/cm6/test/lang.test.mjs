import test from "node:test"
import assert from "node:assert"
import {
  smaliFoldEnd, smaliWordKind, asmIsLabelLine, asmStartsLabel, asmIsCommentStart,
  asmWordKind, ASM_REG, indentFoldEnd, minDiff,
} from "../src/lang.js"

test("smali 折叠块识别", () => {
  assert.ok(smaliFoldEnd(".method public static main([Ljava/lang/String;)V"))
  assert.ok(smaliFoldEnd("  .method private foo()V"), "允许前导空白")
  assert.ok(smaliFoldEnd(".annotation system Ldalvik/annotation/Throws;"))
  assert.ok(smaliFoldEnd(".array-data 4"))
  assert.equal(smaliFoldEnd(".end method"), null, "结束行不能当起点")
  assert.equal(smaliFoldEnd("invoke-static {v0}, Ljava/lang/System;->exit(I)V"), null)
  assert.equal(smaliFoldEnd("# .method in comment"), null, "注释里的不算")
})

test("smali 词法类别", () => {
  assert.equal(smaliWordKind("invoke-static"), "keyword")
  assert.equal(smaliWordKind("if-eqz"), "keyword")
  assert.equal(smaliWordKind("p0"), "variableName")
  assert.equal(smaliWordKind("v12"), "variableName")
  // 未收录的伪指令不应崩
  assert.equal(smaliWordKind("some-new-op"), "variableName")
})

test("asm 标签与注释", () => {
  assert.equal(asmIsLabelLine("loop:"), true)
  assert.equal(asmIsLabelLine("_start:"), true)
  assert.equal(asmIsLabelLine("loop: mov x0, #1"), false)
  assert.equal(asmIsLabelLine("  indented:"), false)
  assert.equal(asmStartsLabel("loop: mov x0, x1"), true)
  assert.equal(asmStartsLabel("  mov x0, x1"), false)
  assert.equal(asmIsCommentStart("; comment", 0), true)
  assert.equal(asmIsCommentStart("@ comment", 0), true)
  assert.equal(asmIsCommentStart("// comment", 0), true)
  assert.equal(asmIsCommentStart("# comment", 0), true)
  assert.equal(asmIsCommentStart("#0x10", 0), false, "# 后跟数字是立即数不是注释")
})

test("asm 寄存器与关键字", () => {
  for (const r of ["x0", "x29", "w0", "sp", "lr", "pc", "xzr", "rax", "rsp", "al"]) {
    assert.ok(ASM_REG.test(r), r)
    assert.equal(asmWordKind(r, false), "variableName")
  }
  for (const k of ["mov", "bl", "stp", "ldr", "cbz", "b.eq"]) {
    assert.equal(asmWordKind(k, true), "keyword", k + " 行首")
  }
  // 同名歧义：行首是助记符，操作数位置是寄存器
  assert.equal(asmWordKind("bl", true), "keyword")
  assert.equal(asmWordKind("bl", false), "variableName")
  assert.equal(ASM_REG.test("x31"), false, "arm64 没有 x31")
  assert.equal(ASM_REG.test("foo"), false)
})

test("缩进折叠", () => {
  const lines = [
    "if (a) {",       // 0
    "    b()",        // 1
    "    if (c) {",   // 2
    "        d()",    // 3
    "    }",          // 4
    "}",              // 5
    "",               // 6
    "tail",           // 7
  ]
  const get = (i) => lines[i]
  assert.equal(indentFoldEnd(lines.length, get, 0), 4, "到缩进回到 0 的 `}` 前一行")
  assert.equal(indentFoldEnd(lines.length, get, 2), 3)
  assert.equal(indentFoldEnd(lines.length, get, 1), -1, "单行无块")
  assert.equal(indentFoldEnd(lines.length, get, 6), -1, "空行无块")
  assert.equal(indentFoldEnd(lines.length, get, 7), -1, "末行无块")
})

test("最小差异替换", () => {
  assert.deepEqual(minDiff("abc", "abc"), { prefix: 3, suffix: 0, insert: "" })
  assert.deepEqual(minDiff("abcdef", "abcXef"), { prefix: 3, suffix: 2, insert: "X" })
  assert.deepEqual(minDiff("", "abc"), { prefix: 0, suffix: 0, insert: "abc" })
  assert.deepEqual(minDiff("abc", ""), { prefix: 0, suffix: 0, insert: "" })
  // 整行插入
  const a = "a\nb"
  const b = "a\nx\nb"
  const d = minDiff(a, b)
  assert.equal(a.substring(0, d.prefix) + d.insert + a.substring(a.length - d.suffix), b)
  // 前缀后缀裁剪不能重叠（同字符重复的场景）
  const x = "aaaa"
  const y = "aaaaa"
  const dd = minDiff(x, y)
  assert.ok(dd.prefix + dd.suffix <= y.length)
  assert.equal(x.substring(0, dd.prefix) + dd.insert + x.substring(x.length - dd.suffix), y)
})
