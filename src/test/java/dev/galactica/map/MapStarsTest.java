package dev.galactica.map;

import dev.galactica.client.map.ArmLegend;
import dev.galactica.client.map.FaceOnBake;
import dev.galactica.client.map.MapStars;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.net.JumpTargetRules;
import dev.galactica.sky.SkyTonemap;
import dev.galactica.star.Star;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MapStarsTest {
    private static final Galaxy GALAXY = new Galaxy(Frames.GALAXY_SEED);
    private static final Star SOL = StarCatalog.SOL;

    @Test
    void nearestStarsViewListsEveryStarWithAValidAddress() {
        List<MapStars.MapStar> list = MapStars.list(GALAXY, SOL.x(), SOL.y(), SOL.z(), 30);
        // ~0.1 stars/pc³ in a 37.5 × 37.5 × 30 pc box, minus the catalog's share near the Sun.
        assertTrue(list.size() > 1_500 && list.size() <= MapStars.MAX_STARS, "got " + list.size());
        Set<Long> ids = new HashSet<>();
        for (MapStars.MapStar m : list) {
            Star s = m.star();
            assertTrue(Math.abs(s.x() - SOL.x()) <= 30 * 0.5 * MapStars.MARGIN);
            assertTrue(Math.abs(s.z() - SOL.z()) <= 15);
            assertTrue(ids.add(s.id()), "duplicate star");
            // Every listed address is one the server accepts back.
            assertEquals(s.id(), JumpTargetRules.resolve(GALAXY, s.id(), m.sector(), m.index()).id());
        }
    }

    @Test
    void localBubbleViewListsOnlyTheLuminousStreamCappedByBrightness() {
        List<MapStars.MapStar> list = MapStars.list(GALAXY, SOL.x(), SOL.y(), SOL.z(), 300);
        assertEquals(MapStars.MAX_STARS, list.size(), "tens of thousands of luminous stars, capped");
        double faintest = list.stream().mapToDouble(m -> m.star().absoluteMagnitude()).max().orElseThrow();
        assertTrue(faintest < 2.0, "only bright stars survive the cap, faintest M = " + faintest);
        for (int i = 0; i < list.size(); i += 97) {
            MapStars.MapStar m = list.get(i);
            assertEquals(m.star(), JumpTargetRules.resolve(GALAXY, m.star().id(), m.sector(), m.index()));
        }
        assertTrue(MapStars.list(GALAXY, SOL.x(), SOL.y(), SOL.z(), 3_000).isEmpty(), "wide views list no procedural stars");
    }

    @Test
    void armLabelsSitOnTheirOwnArms() {
        double[][] a = ArmLegend.anchors();
        assertEquals(4, a.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(i, GalaxyModel.nearestArm(a[i][0], a[i][1]));
            assertTrue(GalaxyModel.armStrength(a[i][0], a[i][1]) > 0.9, "label " + i + " on the ridge");
        }
    }

    @Test
    void faceOnBakeShowsBulgeArmsAndDarkSky() {
        int n = 96;
        float[] hdr = FaceOnBake.bakeHdr(Frames.GALAXY_SEED, n);
        int[] argb = FaceOnBake.tonemap(hdr);
        assertEquals(n * n, argb.length);
        int centre = (n / 2) * n + n / 2;
        int corner = 0;
        // The Sun's column: row ↔ x = R0, column ↔ y = 0.
        int sunRow = (int) ((GalaxyModel.R0 + 15_000) / 30_000 * n);
        int sun = sunRow * n + n / 2;
        assertTrue(lum(hdr, centre) > 20 * lum(hdr, sun), "the bulge outshines the solar circle");
        assertEquals(0xFF000000, argb[corner], "intergalactic corners are black");
        assertNotEquals(0xFF000000, argb[sun], "the solar neighbourhood is still on the map");
        assertTrue((argb[centre] & 0xFF) > 150, "the bulge is bright");
    }

    private static double lum(float[] hdr, int i) {
        return SkyTonemap.luminance(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]);
    }
}
