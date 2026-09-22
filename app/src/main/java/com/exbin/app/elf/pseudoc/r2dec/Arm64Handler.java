package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AArch64 (ARM64) instruction handler — Stage 3 of the r2dec pipeline.
 * <p>Translates ARM64 machine instructions into IR nodes and sets
 * control-flow metadata on {@link IrInsn} objects.
 * <p>Supported instruction classes:
 * <ul>
 *   <li>Branches: b, b.{cond}, cbz, cbnz, tbz, tbnz</li>
 *   <li>Calls: bl, blr</li>
 *   <li>Returns: ret, br x30</li>
 *   <li>Data: mov, add, sub, mul, and, orr, eor, lsl, lsr, cmp, ldr*, str*</li>
 * </ul>
 */
public class Arm64Handler implements InstructionHandler {

    /** Last CMP operands (for feeding conditional-branch conditions). */
    private IrNode cmpA;
    private IrNode cmpB;

    /** v4.8: 哨兵 — cmp 操作数含特殊寄存器 (sp/x29/x30 等) 时条件不可解析,
     *  后续条件分支降级为无条件跳转 (保留跳转语义, 不泄漏底层寄存器). */
    private static final IrNode UNRESOLVED_COND = IrNode.var("__unresolved_cond__");

    private static boolean isUnresolvedCond(IrNode n) {
        return n instanceof IrNode.Var
                && "__unresolved_cond__".equals(((IrNode.Var) n).name);
    }

