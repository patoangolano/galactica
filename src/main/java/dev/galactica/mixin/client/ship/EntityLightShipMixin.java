package dev.galactica.mixin.client.ship;

import dev.galactica.client.ship.ShipEnvironment;
import dev.galactica.ship.Starship;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lights a ship's passengers in space as the ship's own lightmap route is lit ({@link ShipEnvironment#lightCoords}),
 * instead of the zero lightmap there. Covers the pilot's body in third person and the first-person arm, which
 * {@code FirstPersonHandsAndItemsRenderer} draws with the player's extracted {@code lightCoords}.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityLightShipMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void galactica$passengerLight(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof Starship ship)) return;
        int light = ShipEnvironment.passengerLight(ship, partialTicks);
        if (light >= 0) state.lightCoords = Math.max(state.lightCoords & 0xFFFF, light & 0xFFFF) | Math.max(state.lightCoords >>> 16, light >>> 16) << 16;
    }
}
