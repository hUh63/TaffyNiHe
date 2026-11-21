/*
 * 编辑器词法着色（不依赖 Android / Compose，可直接单测）。
 *
 * 只做一件事：把文本切成「带类型的区间」，颜色由渲染层决定
 * （sora-editor 的 AnalyzeManager 把 Token 换成 Span；需要 AnnotatedString 的地方
 * 换成 SpanStyle）。这样同一份规则既能喂给编辑器，也能喂给预览面板。
 *
 * 设计原则：宁可漏标，不要错标 —— 认不出的字符一律不上色。原因很实际：
 * 手机上编辑 smali / 汇编时，一次错标就会让整段串成一色，比纯文本更难读。
 */
package com.soreverse.mcp.core

object EditorLexer {

    /** 语法家族（与 CodeHighlighter.Lang 一一对应，EXT 语法包走 PLAIN）。 */
    enum class Syntax { PYTHON, SHELL, JSON, SMALI, C, JAVA, XML, MARKDOWN, ASSEMBLY, PLAIN }

    /** Token 类型；渲染层按它取色。 */
    enum class Kind { KEYWORD, COMMENT, STRING, NUMBER, FUNC, TYPE, CONST, TAG, ATTR, DIRECTIVE, CODE }

    class Token(val start: Int, val end: Int, val kind: Kind) {
        override fun toString(): String = "$kind($start..$end)"
    }

    /** 跨行词法状态：块注释 / Python 三引号 / Markdown 栅栏 / XML 注释。 */
    class State {
        var blockComment = false
        var tripleQuote = false
        var tripleChar = '"'
        var mdFence = false
        var xmlComment = false
    }

    // ────────────────────────── 入口 ──────────────────────────

    /** 整篇切词（单测与大文件一次性分析用）。 */
    fun tokenize(text: String, syntax: Syntax): List<Token> {
        val out = ArrayList<Token>(text.length / 12 + 8)
        val st = State()
        var pos = 0
        while (pos <= text.length) {
            var nl = text.indexOf('\n', pos)
            if (nl < 0) nl = text.length
            scanLine(text, pos, nl, syntax, st, out)
            if (nl >= text.length) break
            pos = nl + 1
        }
        return out
    }

    /**
     * 切一行（不含行尾换行）。返回新列表，调用方按行号塞进 Span。
     * 之所以按行给，是为了让 AnalyzeManager 不必把整篇文本再复制一份。
     */
    fun tokenizeLine(line: String, syntax: Syntax, state: State): List<Token> {
        val out = ArrayList<Token>(16)
        scanLine(line, 0, line.length, syntax, state, out)
        return out
    }

    private fun scanLine(s: String, from: Int, to: Int, syn: Syntax, st: State, out: MutableList<Token>) {
        if (from >= to) return
        when (syn) {
            Syntax.PLAIN -> return
            Syntax.MARKDOWN -> scanMarkdown(s, from, to, st, out)
            Syntax.XML -> scanXml(s, from, to, st, out)
            else -> scanCode(s, from, to, syn, st, out)
        }
    }

    // ────────────────────────── 通用扫描 ──────────────────────────

