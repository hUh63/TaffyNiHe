package com.exbin.app.util;

import com.exbin.app.nativebridge.NativeBridge;

/**
 * C++ (Itanium ABI) 符号 demangle 代理.
 * <p>
 * v2.3.2: 删除纯 Java 实现, 底层统一调用 NativeBridge.demangle(),
 * 由 JNI 层的 __cxa_demangle 完成解码.
 */
public final class Demangler {

    private Demangler() {}

    public static class Result {
        public final String demangled;
        public final boolean supported;

        Result(String demangled, boolean supported) {
            this.demangled = demangled;
            this.supported = supported;
        }
    }

    /**
     * 对 mangled C++ 符号进行解码.
     *
     * @param mangled 符号名, 如 "_ZN3foo3barEi"
     * @return Result: demangled 名称(不含返回类型) + 是否解码成功
     */
    public static Result demangle(String mangled) {
        if (mangled == null || mangled.isEmpty()) {
            return new Result("", true);
        }
        String s = mangled.trim();

        // 非 mangled 名称直接返回
        if (!s.startsWith("_Z")) {
            return new Result(s, true);
        }

        // v2.3.2: 统一走 native __cxa_demangle
        String demangled = NativeBridge.demangle(s);
        if (demangled == null || demangled.equals(s)) {
            // 解码失败或不可用时返回原始符号
            return new Result(s, false);
        }
        return new Result(demangled, true);
    }
}
