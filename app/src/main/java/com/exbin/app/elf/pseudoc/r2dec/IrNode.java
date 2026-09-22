package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * IR Node hierarchy — Layer 1 of the 3-layer architecture (IrNode → Stmt → Printer).
 * <p>Ported from r2dec's core/base.js. Each node has a {@code toC()} method that
 * returns its C representation as a string (without trailing semicolon or indentation).
 * Control-flow nodes contain child node lists for recursive printing.
 */
public abstract class IrNode {

    /** Render to C code string (no semicolon, no indent). */
    public abstract String toC();

    // ── Expressions ──

    public static class Var extends IrNode {
        public final String name;
        public Var(String name) { this.name = name; }
        @Override public String toC() { return name; }
    }

    public static class Num extends IrNode {
        public final String value;
        public Num(String value) { this.value = value; }
        public Num(long val) { this.value = "0x" + Long.toUnsignedString(val, 16); }
        @Override public String toC() { return value; }
    }

    public static class Str extends IrNode {
        public final String content;
        /** v2.9.39: 伪C输出中的字符串最大显示长度, 超过则截断. */
        private static final int MAX_DISPLAY_LEN = 80;
        /** v2.9.42: 超过此长度且无空格的字符串视为拼接 bug, 进一步截断.
         *  从 40 降至 16, 以捕获 "JNIBridgesrc/lib.rsD" (20字符) 等短拼接. */
        private static final int SUSPICIOUS_LEN = 16;
        public Str(String content) { this.content = content; }
        @Override public String toC() {
            if (content == null) return "\"\"";
            String s = content;
            // v2.9.39: 截断超长字符串, 防止拼接 bug 污染输出
            if (s.length() > MAX_DISPLAY_LEN) {
                s = s.substring(0, MAX_DISPLAY_LEN);
            }
            // v2.9.42: 检测拼接 bug — 超长且无空格的字符串很可能是多个相邻
            // 字符串被错误合并 (如 "JNIBridgesrc/lib.rsD" 20字符无空格)
            // v2.9.43: 豁免 C++ mangled names (_Z*) 和含路径分隔符的字符串
            if (s.length() > SUSPICIOUS_LEN && s.indexOf(' ') < 0) {
                boolean isLegit = s.startsWith("_Z") || s.contains("/");
                if (!isLegit) {
                    s = s.substring(0, SUSPICIOUS_LEN);
                }
            }
            // v2.9.42: Rust 路径拼接截断 — "msg/home/user/..." → "msg"
            if (s.length() > 20 && s.indexOf(' ') >= 0) {
                String[] prefixes = {"/home/", "/Users/", "/root/", "/tmp/",
                        "/var/", "/opt/", "/usr/", "/etc/", "/data/", "/system/"};
                for (String pfx : prefixes) {
                    int idx = s.indexOf(pfx);
                    if (idx > 10) {
                        s = s.substring(0, idx);
                        break;
                    }
                }
            }
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\': sb.append("\\\\"); break;
                    case '"':  sb.append("\\\""); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c >= 0x20 && c < 0x7f) sb.append(c);
                        else sb.append(String.format("\\x%02x", (int) c));
                }
            }
            // 如果被截断, 添加省略号标记
            if (content.length() > MAX_DISPLAY_LEN) {
                sb.append("...\"");
            } else {
                sb.append('"');
            }
            return sb.toString();
        }
    }

    public static class BinOp extends IrNode {
        public final String op;
        public final IrNode left, right;
        public BinOp(String op, IrNode left, IrNode right) {
            this.op = op; this.left = left; this.right = right;
        }
        @Override public String toC() {
            return autoParen(left) + " " + op + " " + autoParen(right);
        }
    }

    public static class UnaryOp extends IrNode {
        public final String op;
        public final IrNode operand;
        public final boolean postfix;
        public UnaryOp(String op, IrNode operand, boolean postfix) {
            this.op = op; this.operand = operand; this.postfix = postfix;
        }
        public UnaryOp(String op, IrNode operand) {
            this(op, operand, false);
        }
        @Override public String toC() {
            return postfix ? operand.toC() + op : op + operand.toC();
        }
    }

    public static class Ternary extends IrNode {
        public final IrNode cond, trueExpr, falseExpr;
        public Ternary(IrNode cond, IrNode trueExpr, IrNode falseExpr) {
            this.cond = cond; this.trueExpr = trueExpr; this.falseExpr = falseExpr;
        }
        @Override public String toC() {
            return autoParen(cond) + " ? " + autoParen(trueExpr) + " : " + autoParen(falseExpr);
        }
    }

    public static class Cast extends IrNode {
        public final String type;
        public final IrNode expr;
        public Cast(String type, IrNode expr) { this.type = type; this.expr = expr; }
        @Override public String toC() { return "(" + type + ")" + expr.toC(); }
    }

    public static class MemDeref extends IrNode {
        public final IrNode pointer;
        public final String typeStr;
        public MemDeref(IrNode pointer, String typeStr) {
            this.pointer = pointer; this.typeStr = typeStr;
        }
        @Override public String toC() {
            return "*(" + typeStr + "*)" + autoParen(pointer);
        }
    }

    public static class CallExpr extends IrNode {
        public final String func;
        public final List<String> args;
        public CallExpr(String func, List<String> args) {
            this.func = func; this.args = args;
        }
        @Override public String toC() {
            if (func.indexOf('(') >= 0) return func; // fcn(0x1338): 地址即参数
            return func + "(" + String.join(", ", args) + ")";
        }
    }

    public static class AddrOf extends IrNode {
        public final String symbol;
        public AddrOf(String symbol) { this.symbol = symbol; }
        @Override public String toC() { return "&" + symbol; }
    }

    // ── Statements ──

    public static class Assign extends IrNode {
        public final String dst;
        public final IrNode src;
        public Assign(String dst, IrNode src) { this.dst = dst; this.src = src; }
        @Override public String toC() {
            if (src instanceof BinOp) {
                BinOp b = (BinOp) src;
                if (b.left instanceof Var && ((Var) b.left).name.equals(dst)) {
                    return dst + " " + b.op + "= " + b.right.toC();
                }
            }
            if (src instanceof Var && ((Var) src).name.equals(dst)) {
                return "";
            }
            return dst + " = " + src.toC();
        }
    }

    public static class Return extends IrNode {
        public final IrNode expr;
        public Return(IrNode expr) { this.expr = expr; }
        public Return() { this.expr = null; }
        @Override public String toC() {
            return expr != null ? "return " + expr.toC() : "return";
        }
    }

    public static class Nop extends IrNode {
        @Override public String toC() { return ""; }
    }

    public static class Goto extends IrNode {
        public final String label;
        public Goto(String label) { this.label = label; }
        @Override public String toC() { return "goto " + label; }
    }

    public static class Label extends IrNode {
        public final String name;
        public Label(String name) { this.name = name; }
        @Override public String toC() { return name + ":"; }
    }

    public static class Break extends IrNode {
        @Override public String toC() { return "break"; }
    }
    public static class Continue extends IrNode {
        @Override public String toC() { return "continue"; }
    }

    public static class Raw extends IrNode {
        public final String text;
        public Raw(String text) { this.text = text; }
        @Override public String toC() { return text; }
    }

    public static class CallStmt extends IrNode {
        public final String func;
        public final List<String> args;
        public CallStmt(String func, List<String> args) {
            this.func = func; this.args = args;
        }
        @Override public String toC() {
            if (func.indexOf('(') >= 0) return func; // fcn(0x1338): 地址即参数
            return func + "(" + String.join(", ", args) + ")";
        }
    }

    // ── Control Flow ──

    public static class IfStmt extends IrNode {
        public IrNode cond;
        public final List<IrNode> thenBody = new ArrayList<>();
        public final List<IrNode> elseBody = new ArrayList<>();
        public boolean hasElse = false;
        @Override public String toC() { return "if (" + cond.toC() + ")"; }
    }

    public static class WhileStmt extends IrNode {
        public IrNode cond;
        public final List<IrNode> body = new ArrayList<>();
        @Override public String toC() { return "while (" + cond.toC() + ")"; }
    }

    public static class DoWhileStmt extends IrNode {
        public IrNode cond;
        public final List<IrNode> body = new ArrayList<>();
        @Override public String toC() { return "while (" + cond.toC() + ")"; }
    }

    public static class Composed extends IrNode {
        public final List<IrNode> parts = new ArrayList<>();
        @Override public String toC() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(parts.get(i).toC());
            }
            return sb.toString();
        }
    }

    // ── Helpers ──

    static String autoParen(IrNode n) {
        if (n instanceof BinOp || n instanceof Ternary) {
            return "(" + n.toC() + ")";
        }
        return n.toC();
    }

    // ── Factory helpers (mirroring r2dec's Base.* API) ──

    public static IrNode assign(String dst, IrNode src) { return new Assign(dst, src); }
    public static IrNode assign(String dst, String src) { return new Assign(dst, new Var(src)); }
    public static IrNode num(String v) { return new Num(v); }
    public static IrNode num(long v) { return new Num(v); }
    public static IrNode var(String n) { return new Var(n); }
    public static IrNode str(String s) { return new Str(s); }
    public static IrNode nop() { return new Nop(); }
    public static IrNode retNull() { return new Return(); }

    public static IrNode add(String dst, IrNode a, IrNode b) {
        if (a instanceof Var && ((Var) a).name.equals(dst) && b instanceof Num && isOne(((Num) b).value))
            return new Raw(dst + "++");
        return new Assign(dst, new BinOp("+", a, b));
    }
    public static IrNode sub(String dst, IrNode a, IrNode b) {
        if (a instanceof Var && ((Var) a).name.equals(dst) && b instanceof Num && isOne(((Num) b).value))
            return new Raw(dst + "--");
        return new Assign(dst, new BinOp("-", a, b));
    }

    private static boolean isOne(String v) {
        return v.equals("1") || v.equals("0x1");
    }
    public static IrNode mul(String dst, IrNode a, IrNode b) { return new Assign(dst, new BinOp("*", a, b)); }
    public static IrNode div(String dst, IrNode a, IrNode b) { return new Assign(dst, new BinOp("/", a, b)); }
    public static IrNode mod(String dst, IrNode a, IrNode b) { return new Assign(dst, new BinOp("%", a, b)); }
    public static IrNode and(String dst, IrNode a, IrNode b) {
        if (b instanceof Num && ((Num) b).value.equals("0")) return new Assign(dst, new Num("0"));
        return new Assign(dst, new BinOp("&", a, b));
    }
    public static IrNode or(String dst, IrNode a, IrNode b) {
        if (b instanceof Num && ((Num) b).value.equals("0")) return new Assign(dst, a);
        return new Assign(dst, new BinOp("|", a, b));
    }
    public static IrNode xor(String dst, IrNode a, IrNode b) {
        if (a instanceof Var && b instanceof Var && ((Var) a).name.equals(((Var) b).name))
            return new Assign(dst, new Num("0"));
        return new Assign(dst, new BinOp("^", a, b));
    }
    public static IrNode neg(String dst, IrNode src) { return new Assign(dst, new UnaryOp("-", src)); }
    public static IrNode not(String dst, IrNode src) { return new Assign(dst, new UnaryOp("~", src)); }
    public static IrNode shl(String dst, IrNode a, IrNode b) { return new Assign(dst, new BinOp("<<", a, b)); }
    public static IrNode shr(String dst, IrNode a, IrNode b) { return new Assign(dst, new BinOp(">>", a, b)); }

    public static IrNode readMem(String ptr, String reg, int bits, boolean signed) {
        String type = (signed ? "int" : "uint") + bits + "_t";
        return new Assign(reg, new Raw("*(" + type + "*)(" + ptr + ")"));
    }
    public static IrNode writeMem(String ptr, String reg, int bits, boolean signed) {
        String type = (signed ? "int" : "uint") + bits + "_t";
        return new Assign("*(" + type + "*)(" + ptr + ")", new Var(reg));
    }

    public static IrNode condAssign(String dst, IrNode a, IrNode b, String cond, IrNode trueE, IrNode falseE) {
        IrNode condExpr = makeCondition(a, b, cond, false);
        return new Assign(dst, new Ternary(condExpr, trueE, falseE));
    }

    public static IrNode makeCondition(IrNode a, IrNode b, String cond, boolean invert) {
        String op;
        String ci = cond.toUpperCase();
        if (invert) {
            switch (ci) {
                case "EQ": op = "!="; break;
                case "NE": op = "=="; break;
                case "LT": op = ">="; break;
                case "LE": op = ">"; break;
                case "GT": op = "<="; break;
                case "GE": op = "<"; break;
                default:   op = "!="; break;
            }
        } else {
            switch (ci) {
                case "EQ": op = "=="; break;
                case "NE": op = "!="; break;
                case "LT": op = "<"; break;
                case "LE": op = "<="; break;
                case "GT": op = ">"; break;
                case "GE": op = ">="; break;
                default:   op = "=="; break;
            }
        }
        return new BinOp(op, a, b);
    }

    public static IrNode composed(IrNode... parts) {
        Composed c = new Composed();
        c.parts.addAll(Arrays.asList(parts));
        return c;
    }

    public static IrNode cast(String type, IrNode expr) { return new Cast(type, expr); }
}
