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
    fun lineOf(text: String, offset: Int): Int {
        // 不能用 substring(0, offset)：2 MB 文本里光标靠后时一次调用就分配 2 MB 前缀，
        // 而 selectionLineRange 会连调两次。逐字符扫描同样是 O(offset)，但零分配。
        val end = offset.coerceIn(0, text.length)
        var line = 1
        var i = 0
        while (i < end) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }

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
        // 原来调两次 lineOf = 两次从头扫到光标；这里一次扫描同时拿到两个行号。
        val len = text.length
        val a = selStart.coerceIn(0, len)
        val b = selEnd.coerceIn(0, len)
        var line = 1
        var lineA = 1
        var i = 0
        while (i < b) {
            if (text[i] == '\n') line++
            if (i + 1 == a) lineA = line
            i++
        }
        return lineA to line
    }

    /**
     * 行区间变换：对 [from, to] 内每行应用 f(line, index)，光标落在 from 行行首。
     *
     * 只替换目标行、其余原样拷贝：原来 split 全篇再 joinToString，
     * 2 MB 文件做一次「缩进 3 行」也要分配几十万个 String（两份）。
     */
    fun transformLines(text: String, from: Int, to: Int, f: (String, Int) -> String): Pair<String, Int> {
        if (text.isEmpty()) {
            // 与原实现等价：空文本只有一行（索引 0），f 可能返回非空内容
            val nt = f("", 0)
            return nt to (if (from <= 1) 0 else nt.length)
        }
        var total = 1
        for (c in text) if (c == '\n') total++
        val lo = from.coerceIn(1, total)
        val hi = to.coerceIn(1, total)
        val a = minOf(lo, hi)
        val b = maxOf(lo, hi)
        val sb = StringBuilder(text.length + 64)
        var line = 1
        var i = 0
        var caret = -1
        while (true) {
            val nl = text.indexOf('\n', i)
            val end = if (nl < 0) text.length else nl
            if (line == from) caret = sb.length
            if (line in a..b) sb.append(f(text.substring(i, end), line - 1)) else sb.append(text, i, end)
            if (nl < 0) break
            sb.append('\n')
            i = nl + 1
            line++
        }
        // 光标语义与原实现一致：from 越界（> 总行数）时落到文本末尾
        return sb.toString() to (if (caret >= 0) caret else if (from <= 1) 0 else sb.length)
    }

    /**
     * 行排序：对选区覆盖的行（无选区则整篇）按字典序排序（对标 Xed-Editor 的 line sorting commands）。
     * @param desc 降序
     * @param caseSensitive 是否区分大小写（默认不区分，更符合直觉）
     * @return (新文本, 新光标偏移)——光标落在排序区间首行行首
     */
    fun sortLines(
        text: String,
        selStart: Int,
        selEnd: Int,
        desc: Boolean,
        caseSensitive: Boolean = false,
    ): Pair<String, Int> {
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val lines = text.split("\n")
        val a = (from - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val b = to.coerceIn(1, lines.size)
        if (b - a < 2) return text to selStart
        val slice = lines.subList(a, b).toList()
        val cmp = if (caseSensitive) compareBy<String> { it } else compareBy<String> { it.lowercase() }
        val sorted = slice.sortedWith(if (desc) cmp.reversed() else cmp)
        val mutable = lines.toMutableList()
        for (i in sorted.indices) mutable[a + i] = sorted[i]
        val nt = mutable.joinToString("\n")
        return nt to lineStartOffset(nt, a + 1)
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

    /**
     * 多行同列插入 —— 「列选择 / 多光标」在单 BasicTextField 里的可行形态。
     *
     * 真正的多光标要自绘布局并每帧重建 OffsetMapping，代价极高；而行编辑里绝大多数需求是
     * 「这几行同一个位置各插一段同样的东西」（批量加属性、批量加修饰符、批量加前缀）。
     * 这里做成纯函数：对选区覆盖的每一行，按同一规则插入同一段文本。
     *
     * @param atEnd true = 追加到每行行尾（行尾空白之前）
     * @param column >= 0 时忽略 atEnd，固定插在第 column 个字符处（短行用空格补齐）——
     *              这才是严格意义的列编辑，需要视觉对齐时用
     * @param skipBlank 跳过空行（默认 true：空行插入会留下一排孤儿符号）
     * @return (新文本, 新光标偏移)——光标落在改动区间首行行首
     */
    fun insertAtColumn(
        text: String,
        selStart: Int,
        selEnd: Int,
        ins: String,
        atEnd: Boolean,
        column: Int = -1,
        skipBlank: Boolean = true,
    ): Pair<String, Int> {
        if (ins.isEmpty()) return text to selStart
        val lines = text.split('\n')
        if (lines.isEmpty()) return text to selStart
        val (from, to) = selectionLineRange(text, selStart, selEnd)
        val lo = (minOf(from, to) - 1).coerceIn(0, lines.size - 1)
        val hi = (maxOf(from, to) - 1).coerceIn(0, lines.size - 1)
        if (lo > hi) return text to selStart

        val out = lines.toMutableList()
        for (i in lo..hi) {
            val ln = out[i]
            if (skipBlank && ln.isBlank()) continue
            out[i] = when {
                column >= 0 -> {
                    val cut = minOf(column, ln.length)
                    val pad = if (ln.length < column) " ".repeat(column - ln.length) else ""
                    ln.substring(0, cut) + pad + ins + ln.substring(cut)
                }
                // 行尾：插在原行尾空白之前，保持原来的对齐尾巴
                atEnd -> {
                    val body = ln.trimEnd()
                    body + ins + ln.substring(body.length)
                }
                // 缩进之后：不动缩进，批量加注解/修饰符就是这个位置
                else -> {
                    var k = 0
                    while (k < ln.length && (ln[k] == ' ' || ln[k] == '\t')) k++
                    ln.substring(0, k) + ins + ln.substring(k)
                }
            }
        }
        val nt = out.joinToString("\n")
        return nt to lineStartOffset(nt, lo + 1)
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
     * 大小写不敏感的子串查找（不重叠）。
     *
     * 原来这里写的是 `findAll(text.lowercase(), query.lowercase())`：
     * ① 2 MB 文本每次查找都要分配一份完整的小写副本；
     * ② `lowercase()` 可能改变字符串长度（如土耳其语 İ → i̇），
     *    返回的偏移就与原文对不上，跳转/替换会落到错误位置。
     * 逐字符比较零分配，且偏移始终基于原文。
     */
    fun findAllIgnoreCase(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        val n = text.length
        val m = q.length
        if (m > n) return emptyList()
        val out = ArrayList<Int>()
        var i = 0
        while (i <= n - m) {
            var k = 0
            while (k < m && text[i + k].lowercaseChar() == q[k]) k++
            if (k == m) {
                out.add(i)
                i += m
            } else {
                i++
            }
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
    /** 清理行尾空白：单遍扫描，不 split 全篇（大文件下省两份行数组）。 */
    fun trimTrailing(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (true) {
            val nl = text.indexOf('\n', i)
            val end = if (nl < 0) text.length else nl
            var e = end
            while (e > i && text[e - 1].isWhitespace()) e--
            sb.append(text, i, e)
            if (nl < 0) break
            sb.append('\n')
            i = nl + 1
        }
        return sb.toString()
    }

    /**
     * 从 [start] 处的 '<' 往后找标签结束的 '>'，跳过引号内的 '>'。
     *
     * 直接用 indexOf('>') 会在 `<a b="x > y">` 这种合法属性值上切错位置，
     * 把标签截成半截——格式化结果看起来"少了个括号"。
     */
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

    /**
     * 规整标签：属性之间压成一个空格、`<d />` → `<d/>`、`<a b="1" >` → `<a b="1">`。
     *
     * 不能用「对整串做 replace」——那样 `b="x > y"` 里的空格会被当成结构空白压掉
     * （`.replace(" >", ">")` 会把它变成 `x> y`）。所以先拆出属性，只规整属性名与分隔，
     * 属性值原样保留（引号、内部空白都不碰）。
     */
    private fun normalizeTag(tag: String): String {
        val parsed = splitTag(tag)
        if (parsed == null) return tag
        val (name, attrs) = parsed
        val selfClose = tag.trimEnd().endsWith("/>")
        val sb = StringBuilder("<").append(name)
        for (a in attrs) {
            val eq = a.indexOf('=')
            if (eq > 0) {
                sb.append(' ').append(a.substring(0, eq).trim()).append('=').append(a.substring(eq + 1))
            } else {
                sb.append(' ').append(a)
            }
        }
        if (selfClose) sb.append("/>") else sb.append(">")
        return sb.toString()
    }

    /**
     * 拆出标签名与属性列表（引号内的空白不拆）。
     *
     * 返回 null 表示这不是一个可拆的开标签（如 `</a>`、`<?pi?>`），调用方按单行原样输出。
     */
    private fun splitTag(tag: String): Pair<String, List<String>>? {
        if (!tag.startsWith("<") || !tag.endsWith(">")) return null
        if (tag.startsWith("</") || tag.startsWith("<!") || tag.startsWith("<?")) return null
        var inner = tag.substring(1, tag.length - 1)
        if (inner.endsWith("/")) inner = inner.dropLast(1)
        val name = inner.takeWhile { !it.isWhitespace() }
        if (name.isEmpty()) return null
        val rest = inner.drop(name.length)
        val attrs = ArrayList<String>()
        val sb = StringBuilder()
        var quote = ' '
        for (ch in rest) {
            if (quote != ' ') {
                sb.append(ch)
                if (ch == quote) quote = ' '
            } else if (ch == '"' || ch == '\'') {
                quote = ch
                sb.append(ch)
            } else if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    attrs.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) attrs.add(sb.toString())
        return name to attrs
    }

    /**
     * XML / HTML 缩进格式化（纯 Kotlin，零依赖，可单测）。
     *
     * 只做「缩进 + 换行」这一件事：不动属性顺序、不合并自闭合、不补全缺失标签。
     * 结构与 [diagnose][EditorCodeIntel.diagnose] 的 XML 检查对齐——返回 null 表示结构不完整
     * （标签未闭合、注释/CDATA 未闭合），此时不该把半截结果写回文件。
     *
     * @param unit 缩进单位
     * @param keepBlankLines 是否保留原有空行（默认丢弃，格式化后更紧凑）
     */
    fun formatXml(text: String, unit: String = "  ", keepBlankLines: Boolean = false): String? {
        val out = StringBuilder()
        var depth = 0
        var i = 0
        val n = text.length

        fun emit(s: String, d: Int) {
            if (s.isEmpty()) return
            if (out.isNotEmpty()) {
                if (!keepBlankLines) {
                    // 压缩连续换行
                    while (out.isNotEmpty() && out[out.length - 1] == '\n') out.setLength(out.length - 1)
                }
                if (out.isNotEmpty()) out.append('\n')
            }
            out.append(unit.repeat(d.coerceAtLeast(0))).append(s)
        }

        // 多行标签输出：属性多或原本就跨行的标签，每个属性一行（APK 解出来的 manifest 就是这种形态，
        // 压成一行反而更难读）。
        fun emitOpenTag(tag: String, depth: Int, selfClose: Boolean) {
            val parsed = splitTag(tag)
            if (parsed == null) {
                emit(normalizeTag(tag), depth)
                return
            }
            val (name, attrs) = parsed
            val total = name.length + attrs.sumOf { it.length + 1 }
            val multiline = tag.contains('\n') || total > 60
            if (attrs.isEmpty() || !multiline) {
                emit(normalizeTag(tag), depth)
                return
            }
            emit("<" + name, depth)
            attrs.forEachIndexed { idx, a ->
                out.append('\n').append(unit.repeat(depth)).append(unit).append(a)
                if (idx == attrs.lastIndex) out.append(if (selfClose) "/>" else ">")
            }
        }

        while (i < n) {
            if (text[i] != '<') {
                val end = text.indexOf('<', i).let { if (it < 0) n else it }
                val t = text.substring(i, end).trim()
                if (t.isNotEmpty()) emit(t, depth)
                i = end
                continue
            }
            // 注释 / CDATA / 处理指令 / DOCTYPE：整体保留
            if (text.startsWith("<!--", i)) {
                val e = text.indexOf("-->", i)
                if (e < 0) return null
                emit(text.substring(i, e + 3), depth)
                i = e + 3
                continue
            }
            if (text.startsWith("<![CDATA[", i)) {
                val e = text.indexOf("]]>", i)
                if (e < 0) return null
                emit(text.substring(i, e + 3), depth)
                i = e + 3
                continue
            }
            if (text.startsWith("<?", i) || text.startsWith("<!", i)) {
                val e = findTagEnd(text, i)
                if (e < 0) return null
                emit(text.substring(i, e + 1), depth)
                i = e + 1
                continue
            }
            val gt = findTagEnd(text, i)
            if (gt < 0) return null
            val tag = text.substring(i, gt + 1)
            val closing = tag.startsWith("</")
            val selfClose = tag.endsWith("/>")
            if (closing) {
                depth = (depth - 1).coerceAtLeast(0)
                emit(normalizeTag(tag), depth)
            } else {
                emitOpenTag(tag, depth, selfClose)
                if (!selfClose) depth++
            }
            i = gt + 1
        }
        // 结构性不平衡（未闭合）时不写回
        if (depth != 0) return null
        return out.toString()
    }
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
