package dev.galactica.nav.autopilot;

import dev.galactica.ship.DriveRules;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.science.Units;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans a chain of hyperjumps toward a star beyond one jump's range. Greedy best-first over real stars: each hop goes
 * to the star within range that leaves the least distance to the target (and must cut it by at least
 * {@link #MIN_PROGRESS} of the range, so the chain cannot stall between two stars). The last hop lands on the target.
 * Pure Java: the stars come from a {@link StarSource} (the galaxy in game, a synthetic field in tests).
 *
 * <p>Only the first hop is ever executed before the planner runs again from the arrival point, so the chain follows
 * the stars actually reached, and the exotic-matter check uses the cost of the whole remaining chain.
 */
public final class JumpPlanner {
    private JumpPlanner() {}

    /** A hop must bring the ship at least this fraction of the jump range closer to the target. */
    public static final double MIN_PROGRESS = 0.05;
    /** Longest chain considered; beyond it the planner gives up (and the autopilot warps instead). */
    public static final int MAX_HOPS = 64;

    @FunctionalInterface
    public interface StarSource {
        /** Every star within {@code radiusPc} of galactocentric (x, y, z) pc (more is fine; they are filtered). */
        List<Star> near(double x, double y, double z, double radiusPc);
    }

    /**
     * @param hops      the stars to jump to, in order, the target last; empty when unreachable
     * @param cost      exotic matter the whole chain costs (each hop ⌈ly⌉, at least 1)
     * @param reachable a chain exists (whether or not the fuel covers it)
     */
    public record Plan(List<Star> hops, int cost, boolean reachable) {
        public boolean affordable(int exotic) {
            return reachable && cost <= exotic;
        }

        public Star next() {
            return hops.isEmpty() ? null : hops.getFirst();
        }
    }

    /** Light years between two galactocentric points in parsecs. */
    static double ly(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) * Units.PARSEC / Units.LIGHT_YEAR;
    }

    /**
     * The chain from (x, y, z) pc to {@code target} with jumps of at most {@code rangeLy}.
     * {@code rangeLy} is the drive's range for the matter aboard ({@link DriveRules#rangeLy}), never more than its tier.
     */
    public static Plan plan(double x, double y, double z, Star target, double rangeLy, StarSource stars) {
        if (!(rangeLy > 0)) return new Plan(List.of(), 0, false);
        double rangePc = rangeLy * Units.LIGHT_YEAR / Units.PARSEC;
        List<Star> hops = new ArrayList<>();
        int cost = 0;
        double cx = x, cy = y, cz = z;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            double left = ly(cx, cy, cz, target.x(), target.y(), target.z());
            if (left <= rangeLy) {
                hops.add(target);
                cost += DriveRules.exoticCost(left);
                return new Plan(List.copyOf(hops), cost, true);
            }
            Star best = null;
            double bestLeft = left - MIN_PROGRESS * rangeLy, bestHop = 0;
            for (Star s : stars.near(cx, cy, cz, rangePc)) {
                if (s.kind() == StarKind.BLACK_HOLE && s.mass() > 1e5) continue; // Sgr A* is its own region
                double hopLy = ly(cx, cy, cz, s.x(), s.y(), s.z());
                if (hopLy > rangeLy || hopLy < 1e-6) continue;
                double rest = ly(s.x(), s.y(), s.z(), target.x(), target.y(), target.z());
                if (rest < bestLeft) {
                    bestLeft = rest;
                    best = s;
                    bestHop = hopLy;
                }
            }
            if (best == null) return new Plan(List.of(), cost, false);
            hops.add(best);
            cost += DriveRules.exoticCost(bestHop);
            cx = best.x();
            cy = best.y();
            cz = best.z();
        }
        return new Plan(List.of(), cost, false);
    }
}
