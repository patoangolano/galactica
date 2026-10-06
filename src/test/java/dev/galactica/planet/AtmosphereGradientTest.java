package dev.galactica.planet;

import dev.galactica.system.Body;
import org.junit.jupiter.api.Test;

import static dev.galactica.planet.SurfaceAtmosphereTest.sol;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereGradientTest {

    private static final double MU60 = Math.sin(Math.toRadians(60));

    @Test
    void seaLevelZenithIsTheBiomeSkyColour() {
        for (String name : new String[] {"Earth", "Mars", "Titan", "Venus"}) {
            Body b = sol(name);
            AtmosphereGradient a = AtmosphereGradient.of(b);
            double[] rgb = new double[3];
            a.sky(1, MU60, MU60, 0, rgb);
            int c = SurfaceAtmosphere.skyColour(b);
            assertEquals(AtmosphereGradient.decode((c >> 16) & 0xFF), rgb[0], 1e-9, name);
            assertEquals(AtmosphereGradient.decode((c >> 8) & 0xFF), rgb[1], 1e-9, name);
            assertEquals(AtmosphereGradient.decode(c & 0xFF), rgb[2], 1e-9, name);
        }
    }

    @Test
    void skyDarkensToBlackWithAltitude() {
        Body earth = sol("Earth");
        AtmosphereGradient a = AtmosphereGradient.of(earth);
        double h = earth.scaleHeight();
        double prev = a.brightness(0);
        assertEquals(1, prev, 1e-9);
        for (double alt = 1000; alt < 200_000; alt *= 1.5) {
            double b = a.brightness(alt);
            assertTrue(b < prev, "monotone at " + alt);
            prev = b;
        }
        // Optically thin above a few km: brightness follows the column, e^(−h/H).
        // Nearly thin: brightness follows the column e^(−h/H), a little above it because the low
        // observer also lost some light to extinction.
        double b2 = a.brightness(2 * h);
        assertTrue(b2 > Math.exp(-2) && b2 < 1.3 * Math.exp(-2), "at 2H: " + b2);
        assertTrue(a.brightness(earth.atmosphereTopMetres()) < 1e-4, "black at the top of the atmosphere");
    }

    @Test
    void earthZenithIsBluerThanTheHorizon() {
        AtmosphereGradient a = AtmosphereGradient.of(sol("Earth"));
        double[] zen = new double[3], hor = new double[3];
        a.sky(1, 0, MU60, 0, zen);
        a.sky(0.02, 0, MU60, 0, hor);
        assertTrue(zen[2] / zen[0] > hor[2] / hor[0], "horizon is whiter");
        assertTrue(AtmosphereGradient.luminance(hor) > AtmosphereGradient.luminance(zen), "horizon is brighter");
    }

    @Test
    void sunsetReddensTheSkyNearTheSun() {
        AtmosphereGradient a = AtmosphereGradient.of(sol("Earth"));
        double[] noon = new double[3], dusk = new double[3];
        a.sky(0.05, 0.9, 0.9, 0, noon);
        a.sky(0.05, 0.99, 0.01, 0, dusk);
        assertTrue(dusk[0] / dusk[2] > 2 * noon[0] / noon[2], "red/blue " + dusk[0] / dusk[2] + " vs " + noon[0] / noon[2]);
        double[] night = new double[3];
        a.sky(1, 0, -0.3, 0, night);
        assertEquals(0, AtmosphereGradient.luminance(night), 1e-12, "no sky light with the sun far below the horizon");
    }

    @Test
    void airlessBodiesHaveNoSky() {
        AtmosphereGradient a = AtmosphereGradient.of(sol("Moon"));
        assertTrue(a.vacuum());
        double[] rgb = new double[3];
        a.sky(1, 0.5, 0.8, 0, rgb);
        assertEquals(0, rgb[0] + rgb[1] + rgb[2], 0);
        assertEquals(0, a.brightness(0), 0);
    }

    @Test
    void groundSeenFromSpaceIsVeiledByTheWholeColumn() {
        AtmosphereGradient a = AtmosphereGradient.of(sol("Earth"));
        double[] in = new double[3], t = new double[3], inLow = new double[3], tLow = new double[3];
        a.lookDown(1, -0.5, 0.8, 1e7, in, t);
        a.lookDown(1, -0.5, 0.8, 100, inLow, tLow);
        for (int c = 0; c < 3; c++) {
            assertTrue(t[c] > 0 && t[c] < 1);
            assertTrue(tLow[c] > t[c], "less air below a low observer");
            assertTrue(in[c] > inLow[c]);
        }
        assertTrue(t[2] < t[0], "blue is extinguished more than red");
        assertTrue(in[2] > in[0], "and scattered more: the blue veil of Earth from orbit");
    }

    @Test
    void airlightGrowsWithDistance() {
        AtmosphereGradient a = AtmosphereGradient.of(sol("Earth"));
        double[] in1 = new double[3], t1 = new double[3], in2 = new double[3], t2 = new double[3];
        a.horizontal(5_000, 0, 0, 0.8, in1, t1);
        a.horizontal(80_000, 0, 0, 0.8, in2, t2);
        assertTrue(t2[1] < t1[1] && in2[1] > in1[1]);
        double[] hor = new double[3];
        a.sky(0, 0, 0.8, 0, hor);
        assertTrue(in2[1] < hor[1], "airlight never exceeds the horizon sky");
    }

    @Test
    void airmassIsKastenYoung() {
        assertEquals(1.0, AtmosphereGradient.airmass(1), 1e-3);
        assertEquals(2.0, AtmosphereGradient.airmass(0.5), 0.01);
        assertEquals(37.92, AtmosphereGradient.airmass(0), 0.1);
    }
}
