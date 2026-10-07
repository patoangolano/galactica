package dev.galactica.ship;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Deploys a {@link Starship} where the player looks, at most 8 blocks out (spec §1): on the block hit, or,
 * with nothing in reach (open space), floating 8 blocks ahead. The nose points along the player's heading.
 * Consumed outside creative.
 */
public class ShipBeaconItem extends Item {
    public static final double REACH = 8.0;

    public ShipBeaconItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(REACH));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 at;
        if (hit.getType() == HitResult.Type.BLOCK) {
            at = hit.getLocation();
            Direction face = hit.getDirection();
            // Off a wall or ceiling, back away by half the box so the ship does not spawn inside the block.
            double half = ShipDimensions.BOX_WIDTH / 2.0 + 0.1;
            if (face != Direction.UP) at = at.add(face.getStepX() * half, face == Direction.DOWN ? -ShipDimensions.BOX_HEIGHT - 0.1 : 0, face.getStepZ() * half);
        } else {
            at = end.subtract(0, ShipDimensions.PIVOT, 0);
        }
        // Keep the whole hull off the player, not just the box: the nose points along the player's heading, so the
        // tail (and on a sideways deploy a wing) reaches back toward them. The centre goes at least as far out as the
        // hull reaches toward the player, plus the player's half width and an arm's length.
        Quaternionf attitude = new Quaternionf().rotationY((float) Math.toRadians(-player.getYRot()));
        Vec3 flat = new Vec3(at.x - player.getX(), 0, at.z - player.getZ());
        Vec3 dir = flat.lengthSqr() > 1e-4 ? flat.normalize() : Vec3.directionFromRotation(0F, player.getYRot());
        double clear = Math.max(ShipDimensions.BOX_WIDTH / 2.0, towardPlayer(attitude, dir)) + 0.3 + 1.0;
        if (flat.length() < clear) at = new Vec3(player.getX() + dir.x * clear, at.y, player.getZ() + dir.z * clear);

        Starship ship = ShipEntities.SHIP.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (ship == null) return InteractionResult.FAIL;
        ship.snapTo(at.x, at.y, at.z, player.getYRot(), 0F);
        // The box and the nose, wings and tail beyond it must all be clear of blocks.
        if (!level.noCollision(ship, ship.getBoundingBox()) || !Starship.hullClear(level, at, attitude)) return InteractionResult.FAIL;
        if (level instanceof ServerLevel sl) {
            ship.setAttitude(attitude);
            ship.setHydrogen(Starship.DEPLOY_HYDROGEN);
            EntityType.createDefaultStackConfig(sl, stack, player).apply(ship);
            sl.addFreshEntity(ship);
            sl.gameEvent(player, GameEvent.ENTITY_PLACE, at);
            stack.consume(1, player);
        }
        return InteractionResult.SUCCESS;
    }

    /** How far the hull reaches from its centre toward a player standing along {@code -dir} (horizontal), blocks. */
    private static double towardPlayer(Quaternionf attitude, Vec3 dir) {
        org.joml.Vector3f v = new org.joml.Vector3f();
        double reach = 0;
        for (float[] p : ShipHullProbes.POINTS) {
            attitude.transform(v.set(p[0], p[1], p[2]));
            reach = Math.max(reach, -(v.x * dir.x + v.z * dir.z));
        }
        return reach;
    }
}
