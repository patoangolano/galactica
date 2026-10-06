package dev.galactica.exotic;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Named exotic objects and the S-stars orbiting Sgr A*.
 *
 * <p>Orbits (sky-plane elements, see {@link SkyOrbit}):
 * <ul>
 *   <li><b>S2</b>: a = 0.12495″, e = 0.88441, i = 134.70°, Ω = 228.19°, ω = 66.25°,
 *       t<sub>peri</sub> = 2018.3789 (GRAVITY Collaboration 2022, A&amp;A 657, L12, table B.1, the
 *       four-star fit). At R₀ = 8277 pc that is a = 1034 AU, r<sub>peri</sub> = 119.6 AU, P = 16.04 yr,
 *       consistent with the 16.0455 yr of the S2-only fit (GRAVITY Collaboration 2020, A&amp;A 636, L5).</li>
 *   <li><b>S29</b>: a = 0.3975″, e = 0.9693, i = 144.37°, Ω = 7.00°, ω = 205.79°,
 *       t<sub>peri</sub> = 2021.4104 (same table).</li>
 *   <li><b>S55</b> (S0-102): a = 0.10440″, e = 0.7267, i = 158.52°, Ω = 314.94°, ω = 322.78°,
 *       t<sub>peri</sub> = 2021.6940 (same table).</li>
 * </ul>
 * All three come from one fit: angular semi-major axes are converted at its R₀ = 8277 pc, and the
 * periods follow from Kepler's law with its M = 4.297 × 10⁶ M☉. The elements are osculating
 * Keplerian ones (Schwarzschild precession, 12.1′ per S2 orbit, is not modelled).
 *
 * <p>Stellar parameters: S2 is a B0–2.5 V star, 13.6 M☉, T<sub>eff</sub> = 28 500 K,
 * log L/L☉ = 4.35 (Habibi et al. 2017, ApJ 847, 120). S29 and S55 are fainter than S2 in the K band
 * and have no spectral fit used here; they get typical B2 V / B3 V parameters (mass, L, T<sub>eff</sub>
 * from the main-sequence calibration of Pecaut &amp; Mamajek 2013, ApJS 208, 9). Those are estimates.
 */
public final class ExoticCatalog {
    private ExoticCatalog() {}

    /** Sgr A* mass of the 2022 four-star fit, M☉. */
    public static final double SGR_A_MASS = 4.297e6;
    private static final double R0_2022 = 8277.0;

    public static final SkyOrbit S2 = kepler(0.12495 * R0_2022, 0.88441, 134.70, 228.19, 66.25, 2018.3789);
    public static final SkyOrbit S29 = kepler(0.3975 * R0_2022, 0.9693, 144.37, 7.00, 205.79, 2021.4104);
    public static final SkyOrbit S55 = kepler(0.10440 * R0_2022, 0.7267, 158.52, 314.94, 322.78, 2021.6940);

    /** One S-star: catalog id, name, orbit and stellar parameters. */
    public record SStar(long id, String name, SkyOrbit orbit, double massSolar, double luminosity, double teffK) {
        public double radiusSolar() {
            double t = teffK / Units.SOLAR_TEFF;
            return Math.sqrt(luminosity) / (t * t);
        }
    }

    public static final List<SStar> S_STARS = List.of(
            new SStar(-101, "S2", S2, 13.6, Math.pow(10, 4.35), 28_500),
            new SStar(-102, "S29", S29, 7.3, 2_500, 20_600),
            new SStar(-103, "S55", S55, 5.4, 800, 17_000));

    /** The named remnants this package renders specially. */
    public static List<Star> namedRemnants() {
        List<Star> out = new ArrayList<>();
        out.add(StarCatalog.SGR_A_STAR);
        Star cyg = StarCatalog.byName("Cygnus X-1");
        if (cyg != null) out.add(cyg);
        return out;
    }

    /**
     * The S-stars at time t (seconds since J2000, the {@code NavState} clock) as ordinary
     * catalog {@link Star}s with galactocentric positions in parsecs, ready to be added to the
     * Galactic Centre neighbourhood. Ids are −101…−103 (never colliding with procedural hashes).
     */
    public static List<Star> sStars(double timeSeconds) {
        Star c = StarCatalog.SGR_A_STAR;
        List<Star> out = new ArrayList<>(S_STARS.size());
        double[] off = new double[3];
        for (SStar s : S_STARS) {
            s.orbit().galacticOffsetAu(timeSeconds, c.x(), c.y(), c.z(), off);
            double k = Units.AU / Units.PARSEC;
            out.add(new Star(s.id(), s.name(), c.x() + off[0] * k, c.y() + off[1] * k, c.z() + off[2] * k,
                    StarKind.MAIN_SEQUENCE, s.massSolar(), s.radiusSolar(), s.luminosity(), s.teffK(),
                    0.0066, 0.0, SeedRng.hash(0xCA7A_10C0L, s.id()), true));
        }
        return out;
    }

    private static SkyOrbit kepler(double aAu, double e, double i, double node, double peri, double tp) {
        return new SkyOrbit(aAu, e, i, node, peri, tp, SkyOrbit.keplerPeriodYears(aAu, SGR_A_MASS));
    }
}
