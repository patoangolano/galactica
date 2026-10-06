package dev.galactica.deepsky;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Every deep-sky volume as seen from one observer, prepared for fast ray queries.
 *
 * <p><b>Spatial index.</b> All rays of a sky bake start at the observer, so the natural index is
 * angular: each Gaussian's 3.6 σ bounding sphere subtends a cap on the sky, and the cap is
 * rasterised into a 1° longitude–latitude grid (CSR arrays). A ray only tests the volumes listed
 * in its cell, plus the few whose bounding sphere contains the observer.
 *
 * <p><b>Integration.</b> For a Gaussian with precision matrix P = Σ⁻¹ centred at offset v from the
 * observer, along the ray x = t·d the exponent is −½(a t² − 2 b t + q) with a = dᵀPd, b = dᵀPv,
 * q = vᵀPv, so
 *
 * <pre>  ∫₀^∞ G dt = N √(2π/a) · exp(−½(q − b²/a)) · ½[1 + erf(t₀/(√2 σ_t))],  t₀ = b/a, σ_t = a^{-1/2}. </pre>
 *
 * Each volume becomes one event at its peak distance t₀ carrying emitted radiance and dust optical
 * depth, which the galaxy ray-march merges in distance order (so dust in front of a nebula hides
 * it and dust behind does not). Before inversion, Σ is widened by the pixel footprint
 * (σ_pix = ½ θ_pixel · distance); because G stays normalised this conserves each object's flux,
 * so objects much smaller than a pixel still contribute their exact light (no aliasing).
 */
public final class DeepSkyView {
    /** Bounding radius in standard deviations; e^{−½·3.6²} ≈ 1.5 × 10⁻³. */
    private static final double CAP_SIGMA = 3.6;
    /**
     * Gaussians are truncated at the bounding sphere; a smooth taper of the exponent between
     * TAPER_START and MAX_EXPONENT makes them reach exactly zero there, so even the brightest
     * volume seen from inside never shows a hard edge.
     */
    private static final double MAX_EXPONENT = 0.5 * CAP_SIGMA * CAP_SIGMA - 0.05, TAPER_START = 3.0;
    private static final int NLON = 360, NLAT = 180;
    private static final double INV_4PI = 1.0 / (4 * Math.PI);
    private static final double NORM3 = 1.0 / Math.pow(2 * Math.PI, 1.5) * Math.sqrt(2 * Math.PI);
    private static final double BLUR = 0.5;

    public final double ox, oy, oz;
    private final int n;
    // Struct of arrays: offset from the observer, precision matrix, column normalisation, payload.
    private final double[] vx, vy, vz, p00, p01, p02, p11, p12, p22, q, norm, er, eg, eb, dust;
    private final int[] cellStart, cellItems, global;

    /**
     * A view in which no cluster member is drawn as a star: every cluster glows with all of its
     * light (procedural members and anchors).
     *
     * @param pixelAngle angular size of one output pixel (rad); 0 for exact (unblurred) volumes
     * @param dustOnly   keep only absorbing volumes (for star extinction)
     */
    public DeepSkyView(double ox, double oy, double oz, List<DeepSkyObject> objects, double pixelAngle, boolean dustOnly) {
        this(ox, oy, oz, objects, pixelAngle, dustOnly, c -> c.unresolvedFraction(Double.NEGATIVE_INFINITY));
    }

