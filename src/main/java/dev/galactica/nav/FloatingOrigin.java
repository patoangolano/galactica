package dev.galactica.nav;

import dev.galactica.Galactica;
import dev.galactica.science.Units;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Floating-origin recentre as one explicit, ordered event.
 *
 * <p>A player's craft (their root vehicle with every rider, or the player alone on EVA) that strays more than
 * {@link #LIMIT} blocks from block (0, 0, 0) of the space dimension is moved back by a whole number of blocks and
 * the offset is folded into the riders' galactic origins. Nothing goes through a vanilla teleport: the pilot's
 * client is authoritative for the ship, and a teleport packet would snap its prediction back to the server's
 * (older) position while the origin arrived separately in the NAV attachment sync, so the hull, the camera and the
 * sky jumped against each other.
 *
 * <p>Instead, in one server tick: the craft is moved on the server, every rider's anti-cheat reference positions
 * are moved with it, and each rider gets an {@link OriginShiftPayload} carrying the shift and their new origin
 * (sent before the attachment sync). The client applies both in one tick and acknowledges; until that ack arrives
 * the rider's movement packets are still in the old frame and are translated by the shift
 * ({@code ServerGamePacketListenerOriginMixin}), so the pilot never rubber-bands. One shift per craft is in flight
 * at a time; the next waits for the ack (the ack normally takes one round trip, the craft moves at most 15
 * blocks per tick, so the limit is never meaningfully exceeded).
 */
public final class FloatingOrigin {
    private FloatingOrigin() {}

    /** Recentre when the craft is farther than this from block 0 on any axis. */
    public static final double LIMIT = 1024.0;
    /** A shift the client never acknowledged (mod missing, connection stalled) stops translating after this. */
    private static final int ACK_TIMEOUT_TICKS = 100;

    private record Pending(int seq, Vec3 delta, int sentAt) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static int nextSeq = 1;

    /** Call once from {@code Galactica.onInitialize}. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(OriginShiftPayload.TYPE, OriginShiftPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OriginShiftAckPayload.TYPE, OriginShiftAckPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OriginShiftAckPayload.TYPE, (msg, ctx) -> acknowledge(ctx.player(), msg.seq()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> PENDING.clear());
    }

    /** Per-tick housekeeping: forgets shifts that left space or were never acknowledged. */
    public static void tick(ServerPlayer p) {
        Pending pending = PENDING.get(p.getUUID());
        if (pending == null) return;
        if (p.level().dimension() != Galactica.SPACE) {
            PENDING.remove(p.getUUID());
        } else if (p.level().getServer().getTickCount() - pending.sentAt() > ACK_TIMEOUT_TICKS) {
            Galactica.LOG.warn("Origin shift {} for {} was never acknowledged", pending.seq(), p.getName().getString());
            PENDING.remove(p.getUUID());
        }
    }

    /**
     * The whole-block shift that brings {@code p}'s craft back near block 0, or null when it is inside the limit or
     * a shift cannot happen this tick (one is already in flight for someone aboard, or a vanilla teleport is).
     */
    public static @Nullable Vec3 shiftNeeded(ServerPlayer p) {
        Entity root = p.getRootVehicle();
        if (Math.abs(root.getX()) <= LIMIT && Math.abs(root.getY()) <= LIMIT && Math.abs(root.getZ()) <= LIMIT) return null;
        for (ServerPlayer rider : riders(root)) {
            if (PENDING.containsKey(rider.getUUID()) || ((OriginShiftable) rider.connection).galactica$awaitingTeleport()) return null;
        }
        return new Vec3(-Math.round(root.getX()), -Math.round(root.getY()), -Math.round(root.getZ()));
    }

    /** The galactic origin after folding in a block shift {@code d} (the origin moves opposite to the blocks). */
    public static GalacticPos fold(GalacticPos origin, Vec3 d) {
        double[] g = Frames.mcToGalactic(-d.x, -d.y, -d.z);
        double k = Units.METRES_PER_BLOCK;
        return origin.plusMetres(g[0] * k, g[1] * k, g[2] * k);
    }

    /**
     * Moves {@code leader}'s craft by {@code d} blocks and tells every player aboard, each with their own new origin:
     * the leader's is {@code leaderNew}, the crew's move by the same galactic displacement (as
     * {@code Starship.carryCrew} will write). Call before the NAV attachments are written, so each client sees the
     * payload first and the attachment sync that follows is a no-op.
     */
    public static void apply(ServerPlayer leader, Vec3 d, GalacticPos leaderOld, GalacticPos leaderNew) {
        Entity root = leader.getRootVehicle();
        List<Entity> craft = new ArrayList<>();
        craft.add(root);
        root.getIndirectPassengers().forEach(craft::add);
        for (Entity e : craft) {
            // Whole-tree translation keeps every seat offset exact; xo/xOld move too, so interpolation is unaffected.
            e.setPos(e.getX() + d.x, e.getY() + d.y, e.getZ() + d.z);
            e.xo += d.x;
            e.yo += d.y;
            e.zo += d.z;
            e.xOld += d.x;
            e.yOld += d.y;
            e.zOld += d.z;
        }
        double[] od = leaderNew.metresFrom(leaderOld);
        int now = leader.level().getServer().getTickCount();
        for (ServerPlayer rider : riders(root)) {
            GalacticPos origin = rider == leader ? leaderNew
                    : rider.getAttachedOrCreate(Galactica.NAV).origin().plusMetres(od[0], od[1], od[2]);
            ((OriginShiftable) rider.connection).galactica$shiftReferences(d.x, d.y, d.z);
            rider.level().getChunkSource().move(rider);
            int seq = nextSeq++;
            PENDING.put(rider.getUUID(), new Pending(seq, d, now));
            ServerPlayNetworking.send(rider, new OriginShiftPayload(seq, d.x, d.y, d.z, origin));
        }
    }

    /**
     * The shift still to add to {@code p}'s incoming movement packets (sent before the client applied it), or null.
     */
    public static @Nullable Vec3 pendingDelta(ServerPlayer p) {
        Pending pending = PENDING.get(p.getUUID());
        return pending == null ? null : pending.delta();
    }

    /** A vanilla teleport supersedes the shift: packets until its ack are dropped by vanilla anyway. */
    public static void cancel(ServerPlayer p) {
        PENDING.remove(p.getUUID());
    }

    private static void acknowledge(ServerPlayer p, int seq) {
        Pending pending = PENDING.get(p.getUUID());
        if (pending != null && pending.seq() == seq) PENDING.remove(p.getUUID());
    }

    private static List<ServerPlayer> riders(Entity root) {
        List<ServerPlayer> out = new ArrayList<>();
        if (root instanceof ServerPlayer sp) out.add(sp);
        for (Entity e : root.getIndirectPassengers()) if (e instanceof ServerPlayer sp) out.add(sp);
        return out;
    }
}
