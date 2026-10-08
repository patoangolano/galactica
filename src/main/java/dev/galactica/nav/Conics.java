package dev.galactica.nav;

/**
 * Points along the two-body conic through a state vector, for drawing a craft's orbit or escape path on the map.
 * Pure Java, galactic frame, metres relative to the attracting body.
 */
public final class Conics {
    private Conics() {}

    /**
     * {@code n} points of the conic through position {@code r} with velocity {@code v} around a body of gravitational
     * parameter {@code mu}, by true anomaly: a closed ellipse all the way round, an open hyperbola (or parabola) over
     * the part of its branch within {@code maxRadius}. Returns x, y, z triples; empty for a degenerate (radial) state.
     */
    public static double[] points(double[] r, double[] v, double mu, int n, double maxRadius) {
        double rm = len(r);
        double[] h = cross(r, v);
        double hm = len(h);
        if (rm <= 0 || hm < 1e-9 * rm * Math.max(1, len(v))) return new double[0];
        double[] vxh = cross(v, h);
        double[] e = {vxh[0] / mu - r[0] / rm, vxh[1] / mu - r[1] / rm, vxh[2] / mu - r[2] / rm};
        double ecc = len(e);
        double p = hm * hm / mu;
        // Perifocal basis: P toward periapsis (the current radius for a circle), Q = ĥ × P.
        double[] pDir = ecc > 1e-9 ? scale(e, 1 / ecc) : scale(r, 1 / rm);
        double[] hn = scale(h, 1 / hm);
        double[] qDir = cross(hn, pDir);
        double nuMax = Math.PI;
        if (ecc >= 1) {
            // Open branch: 1 + e cos ν > 0; stop where r reaches maxRadius.
            double lim = Math.acos(-1 / ecc) - 1e-3;
            double cosAt = (p / maxRadius - 1) / ecc;
            nuMax = cosAt >= -1 && cosAt <= 1 ? Math.min(lim, Math.acos(cosAt)) : lim;
        }
        double[] out = new double[n * 3];
        for (int i = 0; i < n; i++) {
            double nu = ecc >= 1 ? -nuMax + 2 * nuMax * i / (n - 1) : 2 * Math.PI * i / (n - 1);
            double rad = p / (1 + ecc * Math.cos(nu));
            if (!(rad > 0) || rad > maxRadius) rad = maxRadius;
            double c = Math.cos(nu) * rad, s = Math.sin(nu) * rad;
            for (int k = 0; k < 3; k++) out[i * 3 + k] = c * pDir[k] + s * qDir[k];
        }
        return out;
    }

    private static double len(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    private static double[] scale(double[] a, double k) {
        return new double[] {a[0] * k, a[1] * k, a[2] * k};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
