package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dominator tree builder — implements Cooper-Harvey-Kennedy algorithm
 * (A Simple, Fast Dominance Algorithm, 2001).
 *
 * <p>Computes:
 * <ul>
 *   <li><b>Immediate dominator</b> (idom) for each block — the unique
 *       nearest dominator in the dominator tree.</li>
 *   <li><b>Dominance frontier</b> (DF) for each block — the set of blocks
 *       where a variable defined in a dominated block "escapes" to a
 *       join point. Used for Phi-node placement in SSA construction.</li>
 *   <li><b> dominator tree children</b> — for traversing the dominator
 *       tree in pre-order during SSA renaming.</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <pre>
 *   // Cooper's iterative algorithm — O(n log n) with RPO
 *   for each node b in reverse postorder:
 *     new_idom = first processed predecessor of b
 *     for each other predecessor p of b:
 *       if idom[p] is defined:
 *         new_idom = intersect(p, new_idom)
 *     if idom[b] != new_idom:
 *       idom[b] = new_idom
 *       changed = true
 *
 *   // Dominance frontier
 *   for each block b with >= 2 predecessors:
 *     for each predecessor p of b:
 *       runner = p
 *       while runner != idom[b]:
 *         DF[runner].add(b)
 *         runner = idom[runner]
 * </pre>
 *
 * <p>This is the foundational pass for SSA construction. Without a
 * dominator tree, we cannot place Phi nodes or do variable renaming.
 */
public final class DominatorTree {

    /** Block address → immediate dominator block address (0 = entry, no idom). */
    private final Map<Long, Long> idom = new HashMap<>();

    /** Block address → dominance frontier (set of block addresses). */
    private final Map<Long, Set<Long>> domFrontier = new HashMap<>();

    /** Block address → children in dominator tree. */
    private final Map<Long, List<Long>> domChildren = new HashMap<>();

    /** Reverse post-order of blocks (used for iteration). */
    private final List<Long> rpo;

    /** RPO index for each block (for intersect comparisons). */
    private final Map<Long, Integer> rpoIndex = new HashMap<>();

    /** All block addresses. */
    private final List<Long> blockAddrs;

    /** Predecessor map: block → list of predecessor block addresses. */
    private final Map<Long, List<Long>> preds;

    /** Successor map: block → list of successor block addresses. */
    private final Map<Long, List<Long>> succs;

    /** Entry block address. */
    private final long entryAddr;

    public DominatorTree(List<Long> blockAddrs,
                         Map<Long, List<Long>> preds,
                         Map<Long, List<Long>> succs,
                         long entryAddr) {
        this.blockAddrs = blockAddrs;
        this.preds = preds;
        this.succs = succs;
        this.entryAddr = entryAddr;
        this.rpo = new ArrayList<>();
        computeRPO();
        computeIdom();
        computeDomFrontier();
        buildDomChildren();
    }

    // ── Reverse Post-Order computation (DFS) ──

    private void computeRPO() {
        Set<Long> visited = new HashSet<>();
        List<Long> postOrder = new ArrayList<>();
        dfsPostOrder(entryAddr, visited, postOrder);
        // RPO = reverse of post-order
        for (int i = postOrder.size() - 1; i >= 0; i--) {
            rpo.add(postOrder.get(i));
        }
        for (int i = 0; i < rpo.size(); i++) {
            rpoIndex.put(rpo.get(i), i);
        }
    }

    private void dfsPostOrder(long addr, Set<Long> visited, List<Long> postOrder) {
        if (!visited.add(addr)) return;
        List<Long> s = succs.get(addr);
        if (s != null) {
            for (long succ : s) {
                dfsPostOrder(succ, visited, postOrder);
            }
        }
        postOrder.add(addr);
    }

    // ── Immediate dominator computation (Cooper's algorithm) ──

