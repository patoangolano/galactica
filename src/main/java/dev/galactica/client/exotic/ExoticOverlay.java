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
import dev.galactica.exotic.ExoticCatalog;
import dev.galactica.exotic.ExoticImager;
import dev.galactica.exotic.ExoticObject;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
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

/**
 * Draws black holes, neutron stars and white dwarfs over the space sky, through the vanilla
 * {@link RenderPipelines#END_SKY} pipeline with one {@link DynamicTexture} per sprite painted by
 * {@link ExoticImager} — the same contract as the planet sprites, so shader packs see ordinary
 * textured sky.
 *
 * <p>Which objects: remnants of the {@link Neighbourhood} within {@link #LOCAL_RADIUS_PC}, plus
 * Sgr A* and Cygnus X-1 within {@link #NAMED_RADIUS_PC}. A black hole gets up to two sprites: the
 * lens halo (background bent around the Einstein ring, only when that ring spans a few pixels) and
 * the core (shadow, photon ring, disk or RIAF) at its true angular size, falling back to a point
 * when unresolved. Pulsars and white dwarfs are points; a pulsar adds its sweeping emission cones,
 * drawn at a fixed visualisation scale since the real light cylinder is far below a pixel.
 *
 * <p>Two-phase use from {@code SpaceSkyRenderer.render()}: {@link #prepare} before the render pass
 * opens (paints and uploads textures, builds the vertex buffer), {@link #draw} inside it after the
 * resolved planets.
 */
public final class ExoticOverlay {
    private ExoticOverlay() {}

    public static final double LOCAL_RADIUS_PC = 1.0;
    public static final double NAMED_RADIUS_PC = 50.0;
    /** Inside the planet shell (89) so exotic sprites sort in front of everything else in the sky. */
    private static final float SHELL = 88f;
    private static final double PX_PER_RAD = 885.0;
    /** A core is painted once it spans this many pixels; below, it is a point. */
    private static final double RESOLVE_PX = 4.0;
    /** The lens halo is drawn once the Einstein ring region spans this many pixels. */
    private static final double HALO_PX = 3.0;
    private static final double POINT_HALF = 0.0035;
    private static final int REPAINTS_PER_FRAME = 2;
    private static final double DIR_EPS = Math.cos(Math.toRadians(0.3));
    private static final int EVICT_FRAMES = 200;
    /** Lens halos also refresh this often, so they follow re-bakes of the Milky Way. */
    private static final long HALO_REFRESH_NANOS = 3_000_000_000L;
    /** Pulsars spinning faster than this are drawn time-averaged and flash at their duty cycle. */
    private static final double STROBE_PERIOD_S = 0.1;

    private enum Layer { HALO, CORE, POINT }

    private record Sprite(float dx, float dy, float dz, double halfAngle, GpuTextureView view, int argb) {}

    private static final class Entry {
        DynamicTexture texture;
        int size;
        double[] forward;
        double halfAngle;
        long paintedAt;
        int lastSeen;
        int[] scratch;
    }

    private static final Map<Long, ExoticObject> OBJECTS = new HashMap<>();
    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static final List<Sprite> SPRITES = new ArrayList<>();
    private static GpuBuffer vertices;
    private static int frame, repaints;

    /**
     * Before the render pass: chooses what to draw, repaints stale textures (at most
     * {@value #REPAINTS_PER_FRAME} per frame plus first paints), uploads them and builds the quads.
     *
     * @param eye          galactic position of the camera ({@code SkyManager.get().viewer()})
     * @param timeSeconds  game clock in seconds since J2000 ({@code NavState.timeSeconds()})
     */
    public static void prepare(GalacticPos eye, double timeSeconds) {
        frame++;
        repaints = 0;
        SPRITES.clear();
        if (vertices != null) {
            vertices.close();
            vertices = null;
        }
        if (eye == null) {
            evict();
            return;
        }
        Neighbourhood nb = SkyManager.get().neighbourhood();
        Map<Long, Star> wanted = new LinkedHashMap<>();
        double ex = eye.xPc(), ey = eye.yPc(), ez = eye.zPc();
        for (Star s : nb.nearbyStars(eye)) {
            if (s.kind().isRemnant() && s.distanceTo(ex, ey, ez) <= LOCAL_RADIUS_PC) wanted.put(s.id(), s);
        }
        for (Star s : ExoticCatalog.namedRemnants()) {
            if (s.distanceTo(ex, ey, ez) <= NAMED_RADIUS_PC) wanted.put(s.id(), s);
        }
        // SpaceSkyRenderer already draws the disc of the system's own (non-black-hole) star.
        Star own = nb.nearestStar(eye);
        if (own != null && own.kind() == StarKind.WHITE_DWARF
                && Neighbourhood.starPos(own).distanceMetres(eye) <= SkyManager.NEAR_LIMIT_PC * Units.PARSEC) {
            wanted.remove(own.id());
        }

        List<Placed> placed = new ArrayList<>();
        for (Star s : wanted.values()) {
            ExoticObject o = OBJECTS.computeIfAbsent(s.id(), id -> ExoticObject.of(s));
            if (o == null) continue;
            GalacticPos at = Neighbourhood.starPos(s);
            double[] g = at.metresFrom(eye);
            double[] f = Frames.galacticToMc(g[0], g[1], g[2]);
            double d = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
            if (d <= 0) continue;
            f[0] /= d;
            f[1] /= d;
            f[2] /= d;
            placed.add(new Placed(o, f, d));
        }
        placed.sort((a, b) -> Double.compare(b.distance(), a.distance()));
        GalaxySampler sky = null;
        for (Placed p : placed) {
            if (p.object().kind() == StarKind.BLACK_HOLE && sky == null) sky = new GalaxySampler();
            queue(p, timeSeconds, sky);
        }
        evict();
        buildVertices();
    }

