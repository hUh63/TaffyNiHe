package capstone.jni;

public class RegsAccess implements capstone.api.RegsAccess {

    private final short[] regsRead;
    private final short[] regsWrite;

    public RegsAccess(short[] regsRead, short[] regsWrite) {
        this.regsRead = regsRead;
        this.regsWrite = regsWrite;
    }

    @Override
    public short[] getRegsRead() {
        return regsRead;
    }

    @Override
    public short[] getRegsWrite() {
        return regsWrite;
    }

}
