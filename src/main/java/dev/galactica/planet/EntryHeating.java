package dev.galactica.planet;

/**
 * Entry heating (spec §5).
 *
 * <p><b>Physical glow (M7).</b> The plasma strength itself comes from the Sutton–Graves heat flux
 * ({@link dev.galactica.planet.transfer.EntryAerothermo#plasma}), computed from real altitude and speed on
 * both sides of the switch. What the eye sees lags it: the heat shield keeps radiating after the peak.
 * {@link #glow} holds the visible strength with a first-order thermal time constant {@link #GLOW_TAU_SECONDS},
 * τ = ρ·c·δ/(4εσT³) for a thin radiating skin; a 2 mm carbon–carbon skin (ρ 1 800 kg/m³, c 1 000 J/kg·K)
 * near 1 500 K gives τ ≈ 1–2 s. That lag is what carries the glow across the dimension switch.
 *
 * <p><b>Arrival fade (M4).</b> {@link #intensity(double, long)} is the older arrival-based glow, kept for
 * code that only knows the time since arrival: strength grows with log pressure, since the shock layer's
 * heating rate scales with √ρ and the visible plasma saturates: 0.1 bar (just visible) → 1 at 100 bar (Venus).
 */
public final class EntryHeating {
    private EntryHeating() {}

    public static final double MIN_PRESSURE_BAR = 0.1;
    public static final int DURATION_TICKS = 100;
    /** Cooling time constant of the visible glow, seconds. */
    public static final double GLOW_TAU_SECONDS = 1.5;

    /** 0 (none) to 1 (full glow) for a body's surface pressure, {@code ticks} after arrival. */
    public static double intensity(double pressureBar, long ticks) {
        if (!(pressureBar > MIN_PRESSURE_BAR) || ticks < 0 || ticks >= DURATION_TICKS) return 0;
        double density = Math.min(1, (Math.log10(pressureBar) + 1) / 3);
        double fade = 1 - (double) ticks / DURATION_TICKS;
        return (0.2 + 0.8 * density) * fade * fade;
    }

    /**
     * Visible glow after {@code dtSeconds}: rises at once to the instantaneous strength (heating is fast),
     * otherwise cools exponentially from {@code previous}.
     */
    public static double glow(double previous, double instant, double dtSeconds) {
        double cooled = previous * Math.exp(-Math.max(0, dtSeconds) / GLOW_TAU_SECONDS);
        double g = Math.max(cooled, instant);
        return g < 1e-3 ? 0 : Math.min(1, g);
    }
}
