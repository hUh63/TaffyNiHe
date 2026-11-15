package com.soreverse.mcp

/**
 * 编辑器「输入智能」层：把 sora-editor（官方 demo）里真正好用的那几件事，用纯 Kotlin 落到
 * 塔菲现有的 BasicTextField 架构上——自动配对、闭括号跳过、回车继承缩进、闭括号回退缩进、
 * 括号匹配定位、以及轻量语法诊断。
 *
 * 为什么不直接引入 sora-editor：它是一整套自绘 View（自带渲染、布局、IME 适配、tree-sitter
 * 绑定与 native .so），换掉等于把现有编辑器（多标签、快照、差异标记、命令系统、扩展注入）
 * 全部重写一遍；而用户真正感知到的差异其实集中在「打字手感」和「错误提示」这两处。
 *
 * 所以这里只吸收行为，不吸收实现。所有函数都是纯文本进 / 纯文本出，可在 JVM 上单测。
 */
internal object EditorCodeIntel {

    /** 一条诊断（offset 是文本偏移，line 是 1-based 行号）。 */
    data class Diag(val offset: Int, val line: Int, val message: String)

    /** 一对配对的括号偏移。 */
    data class BracketMatch(val open: Int, val close: Int)

    /** 输入变换结果：新文本 + 新光标（以及可选的选区尾，用于「包裹选区」）。 */
    data class ApplyResult(val text: String, val caret: Int, val selectionEnd: Int = caret)

    /** 开括号 → 闭括号。 */
    private val PAIR = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')

    /** 选区包裹的上限：字符数与行数。超过就按默认行为（替换选区）处理。 */
    private const val MAX_SURROUND_LEN = 4_000
    private const val MAX_SURROUND_LINES = 20

    /** 这些字符出现在「下一个字符」位置时，说明自动补右括号不会打断已有结构。 */
    private const val SAFE_NEXT = ")]},;:>"

    private fun lineStartOf(text: String, offset: Int): Int {
        val i = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
        return if (i < 0) 0 else i + 1
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /**
     * 处理「刚输入的一个字符」。
     *
     * @param text 输入前的整篇文本
     * @param selStart/selEnd 输入前的选区（无选区时相等）
     * @param typed 输入内容，只有长度 1 才处理
     * @param unit 缩进单位（"    " 或 "\t"）
     * @param autoPair 是否自动补右括号
     * @param autoIndent 是否回车继承缩进 / 闭括号回退缩进
     * @return 需要改写的 (文本, 光标)，返回 null 表示按默认行为处理
     */
    fun applyTyped(
        text: String,
        selStart: Int,
        selEnd: Int,
        typed: String,
        unit: String,
        autoPair: Boolean = true,
        autoIndent: Boolean = true,
    ): ApplyResult? {
        if (typed.length != 1) return null
        val c = typed[0]
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(0, text.length)
        val lo = minOf(a, b)
        val hi = maxOf(a, b)

        if (c == '\n') {
            if (!autoIndent) return null
            return onEnter(text, lo, hi, unit)
        }

        // 输入闭括号而下一个字符正好就是它：只移动光标，文本一个字都不动（这是「跳过」的手感来源）。
        // 注意不能走「插入」路径——那会把已有的 ')' 变成 '))'。
        if (lo == hi && hi < text.length && text[hi] == c &&
            (c == ')' || c == ']' || c == '}' || c == '"' || c == '\'')
        ) {
            return ApplyResult(text, hi + 1)
        }

        // 闭括号回退缩进：整行只有空白，且缩进里至少有一级，就退一级再插
        if (autoIndent && (c == '}' || c == ']' || c == ')')) {
            val lineStart = lineStartOf(text, lo)
            val before = text.substring(lineStart, lo)
            if (before.isNotEmpty() && before.all { it == ' ' || it == '\t' } && before.startsWith(unit)) {
                val newBefore = before.removePrefix(unit)
                return ApplyResult(
                    text.substring(0, lineStart) + newBefore + typed + text.substring(hi),
                    lineStart + newBefore.length + 1,
                )
            }
            return null
        }

        if (!autoPair) return null
        val close = PAIR[c] ?: return null

        // 有选区：用括号把选区包起来（surround 行为），光标留在闭合符之后。
        // 但只对「短选区」这么做——全选后输入一个 `(` 的本意是替换全文，
        // 若也包裹，整个文件会被一对括号包住，这是最难纠正的一类误操作。
        if (lo != hi) {
            val sel = text.substring(lo, hi)
            val multiline = sel.count { it == '\n' }
            if (sel.length > MAX_SURROUND_LEN || multiline > MAX_SURROUND_LINES) return null
            val nt = text.substring(0, lo) + c + sel + close + text.substring(hi)
            val pos = lo + 1 + sel.length + 1
            return ApplyResult(nt, pos, pos)
        }

        // 自动补右：后面是行尾/空白/既有闭合符时才补，否则会破坏已有结构（如 `(a+b)` 里输入 `(`）
        val nextOk = hi >= text.length || text[hi].isWhitespace() || SAFE_NEXT.indexOf(text[hi]) >= 0
        if (!nextOk) return null
        return ApplyResult(text.substring(0, lo) + c + close + text.substring(hi), lo + 1)
    }

    /** 回车：继承当前行缩进；若在 `{` 之后则多一级；若正好在 `{}` 中间则展开成三行。 */
    fun onEnter(text: String, selStart: Int, selEnd: Int, unit: String): ApplyResult {
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(0, text.length)
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val lineStart = lineStartOf(text, lo)
        val prefix = text.substring(lineStart, lo)
        val indent = prefix.takeWhile { it == ' ' || it == '\t' }
        val prev = if (lo > 0) text[lo - 1] else ' '
        val next = if (hi < text.length) text[hi] else ' '
        val deeper = prev == '{' || prev == '[' || prev == '(' || prev == ':'
        val newIndent = indent + if (deeper) unit else ""

        // `{|}` 中间回车 → 变成两行（中间行缩进一级，闭合符留在原缩进）
        if ((prev == '{' && next == '}') || (prev == '[' && next == ']') || (prev == '(' && next == ')')) {
            val insert = "\n" + newIndent + "\n" + indent
            val nt = text.substring(0, lo) + insert + text.substring(hi)
            return ApplyResult(nt, lo + 1 + newIndent.length)
        }
        val insert = "\n" + newIndent
        val nt = text.substring(0, lo) + insert + text.substring(hi)
        return ApplyResult(nt, lo + insert.length)
    }

    /**
     * 光标处（含紧挨光标左右）的括号配对。
     *
     * 只处理 () [] {}；引号不参与高亮（成对引号在长文本里匹配噪音太大）。
     */
    fun matchBracket(text: String, caret: Int): BracketMatch? {
        val pos = caret.coerceIn(0, text.length)
        // 光标左边是开括号，或右边是闭括号 → 从这一对开始找
        if (pos > 0 && text[pos - 1] in "([{") {
            val close = findForward(text, pos - 1) ?: return null
            return BracketMatch(pos - 1, close)
        }
        if (pos < text.length && text[pos] in ")]}") {
            val open = findBackward(text, pos) ?: return null
            return BracketMatch(open, pos)
        }
        return null
    }

    private fun openerOf(ch: Char): Char = when (ch) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> ' '
    }