    private fun scanCode(s: String, from: Int, to: Int, syn: Syntax, st: State, out: MutableList<Token>) {
        val kw = keywords(syn)
        val types = typeWords(syn)
        val consts = constWords(syn)
        val allowBlockComment = syn == Syntax.C || syn == Syntax.JAVA || syn == Syntax.JSON
        var i = from
        var firstCode = true
        while (i < to) {
            if (st.blockComment) {
                val close = s.indexOf("*/", i)
                if (close < 0 || close + 2 > to) {
                    add(out, i, to, Kind.COMMENT)
                    return
                }
                add(out, i, close + 2, Kind.COMMENT)
                st.blockComment = false
                i = close + 2
                continue
            }
            if (st.tripleQuote) {
                val mark = buildString { append(st.tripleChar); append(st.tripleChar); append(st.tripleChar) }
                val close = s.indexOf(mark, i)
                if (close < 0 || close + 3 > to) {
                    add(out, i, to, Kind.STRING)
                    return
                }
                add(out, i, close + 3, Kind.STRING)
                st.tripleQuote = false
                i = close + 3
                continue
            }

            val c = s[i]

            // 空白
            if (c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }

            // 行注释
            if (syncCommentStart(s, i, to, syn)) {
                add(out, i, to, Kind.COMMENT)
                break
            }

            // 块注释
            if (allowBlockComment && c == '/' && i + 1 < to && s[i + 1] == '*') {
                val close = s.indexOf("*/", i + 2)
                if (close < 0 || close + 2 > to) {
                    add(out, i, to, Kind.COMMENT)
                    st.blockComment = true
                    return
                }
                add(out, i, close + 2, Kind.COMMENT)
                i = close + 2
                continue
            }

            // Python 三引号（必须先于普通字符串判断）
            if (syn == Syntax.PYTHON && (c == '"' || c == '\'') && i + 2 < to &&
                s[i + 1] == c && s[i + 2] == c
            ) {
                val mark = buildString { append(c); append(c); append(c) }
                val close = s.indexOf(mark, i + 3)
                if (close < 0 || close + 3 > to) {
                    add(out, i, to, Kind.STRING)
                    st.tripleQuote = true
                    st.tripleChar = c
                    return
                }
                add(out, i, close + 3, Kind.STRING)
                i = close + 3
                continue
            }

            // 字符串（Shell 单引号内没有转义）
            if (c == '"' || c == '\'') {
                val escape = !(syn == Syntax.SHELL && c == '\'')
                val end = scanString(s, i, to, c, escape)
                if (end < 0) {
                    add(out, i, to, Kind.STRING)
                    i = to
                } else {
                    add(out, i, end, Kind.STRING)
                    i = end
                }
                firstCode = false
                continue
            }

            // Shell 变量 / 位置参数
            if (syn == Syntax.SHELL && c == '$') {
                var j = i + 1
                if (j < to && s[j] == '{') {
                    val close = s.indexOf('}', j)
                    j = if (close > j && close < to) close + 1 else to
                } else if (j < to && (s[j].isLetter() || s[j] == '_')) {
                    while (j < to && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                } else if (j < to && s[j].isDigit()) {
                    while (j < to && s[j].isDigit()) j++
                } else if (j < to && (s[j] == '@' || s[j] == '*' || s[j] == '#' || s[j] == '?' || s[j] == '$' || s[j] == '!')) {
                    j++
                }
                if (j > i + 1) {
                    add(out, i, j, Kind.CONST)
                    i = j
                    firstCode = false
                    continue
                }
            }

            // 数字（汇编的 #0x.. 立即数也算）
            if (c.isDigit() || (c == '#' && syn == Syntax.ASSEMBLY && i + 1 < to && s[i + 1].isDigit())) {
                val end = scanNumber(s, i, to)
                add(out, i, end, Kind.NUMBER)
                i = end
                firstCode = false
                continue
            }

            // Smali 指令（.method / .field ...）
            if (c == '.' && syn == Syntax.SMALI) {
                var j = i + 1
                while (j < to && (s[j].isLetterOrDigit() || s[j] == '-' || s[j] == '_')) j++
                if (j > i + 1) {
                    add(out, i, j, Kind.DIRECTIVE)
                    i = j
                    firstCode = false
                    continue
                }
            }

            // 标识符 / 关键字 / 类型 / 函数名 / 标签
            if (isIdentStart(c)) {
                var j = i + 1
                while (j < to && isIdentPart(s[j])) j++
                val word = s.substring(i, j)
                val kind: Kind? = when {
                    syn == Syntax.SMALI && isSmaliRegister(word) -> Kind.TYPE
                    kw.contains(word) -> Kind.KEYWORD
                    types.contains(word) -> Kind.TYPE
                    consts.contains(word) -> Kind.CONST
                    // 汇编：行首第一个词是助记符
                    syn == Syntax.ASSEMBLY && firstCode -> Kind.KEYWORD
                    else -> {
                        var k = j
                        while (k < to && (s[k] == ' ' || s[k] == '\t')) k++
                        when {
                            k < to && s[k] == '(' -> Kind.FUNC
                            // 标签（xxx: 且不是 xxx::）
                            k < to && s[k] == ':' && (k + 1 >= to || s[k + 1] != ':') -> Kind.FUNC
                            else -> null
                        }
                    }
                }
                if (kind != null) add(out, i, j, kind)
                i = j
                firstCode = false
                continue
            }

            i++
            firstCode = false
        }
    }

    private fun syncCommentStart(s: String, i: Int, to: Int, syn: Syntax): Boolean {
        val c = s[i]
        return when (syn) {
            // Python / Smali：任何位置的 # 都是注释
            Syntax.PYTHON, Syntax.SMALI -> c == '#'
            // Shell：只有行首或空白 / 分隔符之后的 # 才是注释（$# 是参数个数）
            Syntax.SHELL -> {
                if (c != '#') false else {
                    var p = i - 1
                    while (p >= 0 && (s[p] == ' ' || s[p] == '\t')) p--
                    p < 0 || s[p] == '\n' || s[p] == '\r' || s[p] == ';' || s[p] == '|' ||
                        s[p] == '&' || s[p] == '(' || s[p] == '`'
                }
            }
            // C / Java：//
            Syntax.C, Syntax.JAVA -> c == '/' && i + 1 < to && s[i + 1] == '/'
            // JSON：标准 JSON 没有注释，但配置文件里 // 很常见，容忍它
            Syntax.JSON -> c == '/' && i + 1 < to && s[i + 1] == '/'
            // 汇编：; 与 //。ARM/RISC-V 的 # 是立即数前缀，不能当注释
            Syntax.ASSEMBLY -> c == ';' || (c == '/' && i + 1 < to && s[i + 1] == '/')
            else -> false
        }
    }

    private fun scanString(s: String, quoteAt: Int, to: Int, quote: Char, escape: Boolean): Int {
        var j = quoteAt + 1
        while (j < to) {
            val ch = s[j]
            if (escape && ch == '\\') {
                j += 2
                continue
            }
            if (ch == quote) return j + 1
            j++
        }
        return -1
    }

    private fun scanNumber(s: String, i: Int, to: Int): Int {
        var j = i
        if (s[j] == '#') j++
        if (j + 1 < to && s[j] == '0' && (s[j + 1] == 'x' || s[j + 1] == 'X')) {
            j += 2
            while (j < to && (s[j].isDigit() || s[j].lowercaseChar() in 'a'..'f' || s[j] == '_')) j++
            return j
        }
        if (j + 1 < to && s[j] == '0' && (s[j + 1] == 'b' || s[j + 1] == 'o')) {
            j += 2
            while (j < to && (s[j].isDigit() || s[j] == '_')) j++
            return j
        }
        var dot = false
        while (j < to) {
            val ch = s[j]
            if (ch.isDigit() || ch == '_') {
                j++
                continue
            }
            if (ch == '.' && !dot) {
                dot = true
                j++
                continue
            }
            if ((ch == 'e' || ch == 'E') && j + 1 < to) {
                j++
                if (j < to && (s[j] == '+' || s[j] == '-')) j++
                continue
            }
            break
        }
        // 数值后缀：只在后面不是标识符字符时才算（避免把 1for 里的 f 吞掉）
        if (j < to && s[j] in "fFlLuUdD" && (j + 1 >= to || !isIdentPart(s[j + 1]))) j++
        return j
    }

    // ────────────────────────── XML ──────────────────────────

    private fun scanXml(s: String, from: Int, to: Int, st: State, out: MutableList<Token>) {
        var i = from
        var inTag = false
        while (i < to) {
            if (st.xmlComment) {
                val close = s.indexOf("-->", i)
                if (close < 0 || close + 3 > to) {
                    add(out, i, to, Kind.COMMENT)
                    return
                }
                add(out, i, close + 3, Kind.COMMENT)
                st.xmlComment = false
                i = close + 3
                continue
            }
            val c = s[i]
            if (!inTag) {
                if (c == '<' && i + 3 < to && s[i + 1] == '!' && s[i + 2] == '-' && s[i + 3] == '-') {
                    val close = s.indexOf("-->", i + 4)
                    if (close < 0 || close + 3 > to) {
                        add(out, i, to, Kind.COMMENT)
                        st.xmlComment = true
                        return
                    }
                    add(out, i, close + 3, Kind.COMMENT)
                    i = close + 3
                    continue
                }
                if (c == '<') {
                    var j = i + 1
                    if (j < to && s[j] == '/') j++
                    val nameStart = j
                    while (j < to && isXmlName(s[j])) j++
                    if (j > nameStart) {
                        add(out, i, j, Kind.TAG)
                        inTag = true
                        i = j
                        continue
                    }
                    // <!DOCTYPE ...> 之类整段当标签
                    if (j < to && (s[j] == '?' || s[j] == '!')) {
                        var k = j
                        while (k < to && s[k] != '>') k++
                        if (k < to) k++
                        add(out, i, k, Kind.TAG)
                        i = k
                        continue
                    }
                    i++
                    continue
                }
                i++
                continue
            }
            // 标签内部
            if (c == '>') {
                add(out, i, i + 1, Kind.TAG)
                inTag = false
                i++
                continue
            }
            if (c == '/' && i + 1 < to && s[i + 1] == '>') {
                add(out, i, i + 2, Kind.TAG)
                inTag = false
                i += 2
                continue
            }
            if (c == '"' || c == '\'') {
                val end = scanString(s, i, to, c, true)
                val e = if (end < 0) to else end
                add(out, i, e, Kind.STRING)
                i = e
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }
            if (isIdentStart(c) || c == ':') {
                var j = i + 1
                while (j < to && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == ':' || s[j] == '-' || s[j] == '.')) j++
                add(out, i, j, Kind.ATTR)
                i = j
                continue
            }
            i++
        }
    }

