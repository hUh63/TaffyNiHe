package capstone.jni;

import capstone.Capstone;
import capstone.api.Instruction;

public class FastInstruction extends Instruction {

    private final FastDisassembler disassembler;
    private final int id;
    private final long address;
    private final String mnemonic;
    private final String opStr;
    private final byte[] bytes;
    private final InsnDeallocator deallocator;
    private final long insn;

    public FastInstruction(FastDisassembler disassembler, long address, String mnemonic, String opStr, byte[] bytes,
                           InsnDeallocator deallocator, long insn) {
        this(disassembler, 0, address, mnemonic, opStr, bytes, deallocator, insn);
    }

    public FastInstruction(FastDisassembler disassembler, int id, long address, String mnemonic, String opStr, byte[] bytes,
                           InsnDeallocator deallocator, long insn) {
        this.disassembler = disassembler;
        this.id = id;
        this.address = address;
        this.mnemonic = mnemonic;
        this.opStr = opStr;
        this.bytes = bytes;
        this.deallocator = deallocator;
        this.insn = insn;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public long getAddress() {
        return address;
    }

    @Override
    public String getMnemonic() {
        return mnemonic;
    }

    @Override
    public String getOpStr() {
        return opStr;
    }

    @Override
    public short getSize() {
        return (short) bytes.length;
    }

    private boolean operandsTried;
    private Capstone.OpInfo operands;

    @Override
    public Capstone.OpInfo getOperands() {
        if (operandsTried) {
            return operands;
        }
        try {
            operands = disassembler.getOpInfo(insn);
        } finally {
            operandsTried = true;
        }
        return operands;
    }

    @Override
    public byte[] getBytes() {
        return bytes;
    }

    private boolean regsAccessTried;
    private RegsAccess regsAccess;

    @Override
    public capstone.api.RegsAccess regsAccess() {
        if (regsAccessTried) {
            return regsAccess;
        }
        try {
            regsAccess = disassembler.regsAccess(insn);
        } finally {
            regsAccessTried = true;
        }
        return regsAccess;
    }

    @Override
    public String regName(int regId) {
        return disassembler.regName(regId);
    }

    @Override
    public int mapToUnicornReg(int capstoneReg) {
        return disassembler.mapToUnicornReg(capstoneReg);
    }

    @Override
    public int mapToCapstoneReg(int unicornReg) {
        return disassembler.mapToCapstoneReg(unicornReg);
    }
}
