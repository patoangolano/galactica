package dev.galactica.mixin.planet;

import dev.galactica.planet.PlanetGravity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Planet gravity for every non-player entity: {@code Entity.getGravity()} is the single final
 * funnel over each type's {@code getDefaultGravity()} (items 0.04, arrows 0.05, mobs' GRAVITY
 * attribute, boats, ships), so scaling its result once covers them all. Players are left to the
 * GRAVITY attribute modifier PlanetEntry gives them, so they are not scaled twice.
 */
@Mixin(Entity.class)
public abstract class EntityGravityMixin {
    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void galactica$planetGravity(CallbackInfoReturnable<Double> cir) {
        double gravity = cir.getReturnValueD();
        Entity self = (Entity) (Object) this;
        if (gravity == 0.0 || self instanceof Player) return;
        double scale = PlanetGravity.scale(self.level());
        if (scale != 1.0) cir.setReturnValue(gravity * scale);
    }
}
