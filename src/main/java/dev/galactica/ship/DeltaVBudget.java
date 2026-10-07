package dev.galactica.ship;

/**
 * Server-side velocity-change credit of a piloted ship, m/s. The pilot's client is authoritative for its velocity
 * and for what it moves into the orbital frame (Krakensbane), so each report claims a velocity change; the credit
 * refills per <i>elapsed server tick</i> with what the engines plus gravity could do in it, banks at most
 * {@link #MAX_TICKS} ticks of it, and the reports spend it. Several reports in one tick (a burst after lag, or a
 * modified client sending a hundred) share that tick's credit instead of each getting a budget of its own.
 */
public final class DeltaVBudget {
    /** Ticks of credit that can be banked: a report gap longer than this (one second) is not paid for. */
    public static final int MAX_TICKS = 20;
    private double credit;

    /** Adds {@code elapsed} ticks of {@code perTick} m/s, capped at {@link #MAX_TICKS} of it. */
    public void refill(int elapsed, double perTick) {
        if (elapsed <= 0 || !(perTick > 0)) return;
        credit = Math.min(credit + elapsed * perTick, MAX_TICKS * perTick);
    }

    /** Spends up to {@code wanted} m/s and returns how much was granted. */
    public double spend(double wanted) {
        double granted = Math.max(0, Math.min(wanted, credit));
        credit -= granted;
        return granted;
    }

    public double credit() {
        return credit;
    }
}
