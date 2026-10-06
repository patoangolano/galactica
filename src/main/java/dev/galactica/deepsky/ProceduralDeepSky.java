package dev.galactica.deepsky;

import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.science.SeedRng;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural deep-sky objects beyond the catalogue, deterministic in the galaxy seed.
 *
 * <p><b>Globular clusters</b> bring the total to {@link #GLOBULAR_TOTAL} (the Harris 2010
 * catalogue lists 157): a metal-poor halo population with number density ∝ r^{−3.5} (Harris
 * 2001; Djorgovski &amp; Meylan 1994) and a metal-rich bulge/disk population (Zinn 1985), ~30 %.
 * Luminosities follow the Gaussian globular-cluster luminosity function M_V = −7.4 ± 1.2
 * (Harris 2001), concentrations c = 1.5 ± 0.35 and half-light radii ~3 pc (log-normal).
 *
 * <p><b>Disk objects</b> are drawn per 250 pc column of the disk with Poisson counts whose mean
 * scales with the {@link GalaxyModel} density at the column, normalised to the observed local
 * surface densities at the solar circle:
 * <ul>
 *   <li>H II regions ∝ young-star density (arms), Σ ≈ 8 kpc⁻² above L(Hα) ≈ 50 L☉, luminosity
 *       function dN/dL ∝ L^{−1.8} (McKee &amp; Williams 1997), radius ∝ L^{1/3} (Strömgren);</li>
 *   <li>open clusters: young (&lt; 100 Myr) ∝ young-star density, Σ ≈ 60 kpc⁻², older ones
 *       ∝ disk density, Σ ≈ 90 kpc⁻² (Piskunov et al. 2006); embedded-cluster mass function
 *       dN/dM ∝ M^{−2} from 50 to 2 × 10⁴ M☉ (Lada &amp; Lada 2003);</li>
 *   <li>dark molecular clouds ∝ dust density (concentrated in arms), Σ ≈ 40 kpc⁻².</li>
 * </ul>
 * Nothing procedural is placed where the real sky is already catalogued: no H II regions within
 * 600 pc of the Sun, no open clusters within 300 pc, and no dense clouds inside the Local Bubble
 * (~120 pc, Lallement et al. 2003; Zucker et al. 2022, Nature 601, 334).
 */
public final class ProceduralDeepSky {
    private ProceduralDeepSky() {}

    public static final int GLOBULAR_TOTAL = 150;
    public static final double CELL_PC = 250.0;
    static final double SIGMA_HII = 8, SIGMA_OC_YOUNG = 60, SIGMA_OC_OLD = 90, SIGMA_DARK = 18; // per kpc²
    private static final double SUN_EXCLUDE_HII = 600, SUN_EXCLUDE_OC = 300, SUN_EXCLUDE_DARK = 120;

    private static final double[] SUN = DeepMath.galactocentric(0, 0, 0);
    private static final double NORM_YOUNG, NORM_DISK, NORM_DUST;

