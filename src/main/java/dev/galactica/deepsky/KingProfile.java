package dev.galactica.deepsky;

/**
 * The King (1962, AJ 67, 471) empirical star-cluster profile with core radius r_c and tidal radius
 * r_t (concentration c = log₁₀ r_t/r_c). Surface density
 *
 * <pre>  Σ(R) ∝ [ (1 + R²/r_c²)^{-1/2} − (1 + r_t²/r_c²)^{-1/2} ]² </pre>
 *
 * and the volume density obtained by its Abel inversion (King 1962 eq. 27)
 *
 * <pre>  ρ(r) ∝ z⁻² [ arccos(z)/z − √(1 − z²) ],   z² = (1 + r²/r_c²)/(1 + r_t²/r_c²). </pre>
 *
 * Provides radius sampling for member stars (tabulated cumulative mass), the projected half-light
 * radius, and a multi-Gaussian expansion for the unresolved glow.
 */
public final class KingProfile {
    public final double rc, rt;
    private static final int TABLE = 512;
    /** Radii (pc) at equal steps of enclosed fraction 0 .. 1. */
    private final double[] inverseCdf = new double[TABLE + 1];
    private volatile Mge mge;

    public KingProfile(double rc, double rt) {
        this.rc = rc;
        this.rt = Math.max(rt, rc * 1.5);
        // Enclosed mass M(<r) = ∫ 4π r² ρ dr on a fine grid, then inverted.
        int n = 4096;
        double[] r = new double[n + 1], m = new double[n + 1];
        for (int i = 1; i <= n; i++) {
            r[i] = this.rt * Math.pow((double) i / n, 1.6); // denser sampling near the core
            double rm = 0.5 * (r[i] + r[i - 1]);
            m[i] = m[i - 1] + 4 * Math.PI * rm * rm * density(rm) * (r[i] - r[i - 1]);
        }
        int j = 0;
        for (int k = 0; k <= TABLE; k++) {
            double target = m[n] * k / TABLE;
            while (j < n && m[j + 1] < target) j++;
            if (j >= n) {
                inverseCdf[k] = this.rt;
            } else {
                double f = (target - m[j]) / Math.max(1e-300, m[j + 1] - m[j]);
                inverseCdf[k] = r[j] + f * (r[j + 1] - r[j]);
            }
        }
    }

    /** Unnormalised volume density at radius r (pc). */
    public double density(double r) {
        if (r >= rt) return 0;
        double z2 = (1 + r * r / (rc * rc)) / (1 + rt * rt / (rc * rc));
        double z = Math.sqrt(z2);
        return (Math.acos(z) / z - Math.sqrt(Math.max(0, 1 - z2))) / z2;
    }

    /** Unnormalised projected surface density at projected radius R (pc). */
    public double surfaceDensity(double rProj) {
        if (rProj >= rt) return 0;
        double a = 1 / Math.sqrt(1 + rProj * rProj / (rc * rc)) - 1 / Math.sqrt(1 + rt * rt / (rc * rc));
        return a * a;
    }

    /** Radius enclosing fraction u ∈ [0, 1) of the stars. */
    public double radiusAt(double u) {
        double f = Math.max(0, Math.min(1, u)) * TABLE;
        int i = Math.min(TABLE - 1, (int) f);
        return inverseCdf[i] + (f - i) * (inverseCdf[i + 1] - inverseCdf[i]);
    }

    /** 3D half-mass radius. */
    public double halfMassRadius() {
        return radiusAt(0.5);
    }

    /** Projected half-light radius r_h (numerically, from the surface density). */
    public double projectedHalfRadius() {
        int n = 4000;
        double[] cum = new double[n + 1];
        for (int i = 1; i <= n; i++) {
            double r = rt * (i - 0.5) / n;
            cum[i] = cum[i - 1] + 2 * Math.PI * r * surfaceDensity(r) * rt / n;
        }
        for (int i = 1; i <= n; i++) if (cum[i] >= 0.5 * cum[n]) return rt * i / n;
        return rt;
    }

    /** Ratio r_h / r_c of a King profile with concentration c (used to place catalogue clusters). */
    public static double halfLightOverCore(double concentration) {
        return new KingProfile(1.0, Math.pow(10, concentration)).projectedHalfRadius();
    }

    /** Multi-Gaussian expansion of the 3D density (fractions of the total), cached. */
    public Mge mge() {
        Mge m = mge;
        if (m == null) {
            m = Mge.fit(this::density, Math.min(rc * 0.25, rt * 0.02), rt * 0.95, 7, 3);
            mge = m;
        }
        return m;
    }
}
