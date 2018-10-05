package wavesimulatorjava;

public enum CoThreadMission {
    Pause(1),
    Destroy(2),
    CalculateForces(4),
    MoveParticles(8),
    CalculateColors(16);
    private final int mask;

    private CoThreadMission(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }
}
