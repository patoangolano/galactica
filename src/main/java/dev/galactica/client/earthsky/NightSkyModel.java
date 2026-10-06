package dev.galactica.client.earthsky;

import dev.galactica.sky.GalaxySkyBaker;

/**
 * Earth's atmosphere between the observer and the night sky, for the Overworld: what makes the
 * Milky Way fade towards the horizon and vanish around a bright Moon. Pure Java.
 *
 * <ul>
 *   <li><b>Airmass</b> X(h): Kasten &amp; Young (1989, Appl. Opt. 28, 4735), finite at the
 *       horizon (≈ 38).</li>
 *   <li><b>Extinction</b>: T = 10^(−0.4 k X) with k = {@value #K_V} mag per airmass, the V-band
 *       coefficient of a dark high-altitude site (Krisciunas et al. 1987).</li>
 *   <li><b>Natural sky</b> (airglow and zodiacal light): B_dark(Z) = B_zen · X · 10^(−0.4 k (X − 1))
 *       (Garstang 1989; Krisciunas &amp; Schaefer 1991, PASP 103, 1033, eq. 2): brighter towards the
 *       horizon, where more airglow layer is in the line of sight (the van Rhijn effect).
 *       B_zen = {@value #B_ZENITH_NL} nL ≈ 22.0 V mag arcsec⁻², a Bortle-1 sky.</li>
 *   <li><b>Moonlight</b>: Krisciunas &amp; Schaefer (1991) eqs. 15–21: Rayleigh and Mie scattering
 *       f(ρ) = 10^5.36 (1.06 + cos²ρ) + 10^(6.15 − ρ/40), Moon brightness
 *       I* = 10^(−0.4 (3.84 + 0.026|α| + 4·10⁻⁹ α⁴)) at phase angle α, both extinguished along their
 *       airmasses: B_moon = f(ρ) I* 10^(−0.4 k X_moon) (1 − 10^(−0.4 k X)).</li>
 * </ul>
 *
 * <p><b>What the eye keeps of the Milky Way.</b> The band is seen by its contrast against the sky
 * (Weber), so behind a sky F times brighter than the dark zenith its visibility drops; with the
 * light dimmed by T on the way down the visible fraction is V = T / F^0.4 (the eye adapts
 * partly to a brighter sky, so contrast falls more slowly than 1/F; Blackwell 1946). The veil drawn over the night sky has opacity
 * 1 − V and adds the sky's own glow (greenish airglow low down, blue-white moonlight near the Moon).
 *
 * <p><b>Sun and Moon stay clear.</b> The veil is drawn after vanilla's sun and moon, so it is cut
 * out over their sprites ({@link #bodyClearance}): vanilla's visible disc fills the middle 16 of the
 * sun sprite's 32 px (quad half-width 30 at height 100, so ±8.5°) and 14 of the moon's 32 px (half-width
 * 20, so ±5.0°); the cut-out is that square in the body's own frame, feathered over the outer quarter of
 * it (inside the disc, so no unveiled halo rings the sprite; the limb is veiled a little, like glare).
 * It also fades out with the Sun above −{@value #TWILIGHT_END_DEG}° ({@link #twilightWeight}): in
 * civil and nautical twilight scattered sunlight, which vanilla's sky colour and sunset fan
 * already paint, outshines airglow and moonlight, and the dark-sky model no longer applies
 * (the sky darkens to its night value as the Sun sinks from 0° to about −12°; Patat 2003, A&amp;A 400, 1183).
 */
public final class NightSkyModel {
    private NightSkyModel() {}

    /** V-band extinction coefficient, mag per airmass, of a dark high-altitude site. */
    public static final double K_V = 0.20;
    /** Dark-sky zenith brightness in nanolamberts (≈ 22.0 V mag arcsec⁻²). */
    public static final double B_ZENITH_NL = 54.0;
    /** Display glow (sRGB-ish, 0–1) per doubling of the sky brightness over the dark zenith. */
    static final double GLOW_PER_DOUBLING = 0.012;
    static final double MAX_GLOW = 0.12, MAX_VEIL = 0.96;
    /** Exponent of the contrast loss behind a sky F times brighter than the dark zenith (partial adaptation). */
    static final double CONTRAST_EXPONENT = 0.4;
    private static final float[] AIRGLOW = {0.62f, 0.72f, 0.60f};
    private static final float[] MOONLIGHT = {0.60f, 0.72f, 1.00f};
    /** Tangent of the half-width of the visible disc in vanilla's sun and moon sprites (see class doc). */
    public static final double SUN_DISC_TAN = 30.0 * 16 / 32 / 100, MOON_DISC_TAN = 20.0 * 14 / 32 / 100;
    /** Feather of the cut-out: the veil returns from this fraction of the disc half-width out to its edge. */
    static final double CLEAR_FEATHER_START = 0.75;
    /** Solar altitude (deg below the horizon) by which the veil reaches full strength. */
    public static final double TWILIGHT_END_DEG = 12.0;

