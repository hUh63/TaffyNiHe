package com.soreverse.mcp

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.soreverse.mcp.core.EditorLexer
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 编辑器渲染层是 sora-editor（io.github.rosemoe:editor，LGPL-2.1）。
 *
 * 原来是用 `BasicTextField` + 自绘行号 + 自绘缩进参考线 + 自绘语法着色拼出来的：
 * 大文件要自己窗口化、撤销栈要自己维护、自动缩进/括号配对要自己算、滚动要自己接
 * 两个 `ScrollState`。换成 sora 之后这些都由库负责 —— 增量高亮、代码折叠、括号匹配、
 * 自动缩进、undo/redo、放大镜、滚动条都是现成的，塔菲只保留业务层（多标签、备份、
 * 命令工具条、搜索替换）。
 *
 * 语法着色不再用库里自带的 Java 词法：塔菲的目标文件是 smali / 汇编 / 配置 / 脚本，
 * Java 词法会把 `.method`、`p0`、`#0x10` 全当普通文本。这里接
 * [EditorAnalyzer]（Java）+ [EditorLexer]（Kotlin，有单测），同时把「行级改动标记」
 * 画成整行底色 —— 库的 LineBackground 正好是这个用途。
 *
 * 这里是一层薄封装：
 * - [SoraEditorHandle] 给业务层调（设文本、设选择、撤销/重做、跳行、取选区）；
 * - [SoraEditorView] 负责把 Compose 的状态同步进 View，并把编辑/光标事件回传。
 *
 * 不做「每帧 setText」：文本只在 `LaunchedEffect(text)` 里比对后同步，样式类属性在
 * `update` 里比对字段后同步，避免大文件每次重组都整串 toString。
 */
internal data class SoraColors(
    val background: Color,
    val text: Color,
    val gutter: Color,
    val gutterCurrent: Color,
    val selection: Color,
    val currentLine: Color,
    val accent: Color,
)

/**
 * 一条诊断（来自 LSP）。只带整行信息 —— LSP 那边也只保留了行号与消息，
 * 所以整行画波浪线，不去猜字符范围（猜错会让人以为错在别的地方）。
 */
internal data class SoraDiag(val line1: Int, val severity: Int, val message: String) {
    companion object {
        const val ERROR = 0
        const val WARNING = 1
        const val INFO = 2
    }
}

/** 业务层持有的编辑器句柄；View 未挂载时所有调用都是空操作。 */
internal class SoraEditorHandle {

    internal var editor: CodeEditor? = null
        private set

    /**
     * 最近一次由编辑器回传给业务层的文本对象。
     * 用来跳过「自己的改动又通过 LaunchedEffect 同步回编辑器」这次无意义的全文比较。
     */
    @Volatile
    internal var lastEmitted: String? = null

    internal fun bind(e: CodeEditor?) {
        editor = e
        if (e == null) lastEmitted = null
    }

    fun currentText(): String = editor?.text?.toString().orEmpty()

    fun selectionOffsets(): Pair<Int, Int> {
        val e = editor ?: return 0 to 0
        val c = e.cursor
        return c.left to c.right
    }

    /** 用「字符偏移」设置选择；sora 的公开 API 是行列，这里统一转换。 */
    fun setSelectionOffsets(start: Int, end: Int, makeVisible: Boolean = true) {
        val e = editor ?: return
        val t = e.text
        val len = t.length
        val a = t.indexer.getCharPosition(start.coerceIn(0, len))
        val b = t.indexer.getCharPosition(end.coerceIn(0, len))
        e.setSelectionRegion(a.line, a.column, b.line, b.column)
        if (makeVisible) e.ensureSelectionVisible()
    }

    /** 设整篇文本；文本相同时只移动光标，避免无谓地重置撤销栈。 */
    fun setText(text: String, caret: Int = -1) {
        val e = editor ?: return
        if (e.text.toString() == text) {
            if (caret >= 0) setSelectionOffsets(caret, caret)
            return
        }
        e.setText(text)
        lastEmitted = null
        if (caret >= 0) setSelectionOffsets(caret, caret)
    }

    fun undo() {
        editor?.undo()
    }

    fun redo() {
        editor?.redo()
    }

