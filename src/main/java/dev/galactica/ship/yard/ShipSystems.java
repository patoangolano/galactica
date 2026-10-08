package dev.galactica.ship.yard;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The systems a block-built ship carries besides flight (pure Java, unit-tested): docking ports, the hangar volume,
 * shields, reactors and scanners, all from its cells in the ship grid (helm at 0,0,0, +Z nose, +Y dorsal).
 *
 * @param ports            docking ports: cell and the direction ({@code Direction} ordinal) their mating face points
 * @param hangar           the hangar volume, or null
 * @param shieldGenerators shield generator blocks
 * @param reactors         reactor blocks
 * @param scanners         scanner arrays
 * @param gravityGenerators artificial-gravity generators
 */
public record ShipSystems(List<Port> ports, @Nullable Hangar hangar, int shieldGenerators, int reactors, int scanners, int gravityGenerators) {
    public static final ShipSystems NONE = new ShipSystems(List.of(), null, 0, 0, 0, 0);

    /** Tallest hangar a bay floor reaches up into, blocks. */
    public static final int MAX_HANGAR_HEIGHT = 16;
    /** A hangar lower than this is no hangar (not even a shuttle fits). */
    public static final int MIN_HANGAR_HEIGHT = 3;

    /** A docking port's cell and the {@code Direction} ordinal of its mating face. */
    public record Port(int x, int y, int z, int facing) {
        /** The mating face's centre, cell coordinates (cell x spans x..x+1). */
        public double[] faceCentre() {
            int[] s = facing >= 0 && facing < 6 ? ShipCell.STEP[facing] : new int[] {0, 1, 0};
            return new double[] {x + 0.5 + 0.5 * s[0], y + 0.5 + 0.5 * s[1], z + 0.5 + 0.5 * s[2]};
        }
    }

    /**
     * The empty space above a run of hangar bay blocks, cell coordinates inclusive: x and z span the bay floor, y runs
     * from just above it ({@code floorY + 1}) to the lowest ceiling over it.
     */
    public record Hangar(int minX, int minZ, int maxX, int maxZ, int floorY, int topY) {
        public int height() {
            return topY - floorY;
        }

        /** Whether a point (cell coordinates) lies inside the volume. */
        public boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1 && y >= floorY + 1 && y < topY + 1;
        }

        /** Whether a box of half-width {@code half} and {@code height} centred at (x, z) on the floor fits inside. */
        public boolean fits(double half, double height) {
            return 2 * half <= maxX - minX + 1 && 2 * half <= maxZ - minZ + 1 && height <= height();
        }
    }

    public boolean shields() {
        return shieldGenerators > 0;
    }

    /** Shield pool, points. */
    public float shieldCapacity() {
        return ShipPart.SHIELD_PER_GENERATOR * shieldGenerators;
    }

    /** Shield recharge, points per second: the reactors' output, none without a reactor. */
    public float shieldRecharge() {
        return shieldGenerators == 0 ? 0F : ShipPart.REACTOR_RECHARGE * reactors;
    }

    /** Scanner range, blocks; 0 without a scanner, or without a reactor to power it. */
    public double scannerRange() {
        if (scanners == 0 || reactors == 0) return 0;
        return Math.min(ShipPart.MAX_SCANNER_RANGE, ShipPart.SCANNER_RANGE + ShipPart.EXTRA_SCANNER_RANGE * (scanners - 1));
    }

    /** Whether the ship can make artificial gravity: a generator and a reactor to power it (hydrogen is checked aboard). */
    public boolean artificialGravity() {
        return gravityGenerators > 0 && reactors > 0;
    }

    public static ShipSystems compute(Collection<ShipCell> cells) {
        IntOpenHashSet occupied = new IntOpenHashSet();
        for (ShipCell c : cells) occupied.add(c.packed());
        List<Port> ports = new ArrayList<>();
        List<ShipCell> bays = new ArrayList<>();
        int shields = 0, reactors = 0, scanners = 0, gravity = 0;
        for (ShipCell c : cells) {
            switch (c.part()) {
                case DOCKING_PORT -> ports.add(new Port(c.x(), c.y(), c.z(), c.facing() < 0 ? 1 : c.facing()));
                case HANGAR_BAY -> bays.add(c);
                case SHIELD_GENERATOR -> shields++;
                case REACTOR -> reactors++;
                case SCANNER -> scanners++;
                case GRAVITY_GENERATOR -> gravity++;
                default -> { }
            }
        }
        return new ShipSystems(List.copyOf(ports), hangar(bays, occupied), shields, reactors, scanners, gravity);
    }

    /**
     * The hangar over the bay floor: the floor is the highest layer of bay blocks; x and z span it; the ceiling is the
     * lowest ship block above any floor cell (or {@link #MAX_HANGAR_HEIGHT} up, an open-topped bay).
     */
    static @Nullable Hangar hangar(List<ShipCell> bays, IntOpenHashSet occupied) {
        if (bays.isEmpty()) return null;
        int floor = Integer.MIN_VALUE;
        for (ShipCell c : bays) floor = Math.max(floor, c.y());
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int height = MAX_HANGAR_HEIGHT;
        for (ShipCell c : bays) {
            if (c.y() != floor) continue;
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
            int h = 0;
            while (h < MAX_HANGAR_HEIGHT && !occupied.contains(ShipCell.pack(c.x(), floor + 1 + h, c.z()))) h++;
            height = Math.min(height, h);
        }
        if (height < MIN_HANGAR_HEIGHT) return null;
        return new Hangar(minX, minZ, maxX, maxZ, floor, floor + height);
    }
}
