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
import dev.galactica.science.Blackbody;
import dev.galactica.sky.GalaxySkyBaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.concurrent.CompletableFuture;

/**
 * Replaces the Overworld's 1 500 random vanilla stars with the real sky seen from the Sun: the
 * Milky Way ray-marched through the galaxy model and the real naked-eye stars, laid out in vanilla's
 * star-buffer frame so they wheel overhead with vanilla's own pose (and Iris' sun-path tilt).
 *
 * <p>Life cycle:
 * <ol>
 *   <li>{@link #initClient()} starts the bake ({@link EarthSkyBake}) on a daemon thread.</li>
 *   <li>{@link #prepare()} runs at HEAD of {@code SkyRenderer.render}, before the sky pass opens:
 *       it uploads the texture and builds the vertex buffers once the bake is done (uploads are
 *       illegal while a pass is open).</li>
 *   <li>{@link #renderStars} runs at HEAD of {@code SkyRenderer.renderStars} inside vanilla's pass
 *       and draws with {@link RenderPipelines#END_SKY}, which Iris maps to
 *       {@code gbuffers_skytextured}.</li>
 * </ol>
 * Until the bake lands (a few seconds after launch) vanilla's stars are left untouched.
 */
public final class EarthSky {
    private EarthSky() {}

    /**
     * Config flag {@code galactica.realNightSky}; default true. Read from the JVM system property of
     * the same name at {@link #initClient()}; the integrator may also set it directly.
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
    /** Half-size of a faint star sprite, radians: ~2 px at 70° FOV on a 1080-line screen, as vanilla's 0.15–0.25 / 100. */
    private static final double STAR_SPRITE = 0.0022;
    private static final double MAX_SWELL = 5.0;

    private static CompletableFuture<EarthSkyBake.Baked> pending;
    private static boolean ready;
    private static DynamicTexture galaxyTexture;
    private static GpuBuffer sphere, stars;
    private static int sphereIndices, starIndices;

    public static void initClient() {
        realNightSky = !"false".equalsIgnoreCase(System.getProperty("galactica.realNightSky", "true"));
        startBake();
    }

