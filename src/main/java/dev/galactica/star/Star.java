package dev.galactica.star;

import dev.galactica.science.Units;

/**
 * An immutable star. Position is galactocentric in parsecs (see {@code GalaxyModel}); physical
 * quantities are in solar units, temperature in kelvin, age in Gyr.
 *
 * @param id          globally unique, stable id (sector hash + index, or a catalog id)
 * @param name        display name
 * @param x           galactocentric x, pc
 * @param y           galactocentric y, pc
 * @param z           galactocentric z, pc
 */
public record Star(
        long id,
        String name,
        double x, double y, double z,
        StarKind kind,
        double mass,
        double radius,
        double luminosity,
        double temperature,
        double ageGyr,
        double metallicity,
        long systemSeed,
        boolean catalog) {

    /** Morgan–Keenan style designation, e.g. "G2V", "M4.5V", "K1III", "DA", "NS". */
    public String spectralType() {
        return switch (kind) {
            case WHITE_DWARF -> temperature > 12_000 ? "DA" : "DC";
            case NEUTRON_STAR -> "NS";
            case BLACK_HOLE -> "BH";
            default -> SpectralClass.of(temperature) + kind.luminosityClass;
        };
    }

    /** Absolute visual magnitude with a blackbody bolometric correction. */
    public double absoluteMagnitude() {
        if (kind == StarKind.BLACK_HOLE) return Double.POSITIVE_INFINITY;
        double mbol = 4.74 - 2.5 * Math.log10(Math.max(1e-12, luminosity));
        return mbol - SpectralClass.bolometricCorrection(temperature);
    }

    public double radiusMetres() {
        return radius * Units.SOLAR_RADIUS;
    }

    public double massKg() {
        return mass * Units.SOLAR_MASS;
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Inner and outer edge of the conservative habitable zone in AU (Kopparapu et al. 2013, scaled by √L). */
    public double[] habitableZoneAu() {
        double s = Math.sqrt(Math.max(1e-9, luminosity));
        return new double[] {0.95 * s, 1.67 * s};
    }

    /** Water snow line in AU (Hayashi 1981 scaling, 2.7 AU for the Sun). */
    public double snowLineAu() {
        return 2.7 * Math.sqrt(Math.max(1e-9, luminosity));
    }
}
