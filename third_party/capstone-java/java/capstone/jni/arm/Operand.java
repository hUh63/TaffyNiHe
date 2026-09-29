package capstone.jni.arm;


import capstone.jni.OpShift;

public class Operand implements capstone.api.arm.Operand {

    private final int vectorIndex;
    private final OpShift shift;
    private final int type;
    private final OpValue value;
    private final boolean subtracted;

    public Operand(int vectorIndex, OpShift shift, int type, OpValue value, boolean subtracted) {
        this.vectorIndex = vectorIndex;
        this.shift = shift;
        this.type = type;
        this.value = value;
        this.subtracted = subtracted;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public OpValue getValue() {
        return value;
    }

    @Override
    public int getVectorIndex() {
        return vectorIndex;
    }

    @Override
    public boolean isSubtracted() {
        return subtracted;
    }

    @Override
    public OpShift getShift() {
        return shift;
    }

}
