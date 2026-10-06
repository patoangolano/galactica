package dev.galactica.client.vfx;

/**
 * Animation clock for procedural shaders, independent of game ticks, pausing and {@code /tick}.
 *
 * <p>The value goes to the GPU as a 32-bit float. A float has a 24-bit mantissa, so an unwrapped
 * seconds counter loses millisecond resolution after ~4.6 hours and animations start to stutter.
 * Wrapping at {@link #PERIOD_S} keeps the step below 0.25 ms. The wrap is NOT a multiple of the
 * shaders' animation rates, so the wrapped time may only drive effects that tolerate a jump once
 * per period (noise drift, which reads as a reseed). Motions whose discontinuity would show
 * (rotation, cell life cycles) are turned into phases on the CPU from {@link #elapsedSeconds()} in
 * double precision and wrapped by their own period, as {@code StarUniforms} does.
 */
public final class VfxClock {
    private VfxClock() {}

    /** Wrap period of {@link #seconds()}, s (one hour). */
    public static final double PERIOD_S = 3600.0;

    private static final long START = System.nanoTime();

    /** Seconds since client start, wrapped to [0, {@link #PERIOD_S}). */
    public static float seconds() {
        return wrap(System.nanoTime() - START);
    }

    /** Seconds since client start, unwrapped, in double (sub-microsecond resolution for centuries). */
    public static double elapsedSeconds() {
        return (System.nanoTime() - START) / 1e9;
    }

    /** Wraps an elapsed time in nanoseconds (may be negative) to [0, PERIOD_S) seconds. */
    public static float wrap(long elapsedNanos) {
        long periodNs = (long) (PERIOD_S * 1e9);
        long r = Math.floorMod(elapsedNanos, periodNs);
        return (float) (r / 1e9);
    }

    /** Wraps elapsed seconds (may be negative) to [0, PERIOD_S). */
    public static float wrapSeconds(double elapsedSeconds) {
        double r = elapsedSeconds - PERIOD_S * Math.floor(elapsedSeconds / PERIOD_S);
        float f = (float) r;
        return f >= (float) PERIOD_S ? 0f : f;
    }
}
