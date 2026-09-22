package com.exbin.app.elf.pseudoc.r2dec;

import com.exbin.app.elf.ControlFlowAnalyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Control flow structurer — Stage 4 of the r2dec pipeline.
 * <p>Ported from r2dec's {@code controlflow.js}. Takes a flat list of
 * {@link IrInsn} objects (with control-flow metadata set by the handler in
 * Stage 3) and produces structured pseudo-C output with if/else, while,
 * do-while, and goto constructs.
 *
 * <h3>Algorithm overview</h3>
 * <ol>
 *   <li>{@link #identifyBlocks()} — partition instructions into basic blocks
 *       using leader analysis (first instruction, jump targets, post-branch,
 *       post-return).</li>
 *   <li>{@link #buildBlockGraph()} — compute fall-through, jump targets,
 *       predecessors/successors, and branch classification for each block.</li>
 *   <li>{@link #emitRegion(long, java.util.Set, int)} — recursive-descent
 *       structuring that detects loops (while, do-while), conditionals
 *       (if/else), and falls back to {@code goto} for irreducible edges.
 *       v4.5: 区域边界由支配/后支配闭包集合给出, 不再依赖地址单调性.</li>
 * </ol>
 *
 * <h3>Important invariant</h3>
 * <p>This class NEVER calls {@code emitted.remove()}. Once a block is marked
 * emitted, it stays emitted. If {@code emitRegion} encounters an already-
 * emitted block, it emits a {@code goto L_XXX;} instead of recursing. This
 * prevents infinite recursion on irreducible control flow.
 */
public class ControlFlowStructurer {

    // ── Fields ──

    /** Source IR instructions (Stage 3 output). */
    private final List<IrInsn> instructions;

    /** Basic blocks identified by leader analysis. */
    private List<Block> blocks;

    /** Quick lookup: start address -> Block. */
    private Map<Long, Block> blockByStart;

    /** Quick lookup: instruction address -> index in {@link #instructions}. */
    private Map<Long, Integer> addrToIdx;

    /** Structured pseudo-C output lines (populated by {@link #structure()}). */
    private List<String> output;

    /** Addresses of blocks that have already been emitted. */
    private Set<Long> emitted;

    /** v4.9: 空块重定向 — 无指令块的目标地址 → fall-through 后继 (goto 合理化). */
    private Map<Long, Long> emptyRedirect;

    /**
     * Stack of innermost loop exit addresses, for {@code goto}→{@code break}
     * simplification. Pushed by {@link #emitWhileLoop} and {@link #emitDoWhile}.
     */
    private java.util.ArrayDeque<Long> loopExitStack;

    /** v4.1: 当前活动 while/do-while 循环头地址栈 (latch 回边目标比对用). */
    private java.util.ArrayDeque<Long> whileHeadStack;

    /** v4.1: 块起始地址 → 该块第一行在 output 中的索引 (goto 标签回填用 / BlockPseudoCProvider 切分用). */
    private Map<Long, Integer> blockFirstLine;

    /** 获取块起始地址→输出行索引映射 (structure() 后有效). */
    public Map<Long, Integer> getBlockFirstLine() {
        return blockFirstLine != null ? blockFirstLine : new HashMap<Long, Integer>();
    }

    /** Maximum recursion depth to prevent stack overflow on pathological CFGs. */
    private static final int MAX_DEPTH = 50;

    // ── v3.9: native CFG 融合 (native 为主, 自建为辅) ──

    /** native 层控制流图 (可选; null = 纯自建路径). */
    private ControlFlowAnalyzer.CFG nativeCfg;

    /** 支配树 (融合后块图构建); 失败为 null → emitRegion 回退地址近似判定. */
    private DominatorTree domTree;

    /** v4.5: 后支配树 (if-else 汇合点 / 循环出口判定). */
    private PostDominatorTree postDomTree;

    /** v3.9: 跳转表解析结果 (jumpAddr → JumpTable), 来自 ctx (Stage 4b2). */
    private Map<Long, JumpTableResolver.JumpTable> jumpTables =
            java.util.Collections.emptyMap();

    /** native 块类型 (ControlFlowAnalyzer BasicBlockType, JNI 契约). */
    private static final int NBB_NORMAL = 0;
    private static final int NBB_ENTRY = 1;
    private static final int NBB_EXIT = 2;
    private static final int NBB_CONDITIONAL = 3;
    private static final int NBB_UNCONDITIONAL = 4;
    private static final int NBB_CALL = 5;
    private static final int NBB_RETURN = 6;

    // ── Constructor ──

    /**
     * v3.5: 符号表上下文 (可选) — 用于 outbounds jump 符号化.
     */
    private final DecompContext ctx;

    /**
     * Create a structurer for the given IR instruction list.
     *
     * @param instructions IR instructions with control-flow metadata
     *                     (produced by Stage 3 handler)
     */
    public ControlFlowStructurer(List<IrInsn> instructions) {
        this(instructions, null);
    }

    /**
     * Create a structurer with symbol-table context (v3.5).
     *
     * @param instructions IR instructions with control-flow metadata
     * @param ctx          decompilation context (may be null)
     */
    public ControlFlowStructurer(List<IrInsn> instructions, DecompContext ctx) {
        this.instructions = instructions != null ? instructions : new ArrayList<IrInsn>();
        this.ctx = ctx;
    }

    /**
     * v3.9: 带 native CFG 的构造 (native 为主, 自建为辅).
     *
     * @param instructions IR instructions with control-flow metadata
     * @param ctx          decompilation context (may be null)
     * @param nativeCfg    native 层控制流分析结果 (可为 null, 完全走自建路径)
     */
    public ControlFlowStructurer(List<IrInsn> instructions, DecompContext ctx,
                                 ControlFlowAnalyzer.CFG nativeCfg) {
        this(instructions, ctx);
        this.nativeCfg = nativeCfg;
    }

    // ── Public API ──

    /**
     * Run the full structuring pipeline and populate {@link #output}.
     * <p>After calling this method, use {@link #getOutput()} to retrieve the
     * structured pseudo-C lines.
     */
    public void structure() {
        output = new ArrayList<>();
        emitted = new HashSet<>();
        loopExitStack = new java.util.ArrayDeque<Long>();
        whileHeadStack = new java.util.ArrayDeque<Long>();
        blockFirstLine = new HashMap<>();
        blocks = new ArrayList<>();
        blockByStart = new HashMap<>();
        addrToIdx = new HashMap<>();

        if (instructions.isEmpty()) {
            return;
        }

        // Build address-to-index map
        for (int i = 0; i < instructions.size(); i++) {
            addrToIdx.put(instructions.get(i).addr, i);
        }

        // v3.9: 跳转表来自 ctx (Stage 4b2 填充)
        if (ctx != null && ctx.jumpTables != null && !ctx.jumpTables.isEmpty()) {
            jumpTables = ctx.jumpTables;
        }

        identifyBlocks();
        buildBlockGraph();
        // v4.4: 结构化前可达性分析 — 从入口沿 succs BFS, 删不可达块。
        // 不可达块 (数据区被当指令的垃圾块/悬空代码) 不再进入发射,
        // 消除尾部死代码与悬空 goto 的来源。
        removeUnreachableBlocks();
        // v4.0: 块内 IR 优化 (常量/拷贝传播/死赋值消除/内存自写消除) —
        // 在支配树与发射前对每条指令的 IR 做安全数据流优化
        IrOptimizer.optimizeBlocks(blocks);
        buildDomTree(); // v3.9: 支配树 (回边判定), 失败静默回退地址近似
        buildPostDomTree(); // v4.5: 后支配树 (汇合点/循环出口)
        buildEmptyRedirect(); // v4.9: 空块 → fall-through 重定向 (goto 合理化)

        if (!blocks.isEmpty()) {
            long firstBlock = blocks.get(0).startAddr;
            // v4.5: 区域边界从「地址区间」升级为「块集合」——
            // 循环体/if 分支的边界由支配/后支配闭包决定, 消灭地址交错
            // 导致的假截断 (0x1cfc 交错循环类问题).
            Set<Long> all = new HashSet<>();
            for (Block blk : blocks) {
                all.add(blk.startAddr);
            }
            emitRegion(firstBlock, all, 0);
            emitRemainingBlocks(); // v3.5: 尾随 pass — 保证 0 丢块
            backfillGotoLabels(); // v4.1: 回边/跨分支 goto 的缺失标签回填
            // v4.7: 复合条件折叠 (ruleBlockOr) — 相邻同目标 if-goto 合并为 ||
            output = foldBlockOr(output);
        }
    }

    /**
     * Get the structured output. Call {@link #structure()} first.
     *
     * @return list of pseudo-C lines, or empty list if structure() not called
     */
    public List<String> getOutput() {
        List<String> out = output != null ? output : new ArrayList<String>();
        // v3.8: 悬空 goto 兜底 — 旧架构 emitRegion 中不可达死代码块的 goto
        // 指向主路径已 emit 且无 label 的块; 在输出末尾补发缺失 label,
        // 避免生成不可编译的 "goto L_xxx;" (L_xxx 未定义)。
        Set<String> gotoRefs = new LinkedHashSet<>();
        Set<String> defined = new HashSet<>();
        for (String line : out) {
            String t = line.trim();
            int gi = t.indexOf("goto L_");
            if (gi >= 0 && t.endsWith(";")) {
                // v4.1: 兼容 if (cond) goto L_xxx; 形式的条件回边
                gotoRefs.add(t.substring(gi + 5, t.length() - 1).trim());
            } else if (t.endsWith(":")) {
                defined.add(t.substring(0, t.length() - 1).trim());
            }
        }
        List<String> result = new ArrayList<>(out);
        for (String target : gotoRefs) {
            if (!defined.contains(target)) {
                result.add(target + ":;");
            }
        }
        // ── v4.0: 文本清理 pass ──
        // 1) 尾 goto 消除: "goto X;" 后紧跟 "X:" (跳过空行) → 删除 goto 行
        //    (执行流自然落进目标标签, 语义等价)
        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);
            if (line == null) continue;
            String t = line.trim();
            if (!(t.startsWith("goto ") && t.endsWith(";"))) continue;
            String target = t.substring(5, t.length() - 1).trim();
            int j = i + 1;
            // v4.1: 跳过空行与注释行 (补发块之间常夹 // 或 /* */ 注释)
            while (j < result.size() && (result.get(j) == null
                    || result.get(j).trim().isEmpty()
                    || result.get(j).trim().startsWith("//")
                    || result.get(j).trim().startsWith("/*"))) j++;
            if (j < result.size()) {
                String nxt = result.get(j).trim();
                if (nxt.equals(target + ":") || nxt.equals(target + ":;")) {
                    result.set(i, null);
                }
            }
        }
        // 2) 空标签合并: 无 goto 引用的标签行 → 删
        //    (v4.1: 引用收集匹配 if (cond) goto L_xxx;, 尾 goto 消除留下的
        //    孤立标签会被清理)
        Set<String> refs = new HashSet<>();
        for (String line : result) {
            if (line == null) continue;
            String t = line.trim();
            int gi = t.indexOf("goto L_");
            if (gi >= 0 && t.endsWith(";")) {
                refs.add(t.substring(gi + 5, t.length() - 1).trim());
            }
        }
        if (Boolean.getBoolean("cfs.dbg4")) System.err.println("DBG refs=" + refs);
        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);
            if (line == null) continue;
            String t = line.trim();
            String name = null;
            if (t.endsWith(":")) {
                name = t.substring(0, t.length() - 1).trim();
            } else if (t.endsWith(":;")) {
                name = t.substring(0, t.length() - 2).trim();
            }
            if (name == null || refs.contains(name)) continue;
            // v4.1: 无任何 goto 引用即死标签, 直接删 (后随内容不受影响)
            result.set(i, null);
        }
        // 3) 双分号清理: 非 for 行内的 ";;" → ";" (for(;;) 合法需保留)
        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);
            if (line == null) continue;
            if (line.contains("for") || line.contains("FOR")) continue;
            String prev;
            do {
                prev = line;
                line = line.replace(";;", ";");
            } while (!line.equals(prev));
            result.set(i, line);
        }
        List<String> cleaned = new ArrayList<>(result.size());
        for (String line : result) {
            if (line != null) cleaned.add(line);
        }
        return cleaned;
    }

    // ── Stage 4a: Block identification ──

    /**
     * Identify basic blocks using leader analysis.
     * <p>Leaders are:
     * <ul>
     *   <li>The first instruction</li>
     *   <li>Jump targets (addresses branched to)</li>
     *   <li>Post-branch (instruction immediately after a branch)</li>
     *   <li>Post-return (instruction immediately after a return)</li>
     * </ul>
     * Instructions between consecutive leaders form a single basic block.
     */
    private void identifyBlocks() {
        if (instructions.isEmpty()) return;

        Set<Long> leaders = new HashSet<>();
        // First instruction is always a leader
        leaders.add(instructions.get(0).addr);

        for (int i = 0; i < instructions.size(); i++) {
            IrInsn insn = instructions.get(i);
            if (insn.isBranch || insn.isReturn) {
                // Post-branch / post-return: next instruction is a leader
                if (i + 1 < instructions.size()) {
                    leaders.add(instructions.get(i + 1).addr);
                }
                // Jump target is a leader
                if (insn.jumpTarget > 0) {
                    leaders.add(insn.jumpTarget);
                }
            }
        }

        // v3.9: native 为主 — native 可达块起点并入 leaders.
        //   (native 递归下降识别的块边界含间接跳转/跳转表目标, 比自建 leader 更全;
        //    不在指令流的地址由下方 addrToIdx 检查跳过)
        if (nativeCfg != null && nativeCfg.blocks.size() > 1) {
            for (ControlFlowAnalyzer.Block nb : nativeCfg.blocks) {
                if (nb.startAddr > 0) leaders.add(nb.startAddr);
            }
        }

        // v3.9: 跳转表目标并入 leaders (switch case 块必须被分割/发射)
        for (JumpTableResolver.JumpTable jt : jumpTables.values()) {
            for (Long t : jt.targets) {
                if (t != null && t > 0) leaders.add(t);
            }
        }

        // Sort leaders by address (natural order)
        List<Long> sortedLeaders = new ArrayList<>(leaders);
        sortedLeaders.sort(null);

        // Split instructions into blocks
        blocks = new ArrayList<>();
        blockByStart = new HashMap<>();
        int n = instructions.size();
        for (long leader : sortedLeaders) {
            Integer startIdx = addrToIdx.get(leader);
            if (startIdx == null) continue; // leader not in instruction stream
            Block block = new Block(leader);
            for (int k = startIdx; k < n; k++) {
                IrInsn insn = instructions.get(k);
                if (k > startIdx && leaders.contains(insn.addr)) {
                    break; // hit next leader
                }
                block.insns.add(insn);
            }
            if (block.insns.isEmpty()) continue;
            block.endAddr = block.insns.get(block.insns.size() - 1).addr;
            blocks.add(block);
            blockByStart.put(leader, block);
        }
    }

    /**
     * v4.4: 结构化前可达性分析 — 从入口沿 succs BFS 收集可达块,
     * 删除不可达块并重建 preds (块图其余部分不变)。
     * <p>不可达块来源: 数据区被当指令 (符号 size 边界包进函数的 .rodata),
     * 函数尾部垃圾解码, 以及被不可达分支孤立的代码。删除后 emitRemainingBlocks
     * 不再需要发射它们, 尾部死代码与悬空 goto 从源头消失。
     */
    private void removeUnreachableBlocks() {
        if (blocks.isEmpty()) return;
        Set<Long> reachable = new HashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        long root = blocks.get(0).startAddr;
        reachable.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            Block cb = blockByStart.get(cur);
            if (cb == null) continue;
            for (long s : cb.succs) {
                if (s > 0 && reachable.add(s)) queue.add(s);
            }
        }
        List<Block> kept = new ArrayList<>();
        for (Block b : blocks) {
            if (reachable.contains(b.startAddr)) kept.add(b);
        }
        if (kept.size() == blocks.size()) return; // 全可达, 无变化
        blocks = kept;
        blockByStart = new HashMap<>();
        for (Block b : blocks) {
            blockByStart.put(b.startAddr, b);
        }
        for (Block b : blocks) {
            b.preds.clear();
        }
        for (Block b : blocks) {
            for (long succAddr : b.succs) {
                Block succBlock = blockByStart.get(succAddr);
                if (succBlock != null) {
                    succBlock.preds.add(b.startAddr);
                }
            }
        }
    }

    // ── Stage 4b: Block graph construction ──

    /**
     * Build the control-flow graph: compute fall-through, jump targets,
     * predecessors/successors, and branch classification for each block.
     */
    private void buildBlockGraph() {
        int n = instructions.size();
        // v3.9: native 为主 — 建立 native 块/边索引 (仅当 native CFG 非退化)
        Map<Long, ControlFlowAnalyzer.Block> nativeByStart = null;
        Map<Long, List<Long>> nativeSuccs = null;
        if (nativeCfg != null && nativeCfg.blocks.size() > 1) {
            nativeByStart = new HashMap<>();
            nativeSuccs = new HashMap<>();
            for (ControlFlowAnalyzer.Block nb : nativeCfg.blocks) {
                nativeByStart.put(nb.startAddr, nb);
                List<Long> out = new ArrayList<>();
                for (ControlFlowAnalyzer.Edge e : nb.successors) {
                    if (e.to > 0 && !out.contains(e.to)) out.add(e.to);
                }
                nativeSuccs.put(nb.startAddr, out);
            }
        }

        for (Block b : blocks) {
            if (b.insns.isEmpty()) continue;
            IrInsn last = b.insns.get(b.insns.size() - 1);
            ControlFlowAnalyzer.Block nb =
                    nativeByStart != null ? nativeByStart.get(b.startAddr) : null;

            if (nb != null) {
                // ── v3.9: native 为主 — 块字段直接来自 native (语义已对齐) ──
                b.jumpTarget = nb.branchTarget;
                b.isCondBranch = nb.blockType == NBB_CONDITIONAL;
                b.isUncondBranch = nb.blockType == NBB_UNCONDITIONAL;
                b.isReturn = nb.blockType == NBB_RETURN;
                b.isNativeLoopHeader = nb.isLoopHeader;

                if (b.isCondBranch) {
                    // 条件分支: native 的 fallThrough 是"假分支"目标 (精确)
                    b.fallThrough = nb.fallThrough;
                } else if (nb.blockType == NBB_EXIT) {
                    // 出口块 (间接跳转/外部跳转): 无 fall-through;
                    // v3.9: 跳转表块例外 — 表外索引硬件上顺序下一条 (default)
                    if (jumpTables.containsKey(b.endAddr)) {
                        Integer lastIdx = addrToIdx.get(last.addr);
                        if (lastIdx != null && lastIdx + 1 < n) {
                            b.fallThrough = instructions.get(lastIdx + 1).addr;
                        }
                    } else {
                        b.fallThrough = 0;
                    }
                } else {
                    Integer lastIdx = addrToIdx.get(last.addr);
                    if (lastIdx != null && !b.isReturn && !b.isUncondBranch
                            && lastIdx + 1 < n) {
                        b.fallThrough = instructions.get(lastIdx + 1).addr;
                    }
                }

                // 边: native edges 为主
                List<Long> nsuccs = nativeSuccs.get(b.startAddr);
                if (nsuccs != null) {
                    b.succs.addAll(nsuccs);
                }
                // 补充自建缺失目标 (native 标 EXIT 但目标在图内的情况)
                if (b.jumpTarget > 0 && !b.succs.contains(b.jumpTarget)) {
                    b.succs.add(b.jumpTarget);
                }
                if (b.fallThrough > 0 && !b.succs.contains(b.fallThrough)) {
                    b.succs.add(b.fallThrough);
                }
                // v3.9: 跳转表目标 (native EXIT 块无 succs, 表目标需显式并入)
                addTableSuccs(b);
            } else {
                // ── 自建兜底: native 未覆盖的块 (不可达死代码等) ──
                b.jumpTarget = last.jumpTarget;
                b.isCondBranch = last.isCondBranch;
                b.isUncondBranch = last.isUncondBranch;
                b.isReturn = last.isReturn;

                Integer lastIdx = addrToIdx.get(last.addr);
                // v4.1: 条件返回 (popeq/bxeq 等) 保留 fall-through —
                // 它后面仍有可达代码, 不能被当无条件 return 截断.
                boolean condReturn = b.isReturn && b.isCondBranch;
                if (lastIdx != null && (!b.isReturn || condReturn)
                        && !b.isUncondBranch && lastIdx + 1 < n) {
                    b.fallThrough = instructions.get(lastIdx + 1).addr;
                }

                if (b.isReturn) {
                    // v4.4: 条件返回 (popeq/bxeq) 的 fall-through 也是后继 —
                    // 否则可达性分析 (removeUnreachableBlocks) 会误删它后面的块。
                    if (condReturn && b.fallThrough > 0) {
                        b.succs.add(b.fallThrough);
                    }
                } else if (b.isUncondBranch) {
                    if (b.jumpTarget > 0) {
                        b.succs.add(b.jumpTarget);
                    }
                    // v3.9: 跳转表 (tbb/tbh/br) — jumpTarget 为 0, 目标来自表;
                    //       表外索引硬件上顺序下一条 → fallThrough (default 分支)
                    if (jumpTables.containsKey(b.endAddr)) {
                        Integer li = addrToIdx.get(last.addr);
                        if (li != null && li + 1 < n) {
                            b.fallThrough = instructions.get(li + 1).addr;
                        }
                    }
                    // v3.9: 跳转表 (tbb/tbh/br) — jumpTarget 为 0, 目标来自表
                    addTableSuccs(b);
                } else if (b.isCondBranch) {
                    if (b.jumpTarget > 0) {
                        b.succs.add(b.jumpTarget);
                    }
                    if (b.fallThrough > 0) {
                        b.succs.add(b.fallThrough);
                    }
                } else {
                    // Normal block: falls through
                    if (b.fallThrough > 0) {
                        b.succs.add(b.fallThrough);
                    }
                }
            }
        }

        // Build predecessor lists
        for (Block b : blocks) {
            for (long succAddr : b.succs) {
                Block succBlock = blockByStart.get(succAddr);
                if (succBlock != null) {
                    succBlock.preds.add(b.startAddr);
                }
            }
        }
    }

    /**
     * v3.9: 把跳转表目标并入块的 succs (供图构建), 并记录 tableTargets
     * (供 emitRegion 输出 if-goto 链)。跳转指令地址 = 块尾指令地址。
     */
    private void addTableSuccs(Block b) {
        JumpTableResolver.JumpTable jt = jumpTables.get(b.endAddr);
        if (jt == null) return;
        b.tableTargets = jt.targets;
        b.tableIndexReg = jt.indexReg;
        for (Long t : jt.targets) {
            if (t != null && t > 0 && !b.succs.contains(t)) {
                b.succs.add(t);
            }
        }
    }

    // ── Stage 4c: Region emission (recursive descent) ──

    /**
     * Emit a region of code starting at {@code startAddr}, stopping when
     * {@code currentAddr >= stopAddr} or when a terminator is encountered.
     * <p>This is the core structuring method. It detects:
     * <ul>
     *   <li><b>Back-edges</b> (loop latches / do-while patterns)</li>
     *   <li><b>Loop headers</b> (while loops with forward-exit branches)</li>
     *   <li><b>If/else</b> (forward conditional branches)</li>
     *   <li><b>Normal blocks</b> (straight-line code)</li>
     * </ul>
     *
     * @param startAddr address of the first block to emit
     * @param stopAddr  stop emitting when currentAddr reaches this value
     * @param depth     current nesting depth (for indentation and recursion guard)
     */
    /**
     * Emit the region of blocks reachable from {@code startAddr}.
     * <p>v4.5: 区域边界从「地址上限 stopAddr」升级为「块集合 region」——
     * 只有 region 内的块才会被发射; 遇到 region 外块 (循环出口/汇合点/旁路)
     * 即停止或发射 goto。区域由调用方按支配/后支配闭包构造, 不再依赖
     * 地址单调性, 交错循环 (循环体地址跨越外层跳转目标) 不再被截断。
     *
     * @param startAddr 发射起点 (必须是 region 内块)
     * @param region    允许发射的块起始地址集合
     * @param depth     当前缩进深度
     */
    private void emitRegion(long startAddr, Set<Long> region, int depth) {
        if (depth > MAX_DEPTH) {
            emitLine(depth, "/* recursion depth exceeded, truncated */");
            return;
        }

        long currentAddr = startAddr;
        while (currentAddr > 0 && region.contains(currentAddr)) {
            Block b = blockByStart.get(currentAddr);
            if (b == null) {
                // Unknown block — address not a leader
                emitLine(depth, "/* unknown block 0x" + Long.toHexString(currentAddr) + " */");
                // v3.5: 不丢代码 — 跳到下一个已知块继续发射 (fallthrough)
                long next = nextBlockStartAfter(currentAddr);
                if (next > 0 && region.contains(next)) {
                    currentAddr = next;
                    continue;
                }
                break;
            }

            // Already emitted? Emit goto instead of recursing (prevents infinite recursion)
            if (emitted.contains(currentAddr)) {
                // Tail-goto simplification: if the target block is a "tail"
                // block (ends in return / is the last block of the function),
                // emit return; instead of goto. If the target is the current
                // innermost loop's exit, emit break; instead.
                String simplified = simplifyTailGoto(currentAddr, depth);
                if (simplified != null) {
                    emitLine(depth, simplified);
                } else {
                    emitLine(depth, "goto " + label(currentAddr) + ";");
                }
                break;
            }
            emitted.add(currentAddr);

            // r2dec: _set_outbounds_jump — a branch to an address not present in
            // the instruction list is a function-pointer call (tail call when it
            // is the last instruction of the function).
            if (b.jumpTarget > 0 && !addrToIdx.containsKey(b.jumpTarget)) {
                boolean fallsThrough = emitOutboundsJump(b, depth);
                if (fallsThrough && b.fallThrough > 0 && region.contains(b.fallThrough)) {
                    currentAddr = b.fallThrough;
                    continue;
                }
                break;
            }

            // ── Check back-edge (支配树回边判定, 退化时地址近似) ──
            if (isBackEdge(b)) {
                if (b.jumpTarget == currentAddr) {
                    // Self-loop: r2dec _set_loops distinguishes an empty-body
                    // spin (whileInline → "while (cond);") from a body-carrying
                    // loop (do { ... } while (cond);).
                    if (hasBodyCode(b)) {
                        emitDoWhile(b, depth);
                    } else {
                        emitWhileInline(b, depth);
                    }
                    // Continue after the loop
                    if (b.isCondBranch && b.fallThrough > 0 && region.contains(b.fallThrough)) {
                        currentAddr = b.fallThrough;
                        continue;
                    }
                    break;
                }
                if (emitted.contains(b.jumpTarget)) {
                    // While-loop latch: the back-edge target was already emitted
                    // as a loop header. Emit the latch body (suppress branch).
                    // v4.1: 仅当回边目标是当前 while 头才可抑制 — 回边到循环
                    // 体内其它块 (内层 do-while 头) 必须输出条件 goto, 否则回边
                    // 语义丢失 (假 while / 代码缺失).
                    boolean toCurrentHead = whileHeadStack != null
                            && !whileHeadStack.isEmpty()
                            && whileHeadStack.peek() == b.jumpTarget;
                    if (toCurrentHead) {
                        emitBlockInstructions(b, depth, true);
                        if (b.isCondBranch) {
                            currentAddr = b.fallThrough;
                            continue;
                        } else {
                            break;
                        }
                    }
                    emitBlockInstructions(b, depth, true);
                    IrInsn latchInsn = b.insns.get(b.insns.size() - 1);
                    if (b.isCondBranch) {
                        IrNode latchCond = makeCond(latchInsn, false);
                        emitLine(depth, "if (" + latchCond.toC() + ") goto "
                                + label(b.jumpTarget) + ";");
                        currentAddr = b.fallThrough;
                        continue;
                    } else {
                        emitLine(depth, "goto " + label(b.jumpTarget) + ";");
                        break;
                    }
                } else {
                    // Do-while pattern: back-edge target not yet emitted
                    emitDoWhile(b, depth);
                    if (b.isCondBranch && b.fallThrough > 0 && region.contains(b.fallThrough)) {
                        currentAddr = b.fallThrough;
                        continue;
                    }
                    break;
                }
            }

            // ── Check loop header (回边前驱 / native 循环头 + 前向出口分支) ──
            // v4.2: 假 while 修复 — while 发射要求至少一个自建回边 latch 落在
            // 循环体区间 [fallThrough, jumpTarget) 内; 若所有回边前驱都在
            // loopExit 之后 (循环体跨越本块跳转目标, 如 JNI_OnLoad 0x1b4c 的
            // 回边 0x1d30/0x1d48 位于 else 分支 0x1cd0 之后), while 体永远收
            // 不到回跳 → 假循环。此时降级为 if-else + 后续 goto label (对齐
            // 正版 r2dec)。native loop header (isNativeLoopHeader) 语义由
            // native 边分析保证, 不在此约束内。
            boolean hasBackEdge = hasBackEdgePred(b, currentAddr);
            if (hasBackEdge || b.isNativeLoopHeader) {
                if (b.isCondBranch && b.jumpTarget > currentAddr) {
                    // v4.5: 循环出口 = header 的后支配者 (所有 header 出发路径
                    // 必经的块), 不再用地址 jumpTarget 近似; 循环体 = header
                    // 支配闭包。不可约/无后支配出口时降级 if-else (回边由
                    // 后续 emitRegion 的 goto label 兜底, 语义保持).
                    long loopExit = postDomExit(b.startAddr);
                    if (loopExit > 0) {
                        emitWhileLoop(b, loopExit, depth);
                        currentAddr = loopExit;
                        continue;
                    }
                    // v4.6: 条件头无后支配出口 → 尝试 do-while 折叠
                    long exit = tryEmitDoWhile(b, depth);
                    if (exit >= 0) {
                        if (exit > 0) {
                            currentAddr = exit;
                            continue;
                        }
                        break; // 无限循环, 本区域终止
                    }
                } else if (hasBackEdge) {
                    // v4.6: 普通块循环头 (回边 latch 跳回本块) → do-while 折叠。
                    // 典型: `if (cond) { L_xxx: ...body...; goto L_xxx; }` ——
                    // latch 被 header 支配且无旁路入口时为纯无限循环,
                    // 输出 do { ... } while (1); 吸收回边 goto。
                    // latch 有旁路 (多入口, 不可约) 时放弃 → 保持 label+goto。
                    long exit = tryEmitDoWhile(b, depth);
                    if (exit >= 0) {
                        if (exit > 0) {
                            currentAddr = exit;
                            continue;
                        }
                        break; // 无限循环, 本区域终止
                    }
                }
            }

            // ── Check if/else (forward conditional branch) ──
            if (b.isCondBranch && b.jumpTarget > currentAddr) {
                long mergePoint = emitIfElse(b, region, depth);
                currentAddr = mergePoint;
                continue;
            }

            // ── Normal block: emit instructions and follow control flow ──
            emitBlockInstructions(b, depth, false);

            // v4.8: 向后条件分支 (不可约回边/已发射目标) — 保留条件输出
            // if (cond) goto L_xxx;, 不再吞条件。原逻辑: isBackEdge 判定
            // 失败 (latch 不 dom 头, 不可约) 时该分支无 goto 输出, 条件
            // 跳转消失, 后续仅剩无条件 b 的 goto → 条件语义丢失。
            if (b.isCondBranch && !b.isReturn && !b.isUncondBranch) {
                long target = b.jumpTarget;
                if (target > 0 && (emitted.contains(target) || target < currentAddr)) {
                    IrInsn last = b.insns.get(b.insns.size() - 1);
                    IrNode cond = makeCond(last, false);
                    emitLine(depth, "if (" + cond.toC() + ") goto "
                            + label(target) + ";");
                    currentAddr = b.fallThrough;
                    continue;
                }
            }

            // v4.1: 条件返回 (popeq/bxeq 等) 不是终结 — 后续 fall-through
            // 仍可达, 不能 break (否则循环体/回边代码丢失).
            if (b.isReturn && !b.isCondBranch) {
                break;
            }
            // v3.9: 跳转表结尾 — 输出 if (idx == i) goto 链, 然后沿 fallThrough 继续
            if (b.tableTargets != null && !b.tableTargets.isEmpty()) {
                emitSwitchChain(b, depth);
                currentAddr = b.fallThrough;
                continue;
            }
            if (b.isUncondBranch) {
                if (b.jumpTarget <= 0 || !region.contains(b.jumpTarget)) {
                    // v4.9: 跳转目标超出当前区域 (跳转板) — 显式 goto,
                    // 否则该分支路径在结构化输出中丢失 (空 then/悬空标签)。
                    // 例: `if (c) { L_x: }` 中 L_x = mvn+b #out, 原实现静默
                    // 吸收 → 条件为真路径消失, goto L_x 指向空标签。
                    if (b.jumpTarget > 0) {
                        emitLine(depth, "goto " + label(b.jumpTarget) + ";");
                    }
                    break;
                }
                currentAddr = b.jumpTarget;
                continue;
            }
            // Fall through to next block
            currentAddr = b.fallThrough;
        }
    }

    /**
     * v3.9: 输出跳转表 if-goto 链 (与 SwitchCasePass 的 CMP_GOTO 风格一致):
     * <pre>
     *   // switch table @ 0x... (index w8)
     *   if (w8 == 0) goto L_0x...;
     *   if (w8 == 1) goto L_0x...;
     * </pre>
     * 表外索引 (default) 硬件上顺序下一条 — emitRegion 已沿 fallThrough 顺序
     * 发射, 无需 goto。表目标块由 emitRemainingBlocks 兜底发射 (goto 有定义)。
     */
    private void emitSwitchChain(Block b, int depth) {
        JumpTableResolver.JumpTable jt = jumpTables.get(b.endAddr);
        if (jt == null || jt.targets == null || jt.targets.isEmpty()) return;
        emitLine(depth, "// switch table @ 0x" + Long.toHexString(jt.jumpAddr)
                + " (index " + jt.indexReg + ", " + jt.targets.size() + " cases)");
        for (int i = 0; i < jt.targets.size(); i++) {
            Long t = jt.targets.get(i);
            if (t == null || t <= 0) continue;
            emitLine(depth, "if (" + jt.indexReg + " == " + i + ") goto " + label(t) + ";");
        }
    }

    /**
     * Emit an outbounds jump as a function-pointer call.
     * <p>Ported from r2dec's {@code _set_outbounds_jump}: a branch whose target
     * is not part of this function's instruction stream is treated as a call to
     * an external function. When the branch is the last instruction of the
     * function it is a tail call and becomes {@code return sub_XXXX();}.
     *
     * @param b     the block ending with the outbounds branch
     * @param depth current indentation depth
     * @return true if execution should continue at the block's fall-through
     *         (conditional branch that is not the last instruction)
     */
    private boolean emitOutboundsJump(Block b, int depth) {
        long target = b.jumpTarget;
        boolean isLast = !instructions.isEmpty()
                && b.endAddr == instructions.get(instructions.size() - 1).addr;
        String callText = resolveOutboundName(b);

        if (isLast) {
            // Tail call: r2dec wraps the call in a return.
            emitLine(depth, "return " + callText + ";");
            if (b.isCondBranch) {
                emitLine(depth, "/* r2dec: conditional outbounds jump emitted as return; check the disassembly */");
            }
            return false;
        }
        if (b.isCondBranch) {
            // Conditional external jump → if (cond) { fcn(...); }
            // Branch condition is not inverted: taken = call the external fcn.
            IrInsn lastInsn = b.insns.get(b.insns.size() - 1);
            IrNode cond = makeCond(lastInsn, false);
            emitLine(depth, "if (" + cond.toC() + ") {");
            emitLine(depth + 1, callText + ";");
            emitLine(depth, "}");
            return true;
        }
        // Unconditional external jump (not last): plain call; the code after
        // it is unreachable.
        emitLine(depth, callText + ";");
        return false;
    }

    /**
     * v4.6: outbounds jump 符号化 — 优先用最后指令已解析的 callee,
     * 否则查符号表; 都失败时回退 sub_<hex>. 统一带空括号保持调用语义
     * (emitOutboundsJump 以调用语句/尾调用发射, 裸函数名是无效 C).
     */
    private String resolveOutboundName(Block b) {
        IrInsn last = b.insns.get(b.insns.size() - 1);
        if (last != null && last.callee != null && !last.callee.isEmpty()) {
            return last.callee + "()";
        }
        long target = b.jumpTarget;
        if (ctx != null && target > 0) {
            String name = GlobalVarResolver.resolveName(target, ctx);
            if (name != null) {
                return GlobalVarResolver.sanitizeFuncName(
                        GlobalVarResolver.demangle(name, ctx)) + "()";
            }
        }
        return "sub_" + Long.toHexString(target) + "()";
    }

    /**
     * Emit an empty-body self-loop as a single-line spin.
     * <p>Ported from r2dec's {@code Scope.whileInline}: a self-loop whose body
     * carries no IR (e.g. a bare {@code cbz x0, .} busy-wait) becomes
     * {@code while (cond);} instead of a multi-line empty do-while.
     *
     * @param b     the self-loop block (branch-only body)
     * @param depth current indentation depth
     */
    private void emitWhileInline(Block b, int depth) {
        IrInsn lastInsn = b.insns.get(b.insns.size() - 1);
        if (b.isCondBranch) {
            // Branch taken = keep spinning → condition is NOT inverted.
            IrNode cond = makeCond(lastInsn, false);
            emitLine(depth, "while (" + cond.toC() + ");");
        } else {
            // Unconditional self-branch (e.g. "b ." panic loop) → while (1);
            emitLine(depth, "while (1);");
        }
    }

    /**
     * Check whether a block carries any real IR output besides its terminator
     * branch. Used to distinguish r2dec's whileInline (empty spin body) from a
     * do-while (body-carrying self-loop).
     *
     * @param b the block to inspect
     * @return true if at least one non-Nop, non-empty IR node exists before
     *         the block's last (branch) instruction
     */
    /**
     * v4.1: 回填缺失的 goto 目标标签。
     * <p>条件回边 (if (cond) goto L_xxx;) 与跨分支回边的目标块可能在主路径
     * 上已发射且无标签。这里在对应块的首行前插入标签行; 找不到块首行时
     * 留给 getOutput 的末尾兑底。
     */
    private void backfillGotoLabels() {
        if (output == null || output.isEmpty()) return;
        Set<Long> defined = new HashSet<>();
        for (String line : output) {
            String t = line.trim();
            if (t.endsWith(":") && !t.startsWith("//")) {
                Long addr = parseLabelAddr(t.substring(0, t.length() - 1).trim());
                if (addr != null) defined.add(addr);
            }
        }
        Set<Long> needed = new LinkedHashSet<>();
        for (String line : output) {
            int gi = line.indexOf("goto L_");
            while (gi >= 0) {
                int semi = line.indexOf(';', gi);
                if (semi > 0) {
                    Long addr = parseLabelAddr(line.substring(gi + 5, semi).trim());
                    if (addr != null && !defined.contains(addr)) needed.add(addr);
                }
                gi = line.indexOf("goto L_", gi + 1);
            }
        }
        if (needed.isEmpty()) return;
        // 倒序插入到块首行前 (行号稳定)
        List<long[]> inserts = new ArrayList<>();
        for (Long addr : needed) {
            Integer idx = blockFirstLine.get(addr);
            if (idx != null) inserts.add(new long[]{addr, idx});
        }
        inserts.sort((a, b) -> Long.compare(b[1], a[1]));
        for (long[] ins : inserts) {
            int idx = (int) ins[1];
            String indent = "";
            if (idx < output.size()) {
                String src = output.get(idx);
                int k = 0;
                while (k < src.length() && src.charAt(k) == ' ') k++;
                indent = src.substring(0, k);
            }
            output.add(idx, indent + label(ins[0]) + ":");
        }
    }

    /** v4.1: "L_xxxx" → 块地址; 解析失败返回 null. */
    private Long parseLabelAddr(String name) {
        if (name == null || !name.startsWith("L_")) return null;
        try {
            return Long.parseLong(name.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * v4.1: 删除无 goto 引用的孤立标签行。
     * <p>getOutput 阶段引用有效的标签, 可能被后续文本 pass (PeepholeOptimizer
     * 的 dead/tail goto 简化) 删掉引用后残留。R2DecPseudoC 全流程末尾调用。
     */
    public static List<String> removeOrphanLabels(List<String> in) {
        if (in == null || in.isEmpty()) return in;
        Set<String> refs = new HashSet<>();
        for (String line : in) {
            if (line == null) continue;
            String t = line.trim();
            int gi = t.indexOf("goto L_");
            if (gi >= 0 && t.endsWith(";")) {
                refs.add(t.substring(gi + 5, t.length() - 1).trim());
            }
        }
        List<String> out = new ArrayList<>(in.size());
        for (String line : in) {
            if (line == null) continue;
            String t = line.trim();
            String name = null;
            if (t.endsWith(":")) {
                name = t.substring(0, t.length() - 1).trim();
            } else if (t.endsWith(":;")) {
                name = t.substring(0, t.length() - 2).trim();
            }
            if (name != null && name.startsWith("L_")
                    && !refs.contains(name)) {
                continue; // 孤立标签: 无任何 goto 引用
            }
            out.add(line);
        }
        return out;
    }

    /**
     * v3.5: 尾随发射 pass — emitRegion 结束后仍有未发射块时补发 (带标签),
     * 保证 0 丢块 (不可达/非结构化边上的块也会出现在伪 C 里).
     * v4.0: 可达性过滤 — 从函数入口沿 succs (含跳转表目标) BFS, 只补发
     * 可达块; 已被 goto 引用的死块保留 (防悬空 goto).
     * v4.2: 分段递归结构化 — 未 emit 可达块按地址连续段用 emitRegion 递归
     * 发射 (得到 if/do-while 嵌套而非扁平列表), 段内跨出段外的边由
     * emitted 检查转 goto; 奇异交错剩余块走扁平兜底。
     */
    private void emitRemainingBlocks() {
        Set<Long> reachable = new HashSet<>();
        if (!blocks.isEmpty()) {
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            long root = blocks.get(0).startAddr;
            reachable.add(root);
            queue.add(root);
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                Block cb = blockByStart.get(cur);
                if (cb == null) continue;
                for (long s : cb.succs) {
                    if (s > 0 && reachable.add(s)) queue.add(s);
                }
            }
        }
        // 已被 emitRegion 的 goto 引用的块必须保留 (兜底语义: 0 悬空 goto)
        // v4.1: 匹配 if (cond) goto L_xxx; 形式的条件回边引用
        for (String line : output) {
            String t = line.trim();
            int gi = t.indexOf("goto L_");
            if (gi >= 0 && t.endsWith(";")) {
                String target = t.substring(gi + 5, t.length() - 1).trim();
                Long addr = labelAddr(target);
                if (addr != null) reachable.add(addr);
            }
        }

        // ── v4.2: 未 emit 可达块按地址分段, 每段递归 emitRegion ──
        List<Long> todo = new ArrayList<>();
        for (Block b : blocks) {
            if (!emitted.contains(b.startAddr) && reachable.contains(b.startAddr)) {
                todo.add(b.startAddr);
            }
        }
        todo.sort(null);
        List<long[]> segs = new ArrayList<>();
        long segStart = -1, segEnd = -1;
        for (long addr : todo) {
            Block b = blockByStart.get(addr);
            if (segStart < 0) {
                segStart = addr;
                segEnd = b.endAddr;
            } else if (addr <= segEnd) {
                segEnd = Math.max(segEnd, b.endAddr);
            } else {
                segs.add(new long[]{segStart, segEnd});
                segStart = addr;
                segEnd = b.endAddr;
            }
        }
        if (segStart >= 0) segs.add(new long[]{segStart, segEnd});

        boolean firstSeg = true;
        for (long[] seg : segs) {
            if (output.size() > 0 && !output.get(output.size() - 1).isEmpty()) {
                output.add("");
            }
            int segBefore = output.size();
            // v4.8: 兜底分段标记 — 这些块 CFG 可达但主 emitRegion 因不可约
            // 回边未结构化, 不是死代码; 仅首段注明, 避免误读为孤立语句.
            if (firstSeg) {
                output.add("// --- fallback blocks (irreducible CFG, not dead code) ---");
                firstSeg = false;
            }
            // v4.5: 段区域 = 段内块集合 (旧签名 seg[1]+4 地址上限)
            Set<Long> segRegion = new HashSet<>();
            for (Block b : blocks) {
                if (b.startAddr >= seg[0] && b.startAddr <= seg[1]) {
                    segRegion.add(b.startAddr);
                }
            }
            emitRegion(seg[0], segRegion, 1);
            // v4.8: 段未产出语句 (空块) — 撤掉注释与多余空行, 避免悬挂注释
            if (output.size() <= segBefore + 1) {
                while (output.size() > segBefore) output.remove(output.size() - 1);
                if (!output.isEmpty() && output.get(output.size() - 1).isEmpty()) {
                    output.remove(output.size() - 1);
                }
            }
        }

        // ── 扁平兜底: 递归后仍未 emit 的可达块 (奇异地址交错) ──
        boolean any = false;
        for (Block b : blocks) {
            if (emitted.contains(b.startAddr)) continue;
            if (!reachable.contains(b.startAddr)) continue; // v4.0: 死块跳过
            if (isInvisibleBlock(b)) {
                // v4.9: 不可见块 (全静默指令) — 标记跳过, 不发射悬挂标签
                // (goto 已由 emptyRedirect 重定向到实际后继)
                emitted.add(b.startAddr);
                continue;
            }
            if (!any) {
                if (output.size() > 0 && !output.get(output.size() - 1).isEmpty()) {
                    output.add("");
                }
                any = true;
            }
            emitted.add(b.startAddr);
            output.add(labelRaw(b.startAddr) + ":");
            emitBlockInstructions(b, 0, false);
            if (b.isReturn) {
                output.add("return;");
            } else if (b.isCondBranch) {
                // v4.1: 条件分支输出 if-goto (替代原 branch/fallthrough 注释,
                // 语义保留且输出干净)
                IrInsn last = b.insns.get(b.insns.size() - 1);
                IrNode cond = makeCond(last, false);
                output.add("if (" + cond.toC() + ") goto "
                        + label(b.jumpTarget) + ";");
            } else if (b.isUncondBranch) {
                output.add("goto " + label(b.jumpTarget) + ";");
            }
        }
    }

    /**
     * v4.7: 复合条件折叠 (ruleBlockOr) — 相邻同缩进、同目标的
     * {@code if (c1) goto L;} + {@code if (c2) goto L;} 折叠为
     * {@code if (c1 || c2) goto L;}。短路语义完全等价: c1 真时跳 L,
     * c2 不评估; c1 假才评估 c2。产生此类行的路径: emitRegion 的 latch
     * 分支发射 (if (cond) goto L_xxx;) 与 emitRemainingBlocks 扁平兜底
     * (条件分支块连续跳同一目标)。跳转表链 (emitSwitchChain) 目标各
     * 不相同, 天然不折叠。
     *
     * @param in 结构化输出行 (可含 null)
     * @return 折叠后的行列表 (null 已过滤)
     */
    public static List<String> foldBlockOr(List<String> in) {
        if (in == null || in.isEmpty()) return in;
        List<String> out = new ArrayList<>(in.size());
        int i = 0;
        while (i < in.size()) {
            String line = in.get(i);
            if (line == null) {
                i++;
                continue;
            }
            Matcher m = IF_GOTO.matcher(line);
            if (m.matches()) {
                String indent = m.group(1);
                String cond = m.group(2);
                String target = m.group(3);
                int j = i + 1;
                while (j < in.size()) {
                    String nxt = in.get(j);
                    if (nxt == null) {
                        j++;
                        continue;
                    }
                    Matcher nm = IF_GOTO.matcher(nxt);
                    if (nm.matches() && nm.group(1).equals(indent)
                            && nm.group(3).equals(target)) {
                        cond = cond + " || " + nm.group(2);
                        j++;
                    } else {
                        break;
                    }
                }
                out.add(indent + "if (" + cond + ") goto " + target + ";");
                i = j;
            } else {
                out.add(line);
                i++;
            }
        }
        return out;
    }

    private static final Pattern IF_GOTO = Pattern.compile(
            "^(\\s*)if \\((.*)\\) goto (L_[0-9a-f]+);$");

    /**
     * v4.9: 不可见块重定向 — 块内所有指令均静默 (无 IR 输出, 如对齐跳板
     * `b #target` 或纯数据占位) 时, 其 goto 引用重定向到实际后继:
     * 无条件跳转块 → jumpTarget; 否则 → fall-through。链式解析。
     */
    private void buildEmptyRedirect() {
        emptyRedirect = new HashMap<>();
        for (Block b : blocks) {
            if (!isInvisibleBlock(b)) continue;
            long target = 0;
            if (b.isUncondBranch && b.jumpTarget > 0) {
                target = b.jumpTarget;
            } else if (b.fallThrough != 0) {
                target = b.fallThrough;
            }
            if (target != 0) emptyRedirect.put(b.startAddr, target);
        }
        if (emptyRedirect.isEmpty()) return;
        for (Map.Entry<Long, Long> e : emptyRedirect.entrySet()) {
            long cur = e.getValue();
            Set<Long> seen = new HashSet<>();
            while (emptyRedirect.containsKey(cur) && seen.add(cur)) {
                cur = emptyRedirect.get(cur);
            }
            e.setValue(cur);
        }
    }

    /** v4.9: 块内指令全部静默 (nodes 为空/Nop 或 valid=false) → 输出不可见. */
    private boolean isInvisibleBlock(Block b) {
        if (b.insns.isEmpty()) return true;
        for (IrInsn insn : b.insns) {
            if (!insn.valid) continue;
            if (insn.nodes != null) {
                for (IrNode n : insn.nodes) {
                    if (n != null && !(n instanceof IrNode.Nop)) return false;
                }
            }
        }
        return true;
    }

    /** v4.0: label 名 → 块起始地址 (emitRemainingBlocks 可达性用). */
    private Long labelAddr(String name) {
        for (Block b : blocks) {
            if (labelRaw(b.startAddr).equals(name)) return b.startAddr;
        }
        return null;
    }

    /** v3.5: 找到地址大于 addr 的最近块起始 (未知块 fallthrough 用). */
    private long nextBlockStartAfter(long addr) {
        long best = -1;
        for (Long s : blockByStart.keySet()) {
            if (s > addr && (best < 0 || s < best)) best = s;
        }
        return best;
    }

    private boolean hasBodyCode(Block b) {
        int count = b.insns.size();
        for (int i = 0; i < count - 1; i++) {
            IrInsn insn = b.insns.get(i);
            if (insn == null || !insn.valid || insn.nodes == null) continue;
            for (int k = 0; k < insn.nodes.size(); k++) {
                IrNode node = insn.nodes.get(k);
                if (node == null || node instanceof IrNode.Nop) continue;
                String t = node.toC();
                if (t != null && !t.isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * Emit a do-while loop.
     * <p>The current block is the loop body that ends with a back-edge branch.
     * The branch is taken to continue the loop, so the while condition is
     * the branch condition (not inverted).
     *
     * @param b     the loop body block (ends with back-edge)
     * @param depth current indentation depth
     */
    private void emitDoWhile(Block b, int depth) {
        emitLine(depth, "do {");
        // The loop exits at b.fallThrough; push it so gotos targeting it
        // inside the body become break;.
        if (b.fallThrough > 0) loopExitStack.push(b.fallThrough);
        // v4.1: 循环头 = 回边目标 (latch 比对用)
        if (b.jumpTarget > 0) whileHeadStack.push(b.jumpTarget);
        // Emit instructions before the back-edge branch (suppress last insn)
        emitBlockInstructions(b, depth + 1, true);
        if (b.fallThrough > 0) loopExitStack.pop();
        if (b.jumpTarget > 0) whileHeadStack.pop();

        IrInsn lastInsn = b.insns.get(b.insns.size() - 1);
        if (b.isCondBranch) {
            // Branch taken = continue loop → condition not inverted
            IrNode cond = makeCond(lastInsn, false);
            emitLine(depth, "} while (" + cond.toC() + ");");
        } else {
            // Unconditional back-edge → infinite loop
            emitLine(depth, "} while (1);");
        }
    }

    /**
     * v4.6: do-while 图折叠 — 循环头 {@code b} (普通块或条件分支块) 有回边
     * latch 跳回, 且所有 latch 都被 {@code b} 支配 (单入口) 时, 输出
     * {@code do { ... } while (1);} / {@code do { ... } while (cond);} 吸收回边
     * goto。典型场景: {@code if (cond) { L_xxx: ...; goto L_xxx; }} 的纯无限
     * 循环 (JNI_OnLoad 0x1b4c / 0x1be8)。
     *
     * @param b     循环头块
     * @param depth 缩进深度
     * @return ≥0 折叠成功: 0=无限循环无出口 (调用方终止本区域);
     *         &gt;0=循环出口地址; -1=失败 (多入口/不可约, 保持 label+goto)
     */
    private long tryEmitDoWhile(Block b, int depth) {
        if (domTree == null) return -1;
        // 前置: 存在回边 latch 且全部被 b 支配 (无旁路入口 = 单入口)
        List<Long> latches = new ArrayList<>();
        for (int i = 0; i < b.preds.size(); i++) {
            long p = b.preds.get(i);
            if (isBackEdgeFrom(b, p)) {
                if (!domTree.dominates(b.startAddr, p)) return -1;
                latches.add(p);
            }
        }
        if (latches.isEmpty()) return -1;
        // 循环体 = b 支配闭包 (含 latch, 不含 b 自身 — header 单独发射)
        Set<Long> body = domClosure(b.startAddr, -1);
        body.remove(b.startAddr);
        for (long l : latches) {
            if (!body.contains(l)) return -1;
        }
        if (body.isEmpty()) return -1;

        if (b.isCondBranch) {
            // 条件分支头: 分支(cond true)跳 jumpTarget = 退出 → do { } while (!cond)
            emitBlockInstructions(b, depth, true); // 抑制分支
            emitLine(depth, "do {");
            loopExitStack.push(b.fallThrough);
            whileHeadStack.push(b.startAddr);
            emitRegion(b.fallThrough, body, depth + 1);
            whileHeadStack.pop();
            loopExitStack.pop();
            IrInsn lastInsn = b.insns.get(b.insns.size() - 1);
            IrNode cond = makeCond(lastInsn, true); // 分支退出 → 取反
            emitLine(depth, "} while (" + cond.toC() + ");");
            return b.jumpTarget;
        }
        // 普通块头: 无条件回跳 → do { ... } while (1);
        emitLine(depth, "do {");
        whileHeadStack.push(b.startAddr);
        emitBlockInstructions(b, depth + 1, false); // header 完整指令
        // body 从顺序后继起; uncond header (b xxx 结尾, 无 fallThrough,
        // 如 T5 的 0x500c → b 0x5008) 时从第一个 latch 起 — 保证 latch
        // 被 emitted 标记 (否则 emitRemainingBlocks 兜底补 goto).
        long bodyStart = b.fallThrough;
        if (bodyStart <= 0 || !body.contains(bodyStart)) {
            bodyStart = latches.get(0);
        }
        emitRegion(bodyStart, body, depth + 1);
        whileHeadStack.pop();
        emitLine(depth, "} while (1);");
        return 0; // 无限循环
    }

    /**
     * Emit a while loop.
     * <p>The current block is a loop header with a conditional branch that
     * jumps forward to the loop exit. The loop body is the fall-through.
     * The branch is taken to EXIT the loop, so the while condition is
     * the inverse of the branch condition.
     * <p>v4.5: 循环体边界 = header 支配闭包 (不含 loopExit); loopExit 由
     * 后支配树给出 (header 的后支配者 = 所有 header 路径必经的出口),
     * 不再用地址 jumpTarget 近似。
     *
     * @param b        the loop header block (conditional branch forward)
     * @param loopExit the loop exit address (post-dominator of the header)
     * @param depth    current indentation depth
     */
    private void emitWhileLoop(Block b, long loopExit, int depth) {
        // Emit pre-branch instructions (suppress the branch itself)
        emitBlockInstructions(b, depth, true);

        IrInsn lastInsn = b.insns.get(b.insns.size() - 1);
        // Branch taken = exit loop → while condition = inverse of branch
        IrNode cond = makeCond(lastInsn, true);

        // Spinlock heuristic: an empty while-body (the fall-through block is
        // the loop exit itself, i.e. no instructions between header and exit)
        // is almost certainly a busy-wait spin. Annotate it so the reader
        // doesn't see a bare "while (cond) {}".
        boolean spin = (b.fallThrough == loopExit);
        String spinNote = spin ? "  // spin" : "";
        emitLine(depth, "while (" + cond.toC() + ") {" + spinNote);
        loopExitStack.push(loopExit);
        // v4.1: 循环头 = 当前块 (latch 回边目标比对用)
        whileHeadStack.push(b.startAddr);
        Set<Long> bodyRegion = domClosure(b.startAddr, loopExit);
        bodyRegion.remove(b.startAddr); // header 已发射
        emitRegion(b.fallThrough, bodyRegion, depth + 1);
        whileHeadStack.pop();
        loopExitStack.pop();
        emitLine(depth, "}");
    }

    /**
     * Emit an if/else construct.
     * <p>The current block has a conditional branch that jumps forward.
     * The fall-through is the "then" block; the jump target is the "else"
     * block (or merge point if no else). The branch goes to else/merge when
     * the condition is true, so the if condition is the inverse of the
     * branch condition.
     * <p>v4.5: 汇合点 = then/else 入口的最近共同后支配者 (后支配树), 区域 =
     * 各分支入口的支配闭包。替代 v3.2.16 的地址区间扫描 (findMergePoint),
     * 对地址交错/嵌套分支更精确。
     *
     * @param b      the block with the conditional branch
     * @param region 调用方允许发射的块集合 (递归时由闭包构造)
     * @param depth  current indentation depth
     * @return the merge point address where the caller should continue
     */
    private long emitIfElse(Block b, Set<Long> region, int depth) {
        // Emit pre-branch instructions (suppress the branch itself)
        emitBlockInstructions(b, depth, true);

        IrInsn branchInsn = b.insns.get(b.insns.size() - 1);

        long thenAddr = b.fallThrough;
        long elseAddr = b.jumpTarget;

        // Detect else: then/else 汇合点在 else 入口之后 → 有 else 分支
        long mergePoint = postMergePoint(b);
        boolean hasElse = (mergePoint > 0 && mergePoint != elseAddr);

        if (mergePoint <= 0) {
            // 无后支配汇合点 (then/else 之一为返回分支, 或不可约) →
            // then 汇入 else 入口, 无 else
            mergePoint = elseAddr;
        }

        // If condition: branch goes to else → if condition = inverse of branch
        IrNode cond = makeCond(branchInsn, true);

        emitLine(depth, "if (" + cond.toC() + ") {");
        Set<Long> thenRegion = domClosure(thenAddr, mergePoint);
        emitRegion(thenAddr, thenRegion, depth + 1);
        emitLine(depth, "}");

        if (hasElse) {
            emitLine(depth, "else {");
            Set<Long> elseRegion = domClosure(elseAddr, mergePoint);
            emitRegion(elseAddr, elseRegion, depth + 1);
            emitLine(depth, "}");
        }

        return mergePoint;
    }

    // ── Instruction emission helpers ──

    /**
     * Emit all instructions in a block.
     *
     * @param b              the block
     * @param depth          indentation depth
     * @param suppressBranch if true, skip the last instruction (the branch/return)
     */
    private void emitBlockInstructions(Block b, int depth, boolean suppressBranch) {
        // v4.1: 记录块首行 (goto 标签回填用)
        if (b != null && b.startAddr > 0
                && !blockFirstLine.containsKey(b.startAddr)) {
            blockFirstLine.put(b.startAddr, output.size());
        }
        int count = b.insns.size();
        if (suppressBranch && count > 0) {
            count--; // don't emit the terminator
        }
        for (int i = 0; i < count; i++) {
            emitInstruction(b.insns.get(i), depth);
        }
    }

    /**
     * Emit a single instruction's IR nodes.
     * <p>Skips invalid (null) nodes and {@link IrNode.Nop}. Handles:
     * <ul>
     *   <li>{@link IrNode.Composed} — multi-statement, emit with semicolon</li>
     *   <li>{@link IrNode.Nop} — skip (empty)</li>
     *   <li>{@link IrNode.Raw} — raw text, emit as-is (no added semicolon)</li>
     *   <li>Normal (Assign, Return, CallStmt, ...) — emit with semicolon</li>
     * </ul>
     *
     * @param insn  the IR instruction
     * @param depth indentation depth
     */
    private void emitInstruction(IrInsn insn, int depth) {
        if (insn == null || insn.nodes == null) return;
        if (!insn.valid) return; // suppressed by adrp+add merge or similar

        // IT-block wrap (ARM32 Thumb): emit nodes inside if (cond) { ... }
        boolean wrapped = insn.itWrapCond != null && !insn.itWrapCond.isEmpty();
        if (wrapped) {
            emitLine(depth, "if (" + insn.itWrapCond + ") {");
        }
        int nodeDepth = wrapped ? depth + 1 : depth;

        for (int i = 0; i < insn.nodes.size(); i++) {
            IrNode node = insn.nodes.get(i);
            if (node == null) continue;
            if (node instanceof IrNode.Nop) continue;
            String text = node.toC();
            if (text == null || text.isEmpty()) continue;

            if (node instanceof IrNode.Label) {
                emitLine(nodeDepth, text);
            } else if (node instanceof IrNode.Raw) {
                // Raw: comments/labels as-is, code statements get semicolon
                if (text.startsWith("/*") || text.endsWith(":") || text.startsWith("//")) {
                    emitLine(nodeDepth, text);
                } else {
                    emitLine(nodeDepth, text + ";");
                }
            } else {
                emitLine(nodeDepth, text + ";");
            }
        }

        if (wrapped) {
            emitLine(depth, "}");
        }
    }

    /**
     * Append an indented line to the output.
     *
     * @param depth indentation depth (4 spaces per level)
     * @param text  the line content
     */
    private void emitLine(int depth, String text) {
        output.add(indentStr(depth) + text);
    }

    /**
     * Generate an indentation string.
     *
     * @param depth indentation depth (4 spaces per level)
     * @return the indentation prefix
     */
    private String indentStr(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("    ");
        }
        return sb.toString();
    }

    /**
     * v4.5: 构建后支配树 (镜像 buildDomTree, 失败静默置 null →
     * 汇合点/循环出口判定回退地址近似)。
     */
    private void buildPostDomTree() {
        try {
            if (blocks.isEmpty()) return;
            List<Long> addrs = new ArrayList<>();
            Map<Long, List<Long>> predsMap = new HashMap<>();
            Map<Long, List<Long>> succsMap = new HashMap<>();
            for (Block b : blocks) {
                addrs.add(b.startAddr);
                predsMap.put(b.startAddr, new ArrayList<>(b.preds));
                succsMap.put(b.startAddr, new ArrayList<>(b.succs));
            }
            postDomTree = new PostDominatorTree(addrs, predsMap, succsMap);
        } catch (Throwable t) {
            postDomTree = null;
        }
    }

    /**
     * v4.5: 支配闭包 — 被 {@code entry} 支配的块起始地址集合 (不含
     * {@code exclude}; exclude ≤ 0 表示不排除)。用于限定 if 分支/循环体
     * 的发射区域: 分支体所有块必经入口, 出口/汇合点不被入口支配
     * (旁路路径存在) → 自动排除在闭包外, 不依赖地址单调性。
     */
    private Set<Long> domClosure(long entry, long exclude) {
        Set<Long> s = new HashSet<>();
        if (domTree == null) {
            // 退化: 无支配树时按地址区间近似 (entry..exclude 之间的块)
            for (Block b : blocks) {
                if (b.startAddr == exclude) continue;
                if (b.startAddr >= entry && (exclude <= 0 || b.startAddr < exclude)) {
                    s.add(b.startAddr);
                }
            }
            return s;
        }
        for (Block b : blocks) {
            if (b.startAddr == exclude) continue;
            if (domTree.dominates(entry, b.startAddr)) {
                s.add(b.startAddr);
            }
        }
        return s;
    }

    /**
     * v4.5: if-else 汇合点 — then/else 入口的最近共同后支配者。
     * 返回 -1 表示无后支配汇合 (分支之一为返回, 或不可约)。
     */
    private long postMergePoint(Block b) {
        long thenAddr = b.fallThrough;
        long elseAddr = b.jumpTarget;
        if (thenAddr <= 0 || elseAddr <= 0 || postDomTree == null) return -1;
        if (elseAddr <= thenAddr) return -1; // 非前向分支 (回边场景, 不在此处理)
        long m = postDomTree.lcpd(thenAddr, elseAddr);
        if (m == thenAddr || m == elseAddr || m == PostDominatorTree.EXIT) {
            return -1; // 一个分支直接汇入另一个入口 → 无 else
        }
        return m;
    }

    /**
     * v4.5: 循环出口 — header 的最近后支配者 (ipdom)。
     * 对单出口自然循环: 所有 header 出发路径必经出口块, 而循环体内块
     * 被 header→exit 直跳路径绕过 → 不后支配 header, 故 ipdom(header)
     * 恰为循环出口。死循环 (ipdom 自身/0/EXIT) 返回 -1 → 调用方降级。
     */
    private long postDomExit(long headerAddr) {
        if (postDomTree == null) return -1;
        long x = postDomTree.ipdom(headerAddr);
        if (x == 0 || x == PostDominatorTree.EXIT || x == headerAddr) return -1;
        return x;
    }

    // ── Condition and merge helpers ──

    /**
     * Build a condition expression from an instruction's condition metadata.
     *
     * @param insn   the branch instruction (with condA, condB, condType set)
     * @param invert if true, invert the condition operator
     * @return an {@link IrNode} representing the condition
     */
    private IrNode makeCond(IrInsn insn, boolean invert) {
        IrNode a = insn.condA != null ? insn.condA : IrNode.num(0);
        IrNode b = insn.condB != null ? insn.condB : IrNode.num(0);
        String condType = insn.condType != null ? insn.condType : "NE";
        return IrNode.makeCondition(a, b, condType, invert);
    }

    /**
     * Find the merge point after an if/else by checking if the then-region
     * ends with an unconditional jump that skips the else block.
     * <p>v3.2.16: scans <b>every block of the whole then-region</b>
     * ({@code fallThrough .. jumpTarget}) and returns the <b>largest</b>
     * unconditional-jump target. This is correct for nested if/else where the
     * then-body's last block may end in a conditional branch but an earlier
     * inner block exits the region with an unconditional jump.
     * <p>v4.5: 已被 {@link #postMergePoint} (后支配汇合点) 替代, 仅保留作
     * 无后支配树时的退化回退。
     *
     * @param b the block with the conditional branch
     * @return the merge point address, or -1 if not found
     */
    private long findMergePoint(Block b) {
        long thenAddr = b.fallThrough;
        long elseAddr = b.jumpTarget;
        // v3.5: 边界加固 — 无 fallthrough 或非前向分支 (jumpTarget <= thenAddr)
        //   时不存在 merge point, 直接返回 -1 (避免扫到无关块).
        if (thenAddr <= 0 || elseAddr <= thenAddr) return -1;
        long best = -1;
        for (int i = 0; i < blocks.size(); i++) {
            Block blk = blocks.get(i);
            if (blk.startAddr >= thenAddr && blk.startAddr < elseAddr
                    && !blk.insns.isEmpty()) {
                IrInsn lastInsn = blk.insns.get(blk.insns.size() - 1);
                if (lastInsn.isUncondBranch && lastInsn.jumpTarget > 0
                        && lastInsn.jumpTarget > best) {
                    best = lastInsn.jumpTarget;
                }
            }
        }
        return best;
    }

    /**
     * v3.9: 回边判定 — 支配树可用时用经典定义 (边 b→target 是回边 ⟺
     * target 支配 b), 否则回退地址近似 (target 地址 ≤ b 地址).
     */
    private boolean isBackEdge(Block b) {
        if (b.jumpTarget <= 0) return false;
        if (domTree != null) {
            return domTree.dominates(b.jumpTarget, b.startAddr);
        }
        return b.jumpTarget <= b.startAddr;
    }

    /**
     * v3.9: 基于融合后块图构建支配树; 失败置 null (emitRegion 回退地址近似).
     * v4.4: 不再要求 native CFG — 自建块图同样建支配树 (回边用经典定义,
     * 不再靠地址近似), 是结构化正确性的基础。
     */
    private void buildDomTree() {
        try {
            if (blocks.isEmpty()) return;
            List<Long> addrs = new ArrayList<>();
            Map<Long, List<Long>> predsMap = new HashMap<>();
            Map<Long, List<Long>> succsMap = new HashMap<>();
            for (Block b : blocks) {
                addrs.add(b.startAddr);
                predsMap.put(b.startAddr, new ArrayList<>(b.preds));
                succsMap.put(b.startAddr, new ArrayList<>(b.succs));
            }
            domTree = new DominatorTree(
                    addrs, predsMap, succsMap, blocks.get(0).startAddr);
        } catch (Throwable t) {
            domTree = null;
        }
    }

    /**
     * Check if a block has a predecessor whose address is greater than
     * {@code currentAddr} <b>and whose jump target is exactly this block</b>,
     * indicating a real back-edge (loop latch).
     * <p>v3.2.16: The jump-target check prevents "fake while" misclassification
     * when a higher-address block merely falls through / jumps elsewhere but
     * still appears in {@code preds} (e.g. via a shared successor list).
     *
     * @param b           the block to check
     * @param currentAddr the current block's address
     * @return true if a genuine back-edge predecessor exists
     */
    private boolean hasBackEdgePred(Block b, long currentAddr) {
        // v3.9: 支配树可用时用经典回边定义: pred→b 是回边 ⟺ b 支配 pred
        if (domTree != null) {
            for (int i = 0; i < b.preds.size(); i++) {
                if (domTree.dominates(b.startAddr, b.preds.get(i))) {
                    return true;
                }
            }
            return false;
        }
        for (int i = 0; i < b.preds.size(); i++) {
            long predAddr = b.preds.get(i);
            if (predAddr > currentAddr) {
                Block pred = blockByStart.get(predAddr);
                if (pred != null && pred.jumpTarget == currentAddr) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * v4.2: 检查是否存在回边 latch 落在循环体区间 [fallThrough, loopExit) 内。
     * while 发射的语义前提: 循环体内某块 (latch) 能跳回 header, 且该 latch
     * 位于 loopExit 之前。若所有回边前驱都 ≥ loopExit (跨区回边), 循环体实际
     * 跨越本块的跳转目标, 用 while 会生成永不回跳的假循环 → 应降级为 label+goto。
     *
     * @param b       候选 loop header 块
     * @param loopExit 本块条件跳的目标 (loopExit = b.jumpTarget)
     * @return true 当存在回边前驱满足 startAddr < predAddr < loopExit
     */
    private boolean loopLatchInBody(Block b, long loopExit) {
        for (int i = 0; i < b.preds.size(); i++) {
            long predAddr = b.preds.get(i);
            if (predAddr <= b.startAddr || predAddr >= loopExit) continue;
            if (isBackEdgeFrom(b, predAddr)) return true;
        }
        return false;
    }

    /**
     * v4.2: 判定 predAddr → b 是否为回边 (支配树优先, 退化时地址近似).
     */
    private boolean isBackEdgeFrom(Block b, long predAddr) {
        if (domTree != null) {
            return domTree.dominates(b.startAddr, predAddr);
        }
        Block pred = blockByStart.get(predAddr);
        return pred != null && pred.jumpTarget == b.startAddr;
    }

    /**
     * Tail-goto simplification for the goto emitted when reaching an
     * already-emitted block.
     * <p>Rules (checked in order):
     * <ol>
     *   <li>If {@code targetAddr} is the innermost loop's exit address
     *       (top of {@link #loopExitStack}), the goto is a loop exit →
     *       return {@code "break;"}.</li>
     *   <li>If the target block is a function "tail" — i.e. it (or the chain
     *       it falls through to) ends in a return and has no other
     *       predecessors that re-enter the main body — the goto is just a
     *       forward jump to the function epilogue → return
     *       {@code "return;"} (or {@code "break;"} if inside a loop, to avoid
     *       prematurely exiting siblings).</li>
     * </ol>
     * Returns {@code null} when no simplification applies (emit a plain goto).
     *
     * @param targetAddr the already-emitted block being jumped to
     * @param depth      current nesting depth (unused, reserved)
     * @return simplified statement text, or {@code null}
     */
    private String simplifyTailGoto(long targetAddr, int depth) {
        // Rule 1: goto to the innermost loop's exit → break
        if (loopExitStack != null && !loopExitStack.isEmpty()
                && loopExitStack.peek() == targetAddr) {
            return "break;";
        }
        // Rule 2: goto to a tail/return block → return
        if (isTailReturnBlock(targetAddr)) {
            // If we are inside a loop, prefer break so the loop's own exit
            // logic (which may set the return value) still runs; otherwise a
            // bare return is the cleanest tail simplification.
            if (loopExitStack != null && !loopExitStack.isEmpty()) {
                return "break;";
            }
            return "return;";
        }
        return null;
    }

    /**
     * Determine whether the block at {@code addr} (and the straight-line chain
     * it falls through to) is a function "tail": it ends in a return and does
     * not branch back into already-structured code. A tail block carries no
     * real logic worth keeping a goto for, so a goto to it can become a return.
     *
     * @param addr the candidate tail block start address
     * @return true if the block chain ends in a return
     */
    private boolean isTailReturnBlock(long addr) {
        Block b = blockByStart.get(addr);
        if (b == null) return false;
        // Walk the fall-through chain until a terminator is hit.
        int guard = 0;
        while (b != null && guard < 64) {
            guard++;
            // 条件返回 (popeq/bxeq) 不是 clean tail — 后面仍有可达代码,
            // 把它当尾块会把 goto 误简化为 return/break (语义错误).
            if (b.isCondBranch) {
                return false;
            }
            if (b.isReturn) {
                return true;
            }
            if (b.isUncondBranch) {
                // Follow unconditional jump to the next block.
                if (b.jumpTarget <= 0) return false;
                b = blockByStart.get(b.jumpTarget);
                continue;
            }
            // Straight-line fall-through.
            if (b.fallThrough <= 0) return false;
            b = blockByStart.get(b.fallThrough);
        }
        return false;
    }

    /**
     * Generate a label name for an address (v4.9: 空块重定向后返回后继地址的标签).
     *
     * @param addr the block address
     * @return label string like "L_1a2b"
     */
    private String label(long addr) {
        if (emptyRedirect != null) {
            Long r = emptyRedirect.get(addr);
            if (r != null) addr = r;
        }
        return "L_" + Long.toHexString(addr);
    }

    /** 原始地址标签 (发射块定义处使用, 不做空块重定向). */
    private String labelRaw(long addr) {
        return "L_" + Long.toHexString(addr);
    }

    // ── Block inner class ──

    /**
     * Basic block — a maximal sequence of straight-line instructions
     * with a single entry point (startAddr) and a single exit point.
     */
    static class Block {
        /** Address of the first instruction in this block. */
        long startAddr;

        /** Address of the last instruction in this block. */
        long endAddr;

        /** Instructions belonging to this block. */
        final List<IrInsn> insns = new ArrayList<>();

        /** Successor block addresses. */
        final List<Long> succs = new ArrayList<>();

        /** Predecessor block addresses. */
        final List<Long> preds = new ArrayList<>();

        /** Fall-through address (next sequential block), 0 if none. */
        long fallThrough;

        /** Branch target address, 0 if not a branch. */
        long jumpTarget;

        /** True if the block ends with a conditional branch. */
        boolean isCondBranch;

        /** True if the block ends with an unconditional branch. */
        boolean isUncondBranch;

        /** True if the block ends with a return. */
        boolean isReturn;

        /** v3.9: native CFG 标记的循环头 (BACK_EDGE 边目标). */
        boolean isNativeLoopHeader;

        /** v3.9: 跳转表目标 (非 null = 本块以跳转表结尾, 触发 emitSwitchChain). */
        List<Long> tableTargets;

        /** v3.9: 跳转表索引寄存器名 (生成 if (reg == i) 链). */
        String tableIndexReg;

        Block(long startAddr) {
            this.startAddr = startAddr;
        }
    }
}