    /** Relative airmass at apparent altitude {@code altDeg} (Kasten &amp; Young 1989); clamped below −2°. */
    public static double airmass(double altDeg) {
        double h = Math.max(-2.0, altDeg);
        if (h < 0) h = 0; // below the geometric horizon: keep the horizon value (terrain covers it anyway)
        return 1.0 / (Math.sin(Math.toRadians(h)) + 0.50572 * Math.pow(h + 6.07995, -1.6364));
    }

    /** Fraction of light reaching the observer from altitude {@code altDeg}. */
    public static double transmission(double altDeg) {
        return Math.pow(10, -0.4 * K_V * airmass(altDeg));
    }

    /** Natural (moonless) sky brightness at altitude {@code altDeg}, nL. */
    public static double darkSky(double altDeg) {
        double x = airmass(altDeg);
        return B_ZENITH_NL * x * Math.pow(10, -0.4 * K_V * (x - 1));
    }

    /**
     * Moonlit sky brightness (nL, Krisciunas &amp; Schaefer 1991) at altitude {@code altDeg},
     * {@code rhoDeg} from the Moon, the Moon at {@code moonAltDeg} with phase angle {@code phaseDeg}
     * (0 = full, 180 = new). Zero with the Moon below the horizon.
     */
    public static double moonSky(double altDeg, double rhoDeg, double moonAltDeg, double phaseDeg) {
        if (moonAltDeg < -0.5) return 0;
        double a = Math.abs(phaseDeg);
        if (a >= 179) return 0;
        double iStar = Math.pow(10, -0.4 * (3.84 + 0.026 * a + 4e-9 * a * a * a * a));
        double rho = Math.max(4.0, rhoDeg); // the formula diverges at the Moon; inside 4° it is the Moon's own glare
        double c = Math.cos(Math.toRadians(rho));
        double f = Math.pow(10, 5.36) * (1.06 + c * c) + Math.pow(10, 6.15 - rho / 40);
        return f * iStar * Math.pow(10, -0.4 * K_V * airmass(moonAltDeg)) * (1 - Math.pow(10, -0.4 * K_V * airmass(altDeg)));
    }

    /** Phase angle (deg) of a vanilla moon phase index (0 full … 4 new … 7). */
    public static double phaseAngle(int phaseIndex) {
        int k = Math.floorMod(phaseIndex, 8);
        return Math.min(k, 8 - k) * 45.0;
    }

    /**
     * Visible fraction V of the sky's light (extinction, then the contrast lost to a brighter sky)
     * and the glow the brighter sky adds, as a translucent veil texel: {@code out = {r, g, b, a}}
     * with colour in display (gamma-encoded) units 0–1 such that blending colour·a + sky·(1 − a),
     * as the GPU blends into the framebuffer, dims the sky behind to V and adds the glow.
     *
     * @param moonDir unit world direction of the Moon (y up), or null for no Moon
     */
    public static void veil(double[] dir, double[] moonDir, double phaseDeg, float[] out) {
        veilCore(dir, moonDir, phaseDeg, out);
    }

