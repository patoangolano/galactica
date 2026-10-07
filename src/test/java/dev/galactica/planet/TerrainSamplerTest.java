package dev.galactica.planet;

import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import org.junit.jupiter.api.Test;

import static dev.galactica.planet.SurfaceAtmosphereTest.sol;
import static org.junit.jupiter.api.Assertions.*;

class TerrainSamplerTest {

    private static TerrainSampler of(String name) {
        Body b = sol(name);
        return TerrainSampler.of(b, SurfaceGrid.of(b.radiusMetres()));
    }

    @Test
    void reliefFollowsSupportableHeightAndCompressesOnlyPastTheBudget() {
        // σ/(ρg): Everest at 1 g, capped at Olympus Mons and at 8.5 % of the radius.
        assertEquals(8848, TerrainSampler.reliefPeakMetres(1.0, 6.4e6), 1e-9);
        assertEquals(TerrainSampler.OLYMPUS_M, TerrainSampler.reliefPeakMetres(0.1, 6.4e6), 1e-9);
        assertEquals(0.085 * 11e3, TerrainSampler.reliefPeakMetres(0.0006, 11e3), 1e-9);
        // An Earth-size 1 g world fits the budget: true scale (885 blocks at 10 m/block).
        TerrainSampler terran = new TerrainSampler(BodyType.TERRAN, 1, 1.0, SurfaceGrid.of(6.371e6), false, false);
        assertEquals(1.0, terran.verticalScale(), 1e-9);
        assertEquals(8848 / SurfaceGrid.of(6.371e6).metresPerBlock(), terran.peakBlocks(), 1e-6);
        // Mars and the Moon exceed it and are compressed by one factor, honestly reported.
        TerrainSampler mars = of("Mars"), moon = of("Moon");
        assertEquals(TerrainSampler.PEAK_BUDGET, mars.peakBlocks(), 1e-9);
        assertEquals(TerrainSampler.PEAK_BUDGET / (21_900 / mars.grid().metresPerBlock()), mars.verticalScale(), 1e-9);
        assertTrue(moon.verticalScale() < 0.15 && mars.verticalScale() < 0.3, moon.verticalScale() + " " + mars.verticalScale());
        // Heavier worlds stay lower; mountain ranges widen with their height.
        TerrainSampler heavy = new TerrainSampler(BodyType.TERRAN, 1, 3.0, SurfaceGrid.of(9e6), false, false);
        assertTrue(heavy.peakBlocks() < terran.peakBlocks());
        assertTrue(heavy.featureScale() < terran.featureScale());
        assertEquals(TerrainSampler.PEAK_CAP, TerrainSampler.softCap(1e9, TerrainSampler.PEAK_KNEE, TerrainSampler.PEAK_CAP), 1e-6);
        assertEquals(500, TerrainSampler.softCap(500, TerrainSampler.PEAK_KNEE, TerrainSampler.PEAK_CAP), 1e-12);
    }

    @Test
    void deterministicAndBounded() {
        TerrainSampler a = of("Mars"), b = of("Mars");
        for (int i = 0; i < 2000; i++) {
            int x = i * 7919 - 500_000, z = i * 104_729 % 900_000 - 450_000;
            int h = a.height(x, z);
            assertEquals(h, b.height(x, z));
            assertTrue(h > TerrainSampler.MIN_Y && h < TerrainSampler.MAX_Y, "h=" + h);
            assertTrue(h <= TerrainSampler.SEA_LEVEL + TerrainSampler.PEAK_CAP && h >= TerrainSampler.SEA_LEVEL - TerrainSampler.DEPTH_CAP - 1, "h=" + h);
        }
    }

    @Test
    void longitudeSeamIsContinuous() {
        TerrainSampler t = of("Earth");
        int w = (int) t.grid().width();
        for (int z = -3000; z <= 3000; z += 250) {
            assertEquals(t.height(-w / 2, z), t.height(w / 2, z), "the seam column is the same place");
            assertTrue(Math.abs(t.height(w / 2 - 1, z) - t.height(-w / 2, z)) <= 4, "no cliff across the seam at z=" + z);
        }
    }

