package dev.galactica.system;

import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;

import java.util.List;

/**
 * The real Solar System. Planet elements are J2000 mean elements (Standish, JPL); moons use mean
 * orbital elements from JPL SSD. Epoch t = 0 is J2000.0.
 */
public final class SolarSystem {
    private SolarSystem() {}

    private static final double KM = 1000.0;
    private static List<Body> cache;

    public static synchronized List<Body> planets() {
        if (cache == null) cache = build();
        return cache;
    }

    private static List<Body> build() {
        double sun = Units.SOLAR_MASS;
        Body earth = planet(3, "Earth", BodyType.TERRAN, 1.00000, 0.01671, 0.000, -11.26064, 114.207, 357.517, sun,
                1.0, 1.0, 0.306, 255, 288, 1.013, Body.Atmosphere.N2_O2, 23.934, 23.44, false, Body.Life.COMPLEX);
        Body mars = planet(4, "Mars", BodyType.DESERT, 1.52366, 0.09341, 1.850, 49.558, 286.502, 19.393, sun,
                0.107, 0.532, 0.25, 210, 210, 0.006, Body.Atmosphere.TRACE_CO2, 24.623, 25.19, false, Body.Life.NONE);
        Body jupiter = planet(5, "Jupiter", BodyType.GAS_GIANT, 5.20336, 0.04839, 1.305, 100.464, 273.867, 20.073, sun,
                317.8, 11.21, 0.343, 110, 165, 1.0, Body.Atmosphere.H2_HE, 9.925, 3.13, false, Body.Life.NONE);
        Body saturn = planet(6, "Saturn", BodyType.GAS_GIANT, 9.53707, 0.05415, 2.485, 113.665, 339.392, 316.887, sun,
                95.2, 9.45, 0.342, 81, 134, 1.0, Body.Atmosphere.H2_HE, 10.656, 26.73, true, Body.Life.NONE);
        Body uranus = planet(7, "Uranus", BodyType.ICE_GIANT, 19.1913, 0.04717, 0.772, 74.006, 98.999, 140.227, sun,
                14.5, 4.01, 0.30, 58, 76, 1.0, Body.Atmosphere.H2_HE, -17.24, 97.77, true, Body.Life.NONE);
        Body neptune = planet(8, "Neptune", BodyType.ICE_GIANT, 30.0690, 0.00859, 1.770, 131.784, 276.34, 256.756, sun,
                17.1, 3.88, 0.29, 46, 72, 1.0, Body.Atmosphere.H2_HE, 16.11, 28.32, false, Body.Life.NONE);

        return List.of(
                planet(1, "Mercury", BodyType.BARREN, 0.38710, 0.20563, 7.005, 48.331, 29.125, 174.795, sun,
                        0.0553, 0.383, 0.088, 440, 440, 0.0, Body.Atmosphere.NONE, 1407.6, 0.034, false, Body.Life.NONE),
                planet(2, "Venus", BodyType.GREENHOUSE, 0.72333, 0.00677, 3.395, 76.680, 54.853, 50.447, sun,
                        0.815, 0.949, 0.76, 229, 737, 92.0, Body.Atmosphere.CO2_THICK, -5832.5, 2.64, false, Body.Life.NONE),
                SystemGenerator.withMoons(earth, List.of(
                        moon(earth, 31, "Moon", BodyType.BARREN, 384_400, 0.0549, 5.145, 0.0123, 0.2727, 0.11, 250, 0,
                                Body.Atmosphere.NONE))),
                SystemGenerator.withMoons(mars, List.of(
                        moon(mars, 41, "Phobos", BodyType.BARREN, 9_376, 0.0151, 1.08, 1.78e-9, 0.00175, 0.07, 233, 0, Body.Atmosphere.NONE),
                        moon(mars, 42, "Deimos", BodyType.BARREN, 23_463, 0.0003, 1.79, 2.5e-10, 0.00097, 0.07, 233, 0, Body.Atmosphere.NONE))),
                SystemGenerator.withMoons(jupiter, List.of(
                        moon(jupiter, 51, "Io", BodyType.LAVA, 421_700, 0.0041, 0.05, 0.015, 0.286, 0.63, 110, 0, Body.Atmosphere.NONE),
                        moon(jupiter, 52, "Europa", BodyType.ICE, 671_034, 0.009, 0.47, 0.008, 0.245, 0.67, 102, 0, Body.Atmosphere.NONE),
                        moon(jupiter, 53, "Ganymede", BodyType.ICE, 1_070_412, 0.0013, 0.20, 0.025, 0.413, 0.43, 110, 0, Body.Atmosphere.NONE),
                        moon(jupiter, 54, "Callisto", BodyType.ICE, 1_882_709, 0.0074, 0.19, 0.018, 0.378, 0.22, 134, 0, Body.Atmosphere.NONE))),
                SystemGenerator.withMoons(saturn, List.of(
                        moon(saturn, 61, "Enceladus", BodyType.ICE, 237_948, 0.0047, 0.02, 1.8e-5, 0.0396, 0.81, 75, 0, Body.Atmosphere.NONE),
                        moon(saturn, 62, "Titan", BodyType.ICE, 1_221_870, 0.0288, 0.35, 0.0225, 0.404, 0.22, 94, 1.45, Body.Atmosphere.N2_CH4))),
                SystemGenerator.withMoons(uranus, List.of(
                        moon(uranus, 71, "Titania", BodyType.ICE, 435_910, 0.0011, 0.34, 5.9e-4, 0.1235, 0.17, 70, 0, Body.Atmosphere.NONE))),
                SystemGenerator.withMoons(neptune, List.of(
                        moon(neptune, 81, "Triton", BodyType.ICE, 354_759, 0.000016, 156.9, 3.59e-3, 0.2122, 0.76, 38, 1.4e-5, Body.Atmosphere.N2_CH4))));
    }

    private static Body planet(int idx, String name, BodyType type, double aAu, double e, double iDeg, double nodeDeg,
                               double argPeriDeg, double meanAnomDeg, double parentKg, double massE, double radiusE,
                               double albedo, double teq, double tSurf, double pBar, Body.Atmosphere atm,
                               double rotH, double tilt, boolean rings, Body.Life life) {
        Orbit o = new Orbit(aAu * Units.AU, e, Math.toRadians(iDeg), Math.toRadians(nodeDeg), Math.toRadians(argPeriDeg),
                Math.toRadians(meanAnomDeg), parentKg);
        long id = SeedRng.hash(0x501_5757L, idx);
        return new Body(id, name, type, o, massE, radiusE, albedo, teq, tSurf, pBar, atm, rotH, tilt, false, rings,
                life, SeedRng.hash(id, 0x7E44A1L), List.of());
    }

    private static Body moon(Body parent, int idx, String name, BodyType type, double aKm, double e, double iDeg,
                             double massE, double radiusE, double albedo, double tSurf, double pBar, Body.Atmosphere atm) {
        Orbit o = new Orbit(aKm * KM, e, Math.toRadians(iDeg), 0, 0, Math.toRadians(idx * 47.0 % 360), parent.massKg());
        long id = SeedRng.hash(0x501_5757L, idx);
        return new Body(id, name, type, o, massE, radiusE, albedo, tSurf, tSurf, pBar, atm,
                o.periodSeconds() / 3600.0, 0, true, false, Body.Life.NONE, SeedRng.hash(id, 0x7E44A1L), List.of());
    }
}
