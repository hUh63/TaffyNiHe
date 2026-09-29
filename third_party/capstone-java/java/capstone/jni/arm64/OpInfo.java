package capstone.jni.arm64;

public class OpInfo implements capstone.api.arm64.OpInfo {

    private final int cc;
    private final boolean updateFlags;
    private final boolean writeBack;
    private final Operand[] operands;

    public OpInfo(int cc, boolean updateFlags, boolean writeBack, Operand[] operands) {
        this.cc = cc;
        this.updateFlags = updateFlags;
        this.writeBack = writeBack;
        this.operands = operands;
    }

    @Override
    public boolean isWriteBack() {
        return writeBack;
    }

    @Override
    public boolean isUpdateFlags() {
        return updateFlags;
    }

    @Override
    public int getCodeCondition() {
        return cc;
    }

    @Override
    public Operand[] getOperands() {
        return operands;
    }
}
