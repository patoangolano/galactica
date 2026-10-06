package dev.galactica.client.vfx.star;

import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;

/**
 * Physics behind the look of a stellar photosphere, pure Java so it can be unit-tested. Every
 * number the star shader animates is derived here from (Teff, M, R, age); the shader only draws.
 *
 * <h2>Granulation</h2>
 * Granules are the tops of convection cells. Their horizontal size scales with the photospheric
 * pressure scale height {@code H = k·T / (μ·m_H·g)}: radiation-hydrodynamics grids find granule
 * diameters of ≈ 8–10 H across the HR diagram (Nordlund, Stein &amp; Asplund 2009, Living Rev. Solar
 * Phys. 6, 2; Trampedach et al. 2013, ApJ 769, 18). For the Sun H ≈ 130 km and granules ≈ 1 Mm,
 * so the disc holds ~600 granules per radius, while for Betelgeuse (log g ≈ −0.5) only a handful
 * of giant cells cover the disc (Schwarzschild 1975, ApJ 195, 137; Chiavassa et al. 2010).
 * Lifetimes follow the sound-crossing time of a cell, {@code τ ∝ H / c_s ∝ √T / g}, normalised to
 * the solar ≈ 11 min (Title et al. 1989). Both are compressed for display (see {@link #visualCellsPerRadius}).
 * Intensity contrast rises with Teff and falls with g (Magic et al. 2013, A&amp;A 557, A26: ≈ 16 % rms for
 * the Sun at 500 nm, ≈ 20–25 % for F stars and low-gravity giants, ≲ 10 % for K/M dwarfs), and vanishes
 * above the Kraft break where envelopes become radiative (Kraft 1967; ≈ 6200–7500 K).
 *
 * <h2>Limb darkening</h2>
 * Quadratic law {@code I(μ)/I(1) = 1 − a(1−μ) − b(1−μ)²} with V-band coefficients interpolated in
 * log Teff from Claret (2000, A&amp;A 363, 1081) ATLAS LTE models at log g = 4.5, [M/H] = 0.
 */
public final class StarSurfaceModel {
    private StarSurfaceModel() {}

    /** Solar surface gravity, log10(g / cm s⁻²). */
    public static final double LOG_G_SUN = 4.438;
    /** Mean molecular weight of a neutral solar-composition photosphere. */
    public static final double MU_PHOTOSPHERE = 1.30;
    private static final double M_HYDROGEN = 1.6735575e-27;
    /** Granule diameter in pressure scale heights (Nordlund et al. 2009 find ≈ 8–10). */
    public static final double GRANULE_H = 8.5;
    /** Observed solar granule lifetime, s (Title et al. 1989, ApJ 336, 475: 8–16 min). */
    public static final double SUN_GRANULE_LIFETIME_S = 670.0;
    /** Kraft break: below LO the envelope is fully convective at the surface, above HI it is radiative. */
    public static final double KRAFT_LO = 6200.0, KRAFT_HI = 7500.0;

    // ------------------------------------------------------------------ gravity and scale height

    /** log10 surface gravity in cgs from mass and radius in solar units. */
    public static double logG(double massSolar, double radiusSolar) {
        double g = Units.G * massSolar * Units.SOLAR_MASS / Math.pow(radiusSolar * Units.SOLAR_RADIUS, 2);
        return Math.log10(Math.max(1e-30, g * 100.0));
    }

    /** Photospheric pressure scale height in km. */
    public static double pressureScaleHeightKm(double teff, double logG) {
        double g = Math.pow(10, logG) / 100.0;
        return Units.K_BOLTZMANN * teff / (MU_PHOTOSPHERE * M_HYDROGEN * g) / 1000.0;
    }

    /** Typical granule diameter in km. */
    public static double granuleSizeKm(double teff, double logG) {
        return GRANULE_H * pressureScaleHeightKm(teff, logG);
    }

    /** Physical number of granules across one stellar radius. */
    public static double granulesPerRadius(double radiusSolar, double teff, double logG) {
        return radiusSolar * Units.SOLAR_RADIUS / 1000.0 / granuleSizeKm(teff, logG);
    }

    /**
     * Cells per radius actually drawn. Six hundred solar granules per radius would alias into grey
     * at any on-screen size, so the count is compressed as a square root around a pivot of 3 (giant
     * stars keep their true handful of cells) and capped at 64. The map is strictly monotonic, so the
     * ordering "dwarfs fine, giants coarse" survives exactly.
     */
    public static double visualCellsPerRadius(double physicalPerRadius) {
        double p = Math.max(1e-6, physicalPerRadius);
        double v = p <= 3.0 ? p : 3.0 * Math.sqrt(p / 3.0);
        return clamp(v, 2.0, 64.0);
    }

