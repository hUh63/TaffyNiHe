package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v4.0: 块内 IR 优化器 — 对每个基本块的指令 IR (IrInsn.nodes 展平) 做
 * 安全的数据流优化:
 * <ul>
 *   <li>常量传播 + 二元常量折叠 (含调用参数内联)</li>
 *   <li>拷贝传播 (源寄存器被重定义即失效)</li>
 *   <li>死赋值消除 (被覆盖 / return 块内反向 liveness)</li>
 *   <li>内存自写消除 (读回原值再写回同一地址)</li>
 *   <li>自赋值清理 (x0 = x0)</li>
 * </ul>
 * 只删除可证明无副作用的赋值, 其它节点原样保留 (零回归).
 */
public final class IrOptimizer {

    private static final int MAX_ROUNDS = 3;

    /** v4.0: 调用者保存寄存器 — 调用后值不确定, 必须清除其常量/拷贝映射. */
    private static final Set<String> CLOBBERED = new HashSet<>();
    static {
        for (int i = 0; i <= 18; i++) {
            CLOBBERED.add("x" + i);
            CLOBBERED.add("w" + i);
        }
        for (int i = 0; i <= 12; i++) {
            CLOBBERED.add("r" + i);
        }
    }

    private static void clearClobbered(Map<String, ?> map) {
        for (String reg : CLOBBERED) map.remove(reg);
    }

    /** 优化入口: 对块列表中每个块做块内优化. */
    public static void optimizeBlocks(List<ControlFlowStructurer.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        for (ControlFlowStructurer.Block b : blocks) {
            if (b.insns == null || b.insns.isEmpty()) continue;
            optimizeBlock(b);
        }
    }

    private static void optimizeBlock(ControlFlowStructurer.Block b) {
        List<RawToIr.FlatInsn> flats = new ArrayList<>();
        List<int[]> backref = new ArrayList<>(); // {insnIdx, nodeIdx}
        for (int ii = 0; ii < b.insns.size(); ii++) {
            IrInsn insn = b.insns.get(ii);
            if (insn == null || !insn.valid || insn.nodes == null) continue;
            for (int ni = 0; ni < insn.nodes.size(); ni++) {
                IrNode nd = insn.nodes.get(ni);
                if (nd == null) continue;
                flats.add(RawToIr.flatten(nd));
                backref.add(new int[]{ii, ni});
            }
        }
        if (flats.isEmpty()) return;

        boolean[] dead = new boolean[flats.size()];
        boolean isReturnBlock = b.isReturn;
        if (!isReturnBlock) {
            for (RawToIr.FlatInsn f : flats) {
                if (f.kind == RawToIr.Kind.RETURN) { isReturnBlock = true; break; }
            }
        }

        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            changed |= constProp(flats, dead);
            changed |= copyProp(flats, dead);
            changed |= deadAssign(flats, dead, isReturnBlock);
            changed |= selfWriteElim(flats, dead);
            changed |= selfAssignElim(flats, dead);
            if (!changed) break;
        }

