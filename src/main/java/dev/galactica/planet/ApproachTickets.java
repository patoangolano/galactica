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
 * have terrain. One ticket per approaching player, placed at the projected switch point (sub-point plus
 * ground track × ETA) in the destination level, moved when the projection drifts by more than
 * {@link #MOVE_CHUNKS} chunks and removed on arrival or abort.
 *
 * <p>The type loads chunks ({@code FLAG_LOADING}) and keeps the destination level ticking while nobody is in
 * it yet ({@code FLAG_KEEP_DIMENSION_ACTIVE}); its 600-tick timeout cleans up a ticket a crash or logout left
 * behind. {@code TicketType(long, int)}, both flags, {@code BuiltInRegistries.TICKET_TYPE} and
 * {@code ServerChunkCache.addTicketAndLoadWithRadius/removeTicketWithRadius} are verified on 26.3.
 */
public final class ApproachTickets {
    private ApproachTickets() {}

    public static final TicketType APPROACH = Registry.register(BuiltInRegistries.TICKET_TYPE, Galactica.id("approach"),
            new TicketType(600L, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    /** Loaded radius around the projected point, chunks (capped: generation is not free). */
    public static final int MAX_RADIUS = 6;
    /** Re-place the ticket when the projection moves this many chunks. */
    public static final int MOVE_CHUNKS = 2;

    private record Held(ServerLevel level, ChunkPos pos, int radius) {}

    private static final Map<UUID, Held> HELD = new HashMap<>();

    /** Forces class init (and so the registry entry) during mod init. */
    public static void init() {
        if (APPROACH == null) throw new IllegalStateException("approach ticket type not registered");
    }

    /** Holds chunks around block (x, z) of {@code level} for player {@code id}, moving an older ticket if needed. */
    public static void hold(UUID id, ServerLevel level, double x, double z) {
        int radius = Math.min(MAX_RADIUS, Math.max(2, level.getServer().getPlayerList().getViewDistance()));
        ChunkPos pos = new ChunkPos(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        Held old = HELD.get(id);
        if (old != null) {
            boolean same = old.level == level && Math.abs(old.pos.x() - pos.x()) < MOVE_CHUNKS
                    && Math.abs(old.pos.z() - pos.z()) < MOVE_CHUNKS;
            if (same) return;
            release(id);
        }
        level.getChunkSource().addTicketAndLoadWithRadius(APPROACH, pos, radius);
        HELD.put(id, new Held(level, pos, radius));
    }

    /** Drops the player's ticket, if any. */
    public static void release(UUID id) {
        Held h = HELD.remove(id);
        if (h != null) h.level.getChunkSource().removeTicketWithRadius(APPROACH, h.pos, h.radius);
    }

    /** Server stop: tickets die with their levels. */
    public static void clear() {
        HELD.clear();
    }
}
