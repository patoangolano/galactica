package dev.galactica.planet;

import dev.galactica.system.Body;
import dev.galactica.system.BodyType;

/**
 * Relief of an airless body as lit from space: the slope field that lets the low sun of the
 * terminator raise crater rims and fill crater floors with shade, as on any photograph of the Moon.
 *
 * <p>The block terrain is far too flat for this at orbital scale (its relief is a few hundred
 * blocks, and the big basins of {@link PlanetMacro} are only tens of blocks deep), so the slopes come
 * from the same crater fields at their <em>physical</em> depth instead: a crater of diameter D is
 * d = min(0.2 D, 1.044 D<sup>0.301</sup>) deep (simple bowls vs complex craters, Pike 1977, km).
 * The macro basins ({@link PlanetMacro#craterField} at the frequencies the macro map uses) are
 * joined by four finer, visual-only crater populations on bodies whose air cannot stop impactors
 * ({@link SurfaceAtmosphere#cratered}); those are degraded (60 % depth) like the old, overlapping
 * craters of a saturated surface.
 *
 * <p><b>Filtering.</b> Every population is faded in only where its craters span three texels of
 * the image being made ({@code footprint}, radians of arc per texel), so the field never aliases:
 * the 10 km texels of a whole-globe image show the basins and the large craters, a close clipmap
 * level adds the small ones.
 *
 * <p>Frame: body-fixed unit vector (x, y, z), z north, as {@link PlanetMacro}. Thread-safe.
 */
public final class SpaceRelief {
    /** Fine populations: frequency on the unit sphere (cell = R/f) and seed salt. */
    private static final double[] FINE_FREQ = {24, 60, 150, 375};
    private static final long[] FINE_SALT = {0x5EE1_0001L, 0x5EE1_0002L, 0x5EE1_0003L, 0x5EE1_0004L};
    /** Mean crater diameter of a {@link PlanetMacro#craterField} population, in cells. */
    private static final double MEAN_DIAMETER_CELLS = 0.4;
    /** Depth of the field's bowl floor, field units. */
    private static final double FIELD_DEPTH = 0.30;
    /** Soft limit of the slope (rise over run): tan 35°, the angle of repose of lunar regolith. */
    static final double MAX_SLOPE = 0.7;

    private final int layers;
    private final long[] seed;
    private final double[] freq, weight, metresPerUnit, albedoWeight;
    private final boolean[] macroLayer;
    private final double radius;
    private final boolean cratered;
    private final long toneSeed;
    /** Regolith tone: base frequency on the unit sphere, and the feature size it is faded in at. */
    private static final double TONE_FREQ = 9, TONE_ANGLE = 0.5 / TONE_FREQ;

