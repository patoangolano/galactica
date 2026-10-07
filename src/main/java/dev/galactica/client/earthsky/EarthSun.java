package dev.galactica.client.earthsky;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.galactica.client.ship.IrisCompat;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.client.vfx.star.StarDiscRenderer;
import dev.galactica.client.vfx.star.StarVisual;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Locale;

/**
 * The Overworld's Sun drawn as the mod draws every star, in place of vanilla's square sprite: Sol (5 772 K) as the
 * animated plasma disc of {@link StarDiscRenderer} (limb darkening, granulation, corona) or, when that shader is
 * off or failed, the limb-darkened disc sprite in Sol's blackbody colour; both over a glare halo
 * ({@link SunAppearance#glare}). It sits exactly where vanilla puts its sun (vanilla's own pose for
 * {@code renderSun}, so the day cycle, Iris' sun-path tilt and the lighting all agree), is reddened and dimmed by
 * airmass extinction near the horizon ({@link SunAppearance#filter}) and fades with rain like vanilla's.
 *
 * <p><b>Size.</b> The disc has the true angular diameter, asin(R☉ / 1 AU) × 2 = 0.53° (about 8 px across at 70° FOV
 * on a 1080-line window), as every star and planet the mod draws (the other planets' suns in
 * {@code SurfaceSkyOverlay} too), not vanilla's ~17° square. On its own that disc is a pin-prick: what makes the
 * real Sun look big and blinding is glare, the eye's or the lens's bloom plus the aerosol aureole, which spread its
 * light over degrees. That is drawn as an additive halo (vanilla's own {@code CELESTIAL} blend, the one its square
 * sun used, so it lightens the sky instead of greying it) reaching {@value #GLARE_HALF_DEG}°: blown out to white
 * within about 1°, the way a daylight exposure renders the Sun, then the aureole's θ^−1.3 and a broad
 * glow. So the Sun reads at a glance like a photographed Sun, about as large as vanilla's at first sight, and the
 * plasma disc inside it is still the true size when looked at.
 *
 * <p>Two steps, because uniform uploads and buffer creation are illegal inside a render pass:
 * {@link #prepare} at HEAD of {@code SkyRenderer.render} (no pass open yet), {@link #render} at HEAD of
 * {@code renderSun} inside vanilla's sky pass. Off when the {@code realNightSky} setting is off, outside the
 * Overworld, and while an Iris shader pack is active (the pack draws its own sun from vanilla's).
 */
public final class EarthSun {
    private EarthSun() {}

    /** Angular radius of the Sun from 1 AU, rad (0.2666°). */
    public static final double ANGULAR_RADIUS = Math.asin(StarCatalog.SOL.radiusMetres() / Units.AU);
    public static final double GLARE_HALF_DEG = 20.0;
    /** Shells: glare behind the plasma disc (89, {@link StarDiscRenderer#SHELL_RADIUS}), the fallback sprite in between. */
    private static final float R_GLARE = 92f, R_SPRITE = 90f;
    private static final int GLARE_SIZE = 256;

    private static final StarVisual VISUAL = StarVisual.from(StarCatalog.SOL);
    private static final float[] BLACKBODY = new float[3];

    static {
        Blackbody.linearRgb(StarCatalog.SOL.temperature(), BLACKBODY);
    }

    private static DynamicTexture glare;
    private static GpuBuffer glareQuad, spriteQuad;

    /** This frame, set by {@link #prepare}. */
    private static boolean drawThisFrame, gpuThisFrame;
    private static final float[] FILTER = new float[3];
    private static float fade, altitudeDeg;

    /** Whether the mod's Sun replaces vanilla's this frame. */
    public static boolean enabled() {
        if (!EarthSky.realNightSky) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return false;
        return !IrisCompat.shaderPackActive();
    }