        // 从后往前移除死节点 (索引稳定)
        for (int i = flats.size() - 1; i >= 0; i--) {
            if (!dead[i]) continue;
            int[] ref = backref.get(i);
            IrInsn insn = b.insns.get(ref[0]);
            if (ref[1] < insn.nodes.size()) {
                insn.nodes.remove(ref[1]);
            }
        }
        // 重建被传播改写的节点
        for (int i = 0; i < flats.size(); i++) {
            if (dead[i]) continue;
            RawToIr.FlatInsn f = flats.get(i);
            IrNode rebuilt = rebuild(f);
            if (rebuilt != null) {
                int[] ref = backref.get(i);
                b.insns.get(ref[0]).nodes.set(ref[1], rebuilt);
            }
        }
    }

    /** 重建节点 (传播已改 src/callArgs 时生成新节点). */
    private static IrNode rebuild(RawToIr.FlatInsn f) {
        switch (f.kind) {
            case ASSIGN_VAR:
            case ASSIGN_NUM:
            case ASSIGN_BINOP:
            case ASSIGN_MEM:
            case ASSIGN_OTHER:
                return new IrNode.Assign(f.dst, f.src);
            case RETURN:
                if (f.src == null) return new IrNode.Return();
                return new IrNode.Return(f.src);
            case CALL:
                if (f.original instanceof IrNode.CallStmt) {
                    IrNode.CallStmt c = (IrNode.CallStmt) f.original;
                    if (argsEqual(c.args, f.callArgs)) return c;
                    return new IrNode.CallStmt(c.func, f.callArgs);
                }
                if (f.original instanceof IrNode.CallExpr) {
                    IrNode.CallExpr c = (IrNode.CallExpr) f.original;
                    if (argsEqual(c.args, f.callArgs)) return c;
                    return new IrNode.CallExpr(c.func, f.callArgs);
                }
                return f.original;
            default:
                return f.original;
        }
    }

    private static boolean argsEqual(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    // ── Pass 1: 常量传播 + 二元折叠 + 调用参数内联 ──

    private static boolean constProp(List<RawToIr.FlatInsn> flats, boolean[] dead) {
        boolean changed = false;
        Map<String, IrNode> consts = new HashMap<>();
        for (int i = 0; i < flats.size(); i++) {
            if (dead[i]) continue;
            RawToIr.FlatInsn f = flats.get(i);
            switch (f.kind) {
                case ASSIGN_VAR:
                case ASSIGN_BINOP:
                    if (f.dst == null || RawToIr.isMemDst(f.dst)) break;
                    // 替换操作数中的已知常量
                    IrNode s = subst(f.src, consts);
                    if (s != f.src) { f.src = s; changed = true; }
                    // 二元常量折叠
                    if (f.kind == RawToIr.Kind.ASSIGN_BINOP) {
                        IrNode folded = foldBinOp((IrNode.BinOp) s);
                        if (folded != null) {
                            f.kind = RawToIr.Kind.ASSIGN_NUM;
                            f.src = folded;
                            changed = true;
                        }
                    }
                    consts.remove(f.dst);
                    if (f.kind == RawToIr.Kind.ASSIGN_NUM
                            && f.src instanceof IrNode.Num) {
                        consts.put(f.dst, f.src);
                    }
                    break;
                case ASSIGN_NUM:
                    if (f.dst == null || RawToIr.isMemDst(f.dst)) break;
                    consts.put(f.dst, f.src);
                    break;
                case ASSIGN_MEM:
                    break; // 内存写不改寄存器
                case CALL: {
                    // 调用参数常量内联: arg token == 已知常量变量 → 替换
                    List<String> args = f.callArgs;
                    if (args == null) break;
                    for (int k = 0; k < args.size(); k++) {
                        String arg = args.get(k).trim();
                        if (consts.containsKey(arg)) {
                            args.set(k, consts.get(arg).toC());
                            f.uses.remove(arg);
                            changed = true;
                        }
                    }
                    // v4.0: 调用 clobber 调用者保存寄存器 — 清除其常量映射
                    //   (否则 mov x0,#5; bl foo; mov x1,x0 会把 x1 错内联成 5)
                    clearClobbered(consts);
                    break;
                }
                case RETURN:
                    if (f.src != null) {
                        IrNode r = subst(f.src, consts);
                        if (r != f.src) { f.src = r; changed = true; }
                    }
                    break;
                default:
                    break;
            }
        }
        return changed;
    }

    /** 递归替换表达式树中的 Var (仅在映射命中时重建). */
    private static IrNode subst(IrNode n, Map<String, IrNode> map) {
        if (n instanceof IrNode.Var) {
            IrNode r = map.get(((IrNode.Var) n).name);
            return r != null ? r : n;
        }
        if (n instanceof IrNode.BinOp) {
            IrNode.BinOp b = (IrNode.BinOp) n;
            IrNode l = subst(b.left, map);
            IrNode r = subst(b.right, map);
            if (l != b.left || r != b.right) return new IrNode.BinOp(b.op, l, r);
            return b;
        }
        if (n instanceof IrNode.UnaryOp) {
            IrNode.UnaryOp u = (IrNode.UnaryOp) n;
            IrNode o = subst(u.operand, map);
            if (o != u.operand) return new IrNode.UnaryOp(u.op, o, u.postfix);
            return u;
        }
        if (n instanceof IrNode.MemDeref) {
            IrNode.MemDeref m = (IrNode.MemDeref) n;
            IrNode p = subst(m.pointer, map);
            if (p != m.pointer) return new IrNode.MemDeref(p, m.typeStr);
            return m;
        }
        if (n instanceof IrNode.Cast) {
            IrNode.Cast c = (IrNode.Cast) n;
            IrNode e = subst(c.expr, map);
            if (e != c.expr) return new IrNode.Cast(c.type, e);
            return c;
        }
        if (n instanceof IrNode.Ternary) {
            IrNode.Ternary t = (IrNode.Ternary) n;
            IrNode c = subst(t.cond, map);
            IrNode a = subst(t.trueExpr, map);
            IrNode b = subst(t.falseExpr, map);
            if (c != t.cond || a != t.trueExpr || b != t.falseExpr) {
                return new IrNode.Ternary(c, a, b);
            }
            return t;
        }
        return n;
    }

    /** 二元常量折叠 (64 位无符号环绕), 不能折叠返回 null. */
    private static IrNode foldBinOp(IrNode.BinOp b) {
        if (!(b.left instanceof IrNode.Num) || !(b.right instanceof IrNode.Num)) {
            return null;
        }
        Long l = parseNum(((IrNode.Num) b.left).value);
        Long r = parseNum(((IrNode.Num) b.right).value);
        if (l == null || r == null) return null;
        long v;
        switch (b.op) {
            case "+":  v = l + r; break;
            case "-":  v = l - r; break;
            case "*":  v = l * r; break;
            case "&":  v = l & r; break;
            case "|":  v = l | r; break;
            case "^":  v = l ^ r; break;
            case "<<": v = l << (int) (r & 63); break;
            case ">>": v = l >>> (int) (r & 63); break;
            default:   return null;
        }
        return new IrNode.Num(v);
    }

    /** 解析 Num 文本 (0x 前缀十六进制或十进制), 失败返回 null. */
    private static Long parseNum(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.trim();
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Long.parseUnsignedLong(t.substring(2), 16);
            }
            return Long.parseUnsignedLong(t, 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Pass 2: 拷贝传播 (源寄存器被重定义即失效) ──

    private static boolean copyProp(List<RawToIr.FlatInsn> flats, boolean[] dead) {
        boolean changed = false;
        Map<String, String> copies = new HashMap<>(); // v → w (v 当前等于 w)
        for (int i = 0; i < flats.size(); i++) {
            if (dead[i]) continue;
            RawToIr.FlatInsn f = flats.get(i);
            switch (f.kind) {
                case ASSIGN_VAR: {
                    IrNode s = f.src;
                    if (s instanceof IrNode.Var) {
                        String w = ((IrNode.Var) s).name;
                        if (!w.equals(f.dst) && copies.containsKey(w)) {
                            // v = w, 而 w 又是拷贝 → v = copies[w]
                            String t = copies.get(w);
                            f.src = new IrNode.Var(t);
                            changed = true;
                        } else if (!w.equals(f.dst)) {
                            copies.put(f.dst, w);
                        }
                    }
                    // 注意: 不在此处 remove(f.dst) — put 已覆盖旧拷贝条目,
                    // 自赋值 (v = v) 时旧映射仍有效 (v 值未变)
                    break;
                }
                case ASSIGN_NUM:
                case ASSIGN_BINOP:
                case ASSIGN_OTHER:
                    if (f.dst != null) copies.remove(f.dst);
                    break;
                case CALL: {
                    List<String> args = f.callArgs;
                    if (args == null) break;
                    for (int k = 0; k < args.size(); k++) {
                        String arg = args.get(k).trim();
                        if (copies.containsKey(arg)) {
                            args.set(k, copies.get(arg));
                            f.uses.remove(arg);
                            f.uses.add(copies.get(arg));
                            changed = true;
                        }
                    }
                    // v4.0: 调用 clobber 调用者保存寄存器 — 清除其拷贝映射
                    //   (键: x0 自身; 值: x1 = x0 的拷贝也一并失效)
                    copies.keySet().removeIf(CLOBBERED::contains);
                    copies.values().removeIf(CLOBBERED::contains);
                    break;
                }
                case RETURN:
                    if (f.src != null) {
                        IrNode r = substCopy(f.src, copies);
                        if (r != f.src) { f.src = r; changed = true; }
                    }
                    break;
                default:
                    break;
            }
            // 源寄存器被重定义 → 相关拷贝映射失效
            if (f.dst != null && !RawToIr.isMemDst(f.dst)) {
                String d = f.dst;
                // 从 copies 中移除"值为 d"的条目 (v = d 且 d 被重定义)
                copies.entrySet().removeIf(e -> e.getValue().equals(d));
            }
        }
        return changed;
    }

    /** 拷贝传播专用的表达式替换 (Var → Var). */
    private static IrNode substCopy(IrNode n, Map<String, String> map) {
        if (n instanceof IrNode.Var) {
            String r = map.get(((IrNode.Var) n).name);
            return r != null ? new IrNode.Var(r) : n;
        }
        if (n instanceof IrNode.BinOp) {
            IrNode.BinOp b = (IrNode.BinOp) n;
            IrNode l = substCopy(b.left, map);
            IrNode r = substCopy(b.right, map);
            if (l != b.left || r != b.right) return new IrNode.BinOp(b.op, l, r);
            return b;
        }
        if (n instanceof IrNode.UnaryOp) {
            IrNode.UnaryOp u = (IrNode.UnaryOp) n;
            IrNode o = substCopy(u.operand, map);
            if (o != u.operand) return new IrNode.UnaryOp(u.op, o, u.postfix);
            return u;
        }
        if (n instanceof IrNode.MemDeref) {
            IrNode.MemDeref m = (IrNode.MemDeref) n;
            IrNode p = substCopy(m.pointer, map);
            if (p != m.pointer) return new IrNode.MemDeref(p, m.typeStr);
            return m;
        }
        if (n instanceof IrNode.Cast) {
            IrNode.Cast c = (IrNode.Cast) n;
            IrNode e = substCopy(c.expr, map);
            if (e != c.expr) return new IrNode.Cast(c.type, e);
            return c;
        }
        return n;
    }

    // ── Pass 3: 死赋值消除 ──

    private static boolean deadAssign(List<RawToIr.FlatInsn> flats, boolean[] dead,
                                      boolean isReturnBlock) {
        boolean changed = false;
        // 3a: 被后续 def 覆盖且中间无 use → 删前一个
        for (int i = 0; i < flats.size(); i++) {
            if (dead[i]) continue;
            RawToIr.FlatInsn f = flats.get(i);
            if (!isAssignKind(f.kind) || f.dst == null
                    || RawToIr.isMemDst(f.dst)) continue;
            String v = f.dst;
            boolean hasUse = false, hasDef = false;
            for (int j = i + 1; j < flats.size(); j++) {
                if (dead[j]) continue;
                RawToIr.FlatInsn g = flats.get(j);
                if (g.uses.contains(v)) { hasUse = true; break; }
                if (g.dst != null && g.dst.equals(v)) { hasDef = true; break; }
            }
            if (hasDef && !hasUse && !isCallLike(f.src)) { dead[i] = true; changed = true; }
        }
        // 3b: return 块反向 liveness — 无后继, 块尾死变量赋值可删
        if (isReturnBlock) {
            Set<String> live = new HashSet<>();
            for (int i = flats.size() - 1; i >= 0; i--) {
                if (dead[i]) continue;
                RawToIr.FlatInsn f = flats.get(i);
                if (f.kind == RawToIr.Kind.RETURN) {
                    live.addAll(f.uses);
                    continue;
                }
                if (isAssignKind(f.kind) && f.dst != null
                        && !RawToIr.isMemDst(f.dst)) {
                    String v = f.dst;
                    if (!live.contains(v)) {
                        // v4.0: 含调用的赋值有副作用, 不可删
                        if (isCallLike(f.src)) {
                            live.addAll(f.uses);
                            continue;
                        }
                        // 特殊: 返回寄存器/帧指针不清 (保守)
                        if (!v.equals("x0") && !v.equals("r0")
                                && !v.equals("sp") && !v.equals("lr")
                                && !v.equals("pc") && !v.equals("fp")) {
                            dead[i] = true;
                            changed = true;
                            continue;
                        }
                    } else {
                        live.remove(v);
                    }
                }
                live.addAll(f.uses);
            }
        }
        return changed;
    }

    /** v4.0: src 是否含调用 (有副作用, 死赋值消除必须保留). */
    private static boolean isCallLike(IrNode src) {
        if (src instanceof IrNode.CallExpr || src instanceof IrNode.CallStmt) {
            return true;
        }
        if (src instanceof IrNode.Raw) {
            String t = ((IrNode.Raw) src).text;
            return t.contains("(") && !t.startsWith("*(");
        }
        return false;
    }

    private static boolean isAssignKind(RawToIr.Kind k) {
        return k == RawToIr.Kind.ASSIGN_VAR || k == RawToIr.Kind.ASSIGN_NUM
                || k == RawToIr.Kind.ASSIGN_BINOP || k == RawToIr.Kind.ASSIGN_MEM
                || k == RawToIr.Kind.ASSIGN_OTHER;
    }

    // ── Pass 4: 内存自写消除 (r = *(T*)(E); ...; *(T*)(E) = r;) ──

    private static boolean selfWriteElim(List<RawToIr.FlatInsn> flats, boolean[] dead) {
        boolean changed = false;
        for (int i = 0; i < flats.size(); i++) {
            if (dead[i]) continue;
            RawToIr.FlatInsn f = flats.get(i);
            if (f.kind != RawToIr.Kind.ASSIGN_MEM) continue;
            if (!(f.src instanceof IrNode.Var)) continue;
            String r = ((IrNode.Var) f.src).name;
            String memText = f.dst;
            // 向前找 r 的最近 def
            int j = i - 1;
            for (; j >= 0; j--) {
                if (dead[j]) continue;
                RawToIr.FlatInsn g = flats.get(j);
                if (g.dst != null && g.dst.equals(r)) break;
            }
            if (j < 0) continue;
            RawToIr.FlatInsn srcDef = flats.get(j);
            if (!isAssignKind(srcDef.kind)) continue;
            String srcText = srcDef.src != null ? srcDef.src.toC() : null;
            if (srcText == null || !srcText.equals(memText)) continue;
            // (j, i) 之间无对同一地址的写
            boolean written = false;
            for (int k = j + 1; k < i; k++) {
                if (dead[k]) continue;
                RawToIr.FlatInsn g = flats.get(k);
                if (g.kind == RawToIr.Kind.ASSIGN_MEM && g.dst.equals(memText)) {
                    written = true;
                    break;
                }
            }
            if (!written) { dead[i] = true; changed = true; }
        }
        return changed;
    }

    // ── Pass 5: 自赋值清理 (x0 = x0 → 空) ──

    private static boolean selfAssignElim(List<RawToIr.FlatInsn> flats, boolean[] dead) {
        boolean changed = false;
        for (int i = 0; i < flats.size(); i++) {
            if (dead[i]) continue;
            RawToIr.FlatInsn f = flats.get(i);
            if (f.kind == RawToIr.Kind.ASSIGN_VAR
                    && f.dst != null && f.src instanceof IrNode.Var
                    && ((IrNode.Var) f.src).name.equals(f.dst)) {
                dead[i] = true;
                changed = true;
            }
        }
        return changed;
    }

    private IrOptimizer() {}
}
