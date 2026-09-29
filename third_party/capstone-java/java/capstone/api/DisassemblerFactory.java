package capstone.api;

import capstone.Capstone;
import capstone.jni.FastDisassembler;

public class DisassemblerFactory {

    public static Disassembler createDisassembler(int arch, int mode) {
        try {
            return new FastDisassembler(arch, mode);
        } catch (UnsatisfiedLinkError e) {
            return new Capstone(arch, mode);
        }
    }

    public static Disassembler createArmDisassembler(boolean thumb) {
        return createDisassembler(Capstone.CS_ARCH_ARM, thumb ? Capstone.CS_MODE_THUMB : Capstone.CS_MODE_ARM);
    }

    public static Disassembler createArm64Disassembler() {
        return createDisassembler(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM);
    }

}
