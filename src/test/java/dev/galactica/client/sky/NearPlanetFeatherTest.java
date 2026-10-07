package dev.galactica.client.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clipmap level rim fade: opaque inside, transparent on the edge, colour untouched. */
class NearPlanetFeatherTest {
    private static final int OPAQUE = 0xFF336699;

    @Test
    void interiorStaysOpaque() {
        assertEquals(OPAQUE, NearPlanetRenderer.feather(OPAQUE, 0.5, 0.5));
        assertEquals(OPAQUE, NearPlanetRenderer.feather(OPAQUE, NearPlanetRenderer.LEVEL_FEATHER, 0.5));
    }

    @Test
    void rimIsTransparentWithSameColour() {
        int edge = NearPlanetRenderer.feather(OPAQUE, 0.0, 0.5);
        assertEquals(0, edge >>> 24);
        assertEquals(OPAQUE & 0xFFFFFF, edge & 0xFFFFFF);
        assertEquals(0, NearPlanetRenderer.feather(OPAQUE, 0.5, 1.0) >>> 24);
    }

    @Test
    void fadeIsMonotonicTowardsTheCentre() {
        int last = -1;
        for (int i = 0; i <= 10; i++) {
            int a = NearPlanetRenderer.feather(OPAQUE, NearPlanetRenderer.LEVEL_FEATHER * i / 10.0, 0.5) >>> 24;
            assertTrue(a >= last, "alpha must not drop towards the centre");
            last = a;
        }
        assertEquals(255, last);
    }
}
