package dev.galactica.mixin.client.earthsky;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.galactica.client.earthsky.EarthSky;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overworld real night sky ({@link EarthSky}).
 *
 * <p>Priority 1100: Iris 1.11.7 ({@code net.irisshaders.iris.mixin.MixinSkyRenderer}, default
 * priority 1000) injects {@code iris$setStarRenderStage} at HEAD of {@code renderStars}, not
 * cancellable, to switch its phase to STARS. Mixins are applied in ascending priority and each HEAD
 * callback is inserted just before the original first instruction, so a later-applied (higher
 * priority) mixin's callback runs after Iris's: the phase is set before we draw and cancel.
 */
@Mixin(value = SkyRenderer.class, priority = 1100)
public abstract class EarthSkyMixin {
    /** Before vanilla opens the sky pass: uploads must happen while no render pass is open. */
    @Inject(method = "render", at = @At("HEAD"))
    private void galactica$prepareEarthSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
        EarthSky.prepare();
    }

    /** Inside vanilla's pass, with vanilla's star pose: draw the real sky instead of 1 500 random stars. */
    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void galactica$realNightSky(RenderPass renderPass, float starBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (EarthSky.renderStars(renderPass, starBrightness, poseStack)) ci.cancel();
    }
}
