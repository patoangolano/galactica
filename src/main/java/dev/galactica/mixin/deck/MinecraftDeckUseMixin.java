package dev.galactica.mixin.deck;

import dev.galactica.client.ship.DeckClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The use key of a deck walker first tries the ship's own blocks ({@link DeckClient#useFromDeck}): the ship's blocks
 * are entity data, so vanilla's crosshair never finds them.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDeckUseMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void galactica$useShipBlock(CallbackInfo ci) {
        if (DeckClient.useFromDeck((Minecraft) (Object) this)) ci.cancel();
    }
}
