package com.soreverse.mcp

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.soreverse.mcp.core.EditorDoc
import com.soreverse.mcp.core.TextDiff
import org.json.JSONArray
import org.json.JSONObject

/**
 * 编辑器渲染层：WebView + CodeMirror 6（assets/editor）。
 *
 * 换掉 sora-editor 的原因只有一条 —— **代码折叠**。sora 0.24.x 的
 * `FoldingRegion` 是没有任何调用方的预留 API，布局层以「逻辑行号 == 视觉行号」
 * 为前提，不存在隐藏行的概念，所以不做库级 fork 就折不了。
 *
 * 换成 CM6 之后拿到的能力：
 * - 真折叠（foldGutter + foldAll/unfoldAll + 语言自带 foldService）
 * - 撤销栈在 JS 侧（history 扩展），一套栈管住所有编辑，包括程序化编辑
 * - 视口虚拟化渲染 + 分级的语法解析开关，大文件不卡
 * - 搜索替换面板、括号匹配、多光标都是内置的
 *
 * Kotlin 只负责三件事：把文本推给 JS（分块 base64）、保存业务状态（影子文本）、
 * 把外观（配色/字号/只读）同步下去。文本变换（缩进、注释、格式化之外的行操作）
 * 一律转发成 CM 命令，避免「把 1MB 文本抛上来改完再抛回去」。
 *
 * 性能设计：**打字不触发 Compose 重组**。
 * JS 每次变更只回传最小 diff，写进 [EditorDoc]（普通对象，不是 state）；
 * 只有 250ms 节流的文本快照、以及轻量的选区三元组进 state。
 * 因此光标移动只重组订阅选区的少数控件，键盘输入不引起整页重组。
 */
internal data class CmColors(
    val background: Color,
    val text: Color,
    val gutter: Color,
    val gutterCurrent: Color,
    val selection: Color,
    val currentLine: Color,
    val accent: Color,
    val accentOn: Color = Color.White,
    val panel: Color = background,
    val border: Color = gutter,
    val error: Color = Color(0xFFFF6B6B),
    val warning: Color = Color(0xFFE0A030),
    val info: Color = Color(0xFF5A9BD5),
) {
    fun toCssVars(dark: Boolean): String {
        fun hex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())
        return JSONObject()
            .put("bg", hex(background))
            .put("fg", hex(text))
            .put("gut", hex(gutter))
            .put("gutfg", hex(text.copy(alpha = 0.45f)))
            .put("gutcur", hex(gutterCurrent))
            .put("curline", hex(currentLine))
            .put("sel", hex(selection))
            .put("caret", hex(accent))
            .put("accent", hex(accent))
            .put("accentfg", hex(accentOn))
            .put("panel", hex(panel))
            .put("input", hex(gutterCurrent))
            .put("border", hex(border))
            .put("foldbg", hex(gutterCurrent))
            .put("err", hex(error))
            .put("warn", hex(warning))
            .put("info", hex(info))
            .put("dark", dark)
            .toString()
    }
}

/** 一条行级诊断（LSP 侧只有行号，所以整行标记，不去猜字符范围）。 */
internal data class CmDiag(val line1: Int, val severity: Int, val message: String) {
    companion object {
        const val ERROR = 0
        const val WARNING = 1
        const val INFO = 2
    }
}

/** 选区 + 文档规模。只有它进 Compose state 时会随光标频繁变化，所以刻意做小。 */
internal data class CmSel(
    val a: Int = 0,
    val b: Int = 0,
    val line: Int = 1,
    val col: Int = 1,
    val lines: Int = 1,
    val len: Int = 0,
) {
    val hasSelection: Boolean get() = b > a
    val min: Int get() = a
    val max: Int get() = b
}

