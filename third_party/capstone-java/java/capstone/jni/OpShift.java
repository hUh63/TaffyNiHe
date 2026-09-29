package capstone.jni;

public class OpShift implements capstone.api.OpShift {

    private final int type;
    private final int value;

    public OpShift(int type, int value) {
        this.type = type;
        this.value = value;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public int getValue() {
        return value;
    }
}
