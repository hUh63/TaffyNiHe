package com.exbin.app.nativebridge;

import com.exbin.app.elf.pseudoc.r2dec.ItaniumDemangler;

/**
 * 塔菲逆核 · Exbin 复刻层的 NativeBridge 适配桥。
 * <p>
 * Exbin 的 r2dec / FunctionSignatureAnalyzer / DisasmAnnotator 等纯 Java 模块通过本类访问
 * native 增强能力（demangle / 签名还原 / 控制流分析 / 变量分析）。
 * <p>
 * 塔菲逆核没有 Exbin 那套 soide-native，因此本类采取「离线优先 + 可选后端注入」策略：
 * <ul>
 *   <li>{@link #demangle(String)} 委托纯 Java 的 {@link ItaniumDemangler}（等价 native __cxa_demangle）；</li>
 *   <li>签名还原（{@code restoreSignatures*}）通过 {@link SignatureBackend} 注入点由塔菲
 *       Kotlin 侧用 rizin(afvj/afij/axt) 提供实现；未注入时返回空结果，调用方自动降级；</li>
 *   <li>{@link #analyzeControlFlow} 返回 null，使 ControlFlowAnalyzer 走 fallback —
 *       塔菲通过 {@code PseudoCContext.prebuiltCfg}（rizin agfj 预构建）提供真实 CFG。</li>
 * </ul>
 */
public final class NativeBridge {

    private NativeBridge() {}

    // ============================================================
    // 塔菲扩展: 签名还原后端注入点
    //   Exbin 用 soide-native 的 capstone 分析实现；塔菲用 rizin 等价实现。
    // ============================================================

    /** 签名还原后端契约（由塔菲 RizinSignatureBackend 实现并注册）。 */
    public interface SignatureBackend {
        /** 后端是否可用。 */
        boolean available();

        /** 批量 demangle（返回与输入等长数组；失败元素保持原样）。 */
        String[] demangleBatch(String[] names);

        /** 基于 SO 字节做函数签名还原（addrs 为文件偏移）。 */
        String[] restoreSignatures(byte[] soData, long[] addrs, int[] sizes,
                                   int[] thumbFlags, int machine, String[] names);

        /** 基于 native handle（SO 路径）做签名还原。 */
        String[] restoreSignaturesByHandle(long handle, long[] addrs, int[] sizes,
                                           int[] thumbFlags, int machine, String[] names);
    }

    private static volatile SignatureBackend sSigBackend = null;

    /** 注册签名还原后端（塔菲 RizinSignatureBackend）。 */
    public static void setSignatureBackend(SignatureBackend backend) {
        sSigBackend = backend;
    }

    public static SignatureBackend signatureBackend() {
        return sSigBackend;
    }

    /** 签名还原能力是否可用（区别于 {@link #isSupported()} 的 native 增强总开关）。 */
    public static boolean isSigSupported() {
        SignatureBackend b = sSigBackend;
        return b != null && b.available();
    }

    // ============================================================
    // demangle
    // ============================================================

    /**
     * Itanium demangle —— 委托纯 Java {@link ItaniumDemangler}（等价 __cxa_demangle）。
     *
     * @param name mangled 名（如 _ZN3foo3barEi）；非 mangled 或解码失败时原样返回
     */
    public static String demangle(String name) {
        if (name == null || name.length() < 3 || !name.startsWith("_Z")) return name;
        try {
            String d = ItaniumDemangler.demangle(name);
            return (d != null && !d.isEmpty()) ? d : name;
        } catch (Throwable t) {
            return name;
        }
    }

