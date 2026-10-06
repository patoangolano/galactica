package dev.galactica.client.planet;

import com.mojang.blaze3d.pipeline.RenderTarget;
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
import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.planet.BodyFrame;
import dev.galactica.planet.PlanetAddress;
import dev.galactica.planet.PlanetEntry;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.planet.SkyAlignment;
import dev.galactica.planet.SurfaceAtmosphere;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.planet.SurfaceState;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.PlanetImager;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The real sky over a surface slot, drawn after vanilla's sky (whose sun and moon are suppressed):
 * the system's star as a limb-darkened disc at its true angular size and blackbody colour, the
 * other planets and moons (painted by {@link PlanetImager} when resolved, dots otherwise) and the
 * Milky Way at night. Vanilla keeps the day cycle and lighting, so the sky is rotated by
 * {@link SkyAlignment} to put the star on vanilla's sun; every other object keeps its true angle
 * from the star. Daylight in an atmosphere washes out planets and the galaxy in proportion to the
 * sky's {@link SurfaceAtmosphere.SkyClass#daylightOpacity}; airless skies show them at noon.
 *
 * <p>Same technique as the space sky: vanilla {@code END_SKY} pipeline, our own textures on
 * Sampler0, every texture resolved before the pass opens.
 */
public final class SurfaceSkyOverlay {
    private SurfaceSkyOverlay() {}

    private static final float R_GALAXY = 100f, R_NEAR = 90f;
    private static final double PX_PER_RAD = 885.0, RESOLVE_RAD = 0.0045, DOT_SIZE = 0.0016;

    private record Quad(float dx, float dy, float dz, double size, int argb) {}
    private record Painted(float dx, float dy, float dz, double halfAngle, double distance, DynamicTexture texture, float brightness) {}

    private static GpuBuffer sphere, quads, painted;
    private static int sphereIndices, quadIndices;
    private static final List<Painted> PAINTED = new ArrayList<>();
    private static PlanetAddress cachedAddress;
    private static PlanetAddress.Resolved cached;

