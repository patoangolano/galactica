package dev.galactica.mixin.perf;

import dev.galactica.client.dev.PerfBench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Benchmark only: one mark per client frame (the head of {@code runTick}), for frame-time percentiles. */
@Mixin(Minecraft.class)
public abstract class FrameTimeMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void galactica$frame(boolean advanceGameTime, CallbackInfo ci) {
        PerfBench.frame();
    }
}
