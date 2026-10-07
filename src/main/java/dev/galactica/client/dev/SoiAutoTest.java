package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.hud.OrbitReadout;
import dev.galactica.client.ship.ShipClient;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Autotest of the patched-conic orbital frame ({@code OrbitalFrame}) near Earth. Each phase drives the craft through
 * the game's own key mappings (W, sprint) or leaves it alone, measures the craft's real altitude above Earth every
 * tick (its block position plus the synced galactic origin, against Earth's position at the synced clock) and logs
 * one {@code [autotest] PASS|FAIL} line:
 * <ul>
 *   <li>{@link #orbit}: a ship given circular speed at 400 km keeps its altitude within ±5 % for a minute;</li>
 *   <li>{@link #approach}: from 2,000 km, flying at Earth (main engine, or the warp drive) brings the altitude down
 *       and ends in the entry switch within a time limit — the reported bug was Earth receding as one flew at it;</li>
 *   <li>{@link #takeOff}: a ship climbing out of the Overworld and left alone stays near Earth (falls back and
 *       re-enters, or at least stays bounded) instead of watching Earth run away at 29.8 km/s;</li>
 *   <li>{@link #evaFall}: a suited player at rest 1,000 km up falls toward Earth at ½ g t².</li>
 * </ul>
 */
public final class SoiAutoTest {
    private SoiAutoTest() {}

    /** One phase; returns true once it has logged its verdict. {@code t} counts client ticks from 0. */
    interface Phase {
        boolean tick(Minecraft mc, int t);
    }

    private static boolean installed;
    private static @Nullable Phase phase;
    private static int ticks;

    static void start(Phase p) {
        if (!installed) {
            installed = true;
            ClientTickEvents.END_CLIENT_TICK.register(SoiAutoTest::tick);
        }
        phase = p;
        ticks = 0;
    }

    static boolean done() {
        return phase == null;
    }

    private static void tick(Minecraft mc) {
        Phase p = phase;
        if (p == null || mc.player == null || mc.level == null) return;
        boolean finished;
        try {
            finished = p.tick(mc, ticks++);
        } catch (Throwable e) {
            Galactica.LOG.error("[autotest] FAIL soi phase crashed", e);
            finished = true;
        }
        if (finished) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            phase = null;
        }
    }

    private static boolean inSpace(Minecraft mc) {
        return mc.level.dimension() == Galactica.SPACE;
    }

    /** Earth located from the local craft at the synced clock, or null outside space. */
    private static Neighbourhood.@Nullable Located earth(Minecraft mc, GalacticPos here, NavState nav) {
        for (Neighbourhood.Located l : SkyManager.get().neighbourhood().locateBodies(here, nav.timeSeconds())) {
            if ("Earth".equals(l.body().name())) return l;
        }
        return null;
    }

    private static @Nullable GalacticPos craft(Minecraft mc) {
        NavState nav = mc.player.getAttached(Galactica.NAV);
        if (nav == null || !inSpace(mc)) return null;
        Entity root = mc.player.getRootVehicle();
        return Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
    }

    /** The craft's altitude above Earth's surface, metres; NaN outside space. */
    static double earthAltitude(Minecraft mc) {
        GalacticPos here = craft(mc);
        if (here == null) return Double.NaN;
        NavState nav = mc.player.getAttached(Galactica.NAV);
        Neighbourhood.Located e = earth(mc, here, nav);
        return e == null ? Double.NaN : e.pos().distanceMetres(here) - e.body().radiusMetres();
    }

    /** Points the piloted ship's nose at Earth's centre, on the client and the integrated server. */
    private static void aimShipAtEarth(Minecraft mc) {
        Starship ship = ShipClient.piloted(mc);
        GalacticPos here = craft(mc);
        if (ship == null || here == null) return;
        Neighbourhood.Located e = earth(mc, here, mc.player.getAttached(Galactica.NAV));
        if (e == null) return;
        double[] g = e.pos().metresFrom(here);
        double[] n = Frames.galacticToMc(g[0], g[1], g[2]);
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        setAttitude(mc, ship, new Quaternionf().rotationTo(0, 0, 1, (float) (n[0] / len), (float) (n[1] / len), (float) (n[2] / len)));
    }

    private static void setAttitude(Minecraft mc, Starship ship, Quaternionf q) {
        ship.setAttitude(q);
        var srv = mc.getSingleplayerServer();
        if (srv == null) return;
        java.util.UUID id = ship.getUUID();
        srv.execute(() -> {
            for (var lvl : srv.getAllLevels()) if (lvl.getEntity(id) instanceof Starship s) s.setAttitude(q);
        });
    }

    /** Puts the piloted ship at row {@code y} (same column), on the client and the integrated server. */
    private static void moveShip(Minecraft mc, Starship ship, double y) {
        double x = ship.getX(), z = ship.getZ();
        ship.setPos(x, y, z);
        ship.xo = x;
        ship.yo = y;
        ship.zo = z;
        var srv = mc.getSingleplayerServer();
        if (srv == null) return;
        java.util.UUID id = ship.getUUID();
        srv.execute(() -> {
            for (var lvl : srv.getAllLevels()) if (lvl.getEntity(id) instanceof Starship s) s.setPos(x, y, z);
        });
    }

    private static void stopShip(Minecraft mc, Starship ship) {
        ship.overrideVelocity(Vec3.ZERO);
        var srv = mc.getSingleplayerServer();
        if (srv == null) return;
        java.util.UUID id = ship.getUUID();
        srv.execute(() -> {
            for (var lvl : srv.getAllLevels()) if (lvl.getEntity(id) instanceof Starship s) s.setDeltaMovement(Vec3.ZERO);
        });
    }

    private static String km(double m) {
        return String.format(Locale.ROOT, "%.1f km", m / 1e3);
    }

    private static String readout(Minecraft mc) {
        OrbitReadout o = OrbitReadout.current(mc);
        return o == null ? "no readout" : String.format(Locale.ROOT, "HUD: %s alt %s v %s Pe %s Ap %s", o.body(), km(o.altitude()),
                OrbitReadout.speed(o.speed()), OrbitReadout.apsis(o.periapsis()), OrbitReadout.apsis(o.apoapsis()));
    }

    /** (c) Left alone on a circular orbit at {@code target} metres: the altitude stays within ±5 % for {@code length} ticks. */
    static Phase orbit(double target, int length) {
        double[] range = {Double.MAX_VALUE, -Double.MAX_VALUE};
        int[] missed = {0};
        return (mc, t) -> {
            double alt = earthAltitude(mc);
            if (Double.isNaN(alt)) missed[0]++;
            else {
                range[0] = Math.min(range[0], alt);
                range[1] = Math.max(range[1], alt);
            }
            if (t % 200 == 0) Galactica.LOG.info("[autotest] orbit t={} altitude {} ({})", t, km(alt), readout(mc));
            if (t < length) return false;
            boolean ok = missed[0] == 0 && range[0] > 0.95 * target && range[1] < 1.05 * target;
            Galactica.LOG.info("[autotest] {} circular orbit at {}: altitude {} .. {} over {} s (±5 % allowed), {} ticks out of space; {}",
                    ok ? "PASS" : "FAIL", km(target), km(range[0]), km(range[1]), length / 20, missed[0], readout(mc));
            return true;
        };
    }

    /**
     * (b) Flies at Earth from where the craft is: the main engine with the ship's nose on Earth's centre, or the warp
     * drive (sprint) for a suited creative player looking at it. Passes when the entry switch happens within
     * {@code limit} ticks and the altitude only went down on the way.
     */
    static Phase approach(String how, boolean warp, int limit) {
        double[] first = {Double.NaN}, last = {Double.NaN};
        int[] climbs = {0};
        return (mc, t) -> {
            if (inSpace(mc)) {
                if (warp) {
                    mc.player.getAbilities().flying = true;
                    mc.options.keySprint.setDown(true);
                    mc.player.setSprinting(true);
                } else {
                    if (t % 100 == 0) aimShipAtEarth(mc);
                    mc.options.keyUp.setDown(true);
                }
                double alt = earthAltitude(mc);
                if (!Double.isNaN(alt)) {
                    if (Double.isNaN(first[0])) first[0] = alt;
                    if (!Double.isNaN(last[0]) && alt > last[0] + 50) climbs[0]++;
                    last[0] = alt;
                }
                if (t % (warp ? 20 : 200) == 0) Galactica.LOG.info("[autotest] approach ({}) t={} altitude {} ({})", how, t, km(alt), readout(mc));
                if (t < limit) return false;
            }
            boolean entered = !inSpace(mc);
            boolean ok = entered && climbs[0] == 0 && first[0] > last[0];
            Galactica.LOG.info("[autotest] {} approach to Earth by {}: from {} down to {}, {} in {} s (limit {} s), climbing ticks {}",
                    ok ? "PASS" : "FAIL", how, km(first[0]), km(last[0]), entered ? "entered " + mc.level.dimension().identifier() : "no entry",
                    String.format(Locale.ROOT, "%.1f", t / 20.0), limit / 20, climbs[0]);
            return true;
        };
    }

    /**
     * (a) From the Overworld in a ship: stopped ten rows under the take-off row, nose straight up, main engine until the
     * switch (~280 m/s, an Overworld block being ~10 m), then hands off. Passes when the craft reached space and, left
     * alone, stayed within 200 km of Earth and either fell back into it or was on a bound, decelerating climb by the
     * end (finite apoapsis under 200 km, radial speed falling). Before the fix Earth receded at ~1.5 km per tick.
     */
    static Phase takeOff(int coastTicks) {
        int[] leftAt = {-1}, backAt = {-1};
        double[] max = {0}, end = {Double.NaN}, vr = {Double.NaN, Double.NaN}, ap = {Double.NaN};
        return (mc, t) -> {
            Starship ship = ShipClient.piloted(mc);
            if (t == 0) {
                if (ship == null || mc.level.dimension() != Level.OVERWORLD) {
                    Galactica.LOG.warn("[autotest] FAIL take-off: not piloting a ship in the Overworld ({})", mc.level.dimension().identifier());
                    return true;
                }
                stopShip(mc, ship);
                moveShip(mc, ship, dev.galactica.planet.PlanetEntry.EXIT_Y - 10);
                setAttitude(mc, ship, new Quaternionf().rotationX((float) Math.toRadians(-90)));
                Galactica.LOG.info("[autotest] take-off: nose up at y={}", String.format(Locale.ROOT, "%.0f", ship.getY()));
            }
            if (leftAt[0] < 0) {
                if (inSpace(mc)) {
                    leftAt[0] = t;
                    mc.options.keyUp.setDown(false);
                    Galactica.LOG.info("[autotest] take-off: in space after {} ticks; hands off ({})", t, readout(mc));
                } else if (t > 1200) {
                    Galactica.LOG.warn("[autotest] FAIL take-off: still in {} after 60 s", mc.level.dimension().identifier());
                    return true;
                } else {
                    mc.options.keyUp.setDown(true);
                    return false;
                }
            }
            int coast = t - leftAt[0];
            if (backAt[0] < 0 && !inSpace(mc)) backAt[0] = coast;
            double alt = earthAltitude(mc);
            if (!Double.isNaN(alt)) {
                max[0] = Math.max(max[0], alt);
                end[0] = alt;
            }
            OrbitReadout o = OrbitReadout.current(mc);
            if (o != null) {
                if (coast >= 20 && Double.isNaN(vr[0])) vr[0] = o.vertical();
                vr[1] = o.vertical();
                ap[0] = o.apoapsis();
            }
            if (coast % 40 == 0 && backAt[0] < 0) Galactica.LOG.info("[autotest] take-off coast t={} altitude {} ({})", coast, km(alt), readout(mc));
            if (backAt[0] < 0 && coast < coastTicks) return false;
            boolean bounded = max[0] < 200e3;
            boolean falling = Double.isFinite(ap[0]) && ap[0] < 200e3 && vr[1] < vr[0];
            boolean ok = bounded && (backAt[0] >= 0 || falling);
            Galactica.LOG.info("[autotest] {} take-off from Earth, hands off: highest {}, {}", ok ? "PASS" : "FAIL", km(max[0]),
                    backAt[0] >= 0 ? "fell back and re-entered after " + String.format(Locale.ROOT, "%.1f s", backAt[0] / 20.0)
                            : String.format(Locale.ROOT, "still in space after %d s at %s, radial speed %.0f -> %.0f m/s, apoapsis %s",
                                    coast / 20, km(end[0]), vr[0], vr[1], OrbitReadout.apsis(ap[0])));
            return true;
        };
    }

    /**
     * (d) A suited player left alone in space for {@code length} ticks: the altitude drops by −v₀·t + ½ g t² (±30 %),
     * v₀ being the radial speed when the measurement starts (the frame has been falling since the player was parked).
     */
    static Phase evaFall(int length) {
        double[] start = {Double.NaN, 0}, startTime = {0}, v0 = {0};
        return (mc, t) -> {
            double alt = earthAltitude(mc);
            NavState nav = mc.player.getAttached(Galactica.NAV);
            if (Double.isNaN(alt) || nav == null) {
                if (t > 40) {
                    Galactica.LOG.warn("[autotest] FAIL EVA gravity: not in space near Earth");
                    return true;
                }
                return false;
            }
            if (Double.isNaN(start[0])) {
                OrbitReadout o = OrbitReadout.current(mc);
                if (o == null) return false;
                start[0] = alt;
                startTime[0] = nav.timeSeconds();
                v0[0] = o.vertical();
                return false;
            }
            if (t < length) return false;
            double elapsed = nav.timeSeconds() - startTime[0];
            double r = alt + 6.371e6;
            double g = dev.galactica.science.Units.G * dev.galactica.science.Units.EARTH_MASS / (r * r);
            double expected = -v0[0] * elapsed + 0.5 * g * elapsed * elapsed, fell = start[0] - alt;
            boolean ok = fell > 0.7 * expected && fell < 1.3 * expected && mc.player.getVehicle() == null;
            Galactica.LOG.info(String.format(Locale.ROOT,
                    "[autotest] %s EVA pulled down by Earth: fell %.0f m in %.1f s from %s (-v0 t + 1/2 g t^2 = %.0f m, v0 = %.1f m/s, g = %.2f m/s^2)",
                    ok ? "PASS" : "FAIL", fell, elapsed, km(start[0]), expected, v0[0], g));
            return true;
        };
    }
}
