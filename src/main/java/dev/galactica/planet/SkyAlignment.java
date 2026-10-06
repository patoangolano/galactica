package dev.galactica.planet;

/**
 * Rotation from inertial directions (Minecraft axes of the galactic frame) to the sky of a surface
 * dimension. The surface keeps vanilla's day cycle, so instead of placing the star where geometry
 * says, the whole celestial sphere is turned until the star sits on vanilla's sun and the body's
 * spin axis lies along vanilla's celestial pole (−Z, about which the sun turns). Everything else in
 * the sky (planets, moons, the galaxy) keeps its true angle to the star.
 */
public final class SkyAlignment {
    private SkyAlignment() {}

    /** Vanilla sun direction for a sun angle in radians: SkyRenderer turns (0, 1, 0) by X(angle) then Y(−90°). */
    public static double[] vanillaSun(double sunAngle) {
        return new double[] {-Math.sin(sunAngle), Math.cos(sunAngle), 0};
    }

    /**
     * Row-major 3×3 rotation taking {@code starDir} exactly to {@code sunDir} and the spin axis as
     * close as possible to the pole −Z. Inputs need not be unit length.
     */
    public static double[] matrix(double[] starDir, double[] spinAxis, double[] sunDir) {
        double[] e1 = BodyFrame.normalize(starDir.clone());
        double[] a = orthogonal(spinAxis, e1, new double[] {0, 1, 0});
        double[] e2 = BodyFrame.cross(a, e1);
        double[] s1 = BodyFrame.normalize(sunDir.clone());
        double[] sa = orthogonal(new double[] {0, 0, -1}, s1, new double[] {1, 0, 0});
        double[] s2 = BodyFrame.cross(sa, s1);
        // M = s1·e1ᵀ + s2·e2ᵀ + sa·aᵀ: both triples are right-handed (e1 × e2 = a), so M is a proper rotation.
        double[] m = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) m[r * 3 + c] = s1[r] * e1[c] + s2[r] * e2[c] + sa[r] * a[c];
        return m;
    }

    public static double[] apply(double[] m, double[] v) {
        return new double[] {
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    /** Unit vector along {@code v} minus its component on unit {@code e}; {@code fallback} when v ∥ e. */
    private static double[] orthogonal(double[] v, double[] e, double[] fallback) {
        double d = BodyFrame.dot(v, e);
        double[] o = {v[0] - d * e[0], v[1] - d * e[1], v[2] - d * e[2]};
        if (BodyFrame.dot(o, o) < 1e-12) {
            d = BodyFrame.dot(fallback, e);
            o = new double[] {fallback[0] - d * e[0], fallback[1] - d * e[1], fallback[2] - d * e[2]};
        }
        return BodyFrame.normalize(o);
    }
}
