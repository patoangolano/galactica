package dev.galactica.ship.yard;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The occupancy grid: bricks, lookups, and world blocks against a rotated hull. */
class ShipOccupancyTest {
    @Test
    void lookupsAcrossBricks() {
        List<ShipCell> cells = new ArrayList<>();
        for (int x = -10; x <= 20; x += 3) for (int y = -3; y <= 9; y += 4) cells.add(ShipCell.structure(x, y, x / 2, 500));
        ShipOccupancy occ = new ShipOccupancy(cells);
        assertEquals(cells.size(), occ.size());
        for (ShipCell c : cells) assertTrue(occ.occupied(c.x(), c.y(), c.z()));
        assertFalse(occ.occupied(-9, -3, -5));
        assertFalse(occ.occupied(100, 0, 0), "outside the box");
        assertTrue(occ.hits(-9.99, -2.5, -4.5));
    }

    @Test
    void aSpireBetweenProbesIsCaught() {
        // A 16 × 1 × 16 deck: the probes sit on its outline; a world block poking up under the middle of the deck
        // is inside the hull even though no probe is near it.
        List<ShipCell> cells = new ArrayList<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) cells.add(ShipCell.structure(x, 0, z, 500));
        ShipOccupancy occ = new ShipOccupancy(cells);
        Vector3d com = new Vector3d(8, 0.5, 8);
        Vector3d pivot = new Vector3d(100, 64.5, 100); // the deck spans y 64..65 in the world
        Quaternionf level = new Quaternionf();
        Vector3d scratch = new Vector3d();
        assertTrue(occ.overlapsWorldBlock(100, 64, 100, pivot, level.conjugate(new Quaternionf()), com, scratch), "block inside the deck");
        assertFalse(occ.overlapsWorldBlock(100, 63, 100, pivot, new Quaternionf(), com, scratch), "block just under the deck");
        assertFalse(occ.overlapsWorldBlock(100, 65, 100, pivot, new Quaternionf(), com, scratch), "block just over the deck");
        assertFalse(occ.overlapsWorldBlock(120, 64, 100, pivot, new Quaternionf(), com, scratch), "beyond its edge");
        // Yawed 45°, a block at the deck's old corner (x 92, z 92) is outside, one 10 blocks out along the diagonal inside.
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(45));
        Quaternionf inverse = new Quaternionf(yaw).conjugate();
        assertFalse(occ.overlapsWorldBlock(92, 64, 92, pivot, inverse, com, scratch));
        assertTrue(occ.overlapsWorldBlock(100, 64, 110, pivot, inverse, com, scratch));
    }
}