    private SpaceRelief(Body b) {
        radius = b.radiusMetres();
        long s = b.terrainSeed();
        cratered = b.type().solidSurface && SurfaceAtmosphere.cratered(b) && b.type() != BodyType.LAVA;
        toneSeed = s ^ 0x70E5_0001L;
        java.util.List<double[]> spec = new java.util.ArrayList<>();
        java.util.List<Long> seeds = new java.util.ArrayList<>();
        // {frequency, field weight, albedo weight, depth factor, macro?}
        switch (b.type()) {
            case BARREN -> {
                spec.add(new double[] {4, 1.0, 1.0, 1.0, 1});
                seeds.add(s ^ 0xC4A7_000AL);
                spec.add(new double[] {11, 0.6, 1.0, 1.0, 1});
                seeds.add(s ^ 0xC4A7_000BL);
            }
            case DESERT -> {
                spec.add(new double[] {7, 0.6, 1.0, 1.0, 1});
                seeds.add(s ^ 0xC4A7_0004L);
            }
            case ICE -> {
                double dirt = Math.max(0, Math.min(1, (0.62 - b.albedo()) / 0.4));
                if (dirt > 0) {
                    spec.add(new double[] {6, dirt * 1.2, 1.0, 1.0, 1});
                    seeds.add(s ^ 0xC4A7_0008L);
                }
            }
            default -> { }
        }
        // Fine populations on bodies that keep their craters; Io-like lava worlds resurface them, clean
        // ice shells (Europa) keep few.
        if (b.type().solidSurface && SurfaceAtmosphere.cratered(b) && b.type() != BodyType.LAVA) {
            double w = 1.0;
            if (b.type() == BodyType.ICE) w = 0.3 + 0.7 * Math.max(0, Math.min(1, (0.62 - b.albedo()) / 0.4));
            for (int i = 0; i < FINE_FREQ.length; i++) {
                spec.add(new double[] {FINE_FREQ[i], w, 0.5, 0.6, 0});
                seeds.add(s ^ FINE_SALT[i]);
            }
        }
        layers = spec.size();
        seed = new long[layers];
        freq = new double[layers];
        weight = new double[layers];
        metresPerUnit = new double[layers];
        albedoWeight = new double[layers];
        macroLayer = new boolean[layers];
        for (int i = 0; i < layers; i++) {
            double[] p = spec.get(i);
            seed[i] = seeds.get(i);
            freq[i] = p[0];
            weight[i] = p[1];
            albedoWeight[i] = p[2];
            macroLayer[i] = p[4] > 0;
            double dKm = MEAN_DIAMETER_CELLS * radius / p[0] / 1000.0;
            double depthKm = Math.min(0.2 * dKm, 1.044 * Math.pow(dKm, 0.301)) * p[3];
            metresPerUnit[i] = depthKm * 1000.0 / FIELD_DEPTH;
        }
    }

    public static SpaceRelief of(Body b) {
        return new SpaceRelief(b);
    }

    /** True when there is anything to light (a body without crater populations is smooth). */
    public boolean any() {
        return layers > 0 || cratered;
    }

    /**
     * Slope at body-fixed unit direction (x, y, z) for an image whose texels span {@code footprint}
     * radians of arc: writes the tangential gradient of height over radius into {@code slope} (the
     * lit normal is normalize(n − slope)), and returns the albedo factor of the craters (bright
     * rims and ejecta, dark floors). With {@code macroAlbedo} false the factor leaves out the macro
     * basins, whose albedo the caller already has (the orbital imager paints them).
     */
    public double sample(double x, double y, double z, double footprint, boolean macroAlbedo, double[] slope) {
        return sample(x, y, z, footprint, macroAlbedo, 1, slope);
    }

    /**
     * {@link #sample(double, double, double, double, boolean, double[])} with the fine populations
     * scaled by {@code fineWeight}: lunar maria are young lava plains with a few percent of the
     * highlands' crater density (Hartmann 1981), so callers pass ~0.3 on mare ground.
     */
    public double sample(double x, double y, double z, double footprint, boolean macroAlbedo, double fineWeight, double[] slope) {
        return sample(x, y, z, footprint, Double.NaN, macroAlbedo, fineWeight, slope, null, null);
    }

