package capstone.api.arm64;

import capstone.Capstone;

public interface OpInfo extends Capstone.OpInfo {

    boolean isWriteBack();

    boolean isUpdateFlags();

    int getCodeCondition();

    Operand[] getOperands();

}