/**
 * 业务层持有的编辑器句柄。WebView 未挂载 / 未就绪时：
 * - 文本类调用先记成「待推送」，就绪后自动补发（不会丢内容）；
 * - 命令类调用排队（最多 64 条，超出丢弃最旧的）。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class CodeMirrorHandle {

    private val main = Handler(Looper.getMainLooper())
    private val doc = EditorDoc()

    @Volatile
    private var web: WebView? = null
    private var booted = false
    private var ready = false
    private val queue = ArrayDeque<String>()
    private var pendingText: Pair<String, Int>? = null

    private var lang = "plain"
    private var appliedColors: CmColors? = null
    private var appliedDark: Boolean? = null
    private var appliedFontPx = -1
    private var appliedReadOnly: Boolean? = null

    private var snapScheduled = false
    private var selDirty = false

    /** 选区（轻量，光标移动即更新）。 */
    internal val sel = mutableStateOf(CmSel())

    /** 节流后的全文快照：给「行数/大纲/差异计数」这类派生计算用，不进按键路径。 */
    internal val snapText = mutableStateOf("")

    internal val canUndo = mutableStateOf(false)
    internal val canRedo = mutableStateOf(false)
    internal val foldedCount = mutableIntStateOf(0)
    internal val readyState = mutableStateOf(false)

    /** JS 侧异常与关键日志，交给页面显示，不再静默。 */
    internal var onLog: ((String) -> Unit)? = null
    internal var onReady: (() -> Unit)? = null

    // ------------------------------------------------------------ 生命周期

    internal fun attach(w: WebView) {
        if (web === w) return
        web = w
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkImage = true
            // 纯本地页面：断掉一切网络，避免被内容牵连，也省得加载外部字体
            blockNetworkLoads = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.setBackgroundColor((appliedColors?.background ?: Color(0xFF101014)).toArgb())
        w.addJavascriptInterface(JsBridge(this), "AndroidBridge")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // 渲染进程崩掉（内存压力/驱动问题）时不要连带整个应用退出，重建即可
                onLog?.invoke("webview render process gone")
                main.post { runCatching { web?.destroy() }; web = null; ready = false; booted = false }
                return true
            }
        }
        w.loadUrl("file:///android_asset/editor/index.html")
    }

    internal fun detach(w: WebView) {
        if (web !== w) return
        web = null
        ready = false
        booted = false
        readyState.value = false
        queue.clear()
        runCatching { w.removeJavascriptInterface("AndroidBridge") }
        runCatching { w.stopLoading() }
        runCatching { (w.parent as? ViewGroup)?.removeView(w) }
        runCatching { w.destroy() }
    }

    /** 页面切到后台时暂停 JS 定时器与渲染，回来再恢复。 */
    internal fun onHostPause() {
        runCatching { web?.onPause() }
    }

    internal fun onHostResume() {
        runCatching { web?.onResume() }
    }

    // ------------------------------------------------------------ JS → Kotlin

    // JavaBridge 走反射：类与方法都必须是可见的 public（Kotlin internal 编译后仍是 public），
    // 并且 @JavascriptInterface 的方法会被 R8 自动 keep
    internal class JsBridge(private val h: CodeMirrorHandle) {
        @JavascriptInterface
        fun post(kind: String, payload: String) {
            h.onJsMessage(kind, payload)
        }
    }

    private fun onJsMessage(kind: String, payload: String) {
        when (kind) {
            "boot" -> {
                booted = true
                sendInit()
            }
            "ready" -> {
                ready = true
                readyState.value = true
                pendingText?.let { (t, c) -> pendingText = null; pushDoc(t, c) }
                flush()
                main.post { onReady?.invoke() }
            }
            "change" -> applyChanges(payload)
            "sel" -> applySel(payload)
            "state" -> applyState(payload)
            "log" -> main.post { onLog?.invoke(payload) }
            else -> {}
        }
    }

    private fun applyChanges(payload: String) {
        val arr = runCatching { JSONArray(payload) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            doc.apply(o.optInt("from"), o.optInt("to"), o.optString("insert", ""))
        }
        scheduleSnap()
    }

    private fun applySel(payload: String) {
        // "a,b,line,col,lines,len"
        val parts = payload.split(',')
        if (parts.size < 6) return
        val n = IntArray(6) { i -> parts[i].trim().toIntOrNull() ?: 0 }
        val next = CmSel(n[0], n[1], n[2], n[3], n[4], n[5])
        if (sel.value == next) return
        main.post { sel.value = next }
        if (!snapScheduled) scheduleSnap()
    }

    private fun applyState(payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val u = o.optInt("undo", 0) > 0
        val r = o.optInt("redo", 0) > 0
        val f = o.optInt("folded", 0)
        main.post {
            if (canUndo.value != u) canUndo.value = u
            if (canRedo.value != r) canRedo.value = r
            foldedCount.intValue = f
        }
    }

    /** 文本快照节流：250ms 内多次输入只取最后一次，避免每键都触发派生计算。 */
    private fun scheduleSnap() {
        if (snapScheduled) return
        snapScheduled = true
        main.postDelayed({
            snapScheduled = false
            val t = doc.text()
            if (snapText.value !== t) snapText.value = t
        }, 250)
    }

    // ------------------------------------------------------------ Kotlin → JS

    private fun exec(code: String) {
        val w = web
        if (w == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { exec(code) }
            return
        }
        runCatching { w.evaluateJavascript(code, null) }
            .onFailure { onLog?.invoke("eval-fail: ${it.message}") }
    }

    private fun sendInit() {
        val body = JSONObject()
            .put("lang", lang)
            .put("dark", appliedDark ?: true)
            .put("readOnly", appliedReadOnly ?: false)
        exec("CM.init(${JSONObject.quote(body.toString())})")
        appliedColors?.let { exec("CM.setTheme(${JSONObject.quote(it.toCssVars(appliedDark ?: true))})") }
        if (appliedFontPx > 0) exec("CM.setFont(${appliedFontPx})")
    }

    private fun flush() {
        while (queue.isNotEmpty()) exec(queue.removeFirst())
    }

    private fun enqueue(code: String) {
        if (ready) {
            exec(code)
        } else {
            if (queue.size > 64) queue.removeFirst()
            queue.addLast(code)
        }
    }

    // ------------------------------------------------------------ 外观

    /**
     * 外观同步。每次都做字段比对 —— 这个方法在每次重组都会被调，
     * 不能无条件下发（theme reconfigure 会让 CM 重新计算样式）。
     */
    internal fun applyAppearance(colors: CmColors, dark: Boolean, fontPx: Int, readOnly: Boolean) {
        var themeChanged = false
        if (appliedColors != colors || appliedDark != dark) {
            appliedColors = colors
            appliedDark = dark
            themeChanged = true
        }
        val fontChanged = fontPx != appliedFontPx
        if (fontChanged) appliedFontPx = fontPx
        val roChanged = readOnly != appliedReadOnly
        if (roChanged) appliedReadOnly = readOnly

        if (themeChanged) {
            web?.setBackgroundColor(colors.background.toArgb())
            val css = JSONObject.quote(colors.toCssVars(dark))
            enqueue("CM.setTheme($css)")
        }
        if (fontChanged) enqueue("CM.setFont($fontPx)")
        if (roChanged) enqueue("CM.setReadOnly($readOnly)")
    }

    internal fun setGutter(on: Boolean) = enqueue("CM.setGutter($on)")
    internal fun setWordWrap(on: Boolean) = enqueue("CM.setWrap($on)")

    internal fun setLanguage(name: String) {
        if (lang == name) return
        lang = name
        enqueue("CM.setLang(${JSONObject.quote(name)})")
    }

    // ------------------------------------------------------------ 文本

    /**
     * 整篇替换（打开文件 / 切换标签 / 新建）。会清空 CM 的撤销栈 —— 这正确：
     * 新文档的历史不应跨文件继承。
     */
    internal fun setText(text: String, caret: Int = -1) {
        doc.set(text)
        if (snapText.value !== text) snapText.value = text
        pushDoc(text, caret)
    }

    private fun pushDoc(text: String, caret: Int) {
        if (!ready) {
            pendingText = text to caret
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        exec("CM.beginDoc(${text.length})")
        // 256KB 一段：再大单次 JSBridge 调用会在主线程明显卡顿
        val step = 256 * 1024
        var i = 0
        while (i < b64.length) {
            val j = minOf(i + step, b64.length)
            exec("CM.chunk('${b64.substring(i, j)}')")
            i = j
        }
        exec("CM.endDoc($caret)")
    }

    /**
     * 程序化编辑（缩进、注释、格式化、AI 插入）：只把最小差异发过去。
     * 走 CM 的普通事务 → 进同一套撤销栈，用户 Ctrl+Z 能撤掉格式化结果。
     */
    internal fun applyText(text: String, caret: Int = -1) {
        val old = doc.text()
        if (old == text) {
            if (caret >= 0) setSelectionOffsets(caret, caret)
            return
        }
        // 未就绪时直接排队整篇推送，避免「基于旧 doc 算出的差量」落到新文档上
        if (!ready) {
            setText(text, caret)
            return
        }
        val patch = TextDiff.minPatch(old, text)
        doc.set(text)
        val b64 = Base64.encodeToString(patch.insert.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        exec("CM.applyDiff(${patch.prefix},${patch.suffix},'$b64')")
        if (caret >= 0) setSelectionOffsets(caret, caret)
        scheduleSnap()
    }

    internal fun currentText(): String = doc.text()
    internal fun length(): Int = doc.length()

    internal fun insertAtCursor(text: String) {
        // 插入片段通常很小，直接走引号转义（base64 在这里反而多一次解码）
        enqueue("CM.insert(${JSONObject.quote(text)})")
        // 同步镜像：插入发生在当前选区
        val s = sel.value
        doc.apply(s.min, s.max, text)
        scheduleSnap()
    }

    // ------------------------------------------------------------ 选区 / 跳转

    internal fun setSelectionOffsets(start: Int, end: Int, visible: Boolean = true) {
        val a = start.coerceAtLeast(0)
        val b = end.coerceAtLeast(a)
        main.post { sel.value = sel.value.copy(a = a, b = b) }
        enqueue("CM.setSel($a,$b,$visible)")
    }

    internal fun scrollToLine(line0: Int) {
        enqueue("CM.scrollToLine(${line0 + 1})")
    }

    internal fun selectedText(): String {
        val s = sel.value
        val t = doc.text()
        return if (s.hasSelection && s.max <= t.length) t.substring(s.min, s.max) else ""
    }

    // ------------------------------------------------------------ 命令

    internal fun undo() = enqueue("CM.undo()")
    internal fun redo() = enqueue("CM.redo()")

    internal fun foldAll() = enqueue("CM.foldAll()")
    internal fun unfoldAll() = enqueue("CM.unfoldAll()")
    internal fun foldAtCursor() = enqueue("CM.foldAtCursor()")
    internal fun unfoldAtCursor() = enqueue("CM.unfoldAtCursor()")
    internal fun jumpBlock(dir: Int) = enqueue("CM.jumpBlock(${if (dir >= 0) 1 else -1})")

    internal fun openSearch() = enqueue("CM.cmd('search')")
    internal fun findNext() = enqueue("CM.cmd('findnext')")
    internal fun findPrev() = enqueue("CM.cmd('findprev')")
    internal fun closeSearch() = enqueue("CM.cmd('closesearch')")

    /** 转发给 CM 的内置命令（行操作等），args 用 JSON 字符串。 */
    internal fun command(name: String, arg: String? = null) {
        val a = if (arg == null) "" else ", ${JSONObject.quote(arg)}"
        enqueue("CM.cmd(${JSONObject.quote(name)}$a)")
    }

    internal fun focus() = enqueue("CM.focus()")

    // ------------------------------------------------------------ 装饰

    internal fun setDiagnostics(items: List<CmDiag>) {
        if (items.isEmpty()) {
            enqueue("CM.setDiagnostics('[]')")
            return
        }
        val arr = JSONArray()
        for (d in items) {
            arr.put(JSONObject().put("line", d.line1).put("sev", d.severity).put("msg", d.message))
        }
        enqueue("CM.setDiagnostics(${JSONObject.quote(arr.toString())})")
    }

    /** 差异标记：line 为 1 基，kind = add / mod / del（第三个分量留作以后的说明文案） */
    internal fun setDiffMarks(marks: List<Triple<Int, String, String>>) {
        val arr = JSONArray()
        for ((line, kind, _) in marks) arr.put(JSONObject().put("line", line).put("kind", kind))
        enqueue("CM.setDiff(${JSONObject.quote(arr.toString())})")
    }
}

/**
 * Compose 包装。刻意不套任何 Scroll 修饰符：滚动由 CM 自己接管，
 * Compose 再套一层会让「手指在代码区拖动」变成两套滚动的竞争。
 */
@Composable
internal fun CodeMirrorEditor(
    handle: CodeMirrorHandle,
    colors: CmColors,
    dark: Boolean,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 13,
    readOnly: Boolean = false,
) {
    val density = LocalDensity.current.density
    val fontPx = (fontSizeSp * density).toInt().coerceIn(8, 72)

    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).also { handle.attach(it) }
        },
        update = { handle.applyAppearance(colors, dark, fontPx, readOnly) },
        onRelease = { handle.detach(it) },
    )
}
