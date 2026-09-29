package capstone.api;

import capstone.Capstone;

public abstract class Instruction {

    public abstract int getId();

    public abstract long getAddress();

    public abstract String getMnemonic();

    public abstract String getOpStr();

    public abstract short getSize();

    public abstract Capstone.OpInfo getOperands();

    public abstract String regName(int regId);

    public abstract int mapToUnicornReg(int capstoneReg);
    public abstract int mapToCapstoneReg(int unicornReg);

    public abstract byte[] getBytes();

    public abstract RegsAccess regsAccess();

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(getMnemonic());
        String opStr = getOpStr();
        if (opStr != null && opStr.trim().length() > 0) {
            builder.append(" ").append(opStr.trim());
        }
        return builder.toString();
    }
}