    private void computeIdom() {
        // Initialize: entry dominates itself, others undefined
        idom.put(entryAddr, entryAddr);
        for (long addr : blockAddrs) {
            if (addr != entryAddr) {
                idom.put(addr, 0L); // 0 = undefined
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (long b : rpo) {
                if (b == entryAddr) continue;

                // Find first processed predecessor
                long newIdom = 0;
                List<Long> bPreds = preds.get(b);
                if (bPreds == null) continue;

                for (long p : bPreds) {
                    if (idom.getOrDefault(p, 0L) != 0L) {
                        newIdom = p;
                        break;
                    }
                }
                if (newIdom == 0) continue;

                // Intersect with other processed predecessors
                for (long p : bPreds) {
                    if (p == newIdom) continue;
                    if (idom.getOrDefault(p, 0L) != 0L) {
                        newIdom = intersect(p, newIdom);
                    }
                }

                if (idom.get(b) != newIdom) {
                    idom.put(b, newIdom);
                    changed = true;
                }
            }
        }
    }

    /**
     * Cooper's intersect: walk up the dominator tree until both
     * fingers meet. Uses RPO index for comparison.
     */
    private long intersect(long b1, long b2) {
        long finger1 = b1;
        long finger2 = b2;
        while (finger1 != finger2) {
            while (rpoIndex.getOrDefault(finger1, Integer.MAX_VALUE)
                    > rpoIndex.getOrDefault(finger2, Integer.MAX_VALUE)) {
                finger1 = idom.getOrDefault(finger1, 0L);
                if (finger1 == 0) return finger2;
            }
            while (rpoIndex.getOrDefault(finger2, Integer.MAX_VALUE)
                    > rpoIndex.getOrDefault(finger1, Integer.MAX_VALUE)) {
                finger2 = idom.getOrDefault(finger2, 0L);
                if (finger2 == 0) return finger1;
            }
        }
        return finger1;
    }

    // ── Dominance frontier computation ──

    private void computeDomFrontier() {
        for (long addr : blockAddrs) {
            domFrontier.put(addr, new HashSet<>());
        }

        for (long b : blockAddrs) {
            List<Long> bPreds = preds.get(b);
            if (bPreds == null || bPreds.size() < 2) continue;

            long bIdom = idom.getOrDefault(b, 0L);
            for (long p : bPreds) {
                long runner = p;
                while (runner != 0 && runner != bIdom) {
                    domFrontier.get(runner).add(b);
                    long next = idom.getOrDefault(runner, 0L);
                    if (next == runner) break; // self-loop, avoid infinite
                    runner = next;
                }
            }
        }
    }

    // ── Dominator tree children ──

    private void buildDomChildren() {
        for (long addr : blockAddrs) {
            domChildren.put(addr, new ArrayList<>());
        }
        for (long addr : blockAddrs) {
            if (addr == entryAddr) continue;
            long d = idom.getOrDefault(addr, 0L);
            if (d != 0 && d != addr) {
                domChildren.get(d).add(addr);
            }
        }
    }

    // ── Public accessors ──

    public long idom(long addr) {
        return idom.getOrDefault(addr, 0L);
    }

    public Set<Long> domFrontier(long addr) {
        return domFrontier.getOrDefault(addr, new HashSet<>());
    }

    public List<Long> domChildren(long addr) {
        return domChildren.getOrDefault(addr, new ArrayList<>());
    }

    public List<Long> rpo() {
        return rpo;
    }

    public long entry() {
        return entryAddr;
    }

    /**
     * Check if {@code a} dominates {@code b} (a is on the dominator
     * path from entry to b).
     */
    public boolean dominates(long a, long b) {
        if (a == b) return true;
        if (a == entryAddr) return true; // entry 支配一切可达块
        long cur = b;
        while (true) {
            long d = idom.getOrDefault(cur, 0L);
            if (d == 0 || d == cur) return false; // 未定义 idom (不可达块) 或非 entry 自环
            if (d == a) return true;
            cur = d;
        }
    }
}
