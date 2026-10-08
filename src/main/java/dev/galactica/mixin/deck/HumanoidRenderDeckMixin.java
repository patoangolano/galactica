package dev.galactica.mixin.deck;

import dev.galactica.ship.deck.Deck;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Deck walkers ride their ship as passengers but stand and walk: never the sitting pose. */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidRenderDeckMixin {
    @Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
    private static void galactica$standOnDeck(LivingEntity entity, HumanoidRenderState state, float partialTicks, ItemModelResolver resolver,
                                              CallbackInfo ci) {
        if (state.isPassenger && Deck.isWalker(entity)) state.isPassenger = false;
    }
}