    /** 批量 demangle —— 优先注入后端，其次逐条 {@link #demangle(String)}。 */
    public static String[] demangleBatch(String[] names) {
        if (names == null || names.length == 0) return new String[0];
        SignatureBackend b = sSigBackend;
        if (b != null && b.available()) {
            try {
                String[] r = b.demangleBatch(names);
                if (r != null && r.length == names.length) return r;
            } catch (Throwable ignored) {
            }
        }
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) out[i] = demangle(names[i]);
        return out;
    }

    // ============================================================
    // 签名还原（转发注入后端；未注入则空结果）
    // ============================================================

    public static String[] restoreSignatures(byte[] soData, long[] addrs, int[] sizes,
                                             int[] thumbFlags, int machine, String[] names) {
        if (soData == null || addrs == null || sizes == null) return new String[0];
        SignatureBackend b = sSigBackend;
        if (b != null && b.available()) {
            try {
                String[] r = b.restoreSignatures(soData, addrs, sizes, thumbFlags, machine, names);
                return r != null ? r : new String[0];
            } catch (Throwable ignored) {
            }
        }
        return new String[0];
    }

    public static String[] restoreSignaturesByHandle(long handle, long[] addrs, int[] sizes,
                                                     int[] thumbFlags, int machine, String[] names) {
        if (handle == 0 || addrs == null || sizes == null) return new String[0];
        SignatureBackend b = sSigBackend;
        if (b != null && b.available()) {
            try {
                String[] r = b.restoreSignaturesByHandle(handle, addrs, sizes, thumbFlags, machine, names);
                return r != null ? r : new String[0];
            } catch (Throwable ignored) {
            }
        }
        return new String[0];
    }

    /**
     * 结构化签名通道 —— 塔菲后端暂不提供（返回 null，FSA 自动降级到字符串通道）。
     */
    public static Object[][] restoreSignaturesStructuredByHandle(long handle, long[] addrs, int[] sizes,
                                                                 int[] thumbFlags, int machine, String[] names) {
        return null;
    }

    // ============================================================
    // 其余 native 增强能力 —— 桩（调用方均有 null / 长度守卫）
    // ============================================================

    /** 恒为 false：禁用 Exbin native 增强（demangle 除外，见上）。 */
    public static boolean isSupported() {
        return false;
    }

    /** 打开 SO —— 桩：无句柄。 */
    public static long parseSo(String path, boolean deep) {
        return 0L;
    }

    /** 读取代码字节 —— 桩：无数据。 */
    public static byte[] readCode(long handle, long vaddr, int maxLen) {
        return null;
    }

    /** 释放句柄 —— 桩：no-op。 */
    public static void freeSo(long handle) {
    }

    /** noreturn 传播 —— 桩：不消除任何指令。 */
    public static boolean[] markNoreturnUnreachable(Object[] insnArr) {
        return null;
    }

    /** afvj 局部变量三分类 —— 桩：无。 */
    public static Object[][] buildAfvj(Object[] insnArr) {
        return null;
    }

    /** aaef 计算交叉引用 —— 桩：无。 */
    public static Object[][] analyzeDataFlow(Object[] insnArr) {
        return null;
    }

    /**
     * 控制流分析 —— 桩：返回 null，使 {@code ControlFlowAnalyzer.build} 走其
     * fallback；塔菲通过 prebuiltCfg 提供真实 CFG，不依赖此路径。
     */
    public static Object[] analyzeControlFlow(Object[] insnData, String funcName,
                                              long funcAddr, int machineType, boolean isThumb) {
        return null;
    }

    // ============================================================
    // native 数据载体（与 Exbin 字段布局一致）
    // ============================================================

    /** 虚表条目（r2dec 的 DecompContext 会读取字段）。 */
    public static class VTableEntryNative {
        public long address;
        public String className;
        public long[] slots;
        public int confidence;
        public int rttiType;
        public long offsetToTop;
        public long typeinfoAddr;
        public long nameAddr;
        public String scanMethod;
        public String[] slotNames;
        public String[] inheritChain;

        public VTableEntryNative() {}
    }

    /** 数据标签（地址 → 名称映射，DisasmAnnotator 消费）。 */
    public static class DataLabelNative {
        public long address;
        public String name;
        public int size;
        public int type;        // 0=符号表, 1=重定位GOT, 2=字符串, 3=自动dword_
        public boolean isImported;
        public DataLabelNative() {}
    }

    /** 常量条目（数据段分析）。 */
    public static class ConstantEntryNative {
        public long address;
        public long offset;
        public int size;
        public String type;
        public String value;
        public String section;
        public ConstantEntryNative() {}
    }

    /** 全局变量条目（数据段分析）。 */
    public static class GlobalVarEntryNative {
        public long address;
        public long offset;
        public int size;
        public String section;
        public String type;
        public String basis;
        public boolean isPointer;
        public boolean isRelocated;
        public long initialValue;
        public String symbolName;
        public GlobalVarEntryNative() {}
    }

    /** 交叉引用条目（callers / callees / data refs）。 */
    public static class CrossRefEntryNative {
        public long fromAddr;
        public long toAddr;
        public String fromFunc;
        public String toFunc;
        public String type;
        public String instruction;
        public CrossRefEntryNative() {}
    }

    // ============================================================
    // 交叉引用分析 —— 桩
    //   GlobalCallGraphAnalyzer 在 native 不可用时自动降级到
    //   纯 Java 路径（扫描 FunctionInfo.instructions 的 call 指令）。
    // ============================================================

    public static boolean analyzeCrossRefs(long handle) {
        return false;
    }

    public static boolean isCrossRefAnalyzed(long handle) {
        return false;
    }

    public static CrossRefEntryNative[] getCallees(long handle, long funcAddr) {
        return null;
    }

    public static CrossRefEntryNative[] getCallers(long handle, long funcAddr) {
        return null;
    }

    // ============================================================
    // Exbin 自研 microcode + SSA 反编译器（libexbin_decomp.so）
    //   AsmInsn → MicrocodeEmitter → SSA → Optimize → CFGStructure
    //           → CTree → Beautify → CPrinter
    //   纯 C++17 + JNI，无第三方依赖；仅 arm64/arm32/x86 ABI 由 CI 各自编译。
    // ============================================================

    private static final boolean sDecompLoaded;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("exbin_decomp");
            ok = true;
        } catch (Throwable ignored) {
            // 该 ABI 未打包 libexbin_decomp.so：静默降级，不影响其它能力
        }
        sDecompLoaded = ok;
    }

    /** libexbin_decomp.so 是否可用。 */
    public static boolean isExbinDecompilerAvailable() {
        return sDecompLoaded;
    }

    private static native String nativeDecompileFunction(
            String funcName, long funcAddr, int machine, boolean isThumb, String soPath,
            String[] mnemonics, String[] opStrs, long[] addresses, int[] sizes,
            long[] labelAddrs, String[] labelNames,
            long[] importAddrs, String[] importNames,
            String[] sigNames, String[] sigStrings,
            long[] symAddrs, String[] symNames);

    /**
     * 调用 Exbin 反编译器生成伪 C。
     *
     * @return 伪 C 文本；so 未打包 / native 失败时返回 null（调用方降级）
     */
    public static String decompileFunction(
            String funcName, long funcAddr, int machine, boolean isThumb, String soPath,
            String[] mnemonics, String[] opStrs, long[] addresses, int[] sizes,
            long[] labelAddrs, String[] labelNames,
            long[] importAddrs, String[] importNames,
            String[] sigNames, String[] sigStrings,
            long[] symAddrs, String[] symNames) {
        if (!sDecompLoaded) return null;
        try {
            return nativeDecompileFunction(
                    funcName, funcAddr, machine, isThumb, soPath,
                    mnemonics, opStrs, addresses, sizes,
                    labelAddrs, labelNames,
                    importAddrs, importNames,
                    sigNames, sigStrings,
                    symAddrs, symNames);
        } catch (Throwable t) {
            return null;
        }
    }
}
