package dev.galactica.ship;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static dev.galactica.ship.ShipFlightModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipFlightModelTest {
    private static final Vector3d NO_G = new Vector3d();
    private static final Vector3d EARTH_G = new Vector3d(0, -9.80665, 0);

    private static double run(Quaterniond q, Vector3d v, Controls c, Vector3d g, double fuel, int ticks) {
        double burned = 0;
        for (int i = 0; i < ticks; i++) burned += step(q, v, c, g, fuel - burned, TICK);
        return burned;
    }

    @Test
    void fullThrustIsFiveG() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        double burned = run(q, v, new Controls(1, 0, 0, 0, 0, false), NO_G, 400, 20);
        assertEquals(50.0, v.z, 1e-9, "2.5 MN on 50 t for 1 s");
        assertEquals(0.0, v.x, 1e-12);
        assertEquals(0.05, burned, 1e-12, "1 unit per 20 s of main thrust");
    }

    @Test
    void thrustFollowsTheNose() {
        Quaterniond q = new Quaterniond().rotateY(Math.toRadians(-90)); // yawed 90° right: nose to −X
        Vector3d v = new Vector3d();
        run(q, v, new Controls(1, 0, 0, 0, 0, false), NO_G, 400, 20);
        assertEquals(-50.0, v.x, 1e-9);
        assertEquals(0.0, v.z, 1e-9);
    }

    @Test
    void twentySecondsOfThrustBurnsOneCell() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        double burned = run(q, v, new Controls(-1, 0, 0, 0, 0, true), NO_G, 400, 400);
        assertEquals(1.0, burned, 1e-9);
        assertEquals(MAX_SPEED, v.length(), 1e-9, "capped");
    }

    @Test
    void dryTanksKillTheMainEngineButNotTheRcs() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        double burned = run(q, v, new Controls(1, 1, 0, 0, 0, false), NO_G, 0, 20);
        assertEquals(0.0, burned);
        assertEquals(0.0, v.z, 1e-12);
        assertEquals(RCS_ACCEL, v.y, 1e-9);
    }

    @Test
    void fuelRunsOutMidBurn() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        double burned = run(q, v, new Controls(1, 0, 0, 0, 0, false), NO_G, 0.01, 100);
        assertEquals(0.01, burned, 1e-12, "never burns more than is aboard");
    }

    @Test
    void coupledModeNullsSideslipDecoupledKeepsIt() {
        Quaterniond q = new Quaterniond();
        Vector3d coupled = new Vector3d(5, 0, 20);
        run(q, coupled, Controls.IDLE, NO_G, 400, 10);
        assertEquals(0.0, coupled.x, 1e-12, "10 m/s² RCS kills 5 m/s in 0.5 s");
        assertEquals(20.0, coupled.z, 1e-12, "forward speed is the pilot's");

        Vector3d decoupled = new Vector3d(5, 0, 20);
        run(q, decoupled, new Controls(0, 0, 0, 0, 0, false), NO_G, 400, 10);
        assertEquals(5.0, decoupled.x, 1e-12);
    }

    @Test
    void coupledModeHoversAgainstOneG() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        run(q, v, Controls.IDLE, EARTH_G, 400, 200);
        assertEquals(0.0, v.y, 1e-9, "RCS authority 10 m/s² exceeds 9.81 m/s²");

        Vector3d falling = new Vector3d();
        run(q, falling, new Controls(0, 0, 0, 0, 0, false), EARTH_G, 400, 20);
        assertEquals(-9.80665, falling.y, 1e-9);
    }

    @Test
    void commandedLiftIsNotDamped() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        run(q, v, new Controls(0, 1, 0, 0, 0, true), NO_G, 400, 20);
        assertEquals(RCS_ACCEL, v.y, 1e-9);
    }

    @Test
    void rotationsStayNormalisedAndFollowConventions() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        for (int i = 0; i < 1000; i++) step(q, v, new Controls(0, 0, 3.1, -2.7, 4.4, true), NO_G, 0, TICK);
        assertEquals(1.0, q.lengthSquared(), 1e-12);

        Quaterniond right = new Quaterniond();
        step(right, v, new Controls(0, 0, 9, 0, 0, true), NO_G, 0, TICK);
        assertTrue(right.transform(new Vector3d(0, 0, 1)).x < 0, "yaw right turns the nose toward −X");

        Quaterniond down = new Quaterniond();
        step(down, v, new Controls(0, 0, 0, 9, 0, true), NO_G, 0, TICK);
        assertTrue(down.transform(new Vector3d(0, 0, 1)).y < 0, "positive pitch is nose down");

        Quaterniond roll = new Quaterniond();
        step(roll, v, new Controls(0, 0, 0, 0, 9, true), NO_G, 0, TICK);
        assertTrue(roll.transform(new Vector3d(-1, 0, 0)).y < 0, "roll right drops the starboard (−X) wing");
    }

    @Test
    void turnRateIsCapped() {
        Quaterniond q = new Quaterniond();
        step(q, new Vector3d(), new Controls(0, 0, 90, 0, 0, true), NO_G, 0, TICK);
        double yaw = Math.toDegrees(Math.atan2(-q.transform(new Vector3d(0, 0, 1)).x, q.transform(new Vector3d(0, 0, 1)).z));
        assertEquals(MAX_TURN_PER_TICK, yaw, 1e-9);
    }

    @Test
    void serverBoundCoversEverythingThePilotCanDo() {
        Quaterniond q = new Quaterniond().rotateXYZ(0.3, 1.1, -0.4);
        Vector3d v = new Vector3d(3, -4, 7);
        Vector3d before = new Vector3d(v);
        step(q, v, new Controls(1, 1, 0, 0, 0, true), EARTH_G, 400, TICK);
        assertTrue(v.distance(before) <= maxDeltaVPerTick(true, 9.80665) + 1e-9);
    }

    @Test
    void driveRules() {
        assertEquals(0.0, DriveRules.rangeLy(0));
        assertEquals(4.0, DriveRules.rangeLy(4));
        assertEquals(10.0, DriveRules.rangeLy(64), "tier-1 drive tops out at 10 ly");
        assertEquals(5, DriveRules.exoticCost(4.37), "α Cen costs ⌈4.37⌉");
        assertEquals(1, DriveRules.exoticCost(0.2));
        assertEquals(3, DriveRules.exoticCost(3.0));
        assertTrue(DriveRules.canJump(4.37, 5));
        assertFalse(DriveRules.canJump(4.37, 4));
        assertFalse(DriveRules.canJump(10.5, 64));
        assertFalse(DriveRules.canJump(Double.NaN, 64));
        assertEquals(0.0, DriveRules.warpHydrogenPerTick(999 * dev.galactica.science.Units.C));
        assertEquals(1.0, DriveRules.warpHydrogenPerTick(2e3 * dev.galactica.science.Units.C) * 200, 1e-12, "1 per 10 s");
    }

    @Test
    void serverAcceptsTurnsWithinTheCap() {
        Quaterniond reported = new Quaterniond().rotateY(Math.toRadians(20));
        Quaterniond out = clampAttitude(new Quaterniond(), reported, 1);
        assertEquals(0.0, out.difference(reported, new Quaterniond()).angle(), 1e-6, "20° in one tick is under 3 × 9°");
    }

    @Test
    void serverClampsTeleportingAttitude() {
        Quaterniond out = clampAttitude(new Quaterniond(), new Quaterniond().rotateX(Math.toRadians(170)), 1);
        assertEquals(3 * MAX_TURN_PER_TICK + 1, Math.toDegrees(out.angle()), 1e-6, "slerped to the per-tick limit");
        Quaterniond flipped = new Quaterniond().rotateY(Math.toRadians(10));
        flipped.set(-flipped.x, -flipped.y, -flipped.z, -flipped.w);
        assertEquals(10.0, Math.toDegrees(clampAttitude(new Quaterniond(), flipped, 1).angle()), 1e-6, "−q is q: no long way round");
        assertEquals(170.0, Math.toDegrees(clampAttitude(new Quaterniond(), new Quaterniond().rotateX(Math.toRadians(170)), 10).angle()), 1e-6,
                "a late report covers all elapsed ticks");
    }

    @Test
    void mainEngineAllowanceOnlyWhenBurning() {
        assertEquals((MAIN_ACCEL + Math.sqrt(3) * RCS_ACCEL) * TICK, maxDeltaVPerTick(true, 0), 1e-12);
        assertEquals(Math.sqrt(3) * RCS_ACCEL * TICK, maxDeltaVPerTick(false, 0), 1e-12);
    }

    private static final Vector3d MARS_G = new Vector3d(0, -3.721, 0);

    @Test
    void parkedShipSettlesOnItsGearInsteadOfHovering() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d(3.0, 0, 2.0); // touched down sliding at 3.6 m/s
        Controls idle = new Controls(0, 0, 0, 0, 0, true);
        for (int i = 0; i < 40; i++) {
            step(q, v, idle, MARS_G, 400, TICK, true);
            v.y = Math.max(0, v.y); // the ground stops the fall
        }
        assertEquals(0.0, Math.hypot(v.x, v.z), 1e-12, "static friction holds it");
        // Unlike flight assist, gravity is not cancelled: the hull presses on its gear (the ground stops it).
        Vector3d w = new Vector3d();
        step(q, w, idle, MARS_G, 400, TICK, true);
        assertEquals(-3.721 * TICK, w.y, 1e-12);
    }

    @Test
    void gearFrictionIsCoulomb() {
        Vector3d v = new Vector3d(10, 0, 0);
        gearFriction(v, MARS_G, TICK);
        assertEquals(10 - GEAR_FRICTION * 3.721 * TICK, v.x, 1e-12);
        Vector3d slow = new Vector3d(0.05, -1, 0);
        gearFriction(slow, MARS_G, TICK);
        assertEquals(0, slow.x, 1e-12);
        assertEquals(-1, slow.y, 1e-12, "the fall is the ground's business");
    }

    @Test
    void liftOrMainEngineStillTakesOff() {
        Quaterniond q = new Quaterniond();
        Vector3d v = new Vector3d();
        step(q, v, new Controls(0, 1, 0, 0, 0, true), MARS_G, 400, TICK, true);
        assertTrue(v.y > 0, "RCS up lifts off");
        assertFalse(parked(new Controls(1, 0, 0, 0, 0, true), 400, true));
        assertTrue(parked(new Controls(1, 0, 0, 0, 0, true), 0, true), "no fuel, no main engine");
        assertFalse(parked(new Controls(0, 0, 0, 0, 0, true), 400, false));
    }
}
