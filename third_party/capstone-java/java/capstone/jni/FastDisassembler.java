package capstone.jni;

import capstone.Capstone;
import capstone.api.Disassembler;
import capstone.api.Instruction;


public class FastDisassembler implements Disassembler {


    private static native long nativeInitialize(boolean is64Bit, int mode);
    private static native int nativeDestroy(long handle);
    private static native int setDetail(long handle, boolean on);
    private native FastInstruction[] disasm(long handle, byte[] code, long address, long count);
    private static native String regName(long handle, int regId);
    static native void cs_free(long insn, long count);
    private static native RegsAccess regsAccess(long handle, long insn);
    private static native Capstone.OpInfo getOpInfo(long handle, long insn);
    private static native int mapToUnicornReg(long handle, int capstoneReg);
    private static native int mapToCapstoneReg(long handle, int unicornReg);

    final RegsAccess regsAccess(long insn) {
        return regsAccess(nativeHandle, insn);
    }

    final Capstone.OpInfo getOpInfo(long insn) {
        return getOpInfo(nativeHandle, insn);
    }

    final String regName(int regId) {
        return regName(nativeHandle, regId);
    }

    private final long nativeHandle;

    public FastDisassembler(int arch, int mode) {
        final boolean is64Bit;
        if (arch == Capstone.CS_ARCH_ARM) {
            is64Bit = false;
        } else if (arch == Capstone.CS_ARCH_ARM64) {
            is64Bit = true;
        } else {
            throw new UnsupportedOperationException("arch=" + arch);
        }

        this.nativeHandle = nativeInitialize(is64Bit, mode);

        if (this.nativeHandle == 0) {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Instruction[] disasm(byte[] code, long address) {
        return disasm(code, address, 0);
    }

    @Override
    public Instruction[] disasm(byte[] code, long address, long count) {
        return disasm(nativeHandle, code, address, count);
    }

    final int mapToUnicornReg(int capstoneReg) {
        return mapToUnicornReg(nativeHandle, capstoneReg);
    }

    final int mapToCapstoneReg(int unicornReg) {
        return mapToCapstoneReg(nativeHandle, unicornReg);
    }

    @Override
    public void setDetail(boolean on) {
        if (setDetail(nativeHandle, on) != 0) {
            throw new IllegalStateException("ERROR: Failed to set detail option");
        }
    }

    private boolean closed;

    @Override
    public void close() {
        if (nativeHandle != 0 && !closed) {
            try {
                if (nativeDestroy(nativeHandle) != 0) {
                    throw new IllegalStateException("ERROR: Failed to call cs_close");
                }
            } finally {
                closed = true;
            }
        }
    }

}
