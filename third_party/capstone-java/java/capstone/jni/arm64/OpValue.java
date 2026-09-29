package capstone.jni.arm64;

public class OpValue implements capstone.api.arm64.OpValue {

    private final int reg;
    private final long imm;
    private final double fp;
    private final MemType mem;
    private final int pstate;
    private final int sys;
    private final int prefetch;
    private final int barrier;

    public OpValue(int reg, long imm, double fp, MemType mem, int pstate, int sys, int prefetch, int barrier) {
        this.reg = reg;
        this.imm = imm;
        this.fp = fp;
        this.mem = mem;
        this.pstate = pstate;
        this.sys = sys;
        this.prefetch = prefetch;
        this.barrier = barrier;
    }

    @Override
    public int getReg() {
        return reg;
    }

    @Override
    public long getImm() {
        return imm;
    }

    @Override
    public double getFp() {
        return fp;
    }

    @Override
    public capstone.api.arm64.MemType getMem() {
        return mem;
    }
}
