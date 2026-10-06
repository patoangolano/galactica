package dev.galactica.net;

import dev.galactica.Galactica;
import dev.galactica.galaxy.SectorKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: the Galaxy Map asks to set (or clear) the hyperjump target. Only the star's address is
 * sent; the server re-derives the star from its own galaxy and never trusts a client position.
 *
 * @param starId  {@code Star.id()} (procedural ids are seed hashes of either sign, so the kind of
 *                star is carried by {@code index}, never inferred from the id)
 * @param ix      sector of a procedural star (ignored otherwise)
 * @param index   global index of a procedural star in its sector, {@link #CATALOG} for a catalog
 *                star, {@link #CLEAR} to clear the target
 */
public record SetJumpTargetPayload(long starId, int ix, int iy, int iz, int index) implements CustomPacketPayload {
    public static final int CATALOG = -1, CLEAR = -2;

    public static final Type<SetJumpTargetPayload> TYPE = new Type<>(Galactica.id("set_jump_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetJumpTargetPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.LONG, SetJumpTargetPayload::starId,
            ByteBufCodecs.INT, SetJumpTargetPayload::ix,
            ByteBufCodecs.INT, SetJumpTargetPayload::iy,
            ByteBufCodecs.INT, SetJumpTargetPayload::iz,
            ByteBufCodecs.VAR_INT, SetJumpTargetPayload::index,
            SetJumpTargetPayload::new);

    public static SetJumpTargetPayload clear() {
        return new SetJumpTargetPayload(0L, 0, 0, 0, CLEAR);
    }

    public static SetJumpTargetPayload catalog(long starId) {
        return new SetJumpTargetPayload(starId, 0, 0, 0, CATALOG);
    }

    public static SetJumpTargetPayload procedural(long starId, SectorKey sector, int index) {
        return new SetJumpTargetPayload(starId, sector.ix(), sector.iy(), sector.iz(), index);
    }

    public boolean isClear() {
        return index == CLEAR;
    }

    public SectorKey sector() {
        return new SectorKey(ix, iy, iz);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
