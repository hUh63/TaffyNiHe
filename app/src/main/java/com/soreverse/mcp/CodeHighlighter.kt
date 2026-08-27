package com.soreverse.mcp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * 轻量语法高亮（借鉴 Xed-Editor 的语法高亮能力，零依赖实现）。
 * 支持 Python / Shell / JSON / 文本 四类；逐字符状态机处理字符串/注释，
 * 关键字与数字用单词匹配。为控制性能最多高亮 400 行，超出部分用默认色。
 */
object CodeHighlighter {

    enum class Lang { PYTHON, SHELL, JSON, TEXT }

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

    private const val MAX_HIGHLIGHT_LINES = 400

    /** 高亮代码为 AnnotatedString。 */
    fun highlight(code: String, lang: Lang): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
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

    private fun mono(fg: Color) = SpanStyle(color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    private fun highlightLine(builder: androidx.compose.ui.text.AnnotatedString.Builder, line: String, lang: Lang) {
        val py = lang == Lang.PYTHON
        val sh = lang == Lang.SHELL
        val json = lang == Lang.JSON
        if (lang == Lang.TEXT) {
            builder.withStyle(mono(DEF)) { append(line) }
            return
        }
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            // 注释
            if (py && c == '#') { builder.withStyle(mono(COM)) { append(line.substring(i)) }; return }
            if (sh && c == '#') { builder.withStyle(mono(COM)) { append(line.substring(i)) }; return }
            // 字符串
            if (c == '"' || c == '\'') {
                val quote = c
                var j = i + 1
                while (j < n && line[j] != quote) {
                    if (line[j] == '\\') j++
                    j++
                }
                val end = if (j < n) j + 1 else n
                builder.withStyle(mono(STR)) { append(line.substring(i, end)) }
                i = end
                continue
            }
            // JSON 键: "key": 
            if (json && c == '"') {
                var j = i + 1
                while (j < n && line[j] != '"') j++
                if (j < n) {
                    var k = j + 1
                    while (k < n && line[k].isWhitespace()) k++
                    if (k < n && line[k] == ':') {
                        builder.withStyle(mono(KEY)) { append(line.substring(i, j + 1)) }
                        i = k
                        continue
                    }
                }
            }
            // 数字
            if (c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit())) {
                var j = i + 1
                while (j < n && (line[j].isDigit() || line[j] == '.' || line[j] == 'x' || line[j] in 'a'..'f' || line[j] in 'A'..'F' || line[j] == 'e' || line[j] == '+' || line[j] == '-')) j++
                builder.withStyle(mono(NUM)) { append(line.substring(i, j)) }
                i = j
                continue
            }
            // 标识符（关键字/内置函数）
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                val style = when {
                    py && word in PY_KEYWORDS -> KW
                    py && word in PY_BUILTINS -> FNC
                    sh && word in SH_KEYWORDS -> KW
                    sh && word in SH_BUILTINS -> FNC
                    else -> DEF
                }
                builder.withStyle(mono(style)) { append(word) }
                i = j
                continue
            }
            // 其他字符
            builder.withStyle(mono(DEF)) { append(c.toString()) }
            i++
        }
    }
}
