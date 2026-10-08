package dev.galactica.ship.yard;

/**
 * One block of a block-built ship as the stats see it: its cell in the ship grid (body orientation: +Z nose, +Y
 * dorsal, +X port; the helm's cell is 0,0,0), its part, mass, and for a thruster the direction its nozzle points
 * ({@link #facing}, Minecraft {@code Direction} ordinal: 0 down, 1 up, 2 north −Z, 3 south +Z, 4 west −X, 5 east +X),
 * −1 otherwise.
 */
public record ShipCell(int x, int y, int z, ShipPart part, double massKg, int facing) {
    /** Unit steps of the six {@code Direction} ordinals. */
    public static final int[][] STEP = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    public static ShipCell structure(int x, int y, int z, double massKg) {
        return new ShipCell(x, y, z, ShipPart.STRUCTURE, massKg, -1);
    }

    /** Packs a cell position (each coordinate within ±511) into one int. */
    public static int pack(int x, int y, int z) {
        return (x + 512) << 20 | (y + 512) << 10 | (z + 512);
    }

    public static int unpackX(int p) { return (p >> 20 & 0x3FF) - 512; }
    public static int unpackY(int p) { return (p >> 10 & 0x3FF) - 512; }
    public static int unpackZ(int p) { return (p & 0x3FF) - 512; }

    public int packed() {
        return pack(x, y, z);
    }
}
