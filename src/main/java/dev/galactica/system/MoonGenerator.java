package dev.galactica.system;

import dev.galactica.science.SeedRng;
import dev.galactica.star.Star;

import java.util.ArrayList;
import java.util.List;

/**
 * Regular moons. Giants get a Galilean-like set whose total mass is ~10^-4 of the planet
 * (Canup &amp; Ward 2006); terrestrial planets occasionally get one large impact-made moon.
 * Moons stay well inside the Hill sphere (&lt; 0.4 r_H for prograde stability).
 */
final class MoonGenerator {
    private MoonGenerator() {}

    static List<Body> generate(Star star, Body planet, SeedRng parentRng) {
        SeedRng rng = new SeedRng(SeedRng.hash(planet.id(), 0x300_0A1L));
        boolean giant = !planet.type().solidSurface;
        int count;
        if (giant && planet.type() != BodyType.SUB_NEPTUNE && planet.type() != BodyType.HOT_JUPITER) count = 2 + rng.nextInt(4);
        else if (planet.type().solidSurface && planet.massEarth() > 0.3 && rng.nextDouble() < 0.2) count = 1;
        else count = 0;
        if (count == 0) return List.of();

        double hill = planet.orbit().hillRadius(planet.massKg());
        double rp = planet.radiusMetres();
        double a = Math.max(rp * (giant ? 5.5 : 30), rp * 3);
        List<Body> moons = new ArrayList<>();
        double totalMass = giant ? planet.massEarth() * 1e-4 : planet.massEarth() * rng.range(0.005, 0.02);
        for (int i = 0; i < count && a < 0.4 * hill; i++) {
            double massE = totalMass * rng.range(0.1, 0.5);
            double radiusE = Math.pow(massE, 0.3) * (giant ? 0.95 : 1.0); // rock/ice moons, ρ≈2–3.5 g/cm³
            double albedo = rng.range(0.1, 0.7);
            double teq = SystemGenerator.equilibriumTemperature(star, planet.orbit().semiMajorAxis() / dev.galactica.science.Units.AU, albedo);
            BodyType type = teq > 700 ? BodyType.LAVA : teq < 170 ? BodyType.ICE : BodyType.BARREN;
            // Tidal heating of an inner moon in resonance (Io) turns it volcanic.
            if (giant && i == 0 && rng.nextDouble() < 0.3) type = BodyType.LAVA;
            boolean thickAir = massE > 0.015 && teq < 120 && rng.nextDouble() < 0.25; // Titan analogue
            Orbit o = new Orbit(a, Math.abs(0.01 * rng.gaussian()), Math.toRadians(Math.abs(rng.gaussian())),
                    rng.range(0, 2 * Math.PI), rng.range(0, 2 * Math.PI), rng.range(0, 2 * Math.PI), planet.massKg());
            long id = SeedRng.hash(planet.id(), i + 1);
            moons.add(new Body(id, planet.name() + " " + roman(i + 1), type, o, massE, radiusE, albedo, teq,
                    thickAir ? teq + 12 : teq, thickAir ? rng.range(0.8, 2.0) : 0.0,
                    thickAir ? Body.Atmosphere.N2_CH4 : Body.Atmosphere.NONE,
                    o.periodSeconds() / 3600.0, 0, true, false, Body.Life.NONE, SeedRng.hash(id, 0x7E44A1L), List.of()));
            a *= rng.range(1.6, 2.2); // Laplace-resonance-like spacing
        }
        return moons;
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> Integer.toString(n);
        };
    }
}
