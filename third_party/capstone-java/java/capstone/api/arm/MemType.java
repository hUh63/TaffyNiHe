package capstone.api.arm;

public interface MemType {

    int getBase();

    int getIndex();

    int getScale();

    int getDisp();

    int getLshift();

}
