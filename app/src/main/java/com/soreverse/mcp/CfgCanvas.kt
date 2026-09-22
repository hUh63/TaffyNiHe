// 塔菲逆核: 分析页 CFG 图形化画布（纯 Compose Canvas 自绘，无 ELK/dagre 依赖）。
//
// 布局（Sugiyama 简化版，v1.3.18 精致化升级）：
//   1. 解析 rizin rzCfg 的 JSON（basicBlocks/blocks + jump/fail + edges，地址为 hex 字符串）。
//   2. BFS 最短距离分层；孤立/不可达块在主图下方单独成行。
//   3. 跨层长边拆分为虚拟节点（dummy node），使每条边只连接相邻层 —— 消除「边斜穿节点」。
//   4. 层内定序：DFS 前序初值 → median 启发式（相邻层邻居位置的中位数），4~8 轮上下交替迭代，
//      稳定排序（java.util.Collections.sort）保证同输入结果可重复。
//   5. 端口分配：下出边按目标 x 排序均分到节点底边，上入边按源 x 排序均分到顶边，
//      回边走右侧端口并按目标 y 分散 —— 多条边不再重叠在同一像素点。
//   6. 坐标细化：层分配（y）与 x 坐标分离；x 用相邻层已定位节点的中位数迭代收敛 + 重叠消除。
//   7. 节点宽度按内容自适应（Paint.measureText 量地址与摘要），摘要支持 1~2 行。
//   8. 大图保护：块数 > 400 时自动关闭虚拟节点、迭代降到 2 轮；另有「简化视图」只画块骨架。
//
// 渲染：
//   - 边为**圆角正交折线**（拐角圆角 9dp，三次贝塞尔过渡；cap/join 亦为 Round）；实心箭头且随线宽缩放；
//     jump=主题色实线 / fail=橙色虚线 / 回边=粉色加粗醒目（明显区分）。
//   - 节点按角色分层描边 + 左侧色条：入口块(无前驱=绿) / 返回块(无后继=青) / 循环头(有回边指向=紫)
//     / 普通块(描边色)；选中态高亮 + 加粗描边。
//   - 背景细点阵网格（随缩放淡出）；缩放很小时隐藏块内文字只留色块。
//   - 交互：双指缩放 / 单指拖拽平移 / 点击选中 / 适应屏幕 / 缩放到 100% / 定位入口块；
//     下方信息条显示选中块的地址范围与后继列表（jump→ / fail→ 目标地址）。
package com.soreverse.mcp

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val NODE_W_MIN_DP = 98f
private const val NODE_W_MAX_DP = 300f
private const val NODE_H_MIN_DP = 40f
private const val LAYER_GAP_DP = 74f
private const val LANE_GAP_DP = 30f
private const val BACK_CHANNEL_DP = 24f
private const val PAD_X_DP = 8f
private const val PAD_Y_DP = 7f
private const val ADDR_LINE_DP = 15f
private const val SUM_LINE_DP = 13f
private const val DUMMY_W_DP = 8f
private const val MAX_SUMMARY_CHARS = 140
private const val DUMMY_NODE_LIMIT = 400
private const val LAYOUT_ITERATIONS_SMALL = 8
private const val LAYOUT_ITERATIONS_LARGE = 2
private const val TEXT_HIDE_SCALE = 0.42f

// ───────────────────────── 数据模型 ─────────────────────────

/** 单个基本块（地址 / 范围 / 首行指令摘要）。 */
internal data class CfgBlock(
    val index: Int,
    val addrText: String,
    val addrValue: Long,
    val endText: String,
    val summary: String,
)

/** 一条控制流边。kind: jump | fail | edge。 */
internal data class CfgEdge(val from: Int, val to: Int, val kind: String)

/** 解析后的 CFG 图。 */
internal data class CfgGraph(
    val functionName: String,
    val functionVa: String,
    val blocks: List<CfgBlock>,
    val edges: List<CfgEdge>,
)

/**
 * 布局后的节点矩形（世界坐标，单位 px；原点 = 图中心）。
 * index < 0 表示虚拟节点（dummy node，仅参与分层排序与边路由，不绘制、不可点选）。
 */
internal class CfgNodeBox(
    val index: Int,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val addrText: String = "",
    val lines: List<String> = emptyList(),
) {
    val isDummy: Boolean get() = index < 0
    val left: Float get() = cx - w / 2f
    val right: Float get() = cx + w / 2f
    val top: Float get() = cy - h / 2f
    val bottom: Float get() = cy + h / 2f

    fun contains(x: Float, y: Float): Boolean = !isDummy && x in left..right && y in top..bottom
}

/** 一条已经算好端口的边路由（世界坐标折线）。 */
internal class CfgRoute(
    val from: Int,
    val to: Int,
    val kind: String,
    val isBack: Boolean,
    val isSelf: Boolean,
    val points: List<Offset>,
)

/** 布局结果：节点盒（含虚拟节点）+ 边路由 + 尺寸 + 角色集合。 */
internal class CfgLayoutResult(
    val boxes: List<CfgNodeBox>,
    val routes: List<CfgRoute>,
    val width: Float,
    val height: Float,
    val entryIndex: Int,
    val loopHeadIndices: Set<Int>,
    val returnIndices: Set<Int>,
)

/** 布局过程中的可变节点。 */
private class LNode(val index: Int, var key: Int) {
    var layer = 0
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    val out = ArrayList<LNode>()
    val inc = ArrayList<LNode>()
}

