package dev.galactica.science;

/** Seeded 3D value noise and fBm, used for dust filaments and nebula structure. */
public final class Noise {
    private Noise() {}

    private static double lattice(long seed, long x, long y, long z) {
        long h = SeedRng.mix(seed ^ (x * 0x8DA6B343L) ^ (y * 0xD8163841L) ^ (z * 0xCB1AB31FL));
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    public static double value(long seed, double x, double y, double z) {
        long x0 = (long) Math.floor(x), y0 = (long) Math.floor(y), z0 = (long) Math.floor(z);
        double fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);
        double c000 = lattice(seed, x0, y0, z0), c100 = lattice(seed, x0 + 1, y0, z0);
        double c010 = lattice(seed, x0, y0 + 1, z0), c110 = lattice(seed, x0 + 1, y0 + 1, z0);
        double c001 = lattice(seed, x0, y0, z0 + 1), c101 = lattice(seed, x0 + 1, y0, z0 + 1);
        double c011 = lattice(seed, x0, y0 + 1, z0 + 1), c111 = lattice(seed, x0 + 1, y0 + 1, z0 + 1);
        double x00 = lerp(fx, c000, c100), x10 = lerp(fx, c010, c110);
        double x01 = lerp(fx, c001, c101), x11 = lerp(fx, c011, c111);
        return lerp(fz, lerp(fy, x00, x10), lerp(fy, x01, x11));
    }

    /** Fractal sum in [-1, 1] approximately. */
    public static double fbm(long seed, double x, double y, double z, int octaves) {
        double sum = 0, amp = 0.5, freq = 1, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * value(seed + i * 31L, x * freq, y * freq, z * freq);
            norm += amp;
            amp *= 0.5;
            freq *= 2.03;
        }
        return sum / norm;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
