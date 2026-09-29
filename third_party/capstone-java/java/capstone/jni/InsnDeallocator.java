package capstone.jni;

class InsnDeallocator {

    private final long insn;
    private final long count;

    InsnDeallocator(long insn, long count) {
        this.insn = insn;
        this.count = count;
    }

    @Override
    protected void finalize() {
        FastDisassembler.cs_free(insn, count);
    }

}
