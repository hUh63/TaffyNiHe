package com.exbin.app.elf.pseudoc.r2dec;

import com.exbin.app.elf.FunctionInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-function decompilation context. Holds condition state (for
 * {@code cmp}+{@code b.cc}/{@code csel}), per-block register-marker tracking,
 * the local/argument variable tables and the metadata used by the ARM/AArch64
 * instruction handlers.
 */
public class DecompContext {

    // ── Condition state (set by cmp, consumed by b.cc / csel) ──
    public String condA;
    public String condB;
    public String condType;

    // ── Per-block register marker tracking ──
    public final Map<Integer, Map<String, MarkerData>> markers = new HashMap<>();

    // ── Return register (null => void) ──
    public String retReg;

    // ── Variable tables ──
    public final List<VarInfo> localVars = new ArrayList<>();
    public final List<VarInfo> argVars = new ArrayList<>();
    public final Set<String> localVarNames = new HashSet<>();

    // ── Stack variable mapping: "sp+0x10" / "x29+0x8" → "var_10" ──
    public final Map<String, String> stackVarMap = new HashMap<>();
    public final Set<String> usedStackVars = new HashSet<>();

    // ─ v3.5: 栈指针别名表: reg → 栈基址(sp/x29/fp) + 偏移.
    //   由 handler 在 add/sub/mov 建立, 内存访问/调用参数时折叠回栈变量.
    public final Map<String, StackAlias> spAliases = new HashMap<>();

    // ── adrp+add marker tracking: reg → page address (per instruction index) ──
    public final Map<String, Long> adrpPages = new HashMap<>();
    public final Map<String, Long> resolvedAddrs = new HashMap<>();

    // ── Register type inference: reg → inferred C type (e.g. "void*") ──
    public final Map<String, String> regTypeMap = new HashMap<>();

    // ─ v2.9.34: Inferred return type (overrides returnType() when set) ──
    public String inferredReturnType;

    // ─ v2.9.34: Per-parameter override types (reg name → C type).
    //   Filled by ParameterInferencePass from known signatures.
    //   Takes priority over regTypeMap for argument declarations. ──
    public final Map<String, String> paramTypeMap = new HashMap<>();

    // ── Function pointer tracking: reg → source expression (for blx rN calls) ──
    public final Map<String, String> funcPtrSource = new HashMap<>();

    // ── Labels / imports / function metadata ──
    public Map<Long, String> labels = new HashMap<>();
    public Map<Long, String> imports = new HashMap<>();
    public Map<Long, String> calleeMap = new HashMap<>();
    public String funcName;
    public long funcAddr;
    public boolean isAarch64;
    public boolean isThumb;
    public List<IrInsn> instructions;

    // ── v2.9.35: 符号表 + 类型推断扩展 ──

    /** 函数名 → C 签名字符串, 来自 NativeBridge.lookupKnownSig / restoreSignatures */
    public Map<String, String> signatureMap = new HashMap<>();

    /**
     * v4.6: 函数地址 → FunctionInfo (native 结构化签名已还原),
     * 调用点参数个数/返回类型对齐用. 按地址匹配绕开 demangle/命名差异.
     */
    public Map<Long, FunctionInfo> signatureByAddr;

    /** 地址 → 符号名 (统一), 用于解析全局变量和 sub_XXXX */
    public Map<Long, String> symbolMap = new HashMap<>();

    /** v4.0: 字符串地址 → 内容 (r2 Csj 等价物), 供 findString 解析字符串字面量. */
    public Map<Long, String> stringMap = new HashMap<>();

    /** 全局变量地址 → 类型字符串 */
    public Map<Long, String> globalVarTypes = new HashMap<>();

    /** 全局变量地址 → 符号名 */
    public Map<Long, String> globalVarNames = new HashMap<>();

    /** 当前函数的预还原签名 (来自 nativeRestoreSignatures) */
    public String funcSignature;

