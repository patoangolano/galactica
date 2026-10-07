package dev.galactica.ship;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipSpecTest {
    @Test
    void kestrelPresetIsTodaysConstants() {
        ShipSpec k = ShipSpec.KESTREL;
        assertEquals(50_000.0, k.massKg());
        assertEquals(2.5e6, k.mainThrustN());
        assertEquals(0.5e6, k.rcsThrustN());
        assertEquals(ShipFlightModel.MAIN_ACCEL, k.mainAccel(), 1e-12);
        assertEquals(ShipFlightModel.RCS_ACCEL, k.rcsAccel(), 1e-12);
        assertEquals(1.0 / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN, k.hydrogenPerSecond(), 1e-12);
        assertEquals(Starship.MAX_HYDROGEN, k.maxHydrogen());
        assertEquals(Starship.MAX_EXOTIC, k.maxExotic());
        assertEquals(DriveRules.TIER_RANGE_LY, k.jumpRangeLy());
        assertEquals(2, k.seatCount());
        assertSame(ShipHullProbes.POINTS, k.probes());
        assertEquals(ShipDimensions.PIVOT, k.pivot());
    }

    @Test
    void kestrelStepMatchesTheLegacyStep() {
        var c = new ShipFlightModel.Controls(1, 0.5, 2, -1, 3, true);
        Quaterniond qa = new Quaterniond().rotateY(0.3), qb = new Quaterniond(qa);
        Vector3d va = new Vector3d(10, -3, 40), vb = new Vector3d(va);
        Vector3d g = new Vector3d(0, -9.8, 0), frame = new Vector3d(5, 0, 1);
        double ba = ShipFlightModel.step(qa, va, c, g, 10, ShipFlightModel.TICK, false, frame);
        double bb = ShipFlightModel.step(ShipSpec.KESTREL, qb, vb, c, g, 10, ShipFlightModel.TICK, false, frame);
        assertEquals(ba, bb, 1e-15);
        assertTrue(va.equals(vb, 1e-12));
        assertTrue(qa.equals(qb, 1e-12));
        assertEquals(ShipFlightModel.maxDeltaVPerTick(true, 9.8), ShipFlightModel.maxDeltaVPerTick(ShipSpec.KESTREL, true, 9.8), 1e-12);
    }

    @Test
    void driveTierLimitsRange() {
        assertEquals(10.0, DriveRules.rangeLy(64));
        assertEquals(25.0, DriveRules.rangeLy(64, 25));
        assertEquals(0.0, DriveRules.rangeLy(64, 0), "no hyperdrive core, no range");
        assertTrue(!DriveRules.canJump(1.0, 64, 0));
        assertTrue(DriveRules.canJump(20.0, 64, 25));
    }
}
