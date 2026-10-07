package dev.galactica.ship;

import java.util.List;

/**
 * The Starship's moving parts, free of Minecraft types: landing gear, canopy, main-engine gimbal and the RCS
 * thruster table. The client ticks this state on every visible ship; both renderers (vanilla ModelPart and the
 * optional GeckoLib one) pose the same bones from it.
 *
 * <p>Angles are in the Blockbench / design convention of {@code assets-src/ship/gen_starship.py} (degrees, right
 * handed, body frame +X port, +Y dorsal, +Z nose); the generator writes the same numbers into the GeckoLib
 * animation file.
 */
public final class ShipAnimator {
    private ShipAnimator() {}

    public static final float GEAR_FRONT_FOLD_DEG = -95F, GEAR_MAIN_FOLD_DEG = 92F, CANOPY_OPEN_DEG = -38F;
    /** Full travel times, seconds (gear: typical light-jet retraction, 1–2 s). */
    public static final float GEAR_SECONDS = 1.5F, CANOPY_SECONDS = 1.2F;
    /** Gear comes down below this height above ground (blocks) and goes up above {@link #GEAR_UP_HEIGHT}. */
    public static final double GEAR_DOWN_HEIGHT = 8.0, GEAR_UP_HEIGHT = 12.0;
    /**
     * Main-engine gimbal range. Real gimballed engines swing ±5–8.5° (RS-25: ±10.5° pitch, ±8.5° yaw; Merlin 1D:
     * ±5°); the pods use ±8°.
     */
    public static final float GIMBAL_MAX_DEG = 8F;
    /** Degrees of nozzle swing per degree per tick of body turn rate, before the clamp. */
    public static final float GIMBAL_GAIN = 1.6F;
    /** Gimbal actuator slew limit, degrees per tick (≈ 30 °/s, in the range of hydraulic TVC actuators). */
    public static final float GIMBAL_SLEW_PER_TICK = 1.5F;

    /** Moves {@code current} toward {@code target} at a full-travel time of {@code seconds}, over {@code dt} seconds. */
    public static float approach(float current, float target, float dt, float seconds) {
        float step = dt / seconds;
        if (Math.abs(target - current) <= step) return target;
        return current + Math.copySign(step, target - current);
    }

    /** Hermite smoothstep: the eased fraction the bones use, so parts start and stop gently. */
    public static float ease(float t) {
        float x = Math.clamp(t, 0F, 1F);
        return x * x * (3 - 2 * x);
    }

    /**
     * Whether the gear should be down. Hysteresis between {@link #GEAR_DOWN_HEIGHT} and {@link #GEAR_UP_HEIGHT}
     * keeps it from cycling while hovering at the threshold.
     *
     * @param down        the gear's current target
     * @param onGround    resting on something
     * @param heightAbove height of the hull above the ground below it, blocks ({@code +∞} with nothing below)
     */
    public static boolean gearDown(boolean down, boolean onGround, double heightAbove) {
        if (onGround || heightAbove <= GEAR_DOWN_HEIGHT) return true;
        if (heightAbove > GEAR_UP_HEIGHT) return false;
        return down;
    }

    /** The canopy stands open while nobody is aboard, so the pilot can climb in; it shuts once anyone boards. */
    public static boolean canopyOpen(int passengers) {
        return passengers == 0;
    }

    /**
     * Thrust-vector gimbal target for a body turn rate (degrees per tick about the body axes): the nozzle swings
     * against the turn, the way a TVC engine pushes the tail to rotate the nose. Clamped to ±{@link #GIMBAL_MAX_DEG}.
     */
    public static float gimbalTarget(float bodyRateDegPerTick) {
        return Math.clamp(-GIMBAL_GAIN * bodyRateDegPerTick, -GIMBAL_MAX_DEG, GIMBAL_MAX_DEG);
    }

    /** One actuator step toward the target, slew-limited. */
    public static float gimbalStep(float current, float target) {
        return Math.abs(target - current) <= GIMBAL_SLEW_PER_TICK ? target
                : current + Math.copySign(GIMBAL_SLEW_PER_TICK, target - current);
    }

    // ------------------------------------------------------------------------------------------------ RCS

    /** RCS command bits (what the pilot asked the ship to do, not which nozzle fires). */
    public static final int RCS_UP = 1, RCS_DOWN = 2, RCS_YAW_LEFT = 4, RCS_YAW_RIGHT = 8, RCS_ROLL_LEFT = 16, RCS_ROLL_RIGHT = 32;

    /**
     * Builds the command mask.
     *
     * @param lift      −1 … 1 dorsal translation
     * @param yawKeys   +1 nose right, −1 nose left
     * @param roll      +1 right wing down, −1 left wing down
     */
    public static int rcsMask(float lift, float yawKeys, float roll) {
        int m = 0;
        if (lift > 0) m |= RCS_UP;
        if (lift < 0) m |= RCS_DOWN;
        if (yawKeys < 0) m |= RCS_YAW_LEFT;
        if (yawKeys > 0) m |= RCS_YAW_RIGHT;
        if (roll < 0) m |= RCS_ROLL_LEFT;
        if (roll > 0) m |= RCS_ROLL_RIGHT;
        return m;
    }

    /**
     * One RCS nozzle: body-frame position (blocks from the hull centre) and exhaust direction (unit). The exhaust
     * leaves opposite to the force it makes (Newton's third law), so e.g. lifting fires the belly nozzles downward.
     */
    public record RcsJet(int command, float x, float y, float z, float dx, float dy, float dz) {}

    /** Blocks per design unit of the part table (the model is exported at {@link ShipDimensions#SCALE}). */
    private static final float PX = ShipDimensions.PX;

    private static RcsJet jet(int command, float xPx, float yPx, float zPx, float dx, float dy, float dz) {
        return new RcsJet(command, xPx * PX, yPx * PX, zPx * PX, dx, dy, dz);
    }

    /**
     * The thruster table, at the RCS blocks of the model: nose quad, pod top/bottom, pod flanks and wing tips.
     * Yaw right (nose toward −X, starboard) fires the nose's port nozzle and the starboard tail nozzle; roll right
     * (starboard wing down) fires the starboard tip upward and the port tip downward.
     */
    public static final List<RcsJet> JETS = List.of(
            jet(RCS_UP, 0, -5.2F, 30, 0, -1, 0),
            jet(RCS_UP, 14, -7.6F, -32.5F, 0, -1, 0),
            jet(RCS_UP, -14, -7.6F, -32.5F, 0, -1, 0),
            jet(RCS_DOWN, 0, 2.2F, 31, 0, 1, 0),
            jet(RCS_DOWN, 14, 6.6F, -32.5F, 0, 1, 0),
            jet(RCS_DOWN, -14, 6.6F, -32.5F, 0, 1, 0),
            jet(RCS_YAW_RIGHT, 5.2F, -0.4F, 31.6F, 1, 0, 0),
            jet(RCS_YAW_RIGHT, -20.4F, -0.5F, -31, -1, 0, 0),
            jet(RCS_YAW_LEFT, -5.2F, -0.4F, 31.6F, -1, 0, 0),
            jet(RCS_YAW_LEFT, 20.4F, -0.5F, -31, 1, 0, 0),
            jet(RCS_ROLL_RIGHT, -35, -2.3F, -27, 0, 1, 0),
            jet(RCS_ROLL_RIGHT, 35, -2.3F, -27, 0, -1, 0),
            jet(RCS_ROLL_LEFT, 35, -2.3F, -27, 0, 1, 0),
            jet(RCS_ROLL_LEFT, -35, -2.3F, -27, 0, -1, 0));
}
