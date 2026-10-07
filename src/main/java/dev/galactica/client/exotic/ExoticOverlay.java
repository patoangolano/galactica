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
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.client.sky.SpaceSkyRenderer;
import dev.galactica.exotic.ExoticCatalog;
import dev.galactica.exotic.ExoticImager;
import dev.galactica.exotic.ExoticObject;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws black holes, pulsar beams and the S-stars over the space sky, through the vanilla
 * {@link RenderPipelines#END_SKY} pipeline with one {@link DynamicTexture} per sprite painted by
 * {@link ExoticImager}: the same contract as the planet sprites, so shader packs see ordinary
 * textured sky.
 *
 * <p>What is drawn:
 * <ul>
 *   <li>Black holes of the {@link Neighbourhood} within {@link #LOCAL_RADIUS_PC}, and Sgr A* /
 *       Cygnus X-1 within {@link #NAMED_RADIUS_PC}. Up to two sprites each: the lens halo (sky
 *       bent around the Einstein ring, opaque only where the bending exceeds a pixel and within
 *       3 θ<sub>E</sub>, transparent elsewhere so the star layer shows through) and the core
 *       (shadow, photon ring, disk or RIAF) at true angular size; an unresolved accreting hole is a
 *       coloured point. The star layer skips black holes, so nothing is drawn twice.</li>
 *   <li>Neutron stars: only the sweeping emission cones and the flash when a cone crosses the
 *       observer. The steady point is already in the star layer (or the near-body disc for the
 *       system's own star), so the sprite paints no steady core.</li>
 *   <li>White dwarfs: nothing. The star layer and the near-body disc already draw them.</li>
 *   <li>The S-stars (S2, S29, S55) as PSF points while within {@link #NAMED_RADIUS_PC} of Sgr A*,
 *       positioned by {@link ExoticCatalog#sStars} at the game clock. They are drawn here so they
 *       never enter the {@link Neighbourhood} and can never become a system star.</li>
 * </ul>
 *
 * <p>Cost: textures are sized to their on-screen footprint (32–256 px) and painted on a background
 * thread; {@link #prepare} only uploads finished images, under a budget of
 * {@value #UPLOAD_BUDGET_NANOS} ns per frame (the first paint of a sprite counts too). A sprite keeps
 * showing its previous image until the new one is uploaded.
 *
 * <p>Use from {@code SpaceSkyRenderer.render()}: {@link #prepare} before the render pass opens,
 * {@link #draw} inside it right after the "Stars" layer and before the near bodies, so this
 * system's star and planets cover sprites behind them. {@link #initClient} frees everything as soon
 * as the player is no longer in the space dimension.
 */
public final class ExoticOverlay {
    private ExoticOverlay() {}

    public static final double LOCAL_RADIUS_PC = 1.0;
    public static final double NAMED_RADIUS_PC = 50.0;
    /** Between the star shell (95) and the near-body shell (90). Draw order, not depth, sorts the sky. */
    private static final float SHELL = 92f;
    private static final double PX_PER_RAD = 885.0;
    /** A core is painted once it spans this many pixels; below, it is a point. */
    private static final double RESOLVE_PX = 4.0;
    /** The lens halo is drawn once its lensed region spans this many pixels. */
    private static final double HALO_PX = 3.0;
    /** Largest sprite half-angles; a larger object is cropped, keeping quads well inside the far plane. */
    private static final double HALO_MAX = 0.35, CORE_MAX = 0.6;
    private static final double POINT_HALF = 0.0035;
    /** Same constants as the star layer's PSF (SpaceSkyRenderer.psfSize), so S-stars match other stars. */
    private static final double STAR_SPRITE = 0.0016, MAX_SWELL = 6.0;
    private static final long UPLOAD_BUDGET_NANOS = 500_000L;
    private static final double DIR_EPS = Math.cos(Math.toRadians(0.3));
    private static final int EVICT_FRAMES = 200;
    /** Lens halos also refresh this often, so they follow re-bakes of the Milky Way. */
    private static final long HALO_REFRESH_NANOS = 3_000_000_000L;
    /** Pulsars spinning faster than this are drawn time-averaged and flash at their duty cycle. */
    private static final double STROBE_PERIOD_S = 0.1;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica exotic imager");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private enum Layer { HALO, CORE }

    private record Sprite(float dx, float dy, float dz, double halfAngle, GpuTextureView view, int argb) {}

    /** A finished background paint: ARGB already flipped into texture order. */
    private record Painted(int[] argb, int size, double[] forward, double halfAngle, long requestedAt, int generation) {}

    private static final class Entry {
        DynamicTexture texture;
        int size;
        /** State of the image currently uploaded. */
        double[] forward;
        double halfAngle;
        long paintedAt;
        int lastSeen;
        /** Paint in flight, and the state it was requested for. */
        CompletableFuture<Painted> job;
        double[] jobForward;
        double jobHalf;
        int jobSize;
    }

    private static final Map<Long, ExoticObject> OBJECTS = new HashMap<>();
    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static final List<Sprite> SPRITES = new ArrayList<>();
    private static GpuBuffer vertices;
    private static final dev.galactica.client.vfx.StreamingVertexBuffer VERTICES = new dev.galactica.client.vfx.StreamingVertexBuffer("Galactica exotic objects");
    private static int frame, generation;
    private static long uploadDeadline;

    /**
     * Registers the tick hook that frees every texture once the player leaves the space dimension,
     * and {@link NebulaCloseUp}'s equivalent.
     */
    public static void initClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!ENTRIES.isEmpty() && !SpaceSkyRenderer.active()) clear();
        });
        NebulaCloseUp.initClient();
    }

    /**
     * Before the render pass: chooses what to draw, uploads finished paints within the frame budget,
     * queues new paints on the worker and builds the quads.
     *
     * @param eye          galactic position of the camera ({@code SkyManager.get().viewer()})
     * @param timeSeconds  game clock in seconds since J2000 ({@code NavState.timeSeconds()})
     */
    public static void prepare(GalacticPos eye, double timeSeconds) {
        frame++;
        uploadDeadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        SPRITES.clear();
        vertices = null; // a view of VERTICES: rewritten in place by buildVertices
        if (eye == null) {
            evict();
            return;
        }
        Neighbourhood nb = SkyManager.get().neighbourhood();
        Map<Long, Star> wanted = new LinkedHashMap<>();
        double ex = eye.xPc(), ey = eye.yPc(), ez = eye.zPc();
        for (Star s : remnants(nb.nearbyStars(eye))) {
            if (s.distanceTo(ex, ey, ez) <= LOCAL_RADIUS_PC) wanted.put(s.id(), s);
        }
        for (Star s : ExoticCatalog.namedRemnants()) {
            if (s.distanceTo(ex, ey, ez) <= NAMED_RADIUS_PC) wanted.put(s.id(), s);
        }

        List<Placed> placed = new ArrayList<>();
        for (Star s : wanted.values()) {
            ExoticObject o = OBJECTS.computeIfAbsent(s.id(), id -> ExoticObject.of(s));
            if (o == null) continue;
            double[] f = direction(Neighbourhood.starPos(s), eye);
            if (f == null) continue;
            placed.add(new Placed(o, new double[] {f[0], f[1], f[2]}, f[3]));
        }
        placed.sort((a, b) -> Double.compare(b.distance(), a.distance()));
        GalaxySampler sky = null;
        for (Placed p : placed) {
            if (p.object().kind() == StarKind.BLACK_HOLE && sky == null) sky = new GalaxySampler();
            queue(p, timeSeconds, sky);
        }
        if (StarCatalog.SGR_A_STAR.distanceTo(ex, ey, ez) <= NAMED_RADIUS_PC) addSStars(eye, timeSeconds);
        evict();
        buildVertices();
    }

    /**
     * Inside the pass, right after the "Stars" layer and before the near bodies: one END_SKY draw
     * per sprite.
     *
     * @param pass      the open sky render pass
     * @param quads     {@code RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)}
     * @param modelView {@code RenderSystem.getModelViewMatrixCopy()} as used for the other layers
     */
    public static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            drawTimed(pass, quads, modelView);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.EXOTIC, perf);
        }
    }

    private static void drawTimed(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView) {
        if (vertices == null || SPRITES.isEmpty()) return;
        GpuSampler clamp = SkyTextures.linearClamp();
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1));
        for (int i = 0; i < SPRITES.size(); i++) {
            pass.pushDebugGroup(() -> "Galactica exotic");
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("Sampler0", SPRITES.get(i).view(), clamp);
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(quads.getBuffer(6), quads.type());
            pass.drawIndexed(6, 1, 0, i * 4, 0);
            pass.popDebugGroup();
        }
    }

    /** Frees every texture and drops paints in flight. Render thread only. */
    public static void clear() {
        generation++;
        for (Entry e : ENTRIES.values()) {
            if (e.texture != null) e.texture.close();
            if (e.job != null) e.job.cancel(false);
        }
        ENTRIES.clear();
        OBJECTS.clear();
        SPRITES.clear();
        vertices = null;
        VERTICES.close();
    }

    // ------------------------------------------------------------------ selection

    private record Placed(ExoticObject object, double[] forward, double distance) {}

    /** The drawn kinds among the neighbourhood's stars, refiltered only when that (cached) list changes. */
    private static List<Star> remnantSource, remnantList = List.of();

    /**
     * Scanning every neighbourhood star each frame (thousands in the bulge) was most of the overlay's cost; remnants
     * are a few in a thousand, so the scan runs once per sector change instead.
     */
    private static List<Star> remnants(List<Star> all) {
        if (all != remnantSource) {
            List<Star> out = new ArrayList<>();
            for (Star s : all) if (drawn(s.kind())) out.add(s);
            remnantList = out;
            remnantSource = all;
        }
        return remnantList;
    }

    private static boolean drawn(StarKind k) {
        return k == StarKind.BLACK_HOLE || k == StarKind.NEUTRON_STAR;
    }

    /** Unit Minecraft-axes direction from eye to {@code at}, plus the distance in metres as [3]. */
    private static double[] direction(GalacticPos at, GalacticPos eye) {
        double[] g = at.metresFrom(eye);
        double[] f = Frames.galacticToMc(g[0], g[1], g[2]);
        double d = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        if (!(d > 0)) return null;
        return new double[] {f[0] / d, f[1] / d, f[2] / d, d};
    }

    private static void queue(Placed p, double t, GalaxySampler sky) {
        ExoticObject o = p.object();
        double d = p.distance();
        double[] f = p.forward();
        float fx = (float) f[0], fy = (float) f[1], fz = (float) f[2];
        switch (o.kind()) {
            case BLACK_HOLE -> {
                double rsOverD = o.rsMetres() / d;
                double coreHalf = Math.atan(ExoticImager.coreExtentRs(o) * rsOverD);
                double lens = ExoticImager.lensExtent(rsOverD, PX_PER_RAD);
                if (lens * PX_PER_RAD >= HALO_PX && lens > 1.5 * coreHalf) {
                    double half = Math.min(HALO_MAX, 1.05 * lens);
                    Sprite s = paint(o, Layer.HALO, f, d, half, lens, t, footprint(half, 256), sky);
                    if (s != null) SPRITES.add(s);
                }
                if (coreHalf * PX_PER_RAD >= RESOLVE_PX) {
                    double half = Math.min(CORE_MAX, coreHalf);
                    Sprite s = paint(o, Layer.CORE, f, d, half, Double.POSITIVE_INFINITY, t, footprint(half, 256), sky);
                    if (s != null) SPRITES.add(s);
                } else if (o.accretion() != ExoticObject.Accretion.NONE) {
                    // Unresolved accretion flow: a point in the disk's colour, fading as it shrinks.
                    float[] rgb = new float[3];
                    ExoticImager.unresolvedColour(o, rgb);
                    double frac = coreHalf * PX_PER_RAD / RESOLVE_PX;
                    float alpha = (float) Math.min(1, (o.accretion() == ExoticObject.Accretion.RIAF ? 0.2 : 0.5) + 0.5 * frac);
                    SPRITES.add(new Sprite(fx, fy, fz, POINT_HALF, SkyTextures.psf().getTextureView(), argb(rgb, alpha)));
                }
            }
            case NEUTRON_STAR -> {
                double pc = d / Units.PARSEC;
                double half = Math.max(0.004, Math.min(0.03, 0.03 * 0.05 / Math.max(1e-6, pc)));
                Sprite s = paint(o, Layer.CORE, f, d, half, Double.POSITIVE_INFINITY, t, footprint(half, 128), null);
                if (s != null) SPRITES.add(s);
            }
            default -> { }
        }
    }

    /** S2, S29, S55 as star-layer-style PSF points (magnitude, extinction and size as the star layer). */
    private static void addSStars(GalacticPos eye, double t) {
        float[] rgb = new float[3];
        double ex = eye.xPc(), ey = eye.yPc(), ez = eye.zPc();
        for (Star s : ExoticCatalog.sStars(t)) {
            double dx = s.x() - ex, dy = s.y() - ey, dz = s.z() - ez;
            double dPc = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dPc < 1e-9) continue;
            double av = 0, step = dPc / 4;
            for (int i = 0; i < 4; i++) {
                double k = (i + 0.5) * step / dPc;
                av += GalaxyModel.dustExtinctionPerPc(ex + dx * k, ey + dy * k, ez + dz * k) * step;
            }
            double mag = s.absoluteMagnitude() + 5 * Math.log10(dPc / 10.0) + av;
            float alpha = (float) Math.min(1.0, 0.35 * Math.pow(10, -0.4 * (mag - 6.5)));
            if (alpha < 0.02f) continue;
            double size = STAR_SPRITE * Math.min(MAX_SWELL, Math.max(1.0, Math.pow(10, -0.4 * (mag - 4.0) * 0.35)));
            Blackbody.linearRgb(s.temperature(), rgb);
            // Galactic (x, y, z) → Minecraft (x, z, −y), as the star layer.
            SPRITES.add(new Sprite((float) (dx / dPc), (float) (dz / dPc), (float) (-dy / dPc), size,
                    SkyTextures.psf().getTextureView(), argb(rgb, alpha)));
        }
    }

    // ------------------------------------------------------------------ textures

    /** Texture edge for a sprite of half-angle {@code half}: its on-screen size, power of two, 32…max. */
    static int footprint(double half, int max) {
        int px = (int) Math.ceil(2 * half * PX_PER_RAD);
        int size = Integer.highestOneBit(Math.max(1, px - 1)) << 1;
        return Math.max(32, Math.min(max, size));
    }

    /**
     * Returns the sprite to draw this frame (null until the first paint is uploaded), uploading a
     * finished paint if the frame budget allows and queuing a new one when the image is stale.
     */
    private static Sprite paint(ExoticObject o, Layer layer, double[] f, double d, double half, double lensOuter,
                                double t, int size, GalaxySampler sky) {
        long key = o.star().id() * 4 + layer.ordinal();
        Entry e = ENTRIES.computeIfAbsent(key, k -> new Entry());
        e.lastSeen = frame;
        long now = System.nanoTime();

        // 1. Upload a finished paint (the first one included) while the budget lasts.
        if (e.job != null && e.job.isDone()) {
            Painted p = e.job.isCompletedExceptionally() || e.job.isCancelled() ? null : e.job.getNow(null);
            if (p == null || p.generation() != generation) {
                e.job = null;
            } else if (now < uploadDeadline) {
                upload(key, e, p);
                e.job = null;
            }
        }

        // 2. Queue a new paint when the image no longer matches what should be seen.
        boolean pulsing = o.kind() == StarKind.NEUTRON_STAR && o.pulsar().periodS() >= STROBE_PERIOD_S;
        double[] ref = e.job != null ? e.jobForward : e.forward;
        double refHalf = e.job != null ? e.jobHalf : e.halfAngle;
        int refSize = e.job != null ? e.jobSize : e.size;
        boolean stale = ref == null || pulsing || refSize != size
                || dot(ref, f) < DIR_EPS
                || Math.abs(refHalf / half - 1) > 0.03
                || layer == Layer.HALO && now - e.paintedAt > HALO_REFRESH_NANOS;
        if (stale && e.job == null) {
            ExoticImager.View v = view(o, f, d, half, lensOuter, t);
            int gen = generation;
            double[] fwd = f.clone();
            e.jobForward = fwd;
            e.jobHalf = half;
            e.jobSize = size;
            e.job = CompletableFuture.supplyAsync(() -> render(o, v, size, sky, fwd, half, now, gen), WORKER);
        }

        if (e.texture == null) return null;
        return new Sprite((float) f[0], (float) f[1], (float) f[2], e.halfAngle, e.texture.getTextureView(), 0xFFFFFFFF);
    }

    /** Worker thread: paint and flip into texture order (the imager's column 0 is the −right edge). */
    private static Painted render(ExoticObject o, ExoticImager.View v, int size, GalaxySampler sky,
                                  double[] fwd, double half, long requestedAt, int gen) {
        int[] img = new int[size * size];
        ExoticImager.render(o, v, size, img, sky);
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            int row = y * size;
            for (int x = 0; x < size; x++) out[row + size - 1 - x] = img[row + x];
        }
        return new Painted(out, size, fwd, half, requestedAt, gen);
    }

    /** Render thread, before the pass: (re)creates the texture at the painted size and uploads. */
    private static void upload(long key, Entry e, Painted p) {
        int size = p.size();
        if (e.texture == null || e.size != size) {
            if (e.texture != null) e.texture.close();
            String name = "galactica:exotic/" + Long.toHexString(key);
            e.texture = new DynamicTexture(() -> name, new NativeImage(size, size, true));
            e.size = size;
        }
        NativeImage img = e.texture.getPixels();
        int[] px = p.argb();
        for (int y = 0; y < size; y++) {
            int row = y * size;
            for (int x = 0; x < size; x++) img.setPixel(x, y, px[row + x]);
        }
        e.texture.upload();
        e.forward = p.forward();
        e.halfAngle = p.halfAngle();
        e.paintedAt = p.requestedAt();
    }

    /** Same basis the sky renderer uses for planets, so the painted image lands on the quad. */
    private static ExoticImager.View view(ExoticObject o, double[] f, double d, double half, double lensOuter, double t) {
        Vector3f[] basis = quadBasis((float) f[0], (float) f[1], (float) f[2]);
        ExoticImager.View v = new ExoticImager.View();
        v.forward = f.clone();
        v.up = new double[] {basis[1].x, basis[1].y, basis[1].z};
        v.right = cross(f, v.up);
        double[] ax = o.axis();
        v.axis = Frames.galacticToMc(ax[0], ax[1], ax[2]);
        v.distanceMetres = d;
        v.halfAngle = half;
        v.pxPerRad = PX_PER_RAD;
        v.timeSeconds = t;
        v.timeAveraged = o.pulsar() != null && o.pulsar().periodS() < STROBE_PERIOD_S;
        v.lensOuter = lensOuter;
        v.steadyCore = false; // neutron stars: the star layer / near-body disc draws the steady point
        return v;
    }

    private static void evict() {
        Iterator<Map.Entry<Long, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (frame - e.lastSeen > EVICT_FRAMES) {
                if (e.texture != null) e.texture.close();
                if (e.job != null) e.job.cancel(false);
                it.remove();
            }
        }
        if (OBJECTS.size() > 4096) OBJECTS.clear();
    }

    // ------------------------------------------------------------------ geometry

    private static void buildVertices() {
        if (SPRITES.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(SPRITES.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (Sprite s : SPRITES) spriteQuad(b, s.dx(), s.dy(), s.dz(), SHELL, s.halfAngle(), s.argb());
            try (MeshData mesh = b.buildOrThrow()) {
                vertices = VERTICES.upload(mesh.vertexBuffer());
            }
        }
    }

    private static Vector3f[] quadBasis(float dx, float dy, float dz) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f t1 = new Vector3f(dir).cross(up).normalize();
        Vector3f t2 = new Vector3f(dir).cross(t1).normalize();
        return new Vector3f[] {t1, t2};
    }

    private static void spriteQuad(BufferBuilder b, float dx, float dy, float dz, float radius, double halfAngle, int argb) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f[] basis = quadBasis(dx, dy, dz);
        Vector3f t1 = basis[0], t2 = basis[1];
        float h = (float) (Math.tan(Math.min(CORE_MAX, halfAngle)) * radius);
        Vector3f c = new Vector3f(dir).mul(radius);
        float[][] v = new float[4][];
        float[][] corners = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
        for (int k = 0; k < 4; k++) {
            float sx = corners[k][0] * h, sy = corners[k][1] * h;
            v[k] = new float[] {c.x + t1.x * sx + t2.x * sy, c.y + t1.y * sx + t2.y * sy, c.z + t1.z * sx + t2.z * sy, corners[k][2], corners[k][3]};
        }
        // END_SKY culls back faces and the camera sits at the shell centre: wind every quad inward.
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

    private static int argb(float[] rgb, float alpha) {
        return (Math.round(Math.min(1, alpha) * 255) << 24) | (GalaxySampler.encode(rgb[0]) << 16)
                | (GalaxySampler.encode(rgb[1]) << 8) | GalaxySampler.encode(rgb[2]);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
