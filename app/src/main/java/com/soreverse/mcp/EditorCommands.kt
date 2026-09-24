package com.soreverse.mcp

import android.content.Context

/**
 * 编辑器「命令系统」——借鉴 Xed-Editor 的 Command / CommandProvider / ToolbarConfiguration 三层设计。
 *
 * Xed 的做法是：所有编辑动作都是 `Command` 对象（带 id / 标签 / 可用性 / 是否开关态 / 长按重复），
 * 工具条与键盘上方键行**都只是命令 id 的列表**，顺序持久化在 SharedPreferences 里，
 * 因此"自定义工具条、命令面板、快捷键"都是天然能力，不需要改 UI 代码。
 *
 * 我们照此拆成三层：
 *  - [EditorHost]：编辑器向命令暴露的最小能力面（状态 + 通用原语 + 页面级动作分发）。
 *  - [EditorCommand]：一条命令（纯数据 + lambda，无 Android 依赖之外的东西）。
 *  - [EditorCommands]：中央注册表（`ALL`，按 id 查找）+ 工具条顺序持久化（`order/setOrder/add/remove/reset`）。
 *
 * 行级/文本级动作全部委托给 [EditorTextOps]（纯函数，可单测）。
 */
internal interface EditorHost {
    val text: String
    val selStart: Int
    val selEnd: Int
    val lang: CodeHighlighter.Lang
    val canUndo: Boolean
    val canRedo: Boolean
    val zh: Boolean
    val hasSelection: Boolean get() = selEnd > selStart

    /** 记录一次撤销点（写文本前调用）。 */
    fun recordUndo()

    /** 替换全文并把光标置于 caret。 */
    fun setText(newText: String, caret: Int)

    /** 往控制台写一行说明。 */
    fun say(msg: String)

    /** 页面级动作分发（保存/运行/查找/跳转/剪贴板/AI/面板/视图开关…）。 */
    fun act(id: String)

    /** 读视图开关状态（供开关型命令的 isOn 判定）。 */
    fun flag(name: String): Boolean = false
}

/** 一条编辑器命令。 */
internal class EditorCommand(
    val id: String,
    val zh: String,
    val en: String,
    val group: String,
    val repeatOnHold: Boolean = false,
    /** 开关型命令的当前状态（用于工具条高亮）。 */
    val isOn: (EditorHost) -> Boolean = { false },
    val enabled: (EditorHost) -> Boolean = { true },
    val perform: (EditorHost) -> Unit,
) {
    fun label(zh: Boolean): String = if (zh) this.zh else en

    /** 长按命令（可空）。 */
    var onLongPress: ((EditorHost) -> Unit)? = null

    /** 命令面板里的分组名。 */
    fun groupLabel(zh: Boolean): String = when (group) {
        EditorCommands.G_EDIT -> if (zh) "编辑" else "Edit"
        EditorCommands.G_LINE -> if (zh) "行操作" else "Lines"
        EditorCommands.G_CASE -> if (zh) "大小写 / 格式化" else "Case / Format"
        EditorCommands.G_FILE -> if (zh) "文件" else "File"
        EditorCommands.G_NAV -> if (zh) "导航" else "Navigation"
        EditorCommands.G_VIEW -> if (zh) "视图" else "View"
        EditorCommands.G_AI -> if (zh) "代码智能" else "Intelligence"
        EditorCommands.G_PANEL -> if (zh) "面板" else "Panels"
        else -> if (zh) "其他" else "Other"
    }
}

/** 编辑器命令注册表 + 工具条顺序。 */
internal object EditorCommands {

    const val G_EDIT = "edit"
    const val G_LINE = "line"
    const val G_CASE = "case"
    const val G_FILE = "file"
    const val G_NAV = "nav"
    const val G_VIEW = "view"
    const val G_AI = "ai"
    const val G_PANEL = "panel"

    /** 默认工具条（用户可在命令面板里增删/排序，顺序落到 SharedPreferences）。 */
    const val DEFAULT_TOOLBAR = "edit.undo|edit.redo|line.indent|line.outdent|line.comment|file.save|nav.find|panel.console"

    private const val PREFS = "taffy_editor_prefs"
    private const val KEY_TOOLBAR = "editor_toolbar_order"

    /** 语言相关的注释前缀。 */
    fun commentPrefix(lang: CodeHighlighter.Lang): String = when (lang) {
        CodeHighlighter.Lang.PYTHON, CodeHighlighter.Lang.SHELL, CodeHighlighter.Lang.SMALI -> "# "
        CodeHighlighter.Lang.XML -> "<!-- "
        else -> "// "
    }

