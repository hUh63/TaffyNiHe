package capstone.jni.arm;

public class OpInfo implements capstone.api.arm.OpInfo {

    private final boolean userMode;
    private final int vectorSize;
    private final int vectorData;
    private final int cpsMode;
    private final int cpsFlag;
    private final int cc;
    private final boolean updateFlags;
    private final boolean writeBack;
    private final int memBarrier;
    private final Operand[] operands;

    public OpInfo(boolean userMode, int vectorSize, int vectorData, int cpsMode, int cpsFlag, int cc, boolean updateFlags, boolean writeBack, int memBarrier, Operand[] operands) {
        this.userMode = userMode;
        this.vectorSize = vectorSize;
        this.vectorData = vectorData;
        this.cpsMode = cpsMode;
        this.cpsFlag = cpsFlag;
        this.cc = cc;
        this.updateFlags = updateFlags;
        this.writeBack = writeBack;
        this.memBarrier = memBarrier;
        this.operands = operands;
    }

    @Override
    public boolean isWriteBack() {
        return writeBack;
    }

    @Override
    public Operand[] getOperands() {
        return operands;
    }

    @Override
    public boolean isUpdateFlags() {
        return updateFlags;
    }

    @Override
    public int getCpsMode() {
        return cpsMode;
    }

    @Override
    public int getCpsFlag() {
        return cpsFlag;
    }

    @Override
    public int getVectorData() {
        return vectorData;
    }

    @Override
    public int getVectorSize() {
        return vectorSize;
    }

    @Override
    public boolean isUserMode() {
        return userMode;
    }

    @Override
    public int getCodeCondition() {
        return cc;
    }

}
