package dev.galactica.nav;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Per-player navigation state, persisted and synced to the owning client.
 *
 * @param origin      galactic position of block (0, 0, 0) of the space dimension for this player —
 *                    the floating origin. The player's true position is origin + their block offset.
 * @param speed       current warp speed, m/s (0 = drive idle)
 * @param maxSpeed    drive limit, m/s
 * @param timeSeconds universal time since J2000 used for orbits, seconds
 * @param frameVx     velocity of the space frame relative to its anchor body, galactic m/s ({@link OrbitalFrame}):
 *                    the craft's inertial velocity minus its own block velocity
 * @param frameVy     see {@code frameVx}
 * @param frameVz     see {@code frameVx}
 * @param anchor      id of the body the frame co-moves with ({@link OrbitalFrame#ANCHOR_STAR}, or
 *                    {@link OrbitalFrame#ANCHOR_UNSET} until the next space tick adopts one)
 * @param rebaseAck   sequence number of the last pilot report whose Krakensbane rebase the server has folded into the
 *                    frame velocity: the pilot's client adds the rebases it sent after that one to the synced frame
 *                    velocity, so flight assist measures against the frame as it will be, not one round trip old. Not
 *                    persisted (report numbers restart with the client).
 */
public record NavState(GalacticPos origin, double speed, double maxSpeed, double timeSeconds,
                       double frameVx, double frameVy, double frameVz, long anchor, int rebaseAck) {

    public NavState(GalacticPos origin, double speed, double maxSpeed, double timeSeconds,
                    double frameVx, double frameVy, double frameVz, long anchor) {
        this(origin, speed, maxSpeed, timeSeconds, frameVx, frameVy, frameVz, anchor, 0);
    }

    public NavState(GalacticPos origin, double speed, double maxSpeed, double timeSeconds) {
        this(origin, speed, maxSpeed, timeSeconds, 0, 0, 0, OrbitalFrame.ANCHOR_UNSET);
    }

    /** A new player's space frame starts in high Earth orbit at J2000. */
    public static NavState initial() {
        return new NavState(Frames.nearEarth(0.0), 0.0, dev.galactica.config.GalacticaConfigs.get().warpMaxPcPerS() * dev.galactica.science.Units.PARSEC, 0.0);
    }

    public NavState withOrigin(GalacticPos o) {
        return new NavState(o, speed, maxSpeed, timeSeconds, frameVx, frameVy, frameVz, anchor, rebaseAck);
    }

    public NavState withSpeed(double s) {
        return new NavState(origin, s, maxSpeed, timeSeconds, frameVx, frameVy, frameVz, anchor, rebaseAck);
    }

    public NavState withMaxSpeed(double s) {
        return new NavState(origin, speed, s, timeSeconds, frameVx, frameVy, frameVz, anchor, rebaseAck);
    }

    public NavState withTime(double t) {
        return new NavState(origin, speed, maxSpeed, t, frameVx, frameVy, frameVz, anchor, rebaseAck);
    }

    /** The frame's velocity relative to its anchor body, galactic m/s (a fresh array). */
    public double[] frameVelocity() {
        return new double[] {frameVx, frameVy, frameVz};
    }

    public NavState withFrame(double[] v, long anchorId) {
        return new NavState(origin, speed, maxSpeed, timeSeconds, v[0], v[1], v[2], anchorId, rebaseAck);
    }

    public NavState withRebaseAck(int seq) {
        return new NavState(origin, speed, maxSpeed, timeSeconds, frameVx, frameVy, frameVz, anchor, seq);
    }

    /** At rest relative to whatever body dominates where the next space tick finds the craft (teleports, arrivals). */
    public NavState resetFrame() {
        return withFrame(new double[3], OrbitalFrame.ANCHOR_UNSET);
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
            ByteBufCodecs.DOUBLE, NavState::frameVx,
            ByteBufCodecs.DOUBLE, NavState::frameVy,
            ByteBufCodecs.DOUBLE, NavState::frameVz,
            ByteBufCodecs.LONG, NavState::anchor,
            ByteBufCodecs.VAR_INT, NavState::rebaseAck,
            NavState::new);

    public static final Codec<NavState> CODEC = RecordCodecBuilder.create(i -> i.group(
            GalacticPos.CODEC.fieldOf("origin").forGetter(NavState::origin),
            Codec.DOUBLE.fieldOf("speed").forGetter(NavState::speed),
            Codec.DOUBLE.fieldOf("max_speed").forGetter(NavState::maxSpeed),
            Codec.DOUBLE.fieldOf("time").forGetter(NavState::timeSeconds),
            Codec.DOUBLE.optionalFieldOf("frame_vx", 0.0).forGetter(NavState::frameVx),
            Codec.DOUBLE.optionalFieldOf("frame_vy", 0.0).forGetter(NavState::frameVy),
            Codec.DOUBLE.optionalFieldOf("frame_vz", 0.0).forGetter(NavState::frameVz),
            Codec.LONG.optionalFieldOf("anchor", OrbitalFrame.ANCHOR_UNSET).forGetter(NavState::anchor)
    ).apply(i, (o, s, m, t, x, y, z, a) -> new NavState(o, s, m, t, x, y, z, a, 0)));
}
