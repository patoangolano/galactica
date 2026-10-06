package dev.galactica.ship;

/**
 * How bright the hull of a ship in space is, free of Minecraft types.
 *
 * <p>Space has no sky light and no ambient term, so vanilla's lightmap renders a ship there black. Here the hull is
 * lit the way a camera would show it: by the host star's flux at the ship and the angle each face makes with the
 * star, under an auto-exposure that keeps a dim far-out ship readable.
 *
 * <ul>
 *   <li><b>Flux.</b> Insolation relative to Earth's, S = (L/L☉)/(d/1 AU)², from the inverse-square law
 *       F = L/(4πd²); S = 1 is the solar constant, 1361 W/m² (Kopp &amp; Lean 2011).</li>
 *   <li><b>Exposure.</b> A camera with auto-exposure compresses the ~10⁶ range between Mercury and the Kuiper belt
 *       to a few stops: the direct term is 1 for S ≥ 1 and falls by {@value #STOPS_PER_DECADE} stops per decade of
 *       flux below that, never under {@value #MIN_DIRECT}.</li>
 *   <li><b>Shading.</b> Lambert's cosine law, I = I_direct·max(0, n·l), plus an ambient floor standing in for
 *       starlight, planetshine and the ship's own lamps ({@value #AMBIENT}), so the night side is dark grey, not
 *       black. Far from any star (interstellar space) only a slightly raised floor remains
 *       ({@value #INTERSTELLAR_AMBIENT}): the ship stays visible.</li>
 *   <li><b>Colour.</b> The star's blackbody colour, half-way to white (the eye adapts to the illuminant).</li>
 * </ul>
 */
public final class ShipLighting {
    private ShipLighting() {}

    public static final double AMBIENT = 0.16;
    public static final double INTERSTELLAR_AMBIENT = 0.30;
    public static final double MIN_DIRECT = 0.25;
    public static final double STOPS_PER_DECADE = 1.0;

    /** Insolation relative to Earth's: (L/L☉)/(d/AU)². */
    public static double insolation(double luminositySolar, double distanceAu) {
        if (!(luminositySolar > 0) || !(distanceAu > 0)) return 0;
        return luminositySolar / (distanceAu * distanceAu);
    }

    /** Auto-exposed strength of the direct star light, 0 (no star) or {@link #MIN_DIRECT} … 1. */
    public static double directStrength(double insolation) {
        if (!(insolation > 0)) return 0;
        if (insolation >= 1) return 1;
        double stops = -Math.log10(insolation) * STOPS_PER_DECADE;
        return Math.max(MIN_DIRECT, Math.pow(2, -stops));
    }

    /** Ambient floor for a given direct strength: interstellar space gets the raised floor. */
    public static double ambient(double direct) {
        return direct > 0 ? AMBIENT : INTERSTELLAR_AMBIENT;
    }

    /**
     * Brightness of a face, 0 … 1.
     *
     * @param nDotL  cosine between the face normal and the direction to the star
     * @param direct auto-exposed direct strength ({@link #directStrength})
     */
    public static double shade(double nDotL, double direct) {
        double amb = ambient(direct);
        return Math.min(1, amb + (1 - amb) * direct * Math.max(0, nDotL));
    }

    /**
     * The light colour: the star's blackbody colour (linear sRGB, max 1) mixed half-way to white, renormalised to a
     * maximum component of 1. Writes into {@code out}.
     */
    public static void tint(float[] blackbodyRgb, float[] out) {
        float r = 0.5F + 0.5F * blackbodyRgb[0], g = 0.5F + 0.5F * blackbodyRgb[1], b = 0.5F + 0.5F * blackbodyRgb[2];
        float m = Math.max(r, Math.max(g, b));
        out[0] = r / m;
        out[1] = g / m;
        out[2] = b / m;
    }
}
