package dev.galactica.deepsky;

import dev.galactica.science.SeedRng;

/**
 * Fast seeded 3D gradient noise for the volumetric nebula fields ({@link NebulaField}).
 *
 * <p>Improved Perlin noise (Perlin 2002, SIGGRAPH "Improving Noise": quintic fade, 12 edge
 * gradients) over a 256-entry permutation, about 4× cheaper than the hash-per-corner value noise of
 * {@code science.Noise}: a nebula march evaluates it tens of millions of times per bake.
 *
 * <p>Fractal sums rotate the lattice between octaves (a fixed irrational rotation) so the grid axes
 * never line up across scales, and accept a fractional octave count: the last octave fades in
 * linearly, so a level-of-detail cut-off that moves with distance never pops.
 */
public final class NebulaNoise {
    private final int[] perm = new int[512];
    /** Per-octave offsets, so octaves are decorrelated even where the rotation leaves a point fixed. */
    private final double[] ox = new double[16], oy = new double[16], oz = new double[16];

    /** Rotation applied between octaves (axis (1, 2, 3)/√14, angle 1 rad), times the lacunarity. */
    private static final double[][] ROT;
    public static final double LACUNARITY = 2.03;

    static {
        double[] a = {1 / Math.sqrt(14), 2 / Math.sqrt(14), 3 / Math.sqrt(14)};
        double c = Math.cos(1.0), s = Math.sin(1.0), t = 1 - c;
        ROT = new double[][] {
                {t * a[0] * a[0] + c, t * a[0] * a[1] - s * a[2], t * a[0] * a[2] + s * a[1]},
                {t * a[0] * a[1] + s * a[2], t * a[1] * a[1] + c, t * a[1] * a[2] - s * a[0]},
                {t * a[0] * a[2] - s * a[1], t * a[1] * a[2] + s * a[0], t * a[2] * a[2] + c}};
        for (double[] row : ROT) for (int j = 0; j < 3; j++) row[j] *= LACUNARITY;
    }

    private static final int RIDGE_OCTAVES = 10;
    /** 1 / Σ 2^-k over the ridge octaves: a full-detail crest is 1. */
    private static final double RIDGE_NORM = 0.5 / (1 - Math.pow(0.5, RIDGE_OCTAVES));
    /** Mean contribution of each ridge octave (with the weights of the octaves above it). */
    private final double[] ridgeMean = new double[RIDGE_OCTAVES];

    private static final NebulaNoise[] SHARED = new NebulaNoise[8];

    /**
     * One of eight shared lattices, picked by {@code seed}. Fields differ by where they sample the
     * lattice ({@link #offset}), so hundreds of procedural nebulae need no lattice of their own.
     */
    public static NebulaNoise shared(long seed) {
        int k = (int) (SeedRng.mix(seed ^ 0x5EEDL) & 7);
        NebulaNoise n = SHARED[k];
        if (n == null) {
            synchronized (SHARED) {
                n = SHARED[k];
                if (n == null) {
                    n = new NebulaNoise(0xA11CE + k);
                    SHARED[k] = n;
                }
            }
        }
        return n;
    }

    /** A seed-dependent lattice offset in [−64, 64)³, to decorrelate fields sharing a lattice. */
    public static double[] offset(long seed) {
        SeedRng r = new SeedRng(SeedRng.hash(seed, 0x0FF5L));
        return new double[] {r.range(-64, 64), r.range(-64, 64), r.range(-64, 64)};
    }

    public NebulaNoise(long seed) {
        SeedRng rng = new SeedRng(SeedRng.hash(seed, 0x9E81AL));
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        for (int i = 0; i < 16; i++) {
            ox[i] = rng.range(-100, 100);
            oy[i] = rng.range(-100, 100);
            oz[i] = rng.range(-100, 100);
        }
        // Mean per-octave ridge signal, measured on this lattice (4 096 random points).
        double[] acc = new double[RIDGE_OCTAVES];
        int n = 4096;
        for (int k = 0; k < n; k++) {
            double x = rng.range(0, 256), y = rng.range(0, 256), z = rng.range(0, 256), weight = 1;
            for (int i = 0; i < RIDGE_OCTAVES; i++) {
                double r = Math.max(0, 1 - Math.abs(noise(x + ox[i], y + oy[i], z + oz[i]) * 1.4));
                r *= r;
                acc[i] += r * weight;
                weight = Math.min(1, r * 1.6);
                double nx = ROT[0][0] * x + ROT[0][1] * y + ROT[0][2] * z;
                double ny = ROT[1][0] * x + ROT[1][1] * y + ROT[1][2] * z;
                double nz = ROT[2][0] * x + ROT[2][1] * y + ROT[2][2] * z;
                x = nx;
                y = ny;
                z = nz;
            }
        }
        for (int i = 0; i < RIDGE_OCTAVES; i++) ridgeMean[i] = acc[i] / n;
    }

