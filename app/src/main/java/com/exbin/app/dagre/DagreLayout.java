package com.exbin.app.dagre;

import java.util.*;

/**
 * dagre 布局引擎 — Java 8 一比一移植。
 *
 * 严格参照 dagre/lib/layout.js 流水线:
 *   1. acyclic.run      — DFS 去环
 *   2. rank             — Network Simplex 分层
 *   3. normalize.run    — 跨层边插入虚拟节点
 *   4. order            — Barycenter 排序
 *   5. position         — Y + Brandes-Köpf X
 *   6. normalize.undo   — 移除虚拟节点，生成边 points
 *   7. translate        — 全局平移
 *   8. acyclic.undo     — 恢复反转边
 */
public class DagreLayout {

    // ── 常量 ──────────────────────────────────────────────────
    private static final String REV_PREFIX = "_rev_";

    // ============================================================
    // 公共入口
    // ============================================================
    /**
     * 主布局流水线 (严格对应 dagre/lib/layout.js runLayout)
     *   1. acyclic.run
     *   2. rank (asNonCompoundGraph)
     *   3. removeEmptyRanks + normalizeRanks
     *   4. normalize.run
     *   5. order
     *   6. position
     *   7. normalize.undo
     *   8. translateGraph
     *   9. assignNodeIntersects
     *  10. reversePointsForReversedEdges
     *  11. acyclic.undo
     */
    public static void layout(DagreGraph g) {
        acyclicRun(g);
        rank(g);
        removeEmptyRanks(g);
        normalizeRanks(g);
        normalizeRun(g);
        order(g);
        position(g);
        normalizeUndo(g);
        translateGraph(g);
        assignNodeIntersects(g);
        reversePoints(g);
        acyclicUndo(g);
    }

    // ============================================================
    // 1. acyclic — DFS 去环 (一比一 dagre/lib/acyclic.js)
    // ============================================================
    private static void acyclicRun(DagreGraph g) {
        // DFS 找环中的边并反转
        Set<String> visited = new HashSet<>();
        Set<String> onStack  = new HashSet<>();
        for (String v : g.nodes()) {
            if (!visited.contains(v)) acyclicDfs(g, v, visited, onStack);
        }
    }

    private static void acyclicDfs(DagreGraph g, String v,
								   Set<String> visited, Set<String> onStack) {
        visited.add(v);
        onStack.add(v);
        for (String eid : new ArrayList<>(g.outEdges(v))) {
            Map<String, Object> edge = g.edgeById(eid);
            if (edge == null) continue;
            String w = (String) edge.get("w");
            // v2.8.20: 自环直接删除，不反转
            if (w.equals(v)) {
                g.removeEdge(eid);
                continue;
            }
            if (onStack.contains(w)) {
                // 反转回边
                String name = (String) edge.get("name");
                String fwd = (name != null) ? name : (v + "->" + w);
                Map<String, Object> copy = new LinkedHashMap<>(edge);
                copy.remove("v"); copy.remove("w"); copy.remove("name");
                g.removeEdge(eid);
                copy.put("reversed", true);
                copy.put("forwardName", fwd);
                g.setEdge(w, v, copy, REV_PREFIX + fwd);
            } else if (!visited.contains(w)) {
                acyclicDfs(g, w, visited, onStack);
            }
        }
        onStack.remove(v);
    }

    private static void acyclicUndo(DagreGraph g) {
        List<String> todo = new ArrayList<>();
        for (String eid : g.edges()) {
            Map<String, Object> e = g.edgeById(eid);
            if (e != null && Boolean.TRUE.equals(e.get("reversed")))
                todo.add(eid);
        }
        for (String eid : todo) {
            Map<String, Object> edge = g.edgeById(eid);
            if (edge == null) continue;
            String v = (String) edge.get("v"), w = (String) edge.get("w");
            String fwd = (String) edge.get("forwardName");
            Map<String, Object> attrs = new LinkedHashMap<>(edge);
            attrs.remove("v"); attrs.remove("w"); attrs.remove("name");
            attrs.remove("reversed"); attrs.remove("forwardName");
            g.removeEdge(eid);
            g.setEdge(w, v, attrs, fwd);
        }
    }