    // ── v3.3.1: native 结构化签名 — native 层分析结果, r2dec 直接消费,
    //            不再 parseToSignature 字符串 (与 funcSignature 同源, 更精确) ──
    /** native 分析的返回类型 (null = 未提供, 走原推断) */
    public String nativeRetType;
    /** native 分析的参数类型列表 (下标与 float/wide/stackOffsets 对齐; null = 未提供) */
    public List<String> nativeParamTypes;
    /** native 分析的参数名 (可 null) */
    public List<String> nativeParamNames;
    /** 各参数是否浮点寄存器传参 */
    public boolean[] nativeParamFloat;
    /** 各参数是否 64 位宽类型 */
    public boolean[] nativeParamWide;
    /** 各参数的栈偏移 (sp 基) */
    public int[] nativeParamStackOffsets;
    /** 函数是否 noreturn (调用后不再返回) */
    public boolean nativeNoreturn;
    /** 函数是否变参 */
    public boolean nativeVariadic;
    /** 函数是否静态 (仅内部调用) */
    public boolean nativeIsStatic;

    /** mangled → demangled 名映射 */
    public Map<String, String> demangledNames = new HashMap<>();

    // ── v3.2.16: r2 afvj 统一变量视图 (native 层填充) ──
    // 三个 List 分别对应 r2 的 {reg[], sp[], bp[]}, 每条 AfvjVarEntry 含
    // name/type/delta/reads/writes/refAddr.
    public final List<R2DecPseudoC.AfvjVarEntry> afvjReg = new ArrayList<>();
    public final List<R2DecPseudoC.AfvjVarEntry> afvjSp  = new ArrayList<>();
    public final List<R2DecPseudoC.AfvjVarEntry> afvjBp  = new ArrayList<>();

    // ── v3.2.16: r2 aaef 简化版 — 计算的间接引用 (native 层填充) ──
    public final List<R2DecPseudoC.ComputedXrefEntry> computedXrefs = new ArrayList<>();

    // ── v3.9: 跳转表解析结果 — jumpAddr → JumpTable (Stage 4 前由
    //          JumpTableResolver 填充, ControlFlowStructurer 消费) ──
    public final Map<Long, JumpTableResolver.JumpTable> jumpTables = new HashMap<>();

    // ── v4.1: so 分析工具已产出数据的完整接入 (全部可空, 缺失时消费点降级) ──

    /** 重定位位置 → 符号名 (GOT/数据地址解析用) */
    public Map<Long, String> relocSymbols;

    /** 依赖库列表 (DT_NEEDED) — 数据就绪, 供未来库识别/签名分析 */
    public List<String> neededLibraries;

    /** 指令地址 → 字符串内容 (StringReferenceAnalyzer 反向索引), findString 精确命中优先 */
    public Map<Long, String> insnStringRefs;

    /** 数据常量地址 → [type, value] */
    public Map<Long, String[]> dataConstants;

    /** 虚表条目 (含槽位函数名), 间接调用美化用 */
    public com.exbin.app.nativebridge.NativeBridge.VTableEntryNative[] vtableEntries;

    /** 函数地址 → 调用点最大寄存器参数数 (签名缺失时参数个数兜底) */
    public Map<Long, Integer> callerMaxRegs;

    /** 虚表基址 → 条目 (懒构建) */
    private Map<Long, com.exbin.app.nativebridge.NativeBridge.VTableEntryNative> vtableByAddr;

