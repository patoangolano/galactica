package dev.galactica.client.earthsky;

/**
 * The celestial sphere as an Overworld observer sees it. Pure Java (no Minecraft classes) so it can
 * be unit-tested.
 *
 * <h2>Frames</h2>
 * <ul>
 *   <li><b>Equatorial</b> (ICRS / J2000): x → vernal equinox (α = 0h, δ = 0), z → north celestial
 *       pole, y = z × x (α = 6h). Right-handed.</li>
 *   <li><b>Heliocentric galactic</b>: x → Galactic Centre (l = 0, b = 0), y → l = 90°, z → north
 *       galactic pole. Related to equatorial by the IAU 1958 / Hipparcos rotation
 *       (ESA 1997, <i>The Hipparcos and Tycho Catalogues</i>, vol. 1 §1.5.3, eq. 1.5.11), the same
 *       matrix as {@code dev.galactica.system.SystemFrame}.</li>
 *   <li><b>Galactocentric</b> (the galaxy model's frame): directions are heliocentric galactic with x
 *       and y negated (see {@code StarCatalog.galacticToGalactocentric}).</li>
 *   <li><b>Space</b> (Minecraft axes in galactica:space): galactocentric (x, y, z) ↦ (x, z, −y),
 *       see {@code Frames.galacticToMc}.</li>
 *   <li><b>Star buffer</b>: the frame vanilla's 1 500 random stars are built in. {@code SkyRenderer}
 *       draws them with {@code rotateDegrees(Axis.YP, −90)} then {@code rotate(Axis.XP, starAngle)},
 *       so world = R<sub>y</sub>(−90°)·R<sub>x</sub>(θ)·buffer.</li>
 * </ul>
 *
 * <h2>Choice of the equatorial → buffer mapping</h2>
 * The vanilla sky turns about buffer ±X, which R<sub>y</sub>(−90°) carries onto world ±Z: the
 * Overworld is an observer standing on the equator, with the celestial poles on the north (−Z) and
 * south (+Z) horizons. So the north celestial pole goes to buffer −X (→ world −Z, Minecraft north).
 * At θ = 0 (the sun's noon position) we put the vernal equinox at the zenith (buffer +Y); the third
 * axis follows from right-handedness, so the matrix is a proper rotation (det = +1) and no
 * constellation is mirrored:
 * <pre>  buffer = ( −e<sub>z</sub>,  e<sub>x</sub>,  −e<sub>y</sub> )</pre>
 * As θ grows the zenith moves west (buffer +Z → world −X), i.e. the sky turns about the north pole by
 * −θ — the sense of the real diurnal motion (Earth spins eastward, so stars rise in the east).
 */
public final class EquatorialFrame {
    private EquatorialFrame() {}

    /** IAU 2006 mean obliquity of the ecliptic at J2000 (Capitaine et al. 2003, A&amp;A 412, 567). */
    public static final double OBLIQUITY_J2000_DEG = 23.4392911;

    /** Rows map ICRS/J2000 equatorial unit vectors to heliocentric galactic axes (ESA 1997 §1.5.3). */
    static final double[][] EQ_TO_GAL = {
            {-0.0548755604, -0.8734370902, -0.4838350155},
            {+0.4941094279, -0.4448296300, +0.7469822445},
            {-0.8676661490, -0.1980763734, +0.4559837762}};

    /** Rows map equatorial unit vectors to vanilla's star-buffer frame (see class javadoc). */
    static final double[][] EQ_TO_STAR = {
            {0, 0, -1},
            {1, 0, 0},
            {0, -1, 0}};

