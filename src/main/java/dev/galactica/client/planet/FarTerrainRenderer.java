package dev.galactica.client.planet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.planet.AtmosphereGradient;
import dev.galactica.planet.PlanetMacro;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.planet.TerrainSampler;
import dev.galactica.system.Body;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The ground beyond vanilla's render distance, out to the true horizon, while flying high over a
 * surface slot: a skirt of rings around the camera whose heights and colours come from the
 * {@link SurfaceClipmap} (the real generator), dropped by the curvature sag d²/(2R) of the map sphere,
 * so the ground curves away exactly as the planet seen from space does.
 *
 * <p><b>Geometry.</b> Horizon distance √(2Rh) for camera height h above the datum, plus √(2R·250)
 * so mountains beyond the horizon still poke over it. Each clipmap level draws the annulus it is the
 * finest level for; finer levels fade into coarser ones through the clipmap's alpha border.
 *
 * <p><b>Air.</b> Ground colour is multiplied by the transmittance of the path and a second, untextured
 * layer adds the airlight (Koschmieder 1924) from {@link AtmosphereGradient}, so distant terrain melts
 * into the horizon sky with the same colour on both sides of the space switch.
 *
 * <p><b>Pass.</b> Drawn in the sky pass ({@code END_SKY}, Iris-safe like every other sky layer) on a
 * shell of radius {@link #R_SHELL}: no depth is written, so the vanilla chunks drawn later cover the
 * skirt for free where real terrain exists. It fades in between Y {@link #FADE_LOW_Y} and
 * {@link #FADE_HIGH_Y}; lower down vanilla's own terrain and fog reach the horizon.
 */
public final class FarTerrainRenderer {
    private FarTerrainRenderer() {}

    static final float R_SHELL = 80f;
    public static final double FADE_LOW_Y = 400, FADE_HIGH_Y = 600;
    /** Start baking a little below the fade so the skirt is ready when it appears. */
    static final double BAKE_Y = 300;
    private static final int RINGS = 24, SEGMENTS = 96;
    /** Highest relief that can show over the horizon, blocks. */
    private static final double TALLEST = 250;

    private record Part(int firstVertex, int quads, DynamicTexture texture) {}

    private static GpuBuffer buffer;
    private static final List<Part> PARTS = new ArrayList<>();
    private static double builtX = Double.NaN, builtY, builtZ, builtSun0, builtSun1, builtSun2, builtAlpha;
    private static int builtGeneration = -1;

    /** Frees GPU buffers (the clipmap is released by its owner). */
    public static void release() {
        if (buffer != null) buffer.close();
        buffer = null;
        PARTS.clear();
        builtX = Double.NaN;
        builtGeneration = -1;
    }

    /**
     * Per frame, before the sky pass opens: requests and uploads clipmap levels and rebuilds the skirt
     * when the camera, the sun or the clipmap changed. {@code sunMc} is the unit direction to the sun
     * in camera-world axes; {@code renderChunks} vanilla's effective render distance.
     */
    public static void prepare(Body body, Vec3 cam, double[] sunMc, int renderChunks) {
        if (cam.y < BAKE_Y || !body.type().solidSurface) {
            if (buffer != null) release();
            return;
        }
        SurfaceClipmap clip = SurfaceClipmap.of(body);
        clip.request(cam.x, cam.z, 0);
        clip.upload();
        double alpha = PlanetMacro.smooth(FADE_LOW_Y, FADE_HIGH_Y, cam.y);
        if (alpha < 0.01) {
            if (buffer != null) release();
            return;
        }
        boolean moved = Double.isNaN(builtX) || Math.abs(cam.x - builtX) > 2 || Math.abs(cam.y - builtY) > 2 || Math.abs(cam.z - builtZ) > 2
                || Math.abs(sunMc[0] - builtSun0) + Math.abs(sunMc[1] - builtSun1) + Math.abs(sunMc[2] - builtSun2) > 0.003
                || Math.abs(alpha - builtAlpha) > 0.02 || clip.generation() != builtGeneration;
        if (!moved) return;
        builtX = cam.x;
        builtY = cam.y;
        builtZ = cam.z;
        builtSun0 = sunMc[0];
        builtSun1 = sunMc[1];
        builtSun2 = sunMc[2];
        builtAlpha = alpha;
        builtGeneration = clip.generation();
        rebuild(body, clip, cam, sunMc, renderChunks, alpha);
    }

    /** Draws the skirt inside the open sky pass. */
    public static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer idx, Matrix4f modelView) {
        if (buffer == null || PARTS.isEmpty()) return;
        GpuSampler clamp = SkyTextures.linearClamp();
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1));
        for (Part p : PARTS) {
            GpuTextureView view = p.texture().getTextureView();
            pass.pushDebugGroup(() -> "Galactica far terrain");
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("Sampler0", view, clamp);
            pass.setVertexBuffer(0, buffer.slice());
            pass.setIndexBuffer(idx.getBuffer(p.quads() * 6), idx.type());
            pass.drawIndexed(p.quads() * 6, 1, 0, p.firstVertex(), 0);
            pass.popDebugGroup();
        }
    }

    /** Horizon distance in blocks over a sphere of radius R for height h: √(2Rh + h²). */
    public static double horizonDistance(double radiusBlocks, double height) {
        double h = Math.max(0, height);
        return Math.sqrt(2 * radiusBlocks * h + h * h);
    }

    /** Curvature sag d²/(2R) of the ground at horizontal distance d below the tangent plane, blocks. */
    public static double curvatureDrop(double distance, double radiusBlocks) {
        return distance * distance / (2 * radiusBlocks);
    }

    // ---------------------------------------------------------------- mesh

    private static void rebuild(Body body, SurfaceClipmap clip, Vec3 cam, double[] sun, int renderChunks, double alpha) {
        if (buffer != null) buffer.close();
        buffer = null;
        PARTS.clear();
        SurfaceGrid grid = clip.grid();
        double rb = grid.radiusBlocks(), mpb = grid.metresPerBlock();
        double camH = cam.y - TerrainSampler.SEA_LEVEL;
        double r0 = Math.max(32, renderChunks * 16 * 0.85);
        double rMax = horizonDistance(rb, camH) + horizonDistance(rb, TALLEST);
        SurfaceClipmap.Level coarsest = clip.level(SurfaceClipmap.LEVELS - 1);
        rMax = Math.min(rMax, coarsest.span() * 0.5);
        if (rMax <= r0) return;
        AtmosphereGradient atm = AtmosphereGradient.of(body);
        double muSun = sun[1];
        // Direct light on the far ground: the sun's elevation (Lambert on level ground) through the air.
        double[] sunT = new double[3];
        atm.sunTransmittance(muSun, sunT);
        double lambert = 0.06 + 0.94 * PlanetMacro.smooth(-0.05, 0.35, muSun);

        int quadsTotal = 0;
        double[] inner = new double[SurfaceClipmap.LEVELS], outer = new double[SurfaceClipmap.LEVELS];
        boolean[] use = new boolean[SurfaceClipmap.LEVELS];
        double prevOpaque = r0;
        for (int k = 0; k < SurfaceClipmap.LEVELS; k++) {
            SurfaceClipmap.Level l = clip.level(k);
            if (!l.ready()) continue;
            double half = l.span() * 0.5 - SurfaceClipmap.FADE * l.spacing * 0.5;
            inner[k] = Math.max(r0, prevOpaque * 0.9);
            outer[k] = Math.min(rMax, half);
            if (outer[k] > inner[k]) {
                use[k] = true;
                quadsTotal += RINGS * SEGMENTS;
            }
            prevOpaque = Math.max(prevOpaque, l.span() * 0.5 - SurfaceClipmap.FADE * l.spacing);
        }
        if (quadsTotal == 0) return;
        quadsTotal += RINGS * SEGMENTS; // airlight layer

        int stride = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(quadsTotal * 4 * stride)) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int vertex = 0;
            float[][] q = new float[4][5];
            int[] qc = new int[4];
            double[] in = new double[3], tr = new double[3];
            // Ground, coarse to fine.
            for (int k = SurfaceClipmap.LEVELS - 1; k >= 0; k--) {
                if (!use[k]) continue;
                SurfaceClipmap.Level l = clip.level(k);
                int emitted = 0;
                for (int ri = 0; ri < RINGS; ri++) {
                    double ra = ring(inner[k], outer[k], ri), rc = ring(inner[k], outer[k], ri + 1);
                    for (int si = 0; si < SEGMENTS; si++) {
                        boolean ok = true;
                        for (int c = 0; c < 4 && ok; c++) {
                            double r = (c == 0 || c == 3) ? ra : rc;
                            double phi = 2 * Math.PI * (si + ((c == 2 || c == 3) ? 1 : 0)) / SEGMENTS;
                            double dx = r * Math.cos(phi), dz = r * Math.sin(phi);
                            double lx = l.centreX() + grid.wrapX(cam.x + dx - l.centreX()), lz = cam.z + dz;
                            double h = l.height(lx, lz);
                            if (Double.isNaN(h)) { ok = false; break; }
                            double y = h + 1 - curvatureDrop(r, rb) - cam.y;
                            double n = Math.sqrt(dx * dx + y * y + dz * dz);
                            double vx = dx / n, vy = y / n, vz = dz / n;
                            q[c][0] = (float) (vx * R_SHELL);
                            q[c][1] = (float) (vy * R_SHELL);
                            q[c][2] = (float) (vz * R_SHELL);
                            q[c][3] = (float) l.u(lx);
                            q[c][4] = (float) l.v(lz);
                            atm.horizontal(n * mpb, Math.max(0, (camH + (h - TerrainSampler.SEA_LEVEL)) * 0.5 * mpb),
                                    vx * sun[0] + vy * sun[1] + vz * sun[2], muSun, in, tr);
                            qc[c] = argb(enc(tr[0] * sunT[0] * lambert), enc(tr[1] * sunT[1] * lambert), enc(tr[2] * sunT[2] * lambert), alpha);
                        }
                        if (!ok) continue;
                        emitInward(b, q, qc);
                        emitted++;
                    }
                }
                if (emitted > 0) PARTS.add(new Part(vertex, emitted, l.texture()));
                vertex += emitted * 4;
            }
            // Airlight over the whole skirt, untextured.
            int emitted = 0;
            if (!atm.vacuum()) {
                double[] rs = {r0, rMax};
                for (int ri = 0; ri < RINGS; ri++) {
                    double ra = ring(rs[0], rs[1], ri), rc = ring(rs[0], rs[1], ri + 1);
                    for (int si = 0; si < SEGMENTS; si++) {
                        for (int c = 0; c < 4; c++) {
                            double r = (c == 0 || c == 3) ? ra : rc;
                            double phi = 2 * Math.PI * (si + ((c == 2 || c == 3) ? 1 : 0)) / SEGMENTS;
                            double dx = r * Math.cos(phi), dz = r * Math.sin(phi);
                            double h = clip.height(cam.x + dx, cam.z + dz);
                            if (Double.isNaN(h)) h = TerrainSampler.SEA_LEVEL;
                            double y = h + 1 - curvatureDrop(r, rb) - cam.y;
                            double n = Math.sqrt(dx * dx + y * y + dz * dz);
                            double vx = dx / n, vy = y / n, vz = dz / n;
                            q[c][0] = (float) (vx * R_SHELL * 0.995);
                            q[c][1] = (float) (vy * R_SHELL * 0.995);
                            q[c][2] = (float) (vz * R_SHELL * 0.995);
                            q[c][3] = 0.5f;
                            q[c][4] = 0.5f;
                            atm.horizontal(n * mpb, Math.max(0, (camH + (h - TerrainSampler.SEA_LEVEL)) * 0.5 * mpb),
                                    vx * sun[0] + vy * sun[1] + vz * sun[2], muSun, in, tr);
                            qc[c] = additive(in, alpha);
                        }
                        emitInward(b, q, qc);
                        emitted++;
                    }
                }
                if (emitted > 0) PARTS.add(new Part(vertex, emitted, SurfaceClipmap.white()));
            }
            if (PARTS.isEmpty()) return;
            try (MeshData mesh = b.buildOrThrow()) {
                buffer = RenderSystem.getDevice().createBuffer(() -> "Galactica far terrain", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** Ring radius i of {@link #RINGS}, geometric so rings are about equally tall on screen. */
    private static double ring(double r0, double r1, int i) {
        return r0 * Math.pow(r1 / r0, i / (double) RINGS);
    }

    /**
     * Encodes linear light L as an alpha-blended colour that adds L over what is behind:
     * a = max channel (display), rgb = display/a, so out = rgb·a + dst·(1 − a) ≈ L + dst·(1 − a).
     */
    static int additive(double[] linear, double alpha) {
        double r = enc(linear[0]), g = enc(linear[1]), b = enc(linear[2]);
        double a = Math.min(1, Math.max(r, Math.max(g, b)));
        if (a < 1e-4) return 0;
        return argb(r / a, g / a, b / a, a * alpha);
    }

    static double enc(double linear) {
        return AtmosphereGradient.encode(linear);
    }

    static int argb(double r, double g, double b, double a) {
        return (int) Math.round(clamp01(a) * 255) << 24 | (int) Math.round(clamp01(r) * 255) << 16
                | (int) Math.round(clamp01(g) * 255) << 8 | (int) Math.round(clamp01(b) * 255);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** END_SKY culls back faces and the camera sits at every shell's centre: wind each quad inward. */
    static void emitInward(BufferBuilder b, float[][] v, int[] argb) {
        float ax = v[1][0] - v[0][0], ay = v[1][1] - v[0][1], az = v[1][2] - v[0][2];
        float bx = v[2][0] - v[0][0], by = v[2][1] - v[0][1], bz = v[2][2] - v[0][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float cx = v[0][0] + v[2][0], cy = v[0][1] + v[2][1], cz = v[0][2] + v[2][2];
        boolean outward = nx * cx + ny * cy + nz * cz > 0;
        for (int k = 0; k < 4; k++) {
            int i = outward ? 3 - k : k;
            float[] p = v[i];
            b.addVertex(p[0], p[1], p[2]).setUv(p[3], p[4]).setColor(argb[i]);
        }
    }
}
