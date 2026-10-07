package dev.galactica.client.exotic;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.client.sky.SpaceSkyRenderer;
import dev.galactica.deepsky.CloseUp;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.sky.GalaxySkyBaker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws close-range impostors of compact nebulae ({@link CloseUp}) over the baked Milky Way in the
 * space sky: when the viewer is within a few radii of the Orion Nebula, the Pleiades' dust or
 * another catalogued nebula, a gnomonic close-up at screen resolution replaces the 0.18° pixels of
 * the sky bake in that patch, feathered at its rim into the bake. Up to {@value #MAX_TARGETS} at
 * once (near M42 the Horsehead's IC 434 is a few degrees away).
 *
 * <p>Drawn with {@link RenderPipelines#END_SKY} on quads at radius {@value #SHELL}, between the
 * Milky Way shell (100) and the star shell (95), so the stars stay in front: call {@link #draw}
 * right after the "Milky Way" layer and before "Stars". Colour and opacity live in the textures.
 *
 * <p>Painting runs on one low-priority worker (the bake inside it is parallel). {@link #prepare},
 * before the pass opens, uploads finished paints and queues a new one when the viewer has moved
 * enough for a patch to drift by a twentieth of its half-angle or change size by 5 %; an image is
 * kept until its replacement is uploaded, and dropped when its nebula is no longer a target.
 * The target list is rescanned only after the viewer has moved by {@link CloseUp.Selector#REFRESH}
 * of the nearest nebula's distance, and the vertex buffer is rebuilt only when a patch changes.
 * Every texture is freed once the player leaves the space dimension (tick hook registered by
 * {@link #initClient}, called from {@link ExoticOverlay#initClient}).
 */
public final class NebulaCloseUp {
    private NebulaCloseUp() {}

    /** Between the Milky Way shell (100) and the star shell (95). */
    private static final float SHELL = 97f;
    private static final double PX_PER_RAD = 885.0;
    private static final double DRIFT = 0.05, RESIZE = 0.05;
    static final int MAX_TARGETS = 2;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica nebula close-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private record Painted(int[] argb, int size, double[] forwardMc, double halfAngle, int generation) {}

    /** One nebula's impostor: the uploaded image and the paint in flight. */
    private static final class Entry {
        DynamicTexture texture;
        int size;
        double[] forward;
        double half;
        CompletableFuture<Painted> job;
        double[] jobForward;
        double jobHalf;
    }

    private static final GalaxySkyBaker BAKER = new GalaxySkyBaker(Frames.GALAXY_SEED);
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final List<Entry> DRAWN = new ArrayList<>();
    private static final List<Entry> NEXT = new ArrayList<>();
    private static final CloseUp.Selector SELECTOR = new CloseUp.Selector();
    private static int generation;
    private static GpuBuffer quads;
    /** Set when a patch is uploaded or dropped: the quads must be rebuilt. */
    private static boolean quadsDirty;

    /** Registers the tick hook that frees every close-up once the player leaves the space dimension (or the world). */
    static void initClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if ((!ENTRIES.isEmpty() || quads != null) && !SpaceSkyRenderer.active()) clear();
        });
    }

    /**
     * Before the sky pass opens (from {@code SpaceSkyRenderer.render()}, next to
     * {@code ExoticOverlay.prepare}): picks the targets, uploads finished paints, queues new ones.
     */
    public static void prepare(GalacticPos eye) {
        if (eye == null) {
            clear();
            return;
        }
        NEXT.clear();
        double ox = eye.xPc(), oy = eye.yPc(), oz = eye.zPc();
        List<CloseUp.Target> targets = SELECTOR.targets(ox, oy, oz, MAX_TARGETS);
        List<String> ids = new ArrayList<>();
        for (CloseUp.Target t : targets) ids.add(t.object().id);
        // Forget nebulae that are no longer targets.
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> me = it.next();
            if (ids.contains(me.getKey())) continue;
            Entry e = me.getValue();
            if (e.job != null) e.job.cancel(false);
            if (e.texture != null) e.texture.close();
            it.remove();
            quadsDirty = true;
        }
        boolean queued = false;
        for (Entry e : ENTRIES.values()) if (e.job != null) queued = true;
        for (CloseUp.Target t : targets) {
            Entry e = ENTRIES.computeIfAbsent(t.object().id, k -> new Entry());
            if (e.job != null && e.job.isDone()) {
                Painted p = e.job.isCompletedExceptionally() || e.job.isCancelled() ? null : e.job.getNow(null);
                e.job = null;
                if (p != null && p.generation() == generation) upload(e, p);
            }
            double[] g = t.direction();
            double[] f = Frames.galacticToMc(g[0], g[1], g[2]);
            double half = t.halfAngle();
            double[] refF = e.job != null ? e.jobForward : e.forward;
            double refHalf = e.job != null ? e.jobHalf : e.half;
            boolean stale = refF == null || Math.abs(refHalf / half - 1) > RESIZE || Math.acos(Math.min(1, dot(refF, f))) > DRIFT * half;
            // One paint at a time on the worker: the largest target first.
            if (stale && e.job == null && !queued) {
                queued = true;
                int size = footprint(half);
                Vector3f[] basis = quadBasis((float) f[0], (float) f[1], (float) f[2]);
                // Quad u along t1, v along t2 (Minecraft axes) → galactocentric (x, −z, y).
                double[] e1 = {basis[0].x, -basis[0].z, basis[0].y}, e2 = {basis[1].x, -basis[1].z, basis[1].y};
                int gen = generation;
                e.jobForward = f;
                e.jobHalf = half;
                e.job = CompletableFuture.supplyAsync(() -> new Painted(CloseUp.paint(BAKER, ox, oy, oz, g, e1, e2, half, size),
                        size, f, half, gen), WORKER).exceptionally(ex -> {
                    Galactica.LOG.error("Nebula close-up paint failed", ex);
                    return null;
                });
            }
            if (e.texture != null) NEXT.add(e);
        }
        if (quadsDirty || !NEXT.equals(DRAWN)) {
            DRAWN.clear();
            DRAWN.addAll(NEXT);
            if (quads != null) {
                quads.close();
                quads = null;
            }
            if (!DRAWN.isEmpty()) buildQuads();
            quadsDirty = false;
        }
    }

    /** Inside the pass, right after the Milky Way layer and before the stars; {@code dim} is the Milky Way's dimming. */
    public static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer indices, Matrix4f modelView, float dim) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            drawTimed(pass, indices, modelView, dim);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.NEBULA, perf);
        }
    }

    private static void drawTimed(RenderPass pass, RenderSystem.AutoStorageIndexBuffer indices, Matrix4f modelView, float dim) {
        if (quads == null || DRAWN.isEmpty()) return;
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(dim, dim, dim, 1f));
        // Farthest (smallest) first, so a nearer nebula's patch lies over a farther one's.
        for (int i = DRAWN.size() - 1; i >= 0; i--) {
            Entry e = DRAWN.get(i);
            pass.pushDebugGroup(() -> "Galactica nebula close-up");
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("Sampler0", e.texture.getTextureView(), SkyTextures.linearClamp());
            pass.setVertexBuffer(0, quads.slice());
            pass.setIndexBuffer(indices.getBuffer(6), indices.type());
            pass.drawIndexed(6, 1, 0, i * 4, 0);
            pass.popDebugGroup();
        }
    }

    /** Close-up patches drawn in the last frame (autotest probe). */
    public static int drawnCount() {
        return DRAWN.size();
    }

    /** Frees every texture and drops the paints in flight. Render thread only. */
    public static void clear() {
        generation++;
        for (Entry e : ENTRIES.values()) {
            if (e.job != null) e.job.cancel(false);
            if (e.texture != null) e.texture.close();
        }
        ENTRIES.clear();
        DRAWN.clear();
        NEXT.clear();
        SELECTOR.reset();
        quadsDirty = false;
        if (quads != null) {
            quads.close();
            quads = null;
        }
    }

    /** Texture edge: the patch's on-screen size, a power of two between 128 and 1024. */
    static int footprint(double half) {
        int px = (int) Math.ceil(2 * Math.tan(half) * PX_PER_RAD);
        int size = Integer.highestOneBit(Math.max(1, px - 1)) << 1;
        return Math.max(128, Math.min(1024, size));
    }

    private static void upload(Entry e, Painted p) {
        int size = p.size();
        if (e.texture == null || e.size != size) {
            if (e.texture != null) e.texture.close();
            e.texture = new DynamicTexture(() -> "galactica:nebula_close_up", new NativeImage(size, size, true));
            e.size = size;
        }
        NativeImage img = e.texture.getPixels();
        int[] px = p.argb();
        for (int y = 0; y < size; y++) {
            int row = y * size;
            for (int x = 0; x < size; x++) img.setPixel(x, y, px[row + x]);
        }
        e.texture.upload();
        e.forward = p.forwardMc();
        e.half = p.halfAngle();
        quadsDirty = true;
    }

    private static void buildQuads() {
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(DRAWN.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (Entry e : DRAWN) quad(b, e.forward, e.half);
            try (MeshData mesh = b.buildOrThrow()) {
                quads = RenderSystem.getDevice().createBuffer(() -> "Galactica nebula close-ups", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** The patch's quad: centre at {@code forward}·{@value #SHELL}, u along t1, v along t2, gnomonic half-size tan(half). */
    private static void quad(BufferBuilder b, double[] forward, double half) {
        float fx = (float) forward[0], fy = (float) forward[1], fz = (float) forward[2];
        Vector3f dir = new Vector3f(fx, fy, fz).normalize();
        Vector3f[] basis = quadBasis(fx, fy, fz);
        float h = (float) (Math.tan(half) * SHELL);
        Vector3f c = new Vector3f(dir).mul(SHELL);
        float[][] corners = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
        float[][] v = new float[4][];
        for (int k = 0; k < 4; k++) {
            float sx = corners[k][0] * h, sy = corners[k][1] * h;
            v[k] = new float[] {c.x + basis[0].x * sx + basis[1].x * sy, c.y + basis[0].y * sx + basis[1].y * sy,
                    c.z + basis[0].z * sx + basis[1].z * sy, corners[k][2], corners[k][3]};
        }
        // END_SKY culls back faces and the camera sits at the centre: wind the quad inward.
        float ax = v[1][0] - v[0][0], ay = v[1][1] - v[0][1], az = v[1][2] - v[0][2];
        float bx = v[2][0] - v[0][0], by = v[2][1] - v[0][1], bz = v[2][2] - v[0][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        boolean outward = nx * (v[0][0] + v[2][0]) + ny * (v[0][1] + v[2][1]) + nz * (v[0][2] + v[2][2]) > 0;
        for (int k = 0; k < 4; k++) {
            float[] p = v[outward ? 3 - k : k];
            b.addVertex(p[0], p[1], p[2]).setUv(p[3], p[4]).setColor(0xFFFFFFFF);
        }
    }

    /** The same quad basis as {@code ExoticOverlay} and the planet sprites. */
    private static Vector3f[] quadBasis(float dx, float dy, float dz) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f t1 = new Vector3f(dir).cross(up).normalize();
        Vector3f t2 = new Vector3f(dir).cross(t1).normalize();
        return new Vector3f[] {t1, t2};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
