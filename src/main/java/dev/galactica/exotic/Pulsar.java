package dev.galactica.exotic;

import dev.galactica.science.SeedRng;

/**
 * Rotation and beam geometry of a pulsar, drawn deterministically from a seed.
 *
 * <ul>
 *   <li>Spin period: about 10 % of the known population are recycled millisecond pulsars (ATNF
 *       catalogue, Manchester et al. 2005, AJ 129, 1993), log₁₀ P/s ~ N(−2.43, 0.20); the rest are
 *       "normal" pulsars with log₁₀ P/s ~ N(−0.30, 0.35), median ≈ 0.5 s (Faucher-Giguère &amp;
 *       Kaspi 2006, ApJ 643, 332, evolved population).</li>
 *   <li>Magnetic obliquity χ: isotropic, cos χ uniform in [0, 1].</li>
 *   <li>Beam half-opening: ρ = 5.75° · P<sup>−1/2</sup> (outer cone, Rankin 1993, ApJ 405, 285),
 *       capped at 45° for millisecond pulsars.</li>
 * </ul>
 * The observer sees a pulse when the angle between either magnetic pole and the direction to the
 * observer drops below ρ. Optical pulses of this kind are real (the Crab pulsar, Cocke et al. 1969);
 * the visible cones drawn by the renderer are a visualisation of where that emission goes.
 *
 * @param periodS      spin period, seconds
 * @param obliquityRad angle between spin and magnetic axes
 * @param beamHalfRad  beam half-opening ρ
 * @param phase0       rotation phase at t = 0, radians
 */
public record Pulsar(double periodS, double obliquityRad, double beamHalfRad, double phase0) {

    public static final double MSP_FRACTION = 0.10;

    public static Pulsar fromSeed(long seed) {
        SeedRng rng = new SeedRng(SeedRng.hash(seed, 0x9051_7A11L));
        boolean msp = rng.nextDouble() < MSP_FRACTION;
        double logP = msp ? -2.43 + 0.20 * rng.gaussian() : -0.30 + 0.35 * rng.gaussian();
        logP = msp ? Math.max(-2.85, Math.min(-1.5, logP)) : Math.max(-1.6, Math.min(1.1, logP));
        double p = Math.pow(10, logP);
        double chi = Math.acos(rng.nextDouble());
        double rho = Math.min(Math.toRadians(45), Math.toRadians(5.75) / Math.sqrt(p));
        double phase = rng.range(0, 2 * Math.PI);
        return new Pulsar(p, chi, rho, phase);
    }

    /** Rotation phase at t seconds, in [0, 2π). */
    public double phase(double t) {
        double turns = t / periodS;
        double frac = turns - Math.floor(turns);
        double ph = phase0 + 2 * Math.PI * frac;
        return ph % (2 * Math.PI);
    }

    /**
     * Unit magnetic-axis vector at time t for a spin axis {@code spin} (unit, any frame). The
     * reference meridian is fixed by the spin axis alone, so the result is deterministic.
     */
    public void magneticAxis(double[] spin, double t, double[] out) {
        double[] e1 = new double[3], e2 = new double[3];
        perpendicularBasis(spin, e1, e2);
        double ph = phase(t), s = Math.sin(obliquityRad), c = Math.cos(obliquityRad);
        double cp = Math.cos(ph), sp = Math.sin(ph);
        for (int k = 0; k < 3; k++) out[k] = c * spin[k] + s * (cp * e1[k] + sp * e2[k]);
    }

    /**
     * Beam intensity (0..1) seen by an observer in direction {@code toObserver} (unit, from the
     * pulsar) at time t: a Gaussian in the angle to the nearer pole, 1 on axis, ≈ 0 beyond ρ.
     */
    public double beamIntensity(double[] spin, double[] toObserver, double t) {
        double[] m = new double[3];
        magneticAxis(spin, t, m);
        double c = Math.abs(SkyOrbit.dot(m, toObserver));
        double ang = Math.acos(Math.min(1, c));
        double x = ang / beamHalfRad;
        return Math.exp(-2.5 * x * x);
    }

    /** True when the observer is inside either beam at time t. */
    public boolean beamOnObserver(double[] spin, double[] toObserver, double t) {
        double[] m = new double[3];
        magneticAxis(spin, t, m);
        return Math.acos(Math.min(1, Math.abs(SkyOrbit.dot(m, toObserver)))) < beamHalfRad;
    }

    /**
     * Fraction of a rotation during which the observer is inside a beam (the pulse duty cycle),
     * by sampling 720 phases. Zero when the beams never sweep the observer.
     */
    public double dutyCycle(double[] spin, double[] toObserver) {
        int on = 0, n = 720;
        for (int k = 0; k < n; k++) if (beamOnObserver(spin, toObserver, periodS * k / n)) on++;
        return on / (double) n;
    }

    /** Two unit vectors completing {@code axis} to a right-handed orthonormal basis. */
    static void perpendicularBasis(double[] axis, double[] e1, double[] e2) {
        double rx = Math.abs(axis[1]) < 0.9 ? 0 : 1, ry = Math.abs(axis[1]) < 0.9 ? 1 : 0;
        double d = rx * axis[0] + ry * axis[1];
        e1[0] = rx - d * axis[0];
        e1[1] = ry - d * axis[1];
        e1[2] = -d * axis[2];
        SkyOrbit.norm(e1);
        double[] c = SkyOrbit.cross(axis, e1);
        System.arraycopy(c, 0, e2, 0, 3);
    }
}
