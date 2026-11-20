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
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 编辑器渲染层已换成 sora-editor（io.github.rosemoe:editor，LGPL-2.1）。
 *
 * 原来是用 `BasicTextField` + 自绘行号 + 自绘缩进参考线 + 自绘语法着色拼出来的：
 * 大文件要自己窗口化、撤销栈要自己维护、自动缩进/括号配对要自己算、滚动要自己接
 * 两个 `ScrollState`。换成 sora 之后这些都由库负责 —— 增量高亮、代码折叠、括号匹配、
 * 自动缩进、undo/redo、放大镜、滚动条都是现成的，塔菲只保留业务层（多标签、备份、
 * 命令工具条、搜索替换）。
 *
 * 这里是一层薄封装：
 * - [SoraEditorHandle] 给业务层调（设文本、设选择、撤销/重做、跳行、取选区）；
 * - [SoraEditorView] 负责把 Compose 的状态同步进 View，并把编辑/光标事件回传。
 *
 * 不做「每帧 setText」：文本只在 `LaunchedEffect(text)` 里比对后同步，样式类属性在
 * `update` 里比对字段后同步，避免大文件每次重组都整串 toString（8 MiB 的文件那会卡死）。
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

/** 业务层持有的编辑器句柄；View 未挂载时所有调用都是空操作。 */
internal class SoraEditorHandle {

    internal var editor: CodeEditor? = null
        private set

    internal fun bind(e: CodeEditor?) {
        editor = e
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
    onTextChanged: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit,
    onCursorMoved: (line0: Int, column0: Int) -> Unit,
) {
    val onText = rememberUpdatedState(onTextChanged)
    val onCursor = rememberUpdatedState(onCursorMoved)
    val density = LocalDensity.current
    val fontPx = with(density) { fontSizeSp.sp.toPx() }
    val scheme = remember(dark, colors) { TaffyScheme(dark, colors) }

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
                    val (a, b) = runCatching { this.text.cursor.left to this.text.cursor.right }.getOrDefault(0 to 0)
                    onText.value(this.text.toString(), a, b)
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
                ed.editorLanguage = when (lang) {
                    CodeHighlighter.Lang.JAVA, CodeHighlighter.Lang.C, CodeHighlighter.Lang.SMALI -> JavaLanguage()
                    else -> EmptyLanguage()
                }
                ed.appliedLang = lang
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
            // 文本不在 update 里比对：8 MiB 文件每次重组都 toString 会卡死，改由
            // LaunchedEffect(text) 在外部状态真的变化时做一次比对与同步。
        },
        onRelease = { ed ->
            handle.bind(null)
            runCatching { ed.release() }
        },
    )

    androidx.compose.runtime.LaunchedEffect(text, handle) {
        val ed = handle.editor ?: return@LaunchedEffect
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
 * 只覆盖实际会显示出来的槽位：正文、行号、当前行、选择、滚动条与补全浮层。
 * 语法着色由 Language 自己的 span 决定，不在这里设死颜色 —— 否则换语言后
 * 关键字全变成一种色。
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
    }
}