    private fun isXmlName(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'

    // ────────────────────────── Markdown ──────────────────────────

    private fun scanMarkdown(s: String, from: Int, to: Int, st: State, out: MutableList<Token>) {
        var p = from
        while (p < to && (s[p] == ' ' || s[p] == '\t')) p++

        // 代码栅栏（跨行）
        if (st.mdFence) {
            if (p + 2 < to && s[p] == '`' && s[p + 1] == '`' && s[p + 2] == '`') {
                add(out, p, p + 3, Kind.KEYWORD)
                if (p + 3 < to) add(out, p + 3, to, Kind.TYPE)
                st.mdFence = false
            } else {
                add(out, from, to, Kind.CODE)
            }
            return
        }
        if (p + 2 < to && s[p] == '`' && s[p + 1] == '`' && s[p + 2] == '`') {
            add(out, p, p + 3, Kind.KEYWORD)
            if (p + 3 < to) add(out, p + 3, to, Kind.TYPE)
            st.mdFence = true
            return
        }

        // 标题
        var hashes = 0
        while (p + hashes < to && s[p + hashes] == '#' && hashes < 6) hashes++
        if (hashes in 1..6 && (p + hashes >= to || s[p + hashes] == ' ')) {
            add(out, p, to, Kind.KEYWORD)
            return
        }
        // 引用
        if (p < to && s[p] == '>') {
            add(out, p, to, Kind.COMMENT)
            return
        }
        // 列表符
        if (p + 1 < to && (s[p] == '-' || s[p] == '*' || s[p] == '+') && s[p + 1] == ' ') {
            add(out, p, p + 1, Kind.KEYWORD)
        } else {
            var d = p
            while (d < to && s[d].isDigit()) d++
            if (d > p && d < to && (s[d] == '.' || s[d] == ')') && d + 1 < to && s[d + 1] == ' ') {
                add(out, p, d + 1, Kind.KEYWORD)
            }
        }
        // 水平线
        if (p + 2 < to && (s[p] == '-' || s[p] == '*' || s[p] == '_') &&
            s[p] == s[p + 1] && s[p + 1] == s[p + 2]
        ) {
            add(out, p, to, Kind.COMMENT)
            return
        }

        // 行内：`code`、**粗体**、_斜体_、[文字](链接)
        var i = from
        while (i < to) {
            val c = s[i]
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                val e = if (close < 0 || close >= to) to else close + 1
                add(out, i, e, Kind.CODE)
                i = e
                continue
            }
            if (c == '*' || c == '_') {
                val dbl = i + 1 < to && s[i + 1] == c
                val mark = if (dbl) "$c$c" else "$c"
                val close = s.indexOf(mark, i + mark.length)
                if (close > i && close < to) {
                    add(out, i, close + mark.length, Kind.TYPE)
                    i = close + mark.length
                    continue
                }
            }
            if (c == '[') {
                val cb = s.indexOf(']', i + 1)
                if (cb > i && cb < to) {
                    add(out, i, cb + 1, Kind.FUNC)
                    if (cb + 1 < to && s[cb + 1] == '(') {
                        val cp = s.indexOf(')', cb + 2)
                        if (cp > cb + 1 && cp < to) {
                            add(out, cb + 1, cp + 1, Kind.STRING)
                            i = cp + 1
                            continue
                        }
                    }
                    i = cb + 1
                    continue
                }
            }
            i++
        }
    }

