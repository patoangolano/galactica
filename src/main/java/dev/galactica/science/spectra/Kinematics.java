package dev.galactica.science.spectra;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.Star;

/**
 * Space velocities of stars and observers, in km/s in the galactocentric frame of
 * {@code GalaxyModel} (Sun on +x, north galactic pole +z, rotation at the Sun towards −y, i.e.
 * clockwise seen from the north).
 *
 * <ul>
 *   <li><b>Rotation curve.</b> Flat at Θ₀ = 233 km/s beyond 3 kpc (Mróz et al. 2019, ApJL 870, L10:
 *       233.6 ± 2.8 km/s from Cepheids, flat to ~25 kpc), rising as a solid body inside 3 kpc where
 *       the bar and bulge dominate (cf. Sofue 2013, PASJ 65, 118).</li>
 *   <li><b>Peculiar motions.</b> Isotropic Gaussian with 3-D rms 20 km/s, seeded per star: the
 *       velocity dispersion of the thin disk near the Sun (Dehnen &amp; Binney 1998, MNRAS 298, 387,
 *       give σ<sub>U,V,W</sub> ≈ 20–35 km/s, smaller for young stars).</li>
 *   <li><b>The Sun.</b> Circular velocity plus the solar peculiar motion (U, V, W) = (11.1, 12.24,
 *       7.25) km/s (Schönrich, Binney &amp; Dehnen 2010, MNRAS 403, 1829).</li>
 *   <li><b>Real stars.</b> Catalog stars use their measured heliocentric radial velocities
 *       ({@link #catalogRadialVelocity}), so from the Sun the spectrometer reads the SIMBAD value.</li>
 * </ul>
 */
public final class Kinematics {
    private Kinematics() {}

    /** Θ₀, km/s (Mróz et al. 2019). */
    public static final double V0_KMS = 233.0;
    /** Inside this galactocentric radius the curve rises linearly (solid-body rotation), pc. */
    public static final double SOLID_BODY_RADIUS_PC = 3000.0;
    /** One-dimensional peculiar dispersion: 20/√3 km/s gives a 3-D rms of 20 km/s. */
    public static final double SIGMA_1D_KMS = 20.0 / Math.sqrt(3.0);
    /** Solar peculiar motion (Schönrich et al. 2010) in galactocentric axes: U is towards the GC (−x), V along rotation (−y), W to the NGP (+z). */
    public static final double[] SOLAR_PECULIAR = {-11.1, -12.24, 7.25};

    private static final long PECULIAR_SALT = 0x5EC_7E10C1L;

    /** Circular speed at cylindrical radius R (pc), km/s. */
    public static double circularSpeed(double rPc) {
        return rPc >= SOLID_BODY_RADIUS_PC ? V0_KMS : V0_KMS * Math.max(0, rPc) / SOLID_BODY_RADIUS_PC;
    }

    /** Velocity of a circular orbit through (x, y, z) pc, km/s: v = Θ(R)·(y, −x, 0)/R. */
    public static double[] circularVelocity(double x, double y, double z) {
        double r = Math.sqrt(x * x + y * y);
        if (r < 1e-9) return new double[3];
        double v = circularSpeed(r);
        return new double[] {v * y / r, -v * x / r, 0};
    }

    /** Seeded isotropic peculiar velocity, km/s. Deterministic in {@code seed}. */
    public static double[] peculiar(long seed) {
        SeedRng rng = new SeedRng(SeedRng.hash(seed, PECULIAR_SALT));
        return new double[] {SIGMA_1D_KMS * rng.gaussian(), SIGMA_1D_KMS * rng.gaussian(), SIGMA_1D_KMS * rng.gaussian()};
    }

    /** Space velocity of the Sun, km/s. */
    public static double[] solVelocity() {
        Star sol = StarCatalog.SOL;
        double[] v = circularVelocity(sol.x(), sol.y(), sol.z());
        for (int i = 0; i < 3; i++) v[i] += SOLAR_PECULIAR[i];
        return v;
    }

