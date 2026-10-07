package dev.galactica.mixin.client.ship;

import dev.galactica.client.ship.ShipEnvironment;
import dev.galactica.client.ship.ShipRiderState;
import dev.galactica.ship.Starship;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
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
 *       turns them with the hull.</li>
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
        int light = ShipEnvironment.passengerLight(ship, partialTicks);
        if (light >= 0) state.lightCoords = Math.max(state.lightCoords & 0xFFFF, light & 0xFFFF) | Math.max(state.lightCoords >>> 16, light >>> 16) << 16;
    }
}
