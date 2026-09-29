package capstone.jni.arm64;

import capstone.jni.OpShift;

public class Operand implements capstone.api.arm64.Operand {

    private final int vectorIndex;
    private final int vas;
    private final int vess;
    private final OpShift shift;
    private final int ext;
    private final int type;
    private final OpValue value;
    private final byte access;

    public Operand(int vectorIndex, int vas, int vess, OpShift shift, int ext, int type, OpValue value, byte access) {
        this.vectorIndex = vectorIndex;
        this.vas = vas;
        this.vess = vess;
        this.shift = shift;
        this.ext = ext;
        this.type = type;
        this.value = value;
        this.access = access;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public int getExt() {
        return ext;
    }

    @Override
    public int getVas() {
        return vas;
    }

    @Override
    public int getVess() {
        return vess;
    }

    @Override
    public int getVectorIndex() {
        return vectorIndex;
    }

    @Override
    public OpValue getValue() {
        return value;
    }

    @Override
    public OpShift getShift() {
        return shift;
    }
}
