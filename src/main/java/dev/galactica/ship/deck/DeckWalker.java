package dev.galactica.ship.deck;

import java.util.ArrayList;
import java.util.List;

/**
 * One body walking on a ship's deck, simulated entirely in the ship's local frame (cell coordinates, the frame's
 * axes), so the ship's speed and turn never enter the step: the walker rides the frame ({@link ShipPose#carry}) and
 * walks in it. The step copies vanilla's ground and air movement (acceleration, friction, jump, step-up, the 0.98
 * vertical drag) with gravity as a vector in the ship's axes, and collides an axis-aligned box against the ship's own
 * blocks with {@link BoxSweep}. Pure Java; one instance per walker.
 */
public final class DeckWalker {
    /** Supplies the ship's collision boxes (cell coordinates) that touch a region. */
    public interface Blocks {
        void collect(Aabb region, List<Aabb> out);
    }

    /**
     * Movement intent for one tick.
     *
     * @param forward    −1..1 (vanilla {@code zza})
     * @param strafe     −1..1, positive to the left (vanilla {@code xxa})
     * @param yawDeg     look yaw in the ship's frame, Minecraft's convention (0 faces +Z, 90 faces −X)
     * @param jump       jump key
     * @param sprint     sprinting
     * @param sneak      sneak key
     * @param speed      movement speed attribute (0.1 for a player)
     * @param jumpPower  vertical jump velocity, cells per tick (0.42 for a player)
     */
    public record Input(double forward, double strafe, double yawDeg, boolean jump, boolean sprint, boolean sneak, double speed, double jumpPower) {
        public static final Input NONE = new Input(0, 0, 0, false, false, false, 0.1, 0.42);
    }

    /** Body size and step height. */
    public record Size(double width, double height, double stepHeight) {
        public static final Size PLAYER = new Size(0.6, 1.8, 0.6);
        public static final Size ITEM = new Size(0.25, 0.25, 0.0);
    }

    // State, cell coordinates and cells per tick.
    public double x, y, z, vx, vy, vz;
    public boolean onGround, horizontalCollision;
    /** Distance walked in the ship's frame last step (horizontal), for the walk animation. */
    public double walked;
    private final Size size;
    private final List<Aabb> scratch = new ArrayList<>();

    public DeckWalker(Size size, double x, double y, double z) {
        this.size = size;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Size size() {
        return size;
    }

    public Aabb box() {
        return Aabb.feet(x, y, z, size.width(), size.height());
    }

    /** Vanilla ground friction (block friction 0.6 × 0.91) and air friction. */
    public static final double GROUND_FRICTION = 0.546, AIR_FRICTION = 0.91;
    /** Vanilla air control and its sprint boost. */
    public static final double AIR_ACCEL = 0.02;

    /**
     * Advances one tick. {@code gravity} is the acceleration in the ship's axes, cells per tick² (0, −0.08, 0 is vanilla
     * gravity on a level deck); all zero is weightless: the walker floats, steering gently with the keys (jump up,
     * sneak down), and keeps drifting.
     */
    public void step(Input in, double gx, double gy, double gz, Blocks blocks) {
        boolean weightless = gx == 0 && gy == 0 && gz == 0;
        double yaw = Math.toRadians(in.yawDeg());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        double f = in.forward(), s = in.strafe();
        double len = Math.sqrt(f * f + s * s);
        if (len > 1) {
            f /= len;
            s /= len;
        }
        f *= 0.98;
        s *= 0.98;
        double sprint = in.sprint() ? 1.3 : 1.0;
        double accel;
        if (weightless) accel = AIR_ACCEL * sprint;
        else if (onGround) accel = in.speed() * sprint * (in.sneak() ? 0.3 : 1.0);
        else accel = AIR_ACCEL * sprint;
        vx += (s * cos - f * sin) * accel;
        vz += (f * cos + s * sin) * accel;
        if (weightless) {
            if (in.jump()) vy += AIR_ACCEL;
            if (in.sneak()) vy -= AIR_ACCEL;
        } else if (in.jump() && onGround) {
            vy = in.jumpPower();
            if (in.sprint()) {
                vx += -sin * 0.2;
                vz += cos * 0.2;
            }
        }
        move(vx, vy, vz, blocks);
        if (weightless) {
            vx *= AIR_FRICTION;
            vy *= AIR_FRICTION;
            vz *= AIR_FRICTION;
        } else {
            vx += gx;
            vy += gy;
            vz += gz;
            vy *= 0.98;
            double fr = onGround ? GROUND_FRICTION : AIR_FRICTION;
            vx *= fr;
            vz *= fr;
        }
    }

    /** Moves by (dx, dy, dz) against the ship's blocks, with step-up; zeroes velocity along each blocked axis. */
    public void move(double dx, double dy, double dz, Blocks blocks) {
        Aabb box = box();
        scratch.clear();
        double up = size.stepHeight();
        blocks.collect(box.expandTowards(dx, dy, dz).expandTowards(0, up, 0).inflate(1e-3), scratch);
        // Start out of the hull if a turn or an attach left the box inside a block.
        double[] out = BoxSweep.depenetrate(box, scratch, 0.6, 4);
        if (out != null) {
            x += out[0];
            y += out[1];
            z += out[2];
            if (out[1] > 0 && vy < 0) vy = 0;
            box = box();
        }
        double[] m = BoxSweep.collideWithStep(dx, dy, dz, box, scratch, up, onGround);
        x += m[0];
        y += m[1];
        z += m[2];
        boolean xCol = Math.abs(m[0] - dx) > 1e-9, zCol = Math.abs(m[2] - dz) > 1e-9, yCol = Math.abs(m[1] - dy) > 1e-9;
        horizontalCollision = xCol || zCol;
        onGround = yCol && dy < 0;
        if (xCol) vx = 0;
        if (zCol) vz = 0;
        if (yCol) vy = 0;
        walked = Math.sqrt(m[0] * m[0] + m[2] * m[2]);
    }

    /** Whether a ship block lies under the feet within {@code depth} (footprint corners and centre). */
    public boolean floorWithin(double depth, Blocks blocks) {
        Aabb probe = new Aabb(x - size.width() / 2, y - depth, z - size.width() / 2, x + size.width() / 2, y + 0.01, z + size.width() / 2);
        scratch.clear();
        blocks.collect(probe, scratch);
        for (Aabb o : scratch) if (o.intersects(probe, 1e-6)) return true;
        return false;
    }

    /** Whether the walker's box overlaps a ship block now. */
    public boolean inBlock(Blocks blocks) {
        Aabb box = box();
        scratch.clear();
        blocks.collect(box, scratch);
        return BoxSweep.overlaps(box, scratch, 1e-4);
    }
}
