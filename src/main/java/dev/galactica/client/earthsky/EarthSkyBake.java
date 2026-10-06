package dev.galactica.client.earthsky;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.science.Blackbody;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;
import dev.galactica.sky.Starfield;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * The Overworld night sky, computed once from the Sun's position in the procedural galaxy and laid
 * out in vanilla's star-buffer frame ({@link EquatorialFrame}). Pure Java: runs on a worker thread,
 * and in unit tests.
 * <ul>
 *   <li>Milky Way: an equirectangular image whose pixel (u, v) is the star-buffer direction
 *       {@link GalaxySkyBaker#equirectDirection}; each pixel ray-marches the galaxy model from Sol
 *       along the matching galactocentric direction ({@link GalaxySkyBaker#march}), then
 *       {@link SkyTonemap} maps it to 8-bit. Alpha carries the light so the band can be blended over
 *       the vanilla sky colour without darkening it.</li>
 *   <li>Stars: {@link BrightStars#catalogue()} (real: the Yale Bright Star Catalogue to V = 4.5, about
 *       900 stars, with the named list's combined-light values) plus {@link Starfield#compute} from
 *       Sol for everything fainter, down to the naked-eye limit. Galaxy stars brighter than the
 *       catalogue's limit (procedural ones and {@code StarCatalog} duplicates alike) are dropped, so
 *       every star that shapes a constellation figure is real and at its real place; the procedural
 *       model only fills the faint 4.5–6.5 background.</li>
 * </ul>
 */
public final class EarthSkyBake {
    private EarthSkyBake() {}

    /** Naked-eye limit under a dark sky (Schaefer 1993, Vistas Astron. 36, 311: ~6.5 for most observers). */
    public static final double LIMITING_MAG = 6.5;
    /** The band is a faint glow to the eye; this caps how opaque its brightest pixel may be. */
    public static final double GALAXY_OPACITY = 0.9;

    /** A star sprite: unit direction in the star-buffer frame, V magnitude, linear-sRGB colour. */
    public record Sprite(String name, float bx, float by, float bz, double mag, float r, float g, float b) {}

    /** Everything the renderer needs. {@code galaxyArgb} is width × height, row-major, 0xAARRGGBB. */
    public record Baked(int width, int height, int[] galaxyArgb, List<Sprite> stars) {}

    /** Bakes on {@code pool}; the caller owns the pool (and its thread count and priority). */
    public static Baked bake(int width, int height, ForkJoinPool pool) {
        return new Baked(width, height, galaxy(width, height, pool), stars(new Galaxy(Frames.GALAXY_SEED)));
    }

    /** The Milky Way on the common pool (tests). */
    public static int[] galaxy(int width, int height) {
        return galaxy(width, height, ForkJoinPool.commonPool());
    }

    /**
     * The Milky Way as seen from Sol, in star-buffer equirect layout, with light-carrying alpha.
     *
     * <p>The galaxy is baked with its deep-sky objects (the Orion Nebula, the Magellanic Clouds,
     * Andromeda, the Coalsack and the other dark clouds that cut the band) by the two-scale bake
     * rotated into the star-buffer frame, then mapped by {@link #nakedEye}. Runs on {@code pool}:
     * a parallel stream started from inside a ForkJoinPool task runs in that pool.
     */
    public static int[] galaxy(int width, int height, ForkJoinPool pool) {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        double ox = StarCatalog.SOL.x(), oy = StarCatalog.SOL.y(), oz = StarCatalog.SOL.z();
        float[] hdr = new float[width * height * 3];
        double[][] rot = starToGalactic();
        pool.submit(() -> {
            baker.bakeEquirectRadiance(ox, oy, oz, width, height, hdr, rot);
            GalaxySkyBaker.compressHighlights(hdr, width, height, true);
        }).join();
        return nakedEye(hdr);
    }

    /** Rows map a star-buffer direction to the galactocentric ray direction ({@link EquatorialFrame#starToGalactocentric}). */
    static double[][] starToGalactic() {
        double[][] m = new double[3][3];
        for (int c = 0; c < 3; c++) {
            double[] e = new double[3];
            e[c] = 1;
            double[] g = EquatorialFrame.starToGalactocentric(e);
            for (int r = 0; r < 3; r++) m[r][c] = g[r];
        }
        return m;
    }

    /** Luminance percentiles anchoring the naked-eye mapping: the sky floor and the brightest star clouds. */
    static final double FLOOR_QUANTILE = 0.78, PEAK_QUANTILE = 0.997;
    /** Display value of the brightest star clouds: the band is a faint glow, never a white road. */
    static final double PEAK_DISPLAY = 0.6;
    /**
     * Fraction of colour kept: the band is seen near the rod/cone threshold, where colour vision
     * fades (mesopic vision, Purkinje shift), so it reads almost grey-white with a hint of warmth.
     */
    static final double SATURATION = 0.35;

    /**
     * Maps sky radiance to what a dark-adapted eye sees of the Milky Way under a Bortle-1 sky: the
     * diffuse light above the sky floor (the 78th percentile: the faint glow of the whole sphere is
     * the natural background, not the band) scaled to the Scutum–Sagittarius star clouds (the
     * 99.7th percentile), mapped almost linearly (power 1.15) to at most {@link #PEAK_DISPLAY} of
     * the display range — so the Great Rift and the Coalsack stay dark, the band's edges fade
     * instead of ending, and the band stays a faint glow — and
     * desaturated toward the eye's grey ({@link #SATURATION}). Output is light-carrying ARGB
     * ({@link #lightAlpha}).
     */
    static int[] nakedEye(float[] hdr) {
        int n = hdr.length / 3;
        // Percentiles from every 5th pixel: plenty for quantiles, and no second full-size array.
        int stride = 5, ns = (n + stride - 1) / stride;
        double[] sorted = new double[ns];
        for (int i = 0, k = 0; i < n; i += stride) sorted[k++] = SkyTonemap.luminance(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]);
        java.util.Arrays.sort(sorted);
        double floor = sorted[(int) (ns * FLOOR_QUANTILE)];
        double peak = Math.max(floor * 1.01 + 1e-12, sorted[Math.min(ns - 1, (int) (ns * PEAK_QUANTILE))]);
        int[] argb = new int[n];
        for (int i = 0; i < n; i++) {
            double y = SkyTonemap.luminance(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]);
            double x = Math.max(0, y - floor) / (peak - floor);
            if (x <= 0 || y <= 0) continue;
            double m = PEAK_DISPLAY * Math.pow(Math.min(1.6, x), 1.15);
            m = Math.min(1, m);
            // Chroma at the pixel's luminance, pulled toward grey.
            double r = hdr[i * 3] / y, g = hdr[i * 3 + 1] / y, b = hdr[i * 3 + 2] / y;
            r = 1 + (r - 1) * SATURATION;
            g = 1 + (g - 1) * SATURATION;
            b = 1 + (b - 1) * SATURATION;
            double mx = Math.max(r, Math.max(g, b));
            // m is a display (gamma-encoded) value, as the sky shell blends in display space; the
            // chroma ratio is gamma-encoded with it (encoding m itself would lift the faint edge of
            // the band into a flat plateau).
            int ri = (int) Math.round(255 * m * Math.pow(r / mx, 1 / 2.2));
            int gi = (int) Math.round(255 * m * Math.pow(g / mx, 1 / 2.2));
            int bi = (int) Math.round(255 * m * Math.pow(b / mx, 1 / 2.2));
            argb[i] = lightAlpha(0xFF000000 | (ri << 16) | (gi << 8) | bi);
        }
        return argb;
    }

    /**
     * Opaque colour c → (c / a, a) with a = max channel × {@link #GALAXY_OPACITY}. Under the
     * translucent blend, c/a · a + dst · (1 − a) adds the band's light while black sky stays fully
     * transparent (vanilla's night-blue sky shows through, never a black dome).
     */
    static int lightAlpha(int opaque) {
        int r = (opaque >> 16) & 0xFF, g = (opaque >> 8) & 0xFF, b = opaque & 0xFF;
        int m = Math.max(r, Math.max(g, b));
        if (m == 0) return 0;
        int a = (int) Math.round(m * GALAXY_OPACITY);
        int rr = Math.min(255, Math.round(r * 255f / m)), gg = Math.min(255, Math.round(g * 255f / m)), bb = Math.min(255, Math.round(b * 255f / m));
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** Real catalogue stars plus the galaxy's own faint stars as seen from Sol, brightest first. */
    public static List<Sprite> stars(Galaxy galaxy) {
        List<Sprite> out = new ArrayList<>();
        float[] rgb = new float[3];
        for (BrightStars.Entry e : BrightStars.catalogue()) {
            double[] b = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(e.raDeg(), e.decDeg()));
            Blackbody.linearRgb(e.temperature(), rgb);
            out.add(new Sprite(e.name(), (float) b[0], (float) b[1], (float) b[2], e.vmag(), rgb[0], rgb[1], rgb[2]));
        }
        for (Starfield.VisibleStar v : Starfield.compute(galaxy, StarCatalog.SOL.x(), StarCatalog.SOL.y(), StarCatalog.SOL.z())) {
            double m = v.apparentMag();
            if (m > LIMITING_MAG || m < BrightStars.CATALOGUE_LIMIT_MAG) continue;
            double[] b = EquatorialFrame.spaceToStar(v.dx(), v.dy(), v.dz());
            double n = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            out.add(new Sprite(v.star().name(), (float) (b[0] / n), (float) (b[1] / n), (float) (b[2] / n), m, v.r(), v.g(), v.b()));
        }
        out.sort((x, y) -> Double.compare(x.mag(), y.mag()));
        return out;
    }

    /** Pixel (column, row) of a star-buffer direction in the equirect layout; inverse of equirectDirection. */
    public static int[] pixelOf(double[] b, int width, int height) {
        double lat = Math.asin(Math.max(-1, Math.min(1, b[1])));
        double phi = Math.atan2(b[0], -b[2]);
        double u = phi / (2 * Math.PI);
        u -= Math.floor(u);
        double v = (Math.PI / 2 - lat) / Math.PI;
        return new int[] {Math.min(width - 1, (int) (u * width)), Math.min(height - 1, (int) (v * height))};
    }
}
