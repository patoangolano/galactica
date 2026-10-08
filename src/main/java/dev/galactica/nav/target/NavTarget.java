package dev.galactica.nav.target;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.galaxy.SectorKey;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A locked navigation target: a star (black holes and remnants included), a planet or moon of some star's system, or a
 * deep-sky object used as a destination point. Set from the 3D map, validated and named by the server
 * ({@link NavTargetRules}), persisted and synced on the player ({@link NavTargets#TYPE}).
 *
 * <p>The star is kept by its galaxy address (id, sector, index; {@code index == CATALOG} for catalog stars) so its
 * system can be regenerated anywhere; {@code xPc..zPc} is the star's (or the deep-sky object's) galactocentric
 * position so the HUD and the planner need no galaxy lookup for distances.
 *
 * @param kind      what the target is
 * @param name      display name, chosen by the server
 * @param starId    {@code Star.id()} of the system star (0 for deep-sky targets)
 * @param ix        sector of a procedural star
 * @param index     global index of a procedural star in its sector, {@link #CATALOG} for a catalog star
 * @param bodyId    planet or moon id ({@code Body.id()}), 0 for a star or deep-sky target
 * @param deepSkyId {@code DeepSkyObject.id} of a deep-sky target, empty otherwise
 */
public record NavTarget(Kind kind, String name, long starId, int ix, int iy, int iz, int index, long bodyId, String deepSkyId,
                        double xPc, double yPc, double zPc) {

    public static final int CATALOG = -1;

    public enum Kind {
        STAR, PLANET, MOON, DEEP_SKY;

        public boolean isBody() {
            return this == PLANET || this == MOON;
        }

        public boolean hasStar() {
            return this != DEEP_SKY;
        }

        public String translationKey() {
            return "nav.galactica.kind." + name().toLowerCase(java.util.Locale.ROOT);
        }

        static Kind byOrdinal(int o) {
            Kind[] v = values();
            return o >= 0 && o < v.length ? v[o] : STAR;
        }
    }

    public SectorKey sector() {
        return new SectorKey(ix, iy, iz);
    }

    /** Distance (pc) from galactocentric (x, y, z) pc to the target's star or deep-sky centre. */
    public double distancePc(double x, double y, double z) {
        double dx = xPc - x, dy = yPc - y, dz = zPc - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Same system star (or same deep-sky object) as {@code o}: the HUD keeps the ETA history across re-locks. */
    public boolean sameObject(NavTarget o) {
        return o != null && o.kind == kind && o.starId == starId && o.bodyId == bodyId && o.deepSkyId.equals(deepSkyId);
    }

    public static final Codec<NavTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("kind").forGetter(t -> t.kind.ordinal()),
            Codec.STRING.fieldOf("name").forGetter(NavTarget::name),
            Codec.LONG.fieldOf("star").forGetter(NavTarget::starId),
            Codec.INT.fieldOf("ix").forGetter(NavTarget::ix),
            Codec.INT.fieldOf("iy").forGetter(NavTarget::iy),
            Codec.INT.fieldOf("iz").forGetter(NavTarget::iz),
            Codec.INT.fieldOf("index").forGetter(NavTarget::index),
            Codec.LONG.fieldOf("body").forGetter(NavTarget::bodyId),
            Codec.STRING.optionalFieldOf("deepsky", "").forGetter(NavTarget::deepSkyId),
            Codec.DOUBLE.fieldOf("x").forGetter(NavTarget::xPc),
            Codec.DOUBLE.fieldOf("y").forGetter(NavTarget::yPc),
            Codec.DOUBLE.fieldOf("z").forGetter(NavTarget::zPc)
    ).apply(i, (k, n, s, x, y, z, idx, b, d, px, py, pz) -> new NavTarget(Kind.byOrdinal(k), n, s, x, y, z, idx, b, d, px, py, pz)));

    public static final StreamCodec<ByteBuf, NavTarget> STREAM_CODEC = StreamCodec.of(
            (buf, t) -> {
                ByteBufCodecs.VAR_INT.encode(buf, t.kind.ordinal());
                ByteBufCodecs.STRING_UTF8.encode(buf, t.name);
                buf.writeLong(t.starId);
                buf.writeInt(t.ix);
                buf.writeInt(t.iy);
                buf.writeInt(t.iz);
                buf.writeInt(t.index);
                buf.writeLong(t.bodyId);
                ByteBufCodecs.STRING_UTF8.encode(buf, t.deepSkyId);
                buf.writeDouble(t.xPc);
                buf.writeDouble(t.yPc);
                buf.writeDouble(t.zPc);
            },
            buf -> new NavTarget(Kind.byOrdinal(ByteBufCodecs.VAR_INT.decode(buf)), ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readLong(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readLong(),
                    ByteBufCodecs.STRING_UTF8.decode(buf), buf.readDouble(), buf.readDouble(), buf.readDouble()));
}
