package dev.galactica.client.nav;

import dev.galactica.Galactica;
import dev.galactica.nav.NavState;
import dev.galactica.nav.OriginShiftAckPayload;
import dev.galactica.nav.OriginShiftPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client half of the floating-origin recentre ({@code FloatingOrigin}). The whole craft — root vehicle and every
 * rider, current and previous-tick positions and any interpolation target — and the NAV origin move in the same
 * call, so the camera (interpolated between the previous and current tick) and the sky (origin + camera) are
 * unchanged on the next frame. The ack goes out right away; every movement packet after it is in the new frame.
 */
public final class OriginShiftClient {
    private OriginShiftClient() {}

    /** Shifts applied since start-up (read by the dev autotest). */
    public static int applied;

    public static void initClient() {
        // Fabric runs play payload handlers on the client thread, inline and in packet order.
        ClientPlayNetworking.registerGlobalReceiver(OriginShiftPayload.TYPE, (payload, ctx) -> apply(payload));
    }

    private static void apply(OriginShiftPayload msg) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Vec3 d = new Vec3(msg.dx(), msg.dy(), msg.dz());
        shiftTree(player.getRootVehicle(), d);
        NavState nav = player.getAttached(Galactica.NAV);
        player.setAttached(Galactica.NAV, (nav != null ? nav : NavState.initial()).withOrigin(msg.origin()));
        applied++;
        ClientPlayNetworking.send(new OriginShiftAckPayload(msg.seq()));
    }

    private static void shiftTree(Entity e, Vec3 d) {
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
        for (Entity passenger : e.getPassengers()) shiftTree(passenger, d);
    }
}
