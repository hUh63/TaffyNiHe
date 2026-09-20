// 塔菲逆核: 分析页 CFG 图形化画布（纯 Compose Canvas 自绘，无 ELK/dagre 依赖）。
//
// 设计要点（对标 Exbin 自绘 CFG）：
//   1. 解析 rizin rzCfg 的 JSON（basicBlocks/blocks + edges/jump/fail，地址均为 hex 字符串）。
//   2. 自实现 Sugiyama 式分层布局：建图 → BFS 最短距离分层 → DFS 前序定序 → barycenter 降交叉
//      → 层间距 90dp / 同层间距 140dp 计算坐标 → 整图居中（世界坐标原点 = 图中心）。
//   3. Canvas 自绘：圆角矩形节点 + 折线箭头（jump 实线 / fail 虚线 / 回边醒目色）。
//   4. 交互：双指缩放 + 单指平移、点击节点高亮并在下方信息条展示块详情、「适应屏幕」复位。
//   5. 空图 / 单块 / 孤立块 / 环 均不崩、不除零。
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
import androidx.compose.foundation.layout.Row
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

private const val NODE_W_DP = 124f
private const val NODE_H_DP = 48f
private const val LAYER_GAP_DP = 90f
private const val LANE_GAP_DP = 140f
private const val BACK_CHANNEL_DP = 30f
private const val MAX_SUMMARY_CHARS = 48

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

