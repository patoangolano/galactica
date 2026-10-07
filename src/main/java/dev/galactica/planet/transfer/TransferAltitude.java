package dev.galactica.planet.transfer;

/**
 * One altitude scale on both sides of the hand-off. A surface block row Y sits at
 * (Y − sea level) × metres-per-block above the datum, the same scale the map uses horizontally
 * ({@code SurfaceGrid.metresPerBlock()}), so the space side triggers the switch at exactly the height the
 * surface side places you, and taking off returns you to the same radial at the same height.
 *
 * <p>The switch rows are above every build limit (surface slots top out at Y 1279, the vanilla Overworld at 319):
 * {@code LevelLoadTracker.WaitingForPlayerChunk.isReady()} returns true at once when the camera is
 * outside build height (verified on 26.3), so the client never waits for chunks at the switch. A level taller than
 * that (a Tectonic Overworld configured up to Y 2031) moves its rows up with it: {@link #entryY(int)} keeps the
 * whole 64-block arrival band above the build limit and every terrain block more than 100 blocks below the entry
 * row (the touchdown test), and {@link #exitY(int)} stays {@link #EXIT_ABOVE_ENTRY} above it.
 * Earth's switch (Overworld, sea level {@value #OVERWORLD_SEA_LEVEL}) is then (1500 − 63) × ≈10.0 m ≈ 14.4 km,
 * below the stratopause where the plasma has died down; Mars ≈ 7.5 km, the Moon ≈ 3.8 km.
 *
 * <p><b>Re-entry after a take-off.</b> The take-off altitude ({@link #EXIT_Y}) already sits above the switch
 * altitude ({@link #ENTRY_Y}), and the server adds a short time cooldown, so a craft cannot bounce straight back.
 * Beyond that, re-entry into the body just left is held off only while the craft is still <i>climbing</i> and
 * below {@link #REARM_FACTOR} × the switch altitude ({@link #reentryBlocked}); a craft that turns back down
 * re-enters normally, at any height.
 */
public final class TransferAltitude {
    private TransferAltitude() {}

    /** Surface row where an entry arrives (and the space-side trigger altitude it corresponds to). */
    public static final double ENTRY_Y = 1500;
    /** Surface row above which a craft takes off to space. */
    public static final double EXIT_Y = 1700;
    /** Take-off row above the entry row, blocks (EXIT_Y − ENTRY_Y). */
    public static final double EXIT_ABOVE_ENTRY = EXIT_Y - ENTRY_Y;
    /** Clearance of the entry row above a level's top block: the 64-block arrival band plus the 100-block touchdown margin. */
    public static final int ENTRY_CLEARANCE = 128;
    /** Re-entry is armed only above this multiple of the switch altitude. */
    public static final double REARM_FACTOR = 1.3;
    /** Sea level of the Overworld, where Earth of Sol is landed. */
    public static final int OVERWORLD_SEA_LEVEL = 63;

    /**
     * Entry row of a level whose highest block is {@code levelMaxY}: {@link #ENTRY_Y}, or higher when the level
     * reaches within {@link #ENTRY_CLEARANCE} blocks of it.
     */
    public static double entryY(int levelMaxY) {
        return Math.max(ENTRY_Y, levelMaxY + ENTRY_CLEARANCE);
    }

    /** Take-off row of a level whose highest block is {@code levelMaxY}. */
    public static double exitY(int levelMaxY) {
        return entryY(levelMaxY) + EXIT_ABOVE_ENTRY;
    }

    /** Altitude of a switch at entry row {@code entryY}, metres. */
    public static double switchAltitude(double entryY, double seaLevel, double metresPerBlock) {
        return realAltitude(entryY, seaLevel, metresPerBlock);
    }

    /** Physical height above the datum of surface row y, metres. */
    public static double realAltitude(double y, double seaLevel, double metresPerBlock) {
        return (y - seaLevel) * metresPerBlock;
    }

    /** Surface row of a physical altitude. Inverse of {@link #realAltitude}. */
    public static double blockY(double altitudeMetres, double seaLevel, double metresPerBlock) {
        return seaLevel + altitudeMetres / metresPerBlock;
    }

    /** Altitude of the switch: where {@link #ENTRY_Y} sits, metres. */
    public static double switchAltitude(double seaLevel, double metresPerBlock) {
        return realAltitude(ENTRY_Y, seaLevel, metresPerBlock);
    }

    /** Altitude of a take-off: where {@link #EXIT_Y} sits, metres. */
    public static double exitAltitude(double seaLevel, double metresPerBlock) {
        return realAltitude(EXIT_Y, seaLevel, metresPerBlock);
    }

    /** True once a craft is high enough above the switch for a new entry to trigger. */
    public static boolean reentryArmed(double altitudeMetres, double switchAltitude) {
        return altitudeMetres > REARM_FACTOR * switchAltitude;
    }

    /**
     * True while a craft that just took off from this body must not re-enter it yet: it is still below
     * {@link #REARM_FACTOR} × the switch altitude <i>and</i> still climbing ({@code radialVelocity} > 0, m/s,
     * positive up). A descending craft is never blocked, so a take-off that turns back can always land again.
     */
    public static boolean reentryBlocked(double altitudeMetres, double switchAltitude, double radialVelocity) {
        return radialVelocity > 0 && !reentryArmed(altitudeMetres, switchAltitude);
    }

    /**
     * Seconds until a craft at {@code altitude} reaches the switch at {@code descentRate} m/s (positive
     * down), capped at {@code maxSeconds}; {@code maxSeconds} when it is not descending.
     */
    public static double secondsToSwitch(double altitude, double switchAltitude, double descentRate, double maxSeconds) {
        double above = altitude - switchAltitude;
        if (above <= 0) return 0;
        if (!(descentRate > 1e-3)) return maxSeconds;
        return Math.min(maxSeconds, above / descentRate);
    }
}
