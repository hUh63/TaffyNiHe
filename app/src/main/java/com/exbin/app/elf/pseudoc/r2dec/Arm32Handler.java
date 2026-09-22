package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ARM32 (AArch32 / Thumb) instruction handler — Stage 3 of the r2dec pipeline.
 * <p>Translates ARM32 machine instructions into IR nodes and sets
 * control-flow metadata on {@link IrInsn} objects.
 * <p>ARM32 condition codes appear as mnemonic suffixes (e.g. {@code beq},
 * {@code bne}, {@code blne}). The handler distinguishes branch ({@code b}),
 * branch-with-link ({@code bl}), and branch-exchange ({@code bx}) by
 * examining the mnemonic prefix and stripping the condition suffix.
 */
public class Arm32Handler implements InstructionHandler {

    /** Valid ARM condition-code suffixes. */
    private static final Set<String> COND_CODES = new HashSet<>(Arrays.asList(
            "eq", "ne", "cs", "hs", "cc", "lo", "mi", "pl", "vs", "vc",
            "hi", "ls", "ge", "lt", "gt", "le", "al"));

    /** Last CMP operands (for feeding conditional-branch conditions). */
    private IrNode cmpA;
    private IrNode cmpB;

    /** v4.8: 哨兵 — cmp 操作数含特殊寄存器 (sp/pc/fp 等) 时条件不可解析,
     *  后续条件分支降级为无条件跳转 (保留跳转语义, 不泄漏底层寄存器). */
    private static final IrNode UNRESOLVED_COND = IrNode.var("__unresolved_cond__");

    private static boolean isUnresolvedCond(IrNode n) {
        return n instanceof IrNode.Var
                && "__unresolved_cond__".equals(((IrNode.Var) n).name);
    }

    // ── IT-block state (Thumb) ──
    /** Remaining instructions in the current IT block (0 = not in an IT block). */
    private int itRemaining = 0;
    /** Mask chars after "it" (e.g. "te" for "itte"); "" for a plain "it". */
    private String itMask = "";
    /** Index of the next instruction to consume in the IT block (0-based). */
    private int itPos = 0;
    /** C condition string for a "then" (T) slot. */
    private String itThenCond = null;
    /** C condition string for an "else" (E) slot (inverted). */
    private String itElseCond = null;
    /** Set when the instruction just processed was the IT instruction itself. */
    private boolean itJustStarted = false;

    @Override
    public String archName() {
        return "arm";
    }

    @Override
    public List<IrNode> handle(IrInsn insn, DecompContext ctx, List<IrInsn> all) {
        List<IrNode> result = new ArrayList<>();
        String mn = insn.mnemonic;
        String op = insn.opStr != null ? insn.opStr.trim() : "";
        String[] ops = op.isEmpty() ? new String[0] : splitOps(op);

        try {
            handleCore(insn, mn, op, ops, ctx, result, all);
        } catch (Throwable t) {
            result.clear();
            result.add(new IrNode.Raw("/* handler error: " + t.getClass().getSimpleName()
                    + " " + mn + " " + op + " */"));
        }
        // v3.5: 调用边界 — 清易失寄存器栈别名 (被调函数可能改写 r0-r3)
        if (ctx != null && insn.isCall) {
            ctx.clearVolatileStackAliases(false);
        }
        // Wrap instructions that fall inside an IT block with if (cond) { ... }
        applyITWrap(insn, result);
        return result;
    }

    private void handleCore(IrInsn insn, String mn, String op,
                            String[] ops, DecompContext ctx, List<IrNode> result,
                            List<IrInsn> all) {

        // ── v3.5: 栈指针别名失效 — 写寄存器指令使旧别名过期 ──
        // 排除存储 (ops[0] 是源) 与 add/sub/mov 系列 (分支内自管理链式)
        if (ctx != null && ops.length > 0 && ops[0].matches("r\\d+")
                && !mn.startsWith("str") && !mn.startsWith("stm") && !mn.startsWith("push")
                && !mn.startsWith("add") && !mn.startsWith("sub")
                && !mn.startsWith("adc") && !mn.startsWith("sbc")
                && !mn.equals("mov")) {
            ctx.clearStackAlias(ops[0]);
        }

        // ── IT block (Thumb): it{mask} cond ──
        // E.g. "it eq" (1 then), "itt ne" (2 then), "itte ge" (then,then,else).
        // The IT instruction itself produces nothing; the following N instructions
        // are conditionally executed and get wrapped by applyITWrap().
        if (isItMnemonic(mn)) {
            String mask = mn.substring(2); // "" for "it", "t"/"e"/... for "itt"/"ite"/...
            String condCode = ops.length > 0 ? stripImm(ops[0]).trim() : "eq";
            if (!COND_CODES.contains(condCode)) {
                condCode = "eq"; // defensive default
            }
            String condType = mapCond(condCode);
            IrNode a = cmpA != null ? cmpA : IrNode.num(0);
            IrNode b = cmpB != null ? cmpB : IrNode.num(0);
            itThenCond = IrNode.makeCondition(a, b, condType, false).toC();
            itElseCond = IrNode.makeCondition(a, b, condType, true).toC();
            itMask = mask;
            itPos = 0;
            itRemaining = 1 + mask.length();
            itJustStarted = true;
            insn.valid = false; // the IT instruction emits nothing
            return;
        }

        // ── Returns: bx lr, pop {pc}, ldm pc ──
        // v3.4.1: 返回值恢复 — 回溯 r0 的最近赋值, 生成 "return <expr>".
        //   例: ldr r0, [sp,#8]; bx lr  → return var_8;
        //   例: mov r0, #5;     bx lr  → return 5;
        //   若 r0 无活跃赋值 (void 函数 / 值不可追踪), 保持裸 return;
        //   同时设置 ctx.retReg = "r0" → 函数签名返回类型不再是恒 void.
        // v3.8: 条件码剥离后判断 base, 支持 bxeq/popeq 等条件返回
        //   (条件返回输出为 if (cond) return ...;)。
        if (mn.startsWith("ldm") && op.contains("pc")) {
            insn.isReturn = true;
            String retExpr = findReturnValue(insn, all, ctx);
            if (retExpr != null) {
                if (ctx.retReg == null) ctx.retReg = "r0";
                result.add(new IrNode.Return(new IrNode.Raw(retExpr)));
            } else {
                result.add(IrNode.retNull());
            }
            return;
        }

        // ── Decompose mnemonic into base + condition ──
        String base = mn;
        String cond = null;
        // Try to strip a 2-char condition suffix
        if (mn.length() > 2) {
            String suffix = mn.substring(mn.length() - 2);
            if (COND_CODES.contains(suffix)) {
                // Make sure the remaining part is a valid mnemonic prefix
                String prefix = mn.substring(0, mn.length() - 2);
                if (isValidMnPrefix(prefix)) {
                    base = prefix;
                    cond = suffix;
                }
            }
        }
        // v4.9: Thumb-2 显式宽度后缀 (movs.w / mov.w) — Capstone 罕见输出,
        // 先剥 .w/.n 再继续 (仅当剩余部分是合法前缀).
        if (base.indexOf('.') > 0) {
            String stem = base.substring(0, base.indexOf('.'));
            String width = base.substring(base.indexOf('.') + 1);
            if ((width.equals("w") || width.equals("n")) && isValidMnPrefix(stem)) {
                base = stem;
            }
        }
        // v4.9: ARM flag-update 后缀 's' — Capstone 对 movs/adds/subs 等原样
        // 输出 (如 "movs r0, #0" 是 mov+flags, 不是伪 "mo"+"vs" 条件码; 末尾
        // "vs" 撞 COND_CODES 导致上面的条件剥离被拒). 仅当去 s 后是合法数据
        // 指令前缀才剥离, 避免误伤 VFP 单精度 vadds/vmovs 等.
        if (base.endsWith("s") && base.length() > 2) {
            String noS = base.substring(0, base.length() - 1);
            if (isValidMnPrefix(noS)) {
                base = noS;
            }
        }

        // ── Returns: bx lr, pop {pc}, ldm pc (条件码剥离后, 支持 bxeq/popeq) ──
        if ((base.equals("bx") && (op.equals("lr") || op.equals("r14")))
                || (base.equals("pop") && op.contains("pc"))) {
            insn.isReturn = true;
            String retExpr = findReturnValue(insn, all, ctx);
            if (retExpr != null && ctx.retReg == null) ctx.retReg = "r0";
            if (cond != null) {
                String condExpr = IrNode.makeCondition(
                        cmpA != null ? cmpA : IrNode.num(0),
                        cmpB != null ? cmpB : IrNode.num(0),
                        mapCond(cond), false).toC();
                // v4.1: 条件返回是条件分支 — 块图据此保留 fall-through,
                // 结构化器不会把它当无条件 return 截断后续代码.
                insn.isCondBranch = true;
                result.add(new IrNode.Raw("if (" + condExpr + ") return"
                        + (retExpr != null ? " " + retExpr : "") + ";"));
            } else if (retExpr != null) {
                result.add(new IrNode.Return(new IrNode.Raw(retExpr)));
            } else {
                result.add(IrNode.retNull());
            }
            return;
        }

        // ── Unconditional branch ──
        if (base.equals("b") && cond == null) {
            insn.isBranch = true;
            insn.isUncondBranch = true;
            insn.jumpTarget = normalizeTarget(parseAddr(op));
            return;
        }

        // ── Conditional branch (b{cond}) ──
        if (base.equals("b") && cond != null) {
            // v4.8: cmp 含特殊寄存器 → 条件不可解析 → 降级无条件跳转
            // (保留跳转目标, 避免输出 if (x0 == sp) 泄漏)
            if (isUnresolvedCond(cmpA)) {
                insn.isBranch = true;
                insn.isCondBranch = false;
                insn.isUncondBranch = true;
                insn.jumpTarget = normalizeTarget(parseAddr(op));
                return;
            }
            insn.isBranch = true;
            insn.isCondBranch = true;
            insn.jumpTarget = normalizeTarget(parseAddr(op));
            insn.condType = mapCond(cond);
            insn.condA = cmpA != null ? cmpA : IrNode.num(0);
            insn.condB = cmpB != null ? cmpB : IrNode.num(0);
            return;
        }

        // ── v3.2.16: CBZ / CBNZ (Thumb 16-bit conditional branch) ──
        if (mn.equals("cbz") || mn.equals("cbnz")) {
            if (ops.length >= 2) {
                // v4.8: 比较对象为特殊寄存器 → 降级无条件跳转
                if (hasSpecialReg(ops[0].trim())) {
                    insn.isBranch = true;
                    insn.isUncondBranch = true;
                    insn.jumpTarget = normalizeTarget(parseAddr(ops[1].trim()));
                    return;
                }
                insn.isBranch = true;
                insn.isCondBranch = true;
                insn.jumpTarget = normalizeTarget(parseAddr(ops[1].trim()));
                insn.condType = mn.equals("cbz") ? "EQ" : "NE";
                insn.condA = IrNode.var(ops[0].trim());
                insn.condB = IrNode.num(0);
            }
            return;
        }

        // ── v3.2.16: TBB / TBH — jump-table dispatch (handled by SwitchCasePass) ──
        if (mn.equals("tbb") || mn.equals("tbh")) {
            insn.isBranch = true;
            insn.isUncondBranch = true; // indirect — no static target
            return;
        }

        // ── Calls: bl, blx ──
        if (base.equals("bl") || base.equals("blx")) {
            // Check if this is an indirect call: blx rN (register operand, not address)
            if (base.equals("blx") && op.matches("r[0-9]+")) {
                insn.isCall = true;
                String callee = op.trim();
                List<String> args = new ArrayList<>();
                int argCount = inferArgCount(insn, all);
                // v3.5: 回溯 r0-r3 赋值还原参数值 (对齐 Arm64Handler.backtraceArgValues)
                Map<String, String> argVal = backtraceArgValues(insn, all, ctx);
                for (int i = 0; i < argCount && i < 4; i++) {
                    String reg = "r" + i;
                    String v = argVal.get(reg);
                    args.add((v != null && !v.isEmpty()) ? v : reg);
                }
                // Check if we tracked the source of this register as a function pointer
                if (ctx != null && ctx.funcPtrSource.containsKey(callee)) {
                    String ptrExpr = ctx.funcPtrSource.get(callee);
                    // v4.1: 虚表槽位命中 → 类方法名 (ClassName::method)
                    String vtName = ctx.resolveVtableSlot(ptrExpr);
                    if (vtName != null) {
                        if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                            IrInsn next = all.get(insn.idx + 1);
                            if (next.mnemonic.equals("mov") && next.opStr != null) {
                                String[] nextOps = next.opStr.trim().split(",");
                                if (nextOps.length >= 2 && nextOps[1].trim().equals("r0")) {
                                    String dstReg = nextOps[0].trim();
                                    result.add(new IrNode.Assign(dstReg,
                                            new IrNode.CallExpr(vtName, args)));
                                    next.valid = false;
                                    return;
                                }
                            }
                        }
                        result.add(new IrNode.CallStmt(vtName, args));
                        return;
                    }
                    // Check return value capture
                    if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                        IrInsn next = all.get(insn.idx + 1);
                        if (next.mnemonic.equals("mov") && next.opStr != null) {
                            String[] nextOps = next.opStr.trim().split(",");
                            if (nextOps.length >= 2 && nextOps[1].trim().equals("r0")) {
                                String dstReg = nextOps[0].trim();
                                result.add(new IrNode.Assign(dstReg,
                                        new IrNode.Raw(funcPtrCallTyped(ptrExpr, args, ctx))));
                                next.valid = false;
                                return;
                            }
                        }
                    }
                    result.add(new IrNode.Raw(funcPtrCallTyped(ptrExpr, args, ctx)));
                    return;
                }
                // Fallback: indirect call without known source
                result.add(new IrNode.CallStmt(callee, args));
                return;
            }

