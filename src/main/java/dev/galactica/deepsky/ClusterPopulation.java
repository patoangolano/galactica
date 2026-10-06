package dev.galactica.deepsky;

import dev.galactica.science.Blackbody;
import dev.galactica.science.SeedRng;
import dev.galactica.star.SpectralClass;
import dev.galactica.star.StarKind;
import dev.galactica.star.StellarPhysics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stellar population of a coeval cluster of a given age and metallicity, per star formed:
 * Kroupa (2001) IMF split into mass bins (with one bin holding exactly the evolved stars between
 * the turnoff and the white-dwarf boundary), simple stellar evolution, and the luminosity function
 * it produces. Shared by all clusters in the same (age, [Fe/H], mass cap) bucket.
 *
 * <p>Evolved low-mass stars follow the observed phases of globular-cluster colour–magnitude
 * diagrams: red-giant branch up to the tip at ~2 500 L☉ (where most time is spent near the base),
 * horizontal branch at ~50 L☉ (red clump for [Fe/H] &gt; −1, blue HB for metal-poor clusters,
 * the second-parameter trend of Lee, Demarque &amp; Zinn 1994), and the asymptotic giant branch.
 */
public final class ClusterPopulation {
    /** Fraction of the main-sequence lifetime spent in post-main-sequence phases. */
    static final double POST_MS = 0.10;
    private static final double[] BASE_EDGES = {0.08, 0.15, 0.25, 0.4, 0.6, 0.8, 1.0, 1.3, 1.7, 2.2, 3.0, 4.0, 6.0, 9.0, 15.0, 25.0, 40.0, 150.0};
    private static final Map<String, ClusterPopulation> CACHE = new ConcurrentHashMap<>();

    public final double ageGyr, feh, massCap;
    public final double turnoffMass, remnantMass;
    /** Mass bin edges (M☉) and the index of the evolved-star bin. */
    final double[] edges;
    final int giantBin;
    /** Expected stars per bin per star formed. */
    final double[] fraction;
    /** Brightest absolute V magnitude seen in each bin's sample. */
    final double[] brightestMv;
    /** Luminosity function per star: magnitudes ascending, cumulative count and V luminosity. */
    private final double[] lfMag, lfCount, lfLum;
    public final double lumPerStar;
    /** Luminosity-weighted linear-sRGB colour of the integrated light (max component 1). */
    public final float[] colour = new float[3];

