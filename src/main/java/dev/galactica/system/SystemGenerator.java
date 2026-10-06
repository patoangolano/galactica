package dev.galactica.system;

import dev.galactica.galaxy.StarNames;
import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a star's planetary system from its {@code systemSeed}. Statistics follow the Kepler/TESS
 * era occurrence results:
 * <ul>
 *   <li>orbits are spaced by log-normal period ratios around 1.9 ("peas in a pod", Weiss et al. 2018);</li>
 *   <li>small planets show the radius valley at ~1.8 R⊕ (Fulton et al. 2017): super-Earths peak at 1.3 R⊕,
 *       sub-Neptunes at 2.4 R⊕;</li>
 *   <li>giant-planet occurrence rises as 10^(2·[Fe/H]) and with stellar mass (Fischer &amp; Valenti 2005);</li>
 *   <li>mass–radius from the Chen &amp; Kipping (2017) forecaster;</li>
 *   <li>equilibrium temperature T_eq = T★·√(R★/2a)·(1−A)^¼.</li>
 * </ul>
 */
public final class SystemGenerator {
    private SystemGenerator() {}

    public static List<Body> generate(Star star) {
        if ("Sol".equals(star.name()) && star.catalog()) return SolarSystem.planets();
        if (star.kind() == StarKind.BLACK_HOLE) return List.of();
        SeedRng rng = new SeedRng(star.systemSeed());
        double ms = Math.max(0.08, star.mass());
        double starMassKg = star.massKg();

        boolean mDwarf = star.temperature() < 3900 && star.kind() == StarKind.MAIN_SEQUENCE;
        double giantChance = Math.min(0.6, 0.06 * Math.pow(10, 2 * star.metallicity()) * Math.min(2.5, ms));
        double smallChance = star.kind().isRemnant() ? 0.08 : (mDwarf ? 0.7 : 0.55);

        double snow = star.snowLineAu();
        double aAu = rng.logUniform(0.015, 0.09) * Math.cbrt(ms);
        // Engulfed region of giants: nothing survives inside ~1.2 stellar radii of a few AU.
        double minAu = Math.max(star.radius() * Units.SOLAR_RADIUS / Units.AU * 3, 0.01);
        double outerAu = 45.0 * Math.cbrt(ms);

        List<Body> planets = new ArrayList<>();
        int slot = 0;
        while (aAu < outerAu && planets.size() < 12) {
            double periodRatio = Math.exp(Math.log(1.9) + 0.25 * rng.gaussian());
            if (aAu > minAu) {
                boolean beyondSnow = aAu > snow;
                double p = beyondSnow ? giantChance + 0.25 : smallChance;
                if (rng.nextDouble() < p) {
                    planets.add(makePlanet(star, starMassKg, rng, aAu, beyondSnow, giantChance, planets.size()));
                }
            }
            aAu *= Math.pow(Math.max(1.25, periodRatio), 2.0 / 3.0);
            slot++;
            if (slot > 40) break;
        }
        return planets;
    }

