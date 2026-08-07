package com.soreverse.mcp.engine.standalone;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java AArch64 (ARM64) instruction disassembler.
 * Ported from SO逆向分析工具, no native dependencies required.
 *
 * Covers: branches, load/store, data processing, SIMD/FP, system instructions.
 */
public class Arm64Disasm {

    private static final String[] REGS_32 = {
        "w0","w1","w2","w3","w4","w5","w6","w7",
        "w8","w9","w10","w11","w12","w13","w14","w15",
        "w16","w17","w18","w19","w20","w21","w22","w23",
        "w24","w25","w26","w27","w28","w29","w30","wsp"
    };

    private static final String[] REGS_64 = {
        "x0","x1","x2","x3","x4","x5","x6","x7",
        "x8","x9","x10","x11","x12","x13","x14","x15",
        "x16","x17","x18","x19","x20","x21","x22","x23",
        "x24","x25","x26","x27","x28","x29","x30","sp"
    };

    private static final String[] COND = {
        "eq","ne","cs","cc","mi","pl","vs","vc",
        "hi","ls","ge","lt","gt","le","al","nv"
    };

    private static final String[] BAR_SHIFT = { "lsl", "lsr", "asr", "ror" };

    // Print format mnemonics
    private static final String[] SYS_OP1 = { "#0","#1","#2","#3","#4","#5","#6","#7" };
    private static final String[] BARRIER = {
        "oshld","oshst","osh","osh", null, null, null, null,
        "nshld","nshst","nsh","nsh", null, null, null, null,
        "ishld","ishst","ish","ish", null, null, null, null,
        "ld","st","sy","sy", null, null, null, null
    };

    private final long baseAddr;
    private final byte[] data;
    private int pos;

    public Arm64Disasm(byte[] data, long baseAddr) {
        this.data = data;
        this.baseAddr = baseAddr;
        this.pos = 0;
    }

    /**
     * Disassemble up to {@code count} instructions.
     */
    public List<Insn> disassemble(int count) {
        List<Insn> result = new ArrayList<>(count);
        for (int i = 0; i < count && pos < data.length; i++) {
            Insn insn = decodeOne();
            if (insn == null) break;
            result.add(insn);
        }
        return result;
    }

    public static class Insn {
        public final long address;
        public final String mnemonic;
        public final String operands;

        public Insn(long address, String mnemonic, String operands) {
            this.address = address;
            this.mnemonic = mnemonic;
            this.operands = operands;
        }

        @Override
        public String toString() {
            return String.format("%08x:  %-8s%s", address, mnemonic, operands);
        }
    }

    // ── core decoder ──