    /** 跳到指定行（0 基），并把该行滚到可见区。 */
    fun scrollToLine(line0: Int) {
        val e = editor ?: return
        val line = line0.coerceIn(0, (e.text.lineCount - 1).coerceAtLeast(0))
        val off = e.text.indexer.getCharIndex(line, 0)
        setSelectionOffsets(off, off)
    }

    /** 当前选中的文本（多选区时取主选区）。 */
    fun selectedText(): String {
        val e = editor ?: return ""
        val (a, b) = selectionOffsets()
        val t = e.text.toString()
        return if (a < b && b <= t.length) t.substring(a, b) else ""
    }

    /** 只保证光标可见，不改选区（跳转会先设选区，再调这个）。 */
    fun ensureVisible() {
        editor?.ensureSelectionVisible()
    }

    /** 把诊断铺到整行上（库负责画波浪线、长按提示）。传空列表即清空。 */
    fun setDiagnostics(items: List<SoraDiag>) {
        val e = editor ?: return
        val t = e.text
        val container = DiagnosticsContainer(true)
        if (items.isNotEmpty()) {
            val lastLine = (t.lineCount - 1).coerceAtLeast(0)
            for (d in items.take(200)) {
                val line = (d.line1 - 1).coerceIn(0, lastLine)
                val start = runCatching { t.indexer.getCharIndex(line, 0) }.getOrDefault(0)
                val len = runCatching { t.getLine(line).length }.getOrDefault(0)
                val end = (start + len).coerceAtMost(t.length)
                if (end <= start) continue
                val sev = when (d.severity) {
                    SoraDiag.ERROR -> DiagnosticRegion.SEVERITY_ERROR
                    SoraDiag.WARNING -> DiagnosticRegion.SEVERITY_WARNING
                    else -> DiagnosticRegion.SEVERITY_TYPO
                }
                container.addDiagnostic(DiagnosticRegion(start, end, sev))
            }
        }
        e.setDiagnostics(container)
    }

    fun cursorLine0(): Int = editor?.cursor?.leftLine ?: 0

    fun lineCount(): Int = editor?.text?.lineCount ?: 1
}

/** 带「上次应用值」的 CodeEditor：用来判断语言/主题/字号是否真的变了。 */
private class TaffyCodeEditor(context: Context) : CodeEditor(context) {
    var appliedLang: CodeHighlighter.Lang? = null
    var appliedScheme: EditorColorScheme? = null
    var appliedFontPx: Float = -1f
    var appliedWrap: Boolean? = null
    var appliedLineNumbers: Boolean? = null
    var appliedDiff: Map<Int, Int>? = null
    var analyzer: EditorAnalyzer? = null
}

/**
 * 只负责「把分析器与编辑辅助交给库」的语言实现。
 *
 * 换掉库里自带的 Java 语言后有两个编辑体验会丢：自动补右括号、回车自动缩进
 * （EmptyLanguage 里两者都是空实现）。这里按「未闭合括号深度」补回来：
 * - 光标前的 `(` `[` `{` 未闭合几个，就多缩几级；Python 的 `:` 结尾也缩一级；
 * - 括号与引号补全。
 * 不追求把每种语法的缩进规则做全 —— 只要回车后不用手动敲空格就够。
 */
private class TaffyLanguage(
    private val analyzer: AnalyzeManager,
    private val syntax: EditorLexer.Syntax,
) : EmptyLanguage() {

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun getSymbolPairs(): SymbolPairMatch = SYMBOL_PAIRS

    override fun getIndentAdvance(text: ContentReference, line: Int, column: Int): Int {
        val src = runCatching {
            val l = text.getLine(line)
            l.substring(0, column.coerceIn(0, l.length))
        }.getOrNull() ?: return 0
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
            i++
        }
        if (depth > 0) return depth * 4
        // Python 的复合语句以 : 结尾，下一行要缩进一级
        if (syntax == EditorLexer.Syntax.PYTHON && src.trimEnd().endsWith(":")) return 4
        return 0
    }

    companion object {
        private val SYMBOL_PAIRS = SymbolPairMatch().apply {
            putPair("(", SymbolPairMatch.SymbolPair("(", ")"))
            putPair("[", SymbolPairMatch.SymbolPair("[", "]"))
            putPair("{", SymbolPairMatch.SymbolPair("{", "}"))
            putPair("\"", SymbolPairMatch.SymbolPair("\"", "\""))
            putPair("'", SymbolPairMatch.SymbolPair("'", "'"))
        }
    }
}

