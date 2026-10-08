package dev.galactica.mixin.client.dev;

import dev.galactica.client.dev.AutotestInput;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev autotest: {@code setAll} re-reads the physical keys on focus and screen changes; not in a gated run. */
@Mixin(KeyMapping.class)
public abstract class KeyMappingGateMixin {
    @Inject(method = "setAll()V", at = @At("HEAD"), cancellable = true)
    private static void galactica$gateSetAll(CallbackInfo ci) {
        if (AutotestInput.GATED) ci.cancel();
    }
}
