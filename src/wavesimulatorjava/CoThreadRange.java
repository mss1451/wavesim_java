package wavesimulatorjava;

public class CoThreadRange {

    public int tIndex, firstIndex, count;

    public CoThreadRange(int xtIndex, int xfirstIndex, int xcount) {
        tIndex = xtIndex;
        firstIndex = xfirstIndex;
        count = xcount;
    }

    public CoThreadRange() {
        tIndex = 0;
        firstIndex = 0;
        count = 0;
    }
}
