package dev.galactica.mixin.client.ship;

import dev.galactica.client.ship.ShipEnvironment;
import dev.galactica.client.ship.ShipRiderState;
import dev.galactica.ship.Starship;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A ship's passengers, at render-state extraction:
 * <ul>
 *   <li>lit in space as the ship's own lightmap route is lit ({@link ShipEnvironment#lightCoords}), instead of the
 *       zero lightmap there. Covers the pilot's body in third person and the first-person arm, which
 *       {@code FirstPersonHandsAndItemsRenderer} draws with the player's extracted {@code lightCoords};</li>
 *   <li>given the hull's attitude and their seat height ({@link ShipRiderState}), so {@code LivingRendererShipMixin}
 *       turns them with the hull, and their look in the hull's frame, which their head follows.</li>
 * </ul>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityLightShipMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void galactica$passengerLight(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        ShipRiderState rider = (ShipRiderState) state;
        if (!(entity.getVehicle() instanceof Starship ship)) {
            rider.galactica$setRidesShip(false);
            return;
        }
        rider.galactica$setRidesShip(true);
        ship.getAttitude(partialTicks, rider.galactica$shipAttitude());
        rider.galactica$setSeatHeight((float) entity.getVehicleAttachmentPoint(ship).y);
        // The rider's own look in the hull's frame (the body is turned to face the nose, so the head carries the rest).
        float[] head = rider.galactica$headTurn();
        if (ship.getControllingPassenger() == entity) {
            head[0] = head[1] = 0F;
        } else {
            Vec3 look = entity.getViewVector(partialTicks);
            Vector3f d = rider.galactica$shipAttitude().transformInverse(new Vector3f((float) look.x, (float) look.y, (float) look.z));
            head[0] = Mth.clamp((float) Math.toDegrees(Math.atan2(-d.x, d.z)), -85F, 85F);
            head[1] = (float) Math.toDegrees(-Math.asin(Mth.clamp(d.y, -1F, 1F)));
        }
        int light = ShipEnvironment.passengerLight(ship, partialTicks);
        if (light >= 0) state.lightCoords = Math.max(state.lightCoords & 0xFFFF, light & 0xFFFF) | Math.max(state.lightCoords >>> 16, light >>> 16) << 16;
    }
}
