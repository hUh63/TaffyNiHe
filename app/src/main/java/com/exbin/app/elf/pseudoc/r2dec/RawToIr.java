package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v4.0: RawToIr 解析桥 — 把 handler 生成的 IrNode 树 (每条指令的 nodes)
 * 解析为平坦指令视图 {kind, dst, src, uses}, 供 IrOptimizer 做块内数据流优化.
 * <p>
 * 只识别可安全处理的形态 (赋值/返回/调用), 其它节点原样保留 (零回归).
 * Raw 文本的 use 提取限定在寄存器/栈变量 token, 符号名与函数名不参与传播.
 */
public final class RawToIr {

    public enum Kind {
        /** dst = src (src 为纯变量, 可拷贝传播). */
        ASSIGN_VAR,
        /** dst = src (src 为数值常量). */
        ASSIGN_NUM,
        /** dst = src (src 为二元运算, 可常量折叠). */
        ASSIGN_BINOP,
        /** dst = src (dst 是内存表达式, 如 *(uint32_t*)(sp + 0x14)). */
        ASSIGN_MEM,
        /** 其它赋值形态 (保留). */
        ASSIGN_OTHER,
        /** 函数调用语句. */
        CALL,
        /** return 语句. */
        RETURN,
        /** 其它节点 (原样保留). */
        OTHER
    }

    /** 平坦指令: 传播时可改写 src / callArgs. */
    public static final class FlatInsn {
        public Kind kind;
        public String dst;            // 赋值目标 (变量名或内存表达式), 非赋值 null
        public IrNode src;            // 赋值源 / return 表达式 / 原节点引用
        public final List<String> uses = new ArrayList<>();
        public String callFunc;       // CALL: 函数名
        public List<String> callArgs; // CALL: 可变参数列表 (常量传播用)
        public final IrNode original; // 非可重建节点时保留原引用

        FlatInsn(Kind kind, String dst, IrNode src) {
            this.kind = kind; this.dst = dst; this.src = src;
            this.original = src;
        }
    }

    /** Raw 文本中的变量 token: 寄存器 + 栈变量 (符号名/函数名不提取). */
    private static final Pattern VAR_TOKEN = Pattern.compile(
            "\\b(x\\d+|w\\d+|r\\d+|s\\d+|d\\d+|q\\d+|v\\d+|v[0-9a-f]+\\." +
            "\\d+[bsdh]|sp|lr|pc|fp|xzr|wzr|" +
            "(?:var|local|arg|global|gvar)_[a-zA-Z0-9_]+)\\b");

    /** 赋值目标是否为内存表达式 (内存写时其地址表达式也是 use). */
    public static boolean isMemDst(String dst) {
        return dst != null && (dst.contains("*(") || dst.contains("["));
    }

    /** 从文本提取寄存器/栈变量 token. */
    public static void extractUses(String text, List<String> out) {
        if (text == null || text.isEmpty()) return;
        Matcher m = VAR_TOKEN.matcher(text);
        while (m.find()) {
            String tok = m.group(1);
            if (!out.contains(tok)) out.add(tok);
        }
    }

