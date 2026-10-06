package dev.galactica.galaxy;

/**
 * A cubic cell of the galaxy, {@link Galaxy#SECTOR_SIZE_PC} on a side, addressed by integer
 * coordinates in the galactocentric frame. ±30 kpc fits in a few thousand cells per axis.
 */
public record SectorKey(int ix, int iy, int iz) {

    public static SectorKey containing(double x, double y, double z) {
        double s = Galaxy.SECTOR_SIZE_PC;
        return new SectorKey((int) Math.floor(x / s), (int) Math.floor(y / s), (int) Math.floor(z / s));
    }

    public double minX() { return ix * Galaxy.SECTOR_SIZE_PC; }
    public double minY() { return iy * Galaxy.SECTOR_SIZE_PC; }
    public double minZ() { return iz * Galaxy.SECTOR_SIZE_PC; }

    public double centerX() { return (ix + 0.5) * Galaxy.SECTOR_SIZE_PC; }
    public double centerY() { return (iy + 0.5) * Galaxy.SECTOR_SIZE_PC; }
    public double centerZ() { return (iz + 0.5) * Galaxy.SECTOR_SIZE_PC; }

    public SectorKey offset(int dx, int dy, int dz) {
        return new SectorKey(ix + dx, iy + dy, iz + dz);
    }

    /** Short human code, e.g. "S+817.0000.0002". */
    public String code() {
        return String.format("S%+05d.%+05d.%+04d", ix, iy, iz);
    }
}
