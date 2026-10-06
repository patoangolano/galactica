package dev.galactica;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.GalacticPos;
import dev.galactica.science.Blackbody;
import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.SpectralClass;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.star.StellarPhysics;
import dev.galactica.system.Body;
import dev.galactica.system.MassRadius;
import dev.galactica.system.Orbit;
import dev.galactica.system.SolarSystem;
import dev.galactica.system.SystemGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScienceTest {

    @Test
    void localStellarDensityMatchesRecons() {
        double n = GalaxyModel.starDensity(GalaxyModel.R0, 0, GalaxyModel.Z0);
        assertEquals(0.1, n, 0.03, "local density should be ~0.1 stars/pc^3");
    }

    @Test
    void totalStarCountIsAboutAHundredBillion() {
        double total = 0, st = 500;
        for (double x = -30000; x < 30000; x += st)
            for (double y = -30000; y < 30000; y += st)
                for (double z = -6000; z < 6000; z += 100)
                    total += GalaxyModel.starDensity(x + st / 2, y + st / 2, z + 50) * st * st * 100;
        assertTrue(total > 5e10 && total < 4e11, "total = " + total);
    }

    @Test
    void generationIsDeterministic() {
        Galaxy a = new Galaxy(7), b = new Galaxy(7);
        SectorKey k = new SectorKey(817, 3, 1);
        assertEquals(a.starCount(k), b.starCount(k));
        for (int i = 0; i < a.starCount(k); i++) assertEquals(a.star(k, i), b.star(k, i));
        Star s = a.star(k, 0);
        assertEquals(SystemGenerator.generate(s), SystemGenerator.generate(s));
    }

    @Test
    void kroupaImfFractions() {
        // Kroupa (2001): ~10 % of stars above 1 M☉, ~0.6 % above 8 M☉ (by number, 0.08–150 M☉).
        assertEquals(0.10, StellarPhysics.fractionAbove(1.0), 0.02);
        assertEquals(0.006, StellarPhysics.fractionAbove(8.0), 0.002);
        SeedRng rng = new SeedRng(1);
        int above1 = 0, n = 200_000;
        for (int i = 0; i < n; i++) if (StellarPhysics.sampleInitialMass(rng) > 1.0) above1++;
        assertEquals(StellarPhysics.fractionAbove(1.0), above1 / (double) n, 0.005);
    }

    @Test
    void sunIsAG2DwarfWithTheRightMagnitudeAndColour() {
        Star sol = StarCatalog.SOL;
        assertEquals("G2V", sol.spectralType());
        assertEquals(4.83, sol.absoluteMagnitude(), 0.1);
        int rgb = Blackbody.srgbPacked(5772);
        assertTrue(((rgb >> 16) & 0xFF) >= ((rgb) & 0xFF), "the Sun is white with a slight warm tint");
        int hot = Blackbody.srgbPacked(30_000), cool = Blackbody.srgbPacked(3_000);
        assertTrue((hot & 0xFF) > ((hot >> 16) & 0xFF), "O/B stars are blue");
        assertTrue(((cool >> 16) & 0xFF) > (cool & 0xFF), "M dwarfs are orange-red");
    }

    @Test
    void spectralClassBoundaries() {
        assertEquals('O', SpectralClass.letter(40_000));
        assertEquals('A', SpectralClass.letter(9_000));
        assertEquals('G', SpectralClass.letter(5_772));
        assertEquals('M', SpectralClass.letter(3_000));
    }

    @Test
    void catalogStarsLandWhereTheyShould() {
        Star acen = StarCatalog.byName("Alpha Centauri A");
        Star sol = StarCatalog.SOL;
        assertEquals(1.3474, acen.distanceTo(sol.x(), sol.y(), sol.z()), 1e-3);
        // l = 315.7°: toward the Galactic Centre (−x) and against rotation (+y in this frame).
        assertTrue(acen.x() < sol.x() && acen.y() > sol.y());
    }

    @Test
    void solarSystemOrbitsMatchKepler() {
        List<Body> planets = SolarSystem.planets();
        Body earth = planets.get(2);
        assertEquals(365.25, earth.orbit().periodSeconds() / Units.SECONDS_PER_DAY, 0.3);
        assertEquals(1.0, earth.surfaceGravityG(), 0.01);
        assertEquals(11.19, earth.escapeVelocity() / 1000, 0.05);
        assertEquals(27.3, earth.moons().get(0).orbit().periodSeconds() / Units.SECONDS_PER_DAY, 0.2);
        Body jupiter = planets.get(4);
        assertEquals(11.86, jupiter.orbit().periodSeconds() / Units.SECONDS_PER_YEAR, 0.02);
    }

    @Test
    void keplerSolverRoundTrips() {
        for (double e : new double[] {0, 0.1, 0.5, 0.9, 0.99}) {
            for (double m = 0; m < 6.28; m += 0.37) {
                double ea = Orbit.eccentricAnomaly(m, e);
                assertEquals(m, ea - e * Math.sin(ea), 1e-9);
            }
        }
        Orbit o = new Orbit(Units.AU, 0.3, 0.2, 1, 2, 0, Units.SOLAR_MASS);
        double[] p = new double[3];
        o.positionAt(0, p);
        double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        assertEquals(o.periapsis(), r, 1.0, "mean anomaly 0 is periapsis");
    }

    @Test
    void massRadiusRelationIsContinuous() {
        assertEquals(1.0, MassRadius.radiusFromMass(1.0), 1e-9);
        assertEquals(MassRadius.radiusFromMass(2.039), MassRadius.radiusFromMass(2.041), 0.01);
        assertEquals(11.2, MassRadius.radiusFromMass(317.8), 1.5, "Jupiter");
        assertEquals(3.0, MassRadius.radiusFromMass(MassRadius.massFromRadius(3.0)), 1e-9);
    }

    @Test
    void remnantsAppearInOldPopulations() {
        Galaxy g = new Galaxy(3);
        SectorKey k = StarCatalog.SOL_SECTOR;
        int wd = 0, total = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                SectorKey s = k.offset(dx, dy, 0);
                for (int i = 0; i < g.starCount(s); i++) {
                    total++;
                    if (g.star(s, i).kind() == StarKind.WHITE_DWARF) wd++;
                }
            }
        }
        // White dwarfs are ~6 % of the local census (RECONS 10 pc: 20 of ~320 stars).
        assertEquals(0.06, wd / (double) total, 0.03);
    }

    @Test
    void galacticPosKeepsPrecisionAcrossTheGalaxy() {
        GalacticPos far = GalacticPos.ofParsecs(-15_000, 9_000, 120);
        GalacticPos moved = far.plusMetres(0.25, -0.5, 1.0);
        double[] d = moved.metresFrom(far);
        assertEquals(0.25, d[0], 1e-3);
        assertEquals(-0.5, d[1], 1e-3);
        assertEquals(1.0, d[2], 1e-3);
        GalacticPos crossed = far.plusMetres(10 * Units.PARSEC * 2.5, 0, 0);
        assertEquals(10 * Units.PARSEC * 2.5, crossed.distanceMetres(far), 1e5);
        int sectorsCrossed = crossed.sector().ix() - far.sector().ix();
        assertTrue(sectorsCrossed == 2 || sectorsCrossed == 3, "25 pc spans two or three 10 pc sectors");
        assertTrue(crossed.ox() >= 0 && crossed.ox() < GalacticPos.CELL, "offset stays normalised");
        assertEquals(-15_000, far.xPc(), 1e-6);
    }
}
