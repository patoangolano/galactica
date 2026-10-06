package dev.galactica.client.discovery;

import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.discovery.DiscoveryPayloads;
import dev.galactica.discovery.ScanRules;
import dev.galactica.discovery.ScanTarget;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.planet.PlanetAddress;
import dev.galactica.planet.PlanetEntry;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.planet.SkyAlignment;
import dev.galactica.planet.SurfaceState;
import dev.galactica.science.Units;
import dev.galactica.sky.Starfield;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds what the spectrometer is pointed at: the nearest object within
 * {@link ScanRules#PICK_DEG} of the crosshair (or within its own angular radius, for a big disc)
 * among the current system's star, planets and moons and every resolved star in the sky. The
 * starfield is recomputed here with {@link Starfield#compute} (the same function the sky uses), on
 * a worker thread, and cached until the observer moves 0.25 pc, like {@code SkyManager}'s copy. On
 * a planet surface the only target is the host star, which the surface sky draws on vanilla's sun.
 */
final class TargetFinder {
    private TargetFinder() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica spectrometer");
        t.setDaemon(true);
        return t;
    });
    /** Worker-thread only: Neighbourhood is not thread-safe, so the sky's instance is not shared. */
    private static Neighbourhood workerNeighbourhood;
    private static List<Starfield.VisibleStar> stars;
    private static GalacticPos starsAt;
    private static volatile boolean busy;
    private static long lastPress;

    private record Pick(ScanTarget target, String name, double angle) {}

    static void scan() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || busy) return;
        long now = System.currentTimeMillis();
        if (now - lastPress < 400) return;
        lastPress = now;
        if (!ClientPlayNetworking.canSend(DiscoveryPayloads.Scan.TYPE)) return;

        Vec3 lookV = player.getLookAngle();
        double[] look = {lookV.x, lookV.y, lookV.z};
        Galaxy galaxy = SkyManager.get().galaxy();
        if (mc.level.dimension() == Galactica.SPACE) {
            NavState nav = player.getAttached(Galactica.NAV);
            if (nav == null) return;
            Vec3 eye = player.getEyePosition();
            GalacticPos here = Frames.playerPos(nav, eye.x, eye.y, eye.z);
            double t = nav.timeSeconds();
            busy = true;
            player.sendOverlayMessage(Component.translatable("galactica.scan.scanning"));
            WORKER.execute(() -> {
                Pick pick = null;
                try {
                    pick = pickInSpace(galaxy, here, t, look);
                } catch (Throwable e) {
                    Galactica.LOG.error("Spectrometer target search failed", e);
                } finally {
                    busy = false;
                }
                Pick result = pick;
                mc.execute(() -> deliver(result));
            });
        } else if (PlanetSlots.slotOf(mc.level.dimension()) >= 0) {
            SurfaceState st = player.getAttached(PlanetEntry.SURFACE);
            PlanetAddress.Resolved r = st == null ? null : st.address().resolve(galaxy);
            if (r == null || r.star().kind() == StarKind.BLACK_HOLE) {
                deliver(null);
                return;
            }
            float sunDeg = mc.level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, player.position());
            double[] sun = SkyAlignment.vanillaSun(Math.toRadians(sunDeg));
            Star star = r.star();
            busy = true;
            WORKER.execute(() -> {
                Pick pick = null;
                try {
                    double angle = ScanRules.angleDeg(sun, look);
                    if (angle <= ScanRules.PICK_DEG * 2 && sun[1] > -0.05) {
                        ScanTarget target = ScanTarget.of(galaxy, star);
                        if (target != null) pick = new Pick(target, star.name(), angle);
                    }
                } finally {
                    busy = false;
                }
                Pick result = pick;
                mc.execute(() -> deliver(result));
            });
        } else {
            player.sendOverlayMessage(Component.translatable("galactica.scan.no_sky"));
        }
    }

    private static void deliver(Pick pick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (pick == null) {
            mc.player.sendOverlayMessage(Component.translatable("galactica.scan.no_target", ScanRules.PICK_DEG));
            return;
        }
        mc.player.sendOverlayMessage(Component.translatable("galactica.scan.target", pick.name()));
        ClientPlayNetworking.send(new DiscoveryPayloads.Scan(pick.target()));
    }

    /** Worker thread. */
    private static Pick pickInSpace(Galaxy galaxy, GalacticPos here, double t, double[] look) {
        if (workerNeighbourhood == null) workerNeighbourhood = new Neighbourhood(galaxy);
        Neighbourhood nb = workerNeighbourhood;
        Star sys = nb.systemStar(here);

        Star bestStar = null;
        Body bestPlanet = null, bestMoon = null;
        double best = Double.MAX_VALUE;

        if (sys != null && sys.kind() != StarKind.BLACK_HOLE) {
            double[] g = Neighbourhood.starPos(sys).metresFrom(here);
            double a = angleTo(g, look);
            if (a <= tolerance(sys.radiusMetres(), g) && a < best) {
                best = a;
                bestStar = sys;
            }
        }
        if (sys != null) {
            for (Neighbourhood.Located l : nb.locateBodies(here, t)) {
                double[] g = l.pos().metresFrom(here);
                double a = angleTo(g, look);
                if (a <= tolerance(l.body().radiusMetres(), g) && a < best) {
                    best = a;
                    bestStar = null;
                    bestPlanet = l.parent() != null ? l.parent() : l.body();
                    bestMoon = l.parent() != null ? l.body() : null;
                }
            }
        }
        if (stars == null || starsAt == null || here.distanceMetres(starsAt) > 0.25 * Units.PARSEC) {
            stars = Starfield.compute(galaxy, here.xPc(), here.yPc(), here.zPc());
            starsAt = here;
        }
        // Directions in the cached list are from where it was computed; they are exact to < 0.25 pc / d.
        for (Starfield.VisibleStar v : stars) {
            if (sys != null && v.star().id() == sys.id()) continue;
            double a = ScanRules.angleDeg(new double[] {v.dx(), v.dy(), v.dz()}, look);
            if (a <= ScanRules.PICK_DEG && a < best) {
                best = a;
                bestStar = v.star();
                bestPlanet = bestMoon = null;
            }
        }

        if (bestStar != null) {
            ScanTarget target = ScanTarget.of(galaxy, bestStar);
            return target == null ? null : new Pick(target, bestStar.name(), best);
        }
        if (bestPlanet == null) return null;
        List<Body> planets = nb.systemBodies();
        int pi = -1, mi = -1;
        for (int i = 0; i < planets.size(); i++) if (planets.get(i).id() == bestPlanet.id()) pi = i;
        if (pi < 0) return null;
        if (bestMoon != null) {
            List<Body> moons = planets.get(pi).moons();
            for (int j = 0; j < moons.size(); j++) if (moons.get(j).id() == bestMoon.id()) mi = j;
            if (mi < 0) return null;
        }
        ScanTarget host = ScanTarget.of(galaxy, sys);
        if (host == null) return null;
        Body b = bestMoon != null ? bestMoon : bestPlanet;
        return new Pick(host.withBody(pi, mi), b.name(), best);
    }

    private static double angleTo(double[] galactic, double[] lookMc) {
        return ScanRules.angleDeg(Frames.galacticToMc(galactic[0], galactic[1], galactic[2]), lookMc);
    }

    /** Pick cone: 1.5°, or the object's own angular radius when it looks bigger than that. */
    private static double tolerance(double radiusMetres, double[] offset) {
        double d = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2]);
        double ang = d > radiusMetres ? Math.toDegrees(Math.asin(radiusMetres / d)) : 90;
        return Math.max(ScanRules.PICK_DEG, ang);
    }
}
