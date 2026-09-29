package capstone.jni.arm;

public class OpValue implements capstone.api.arm.OpValue {

    private final int reg;
    private final int imm;
    private final double fp;
    private final MemType mem;
    private final int setEnd;

    public OpValue(int reg, int imm, double fp, MemType mem, int setEnd) {
        this.reg = reg;
        this.imm = imm;
        this.fp = fp;
        this.mem = mem;
        this.setEnd = setEnd;
    }

    @Override
    public int getReg() {
        return reg;
    }

    @Override
    public int getImm() {
        return imm;
    }

    @Override
    public int getSetEnd() {
        return setEnd;
    }

    @Override
    public double getFp() {
        return fp;
    }

    @Override
    public MemType getMem() {
        return mem;
    }
}
