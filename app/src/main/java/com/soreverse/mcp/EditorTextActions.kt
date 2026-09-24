package com.soreverse.mcp

import androidx.compose.runtime.mutableStateListOf

/**
 * 编辑器「文本动作」——借鉴 Xed-Editor 的 `TextActionItem` / `registerTextAction` 机制。
 *
 * Xed 允许通过扩展点往光标浮条里注入自定义动作（`shouldShow(editor)` + `onClick(editor)`）。
 * 我们把它做成一个**可注册的全局动作表**：内置动作 + 运行时注册的扩展动作共同渲染成
 * 编辑器顶部那条「内联动作条」，扩展系统（SettingsExtensionsPage）以后可以往里加按钮，
 * 不需要改编辑器代码。
 */
internal class EditorTextAction(
    val id: String,
    val zh: String,
    val en: String,
    /** 何时显示（例如"复制"仅在选中时出现）。 */
    val showWhen: (EditorHost) -> Boolean = { true },
    val perform: (EditorHost) -> Unit,
) {
    fun label(zh: Boolean): String = if (zh) this.zh else en
}

internal object EditorTextActions {

    /** 内置动作（行内编辑核心）。 */
    val builtin: List<EditorTextAction> = listOf(
        EditorTextAction("text.copy", "复制", "Copy", { it.hasSelection }) { it.act("copy") },
        EditorTextAction("text.cut", "剪切", "Cut", { it.hasSelection }) { it.act("cut") },
        EditorTextAction("text.paste", "粘贴", "Paste") { it.act("paste") },
        EditorTextAction("text.all", "全选", "All") { it.act("selectall") },
        EditorTextAction("text.dupline", "复制行", "Dup line") { h ->
            val r = EditorTextOps.duplicateLines(h.text, h.selStart, h.selEnd)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.up", "↑行", "Up") { h ->
            val r = EditorTextOps.moveLines(h.text, h.selStart, h.selEnd, -1)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.down", "↓行", "Down") { h ->
            val r = EditorTextOps.moveLines(h.text, h.selStart, h.selEnd, 1)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.delline", "删行", "Del line") { h ->
            val r = EditorTextOps.deleteLines(h.text, h.selStart, h.selEnd)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.comment", "注释", "Comment") { h ->
            val r = EditorTextOps.toggleComment(h.text, h.selStart, h.selEnd, EditorCommands.commentPrefix(h.lang))
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.upper", "大写", "UPPER", { it.hasSelection }) { h ->
            val r = EditorTextOps.changeCase(h.text, h.selStart, h.selEnd, true)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.lower", "小写", "lower", { it.hasSelection }) { h ->
            val r = EditorTextOps.changeCase(h.text, h.selStart, h.selEnd, false)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.indent", "缩进", "Indent") { h ->
            val useTab = h.lang == CodeHighlighter.Lang.SHELL || h.lang == CodeHighlighter.Lang.SMALI
            val r = EditorTextOps.indent(h.text, h.selStart, h.selEnd, false, useTab)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.outdent", "取消缩进", "Outdent") { h ->
            val useTab = h.lang == CodeHighlighter.Lang.SHELL || h.lang == CodeHighlighter.Lang.SMALI
            val r = EditorTextOps.indent(h.text, h.selStart, h.selEnd, true, useTab)
            h.recordUndo(); h.setText(r.first, r.second)
        },
        EditorTextAction("text.format", "格式化", "Format") { h ->
            val json = if (h.lang == CodeHighlighter.Lang.JSON) EditorTextOps.formatJson(h.text) else null
            if (json != null) {
                h.recordUndo(); h.setText(json, 0); h.say("[已格式化 JSON]")
            } else if (h.lang == CodeHighlighter.Lang.JSON) {
                h.say("[JSON 无效，无法格式化]")
            } else {
                h.recordUndo(); h.setText(EditorTextOps.trimTrailing(h.text), h.selStart); h.say("[已清理行尾空白]")
            }
        },
        EditorTextAction("text.undo", "撤销", "Undo", { it.canUndo }) { it.act("undo") },
        EditorTextAction("text.redo", "重做", "Redo", { it.canRedo }) { it.act("redo") },
        EditorTextAction("text.jump", "Ln", "Ln") { it.act("jump") },
    )

    /** 运行时注册的扩展动作（可由扩展系统注入）。 */
    private val extras = mutableStateListOf<EditorTextAction>()

    val extra: List<EditorTextAction> get() = extras

    /** 注册一个扩展动作；id 重复时忽略并返回 false。 */
    fun register(action: EditorTextAction): Boolean {
        if (builtin.any { it.id == action.id } || extras.any { it.id == action.id }) return false
        extras.add(action)
        return true
    }

    /** 取消注册。 */
    fun unregister(id: String): Boolean = extras.removeAll { it.id == id }

    /** 全部动作（内置 + 扩展，扩展在后）。 */
    fun all(): List<EditorTextAction> = builtin + extras
}
