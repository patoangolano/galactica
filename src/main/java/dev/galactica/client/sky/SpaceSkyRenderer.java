package dev.galactica.client.sky;

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
import dev.galactica.client.vfx.star.StarDiscRenderer;
import dev.galactica.client.vfx.star.StarVisual;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.PlanetImager;
import dev.galactica.sky.Starfield;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws space. Everything goes through the vanilla {@link RenderPipelines#END_SKY} pipeline
 * (position + UV + colour, alpha blended) with our own textures bound to Sampler0. Iris maps that
 * pipeline object to the shader pack's {@code gbuffers_skytextured} program, so packs fog, bloom
 * and grade our sky exactly as they do the vanilla one — no custom shader to break.
 *
 * <p>Layers, back to front, on concentric shells inside the far plane:
 * Milky Way sphere (r = 100) → resolved stars (r = 95) → this system's star and planets (r = 90).
 */
public final class SpaceSkyRenderer {
    private SpaceSkyRenderer() {}

    private static final float R_GALAXY = 100f, R_STARS = 95f, R_NEAR = 90f;
    /** Angular half-size of a faint star sprite, radians (≈ 1.5 px at 70° FOV / 1080p). */
    private static final double STAR_SPRITE = 0.0016;
    /** A camera PSF saturates: even the Sun from Earth only blooms to a few times the faint-star size. */
    private static final double MAX_SWELL = 6.0;

    static double psfSize(double mag) {
        return STAR_SPRITE * Math.min(MAX_SWELL, Math.max(1.0, Math.pow(10, -0.4 * (mag - 4.0) * 0.35)));
    }

    private static GpuBuffer sphere;
    private static int sphereIndices;
    private static GpuBuffer stars;
    private static int starIndices;
    private static GpuBuffer near;
    private static int nearIndices;
    private static float galaxyDim = 1f;
    /** The system star drawn by the GPU plasma shader this frame instead of a flat disc sprite. */
    private static StarVisual nearVisual;
    private static Star nearVisualStar;
    private static boolean starGpu;
    private static float[] starDir = new float[3];
    private static double starAng;
    private static GpuBuffer planets;
    private static final List<Resolved> RESOLVED = new ArrayList<>();
    /** On-screen pixels per radian at the default 70° FOV on a 1080-line window. */
    private static final double PX_PER_RAD = 885.0;
    /** Bodies wider than this (radians) are painted by PlanetImager instead of drawn as a dot (~4 px). */
    private static final double RESOLVE_RAD = 0.0045;

    /** A planet or moon big enough on screen to be painted, with the texture made for this frame. */
    private record Resolved(float dx, float dy, float dz, double halfAngle, double distance,
                            net.minecraft.client.renderer.texture.DynamicTexture texture, float brightness) {}

    /** True when the last frame drew the system star with the GPU plasma shader (autotest probe). */
    public static boolean starPlasmaDrawn() {
        return starGpu;
    }

    /** Angular radius of the plasma-drawn star in the last frame, radians (autotest probe). */
    public static double starPlasmaAngularRadius() {
        return starGpu ? starAng : 0;
    }

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == dev.galactica.Galactica.SPACE;
    }

    public static void render() {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            renderSky();
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.SPACE_SKY, perf);
        }
    }

    private static void renderSky() {
        Minecraft mc = Minecraft.getInstance();
        SkyManager mgr = SkyManager.get();
        long perf = dev.galactica.perf.Perf.t0();
        mgr.update(mc);
        dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.SKY_MANAGER, perf);
        SkyManager.BakedSky sky = mgr.takeSky();
        if (sky != null) SkyTextures.uploadGalaxy(sky.argb());
        SkyManager.BakedStars st = mgr.takeStars();
        if (st != null) rebuildStars(st.stars());
        if (sphere == null) buildSphere();
        perf = dev.galactica.perf.Perf.t0();
        PlanetSprites.beginFrame();
        NearPlanetRenderer.beginFrame();
        rebuildNear(mgr);
        rebuildPlanets();
        NearPlanetRenderer.endFrame();
        PlanetSprites.evict();
        dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.SPACE_NEAR, perf);
        perf = dev.galactica.perf.Perf.t0();
        dev.galactica.client.exotic.ExoticOverlay.prepare(mgr.viewer(), mgr.nav() != null ? mgr.nav().timeSeconds() : 0);
        dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.EXOTIC, perf);
        perf = dev.galactica.perf.Perf.t0();
        dev.galactica.client.exotic.NebulaCloseUp.prepare(mgr.viewer());
        dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.NEBULA, perf);

        // Resolve every texture first: creating a DynamicTexture uploads it, and uploads are
        // commands that are illegal while a render pass is open.
        GpuTextureView galaxyView = SkyTextures.galaxy().getTextureView();
        GpuTextureView psfView = SkyTextures.psf().getTextureView();
        GpuTextureView discView = SkyTextures.disc().getTextureView();
        GpuSampler wrapU = SkyTextures.linearWrapU(), clamp = SkyTextures.linearClamp();

        RenderTarget rt = mc.gameRenderer.mainRenderTarget();
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        perf = dev.galactica.perf.Perf.t0();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Galactica space sky", rt.getColorTextureView(), Optional.empty(), rt.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            draw(pass, quads, modelView, sphere, sphereIndices, galaxyView, wrapU,
                    new Vector4f(galaxyDim, galaxyDim, galaxyDim, 1f), "Milky Way");
            dev.galactica.client.exotic.NebulaCloseUp.draw(pass, quads, modelView, galaxyDim);
            if (stars != null) {
                draw(pass, quads, modelView, stars, starIndices, psfView, clamp,
                        new Vector4f(1, 1, 1, Math.min(1f, 0.25f + 0.75f * galaxyDim)), "Stars");
            }
            NearPlanetRenderer.drawSky(pass, quads, modelView);
            dev.galactica.client.exotic.ExoticOverlay.draw(pass, quads, modelView);
            if (starGpu) StarDiscRenderer.draw(pass, modelView, starDir[0], starDir[1], starDir[2], starAng, nearVisual);
            if (near != null && nearIndices > 0) {
                draw(pass, quads, modelView, near, nearIndices, discView, clamp,
                        new Vector4f(1, 1, 1, 1), "Near bodies");
            }
            // Resolved planets, far to near, each with its own painted texture.
            for (int i = 0; i < RESOLVED.size(); i++) {
                Resolved r = RESOLVED.get(i);
                GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView,
                        new Vector4f(r.brightness(), r.brightness(), r.brightness(), 1f));
                pass.pushDebugGroup(() -> "Planet");
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
                pass.setUniform("DynamicTransforms", transforms);
                pass.setUniform("Sampler0", r.texture().getTextureView(), clamp);
                pass.setVertexBuffer(0, planets.slice());
                pass.setIndexBuffer(quads.getBuffer(6), quads.type());
                pass.drawIndexed(6, 1, 0, i * 4, 0);
                pass.popDebugGroup();
            }
            NearPlanetRenderer.draw(pass, quads, modelView);
        }
        dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.SPACE_DRAW, perf);
    }

    private static void draw(RenderPass pass, RenderSystem.AutoStorageIndexBuffer quads, Matrix4f modelView, GpuBuffer vb, int indexCount,
                             GpuTextureView texture, GpuSampler sampler, Vector4f colour, String label) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, colour);
        pass.pushDebugGroup(() -> label);
        pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.END_SKY));
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Sampler0", texture, sampler);
        pass.setVertexBuffer(0, vb.slice());
        pass.setIndexBuffer(quads.getBuffer(indexCount), quads.type());
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
        pass.popDebugGroup();
    }

    // ---------------------------------------------------------------- geometry

    private static void buildSphere() {
        int lon = 96, lat = 48;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(lon * lat * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            double[] d = new double[3];
            float[][] v = new float[4][5];
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
                sphere = RenderSystem.getDevice().createBuffer(() -> "Galactica Milky Way sphere", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void rebuildStars(List<Starfield.VisibleStar> list) {
        if (stars != null) {
            stars.close();
            stars = null;
        }
        if (list.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(list.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (Starfield.VisibleStar s : list) {
                double m = s.apparentMag();
                // Faint stars dim at constant size; bright ones also swell, like a camera's PSF.
                float alpha = (float) Math.min(1.0, 0.35 * Math.pow(10, -0.4 * (m - 6.5)));
                double size = psfSize(m);
                spriteQuad(b, s.dx(), s.dy(), s.dz(), R_STARS, size, argb(s.r(), s.g(), s.b(), alpha));
            }
            try (MeshData mesh = b.buildOrThrow()) {
                starIndices = mesh.drawState().indexCount();
                stars = RenderSystem.getDevice().createBuffer(() -> "Galactica stars", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** The system's own star and its planets and moons, re-placed every frame. */
    private static void rebuildNear(SkyManager mgr) {
        if (near != null) {
            near.close();
            near = null;
            nearIndices = 0;
        }
        galaxyDim = 1f;
        starGpu = false;
        RESOLVED.clear();
        GalacticPos eye = mgr.viewer();
        if (eye == null) return;
        Neighbourhood nb = mgr.neighbourhood();
        Star star = nb.nearestStar(eye);
        if (star == null) return;
        GalacticPos sp = Neighbourhood.starPos(star);
        double dStar = sp.distanceMetres(eye);
        if (dStar > SkyManager.NEAR_LIMIT_PC * Units.PARSEC) return;

        List<NearQuad> quads = new ArrayList<>();
        float[] rgb = new float[3];
        RESOLVED.clear();
        double t = mgr.nav() != null ? mgr.nav().timeSeconds() : 0;

        // Star: disc at its true angular size, or a PSF-sized dot when unresolved.
        double magStar = star.absoluteMagnitude() + 5 * Math.log10(dStar / (10 * Units.PARSEC));
        if (star.kind() != StarKind.BLACK_HOLE) {
            Blackbody.linearRgb(star.temperature(), rgb);
            double ang = Math.asin(Math.min(1, star.radiusMetres() / dStar));
            if (StarDiscRenderer.wants(ang)) {
                if (nearVisual == null || !star.equals(nearVisualStar)) {
                    nearVisual = StarVisual.from(star);
                    nearVisualStar = star;
                }
                double[] g = sp.metresFrom(eye);
                double[] mc = Frames.galacticToMc(g[0], g[1], g[2]);
                starDir = new float[] {(float) mc[0], (float) mc[1], (float) mc[2]};
                starAng = ang;
                starGpu = StarDiscRenderer.prepare(nearVisual, ang);
            }
            if (!starGpu) {
                double size = Math.max(ang * 2.0, psfSize(magStar));
                addBody(quads, eye, sp, Math.min(size, 1.2), argb(rgb[0], rgb[1], rgb[2], 1f));
            }
            // A sun brighter than the full Moon washes the Milky Way out, as for a camera exposed for it.
            galaxyDim = (float) Math.max(0.12, Math.min(1.0, (magStar + 13.0) / 12.0));
        }

        double lStar = star.luminosity();
        for (Neighbourhood.Located l : nb.locateBodies(eye, t)) {
            Body b = l.body();
            double d = l.pos().distanceMetres(eye);
            if (d < b.radiusMetres()) continue;
            double ang = Math.asin(b.radiusMetres() / d);
            if (NearPlanetRenderer.offer(b, ang, star, l.pos(), sp, eye, t)) continue;
            // Reflected light: F_p/F_star = A·R²·D² / (2π·a²·d²)·phase, with D the star distance, a the orbit radius.
            double a = l.pos().distanceMetres(sp);
            double ratio = b.albedo() * b.radiusMetres() * b.radiusMetres() * dStar * dStar / (2 * Math.PI * a * a * d * d) * 0.5;
            double mag = magStar - 2.5 * Math.log10(Math.max(1e-30, ratio));
            if (lStar <= 0 || mag > 7.0 && ang < STAR_SPRITE) continue;
            if (ang * 2 * PlanetImager.extent(b) > RESOLVE_RAD) {
                resolve(l, star, sp, eye, d, ang, a, rgb, t);
                continue;
            }
            float alpha = (float) Math.min(1.0, 0.35 * Math.pow(10, -0.4 * (mag - 6.5)));
            double size = Math.max(ang * 2.0, STAR_SPRITE);
            int c = bodyColour(b.type());
            addBody(quads, eye, l.pos(), Math.min(size, 1.2), (Math.round(alpha * 255) << 24) | (c & 0xFFFFFF));
        }
        if (quads.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(quads.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (NearQuad q : quads) spriteQuad(b, q.dx(), q.dy(), q.dz(), R_NEAR, q.size(), q.argb());
            try (MeshData mesh = b.buildOrThrow()) {
                nearIndices = mesh.drawState().indexCount();
                near = RenderSystem.getDevice().createBuffer(() -> "Galactica near bodies", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /**
     * Sets up the imager view for a body and queues it for painting. The quad basis is the same one
     * {@link #spriteQuad} builds, so the painted image lands exactly on the quad.
     */
    private static void resolve(Neighbourhood.Located l, Star star, GalacticPos starPos, GalacticPos eye, double d,
                                double ang, double orbitRadius, float[] starRgb, double t) {
        Body b = l.body();
        double[] g = l.pos().metresFrom(eye);
        double[] f = Frames.galacticToMc(g[0], g[1], g[2]);
        normalize(f);
        double[] ls = starPos.metresFrom(l.pos());
        double[] toStar = Frames.galacticToMc(ls[0], ls[1], ls[2]);
        normalize(toStar);
        Vector3f[] basis = quadBasis((float) f[0], (float) f[1], (float) f[2]);
        PlanetImager.View v = new PlanetImager.View();
        v.forward = f;
        v.up = new double[] {basis[1].x, basis[1].y, basis[1].z};
        v.right = cross(f, v.up); // imager convention: right = forward × up
        v.toStar = toStar;
        Blackbody.linearRgb(Math.max(1000, star.temperature()), starRgb);
        v.starR = starRgb[0];
        v.starG = starRgb[1];
        v.starB = starRgb[2];
        // Spin axis: the system normal tipped by the axial tilt, expressed in Minecraft axes.
        double tilt = Math.toRadians(b.axialTiltDeg());
        double[] spinG = new double[3];
        dev.galactica.system.SystemFrame.toGalactic(star, Math.sin(tilt), Math.cos(tilt), 0, spinG);
        v.spinAxis = Frames.galacticToMc(spinG[0], spinG[1], spinG[2]);
        normalize(v.spinAxis);
        double period = b.rotationHours() * 3600.0;
        v.rotationRad = period == 0 ? 0 : (2 * Math.PI * (t / period)) % (2 * Math.PI);

        double extent = PlanetImager.extent(b);
        double halfAngle = Math.min(1.2, Math.atan(extent * Math.tan(ang)));
        double pixels = 2 * halfAngle * PX_PER_RAD;
        var tex = PlanetSprites.get(b, v, pixels);
        // Exposure: the painted image is exposed for Earth-like insolation; far from the star it dims,
        // compressed (^0.3) the way a camera with auto-exposure would show a dim outer planet.
        double flux = star.luminosity() / Math.max(1e-12, Math.pow(orbitRadius / Units.AU, 2));
        float brightness = (float) Math.max(0.2, Math.min(1.0, Math.pow(flux, 0.3)));
        RESOLVED.add(new Resolved((float) f[0], (float) f[1], (float) f[2], halfAngle, d, tex, brightness));
    }

    private static void rebuildPlanets() {
        if (planets != null) {
            planets.close();
            planets = null;
        }
        if (RESOLVED.isEmpty()) return;
        RESOLVED.sort((x, y) -> Double.compare(y.distance(), x.distance()));
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(RESOLVED.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (Resolved r : RESOLVED) spriteQuad(b, r.dx(), r.dy(), r.dz(), R_NEAR - 1f, r.halfAngle(), 0xFFFFFFFF);
            try (MeshData mesh = b.buildOrThrow()) {
                planets = RenderSystem.getDevice().createBuffer(() -> "Galactica planets", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
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

    /** Tangent basis {t1, t2} of a sprite facing the origin along dir; shared by sprites and planets. */
    private static Vector3f[] quadBasis(float dx, float dy, float dz) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f t1 = new Vector3f(dir).cross(up).normalize();
        Vector3f t2 = new Vector3f(dir).cross(t1).normalize();
        return new Vector3f[] {t1, t2};
    }

    private record NearQuad(float dx, float dy, float dz, double size, int argb) {}

    /** Queues a sprite for an object at galactic position {@code at}; size is the angular half-width of the quad (= disc diameter). */
    private static void addBody(List<NearQuad> out, GalacticPos eye, GalacticPos at, double size, int argb) {
        double[] g = at.metresFrom(eye);
        double[] m = Frames.galacticToMc(g[0], g[1], g[2]);
        double len = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        if (len <= 0) return;
        // The disc texture puts the limb at half the quad, so the quad is twice the disc.
        out.add(new NearQuad((float) (m[0] / len), (float) (m[1] / len), (float) (m[2] / len), size, argb));
    }

    private static int bodyColour(BodyType t) {
        return switch (t) {
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

    /** A camera-facing quad centred on direction (dx, dy, dz) at the given shell radius; angular size in radians. */
    private static void spriteQuad(BufferBuilder b, float dx, float dy, float dz, float radius, double angularSize, int argb) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        Vector3f[] basis = quadBasis(dx, dy, dz);
        Vector3f t1 = basis[0], t2 = basis[1];
        float h = (float) (Math.tan(Math.min(1.2, angularSize)) * radius);
        Vector3f c = new Vector3f(dir).mul(radius);
        float[][] v = new float[4][];
        float[][] corners = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
        for (int k = 0; k < 4; k++) {
            float sx = corners[k][0] * h, sy = corners[k][1] * h;
            v[k] = new float[] {c.x + t1.x * sx + t2.x * sy, c.y + t1.y * sx + t2.y * sy, c.z + t1.z * sx + t2.z * sy, corners[k][2], corners[k][3]};
        }
        emitInwardQuad(b, v, argb);
    }

    /**
     * END_SKY culls back faces, and the camera sits at the centre of every shell, so each quad is
     * wound to face the origin: if its normal points outward, the vertex order is reversed.
     */
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
        return (Math.round(Math.min(1, a) * 255) << 24) | (Math.round(encode(r) * 255) << 16) | (Math.round(encode(g) * 255) << 8)
                | Math.round(encode(b) * 255);
    }

    private static float encode(float linear) {
        return (float) Blackbody.encode(linear);
    }
}
