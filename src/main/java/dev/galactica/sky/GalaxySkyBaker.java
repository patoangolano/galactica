package dev.galactica.sky;

import dev.galactica.deepsky.DeepSky;
import dev.galactica.deepsky.DeepSkyView;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.science.Noise;

/**
 * Bakes the diffuse light of the Milky Way as seen from an arbitrary point in the galaxy into an
 * HDR cubemap, by ray-marching the {@link GalaxyModel} along every pixel direction:
 *
 * <pre>  I_c(dir) = ∫ j_c(s) · exp(−τ_c(s)) ds,   τ_c(s) = ∫ k_c·A_V(s') ds' </pre>
 *
 * where j is the luminosity density of the old disk, young arm population, bulge and halo (each
 * with its own colour), plus Hα emission from H II regions, and τ is dust optical depth with the
 * Cardelli (1989) R_V = 3.1 extinction law per channel (blue dimmed more than red → reddening).
 * Dust density is modulated by fBm noise, which carves filaments and rifts like the Great Rift.
 *
 * <p>Individually rendered stars (closer than {@link #START_PC}) are excluded so light is not
 * counted twice. Output is linear sRGB in L☉ pc⁻² sr⁻¹ (exposure is applied by the renderer).
 * Runs off-thread. {@link #bakeEquirect} marches the smooth galaxy at half resolution and composites
 * the sharp deep-sky volumes at full resolution (see {@code bakeEquirectTwoScale}): a 2048 × 1024
 * bake takes ~2.2–2.5 s on 8 cores from the Sun (~3.7 s from inside the Orion Nebula, ~4.7 s cold).
 * A full-resolution march costs ~40 µs per ray (~10 s for the same image); the cube-face
 * {@link #bake} still uses it.
 *
 * <p>Deep-sky objects ({@link DeepSky}: nebulae, dark clouds, supernova remnants, the glow of
 * star clusters, the Magellanic Clouds and Andromeda) are Gaussian volumes integrated analytically
 * along each ray by a {@link DeepSkyView} prepared once per bake; their emission and extinction
 * are merged into the march in distance order. Within {@link DeepSky#VIEW_RADIUS_PC} discrete H II
 * regions replace the statistical H II glow of the march; across the band from
 * {@link DeepSky#HII_CROSSFADE_START_PC} to the view radius the two cross-fade with weights that
 * sum to one ({@link DeepSky#statisticalHiiWeight}), so no H II light is counted twice.
 */
public final class GalaxySkyBaker {
    public static final double START_PC = 25.0;
    public static final double END_PC = 60_000.0;
    private static final int STEPS = 160;
    private static final int MAX_STEPS = 700;
    private static final double GROWTH = Math.pow(END_PC / START_PC, 1.0 / STEPS) - 1;
    /** Distance along a ray over which the smooth structural model is held constant. */
    private static final double MODEL_SPACING_PC = 40.0;

    /** A_λ / A_V for the R, G, B channel centres (~610, 550, 465 nm), CCM89 with R_V = 3.1. */
    private static final double[] EXTINCTION_RATIO = {0.80, 1.00, 1.30};
    private static final double MAG_TO_TAU = 0.4 * Math.log(10); // τ = 0.921 · A

    // Linear-sRGB colours of each population (blackbody of the population's light-weighted Teff).
    private static final double[] OLD_DISK = {1.00, 0.86, 0.72};   // ~4 800 K
    private static final double[] YOUNG = {0.70, 0.80, 1.00};      // ~12 000 K
    private static final double[] BULGE = {1.00, 0.78, 0.58};      // ~4 200 K
    private static final double[] H_ALPHA = {1.00, 0.18, 0.30};    // 656 nm with a little Hβ

    /** Mean V-band luminosity per star (L☉) for each population. Local disk: ~0.05 L☉ pc⁻³ at 0.095 stars pc⁻³. */
    private static final double L_OLD = 0.55, L_BULGE = 0.4, L_YOUNG = 60.0, L_HALPHA = 4.0;

    private final long seed;
    private final DeepSky deepSky;

    public GalaxySkyBaker(long seed) {
        this.seed = seed;
        this.deepSky = DeepSky.of(seed);
    }

