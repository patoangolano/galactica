package dev.galactica.ship.yard;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyFillTest {
    /** A sparse test world: ship blocks, natural ground below y = -2, everything else air. */
    static final class World implements AssemblyFill.Grid {
        final Int2ObjectOpenHashMap<ShipPart> parts = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap facing = new Int2IntOpenHashMap();
        final it.unimi.dsi.fastutil.ints.IntOpenHashSet unmovable = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        int groundTop = -2;

        World put(int x, int y, int z, ShipPart p) {
            parts.put(ShipCell.pack(x, y, z), p);
            return this;
        }

        World thruster(int x, int y, int z, int dir) {
            put(x, y, z, ShipPart.THRUSTER);
            facing.put(ShipCell.pack(x, y, z), dir);
            return this;
        }

        @Override
        public AssemblyFill.Kind kind(int x, int y, int z) {
            int p = ShipCell.pack(x, y, z);
            if (unmovable.contains(p)) return AssemblyFill.Kind.UNMOVABLE;
            if (parts.containsKey(p)) return AssemblyFill.Kind.SHIP;
            return AssemblyFill.Kind.OUTSIDE; // air above, natural ground (never filled) below groundTop
        }

        @Override
        public ShipPart part(int x, int y, int z) {
            return parts.getOrDefault(ShipCell.pack(x, y, z), ShipPart.STRUCTURE);
        }

        @Override
        public boolean blocksHead(int x, int y, int z) {
            return parts.containsKey(ShipCell.pack(x, y, z));
        }
    }

    /** Helm, a 3-long hull behind it, tank, aft thruster. */
    static World minimal() {
        return new World().put(0, 0, 0, ShipPart.HELM).put(0, 0, -1, ShipPart.STRUCTURE)
                .put(0, 0, -2, ShipPart.HYDROGEN_TANK).thruster(0, 0, -3, 2).put(0, -1, 0, ShipPart.LANDING_GEAR);
    }

    @Test
    void fillsConnectedBlocksAndLeavesTheRest() {
        World w = minimal();
        w.put(5, 0, 0, ShipPart.STRUCTURE); // not touching
        var r = AssemblyFill.fill(w);
        assertTrue(r.ok());
        assertEquals(5, r.positions().size());
        assertNull(AssemblyFill.validate(w, r.positions(), p -> w.facing.getOrDefault(p, -1)));
    }

    @Test
    void capAndBoundsAndUnmovable() {
        World big = new World().put(0, 0, 0, ShipPart.HELM);
        for (int x = 0; x < 20; x++) for (int y = 0; y < 11; y++) for (int z = -20; z <= -1; z++) big.put(x, y, z, ShipPart.STRUCTURE);
        assertEquals(AssemblyFill.Error.TOO_MANY_BLOCKS, AssemblyFill.fill(big).error());

        World wide = new World().put(0, 0, 0, ShipPart.HELM);
        for (int i = 1; i <= 70; i++) wide.put(i, 0, 0, ShipPart.STRUCTURE);
        assertEquals(AssemblyFill.Error.TOO_LARGE, AssemblyFill.fill(wide).error());

        World anchored = minimal();
        anchored.unmovable.add(ShipCell.pack(0, -2, 0));
        var r = AssemblyFill.fill(anchored);
        assertEquals(AssemblyFill.Error.UNMOVABLE_BLOCK, r.error());
        assertEquals(-2, r.errorY());
    }

    @Test
    void partRules() {
        World noFuel = new World().put(0, 0, 0, ShipPart.HELM).thruster(0, 0, -1, 2);
        assertEquals(AssemblyFill.Error.NO_FUEL, check(noFuel));
        World noThruster = new World().put(0, 0, 0, ShipPart.HELM).put(0, 0, -1, ShipPart.HYDROGEN_TANK);
        assertEquals(AssemblyFill.Error.NO_THRUSTER, check(noThruster));
        World sideways = new World().put(0, 0, 0, ShipPart.HELM).put(0, 0, -1, ShipPart.HYDROGEN_TANK).thruster(1, 0, 0, 5);
        assertEquals(AssemblyFill.Error.NO_MAIN_THRUSTER, check(sideways));
        World twoHelms = minimal().put(0, 0, 1, ShipPart.HELM);
        assertEquals(AssemblyFill.Error.TWO_HELMS, check(twoHelms));
        World roofed = minimal().put(0, 1, 0, ShipPart.STRUCTURE);
        assertEquals(AssemblyFill.Error.NO_HEADROOM, check(roofed));
    }

    private static AssemblyFill.Error check(World w) {
        var r = AssemblyFill.fill(w);
        if (!r.ok()) return r.error();
        var v = AssemblyFill.validate(w, r.positions(), p -> w.facing.getOrDefault(p, -1));
        return v == null ? null : v.error();
    }
}