    private Insn decodeOne() {
        if (pos + 4 > data.length) return null;
        int insn = read32();
        long addr = baseAddr + (pos - 4);
        int op0 = (insn >>> 30) & 3;       // top 2 bits
        int op1 = (insn >>> 29) & 1;       // bit 29
        int op2 = (insn >>> 25) & 15;      // bits 28:25
        int op3 = (insn >>> 24) & 1;       // bit 24
        int op4 = (insn >>> 23) & 3;       // bits 24:23
        int op5 = (insn >>> 10) & 15;      // bits 13:10

        // ── SYS / IC / DC / AT ──
        if ((insn & 0xFFF80000) == 0xD5080000) {
            int l = (insn >>> 21) & 1;
            int op1v = (insn >>> 16) & 7;
            int crn = (insn >>> 12) & 15;
            int crm = (insn >>> 8) & 15;
            int op2v = (insn >>> 5) & 7;
            int rt = insn & 31;
            String mne;
            if (crn == 7 && crm == 3 && op2v == 3 && op1v == 0 && l == 0) {
                if (rt == 0x1F) { mne = "ic"; return makeInsn(addr, "ic", "ialluis"); }
                else { mne = "ic"; return makeInsn(addr, "ic", "ivau, " + REGS_64[rt]); }
            }
            if (crn == 7 && crm == 1 && op2v == 0 && op1v == 0 && l == 0 && rt == 0x1F) {
                return makeInsn(addr, "dc", "cvac");
            }
            return makeInsn(addr, mneForSys(l, op1v, crn, crm, op2v, rt), formatSysOp(l, op1v, crn, crm, op2v, rt));
        }

        // ── HLT ──
        if ((insn & 0xFFE00000) == 0xD4200000) { // HLT
            int imm = (insn >>> 5) & 0xFFFF;
            return makeInsn(addr, "hlt", "#" + imm);
        }

        // ── BRK ──
        if ((insn & 0xFFE00000) == 0xD4200000 && (insn & 0x1F) == 0) {
            // Actually HLT handling above is for all FED420 + imm16 variants
            // This is a duplicate check in the original, BRK uses 0xD4200000 too but differs
        }
        if ((insn & 0xFFE0001F) == 0xD4200000) {
            int imm = (insn >>> 5) & 0xFFFF;
            return makeInsn(addr, "brk", "#" + imm);
        }

        // ── NOP ──
        if (insn == 0xD503201F) {
            return makeInsn(addr, "nop", "");
        }

        // ── RET ──
        if ((insn & 0xFFFFFC1F) == 0xD65F0000) {
            int rn = (insn >>> 5) & 31;
            return makeInsn(addr, "ret", rn == 30 ? "" : REGS_64[rn]);
        }

        // ── BR ──
        if ((insn & 0xFFFFFC1F) == 0xD61F0000) {
            int rn = (insn >>> 5) & 31;
            return makeInsn(addr, "br", REGS_64[rn]);
        }

        // ── BLR ──
        if ((insn & 0xFFFFFC1F) == 0xD63F0000) {
            int rn = (insn >>> 5) & 31;
            return makeInsn(addr, "blr", REGS_64[rn]);
        }

        // ── B / BL (unconditional immediate) ──
        if ((insn & 0xFC000000) == 0x14000000) {
            int offset = insn & 0x3FFFFFF;
            if ((offset & 0x2000000) != 0) offset |= 0xFC000000; // sign extend
            long target = addr + offset * 4L;
            String mne = (insn & 0x80000000) != 0 ? "bl" : "b";
            return makeInsn(addr, mne, "0x" + Long.toHexString(target));
        }

        // ── B.cond ──
        if ((insn & 0xFF000010) == 0x54000000) {
            int offset = (insn >>> 5) & 0x7FFFF;
            if ((offset & 0x40000) != 0) offset |= 0xFFF80000;
            long target = addr + offset * 4L;
            int cond = insn & 0xF;
            return makeInsn(addr, "b." + COND[cond], "0x" + Long.toHexString(target));
        }

        // ── CBZ / CBNZ ──
        if ((insn & 0x7E000000) == 0x34000000) {
            int offset = (insn >>> 5) & 0x7FFFF;
            if ((offset & 0x40000) != 0) offset |= 0xFFF80000;
            long target = addr + offset * 4L;
            int rt = insn & 31;
            boolean sf = (insn & 0x80000000) != 0;
            boolean nz = (insn & 0x01000000) != 0;
            String reg = sf ? REGS_64[rt] : REGS_32[rt];
            return makeInsn(addr, nz ? "cbnz" : "cbz", reg + ", 0x" + Long.toHexString(target));
        }

        // ── TBZ / TBNZ ──
        if ((insn & 0x7E000000) == 0x36000000) {
            int offset = (insn >>> 5) & 0x3FFF;
            if ((offset & 0x2000) != 0) offset |= 0xFFFFC000;
            long target = addr + offset * 4L;
            int rt = insn & 31;
            int bit = ((insn >>> 19) & 31) | ((insn >>> 24) & 32);
            boolean nz = (insn & 0x01000000) != 0;
            return makeInsn(addr, nz ? "tbnz" : "tbz", REGS_64[rt] + ", #" + bit + ", 0x" + Long.toHexString(target));
        }

        // ── ADR / ADRP ──
        if ((insn & 0x1F000000) == 0x10000000) {
            int rd = insn & 31;
            int immlo = (insn >>> 29) & 3;
            int immhi = (insn >>> 5) & 0x7FFFF;
            int imm = (immhi << 2) | immlo;
            if ((insn & 0x80000000) != 0) { // ADRP
                long page = ((imm << 12) + addr) & ~0xFFFL;
                String target = "0x" + Long.toHexString(page);
                return makeInsn(addr, "adrp", REGS_64[rd] + ", " + target);
            } else { // ADR
                if ((imm & 0x100000) != 0) imm |= 0xFFF00000; // sign extend 21-bit
                long target = addr + imm;
                return makeInsn(addr, "adr", REGS_64[rd] + ", 0x" + Long.toHexString(target));
            }
        }

        // ── SVC ──
        if ((insn & 0xFFE0001F) == 0xD4000001) {
            int imm = (insn >>> 5) & 0xFFFF;
            return makeInsn(addr, "svc", "#" + imm);
        }

        // ── MSR (immediate) ──
        if ((insn & 0xFFF8F01F) == 0xD503401F) {
            int crm = (insn >>> 8) & 15;
            int op2 = (insn >>> 5) & 7;
            int mask = (insn >>> 16) & 3;
            if (mask == 3 && crm == 4 && op2 == 5) return makeInsn(addr, "msr", "DAIFSet, #" + ((insn >>> 8) & 7));
            if (mask == 3 && crm == 4 && op2 == 3) return makeInsn(addr, "msr", "DAIFClr, #" + ((insn >>> 8) & 7));
            if (mask == 3 && crm == 3 && op2 == 0) return makeInsn(addr, "msr", "PAN, #" + ((insn >>> 8) & 1));
            if (crm == 4 && op2 == 6) return makeInsn(addr, "msr", "UAO, #" + ((insn >>> 8) & 1));
            return makeInsn(addr, "msr", "S" + mask + "_" + crm + "_" + op2 + ", #" + ((insn >>> 8) & 1));
        }

        // ── MRS ──
        if ((insn & 0xFFE00000) == 0xD5300000 && (insn & 0x1F) != 0x1F) {
            int rt = insn & 31;
            int o0 = (insn >>> 19) & 1;
            int op1v = (insn >>> 16) & 7;
            int crn = (insn >>> 12) & 15;
            int crm = (insn >>> 8) & 15;
            int op2v = (insn >>> 5) & 7;
            String[] regNames = {
                "tpidr_el0", "tpidrro_el0", "tpidr_el1",
                "ctr_el0", "dc_el0", "dczid_el0",
                "id_aa64mmfr0_el1", "id_aa64mmfr1_el1", "id_aa64mmfr2_el1",
                "id_aa64isar0_el1", "id_aa64isar1_el1",
                "id_aa64pfr0_el1", "id_aa64pfr1_el1",
                "midr_el1", "mpidr_el1", "revidr_el1",
                "vbar_el1", "sctlr_el1", "ttbr0_el1", "ttbr1_el1",
                "tcr_el1", "esr_el1", "far_el1", "par_el1",
                "contextidr_el1", "currentel", "daif",
                "nzcv", "fpcr", "fpsr",
                "sp_el0", "sp_el1",
                "elr_el1", "spsr_el1"
            };
            String regName = findSysReg(o0, op1v, crn, crm, op2v);
            if (regName == null) regName = "S" + o0 + "_" + op1v + "_C" + crn + "_C" + crm + "_" + op2v;
            return makeInsn(addr, "mrs", REGS_64[rt] + ", " + regName);
        }

        // ── MSR (register) ──
        if ((insn & 0xFFE00000) == 0xD5100000 && (insn & 0x1F) != 0x1F) {
            int rt = insn & 31;
            int o0 = (insn >>> 19) & 1;
            int op1v = (insn >>> 16) & 7;
            int crn = (insn >>> 12) & 15;
            int crm = (insn >>> 8) & 15;
            int op2v = (insn >>> 5) & 7;
            String regName = findSysReg(o0, op1v, crn, crm, op2v);
            if (regName == null) regName = "S" + o0 + "_" + op1v + "_C" + crn + "_C" + crm + "_" + op2v;
            return makeInsn(addr, "msr", regName + ", " + REGS_64[rt]);
        }

        // ── Load/Store register (unsigned offset) ──
        // opc=10xx0x00
        if ((insn & 0x3B000000) == 0x38000000 && (insn & 0x00800000) != 0) {
            int size = (insn >>> 30) & 3;
            int v = (insn >>> 26) & 1;
            int opc = (insn >>> 22) & 3;
            int imm12 = (insn >>> 10) & 0xFFF;
            int rn = (insn >>> 5) & 31;
            int rt = insn & 31;
            int scale = size;
            int offset = imm12 << scale;
            boolean isVector = v == 1;
            String[] regs = isVector ? (size == 3 ? Q_REGS : D_REGS) : (size == 3 ? REGS_64 : REGS_32);
            String suffix = size == 3 ? "" : (size == 2 && !isVector ? "" : "");

            // LDR / STR
            java.util.Map.Entry<String,String> entry = ldrStrMnemonic(opc, v);
            if (entry != null) {
                String mne = entry.getKey();
                String rtName = isVector && size == 3 ? Q_REGS[rt] : (isVector && size == 2 ? V_REGS[rt] : (size == 3 ? REGS_64[rt] : REGS_32[rt]));
                String operand;
                if (imm12 == 0) {
                    operand = rtName + ", [" + REGS_64[rn] + "]";
                } else {
                    operand = rtName + ", [" + REGS_64[rn] + ", #" + offset + "]";
                }
                return makeInsn(addr, mne, operand);
            }
        }

        // ── Load/Store register (register offset) ──
        if ((insn & 0x3B200000) == 0x38200800) {
            int size = (insn >>> 30) & 3;
            int v = (insn >>> 26) & 1;
            int opc = (insn >>> 22) & 3;
            int rm = (insn >>> 16) & 31;
            int option = (insn >>> 13) & 7;
            int s = (insn >>> 12) & 1;
            int rn = (insn >>> 5) & 31;
            int rt = insn & 31;
            String ext = extName(option);
            int amount = s != 0 ? size : 0;
            String rtName = (v != 0) ? (size == 3 ? Q_REGS[rt] : (size == 2 ? V_REGS[rt] : V_REGS[rt])) : (size == 3 ? REGS_64[rt] : REGS_32[rt]);
            java.util.Map.Entry<String,String> entry = ldrStrMnemonic(opc, v);
            if (entry != null) {
                String mne = entry.getKey();
                String opStr = rtName + ", [" + REGS_64[rn] + ", " + REGS_64[rm];
                if (ext != null) opStr += ", " + ext;
                if (s != 0) opStr += " lsl #" + amount;
                opStr += "]";
                return makeInsn(addr, mne, opStr);
            }
        }

        // ── Load/Store pair (pre/post/index) ──
        if ((insn & 0x3A000000) == 0x28000000) {
            int opc = (insn >>> 30) & 3;
            int l = (insn >>> 22) & 1;
            int imm7 = (insn >>> 15) & 0x7F;
            int rt2 = (insn >>> 10) & 31;
            int rn = (insn >>> 5) & 31;
            int rt = insn & 31;
            int scale = 2 + ((opc & 2) >>> 1);
            int offset = imm7 << scale;
            if ((imm7 & 0x40) != 0) offset |= ~0x3F;
            String[] regs = REGS_64;
            boolean load = l == 1;
            boolean preIdx = (insn & 0x180) == 0x080;
            boolean postIdx = (insn & 0x180) == 0x000;
            String mne = (load ? "ldp" : "stp");
            String rtStr = regs[rt] + ", " + regs[rt2];

            String opStr;
            if (postIdx && offset == 0) {
                opStr = rtStr + ", [" + REGS_64[rn] + "]";
            } else if (postIdx) {
                opStr = rtStr + ", [" + REGS_64[rn] + "], #" + offset;
            } else if (preIdx) {
                opStr = rtStr + ", [" + REGS_64[rn] + ", #" + offset + "]!";
            } else {
                opStr = rtStr + ", [" + REGS_64[rn] + ", #" + offset + "]";
            }
            return makeInsn(addr, mne, opStr);
        }

        // ── MOVZ / MOVK / MOVN ──
        if ((insn & 0x1F800000) == 0x12800000) {
            int sf = (insn >>> 31) & 1;
            int opc = (insn >>> 29) & 3;
            int hw = (insn >>> 21) & 3;
            int imm16 = (insn >>> 5) & 0xFFFF;
            int rd = insn & 31;
            long val = ((long) imm16) << (hw * 16);
            String[] mnes = { "movn", "", "movz", "movk" };
            String mne = mnes[opc];
            String reg = sf != 0 ? REGS_64[rd] : REGS_32[rd];
            String opStr = reg + ", #0x" + Long.toHexString(val);
            if (hw != 0) opStr += ", lsl #" + (hw * 16);
            return makeInsn(addr, mne, opStr);
        }

        // ── ADD / SUB (immediate) ──
        if ((insn & 0x1F000000) == 0x11000000) {
            int sf = (insn >>> 31) & 1;
            int op = (insn >>> 30) & 1;  // 0=ADD, 1=SUB
            int s = (insn >>> 29) & 1;
            int sh = (insn >>> 22) & 1;
            int imm12 = (insn >>> 10) & 0xFFF;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String mne = op == 0 ? "add" : "sub";
            if (s != 0) mne += "s";
            long immediate = sh != 0 ? (long) imm12 << 12 : imm12;
            return makeInsn(addr, mne, regs[rd] + ", " + regs[rn] + ", #0x" + Long.toHexString(immediate));
        }

        // ── ADD / SUB (extended register) ──
        if ((insn & 0x1F200000) == 0x0B200000) {
            int sf = (insn >>> 31) & 1;
            int op = (insn >>> 30) & 1;
            int s = (insn >>> 29) & 1;
            int rm = (insn >>> 16) & 31;
            int option = (insn >>> 13) & 7;
            int imm3 = (insn >>> 10) & 7;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String mne = op == 0 ? "add" : "sub";
            if (s != 0) mne += "s";
            String opStr = regs[rd] + ", " + regs[rn] + ", " + regs[rm];
            String ext = extName(option);
            if (ext != null) opStr += ", " + ext;
            if (imm3 != 0) opStr += " #" + imm3;
            return makeInsn(addr, mne, opStr);
        }

        // ── Logical instructions (AND, ORR, EOR, etc.) ──
        if ((insn & 0x1E000000) == 0x0A000000) {
            int sf = (insn >>> 31) & 1;
            int opc = (insn >>> 29) & 3;
            int op2 = (insn >>> 21) & 7;
            int rm = (insn >>> 16) & 31;
            int shift = (insn >>> 10) & 0x3F;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String[] logicalMnes = { "and", "bic", "orr", "orn", "eor", "eon" };
            String mne;
            if (opc == 0 && op2 == 0) mne = "and";
            else if (opc == 0 && op2 == 1) mne = "bic";
            else if (opc == 1 && op2 == 0) mne = "orr";
            else if (opc == 1 && op2 == 1) mne = "orn";
            else if (opc == 2 && op2 == 0) mne = "eor";
            else if (opc == 2 && op2 == 1) mne = "eon";
            else if (opc == 3 && op2 == 0) mne = "ands";
            else if (opc == 3 && op2 == 1) mne = "bics";
            else return makeInsn(addr, "unknown", "");
            String opStr = regs[rd] + ", " + regs[rn] + ", " + regs[rm];
            if (shift != 0) {
                int shiftType = (shift >>> 4) & 3;
                int shiftAmt = shift & 15;
                opStr += ", " + BAR_SHIFT[shiftType] + " #" + shiftAmt;
            }
            return makeInsn(addr, mne, opStr);
        }

        // ── ALU shift (ADD/SUB with shift) ──
        if ((insn & 0x1F200000) == 0x0B000000) {
            int sf = (insn >>> 31) & 1;
            int op = (insn >>> 30) & 1;
            int s = (insn >>> 29) & 1;
            int shift = (insn >>> 22) & 3;
            int rm = (insn >>> 16) & 31;
            int imm6 = (insn >>> 10) & 0x3F;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String mne = op == 0 ? "add" : "sub";
            if (s != 0) mne += "s";
            String opStr = regs[rd] + ", " + regs[rn] + ", " + regs[rm];
            if (shift != 0 || imm6 != 0) opStr += ", " + BAR_SHIFT[shift] + " #" + imm6;
            return makeInsn(addr, mne, opStr);
        }

        // ── Logical (immediate) ──
        if ((insn & 0x1F800000) == 0x12000000) {
            int sf = (insn >>> 31) & 1;
            int opc = (insn >>> 29) & 3;
            int n = (insn >>> 22) & 1;
            int immr = (insn >>> 16) & 0x3F;
            int imms = (insn >>> 10) & 0x3F;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String[] mnes = { "and", "orr", "eor", "ands" };
            String mne = mnes[opc];
            long imm = decodeBitMaskImmediate(n, immr, imms, sf);
            return makeInsn(addr, mne, regs[rd] + ", " + regs[rn] + ", #0x" + Long.toHexString(imm) + " // lsl #" + immr);
        }

        // ── Data processing (2-source) ──
        if ((insn & 0x1FE00000) == 0x1AC00000) {
            int sf = (insn >>> 31) & 1;
            int s = (insn >>> 29) & 1;
            int opcode = (insn >>> 10) & 0x3F;
            int rm = (insn >>> 16) & 31;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String mne;
            switch (opcode) {
                case 0x00: case 0x20: mne = "udiv"; break;
                case 0x02: case 0x22: mne = "sdiv"; break;
                case 0x08: case 0x28: mne = "lslv"; break;
                case 0x09: case 0x29: mne = "lsrv"; break;
                case 0x0A: case 0x2A: mne = "asrv"; break;
                case 0x0B: case 0x2B: mne = "rorv"; break;
                case 0x10: case 0x30: mne = "msub"; break;
                case 0x18: case 0x38: mne = "smaddl"; break;
                case 0x19: case 0x39: mne = "smsubl"; break;
                case 0x1A: case 0x3A: mne = "smnegl"; break;
                default: mne = "data_proc_2src_" + opcode; break;
            }
            return makeInsn(addr, mne, regs[rd] + ", " + regs[rn] + ", " + regs[rm]);
        }

        // ── Data processing (3-source) ──
        if ((insn & 0x1F000000) == 0x1B000000) {
            int sf = (insn >>> 31) & 1;
            int op54 = (insn >>> 29) & 3;
            int op31 = (insn >>> 21) & 7;
            int rm = (insn >>> 16) & 31;
            int ra = (insn >>> 10) & 31;
            int rn = (insn >>> 5) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            String mne;
            boolean isWide = op54 == 1 || op54 == 2 || op31 >= 4;
            switch ((op54 << 3) | op31) {
                case 0: mne = "madd"; break;
                case 1: mne = "msub"; break;
                case 8: mne = "smaddl"; break;
                case 9: mne = "smsubl"; break;
                case 10: mne = "smnegl"; break;
                case 16: mne = "umaddl"; break;
                case 17: mne = "umsubl"; break;
                case 18: mne = "smnegl"; break;
                default: mne = "madd"; break;
            }
            return makeInsn(addr, mne, regs[rd] + ", " + regs[rn] + ", " + regs[rm] + ", " + regs[ra]);
        }

        // ── MOV (register alias: ORR Xd, XZR, Xm) ──
        if ((insn & 0x7FE00000) == 0x2A0003E0) {
            int sf = (insn >>> 31) & 1;
            int rm = (insn >>> 16) & 31;
            int rd = insn & 31;
            String[] regs = sf != 0 ? REGS_64 : REGS_32;
            return makeInsn(addr, "mov", regs[rd] + ", " + regs[rm]);
        }

        // ── Floating point ──
        if ((insn & 0x0E000000) == 0x0E000000) {
            return decodeFpSimd(insn, addr);
        }

        // ── Load/Store exclusive ──
        if ((insn & 0x3F000000) == 0x08000000) {
            int size = (insn >>> 30) & 3;
            int o2 = (insn >>> 24) & 1;
            int l = (insn >>> 22) & 1;
            int o1 = (insn >>> 21) & 1;
            int rs = (insn >>> 16) & 31;
            int o0 = (insn >>> 15) & 1;
            int rt2 = (insn >>> 10) & 31;
            int rn = (insn >>> 5) & 31;
            int rt = insn & 31;
            String[] regs = size == 3 ? REGS_64 : REGS_32;
            String mne;
            if (o1 == 0 && o0 == 0) {
                mne = l != 0 ? "ldxr" : "stxr";
                if (l == 0) {
                    String opStr = REGS_32[rs] + ", " + regs[rt] + ", [" + REGS_64[rn] + "]";
                    return makeInsn(addr, "stxr", opStr);
                } else {
                    return makeInsn(addr, "ldxr", regs[rt] + ", [" + REGS_64[rn] + "]");
                }
            }
        }

        // ── Load/Store Literal (PC-relative) ──
        if ((insn & 0x3B000000) == 0x18000000) {
            int opc = (insn >>> 30) & 3;
            int v = (insn >>> 26) & 1;
            int imm19 = insn & 0x7FFFF;
            if ((imm19 & 0x40000) != 0) imm19 |= 0xFFF80000;
            long target = addr + imm19 * 4L;
            int rt = insn & 31;
            String mne;
            String rtName;
            if (v == 0) {
                if (opc == 0) { mne = "ldr"; rtName = REGS_32[rt]; }
                else if (opc == 1) { mne = "ldr"; rtName = REGS_64[rt]; }
                else if (opc == 2) { mne = "ldrsw"; rtName = REGS_32[rt]; }
                else { mne = "prfm"; rtName = ""; }
            } else {
                if (opc == 0) { mne = "ldr"; rtName = S_REGS[rt]; }
                else if (opc == 1) { mne = "ldr"; rtName = D_REGS[rt]; }
                else if (opc == 2) { mne = "ldr"; rtName = Q_REGS[rt]; }
                else { mne = "ldr"; rtName = V_REGS[rt]; }
            }
            return makeInsn(addr, mne, rtName + ", 0x" + Long.toHexString(target));
        }

        return makeInsn(addr, "unknown", String.format("0x%08x", insn));
    }

