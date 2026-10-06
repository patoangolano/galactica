package dev.galactica.deepsky;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link NebulaField} marched on a coarser angular grid than the image and interpolated.
 *
 * <p>A field seen from inside or close by (Barnard's Loop around Orion, M42 from the Trapezium)
 * covers much of the sky with structure that is, after level of detail, several pixels wide. The
 * view then marches it once per node of a longitude–latitude grid {@code factor} times coarser
 * than the image, and each image pixel blends its four nearest nodes bilinearly: up to 16× fewer
 * marches for the same picture.
 *
 * <p>Each node folds its march into {@value #BINS} events, one per third of the bounding chord
 * (merged exactly: a merged event carries E = e_A / h(a_B) + e_B · h(a_A) per channel, so that its
 * light behind its own half-extinction is what the two separate events gave). Neighbouring nodes
 * have nearly the same chord, so bin k of each corresponds and the events are interpolated
 * element-wise: the light a bin sends out, L = E · h(a), and its A_V linearly, the distance
 * weighted by light and dust; E is rebuilt from the interpolated L and A_V. (Interpolating E
 * itself would blow up next to opaque dust, where E carries 1/h of a large column.)
 *
 * <p>Nodes are computed lazily in 16 × 16 tiles on whichever thread asks first; only the tiles
 * under the field's cap are ever built. Thread-safe.
 */
final class CoarseFieldGrid {
    static final int BINS = 3, STRIDE = BINS * 5, TILE = 16;

    private final NebulaField field;
    private final double ox, oy, oz;
    private final double nodeAngle, scale, pixelAngle;
    private final int nLon, nLat, tilesLon;
    private final ConcurrentHashMap<Integer, double[]> tiles = new ConcurrentHashMap<>();

    CoarseFieldGrid(NebulaField field, double ox, double oy, double oz, double imagePixelAngle, int factor, double scale) {
        this.field = field;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.scale = scale;
        this.pixelAngle = imagePixelAngle * factor;
        this.nLon = Math.max(8, (int) Math.ceil(2 * Math.PI / pixelAngle));
        this.nLat = Math.max(4, nLon / 2);
        this.nodeAngle = 2 * Math.PI / nLon;
        this.tilesLon = (nLon + TILE - 1) / TILE;
    }

    /**
     * Coarse-grid factor for a field seen from an observer at distance {@code dist} from its
     * centre: its finest scale must span at least two image pixels at the nearest point of the
     * field (or at the field's own radius when the observer is inside), and at most 4.
     */
    static int factorFor(NebulaField f, double dist, double pixelAngle) {
        if (!(pixelAngle > 0)) return 1;
        double near = Math.max(dist - f.radius, 0.35 * f.radius);
        double proj = f.detailPc() / near;
        int k = (int) Math.floor(proj / (2 * pixelAngle));
        return Math.max(1, Math.min(4, k));
    }

    /** Appends this field's interpolated events for unit direction d (galactocentric) to {@code ev}. */
    void events(double dx, double dy, double dz, DeepSkyView.RayEvents ev) {
        double lon = Math.atan2(dy, dx), lat = Math.asin(Math.max(-1, Math.min(1, dz)));
        double u = (lon + Math.PI) / nodeAngle - 0.5, v = (lat + Math.PI / 2) / nodeAngle - 0.5;
        int i0 = (int) Math.floor(u), j0 = (int) Math.floor(v);
        double fu = u - i0, fv = v - j0;
        double[] acc = ACC.get();
        java.util.Arrays.fill(acc, 0);
        for (int k = 0; k < 4; k++) {
            int i = Math.floorMod(i0 + (k & 1), nLon);
            int j = Math.max(0, Math.min(nLat - 1, j0 + (k >> 1)));
            double w = ((k & 1) == 0 ? 1 - fu : fu) * ((k >> 1) == 0 ? 1 - fv : fv);
            if (w <= 0) continue;
            double[] tile = tile(i / TILE, j / TILE);
            int base = ((j % TILE) * TILE + (i % TILE)) * STRIDE;
            for (int b = 0; b < BINS; b++) {
                int o = base + b * 5;
                double r = tile[o + 1], g = tile[o + 2], bl = tile[o + 3], av = tile[o + 4];  // r, g, b are L
                double m = r + g + bl + 10 * av;
                if (m <= 0) continue;
                int a = b * 6;
                acc[a] += w * m * tile[o];
                acc[a + 1] += w * r;
                acc[a + 2] += w * g;
                acc[a + 3] += w * bl;
                acc[a + 4] += w * av;
                acc[a + 5] += w * m;
            }
        }
        for (int b = 0; b < BINS; b++) {
            int a = b * 6;
            if (acc[a + 5] <= 0) continue;
            double tau = Math.min(1_000, acc[a + 4]) * NebulaField.MAG_TO_TAU;
            ev.add(acc[a] / acc[a + 5], acc[a + 1] * Math.exp(0.5 * tau * NebulaField.KR),
                    acc[a + 2] * Math.exp(0.5 * tau * NebulaField.KG), acc[a + 3] * Math.exp(0.5 * tau * NebulaField.KB), acc[a + 4]);
        }
    }

    private double[] tile(int ti, int tj) {
        int key = tj * tilesLon + ti;
        double[] t = tiles.get(key);
        if (t != null) return t;
        return tiles.computeIfAbsent(key, k -> build(ti, tj));
    }

    private double[] build(int ti, int tj) {
        double[] out = new double[TILE * TILE * STRIDE];
        DeepSkyView.RayEvents ev = new DeepSkyView.RayEvents();
        double[] ch = new double[2 * NebulaField.MAX_INTERVALS];
        for (int jj = 0; jj < TILE; jj++) {
            int j = tj * TILE + jj;
            if (j >= nLat) break;
            double lat = (j + 0.5) * nodeAngle - Math.PI / 2;
            for (int ii = 0; ii < TILE; ii++) {
                int i = ti * TILE + ii;
                if (i >= nLon) break;
                double lon = (i + 0.5) * nodeAngle - Math.PI;
                double c = Math.cos(lat);
                double dx = c * Math.cos(lon), dy = c * Math.sin(lon), dz = Math.sin(lat);
                if (!field.chord(ox, oy, oz, dx, dy, dz, ch)) continue;
                double t0 = ch[0], t1 = ch[1];
                ev.clear();
                field.march(ox, oy, oz, dx, dy, dz, pixelAngle, DeepSkyView.jitter(dx, dy, dz), scale, ev);
                int base = (jj * TILE + ii) * STRIDE;
                for (int e = 0; e < ev.count; e++) {
                    int b = (int) Math.floor((ev.t[e] - t0) / (t1 - t0) * BINS);
                    b = Math.max(0, Math.min(BINS - 1, b));
                    merge(out, base + b * 5, ev.t[e], ev.r[e], ev.g[e], ev.b[e], ev.av[e]);
                }
                // E → L = E · h(a): the light each bin sends towards the observer.
                for (int bb = 0; bb < BINS; bb++) {
                    int o = base + bb * 5;
                    double tau = Math.min(1_000, out[o + 4]) * NebulaField.MAG_TO_TAU;
                    out[o + 1] *= Math.exp(-0.5 * tau * NebulaField.KR);
                    out[o + 2] *= Math.exp(-0.5 * tau * NebulaField.KG);
                    out[o + 3] *= Math.exp(-0.5 * tau * NebulaField.KB);
                }
            }
        }
        return out;
    }

    /** Folds event (t, e, a) behind the event already at {@code o} (see the class comment). */
    static void merge(double[] s, int o, double t, double r, double g, double b, double a) {
        double aA = s[o + 4];
        double wA = s[o + 1] + s[o + 2] + s[o + 3] + 10 * aA, wB = r + g + b + 10 * a;
        if (wA <= 0) {
            s[o] = t;
            s[o + 1] = r;
            s[o + 2] = g;
            s[o + 3] = b;
            s[o + 4] = a;
            return;
        }
        double tauA = Math.min(1_000, aA) * NebulaField.MAG_TO_TAU, tauB = Math.min(1_000, a) * NebulaField.MAG_TO_TAU;
        s[o + 1] = s[o + 1] * Math.exp(0.5 * tauB * NebulaField.KR) + r * Math.exp(-0.5 * tauA * NebulaField.KR);
        s[o + 2] = s[o + 2] * Math.exp(0.5 * tauB * NebulaField.KG) + g * Math.exp(-0.5 * tauA * NebulaField.KG);
        s[o + 3] = s[o + 3] * Math.exp(0.5 * tauB * NebulaField.KB) + b * Math.exp(-0.5 * tauA * NebulaField.KB);
        s[o] = (s[o] * wA + t * wB) / (wA + wB);
        s[o + 4] = aA + a;
    }

    private static final ThreadLocal<double[]> ACC = ThreadLocal.withInitial(() -> new double[BINS * 6]);
}
