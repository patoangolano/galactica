package dev.galactica.planet;

/**
 * Entry heating (spec §5): a particle and HUD effect when the atmosphere is thicker than
 * {@link #MIN_PRESSURE_BAR}. Ships and suits arrive at Y 600 with their orbital speed already spent
 * by the hand-off, so the glow is the hand-off's aftermath: it fades over {@link #DURATION_TICKS}
 * after arrival, stronger in denser air. Strength grows with log pressure, since the shock layer's
 * heating rate scales with √ρ and the visible plasma saturates: 0.1 bar (just visible) → 1 at 100 bar (Venus).
 */
public final class EntryHeating {
    private EntryHeating() {}

    public static final double MIN_PRESSURE_BAR = 0.1;
    public static final int DURATION_TICKS = 100;

    /** 0 (none) to 1 (full glow) for a body's surface pressure, {@code ticks} after arrival. */
    public static double intensity(double pressureBar, long ticks) {
        if (!(pressureBar > MIN_PRESSURE_BAR) || ticks < 0 || ticks >= DURATION_TICKS) return 0;
        double density = Math.min(1, (Math.log10(pressureBar) + 1) / 3);
        double fade = 1 - (double) ticks / DURATION_TICKS;
        return (0.2 + 0.8 * density) * fade * fade;
    }
}
