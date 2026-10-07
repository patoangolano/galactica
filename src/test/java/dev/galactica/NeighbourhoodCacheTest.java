package dev.galactica;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.star.Star;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The nearest-star and body-position caches answer exactly what a fresh scan does. */
class NeighbourhoodCacheTest {
    private static Star brute(Neighbourhood n, GalacticPos p) {
        Star best = null;
        double bestD = Double.MAX_VALUE;
        for (Star s : n.nearbyStars(p)) {
            if (s.kind() == dev.galactica.star.StarKind.BLACK_HOLE && s.mass() > 1e5) continue;
            double d = s.distanceTo(p.xPc(), p.yPc(), p.zPc());
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    @Test
    void nearestStarMatchesAFreshScanAlongWalks() {
        Galaxy g = new Galaxy(Frames.GALAXY_SEED);
        Neighbourhood cached = new Neighbourhood(g);
        Random r = new Random(7);
        // Near Sol (sparse disk) and in the bulge (dense), small and large steps, crossing sectors and stars.
        double[][] starts = {{Frames.SOL_POS.xPc(), Frames.SOL_POS.yPc(), Frames.SOL_POS.zPc()}, {300, 200, 40}};
        for (double[] s : starts) {
            double x = s[0], y = s[1], z = s[2];
            for (int i = 0; i < 400; i++) {
                double step = i % 3 == 0 ? 0.5 : 0.01;
                x += (r.nextDouble() - 0.5) * step;
                y += (r.nextDouble() - 0.5) * step;
                z += (r.nextDouble() - 0.5) * step;
                GalacticPos p = GalacticPos.ofParsecs(x, y, z);
                Star got = cached.nearestStar(p);
                assertEquals(brute(new Neighbourhood(g), p).id(), got.id(), "walk step " + i + " at " + x + "," + y + "," + z);
                assertSame(got, cached.nearestStar(p));
            }
        }
    }

    @Test
    void locateBodiesIsStableForTheSameQuery() {
        Neighbourhood n = new Neighbourhood(new Galaxy(Frames.GALAXY_SEED));
        var a = n.locateBodies(Frames.SOL_POS, 1000);
        assertSame(a, n.locateBodies(Frames.SOL_POS, 1000));
        var b = n.locateBodies(Frames.SOL_POS, 2000);
        assertEquals(a.size(), b.size());
        var fresh = new Neighbourhood(new Galaxy(Frames.GALAXY_SEED)).locateBodies(Frames.SOL_POS, 2000);
        for (int i = 0; i < b.size(); i++) assertEquals(fresh.get(i).pos(), b.get(i).pos());
    }
}
