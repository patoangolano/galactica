package dev.galactica.survival;

/**
 * What a player's skin is exposed to: total pressure (bar), ambient temperature (K) and whether the
 * gas is breathable. Pure data; {@link SurvivalEnvironment} decides which one applies to a player.
 *
 * @param pressureBar total ambient pressure, 0 in vacuum
 * @param tempK       ambient (surface or radiative equilibrium) temperature in kelvin
 * @param breathable  true only for an N₂/O₂ mix with a usable O₂ partial pressure
 */
public record Environment(double pressureBar, double tempK, boolean breathable) {

    /** Sea-level Earth: 1.013 bar, the 288 K global mean surface temperature. */
    public static final Environment EARTH = new Environment(1.013, 288.0, true);

    /**
     * Interplanetary/interstellar vacuum. The temperature is the 2.725 K CMB: an unsuited body radiates
     * to it far faster than starlight heats it outside ~0.1 AU, so "cold" is the honest default.
     */
    public static final Environment VACUUM = new Environment(0.0, 2.725, false);

    /** Pressurised ship cabin: ISS standard 1.013 bar, 295 K. */
    public static final Environment CABIN = new Environment(1.013, 295.0, true);

    public Environment {
        if (!(pressureBar >= 0) || !(tempK >= 0)) throw new IllegalArgumentException("pressure and temperature must be ≥ 0");
    }

    public boolean isVacuum() {
        return pressureBar < 1e-6;
    }
}