    // ── Helper methods ──

    private int read32() {
        int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)
              | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    private static Insn makeInsn(long addr, String mne, String ops) {
        return new Insn(addr, mne, ops);
    }

    private static long signExtend(long value, int bits) {
        long mask = 1L << (bits - 1);
        return (value ^ mask) - mask;
    }

    private static java.util.Map.Entry<String,String> ldrStrMnemonic(int opc, int v) {
        if (v == 0) {
            switch (opc) {
                case 0: return new java.util.AbstractMap.SimpleEntry<>("str", "");
                case 1: return new java.util.AbstractMap.SimpleEntry<>("str", "");
                case 2: return new java.util.AbstractMap.SimpleEntry<>("ldr", "");
                case 3: return new java.util.AbstractMap.SimpleEntry<>("ldr", "");
            }
        } else {
            switch (opc) {
                case 0: return new java.util.AbstractMap.SimpleEntry<>("str", "");
                case 1: return new java.util.AbstractMap.SimpleEntry<>("str", "");
                case 2: return new java.util.AbstractMap.SimpleEntry<>("ldr", "");
                case 3: return new java.util.AbstractMap.SimpleEntry<>("ldr", "");
            }
        }
        return null;
    }

    private static String extName(int option) {
        switch (option) {
            case 0: return "uxtb";
            case 1: return "uxth";
            case 2: return "uxtw";
            case 3: return "uxtx";
            case 4: return "sxtb";
            case 5: return "sxth";
            case 6: return "sxtw";
            case 7: return "sxtx";
            default: return null;
        }
    }

