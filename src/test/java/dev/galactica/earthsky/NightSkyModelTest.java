package dev.galactica.earthsky;

import dev.galactica.client.earthsky.NightSkyModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Earth's atmosphere over the Overworld night sky (Kasten &amp; Young 1989; Krisciunas &amp; Schaefer 1991). */
class NightSkyModelTest {

    @Test
    void airmassAndExtinction() {
        assertEquals(1.0, NightSkyModel.airmass(90), 1e-3);
        assertEquals(2.0, NightSkyModel.airmass(30), 0.01);
        assertEquals(38.0, NightSkyModel.airmass(0), 0.3); // Kasten & Young: 37.92 at the horizon
        assertEquals(Math.pow(10, -0.4 * NightSkyModel.K_V), NightSkyModel.transmission(90), 1e-4);
        assertTrue(NightSkyModel.transmission(0) < 0.002);
    }

    @Test
    void naturalSkyBrightensTowardsTheHorizon() {
        assertEquals(NightSkyModel.B_ZENITH_NL, NightSkyModel.darkSky(90), 0.05);
        assertTrue(NightSkyModel.darkSky(15) > 2 * NightSkyModel.darkSky(90), "van Rhijn brightening");
    }

    @Test
    void moonlightFollowsKrisciunasAndSchaefer() {
        double full30 = NightSkyModel.moonSky(60, 30, 60, 0), full90 = NightSkyModel.moonSky(60, 90, 60, 0);
        assertTrue(full30 > full90, "brighter near the Moon");
        // A full Moon high up makes the sky several magnitudes brighter than a dark one.
        assertTrue(full90 > 5 * NightSkyModel.B_ZENITH_NL, "full moon sky " + full90 + " nL");
        assertTrue(NightSkyModel.moonSky(60, 30, 60, 90) < 0.25 * full30, "quarter moon much fainter");
        assertEquals(0, NightSkyModel.moonSky(60, 30, -10, 0));
        assertEquals(0, NightSkyModel.moonSky(60, 30, 60, 180));
        assertEquals(0, NightSkyModel.phaseAngle(0));
        assertEquals(180, NightSkyModel.phaseAngle(4));
        assertEquals(45, NightSkyModel.phaseAngle(7));
    }

    @Test
    void veilIsClearOverheadOnADarkNightAndThickAtTheHorizon() {
        float[] t = new float[4];
        NightSkyModel.veil(new double[] {0, 1, 0}, null, 180, t);
        assertEquals(0, t[3], 1e-3);
        NightSkyModel.veil(new double[] {1, 0.02, 0}, null, 180, t);
        assertTrue(t[3] > 0.9, "horizon " + t[3]);
        NightSkyModel.veil(new double[] {Math.cos(Math.toRadians(30)), Math.sin(Math.toRadians(30)), 0}, null, 180, t);
        assertTrue(t[3] > 0.1 && t[3] < 0.6, "30 degrees " + t[3]);
        // A full Moon overhead washes the band out even at the zenith.
        double[] up = NightSkyModel.moonDirection(0);
        assertArrayEquals(new double[] {0, 1, 0}, up, 1e-12);
        NightSkyModel.veil(new double[] {0.3, 0.95, 0}, up, 0, t);
        assertTrue(t[3] > 0.5, "moonlit " + t[3]);
        // The texture holds the same values, alpha in the top byte.
        int[] tex = NightSkyModel.veilTexture(32, 16, null, 180);
        assertTrue((tex[0] >>> 24) < 3, "zenith row clear");
        assertTrue((tex[7 * 32] >>> 24) > 200, "horizon row opaque");
    }

