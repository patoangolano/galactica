package dev.galactica.ship.yard;

import dev.galactica.ship.ShipSpec;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every prefab is one connected, valid ship that hovers at 1 g, climbs, and fits the caps. */
class ShipTemplateTest {
    private static final double G = 9.80665;

    private static AssemblyFill.Grid grid(ShipTemplate t) {
        Int2ObjectOpenHashMap<ShipTemplate.Mat> m = new Int2ObjectOpenHashMap<>();
        for (var p : t.placements()) m.put(ShipCell.pack(p.x(), p.y(), p.z()), p.mat());
        return new AssemblyFill.Grid() {
            @Override
            public AssemblyFill.Kind kind(int x, int y, int z) {
                return m.containsKey(ShipCell.pack(x, y, z)) ? AssemblyFill.Kind.SHIP : AssemblyFill.Kind.OUTSIDE;
            }

            @Override
            public ShipPart part(int x, int y, int z) {
                var mat = m.get(ShipCell.pack(x, y, z));
                return mat == null ? ShipPart.STRUCTURE : mat.part;
            }

            @Override
            public boolean blocksHead(int x, int y, int z) {
                return m.containsKey(ShipCell.pack(x, y, z));
            }
        };
    }

    @Test
    void everyPrefabAssemblesAndFlies() {
        for (String name : ShipTemplate.NAMES) {
            ShipTemplate t = ShipTemplate.byName(name);
            AssemblyFill.Grid g = grid(t);
            var fill = AssemblyFill.fill(g);
            assertTrue(fill.ok(), name + ": " + fill.error());
            assertEquals(t.size(), fill.positions().size(), name + " is one connected piece");
            var facing = new Int2ObjectOpenHashMap<ShipTemplate.Mat>();
            for (var p : t.placements()) facing.put(ShipCell.pack(p.x(), p.y(), p.z()), p.mat());
            assertNull(AssemblyFill.validate(g, fill.positions(), p -> facing.get(p).facing), name + " passes the part rules");
            ShipSpec s = ShipStats.compute(t.cells()).spec();
            assertTrue(s.liftAccel() > 1.05 * G, name + " hovers at 1 g: lift " + s.liftAccel());
            assertTrue(s.mainAccel() > 0.4 * G, name + " climbs: main " + s.mainAccel());
            assertTrue(s.probes().length <= ShipStats.MAX_PROBES);
            assertTrue(s.seatCount() >= 2, name + " seats crew");
            // The lift jets now burn hydrogen: a full tank must still hover the ship for a while (≥ 2 min at 1 g).
            double hover = s.liftEngineShare(G) * s.liftHydrogenPerSecond();
            assertTrue(hover == 0 || s.maxHydrogen() / hover > 120, name + " hovers " + s.maxHydrogen() / hover + " s on a full load");
        }
    }

    @Test
    void autopilotLandingMarginComesFromTheShip() {
        assertEquals(dev.galactica.nav.autopilot.AutopilotMath.MAX_LANDING_GRAVITY,
                dev.galactica.nav.autopilot.AutopilotMath.maxLandingGravity(ShipSpec.KESTREL), 1e-12, "the Kestrel keeps today's limit");
        double freighter = dev.galactica.nav.autopilot.AutopilotMath.maxLandingGravity(ShipStats.compute(ShipTemplate.freighter().cells()).spec());
        assertTrue(freighter < G && freighter > 3.72, "the freighter lands on Mars but not on Earth: " + freighter);
        double shuttle = dev.galactica.nav.autopilot.AutopilotMath.maxLandingGravity(ShipStats.compute(ShipTemplate.shuttle().cells()).spec());
        assertTrue(shuttle > G, "the shuttle's lift jets land it on Earth: " + shuttle);
    }

    @Test
    void classesDiffer() {
        ShipSpec k = ShipStats.compute(ShipTemplate.kestrel().cells()).spec();
        assertEquals(400F, k.maxHydrogen(), "four tanks, like the modelled Kestrel");
        assertEquals(2, k.seatCount());
        assertEquals(10.0, k.jumpRangeLy());
        ShipTemplate freighter = ShipTemplate.freighter();
        assertTrue(freighter.size() > 2000 && freighter.size() <= AssemblyFill.MAX_BLOCKS, "freighter " + freighter.size());
        ShipSpec e = ShipStats.compute(ShipTemplate.explorer().cells()).spec();
        assertEquals(20.0, e.jumpRangeLy());
        ShipSpec f = ShipStats.compute(freighter.cells()).spec();
        assertTrue(f.turnScale() < k.turnScale(), "the freighter turns slower");
        assertTrue(ShipTemplate.shuttle().size() > 250);
    }
}
