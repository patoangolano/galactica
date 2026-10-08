package dev.galactica.mixin.client.dev;

import dev.galactica.client.dev.AutotestInput;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev autotest: drops OS mouse buttons, wheel and movement ({@link AutotestInput}). */
@Mixin(MouseHandler.class)
public abstract class MouseInputGateMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void galactica$gateButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (AutotestInput.drop()) ci.cancel();
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void galactica$gateScroll(long window, double x, double y, CallbackInfo ci) {
        if (AutotestInput.drop()) ci.cancel();
    }

    @Inject(method = "onMove(JDDDD)V", at = @At("HEAD"), cancellable = true)
    private void galactica$gateMove(long window, double a, double b, double c, double d, CallbackInfo ci) {
        if (AutotestInput.drop()) ci.cancel();
    }
}
