// 塔菲逆核: 分析页 CFG 图形化画布（纯 Compose Canvas 自绘；默认 Sugiyama 简化版自研布局，另提供可选 Dagre 引擎）。
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
import com.soreverse.mcp.engine.ExbinDagre
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
private const val ASM_BLOCK_MAX_LINES = 6

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
internal fun layoutCfgGraph(graph: CfgGraph, density: Float, maxLines: Int = 2): CfgLayoutResult {
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
        var lines = wrapSummary(b.summary, paintSum, w - padX * 2f, maxLines)
        var maxLineW = addrW
        lines.forEach { maxLineW = max(maxLineW, paintSum.measureText(it)) }
        w = (maxLineW + padX * 2f).coerceIn(minW, maxW)
        lines = wrapSummary(b.summary, paintSum, w - padX * 2f, maxLines)
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

/**
 * Dagre 布局引擎（Exbin `DagreLayout`，dagre.js 的 1:1 Java 移植）。
 *
 * 与自研 [layoutCfgGraph] 平行：走标准 dagre 流水线（去环 → rank → normalize →
 * 交叉最小化 order → 坐标二次优化 position），长边由 dagre 内部拆虚节点处理。
 * 布局失败自动回退自研分层布局，绝不产出空图。
 */
internal fun layoutCfgGraphDagre(graph: CfgGraph, density: Float, maxLines: Int = 2): CfgLayoutResult {
    val n = graph.blocks.size
    if (n == 0 || density <= 0f) {
        return CfgLayoutResult(emptyList(), emptyList(), 0f, 0f, -1, emptySet(), emptySet())
    }

    val padX = PAD_X_DP * density
    val padY = PAD_Y_DP * density
    val addrLine = ADDR_LINE_DP * density
    val sumLine = SUM_LINE_DP * density
    val minW = NODE_W_MIN_DP * density
    val maxW = NODE_W_MAX_DP * density
    val minH = NODE_H_MIN_DP * density
    val nodesep = LANE_GAP_DP * density
    val ranksep = LAYER_GAP_DP * density

    val paintAddr = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textSize = 10.5f * density }
    val paintSum = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textSize = 9f * density }

    // ── 1. 节点尺寸（与自研布局一致的内容自适应）──
    val widths = FloatArray(n)
    val heights = FloatArray(n)
    val summaries = arrayOfNulls<List<String>>(n)
    for (i in 0 until n) {
        val b = graph.blocks[i]
        val addrW = paintAddr.measureText(b.addrText)
        val sumW = paintSum.measureText(b.summary)
        val desired = max(addrW, min(sumW, maxW - padX * 2f)) + padX * 2f
        var w = desired.coerceIn(minW, maxW)
        var lines = wrapSummary(b.summary, paintSum, w - padX * 2f, maxLines)
        var maxLineW = addrW
        lines.forEach { maxLineW = max(maxLineW, paintSum.measureText(it)) }
        w = (maxLineW + padX * 2f).coerceIn(minW, maxW)
        lines = wrapSummary(b.summary, paintSum, w - padX * 2f, maxLines)
        widths[i] = w
        heights[i] = max(padY * 2f + addrLine + lines.size * sumLine, minH)
        summaries[i] = lines
    }

    // ── 2. 交给 dagre ──
    val ids = ArrayList<String>(n)
    val sizes = HashMap<String, Pair<Float, Float>>(n * 2)
    for (i in 0 until n) {
        val id = i.toString()
        ids.add(id)
        sizes[id] = widths[i] to heights[i]
    }
    val edges = ArrayList<Pair<String, String>>(graph.edges.size)
    for (e in graph.edges) {
        if (e.from == e.to) continue
        if (e.from !in 0 until n || e.to !in 0 until n) continue
        edges.add(e.from.toString() to e.to.toString())
    }

    val laid = ExbinDagre.layout(ids, sizes, edges, nodesep.toDouble(), ranksep.toDouble(), "TB")
        ?: return layoutCfgGraph(graph, density, maxLines)
    val nodesMap = laid.first
    val routesMap = laid.second

    // ── 3. 求包围盒，平移到「图中心为原点」坐标系 ──
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until n) {
        val nd = nodesMap[i.toString()] ?: continue
        minX = min(minX, nd.x - widths[i] / 2f)
        maxX = max(maxX, nd.x + widths[i] / 2f)
        minY = min(minY, nd.y - heights[i] / 2f)
        maxY = max(maxY, nd.y + heights[i] / 2f)
    }
    if (minX > maxX) return layoutCfgGraph(graph, density, maxLines)
    val cx0 = (minX + maxX) / 2f
    val cy0 = (minY + maxY) / 2f

    val boxes = ArrayList<CfgNodeBox>(n)
    for (i in 0 until n) {
        val nd = nodesMap[i.toString()] ?: continue
        boxes.add(
            CfgNodeBox(
                i, nd.x - cx0, nd.y - cy0, widths[i], heights[i],
                graph.blocks[i].addrText, summaries[i] ?: emptyList(),
            ),
        )
    }

    // ── 4. 邻接 / 层号 / 边路由 ──
    val succ = Array(n) { ArrayList<Int>() }
    val pred = Array(n) { ArrayList<Int>() }
    graph.edges.forEach { e ->
        if (e.from == e.to) return@forEach
        if (e.from !in 0 until n || e.to !in 0 until n) return@forEach
        if (!succ[e.from].contains(e.to)) succ[e.from].add(e.to)
        if (!pred[e.to].contains(e.from)) pred[e.to].add(e.from)
    }
    val rankOf = IntArray(n) { nodesMap[it.toString()]?.rank ?: 0 }
    val backChannel = BACK_CHANNEL_DP * density

    val routes = ArrayList<CfgRoute>(graph.edges.size)
    for (e in graph.edges) {
        if (e.from !in 0 until n || e.to !in 0 until n) continue
        val a = boxes.getOrNull(e.from) ?: continue
        val b = boxes.getOrNull(e.to) ?: continue
        val self = e.from == e.to
        val isBack = !self && rankOf[e.to] <= rankOf[e.from]
        val pts: List<Offset> = when {
            self -> listOf(
                Offset(a.right, a.top + a.h * 0.25f),
                Offset(a.right + backChannel, a.top + a.h * 0.25f),
                Offset(a.right + backChannel, a.top - a.h * 0.15f),
                Offset(a.cx, a.top - a.h * 0.15f),
                Offset(a.cx, a.top),
            )
            else -> {
                val raw = routesMap[e.from.toString() + "|" + e.to.toString()]
                if (raw != null && raw.size >= 2) {
                    raw.map { Offset(it.first - cx0, it.second - cy0) }
                } else if (isBack) {
                    val chanX = max(a.right, b.right) + backChannel
                    listOf(
                        Offset(a.right, a.cy), Offset(chanX, a.cy),
                        Offset(chanX, b.cy), Offset(b.right, b.cy),
                    )
                } else {
                    listOf(Offset(a.cx, a.bottom), Offset(b.cx, b.top))
                }
            }
        }
        routes.add(CfgRoute(e.from, e.to, e.kind, isBack, self, pts))
    }

    val entryIndex = (0 until n).filter { pred[it].isEmpty() }.minByOrNull { rankOf[it] } ?: -1
    val loopHeads = HashSet<Int>()
    val returns = HashSet<Int>()
    for (i in 0 until n) {
        if (succ[i].isEmpty()) returns.add(i)
    }
    graph.edges.forEach { e ->
        if (e.from in 0 until n && e.to in 0 until n && e.from != e.to && rankOf[e.to] <= rankOf[e.from]) {
            loopHeads.add(e.to)
        }
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
private fun CfgChip(text: String, tint: Color, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppShape.xs),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else tint,
            maxLines = 1,
        )
    }
}

