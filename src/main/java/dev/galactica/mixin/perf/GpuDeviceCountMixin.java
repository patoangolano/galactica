package dev.galactica.mixin.perf;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.galactica.perf.Perf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Benchmark only: counts GPU buffers and textures created (each one an allocation plus, with data, an upload). */
@Mixin(FrontendGpuDevice.class)
public abstract class GpuDeviceCountMixin {
    @Inject(method = "createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;",
            at = @At("HEAD"), require = 0)
    private void galactica$empty(Supplier<String> label, int usage, long size, CallbackInfoReturnable<GpuBuffer> cir) {
        if (!Perf.on) return;
        Perf.buffersCreated++;
        Perf.bufferCreateBytes += size;
    }

    @Inject(method = "createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;",
            at = @At("HEAD"), require = 0)
    private void galactica$filled(Supplier<String> label, int usage, ByteBuffer data, CallbackInfoReturnable<GpuBuffer> cir) {
        if (!Perf.on) return;
        Perf.buffersCreated++;
        Perf.bufferCreateBytes += data.remaining();
    }

    @Inject(method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;",
            at = @At("HEAD"), require = 0)
    private void galactica$texture(CallbackInfoReturnable<GpuTexture> cir) {
        if (Perf.on) Perf.texturesCreated++;
    }

    @Inject(method = "createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;",
            at = @At("HEAD"), require = 0)
    private void galactica$textureNamed(CallbackInfoReturnable<GpuTexture> cir) {
        if (Perf.on) Perf.texturesCreated++;
    }
}