    /**
     * {@link #veil(double[], double[], double, float[])} with the sun and moon sprites cut out and
     * the twilight fade. Bodies are given by vanilla's angles (radians; NaN = absent), because the
     * cut-out needs the sprite's own frame, not just its direction.
     */
    public static void veil(double[] dir, double moonAngleRad, double phaseDeg, double sunAngleRad, float[] out) {
        double[] moonDir = Double.isNaN(moonAngleRad) ? null : moonDirection(moonAngleRad);
        veilCore(dir, moonDir, phaseDeg, out);
        if (out[3] == 0) return;
        double keep = 1;
        if (!Double.isNaN(sunAngleRad)) {
            keep *= twilightWeight(Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, Math.cos(sunAngleRad))))));
            keep *= bodyClearance(dir, sunAngleRad, SUN_DISC_TAN);
        }
        if (moonDir != null) keep *= bodyClearance(dir, moonAngleRad, MOON_DISC_TAN);
        // Scaling alpha alone keeps the added glow (colour·alpha) in proportion.
        out[3] = (float) (out[3] * keep);
        if (out[3] < 1e-4f) out[0] = out[1] = out[2] = out[3] = 0;
    }

    /**
     * 0 inside vanilla's sprite of a body at {@code angleRad}, 1 outside, feathered from
     * {@value #CLEAR_FEATHER_START} of its half-width to its edge. The sprite is a square of half-width
     * {@code discTan} (tangent-plane units) on the plane tangent at the body's direction
     * b = (−sin a, cos a, 0), with sides along R<sub>y</sub>(−90°)·R<sub>x</sub>(a) of x̂ and ẑ:
     * e<sub>x</sub> = (0, 0, 1) and e<sub>z</sub> = (−cos a, −sin a, 0).
     */
    public static double bodyClearance(double[] dir, double angleRad, double discTan) {
        double s = Math.sin(angleRad), c = Math.cos(angleRad);
        double along = -s * dir[0] + c * dir[1];
        if (along <= 0) return 1;
        double px = dir[2] / along, pz = (-c * dir[0] - s * dir[1]) / along;
        double m = Math.max(Math.abs(px), Math.abs(pz)) / discTan;
        return smoothstep(CLEAR_FEATHER_START, 1, m);
    }

    /** Veil strength with the Sun at {@code sunAltDeg}: 0 with the Sun up, 1 below −{@value #TWILIGHT_END_DEG}°. */
    public static double twilightWeight(double sunAltDeg) {
        return smoothstep(0, TWILIGHT_END_DEG, -sunAltDeg);
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static void veilCore(double[] dir, double[] moonDir, double phaseDeg, float[] out) {
        double alt = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir[1]))));
        double dark = darkSky(alt), moon = 0;
        if (moonDir != null) {
            double c = dir[0] * moonDir[0] + dir[1] * moonDir[1] + dir[2] * moonDir[2];
            double rho = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
            double moonAlt = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, moonDir[1]))));
            moon = moonSky(alt, rho, moonAlt, phaseDeg);
        }
        double f = (dark + moon) / B_ZENITH_NL;
        double visible = transmission(alt) / transmission(90) / Math.pow(Math.max(1, f), CONTRAST_EXPONENT);
        double a = Math.max(0, Math.min(MAX_VEIL, 1 - visible));
        double glow = Math.min(MAX_GLOW, GLOW_PER_DOUBLING * Math.log(Math.max(1, f)) / Math.log(2));
        double wm = (dark + moon) > 0 ? moon / (dark + moon) : 0;
        if (a < 1e-4) {
            out[0] = out[1] = out[2] = out[3] = 0;
            return;
        }
        for (int k = 0; k < 3; k++) {
            double col = (AIRGLOW[k] * (1 - wm) + MOONLIGHT[k] * wm) * glow / a;
            out[k] = (float) Math.min(1, col);
        }
        out[3] = (float) a;
    }

    /**
     * The veil as an equirectangular ARGB texture in world axes ({@link GalaxySkyBaker#equirectDirection}
     * layout: u around +Y from −Z, v from zenith to nadir). Colour is display (gamma-encoded) values,
     * since the sky shell blends in display space.
     */
    public static int[] veilTexture(int w, int h, double[] moonDir, double phaseDeg) {
        return veilTexture(w, h, moonDir, phaseDeg, Double.NaN, Double.NaN);
    }

    /** As {@link #veilTexture(int, int, double[], double)}, with the sprite cut-outs and twilight fade when the angles are given. */
    public static int[] veilTexture(int w, int h, double moonAngleRad, double phaseDeg, double sunAngleRad) {
        return veilTexture(w, h, null, phaseDeg, moonAngleRad, sunAngleRad);
    }

    private static int[] veilTexture(int w, int h, double[] moonDir, double phaseDeg, double moonAngleRad, double sunAngleRad) {
        boolean angles = !Double.isNaN(moonAngleRad) || !Double.isNaN(sunAngleRad);
        int[] argb = new int[w * h];
        double[] d = new double[3];
        float[] t = new float[4];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                GalaxySkyBaker.equirectDirection((i + 0.5) / w, (j + 0.5) / h, d);
                if (angles) veil(d, moonAngleRad, phaseDeg, sunAngleRad, t);
                else veilCore(d, moonDir, phaseDeg, t);
                int a = Math.round(255 * t[3]);
                int r = Math.round(255 * t[0]), g = Math.round(255 * t[1]), b = Math.round(255 * t[2]);
                argb[j * w + i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return argb;
    }

    /**
     * World direction of vanilla's Moon at {@code moonAngleRad}: the moon quad sits at +Y under
     * R<sub>y</sub>(−90°)·R<sub>x</sub>(angle), i.e. (−sin a, cos a, 0).
     */
    public static double[] moonDirection(double moonAngleRad) {
        return new double[] {-Math.sin(moonAngleRad), Math.cos(moonAngleRad), 0};
    }
}