    /**
     * 解析间接调用地址表达式 (如 "r1 + 0x18" / "0x12018") 是否命中虚表槽位.
     * 命中返回 "className::methodName" (或仅 className 当槽位无名字), 否则 null.
     * 基址可为字面量或寄存器名 — 寄存器名经 resolvedAddrs/adrpPages 解析 (由 handler 填充).
     * 纯绝对地址 (movw/movt 折叠结果) 反向匹配: addr - vtableBase ∈ 槽位范围.
     */
    public String resolveVtableSlot(String addrExpr) {
        if (addrExpr == null || addrExpr.isEmpty()
                || vtableEntries == null || vtableEntries.length == 0) {
            return null;
        }
        String s = addrExpr.trim();
        Long base = null;
        long offset = 0;
        // 形如 "base + off" / "base - off"
        int plus = s.lastIndexOf('+');
        int minus = s.lastIndexOf('-');
        int idx = Math.max(plus, minus);
        if (idx > 0) {
            String bs = s.substring(0, idx).trim();
            String os = s.substring(idx + 1).trim();
            Long off = parseConstAddr(os);
            if (off == null) return null;
            if (os.contains("-") && minus > plus) off = -off;
            offset = off;
            base = parseConstAddr(bs);
        } else {
            base = parseConstAddr(s);
            if (base == null) return null;
        }
        if (base == null || base == 0L) return null;

        Map<Long, com.exbin.app.nativebridge.NativeBridge.VTableEntryNative> byAddr =
                vtableByAddr();
        int ptrSize = isAarch64 ? 8 : 4;
        // 1. 直接命中: base = 虚表基址, offset = 槽位偏移
        com.exbin.app.nativebridge.NativeBridge.VTableEntryNative vt = byAddr.get(base);
        if (vt == null && offset == 0) {
            // 2. 绝对地址反向匹配: addr - vtableBase ∈ [0, slots*ptrSize)
            for (com.exbin.app.nativebridge.NativeBridge.VTableEntryNative e : byAddr.values()) {
                long delta = base - e.address;
                if (delta >= 0 && delta % ptrSize == 0) {
                    int slot = (int) (delta / ptrSize);
                    String n = slotName(e, slot);
                    if (n != null) return n;
                }
            }
            return null;
        }
        if (vt == null) return null;
        if (offset < 0 || offset % ptrSize != 0) return null;
        return slotName(vt, (int) (offset / ptrSize));
    }

    /** 取虚表槽位名: slotNames 优先, 其次 slots 地址经符号表解析. 无效返回 null. */
    private String slotName(com.exbin.app.nativebridge.NativeBridge.VTableEntryNative vt, int slot) {
        if (vt == null || vt.confidence < 30) return null;
        if (vt.slotNames != null && slot < vt.slotNames.length) {
            String name = vt.slotNames[slot];
            if (name != null && !name.isEmpty()) {
                return vt.className + "::" + name;
            }
        }
        if (vt.slots != null && slot < vt.slots.length) {
            long faddr = vt.slots[slot];
            String fn = faddr != 0 ? GlobalVarResolver.resolveName(faddr, this) : null;
            if (fn != null && !fn.startsWith("global_")) {
                return vt.className + "::" + fn;
            }
        }
        return null;
    }