/** CodeHighlighter.Lang → 词法语法族；null 表示不着色。 */
internal fun CodeHighlighter.Lang.lexerSyntax(): EditorLexer.Syntax? = when (this) {
    CodeHighlighter.Lang.PYTHON -> EditorLexer.Syntax.PYTHON
    CodeHighlighter.Lang.SHELL -> EditorLexer.Syntax.SHELL
    CodeHighlighter.Lang.JSON -> EditorLexer.Syntax.JSON
    CodeHighlighter.Lang.SMALI -> EditorLexer.Syntax.SMALI
    CodeHighlighter.Lang.C -> EditorLexer.Syntax.C
    CodeHighlighter.Lang.JAVA -> EditorLexer.Syntax.JAVA
    CodeHighlighter.Lang.XML -> EditorLexer.Syntax.XML
    CodeHighlighter.Lang.MD -> EditorLexer.Syntax.MARKDOWN
    CodeHighlighter.Lang.ASM -> EditorLexer.Syntax.ASSEMBLY
    // 语法包（EXT）的语言定义是运行期 JSON，词法规则不固定，走纯文本
    CodeHighlighter.Lang.EXT -> EditorLexer.Syntax.PLAIN
    CodeHighlighter.Lang.TEXT -> null
}

/** token 类型 → sora 颜色槽（槽位在 EditorAnalyzer 里定义，避开库预定义的 1..83）。 */
private val KIND_COLOR_IDS: Map<EditorLexer.Kind, Int> = mapOf(
    EditorLexer.Kind.KEYWORD to EditorAnalyzer.CID_KEYWORD,
    EditorLexer.Kind.COMMENT to EditorAnalyzer.CID_COMMENT,
    EditorLexer.Kind.STRING to EditorAnalyzer.CID_STRING,
    EditorLexer.Kind.NUMBER to EditorAnalyzer.CID_NUMBER,
    EditorLexer.Kind.FUNC to EditorAnalyzer.CID_FUNC,
    EditorLexer.Kind.TYPE to EditorAnalyzer.CID_TYPE,
    EditorLexer.Kind.CONST to EditorAnalyzer.CID_CONST,
    EditorLexer.Kind.TAG to EditorAnalyzer.CID_TAG,
    EditorLexer.Kind.ATTR to EditorAnalyzer.CID_ATTR,
    EditorLexer.Kind.DIRECTIVE to EditorAnalyzer.CID_DIRECTIVE,
    EditorLexer.Kind.CODE to EditorAnalyzer.CID_CODE,
)

/**
 * 词法配色：与 CodeHighlighter 的 Compose 调色板同源（深浅两套分别对齐 VSCode-Dark 与 One-Light，
 * 浅色底上压暗过，保证 4.5:1 以上对比度）。
 */
private class SyntaxColors(
    val keyword: Int,
    val comment: Int,
    val string: Int,
    val number: Int,
    val func: Int,
    val type: Int,
    val const: Int,
    val tag: Int,
    val attr: Int,
    val directive: Int,
    val code: Int,
) {
    companion object {
        private val DARK = SyntaxColors(
            keyword = 0xFFC586C0.toInt(),
            comment = 0xFF7E8894.toInt(),
            string = 0xFF6AAB73.toInt(),
            number = 0xFFB5CEA8.toInt(),
            func = 0xFFDCDCAA.toInt(),
            type = 0xFF4EC9B0.toInt(),
            const = 0xFF569CD6.toInt(),
            tag = 0xFF569CD6.toInt(),
            attr = 0xFF9CDCFE.toInt(),
            directive = 0xFFD7BA7D.toInt(),
            code = 0xFFCE9178.toInt(),
        )
        private val LIGHT = SyntaxColors(
            keyword = 0xFFA626A4.toInt(),
            comment = 0xFF6C7178.toInt(),
            string = 0xFF3F813E.toInt(),
            number = 0xFF7C5700.toInt(),
            func = 0xFF2D63D6.toInt(),
            type = 0xFF2A7B72.toInt(),
            const = 0xFF0B6E99.toInt(),
            tag = 0xFF0B6E99.toInt(),
            attr = 0xFF2D63D6.toInt(),
            directive = 0xFFA0522D.toInt(),
            code = 0xFF8B5A00.toInt(),
        )

        fun of(dark: Boolean): SyntaxColors = if (dark) DARK else LIGHT
    }
}

