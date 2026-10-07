package dev.galactica.client.nav;

import dev.galactica.Galactica;
import dev.galactica.nav.NavState;
import dev.galactica.nav.OriginShiftAckPayload;
import dev.galactica.nav.OriginShiftPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Client half of the floating-origin recentre ({@code FloatingOrigin}). The whole craft — the root entity the server
 * moved and every rider, current and previous-tick positions and any interpolation target — and the NAV origin
 * move in the same call, so the camera (interpolated between the previous and current tick) and the sky (origin +
 * camera) are unchanged on the next frame. The ack goes out right away; every movement packet after it is in the
 * new frame.
 *
 * <p>A shift is never dropped: one that arrives while there is no local player (never expected in the play phase,
 * but the server would otherwise translate every later packet and the two sides would stay a shift apart) waits
 * and is applied, in order, at the start of the next client tick that has one.
 */
public final class OriginShiftClient {
    private OriginShiftClient() {}

    /** Shifts applied since start-up (read by the dev autotest). */
    public static int applied;

    private static final ArrayDeque<OriginShiftPayload> WAITING = new ArrayDeque<>();

    public static void initClient() {
        // Fabric runs play payload handlers on the client thread, inline and in packet order.
        ClientPlayNetworking.registerGlobalReceiver(OriginShiftPayload.TYPE, (payload, ctx) -> receive(payload));
        ClientTickEvents.START_CLIENT_TICK.register(mc -> drain());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> WAITING.clear());
    }

    private static void receive(OriginShiftPayload msg) {
        WAITING.addLast(msg);
        drain();
    }

    private static void drain() {
        while (!WAITING.isEmpty()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            apply(player, WAITING.pollFirst());
        }
    }

    private static void apply(LocalPlayer player, OriginShiftPayload msg) {
        Vec3 d = new Vec3(msg.dx(), msg.dy(), msg.dz());
        // Shift exactly what the server moved (its root vehicle's tree), plus our own player if the client
        // momentarily sees it outside that tree; each entity once.
        Set<Entity> done = new HashSet<>();
        ClientLevel level = player.level() instanceof ClientLevel cl ? cl : null;
        Entity root = level != null ? level.getEntity(msg.rootId()) : null;
        if (root != null) shiftTree(root.getRootVehicle(), d, done);
        if (!done.contains(player)) shiftTree(player.getRootVehicle(), d, done);
        NavState nav = player.getAttached(Galactica.NAV);
        player.setAttached(Galactica.NAV, (nav != null ? nav : NavState.initial()).withOrigin(msg.origin()).withTime(msg.time()));
        applied++;
        ClientPlayNetworking.send(new OriginShiftAckPayload(msg.seq()));
    }

    private static void shiftTree(Entity e, Vec3 d, Set<Entity> done) {
        if (!done.add(e)) return;
        // A non-authoritative entity (a ship seen by its crew) may be mid-interpolation towards an old-frame target.
        PositionAndRotation target = null;
        if (e.isInterpolating()) {
            target = e.getInterpolation().target().immutable();
            e.getInterpolation().cancel();
        }
        e.setPos(e.getX() + d.x, e.getY() + d.y, e.getZ() + d.z);
        e.xo += d.x;
        e.yo += d.y;
        e.zo += d.z;
        e.xOld += d.x;
        e.yOld += d.y;
        e.zOld += d.z;
        if (target != null) e.moveOrInterpolateTo(target.position().add(d), target.yRot(), target.xRot());
        for (Entity passenger : e.getPassengers()) shiftTree(passenger, d, done);
    }
}
