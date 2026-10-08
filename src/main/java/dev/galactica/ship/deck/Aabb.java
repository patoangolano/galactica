package dev.galactica.ship.deck;

/**
 * An axis-aligned box in a ship's local frame (ship-grid cell coordinates: cell x spans x..x+1), free of Minecraft
 * types so the deck physics is unit-tested. Immutable.
 */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    /** A box {@code width} wide and {@code height} tall standing with its feet centre at (x, y, z). */
    public static Aabb feet(double x, double y, double z, double width, double height) {
        double h = width / 2;
        return new Aabb(x - h, y, z - h, x + h, y + height, z + h);
    }

    /** The unit cube of cell (x, y, z). */
    public static Aabb cell(int x, int y, int z) {
        return new Aabb(x, y, z, x + 1, y + 1, z + 1);
    }

    public Aabb move(double dx, double dy, double dz) {
        return new Aabb(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public Aabb inflate(double d) {
        return new Aabb(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    public Aabb inflate(double dx, double dy, double dz) {
        return new Aabb(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Grows the box towards a move, as vanilla's {@code expandTowards}. */
    public Aabb expandTowards(double dx, double dy, double dz) {
        return new Aabb(dx < 0 ? minX + dx : minX, dy < 0 ? minY + dy : minY, dz < 0 ? minZ + dz : minZ,
                dx > 0 ? maxX + dx : maxX, dy > 0 ? maxY + dy : maxY, dz > 0 ? maxZ + dz : maxZ);
    }

    /** Strict overlap with a tolerance: boxes that only touch, or overlap by less than {@code eps}, do not count. */
    public boolean intersects(Aabb o, double eps) {
        return maxX - eps > o.minX && minX + eps < o.maxX && maxY - eps > o.minY && minY + eps < o.maxY
                && maxZ - eps > o.minZ && minZ + eps < o.maxZ;
    }

    public boolean intersects(Aabb o) {
        return intersects(o, 0);
    }

    public double min(int axis) {
        return axis == 0 ? minX : axis == 1 ? minY : minZ;
    }

    public double max(int axis) {
        return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
    }

    public double centreX() {
        return (minX + maxX) / 2;
    }

    public double centreZ() {
        return (minZ + maxZ) / 2;
    }

    /** Moves along one axis (0 x, 1 y, 2 z). */
    public Aabb move(int axis, double d) {
        return axis == 0 ? move(d, 0, 0) : axis == 1 ? move(0, d, 0) : move(0, 0, d);
    }
}
