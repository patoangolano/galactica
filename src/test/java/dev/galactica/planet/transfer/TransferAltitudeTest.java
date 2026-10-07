package dev.galactica.planet.transfer;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.planet.TerrainSampler;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransferAltitudeTest {

    static Body sol(String name) {
        for (Body p : SystemGenerator.generate(StarCatalog.SOL)) {
            if (p.name().equals(name)) return p;
            for (Body m : p.moons()) if (m.name().equals(name)) return m;
        }
        throw new AssertionError(name);
    }

    @Test
    void altitudeRoundTrip() {
        for (double mpb : new double[] {1.0, 2.7, 5.3, 10.02}) {
            for (double y = -64; y <= 1800; y += 37.5) {
                double alt = TransferAltitude.realAltitude(y, TerrainSampler.SEA_LEVEL, mpb);
                assertEquals(y, TransferAltitude.blockY(alt, TerrainSampler.SEA_LEVEL, mpb), 1e-9);
            }
        }
    }

    @Test
    void switchAltitudesMatchThePlan() {
        double earth = TransferAltitude.switchAltitude(TerrainSampler.SEA_LEVEL, SurfaceGrid.of(sol("Earth").radiusMetres()).metresPerBlock());
        double mars = TransferAltitude.switchAltitude(TerrainSampler.SEA_LEVEL, SurfaceGrid.of(sol("Mars").radiusMetres()).metresPerBlock());
        double moon = TransferAltitude.switchAltitude(TerrainSampler.SEA_LEVEL, SurfaceGrid.of(sol("Moon").radiusMetres()).metresPerBlock());
        assertEquals(14_000, earth, 200);
        assertEquals(7_500, mars, 200);
        assertEquals(3_800, moon, 200);
    }

    @Test
    void entryThenExitKeepsAltitudeWithinOneBlock() {
        // Space altitude h → surface row → (craft untouched) → space altitude again.
        SurfaceGrid g = SurfaceGrid.of(sol("Mars").radiusMetres());
        double h = TransferAltitude.switchAltitude(TerrainSampler.SEA_LEVEL, g.metresPerBlock()) - 3.3;
        double y = TransferAltitude.blockY(h, TerrainSampler.SEA_LEVEL, g.metresPerBlock());
        double back = TransferAltitude.realAltitude(y, TerrainSampler.SEA_LEVEL, g.metresPerBlock());
        assertEquals(h, back, g.metresPerBlock());
    }

    @Test
    void hysteresisAndExitAboveEntry() {
        double h = TransferAltitude.switchAltitude(TerrainSampler.SEA_LEVEL, 10);
        double exit = TransferAltitude.exitAltitude(TerrainSampler.SEA_LEVEL, 10);
        assertTrue(exit > h);
        assertFalse(TransferAltitude.reentryArmed(exit, h), "a fresh take-off must not re-enter at once");
        assertTrue(TransferAltitude.reentryArmed(1.31 * h, h));
    }

    @Test
    void secondsToSwitch() {
        assertEquals(10, TransferAltitude.secondsToSwitch(2000, 1000, 100, 60), 1e-12);
        assertEquals(60, TransferAltitude.secondsToSwitch(2000, 1000, -5, 60), 1e-12);
        assertEquals(0, TransferAltitude.secondsToSwitch(900, 1000, 5, 60), 1e-12);
    }

    @Test
    void earthOverworldMapRoundTripAndEarthIndex() {
        Body earth = sol("Earth");
        List<Body> planets = SystemGenerator.generate(StarCatalog.SOL);
        assertEquals("Earth", planets.get(EarthOverworld.EARTH_PLANET_INDEX).name());
        assertTrue(EarthOverworld.isEarth("Sol", EarthOverworld.EARTH_PLANET_INDEX, -1));
        assertFalse(EarthOverworld.isEarth("Sol", EarthOverworld.EARTH_PLANET_INDEX, 0));
        EarthOverworld map = new EarthOverworld(SurfaceGrid.of(earth.radiusMetres()), 120.5, -48.5);
        assertEquals(120.5, map.blockX(0), 1e-9);
        for (double lat = -1.4; lat <= 1.4; lat += 0.2) {
            for (double lon = -3.0; lon <= 3.0; lon += 0.5) {
                assertEquals(lat, map.latitude(map.blockZ(lat)), 1e-9);
                assertEquals(lon, map.longitude(map.blockX(lon)), 1e-9);
            }
        }
        assertTrue(map.blockZ(0.5) < map.blockZ(0), "north is −Z");
    }

    @Test
    void takeOffThatTurnsBackCanLandAgain() {
        double mpb = 10.0;
        double h = TransferAltitude.switchAltitude(TransferAltitude.OVERWORLD_SEA_LEVEL, mpb);
        double exit = TransferAltitude.exitAltitude(TransferAltitude.OVERWORLD_SEA_LEVEL, mpb);
        // Still climbing just after the take-off: blocked, so it cannot fall back through the trigger at once.
        assertTrue(TransferAltitude.reentryBlocked(exit, h, 50));
        // Turned back down below the 1.3x band: never blocked (the old Schmitt band made this craft sink forever).
        assertFalse(TransferAltitude.reentryBlocked(exit, h, -20));
        assertFalse(TransferAltitude.reentryBlocked(1.05 * h, h, -1));
        // Hovering (no radial rate) is not climbing either.
        assertFalse(TransferAltitude.reentryBlocked(exit, h, 0));
        // High enough: armed whatever the direction.
        assertFalse(TransferAltitude.reentryBlocked(1.31 * h, h, 100));
    }

    @Test
    void entryRowsClearEveryBuildLimit() {
        // Vanilla Overworld (top 319) and the tall planet slots (top 1279) keep the classic rows.
        assertEquals(TransferAltitude.ENTRY_Y, TransferAltitude.entryY(319));
        assertEquals(TransferAltitude.ENTRY_Y, TransferAltitude.entryY(dev.galactica.planet.TerrainSampler.MAX_Y));
        assertEquals(TransferAltitude.EXIT_Y, TransferAltitude.exitY(dev.galactica.planet.TerrainSampler.MAX_Y));
        // A Tectonic Overworld at the 26.3 ceiling (top 2031): rows move up together.
        for (int top : new int[] {319, 639, 1279, 1500, 2031}) {
            double entry = TransferAltitude.entryY(top);
            assertTrue(entry - 64 > top, "the whole arrival band is above build height for top " + top);
            assertTrue(entry - 100 > top, "no terrain reaches the touchdown margin for top " + top);
            assertEquals(TransferAltitude.EXIT_ABOVE_ENTRY, TransferAltitude.exitY(top) - entry, 1e-9);
        }
        // The planet slot's highest possible ground is far below the arrival band.
        assertTrue(dev.galactica.planet.TerrainSampler.SEA_LEVEL + dev.galactica.planet.TerrainSampler.PEAK_CAP
                < TransferAltitude.ENTRY_Y - 100);
    }
}