@Composable
internal fun SoraEditorView(
    handle: SoraEditorHandle,
    text: String,
    readOnly: Boolean,
    lang: CodeHighlighter.Lang,
    dark: Boolean,
    colors: SoraColors,
    fontSizeSp: Float,
    lineNumbers: Boolean,
    wordWrap: Boolean,
    modifier: Modifier = Modifier,
    marks: Map<Int, EditorDiffMarks.Mark> = emptyMap(),
    markColors: Map<EditorDiffMarks.Mark, Color> = emptyMap(),
    onTextChanged: (text: String?, selectionStart: Int, selectionEnd: Int) -> Unit,
    onCursorMoved: (line0: Int, column0: Int) -> Unit,
) {
    val onText = rememberUpdatedState(onTextChanged)
    val onCursor = rememberUpdatedState(onCursorMoved)
    val density = LocalDensity.current
    val fontPx = with(density) { fontSizeSp.sp.toPx() }
    val scheme = remember(dark, colors) { TaffyScheme(dark, colors) }

    // 差异行：marks 是 1 基行号，sora 的 LineBackground 用 0 基
    val diffArgb = remember(markColors) { markColors.mapValues { it.value.toArgb() } }
    val diffLines = remember(marks, diffArgb) {
        if (marks.isEmpty()) {
            emptyMap<Int, Int>()
        } else {
            val out = HashMap<Int, Int>(marks.size)
            for ((ln, kind) in marks) {
                val argb = diffArgb[kind] ?: continue
                out[ln - 1] = argb
            }
            out
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TaffyCodeEditor(ctx).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTypefaceLineNumber(Typeface.MONOSPACE)
                setLineSpacing(0f, 1.35f)
                setTabWidth(4)
                setHighlightCurrentLine(true)
                setHighlightBracketPair(true)
                setPinLineNumber(true)
                setScrollBarEnabled(true)
                setColorScheme(scheme)
                appliedScheme = scheme
                // 不在这里 setText("")：空内容已经是默认值，而一次 setText 会触发
                // ContentChangeEvent，把业务层的 code 清成空串（factory 时文本还没同步）。
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    // 显式 this.text：外层 composable 的参数也叫 text，不写 this 会取到那个 String。
                    val c = this.text.cursor
                    val a = c.left
                    val b = c.right
                    // 2 MB 以上的文本业务层是只读的（见编辑器页），这里再兜一层：
                    // 真的有人拿它编辑超大文件时不回传全文，免得每次按键都复制整篇。
                    // 回传 null 表示「文本没同步」，调用方要保留自己的副本而不是当成空串。
                    val full: String? = if (this.text.length <= 2_000_000) this.text.toString() else null
                    handle.lastEmitted = full
                    onText.value(full, a, b)
                }
                subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                    runCatching {
                        val c = this.text.cursor
                        onCursor.value(c.leftLine, c.leftColumn)
                    }
                }
                handle.bind(this)
            }
        },
        update = { ed ->
            if (ed.appliedScheme !== scheme) {
                ed.setColorScheme(scheme)
                ed.appliedScheme = scheme
            }
            if (ed.appliedLang != lang) {
                val syntax = lang.lexerSyntax()
                if (syntax == null) {
                    ed.analyzer = null
                    ed.setEditorLanguage(EmptyLanguage())
                    ed.setDiagnostics(DiagnosticsContainer(true))
                } else {
                    val analyzer = EditorAnalyzer(syntax, KIND_COLOR_IDS)
                    analyzer.setDiffLines(diffLines)
                    ed.analyzer = analyzer
                    ed.setEditorLanguage(TaffyLanguage(analyzer, syntax))
                    ed.appliedDiff = diffLines
                }
                ed.appliedLang = lang
            }
            // 差异行变了：换 map + 重跑一次分析（LineBackground 只能由分析结果整体产出）
            if (ed.appliedDiff !== diffLines) {
                ed.appliedDiff = diffLines
                ed.analyzer?.let {
                    it.setDiffLines(diffLines)
                    // 分析器还没被库 reset 过时 rerun 会抛，重跑一次也无害
                    runCatching { it.rerun() }
                }
            }
            if (ed.appliedFontPx != fontPx) {
                ed.setTextSizePx(fontPx)
                ed.appliedFontPx = fontPx
            }
            if (ed.appliedWrap != wordWrap) {
                ed.setWordwrap(wordWrap)
                ed.appliedWrap = wordWrap
            }
            if (ed.appliedLineNumbers != lineNumbers) {
                ed.setLineNumberEnabled(lineNumbers)
                ed.appliedLineNumbers = lineNumbers
            }
            if (ed.isEditable != !readOnly) {
                ed.isEditable = !readOnly
            }
        },
        onRelease = { ed ->
            handle.bind(null)
            ed.analyzer = null
            runCatching { ed.release() }
        },
    )

    androidx.compose.runtime.LaunchedEffect(text, handle) {
        val ed = handle.editor ?: return@LaunchedEffect
        // 编辑器自己回传的文本不用再同步回去：省掉一次全文比较（大文件这里是实打实的开销）
        if (handle.lastEmitted === text) return@LaunchedEffect
        if (ed.text.toString() != text) {
            val caret = ed.cursor.left.coerceAtMost(text.length)
            ed.setText(text)
            if (caret > 0) {
                val t = ed.text
                val pos = t.indexer.getCharPosition(caret.coerceIn(0, t.length))
                ed.setSelection(pos.line, pos.column)
            }
        }
    }
}

