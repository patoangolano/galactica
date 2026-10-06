package dev.galactica.deepsky;

import dev.galactica.science.SeedRng;
import dev.galactica.star.SpectralClass;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An open or globular star cluster: {@link #nStars} stars formed together {@link #ageGyr} ago,
 * spatially distributed with a {@link KingProfile}. Members are never stored: member {@code i} of
 * mass bin {@code k} is a pure function of (cluster seed, k, i), and only the bins bright enough to
 * matter at the observer's distance are generated. The light of members that are not drawn as
 * individual stars is rendered as a smooth glow (the cluster's multi-Gaussian {@link Blob}s,
 * scaled by {@link #unresolvedFraction}).
 *
 * <p>Real bright members can be given as {@link Anchor}s (e.g. the Seven Sisters of the Pleiades
 * with their measured positions and magnitudes); the procedural members are then drawn only
 * below {@code massCap} so the anchors are not duplicated.
 */
public final class StarCluster extends DeepSkyObject {
    /**
     * Number of members the starfield may draw for one cluster; with the starfield's own budget
     * this keeps a view from the core of ω Centauri bounded. Fainter members stay in the glow.
     */
    public static final double MEMBER_BUDGET = 9_000;
    /** Same faint limit as the starfield. */
    public static final double LIMITING_MAGNITUDE = 7.5;

    /** A real star of the cluster, J2000 position, apparent V magnitude, effective temperature. */
    public record Anchor(String name, double raDeg, double decDeg, double vMag, double teff, StarKind kind, double mass) {}

    public final double ageGyr, feh, nStars, foregroundAv;
    public final KingProfile king;
    public final ClusterPopulation population;
    private final List<Anchor> anchors;
    private volatile List<Star> anchorStars;
    /** Associated nebulosity (e.g. the Pleiades reflection nebula), or null. */
    private final Function<DeepSkyObject, List<Blob>> extra;

    StarCluster(String id, String name, DeepSkyType type, double[] pos, boolean catalogued, long seed,
                double ageGyr, double feh, double nStars, double rcPc, double rtPc,
                List<Anchor> anchors, double massCap, double foregroundAv, Function<DeepSkyObject, List<Blob>> extra) {
        super(id, name, type, pos, Math.max(rtPc, rcPc * 1.5), catalogued, seed, o -> ((StarCluster) o).allBlobs());
        this.extra = extra;
        this.ageGyr = ClusterPopulation.snapAge(ageGyr);
        this.feh = ClusterPopulation.snapFeh(feh);
        this.nStars = nStars;
        this.king = new KingProfile(rcPc, rtPc);
        this.population = ClusterPopulation.of(ageGyr, feh, massCap);
        this.anchors = List.copyOf(anchors);
        this.foregroundAv = foregroundAv;
    }

    /** Total V-band luminosity (L☉), procedural members plus anchors. */
    public double luminosityV() {
        double l = nStars * population.lumPerStar;
        for (Star s : anchorStars()) l += Math.pow(10, -0.4 * (s.absoluteMagnitude() - 4.83));
        return l;
    }

    /**
     * Absolute magnitude of the faintest member drawn individually for an observer at distance d
     * (pc) whose line of sight to the cluster has V extinction {@code avMag}: the member must reach
     * the starfield's limiting magnitude after extinction, and the count is capped at
     * {@link #MEMBER_BUDGET}. {@link DeepSky#resolution} is the one place both the sky bake and the
     * starfield get this value from.
     */
    public double resolvedMagnitudeLimit(double dPc, double avMag) {
        double dEff = Math.max(dPc, king.halfMassRadius());
        double byEye = LIMITING_MAGNITUDE - 5 * Math.log10(dEff / 10.0) - Math.max(0, avMag);
        return Math.min(byEye, population.magnitudeForCount(nStars, MEMBER_BUDGET));
    }

    /** Absolute magnitude of the brightest member (anchors included). */
    public double brightestMv() {
        double b = population.brightestMv();
        for (Star s : anchorStars()) b = Math.min(b, s.absoluteMagnitude());
        return b;
    }

    /**
     * Scale of the cluster's smooth glow (whose volumes carry the expected light of all procedural
     * members) when members brighter than absolute magnitude {@code limitMv} are drawn as stars.
     *
     * <p>The glow keeps the <em>expected</em> light of the procedural members fainter than the
     * limit, plus every anchor fainter than the limit; the members brighter than the limit are
     * exactly the stars {@link #members} emits. The faint members are never generated, so their
     * expectation is the right stand-in for them; the bright ones are the actual draw. No member
     * is in both layers or in neither, whatever the Poisson draw of the bright end. The value is
     * 1 when nothing is resolved and may exceed 1 slightly when unresolved anchors are folded into
     * the glow (far away).
     */
    public double unresolvedFraction(double limitMv) {
        double proc = nStars * population.lumPerStar;
        if (proc <= 0) return 0;
        double glow = nStars * Math.max(0, population.lumPerStar - population.lumBrighter(limitMv));
        for (Star s : anchorStars()) {
            double mv = s.absoluteMagnitude();
            if (mv > limitMv) glow += Math.pow(10, -0.4 * (mv - 4.83));
        }
        return glow / proc;
    }

    /** The real anchor stars as {@link Star}s (catalog = true). */
    public List<Star> anchorStars() {
        List<Star> a = anchorStars;
        if (a == null) {
            List<Star> out = new ArrayList<>();
            double[] sun = DeepMath.galactocentric(0, 0, 0);
            double dist = Math.sqrt(sq(x - sun[0]) + sq(y - sun[1]) + sq(z - sun[2]));
            int i = 0;
            for (Anchor an : anchors) {
                double[] lb = DeepMath.equatorialToGalactic(an.raDeg(), an.decDeg());
                // Members are spread in depth like on the sky: a deterministic offset of ~r_h.
                SeedRng rng = new SeedRng(SeedRng.hash(seed, 0xA4C0L, i));
                double d = dist + rng.gaussian() * king.projectedHalfRadius() * 0.5;
                double[] p = DeepMath.galactocentric(lb[0], lb[1], d);
                double mv = an.vMag() - 5 * Math.log10(d / 10.0) - foregroundAv;
                double mbol = mv + SpectralClass.bolometricCorrection(an.teff());
                double lum = Math.pow(10, (4.74 - mbol) / 2.5);
                double radius = Math.sqrt(lum) * Math.pow(5772.0 / an.teff(), 2);
                out.add(new Star(-10_000 - (Math.abs(seed % 10_000) * 32 + i), an.name(), p[0], p[1], p[2], an.kind(),
                        an.mass(), radius, lum, an.teff(), ageGyr, feh, SeedRng.hash(seed, 0x5157_E3L, i), true));
                i++;
            }
            a = List.copyOf(out);
            anchorStars = a;
        }
        return a;
    }

    /** Expected number of procedural members in mass bin k (Poisson draw, deterministic). */
    public int binCount(int k) {
        SeedRng rng = new SeedRng(SeedRng.hash(seed, 0xB1A5L, k));
        return rng.poisson(nStars * population.fraction[k]);
    }

    /**
     * Emits every member brighter than absolute magnitude {@code maxMv} (anchors included).
     * Only bins whose brightest member can pass are generated.
     */
    public void members(double maxMv, Consumer<Star> sink) {
        for (Star s : anchorStars()) if (s.absoluteMagnitude() <= maxMv) sink.accept(s);
        ClusterPopulation p = population;
        for (int k = 0; k < p.binCount(); k++) {
            if (p.brightestMv[k] - 0.75 > maxMv) continue;
            int n = binCount(k);
            for (int i = 0; i < n; i++) {
                Star s = member(k, i);
                if (s != null && s.absoluteMagnitude() <= maxMv) sink.accept(s);
            }
        }
    }

    /** Member i of mass bin k, or null for a black hole. */
    public Star member(int k, int i) {
        long ms = SeedRng.hash(seed, k, i);
        SeedRng rng = new SeedRng(ms);
        ClusterPopulation p = population;
        double m0 = ClusterPopulation.massInBin(p.edges[k], p.edges[k + 1], rng.nextDouble());
        double r = king.radiusAt(rng.nextDouble());
        double cz = 2 * rng.nextDouble() - 1, ph = 2 * Math.PI * rng.nextDouble(), sz = Math.sqrt(1 - cz * cz);
        double[] st = ClusterPopulation.evolve(m0, ageGyr, feh + 0.03 * rng.gaussian(), rng);
        if (st[0] == ClusterPopulation.KIND_BH) return null;
        StarKind kind = ClusterPopulation.kind(st[0]);
        double lum = st[1], teff = st[2];
        double radius = kind == StarKind.NEUTRON_STAR ? 12_000.0 / 6.957e8 : Math.sqrt(Math.max(1e-12, lum)) * Math.pow(5772.0 / teff, 2);
        return new Star(ms, name + " " + designation(k, i), x + r * sz * Math.cos(ph), y + r * sz * Math.sin(ph), z + r * cz,
                kind, st[3], radius, lum, teff, ageGyr, feh, SeedRng.hash(ms, 0x5157_E3L), false);
    }

    /**
     * Up to {@code max} members (anchors included, black holes excluded) within {@code radiusPc}
     * of (px, py, pz), nearest first. Positions are drawn before the expensive stellar evolution,
     * and the King radius of a member is a monotonic function of its second random number, so the
     * members that cannot lie in the query sphere are rejected after one hash and two random
     * numbers; only the nearest {@code max} candidates are evolved into {@link Star}s.
     */
    public List<Star> membersNear(double px, double py, double pz, double radiusPc, int max) {
        List<Star> out = new ArrayList<>();
        if (max <= 0) return out;
        double dc = distanceTo(px, py, pz);
        double rLo = Math.max(0, dc - radiusPc), rHi = dc + radiusPc;
        double uLo = rLo <= 0 ? 0 : cdfBound(rLo, false), uHi = rHi >= king.rt ? 1 : cdfBound(rHi, true);
        double r2 = radiusPc * radiusPc;
        // Max-heap on distance² of {d², k, i}: holds the nearest `max` (+ slack for black holes).
        int keep = max + 8;
        java.util.PriorityQueue<double[]> heap = new java.util.PriorityQueue<>(keep + 1, (a, b) -> Double.compare(b[0], a[0]));
        ClusterPopulation p = population;
        for (int k = 0; rLo < king.rt && k < p.binCount(); k++) {
            int n = binCount(k);
            for (int i = 0; i < n; i++) {
                SeedRng rng = new SeedRng(SeedRng.hash(seed, k, i));
                rng.nextDouble(); // mass, same sequence as member(k, i)
                double u = rng.nextDouble();
                if (u < uLo || u > uHi) continue;
                double r = king.radiusAt(u);
                double cz = 2 * rng.nextDouble() - 1, ph = 2 * Math.PI * rng.nextDouble(), sz = Math.sqrt(1 - cz * cz);
                double mx = x + r * sz * Math.cos(ph) - px, my = y + r * sz * Math.sin(ph) - py, mz = z + r * cz - pz;
                double d2 = mx * mx + my * my + mz * mz;
                if (d2 > r2) continue;
                if (heap.size() >= keep) {
                    if (d2 >= heap.peek()[0]) continue;
                    heap.poll();
                }
                heap.add(new double[] {d2, k, i});
            }
        }
        for (double[] h : heap) {
            Star s = member((int) h[1], (int) h[2]);
            if (s != null) out.add(s);
        }
        for (Star s : anchorStars()) if (s.distanceTo(px, py, pz) <= radiusPc) out.add(s);
        out.sort((a, b) -> Double.compare(a.distanceTo(px, py, pz), b.distanceTo(px, py, pz)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Bisection on the monotonic {@link KingProfile#radiusAt}: with {@code upper} false, the largest
     * u found with radiusAt(u) &lt; r (every u below it lies inside r); with {@code upper} true, the
     * smallest u found with radiusAt(u) &gt; r (every u above it lies outside r).
     */
    private double cdfBound(double r, boolean upper) {
        double lo = 0, hi = 1;
        for (int it = 0; it < 40; it++) {
            double mid = 0.5 * (lo + hi);
            double rm = king.radiusAt(mid);
            if (upper ? rm <= r : rm < r) lo = mid;
            else hi = mid;
        }
        return upper ? hi : lo;
    }

    private static String designation(int k, int i) {
        return Character.toString((char) ('A' + Math.min(25, k))) + i;
    }

    private List<Blob> allBlobs() {
        List<Blob> out = new ArrayList<>(glowBlobs());
        if (extra != null) out.addAll(extra.apply(this));
        return out;
    }

    /** Smooth glow of the whole cluster (multi-Gaussian King profile), tagged for per-view scaling. */
    private List<Blob> glowBlobs() {
        double lum = nStars * population.lumPerStar; // anchors are always resolved stars
        if (lum <= 0) return List.of();
        double[] rgb = {population.colour[0], population.colour[1], population.colour[2]};
        // Same convention as the baker's populations: channel = L × colour (max component 1).
        double lumScale = lum;
        Mge m = king.mge();
        List<Blob> out = new ArrayList<>();
        double[] c = position();
        for (int k = 0; k < m.sigma.length; k++) {
            if (m.weight[k] < 1e-4) continue;
            out.add(Blob.sphere(c, m.sigma[k], rgb, lumScale * m.weight[k], 0).asGlowOf(this));
        }
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }
}
