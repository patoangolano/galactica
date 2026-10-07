package dev.galactica.nav;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Per-player navigation state, persisted and synced to the owning client.
 *
 * @param origin    galactic position of block (0, 0, 0) of the space dimension for this player —
 *                  the floating origin. The player's true position is origin + their block offset.
 * @param speed     current warp speed, m/s (0 = drive idle)
 * @param maxSpeed  drive limit, m/s
 * @param timeSeconds universal time since J2000 used for orbits, seconds
 */
public record NavState(GalacticPos origin, double speed, double maxSpeed, double timeSeconds) {

    /** A new player's space frame starts in high Earth orbit at J2000. */
    public static NavState initial() {
        return new NavState(Frames.nearEarth(0.0), 0.0, dev.galactica.config.GalacticaConfigs.get().warpMaxPcPerS() * dev.galactica.science.Units.PARSEC, 0.0);
    }

    public NavState withOrigin(GalacticPos o) {
        return new NavState(o, speed, maxSpeed, timeSeconds);
    }

    public NavState withSpeed(double s) {
        return new NavState(origin, s, maxSpeed, timeSeconds);
    }

    public NavState withMaxSpeed(double s) {
        return new NavState(origin, speed, s, timeSeconds);
    }

    public NavState withTime(double t) {
        return new NavState(origin, speed, maxSpeed, t);
    }

    public static final StreamCodec<ByteBuf, GalacticPos> POS_STREAM = StreamCodec.composite(
            ByteBufCodecs.LONG, GalacticPos::ax, ByteBufCodecs.LONG, GalacticPos::ay, ByteBufCodecs.LONG, GalacticPos::az,
            ByteBufCodecs.DOUBLE, GalacticPos::ox, ByteBufCodecs.DOUBLE, GalacticPos::oy, ByteBufCodecs.DOUBLE, GalacticPos::oz,
            GalacticPos::new);

    public static final StreamCodec<ByteBuf, NavState> STREAM_CODEC = StreamCodec.composite(
            POS_STREAM, NavState::origin,
            ByteBufCodecs.DOUBLE, NavState::speed,
            ByteBufCodecs.DOUBLE, NavState::maxSpeed,
            ByteBufCodecs.DOUBLE, NavState::timeSeconds,
            NavState::new);

    public static final Codec<NavState> CODEC = RecordCodecBuilder.create(i -> i.group(
            GalacticPos.CODEC.fieldOf("origin").forGetter(NavState::origin),
            Codec.DOUBLE.fieldOf("speed").forGetter(NavState::speed),
            Codec.DOUBLE.fieldOf("max_speed").forGetter(NavState::maxSpeed),
            Codec.DOUBLE.fieldOf("time").forGetter(NavState::timeSeconds)
    ).apply(i, NavState::new));
}