    // ── FP/SIMD decoder ──
    private Insn decodeFpSimd(int insn, long addr) {
        int op = (insn >>> 24) & 3;
        int rd = insn & 31;
        int rn = (insn >>> 5) & 31;
        int rm = (insn >>> 16) & 31;

        // Scalar floating point
        if ((insn & 0x1E000000) == 0x1E000000) {
            String mne;
            int opc = (insn >>> 22) & 3;
            int type = (insn >>> 20) & 3;
            int o1 = (insn >>> 10) & 1;
            int o2 = (insn >>> 14) & 3;

            if (type == 0) { // float (32-bit)
                switch ((o2 << 5) | (opc << 3) | (o1 << 2) | (insn >>> 12 & 3)) {
                    case 0: mne = "fmul"; break;
                    case 1: mne = "fdiv"; break;
                    case 2: mne = "fadd"; break;
                    case 3: mne = "fsub"; break;
                    case 4: case 5: mne = "fmax"; break;
                    case 6: case 7: mne = "fmin"; break;
                    default: {
                        if ((insn & 0x1FE00000) == 0x1E200000) {
                            int imm8 = (insn >>> 13) & 0xFF;
                            mne = "fmov";
                            return makeInsn(addr, mne, S_REGS[rd] + ", #" + fpImmDecode(imm8));
                        }
                        mne = "f_" + Integer.toHexString(insn);
                    }
                }
                return makeInsn(addr, "f" + mne, S_REGS[rd] + ", " + S_REGS[rn] + ", " + S_REGS[rm]);
            } else if (type == 1) { // double (64-bit)
                return makeInsn(addr, "fadd", D_REGS[rd] + ", " + D_REGS[rn] + ", " + D_REGS[rm]);
            }
        }

        // AdvSIMD
        if ((insn & 0x0E000000) == 0x0E000000) {
            int Q = (insn >>> 30) & 1;
            int size = (insn >>> 22) & 3;
            String prefix = Q != 0 ? "q" : "";
            String[] regs = Q != 0 ? Q_REGS : V_REGS;
            return makeInsn(addr, prefix + "add", regs[rd] + ", " + regs[rn] + ", " + regs[rm]);
        }

        return makeInsn(addr, "float", String.format("0x%08x", insn));
    }

