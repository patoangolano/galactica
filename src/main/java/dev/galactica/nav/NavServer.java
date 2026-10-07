package dev.galactica.nav;

import dev.galactica.Galactica;
import dev.galactica.science.Units;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of space travel.
 *
 * <p><b>Warp drive.</b> Flying in space and holding the sprint key spools the drive: speed grows 15 % per
 * tick from 1 km/s, so full warp (10 pc/s) is ~12 s away. A governor caps speed at half the
 * distance per second to the nearest surface ahead (35° cone), so the ship decelerates smoothly on
 * arrival, as in Elite's supercruise, while leaving a planet is unrestricted. Sneaking cuts the drive. While warping only the galactic origin moves;
 * the player's block position is untouched.
 *
 * <p><b>Orbital frame.</b> Every tick the frame co-moves with the body whose sphere of influence the craft is in,
 * carries the craft's velocity relative to it and falls under its gravity ({@link OrbitalFrame}), so a planet no
 * longer runs away at its orbital speed once a craft leaves it, and a craft at the right speed orbits. While the warp
 * drive moves, the frame is frozen (no gravity, velocity kept), and a warp into another body's sphere arrives at rest
 * relative to that body.
 *
 * <p><b>Floating origin.</b> A craft more than {@link #ORIGIN_LIMIT} blocks from block (0,0,0) of its
 * frame is moved back to it and the offset is folded into {@link NavState#origin()} ({@link FloatingOrigin}), so
 * block coordinates stay small enough for float rendering and chunk loading anywhere in the galaxy.
 */
public final class NavServer {
    private NavServer() {}

    public static final double ORIGIN_LIMIT = FloatingOrigin.LIMIT;
    private static final double TICK_SECONDS = 0.05;
    private static final double SPOOL_MIN = 1.0e3;
    private static final double SPOOL_RATE = 1.15;
    private static final net.minecraft.resources.Identifier ZERO_G = Galactica.id("zero_g");

    private static final Map<UUID, Neighbourhood> NEIGHBOURHOODS = new HashMap<>();

    public static Neighbourhood neighbourhood(ServerPlayer p) {
        return NEIGHBOURHOODS.computeIfAbsent(p.getUUID(), u -> new Neighbourhood(Galactica.GALAXY));
    }

    public static void tick(MinecraftServer server) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            tickTimed(server);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.NAV_SERVER, perf);
        }
    }

    private static void tickTimed(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean inSpace = p.level().dimension() == Galactica.SPACE;
            applyZeroG(p, inSpace);
            FloatingOrigin.tick(p);
            FloatingOrigin.syncViewRadius(p);
            dev.galactica.planet.PlanetEntry.tickSurface(p);
            if (inSpace) {
                tickInSpace(p);
                if (p.level().dimension() == Galactica.SPACE) dev.galactica.planet.PlanetEntry.tryEnter(p);
            }
        }
        NEIGHBOURHOODS.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
    }

    private static void tickInSpace(ServerPlayer p) {
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double t = nav.timeSeconds() + TICK_SECONDS;

        if (!p.getAbilities().mayfly) {
            p.getAbilities().mayfly = true;
            p.onUpdateAbilities();
        }
        p.resetFallDistance();
        // Passengers ride in the pilot's frame; Starship.carryCrew moves their origin with the pilot's.
        if (dev.galactica.ship.Starship.isCrew(p)) {
            // Nobody at the controls: the first player aboard still leads the frame (orbit, recentre; no drive).
            if (dev.galactica.ship.Starship.leadsCraft(p)) {
                FloatingOrigin.keepCraftLoaded(p);
                advance(p, nav, t, 0, null);
            }
            return;
        }
        FloatingOrigin.keepCraftLoaded(p);

        Entity root = p.getRootVehicle();
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        double speed = nav.speed();
        // Raw key state, not isSprinting(): vanilla only counts as sprinting while moving forward,
        // and the drive should spool with the key alone, whatever direction the player drifts.
        var input = p.getLastClientInput();
        if (input.shift()) {
            speed = 0;
        } else if (input.sprint() && (p.getAbilities().flying || dev.galactica.ship.Starship.isPiloting(p))) {
            Vec3 look = p.getLookAngle();
            double[] dir = Frames.mcToGalactic(look.x, look.y, look.z);
            double governor = Math.max(SPOOL_MIN, 0.5 * neighbourhood(p).distanceToNearestSurfaceAhead(here, t, dir));
            speed = Math.min(Math.min(nav.maxSpeed(), governor), Math.max(SPOOL_MIN, speed * SPOOL_RATE));
        } else {
            speed = speed < SPOOL_MIN ? 0 : speed * 0.8; // drop out of warp over ~1 s
        }

        if (speed > 0 && !dev.galactica.ship.Starship.consumeWarpFuel(p, speed)) speed = 0;

        double[] warpStep = null;
        if (speed > 0) {
            Vec3 look = p.getLookAngle();
            double[] g = Frames.mcToGalactic(look.x, look.y, look.z);
            double step = speed * TICK_SECONDS;
            warpStep = new double[] {g[0] * step, g[1] * step, g[2] * step};
        }
        advance(p, nav, t, speed, warpStep);
    }

    /**
     * Moves the leader's frame one tick and writes it: the patched-conic frame step ({@link OrbitalFrame#advance}: the
     * dominant body's own motion, the frame velocity, its gravity), the warp step, then the floating-origin recentre.
     */
    private static void advance(ServerPlayer p, NavState nav, double t, double speed, double[] warpStep) {
        Entity root = p.getRootVehicle();
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        OrbitalFrame.Advance frame = OrbitalFrame.advance(neighbourhood(p), here, nav.frameVelocity(), nav.anchor(),
                nav.timeSeconds(), TICK_SECONDS, speed > 0);
        double[] d = frame.originDelta();
        GalacticPos origin = nav.origin().plusMetres(d[0], d[1], d[2]);
        if (warpStep != null) origin = origin.plusMetres(warpStep[0], warpStep[1], warpStep[2]);

        // Floating origin: bring the craft back near block 0 and fold the offset into the galactic origin. The shift
        // reaches the riders' clients as one ordered event (FloatingOrigin), before the attachment sync below.
        Vec3 shift = FloatingOrigin.shiftNeeded(p);
        if (shift != null) origin = FloatingOrigin.fold(origin, shift);

        double[] v = frame.frameVelocity();
        NavState next = new NavState(origin, speed, nav.maxSpeed(), t, v[0], v[1], v[2], frame.anchor(), nav.rebaseAck());
        if (shift != null) FloatingOrigin.apply(p, shift, nav.origin(), next.origin(), t);
        // Written (and synced to the owner) every tick: the frame co-moves with the planets and falls, so the origin and
        // the clock change every tick and must reach the client together. (Writing only on change used to leave the
        // clock of an idle craft advancing one tick per second.)
        p.setAttached(Galactica.NAV, next);
        dev.galactica.ship.Starship.carryCrew(p, nav.origin(), next);
    }

    private static void applyZeroG(ServerPlayer p, boolean inSpace) {
        AttributeInstance g = p.getAttribute(Attributes.GRAVITY);
        if (g == null) return;
        if (inSpace && !g.hasModifier(ZERO_G)) {
            g.addOrUpdateTransientModifier(new AttributeModifier(ZERO_G, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!inSpace && g.hasModifier(ZERO_G)) {
            g.removeModifier(ZERO_G);
        }
    }

    /** Sends a player to their space frame, remembering where they left from. */
    public static boolean launch(ServerPlayer p) {
        ServerLevel space = p.level().getServer().getLevel(Galactica.SPACE);
        if (space == null) return false;
        if (p.level().dimension() != Galactica.SPACE) {
            p.setAttached(Galactica.RETURN_POINT, new ReturnPoint(p.level().dimension().identifier(), p.getX(), p.getY(), p.getZ()));
            // Whatever orbit the frame carried when this player last left space is long over.
            p.setAttached(Galactica.NAV, p.getAttachedOrCreate(Galactica.NAV).resetFrame());
        }
        boolean fromSurface = p.level().dimension() != Galactica.SPACE;
        p.teleport(new TeleportTransition(space, new Vec3(0.5, 0, 0.5), Vec3.ZERO, p.getYRot(), p.getXRot(),
                TeleportTransition.PLAY_PORTAL_SOUND));
        p.getAbilities().mayfly = true;
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        if (fromSurface) clearOfEntryShell(p);
        return true;
    }

    /**
     * After a launch: a frame left where an entry switched (a few km over a planet) would drop the player straight back
     * in, so a launch that lands inside twice a body's entry altitude is raised along the radial to that height and
     * put on a circular orbit there.
     */
    private static void clearOfEntryShell(ServerPlayer p) {
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        GalacticPos here = Frames.playerPos(nav, p.getX(), p.getY(), p.getZ());
        for (Neighbourhood.Located l : neighbourhood(p).locateBodies(here, nav.timeSeconds())) {
            var b = l.body();
            if (!b.type().solidSurface) continue;
            double safe = 2 * dev.galactica.planet.PlanetEntry.entryAltitude(b);
            double[] r = here.metresFrom(l.pos());
            double rm = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
            if (rm - b.radiusMetres() >= safe || rm <= 0) continue;
            double k = (b.radiusMetres() + safe) / rm;
            moveTo(p, l.pos().plusMetres(r[0] * k, r[1] * k, r[2] * k));
            circularize(p);
            return;
        }
    }

    /** Returns a player to where they launched from (or the overworld spawn). */
    public static void land(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        ReturnPoint rp = p.getAttached(Galactica.RETURN_POINT);
        ServerLevel dest = null;
        Vec3 pos = null;
        if (rp != null) {
            dest = server.getLevel(ResourceKey.create(Registries.DIMENSION, rp.dimension()));
            pos = new Vec3(rp.x(), rp.y(), rp.z());
        }
        if (dest == null) {
            dest = server.getLevel(Level.OVERWORLD);
            pos = Vec3.atBottomCenterOf(dest.getRespawnData().pos());
        }
        p.setAttached(Galactica.NAV, p.getAttachedOrCreate(Galactica.NAV).withSpeed(0));
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
        }
        p.teleport(new TeleportTransition(dest, pos, Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.PLAY_PORTAL_SOUND));
    }

    /** Re-anchors the player's frame so that they stand at {@code target}, at rest relative to the body that dominates there. */
    public static void moveTo(ServerPlayer p, GalacticPos target) {
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double[] g = Frames.mcToGalactic(p.getX(), p.getY(), p.getZ());
        p.setAttached(Galactica.NAV, nav.withOrigin(target.plusMetres(-g[0], -g[1], -g[2])).withSpeed(0).resetFrame());
    }

    /**
     * Puts the player's craft on a circular orbit through where it is now, around the body that dominates there: the
     * frame takes the circular velocity (minus the craft's own block velocity), prograde about galactic north (or
     * about galactic x when the craft sits over a pole of that axis). Returns the body, or null outside a system.
     */
    public static OrbitalFrame.Dominant circularize(ServerPlayer p) {
        if (p.level().dimension() != Galactica.SPACE) return null;
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        Entity root = p.getRootVehicle();
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        OrbitalFrame.Dominant d = OrbitalFrame.dominant(neighbourhood(p), here, nav.timeSeconds());
        if (d == null) return null;
        double[] r = here.metresFrom(d.pos());
        double rm = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        double[] normal = Math.abs(r[2]) < 0.9 * rm ? new double[] {0, 0, 1} : new double[] {1, 0, 0};
        double[] v = OrbitalFrame.circularVelocity(r, d.mu(), normal);
        Vec3 dm = root.getDeltaMovement();
        double[] own = Frames.mcToGalactic(dm.x * 20, dm.y * 20, dm.z * 20);
        p.setAttached(Galactica.NAV, nav.withSpeed(0).withFrame(new double[] {v[0] - own[0], v[1] - own[1], v[2] - own[2]}, d.id()));
        dev.galactica.ship.Starship.carryCrew(p, nav.origin(), p.getAttachedOrCreate(Galactica.NAV));
        return d;
    }

    public static String formatSpeed(double ms) {
        if (ms <= 0) return "idle";
        if (ms < 1e5) return String.format("%.1f km/s", ms / 1e3);
        double c = ms / Units.C;
        if (c < 1e4) return String.format("%.3g c", c);
        return String.format("%.3g c (%.3g pc/s)", c, ms / Units.PARSEC);
    }
}