private val LNode.left: Float get() = x - w / 2f
private val LNode.right: Float get() = x + w / 2f
private val LNode.top: Float get() = y - h / 2f
private val LNode.bottom: Float get() = y + h / 2f

/** 一条待路由的边（真实端点 + 可选虚拟节点链）。 */
private class RouteSeed(
    val from: Int,
    val to: Int,
    val kind: String,
    val isBack: Boolean,
    val isSelf: Boolean,
    val chain: List<LNode>,
)

// ───────────────────────── JSON 解析 ─────────────────────────

/** 读取可能为 null 的字符串字段（rizin 用 JSON null 表示「无后继」）。 */
private fun optNullableText(o: JSONObject, key: String): String? {
    val raw = o.opt(key) ?: return null
    if (raw == JSONObject.NULL) return null
    val s = raw.toString().trim()
    if (s.isEmpty() || s == "null") return null
    return s
}

private fun firstNonBlankText(o: JSONObject, vararg keys: String): String {
    for (k in keys) {
        val v = optNullableText(o, k) ?: continue
        if (v.isNotBlank()) return v
    }
    return ""
}

/** 解析 "0x1234" / "1234" 形式的地址；失败返回 -1。 */
internal fun parseCfgAddr(text: String): Long {
    val t = text.trim()
    if (t.isEmpty()) return -1L
    val neg = t.startsWith("-")
    var body = if (neg) t.substring(1) else t
    if (body.startsWith("0x") || body.startsWith("0X")) body = body.substring(2)
    if (body.isEmpty()) return -1L
    val hasHexLetter = body.any { it in 'a'..'f' || it in 'A'..'F' }
    val v = if (hasHexLetter) body.toLongOrNull(16) else (body.toLongOrNull(10) ?: body.toLongOrNull(16))
    val r = v ?: return -1L
    return if (neg) -r else r
}

/** 解析 rzCfg 返回的 JSON（容错：字段缺失/为空/结构异常都不抛异常）。 */
internal fun parseCfgGraph(json: String): CfgGraph {
    if (json.isBlank()) return CfgGraph("", "", emptyList(), emptyList())
    return try {
        val root = JSONObject(json)
        val name = firstNonBlankText(root, "functionName", "name", "function")
        val va = firstNonBlankText(root, "functionVa", "functionAddress", "addr")
        val arr = root.optJSONArray("basicBlocks") ?: root.optJSONArray("blocks") ?: JSONArray()
        val count = arr.length()
        val blocks = ArrayList<CfgBlock>(count)
        val byText = HashMap<String, Int>()
        val byValue = HashMap<Long, Int>()
        for (i in 0 until count) {
            val o = arr.optJSONObject(i) ?: JSONObject()
            val addrText = firstNonBlankText(o, "addr", "startAddr", "start", "address", "va")
            val endText = firstNonBlankText(o, "endAddr", "end")
            val rawSummary = firstNonBlankText(
                o, "summary", "firstLine", "firstInsn", "insn", "opcode", "disasm", "asm", "text", "label",
            ).lineSequence().firstOrNull().orEmpty().trim()
            val summary = if (rawSummary.length > MAX_SUMMARY_CHARS) rawSummary.take(MAX_SUMMARY_CHARS) + "…" else rawSummary
            val label = addrText.ifBlank { if (count == 1) "entry" else "block#$i" }
            val value = if (addrText.isBlank()) -1L else parseCfgAddr(addrText)
            blocks += CfgBlock(i, label, value, endText, summary)
            byText[label.trim().lowercase()] = i
            if (addrText.isNotBlank()) byText[addrText.trim().lowercase()] = i
            if (value >= 0L) byValue[value] = i
        }
        fun resolve(text: String?): Int {
            if (text.isNullOrBlank()) return -1
            val t = text.trim()
            byText[t.lowercase()]?.let { return it }
            val v = parseCfgAddr(t)
            if (v >= 0L) byValue[v]?.let { return it }
            return -1
        }
        val edges = ArrayList<CfgEdge>()
        val seen = HashSet<String>()
        fun addEdge(from: Int, to: Int, kind: String) {
            if (from < 0 || to < 0 || from >= count || to >= count) return
            if (seen.add("$from|$to|$kind")) edges += CfgEdge(from, to, kind)
        }
        for (i in 0 until count) {
            val o = arr.optJSONObject(i) ?: continue
            val jumpText = optNullableText(o, "jump")
            val jumpValid = if (o.has("jumpValid")) o.optBoolean("jumpValid") else jumpText != null
            if (jumpValid && jumpText != null) {
                val t = resolve(jumpText)
                if (t >= 0) addEdge(i, t, "jump")
            }
            val failText = optNullableText(o, "fail")
            val failValid = if (o.has("failValid")) o.optBoolean("failValid") else failText != null
            if (failValid && failText != null) {
                val t = resolve(failText)
                if (t >= 0) addEdge(i, t, "fail")
            }
        }
        val explicit = root.optJSONArray("edges")
        if (explicit != null) {
            for (i in 0 until explicit.length()) {
                val e = explicit.optJSONObject(i) ?: continue
                val f = resolve(optNullableText(e, "from") ?: e.optString("src"))
                val t = resolve(optNullableText(e, "to") ?: e.optString("dst"))
                if (f >= 0 && t >= 0) addEdge(f, t, "edge")
            }
        }
        CfgGraph(name, va, blocks, edges)
    } catch (_: Exception) {
        CfgGraph("", "", emptyList(), emptyList())
    }
}

