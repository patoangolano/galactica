package dev.galactica.client.planet;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.galactica.Galactica;
import dev.galactica.planet.transfer.EntryAerothermo;
import dev.galactica.planet.transfer.TransferNet;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;

/**
 * Entry plasma and the switch mask (M7), drawn with Galactica's own shaders.
 *
 * <ul>
 *   <li><b>Sheath and wake</b> ({@code core/plasma_sheath}): a bow-shock shell around the craft, shaped by
 *       Billig's correlation for the current Mach number ({@link EntryAerothermo#shockAxial}), with turbulent flow
 *       lines streaming downstream, and a trailing glow whose length grows with the plasma strength. Submitted in
 *       COLLECT_SUBMITS through a custom {@link RenderType}, so vanilla handles depth, fog, OIT and Iris phases.</li>
 *   <li><b>Screen</b> ({@code core/plasma_screen}): a smooth full-screen pass after the main pass (END_MAIN):
 *       plasma vignette with radial flow lines, the switch veil and the cross-fade of the last pre-switch frame,
 *       copied with {@link CommandEncoder#copyTextureToTexture} while the switch is under a second away.</li>
 *   <li><b>Embers</b> ({@code core/plasma_particle}): 64×64 soft additive sprites sampled LINEAR from a
 *       standalone texture (the particle atlas is NEAREST), see {@link Ember}.</li>
 * </ul>
 * Every pipeline compile is guarded: a pipeline that fails once is memoised as failed and never queried again,
 * since {@code RenderSystem.getCompiledPipelineNullable} returning null makes Iris throw. Uploads and copies happen
 * before our render pass opens; the vertex data goes through the submit path or none at all (full-screen triangle).
 */
public final class TransferFx {
    private TransferFx() {}

    private static Identifier id(String path) {
        return Galactica.id(path);
    }

    /** Standalone ember sprite; its .png.mcmeta sets blur (LINEAR) and clamp. */
    public static final Identifier EMBER_TEX = id("textures/fx/plasma_ember.png");

    private static final DepthStencilState TEST_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    // ---------------------------------------------------------------- pipelines

