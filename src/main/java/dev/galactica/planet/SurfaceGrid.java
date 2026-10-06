package dev.galactica.planet;

/**
 * Equirectangular map of a body's surface onto block x/z. Longitude runs along +x (east), latitude
 * along −z (north, as Minecraft's north is −Z), and both use the same angular scale so shapes stay
 * conformal at the equator. The full circumference is {@link #width()} blocks: true 1 m per block
 * up to {@link #MAX_WIDTH} (≈ 640 km radius), beyond that the map is compressed so a full lap
 * stays inside the ±30 000 000 world limit with room to spare and chunk coordinates stay small.
 *
 * @param radiusMetres physical radius of the body
 * @param width        blocks around the equator; x wraps every {@code width} blocks
 */
public record SurfaceGrid(double radiusMetres, double width) {

    /** 4 000 blocks of circumference per 1 000 km: Earth is mapped at 1 block = 10.0 m, Mars 5.3 m, the Moon 2.7 m. */
    public static final double MAX_WIDTH = 4_000_000;

    public static SurfaceGrid of(double radiusMetres) {
        double w = Math.min(2 * Math.PI * radiusMetres, MAX_WIDTH);
        // An even number of whole chunks, so the wrap seam always falls on a chunk border.
        return new SurfaceGrid(radiusMetres, Math.max(32, 32 * Math.round(w / 32)));
    }

    /** Horizontal metres represented by one block (1.0 for small bodies). */
    public double metresPerBlock() {
        return 2 * Math.PI * radiusMetres / width;
    }

    /** Radius of the map sphere in blocks: the length scale every terrain feature is measured in. */
    public double radiusBlocks() {
        return width / (2 * Math.PI);
    }

    public double halfWidth() {
        return width / 2;
    }

    /** Distance from the equator to either pole, blocks. */
    public double poleZ() {
        return width / 4;
    }

    /** Folds x into [−width/2, width/2). */
    public double wrapX(double x) {
        double w = width;
        double r = (x + w / 2) % w;
        if (r < 0) r += w;
        return r - w / 2;
    }

    /** Longitude (radians, east positive, in [−π, π)) of block column x. */
    public double longitude(double x) {
        return wrapX(x) / width * 2 * Math.PI;
    }

    /** Latitude (radians, north positive) of block row z, clamped at the poles. */
    public double latitude(double z) {
        return Math.max(-Math.PI / 2, Math.min(Math.PI / 2, -z / radiusBlocks()));
    }

    /**
     * Walking over a pole on a sphere arrives on the opposite meridian, heading back towards the
     * equator. For a point past a pole (|z| > {@link #poleZ()}) returns that point, {x', z'}: z mirrored
     * back inside the pole row and x moved half a lap; null while inside the map. Rows beyond a pole
     * would otherwise all sample the pole itself, an endless strip of identical terrain.
     */
    public double[] overPole(double x, double z) {
        double pole = poleZ();
        if (Math.abs(z) <= pole) return null;
        return new double[] {wrapX(x + halfWidth()), Math.signum(z) * 2 * pole - z};
    }

    public double blockX(double lon) {
        return wrapX(lon / (2 * Math.PI) * width);
    }

    public double blockZ(double lat) {
        return -lat * radiusBlocks();
    }

    /**
     * Point on the map sphere (radius {@link #radiusBlocks()}) under block column (x, z), written to
     * out as (x, y = north pole, z). Sampling noise on the sphere makes the x seam invisible.
     */
    public void spherePoint(double x, double z, double[] out) {
        double lon = longitude(x), lat = latitude(z), r = radiusBlocks();
        double c = Math.cos(lat);
        out[0] = r * c * Math.cos(lon);
        out[1] = r * Math.sin(lat);
        out[2] = r * c * Math.sin(lon);
    }
}
