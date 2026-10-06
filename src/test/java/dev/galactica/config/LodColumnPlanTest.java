package dev.galactica.config;

import dev.galactica.compat.dh.DhLevels;
import dev.galactica.compat.dh.LodColumnPlan;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.planet.TerrainSampler;
import dev.galactica.planet.TerrainSampler.Material;
import dev.galactica.system.BodyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DH compat's pure half (kept with the config tests: this area owns only this test package).
 * Every planned column must satisfy DH 3.3.4's validator and agree block-for-block with
 * {@link TerrainSampler#materialAt}, which is what {@code PlanetChunkGenerator} places.
 */
class LodColumnPlanTest {
    private static final int MIN_Y = TerrainSampler.MIN_Y, HEIGHT = TerrainSampler.MAX_Y - TerrainSampler.MIN_Y + 1;

    @Test
    void spansAreContiguousValidAndMatchTheChunkGenerator() {
        TerrainSampler[] worlds = {
                TerrainSampler.placeholder(),
                new TerrainSampler(BodyType.TERRAN, 42L, 1.0, SurfaceGrid.of(1.0e5), false, false),
                new TerrainSampler(BodyType.OCEAN, 7L, 0.8, SurfaceGrid.of(1.0e5), false, true),
                new TerrainSampler(BodyType.LAVA, 9L, 1.2, SurfaceGrid.of(1.0e5), true, false),
        };
        LodColumnPlan plan = new LodColumnPlan();
        TerrainSampler.Column ref = new TerrainSampler.Column();
        int wet = 0;
        for (TerrainSampler t : worlds) {
            for (int i = 0; i < 400; i++) {
                int x = i * 977 - 150_000, z = i * 613 + 20_000;
                int h = t.height(x, z);
                int slope = Math.abs(t.height(x + 1, z) - h);
                plan.plan(t, x, z, h, slope, 1, MIN_Y, HEIGHT);
                validateLikeDh(plan);
                t.column(x, z, h, slope, ref);
                if (ref.fluid != Material.AIR) wet++;
                for (int k = 0; k < plan.count(); k++) {
                    LodColumnPlan.Span s = plan.span(k);
                    for (int yRel = s.bottom; yRel < s.top; yRel += Math.max(1, (s.top - s.bottom) / 7)) {
                        int y = yRel + MIN_Y;
                        assertEquals(t.materialAt(ref, y, x, z), s.material, "y " + y + " in " + t.type() + " column " + x + "," + z);
                    }
                    assertEquals(t.materialAt(ref, s.top - 1 + MIN_Y, x, z), s.material, "top block of span");
                }
            }
        }
        assertTrue(wet > 50, "the sample must include seas, got " + wet);
    }

    @Test
    void submergedFloorsLoseSkyLightAndAirIsLit() {
        TerrainSampler ocean = new TerrainSampler(BodyType.OCEAN, 7L, 0.8, SurfaceGrid.of(1.0e5), false, false);
        LodColumnPlan plan = new LodColumnPlan();
        for (int i = 0; i < 2000; i++) {
            int x = i * 389, z = -i * 211;
            int h = ocean.height(x, z);
            if (h >= TerrainSampler.SEA_LEVEL - 20) continue;
            plan.plan(ocean, x, z, h, 0, 1, MIN_Y, HEIGHT);
            LodColumnPlan.Span floor = null, last = plan.span(plan.count() - 1);
            for (int k = 0; k < plan.count(); k++) {
                if (plan.span(k).top == h + 1 - MIN_Y) floor = plan.span(k);
            }
            assertNotNull(floor);
            assertEquals(0, floor.skyLight, ">15 blocks of water leaves no sky light");
            assertEquals(Material.AIR, last.material);
            assertEquals(15, last.skyLight);
            assertEquals(HEIGHT, last.top, "air reaches the build limit");
            return;
        }
        fail("no deep ocean column found");
    }

    @Test
    void coarseColumnsUseMeanGradientForSlope() {
        TerrainSampler terran = new TerrainSampler(new dev.galactica.planet.PlanetMacro(BodyType.TERRAN, 1L, true, 0.3), 1.0, SurfaceGrid.of(1.0e5), false, false);
        LodColumnPlan plan = new LodColumnPlan();
        int h = TerrainSampler.SEA_LEVEL + 20;
        // A 10-block rise across a 16-block LOD column is gentle (≈0.6 per block): grass, not bare stone.
        plan.plan(terran, 0, 0, h, 10, 16, MIN_Y, HEIGHT);
        assertEquals(Material.GRASS_BLOCK, topSolid(plan));
        // The same rise across one block is a cliff.
        plan.plan(terran, 0, 0, h, 10, 1, MIN_Y, HEIGHT);
        assertEquals(Material.STONE, topSolid(plan));
    }

    @Test
    void coarseSlopeRoundsToNearestBlockNotUp() {
        assertEquals(2, LodColumnPlan.perBlockSlope(33, 16), "2.06 blocks/block is a 2-block gradient");
        assertEquals(3, LodColumnPlan.perBlockSlope(40, 16), "2.5 rounds half up");
        assertEquals(3, LodColumnPlan.perBlockSlope(44, 16));
        assertEquals(7, LodColumnPlan.perBlockSlope(7, 1), "full detail keeps the raw step");
        TerrainSampler terran = new TerrainSampler(new dev.galactica.planet.PlanetMacro(BodyType.TERRAN, 1L, true, 0.3), 1.0, SurfaceGrid.of(1.0e5), false, false);
        LodColumnPlan plan = new LodColumnPlan();
        int h = TerrainSampler.SEA_LEVEL + 20;
        // Mean gradient 2.06 is below the bare-rock threshold of 3: the coarse column stays grass,
        // as the full-detail blocks it averages over would be.
        plan.plan(terran, 0, 0, h, 33, 16, MIN_Y, HEIGHT);
        assertEquals(Material.GRASS_BLOCK, topSolid(plan));
        plan.plan(terran, 0, 0, h, 44, 16, MIN_Y, HEIGHT);
        assertEquals(Material.STONE, topSolid(plan));
    }

    @Test
    void iceCapDampensSkyLightLikeOneMoreBlockOfWater() {
        TerrainSampler ocean = new TerrainSampler(BodyType.OCEAN, 7L, 0.8, SurfaceGrid.of(1.0e5), false, true);
        LodColumnPlan plan = new LodColumnPlan();
        TerrainSampler.Column ref = new TerrainSampler.Column();
        int checked = 0;
        for (int i = 0; i < 20_000 && checked < 20; i++) {
            int x = i * 977 - 150_000, z = i * 613 + 20_000;
            int h = ocean.height(x, z);
            ocean.column(x, z, h, 0, ref);
            if (!ref.iceCap || ref.fluid == Material.AIR || ref.fluidTop <= h + 1) continue;
            plan.plan(ocean, x, z, h, 0, 1, MIN_Y, HEIGHT);
            LodColumnPlan.Span floor = null, fluid = null, ice = null;
            for (int k = 0; k < plan.count(); k++) {
                LodColumnPlan.Span s = plan.span(k);
                if (s.top == h + 1 - MIN_Y) floor = s;
                if (s.material == Material.ICE) ice = s;
                else if (s.material == ref.fluid) fluid = s;
            }
            assertNotNull(ice, "ice span");
            assertNotNull(fluid, "fluid span under the ice");
            assertNotNull(floor);
            assertEquals(15, ice.skyLight, "the ice top is open to the sky");
            assertEquals(14, fluid.skyLight, "one ice block above the fluid");
            int depth = ref.fluidTop - h; // fluid blocks plus the ice block
            assertEquals(Math.max(0, 15 - depth), floor.skyLight, "floor under " + depth + " blocks of water and ice");
            checked++;
        }
        assertTrue(checked > 0, "the sample must include ice-capped seas");
    }

    @Test
    void everyMaterialHasAVanillaBlockId() {
        for (Material m : Material.values()) {
            String id = LodColumnPlan.blockId(m);
            assertTrue(id.startsWith("minecraft:") && id.length() > 10, m + " -> " + id);
        }
        assertEquals(15, LodColumnPlan.blockLight(Material.LAVA));
        assertEquals(0, LodColumnPlan.blockLight(Material.STONE));
    }

    @Test
    void dimensionNamesMapToSlots() {
        assertEquals(0, DhLevels.surfaceSlot("galactica:surface_0"));
        assertEquals(15, DhLevels.surfaceSlot("galactica:surface_15"));
        assertEquals(-1, DhLevels.surfaceSlot("galactica:surface_16"));
        assertEquals(-1, DhLevels.surfaceSlot("galactica:surface_"));
        assertEquals(-1, DhLevels.surfaceSlot("galactica:surface_-1"));
        assertEquals(-1, DhLevels.surfaceSlot("minecraft:overworld"));
        assertEquals(-1, DhLevels.surfaceSlot(null));
        assertTrue(DhLevels.isSpace("galactica:space"));
    }

    private static Material topSolid(LodColumnPlan plan) {
        Material top = null;
        for (int k = 0; k < plan.count(); k++) {
            Material m = plan.span(k).material;
            if (m != Material.AIR && m != Material.WATER && m != Material.LAVA && m != Material.ICE) top = m;
        }
        return top;
    }

    /** Port of DH 3.3.4 LodDataBuilder.validateOrThrowApiDataColumn plus our contiguity guarantee. */
    private static void validateLikeDh(LodColumnPlan plan) {
        assertTrue(plan.count() >= 2 && plan.count() <= LodColumnPlan.MAX_SPANS);
        int expectBottom = 0;
        for (int k = 0; k < plan.count(); k++) {
            LodColumnPlan.Span s = plan.span(k);
            int height = s.top - s.bottom;
            assertTrue(height > 0 && height < 6095, "span height " + height);
            assertEquals(expectBottom, s.bottom, "spans must tile the column bottom-up");
            assertTrue(s.skyLight >= 0 && s.skyLight <= 15);
            expectBottom = s.top;
        }
        assertEquals(HEIGHT, expectBottom, "column must reach the build limit");
    }
}
