package dev.galactica.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.nio.ByteBuffer;

/**
 * A vertex buffer rewritten in place, for geometry rebuilt every frame (the space sky's own star and planets, the
 * exotic sprites). Creating a fresh GPU buffer per frame cost an allocation in the driver each time; this one only
 * grows (to the next power of two) and is otherwise overwritten with one upload. Render thread, outside any pass.
 */
public final class StreamingVertexBuffer implements AutoCloseable {
    private final String label;
    private GpuBuffer buffer;

    public StreamingVertexBuffer(String label) {
        this.label = label;
    }

    /** Uploads {@code data} (its remaining bytes) and returns the buffer holding it from offset 0. */
    public GpuBuffer upload(ByteBuffer data) {
        int bytes = data.remaining();
        if (buffer == null || buffer.isClosed() || buffer.size() < bytes) {
            if (buffer != null) buffer.close();
            long capacity = Math.max(4096L, Long.highestOneBit(Math.max(1L, bytes - 1L)) << 1);
            buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, bytes), data);
        return buffer;
    }

    @Override
    public void close() {
        if (buffer != null) buffer.close();
        buffer = null;
    }
}