// ───────────────────────── 背景 / 边路径 / 拖动 / 小地图 ─────────────────────────

/** 解析 rizin pdfj（函数反汇编 JSON）→ 块入口地址 → 指令行列表。 */
internal fun parsePdfjInsns(jsonText: String): Map<Long, List<String>> {
    if (jsonText.isBlank()) return emptyMap()
    return try {
        val o = JSONObject(jsonText)
        val blocks = o.optJSONArray("blocks") ?: return emptyMap()
        val m = HashMap<Long, List<String>>()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val addr = parseCfgAddr(b.opt("addr")?.toString() ?: "")
            if (addr < 0L) continue
            val ops = b.optJSONArray("ops") ?: continue
            val lines = ArrayList<String>(ops.length())
            for (j in 0 until ops.length()) {
                val op = ops.optJSONObject(j) ?: continue
                val t = firstNonBlankText(op, "disasm", "opcode", "text", "code")
                if (t.isNotBlank()) lines.add(t.lineSequence().firstOrNull().orEmpty().trim())
            }
            if (lines.isNotEmpty()) m[addr] = lines
        }
        m
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 解析 java 引擎伪 C：按 `label_<hex>:` 切分 → 块入口地址 → 伪C 行列表。 */
internal fun parsePseudoBlocks(text: String): Map<Long, List<String>> {
    if (text.isBlank()) return emptyMap()
    val m = LinkedHashMap<Long, MutableList<String>>()
    val re = Regex("^label_([0-9a-fA-F]+):?$")
    var cur = -1L
    text.lineSequence().forEach { raw ->
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("//")) return@forEach
        val lm = re.find(t)
        if (lm != null) {
            val a = lm.groupValues[1].toLongOrNull(16)
            if (a != null) {
                cur = a
                m.getOrPut(a) { ArrayList() }
            }
        } else if (cur >= 0L) {
            m[cur]?.add(t)
        }
    }
    return m
}

