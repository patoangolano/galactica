package dev.galactica.planet;

import dev.galactica.system.Body;

/**
 * Conditions at a player's feet, for life support: surface pressure (bar), contact temperature (K)
 * and whether an unsuited human can breathe ({@link Body#breathable()}).
 *
 * <p>{@code tempK} is the temperature of what the player touches: air, or the ground on an airless
 * body. Open space has nothing to touch, so {@link #VACUUM} carries NaN: every threshold comparison
 * (spec §3: below 200 K, above 330 K) is false and no hot or cold damage applies there; suffocation
 * is the vacuum's hazard. Use {@link #hasContactTemperature()} rather than comparing against NaN.
 */
public record PlanetEnvironment(double pressureBar, double tempK, boolean breathable) {

    public static final PlanetEnvironment VACUUM = new PlanetEnvironment(0.0, Double.NaN, false);

    public static PlanetEnvironment of(Body b) {
        return new PlanetEnvironment(b.surfacePressureBar(), b.surfaceTemp(), b.breathable());
    }

    public boolean hasContactTemperature() {
        return !Double.isNaN(tempK);
    }
}