    private static synchronized void startBake() {
        if (pending != null) return;
        pending = CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            EarthSkyBake.Baked b = EarthSkyBake.bake(SKY_WIDTH, SKY_HEIGHT);
            Galactica.LOG.info("Earth night sky baked in {} ms ({} stars)", (System.nanoTime() - t0) / 1_000_000, b.stars().size());
            return b;
        }, r -> {
            Thread t = new Thread(r, "Galactica Earth sky baker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }).exceptionally(e -> {
            Galactica.LOG.error("Earth night sky bake failed; keeping vanilla stars", e);
            return null;
        });
    }

    /** True when this frame's Overworld stars are ours. */
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
        }
        if (ready) {
            // Resolve every lazily-created texture and grow the shared index buffer now, not inside the pass.
            SkyTextures.psf().getTextureView();
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(Math.max(sphereIndices, starIndices));
        }
    }

    /**
     * HEAD of SkyRenderer.renderStars, inside vanilla's sky pass. {@code poseStack} already holds
     * vanilla's R_y(−90°)·R_x(starAngle) (and Iris' sun-path tilt). Returns true when it drew and
     * vanilla's stars must be cancelled.
     */
    public static boolean renderStars(RenderPass pass, float starBrightness, PoseStack poseStack) {
        if (!active()) return false;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack()).mul(poseStack.last().pose());
        if (seasons) modelView.rotateX((float) Math.toRadians(EquatorialFrame.sunRightAscensionDeg(yearFraction())));

        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        Vector4f colour = new Vector4f(1f, 1f, 1f, Math.min(1f, starBrightness));
        draw(pass, quads, modelView, sphere, sphereIndices, galaxyTexture.getTextureView(), SkyTextures.linearWrapU(), colour, "Milky Way (Earth)");
        if (stars != null) {
            draw(pass, quads, modelView, stars, starIndices, SkyTextures.psf().getTextureView(), SkyTextures.linearClamp(), colour, "Real stars");
        }
        return true;
    }

    /** Fraction of the year since the March equinox, from the Overworld clock (day 0 = equinox). */
    private static double yearFraction() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        double days = mc.level.getOverworldClockTime() / 24000.0;
        return days / Math.max(1.0, yearLengthDays);
    }

    private static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView, GpuBuffer vb, int indexCount,
                             GpuTextureView texture, GpuSampler sampler, Vector4f colour, String label) {
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
        NativeImage img = new NativeImage(baked.width(), baked.height(), true);
        int[] px = baked.galaxyArgb();
        for (int i = 0; i < px.length; i++) img.setPixel(i % baked.width(), i / baked.width(), px[i]);
        galaxyTexture = new DynamicTexture(() -> "galactica:earth_milky_way", img);
        buildSphere();
        buildStars(baked);
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
                    emitInwardQuad(b, v, 0xFFFFFFFF);
                }
            }
            try (MeshData mesh = b.buildOrThrow()) {
                sphereIndices = mesh.drawState().indexCount();
                sphere = RenderSystem.getDevice().createBuffer(() -> "Galactica Earth Milky Way", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void buildStars(EarthSkyBake.Baked baked) {
        var list = baked.stars();
        if (list.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(list.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (EarthSkyBake.Sprite s : list) {
                spriteQuad(b, s.bx(), s.by(), s.bz(), R_STARS, spriteSize(s.mag()), argb(s.r(), s.g(), s.b(), spriteAlpha(s.mag())));
            }
            try (MeshData mesh = b.buildOrThrow()) {
                starIndices = mesh.drawState().indexCount();
                stars = RenderSystem.getDevice().createBuffer(() -> "Galactica Earth stars", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** Faint stars dim at constant size (Pogson: flux ∝ 10^(−0.4 m)); full opacity is reached at V ≈ 4. */
    static float spriteAlpha(double mag) {
        return (float) Math.min(1.0, 0.1 * Math.pow(10, -0.4 * (mag - EarthSkyBake.LIMITING_MAG)));
    }

    /** Brighter than V ≈ 3.5, a star also swells, like the eye's/camera's saturated point-spread function. */
    static double spriteSize(double mag) {
        return STAR_SPRITE * Math.min(MAX_SWELL, Math.max(1.0, Math.pow(10, -0.4 * (mag - 3.5) * 0.4)));
    }

    private static void spriteQuad(BufferBuilder b, float dx, float dy, float dz, float radius, double angularSize, int argb) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f t1 = new Vector3f(dir).cross(up).normalize();
        Vector3f t2 = new Vector3f(dir).cross(t1).normalize();
        float h = (float) (Math.tan(angularSize) * radius);
        Vector3f c = new Vector3f(dir).mul(radius);
        float[][] v = new float[4][];
        float[][] corners = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
        for (int k = 0; k < 4; k++) {
            float sx = corners[k][0] * h, sy = corners[k][1] * h;
            v[k] = new float[] {c.x + t1.x * sx + t2.x * sy, c.y + t1.y * sx + t2.y * sy, c.z + t1.z * sx + t2.z * sy, corners[k][2], corners[k][3]};
        }
        emitInwardQuad(b, v, argb);
    }

    /** END_SKY culls back faces and the camera is at the centre: wind every quad to face the origin. */
    private static void emitInwardQuad(BufferBuilder b, float[][] v, int argb) {
        float ax = v[1][0] - v[0][0], ay = v[1][1] - v[0][1], az = v[1][2] - v[0][2];
        float bx = v[2][0] - v[0][0], by = v[2][1] - v[0][1], bz = v[2][2] - v[0][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float cx = v[0][0] + v[2][0], cy = v[0][1] + v[2][1], cz = v[0][2] + v[2][2];
        boolean outward = nx * cx + ny * cy + nz * cz > 0;
        for (int k = 0; k < 4; k++) {
            float[] p = v[outward ? 3 - k : k];
            b.addVertex(p[0], p[1], p[2]).setUv(p[3], p[4]).setColor(argb);
        }
    }

    private static int argb(float r, float g, float b, float a) {
        return (Math.round(Math.min(1, a) * 255) << 24) | (Math.round((float) Blackbody.encode(r) * 255) << 16)
                | (Math.round((float) Blackbody.encode(g) * 255) << 8) | Math.round((float) Blackbody.encode(b) * 255);
    }
}
