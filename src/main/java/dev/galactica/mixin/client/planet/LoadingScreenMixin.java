package dev.galactica.mixin.client.planet;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.galactica.client.planet.TransferClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Seamless transfer: skips the vanilla level-loading screen for a respawn that a Galactica transfer armed
 * ({@link TransferClient#consumeArmed()}, one shot). Every other respawn (death, portals, other mods) shows it as
 * usual. Only the screen is skipped: {@code ClientPacketListener.startWaitingForNewLevel} still starts the
 * {@code LevelLoadTracker}, which {@code ClientPacketListener.tick} advances and closes with
 * {@code notifyPlayerLoaded()}. At the switch rows the camera is above build height, where
 * {@code WaitingForPlayerChunk.isReady()} returns true at once (verified on 26.3).
 *
 * <p>Same technique as kennytv's MIT "Force Close Loading Screen" mod, reimplemented and scoped to transfers.
 */
@Mixin(ClientPacketListener.class)
public abstract class LoadingScreenMixin {

    @WrapOperation(method = "startWaitingForNewLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void galactica$skipTransferLoadingScreen(Minecraft minecraft, Screen screen, Operation<Void> original) {
        if (TransferClient.consumeArmed()) return;
        original.call(minecraft, screen);
    }
}
