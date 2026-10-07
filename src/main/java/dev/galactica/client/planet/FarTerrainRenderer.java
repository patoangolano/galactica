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
 * <p><b>Air.</b> Koschmieder's law (1924), L = G·T + L_h·(1 − T), is itself an over-blend: the ground
 * is drawn lit but unattenuated, and a second, untextured layer is blended over it with colour L_h
 * (the horizon sky of {@link AtmosphereGradient}, per channel weighted by its own 1 − T) and alpha
 * 1 − T, so distant terrain melts into the horizon sky. Heights above the datum go through the
 * slot's {@link AtmosphereGradient.AltitudeMap}, the same altitude the sky dome uses.
 *
 * <p><b>Cost.</b> The skirt is rebuilt when the camera moves by max(2, 1 % of its height) blocks, the
 * sun moves, the fade changes or a clipmap level lands, and at most every
 * {@link #MIN_REBUILD_NANOS} ns unless a new level landed.
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
    private static final double TALLEST = TerrainSampler.PEAK_CAP;
    /** Fastest rebuild rate while moving: 10 per second. */
    static final long MIN_REBUILD_NANOS = 100_000_000L;

    private record Part(int firstVertex, int quads, DynamicTexture texture) {}

    private static GpuBuffer buffer;
    private static final List<Part> PARTS = new ArrayList<>();
    private static double builtX = Double.NaN, builtY, builtZ, builtSun0, builtSun1, builtSun2, builtAlpha;
    private static int builtGeneration = -1;
    private static long builtAt;

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
    public static void prepare(Body body, Vec3 cam, double[] sunMc, int renderChunks, AtmosphereGradient.AltitudeMap altitude) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            prepareTimed(body, cam, sunMc, renderChunks, altitude);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.FAR_TERRAIN, perf);
        }
    }

    private static void prepareTimed(Body body, Vec3 cam, double[] sunMc, int renderChunks, AtmosphereGradient.AltitudeMap altitude) {
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
        double step = Math.max(2, 0.01 * (cam.y - TerrainSampler.SEA_LEVEL));
        boolean landed = clip.generation() != builtGeneration;
        boolean moved = Double.isNaN(builtX) || landed
                || Math.abs(clip.grid().wrapX(cam.x - builtX)) > step || Math.abs(cam.y - builtY) > step || Math.abs(cam.z - builtZ) > step
                || Math.abs(sunMc[0] - builtSun0) + Math.abs(sunMc[1] - builtSun1) + Math.abs(sunMc[2] - builtSun2) > 0.003
                || Math.abs(alpha - builtAlpha) > 0.02;
        if (!moved) return;
        long now = System.nanoTime();
        if (!landed && !Double.isNaN(builtX) && now - builtAt < MIN_REBUILD_NANOS) return;
        builtAt = now;
        builtX = cam.x;
        builtY = cam.y;
        builtZ = cam.z;
        builtSun0 = sunMc[0];
        builtSun1 = sunMc[1];
        builtSun2 = sunMc[2];
        builtAlpha = alpha;
        builtGeneration = clip.generation();
        rebuild(body, clip, cam, sunMc, renderChunks, alpha, altitude);
    }

    /** Draws the skirt inside the open sky pass. */
    public static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer idx, Matrix4f modelView) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            drawTimed(pass, idx, modelView);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.FAR_TERRAIN, perf);
        }
    }

    private static void drawTimed(RenderPass pass, RenderSystem.AutoStorageIndexBuffer idx, Matrix4f modelView) {
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

    private static void rebuild(Body body, SurfaceClipmap clip, Vec3 cam, double[] sun, int renderChunks, double alpha,
                                AtmosphereGradient.AltitudeMap altitude) {
        if (buffer != null) buffer.close();
        buffer = null;
        PARTS.clear();
        SurfaceGrid grid = clip.grid();
        double rb = grid.radiusBlocks(), mpb = grid.metresPerBlock();
        double camH = cam.y - TerrainSampler.SEA_LEVEL;
        double r0 = Math.max(32, renderChunks * 16 * 0.85);
        double rMax = horizonDistance(rb, camH) + horizonDistance(rb, TALLEST);
        int top = clip.coarsestUsable();
        if (top < 0 || rMax <= r0) return;
        AtmosphereGradient atm = AtmosphereGradient.of(body);
        double muSun = sun[1];
        // Direct light on the far ground: the sun's elevation (Lambert on level ground) through the air.
        double[] sunT = new double[3];
        atm.sunTransmittance(muSun, sunT);
        double lambert = 0.06 + 0.94 * PlanetMacro.smooth(-0.05, 0.35, muSun);

        int quadsTotal = 0;
        double[] inner = new double[SurfaceClipmap.LEVELS], outer = new double[SurfaceClipmap.LEVELS];
        boolean[] use = new boolean[SurfaceClipmap.LEVELS];
        double prevOpaque = r0, covered = r0;
        for (int k = 0; k <= top; k++) {
            SurfaceClipmap.Level l = clip.level(k);
            if (!l.ready()) continue;
            // A level is centred where it was last baked, which lags the camera by up to an eighth of
            // its span plus the snap: measure what it really covers around the camera (Chebyshev
            // distance to its edge, the largest circle inside the square).
            double off = Math.max(Math.abs(grid.wrapX(cam.x - l.centreX())), Math.abs(cam.z - l.centreZ()));
            double edge = l.span() * 0.5 - off;
            double fadeBand = SurfaceClipmap.FADE * l.spacing;
            inner[k] = Math.max(r0, prevOpaque * 0.9);
            outer[k] = Math.min(rMax, edge - 0.5 * fadeBand);
            if (outer[k] > inner[k]) {
                use[k] = true;
                quadsTotal += 2 * RINGS * SEGMENTS; // each ground quad goes out as two triangles (emitTriangles)
                covered = Math.max(covered, outer[k]);
            }
            prevOpaque = Math.max(prevOpaque, edge - fadeBand);
        }
        // The airlight layer stops where the ground does.
        rMax = Math.min(rMax, covered);
        if (quadsTotal == 0) return;
        quadsTotal += RINGS * SEGMENTS; // airlight layer

        int stride = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(quadsTotal * 4 * stride)) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int vertex = 0;
            float[][] q = new float[4][5];
            int[] qc = new int[4];
            double[] in = new double[3], tr = new double[3];
            double[] lattice = new double[(RINGS + 1) * SEGMENTS * 5];
            float[] uv = new float[(RINGS + 1) * SEGMENTS];
            // Ground, coarse to fine.
            for (int k = SurfaceClipmap.LEVELS - 1; k >= 0; k--) {
                if (!use[k]) continue;
                SurfaceClipmap.Level l = clip.level(k);
                int emitted = 0;
                // Directions on a (RINGS + 1) × SEGMENTS lattice, the elevation angle clamped to its running maximum
                // outward along each azimuth: ground hidden behind a nearer ridge folds into it instead of turning
                // the quad over (with 1 000-block mountains, folded quads left triangular holes in the skirt).
                for (int ri = 0; ri <= RINGS; ri++) {
                    double r = ring(inner[k], outer[k], ri);
                    for (int si = 0; si < SEGMENTS; si++) {
                        int o = (ri * SEGMENTS + si) * 5;
                        double phi = 2 * Math.PI * si / SEGMENTS;
                        double dx = r * Math.cos(phi), dz = r * Math.sin(phi);
                        double lx = l.centreX() + grid.wrapX(cam.x + dx - l.centreX()), lz = cam.z + dz;
                        double h = l.height(lx, lz);
                        if (Double.isNaN(h)) {
                            lattice[o] = Double.NaN;
                            continue;
                        }
                        double y = h + 1 - curvatureDrop(r, rb) - cam.y;
                        double slope = y / r; // tan(elevation)
                        if (ri > 0) {
                            double prev = lattice[((ri - 1) * SEGMENTS + si) * 5 + 4];
                            if (!Double.isNaN(prev) && slope < prev) slope = prev;
                        }
                        double n = Math.sqrt(1 + slope * slope);
                        lattice[o] = Math.cos(phi) / n;
                        lattice[o + 1] = slope / n;
                        lattice[o + 2] = Math.sin(phi) / n;
                        lattice[o + 3] = l.u(lx);
                        lattice[o + 4] = slope;
                        uv[ri * SEGMENTS + si] = (float) l.v(lz);
                    }
                }
                for (int ri = 0; ri < RINGS; ri++) {
                    for (int si = 0; si < SEGMENTS; si++) {
                        boolean ok = true;
                        for (int c = 0; c < 4 && ok; c++) {
                            int rr = (c == 0 || c == 3) ? ri : ri + 1;
                            int ss = (si + ((c == 2 || c == 3) ? 1 : 0)) % SEGMENTS;
                            int o = (rr * SEGMENTS + ss) * 5;
                            if (Double.isNaN(lattice[o])) { ok = false; break; }
                            q[c][0] = (float) (lattice[o] * R_SHELL);
                            q[c][1] = (float) (lattice[o + 1] * R_SHELL);
                            q[c][2] = (float) (lattice[o + 2] * R_SHELL);
                            q[c][3] = (float) lattice[o + 3];
                            q[c][4] = uv[rr * SEGMENTS + ss];
                            // Lit but unattenuated: the airlight layer over it applies the path's transmittance.
                            qc[c] = argb(enc(sunT[0] * lambert), enc(sunT[1] * lambert), enc(sunT[2] * lambert), alpha);
                        }
                        if (!ok) continue;
                        emitTriangles(b, q, qc);
                        emitted += 2;
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
                            double meanAlt = 0.5 * (altitude.metres(camH) + altitude.metres(h - TerrainSampler.SEA_LEVEL));
                            atm.horizontal(n * mpb, meanAlt, vx * sun[0] + vy * sun[1] + vz * sun[2], muSun, in, tr);
                            qc[c] = over(in, tr, alpha);
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
     * Koschmieder over-blend: alpha = 1 − T̄ (mean transmittance) and colour = inscatter/(1 − T̄) per
     * channel, where inscatter = L_h·(1 − T) is what the path adds. Blended over unattenuated ground G
     * it gives G·T̄ + L_h·(1 − T): the law, with one extinction for all three channels.
     */
    static int over(double[] inscatter, double[] transmittance, double fade) {
        double a = 1 - (transmittance[0] + transmittance[1] + transmittance[2]) / 3;
        if (a < 1e-4) return 0;
        return argb(enc(inscatter[0] / a), enc(inscatter[1] / a), enc(inscatter[2] / a), a * fade);
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
    private static final float[][] TRI = new float[4][];
    private static final int[] TRI_ARGB = new int[4];

    /**
     * A ground quad as two triangles (each a quad with its last vertex repeated), each turned to face the camera on
     * its own: a quad of tall terrain can be concave on screen, and one winding for the whole quad then culled one
     * of its triangles (the triangular holes in the skirt). Render thread only.
     */
    static void emitTriangles(BufferBuilder b, float[][] v, int[] argb) {
        int[][] tris = {{0, 1, 2}, {0, 2, 3}};
        for (int[] t : tris) {
            for (int k = 0; k < 3; k++) {
                TRI[k] = v[t[k]];
                TRI_ARGB[k] = argb[t[k]];
            }
            TRI[3] = v[t[2]];
            TRI_ARGB[3] = argb[t[2]];
            emitInward(b, TRI, TRI_ARGB);
        }
    }

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
