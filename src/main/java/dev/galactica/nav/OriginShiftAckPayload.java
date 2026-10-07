package dev.galactica.nav;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: the client applied {@link OriginShiftPayload} {@code seq}. It travels on the same ordered connection as the
 * vanilla movement packets, so everything before it is in the old frame and everything after it in the new one.
 */
public record OriginShiftAckPayload(int seq) implements CustomPacketPayload {
    public static final Type<OriginShiftAckPayload> TYPE = new Type<>(Galactica.id("origin_shift_ack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OriginShiftAckPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OriginShiftAckPayload::seq, OriginShiftAckPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
