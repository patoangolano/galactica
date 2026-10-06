package dev.galactica.deepsky;

import dev.galactica.science.SeedRng;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;

import static dev.galactica.deepsky.DeepMath.dec;
import static dev.galactica.deepsky.DeepMath.ra;

/**
 * Real deep-sky objects. Positions are galactic (l, b) or J2000 (α, δ) with distances from the
 * literature; physical sizes are angular sizes times distance. Sources:
 * <ul>
 *   <li>Orion: distance 414 ± 7 pc (Menten et al. 2007), 412 pc used; ionising rate
 *       Q ≈ 10⁴⁹ s⁻¹ from θ¹ Ori C (O'Dell 2001, ARA&amp;A 39, 99); Barnard's Loop and λ Ori ring
 *       (Ochsendorf et al. 2015).</li>
 *   <li>Carina NGC 3372: 2.3 kpc, Q ≈ 10⁵¹ s⁻¹ (Smith 2006, MNRAS 367, 763).</li>
 *   <li>Lagoon M8: 1.25 kpc (Tothill et al. 2008, Handbook of Star Forming Regions II);
 *       Eagle M16: 1.74 kpc (Hillenbrand et al. 1993; Hester et al. 1996, AJ 111, 2349);
 *       Rosette: 1.6 kpc (Román-Zúñiga &amp; Lada 2008); North America/Pelican: 795 pc
 *       (Kuhn et al. 2020, Gaia DR2).</li>
 *   <li>Crab M1: 2.0 kpc, synchrotron M_V ≈ −4.7 (Hester 2008, ARA&amp;A 46, 127); Vela SNR: 287 pc
 *       (Dodson et al. 2003 pulsar parallax); Cygnus Loop: 735 pc (Fesen et al. 2018, Gaia).</li>
 *   <li>Ring M57: 787 pc; Helix NGC 7293: 200 pc (Gaia DR3 parallaxes of the central stars;
 *       O'Dell et al. 2004/2013 for the 3D structure).</li>
 *   <li>Coalsack: 180 pc, A_V ≈ 1.5 (Seidensticker &amp; Schmidt-Kaler 1989); Barnard 68: 125 pc,
 *       A_V &gt; 25 at the centre (Alves, Lada &amp; Lada 2001, Nature 409, 159); Taurus: 141 pc
 *       (Galli et al. 2019); ρ Oph: 139 pc (Ortiz-León et al. 2017); Pipe: 130 pc (Lombardi et al. 2006).</li>
 *   <li>Open clusters: Gaia DR2 distances (Cantat-Gaudin et al. 2018), ages and structure from
 *       Kharchenko et al. 2013 (MWSC); Pleiades member photometry (Hipparcos/SIMBAD);
 *       h and χ Persei: 2.34 kpc, 14 Myr (Currie et al. 2010); ONC: Hillenbrand 1997.</li>
 *   <li>Globular clusters: Harris (1996, 2010 edition) — distance, M_V, core radius, concentration,
 *       [Fe/H].</li>
 *   <li>Magellanic Clouds: LMC 49.59 kpc (Pietrzyński et al. 2019, Nature 567, 200), disk
 *       i = 34.7°, line of nodes Θ = 122.5° (van der Marel &amp; Cioni 2001); SMC 62.4 kpc
 *       (Graczyk et al. 2020). M31: 765 kpc, i = 77°, PA = 38°, disk scale length 5.3 kpc
 *       (Courteau et al. 2011); M33: 840 kpc (Freedman et al. 1991; Gieren et al. 2013).</li>
 * </ul>
 * The ionised-line luminosity used for an H II region is L(Hα + Hβ) ≈ 3.6 × 10⁻⁴⁶ L☉ · Q(H⁰)
 * (case B, T_e = 10⁴ K).
 */
public final class DeepSkyCatalog {
    private DeepSkyCatalog() {}

    private static volatile List<DeepSkyObject> all;

    public static List<DeepSkyObject> all() {
        List<DeepSkyObject> a = all;
        if (a == null) {
            synchronized (DeepSkyCatalog.class) {
                a = all;
                if (a == null) {
                    a = List.copyOf(build());
                    all = a;
                }
            }
        }
        return a;
    }

    static long seedOf(String id) {
        return SeedRng.hash(0xDEE9_5C7AL, id.hashCode());
    }

