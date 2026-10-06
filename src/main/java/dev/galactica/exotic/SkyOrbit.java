package dev.galactica.exotic;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.Units;

/**
 * A Keplerian orbit given by the elements astronomers publish for a visual binary or an S-star:
 * semi-major axis, eccentricity, inclination i, longitude of the ascending node Ω and argument of
 * periapsis ω, all referred to the plane of the sky as seen from the Sun, plus the periapsis epoch.
 *
 * <p>Sky-plane convention (Thiele–Innes, Heintz 1978 "Double Stars"; as used by Gillessen et al.
 * 2017, ApJ 837, 30 and GRAVITY Collaboration 2020, A&amp;A 636, L5): with u = ω + ν,
 * <pre>
 *   X = r (cos Ω cos u − sin Ω sin u cos i)     towards celestial north
 *   Y = r (sin Ω cos u + cos Ω sin u cos i)     towards east
 *   Z = r sin u sin i                           along the line of sight, away from the Sun
 * </pre>
 * i &gt; 90° means clockwise motion on the sky; the ascending node is where the star recedes. The
 * (north, east, away) triad is left-handed, so it is never used for cross products; the three
 * components are simply laid along the galactic vectors from {@link #skyBasis}.
 *
 * @param aAu         semi-major axis, AU
 * @param e           eccentricity
 * @param iDeg        inclination, degrees
 * @param nodeDeg     Ω, degrees east of north
 * @param periDeg     ω, degrees
 * @param tPeriYear   epoch of periapsis, Julian years (e.g. 2018.379)
 * @param periodYears orbital period, Julian years
 */
