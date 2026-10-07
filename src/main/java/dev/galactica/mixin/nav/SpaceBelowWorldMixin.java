package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import dev.galactica.ship.Starship;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Below galactica:space's build range, players, ships and whatever rides them are neither hurt nor discarded. The
 * floating origin keeps crafts near block 0 vertically, but a craft or player saved lower in an older world (the
 * dimension used to be 4 064 blocks tall) must survive until it is brought back ({@code FloatingOrigin}, and
 * {@code Starship} for a parked ship). Everything else (arrows, items, mobs) keeps the vanilla void discard, so
 * nothing thrown downward in zero-g drifts and is saved forever.
 */
@Mixin(Entity.class)
public abstract class SpaceBelowWorldMixin {
    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void galactica$noVoidInSpace(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().dimension() != Galactica.SPACE) return;
        Entity root = self.getRootVehicle();
        if (self instanceof Player || root instanceof Starship || root instanceof Player) ci.cancel();
    }
}
