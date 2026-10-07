package dev.galactica.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.galactica.Galactica;
import dev.galactica.client.vfx.StreamingVertexBuffer;
import dev.galactica.client.vfx.Vec4Block;
import dev.galactica.client.vfx.VfxPipelines;
import dev.galactica.client.vfx.VfxUniformBuffer;
import dev.galactica.nav.GalacticPos;
import dev.galactica.science.Units;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Draws the 3D map with Galactica's own pipelines into an offscreen colour texture the map screen then blits
 * full-screen under its interface. It runs at the end of the level's main pass ({@link LevelRenderEvents#END_MAIN}),
 * before the GUI and with no render pass open, so every upload (the projection block, the per-frame sprites, a newly
 * streamed star field) happens first, then one pass draws, back to front in blend terms:
 * <ol>
 *   <li>the galaxy disk impostor: {@link #DISC_LAYERS} face-on layers of the baked Milky Way ({@link FaceOnTexture}) at
 *       heights weighted like the thin disk's sech² profile, plus the bulge as a world-sized glow (additive);</li>
 *   <li>deep-sky clouds at their real size (additive);</li>
 *   <li>the streamed star field (static buffer, one draw) and the per-frame stars and glows (additive, pixel-sized);</li>
 *   <li>planets, moons and star discs, lit as spheres (translucent).</li>
 * </ol>
 * Orbits, labels, markers and panels are GUI elements drawn by the screen on top.
 */
public final class MapRenderer {
    private MapRenderer() {}

    static final int DISC_LAYERS = 15;
    /** Thin-disk scale height used to weight the layers, pc (drawn ~2× real for legibility from afar). */
    private static final double DISC_SCALE_HEIGHT_PC = 260;
    private static final double DISC_HALF_PC = MapView.BACKGROUND_HALF_PC;

    private static final RenderPipeline.Snippet MATRICES = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .buildSnippet();

    static final RenderPipeline SPRITE_PX = VfxPipelines.register(RenderPipeline.builder(MATRICES)
            .withLocation(VfxPipelines.id("pipeline/map_sprite"))
            .withVertexShader(VfxPipelines.id("core/map_sprite"))
            .withFragmentShader(VfxPipelines.id("core/map_sprite"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withCull(false)
            .build());
    static final RenderPipeline SPRITE_WORLD = VfxPipelines.register(RenderPipeline.builder(MATRICES)
            .withLocation(VfxPipelines.id("pipeline/map_sprite_world"))
            .withVertexShader(VfxPipelines.id("core/map_sprite"))
            .withFragmentShader(VfxPipelines.id("core/map_sprite"))
            .withShaderDefine("WORLD_SIZE")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withCull(false)
            .build());
    static final RenderPipeline BODY = VfxPipelines.register(RenderPipeline.builder(MATRICES)
            .withLocation(VfxPipelines.id("pipeline/map_body"))
            .withVertexShader(VfxPipelines.id("core/map_body"))
            .withFragmentShader(VfxPipelines.id("core/map_body"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build());
    static final RenderPipeline DISC = VfxPipelines.register(RenderPipeline.builder(MATRICES)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withLocation(VfxPipelines.id("pipeline/map_disc"))
            .withVertexShader(VfxPipelines.id("core/map_disc"))
            .withFragmentShader(VfxPipelines.id("core/map_disc"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withCull(false)
            .build());

    /** The screen being drawn for, set while the map is open. */
    private static @Nullable GalaxyMapScreen screen;
    private static GpuTexture colour;
    private static GpuTextureView colourView;
    /** Textures replaced by a resize stay alive a few frames: the GUI may still hold a view of the old one. */
    private static final ArrayDeque<Object[]> retired = new ArrayDeque<>();
    private static int frame;
    private static VfxUniformBuffer projection;
    private static StreamingVertexBuffer px, world, bodies;
    private static GpuBuffer field, disc;
    private static int fieldQuads, discQuads;
    private static GalacticPos fieldOrigin;
    private static boolean installed, warned;
    /** Frames drawn, for the autotest. */
    public static volatile int framesDrawn;

    public static void install() {
        if (installed) return;
        installed = true;
        LevelRenderEvents.END_MAIN.register(ctx -> render());
        if (DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize() != 28 || DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() != MapStarField.VERTEX_BYTES) {
            Galactica.LOG.warn("Galactica map: unexpected vertex sizes {} / {}", DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize(),
                    DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize());
        }
    }

    /** Render thread (screen init / removal), outside any pass: a new screen streams its own field. */
    static void attach(@Nullable GalaxyMapScreen s) {
        screen = s;
        if (field != null) {
            field.close();
            field = null;
        }
        fieldQuads = 0;
        if (s != null) s.field().markFresh(); // a resize re-runs init: upload the screen's current field again
    }

    /** The finished frame's texture view for the screen's blit, or null before the first frame. */
    static @Nullable GpuTextureView view() {
        return colourView;
    }

    static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    private static void render() {
        GalaxyMapScreen s = screen;
        Minecraft mc = Minecraft.getInstance();
        if (s == null || mc.gui.screen() != s) return;
        long perf = dev.galactica.perf.Perf.t0();
        try {
            draw(mc, s);
        } catch (RuntimeException e) {
            if (!warned) Galactica.LOG.error("Galactica map: drawing failed", e);
            warned = true;
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.MAP, perf);
        }
    }

    private static void draw(Minecraft mc, GalaxyMapScreen s) {
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        if (VfxUniformBuffer.inRenderPass(enc)) return;
        var main = mc.gameRenderer.mainRenderTarget();
        int w = main.width, h = main.height;
        ensureTarget(w, h);
        frame++;
        while (!retired.isEmpty() && (int) retired.peekFirst()[2] < frame - 3) {
            Object[] r = retired.pollFirst();
            ((GpuTextureView) r[1]).close();
            ((GpuTexture) r[0]).close();
        }

        // Scene for this frame: the camera eases, the streamed field is requested, the sprites are written.
        MapCamera cam = s.camera();
        cam.setViewport(w, h);
        MapScene scene = s.prepareFrame();
        MapStarField.Batch batch = s.field().current();

        if (projection == null) {
            projection = new VfxUniformBuffer("Galactica map projection", 64);
            px = new StreamingVertexBuffer("Galactica map sprites");
            world = new StreamingVertexBuffer("Galactica map clouds");
            bodies = new StreamingVertexBuffer("Galactica map bodies");
        }
        boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        Matrix4f proj = new Matrix4f(cam.projection(zZeroToOne));
        float[] m = proj.get(new float[16]);
        Vec4Block pb = new Vec4Block(4);
        for (int i = 0; i < 4; i++) pb.put(i, m[i * 4], m[i * 4 + 1], m[i * 4 + 2], m[i * 4 + 3]);
        GpuBufferSlice projSlice = projection.upload(pb);
        if (projSlice == null) return;

        if (s.field().takeFresh()) {
            if (field != null) field.close();
            field = null;
            fieldQuads = 0;
            ByteBuffer v = batch.vertices();
            if (v != null && v.remaining() > 0) {
                field = RenderSystem.getDevice().createBuffer(() -> "Galactica map field", GpuBuffer.USAGE_VERTEX, v.duplicate().order(ByteOrder.nativeOrder()));
                fieldQuads = v.remaining() / (4 * MapStarField.VERTEX_BYTES);
            }
            fieldOrigin = batch.origin();
        }
        if (disc == null) buildDisc();
        GpuBuffer pxBuf = scene.pxSprites.quads() > 0 ? px.upload(scene.pxSprites.data()) : null;
        GpuBuffer worldBuf = scene.worldSprites.quads() > 0 ? world.upload(scene.worldSprites.data()) : null;
        GpuBuffer bodyBuf = scene.bodies.quads() > 0 ? bodies.upload(scene.bodies.data()) : null;
        int maxQuads = Math.max(Math.max(fieldQuads, discQuads), Math.max(scene.pxSprites.quads(), Math.max(scene.worldSprites.quads(), scene.bodies.quads())));
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        quads.getBuffer(Math.max(6, maxQuads * 6)); // grow the shared index buffer before the pass opens

        boolean faceOn = FaceOnTexture.resolve();
        GpuTextureView discView = null;
        if (faceOn && scene.discAlpha > 0.01) {
            AbstractTexture t = mc.getTextureManager().getTexture(FaceOnTexture.ID);
            discView = t.getTextureView();
        }

        Matrix4d view = cam.view();
        Matrix4f viewF = new Matrix4f(view);
        Matrix4f params = new Matrix4f().m00(w).m01(h).m02(0).m03(0);
        Matrix4f fieldParams = new Matrix4f().m00(w).m01(h).m02((float) scene.fieldNearCull).m03(0);
        Vector4f white = new Vector4f(1, 1, 1, 1);

        enc.clearColorTexture(colour, new Vector4f(0.006F, 0.008F, 0.02F, 1F));
        CompiledRenderPipeline pSprite = VfxPipelines.compiledOrNull(SPRITE_PX), pWorld = VfxPipelines.compiledOrNull(SPRITE_WORLD),
                pBody = VfxPipelines.compiledOrNull(BODY), pDisc = VfxPipelines.compiledOrNull(DISC);
        try (RenderPass pass = enc.createRenderPass(() -> "Galactica 3D map", colourView, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projSlice);
            if (pDisc != null && discView != null && disc != null) {
                // Disk layers: parsecs about the Galactic Centre.
                Matrix4f mv = modelView(view, cam, GalacticPos.ofParsecs(0, 0, 0), Units.PARSEC);
                float k = (float) scene.discAlpha;
                GpuBufferSlice t = RenderSystem.getDynamicUniforms().writeTransform(mv, new Vector4f(k, k, k, 1), new Vector3f(), params);
                pass.setPipeline(pDisc);
                pass.setUniform("DynamicTransforms", t);
                pass.setUniform("Sampler0", discView, sampler());
                pass.setVertexBuffer(0, disc.slice());
                pass.setIndexBuffer(quads.getBuffer(discQuads * 6), quads.type());
                pass.drawIndexed(discQuads * 6, 1, 0, 0, 0);
            }
            if (pWorld != null && worldBuf != null) {
                GpuBufferSlice t = RenderSystem.getDynamicUniforms().writeTransform(viewF, white, new Vector3f(), params);
                quadsDraw(pass, pWorld, t, worldBuf, scene.worldSprites.quads(), quads);
            }
            if (pSprite != null && field != null && fieldQuads > 0) {
                Matrix4f mv = modelView(view, cam, fieldOrigin, Units.PARSEC);
                GpuBufferSlice t = RenderSystem.getDynamicUniforms().writeTransform(mv, white, new Vector3f(), fieldParams);
                quadsDraw(pass, pSprite, t, field, fieldQuads, quads);
            }
            if (pSprite != null && pxBuf != null) {
                GpuBufferSlice t = RenderSystem.getDynamicUniforms().writeTransform(viewF, white, new Vector3f(), params);
                quadsDraw(pass, pSprite, t, pxBuf, scene.pxSprites.quads(), quads);
            }
            if (pBody != null && bodyBuf != null) {
                GpuBufferSlice t = RenderSystem.getDynamicUniforms().writeTransform(viewF, white, new Vector3f(), params);
                quadsDraw(pass, pBody, t, bodyBuf, scene.bodies.quads(), quads);
            }
        }
        framesDrawn++;
    }

    private static void quadsDraw(RenderPass pass, CompiledRenderPipeline p, GpuBufferSlice transforms, GpuBuffer vb, int quadCount,
                                  RenderSystem.AutoStorageIndexBuffer quads) {
        pass.setPipeline(p);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setVertexBuffer(0, vb.slice());
        pass.setIndexBuffer(quads.getBuffer(quadCount * 6), quads.type());
        pass.drawIndexed(quadCount * 6, 1, 0, 0, 0);
    }

    /** View × model for a layer whose vertices are {@code unitMetres}-sized units relative to {@code origin}. */
    static Matrix4f modelView(Matrix4d view, MapCamera cam, GalacticPos origin, double unitMetres) {
        Vector3d o = cam.toUnits(origin);
        double s = unitMetres / cam.distance();
        return new Matrix4f(new Matrix4d(view).translate(o.x, o.y, o.z).scale(s));
    }

    private static void ensureTarget(int w, int h) {
        if (colour != null && colour.getWidth(0) == w && colour.getHeight(0) == h) return;
        if (colour != null) retired.add(new Object[] {colour, colourView, frame});
        var device = RenderSystem.getDevice();
        colour = device.createTexture(() -> "Galactica 3D map", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        colourView = device.createTextureView(colour);
    }

    /**
     * The disk impostor: {@link #DISC_LAYERS} squares 30 kpc across at heights spread over ±2.5 scale heights, each
     * weighted by sech²(z/h) so the stack integrates to the face-on image seen from above, and a bulge glow is added
     * by the scene. Texture u runs along +y and v along +x ({@link FaceOnBake}).
     */
    private static void buildDisc() {
        QuadWriter q = new QuadWriter(false, DISC_LAYERS);
        double[] wts = new double[DISC_LAYERS];
        double sum = 0;
        for (int i = 0; i < DISC_LAYERS; i++) {
            double z = layerZ(i);
            double c = Math.cosh(z / DISC_SCALE_HEIGHT_PC);
            wts[i] = 1 / (c * c);
            sum += wts[i];
        }
        float hp = (float) DISC_HALF_PC;
        for (int i = 0; i < DISC_LAYERS; i++) {
            float z = (float) layerZ(i);
            float[][] corners = {{-hp, -hp, z}, {hp, -hp, z}, {hp, hp, z}, {-hp, hp, z}};
            float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
            q.textured(corners, uv, 0xFFFFFFFF, (float) (wts[i] / sum * 1.15));
        }
        discQuads = q.quads();
        ByteBuffer d = q.data();
        disc = RenderSystem.getDevice().createBuffer(() -> "Galactica map disk", GpuBuffer.USAGE_VERTEX, d);
    }

    private static double layerZ(int i) {
        return (i - (DISC_LAYERS - 1) / 2.0) / ((DISC_LAYERS - 1) / 2.0) * 2.5 * DISC_SCALE_HEIGHT_PC;
    }
}