    private static RenderPipeline.Builder sheathBase() {
        return RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withVertexShader(id("core/plasma_sheath"))
                .withFragmentShader(id("core/plasma_sheath"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
    }

    /** Classic path: alpha-weighted additive, depth-tested, no depth write. */
    public static final RenderPipeline SHEATH = sheathBase()
            .withLocation(id("pipeline/plasma_sheath"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(TEST_NO_WRITE)
            .build();

    /** Improved-transparency path: required, or a blended render type crashes when OIT is on. */
    public static final OitPipelineSet SHEATH_OIT =
            OitPipelineSet.builder("galactica_plasma_sheath", sheathBase().withShaderDefine("OIT_ADDITIVE")).build();

    public static final RenderType SHEATH_TYPE = RenderType.create("galactica_plasma_sheath",
            RenderSetup.builder(SHEATH).setOitPipelines(SHEATH_OIT).createRenderSetup());

    /** Full-screen veil, vignette and cross-fade; premultiplied output. */
    public static final RenderPipeline SCREEN = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withLocation(id("pipeline/plasma_screen"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(id("core/plasma_screen"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();

    public static final RenderPipeline EMBER = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
            .withLocation(id("pipeline/plasma_particle"))
            .withFragmentShader(id("core/plasma_particle"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(TEST_NO_WRITE)
            .withCull(false)
            .build();

    public static final OitPipelineSet EMBER_OIT = OitPipelineSet.builder("galactica_plasma_particle",
                    RenderPipeline.builder(RenderPipelines.OIT_PARTICLE_SNIPPET)
                            .withFragmentShader(id("core/plasma_particle"))
                            .withShaderDefine("OIT_ADDITIVE")
                            .withCull(false))
            .build();

    // ---------------------------------------------------------------- lifecycle

    private static boolean screenFailed;
    private static TextureTarget snapshot;
    /** 1×1 stand-in bound to InSampler when no frame was ever copied (the shader multiplies it by fade = 0). */
    private static TextureTarget blank;
    private static long snapshotAt;

    static void init() {
        // Precompile at the first resource reload instead of hitching on first use (optional: a failure only warns).
        RenderPipelines.registerOptional(SHEATH);
        RenderPipelines.registerOptional(SCREEN);
        RenderPipelines.registerOptional(EMBER);
        ParticleProviderRegistry.getInstance().register(TransferNet.PLASMA_EMBER, Ember.Provider::new);
        LevelRenderEvents.COLLECT_SUBMITS.register(TransferFx::collect);
        LevelRenderEvents.END_MAIN.register(TransferFx::endMain);
    }

    // ---------------------------------------------------------------- sheath (world space)

    private static final int AZIMUTH = 32, RINGS = 14, WAKE_RINGS = 10;

    private static void collect(LevelRenderContext ctx) {
        TransferClient.frame();
        double glow = TransferClient.glow();
        Minecraft mc = Minecraft.getInstance();
        if (glow < 0.02 || mc.player == null) return;
        Entity root = mc.player.getRootVehicle();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 pos = root.getPosition(pt).add(0, root.getBbHeight() * 0.5, 0);
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        Vec3 a = TransferClient.flow();
        Vec3 ref = Math.abs(a.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 b1 = a.cross(ref).normalize(), b2 = a.cross(b1).normalize();
        float radius = (float) (Math.max(root.getBbWidth(), root.getBbHeight()) * 0.6 + 0.4);
        double mach = Math.clamp(TransferClient.mach(), 1.5, 25.0);
        int r = (int) (Math.clamp(glow, 0.0, 1.0) * 255), g = (int) (TransferClient.temperatureNorm() * 255);

        PoseStack ps = ctx.poseStack();
        ps.pushPose();
        ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
        ctx.submitNodeCollector().submitCustomGeometry(ps, SHEATH_TYPE, (pose, buf) -> {
            shell(pose, buf, a, b1, b2, radius, mach, r, g);
            wake(pose, buf, a, b1, b2, radius, glow, r, g);
        });
        ps.popPose();
    }

    /** Bow-shock shell: Billig hyperbola revolved about the flow axis, nose upstream. */
    private static void shell(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b1, Vec3 b2, float radius, double mach, int r, int g) {
        double yMax = radius * 3.2;
        for (int j = 0; j < RINGS; j++) {
            double v0 = (double) j / RINGS, v1 = (double) (j + 1) / RINGS;
            double y0 = Math.pow(v0, 1.3) * yMax, y1 = Math.pow(v1, 1.3) * yMax;
            double x0 = Math.max(-radius * 4, EntryAerothermo.shockAxial(radius, mach, y0));
            double x1 = Math.max(-radius * 4, EntryAerothermo.shockAxial(radius, mach, y1));
            int f0 = fade(v0), f1 = fade(v1);
            for (int i = 0; i < AZIMUTH; i++) {
                double t0 = 2 * Math.PI * i / AZIMUTH, t1 = 2 * Math.PI * (i + 1) / AZIMUTH;
                float u0 = (float) i / AZIMUTH, u1 = (float) (i + 1) / AZIMUTH;
                vertex(pose, buf, a, b1, b2, x0, y0, t0, u0, (float) v0, r, g, 0, f0);
                vertex(pose, buf, a, b1, b2, x0, y0, t1, u1, (float) v0, r, g, 0, f0);
                vertex(pose, buf, a, b1, b2, x1, y1, t1, u1, (float) v1, r, g, 0, f1);
                vertex(pose, buf, a, b1, b2, x1, y1, t0, u0, (float) v1, r, g, 0, f1);
            }
        }
    }

    /** Trailing glow: a tapering tube downstream, longer in stronger plasma. */
    private static void wake(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b1, Vec3 b2, float radius, double glow, int r, int g) {
        double length = radius * (4 + 14 * glow);
        for (int j = 0; j < WAKE_RINGS; j++) {
            double v0 = (double) j / WAKE_RINGS, v1 = (double) (j + 1) / WAKE_RINGS;
            double x0 = -radius * 0.4 - v0 * length, x1 = -radius * 0.4 - v1 * length;
            double y0 = radius * (1.1 - 0.75 * v0), y1 = radius * (1.1 - 0.75 * v1);
            int f0 = (int) (255 * (1 - v0)), f1 = (int) (255 * (1 - v1));
            for (int i = 0; i < AZIMUTH; i++) {
                double t0 = 2 * Math.PI * i / AZIMUTH, t1 = 2 * Math.PI * (i + 1) / AZIMUTH;
                float u0 = (float) i / AZIMUTH, u1 = (float) (i + 1) / AZIMUTH;
                vertex(pose, buf, a, b1, b2, x0, y0, t0, u0, (float) v0, r, g, 255, f0);
                vertex(pose, buf, a, b1, b2, x0, y0, t1, u1, (float) v0, r, g, 255, f0);
                vertex(pose, buf, a, b1, b2, x1, y1, t1, u1, (float) v1, r, g, 255, f1);
                vertex(pose, buf, a, b1, b2, x1, y1, t0, u0, (float) v1, r, g, 255, f1);
            }
        }
    }

    /** Shell rim fade: full over most of the shell, smoothly to 0 at the open end. */
    private static int fade(double v) {
        double x = Math.clamp((v - 0.7) / 0.3, 0.0, 1.0);
        return (int) (255 * (1 - x * x * (3 - 2 * x)));
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b1, Vec3 b2, double axial, double radial,
                               double theta, float u, float v, int r, int g, int kind, int alpha) {
        double c = Math.cos(theta) * radial, s = Math.sin(theta) * radial;
        float x = (float) (a.x * axial + b1.x * c + b2.x * s);
        float y = (float) (a.y * axial + b1.y * c + b2.y * s);
        float z = (float) (a.z * axial + b1.z * c + b2.z * s);
        buf.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, kind, alpha);
    }

    // ---------------------------------------------------------------- screen (after the main pass)

    private static CompiledRenderPipeline screenPipeline() {
        if (screenFailed) return null;
        try {
            CompiledRenderPipeline p = RenderSystem.getCompiledPipelineNullable(SCREEN);
            if (p == null) screenFailed = true;
            return p;
        } catch (RuntimeException e) {
            // Iris dereferences a null result in its own mixin: treat any failure as permanent for this session.
            screenFailed = true;
            Galactica.LOG.warn("Galactica plasma screen pipeline unavailable; the switch falls back to the HUD veil", e);
            return null;
        }
    }

    /** True when the shader screen pass is drawing; the HUD then skips its own fallback bands. */
    public static boolean screenActive() {
        return !screenFailed;
    }

    private static void endMain(LevelRenderContext ctx) {
        RenderTarget main = ctx.gameRenderer().mainRenderTarget();
        if (main.getColorTexture() == null) return;
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        if (enc instanceof com.mojang.renderpearl.frontend.FrontendCommandEncoder fe && fe.isInRenderPass()) return;

        // 1. Keep the newest frame of the old level while the switch is near (copy before any pass opens).
        if (TransferClient.captureWanted() && !screenFailed) {
            ensureSnapshot(main);
            enc.copyTextureToTexture(main.getColorTexture(), snapshot.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            snapshotAt = System.nanoTime();
        }

        double glow = TransferClient.glow(), mask = TransferClient.mask();
        boolean freshSnapshot = snapshot != null && TransferClient.switchedAt() != 0
                && TransferClient.switchedAt() - snapshotAt < (long) 1.0e9 && TransferClient.switchedAt() >= snapshotAt;
        double fade = freshSnapshot ? TransferClient.crossfade() : 0;
        double vignette = Math.max(glow * 0.75, mask);
        if (vignette < 0.003 && fade < 0.003) return;
        CompiledRenderPipeline pipe = screenPipeline();
        if (pipe == null) return;
        if (snapshot == null || fade > 0 && (snapshot.width != main.width || snapshot.height != main.height)) fade = 0;
        TextureTarget source = snapshot;
        if (source == null) {
            if (blank == null) {
                blank = new TextureTarget("galactica transfer blank", 1, 1, main.getColorTexture().getFormat(), null);
                // Defined contents (no pass is open yet): transparent black.
                enc.clearColorTexture(blank.getColorTexture(), new org.joml.Vector4f(0F, 0F, 0F, 0F));
            }
            source = blank;
        }

        float haze = (float) (1 - Math.clamp(glow / 0.08, 0.0, 1.0));
        float seconds = (float) ((System.nanoTime() / 1e9) % 3600.0);
        float[] centre = flowCentre();
        Matrix4f params = new Matrix4f().m00(seconds).m01(centre[0]).m02(centre[1]).m03((float) (0.8 + 1.6 * glow));
        // Write the uniforms before the pass opens: no buffer traffic while a render pass is recording.
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
                new Vector4f((float) vignette, (float) TransferClient.temperatureNorm(), (float) fade, haze),
                new Vector3f(), params);
        try (RenderPass pass = enc.createRenderPass(() -> "galactica plasma screen", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipe);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("InSampler", source.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void ensureSnapshot(RenderTarget main) {
        if (snapshot != null && snapshot.width == main.width && snapshot.height == main.height) return;
        if (snapshot != null) snapshot.destroyBuffers();
        // Same format as the main target: the backend copy needs compatible formats (Iris may swap it).
        snapshot = new TextureTarget("galactica transfer snapshot", main.width, main.height, main.getColorTexture().getFormat(), null);
    }

    /** Where the oncoming flow appears on screen (0..1), from the look direction and the direction of motion. */
    private static float[] flowCentre() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return new float[] {0.5F, 0.5F};
        Vec3 look = mc.player.getViewVector(1F);
        Vec3 f = TransferClient.flow();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();
        double ahead = Math.max(0.25, f.dot(look));
        float x = (float) Math.clamp(0.5 + 0.35 * f.dot(right) / ahead, 0.12, 0.88);
        float y = (float) Math.clamp(0.5 + 0.35 * f.dot(up) / ahead, 0.12, 0.88);
        return new float[] {x, y};
    }

    // ---------------------------------------------------------------- embers

    /** Additive, linearly filtered ember that bypasses the NEAREST particle atlas. */
    public static final class Ember extends SingleQuadParticle {
        /** Layers are keyed by identity: one instance. Translucent → after-terrain / OIT phase. */
        public static final SingleQuadParticle.Layer LAYER = new SingleQuadParticle.Layer(true, EMBER_TEX, EMBER, EMBER_OIT);

        private final float startSize;
        private final float tn;

        Ember(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z, dx, dy, dz, sprites.first());
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.hasPhysics = false;
            this.gravity = 0.0F;
            this.friction = 0.94F;
            RandomSource rnd = this.random;
            this.lifetime = 10 + rnd.nextInt(14);
            this.startSize = this.quadSize = 0.12F + rnd.nextFloat() * 0.22F;
            // Observers have no plasma state of their own: they see a mid-orange ember.
            double norm = TransferClient.glow() > 0.01 ? TransferClient.temperatureNorm() : 0.35;
            this.tn = (float) norm;
            colour(1F);
        }

        private void colour(float life) {
            // Embers cool as they trail: white-yellow at birth, deep orange-red at death.
            float heat = tn * 0.6F + 0.4F * life;
            this.setColor(1.0F, 0.25F + 0.7F * heat, 0.05F + 0.75F * heat * heat);
        }

        @Override
        protected SingleQuadParticle.Layer getLayer() {
            return LAYER;
        }

        @Override
        protected float getU0() {
            return 0F;
        }

        @Override
        protected float getU1() {
            return 1F;
        }

        @Override
        protected float getV0() {
            return 0F;
        }

        @Override
        protected float getV1() {
            return 1F;
        }

        @Override
        protected int getLightCoords(float partialTick) {
            return 0xF000F0;
        }

        @Override
        public void tick() {
            super.tick();
            float life = 1F - (float) this.age / this.lifetime;
            this.quadSize = startSize * (0.6F + 0.8F * life);
            this.alpha = life * life;
            colour(life);
        }

        public static final class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;

            public Provider(SpriteSet sprites) {
                this.sprites = sprites;
            }

            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                           double dx, double dy, double dz, RandomSource random) {
                // Every ember passes here, including the ones the server sends for other players' entries,
                // so this is where the entryEffects setting thins them (0 drops them all).
                double effects = dev.galactica.config.GalacticaConfigs.get().entryEffects();
                if (!dev.galactica.config.ConfigEffects.keepEmber(effects, random.nextDouble())) return null;
                return new Ember(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }
}
