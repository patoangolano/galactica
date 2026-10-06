package dev.galactica.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.galactica.client.sky.SpaceSkyRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In galactica:space the vanilla sky (disc, sun, moon, 1 500 random stars) is replaced wholesale.
 * Iris only hooks SkyRenderer's private sub-methods and the LevelRenderer sky-pass lambda around
 * this call, so cancelling here keeps its sky phase bracketing intact.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void galactica$renderSpace(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
        if (!SpaceSkyRenderer.active()) return;
        RenderSystem.setShaderFog(skyFog);
        SpaceSkyRenderer.render();
        ci.cancel();
    }
}
