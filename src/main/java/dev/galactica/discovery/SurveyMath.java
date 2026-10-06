package dev.galactica.discovery;

import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;

import java.util.ArrayList;
import java.util.List;

/**
 * Indirect detection of planets around a star scanned from outside its system: transit photometry
 * and radial-velocity spectroscopy, the two methods behind ~95 % of known exoplanets.
 *
 * <p><b>Geometry.</b> Each planet gets one seeded, observer-independent cos i, uniform on [0, 1)
 * (isotropic orbit normals). It transits when the impact parameter b = a·cos i·(1−e²)/(1+e·sin ω)
 * is below 1 + R_p/R★, which happens with exactly the geometric transit probability
 * p = (R★ + R_p)/a · (1 + e·sin ω)/(1 − e²) (Winn 2010, "Transits and Occultations", eq. 9).
 * For a hot Jupiter p ≈ 10 %, for the Earth seen from afar 0.47 %.
 *
 * <p><b>Radial velocity.</b> K = (2πG/P)^⅓ · M_p sin i / (M★ + M_p)^⅔ / √(1 − e²) (Cumming et al.
 * 1999, ApJ 526, 890; Lovis &amp; Fischer 2010). Jupiter makes the Sun move 12.5 m/s, the Earth
 * 0.09 m/s. Detection needs K &gt; {@link #RV_LIMIT_MS} (ESPRESSO-class precision, Pepe et al.
 * 2021) and, for both methods, a period shorter than the {@link #BASELINE_YEARS}-year survey.
 */
public final class SurveyMath {
    private SurveyMath() {}

    public static final double RV_LIMIT_MS = 1.0;
    public static final double BASELINE_YEARS = 10.0;
    private static final long INCLINATION_SALT = 0x7A_4A5175L;

    /**
     * One planet revealed by a survey.
     *
     * @param planetIndex  index in {@code SystemGenerator.generate} order
     * @param transit      transits for this (any) observer
     * @param rv           RV semi-amplitude above the limit
     * @param kMs          RV semi-amplitude including sin i, m/s
     * @param periodDays   orbital period
     * @param depthPpm     transit depth (R_p/R★)², ppm
     */
    public record Detection(int planetIndex, boolean transit, boolean rv, double kMs, double periodDays, double depthPpm) {}

    /** Seeded cos i ∈ [0, 1) for a planet: same for every observer and every server with this galaxy. */
    public static double cosInclination(Body planet) {
        return new SeedRng(SeedRng.hash(planet.id(), INCLINATION_SALT)).nextDouble();
    }

    /** Geometric transit probability (Winn 2010 eq. 9), capped at 1. */
    public static double transitProbability(Star star, Body planet) {
        double a = planet.orbit().semiMajorAxis(), e = planet.orbit().eccentricity();
        double ecc = (1 + e * Math.sin(planet.orbit().argumentOfPeriapsis())) / (1 - e * e);
        return Math.min(1.0, (star.radiusMetres() + planet.radiusMetres()) / a * ecc);
    }

    public static boolean transits(Star star, Body planet) {
        return cosInclination(planet) < transitProbability(star, planet);
    }

    /** RV semi-amplitude in m/s for an orbit seen at inclination with sin i = {@code sinI}. */
    public static double semiAmplitude(Star star, Body planet, double sinI) {
        double p = planet.orbit().periodSeconds(), e = planet.orbit().eccentricity();
        double mp = planet.massKg(), ms = star.massKg();
        return Math.cbrt(2 * Math.PI * Units.G / p) * mp * sinI / Math.pow(ms + mp, 2.0 / 3.0) / Math.sqrt(1 - e * e);
    }

    /** What a decade-long transit + RV survey of {@code star} reveals among {@code planets}. */
    public static List<Detection> survey(Star star, List<Body> planets) {
        List<Detection> out = new ArrayList<>();
        if (star.kind() == dev.galactica.star.StarKind.BLACK_HOLE) return out;
        for (int i = 0; i < planets.size(); i++) {
            Body pl = planets.get(i);
            double periodDays = pl.orbit().periodSeconds() / Units.SECONDS_PER_DAY;
            if (periodDays > BASELINE_YEARS * 365.25) continue;
            double cosI = cosInclination(pl);
            double sinI = Math.sqrt(1 - cosI * cosI);
            boolean transit = cosI < transitProbability(star, pl);
            double k = semiAmplitude(star, pl, sinI);
            boolean rv = k > RV_LIMIT_MS;
            if (transit || rv) {
                double ratio = pl.radiusMetres() / star.radiusMetres();
                out.add(new Detection(i, transit, rv, k, periodDays, ratio * ratio * 1e6));
            }
        }
        return out;
    }
}
