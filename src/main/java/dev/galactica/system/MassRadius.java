package dev.galactica.system;

/**
 * Chen &amp; Kipping (2017) probabilistic mass–radius relation, mean branch.
 * Terran: R = M^0.279 (M &lt; 2.04 M⊕); Neptunian: R = 0.808·M^0.589 (up to 0.414 M_J ≈ 131.6 M⊕);
 * Jovian: R ∝ M^−0.044 (degenerate interior, radius almost constant at ~1 R_J).
 * The mean C&amp;K curve overshoots Jupiter by ~20 %, so the Jovian branch is normalised to Jupiter
 * (11.21 R⊕ at 317.8 M⊕) and the Neptunian branch is capped by it; the two meet near 95 M⊕.
 */
public final class MassRadius {
    private MassRadius() {}

    private static final double T1 = 2.04;
    private static final double R_T1 = Math.pow(T1, 0.279);
    private static final double K_NEP = R_T1 / Math.pow(T1, 0.589);
    private static final double K_JOV = 11.21 / Math.pow(317.8, -0.044);

    public static double radiusFromMass(double m) {
        if (m < T1) return Math.pow(m, 0.279);
        return Math.min(K_NEP * Math.pow(m, 0.589), K_JOV * Math.pow(m, -0.044));
    }

    /** Inverse on the terran and Neptunian branches (the Jovian branch is not invertible). */
    public static double massFromRadius(double r) {
        if (r < R_T1) return Math.pow(r, 1 / 0.279);
        return Math.pow(r / K_NEP, 1 / 0.589);
    }
}