    @Test
    void earthHasSeasAndLand() {
        TerrainSampler t = of("Earth");
        int sea = 0, land = 0;
        TerrainSampler.Column c = new TerrainSampler.Column();
        for (int i = 0; i < 400; i++) {
            int x = i * 9_973, z = (i * 31_337) % 400_000 - 200_000;
            int h = t.height(x, z);
            t.column(x, z, h, 0, c);
            if (c.fluid == TerrainSampler.Material.WATER) {
                sea++;
                assertEquals(TerrainSampler.Material.WATER, t.materialAt(c, TerrainSampler.SEA_LEVEL, x, z));
            } else {
                land++;
            }
        }
        assertTrue(sea > 80 && land > 80, "sea " + sea + " land " + land);
    }

    @Test
    void oceanWorldIsMostlyWater() {
        TerrainSampler t = new TerrainSampler(BodyType.OCEAN, 99, 1.2, SurfaceGrid.of(8e6), false, false);
        int wet = 0;
        for (int i = 0; i < 400; i++) if (t.height(i * 7_001, (i * 13_007) % 300_000) < TerrainSampler.SEA_LEVEL) wet++;
        assertTrue(wet > 300, "wet " + wet);
    }

    @Test
    void lavaWorldsPoolLavaAtSeaLevel() {
        TerrainSampler t = new TerrainSampler(BodyType.LAVA, 7, 1.0, SurfaceGrid.of(6e6), false, false);
        TerrainSampler.Column c = new TerrainSampler.Column();
        boolean found = false;
        for (int i = 0; i < 2000 && !found; i++) {
            int x = i * 5_003, z = (i * 11_003) % 200_000;
            int h = t.height(x, z);
            t.column(x, z, h, 0, c);
            if (h < TerrainSampler.SEA_LEVEL) {
                assertEquals(TerrainSampler.Material.LAVA, t.materialAt(c, TerrainSampler.SEA_LEVEL, x, z));
                found = true;
            }
        }
        assertTrue(found, "some lava lake below the datum");
    }

    @Test
    void cratersAreBowlsWithRims() {
        double r = 50;
        assertEquals(-0.4 * r, TerrainSampler.craterProfile(0, r), 0.01 * r);
        assertTrue(TerrainSampler.craterProfile(1.0, r) > 0, "raised rim");
        assertEquals(0, TerrainSampler.craterProfile(1.8, r), 0.05);
    }

    @Test
    void airlessBodiesAreCrateredAirBodiesAreNot() {
        // Same body type and seed, only the crater flag differs: craters must actually dig into the ground.
        SurfaceGrid g = SurfaceGrid.of(1.7e6);
        TerrainSampler plain = new TerrainSampler(BodyType.BARREN, 5, 0.17, g, false, false);
        TerrainSampler pocked = new TerrainSampler(BodyType.BARREN, 5, 0.17, g, true, false);
        int deeper = 0;
        for (int x = 0; x < 4000; x += 8)
            for (int z = 0; z < 4000; z += 200) if (pocked.height(x, z) < plain.height(x, z) - 5) deeper++;
        assertTrue(deeper > 20, "crater floors found: " + deeper);
    }

    @Test
    void layeringFollowsPalette() {
        TerrainSampler mars = of("Mars");
        TerrainSampler.Column c = new TerrainSampler.Column();
        mars.column(0, 0, 150, 0, c);
        // Bright red sand, or dark basaltic sand where the macro field puts a dark lowland.
        TerrainSampler.Material top = mars.materialAt(c, 150, 0, 0);
        assertTrue(top == TerrainSampler.Material.RED_SAND || top == TerrainSampler.Material.COARSE_DIRT, top.name());
        assertEquals(TerrainSampler.Material.TERRACOTTA, mars.materialAt(c, 145, 0, 0));
        assertEquals(TerrainSampler.Material.STONE, mars.materialAt(c, 100, 0, 0));
        assertEquals(TerrainSampler.Material.AIR, mars.materialAt(c, 151, 0, 0));
        assertEquals(TerrainSampler.Material.BEDROCK, mars.materialAt(c, TerrainSampler.MIN_Y, 0, 0));
    }
}
