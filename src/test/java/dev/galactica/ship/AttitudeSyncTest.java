package dev.galactica.ship;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttitudeSyncTest {
    private static final float EPS = 1e-5F;

    /** One client tick as Starship runs it: previous ← current, then the synced hand-over. */
    private static void tick(AttitudeSync sync, Quaternionf current, Quaternionf previous, boolean remote) {
        previous.set(current);
        sync.apply(current, remote);
    }

    @Test
    void firstValueSnapsBothEnds() {
        AttitudeSync sync = new AttitudeSync();
        Quaternionf cur = new Quaternionf(), prev = new Quaternionf();
        Quaternionf q = new Quaternionf().rotateY(1.2F);
        assertTrue(sync.receive(q, cur, prev));
        assertTrue(cur.equals(q, EPS) && prev.equals(q, EPS), "no sweep in from identity after spawn");
    }

    @Test
    void aTurnBetweenTicksSurvivesTheNextTick() {
        AttitudeSync sync = new AttitudeSync();
        Quaternionf cur = new Quaternionf(), prev = new Quaternionf();
        sync.receive(new Quaternionf(), cur, prev);
        Quaternionf turned = new Quaternionf().rotateY((float) Math.toRadians(5));
        assertFalse(sync.receive(turned, cur, prev), "packet between ticks waits");
        tick(sync, cur, prev, true);
        assertTrue(prev.equals(new Quaternionf(), EPS));
        assertTrue(cur.equals(turned, EPS));
        // The gimbal's body-frame turn: q_prev⁻¹·q_now is the 5° yaw, not the identity.
        Quaternionf d = new Quaternionf(prev).conjugate().mul(cur);
        assertEquals(5.0, Math.toDegrees(2 * Math.acos(Math.min(1, d.w))), 1e-3);
        // Render interpolation now has two different ends to slerp between.
        Quaternionf mid = prev.slerp(cur, 0.5F, new Quaternionf());
        assertEquals(2.5, Math.toDegrees(2 * Math.acos(Math.min(1, mid.w))), 1e-3);
        // A quiet tick after that: no turn.
        tick(sync, cur, prev, true);
        assertTrue(prev.equals(cur, EPS));
    }

    @Test
    void localPilotIgnoresEchoes() {
        AttitudeSync sync = new AttitudeSync();
        Quaternionf cur = new Quaternionf(), prev = new Quaternionf();
        sync.receive(new Quaternionf(), cur, prev);
        Quaternionf predicted = new Quaternionf().rotateX(0.3F);
        cur.set(predicted);
        sync.receive(new Quaternionf().rotateX(0.1F), cur, prev);
        tick(sync, cur, prev, false);
        assertTrue(cur.equals(predicted, EPS));
    }

    @Test
    void resetDropsAWaitingValue() {
        AttitudeSync sync = new AttitudeSync();
        Quaternionf cur = new Quaternionf(), prev = new Quaternionf();
        sync.receive(new Quaternionf(), cur, prev);
        sync.receive(new Quaternionf().rotateZ(1F), cur, prev);
        Quaternionf set = new Quaternionf().rotateY(-0.4F);
        cur.set(set);
        sync.reset(set);
        tick(sync, cur, prev, true);
        assertTrue(cur.equals(set, EPS));
    }
}
