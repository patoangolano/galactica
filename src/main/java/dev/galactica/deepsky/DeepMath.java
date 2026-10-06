package dev.galactica.deepsky;

import dev.galactica.galaxy.StarCatalog;

/**
 * Small numerical helpers for the deep-sky module: the error function, sky-coordinate
 * conversions and 3 × 3 symmetric matrix algebra for the Gaussian volumes.
 */
public final class DeepMath {
    private DeepMath() {}

    /**
     * ICRS (J2000 equatorial) → galactic rotation matrix, rows are the galactic x, y, z axes
     * (Hipparcos and Tycho Catalogues, ESA SP-1200, 1997, vol. 1 §1.5.3).
     */
    private static final double[][] EQ_TO_GAL = {
            {-0.0548755604, -0.8734370902, -0.4838350155},
            {+0.4941094279, -0.4448296300, +0.7469822445},
            {-0.8676661490, -0.1980763734, +0.4559837762}};

    /** Error function, Abramowitz &amp; Stegun 7.1.26 (|error| &lt; 1.5 × 10⁻⁷). */
    public static double erf(double x) {
        double s = Math.signum(x);
        double a = Math.abs(x);
        if (a > 6) return s;
        double t = 1.0 / (1.0 + 0.3275911 * a);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-a * a);
        return s * y;
    }

    /** RA in hours/minutes/seconds and Dec in degrees/arcmin/arcsec → degrees. */
    public static double ra(double h, double m, double s) {
        return 15.0 * (h + m / 60.0 + s / 3600.0);
    }

    public static double dec(double d, double m, double s) {
        double sign = (d < 0 || (d == 0 && 1 / d < 0)) ? -1 : 1;
        return sign * (Math.abs(d) + m / 60.0 + s / 3600.0);
    }

    /** Equatorial (α, δ in degrees) → galactic (l, b in degrees). */
    public static double[] equatorialToGalactic(double raDeg, double decDeg) {
        double a = Math.toRadians(raDeg), d = Math.toRadians(decDeg);
        double[] e = {Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a), Math.sin(d)};
        double gx = dot(EQ_TO_GAL[0], e), gy = dot(EQ_TO_GAL[1], e), gz = dot(EQ_TO_GAL[2], e);
        double l = Math.toDegrees(Math.atan2(gy, gx));
        if (l < 0) l += 360;
        return new double[] {l, Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, gz))))};
    }

    /** Heliocentric galactic (l, b, d) → galactocentric position of {@code GalaxyModel}. */
    public static double[] galactocentric(double lDeg, double bDeg, double dPc) {
        return StarCatalog.galacticToGalactocentric(lDeg, bDeg, dPc);
    }

    /**
     * Unit direction (galactocentric axes) of galactic (l, b). Heliocentric x_h → −x, y_h → −y,
     * z_h → z (a rotation by π about z, see {@link StarCatalog#galacticToGalactocentric}).
     */
    public static double[] direction(double lDeg, double bDeg) {
        double l = Math.toRadians(lDeg), b = Math.toRadians(bDeg);
        return new double[] {-Math.cos(b) * Math.cos(l), -Math.cos(b) * Math.sin(l), Math.sin(b)};
    }

    /** Galactocentric direction of the north celestial pole (third column of the rotation). */
    public static double[] northCelestialPole() {
        return new double[] {-EQ_TO_GAL[0][2], -EQ_TO_GAL[1][2], EQ_TO_GAL[2][2]};
    }

    /** Galactic (l, b) of a galactocentric point as seen from the Sun, in degrees. */
    public static double[] galacticFromSun(double x, double y, double z) {
        double[] sun = galactocentric(0, 0, 0);
        double xh = -(x - sun[0]), yh = -(y - sun[1]), zh = z - sun[2];
        double l = Math.toDegrees(Math.atan2(yh, xh));
        if (l < 0) l += 360;
        double r = Math.sqrt(xh * xh + yh * yh + zh * zh);
        return new double[] {l, r > 0 ? Math.toDegrees(Math.asin(zh / r)) : 0, r};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] normalize(double[] a) {
        double n = Math.sqrt(dot(a, a));
        return n > 0 ? new double[] {a[0] / n, a[1] / n, a[2] / n} : new double[] {0, 0, 1};
    }

    /** Any unit vector perpendicular to {@code n}. */
    static double[] perpendicular(double[] n) {
        double[] t = Math.abs(n[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0};
        return normalize(cross(n, t));
    }

    /**
     * Inverse of the symmetric matrix (xx, xy, xz, yy, yz, zz) written into {@code out} in the same
     * layout; returns the determinant of the input.
     */
    static double invertSymmetric(double xx, double xy, double xz, double yy, double yz, double zz, double[] out) {
        double c00 = yy * zz - yz * yz;
        double c01 = xz * yz - xy * zz;
        double c02 = xy * yz - xz * yy;
        double det = xx * c00 + xy * c01 + xz * c02;
        double inv = 1.0 / det;
        out[0] = c00 * inv;
        out[1] = c01 * inv;
        out[2] = c02 * inv;
        out[3] = (xx * zz - xz * xz) * inv;
        out[4] = (xy * xz - xx * yz) * inv;
        out[5] = (xx * yy - xy * xy) * inv;
        return det;
    }

    static double smoothstep(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