    static {
        double y = 0, d = 0, u = 0;
        int n = 720;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double x = GalaxyModel.R0 * Math.cos(a), yy = GalaxyModel.R0 * Math.sin(a);
            y += young(x, yy);
            d += disk(x, yy);
            u += GalaxyModel.dustExtinctionPerPc(x, yy, 0);
        }
        NORM_YOUNG = y / n;
        NORM_DISK = d / n;
        NORM_DUST = u / n;
    }

    private static double disk(double x, double y) {
        return Math.max(0, GalaxyModel.starDensity(x, y, 0) - GalaxyModel.bulgeDensity(x, y, 0) - GalaxyModel.haloDensity(x, y, 0));
    }

    private static double young(double x, double y) {
        return GalaxyModel.youngFraction(x, y, 0) * disk(x, y);
    }

    private static double distSun(double x, double y, double z) {
        return Math.sqrt(sq(x - SUN[0]) + sq(y - SUN[1]) + sq(z - SUN[2]));
    }

    /** Designation from the position seen from the Sun, e.g. "G209.0-19.4". */
    static String designation(String prefix, double x, double y, double z) {
        double[] lb = DeepMath.galacticFromSun(x, y, z);
        return String.format(java.util.Locale.ROOT, "%s G%05.1f%+05.1f", prefix, lb[0], lb[1]);
    }

    // ------------------------------------------------------------------ globular clusters

    public static List<DeepSkyObject> globulars(long seed) {
        List<DeepSkyObject> out = new ArrayList<>();
        int named = 0;
        for (DeepSkyObject o : DeepSkyCatalog.all()) if (o.type == DeepSkyType.GLOBULAR_CLUSTER) named++;
        SeedRng rng = new SeedRng(SeedRng.hash(seed, 0x610B_C1L));
        int made = 0, guard = 0;
        while (made < GLOBULAR_TOTAL - named && guard++ < 10_000) {
            boolean bulge = rng.nextDouble() < 0.3;
            double x, y, z, feh;
            if (bulge) {
                x = rng.gaussian() * 1200;
                y = rng.gaussian() * 1200;
                z = rng.gaussian() * 700;
                feh = -0.6 + 0.3 * rng.gaussian();
            } else {
                // ρ ∝ r^-3.5 ⇒ dN/dr ∝ r^-1.5 ⇒ r = (a + u(b − a))^-2 with a = r1^-1/2, b = r2^-1/2.
                double a = Math.pow(1_500, -0.5), b = Math.pow(100_000, -0.5);
                double r = Math.pow(a + rng.nextDouble() * (b - a), -2);
                double[] d = Morphology.randomUnit(rng);
                x = r * d[0];
                y = r * d[1];
                z = r * d[2] * 0.8;
                feh = -1.6 + 0.3 * rng.gaussian();
            }
            double mv = Math.max(-10.2, Math.min(-4.5, -7.4 + 1.2 * rng.gaussian()));
            double c = Math.max(0.8, Math.min(2.5, 1.5 + 0.35 * rng.gaussian()));
            double rh = 3.0 * Math.pow(10, 0.25 * rng.gaussian());
            long s = rng.nextLong();
            if (distSun(x, y, z) < 1_500) continue;
            double rc = rh / halfLightOverCore(c);
            double age = feh > -1.0 ? 11.5 : 12.6;
            ClusterPopulation pop = ClusterPopulation.of(age, feh, 150);
            double n = Math.pow(10, -0.4 * (mv - 4.83)) / pop.lumPerStar;
            out.add(new StarCluster("gc" + made, designation("GCl", x, y, z), DeepSkyType.GLOBULAR_CLUSTER,
                    new double[] {x, y, z}, false, s, age, feh, n, rc, rc * Math.pow(10, c), List.of(), 150, 0, null));
            made++;
        }
        return out;
    }

    private static final java.util.Map<Integer, Double> RH_OVER_RC = new java.util.concurrent.ConcurrentHashMap<>();

    static double halfLightOverCore(double c) {
        int key = (int) Math.round(c * 20);
        return RH_OVER_RC.computeIfAbsent(key, k -> KingProfile.halfLightOverCore(k / 20.0));
    }

    // ------------------------------------------------------------------ disk cells

    static long cellKey(int ix, int iy) {
        return ((long) ix << 32) ^ (iy & 0xFFFF_FFFFL);
    }

    /** All procedural disk objects of one 250 pc column. */
    public static List<DeepSkyObject> cell(long seed, int ix, int iy) {
        double x0 = ix * CELL_PC, y0 = iy * CELL_PC;
        double cx = x0 + CELL_PC / 2, cy = y0 + CELL_PC / 2;
        if (cx * cx + cy * cy > GalaxyModel.RADIUS_LIMIT * GalaxyModel.RADIUS_LIMIT * 0.6) return List.of();
        double area = CELL_PC * CELL_PC / 1e6; // kpc²
        SeedRng rng = new SeedRng(SeedRng.hash(seed, 0xCE11L, ix, iy));
        double yw = young(cx, cy) / NORM_YOUNG, dw = disk(cx, cy) / NORM_DISK, uw = GalaxyModel.dustExtinctionPerPc(cx, cy, 0) / NORM_DUST;
        List<DeepSkyObject> out = new ArrayList<>();

        int nHii = rng.poisson(SIGMA_HII * area * yw);
        for (int i = 0; i < nHii; i++) {
            double x = x0 + rng.nextDouble() * CELL_PC, y = y0 + rng.nextDouble() * CELL_PC, z = 50 * rng.gaussian();
            // dN/dL ∝ L^-1.8 between 50 and 3 × 10^5 L☉.
            double a = Math.pow(50, -0.8), b = Math.pow(3e5, -0.8);
            double l = Math.pow(a + rng.nextDouble() * (b - a), -1 / 0.8);
            double r = 3.0 * Math.cbrt(l / 1000.0);
            double oiii = Math.min(0.9, 0.1 + 0.15 * Math.log10(l / 50));
            double dust = rng.range(2, 6);
            long s = rng.nextLong();
            if (distSun(x, y, z) < SUN_EXCLUDE_HII) continue;
            double[] p = {x, y, z};
            out.add(DeepSkyObject.of("hii:" + Long.toHexString(s), designation("HII", x, y, z), DeepSkyType.EMISSION_NEBULA,
                    p, r * 2.2, false, s, o -> Morphology.hiiRegion(o.seed, Morphology.Frame.random(p, new SeedRng(o.seed ^ 7),
                            r * rng2(o.seed, 0.8, 1.4), r, r * rng2(o.seed ^ 3, 0.6, 1.0)), l, oiii, dust, (int) (o.seed & 1))));
        }

        int nYoung = rng.poisson(SIGMA_OC_YOUNG * area * yw);
        int nOld = rng.poisson(SIGMA_OC_OLD * area * dw);
        for (int i = 0; i < nYoung + nOld; i++) {
            boolean isYoung = i < nYoung;
            double x = x0 + rng.nextDouble() * CELL_PC, y = y0 + rng.nextDouble() * CELL_PC;
            double z = (isYoung ? 60 : 150) * rng.gaussian();
            double age = isYoung ? rng.logUniform(0.001, 0.1) : rng.logUniform(0.1, 3.0);
            // dN/dM ∝ M^-2 between 50 and 2 × 10^4 M☉, with mass loss for old clusters.
            double mass = 1.0 / (1.0 / 50 - rng.nextDouble() * (1.0 / 50 - 1.0 / 20_000));
            if (!isYoung) mass *= Math.pow(age / 0.1, -0.3);
            double n = Math.max(30, mass / 0.6);
            double rc = rng.logUniform(0.5, 2.5);
            double rt = rc * Math.pow(10, rng.range(0.6, 1.2));
            double feh = GalaxyModel.metallicity(x, y, z) + 0.1 * rng.gaussian();
            long s = rng.nextLong();
            if (distSun(x, y, z) < SUN_EXCLUDE_OC) continue;
            out.add(new StarCluster("oc:" + Long.toHexString(s), designation("OCl", x, y, z), DeepSkyType.OPEN_CLUSTER,
                    new double[] {x, y, z}, false, s, age, feh, n, rc, rt, List.of(), 150, 0, null));
        }

        int nDark = rng.poisson(SIGMA_DARK * area * uw);
        for (int i = 0; i < nDark; i++) {
            double x = x0 + rng.nextDouble() * CELL_PC, y = y0 + rng.nextDouble() * CELL_PC, z = 45 * rng.gaussian();
            double r = rng.logUniform(3, 25);
            double av = rng.logUniform(0.4, 2.0);
            int fil = 3 + rng.nextInt(6);
            double env = rng.range(0.15, 0.4);
            long s = rng.nextLong();
            if (distSun(x, y, z) < SUN_EXCLUDE_DARK + r) continue;
            double[] p = {x, y, z};
            out.add(DeepSkyObject.of("dn:" + Long.toHexString(s), designation("DN", x, y, z), DeepSkyType.DARK_NEBULA,
                    p, r * 1.6, false, s, o -> Morphology.darkCloud(o.seed, Morphology.Frame.random(p, new SeedRng(o.seed ^ 5),
                            r, r * rng2(o.seed, 0.5, 1.0), r * rng2(o.seed ^ 9, 0.3, 0.7)), av, fil, env)));
        }
        return out;
    }

    private static double rng2(long seed, double lo, double hi) {
        return new SeedRng(seed).range(lo, hi);
    }

    private static double sq(double v) {
        return v * v;
    }
}