public record SkyOrbit(double aAu, double e, double iDeg, double nodeDeg, double periDeg,
                       double tPeriYear, double periodYears) {

    /** North celestial pole in galactic coordinates (J2000; Liu, Zhu &amp; Zhang 2011, A&amp;A 526, A16). */
    public static final double NCP_L_DEG = 122.93192, NCP_B_DEG = 27.12825;

    /** Seconds since J2000.0 of a Julian epoch (J2000.0 = epoch 2000.0, 365.25-day years). */
    public static double epochToSeconds(double julianYear) {
        return (julianYear - 2000.0) * Units.SECONDS_PER_YEAR;
    }

    /** Period from Kepler's third law, P² = a³ / M (years, AU, solar masses). */
    public static double keplerPeriodYears(double aAu, double massSolar) {
        return Math.sqrt(aAu * aAu * aAu / massSolar);
    }

    public double periapsisAu() {
        return aAu * (1 - e);
    }

    public double apoapsisAu() {
        return aAu * (1 + e);
    }

    /** Solves Kepler's equation E − e sin E = M by Newton iteration (converges for e &lt; 1 from E₀ = π for high e). */
    public static double eccentricAnomaly(double meanAnomaly, double e) {
        double m = meanAnomaly % (2 * Math.PI);
        if (m < 0) m += 2 * Math.PI;
        double ecc = e > 0.8 ? Math.PI : m;
        for (int k = 0; k < 50; k++) {
            double f = ecc - e * Math.sin(ecc) - m;
            double d = f / (1 - e * Math.cos(ecc));
            ecc -= d;
            if (Math.abs(d) < 1e-14) break;
        }
        return ecc;
    }

    /**
     * Sky-frame offset from the central mass at time t (seconds since J2000), in AU:
     * out = {X north, Y east, Z away from the Sun}.
     */
    public void skyOffsetAu(double tSeconds, double[] out) {
        double tp = epochToSeconds(tPeriYear);
        double mean = 2 * Math.PI * (tSeconds - tp) / (periodYears * Units.SECONDS_PER_YEAR);
        double ecc = eccentricAnomaly(mean, e);
        double r = aAu * (1 - e * Math.cos(ecc));
        double nu = 2 * Math.atan2(Math.sqrt(1 + e) * Math.sin(ecc / 2), Math.sqrt(1 - e) * Math.cos(ecc / 2));
        double u = Math.toRadians(periDeg) + nu;
        double node = Math.toRadians(nodeDeg), inc = Math.toRadians(iDeg);
        double cu = Math.cos(u), su = Math.sin(u), cn = Math.cos(node), sn = Math.sin(node), ci = Math.cos(inc);
        out[0] = r * (cn * cu - sn * su * ci);
        out[1] = r * (sn * cu + cn * su * ci);
        out[2] = r * su * Math.sin(inc);
    }

    /**
     * The sky-plane axes at a target, as unit vectors in the galactocentric frame of
     * {@code GalaxyModel} (GC at the origin, Sun at (R0, 0, Z0), z to the north galactic pole):
     * {@code basis[0]} = north, {@code basis[1]} = east, {@code basis[2]} = line of sight away from the Sun.
     * The heliocentric → galactocentric map (x, y, z) → (−x, −y, z) is a proper rotation (see
     * {@link StarCatalog#galacticToGalactocentric}).
     */
    public static double[][] skyBasis(double xPc, double yPc, double zPc) {
        double sx = StarCatalog.SOL.x(), sy = StarCatalog.SOL.y(), sz = StarCatalog.SOL.z();
        double[] los = {xPc - sx, yPc - sy, zPc - sz};
        norm(los);
        double l = Math.toRadians(NCP_L_DEG), b = Math.toRadians(NCP_B_DEG);
        double[] ncp = {-Math.cos(b) * Math.cos(l), -Math.cos(b) * Math.sin(l), Math.sin(b)};
        double d = dot(ncp, los);
        double[] north = {ncp[0] - d * los[0], ncp[1] - d * los[1], ncp[2] - d * los[2]};
        norm(north);
        // East = north × (away): at RA 0, Dec 0 this gives +RA, checked in equatorial axes.
        double[] east = cross(north, los);
        return new double[][] {north, east, los};
    }

    /** Offset from the central mass in the galactocentric frame, in AU, for a centre at (xPc, yPc, zPc). */
    public void galacticOffsetAu(double tSeconds, double xPc, double yPc, double zPc, double[] out) {
        double[] s = new double[3];
        skyOffsetAu(tSeconds, s);
        double[][] basis = skyBasis(xPc, yPc, zPc);
        for (int k = 0; k < 3; k++) out[k] = s[0] * basis[0][k] + s[1] * basis[1][k] + s[2] * basis[2][k];
    }

    /**
     * Unit orbital angular-momentum vector (galactocentric frame) for a flow or orbit with the
     * given sky-plane i and Ω around a centre at (xPc, yPc, zPc): r(u = 0) × r(u = 90°), with both
     * positions first laid onto proper galactic vectors so the cross product is right-handed.
     */
    public static double[] angularMomentumAxis(double iDeg, double nodeDeg, double xPc, double yPc, double zPc) {
        double[][] basis = skyBasis(xPc, yPc, zPc);
        double node = Math.toRadians(nodeDeg), inc = Math.toRadians(iDeg);
        double[] p = combine(basis, Math.cos(node), Math.sin(node), 0);
        double[] q = combine(basis, -Math.sin(node) * Math.cos(inc), Math.cos(node) * Math.cos(inc), Math.sin(inc));
        double[] h = cross(p, q);
        norm(h);
        return h;
    }

    /**
     * Unit vector (galactocentric) for a jet or disk axis with inclination {@code iDeg} to the line
     * of sight (axis tipped towards the Sun) and sky position angle {@code paDeg} (east of north).
     */
    public static double[] axisFromJet(double iDeg, double paDeg, double xPc, double yPc, double zPc) {
        double[][] basis = skyBasis(xPc, yPc, zPc);
        double i = Math.toRadians(iDeg), pa = Math.toRadians(paDeg);
        double[] v = combine(basis, Math.sin(i) * Math.cos(pa), Math.sin(i) * Math.sin(pa), -Math.cos(i));
        norm(v);
        return v;
    }

    private static double[] combine(double[][] basis, double n, double e, double z) {
        double[] v = new double[3];
        for (int k = 0; k < 3; k++) v[k] = n * basis[0][k] + e * basis[1][k] + z * basis[2][k];
        return v;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static void norm(double[] v) {
        double n = Math.sqrt(dot(v, v));
        if (n > 0) {
            v[0] /= n;
            v[1] /= n;
            v[2] /= n;
        }
    }
}
