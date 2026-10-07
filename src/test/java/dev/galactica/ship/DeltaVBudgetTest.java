package dev.galactica.ship;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeltaVBudgetTest {
    private static final double PER_TICK = ShipFlightModel.maxDeltaVPerTick(true, 9.8) * 1.1 + 0.5;

    @Test
    void anHonestPilotReportingEveryTickGetsEveryTicksBurn() {
        DeltaVBudget b = new DeltaVBudget();
        for (int tick = 0; tick < 200; tick++) {
            b.refill(1, PER_TICK);
            assertEquals(PER_TICK, b.spend(PER_TICK), 1e-9, "a full burn each tick is never clamped");
        }
    }

    @Test
    void spammedReportsShareOneTicksBudget() {
        // A modified client sends 100 reports per server tick, each claiming a full tick of burn (all of it rebased
        // into the orbital frame). Before, each report got its own budget: 100× the engines.
        DeltaVBudget b = new DeltaVBudget();
        double total = 0;
        int ticks = 600;
        for (int tick = 0; tick < ticks; tick++) {
            b.refill(1, PER_TICK);
            for (int r = 0; r < 100; r++) total += b.spend(PER_TICK);
        }
        assertEquals(ticks * PER_TICK, total, 1e-6, "no more than the engines can do over the elapsed ticks");
    }

    @Test
    void aBurstAfterLagIsPaidFromTheBank() {
        DeltaVBudget b = new DeltaVBudget();
        b.refill(3, PER_TICK); // three ticks without a report, then three reports in one tick
        for (int r = 0; r < 3; r++) assertEquals(PER_TICK, b.spend(PER_TICK), 1e-9);
        assertEquals(0, b.spend(PER_TICK), 1e-12, "the fourth is not paid for");
    }

    @Test
    void theBankIsCapped() {
        DeltaVBudget b = new DeltaVBudget();
        for (int i = 0; i < 1000; i++) b.refill(20, PER_TICK);
        assertEquals(DeltaVBudget.MAX_TICKS * PER_TICK, b.credit(), 1e-9);
        b.refill(0, PER_TICK);
        b.refill(-5, PER_TICK);
        assertEquals(DeltaVBudget.MAX_TICKS * PER_TICK, b.credit(), 1e-9);
    }
}
