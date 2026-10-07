package dev.galactica.nav.autopilot;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The autopilot of one player, decided by the server ({@link AutopilotServer}) and synced to every client tracking the
 * player, so the pilot's client flies the phase it names and passengers see it on their HUD. Not persisted: a relog
 * leaves the ship in manual control.
 *
 * @param engaged  flying the course
 * @param phase    what it is doing now
 * @param mode     what it does on arrival at a planet or moon
 * @param hops     hyperjumps left in the planned chain (0 outside {@link Phase#JUMP})
 * @param eta      estimated seconds to arrival, negative when unknown
 */
public record AutopilotState(boolean engaged, Phase phase, Mode mode, int hops, float eta) {

    public static final AutopilotState OFF = new AutopilotState(false, Phase.IDLE, Mode.ORBIT, 0, -1F);

    public enum Phase {
        /** Not flying. */
        IDLE,
        /** Turning toward the next leg before anything fires. */
        ALIGN,
        /** Spooling the hyperdrive toward the next star of the chain. */
        JUMP,
        /** Warp drive toward the target, released short of it. */
        WARP,
        /** Burning into a circular orbit around the target body. */
        INSERT,
        /** Powered vertical descent from orbit altitude through the entry shell. */
        DESCENT,
        /** On the body's surface level: levelled, coming down on the RCS to the gear. */
        LANDING;

        public String translationKey() {
            return "hud.galactica.autopilot.phase." + name().toLowerCase(java.util.Locale.ROOT);
        }

        static Phase byOrdinal(int o) {
            Phase[] v = values();
            return o >= 0 && o < v.length ? v[o] : IDLE;
        }
    }

    public enum Mode {
        /** Park in a circular orbit (a star or a deep-sky object: stop at a safe distance). */
        ORBIT,
        /** Continue through the entry into a landing on the gear. */
        LAND;

        static Mode byOrdinal(int o) {
            return o == 1 ? LAND : ORBIT;
        }
    }

    public AutopilotState withPhase(Phase p) {
        return new AutopilotState(engaged, p, mode, hops, eta);
    }

    public AutopilotState withProgress(int hopsLeft, double etaSeconds) {
        return new AutopilotState(engaged, phase, mode, hopsLeft, (float) etaSeconds);
    }

    public static final StreamCodec<ByteBuf, AutopilotState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, AutopilotState::engaged,
            ByteBufCodecs.VAR_INT.map(Phase::byOrdinal, Phase::ordinal), AutopilotState::phase,
            ByteBufCodecs.VAR_INT.map(Mode::byOrdinal, Mode::ordinal), AutopilotState::mode,
            ByteBufCodecs.VAR_INT, AutopilotState::hops,
            ByteBufCodecs.FLOAT, AutopilotState::eta,
            AutopilotState::new);
}
