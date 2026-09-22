package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.3: 未读局部变量消除 — 高级伪 C 抽象化 pass.
 *
 * <p>消除"被赋值但从未被读取"的局部变量赋值链. 典型场景:
 * <pre>
 *   var_8 = x1;     ← var_8 从未被读
 *   ...
 *   return;          →  var_8 = x1; 被删除
 * </pre>
 *
 * <p>这是寄存器无关化/汇编无关化的关键一步: 编译器生成的临时存储
 * (str xN, [sp,#off] + ldr xM, [sp,#off]) 在栈变量提升后留下大量
 * 死赋值, 正版 r2dec 会通过 def-use 消除它们.
 *
 * <p>规则: 局部变量 (var_* / 临时寄存器 x9+ / w9+) 若只被赋值从未被读,
 * 删除其赋值行. 参数 (x0-x7/r0-r3 入参) 和函数返回值寄存器除外.
 */
public final class UnusedLocalEliminationPass {

    private UnusedLocalEliminationPass() {
    }

    /** 匹配 "name = expr;" 形式的赋值行 (含缩进). */
    private static final Pattern ASSIGN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+);\\s*$");

    /** v4.0: 完整标识符匹配 (窗口 def-use 用). */
    private static boolean containsIdent(String name, String text) {
        if (text == null || text.isEmpty()) return false;
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }

    /** 需要保守保留的变量: 参数寄存器 / 返回寄存器 / sp / fp / lr. */
    private static final Set<String> KEEP = new HashSet<>();
    static {
        for (int i = 0; i <= 7; i++) {
            KEEP.add("x" + i);
            KEEP.add("w" + i);
            KEEP.add("r" + i);
        }
        KEEP.add("sp");
        KEEP.add("x29");
        KEEP.add("fp");
        KEEP.add("lr");
        KEEP.add("x30");
    }

    /**
     * 消除未读局部变量赋值. 迭代到不动点 (删除可能暴露更多未读变量).
     */
    public static List<String> optimize(List<String> body) {
        if (body == null || body.isEmpty()) return body;
        List<String> current = new ArrayList<>(body);
        boolean changed;
        int guard = 0;
        do {
            changed = eliminateOnce(current);
        } while (changed && ++guard < 10);
        return current;
    }

    /**
     * 单轮消除: 找到从未被读取的局部变量赋值并删除.
     *
     * @return true 若发生了删除
     */
    private static boolean eliminateOnce(List<String> lines) {
        // 第一遍: 统计每个变量的读取 (出现在 RHS 或非赋值位置) 与赋值
        Set<String> readVars = new HashSet<>();
        Set<String> assignedVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = ASSIGN.matcher(line);
            if (m.matches()) {
                String lhs = m.group(1);
                String rhs = m.group(2);
                assignedVars.add(lhs);
                // RHS 中的标识符都是读取
                collectIdentifiers(rhs, readVars);
            } else {
                // 非赋值行 (if/while/call/return/++): 整行标识符都是读取
                collectIdentifiers(line, readVars);
            }
        }

        // 第二遍: 删除"赋值了但从未读"的局部变量行
        boolean removed = false;
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher m = ASSIGN.matcher(line);
            if (m.matches()) {
                String lhs = m.group(1);
                String rhs = m.group(2);
                boolean isLocal = isEliminable(lhs);
                if (isLocal && !readVars.contains(lhs)) {
                    // 无副作用 RHS 才删 (调用/内存读保守保留)
                    if (!hasSideEffect(rhs)) {
                        removed = true;
                        continue;  // 跳过此行
                    }
                }
            }
            result.add(line);
        }
        lines.clear();
        lines.addAll(result);
        // v4.0: 窗口 def-use — 赋值被后续覆盖且窗口内无读取 → 删除.
        // 比全函数统计更强: 处理 var_8 = x1; ...; var_8 = var_8 + 1; 这类
        // 覆盖链 (第一行的值从未被读).
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = ASSIGN.matcher(lines.get(i));
            if (!m.matches()) continue;
            String lhs = m.group(1);
            if (!isEliminable(lhs)) continue;
            boolean read = false, covered = false;
            for (int j = i + 1; j < lines.size(); j++) {
                String l = lines.get(j);
                Matcher m2 = ASSIGN.matcher(l);
                if (m2.matches()) {
                    if (containsIdent(lhs, m2.group(2))) { read = true; break; }
                    if (m2.group(1).equals(lhs)) { covered = true; break; }
                } else if (containsIdent(lhs, l)) {
                    read = true;
                    break;
                }
            }
            if (covered && !read && !hasSideEffect(m.group(2))) {
                lines.remove(i);
                removed = true;
                i--;
            }
        }
        return removed;
    }

    /** 该变量是否为可消除的局部变量 (非参数/返回/栈指针). */
    private static boolean isEliminable(String name) {
        if (name == null || name.isEmpty()) return false;
        if (KEEP.contains(name)) return false;
        // var_* 栈变量 / 临时寄存器 (x9+ / w9+ / r4+) 可消除
        if (name.startsWith("var_")) return true;
        if (name.matches("[wxr]\\d+")) {
            int n = Integer.parseInt(name.substring(1));
            return n >= 8;  // x8+/w8+/r4+ 是临时寄存器
        }
        return false;
    }

    /** 提取字符串中的所有标识符 (读取集合). */
    private static void collectIdentifiers(String text, Set<String> out) {
        if (text == null) return;
        Matcher m = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b").matcher(text);
        while (m.find()) {
            String tok = m.group(1);
            // 跳过 C 关键字 / 类型
            if (isKeyword(tok)) continue;
            out.add(tok);
        }
    }

    /** 简单副作用检测: 调用 / 内存读 / 自增 / 条件. */
    private static boolean hasSideEffect(String rhs) {
        if (rhs == null) return false;
        if (rhs.contains("(")) return true;        // 函数调用/转换
        if (rhs.contains("*(")) return true;       // 内存读
        if (rhs.contains("__")) return true;       // builtin
        return false;
    }

    private static boolean isKeyword(String tok) {
        switch (tok) {
            case "if": case "else": case "while": case "do": case "for":
            case "return": case "switch": case "case": case "break":
            case "continue": case "goto": case "sizeof": case "void":
            case "int": case "long": case "char": case "short": case "float":
            case "double": case "unsigned": case "signed": case "bool":
            case "uint32_t": case "uint64_t": case "int32_t": case "int64_t":
            case "uint8_t": case "int8_t": case "uint16_t": case "int16_t":
            case "size_t": case "NULL": case "true": case "false":
                return true;
            default:
                return false;
        }
    }
}
