package dev.galactica.client.sky;

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
import dev.galactica.client.planet.SurfaceClipmap;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.planet.AtmosphereGradient;
import dev.galactica.planet.BodyFrame;
import dev.galactica.planet.PlanetEntry;
import dev.galactica.planet.SurfaceAtmosphere;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import dev.galactica.sky.PlanetImager;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * A solid body seen up close from space, once it fills more than {@link #CLAIM_HALF_ANGLE} of
 * angular radius: instead of the painted sprite, the visible cap of the sphere is drawn as a mesh
 * textured from the {@link SurfaceClipmap}, i.e. from the real terrain generator, so approaching a
 * planet shows the ground you will land on, and it keeps sharpening as you descend.
 *
 * <p><b>Layers</b>, back to front, all on the sky shell (r = {@link #R_SHELL}) through
 * {@code END_SKY} (Iris maps it to the pack's textured-sky program, like the rest of our sky):
 * <ol>
 *   <li>the sky dome, when the camera is inside the atmosphere ({@link AtmosphereGradient}, the same
 *       function the surface sky uses, so the colours match at the switch altitude);</li>
 *   <li>the globe: the whole visible cap from the clipmap's equirectangular globe;</li>
 *   <li>clipmap levels, coarse to fine, as patches of the sphere around the sub-camera point;</li>
 *   <li>the imager's cloud layer (Earth-like worlds);</li>
 *   <li>the airlight veil seen through the atmosphere, and the limb glow just outside the disc.</li>
 * </ol>
 *
 * <p><b>Precision.</b> Every vertex is built in double precision as eye → surface point =
 * (centre − eye) + R·n̂ and only its direction is stored, so a planet a few metres or many radii away
 * is equally exact. Only front-facing ground is meshed (the cap within the horizon angle
 * acos(R/d)), since the shell projection would otherwise fold the far side onto the near.
 *
 * <p><b>Light.</b> Lambert ground under the star's blackbody colour, times the direct-sun
 * transmittance. The view path is one over-blended veil (Koschmieder's form): alpha 1 − T̄ and colour
 * inscatter/(1 − T̄) from {@link AtmosphereGradient#lookDown}, so the ground is attenuated once.
 * Airless bodies ({@link SurfaceClipmap#spaceLit}) are lit per texel instead: the clipmap relights
 * its globe and levels for the true star and eye directions with the crater relief of
 * {@link dev.galactica.planet.SpaceRelief} (Lommel–Seeliger regolith, as the imager), so the
 * terminator falls where the star puts it and rims and crater floors catch and lose the light; the
 * vertex colour then only carries the star's colour and the exposure.
 *
 * <p><b>Hand-over.</b> A body starts baking its clipmap once it passes {@link #BAKE_HALF_ANGLE}; it is
 * claimed from the sprite only above {@link #CLAIM_HALF_ANGLE}, only if it was the largest such body
 * last frame and only once its globe is uploaded, so the planet never vanishes while a bake runs.
 * Cloud-deck worlds (Venus-like, steam, Titan-like haze) are never claimed: from space their deck
 * is what you see, and the imager's sprite already paints it.
 *
 * <p><b>Cost.</b> The mesh (≈15 k quads) is rebuilt only when the eye moves by more than a quarter
 * pixel at the nearest ground, the star direction or the body's spin moves comparably, or a clipmap
 * level lands.
 */
public final class NearPlanetRenderer {
    private NearPlanetRenderer() {}

    /** Above this angular radius the near renderer replaces the sprite. */
    public static final double CLAIM_HALF_ANGLE = Math.toRadians(20);
    /** Above this angular radius the body's clipmap starts baking, so it is ready by the claim. */
    public static final double BAKE_HALF_ANGLE = Math.toRadians(15);
    static final float R_SHELL = 89f;
    private static final int CAP_RINGS = 32, CAP_SEGMENTS = 80, LEVEL_GRID = 32, DOME_LAT = 16, DOME_LON = 32;
    private static final double PX_PER_RAD = 885.0;
    /** Largest view shift (radians) that keeps the last mesh: a quarter pixel. */
    private static final double SHIFT_EPS = 0.25 / PX_PER_RAD;

    private record Part(int firstVertex, int quads, DynamicTexture texture, boolean wrapU) {}

    private record Candidate(Body body, double halfAngle, Star star, GalacticPos bodyPos, GalacticPos starPos, GalacticPos eye, double t) {}

    /** Largest bakeable body this frame, and the one drawn (claimed) this frame. */
    private static Candidate largest, claimed;
    /** The largest bakeable body of the previous frame: the only one that may be claimed. */
    private static long winnerId = Long.MIN_VALUE;
    private static GpuBuffer buffer;
    private static final List<Part> SKY_PARTS = new ArrayList<>(), PARTS = new ArrayList<>();
    private static boolean active;
    private static final Ctx CTX = new Ctx();
    // What the current mesh was built from.
    private static long builtBody = Long.MIN_VALUE;
    private static int builtGeneration = -1, builtMinLevel = -1;
    private static final double[] builtRel = new double[3], builtToStar = new double[3];
    private static double builtSpin;

    /** Bodies the near renderer can draw: solid, and not hidden under a global cloud or haze deck. */
    static boolean eligible(Body b) {
        if (!b.type().solidSurface) return false;
        SurfaceAtmosphere.SkyClass sky = SurfaceAtmosphere.skyClass(b);
        return sky != SurfaceAtmosphere.SkyClass.YELLOW && sky != SurfaceAtmosphere.SkyClass.STEAM
                && sky != SurfaceAtmosphere.SkyClass.ORANGE;
    }

    /** True when the last mesh drew the claimed body from its sun-lit images (autotest probe). */
    public static boolean claimedLit() {
        return claimed != null && builtLit;
    }

    private static boolean builtLit;

    /** Name of the body drawn as terrain in the last frame, or null (autotest probe). */
    public static String claimedName() {
        return claimed == null ? null : claimed.body().name();
    }

    /** Start of a space-sky frame. */
    public static void beginFrame() {
        largest = null;
        claimed = null;
    }

    /**
     * Offers a body for this frame (positions galactic: its centre, its star and the eye). Bodies
     * above {@link #BAKE_HALF_ANGLE} start baking; returns true when the near renderer draws this
     * body this frame, in which case the caller skips its sprite.
     */
    public static boolean offer(Body b, double halfAngle, Star star, GalacticPos bodyPos, GalacticPos starPos, GalacticPos eye, double t) {
        if (halfAngle <= BAKE_HALF_ANGLE || !eligible(b)) return false;
        Candidate c = new Candidate(b, halfAngle, star, bodyPos, starPos, eye, t);
        if (largest == null || halfAngle > largest.halfAngle()) largest = c;
        if (claimed != null || halfAngle <= CLAIM_HALF_ANGLE || b.id() != winnerId) return false;
        SurfaceClipmap clip = SurfaceClipmap.current();
        if (clip == null || clip.body().id() != b.id() || clip.globe() == null) return false;
        // An airless body is drawn only once its sun-lit globe exists (the unlit one has no relief).
        if (clip.spaceLit() && clip.globeLit() == null) return false;
        claimed = c;
        return true;
    }

    /**
     * End of the frame's preparation, before the sky pass opens: bakes/uploads the clipmap of the
     * claimed (or else the largest) body and rebuilds the mesh of the claimed one when it moved;
     * frees everything when nothing is close.
     */
    public static void endFrame() {
        Candidate target = claimed != null ? claimed : largest;
        winnerId = largest != null ? largest.body().id() : Long.MIN_VALUE;
        if (claimed == null) freeMesh();
        if (target == null) {
            if (active) {
                active = false;
                // The surface overlay owns the clipmap on the ground; in space only we let it go.
                if (SurfaceClipmap.current() != null) SurfaceClipmap.releaseAll();
            }
            return;
        }
        active = true;
        Ctx x = CTX;
        if (!x.setup(target)) {
            freeMesh();
            return;
        }
        SurfaceClipmap clip = SurfaceClipmap.of(target.body());
        int minLevel = minLevel(x, clip);
        clip.request(x.subX, x.subZ, minLevel);
        if (clip.spaceLit()) clip.requestLight(x.bodyFixedLight());
        clip.upload();
        if (claimed == null) return;
        if (!changed(x, clip, minLevel)) return;
        freeMesh();
        build(x, clip, minLevel);
    }

    /**
     * Draws the atmosphere dome seen from inside the air. Call in the space-sky pass right after the
     * Milky Way and the stars, before the sun and the near bodies, so the sun is not veiled by it.
     */
    public static void drawSky(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView) {
        drawParts(pass, quads, modelView, SKY_PARTS);
    }

    /** Draws the claimed body inside the open space-sky pass, after the resolved planets. */
    public static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView) {
        drawParts(pass, quads, modelView, PARTS);
    }

    private static void drawParts(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView, List<Part> parts) {
        if (buffer == null || parts.isEmpty()) return;
        GpuSampler clamp = SkyTextures.linearClamp(), wrapU = SkyTextures.linearWrapU();
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1));
        for (Part p : parts) {
            pass.pushDebugGroup(() -> "Galactica near planet");
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("Sampler0", p.texture().getTextureView(), p.wrapU() ? wrapU : clamp);
            pass.setVertexBuffer(0, buffer.slice());
            pass.setIndexBuffer(quads.getBuffer(p.quads() * 6), quads.type());
            pass.drawIndexed(p.quads() * 6, 1, 0, p.firstVertex(), 0);
            pass.popDebugGroup();
        }
    }

    private static void freeMesh() {
        if (buffer != null) buffer.close();
        buffer = null;
        PARTS.clear();
        SKY_PARTS.clear();
        builtBody = Long.MIN_VALUE;
    }

    // ---------------------------------------------------------------- build

    /** Per-frame geometry context: everything in galactic axes, metres, relative to the eye. Reused. */
    private static final class Ctx {
        Body body;
        final double[] rel = new double[3]; // eye → centre
        double d, radius, altitude, subX, subZ;
        double[] pole, e1, e2;
        final double[] toStar = new double[3], up = new double[3];
        double spin;
        float starR, starG, starB, brightness;
        AtmosphereGradient atm;
        boolean airless;
        SurfaceGrid grid;
        final double[] in = new double[3], tr = new double[3], sunT = new double[3];
        final double[] p = new double[3], v = new double[3], tp = new double[3];
        final float[] rgb = new float[3];

        /** Fills the context for a candidate; false when the eye is inside the body. */
        boolean setup(Candidate cand) {
            Body b = cand.body();
            body = b;
            double[] r = cand.bodyPos().metresFrom(cand.eye());
            System.arraycopy(r, 0, rel, 0, 3);
            d = Math.sqrt(dot(rel, rel));
            radius = b.radiusMetres();
            altitude = d - radius;
            if (altitude <= 0) return false;
            for (int k = 0; k < 3; k++) up[k] = -rel[k] / d;
            pole = BodyFrame.pole(cand.star(), b);
            e1 = BodyFrame.meridianZero(pole);
            e2 = cross(pole, e1);
            spin = BodyFrame.spinAngle(b, cand.t());
            double[] ts = cand.starPos().metresFrom(cand.bodyPos());
            double ns = Math.sqrt(dot(ts, ts));
            for (int k = 0; k < 3; k++) toStar[k] = ts[k] / ns;
            Blackbody.linearRgb(Math.max(1000, cand.star().temperature()), rgb);
            starR = rgb[0];
            starG = rgb[1];
            starB = rgb[2];
            // Same auto-exposure as the sprite: flux^0.3 clamped, so the hand-over does not change brightness.
            double flux = cand.star().luminosity() / Math.max(1e-12, Math.pow(ns / Units.AU, 2));
            brightness = (float) Math.max(0.2, Math.min(1.0, Math.pow(flux, 0.3)));
            atm = AtmosphereGradient.of(b);
            airless = atm.vacuum() || !(b.surfacePressureBar() > 0.05);
            grid = SurfaceGrid.of(radius);
            double[] ll = BodyFrame.latLon(cand.star(), b, cand.t(), up);
            subX = grid.blockX(ll[1]);
            subZ = grid.blockZ(ll[0]);
            return true;
        }

        /** Star direction and eye position (in radii) in the body-fixed frame of the terrain. */
        SurfaceClipmap.SpaceLight bodyFixedLight() {
            double cs = Math.cos(spin), ss = Math.sin(spin);
            double[] bx = new double[3], by = new double[3];
            for (int k = 0; k < 3; k++) {
                bx[k] = e1[k] * cs + e2[k] * ss;
                by[k] = -e1[k] * ss + e2[k] * cs;
            }
            return new SurfaceClipmap.SpaceLight(dot(toStar, bx), dot(toStar, by), dot(toStar, pole),
                    -dot(rel, bx) / radius, -dot(rel, by) / radius, -dot(rel, pole) / radius);
        }

        /** Galactic unit normal of body-fixed latitude/longitude. */
        void normal(double lat, double lon, double[] out) {
            double a = lon + spin, c = Math.cos(lat), s = Math.sin(lat), ca = Math.cos(a) * c, sa = Math.sin(a) * c;
            for (int k = 0; k < 3; k++) out[k] = e1[k] * ca + e2[k] * sa + pole[k] * s;
        }
    }

    /** Finest clipmap level the screen can resolve at nadir. */
    private static int minLevel(Ctx x, SurfaceClipmap clip) {
        double footprint = x.altitude / x.grid.metresPerBlock() / PX_PER_RAD; // blocks per pixel at nadir
        for (int k = 0; k < SurfaceClipmap.LEVELS; k++) {
            if (clip.level(k).spacing >= footprint * 0.5) return k;
        }
        return SurfaceClipmap.LEVELS;
    }

    /** True when the last mesh no longer matches what the eye sees (quarter-pixel tolerances). */
    private static boolean changed(Ctx x, SurfaceClipmap clip, int minLevel) {
        boolean same = buffer != null && builtBody == x.body.id() && builtGeneration == clip.generation() && builtMinLevel == minLevel;
        if (same) {
            double dr = 0, ds = 0;
            for (int k = 0; k < 3; k++) {
                dr += (x.rel[k] - builtRel[k]) * (x.rel[k] - builtRel[k]);
                ds += (x.toStar[k] - builtToStar[k]) * (x.toStar[k] - builtToStar[k]);
            }
            double tol = SHIFT_EPS * x.altitude; // eye shift seen at the nearest ground
            same = dr < tol * tol && Math.sqrt(ds) < 4 * SHIFT_EPS && x.radius * Math.abs(x.spin - builtSpin) < tol;
        }
        if (same) return false;
        builtBody = x.body.id();
        builtGeneration = clip.generation();
        builtMinLevel = minLevel;
        System.arraycopy(x.rel, 0, builtRel, 0, 3);
        System.arraycopy(x.toStar, 0, builtToStar, 0, 3);
        builtSpin = x.spin;
        return true;
    }

    private static void build(Ctx x, SurfaceClipmap clip, int minLevel) {
        Body b = x.body;
        int stride = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
        int maxQuads = DOME_LAT * DOME_LON + 3 * CAP_RINGS * CAP_SEGMENTS + SurfaceClipmap.LEVELS * LEVEL_GRID * LEVEL_GRID + CAP_SEGMENTS * 4;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(maxQuads * 4 * stride)) {
            BufferBuilder buf = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int[] vertex = {0};
            // Inside the entry shell's neighbourhood only: the take-off point sits at 1.5× its altitude.
            if (!x.atm.vacuum() && x.altitude < 1.5 * PlanetEntry.entryAltitude(b)) {
                addPart(SKY_PARTS, vertex, dome(buf, x), SurfaceClipmap.white(), false);
            }
            // Airless: the sun-lit images, whose texels carry the light (mode 3); else the albedo images.
            boolean lit = clip.spaceLit() && clip.globeLit() != null;
            builtLit = lit;
            DynamicTexture globe = lit ? clip.globeLit() : clip.globe();
            double horizon = Math.acos(Math.min(1, x.radius / x.d)) * 0.9995;
            if (globe != null) addPart(PARTS, vertex, cap(buf, x, horizon, lit ? 3 : 0), globe, true);
            // Each level fades into whatever is drawn under it; the bottom one only when the globe cap is there.
            boolean under = globe != null;
            for (int k = clip.coarsestUsable(); k >= Math.min(minLevel, SurfaceClipmap.LEVELS - 1); k--) {
                SurfaceClipmap.Level l = clip.level(k);
                if (!l.ready()) continue;
                DynamicTexture tex = lit ? l.litTexture() : l.texture();
                if (tex == null) continue; // its lit image is still on the way
                addPart(PARTS, vertex, level(buf, x, l, under, lit), tex, false);
                under = true;
            }
            DynamicTexture clouds = clip.clouds();
            if (clouds != null && (b.type() == BodyType.TERRAN || b.type() == BodyType.OCEAN)) {
                addPart(PARTS, vertex, cap(buf, x, horizon, 1), clouds, true);
            }
            if (!x.atm.vacuum()) {
                addPart(PARTS, vertex, cap(buf, x, horizon, 2), SurfaceClipmap.white(), false);
                if (x.altitude > 0.5 * x.atm.scaleHeight()) addPart(PARTS, vertex, limb(buf, x), SurfaceClipmap.white(), false);
            }
            if (PARTS.isEmpty() && SKY_PARTS.isEmpty()) return;
            try (MeshData mesh = buf.buildOrThrow()) {
                buffer = RenderSystem.getDevice().createBuffer(() -> "Galactica near planet", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void addPart(List<Part> into, int[] vertex, int quads, DynamicTexture texture, boolean wrapU) {
        if (quads > 0 && texture != null) into.add(new Part(vertex[0], quads, texture, wrapU));
        vertex[0] += quads * 4;
    }

    /** Sky dome from inside the atmosphere: radiance from {@link AtmosphereGradient#sky}, added over space. */
    private static int dome(BufferBuilder buf, Ctx x) {
        float[][] q = new float[4][5];
        int[] c = new int[4];
        double[] d = new double[3], rgb = new double[3];
        double muSun = dot(x.up, x.toStar);
        int n = 0;
        for (int j = 0; j < DOME_LAT; j++) {
            for (int i = 0; i < DOME_LON; i++) {
                for (int k = 0; k < 4; k++) {
                    int jj = j + (k >= 2 ? 1 : 0), ii = i + (k == 1 || k == 2 ? 1 : 0);
                    double el = Math.PI / 2 - Math.PI * jj / DOME_LAT, az = 2 * Math.PI * ii / DOME_LON;
                    // Minecraft-axis direction → galactic.
                    double[] g = Frames.mcToGalactic(Math.cos(el) * Math.cos(az), Math.sin(el), Math.cos(el) * Math.sin(az));
                    d[0] = g[0];
                    d[1] = g[1];
                    d[2] = g[2];
                    // Unscaled by the star: the surface sky uses the same calibrated colours.
                    x.atm.sky(dot(d, x.up), dot(d, x.toStar), muSun, x.altitude, rgb);
                    set(q[k], d, 0.5, 0.5, R_SHELL + 0.5f);
                    c[k] = additive(rgb);
                }
                emitInward(buf, q, c);
                n++;
            }
        }
        return n;
    }

    /**
     * The visible cap around the sub-camera point. mode 0: ground from the globe (lit, path
     * transmittance); 1: clouds (lit white, alpha from the texture); 2: airlight veil (untextured);
     * 3: ground from a sun-lit image (the texels carry the light).
     */
    private static int cap(BufferBuilder buf, Ctx x, double horizon, int mode) {
        double[] t1 = perpendicular(x.up), t2 = cross(x.up, t1);
        float[][] q = new float[4][5];
        int[] c = new int[4];
        double[] nrm = new double[3];
        double[] us = new double[4];
        int n = 0;
        for (int ri = 0; ri < CAP_RINGS; ri++) {
            for (int si = 0; si < CAP_SEGMENTS; si++) {
                for (int k = 0; k < 4; k++) {
                    int rr = ri + (k == 1 || k == 2 ? 1 : 0), ss = si + (k >= 2 ? 1 : 0);
                    double th = horizon * Math.pow(rr / (double) CAP_RINGS, 0.8), ph = 2 * Math.PI * ss / CAP_SEGMENTS;
                    double ct = Math.cos(th), st = Math.sin(th), cp = Math.cos(ph), sp = Math.sin(ph);
                    for (int m = 0; m < 3; m++) nrm[m] = ct * x.up[m] + st * (cp * t1[m] + sp * t2[m]);
                    double lat = Math.asin(clamp(dot(nrm, x.pole), -1, 1));
                    double lon = BodyFrameWrap.wrap(Math.atan2(dot(nrm, x.e2), dot(nrm, x.e1)) - x.spin);
                    us[k] = (lon + Math.PI) / (2 * Math.PI);
                    q[k][4] = (float) ((Math.PI / 2 - lat) / Math.PI);
                    c[k] = surfaceVertex(x, nrm, q[k], mode, mode == 1 ? 0.15 : 0);
                }
                // Longitude seam: keep the quad's u contiguous; the sampler repeats in u.
                double lo = Math.min(Math.min(us[0], us[1]), Math.min(us[2], us[3])), hi = Math.max(Math.max(us[0], us[1]), Math.max(us[2], us[3]));
                for (int k = 0; k < 4; k++) q[k][3] = (float) (hi - lo > 0.5 && us[k] < 0.5 ? us[k] + 1 : us[k]);
                emitInward(buf, q, c);
                n++;
            }
        }
        return n;
    }

    /**
     * One clipmap level as a patch of the sphere; quads behind the horizon are skipped. {@code fade}
     * feathers its rim, for a level drawn over something (a coarser level or the globe cap).
     */
    private static int level(BufferBuilder buf, Ctx x, SurfaceClipmap.Level l, boolean fade, boolean lit) {
        float[][] q = new float[4][5];
        int[] c = new int[4];
        double[] nrm = new double[3];
        double span = l.span();
        int n = 0;
        for (int j = 0; j < LEVEL_GRID; j++) {
            for (int i = 0; i < LEVEL_GRID; i++) {
                boolean visible = true;
                for (int k = 0; k < 4 && visible; k++) {
                    int ii = i + (k == 1 || k == 2 ? 1 : 0), jj = j + (k >= 2 ? 1 : 0);
                    double fu = ii / (double) LEVEL_GRID, fv = jj / (double) LEVEL_GRID;
                    double mx = l.centreX() + (fu - 0.5) * span, mz = l.centreZ() + (fv - 0.5) * span;
                    double[] over = x.grid.overPole(mx, mz);
                    if (over != null) {
                        mx = over[0];
                        mz = over[1];
                    }
                    x.normal(x.grid.latitude(mz), x.grid.longitude(mx), nrm);
                    // Front-facing only: the eye must be above this point's horizon plane.
                    double facing = 0;
                    for (int m = 0; m < 3; m++) facing += (x.rel[m] + x.radius * nrm[m]) * nrm[m];
                    if (facing >= -1e-6 * x.d) visible = false;
                    q[k][3] = (float) fu;
                    q[k][4] = (float) fv;
                    int col = surfaceVertex(x, nrm, q[k], lit ? 3 : 0, 0);
                    c[k] = fade ? feather(col, fu, fv) : col;
                }
                if (!visible) continue;
                emitInward(buf, q, c);
                n++;
            }
        }
        return n;
    }

    /** Outer share of a clipmap level over which it fades into the coarser layer beneath. */
    static final double LEVEL_FEATHER = 0.15;

    /**
     * Fades a level's colour alpha to 0 at its rim (patch coordinates fu, fv in 0..1), so a finer
     * level blends into the coarser one under it instead of ending on a hard square edge.
     */
    static int feather(int argb, double fu, double fv) {
        double edge = Math.min(Math.min(fu, 1 - fu), Math.min(fv, 1 - fv)) / LEVEL_FEATHER;
        if (edge >= 1) return argb;
        double s = Math.max(0, edge);
        s = s * s * (3 - 2 * s);
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * s);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Thin glowing shell just outside the disc: grazing airlight fading over a few scale heights. */
    private static int limb(BufferBuilder buf, Ctx x) {
        double[] centre = {x.rel[0] / x.d, x.rel[1] / x.d, x.rel[2] / x.d};
        double[] t1 = perpendicular(centre), t2 = cross(centre, t1);
        double h = x.atm.scaleHeight();
        double[] radii = {x.radius, x.radius + 1.5 * h, x.radius + 6 * h};
        float[][] q = new float[4][5];
        int[] c = new int[4];
        double[] dir = new double[3], rgb = new double[3], tr = new double[3];
        int n = 0;
        for (int band = 0; band < 2; band++) {
            for (int si = 0; si < CAP_SEGMENTS; si++) {
                for (int k = 0; k < 4; k++) {
                    int b2 = band + (k == 1 || k == 2 ? 1 : 0), ss = si + (k >= 2 ? 1 : 0);
                    double a = Math.asin(Math.min(1, radii[b2] / x.d));
                    double ph = 2 * Math.PI * ss / CAP_SEGMENTS;
                    for (int m = 0; m < 3; m++) dir[m] = Math.cos(a) * centre[m] + Math.sin(a) * (Math.cos(ph) * t1[m] + Math.sin(ph) * t2[m]);
                    // Tangent point of this ray on the shell, its local up and sun elevation.
                    double[] tp = x.tp;
                    double along = dot(x.rel, dir);
                    for (int m = 0; m < 3; m++) tp[m] = dir[m] * along - x.rel[m];
                    double tn = Math.sqrt(dot(tp, tp));
                    for (int m = 0; m < 3; m++) tp[m] /= Math.max(1e-9, tn);
                    x.atm.lookDown(0.0, dot(dir, x.toStar), dot(tp, x.toStar), 1e12, rgb, tr);
                    double fall = Math.exp(-(radii[b2] - x.radius) / h);
                    for (int m = 0; m < 3; m++) rgb[m] *= fall;
                    scale(rgb, x);
                    set(q[k], dir, 0.5, 0.5, R_SHELL);
                    c[k] = additive(rgb);
                }
                emitInward(buf, q, c);
                n++;
            }
        }
        return n;
    }

    /**
     * Vertex at surface normal {@code nrm} (galactic): writes its shell position into q[0..2] and
     * returns the colour for the layer mode (0 ground, 1 clouds, 2 airlight).
     */
    private static int surfaceVertex(Ctx x, double[] nrm, float[] q, int mode, double cloudLift) {
        double[] p = x.p, v = x.v;
        double lift = x.radius * (1 + cloudLift * x.atm.scaleHeight() / x.radius);
        for (int m = 0; m < 3; m++) p[m] = x.rel[m] + lift * nrm[m];
        double len = Math.sqrt(dot(p, p));
        for (int m = 0; m < 3; m++) v[m] = p[m] / len;
        set(q, v, q[3], q[4], R_SHELL - (mode == 3 ? 0 : mode) * 0.2f);
        if (mode == 3) {
            // Sun-lit image: the texel holds albedo × reflectance × exposure; add the star's colour, and
            // on a thin-air body that still counts as airless here (Mars, 6 mbar with dust of optical
            // depth ~0.5) the sunlight's path through that air, as mode 0 applies: it dims and reddens
            // the ground towards the terminator. Taken at the smooth sphere's sun angle (the texel's
            // relief only tilts the surface under the same air column), floored at a grazing sun so
            // rims lit just past the terminator are not cut to black by the vertex interpolation.
            double tr = x.starR * x.brightness, tg = x.starG * x.brightness, tb = x.starB * x.brightness;
            if (!x.atm.vacuum()) {
                double mu0 = Math.max(0.02, dot(nrm, x.toStar));
                x.atm.sunTransmittance(mu0, x.sunT);
                tr *= x.sunT[0];
                tg *= x.sunT[1];
                tb *= x.sunT[2];
            }
            return argb(enc(tr), enc(tg), enc(tb), 1);
        }
        double mu0 = dot(nrm, x.toStar), mu = Math.max(1e-3, -dot(v, nrm)), cosScatter = dot(v, x.toStar);
        if (mode == 2) {
            // The view path as one over-blend: alpha 1 − T̄, colour inscatter/(1 − T̄) (Koschmieder's form).
            x.atm.lookDown(mu, cosScatter, mu0, x.altitude, x.in, x.tr);
            scale(x.in, x);
            double a = 1 - (x.tr[0] + x.tr[1] + x.tr[2]) / 3;
            if (a < 1e-4) return 0;
            return argb(enc(x.in[0] / a), enc(x.in[1] / a), enc(x.in[2] / a), a);
        }
        double direct;
        if (mu0 <= 0) direct = 0;
        else if (x.airless) direct = 0.65 * 2 * mu0 / (mu0 + mu) + 0.35 * mu0; // Lommel–Seeliger/Lambert, as the imager
        else direct = mu0;
        x.atm.sunTransmittance(mu0, x.sunT);
        if (x.atm.vacuum()) x.sunT[0] = x.sunT[1] = x.sunT[2] = mu0 > 0 ? 1 : 0;
        double k = x.brightness * (mode == 1 ? PlanetImager.EXPOSURE * 0.78 : 1);
        // Unattenuated by the view path: the veil over it applies the transmittance once.
        double r = (direct * x.sunT[0] + 0.004) * x.starR * k;
        double g = (direct * x.sunT[1] + 0.004) * x.starG * k;
        double b = (direct * x.sunT[2] + 0.004) * x.starB * k;
        return argb(enc(r), enc(g), enc(b), 1);
    }

    // ---------------------------------------------------------------- helpers

    private static void scale(double[] rgb, Ctx x) {
        rgb[0] *= x.starR * x.brightness;
        rgb[1] *= x.starG * x.brightness;
        rgb[2] *= x.starB * x.brightness;
    }

    /** Galactic direction to a shell vertex in Minecraft axes. */
    private static void set(float[] q, double[] dirGalactic, double u, double v, float radius) {
        // Frames.galacticToMc without the allocation: (gx, gy, gz) → (gx, gz, −gy).
        double mx = dirGalactic[0], my = dirGalactic[2], mz = -dirGalactic[1];
        double n = Math.sqrt(mx * mx + my * my + mz * mz);
        q[0] = (float) (mx / n * radius);
        q[1] = (float) (my / n * radius);
        q[2] = (float) (mz / n * radius);
        q[3] = (float) u;
        q[4] = (float) v;
    }

    /** Linear light added over what is behind (a = brightest display channel). */
    private static int additive(double[] linear) {
        double r = enc(linear[0]), g = enc(linear[1]), b = enc(linear[2]);
        double a = Math.min(1, Math.max(r, Math.max(g, b)));
        if (a < 1e-4) return 0;
        return argb(r / a, g / a, b / a, a);
    }

    private static double enc(double linear) {
        return AtmosphereGradient.encode(linear);
    }

    private static int argb(double r, double g, double b, double a) {
        return (int) Math.round(clamp(a, 0, 1) * 255) << 24 | (int) Math.round(clamp(r, 0, 1) * 255) << 16
                | (int) Math.round(clamp(g, 0, 1) * 255) << 8 | (int) Math.round(clamp(b, 0, 1) * 255);
    }

    private static double[] perpendicular(double[] n) {
        double[] ref = Math.abs(n[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0};
        double[] t = cross(ref, n);
        double l = Math.sqrt(dot(t, t));
        return new double[] {t[0] / l, t[1] / l, t[2] / l};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Longitude wrap into [−π, π). */
    private static final class BodyFrameWrap {
        static double wrap(double a) {
            double r = (a + Math.PI) % (2 * Math.PI);
            if (r < 0) r += 2 * Math.PI;
            return r - Math.PI;
        }
    }

    /** END_SKY culls back faces and the camera sits at every shell's centre: wind each quad inward. */
    private static void emitInward(BufferBuilder b, float[][] v, int[] argb) {
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
