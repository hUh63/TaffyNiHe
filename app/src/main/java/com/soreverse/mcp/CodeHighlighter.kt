package com.soreverse.mcp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * 轻量语法高亮（借鉴 Xed-Editor 的语法高亮能力，零依赖实现）。
 * 内置语言扩展: Python / Shell / JSON / Smali / C / Java / XML / Markdown / 文本。
 * 逐字符状态机处理字符串/注释，关键字与数字用单词匹配。最多高亮 400 行。
 */
object CodeHighlighter {

    enum class Lang(val ext: String) {
        PYTHON("py"), SHELL("sh"), JSON("json"),
        SMALI("smali"), C("c"), JAVA("java"), XML("xml"), MD("md"),
        EXT("ext"),   // 语法包扩展语言（EditorSyntaxPacks 安装的插件语言）
        TEXT("txt");

        companion object {
            /** 按文件扩展名推断语言（内置语言优先，其次语法包扩展语言）。 */
            fun fromExt(ext: String): Lang {
                val e = ext.lowercase()
                val builtin = when (e) {
                    "py", "pyw" -> PYTHON
                    "sh", "bash", "zsh" -> SHELL
                    "json" -> JSON
                    "smali" -> SMALI
                    "c", "h", "cpp", "cc", "hpp", "cxx" -> C
                    "java", "kt", "kts" -> JAVA
                    "xml", "html", "svg" -> XML
                    "md", "markdown" -> MD
                    "txt" -> TEXT
                    else -> null
                }
                if (builtin != null) return builtin
                return if (com.soreverse.mcp.core.EditorSyntaxPacks.forExt(e) != null) EXT else TEXT
            }
        }
    }

    /** EXT 模式当前生效的语法包（编辑器页根据文件扩展名或手动选择设置）。 */
    @Volatile
    var activePack: com.soreverse.mcp.core.EditorSyntaxPacks.SyntaxPack? = null

    // 配色（深色主题）
    private val KW = Color(0xFFC586C0)      // 关键字
    private val STR = Color(0xFF6AAB73)     // 字符串
    private val COM = Color(0xFF6E7681)     // 注释
    private val NUM = Color(0xFFB5CEA8)     // 数字
    private val FNC = Color(0xFFDCDCAA)     // 函数名/内置
    private val DEF = Color(0xFFD6E2F0)     // 默认
    private val KEY = Color(0xFF9CDCFE)     // JSON 键