    /** Unit vector of a J2000 right ascension and declination, both in degrees. */
    public static double[] equatorial(double raDeg, double decDeg) {
        double a = Math.toRadians(raDeg), d = Math.toRadians(decDeg);
        return new double[] {Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a), Math.sin(d)};
    }

    /** Equatorial unit vector → star-buffer frame. */
    public static double[] equatorialToStar(double[] e) {
        return mul(EQ_TO_STAR, e);
    }

    /** Star-buffer direction → equatorial. */
    public static double[] starToEquatorial(double[] b) {
        return mulT(EQ_TO_STAR, b);
    }

    /** Equatorial → heliocentric galactic (x → GC, z → NGP). */
    public static double[] equatorialToGalactic(double[] e) {
        return mul(EQ_TO_GAL, e);
    }

    /** Heliocentric galactic → equatorial. */
    public static double[] galacticToEquatorial(double[] h) {
        return mulT(EQ_TO_GAL, h);
    }

    /**
     * A direction in galactica:space Minecraft axes (what {@code Starfield.VisibleStar} carries)
     * → star-buffer frame. Space (x, y, z) → galactocentric (x, −z, y) → heliocentric (−x, z, y).
     */
    public static double[] spaceToStar(double sx, double sy, double sz) {
        double[] h = {-sx, sz, sy};
        return equatorialToStar(galacticToEquatorial(h));
    }

    /** Star-buffer direction → galactocentric direction, as {@code GalaxySkyBaker.march} takes it. */
    public static double[] starToGalactocentric(double[] b) {
        double[] h = equatorialToGalactic(starToEquatorial(b));
        return new double[] {-h[0], -h[1], h[2]};
    }

    /**
     * Where a star-buffer direction lands in the world when the sky is at angle θ (radians), exactly
     * as vanilla's pose stack composes it: R<sub>y</sub>(−90°)·R<sub>x</sub>(θ). Rotation conventions
     * are JOML's ({@code rotationX}: y' = y cos θ − z sin θ, z' = y sin θ + z cos θ;
     * {@code rotationY}: x' = x cos φ + z sin φ, z' = −x sin φ + z cos φ).
     */
    public static double[] starToWorld(double[] b, double starAngleRad) {
        double c = Math.cos(starAngleRad), s = Math.sin(starAngleRad);
        double x = b[0], y = b[1] * c - b[2] * s, z = b[1] * s + b[2] * c;
        // R_y(−90°): cos = 0, sin = −1.
        return new double[] {-z, y, x};
    }

    /**
     * Right ascension of the mean Sun (degrees) a fraction of the year after the March equinox.
     * The ecliptic longitude advances uniformly (mean Sun; the equation of centre, ≤ 1.9°, is
     * ignored) and α = atan2(cos ε · sin λ, cos λ) (Meeus 1998, <i>Astronomical Algorithms</i>, eq. 13.3
     * with β = 0).
     */
    public static double sunRightAscensionDeg(double yearFraction) {
        double lambda = 2 * Math.PI * (yearFraction - Math.floor(yearFraction));
        double e = Math.toRadians(OBLIQUITY_J2000_DEG);
        double a = Math.toDegrees(Math.atan2(Math.cos(e) * Math.sin(lambda), Math.cos(lambda)));
        return a < 0 ? a + 360 : a;
    }

    public static double determinant(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /** The composite space → star-buffer matrix, row-major (for determinant checks). */
    public static double[][] spaceToStarMatrix() {
        double[][] m = new double[3][3];
        for (int j = 0; j < 3; j++) {
            double[] col = spaceToStar(j == 0 ? 1 : 0, j == 1 ? 1 : 0, j == 2 ? 1 : 0);
            for (int i = 0; i < 3; i++) m[i][j] = col[i];
        }
        return m;
    }

    static double[] mul(double[][] m, double[] v) {
        return new double[] {
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    static double[] mulT(double[][] m, double[] v) {
        return new double[] {
                m[0][0] * v[0] + m[1][0] * v[1] + m[2][0] * v[2],
                m[0][1] * v[0] + m[1][1] * v[1] + m[2][1] * v[2],
                m[0][2] * v[0] + m[1][2] * v[1] + m[2][2] * v[2]};
    }
}
