package dev.galactica.nav;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C floating-origin recentre ({@link FloatingOrigin}): every block position of the receiving player's craft (root
 * vehicle and all riders, or the player alone on EVA) moves by ({@code dx}, {@code dy}, {@code dz}) blocks and the
 * player's galactic origin becomes {@code origin} at clock {@code time} (the frame co-moves with planets, so the two
 * go together), both in the same client tick, so neither the camera nor the sky
 * moves. The client answers with {@link OriginShiftAckPayload}; movement packets it sent before that ack are in
 * the old frame and the server translates them. {@code rootId} is the craft's root entity as the server moved it,
 * so the client shifts that whole tree even if it momentarily disagrees about who rides what.
 */
public record OriginShiftPayload(int seq, int rootId, double dx, double dy, double dz, GalacticPos origin, double time) implements CustomPacketPayload {
    public static final Type<OriginShiftPayload> TYPE = new Type<>(Galactica.id("origin_shift"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OriginShiftPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OriginShiftPayload::seq,
            ByteBufCodecs.VAR_INT, OriginShiftPayload::rootId,
            ByteBufCodecs.DOUBLE, OriginShiftPayload::dx,
            ByteBufCodecs.DOUBLE, OriginShiftPayload::dy,
            ByteBufCodecs.DOUBLE, OriginShiftPayload::dz,
            NavState.POS_STREAM.cast(), OriginShiftPayload::origin,
            ByteBufCodecs.DOUBLE, OriginShiftPayload::time,
            OriginShiftPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
