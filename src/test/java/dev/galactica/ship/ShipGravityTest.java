package dev.galactica.ship;

import dev.galactica.science.Units;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.galactica.ship.ShipGravity.Attractor;
import static org.junit.jupiter.api.Assertions.*;

class ShipGravityTest {
    private static final double MU_SUN = Units.G * Units.SOLAR_MASS, MU_EARTH = Units.G * Units.EARTH_MASS;
    private static final double EARTH_SOI = 9.25e8, EARTH_R = 6.371e6;

    private static double[] pull(Attractor... a) {
        return ShipGravity.pull(List.of(a));
    }

    @Test
    void inLowOrbitTheEarthPullsAtNearlyOneG() {
        double r = EARTH_R + 400e3;
        double[] g = pull(new Attractor(new double[] {Units.AU, 0, 0}, MU_SUN, Double.POSITIVE_INFINITY, 7e8),
                new Attractor(new double[] {0, 0, -r}, MU_EARTH, EARTH_SOI, EARTH_R));
        assertEquals(0.0, g[0], 1e-12, "the Sun is not the dominant body inside Earth's sphere");
        assertEquals(-MU_EARTH / (r * r), g[2], 1e-9);
        assertEquals(8.7, -g[2], 0.05, "ISS altitude");
    }

    @Test
    void outsideEverySphereTheStarPulls() {
        double[] g = pull(new Attractor(new double[] {Units.AU, 0, 0}, MU_SUN, Double.POSITIVE_INFINITY, 7e8),
                new Attractor(new double[] {0, 2e9, 0}, MU_EARTH, EARTH_SOI, EARTH_R));
        assertEquals(MU_SUN / (Units.AU * Units.AU), g[0], 1e-12);
        assertEquals(5.93e-3, g[0], 1e-4, "solar pull at 1 AU");
        assertEquals(0.0, g[1]);
    }

    @Test
    void nestedSpheresResolveToTheInnermost() {
        double muMoon = Units.G * 7.342e22;
        double[] g = pull(new Attractor(new double[] {0, 3.8e8, 0}, MU_EARTH, EARTH_SOI, EARTH_R),
                new Attractor(new double[] {0, 0, 1e7}, muMoon, 6.6e7, 1.737e6));
        assertEquals(0.0, g[1], 1e-12);
        assertEquals(muMoon / 1e14, g[2], 1e-12);
    }

    @Test
    void insideABodyThePullIsHeldAtItsSurfaceValue() {
        double[] g = pull(new Attractor(new double[] {1, 0, 0}, MU_EARTH, EARTH_SOI, EARTH_R));
        assertEquals(MU_EARTH / (EARTH_R * EARTH_R), g[0], 1e-9);
        assertArrayEquals(new double[3], pull());
    }
}