/** 布局后的节点矩形（世界坐标，单位 px；原点 = 图中心）。 */
internal class CfgNodeBox(
    val index: Int,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
) {
    val left: Float get() = cx - w / 2f
    val right: Float get() = cx + w / 2f
    val top: Float get() = cy - h / 2f
    val bottom: Float get() = cy + h / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

internal class CfgLayoutResult(
    val boxes: List<CfgNodeBox>,
    val width: Float,
    val height: Float,
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

/** 解析 "0x1234" / "1234" / "1234" 形式的地址；失败返回 -1。 */
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

// ───────────────────────── 分层布局（Sugiyama 简化版） ─────────────────────────

/** 按 DFS 前序 + barycenter（2 轮）给同层节点定序，减少交叉。 */
private fun reorderLayer(
    layer: MutableList<Int>,
    reference: List<Int>,
    neighbors: Array<out List<Int>>,
    fallbackOrder: IntArray,
) {
    if (layer.size <= 1) return
    val refIndex = HashMap<Int, Int>(reference.size)
    reference.forEachIndexed { i, node -> refIndex[node] = i }
    val bary = HashMap<Int, Float>(layer.size)
    layer.forEach { node ->
        val positions = neighbors[node].mapNotNull { refIndex[it] }
        bary[node] = if (positions.isEmpty()) -1f else positions.sum().toFloat() / positions.size
    }
    layer.sortWith(
        compareBy(
            { node ->
                val b = bary[node] ?: -1f
                if (b < 0f) Float.MAX_VALUE else b
            },
            { node -> fallbackOrder[node] },
        ),
    )
}

/** BFS 分层 + 层内定序 + 坐标计算；空图返回空布局。 */
internal fun layoutCfgGraph(graph: CfgGraph, density: Float): CfgLayoutResult {
    val n = graph.blocks.size
    if (n == 0 || density <= 0f) return CfgLayoutResult(emptyList(), 0f, 0f)
    val succ = Array(n) { mutableListOf<Int>() }
    val pred = Array(n) { mutableListOf<Int>() }
    graph.edges.forEach { e ->
        if (e.from == e.to) return@forEach
        if (e.from !in 0 until n || e.to !in 0 until n) return@forEach
        if (!succ[e.from].contains(e.to)) succ[e.from] += e.to
        if (!pred[e.to].contains(e.from)) pred[e.to] += e.from
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
    // 孤立块 / 不可达块：主图下方单独成行（每行 <=4 个），避免与主图重叠。
    var orphan = 0
    for (i in 0 until n) {
        if (layer[i] < 0) {
            layer[i] = maxLayer + 1 + orphan / 4
            orphan++
        }
    }
    maxLayer = layer.maxOrNull() ?: maxLayer
    if (maxLayer < 0) maxLayer = 0
    // DFS 前序作为层内初始顺序（环用 visited 短路，不会死循环）。
    val order = IntArray(n)
    var ord = 0
    val visited = BooleanArray(n)
    val stack = ArrayDeque<Int>()
    val seeds = ArrayList<Int>(n + starts.size)
    seeds += starts
    for (i in 0 until n) seeds += i
    seeds.forEach { s ->
        if (visited[s]) return@forEach
        stack.addLast(s)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (visited[c]) continue
            visited[c] = true
            order[c] = ord++
            val next = succ[c].filter { !visited[it] }
            for (k in next.indices.reversed()) stack.addLast(next[k])
        }
    }
    val layers = ArrayList<MutableList<Int>>(maxLayer + 1)
    for (l in 0..maxLayer) layers.add(mutableListOf())
    for (i in 0 until n) layers[layer[i].coerceIn(0, maxLayer)] += i
    layers.forEach { l -> l.sortBy { order[it] } }
    repeat(2) {
        for (li in 1 until layers.size) reorderLayer(layers[li], layers[li - 1], pred, order)
        for (li in layers.size - 2 downTo 0) reorderLayer(layers[li], layers[li + 1], succ, order)
    }
    val nodeW = NODE_W_DP * density
    val nodeH = NODE_H_DP * density
    val laneGap = LANE_GAP_DP * density
    val layerGap = LAYER_GAP_DP * density
    val cx = FloatArray(n)
    val cy = FloatArray(n)
    layers.forEachIndexed { li, l ->
        if (l.isEmpty()) return@forEachIndexed
        val total = (l.size - 1).coerceAtLeast(0) * laneGap
        l.forEachIndexed { i, node ->
            cx[node] = i * laneGap - total / 2f
            cy[node] = li * layerGap
        }
    }
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until n) {
        minX = min(minX, cx[i] - nodeW / 2f)
        maxX = max(maxX, cx[i] + nodeW / 2f)
        minY = min(minY, cy[i] - nodeH / 2f)
        maxY = max(maxY, cy[i] + nodeH / 2f)
    }
    if (minX > maxX || minY > maxY) return CfgLayoutResult(emptyList(), 0f, 0f)
    val offX = (minX + maxX) / 2f
    val offY = (minY + maxY) / 2f
    val boxes = (0 until n).map { i ->
        CfgNodeBox(i, cx[i] - offX, cy[i] - offY, nodeW, nodeH)
    }
    return CfgLayoutResult(boxes, maxX - minX, maxY - minY)
}

// ───────────────────────── Canvas 绘制 ─────────────────────────

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

    val colors = MaterialTheme.colorScheme
    val jumpColor = colors.primary
    val failColor = AppPalette.orange
    val backColor = AppPalette.pink
    val selColor = colors.primary

    fun applyFit() {
        if (viewport.width <= 0 || viewport.height <= 0) return
        if (layout.boxes.isEmpty()) {
            scale = 1f
            pan = Offset.Zero
            return
        }
        val w = layout.width.coerceAtLeast(1f) + 72f
        val h = layout.height.coerceAtLeast(1f) + 72f
        scale = min(viewport.width / w, viewport.height / h).coerceIn(0.15f, 2.5f)
        pan = Offset.Zero
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
                        scale = (scale * zoom).coerceIn(0.15f, 6f)
                        pan += panChange
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sc = scale
                val originX = size.width / 2f + pan.x
                val originY = size.height / 2f + pan.y
                fun px(v: Float) = v * sc + originX
                fun py(v: Float) = v * sc + originY

                // ── 背景点阵网格（步长随缩放变化，迭代次数有上限） ──
                val step = 48f * density * sc
                if (step in 8f..size.width.coerceAtLeast(64f)) {
                    val dotColor = colors.outlineVariant.copy(alpha = 0.35f)
                    var gx = ((originX % step) + step) % step
                    var guard = 0
                    while (gx < size.width && guard < 400) {
                        var gy = ((originY % step) + step) % step
                        var guardY = 0
                        while (gy < size.height && guardY < 400) {
                            drawCircle(dotColor, radius = 1f, center = Offset(gx, gy))
                            gy += step
                            guardY++
                        }
                        gx += step
                        guard++
                    }
                }

                // ── 边 ──
                val strokeW = max(1f, 1.4f * density * sc.coerceIn(0.5f, 2f))
                val dash = PathEffect.dashPathEffect(
                    floatArrayOf(9f * sc.coerceIn(0.5f, 2f), 6f * sc.coerceIn(0.5f, 2f)),
                    0f,
                )
                val arrowSize = max(5f, 8f * density * sc.coerceIn(0.4f, 2f))
                graph.edges.forEach { e ->
                    val a = layout.boxes.getOrNull(e.from) ?: return@forEach
                    val b = layout.boxes.getOrNull(e.to) ?: return@forEach
                    val isBack = b.cy <= a.cy + 0.5f
                    val color = when {
                        isBack -> backColor
                        e.kind == "fail" -> failColor
                        else -> jumpColor
                    }
                    val effect = if (e.kind == "fail" && !isBack) dash else null
                    if (e.from == e.to) {
                        // 自环：节点上方画一个矩形环绕。
                        val lift = 18f * density * sc.coerceIn(0.5f, 1.6f)
                        val p1 = Offset(px(a.right), py(a.top))
                        val p2 = Offset(px(a.right) + lift, py(a.top) - lift)
                        val p3 = Offset(px(a.left) - lift, py(a.top) - lift)
                        val p4 = Offset(px(a.left), py(a.top))
                        val loop = Path()
                        loop.moveTo(p1.x, p1.y)
                        loop.lineTo(p2.x, p2.y)
                        loop.lineTo(p3.x, p3.y)
                        loop.lineTo(p4.x, p4.y)
                        drawPath(loop, color, style = Stroke(width = strokeW, pathEffect = effect))
                        arrowHead(p4, p3, color, arrowSize)
                        return@forEach
                    }
                    if (isBack) {
                        // 回边：从源节点侧面绕行到目标节点侧面（醒目色）。
                        val gap = BACK_CHANNEL_DP * density
                        val channel = max(a.right, b.right) + gap
                        val p0 = Offset(px(a.right), py(a.cy))
                        val p1 = Offset(px(channel), py(a.cy))
                        val p2 = Offset(px(channel), py(b.cy))
                        val p3 = Offset(px(b.right) + strokeW, py(b.cy))
                        val path = Path().apply {
                            moveTo(p0.x, p0.y)
                            lineTo(p1.x, p1.y)
                            lineTo(p2.x, p2.y)
                            lineTo(p3.x, p3.y)
                        }
                        drawPath(path, color, style = Stroke(width = strokeW))
                        arrowHead(p3, p2, color, arrowSize)
                    } else {
                        val exit = a.bottom
                        val entry = b.top
                        val midY = (exit + entry) / 2f
                        val p0 = Offset(px(a.cx), py(exit))
                        val p1 = Offset(px(a.cx), py(midY))
                        val p2 = Offset(px(b.cx), py(midY))
                        val p3 = Offset(px(b.cx), py(entry))
                        val path = Path().apply {
                            moveTo(p0.x, p0.y)
                            lineTo(p1.x, p1.y)
                            lineTo(p2.x, p2.y)
                            lineTo(p3.x, p3.y)
                        }
                        drawPath(path, color, style = Stroke(width = strokeW, pathEffect = effect))
                        arrowHead(p3, p2, color, arrowSize)
                    }
                }

                // ── 节点 ──
                val nodeStrokeW = max(1f, 1f * density * sc.coerceIn(0.5f, 2f))
                val radius = CornerRadius(9f * density * sc.coerceIn(0.4f, 2f))
                val paintAddr = Paint().apply {
                    isAntiAlias = true
                    typeface = Typeface.MONOSPACE
                    textSize = (10.5f * density * sc).coerceIn(8f, 34f)
                }
                val paintSummary = Paint().apply {
                    isAntiAlias = true
                    typeface = Typeface.MONOSPACE
                    textSize = (9f * density * sc).coerceIn(7f, 30f)
                }
                layout.boxes.forEach { box ->
                    val isSel = box.index == selected
                    val topLeft = Offset(px(box.left), py(box.top))
                    val rectSize = Size(box.w * sc, box.h * sc)
                    drawRoundRect(
                        color = if (isSel) colors.primary.copy(alpha = 0.16f) else colors.surfaceContainerHigh,
                        topLeft = topLeft,
                        size = rectSize,
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = if (isSel) selColor else colors.outlineVariant,
                        topLeft = topLeft,
                        size = rectSize,
                        cornerRadius = radius,
                        style = Stroke(width = if (isSel) nodeStrokeW * 2f else nodeStrokeW),
                    )
                    val block = graph.blocks.getOrNull(box.index) ?: return@forEach
                    val maxTextW = box.w * sc - 12f * density
                    if (maxTextW <= 10f) return@forEach
                    val showSummary = block.summary.isNotBlank() && sc >= 0.45f
                    paintAddr.color = if (isSel) selColor.toArgb() else colors.onSurface.toArgb()
                    paintSummary.color = colors.onSurfaceVariant.toArgb()
                    val addrText = fitText(paintAddr, block.addrText, maxTextW)
                    val summaryText = if (showSummary) fitText(paintSummary, block.summary, maxTextW) else ""
                    val lineGap = 3f * density * sc.coerceIn(0.5f, 1.5f)
                    val totalH = if (summaryText.isNotEmpty()) paintAddr.textSize + lineGap + paintSummary.textSize else paintAddr.textSize
                    val startY = py(box.cy) - totalH / 2f
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        val aW = paintAddr.measureText(addrText)
                        nc.drawText(addrText, px(box.cx) - aW / 2f, startY + paintAddr.textSize, paintAddr)
                        if (summaryText.isNotEmpty()) {
                            val sW = paintSummary.measureText(summaryText)
                            nc.drawText(
                                summaryText,
                                px(box.cx) - sW / 2f,
                                startY + paintAddr.textSize + lineGap + paintSummary.textSize,
                                paintSummary,
                            )
                        }
                    }
                }
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

            // 右上角：适应屏幕
            Surface(
                onClick = { applyFit() },
                shape = RoundedCornerShape(AppShape.xs),
                color = colors.surfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Text(
                    if (zh) "适应屏幕" else "Fit",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                )
            }
            // 左上角：统计
            Surface(
                shape = RoundedCornerShape(AppShape.xs),
                color = colors.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            ) {
                Text(
                    "blocks ${graph.blocks.size} · edges ${graph.edges.size} · ${(scale * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.size(6.dp))

        // ── 下方信息条 ──
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
