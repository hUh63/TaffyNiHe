package capstone.api.arm64;

import capstone.api.OpShift;

public interface Operand {

    int getType();

    int getExt();

    int getVas();

    int getVess();

    int getVectorIndex();

    OpValue getValue();

    OpShift getShift();

}
