package dev.galactica.ship;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;

/**
 * Pilot's client: reconciles its predicted velocity with the server's clamp. Every report is remembered by
 * sequence number; when the server answers that it accepted a different velocity for report {@code seq}, the
 * difference is the part of the prediction the server refused, and it is removed from the current velocity.
 * Reports sent after {@code seq} carried the same refused part (they were predicted from it), so they are rebased
 * by the same amount: when the server clamps those as well, only what is still left over gets removed, never the
 * same excess twice. Corrections for reports already reconciled, or too old to be remembered, are ignored.
 */
public final class PilotReconciler {
    /** Velocity differences below this (m/s) are float noise, not a clamp. */
    public static final double EPSILON = 0.05;
    private static final int HISTORY = 64;

    private final int[] seqs = new int[HISTORY];
    private final Vector3d[] reported = new Vector3d[HISTORY];
    private int next = 1, reconciledUpTo;
    /**
     * Last number issued by any reconciler on this client: report numbers keep rising across ships, so a new ship's
     * reports are never taken for ones the server already acknowledged ({@code NavState.rebaseAck}).
     */
    private static int lastIssued;

    public PilotReconciler() {
        for (int i = 0; i < HISTORY; i++) reported[i] = new Vector3d();
    }

    /** Records the velocity (m/s) about to be reported and returns the report's sequence number. */
    public int record(Vector3dc velocity) {
        int seq = Math.max(next, lastIssued + 1);
        next = seq + 1;
        lastIssued = seq;
        int i = Math.floorMod(seq, HISTORY);
        seqs[i] = seq;
        reported[i].set(velocity);
        return seq;
    }

    /**
     * The server accepted {@code accepted} (m/s) for report {@code seq}: returns the velocity to subtract from the
     * current prediction, or {@code null} when there is nothing left to correct.
     */
    public @Nullable Vector3d correct(int seq, Vector3dc accepted) {
        if (seq <= reconciledUpTo || seq >= next) return null;
        int i = Math.floorMod(seq, HISTORY);
        if (seqs[i] != seq) return null; // fell out of the history
        reconciledUpTo = seq;
        Vector3d excess = new Vector3d(reported[i]).sub(accepted);
        if (excess.length() < EPSILON) return null;
        for (int s = seq + 1; s < next; s++) {
            int j = Math.floorMod(s, HISTORY);
            if (seqs[j] == s) reported[j].sub(excess);
        }
        return excess;
    }

    /** Forgets the pending reports: the velocity was replaced outside the flight model, so their clamps no longer apply. */
    public void reset() {
        java.util.Arrays.fill(seqs, 0);
        reconciledUpTo = next - 1;
    }
}
