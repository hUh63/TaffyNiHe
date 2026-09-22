package com.exbin.app.nativebridge;

/**
 * 塔菲逆核 · r2dec 移植的最小 NativeBridge 契约桩。
 * <p>
 * Exbin 的 r2dec 纯 Java 移植在 native 增强不可用时会自动降级为离线模式；
 * 本桩令 {@link #isSupported()} 恒为 false，使 r2dec 走纯 Java 路径。
 * 所有 native 增强方法返回空值（调用方均有 null/长度守卫）。
 * <p>
 * 唯一的例外：CFG 由塔菲在 Kotlin 侧用 rizin(agfj) 预构建并通过
 * {@code PseudoCConverter.PseudoCContext.prebuiltCfg} 注入，不经过
 * {@link #analyzeControlFlow}。
 */
public final class NativeBridge {

    private NativeBridge() {}

    /** 恒为 false：禁用一切 native 增强（demangle/vtable/afvj/aaef/noreturn 传播）。 */
    public static boolean isSupported() {
        return false;
    }

    /** Itanium demangle 降级：原样返回。 */
    public static String demangle(String name) {
        return name;
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

    /** 与 Exbin 契约一致的虚表条目（r2dec 的 DecompContext 会读取字段）。 */
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
}
