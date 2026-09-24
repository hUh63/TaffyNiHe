package com.soreverse.mcp

/**
 * 编辑器纯文本操作集（零 Android 依赖，可在 JVM 上单测）。
 *
 * 设计来源：Xed-Editor 的命令系统把所有编辑动作与 UI 解耦——动作只吃「文本 + 选区」，
 * 吐「新文本 + 新光标」。这里照此把原先散落在 composable 里的行编辑逻辑抽成纯函数，
 * 好处是：①可单测；②工具条 / ExtraKeys 键行 / 内联动作条三处渲染共用同一实现；
 * ③后续做命令面板、快捷键、可拖拽工具条都无需再碰 UI 代码。
 *
 * 约定：所有行号 1-based；选区用 (selStart, selEnd) 且保证 selStart <= selEnd；
 * 返回值为 (新文本, 新光标偏移)。
 */
internal object EditorTextOps {

    /** 偏移 → 行号（1-based）。 */
    fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** 列号（1-based，按 UTF-16 计数，与编辑器光标一致）。 */
    fun columnOf(text: String, offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        val nl = text.lastIndexOf('\n', (o - 1).coerceAtLeast(0))
        return o - (nl + 1) + 1
    }

    /** 行首偏移（越界时返回文本长度）。 */
    fun lineStartOffset(text: String, line: Int): Int {
        if (line <= 1) return 0
        var idx = 0
        var n = 1
        while (n < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) return text.length
            idx = nl + 1
            n++
        }
        return idx
    }

    /** 选区覆盖的行号区间（1-based，含端点）。 */
    fun selectionLineRange(text: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(0, text.length)
        return lineOf(text, a) to lineOf(text, b)
    }

    /** 行区间变换：对 [from, to] 内每行应用 f(line, index)，光标落在 from 行行首。 */
    fun transformLines(text: String, from: Int, to: Int, f: (String, Int) -> String): Pair<String, Int> {
        val lines = text.split('\n')
        if (lines.isEmpty()) return text to 0
        val a = (from - 1).coerceIn(0, lines.size - 1)
        val b = (to - 1).coerceIn(0, lines.size - 1)
        val mutable = lines.toMutableList()
        for (i in minOf(a, b)..maxOf(a, b)) mutable[i] = f(mutable[i], i)
        val nt = mutable.joinToString("\n")
        return nt to lineStartOffset(nt, from.coerceAtLeast(1))
    }

    /** 缩进 / 取消缩进选中行。 */
    fun indent(text: String, selStart: Int, selEnd: Int, outdent: Boolean, useTab: Boolean): Pair<String, Int> {
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val unit = if (useTab) "\t" else "    "
        return transformLines(text, from, to) { line, _ ->
            if (!outdent) unit + line
            else when {
                line.startsWith("\t") -> line.substring(1)
                line.startsWith("    ") -> line.substring(4)
                line.startsWith("  ") -> line.substring(2)
                line.startsWith(" ") -> line.substring(1)
                else -> line
            }
        }
    }

    /** 切换行注释（整块已注释则取消，否则添加）。prefix 形如 "# "、 "// "、 "<!-- "。 */
    fun toggleComment(text: String, selStart: Int, selEnd: Int, prefix: String): Pair<String, Int> {
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val lines = text.split('\n')
        val seg = (from - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            .let { s -> lines.drop(s).take((to - from + 1).coerceAtLeast(1)) }
        val bare = prefix.trim()
        val allCommented = seg.isNotEmpty() && seg.all { it.trimStart().startsWith(bare) }
        return transformLines(text, from, to) { line, _ ->
            if (allCommented) {
                val idx = line.indexOf(bare)
                val without = if (idx >= 0) line.substring(0, idx) + line.substring(idx + bare.length) else line
                if (without.startsWith(" ")) without.substring(1) else without
            } else {
                prefix + line
            }
        }
    }

    /** 上下移动选中行（dy = -1 上移 / +1 下移）；整段搬移，越界则原样返回。 */
    fun moveLines(text: String, selStart: Int, selEnd: Int, dy: Int): Pair<String, Int> {
        val lines = text.split('\n').toMutableList()
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val a = from - 1
        val b = to - 1
        if (a < 0 || b >= lines.size || a > b) return text to selStart
        val slice = lines.subList(a, b + 1).toList()
        if (dy < 0) {
            if (a == 0) return text to selStart
            repeat(b - a + 1) { lines.removeAt(a) }
            lines.addAll(a - 1, slice)
        } else {
            if (b >= lines.size - 1) return text to selStart
            repeat(b - a + 1) { lines.removeAt(a) }
            lines.addAll(a + 1, slice)
        }
        val nt = lines.joinToString("\n")
        return nt to lineStartOffset(nt, (from + dy).coerceAtLeast(1))
    }

    /** 复制选中行到下方。 */
    fun duplicateLines(text: String, selStart: Int, selEnd: Int): Pair<String, Int> {
        val lines = text.split('\n').toMutableList()
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val a = (from - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val b = to.coerceIn(1, lines.size)
        val slice = lines.subList(a, b).toList()
        lines.addAll(b, slice)
        val nt = lines.joinToString("\n")
        return nt to lineStartOffset(nt, from)
    }

    /** 删除选中行。 */
    fun deleteLines(text: String, selStart: Int, selEnd: Int): Pair<String, Int> {
        val lines = text.split('\n').toMutableList()
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val a = (from - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val b = (to - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        repeat(maxOf(b - a + 1, 1)) { if (a < lines.size) lines.removeAt(a) }
        if (lines.isEmpty()) lines.add("")
        val nt = lines.joinToString("\n")
        return nt to lineStartOffset(nt, from)
    }

    /** 选区大小写转换（无选区时原样返回）。 */
    fun changeCase(text: String, selStart: Int, selEnd: Int, upper: Boolean): Pair<String, Int> {
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(0, text.length)
        if (a == b) return text to a
        val seg = text.substring(minOf(a, b), maxOf(a, b))
        val rep = if (upper) seg.uppercase() else seg.lowercase()
        return (text.substring(0, a) + rep + text.substring(b)) to (a + rep.length)
    }

    /** 在选区处插入文本。 */
    fun insert(text: String, selStart: Int, selEnd: Int, ins: String): Pair<String, Int> {
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(0, text.length)
        return (text.substring(0, a) + ins + text.substring(b)) to (a + ins.length)
    }

    /** 退格删除（有选区则删除选区）。 */
    fun deleteBackward(text: String, selStart: Int, selEnd: Int): Pair<String, Int> {
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(0, text.length)
        return when {
            a != b -> (text.substring(0, a) + text.substring(b)) to a
            a > 0 -> (text.substring(0, a - 1) + text.substring(a)) to (a - 1)
            else -> text to 0
        }
    }

    /** 光标左右移动。 */
    fun moveCaret(text: String, selStart: Int, delta: Int): Int =
        (selStart + delta).coerceIn(0, text.length)

    /** 光标上下移动（尽量保持列位置）。 */
    fun moveCaretVertical(text: String, selStart: Int, dy: Int): Int {
        val s = selStart.coerceIn(0, text.length)
        val before = text.substring(0, s)
        val lineIdx = before.count { it == '\n' }
        val col = s - (before.lastIndexOf('\n') + 1)
        val lines = text.split('\n')
        val target = (lineIdx + dy).coerceIn(0, lines.size - 1)
        var off = 0
        for (i in 0 until target) off += lines[i].length + 1
        off += col.coerceAtMost(lines[target].length)
        return off.coerceIn(0, text.length)
    }

    /** 光标移到行首 / 行尾。 */
    fun moveCaretEdge(text: String, selStart: Int, toEnd: Boolean): Int {
        val s = selStart.coerceIn(0, text.length)
        val ls = text.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val le = text.indexOf('\n', s).let { if (it < 0) text.length else it }
        return if (toEnd) le else ls
    }

    /** 子串查找：返回全部匹配起始下标（不重叠）。 */
    fun findAll(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(query)
        while (i >= 0) {
            out.add(i)
            i = text.indexOf(query, i + query.length)
        }
        return out
    }

    /**
     * JSON 美化（对象或数组）；非法时返回 null。
     *
     * 不用 `org.json`：它的 `toString(2)` 在单元测试环境（mockable android.jar）不可用，
     * 且宽松模式对 `{oops}` 这类畸形输入仍会"成功"。这里自带一个严格的小解析器，
     * 顺便让格式化结果稳定可控。
     */
    fun formatJson(text: String): String? = JsonPretty(text).pretty()

    /** 清理行尾空白。 */
    fun trimTrailing(text: String): String = text.split('\n').joinToString("\n") { it.trimEnd() }
}

/** 轻量 JSON 校验 + 美化（纯 Kotlin，零依赖，可单测）。 */
private class JsonPretty(private val s: String) {
    private var i = 0
    private val sb = StringBuilder()

    fun pretty(): String? {
        skipWs()
        if (!value(0)) return null
        skipWs()
        return if (i == s.length) sb.toString() else null
    }

    private fun indent(depth: Int) = "  ".repeat(depth)

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(depth: Int): Boolean {
        if (i >= s.length) return false
        return when (s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> lit("true")
            'f' -> lit("false")
            'n' -> lit("null")
            else -> num()
        }
    }

    private fun obj(depth: Int): Boolean {
        sb.append('{'); i++; skipWs()
        if (i < s.length && s[i] == '}') { sb.append('}'); i++; return true }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') return false
            sb.append('\n').append(indent(depth + 1))
            str()
            skipWs()
            if (i >= s.length || s[i] != ':') return false
            i++
            sb.append(": ")
            skipWs()
            if (!value(depth + 1)) return false
            skipWs()
            if (i >= s.length) return false
            when (s[i]) {
                ',' -> { sb.append(','); i++ }
                '}' -> {
                    sb.append('\n').append(indent(depth)).append('}')
                    i++
                    return true
                }
                else -> return false
            }
        }
    }

    private fun arr(depth: Int): Boolean {
        sb.append('['); i++; skipWs()
        if (i < s.length && s[i] == ']') { sb.append(']'); i++; return true }
        while (true) {
            sb.append('\n').append(indent(depth + 1))
            skipWs()
            if (!value(depth + 1)) return false
            skipWs()
            if (i >= s.length) return false
            when (s[i]) {
                ',' -> { sb.append(','); i++ }
                ']' -> {
                    sb.append('\n').append(indent(depth)).append(']')
                    i++
                    return true
                }
                else -> return false
            }
        }
    }

    private fun str(): Boolean {
        if (i >= s.length || s[i] != '"') return false
        val start = i
        i++
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> {
                    i++
                    sb.append(s, start, i)
                    return true
                }
                else -> i++
            }
        }
        return false
    }

    private fun lit(name: String): Boolean {
        if (s.startsWith(name, i)) {
            sb.append(name)
            i += name.length
            return true
        }
        return false
    }

    private fun num(): Boolean {
        val start = i
        if (i < s.length && s[i] == '-') i++
        var digits = 0
        while (i < s.length && s[i].isDigit()) { i++; digits++ }
        if (digits == 0) { i = start; return false }
        if (i < s.length && s[i] == '.') {
            i++
            var frac = 0
            while (i < s.length && s[i].isDigit()) { i++; frac++ }
            if (frac == 0) { i = start; return false }
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var exp = 0
            while (i < s.length && s[i].isDigit()) { i++; exp++ }
            if (exp == 0) { i = start; return false }
        }
        sb.append(s, start, i)
        return true
    }
}
