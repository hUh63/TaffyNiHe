package com.exbin.app.elf.pseudoc.r2dec;

import com.exbin.app.elf.DisassembledInstruction;
import com.exbin.app.elf.ControlFlowAnalyzer;
import com.exbin.app.elf.pseudoc.PseudoCConverter;
import com.exbin.app.nativebridge.NativeBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main entry point for the r2dec pseudo-C generator.
 * <p>Implements {@link PseudoCConverter} and orchestrates the full
 * decompilation pipeline in five stages:
 * <ol>
 *   <li><b>Stage 1</b> — Build {@link IrInsn} list from disassembled
 *       instructions; resolve callee names from labels/imports.</li>
 *   <li><b>Stage 2</b> — Stack frame / type / parameter analysis data comes
 *       from the native side (afvj, structured signatures, nativeRegType);
 *       no Java-side analysis passes (v4.3 精简).</li>
 *   <li><b>Stage 3</b> — Run {@link InstructionHandler} (Arm64 or Arm32) for
 *       each instruction, translating to IR nodes. Errors are caught
 *       per-instruction to ensure robustness.</li>
 *   <li><b>Stage 4</b> — {@link ControlFlowStructurer#structure()} converts
 *       the flat IR into structured pseudo-C with if/else, while, do-while.</li>
 *   <li><b>Stage 5</b> — {@link #printResult} assembles the final output with
 *       function signature, local declarations, and body.</li>
 * </ol>
 * If any stage fails catastrophically, the converter falls back to a raw
 * disassembly listing so the user always gets usable output.
 */
public class R2DecPseudoC implements PseudoCConverter {

    /** ELF machine type: ARM (32-bit). */
    public static final int EM_ARM = 40;

    /** ELF machine type: AArch64 (ARM 64-bit). */
    public static final int EM_AARCH64 = 183;

    @Override
    public String name() {
        return "R2Dec (r2dec 移植)";
    }

    @Override
    public List<String> convert(PseudoCContext pctx) {
        if (pctx == null
                || pctx.instructions == null
                || pctx.instructions.isEmpty()) {
            List<String> r = new ArrayList<>();
            r.add("// No instructions");
            return r;
        }
        try {
            return doDecompile(pctx);
        } catch (Throwable t) {
            return fallbackRaw(pctx, t);
        }
    }

    /**
     * v4.x: 整函数流水线 — 只跑到 Stage 4 (structurer), 不做后处理,
     * 返回原始结构化 body + 块首行映射, 供 BlockPseudoCProvider 按块切分.
     * <p>跳过 SSA/StructRecovery/SwitchCase/Peephole/LabelCleanup 等后处理,
     * 保证 blockFirstLine 索引精确对应输出行.
     *
     * @return Object[]{body(List<String>), blockFirstLine(Map<Long,Integer>)};
     *         失败返回 null
     */
    public Object[] convertWithBlockMap(PseudoCContext pctx) {
        if (pctx == null || pctx.instructions == null || pctx.instructions.isEmpty()) return null;
        try {
            List<IrInsn> insns = buildInsns(pctx);
            DecompContext ctx = buildCtx(pctx);
            translateInsns(insns, ctx);

            ControlFlowAnalyzer.CFG nativeCfg = pctx.prebuiltCfg;
            if (nativeCfg == null) {
            try {
                nativeCfg = ControlFlowAnalyzer.build(pctx.instructions);
                if (nativeCfg == null || nativeCfg.blocks.size() <= 1) nativeCfg = null;
            } catch (Throwable t) { nativeCfg = null; }
            }

            try {
                long funcMax = 0;
                if (!insns.isEmpty()) funcMax = insns.get(insns.size() - 1).addr;
                ctx.jumpTables.putAll(JumpTableResolver.resolve(
                        insns, pctx.soPath, pctx.functionAddress, funcMax));
            } catch (Throwable t) { /* 忽略 */ }

            ControlFlowStructurer structurer = new ControlFlowStructurer(insns, ctx, nativeCfg);
            structurer.structure();
            List<String> body = structurer.getOutput();
            Map<Long, Integer> blockMap = structurer.getBlockFirstLine();
            return new Object[]{ body, blockMap };
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Pipeline ──

    /**
     * Execute the full five-stage decompilation pipeline.
     *
     * @param pctx the pseudo-C context with disassembly and metadata
     * @return structured pseudo-C output lines
     */
    private List<String> doDecompile(PseudoCContext pctx) {

        // ── Stage 1-3: 构建 IR + 上下文透传 + 逐指令翻译 ──
        List<IrInsn> insns = buildInsns(pctx);
        DecompContext ctx = buildCtx(pctx);
        translateInsns(insns, ctx);

        // ── Stage 4: Control flow structuring ──
        // v3.9: native CFG 为主, 自建为辅 — native 层 (capstone C++) 递归下降
        //   分析的可达块/边/循环结构注入结构化器; 退化 (null/单块/异常) 时
        //   nativeCfg 置 null, ControlFlowStructurer 走自建路径。
        // v4.4: 自建路径同样构建支配树 (回边用经典定义, 不再地址近似)。
        ControlFlowAnalyzer.CFG nativeCfg = pctx.prebuiltCfg;
        if (nativeCfg == null) {
        try {
            nativeCfg = ControlFlowAnalyzer.build(pctx.instructions);
            if (nativeCfg == null || nativeCfg.blocks.size() <= 1) {
                nativeCfg = null;
            }
        } catch (Throwable t) {
            nativeCfg = null;
        }
        }

        // ── Stage 4b2: 跳转表解析 (v3.9) ──
        // 从 so 直读 switch 跳转表 (tbb/tbh / AArch64 adr+ldr+br / ARM32 内联 b 表),
        // 结果存 ctx.jumpTables 供 ControlFlowStructurer 融合。任何失败/无 soPath
        // 都返回空表 — 不影响现有结构化 (间接跳转保持原行为)。
        try {
            long funcMax = 0;
            if (!insns.isEmpty()) {
                funcMax = insns.get(insns.size() - 1).addr;
            }
            ctx.jumpTables.putAll(JumpTableResolver.resolve(
                    insns, pctx.soPath, pctx.functionAddress, funcMax));
        } catch (Throwable t) {
            // 忽略 — 跳转表解析失败不改变现有输出
        }
        ControlFlowStructurer structurer = new ControlFlowStructurer(insns, ctx, nativeCfg);
        structurer.structure();
        List<String> body = structurer.getOutput();

        // ── Stage 4a: SSA-based optimization (v2.9.38) ──
        // SSA construction: variable versioning → global constant propagation
        // → global dead code elimination → type propagation → SSA deconstruction.
        // This is the key pass that brings us to r2ghidra-level precision:
        // def-use chains enable global DCE and cross-block type inference.
        body = SsaConverter.optimize(body, ctx.isAarch64);

        // ── Stage 4b: Structure recovery (v2.9.38) ──
        // Cluster memory accesses by base address: *(type*)(x10 + 0x8) →
        // v_x10->field_8. Turns raw offset soup into readable struct fields.
        body = StructRecoveryPass.recover(body);

        // ── Stage 4c: Switch-case detection (v2.9.38) ──
        // Detect jump table patterns: 3+ consecutive if(reg==const) goto
        // → switch(reg) { case const: goto label; ... }
        body = SwitchCasePass.detect(body);

        // ── Stage 4d: Peephole optimization (post-processing) ──
        // Removes empty branches, folds adjacent constant assignments, does
        // local dead-store elimination, and merges adjacent if/!if into
        // if-else. Runs to a fixed point. See {@link PeepholeOptimizer}.
        body = PeepholeOptimizer.optimize(body);

        // ── Stage 4d2: 孤立标签清理 (v4.1) — Peephole 简化删掉 goto 后,
        // 无引用的 L_xxx: 标签残留会被移除. ──
        body = ControlFlowStructurer.removeOrphanLabels(body);

        // ── Stage 4d3: 输出标签清理 (v4.9) — 消除裸地址标签 (L_1b4c 等):
        // 折叠 if-then 内跳转板、内联单前驱尾块, 剩余必需标签 (循环头/汇合点)
        // 语义命名 loop_N / merge_N. ──
        body = LabelCleanupPass.optimize(body);

        // ── Stage 4e: Variable renaming — saved_lr, saved_x19, etc. ──
        body = VariableRenamer.rename(body, insns, ctx.isAarch64);

        // ── Stage 4e2: Unused local elimination (v3.3) ──
        // 消除"赋值但从未读取"的局部变量 — 寄存器无关化的关键: 编译器
        // 的临时存储 (str/ldr 栈往返) 留下死赋值, 这里按 def-use 消除.
        body = UnusedLocalEliminationPass.optimize(body);

        // ── Stage 4e3: (removed) Magic number annotations — v4.3 精简删除 ──

        // ── Stage 4e4: JNI vtable call resolution (v4.2) ──
        // JNI 调用接口间接调用 ((*)(*(r1+0x18)))(r0, ...) → (*r0)->GetEnv(r0, ...)
        // v4.3: 参数类型直接来自 native 结构化签名 (按序映射到 r_i/x_i),
        // 不再依赖已删除的 ParameterInferencePass 填充 paramTypeMap.
        Map<String, String> jniParamTypes = new HashMap<>();
        if (ctx.nativeParamTypes != null) {
            for (int i = 0; i < ctx.nativeParamTypes.size(); i++) {
                String t = ctx.nativeParamTypes.get(i);
                if (t == null || t.isEmpty()) continue;
                jniParamTypes.put(ctx.isAarch64 ? "x" + i : "r" + i, t);
            }
        }
        for (DecompContext.VarInfo v : ctx.argVars) {
            if (!jniParamTypes.containsKey(v.name)) {
                String t = ctx.paramTypeMap.get(v.name);
                if (t == null) t = v.type;
                jniParamTypes.put(v.name, t);
            }
        }
        body = JniVtablePass.run(body, jniParamTypes);

        // ── Stage 4e5: Semantic variable naming (v4.2) ──
        // JNI ABI 固定的栈槽语义名 (GetEnv 第 2 参数 = &env).
        VariableSemanticsPass.RenameResult renameResult = VariableSemanticsPass.run(body);
        body = renameResult.body;

        // ── Stage 4f: Temp register variable collection ──
        // v2.9.37: Declare temporary registers (w8, x9, ...) as local variables
        Set<String> argVarNames = new HashSet<>();
        for (DecompContext.VarInfo v : ctx.argVars) {
            argVarNames.add(v.name);
        }
        // v4.3: 删 StackFrameAnalyzer 后 argVars 不再填充 — native 签名参数
        // 对应的寄存器 (r0..rN / x0..xN) 视为已声明参数, 避免 TempVarCollector
        // 把它们收集成临时变量 (void* r0; 会被 ParamSemanticsPass 替换成
        // void* vm; 与签名参数重名).
        if (ctx.nativeParamTypes != null) {
            for (int i = 0; i < ctx.nativeParamTypes.size(); i++) {
                argVarNames.add(ctx.isAarch64 ? "x" + i : "r" + i);
            }
        }
        List<String> tempDecls = TempVarCollector.collectDeclarations(
                body, ctx.isAarch64, argVarNames);

        // ── Stage 5: Assemble final output ──
        List<String> assembled = applyRenames(printResult(pctx, ctx, body, tempDecls), renameResult.renames);
        // v4.6: 语义变量声明类型修正 (GetEnv 的 env 槽 → JNIEnv*), 作用于声明行
        assembled = applyTypeOverrides(assembled, renameResult.typeOverrides);
        // Stage 5b: JNI 参数语义化 — JavaVM* 参数引入 vm 别名 (v4.2)
        List<String> finalOut = ParamSemanticsPass.run(assembled);
        // v4.8: 硬性防线 — 伪 C 输出中绝不允许出现 sp/pc/fp 等底层寄存器
        // 变量 (对齐 IDA 抽象: 栈指针/帧指针/程序计数器被消除或合并).
        // 含裸特殊寄存器 token 的行 (非 saved_/var_ 前缀) 整行删除: 该行
        // 语义依赖不可追踪的底层寄存器, 保留即泄漏实现细节.
        return stripRawSpecialRegs(finalOut);
    }

    /**
     * v4.10: Stage 1 — 构建 IrInsn 列表 (保持指令顺序与 idx).
     */
    private static List<IrInsn> buildInsns(PseudoCContext pctx) {
        List<IrInsn> insns = new ArrayList<>();
        for (int i = 0; i < pctx.instructions.size(); i++) {
            DisassembledInstruction d = pctx.instructions.get(i);
            IrInsn ir = new IrInsn(d);
            ir.idx = i;
            insns.add(ir);
        }
        return insns;
    }

    /**
     * v4.10: 创建 DecompContext 并透传全部符号/签名/分析数据 (Stage 1 后半 + Stage 2).
     */
    private static DecompContext buildCtx(PseudoCContext pctx) {
        Map<Long, String> calleeMap = new HashMap<>();
        if (pctx.labels != null) {
            calleeMap.putAll(pctx.labels);
        }
        if (pctx.imports != null) {
            calleeMap.putAll(pctx.imports);
        }

        DecompContext ctx = new DecompContext(
                pctx.machine, pctx.isThumb, pctx.labels, pctx.imports);
        ctx.calleeMap = calleeMap;
        ctx.funcName = pctx.functionName;
        ctx.funcAddr = pctx.functionAddress;

        // v2.9.35: Pass through extended symbol/type data from PseudoCContext
        if (pctx.signatureMap != null) ctx.signatureMap = pctx.signatureMap;
        // v4.6: 函数地址 → 结构化签名 (调用点参数个数对齐)
        if (pctx.signatureByAddr != null) ctx.signatureByAddr = pctx.signatureByAddr;
        if (pctx.symbolMap != null) ctx.symbolMap = pctx.symbolMap;
        // v4.0: 字符串地址→内容 (findString 用, 缺失时字符串识别降级为 referencedString)
        if (pctx.stringMap != null && !pctx.stringMap.isEmpty()) {
            ctx.stringMap.putAll(pctx.stringMap);
        }
        if (pctx.globalVarTypes != null) ctx.globalVarTypes = pctx.globalVarTypes;
        if (pctx.globalVarNames != null) ctx.globalVarNames = pctx.globalVarNames;
        if (pctx.funcSignature != null) ctx.funcSignature = pctx.funcSignature;
        // v3.3.1: 透传 native 结构化签名 (结构化数据, 非字符串)
        if (pctx.nativeParamTypes != null) ctx.nativeParamTypes = pctx.nativeParamTypes;
        if (pctx.nativeParamNames != null) ctx.nativeParamNames = pctx.nativeParamNames;
        if (pctx.nativeRetType != null) ctx.nativeRetType = pctx.nativeRetType;
        if (pctx.nativeParamFloat != null) ctx.nativeParamFloat = pctx.nativeParamFloat;
        if (pctx.nativeParamWide != null) ctx.nativeParamWide = pctx.nativeParamWide;
        if (pctx.nativeParamStackOffsets != null) ctx.nativeParamStackOffsets = pctx.nativeParamStackOffsets;
        ctx.nativeNoreturn = pctx.nativeNoreturn;
        ctx.nativeVariadic = pctx.nativeVariadic;
        ctx.nativeIsStatic = pctx.nativeIsStatic;
        if (pctx.demangledNames != null) ctx.demangledNames = pctx.demangledNames;

        // v4.1: so 分析工具已产出数据的完整接入 (全部可空, 缺失时消费点降级)
        if (pctx.relocSymbols != null) ctx.relocSymbols = pctx.relocSymbols;
        if (pctx.neededLibraries != null) ctx.neededLibraries = pctx.neededLibraries;
        if (pctx.insnStringRefs != null && !pctx.insnStringRefs.isEmpty()) {
            ctx.insnStringRefs = pctx.insnStringRefs;
        }
        if (pctx.dataConstants != null && !pctx.dataConstants.isEmpty()) {
            ctx.dataConstants = pctx.dataConstants;
        }
        if (pctx.vtableEntries != null && pctx.vtableEntries.length > 0) {
            ctx.vtableEntries = pctx.vtableEntries;
        }
        if (pctx.callerMaxRegs != null && !pctx.callerMaxRegs.isEmpty()) {
            ctx.callerMaxRegs = pctx.callerMaxRegs;
        }

        // ── Stage 2: 分析数据直接来自 native 侧 (afvj 栈变量 / 结构化签名 /
        //    nativeRegType / native CFG), 不再做 Java 自制分析 — 见 v4.3 精简 ──
        ctx.isAarch64 = (pctx.machine == EM_AARCH64);
        return ctx;
    }

    /**
     * v4.10: Stage 3 — 逐指令 handler 翻译. 单指令异常降级为注释, 不中断整体.
     */
    private static void translateInsns(List<IrInsn> insns, DecompContext ctx) {
        InstructionHandler handler;
        if (ctx.isAarch64) {
            handler = new Arm64Handler();
        } else {
            handler = new Arm32Handler();
        }
        for (int i = 0; i < insns.size(); i++) {
            IrInsn ir = insns.get(i);
            try {
                List<IrNode> nodes = handler.handle(ir, ctx, insns);
                if (nodes != null) {
                    ir.nodes.addAll(nodes);
                }
            } catch (Throwable t) {
                ir.nodes.add(new IrNode.Raw(
                        "/* handler error: " + t.getClass().getSimpleName()
                                + " at 0x" + Long.toHexString(ir.addr)
                                + " (" + ir.mnemonic + " " + ir.opStr + ") */"));
            }
        }
    }

    /**
     * v4.8: 兜底清理 — 删除含裸特殊寄存器 token (sp/pc/fp/bp/lr/r11/r13-r15/
     * x29/x30 等) 的**纯语句行**。saved_fp/saved_lr/saved_x29 等抽象化变量名
     * 不受影响。结构行 (if/while/for/return/标签/括号) 保留 — 破坏结构比
     * 保留泄漏更糟; 结构行泄漏由源头 (handler 抽象化) 保证不产生。
     */
    private static final java.util.regex.Pattern RAW_SPECIAL_REG =
            java.util.regex.Pattern.compile(
                    "(?<![0-9a-zA-Z_>])(?:sp|pc|fp|bp|sl|lr|r11|r13|r14|r15|x29|w29|x30|w30)(?!\\d)");

    /** 结构行: 以结构关键字/括号开头, 或形如 label: 的标签行. */
    private static boolean isStructuralLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return false;
        if (t.startsWith("if") || t.startsWith("while") || t.startsWith("for")
                || t.startsWith("do") || t.startsWith("switch") || t.startsWith("case")
                || t.startsWith("default") || t.startsWith("return")
                || t.startsWith("break") || t.startsWith("continue")
                || t.startsWith("goto") || t.startsWith("else")
                || t.startsWith("{") || t.startsWith("}")) {
            return true;
        }
        // 标签行: 行尾冒号且无赋值/调用 (label: / case N:)
        if (t.endsWith(":") && !t.contains("=") && !t.contains("(")) return true;
        return false;
    }

    static List<String> stripRawSpecialRegs(List<String> lines) {
        if (lines == null || lines.isEmpty()) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null && RAW_SPECIAL_REG.matcher(line).find()) {
                if (!isStructuralLine(line)) {
                    continue; // 纯语句行含裸特殊寄存器 — 删除 (死语义无损)
                }
            }
            out.add(line);
        }
        return out;
    }

    /**
     * v4.6: 把语义变量的声明行类型替换为语义类型 (如 {@code uint64_t env;} →
     * {@code JNIEnv* env;}). 仅匹配声明行 (TYPE NAME;), body 赋值不受影响.
     */
    private static List<String> applyTypeOverrides(List<String> lines, Map<String, String> typeOverrides) {
        if (typeOverrides == null || typeOverrides.isEmpty()) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String l = line;
            for (Map.Entry<String, String> e : typeOverrides.entrySet()) {
                String name = Pattern.quote(e.getKey());
                if (l.matches("\\s*[A-Za-z_][A-Za-z0-9_ \\t*]*?\\s+" + name + ";\\s*")) {
                    l = l.replaceFirst("^(\\s*)[A-Za-z_][A-Za-z0-9_ \\t*]*?(\\s+)" + name + ";",
                            "$1" + e.getValue() + "$2" + e.getKey() + ";");
                }
            }
            out.add(l);
        }
        return out;
    }

    /** Apply semantic renames to the final output (covers declarations too). */
    private static List<String> applyRenames(List<String> lines, Map<String, String> renames) {
        if (renames == null || renames.isEmpty()) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String l = line;
            for (Map.Entry<String, String> e : renames.entrySet()) {
                l = l.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
            }
            out.add(l);
        }
        return out;
    }

    // ── Output assembly ──

    /**
     * Assemble the final pseudo-C output.
     * <p>Produces:
     * <ol>
     *   <li>Header comment (function name, address, architecture)</li>
     *   <li>Function signature with arguments from {@code ctx.argDeclarations()}</li>
     *   <li>Local variable declarations from {@code ctx.localVarDeclarations()}</li>
     *   <li>Body lines (each prefixed with 4-space indent)</li>
     *   <li>Closing brace</li>
     * </ol>
     *
     * @param pctx the original pseudo-C context
     * @param ctx  the decompilation context (with declarations)
     * @param body the structured body lines from Stage 4
     * @return the final pseudo-C output
     */
    private List<String> printResult(PseudoCContext pctx,
                                     DecompContext ctx,
                                     List<String> body,
                                     List<String> tempDecls) {
        List<String> result = new ArrayList<>();

        // Determine function name
        String fname = pctx.functionName;
        if (fname == null || fname.isEmpty()) {
            fname = "sub_" + Long.toHexString(pctx.functionAddress);
        }
        // v3.4.2: mangled C++ 名优先 demangle 显示 (如
        //   _ZN9Minecraft11getGameModeEv → Minecraft::getGameMode),
        //   demangle 失败时保持原样; 展示用名保留 :: (r2dec 同款).
        fname = demangleForDisplay(fname);

        // Determine architecture string
        String arch;
        if (pctx.machine == EM_AARCH64) {
            arch = "AArch64";
        } else if (pctx.isThumb) {
            arch = "ARM/Thumb";
        } else {
            arch = "ARM";
        }

        // Header comment
        result.add("// === R2Dec pseudo-C ===");
        result.add("// 函数: " + fname
                + "  地址: 0x" + Long.toHexString(pctx.functionAddress)
                + "  架构: " + arch);

        // v3.3: aflj 数据模型 — 函数统计 (bbs/callrefs/datarefs, 对齐 r2 aflj)
        try {
            StringBuilder stats = new StringBuilder("// 统计: ");
            boolean any = false;
            if (pctx.bbCount > 0) { stats.append("bbs=").append(pctx.bbCount); any = true; }
            if (pctx.callRefs > 0) { if (any) stats.append("  "); stats.append("callrefs=").append(pctx.callRefs); any = true; }
            if (pctx.dataRefs > 0) { if (any) stats.append("  "); stats.append("datarefs=").append(pctx.dataRefs); any = true; }
            if (any) result.add(stats.toString());
        } catch (Throwable t) {
            // 统计字段缺失时静默跳过
        }

        // v3.5: libc 头文件声明 (对齐 r2dec db/macros.js) — 扫描 body 中已识别的调用
        List<String> includes = LibcCallDb.scanIncludes(body);
        for (String inc : includes) {
            result.add("#include " + inc);
        }

        // Function signature
        // v3.4.1: 优先消费 native 结构化签名 (nativeRetType/nativeParamTypes),
        //         缺失时降级到寄存器推断 (returnType()/argDeclarations()).
        String retType = (ctx.nativeRetType != null && !ctx.nativeRetType.isEmpty())
                ? ctx.nativeRetType : ctx.returnType();
        StringBuilder sig = new StringBuilder();
        if (ctx.nativeIsStatic) sig.append("static ");
        sig.append(retType).append(" ").append(fname).append("(");
        List<String> args = nativeArgDeclarations(ctx);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(args.get(i));
        }
        if (ctx.nativeVariadic) {
            if (!args.isEmpty()) sig.append(", ");
            sig.append("...");
        }
        sig.append(") {");
        result.add(sig.toString());

        // Local variable declarations
        List<String> locals = ctx.localVarDeclarations();
        // v3.2.16: 空行分隔签名与局部声明，提升可读性
        boolean hasDecls = !locals.isEmpty()
                || (tempDecls != null && !tempDecls.isEmpty());
        if (hasDecls) {
            result.add("");
        }
        // v2.9.42: Track variable names already declared by locals to
        // prevent TempVarCollector from re-declaring them (which caused
        // duplicate declarations and type conflicts like:
        //   uint32_t var_14;  (from locals, correct type)
        //   uint64_t var_14;  (from TempVarCollector, wrong type)
        Set<String> declaredNames = new HashSet<>();
        for (int i = 0; i < locals.size(); i++) {
            result.add("    " + locals.get(i));
            // Extract variable name from "type name;" declaration
            String decl = locals.get(i).trim();
            // Remove trailing ; and split
            String[] parts = decl.replace(";", "").trim().split("\\s+");
            if (parts.length >= 2) {
                declaredNames.add(parts[parts.length - 1]);
            }
        }

        // v2.9.37: Temp register declarations (w8, x9, etc.)
        // v2.9.42: Skip any declaration whose variable name is already
        // declared by locals (prevents duplicates and type conflicts).
        if (tempDecls != null) {
            for (int i = 0; i < tempDecls.size(); i++) {
                String decl = tempDecls.get(i).trim();
                // Extract variable name from "type name;" or "type name[2];"
                String[] parts = decl.replace(";", "").replace("[", " ").trim().split("\\s+");
                if (parts.length >= 2) {
                    String varName = parts[parts.length - 1];
                    if (declaredNames.contains(varName)) {
                        continue; // Skip duplicate
                    }
                    // v4.3: 签名参数名 (vm/reserved/... 语义名) 不是局部变量,
                    // 跳过, 避免与签名参数重名遮蔽
                    if (ctx.nativeParamNames != null && ctx.nativeParamNames.contains(varName)) {
                        continue;
                    }
                    declaredNames.add(varName);
                }
                result.add("    " + tempDecls.get(i));
            }
        }

        // v3.2.16: 局部声明与函数体之间加空行
        if (hasDecls && !body.isEmpty()) {
            result.add("");
        }

        // Body lines with 4-space indent
        // v3.5: 组装前先重建 body 缩进 (按大括号深度, 注释/空行/标签行不计数)
        body = IndentFixPass.fix(body);
        // v3.4.1: 非 void 函数兜底 — 残留的裸 "return;" (来自
        //   ControlFlowStructurer.simplifyTailGoto / PeepholeOptimizer 的
        //   goto→return 简化) 补上返回寄存器: return r0;/x0;.
        String retRegFallback = null;
        if (!retType.equals("void")) {
            retRegFallback = (ctx.retReg != null) ? ctx.retReg
                    : (pctx.machine == EM_AARCH64 ? "x0" : "r0");
        }
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (retRegFallback != null) {
                String trimmed = line.trim();
                if (trimmed.equals("return;")) {
                    int ind = line.length() - trimmed.length();
                    line = line.substring(0, ind) + "return " + retRegFallback + ";";
                }
            }
            result.add("    " + line);
        }

        // Closing brace
        result.add("}");

        // ── v3.3: r2 afvj 统一变量视图 + aaef computed xrefs (native 分析结果) ──
        // 显示在函数体后, 供用户核对变量/引用 (r2dec 正版不显示, 属 ExplorerSo 增强)
        boolean hasAfvj = !ctx.afvjReg.isEmpty() || !ctx.afvjSp.isEmpty() || !ctx.afvjBp.isEmpty();
        boolean hasXref = !ctx.computedXrefs.isEmpty();
        if (hasAfvj || hasXref) {
            result.add("");
            if (hasAfvj) {
                result.add("// ── 变量视图 (afvj) ──");
                for (AfvjVarEntry e : ctx.afvjReg) {
                    result.add("//   reg " + e.name + " : " + e.type
                            + "  reads=" + e.reads + " writes=" + e.writes
                            + "  ref=0x" + Long.toHexString(e.refAddr));
                }
                for (AfvjVarEntry e : ctx.afvjSp) {
                    result.add("//   sp  " + e.name + " : " + e.type
                            + "  delta=" + e.delta
                            + "  reads=" + e.reads + " writes=" + e.writes
                            + "  ref=0x" + Long.toHexString(e.refAddr));
                }
                for (AfvjVarEntry e : ctx.afvjBp) {
                    result.add("//   bp  " + e.name + " : " + e.type
                            + "  delta=" + e.delta
                            + "  reads=" + e.reads + " writes=" + e.writes
                            + "  ref=0x" + Long.toHexString(e.refAddr));
                }
            }
            if (hasXref) {
                result.add("// ── 计算引用 (aaef) ──");
                for (ComputedXrefEntry e : ctx.computedXrefs) {
                    result.add("//   0x" + Long.toHexString(e.insnAddr)
                            + "  " + e.kind.name().toLowerCase(Locale.ROOT)
                            + " → 0x" + Long.toHexString(e.target)
                            + "  via " + e.viaReg);
                }
            }
        }

        return result;
    }

    /**
     * v3.4.1: 参数声明 — 优先 native 结构化签名 (nativeParamTypes + names),
     * 缺失时降级到 DecompContext 的寄存器推断 argDeclarations().
     */
    private static List<String> nativeArgDeclarations(DecompContext ctx) {
        List<String> decls = new ArrayList<>();
        List<String> nativeTypes = ctx.nativeParamTypes;
        if (nativeTypes != null && !nativeTypes.isEmpty()) {
            List<String> nativeNames = ctx.nativeParamNames;
            for (int i = 0; i < nativeTypes.size(); i++) {
                String t = nativeTypes.get(i);
                if (t == null || t.isEmpty()) continue;
                String n = (nativeNames != null && i < nativeNames.size())
                        ? nativeNames.get(i) : null;
                if (n == null || n.isEmpty()) {
                    n = "arg_" + i;
                }
                decls.add(t + " " + n);
            }
            return decls;
        }
        return ctx.argDeclarations();
    }

    // ── Fallback ──

    /**
     * Fallback: produce a raw disassembly listing when decompilation fails.
     *
     * @param pctx the original pseudo-C context
     * @param t    the error that triggered the fallback
     * @return raw disassembly as comment lines inside a function body
     */
    private List<String> fallbackRaw(PseudoCContext pctx, Throwable t) {
        List<String> r = new ArrayList<>();
        r.add("// R2Dec 反编译失败: " + t.getClass().getSimpleName()
                + " - " + (t.getMessage() != null ? t.getMessage() : ""));
        r.add("// 退化为原始反汇编列表:");

        String fname = pctx.functionName;
        if (fname == null || fname.isEmpty()) {
            fname = "sub_" + Long.toHexString(pctx.functionAddress);
        }
        fname = sanitizeName(fname);

        r.add("void " + fname + "() {");
        if (pctx.instructions != null) {
            for (int i = 0; i < pctx.instructions.size(); i++) {
                DisassembledInstruction d = pctx.instructions.get(i);
                String mn = d.mnemonic != null ? d.mnemonic : "";
                String op = d.opStr != null ? d.opStr : "";
                r.add("    // 0x" + Long.toHexString(d.address)
                        + ": " + mn + " " + op);
            }
        }
        r.add("}");
        return r;
    }

    // ── Utilities ──

    /**
     * v3.2.16: 调 native 层 noreturn 传播 pass, 把 suppressed 标记写回
     * {@code ir.valid}。native 不可用 / 抛错时静默跳过 (Java fallback 策略是
     * 不消除 unreachable, 让用户多看一些可能无意义的代码)。
     *
     * @param insns 当前函数的 IR 指令列表 (顺序与 native 输入一一对应)
     * @param raw   原始 DisassembledInstruction 列表 (native 输入来源)
     */
    private static void applyNoreturnPropagation(List<IrInsn> insns,
                                                 List<DisassembledInstruction> raw) {
        if (!NativeBridge.isSupported() || insns == null || raw == null) return;
        if (insns.isEmpty() || raw.isEmpty()) return;
        try {
            Object[] insnArr = toInsnInfoArray(raw);
            boolean[] suppressed = NativeBridge.markNoreturnUnreachable(insnArr);
            if (suppressed == null || suppressed.length != insns.size()) return;
            for (int i = 0; i < insns.size(); i++) {
                if (suppressed[i]) {
                    insns.get(i).valid = false;
                }
            }
        } catch (Throwable t) {
            // native 库未加载 / 函数未链接 — 静默跳过, 不影响主流程
        }
    }

    /**
     * v3.2.16: 调 native afvj + aaef pass, 把结果存入 ctx.
     * native 不可用 / 抛错时静默跳过.
     */
    private static void applyAnalysisPasses(List<IrInsn> insns,
                                            List<DisassembledInstruction> raw,
                                            DecompContext ctx) {
        if (!NativeBridge.isSupported() || insns == null || raw == null || ctx == null) return;
        if (insns.isEmpty()) return;
        try {
            Object[] insnArr = toInsnInfoArray(raw);
            // afvj 三分类 → 存到 ctx (AfvjVarEntry 是按 kind 分类的列表)
            Object[][] afvjArr = NativeBridge.buildAfvj(insnArr);
            if (afvjArr != null) {
                ctx.afvjSp.clear();
                ctx.afvjBp.clear();
                ctx.afvjReg.clear();
                for (Object row : afvjArr) {
                    if (!(row instanceof Object[])) continue;
                    Object[] r = (Object[]) row;
                    if (r.length < 7) continue;
                    int kind = (Integer) r[0];
                    AfvjVarEntry e = new AfvjVarEntry(
                            (String) r[1],   // name
                            (String) r[2],   // type
                            (Integer) r[3],  // delta
                            ((Long) r[6]).longValue(),  // refAddr
                            (Integer) r[4],  // reads
                            (Integer) r[5]); // writes
                    if (kind == 0)      ctx.afvjReg.add(e);
                    else if (kind == 1) ctx.afvjSp.add(e);
                    else if (kind == 2) ctx.afvjBp.add(e);
                }
            }
            // aaef computed xrefs → 存到 ctx
            Object[][] xrefArr = NativeBridge.analyzeDataFlow(insnArr);
            if (xrefArr != null) {
                ctx.computedXrefs.clear();
                for (Object row : xrefArr) {
                    if (!(row instanceof Object[])) continue;
                    Object[] r = (Object[]) row;
                    if (r.length < 4) continue;
                    int kind = (Integer) r[0];
                    ComputedXrefEntry.Kind k;
                    switch (kind) {
                        case 0: k = ComputedXrefEntry.Kind.CALL; break;
                        case 1: k = ComputedXrefEntry.Kind.JUMP; break;
                        case 2: k = ComputedXrefEntry.Kind.DATA_READ; break;
                        default: k = ComputedXrefEntry.Kind.DATA_WRITE; break;
                    }
                    ctx.computedXrefs.add(new ComputedXrefEntry(
                            k,
                            ((Long) r[1]).longValue(),  // insnAddr
                            ((Long) r[2]).longValue(),  // target
                            (String) r[3]));             // viaReg
                }
            }
        } catch (Throwable t) {
            // 静默跳过
        }
    }

    /** v3.2.16: afvj 单条变量 (native 端 AfvjVar 的 Java 镜像). */
    public static class AfvjVarEntry {
        public final String name;
        public final String type;
        public final int delta;
        public final long refAddr;
        public final int reads;
        public final int writes;
        public AfvjVarEntry(String name, String type, int delta, long refAddr,
                            int reads, int writes) {
            this.name = name; this.type = type; this.delta = delta;
            this.refAddr = refAddr; this.reads = reads; this.writes = writes;
        }
    }

    /** v3.2.16: aaef computed xref (native 端 ComputedXref 的 Java 镜像). */
    public static class ComputedXrefEntry {
        public enum Kind { CALL, JUMP, DATA_READ, DATA_WRITE }
        public final Kind kind;
        public final long insnAddr;
        public final long target;
        public final String viaReg;
        public ComputedXrefEntry(Kind kind, long insnAddr, long target, String viaReg) {
            this.kind = kind; this.insnAddr = insnAddr; this.target = target; this.viaReg = viaReg;
        }
    }

    /**
     * 将 DisassembledInstruction[] 转为 native ndk_insn_t 风格的 Object[][].
     * 字段顺序 (与 nativeAnalyzeControlFlow 兼容):
     *   [0] Long addr
     *   [1] Integer size
     *   [2] byte[] bytes (native 不读, 传 null 也行)
     *   [3] String mnemonic
     *   [4] String opStr
     *   [5] Boolean bbLeader (false)
     */
    private static Object[] toInsnInfoArray(List<DisassembledInstruction> raw) {
        Object[] rows = new Object[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            DisassembledInstruction d = raw.get(i);
            Object[] row = new Object[6];
            row[0] = d.address;
            row[1] = (d.bytes != null) ? d.bytes.length : 0;
            row[2] = d.bytes;
            row[3] = d.mnemonic;
            row[4] = d.opStr;
            row[5] = Boolean.FALSE;
            rows[i] = row;
        }
        return rows;
    }

    /**
     * v3.4.2: mangled C++ 符号名 → 可读 demangled 名 (去掉参数列表, 只留
     * 函数签名名, 如 "Minecraft::getGameMode()" → "Minecraft::getGameMode").
     * 非 mangled 名 / native 不可用 / 解析失败时原样返回.
     */
    private String demangleForDisplay(String name) {
        if (name == null || name.isEmpty()) return name;
        if (!name.startsWith("_Z") || name.length() >= 512) return name;
        try {
            if (!NativeBridge.isSupported()) return name;
            String dm = NativeBridge.demangle(name);
            if (dm != null && !dm.equals(name) && dm.contains("(")) {
                return dm.substring(0, dm.indexOf('(')).trim();
            }
        } catch (Throwable ignored) {
        }
        // v4.9: native 解析失败 → Java 轻量 Itanium demangler 强制兜底
        // (如 _ZNK17AttributeInstance15getCurrentValueEv 在 __cxa_demangle
        // 拒绝时仍可解出 AttributeInstance::getCurrentValue)
        String dm = ItaniumDemangler.demangle(name);
        if (dm != null) {
            int p = dm.indexOf('(');
            return p >= 0 ? dm.substring(0, p).trim() : dm.trim();
        }
        return name;
    }

    /**
     * Sanitize a name for use as a C identifier.
     * <p>Replaces all non-alphanumeric characters (except underscore) with
     * underscores, and prepends an underscore if the result starts with a digit.
     *
     * @param name the raw name (may contain dots, dashes, etc.)
     * @return a valid C identifier
     */
    private String sanitizeName(String name) {
        if (name == null || name.isEmpty()) {
            return "unnamed";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0) {
            return "unnamed";
        }
        if (Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
