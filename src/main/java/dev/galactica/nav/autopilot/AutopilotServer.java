package dev.galactica.nav.autopilot;

import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.OrbitalFrame;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.nav.target.NavTargets;
import dev.galactica.nav.target.TargetGeometry;
import dev.galactica.net.AutopilotPayload;
import dev.galactica.science.Units;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.Hyperdrive;
import dev.galactica.ship.JumpTargetInfo;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.ship.Starship;
import dev.galactica.star.Star;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The autopilot's plan, owned by the server. Each tick it decides the phase ({@link AutopilotState.Phase}) of every
 * engaged player and syncs it; the pilot's client flies that phase through the ship's own controls (the input record,
 * mouse turns and {@code ShipControlPayload}, all validated as usual), so prediction and server never disagree and no
 * rule is bypassed: warp obeys the governor and the orbital frame, burns spend hydrogen and the Δv budget, jumps go
 * through the hyperdrive's range, exotic-matter cost and spool.
 *
 * <ul>
 *   <li>Another system, exotic matter enough for the {@link JumpPlanner} chain: {@link AutopilotState.Phase#JUMP}, one
 *       hop at a time, replanned after each arrival. Otherwise the warp drive crosses interstellar space.</li>
 *   <li>Inside the system: {@link AutopilotState.Phase#WARP} at the leg's aim point ({@link AutopilotLegs}), released
 *       short of it; a warp into a body's sphere of influence drops out at rest relative to it.</li>
 *   <li>A body: {@link AutopilotState.Phase#INSERT} (circular orbit, checked here from the same relative state the
 *       client steers by), or with {@link AutopilotState.Mode#LAND} {@link AutopilotState.Phase#DESCENT} into the
 *       entry the planet area already runs, then {@link AutopilotState.Phase#LANDING} on the surface level.</li>
 *   <li>It lets go on arrival, on any manual input (the client reports it), when the pilot leaves the seat, when the
 *       target is cleared or lost, when the fuel a phase needs is gone, and when a phase makes no progress.</li>
 * </ul>
 */
public final class AutopilotServer {
    private AutopilotServer() {}

    public static final AttachmentType<AutopilotState> STATE = AttachmentRegistry.create(Galactica.id("autopilot"),
            b -> b.syncWith(AutopilotState.STREAM_CODEC, AttachmentSyncPredicate.all()));

    /** Seconds a phase may run before the autopilot gives up on it. */
    private static final int INSERT_TIMEOUT = 20 * 180, DESCENT_TIMEOUT = 20 * 600, LANDING_TIMEOUT = 20 * 300;
    /** A warp leg must shorten the remaining distance by 1 % within this many ticks. */
    private static final int WARP_STALL_TICKS = 20 * 45;
    /** Ticks an orbit (or a landed ship) must hold before the autopilot calls it arrived. */
    private static final int SETTLE_TICKS = 40;

    /** Server-side bookkeeping of one engaged course. */
    private static final class Run {
        int phaseStart;
        int settled;
        int nextJump;
        int jumpFailures;
        JumpPlanner.Plan plan;
        int planAt = Integer.MIN_VALUE;
        double bestSurface = Double.MAX_VALUE;
        int bestAt;
        double legKey = Double.NaN;
        AutopilotState.Phase announced = AutopilotState.Phase.IDLE;
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();

    public static void init() {
        // Registered from Galactica after NavServer's tick: the phase is judged on the frame NavServer just moved.
    }

    public static AutopilotState state(net.minecraft.world.entity.player.Player p) {
        AutopilotState s = p.getAttached(STATE);
        return s != null ? s : AutopilotState.OFF;
    }

    // ---------------------------------------------------------------- requests

    public static void handle(ServerPlayer p, AutopilotPayload payload) {
        switch (payload.action()) {
            case AutopilotPayload.ENGAGE_ORBIT -> engage(p, AutopilotState.Mode.ORBIT);
            case AutopilotPayload.ENGAGE_LAND -> engage(p, AutopilotState.Mode.LAND);
            case AutopilotPayload.DISENGAGE_MANUAL -> disengage(p, "hud.galactica.autopilot.off.manual");
            default -> disengage(p, "hud.galactica.autopilot.off.requested");
        }
    }

    private static @Nullable Starship pilotShip(ServerPlayer p) {
        return p.getVehicle() instanceof Starship s && s.getControllingPassenger() == p ? s : null;
    }

    public static void engage(ServerPlayer p, AutopilotState.Mode mode) {
        NavTarget t = NavTargets.get(p);
        if (t == null) {
            p.sendOverlayMessage(Component.translatable("hud.galactica.autopilot.no_target"));
            return;
        }
        Starship ship = pilotShip(p);
        if (p.level().dimension() != Galactica.SPACE) {
            p.sendOverlayMessage(Component.translatable("hud.galactica.autopilot.not_in_space"));
            return;
        }
        if (ship == null) {
            if (p.getVehicle() != null) {
                p.sendOverlayMessage(Component.translatable("hud.galactica.autopilot.only_pilot"));
                return;
            }
            // On EVA only the warp drive flies, and it carries a suited player only in creative (Starship.consumeWarpFuel).
            if (!p.isCreative()) {
                p.sendOverlayMessage(Component.translatable("hud.galactica.autopilot.eva_creative"));
                return;
            }
            mode = AutopilotState.Mode.ORBIT;
        }
        if (mode == AutopilotState.Mode.LAND) {
            TargetGeometry.Resolved r = TargetGeometry.resolve(Galactica.GALAXY, t, p.getAttachedOrCreate(Galactica.NAV).timeSeconds());
            if (r == null || r.body() == null || !AutopilotMath.landable(r.body(), ship != null ? ship.spec() : dev.galactica.ship.ShipSpec.KESTREL)) {
                p.sendOverlayMessage(Component.translatable("hud.galactica.autopilot.cannot_land", t.name()));
                return;
            }
        }
        Run run = new Run();
        run.phaseStart = p.level().getServer().getTickCount();
        RUNS.put(p.getUUID(), run);
        p.setAttached(STATE, new AutopilotState(true, AutopilotState.Phase.ALIGN, mode, 0, -1F));
        tell(p, Component.translatable(mode == AutopilotState.Mode.LAND ? "hud.galactica.autopilot.engaged.land"
                : "hud.galactica.autopilot.engaged", t.name()));
    }

    /** Hands control back with a reason (a translation key taking the target name), or silently when not engaged. */
    public static void disengage(ServerPlayer p, String reasonKey, Object... args) {
        RUNS.remove(p.getUUID());
        AutopilotState s = p.getAttached(STATE);
        if (s == null || !s.engaged()) return;
        p.setAttached(STATE, AutopilotState.OFF);
        NavTarget t = NavTargets.get(p);
        Object[] a = args.length > 0 ? args : new Object[] {t != null ? t.name() : "?"};
        tell(p, Component.translatable(reasonKey, a));
        Galactica.LOG.info("Autopilot of {} off: {}", p.getName().getString(), Component.translatable(reasonKey, a).getString());
    }

    /** The pilot and everyone riding with them read the autopilot's messages. */
    private static void tell(ServerPlayer p, Component c) {
        p.sendOverlayMessage(c);
        Entity root = p.getRootVehicle();
        for (Entity e : root.getIndirectPassengers()) if (e instanceof ServerPlayer crew && crew != p) crew.sendOverlayMessage(c);
    }

    // ---------------------------------------------------------------- tick

    public static void tick(MinecraftServer server) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                AutopilotState s = p.getAttached(STATE);
                if (s == null || !s.engaged()) continue;
                try {
                    step(p, s, server.getTickCount());
                } catch (RuntimeException e) {
                    Galactica.LOG.error("Autopilot step failed for {}", p.getName().getString(), e);
                    disengage(p, "hud.galactica.autopilot.off.error");
                }
            }
            RUNS.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.NAV_SERVER, perf);
        }
    }

    private static void step(ServerPlayer p, AutopilotState s, int now) {
        Run run = RUNS.computeIfAbsent(p.getUUID(), u -> {
            Run r = new Run();
            r.phaseStart = now;
            return r;
        });
        NavTarget t = NavTargets.get(p);
        if (t == null) {
            disengage(p, "hud.galactica.autopilot.off.no_target");
            return;
        }
        Starship ship = pilotShip(p);
        if (ship == null && p.getVehicle() != null) {
            disengage(p, "hud.galactica.autopilot.off.left_seat");
            return;
        }
        if (p.level().dimension() != Galactica.SPACE) {
            if (ship != null && s.mode() == AutopilotState.Mode.LAND
                    && (s.phase() == AutopilotState.Phase.DESCENT || s.phase() == AutopilotState.Phase.LANDING)) {
                landingStep(p, s, run, t, ship, now);
            } else {
                disengage(p, "hud.galactica.autopilot.off.left_space");
            }
            return;
        }
        if (ship == null && !p.isCreative()) {
            disengage(p, "hud.galactica.autopilot.eva_creative");
            return;
        }
        if (s.phase() == AutopilotState.Phase.LANDING) {
            // Back in space during a landing (a bounce off the entry, or a take-off): not this autopilot's business.
            disengage(p, "hud.galactica.autopilot.off.left_surface");
            return;
        }
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double time = nav.timeSeconds();
        Entity root = p.getRootVehicle();
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        TargetGeometry.Resolved r = TargetGeometry.resolve(Galactica.GALAXY, t, time);
        if (r == null) {
            disengage(p, "hud.galactica.autopilot.off.target_lost");
            return;
        }
        if (ship != null && ship.hydrogen() <= 0 && s.phase() != AutopilotState.Phase.JUMP) {
            disengage(p, "hud.galactica.autopilot.off.fuel");
            return;
        }
        Neighbourhood nb = NavServer.neighbourhood(p);
        if (s.phase() == AutopilotState.Phase.INSERT && ship != null) {
            insertStep(p, s, run, ship, nb, here, nav, r, now);
            return;
        }
        if (s.phase() == AutopilotState.Phase.DESCENT && ship != null) {
            if (now - run.phaseStart > DESCENT_TIMEOUT) {
                disengage(p, "hud.galactica.autopilot.off.stalled");
                return;
            }
            double alt = here.distanceMetres(r.pos()) - r.radius();
            set(p, s.withProgress(0, Math.sqrt(2 * alt / 18.0) + 80), run);
            return;
        }
        Star cur = nb.systemStar(here);
        boolean inSystem = AutopilotLegs.inTargetSystem(t, r, cur);
        if (ship != null && ship.isSpooling()) {
            set(p, s.withPhase(AutopilotState.Phase.JUMP), run);
            return;
        }
        if (!inSystem && ship != null && ship.exotic() > 0 && run.jumpFailures < 3) {
            if (run.plan == null || now - run.planAt > 200) {
                run.plan = JumpPlanner.plan(here.xPc(), here.yPc(), here.zPc(), r.star(), DriveRules.rangeLy(ship.exotic(), ship.jumpTierLy()),
                        (x, y, z, rad) -> Galactica.GALAXY.starsNear(x, y, z, rad, 20_000));
                run.planAt = now;
            }
            if (run.plan.affordable(ship.exotic())) {
                int hops = run.plan.hops().size();
                set(p, new AutopilotState(true, AutopilotState.Phase.JUMP, s.mode(), hops,
                        (float) AutopilotMath.hyperjumpSeconds(hops)), run);
                if (now >= run.nextJump && nav.speed() == 0) {
                    Star next = run.plan.next();
                    GalacticPos np = Neighbourhood.starPos(next);
                    if (Hyperdrive.autopilotJump(ship, p, new JumpTargetInfo(next.name(), np.xPc(), np.yPc(), np.zPc()))) {
                        run.nextJump = now + DriveRules.SPOOL_TICKS + 20;
                        run.plan = null;
                    } else {
                        run.jumpFailures++;
                        run.nextJump = now + 20;
                    }
                }
                return;
            }
        }

        AutopilotLegs.Leg leg = AutopilotLegs.leg(t, r, cur);
        double surface = leg.surfaceDistance(here);
        if (leg.last() && leg.reached(here) && nav.speed() == 0) {
            if (ship != null && r.body() != null) {
                AutopilotState.Phase next = s.mode() == AutopilotState.Mode.LAND ? AutopilotState.Phase.DESCENT : AutopilotState.Phase.INSERT;
                run.phaseStart = now;
                run.settled = 0;
                set(p, new AutopilotState(true, next, s.mode(), 0, -1F), run);
                return;
            }
            disengage(p, r.body() != null ? "hud.galactica.autopilot.arrived.eva" : "hud.galactica.autopilot.arrived.near",
                    t.name(), distance(surface));
            return;
        }
        // Progress watchdog: a leg that stops shortening (blocked heading, a drive held off) is handed back.
        double legKey = leg.radius() + (leg.last() ? 0.5 : 0);
        if (legKey != run.legKey) {
            run.legKey = legKey;
            run.bestSurface = Double.MAX_VALUE;
            run.bestAt = now;
        }
        if (surface < run.bestSurface * 0.99) {
            run.bestSurface = surface;
            run.bestAt = now;
        } else if (now - run.bestAt > WARP_STALL_TICKS) {
            disengage(p, "hud.galactica.autopilot.off.stalled");
            return;
        }
        Vec3 look = p.getLookAngle();
        double[] dir = leg.aim().metresFrom(here);
        double[] lg = Frames.mcToGalactic(look.x, look.y, look.z);
        double cos = (dir[0] * lg[0] + dir[1] * lg[1] + dir[2] * lg[2]) / Math.max(1e-30, Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]));
        AutopilotState.Phase phase = nav.speed() == 0 && cos < Math.cos(Math.toRadians(AutopilotMath.WARP_CONE_DEG))
                ? AutopilotState.Phase.ALIGN : AutopilotState.Phase.WARP;
        double eta = AutopilotMath.warpSeconds(Math.max(0, surface), leg.arrival(), nav.maxSpeed());
        set(p, new AutopilotState(true, phase, s.mode(), 0, (float) eta), run);
    }

    /** Orbit insertion: arrived once the conic is near-circular above the entry shell for {@link #SETTLE_TICKS}. */
    private static void insertStep(ServerPlayer p, AutopilotState s, Run run, Starship ship, Neighbourhood nb, GalacticPos here,
                                   NavState nav, TargetGeometry.Resolved r, int now) {
        if (now - run.phaseStart > INSERT_TIMEOUT) {
            disengage(p, "hud.galactica.autopilot.off.insert_failed");
            return;
        }
        Vec3 dm = ship.getDeltaMovement();
        double[] own = Frames.mcToGalactic(dm.x * 20, dm.y * 20, dm.z * 20);
        double[][] rv = AutopilotKinematics.relative(nb, here, nav, own, r, nav.timeSeconds());
        double[] apsides = OrbitalFrame.apsides(rv[0], rv[1], r.mu());
        double floor = r.radius() + 1.05 * AutopilotMath.entryAltitude(r.body());
        run.settled = AutopilotMath.orbitReached(apsides, floor) ? run.settled + 1 : 0;
        double[] want = AutopilotMath.circularVelocity(rv[0], rv[1], r.mu(), new double[] {0, 0, 1});
        double dv = Math.sqrt(sq(want[0] - rv[1][0]) + sq(want[1] - rv[1][1]) + sq(want[2] - rv[1][2]));
        if (run.settled >= SETTLE_TICKS) {
            disengage(p, "hud.galactica.autopilot.arrived.orbit", r.body().name(),
                    distance(apsides[0] - r.radius()), distance(apsides[1] - r.radius()));
            Galactica.LOG.info("Autopilot orbit of {}: Pe {} m Ap {} m (r {} m)", r.body().name(), Math.round(apsides[0] - r.radius()),
                    Math.round(apsides[1] - r.radius()), Math.round(r.radius()));
            return;
        }
        set(p, s.withProgress(0, dv / Math.max(0.1, ship.spec().mainAccel()) + SETTLE_TICKS / 20.0), run);
    }

    /** On the target's surface level: arrived once the ship rests on the ground, still, for {@link #SETTLE_TICKS}. */
    private static void landingStep(ServerPlayer p, AutopilotState s, Run run, NavTarget t, Starship ship, int now) {
        if (s.phase() == AutopilotState.Phase.DESCENT) {
            run.phaseStart = now;
            run.settled = 0;
            set(p, s.withPhase(AutopilotState.Phase.LANDING), run);
            return;
        }
        if (now - run.phaseStart > LANDING_TIMEOUT) {
            disengage(p, "hud.galactica.autopilot.off.stalled");
            return;
        }
        if (ship.hydrogen() <= 0) {
            disengage(p, "hud.galactica.autopilot.off.fuel");
            return;
        }
        boolean still = ship.getDeltaMovement().length() * 20 < 1.0;
        run.settled = (ship.onGround() || ship.restingContact()) && still ? run.settled + 1 : 0;
        if (run.settled >= SETTLE_TICKS) {
            disengage(p, "hud.galactica.autopilot.arrived.landed", t.name());
            return;
        }
        double h = Math.max(0, ship.getY() - ship.level().getMinY());
        set(p, s.withProgress(0, Math.min(600, h / 20.0)), run);
    }

    /** Writes the state when it changed (the attachment syncs on every write) and announces a new phase once. */
    private static void set(ServerPlayer p, AutopilotState next, Run run) {
        AutopilotState cur = p.getAttached(STATE);
        boolean etaMoved = cur == null || Math.abs(cur.eta() - next.eta()) >= Math.max(1F, 0.02F * Math.abs(next.eta()));
        if (cur == null || cur.phase() != next.phase() || cur.mode() != next.mode() || cur.hops() != next.hops()
                || cur.engaged() != next.engaged() || etaMoved) {
            p.setAttached(STATE, next);
        }
        if (next.phase() != run.announced && next.phase() != AutopilotState.Phase.ALIGN) {
            run.announced = next.phase();
            NavTarget t = NavTargets.get(p);
            tell(p, Component.translatable("hud.galactica.autopilot.now", Component.translatable(next.phase().translationKey()),
                    t != null ? t.name() : "?"));
            Galactica.LOG.info("Autopilot of {}: {} ({})", p.getName().getString(), next.phase(), t != null ? t.name() : "?");
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** "512 km", "0.42 AU", "3.1 ly". */
    public static String distance(double m) {
        if (m < 1e3) return String.format(java.util.Locale.ROOT, "%.0f m", m);
        if (m < 1e9) return String.format(java.util.Locale.ROOT, "%,.0f km", m / 1e3);
        if (m < 0.05 * Units.LIGHT_YEAR) return String.format(java.util.Locale.ROOT, "%.2f AU", m / Units.AU);
        return String.format(java.util.Locale.ROOT, "%.2f ly", m / Units.LIGHT_YEAR);
    }
}