private fun nextBgStyle(cur: String): String = when (cur) {
    "grid" -> "cobweb"
    "cobweb" -> "honeycomb"
    "honeycomb" -> "radar"
    "radar" -> "none"
    else -> "grid"
}

private fun nextRouting(cur: String): String = when (cur) {
    "ortho" -> "polyline"
    "polyline" -> "spline"
    else -> "ortho"
}

private fun bgStyleLabel(zh: Boolean, s: String): String = when (s) {
    "none" -> if (zh) "背景:无" else "BG:None"
    "cobweb" -> if (zh) "背景:蛛网" else "BG:Web"
    "honeycomb" -> if (zh) "背景:蜂窝" else "BG:Honeycomb"
    "radar" -> if (zh) "背景:雷达" else "BG:Radar"
    else -> if (zh) "背景:网格" else "BG:Grid"
}

private fun routeStyleLabel(zh: Boolean, s: String): String = when (s) {
    "polyline" -> if (zh) "边:折线" else "Edge:Poly"
    "spline" -> if (zh) "边:样条" else "Edge:Spline"
    else -> if (zh) "边:正交" else "Edge:Ortho"
}

/** 直线折线路径（第二种边路由风格）。 */
private fun polylineCfgPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
    return path
}

/** Catmull-Rom → 三次贝塞尔：平滑通过所有控制点（第三种边路由风格）。 */
private fun splineCfgPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    if (pts.size == 2) {
        path.lineTo(pts[1].x, pts[1].y)
        return path
    }
    for (i in 0 until pts.size - 1) {
        val p0 = if (i == 0) pts[0] else pts[i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = if (i + 2 < pts.size) pts[i + 2] else pts[pts.size - 1]
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    return path
}

/** 应用手动拖动偏移生成有效布局（不改动原始布局；边端点随节点平移）。 */
internal fun applyCfgDrag(layout: CfgLayoutResult, offs: Map<Int, Offset>): CfgLayoutResult {
    if (offs.isEmpty()) return layout
    val boxes = ArrayList<CfgNodeBox>(layout.boxes.size)
    layout.boxes.forEach { b ->
        val o = if (b.isDummy) null else offs[b.index]
        boxes += if (o == null) b else CfgNodeBox(b.index, b.cx + o.x, b.cy + o.y, b.w, b.h, b.addrText, b.lines)
    }
    val routes = ArrayList<CfgRoute>(layout.routes.size)
    layout.routes.forEach { r ->
        val of = offs[r.from]
        val ot = offs[r.to]
        if (of == null && ot == null) {
            routes += r
        } else {
            val pts = r.points.toMutableList()
            if (pts.isNotEmpty()) {
                if (of != null) pts[0] = Offset(pts[0].x + of.x, pts[0].y + of.y)
                val li = pts.size - 1
                if (ot != null && li > 0) pts[li] = Offset(pts[li].x + ot.x, pts[li].y + ot.y)
            }
            routes += CfgRoute(r.from, r.to, r.kind, r.isBack, r.isSelf, pts)
        }
    }
    return CfgLayoutResult(
        boxes, routes, layout.width, layout.height,
        layout.entryIndex, layout.loopHeadIndices, layout.returnIndices,
    )
}

/** 背景图案绘制（网格 / 蛛网 / 蜂窝 / 雷达 / 无）。 */
private fun DrawScope.drawCfgBackground(
    bgStyle: String,
    colors: androidx.compose.material3.ColorScheme,
    density: Float,
    sc: Float,
    viewportSize: Size,
    originX: Float,
    originY: Float,
) {
    if (bgStyle == "none") return
    val soft = colors.outlineVariant.copy(alpha = 0.32f)
    when (bgStyle) {
        "cobweb" -> {
            val c = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            val maxR = sqrt(c.x * c.x + c.y * c.y) + 40f
            val ring = 46f * density * sc
            if (ring >= 12f) {
                var r = ring
                var rg = 0
                while (r < maxR && rg < 200) {
                    drawCircle(soft, radius = r, center = c, style = Stroke(1f))
                    r += ring
                    rg++
                }
            }
            var a = 0f
            var ag = 0
            while (a < 360f && ag < 24) {
                val rad = a * PI.toFloat() / 180f
                drawLine(soft, c, Offset(c.x + cos(rad) * maxR, c.y + sin(rad) * maxR), strokeWidth = 1f)
                a += 30f
                ag++
            }
        }
        "honeycomb" -> {
            val rr = 30f * density * sc
            if (rr >= 10f) {
                val dx = 1.5f * rr
                val dy = sqrt(3f) * rr
                var col = 0
                var x = -dx
                var cg = 0
                while (x < viewportSize.width + dx && cg < 120) {
                    var y = if (col % 2 == 0) 0f else dy / 2f
                    var yg = 0
                    while (y < viewportSize.height + dy && yg < 160) {
                        drawCfgHexagon(Offset(x, y), rr, soft)
                        y += dy
                        yg++
                    }
                    x += dx
                    col++
                    cg++
                }
            }
        }
        "radar" -> {
            val c = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            val maxR = sqrt(c.x * c.x + c.y * c.y) + 40f
            val ring = 54f * density * sc
            if (ring >= 14f) {
                var r = ring
                var rg = 0
                while (r < maxR && rg < 120) {
                    drawCircle(soft, radius = r, center = c, style = Stroke(1f))
                    r += ring
                    rg++
                }
            }
            drawLine(soft, Offset(0f, c.y), Offset(viewportSize.width, c.y), strokeWidth = 1f)
            drawLine(soft, Offset(c.x, 0f), Offset(c.x, viewportSize.height), strokeWidth = 1f)
        }
        else -> {
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
        }
    }
}

private fun DrawScope.drawCfgHexagon(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 6) {
        val rad = (60f * i - 30f) * PI.toFloat() / 180f
        val x = c.x + cos(rad) * r
        val y = c.y + sin(rad) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = Stroke(1f))
}