    @Test
    void veilLeavesVanillaSunAndMoonSpritesClear() {
        float[] t = new float[4];
        // Full Moon 30 degrees above the western horizon (vanilla moon angle 60 degrees), Sun far below.
        double moonA = Math.toRadians(60), sunA = moonA + Math.PI;
        double[] moon = NightSkyModel.moonDirection(moonA);
        NightSkyModel.veil(moon, moonA, 0, sunA, t);
        assertEquals(0, t[3], 1e-6, "centre of the Moon");
        // Just inside the sprite's visible disc (±5.0 deg): still clear.
        double[] edge = tilt(moon, new double[] {0, 0, 1}, Math.toDegrees(Math.atan(0.7 * NightSkyModel.MOON_DISC_TAN)));
        NightSkyModel.veil(edge, moonA, 0, sunA, t);
        assertEquals(0, t[3], 1e-6, "inner disc");
        // 12 deg away: the moonlit sky veils the band as before the cut-out.
        double[] off = tilt(moon, new double[] {0, 0, 1}, 12);
        float[] ref = new float[4];
        NightSkyModel.veil(off, moon, 0, ref);
        NightSkyModel.veil(off, moonA, 0, sunA, t);
        assertTrue(ref[3] > 0.5, "moonlit glare " + ref[3]);
        assertEquals(ref[3], t[3], 1e-6);
        // The square follows the sprite's frame: 4.8 deg out along its diagonal (past 0.7 of the 5.0 deg edge distance) is still clear.
        assertEquals(0, NightSkyModel.bodyClearance(tilt(tilt(moon, new double[] {0, 0, 1}, 3.4), new double[] {-Math.cos(moonA), -Math.sin(moonA), 0}, 3.4),
                moonA, NightSkyModel.MOON_DISC_TAN), 0.05);
        assertEquals(1, NightSkyModel.bodyClearance(new double[] {-moon[0], -moon[1], -moon[2]}, moonA, NightSkyModel.MOON_DISC_TAN));
    }

    @Test
    void veilFadesOutInTwilightAndSparesTheSettingSun() {
        assertEquals(0, NightSkyModel.twilightWeight(5));
        assertEquals(0, NightSkyModel.twilightWeight(0));
        assertEquals(0.5, NightSkyModel.twilightWeight(-6), 1e-9);
        assertEquals(1, NightSkyModel.twilightWeight(-NightSkyModel.TWILIGHT_END_DEG));
        float[] t = new float[4];
        double[] horizon = {-1, 0.02, 0};
        // Sun 3 degrees below the western horizon: the horizon there is not darkened over the sunset.
        double sunA = Math.toRadians(93);
        NightSkyModel.veil(horizon, sunA + Math.PI, 180, sunA, t);
        assertTrue(t[3] < 0.1, "dusk horizon " + t[3]);
        // Deep night: the horizon is thick again.
        sunA = Math.toRadians(150);
        NightSkyModel.veil(horizon, sunA + Math.PI, 180, sunA, t);
        assertTrue(t[3] > 0.9, "night horizon " + t[3]);
        // A sun sprite at the horizon at sunset is cut out even if the veil were at full strength.
        double[] sun = NightSkyModel.moonDirection(Math.toRadians(90));
        assertEquals(0, NightSkyModel.bodyClearance(sun, Math.toRadians(90), NightSkyModel.SUN_DISC_TAN));
        // Angle-based texture: the Moon's texel is clear.
        int w = 256, h = 128;
        int[] tex = NightSkyModel.veilTexture(w, h, Math.toRadians(60), 0, Math.toRadians(240));
        double[] d = new double[3];
        double[] moon = NightSkyModel.moonDirection(Math.toRadians(60));
        int best = 0;
        double bestDot = -2;
        for (int p = 0; p < w * h; p++) {
            dev.galactica.sky.GalaxySkyBaker.equirectDirection((p % w + 0.5) / w, (p / w + 0.5) / h, d);
            double dot = d[0] * moon[0] + d[1] * moon[1] + d[2] * moon[2];
            if (dot > bestDot) { bestDot = dot; best = p; }
        }
        assertEquals(0, tex[best] >>> 24, "Moon texel");
    }

    /** {@code v} turned by {@code deg} towards the unit vector {@code towards} (perpendicular to v). */
    private static double[] tilt(double[] v, double[] towards, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        double[] o = {v[0] * c + towards[0] * s, v[1] * c + towards[1] * s, v[2] * c + towards[2] * s};
        double n = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
        return new double[] {o[0] / n, o[1] / n, o[2] / n};
    }
}