    /** Gradient noise in about [−1, 1] (|value| &lt; 1 everywhere), zero on the integer lattice. */
    public double noise(double x, double y, double z) {
        double fx0 = Math.floor(x), fy0 = Math.floor(y), fz0 = Math.floor(z);
        int xi = (int) fx0 & 255, yi = (int) fy0 & 255, zi = (int) fz0 & 255;
        double xf = x - fx0, yf = y - fy0, zf = z - fz0;
        double u = fade(xf), v = fade(yf), w = fade(zf);
        int[] p = perm;
        int a = p[xi] + yi, aa = p[a] + zi, ab = p[a + 1] + zi;
        int b = p[xi + 1] + yi, ba = p[b] + zi, bb = p[b + 1] + zi;
        double x1 = lerp(u, grad(p[aa], xf, yf, zf), grad(p[ba], xf - 1, yf, zf));
        double x2 = lerp(u, grad(p[ab], xf, yf - 1, zf), grad(p[bb], xf - 1, yf - 1, zf));
        double y1 = lerp(v, x1, x2);
        x1 = lerp(u, grad(p[aa + 1], xf, yf, zf - 1), grad(p[ba + 1], xf - 1, yf, zf - 1));
        x2 = lerp(u, grad(p[ab + 1], xf, yf - 1, zf - 1), grad(p[bb + 1], xf - 1, yf - 1, zf - 1));
        double y2 = lerp(v, x1, x2);
        return lerp(w, y1, y2);
    }

    /**
     * Fractal Brownian motion, gain ½ per octave, in about [−1, 1]. {@code octaves} may be
     * fractional: the last octave fades in, so a level-of-detail cut-off never pops. The
     * normalisation does not depend on the octave count (missing octaves are replaced by their mean,
     * zero), so a band-limited far view has the same mean as the full-detail close view.
     */
    public double fbm(double x, double y, double z, double octaves) {
        double sum = 0, amp = 1;
        int whole = (int) Math.min(15, Math.floor(octaves));
        double frac = Math.min(1, Math.max(0, octaves - whole));
        for (int i = 0; i <= whole && i < 16; i++) {
            double wgt = i < whole ? 1 : frac;
            if (wgt <= 0) break;
            sum += amp * wgt * noise(x + ox[i], y + oy[i], z + oz[i]);
            double nx = ROT[0][0] * x + ROT[0][1] * y + ROT[0][2] * z;
            double ny = ROT[1][0] * x + ROT[1][1] * y + ROT[1][2] * z;
            double nz = ROT[2][0] * x + ROT[2][1] * y + ROT[2][2] * z;
            x = nx;
            y = ny;
            z = nz;
            amp *= 0.5;
        }
        return sum * 0.8;
    }

    /**
     * Ridged multifractal (Musgrave 1994): sharp crests where the noise crosses zero, the shape of
     * filaments and wrinkled sheets in molecular clouds and shock fronts. In [0, 1]. Octaves beyond
     * the (fractional) count are replaced by their mean contribution, so the mean density, and with
     * it the light and dust a field holds, does not change with level of detail.
     */
    public double ridged(double x, double y, double z, double octaves) {
        double sum = 0, amp = 1, weight = 1;
        int whole = (int) Math.min(15, Math.floor(octaves));
        double frac = Math.min(1, Math.max(0, octaves - whole));
        int i = 0;
        for (; i <= whole && i < RIDGE_OCTAVES; i++) {
            double wgt = i < whole ? 1 : frac;
            if (wgt <= 0) break;
            double r = 1 - Math.abs(noise(x + ox[i], y + oy[i], z + oz[i]) * 1.4);
            r = Math.max(0, r);
            r *= r;
            double sig = r * weight;
            sum += amp * (wgt * sig + (1 - wgt) * ridgeMean[i]);
            weight = Math.min(1, r * 1.6);
            double nx = ROT[0][0] * x + ROT[0][1] * y + ROT[0][2] * z;
            double ny = ROT[1][0] * x + ROT[1][1] * y + ROT[1][2] * z;
            double nz = ROT[2][0] * x + ROT[2][1] * y + ROT[2][2] * z;
            x = nx;
            y = ny;
            z = nz;
            amp *= 0.5;
            if (wgt < 1) {
                i++;
                break;
            }
        }
        for (; i < RIDGE_OCTAVES; i++) {
            sum += amp * ridgeMean[i];
            amp *= 0.5;
        }
        return Math.min(1, sum * RIDGE_NORM);
    }

    /**
     * Octave count that resolves features down to {@code finestPc} (pc) for a base wavelength
     * {@code basePc}: octave k has wavelength basePc / LACUNARITY^k, and an octave is kept while its
     * wavelength exceeds two LOD lengths (Nyquist), with a one-octave fade.
     */
    public static double octavesFor(double basePc, double lodPc, double max) {
        if (lodPc <= 0) return max;
        double o = Math.log(basePc / (2 * lodPc)) / Math.log(LACUNARITY) + 1;
        return Math.max(0.0, Math.min(max, o));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** Dot product with one of the 12 edge gradients (Perlin's reference bit selection, no table load). */
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