    static double[] lb(double l, double b, double d) {
        return DeepMath.galactocentric(l, b, d);
    }

    static double[] eq(double raDeg, double decDeg, double d) {
        double[] g = DeepMath.equatorialToGalactic(raDeg, decDeg);
        return DeepMath.galactocentric(g[0], g[1], d);
    }

    /** Unit vector from the Sun towards a galactocentric point. */
    static double[] sightLine(double[] p) {
        double[] sun = DeepMath.galactocentric(0, 0, 0);
        return DeepMath.normalize(new double[] {p[0] - sun[0], p[1] - sun[1], p[2] - sun[2]});
    }

    /** On-sky tangent directions at a point seen from the Sun: {towards +l, towards +b, line of sight}. */
    static double[][] skyBasis(double[] p) {
        double[] n = sightLine(p);
        double[] ngp = {0, 0, 1};
        double[] east = DeepMath.normalize(DeepMath.cross(ngp, n));   // increasing l
        // Sign: d(direction)/dl for direction (−cos l, −sin l) is (sin l, −cos l) = ngp × n.
        double[] north = DeepMath.cross(n, east);                      // increasing b
        return new double[][] {east, north, n};
    }

    /** Equatorial sky basis at a point: {east (increasing α), north (increasing δ), line of sight}. */
    static double[][] equatorialBasis(double[] p) {
        double[] n = sightLine(p);
        double[] ncp = DeepMath.northCelestialPole();
        double[] north = DeepMath.normalize(new double[] {ncp[0] - DeepMath.dot(ncp, n) * n[0],
                ncp[1] - DeepMath.dot(ncp, n) * n[1], ncp[2] - DeepMath.dot(ncp, n) * n[2]});
        double[] east = DeepMath.cross(north, n);
        return new double[][] {east, north, n};
    }

    /**
     * Normal of a disk with inclination i and line-of-nodes position angle Θ (degrees, from north
     * through east), seen from the Sun.
     */
    static double[] diskNormal(double[] p, double incDeg, double paDeg) {
        double[][] e = equatorialBasis(p);
        double pa = Math.toRadians(paDeg), inc = Math.toRadians(incDeg);
        double[] nodes = new double[3], toSun = new double[3];
        for (int k = 0; k < 3; k++) {
            nodes[k] = Math.cos(pa) * e[1][k] + Math.sin(pa) * e[0][k];
            toSun[k] = -e[2][k];
        }
        double[] perp = DeepMath.cross(nodes, toSun);
        return DeepMath.normalize(new double[] {
                Math.cos(inc) * toSun[0] + Math.sin(inc) * perp[0],
                Math.cos(inc) * toSun[1] + Math.sin(inc) * perp[1],
                Math.cos(inc) * toSun[2] + Math.sin(inc) * perp[2]});
    }

    private static Morphology.Frame frameRandom(String id, double[] c, double sx, double sy, double sz) {
        return Morphology.Frame.random(c, new SeedRng(seedOf(id) ^ 0xF4A3EL), sx, sy, sz);
    }

    /** Frame aligned with the sky as seen from the Sun: local x along +l, y along +b, z along the line of sight. */
    private static Morphology.Frame frameSky(double[] c, double sl, double sb, double sd) {
        double[][] b = skyBasis(c);
        return new Morphology.Frame(c, b[0], b[1], b[2], sl, sb, sd);
    }

    private static DeepSkyObject neb(String id, String name, DeepSkyType type, double[] pos, double radius,
                                     java.util.function.Function<DeepSkyObject, List<Blob>> builder) {
        return DeepSkyObject.of(id, name, type, pos, radius, true, seedOf(id), builder);
    }

