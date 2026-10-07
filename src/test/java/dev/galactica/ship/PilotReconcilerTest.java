package dev.galactica.ship;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PilotReconcilerTest {
    private static final double EPS = 1e-9;

    @Test
    void acceptedAsReportedNeedsNoCorrection() {
        PilotReconciler r = new PilotReconciler();
        int seq = r.record(new Vector3d(0, 0, 10));
        assertNull(r.correct(seq, new Vector3d(0, 0, 10)));
    }

    @Test
    void refusedPartIsRemovedOnce() {
        PilotReconciler r = new PilotReconciler();
        // The pilot predicts a burn of 2.5 m/s per tick; the server only accepts 1 m/s of the first tick.
        int s1 = r.record(new Vector3d(0, 0, 2.5));
        int s2 = r.record(new Vector3d(0, 0, 5.0));
        int s3 = r.record(new Vector3d(0, 0, 7.5));
        Vector3d e1 = r.correct(s1, new Vector3d(0, 0, 1.0));
        assertNotNull(e1);
        assertEquals(1.5, e1.z, EPS);
        // Report 2 still carried that 1.5: the server clamps it too (to 1.0 + 3.25), but relative to the rebased
        // prediction (5.0 - 1.5 = 3.5) the server accepted more, so the pilot gets 0.75 back, not another -1.5.
        Vector3d e2 = r.correct(s2, new Vector3d(0, 0, 4.25));
        assertNotNull(e2);
        assertEquals(-0.75, e2.z, EPS);
        // Report 3 was accepted as sent: no correction is sent, nothing is left to correct.
        assertNull(r.correct(s3, new Vector3d(0, 0, 7.5 - 1.5 + 0.75)));
    }

    @Test
    void staleAndUnknownCorrectionsAreIgnored() {
        PilotReconciler r = new PilotReconciler();
        int s1 = r.record(new Vector3d(1, 0, 0));
        int s2 = r.record(new Vector3d(2, 0, 0));
        assertNotNull(r.correct(s2, new Vector3d(0, 0, 0)));
        assertNull(r.correct(s1, new Vector3d(0, 0, 0)), "older than the last reconciled report");
        assertNull(r.correct(s2 + 5, new Vector3d(0, 0, 0)), "never reported");
        for (int i = 0; i < 100; i++) r.record(new Vector3d());
        assertNull(r.correct(s2 + 1, new Vector3d(9, 9, 9)), "fell out of the history");
    }

    @Test
    void resetDropsPendingReports() {
        PilotReconciler r = new PilotReconciler();
        int s1 = r.record(new Vector3d(0, 0, 5));
        r.reset();
        assertNull(r.correct(s1, new Vector3d()));
        int s2 = r.record(new Vector3d(0, 0, 5));
        assertNotNull(r.correct(s2, new Vector3d()));
    }
}