// ───────────────────────── 文本度量 ─────────────────────────

/** 贪心按像素宽度把摘要折成 1..maxLines 行；最后一行超宽时截断并加省略号。 */
private fun wrapSummary(text: String, paint: Paint, avail: Float, maxLines: Int): List<String> {
    val src = text.trim()
    if (src.isEmpty() || avail <= 8f || maxLines <= 0) return emptyList()
    val lines = ArrayList<String>(maxLines)
    var rest = src
    while (rest.isNotEmpty() && lines.size < maxLines) {
        if (paint.measureText(rest) <= avail || lines.size == maxLines - 1) {
            var s: String = rest
            if (paint.measureText(s) > avail) {
                var end = s.length
                while (end > 1 && paint.measureText(s.substring(0, end) + "…") > avail) end--
                s = s.substring(0, end).trimEnd() + "…"
            }
            lines.add(s)
            rest = ""
        } else {
            var end = 1
            while (end < rest.length && paint.measureText(rest.substring(0, end + 1)) <= avail) end++
            var cut = end
            for (j in end downTo max(1, end - 12)) {
                val c = rest[j - 1]
                if (c == ' ' || c == ',' || c == ';' || c == ')' || c == ']' || c == '>') {
                    cut = j
                    break
                }
            }
            lines.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trimStart()
        }
    }
    return lines
}

// ───────────────────────── 分层布局（Sugiyama 简化版） ─────────────────────────

/**
 * 分层 → 层内定序（median+barycenter 多轮迭代）→ 端口分配 → x 坐标收敛 → 边路由。
 * 空图 / 非法输入返回空布局；块数超过阈值自动关闭虚拟节点并降低迭代轮数。
 */
