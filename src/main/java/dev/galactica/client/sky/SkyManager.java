package dev.galactica.client.sky;

import dev.galactica.Galactica;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Units;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;
import dev.galactica.sky.Starfield;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the client's view of the galaxy current. Heavy work runs on one background thread and
 * hands results back through atomic slots that the render thread polls:
 * <ul>
 *   <li>the diffuse Milky Way, re-baked after moving 2 % of the distance to the nearest dense
 *       structure (at least 3 pc), so parallax of the arms and bulge stays smooth while warping;</li>
 *   <li>the resolved starfield, recomputed after moving 0.25 pc.</li>
 * </ul>
 * Objects closer than {@link #NEAR_LIMIT_PC} (the system's own star, planets, moons) are not baked:
 * {@link SpaceSkyRenderer} places them every frame because their directions change as you fly.
 */
public final class SkyManager {
    /**
     * Panorama size from the {@code skyQuality} setting (1024/2048/4096 wide, 2:1). Read at each bake
     * request; a change re-bakes on the next frame in space or on a surface, and
     * {@link SkyTextures#uploadGalaxy} resizes the texture to match.
     */
    public static int skyWidth() {
        return dev.galactica.config.GalacticaConfigs.get().skyBakeWidth();
    }

    public static int skyHeight() {
        return dev.galactica.config.ConfigEffects.skyHeight(skyWidth());
    }

    public static final double NEAR_LIMIT_PC = 0.05;

    public record BakedSky(int[] argb, GalacticPos at) {}
    public record BakedStars(List<Starfield.VisibleStar> stars, GalacticPos at) {}

    private static final SkyManager INSTANCE = new SkyManager();

    /** Dev autotest hook: sees the camera's galactic position once per rendered frame. Null in normal play. */
    public static java.util.function.@org.jspecify.annotations.Nullable Consumer<GalacticPos> frameProbe;

    public static SkyManager get() {
        return INSTANCE;
    }

    private final Galaxy galaxy = new Galaxy(Frames.GALAXY_SEED);
    private final GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
    private final Neighbourhood neighbourhood = new Neighbourhood(galaxy);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica sky baker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final AtomicReference<BakedSky> pendingSky = new AtomicReference<>();
    private final AtomicReference<BakedStars> pendingStars = new AtomicReference<>();
    private volatile boolean skyBusy, starsBusy;
    /** Bake scratch, touched only by the worker thread. */
    private float[] hdrScratch;
    private GalacticPos skyRequestedAt, starsRequestedAt;
    /** Width of the last bake requested; a different configured width forces a re-bake. */
    private int skyRequestedWidth;
    private GalacticPos viewer;
    private NavState nav;

    private SkyManager() {}

    public Galaxy galaxy() {
        return galaxy;
    }

    public Neighbourhood neighbourhood() {
        return neighbourhood;
    }

    /** Galactic position of the camera this frame, or null outside space. */
    public GalacticPos viewer() {
        return viewer;
    }

    public NavState nav() {
        return nav;
    }

    public BakedSky takeSky() {
        return pendingSky.getAndSet(null);
    }

    public BakedStars takeStars() {
        return pendingStars.getAndSet(null);
    }

    /** Called once per frame from the renderer while in space. */
    public void update(Minecraft mc) {
        if (mc.player == null) return;
        nav = mc.player.getAttached(Galactica.NAV);
        if (nav == null) nav = NavState.initial();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        viewer = Frames.playerPos(nav, cam.x, cam.y, cam.z);
        if (nav.speed() <= 0) {
            // The camera is interpolated between the last two ticks, the origin is not: it already holds this tick's
            // frame motion (OrbitalFrame), ~385 m per tick in low orbit. Taking back the part of it the camera has not
            // reached yet keeps the planet below gliding instead of stepping at 20 Hz.
            double k = -(1.0 - mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) * 0.05;
            viewer = viewer.plusMetres(nav.frameVx() * k, nav.frameVy() * k, nav.frameVz() * k);
        }
        if (frameProbe != null) frameProbe.accept(viewer);

        int width = skyWidth();
        if (!skyBusy && (skyRequestedAt == null || width != skyRequestedWidth
                || viewer.distanceMetres(skyRequestedAt) > skyRebakeDistance(viewer))) {
            skyRequestedAt = viewer;
            skyRequestedWidth = width;
            skyBusy = true;
            GalacticPos at = viewer;
            int w = width, h = dev.galactica.config.ConfigEffects.skyHeight(width);
            worker.execute(() -> {
                long perf = dev.galactica.perf.Perf.t0();
                try {
                    // The HDR scratch (25 MB at 2048 wide) is reused across bakes on this one worker thread.
                    float[] hdr = hdrScratch != null && hdrScratch.length == w * h * 3 ? hdrScratch : (hdrScratch = new float[w * h * 3]);
                    baker.bakeEquirect(at.xPc(), at.yPc(), at.zPc(), w, h, hdr);
                    SkyTonemap tm = SkyTonemap.fit(hdr);
                    int[] argb = new int[w * h];
                    for (int i = 0; i < argb.length; i++) argb[i] = tm.toArgb(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]);
                    pendingSky.set(new BakedSky(argb, at));
                    dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.SKY_BAKE, perf);
                } catch (Throwable t) {
                    Galactica.LOG.error("Galaxy sky bake failed", t);
                } finally {
                    skyBusy = false;
                }
            });
        }
        if (!starsBusy && (starsRequestedAt == null || viewer.distanceMetres(starsRequestedAt) > 0.25 * Units.PARSEC)) {
            starsRequestedAt = viewer;
            starsBusy = true;
            GalacticPos at = viewer;
            worker.execute(() -> {
                long perf = dev.galactica.perf.Perf.t0();
                try {
                    List<Starfield.VisibleStar> all = Starfield.compute(galaxy, at.xPc(), at.yPc(), at.zPc());
                    // Stars inside the near limit are drawn live by the renderer.
                    List<Starfield.VisibleStar> far = all.stream().filter(v -> v.distancePc() > NEAR_LIMIT_PC).toList();
                    pendingStars.set(new BakedStars(far, at));
                    dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.STARFIELD, perf);
                } catch (Throwable t) {
                    Galactica.LOG.error("Starfield computation failed", t);
                } finally {
                    starsBusy = false;
                }
            });
        }
    }

    /** 2 % of the distance to the Galactic Centre or the disk mid-plane, whichever is closer; ≥ 3 pc. */
    private static double skyRebakeDistance(GalacticPos p) {
        double r = Math.sqrt(p.xPc() * p.xPc() + p.yPc() * p.yPc() + p.zPc() * p.zPc());
        double scale = Math.min(r, Math.max(Math.abs(p.zPc()), 150));
        double basePc = Math.max(3.0, 0.02 * scale);
        return dev.galactica.deepsky.DeepSky.of(Frames.GALAXY_SEED).rebakeDistancePc(p.xPc(), p.yPc(), p.zPc(), basePc) * Units.PARSEC;
    }

    /** Forces both layers to rebuild, e.g. after a hyperjump. */
    public void invalidate() {
        skyRequestedAt = null;
        starsRequestedAt = null;
    }
}
