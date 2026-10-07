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
 *   <li>{@link #orbit}: a ship given circular speed at 400 km keeps its altitude within ±5 % for a minute, on a conic
 *       whose apsides stay within ±2 % and whose energy drifts under 0.1 %;</li>
 *   <li>{@link #approach}: from 2,000 km, flying at Earth (main engine, or the warp drive) brings the altitude down
 *       and ends in the entry switch within a time limit, the player and ship surviving it — the reported bug was Earth
 *       receding as one flew at it;</li>
 *   <li>{@link #takeOff}: a ship climbing out of the Overworld and left alone (assist off) coasts ballistically under
 *       Earth's g and falls back instead of watching Earth run away at 29.8 km/s;</li>
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
        Runtime rt = Runtime.getRuntime();
        String heap = String.format(Locale.ROOT, "heap %d/%d MB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        return o == null ? "no readout, " + heap : String.format(Locale.ROOT, "HUD: %s alt %s v %s Pe %s Ap %s; %s", o.body(), km(o.altitude()),
                OrbitReadout.speed(o.speed()), OrbitReadout.apsis(o.periapsis()), OrbitReadout.apsis(o.apoapsis()), heap);
    }

    /**
     * (c) Left alone, flight assist off, on a circular orbit at {@code target} metres for {@code length} ticks. Passes
     * when the altitude stays within ±5 %, and the conic is really that circular orbit: periapsis and apoapsis both
     * within ±2 % of the target and the specific orbital energy v²/2 − μ/r drifting under 0.1 %. A craft with no
     * tangential speed would fall ½ g t² ≈ 15.7 km in a minute, inside ±5 %, but its periapsis is an impact, so it
     * fails.
     */
    static Phase orbit(double target, int length) {
        double[] range = {Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] apsides = {Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] energy = {Double.NaN, Double.NaN};
        int[] missed = {0};
        double mu = dev.galactica.science.Units.G * dev.galactica.science.Units.EARTH_MASS;
        return (mc, t) -> {
            Starship ship = ShipClient.piloted(mc);
            // Flight assist brakes relative to the planet, orbital speed included: coasting on an orbit is assist off.
            if (t == 0 && ship != null && ship.coupled()) ship.toggleCoupled();
            double alt = earthAltitude(mc);
            OrbitReadout o = OrbitReadout.current(mc);
            if (Double.isNaN(alt) || o == null) missed[0]++;
            else {
                range[0] = Math.min(range[0], alt);
                range[1] = Math.max(range[1], alt);
                apsides[0] = Math.min(apsides[0], o.periapsis());
                apsides[1] = Math.max(apsides[1], o.apoapsis());
                double e = o.speed() * o.speed() / 2 - mu / (o.altitude() + 6.371e6);
                if (Double.isNaN(energy[0])) energy[0] = e;
                energy[1] = e;
            }
            if (t % 200 == 0) Galactica.LOG.info("[autotest] orbit t={} altitude {} ({})", t, km(alt), readout(mc));
            if (t < length) return false;
            double drift = Math.abs(energy[1] / energy[0] - 1);
            boolean band = range[0] > 0.95 * target && range[1] < 1.05 * target;
            boolean conic = apsides[0] > 0.98 * target && apsides[1] < 1.02 * target;
            boolean ok = missed[0] == 0 && band && conic && drift < 1e-3;
            Galactica.LOG.info(String.format(Locale.ROOT,
                    "[autotest] %s circular orbit at %s: altitude %s .. %s over %d s (±5 %% allowed; free fall would drop %s), "
                            + "Pe >= %s Ap <= %s (±2 %% allowed), energy drift %.4f %% (< 0.1 %%), %d ticks without readout; %s",
                    ok ? "PASS" : "FAIL", km(target), km(range[0]), km(range[1]), length / 20,
                    km(0.5 * mu / Math.pow(6.371e6 + target, 2) * Math.pow(length / 20.0, 2)), km(apsides[0]), km(apsides[1]),
                    drift * 100, missed[0], readout(mc)));
            return true;
        };
    }

    /**
     * (b) Flies at Earth from where the craft is: the main engine with the ship's nose on Earth's centre, or the warp
     * drive (sprint) for a suited creative player looking at it. Passes when the entry switch happens within
     * {@code limit} ticks, the altitude only went down on the way, and two seconds after the switch the player is alive,
     * a pilot is still at the controls with the hull it had in space, and the arrival speed is within the flight
     * model's block cap: the main-engine run enters at km/s relative to Earth, far above what the switch used to see.
     */
    static Phase approach(String how, boolean warp, int limit) {
        double[] first = {Double.NaN}, last = {Double.NaN}, entrySpeed = {Double.NaN}, hull = {Double.NaN}, arrival = {0};
        int[] climbs = {0}, enteredAt = {-1};
        return (mc, t) -> {
            if (enteredAt[0] < 0 && inSpace(mc)) {
                Starship ship = ShipClient.piloted(mc);
                if (warp) {
                    mc.player.getAbilities().flying = true;
                    mc.options.keySprint.setDown(true);
                    mc.player.setSprinting(true);
                } else {
                    // Assist on, as a pilot flies: it measures drift against Earth, not against the falling frame.
                    if (t == 0 && ship != null && !ship.coupled()) ship.toggleCoupled();
                    if (t % 100 == 0) aimShipAtEarth(mc);
                    mc.options.keyUp.setDown(true);
                }
                if (ship != null) hull[0] = ship.hull();
                double alt = earthAltitude(mc);
                if (!Double.isNaN(alt)) {
                    if (Double.isNaN(first[0])) first[0] = alt;
                    if (!Double.isNaN(last[0]) && alt > last[0] + 50) climbs[0]++;
                    last[0] = alt;
                }
                OrbitReadout o = OrbitReadout.current(mc);
                if (o != null) entrySpeed[0] = o.speed();
                if (t % (warp ? 20 : 200) == 0) Galactica.LOG.info("[autotest] approach ({}) t={} altitude {} ({})", how, t, km(alt), readout(mc));
                return t >= limit && finish(how, limit, t, first, last, climbs, entrySpeed, hull, arrival, false, mc);
            }
            if (enteredAt[0] < 0) {
                enteredAt[0] = t;
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
            }
            Entity root = mc.player.getRootVehicle();
            arrival[0] = Math.max(arrival[0], root.getDeltaMovement().length() * 20);
            if (t - enteredAt[0] < 40) return false;
            return finish(how, limit, enteredAt[0], first, last, climbs, entrySpeed, hull, arrival, true, mc);
        };
    }

    private static boolean finish(String how, int limit, int t, double[] first, double[] last, int[] climbs, double[] entrySpeed,
                                  double[] hull, double[] arrival, boolean entered, Minecraft mc) {
        Starship ship = ShipClient.piloted(mc);
        boolean alive = mc.player.isAlive() && mc.player.getHealth() > 0;
        boolean shipOk = Double.isNaN(hull[0]) || ship != null && ship.hull() >= hull[0];
        boolean sane = arrival[0] <= dev.galactica.ship.ShipFlightModel.MAX_SPEED * 1.01;
        boolean ok = entered && climbs[0] == 0 && first[0] > last[0] && alive && shipOk && sane;
        Galactica.LOG.info(String.format(Locale.ROOT,
                "[autotest] %s approach to Earth by %s: from %s down to %s, %s in %.1f s (limit %d s), climbing ticks %d; "
                        + "entered at %s relative to Earth, arrived at %.0f m/s (cap %.0f), player %s, %s",
                ok ? "PASS" : "FAIL", how, km(first[0]), km(last[0]), entered ? "entered " + mc.level.dimension().identifier() : "no entry",
                t / 20.0, limit / 20, climbs[0], OrbitReadout.speed(entrySpeed[0]), arrival[0], dev.galactica.ship.ShipFlightModel.MAX_SPEED,
                alive ? "alive" : "DEAD", Double.isNaN(hull[0]) ? "on EVA" : ship == null ? "ship LOST"
                        : String.format(Locale.ROOT, "hull %.0f -> %.0f", hull[0], ship.hull())));
        return true;
    }

    /**
     * (a) From the Overworld in a ship: stopped ten rows under the take-off row, nose straight up, main engine until the
     * switch (~280 m/s, an Overworld block being ~10 m), then hands off with flight assist off (on, it would hover the
     * ship against Earth). Passes when the craft reached space, stayed within 200 km of Earth, and coasted
     * ballistically: its radial speed fell at Earth's g (measured over the coast, ±30 %), until it fell back into the
     * entry switch or, by the end, was on a bound climb (finite apoapsis under 200 km). Before the fix Earth receded at
     * ~1.5 km per tick.
     */
    static Phase takeOff(int coastTicks) {
        int[] leftAt = {-1}, backAt = {-1};
        double[] max = {0}, end = {Double.NaN}, vr = {Double.NaN, Double.NaN}, vrTime = {Double.NaN, Double.NaN}, ap = {Double.NaN};
        return (mc, t) -> {
            Starship ship = ShipClient.piloted(mc);
            if (t == 0) {
                if (ship == null || mc.level.dimension() != Level.OVERWORLD) {
                    Galactica.LOG.warn("[autotest] FAIL take-off: not piloting a ship in the Overworld ({})", mc.level.dimension().identifier());
                    return true;
                }
                stopShip(mc, ship);
                moveShip(mc, ship, dev.galactica.planet.PlanetEntry.exitY(mc.level) - 10);
                setAttitude(mc, ship, new Quaternionf().rotationX((float) Math.toRadians(-90)));
                Galactica.LOG.info("[autotest] take-off: nose up at y={}", String.format(Locale.ROOT, "%.0f", ship.getY()));
            }
            if (leftAt[0] < 0) {
                if (inSpace(mc)) {
                    leftAt[0] = t;
                    mc.options.keyUp.setDown(false);
                    if (ship != null && ship.coupled()) ship.toggleCoupled();
                    Galactica.LOG.info("[autotest] take-off: in space after {} ticks; hands off, assist {} ({})", t,
                            ship != null && ship.coupled() ? "on" : "off", readout(mc));
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
            if (backAt[0] < 0) {
                double alt = earthAltitude(mc);
                if (!Double.isNaN(alt)) {
                    max[0] = Math.max(max[0], alt);
                    end[0] = alt;
                }
                OrbitReadout o = OrbitReadout.current(mc);
                NavState nav = mc.player.getAttached(Galactica.NAV);
                if (o != null && nav != null && coast >= 20) {
                    if (Double.isNaN(vr[0])) {
                        vr[0] = o.vertical();
                        vrTime[0] = nav.timeSeconds();
                    }
                    vr[1] = o.vertical();
                    vrTime[1] = nav.timeSeconds();
                    ap[0] = o.apoapsis();
                }
                if (coast % 40 == 0) Galactica.LOG.info("[autotest] take-off coast t={} altitude {} ({})", coast, km(alt), readout(mc));
            }
            if (backAt[0] < 0 && coast < coastTicks) return false;
            double span = vrTime[1] - vrTime[0];
            double gMeasured = span > 2 ? (vr[0] - vr[1]) / span : Double.NaN;
            double r = (Double.isNaN(end[0]) ? 0 : end[0]) + 6.371e6;
            double g = dev.galactica.science.Units.G * dev.galactica.science.Units.EARTH_MASS / (r * r);
            boolean ballistic = gMeasured > 0.7 * g && gMeasured < 1.3 * g;
            boolean bounded = max[0] < 200e3;
            boolean falling = Double.isFinite(ap[0]) && ap[0] < 200e3 && vr[1] < vr[0];
            boolean ok = bounded && ballistic && (backAt[0] >= 0 || falling);
            Galactica.LOG.info(String.format(Locale.ROOT,
                    "[autotest] %s take-off from Earth, hands off, assist off: highest %s, radial speed %.0f -> %.0f m/s over %.1f s "
                            + "= %.2f m/s^2 of pull (g = %.2f, ±30 %%), %s",
                    ok ? "PASS" : "FAIL", km(max[0]), vr[0], vr[1], span, gMeasured, g,
                    backAt[0] >= 0 ? String.format(Locale.ROOT, "fell back and re-entered after %.1f s", backAt[0] / 20.0)
                            : String.format(Locale.ROOT, "still in space after %d s at %s, apoapsis %s", coast / 20, km(end[0]),
                                    OrbitReadout.apsis(ap[0]))));
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
