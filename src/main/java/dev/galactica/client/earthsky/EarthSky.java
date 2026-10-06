package dev.galactica.client.earthsky;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.sky.GalaxySkyBaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Replaces the Overworld's 1 500 random vanilla stars with the real sky seen from the Sun: the
 * Milky Way ray-marched through the galaxy model and the real naked-eye stars, laid out in vanilla's
 * star-buffer frame so they wheel overhead with vanilla's own pose (and Iris' sun-path tilt).
 *
 * <p>Life cycle:
 * <ol>
 *   <li>{@link #initClient()} starts the bake ({@link EarthSkyBake}) on a low-priority pool using
 *       half the cores, and only when {@link #realNightSky} is on.</li>
 *   <li>{@link #prepare()} runs at HEAD of {@code SkyRenderer.render}, before the sky pass opens:
 *       it uploads the textures and builds the vertex buffers once the bake is done (uploads are
 *       illegal while a pass is open), then drops the CPU copies.</li>
 *   <li>{@link #renderMilkyWay} runs inside {@code renderSunMoonAndStars}, after the Y −90° turn
 *       (and Iris' sun-path tilt) and before the sun and moon are drawn, so the band sits behind
 *       them and never dims them.</li>
 *   <li>{@link #renderStars} runs at HEAD of {@code SkyRenderer.renderStars} and replaces vanilla's
 *       stars. Both draw with {@link RenderPipelines#END_SKY} (Iris: {@code gbuffers_skytextured}).</li>
 * </ol>
 * Until the bake lands vanilla's stars are left untouched.
 */
public final class EarthSky {
    private EarthSky() {}

    /**
     * Config flag {@code galactica.realNightSky}; default true. Read from the JVM system property of
     * the same name at {@link #initClient()}; the integrator may also set it directly (turning it on
     * later starts the bake on the next Overworld frame).
     */
    public static volatile boolean realNightSky = true;
    /**
     * Shift the stars through the year: the night side faces Orion in one season and Scorpius in
     * the other. Year length in Overworld days.
     */
    public static volatile boolean seasons = true;
    public static volatile double yearLengthDays = 365.25;

    public static final int SKY_WIDTH = 2048, SKY_HEIGHT = 1024;
    /** Shells inside vanilla's star radius (100): the band behind, stars in front. */
    private static final float R_GALAXY = 100f, R_STARS = 95f;

    private static CompletableFuture<EarthSkyBake.Baked> pending;
    private static boolean ready;
    private static DynamicTexture galaxyTexture, starTexture;
    private static GpuBuffer sphere, stars;
    private static int sphereIndices, starIndices;

    public static void initClient() {
        realNightSky = !"false".equalsIgnoreCase(System.getProperty("galactica.realNightSky", "true"));
        if (realNightSky) startBake();
    }

    private static synchronized void startBake() {
        if (pending != null) return;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        ForkJoinPool pool = new ForkJoinPool(threads, p -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
            t.setName("Galactica Earth sky baker " + t.getPoolIndex());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }, null, false);
        pending = CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            EarthSkyBake.Baked b = EarthSkyBake.bake(SKY_WIDTH, SKY_HEIGHT, pool);
            Galactica.LOG.info("Earth night sky baked in {} ms on {} threads ({} stars)",
                    (System.nanoTime() - t0) / 1_000_000, threads, b.stars().size());
            return b;
        }, pool).whenComplete((b, e) -> pool.shutdown()).exceptionally(e -> {
            Galactica.LOG.error("Earth night sky bake failed; keeping vanilla stars", e);
            return null;
        });
    }

    /** True when this frame's Overworld sky is ours. */
    public static boolean active() {
        if (!realNightSky || !ready) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }

    /** HEAD of SkyRenderer.render, outside any render pass: finish GPU uploads. */
    public static void prepare() {
        if (!realNightSky) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        if (pending == null) startBake();
        if (!ready && pending.isDone()) {
            EarthSkyBake.Baked baked = pending.getNow(null);
            if (baked == null) {
                realNightSky = false; // the bake threw: stay on vanilla stars for this session
                return;
            }
            upload(baked);
            ready = true;
            // Release the bake (an 8 MB pixel array and the sprite list): the GPU holds it all now.
            pending = CompletableFuture.completedFuture(null);
        }
        if (ready) {
            // Grow the shared index buffer now, not inside the pass.
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(Math.max(sphereIndices, starIndices));
        }
    }

    /**
     * Inside {@code renderSunMoonAndStars}, before the sun is drawn. {@code poseStack} holds
     * R_y(−90°) and Iris' sun-path tilt; the stars' own R_x(starAngle) is added here exactly as
     * vanilla adds it before {@code renderStars}.
     */
    public static void renderMilkyWay(RenderPass pass, PoseStack poseStack, float starAngle, float starBrightness) {
        if (starBrightness <= 0f || !active()) return;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack()).mul(poseStack.last().pose()).rotateX(starAngle);
        seasonal(modelView);
        Vector4f colour = new Vector4f(1f, 1f, 1f, Math.min(1f, starBrightness));
        draw(pass, modelView, sphere, sphereIndices, galaxyTexture.getTextureView(), SkyTextures.linearWrapU(), colour, "Milky Way (Earth)");
    }

    /**
     * HEAD of SkyRenderer.renderStars, inside vanilla's sky pass. {@code poseStack} already holds
     * vanilla's R_y(−90°)·R_x(starAngle) (and Iris' sun-path tilt). Returns true when vanilla's
     * stars must be cancelled.
     */
    public static boolean renderStars(RenderPass pass, float starBrightness, PoseStack poseStack) {
        if (!active()) return false;
        if (stars != null) {
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack()).mul(poseStack.last().pose());
            seasonal(modelView);
            Vector4f colour = new Vector4f(1f, 1f, 1f, Math.min(1f, starBrightness));
            draw(pass, modelView, stars, starIndices, starTexture.getTextureView(), SkyTextures.linearClamp(), colour, "Real stars");
        }
        return true;
    }

    private static void seasonal(Matrix4f modelView) {
        if (seasons) modelView.rotateX((float) Math.toRadians(EquatorialFrame.sunRightAscensionDeg(yearFraction())));
    }

    /** Fraction of the year since the March equinox, from the Overworld clock (day 0 = equinox). */
    private static double yearFraction() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        double days = mc.level.getOverworldClockTime() / 24000.0;
        return days / Math.max(1.0, yearLengthDays);
    }

    private static void draw(RenderPass pass, Matrix4f modelView, GpuBuffer vb, int indexCount,
                             GpuTextureView texture, GpuSampler sampler, Vector4f colour, String label) {
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, colour);
        pass.pushDebugGroup(() -> label);
        pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Sampler0", texture, sampler);
        pass.setVertexBuffer(0, vb.slice());
        pass.setIndexBuffer(quads.getBuffer(indexCount), quads.type());
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
        pass.popDebugGroup();
    }

    // ---------------------------------------------------------------- uploads (outside the pass)

    private static void upload(EarthSkyBake.Baked baked) {
        galaxyTexture = texture("galactica:earth_milky_way", baked.width(), baked.height(), baked.galaxyArgb());
        starTexture = texture("galactica:earth_star_atlas", StarAtlas.WIDTH, StarAtlas.HEIGHT, StarAtlas.pixels());
        buildSphere();
        buildStars(baked);
    }

    /**
     * Uploads {@code argb} (DynamicTexture's constructor writes it to the GPU), then swaps the CPU
     * copy for a 1×1 image: {@code setPixels} closes the big one. Vanilla's ReloadableTexture also
     * closes its NativeImage right after {@code writeToTexture}.
     */
    private static DynamicTexture texture(String label, int w, int h, int[] argb) {
        NativeImage img = new NativeImage(w, h, true);
        for (int i = 0; i < argb.length; i++) img.setPixel(i % w, i / w, argb[i]);
        DynamicTexture t = new DynamicTexture(() -> label, img);
        t.setPixels(new NativeImage(1, 1, true));
        return t;
    }

    private static void buildSphere() {
        int lon = 96, lat = 48;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(lon * lat * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            double[] d = new double[3];
            float[][] v = new float[4][];
            for (int j = 0; j < lat; j++) {
                for (int i = 0; i < lon; i++) {
                    float u0 = (float) i / lon, u1 = (float) (i + 1) / lon, v0 = (float) j / lat, v1 = (float) (j + 1) / lat;
                    float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                    for (int k = 0; k < 4; k++) {
                        GalaxySkyBaker.equirectDirection(uv[k][0], uv[k][1], d);
                        v[k] = new float[] {(float) d[0] * R_GALAXY, (float) d[1] * R_GALAXY, (float) d[2] * R_GALAXY, uv[k][0], uv[k][1]};
                    }
                    emitInwardQuad(b, v);
                }
            }
            try (MeshData mesh = b.buildOrThrow()) {
                sphereIndices = mesh.drawState().indexCount();
                sphere = RenderSystem.getDevice().createBuffer(() -> "Galactica Earth Milky Way", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** Each star: a quad at its direction whose UVs pick the atlas cell for its colour and magnitude. */
    private static void buildStars(EarthSkyBake.Baked baked) {
        var list = baked.stars();
        if (list.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(list.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (EarthSkyBake.Sprite s : list) {
                float[] cell = StarAtlas.uv(StarAtlas.column(s.r(), s.g(), s.b()), StarAtlas.row(s.mag()));
                spriteQuad(b, s.bx(), s.by(), s.bz(), R_STARS, StarAtlas.spriteSize(s.mag()), cell);
            }
            try (MeshData mesh = b.buildOrThrow()) {
                starIndices = mesh.drawState().indexCount();
                stars = RenderSystem.getDevice().createBuffer(() -> "Galactica Earth stars", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void spriteQuad(BufferBuilder b, float dx, float dy, float dz, float radius, double angularSize, float[] cell) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f t1 = new Vector3f(dir).cross(up).normalize();
        Vector3f t2 = new Vector3f(dir).cross(t1).normalize();
        float h = (float) (Math.tan(angularSize) * radius);
        Vector3f c = new Vector3f(dir).mul(radius);
        float[][] v = new float[4][];
        float[][] corners = {{-1, -1, cell[0], cell[1]}, {1, -1, cell[2], cell[1]}, {1, 1, cell[2], cell[3]}, {-1, 1, cell[0], cell[3]}};
        for (int k = 0; k < 4; k++) {
            float sx = corners[k][0] * h, sy = corners[k][1] * h;
            v[k] = new float[] {c.x + t1.x * sx + t2.x * sy, c.y + t1.y * sx + t2.y * sy, c.z + t1.z * sx + t2.z * sy, corners[k][2], corners[k][3]};
        }
        emitInwardQuad(b, v);
    }

    /**
     * END_SKY culls back faces and the camera is at the centre: wind every quad to face the origin
     * ({@link SkyMesh#inwardOrder}). Vertex colour is white; colour and brightness live in the
     * textures, so shader packs that drop vertex colour (Iris' SKY_TEXTURED is POSITION_TEX) keep them.
     */
    private static void emitInwardQuad(BufferBuilder b, float[][] v) {
        for (int k : SkyMesh.inwardOrder(v)) {
            float[] p = v[k];
            b.addVertex(p[0], p[1], p[2]).setUv(p[3], p[4]).setColor(0xFFFFFFFF);
        }
    }
}