    /** Physical granule lifetime in seconds, τ ∝ √T / g, solar 670 s. */
    public static double granuleLifetimeSeconds(double teff, double logG) {
        return SUN_GRANULE_LIFETIME_S * Math.sqrt(teff / Units.SOLAR_TEFF) * Math.pow(10, LOG_G_SUN - logG);
    }

    /** Displayed lifetime: the Sun's 11 min becomes 20 s; giants slow down but stay animated (≤ 90 s). */
    public static double visualLifetimeSeconds(double physicalSeconds) {
        return clamp(20.0 * Math.pow(Math.max(1e-6, physicalSeconds) / SUN_GRANULE_LIFETIME_S, 0.25), 8.0, 90.0);
    }

    /** Fraction of a surface convection zone: 1 below the Kraft break, 0 for radiative envelopes. */
    public static double convectiveFraction(double teff) {
        return 1.0 - smoothstep(KRAFT_LO, KRAFT_HI, teff);
    }

    /** RMS granulation intensity contrast (fraction), Magic et al. 2013 trend; Sun ≈ 0.16. */
    public static double granulationContrast(double teff, double logG) {
        double c = 0.16 * Math.pow(teff / Units.SOLAR_TEFF, 1.5) * (1.0 + 0.08 * (LOG_G_SUN - logG));
        return clamp(c, 0.0, 0.30) * convectiveFraction(teff);
    }

    // ------------------------------------------------------------------ limb darkening

    /** log Teff grid and V-band quadratic coefficients (a, b), Claret 2000 table, log g 4.5, solar [M/H]. */
    private static final double[] LD_T = {3500, 4000, 4500, 5000, 5750, 6500, 7500, 8500, 10000, 15000, 25000, 40000};
    private static final double[] LD_A = {0.60, 0.68, 0.62, 0.55, 0.44, 0.36, 0.29, 0.25, 0.21, 0.16, 0.12, 0.10};
    private static final double[] LD_B = {0.17, 0.11, 0.15, 0.20, 0.26, 0.30, 0.33, 0.33, 0.32, 0.28, 0.25, 0.22};

    /** Quadratic limb-darkening coefficients {a, b} for the V band at this Teff. */
    public static double[] limbCoefficients(double teff) {
        double lt = Math.log(clamp(teff, LD_T[0], LD_T[LD_T.length - 1]));
        int i = 0;
        while (i < LD_T.length - 2 && lt > Math.log(LD_T[i + 1])) i++;
        double f = (lt - Math.log(LD_T[i])) / (Math.log(LD_T[i + 1]) - Math.log(LD_T[i]));
        f = clamp(f, 0, 1);
        return new double[] {LD_A[i] + f * (LD_A[i + 1] - LD_A[i]), LD_B[i] + f * (LD_B[i + 1] - LD_B[i])};
    }

    /** Relative specific intensity at μ = cos(angle from disc centre). */
    public static double limbIntensity(double mu, double a, double b) {
        double x = 1.0 - clamp(mu, 0, 1);
        return 1.0 - a * x - b * x * x;
    }

    /**
     * Wavelength factors applied to (a, b) per display channel: darkening is stronger in the blue,
     * which reddens the limb (Neckel &amp; Labs 1994, Sol. Phys. 153, 91: limb/centre ≈ 0.45 at 650 nm,
     * 0.25 at 450 nm for the Sun). With the solar V-band (a, b) = (0.44, 0.26), a + b = 0.70, so the
     * factors 0.80 and 1.07 give limb/centre 1 - 0.80*0.70 = 0.44 (red) and 1 - 1.07*0.70 = 0.25 (blue).
     * Must match {@code LIMB_CHROMA} in star_disc.fsh.
     */
    public static final double[] LIMB_CHROMA = {0.80, 1.00, 1.07};

    /** Display-channel (0 = R, 1 = G, 2 = B) limb intensity, floored at 0.03 as in star_disc.fsh. */
    public static double limbIntensity(double mu, double a, double b, int channel) {
        double k = LIMB_CHROMA[channel];
        return Math.max(0.03, limbIntensity(mu, a * k, b * k));
    }

    // ------------------------------------------------------------------ display transform

    /** Exposure of the filmic curve; must match {@code EXPOSURE} in star_disc.fsh. */
    public static final double EXPOSURE = 1.25;
    /** Share of the small-scale (granulation) contrast kept after tone mapping; matches {@code DETAIL_KEEP}. */
    public static final double DETAIL_KEEP = 0.85;

