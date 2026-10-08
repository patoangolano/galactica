package dev.galactica.mixin.client.nav;

import dev.galactica.client.nav.AutopilotClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeyboardInput.tick() rebuilds the input record from the keys every tick. While the autopilot flies, its record
 * replaces the keys' here, so the very same record feeds the ship's prediction and travels to the server in
 * {@code ServerboundPlayerInputPacket} (main engine, warp, RCS). A key the player presses hands control back
 * ({@link AutopilotClient#filterInput}).
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputAutopilotMixin {
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void galactica$autopilot(CallbackInfo ci) {
        ClientInput self = (ClientInput) (Object) this;
        Input flown = AutopilotClient.filterInput(self.keyPresses);
        if (flown != null) self.keyPresses = flown;
    }
}