    private static List<DeepSkyObject> build() {
        List<DeepSkyObject> out = new ArrayList<>();
        // ------------------------------------------------------------ emission nebulae
        double[] m42 = lb(209.01, -19.38, 412);
        out.add(neb("m42", "Orion Nebula (M42)", DeepSkyType.EMISSION_NEBULA, m42, 7, o -> List.of())
                .withFields(o -> NebulaShapes.orionNebula(o.seed, m42)));
        double[] ic434 = lb(206.95, -16.60, 400);
        out.add(neb("ic434", "Horsehead Nebula (IC 434)", DeepSkyType.EMISSION_NEBULA, ic434, 5, o -> {
            List<Blob> b = new ArrayList<>();
            // The Horsehead: a dark pillar of the L1630 cloud in front of the ionised sheet.
            double[][] sk = skyBasis(ic434);
            double[] head = {ic434[0] - sk[2][0] * 1.5 - sk[0][0] * 0.4, ic434[1] - sk[2][1] * 1.5 - sk[0][1] * 0.4, ic434[2] - sk[2][2] * 1.5 - sk[0][2] * 0.4};
            b.add(Blob.axial(head, sk[0], 0.35, 0.12, new double[3], 0, Blob.dustFor(12, 0.35, 0.12)));
            return b;
        }).withFields(o -> List.of(NebulaShapes.hii(o.seed, frameSky(ic434, 1.2, 3.2, 0.8), 350, 0.05, 2.0, 0, 0.1))));
        double[] loopC = lb(206.0, -17.0, 380);
        double[][] loopSky = skyBasis(loopC);
        double[] loopDir = DeepMath.normalize(new double[] {loopSky[0][0] + 0.6 * loopSky[1][0], loopSky[0][1] + 0.6 * loopSky[1][1], loopSky[0][2] + 0.6 * loopSky[1][2]});
        out.add(neb("barnards_loop", "Barnard's Loop", DeepSkyType.EMISSION_NEBULA, loopC, 72, o -> List.of())
                .withFields(o -> List.of(NebulaShapes.barnardsLoop(o.seed, new Morphology.Frame(loopC, loopSky[0], loopSky[1], loopSky[2], 55, 55, 55)))));
        // Orion A giant molecular cloud, behind M42 and stretching ~10° south-east (Großschedl et al. 2018).
        double[] orionA = lb(211.5, -19.6, 432);
        out.add(neb("orion_a", "Orion A Molecular Cloud", DeepSkyType.DARK_NEBULA, orionA, 32, o -> List.of())
                .withFields(o -> List.of(new DarkField(o.seed, frameSky(orionA, 22, 5, 7), 6.0, 0.6, 0.05))));
        double[] lamOri = eq(ra(5, 35, 0), dec(9, 56, 0), 400);
        out.add(neb("lambda_ori", "Lambda Orionis Ring", DeepSkyType.EMISSION_NEBULA, lamOri, 44, o -> List.of())
                .withFields(o -> List.of(NebulaShapes.lambdaOrionis(o.seed, frameSky(lamOri, 28, 28, 26)))));
        double[] carina = lb(287.6, -0.6, 2300);
        out.add(neb("ngc3372", "Carina Nebula (NGC 3372)", DeepSkyType.EMISSION_NEBULA, carina, 45, o -> List.of())
                .withFields(o -> List.of(NebulaShapes.carina(o.seed, frameSky(carina, 26, 22, 20)))));
        double[] m8 = lb(6.0, -1.2, 1250);
        out.add(neb("m8", "Lagoon Nebula (M8)", DeepSkyType.EMISSION_NEBULA, m8, 28, o -> List.of())
                .withFields(o -> List.of(NebulaShapes.lagoon(o.seed, frameSky(m8, 11, 6, 7)))));
        double[] m16 = lb(17.0, 0.8, 1740);
        out.add(neb("m16", "Eagle Nebula (M16)", DeepSkyType.EMISSION_NEBULA, m16, 22, o -> {
            List<Blob> b = new ArrayList<>();
            // Pillars of Creation: dust columns pointing at NGC 6611, with ionised tips.
            double[][] sk = skyBasis(m16);
            for (int i = 0; i < 3; i++) {
                double off = -1.2 + i * 1.1;
                double[] base = {m16[0] + sk[1][0] * -3.0 + sk[0][0] * off, m16[1] + sk[1][1] * -3.0 + sk[0][1] * off, m16[2] + sk[1][2] * -3.0 + sk[0][2] * off};
                double len = 1.0 + 0.4 * i;
                b.add(Blob.axial(base, sk[1], len, 0.22, new double[3], 0, Blob.dustFor(9, len, 0.22)));
                double[] tip = {base[0] + sk[1][0] * len * 1.6, base[1] + sk[1][1] * len * 1.6, base[2] + sk[1][2] * len * 1.6};
                b.add(Blob.sphere(tip, 0.25, Morphology.H_ALPHA, 400, 0));
            }
            return b;
        }).withFields(o -> List.of(NebulaShapes.eagle(o.seed, frameRandom(o.id, m16, 8, 7, 7)))));
        double[] rosette = lb(206.3, -2.1, 1600);
        out.add(neb("ngc2237", "Rosette Nebula (NGC 2237)", DeepSkyType.EMISSION_NEBULA, rosette, 24, o -> List.of())
                .withFields(o -> List.of(NebulaShapes.rosette(o.seed, frameRandom(o.id, rosette, 15, 15, 14)))));
        double[] ngc7000 = eq(ra(20, 59, 17), dec(44, 31, 0), 795);
        out.add(neb("ngc7000", "North America Nebula (NGC 7000)", DeepSkyType.EMISSION_NEBULA, ngc7000, 22, o -> List.of())
                .withFields(o -> List.of(NebulaShapes.northAmerica(o.seed, NebulaShapes.equatorial(ngc7000, 10, 10, 7)))));

        // ------------------------------------------------------------ supernova remnants
        double[] crab = lb(184.56, -5.78, 2000);
        out.add(neb("m1", "Crab Nebula (M1)", DeepSkyType.SUPERNOVA_REMNANT, crab, 3, o ->
                Morphology.crab(o.seed, frameRandom(o.id, crab, 1.6, 1.6, 1.6), 6_000, 1_500, 80)));
        double[] vela = lb(263.9, -3.3, 287);
        out.add(neb("vela_snr", "Vela Supernova Remnant", DeepSkyType.SUPERNOVA_REMNANT, vela, 22, o ->
                Morphology.snrShell(o.seed, frameRandom(o.id, vela, 19, 19, 19), 400, 0.5, 260)));
        double[] cygLoop = lb(74.0, -8.5, 735);
        out.add(neb("cygnus_loop", "Cygnus Loop (Veil Nebula)", DeepSkyType.SUPERNOVA_REMNANT, cygLoop, 21, o ->
                Morphology.snrShell(o.seed, frameRandom(o.id, cygLoop, 18.5, 18.5, 18.5), 300, 0.6, 260)));

        // ------------------------------------------------------------ planetary nebulae
        double[] m57 = eq(ra(18, 53, 35.1), dec(33, 1, 45), 787);
        out.add(neb("m57", "Ring Nebula (M57)", DeepSkyType.PLANETARY_NEBULA, m57, 0.6, o ->
                Morphology.planetary(o.seed, Morphology.Frame.axes(m57, sightLine(m57), 0.16, 0.2), 40, 1.5)));
        double[] helix = eq(ra(22, 29, 38.5), dec(-20, 50, 13), 200);
        out.add(neb("ngc7293", "Helix Nebula (NGC 7293)", DeepSkyType.PLANETARY_NEBULA, helix, 1.2, o -> {
            double[] s = sightLine(helix);
            double[] t = DeepMath.perpendicular(s);
            double[] ax = DeepMath.normalize(new double[] {s[0] + 0.4 * t[0], s[1] + 0.4 * t[1], s[2] + 0.4 * t[2]});
            return Morphology.planetary(o.seed, Morphology.Frame.axes(helix, ax, 0.5, 0.4), 15, 0.8);
        }));

        // ------------------------------------------------------------ dark nebulae
        double[] coal = lb(301.0, -1.0, 180);
        out.add(neb("coalsack", "Coalsack Nebula", DeepSkyType.DARK_NEBULA, coal, 12, o -> List.of())
                .withFields(o -> List.of(new DarkField(o.seed, frameSky(coal, 8, 7, 4), 1.2, 1.3, 0.14))));
        double[] b68 = eq(ra(17, 22, 38.2), dec(-23, 49, 34), 125);
        out.add(neb("barnard68", "Barnard 68", DeepSkyType.DARK_NEBULA, b68, 0.1, o ->
                Morphology.globule(frameRandom(o.id, b68, 0.06, 0.06, 0.06), 30)));
        double[] taurus = eq(ra(4, 30, 0), dec(25, 30, 0), 141);
        out.add(neb("taurus_cloud", "Taurus Molecular Cloud", DeepSkyType.DARK_NEBULA, taurus, 20, o -> List.of())
                .withFields(o -> List.of(new DarkField(o.seed, frameSky(taurus, 15, 10, 5), 2.5, 0.3, 0.04))));
        double[] rhoOph = eq(ra(16, 28, 0), dec(-24, 20, 0), 139);
        out.add(neb("rho_oph_cloud", "Rho Ophiuchi Cloud", DeepSkyType.DARK_NEBULA, rhoOph, 8, o -> List.of())
                .withFields(o -> List.of(new DarkField(o.seed, frameRandom(o.id, rhoOph, 6, 4, 4), 3.5, 0.7, 0.06))));
        double[] pipe = eq(ra(17, 25, 0), dec(-26, 0, 0), 130);
        out.add(neb("pipe_nebula", "Pipe Nebula", DeepSkyType.DARK_NEBULA, pipe, 18, o -> List.of())
                .withFields(o -> List.of(new DarkField(o.seed, frameSky(pipe, 3.5, 13, 2.5), 2.5, 0.5, 0.07))));

        // ------------------------------------------------------------ open clusters
        double[] pleiades = lb(166.57, -23.52, 136);
        // Pleiades: the eleven members brighter than V ≈ 5.8 (Hipparcos V), procedural members capped
        // just below the least massive of them (Asterope, ~2.9 M☉).
        out.add(new StarCluster("m45", "Pleiades (M45)", DeepSkyType.OPEN_CLUSTER, pleiades, true, seedOf("m45"),
                0.125, 0.0, 1000, 1.4, 12.0, List.of(
                new StarCluster.Anchor("Alcyone", ra(3, 47, 29.1), dec(24, 6, 18), 2.87, 12_258, StarKind.GIANT, 6.0),
                new StarCluster.Anchor("Atlas", ra(3, 49, 9.7), dec(24, 3, 12), 3.62, 12_000, StarKind.GIANT, 5.0),
                new StarCluster.Anchor("Electra", ra(3, 44, 52.5), dec(24, 6, 48), 3.70, 13_500, StarKind.GIANT, 4.7),
                new StarCluster.Anchor("Maia", ra(3, 45, 49.6), dec(24, 22, 4), 3.86, 13_000, StarKind.GIANT, 4.6),
                new StarCluster.Anchor("Merope", ra(3, 46, 19.6), dec(23, 56, 54), 4.18, 14_000, StarKind.MAIN_SEQUENCE, 4.5),
                new StarCluster.Anchor("Taygeta", ra(3, 45, 12.5), dec(24, 28, 2), 4.30, 13_700, StarKind.MAIN_SEQUENCE, 4.5),
                new StarCluster.Anchor("Pleione", ra(3, 49, 11.2), dec(24, 8, 12), 5.05, 12_000, StarKind.MAIN_SEQUENCE, 3.4),
                new StarCluster.Anchor("Celaeno", ra(3, 44, 48.2), dec(24, 17, 22), 5.45, 13_300, StarKind.MAIN_SEQUENCE, 4.4),
                new StarCluster.Anchor("Asterope", ra(3, 45, 54.5), dec(24, 33, 17), 5.76, 10_000, StarKind.MAIN_SEQUENCE, 2.9),
                new StarCluster.Anchor("HD 23753", ra(3, 48, 20.8), dec(23, 25, 16), 5.45, 12_300, StarKind.MAIN_SEQUENCE, 3.2),
                new StarCluster.Anchor("18 Tauri", ra(3, 45, 9.7), dec(24, 50, 21), 5.66, 12_800, StarKind.MAIN_SEQUENCE, 3.0)),
                2.85, 0.12, null).withFields(o -> List.of(NebulaShapes.pleiadesDust(o.seed, (StarCluster) o, pleiades))));
        double[] hyades = lb(180.06, -22.34, 47.5);
        out.add(new StarCluster("hyades", "Hyades", DeepSkyType.OPEN_CLUSTER, hyades, true, seedOf("hyades"),
                0.625, 0.13, 700, 2.7, 10.0, List.of(
                new StarCluster.Anchor("Gamma Tauri", ra(4, 19, 47.6), dec(15, 37, 39), 3.65, 4_965, StarKind.GIANT, 2.7),
                new StarCluster.Anchor("Delta1 Tauri", ra(4, 22, 56.1), dec(17, 32, 33), 3.76, 4_980, StarKind.GIANT, 2.7),
                new StarCluster.Anchor("Epsilon Tauri", ra(4, 28, 37.0), dec(19, 10, 50), 3.53, 4_900, StarKind.GIANT, 2.7),
                new StarCluster.Anchor("Theta1 Tauri", ra(4, 28, 34.5), dec(15, 57, 44), 3.84, 4_950, StarKind.GIANT, 2.7),
                new StarCluster.Anchor("Theta2 Tauri", ra(4, 28, 39.7), dec(15, 52, 15), 3.40, 8_000, StarKind.GIANT, 2.8)),
                2.2, 0.0, null));
        out.add(new StarCluster("m44", "Praesepe (M44)", DeepSkyType.OPEN_CLUSTER, lb(205.92, 32.48, 186), true, seedOf("m44"),
                0.6, 0.16, 1100, 3.0, 12.0, List.of(), 150, 0.0, null));
        out.add(new StarCluster("ngc869", "h Persei (NGC 869)", DeepSkyType.OPEN_CLUSTER, lb(134.63, -3.74, 2340), true, seedOf("ngc869"),
                0.014, -0.1, 8_000, 1.3, 12.0, List.of(), 150, 0.0, null));
        out.add(new StarCluster("ngc884", "Chi Persei (NGC 884)", DeepSkyType.OPEN_CLUSTER, lb(135.05, -3.58, 2340), true, seedOf("ngc884"),
                0.014, -0.1, 6_000, 1.5, 12.0, List.of(), 150, 0.0, null));
        // Trapezium Cluster: θ¹ Ori A–D and θ² Ori A/B as anchors (SIMBAD V; masses from spectral type,
        // Martins et al. 2005 for O stars, Harmanec 1988 for B stars); procedural members stop just
        // below the least massive anchor (θ¹ Ori B, ~7 M☉) so no bright star appears twice.
        out.add(new StarCluster("onc", "Trapezium Cluster", DeepSkyType.OPEN_CLUSTER, lb(209.01, -19.38, 410), true, seedOf("onc"),
                0.002, -0.05, 2_800, 0.2, 3.5, List.of(
                new StarCluster.Anchor("Theta1 Orionis C", ra(5, 35, 16.46), dec(-5, 23, 22.8), 5.13, 39_000, StarKind.MAIN_SEQUENCE, 33),
                new StarCluster.Anchor("Theta1 Orionis A", ra(5, 35, 15.83), dec(-5, 23, 14.3), 6.73, 30_000, StarKind.MAIN_SEQUENCE, 14),
                new StarCluster.Anchor("Theta1 Orionis B", ra(5, 35, 16.13), dec(-5, 23, 6.8), 7.96, 21_000, StarKind.MAIN_SEQUENCE, 7),
                new StarCluster.Anchor("Theta1 Orionis D", ra(5, 35, 17.26), dec(-5, 23, 16.6), 6.71, 32_000, StarKind.MAIN_SEQUENCE, 17),
                new StarCluster.Anchor("Theta2 Orionis A", ra(5, 35, 22.90), dec(-5, 24, 57.8), 5.02, 34_000, StarKind.MAIN_SEQUENCE, 25),
                new StarCluster.Anchor("Theta2 Orionis B", ra(5, 35, 26.40), dec(-5, 25, 0.8), 6.38, 29_000, StarKind.MAIN_SEQUENCE, 12)),
                6.8, 1.7, null));
        out.add(new StarCluster("ngc2244", "NGC 2244", DeepSkyType.OPEN_CLUSTER, rosette, true, seedOf("ngc2244"),
                0.002, -0.1, 2_000, 1.5, 8.0, List.of(), 150, 0.0, null));
        out.add(new StarCluster("ngc6611", "NGC 6611", DeepSkyType.OPEN_CLUSTER, m16, true, seedOf("ngc6611"),
                0.002, 0.0, 3_000, 1.0, 7.0, List.of(), 150, 0.0, null));
        out.add(new StarCluster("ngc6530", "NGC 6530", DeepSkyType.OPEN_CLUSTER, m8, true, seedOf("ngc6530"),
                0.002, 0.0, 3_000, 1.2, 7.0, List.of(), 150, 0.0, null));
        out.add(new StarCluster("tr16", "Trumpler 16", DeepSkyType.OPEN_CLUSTER, carina, true, seedOf("tr16"),
                0.003, 0.0, 6_000, 1.0, 8.0, List.of(), 150, 0.0, null));
        out.add(new StarCluster("ic2602", "IC 2602 (Southern Pleiades)", DeepSkyType.OPEN_CLUSTER, lb(289.6, -4.9, 151), true, seedOf("ic2602"),
                0.035, 0.0, 450, 1.5, 7.0, List.of(
                new StarCluster.Anchor("Theta Carinae", ra(10, 42, 57.4), dec(-64, 23, 40), 2.74, 30_000, StarKind.MAIN_SEQUENCE, 15)),
                6.0, 0.1, null));

        // ------------------------------------------------------------ globular clusters (Harris 2010)
        //  id        name                    l       b      R_sun(kpc) M_V     r_c(')  c     [Fe/H]
        globular(out, "ngc104", "47 Tucanae (NGC 104)", 305.89, -44.89, 4.5, -9.42, 0.36, 2.07, -0.72);
        globular(out, "ngc5139", "Omega Centauri (NGC 5139)", 309.10, 14.97, 5.2, -10.26, 2.37, 1.31, -1.53);
        globular(out, "m13", "Hercules Cluster (M13)", 59.01, 40.91, 7.1, -8.55, 0.62, 1.53, -1.53);
        globular(out, "m22", "M22 (NGC 6656)", 9.89, -7.55, 3.2, -8.50, 1.33, 1.38, -1.70);
        globular(out, "m4", "M4 (NGC 6121)", 350.97, 15.97, 2.2, -7.19, 1.16, 1.65, -1.16);
        globular(out, "m15", "M15 (NGC 7078)", 65.01, -27.31, 10.4, -9.19, 0.14, 2.29, -2.37);
        globular(out, "m3", "M3 (NGC 5272)", 42.22, 78.71, 10.2, -8.88, 0.37, 1.89, -1.50);
        globular(out, "m5", "M5 (NGC 5904)", 3.86, 46.80, 7.5, -8.81, 0.44, 1.73, -1.29);
        globular(out, "ngc6397", "NGC 6397", 338.17, -11.96, 2.3, -6.64, 0.05, 2.50, -2.02);
        globular(out, "m92", "M92 (NGC 6341)", 68.34, 34.86, 8.3, -8.21, 0.26, 1.68, -2.31);

        // ------------------------------------------------------------ external galaxies
        double[] lmc = eq(ra(5, 23, 34.5), dec(-69, 45, 22), 49_590);
        out.add(neb("lmc", "Large Magellanic Cloud", DeepSkyType.GALAXY, lmc, 9_000, o -> {
            double[] nrm = diskNormal(lmc, 34.7, 122.5);
            double lv = 1.8e9; // M_V = −18.36
            List<Blob> b = new ArrayList<>(Morphology.diskGalaxy(o.seed, lmc, nrm, 1_450, 0.2, lv * 0.81, 0, 0, 90, lv * 0.05, 0, 0));
            // Off-centre stellar bar (van der Marel 2001), along the line of nodes.
            double[][] e = equatorialBasis(lmc);
            double pa = Math.toRadians(115);
            double[] bar = DeepMath.normalize(new double[] {Math.cos(pa) * e[1][0] + Math.sin(pa) * e[0][0], Math.cos(pa) * e[1][1] + Math.sin(pa) * e[0][1], Math.cos(pa) * e[1][2] + Math.sin(pa) * e[0][2]});
            double[] side = DeepMath.cross(nrm, bar);
            double[] barC = {lmc[0] + side[0] * 300, lmc[1] + side[1] * 300, lmc[2] + side[2] * 300};
            b.add(Morphology.stars(barC, bar, 1_100, side, 330, nrm, 300, Morphology.OLD_STARS, lv * 0.14));
            // 30 Doradus (Tarantula): Q ≈ 4 × 10⁵¹ s⁻¹ (Crowther 2019).
            double[] tar = eq(ra(5, 38, 42), dec(-69, 6, 3), 49_590);
            b.addAll(Morphology.hiiRegion(o.seed ^ 30, frameRandom("30dor", tar, 110, 90, 90), 1.4e6, 0.6, 0, 0));
            return b;
        }));
        double[] smc = eq(ra(0, 52, 44.8), dec(-72, 49, 43), 62_440);
        out.add(neb("smc", "Small Magellanic Cloud", DeepSkyType.GALAXY, smc, 7_000, o -> {
            double lv = 4.7e8; // M_V = −16.8
            double[][] e = equatorialBasis(smc);
            double pa = Math.toRadians(45);
            double[] maj = DeepMath.normalize(new double[] {Math.cos(pa) * e[1][0] + Math.sin(pa) * e[0][0], Math.cos(pa) * e[1][1] + Math.sin(pa) * e[0][1], Math.cos(pa) * e[1][2] + Math.sin(pa) * e[0][2]});
            double[] los = e[2];
            double[] min = DeepMath.cross(los, maj);
            List<Blob> b = new ArrayList<>();
            b.add(Morphology.stars(smc, maj, 1_000, min, 520, los, 2_500, Morphology.OLD_STARS, lv * 0.55));
            b.add(Morphology.stars(smc, maj, 500, min, 260, los, 1_200, Morphology.YOUNG_STARS, lv * 0.35));
            SeedRng rng = new SeedRng(o.seed);
            for (int i = 0; i < 30; i++) {
                double[] p = {smc[0] + maj[0] * rng.gaussian() * 900 + min[0] * rng.gaussian() * 400 + los[0] * rng.gaussian() * 1500,
                        smc[1] + maj[1] * rng.gaussian() * 900 + min[1] * rng.gaussian() * 400 + los[1] * rng.gaussian() * 1500,
                        smc[2] + maj[2] * rng.gaussian() * 900 + min[2] * rng.gaussian() * 400 + los[2] * rng.gaussian() * 1500};
                b.add(Blob.sphere(p, rng.range(40, 120), rng.nextDouble() < 0.5 ? Morphology.H_ALPHA : Morphology.YOUNG_STARS, lv * 0.1 / 30 * rng.logUniform(0.3, 3), 0));
            }
            // NGC 346, the SMC's brightest H II region.
            b.addAll(Morphology.hiiRegion(o.seed ^ 346, frameRandom("ngc346", eq(ra(0, 59, 5), dec(-72, 10, 38), 62_440), 60, 50, 50), 1.5e5, 0.5, 0, 0));
            return b;
        }));
        double[] m31 = eq(ra(0, 42, 44.3), dec(41, 16, 9), 765_000);
        out.add(neb("m31", "Andromeda Galaxy (M31)", DeepSkyType.GALAXY, m31, 40_000, o -> {
            double[] nrm = diskNormal(m31, 77, 38);
            double lv = 2.6e10; // V = 3.44, A_V = 0.17 → M_V = −21.1
            List<Blob> b = new ArrayList<>(Morphology.diskGalaxy(o.seed, m31, nrm, 5_300, 0.08, lv * 0.66, 1_000, lv * 0.32, 90, lv * 0.02, 0.7, 10_000));
            // Satellites M32 (M_V −16.4) and M110 (M_V −16.3).
            b.add(Blob.sphere(eq(ra(0, 42, 41.8), dec(40, 51, 55), 765_000), 120, Morphology.BULGE_STARS, 3.0e8, 0));
            b.add(Blob.sphere(eq(ra(0, 40, 22.1), dec(41, 41, 7), 765_000), 650, Morphology.OLD_STARS, 2.8e8, 0));
            return b;
        }));
        double[] m33 = eq(ra(1, 33, 50.9), dec(30, 39, 37), 840_000);
        out.add(neb("m33", "Triangulum Galaxy (M33)", DeepSkyType.GALAXY, m33, 15_000, o -> {
            double[] nrm = diskNormal(m33, 55, 23);
            double lv = 3.0e9; // M_V = −18.9
            return Morphology.diskGalaxy(o.seed, m33, nrm, 1_600, 0.1, lv * 0.9, 0, 0, 120, lv * 0.08, 0, 0);
        }));
        return out;
    }

    /**
     * Adds a Harris (2010) globular cluster: core radius from r_c in arcmin at distance R, tidal
     * radius r_t = r_c·10^c, and star count from the integrated M_V and a 12 Gyr population.
     */
    private static void globular(List<DeepSkyObject> out, String id, String name, double l, double b, double rKpc,
                                 double mv, double rcArcmin, double c, double feh) {
        double d = rKpc * 1000;
        double rc = d * Math.tan(Math.toRadians(rcArcmin / 60.0));
        double rt = rc * Math.pow(10, c);
        double age = feh > -1.0 ? 11.5 : 12.6;
        ClusterPopulation pop = ClusterPopulation.of(age, feh, 150);
        double n = Math.pow(10, -0.4 * (mv - 4.83)) / pop.lumPerStar;
        out.add(new StarCluster(id, name, DeepSkyType.GLOBULAR_CLUSTER, lb(l, b, d), true, seedOf(id),
                age, feh, n, rc, rt, List.of(), 150, 0.0, null));
    }
}
