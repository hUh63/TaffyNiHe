package com.soreverse.mcp.engine

import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkGraphFactory
import org.eclipse.elk.graph.ElkGraphPackage
import org.eclipse.elk.graph.ElkNode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Eclipse ELK（Layered）布局引擎桥接。
 *
 * 对应 Exbin `ui/graph/cfg/ElkLayoutEngine`（官方 ELK Java 库版的移植版）：
 * 以 `org.eclipse.elk.layered` 算法做分层布局 + 正交边路由，
 * 参数与 Exbin 保持一致（层间距 / 同层间距 / 边-节点间距 / 减交叉三件套等）。
 *
 * 初始化（EMF 元数据注册）是全局一次性动作，这里用 [AtomicBoolean] 做双检锁。
 */
internal object ExbinElk {

    /** 单个节点的布局结果（中心坐标 + 层号）。 */
    class NodeOut(val x: Float, val y: Float, val rank: Int)

    private val initialized = AtomicBoolean(false)

    private fun ensureInit() {
        if (initialized.get()) return
        synchronized(initialized) {
            if (initialized.get()) return
            try {
                ElkGraphPackage.eINSTANCE.eClass()
                LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(
                    CoreOptions(),
                    LayeredOptions(),
                )
                initialized.set(true)
            } catch (t: Throwable) {
                initialized.set(false)
            }
        }
    }

    /**
     * 执行 ELK Layered 布局。
     *
     * @param ids         节点 id（保序）
     * @param sizes       id → (width, height)（px）
     * @param edges       (fromId, toId) 列表（自环忽略）
     * @param nodesep     同层节点间距
     * @param ranksep     层间距
     * @param edgeRouting "orthogonal" | "polyline" | "splines"
     * @return (节点中心坐标+层号, 边折线点集) ；失败返回 null（调用方回退）
     */
    fun layout(
        ids: List<String>,
        sizes: Map<String, Pair<Float, Float>>,
        edges: List<Pair<String, String>>,
        nodesep: Double = 60.0,
        ranksep: Double = 80.0,
        edgeRouting: String = "orthogonal",
    ): Pair<Map<String, ExbinDagre.NodeOut>, Map<String, List<Pair<Float, Float>>>>? {
        if (ids.isEmpty()) return null
        return runCatching {
            ensureInit()
            val f = ElkGraphFactory.eINSTANCE
            val root = f.createElkNode()
            val nodes = HashMap<String, ElkNode>(ids.size * 2)
            val order = HashMap<String, Int>(ids.size * 2)
            for ((i, id) in ids.withIndex()) {
                val s = sizes[id] ?: (120f to 44f)
                val en = f.createElkNode()
                en.setDimensions(s.first.toDouble(), s.second.toDouble())
                root.children.add(en)
                nodes[id] = en
                order[id] = i
            }

            val edgeList = ArrayList<org.eclipse.elk.graph.ElkEdge>(edges.size)
            val edgePairs = ArrayList<Pair<String, String>>(edges.size)
            val seen = HashSet<String>(edges.size * 2)
            for ((a, b) in edges) {
                if (a == b) continue
                val key = "$a\u0000$b"
                if (!seen.add(key)) continue
                val na = nodes[a] ?: continue
                val nb = nodes[b] ?: continue
                val e = f.createElkEdge()
                e.sources.add(na)
                e.targets.add(nb)
                root.containedEdges.add(e)
                edgeList.add(e)
                edgePairs.add(a to b)
            }

            // ── 布局参数（对齐 Exbin ElkLayoutEngine 的取值）──
            root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
            root.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            root.setProperty(
                CoreOptions.EDGE_ROUTING,
                when (edgeRouting) {
                    "polyline" -> EdgeRouting.POLYLINE
                    "splines" -> EdgeRouting.SPLINES
                    else -> EdgeRouting.ORTHOGONAL
                },
            )
            root.setProperty(CoreOptions.SPACING_NODE_NODE, nodesep)
            root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, ranksep)
            root.setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, nodesep)
            root.setProperty(CoreOptions.SPACING_EDGE_NODE, 10.0)
            root.setProperty(LayeredOptions.SPACING_EDGE_EDGE, 14.0)
            root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, 24.0)
            root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 60.0)
            root.setProperty(LayeredOptions.THOROUGHNESS, 15)
            root.setProperty(LayeredOptions.MERGE_EDGES, false)
            root.setProperty(LayeredOptions.UNNECESSARY_BENDPOINTS, true)
            root.setProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY, CycleBreakingStrategy.DEPTH_FIRST)
            root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.PREFER_EDGES)
            root.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_ORDER)

            RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

            // ── 读节点坐标（ELK 为左上角；转为中心坐标）──
            // 层号由 y 坐标离散化得到（ELK 不直接暴露 rank，用于回边判定）。
            val ys = ArrayList<Double>(ids.size)
            for (id in ids) nodes[id]?.let { ys.add(it.y) }
            ys.sort()
            val rankLevels = ArrayList<Double>(ys.size)
            for (y in ys) {
                if (rankLevels.isEmpty() || y - rankLevels.last() > 1.0) rankLevels.add(y)
            }

            val out = HashMap<String, ExbinDagre.NodeOut>(ids.size * 2)
            for (id in ids) {
                val en = nodes[id] ?: continue
                val w = en.width
                val h = en.height
                val cx = (en.x + w / 2.0).toFloat()
                val cy = (en.y + h / 2.0).toFloat()
                var rank = 0
                for ((ri, ry) in rankLevels.withIndex()) {
                    if (en.y <= ry + 0.5) { rank = ri; break }
                    rank = ri
                }
                out[id] = ExbinDagre.NodeOut(cx, cy, rank)
            }

            // ── 读边路径（section: start → bends → end）──
            val routes = HashMap<String, List<Pair<Float, Float>>>(edgePairs.size * 2)
            for (i in edgePairs.indices) {
                val e = edgeList[i]
                if (e.sections.isEmpty()) continue
                val sec = e.sections[0]
                val pts = ArrayList<Pair<Float, Float>>(4)
                pts.add(sec.startX.toFloat() to sec.startY.toFloat())
                for (bp in sec.bendPoints) pts.add(bp.x.toFloat() to bp.y.toFloat())
                pts.add(sec.endX.toFloat() to sec.endY.toFloat())
                if (pts.size >= 2) {
                    val (a, b) = edgePairs[i]
                    routes["$a|$b"] = pts
                }
            }
            out to routes
        }.getOrNull()
    }
}
