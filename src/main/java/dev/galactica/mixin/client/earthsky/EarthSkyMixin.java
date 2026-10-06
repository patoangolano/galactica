package dev.galactica.mixin.client.earthsky;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.galactica.client.earthsky.EarthSky;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
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
 *
 * <p>The Milky Way is drawn in {@code renderSunMoonAndStars} just before its second
 * {@code PoseStack.pushPose()} (ordinal 1): after {@code rotateDegrees(Axis.YP, −90)} and after Iris'
 * sun-path tilt (injected AFTER that rotateDegrees, ordinal 0), but before the sun and moon are
 * drawn, so the band's translucent blend never covers them. Iris' tilt callback sits right after
 * the rotate call and ours right before the push, so the order holds whichever mixin applies first.
 */
@Mixin(value = SkyRenderer.class, priority = 1100)
public abstract class EarthSkyMixin {
    /** Before vanilla opens the sky pass: uploads must happen while no render pass is open. */
    @Inject(method = "render", at = @At("HEAD"))
    private void galactica$prepareEarthSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
        EarthSky.prepare();
    }

    /** Behind the sun and moon, with the stars' pose: the Milky Way band. */
    @Inject(method = "renderSunMoonAndStars",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 1))
    private void galactica$milkyWay(RenderPass renderPass, PoseStack poseStack, float sunAngle, float moonAngle, float starAngle,
                                    MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
        EarthSky.renderMilkyWay(renderPass, poseStack, starAngle, starBrightness);
    }

    /** Inside vanilla's pass, with vanilla's star pose: draw the real stars instead of 1 500 random ones. */
    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void galactica$realNightSky(RenderPass renderPass, float starBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (EarthSky.renderStars(renderPass, starBrightness, poseStack)) ci.cancel();
    }
}
