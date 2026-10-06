package dev.galactica.system;

import dev.galactica.science.Units;

import java.util.List;

/**
 * A planet or moon. Mass and radius in Earth units, temperatures in kelvin, pressure in bar,
 * rotation period in hours (negative = retrograde), axial tilt in degrees.
 */
public record Body(
        long id,
        String name,
        BodyType type,
        Orbit orbit,
        double massEarth,
        double radiusEarth,
        double albedo,
        double equilibriumTemp,
        double surfaceTemp,
        double surfacePressureBar,
        Atmosphere atmosphere,
        double rotationHours,
        double axialTiltDeg,
        boolean tidallyLocked,
        boolean rings,
        Life life,
        long terrainSeed,
        List<Body> moons) {

    public enum Atmosphere { NONE, TRACE_CO2, CO2_THICK, N2_O2, N2_CH4, H2O_STEAM, H2_HE }

    public enum Life { NONE, MICROBIAL, COMPLEX }

    public double massKg() {
        return massEarth * Units.EARTH_MASS;
    }

    public double radiusMetres() {
        return radiusEarth * Units.EARTH_RADIUS;
    }

    /** Surface gravity in m/s². */
    public double surfaceGravity() {
        double r = radiusMetres();
        return Units.G * massKg() / (r * r);
    }

    public double surfaceGravityG() {
        return surfaceGravity() / Units.EARTH_GRAVITY;
    }

    public double escapeVelocity() {
        return Math.sqrt(2 * Units.G * massKg() / radiusMetres());
    }

    /** Speed of a circular orbit 1 % above the surface (or cloud deck). */
    public double lowOrbitSpeed() {
        return Math.sqrt(Units.G * massKg() / (radiusMetres() * 1.01));
    }

    /** Pressure scale height H = kT/(μ m_u g), in metres. */
    public double scaleHeight() {
        double mu = switch (atmosphere) {
            case H2_HE -> 2.3;
            case CO2_THICK, TRACE_CO2 -> 44.0;
            case H2O_STEAM -> 18.0;
            case N2_CH4 -> 28.6;
            default -> 28.97;
        };
        return Units.K_BOLTZMANN * Math.max(30, surfaceTemp) / (mu * 1.66053907e-27 * surfaceGravity());
    }

    /** Height above which the atmosphere is treated as vacuum (pressure below 1 µbar). */
    public double atmosphereTopMetres() {
        if (surfacePressureBar <= 0) return 0;
        return scaleHeight() * Math.log(Math.max(1.0, surfacePressureBar / 1e-6));
    }

    public boolean breathable() {
        return atmosphere == Atmosphere.N2_O2 && surfacePressureBar > 0.5 && surfacePressureBar < 3
                && surfaceTemp > 250 && surfaceTemp < 320;
    }
}
