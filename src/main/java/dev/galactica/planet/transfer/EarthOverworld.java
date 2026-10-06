package dev.galactica.planet.transfer;

import dev.galactica.planet.SurfaceGrid;

/**
 * Earth of Sol lands in minecraft:overworld instead of a surface slot, so a survival world's own
 * Overworld is Earth: descend to Earth and you arrive above your home world, climb a ship out of the
 * Overworld and you are in orbit above Earth at the matching point.
 *
 * <p>The Overworld is laid out with Earth's {@link SurfaceGrid} (equirectangular, 1 block ≈ 10 m, a full lap
 * of 4 000 000 blocks, inside the ±30 000 000 world limit) centred on the world spawn: spawn is latitude 0,
 * longitude 0, east is +X and north is −Z. Unlike a slot the Overworld does not wrap at the antimeridian;
 * positions past ±{@link SurfaceGrid#halfWidth()} are folded back into [−π, π) when converted to longitude, so
 * a round trip is exact inside one lap.
 *
 * @param grid   Earth's surface grid
 * @param spawnX block X of latitude 0, longitude 0
 * @param spawnZ block Z of latitude 0, longitude 0
 */
public record EarthOverworld(SurfaceGrid grid, double spawnX, double spawnZ) {

    /** Planet index of Earth in {@code SystemGenerator.generate(Sol)} (Mercury, Venus, Earth). */
    public static final int EARTH_PLANET_INDEX = 2;
    /** Catalog name of Earth's host star. */
    public static final String SOL = "Sol";

    /** True when a {@code PlanetAddress} (catalog, planet, moon) points at Earth itself (not the Moon). */
    public static boolean isEarth(String catalog, int planet, int moon) {
        return SOL.equals(catalog) && planet == EARTH_PLANET_INDEX && moon < 0;
    }

    /** Overworld block X of a longitude (radians, east positive). */
    public double blockX(double lon) {
        return spawnX + grid.blockX(lon);
    }

    /** Overworld block Z of a latitude (radians, north positive). */
    public double blockZ(double lat) {
        return spawnZ + grid.blockZ(lat);
    }

    /** Longitude under Overworld column x. */
    public double longitude(double x) {
        return grid.longitude(x - spawnX);
    }

    /** Latitude under Overworld row z, clamped at the poles. */
    public double latitude(double z) {
        return grid.latitude(z - spawnZ);
    }
}
