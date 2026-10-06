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
    public static final int SKY_WIDTH = 2048, SKY_HEIGHT = 1024;
    public static final double NEAR_LIMIT_PC = 0.05;

    public record BakedSky(int[] argb, GalacticPos at) {}
    public record BakedStars(List<Starfield.VisibleStar> stars, GalacticPos at) {}

    private static final SkyManager INSTANCE = new SkyManager();

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
    private GalacticPos skyRequestedAt, starsRequestedAt;
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

        if (!skyBusy && (skyRequestedAt == null || viewer.distanceMetres(skyRequestedAt) > skyRebakeDistance(viewer))) {
            skyRequestedAt = viewer;
            skyBusy = true;
            GalacticPos at = viewer;
            worker.execute(() -> {
                try {
                    float[] hdr = new float[SKY_WIDTH * SKY_HEIGHT * 3];
                    baker.bakeEquirect(at.xPc(), at.yPc(), at.zPc(), SKY_WIDTH, SKY_HEIGHT, hdr);
                    SkyTonemap tm = SkyTonemap.fit(hdr);
                    int[] argb = new int[SKY_WIDTH * SKY_HEIGHT];
                    for (int i = 0; i < argb.length; i++) argb[i] = tm.toArgb(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]);
                    pendingSky.set(new BakedSky(argb, at));
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
                try {
                    List<Starfield.VisibleStar> all = Starfield.compute(galaxy, at.xPc(), at.yPc(), at.zPc());
                    // Stars inside the near limit are drawn live by the renderer.
                    List<Starfield.VisibleStar> far = all.stream().filter(v -> v.distancePc() > NEAR_LIMIT_PC).toList();
                    pendingStars.set(new BakedStars(far, at));
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
        return Math.max(3.0, 0.02 * scale) * Units.PARSEC;
    }

    /** Forces both layers to rebuild, e.g. after a hyperjump. */
    public void invalidate() {
        skyRequestedAt = null;
        starsRequestedAt = null;
    }
}
