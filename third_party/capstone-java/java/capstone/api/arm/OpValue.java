package capstone.api.arm;

public interface OpValue {

    int getReg();

    int getImm();

    int getSetEnd();

    double getFp();

    MemType getMem();

}