    private fun findForward(text: String, openIdx: Int): Int? {
        val open = text[openIdx]
        val close = PAIR[open] ?: return null
        var depth = 0
        var i = openIdx
        var inStr = false
        var strCh = ' '
        while (i < text.length) {
            val ch = text[i]
            if (inStr) {
                if (ch == '\\') { i += 2; continue }
                if (ch == strCh) inStr = false
            } else {
                if (ch == '"' || ch == '\'') { inStr = true; strCh = ch }
                else if (ch == open) depth++
                else if (ch == close) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun findBackward(text: String, closeIdx: Int): Int? {
        val close = text[closeIdx]
        val open = openerOf(close)
        if (open == ' ') return null
        var depth = 0
        var i = closeIdx
        while (i >= 0) {
            val ch = text[i]
            if (ch == close) depth++
            else if (ch == open) {
                depth--
                if (depth == 0) return i
            }
            i--
        }
        return null
    }

    /**
     * 轻量诊断：只报「结构性」问题（括号不平衡、JSON 字符串未闭合、XML 标签不匹配）。
     *
     * 不做语法层面的深判：编辑器里的诊断要么快且准，要么就把用户当审阅器——误报比不报更烦。
     * 因此只覆盖这四类，且最多返回 [max] 条。
     */
    fun diagnose(text: String, mode: String, max: Int = 20): List<Diag> {
        val out = ArrayList<Diag>()
        val m = mode.uppercase()
        val json = m == "JSON"
        val xml = m == "XML"
        if (json) {
            scanJson(text, out, max)
        } else if (xml) {
            scanXml(text, out, max)
        } else {
            scanBrackets(text, out, max)
        }
        return out
    }

    private fun addDiag(out: MutableList<Diag>, text: String, offset: Int, msg: String, max: Int) {
        if (out.size >= max) return
        out.add(Diag(offset, lineOf(text, offset), msg))
    }

    private fun scanBrackets(text: String, out: MutableList<Diag>, max: Int) {
        val stack = ArrayList<Pair<Char, Int>>()
        var inStr = false
        var strCh = ' '
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inStr) {
                if (ch == '\\') { i += 2; continue }
                if (ch == strCh) inStr = false
            } else {
                when (ch) {
                    '"', '\'' -> { inStr = true; strCh = ch }
                    '(', '[', '{' -> stack.add(ch to i)
                    ')', ']', '}' -> {
                        val top = stack.lastOrNull()
                        if (top == null || openerOf(ch) != top.first) {
                            addDiag(out, text, i, "多余的 " + ch, max)
                        } else {
                            stack.removeAt(stack.size - 1)
                        }
                    }
                }
            }
            i++
        }
        if (inStr) addDiag(out, text, text.length, "字符串未闭合", max)
        for ((ch, idx) in stack.takeLast(4)) {
            addDiag(out, text, idx, "未闭合的 " + ch, max)
        }
    }

    private fun scanJson(text: String, out: MutableList<Diag>, max: Int) {
        val stack = ArrayList<Pair<Char, Int>>()
        var inStr = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inStr) {
                if (ch == '\\') { i += 2; continue }
                if (ch == '"') inStr = false
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{', '[' -> stack.add(ch to i)
                    '}', ']' -> {
                        val top = stack.lastOrNull()
                        if (top == null) addDiag(out, text, i, "多余的 " + ch, max)
                        else if (openerOf(ch) != top.first) addDiag(out, text, i, "括号不匹配：期望闭合 " + PAIR[top.first], max)
                        else stack.removeAt(stack.size - 1)
                    }
                }
            }
            i++
        }
        if (inStr) addDiag(out, text, text.length, "字符串未闭合（少了引号）", max)
        for ((ch, idx) in stack.takeLast(4)) {
            addDiag(out, text, idx, "未闭合的 " + ch + "（缺少 " + PAIR[ch] + "）", max)
        }
    }