internal fun layoutCfgGraph(graph: CfgGraph, density: Float): CfgLayoutResult {
    val n = graph.blocks.size
    if (n == 0 || density <= 0f) {
        return CfgLayoutResult(emptyList(), emptyList(), 0f, 0f, -1, emptySet(), emptySet())
    }
    val useDummies = n <= DUMMY_NODE_LIMIT
    val iterations = if (useDummies) LAYOUT_ITERATIONS_SMALL else LAYOUT_ITERATIONS_LARGE

    val padX = PAD_X_DP * density
    val padY = PAD_Y_DP * density
    val addrLine = ADDR_LINE_DP * density
    val sumLine = SUM_LINE_DP * density
    val minW = NODE_W_MIN_DP * density
    val maxW = NODE_W_MAX_DP * density
    val minH = NODE_H_MIN_DP * density
    val laneGap = LANE_GAP_DP * density
    val layerGap = LAYER_GAP_DP * density

    val paintAddr = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textSize = 10.5f * density }
    val paintSum = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textSize = 9f * density }

    // ── 1. 真实节点尺寸：按内容自适应 ──
    val widths = FloatArray(n)
    val heights = FloatArray(n)
    val summaries = arrayOfNulls<List<String>>(n)
    for (i in 0 until n) {
        val b = graph.blocks[i]
        val addrW = paintAddr.measureText(b.addrText)
        val sumW = paintSum.measureText(b.summary)
        val desired = max(addrW, min(sumW, maxW - padX * 2f)) + padX * 2f
        var w = desired.coerceIn(minW, maxW)
        var lines = wrapSummary(b.summary, paintSum, w - padX * 2f, 2)
        var maxLineW = addrW
        lines.forEach { maxLineW = max(maxLineW, paintSum.measureText(it)) }
        w = (maxLineW + padX * 2f).coerceIn(minW, maxW)
        lines = wrapSummary(b.summary, paintSum, w - padX * 2f, 2)
        widths[i] = w
        heights[i] = max(padY * 2f + addrLine + lines.size * sumLine, minH)
        summaries[i] = lines
    }

    // ── 2. 真实节点邻接 + BFS 分层 ──
    val succ = Array(n) { ArrayList<Int>() }
    val pred = Array(n) { ArrayList<Int>() }
    graph.edges.forEach { e ->
        if (e.from == e.to) return@forEach
        if (e.from !in 0 until n || e.to !in 0 until n) return@forEach
        if (!succ[e.from].contains(e.to)) succ[e.from].add(e.to)
        if (!pred[e.to].contains(e.from)) pred[e.to].add(e.from)
    }
    val layer = IntArray(n) { -1 }
    val roots = (0 until n).filter { pred[it].isEmpty() }
    val starts = if (roots.isNotEmpty()) roots else listOf(0)
    val queue = ArrayDeque<Int>()
    starts.forEach {
        layer[it] = 0
        queue.addLast(it)
    }
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        succ[cur].forEach { nx ->
            if (layer[nx] < 0) {
                layer[nx] = layer[cur] + 1
                queue.addLast(nx)
            }
        }
    }
    var maxLayer = layer.filter { it >= 0 }.maxOrNull() ?: 0
    var orphan = 0
    for (i in 0 until n) {
        if (layer[i] < 0) {
            layer[i] = maxLayer + 1 + orphan / 4
            orphan++
        }
    }
    maxLayer = layer.maxOrNull() ?: maxLayer
    if (maxLayer < 0) maxLayer = 0

    // ── 3. DFS 前序作为层内初始顺序（环用 visited 短路）──
    val order = IntArray(n)
    var ordCount = 0
    run {
        val visited = BooleanArray(n)
        val stack = ArrayDeque<Int>()
        val seeds = ArrayList<Int>(2 * n)
        seeds += starts
        for (i in 0 until n) seeds += i
        seeds.forEach { s ->
            if (visited[s]) return@forEach
            stack.addLast(s)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                if (visited[c]) continue
                visited[c] = true
                order[c] = ordCount++
                val next = succ[c].filter { !visited[it] }
                for (k in next.indices.reversed()) stack.addLast(next[k])
            }
        }
    }

    // ── 4. 建立节点表（真实 + 跨层长边拆出的虚拟节点）──
    val nodes = ArrayList<LNode>(n)
    for (i in 0 until n) {
        val ln = LNode(i, order[i])
        ln.layer = layer[i].coerceIn(0, maxLayer)
        ln.w = widths[i]
        ln.h = heights[i]
        nodes.add(ln)
    }
    val realNodes: List<LNode> = nodes.toList()
    var ordSeq = n
    val seeds = ArrayList<RouteSeed>(graph.edges.size)
    graph.edges.forEach { e ->
        if (e.from !in 0 until n || e.to !in 0 until n) return@forEach
        if (e.from == e.to) {
            seeds += RouteSeed(e.from, e.to, e.kind, false, true, emptyList())
            return@forEach
        }
        val a = realNodes[e.from]
        val b = realNodes[e.to]
        val isBack = b.layer <= a.layer
        if (useDummies && b.layer > a.layer + 1) {
            val chain = ArrayList<LNode>(b.layer - a.layer - 1)
            var prev = a
            for (li in a.layer + 1 until b.layer) {
                val d = LNode(-1, ordSeq++)
                d.layer = li
                d.w = DUMMY_W_DP * density
                d.h = 0f
                nodes.add(d)
                chain.add(d)
                prev.out.add(d)
                d.inc.add(prev)
                prev = d
            }
            prev.out.add(b)
            b.inc.add(prev)
            seeds += RouteSeed(e.from, e.to, e.kind, false, false, chain)
        } else {
            a.out.add(b)
            b.inc.add(a)
            seeds += RouteSeed(e.from, e.to, e.kind, isBack, false, emptyList())
        }
    }

    val maxL = nodes.maxOf { it.layer }
    val layers = ArrayList<MutableList<LNode>>(maxL + 1)
    for (l in 0..maxL) layers.add(ArrayList())
    nodes.forEach { layers[it.layer.coerceIn(0, maxL)].add(it) }
    layers.forEach { l -> l.sortBy { it.key } }

    // ── 5. 层内定序：median 启发式 + 多轮上下交替迭代（稳定排序）──
    fun positionsOf(l: List<LNode>): HashMap<LNode, Int> {
        val m = HashMap<LNode, Int>(l.size * 2)
        l.forEachIndexed { i, nd -> m[nd] = i }
        return m
    }

    fun orderLayer(l: MutableList<LNode>, ref: Map<LNode, Int>, useIn: Boolean) {
        if (l.size <= 1) return
        val scored = ArrayList<Triple<LNode, Float, Int>>(l.size)
        l.forEach { nd ->
            val nb = (if (useIn) nd.inc else nd.out).mapNotNull { ref[it] }.sorted()
            val med = when {
                nb.isEmpty() -> -1f
                nb.size % 2 == 1 -> nb[nb.size / 2].toFloat()
                else -> (nb[nb.size / 2 - 1] + nb[nb.size / 2]) / 2f
            }
            scored.add(Triple(nd, med, nd.key))
        }
        scored.sortWith(Comparator { x, y ->
            val mx = if (x.second < 0f) Float.MAX_VALUE else x.second
            val my = if (y.second < 0f) Float.MAX_VALUE else y.second
            val c = mx.compareTo(my)
            if (c != 0) c else x.third.compareTo(y.third)
        })
        l.clear()
        scored.forEach { l.add(it.first) }
    }

    repeat(iterations) {
        for (li in 1 until layers.size) orderLayer(layers[li], positionsOf(layers[li - 1]), true)
        for (li in layers.size - 2 downTo 0) orderLayer(layers[li], positionsOf(layers[li + 1]), false)
    }

    // ── 6. x 坐标：中位数迭代收敛 + 重叠消除（层分配与 x 分离）──
    layers.forEach { l ->
        var cursor = 0f
        l.forEach { nd ->
            nd.x = cursor + nd.w / 2f
            cursor += nd.w + laneGap
        }
    }

    fun placeLayer(l: List<LNode>, desired: HashMap<LNode, Float>) {
        var prevRight: Float? = null
        l.forEach { nd ->
            val want = desired[nd] ?: nd.x
            val minCenter = prevRight?.let { it + laneGap + nd.w / 2f }
            nd.x = if (minCenter == null) want else max(want, minCenter)
            prevRight = nd.x + nd.w / 2f
        }
    }

    fun medianNeighborX(nd: LNode, useIn: Boolean): Float? {
        val xs = (if (useIn) nd.inc else nd.out).map { it.x }.sorted()
        if (xs.isEmpty()) return null
        return if (xs.size % 2 == 1) xs[xs.size / 2] else (xs[xs.size / 2 - 1] + xs[xs.size / 2]) / 2f
    }

    repeat(4) {
        for (li in 1 until layers.size) {
            val desired = HashMap<LNode, Float>()
            layers[li].forEach { nd -> medianNeighborX(nd, true)?.let { desired[nd] = it } }
            placeLayer(layers[li], desired)
        }
        for (li in layers.size - 2 downTo 0) {
            val desired = HashMap<LNode, Float>()
            layers[li].forEach { nd -> medianNeighborX(nd, false)?.let { desired[nd] = it } }
            placeLayer(layers[li], desired)
        }
    }

    // ── 7. y 坐标：按层高累计 ──
    val layerH = FloatArray(layers.size) { 0f }
    layers.forEachIndexed { li, l -> layerH[li] = l.maxOfOrNull { it.h } ?: 0f }
    val layerTop = FloatArray(layers.size)
    var acc = 0f
    for (li in layers.indices) {
        layerTop[li] = acc
        acc += layerH[li] + layerGap
    }
    nodes.forEach { it.y = layerTop[it.layer] + layerH[it.layer] / 2f }

    // ── 8. 整图居中（世界坐标原点 = 图中心）──
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    nodes.forEach { nd ->
        minX = min(minX, nd.left)
        maxX = max(maxX, nd.right)
        minY = min(minY, nd.y - nd.h / 2f)
        maxY = max(maxY, nd.y + nd.h / 2f)
    }
    if (minX > maxX || minY > maxY) {
        return CfgLayoutResult(emptyList(), emptyList(), 0f, 0f, -1, emptySet(), emptySet())
    }
    val offX = (minX + maxX) / 2f
    val offY = (minY + maxY) / 2f
    nodes.forEach {
        it.x -= offX
        it.y -= offY
    }

    // ── 9. 端口分配：下出/上入均分，回边走右侧端口 ──
    val bottomOut = HashMap<LNode, ArrayList<RouteSeed>>()
    val topIn = HashMap<LNode, ArrayList<RouteSeed>>()
    val rightOut = HashMap<LNode, ArrayList<RouteSeed>>()
    val rightIn = HashMap<LNode, ArrayList<RouteSeed>>()
    seeds.forEach { sd ->
        if (sd.isSelf) return@forEach
        val a = realNodes[sd.from]
        val b = realNodes[sd.to]
        if (sd.isBack) {
            rightOut.getOrPut(a) { ArrayList() }.add(sd)
            rightIn.getOrPut(b) { ArrayList() }.add(sd)
        } else {
            bottomOut.getOrPut(a) { ArrayList() }.add(sd)
            topIn.getOrPut(b) { ArrayList() }.add(sd)
        }
    }
    fun firstTargetX(sd: RouteSeed): Float = sd.chain.firstOrNull()?.x ?: realNodes[sd.to].x
    bottomOut.forEach { (_, list) -> list.sortBy { firstTargetX(it) } }
    topIn.forEach { (_, list) -> list.sortBy { realNodes[it.from].x } }
    rightOut.forEach { (_, list) -> list.sortBy { realNodes[it.to].y } }
    rightIn.forEach { (_, list) -> list.sortBy { realNodes[it.from].y } }

    fun bottomPortX(nd: LNode, sd: RouteSeed): Float {
        val list = bottomOut[nd] ?: return nd.x
        val k = list.size
        val i = list.indexOfFirst { it === sd }
        val usable = (nd.w - padX * 2f).coerceAtLeast(6f)
        return nd.left + padX + usable * (i + 1) / (k + 1)
    }

    fun topPortX(nd: LNode, sd: RouteSeed): Float {
        val list = topIn[nd] ?: return nd.x
        val k = list.size
        val i = list.indexOfFirst { it === sd }
        val usable = (nd.w - padX * 2f).coerceAtLeast(6f)
        return nd.left + padX + usable * (i + 1) / (k + 1)
    }

    fun sidePortY(nd: LNode, sd: RouteSeed, outgoing: Boolean): Float {
        val list = (if (outgoing) rightOut[nd] else rightIn[nd]) ?: return nd.y
        val k = list.size
        val i = list.indexOfFirst { it === sd }
        val usable = (nd.h - padY * 2f).coerceAtLeast(6f)
        return nd.top + padY + usable * (i + 1) / (k + 1)
    }

    // ── 10. 生成正交折线路由 ──
    val maxRight = nodes.maxOf { it.right }
    var backIdx = 0
    val routes = ArrayList<CfgRoute>(seeds.size)
    seeds.forEach { sd ->
        val a = realNodes[sd.from]
        val b = realNodes[sd.to]
        val pts: MutableList<Offset> = ArrayList(8)
        when {
            sd.isSelf -> {
                val lift = 15f * density
                val ex = a.x + a.w * 0.30f
                val en = a.x - a.w * 0.30f
                pts.add(Offset(ex, a.top))
                pts.add(Offset(ex, a.top - lift))
                pts.add(Offset(en, a.top - lift))
                pts.add(Offset(en, a.top))
            }
            sd.isBack -> {
                backIdx++
                val channel = maxRight + BACK_CHANNEL_DP * density * backIdx
                val exitY = sidePortY(a, sd, true)
                val entryY = sidePortY(b, sd, false)
                pts.add(Offset(a.right, exitY))
                pts.add(Offset(channel, exitY))
                pts.add(Offset(channel, entryY))
                pts.add(Offset(b.right + 2f, entryY))
            }
            else -> {
                val exitX = bottomPortX(a, sd)
                val entryX = topPortX(b, sd)
                pts.add(Offset(exitX, a.bottom))
                var prevX = exitX
                var prevBottom = a.bottom
                sd.chain.forEach { d ->
                    val my = (prevBottom + d.y) / 2f
                    pts.add(Offset(prevX, my))
                    pts.add(Offset(d.x, my))
                    prevX = d.x
                    prevBottom = d.y
                }
                val my = (prevBottom + b.top) / 2f
                pts.add(Offset(prevX, my))
                pts.add(Offset(entryX, my))
                pts.add(Offset(entryX, b.top))
            }
        }
        routes.add(CfgRoute(sd.from, sd.to, sd.kind, sd.isBack, sd.isSelf, pts))
    }

    // ── 11. 角色集合 + 节点盒 ──
    val entryIndex = (0 until n).filter { pred[it].isEmpty() }.minByOrNull { layer[it] } ?: -1
    val loopHeads = LinkedHashSet<Int>()
    val returns = LinkedHashSet<Int>()
    graph.edges.forEach { e ->
        if (e.from == e.to || e.from !in 0 until n || e.to !in 0 until n) return@forEach
        if (layer[e.to] <= layer[e.from]) loopHeads.add(e.to)
    }
    for (i in 0 until n) if (succ[i].isEmpty()) returns.add(i)

    val boxes = nodes.map { nd ->
        val real = nd.index
        CfgNodeBox(
            real,
            nd.x,
            nd.y,
            nd.w,
            nd.h,
            addrText = if (real >= 0) graph.blocks[real].addrText else "",
            lines = if (real >= 0) (summaries[real] ?: emptyList()) else emptyList(),
        )
    }
    return CfgLayoutResult(boxes, routes, maxX - minX, maxY - minY, entryIndex, loopHeads, returns)
}

