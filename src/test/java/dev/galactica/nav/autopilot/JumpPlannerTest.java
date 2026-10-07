package dev.galactica.nav.autopilot;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.science.Units;
import dev.galactica.ship.DriveRules;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JumpPlannerTest {
    private static final double LY_PC = Units.LIGHT_YEAR / Units.PARSEC;

    private static Star star(long id, double x, double y, double z) {
        return new Star(id, "S" + id, x, y, z, StarKind.MAIN_SEQUENCE, 1, 1, 1, 5800, 4, 0, id, false);
    }

    /** A star every {@code stepPc} along +x, slightly off the line. */
    private static List<Star> line(int n, double stepPc) {
        List<Star> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(star(i, i * stepPc, (i % 3 - 1) * 0.2, 0));
        return out;
    }

    private static JumpPlanner.StarSource source(List<Star> stars) {
        return (x, y, z, r) -> stars.stream().filter(s -> s.distanceTo(x, y, z) <= r + 1e-9).toList();
    }

    @Test
    void targetWithinRangeIsOneHop() {
        Star t = star(99, 2.5, 0, 0); // 8.15 ly
        JumpPlanner.Plan p = JumpPlanner.plan(0, 0, 0, t, 10, source(List.of(t)));
        assertTrue(p.reachable());
        assertEquals(List.of(t), p.hops());
        assertEquals(9, p.cost());
        assertTrue(p.affordable(9) && !p.affordable(8));
    }

    @Test
    void chainsAlongAStarFieldEachHopWithinRange() {
        List<Star> field = line(20, 2.0); // a star every 6.5 ly
        Star target = field.getLast(); // 40 pc = 130 ly
        JumpPlanner.Plan p = JumpPlanner.plan(0, 0, 0, target, 10, source(field));
        assertTrue(p.reachable());
        assertEquals(target, p.hops().getLast());
        double cx = 0, cy = 0, cz = 0;
        int cost = 0;
        for (Star s : p.hops()) {
            double ly = s.distanceTo(cx, cy, cz) / LY_PC;
            assertTrue(ly <= 10 + 1e-9, "hop of " + ly + " ly");
            cost += DriveRules.exoticCost(ly);
            cx = s.x();
            cy = s.y();
            cz = s.z();
        }
        assertEquals(cost, p.cost());
        // Greedy takes the farthest star in range each time: 130 ly in hops of ~6.5 ly → 20 hops, never more.
        assertTrue(p.hops().size() <= 20, "hops " + p.hops().size());
    }

    @Test
    void aGapWiderThanTheRangeIsUnreachable() {
        List<Star> field = new ArrayList<>(line(3, 2.0));
        Star far = star(50, 20, 0, 0); // 14 pc beyond the last star of the chain
        field.add(far);
        JumpPlanner.Plan p = JumpPlanner.plan(0, 0, 0, far, 10, source(field));
        assertFalse(p.reachable());
        assertTrue(p.hops().isEmpty());
        assertFalse(p.affordable(64));
    }

    @Test
    void lessMatterMeansShorterHops() {
        List<Star> field = line(10, 1.0); // every 3.26 ly
        Star target = field.getLast();
        JumpPlanner.Plan wide = JumpPlanner.plan(0, 0, 0, target, 10, source(field));
        JumpPlanner.Plan narrow = JumpPlanner.plan(0, 0, 0, target, DriveRules.rangeLy(4), source(field));
        assertTrue(narrow.reachable() && wide.reachable());
        assertTrue(narrow.hops().size() > wide.hops().size());
        assertFalse(JumpPlanner.plan(0, 0, 0, target, 0, source(field)).reachable(), "no matter, no range");
    }

    @Test
    void solToEpsilonEridaniInTheRealGalaxyTakesTwoJumps() {
        Galaxy g = new Galaxy(Frames.GALAXY_SEED);
        Star sol = StarCatalog.SOL, eps = StarCatalog.byName("Epsilon Eridani");
        double ly = eps.distanceTo(sol.x(), sol.y(), sol.z()) / LY_PC;
        assertTrue(ly > 10 && ly < 11, "10.5 ly away: just beyond one jump");
        JumpPlanner.Plan p = JumpPlanner.plan(sol.x(), sol.y(), sol.z(), eps, 10, (x, y, z, r) -> g.starsNear(x, y, z, r, 20_000));
        assertTrue(p.reachable(), "a chain exists through the local stars");
        assertEquals(eps, p.hops().getLast());
        assertTrue(p.hops().size() >= 2 && p.hops().size() <= 3, "hops " + p.hops());
        assertTrue(p.cost() <= 20, "cost " + p.cost());
    }
}
