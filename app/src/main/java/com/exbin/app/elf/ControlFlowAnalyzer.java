package com.exbin.app.elf;

import com.exbin.app.nativebridge.NativeBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 控制流分析: 通过 native 层 (C++ + capstone) 构建基本块和分支图.
 * <p>
 * v2.9.47: 控制流分析下沉到 native 层 (control_flow_analyzer.cpp).
 * Java 层只做 JNI 调用 + 数据转换, 不再自己分析指令.
 * <p>
 * 调用链:
 *   Java: ControlFlowAnalyzer.build(insns)
 *       → NativeBridge.analyzeControlFlow(insnData, funcName, funcAddr, machineType, isThumb)
 *       → JNI: nativeAnalyzeControlFlow()
 *       → C++: ndk_cfg::ControlFlowAnalyzer::analyzeFunction()
 *       → 返回 CFG 数据 (blocks + edges + loops + depths)
 *       → Java 层解析 Object[] → CFG 结构体
 */
public class ControlFlowAnalyzer {

    /** 边的类型,对应 UI 着色:真分支绿, 假分支红, 默认蓝 */
    public enum EdgeKind {
        /** 条件分支"成立"的目标 - 绿色 */
        TRUE_BRANCH,
        /** 条件分支"不成立"的 fall-through 目标 - 红色 */
        FALSE_BRANCH,
        /** 无条件跳转, 调用, 顺序流 - 蓝色 */
        UNCONDITIONAL,
        /** 回边 (循环) - 橙色 */
        BACK_EDGE,
    }

    public static class Block {
        public long startAddr;
        public long endAddr;          // inclusive
        public List<DisassembledInstruction> instructions;
        public List<Edge> successors;   // 后继边
        public boolean isEntry;
        public boolean isExit;
        public boolean isLoopHeader;    // v2.9.47: 循环头
        public int depth;               // v2.9.47: BFS 深度
        public int blockId;             // v2.9.47: 基本块 ID
        public int blockType;           // v2.9.47: 块类型 (0=normal,1=entry,2=exit,3=cond,4=uncond,5=call,6=return)
        public String branchCondition;  // v2.9.47: 分支条件 (如 "beq")
        public long branchTarget;       // v2.9.47: 分支目标地址
        public long fallThrough;        // v2.9.47: fall-through 地址
        // 测量时填充,供 UI 层布局用
        public float extraMeasuredWidth;
        public float extraMeasuredHeight;

        public Block(long startAddr) {
            this.startAddr = startAddr;
            this.instructions = new ArrayList<>();
            this.successors = new ArrayList<>();
            this.isEntry = false;
            this.isExit = false;
            this.isLoopHeader = false;
            this.depth = -1;
            this.blockId = -1;
            this.blockType = 0;
            this.branchTarget = 0;
            this.fallThrough = 0;
        }
    }

    public static class Edge {
        public long from;
        public long to;
        public EdgeKind kind;
        public String label;   // 例如 "true"/"false"/"uncond"/"call"/"loop"

        public Edge(long from, long to, EdgeKind kind, String label) {
            this.from = from;
            this.to = to;
            this.kind = kind;
            this.label = label;
        }
    }

    public static class CFG {
        public List<Block> blocks;
        public List<DisassembledInstruction> instructions;
        public Map<Long, Block> byAddr;
        public int maxDepth;           // v2.9.47: 最大深度
        public int loopCount;          // v2.9.47: 循环数量
        public int entryBlockId;       // v2.9.47: 入口块 ID
        public List<Integer> exitBlockIds; // v2.9.47: 出口块 ID 列表

        public CFG() {
            blocks = new ArrayList<>();
            byAddr = new HashMap<>();
            instructions = new ArrayList<>();
            maxDepth = 0;
            loopCount = 0;
            entryBlockId = -1;
            exitBlockIds = new ArrayList<>();
        }
    }

    /** 基本块类型常量 (与 C++ BasicBlockType 对应) */
    private static final int BB_TYPE_NORMAL = 0;
    private static final int BB_TYPE_ENTRY = 1;
    private static final int BB_TYPE_EXIT = 2;
    private static final int BB_TYPE_CONDITIONAL = 3;
    private static final int BB_TYPE_UNCONDITIONAL = 4;
    private static final int BB_TYPE_CALL = 5;
    private static final int BB_TYPE_RETURN = 6;