// ───────────────────────── Canvas 绘制 ─────────────────────────

/** 圆角正交折线：拐角用三次贝塞尔近似二次圆角（避免 quadraticTo/quadraticBezierTo 的版本差异）。 */
private fun roundedOrthoPath(pts: List<Offset>, radius: Float): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    if (pts.size == 2) {
        path.lineTo(pts[1].x, pts[1].y)
        return path
    }
    for (i in 1 until pts.size - 1) {
        val p = pts[i - 1]
        val c = pts[i]
        val n = pts[i + 1]
        val inLen = sqrt((c.x - p.x) * (c.x - p.x) + (c.y - p.y) * (c.y - p.y))
        val outLen = sqrt((n.x - c.x) * (n.x - c.x) + (n.y - c.y) * (n.y - c.y))
        if (inLen < 0.5f || outLen < 0.5f) {
            path.lineTo(c.x, c.y)
            continue
        }
        val r = min(radius, min(inLen / 2f, outLen / 2f))
        if (r < 0.5f) {
            path.lineTo(c.x, c.y)
            continue
        }
        val ax = c.x - (c.x - p.x) / inLen * r
        val ay = c.y - (c.y - p.y) / inLen * r
        val bx = c.x + (n.x - c.x) / outLen * r
        val by = c.y + (n.y - c.y) / outLen * r
        path.lineTo(ax, ay)
        // a -> c -> b 的二次圆角，用三次贝塞尔等价近似
        path.cubicTo(
            ax + (c.x - ax) * 2f / 3f, ay + (c.y - ay) * 2f / 3f,
            bx + (c.x - bx) * 2f / 3f, by + (c.y - by) * 2f / 3f,
            bx, by,
        )
    }
    val last = pts[pts.size - 1]
    path.lineTo(last.x, last.y)
    return path
}

