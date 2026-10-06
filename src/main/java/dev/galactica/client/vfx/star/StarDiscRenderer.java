package dev.galactica.client.vfx.star;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.galactica.client.vfx.Vec4Block;
import dev.galactica.client.vfx.VfxClock;
import dev.galactica.client.vfx.VfxPipelines;
import dev.galactica.client.vfx.VfxQuad;
import dev.galactica.client.vfx.VfxUniformBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws a resolved star as an animated plasma surface (shader {@code galactica:core/star_disc})
 * on a camera-facing quad on the sky shell.
 *
 * <p>Two-step use, because uniform uploads are commands that are illegal inside a render pass:
 * <pre>{@code
 * StarVisual v = StarVisual.from(star);                 // cache it per star
 * boolean gpu = StarDiscRenderer.prepare(v, angR);       // BEFORE createRenderPass
 * try (RenderPass pass = ...) {
 *     ...
 *     if (!gpu || !StarDiscRenderer.draw(pass, modelView, dx, dy, dz, angR, v)) drawOldTexturedQuad();
 * }
 * }</pre>
 * {@code draw} changes the pass's pipeline; the caller's next draw must set its own (the sky
 * renderers already do for every draw).
 */
public final class StarDiscRenderer {
    private StarDiscRenderer() {}

    /** Shell radius, just inside the near-body shell (90) so it is inside a 2-chunk far plane. */
    public static final float SHELL_RADIUS = 89f;
    /** Below this angular radius (≈ 1.3 px) the textured PSF dot looks the same and is cheaper. */
    public static final double MIN_ANGULAR_RADIUS = 0.0015;
    /** Distinct stars kept with their own uniform buffer (binaries, the overlay and space sky). */
    private static final int MAX_BUFFERS = 8;

    /** Premultiplied alpha: the disc (alpha = coverage) hides the sky behind it, the halo (alpha 0) adds light. */
    public static final RenderPipeline PIPELINE = VfxPipelines.skySprite("star_disc", "StarParams",
            BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA);

    private static final Vector4f WHITE = new Vector4f(1f, 1f, 1f, 1f);
    private static final Map<StarVisual, Slot> SLOTS = new LinkedHashMap<>(16, 0.75f, true);
    private static final Vec4Block SCRATCH = new Vec4Block(StarUniforms.VEC4_COUNT);

    private static final class Slot {
        final VfxUniformBuffer ubo = new VfxUniformBuffer("Galactica StarParams", StarUniforms.SIZE_BYTES);
        GpuBufferSlice ready;
        double angularRadius = Double.NaN;
        long usedNs;
    }

    /** Registers the pipeline for precompilation and the shader-retry listener. Call from the client initializer. */
    public static void initClient() {
        VfxPipelines.register(PIPELINE);
        VfxPipelines.initClient();
    }

    /** True when the shader is usable (not known to have failed). */
    public static boolean available() {
        return !VfxPipelines.failed(PIPELINE);
    }

    /** Whether a star at this angular radius should use the shader rather than a PSF dot. */
    public static boolean wants(double angularRadiusRad) {
        return angularRadiusRad > MIN_ANGULAR_RADIUS && available();
    }

    public static boolean prepare(StarVisual visual, double angularRadiusRad) {
        return prepare(visual, angularRadiusRad, 1f);
    }

    /**
     * Uploads this frame's parameters for one star. Must run before the render pass opens.
     *
     * @param intensity 0..1 overall fade (rain, dawn); 1 = full brightness
     * @return false when the star cannot be drawn by the shader this frame (caller falls back)
     */
    public static boolean prepare(StarVisual visual, double angularRadiusRad, float intensity) {
        if (visual == null || !visual.drawable() || !(angularRadiusRad > 0)) return false;
        if (VfxPipelines.compiledOrNull(PIPELINE) == null) return false;
        if (VfxUniformBuffer.inRenderPass(RenderSystem.getDevice().createCommandEncoder())) return false;
        VfxQuad.ensure();
        Slot slot = SLOTS.get(visual);
        if (slot == null) {
            evictStale();
            slot = new Slot();
            SLOTS.put(visual, slot);
        }
        StarUniforms.pack(visual, angularRadiusRad, Math.max(0f, Math.min(1f, intensity)), VfxClock.elapsedSeconds(), SCRATCH);
        slot.ready = slot.ubo.upload(SCRATCH);
        slot.angularRadius = angularRadiusRad;
        slot.usedNs = System.nanoTime();
        return slot.ready != null;
    }

    /**
     * Draws a star prepared this frame. {@code dir} need not be normalised.
     *
     * @return false if nothing was drawn (not prepared with this angular radius, or shader failed)
     */
    public static boolean draw(RenderPass pass, Matrix4f modelView, float dirX, float dirY, float dirZ,
                               double angularRadiusRad, StarVisual visual) {
        Slot slot = SLOTS.get(visual);
        if (slot == null || slot.ready == null || slot.angularRadius != angularRadiusRad || !VfxQuad.ready()) return false;
        CompiledRenderPipeline pipe = VfxPipelines.compiledOrNull(PIPELINE);
        if (pipe == null) return false;

        Vector3f dir = new Vector3f(dirX, dirY, dirZ);
        if (dir.lengthSquared() < 1e-12f) return false;
        dir.normalize();
        // right = dir x up and north = right x dir, so north is world up projected onto the sky (stellar
        // north drawn "up"). This is the opposite sign of SpaceSkyRenderer.quadBasis' t2 (dir x t1);
        // harmless because culling is off, and it keeps north up on screen.
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(dir).cross(up).normalize();
        Vector3f north = new Vector3f(right).cross(dir).normalize();
        float half = (float) (Math.tan(StarUniforms.quadHalfAngle(angularRadiusRad)) * SHELL_RADIUS);
        Matrix4f mv = new Matrix4f(modelView).mul(VfxQuad.placement(dir.x, dir.y, dir.z, right.x, right.y, right.z,
                north.x, north.y, north.z, SHELL_RADIUS, half));

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(mv, WHITE);
        pass.pushDebugGroup(() -> "Galactica star disc");
        pass.setPipeline(pipe);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("StarParams", slot.ready);
        VfxQuad.draw(pass);
        pass.popDebugGroup();
        // One draw per preparation: a stale slice must not be reused next frame without a new upload.
        slot.ready = null;
        return true;
    }

    /** Frees buffers of stars not drawn for 5 s once more than {@link #MAX_BUFFERS} are held. */
    private static void evictStale() {
        if (SLOTS.size() < MAX_BUFFERS) return;
        long now = System.nanoTime();
        Iterator<Map.Entry<StarVisual, Slot>> it = SLOTS.entrySet().iterator();
        while (it.hasNext() && SLOTS.size() >= MAX_BUFFERS) {
            Slot s = it.next().getValue();
            if (now - s.usedNs > 5_000_000_000L) {
                s.ubo.close();
                it.remove();
            }
        }
    }
}
