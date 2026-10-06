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
    void reliefScalesInverselyWithGravityWithinClamp() {
        assertEquals(60, TerrainSampler.reliefFor(1.0), 1e-9);
        assertEquals(60 / 0.3794, of("Mars").amplitude(), 1.5);
        assertEquals(TerrainSampler.MAX_RELIEF, of("Moon").amplitude(), 1e-9);
        assertEquals(TerrainSampler.MIN_RELIEF, TerrainSampler.reliefFor(5.0), 1e-9);
        assertTrue(of("Mars").amplitude() > of("Earth").amplitude());
    }

    @Test
    void deterministicAndBounded() {
        TerrainSampler a = of("Mars"), b = of("Mars");
        for (int i = 0; i < 2000; i++) {
            int x = i * 7919 - 500_000, z = i * 104_729 % 900_000 - 450_000;
            int h = a.height(x, z);
            assertEquals(h, b.height(x, z));
            assertTrue(h > TerrainSampler.MIN_Y && h < TerrainSampler.MAX_Y, "h=" + h);
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
        assertEquals(TerrainSampler.Material.RED_SAND, mars.materialAt(c, 150, 0, 0));
        assertEquals(TerrainSampler.Material.TERRACOTTA, mars.materialAt(c, 145, 0, 0));
        assertEquals(TerrainSampler.Material.STONE, mars.materialAt(c, 100, 0, 0));
        assertEquals(TerrainSampler.Material.AIR, mars.materialAt(c, 151, 0, 0));
        assertEquals(TerrainSampler.Material.BEDROCK, mars.materialAt(c, TerrainSampler.MIN_Y, 0, 0));
    }
}
