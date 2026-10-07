package dev.galactica.net;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: engage the autopilot (to orbit or to land) or hand control back. The server decides whether it may. */
public record AutopilotPayload(int action) implements CustomPacketPayload {
    public static final int DISENGAGE = 0, ENGAGE_ORBIT = 1, ENGAGE_LAND = 2, DISENGAGE_MANUAL = 3;

    public static final Type<AutopilotPayload> TYPE = new Type<>(Galactica.id("autopilot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AutopilotPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AutopilotPayload::action, AutopilotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
