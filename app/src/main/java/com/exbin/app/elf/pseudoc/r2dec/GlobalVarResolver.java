package com.exbin.app.elf.pseudoc.r2dec;

import java.util.Map;

/**
 * Resolves global variable addresses to symbolic names and types using
 * the symbol table and data-analysis data passed in from the Android-side
 * ELF parser / NativeBridge.
 * <p>
 * This replaces the previous {@code "global_XXXXXX"} placeholder naming
 * with real symbol names from the ELF symbol table (e.g. {@code "g_config"},
 * {@code "g_lock"}) and inferred types from data-segment analysis (e.g.
 * {@code "pthread_mutex_t"}).
 *
 * <h3>Resolution priority</h3>
 * <ol>
 *   <li>{@code ctx.globalVarNames} — exact address → symbol name
 *       (from ELF .symtab / .dynsym / DataLabels)</li>
 *   <li>{@code ctx.symbolMap} — unified address → name map
 *       (from ELF symbol table, includes function + data symbols)</li>
 *   <li>{@code ctx.labels} / {@code ctx.imports} — fallback</li>
 *   <li>Generate {@code "global_XXXX"} placeholder (last resort)</li>
 * </ol>
 *
 * <h3>Type resolution</h3>
 * When a global variable is resolved, its type is looked up in
 * {@code ctx.globalVarTypes} (from {@code NativeBridge.analyzeSoDataNative}).
 * If found, the type is stored in {@code ctx.regTypeMap} so that subsequent
 * uses of the register carrying this address get the right type.
 *
 * @since v2.9.35
 */
public final class GlobalVarResolver {

    private GlobalVarResolver() {}

    /**
     * Resolve a global variable address to a symbolic name.
     *
     * @param addr the global variable address
     * @param ctx  the decompilation context
     * @return the symbol name, or {@code null} if unresolvable
     */
    public static String resolveName(long addr, DecompContext ctx) {
        if (ctx == null || addr == 0) return null;

        // 1. globalVarNames (exact match, from ELF symbols/DataLabels)
        if (ctx.globalVarNames != null) {
            String name = ctx.globalVarNames.get(addr);
            if (name != null && !name.isEmpty()) return name;
        }

        // 2. symbolMap (unified address → name)
        if (ctx.symbolMap != null) {
            String name = ctx.symbolMap.get(addr);
            if (name != null && !name.isEmpty()) return name;
        }

        // v4.1: relocSymbols — 重定位位置 (GOT 槽/数据地址) → 符号名
        if (ctx.relocSymbols != null) {
            String name = ctx.relocSymbols.get(addr);
            if (name != null && !name.isEmpty()) return name;
        }

        // 3. labels / imports (fallback — may contain data labels)
        if (ctx.labels != null) {
            String name = ctx.labels.get(addr);
            if (name != null && !name.isEmpty()) return name;
        }
        if (ctx.imports != null) {
            String name = ctx.imports.get(addr);
            if (name != null && !name.isEmpty()) return name;
        }

        // 4. Last resort: generate a placeholder
        return "global_" + Long.toHexString(addr);
    }

    /**
     * Resolve a global variable's type.
     *
     * @param addr the global variable address
     * @param ctx  the decompilation context
     * @return the type string (e.g. "int", "void*", "pthread_mutex_t"),
     *         or {@code null} if unknown
     */
    public static String resolveType(long addr, DecompContext ctx) {
        if (ctx == null || addr == 0) return null;
        if (ctx.globalVarTypes != null) {
            return ctx.globalVarTypes.get(addr);
        }
        return null;
    }

    /**
     * Resolve a global variable and return a C-style address-of expression.
     * <p>
     * If the symbol name is a real name (not a placeholder), returns
     * {@code "&symbolName"}. If it's a placeholder, returns
     * {@code "&global_XXXX"}.
     *
     * @param addr the global variable address
     * @param ctx  the decompilation context
     * @return the address-of expression, never null
     */
    public static String resolveAddrOf(long addr, DecompContext ctx) {
        String name = resolveName(addr, ctx);
        if (name == null) {
            name = "global_" + Long.toHexString(addr);
        }
        return "&" + name;
    }

    /**
     * Check if a resolved name is a real symbol (not a generated placeholder).
     *
     * @param name the resolved name
     * @return true if the name is a real symbol from the ELF table
     */
    public static boolean isRealSymbol(String name) {
        if (name == null) return false;
        return !name.startsWith("global_")
                && !name.startsWith("sym_")
                && !name.startsWith("sub_")
                && !name.startsWith("loc_");
    }

    /**
     * Demangle a symbol name if a demangled version is available.
     *
     * @param name the (possibly mangled) symbol name
     * @param ctx  the decompilation context
     * @return the demangled name, or the original if no demangling is available
     */
    public static String demangle(String name, DecompContext ctx) {
        if (name == null || ctx == null || ctx.demangledNames == null) return name;
        String demangled = ctx.demangledNames.get(name);
        return demangled != null ? demangled : name;
    }

    /**
     * Convert a (possibly C++ demangled) symbol name into a valid C function
     * name: every non [A-Za-z0-9] run becomes a single underscore, leading /
     * trailing underscores are trimmed, a leading digit gets an "f_" prefix,
     * and overlong names (> 80 chars) are truncated with an ellipsis.
     * <p>e.g. {@code std::__cxx11::basic_string<char, std::char_traits<char> >}
     * → {@code std___cxx11__basic_string_char__std__char_traits_char_} (usable
     * in pseudo-C output, matching r2dec's fully-demangled call names).
     *
     * @param name the raw or demangled symbol name
     * @return a C-safe function name, or {@code null} if input was null
     */
    public static String sanitizeFuncName(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder(name.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (ok) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String r = sb.toString();
        int s = 0, e = r.length();
        while (s < e && r.charAt(s) == '_') s++;
        while (e > s && r.charAt(e - 1) == '_') e--;
        r = r.substring(s, e);
        if (r.isEmpty()) return "unknown_callee";
        if (r.charAt(0) >= '0' && r.charAt(0) <= '9') {
            r = "f_" + r;
        }
        if (r.length() > 80) {
            r = r.substring(0, 77) + "...";
        }
        return r;
    }
}
