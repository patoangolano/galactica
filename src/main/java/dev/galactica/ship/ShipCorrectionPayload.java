package dev.galactica.ship;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Vector3fc;

/**
 * Server → pilot, only when {@link Starship#acceptPilotReport} had to clamp a report: the velocity (m/s) the
 * server accepted for report {@code seq}. The pilot's client ignores vanilla's velocity broadcast for its own ship
 * (a tick-old echo of its own reports), so this is how a refused part of the prediction reaches it.
 */
public record ShipCorrectionPayload(int entityId, int seq, Vector3fc velocity) implements CustomPacketPayload {
    public static final Type<ShipCorrectionPayload> TYPE = new Type<>(Galactica.id("ship_correction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShipCorrectionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShipCorrectionPayload::entityId,
            ByteBufCodecs.VAR_INT, ShipCorrectionPayload::seq,
            ByteBufCodecs.VECTOR3F, ShipCorrectionPayload::velocity,
            ShipCorrectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