    private val PY_KEYWORDS = setOf(
        "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
        "in", "not", "and", "or", "is", "None", "True", "False", "try", "except", "finally",
        "with", "lambda", "pass", "break", "continue", "global", "nonlocal", "raise", "yield", "assert",
    )
    private val PY_BUILTINS = setOf(
        "print", "len", "range", "type", "int", "str", "float", "list", "dict", "set", "tuple",
        "open", "input", "enumerate", "zip", "map", "filter", "sum", "min", "max", "abs", "sorted",
        "isinstance", "getattr", "setattr", "hasattr", "repr", "format", "hex", "bin", "ord", "chr",
        "super", "self", "Exception", "ValueError", "KeyError", "TypeError",
    )
    private val SH_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "in", "return", "exit", "export", "local", "readonly", "shift", "break", "continue",
    )
    private val SH_BUILTINS = setOf(
        "echo", "cd", "ls", "cat", "pwd", "grep", "sed", "awk", "find", "cp", "mv", "rm", "mkdir",
        "touch", "chmod", "chown", "tar", "zip", "unzip", "curl", "wget", "ps", "kill", "sleep",
        "source", "alias", "unset", "set", "test", "expr", "head", "tail", "sort", "uniq", "wc",
    )
    private val SMALI_KEYWORDS = setOf(
        ".method", ".end method", ".field", ".end field", ".class", ".super", ".source", ".locals",
        ".registers", ".prologue", ".param", ".annotation", ".end annotation", ".line", ".directive",
        "invoke-static", "invoke-virtual", "invoke-direct", "invoke-super", "invoke-interface",
        "return", "return-void", "return-object", "goto", "if-eq", "if-ne", "if-lt", "if-ge",
        "if-gt", "if-le", "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez",
        "const", "const/4", "const/16", "const/high16", "const-string", "const-wide", "move", "move-object",
        "new-instance", "new-array", "iget", "iget-object", "iput", "iput-object", "sget", "sput",
        "check-cast", "instance-of", "array-length", "aget", "aput", "monitor-enter", "monitor-exit",
        "throw", "packed-switch", "sparse-switch", "nop", "p0", "p1", "p2", "p3", "p4", "p5", "p6",
        "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10",
    )
    private val C_KEYWORDS = setOf(
        "int", "void", "char", "long", "short", "float", "double", "unsigned", "signed", "const",
        "static", "struct", "union", "enum", "typedef", "sizeof", "return", "if", "else", "while",
        "for", "do", "switch", "case", "default", "break", "continue", "goto", "extern", "inline",
        "volatile", "register", "auto", "#include", "#define", "#ifdef", "#ifndef", "#endif", "#pragma",
    )
    private val JAVA_KEYWORDS = setOf(
        "public", "private", "protected", "static", "final", "class", "interface", "extends",
        "implements", "abstract", "void", "int", "long", "boolean", "char", "float", "double",
        "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "new", "this", "super", "import", "package", "try", "catch", "finally",
        "throw", "throws", "synchronized", "volatile", "transient", "native", "enum", "null", "true", "false",
    )

    private const val MAX_HIGHLIGHT_LINES = 400

    /** 高亮代码为 AnnotatedString。 */
    fun highlight(code: String, lang: Lang): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        if (lang == Lang.EXT) {
            val pack = activePack
                ?: code.take(2000).let { src -> com.soreverse.mcp.core.EditorSyntaxPacks.packs.firstOrNull { p -> p.keywords.any { k -> src.contains(k) } } }
            return if (pack != null) highlightWithPack(code, pack) else highlight(code, Lang.TEXT)
        }
        val lines = code.split("\n")
        val limit = if (lines.size > MAX_HIGHLIGHT_LINES) MAX_HIGHLIGHT_LINES else lines.size
        return buildAnnotatedString {
            for (i in 0 until lines.size) {
                if (i < limit) {
                    highlightLine(this, lines[i], lang)
                } else {
                    append(lines[i])
                }
                if (i != lines.size - 1) append("\n")
            }
        }
    }

    /** 用语法包高亮（语言插件通用着色器：关键字/内置/字符串/数字/行注释/块注释）。 */
    fun highlightWithPack(code: String, pack: com.soreverse.mcp.core.EditorSyntaxPacks.SyntaxPack): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        val ci = pack.caseInsensitive
        val kw = if (ci) pack.keywords.map { it.lowercase() }.toSet() else pack.keywords
        val bi = if (ci) pack.builtins.map { it.lowercase() }.toSet() else pack.builtins
        val lines = code.split("\n")
        val limit = if (lines.size > MAX_HIGHLIGHT_LINES) MAX_HIGHLIGHT_LINES else lines.size
        return buildAnnotatedString {
            for (i in 0 until lines.size) {
                if (i < limit) {
                    highlightPackLine(this, lines[i], kw, bi, pack, ci)
                } else {
                    append(lines[i])
                }
                if (i != lines.size - 1) append("\n")
            }
        }
    }

    private fun highlightPackLine(
        builder: androidx.compose.ui.text.AnnotatedString.Builder,
        line: String,
        kw: Set<String>,
        bi: Set<String>,
        pack: com.soreverse.mcp.core.EditorSyntaxPacks.SyntaxPack,
        ci: Boolean,
    ) {
        val lc = pack.lineComment
        val bcs = pack.blockCommentStart
        val bce = pack.blockCommentEnd
        val t = line.trimStart()
        if (lc != null && t.startsWith(lc)) {
            builder.withStyle(mono(COM)) { builder.append(line) }
            return
        }
        var i = 0
        val n = line.length
        while (i < n) {
            val ch = line[i]
            // 行内注释
            if (lc != null && line.startsWith(lc, i)) {
                builder.withStyle(mono(COM)) { builder.append(line.substring(i)) }
                return
            }
            // 块注释（行内配对；跨行块注释仅着色起始行）
            if (bcs != null && line.startsWith(bcs, i)) {
                val end = if (bce != null) line.indexOf(bce, i + bcs.length) else -1
                val stop = if (end >= 0) end + bce.length else n
                builder.withStyle(mono(COM)) { builder.append(line.substring(i, stop)) }
                i = stop
                continue
            }
            // 字符串
            if (ch == '"' || ch == '\'') {
                val quote = ch
                var j = i + 1
                while (j < n && line[j] != quote) {
                    if (line[j] == '\\') j++
                    j++
                }
                val end = if (j < n) j + 1 else n
                builder.withStyle(mono(STR)) { builder.append(line.substring(i, end)) }
                i = end
                continue
            }
            // 数字
            if (ch.isDigit() || (ch == '-' && i + 1 < n && line[i + 1].isDigit())) {
                var j = i + 1
                while (j < n && (line[j].isDigit() || line[j] == '.' || line[j] == 'x' || line[j] in 'a'..'f' || line[j] in 'A'..'F' || line[j] == 'e')) j++
                builder.withStyle(mono(NUM)) { builder.append(line.substring(i, j)) }
                i = j
                continue
            }
            // 标识符
            if (ch.isLetter() || ch == '_' || ch == '@') {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                val probe = if (ci) word.lowercase() else word
                val style = when {
                    probe in kw -> KW
                    probe in bi -> FNC
                    else -> DEF
                }
                builder.withStyle(mono(style)) { builder.append(word) }
                i = j
                continue
            }
            builder.withStyle(mono(DEF)) { builder.append(ch.toString()) }
            i++
        }
    }

    private fun mono(fg: Color) = SpanStyle(color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    private fun highlightLine(builder: androidx.compose.ui.text.AnnotatedString.Builder, line: String, lang: Lang) {
        val py = lang == Lang.PYTHON
        val sh = lang == Lang.SHELL
        val smali = lang == Lang.SMALI
        val c = lang == Lang.C
        val java = lang == Lang.JAVA
        val json = lang == Lang.JSON
        val xml = lang == Lang.XML
        val md = lang == Lang.MD
        if (lang == Lang.TEXT) {
            builder.withStyle(mono(DEF)) { builder.append(line) }
            return
        }
        // Markdown: 标题/列表/引用/代码围栏 整行处理
        if (md) {
            val t = line.trimStart()
            val style = when {
                t.startsWith("#") -> FNC
                t.startsWith("```") -> KW
                t.startsWith("- ") || t.startsWith("* ") || Regex("^\\d+\\. ").containsMatchIn(t) -> KEY
                t.startsWith(">") -> STR
                else -> DEF
            }
            builder.withStyle(mono(style)) { builder.append(line) }
            return
        }
        // XML: <tag 属性="值"> 结构着色
        if (xml) {
            var i2 = 0
            val n2 = line.length
            while (i2 < n2) {
                val ch = line[i2]
                when {
                    ch == '<' -> {
                        val close = line.indexOf('>', i2)
                        val end = if (close >= 0) close + 1 else n2
                        builder.withStyle(mono(KW)) { builder.append(line.substring(i2, end)) }
                        i2 = end
                    }
                    ch.isDigit() -> {
                        var j = i2 + 1
                        while (j < n2 && line[j].isDigit()) j++
                        builder.withStyle(mono(NUM)) { builder.append(line.substring(i2, j)) }
                        i2 = j
                    }
                    else -> {
                        builder.withStyle(mono(DEF)) { builder.append(ch.toString()) }
                        i2++
                    }
                }
            }
            return
        }
        // 行注释前缀: # (python/shell/smali) / // (c/java)
        if ((py || sh || smali) && line.trimStart().startsWith("#")) {
            builder.withStyle(mono(COM)) { builder.append(line) }
            return
        }
        if ((c || java) && line.trimStart().startsWith("//")) {
            builder.withStyle(mono(COM)) { builder.append(line) }
            return
        }
        var i = 0
        val n = line.length
        while (i < n) {
            val ch = line[i]
            // 行内注释
            if ((py || sh || smali) && ch == '#') { builder.withStyle(mono(COM)) { builder.append(line.substring(i)) }; return }
            if ((c || java) && ch == '/' && i + 1 < n && line[i + 1] == '/') { builder.withStyle(mono(COM)) { builder.append(line.substring(i)) }; return }
            // 字符串
            if (ch == '"' || ch == '\'') {
                val quote = ch
                var j = i + 1
                while (j < n && line[j] != quote) {
                    if (line[j] == '\\') j++
                    j++
                }
                val end = if (j < n) j + 1 else n
                builder.withStyle(mono(STR)) { builder.append(line.substring(i, end)) }
                i = end
                continue
            }
            // JSON 键: "key": 
            if (json && ch == '"') {
                var j = i + 1
                while (j < n && line[j] != '"') j++
                if (j < n) {
                    var k = j + 1
                    while (k < n && line[k].isWhitespace()) k++
                    if (k < n && line[k] == ':') {
                        builder.withStyle(mono(KEY)) { builder.append(line.substring(i, j + 1)) }
                        i = k
                        continue
                    }
                }
            }
            // 数字
            if (ch.isDigit() || (ch == '-' && i + 1 < n && line[i + 1].isDigit())) {
                var j = i + 1
                while (j < n && (line[j].isDigit() || line[j] == '.' || line[j] == 'x' || line[j] in 'a'..'f' || line[j] in 'A'..'F' || line[j] == 'e' || line[j] == '+' || line[j] == '-')) j++
                builder.withStyle(mono(NUM)) { builder.append(line.substring(i, j)) }
                i = j
                continue
            }
            // 标识符（关键字/内置函数）
            if (ch.isLetter() || ch == '_' || ch == '#' || ch == '.') {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '-' || line[j] == '/')) j++
                val word = line.substring(i, j)
                val style = when {
                    py && word in PY_KEYWORDS -> KW
                    py && word in PY_BUILTINS -> FNC
                    sh && word in SH_KEYWORDS -> KW
                    sh && word in SH_BUILTINS -> FNC
                    smali && word in SMALI_KEYWORDS -> KW
                    smali && (word.startsWith("invoke-") || word.startsWith("const") || word.startsWith(".method")) -> KW
                    c && word in C_KEYWORDS -> KW
                    java && word in JAVA_KEYWORDS -> KW
                    else -> DEF
                }
                builder.withStyle(mono(style)) { builder.append(word) }
                i = j
                continue
            }
            // 其他字符
            builder.withStyle(mono(DEF)) { builder.append(ch.toString()) }
            i++
        }
    }
}
