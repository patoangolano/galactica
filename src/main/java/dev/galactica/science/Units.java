package dev.galactica.science;

/**
 * Physical constants and unit conversions (SI unless stated). Values follow IAU 2015 / CODATA 2018.
 */
public final class Units {
    private Units() {}

    public static final double G = 6.67430e-11;              // m^3 kg^-1 s^-2
    public static final double C = 299_792_458.0;            // m/s
    public static final double SIGMA_SB = 5.670374419e-8;    // W m^-2 K^-4
    public static final double H_PLANCK = 6.62607015e-34;    // J s
    public static final double K_BOLTZMANN = 1.380649e-23;   // J/K

    public static final double AU = 1.495978707e11;          // m
    public static final double PARSEC = 3.0856775814913673e16; // m
    public static final double LIGHT_YEAR = 9.4607304725808e15; // m

    public static final double SOLAR_MASS = 1.98847e30;      // kg
    public static final double SOLAR_RADIUS = 6.957e8;       // m (IAU nominal)
    public static final double SOLAR_LUMINOSITY = 3.828e26;  // W (IAU nominal)
    public static final double SOLAR_TEFF = 5772.0;          // K

    public static final double EARTH_MASS = 5.9722e24;       // kg
    public static final double EARTH_RADIUS = 6.3781e6;      // m (equatorial)
    public static final double JUPITER_MASS = 1.89813e27;    // kg
    public static final double JUPITER_RADIUS = 7.1492e7;    // m

    public static final double EARTH_GRAVITY = 9.80665;      // m/s^2
    public static final double SECONDS_PER_DAY = 86_400.0;
    public static final double SECONDS_PER_YEAR = 365.25 * SECONDS_PER_DAY; // Julian year
    public static final double GYR = 1e9 * SECONDS_PER_YEAR;

    /** One Minecraft block is one metre; the mod keeps that as its base length. */
    public static final double METRES_PER_BLOCK = 1.0;
}
