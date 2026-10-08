package dev.galactica.ship.yard;

import dev.galactica.ship.ShipSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Docking ports, hangars, shields, reactors and scanners from a ship's cells. */
class ShipSystemsTest {
    private static ShipCell part(int x, int y, int z, ShipPart p, int facing) {
        return new ShipCell(x, y, z, p, p.massKg, facing);
    }

    @Test
    void hangarIsTheSpaceAboveTheBayUpToTheLowestCeiling() {
        List<ShipCell> cells = new ArrayList<>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 7; z++) cells.add(part(x, 0, z, ShipPart.HANGAR_BAY, -1));
        // A ceiling 6 up over most of the bay, one beam at 4 up.
        for (int x = 0; x < 5; x++) for (int z = 0; z < 7; z++) cells.add(ShipCell.structure(x, 7, z, 500));
        cells.add(ShipCell.structure(2, 5, 3, 500));
        ShipSystems s = ShipSystems.compute(cells);
        ShipSystems.Hangar h = s.hangar();
        assertNotNull(h);
        assertEquals(0, h.minX());
        assertEquals(4, h.maxX());
        assertEquals(6, h.maxZ());
        assertEquals(0, h.floorY());
        assertEquals(4, h.height(), "the beam at y 5 is the ceiling: cells 1..4 are free");
        assertTrue(h.contains(2.5, 1.2, 3.5));
        assertFalse(h.contains(2.5, 5.5, 3.5));
        assertFalse(h.contains(5.5, 1.2, 3.5));
        assertTrue(h.fits(2.0, 4.0));
        assertFalse(h.fits(3.0, 4.0));
    }

    @Test
    void aBayWithNoHeadroomIsNoHangar() {
        List<ShipCell> cells = new ArrayList<>();
        cells.add(part(0, 0, 0, ShipPart.HANGAR_BAY, -1));
        cells.add(ShipCell.structure(0, 2, 0, 500));
        assertNull(ShipSystems.compute(cells).hangar());
    }

    @Test
    void portsShieldsReactorsScanners() {
        List<ShipCell> cells = new ArrayList<>();
        cells.add(part(0, 3, 0, ShipPart.DOCKING_PORT, 1));
        cells.add(part(1, 0, 0, ShipPart.SHIELD_GENERATOR, -1));
        cells.add(part(2, 0, 0, ShipPart.SHIELD_GENERATOR, -1));
        cells.add(part(3, 0, 0, ShipPart.SCANNER, -1));
        cells.add(part(4, 0, 0, ShipPart.SCANNER, -1));
        ShipSystems unpowered = ShipSystems.compute(cells);
        assertEquals(1, unpowered.ports().size());
        assertEquals(2 * ShipPart.SHIELD_PER_GENERATOR, unpowered.shieldCapacity());
        assertEquals(0F, unpowered.shieldRecharge(), "no reactor, no recharge");
        assertEquals(0.0, unpowered.scannerRange(), "no reactor, no scanner");
        double[] face = unpowered.ports().getFirst().faceCentre();
        assertEquals(0.5, face[0], 1e-9);
        assertEquals(4.0, face[1], 1e-9, "an up-facing port's face is the top of its cell");
        cells.add(part(5, 0, 0, ShipPart.REACTOR, -1));
        ShipSystems powered = ShipSystems.compute(cells);
        assertEquals(ShipPart.REACTOR_RECHARGE, powered.shieldRecharge());
        assertEquals(ShipPart.SCANNER_RANGE + ShipPart.EXTRA_SCANNER_RANGE, powered.scannerRange());
    }

    @Test
    void theFreighterCarriesTheShuttleInItsHangarAndBothDock() {
        ShipTemplate freighter = ShipTemplate.freighter(), shuttle = ShipTemplate.shuttle();
        ShipSystems f = ShipSystems.compute(freighter.cells()), s = ShipSystems.compute(shuttle.cells());
        ShipSystems.Hangar h = f.hangar();
        assertNotNull(h, "the freighter has a hangar");
        int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE, x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (ShipCell c : shuttle.cells()) {
            y0 = Math.min(y0, c.y()); y1 = Math.max(y1, c.y());
            x0 = Math.min(x0, c.x()); x1 = Math.max(x1, c.x());
            z0 = Math.min(z0, c.z()); z1 = Math.max(z1, c.z());
        }
        assertTrue(y1 - y0 + 1 <= h.height(), "shuttle height " + (y1 - y0 + 1) + " in hangar " + h.height());
        assertTrue(x1 - x0 + 1 <= h.maxX() - h.minX() + 1, "shuttle width fits the bay");
        assertTrue(z1 - z0 + 1 <= h.maxZ() - h.minZ() + 1, "shuttle length fits the bay");
        assertFalse(f.ports().isEmpty());
        assertFalse(s.ports().isEmpty());
        assertTrue(f.shields() && f.shieldRecharge() > 0 && f.scannerRange() > 0, "freighter systems are powered");
        ShipSpec fs = ShipStats.compute(freighter.cells()).spec(), ss = ShipStats.compute(shuttle.cells()).spec();
        assertTrue(fs.massKg() > ss.massKg(), "the freighter carries the shuttle, not the reverse");
        ShipSystems e = ShipSystems.compute(ShipTemplate.explorer().cells());
        assertEquals(2, e.scanners());
        assertTrue(e.scannerRange() > ShipPart.SCANNER_RANGE);
    }
}
