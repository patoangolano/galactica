package dev.galactica.ship.collide;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Vector3fc;

/**
 * Server → pilot: the server resolved a collision of the ship they fly ({@link ShipCollisions}). The pilot's client,
 * which owns the ship's motion, adds the velocity change {@code dv} (m/s), moves the hull by {@code nudge} (blocks, out
 * of the other hull) and adds {@code spin} (rad/s, world) to its turn, then acknowledges impulse {@code id}
 * ({@link Ack}) so the server stops translating its older reports.
 */
public record ShipImpulsePayload(int entityId, int id, Vector3fc dv, Vector3fc nudge, Vector3fc spin) implements CustomPacketPayload {
    public static final Type<ShipImpulsePayload> TYPE = new Type<>(Galactica.id("ship_impulse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShipImpulsePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShipImpulsePayload::entityId,
            ByteBufCodecs.VAR_INT, ShipImpulsePayload::id,
            ByteBufCodecs.VECTOR3F, ShipImpulsePayload::dv,
            ByteBufCodecs.VECTOR3F, ShipImpulsePayload::nudge,
            ByteBufCodecs.VECTOR3F, ShipImpulsePayload::spin,
            ShipImpulsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Pilot → server: impulse {@code id} of ship {@code entityId} is applied; reports and moves sent from now on include it. */
    public record Ack(int entityId, int id) implements CustomPacketPayload {
        public static final Type<Ack> TYPE = new Type<>(Galactica.id("ship_impulse_ack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ack::entityId, ByteBufCodecs.VAR_INT, Ack::id, Ack::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