    /** HEAD of {@code SkyRenderer.render}, outside any render pass. */
    public static void prepare(SkyRenderState state) {
        drawThisFrame = gpuThisFrame = false;
        if (!enabled()) return;
        // Vanilla's sun sits at R_y(−90°)·R_x(sunAngle)·(0, 1, 0): its height above the horizon is cos(sunAngle).
        altitudeDeg = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, Math.cos(state.sunAngle)))));
        SunAppearance.filter(altitudeDeg, FILTER);
        fade = Math.max(0f, Math.min(1f, state.rainBrightness));
        if (fade * Math.max(FILTER[0], Math.max(FILTER[1], FILTER[2])) < 1e-3) return;
        ensureResources();
        if (StarDiscRenderer.wants(ANGULAR_RADIUS)) gpuThisFrame = StarDiscRenderer.prepare(VISUAL, ANGULAR_RADIUS, fade);
        // Grow the shared quad index buffer here, not inside the pass.
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
        drawThisFrame = true;
    }

    /**
     * HEAD of {@code SkyRenderer.renderSun}, inside vanilla's sky pass; {@code poseStack} holds vanilla's sun pose.
     * Returns true when vanilla's sun must be cancelled.
     */
    public static boolean render(RenderPass pass, PoseStack poseStack) {
        if (!enabled()) return false;
        if (!drawThisFrame) return true; // set, or rained out: nothing to draw, and no square sun either
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack()).mul(poseStack.last().pose());
        // Additive (CELESTIAL): the halo adds Sol's filtered light to the sky; rain fades it like vanilla's sun.
        if (fade * SunAppearance.luminance(FILTER) > 0.004f) {
            draw(pass, RenderPipelines.CELESTIAL, modelView, glareQuad, glare.getTextureView(),
                    new Vector4f(FILTER[0], FILTER[1], FILTER[2], fade), "Sun glare");
        }
        boolean drawn = gpuThisFrame && StarDiscRenderer.draw(pass, modelView, 0f, 1f, 0f, ANGULAR_RADIUS, VISUAL,
                new Vector4f(FILTER[0], FILTER[1], FILTER[2], 1f));
        if (!drawn) {
            Vector4f c = new Vector4f(BLACKBODY[0] * FILTER[0], BLACKBODY[1] * FILTER[1], BLACKBODY[2] * FILTER[2], fade);
            draw(pass, RenderPipelines.END_SKY, modelView, spriteQuad, SkyTextures.disc().getTextureView(), c, "Sun disc");
        }
        probeGpu = drawn;
        probeFrame = System.nanoTime();
        return true;
    }

    // ---------------------------------------------------------------- autotest probe

    private static boolean probeGpu;
    private static long probeFrame = Long.MIN_VALUE;

    /** Whether the last frame drew the mod's Sun (not vanilla's square). */
    public static boolean lastDrawn() {
        return probeFrame != Long.MIN_VALUE && System.nanoTime() - probeFrame < 500_000_000L;
    }

    public static String lastProbe() {
        return String.format(Locale.ROOT, "gpu=%s altitude=%.1f deg filter=(%.2f, %.2f, %.2f) rain fade=%.2f diameter=%.3f deg",
                probeGpu, altitudeDeg, FILTER[0], FILTER[1], FILTER[2], fade, Math.toDegrees(2 * ANGULAR_RADIUS));
    }

    public static float[] lastFilter() {
        return FILTER.clone();
    }

    public static float lastFade() {
        return fade;
    }

    // ---------------------------------------------------------------- resources (outside the pass)

    private static void ensureResources() {
        if (glare == null) {
            NativeImage img = new NativeImage(GLARE_SIZE, GLARE_SIZE, true);
            for (int y = 0; y < GLARE_SIZE; y++) {
                for (int x = 0; x < GLARE_SIZE; x++) {
                    double dx = (x + 0.5) / GLARE_SIZE * 2 - 1, dy = (y + 0.5) / GLARE_SIZE * 2 - 1;
                    // The quad is a gnomonic (tangent-plane) projection: radius r on it is atan(r·tan half) from the centre.
                    double theta = Math.toDegrees(Math.atan(Math.sqrt(dx * dx + dy * dy) * Math.tan(Math.toRadians(GLARE_HALF_DEG))));
                    int a = (int) Math.round(255 * SunAppearance.glare(theta, GLARE_HALF_DEG));
                    img.setPixel(x, y, (a << 24) | 0xFFFFFF);
                }
            }
            glare = new DynamicTexture(() -> "galactica:earth_sun_glare", img);
        }
        // CELESTIAL takes POSITION_TEX, END_SKY POSITION_TEX_COLOR.
        if (glareQuad == null) glareQuad = quad(R_GLARE, Math.toRadians(GLARE_HALF_DEG), DefaultVertexFormat.POSITION_TEX, "Galactica Earth sun glare");
        // SkyTextures.disc() puts the disc in the inner half of its quad, its halo outside.
        if (spriteQuad == null) spriteQuad = quad(R_SPRITE, 2 * ANGULAR_RADIUS, DefaultVertexFormat.POSITION_TEX_COLOR, "Galactica Earth sun disc");
        SkyTextures.disc(); // created lazily, and creating it uploads
    }

    /** A quad centred on vanilla's sun direction (+Y in the sun pose) at {@code radius}, half-angle {@code half}. */
    private static GpuBuffer quad(float radius, double half, VertexFormat format, String label) {
        float h = (float) (Math.tan(half) * radius);
        float[][] v = {{-h, radius, -h, 0, 0}, {h, radius, -h, 1, 0}, {h, radius, h, 1, 1}, {-h, radius, h, 0, 1}};
        boolean colour = format == DefaultVertexFormat.POSITION_TEX_COLOR;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(4 * format.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, format);
            // The sky pipelines cull back faces and the camera is at the centre: face the origin.
            for (int k : SkyMesh.inwardOrder(v)) {
                b.addVertex(v[k][0], v[k][1], v[k][2]).setUv(v[k][3], v[k][4]);
                if (colour) b.setColor(0xFFFFFFFF);
            }
            try (MeshData mesh = b.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void draw(RenderPass pass, RenderPipeline pipeline, Matrix4f modelView, GpuBuffer vb, GpuTextureView texture,
                             Vector4f colour, String label) {
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, colour);
        pass.pushDebugGroup(() -> label);
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Sampler0", texture, SkyTextures.linearClamp());
        pass.setVertexBuffer(0, vb.slice());
        pass.setIndexBuffer(quads.getBuffer(6), quads.type());
        pass.drawIndexed(6, 1, 0, 0, 0);
        pass.popDebugGroup();
    }
}
