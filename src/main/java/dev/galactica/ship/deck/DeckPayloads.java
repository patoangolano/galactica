package dev.galactica.ship.deck;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server deck reports ({@link Deck}). The server checks each against its own view of the ship. */
public final class DeckPayloads {
    private DeckPayloads() {}

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Board.TYPE, Board.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Move.TYPE, Move.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Leave.TYPE, Leave.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
    }

    /** The sender stepped onto ship {@code shipId}'s deck at cell position (x, y, z). */
    public record Board(int shipId, float x, float y, float z, int epoch) implements CustomPacketPayload {
        public static final Type<Board> TYPE = new Type<>(Galactica.id("deck_board"));
        public static final StreamCodec<FriendlyByteBuf, Board> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.shipId);
            buf.writeFloat(p.x);
            buf.writeFloat(p.y);
            buf.writeFloat(p.z);
            buf.writeVarInt(p.epoch);
        }, buf -> new Board(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The sender's deck position this tick (under the server's last {@code epoch} for it). */
    public record Move(int shipId, float x, float y, float z, int epoch) implements CustomPacketPayload {
        public static final Type<Move> TYPE = new Type<>(Galactica.id("deck_move"));
        public static final StreamCodec<FriendlyByteBuf, Move> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.shipId);
            buf.writeFloat(p.x);
            buf.writeFloat(p.y);
            buf.writeFloat(p.z);
            buf.writeVarInt(p.epoch);
        }, buf -> new Move(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The sender stepped off the deck: where (world) and how fast (blocks per tick, world). */
    public record Leave(double x, double y, double z, double vx, double vy, double vz) implements CustomPacketPayload {
        public static final Type<Leave> TYPE = new Type<>(Galactica.id("deck_leave"));
        public static final StreamCodec<FriendlyByteBuf, Leave> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeDouble(p.x);
            buf.writeDouble(p.y);
            buf.writeDouble(p.z);
            buf.writeDouble(p.vx);
            buf.writeDouble(p.vy);
            buf.writeDouble(p.vz);
        }, buf -> new Leave(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The sender, walking on ship {@code shipId}, used its block {@code block} (helm, seat, container). */
    public record Use(int shipId, int block) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(Galactica.id("deck_use"));
        public static final StreamCodec<FriendlyByteBuf, Use> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.shipId);
            buf.writeVarInt(p.block);
        }, buf -> new Use(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
