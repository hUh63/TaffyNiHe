package capstone.jni.arm;

public class MemType implements capstone.api.arm.MemType {

    private final int base;
    private final int index;
    private final int scale;
    private final int disp;
    private final int lshift;

    public MemType(int base, int index, int scale, int disp, int lshift) {
        this.base = base;
        this.index = index;
        this.scale = scale;
        this.disp = disp;
        this.lshift = lshift;
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
    public int getScale() {
        return scale;
    }

    @Override
    public int getDisp() {
        return disp;
    }

    @Override
    public int getLshift() {
        return lshift;
    }

}
