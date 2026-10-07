package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In space the local player's own craft (the ship and everyone aboard) is drawn even before the chunk section it
 * occupies has arrived, compiled and faded in. Vanilla hides entities in such sections; after a floating-origin
 * recentre that hid the pilot's ship for the few ticks the new chunk took, which read as the ship blinking.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorCraftVisibleMixin {
    @Inject(method = "isEntityVisible", at = @At("RETURN"), cancellable = true)
    private void galactica$showOwnCraft(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks,
                                        long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || entity.level().dimension() != Galactica.SPACE) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && entity.getRootVehicle() == player.getRootVehicle()) {
            cir.setReturnValue(true);
        }
    }
}