    // ────────────────────────── 工具 ──────────────────────────

    private fun add(out: MutableList<Token>, start: Int, end: Int, kind: Kind) {
        if (end <= start) return
        // 合并相邻同类区间，减少 Span 数量（大文件能省掉可观的分配）
        val last = out.lastOrNull()
        if (last != null && last.end == start && last.kind == kind) {
            out[out.size - 1] = Token(last.start, end, kind)
        } else {
            out.add(Token(start, end, kind))
        }
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** smali 寄存器：v0..v15 / p0..p15 / v10。 */
    private fun isSmaliRegister(w: String): Boolean {
        if (w.length < 2 || w.length > 3) return false
        if (w[0] != 'v' && w[0] != 'p') return false
        for (i in 1 until w.length) if (!w[i].isDigit()) return false
        return true
    }

    // ────────────────────────── 词表 ──────────────────────────

    private val PY_KEYWORDS = setOf(
        "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
        "in", "not", "and", "or", "is", "try", "except", "finally", "with", "lambda", "pass",
        "break", "continue", "global", "nonlocal", "raise", "yield", "assert", "del", "async", "await",
    )
    private val PY_CONSTS = setOf("None", "True", "False", "self", "cls")
    private val PY_TYPES = setOf(
        "int", "str", "float", "bool", "list", "dict", "set", "tuple", "bytes", "object",
        "Exception", "ValueError", "KeyError", "TypeError", "RuntimeError", "object",
    )

    private val SH_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "in", "return", "exit", "export", "local", "readonly", "shift", "break",
        "continue", "until", "select", "time",
    )
    private val SH_TYPES = setOf(
        "echo", "cd", "ls", "cat", "pwd", "grep", "sed", "awk", "find", "cp", "mv", "rm",
        "mkdir", "rmdir", "touch", "chmod", "chown", "tar", "zip", "unzip", "curl", "wget",
        "ps", "kill", "sleep", "source", "alias", "unset", "set", "test", "expr", "head",
        "tail", "sort", "uniq", "wc", "printf", "read", "eval", "exec", "tee", "xargs",
    )