private fun DrawScope.arrowHead(tip: Offset, from: Offset, color: Color, sizePx: Float) {
    var dx = tip.x - from.x
    var dy = tip.y - from.y
    var len = sqrt(dx * dx + dy * dy)
    if (len < 0.001f) {
        dx = 0f
        dy = 1f
        len = 1f
    }
    val ux = dx / len
    val uy = dy / len
    val px = -uy
    val py = ux
    val baseX = tip.x - ux * sizePx
    val baseY = tip.y - uy * sizePx
    val path = Path()
    path.moveTo(tip.x, tip.y)
    path.lineTo(baseX + px * sizePx * 0.5f, baseY + py * sizePx * 0.5f)
    path.lineTo(baseX - px * sizePx * 0.5f, baseY - py * sizePx * 0.5f)
    path.close()
    drawPath(path, color)
}

private fun fitText(paint: Paint, text: String, maxWidth: Float): String {
    if (text.isEmpty() || maxWidth <= 0f) return ""
    if (paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 1) {
        val candidate = text.take(end) + "…"
        if (paint.measureText(candidate) <= maxWidth) return candidate
        end--
    }
    return ""
}

@Composable
private fun CfgChip(text: String, tint: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppShape.xs),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
}

// ───────────────────────── 组合视图 ─────────────────────────

/**
 * CFG 图形画布。json 为 rzCfg 原始 JSON；空/非法 JSON 时显示「无 CFG 数据」而不是崩溃。
 */
