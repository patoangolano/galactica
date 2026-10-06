package dev.galactica.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * One persistent uniform buffer rewritten with {@code writeToBuffer} (the vanilla
 * GlobalSettingsUniform pattern, "route A" in docs/recon/api_vfx_pipelines.md). The write is a
 * command, so it must happen before a render pass opens; {@link #upload} refuses otherwise.
 *
 * <p>Identical contents are not re-sent. Blocks that carry an animation clock (such as
 * {@code StarParams}) change every frame, so for them the check never skips; it costs one array
 * comparison and never allocates, because the last block is kept in a preallocated copy.
 */
public final class VfxUniformBuffer implements AutoCloseable {
    private final GpuBuffer buffer;
    private final int sizeBytes;
    private final Vec4Block last;
    private boolean hasLast;

    public VfxUniformBuffer(String label, int sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes % 16 != 0) throw new IllegalArgumentException("size must be a positive multiple of 16, got " + sizeBytes);
        this.sizeBytes = sizeBytes;
        this.last = new Vec4Block(sizeBytes / 16);
        this.buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, sizeBytes);
    }

    /** True when a render pass is open, i.e. uploads are illegal right now. */
    public static boolean inRenderPass(CommandEncoder encoder) {
        return encoder instanceof FrontendCommandEncoder fe && fe.isInRenderPass();
    }

    /**
     * Uploads the block and returns the slice to bind, or null when called inside a render pass
     * (the previous contents stay bound-able, but are stale).
     */
    public GpuBufferSlice upload(Vec4Block block) {
        if (block.sizeBytes() != sizeBytes) throw new IllegalArgumentException("block is " + block.sizeBytes() + " B, buffer " + sizeBytes + " B");
        if (hasLast && block.equals(last)) return buffer.slice();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (inRenderPass(encoder)) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(sizeBytes);
            block.write(data);
            data.rewind();
            encoder.writeToBuffer(buffer.slice(), data);
        }
        last.copyFrom(block);
        hasLast = true;
        return buffer.slice();
    }

    public GpuBufferSlice slice() {
        return buffer.slice();
    }

    @Override
    public void close() {
        buffer.close();
    }
}
