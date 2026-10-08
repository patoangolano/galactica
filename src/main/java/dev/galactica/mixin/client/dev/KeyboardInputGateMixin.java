package dev.galactica.mixin.client.dev;

import dev.galactica.client.dev.AutotestInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev autotest: drops OS key events ({@link AutotestInput}). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardInputGateMixin {
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void galactica$gateKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (AutotestInput.drop()) ci.cancel();
    }

    @Inject(method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", at = @At("HEAD"), cancellable = true)
    private void galactica$gateChar(long window, CharacterEvent event, CallbackInfo ci) {
        if (AutotestInput.drop()) ci.cancel();
    }
}
