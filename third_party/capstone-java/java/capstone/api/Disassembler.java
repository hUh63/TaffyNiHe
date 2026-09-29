package capstone.api;

import java.io.Closeable;

public interface Disassembler extends Closeable {

    Instruction[] disasm(byte[] code, long address);

    Instruction[] disasm(byte[] code, long address, long count);

    void setDetail(boolean on);

}
