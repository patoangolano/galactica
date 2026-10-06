package dev.galactica.client.vfx.star;

import dev.galactica.client.vfx.Vec4Block;
import dev.galactica.client.vfx.VfxClock;

/**
 * Packs a {@link StarVisual} into the {@code StarParams} std140 block declared in
 * {@code assets/galactica/shaders/include/galactica/star_params.glsl}. Slot order here and in the
 * GLSL block must stay identical; {@code dev.galactica.vfx.StarVisualTest#uniformLayoutMatchesTheGlslBlock}
 * pins it.
 *
 * <p>Periodic motions are converted to phases here, in double precision from the unwrapped clock,
 * so they stay continuous when the float shader clock wraps ({@link VfxClock#PERIOD_S}).
 */
public final class StarUniforms {
    private StarUniforms() {}

    public static final int COLOR = 0, LIMB = 1, GRAN = 2, ACTIVE = 3, CHROMO = 4, CORONA = 5, GEOM = 6, PHASE = 7, SPOTS = 8;
    public static final int MAX_SPOTS = 8;
    public static final int VEC4_COUNT = SPOTS + MAX_SPOTS;
    /** Block size in bytes (256). */
    public static final int SIZE_BYTES = VEC4_COUNT * 16;

    /** Largest quad half-angle, rad: a flat quad cannot cover a hemisphere (tan → ∞). */
    public static final double MAX_QUAD_HALF_ANGLE = 1.2;
    /** Quad half-size in stellar radii for a well resolved disc: room for the corona and prominences. */
    public static final double CORONA_MARGIN = 3.5;
    /** Minimum halo radius in pixels so a 4-pixel Sun still gets a visible bloom. */
    public static final double MIN_HALO_PX = 14.0;
    /** On-screen pixels per radian at 70° FOV on a 1080-line window (same constant as the sky renderer). */
    public static final double PX_PER_RAD = 885.0;

    /** Angular half-size of the camera-facing quad for a disc of this angular radius, rad. */
    public static double quadHalfAngle(double angularRadius) {
        double margin = Math.max(CORONA_MARGIN, MIN_HALO_PX / Math.max(1e-9, angularRadius * PX_PER_RAD));
        return Math.min(MAX_QUAD_HALF_ANGLE, angularRadius * margin);
    }

    /** Snodgrass &amp; Ulrich (1990) differential rotation: Omega(lat) = A (1 - B sin^2 - C sin^4). */
    public static final double DIFF_B = 0.162, DIFF_C = 0.121;
    /**
     * Largest shear angle, rad. Real differential rotation winds a surface pattern up without bound;
     * real spots and network cells decay within days to weeks (Petrovay &amp; van Driel-Gesztelyi 1997,
     * Sol. Phys. 176, 249), so no feature ever sees more than a fraction of a turn of shear. The
     * displayed shear therefore oscillates (triangle wave) between 0 and this value: continuous, and
     * the pole lags the equator by at most (1 + C/B) * SHEAR_MAX, about 1.75 pi.
     */
    public static final double SHEAR_MAX = Math.PI;

    /** {@code x mod period} in [0, period). */
    static double wrap(double x, double period) {
        double r = x - period * Math.floor(x / period);
        return r >= period ? 0.0 : r;
    }

    /** Equatorial rotation angle A t, wrapped to [0, 2 pi). */
    public static double equatorialPhase(double rate, double tSeconds) {
        return wrap(rate * tSeconds, 2 * Math.PI);
    }

    /** Continuous, bounded stand-in for the shear angle B A t: a triangle wave in [0, SHEAR_MAX] with slope B A. */
    public static double shearPhase(double rate, double tSeconds) {
        double m = wrap(DIFF_B * rate * tSeconds, 2 * SHEAR_MAX);
        return m <= SHEAR_MAX ? m : 2 * SHEAR_MAX - m;
    }

    /** Phase of a cycle of this period, 2 pi t / period wrapped to [0, 2 pi). */
    public static double cyclePhase(double periodSeconds, double tSeconds) {
        if (!(periodSeconds > 0)) return 0.0;
        return 2 * Math.PI * wrap(tSeconds / periodSeconds, 1.0);
    }

    /** Supergranules live ~24 granule lifetimes (about a day against ~10 min; Rieutord &amp; Rincon 2010). */
    public static final double SUPERGRANULE_LIFETIMES = 24.0;

    /**
     * Fills {@code out} (must have {@link #VEC4_COUNT} slots).
     *
     * @param angularRadius disc angular radius, rad
     * @param intensity     overall multiplier (rain, daylight fading), 0..1
     * @param elapsedSeconds unwrapped animation clock, s ({@link VfxClock#elapsedSeconds()})
     */
    public static Vec4Block pack(StarVisual v, double angularRadius, float intensity, double elapsedSeconds, Vec4Block out) {
        if (out.vec4Count() != VEC4_COUNT) throw new IllegalArgumentException("block must have " + VEC4_COUNT + " vec4");
        double tanHalf = Math.tan(quadHalfAngle(angularRadius));
        // Seed folded to [0, 1000): large enough to decorrelate stars, small enough for float noise precision.
        double seedF = Math.floorMod(v.seed(), 100_003L) / 100.003;
        out.put(COLOR, v.r(), v.g(), v.b(), intensity);
        out.put(LIMB, v.limbA(), v.limbB(), v.smooth() ? 1 : 0, StarSurfaceModel.EXPOSURE);
        out.put(GRAN, v.cellsPerRadius(), v.lifetimeS(), v.contrast(), v.superCells());
        out.put(ACTIVE, v.faculae(), v.activity(), v.flareRate(), seedF);
        out.put(CHROMO, v.chromosphere(), v.chromosphere(), v.prominence(), v.prominences());
        out.put(CORONA, v.corona(), v.coronaIndex(), v.streamers(), 1.0);
        out.put(GEOM, angularRadius, tanHalf, VfxClock.wrapSeconds(elapsedSeconds), v.rotationRate());
        out.put(PHASE, equatorialPhase(v.rotationRate(), elapsedSeconds), shearPhase(v.rotationRate(), elapsedSeconds),
                cyclePhase(v.lifetimeS(), elapsedSeconds), cyclePhase(v.lifetimeS() * SUPERGRANULE_LIFETIMES, elapsedSeconds));
        for (int i = 0; i < MAX_SPOTS; i++) {
            if (i < v.spots().size()) {
                StarVisual.Spot s = v.spots().get(i);
                out.put(SPOTS + i, s.latitude(), s.longitude(), s.radius(), s.umbra());
            } else {
                out.put(SPOTS + i, 0f, 0f, 0f, 0f);
            }
        }
        return out;
    }
}