    /**
     * Space velocity of a star, km/s. Catalog stars with a measured radial velocity move with the
     * Sun plus that velocity along the line of sight (their tangential motion relative to the Sun is
     * not modelled), so the heliocentric value is reproduced exactly; Sgr A* is at rest at the
     * dynamical centre (Reid &amp; Brunthaler 2004, ApJ 616, 872); everything else rotates with the disk
     * plus a seeded peculiar velocity.
     */
    public static double[] starVelocity(Star s) {
        if (s == StarCatalog.SOL) return solVelocity();
        if (s == StarCatalog.SGR_A_STAR) return new double[3];
        if (s.catalog()) {
            double rv = catalogRadialVelocity(s.id());
            if (!Double.isNaN(rv)) {
                Star sol = StarCatalog.SOL;
                double dx = s.x() - sol.x(), dy = s.y() - sol.y(), dz = s.z() - sol.z();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double[] v = solVelocity();
                if (d > 0) {
                    v[0] += rv * dx / d;
                    v[1] += rv * dy / d;
                    v[2] += rv * dz / d;
                }
                return v;
            }
        }
        double[] v = circularVelocity(s.x(), s.y(), s.z());
        double[] p = peculiar(s.id());
        for (int i = 0; i < 3; i++) v[i] += p[i];
        return v;
    }

    /**
     * Measured heliocentric radial velocity of a catalog star (km/s, positive = receding), or NaN
     * when none is tabulated. Values from SIMBAD (Wenger et al. 2000) as adopted there, with the
     * source SIMBAD cites on each row; Proxima and α Cen from Kervella et al. (2017), TRAPPIST-1 from
     * Reiners et al. (2018).
     */
    public static double catalogRadialVelocity(long catalogId) {
        return switch ((int) catalogId) {
            case -3 -> -21.4;   // α Cen A
            case -4 -> -18.6;   // α Cen B
            case -5 -> -22.2;   // Proxima Centauri
            case -6 -> -110.1;  // Barnard's Star (Fouqué et al. 2018)
            case -7 -> 19.6;    // Wolf 359 (Fouqué et al. 2018)
            case -8 -> -84.6;   // Lalande 21185 (Fouqué et al. 2018)
            case -9, -10 -> -5.5; // Sirius A/B (system)
            case -11 -> 16.4;   // ε Eridani
            case -12 -> -16.7;  // τ Ceti
            case -13 -> -4.5;   // Procyon A (Jönsson et al. 2020)
            case -14 -> -26.6;  // Altair (Gontcharov 2006)
            case -15 -> -13.5;  // Vega (Adelman et al. 2004)
            case -16 -> 6.5;    // Fomalhaut
            case -17 -> -51.7;  // TRAPPIST-1
            case -18 -> -5.2;   // Arcturus
            case -19 -> -16.4;  // Polaris (Pourbaix et al. 2004)
            case -20 -> 21.9;   // Betelgeuse
            case -21 -> 17.8;   // Rigel
            case -22 -> -4.9;   // Deneb (Gontcharov 2006)
            case -23 -> -3.5;   // Antares (Gontcharov 2006)
            case -24 -> -25.0;  // η Carinae (Wilson 1953, GCRV)
            case -25 -> -2.7;   // Cygnus X-1, systemic velocity of HD 226868 (Gontcharov 2006)
            default -> Double.NaN;
        };
    }

    /** Radial velocity (km/s, positive = receding) of a target relative to an observer, along the unit line of sight from observer to target. */
    public static double radialVelocity(double[] vTarget, double[] vObserver, double[] lineOfSight) {
        double n = Math.sqrt(lineOfSight[0] * lineOfSight[0] + lineOfSight[1] * lineOfSight[1] + lineOfSight[2] * lineOfSight[2]);
        if (n == 0) return 0;
        double s = 0;
        for (int i = 0; i < 3; i++) s += (vTarget[i] - vObserver[i]) * lineOfSight[i];
        return s / n;
    }

    /** Relativistic longitudinal Doppler factor λ_obs/λ_rest = √((1+β)/(1−β)), β = v_r/c. */
    public static double dopplerFactor(double rvKms) {
        double beta = Math.max(-0.999, Math.min(0.999, rvKms * 1000.0 / Units.C));
        return Math.sqrt((1 + beta) / (1 - beta));
    }

    /** Adds a velocity in m/s (e.g. an orbital velocity) to one in km/s. */
    public static double[] plusMetresPerSecond(double[] vKms, double[] vMs) {
        return new double[] {vKms[0] + vMs[0] / 1000.0, vKms[1] + vMs[1] / 1000.0, vKms[2] + vMs[2] / 1000.0};
    }
}