    private val SMALI_TYPES = setOf(
        "void", "boolean", "byte", "short", "char", "int", "long", "float", "double",
        "String", "Object", "Class",
    )

    private val C_KEYWORDS = setOf(
        "int", "void", "char", "long", "short", "float", "double", "unsigned", "signed", "const",
        "static", "struct", "union", "enum", "typedef", "sizeof", "return", "if", "else", "while",
        "for", "do", "switch", "case", "default", "break", "continue", "goto", "extern", "inline",
        "volatile", "register", "auto", "restrict", "sizeof", "_Bool",
    )
    private val C_TYPES = setOf(
        "size_t", "ssize_t", "off_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "int8_t", "int16_t", "int32_t", "int64_t", "FILE", "va_list", "bool",
    )

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while",
        // Kotlin 常见关键字，编辑 .kt 时不至于全灰
        "fun", "val", "var", "when", "object", "companion", "override", "open", "data",
        "sealed", "suspend", "inline", "internal", "lateinit", "typealias", "init",
    )
    private val JAVA_TYPES = setOf(
        "String", "Object", "Integer", "Long", "Short", "Byte", "Boolean", "Character",
        "Float", "Double", "List", "Map", "Set", "Array", "ArrayList", "HashMap", "HashSet",
        "Exception", "RuntimeException", "Throwable", "Class", "System", "Math",
    )

    private val JSON_CONSTS = setOf("true", "false", "null")

    private val ASM_MNEMONICS = setOf(
        // AArch64
        "mov", "movz", "movk", "movn", "ldr", "ldrb", "ldrh", "ldrsw", "ldp", "ldur", "ldxr",
        "str", "strb", "strh", "stp", "stur", "stxr", "add", "adds", "sub", "subs", "adrp",
        "adr", "cmp", "cmn", "tst", "b", "bl", "br", "blr", "ret", "cbz", "cbnz", "tbz", "tbnz",
        "b.eq", "b.ne", "b.lt", "b.le", "b.gt", "b.ge", "b.hi", "b.ls", "b.hs", "b.lo",
        "and", "ands", "orr", "eor", "lsl", "lsr", "asr", "ror", "mul", "madd", "msub",
        "div", "sdiv", "udiv", "neg", "mvn", "svc", "nop", "dmb", "dsb", "isb",
        // ARM32
        "push", "pop", "ldmia", "stmia", "blx", "bx", "teq", "adcs", "sbcs", "rsb",
        // x86
        "jmp", "je", "jne", "jz", "jnz", "call", "leave", "test", "xor", "lea", "int", "nop",
        "shl", "shr", "sar", "inc", "dec", "imul", "idiv", "rep", "movs", "stos", "cmpxchg",
        "syscall", "sysenter", "ud2", "endbr64",
    )
    private val ASM_REGISTERS = setOf(
        "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10", "x11", "x12",
        "x13", "x14", "x15", "x16", "x17", "x18", "x19", "x20", "x21", "x22", "x23", "x24",
        "x25", "x26", "x27", "x28", "x29", "x30",
        "w0", "w1", "w2", "w3", "w4", "w5", "w6", "w7", "w8", "w9", "w10", "w11", "w12",
        "w13", "w14", "w15", "w16", "w17", "w18", "w19", "w20", "w21", "w22", "w23", "w24",
        "w25", "w26", "w27", "w28", "w29", "w30",
        "sp", "lr", "pc", "fp", "ip", "xzr", "wzr", "cpsr", "nzcv",
        "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "r12",
        "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp", "eax", "ebx", "ecx", "edx",
        "esi", "edi", "ebp", "esp", "al", "ah", "bl", "cl", "dl", "ax", "bx", "cx", "dx",
    )

    private fun keywords(syn: Syntax): Set<String> = when (syn) {
        Syntax.PYTHON -> PY_KEYWORDS
        Syntax.SHELL -> SH_KEYWORDS
        Syntax.JSON -> emptySet()
        Syntax.SMALI -> emptySet()
        Syntax.C -> C_KEYWORDS
        Syntax.JAVA -> JAVA_KEYWORDS
        Syntax.ASSEMBLY -> ASM_MNEMONICS
        else -> emptySet()
    }

    private fun typeWords(syn: Syntax): Set<String> = when (syn) {
        Syntax.PYTHON -> PY_TYPES
        Syntax.SHELL -> SH_TYPES
        Syntax.SMALI -> SMALI_TYPES
        Syntax.C -> C_TYPES
        Syntax.JAVA -> JAVA_TYPES
        Syntax.ASSEMBLY -> ASM_REGISTERS
        else -> emptySet()
    }

    private fun constWords(syn: Syntax): Set<String> = when (syn) {
        Syntax.PYTHON -> PY_CONSTS
        Syntax.JSON -> JSON_CONSTS
        else -> emptySet()
    }
}
