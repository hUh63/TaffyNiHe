package com.soreverse.mcp.engine

/**
 * Exbin [com.exbin.app.dagre.DagreLayout]（dagre.js 的 1:1 Java 移植）桥接层。
 *
 * 输入节点尺寸与边，输出标准 dagre 布局坐标（节点中心 x/y、rank）与边的折线点集。
 * 供 CFG 画布作为「可选布局引擎」使用（与自研分层布局平行）。
 */
internal object ExbinDagre {

    /** 单个节点的布局结果（中心坐标 + 层号）。 */
    class NodeOut(val x: Float, val y: Float, val rank: Int)

    class EdgeOut(val points: List<Pair<Float, Float>>)

    /**
     * 执行 dagre 布局。
     *
     * @param ids     节点 id（保序）
     * @param sizes   id → (width, height)（px）
     * @param edges   (fromId, toId) 列表（自环会被忽略）
     * @param nodesep 同层节点间距
     * @param ranksep 层间距
     * @param rankdir "TB"（默认）| "LR"
     * @return (节点布局, 边折线点集) ；失败返回 null（调用方回退自研布局）
     */
    fun layout(
        ids: List<String>,
        sizes: Map<String, Pair<Float, Float>>,
        edges: List<Pair<String, String>>,
        nodesep: Double = 40.0,
        ranksep: Double = 60.0,
        rankdir: String = "TB",
    ): Pair<Map<String, NodeOut>, Map<String, List<Pair<Float, Float>>>>? {
        if (ids.isEmpty()) return null
        return runCatching {
            val g = com.exbin.app.dagre.DagreGraph()
            val graphAttrs = HashMap<String, Any?>()
            graphAttrs["rankdir"] = rankdir
            graphAttrs["nodesep"] = nodesep
            graphAttrs["ranksep"] = ranksep
            graphAttrs["edgesep"] = 10.0
            graphAttrs["marginx"] = 0.0
            graphAttrs["marginy"] = 0.0
            g.setGraph(graphAttrs)

            for (id in ids) {
                val s = sizes[id] ?: (40f to 20f)
                val na = HashMap<String, Any?>()
                na["width"] = s.first.toDouble()
                na["height"] = s.second.toDouble()
                g.setNode(id, na)
            }
            val seen = HashSet<String>(edges.size * 2)
            for ((a, b) in edges) {
                if (a == b) continue
                val key = "$a\u0000$b"
                if (!seen.add(key)) continue
                g.setEdge(a, b, HashMap<String, Any?>())
            }

            com.exbin.app.dagre.DagreLayout.layout(g)

            val nodes = HashMap<String, NodeOut>(ids.size * 2)
            for (id in ids) {
                val a = g.node(id) ?: continue
                val x = (a["x"] as? Number)?.toFloat() ?: continue
                val y = (a["y"] as? Number)?.toFloat() ?: continue
                val rank = (a["rank"] as? Number)?.toInt() ?: 0
                nodes[id] = NodeOut(x, y, rank)
            }

            val routes = HashMap<String, List<Pair<Float, Float>>>()
            for ((a, b) in edges) {
                if (a == b) continue
                if (routes.containsKey("$a|$b")) continue
                val e = g.edge(a, b) ?: continue
                val pts = e["points"]
                if (pts is List<*>) {
                    val list = ArrayList<Pair<Float, Float>>(pts.size)
                    for (p in pts) {
                        if (p is Map<*, *>) {
                            val px = (p["x"] as? Number)?.toFloat()
                            val py = (p["y"] as? Number)?.toFloat()
                            if (px != null && py != null) list.add(px to py)
                        }
                    }
                    if (list.isNotEmpty()) routes["$a|$b"] = list
                }
            }
            nodes to routes
        }.getOrNull()
    }
}
