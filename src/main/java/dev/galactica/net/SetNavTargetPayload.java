package dev.galactica.net;

import dev.galactica.Galactica;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.nav.target.NavTarget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: lock (or clear) the navigation target from the 3D map. Only an address travels: the server re-derives and names
 * the object ({@code NavTargetRules}).
 *
 * @param kind      {@link NavTarget.Kind} ordinal, or {@link #CLEAR}
 * @param index     global index of a procedural star in its sector, {@link NavTarget#CATALOG} for a catalog star
 * @param bodyId    planet or moon id for body targets
 * @param deepSkyId deep-sky object id for deep-sky targets
 */
public record SetNavTargetPayload(int kind, long starId, int ix, int iy, int iz, int index, long bodyId, String deepSkyId)
        implements CustomPacketPayload {
    public static final int CLEAR = -1;

    public static final Type<SetNavTargetPayload> TYPE = new Type<>(Galactica.id("set_nav_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetNavTargetPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeLong(p.starId);
                buf.writeInt(p.ix);
                buf.writeInt(p.iy);
                buf.writeInt(p.iz);
                buf.writeVarInt(p.index);
                buf.writeLong(p.bodyId);
                ByteBufCodecs.stringUtf8(256).encode(buf, p.deepSkyId);
            },
            buf -> new SetNavTargetPayload(buf.readVarInt(), buf.readLong(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readVarInt(), buf.readLong(), ByteBufCodecs.stringUtf8(256).decode(buf)));

    public static SetNavTargetPayload clear() {
        return new SetNavTargetPayload(CLEAR, 0L, 0, 0, 0, 0, 0L, "");
    }

    public static SetNavTargetPayload star(long starId, SectorKey sector, int index) {
        return new SetNavTargetPayload(NavTarget.Kind.STAR.ordinal(), starId, sector.ix(), sector.iy(), sector.iz(), index, 0L, "");
    }

    public static SetNavTargetPayload body(NavTarget.Kind kind, long starId, SectorKey sector, int index, long bodyId) {
        return new SetNavTargetPayload(kind.ordinal(), starId, sector.ix(), sector.iy(), sector.iz(), index, bodyId, "");
    }

    public static SetNavTargetPayload deepSky(String id) {
        return new SetNavTargetPayload(NavTarget.Kind.DEEP_SKY.ordinal(), 0L, 0, 0, 0, 0, 0L, id);
    }

    public boolean isClear() {
        return kind == CLEAR;
    }

    public SectorKey sector() {
        return new SectorKey(ix, iy, iz);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