    private fun useTab(lang: CodeHighlighter.Lang): Boolean =
        lang == CodeHighlighter.Lang.SHELL || lang == CodeHighlighter.Lang.SMALI

    private fun writeText(h: EditorHost, res: Pair<String, Int>) {
        h.recordUndo()
        h.setText(res.first, res.second)
    }

    /** 全部命令。 */
    val ALL: List<EditorCommand> = listOf(
        // ── 编辑 ──
        EditorCommand("edit.undo", "撤销", "Undo", G_EDIT, enabled = { it.canUndo }) { it.act("undo") },
        EditorCommand("edit.redo", "重做", "Redo", G_EDIT, enabled = { it.canRedo }) { it.act("redo") },
        // ── 行操作 ──
        EditorCommand("line.indent", "缩进", "Indent", G_LINE) { h ->
            writeText(h, EditorTextOps.indent(h.text, h.selStart, h.selEnd, false, useTab(h.lang)))
        },
        EditorCommand("line.outdent", "取消缩进", "Outdent", G_LINE) { h ->
            writeText(h, EditorTextOps.indent(h.text, h.selStart, h.selEnd, true, useTab(h.lang)))
        },
        EditorCommand("line.comment", "注释", "Comment", G_LINE) { h ->
            writeText(h, EditorTextOps.toggleComment(h.text, h.selStart, h.selEnd, commentPrefix(h.lang)))
        },
        EditorCommand("line.dup", "复制行", "Duplicate line", G_LINE) { h ->
            writeText(h, EditorTextOps.duplicateLines(h.text, h.selStart, h.selEnd))
        },
        EditorCommand("line.up", "上移行", "Move line up", G_LINE, repeatOnHold = true) { h ->
            writeText(h, EditorTextOps.moveLines(h.text, h.selStart, h.selEnd, -1))
        },
        EditorCommand("line.down", "下移行", "Move line down", G_LINE, repeatOnHold = true) { h ->
            writeText(h, EditorTextOps.moveLines(h.text, h.selStart, h.selEnd, 1))
        },
        EditorCommand("line.del", "删除行", "Delete line", G_LINE) { h ->
            writeText(h, EditorTextOps.deleteLines(h.text, h.selStart, h.selEnd))
        },
        // ── 大小写 / 格式化 ──
        EditorCommand("case.upper", "转大写", "UPPER", G_CASE, enabled = { it.hasSelection }) { h ->
            writeText(h, EditorTextOps.changeCase(h.text, h.selStart, h.selEnd, true))
        },
        EditorCommand("case.lower", "转小写", "lower", G_CASE, enabled = { it.hasSelection }) { h ->
            writeText(h, EditorTextOps.changeCase(h.text, h.selStart, h.selEnd, false))
        },
        EditorCommand("case.format", "格式化", "Format", G_CASE) { h ->
            val json = if (h.lang == CodeHighlighter.Lang.JSON) EditorTextOps.formatJson(h.text) else null
            if (json != null) {
                writeText(h, json to 0)
                h.say("[已格式化 JSON]")
            } else if (h.lang == CodeHighlighter.Lang.JSON) {
                h.say("[JSON 无效，无法格式化]")
            } else {
                writeText(h, EditorTextOps.trimTrailing(h.text) to h.selStart)
                h.say("[已清理行尾空白]")
            }
        },
        // ── 文件 ──
        EditorCommand("file.save", "保存", "Save", G_FILE, enabled = { it.text.isNotBlank() }) { it.act("save") },
        EditorCommand("file.open", "打开文件", "Open file", G_FILE) { it.act("open") },
        EditorCommand("file.new", "新建文件", "New file", G_FILE) { it.act("new") },
        EditorCommand("file.rollback", "回滚备份", "Restore backup", G_FILE, enabled = { true }) { it.act("rollback") },
        // ── 导航 ──
        EditorCommand("nav.find", "查找替换", "Find / Replace", G_NAV) { it.act("find") },
        EditorCommand("nav.jump", "跳转（行/偏移/符号）", "Go to (line/offset/symbol)", G_NAV) { it.act("jump") },
        EditorCommand("nav.outline", "符号大纲", "Symbol outline", G_NAV) { it.act("panel:outline") },
        // ── 视图 ──
        EditorCommand("view.linenums", "行号", "Line numbers", G_VIEW, isOn = { it.flag("linenums") }) { it.act("toggle:linenums") },
        EditorCommand("view.readonly", "只读", "Read only", G_VIEW, isOn = { it.flag("readonly") }) { it.act("toggle:readonly") },
        EditorCommand("view.windowed", "窗口化查看", "Windowed view", G_VIEW, isOn = { it.flag("windowed") }) { it.act("toggle:windowed") },
        EditorCommand("view.keys", "符号键行", "Keys row", G_VIEW, isOn = { it.flag("keys") }) { it.act("toggle:keys") },
        // ── 代码智能 ──
        EditorCommand("ai.complete", "补全", "Complete", G_AI, enabled = { it.lang == CodeHighlighter.Lang.PYTHON }) { it.act("ai:complete") },
        EditorCommand("ai.hover", "文档", "Hover doc", G_AI, enabled = { it.lang == CodeHighlighter.Lang.PYTHON }) { it.act("ai:hover") },
        EditorCommand("ai.defs", "跳转定义", "Go to definition", G_AI, enabled = { it.lang == CodeHighlighter.Lang.PYTHON }) { it.act("ai:defs") },
        EditorCommand("ai.diag", "诊断", "Diagnostics", G_AI, enabled = { it.lang == CodeHighlighter.Lang.PYTHON }) { it.act("ai:diag") },
        EditorCommand("ai.ask", "AI 助手", "AI assist", G_AI) { it.act("ai:ask") },
        EditorCommand("ai.packs", "语法包管理", "Syntax packs", G_AI) { it.act("packs") },
        // ── 面板 ──
        EditorCommand("panel.console", "控制台", "Console", G_PANEL) { it.act("panel:console") },
        EditorCommand("panel.files", "工作区文件", "Workspace files", G_PANEL) { it.act("panel:files") },
        EditorCommand("nav.selectall", "全选", "Select all", G_EDIT) { it.act("selectall") },
        EditorCommand("edit.copy", "复制", "Copy", G_EDIT, enabled = { it.hasSelection }) { it.act("copy") },
        EditorCommand("edit.cut", "剪切", "Cut", G_EDIT, enabled = { it.hasSelection }) { it.act("cut") },
        EditorCommand("edit.paste", "粘贴", "Paste", G_EDIT) { it.act("paste") },
    )