    /** Deep-sky volumes seen from a point, for images whose pixels subtend {@code pixelAngle} radians. */
    public DeepSkyView prepare(double ox, double oy, double oz, double pixelAngle) {
        return deepSky.view(ox, oy, oz, pixelAngle);
    }

    /**
     * Fills {@code out} (6 faces × size × size × 3 floats, face order +X, −X, +Y, −Y, +Z, −Z in
     * Minecraft axes) for an observer at galactocentric (ox, oy, oz) parsecs.
     */
    public void bake(double ox, double oy, double oz, int size, float[] out) {
        DeepSkyView view = prepare(ox, oy, oz, 0.5 * Math.PI / size);
        double[] dir = new double[3];
        double[] rgb = new double[3];
        for (int face = 0; face < 6; face++) {
            for (int v = 0; v < size; v++) {
                for (int u = 0; u < size; u++) {
                    CubeMath.faceDirection(face, (u + 0.5) / size * 2 - 1, (v + 0.5) / size * 2 - 1, dir);
                    // Minecraft (x, y, z) with y up → galactic frame (x, −z, y) with z towards the NGP.
                    march(view, ox, oy, oz, dir[0], -dir[2], dir[1], rgb);
                    int i = ((face * size + v) * size + u) * 3;
                    out[i] = (float) rgb[0];
                    out[i + 1] = (float) rgb[1];
                    out[i + 2] = (float) rgb[2];
                }
            }
        }
    }

    /**
     * Equirectangular bake in Minecraft axes ({@link #equirectDirection}), rows in parallel.
     * {@code out} holds width × height × 3 floats.
     */
    public void bakeEquirect(double ox, double oy, double oz, int width, int height, float[] out) {
        bakeEquirectRadiance(ox, oy, oz, width, height, out);
        compressHighlights(out, width, height, true);
    }

    /**
     * {@link #bakeEquirect} without {@link #compressHighlights}: the physical radiance of every
     * pixel (L☉ pc⁻² sr⁻¹), for measurements and tests.
     */
    public void bakeEquirectRadiance(double ox, double oy, double oz, int width, int height, float[] out) {
        bakeEquirectRadiance(ox, oy, oz, width, height, out, MC_TO_GALACTIC);
    }

    /**
     * Minecraft axes (x, y up, z) → galactic frame (x, −z, y): the layout of the space sky. Rows map
     * an equirect direction ({@link #equirectDirection}) to the galactocentric ray direction.
     */
    public static final double[][] MC_TO_GALACTIC = {{1, 0, 0}, {0, 0, -1}, {0, 1, 0}};

    /**
     * Equirectangular radiance bake whose pixel directions ({@link #equirectDirection}) are rotated
     * into galactocentric axes by {@code toGalactic} (rows of a rotation matrix): any sky frame,
     * e.g. vanilla's star buffer for the Overworld sky, gets the two-scale bake.
     */
    public void bakeEquirectRadiance(double ox, double oy, double oz, int width, int height, float[] out, double[][] toGalactic) {
        DeepSkyView view = prepare(ox, oy, oz, 2 * Math.PI / width);
        if (width % 2 != 0 || height % 2 != 0 || width < 8 || height < 8) {
            java.util.stream.IntStream.range(0, height).parallel().forEach(j -> {
                double[] dir = new double[3];
                double[] rgb = new double[3];
                double[] g = new double[3];
                for (int i = 0; i < width; i++) {
                    equirectDirection((i + 0.5) / width, (j + 0.5) / height, dir);
                    rotate(toGalactic, dir, g);
                    march(view, ox, oy, oz, g[0], g[1], g[2], rgb);
                    int k = (j * width + i) * 3;
                    out[k] = (float) rgb[0];
                    out[k + 1] = (float) rgb[1];
                    out[k + 2] = (float) rgb[2];
                }
            });
            return;
        }
        bakeEquirectTwoScale(view, ox, oy, oz, width, height, out, toGalactic);
    }

