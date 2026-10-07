package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Space has no void: nothing is hurt or discarded for being below galactica:space's build range. The floating origin
 * keeps crafts within ±{@code FloatingOrigin.LIMIT_Y} of block 0 vertically, but a craft saved lower in an older
 * world (the dimension used to be 4 064 blocks tall) must survive until its first recentre.
 */
@Mixin(Entity.class)
public abstract class SpaceBelowWorldMixin {
    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void galactica$noVoidInSpace(CallbackInfo ci) {
        if (((Entity) (Object) this).level().dimension() == Galactica.SPACE) ci.cancel();
    }
}