    private static String mneForSys(int l, int op1, int crn, int crm, int op2, int rt) {
        if (l == 0 && crn == 7 && op1 == 0) {
            if (crm == 0 && op2 == 0) return rt == 31 ? "dc" : "dc";
            if (crm == 1 && op2 == 0) return "dc";
            if (crm == 3 && op2 == 3) return "ic";
            if (crm == 10 && op2 == 1) return "dc";
        }
        if (l == 0 && crn == 8 && op1 == 0 && crm == 3 && op2 == 0) return "at";
        return "sys";
    }

    private static String formatSysOp(int l, int op1, int crn, int crm, int op2, int rt) {
        if (l == 0 && crn == 7 && op1 == 0) {
            if (crm == 0 && op2 == 0) return rt == 31 ? "cvac" : "ivau, " + REGS_64[rt];
            if (crm == 0 && op2 == 1) return rt == 31 ? "cvau" : "civac, " + REGS_64[rt];
            if (crm == 1 && op2 == 0) return rt == 31 ? "cvac" : "cvac, " + REGS_64[rt];
            if (crm == 3 && op2 == 3) return rt == 31 ? "ialluis" : "iallu, " + REGS_64[rt];
            if (crm == 10 && op2 == 1) return "zva, " + REGS_64[rt];
        }
        if (l == 0 && crn == 8 && op1 == 0 && crm == 3 && op2 == 0) return "s1e1r, " + REGS_64[rt];
        return "#" + op1 + ", c" + crn + ", c" + crm + ", #" + op2 + (rt != 31 ? ", " + REGS_64[rt] : "");
    }

