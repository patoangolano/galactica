package dev.galactica.ship;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Pilot → server: the hyperjump key was pressed. The target is the server's to choose (see {@link Hyperdrive}). */
public record HyperjumpPayload(int entityId) implements CustomPacketPayload {
    public static final Type<HyperjumpPayload> TYPE = new Type<>(Galactica.id("hyperjump"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HyperjumpPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HyperjumpPayload::entityId, HyperjumpPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
