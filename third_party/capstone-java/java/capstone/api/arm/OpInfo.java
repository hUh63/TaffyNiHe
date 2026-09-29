package capstone.api.arm;

import capstone.Capstone;

public interface OpInfo extends Capstone.OpInfo {

    boolean isWriteBack();

    Operand[] getOperands();

    boolean isUpdateFlags();

    int getCpsMode();

    int getCpsFlag();

    int getVectorData();

    int getVectorSize();

    boolean isUserMode();

    int getCodeCondition();

}
