package dev.galactica.earthsky;

import dev.galactica.client.earthsky.SunAppearance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SunAppearanceTest {
    @Test
    void airmassMatchesKastenYoung() {
        assertEquals(1.0, SunAppearance.airmass(90), 1e-3);
        assertEquals(2.0, SunAppearance.airmass(30), 0.01);
        assertEquals(38.1, SunAppearance.airmass(0), 0.5);
    }

    @Test
    void whiteAtNoonRedOnTheHorizonGoneBelow() {
        float[] f = SunAppearance.filter(60, new float[3]);
        for (float c : f) assertEquals(1.0, c, 0.01, "noon Sun is display white");
        f = SunAppearance.filter(3, new float[3]);
        assertTrue(f[0] > 0.99 && f[1] > 0.8 && f[2] < 0.6, "yellow-orange at 3 deg: " + java.util.Arrays.toString(f));
        f = SunAppearance.filter(0.4, new float[3]);
        assertTrue(f[0] > 2 * f[1] && f[1] > 3 * f[2], "red on the horizon: " + java.util.Arrays.toString(f));
        f = SunAppearance.filter(-2, new float[3]);
        assertEquals(0, f[0] + f[1] + f[2], 1e-9);
    }

    @Test
    void glareFallsOffAndEndsAtTheSpriteEdge() {
        assertTrue(SunAppearance.glare(0, 8) > SunAppearance.glare(1, 8));
        assertTrue(SunAppearance.glare(1, 8) > SunAppearance.glare(4, 8));
        assertEquals(0, SunAppearance.glare(8, 8));
    }
}
