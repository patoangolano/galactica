package dev.galactica.ship.deck;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * A moving hull against a body that does not ride it (a player standing beside a ship that turns or slides into them).
 * The hull's move over one tick is cut into sub-steps short enough that no hull point travels more than
 * {@link #MAX_STEP} blocks, so a wall 30 blocks from the pivot turning at 2.5 rad/s, or a ship crossing 14 blocks a
 * tick, never jumps over the body; at the first sub-step where the body's box overlaps the hull it is pushed out
 * (shortest way, preferring up) and from then on rides the hull to the end of the tick. Pure Java.
 */
public final class HullSweep {
    private HullSweep() {}

    /** Largest hull-point travel between two tests, blocks. */
    public static final double MAX_STEP = 0.4;
    /** Most sub-steps one tick is cut into. */
    public static final int MAX_SUBSTEPS = 64;

    /**
     * @param hit      whether the hull reached the body this tick
     * @param fraction when (0..1 of the tick) it did
     * @param local    where the body ends, ship cell coordinates (pushed clear of the hull)
     * @param world    where the body ends in the world
     */
    public record Result(boolean hit, double fraction, Vector3d local, Vector3d world) {}

    /** Sub-steps for a hull of {@code reach} blocks moving between two poses. */
    public static int substeps(ShipPose from, ShipPose to, double reach) {
        double travel = from.maxTravel(to, reach);
        return (int) Math.clamp(Math.ceil(travel / MAX_STEP), 1, MAX_SUBSTEPS);
    }

    /**
     * Sweeps the hull from {@code from} to {@code to} past a body standing at {@code feetWorld} (bottom centre of its
     * box). {@code reach} is the hull's farthest point from the pivot.
     */
    public static Result sweep(Vector3dc feetWorld, DeckWalker.Size size, ShipPose from, ShipPose to, double reach, DeckWalker.Blocks blocks) {
        int n = substeps(from, to, reach);
        List<Aabb> boxes = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double t = (double) k / n;
            ShipPose pose = k == n ? to : from.lerp(to, t);
            Vector3d local = pose.toLocal(feetWorld);
            Aabb box = Aabb.feet(local.x, local.y, local.z, size.width(), size.height());
            boxes.clear();
            blocks.collect(box.inflate(1e-3), boxes);
            if (!BoxSweep.overlaps(box, boxes, 1e-4)) continue;
            // Push clear with the hull at that instant: a larger region, since the push can carry the box a few blocks.
            boxes.clear();
            blocks.collect(box.inflate(3), boxes);
            double[] push = BoxSweep.depenetrate(box, boxes, size.height() * 0.6, 8);
            if (push != null) local.add(push[0], push[1], push[2]);
            return new Result(true, t, local, to.toWorld(local));
        }
        return new Result(false, 1, to.toLocal(feetWorld), new Vector3d(feetWorld));
    }
}
