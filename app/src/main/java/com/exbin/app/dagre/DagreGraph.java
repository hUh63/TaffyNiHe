package com.exbin.app.dagre;

import java.util.*;

/**
 * dagre.graphlib.Graph 的 Java 8 移植。
 * 支持多图(multigraph)、节点属性、边属性、图级属性。
 * 仅实现 CFG 布局所需的功能子集。
 */
public class DagreGraph {

    // ── 图级选项 ────────────────────────────────────────────────
    private final Map<String, Object> graphAttrs = new LinkedHashMap<>();

    // ── 节点: nodeId → attributes ───────────────────────────────
    private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();

    // ── 边: 多图支持, 用 "v|w|name" 作为 key ──────────────────
    //     每条边存 {v, w, name, ...customAttrs}
    private final Map<String, Map<String, Object>> edges = new LinkedHashMap<>();

    // ── 父子关系（子图，这里仅支持一层） ──────────────────────
    private final Map<String, String> parentMap = new HashMap<>();
    private final Map<String, List<String>> childrenMap = new HashMap<>();

    // ── 缓存: 后继/前驱 ─────────────────────────────────────────
    private Map<String, List<String>> successorsCache;
    private Map<String, List<String>> predecessorsCache;
    private boolean dirty = true;

    private final boolean isMultigraph;

    public DagreGraph() {
        this(false);
    }

    public DagreGraph(boolean multigraph) {
        this.isMultigraph = multigraph;
    }

    // ============================================================
    // 图级
    // ============================================================
    public void setGraph(Map<String, Object> attrs) {
        graphAttrs.clear();
        if (attrs != null) graphAttrs.putAll(attrs);
    }

    public Map<String, Object> graph() {
        return graphAttrs;
    }

    public Object graph(String key) {
        return graphAttrs.get(key);
    }

    // ============================================================
    // 节点
    // ============================================================
    public void setNode(String id, Map<String, Object> attrs) {
        Map<String, Object> node = nodes.get(id);
        if (node == null) {
            node = new LinkedHashMap<>();
            nodes.put(id, node);
        }
        if (attrs != null) node.putAll(attrs);
        dirty = true;
    }

    public Map<String, Object> node(String id) {
        return nodes.get(id);
    }

    public List<String> nodes() {
        return new ArrayList<>(nodes.keySet());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public void removeNode(String id) {
        nodes.remove(id);
        // also remove incident edges
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : edges.entrySet()) {
            Map<String, Object> edge = e.getValue();
            if (id.equals(edge.get("v")) || id.equals(edge.get("w"))) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) edges.remove(k);
        parentMap.remove(id);
        childrenMap.remove(id);
        dirty = true;
    }

    // ============================================================
    // 边 (多图)
    // ============================================================
    public void setEdge(String v, String w, Map<String, Object> attrs, String name) {
        if (!isMultigraph) {
            setEdgeSimple(v, w, attrs);
            return;
        }
        String key = edgeKey(v, w, name);
        Map<String, Object> edge = edges.get(key);
        if (edge == null) {
            edge = new LinkedHashMap<>();
            edge.put("v", v);
            edge.put("w", w);
            edge.put("name", name);
            edges.put(key, edge);
        }
        if (attrs != null) edge.putAll(attrs);
        dirty = true;
    }

    public void setEdge(String v, String w, Map<String, Object> attrs) {
        setEdge(v, w, attrs, null);
    }

    private void setEdgeSimple(String v, String w, Map<String, Object> attrs) {
        String key = v + "|" + w;
        Map<String, Object> edge = edges.get(key);
        if (edge == null) {
            edge = new LinkedHashMap<>();
            edge.put("v", v);
            edge.put("w", w);
            edges.put(key, edge);
        }
        if (attrs != null) edge.putAll(attrs);
        dirty = true;
    }

    public Map<String, Object> edge(String v, String w, String name) {
        return edges.get(edgeKey(v, w, name));
    }

    public Map<String, Object> edge(String v, String w) {
        return edge(v, w, null);
    }

    /** 取边对象（通过 edge id），edge id 是 edges map 的 key */
    public Map<String, Object> edgeById(String edgeId) {
        return edges.get(edgeId);
    }

    public List<String> edges() {
        return new ArrayList<>(edges.keySet());
    }

    public void removeEdge(String v, String w, String name) {
        edges.remove(edgeKey(v, w, name));
        dirty = true;
    }

    public void removeEdge(String edgeId) {
        edges.remove(edgeId);
        dirty = true;
    }

    // ============================================================
    // 邻接关系
    // ============================================================
    public List<String> successors(String v) {
        rebuildAdj();
        List<String> s = successorsCache.get(v);
        return s != null ? s : Collections.emptyList();
    }

    public List<String> predecessors(String v) {
        rebuildAdj();
        List<String> p = predecessorsCache.get(v);
        return p != null ? p : Collections.emptyList();
    }

    /** 出边列表（返回 edge id） */
    public List<String> outEdges(String v) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : edges.entrySet()) {
            if (v.equals(e.getValue().get("v"))) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /** 入边列表（返回 edge id） */
    public List<String> inEdges(String v) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : edges.entrySet()) {
            if (v.equals(e.getValue().get("w"))) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    // ============================================================
    // 子图/父子关系（简化版，仅支持一层）
    // ============================================================
    public void setParent(String child, String parent) {
        parentMap.put(child, parent);
        childrenMap.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
    }

    public String parent(String v) {
        return parentMap.get(v);
    }

    public List<String> children(String v) {
        List<String> c = childrenMap.get(v);
        return c != null ? c : Collections.emptyList();
    }

    // ============================================================
    // 内部
    // ============================================================
    private String edgeKey(String v, String w, String name) {
        if (name != null) return v + "|" + w + "|" + name;
        return v + "|" + w;
    }

    private void rebuildAdj() {
        if (!dirty) return;
        successorsCache = new HashMap<>();
        predecessorsCache = new HashMap<>();
        for (String n : nodes.keySet()) {
            successorsCache.put(n, new ArrayList<>());
            predecessorsCache.put(n, new ArrayList<>());
        }
        for (Map<String, Object> edge : edges.values()) {
            String v = (String) edge.get("v");
            String w = (String) edge.get("w");
            List<String> s = successorsCache.get(v);
            if (s != null && !s.contains(w)) s.add(w);
            List<String> p = predecessorsCache.get(w);
            if (p != null && !p.contains(v)) p.add(v);
        }
        dirty = false;
    }
}
