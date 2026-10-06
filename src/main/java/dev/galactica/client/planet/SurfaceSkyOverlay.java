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
import dev.galactica.planet.AtmosphereGradient;
import dev.galactica.planet.BodyFrame;
import dev.galactica.planet.PlanetAddress;
import dev.galactica.planet.PlanetEntry;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.planet.SkyAlignment;
import dev.galactica.planet.SurfaceAtmosphere;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.planet.SurfaceState;
import dev.galactica.planet.TerrainSampler;
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
 *
 * <p><b>Altitude.</b> The sky itself is a dome painted from {@link AtmosphereGradient} (calibrated to
 * the biome sky colour at the ground) and drawn over vanilla's sky with alpha = the day weight, so
 * by day it replaces vanilla's fixed biome colour and at night vanilla's sky shows through. The
 * camera's altitude comes from the slot's {@link AtmosphereGradient.AltitudeMap}: true scale at the
 * ground, reaching the entry altitude ({@link PlanetEntry#entryAltitude}) exactly at
 * {@link PlanetEntry#ENTRY_Y}, so climbing to the switch darkens the sky to the black the space side
 * shows there, and daylight washout of stars and planets falls with the air above. Beyond render
 * distance {@link FarTerrainRenderer} draws the real terrain to the curved horizon once the camera
 * is high.
 *
 * <p>Per frame only the cheap part runs: positions from cached orbits. The system's body list is
 * generated once per landing, and the vertex buffers are rebuilt only when something moved by more
 * than {@link #MOVE_EPS} (a third of a pixel at normal FOV): the sky turns 0.015° a tick, so that is
 * every few frames instead of every frame. Leaving the surface frees the buffers and painted textures.
 */
public final class SurfaceSkyOverlay {
    private SurfaceSkyOverlay() {}

    private static final float R_GALAXY = 100f, R_NEAR = 90f;
    private static final double PX_PER_RAD = 885.0, RESOLVE_RAD = 0.0045, DOT_SIZE = 0.0016;

    /** Largest direction or size change (radians) that keeps last frame's vertex buffers. */
    private static final double MOVE_EPS = 4e-4;
    private static final int ALPHA_EPS = 3;

    private record Quad(float dx, float dy, float dz, double size, int argb) {}
    private record Painted(long id, float dx, float dy, float dz, double halfAngle, double distance, DynamicTexture texture, float brightness) {}

    private static GpuBuffer sphere, quads, painted, dome;
    private static int domeIndices;
    /** Inputs of the current dome: sun direction, altitude (m), rain; NaN forces a rebuild. */
    private static double domeSun0 = Double.NaN, domeSun1, domeSun2, domeAltitude, domeRain;
    private static AtmosphereGradient.AltitudeMap altitudeMap;
    private static final int DOME_LAT = 24, DOME_LON = 48;
    private static long atmBody = Long.MIN_VALUE;
    private static AtmosphereGradient atm = AtmosphereGradient.NONE;
    private static int sphereIndices, quadIndices;
    private static final List<Painted> PAINTED = new ArrayList<>();
    private static List<Quad> builtQuads = List.of();
    private static List<Painted> builtPainted = List.of();
    private static PlanetAddress cachedAddress;
    private static PlanetAddress.Resolved cached;
    private static List<Body> cachedSystem = List.of();

    /**
     * True in a surface slot whose body the client knows. Called by the sky mixin every frame in every
     * dimension, so it is also where the overlay lets go of its GPU resources after take-off.
     */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        boolean on = mc.level != null && mc.player != null && PlanetSlots.slotOf(mc.level.dimension()) >= 0 && resolved(mc) != null;
        if (!on && (quads != null || painted != null || cachedAddress != null)) release();
        return on;
    }

    /** The body under the local player, or null off-planet. */
    public static Body currentBody() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || PlanetSlots.slotOf(mc.level.dimension()) < 0) return null;
        PlanetAddress.Resolved r = resolved(mc);
        return r != null ? r.body() : null;
    }

    private static PlanetAddress.Resolved resolved(Minecraft mc) {
        SurfaceState st = mc.player.getAttached(PlanetEntry.SURFACE);
        if (st == null) return null;
        if (!st.address().equals(cachedAddress)) {
            cachedAddress = st.address();
            cached = st.address().resolve(SkyManager.get().galaxy());
            cachedSystem = cached != null ? SystemGenerator.generate(cached.star()) : List.of();
        }
        return cached;
    }

    private static void release() {
        if (quads != null) quads.close();
        if (painted != null) painted.close();
        quads = painted = null;
        builtQuads = List.of();
        builtPainted = List.of();
        PAINTED.clear();
        cachedAddress = null;
        cached = null;
        cachedSystem = List.of();
        SurfaceSprites.clear();
        if (dome != null) dome.close();
        dome = null;
        domeIndices = 0;
        domeSun0 = Double.NaN;
        atmBody = Long.MIN_VALUE;
        altitudeMap = null;
        FarTerrainRenderer.release();
        Minecraft mc = Minecraft.getInstance();
        // In space the near-planet renderer keeps using the clipmap; anywhere else it is dead weight.
        if (mc.level == null || mc.level.dimension() != Galactica.SPACE) SurfaceClipmap.releaseAll();
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
        if (atmBody != here.id()) {
            atmBody = here.id();
            atm = AtmosphereGradient.of(here);
            altitudeMap = altitudeMap(here);
            domeSun0 = Double.NaN;
        }
        double altitudeM = altitudeMap.metres(cam.y - TerrainSampler.SEA_LEVEL);
        double airAbove = atm.vacuum() ? 1 : Math.min(1, atm.brightness(altitudeM));
        double washout = day * skyClass.daylightOpacity * airAbove;
        rebuildDome(sun, altitudeM, state.rainBrightness);
        FarTerrainRenderer.prepare(here, cam, sun, mc.options.getEffectiveRenderDistance(), altitudeMap);
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
        for (Body planet : cachedSystem) {
            addBody(list, star, starPos, eye, here, planet, null, t, m, magStar, washout, rain);
            for (Body moon : planet.moons()) addBody(list, star, starPos, eye, here, planet, moon, t, m, magStar, washout, rain);
        }
        PAINTED.sort((x, y) -> Double.compare(y.distance(), x.distance()));
        if (!sameQuads(list, builtQuads)) rebuildQuads(list);
        if (!samePainted(PAINTED, builtPainted)) rebuildPainted();
        SurfaceSprites.evict();

        GpuTextureView galaxyView = SkyTextures.galaxy().getTextureView();
        GpuTextureView discView = SkyTextures.disc().getTextureView();
        // Created lazily, and creating a DynamicTexture uploads: resolve it before the pass opens.
        GpuTextureView whiteView = SurfaceClipmap.white().getTextureView();
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
            if (dome != null) draw(pass, idx, modelView, dome, domeIndices, 0, whiteView, clamp, new Vector4f(1, 1, 1, 1));
            if (galaxyAlpha > 0.01f) draw(pass, idx, galaxyView4, sphere, sphereIndices, 0, galaxyView, wrapU, new Vector4f(1, 1, 1, galaxyAlpha));
            if (quads != null) draw(pass, idx, modelView, quads, quadIndices, 0, discView, clamp, new Vector4f(1, 1, 1, 1));
            for (int i = 0; i < PAINTED.size(); i++) {
                Painted p = PAINTED.get(i);
                draw(pass, idx, modelView, painted, 6, i * 4, p.texture().getTextureView(), clamp,
                        new Vector4f(p.brightness(), p.brightness(), p.brightness(), (float) ((1 - 0.6 * washout) * rain)));
            }
            // Ground beyond render distance last: it hides bodies that have set below the horizon.
            FarTerrainRenderer.draw(pass, idx, modelView);
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
        PAINTED.add(new Painted(b.id(), (float) f[0], (float) f[1], (float) f[2], halfAngle, d, tex, brightness));
    }

    // ---------------------------------------------------------------- geometry

    private static boolean sameQuads(List<Quad> now, List<Quad> built) {
        if (now.size() != built.size()) return false;
        for (int i = 0; i < now.size(); i++) {
            Quad a = now.get(i), b = built.get(i);
            if (!near(a.dx(), a.dy(), a.dz(), b.dx(), b.dy(), b.dz()) || Math.abs(a.size() - b.size()) > MOVE_EPS
                    || (a.argb() & 0xFFFFFF) != (b.argb() & 0xFFFFFF) || Math.abs((a.argb() >>> 24) - (b.argb() >>> 24)) > ALPHA_EPS) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePainted(List<Painted> now, List<Painted> built) {
        if (now.size() != built.size()) return false;
        for (int i = 0; i < now.size(); i++) {
            Painted a = now.get(i), b = built.get(i);
            if (a.id() != b.id() || !near(a.dx(), a.dy(), a.dz(), b.dx(), b.dy(), b.dz()) || Math.abs(a.halfAngle() - b.halfAngle()) > MOVE_EPS) {
                return false;
            }
        }
        return true;
    }

    /** Unit directions within {@link #MOVE_EPS} radians (chord length ≈ angle at these scales). */
    private static boolean near(float ax, float ay, float az, float bx, float by, float bz) {
        float x = ax - bx, y = ay - by, z = az - bz;
        return x * x + y * y + z * z < MOVE_EPS * MOVE_EPS;
    }

    private static void rebuildQuads(List<Quad> list) {
        if (quads != null) {
            quads.close();
            quads = null;
        }
        builtQuads = List.copyOf(list);
        if (list.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(list.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (Quad q : list) spriteQuad(b, q.dx(), q.dy(), q.dz(), R_NEAR, q.size(), q.argb());
            try (MeshData mesh = b.buildOrThrow()) {
                quadIndices = mesh.drawState().indexCount();
                quads = RenderSystem.getDevice().createBuffer(() -> "Galactica surface sky bodies", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** Geometry only: textures and brightness are bound per draw, so they never force a rebuild. */
    private static void rebuildPainted() {
        if (painted != null) {
            painted.close();
            painted = null;
        }
        builtPainted = List.copyOf(PAINTED);
        if (PAINTED.isEmpty()) return;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(PAINTED.size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (Painted p : PAINTED) spriteQuad(b, p.dx(), p.dy(), p.dz(), R_NEAR - 1f, p.halfAngle(), 0xFFFFFFFF);
            try (MeshData mesh = b.buildOrThrow()) {
                painted = RenderSystem.getDevice().createBuffer(() -> "Galactica surface sky planets", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /**
     * The slot's altitude scale: {@link AtmosphereGradient.AltitudeMap} from the body's metres per
     * block to its entry altitude at {@link PlanetEntry#ENTRY_Y}. Shared with the far terrain.
     */
    public static AtmosphereGradient.AltitudeMap altitudeMap(Body b) {
        return AtmosphereGradient.AltitudeMap.of(SurfaceGrid.of(b.radiusMetres()).metresPerBlock(),
                PlanetEntry.ENTRY_Y - TerrainSampler.SEA_LEVEL, PlanetEntry.entryAltitude(b));
    }

    /**
     * Sky dome coloured by {@link AtmosphereGradient} for the camera's altitude and the sun's
     * position, drawn over vanilla's sky (on a shell just inside the galaxy's, drawn before it) with
     * colour = the radiance and alpha = the day weight × rain: by day the result is the physical sky,
     * which darkens with altitude; at night and in storms vanilla's sky shows through. 1 152 quads,
     * rebuilt only when the sun moves by 0.003, the altitude by 1 % of the scale height or the rain.
     */
    private static void rebuildDome(double[] sun, double altitudeM, float rain) {
        if (atm.vacuum()) {
            if (dome != null) dome.close();
            dome = null;
            domeIndices = 0;
            return;
        }
        boolean same = !Double.isNaN(domeSun0) && Math.abs(sun[0] - domeSun0) + Math.abs(sun[1] - domeSun1) + Math.abs(sun[2] - domeSun2) < 0.003
                && Math.abs(altitudeM - domeAltitude) < 0.01 * atm.scaleHeight() && Math.abs(rain - domeRain) < 0.01;
        if (same) return;
        domeSun0 = sun[0];
        domeSun1 = sun[1];
        domeSun2 = sun[2];
        domeAltitude = altitudeM;
        domeRain = rain;
        if (dome != null) dome.close();
        dome = null;
        domeIndices = 0;
        double dayWeight = AtmosphereGradient.sunVisibility(sun[1]) * rain;
        if (dayWeight < 1e-3) return;
        float r = R_GALAXY - 1;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(DOME_LAT * DOME_LON * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            float[][] v = new float[4][5];
            int[] c = new int[4];
            double[] rgb = new double[3];
            for (int j = 0; j < DOME_LAT; j++) {
                for (int i = 0; i < DOME_LON; i++) {
                    for (int k = 0; k < 4; k++) {
                        int jj = j + (k >= 2 ? 1 : 0), ii = i + (k == 1 || k == 2 ? 1 : 0);
                        // Rows dense near the horizon, where the gradient changes fastest.
                        double f = jj / (double) DOME_LAT;
                        double el = Math.PI / 2 - Math.PI * (f < 0.5 ? 0.5 * Math.pow(2 * f, 0.6) : 1 - 0.5 * Math.pow(2 - 2 * f, 0.6));
                        double az = 2 * Math.PI * ii / DOME_LON;
                        double dx = Math.cos(el) * Math.cos(az), dy = Math.sin(el), dz = Math.cos(el) * Math.sin(az);
                        atm.sky(dy, dx * sun[0] + dy * sun[1] + dz * sun[2], sun[1], altitudeM, rgb);
                        v[k][0] = (float) dx * r;
                        v[k][1] = (float) dy * r;
                        v[k][2] = (float) dz * r;
                        v[k][3] = 0.5f;
                        v[k][4] = 0.5f;
                        c[k] = over(rgb, dayWeight);
                    }
                    emitInwardQuad(b, v, c);
                }
            }
            try (MeshData mesh = b.buildOrThrow()) {
                domeIndices = mesh.drawState().indexCount();
                dome = RenderSystem.getDevice().createBuffer(() -> "Galactica surface sky dome", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** Linear radiance as an over-blended colour: rgb = sRGB(L), alpha = weight. */
    private static int over(double[] linear, double weight) {
        double r = AtmosphereGradient.encode(linear[0]), g = AtmosphereGradient.encode(linear[1]), b = AtmosphereGradient.encode(linear[2]);
        return (int) Math.round(clamp(weight, 0, 1) * 255) << 24 | (int) Math.round(r * 255) << 16 | (int) Math.round(g * 255) << 8
                | (int) Math.round(b * 255);
    }

    private static void emitInwardQuad(BufferBuilder b, float[][] v, int[] argb) {
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
