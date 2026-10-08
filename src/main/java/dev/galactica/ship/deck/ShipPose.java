package dev.galactica.ship.deck;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Where a ship is: its pivot (centre of mass) in the world, its attitude, and where that pivot sits in the ship grid
 * ({@code com}, cell coordinates). Converts between world positions and ship-local cell coordinates (cell x spans
 * x..x+1, body orientation +Z nose, +Y dorsal), and carries a point riding the ship from one pose to the next.
 * Immutable; pure Java.
 */
public record ShipPose(Vector3dc pivot, Quaterniondc attitude, Vector3dc com) {
    public ShipPose {
        pivot = new Vector3d(pivot);
        attitude = new Quaterniond(attitude).normalize();
        com = new Vector3d(com);
    }

    /** A world point in cell coordinates. */
    public Vector3d toLocal(Vector3dc world) {
        Vector3d d = new Vector3d(world).sub(pivot);
        attitude.transformInverse(d);
        return d.add(com);
    }

    /** A cell-coordinate point in the world. */
    public Vector3d toWorld(Vector3dc local) {
        Vector3d d = new Vector3d(local).sub(com);
        attitude.transform(d);
        return d.add(pivot);
    }

    /** A world direction in the ship's axes. */
    public Vector3d dirToLocal(Vector3dc world) {
        return attitude.transformInverse(new Vector3d(world));
    }

    /** A ship-axes direction in the world. */
    public Vector3d dirToWorld(Vector3dc local) {
        return attitude.transform(new Vector3d(local));
    }

    /** Where a world point riding the ship at this pose is once the ship has moved to {@code next}. */
    public Vector3d carry(Vector3dc world, ShipPose next) {
        return next.toWorld(toLocal(world));
    }

    /** The pose a fraction {@code t} of the way to {@code next} (pivot lerped, attitude slerped). */
    public ShipPose lerp(ShipPose next, double t) {
        return new ShipPose(new Vector3d(pivot).lerp(next.pivot, t), new Quaterniond(attitude).slerp(next.attitude, t), com);
    }

    /** Minecraft yaw of the ship's nose, radians: atan2(−x, z) of +Z in the world. */
    static double heading(Quaterniondc q) {
        Vector3d f = q.transform(new Vector3d(0, 0, 1));
        return Math.atan2(-f.x, f.z);
    }

    /** Wrapped heading change in degrees, in (−180, 180]. */
    public double headingDelta(ShipPose next) {
        double d = Math.toDegrees(heading(next.attitude) - heading(attitude));
        d = d % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    /** The largest distance any point within {@code reach} of the pivot travels from here to {@code next}, blocks. */
    public double maxTravel(ShipPose next, double reach) {
        Quaterniond rel = new Quaterniond(attitude).conjugate().mul(next.attitude);
        double w = Math.min(1, Math.abs(rel.w));
        double angle = 2 * Math.acos(w);
        return pivot.distance(next.pivot) + angle * reach;
    }
}
