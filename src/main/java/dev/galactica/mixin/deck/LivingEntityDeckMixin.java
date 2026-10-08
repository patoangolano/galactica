package dev.galactica.mixin.deck;

import dev.galactica.ship.deck.Deck;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A deck walker moves in its ship's frame ({@link Deck}): vanilla's own travel (world-frame input, gravity and
 * collision, then thrown away by the rider placement) is skipped, and the walk animation follows the walk on the deck
 * rather than the ship's speed (vanilla would stop it for every passenger).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeckMixin {
    @Shadow protected abstract void updateWalkAnimation(float distance);

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void galactica$deckTravel(Vec3 input, CallbackInfo ci) {
        if (Deck.isWalker((LivingEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "calculateEntityAnimation", at = @At("HEAD"), cancellable = true)
    private void galactica$deckAnimation(boolean useY, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!Deck.isWalker(self)) return;
        updateWalkAnimation(Deck.walkedDistance(self));
        ci.cancel();
    }
}
