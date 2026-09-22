package com.exbin.app.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * v2.0.17 (魔改) 变量类型系统 — 12 种基础类型映射.
 * <p>
 * 用户 v2.0.17 反馈: "伪 C 生成的参数与函数详情不一致" — 根本原因之一是类型识别粗粒度.
 * 本类把识别规则集中到一处, 伪 C 生成器 / 函数列表 chip / 函数详情对话框
 * / 汇编注释 全都走 {@link TypeMapper} 作为单一事实源, 消除不一致.
 * <p>
 * 12 种类型 + 修饰符 (signed/unsigned/short/long/const) 全部支持.
 * <p>
 * 公开 API:
 * <ul>
 *   <li>{@link #fromRegister(String)} - 寄存器名 (x8, w0, s0, d1, sp) → 基础类型</li>
 *   <li>{@link #fromStringFunc(String)} - 字符串函数名 (strlen 等) → const char*</li>
 *   <li>{@link #fromStringContent(String)} - 字符串字面量内容 → char &#42; / wchar_t &#42;</li>
 *   <li>{@link #withModifier(String, String)} - 加 unsigned/const 修饰</li>
 *   <li>{@link #compressChip(String)} - 压缩成 1 字符 chip (i/u/l/s/c/b/f/d/p/v/?)</li>
 *   <li>{@link #STRING_FUNCS} - 已知字符串函数常量表 (可被外部扩展)</li>
 * </ul>
 *
 * @author 魔改版 (基于 v2.0.14, 不依赖 v2.0.15/16/17 旧代码)
 */
public final class TypeMapper {

    private TypeMapper() {}

    /* 基础类型常量 — 跟用户需求表 1:1 对应 */
    public static final String T_VOID          = "void";
    public static final String T_VOID_PTR      = "void*";
    public static final String T_BOOL          = "bool";
    public static final String T_CHAR          = "char";
    public static final String T_UCHAR         = "unsigned char";
    public static final String T_SHORT         = "short";
    public static final String T_USHORT        = "unsigned short";
    public static final String T_INT           = "int";
    public static final String T_UINT          = "unsigned int";
    public static final String T_LONG          = "long";
    public static final String T_ULONG         = "unsigned long";
    public static final String T_LLONG         = "long long";
    public static final String T_ULLONG        = "unsigned long long";
    public static final String T_FLOAT         = "float";
    public static final String T_DOUBLE        = "double";
    public static final String T_WCHAR_T       = "wchar_t";
    public static final String T_CONST_CHAR    = "const char*";
    public static final String T_CONST_WCHAR   = "const wchar_t*";
    public static final String T_CHAR_PTR      = "char*";
    public static final String T_WCHAR_PTR     = "wchar_t*";
    public static final String T_UNKNOWN       = "?";

    /* 已知字符串函数表 — 用于 {@link #fromStringFunc(String)} 命中 const char* */
    public static final String[] STRING_FUNCS = {
            "strlen", "strnlen", "strcmp", "strncmp", "strcasecmp", "strncasecmp",
            "strchr", "strrchr", "strstr", "strpbrk", "strspn", "strcspn",
            "strcpy", "strncpy", "strcat", "strncat", "strdup", "strndup",
            "strsep", "strtok", "strerror", "strsignal", "strcoll", "strxfrm",
            "memchr", "memcmp", "memcpy", "memmove", "memset",
            "stpcpy", "stpncpy", "strlcpy", "strlcat",
            "printf", "fprintf", "sprintf", "snprintf",
            "vprintf", "vfprintf", "vsprintf", "vsnprintf",
            "fputs", "fputc", "puts", "putchar", "fgets", "fgetc", "getc",
            "fopen", "fclose", "fread", "fwrite", "fseek", "ftell",
            "rewind", "fflush", "feof",
            "scanf", "fscanf", "sscanf", "vscanf", "vfscanf", "vsscanf",
            "atoi", "atol", "atoll", "strtod", "strtof", "strtold",
            "strtol", "strtoll", "strtoul", "strtoull",
            "wcsdup", "wcslen", "wcsncmp", "wcscpy", "wcsncpy", "wcscat",
            "wcsncat", "wcschr", "wcsrchr", "wcsstr", "wcspbrk", "wcstok",
            "isalpha", "isdigit", "isalnum", "isspace", "isupper", "islower",
            "isxdigit", "ispunct", "isprint", "isgraph", "iscntrl", "isblank"
    };

    /**
     * 寄存器 → 基础类型.
     * <p>
     * x0-x30 → long
     * w0-w30 → int
     * r0-r15 → int
     * s0-s31 → float
     * d0-d31 → double
     * q0-q31 → long long
     * v0-v31 → void*
     * h0-h31 → unsigned short
     * b0-b31 → unsigned char
     * c0-c31 → unsigned char
     * sp/lr/fp/pc → void* (地址性质)
     */
    @NonNull
    public static String fromRegister(@Nullable String reg) {
        if (reg == null || reg.isEmpty()) return T_UNKNOWN;
        String r = reg.trim().toLowerCase(Locale.ROOT);
        switch (r) {
            case "sp": case "xsp": case "wsp": return "sp_t";
            case "lr": case "fp": case "pc":
                return T_VOID_PTR;
        }
        char c0 = r.charAt(0);
        switch (c0) {
            case 'x': return T_LONG;
            case 'w': return T_INT;
            case 'r': return T_INT;
            case 's': return T_FLOAT;
            case 'd': return T_DOUBLE;
            case 'q': return T_LLONG;
            case 'v': return T_VOID_PTR;
            case 'h': return T_USHORT;
            case 'b':
            case 'c': return T_UCHAR;
            default: return T_UNKNOWN;
        }
    }

    /**
     * 字符串函数名 → 字符串指针类型. 命中 {@link #STRING_FUNCS} 表返回 {@link #T_CONST_CHAR},
     * wcs 开头返回 {@link #T_CONST_WCHAR}, 否则 null (让调用方继续推断).
     */
    @Nullable
    public static String fromStringFunc(@Nullable String funcName) {
        if (funcName == null || funcName.isEmpty()) return null;
        String n = funcName.trim().toLowerCase(Locale.ROOT);
        int paren = n.indexOf('(');
        if (paren > 0) n = n.substring(0, paren);
        // C++ mangled 名字: 简单用 :: 截
        int colon = n.lastIndexOf("::");
        if (colon > 0) n = n.substring(colon + 2);
        for (String sf : STRING_FUNCS) {
            if (n.equals(sf)) return T_CONST_CHAR;
        }
        if (n.startsWith("wcs") || n.startsWith("wmem") || n.startsWith("wcslen")) {
            return T_CONST_WCHAR;
        }
        return null;
    }

    /**
     * 字符串字面量内容 → 字符指针类型.
     * <p>
     * 含 NUL / 宽字符 → wchar_t*; 含中文 / 0x80+ 字节 → char* (UTF-8 简化);
     * 全 ASCII → char* (const).
     */
    @NonNull
    public static String fromStringContent(@Nullable String content) {
        if (content == null) return T_CHAR_PTR;
        if (content.isEmpty()) return T_CONST_CHAR;
        boolean wide = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == 0) wide = true;
        }
        return wide ? T_CONST_WCHAR : T_CONST_CHAR;
    }

    /**
     * 加修饰符.
     * <p>
     * modifier ∈ { unsigned / u / signed / s / const / c / volatile / v }
     */
    @NonNull
    public static String withModifier(@NonNull String type, @Nullable String modifier) {
        if (modifier == null || modifier.isEmpty()) return type;
        String m = modifier.trim().toLowerCase(Locale.ROOT);
        String low = type.toLowerCase(Locale.ROOT);
        switch (m) {
            case "unsigned": case "u":
                if (low.startsWith("unsigned ")) return type;
                switch (low) {
                    case "int": case "long": case "long long":
                    case "char": case "short":
                        return "unsigned " + type;
                }
                return type;
            case "signed": case "s":
                return type;  // signed 是 C 默认
            case "const": case "c":
                if (low.startsWith("const ")) return type;
                if (low.endsWith("*")) return "const " + type;
                return type;
            case "volatile": case "v":
                if (low.contains("volatile")) return type;
                return "volatile " + type;
        }
        return type;
    }

    /**
     * 类型 → 1 字符 chip. 给函数列表 retType 标签用.
     * <p>
     * v/i/u/l/s/c/b/f/d/p 是单一字符映射:
     * <pre>
     *   void   → v   int   → i   unsigned int → u
     *   long   → l   short → s   char*        → c
     *   bool   → b   float → f   double       → d
     *   * (任何指针) → p
     * </pre>
     */
    public static char compressChip(@Nullable String type) {
        if (type == null || type.isEmpty()) return '?';
        String s = type.trim().toLowerCase(Locale.ROOT);
        if (s.equals("void") || s.equals("noreturn")) return 'v';
        if (s.equals("bool") || s.equals("_bool") || s.equals("boolean")) return 'b';
        if (s.equals("char") || s.equals("char*") || s.equals("wchar_t")) return 'c';
        if (s.equals("unsigned char") || s.equals("uint8_t") || s.equals("u8")) return 'c';
        if (s.startsWith("const char") || s.startsWith("const signed char")
                || s.startsWith("const unsigned char") || s.equals("const char*")) return 'c';
        if (s.equals("float")) return 'f';
        if (s.equals("double") || s.equals("long double")) return 'd';
        if (s.equals("long") || s.equals("long long") || s.equals("int64")
                || s.equals("int64_t") || s.equals("unsigned long")
                || s.equals("unsigned long long") || s.equals("uint64")
                || s.equals("uint64_t") || s.equals("size_t")
                || s.equals("ssize_t") || s.equals("__int64")) return 'l';
        if (s.equals("unsigned int") || s.equals("unsigned") || s.equals("uint32")
                || s.equals("uint32_t")) return 'u';
        if (s.equals("int") || s.equals("int32") || s.equals("int32_t")
                || s.equals("signed int") || s.equals("signed")) return 'i';
        if (s.equals("short") || s.equals("unsigned short") || s.equals("uint16")
                || s.equals("uint16_t") || s.equals("short int")) return 's';
        if (s.endsWith("*") || s.contains("ptr") || s.contains("pointer")) return 'p';
        if (s.isEmpty()) return '?';
        return s.charAt(0);
    }

    /**
     * 给定类型推断一个"易于显示的短名", 用于函数列表 subtitle / chip 旁标.
     * <pre>
     *   const char* → "const char*"
     *   unsigned int → "uint32"
     *   int          → "int"
     *   其它         → 原样
     * </pre>
     */
    @NonNull
    public static String shortName(@Nullable String type) {
        if (type == null) return T_UNKNOWN;
        String s = type.trim();
        String low = s.toLowerCase(Locale.ROOT);
        if (low.equals("unsigned int")) return "uint32";
        if (low.equals("unsigned long")) return "ulong";
        if (low.equals("unsigned long long")) return "ull";
        if (low.equals("unsigned short")) return "u16";
        if (low.equals("unsigned char")) return "u8";
        if (low.equals("long long")) return "i64";
        if (low.equals("long")) return "long";
        if (low.equals("short")) return "i16";
        if (low.equals("int")) return "int";
        if (low.equals("char")) return "char";
        if (low.equals("bool")) return "bool";
        if (low.equals("float")) return "float";
        if (low.equals("double")) return "double";
        if (low.equals("void")) return "void";
        if (low.equals("void*")) return "void*";
        return s;
    }
}