    /**
     * Both at once: the field for texels of {@code footprint} into {@code slope} (returned albedo
     * factor), and the same field filtered for the coarser {@code coarseFootprint} into
     * {@code coarseSlope} and {@code coarseFac[0]}. The crater fields are evaluated once. Images
     * use the coarse field where the view foreshortens them (towards the limb a texel covers less
     * than a pixel across, and its finest craters would alias into speckle). With a NaN
     * {@code coarseFootprint} only the first is made.
     */
    public double sample(double x, double y, double z, double footprint, double coarseFootprint, boolean macroAlbedo,
            double fineWeight, double[] slope, double[] coarseSlope, double[] coarseFac) {
        boolean two = coarseSlope != null && !Double.isNaN(coarseFootprint);
        double gx = 0, gy = 0, gz = 0, alb = 0, cx = 0, cy = 0, cz = 0, calb = 0;
        double[] g = GRAD.get();
        for (int i = 0; i < layers; i++) {
            double craterAngle = MEAN_DIAMETER_CELLS / freq[i];
            double base = weight[i] * (macroLayer[i] ? 1 : fineWeight);
            double w = base * fade(craterAngle / Math.max(1e-12, footprint));
            double wc = two ? base * fade(craterAngle / Math.max(1e-12, coarseFootprint)) : 0;
            if (w <= 0 && wc <= 0) continue;
            double f = freq[i];
            g[0] = g[1] = g[2] = 0;
            double v = PlanetMacro.craterFieldGrad(seed[i], x * f, y * f, z * f, g);
            // d(height)/d(unit-sphere coordinate), metres, then over the radius: a slope.
            double k = f * metresPerUnit[i] / radius;
            gx += w * k * g[0];
            gy += w * k * g[1];
            gz += w * k * g[2];
            cx += wc * k * g[0];
            cy += wc * k * g[1];
            cz += wc * k * g[2];
            if (macroAlbedo || !macroLayer[i]) {
                alb += albedoWeight[i] * w * v;
                calb += albedoWeight[i] * wc * v;
            }
        }
        // Regolith tone: albedo varies by ±10 % on 50–200 km scales even within one unit (maturity,
        // titanium content of mare basalts, ejecta blankets), which keeps maria and highlands from
        // reading as flat paint. Faded in like a crater population of the same size.
        if (cratered) {
            double tw = fade(TONE_ANGLE / Math.max(1e-12, footprint));
            double twc = two ? fade(TONE_ANGLE / Math.max(1e-12, coarseFootprint)) : 0;
            if (tw > 0 || twc > 0) {
                double tone = 0.10 * PlanetMacro.fbmRot(toneSeed, x * TONE_FREQ, y * TONE_FREQ, z * TONE_FREQ, 3) / 0.6;
                alb += tw * tone;
                calb += twc * tone;
            }
        }
        limit(gx, gy, gz, x, y, z, slope);
        if (two) {
            limit(cx, cy, cz, x, y, z, coarseSlope);
            if (coarseFac != null) coarseFac[0] = Math.max(0.25, 1 + calb);
        }
        return Math.max(0.25, 1 + alb);
    }

    /**
     * Tangential part only, softly limited to the ~35° of regolith at its angle of repose: the
     * field's rim is a narrow ridge whose raw slope would otherwise stand near vertical on bodies
     * whose craters are simple bowls (depth 0.2 D), and light rims far past the terminator.
     */
    private static void limit(double gx, double gy, double gz, double x, double y, double z, double[] slope) {
        double dn = gx * x + gy * y + gz * z;
        double tx = gx - dn * x, ty = gy - dn * y, tz = gz - dn * z;
        double m = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double lim = 1 / Math.sqrt(1 + (m / MAX_SLOPE) * (m / MAX_SLOPE));
        slope[0] = tx * lim;
        slope[1] = ty * lim;
        slope[2] = tz * lim;
    }

    /** 0 below 1.5 texels per crater, 1 above 3. */
    private static double fade(double texels) {
        double t = Math.max(0, Math.min(1, (texels - 1.5) / 1.5));
        return t * t * (3 - 2 * t);
    }

    private static final ThreadLocal<double[]> GRAD = ThreadLocal.withInitial(() -> new double[3]);

    /**
     * Reflectance of airless regolith for the lit normal: the imager's Lommel–Seeliger/Lambert blend
     * (the flat full Moon, Hapke 1981). {@code mu0} and {@code mu} are the cosines to the star and
     * to the eye; 0 on the night side.
     */
    public static double regolith(double mu0, double mu) {
        if (mu0 <= 0) return 0;
        return 0.65 * 2 * mu0 / (mu0 + Math.max(mu, 1e-3)) + 0.35 * mu0;
    }
}
