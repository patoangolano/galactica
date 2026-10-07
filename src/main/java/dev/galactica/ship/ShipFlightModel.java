package dev.galactica.ship;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Newtonian 6-DOF flight of the Starship at block scale (1 block = 1 m), free of Minecraft types so the
 * client prediction and the server's plausibility check run the same arithmetic.
 *
 * <p>Body frame: +Z nose, +Y dorsal, +X port (Minecraft faces +Z at yaw 0, so the pilot's right is −X).
 * Rotation is kinematic (mouse and keys set rates directly, like every flight game's "mouse flight"):
 * an angular-momentum model would add nothing the pilot can feel at 20 Hz and makes server validation harder.
 *
 * <p>Numbers (spec §2): 50 t ship, 2.5 MN main engine (50 m/s² ≈ 5 g), 0.5 MN RCS (10 m/s²). Coupled mode is
 * flight assist: the RCS nulls velocity across the nose, so the ship flies where it points and, with RCS
 * authority above 1 g, hovers on Earth-like worlds. Decoupled is pure inertia.
 */
public final class ShipFlightModel {
    private ShipFlightModel() {}

    public static final double MASS_KG = 50_000.0;
    public static final double MAIN_THRUST_N = 2.5e6;
    public static final double RCS_THRUST_N = 0.5e6;
    public static final double MAIN_ACCEL = MAIN_THRUST_N / MASS_KG;
    public static final double RCS_ACCEL = RCS_THRUST_N / MASS_KG;
    /** Game balance: one hydrogen cell feeds the main engine for 20 s at full throttle. */
    public static final double MAIN_SECONDS_PER_HYDROGEN = 20.0;
    /**
     * Block-space speed cap. Beyond ~15 blocks per tick vanilla's vehicle-move check and chunk streaming fall
     * behind; real distances are covered by the warp drive, which moves the frame instead of the ship.
     */
    public static final double MAX_SPEED = 300.0;
    public static final double TICK = 0.05;
    /** Keyboard yaw (A/D) and roll (Q/E) rates, degrees per second. */
    public static final double KEY_YAW_RATE = 45.0, KEY_ROLL_RATE = 90.0;
    /**
     * Hard cap on any single-axis turn per tick, 180 °/s. Yaw, pitch and roll each obey it, so one tick turns the
     * ship by at most 3 × this angle (a composed rotation never exceeds the sum of its parts):
     * {@link #clampAttitude} holds pilot reports to that.
     */
    public static final double MAX_TURN_PER_TICK = 9.0;

    /**
     * One tick of pilot input.
     *
     * @param thrust  main engine, −1 (full reverse) … 1 (full forward)
     * @param lift    RCS along the dorsal axis, −1 … 1
     * @param yawDeg  turn this step, degrees, positive = nose right
     * @param pitchDeg turn this step, degrees, positive = nose down (Minecraft mouse convention)
     * @param rollDeg turn this step, degrees, positive = right wing down
     * @param coupled flight assist on
     */
    public record Controls(double thrust, double lift, double yawDeg, double pitchDeg, double rollDeg, boolean coupled) {
        public static final Controls IDLE = new Controls(0, 0, 0, 0, 0, true);
    }

    /**
     * Advances attitude and velocity by {@code dt} seconds in place.
     *
     * @param attitude body-to-world rotation, renormalised on return
     * @param velocity world velocity, m/s
     * @param gravity  external acceleration (gravity), m/s², world frame
     * @param hydrogen fuel available; the main engine is dead at 0, the RCS is cold gas and always works
     * @return hydrogen burned this step (never more than {@code hydrogen})
     */
    public static double step(Quaterniond attitude, Vector3d velocity, Controls c, Vector3dc gravity, double hydrogen, double dt) {
        return step(attitude, velocity, c, gravity, hydrogen, dt, false);
    }

    /**
     * Landing-gear friction coefficient (rubber-tyred / skid gear on rock and regolith, 0.6–0.8): the largest
     * horizontal deceleration the ground can apply is μ·g.
     */
    public static final double GEAR_FRICTION = 0.8;

    /**
     * Whether a ship on the ground is parked: nothing asks it to leave (no main engine, no upward RCS). Then the
     * ground, not the RCS, carries its weight, and the gear's friction holds it.
     */
    public static boolean parked(Controls c, double hydrogen, boolean grounded) {
        return grounded && c.lift() <= 0 && (hydrogen <= 0 || c.thrust() == 0);
    }

    /**
     * As {@link #step(Quaterniond, Vector3d, Controls, Vector3dc, double, double)}, knowing whether the hull rests on
     * the ground. A parked ship ({@link #parked}) gets no flight-assist hover (the assist would otherwise null the
     * velocity along the dorsal axis, gravity included, and float the hull on its own thrusters instead of
     * letting it settle on its gear) and its gear holds it: Coulomb friction removes up to μ·g·dt of horizontal speed
     * each step and stops it outright below that (static friction), so a landed ship comes to rest on flat ground
     * and on any slope gentler than atan μ ≈ 39°. Taking off (lift up, or the main engine) is untouched.
     */
    public static double step(Quaterniond attitude, Vector3d velocity, Controls c, Vector3dc gravity, double hydrogen, double dt,
                              boolean grounded) {
        if (parked(c, hydrogen, grounded)) {
            c = new Controls(c.thrust(), Math.min(0, c.lift()), c.yawDeg(), c.pitchDeg(), c.rollDeg(), false);
            double burned = step(attitude, velocity, c, gravity, hydrogen, dt, false);
            gearFriction(velocity, gravity, dt);
            return burned;
        }
        double yaw = clamp(c.yawDeg(), MAX_TURN_PER_TICK), pitch = clamp(c.pitchDeg(), MAX_TURN_PER_TICK),
                roll = clamp(c.rollDeg(), MAX_TURN_PER_TICK);
        attitude.rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll)).normalize();

        double throttle = hydrogen > 0 ? clamp(c.thrust(), 1) : 0;
        double lift = clamp(c.lift(), 1);
        Vector3d fwd = attitude.transform(new Vector3d(0, 0, 1));
        Vector3d up = attitude.transform(new Vector3d(0, 1, 0));
        Vector3d side = attitude.transform(new Vector3d(1, 0, 0));
        velocity.fma(throttle * MAIN_ACCEL * dt, fwd).fma(lift * RCS_ACCEL * dt, up).fma(dt, gravity);

        if (c.coupled()) {
            // Each RCS axis has its own thruster pair, so the budget is per axis; an axis the pilot is
            // commanding is left alone.
            double budget = RCS_ACCEL * dt;
            velocity.fma(-toward0(velocity.dot(side), budget), side);
            if (lift == 0) velocity.fma(-toward0(velocity.dot(up), budget), up);
        }

        double v = velocity.length();
        if (v > MAX_SPEED) velocity.mul(MAX_SPEED / v);
        return Math.min(hydrogen, Math.abs(throttle) * dt / MAIN_SECONDS_PER_HYDROGEN);
    }

    /**
     * Coulomb friction of the gear on the ground: removes up to μ·|g|·dt of the velocity across gravity, and all of
     * it when less is left (the ship sticks). Velocity along gravity is left to the collision with the ground.
     */
    static void gearFriction(Vector3d velocity, Vector3dc gravity, double dt) {
        double g = gravity.length();
        if (g < 1e-9) return;
        Vector3d down = new Vector3d(gravity).div(g);
        double along = velocity.dot(down);
        Vector3d across = new Vector3d(velocity).fma(-along, down);
        double speed = across.length();
        double stop = GEAR_FRICTION * g * dt;
        if (speed <= stop) velocity.sub(across);
        else velocity.fma(-stop / speed, across);
    }

    /** The part of {@code x} a budget-limited thruster can cancel this step. */
    static double toward0(double x, double budget) {
        return Math.copySign(Math.min(Math.abs(x), budget), x);
    }

    /**
     * Largest velocity change one tick of the engines can produce: the server's bound on a pilot's report.
     * Main engine only counts with fuel aboard; RCS acts on up to three axes at once.
     */
    public static double maxDeltaVPerTick(boolean fuelled, double gravity) {
        return ((fuelled ? MAIN_ACCEL : 0) + Math.sqrt(3) * RCS_ACCEL + gravity) * TICK;
    }

    /**
     * The server's bound on a reported attitude: {@code reported} if it lies within {@code 3 × MAX_TURN_PER_TICK}
     * per elapsed tick of {@code current} (plus 1° of float slack), otherwise the slerp toward it that turns
     * exactly that far. Writes into {@code current}.
     */
    public static Quaterniond clampAttitude(Quaterniond current, Quaterniond reported, int ticks) {
        Quaterniond r = new Quaterniond(reported).normalize();
        if (current.dot(r) < 0) r.set(-r.x, -r.y, -r.z, -r.w); // q and −q are the same rotation: take the short way
        double angle = 2 * Math.acos(Math.min(1, Math.abs(current.dot(r))));
        double max = Math.toRadians(3 * MAX_TURN_PER_TICK * Math.max(1, ticks) + 1);
        if (angle <= max) return current.set(r);
        return current.slerp(r, max / angle).normalize();
    }

    private static double clamp(double x, double lim) {
        return Double.isFinite(x) ? Math.max(-lim, Math.min(lim, x)) : 0;
    }
}