    fun byId(id: String): EditorCommand? = ALL.firstOrNull { it.id == id }

    // ── 工具条顺序（持久化）──

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun order(ctx: Context): List<String> {
        val raw = runCatching { prefs(ctx).getString(KEY_TOOLBAR, null) }.getOrNull()
        val ids = if (raw.isNullOrBlank()) DEFAULT_TOOLBAR.split("|") else raw.split("|")
        val valid = ids.filter { it.isNotBlank() && byId(it) != null }
        return if (valid.isEmpty()) DEFAULT_TOOLBAR.split("|") else valid
    }

    fun setOrder(ctx: Context, ids: List<String>) {
        runCatching { prefs(ctx).edit().putString(KEY_TOOLBAR, ids.filter { byId(it) != null }.joinToString("|")).apply() }
    }

    fun addToToolbar(ctx: Context, id: String, index: Int? = null) {
        if (byId(id) == null) return
        val cur = order(ctx).toMutableList()
        cur.remove(id)
        val at = (index ?: cur.size).coerceIn(0, cur.size)
        cur.add(at, id)
        setOrder(ctx, cur)
    }

    fun removeFromToolbar(ctx: Context, id: String) {
        setOrder(ctx, order(ctx).filter { it != id })
    }

    fun toggleInToolbar(ctx: Context, id: String) {
        if (order(ctx).contains(id)) removeFromToolbar(ctx, id) else addToToolbar(ctx, id)
    }

    fun moveInToolbar(ctx: Context, id: String, dy: Int) {
        val cur = order(ctx).toMutableList()
        val i = cur.indexOf(id)
        if (i < 0) return
        val j = (i + dy).coerceIn(0, cur.size - 1)
        if (i == j) return
        cur.removeAt(i)
        cur.add(j, id)
        setOrder(ctx, cur)
    }

    fun resetToolbar(ctx: Context) = setOrder(ctx, DEFAULT_TOOLBAR.split("|"))

    /** 按分组组织的命令列表（命令面板用）。 */
    fun grouped(): List<Pair<String, List<EditorCommand>>> =
        listOf(G_EDIT, G_LINE, G_CASE, G_FILE, G_NAV, G_VIEW, G_AI, G_PANEL)
            .map { g -> g to ALL.filter { it.group == g } }
}
