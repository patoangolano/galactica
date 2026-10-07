package dev.galactica.perf;

import java.util.concurrent.atomic.LongAdder;

/**
 * Development-only CPU and GPU-command accounting for the performance benchmark
 * ({@code -Dgalactica.autotest.perf=true}). Every hook is one volatile read when the benchmark is off:
 * {@link #t0()} returns 0 and {@link #add} ignores a 0 start, so normal play pays nothing measurable.
 *
 * <p>Sections are inclusive wall-clock time on whatever thread runs them (the render thread for client
 * sections, the server thread for server ones, the chunk workers for terrain), summed with
 * {@link LongAdder}s so worker threads can report too. Nested sections are each counted in full.
 */
public final class Perf {
    private Perf() {}

    /** True only while a benchmark window is open. */
    public static volatile boolean on;

    public enum S {
        // Client, render thread.
        SKY_MANAGER, SPACE_SKY, SPACE_NEAR, SPACE_DRAW, SURFACE_SKY, FAR_TERRAIN, EARTH_SKY, NEAR_PLANET, CLIPMAP,
        STAR_DISC, NEBULA, EXOTIC, PLANET_SPRITES, SHIP_EXTRACT, SHIP_SUBMIT, COCKPIT, HUD, MAP, TRANSFER_FX,
        // Server thread.
        SERVER_TICK, NAV_SERVER, PLANET_ENTRY, FLOATING_ORIGIN, STARSHIP_TICK, LIFE_SUPPORT,
        // Worker threads.
        PLANET_CHUNK, DH_LOD, SKY_BAKE, STARFIELD
    }

    private static final S[] ALL = S.values();
    private static final LongAdder[] NANOS = new LongAdder[ALL.length], CALLS = new LongAdder[ALL.length];

    static {
        for (int i = 0; i < ALL.length; i++) {
            NANOS[i] = new LongAdder();
            CALLS[i] = new LongAdder();
        }
    }

    /** GPU command counters (renderpearl frontend), render thread only. */
    public static long draws, bufferWrites, bufferWriteBytes, textureWrites, textureWriteBytes, buffersCreated,
            bufferCreateBytes, texturesCreated, renderPasses;

    public static long t0() {
        return on ? System.nanoTime() : 0L;
    }

    public static void add(S s, long t0) {
        if (t0 == 0L) return;
        NANOS[s.ordinal()].add(System.nanoTime() - t0);
        CALLS[s.ordinal()].increment();
    }

    public static void reset() {
        for (int i = 0; i < ALL.length; i++) {
            NANOS[i].reset();
            CALLS[i].reset();
        }
        draws = bufferWrites = bufferWriteBytes = textureWrites = textureWriteBytes = buffersCreated = bufferCreateBytes
                = texturesCreated = renderPasses = 0;
    }

    public static long nanos(S s) {
        return NANOS[s.ordinal()].sum();
    }

    public static long calls(S s) {
        return CALLS[s.ordinal()].sum();
    }

    public static S[] sections() {
        return ALL;
    }
}