/** 右下角迷你导航图：全图缩略 + 当前视口框（对标 Exbin CfgMinimap）。 */
private fun DrawScope.drawCfgMinimap(
    layout: CfgLayoutResult,
    colors: androidx.compose.material3.ColorScheme,
    size: Size,
    viewport: IntSize,
    scale: Float,
    pan: Offset,
) {
    val gw = layout.width.coerceAtLeast(1f)
    val gh = layout.height.coerceAtLeast(1f)
    val s = min((size.width - 6f) / gw, (size.height - 6f) / gh)
    if (s <= 0f) return
    val cx = size.width / 2f
    val cy = size.height / 2f
    val nodeColor = colors.primary.copy(alpha = 0.55f)
    layout.boxes.forEach { b ->
        if (b.isDummy) return@forEach
        drawRoundRect(
            nodeColor,
            topLeft = Offset(cx + b.left * s, cy + b.top * s),
            size = Size((b.w * s).coerceAtLeast(1.5f), (b.h * s).coerceAtLeast(1.5f)),
            cornerRadius = CornerRadius(1f),
        )
    }
    if (viewport.width > 0 && viewport.height > 0 && scale > 0f) {
        val x0 = (0f - (viewport.width / 2f + pan.x)) / scale
        val y0 = (0f - (viewport.height / 2f + pan.y)) / scale
        val x1 = (viewport.width - (viewport.width / 2f + pan.x)) / scale
        val y1 = (viewport.height - (viewport.height / 2f + pan.y)) / scale
        val l = cx + min(x0, x1) * s
        val t = cy + min(y0, y1) * s
        val r = cx + max(x0, x1) * s
        val b = cy + max(y0, y1) * s
        drawRect(
            colors.primary.copy(alpha = 0.95f),
            topLeft = Offset(l, t),
            size = Size((r - l).coerceAtLeast(1f), (b - t).coerceAtLeast(1f)),
            style = Stroke(1.2f),
        )
    }
}

