package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
    /**
     * The craft vanilla will tick this pass, captured before the loop. Checking only after it is wrong: a craft that
     * crosses into a not-yet-received chunk during its own tick leaves the ticking list mid-loop, and was then ticked
     * a second time here (one extra 14-block step, seen as a camera jump right after every recentre).
     */
    @Unique private @Nullable Entity galactica$tickedByVanilla;

    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void galactica$noteOwnCraft(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        LocalPlayer player = Minecraft.getInstance().player;
        Entity root = player == null ? null : player.getRootVehicle();
        galactica$tickedByVanilla = root != null && self.isTickingEntity(root) ? root : null;
    }

    @Inject(method = "tickEntities", at = @At("TAIL"))
    private void galactica$tickOwnCraft(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        Entity ticked = galactica$tickedByVanilla;
        galactica$tickedByVanilla = null;
        if (self.dimension() != Galactica.SPACE) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.level() != self) return;
        Entity root = player.getRootVehicle();
        if (root == ticked || root.isRemoved() || self.isTickingEntity(root) || self.tickRateManager().isEntityFrozen(root)) return;
        self.guardEntityTick(self::tickNonPassenger, root);
    }
}
