package com.exbin.app.elf.pseudoc.r2dec;

import com.exbin.app.elf.DisassembledInstruction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Instruction carrier wrapping a {@link DisassembledInstruction} with eagerly
 * parsed operands. Ported from r2dec's {@code core/instruction.js} plus the
 * ARM/AArch64 branch and condition classification drawn from {@code arm.js}.
 * <p>
 * On construction the raw assembly is tokenized through {@link ArmParser} and
 * the resulting mnemonic ({@link #mnem}) and operand array ({@link #opd}) are
 * used to classify the instruction (call / return / branch / conditional) and
 * to extract a static jump target when one is present.
 */
public class IrInsn {

    /** ARM32 conditional-branch suffixes (the part after the leading 'b'). */
    public static final Set<String> ARM32_COND_SUFFIXES = new HashSet<>(Arrays.asList(
            "eq", "ne", "cs", "hs", "cc", "lo", "mi", "pl", "vs", "vc",
            "hi", "ls", "ge", "lt", "gt", "le", "al"));

    public long addr;
    public int size;
    public String assembly;
    public String mnemonic;
    public String mnemonicRaw;
    public String opStr;
    public String mnem;
    public Object[] opd;
    public IrNode code;
    public boolean valid;
    public Long jump;

    /** Structured IR nodes produced by the instruction handler (Stage 3). */
    public final List<IrNode> nodes = new ArrayList<>();

    /** Static jump target address (set by handler), 0 if none. */
    public long jumpTarget;

    public boolean isCall;
    public boolean isRet;
    public boolean isBranch;
    public boolean isCondBranch;
    public boolean isUncondBranch;
    public boolean isReturn;

    public String condType;
    public IrNode condA;
    public IrNode condB;

    /**
     * IT-block wrap condition (ARM32 Thumb). When non-null, the structurer
     * emits this instruction's nodes wrapped inside {@code if (<itWrapCond>) { ... }}.
     * Set by {@link Arm32Handler} for each instruction that falls inside an
     * {@code it{mask}} block. {@code null} means "not inside an IT block".
     */
    public String itWrapCond;

    public String string;
    public String symbol;
    public String callee;
    public String label;

    public int marker;
    public int idx; // index in the instruction list (set by pipeline)
    public final DisassembledInstruction origInsn;

    /**
     * Build an IR instruction from a disassembled instruction (marker defaults to 0).
     *
     * @param insn the raw disassembly
     */
    public IrInsn(DisassembledInstruction insn) {
        this(insn, 0);
    }

    /**
     * Build an IR instruction from a disassembled instruction.
     *
     * @param insn   the raw disassembly
     * @param marker the block marker id (used by control-flow tracking)
     */
    public IrInsn(DisassembledInstruction insn, int marker) {
        this.origInsn = insn;
        this.marker = marker;
        this.valid = true;

        this.addr = insn.address;
        this.size = insn.bytes != null ? insn.bytes.length : 0;
        this.mnemonicRaw = insn.mnemonic != null ? insn.mnemonic : "";
        this.mnemonic = this.mnemonicRaw.toLowerCase();
        this.opStr = insn.opStr != null ? insn.opStr : "";
        this.assembly = (this.mnemonicRaw + " " + this.opStr).trim();

        ArmParser.Parsed parsed = ArmParser.parse(this.assembly);
        this.mnem = parsed.mnem;
        this.opd = parsed.opd;

        this.string = (insn.referencedString != null && !insn.referencedString.isEmpty())
                ? insn.referencedString : null;

        this.isCall = isCallMnemonic(this.mnem);
        this.isRet = isRetMnemonic(this.mnem, this.opd);
        this.isBranch = isBranchMnemonic(this.mnem);
        this.isCondBranch = isCondBranchMnemonic(this.mnem);
        this.isUncondBranch = isUncondBranchMnemonic(this.mnem);
        // v3.9: ARM32 跳转表典型实现 add pc, pc, rN — 间接分支 (无直接目标)
        if ("add".equals(this.mnem) && this.opd != null
                && this.opd.length >= 2 && "pc".equals(this.s(0))) {
            this.isBranch = true;
            this.isUncondBranch = true;
        }

        parseJumpTarget();
    }

    // ── Operand accessors (mirror ArmParser.Parsed API) ──

    /** Operand as a String (or {@code null} if it is a memory sub-array). */
    public String s(int i) {
        if (opd == null || i < 0 || i >= opd.length) {
            return null;
        }
        Object o = opd[i];
        return o instanceof String ? (String) o : null;
    }

    /** Operand as a String[] memory sub-array (or {@code null}). */
    public String[] m(int i) {
        if (opd == null || i < 0 || i >= opd.length) {
            return null;
        }
        Object o = opd[i];
        return o instanceof String[] ? (String[]) o : null;
    }

    /** Operand rendered as a single string (memory joined with " + "). */
    public String str(int i) {
        if (opd == null || i < 0 || i >= opd.length) {
            return null;
        }
        Object o = opd[i];
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof String[]) {
            return String.join(" + ", (String[]) o);
        }
        return null;
    }

    /** Number of operands. */
    public int opdLength() {
        return opd == null ? 0 : opd.length;
    }

    // ── Branch / call / return classification ──

    private static boolean isCallMnemonic(String m) {
        return "bl".equals(m) || "blx".equals(m) || "blr".equals(m);
    }

    private static boolean isRetMnemonic(String m, Object[] opd) {
        if ("ret".equals(m) || "retaa".equals(m) || "retab".equals(m)) {
            return true;
        }
        if ("bx".equals(m) && "lr".equals(opdStr(opd, 0))) {
            return true;
        }
        if ("pop".equals(m) && opd != null) {
            for (int i = 0; i < opd.length; i++) {
                if ("pc".equals(opdStr(opd, i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBranchMnemonic(String m) {
        if (m == null) {
            return false;
        }
        if (m.startsWith("b.")) {
            return true;
        }
        if ("b".equals(m) || "bx".equals(m) || "br".equals(m)
                || "tbb".equals(m) || "tbh".equals(m)) {
            return true;
        }
        if ("cbz".equals(m) || "cbnz".equals(m) || "tbz".equals(m) || "tbnz".equals(m)) {
            return true;
        }
        return isArm32CondBranch(m);
    }

    private static boolean isCondBranchMnemonic(String m) {
        if (m == null) {
            return false;
        }
        if (m.startsWith("b.")) {
            return true;
        }
        if ("cbz".equals(m) || "cbnz".equals(m) || "tbz".equals(m) || "tbnz".equals(m)) {
            return true;
        }
        return isArm32CondBranch(m);
    }

    private static boolean isUncondBranchMnemonic(String m) {
        return "b".equals(m) || "bx".equals(m) || "br".equals(m)
                || "tbb".equals(m) || "tbh".equals(m);
    }

    /**
     * True when {@code m} is an ARM32 conditional branch (beq, bne, blt, ...),
     * i.e. a leading 'b' followed by a recognized condition suffix.
     */
    public static boolean isArm32CondBranch(String m) {
        if (m == null || m.length() <= 1 || m.charAt(0) != 'b') {
            return false;
        }
        return ARM32_COND_SUFFIXES.contains(m.substring(1));
    }

    // ── Jump target extraction ──

    private void parseJumpTarget() {
        if (opd == null || opd.length == 0 || mnem == null) {
            return;
        }
        String target = null;
        String m = this.mnem;
        if ("cbz".equals(m) || "cbnz".equals(m) || "tbz".equals(m) || "tbnz".equals(m)) {
            // cbz/cbnz/tbz/tbnz: last operand is the branch target.
            target = s(opd.length - 1);
        } else if (m.startsWith("b.") || "b".equals(m) || "bl".equals(m) || "blx".equals(m)) {
            // b.<cc> / b / bl / blx: first operand is the target.
            target = s(0);
        } else if (isArm32CondBranch(m)) {
            // ARM32 conditional branch (beq, bne, ...): first operand is the target.
            target = s(0);
        }
        if (target != null) {
            Long addr = tryParseAddr(target);
            if (addr != null) {
                this.jump = addr;
            }
        }
    }

    // ── Condition state ──

    /** Set the comparison condition consumed by a following b.cc / csel. */
    public void setCondition(IrNode a, IrNode b, String type) {
        this.condA = a;
        this.condB = b;
        this.condType = type;
    }

    /** Invalidate the static jump target (e.g. it falls outside the function). */
    public void setBadJump() {
        this.jump = null;
    }

    // ── Helpers ──

    private static String opdStr(Object[] opd, int i) {
        if (opd == null || i < 0 || i >= opd.length) {
            return null;
        }
        Object o = opd[i];
        return o instanceof String ? (String) o : null;
    }

    /**
     * Parse a hex address token. Accepts a {@code 0x} prefix or plain hex.
     *
     * @param s the address token
     * @return the parsed address, or {@code null} when not a hex number
     */
    public static Long tryParseAddr(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Long.parseUnsignedLong(t.substring(2), 16);
            }
            return Long.parseUnsignedLong(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format("0x%x: %s", addr, assembly);
    }
}
