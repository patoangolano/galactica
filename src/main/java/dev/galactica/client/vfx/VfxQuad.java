package dev.galactica.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.joml.Matrix4f;

/**
 * A single POSITION_TEX_COLOR unit quad, corners (±1, ±1, 0) with UV = corner, shared by every
 * sprite effect. Placement goes into the model-view matrix ({@link #placement}), so one immutable
 * vertex buffer serves all draws. Creating the buffer uploads it: call {@link #ensure} before a pass.
 */
public final class VfxQuad {
    private VfxQuad() {}

    private static GpuBuffer quad;
    private static int indexCount;

    public static void ensure() {
        if (quad != null) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4)) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            b.addVertex(-1f, -1f, 0f).setUv(-1f, -1f).setColor(0xFFFFFFFF);
            b.addVertex(1f, -1f, 0f).setUv(1f, -1f).setColor(0xFFFFFFFF);
            b.addVertex(1f, 1f, 0f).setUv(1f, 1f).setColor(0xFFFFFFFF);
            b.addVertex(-1f, 1f, 0f).setUv(-1f, 1f).setColor(0xFFFFFFFF);
            try (MeshData mesh = b.buildOrThrow()) {
                indexCount = mesh.drawState().indexCount();
                quad = RenderSystem.getDevice().createBuffer(() -> "Galactica VFX unit quad", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    public static boolean ready() {
        return quad != null;
    }

    /** Binds the quad and its index buffer on an open pass and draws it (pipeline and uniforms already set). */
    public static void draw(RenderPass pass) {
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setVertexBuffer(0, quad.slice());
        pass.setIndexBuffer(quads.getBuffer(indexCount), quads.type());
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
    }

    /**
     * Model matrix putting the unit quad on a sky shell: centred on unit direction {@code dir} at
     * {@code radius}, x along {@code right}, y along {@code up}, half-size {@code half} (world units).
     * JOML's 16-float constructor is column-major.
     */
    public static Matrix4f placement(float dx, float dy, float dz, float rx, float ry, float rz, float ux, float uy, float uz,
                                     float radius, float half) {
        return new Matrix4f(
                rx * half, ry * half, rz * half, 0f,
                ux * half, uy * half, uz * half, 0f,
                dx, dy, dz, 0f,
                dx * radius, dy * radius, dz * radius, 1f);
    }
}
