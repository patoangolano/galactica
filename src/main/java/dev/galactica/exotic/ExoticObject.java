package dev.galactica.exotic;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

/**
 * How a compact remnant looks: everything {@link ExoticImager} needs, derived from a {@link Star}.
 * Named objects use published parameters; procedural ones draw theirs from the star's seed.
 *
 * @param star          the underlying star
 * @param kind          remnant kind
 * @param accretion     accretion flow around a black hole ({@link Accretion#NONE} otherwise)
 * @param rsMetres      Schwarzschild radius 2GM/c², metres (0 for non-black holes)
 * @param diskInnerRs   inner disk edge in units of Rs (ISCO = 3 Rs for Schwarzschild)
 * @param diskOuterRs   outer edge of the rendered disk, Rs
 * @param diskPeakTempK peak effective temperature of the thin disk, K
 * @param axis          unit spin / disk angular-momentum axis, galactocentric frame
 * @param surfaceTempK  photospheric temperature of a white dwarf or neutron star, K
 * @param radiusMetres  physical radius, metres
 * @param pulsar        beam geometry for neutron stars, null otherwise
 */
public record ExoticObject(Star star, StarKind kind, Accretion accretion, double rsMetres,
                           double diskInnerRs, double diskOuterRs, double diskPeakTempK, double[] axis,
                           double surfaceTempK, double radiusMetres, Pulsar pulsar) {

    public enum Accretion {
        /** Quiescent or isolated: shadow, photon ring and lensing only. */
        NONE,
        /** Optically thick, geometrically thin disk (Shakura &amp; Sunyaev 1973). */
        THIN_DISK,
        /** Radiatively inefficient hot flow (Narayan &amp; Yi 1995), as around Sgr A*. */
        RIAF
    }

    /** Fraction of procedural black holes drawn with an active X-ray-binary disk (game-scale choice, see {@link #of}). */
    public static final double ACCRETING_FRACTION = 0.15;

    /** Schwarzschild radius of a mass in solar masses, metres: 2GM/c² = 2953.25 m per M☉. */
    public static double schwarzschildRadius(double massSolar) {
        return 2 * Units.G * massSolar * Units.SOLAR_MASS / (Units.C * Units.C);
    }

    /**
     * Imaging parameters for a remnant star, or null for anything else.
     * <ul>
     *   <li><b>Sgr A*</b>: RIAF, accretion-flow angular momentum from the clockwise orbit of the
     *       2018 near-infrared flares, i ≈ 158°, Ω ≈ 160° (GRAVITY Collaboration 2018, A&amp;A 618, L10).</li>
     *   <li><b>Cygnus X-1</b>: high/soft-state thin disk, kT<sub>in</sub> ≈ 0.4 keV → 4.6 × 10⁶ K
     *       (Gierliński et al. 1999, MNRAS 309, 496); i = 27.5° (Miller-Jones et al. 2021, Science
     *       371, 1046); radio-jet position angle ≈ −17° (Stirling et al. 2001, MNRAS 327, 1273).</li>
     *   <li>Procedural black holes: most stellar-mass holes are isolated and dark; a seeded 15 %
     *       carry an X-ray-binary disk with T<sub>peak</sub> 1–5 × 10⁶ K. Axes are isotropic.</li>
     *   <li>Neutron stars: R = 12 km (Riley et al. 2019, ApJL 887, L21), the star's cooling
     *       temperature, and a {@link Pulsar} from the seed.</li>
     *   <li>White dwarfs: their own radius and cooling temperature.</li>
     * </ul>
     */
    public static ExoticObject of(Star s) {
        if (!s.kind().isRemnant()) return null;
        SeedRng rng = new SeedRng(SeedRng.hash(s.systemSeed(), 0xE207_1C00L));
        double[] axis = isotropic(rng);
        return switch (s.kind()) {
            case BLACK_HOLE -> {
                double rs = schwarzschildRadius(s.mass());
                if (s.id() == StarCatalog.SGR_A_STAR.id()) {
                    double[] a = SkyOrbit.angularMomentumAxis(158, 160, s.x(), s.y(), s.z());
                    yield new ExoticObject(s, s.kind(), Accretion.RIAF, rs, 3, 12, 0, a, 0, rs, null);
                }
                if ("Cygnus X-1".equals(s.name())) {
                    double[] a = SkyOrbit.axisFromJet(27.5, -17, s.x(), s.y(), s.z());
                    yield new ExoticObject(s, s.kind(), Accretion.THIN_DISK, rs, 3, 30, 4.6e6, a, 0, rs, null);
                }
                boolean active = rng.nextDouble() < ACCRETING_FRACTION;
                double t = rng.logUniform(1e6, 5e6);
                yield new ExoticObject(s, s.kind(), active ? Accretion.THIN_DISK : Accretion.NONE, rs, 3, 25,
                        active ? t : 0, axis, 0, rs, null);
            }
            case NEUTRON_STAR -> new ExoticObject(s, s.kind(), Accretion.NONE, 0, 0, 0, 0, axis,
                    s.temperature() > 0 ? s.temperature() : 1e6, 12_000, Pulsar.fromSeed(s.systemSeed()));
            default -> new ExoticObject(s, s.kind(), Accretion.NONE, 0, 0, 0, 0, axis,
                    s.temperature() > 0 ? s.temperature() : 10_000, s.radiusMetres(), null);
        };
    }

    private static double[] isotropic(SeedRng rng) {
        double z = rng.range(-1, 1), phi = rng.range(0, 2 * Math.PI), r = Math.sqrt(1 - z * z);
        return new double[] {r * Math.cos(phi), r * Math.sin(phi), z};
    }
}
