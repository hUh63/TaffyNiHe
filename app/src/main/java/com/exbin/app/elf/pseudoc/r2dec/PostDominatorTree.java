package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-dominator tree builder — 后支配关系 = 反向 CFG 上的支配关系。
 *
 * <p>a 后支配 b ⟺ 从 b 出发的所有路径都经过 a (a 在每条 b→exit 路径上)。
 * 等价地: 在 reverse CFG (所有边反向, return 块连向虚拟出口 EXIT) 上,
 * a 支配 b。
 *
 * <p>实现: 与 {@link DominatorTree} 相同的 Cooper-Harvey-Kennedy 算法,
 * 输入为反向图。用于:
 * <ul>
 *   <li>if-else 汇合点: then 与 else 分支的共同后支配者 (最近共同后支配者)。</li>
 *   <li>循环出口: header 的后支配者 = 从循环头出发必经的出口块
 *       (对自然循环成立; 不可约循环无后支配者 → 结构化器降级 goto)。</li>
 * </ul>
 */
public final class PostDominatorTree {

    /** 虚拟出口块地址 (不在指令流中). */
    public static final long EXIT = Long.MIN_VALUE;

    /** Block address → post-immediate dominator (0 = undefined). */
    private final Map<Long, Long> pdom = new HashMap<>();

    /** Reverse post-order of the reversed graph (from EXIT). */
    private final List<Long> rpo;

    /** RPO index for intersect comparisons. */
    private final Map<Long, Integer> rpoIndex = new HashMap<>();

    /** All block addresses (excluding EXIT). */
    private final List<Long> blockAddrs;

    /**
     * Build the post-dominator tree.
     *
     * @param blockAddrs  all block addresses (entry block first, as in the
     *                    forward CFG)
     * @param succs       forward successor map (return blocks have empty or
     *                    absent successor lists — they connect to EXIT)
     * @param preds       forward predecessor map
     */
    public PostDominatorTree(List<Long> blockAddrs,
                             Map<Long, List<Long>> preds,
                             Map<Long, List<Long>> succs) {
        this.blockAddrs = blockAddrs;
        // ── Build reversed graph ──
        // revSuccs[x] = forward preds[x];  revPreds[x] = forward succs[x]
        // EXIT's preds = blocks with no forward successors (returns/aborts).
        Map<Long, List<Long>> revSuccs = new HashMap<>();
        Map<Long, List<Long>> revPreds = new HashMap<>();
        for (long a : blockAddrs) {
            revSuccs.put(a, new ArrayList<>());
            revPreds.put(a, new ArrayList<>());
        }
        revSuccs.put(EXIT, new ArrayList<>());
        revPreds.put(EXIT, new ArrayList<>());
        for (long a : blockAddrs) {
            List<Long> s = succs.get(a);
            if (s == null || s.isEmpty()) {
                // return/abort block → EXIT in the reversed graph
                // 原图边 a→EXIT ⇒ reverse 边 EXIT→a
                revPreds.get(a).add(EXIT);
                revSuccs.get(EXIT).add(a);
            } else {
                for (long t : s) {
                    if (t == EXIT) continue;
                    // 原图边 a→t ⇒ reverse 边 t→a
                    revPreds.get(a).add(t);
                    revSuccs.get(t).add(a);
                }
            }
        }
        // ── CHK dominance on the reversed graph, rooted at EXIT ──
        this.rpo = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        List<Long> postOrder = new ArrayList<>();
        dfsPostOrder(EXIT, revSuccs, visited, postOrder);
        for (int i = postOrder.size() - 1; i >= 0; i--) {
            rpo.add(postOrder.get(i));
        }
        for (int i = 0; i < rpo.size(); i++) {
            rpoIndex.put(rpo.get(i), i);
        }
        computePdom(revPreds);
    }

    private void dfsPostOrder(long addr, Map<Long, List<Long>> succs,
                              Set<Long> visited, List<Long> postOrder) {
        if (!visited.add(addr)) return;
        List<Long> s = succs.get(addr);
        if (s != null) {
            for (long succ : s) {
                dfsPostOrder(succ, succs, visited, postOrder);
            }
        }
        postOrder.add(addr);
    }

    private void computePdom(Map<Long, List<Long>> revPreds) {
        pdom.put(EXIT, EXIT);
        for (long addr : blockAddrs) {
            pdom.put(addr, 0L);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (long b : rpo) {
                if (b == EXIT) continue;
                long newPdom = 0;
                List<Long> bPreds = revPreds.get(b);
                if (bPreds == null) continue;
                for (long p : bPreds) {
                    if (pdom.getOrDefault(p, 0L) != 0L) {
                        newPdom = p;
                        break;
                    }
                }
                if (newPdom == 0) continue;
                for (long p : bPreds) {
                    if (p == newPdom) continue;
                    if (pdom.getOrDefault(p, 0L) != 0L) {
                        newPdom = intersect(p, newPdom);
                    }
                }
                if (pdom.get(b) != newPdom) {
                    pdom.put(b, newPdom);
                    changed = true;
                }
            }
        }
    }

    private long intersect(long b1, long b2) {
        long finger1 = b1;
        long finger2 = b2;
        while (finger1 != finger2) {
            while (rpoIndex.getOrDefault(finger1, Integer.MAX_VALUE)
                    > rpoIndex.getOrDefault(finger2, Integer.MAX_VALUE)) {
                finger1 = pdom.getOrDefault(finger1, 0L);
                if (finger1 == 0) return finger2;
            }
            while (rpoIndex.getOrDefault(finger2, Integer.MAX_VALUE)
                    > rpoIndex.getOrDefault(finger1, Integer.MAX_VALUE)) {
                finger2 = pdom.getOrDefault(finger2, 0L);
                if (finger2 == 0) return finger1;
            }
        }
        return finger1;
    }

    /**
     * Check if {@code a} post-dominates {@code b}: every path from {@code b}
     * to EXIT passes through {@code a}.
     *
     * @param a candidate post-dominator block address
     * @param b dominated block address
     * @return true if a post-dominates b (false when b cannot reach EXIT,
     *         e.g. inside an infinite loop with no exit)
     */
    public boolean postDominates(long a, long b) {
        if (a == b) return true;
        if (a == EXIT) return true; // EXIT 后支配一切能到出口的块
        long cur = b;
        while (true) {
            long d = pdom.getOrDefault(cur, 0L);
            if (d == 0 || d == cur) return false; // 未定义 (无法到 EXIT) 或非 EXIT 自环
            if (d == a) return true;
            cur = d;
        }
    }

    /** Immediate post-dominator of a block (0 = undefined). */
    public long ipdom(long addr) {
        return pdom.getOrDefault(addr, 0L);
    }

    /**
     * 最近共同后支配者 (Least Common Post-Dominator) of a and b —
     * 两条路径的汇合点。任一无法到 EXIT 时返回 0。
     */
    public long lcpd(long a, long b) {
        long x = a, y = b;
        Set<Long> seen = new HashSet<>();
        while (x != 0) {
            seen.add(x);
            if (x == EXIT) break;
            long d = pdom.getOrDefault(x, 0L);
            if (d == x) break;
            x = d;
        }
        while (y != 0) {
            if (seen.contains(y)) return y;
            long d = pdom.getOrDefault(y, 0L);
            if (d == y) break;
            y = d;
        }
        return 0;
    }
}