    /** 解析常量: 字面量 (0x12000 / 12345, 支持 +/- 号) 或寄存器名 → 常量值 */
    private Long parseConstAddr(String s) {
        if (s == null) return null;
        String t = s.trim().replace("#", "");
        boolean neg = false;
        if (t.startsWith("-")) {
            neg = true;
            t = t.substring(1);
        }
        if (t.startsWith("0x") || t.startsWith("0X")) {
            try {
                long v = Long.parseLong(t.substring(2), 16);
                return neg ? -v : v;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (t.matches("\\d+")) {
            try {
                long v = Long.parseLong(t);
                return neg ? -v : v;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // 寄存器名 → 已解析地址 (adrp+add 或 movw/movt 构造)
        if (resolvedAddrs != null) {
            Long v = resolvedAddrs.get(t);
            if (v != null) return v;
        }
        if (adrpPages != null) {
            Long v = adrpPages.get(t);
            if (v != null) return v;
        }
        return null;
    }

    private Map<Long, com.exbin.app.nativebridge.NativeBridge.VTableEntryNative> vtableByAddr() {
        if (vtableByAddr == null) {
            vtableByAddr = new HashMap<>();
            if (vtableEntries != null) {
                for (com.exbin.app.nativebridge.NativeBridge.VTableEntryNative vt : vtableEntries) {
                    if (vt != null && vt.address != 0) {
                        vtableByAddr.put(vt.address, vt);
                    }
                }
            }
        }
        return vtableByAddr;
    }

    public DecompContext() {
    }

    /**
     * Construct a context with architecture info and symbol maps.
     *
     * @param machine ELF machine type (EM_ARM=40, EM_AARCH64=183)
     * @param isThumb whether Thumb mode (ARM32)
     * @param labels  addr-to-name map for local labels
     * @param imports addr-to-name map for imported symbols
     */
    public DecompContext(int machine, boolean isThumb,
                         Map<Long, String> labels, Map<Long, String> imports) {
        this.isAarch64 = (machine == 183);
        this.isThumb = isThumb;
        if (labels != null) {
            this.labels = labels;
        }
        if (imports != null) {
            this.imports = imports;
        }
    }

    /** Get (or lazily create) the marker map for a given basic-block index. */
    public Map<String, MarkerData> markerMap(int blockIdx) {
        return markers.computeIfAbsent(blockIdx, k -> new HashMap<String, MarkerData>());
    }

    /** Record that {@code reg} holds {@code value} at the boundary of block {@code blockIdx}. */
    public void trackReg(int blockIdx, String reg, long value, IrInsn instr) {
        markerMap(blockIdx).put(reg, new MarkerData(value, instr));
    }

    /** Retrieve the tracked value/instruction for {@code reg} in block {@code blockIdx}. */
    public MarkerData getTracked(int blockIdx, String reg) {
        Map<String, MarkerData> map = markers.get(blockIdx);
        return map == null ? null : map.get(reg);
    }

    public boolean isLocalVar(String name) {
        return localVarNames.contains(name);
    }

    /** Add a stack-slot local variable (de-duplicated by name). */
    public void addLocalVar(String name, String type, int offset) {
        if (!localVarNames.contains(name)) {
            localVarNames.add(name);
            localVars.add(new VarInfo(name, type, offset, true));
        }
    }

    /** Add a register-passed argument variable. */
    public void addArgVar(String name, String type, int regIdx) {
        argVars.add(new VarInfo(name, type, regIdx, false));
    }

    /**
     * "void" when no return register, "uint64_t" for x-regs, "uint32_t" otherwise.
     * v3.4.1: native 结构化签名优先 — native 分析结果比寄存器推断更精确
     * (例: getGameMode 返回 GameMode*, 寄存器推断只能给 uint32_t).
     */
    public String returnType() {
        if (nativeRetType != null && !nativeRetType.isEmpty()) {
            return nativeRetType;
        }
        if (inferredReturnType != null && !inferredReturnType.isEmpty()) {
            return inferredReturnType;
        }
        if (retReg == null) return "void";
        if (retReg.toLowerCase().startsWith("x")) return "uint64_t";
        return "uint32_t";
    }

    /** Argument declarations with inferred types (e.g. void* r0 if dereferenced). */
    public List<String> argDeclarations() {
        List<String> decls = new ArrayList<>();
        for (VarInfo v : argVars) {
            // Priority: paramTypeMap (known signature) > regTypeMap (inferred)
            String paramType = paramTypeMap.get(v.name);
            String inferredType = regTypeMap.get(v.name);
            String type = paramType != null ? paramType
                    : (inferredType != null ? inferredType : v.type);
            decls.add(type + " " + v.name);
        }
        // v4.1: callerMap 兜底 — 无签名且推断无参数时, 按调用点最大寄存器参数数声明
        if (decls.isEmpty() && callerMaxRegs != null) {
            Integer n = callerMaxRegs.get(funcAddr);
            if (n != null && n > 0 && n <= (isAarch64 ? 8 : 4)) {
                String t = isAarch64 ? "uint64_t" : "uint32_t";
                for (int i = 0; i < n; i++) {
                    decls.add(t + " arg_" + i);
                }
            }
        }
        return decls;
    }

    /**
     * Build a stack-variable key from base register and offset.
     * e.g. ("sp", 0x10) → "sp+0x10", ("x29", -8) → "x29-0x8"
     */
    public String stackKey(String baseReg, long offset) {
        if (offset == 0) return baseReg;
        if (offset > 0) return baseReg + "+0x" + Long.toHexString(offset);
        return baseReg + "-0x" + Long.toHexString(-offset);
    }

    /**
     * Register a stack variable: map (baseReg, offset) → varName.
     * <p>v3.2.16: distinguishes arguments from locals — positive offsets from
     * the frame pointer (x29/fp) live in the caller's frame and are named
     * {@code arg_XX}; everything else (sp-relative slots, negative fp offsets)
     * is a local and gets {@code var_XX}.
     */
    public void registerStackVar(String baseReg, long offset, String varName, String type) {
        String key = stackKey(baseReg, offset);
        if (stackVarMap.containsKey(key)) return;
        boolean isArg = offset > 0
                && (baseReg.equals("x29") || baseReg.equals("fp") || baseReg.equals("r11"));
        String name = isArg ? "arg_" + Long.toHexString(offset) : varName;
        stackVarMap.put(key, name);
        addLocalVar(name, type, (int) Math.abs(offset));
    }

    /**
     * Try to resolve a memory expression like "sp + 0x10" or "x29 + -4" to a stack variable name.
     * @return var_XX name, or null if not a known stack variable
     */
    public String resolveStackVar(String baseReg, long offset) {
        String key = stackKey(baseReg, offset);
        String varName = stackVarMap.get(key);
        if (varName != null) {
            usedStackVars.add(varName);
        }
        return varName;
    }

    /**
     * Mark a stack variable as used (so it won't be pruned).
     */
    public void markUsed(String varName) {
        usedStackVars.add(varName);
    }

    // ── v3.5: 栈指针别名 (register → 栈基址+偏移) ──

    /**
     * 记录寄存器为栈基址别名 (如 add x0, sp, #0x10 → x0 = sp+0x10).
     */
    public void trackStackAlias(String reg, String base, long offset) {
        if (reg == null || base == null) return;
        spAliases.put(reg, new StackAlias(base, offset));
    }

    /** 清除寄存器的栈别名 (寄存器被重新赋值时). */
    public void clearStackAlias(String reg) {
        if (reg != null) spAliases.remove(reg);
    }

    /**
     * 解析寄存器到栈基址别名, 支持 ≤3 层链式 (add x0, x1, #8 且 x1 已是别名).
     *
     * @return 归一后的别名 (base 为 sp/x29/fp 等栈基址), 无则 null
     */
    public StackAlias resolveStackAlias(String reg) {
        if (reg == null) return null;
        StackAlias a = spAliases.get(reg);
        if (a == null) return null;
        // 链式展开: base 本身是寄存器且又有别名 (add x0, x1, #8; x1 = sp+0x10)
        for (int depth = 0; depth < 3 && a.base.matches("[wxr]\\d+"); depth++) {
            StackAlias inner = spAliases.get(a.base);
            if (inner == null) break;
            a = new StackAlias(inner.base, inner.offset + a.offset);
        }
        return a;
    }

    /**
     * v3.5: 栈地址 → IDA 风格地址表达式 "&var_XX"（注册并标记使用）。
     * 用于调用参数位置替代 "sp + 0x.." 文本表达式：
     * add x0, sp, #0x50; bl foo → foo(&var_50)。
     */
    public String stackAddrRef(String baseReg, long offset) {
        String varName = resolveStackVar(baseReg, offset);
        if (varName == null) {
            String name = "var_" + Long.toHexString(Math.abs(offset));
            // v4.6: 栈槽宽度按架构 (ARM32 4 字节 / AArch64 8 字节)
            registerStackVar(baseReg, offset, name,
                    isAarch64 ? "uint64_t" : "uint32_t");
            varName = stackVarMap.get(stackKey(baseReg, offset));
            if (varName == null) varName = name;
        }
        markUsed(varName);
        return "&" + varName;
    }

    /** 表达式是否以栈基址开头（如 "sp + x2"）— 无法静态折叠时避免泄漏 sp 到伪 C。 */
    public static boolean isStackBaseExpr(String s) {
        if (s == null) return false;
        return s.equals("sp") || s.startsWith("sp ")
                || s.equals("x29") || s.startsWith("x29 ")
                || s.equals("x19") || s.startsWith("x19 ")
                || s.equals("fp") || s.startsWith("fp ")
                || s.equals("r11") || s.startsWith("r11 ");
    }

    /**
     * 调用边界清除易失寄存器的栈别名 (被调函数可能改写):
     * AArch64 清 x0-x18 (w 变体同), ARM32 清 r0-r3.
     */
    public void clearVolatileStackAliases(boolean isAarch64) {
        if (isAarch64) {
            for (int i = 0; i <= 18; i++) {
                spAliases.remove("x" + i);
                spAliases.remove("w" + i);
            }
        } else {
            for (int i = 0; i <= 3; i++) {
                spAliases.remove("r" + i);
            }
        }
    }


    /**
     * Get only the local variable declarations that are actually used.
     */
    public List<String> localVarDeclarations() {
        List<String> decls = new ArrayList<>();
        // v4.0: afvj 变量视图并入声明 — 同名变量用 native 提取的 r2 类型覆盖
        //   (int_type(access_size) 有符号语义, 比本地寄存器推断更精确),
        //   name 保持不变以保证函数体内引用一致 (零回归)
        Map<String, String> afvjTypeByName = new HashMap<>();
        for (R2DecPseudoC.AfvjVarEntry e : afvjReg) {
            if (e.name != null && e.type != null && !e.type.isEmpty()) {
                afvjTypeByName.putIfAbsent(e.name, e.type);
            }
        }
        for (R2DecPseudoC.AfvjVarEntry e : afvjSp) {
            if (e.name != null && e.type != null && !e.type.isEmpty()) {
                afvjTypeByName.putIfAbsent(e.name, e.type);
            }
        }
        for (R2DecPseudoC.AfvjVarEntry e : afvjBp) {
            if (e.name != null && e.type != null && !e.type.isEmpty()) {
                afvjTypeByName.putIfAbsent(e.name, e.type);
            }
        }
        for (VarInfo v : localVars) {
            if (usedStackVars.contains(v.name)) {
                String t = afvjTypeByName.get(v.name);
                decls.add((t != null ? t : v.type) + " " + v.name + ";");
            }
        }
        return decls;
    }

    // ── Inner types ──

    /** A tracked register value at a block boundary. */
    public static class MarkerData {
        public final long value;
        public final IrInsn instr;

        public MarkerData(long value, IrInsn instr) {
            this.value = value;
            this.instr = instr;
        }
    }

    /** A local (stack-slot) or argument (register) variable. */
    public static class VarInfo {
        public final String name;
        public final String type;
        public final int offsetOrReg;
        public final boolean isLocal;


        public VarInfo(String name, String type, int offsetOrReg, boolean isLocal) {
            this.name = name;
            this.type = type;
            this.offsetOrReg = offsetOrReg;
            this.isLocal = isLocal;
        }
    }

    /** v3.5: 栈指针别名 — 寄存器折叠为 (栈基址, 偏移). */
    public static class StackAlias {
        public final String base;
        public final long offset;

        public StackAlias(String base, long offset) {
            this.base = base;
            this.offset = offset;
        }

        /** 伪 C 表达式: "sp" / "sp + 0x10" / "x29 - 0x8". */
        public String toExpr() {
            if (offset == 0) return base;
            if (offset > 0) return base + " + 0x" + Long.toHexString(offset);
            return base + " - 0x" + Long.toHexString(-offset);
        }
    }

}
