package dev.galactica.ship;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

/**
 * Pilot → server, every client tick while piloting: the predicted attitude and velocity (m/s), plus the
 * flight-assist mode. Position itself rides vanilla's {@code ServerboundMoveVehiclePacket}; this payload
 * carries what that packet cannot (roll, and a velocity the server's "moved too quickly" check can trust).
 * {@code seq} numbers the reports so a {@link ShipCorrectionPayload} can name the one the server clamped.
 */
public record ShipControlPayload(int entityId, int seq, Quaternionfc attitude, Vector3fc velocity, boolean coupled)
        implements CustomPacketPayload {
    public static final Type<ShipControlPayload> TYPE = new Type<>(Galactica.id("ship_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShipControlPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShipControlPayload::entityId,
            ByteBufCodecs.VAR_INT, ShipControlPayload::seq,
            ByteBufCodecs.QUATERNIONF, ShipControlPayload::attitude,
            ByteBufCodecs.VECTOR3F, ShipControlPayload::velocity,
            ByteBufCodecs.BOOL, ShipControlPayload::coupled,
            ShipControlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
