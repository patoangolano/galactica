package dev.galactica.ship;

import dev.galactica.Galactica;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: a ship key aboard. {@link #DOCK} (G) docks or undocks the ship the sender pilots
 * ({@link ShipDocking#toggle}); {@link #CARGO} (B) opens the next cargo container of the ship the sender rides
 * ({@link ShipCargo#openNext}). The server checks who sends it; the client only names the action.
 */
public record ShipActionPayload(byte action) implements CustomPacketPayload {
    public static final byte DOCK = 0, CARGO = 1;
    public static final Type<ShipActionPayload> TYPE = new Type<>(Galactica.id("ship_action"));
    public static final StreamCodec<FriendlyByteBuf, ShipActionPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeByte(p.action), buf -> new ShipActionPayload(buf.readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