    /** 标签结束的 '>'，跳过引号内的 `>`（属性值里写 `>` 是合法的）。 */
    private fun findTagEnd(text: String, start: Int): Int {
        var j = start + 1
        var quote = ' '
        while (j < text.length) {
            val ch = text[j]
            if (quote != ' ') {
                if (ch == quote) quote = ' '
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '>') {
                return j
            }
            j++
        }
        return -1
    }

    private fun scanXml(text: String, out: MutableList<Diag>, max: Int) {
        val stack = ArrayList<Pair<String, Int>>()
        var i = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            if (text.startsWith("<!--", lt)) {
                val end = text.indexOf("-->", lt + 4)
                if (end < 0) { addDiag(out, text, lt, "注释未闭合", max); break }
                i = end + 3
                continue
            }
            if (text.startsWith("<![CDATA[", lt)) {
                val end = text.indexOf("]]>", lt + 9)
                if (end < 0) { addDiag(out, text, lt, "CDATA 未闭合", max); break }
                i = end + 3
                continue
            }
            if (lt + 1 < n && (text[lt + 1] == '?' || text[lt + 1] == '!')) {
                val end = findTagEnd(text, lt)
                if (end < 0) break
                i = end + 1
                continue
            }
            val gt = findTagEnd(text, lt)
            if (gt < 0) { addDiag(out, text, lt, "标签未闭合（缺少 >）", max); break }
            var inner = text.substring(lt + 1, gt).trim()
            val selfClose = inner.endsWith("/")
            if (selfClose) inner = inner.dropLast(1).trim()
            if (inner.startsWith("/")) {
                val name = inner.drop(1).trim().substringBefore(' ')
                val top = stack.lastOrNull()
                if (top == null) {
                    addDiag(out, text, lt, "多余的结束标签 </" + name + ">", max)
                } else if (top.first != name) {
                    addDiag(out, text, lt, "标签不匹配：<" + top.first + "> 被 </" + name + "> 关闭", max)
                    stack.removeAt(stack.size - 1)
                } else {
                    stack.removeAt(stack.size - 1)
                }
            } else if (!selfClose && inner.isNotEmpty()) {
                val name = inner.substringBefore(' ').substringBefore('/')
                stack.add(name to lt)
            }
            i = gt + 1
        }
        for ((name, idx) in stack.takeLast(4)) {
            addDiag(out, text, idx, "<" + name + "> 未闭合", max)
        }
    }

    /**
     * 当前光标所在「块」的范围（用于替代真折叠：真折叠要改写显示文本，会破坏
     * BasicTextField 的 OffsetMapping；「跳到块末尾 / 选中整块」能提供同样的导航价值而不动文本）。
     *
     * @return (startOffset, endOffset)，没有包围块时返回 null
     */
    fun enclosingBlock(text: String, caret: Int): Pair<Int, Int>? {
        val pos = caret.coerceIn(0, text.length)
        // 从光标往回找最近的未配对开括号，再向后找它的闭合
        var i = pos - 1
        var depth = 0
        while (i >= 0) {
            val ch = text[i]
            when (ch) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> {
                    if (depth == 0) {
                        val close = findForward(text, i)
                        if (close != null) return i to close
                    } else {
                        depth--
                    }
                }
            }
            i--
        }
        return null
    }
}
