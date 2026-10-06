package dev.galactica.planet.transfer;

/**
 * One altitude scale on both sides of the hand-off. A surface block row Y sits at
 * (Y − sea level) × metres-per-block above the datum, the same scale the map uses horizontally
 * ({@code SurfaceGrid.metresPerBlock()}), so the space side triggers the switch at exactly the height the
 * surface side places you, and taking off returns you to the same radial at the same height.
 *
 * <p>The switch rows are above every build limit (surface slots top out at Y 511, the Overworld at 319):
 * {@code LevelLoadTracker.WaitingForPlayerChunk.isReady()} returns true at once when the camera is
 * outside build height (verified on 26.3), so the client never waits for chunks at the switch.
 * Earth's switch is then (1500 − 96) × 10.0 m ≈ 14 km, below the stratopause where the plasma has
 * died down; Mars ≈ 7.5 km, the Moon ≈ 3.8 km.
 *
 * <p><b>Hysteresis.</b> After a take-off at {@link #EXIT_Y} a craft could fall straight back through the
 * entry trigger. Re-entry is armed again only once the craft has climbed above {@link #REARM_FACTOR} ×
 * the switch altitude, a Schmitt-trigger band on top of the time cooldown.
 */
public final class TransferAltitude {
    private TransferAltitude() {}

    /** Surface row where an entry arrives (and the space-side trigger altitude it corresponds to). */
    public static final double ENTRY_Y = 1500;
    /** Surface row above which a craft takes off to space. */
    public static final double EXIT_Y = 1700;
    /** Re-entry is armed only above this multiple of the switch altitude. */
    public static final double REARM_FACTOR = 1.3;
    /** Sea level of the Overworld, where Earth of Sol is landed. */
    public static final int OVERWORLD_SEA_LEVEL = 63;

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
