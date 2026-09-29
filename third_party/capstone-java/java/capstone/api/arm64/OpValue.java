package capstone.api.arm64;

public interface OpValue {

    int getReg();

    long getImm();

    double getFp();

    MemType getMem();

}