/**
 * 把塔菲的配色映射到 sora 的 EditorColorScheme。
 *
 * 覆盖实际会显示出来的槽位：正文、行号、当前行、选择、滚动条、补全浮层，
 * 以及词法着色的 11 个自定义槽（由 [EditorAnalyzer] 引用）。
 */
private class TaffyScheme(dark: Boolean, c: SoraColors) : EditorColorScheme(dark) {
    init {
        setColor(EditorColorScheme.WHOLE_BACKGROUND, c.background.toArgb())
        setColor(EditorColorScheme.TEXT_NORMAL, c.text.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, c.gutter.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, c.background.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_CURRENT, c.gutterCurrent.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_PANEL, c.currentLine.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, c.text.toArgb())
        setColor(EditorColorScheme.CURRENT_LINE, c.currentLine.toArgb())
        setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, c.selection.toArgb())
        setColor(EditorColorScheme.TEXT_SELECTED, c.text.toArgb())
        setColor(EditorColorScheme.UNDERLINE, c.accent.toArgb())
        setColor(EditorColorScheme.BLOCK_LINE, c.currentLine.toArgb())
        setColor(EditorColorScheme.BLOCK_LINE_CURRENT, c.currentLine.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, c.currentLine.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, c.text.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, c.gutter.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_TEXT_MATCHED, c.accent.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, c.selection.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_CORNER, c.background.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_TRACK, c.currentLine.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB, c.gutter.copy(alpha = 0.55f).toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, c.accent.copy(alpha = 0.75f).toArgb())
        setColor(EditorColorScheme.LINE_DIVIDER, c.currentLine.toArgb())
        setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, c.selection.toArgb())

        val syn = SyntaxColors.of(dark)
        setColor(EditorAnalyzer.CID_KEYWORD, syn.keyword)
        setColor(EditorAnalyzer.CID_COMMENT, syn.comment)
        setColor(EditorAnalyzer.CID_STRING, syn.string)
        setColor(EditorAnalyzer.CID_NUMBER, syn.number)
        setColor(EditorAnalyzer.CID_FUNC, syn.func)
        setColor(EditorAnalyzer.CID_TYPE, syn.type)
        setColor(EditorAnalyzer.CID_CONST, syn.const)
        setColor(EditorAnalyzer.CID_TAG, syn.tag)
        setColor(EditorAnalyzer.CID_ATTR, syn.attr)
        setColor(EditorAnalyzer.CID_DIRECTIVE, syn.directive)
        setColor(EditorAnalyzer.CID_CODE, syn.code)
    }
}
