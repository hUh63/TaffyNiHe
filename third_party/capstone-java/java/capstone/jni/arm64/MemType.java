package capstone.jni.arm64;

public class MemType implements capstone.api.arm64.MemType {

    private final int base;
    private final int index;
    private final int disp;

    public MemType(int base, int index, int disp) {
        this.base = base;
        this.index = index;
        this.disp = disp;
    }

    @Override
    public int getBase() {
        return base;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @Override
    public int getDisp() {
        return disp;
    }
}
