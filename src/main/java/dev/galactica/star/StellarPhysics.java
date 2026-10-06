package dev.galactica.star;

import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;

/**
 * Stellar physics used by the generator: Kroupa (2001) IMF sampling, main-sequence
 * mass–luminosity and mass–radius relations, main-sequence lifetimes, a short giant phase,
 * and remnants (white dwarf initial–final mass relation and cooling, neutron stars, black holes).
 * All quantities are in solar units unless stated.
 */
public final class StellarPhysics {
    private StellarPhysics() {}

    public static final double M_MIN = 0.08;   // hydrogen-burning limit
    public static final double M_BREAK = 0.5;
    public static final double M_MAX = 150.0;
    private static final double A1 = 1.3, A2 = 2.3;

    private static final double SEG1 = segmentIntegral(1.0, A1, M_MIN, M_BREAK);
    // Continuity at the break: k2 = k1 · M_BREAK^(A2 - A1).
    private static final double K2 = Math.pow(M_BREAK, A2 - A1);
    private static final double SEG2 = segmentIntegral(K2, A2, M_BREAK, M_MAX);
    private static final double P_LOW = SEG1 / (SEG1 + SEG2);

    private static double segmentIntegral(double k, double alpha, double lo, double hi) {
        double e = 1.0 - alpha;
        return k * (Math.pow(hi, e) - Math.pow(lo, e)) / e;
    }

    private static double sampleSegment(double u, double alpha, double lo, double hi) {
        double e = 1.0 - alpha;
        double a = Math.pow(lo, e), b = Math.pow(hi, e);
        return Math.pow(a + u * (b - a), 1.0 / e);
    }

    /** Initial stellar mass drawn from the Kroupa IMF. */
    public static double sampleInitialMass(SeedRng rng) {
        double u = rng.nextDouble();
        return rng.nextDouble() < P_LOW
                ? sampleSegment(u, A1, M_MIN, M_BREAK)
                : sampleSegment(u, A2, M_BREAK, M_MAX);
    }

    /** Fraction of IMF-drawn stars above a given mass, used for tests and population stats. */
    public static double fractionAbove(double mass) {
        if (mass <= M_MIN) return 1.0;
        if (mass >= M_MAX) return 0.0;
        double above = mass < M_BREAK
                ? segmentIntegral(1.0, A1, mass, M_BREAK) + SEG2
                : segmentIntegral(K2, A2, mass, M_MAX);
        return above / (SEG1 + SEG2);
    }

    /** Zero-age main-sequence luminosity (piecewise power law, Duric 2004 / Salaris &amp; Cassisi). */
    public static double mainSequenceLuminosity(double m) {
        if (m < 0.43) return 0.23 * Math.pow(m, 2.3);
        if (m < 2.0) return Math.pow(m, 4.0);
        if (m < 55.0) return 1.4 * Math.pow(m, 3.5);
        return 32_000.0 * m;
    }

    public static double mainSequenceRadius(double m) {
        return m < 1.0 ? Math.pow(m, 0.8) : Math.pow(m, 0.57);
    }

    /** Main-sequence lifetime in Gyr: t ≈ 10 Gyr · M/L, floored at 3 Myr for the most massive stars. */
    public static double mainSequenceLifetimeGyr(double m) {
        return Math.max(0.003, 10.0 * m / mainSequenceLuminosity(m));
    }

    /** Effective temperature from luminosity and radius via Stefan–Boltzmann. */
    public static double effectiveTemperature(double lum, double radius) {
        return Units.SOLAR_TEFF * Math.pow(lum / (radius * radius), 0.25);
    }

    /** White-dwarf mass from the initial mass (Cummings et al. 2018, simplified linear fit). */
    public static double whiteDwarfMass(double initialMass) {
        return Math.min(1.38, 0.109 * initialMass + 0.394);
    }

    /** Eggleton-style white-dwarf radius (solar radii) — shrinks with mass, zero at Chandrasekhar. */
    public static double whiteDwarfRadius(double m) {
        double ratio = m / 1.44;
        return 0.0114 * Math.sqrt(Math.max(1e-4, Math.pow(ratio, -2.0 / 3.0) - Math.pow(ratio, 2.0 / 3.0)));
    }

    /** Mestel-like cooling: Teff ≈ 20 000 K at 0.1 Gyr, falling as t^-0.35, floor 3 500 K. */
    public static double whiteDwarfTemperature(double coolingAgeGyr) {
        return Math.max(3_500.0, 20_000.0 * Math.pow(Math.max(0.005, coolingAgeGyr) / 0.1, -0.35));
    }

    /** Schwarzschild radius in solar radii. */
    public static double schwarzschildRadius(double massSolar) {
        return 2.0 * Units.G * massSolar * Units.SOLAR_MASS / (Units.C * Units.C) / Units.SOLAR_RADIUS;
    }
}