    private static void rotate(double[][] m, double[] v, double[] out) {
        out[0] = m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2];
        out[1] = m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2];
        out[2] = m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2];
    }

    /** Angular scale (rad) of the local adaptation in {@link #compressHighlights}: 1.2°. */
    public static final double ADAPT_SIGMA = Math.toRadians(1.2);
    /** Strength of the highlight compression: a region γ-compressed as (mean / threshold)^−γ. */
    public static final double ADAPT_GAMMA = 0.6;

    /**
     * Local adaptation (dodging and burning) of an equirectangular HDR sky, anchored to its own
     * 99th-percentile luminance. See {@link #compressHighlights(float[], int, int, double, boolean, double)}.
     */
    public static void compressHighlights(float[] hdr, int w, int h, boolean equirect) {
        int n = w * h;
        int stride = Math.max(1, n / 200_000);
        double[] lum = new double[(n + stride - 1) / stride];
        int m = 0;
        for (int p = 0; p < n; p += stride) lum[m++] = 0.2126 * hdr[p * 3] + 0.7152 * hdr[p * 3 + 1] + 0.0722 * hdr[p * 3 + 2];
        java.util.Arrays.sort(lum, 0, m);
        double p99 = lum[Math.min(m - 1, (int) (m * 0.99))];
        compressHighlights(hdr, w, h, 2 * Math.PI / w, equirect, p99);
    }

    /**
     * Brings bright extended regions (nebulae seen from nearby, the bulge from inside it) into a
     * displayable range while keeping their internal contrast, the way astrophotographers
     * composite exposures of M42: every pixel is scaled by (Ŷ/T)^−γ when its surroundings Ŷ (the
     * luminance blurred over {@link #ADAPT_SIGMA}) exceed the threshold T, γ = {@link #ADAPT_GAMMA}.
     * Structure finer than the blur (dark lanes, filaments, fronts) keeps its contrast, while the
     * region as a whole is compressed. The surroundings used are min(Ŷ, max(T, 4Y)), so a faint
     * pixel next to a bright region is never darkened (no dark halo) and the result stays
     * monotonic in Y. Pixels in regions below T are untouched: the Milky Way band is unchanged.
     * Display processing only; radiance is kept by {@link #bakeEquirectRadiance}.
     *
     * @param pixelAngle angular size of a pixel (rad), sets the blur in pixels
     * @param wrapX      the image wraps horizontally (equirectangular; also widens the blur by
     *                   1/cos latitude so it stays {@link #ADAPT_SIGMA} on the sky)
     * @param threshold  luminance T above which regions are compressed
     */
    public static void compressHighlights(float[] hdr, int w, int h, double pixelAngle, boolean wrapX, double threshold) {
        if (!(threshold > 0)) return;
        double sigmaPx = ADAPT_SIGMA / pixelAngle;
        int f = Math.max(1, (int) Math.floor(sigmaPx / 2.0));
        int lw = Math.max(1, w / f), lh = Math.max(1, h / f);
        double[] low = new double[lw * lh];
        boolean any = false;
        for (int j = 0; j < lh; j++) {
            for (int i = 0; i < lw; i++) {
                double s = 0;
                int c = 0;
                for (int y = j * f; y < Math.min(h, (j + 1) * f); y++) {
                    for (int x = i * f; x < Math.min(w, (i + 1) * f); x++) {
                        int p = (y * w + x) * 3;
                        s += 0.2126 * hdr[p] + 0.7152 * hdr[p + 1] + 0.0722 * hdr[p + 2];
                        c++;
                    }
                }
                low[j * lw + i] = c > 0 ? s / c : 0;
                if (low[j * lw + i] > threshold) any = true;
            }
        }
        if (!any) return;
        double sl = sigmaPx / f;
        // Separable Gaussian: horizontal (wrapping, widened towards the poles), then vertical.
        double[] tmp = new double[lw * lh];
        for (int j = 0; j < lh; j++) {
            double sx = sl;
            if (wrapX) {
                double lat = Math.PI / 2 - (j + 0.5) / lh * Math.PI;
                sx = Math.min(lw / 4.0, sl / Math.max(0.05, Math.cos(lat)));
            }
            int r = (int) Math.ceil(3 * sx);
            double[] k = gaussian(sx, r);
            for (int i = 0; i < lw; i++) {
                double s = 0, ws = 0;
                for (int d = -r; d <= r; d++) {
                    int x = i + d;
                    if (wrapX) x = Math.floorMod(x, lw);
                    else if (x < 0 || x >= lw) continue;
                    s += k[d + r] * low[j * lw + x];
                    ws += k[d + r];
                }
                tmp[j * lw + i] = s / ws;
            }
        }
        int rv = (int) Math.ceil(3 * sl);
        double[] kv = gaussian(sl, rv);
        for (int i = 0; i < lw; i++) {
            for (int j = 0; j < lh; j++) {
                double s = 0, ws = 0;
                for (int d = -rv; d <= rv; d++) {
                    int y = j + d;
                    if (y < 0 || y >= lh) continue;
                    s += kv[d + rv] * tmp[y * lw + i];
                    ws += kv[d + rv];
                }
                low[j * lw + i] = s / ws;
            }
        }
        double t = threshold;
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            double v = (y + 0.5) / f - 0.5;
            int y0 = (int) Math.floor(v);
            double fy = v - y0;
            int ya = Math.max(0, Math.min(lh - 1, y0)), yb = Math.max(0, Math.min(lh - 1, y0 + 1));
            for (int x = 0; x < w; x++) {
                double u = (x + 0.5) / f - 0.5;
                int x0 = (int) Math.floor(u);
                double fx = u - x0;
                int xa, xb;
                if (wrapX) {
                    xa = Math.floorMod(x0, lw);
                    xb = Math.floorMod(x0 + 1, lw);
                } else {
                    xa = Math.max(0, Math.min(lw - 1, x0));
                    xb = Math.max(0, Math.min(lw - 1, x0 + 1));
                }
                double yb2 = (1 - fx) * (1 - fy) * low[ya * lw + xa] + fx * (1 - fy) * low[ya * lw + xb]
                        + (1 - fx) * fy * low[yb * lw + xa] + fx * fy * low[yb * lw + xb];
                if (yb2 <= t) continue;
                int p = (y * w + x) * 3;
                double lumP = 0.2126 * hdr[p] + 0.7152 * hdr[p + 1] + 0.0722 * hdr[p + 2];
                double around = Math.min(yb2, Math.max(t, 4 * lumP));
                if (around <= t) continue;
                float g = (float) Math.pow(around / t, -ADAPT_GAMMA);
                hdr[p] *= g;
                hdr[p + 1] *= g;
                hdr[p + 2] *= g;
            }
        });
    }

    private static double[] gaussian(double sigma, int r) {
        double[] k = new double[2 * r + 1];
        for (int d = -r; d <= r; d++) k[d + r] = Math.exp(-0.5 * d * d / (sigma * sigma));
        return k;
    }

    /** Knot distances (pc) at which half-resolution rays record their running integral; the last is the total. */
    static final double[] KNOTS = {80, 200, 500, 1_200, 3_000, 8_000, Double.POSITIVE_INFINITY};
    private static final int KN = KNOTS.length, KSTRIDE = KN * 4;
    private static final int BAND = 16;

    /**
     * Two-scale bake. The diffuse galaxy is smooth at the scale of a pixel, so it is marched at half
     * resolution; each of those rays records its running radiance and green transmittance at the
     * {@link #KNOTS}. Every full-resolution pixel interpolates those curves bilinearly from its four
     * half-resolution neighbours and composites its own, sharp, deep-sky events in distance order:
     * galaxy light between two events is dimmed by the deep-sky dust in front of it, and each event
     * is dimmed by the galaxy dust (read from the interpolated transmittance curve) in front of it.
     * Work is split into bands of half-resolution rows so the curves never exist for the whole sky.
     */
    private void bakeEquirectTwoScale(DeepSkyView view, double ox, double oy, double oz, int width, int height, float[] out,
                                      double[][] toGalactic) {
        bakeTwoScale(view, ox, oy, oz, width, height, out, new DirectionGrid() {
            @Override
            public void direction(double u, double v, double[] gal) {
                double[] d = new double[3];
                equirectDirection(u, v, d);
                rotate(toGalactic, d, gal);
            }

            @Override
            public boolean wrapsU() {
                return true;
            }
        });
    }

    /**
     * A regular image grid of view directions: (u, v) ∈ [0, 1]² → unit galactocentric direction.
     * Directions must vary smoothly with (u, v) (the half-resolution galaxy is interpolated).
     */
    public interface DirectionGrid {
        void direction(double u, double v, double[] galactic);

        /** True when column u = 1 meets column u = 0 (a full turn of longitude). */
        boolean wrapsU();
    }

    /**
     * The two-scale bake of any direction grid, e.g. a gnomonic close-up of a nebula: the galaxy
     * at half resolution with its knot curves, the deep-sky volumes of {@code view} (prepared for
     * this observer at this grid's pixel scale) at full resolution. Rows run in parallel.
     */
    public void bakeGrid(DeepSkyView view, double ox, double oy, double oz, int width, int height, float[] out, DirectionGrid grid) {
        if (width % 2 != 0 || height % 2 != 0 || width < 8 || height < 8) {
            java.util.stream.IntStream.range(0, height).parallel().forEach(j -> {
                double[] g = new double[3], rgb = new double[3];
                for (int i = 0; i < width; i++) {
                    grid.direction((i + 0.5) / width, (j + 0.5) / height, g);
                    march(view, ox, oy, oz, g[0], g[1], g[2], rgb);
                    int k = (j * width + i) * 3;
                    out[k] = (float) rgb[0];
                    out[k + 1] = (float) rgb[1];
                    out[k + 2] = (float) rgb[2];
                }
            });
            return;
        }
        bakeTwoScale(view, ox, oy, oz, width, height, out, grid);
    }

    private void bakeTwoScale(DeepSkyView view, double ox, double oy, double oz, int width, int height, float[] out, DirectionGrid grid) {
        int wl = width / 2, hl = height / 2;
        boolean wrap = grid.wrapsU();
        int bands = (hl + BAND - 1) / BAND;
        java.util.stream.IntStream.range(0, bands).parallel().forEach(band -> {
            // Low-res rows needed: [jl0 - 1, jl1 + 1] clamped, for bilinear interpolation at band edges.
            int jl0 = band * BAND, jl1 = Math.min(hl - 1, jl0 + BAND - 1);
            int ja = Math.max(0, jl0 - 1), jb = Math.min(hl - 1, jl1 + 1);
            float[] curves = new float[(jb - ja + 1) * wl * KSTRIDE];
            double[] rgb = new double[3];
            double[] g = new double[3];
            for (int jl = ja; jl <= jb; jl++) {
                for (int il = 0; il < wl; il++) {
                    grid.direction((il + 0.5) / wl, (jl + 0.5) / hl, g);
                    integrate(null, true, ox, oy, oz, g[0], g[1], g[2], rgb, curves, ((jl - ja) * wl + il) * KSTRIDE);
                }
            }
            double[] c = new double[KSTRIDE];
            for (int j = 2 * jl0; j <= 2 * jl1 + 1 && j < height; j++) {
                double v = (j + 0.5) / 2 - 0.5;
                int j0 = (int) Math.floor(v);
                double fv = v - j0;
                int r0 = Math.max(0, Math.min(hl - 1, j0)) - ja, r1 = Math.max(0, Math.min(hl - 1, j0 + 1)) - ja;
                for (int i = 0; i < width; i++) {
                    double u = (i + 0.5) / 2 - 0.5;
                    int i0 = (int) Math.floor(u);
                    double fu = u - i0;
                    int c0 = wrap ? Math.floorMod(i0, wl) : Math.max(0, Math.min(wl - 1, i0));
                    int c1 = wrap ? Math.floorMod(i0 + 1, wl) : Math.max(0, Math.min(wl - 1, i0 + 1));
                    int a00 = (r0 * wl + c0) * KSTRIDE, a01 = (r0 * wl + c1) * KSTRIDE;
                    int a10 = (r1 * wl + c0) * KSTRIDE, a11 = (r1 * wl + c1) * KSTRIDE;
                    double w00 = (1 - fu) * (1 - fv), w01 = fu * (1 - fv), w10 = (1 - fu) * fv, w11 = fu * fv;
                    for (int q = 0; q < KSTRIDE; q++) {
                        c[q] = w00 * curves[a00 + q] + w01 * curves[a01 + q] + w10 * curves[a10 + q] + w11 * curves[a11 + q];
                    }
                    grid.direction((i + 0.5) / width, (j + 0.5) / height, g);
                    composite(view.trace(g[0], g[1], g[2]), c, rgb);
                    int k = (j * width + i) * 3;
                    out[k] = (float) rgb[0];
                    out[k + 1] = (float) rgb[1];
                    out[k + 2] = (float) rgb[2];
                }
            }
        });
    }

    /** Running galaxy integral at distance t, read from knot curves {E_r, E_g, E_b, T_g} (log-linear in t). */
    private static void curveAt(double[] c, double t, double[] o) {
        double prevT = START_PC, pr = 0, pg = 0, pb = 0, pt = 1;
        for (int k = 0; k < KN; k++) {
            int a = k * 4;
            double kt = KNOTS[k];
            if (t <= kt || k == KN - 1) {
                double f;
                if (t <= prevT) f = 0;
                else if (Double.isInfinite(kt)) f = 1;
                else f = Math.log(t / prevT) / Math.log(kt / prevT);
                f = Math.max(0, Math.min(1, f));
                o[0] = pr + f * (c[a] - pr);
                o[1] = pg + f * (c[a + 1] - pg);
                o[2] = pb + f * (c[a + 2] - pb);
                o[3] = pt + f * (c[a + 3] - pt);
                return;
            }
            prevT = kt;
            pr = c[a];
            pg = c[a + 1];
            pb = c[a + 2];
            pt = c[a + 3];
        }
    }

    private static final ThreadLocal<double[]> CURVE_TMP = ThreadLocal.withInitial(() -> new double[4]);

    private static void composite(DeepSkyView.RayEvents ev, double[] c, double[] rgb) {
        int last = (KN - 1) * 4;
        if (ev.count == 0) {
            rgb[0] = c[last];
            rgb[1] = c[last + 1];
            rgb[2] = c[last + 2];
            return;
        }
        double[] at = CURVE_TMP.get();
        double r = 0, g = 0, b = 0, tR = 1, tG = 1, tB = 1, er = 0, eg = 0, eb = 0;
        for (int i = 0; i < ev.count; i++) {
            curveAt(c, ev.t[i], at);
            // Galaxy light emitted since the previous event, behind the deep-sky dust met so far.
            r += (at[0] - er) * tR;
            g += (at[1] - eg) * tG;
            b += (at[2] - eb) * tB;
            er = at[0];
            eg = at[1];
            eb = at[2];
            double tgG = Math.max(1e-12, at[3]);
            double tgR = Math.pow(tgG, EXTINCTION_RATIO[0]), tgB = Math.pow(tgG, EXTINCTION_RATIO[2]);
            double dt = ev.av[i] * MAG_TO_TAU;
            double hR = Math.exp(-0.5 * dt * EXTINCTION_RATIO[0]), hG = Math.exp(-0.5 * dt), hB = Math.exp(-0.5 * dt * EXTINCTION_RATIO[2]);
            r += ev.r[i] * tgR * tR * hR;
            g += ev.g[i] * tgG * tG * hG;
            b += ev.b[i] * tgB * tB * hB;
            tR *= hR * hR;
            tG *= hG * hG;
            tB *= hB * hB;
        }
        r += (c[last] - er) * tR;
        g += (c[last + 1] - eg) * tG;
        b += (c[last + 2] - eb) * tB;
        rgb[0] = r;
        rgb[1] = g;
        rgb[2] = b;
    }

    /**
     * u ∈ [0,1) is longitude around +Y starting at −Z (north in Minecraft), v ∈ [0,1] runs from the
     * zenith (+Y) to the nadir. The sky sphere mesh uses the same mapping for its UVs.
     */
    public static void equirectDirection(double u, double v, double[] out) {
        double phi = u * 2 * Math.PI;
        double lat = Math.PI / 2 - v * Math.PI;
        double c = Math.cos(lat);
        out[0] = c * Math.sin(phi);
        out[1] = Math.sin(lat);
        out[2] = -c * Math.cos(phi);
    }

    /**
     * Integrates one ray. Steps grow geometrically (fine nearby, coarse far away) but are also capped
     * so the ray never moves more than a quarter of the local disk height per step: without that, an
     * observer outside the disk crosses the ~600 pc slab in one or two samples and the image breaks
     * into concentric aliasing rings.
     */
    public void march(double ox, double oy, double oz, double dx, double dy, double dz, double[] rgb) {
        march(null, ox, oy, oz, dx, dy, dz, rgb);
    }

    /**
     * {@link #march(double, double, double, double, double, double, double[])} with the deep-sky
     * volumes of {@code view} (prepared for this observer; null for the bare galaxy).
     */
    public void march(DeepSkyView view, double ox, double oy, double oz, double dx, double dy, double dz, double[] rgb) {
        integrate(view != null ? view.trace(dx, dy, dz) : null, view != null, ox, oy, oz, dx, dy, dz, rgb, null, 0);
    }

    /**
     * The ray integral. With {@code ev} the deep-sky events are merged in; with {@code knots} the
     * running radiance and green transmittance are recorded at {@link #KNOTS} (4 floats per knot from
     * {@code off}). {@code deepSkyNear} fades the statistical H II glow where discrete regions exist.
     */
    private void integrate(DeepSkyView.RayEvents ev, boolean deepSkyNear, double ox, double oy, double oz,
                           double dx, double dy, double dz, double[] rgb, float[] knots, int off) {
        // Transmittance per channel is tracked multiplicatively: three exponentials per step instead of six.
        double tR = 1, tG = 1, tB = 1, r = 0, g = 0, b = 0;
        int ei = 0, en = ev != null ? ev.count : 0;
        int kn = 0;
        double adz = Math.max(1e-6, Math.abs(dz));
        double s0 = START_PC;
        // The smooth structural model varies on ≥ 100 pc scales, so it is re-evaluated only after the
        // ray has moved MODEL_SPACING_PC (or a fifth of the local height scale vertically); the noise
        // that carries the fine structure is still sampled at every step.
        double sModel = Double.NEGATIVE_INFINITY, zModel = 0;
        double mDisk = 0, mBulgeHalo = 0, mYoungFrac = 0, mDust = 0;
        for (int i = 0; i < MAX_STEPS && s0 < END_PC; i++) {
            if (knots != null) {
                while (kn < KN - 1 && s0 >= KNOTS[kn]) {
                    int a = off + kn++ * 4;
                    knots[a] = (float) r;
                    knots[a + 1] = (float) g;
                    knots[a + 2] = (float) b;
                    knots[a + 3] = (float) tG;
                }
            }
            double zNow = oz + dz * s0;
            // Far from the plane only the smooth, dust-free thick disk and halo remain: steps may grow
            // up to 5x faster there (midpoint error on their exponential/power-law profiles stays < 1 %).
            double growth = GROWTH * (1 + 4 * smoothstep(800, 3000, Math.abs(zNow)));
            double ds = Math.min(s0 * growth, 0.25 * Math.max(Math.abs(zNow), 100.0) / adz);
            ds = Math.max(ds, 2.0);
            double s = s0 + 0.5 * ds;
            s0 += ds;
            // Deep-sky volumes in front of this step's midpoint: emit through the dust so far, then add their own.
            while (ei < en && ev.t[ei] < s) {
                double dt = ev.av[ei] * MAG_TO_TAU;
                double hR = Math.exp(-0.5 * dt * EXTINCTION_RATIO[0]), hG = Math.exp(-0.5 * dt), hB = Math.exp(-0.5 * dt * EXTINCTION_RATIO[2]);
                r += ev.r[ei] * tR * hR;
                g += ev.g[ei] * tG * hG;
                b += ev.b[ei] * tB * hB;
                tR *= hR * hR;
                tG *= hG * hG;
                tB *= hB * hB;
                ei++;
            }
            double x = ox + dx * s, y = oy + dy * s, z = oz + dz * s;
            if (Math.abs(z) > 12_000 || x * x + y * y > 9.0e8) {
                if (dz * z > 0) break; // leaving the galaxy for good
                continue;
            }
            if (s - sModel >= MODEL_SPACING_PC || Math.abs(z - zModel) > 0.2 * Math.max(Math.abs(z), 50.0)) {
                double total = GalaxyModel.starDensity(x, y, z);
                double bulge = GalaxyModel.bulgeDensity(x, y, z);
                double halo = GalaxyModel.haloDensity(x, y, z);
                mDisk = Math.max(0, total - bulge - halo);
                mBulgeHalo = bulge + halo;
                mYoungFrac = GalaxyModel.youngFraction(x, y, z);
                mDust = GalaxyModel.dustExtinctionPerPc(x, y, z);
                sModel = s;
                zModel = z;
            }
            double disk = mDisk;
            double young = mYoungFrac * disk;

            // Molecular clouds: dust is filamentary, so fBm on ~120 pc scales is cubed into thin dark lanes
            // (mean factor ≈ 1, but most of the volume is clear and a few clouds are opaque).
            double av = mDust;
            if (av > 1e-7) {
                double n = 0.5 + 0.5 * Noise.fbm(seed, x / 120.0, y / 120.0, z / 45.0, 5);
                av *= 0.08 + 6.5 * n * n * n;
            }
            // Star clouds: the disk's light is clumpy on ~400 pc scales (Scutum and Sagittarius star clouds).
            if (disk > 1e-6) {
                double c = 0.5 + 0.5 * Noise.fbm(seed ^ 0xC10DL, x / 400.0, y / 400.0, z / 200.0, 3);
                disk *= 0.45 + 1.1 * c;
                young *= 0.2 + 1.6 * c;
            }
            double hii = 0;
            // Inside the deep-sky view radius discrete H II regions replace the statistical glow.
            if (young > 1e-6 && (!deepSkyNear || s > DeepSky.HII_CROSSFADE_START_PC)) {
                double n = Noise.fbm(seed ^ 0x4811L, x / 90.0, y / 90.0, z / 40.0, 3);
                hii = young * Math.max(0, n - 0.15) * 4.0;
                if (deepSkyNear) hii *= DeepSky.statisticalHiiWeight(s);
            }

            double bh = mBulgeHalo * L_BULGE, dk = disk * L_OLD, yg = young * L_YOUNG, ha = hii * L_HALPHA;
            double jr = dk * OLD_DISK[0] + bh * BULGE[0] + yg * YOUNG[0] + ha * H_ALPHA[0];
            double jg = dk * OLD_DISK[1] + bh * BULGE[1] + yg * YOUNG[1] + ha * H_ALPHA[1];
            double jb = dk * OLD_DISK[2] + bh * BULGE[2] + yg * YOUNG[2] + ha * H_ALPHA[2];

            // Emission inside the step is attenuated by half the step's own dust (midpoint rule).
            double dt = av * ds * MAG_TO_TAU;
            double hR = Math.exp(-0.5 * dt * EXTINCTION_RATIO[0]), hG = Math.exp(-0.5 * dt), hB = Math.exp(-0.5 * dt * EXTINCTION_RATIO[2]);
            // Surface brightness per steradian: j·ds / (4π) — the 1/s² of distance cancels the s² of the beam.
            double k = ds / (4 * Math.PI);
            r += jr * k * tR * hR;
            g += jg * k * tG * hG;
            b += jb * k * tB * hB;
            tR *= hR * hR;
            tG *= hG * hG;
            tB *= hB * hB;
            if (tR < 6e-6) break; // τ_R > 12
        }
        // Volumes beyond the end of the march: the halo, the Magellanic Clouds, Andromeda.
        for (; ei < en; ei++) {
            double dt = ev.av[ei] * MAG_TO_TAU;
            double hR = Math.exp(-0.5 * dt * EXTINCTION_RATIO[0]), hG = Math.exp(-0.5 * dt), hB = Math.exp(-0.5 * dt * EXTINCTION_RATIO[2]);
            r += ev.r[ei] * tR * hR;
            g += ev.g[ei] * tG * hG;
            b += ev.b[ei] * tB * hB;
            tR *= hR * hR;
            tG *= hG * hG;
            tB *= hB * hB;
        }
        if (knots != null) {
            for (; kn < KN; kn++) {
                int a = off + kn * 4;
                knots[a] = (float) r;
                knots[a + 1] = (float) g;
                knots[a + 2] = (float) b;
                knots[a + 3] = (float) tG;
            }
        }
        rgb[0] = r;
        rgb[1] = g;
        rgb[2] = b;
    }

    private static double smoothstep(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
