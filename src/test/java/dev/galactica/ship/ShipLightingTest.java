package dev.galactica.ship;

import org.junit.jupiter.api.Test;

import static dev.galactica.ship.ShipLighting.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipLightingTest {
    @Test
    void insolationIsInverseSquare() {
        assertEquals(1.0, insolation(1, 1), 1e-12, "Earth");
        assertEquals(0.25, insolation(1, 2), 1e-12);
        assertEquals(1 / (30.07 * 30.07), insolation(1, 30.07), 1e-12, "Neptune");
        assertEquals(1.0, insolation(25.4, Math.sqrt(25.4)), 1e-12, "Sirius A at its 1-S☉ distance");
        assertEquals(0, insolation(0, 1));
        assertEquals(0, insolation(1, 0));
    }

    @Test
    void exposureLosesOneStopPerDecadeAndBottomsOut() {
        assertEquals(1.0, directStrength(1));
        assertEquals(1.0, directStrength(10), "Mercury is not brighter than white");
        assertEquals(0.5, directStrength(0.1), 1e-12);
        assertEquals(0.25, directStrength(0.01), 1e-12);
        assertEquals(MIN_DIRECT, directStrength(1e-6), "Oort cloud stays readable");
        assertEquals(0, directStrength(0), "no star, no direct light");
    }

    @Test
    void lambertWithAnAmbientFloor() {
        assertEquals(1.0, shade(1, 1), 1e-12, "face toward an Earth-distance sun");
        assertEquals(AMBIENT, shade(-1, 1), 1e-12, "night side is dark grey, not black");
        assertEquals(AMBIENT + (1 - AMBIENT) * 0.5, shade(0.5, 1), 1e-12, "cos 60°");
        assertEquals(INTERSTELLAR_AMBIENT, shade(1, 0), 1e-12, "interstellar space: visible, unlit");
        assertTrue(shade(1, directStrength(1e-3)) > shade(-1, 1));
    }

    @Test
    void exposedHullReadsAgainstSpace() {
        double albedo = 75 / 255.0; // the hull's main grey panel
        double sunlit = albedo * HULL_GAIN * shade(1, 1), night = albedo * HULL_GAIN * shade(-1, 1);
        assertTrue(sunlit > 0.7, "sunlit side is bright: " + sunlit);
        assertTrue(night > 0.12 && night < 0.2, "night side is dark grey, still a silhouette: " + night);
        assertTrue(shade(1, 1) <= 1 && shade(1, 0) <= 1, "the vertex colour carries shade, which fits in 8 bits");
    }

    @Test
    void tintIsNormalisedAndHalfWayToWhite() {
        float[] out = new float[3];
        tint(new float[] {1F, 0.5F, 0.2F}, out);
        assertEquals(1F, out[0], 1e-6);
        assertEquals(0.75F, out[1], 1e-6);
        assertEquals(0.6F, out[2], 1e-6);
        tint(new float[] {0.6F, 0.8F, 1F}, out);
        assertEquals(1F, out[2], 1e-6);
        assertTrue(out[0] < out[1] && out[1] < out[2], "a hot star stays bluish");
    }

    @Test
    void crossingTheSystemEdgeIsContinuous() {
        double r = 1e15;
        assertEquals(1, systemFade(0.5 * r, r), 1e-12);
        assertEquals(1, systemFade(FADE_START * r, r), 1e-12);
        assertEquals(0.5, systemFade(0.9 * r, r), 1e-12);
        assertEquals(0, systemFade(r, r), 1e-12);
        assertEquals(0, systemFade(2 * r, r), 1e-12);
        // Night and day side both slide to the interstellar floor; no step bigger than the sampling would allow.
        double far = directStrength(1e-6); // the exposure floor, as at the edge of a big system
        double prevDay = shade(1, far), prevNight = shade(-1, far);
        for (int i = 1; i <= 400; i++) {
            double d = (FADE_START + (1.05 - FADE_START) * i / 400.0) * r;
            double direct = far * systemFade(d, r);
            double day = shade(1, direct), night = shade(-1, direct);
            assertTrue(Math.abs(day - prevDay) < 0.01 && Math.abs(night - prevNight) < 0.01, "step at " + d / r);
            prevDay = day;
            prevNight = night;
        }
        assertEquals(INTERSTELLAR_AMBIENT, prevDay, 1e-12);
        assertEquals(INTERSTELLAR_AMBIENT, prevNight, 1e-12);
        assertEquals(AMBIENT, ambient(MIN_DIRECT), 1e-12);
        assertEquals(AMBIENT, ambient(1), 1e-12);
    }
}
