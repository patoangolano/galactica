package dev.galactica.planet;

import dev.galactica.Galactica;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-loads the ground under a descending craft before it arrives, so the first surface frames already
 * have terrain. One hold per approaching player, placed at the projected switch point (sub-point plus
 * ground track × ETA) in the destination level, moved when the projection drifts by
 * {@link #MOVE_CHUNKS} chunks or more and removed on arrival or abort.
 *
 * <p><b>Vanilla ticket semantics (26.3, {@code TicketStorage}).</b> A ticket is identified only by
 * (chunk, type, level): adding an equal one just calls {@code resetTicksLeft()}, removing one removes the
 * shared entry. A timed ticket counts down in {@code purgeStaleTickets} while its chunk is loaded, so the
 * 600-tick timeout would drop a ticket 30 s after placement. Hence:
 * <ul>
 *   <li>holds are <b>reference-counted</b> per (level, chunk, radius): the vanilla ticket is added on the first
 *       hold and removed with the last, so two players projecting onto the same chunk never release each other's
 *       pre-load;</li>
 *   <li>a standing hold is <b>refreshed</b> every {@link #REFRESH_TICKS} ticks by re-adding the same ticket, which
 *       resets its timer; the timeout then only cleans up a ticket a crash left behind.</li>
 * </ul>
 * The type loads chunks ({@code FLAG_LOADING}) and keeps the destination level ticking while nobody is in it yet
 * ({@code FLAG_KEEP_DIMENSION_ACTIVE}). {@code TicketType(long, int)}, both flags, {@code BuiltInRegistries.TICKET_TYPE}
 * and {@code ServerChunkCache.addTicketAndLoadWithRadius/removeTicketWithRadius} are verified on 26.3.
 */
public final class ApproachTickets {
    private ApproachTickets() {}

    public static final TicketType APPROACH = Registry.register(BuiltInRegistries.TICKET_TYPE, Galactica.id("approach"),
            new TicketType(600L, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    /** Loaded radius around the projected point, chunks (capped: generation is not free). */
    public static final int MAX_RADIUS = 6;
    /** Re-place the hold when the projection moves this many chunks. */
    public static final int MOVE_CHUNKS = 2;
    /** Re-add a standing ticket this often, well inside the 600-tick timeout. */
    public static final int REFRESH_TICKS = 20;

    /** What vanilla tells tickets apart by (plus the level they live in). */
    private record Key(ServerLevel level, ChunkPos pos, int radius) {}

    private static final class Held {
        final Key key;
        long refreshedAt;

        Held(Key key, long refreshedAt) {
            this.key = key;
            this.refreshedAt = refreshedAt;
        }
    }

    private static final Map<UUID, Held> HELD = new HashMap<>();
    private static final Map<Key, Integer> REFS = new HashMap<>();

    /** Forces class init (and so the registry entry) during mod init. */
    public static void init() {
        if (APPROACH == null) throw new IllegalStateException("approach ticket type not registered");
    }

    /**
     * Holds chunks around block (x, z) of {@code level} for player {@code id} at server tick {@code now}, moving an
     * older hold if the projection drifted and refreshing a standing one.
     */
    public static void hold(UUID id, ServerLevel level, double x, double z, long now) {
        int radius = Math.min(MAX_RADIUS, Math.max(2, level.getServer().getPlayerList().getViewDistance()));
        ChunkPos pos = new ChunkPos(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        Held old = HELD.get(id);
        if (old != null) {
            boolean same = old.key.level() == level && old.key.radius() == radius
                    && Math.abs(old.key.pos().x() - pos.x()) < MOVE_CHUNKS
                    && Math.abs(old.key.pos().z() - pos.z()) < MOVE_CHUNKS;
            if (same) {
                if (now - old.refreshedAt >= REFRESH_TICKS) {
                    // Equal ticket: vanilla only resets its timer (and re-creates it if it had expired anyway).
                    level.getChunkSource().addTicketAndLoadWithRadius(APPROACH, old.key.pos(), old.key.radius());
                    old.refreshedAt = now;
                }
                return;
            }
            release(id);
        }
        Key key = new Key(level, pos, radius);
        // Always (re-)add: for a shared key this refreshes the timer, for a new one it places the ticket.
        level.getChunkSource().addTicketAndLoadWithRadius(APPROACH, pos, radius);
        REFS.merge(key, 1, Integer::sum);
        HELD.put(id, new Held(key, now));
    }

    /** Drops the player's hold, if any; the vanilla ticket goes with the last hold on that chunk. */
    public static void release(UUID id) {
        Held h = HELD.remove(id);
        if (h == null) return;
        Integer left = REFS.merge(h.key, -1, Integer::sum);
        if (left == null || left <= 0) {
            REFS.remove(h.key);
            h.key.level().getChunkSource().removeTicketWithRadius(APPROACH, h.key.pos(), h.key.radius());
        }
    }

    /** True while the player holds a pre-load. */
    public static boolean holds(UUID id) {
        return HELD.containsKey(id);
    }

    /** Server stop: tickets die with their levels. */
    public static void clear() {
        HELD.clear();
        REFS.clear();
    }
}
