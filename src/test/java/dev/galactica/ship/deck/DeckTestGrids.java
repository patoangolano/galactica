package dev.galactica.ship.deck;

import dev.galactica.ship.yard.ShipCell;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.List;

/** Small voxel worlds for the deck tests: a set of full cells. */
final class DeckTestGrids implements DeckWalker.Blocks {
    final IntOpenHashSet cells = new IntOpenHashSet();

    DeckTestGrids box(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) cells.add(ShipCell.pack(x, y, z));
        return this;
    }

    DeckTestGrids clear(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) cells.remove(ShipCell.pack(x, y, z));
        return this;
    }

    @Override
    public void collect(Aabb r, List<Aabb> out) {
        for (int x = (int) Math.floor(r.minX()); x < Math.ceil(r.maxX()); x++)
            for (int y = (int) Math.floor(r.minY()); y < Math.ceil(r.maxY()); y++)
                for (int z = (int) Math.floor(r.minZ()); z < Math.ceil(r.maxZ()); z++)
                    if (cells.contains(ShipCell.pack(x, y, z))) out.add(Aabb.cell(x, y, z));
    }

    /** A 21 × 21 deck at y = 0 (cells y 0, top at 1). */
    static DeckTestGrids deck() {
        return new DeckTestGrids().box(-10, 0, -10, 10, 0, 10);
    }
}
