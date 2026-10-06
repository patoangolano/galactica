package dev.galactica.galaxy;

/**
 * Analytic structural model of the Milky Way. Galactocentric Cartesian frame in parsecs:
 * the Galactic Centre is the origin, z points to the north galactic pole, and the Sun sits at
 * (R0, 0, Z0) with the Galaxy rotating clockwise when seen from the north.
 *
 * <p>Components and parameters (Bland-Hawthorn &amp; Gerhard 2016 review values unless noted):
 * <ul>
 *   <li>thin disk: exponential, scale length 2.6 kpc, scale height 300 pc;</li>
 *   <li>thick disk: scale length 2.0 kpc, scale height 900 pc, 12 % of the local thin-disk density;</li>
 *   <li>boxy bar/bulge: triaxial Gaussian, half-lengths 1.5 × 0.6 × 0.4 kpc, long axis 27° from the Sun–GC line;</li>
 *   <li>stellar halo: power law ρ ∝ r<sup>-3.5</sup>, 0.15 % of the local disk density;</li>
 *   <li>four logarithmic spiral arms, pitch 12°, (Vallée 2017), width 400 pc;</li>
 *   <li>dust: exponential disk, scale length 3.0 kpc, scale height 100 pc, A<sub>V</sub> ≈ 1 mag/kpc at the Sun.</li>
 * </ul>
 * The local stellar number density is 0.095 stars pc<sup>-3</sup> (RECONS 10 pc census incl. white dwarfs),
 * which integrates to ~1.1 × 10<sup>11</sup> stars over the whole model.
 */
public final class GalaxyModel {
    private GalaxyModel() {}

    public static final double R0 = 8178.0;   // pc, GRAVITY Collaboration 2019
    public static final double Z0 = 20.8;     // pc, Bennett & Bovy 2019
    public static final double RADIUS_LIMIT = 30_000.0; // pc, beyond this density is treated as zero

    static final double LOCAL_DENSITY = 0.095;

    static final double THIN_L = 2600.0, THIN_H = 300.0;
    static final double THICK_L = 2000.0, THICK_H = 900.0, THICK_FRAC = 0.12;
    static final double HALO_FRAC = 0.0015, HALO_CORE = 1000.0;
    static final double BAR_A = 1500.0, BAR_B = 600.0, BAR_C = 400.0;
    static final double BAR_ANGLE = Math.toRadians(27.0);
    // (2π)^1.5·a·b·c ≈ 5.7×10^9 pc³ of Gaussian volume; 6 stars/pc³ gives ~3.4×10^10 bulge stars
    // (1.5×10^10 M☉ at a Kroupa mean mass of ~0.45 M☉).
    static final double BAR_CENTRAL_DENSITY = 6.0;

    static final int ARM_COUNT = 4;
    static final double ARM_PITCH = Math.toRadians(12.0);
    static final double ARM_WIDTH = 400.0;
    static final double ARM_REF_RADIUS = 2100.0;     // arms start at the end of the bar
    /** Phase of arm 0 at ARM_REF_RADIUS: puts Sagittarius–Carina 0.86 kpc inside the Sun and Perseus 1.9 kpc outside. */
    static final double ARM_PHASE0 = Math.toRadians(25.0);
    static final double ARM_STAR_CONTRAST = 1.2;     // old stars: arm/interarm ≈ 2 (K-band, Drimmel & Spergel 2001)
    static final double ARM_YOUNG_CONTRAST = 6.0;    // OB stars concentrate strongly in arms

    static final double DUST_L = 3000.0, DUST_H = 100.0;
    /** V-band extinction per parsec at the Sun (≈1 mag/kpc). */
    static final double DUST_LOCAL_AV_PER_PC = 0.001;

    private static final double THIN_NORM = LOCAL_DENSITY * Math.exp(R0 / THIN_L);
    private static final double THICK_NORM = LOCAL_DENSITY * THICK_FRAC * Math.exp(R0 / THICK_L);