    /** v4.8: 特殊寄存器 token 检测 (sp/x29/fp/x30/lr/w29/w30/pc). */
    private static boolean hasSpecialReg(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String tok : s.split("[^a-zA-Z0-9_]+")) {
            if (tok.equals("sp") || tok.equals("x29") || tok.equals("fp")
                    || tok.equals("x30") || tok.equals("lr") || tok.equals("w29")
                    || tok.equals("w30") || tok.equals("pc")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String archName() {
        return "aarch64";
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
        return result;
    }

    private void handleCore(IrInsn insn, String mn, String op,
                            String[] ops, DecompContext ctx, List<IrNode> result,
                            List<IrInsn> all) {

        // ── v3.5: 栈指针别名失效 — 写寄存器指令使旧别名过期 ──
        // 排除存储指令 (ops[0] 是源寄存器) 与 add/sub/mov/orr (分支内自管理链式)
        if (ctx != null && ops.length > 0 && ops[0].matches("[wx]\\d+")
                && !mn.startsWith("str") && !mn.startsWith("stp") && !mn.startsWith("stur")
                && !mn.startsWith("push") && !mn.startsWith("stm")
                && !mn.equals("add") && !mn.equals("adds")
                && !mn.equals("sub") && !mn.equals("subs")
                && !mn.equals("mov") && !mn.equals("orr")) {
            ctx.clearStackAlias(ops[0]);
        }

        // ── Returns ──
        if (mn.equals("ret")) {
            insn.isReturn = true;
            // v3.3: 返回值恢复 — 回溯 x0 的最近赋值, 生成 "return <expr>".
            //   例: ldr x0, [sp,#8]; ret → return var_8;
            //   例: mov x0, #5; ret → return 5;
            //   若 x0 未找到活跃赋值 (void 函数), 保持 return;
            String retExpr = findReturnValue(insn, all, ctx);
            if (retExpr != null) {
                // v3.4: 同步设置返回寄存器 → 函数签名返回类型不再恒为 void
                //   (retReg 由 DecompContext.returnType() 消费)
                if (ctx.retReg == null) ctx.retReg = "x0";
                result.add(new IrNode.Return(new IrNode.Raw(retExpr)));
            } else {
                result.add(IrNode.retNull());
            }
            return;
        }

        // ── Unconditional branch ──
        if (mn.equals("b")) {
            insn.isBranch = true;
            insn.isUncondBranch = true;
            insn.jumpTarget = parseAddr(op);
            return;
        }

        // ── Conditional branch (b.{cond}) ──
        if (mn.startsWith("b.")) {
            insn.isBranch = true;
            insn.isCondBranch = true;
            insn.jumpTarget = parseAddr(op);
            // v4.8: cmp 含特殊寄存器 → 条件不可解析 → 降级无条件跳转
            if (isUnresolvedCond(cmpA)) {
                insn.isCondBranch = false;
                insn.isUncondBranch = true;
                return;
            }
            insn.condType = mapCond(mn.substring(2));
            insn.condA = cmpA != null ? cmpA : IrNode.num(0);
            insn.condB = cmpB != null ? cmpB : IrNode.num(0);
            return;
        }

        // ── CBZ / CBNZ ──
        if (mn.equals("cbz") || mn.equals("cbnz")) {
            insn.isBranch = true;
            insn.isCondBranch = true;
            insn.jumpTarget = parseAddr(ops.length > 1 ? ops[1] : "");
            // v4.8: 比较对象为特殊寄存器 → 降级无条件跳转
            if (ops.length > 0 && hasSpecialReg(ops[0].trim())) {
                insn.isCondBranch = false;
                insn.isUncondBranch = true;
                return;
            }
            // v2.9.36: If ConditionBacktrackPass already set the condition,
            // keep it.
            if (insn.condType == null) {
                String reg = ops.length > 0 ? ops[0] : "x0";
                insn.condA = IrNode.var(reg);
                insn.condB = IrNode.num(0);
                insn.condType = mn.equals("cbz") ? "EQ" : "NE";
            }
            return;
        }

        // ── TBZ / TBNZ ──
        if (mn.equals("tbz") || mn.equals("tbnz")) {
            insn.isBranch = true;
            insn.isCondBranch = true;
            insn.jumpTarget = parseAddr(ops.length > 2 ? ops[2] : "");
            // v2.9.36: If ConditionBacktrackPass already set the condition
            // (from a preceding cset that was invalidated), keep it.
            if (insn.condType == null) {
                String reg = ops.length > 0 ? ops[0] : "x0";
                String bitStr = ops.length > 1 ? ops[1].replace("#", "") : "0";
                long bitVal = 1L << parseLongSafe(bitStr, 0);
                insn.condA = new IrNode.BinOp("&", IrNode.var(reg), IrNode.num(bitVal));
                insn.condB = IrNode.num(0);
                insn.condType = mn.equals("tbz") ? "EQ" : "NE";
            }
            return;
        }

        // ── Calls ──
        if (mn.equals("bl") || mn.equals("blr")) {
            insn.isCall = true;
            long target = parseAddr(op);
            // v4.1: blr 虚表槽位调用 — 前驱 ldr xN, [xBase, #off] 命中虚表时用类方法名
            String vtName = null;
            if (mn.equals("blr")) {
                vtName = resolveVtableIndirect(insn, all, ctx);
            }
            insn.callee = (vtName != null) ? vtName : resolveCallee(target, op, ctx);
            List<String> args = new ArrayList<>();
            // v4.6: 参数个数 — native 结构化签名优先, 其次 libc 表, 再次寄存器扫描
            int argCount = signatureArgCount(target, ctx);
            if (argCount < 0) argCount = LibcCallDb.lookupArgs(insn.callee);
            if (argCount < 0) argCount = inferArgCount(insn, all);
            // v3.2.15: 回溯参数赋值来源 (r2dec _populate_arm64_call_args 语义)
            //   把 "mov x0, #5; bl func" → "func(5)" 而非 "func(x0)"
            Map<String, String> argValueMap = backtraceArgValues(insn, all, ctx);
            for (int i = 0; i < argCount && i < 8; i++) {
                String regName = "x" + i;
                String val = argValueMap.get(regName);
                if (val != null && !val.isEmpty()) {
                    args.add(val);
                } else {
                    args.add(regName);
                }
            }
            // Check if next instruction captures return value (mov wN, w0 or xN, x0)
            if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                IrInsn next = all.get(insn.idx + 1);
                if (next.mnemonic.equals("mov") && next.opStr != null) {
                    String[] nextOps = next.opStr.trim().split(",");
                    if (nextOps.length >= 2 && nextOps[1].trim().matches("[wx]0")) {
                        // Return value is captured: next.dst = call result
                        String dstReg = nextOps[0].trim();
                        result.add(new IrNode.Assign(dstReg,
                                new IrNode.CallExpr(insn.callee, args)));
                        next.valid = false; // suppress the mov
                        if (ctx != null) ctx.clearVolatileStackAliases(ctx.isAarch64);
                        return;
                    }
                }
            }
            result.add(new IrNode.CallStmt(insn.callee, args));
            if (ctx != null) ctx.clearVolatileStackAliases(ctx.isAarch64);
            return;
        }

        // ── BR (indirect branch / return via x30) ──
        if (mn.equals("br")) {
            if (op.equals("x30") || op.equals("lr")) {
                insn.isReturn = true;
                result.add(IrNode.retNull());
            } else {
                insn.isBranch = true;
                insn.isUncondBranch = true;
            }
            return;
        }

        // ── Data instructions ──
        handleData(insn, mn, ops, result, ctx, all);
    }

    // ── Data instruction handling ──

    private void handleData(IrInsn insn, String mn, String[] ops, List<IrNode> result,
                            DecompContext ctx, List<IrInsn> all) {
        if (ops.length == 0) return;
        String dst = ops[0].trim();

        if (mn.equals("mov") || mn.equals("movz") || mn.equals("movn")
                || mn.equals("fmov") || mn.equals("orr") && ops.length == 2) {
            // mov x0, x1  or  orr x0, x1  (register move alias)
            if (ops.length >= 2) {
                String src = stripImm(ops[1].trim());
                // Suppress prologue/epilogue: mov x29, sp  or  mov sp, x29
                if ((dst.equals("x29") || dst.equals("fp")) && src.equals("sp")) {
                    return; // FP setup — produce nothing
                }
                // v3.5: 任何写 sp 忽略 (栈恢复/非常规, 伪 C 中 sp 不可写)
                if (dst.equals("sp")) {
                    return;
                }
                // v3.5: 栈指针别名 — mov xN, sp / mov xN, x29 / mov xN, 别名寄存器
                if ((mn.equals("mov") || mn.equals("orr") && ops.length == 2)
                        && dst.matches("[wx]\\d+")) {
                    DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(src) : null;
                    if (a != null) {
                        ctx.trackStackAlias(dst, a.base, a.offset);
                        return;
                    }
                    if (src.equals("sp") || src.equals("x29") || src.equals("fp")) {
                        ctx.trackStackAlias(dst, src, 0);
                        return;
                    }
                    ctx.clearStackAlias(dst); // 非栈别名源, 旧别名过期
                }
                // Track movz with immediate for later movk merging
                if (mn.equals("movz")) {
                    try {
                        long val = src.startsWith("0x")
                                ? Long.parseUnsignedLong(src.substring(2), 16)
                                : Long.parseLong(src);
                        ctx.resolvedAddrs.put(dst, val);
                    } catch (NumberFormatException ignored) {
                        ctx.resolvedAddrs.remove(dst);
                    }
                } else {
                    ctx.resolvedAddrs.remove(dst);
                }
                result.add(IrNode.assign(dst, parseOperand(src)));
            }
            return;
        }

        if (mn.equals("movk")) {
            // movk Rd, #imm, lsl #shift — update specific bits of Rd
            // Try to track and merge with previous movz
            if (ops.length >= 2) {
                String immStr = stripImm(ops[1].trim());
                long imm = parseAddr(immStr);
                int shift = 0;
                if (ops.length >= 3 && ops[2].trim().startsWith("lsl")) {
                    String shiftStr = ops[2].trim().replaceAll(".*#", "").trim();
                    shift = (int) parseAddr(shiftStr);
                }
                long mask = 0xFFFFL << shift;
                // Check if we have a tracked value for this register
                Long tracked = ctx.resolvedAddrs.get(dst);
                if (tracked != null) {
                    long newVal = (tracked & ~mask) | ((imm << shift) & mask);
                    ctx.resolvedAddrs.put(dst, newVal);
                    result.add(IrNode.assign(dst, IrNode.num(newVal)));
                } else {
                    // Can't track — emit a readable expression
                    if (shift > 0) {
                        result.add(IrNode.assign(dst,
                                new IrNode.Raw("((" + dst + " & ~0x" + Long.toHexString(mask) + ") | 0x"
                                        + Long.toHexString(imm << shift) + ")")));
                    } else {
                        result.add(IrNode.assign(dst,
                                new IrNode.Raw("((" + dst + " & ~0xffff) | 0x"
                                        + Long.toHexString(imm) + ")")));
                    }
                }
            }
            return;
        }

        if (mn.equals("add") || mn.equals("adds")) {
            if (ops.length >= 3) {
                // Suppress epilogue: add sp, sp, #N
                if (dst.equals("sp") && ops[1].trim().equals("sp")) {
                    return; // stack restore — produce nothing
                }
                // v2.9.34: ADD may carry a referencedString if it was not
                // merged with a preceding ADRP (e.g. ADR was used instead,
                // or the ADRP+ADD pair was split by the handler).
                if (insn.origInsn != null && insn.origInsn.referencedString != null
                        && !insn.origInsn.referencedString.isEmpty()) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Str(insn.origInsn.referencedString)));
                    return;
                }
                // v3.5: 任何写 sp 忽略 (非 sp,sp 形式)
                if (dst.equals("sp")) {
                    return;
                }
                // v3.5: 栈指针别名 — add xN, sp|别名, #imm → 注释 + 折叠
                String addSrc = ops[1].trim();
                String addImm = ops[2].trim();
                if (dst.matches("[wx]\\d+") && addImm.startsWith("#")) {
                    long imm = parseAddr(stripImm(addImm));
                    DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(addSrc) : null;
                    if (a != null) {
                        ctx.trackStackAlias(dst, a.base, a.offset + imm);
                        return;
                    }
                    if (addSrc.equals("sp") || addSrc.equals("x29") || addSrc.equals("fp")) {
                        ctx.trackStackAlias(dst, addSrc, imm);
                        return;
                    }
                    ctx.clearStackAlias(dst);
                } else {
                    ctx.clearStackAlias(dst);
                }
                // v3.2.15: 内联桶式移位 — "add x0, x1, x2, lsl #3"
                // (splitOps 按逗号切, 移位在 ops[3])
                // v3.5: add/sub xN, sp|x29|别名, xM（寄存器偏移）无法静态折叠 → 注释保留
                String arithSrc = ops[1].trim();
                if ((arithSrc.equals("sp") || arithSrc.equals("x29") || arithSrc.equals("fp")
                        || ctx != null && ctx.resolveStackAlias(arithSrc) != null)
                        && !ops[2].trim().startsWith("#")) {
                    result.add(new IrNode.Raw("/* " + insn.assembly + " */"));
                    return;
                }
                result.add(IrNode.add(dst, IrNode.var(ops[1].trim()),
                        shiftedOperand(ops, 2, 3)));
            }
            return;
        }

        if (mn.equals("sub") || mn.equals("subs")) {
            if (ops.length >= 3) {
                // Suppress prologue: sub sp, sp, #N
                if (dst.equals("sp") && ops[1].trim().equals("sp")) {
                    return; // stack allocation — produce nothing
                }
                // v3.5: 任何写 sp 忽略
                if (dst.equals("sp")) {
                    return;
                }
                // v3.5: 栈指针别名 — sub xN, sp|别名, #imm (偏移取负)
                String subSrc = ops[1].trim();
                String subImm = ops[2].trim();
                if (dst.matches("[wx]\\d+") && subImm.startsWith("#")) {
                    long imm = parseAddr(stripImm(subImm));
                    DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(subSrc) : null;
                    if (a != null) {
                        ctx.trackStackAlias(dst, a.base, a.offset - imm);
                        return;
                    }
                    if (subSrc.equals("sp") || subSrc.equals("x29") || subSrc.equals("fp")) {
                        ctx.trackStackAlias(dst, subSrc, -imm);
                        return;
                    }
                    ctx.clearStackAlias(dst);
                } else {
                    ctx.clearStackAlias(dst);
                }
                // v3.5: add/sub xN, sp|x29|别名, xM（寄存器偏移）无法静态折叠 → 注释保留
                String arithSrc = ops[1].trim();
                if ((arithSrc.equals("sp") || arithSrc.equals("x29") || arithSrc.equals("fp")
                        || ctx != null && ctx.resolveStackAlias(arithSrc) != null)
                        && !ops[2].trim().startsWith("#")) {
                    result.add(new IrNode.Raw("/* " + insn.assembly + " */"));
                    return;
                }
                result.add(IrNode.sub(dst, IrNode.var(ops[1].trim()),
                        shiftedOperand(ops, 2, 3)));
            }
            return;
        }

        if (mn.equals("mul") || mn.equals("madd")) {
            if (ops.length >= 3) {
                result.add(IrNode.mul(dst, IrNode.var(ops[1].trim()),
                        shiftedOperand(ops, 2, 3)));
            }
            return;
        }

        if (mn.equals("udiv") || mn.equals("sdiv")) {
            if (ops.length >= 3) {
                result.add(IrNode.div(dst, IrNode.var(ops[1].trim()), parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        if (mn.equals("msub")) {
            // msub Rd, Rn, Rm, Ra → Rd = Ra - Rn * Rm
            if (ops.length >= 4) {
                IrNode prod = new IrNode.BinOp("*", IrNode.var(ops[1].trim()), parseOperand(stripImm(ops[2].trim())));
                result.add(new IrNode.Assign(dst, new IrNode.BinOp("-", IrNode.var(ops[3].trim()), prod)));
            }
            return;
        }

        if (mn.equals("and")) {
            if (ops.length >= 3) {
                result.add(IrNode.and(dst, IrNode.var(ops[1].trim()),
                        shiftedOperand(ops, 2, 3)));
            }
            return;
        }

        if (mn.equals("orr")) {
            if (ops.length >= 3) {
                result.add(IrNode.or(dst, IrNode.var(ops[1].trim()),
                        shiftedOperand(ops, 2, 3)));
            }
            return;
        }

        if (mn.equals("eor")) {
            if (ops.length >= 3) {
                result.add(IrNode.xor(dst, IrNode.var(ops[1].trim()),
                        shiftedOperand(ops, 2, 3)));
            }
            return;
        }

        // ── v3.5: bic/bics (位清除), eon (异或非), orn (或非) — 对齐 r2dec arm.js ──
        if (mn.equals("bic") || mn.equals("bics")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("("
                        + IrNode.var(ops[1].trim()).toC() + " & ~("
                        + shiftedOperand(ops, 2, 3).toC() + "))")));
            }
            return;
        }
        if (mn.equals("eon")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("~("
                        + IrNode.var(ops[1].trim()).toC() + " ^ "
                        + shiftedOperand(ops, 2, 3).toC() + ")")));
            }
            return;
        }
        if (mn.equals("orn")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("~("
                        + IrNode.var(ops[1].trim()).toC() + " | "
                        + shiftedOperand(ops, 2, 3).toC() + ")")));
            }
            return;
        }

        if (mn.equals("lsl")) {
            if (ops.length >= 3) {
                result.add(IrNode.shl(dst, IrNode.var(ops[1].trim()), parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        if (mn.equals("lsr") || mn.equals("asr")) {
            if (ops.length >= 3) {
                result.add(IrNode.shr(dst, IrNode.var(ops[1].trim()), parseOperand(stripImm(ops[2].trim()))));
            }
            return;
        }

        if (mn.equals("neg")) {
            if (ops.length >= 2) {
                result.add(IrNode.neg(dst, parseOperand(stripImm(ops[1].trim()))));
            }
            return;
        }

        if (mn.equals("mvn")) {
            if (ops.length >= 2) {
                result.add(IrNode.not(dst, parseOperand(stripImm(ops[1].trim()))));
            }
            return;
        }

        // ── v3.5: movi (NEON 立即数) / umulh (128 位乘高 64 位) — 对齐 r2dec arm.js ──
        if (mn.equals("movi")) {
            if (ops.length >= 2) {
                result.add(IrNode.assign(dst, IrNode.num(stripImm(ops[1].trim()))));
            }
            return;
        }
        if (mn.equals("umulh")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw(
                        "(uint64_t)((unsigned __int128)(" + ops[1].trim()
                        + ") * (" + ops[2].trim() + ") >> 64)")));
            }
            return;
        }

        if (mn.equals("cmp") || mn.equals("cmn")) {
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
            return; // no output node
        }

        if (mn.equals("tst")) {
            if (ops.length >= 2) {
                // v4.8: 操作数含特殊寄存器 → 条件不可解析
                if (hasSpecialReg(ops[0].trim()) || hasSpecialReg(ops[1].trim())) {
                    cmpA = UNRESOLVED_COND;
                    cmpB = IrNode.num(0);
                } else {
                    cmpA = new IrNode.BinOp("&", IrNode.var(ops[0].trim()), parseOperand(stripImm(ops[1].trim())));
                    cmpB = IrNode.num(0);
                }
            }
            return;
        }

        // ── Conditional select: csel → ternary ──
        if (mn.equals("csel") || mn.equals("fcsel")) {
            // csel Rd, Rn, Rm, cond → Rd = (cond) ? Rn : Rm
            if (ops.length >= 4) {
                String cond = mapCond(ops[3].trim());
                IrNode condExpr = IrNode.makeCondition(
                        cmpA != null ? cmpA : IrNode.num(0),
                        cmpB != null ? cmpB : IrNode.num(0),
                        cond, false);
                result.add(new IrNode.Assign(dst,
                        new IrNode.Ternary(condExpr, IrNode.var(ops[1].trim()), IrNode.var(ops[2].trim()))));
            }
            return;
        }

        // ── Conditional set: cset → ternary with 1/0 ──
        if (mn.equals("cset") || mn.equals("cinc") || mn.equals("csetm")) {
            // cset  Rd, cond → Rd = (cond) ? 1 : 0
            // cinc  Rd, Rn, cond → Rd = (cond) ? (Rn+1) : Rn
            // csetm Rd, cond → Rd = (cond) ? -1 : 0   (all-ones / all-zeros)
            if (ops.length >= 2) {
                String condSuffix = ops[ops.length - 1].trim();
                String cond = mapCond(condSuffix);
                IrNode condExpr = IrNode.makeCondition(
                        cmpA != null ? cmpA : IrNode.num(0),
                        cmpB != null ? cmpB : IrNode.num(0),
                        cond, false);
                if (mn.equals("cset")) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr, IrNode.num(1), IrNode.num(0))));
                } else if (mn.equals("csetm")) {
                    // -1 as all-ones (unsigned 0xffffffffffffffff)
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr,
                                    new IrNode.Raw("0xffffffffffffffff"),
                                    IrNode.num(0))));
                } else {
                    // cinc
                    String rn = ops[1].trim();
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr,
                                    new IrNode.BinOp("+", IrNode.var(rn), IrNode.num(1)),
                                    IrNode.var(rn))));
                }
            }
            return;
        }

        // ── Conditional select negate: csneg → ternary ──
        if (mn.equals("csneg") || mn.equals("cneg")) {
            // csneg Rd, Rn, Rm, cond → Rd = (cond) ? Rn : -Rm
            // cneg Rd, Rm, cond → Rd = (cond) ? -Rm : Rm
            if (ops.length >= 3) {
                String condSuffix = ops[ops.length - 1].trim();
                String cond = mapCond(condSuffix);
                IrNode condExpr = IrNode.makeCondition(
                        cmpA != null ? cmpA : IrNode.num(0),
                        cmpB != null ? cmpB : IrNode.num(0),
                        cond, false);
                if (mn.equals("csneg") && ops.length >= 4) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr,
                                    IrNode.var(ops[1].trim()),
                                    new IrNode.UnaryOp("-", IrNode.var(ops[2].trim())))));
                } else {
                    // cneg Rd, Rm, cond
                    String rm = ops[1].trim();
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr,
                                    new IrNode.UnaryOp("-", IrNode.var(rm)),
                                    IrNode.var(rm))));
                }
            }
            return;
        }

        // ── Conditional select invert: csinv / cinv → ternary ──
        // csinv Rd, Rn, Rm, cond → Rd = (cond) ? Rn : ~Rm
        // cinv  Rd, Rm, cond     → Rd = (cond) ? ~Rm : Rm
        if (mn.equals("csinv") || mn.equals("cinv")) {
            if (ops.length >= 3) {
                String condSuffix = ops[ops.length - 1].trim();
                String cond = mapCond(condSuffix);
                IrNode condExpr = IrNode.makeCondition(
                        cmpA != null ? cmpA : IrNode.num(0),
                        cmpB != null ? cmpB : IrNode.num(0),
                        cond, false);
                if (mn.equals("csinv") && ops.length >= 4) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr,
                                    IrNode.var(ops[1].trim()),
                                    new IrNode.UnaryOp("~", IrNode.var(ops[2].trim())))));
                } else {
                    // cinv Rd, Rm, cond
                    String rm = ops[1].trim();
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Ternary(condExpr,
                                    new IrNode.UnaryOp("~", IrNode.var(rm)),
                                    IrNode.var(rm))));
                }
            }
            return;
        }

        // ── Conditional increment: csinc ──
        if (mn.equals("csinc")) {
            // csinc Rd, Rn, Rm, cond → Rd = (cond) ? Rn : (Rm+1)
            if (ops.length >= 4) {
                String cond = mapCond(ops[3].trim());
                IrNode condExpr = IrNode.makeCondition(
                        cmpA != null ? cmpA : IrNode.num(0),
                        cmpB != null ? cmpB : IrNode.num(0),
                        cond, false);
                result.add(new IrNode.Assign(dst,
                        new IrNode.Ternary(condExpr,
                                IrNode.var(ops[1].trim()),
                                new IrNode.BinOp("+", IrNode.var(ops[2].trim()), IrNode.num(1)))));
            }
            return;
        }

        // ── Memory: LDR ──
        if (mn.startsWith("ldr") || mn.startsWith("ldur") || mn.startsWith("ldrb")
                || mn.startsWith("ldrh") || mn.startsWith("ldrsw") || mn.startsWith("ldrsb")
                || mn.startsWith("ldrsh")) {
            if (ops.length >= 2) {
                String memExpr = ops[1].trim();

                // Check for string literal: ldr x0, [pc, #offset] with referencedString
                if (insn.origInsn != null && insn.origInsn.referencedString != null
                        && !insn.origInsn.referencedString.isEmpty()) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Str(insn.origInsn.referencedString)));
                    return;
                }

                // Check for native symbol: ldr x0, [pc, #offset] with nativeTargetAddr
                if (insn.origInsn != null && insn.origInsn.nativeTargetAddr > 0) {
                    long taddr = insn.origInsn.nativeTargetAddr;
                    String sym = resolveAddr(taddr, ctx);
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

                // v3.5: 帧链 walk — ldr x29, [x29, #N] 无意义, 忽略
                if ((dst.equals("x29") || dst.equals("fp"))
                        && memExpr.replaceAll("[\\[\\]! ]", "").startsWith("x29")) {
                    return;
                }

                // v3.5: pc 相对数据加载无符号/字符串信息 → 无法静态表达, 静默
                // v4.2: [pc, X] 未改写形式 → 用已知 pc 基址 (AArch64: 当前
                //       指令地址) 替换为绝对地址表达式, 避免悬空寄存器.
                if (memExpr.contains("pc")) {
                    long pcBase = insn.addr;
                    String absMem = memExpr.replace("pc",
                            "0x" + Long.toHexString(pcBase));
                    String absDeref = derefExpr(absMem);
                    if (absDeref.equals("0x" + Long.toHexString(pcBase))) {
                        return; // [pc] 裸指针 — 无意义, 静默
                    }
                    int bits = regBits(dst);
                    if (bits == 64 && (mn.contains("b") || mn.contains("h")
                            || mn.contains("sw") || mn.contains("sb") || mn.contains("sh"))) {
                        bits = guessBits(mn);
                    }
                    String type = typeFor(bits);
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Raw("*(" + type + "*)(" + absDeref + ")")));
                    return;
                }

                // Try to resolve stack variable: [sp+#N] or [x29+#N] → var_N
                int bits = regBits(dst);
                if (bits == 64 && (mn.contains("b") || mn.contains("h")
                        || mn.contains("sw") || mn.contains("sb") || mn.contains("sh"))) {
                    bits = guessBits(mn);
                }
                String type = typeFor(bits);
                String stackVar = tryResolveStackVar(memExpr, ctx, insn, type);
                if (stackVar != null) {
                    result.add(IrNode.assign(dst, IrNode.var(stackVar)));
                } else {
                    String deref = derefExpr(memExpr);
                    if (!DecompContext.isStackBaseExpr(deref)) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Raw("*(" + type + "*)(" + deref + ")")));
                    }
                }
            }
            return;
        }

        // ── Memory: LDP (pair load) ──
        if (mn.startsWith("ldp")) {
            if (ops.length >= 3) {
                String rt1 = ops[0].trim();
                String rt2 = ops[1].trim();
                // Suppress epilogue: ldp x29, x30, [sp], #N
                if ((rt1.equals("x29") || rt1.equals("fp")) && (rt2.equals("x30") || rt2.equals("lr"))) {
                    return; // epilogue — produce nothing
                }
                String memExpr = ops[2].trim();
                String deref = derefExpr(memExpr);
                if (!DecompContext.isStackBaseExpr(deref)) {
                result.add(new IrNode.Assign(rt1,
                        new IrNode.Raw("*(uint64_t*)(" + deref + ")")));
                result.add(new IrNode.Assign(rt2,
                        new IrNode.Raw("*(uint64_t*)(" + deref + " + 8)")));
                }
            }
            return;
        }

        // ── Memory: STR ──
        if (mn.startsWith("str") || mn.startsWith("stur") || mn.startsWith("strb")
                || mn.startsWith("strh")) {
            if (ops.length >= 2) {
                String srcReg = ops[0].trim();
                String memExpr = ops[1].trim();

                // Try to resolve stack variable: [sp+#N] or [x29+#N] → var_N = srcReg
                int bits = regBits(srcReg);
                if (bits == 64 && (mn.contains("b") || mn.contains("h"))) {
                    bits = guessBits(mn);
                }
                String type = typeFor(bits);
                String stackVar = tryResolveStackVar(memExpr, ctx, insn, type);
                if (stackVar != null) {
                    // Use parseOperand to handle xzr/wzr → 0
                    result.add(IrNode.assign(stackVar, parseOperand(srcReg)));
                } else {
                    String deref = derefExpr(memExpr);
                    if (!DecompContext.isStackBaseExpr(deref)) {
                    result.add(new IrNode.Assign("*(" + type + "*)(" + deref + ")",
                            parseOperand(srcReg)));
                    }
                }
            }
            return;
        }

        // ── Memory: STP (pair store) ──
        if (mn.startsWith("stp")) {
            if (ops.length >= 3) {
                String rt1 = ops[0].trim();
                String rt2 = ops[1].trim();
                // Suppress prologue: stp x29, x30, [sp, #-N]!
                if ((rt1.equals("x29") || rt1.equals("fp")) && (rt2.equals("x30") || rt2.equals("lr"))) {
                    return; // prologue — produce nothing
                }
                String memExpr = ops[2].trim();
                // Try stack variable resolution for first register
                String stackVar1 = tryResolveStackVar(memExpr, ctx, insn);
                if (stackVar1 != null) {
                    result.add(IrNode.assign(stackVar1, parseOperand(rt1)));
                    // Second register is at offset+8 — try to resolve
                    long off2 = getStackOffset(memExpr) + 8;
                    String base = getStackBase(memExpr);
                    if (base != null) {
                        String stackVar2 = ctx.resolveStackVar(base, off2);
                        if (stackVar2 != null) {
                            result.add(IrNode.assign(stackVar2, parseOperand(rt2)));
                        } else {
                            // Register the +8 offset as a new var
                            String varName2 = "var_" + Long.toHexString(off2 >= 0 ? off2 : -off2);
                            ctx.registerStackVar(base, off2, varName2, "uint64_t");
                            ctx.markUsed(varName2);
                            result.add(IrNode.assign(varName2, parseOperand(rt2)));
                        }
                    }
                } else {
                    String deref = derefExpr(memExpr);
                    if (!DecompContext.isStackBaseExpr(deref)) {
                    result.add(new IrNode.Assign("*(uint64_t*)(" + deref + ")",
                            parseOperand(rt1)));
                    result.add(new IrNode.Assign("*(uint64_t*)(" + deref + " + 8)",
                            parseOperand(rt2)));
                    }
                }
            }
            return;
        }

        // ── ADRP / ADR — track for adrp+add merging ──
        if (mn.equals("adrp") || mn.equals("adr")) {
            if (ops.length >= 2) {
                String immStr = stripImm(ops[1].trim());
                long pageAddr = parseAddr(immStr);

                // v2.9.34: ADR instruction may carry a referencedString directly
                // (Arm64StringReferenceAnalyzer sets it on the ADR itself).
                if (insn.origInsn != null && insn.origInsn.referencedString != null
                        && !insn.origInsn.referencedString.isEmpty()) {
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Str(insn.origInsn.referencedString)));
                    return;
                }

                // Check if native layer already resolved the target symbol/string
                if (insn.origInsn != null && insn.origInsn.nativeTargetAddr > 0) {
                    // v2.9.35: Use GlobalVarResolver for real symbol names
                    String sym = GlobalVarResolver.resolveName(insn.origInsn.nativeTargetAddr, ctx);
                    if (sym == null) sym = "global_" + Long.toHexString(insn.origInsn.nativeTargetAddr);
                    // Check if next instruction is add dst, dst, #imm → merge
                    if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                        IrInsn next = all.get(insn.idx + 1);
                        if (next.mnemonic.equals("add") && next.opStr != null) {
                            String[] nextOps = next.opStr.trim().split(",");
                            if (nextOps.length >= 3 && nextOps[0].trim().equals(dst) &&
                                nextOps[1].trim().equals(dst)) {
                                // v2.9.34: ADRP+ADD string reference — the
                                // StringReferenceAnalyzer sets referencedString
                                // on the ADD instruction, not the ADRP. Since we
                                // suppress the ADD here, we must check it now.
                                String str = (next.origInsn != null)
                                        ? next.origInsn.referencedString : null;
                                if (str != null && !str.isEmpty()) {
                                    result.add(new IrNode.Assign(dst, new IrNode.Str(str)));
                                } else {
                                    result.add(IrNode.assign(dst, new IrNode.AddrOf(sym)));
                                    // Propagate global var type
                                    String gtype = GlobalVarResolver.resolveType(
                                            insn.origInsn.nativeTargetAddr, ctx);
                                    if (gtype != null) ctx.regTypeMap.put(dst, gtype);
                                }
                                ctx.resolvedAddrs.put(dst, insn.origInsn.nativeTargetAddr);
                                next.valid = false;
                                return;
                            }
                        }
                    }
                    // Just adrp with known symbol
                    result.add(IrNode.assign(dst, new IrNode.AddrOf(sym)));
                    // Propagate global var type
                    String gtype2 = GlobalVarResolver.resolveType(insn.origInsn.nativeTargetAddr, ctx);
                    if (gtype2 != null) ctx.regTypeMap.put(dst, gtype2);
                    ctx.adrpPages.put(dst, pageAddr);
                    return;
                }

                // Track this adrp for potential adrp+add merging
                ctx.adrpPages.put(dst, pageAddr);
                // Check if next instruction is add dst, dst, #imm
                if (insn.idx >= 0 && insn.idx + 1 < all.size()) {
                    IrInsn next = all.get(insn.idx + 1);
                    if (next.mnemonic.equals("add") && next.opStr != null) {
                        String[] nextOps = next.opStr.trim().split(",");
                        if (nextOps.length >= 3 && nextOps[0].trim().equals(dst) &&
                            nextOps[1].trim().equals(dst)) {
                            long addOffset = parseAddr(stripImm(nextOps[2].trim()));
                            long fullAddr = pageAddr + addOffset;
                            // v2.9.34: Check string reference on the ADD instruction
                            String str = (next.origInsn != null)
                                    ? next.origInsn.referencedString : null;
                            if (str != null && !str.isEmpty()) {
                                result.add(new IrNode.Assign(dst, new IrNode.Str(str)));
                                ctx.resolvedAddrs.put(dst, fullAddr);
                                next.valid = false;
                                ctx.adrpPages.remove(dst);
                                return;
                            }
                            // v4.0: findString — 反汇编层未标注 (StringReferenceAnalyzer
                            //   未覆盖), 但 ELF 字符串表命中 → 直接输出字符串字面量
                            // v4.1: insnStringRefs 精确命中优先 (StringReferenceAnalyzer
                            //   反向索引), 未命中才回退 stringMap 全量表
                            if (ctx != null) {
                                String s = null;
                                if (ctx.insnStringRefs != null) {
                                    long refAddr = 0;
                                    if (next.origInsn != null) refAddr = next.origInsn.address;
                                    if (refAddr == 0 && insn.origInsn != null) refAddr = insn.origInsn.address;
                                    if (refAddr != 0) s = ctx.insnStringRefs.get(refAddr);
                                }
                                if (s == null && ctx.stringMap != null) {
                                    s = ctx.stringMap.get(fullAddr);
                                }
                                if (s != null && !s.isEmpty()) {
                                    result.add(new IrNode.Assign(dst, new IrNode.Str(s)));
                                    ctx.resolvedAddrs.put(dst, fullAddr);
                                    next.valid = false;
                                    ctx.adrpPages.remove(dst);
                                    return;
                                }
                            }
                            // v2.9.35: Use GlobalVarResolver for real symbol names
                            String symName = GlobalVarResolver.resolveName(fullAddr, ctx);
                            if (symName == null) symName = "global_" + Long.toHexString(fullAddr);
                            result.add(IrNode.assign(dst, new IrNode.AddrOf(symName)));
                            // Also propagate the global var type to the register
                            String gtype = GlobalVarResolver.resolveType(fullAddr, ctx);
                            if (gtype != null) {
                                ctx.regTypeMap.put(dst, gtype);
                            }
                            ctx.resolvedAddrs.put(dst, fullAddr);
                            next.valid = false; // suppress the add
                            ctx.adrpPages.remove(dst);
                            return;
                        }
                    }
                }
                // No merging — just emit the page address
                result.add(IrNode.assign(dst, IrNode.num(pageAddr)));
            }
            return;
        }

        // ── Atomic operations: ldaxr/stlxr/ldxr/stxr ──
        if (mn.equals("ldaxr") || mn.equals("ldxr") || mn.equals("ldarb") || mn.equals("ldarh")
                || mn.equals("ldar") || mn.equals("ldaxrb") || mn.equals("ldaxrh")) {
            // Load exclusive / load-acquire: Rd, [Rn] → Rd = __atomic_load_n(Rn, ...)
            if (ops.length >= 2) {
                String reg = ops[0].trim();
                String memExpr = derefExprWithCtx(ops[1].trim(), ctx, insn);
                if (!DecompContext.isStackBaseExpr(memExpr)) {
                int bits = reg.startsWith("w") ? 32 : 64; // v4.8: 按目标寄存器宽度
                String ordering = mn.startsWith("ldar") ? "__ATOMIC_SEQ_CST" : "__ATOMIC_ACQUIRE";
                result.add(new IrNode.Assign(reg,
                        new IrNode.Raw("__atomic_load_n((uint" + bits + "_t*)(" + memExpr + "), " + ordering + ")")));
                }
                // v4.8: 栈基址 → 静默 (不泄漏 sp/x29)
            }
            return;
        }
        if (mn.equals("stlxr") || mn.equals("stxr")) {
            // Store exclusive: Rs, Rd, [Rn] — Rs = 0 on success.
            // v3.2.16: emit a real CAS primitive (expected = the value the
            // preceding ldaxr/ldxr loaded) instead of the void-returning
            // __atomic_store_n assigned to the status register.
            if (ops.length >= 3) {
                String stReg = ops[0].trim();
                String valReg = ops[1].trim();
                String memExpr = derefExprWithCtx(ops[2].trim(), ctx, insn);
                if (!DecompContext.isStackBaseExpr(memExpr)) {
                String expected = findLdaxrExpected(insn, all, memExpr);
                String ptrCast = memExpr.startsWith("var_") || memExpr.startsWith("arg_")
                        ? memExpr : "(uint" + (valReg.startsWith("w") ? 32 : 64) + "_t*)(" + memExpr + ")";
                result.add(new IrNode.Assign(stReg,
                        new IrNode.Raw("__sync_bool_compare_and_swap(" + ptrCast + ", "
                                + expected + ", " + valReg + ") ? 0 : 1")));
                }
                // v4.8: 栈基址 → 静默 (不泄漏 sp/x29)
            }
            return;
        }
        if (mn.equals("stlr") || mn.equals("stlrb") || mn.equals("stlrh") || mn.equals("stlrw")) {
            // Store-release: Rd, [Rn] → __atomic_store_n(Rn, Rd, __ATOMIC_SEQ_CST)
            if (ops.length >= 2) {
                String valReg = ops[0].trim();
                String memExpr = derefExprWithCtx(ops[1].trim(), ctx, insn);
                if (!DecompContext.isStackBaseExpr(memExpr)) {
                int bits = valReg.startsWith("w") ? 32 : 64; // v4.8: 按操作数寄存器宽度
                result.add(new IrNode.Raw("__atomic_store_n((uint" + bits + "_t*)(" + memExpr + "), " + valReg + ", __ATOMIC_SEQ_CST)"));
                }
                // v4.8: 栈基址 → 静默 (不泄漏 sp/x29)
            }
            return;
        }
        if (mn.equals("clrex")) {
            return; // __atomic_thread_fence or just nop
        }

        // ── Debug trap: brk #N → __builtin_trap() ──
        if (mn.equals("brk") || mn.equals("hlt")) {
            result.add(new IrNode.Raw("__builtin_trap()"));
            return;
        }

        // ── NOP / hints ──
        if (mn.equals("nop") || mn.equals("hint") || mn.equals("yield")
                || mn.equals("wfe") || mn.equals("wfi") || mn.equals("sev")
                || mn.equals("dmb") || mn.equals("dsb") || mn.equals("isb")
                || mn.equals("bti")) {
            return; // produce nothing
        }

        // ── v3.2.16: Sign/zero extension (sxtb/sxth/sxtw/uxtb/uxth/uxtw) ──
        if (mn.equals("sxtb") || mn.equals("sxth") || mn.equals("sxtw")
                || mn.equals("uxtb") || mn.equals("uxth") || mn.equals("uxtw")) {
            if (ops.length >= 2) {
                String src = parseOperand(stripImm(ops[1].trim())).toC();
                if (mn.startsWith("s")) {
                    String cast = mn.endsWith("b") ? "(int8_t)"
                            : mn.endsWith("h") ? "(int16_t)" : "(int32_t)";
                    result.add(new IrNode.Assign(dst, new IrNode.Raw(cast + "(" + src + ")")));
                } else {
                    String mask = mn.endsWith("b") ? "0xff"
                            : mn.endsWith("h") ? "0xffff" : "0xffffffff";
                    result.add(new IrNode.Assign(dst,
                            new IrNode.Raw("(" + src + " & " + mask + ")")));
                }
            }
            return;
        }

        // ── v3.2.16: Byte reversal (rev/rev16/rev32) ──
        if (mn.equals("rev") || mn.equals("rev16") || mn.equals("rev32")) {
            if (ops.length >= 2) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("__builtin_bswap64("
                        + parseOperand(stripImm(ops[1].trim())).toC() + ")")));
            }
            return;
        }

        // ── v3.2.16: Register-variable shifts (lslv/lsrv/asrv) ──
        if (mn.equals("lslv") || mn.equals("lsrv") || mn.equals("asrv")) {
            if (ops.length >= 3) {
                String op = mn.startsWith("lsl") ? "<<" : ">>";
                result.add(new IrNode.Assign(dst, new IrNode.BinOp(op,
                        IrNode.var(ops[1].trim()), IrNode.var(ops[2].trim()))));
            }
            return;
        }

        // ── v3.2.16: Rotate (rorv/ror) — (x >> n) | (x << (bits - n)) ──
        if (mn.equals("rorv") || mn.equals("ror")) {
            if (ops.length >= 3) {
                String sh = parseOperand(stripImm(ops[2].trim())).toC();
                String src = IrNode.var(ops[1].trim()).toC();
                String bits = dst.startsWith("x") ? "64" : "32";
                result.add(new IrNode.Assign(dst, new IrNode.Raw("((" + src + " >> " + sh
                        + ") | (" + src + " << (" + bits + " - " + sh + ")))")));
            }
            return;
        }

        // ── v3.2.16: clz / cls ──
        if (mn.equals("clz") || mn.equals("cls")) {
            if (ops.length >= 2) {
                String fn = mn.equals("clz") ? "__builtin_clzll" : "__builtin_clrsbll";
                result.add(new IrNode.Assign(dst, new IrNode.Raw(fn + "("
                        + parseOperand(stripImm(ops[1].trim())).toC() + ")")));
            }
            return;
        }

        // ── v3.2.16: Bitfield insert (bfi/bfxil) ──
        if (mn.equals("bfc")) {
            // bfc Rd, #lsb, #width — 位域清零 → Rd &= ~mask (对齐 r2dec bfc)
            if (ops.length >= 3) {
                long lsb = parseAddr(stripImm(ops[1].trim()));
                long width = parseAddr(stripImm(ops[2].trim()));
                String mask = "0x" + Long.toHexString(
                        width >= 64 ? -1L : ((1L << width) - 1) << lsb);
                result.add(new IrNode.Assign(dst, new IrNode.Raw("(" + dst
                        + " & ~" + mask + ")")));
            }
            return;
        }
        if (mn.equals("bfi") || mn.equals("bfxil")) {
            if (ops.length >= 4) {
                long lsb = parseAddr(stripImm(ops[2].trim()));
                long width = parseAddr(stripImm(ops[3].trim()));
                long maskVal = width >= 64 ? -1L : ((1L << width) - 1) << lsb;
                String mask = "0x" + Long.toHexString(maskVal);
                result.add(new IrNode.Assign(dst, new IrNode.Raw("((" + dst
                        + " & ~" + mask + ") | ((" + ops[1].trim() + " << "
                        + lsb + ") & " + mask + "))")));
            }
            return;
        }

        // ── v3.2.16: Bitfield extract (ubfx/sbfx) and insert-zeros (ubfiz/sbfiz) ──
        if (mn.equals("ubfx") || mn.equals("sbfx") || mn.equals("ubfiz") || mn.equals("sbfiz")) {
            if (ops.length >= 4) {
                long lsb = parseAddr(stripImm(ops[2].trim()));
                long width = parseAddr(stripImm(ops[3].trim()));
                String src = ops[1].trim();
                if (mn.endsWith("fiz")) {
                    String mask = "0x" + Long.toHexString(
                            width >= 64 ? -1L : ((1L << width) - 1) << lsb);
                    result.add(new IrNode.Assign(dst, new IrNode.Raw("((" + src
                            + " << " + lsb + ") & " + mask + ")")));
                } else {
                    String mask = "0x" + Long.toHexString(width >= 64 ? -1L : (1L << width) - 1);
                    if (mn.startsWith("s")) {
                        // Arithmetic shift sign-extends the extracted field.
                        result.add(new IrNode.Assign(dst, new IrNode.Raw(
                                "((int64_t)((" + src + " >> " + lsb + ") & " + mask
                                + ") << " + (64 - width) + ") >> " + (64 - width) + ")")));
                    } else {
                        result.add(new IrNode.Assign(dst, new IrNode.Raw("((" + src
                                + " >> " + lsb + ") & " + mask + ")")));
                    }
                }
            }
            return;
        }

        // ── v3.2.16: mrs/msr — system register access ──
        if (mn.equals("mrs")) {
            if (ops.length >= 2) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw(ops[1].trim())));
            }
            return;
        }
        if (mn.equals("msr")) {
            return; // write system register — no meaningful C
        }

        // ── v3.2.16: prfm / dc / ic / eret — system, ignore or trap ──
        // v3.5: pac* 指针认证 (paciasp/autibsp/xpaclri...) — 只改栈指针/返回地址,
        //   对伪 C 无意义 → 忽略
        if (mn.equals("prfm") || mn.equals("dc") || mn.equals("ic")
                || mn.equals("eret")
                || mn.startsWith("pac") || mn.startsWith("aut") || mn.equals("xpaclri")
                || mn.equals("xpacd") || mn.equals("xpaci")) {
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

        // ── v3.2.16: FP scalar arithmetic ──
        if (mn.equals("fadd") || mn.equals("fsub") || mn.equals("fmul") || mn.equals("fdiv")) {
            if (ops.length >= 3) {
                String op = mn.equals("fadd") ? "+" : mn.equals("fsub") ? "-"
                        : mn.equals("fmul") ? "*" : "/";
                result.add(new IrNode.Assign(dst, new IrNode.BinOp(op,
                        IrNode.var(ops[1].trim()), IrNode.var(ops[2].trim()))));
            }
            return;
        }
        if (mn.equals("fmax") || mn.equals("fmin") || mn.equals("fmaxnm") || mn.equals("fminnm")) {
            if (ops.length >= 3) {
                String fn = mn.startsWith("fmax") ? "__builtin_fmax" : "__builtin_fmin";
                result.add(new IrNode.Assign(dst, new IrNode.Raw(fn + "("
                        + IrNode.var(ops[1].trim()).toC() + ", "
                        + IrNode.var(ops[2].trim()).toC() + ")")));
            }
            return;
        }
        if (mn.equals("fneg") || mn.equals("fabs") || mn.equals("fsqrt")) {
            if (ops.length >= 2) {
                String src = IrNode.var(ops[1].trim()).toC();
                if (mn.equals("fneg")) {
                    result.add(IrNode.neg(dst, IrNode.var(ops[1].trim())));
                } else {
                    String fn = mn.equals("fabs") ? "__builtin_fabs" : "__builtin_sqrt";
                    result.add(new IrNode.Assign(dst, new IrNode.Raw(fn + "(" + src + ")")));
                }
            }
            return;
        }
        if (mn.equals("fcmp") || mn.equals("fcmpe")) {
            if (ops.length >= 2) {
                cmpA = IrNode.var(ops[0].trim());
                cmpB = parseOperand(stripImm(ops[1].trim()));
            }
            return; // no output node — feeds subsequent conditionals
        }
        if (mn.equals("fcvtzs") || mn.equals("fcvtzu") || mn.equals("scvtf")
                || mn.equals("ucvtf") || mn.equals("fcvt")) {
            if (ops.length >= 2) {
                String src = parseOperand(stripImm(ops[1].trim())).toC();
                if (mn.startsWith("fcvtz")) {
                    String cast = mn.equals("fcvtzs") ? "(int64_t)" : "(uint64_t)";
                    result.add(new IrNode.Assign(dst, new IrNode.Raw(cast + "(" + src + ")")));
                } else {
                    result.add(new IrNode.Assign(dst, new IrNode.Raw("(double)(" + src + ")")));
                }
            }
            return;
        }

        // ── v3.5: FP 乘加 ──
        if (mn.equals("fmla") || mn.equals("fmls")) {
            if (ops.length >= 4) {
                String a = IrNode.var(ops[1].trim()).toC();
                String b = IrNode.var(ops[2].trim()).toC();
                String sign = mn.equals("fmla") ? "+" : "-";
                result.add(new IrNode.Assign(dst, new IrNode.Raw(
                        dst + " " + sign + " (" + a + " * " + b + ")")));
            }
            return;
        }

        // ── v3.5: NEON 向量指令 → __simd 语义保留 (vector lanes, 无法映射 C 标量) ──
        if (mn.startsWith("v")) {
            result.add(new IrNode.Raw("__simd(\"" + mn + " " + insn.opStr + "\")"));
            return;
        }

        // ── v3.2.16: CRC32 ──
        if (mn.startsWith("crc32")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Assign(dst, new IrNode.Raw("__builtin_arm_crc32w("
                        + IrNode.var(ops[1].trim()).toC() + ", "
                        + IrNode.var(ops[2].trim()).toC() + ")")));
            }
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        // v3.3: 通用 fallback — 覆盖未显式列举的同类指令 (r2 语义: 三操作数
        //   算术/位操作统一映射为二元表达式, 不再丢成注释).
        //   覆盖: sbc/adc (带进位), smull/umull/smnegl (乘加), rbit, extr,
        //   ccmp/ccmn (条件比较→比较), cmeq/cmtst (NEON 比较), 等.
        // ═══════════════════════════════════════════════════════════════
        // ── 带进位加减 (ADC/SBC): dst = src1 + src2 + carry ──
        if (mn.equals("adc") || mn.equals("adcs") || mn.equals("sbc") || mn.equals("sbcs")) {
            if (ops.length >= 3) {
                String opSign = (mn.equals("adc") || mn.equals("adcs")) ? "+" : "-";
                result.add(new IrNode.Assign(ops[0].trim(),
                        new IrNode.Raw(parseOperand(stripImm(ops[1].trim())).toC()
                                + " " + opSign + " "
                                + parseOperand(stripImm(ops[2].trim())).toC()
                                + (opSign.equals("+") ? " + carry" : " - carry"))));
            }
            return;
        }
        // ── 条件比较 (CCMP/CCMN): 转普通比较 (丢条件标志, 保守) ──
        if (mn.equals("ccmp") || mn.equals("ccmn")) {
            if (ops.length >= 3) {
                result.add(new IrNode.Raw("// ccmp " + insn.opStr));
                result.add(new IrNode.Raw("cmp(" + parseOperand(stripImm(ops[0].trim())).toC()
                        + ", " + parseOperand(stripImm(ops[1].trim())).toC() + ")"));
            }
            return;
        }
        // ── 通用三操作数二元运算: dst, src1, src2 → dst = src1 OP src2 ──
        {
            String op2 = null;
            if (mn.equals("smull") || mn.equals("smnegl") || mn.equals("umull")) op2 = "*";
            else if (mn.equals("smaddl") || mn.equals("umaddl")) op2 = "*";  // dst = a*b + c
            else if (mn.equals("smsubl") || mn.equals("umsubl")) op2 = "*";  // dst = c - a*b
            else if (mn.equals("rbit")) op2 = null;  // 位反转, 单操作
            else if (mn.equals("extr")) op2 = null;  // 提取
            if (op2 != null && ops.length >= 3) {
                String a = parseOperand(stripImm(ops[1].trim())).toC();
                String b = parseOperand(stripImm(ops[2].trim())).toC();
                String expr = a + " " + op2 + " " + b;
                // smaddl/smsubl: 3 个源操作数 (dst = a*b + c)
                if ((mn.equals("smaddl") || mn.equals("umaddl")) && ops.length >= 4) {
                    expr = expr + " + " + parseOperand(stripImm(ops[3].trim())).toC();
                } else if ((mn.equals("smsubl") || mn.equals("umsubl")) && ops.length >= 4) {
                    expr = parseOperand(stripImm(ops[3].trim())).toC() + " - " + expr;
                }
                result.add(new IrNode.Assign(ops[0].trim(), new IrNode.Raw(expr)));
                return;
            }
            // 单操作位操作: rbit dst, src
            if (mn.equals("rbit") && ops.length >= 2) {
                result.add(new IrNode.Assign(ops[0].trim(),
                        new IrNode.Raw("__builtin_bitreverse64("
                                + parseOperand(stripImm(ops[1].trim())).toC() + ")")));
                return;
            }
            // extr dst, src1, src2, #lsb — 位提取: (src1 >> lsb) | (src2 << (64-lsb))
            if (mn.equals("extr") && ops.length >= 4) {
                String lsb = stripImm(ops[3].trim());
                result.add(new IrNode.Assign(ops[0].trim(),
                        new IrNode.Raw("((" + parseOperand(stripImm(ops[1].trim())).toC()
                                + " >> " + lsb + ") | ("
                                + parseOperand(stripImm(ops[2].trim())).toC()
                                + " << (64 - " + lsb + ")))")));
                return;
            }
        }

        // ── Fallback: emit as raw comment ──
        result.add(new IrNode.Raw("/* " + insn.mnemonic + " " + insn.opStr + " */"));
    }

    // ── Helpers ──

    /**
     * v3.3: 回溯 x0/r0 的最近活跃赋值, 生成 return 表达式.
     * 与 {@link #backtraceArgValues} 共用解析逻辑, 但专门服务 ret:
     * <pre>
     *   ldr x0, [sp,#8]; ret  → "var_8"
     *   mov x0, #5;      ret  → "5"
     *   mov x0, x1;      ret  → "x1"
     *   bl foo;          ret  → "foo(...)"  (返回 call 结果, 由调用处理)
     * </pre>
     * 返回 null 表示 x0 无活跃赋值 (void 函数 / 值不可追踪).
     */
    private String findReturnValue(IrInsn insn, List<IrInsn> all, DecompContext ctx) {
        int startIdx = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        if (startIdx < 0) return null;
        String candidate = null; // 最近 x0 赋值 (可能条件执行)
        boolean candidateSet = false;
        for (int i = startIdx - 1; i >= 0 && i >= startIdx - 32; i--) {
            IrInsn prev = all.get(i);
            if (prev == null || prev.opStr == null) continue;
            String mn = prev.mnemonic;
            String op = prev.opStr.trim();
            if (mn == null || mn.isEmpty()) continue;
            // v3.5: 条件分支 → 已收集候选赋值在分支路径内 (条件执行) → 保守返回 x0
            if (prev.isCondBranch) return "x0";
            // 控制流边界: 无条件分支 / 其他 ret → 路径断开
            if ((prev.isBranch && !prev.isCondBranch) || prev.isRet
                    || mn.equals("bl") || mn.equals("blr") || mn.equals("b")) {
                break;
            }
            // call → x0 来自 bl 返回值; 但更早的 call 会被后续赋值覆盖
            if (prev.isCall) {
                if (candidateSet) continue;
                if (prev.nodes != null) {
                    for (IrNode n : prev.nodes) {
                        if (n instanceof IrNode.Assign) {
                            IrNode.Assign a = (IrNode.Assign) n;
                            if ("x0".equals(a.dst)) {
                                return a.src != null ? a.src.toC() : null;
                            }
                        }
                    }
                }
                return null;  // call 未捕获 → 无法确定
            }
            // v4.8: movk — 取 resolvedAddrs 合并值 (movz/movk 链的完整结果)
            if (mn.equals("movk")) {
                if (candidateSet) continue;
                String[] ops = op.split(",");
                if (ops.length < 2) continue;
                String dst = ops[0].trim();
                boolean isRetReg = dst.equals("x0") || dst.equals("w0")
                        || dst.equals("r0") || dst.equals("a1");
                if (!isRetReg) continue;
                Long tracked = ctx.resolvedAddrs.get(dst);
                if (tracked != null) {
                    candidate = "0x" + Long.toHexString(tracked);
                    candidateSet = true;
                }
                continue;
            }
            // 找 x0/r0 的赋值 (只取最近一次, 之后继续前扫查分支)
            if (mn.equals("mov") || mn.equals("movz") || mn.equals("movn")
                    || mn.equals("ldr") || mn.equals("ldur") || mn.equals("ldrb")
                    || mn.equals("ldrh") || mn.equals("ldrsw") || mn.equals("ldrsb")
                    || mn.equals("ldrsh") || mn.equals("add") || mn.equals("orr")
                    || mn.equals("lsl") || mn.equals("lsr") || mn.equals("asr")
                    || mn.equals("mul") || mn.equals("sub") || mn.equals("eor")
                    || mn.equals("and") || mn.equals("mvn") || mn.equals("neg")) {
                if (candidateSet) continue;
                String[] ops = op.split(",");
                if (ops.length < 2) continue;
                String dst = ops[0].trim();
                boolean isRetReg = dst.equals("x0") || dst.equals("w0")
                        || dst.equals("r0") || dst.equals("a1");
                if (!isRetReg) continue;
                // v3.3: 用顶层逗号切分 (方括号内逗号不切), 正确处理 [sp, #8]
                String[] opsTop = splitOps(op);
                String src = opsTop.length > 1 ? opsTop[1].trim() : "";
                // 栈变量: ldr x0, [sp,#8] → var_8
                if (mn.startsWith("ldr") || mn.startsWith("ldur")) {
                    String memExpr = src;
                    String sv = tryResolveStackVar(memExpr, ctx, prev);
                    if (sv != null) {
                        candidate = sv;
                        candidateSet = true;
                        continue;
                    }
                    // ldr x0, [x1] → *(type*)x1
                    candidate = derefExpr(memExpr);
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
                if (src.matches("[wxr]\\d+")) {
                    candidate = src;
                    candidateSet = true;
                    continue;
                }
                return null;  // 复杂表达式, 保守放弃
            }
        }
        return candidateSet ? candidate : null;
    }

    private IrNode parseOperand(String s) {
        s = s.trim();
        if (s.isEmpty()) return IrNode.num(0);
        // Zero registers → 0
        if (s.equals("xzr") || s.equals("wzr")) return IrNode.num(0);
        // Try numeric
        try {
            if (s.startsWith("0x")) {
                Long.parseUnsignedLong(s.substring(2), 16);
                return IrNode.num(s);
            }
            Long.parseLong(s);
            return IrNode.num(s);
        } catch (NumberFormatException e) {
            // not a number → register / symbol
            return IrNode.var(s);
        }
    }

    /**
     * v3.2.15: 解析可能带内联桶式移位的操作数 (r2dec arm.js ARM 坑 #2).
     * splitOps 按逗号切分, "add x0, x1, x2, lsl #3" → ops=[x0,x1,x2,"lsl #3"].
     * <pre>
     *   ops=[..., "x2", "lsl #3"] → (x2 << 3)
     *   ops=[..., "x2", "lsr #3"] → (x2 >> 3)
     *   ops=[..., "x2", "asr #3"] → (x2 >> 3)   // 有符号移位在伪 C 中同为 >>
     *   ops=[..., "x2", "ror #3"] → 无直接对应, 保留原样
     *   ops=[..., "x2"]           → x2 (无移位)
     * </pre>
     */
    private IrNode shiftedOperand(String[] ops, int baseIdx, int shiftIdx) {
        if (ops == null || baseIdx < 0 || baseIdx >= ops.length) {
            return IrNode.num(0);
        }
        IrNode baseNode = parseOperand(stripImm(ops[baseIdx].trim()));
        if (shiftIdx < 0 || shiftIdx >= ops.length) {
            return baseNode; // 无移位
        }
        String shiftPart = ops[shiftIdx].trim();
        String[] shiftTok = shiftPart.split("\\s+");
        if (shiftTok.length < 2) {
            return baseNode;
        }
        String shiftType = shiftTok[0].toLowerCase();
        String shiftAmt = shiftTok[1].trim();
        IrNode amtNode = parseOperand(stripImm(shiftAmt));
        if (shiftType.equals("lsl")) {
            return new IrNode.BinOp("<<", baseNode, amtNode);
        }
        if (shiftType.equals("lsr") || shiftType.equals("asr")) {
            return new IrNode.BinOp(">>", baseNode, amtNode);
        }
        // ror / others: 伪 C 无直接等价, 输出 (base)
        return baseNode;
    }

    private String stripImm(String s) {
        return s.startsWith("#") ? s.substring(1) : s;
    }

    /** v3.5: pc 相对目标绝对地址 (AArch64: pc = 当前指令地址). */
    private long pcRelAddr(IrInsn insn, long imm) {
        return insn.addr + imm;
    }

    /** v3.5: 从 "[pc, #0x14]" / "#0x14" 提取相对偏移. */
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
        // [x0] → x0
        // [x0, #8] → x0 + 8
        // [x0, #8]! → x0 + 8
        // [sp, #-16]! → sp - 16
        String s = memExpr.replace("[", "").replace("]", "").replace("!", "");
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
        if (mn.contains("sw") || mn.contains("sb") || mn.contains("sh")) return 32;
        return 64;
    }

    /**
     * Width (in bits) of a register operand, deduced from its name.
     * <ul>
     *   <li>{@code x0}-{@code x30}, {@code xzr} → 64</li>
     *   <li>{@code w0}-{@code w30}, {@code wzr} → 32</li>
     *   <li>{@code q0}-{@code q31} → 128 (NEON vector)</li>
     *   <li>{@code d0}-{@code d31} → 64 (FP)</li>
     *   <li>{@code s0}-{@code s31} → 32 (FP)</li>
     *   <li>otherwise → 64</li>
     * </ul>
     */
    private int regBits(String reg) {
        if (reg == null || reg.length() < 2) return 64;
        char c = reg.charAt(0);
        if (c == 'x') return 64;
        if (c == 'w') return 32;
        if (c == 'q') return 128;
        if (c == 'd') return 64;
        if (c == 's') return 32;
        if (c == 'h') return 16;
        if (c == 'b') return 8;
        return 64;
    }

    /** C integer type for a register/memory of the given width.
     * v2.9.44: Use __uint128_t for 128-bit (GCC/Clang extension).
     * uint128_t is NOT a standard type and causes compilation errors. */
    private String typeFor(int bits) {
        if (bits >= 128) return "__uint128_t";
        return "uint" + bits + "_t";
    }

    private String mapCond(String suffix) {
        switch (suffix) {
            case "eq": return "EQ";
            case "ne": return "NE";
            case "gt": return "GT";
            case "ge": return "GE";
            case "lt": return "LT";
            case "le": return "LE";
            case "hi": return "GT";   // unsigned higher  → map to signed GT
            case "ls": return "LE";   // unsigned lower-or-same → LE
            case "hs":
            case "cs": return "GE";   // carry set / unsigned higher-or-same → GE
            case "lo":
            case "cc": return "LT";   // carry clear / unsigned lower → LT
            case "mi": return "LT";   // negative → LT
            case "pl": return "GE";   // positive-or-zero → GE
            case "vs": return "EQ";   // overflow (rough mapping)
            case "vc": return "NE";   // no overflow (rough mapping)
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

    private long parseLongSafe(String s, long def) {
        try {
            return Long.parseLong(s.replace("#", "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * v4.1: 解析 blr xN 是否为虚表槽位调用.
     * 向前找最近的有效 ldr xN, [xBase, #off] — 若 xBase 可解析为虚表基址
     * (adrp+add 已记录到 resolvedAddrs), 返回 "ClassName::method" 否则 null.
     */
    private String resolveVtableIndirect(IrInsn insn, List<IrInsn> all, DecompContext ctx) {
        if (ctx == null || ctx.vtableEntries == null || ctx.vtableEntries.length == 0) {
            return null;
        }
        String reg = insn.opStr != null ? insn.opStr.trim() : null;
        if (reg == null) return null;
        for (int i = insn.idx - 1; i >= 0 && i >= insn.idx - 8; i--) {
            IrInsn prev = all.get(i);
            if (prev == null || !prev.valid) continue;
            String pm = prev.mnemonic;
            if (pm.startsWith("ldr") || pm.startsWith("ldur")) {
                String[] po = prev.opStr != null ? prev.opStr.trim().split(",", 2) : null;
                if (po == null || po.length < 2 || !po[0].trim().equals(reg)) return null;
                String mem = po[1].trim();
                if (!mem.startsWith("[")) return null;
                int end = mem.indexOf(']');
                if (end < 0) return null;
                String inner = mem.substring(1, end);
                String[] parts = inner.split(",");
                if (parts.length < 1) return null;
                String baseReg = parts[0].trim();
                long off = 0;
                if (parts.length >= 2) {
                    String os = parts[1].trim().replace("#", "").replace("!", "").trim();
                    try {
                        off = os.startsWith("0x") || os.startsWith("0X")
                                ? Long.parseLong(os.substring(2), 16)
                                : Long.parseLong(os);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                String addrExpr = (off == 0) ? baseReg
                        : baseReg + " + 0x" + Long.toHexString(off);
                return ctx.resolveVtableSlot(addrExpr);
            }
            if (pm.equals("bl") || pm.equals("b") || pm.equals("ret")
                    || pm.startsWith("cb") || pm.startsWith("tb")) {
                return null; // 遇到其他控制流停止
            }
        }
        return null;
    }

    /**
     * v4.6: 调用点参数个数 — 函数地址 → native 结构化签名优先.
     * AArch64 目标 4 字节对齐; 签名缺失时返回 -1.
     */
    private int signatureArgCount(long target, DecompContext ctx) {
        if (ctx == null || ctx.signatureByAddr == null) return -1;
        com.exbin.app.elf.FunctionInfo fi = ctx.signatureByAddr.get(target & ~3L);
        if (fi == null || fi.restoredParamTypes == null) return -1;
        return fi.restoredParamTypes.size();
    }

    private String resolveCallee(long target, String op, DecompContext ctx) {
        // v3.5: AArch64 分支目标 4 字节对齐规范化
        target &= ~3L;
        // v3.2.16: 1. exact callee map (labels/imports from the UI), demangled
        if (ctx != null && ctx.calleeMap != null) {
            String name = ctx.calleeMap.get(target);
            if (name != null) {
                return GlobalVarResolver.sanitizeFuncName(
                        GlobalVarResolver.demangle(name, ctx));
            }
        }
        // v3.2.16: 2. unified symbol table → real (demangled) symbol names,
        // e.g. sub_1458 → std::string::basic_string (r2dec fcn_XXXX parity)
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
            if (sym != null && sym.startsWith("_Z") && sym.length() < 512) {
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
            }
        }
        String s = op.trim().replace("#", "");
        if (s.startsWith("0x")) return "sub_" + s.substring(2);
        return s.isEmpty() ? "unknown_callee" : s;
    }

    /**
     * v3.2.15: 回溯 call 前对 x0-x7 的赋值, 还原参数实际值.
     * (r2dec arm.js _populate_arm64_call_args 语义)
     * <pre>
     *   mov x0, #5
     *   mov x1, x2
     *   bl  func      →  func(5, x2)
     * </pre>
     * 只回溯最近一次对每个寄存器的赋值, 遇到分支/调用/返回即停.
     */
    private Map<String, String> backtraceArgValues(IrInsn insn, List<IrInsn> all,
                                                   DecompContext ctx) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        int startIdx = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        // v3.3: 窗口从 20 提到 64 (r2dec 对 adrp+add+ldr 链可达 3-4 条指令,
        //   大函数里 call 前可能隔更多指令; 64 是安全上限且回溯遇 bl/ret 即停)
        for (int i = startIdx - 1; i >= 0 && i >= startIdx - 64; i--) {
            IrInsn prev = all.get(i);
            // v3.3: 不再跳过 invalid 指令 — adrp+add 合并会把 ADD 标 invalid,
            //   但它携带 referencedString/符号信息, 正是参数值来源.
            String mn = prev.mnemonic;
            String op = prev.opStr;
            if (op == null) continue;
            // 遇到控制流边界停止回溯 (正版: pop/cb*/b*/tb* 即停)
            if (mn.equals("b") || mn.startsWith("b.") || mn.startsWith("cb")
                    || mn.startsWith("tb") || mn.equals("br") || mn.equals("blr")
                    || mn.equals("bl") || prev.isRet || prev.isCall) {
                break;
            }
            // mov/movz/add/ldr 等: dst = value
            if (mn.equals("movk")) {
                // v4.8: movk 是 movz/movk 链的合并步骤 — 回溯时取 resolvedAddrs
                // 里的完整合并值, 而非裸 src (只覆盖 16 位片段)
                String[] ops = op.trim().split(",");
                if (ops.length < 2) continue;
                String dst = ops[0].trim();
                if (!dst.matches("[wx][0-7]")) continue;
                if (result.containsKey(dst)) continue;
                Long tracked = ctx.resolvedAddrs.get(dst);
                if (tracked != null) {
                    result.put(dst, "0x" + Long.toHexString(tracked));
                }
                continue;
            }
            // mov/movz/add/ldr 等: dst = value
            if (mn.equals("mov") || mn.equals("movz") || mn.equals("movn")
                    || mn.equals("add") || mn.equals("ldr") || mn.equals("orr")
                    || mn.equals("adrp") || mn.equals("fmov")) {
                String[] ops = op.trim().split(",");
                if (ops.length < 2) continue;
                String dst = ops[0].trim();
                if (!dst.matches("[wx][0-7]")) continue;
                if (result.containsKey(dst)) continue; // 已找到最近赋值
                String src = ops[1].trim();
                // 解析值: 立即数 / 寄存器 / 字符串 / 地址 / sp+imm 表达式
                String val = resolveArgValue(prev, src, ops, ctx);
                if (val != null) {
                    result.put(dst, val);
                }
            }
        }
        return result;
    }

    /**
     * 把 bl 参数寄存器的赋值来源转成伪 C 表达式.
     * <pre>
     *   #5        → "5"
     *   x2        → "x2" (寄存器透传)
     *   referencedString → "\"...\"" (字符串字面量)
     *   nativeTargetAddr → "sym_xxx" (符号地址)
     *   add x0, sp, #0x50 → "sp + 0x50" (栈指针折叠, 正版 r2dec 语义)
     *   adrp+add 链 → "0x20123" 或字符串 (页地址+偏移合成)
     * </pre>
     */
    private String resolveArgValue(IrInsn prev, String src, String[] ops, DecompContext ctx) {
        if (src == null) return null;
        src = src.trim();
        // 字符串引用: ldr x0, [pc, #N] 或 adrp+add 合并后的 ADD → "str"
        if (prev.origInsn != null && prev.origInsn.referencedString != null
                && !prev.origInsn.referencedString.isEmpty()) {
            String s = prev.origInsn.referencedString.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            return "\"" + s + "\"";
        }
        // 符号地址: ldr x0, [pc, #N] → sym_xxx
        if (prev.origInsn != null && prev.origInsn.nativeTargetAddr != 0) {
            long t = prev.origInsn.nativeTargetAddr;
            if (ctx != null && ctx.symbolMap != null) {
                String sym = ctx.symbolMap.get(t);
                if (sym != null) return sym;
            }
            return "0x" + Long.toHexString(t);
        }
        // ── v3.3: add xN, sp, #imm → "sp + imm" (正版 r2dec: stack arg 直接表达式) ──
        // v3.5: 扩展 — add xN, 栈基址|别名, #imm → "sp + (off+imm)"
        if (prev.mnemonic.equals("add") && ops.length >= 3) {
            String base = ops[1].trim();
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
                DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(base) : null;
                if (a != null) {
                    return ctx.stackAddrRef(a.base, a.offset + immVal);
                }
                if (base.equals("sp") || base.equals("x29") || base.equals("fp")) {
                    return ctx.stackAddrRef(base, immVal);
                }
            }
        }
        // 立即数: #5 / 0x10
        if (src.startsWith("#")) {
            String imm = src.substring(1).trim();
            try {
                if (imm.startsWith("0x")) {
                    Long.parseUnsignedLong(imm.substring(2), 16);
                } else {
                    Long.parseLong(imm);
                }
                return imm;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // 寄存器透传: x2 → x2 (v3.5: 别名 → &var_XX)
        if (src.matches("[wx]\\d+")) {
            DecompContext.StackAlias a = ctx != null ? ctx.resolveStackAlias(src) : null;
            if (a != null) return ctx.stackAddrRef(a.base, a.offset);
            return src;
        }
        // v3.5: 栈基址透传: mov x0, sp → &var_0 / mov x0, x29 → &var_0
        if (src.equals("sp") || src.equals("x29") || src.equals("fp")) {
            return ctx != null ? ctx.stackAddrRef(src, 0) : src;
        }
        return null;
    }

    private int inferArgCount(IrInsn insn, List<IrInsn> all) {        // Scan backwards from bl to find the highest xN written
        int maxArg = -1;
        int startIdx = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        for (int i = startIdx - 1; i >= 0 && i >= startIdx - 20; i--) {
            IrInsn prev = all.get(i);
            String mn = prev.mnemonic;
            String op = prev.opStr;
            if (op == null) continue;
            // v3.3: 遇到控制流边界立即停止 — bl/ret/分支前的赋值不是本调用的参数
            if (mn.equals("b") || mn.startsWith("b.") || mn.startsWith("cb")
                    || mn.startsWith("tb") || mn.equals("br") || mn.equals("blr")
                    || mn.equals("bl") || prev.isRet || prev.isCall) {
                break;
            }
            // Look for mov/add/sub/ldr with x0-x7 as destination
            if (mn.equals("mov") || mn.equals("movz") || mn.equals("add") || mn.equals("sub")
                    || mn.equals("ldr") || mn.equals("ldp") || mn.equals("orr")) {
                String[] ops = op.trim().split(",");
                if (ops.length > 0) {
                    String dst = ops[0].trim();
                    if (dst.matches("[wx][0-7]")) {
                        int n = Integer.parseInt(dst.substring(1));
                        if (n > maxArg) maxArg = n;
                    }
                }
            }
        }
        return maxArg + 1;
    }

    private String[] splitOps(String op) {
        // Split on commas but not inside brackets
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < op.length(); i++) {
            char c = op.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
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
     * Try to resolve a memory expression like "[sp, #0x10]" or "[x29, #-8]"
     * to a stack variable name (var_XX / arg_XX).
     * <p>
     * Resolution order (avoids fragile regex parsing of opStr):
     * <ol>
     *   <li>{@code insn.origInsn.nativeStackVar} — computed by the native
     *       Capstone detail layer, 100% accurate for all addressing modes.</li>
     *   <li>{@code insn.origInsn.stackVar} — filled by the Java
     *       DisasmAnnotator when native detail is unavailable.</li>
     *   <li>Fallback: structured split of the memory expression (no regex),
     *       then {@link DecompContext#resolveStackVar}.</li>
     * </ol>
     * @return variable name, or null if not a known stack variable
     */
    private String tryResolveStackVar(String memExpr, DecompContext ctx, IrInsn insn) {
        return tryResolveStackVar(memExpr, ctx, insn, "uint64_t");
    }

    /**
     * v3.5: 带类型版本 — 动态注册的 var_XX 使用调用方的访问宽度 (uint32_t/uint64_t).
     */
    private String tryResolveStackVar(String memExpr, DecompContext ctx, IrInsn insn,
                                      String type) {
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
        // Strip brackets and pre-index !
        String s = memExpr.replace("[", "").replace("]", "").replace("!", "").trim();
        String[] parts = s.split(",");
        if (parts.length < 1) return null;
        String base = parts[0].trim();
        // v3.5: 栈指针别名 — [x0] 且 x0 = sp+0x10 → 归一为 (sp, 0x10+off)
        DecompContext.StackAlias alias = ctx.resolveStackAlias(base);
        long offset = alias != null ? alias.offset : 0;
        if (alias != null) base = alias.base;
        // Only sp, x29 (FP), x19 (sometimes alternate FP) are stack pointers
        if (!base.equals("sp") && !base.equals("x29") && !base.equals("x19")
                && !base.equals("fp")) {
            return null;
        }
        if (parts.length >= 2) {
            String offStr = stripImm(parts[1].trim());
            // v3.5: 寄存器偏移 ([sp, x2]) 无法静态折叠 → 交 fallback 注释化
            if (!offStr.matches("-?0[xX][0-9a-fA-F]+|-?\\d+")) return null;
            long off = parseAddr(offStr);
            // Handle negative: if starts with -, parse magnitude
            if (offStr.startsWith("-")) {
                off = -parseAddr(offStr.substring(1));
            }
            offset += off;
        }
        String varName = ctx.resolveStackVar(base, offset);
        if (varName == null) {
            // v3.5: [xN] 别名形式 StackFrameAnalyzer 未预注册 → 动态注册
            String name = "var_" + Long.toHexString(Math.abs(offset));
            ctx.registerStackVar(base, offset, name, type);
            ctx.markUsed(name);
            return name;
        }
        return varName;
    }

    /** Extract the base register from a memory expression like "[sp, #0x10]". */
    private String getStackBase(String memExpr) {
        String s = memExpr.replace("[", "").replace("]", "").replace("!", "").trim();
        String[] parts = s.split(",");
        if (parts.length < 1) return null;
        String base = parts[0].trim();
        if (base.equals("sp") || base.equals("x29") || base.equals("x19") || base.equals("fp")) {
            return base;
        }
        return null;
    }

    /** Extract the offset from a memory expression like "[sp, #0x10]". */
    private long getStackOffset(String memExpr) {
        String s = memExpr.replace("[", "").replace("]", "").replace("!", "").trim();
        String[] parts = s.split(",");
        if (parts.length < 2) return 0;
        String offStr = stripImm(parts[1].trim());
        long offset = parseAddr(offStr);
        if (offStr.startsWith("-")) {
            offset = -parseAddr(offStr.substring(1));
        }
        return offset;
    }

    /**
     * derefExpr that also tries stack variable resolution for atomic ops.
     * Passes {@code insn} through so {@link #tryResolveStackVar} can consult
     * the structured {@code nativeStackVar}/{{@code stackVar} fields.
     */
    private String derefExprWithCtx(String memExpr, DecompContext ctx, IrInsn insn) {
        if (ctx != null) {
            String sv = tryResolveStackVar(memExpr, ctx, insn);
            if (sv != null) return sv;
        }
        return derefExpr(memExpr);
    }

    /**
     * v3.2.16: Find the register loaded by the ldaxr/ldxr that precedes this
     * stlxr/stxr (the CAS "expected" old value). Scans back up to 6
     * instructions; stops at any branch/call/barrier/load/store.
     *
     * @param insn the stlxr/stxr instruction
     * @param all  the full instruction list
     * @param addr the dereferenced address expression of the store (e.g. "x0")
     * @return the ldaxr/ldxr target register name, or "0" if none found
     */
    private String findLdaxrExpected(IrInsn insn, List<IrInsn> all, String addr) {
        int start = insn.idx >= 0 ? insn.idx : all.indexOf(insn);
        for (int i = start - 1; i >= 0 && i >= start - 6; i--) {
            IrInsn prev = all.get(i);
            if (prev == null || prev.mnemonic == null) continue;
            String pm = prev.mnemonic;
            if (pm.equals("ldaxr") || pm.equals("ldxr")
                    || pm.equals("ldaxrb") || pm.equals("ldxrb")
                    || pm.equals("ldaxrh") || pm.equals("ldxrh")) {
                String[] ops = prev.opStr != null ? prev.opStr.trim().split(",") : null;
                if (ops != null && ops.length >= 2
                        && derefExprWithCtx(ops[1].trim(), null, null).equals(addr)) {
                    return ops[0].trim();
                }
                return "0";
            }
            // Stop at anything that may change the expected value or control flow
            if (pm.startsWith("b") || pm.startsWith("bl") || pm.equals("br")
                    || pm.equals("dmb") || pm.equals("dsb") || pm.equals("isb")
                    || pm.startsWith("ldr") || pm.startsWith("str")) {
                return "0";
            }
        }
        return "0";
    }

    /**
     * Resolve an address to a symbol name using labels/imports or native target.
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

    /**
     * Get the C type string for a register based on its width.
     * w0-w30 → uint32_t, x0-x30 → uint64_t
     */
    private String regType(String reg) {
        if (reg == null || reg.isEmpty()) return "uint64_t";
        char c = reg.charAt(0);
        if (c == 'w') return "uint32_t";
        if (c == 'x') return "uint64_t";
        return "uint64_t";
    }
}
