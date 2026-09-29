package capstone.api.arm;

import capstone.api.OpShift;

public interface Operand {

    int getType();

    OpValue getValue();

    int getVectorIndex();

    boolean isSubtracted();

    OpShift getShift();

}
