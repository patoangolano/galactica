package dev.galactica.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which body a player is on, attached to the player and synced to them so the client can draw the
 * right sky without its own copy of the slot table.
 *
 * @param slot       surface dimension index, galactica:surface_N
 * @param address    the body that slot holds
 * @param descending true from an unsuited-flight (EVA) entry until first touchdown: flight is
 *                   allowed for the descent from Y 600 and taken away on landing in survival
 */
public record SurfaceState(int slot, PlanetAddress address, boolean descending) {

    public static final Codec<SurfaceState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("slot").forGetter(SurfaceState::slot),
            PlanetAddress.CODEC.fieldOf("address").forGetter(SurfaceState::address),
            Codec.BOOL.optionalFieldOf("descending", false).forGetter(SurfaceState::descending)
    ).apply(i, SurfaceState::new));

    public static final StreamCodec<ByteBuf, SurfaceState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SurfaceState::slot,
            PlanetAddress.STREAM_CODEC, SurfaceState::address,
            ByteBufCodecs.BOOL, SurfaceState::descending,
            SurfaceState::new);

    public SurfaceState landed() {
        return new SurfaceState(slot, address, false);
    }
}
