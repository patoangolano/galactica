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
}