    private ClusterPopulation(double ageGyr, double feh, double massCap) {
        this.ageGyr = ageGyr;
        this.feh = feh;
        this.massCap = massCap;
        this.turnoffMass = massForLifetime(ageGyr);
        this.remnantMass = massForLifetime(ageGyr / (1 + POST_MS));
        List<Double> e = new ArrayList<>();
        for (double v : BASE_EDGES) if ((v < turnoffMass || v > remnantMass) && v < massCap) e.add(v);
        if (turnoffMass < massCap) e.add(turnoffMass);
        if (remnantMass < massCap) e.add(remnantMass);
        e.add(Math.min(massCap, StellarPhysics.M_MAX));
        double[] ed = e.stream().mapToDouble(Double::doubleValue).sorted().distinct().toArray();
        this.edges = ed;
        int gb = -1;
        for (int k = 0; k + 1 < ed.length; k++) if (Math.abs(ed[k] - turnoffMass) < 1e-12) gb = k;
        this.giantBin = gb;
        int nb = ed.length - 1;
        fraction = new double[nb];
        brightestMv = new double[nb];
        for (int k = 0; k < nb; k++) fraction[k] = StellarPhysics.fractionAbove(ed[k]) - StellarPhysics.fractionAbove(ed[k + 1]);

        // Representative sample of each bin → luminosity function.
        List<double[]> samples = new ArrayList<>(); // {Mv, weight}
        double rs = 0, gs = 0, bs = 0;
        float[] rgb = new float[3];
        long bucketSeed = SeedRng.hash(0xC1A5_7E25L, Double.doubleToLongBits(ageGyr), Double.doubleToLongBits(feh));
        for (int k = 0; k < nb; k++) {
            int s = k == giantBin ? 1500 : 160;
            double w = fraction[k] / s;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < s; i++) {
                SeedRng rng = new SeedRng(SeedRng.hash(bucketSeed, k, i));
                // Stratified in the cumulative IMF: a smooth luminosity function from few samples.
                double m0 = massInBin(ed[k], ed[k + 1], (i + 0.5) / s);
                double[] st = evolve(m0, ageGyr, feh, rng);
                if (st[0] == KIND_BH) continue;
                double mv = absoluteMv(st[1], st[2]);
                best = Math.min(best, mv);
                samples.add(new double[] {mv, w});
                double lv = Math.pow(10, -0.4 * (mv - 4.83)) * w;
                Blackbody.linearRgb(st[2], rgb);
                rs += lv * rgb[0];
                gs += lv * rgb[1];
                bs += lv * rgb[2];
            }
            brightestMv[k] = best;
        }
        samples.sort((a, b) -> Double.compare(a[0], b[0]));
        lfMag = new double[samples.size()];
        lfCount = new double[samples.size()];
        lfLum = new double[samples.size()];
        double c = 0, l = 0;
        for (int i = 0; i < samples.size(); i++) {
            double[] sm = samples.get(i);
            c += sm[1];
            l += sm[1] * Math.pow(10, -0.4 * (sm[0] - 4.83));
            lfMag[i] = sm[0];
            lfCount[i] = c;
            lfLum[i] = l;
        }
        lumPerStar = l;
        double mx = Math.max(rs, Math.max(gs, bs));
        if (mx > 0) {
            colour[0] = (float) (rs / mx);
            colour[1] = (float) (gs / mx);
            colour[2] = (float) (bs / mx);
        }
    }

    /** Population for an (age, [Fe/H], mass cap) bucket; age snapped to 0.05 dex, [Fe/H] to 0.25 dex. */
    public static ClusterPopulation of(double ageGyr, double feh, double massCap) {
        double a = snapAge(ageGyr);
        double f = snapFeh(feh);
        double cap = Math.min(StellarPhysics.M_MAX, massCap);
        return CACHE.computeIfAbsent(a + "|" + f + "|" + cap, k -> new ClusterPopulation(a, f, cap));
    }

    public static double snapAge(double ageGyr) {
        return Math.pow(10, Math.round(Math.log10(ageGyr) * 20) / 20.0);
    }

    public static double snapFeh(double feh) {
        return Math.round(feh * 4) / 4.0;
    }

    // Kroupa IMF segments, identical to StellarPhysics, inverted analytically (no bisection).
    private static final double A1 = 1.3, A2 = 2.3, M_BREAK = 0.5;
    private static final double K2 = Math.pow(M_BREAK, A2 - A1);
    private static final double SEG1 = (Math.pow(M_BREAK, 1 - A1) - Math.pow(StellarPhysics.M_MIN, 1 - A1)) / (1 - A1);
    private static final double SEG2 = K2 * (Math.pow(StellarPhysics.M_MAX, 1 - A2) - Math.pow(M_BREAK, 1 - A2)) / (1 - A2);

    /** Mass above which a fraction f of IMF-drawn stars lie (inverse of {@code StellarPhysics.fractionAbove}). */
    static double massAtFractionAbove(double f) {
        double a = Math.max(0, Math.min(1, f)) * (SEG1 + SEG2);
        if (a <= SEG2) {
            double e = 1 - A2;
            return Math.pow(Math.pow(StellarPhysics.M_MAX, e) - a * e / K2, 1 / e);
        }
        double e = 1 - A1;
        return Math.pow(Math.pow(M_BREAK, e) - (a - SEG2) * e, 1 / e);
    }

    /** IMF-distributed mass in [lo, hi) at cumulative position u ∈ [0, 1). */
    static double massInBin(double lo, double hi, double u) {
        double fHi = StellarPhysics.fractionAbove(hi), fLo = StellarPhysics.fractionAbove(lo);
        return Math.max(lo, Math.min(hi, massAtFractionAbove(fHi + (1 - u) * (fLo - fHi))));
    }

    /** Mass whose main-sequence lifetime equals t (Gyr), by bisection. */
    static double massForLifetime(double tGyr) {
        double lo = StellarPhysics.M_MIN, hi = StellarPhysics.M_MAX;
        if (StellarPhysics.mainSequenceLifetimeGyr(hi) >= tGyr) return hi;
        if (StellarPhysics.mainSequenceLifetimeGyr(lo) <= tGyr) return lo;
        for (int i = 0; i < 80; i++) {
            double m = Math.sqrt(lo * hi);
            if (StellarPhysics.mainSequenceLifetimeGyr(m) > tGyr) lo = m; else hi = m;
        }
        return Math.sqrt(lo * hi);
    }

    public int binCount() {
        return fraction.length;
    }

    /** Stars per star formed brighter than (or equal to) absolute magnitude M. */
    public double countBrighter(double mv) {
        int i = upper(mv);
        return i < 0 ? 0 : lfCount[i];
    }

    /** V luminosity (L☉) per star formed in stars brighter than M. */
    public double lumBrighter(double mv) {
        int i = upper(mv);
        return i < 0 ? 0 : lfLum[i];
    }

    /** Faintest magnitude M such that countBrighter(M) · n ≤ budget. */
    public double magnitudeForCount(double n, double budget) {
        if (n <= 0) return 99;
        double target = budget / n;
        int i = Arrays.binarySearch(lfCount, target);
        if (i < 0) i = -i - 2;
        if (i < 0) return -99;
        return i >= lfMag.length - 1 ? 99 : lfMag[i];
    }

    public double brightestMv() {
        double b = Double.POSITIVE_INFINITY;
        for (double v : brightestMv) b = Math.min(b, v);
        return b;
    }

    private int upper(double mv) {
        int i = Arrays.binarySearch(lfMag, mv);
        if (i < 0) i = -i - 2;
        else while (i + 1 < lfMag.length && lfMag[i + 1] == mv) i++;
        return i;
    }

    // ------------------------------------------------------------------ stellar evolution

    static final int KIND_MS = 0, KIND_GIANT = 1, KIND_SUPERGIANT = 2, KIND_WD = 3, KIND_NS = 4, KIND_BH = 5;

    /** Evolves one star; returns {kind, luminosity L☉, Teff K, current mass M☉}. */
    static double[] evolve(double m0, double age, double feh, SeedRng rng) {
        double tms = StellarPhysics.mainSequenceLifetimeGyr(m0);
        if (age < tms) {
            double f = age / tms;
            double lum = StellarPhysics.mainSequenceLuminosity(m0) * (1 + 0.4 * f);
            double rad = StellarPhysics.mainSequenceRadius(m0) * (1 + 0.25 * f);
            return new double[] {KIND_MS, lum, StellarPhysics.effectiveTemperature(lum, rad), m0};
        }
        if (age < tms * (1 + POST_MS)) {
            double phi = (age - tms) / (POST_MS * tms);
            double msl = StellarPhysics.mainSequenceLuminosity(m0);
            if (m0 >= 8) {
                double lum = msl * rng.range(1.0, 1.6);
                double t = phi < 0.5 ? rng.range(10_000, 25_000) : rng.range(3_500, 4_500);
                return new double[] {KIND_SUPERGIANT, lum, t, m0 * 0.9};
            }
            if (m0 >= 2.2) {
                // Intermediate mass: brief Hertzsprung gap, core-helium burning (blue loops), early AGB.
                if (phi < 0.15) return new double[] {KIND_GIANT, msl * 1.6, 8_000 - 20_000 * phi, m0};
                if (phi < 0.9) return new double[] {KIND_GIANT, msl * rng.range(2.0, 4.0), rng.range(4_400, 5_800), m0};
                return new double[] {KIND_GIANT, msl * rng.range(4.0, 12.0), rng.range(3_600, 4_200), m0 * 0.9};
            }
            double metalPoor = Math.max(0, Math.min(2.0, -feh));
            if (phi < 0.80) {
                double x = phi / 0.80;
                double base = msl * 2.0, tip = 2_500.0;
                double lum = base * Math.pow(tip / base, x * x * x);
                double frac = Math.log(lum / base) / Math.log(tip / base);
                double t = 5_100 - 1_150 * frac + 160 * metalPoor;
                return new double[] {KIND_GIANT, lum, t, m0};
            }
            if (phi < 0.95) {
                double lum = 50 * rng.range(0.8, 1.25);
                double t;
                if (feh > -1.0) t = rng.range(4_600, 5_050);          // red clump
                else if (rng.nextDouble() < 0.35 + 0.35 * (metalPoor - 1)) t = rng.range(7_500, 14_000); // blue HB
                else t = rng.range(5_400, 7_200);                       // red HB / RR Lyrae gap
                return new double[] {KIND_GIANT, lum, t, m0 * 0.8};
            }
            return new double[] {KIND_GIANT, rng.logUniform(100, 3_000), rng.range(3_700, 4_500), m0 * 0.75};
        }
        if (m0 < 8) {
            double mass = StellarPhysics.whiteDwarfMass(m0);
            double rad = StellarPhysics.whiteDwarfRadius(mass);
            double t = StellarPhysics.whiteDwarfTemperature(age - tms * (1 + POST_MS));
            return new double[] {KIND_WD, rad * rad * Math.pow(t / 5772.0, 4), t, mass};
        }
        if (m0 < 20) {
            double t = 5e5 * Math.pow(Math.max(1e-3, age - tms), -0.3);
            double rad = 12_000.0 / 6.957e8;
            return new double[] {KIND_NS, rad * rad * Math.pow(t / 5772.0, 4), t, rng.range(1.2, 2.1)};
        }
        return new double[] {KIND_BH, 0, 0, m0 * 0.35};
    }

    static StarKind kind(double code) {
        return switch ((int) code) {
            case KIND_GIANT -> StarKind.GIANT;
            case KIND_SUPERGIANT -> StarKind.SUPERGIANT;
            case KIND_WD -> StarKind.WHITE_DWARF;
            case KIND_NS -> StarKind.NEUTRON_STAR;
            case KIND_BH -> StarKind.BLACK_HOLE;
            default -> StarKind.MAIN_SEQUENCE;
        };
    }

    /** Absolute V magnitude, same convention as {@code Star.absoluteMagnitude()}. */
    static double absoluteMv(double lum, double teff) {
        return 4.74 - 2.5 * Math.log10(Math.max(1e-12, lum)) - SpectralClass.bolometricCorrection(teff);
    }
}
