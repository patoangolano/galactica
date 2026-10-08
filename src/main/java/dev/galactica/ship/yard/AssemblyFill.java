package dev.galactica.ship.yard;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * The flood fill that decides which blocks belong to a ship, on an abstract grid (pure Java, unit-tested): from the
 * helm, through face-connected ship blocks, stopping at air, fluids, plants and natural ground (dirt, sand, natural
 * stone; {@link Grid#kind}), so a ship built on the ground or on scaffolding of natural blocks comes away clean.
 * Positions are relative to the helm.
 *
 * <p>Fails (with the offending position where there is one) when it finds an unbreakable block (bedrock, a
 * barrier), more than {@link #MAX_BLOCKS} blocks, or a ship wider than {@link #MAX_SPAN} × {@link #MAX_HEIGHT} ×
 * {@link #MAX_SPAN}; {@link #validate} then checks the parts.
 */
public final class AssemblyFill {
    private AssemblyFill() {}

    public static final int MAX_BLOCKS = 4096;
    public static final int MAX_SPAN = 64, MAX_HEIGHT = 48;

    public enum Kind {
        /** Not part of a ship and not a reason to stop: air, fluids, plants, natural ground. */
        OUTSIDE,
        SHIP,
        /** Can never fly (bedrock, barriers, end portals). */
        UNMOVABLE
    }

    /** The world as the fill sees it, positions relative to the helm. */
    public interface Grid {
        Kind kind(int x, int y, int z);

        ShipPart part(int x, int y, int z);

        /** True if a seated head at this cell would be inside a solid block. */
        boolean blocksHead(int x, int y, int z);
    }

    public enum Error {
        TOO_MANY_BLOCKS, TOO_LARGE, UNMOVABLE_BLOCK, NO_THRUSTER, NO_MAIN_THRUSTER, NO_FUEL, TWO_HELMS, NO_HEADROOM
    }

    /** {@code positions}: packed ({@link ShipCell#pack}) cells of the ship; or the error and where it was found. */
    public record Result(IntArrayList positions, Error error, int errorX, int errorY, int errorZ) {
        public boolean ok() {
            return error == null;
        }

        static Result fail(Error e, int x, int y, int z) {
            return new Result(null, e, x, y, z);
        }
    }

    public static Result fill(Grid grid) {
        IntOpenHashSet seen = new IntOpenHashSet();
        IntArrayList out = new IntArrayList();
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        int start = ShipCell.pack(0, 0, 0);
        seen.add(start);
        queue.enqueue(start);
        int minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
        while (!queue.isEmpty()) {
            int p = queue.dequeueInt();
            int x = ShipCell.unpackX(p), y = ShipCell.unpackY(p), z = ShipCell.unpackZ(p);
            out.add(p);
            if (out.size() > MAX_BLOCKS) return Result.fail(Error.TOO_MANY_BLOCKS, x, y, z);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            if (maxX - minX >= MAX_SPAN || maxZ - minZ >= MAX_SPAN || maxY - minY >= MAX_HEIGHT) return Result.fail(Error.TOO_LARGE, x, y, z);
            for (int[] s : ShipCell.STEP) {
                int nx = x + s[0], ny = y + s[1], nz = z + s[2];
                if (Math.abs(nx) > 500 || Math.abs(ny) > 500 || Math.abs(nz) > 500) continue;
                int n = ShipCell.pack(nx, ny, nz);
                if (!seen.add(n)) continue;
                Kind k = grid.kind(nx, ny, nz);
                if (k == Kind.UNMOVABLE) return Result.fail(Error.UNMOVABLE_BLOCK, nx, ny, nz);
                if (k == Kind.SHIP) queue.enqueue(n);
            }
        }
        return new Result(out, null, 0, 0, 0);
    }

    /**
     * Part rules for a filled ship: one helm (the anchor), a seated pilot's head clear above it, at least one
     * thruster, one of them pushing the nose (+Z: nozzle toward −Z), and a hydrogen tank. {@code null} if it may fly.
     */
    public static Result validate(Grid grid, IntArrayList positions, java.util.function.IntUnaryOperator thrusterFacing) {
        int thrusters = 0, main = 0, tanks = 0;
        for (int i = 0; i < positions.size(); i++) {
            int p = positions.getInt(i);
            int x = ShipCell.unpackX(p), y = ShipCell.unpackY(p), z = ShipCell.unpackZ(p);
            ShipPart part = grid.part(x, y, z);
            if (part == ShipPart.HELM && (x != 0 || y != 0 || z != 0)) return Result.fail(Error.TWO_HELMS, x, y, z);
            if (part == ShipPart.THRUSTER) {
                thrusters++;
                if (thrusterFacing.applyAsInt(p) == 2) main++; // nozzle north (−Z, aft): thrust toward the nose
            }
            if (part == ShipPart.HYDROGEN_TANK) tanks++;
        }
        if (grid.blocksHead(0, 1, 0)) return Result.fail(Error.NO_HEADROOM, 0, 1, 0);
        if (thrusters == 0) return Result.fail(Error.NO_THRUSTER, 0, 0, 0);
        if (main == 0) return Result.fail(Error.NO_MAIN_THRUSTER, 0, 0, 0);
        if (tanks == 0) return Result.fail(Error.NO_FUEL, 0, 0, 0);
        return null;
    }
}
