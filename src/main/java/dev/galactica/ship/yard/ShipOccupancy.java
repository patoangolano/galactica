package dev.galactica.ship.yard;

import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;

/**
 * Body-frame occupancy of a large block-built ship, for terrain collision beyond what the hull probes see (pure Java,
 * unit-tested). The probes cover a hull's outline with at most {@link ShipStats#MAX_PROBES} points; on a big hull
 * they are decimated, and a ridge or a spire can slip between them into the ship. The occupancy grid closes that gap
 * the other way round: every solid world block near the hull is moved into the ship's frame and tested against the
 * ship's own cells (the dual of Create's contraption collider).
 *
 * <p>Layout: a bitmap of 8³-cell bricks over the hull's bounding box, so a lookup is two array reads and a bit test,
 * and a whole empty brick is skipped by its zero word.
 */
public final class ShipOccupancy {
    /** Brick edge, cells. */
    static final int B = 8;

    private final int minX, minY, minZ, bx, by, bz, endX, endY, endZ;
    /** One long[8] (512 bits) per brick, null where a brick is empty. */
    private final long[][] bricks;
    private final int count;
    /** Every occupied cell, packed ({@link ShipCell#pack}), each once. */
    private final int[] packed;

    /** World points sampled per block: its centre and its corners pulled in by {@link #INSET} towards the centre. */
    public static final double INSET = 0.05;

    public ShipOccupancy(Collection<ShipCell> cells) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (ShipCell c : cells) {
            x0 = Math.min(x0, c.x()); x1 = Math.max(x1, c.x());
            y0 = Math.min(y0, c.y()); y1 = Math.max(y1, c.y());
            z0 = Math.min(z0, c.z()); z1 = Math.max(z1, c.z());
        }
        if (cells.isEmpty()) x0 = y0 = z0 = x1 = y1 = z1 = 0;
        minX = x0;
        minY = y0;
        minZ = z0;
        endX = x1 + 1;
        endY = y1 + 1;
        endZ = z1 + 1;
        bx = (x1 - x0) / B + 1;
        by = (y1 - y0) / B + 1;
        bz = (z1 - z0) / B + 1;
        bricks = new long[bx * by * bz][];
        int n = 0;
        it.unimi.dsi.fastutil.ints.IntArrayList list = new it.unimi.dsi.fastutil.ints.IntArrayList(cells.size());
        for (ShipCell c : cells) {
            int lx = c.x() - minX, ly = c.y() - minY, lz = c.z() - minZ;
            int brick = (lx / B * by + ly / B) * bz + lz / B;
            long[] words = bricks[brick];
            if (words == null) words = bricks[brick] = new long[B * B * B / 64];
            int bit = ((lx % B) * B + ly % B) * B + lz % B;
            if ((words[bit >>> 6] & 1L << bit) == 0) {
                n++;
                list.add(c.packed());
            }
            words[bit >>> 6] |= 1L << bit;
        }
        count = n;
        packed = list.toIntArray();
    }

    /** Every occupied cell, packed ({@link ShipCell#pack}); do not modify. */
    public int[] packedCells() {
        return packed;
    }

    /** Lowest occupied cell corner, cell coordinates. */
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }

    /** One past the highest occupied cell, cell coordinates. */
    public int maxX() { return endX; }
    public int maxY() { return endY; }
    public int maxZ() { return endZ; }

    public int size() {
        return count;
    }

    /** Whether ship-grid cell (x, y, z) holds a block. */
    public boolean occupied(int x, int y, int z) {
        int lx = x - minX, ly = y - minY, lz = z - minZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= bx * B || ly >= by * B || lz >= bz * B) return false;
        long[] words = bricks[(lx / B * by + ly / B) * bz + lz / B];
        if (words == null) return false;
        int bit = ((lx % B) * B + ly % B) * B + lz % B;
        return (words[bit >>> 6] & 1L << bit) != 0;
    }

    /** Whether a ship-grid point (cell coordinates, cell x spans x..x+1) is inside a ship block. */
    public boolean hits(double x, double y, double z) {
        return occupied((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Whether the world block at {@code (wx, wy, wz)} (its minimum corner) overlaps the ship: its centre or one of its
     * eight corners, pulled {@link #INSET} inwards, falls in a ship cell. {@code pivot} is the ship's centre of mass in
     * the world, {@code inverse} its attitude inverted, {@code com} the centre of mass in cell coordinates.
     */
    public boolean overlapsWorldBlock(int wx, int wy, int wz, Vector3dc pivot, Quaternionfc inverse, Vector3dc com, Vector3d scratch) {
        double lo = INSET, hi = 1 - INSET;
        for (int k = 0; k < 9; k++) {
            double ox = k == 8 ? 0.5 : (k & 1) == 0 ? lo : hi;
            double oy = k == 8 ? 0.5 : (k & 2) == 0 ? lo : hi;
            double oz = k == 8 ? 0.5 : (k & 4) == 0 ? lo : hi;
            scratch.set(wx + ox - pivot.x(), wy + oy - pivot.y(), wz + oz - pivot.z());
            inverse.transform(scratch);
            if (hits(scratch.x + com.x(), scratch.y + com.y(), scratch.z + com.z())) return true;
        }
        return false;
    }
}