    public static double cylindricalRadius(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    /** Stars per cubic parsec at a galactocentric position. */
    public static double starDensity(double x, double y, double z) {
        double r = cylindricalRadius(x, y);
        if (r > RADIUS_LIMIT || Math.abs(z) > RADIUS_LIMIT) return 0.0;
        double az = Math.abs(z);
        double thin = THIN_NORM * Math.exp(-r / THIN_L - az / THIN_H) * (1.0 + ARM_STAR_CONTRAST * armStrength(x, y));
        double thick = THICK_NORM * Math.exp(-r / THICK_L - az / THICK_H);
        return thin + thick + bulgeDensity(x, y, z) + haloDensity(x, y, z);
    }

    /** 0..1 proximity to the nearest spiral arm ridge (Gaussian in perpendicular distance). */
    public static double armStrength(double x, double y) {
        double r = cylindricalRadius(x, y);
        if (r < ARM_REF_RADIUS * 0.8) return 0.0;
        double theta = Math.atan2(y, x);
        // Along a log spiral, theta grows with ln(r)/tan(pitch); subtract it to get the arm phase.
        double phase = theta - Math.log(r / ARM_REF_RADIUS) / Math.tan(ARM_PITCH) - ARM_PHASE0;
        double spacing = 2.0 * Math.PI / ARM_COUNT;
        double d = phase - spacing * Math.floor(phase / spacing + 0.5); // in [-spacing/2, spacing/2]
        double perp = r * Math.abs(d) * Math.sin(ARM_PITCH);
        double strength = Math.exp(-0.5 * (perp / ARM_WIDTH) * (perp / ARM_WIDTH));
        // Arms fade in over the bar end and out towards the disk edge.
        double fadeIn = smoothstep(ARM_REF_RADIUS * 0.8, ARM_REF_RADIUS * 1.4, r);
        double fadeOut = 1.0 - smoothstep(15_000, 22_000, r);
        return strength * fadeIn * fadeOut;
    }

    /** Index (0..3) of the nearest arm, for naming and per-arm colour variation. */
    public static int nearestArm(double x, double y) {
        double r = Math.max(1.0, cylindricalRadius(x, y));
        double phase = Math.atan2(y, x) - Math.log(r / ARM_REF_RADIUS) / Math.tan(ARM_PITCH) - ARM_PHASE0;
        double spacing = 2.0 * Math.PI / ARM_COUNT;
        long k = Math.round(phase / spacing);
        return (int) Math.floorMod(k, ARM_COUNT);
    }

    public static double bulgeDensity(double x, double y, double z) {
        double c = Math.cos(BAR_ANGLE), s = Math.sin(BAR_ANGLE);
        double bx = (c * x + s * y) / BAR_A;
        double by = (-s * x + c * y) / BAR_B;
        double bz = z / BAR_C;
        double r2 = bx * bx + by * by + bz * bz;
        return r2 > 60 ? 0.0 : BAR_CENTRAL_DENSITY * Math.exp(-0.5 * r2);
    }

    public static double haloDensity(double x, double y, double z) {
        double r = Math.sqrt(x * x + y * y + z * z);
        double q = (r + HALO_CORE) / (R0 + HALO_CORE);
        return LOCAL_DENSITY * HALO_FRAC * Math.pow(q, -3.5);
    }

    /** V-band extinction per parsec (mag/pc). Dust is concentrated in the plane and in the arms. */
    public static double dustExtinctionPerPc(double x, double y, double z) {
        double r = cylindricalRadius(x, y);
        if (r > RADIUS_LIMIT) return 0.0;
        double norm = DUST_LOCAL_AV_PER_PC * Math.exp(R0 / DUST_L);
        double central = r < 2500 ? smoothstep(500, 2500, r) * 0.7 + 0.3 : 1.0; // dust hole inside the bar
        return norm * Math.exp(-r / DUST_L - Math.abs(z) / DUST_H) * central * (1.0 + 1.5 * armStrength(x, y));
    }

    /**
     * Fraction of a population that is young (&lt;100 Myr) at this position. Drives O/B star
     * frequency and the blue tint of the arms in the rendered sky.
     */
    public static double youngFraction(double x, double y, double z) {
        double r = cylindricalRadius(x, y);
        double disk = Math.exp(-Math.abs(z) / 90.0) * (1.0 - smoothstep(14_000, 20_000, r));
        return 0.01 * disk * (1.0 + ARM_YOUNG_CONTRAST * armStrength(x, y));
    }

    /** Metallicity [Fe/H] in dex: radial gradient −0.06 dex/kpc, thick disk/halo metal-poor. */
    public static double metallicity(double x, double y, double z) {
        double r = cylindricalRadius(x, y);
        double disk = -0.06 * (r - R0) / 1000.0;
        double heightPenalty = -0.25 * Math.min(1.0, Math.abs(z) / 1000.0);
        double bulge = bulgeDensity(x, y, z) > 1.0 ? 0.1 : 0.0;
        double halo = haloDensity(x, y, z) / Math.max(1e-12, starDensity(x, y, z));
        return (1.0 - halo) * (disk + heightPenalty + bulge) + halo * -1.5;
    }

    static double smoothstep(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
