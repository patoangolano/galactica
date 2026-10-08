package dev.galactica.ship.yard;

import dev.galactica.ship.ShipFlightModel;
import dev.galactica.ship.ShipSpec;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipStatsTest {
    private static List<ShipCell> rod(int n, double m) {
        List<ShipCell> cells = new ArrayList<>();
        for (int z = 0; z < n; z++) cells.add(ShipCell.structure(0, 0, z, m));
        return cells;
    }

    @Test
    void rodMassCentreAndInertia() {
        int n = 10;
        double m = 100;
        var r = ShipStats.compute(rod(n, m));
        assertEquals(n * m, r.spec().massKg(), 1e-9);
        assertEquals(n / 2.0, r.com().z, 1e-9);
        assertEquals(0.5, r.com().x, 1e-9);
        // About the centre, perpendicular to the rod: Σ m (z_i − c)² + n m/6, with (z_i − c) = i − (n−1)/2.
        double expected = 0;
        for (int i = 0; i < n; i++) expected += m * Math.pow(i - (n - 1) / 2.0, 2);
        expected += n * m / 6;
        assertEquals(expected, r.spec().inertia().x(), 1e-6);
        assertEquals(expected, r.spec().inertia().y(), 1e-6);
        assertEquals(n * m / 6, r.spec().inertia().z(), 1e-6);
    }

    @Test
    void cubeAndLShape() {
        List<ShipCell> cube = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) cube.add(ShipCell.structure(x, y, z, 10));
        var r = ShipStats.compute(cube);
        assertTrue(r.com().equals(new Vector3d(1.5, 1.5, 1.5), 1e-12));
        assertEquals(r.spec().inertia().x(), r.spec().inertia().y(), 1e-9);
        assertEquals(1.5F, r.spec().pivot(), 1e-6F);
        assertEquals(3.0F, r.spec().boxWidth(), 1e-6F);
        // L: a heavy block at the corner pulls the centre of mass toward it.
        List<ShipCell> l = new ArrayList<>(rod(3, 10));
        l.add(ShipCell.structure(1, 0, 0, 10));
        l.add(ShipCell.structure(2, 0, 0, 70));
        var rl = ShipStats.compute(l);
        assertEquals((0.5 * 30 + 1.5 * 10 + 2.5 * 70) / 110, rl.com().x, 1e-9);
    }

    @Test
    void thrustPerDirectionAndParts() {
        List<ShipCell> cells = new ArrayList<>(rod(4, 500));
        cells.add(new ShipCell(0, 0, 4, ShipPart.HELM, ShipPart.HELM.massKg, -1));
        cells.add(new ShipCell(0, 0, -1, ShipPart.THRUSTER, ShipPart.THRUSTER.massKg, 2)); // nozzle aft: pushes the nose
        cells.add(new ShipCell(1, 0, -1, ShipPart.THRUSTER, ShipPart.THRUSTER.massKg, 2));
        cells.add(new ShipCell(1, 0, 3, ShipPart.THRUSTER, ShipPart.THRUSTER.massKg, 3));  // nozzle forward: brake
        cells.add(new ShipCell(0, -1, 2, ShipPart.THRUSTER, ShipPart.THRUSTER.massKg, 0)); // nozzle down: lift
        cells.add(new ShipCell(1, 0, 1, ShipPart.RCS, ShipPart.RCS.massKg, -1));
        cells.add(new ShipCell(1, 0, 2, ShipPart.HYDROGEN_TANK, ShipPart.HYDROGEN_TANK.massKg, -1));
        cells.add(new ShipCell(1, 0, 0, ShipPart.HYPERDRIVE_CORE, ShipPart.HYPERDRIVE_CORE.massKg, -1));
        cells.add(new ShipCell(2, 0, 0, ShipPart.HYPERDRIVE_CORE, ShipPart.HYPERDRIVE_CORE.massKg, -1));
        ShipSpec s = ShipStats.compute(cells).spec();
        assertEquals(2 * ShipPart.THRUSTER_N, s.mainThrustN(), 1e-6);
        assertEquals(ShipPart.THRUSTER_N, s.reverseThrustN(), 1e-6);
        assertEquals(ShipPart.RCS_N + ShipPart.HELM_RCS_N, s.rcsThrustN(), 1e-6);
        assertEquals(s.rcsThrustN() + ShipPart.THRUSTER_N, s.liftThrustN(), 1e-6);
        assertEquals(ShipPart.TANK_CELLS, s.maxHydrogen());
        assertEquals(2 * ShipPart.CORE_EXOTIC, s.maxExotic());
        assertEquals(15.0, s.jumpRangeLy(), 1e-9);
        assertEquals(1, s.seatCount());
        // Fuel flow scales with the largest engine group: two thrusters burn 2 × 0.6/2.5 of the Kestrel's flow.
        assertEquals(2 * ShipPart.THRUSTER_N / ShipFlightModel.MAIN_THRUST_N / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN, s.hydrogenPerSecond(), 1e-12);
    }

    @Test
    void builtShipFliesThroughTheSharedModel() {
        List<ShipCell> cells = new ArrayList<>(rod(4, 500));
        cells.add(new ShipCell(0, 0, -1, ShipPart.THRUSTER, ShipPart.THRUSTER.massKg, 2));
        ShipSpec s = ShipStats.compute(cells).spec();
        Vector3d v = new Vector3d();
        ShipFlightModel.step(s, new Quaterniond(), v, new ShipFlightModel.Controls(1, 0, 0, 0, 0, false), new Vector3d(), 10,
                ShipFlightModel.TICK, false, new Vector3d());
        assertEquals(s.mainAccel() * ShipFlightModel.TICK, v.z, 1e-9);
        assertEquals(ShipPart.THRUSTER_N / 4000.0, s.mainAccel(), 1e-9);
    }

    @Test
    void probesCoverTheHullAndStayCapped() {
        // A 3 × 1 × 5 slab: every exposed corner is a probe, and all lie on the hull's surface.
        List<ShipCell> slab = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 5; z++) slab.add(ShipCell.structure(x, 0, z, 100));
        var r = ShipStats.compute(slab);
        float[][] p = r.spec().probes();
        assertTrue(p.length > 20 && p.length <= ShipStats.MAX_PROBES);
        double maxX = 0;
        for (float[] q : p) {
            assertTrue(Math.abs(q[0]) <= 1.5 && Math.abs(q[1]) <= 0.5 && Math.abs(q[2]) <= 2.5, "inside the hull's box");
            maxX = Math.max(maxX, q[0]);
        }
        assertEquals(1.5 - 1.0 / 64, maxX, 1e-6, "the outer corner, nudged 1/64 inward");
        // A big freighter is decimated to the cap, landing gear feet kept.
        List<ShipCell> big = new ArrayList<>();
        for (int x = 0; x < 30; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 40; z++) big.add(ShipCell.structure(x, y, z, 100));
        big.add(new ShipCell(15, -1, 20, ShipPart.LANDING_GEAR, ShipPart.LANDING_GEAR.massKg, -1));
        var rb = ShipStats.compute(big);
        assertTrue(rb.spec().probes().length <= ShipStats.MAX_PROBES);
        double lowest = 0;
        for (float[] q : rb.spec().probes()) lowest = Math.min(lowest, q[1]);
        assertEquals(-1 - rb.com().y + 1.0 / 64, lowest, 1e-4, "the gear foot is a probe");
        assertTrue(rb.spec().turnScale() < 1.0, "a 9 600-block hull turns slower than the Kestrel");
    }
}