    /** 边类型常量 (与 C++ EdgeType 对应) */
    private static final int EDGE_TYPE_NORMAL = 0;
    private static final int EDGE_TYPE_CONDITIONAL = 1;
    private static final int EDGE_TYPE_FALLTHROUGH = 2;
    private static final int EDGE_TYPE_BACK = 3;

    /**
     * 从反汇编指令列表构建控制流图.
     * v2.9.47: 通过 JNI 调用 native 层分析器.
     *
     * @param insns 反汇编指令列表
     * @return 控制流图 (包含基本块和边)
     */
    public static CFG build(List<DisassembledInstruction> insns) {
        CFG cfg = new CFG();
        if (insns == null || insns.isEmpty()) return cfg;
        cfg.instructions = insns;

        // 1. 将 DisassembledInstruction 列表转换为 native 层需要的 Object[] 格式
        // 格式: Object[12] = { addr:Long, size:Integer, bytes:byte[],
        //                      mnemonic:String, opStr:String, bbLeader:Integer,
        //                      comment, enhancedOp, locLabel, regType, stackVar, targetAddr:Long }
        Object[] insnData = new Object[insns.size()];
        for (int i = 0; i < insns.size(); i++) {
            DisassembledInstruction ins = insns.get(i);
            Object[] row = new Object[12];
            row[0] = ins.address;
            row[1] = ins.bytes != null ? ins.bytes.length : 2;
            row[2] = ins.bytes != null ? ins.bytes : new byte[0];
            row[3] = ins.mnemonic != null ? ins.mnemonic : "";
            row[4] = ins.opStr != null ? ins.opStr : "";
            row[5] = ins.bbLeader ? 1 : 0;
            row[6] = ins.annotation != null ? ins.annotation : "";
            row[7] = ins.enhancedOpStr != null ? ins.enhancedOpStr : "";
            row[8] = ins.nativeLocLabel != null ? ins.nativeLocLabel : "";
            row[9] = ins.nativeRegType != null ? ins.nativeRegType : "";
            row[10] = ins.stackVar != null ? ins.stackVar : "";
            row[11] = 0L; // targetAddr (不关键, native 层会重新分析)
            insnData[i] = row;
        }

        // 2. 推断函数信息
        long funcAddr = insns.get(0).address;
        String funcName = ""; // 函数名不影响 CFG 分析
        int machineType = inferMachineType(insns);
        boolean isThumb = insns.get(0).isThumb;

        // 3. 调用 native 层控制流分析
        Object[] result = NativeBridge.analyzeControlFlow(
                insnData, funcName, funcAddr, machineType, isThumb);

        if (result == null) {
            // native 分析失败, 回退到简单顺序流
            return buildFallback(insns);
        }

        // 4. 解析 native 返回的 CFG 数据
        // Object[8] = { blockCount, edgeCount, entryBlockId, maxDepth, loopCount,
        //               blocks:Object[][], edges:Object[][], exitBlockIds:int[] }
        try {
            int blockCount = (Integer) result[0];
            int edgeCount = (Integer) result[1];
            cfg.entryBlockId = (Integer) result[2];
            cfg.maxDepth = (Integer) result[3];
            cfg.loopCount = (Integer) result[4];

            // 4a. 解析 blocks
            Object[] blocksData = (Object[]) result[5];
            if (blocksData != null) {
                // 先创建所有块
                for (int i = 0; i < blocksData.length; i++) {
                    Object[] row = (Object[]) blocksData[i];
                    Block b = new Block(0);
                    b.blockId = (Integer) row[0];
                    b.startAddr = (Long) row[1];
                    b.endAddr = (Long) row[2];
                    int instrCount = (Integer) row[3];
                    b.blockType = (Integer) row[4];
                    b.branchCondition = (String) row[5];
                    if (row[6] != null) b.branchTarget = (Long) row[6];
                    if (row[7] != null) b.fallThrough = (Long) row[7];
                    b.depth = (Integer) row[8];

                    // 块类型映射
                    b.isEntry = (b.blockType == BB_TYPE_ENTRY || b.blockId == cfg.entryBlockId);
                    b.isExit = (b.blockType == BB_TYPE_RETURN || b.blockType == BB_TYPE_EXIT);

                    // 填充指令 (从原指令列表中按地址范围筛选)
                    for (DisassembledInstruction ins : insns) {
                        if (ins.address >= b.startAddr && ins.address <= b.endAddr) {
                            b.instructions.add(ins);
                        }
                    }

                    cfg.blocks.add(b);
                    cfg.byAddr.put(b.startAddr, b);
                }

                // 4b. 解析 edges 并关联到块
                Object[] edgesData = (Object[]) result[6];
                if (edgesData != null) {
                    for (int i = 0; i < edgesData.length; i++) {
                        Object[] row = (Object[]) edgesData[i];
                        int fromId = (Integer) row[0];
                        int toId = (Integer) row[1];
                        int edgeType = (Integer) row[2];
                        String label = (String) row[3];

                        // 通过 blockId 找到对应的块
                        Block fromBlock = findBlockById(cfg.blocks, fromId);
                        Block toBlock = findBlockById(cfg.blocks, toId);
                        if (fromBlock != null && toBlock != null) {
                            EdgeKind kind = edgeTypeToKind(edgeType);
                            fromBlock.successors.add(
                                    new Edge(fromBlock.startAddr, toBlock.startAddr, kind, label));
                        }
                    }
                }
            }

            // 4c. 解析 exitBlockIds
            int[] exitIds = (int[]) result[7];
            if (exitIds != null) {
                for (int id : exitIds) {
                    cfg.exitBlockIds.add(id);
                }
            }

            // 4d. 标记循环头 (如果有循环)
            // 简单方式: 如果有回边, 目标块标记为 isLoopHeader
            for (Block b : cfg.blocks) {
                for (Edge e : b.successors) {
                    if (e.kind == EdgeKind.BACK_EDGE) {
                        Block target = cfg.byAddr.get(e.to);
                        if (target != null) {
                            target.isLoopHeader = true;
                        }
                    }
                }
            }

        } catch (Exception e) {
            // 解析失败, 回退
            return buildFallback(insns);
        }

        return cfg;
    }

