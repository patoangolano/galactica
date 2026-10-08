package dev.galactica.ship.deck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Where a deck walker stands on the ship it rides: the bottom centre of its box in the ship grid (cell coordinates).
 * Present (as the {@code galactica:deck} attachment) exactly while the entity walks on a ship instead of sitting.
 * {@code epoch} changes whenever the server sets the position itself (standing up from a seat, a refused move), so the
 * walker's own client, which otherwise owns its position, knows to take it over.
 */
public record DeckPos(float x, float y, float z, int epoch) {
    public static final Codec<DeckPos> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("x").forGetter(DeckPos::x),
            Codec.FLOAT.fieldOf("y").forGetter(DeckPos::y),
            Codec.FLOAT.fieldOf("z").forGetter(DeckPos::z),
            Codec.INT.optionalFieldOf("epoch", 0).forGetter(DeckPos::epoch)).apply(i, DeckPos::new));

    public static final StreamCodec<ByteBuf, DeckPos> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, DeckPos::x, ByteBufCodecs.FLOAT, DeckPos::y, ByteBufCodecs.FLOAT, DeckPos::z,
            ByteBufCodecs.VAR_INT, DeckPos::epoch, DeckPos::new);

    public DeckPos(double x, double y, double z, int epoch) {
        this((float) x, (float) y, (float) z, epoch);
    }
}
