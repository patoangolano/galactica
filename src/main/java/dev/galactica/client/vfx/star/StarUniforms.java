package dev.galactica.client.vfx.star;

import dev.galactica.client.vfx.Vec4Block;

/**
 * Packs a {@link StarVisual} into the {@code StarParams} std140 block declared in
 * {@code assets/galactica/shaders/include/galactica/star_params.glsl}. Slot order here and in the
 * GLSL block must stay identical; {@code StarUniformsTest} pins it.
 */
public final class StarUniforms {
    private StarUniforms() {}

    public static final int COLOR = 0, LIMB = 1, GRAN = 2, ACTIVE = 3, CHROMO = 4, CORONA = 5, GEOM = 6, SPOTS = 7;
    public static final int MAX_SPOTS = 8;
    public static final int VEC4_COUNT = SPOTS + MAX_SPOTS;
    /** Block size in bytes (240). */
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

    /**
     * Fills {@code out} (must have {@link #VEC4_COUNT} slots).
     *
     * @param angularRadius disc angular radius, rad
     * @param intensity     overall multiplier (rain, daylight fading), 0..1
     * @param timeSeconds   animation clock, s
     */
    public static Vec4Block pack(StarVisual v, double angularRadius, float intensity, float timeSeconds, Vec4Block out) {
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
        out.put(GEOM, angularRadius, tanHalf, timeSeconds, v.rotationRate());
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