    private static Body makePlanet(Star star, double starMassKg, SeedRng rng, double aAu, boolean beyondSnow,
                                   double giantChance, int index) {
        long id = SeedRng.hash(star.systemSeed(), index);
        String name = StarNames.planet(star.name(), index);
        double e = Math.min(0.6, Math.abs(0.05 * rng.gaussian()) + (rng.nextDouble() < 0.1 ? rng.range(0.1, 0.4) : 0));
        double inc = Math.toRadians(Math.abs(1.5 * rng.gaussian()));
        Orbit orbit = new Orbit(aAu * Units.AU, e, inc, rng.range(0, 2 * Math.PI), rng.range(0, 2 * Math.PI),
                rng.range(0, 2 * Math.PI), starMassKg);

        double massE, radiusE;
        BodyType type;
        boolean giant = beyondSnow ? rng.nextDouble() < giantChance / (giantChance + 0.25)
                : rng.nextDouble() < giantChance * 0.08; // migrated hot/warm Jupiters are rare (~1 %)
        if (giant) {
            boolean iceGiant = beyondSnow && rng.nextDouble() < 0.35;
            if (iceGiant) {
                massE = rng.logUniform(8, 30);
                radiusE = MassRadius.radiusFromMass(massE);
                type = BodyType.ICE_GIANT;
            } else {
                massE = rng.logUniform(30, 3000);
                radiusE = MassRadius.radiusFromMass(massE);
                type = BodyType.GAS_GIANT;
            }
        } else {
            // Bimodal small-planet radius distribution either side of the radius valley.
            radiusE = rng.nextDouble() < 0.55
                    ? Math.exp(Math.log(1.3) + 0.25 * rng.gaussian())
                    : Math.exp(Math.log(2.4) + 0.2 * rng.gaussian());
            radiusE = Math.max(0.3, Math.min(3.9, radiusE));
            if (beyondSnow && rng.nextDouble() < 0.5) radiusE = rng.range(0.3, 1.2); // icy dwarfs
            massE = MassRadius.massFromRadius(radiusE) * Math.exp(0.15 * rng.gaussian());
            type = radiusE > 1.8 ? BodyType.SUB_NEPTUNE : BodyType.BARREN;
        }

        double albedo = switch (type) {
            case GAS_GIANT, HOT_JUPITER -> 0.34;
            case ICE_GIANT -> 0.30;
            case SUB_NEPTUNE -> 0.35;
            default -> rng.range(0.1, 0.35);
        };
        double teq = equilibriumTemperature(star, aAu, albedo);
        if (type == BodyType.GAS_GIANT && teq > 1000) {
            type = BodyType.HOT_JUPITER;
            radiusE *= 1.0 + 0.3 * Math.min(1.0, (teq - 1000) / 1000); // irradiation inflation
        }

        Body.Atmosphere atm;
        double pressure;
        Body.Life life = Body.Life.NONE;
        double tSurf = teq;
        if (!type.solidSurface || type == BodyType.SUB_NEPTUNE) {
            atm = Body.Atmosphere.H2_HE;
            pressure = 1.0; // reference level: the 1-bar cloud deck
        } else {
            double hz0 = star.habitableZoneAu()[0], hz1 = star.habitableZoneAu()[1];
            boolean retains = massE > 0.25 && !(teq > 900 && massE < 5);
            if (!retains) {
                atm = massE > 0.1 ? Body.Atmosphere.TRACE_CO2 : Body.Atmosphere.NONE;
                pressure = atm == Body.Atmosphere.NONE ? 0 : rng.logUniform(1e-4, 0.01);
                type = teq > 700 ? BodyType.LAVA : (beyondSnow ? BodyType.ICE : BodyType.BARREN);
            } else if (teq > 450 && massE < 5) {
                // Close-in small planets lose their envelopes to XUV photoevaporation (Owen & Wu 2017).
                atm = rng.nextDouble() < 0.3 ? Body.Atmosphere.TRACE_CO2 : Body.Atmosphere.NONE;
                pressure = atm == Body.Atmosphere.NONE ? 0 : rng.logUniform(1e-5, 1e-3);
                type = teq > 700 ? BodyType.LAVA : BodyType.BARREN;
            } else if (aAu < hz0 && teq > 230) {
                atm = rng.nextDouble() < 0.6 ? Body.Atmosphere.CO2_THICK : Body.Atmosphere.H2O_STEAM;
                pressure = rng.logUniform(10, 120);
                type = teq > 700 ? BodyType.LAVA : BodyType.GREENHOUSE;
                // Runaway greenhouse; Venus (T_eq ≈ 230 K at its albedo, 92 bar) sits at ×3.2.
                tSurf = teq * (1.6 + 0.8 * Math.log10(pressure));
            } else if (aAu <= hz1 * 1.1) {
                pressure = rng.logUniform(0.3, 5.0) * Math.min(3.0, massE);
                tSurf = teq + 33.0 * Math.pow(pressure, 0.3);
                boolean wet = rng.nextDouble() < 0.7;
                if (wet && tSurf > 255 && tSurf < 330) {
                    type = radiusE > 1.4 && rng.nextDouble() < 0.5 ? BodyType.OCEAN : BodyType.TERRAN;
                    boolean oxygenated = star.ageGyr() > 2.0 && rng.nextDouble() < 0.25;
                    atm = oxygenated ? Body.Atmosphere.N2_O2 : Body.Atmosphere.N2_CH4;
                    double lifeRoll = rng.nextDouble();
                    if (oxygenated) life = Body.Life.COMPLEX;
                    else if (star.ageGyr() > 0.8 && lifeRoll < 0.5) life = Body.Life.MICROBIAL;
                } else {
                    atm = Body.Atmosphere.TRACE_CO2;
                    type = tSurf < 255 ? BodyType.ICE : BodyType.DESERT;
                }
            } else {
                atm = massE > 0.5 && rng.nextDouble() < 0.3 ? Body.Atmosphere.N2_CH4 : Body.Atmosphere.TRACE_CO2;
                pressure = atm == Body.Atmosphere.N2_CH4 ? rng.logUniform(0.5, 2.0) : rng.logUniform(1e-3, 0.05);
                tSurf = teq + (atm == Body.Atmosphere.N2_CH4 ? 12 : 1);
                type = BodyType.ICE;
            }
        }

        boolean locked = tidallyLocked(star, aAu);
        double rotation = locked ? orbit.periodSeconds() / 3600.0
                : (type.solidSurface ? rng.logUniform(10, 60) : rng.logUniform(9, 18)) * (rng.nextDouble() < 0.1 ? -1 : 1);
        double tilt = locked ? 0 : Math.abs(rng.gaussian() * 20);
        boolean rings = (type == BodyType.GAS_GIANT && rng.nextDouble() < 0.3)
                || (type == BodyType.ICE_GIANT && rng.nextDouble() < 0.5);

        Body planet = new Body(id, name, type, orbit, massE, radiusE, albedo, teq, tSurf, pressure, atm,
                rotation, tilt, locked, rings, life, SeedRng.hash(id, 0x7E44A1L), List.of());
        List<Body> moons = MoonGenerator.generate(star, planet, rng);
        return moons.isEmpty() ? planet : withMoons(planet, moons);
    }

    static Body withMoons(Body p, List<Body> moons) {
        return new Body(p.id(), p.name(), p.type(), p.orbit(), p.massEarth(), p.radiusEarth(), p.albedo(),
                p.equilibriumTemp(), p.surfaceTemp(), p.surfacePressureBar(), p.atmosphere(), p.rotationHours(),
                p.axialTiltDeg(), p.tidallyLocked(), p.rings(), p.life(), p.terrainSeed(), List.copyOf(moons));
    }

    public static double equilibriumTemperature(Star star, double aAu, double albedo) {
        double rStar = star.radius() * Units.SOLAR_RADIUS;
        return star.temperature() * Math.sqrt(rStar / (2 * aAu * Units.AU)) * Math.pow(1 - albedo, 0.25);
    }

    /** Gladman et al. 1996 locking time scaled: everything inside ~0.5·M★^(1/3) AU of an M/K dwarf is locked in &lt;1 Gyr. */
    static boolean tidallyLocked(Star star, double aAu) {
        return aAu < 0.5 * Math.pow(star.mass(), 0.5) && star.ageGyr() > 0.5;
    }
}
