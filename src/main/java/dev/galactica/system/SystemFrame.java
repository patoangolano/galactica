package dev.galactica.system;

import dev.galactica.science.SeedRng;
import dev.galactica.star.Star;

/**
 * Orientation of a planetary system's reference plane in the galactic frame. {@link Orbit} works in
 * the system's own frame (x, y = plane normal, z); this rotates that frame onto galactocentric axes
 * (x, y, z = north galactic pole).
 *
 * <p>Sol uses the real J2000 ecliptic, derived from the IAU/Hipparcos equatorial → galactic matrix
 * (ESA 1997, vol. 1 §1.5.3) and the IAU 2006 obliquity ε = 23.4392911°: the vernal equinox is the
 * reference x-axis and the north ecliptic pole (l = 96.38°, b = 29.81°) the normal, tilted 60.19°
 * from galactic north. Every other system is oriented isotropically at random, as observed for
 * exoplanet systems relative to the Galactic plane, seeded from {@link Star#systemSeed()}.
 */
public final class SystemFrame {
    private SystemFrame() {}

    public static final double OBLIQUITY_J2000_DEG = 23.4392911;

    /** Rows map ICRS/J2000 equatorial unit vectors to heliocentric galactic (x → GC, y → l = 90°, z → NGP). */
    static final double[][] EQ_TO_GAL = {
            {-0.0548755604, -0.8734370902, -0.4838350155},
            {+0.4941094279, -0.4448296300, +0.7469822445},
            {-0.8676661490, -0.1980763734, +0.4559837762}};

    private static final long ORIENTATION_SALT = 0x0B1E_C7A7_10E5L;
    private static final int CACHE_SIZE = 64;

    /** Orthonormal right-handed basis in galactocentric axes: ex = reference direction, ez = plane normal. */
    record Basis(long starId, double[] ex, double[] ey, double[] ez) {}

    private static final Basis SOL = solBasis();
    // Direct-mapped by id; entries are immutable so racy reads from render and server threads are benign.
    private static final Basis[] CACHE = new Basis[CACHE_SIZE];

    /** System-frame vector as {@link Orbit#positionAt} writes it (x, y = normal, z) → galactic-frame metres. */
    public static double[] toGalactic(Star s, double[] systemVec) {
        double[] out = new double[3];
        toGalactic(s, systemVec[0], systemVec[1], systemVec[2], out);
        return out;
    }

    /** Allocation-free form for per-body, per-frame use. */
    public static void toGalactic(Star s, double sx, double sy, double sz, double[] out) {
        Basis b = basis(s);
        // Orbit's (x, y, z) is the astronomical (x, −z, y): undo that before applying the basis.
        double ax = sx, ay = -sz, az = sy;
        out[0] = b.ex[0] * ax + b.ey[0] * ay + b.ez[0] * az;
        out[1] = b.ex[1] * ax + b.ey[1] * ay + b.ez[1] * az;
        out[2] = b.ex[2] * ax + b.ey[2] * ay + b.ez[2] * az;
    }

    /** Unit normal of the system's reference plane (orbital angular momentum direction) in galactic axes. */
    public static double[] normalGalactic(Star s) {
        return basis(s).ez.clone();
    }

    /** Shared cached instance with mutable arrays: package-private so callers cannot corrupt it. */
    static Basis basis(Star s) {
        if (s.id() == SOL.starId) return SOL;
        int slot = (int) (SeedRng.mix(s.id()) & (CACHE_SIZE - 1));
        Basis b = CACHE[slot];
        if (b == null || b.starId != s.id()) {
            b = randomBasis(s.id(), s.systemSeed());
            CACHE[slot] = b;
        }
        return b;
    }

    /** Uniform on SO(3): normal uniform on the sphere, reference axis uniform in the plane. */
    static Basis randomBasis(long id, long systemSeed) {
        SeedRng rng = new SeedRng(SeedRng.hash(systemSeed, ORIENTATION_SALT));
        double cz = 2 * rng.nextDouble() - 1, phi = 2 * Math.PI * rng.nextDouble(), psi = 2 * Math.PI * rng.nextDouble();
        double sz = Math.sqrt(Math.max(0, 1 - cz * cz));
        double[] n = {sz * Math.cos(phi), sz * Math.sin(phi), cz};
        // Any fixed perpendicular, then rotated by ψ about n.
        double[] u = normalize(cross(Math.abs(n[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0}, n));
        double[] v = cross(n, u);
        double c = Math.cos(psi), s = Math.sin(psi);
        double[] ex = {c * u[0] + s * v[0], c * u[1] + s * v[1], c * u[2] + s * v[2]};
        return new Basis(id, ex, cross(n, ex), n);
    }

    private static Basis solBasis() {
        double e = Math.toRadians(OBLIQUITY_J2000_DEG);
        double[] pole = eqToGalactocentric(0, -Math.sin(e), Math.cos(e));
        // The published matrix is orthonormal only to ~1e-10; re-orthogonalise the equinox on the pole.
        double[] q = eqToGalactocentric(1, 0, 0);
        double d = q[0] * pole[0] + q[1] * pole[1] + q[2] * pole[2];
        double[] equinox = normalize(new double[] {q[0] - d * pole[0], q[1] - d * pole[1], q[2] - d * pole[2]});
        return new Basis(dev.galactica.galaxy.StarCatalog.SOL.id(), equinox, cross(pole, equinox), pole);
    }

    /** Equatorial direction → galactocentric axes; as in StarCatalog.galacticToGalactocentric, x and y flip for directions. */
    private static double[] eqToGalactocentric(double x, double y, double z) {
        double[] h = new double[3];
        for (int i = 0; i < 3; i++) h[i] = EQ_TO_GAL[i][0] * x + EQ_TO_GAL[i][1] * y + EQ_TO_GAL[i][2] * z;
        return normalize(new double[] {-h[0], -h[1], h[2]});
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] a) {
        double r = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[] {a[0] / r, a[1] / r, a[2] / r};
    }
}
