package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.9: 轻量 Itanium ABI demangler — native __cxa_demangle 失败时的 Java 兜底.
 * 覆盖常见子集: nested-name + CV/ref 限定、长度前缀标识符、operator 名、
 * 构造/析构、模板参数、基本类型/指针/引用/数组。任何未知编码或越界 →
 * 返回 null (调用方保持 mangled 原样), 绝不抛异常。
 *
 * <pre>
 *   _ZNK17AttributeInstance15getCurrentValueEv
 *     → AttributeInstance::getCurrentValue() const
 *   _ZNSt6vectorIiSaIiEEC1Ev → std::vector<int, std::allocator<int>>::vector()
 * </pre>
 */
public final class ItaniumDemangler {

    private static final Map<String, String> OPERATORS = new HashMap<>();
    static {
        OPERATORS.put("nw", "new");   OPERATORS.put("na", "new[]");
        OPERATORS.put("dl", "delete"); OPERATORS.put("da", "delete[]");
        OPERATORS.put("ps", "+");     OPERATORS.put("ng", "-");
        OPERATORS.put("ad", "&");     OPERATORS.put("de", "*");
        OPERATORS.put("co", "~");     OPERATORS.put("pl", "+");
        OPERATORS.put("mi", "-");     OPERATORS.put("ml", "*");
        OPERATORS.put("dv", "/");     OPERATORS.put("rm", "%");
        OPERATORS.put("an", "&");     OPERATORS.put("or", "|");
        OPERATORS.put("eo", "^");     OPERATORS.put("aS", "<<");
        OPERATORS.put("rs", ">>");    OPERATORS.put("lS", "<<=");
        OPERATORS.put("rS", ">>=");   OPERATORS.put("eq", "==");
        OPERATORS.put("ne", "!=");    OPERATORS.put("lt", "<");
        OPERATORS.put("gt", ">");     OPERATORS.put("le", "<=");
        OPERATORS.put("ge", ">=");    OPERATORS.put("ss", "<=>");
        OPERATORS.put("nt", "!");     OPERATORS.put("aa", "&&");
        OPERATORS.put("oo", "||");    OPERATORS.put("pp", "++");
        OPERATORS.put("mm", "--");    OPERATORS.put("cm", ",");
        OPERATORS.put("pm", "->*");   OPERATORS.put("pt", "->");
        OPERATORS.put("cl", "()");    OPERATORS.put("ix", "[]");
        OPERATORS.put("qu", "?");     OPERATORS.put("sz", "sizeof");
    }

    private static final Map<Character, String> STD_ABBREV = new HashMap<>();
    static {
        STD_ABBREV.put('t', "std");
        STD_ABBREV.put('a', "std::allocator");
        STD_ABBREV.put('b', "std::basic_string");
        STD_ABBREV.put('s', "std::string");
        STD_ABBREV.put('i', "std::istream");
        STD_ABBREV.put('o', "std::ostream");
        STD_ABBREV.put('d', "std::iostream");
    }

    private final String s;
    private int pos;
    private String funcCv = "";
    private String funcRef = "";

    private ItaniumDemangler(String s) {
        this.s = s;
        this.pos = 2; // skip "_Z"
    }

