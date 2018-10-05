package wavesimulatorjava;

public enum ParticleAttribute {
    Height(1), Velocity(2), Loss(4), Mass(8), Fixity(16);
    private final int mask;

    private ParticleAttribute(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }
}
