package dev.galactica.client.vfx;

/**
 * Animation clock for procedural shaders, independent of game ticks, pausing and {@code /tick}.
 *
 * <p>The value goes to the GPU as a 32-bit float. A float has a 24-bit mantissa, so an unwrapped
 * seconds counter loses millisecond resolution after ~4.6 hours and animations start to stutter.
 * Wrapping at {@link #PERIOD_S} keeps the step below 0.25 ms. Shaders must treat a jump back to 0
 * once per period as acceptable (all Galactica effects are noise-driven, so it reads as a reseed).
 */
public final class VfxClock {
    private VfxClock() {}

    /** Wrap period in seconds: one hour, a multiple of every periodic animation in the shaders. */
    public static final double PERIOD_S = 3600.0;

    private static final long START = System.nanoTime();

    /** Seconds since client start, wrapped to [0, {@link #PERIOD_S}). */
    public static float seconds() {
        return wrap(System.nanoTime() - START);
    }

    /** Wraps an elapsed time in nanoseconds (may be negative) to [0, PERIOD_S) seconds. */
    public static float wrap(long elapsedNanos) {
        long periodNs = (long) (PERIOD_S * 1e9);
        long r = Math.floorMod(elapsedNanos, periodNs);
        return (float) (r / 1e9);
    }
}
