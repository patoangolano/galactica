package dev.galactica.mixin.client.ship;

import dev.galactica.ship.Starship;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MouseHandler.turnPlayer → LocalPlayer.turn(DD) (inherited from Entity). While piloting, mouse deltas steer
 * the ship (yaw, pitch in the body frame) instead of the head; the pilot's view follows the nose.
 */
@Mixin(Entity.class)
public abstract class EntityTurnShipMixin {
    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void galactica$steerShip(double xo, double yo, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer p && p.getVehicle() instanceof Starship ship && ship.getControllingPassenger() == p) {
            ship.addMouseInput((float) xo * 0.15F, (float) yo * 0.15F); // Entity.turn's own degrees-per-count
            ci.cancel();
        }
    }
}
