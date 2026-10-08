package dev.galactica.ship.yard;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/**
 * The quarter turns between the ship grid (body orientation: the helm faces +Z, south) and the world, for a helm
 * facing a given way. Kept apart from {@link ShipAssembler} so it loads without the mod's registries.
 */
public final class ShipFrames {
    private ShipFrames() {}

    /** Body → world turn for a helm facing {@code facing}. */
    public static Rotation toWorld(Direction facing) {
        return switch (facing) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Rotation inverse(Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> r;
        };
    }

    /** Turns a horizontal offset (x, z) by {@code r} (clockwise seen from above: south → west → north → east). */
    public static double[] turn(Rotation r, double x, double z) {
        return switch (r) {
            case CLOCKWISE_90 -> new double[] {-z, x};
            case CLOCKWISE_180 -> new double[] {-x, -z};
            case COUNTERCLOCKWISE_90 -> new double[] {z, -x};
            default -> new double[] {x, z};
        };
    }
}
