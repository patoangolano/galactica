package dev.galactica.science;

/**
 * Deterministic, allocation-free RNG (SplitMix64). Every procedural object derives its stream from
 * a hash of its parent seed and its own coordinates, so the galaxy is identical on every machine
 * without storing it.
 */
public final class SeedRng {
    private long state;

    public SeedRng(long seed) {
        this.state = seed;
    }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Order-sensitive hash of a seed and any number of integer keys. */
    public static long hash(long seed, long... keys) {
        long h = mix(seed + 0x9E3779B97F4A7C15L);
        for (long k : keys) {
            h = mix(h ^ (k + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2)));
        }
        return h;
    }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        return mix(state);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in (0, 1], safe for logarithms. */
    public double nextDoubleOpen() {
        return ((nextLong() >>> 11) + 1) * 0x1.0p-53;
    }

    public double range(double lo, double hi) {
        return lo + (hi - lo) * nextDouble();
    }

    public int nextInt(int bound) {
        return (int) ((nextLong() >>> 33) % bound);
    }

    public double gaussian() {
        // Box–Muller; the second value is discarded to keep the stream stateless between calls.
        double u = nextDoubleOpen();
        double v = nextDouble();
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
    }

    public double logUniform(double lo, double hi) {
        return Math.exp(range(Math.log(lo), Math.log(hi)));
    }

    /** Poisson sample; Knuth for small means, normal approximation above 64. */
    public int poisson(double mean) {
        if (mean <= 0) return 0;
        if (mean > 64) {
            return Math.max(0, (int) Math.round(mean + Math.sqrt(mean) * gaussian()));
        }
        double l = Math.exp(-mean);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= nextDouble();
        } while (p > l);
        return k - 1;
    }
}