    /** True in a surface slot whose body the client knows. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && PlanetSlots.slotOf(mc.level.dimension()) >= 0 && resolved(mc) != null;
    }

    private static PlanetAddress.Resolved resolved(Minecraft mc) {
        SurfaceState st = mc.player.getAttached(PlanetEntry.SURFACE);
        if (st == null) return null;
        if (!st.address().equals(cachedAddress)) {
            cachedAddress = st.address();
            cached = st.address().resolve(SkyManager.get().galaxy());
        }
        return cached;
    }

    /** Call after vanilla's sky pass has closed, with the frame's extracted sky state. */
    public static void render(SkyRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        PlanetAddress.Resolved r = resolved(mc);
        if (r == null) return;
        Body here = r.body();
        Star star = r.star();
        NavState nav = mc.player.getAttached(Galactica.NAV);
        double t = nav != null ? nav.timeSeconds() : 0;

        SkyManager mgr = SkyManager.get();
        mgr.update(mc);
        SkyManager.BakedSky sky = mgr.takeSky();
        if (sky != null) SkyTextures.uploadGalaxy(sky.argb());
        if (sphere == null) buildSphere();

        // Where the camera is in the galaxy: on the body's surface under its map coordinates.
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        SurfaceGrid grid = SurfaceGrid.of(here.radiusMetres());
        double[] up = BodyFrame.direction(star, here, t, grid.latitude(cam.z), grid.longitude(cam.x));
        GalacticPos starPos = Neighbourhood.starPos(star);
        double[] off = BodyFrame.offsetFromStar(star, r.planet(), r.moon(), t);
        GalacticPos bodyPos = starPos.plusMetres(off[0], off[1], off[2]);
        double rr = here.radiusMetres();
        GalacticPos eye = bodyPos.plusMetres(up[0] * rr, up[1] * rr, up[2] * rr);

        double[] toStar = mc(starPos.metresFrom(eye));
        double[] sun = SkyAlignment.vanillaSun(state.sunAngle);
        double[] m = SkyAlignment.matrix(toStar, mc(BodyFrame.pole(star, here)), sun);
        SurfaceAtmosphere.SkyClass skyClass = SurfaceAtmosphere.skyClass(here);
        double day = clamp(sun[1] * 3 + 0.3, 0, 1);
        double washout = day * skyClass.daylightOpacity;
        float rain = state.rainBrightness;

        SurfaceSprites.beginFrame();
        List<Quad> list = new ArrayList<>();
        PAINTED.clear();
        double dStar = starPos.distanceMetres(eye);
        double magStar = star.absoluteMagnitude() + 5 * Math.log10(dStar / (10 * Units.PARSEC));
        float[] rgb = new float[3];
        if (star.kind() != StarKind.BLACK_HOLE) {
            Blackbody.linearRgb(star.temperature(), rgb);
            double ang = Math.asin(Math.min(1, star.radiusMetres() / dStar));
            list.add(quad(sun, Math.min(1.2, Math.max(2 * ang, 4 * DOT_SIZE)), argb(rgb[0], rgb[1], rgb[2], rain)));
        }
        for (Body planet : SystemGenerator.generate(star)) {
            addBody(list, star, starPos, eye, here, planet, null, t, m, magStar, washout, rain);
            for (Body moon : planet.moons()) addBody(list, star, starPos, eye, here, planet, moon, t, m, magStar, washout, rain);
        }
        rebuild(list);
        SurfaceSprites.evict();

        GpuTextureView galaxyView = SkyTextures.galaxy().getTextureView();
        GpuTextureView discView = SkyTextures.disc().getTextureView();
        GpuSampler wrapU = SkyTextures.linearWrapU(), clamp = SkyTextures.linearClamp();
        RenderTarget rt = mc.gameRenderer.mainRenderTarget();
        RenderSystem.AutoStorageIndexBuffer idx = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        // The galaxy sphere is fixed in inertial axes; rotate it into the sky with the same M as everything else.
        Matrix4f galaxyView4 = new Matrix4f(modelView).mul(new Matrix4f(new Matrix3f(
                (float) m[0], (float) m[3], (float) m[6],
                (float) m[1], (float) m[4], (float) m[7],
                (float) m[2], (float) m[5], (float) m[8])));
        float galaxyAlpha = (float) ((1 - washout) * 0.8 * rain);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Galactica surface sky", rt.getColorTextureView(), Optional.empty(), rt.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            if (galaxyAlpha > 0.01f) draw(pass, idx, galaxyView4, sphere, sphereIndices, 0, galaxyView, wrapU, new Vector4f(1, 1, 1, galaxyAlpha));
            if (quads != null) draw(pass, idx, modelView, quads, quadIndices, 0, discView, clamp, new Vector4f(1, 1, 1, 1));
            for (int i = 0; i < PAINTED.size(); i++) {
                Painted p = PAINTED.get(i);
                draw(pass, idx, modelView, painted, 6, i * 4, p.texture().getTextureView(), clamp,
                        new Vector4f(p.brightness(), p.brightness(), p.brightness(), (float) ((1 - 0.6 * washout) * rain)));
            }
        }
    }

    private static void addBody(List<Quad> out, Star star, GalacticPos starPos, GalacticPos eye, Body here, Body planet, Body moon,
                                double t, double[] m, double magStar, double washout, float rain) {
        Body b = moon != null ? moon : planet;
        if (b.id() == here.id()) return;
        double[] off = BodyFrame.offsetFromStar(star, planet, moon, t);
        GalacticPos pos = starPos.plusMetres(off[0], off[1], off[2]);
        double[] g = pos.metresFrom(eye);
        double d = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (d < b.radiusMetres()) return;
        double ang = Math.asin(b.radiusMetres() / d);
        double a = Math.sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
        double dStar = starPos.distanceMetres(eye);
        // Reflected light, as in the space sky: F/F★ = A·R²·D²/(2π·a²·d²)·½.
        double ratio = b.albedo() * b.radiusMetres() * b.radiusMetres() * dStar * dStar / (2 * Math.PI * a * a * d * d) * 0.5;
        double mag = magStar - 2.5 * Math.log10(Math.max(1e-30, ratio));
        double[] f = SkyAlignment.apply(m, mc(g));
        normalize(f);
        if (ang * 2 * PlanetImager.extent(b) > RESOLVE_RAD) {
            paint(b, star, pos, f, d, ang, a, m, t);
            return;
        }
        // Daylight raises the sky background by ~6 magnitudes at full washout (Venus stays visible).
        double limit = 6.5 - 6.0 * washout;
        if (mag > limit) return;
        float alpha = (float) Math.min(1.0, 0.35 * Math.pow(10, -0.4 * (mag - limit))) * rain;
        out.add(quad(f, Math.max(2 * ang, DOT_SIZE), (Math.round(alpha * 255) << 24) | (colour(b) & 0xFFFFFF)));
    }

    private static void paint(Body b, Star star, GalacticPos pos, double[] f, double d, double ang, double orbitRadius, double[] m, double t) {
        Vector3f[] basis = quadBasis((float) f[0], (float) f[1], (float) f[2]);
        PlanetImager.View v = new PlanetImager.View();
        v.forward = f;
        v.up = new double[] {basis[1].x, basis[1].y, basis[1].z};
        v.right = cross(f, v.up);
        double[] ls = Neighbourhood.starPos(star).metresFrom(pos);
        v.toStar = SkyAlignment.apply(m, mc(ls));
        normalize(v.toStar);
        float[] c = new float[3];
        Blackbody.linearRgb(Math.max(1000, star.temperature()), c);
        v.starR = c[0];
        v.starG = c[1];
        v.starB = c[2];
        v.spinAxis = SkyAlignment.apply(m, mc(BodyFrame.pole(star, b)));
        normalize(v.spinAxis);
        v.rotationRad = BodyFrame.spinAngle(b, t);
        double halfAngle = Math.min(1.2, Math.atan(PlanetImager.extent(b) * Math.tan(ang)));
        DynamicTexture tex = SurfaceSprites.get(b, v, 2 * halfAngle * PX_PER_RAD);
        double flux = star.luminosity() / Math.max(1e-12, Math.pow(orbitRadius / Units.AU, 2));
        float brightness = (float) Math.max(0.2, Math.min(1.0, Math.pow(flux, 0.3)));
        PAINTED.add(new Painted((float) f[0], (float) f[1], (float) f[2], halfAngle, d, tex, brightness));
    }

    // ---------------------------------------------------------------- geometry

    private static void rebuild(List<Quad> list) {
        if (quads != null) {
            quads.close();
            quads = null;
        }
        if (painted != null) {
            painted.close();
            painted = null;
        }
        if (!list.isEmpty()) {
            try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(list.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
                BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (Quad q : list) spriteQuad(b, q.dx(), q.dy(), q.dz(), R_NEAR, q.size(), q.argb());
                try (MeshData mesh = b.buildOrThrow()) {
                    quadIndices = mesh.drawState().indexCount();
                    quads = RenderSystem.getDevice().createBuffer(() -> "Galactica surface sky bodies", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                }
            }
        }
        if (!PAINTED.isEmpty()) {
            PAINTED.sort((x, y) -> Double.compare(y.distance(), x.distance()));
            try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(PAINTED.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
                BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (Painted p : PAINTED) spriteQuad(b, p.dx(), p.dy(), p.dz(), R_NEAR - 1f, p.halfAngle(), 0xFFFFFFFF);
                try (MeshData mesh = b.buildOrThrow()) {
                    painted = RenderSystem.getDevice().createBuffer(() -> "Galactica surface sky planets", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                }
            }
        }
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
                sphere = RenderSystem.getDevice().createBuffer(() -> "Galactica surface Milky Way", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer idx, Matrix4f modelView, GpuBuffer vb, int indexCount,
                             int baseVertex, GpuTextureView texture, GpuSampler sampler, Vector4f colour) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, colour);
        pass.pushDebugGroup(() -> "Galactica surface sky");
        pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Sampler0", texture, sampler);
        pass.setVertexBuffer(0, vb.slice());
        pass.setIndexBuffer(idx.getBuffer(indexCount), idx.type());
        pass.drawIndexed(indexCount, 1, 0, baseVertex, 0);
        pass.popDebugGroup();
    }

    private static Quad quad(double[] dir, double size, int argb) {
        double n = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        return new Quad((float) (dir[0] / n), (float) (dir[1] / n), (float) (dir[2] / n), size, argb);
    }

    private static Vector3f[] quadBasis(float dx, float dy, float dz) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f upRef = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f t1 = new Vector3f(dir).cross(upRef).normalize();
        Vector3f t2 = new Vector3f(dir).cross(t1).normalize();
        return new Vector3f[] {t1, t2};
    }

    /** Camera-facing quad centred on a direction at a shell radius; angularSize is the quad half-width in radians. */
    private static void spriteQuad(BufferBuilder b, float dx, float dy, float dz, float radius, double angularSize, int argb) {
        Vector3f[] basis = quadBasis(dx, dy, dz);
        Vector3f t1 = basis[0], t2 = basis[1];
        float h = (float) (Math.tan(Math.min(1.2, angularSize)) * radius);
        Vector3f c = new Vector3f(dx, dy, dz).normalize().mul(radius);
        float[][] v = new float[4][];
        float[][] corners = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
        for (int k = 0; k < 4; k++) {
            float sx = corners[k][0] * h, sy = corners[k][1] * h;
            v[k] = new float[] {c.x + t1.x * sx + t2.x * sy, c.y + t1.y * sx + t2.y * sy, c.z + t1.z * sx + t2.z * sy, corners[k][2], corners[k][3]};
        }
        emitInwardQuad(b, v, argb);
    }

    /** END_SKY culls back faces and the camera sits at every shell's centre, so quads are wound to face inward. */
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

    private static int colour(Body b) {
        return switch (b.type()) {
            case LAVA -> 0xFF5A20;
            case BARREN -> 0x9A938A;
            case DESERT -> 0xD2A26A;
            case GREENHOUSE -> 0xF2E2B0;
            case TERRAN -> 0x6E9BD8;
            case OCEAN -> 0x3F6FC8;
            case ICE -> 0xE6F0FA;
            case SUB_NEPTUNE -> 0xA8D0E0;
            case ICE_GIANT -> 0x8FC8E6;
            case GAS_GIANT -> 0xD8B88C;
            case HOT_JUPITER -> 0x8A5A44;
        };
    }

    /** Galactic-frame vector to Minecraft axes. */
    private static double[] mc(double[] g) {
        return Frames.galacticToMc(g[0], g[1], g[2]);
    }

    private static int argb(float r, float g, float b, float a) {
        return (Math.round(Math.min(1, a) * 255) << 24) | (Math.round((float) Blackbody.encode(r) * 255) << 16)
                | (Math.round((float) Blackbody.encode(g) * 255) << 8) | Math.round((float) Blackbody.encode(b) * 255);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static void normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n > 0) {
            v[0] /= n;
            v[1] /= n;
            v[2] /= n;
        }
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