    /** Filmic curve on linear radiance (disc centre = 1). */
    public static double tonemap(double linear) {
        return 1.0 - Math.exp(-Math.max(0, linear) * EXPOSURE);
    }

    /**
     * Display value (gamma-encoded) of one channel: the large-scale profile is tone mapped, then the
     * small-scale granulation factor is reapplied multiplicatively ("local" tone mapping), so the
     * 15–20 % solar granulation survives instead of being squashed by the shoulder of the curve.
     * Mirrors {@code displayDisc} in star_disc.fsh.
     */
    public static double displayDisc(double baseLinear, double granulationFactor) {
        double detail = 1.0 + DETAIL_KEEP * (granulationFactor - 1.0);
        return clamp(Blackbody.encode(tonemap(baseLinear)) * detail, 0, 1);
    }

    // ------------------------------------------------------------------ rotation and activity

    /** Solar sidereal equatorial rotation period, days (Snodgrass &amp; Ulrich 1990). */
    public static final double SUN_ROTATION_DAYS = 25.4;

    /**
     * Rotation period in days. Main-sequence spin-down follows Skumanich (1972) {@code P ∝ t^½}
     * calibrated on the Sun; giants rotate slowly because their radius grew at fixed angular momentum
     * ({@code P ∝ R²}).
     */
    public static double rotationPeriodDays(double ageGyr, double radiusSolar, double teff) {
        double p = SUN_ROTATION_DAYS * Math.sqrt(clamp(ageGyr, 0.05, 13.8) / 4.6);
        if (radiusSolar > 2.0) p *= radiusSolar * radiusSolar / 4.0;
        // Hot stars keep their natal fast rotation (no magnetic braking above the Kraft break).
        double cf = convectiveFraction(teff);
        p = p * cf + 1.5 * (1 - cf);
        return clamp(p, 0.3, 5000.0);
    }

    /** Displayed equatorial angular speed, rad/s: the Sun turns once per 20 real minutes, others scale as P^0.4. */
    public static double visualRotationRate(double periodDays) {
        double visualPeriod = 1200.0 * Math.pow(periodDays / SUN_ROTATION_DAYS, 0.4);
        return 2 * Math.PI / clamp(visualPeriod, 120.0, 20000.0);
    }

    /**
     * Mean magnetic activity relative to the Sun. Chromospheric activity falls with rotation period
     * (Noyes et al. 1984, Rossby-number relation); with Skumanich spin-down this goes as {@code t^-½}.
     * Only convective envelopes host a dynamo. Fully convective M dwarfs stay active for Gyr
     * (West et al. 2008), so they get a floor of 1.5×.
     */
    public static double meanActivity(double ageGyr, double teff) {
        double a = Math.sqrt(4.6 / clamp(ageGyr, 0.05, 13.8));
        if (teff < 3900) a = Math.max(a, 1.5);
        return clamp(a, 0.2, 3.0) * convectiveFraction(teff);
    }

    /**
     * Cycle modulation: activity rises from minimum (phase 0) to maximum (≈ 0.35) and decays,
     * like the asymmetric Waldmeier-shaped solar cycle. Phase in [0, 1).
     */
    public static double cycleShape(double phase) {
        double p = phase - Math.floor(phase);
        double s = p < 0.35 ? Math.sin(Math.PI / 2 * p / 0.35) : Math.cos(Math.PI / 2 * (p - 0.35) / 0.65);
        return 0.12 + 0.88 * s * s;
    }

    /** Spörer's law: mean spot latitude drifts from ≈ 30° at cycle start to ≈ 8° at its end, radians. */
    public static double sporerLatitude(double phase) {
        double p = phase - Math.floor(phase);
        return Math.toRadians(30.0 - 22.0 * p);
    }

    /** M-dwarf flare rate, flares per real minute shown (UV Ceti stars flare ~hourly; compressed). */
    public static double flareRatePerMinute(double teff, double activity) {
        if (teff >= 4000) return 0.0;
        return clamp(0.6 * activity * (1.0 + (4000 - teff) / 1000.0), 0.0, 2.5);
    }

    /** Blackbody display colour (linear sRGB, max channel 1). */
    public static float[] colour(double teff) {
        float[] c = new float[3];
        Blackbody.linearRgb(teff, c);
        return c;
    }

    // ------------------------------------------------------------------ helpers

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(hi, v);
    }

    static double smoothstep(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
