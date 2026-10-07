package dev.galactica.mixin.perf;

import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.galactica.perf.Perf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Benchmark only: counts draw calls issued through the renderpearl frontend (vanilla, ours, other mods). */
@Mixin(FrontendRenderPass.class)
public abstract class RenderPassCountMixin {
    @Inject(method = {"drawIndexed", "drawIndexedIndirect", "draw", "drawIndirect"}, at = @At("HEAD"), require = 0)
    private void galactica$countDraw(CallbackInfo ci) {
        if (Perf.on) Perf.draws++;
    }

    @Inject(method = "drawMultipleIndexed", at = @At("HEAD"), require = 0)
    private void galactica$countMulti(java.util.Collection<?> draws, com.mojang.renderpearl.api.buffers.GpuBuffer index,
                                      com.mojang.renderpearl.api.pipeline.IndexType type, java.util.Collection<String> uniforms,
                                      Object userData, CallbackInfo ci) {
        if (Perf.on) Perf.draws += draws.size();
    }
}
