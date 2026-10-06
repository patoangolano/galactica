package dev.galactica.vfx;

import dev.galactica.client.vfx.star.StarSurfaceModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarSurfaceModelTest {
    // (Teff, M, R) of reference stars.
    private static final double[] SUN = {5772, 1.0, 1.0};
    private static final double[] PROXIMA = {3042, 0.122, 0.154};
    private static final double[] ARCTURUS = {4286, 1.08, 25.4};
    private static final double[] BETELGEUSE = {3600, 18, 900};

    private static double logG(double[] s) {
        return StarSurfaceModel.logG(s[1], s[2]);
    }

    private static double physCells(double[] s) {
        return StarSurfaceModel.granulesPerRadius(s[2], s[0], logG(s));
    }

    @Test
    void solarGravityAndScaleHeight() {
        assertEquals(4.438, logG(SUN), 0.005);
        double h = StarSurfaceModel.pressureScaleHeightKm(SUN[0], logG(SUN));
        assertTrue(h > 100 && h < 180, "solar photospheric H ~ 130 km, got " + h);
        double granule = StarSurfaceModel.granuleSizeKm(SUN[0], logG(SUN));
        assertTrue(granule > 800 && granule < 1500, "solar granules ~ 1 Mm, got " + granule);
    }

    @Test
    void granulesGetFewerAndBiggerAsGravityDrops() {
        double proxima = physCells(PROXIMA), sun = physCells(SUN), arcturus = physCells(ARCTURUS), betelgeuse = physCells(BETELGEUSE);
        assertTrue(proxima > sun && sun > arcturus && arcturus > betelgeuse,
                proxima + " > " + sun + " > " + arcturus + " > " + betelgeuse);
        // Betelgeuse: a handful of giant cells across the disc (Schwarzschild 1975, Chiavassa 2010).
        assertTrue(betelgeuse > 2 && betelgeuse < 30, "Betelgeuse cells per radius " + betelgeuse);
        assertTrue(sun > 300 && sun < 1200, "Sun cells per radius " + sun);
    }

    @Test
    void visualGranuleScaleKeepsTheOrderingAndStaysDrawable() {
        double prev = Double.MAX_VALUE;
        for (double lg = 5.2; lg >= -0.5; lg -= 0.1) {
            double phys = StarSurfaceModel.granulesPerRadius(Math.sqrt(Math.pow(10, 4.438 - lg)), 4500, lg);
            double v = StarSurfaceModel.visualCellsPerRadius(phys);
            assertTrue(v <= prev + 1e-9, "visual cells must not grow as log g drops (lg=" + lg + ")");
            assertTrue(v >= 2 && v <= 64);
            prev = v;
        }
        double sunVis = StarSurfaceModel.visualCellsPerRadius(physCells(SUN));
        double betVis = StarSurfaceModel.visualCellsPerRadius(physCells(BETELGEUSE));
        assertTrue(sunVis > 30, "Sun shows fine granulation: " + sunVis);
        assertTrue(betVis < 8, "Betelgeuse shows few huge cells: " + betVis);
    }

    @Test
    void granuleLifetimes() {
        assertEquals(670, StarSurfaceModel.granuleLifetimeSeconds(SUN[0], logG(SUN)), 15);
        assertEquals(20, StarSurfaceModel.visualLifetimeSeconds(670), 1e-6);
        double giant = StarSurfaceModel.granuleLifetimeSeconds(BETELGEUSE[0], logG(BETELGEUSE));
        assertTrue(giant > 1e6, "Betelgeuse cells live months-years, got " + giant + " s");
        assertTrue(StarSurfaceModel.visualLifetimeSeconds(giant) <= 90);
        assertTrue(StarSurfaceModel.visualLifetimeSeconds(1) >= 8);
    }

    @Test
    void limbDarkeningFollowsClaret() {
        double[] sun = StarSurfaceModel.limbCoefficients(5750);
        assertEquals(0.44, sun[0], 0.01);
        assertEquals(0.26, sun[1], 0.01);
        // Solar limb/centre ~0.3 at 550 nm (Neckel & Labs 1994).
        double limb = StarSurfaceModel.limbIntensity(0, sun[0], sun[1]);
        assertTrue(limb > 0.2 && limb < 0.4, "solar limb/centre " + limb);
        double prevLimb = 0;
        for (double t = 4000; t <= 40000; t *= 1.1) {
            double[] ab = StarSurfaceModel.limbCoefficients(t);
            double i0 = StarSurfaceModel.limbIntensity(0, ab[0], ab[1]);
            assertTrue(i0 > 0.05, "intensity positive at the limb for T=" + t);
            assertTrue(i0 >= prevLimb - 1e-9, "hotter stars darken less (T=" + t + ")");
            prevLimb = i0;
            // Monotonic profile: I decreases from centre to limb.
            double prev = 2;
            for (double mu = 1; mu >= 0; mu -= 0.05) {
                double i = StarSurfaceModel.limbIntensity(mu, ab[0], ab[1]);
                assertTrue(i <= prev + 1e-12);
                prev = i;
            }
        }
        assertEquals(1.0, StarSurfaceModel.limbIntensity(1, sun[0], sun[1]), 1e-12);
    }

    @Test
    void solarLimbReddensLikeNeckelAndLabs() {
        // Neckel & Labs 1994: extreme-limb/centre about 0.45 at 650 nm and 0.25 at 450 nm.
        double[] sun = StarSurfaceModel.limbCoefficients(5750);
        double red = StarSurfaceModel.limbIntensity(0, sun[0], sun[1], 0);
        double green = StarSurfaceModel.limbIntensity(0, sun[0], sun[1], 1);
        double blue = StarSurfaceModel.limbIntensity(0, sun[0], sun[1], 2);
        assertEquals(0.45, red, 0.04, "red limb/centre");
        assertEquals(0.25, blue, 0.04, "blue limb/centre");
        assertTrue(red > green && green > blue, "limb reddens");
        for (int c = 0; c < 3; c++) assertEquals(1.0, StarSurfaceModel.limbIntensity(1, sun[0], sun[1], c), 1e-12);
    }

    @Test
    void granulationContrast() {
        double sun = StarSurfaceModel.granulationContrast(SUN[0], logG(SUN));
        assertTrue(sun > 0.14 && sun < 0.19, "Sun ~16 % rms, got " + sun);
        assertTrue(StarSurfaceModel.granulationContrast(6400, 4.3) > sun, "F stars show stronger granulation");
        assertTrue(StarSurfaceModel.granulationContrast(4000, 4.6) < sun, "K/M dwarfs weaker");
        assertEquals(0, StarSurfaceModel.granulationContrast(9500, 4.1), 1e-9, "A stars: radiative surface, no granulation");
    }

    @Test
    void displayKeepsGranulationVisibleAndIsNotAWhiteBlob() {
        double centre = StarSurfaceModel.displayDisc(1.0, 1.0);
        assertTrue(centre < 0.95 && centre > 0.6, "disc centre display value " + centre);
        double c = StarSurfaceModel.granulationContrast(SUN[0], logG(SUN));
        double bright = StarSurfaceModel.displayDisc(1.0, 1 + c), dark = StarSurfaceModel.displayDisc(1.0, 1 - c);
        double displayed = (bright - dark) / (2 * centre);
        assertTrue(displayed > 0.12, "displayed granulation contrast " + displayed);
        double[] ab = StarSurfaceModel.limbCoefficients(SUN[0]);
        double limb = StarSurfaceModel.displayDisc(StarSurfaceModel.limbIntensity(0.1, ab[0], ab[1]), 1.0);
        assertTrue(limb < 0.8 * centre, "limb visibly darker on screen: " + limb + " vs " + centre);
    }

    @Test
    void activityAndRotation() {
        assertTrue(StarSurfaceModel.meanActivity(0.5, 5800) > StarSurfaceModel.meanActivity(8, 5800), "young stars are more active");
        assertEquals(0, StarSurfaceModel.meanActivity(0.5, 9000), 1e-9, "no dynamo in radiative envelopes");
        assertTrue(StarSurfaceModel.meanActivity(10, 3100) >= 1.5, "old M dwarfs stay active");
        assertEquals(StarSurfaceModel.SUN_ROTATION_DAYS, StarSurfaceModel.rotationPeriodDays(4.6, 1, 5772), 1e-9);
        assertTrue(StarSurfaceModel.rotationPeriodDays(4.6, 25, 4300) > 1000, "giants rotate slowly");
        assertEquals(1200, 2 * Math.PI / StarSurfaceModel.visualRotationRate(StarSurfaceModel.SUN_ROTATION_DAYS), 1e-6);
        assertEquals(Math.toRadians(30), StarSurfaceModel.sporerLatitude(0), 1e-12);
        assertTrue(StarSurfaceModel.sporerLatitude(0.9) < Math.toRadians(12), "spots migrate toward the equator");
        assertTrue(StarSurfaceModel.cycleShape(0.35) > 0.99 && StarSurfaceModel.cycleShape(0.0) < 0.2);
        assertTrue(StarSurfaceModel.flareRatePerMinute(3100, 1.5) > 0);
        assertEquals(0, StarSurfaceModel.flareRatePerMinute(5772, 1.0), 1e-12);
    }
}
