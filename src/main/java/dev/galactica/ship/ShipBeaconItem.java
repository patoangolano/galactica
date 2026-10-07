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
        // Keep the box off the player: at least half the box plus an arm's length away horizontally.
        Vec3 flat = new Vec3(at.x - player.getX(), 0, at.z - player.getZ());
        double clear = ShipDimensions.BOX_WIDTH / 2.0 + 1.0;
        if (flat.length() < clear) {
            Vec3 dir = flat.lengthSqr() > 1e-4 ? flat.normalize() : Vec3.directionFromRotation(0F, player.getYRot());
            at = new Vec3(player.getX() + dir.x * clear, at.y, player.getZ() + dir.z * clear);
        }

        Starship ship = ShipEntities.SHIP.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (ship == null) return InteractionResult.FAIL;
        ship.snapTo(at.x, at.y, at.z, player.getYRot(), 0F);
        if (!level.noCollision(ship, ship.getBoundingBox())) return InteractionResult.FAIL;
        if (level instanceof ServerLevel sl) {
            ship.setAttitude(new Quaternionf().rotationY((float) Math.toRadians(-player.getYRot())));
            ship.setHydrogen(Starship.DEPLOY_HYDROGEN);
            EntityType.createDefaultStackConfig(sl, stack, player).apply(ship);
            sl.addFreshEntity(ship);
            sl.gameEvent(player, GameEvent.ENTITY_PLACE, at);
            stack.consume(1, player);
        }
        return InteractionResult.SUCCESS;
    }
}