@Composable
internal fun CfgCanvas(json: String, zh: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val graph = remember(json) { parseCfgGraph(json) }
    val layout = remember(graph, density) { layoutCfgGraph(graph, density) }
    // rzCfg 返回 err JSON 时给出结构化提示，而不是只显示「空图」。
    val errHint = remember(json) {
        runCatching {
            val o = JSONObject(json)
            o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { o.optString("message") }
        }.getOrDefault("")
    }

    var scale by remember(graph) { mutableStateOf(1f) }
    var pan by remember(graph) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(graph) { mutableStateOf(-1) }
    var fitted by remember(graph) { mutableStateOf(false) }
    // 大图自动进入简化视图（只画块骨架），也可手动切换。
    var simpleView by remember(graph) { mutableStateOf(graph.blocks.size > 260) }

    val colors = MaterialTheme.colorScheme
    val jumpColor = colors.primary
    val failColor = AppPalette.orange
    val backColor = AppPalette.pink
    val entryColor = AppPalette.green
    val loopColor = AppPalette.purple
    val returnColor = AppPalette.teal

    fun applyFit() {
        if (viewport.width <= 0 || viewport.height <= 0) return
        if (layout.boxes.isEmpty()) {
            scale = 1f
            pan = Offset.Zero
            return
        }
        val w = layout.width.coerceAtLeast(1f) + 80f
        val h = layout.height.coerceAtLeast(1f) + 80f
        scale = min(viewport.width / w, viewport.height / h).coerceIn(0.12f, 2.5f)
        pan = Offset.Zero
    }

    fun zoomReset() {
        scale = 1f
        pan = Offset.Zero
        fitted = true
    }

    fun focusEntry() {
        val box = layout.boxes.firstOrNull { it.index == layout.entryIndex } ?: return
        if (viewport.width <= 0 || viewport.height <= 0) return
        pan = Offset(-box.cx * scale, -box.cy * scale)
        selected = box.index
    }

    LaunchedEffect(layout, viewport) {
        if (!fitted && viewport.width > 0 && viewport.height > 0 && layout.boxes.isNotEmpty()) {
            applyFit()
            fitted = true
        }
    }

    val shape = RoundedCornerShape(AppShape.md)
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(colors.surfaceContainerLow)
                .border(BorderStroke(1.dp, colors.outlineVariant), shape)
                .onSizeChanged { viewport = it }
                .pointerInput(layout) {
                    detectTapGestures { pos ->
                        val originX = viewport.width / 2f + pan.x
                        val originY = viewport.height / 2f + pan.y
                        val wx = (pos.x - originX) / scale
                        val wy = (pos.y - originY) / scale
                        selected = layout.boxes.lastOrNull { it.contains(wx, wy) }?.index ?: -1
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.12f, 6f)
                        pan += panChange
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCfgScene(layout, colors, density, scale, pan, size, simpleView, selected)
            }

            if (layout.boxes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (zh) "无 CFG 数据（空图）" else "No CFG data (empty graph)",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        if (errHint.isNotBlank()) {
                            Text(
                                errHint,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 左上角：统计
            Surface(
                shape = RoundedCornerShape(AppShape.xs),
                color = colors.surfaceVariant.copy(alpha = 0.60f),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            ) {
                Text(
                    "blocks ${graph.blocks.size} · edges ${graph.edges.size} · ${(scale * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }

            // 右上角：视图工具（FlowRow 窄屏自动换行）
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CfgChip(if (zh) "适应屏幕" else "Fit", colors.primary) { applyFit() }
                    CfgChip("100%", colors.primary) { zoomReset() }
                    CfgChip(if (zh) "定位入口" else "Entry", entryColor) { focusEntry() }
                    CfgChip(
                        if (simpleView) (if (zh) "完整视图" else "Full") else (if (zh) "简化视图" else "Simple"),
                        colors.onSurfaceVariant,
                    ) { simpleView = !simpleView }
                }
            }
        }

        Spacer(Modifier.size(6.dp))

        // ── 下方信息条：选中块的地址范围 + 后继列表 ──
        Surface(
            shape = RoundedCornerShape(AppShape.sm),
            color = colors.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val block = if (selected >= 0) graph.blocks.getOrNull(selected) else null
            val succ = if (selected >= 0) graph.edges.filter { it.from == selected } else emptyList()
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (block == null) {
                    Text(
                        if (zh) "点击节点查看块详情 · 双指缩放 / 单指拖拽平移" else "Tap a node for details · pinch to zoom / drag to pan",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${if (zh) "块" else "block"} #${block.index}  ${block.addrText}" +
                            if (block.endText.isNotBlank()) " … ${block.endText}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    if (block.summary.isNotBlank()) {
                        Text(
                            block.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (succ.isEmpty()) {
                            if (zh) "后继：无（汇聚/返回块）" else "successors: none"
                        } else {
                            (if (zh) "后继：" else "successors: ") + succ.joinToString("  ") { e ->
                                val target = graph.blocks.getOrNull(e.to)
                                val addr = target?.addrText ?: "#${e.to}"
                                when (e.kind) {
                                    "fail" -> "fail→$addr"
                                    "edge" -> "→$addr"
                                    else -> "jump→$addr"
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


/**
 * CFG 场景绘制（Composable 画布与 PNG 导出共用同一套绘制，保证导出与所见一致）。
 * viewportSize 为画布尺寸；导出时可传整图尺寸 + scale=1 + pan=Zero 得到全景。
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCfgScene(
    layout: CfgLayoutResult,
    colors: androidx.compose.material3.ColorScheme,
    density: Float,
    scale: Float,
    pan: Offset,
    viewportSize: Size,
    simpleView: Boolean,
    selected: Int,
) {
    val sc = scale
    val originX = viewportSize.width / 2f + pan.x
    val originY = viewportSize.height / 2f + pan.y
    fun px(v: Float) = v * sc + originX
    fun py(v: Float) = v * sc + originY

    // ── 背景细点阵网格（随缩放淡出；迭代次数有上限）──
    val step = 42f * density * sc
    if (step >= 10f && step <= max(viewportSize.width, viewportSize.height) * 2f) {
        val fadeIn = ((sc - 0.35f) / 1.65f).coerceIn(0f, 1f)
        val dotColor = colors.outlineVariant.copy(alpha = 0.10f + 0.28f * fadeIn)
        var gx = ((originX % step) + step) % step
        var guard = 0
        while (gx < viewportSize.width && guard < 400) {
            var gy = ((originY % step) + step) % step
            var guardY = 0
            while (gy < viewportSize.height && guardY < 400) {
                drawCircle(dotColor, radius = 0.9f, center = Offset(gx, gy))
                gy += step
                guardY++
            }
            gx += step
            guard++
        }
    }

    val scl = sc.coerceIn(0.5f, 2f)
    val strokeW = max(1f, 1.35f * density * scl)
    val arrowSize = max(4.5f, 7.5f * density * scl)
    val dash = PathEffect.dashPathEffect(floatArrayOf(9f * scl, 6f * scl), 0f)

    // ── 边（正交折线）──
    layout.routes.forEach { r ->
        if (r.points.size < 2) return@forEach
        val color = when {
            r.isBack -> backColor
            r.kind == "fail" -> failColor
            else -> jumpColor
        }
        val lineW = if (r.isBack) strokeW * 1.9f else strokeW
        val effect = if (r.kind == "fail" && !r.isBack) dash else null
        val screenPts = r.points.map { Offset(px(it.x), py(it.y)) }
        val path = roundedOrthoPath(screenPts, 9f * density * scl)
        drawPath(
            path,
            color,
            style = Stroke(
                width = lineW,
                pathEffect = effect,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        val tip = r.points[r.points.size - 1]
        val prev = r.points[r.points.size - 2]
        arrowHead(
            Offset(px(tip.x), py(tip.y)),
            Offset(px(prev.x), py(prev.y)),
            color,
            arrowSize * (if (r.isBack) 1.35f else 1f),
        )
    }

    // ── 节点（按角色分层：入口/返回/循环头/普通/选中）──
    val showText = !simpleView && sc >= TEXT_HIDE_SCALE
    val nodeStroke = max(1f, 1f * density * scl)
    val radius = CornerRadius(8f * density * scl)
    val barW = 3.5f * density * scl
    val paintAddr = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = (10.5f * density * sc).coerceIn(7f, 30f)
    }
    val paintSum = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = (9f * density * sc).coerceIn(6f, 26f)
    }
    layout.boxes.forEach { box ->
        if (box.isDummy) return@forEach
        val isSel = box.index == selected
        val roleColor = when {
            isSel -> colors.primary
            layout.loopHeadIndices.contains(box.index) -> loopColor
            box.index == layout.entryIndex -> entryColor
            layout.returnIndices.contains(box.index) -> returnColor
            else -> colors.outlineVariant
        }
        val hasRole = isSel || box.index == layout.entryIndex ||
            layout.returnIndices.contains(box.index) || layout.loopHeadIndices.contains(box.index)
        val topLeft = Offset(px(box.left), py(box.top))
        val rectSize = Size(box.w * sc, box.h * sc)
        val fill = when {
            isSel -> colors.primary.copy(alpha = 0.16f)
            !showText -> roleColor.copy(alpha = 0.16f)
            else -> colors.surfaceContainerHigh
        }
        drawRoundRect(color = fill, topLeft = topLeft, size = rectSize, cornerRadius = radius)
        if (hasRole) {
            val inset = 3f * density * sc
            val barH = (box.h * sc - inset * 4f).coerceAtLeast(2f)
            drawRoundRect(
                color = roleColor.copy(alpha = 0.95f),
                topLeft = Offset(topLeft.x + inset, topLeft.y + inset * 2f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
        drawRoundRect(
            color = roleColor,
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = radius,
            style = Stroke(width = if (isSel) nodeStroke * 2f else nodeStroke),
        )
        if (!showText) return@forEach
        val maxTextW = box.w * sc - 12f * density
        if (maxTextW <= 10f) return@forEach
        paintAddr.color = if (isSel) colors.primary.toArgb() else colors.onSurface.toArgb()
        paintSum.color = colors.onSurfaceVariant.toArgb()
        val addrText = fitText(paintAddr, box.addrText, maxTextW)
        val lines = box.lines.map { fitText(paintSum, it, maxTextW) }
        val gapY = 2.5f * density * sc
        val sumH = paintSum.textSize
        val totalH = paintAddr.textSize + (if (lines.isEmpty()) 0f else gapY * lines.size + lines.size * sumH)
        val baseline = py(box.cy) - totalH / 2f + paintAddr.textSize
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.drawText(addrText, px(box.cx) - paintAddr.measureText(addrText) / 2f, baseline, paintAddr)
            var y = baseline
            lines.forEach { ln ->
                y += gapY + sumH
                nc.drawText(ln, px(box.cx) - paintSum.measureText(ln) / 2f, y, paintSum)
            }
        }
    }
}
