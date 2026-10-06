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
}
