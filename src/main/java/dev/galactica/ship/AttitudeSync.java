package dev.galactica.ship;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Tick-aligned intake of a synced attitude, for ships a client sees but does not fly.
 *
 * <p>Synced data arrives between ticks. Writing it straight into the current attitude means the next tick's
 * "previous = current" step erases the turn before anything can see it: render interpolation has nothing to slerp
 * and the body-frame turn rate, q_prev⁻¹·q_now, that drives the nozzle gimbal is always the identity. Instead the
 * value is held here and handed over by {@link #apply} right after the tick has saved the previous attitude, so
 * the pair (previous, current) spans exactly one synced step. This is the usual snapshot-interpolation scheme with a
 * one-tick (50 ms) buffer (Bernier 2001, "Latency Compensating Methods in Client/Server In-game Protocol Design").
 */
public final class AttitudeSync {
    private final Quaternionf latest = new Quaternionf();
    private boolean pending, seen;

    /**
     * A synced attitude arrived. The first one after spawn snaps both {@code current} and {@code previous} (no sweep
     * in from the identity) and returns true; later ones wait for {@link #apply}.
     */
    public boolean receive(Quaternionfc q, Quaternionf current, Quaternionf previous) {
        latest.set(q);
        if (!seen) {
            seen = true;
            current.set(q);
            previous.set(q);
            pending = false;
            return true;
        }
        pending = true;
        return false;
    }

    /**
     * Call each tick after {@code previous.set(current)}: moves a waiting synced attitude into {@code current}.
     * {@code remote} false (this client now flies the ship) drops it, since the local prediction owns the attitude.
     */
    public void apply(Quaternionf current, boolean remote) {
        if (!pending) return;
        pending = false;
        if (remote) current.set(latest);
    }

    /** An authoritative set (deploy, commands, load) supersedes anything waiting. */
    public void reset(Quaternionfc q) {
        latest.set(q);
        pending = false;
    }
}