            insn.isCall = true;
            long target = parseAddr(op);
            insn.callee = resolveCallee(target, op, ctx);
            List<String> args = new ArrayList<>();
            // v4.6: 参数个数 — native 结构化签名优先, 其次 libc 表, 再次寄存器扫描
            int argCount = signatureArgCount(target, ctx);
            if (argCount < 0) argCount = LibcCallDb.lookupArgs(insn.callee);
            if (argCount < 0) argCount = inferArgCount(insn, all);
            // v3.5: 回溯 r0-r3 赋值还原参数值 (对齐 Arm64Handler.backtraceArgValues)
            Map<String, String> argVal = backtraceArgValues(insn, all, ctx);
            for (int i = 0; i < argCount && i < 4; i++) {
                String reg = "r" + i;
                String v = argVal.get(reg);
                args.add((v != null && !v.isEmpty()) ? v : reg);
            }
            // Check if next instruction captures return value (mov rN, r0)
            if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                IrInsn next = all.get(insn.idx + 1);
                if (next.mnemonic.equals("mov") && next.opStr != null) {
                    String[] nextOps = next.opStr.trim().split(",");
                    if (nextOps.length >= 2 && nextOps[1].trim().equals("r0")) {
                        String dstReg = nextOps[0].trim();
                        result.add(new IrNode.Assign(dstReg,
                                new IrNode.CallExpr(insn.callee, args)));
                        next.valid = false;
                        return;
                    }
                }
            }
            // v4.8: tail call — 函数末尾的 bl, 其后无代码入口且无返回指令
            // (尾部对齐/常量池数据) → return callee(args); (0x20ec bl 0x710c 等)
            if (isTailCall(insn, all)) {
                StringBuilder tail = new StringBuilder("return ")
                        .append(insn.callee).append("(");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) tail.append(", ");
                    tail.append(args.get(i));
                }
                tail.append(");");
                result.add(new IrNode.Raw(tail.toString()));
                return;
            }
            result.add(new IrNode.CallStmt(insn.callee, args));
            return;
        }

        // ── BX (indirect, not lr) — possibly function pointer call ──
        if (base.equals("bx")) {
            // Check if this is blx rN (indirect call via function pointer)
            if (!op.equals("lr") && !op.equals("r14")) {
                // blx rN — indirect call through register
                insn.isCall = true;
                String callee = op.trim();
                // Check if we tracked the source of this register as a function pointer
                if (ctx != null && ctx.funcPtrSource.containsKey(callee)) {
                    String ptrExpr = ctx.funcPtrSource.get(callee);
                    List<String> args = new ArrayList<>();
                    int argCount = inferArgCount(insn, all);
                    // v3.5: 回溯 r0-r3 赋值还原参数值
                    Map<String, String> argVal = backtraceArgValues(insn, all, ctx);
                    for (int i = 0; i < argCount && i < 4; i++) {
                        String reg = "r" + i;
                        String v = argVal.get(reg);
                        args.add((v != null && !v.isEmpty()) ? v : reg);
                    }
                    // Check return value capture
                    if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                        IrInsn next = all.get(insn.idx + 1);
                        if (next.mnemonic.equals("mov") && next.opStr != null) {
                            String[] nextOps = next.opStr.trim().split(",");
                            if (nextOps.length >= 2 && nextOps[1].trim().equals("r0")) {
                                String dstReg = nextOps[0].trim();
                                result.add(new IrNode.Assign(dstReg,
                                        new IrNode.Raw(funcPtrCallTyped(ptrExpr, args, ctx))));
                                next.valid = false;
                                return;
                            }
                        }
                    }
                    result.add(new IrNode.Raw(funcPtrCallTyped(ptrExpr, args, ctx)));
                    return;
                }
            }
            insn.isBranch = true;
            insn.isUncondBranch = true;
            // indirect: no concrete target
            return;
        }

        // ── Data instructions ──
        handleData(insn, base, ops, result, ctx, all);
    }

    // ── Data instruction handling ──

    private void handleData(IrInsn insn, String mn, String[] ops, List<IrNode> result,
                            DecompContext ctx, List<IrInsn> all) {
        if (ops.length == 0) return;
        String dst = ops[0].trim();

        if (mn.equals("mov") || mn.equals("mvn")) {
            if (ops.length >= 2) {
                String src = stripImm(ops[1].trim());
                // Suppress prologue: mov r11, sp  or  mov fp, sp
                if ((dst.equals("r11") || dst.equals("fp")) && src.equals("sp")) {
                    return;
                }
                // v3.5: 任何写 sp 忽略 (伪 C 中 sp 不可写)
                if (dst.equals("sp")) {
                    return;
                }
                // v3.5: 栈指针别名 — mov rN, sp / mov rN, r11|fp / mov rN, 别名寄存器
                if (mn.equals("mov") && dst.matches("r\\d+")) {
                    DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(src) : null;
                    if (a != null) {
                        ctx.trackStackAlias(dst, a.base, a.offset);
                        return;
                    }
                    if (src.equals("sp") || src.equals("r11") || src.equals("fp")) {
                        ctx.trackStackAlias(dst, src, 0);
                        return;
                    }
                    ctx.clearStackAlias(dst); // 非栈别名源, 旧别名过期
                }
                if (mn.equals("mvn")) {
                    if (hasSpecialReg(src)) return; // v4.8: sp/pc/fp → 静默
                    result.add(IrNode.not(dst, parseOperand(src)));
                } else {
                    // v4.8: pc 作为值读 (mov rN, pc) 或写 pc (mov pc, lr)
                    // → 静默: 位置无关惯用序列无独立伪 C 语义 (adr/bl 已语义化)
                    if (src.equals("pc") || dst.equals("pc")) {
                        return;
                    }
                    // Track mov for function pointer source clearing
                    if (ctx != null) ctx.funcPtrSource.remove(dst);
                    result.add(IrNode.assign(dst, parseOperand(src)));
                }
            }
            return;
        }

        // ── movw / movt — ARM32 immediate loading ──
        if (mn.equals("movw")) {
            // movw Rd, #imm16 → Rd = imm16 (low 16 bits)
            if (ops.length >= 2) {
                long val = parseAddr(stripImm(ops[1].trim()));
                if (ctx != null) ctx.resolvedAddrs.put(dst, val);
                if (ctx != null) ctx.funcPtrSource.remove(dst);
                result.add(IrNode.assign(dst, IrNode.num(val)));
            }
            return;
        }
        if (mn.equals("movt")) {
            // movt Rd, #imm16 → Rd = (Rd & 0xFFFF) | (imm16 << 16)
            if (ops.length >= 2) {
                long imm = parseAddr(stripImm(ops[1].trim()));
                Long tracked = ctx != null ? ctx.resolvedAddrs.get(dst) : null;
                if (tracked != null) {
                    long newVal = (tracked & 0xFFFF) | (imm << 16);
                    ctx.resolvedAddrs.put(dst, newVal);
                    result.add(IrNode.assign(dst, IrNode.num(newVal)));
                } else {
                    result.add(IrNode.assign(dst,
                            new IrNode.Raw("((" + dst + " & 0xffff) | 0x" + Long.toHexString(imm << 16) + ")")));
                }
            }
            return;
        }

        // v3.2.16: adds/adc/adcs — same arithmetic, flags ignored in pseudo-C
        if (mn.equals("add") || mn.equals("adds") || mn.equals("adc") || mn.equals("adcs")) {
            if (ops.length >= 3) {
                // Suppress epilogue: add sp, sp, #N
                if (dst.equals("sp") && ops[1].trim().equals("sp")) {
                    return;
                }
                // v3.5: 任何写 sp 忽略
                if (dst.equals("sp")) {
                    return;
                }
                // v3.5: 栈指针别名 — add rN, sp|r11|fp|别名, #imm
                String addSrc = ops[1].trim();
                String addImm = ops[2].trim();
                if (dst.matches("r\\d+") && addImm.startsWith("#")) {
                    long imm = parseAddr(stripImm(addImm));
                    DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(addSrc) : null;
                    if (a != null) {
                        ctx.trackStackAlias(dst, a.base, a.offset + imm);
                        return;
                    }
                    if (addSrc.equals("sp") || addSrc.equals("r11") || addSrc.equals("fp")) {
                        ctx.trackStackAlias(dst, addSrc, imm);
                        return;
                    }
                    ctx.clearStackAlias(dst);
                } else {
                    ctx.clearStackAlias(dst);
                }
                // v4.8: pc/sp/fp 等特殊寄存器参与运算 → 无法静态表达, 静默
                if (hasSpecialReg(ops[1]) || hasSpecialReg(ops[2])) {
                    return;
                }
                // v3.5: add/sub rN, sp|r11|fp|别名, rM（寄存器偏移）无法静态折叠 → 注释保留
                String arithSrc = ops[1].trim();
                if ((arithSrc.equals("sp") || arithSrc.equals("r11") || arithSrc.equals("fp")
                        || ctx != null && ctx.resolveStackAlias(arithSrc) != null)
                        && !ops[2].trim().startsWith("#")) {
                    // v4.8: sp/r11/fp 寄存器偏移无法静态折叠 → 静默 (不泄漏裸寄存器)
                    return;
                }
                result.add(IrNode.add(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        // v3.2.16: subs/sbcs/sbc — same subtract, flags/carry ignored in pseudo-C
        if (mn.equals("sub") || mn.equals("subs") || mn.equals("rsb") || mn.equals("rsbs")
                || mn.equals("sbc") || mn.equals("sbcs")) {
            if (ops.length >= 3) {
                // Suppress prologue: sub sp, sp, #N  (stack allocation)
                if (dst.equals("sp") && ops[1].trim().equals("sp")) {
                    return;
                }
                // v3.5: 任何写 sp 忽略 (含 sub sp, r11, #N 帧指针恢复)
                if (dst.equals("sp")) {
                    return;
                }
                // v3.5: 栈指针别名 — sub rN, sp|r11|fp|别名, #imm (偏移取负; rsb 不建别名)
                if ((mn.equals("sub") || mn.equals("subs")) && dst.matches("r\\d+")) {
                    String subSrc = ops[1].trim();
                    String subImm = ops[2].trim();
                    if (subImm.startsWith("#")) {
                        long imm = parseAddr(stripImm(subImm));
                        DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(subSrc) : null;
                        if (a != null) {
                            ctx.trackStackAlias(dst, a.base, a.offset - imm);
                            return;
                        }
                        if (subSrc.equals("sp") || subSrc.equals("r11") || subSrc.equals("fp")) {
                            ctx.trackStackAlias(dst, subSrc, -imm);
                            return;
                        }
                    }
                    ctx.clearStackAlias(dst);
                } else {
                    ctx.clearStackAlias(dst);
                }
                if (mn.equals("rsb") || mn.equals("rsbs")) {
                    if (hasSpecialReg(ops[1]) || hasSpecialReg(ops[2])) {
                        return; // v4.8: 特殊寄存器参与运算 → 静默
                    }
                    // Rd = operand2 - Rn  (reverse subtract, 对齐 r2dec rsbs)
                    result.add(new IrNode.Assign(dst,
                            new IrNode.BinOp("-", parseOperand(stripImm(ops[2].trim())),
                                    IrNode.var(ops[1].trim()))));
                } else {
                // v3.5: add/sub rN, sp|r11|fp|别名, rM（寄存器偏移）无法静态折叠 → 注释保留
                String arithSrc = ops[1].trim();
                if ((arithSrc.equals("sp") || arithSrc.equals("r11") || arithSrc.equals("fp")
                        || ctx != null && ctx.resolveStackAlias(arithSrc) != null)
                        && !ops[2].trim().startsWith("#")) {
                    // v4.8: sp/r11/fp 寄存器偏移无法静态折叠 → 静默 (不泄漏裸寄存器)
                    return;
                }
                // v4.8: pc/lr 等特殊寄存器参与运算 → 静默
                if (hasSpecialReg(ops[1]) || hasSpecialReg(ops[2])) {
                    return;
                }
                    result.add(IrNode.sub(dst, IrNode.var(ops[1].trim()),
                            parseOperand(stripImm(ops[2].trim()))));
                }
            }
            return;
        }

        // v3.2.16: muls — same multiply
        if (mn.equals("mul") || mn.equals("mla") || mn.equals("muls")) {
            if (ops.length >= 3) {
                result.add(IrNode.mul(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        // v3.2.16: umull/smull — 64-bit multiply, low half in RdLo
        if (mn.equals("umull") || mn.equals("smull")) {
            if (ops.length >= 4) {
                String lo = ops[0].trim();
                String hi = ops[1].trim();
                String cast = mn.equals("umull") ? "(uint64_t)" : "(int64_t)";
                result.add(new IrNode.Assign(lo, new IrNode.Raw(cast + "("
                        + ops[2].trim() + ") * " + ops[3].trim()
                        + " /* high 32: " + hi + " */")));
            }
            return;
        }

        // v3.2.16: ands — same and; bic/orn = and/or with inverted second operand
        if (mn.equals("and") || mn.equals("ands")) {
            if (ops.length >= 3) {
                result.add(IrNode.and(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }
        if (mn.equals("bic") || mn.equals("bics")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.BinOp("&",
                        IrNode.var(ops[1].trim()),
                        new IrNode.UnaryOp("~", parseOperand(stripImm(ops[2].trim()))))));
            }
            return;
        }

        // v3.2.16: orrs — same or; orn = or with inverted second operand
        if (mn.equals("orr") || mn.equals("orrs")) {
            if (ops.length >= 3) {
                result.add(IrNode.or(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }
        if (mn.equals("orn")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.BinOp("|",
                        IrNode.var(ops[1].trim()),
                        new IrNode.UnaryOp("~", parseOperand(stripImm(ops[2].trim()))))));
            }
            return;
        }

        // v3.2.16: eors — same xor; eon = 异或非 (对齐 r2dec eon)
        if (mn.equals("eor") || mn.equals("eors")) {
            if (ops.length >= 3) {
                result.add(IrNode.xor(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }
        if (mn.equals("eon")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("~("
                        + IrNode.var(ops[1].trim()).toC() + " ^ "
                        + parseOperand(stripImm(ops[2].trim())).toC() + ")")));
            }
            return;
        }

        // v3.2.16: lsls/lsrs/asrs — same shifts (asl = lsl 别名, 对齐 r2dec)
        if (mn.equals("lsl") || mn.equals("lsls") || mn.equals("asl") || mn.equals("asls")) {
            if (ops.length >= 3) {
                result.add(IrNode.shl(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        if (mn.equals("lsr") || mn.equals("lsrs") || mn.equals("asr") || mn.equals("asrs")) {
            if (ops.length >= 3) {
                result.add(IrNode.shr(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        // v3.5: ror / rol — 循环移位 (对齐 r2dec rotate_right/rotate_left)
        if (mn.equals("ror") || mn.equals("rol")) {
            if (ops.length >= 3) {
                String src = IrNode.var(ops[1].trim()).toC();
                String n = parseOperand(stripImm(ops[2].trim())).toC();
                String expr = mn.equals("ror")
                        ? "((" + src + " >> (" + n + " & 31)) | (" + src
                        + " << ((32 - (" + n + ")) & 31)))"
                        : "((" + src + " << (" + n + " & 31)) | (" + src
                        + " >> ((32 - (" + n + ")) & 31)))";
                result.add(new IrNode.Assign(dst, new IrNode.Raw(expr)));
            }
            return;
        }

        // v3.5: ubfx / bfc / bfi — 位域提取/清零/插入 (对齐 r2dec arm.js)
        if (mn.equals("ubfx")) {
            if (ops.length >= 4) {
                String lsb = parseOperand(stripImm(ops[2].trim())).toC();
                String width = parseOperand(stripImm(ops[3].trim())).toC();
                result.add(new IrNode.Assign(dst, new IrNode.Raw("(("
                        + IrNode.var(ops[1].trim()).toC() + " >> " + lsb
                        + ") & ((1 << " + width + ") - 1))")));
            }
            return;
        }
        if (mn.equals("bfc") || mn.equals("bfi")) {
            if (ops.length >= 4) {
                long lsb = parseAddr(stripImm(ops[2].trim()));
                long width = parseAddr(stripImm(ops[3].trim()));
                String mask = "0x" + Long.toHexString(
                        width >= 32 ? -1L : ((1L << width) - 1) << lsb);
                if (mn.equals("bfc")) {
                    // bfc Rd, #lsb, #width → Rd &= ~mask
                    result.add(new IrNode.Assign(dst, new IrNode.Raw("(" + dst
                            + " & ~" + mask + ")")));
                } else {
                    // bfi Rd, Rn, #lsb, #width → Rd = (Rd & ~mask) | ((Rn << lsb) & mask)
                    result.add(new IrNode.Assign(dst, new IrNode.Raw("((" + dst
                            + " & ~" + mask + ") | ((" + ops[1].trim()
                            + " << " + lsb + ") & " + mask + "))")));
                }
            }
            return;
        }

        // v3.2.16: udiv / sdiv — divide
        if (mn.equals("udiv") || mn.equals("sdiv")) {
            if (ops.length >= 3) {
                result.add(IrNode.div(dst, IrNode.var(ops[1].trim()),
                        parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        // v3.2.16: neg — Rd = -Rm
        if (mn.equals("neg") || mn.equals("negs")) {
            if (ops.length >= 2) {
                result.add(IrNode.neg(dst, parseOperand(stripImm(ops[1].trim()))));
            }
            return;
        }

        // v3.2.16: rev / rev16 / rev32 / rbit — byte/bit reversal
        if (mn.equals("rev") || mn.equals("rev16") || mn.equals("rev32")
                || mn.equals("rbit")) {
            if (ops.length >= 2) {
                String fn = mn.equals("rbit")
                        ? "__builtin_bitreverse32" : "__builtin_bswap32";
                result.add(new IrNode.Assign(dst, new IrNode.Raw(fn + "("
                        + parseOperand(stripImm(ops[1].trim())).toC() + ")")));
            }
            return;
        }

        // v3.2.16: uxtb/uxth/sxtb/sxth — zero/sign extension
        if (mn.equals("uxtb") || mn.equals("uxth") || mn.equals("sxtb") || mn.equals("sxth")) {
            if (ops.length >= 2) {
                String src = parseOperand(stripImm(ops[1].trim())).toC();
                if (mn.endsWith("tb")) {
                    result.add(mn.startsWith("s")
                            ? new IrNode.Assign(dst, new IrNode.Raw("(int8_t)(" + src + ")"))
                            : new IrNode.Assign(dst, new IrNode.Raw("(" + src + " & 0xff)")));
                } else {
                    result.add(mn.startsWith("s")
                            ? new IrNode.Assign(dst, new IrNode.Raw("(int16_t)(" + src + ")"))
                            : new IrNode.Assign(dst, new IrNode.Raw("(" + src + " & 0xffff)")));
                }
            }
            return;
        }
        // uxtab/uxtah/sxtab/sxtah — Rd = Rn + ext(Rm)
        if (mn.equals("uxtab") || mn.equals("uxtah") || mn.equals("sxtab") || mn.equals("sxtah")) {
            if (ops.length >= 3) {
                String acc = ops[1].trim();
                String src = parseOperand(stripImm(ops[2].trim())).toC();
                String ext;
                if (mn.endsWith("tab")) {
                    ext = mn.startsWith("s") ? "(int8_t)(" + src + ")" : "(" + src + " & 0xff)";
                } else {
                    ext = mn.startsWith("s") ? "(int16_t)(" + src + ")" : "(" + src + " & 0xffff)";
                }
                result.add(new IrNode.Assign(dst, new IrNode.BinOp("+",
                        IrNode.var(acc), new IrNode.Raw(ext))));
            }
            return;
        }

        // v3.2.16: clz — count leading zeros
        if (mn.equals("clz")) {
            if (ops.length >= 2) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("__builtin_clz("
                        + parseOperand(stripImm(ops[1].trim())).toC() + ")")));
            }
            return;
        }

        if (mn.equals("cmp") || mn.equals("cmn") || mn.equals("ccmp")) {
            if (ops.length >= 2) {
                // v4.8: 操作数含特殊寄存器 → 条件不可解析 (哨兵)
                if (hasSpecialReg(ops[0].trim()) || hasSpecialReg(ops[1].trim())) {
                    cmpA = UNRESOLVED_COND;
                    cmpB = IrNode.num(0);
                } else {
                    cmpA = IrNode.var(ops[0].trim());
                    cmpB = parseOperand(stripImm(ops[1].trim()));
                }
            }
            return;
        }

        // v3.5: adr — 加载 PC 相对地址 (对齐 r2dec adr: dst = addr)
        if (mn.equals("adr")) {
            if (ops.length >= 2) {
                result.add(IrNode.assign(dst, parseOperand(stripImm(ops[1].trim()))));
            }
            return;
        }

        if (mn.equals("tst") || mn.equals("teq")) {
            if (ops.length >= 2) {
                // v4.8: 操作数含特殊寄存器 → 条件不可解析
                if (hasSpecialReg(ops[0].trim()) || hasSpecialReg(ops[1].trim())) {
                    cmpA = UNRESOLVED_COND;
                    cmpB = IrNode.num(0);
                } else {
                    cmpA = new IrNode.BinOp("&", IrNode.var(ops[0].trim()),
                            parseOperand(stripImm(ops[1].trim())));
                    cmpB = IrNode.num(0);
                }
            }
            return;
        }

        // ── Atomic operations: ldrex / strex / clrex ──
        if (mn.equals("ldrex") || mn.equals("ldrexb") || mn.equals("ldrexh")
                || mn.equals("ldrd")) {
            // ldrex Rd, [Rn] → Rd = __atomic_load_n(Rn, __ATOMIC_RELAXED)
            if (ops.length >= 2) {
                String reg = ops[0].trim();
                String memExpr = derefExpr(ops[1].trim());
                if (!DecompContext.isStackBaseExpr(memExpr)) {
                int bits = guessBits(mn);
                String ordering = mn.equals("ldrex") ? "__ATOMIC_RELAXED" : "__ATOMIC_RELAXED";
                result.add(new IrNode.Assign(reg,
                        new IrNode.Raw("__atomic_load_n((uint" + bits + "_t*)(" + memExpr + "), " + ordering + ")")));
                }
            }
            return;
        }
        if (mn.equals("strex") || mn.equals("strexb") || mn.equals("strexh")) {
            // strex Rd, Rm, [Rn] — Rd = 0 on success, 1 on failure.
            // v3.2.16: GCC __atomic_compare_exchange pattern — the expected
            // value is whatever the preceding ldrex loaded, so emit a real
            // CAS primitive instead of the broken __atomic_store_n form.
            if (ops.length >= 3) {
                String statusReg = ops[0].trim();
                String valReg = ops[1].trim();
                String addr = derefExpr(ops[2].trim());
                if (!DecompContext.isStackBaseExpr(addr)) {
                String expected = findLdrexExpected(insn, all, addr);
                int bits = guessBits(mn);
                result.add(new IrNode.Assign(statusReg,
                        new IrNode.Raw("__sync_bool_compare_and_swap((uint" + bits
                                + "_t*)(" + addr + "), " + expected + ", " + valReg
                                + ") ? 0 : 1")));
                }
            }
            return;
        }
        if (mn.equals("clrex")) {
            return; // clear exclusive monitor — produce nothing
        }

        // ── Debug trap: bkpt / brk ──
        if (mn.equals("bkpt") || mn.equals("brk") || mn.equals("udf")) {
            result.add(new IrNode.Raw("__builtin_trap()"));
            return;
        }

        // ── Memory barriers ──
        if (mn.equals("dmb") || mn.equals("dsb") || mn.equals("isb")) {
            // Emit as memory fence comment (or __atomic_thread_fence)
            String fence = mn.equals("dmb") ? "__atomic_thread_fence(__ATOMIC_SEQ_CST)" : null;
            if (fence != null) {
                result.add(new IrNode.Raw(fence));
            }
            return;
        }

        // ── Memory: LDR ──
        if (mn.startsWith("ldr")) {
            if (ops.length >= 2) {
                String memExpr = ops[1].trim();

                // Check for string literal
                if (insn.origInsn != null && insn.origInsn.referencedString != null
                        && !insn.origInsn.referencedString.isEmpty()) {
                    // ldr rN, =str — PC-relative literal-pool load of a string address.
                    // The compiler often pairs it with "add rN, pc, rN" to compute the
                    // absolute address; since we already emit rN = "str" (which decays
                    // to the string's address), suppress that stray add.
                    suppressNextPcAdd(dst, insn, all);
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Str(insn.origInsn.referencedString)));
                    return;
                }

                // Check for native symbol
                if (insn.origInsn != null && insn.origInsn.nativeTargetAddr > 0) {
                    long taddr = insn.origInsn.nativeTargetAddr;
                    String sym = resolveAddr(taddr, ctx);
                    // Same peephole as the string case: ldr rN, =sym + add rN, pc, rN
                    // → rN = &sym (the add is redundant once we have the address).
                    suppressNextPcAdd(dst, insn, all);
                    // v4.1: dataConstants — 无符号名时输出自包含注释 (与下方
                    //   "/* ldr @0x... */" 风格一致). 不用「赋值 + 独立注释行」
                    //   组合: 赋值被 DCE 删除后注释会孤立错位.
                    if (ctx != null && ctx.dataConstants != null
                            && (sym.startsWith("global_") || sym.startsWith("sym_"))) {
                        String[] cv = ctx.dataConstants.get(taddr);
                        if (cv != null && cv.length >= 2 && cv[1] != null && !cv[1].isEmpty()) {
                            result.add(new IrNode.Raw("/* ldr @0x"
                                    + Long.toHexString(taddr)
                                    + " = " + cv[1] + " */"));
                            return;
                        }
                    }
                    result.add(IrNode.assign(dst, new IrNode.AddrOf(sym)));
                    return;
                }

                // v3.5: pc 相对数据加载无符号/字符串信息 → 无法静态表达, 静默
                // v4.2: [pc, reg] 寄存器偏移无法静态折叠 → 保留语义: 用已知
                //       pc 基址 (ARM: addr+8, Thumb: addr+4) 替换为绝对地址,
                //       避免悬空寄存器 (旧版 handler error 来源)。
                if (memExpr.contains("pc")) {
                    boolean thumb = insn.origInsn != null && insn.origInsn.isThumb;
                    long pcBase = insn.addr + (thumb ? 4 : 8);
                    String absMem = memExpr.replace("pc",
                            "0x" + Long.toHexString(pcBase));
                    String absDeref = derefExpr(absMem);
                    if (absDeref.equals("0x" + Long.toHexString(pcBase))) {
                        return; // [pc] 裸指针 — 无意义, 静默
                    }
                    int bits = guessBits(mn);
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Raw("*(" + "uint" + bits
                                    + "_t*)(" + absDeref + ")")));
                    return;
                }

                // Try stack variable resolution
                String stackVar = tryResolveStackVar(memExpr, ctx, insn);
                if (stackVar != null) {
                    result.add(IrNode.assign(dst, IrNode.var(stackVar)));
                } else {
                    // Track function pointer source: ldr rN, [rM, #off].
                    // Store the *address* expression (e.g. "r1 + 0x18"); the call site
                    // wraps it as (*(uint32_t*)(addr)) so the cast chain is valid C:
                    //   ((void(*)(...))(*(uint32_t*)(r1 + 0x18)))(...)
                    String deref = derefExpr(memExpr);
                    if (ctx != null && dst.matches("r[0-9]+") && !deref.startsWith("sp")) {
                        ctx.funcPtrSource.put(dst, deref);
                    }
                    int bits = guessBits(mn);
                    String type = "uint" + bits + "_t";
                    if (DecompContext.isStackBaseExpr(deref)) {
                        return; // v4.8: 栈基址无法解析 → 静默 (不泄漏 sp)
                    }
                    if (hasSpecialReg(deref)) {
                        return; // v4.8: 特殊寄存器参与地址运算 → 静默
                    }
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Raw("*(" + type + "*)(" + deref + ")")));
                }
            }
            return;
        }

        // ── Memory: STR ──
        if (mn.startsWith("str")) {
            if (ops.length >= 2) {
                String srcReg = ops[0].trim();
                String memExpr = ops[1].trim();

                // Try stack variable resolution
                String stackVar = tryResolveStackVar(memExpr, ctx, insn);
                if (stackVar != null) {
                    result.add(IrNode.assign(stackVar, IrNode.var(srcReg)));
                } else {
                    int bits = guessBits(mn);
                    String type = "uint" + bits + "_t";
                    String deref = derefExpr(memExpr);
                    if (!DecompContext.isStackBaseExpr(deref) && !hasSpecialReg(deref)) {
                    result.add(new IrNode.Assign("*(" + type + "*)(" + deref + ")",
                            IrNode.var(srcReg)));
                    }
                }
            }
            return;
        }

        // ── v3.2.16: VLDR / VSTR (FP register load/store) ──
        if (mn.equals("vldr") || mn.equals("vstr")) {
            if (ops.length >= 2) {
                String reg = ops[0].trim();
                String memExpr = derefExpr(ops[1].trim());
                String stackVar = tryResolveStackVar(ops[1].trim(), ctx, insn);
                int bits = reg.startsWith("d") ? 64 : 32;
                if (mn.equals("vldr")) {
                    if (stackVar != null) {
                        result.add(IrNode.assign(reg, IrNode.var(stackVar)));
                    } else if (!DecompContext.isStackBaseExpr(memExpr)) {
                        result.add(new IrNode.Assign(reg,
                                new IrNode.Raw("*(uint" + bits + "_t*)(" + memExpr + ")")));
                    }
                } else {
                    if (stackVar != null) {
                        result.add(IrNode.assign(stackVar, IrNode.var(reg)));
                    } else if (!DecompContext.isStackBaseExpr(memExpr)) {
                        result.add(new IrNode.Assign("*(uint" + bits + "_t*)(" + memExpr + ")",
                                IrNode.var(reg)));
                    }
                }
            }
            return;
        }

        // ── Push / Pop (stack management, not functionally interesting) ──
        // v4.8: 覆盖全部 stm/ldm 变体 (stmia/stmdb/stmib/ldmia/ldmdb/ldmib…),
        // 避免裸 stm 落入默认 fallback 输出 /* stm saved_r4, {…} */ 注释
        if (mn.startsWith("stm") || mn.equals("push") || mn.equals("vpush")) {
            return; // produce nothing
        }
        if (mn.startsWith("ldm") || mn.equals("pop") || mn.equals("vpop")) {
            // pop without pc: restore registers
            return;
        }

        // ── NOP / system ──
        if (mn.equals("nop") || mn.equals("cps")
                || mn.equals("mrs") || mn.equals("msr")) {
            return;
        }

        // ── v3.5: svc — Linux syscall 识别 (对齐 r2dec db/syscalls.js) ──
        if (mn.equals("svc")) {
            String imm = stripImm(insn.opStr.trim());
            try {
                long n = Long.parseLong(imm, 16);
                String name = SyscallNames.nameFor((int) n);
                if (name != null) {
                    result.add(new IrNode.Raw("/* syscall: " + name + " */"));
                }
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        // v3.5: VFP/NEON 指令 — 完整覆盖 Capstone ARM 浮点指令集.
        //   vmov/vcvt/vadd/vsub/vmul/vdiv/vmla/vmls/vneg/vabs/vcmp/vmrs
        //   标量浮点精确翻译; NEON 向量/饱和/多媒体保留 __simd 语义.
        // ═══════════════════════════════════════════════════════════════
        if (mn.startsWith("v")) {
            // 剥离 VFP 类型后缀 (.f32/.f64/.s32/.u32) 与条件后缀 (vaddeq → vadd)
            String base = mn;
            int dot = base.indexOf('.');
            if (dot >= 0) base = base.substring(0, dot);
            if (base.length() > 3 && COND_CODES.contains(base.substring(base.length() - 2))) {
                String prefix = base.substring(0, base.length() - 2);
                if (prefix.startsWith("v")) base = prefix;
            }

            // vmov: 寄存器间移动 / ARM↔FP 传值 / 立即数
            if (base.equals("vmov")) {
                if (ops.length == 2) {
                    String src = ops[1].trim();
                    if (src.startsWith("#")) {
                        result.add(IrNode.assign(dst, IrNode.num(stripImm(src))));
                    } else {
                        result.add(IrNode.assign(dst, IrNode.var(src)));
                    }
                } else {
                    // v4.8: vmov d0, r0, r1 (64 位拼接) → d0 = ((uint64_t)r1 << 32) | r0
                    if (ops.length >= 3 && dst.startsWith("d")) {
                        String lo = ops[1].trim();
                        String hi = ops[2].trim();
                        result.add(new IrNode.Assign(dst,
                                new IrNode.Raw("((uint64_t)(" + hi + ") << 32) | (uint32_t)(" + lo + ")")));
                    }
                    // 其它 vmov 形态 (sN/dN 间移动等) 无法语义化 → 静默
                }
                return;
            }

            // vcvt: 类型转换 vcvt.s32.f32 s15, s15 → s15 = (int32_t)(s15);
            if (base.equals("vcvt") || base.equals("vcvtr")) {
                if (ops.length >= 2) {
                    String src = parseOperand(stripImm(ops[1].trim())).toC();
                    String cast = null;
                    int d1 = mn.indexOf('.');
                    if (d1 >= 0) {
                        int d2 = mn.indexOf('.', d1 + 1);
                        if (d2 >= 0) {
                            cast = vcvtCast(mn.substring(d1 + 1, d2),
                                    mn.substring(d2 + 1));
                        }
                    }
                    if (cast == null) cast = "(int32_t)"; // 保守默认
                    result.add(new IrNode.Assign(dst, new IrNode.Raw(cast + "(" + src + ")")));
                }
                return;
            }

            // vadd/vsub/vmul/vdiv/vmla/vmls: 浮点算术
            if (base.equals("vadd") || base.equals("vsub") || base.equals("vmul")
                    || base.equals("vdiv") || base.equals("vmla") || base.equals("vmls")) {
                if (ops.length >= 3) {
                    String a = parseOperand(stripImm(ops[1].trim())).toC();
                    String b = parseOperand(stripImm(ops[2].trim())).toC();
                    String expr;
                    if (base.equals("vadd")) expr = a + " + " + b;
                    else if (base.equals("vsub")) expr = a + " - " + b;
                    else if (base.equals("vmul")) expr = a + " * " + b;
                    else if (base.equals("vdiv")) expr = a + " / " + b;
                    else if (base.equals("vmla")) expr = dst + " + (" + a + " * " + b + ")";
                    else expr = dst + " - (" + a + " * " + b + ")";
                    result.add(new IrNode.Assign(dst, new IrNode.Raw(expr)));
                }
                return;
            }

            // vneg / vabs
            if (base.equals("vneg") || base.equals("vabs")) {
                if (ops.length >= 2) {
                    String src = parseOperand(stripImm(ops[1].trim())).toC();
                    result.add(new IrNode.Assign(dst, new IrNode.Raw(
                            base.equals("vneg") ? "-(" + src + ")"
                                    : "__builtin_fabs(" + src + ")")));
                }
                return;
            }

            // vcmp / vcmpe: 浮点比较 → cmpA/cmpB (喂条件分支)
            if (base.equals("vcmp") || base.equals("vcmpe")) {
                if (ops.length >= 1) {
                    cmpA = IrNode.var(ops[0].trim());
                    cmpB = (ops.length >= 2) ? parseOperand(stripImm(ops[1].trim()))
                            : IrNode.num(0);
                }
                return;
            }

            // vmrs / vmsr: 状态寄存器存取 → 忽略
            if (base.equals("vmrs") || base.equals("vmsr")) {
                return;
            }

            // 其余 v* (NEON 向量/饱和/多媒体): 保留完整操作数的 __simd 伪函数
            result.add(new IrNode.Raw("__simd(\"" + mn + " " + insn.opStr + "\")"));
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        // v3.3: 通用 fallback — ARM32 条件后缀 (addeq/ne) 剥离后按基指令映射.
        //   覆盖: adc/sbc (带进位), smul/umul, 及所有三操作数算术.
        // ═══════════════════════════════════════════════════════════════
        {
            // 剥离条件后缀: addeq → add, subne → sub, 等 (ARM32 条件执行)
            String base = mn;
            if (base.length() > 3 && IrInsn.ARM32_COND_SUFFIXES.contains(base.substring(base.length() - 2))) {
                base = base.substring(0, base.length() - 2);
            }
            String op2 = null;
            if (base.equals("add") || base.equals("adc")) op2 = "+";
            else if (base.equals("sub") || base.equals("sbc") || base.equals("rsb")) op2 = "-";
            else if (base.equals("and")) op2 = "&";
            else if (base.equals("orr")) op2 = "|";
            else if (base.equals("eor")) op2 = "^";
            else if (base.equals("mul") || base.equals("smul") || base.equals("umul")) op2 = "*";
            if (op2 != null && ops.length >= 3) {
                String a = parseOperand(stripImm(ops[1].trim())).toC();
                String b = parseOperand(stripImm(ops[2].trim())).toC();
                String expr;
                if (base.equals("rsb")) {
                    expr = b + " - " + a;  // rsb: reverse subtract (dst = b - a)
                } else {
                    expr = a + " " + op2 + " " + b;
                }
                // sbc: 带借位 (减 carry)
                if (base.equals("sbc")) expr = expr + " - carry";
                if (base.equals("adc")) expr = expr + " + carry";
                result.add(new IrNode.Assign(ops[0].trim(), new IrNode.Raw(expr)));
                return;
            }
        }

        // ── Fallback ──
        // v4.8: 未知指令静默 — 不生成 /* mnemonic opStr */ 注释 (输出保持干净,
        // 控制流信息由 isBranch/isCondBranch 等标记保留, 不受影响)
    }

    /**
     * v3.4.1: 返回值恢复 (ARM32) — 回溯 r0 的最近赋值, 生成 "return <expr>".
     * <p>对齐 Arm64Handler.findReturnValue 的语义 (r2dec 正版行为):
     * <pre>
     *   mov r0, #5;      bx lr  → "5"
     *   mov r0, r1;      bx lr  → "r1"
     *   ldr r0, [sp,#8]; bx lr  → "var_8"
     *   bl foo;          bx lr  → "foo(...)"  (返回 call 结果, 由调用处理)
     * </pre>
     * 返回 null 表示 r0 无活跃赋值 (void 函数 / 值不可追踪), 保持裸 return.
     */
    private String findReturnValue(IrInsn insn, List<IrInsn> all, DecompContext ctx) {
        int startIdx = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        if (startIdx < 0) return null;
        String candidate = null; // 最近 r0 赋值 (可能条件执行)
        boolean candidateSet = false;
        for (int i = startIdx - 1; i >= 0 && i >= startIdx - 32; i--) {
            IrInsn prev = all.get(i);
            if (prev == null || prev.opStr == null) continue;
            String mn = prev.mnemonic;
            String op = prev.opStr.trim();
            if (mn == null || mn.isEmpty()) continue;
            // v3.5: 条件分支 → 已收集候选赋值在分支路径内 (条件执行) → 保守返回 r0
            if (prev.isCondBranch) return "r0";
            // 控制流边界: 无条件分支 / 其他 return → 路径断开
            if ((prev.isBranch && !prev.isCondBranch) || prev.isRet) break;
            // call → r0 来自 bl 返回值; 但更早的 call 会被后续赋值覆盖
            if (prev.isCall) {
                if (candidateSet) continue;
                if (prev.nodes != null) {
                    for (IrNode n : prev.nodes) {
                        if (n instanceof IrNode.Assign) {
                            IrNode.Assign a = (IrNode.Assign) n;
                            if ("r0".equals(a.dst)) {
                                return a.src != null ? a.src.toC() : null;
                            }
                        }
                    }
                }
                return null; // call 未捕获 → 无法确定
            }
            // 剥离条件后缀: movne → mov
            String base = mn;
            if (mn.length() > 2) {
                String suffix = mn.substring(mn.length() - 2);
                if (COND_CODES.contains(suffix)) {
                    String prefix = mn.substring(0, mn.length() - 2);
                    if (isValidMnPrefix(prefix)) {
                        base = prefix;
                    }
                }
            }
            // 找 r0 的赋值 (只取最近一次, 之后继续前扫查分支)
            if (base.equals("mov") || base.equals("mvn") || base.equals("movw")
                    || base.equals("movt") || base.equals("ldr") || base.equals("ldrb")
                    || base.equals("ldrh") || base.equals("ldrsb") || base.equals("ldrsh")
                    || base.equals("add") || base.equals("sub") || base.equals("rsb")
                    || base.equals("eor") || base.equals("and") || base.equals("orr")
                    || base.equals("lsl") || base.equals("lsr") || base.equals("asr")
                    || base.equals("mul") || base.equals("bic")) {
                if (candidateSet) continue;
                String[] ops = splitOps(op);
                if (ops.length < 2) continue;
                String dst = ops[0].trim();
                boolean isRetReg = dst.equals("r0") || dst.equals("a1");
                if (!isRetReg) continue;
                String src = ops.length > 1 ? ops[1].trim() : "";
                // 栈变量: ldr r0, [sp,#8] → var_8
                if (base.startsWith("ldr")) {
                    String memExpr = src;
                    String sv = tryResolveStackVar(memExpr, ctx, prev);
                    if (sv != null) {
                        candidate = sv;
                        candidateSet = true;
                        continue;
                    }
                    // ldr r0, [r1,#4] → *(uint32_t*)(r1 + 4)
                    int bits = guessBits(mn);
                    candidate = "*(" + "uint" + bits + "_t*)(" + derefExpr(memExpr) + ")";
                    candidateSet = true;
                    continue;
                }
                // 字符串
                if (prev.origInsn != null && prev.origInsn.referencedString != null
                        && !prev.origInsn.referencedString.isEmpty()) {
                    candidate = "\"" + prev.origInsn.referencedString
                            .replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    candidateSet = true;
                    continue;
                }
                // 常量
                if (src.startsWith("#")) {
                    candidate = stripImm(src);
                    candidateSet = true;
                    continue;
                }
                // 寄存器透传
                if (src.matches("r\\d+") || src.equals("a1") || src.equals("a2")) {
                    candidate = src;
                    candidateSet = true;
                    continue;
                }
                return null; // 复杂表达式, 保守放弃
            }
        }
        return candidateSet ? candidate : null;
    }

    // ── Helpers ──

    private boolean isValidMnPrefix(String prefix) {
        // Common ARM32 mnemonic prefixes that can carry a condition suffix
        return prefix.equals("b") || prefix.equals("bl") || prefix.equals("blx")
                || prefix.equals("bx") || prefix.equals("mov") || prefix.equals("mvn")
                || prefix.equals("add") || prefix.equals("sub") || prefix.equals("rsb")
                || prefix.equals("mul") || prefix.equals("mla") || prefix.equals("and")
                || prefix.equals("orr") || prefix.equals("eor") || prefix.equals("bic")
                || prefix.equals("lsl") || prefix.equals("lsr") || prefix.equals("asr")
                || prefix.equals("cmp") || prefix.equals("cmn") || prefix.equals("tst")
                || prefix.equals("teq") || prefix.equals("ldr") || prefix.equals("str")
                || prefix.equals("ldrb") || prefix.equals("strb") || prefix.equals("ldrh")
                || prefix.equals("strh") || prefix.equals("ldrsb") || prefix.equals("ldrsh")
                || prefix.equals("ldrsw") || prefix.equals("push") || prefix.equals("pop")
                || prefix.equals("stmfd") || prefix.equals("ldmfd")
                || prefix.equals("ldrex") || prefix.equals("strex")
                || prefix.equals("ldrexb") || prefix.equals("strexb")
                || prefix.equals("ldrexh") || prefix.equals("strexh")
                || prefix.equals("clrex") || prefix.equals("bkpt")
                || prefix.equals("udf") || prefix.equals("ldrd")
                || prefix.equals("movw") || prefix.equals("movt");
    }

    private IrNode parseOperand(String s) {
        s = s.trim();
        if (s.isEmpty()) return IrNode.num(0);
        try {
            if (s.startsWith("0x")) {
                Long.parseUnsignedLong(s.substring(2), 16);
                return IrNode.num(s);
            }
            Long.parseLong(s);
            return IrNode.num(s);
        } catch (NumberFormatException e) {
            return IrNode.var(s);
        }
    }

    private String stripImm(String s) {
        return s.startsWith("#") ? s.substring(1) : s;
    }

    /**
     * v3.5: vcvt 类型后缀 → C 转换. mn 为 "vcvt.s32.f32" 时 from="s32", to="f32".
     */
    private String vcvtCast(String from, String to) {
        boolean toInt = to.startsWith("s") || to.startsWith("u");
        boolean fromInt = from.startsWith("s") || from.startsWith("u");
        if (!toInt && !fromInt) {
            return to.startsWith("f64") ? "(double)" : "(float)";
        }
        if (toInt) {
            boolean s = to.startsWith("s");
            int bits = (to.equals("s64") || to.equals("u64")) ? 64 : 32;
            return s ? ("(int" + bits + "_t)") : ("(uint" + bits + "_t)");
        }
        return to.startsWith("f64") ? "(double)" : "(float)";
    }

    /**
     * v3.2.16: Find the register loaded by the ldrex that precedes this strex
     * (the CAS "expected" old value). Scans back up to 6 instructions, skipping
     * flag-setting compares; stops at any branch/call/barrier.
     *
     * @param insn the strex instruction
     * @param all  the full instruction list
     * @param addr the dereferenced address expression of the strex (e.g. "r3")
     * @return the ldrex target register name, or "0" if none found
     */
    private String findLdrexExpected(IrInsn insn, List<IrInsn> all, String addr) {
        int start = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        for (int i = start - 1; i >= 0 && i >= start - 6; i--) {
            IrInsn prev = all.get(i);
            if (prev == null || prev.mnemonic == null) continue;
            String pm = prev.mnemonic;
            if (pm.equals("ldrex") || pm.equals("ldrexb") || pm.equals("ldrexh")) {
                String[] ops = prev.opStr != null ? prev.opStr.trim().split(",") : null;
                if (ops != null && ops.length >= 2
                        && derefExpr(ops[1].trim()).equals(addr)) {
                    return ops[0].trim();
                }
                return "0";
            }
            // Stop at anything that may change the expected value or control flow
            if (pm.startsWith("b") || pm.startsWith("bl") || pm.equals("bx")
                    || pm.equals("dmb") || pm.equals("dsb") || pm.equals("isb")
                    || pm.startsWith("ldr") || pm.startsWith("str")) {
                return "0";
            }
        }
        return "0";
    }

    /** v3.5: pc 相对目标绝对地址 (ARM32: pc = addr+8; Thumb: addr+4). */
    private long pcRelAddr(IrInsn insn, long imm) {
        boolean thumb = insn.origInsn != null && insn.origInsn.isThumb;
        return insn.addr + (thumb ? 4 : 8) + imm;
    }

    /** v3.5: 从 "[pc, #0x14]" / "#0x10" 提取相对偏移. */
    private long extractPcOffset(String s) {
        s = s.replace("[", "").replace("]", "").replace("!", "").trim();
        String[] parts = s.split(",");
        String off = parts[parts.length - 1].trim();
        if (off.equals("pc") || off.isEmpty()) return 0;
        off = off.replace("#", "").trim();
        try {
            if (off.startsWith("-0x")) return -Long.parseUnsignedLong(off.substring(3), 16);
            if (off.startsWith("0x")) return Long.parseUnsignedLong(off.substring(2), 16);
            return Long.parseLong(off);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String derefExpr(String memExpr) {
        // [r0] → r0
        // [r0, #8] → r0 + 8
        // [r0, #8]! → r0 + 8
        // [r0], #8 → r0 (post-index, ignore)
        String s = memExpr.replace("[", "").replace("]", "").replace("!", "");
        // Handle post-index: [r0], #8
        int bracket = s.indexOf(']');
        if (bracket >= 0) s = s.substring(0, bracket);
        String[] parts = s.split(",");
        if (parts.length == 1) return parts[0].trim();
        if (parts.length >= 2) {
            String base = parts[0].trim();
            String off = stripImm(parts[1].trim());
            if (off.equals("0")) return base;
            return base + " + " + off;
        }
        return s.trim();
    }

    private int guessBits(String mn) {
        if (mn.contains("b")) return 8;
        if (mn.contains("h")) return 16;
        return 32;
    }

    private String mapCond(String suffix) {
        switch (suffix) {
            case "eq": return "EQ";
            case "ne": return "NE";
            case "gt": return "GT";
            case "ge": return "GE";
            case "lt": return "LT";
            case "le": return "LE";
            case "hi": return "GT";
            case "ls": return "LE";
            case "hs":
            case "cs": return "GE";
            case "lo":
            case "cc": return "LT";
            case "mi": return "LT";
            case "pl": return "GE";
            case "vs": return "EQ";
            case "vc": return "NE";
            case "al": return "EQ"; // always — shouldn't be used as cond
            default:   return "EQ";
        }
    }

    private long parseAddr(String s) {
        if (s == null) return 0;
        s = s.trim().replace("#", "");
        try {
            if (s.startsWith("0x")) return Long.parseUnsignedLong(s.substring(2), 16);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * v3.5: Thumb 地址位规范化 — 分支目标对齐指令地址 (低 bit0 是 ARM/Thumb 标志).
     */
    private long normalizeTarget(long target) {
        return target & ~1L;
    }

    /**
     * v4.6: 调用点参数个数 — 函数地址 → native 结构化签名优先.
     * 签名缺失 (未分析/无参列表) 时返回 -1, 由调用方回退 libc 表/寄存器扫描.
     */
    private int signatureArgCount(long target, DecompContext ctx) {
        if (ctx == null || ctx.signatureByAddr == null) return -1;
        com.exbin.app.elf.FunctionInfo fi = ctx.signatureByAddr.get(normalizeTarget(target));
        if (fi == null || fi.restoredParamTypes == null) return -1;
        return fi.restoredParamTypes.size();
    }

    private String resolveCallee(long target, String op, DecompContext ctx) {
        // v3.5: Thumb 地址位规范化
        target = normalizeTarget(target);
        // v3.2.16: 1. exact callee map (labels/imports from the UI), demangled
        if (ctx != null && ctx.calleeMap != null) {
            String name = ctx.calleeMap.get(target);
            if (name != null) {
                return GlobalVarResolver.sanitizeFuncName(
                        GlobalVarResolver.demangle(name, ctx));
            }
        }
        // v3.2.16: 2. unified symbol table → real (demangled) symbol names
        if (ctx != null && target > 0) {
            String sym = GlobalVarResolver.resolveName(target, ctx);
            if (sym != null && GlobalVarResolver.isRealSymbol(sym)) {
                return GlobalVarResolver.sanitizeFuncName(
                        GlobalVarResolver.demangle(sym, ctx));
            }
        }
        // v3.5: 3. demangledNames 缺失时兜底实时 demangle (对齐 R2DecPseudoC.demangleForDisplay)
        if (ctx != null && ctx.demangledNames == null && target > 0) {
            String sym = null;
            if (ctx.labels != null) sym = ctx.labels.get(target);
            if (sym == null && ctx.imports != null) sym = ctx.imports.get(target);
            if (sym == null && ctx.symbolMap != null) sym = ctx.symbolMap.get(target);
            if (sym != null) {
                String dm = demangleSymbol(sym);
                if (dm != null) return dm;
            }
        }
        String s = op.trim().replace("#", "");
        if (s.startsWith("0x")) return "sub_" + s.substring(2);
        if (s.isEmpty()) return "unknown_callee";
        // v4.8: fallback 对 mangled 符号名 (_Z...) 也做 demangle — 符号表未
        // 命中 (calleeMap/resolveName 均无) 时不再把 ZN17... 原样输出.
        if (s.startsWith("_Z")) {
            String dm = demangleSymbol(s);
            if (dm != null) return dm;
        }
        return s;
    }

    /**
     * v4.8: mangled C++ 符号 (_Z...) → demangle + C 标识符化.
     * NativeBridge 不可用/解析失败时返回 null (调用方保持原样).
     */
    private static String demangleSymbol(String sym) {
        if (sym == null || !sym.startsWith("_Z") || sym.length() >= 512) return null;
        try {
            if (com.exbin.app.nativebridge.NativeBridge.isSupported()) {
                String dm = com.exbin.app.nativebridge.NativeBridge.demangle(sym);
                if (dm != null && !dm.equals(sym) && dm.contains("(")) {
                    return GlobalVarResolver.sanitizeFuncName(
                            dm.substring(0, dm.indexOf('(')).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        // v4.9: native 失败 → Java 轻量 Itanium demangler 兜底
        String dm = ItaniumDemangler.demangle(sym);
        if (dm != null) {
            int p = dm.indexOf('(');
            return GlobalVarResolver.sanitizeFuncName(
                    p >= 0 ? dm.substring(0, p).trim() : dm.trim());
        }
        return null;
    }

    /**
     * Build a valid-C function-pointer indirect call expression.
     * <p>The address expression (e.g. {@code r1 + 0x18}) is dereferenced as a
     * {@code uint32_t} first, then the resulting integer is cast to the
     * function-pointer type. This avoids the broken form
     * {@code (func_ptr_type)*(addr)} where {@code *(addr)} dereferences an
     * integer. Produces:
     * <pre>{@code
     * ((void(*)(r0, r1))(*(uint32_t*)(r1 + 0x18)))(r0, r1)
     * }</pre>
     *
     * @param addrExpr the address expression stored in {@code funcPtrSource}
     * @param argStr   comma-joined argument register list (e.g. "r0, r1")
     * @return a syntactically valid C call expression
     */
    /**
     * Build a valid-C function-pointer indirect call expression whose cast
     * uses <b>types</b> (not register names) for the parameter list, e.g.
     * {@code void*, uint32_t, uint32_t}. The argument-expression list still
     * uses register names. Produces:
     * <pre>{@code
     * ((void(*)(void*, uint32_t, uint32_t))(*(uint32_t*)(r1 + 0x18)))(r0, r1, r2)
     * }</pre>
     *
     * @param addrExpr the address expression stored in {@code funcPtrSource}
     * @param args     the argument register names (e.g. ["r0","r1","r2"])
     * @param ctx      decompilation context (for per-register type hints)
     * @return a syntactically valid, type-annotated C call expression
     */
    private String funcPtrCallTyped(String addrExpr, List<String> args, DecompContext ctx) {
        String argStr = String.join(", ", args);
        List<String> types = new ArrayList<>();
        for (String r : args) {
            String t = (ctx != null && ctx.regTypeMap != null)
                    ? ctx.regTypeMap.get(r) : null;
            types.add(t != null ? t : "uint32_t");
        }
        String typeStr = String.join(", ", types);
        return "((void(*)(" + typeStr + "))(*(uint32_t*)(" + addrExpr + ")))(" + argStr + ")";
    }

    /**
     * True if {@code mn} is a Thumb IT-block instruction: {@code it} optionally
     * followed by 1–3 {@code t}/{@code e} mask chars (e.g. {@code it},
     * {@code itt}, {@code ite}, {@code ittte}).
     */
    private boolean isItMnemonic(String mn) {
        if (mn == null || mn.length() < 2 || !mn.startsWith("it")) {
            return false;
        }
        String mask = mn.substring(2);
        if (mask.length() > 3) {
            return false;
        }
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c != 't' && c != 'e') {
                return false;
            }
        }
        return true;
    }

    /**
     * Apply IT-block conditional wrapping to the instruction just handled.
     * <p>The first instruction of the block is always a "then" (cond); each
     * subsequent instruction is "then" or "else" (inverted) per the mask char.
     * Instructions that produced no nodes (e.g. suppressed prologue) still
     * consume an IT slot but are not wrapped.
     */
    private void applyITWrap(IrInsn insn, List<IrNode> result) {
        // The IT instruction itself: just clear the just-started flag.
        if (itJustStarted) {
            itJustStarted = false;
            return;
        }
        if (itRemaining <= 0) {
            return;
        }
        boolean inverted;
        if (itPos == 0) {
            inverted = false; // first slot is always "then"
        } else {
            inverted = (itMask.charAt(itPos - 1) == 'e');
        }
        String condStr = inverted ? itElseCond : itThenCond;
        // Only wrap when there is something to emit.
        if (result != null && !result.isEmpty() && condStr != null) {
            insn.itWrapCond = condStr;
        }
        itPos++;
        itRemaining--;
        if (itRemaining == 0) {
            itMask = "";
            itPos = 0;
            itThenCond = null;
            itElseCond = null;
        }
    }

    /**
     * Peephole helper for the {@code ldr rN, =label} + {@code add rN, pc, rN}
     * pattern (Thumb / ARM PC-relative literal-pool address computation).
     * <p>When the current instruction is a literal-pool load that already
     * resolves to a string or symbol address, the immediately following
     * {@code add rN, pc, rN} (or {@code add rN, rN, pc}) is redundant and
     * would emit a bogus {@code rN = pc + rN}. This method marks that add
     * invalid so it is suppressed during emission.
     *
     * @param dst  the destination register of the ldr (also the add target)
     * @param insn the current ldr instruction
     * @param all  the full instruction list (for look-ahead)
     */
    private void suppressNextPcAdd(String dst, IrInsn insn, List<IrInsn> all) {
        if (insn == null || insn.idx < 0) return;
        int nextIdx = insn.idx + 1;
        if (nextIdx >= all.size()) return;
        IrInsn next = all.get(nextIdx);
        if (next == null || next.opStr == null) return;
        // Strip a condition suffix from the add (addeq, addne, ...).
        String nm = next.mnemonic;
        if (!nm.startsWith("add")) return;
        String[] nops = splitOps(next.opStr.trim());
        if (nops.length < 3) return;
        String nd = nops[0].trim();
        String n1 = nops[1].trim();
        String n2 = nops[2].trim();
        boolean isPcAdd = (n1.equals("pc") && n2.equals(dst))
                || (n2.equals("pc") && n1.equals(dst));
        if (nd.equals(dst) && isPcAdd) {
            next.valid = false;
        }
    }

    /**
     * v4.8: 操作数/表达式是否含底层特殊寄存器 token (sp/pc/fp/bp/lr/
     * r11/r13-r15/sl)。saved_fp 等抽象化变量名 (下划线连接) 不匹配。
     */
    private static final java.util.Set<String> SPECIAL_REGS = new java.util.HashSet<>(
            java.util.Arrays.asList("sp", "pc", "fp", "bp", "sl", "lr",
                    "r11", "r13", "r14", "r15"));

    private static boolean hasSpecialReg(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String tok : s.split("[^a-zA-Z0-9_]+")) {
            if (SPECIAL_REGS.contains(tok)) return true;
        }
        return false;
    }

    /**
     * v4.8: bl 是否为 tail call — 其后指令 (到函数尾) 既无任何跳转目标
     * 指向 (无代码入口), 也不含 bx/ret 返回, 视为尾部对齐/常量池数据.
     * 用指令地址而非 all.size() 判界, 避免函数末尾混入反汇编垃圾时误判.
     */
    private static boolean isTailCall(IrInsn insn, List<IrInsn> all) {
        if (insn == null || insn.idx < 0 || all == null) return false;
        java.util.Set<Long> targets = new java.util.HashSet<>();
        for (IrInsn x : all) {
            if (x == null) continue;
            if (x.jump != null && x.jump > 0) targets.add(x.jump);
            String m = x.mnemonic;
            if (m == null) continue;
            // 条件分支/跳转表/无条件跳转 (不含 bl/blx — 调用目标不构成函数内入口)
            if (m.startsWith("b") || m.startsWith("cbz") || m.startsWith("cbnz")
                    || m.startsWith("tbb") || m.startsWith("tbh")) {
                String o = x.opStr;
                if (o != null) {
                    String t = o.trim().replace("#", "");
                    if (t.startsWith("0x")) {
                        try {
                            targets.add(Long.parseLong(t.substring(2), 16));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        for (int i = insn.idx + 1; i < all.size(); i++) {
            IrInsn nx = all.get(i);
            if (nx == null) continue;
            if (nx.isRet || (nx.mnemonic != null && nx.mnemonic.equals("bx"))) return false;
            if (targets.contains(nx.addr)) return false;
        }
        return true;
    }

    /**
     * v3.5: 回溯 call 前对 r0-r3 的赋值, 还原参数实际值 (对齐 Arm64Handler).
     * <pre>
     *   mov r0, #5
     *   mov r1, r2
     *   bl  func      →  func(5, r2)
     * </pre>
     * 只回溯最近一次对每个寄存器的赋值, 遇分支/调用/返回即停.
     */
    private Map<String, String> backtraceArgValues(IrInsn insn, List<IrInsn> all,
                                                   DecompContext ctx) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        int startIdx = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        for (int i = startIdx - 1; i >= 0 && i >= startIdx - 64; i--) {
            IrInsn prev = all.get(i);
            String mn = prev.mnemonic;
            String op = prev.opStr;
            if (mn == null || op == null) continue;
            // 控制流边界: 分支/调用/返回停止 (分支内的赋值不是本调用的参数)
            if (prev.isBranch || prev.isRet || prev.isCall) break;
            // 剥离条件后缀: movne → mov
            String base = mn;
            if (base.length() > 3 && COND_CODES.contains(base.substring(base.length() - 2))
                    && isValidMnPrefix(base.substring(0, base.length() - 2))) {
                base = base.substring(0, base.length() - 2);
            }
            if (base.equals("mov") || base.equals("movw") || base.equals("mvn")
                    || base.equals("add") || base.equals("sub") || base.equals("rsb")
                    || base.equals("ldr") || base.equals("ldrb") || base.equals("ldrh")
                    || base.equals("orr") || base.equals("eor") || base.equals("and")) {
                String[] ops = splitOps(op);
                if (ops.length < 2) continue;
                String dst = ops[0].trim();
                if (!dst.matches("r[0-3]")) continue;
                if (result.containsKey(dst)) continue; // 已找到最近赋值
                String src = ops[1].trim();
                String val = resolveArgValue(prev, base, src, ops, ctx);
                if (val != null) {
                    result.put(dst, val);
                }
            }
        }
        return result;
    }

    /**
     * v3.5: 把 bl 参数寄存器的赋值来源转成伪 C 表达式 (ARM32 版).
     * <pre>
     *   #5        → "5"
     *   r2        → "r2" (寄存器透传)
     *   referencedString → "\"...\"" (字符串字面量)
     *   nativeTargetAddr → "sym_xxx" (符号地址)
     *   add r0, sp, #0x50 → "sp + 0x50" (栈指针折叠)
     * </pre>
     */
    private String resolveArgValue(IrInsn prev, String base, String src, String[] ops,
                                   DecompContext ctx) {
        if (src == null) return null;
        src = src.trim();
        // 字符串引用: ldr rN, [pc, #N] → "str"
        if (prev.origInsn != null && prev.origInsn.referencedString != null
                && !prev.origInsn.referencedString.isEmpty()) {
            String s = prev.origInsn.referencedString.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            return "\"" + s + "\"";
        }
        // 符号地址: ldr rN, [pc, #N] → sym_xxx
        if (prev.origInsn != null && prev.origInsn.nativeTargetAddr != 0) {
            long t = prev.origInsn.nativeTargetAddr;
            if (ctx != null && ctx.symbolMap != null) {
                String sym = ctx.symbolMap.get(t);
                if (sym != null) return sym;
            }
            return "0x" + Long.toHexString(t);
        }
        // add/sub rN, sp, #imm → "sp + imm" / "sp - imm" (栈参数直接表达式)
        // v3.5: 扩展 — 栈基址|别名 + #imm
        if ((base.equals("add") || base.equals("sub")) && ops.length >= 3) {
            String b = ops[1].trim();
            String immPart = ops[2].trim();
            if (immPart.startsWith("#")) {
                String imm = immPart.substring(1).trim();
                long immVal;
                try {
                    immVal = imm.startsWith("0x")
                            ? Long.parseUnsignedLong(imm.substring(2), 16)
                            : Long.parseLong(imm);
                } catch (NumberFormatException e) {
                    return null;
                }
                long sign = base.equals("sub") ? -1 : 1;
                DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(b) : null;
                if (a != null) {
                    return ctx.stackAddrRef(a.base, a.offset + sign * immVal);
                }
                if (b.equals("sp") || b.equals("r11") || b.equals("fp")) {
                    return ctx.stackAddrRef(b, sign * immVal);
                }
            }
        }
        // 立即数: #5 / 0x10
        if (src.startsWith("#")) {
            String imm = src.substring(1).trim();
            try {
                if (imm.startsWith("0x")) Long.parseUnsignedLong(imm.substring(2), 16);
                else Long.parseLong(imm);
                return imm;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // 寄存器透传: r2 → r2 (v3.5: 别名 → &var_XX)
        if (src.matches("r\\d+")) {
            DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(src) : null;
            if (a != null) return ctx.stackAddrRef(a.base, a.offset);
            return src;
        }
        // v3.5: 栈基址透传: mov r0, sp → &var_0 / mov r0, r11 → &var_0
        if (src.equals("sp") || src.equals("r11") || src.equals("fp")) {
            return ctx != null ? ctx.stackAddrRef(src, 0) : src;
        }
        return null;
    }

    private int inferArgCount(IrInsn insn, List<IrInsn> all) {
        int maxArg = -1;
        int startIdx = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        for (int i = startIdx - 1; i >= 0 && i >= startIdx - 20; i--) {
            IrInsn prev = all.get(i);
            String mn = prev.mnemonic;
            String op = prev.opStr;
            if (op == null) continue;
            if (mn.equals("mov") || mn.equals("add") || mn.equals("sub")
                    || mn.equals("ldr")) {
                String[] ops = op.trim().split(",");
                if (ops.length > 0) {
                    String dst = ops[0].trim();
                    if (dst.matches("r[0-3]")) {
                        int n = Integer.parseInt(dst.substring(1));
                        if (n > maxArg) maxArg = n;
                    }
                }
            }
        }
        return maxArg + 1;
    }

    private String[] splitOps(String op) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < op.length(); i++) {
            char c = op.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
            if (c == ',' && depth == 0) {
                parts.add(cur.toString().trim());
                cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString().trim());
        return parts.toArray(new String[0]);
    }

    /**
     * Try to resolve a memory expression like "[sp, #0x10]" or "[r11, #-4]"
     * to a stack variable name (var_XX / arg_XX).
     * <p>If the slot was not pre-registered by {@link StackFrameAnalyzer}
     * (e.g. the function lacks a {@code mov r11, sp} prologue so the FP was
     * not detected), it is lazily registered here so that {@code [r11, #-0x14]}
     * still renders as {@code var_14} instead of a raw {@code *(uint32_t*)(...)}.
     *
     * <p>Resolution order (avoids fragile regex parsing of opStr):
     * <ol>
     *   <li>{@code insn.origInsn.nativeStackVar} — computed by the native
     *       Capstone detail layer, 100% accurate for all addressing modes
     *       (pre/post-index, register offset, etc.).</li>
     *   <li>{@code insn.origInsn.stackVar} — filled by the Java
     *       DisasmAnnotator when native detail is unavailable.</li>
     *   <li>Fallback: structured split of the memory expression (no regex),
     *       then {@link DecompContext#resolveStackVar} + lazy registration.</li>
     * </ol>
     */
    private String tryResolveStackVar(String memExpr, DecompContext ctx, IrInsn insn) {
        if (ctx == null) return null;
        // 1. Prefer the structured native-computed stack-var field.
        if (insn != null && insn.origInsn != null) {
            String nsv = insn.origInsn.nativeStackVar;
            if (nsv != null && !nsv.isEmpty()) {
                ctx.markUsed(nsv);
                return nsv;
            }
            String sv = insn.origInsn.stackVar;
            if (sv != null && !sv.isEmpty()) {
                ctx.markUsed(sv);
                return sv;
            }
        }
        // 2. Fallback: structured (non-regex) parse of the memory expression.
        String s = memExpr.replace("[", "").replace("]", "").replace("!", "").trim();
        String[] parts = s.split(",");
        if (parts.length < 1) return null;
        String base = parts[0].trim();
        // v3.5: 栈指针别名 — [r0] 且 r0 = sp+0x14 → 归一为 (sp, 0x14+off)
        DecompContext.StackAlias alias = ctx.resolveStackAlias(base);
        long offset = alias != null ? alias.offset : 0;
        if (alias != null) base = alias.base;
        if (!base.equals("sp") && !base.equals("r11") && !base.equals("fp")) {
            return null;
        }
        if (parts.length >= 2) {
            String offStr = stripImm(parts[1].trim());
            // v3.5: 寄存器偏移 ([sp, r2]) 无法静态折叠 → 交 fallback 注释化
            if (!offStr.matches("-?0[xX][0-9a-fA-F]+|-?\\d+")) return null;
            long off = parseAddr(offStr);
            if (offStr.startsWith("-")) {
                off = -parseAddr(offStr.substring(1));
            }
            offset += off;
        }
        // 1. Already registered by the stack-frame analyzer.
        String var = ctx.resolveStackVar(base, offset);
        if (var != null) return var;

        // 2. Lazy registration: FP-relative negative offset → local var_XX.
        if ((base.equals("r11") || base.equals("fp")) && offset < 0) {
            String name = "var_" + Long.toHexString(-offset);
            ctx.registerStackVar(base, offset, name, "uint32_t");
            ctx.markUsed(name);
            return name;
        }
        // 3. Lazy registration: SP-relative offset → local var_XX.
        if (base.equals("sp")) {
            String name = offset >= 0
                    ? "var_" + Long.toHexString(offset)
                    : "var_" + Long.toHexString(-offset);
            ctx.registerStackVar(base, offset, name, "uint32_t");
            ctx.markUsed(name);
            return name;
        }
        return null;
    }

    /**
     * Resolve an address to a symbol name using labels/imports.
     */
    private String resolveAddr(long addr, DecompContext ctx) {
        if (ctx != null) {
            if (ctx.calleeMap != null) {
                String name = ctx.calleeMap.get(addr);
                if (name != null) return name;
            }
            if (ctx.labels != null) {
                String name = ctx.labels.get(addr);
                if (name != null) return name;
            }
            if (ctx.imports != null) {
                String name = ctx.imports.get(addr);
                if (name != null) return name;
            }
        }
        return "sym_" + Long.toHexString(addr);
    }
}
