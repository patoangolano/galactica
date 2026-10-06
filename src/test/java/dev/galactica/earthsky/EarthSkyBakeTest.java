package dev.galactica.earthsky;

import dev.galactica.client.earthsky.BrightStars;
import dev.galactica.client.earthsky.EarthSkyBake;
import dev.galactica.client.earthsky.EquatorialFrame;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.Frames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EarthSkyBakeTest {

    @Test
    void brightStarCatalogueIsSane() {
        List<BrightStars.Entry> all = BrightStars.all();
        long toLimit = all.stream().filter(e -> e.vmag() <= BrightStars.COMPLETE_TO_MAG).count();
        // ~90 stars of V ≤ 2.5 in the whole sky (Yale BSC).
        assertTrue(toLimit >= 85 && toLimit <= 100, "count " + toLimit);
        assertEquals(-16.7161, BrightStars.byName("Sirius").decDeg(), 1e-3);
        assertEquals(-0.2992, BrightStars.byName("Mintaka").decDeg(), 1e-3); // "-00" keeps its sign
        assertEquals(101.2871, BrightStars.byName("Sirius").raDeg(), 1e-3);
        // Ballesteros: the Sun (B−V = 0.65) ≈ 5 800 K; Betelgeuse red, Rigel blue-white.
        assertEquals(5800, new BrightStars.Entry("Sun", 0, 0, -26.7, 0.65).temperature(), 150);
        assertTrue(BrightStars.byName("Betelgeuse").temperature() < 3900);
        assertTrue(BrightStars.byName("Rigel").temperature() > 9500);
    }

    @Test
    void milkyWayIsBrightestTowardsSagittariusAndDarkAtThePoles() {
        int w = 128, h = 64;
        int[] px = EarthSkyBake.galaxy(w, h);
        double[] gc = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(266.405, -28.936));
        double[] ngp = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(192.859, 27.128));
        double[] anti = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(86.405, 28.936)); // anticentre
        int aGc = bandSum(px, w, h, gc), aNgp = bandSum(px, w, h, ngp), aAnti = bandSum(px, w, h, anti);
        assertTrue(aGc > aAnti, "GC " + aGc + " vs anticentre " + aAnti);
        assertTrue(aAnti > aNgp, "anticentre " + aAnti + " vs NGP " + aNgp);
    }

    /** Alpha (= light) summed over a 5 × 3 pixel patch, so one dust lane cannot flip the comparison. */
    private static int bandSum(int[] px, int w, int h, double[] dir) {
        int[] p = EarthSkyBake.pixelOf(dir, w, h);
        int s = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = Math.floorMod(p[0] + dx, w), y = Math.max(0, Math.min(h - 1, p[1] + dy));
                s += px[y * w + x] >>> 24;
            }
        }
        return s;
    }

    @Test
    void pixelOfInvertsEquirectDirection() {
        double[] d = new double[3];
        dev.galactica.sky.GalaxySkyBaker.equirectDirection((37 + 0.5) / 128.0, (20 + 0.5) / 64.0, d);
        int[] p = EarthSkyBake.pixelOf(d, 128, 64);
        assertArrayEquals(new int[] {37, 20}, p);
    }

    @Test
    void starListIsTheRealBrightSkyPlusFaintProceduralStars() {
        List<EarthSkyBake.Sprite> stars = EarthSkyBake.stars(new Galaxy(Frames.GALAXY_SEED));
        // The real sky holds ~9 000 stars to V = 6.5 (Yale BSC); the model gives about that.
        assertTrue(stars.size() > 5_000 && stars.size() < 16_000, "n = " + stars.size());
        assertEquals("Sirius", stars.get(0).name());
        for (EarthSkyBake.Sprite s : stars) {
            assertTrue(s.mag() <= EarthSkyBake.LIMITING_MAG);
            if (s.mag() < BrightStars.COMPLETE_TO_MAG) assertNotNull(BrightStars.byName(s.name()), s.name() + " is not a real star");
            assertEquals(1.0, Math.sqrt(s.bx() * s.bx() + s.by() * s.by() + s.bz() * s.bz()), 1e-4);
        }
    }
}
