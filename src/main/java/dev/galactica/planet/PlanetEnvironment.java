package dev.galactica.planet;

import dev.galactica.system.Body;

/**
 * Conditions at a player's feet, for life support: surface pressure (bar), temperature (K) and
 * whether an unsuited human can breathe ({@link Body#breathable()}).
 */
public record PlanetEnvironment(double pressureBar, double tempK, boolean breathable) {

    /** Open space: no pressure, and the 2.7 K of the cosmic microwave background to radiate into. */
    public static final PlanetEnvironment VACUUM = new PlanetEnvironment(0.0, 2.725, false);

    public static PlanetEnvironment of(Body b) {
        return new PlanetEnvironment(b.surfacePressureBar(), b.surfaceTemp(), b.breathable());
    }
}
