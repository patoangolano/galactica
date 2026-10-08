package dev.galactica.mixin.deck;

import dev.galactica.ship.deck.ShipVoxelCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Built ships' hulls are solid ({@link ShipVoxelCollision}): the move vanilla collided with the terrain and entity
 * boxes is collided once more with the block shapes of every built ship nearby, in each ship's own frame.
 */
@Mixin(Entity.class)
public abstract class EntityCollideShipMixin {
    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void galactica$shipHulls(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (!ShipVoxelCollision.applies(self)) return;
        Vec3 m = cir.getReturnValue();
        if (m.lengthSqr() == 0) return;
        Vec3 clipped = ShipVoxelCollision.clip(self, m);
        if (clipped != m) cir.setReturnValue(clipped);
    }
}