    public static String demangle(String mangled) {
        if (mangled == null || mangled.length() < 3 || mangled.length() > 4096) return null;
        if (!mangled.startsWith("_Z")) return null;
        try {
            ItaniumDemangler d = new ItaniumDemangler(mangled);
            String name = d.parseName();
            if (name == null) return null;
            String params = d.parseBareFunctionType();
            if (params == null) return null;
            if (d.pos != d.s.length()) return null;
            return name + "(" + params + ")" + d.funcCv + d.funcRef;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean eof() { return pos >= s.length(); }

    private char peek() { return eof() ? '\0' : s.charAt(pos); }

    private char next() { return eof() ? '\0' : s.charAt(pos++); }

    private boolean eat(char c) {
        if (!eof() && s.charAt(pos) == c) { pos++; return true; }
        return false;
    }

    /** <name> ::= <nested-name> | <unscoped-name> | <unscoped-template-name> */
    private String parseName() {
        if (peek() == 'N') return parseNestedName(false);
        if (peek() == 'S') return parseStdName();
        return parseUnqualifiedName();
    }

    /** 标准库缩写: St=std, Sa=std::allocator, Ss=std::string ... (可带模板参数) */
    private String parseStdName() {
        if (!eat('S')) return null;
        if (eof()) return null;
        String ab = STD_ABBREV.get(next());
        if (ab == null) return null;
        if (peek() == 'I') {
            String targs = parseTemplateArgs();
            if (targs == null) return null;
            return ab + "<" + targs + ">";
        }
        return ab;
    }

    /**
     * <nested-name> ::= N [<CV-qualifiers>] [<ref-qualifier>]
     *                   <prefix> <unqualified-name> [<ref-qualifier>] E
     * asType=true 时 CV/ref 拼在末尾 (类型语境); 否则存到 funcCv/funcRef
     * (函数语境的 CV/ref 需放在参数列表之后).
     */
    private String parseNestedName(boolean asType) {
        if (!eat('N')) return null;
        String cv = "";
        if (!eof() && (peek() == 'K' || peek() == 'V')) {
            StringBuilder c = new StringBuilder();
            while (!eof() && (peek() == 'K' || peek() == 'V')) {
                c.append(next() == 'K' ? " const" : " volatile");
            }
            cv = c.toString();
        }
        List<String> parts = new ArrayList<>();
        String last = null;
        while (!eof() && peek() != 'E') {
            // ref-qualifier 在 E 前: ...3barRE
            if ((peek() == 'R' || peek() == 'O') && pos + 1 < s.length()
                    && s.charAt(pos + 1) == 'E') {
                funcRef = next() == 'R' ? " &" : " &&";
                break;
            }
            String u;
            if (peek() == 'C' && pos + 1 < s.length()
                    && s.charAt(pos + 1) >= '1' && s.charAt(pos + 1) <= '3') {
                pos += 2; // 构造函数 → 类名 (不含模板参数)
                u = parts.isEmpty() ? null
                        : stripTemplateArgs(parts.get(parts.size() - 1));
            } else if (peek() == 'D' && pos + 1 < s.length()
                    && s.charAt(pos + 1) >= '0' && s.charAt(pos + 1) <= '2') {
                pos += 2; // 析构函数 → ~类名
                u = parts.isEmpty() ? null
                        : "~" + stripTemplateArgs(parts.get(parts.size() - 1));
            } else {
                u = parseUnqualifiedName();
            }
            if (u == null) return null;
            last = u;
            parts.add(u);
        }
        if (last == null) return null;
        if (!eat('E')) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("::");
            sb.append(parts.get(i));
        }
        if (asType) {
            sb.append(cv);
            if (!funcRef.isEmpty() && !cv.isEmpty()) sb.append(funcRef);
        } else {
            funcCv = cv;
        }
        return sb.toString();
    }

    /** <unqualified-name> ::= <len><id> [I <template-args> E] | <operator-name> */
    private String parseUnqualifiedName() {
        if (eof()) return null;
        char c = peek();
        if (c == 'S' && pos + 1 < s.length()
                && STD_ABBREV.containsKey(s.charAt(pos + 1))) {
            return parseStdName(); // 标准库缩写 (nested 内: std::vector 等)
        }
        if (c >= '0' && c <= '9') {
            int len = 0;
            while (!eof() && peek() >= '0' && peek() <= '9') {
                len = len * 10 + (next() - '0');
                if (len > 4096) return null;
            }
            if (eof() || pos + len > s.length()) return null;
            String id = s.substring(pos, pos + len);
            pos += len;
            if (peek() == 'I') {
                String targs = parseTemplateArgs();
                if (targs == null) return null;
                return id + "<" + targs + ">";
            }
            return id;
        }
        if (pos + 2 <= s.length()) {
            String two = s.substring(pos, pos + 2);
            String op = OPERATORS.get(two);
            if (op != null) {
                pos += 2;
                return "operator" + op;
            }
        }
        return null;
    }

    /** 类名去模板参数: std::vector<int, ...> → vector (ctor/dtor 用) */
    private static String stripTemplateArgs(String name) {
        int lt = name.indexOf('<');
        String base = lt >= 0 ? name.substring(0, lt) : name;
        int scope = base.lastIndexOf("::");
        return scope >= 0 ? base.substring(scope + 2) : base;
    }

    private String parseTemplateArgs() {
        if (!eat('I')) return null;
        List<String> args = new ArrayList<>();
        while (!eof() && peek() != 'E') {
            String a = parseTemplateArg();
            if (a == null) return null;
            args.add(a);
        }
        if (!eat('E')) return null;
        return join(args);
    }

    private String parseTemplateArg() {
        if (eof()) return null;
        char c = peek();
        if (c == 'L') { // 整数字面量: L<type><digits>E
            pos++;
            if (parseType() == null) return null;
            StringBuilder v = new StringBuilder();
            while (!eof() && peek() != 'E') v.append(next());
            if (!eat('E')) return null;
            return v.toString().trim();
        }
        if (c == 'X' || c == 'J') return null; // 表达式/参数包 — 不支持, 保守放弃
        return parseType();
    }

    private String parseBareFunctionType() {
        if (eof()) return "";
        if (peek() == 'v' && pos + 1 == s.length()) {
            pos++;
            return "";
        }
        List<String> params = new ArrayList<>();
        while (!eof()) {
            String t = parseType();
            if (t == null) return null;
            params.add(t);
        }
        return join(params);
    }

    private String parseType() {
        if (eof()) return null;
        char c = peek();
        switch (c) {
            case 'K': pos++; { String t = parseType(); return t == null ? null : t + " const"; }
            case 'V': pos++; { String t = parseType(); return t == null ? null : t + " volatile"; }
            case 'P': pos++; { String t = parseType(); return t == null ? null : t + "*"; }
            case 'R': pos++; { String t = parseType(); return t == null ? null : t + "&"; }
            case 'O': pos++; { String t = parseType(); return t == null ? null : t + "&&"; }
            case 'N': return parseNestedName(true);
            case 'S': return parseStdName();
            case 'A': { // 数组: A<len>_
                pos++;
                int len = 0;
                while (!eof() && peek() >= '0' && peek() <= '9') {
                    len = len * 10 + (next() - '0');
                    if (len > 4096) return null;
                }
                if (!eat('_')) return null;
                String t = parseType();
                return t == null ? null : t + "[" + len + "]";
            }
            case 'v': pos++; return "void";
            case 'w': pos++; return "wchar_t";
            case 'b': pos++; return "bool";
            case 'c': pos++; return "char";
            case 'a': pos++; return "signed char";
            case 'h': pos++; return "unsigned char";
            case 's': pos++; return "short";
            case 't': pos++; return "unsigned short";
            case 'i': pos++; return "int";
            case 'j': pos++; return "unsigned int";
            case 'l': pos++; return "long";
            case 'm': pos++; return "unsigned long";
            case 'x': pos++; return "long long";
            case 'y': pos++; return "unsigned long long";
            case 'n': pos++; return "__int128";
            case 'o': pos++; return "unsigned __int128";
            case 'f': pos++; return "float";
            case 'd': pos++; return "double";
            case 'e': pos++; return "long double";
            case 'g': pos++; return "__float128";
            case 'z': pos++; return "...";
            default:
                return parseUnqualifiedName(); // 数字开头 → 类类型
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
