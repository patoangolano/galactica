package dev.galactica.nav;

import dev.galactica.Galactica;
import dev.galactica.science.Units;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean inSpace = p.level().dimension() == Galactica.SPACE;
            applyZeroG(p, inSpace);
            FloatingOrigin.tick(p);
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
        if (dev.galactica.ship.Starship.isCrew(p)) return;

        GalacticPos here = Frames.playerPos(nav, p.getX(), p.getY(), p.getZ());
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

        GalacticPos origin = nav.origin();
        if (speed > 0) {
            Vec3 look = p.getLookAngle();
            double[] g = Frames.mcToGalactic(look.x, look.y, look.z);
            double step = speed * TICK_SECONDS;
            origin = origin.plusMetres(g[0] * step, g[1] * step, g[2] * step);
        }

        // Floating origin: bring the craft back near block 0 and fold the offset into the galactic origin. The shift
        // reaches the riders' clients as one ordered event (FloatingOrigin), before the attachment sync below.
        Vec3 shift = FloatingOrigin.shiftNeeded(p);
        if (shift != null) origin = FloatingOrigin.fold(origin, shift);

        NavState next = new NavState(origin, speed, nav.maxSpeed(), t);
        if (shift != null) FloatingOrigin.apply(p, shift, nav.origin(), next.origin());
        // Attachment writes are synced to the owner, so only write when something a client can see changed.
        if (speed != 0 || nav.speed() != 0 || !origin.equals(nav.origin()) || (p.tickCount % 20) == 0) {
            p.setAttached(Galactica.NAV, next);
            dev.galactica.ship.Starship.carryCrew(p, nav.origin(), next);
        }
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
        }
        p.teleport(new TeleportTransition(space, new Vec3(0.5, 0, 0.5), Vec3.ZERO, p.getYRot(), p.getXRot(),
                TeleportTransition.PLAY_PORTAL_SOUND));
        p.getAbilities().mayfly = true;
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        return true;
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

    /** Re-anchors the player's frame so that they stand at {@code target}. */
    public static void moveTo(ServerPlayer p, GalacticPos target) {
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double[] g = Frames.mcToGalactic(p.getX(), p.getY(), p.getZ());
        p.setAttached(Galactica.NAV, nav.withOrigin(target.plusMetres(-g[0], -g[1], -g[2])).withSpeed(0));
    }

    public static String formatSpeed(double ms) {
        if (ms <= 0) return "idle";
        if (ms < 1e5) return String.format("%.1f km/s", ms / 1e3);
        double c = ms / Units.C;
        if (c < 1e4) return String.format("%.3g c", c);
        return String.format("%.3g c (%.3g pc/s)", c, ms / Units.PARSEC);
    }
}
