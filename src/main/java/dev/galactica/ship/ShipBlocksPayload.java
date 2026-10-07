package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.ship.build.ShipBlocks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the blocks of a {@link ShipStructure}, sent when the client starts tracking it (and so again after
 * every dimension change, which recreates the entity). States and cells only: block-entity data stays on the server.
 * About 7 bytes a block, so a 4 096-block ship is under 30 kB.
 */
public record ShipBlocksPayload(int entityId, ShipBlocks blocks) implements CustomPacketPayload {
    public static final Type<ShipBlocksPayload> TYPE = new Type<>(Galactica.id("ship_blocks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShipBlocksPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.entityId());
                p.blocks().write(buf);
            },
            buf -> new ShipBlocksPayload(buf.readVarInt(), ShipBlocks.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