    /**
     * @param pixelAngle angular size of one output pixel (rad); 0 for exact (unblurred) volumes
     * @param dustOnly   keep only absorbing volumes (for star extinction)
     * @param glowScale  per cluster, the scale of its glow volumes (the light of the members the
     *                   starfield does not draw, {@link StarCluster#unresolvedFraction})
     */
    public DeepSkyView(double ox, double oy, double oz, List<DeepSkyObject> objects, double pixelAngle, boolean dustOnly,
                       ToDoubleFunction<StarCluster> glowScale) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        int cap = 0;
        for (DeepSkyObject o : objects) cap += o.blobs().size();
        double[][] a = new double[15][cap];
        double[] capAng = new double[cap];
        double[] dirX = new double[cap], dirY = new double[cap], dirZ = new double[cap];
        boolean[] isGlobal = new boolean[cap];
        Map<StarCluster, Double> glow = new IdentityHashMap<>();
        double[] inv = new double[6];
        int k = 0;
        for (DeepSkyObject o : objects) {
            // Procedural H II regions hand over to the baker's statistical H II glow across the
            // cross-fade band; the two weights sum to one, so no H II light is counted twice.
            double objectScale = o.type == DeepSkyType.EMISSION_NEBULA && !o.catalogued
                    ? DeepSky.discreteHiiWeight(o.distanceTo(ox, oy, oz)) : 1;
            for (Blob b : o.blobs()) {
                if (dustOnly && b.dust() <= 0) continue;
                double mult = objectScale;
                if (b.glowOf() != null) {
                    StarCluster c = b.glowOf();
                    mult *= glow.computeIfAbsent(c, glowScale::applyAsDouble);
                }
                boolean emits = !dustOnly && b.emits() && mult > 1e-4;
                if (!emits && b.dust() <= 0) continue;
                double x = b.x() - ox, y = b.y() - oy, z = b.z() - oz;
                double dist = Math.sqrt(x * x + y * y + z * z);
                double s = BLUR * pixelAngle * dist, s2 = s * s;
                double sxx = b.sxx() + s2, syy = b.syy() + s2, szz = b.szz() + s2;
                double det = DeepMath.invertSymmetric(sxx, b.sxy(), b.sxz(), syy, b.syz(), szz, inv);
                if (!(det > 0)) continue;
                a[0][k] = x;
                a[1][k] = y;
                a[2][k] = z;
                for (int j = 0; j < 6; j++) a[3 + j][k] = inv[j];
                a[9][k] = x * (inv[0] * x + inv[1] * y + inv[2] * z) + y * (inv[1] * x + inv[3] * y + inv[4] * z)
                        + z * (inv[2] * x + inv[4] * y + inv[5] * z);
                a[10][k] = NORM3 / Math.sqrt(det);
                a[11][k] = emits ? b.er() * mult * INV_4PI : 0;
                a[12][k] = emits ? b.eg() * mult * INV_4PI : 0;
                a[13][k] = emits ? b.eb() * mult * INV_4PI : 0;
                a[14][k] = b.dust();
                // Gershgorin bound on the largest eigenvalue of Σ: a conservative bounding sphere.
                double lmax = Math.max(sxx + Math.abs(b.sxy()) + Math.abs(b.sxz()),
                        Math.max(syy + Math.abs(b.sxy()) + Math.abs(b.syz()), szz + Math.abs(b.sxz()) + Math.abs(b.syz())));
                double rad = CAP_SIGMA * Math.sqrt(lmax);
                if (dist <= rad * 1.05) {
                    isGlobal[k] = true;
                } else {
                    capAng[k] = Math.asin(rad / dist);
                    dirX[k] = x / dist;
                    dirY[k] = y / dist;
                    dirZ[k] = z / dist;
                }
                k++;
            }
        }
        n = k;
        vx = Arrays.copyOf(a[0], n);
        vy = Arrays.copyOf(a[1], n);
        vz = Arrays.copyOf(a[2], n);
        p00 = Arrays.copyOf(a[3], n);
        p01 = Arrays.copyOf(a[4], n);
        p02 = Arrays.copyOf(a[5], n);
        p11 = Arrays.copyOf(a[6], n);
        p12 = Arrays.copyOf(a[7], n);
        p22 = Arrays.copyOf(a[8], n);
        q = Arrays.copyOf(a[9], n);
        norm = Arrays.copyOf(a[10], n);
        er = Arrays.copyOf(a[11], n);
        eg = Arrays.copyOf(a[12], n);
        eb = Arrays.copyOf(a[13], n);
        dust = Arrays.copyOf(a[14], n);

