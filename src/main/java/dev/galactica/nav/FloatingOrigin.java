package dev.galactica.nav;

import dev.galactica.Galactica;
import dev.galactica.science.Units;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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

    /** Recentre when the craft is farther than this from block 0 horizontally. */
    public static final double LIMIT = 1024.0;
    /**
     * Recentre when the craft is farther than this from block 0 vertically. A vertical shift lands in the same chunk
     * columns, which are already loaded, so it never waits and can be small; that is what lets galactica:space be only
     * 1 536 blocks tall (96 sections per column instead of 254), at most 15 blocks per tick past this between shifts.
     */
    public static final double LIMIT_Y = 192.0;
    /**
     * A vertical recentre moves only this craft, so anything else nearby (another player, another ship, blocks)
     * would jump {@code ~LIMIT_Y} blocks relative to it, well inside the 160-block tracking range. While anything
     * like that is within {@link #GUARD_RADIUS} horizontally (at any height), the vertical recentre waits, up to
     * this height; past it the craft is close to the build limit (±768) and is recentred regardless.
     */
    public static final double HARD_LIMIT_Y = 704.0;
    /**
     * An unoccupied ship in space is kept within this height ({@code Starship}), so a player climbing towards it
     * reaches it before {@link #HARD_LIMIT_Y} forces their own recentre; ships saved higher in older worlds (the
     * dimension used to be 4 064 blocks tall) are brought back here when they load.
     */
    public static final double PARKED_LIMIT_Y = 640.0;
    /** Horizontal reach of the vertical-recentre guard: a ship's tracking range (160 blocks) plus a margin. */
    private static final double GUARD_RADIUS = 176.0;
    /** The guard's scan (players, ships, non-empty sections) is reused this many ticks. */
    private static final int GUARD_RECHECK_TICKS = 20;
    /**
     * Chunk view distance in galactica:space ({@code SpaceViewDistanceMixin}): a ship is tracked within 10 chunks, and
     * empty space beyond that costs chunk generation, saving and meshing every time the origin moves.
     * {@code -Dgalactica.spaceViewDistance} overrides it (2–32).
     */
    public static final int SPACE_VIEW_DISTANCE = Math.clamp(Integer.getInteger("galactica.spaceViewDistance", 10), 2, 32);
    /** A shift the client never acknowledged (mod missing, connection stalled) stops translating after this. */
    private static final int ACK_TIMEOUT_TICKS = 100;
    /** Start holding the landing chunks when the craft's current motion takes it past the limit within this. */
    private static final int PRELOAD_TICKS = 20;
    /**
     * Past the limit, the shift waits for the landing chunks at most this long. Waiting is only a nicety now (a
     * ship in space stays tracked in an unloaded chunk, see {@code Starship.isAlwaysTicking}), and precision must not
     * be traded for it: ~40 ticks is at most ~600 blocks past the limit at full ship speed.
     */
    private static final int MAX_DEFER_TICKS = 40;

    /** Keeps the chunks around block 0 loaded and entity-ticking while a craft approaches the limit (like a pearl). */
    public static final TicketType LANDING = Registry.register(BuiltInRegistries.TICKET_TYPE, Galactica.id("origin_landing"),
            new TicketType(40L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));

    /** Follows a moving craft: every chunk it crosses in the next {@link #LOOK_AHEAD_TICKS} ticks. */
    public static final TicketType CRAFT = Registry.register(BuiltInRegistries.TICKET_TYPE, Galactica.id("craft"),
            new TicketType(40L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
    private static final int LOOK_AHEAD_TICKS = 10;
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>(), LAST_STEP = new HashMap<>();
    /** Server tick at which a craft first needed a shift that is still waiting for its landing chunks. */
    private static final Map<UUID, Integer> DEFER_SINCE = new HashMap<>();
    /** Per player: {server tick of the last guard scan, 1 if something foreign was near}. */
    private static final Map<UUID, int[]> GUARD = new HashMap<>();

    private record Pending(int seq, Vec3 delta, int sentAt) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static int nextSeq = 1;

    /** Call once from {@code Galactica.onInitialize}. */
    public static void init() {
        if (LANDING == null) throw new IllegalStateException("origin landing ticket type not registered");
        PayloadTypeRegistry.clientboundPlay().register(OriginShiftPayload.TYPE, OriginShiftPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OriginShiftAckPayload.TYPE, OriginShiftAckPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OriginShiftAckPayload.TYPE, (msg, ctx) -> acknowledge(ctx.player(), msg.seq()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PENDING.remove(handler.player.getUUID());
            LAST_POS.remove(handler.player.getUUID());
            LAST_STEP.remove(handler.player.getUUID());
            DEFER_SINCE.remove(handler.player.getUUID());
            SENT_RADIUS.remove(handler.player.getUUID());
            GUARD.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            PENDING.clear();
            LAST_POS.clear();
            LAST_STEP.clear();
            DEFER_SINCE.clear();
            SENT_RADIUS.clear();
            GUARD.clear();
            TICKETED.clear();
        });
    }

    /**
     * Keeps the chunks under a moving craft and on its path entity-ticking, the way a thrown ender pearl keeps its
     * own chunk loaded. Right after a recentre the player's own view-distance ticket is still loading a whole new
     * area nearest-first, and a ship flying 15 blocks per tick outran it: each time it crossed into a chunk not loaded
     * yet, vanilla stopped tracking it and the pilot's client dropped the ship (and the pilot out of it) until the
     * chunk arrived. Call every tick for the leader of a craft in space.
     */
    public static void keepCraftLoaded(ServerPlayer p) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            keepCraftLoadedTimed(p);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.FLOATING_ORIGIN, perf);
        }
    }

    private static void keepCraftLoadedTimed(ServerPlayer p) {
        Entity root = p.getRootVehicle();
        Vec3 now = root.position();
        Vec3 before = LAST_POS.put(p.getUUID(), now);
        Vec3 step = before == null ? Vec3.ZERO : now.subtract(before);
        double len = step.length();
        if (len > 64) return; // this tick's jump was a recentre or a teleport: keep the last known step
        LAST_STEP.put(p.getUUID(), step);
        if (len >= 0.5) holdPath(p.level(), CRAFT, now, step);
    }

    /** Tickets every chunk the craft passes in the next {@link #LOOK_AHEAD_TICKS} ticks at {@code step} per tick. */
    private static void holdPath(ServerLevel level, TicketType type, Vec3 from, Vec3 step) {
        long last = Long.MIN_VALUE;
        for (int k = 0; k <= LOOK_AHEAD_TICKS; k++) {
            ChunkPos c = ChunkPos.containing(BlockPos.containing(from.add(step.scale(k))));
            if (c.pack() == last) continue;
            last = c.pack();
            // Radius 2: the centre chunk is entity-ticking (ticket level 31), its neighbours fully loaded.
            hold(level, type, c, 2);
        }
    }

    /** When each (ticket type, chunk) was last ticketed, server ticks. */
    private static final Map<TicketType, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap> TICKETED = new HashMap<>();
    /** A ticket lives 40 ticks; renewing it every 20 keeps it alive without re-adding it every tick. */
    private static final int RENEW_TICKS = 20;

    /**
     * Adds a ticket unless the same one was added in the last {@link #RENEW_TICKS} ticks. A craft holds ~11 chunks
     * ahead of it, and every add re-propagates chunk levels on the server thread: at cruise that was most of the
     * floating origin's cost per tick.
     */
    private static void hold(ServerLevel level, TicketType type, ChunkPos c, int radius) {
        int now = level.getServer().getTickCount();
        var seen = TICKETED.computeIfAbsent(type, t -> new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
        long key = c.pack();
        if (seen.containsKey(key)) {
            int age = now - seen.get(key);
            if (age >= 0 && age < RENEW_TICKS) return;
        }
        seen.put(key, now);
        if (seen.size() > 1024) seen.long2IntEntrySet().removeIf(e -> now - e.getIntValue() >= RENEW_TICKS || now < e.getIntValue());
        level.getChunkSource().addTicketAndLoadWithRadius(type, c, radius);
    }

    /** True when every point of that path is in an entity-ticking chunk. */
    private static boolean pathTicking(ServerLevel level, Vec3 from, Vec3 step) {
        for (int k = 0; k <= LOOK_AHEAD_TICKS; k++) {
            if (!level.isPositionEntityTicking(BlockPos.containing(from.add(step.scale(k))))) return false;
        }
        return true;
    }

    /** Per-tick housekeeping: forgets shifts that left space or were never acknowledged. */
    public static void tick(ServerPlayer p) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            tickTimed(p);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.FLOATING_ORIGIN, perf);
        }
    }

    private static void tickTimed(ServerPlayer p) {
        if (p.level().dimension() != Galactica.SPACE) {
            LAST_POS.remove(p.getUUID());
            LAST_STEP.remove(p.getUUID());
            DEFER_SINCE.remove(p.getUUID());
        }
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
        double far = maxNorm(root.position());
        Vec3 step = LAST_STEP.getOrDefault(p.getUUID(), Vec3.ZERO);
        // Only vertical: the same chunk columns, already loaded, so it needs no preload and never waits.
        Vec3 vertical = verticalShift(p, root);
        // Only a craft actually heading past the limit soon preloads: a parked or slow craft holds nothing extra.
        if (far <= LIMIT && maxNorm(root.position().add(step.scale(PRELOAD_TICKS))) <= LIMIT) {
            DEFER_SINCE.remove(p.getUUID());
            return vertical != null && canShift(root) ? vertical : null;
        }
        // The craft always lands next to block 0: keep those chunks loaded and entity-ticking from a little before
        // the limit. A vehicle moved into a chunk that is not loaded yet stops being tracked at all (vanilla removes
        // it from every client, the pilot included), so the shift waits until the landing chunks are ready.
        ServerLevel level = p.level();
        Vec3 d = new Vec3(-Math.round(root.getX()), -Math.round(root.getY()), -Math.round(root.getZ()));
        // The landing point is the craft's sub-block offset from block 0 whenever the shift happens, so its path after
        // landing is known in advance: radius 3 around block 0 (a landing at -0.4 sits in chunk -1) plus the chunks
        // the craft crosses in its first LOOK_AHEAD_TICKS ticks there, where the follow ticket takes over.
        Vec3 landing = root.position().add(d);
        hold(level, LANDING, ChunkPos.ZERO, 3);
        if (step.length() >= 0.5) holdPath(level, LANDING, landing, step);
        if (!canShift(root)) return null;
        if (far <= LIMIT) return vertical;
        int now = level.getServer().getTickCount();
        int since = DEFER_SINCE.computeIfAbsent(p.getUUID(), u -> now);
        if (now - since < MAX_DEFER_TICKS) {
            // While the landing chunks load, the craft may still climb or sink: recentre that axis alone meanwhile.
            if (!pathTicking(level, landing, step)) return vertical;
            for (Entity e : root.getIndirectPassengers()) {
                if (!level.isPositionEntityTicking(BlockPos.containing(e.position().add(d)))) return vertical;
            }
        } else {
            Galactica.LOG.debug("Origin shift for {} forced after {} ticks waiting for chunks", p.getName().getString(), now - since);
        }
        DEFER_SINCE.remove(p.getUUID());
        return d;
    }

    /**
     * The vertical-only recentre for {@code p}'s craft, or null. Past {@link #LIMIT_Y} it happens only when nothing
     * foreign is near ({@link #foreignNearby}); past {@link #HARD_LIMIT_Y} it happens anyway.
     */
    private static @Nullable Vec3 verticalShift(ServerPlayer p, Entity root) {
        double y = root.getY();
        if (Math.abs(y) <= LIMIT_Y) return null;
        Vec3 d = new Vec3(0, -Math.round(y), 0);
        if (Math.abs(y) > HARD_LIMIT_Y) return d;
        return foreignNearby(p, root) ? null : d;
    }

    /**
     * True when another player, another ship or any non-air block is within {@link #GUARD_RADIUS} blocks of the craft
     * horizontally, at any height: a vertical recentre would make it jump relative to the craft. Only loaded chunks
     * are looked at (nothing is loaded for it); the answer is reused for {@link #GUARD_RECHECK_TICKS} ticks.
     */
    private static boolean foreignNearby(ServerPlayer p, Entity root) {
        ServerLevel level = p.level();
        int now = level.getServer().getTickCount();
        int[] cached = GUARD.get(p.getUUID());
        if (cached != null && now - cached[0] >= 0 && now - cached[0] < GUARD_RECHECK_TICKS) return cached[1] != 0;
        boolean near = scanForeign(level, root);
        GUARD.put(p.getUUID(), new int[] {now, near ? 1 : 0});
        return near;
    }

    private static boolean scanForeign(ServerLevel level, Entity root) {
        double x = root.getX(), z = root.getZ();
        for (ServerPlayer other : level.players()) {
            if (other.getRootVehicle() == root) continue;
            if (Math.abs(other.getX() - x) <= GUARD_RADIUS && Math.abs(other.getZ() - z) <= GUARD_RADIUS) return true;
        }
        var box = new net.minecraft.world.phys.AABB(x - GUARD_RADIUS, level.getMinY() - 4096, z - GUARD_RADIUS,
                x + GUARD_RADIUS, level.getMaxY() + 4096, z + GUARD_RADIUS);
        if (!level.getEntitiesOfClass(dev.galactica.ship.Starship.class, box, s -> s.getRootVehicle() != root).isEmpty()) return true;
        int r = (int) Math.ceil(GUARD_RADIUS / 16.0);
        int cx = BlockPos.containing(x, 0, z).getX() >> 4, cz = BlockPos.containing(x, 0, z).getZ() >> 4;
        for (int i = -r; i <= r; i++) {
            for (int j = -r; j <= r; j++) {
                var chunk = level.getChunkSource().getChunkNow(cx + i, cz + j);
                if (chunk == null) continue;
                for (var section : chunk.getSections()) if (!section.hasOnlyAir()) return true;
            }
        }
        return false;
    }

    /** Horizontal distance from block 0 (max of |x|, |z|): the vertical axis has its own, smaller limit. */
    private static double maxNorm(Vec3 v) {
        return Math.max(Math.abs(v.x), Math.abs(v.z));
    }

    /** No shift while one is in flight for anyone aboard, or while a vanilla teleport is. */
    private static boolean canShift(Entity root) {
        for (ServerPlayer rider : riders(root)) {
            if (PENDING.containsKey(rider.getUUID()) || ((OriginShiftable) rider.connection).galactica$awaitingTeleport()) return false;
        }
        return true;
    }

    private static final Map<UUID, Integer> SENT_RADIUS = new HashMap<>();

    /**
     * Tells a player's client the chunk radius of the level they are in: {@link #SPACE_VIEW_DISTANCE} in space, the
     * server's everywhere else. Vanilla only sends the global value, so the client would keep a render area (and its
     * per-section bookkeeping) far wider than the chunks space actually sends. Call every tick.
     */
    public static void syncViewRadius(ServerPlayer p) {
        int global = p.level().getServer().getPlayerList().getViewDistance();
        int radius = p.level().dimension() == Galactica.SPACE ? Math.min(global, SPACE_VIEW_DISTANCE) : global;
        // Keyed with the global value too: vanilla re-broadcasts the global radius whenever it changes.
        int key = radius * 1000 + global;
        Integer sent = SENT_RADIUS.get(p.getUUID());
        if (sent == null ? radius == global : sent == key) return;
        SENT_RADIUS.put(p.getUUID(), key);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(radius));
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
     * payload first and the attachment sync that follows is a no-op. {@code time} is the clock that goes with the new
     * origins: the frame co-moves with a planet, so an origin only means something together with its time.
     */
    public static void apply(ServerPlayer leader, Vec3 d, GalacticPos leaderOld, GalacticPos leaderNew, double time) {
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
        LAST_POS.put(leader.getUUID(), root.position());
        Galactica.LOG.debug("Origin shift for {}: {} blocks", leader.getName().getString(), d);
        double[] od = leaderNew.metresFrom(leaderOld);
        int now = leader.level().getServer().getTickCount();
        for (ServerPlayer rider : riders(root)) {
            GalacticPos origin = rider == leader ? leaderNew
                    : rider.getAttachedOrCreate(Galactica.NAV).origin().plusMetres(od[0], od[1], od[2]);
            ((OriginShiftable) rider.connection).galactica$shiftReferences(d.x, d.y, d.z);
            rider.level().getChunkSource().move(rider);
            int seq = nextSeq++;
            PENDING.put(rider.getUUID(), new Pending(seq, d, now));
            ServerPlayNetworking.send(rider, new OriginShiftPayload(seq, root.getId(), d.x, d.y, d.z, origin, time));
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