    /** 将单个 IrNode 扁平化. */
    public static FlatInsn flatten(IrNode node) {
        if (node instanceof IrNode.Assign) {
            IrNode.Assign a = (IrNode.Assign) node;
            if (a.dst == null) return new FlatInsn(Kind.OTHER, null, node);
            boolean mem = isMemDst(a.dst);
            FlatInsn f;
            if (mem) {
                f = new FlatInsn(Kind.ASSIGN_MEM, a.dst, a.src);
                extractUses(a.dst, f.uses);
            } else if (a.src instanceof IrNode.Num) {
                f = new FlatInsn(Kind.ASSIGN_NUM, a.dst, a.src);
            } else if (a.src instanceof IrNode.Var) {
                f = new FlatInsn(Kind.ASSIGN_VAR, a.dst, a.src);
            } else if (a.src instanceof IrNode.CallExpr) {
                // v4.0: dst = CallExpr(...) 视作调用 — 有副作用, 参与
                //   clobber 清理且不能被死赋值消除删除
                IrNode.CallExpr c = (IrNode.CallExpr) a.src;
                f = new FlatInsn(Kind.CALL, null, node);
                f.callFunc = c.func;
                f.callArgs = new ArrayList<>(c.args);
                for (String arg : c.args) extractUses(arg, f.uses);
                return f;
            } else if (a.src instanceof IrNode.BinOp) {
                f = new FlatInsn(Kind.ASSIGN_BINOP, a.dst, a.src);
            } else {
                f = new FlatInsn(Kind.ASSIGN_OTHER, a.dst, a.src);
            }
            collectUses(a.src, f.uses);
            return f;
        }
        if (node instanceof IrNode.Return) {
            IrNode.Return r = (IrNode.Return) node;
            FlatInsn f = new FlatInsn(Kind.RETURN, null, r.expr);
            if (r.expr != null) collectUses(r.expr, f.uses);
            return f;
        }
        if (node instanceof IrNode.CallStmt) {
            IrNode.CallStmt c = (IrNode.CallStmt) node;
            FlatInsn f = new FlatInsn(Kind.CALL, null, node);
            f.callFunc = c.func;
            f.callArgs = new ArrayList<>(c.args);
            for (String arg : c.args) extractUses(arg, f.uses);
            return f;
        }
        if (node instanceof IrNode.CallExpr) {
            IrNode.CallExpr c = (IrNode.CallExpr) node;
            FlatInsn f = new FlatInsn(Kind.CALL, null, node);
            f.callFunc = c.func;
            f.callArgs = new ArrayList<>(c.args);
            for (String arg : c.args) extractUses(arg, f.uses);
            return f;
        }
        FlatInsn f = new FlatInsn(Kind.OTHER, null, node);
        collectUses(node, f.uses);
        return f;
    }

    /** 递归收集表达式树中的变量 use (Var 节点 + Raw 文本 token). */
    public static void collectUses(IrNode n, List<String> out) {
        if (n == null) return;
        if (n instanceof IrNode.Var) {
            String v = ((IrNode.Var) n).name;
            if (!out.contains(v)) out.add(v);
            return;
        }
        if (n instanceof IrNode.Num || n instanceof IrNode.Str
                || n instanceof IrNode.AddrOf) {
            return; // 常量/字符串/取地址不读变量
        }
        if (n instanceof IrNode.BinOp) {
            IrNode.BinOp b = (IrNode.BinOp) n;
            collectUses(b.left, out);
            collectUses(b.right, out);
            return;
        }
        if (n instanceof IrNode.UnaryOp) {
            collectUses(((IrNode.UnaryOp) n).operand, out);
            return;
        }
        if (n instanceof IrNode.Ternary) {
            IrNode.Ternary t = (IrNode.Ternary) n;
            collectUses(t.cond, out);
            collectUses(t.trueExpr, out);
            collectUses(t.falseExpr, out);
            return;
        }
        if (n instanceof IrNode.Cast) {
            collectUses(((IrNode.Cast) n).expr, out);
            return;
        }
        if (n instanceof IrNode.MemDeref) {
            collectUses(((IrNode.MemDeref) n).pointer, out);
            return;
        }
        if (n instanceof IrNode.Raw) {
            extractUses(((IrNode.Raw) n).text, out);
            return;
        }
        if (n instanceof IrNode.Composed) {
            for (IrNode p : ((IrNode.Composed) n).parts) collectUses(p, out);
            return;
        }
        // CallExpr / 其它: 从 toC 文本兜底提取 (不破坏节点本身)
        try {
            String c = n.toC();
            if (c != null && !c.isEmpty()) extractUses(c, out);
        } catch (Exception ignore) {
            // 不可渲染节点 — 无 use 信息, 保守跳过
        }
    }

    private RawToIr() {}
}
