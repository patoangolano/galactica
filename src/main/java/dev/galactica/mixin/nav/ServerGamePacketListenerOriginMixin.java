package dev.galactica.mixin.nav;

import dev.galactica.nav.FloatingOrigin;
import dev.galactica.nav.OriginShiftable;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Server half of the ordered floating-origin shift ({@link FloatingOrigin}).
 *
 * <ul>
 *   <li>Movement packets that left the client before it applied the shift are in the old frame: they are
 *       re-dispatched with the shift added, so the controlled vehicle (or the player on EVA) neither snaps back
 *       nor trips the "moved too quickly / wrongly" checks.</li>
 *   <li>The anti-cheat reference positions move with the craft ({@link #galactica$shiftReferences}); vanilla only
 *       resets the vehicle ones at the next connection tick, after the packets queued meanwhile.</li>
 *   <li>A vanilla teleport supersedes a pending shift: vanilla drops every movement packet until its ack.</li>
 * </ul>
 * The hooks sit after {@code ensureRunningOnSameThread}, so they run on the server thread, in packet order.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerOriginMixin implements OriginShiftable {
    @Shadow public ServerPlayer player;
    @Shadow private double firstGoodX;
    @Shadow private double firstGoodY;
    @Shadow private double firstGoodZ;
    @Shadow private double lastGoodX;
    @Shadow private double lastGoodY;
    @Shadow private double lastGoodZ;
    @Shadow private double vehicleFirstGoodX;
    @Shadow private double vehicleFirstGoodY;
    @Shadow private double vehicleFirstGoodZ;
    @Shadow private double vehicleLastGoodX;
    @Shadow private double vehicleLastGoodY;
    @Shadow private double vehicleLastGoodZ;
    @Shadow private @Nullable Vec3 awaitingPositionFromClient;

    @Shadow public abstract void handleMoveVehicle(ServerboundMoveVehiclePacket packet);

    @Shadow public abstract void handleMovePlayer(ServerboundMovePlayerPacket packet);

    @Unique private boolean galactica$redispatching;

    @Override
    public void galactica$shiftReferences(double dx, double dy, double dz) {
        firstGoodX += dx;
        firstGoodY += dy;
        firstGoodZ += dz;
        lastGoodX += dx;
        lastGoodY += dy;
        lastGoodZ += dz;
        vehicleFirstGoodX += dx;
        vehicleFirstGoodY += dy;
        vehicleFirstGoodZ += dz;
        vehicleLastGoodX += dx;
        vehicleLastGoodY += dy;
        vehicleLastGoodZ += dz;
    }

    @Override
    public boolean galactica$awaitingTeleport() {
        return awaitingPositionFromClient != null;
    }

    @Inject(method = "handleMoveVehicle", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void galactica$translateVehicleMove(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (galactica$redispatching) return;
        Vec3 d = FloatingOrigin.pendingDelta(player);
        if (d == null) return;
        PositionAndRotation at = packet.movingTo();
        ci.cancel();
        galactica$redispatching = true;
        try {
            handleMoveVehicle(new ServerboundMoveVehiclePacket(PositionAndRotation.of(at.position().add(d), at.yRot(), at.xRot()),
                    packet.onGround()));
        } finally {
            galactica$redispatching = false;
        }
    }

    @Inject(method = "handleMovePlayer", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void galactica$translatePlayerMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (galactica$redispatching || !packet.hasPosition()) return;
        Vec3 d = FloatingOrigin.pendingDelta(player);
        if (d == null) return;
        double x = packet.getX(0) + d.x, y = packet.getY(0) + d.y, z = packet.getZ(0) + d.z;
        ServerboundMovePlayerPacket moved = packet.hasRotation()
                ? new ServerboundMovePlayerPacket.PosRot(x, y, z, packet.getYRot(0F), packet.getXRot(0F), packet.isOnGround(), packet.horizontalCollision())
                : new ServerboundMovePlayerPacket.Pos(x, y, z, packet.isOnGround(), packet.horizontalCollision());
        ci.cancel();
        galactica$redispatching = true;
        try {
            handleMovePlayer(moved);
        } finally {
            galactica$redispatching = false;
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"))
    private void galactica$teleportSupersedesShift(PositionMoveRotation destination, Set<Relative> relatives, CallbackInfo ci) {
        FloatingOrigin.cancel(player);
    }
}