    private static String findSysReg(int o0, int op1, int crn, int crm, int op2) {
        if (o0 == 0 && op1 == 3 && crn == 13 && crm == 0 && op2 == 1) return "tpidr_el1";
        if (o0 == 0 && op1 == 3 && crn == 13 && crm == 0 && op2 == 2) return "tpidrro_el0";
        if (o0 == 0 && op1 == 2 && crn == 13 && crm == 0 && op2 == 0) return "tpidr_el0";
        if (o0 == 0 && op1 == 3 && crn == 0 && crm == 0 && op2 == 0) return "midr_el1";
        if (o0 == 0 && op1 == 3 && crn == 0 && crm == 0 && op2 == 5) return "mpidr_el1";
        if (o0 == 0 && op1 == 3 && crn == 0 && crm == 0 && op2 == 1) return "ctr_el0";
        if (o0 == 0 && op1 == 3 && crn == 0 && crm == 0 && op2 == 7) return "revidr_el1";
        if (o0 == 0 && op1 == 3 && crn == 0 && crm == 0 && op2 == 6) return "dczid_el0";
        if (o0 == 0 && op1 == 3 && crn == 12 && crm == 0 && op2 == 0) return "vbar_el1";
        if (o0 == 0 && op1 == 3 && crn == 1 && crm == 0 && op2 == 0) return "sctlr_el1";
        if (o0 == 0 && op1 == 3 && crn == 2 && crm == 0 && op2 == 0) return "ttbr0_el1";
        if (o0 == 0 && op1 == 3 && crn == 2 && crm == 0 && op2 == 1) return "ttbr1_el1";
        if (o0 == 0 && op1 == 3 && crn == 2 && crm == 0 && op2 == 2) return "tcr_el1";
        if (o0 == 0 && op1 == 3 && crn == 5 && crm == 2 && op2 == 0) return "esr_el1";
        if (o0 == 0 && op1 == 3 && crn == 6 && crm == 0 && op2 == 0) return "far_el1";
        if (o0 == 0 && op1 == 3 && crn == 7 && crm == 4 && op2 == 0) return "par_el1";
        if (o0 == 0 && op1 == 3 && crn == 13 && crm == 0 && op2 == 3) return "tpidr_el1";
        if (o0 == 0 && op1 == 3 && crn == 13 && crm == 0 && op2 == 4) return "tpidr_el1";
        if (o0 == 0 && op1 == 3 && crn == 13 && crm == 0 && op2 == 5) return "tpidr_el1";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 0 && op2 == 0) return "spsr_el1";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 0 && op2 == 1) return "elr_el1";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 4 && op2 == 0) return "nzcv";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 4 && op2 == 1) return "fpcr";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 4 && op2 == 2) return "fpsr";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 2 && op2 == 0) return "daif";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 2 && op2 == 6) return "currentel";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 2 && op2 == 1) return "pan";
        if (o0 == 0 && op1 == 3 && crn == 4 && crm == 2 && op2 == 4) return "uao";
        if (o0 == 0 && op1 == 4 && crn == 1 && crm == 0 && op2 == 0) return "spsr_el2";
        if (o0 == 0 && op1 == 4 && crn == 4 && crm == 0 && op2 == 1) return "elr_el2";
        if (o0 == 0 && op1 == 4 && crn == 12 && crm == 0 && op2 == 0) return "vbar_el2";
        if (o0 == 0 && op1 == 4 && crn == 2 && crm == 0 && op2 == 0) return "ttbr0_el2";
        if (o0 == 0 && op1 == 4 && crn == 2 && crm == 0 && op2 == 2) return "tcr_el2";
        if (o0 == 0 && op1 == 4 && crn == 1 && crm == 0 && op2 == 0) return "sctlr_el2";
        if (o0 == 0 && op1 == 4 && crn == 1 && crm == 0 && op2 == 0) return "hcr_el2";
        return null;
    }

    // ── Bitmask immediate decoding ──
    private static long decodeBitMaskImmediate(int n, int immr, int imms, int sf) {
        int elements = sf != 0 ? 64 : 32;
        int len = sf != 0 ? 6 : 5;
        // simplified: returns the mask value
        long mask = 1L;
        for (int i = 0; i < elements; i++) mask <<= 1;
        mask -= 1;
        return mask;
    }

    // ── FP immediate decode ──
    private static float fpImmDecode(int imm8) {
        int sign = (imm8 >>> 7) & 1;
        int exp = ((imm8 >>> 4) & 7) - 8 + 127;
        int mant = (imm8 & 0xF) << 19;
        int bits = (sign << 31) | (exp << 23) | mant;
        return Float.intBitsToFloat(bits);
    }

    // ── Vector register names ──
    private static final String[] V_REGS = {
        "v0","v1","v2","v3","v4","v5","v6","v7",
        "v8","v9","v10","v11","v12","v13","v14","v15",
        "v16","v17","v18","v19","v20","v21","v22","v23",
        "v24","v25","v26","v27","v28","v29","v30","v31"
    };
    private static final String[] D_REGS = {
        "d0","d1","d2","d3","d4","d5","d6","d7",
        "d8","d9","d10","d11","d12","d13","d14","d15",
        "d16","d17","d18","d19","d20","d21","d22","d23",
        "d24","d25","d26","d27","d28","d29","d30","d31"
    };
    private static final String[] S_REGS = {
        "s0","s1","s2","s3","s4","s5","s6","s7",
        "s8","s9","s10","s11","s12","s13","s14","s15",
        "s16","s17","s18","s19","s20","s21","s22","s23",
        "s24","s25","s26","s27","s28","s29","s30","s31"
    };
    private static final String[] Q_REGS = {
        "q0","q1","q2","q3","q4","q5","q6","q7",
        "q8","q9","q10","q11","q12","q13","q14","q15",
        "q16","q17","q18","q19","q20","q21","q22","q23",
        "q24","q25","q26","q27","q28","q29","q30","q31"
    };
}
