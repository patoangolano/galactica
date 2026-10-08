package dev.galactica.ship;

import dev.galactica.ship.yard.ShipStats;
import dev.galactica.ship.yard.ShipTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Downward thrusters (VTOL lift) burn hydrogen like the main engine, and do nothing with dry tanks. */
class ShipLiftFuelTest {
    private static final double G = 9.80665;
    private static final ShipSpec SHUTTLE = ShipStats.compute(ShipTemplate.shuttle().cells()).spec();

    @Test
    void liftEnginesAreTheDorsalThrustBeyondTheRcs() {
        assertTrue(SHUTTLE.liftEngineN() > 0, "the shuttle has lift jets");
        assertEquals(0.0, ShipSpec.KESTREL.liftEngineN(), 1e-9, "the modelled Kestrel lifts on its RCS alone");
        assertEquals(SHUTTLE.liftEngineN() / ShipFlightModel.MAIN_THRUST_N / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN,
                SHUTTLE.liftHydrogenPerSecond(), 1e-12);
        assertEquals(SHUTTLE.rcsAccel(), SHUTTLE.liftAccel(false), 1e-12, "dry, only the cold-gas RCS lifts");
    }

    @Test
    void holdingTheLiftKeyBurnsHydrogen() {
        Vector3d v = new Vector3d();
        double burned = ShipFlightModel.step(SHUTTLE, new Quaterniond(), v, new ShipFlightModel.Controls(0, 1, 0, 0, 0, true),
                new Vector3d(0, -G, 0), 100, ShipFlightModel.TICK, false, new Vector3d());
        assertTrue(burned > 0, "lift key burns: " + burned);
        assertTrue(v.y > 0, "and climbs");
        Vector3d dry = new Vector3d();
        double none = ShipFlightModel.step(SHUTTLE, new Quaterniond(), dry, new ShipFlightModel.Controls(0, 1, 0, 0, 0, true),
                new Vector3d(0, -G, 0), 0, ShipFlightModel.TICK, false, new Vector3d());
        assertEquals(0.0, none, 1e-12);
        assertTrue(dry.y < v.y, "dry tanks: the RCS alone lifts less");
    }

    @Test
    void assistedHoverBurnsTheShareOfLiftItNeeds() {
        // A hover at 1 g with the assist on: the RCS gives the first rcsAccel for free, the lift jets the rest.
        Vector3d v = new Vector3d(0, -G * ShipFlightModel.TICK, 0); // falling one tick of gravity
        double burned = ShipFlightModel.step(SHUTTLE, new Quaterniond(), v, ShipFlightModel.Controls.IDLE,
                new Vector3d(0, -G, 0), 100, ShipFlightModel.TICK, false, new Vector3d());
        assertTrue(burned > 0, "hovering on lift jets burns hydrogen: " + burned);
        // The freighter's RCS cannot hold 1 g: its lift jets carry most of the hover, and the server bills that share.
        ShipSpec freighter = ShipStats.compute(ShipTemplate.freighter().cells()).spec();
        assertEquals(0.0, SHUTTLE.liftEngineShare(G), 1e-12, "the shuttle's RCS alone holds 1 g");
        double share = freighter.liftEngineShare(G);
        assertTrue(share > 0 && share < 1, "share " + share);
        double server = ShipFlightModel.liftBurnRate(freighter, false, true, false, new Vector3d(0, 1, 0), new Vector3d(0, -G, 0));
        assertEquals(share * freighter.liftHydrogenPerSecond(), server, 1e-12, "the server bills the same hover share");
        assertEquals(0.0, ShipFlightModel.liftBurnRate(SHUTTLE, false, true, true, new Vector3d(0, 1, 0), new Vector3d(0, -G, 0)),
                "resting on the ground costs nothing");
        assertEquals(SHUTTLE.liftHydrogenPerSecond(), ShipFlightModel.liftBurnRate(SHUTTLE, true, false, true,
                new Vector3d(0, 1, 0), new Vector3d(0, -G, 0)), 1e-12, "the lift key fires every lift jet");
        assertEquals(0.0, ShipFlightModel.liftBurnRate(ShipSpec.KESTREL, true, true, false, new Vector3d(0, 1, 0), new Vector3d(0, -G, 0)));
    }

    @Test
    void theServerGrantsNoLiftEnginesWithoutFuel() {
        double fuelled = ShipFlightModel.maxDeltaVPerTick(SHUTTLE, false, true, G);
        double dry = ShipFlightModel.maxDeltaVPerTick(SHUTTLE, false, false, G);
        assertTrue(dry < fuelled, dry + " < " + fuelled);
    }
}
