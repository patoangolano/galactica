package dev.galactica.mixin.perf;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.galactica.perf.Perf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/** Benchmark only: counts buffer and texture uploads and render passes. */
@Mixin(FrontendCommandEncoder.class)
public abstract class CommandEncoderCountMixin {
    @Inject(method = "writeToBuffer", at = @At("HEAD"), require = 0)
    private void galactica$buffer(GpuBufferSlice slice, ByteBuffer data, CallbackInfo ci) {
        if (!Perf.on) return;
        Perf.bufferWrites++;
        Perf.bufferWriteBytes += data.remaining();
    }

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("HEAD"), require = 0)
    private void galactica$image(GpuTexture texture, NativeImage image, CallbackInfo ci) {
        if (!Perf.on) return;
        Perf.textureWrites++;
        Perf.textureWriteBytes += (long) image.getWidth() * image.getHeight() * 4;
    }

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
            at = @At("HEAD"), require = 0)
    private void galactica$imageRegion(GpuTexture texture, NativeImage image, int a, int b, int c, int d, CallbackInfo ci) {
        if (!Perf.on) return;
        Perf.textureWrites++;
        Perf.textureWriteBytes += (long) image.getWidth() * image.getHeight() * 4;
    }

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"), require = 0)
    private void galactica$bytes(GpuTexture texture, ByteBuffer data, int a, int b, int c, int d, int e, int f, CallbackInfo ci) {
        if (!Perf.on) return;
        Perf.textureWrites++;
        Perf.textureWriteBytes += data.remaining();
    }

    @Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At("HEAD"), require = 0)
    private void galactica$pass(RenderPassDescriptor d, CallbackInfoReturnable<RenderPass> cir) {
        if (Perf.on) Perf.renderPasses++;
    }
}