    /** 根据 blockId 查找块 */
    private static Block findBlockById(List<Block> blocks, int id) {
        for (Block b : blocks) {
            if (b.blockId == id) return b;
        }
        return null;
    }

    /** C++ EdgeType → Java EdgeKind */
    private static EdgeKind edgeTypeToKind(int edgeType) {
        switch (edgeType) {
            case EDGE_TYPE_CONDITIONAL: return EdgeKind.TRUE_BRANCH;
            case EDGE_TYPE_FALLTHROUGH: return EdgeKind.FALSE_BRANCH;
            case EDGE_TYPE_BACK: return EdgeKind.BACK_EDGE;
            default: return EdgeKind.UNCONDITIONAL;
        }
    }

    /** 推断机器类型 */
    private static int inferMachineType(List<DisassembledInstruction> insns) {
        if (insns == null || insns.isEmpty()) return 40; // EM_ARM
        String mn = insns.get(0).mnemonic;
        if (mn != null) {
            // ARM64 指令通常有 x/w 寄存器
            for (DisassembledInstruction ins : insns) {
                if (ins.opStr != null && (ins.opStr.contains("x") || ins.opStr.contains("w"))) {
                    // 检查是否是 ARM64 (x0-x30, w0-w30)
                    if (ins.opStr.matches(".*\\b[xw]\\d{1,2}\\b.*")) {
                        return 183; // EM_AARCH64
                    }
                }
            }
        }
        return 40; // EM_ARM
    }

    /**
     * 回退方案: native 分析失败时, 用简单的顺序流构建 CFG.
     * 不识别分支, 把整个函数当作一个基本块.
     */
    private static CFG buildFallback(List<DisassembledInstruction> insns) {
        CFG cfg = new CFG();
        cfg.instructions = insns;
        if (insns == null || insns.isEmpty()) return cfg;

        Block b = new Block(insns.get(0).address);
        b.endAddr = insns.get(insns.size() - 1).address;
        b.isEntry = true;
        b.isExit = true;
        b.blockId = 0;
        b.depth = 0;
        b.instructions.addAll(insns);
        cfg.blocks.add(b);
        cfg.byAddr.put(b.startAddr, b);
        cfg.entryBlockId = 0;
        cfg.exitBlockIds.add(0);
        return cfg;
    }
}
