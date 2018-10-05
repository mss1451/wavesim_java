package wavesimulatorjava;

public class Color {

    public byte r, g, b;

    public Color(byte r, byte g, byte b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public Color(int rgb32) {
        r = (byte) (rgb32);
        g = (byte) (rgb32 >> 8);
        b = (byte) (rgb32 >> 16);
    }

    int ToRGB32() {
        return r + (g << 8) + (b << 16);
    }

    Color() {
        this.r = 0;
        this.g = 0;
        this.b = 0;
    }
}