        // Rasterise caps into the angular grid: count pass, then fill pass (CSR layout).
        int[] counts = new int[NLON * NLAT + 1];
        int nGlobal = 0;
        for (int i = 0; i < n; i++) {
            if (isGlobal[i]) nGlobal++;
            else forEachCell(dirX[i], dirY[i], dirZ[i], capAng[i], c -> counts[c + 1]++);
        }
        for (int c = 0; c < NLON * NLAT; c++) counts[c + 1] += counts[c];
        cellStart = counts.clone();
        cellItems = new int[counts[NLON * NLAT]];
        int[] fill = Arrays.copyOf(counts, NLON * NLAT);
        global = new int[nGlobal];
        int g = 0;
        for (int i = 0; i < n; i++) {
            if (isGlobal[i]) {
                global[g++] = i;
            } else {
                final int item = i;
                forEachCell(dirX[i], dirY[i], dirZ[i], capAng[i], c -> cellItems[fill[c]++] = item);
            }
        }
    }

    private interface CellSink {
        void accept(int cell);
    }

    private static void forEachCell(double dx, double dy, double dz, double ang, CellSink sink) {
        double lat = Math.asin(Math.max(-1, Math.min(1, dz)));
        double lon = Math.atan2(dy, dx);
        double pad = Math.PI / NLAT; // one bin of slack for the bin's own extent
        double lat0 = lat - ang - pad, lat1 = lat + ang + pad;
        int j0 = Math.max(0, latIndex(lat0)), j1 = Math.min(NLAT - 1, latIndex(lat1));
        double maxAbsLat = Math.max(Math.abs(lat0), Math.abs(lat1));
        boolean allLon = maxAbsLat >= Math.PI / 2 - 1e-9 || Math.sin(ang) >= Math.cos(maxAbsLat);
        double half = allLon ? Math.PI : Math.asin(Math.min(1, Math.sin(ang) / Math.cos(maxAbsLat))) + 2 * Math.PI / NLON;
        if (half >= Math.PI) allLon = true;
        for (int j = j0; j <= j1; j++) {
            if (allLon) {
                for (int i = 0; i < NLON; i++) sink.accept(j * NLON + i);
            } else {
                int i0 = (int) Math.floor((lon - half + Math.PI) / (2 * Math.PI) * NLON);
                int i1 = (int) Math.floor((lon + half + Math.PI) / (2 * Math.PI) * NLON);
                for (int i = i0; i <= i1; i++) sink.accept(j * NLON + Math.floorMod(i, NLON));
            }
        }
    }

    private static int latIndex(double lat) {
        return (int) Math.floor((lat + Math.PI / 2) / Math.PI * NLAT);
    }

    private static int cellOf(double dx, double dy, double dz) {
        int j = Math.min(NLAT - 1, Math.max(0, latIndex(Math.asin(Math.max(-1, Math.min(1, dz))))));
        int i = Math.min(NLON - 1, Math.max(0, (int) ((Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI) * NLON)));
        return j * NLON + i;
    }

    public int volumeCount() {
        return n;
    }

    /** Number of volumes a ray in this direction has to test (diagnostics and performance tests). */
    public int candidates(double dx, double dy, double dz) {
        int c = cellOf(dx, dy, dz);
        return cellStart[c + 1] - cellStart[c] + global.length;
    }

    /** Events along one ray, sorted by distance. Reused per thread. */
    public static final class RayEvents {
        public int count;
        public double[] t = new double[64], r = new double[64], g = new double[64], b = new double[64], av = new double[64];
        private int[] order = new int[64];
        private double[] tmp = new double[64];

        void clear() {
            count = 0;
        }

        void add(double tt, double rr, double gg, double bb, double aa) {
            if (count == t.length) {
                int c = count * 2;
                t = Arrays.copyOf(t, c);
                r = Arrays.copyOf(r, c);
                g = Arrays.copyOf(g, c);
                b = Arrays.copyOf(b, c);
                av = Arrays.copyOf(av, c);
                order = new int[c];
                tmp = new double[c];
            }
            t[count] = tt;
            r[count] = rr;
            g[count] = gg;
            b[count] = bb;
            av[count] = aa;
            count++;
        }

        void sort() {
            if (count < 2) return;
            if (count <= 48) {
                for (int i = 1; i < count; i++) {
                    double tt = t[i], rr = r[i], gg = g[i], bb = b[i], aa = av[i];
                    int j = i - 1;
                    while (j >= 0 && t[j] > tt) {
                        t[j + 1] = t[j];
                        r[j + 1] = r[j];
                        g[j + 1] = g[j];
                        b[j + 1] = b[j];
                        av[j + 1] = av[j];
                        j--;
                    }
                    t[j + 1] = tt;
                    r[j + 1] = rr;
                    g[j + 1] = gg;
                    b[j + 1] = bb;
                    av[j + 1] = aa;
                }
                return;
            }
            Integer[] idx = new Integer[count];
            for (int i = 0; i < count; i++) idx[i] = i;
            final double[] tk = t;
            Arrays.sort(idx, (x, y) -> Double.compare(tk[x], tk[y]));
            for (double[] arr : new double[][] {t, r, g, b, av}) {
                for (int i = 0; i < count; i++) tmp[i] = arr[idx[i]];
                System.arraycopy(tmp, 0, arr, 0, count);
            }
        }
    }

    private static final ThreadLocal<RayEvents> EVENTS = ThreadLocal.withInitial(RayEvents::new);

    /**
     * Collects the emission (radiance per channel, L☉ pc⁻² sr⁻¹) and extinction (A_V, mag) of every
     * volume along direction (dx, dy, dz) (unit, galactocentric axes), sorted by distance.
     */
    public RayEvents trace(double dx, double dy, double dz) {
        RayEvents ev = EVENTS.get();
        ev.clear();
        int c = cellOf(dx, dy, dz);
        for (int k = cellStart[c], e = cellStart[c + 1]; k < e; k++) evaluate(cellItems[k], dx, dy, dz, ev);
        for (int i : global) evaluate(i, dx, dy, dz, ev);
        ev.sort();
        return ev;
    }

    private void evaluate(int i, double dx, double dy, double dz, RayEvents ev) {
        double pdx = p00[i] * dx + p01[i] * dy + p02[i] * dz;
        double pdy = p01[i] * dx + p11[i] * dy + p12[i] * dz;
        double pdz = p02[i] * dx + p12[i] * dy + p22[i] * dz;
        double aa = dx * pdx + dy * pdy + dz * pdz;
        double bb = vx[i] * pdx + vy[i] * pdy + vz[i] * pdz;
        double ex = 0.5 * (q[i] - bb * bb / aa);
        if (ex > MAX_EXPONENT) return;
        double tStar = bb / aa;
        double sAa = Math.sqrt(aa);
        double u = tStar * sAa * 0.7071067811865476; // t₀/(√2 σ_t)
        if (u < -4) return; // entirely behind the observer
        double front = u > 4 ? 1.0 : 0.5 * (1 + DeepMath.erf(u));
        double col = norm[i] / sAa * Math.exp(-ex) * front * taper(ex);
        double sigT = 1 / sAa;
        if (sigT < 0.05 * Math.abs(tStar)) {
            ev.add(Math.max(0, tStar), er[i] * col, eg[i] * col, eb[i] * col, dust[i] * col);
            return;
        }
        // Extended along the ray (near or inside the volume): three Gauss-Hermite nodes (t0, t0 +/- sqrt(3) sigma,
        // weights 2/3, 1/6, 1/6) so overlapping emission and dust interleave smoothly instead of
        // swapping order abruptly. Nodes behind the observer are dropped and the rest renormalised.
        double w0 = 2.0 / 3, h = 1.7320508075688772 * sigT;
        double wm = tStar - h >= 0 ? 1.0 / 6 : 0, wp = 1.0 / 6;
        double w = (tStar >= 0 ? w0 : 0) + wm + wp;
        if (tStar < 0) w0 = 0;
        double k0 = col * w0 / w, km = col * wm / w, kp = col * wp / w;
        if (k0 > 0) ev.add(tStar, er[i] * k0, eg[i] * k0, eb[i] * k0, dust[i] * k0);
        if (km > 0) ev.add(tStar - h, er[i] * km, eg[i] * km, eb[i] * km, dust[i] * km);
        ev.add(Math.max(0, tStar + h), er[i] * kp, eg[i] * kp, eb[i] * kp, dust[i] * kp);
    }

    /** Deep-sky V-band extinction (mag) from the observer to distance d along unit direction u. */
    public double extinction(double ux, double uy, double uz, double d) {
        if (n == 0) return 0;
        double total = 0;
        int c = cellOf(ux, uy, uz);
        for (int k = cellStart[c], e = cellStart[c + 1]; k < e; k++) total += extinctionOne(cellItems[k], ux, uy, uz, d);
        for (int i : global) total += extinctionOne(i, ux, uy, uz, d);
        return total;
    }

    private double extinctionOne(int i, double dx, double dy, double dz, double d) {
        if (dust[i] <= 0) return 0;
        double pdx = p00[i] * dx + p01[i] * dy + p02[i] * dz;
        double pdy = p01[i] * dx + p11[i] * dy + p12[i] * dz;
        double pdz = p02[i] * dx + p12[i] * dy + p22[i] * dz;
        double aa = dx * pdx + dy * pdy + dz * pdz;
        double bb = vx[i] * pdx + vy[i] * pdy + vz[i] * pdz;
        double ex = 0.5 * (q[i] - bb * bb / aa);
        if (ex > MAX_EXPONENT) return 0;
        double sAa = Math.sqrt(aa), k = sAa * 0.7071067811865476, tStar = bb / aa;
        double frac = 0.5 * (DeepMath.erf((d - tStar) * k) - DeepMath.erf(-tStar * k));
        return dust[i] * norm[i] / sAa * Math.exp(-ex) * frac * taper(ex);
    }

    private static double taper(double ex) {
        if (ex <= TAPER_START) return 1;
        double t = (ex - TAPER_START) / (MAX_EXPONENT - TAPER_START);
        return t >= 1 ? 0 : 1 - t * t * (3 - 2 * t);
    }
}
