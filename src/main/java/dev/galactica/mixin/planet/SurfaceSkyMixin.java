package dev.galactica.mixin.planet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.galactica.client.planet.SurfaceSkyOverlay;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On a planet surface vanilla still paints the sky dome, sunrise glow and its random stars, but the
 * square sun and the Overworld's Moon are replaced: {@link SurfaceSkyOverlay} draws the system's real
 * star at the sun's position and the real moons and planets, after vanilla's pass has closed.
 */
@Mixin(SkyRenderer.class)
public abstract class SurfaceSkyMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void galactica$surfaceSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
        if (SurfaceSkyOverlay.active()) SurfaceSkyOverlay.render(state);
    }

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void galactica$hideSun(RenderPass pass, float rainBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (SurfaceSkyOverlay.active()) ci.cancel();
    }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
    private void galactica$hideMoon(RenderPass pass, MoonPhase phase, float rainBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (SurfaceSkyOverlay.active()) ci.cancel();
    }
}