    /**
     * Inside the pass, after the near bodies and resolved planets: one END_SKY draw per sprite.
     *
     * @param pass      the open sky render pass
     * @param quads     {@code RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)}
     * @param modelView {@code RenderSystem.getModelViewMatrixCopy()} as used for the other layers
     */
    public static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView) {
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

    /** Frees every texture, e.g. when leaving the world. */
    public static void clear() {
        for (Entry e : ENTRIES.values()) if (e.texture != null) e.texture.close();
        ENTRIES.clear();
        OBJECTS.clear();
        SPRITES.clear();
        if (vertices != null) {
            vertices.close();
            vertices = null;
        }
    }

    // ------------------------------------------------------------------ selection

    private record Placed(ExoticObject object, double[] forward, double distance) {}

    private static void queue(Placed p, double t, GalaxySampler sky) {
        ExoticObject o = p.object();
        double d = p.distance();
        double[] f = p.forward();
        float fx = (float) f[0], fy = (float) f[1], fz = (float) f[2];
        switch (o.kind()) {
            case BLACK_HOLE -> {
                double rsOverD = o.rsMetres() / d;
                double coreHalf = Math.atan(ExoticImager.coreExtentRs(o) * rsOverD);
                double thetaE = ExoticImager.einsteinAngle(o.rsMetres(), d);
                double haloHalf = Math.min(1.2, Math.max(3 * thetaE, 8 * rsOverD * PX_PER_RAD));
                if (haloHalf * PX_PER_RAD >= HALO_PX && haloHalf > 1.5 * coreHalf) {
                    GpuTextureView tex = paint(o, Layer.HALO, f, d, haloHalf, t, 256, sky);
                    SPRITES.add(new Sprite(fx, fy, fz, haloHalf, tex, 0xFFFFFFFF));
                }
                if (coreHalf * PX_PER_RAD >= RESOLVE_PX) {
                    double half = Math.min(1.2, coreHalf);
                    GpuTextureView tex = paint(o, Layer.CORE, f, d, half, t, 256, sky);
                    SPRITES.add(new Sprite(fx, fy, fz, half, tex, 0xFFFFFFFF));
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
                GpuTextureView tex = paint(o, Layer.CORE, f, d, half, t, 128, null);
                SPRITES.add(new Sprite(fx, fy, fz, half, tex, 0xFFFFFFFF));
            }
            default -> {
                Star s = o.star();
                double mag = s.absoluteMagnitude() + 5 * Math.log10(d / (10 * Units.PARSEC));
                float alpha = (float) Math.min(1.0, 0.35 * Math.pow(10, -0.4 * (mag - 6.5)));
                if (alpha < 0.02f) return;
                GpuTextureView tex = paint(o, Layer.POINT, f, d, POINT_HALF, t, 64, null);
                SPRITES.add(new Sprite(fx, fy, fz, POINT_HALF, tex, (Math.round(alpha * 255) << 24) | 0xFFFFFF));
            }
        }
    }

    // ------------------------------------------------------------------ textures

    private static GpuTextureView paint(ExoticObject o, Layer layer, double[] f, double d, double half, double t,
                                        int size, GalaxySampler sky) {
        long key = o.star().id() * 4 + layer.ordinal();
        Entry e = ENTRIES.computeIfAbsent(key, k -> new Entry());
        e.lastSeen = frame;
        boolean fresh = e.texture == null || e.size != size;
        if (fresh) {
            if (e.texture != null) e.texture.close();
            String name = "galactica:exotic/" + Long.toHexString(key);
            e.texture = new DynamicTexture(() -> name, new NativeImage(size, size, true));
            e.size = size;
            e.scratch = new int[size * size];
            e.forward = null;
        }
        boolean pulsing = o.kind() == StarKind.NEUTRON_STAR && o.pulsar().periodS() >= STROBE_PERIOD_S;
        long now = System.nanoTime();
        boolean stale = fresh || e.forward == null || pulsing
                || dot(e.forward, f) < DIR_EPS
                || Math.abs(e.halfAngle / half - 1) > 0.03
                || layer == Layer.HALO && now - e.paintedAt > HALO_REFRESH_NANOS;
        if (layer == Layer.POINT && !fresh) stale = false; // a white dwarf's point image never changes
        if (stale && (fresh || pulsing || repaints < REPAINTS_PER_FRAME)) {
            if (!fresh && !pulsing) repaints++;
            ExoticImager.View v = view(o, f, d, half, t);
            ExoticImager.render(o, v, size, e.scratch, sky);
            NativeImage img = e.texture.getPixels();
            // The imager's column 0 is the −right edge; the quad puts u = 0 on the +right side.
            for (int y = 0; y < size; y++) {
                int row = y * size;
                for (int x = 0; x < size; x++) img.setPixel(size - 1 - x, y, e.scratch[row + x]);
            }
            e.texture.upload();
            e.forward = f.clone();
            e.halfAngle = half;
            e.paintedAt = now;
        }
        return e.texture.getTextureView();
    }

    /** Same basis the sky renderer uses for planets, so the painted image lands on the quad. */
    private static ExoticImager.View view(ExoticObject o, double[] f, double d, double half, double t) {
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
        return v;
    }

    private static void evict() {
        Iterator<Map.Entry<Long, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (frame - e.lastSeen > EVICT_FRAMES) {
                if (e.texture != null) e.texture.close();
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
                vertices = RenderSystem.getDevice().createBuffer(() -> "Galactica exotic objects", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
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
        float h = (float) (Math.tan(Math.min(1.2, halfAngle)) * radius);
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