// ───────────────────────── 组合视图 ─────────────────────────

/**
 * CFG 图形画布。json 为 rzCfg 原始 JSON；空/非法 JSON 时显示「无 CFG 数据」而不是崩溃。
 */
@Composable
internal fun CfgCanvas(
    json: String,
    zh: Boolean,
    modifier: Modifier = Modifier,
    layoutMode: String = "layered",
    contentMode: String = "summary",
    blockLines: Map<Long, List<String>> = emptyMap(),
) {
    val density = LocalDensity.current.density
    val baseGraph = remember(json) { parseCfgGraph(json) }
    // 块内容模式：summary=首行摘要；asm=块内显示该块完整指令（对标 Exbin BLOCK_CONTENT_ASM）。
    val asmBlocks = contentMode != "summary" && blockLines.isNotEmpty()
    val maxLines = if (asmBlocks) ASM_BLOCK_MAX_LINES else 2
    val graph = remember(baseGraph, asmBlocks, blockLines) {
        if (!asmBlocks) baseGraph else baseGraph.copy(
            blocks = baseGraph.blocks.map { b ->
                val ins = blockLines[b.addrValue]
                if (ins.isNullOrEmpty()) b else b.copy(summary = ins.joinToString("\n"))
            },
        )
    }
    val layout = remember(graph, density, layoutMode, maxLines) {
        when (layoutMode) {
            "grid" -> layoutCfgGrid(graph, density, maxLines)
            "force" -> layoutCfgForce(graph, density, maxLines)
            "dagre" -> layoutCfgGraphDagre(graph, density, maxLines)
            else -> layoutCfgGraph(graph, density, maxLines)
        }
    }
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
    // 背景形状（对标 Exbin BG_NONE/GRID/COBWEB/HONEYCOMB/RADAR）。
    var bgStyle by remember { mutableStateOf("grid") }
    // 边路由风格（对标 Exbin「三种边路由风格」：正交 / 折线 / 样条）。
    var routing by remember { mutableStateOf("ortho") }
    // 单指拖动调整节点位置（对标 Exbin KEY_FLOWCHART_DRAG）。
    var dragMode by remember { mutableStateOf(false) }
    var dragOffsets by remember(graph) { mutableStateOf<Map<Int, Offset>>(emptyMap()) }
    // 右下角迷你导航图（对标 Exbin CfgMinimap「显示小地图」）。
    var showMinimap by remember { mutableStateOf(true) }

    val ctx = LocalContext.current
    val densityObj = LocalDensity.current
    // 应用手动拖动后的有效布局（绘制 / 命中 / 导出共用）。
    val effective = remember(layout, dragOffsets) { applyCfgDrag(layout, dragOffsets) }
    val scaleS = rememberUpdatedState(scale)
    val panS = rememberUpdatedState(pan)
    val viewportS = rememberUpdatedState(viewport)
    val dragModeS = rememberUpdatedState(dragMode)
    val effectiveS = rememberUpdatedState(effective)

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
                .pointerInput(layout, dragMode) {
                    if (!dragMode) return@pointerInput
                    var curIdx = -1
                    detectDragGestures(
                        onDragStart = { pos ->
                            val sc = scaleS.value
                            if (sc > 0f) {
                                val originX = viewportS.value.width / 2f + panS.value.x
                                val originY = viewportS.value.height / 2f + panS.value.y
                                val wx = (pos.x - originX) / sc
                                val wy = (pos.y - originY) / sc
                                curIdx = effectiveS.value.boxes.lastOrNull { it.contains(wx, wy) }?.index ?: -1
                                if (curIdx >= 0) selected = curIdx
                            }
                        },
                        onDragEnd = { curIdx = -1 },
                        onDragCancel = { curIdx = -1 },
                        onDrag = { change, drag ->
                            val sc = scaleS.value
                            if (curIdx >= 0 && sc > 0f) {
                                val prev = dragOffsets[curIdx] ?: Offset.Zero
                                dragOffsets = dragOffsets + (curIdx to Offset(prev.x + drag.x / sc, prev.y + drag.y / sc))
                            }
                            change.consume()
                        },
                    )
                }
                .pointerInput(effective) {
                    detectTapGestures(
                        onDoubleTap = { applyFit() },
                    ) { pos ->
                        val originX = viewport.width / 2f + pan.x
                        val originY = viewport.height / 2f + pan.y
                        val wx = (pos.x - originX) / scale
                        val wy = (pos.y - originY) / scale
                        selected = effective.boxes.lastOrNull { it.contains(wx, wy) }?.index ?: -1
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.12f, 6f)
                        if (!dragModeS.value) pan += panChange
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCfgScene(effective, colors, density, scale, pan, size, simpleView, selected, bgStyle, routing)
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

            // 右下角：迷你导航图（对标 Exbin CfgMinimap「显示小地图」）
            if (showMinimap && effective.boxes.any { !it.isDummy }) {
                Surface(
                    shape = RoundedCornerShape(AppShape.xs),
                    color = colors.surfaceContainerHigh.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(118.dp, 84.dp)
                        .clip(RoundedCornerShape(AppShape.xs))
                        .pointerInput(effective, scale, pan, viewport) {
                            detectTapGestures { pos ->
                                val sz = size
                                val gw = effectiveS.value.width.coerceAtLeast(1f)
                                val gh = effectiveS.value.height.coerceAtLeast(1f)
                                val s = min((sz.width - 6f) / gw, (sz.height - 6f) / gh)
                                if (s > 0f) {
                                    val wx = (pos.x - sz.width / 2f) / s
                                    val wy = (pos.y - sz.height / 2f) / s
                                    pan = Offset(-wx * scaleS.value, -wy * scaleS.value)
                                }
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCfgMinimap(effective, colors, size, viewport, scale, pan)
                    }
                }
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
                    CfgChip(bgStyleLabel(zh, bgStyle), colors.onSurfaceVariant) { bgStyle = nextBgStyle(bgStyle) }
                    CfgChip(routeStyleLabel(zh, routing), colors.onSurfaceVariant) { routing = nextRouting(routing) }
                    CfgChip(
                        if (zh) "拖动节点" else "Drag",
                        colors.onSurfaceVariant,
                        active = dragMode,
                    ) { dragMode = !dragMode }
                    CfgChip(
                        if (zh) "小地图" else "Minimap",
                        colors.onSurfaceVariant,
                        active = showMinimap,
                    ) { showMinimap = !showMinimap }
                    if (dragOffsets.isNotEmpty()) {
                        CfgChip(if (zh) "复位" else "Reset", failColor) { dragOffsets = emptyMap() }
                    }
                    CfgChip(if (zh) "导出 PNG" else "PNG", colors.primary) {
                        val w = (layout.width + 80f).coerceAtLeast(320f)
                        val h = (layout.height + 80f).coerceAtLeast(240f)
                        val p = exportDrawToPng(
                            context = ctx,
                            fileName = "cfg_" + System.currentTimeMillis() + ".png",
                            widthPx = w.toInt(),
                            heightPx = h.toInt(),
                            density = densityObj,
                        ) {
                            drawCfgScene(effective, colors, density, 1f, Offset.Zero, Size(w, h), false, -1, bgStyle, routing)
                        }
                        Toast.makeText(
                            ctx,
                            if (p != null) (if (zh) "已导出：$p" else "saved: $p") else (if (zh) "导出失败" else "export failed"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
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
    bgStyle: String = "grid",
    routing: String = "ortho",
) {
    val jumpColor = colors.primary
    val failColor = AppPalette.orange
    val backColor = AppPalette.pink
    val entryColor = AppPalette.green
    val loopColor = AppPalette.purple
    val returnColor = AppPalette.teal
    val sc = scale
    val originX = viewportSize.width / 2f + pan.x
    val originY = viewportSize.height / 2f + pan.y
    fun px(v: Float) = v * sc + originX
    fun py(v: Float) = v * sc + originY

    // ── 背景图案（对标 Exbin BG_NONE/GRID/COBWEB/HONEYCOMB/RADAR）──
    drawCfgBackground(bgStyle, colors, density, sc, viewportSize, originX, originY)

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
        val path = when (routing) {
            "polyline" -> polylineCfgPath(screenPts)
            "spline" -> splineCfgPath(screenPts)
            else -> roundedOrthoPath(screenPts, 9f * density * scl)
        }
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

/**
 * CFG 紧凑网格布局（第二套布局引擎，对标 Exbin 的多布局引擎可切换）。
 * 忽略分层，按块序排成近似方形网格，边用直连——大图快速浏览时比分层更快、更紧凑。
 */
internal fun layoutCfgGrid(graph: CfgGraph, density: Float, maxLines: Int = 2): CfgLayoutResult {
    val n = graph.blocks.size
    if (n == 0 || density <= 0f) {
        return CfgLayoutResult(emptyList(), emptyList(), 0f, 0f, -1, emptySet(), emptySet())
    }
    val padX = PAD_X_DP * density
    val padY = PAD_Y_DP * density
    val minW = NODE_W_MIN_DP * density
    val maxW = NODE_W_MAX_DP * density
    val minH = NODE_H_MIN_DP * density
    val paintAddr = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textSize = 10.5f * density }

    val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt().coerceIn(1, 12)
    val rows = (n + cols - 1) / cols
    val cellW = maxW * 1.12f + 24f * density
    val cellH = max(74f * density, (PAD_Y_DP * 2f + ADDR_LINE_DP + maxLines * SUM_LINE_DP) * density + 22f * density)

    val boxes = ArrayList<CfgNodeBox>(n)
    val centers = HashMap<Int, Offset>()
    for (i in 0 until n) {
        val b = graph.blocks[i]
        val r = i / cols
        val c = i % cols
        val cx = 40f * density + c * cellW + cellW / 2f
        val cy = 40f * density + r * cellH + cellH / 2f
        val w = max(minW, paintAddr.measureText(b.addrText) + padX * 2f)
        val h = max(minH, (PAD_Y_DP * 2f + ADDR_LINE_DP + maxLines * SUM_LINE_DP) * density)
        centers[i] = Offset(cx, cy)
        boxes.add(CfgNodeBox(i, cx, cy, w, h, b.addrText, listOf(b.summary).filter { it.isNotBlank() }))
    }

    val routes = ArrayList<CfgRoute>(graph.edges.size)
    graph.edges.forEach { e ->
        if (e.from == e.to) return@forEach
        val a = centers[e.from] ?: return@forEach
        val b = centers[e.to] ?: return@forEach
        val isBack = e.to < e.from
        routes.add(
            CfgRoute(
                e.from, e.to, e.kind, isBack, e.from == e.to,
                listOf(
                    Offset(a.x, a.y + cellH * 0.34f),
                    Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f),
                    Offset(b.x, b.y - cellH * 0.34f),
                ),
            ),
        )
    }

    val entry = 0
    return CfgLayoutResult(
        boxes = boxes,
        routes = routes,
        width = cols * cellW + 80f * density,
        height = rows * cellH + 80f * density,
        entryIndex = entry,
        loopHeadIndices = emptySet(),
        returnIndices = emptySet(),
    )
}

/**
 * CFG 力导向布局（第三套布局引擎，Fruchterman-Reingold 简化版）。
 * 把块视为带电荷的粒子：所有节点互相排斥、有边的节点互相吸引，逐轮降温收敛。
 * 适合观察「谁和谁抱团」；节点数过大（>200）时自动降级为网格布局以保证性能。
 */
internal fun layoutCfgForce(graph: CfgGraph, density: Float, maxLines: Int = 2): CfgLayoutResult {
    val n = graph.blocks.size
    if (n == 0 || density <= 0f) {
        return CfgLayoutResult(emptyList(), emptyList(), 0f, 0f, -1, emptySet(), emptySet())
    }
    if (n > 200) return layoutCfgGrid(graph, density)

    val padX = PAD_X_DP * density
    val minW = NODE_W_MIN_DP * density
    val minH = NODE_H_MIN_DP * density
    val paintAddr = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textSize = 10.5f * density }

    val w = FloatArray(n) { max(minW, paintAddr.measureText(graph.blocks[it].addrText) + padX * 2f) }
    val h = FloatArray(n) { max(minH, (PAD_Y_DP * 2f + ADDR_LINE_DP + maxLines * SUM_LINE_DP) * density) }

    // 理想边长
    val k = max(120f * density, kotlin.math.sqrt((n.toFloat()) * 9000f * density))
    // 初始：圆形均匀分布（比随机更稳定，迭代更快收敛）
    val rad = k * kotlin.math.sqrt(n.toFloat()) * 0.5f
    val px = FloatArray(n)
    val py = FloatArray(n)
    for (i in 0 until n) {
        val ang = 2.0 * Math.PI * i / n
        px[i] = (rad * kotlin.math.cos(ang)).toFloat()
        py[i] = (rad * kotlin.math.sin(ang)).toFloat()
    }

    val edges = graph.edges.filter { it.from != it.to && it.from in 0 until n && it.to in 0 until n }

    val iterations = if (n <= 60) 90 else 50
    for (it in 0 until iterations) {
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        // 斥力
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var ddx = px[i] - px[j]
                var ddy = py[i] - py[j]
                var d2 = ddx * ddx + ddy * ddy
                if (d2 < 0.01f) { ddx = 0.1f * (i - j); ddy = 0.1f; d2 = 0.02f }
                val d = kotlin.math.sqrt(d2)
                val f = k * k / d
                val ux = ddx / d
                val uy = ddy / d
                dx[i] += ux * f; dy[i] += uy * f
                dx[j] -= ux * f; dy[j] -= uy * f
            }
        }
        // 引力
        edges.forEach { e ->
            var ddx = px[e.from] - px[e.to]
            var ddy = py[e.from] - py[e.to]
            val d = kotlin.math.sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(0.01f)
            val f = d * d / k
            val ux = ddx / d
            val uy = ddy / d
            dx[e.from] -= ux * f; dy[e.from] -= uy * f
            dx[e.to] += ux * f; dy[e.to] += uy * f
        }
        // 位移限制（温度）
        val temp = k * (1f - it / iterations.toFloat()) * 0.35f + 0.5f
        for (i in 0 until n) {
            val dl = kotlin.math.sqrt(dx[i] * dx[i] + dy[i] * dy[i]).coerceAtLeast(0.001f)
            val step = minOf(dl, temp) / dl
            px[i] += dx[i] * step
            py[i] += dy[i] * step
        }
    }

    // 归一化到正坐标
    val minX = (0 until n).minOf { px[it] - w[it] / 2f }
    val minY = (0 until n).minOf { py[it] - h[it] / 2f }
    val offX = 40f * density - minX
    val offY = 40f * density - minY

    val boxes = ArrayList<CfgNodeBox>(n)
    val centers = HashMap<Int, Offset>()
    for (i in 0 until n) {
        val cx = px[i] + offX
        val cy = py[i] + offY
        centers[i] = Offset(cx, cy)
        boxes.add(CfgNodeBox(i, cx, cy, w[i], h[i], graph.blocks[i].addrText, listOf(graph.blocks[i].summary).filter { it.isNotBlank() }))
    }

    val routes = ArrayList<CfgRoute>(edges.size)
    edges.forEach { e ->
        val a = centers[e.from] ?: return@forEach
        val b = centers[e.to] ?: return@forEach
        routes.add(
            CfgRoute(
                e.from, e.to, e.kind, e.to < e.from, false,
                listOf(
                    Offset(a.x, a.y),
                    Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f),
                    Offset(b.x, b.y),
                ),
            ),
        )
    }

    val maxX = boxes.maxOfOrNull { it.right } ?: 0f
    val maxY = boxes.maxOfOrNull { it.bottom } ?: 0f
    return CfgLayoutResult(
        boxes = boxes,
        routes = routes,
        width = maxX + 40f * density,
        height = maxY + 40f * density,
        entryIndex = 0,
        loopHeadIndices = emptySet(),
        returnIndices = emptySet(),
    )
}