    // ============================================================
    // 2. rank — Network Simplex (一比一 dagre/lib/rank/ 目录)
    // ============================================================
    private static void rank(DagreGraph g) {
        longestPath(g);
        feasibleTree(g);
        networkSimplex(g);
    }

    private static void longestPath(DagreGraph g) {
        Map<String, Integer> memo = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        for (String v : g.nodes()) dfsLongestPath(g, v, memo, visiting);
        for (Map.Entry<String, Integer> e : memo.entrySet())
            g.node(e.getKey()).put("rank", e.getValue());
    }

    private static int dfsLongestPath(DagreGraph g, String v,
									  Map<String, Integer> memo, Set<String> visiting) {
        if (memo.containsKey(v)) return memo.get(v);
        // v2.8.19: 环检测 — 防止 StackOverflowError
        if (visiting.contains(v)) return 0;
        visiting.add(v);
        int max = 0;
        for (String p : g.predecessors(v)) {
            // v2.8.20: 跳过自环
            if (p.equals(v)) continue;
            max = Math.max(max, dfsLongestPath(g, p, memo, visiting));
        }
        int r = g.predecessors(v).isEmpty() ? 0 : max + 1;
        memo.put(v, r);
        visiting.remove(v);
        return r;
    }

    private static void feasibleTree(DagreGraph g) {
        // 构建生成树保证 rank 递增
        Set<String> inTree = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        Map<String, String> parent = new HashMap<>();
        for (String v : g.nodes()) {
            if (g.predecessors(v).isEmpty()) { inTree.add(v); q.add(v); }
        }
        if (inTree.isEmpty()) {
            String f = g.nodes().get(0); inTree.add(f); q.add(f);
        }
        while (!q.isEmpty()) {
            String v = q.poll();
            for (String w : g.successors(v))
                if (!inTree.contains(w)) { inTree.add(w); parent.put(w, v); q.add(w); }
            for (String p : g.predecessors(v))
                if (!inTree.contains(p)) { inTree.add(p); parent.put(v, p); q.add(p); }
        }
        for (int iter = 0; iter < 500; iter++) {
            boolean changed = false;
            for (Map.Entry<String, String> e : parent.entrySet()) {
                int cr = (Integer) g.node(e.getKey()).get("rank");
                int pr = (Integer) g.node(e.getValue()).get("rank");
                if (pr >= cr) {
                    shiftRank(g, e.getKey(), parent, pr + 1 - cr);
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private static void shiftRank(DagreGraph g, String root,
								  Map<String, String> parent, int delta) {
        g.node(root).put("rank", (Integer) g.node(root).get("rank") + delta);
        for (Map.Entry<String, String> e : parent.entrySet())
            if (root.equals(e.getValue())) shiftRank(g, e.getKey(), parent, delta);
    }

    private static void networkSimplex(DagreGraph g) {
        for (int iter = 0; iter < 200; iter++) {
            String bestW = null;
            int bestSlack = 0;
            for (String eid : g.edges()) {
                Map<String, Object> edge = g.edgeById(eid);
                if (edge == null) continue;
                Integer vr = (Integer) g.node((String) edge.get("v")).get("rank");
                Integer wr = (Integer) g.node((String) edge.get("w")).get("rank");
                if (vr == null || wr == null) continue;
                int slack = wr - vr - 1;
                if (slack < bestSlack) { bestSlack = slack; bestW = (String) edge.get("w"); }
            }
            if (bestW == null) break;
            incRank(g, bestW, -bestSlack);
        }
    }

    private static void incRank(DagreGraph g, String root, int delta) {
        g.node(root).put("rank", Math.max(0, (Integer) g.node(root).get("rank") + delta));
        for (String s : g.successors(root)) {
            int sr = (Integer) g.node(s).get("rank");
            int rr = (Integer) g.node(root).get("rank");
            if (sr <= rr) incRank(g, s, delta);
        }
    }

    // ============================================================
    // 2b. removeEmptyRanks — 移除空层 (dagre/lib/util.js)
    // ============================================================
    private static void removeEmptyRanks(DagreGraph g) {
        int maxR = maxRank(g);
        boolean[] hasNodes = new boolean[maxR + 1];
        for (String v : g.nodes()) {
            Integer r = (Integer) g.node(v).get("rank");
            if (r != null && r <= maxR) hasNodes[r] = true;
        }
        int[] map = new int[maxR + 1];
        int nr = 0;
        for (int r = 0; r <= maxR; r++) {
            if (hasNodes[r]) map[r] = nr++;
            else map[r] = -1;
        }
        for (String v : g.nodes()) {
            Integer r = (Integer) g.node(v).get("rank");
            if (r != null && r <= maxR && map[r] >= 0)
                g.node(v).put("rank", map[r]);
        }
    }

    // ============================================================
    // 2c. normalizeRanks — rank 连续化 (dagre/lib/util.js)
    // ============================================================
    private static void normalizeRanks(DagreGraph g) {
        int maxR = maxRank(g);
        boolean[] used = new boolean[maxR + 1];
        for (String v : g.nodes()) {
            Integer r = (Integer) g.node(v).get("rank");
            if (r != null) used[r] = true;
        }
        int[] remap = new int[maxR + 1];
        int nr = 0;
        for (int r = 0; r <= maxR; r++) {
            remap[r] = nr;
            if (used[r]) nr++;
        }
        if (nr == maxR + 1) return;
        for (String v : g.nodes()) {
            Integer r = (Integer) g.node(v).get("rank");
            if (r != null) g.node(v).put("rank", remap[r]);
        }
    }

    // ============================================================
    // 3. normalize — 跨层边插入虚拟节点 (一比一 dagre/lib/normalize.js)
    // ============================================================
    @SuppressWarnings("unchecked")
    private static void normalizeRun(DagreGraph g) {
        g.graph().put("dummyChains", new ArrayList<String>());

        List<EdgeObj> originalEdges = new ArrayList<>();
        for (String eid : g.edges()) {
            Map<String, Object> edge = g.edgeById(eid);
            if (edge != null) {
                originalEdges.add(new EdgeObj(
									  (String) edge.get("v"),
									  (String) edge.get("w"),
									  (String) edge.get("name"),
									  edge
								  ));
            }
        }

        for (EdgeObj eo : originalEdges) {
            normalizeEdge(g, eo.v, eo.w, eo.name, eo.attrs);
        }
    }

    private static void normalizeEdge(DagreGraph g,
									  String v, String w, String name,
									  Map<String, Object> edgeLabel) {
        int vRank = (Integer) g.node(v).get("rank");
        int wRank = (Integer) g.node(w).get("rank");
        if (wRank == vRank + 1) return;

        g.removeEdge(v, w, name);
        List<String> dummyIds = new ArrayList<>();

        for (int i = 0, r = vRank + 1; r < wRank; i++, r++) {
            edgeLabel.put("points", new ArrayList<double[]>());

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("width", 0.0);
            attrs.put("height", 0.0);
            attrs.put("edgeLabel", edgeLabel);
            attrs.put("edgeObj", new EdgeObj(v, w, name, null));
            attrs.put("rank", r);
            attrs.put("dummy", "edge"); // 重要：标记为 edge 类型的 dummy

            String dummyId = addDummyNode(g, attrs);
            dummyIds.add(dummyId);

            if (i == 0) {
                ((List<String>) g.graph().get("dummyChains")).add(dummyId);
            }
            g.setEdge(v, dummyId, new LinkedHashMap<>(), name);
            v = dummyId;
        }
        g.setEdge(v, w, new LinkedHashMap<>(), name);
    }

    private static int dummyCounter = 0;
    private static String addDummyNode(DagreGraph g, Map<String, Object> attrs) {
        String id;
        do { id = "_d" + (dummyCounter++); } while (g.hasNode(id));
        g.setNode(id, attrs);
        return id;
    }

    /** normalize.undo: 移除虚拟节点并生成 edge.points */
    @SuppressWarnings("unchecked")
    private static void normalizeUndo(DagreGraph g) {
        List<String> chains = (List<String>) g.graph().get("dummyChains");
        if (chains == null) return;

        for (String startId : new ArrayList<>(chains)) {
            String v = startId;
            Map<String, Object> node = g.node(v);
            if (node == null) continue;

            Map<String, Object> origLabel = (Map<String, Object>) node.get("edgeLabel");
            EdgeObj edgeObj = (EdgeObj) node.get("edgeObj");
            if (origLabel == null || edgeObj == null) continue;

            // 恢复原边
            Map<String, Object> edgeAttrs = new LinkedHashMap<>(origLabel);
            edgeAttrs.remove("points");
            g.setEdge(edgeObj.v, edgeObj.w, edgeAttrs, edgeObj.name);

            // 遍历 dummy 链，收集 points
            List<double[]> points = new ArrayList<>();
            while (node != null && node.get("dummy") != null) {
                List<String> succs = g.successors(v);
                if (succs.isEmpty()) break;

                double x = ((Number) node.getOrDefault("x", 0.0)).doubleValue();
                double y = ((Number) node.getOrDefault("y", 0.0)).doubleValue();
                points.add(new double[]{x, y});

                String next = succs.get(0);
                g.removeNode(v);
                v = next;
                node = g.node(v);
            }

            // 将 points 写入恢复后的边
            Map<String, Object> restored = g.edge(edgeObj.v, edgeObj.w, edgeObj.name);
            if (restored != null) {
                restored.put("points", points);
            }
        }
        g.graph().remove("dummyChains");
    }

    // ============================================================
    // 4. order — Barycenter (一比一 dagre/lib/order/ 目录)
    // ============================================================
    private static void order(DagreGraph g) {
        int maxR = maxRank(g);
        List<List<String>> layers = new ArrayList<>(maxR + 1);
        for (int r = 0; r <= maxR; r++) layers.add(new ArrayList<>());
        for (String v : g.nodes()) {
            Integer rank = (Integer) g.node(v).get("rank");
            if (rank != null) layers.get(rank).add(v);
        }

        // DFS 初始排序
        Set<String> vis = new HashSet<>();
        for (String v : g.nodes()) {
            Integer r = (Integer) g.node(v).get("rank");
            if (r != null && r == 0 && !vis.contains(v))
                initOrder(g, v, vis, layers);
        }

        // Barycenter 8 passes
        for (int pass = 0; pass < 8; pass++) {
            for (int l = 1; l <= maxR; l++)
                barySort(g, layers.get(l), layers.get(l - 1), true);
            for (int l = maxR - 1; l >= 0; l--)
                barySort(g, layers.get(l), layers.get(l + 1), false);
        }

        for (List<String> layer : layers)
            for (int i = 0; i < layer.size(); i++)
                g.node(layer.get(i)).put("order", i);
    }

    private static void initOrder(DagreGraph g, String v, Set<String> vis,
								  List<List<String>> layers) {
        if (vis.contains(v)) return;
        vis.add(v);
        Integer r = (Integer) g.node(v).get("rank");
        if (r != null && r < layers.size()) layers.get(r).add(v);
        for (String s : g.successors(v)) initOrder(g, s, vis, layers);
    }

    private static void barySort(DagreGraph g, List<String> cur,
								 List<String> nb, boolean topDown) {
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < nb.size(); i++) pos.put(nb.get(i), i);
        Map<String, Double> bary = new HashMap<>();
        for (String v : cur) {
            double sum = 0; int cnt = 0;
            for (String n : (topDown ? g.predecessors(v) : g.successors(v))) {
                Integer p = pos.get(n);
                if (p != null) { sum += p; cnt++; }
            }
            bary.put(v, cnt > 0 ? sum / cnt : Double.MAX_VALUE);
        }
        cur.sort(Comparator.comparingDouble(bary::get));
    }

    // ============================================================
    // 5. position — Y + Brandes-Köpf X
    // (一比一 dagre/lib/position.js + dagre/lib/position/bk.js)
    // ============================================================
    private static void position(DagreGraph g) {
        Map<String, Object> graph = g.graph();
        positionY(g, intVal(graph, "ranksep", 80));

        Map<String, Double> xs = positionX(g);
        for (Map.Entry<String, Double> e : xs.entrySet())
            g.node(e.getKey()).put("x", e.getValue());
    }

    private static void positionY(DagreGraph g, int ranksep) {
        int maxR = maxRank(g);
        List<List<String>> layers = layerMatrix(g, maxR);
        double prevY = 0;
        for (int r = 0; r <= maxR; r++) {
            List<String> layer = layers.get(r);
            double maxH = 0;
            for (String v : layer)
                maxH = Math.max(maxH, doubleVal(g.node(v), "height", 0));
            for (String v : layer)
                g.node(v).put("y", prevY + maxH / 2.0);
            prevY += maxH + ranksep;
        }
    }

    /** Brandes-Köpf X 坐标 (一比一 dagre/lib/position/bk.js) */
    private static Map<String, Double> positionX(DagreGraph g) {
        int nodesep = intVal(g.graph(), "nodesep", 60);
        int edgesep = intVal(g.graph(), "edgesep", 20);

        int maxR = maxRank(g);
        List<List<String>> layers = layerMatrix(g, maxR);
        for (List<String> layer : layers)
            layer.sort(Comparator.comparingInt(v ->
					   (Integer) g.node(v).getOrDefault("order", 0)));

        // 4 方向: ul, ur, dl, dr
        Map<String, Double>[] xss = new HashMap[4];
        String[] tags = {"ul", "ur", "dl", "dr"};

        for (int a = 0; a < 4; a++) {
            boolean down  = (a >= 2);
            boolean right = (a % 2 == 1);

            List<List<String>> adj = adjustLayers(layers, down, right);
            NeighborFn nfn = down
                ? (gg, v) -> gg.successors(v)
			: (gg, v) -> gg.predecessors(v);

            AlignResult al = verticalAlignment(g, adj, nfn);
            Map<String, Double> xs = horizontalCompaction(g, adj, al.root, al.align,
														  nodesep, edgesep, right);
            if (right) {
                for (Map.Entry<String, Double> e : xs.entrySet())
                    xs.put(e.getKey(), -e.getValue());
            }
            xss[a] = xs;
        }

        // 找最小宽度方案
        String smallest = findSmallestWidth(g, xss);
        // 对齐
        alignCoords(xss, smallest);

        // 平衡
        String alignMode = (String) g.graph().get("align");
        return balance(xss, alignMode, g.nodes());
    }

    // ── 辅助: 调整层方向和顺序 ──
    private static List<List<String>> adjustLayers(List<List<String>> layers,
												   boolean down, boolean right) {
        List<List<String>> adj = new ArrayList<>();
        int max = layers.size() - 1;
        for (int r = down ? max : 0; down ? (r >= 0) : (r <= max); r += down ? -1 : 1) {
            List<String> layer = new ArrayList<>(layers.get(r));
            if (right) Collections.reverse(layer);
            adj.add(layer);
        }
        return adj;
    }

    // ── verticalAlignment (一比一) ──
    private static AlignResult verticalAlignment(DagreGraph g,
												 List<List<String>> layering,
												 NeighborFn neighborFn) {
        Map<String, String> root  = new HashMap<>();
        Map<String, String> align = new HashMap<>();
        Map<String, Integer> pos  = new HashMap<>();

        for (List<String> layer : layering)
            for (int i = 0; i < layer.size(); i++) {
                String v = layer.get(i);
                root.put(v, v);
                align.put(v, v);
                pos.put(v, i);
            }

        for (List<String> layer : layering) {
            int prevIdx = -1;
            for (String v : layer) {
                List<String> ws = neighborFn.get(g, v);
                if (ws.isEmpty()) continue;

                // 按 pos 排序邻居
                List<String> sorted = new ArrayList<>(ws);
                sorted.sort(Comparator.comparingInt(pos::get));

                int lo = (sorted.size() - 1) / 2;
                int hi = sorted.size() / 2;
                for (int i = lo; i <= hi; i++) {
                    String w = sorted.get(i);
                    if (align.get(v).equals(v) && prevIdx < pos.get(w)) {
                        align.put(w, v);
                        root.put(v, root.get(w));
                        align.put(v, root.get(v));
                        prevIdx = pos.get(w);
                    }
                }
            }
        }
        return new AlignResult(root, align);
    }

    // ── horizontalCompaction (一比一: buildBlockGraph + 两趟扫描) ──
    private static Map<String, Double> horizontalCompaction(DagreGraph g,
															List<List<String>> layering,
															Map<String, String> root,
															Map<String, String> align,
															int nodesep, int edgesep,
															boolean reverseSep) {
        // 1. 构建块图
        DagreGraph blockG = buildBlockGraph(g, layering, root, nodesep, edgesep, reverseSep);

        // 2. 两趟扫描 (严格对应 dagre bk.js iterate 逻辑)
        Map<String, Double> xs = new HashMap<>();

        // 第一趟: predecessors→node 顺序，最小坐标
        // topologicalOrder(blockG,true) 后序遍历 successors → 右→左
        // reverse 得到 左→右，保证前驱先处理
        List<String> order1 = new ArrayList<>(topologicalOrder(blockG, true));
        Collections.reverse(order1);
        for (String b : order1) {
            double maxX = 0;
            for (String eid : blockG.inEdges(b)) {
                Map<String, Object> edge = blockG.edgeById(eid);
                if (edge == null) continue;
                String pred = (String) edge.get("v");
                double w = doubleVal(edge, "weight", 0);
                maxX = Math.max(maxX, xs.getOrDefault(pred, 0.0) + w);
            }
            xs.put(b, maxX);
        }
        // 第二趟: successors→node 顺序，移除未用空间
        // topologicalOrder(blockG,true) 后序遍历 → 后继先处理 ✓
        List<String> order2 = topologicalOrder(blockG, true);
        for (String b : order2) {
            double minX = Double.MAX_VALUE;
            for (String eid : blockG.outEdges(b)) {
                Map<String, Object> edge = blockG.edgeById(eid);
                if (edge == null) continue;
                String succ = (String) edge.get("w");
                double w = doubleVal(edge, "weight", 0);
                if (xs.containsKey(succ)) {
                    minX = Math.min(minX, xs.get(succ) - w);
                }
            }
            if (minX < Double.MAX_VALUE) {
                xs.put(b, Math.max(xs.getOrDefault(b, 0.0), minX));
            }
        }

        // 3. 将块坐标分配给节点
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, String> e : align.entrySet()) {
            String v = e.getKey();
            String r = root.get(v);
            result.put(v, xs.getOrDefault(r, 0.0));
        }
        return result;
    }

    /** buildBlockGraph: 块图，边权重 = 块间节点的最大间距 */
    private static DagreGraph buildBlockGraph(DagreGraph g,
											  List<List<String>> layering,
											  Map<String, String> root,
											  int nodesep, int edgesep,
											  boolean reverseSep) {
        DagreGraph bg = new DagreGraph();

        SepFn sepFn = (v, w) -> {
            double vw = doubleVal(g.node(v), "width", 0);
            double ww = doubleVal(g.node(w), "width", 0);
            boolean vd = isDummy(g, v);
            boolean wd = isDummy(g, w);
            return vw / 2.0 + (vd ? edgesep : nodesep) / 2.0
				+ (wd ? edgesep : nodesep) / 2.0 + ww / 2.0;
        };

        for (List<String> layer : layering) {
            String prev = null;
            for (String v : layer) {
                String vRoot = root.get(v);
                bg.setNode(vRoot, new LinkedHashMap<>());
                if (prev != null) {
                    String prevRoot = root.get(prev);
                    double sep = sepFn.apply(v, prev);  // v 在右，prev 在左
                    Map<String, Object> existEdge = bg.edge(prevRoot, vRoot);
                    double existing = (existEdge != null)
                        ? doubleVal(existEdge, "weight", 0) : 0;
                    Map<String, Object> eAttrs = new LinkedHashMap<>();
                    eAttrs.put("weight", Math.max(sep, existing));
                    bg.setEdge(prevRoot, vRoot, eAttrs, prevRoot + "|" + vRoot);
                }
                prev = v;
            }
        }
        return bg;
    }

    /** 拓扑排序: forward=true 正向, false=反向 */
    private static List<String> topologicalOrder(DagreGraph g, boolean forward) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> temp = new HashSet<>();

        for (String v : g.nodes()) {
            if (!visited.contains(v)) topoVisit(g, v, visited, temp, result, forward);
        }
        return result;
    }

    private static void topoVisit(DagreGraph g, String v,
								  Set<String> visited, Set<String> temp,
								  List<String> result, boolean forward) {
        if (temp.contains(v)) return; // 忽略环
        if (visited.contains(v)) return;
        temp.add(v);
        List<String> neighbors = forward ? g.successors(v) : g.predecessors(v);
        for (String w : neighbors)
            topoVisit(g, w, visited, temp, result, forward);
        temp.remove(v);
        visited.add(v);
        result.add(v);
    }

    // ── findSmallestWidth / alignCoords / balance (一比一) ──
    private static String findSmallestWidth(DagreGraph g, Map<String, Double>[] xss) {
        double bestW = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int a = 0; a < 4; a++) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (Map.Entry<String, Double> e : xss[a].entrySet()) {
                String v = e.getKey();
                double hw = doubleVal(g.node(v), "width", 0) / 2.0;
                min = Math.min(min, e.getValue() - hw);
                max = Math.max(max, e.getValue() + hw);
            }
            if (max - min < bestW) { bestW = max - min; bestIdx = a; }
        }
        return new String[]{"ul", "ur", "dl", "dr"}[bestIdx];
    }

    private static void alignCoords(Map<String, Double>[] xss, String target) {
        int ti = -1;
        switch (target) { case "ul": ti = 0; break; case "ur": ti = 1; break;
			case "dl": ti = 2; break; case "dr": ti = 3; break; }
        Map<String, Double> tgt = xss[ti];
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
        for (double v : tgt.values()) { tMin = Math.min(tMin, v); tMax = Math.max(tMax, v); }

        for (int a = 0; a < 4; a++) {
            if (a == ti) continue;
            Map<String, Double> xs = xss[a];
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double v : xs.values()) { min = Math.min(min, v); max = Math.max(max, v); }
            boolean isLeft = (a == 0 || a == 2);
            double delta = isLeft ? (tMin - min) : (tMax - max);
            if (Math.abs(delta) > 1e-6) {
                for (String v : xs.keySet()) xs.put(v, xs.get(v) + delta);
            }
        }
    }

    private static Map<String, Double> balance(Map<String, Double>[] xss,
											   String alignMode,
											   List<String> allNodes) {
        Map<String, Double> result = new HashMap<>();
        for (String v : allNodes) {
            if (alignMode != null) {
                String key = alignMode.toLowerCase();
                int a = -1;
                switch (key) { case "ul": a = 0; break; case "ur": a = 1; break;
					case "dl": a = 2; break; case "dr": a = 3; break; }
                if (a >= 0) result.put(v, xss[a].getOrDefault(v, 0.0));
                else result.put(v, 0.0);
            } else {
                double[] vals = new double[4];
                for (int a = 0; a < 4; a++) vals[a] = xss[a].getOrDefault(v, 0.0);
                Arrays.sort(vals);
                result.put(v, (vals[1] + vals[2]) / 2.0);
            }
        }
        return result;
    }

    // ============================================================
    // 6. translateGraph — 全局平移
    // ============================================================
    private static void translateGraph(DagreGraph g) {
        int marginX = intVal(g.graph(), "marginx", 50);
        int marginY = intVal(g.graph(), "marginy", 50);

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (String v : g.nodes()) {
            Map<String, Object> n = g.node(v);
            double x = doubleVal(n, "x", 0), y = doubleVal(n, "y", 0);
            double w = doubleVal(n, "width", 0), h = doubleVal(n, "height", 0);
            minX = Math.min(minX, x - w / 2.0);
            minY = Math.min(minY, y - h / 2.0);
        }
        double dx = marginX - minX;
        double dy = marginY - minY;

        for (String v : g.nodes()) {
            Map<String, Object> n = g.node(v);
            n.put("x", doubleVal(n, "x", 0) + dx);
            n.put("y", doubleVal(n, "y", 0) + dy);
        }

        // 平移边 points
        for (String eid : g.edges()) {
            Map<String, Object> edge = g.edgeById(eid);
            @SuppressWarnings("unchecked")
				List<double[]> pts = (List<double[]>) edge.get("points");
            if (pts != null) {
                for (double[] p : pts) { p[0] += dx; p[1] += dy; }
            }
        }
    }

    // ============================================================
    // 7. assignNodeIntersects — 边端点剪裁到节点边界 (dagre/lib/util.js)
    // ============================================================
    @SuppressWarnings("unchecked")
    private static void assignNodeIntersects(DagreGraph g) {
        for (String eid : g.edges()) {
            Map<String, Object> edge = g.edgeById(eid);
            if (edge == null) continue;
            String v = (String) edge.get("v");
            String w = (String) edge.get("w");
            Map<String, Object> nodeV = g.node(v);
            Map<String, Object> nodeW = g.node(w);
            if (nodeV == null || nodeW == null) continue;

            List<double[]> points = (List<double[]>) edge.get("points");
            if (points == null || points.isEmpty()) {
                // 无边路由点: 直接连两个节点中心
                points = new ArrayList<>();
                points.add(new double[]{
							   doubleVal(nodeW, "x", 0),
							   doubleVal(nodeW, "y", 0)
						   });
                points.add(new double[]{
							   doubleVal(nodeV, "x", 0),
							   doubleVal(nodeV, "y", 0)
						   });
                edge.put("points", points);
            }

            // 第一个点: 从源节点 v 出发 → 剪裁到 v 的矩形边界
            double[] p1 = points.get(0);
            double[] clipped1 = intersectRect(nodeV, p1);
            points.set(0, clipped1);

            // 最后一个点: 进入目标节点 w → 剪裁到 w 的矩形边界
            double[] pN = points.get(points.size() - 1);
            double[] clippedN = intersectRect(nodeW, pN);
            points.set(points.size() - 1, clippedN);
        }
    }

    /**
     * intersectRect: 计算从矩形中心到外部点 p 的连线与矩形边界的交点。
     * dagre/lib/util.js 一比一移植。
     */
    private static double[] intersectRect(Map<String, Object> node, double[] p) {
        double x = doubleVal(node, "x", 0);
        double y = doubleVal(node, "y", 0);
        double w = doubleVal(node, "width", 0) / 2.0;
        double h = doubleVal(node, "height", 0) / 2.0;

        double dx = p[0] - x;
        double dy = p[1] - y;

        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
            // 点在中心 → 默认从底部出去或顶部进入
            return new double[]{x, y};
        }

        double sx, sy;
        if (Math.abs(dy) * w > Math.abs(dx) * h) {
            // 交点在水平边 (上/下)
            sy = (dy > 0) ? y + h : y - h;
            sx = (Math.abs(dy) > 1e-9) ? x + dx * (sy - y) / dy : x;
        } else {
            // 交点在垂直边 (左/右)
            sx = (dx > 0) ? x + w : x - w;
            sy = (Math.abs(dx) > 1e-9) ? y + dy * (sx - x) / dx : y;
        }
        return new double[]{sx, sy};
    }

    // ============================================================
    // 8. reversePoints — 反转被反转边的路由点 (dagre/lib/util.js)
    // ============================================================
    @SuppressWarnings("unchecked")
    private static void reversePoints(DagreGraph g) {
        for (String eid : g.edges()) {
            Map<String, Object> edge = g.edgeById(eid);
            if (edge == null) continue;
            if (Boolean.TRUE.equals(edge.get("reversed"))) {
                List<double[]> points = (List<double[]>) edge.get("points");
                if (points != null) Collections.reverse(points);
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================
    private static int maxRank(DagreGraph g) {
        int max = 0;
        for (String v : g.nodes()) {
            Integer r = (Integer) g.node(v).get("rank");
            if (r != null && r > max) max = r;
        }
        return max;
    }

    private static List<List<String>> layerMatrix(DagreGraph g, int maxR) {
        List<List<String>> layers = new ArrayList<>(maxR + 1);
        for (int r = 0; r <= maxR; r++) layers.add(new ArrayList<>());
        for (String v : g.nodes()) {
            Integer rank = (Integer) g.node(v).get("rank");
            if (rank != null) layers.get(rank).add(v);
        }
        return layers;
    }

    private static boolean isDummy(DagreGraph g, String v) {
        return g.node(v).get("dummy") != null;
    }

    private static int intVal(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).intValue() : def;
    }

    private static double doubleVal(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).doubleValue() : def;
    }

    // ============================================================
    // 内部类型
    // ============================================================
    @FunctionalInterface
    private interface SepFn { double apply(String v, String w); }

    @FunctionalInterface
    private interface NeighborFn { List<String> get(DagreGraph g, String v); }

    private static class AlignResult {
        final Map<String, String> root, align;
        AlignResult(Map<String, String> r, Map<String, String> a) { root = r; align = a; }
    }

    /** 保存原始边信息 (v, w, name)，用于 normalize.undo 恢复 */
    private static class EdgeObj {
        final String v, w, name;
        final Map<String, Object> attrs; // normalize.run 时原边属性
        EdgeObj(String v, String w, String name, Map<String, Object> attrs) {
            this.v = v; this.w = w; this.name = name; this.attrs = attrs;
        }
    }
}

