package dev.galactica.ship.deck;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipStructure;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EVA off a moving ship in space keeps the ship's velocity. Free flight in space (creative-style flying) would brake a
 * player to a stop in about a second, so a walker stepping off a ship that crosses 14 blocks a tick would be left
 * behind at once; instead the velocity they left with is kept as a drift added to their own movement every tick
 * (inertia), until they board something, leave space, or brake it away with the sneak key. The client moves the
 * player (it owns their position); the server keeps the same drift as the player's expected velocity so vanilla's
 * "moved too quickly" check measures only the player's own movement on top of it.
 */
public final class EvaDrift {
    private EvaDrift() {}

    /** Drifts slower than this (blocks per tick) are dropped. */
    public static final double MIN = 0.01;
    /** Share of the drift kept per tick while braking (sneak). */
    public static final double BRAKE = 0.9;

    private static final Map<UUID, Vec3> SERVER = new ConcurrentHashMap<>();

    public static void init() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) tick(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> SERVER.remove(h.player.getUUID()));
    }

    /** Server: a player stepped off {@code ship} with velocity {@code v} (blocks per tick). */
    static void start(ServerPlayer p, ShipStructure ship, Vec3 v) {
        if (p.level().dimension() != Galactica.SPACE || v.length() < MIN) {
            SERVER.remove(p.getUUID());
            return;
        }
        SERVER.put(p.getUUID(), v);
    }

    public static Vec3 drift(ServerPlayer p) {
        return SERVER.getOrDefault(p.getUUID(), Vec3.ZERO);
    }

    private static void tick(ServerPlayer p) {
        Vec3 d = SERVER.get(p.getUUID());
        if (d == null) return;
        if (p.isPassenger() || p.level().dimension() != Galactica.SPACE || d.length() < MIN) {
            SERVER.remove(p.getUUID());
            return;
        }
        if (p.getLastClientInput().shift()) d = d.scale(BRAKE);
        SERVER.put(p.getUUID(), d);
        p.setDeltaMovement(d);
    }

    /** Next drift for a player who is braking ({@code shift}) or not; null when it has run out. */
    public static Vec3 next(Vec3 d, boolean shift) {
        Vec3 n = shift ? d.scale(BRAKE) : d;
        return n.length() < MIN ? null : n;
    }
}
