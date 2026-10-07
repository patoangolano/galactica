package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In space the local player's craft keeps ticking while its chunk has not arrived yet. The client only ticks
 * entities in loaded chunks; right after a floating-origin recentre (or the take-off into the space frame) the
 * craft sits in a chunk the server is still sending, and the ship, its pilot and the camera froze for those ticks.
 * Space has no blocks to collide with, so flying on through an unloaded chunk is exactly right.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelCraftTickMixin {
    @Inject(method = "tickEntities", at = @At("TAIL"))
    private void galactica$tickOwnCraft(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (self.dimension() != Galactica.SPACE) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.level() != self) return;
        Entity root = player.getRootVehicle();
        if (root.isRemoved() || self.isTickingEntity(root) || self.tickRateManager().isEntityFrozen(root)) return;
        self.guardEntityTick(self::tickNonPassenger, root);
    }
}
