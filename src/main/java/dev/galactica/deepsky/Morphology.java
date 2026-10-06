package dev.galactica.deepsky;

import dev.galactica.science.SeedRng;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Gaussian volumes of nebulae and galaxies. Every shape is deterministic in its seed
 * and is written in a local frame (unit radius) that is scaled and rotated into place.
 *
 * <p>Colours are linear sRGB with max component 1, so that a blob of luminosity L adds L × colour
 * per channel, the same convention as the diffuse populations of the galaxy baker. Line colours
 * are those of the emission lines as a colour camera records them:
 * <ul>
 *   <li>Hα 656.3 nm with Hβ 486.1 nm at the case-B ratio 1 : 2.86 (Osterbrock &amp; Ferland 2006) —
 *       the pink-red of H II regions;</li>
 *   <li>[O III] 500.7 nm — teal, strong where the ionising stars are hottest and in planetary
 *       nebulae;</li>
 *   <li>[S II] 671.6/673.1 nm — deep red, enhanced in shock-excited supernova filaments;</li>
 *   <li>scattered starlight of reflection nebulae — bluer than the star (Rayleigh-like λ⁻¹·⁵).</li>
 * </ul>
 */
public final class Morphology {
    private Morphology() {}

    public static final double[] H_ALPHA = {1.00, 0.18, 0.30};
    public static final double[] O_III = {0.07, 1.00, 0.85};
    public static final double[] S_II = {1.00, 0.07, 0.10};
    public static final double[] REFLECTION = {0.42, 0.60, 1.00};
    public static final double[] SYNCHROTRON = {0.72, 0.80, 1.00};
    public static final double[] OLD_STARS = {1.00, 0.86, 0.72};
    public static final double[] BULGE_STARS = {1.00, 0.78, 0.58};
    public static final double[] YOUNG_STARS = {0.70, 0.80, 1.00};
    private static final double[] NONE = {0, 0, 0};

    /**
     * Gain applied to emission-line luminosities. Astrophotographs (colour cameras with the IR-cut
     * filter modified for Hα, long exposures) record line emission against the stellar continuum
     * with roughly an order of magnitude more contrast than the dark-adapted eye, which is nearly
     * blind at 656 nm. The sky is rendered with that photographic response; luminosities in this
     * class are physical and multiplied by this factor only for display.
     */
    public static final double LINE_GAIN = 8.0;

    /** A placed, scaled, rotated local frame: world = c + M·local. */
    static final class Frame {
        final double[] c;
        final double[][] m = new double[3][3];
        final double scale;

        Frame(double[] c, double[] u, double[] v, double[] w, double su, double sv, double sw) {
            this.c = c;
            for (int i = 0; i < 3; i++) {
                m[i][0] = u[i] * su;
                m[i][1] = v[i] * sv;
                m[i][2] = w[i] * sw;
            }
            scale = Math.cbrt(su * sv * sw);
        }

        static Frame random(double[] c, SeedRng rng, double sx, double sy, double sz) {
            double[] u = randomUnit(rng);
            double[] v = DeepMath.perpendicular(u);
            double[] w = DeepMath.cross(u, v);
            return new Frame(c, u, v, w, sx, sy, sz);
        }

        static Frame axes(double[] c, double[] w, double sxy, double sz) {
            double[] u = DeepMath.perpendicular(w);
            double[] v = DeepMath.cross(w, u);
            return new Frame(c, u, v, w, sxy, sxy, sz);
        }

        double[] point(double[] l) {
            return new double[] {
                    c[0] + m[0][0] * l[0] + m[0][1] * l[1] + m[0][2] * l[2],
                    c[1] + m[1][0] * l[0] + m[1][1] * l[1] + m[1][2] * l[2],
                    c[2] + m[2][0] * l[0] + m[2][1] * l[1] + m[2][2] * l[2]};
        }

        /** Axisymmetric local Gaussian (σ along local axis a, σ_p across), mapped to the world. */
        Blob blob(double[] lp, double[] a, double sa, double sp, double[] rgb, double lum, double peakAv) {
            double p = sp * sp, k = sa * sa - p;
            double[][] l = new double[3][3];
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) l[i][j] = (i == j ? p : 0) + k * a[i] * a[j];
            double[][] w = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    double s = 0;
                    for (int q = 0; q < 3; q++) for (int r = 0; r < 3; r++) s += m[i][q] * l[q][r] * m[j][r];
                    w[i][j] = s;
                }
            }
            double[] pc = point(lp);
            double s1 = Math.max(sa, sp) * scale, s2 = Math.min(sa, sp) * scale;
            double dust = peakAv > 0 ? Blob.dustFor(peakAv, s1, sa > sp ? s2 : s1) : 0;
            return new Blob(pc[0], pc[1], pc[2], w[0][0], w[0][1], w[0][2], w[1][1], w[1][2], w[2][2],
                    rgb[0] * lum, rgb[1] * lum, rgb[2] * lum, dust, null);
        }
    }

    static double[] randomUnit(SeedRng rng) {
        double z = 2 * rng.nextDouble() - 1, ph = 2 * Math.PI * rng.nextDouble(), s = Math.sqrt(1 - z * z);
        return new double[] {s * Math.cos(ph), s * Math.sin(ph), z};
    }

    static double[] gauss3(SeedRng rng, double s) {
        return new double[] {rng.gaussian() * s, rng.gaussian() * s, rng.gaussian() * s};
    }

    static double[] mix(double[] a, double wa, double[] b, double wb) {
        double[] r = {a[0] * wa + b[0] * wb, a[1] * wa + b[1] * wb, a[2] * wa + b[2] * wb};
        double mx = Math.max(r[0], Math.max(r[1], r[2]));
        return mx > 0 ? new double[] {r[0] / mx, r[1] / mx, r[2] / mx} : NONE;
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    // ------------------------------------------------------------------ H II regions

    /**
     * An H II region: a bright ionised core around the exciting cluster, a clumpy hierarchical
     * body (fractal like real H II regions, Elmegreen 1997), [O III] concentrated towards the core,
     * and dust filaments and lanes from the parent molecular cloud.
     *
     * @param lHa    Hα + Hβ line luminosity, L☉ (≈ 3.6 × 10⁻⁴⁶ Q(H⁰) for case B)
     * @param oiii   [O III] 500.7 / Hα luminosity ratio near the core
     * @param dustAv peak extinction of the dust lanes, mag
     * @param lanes  number of large dust lanes crossing the region
     */
    public static List<Blob> hiiRegion(long seed, Frame f, double lHa, double oiii, double dustAv, int lanes) {
        SeedRng rng = new SeedRng(seed);
        List<Blob> out = new ArrayList<>();
        double coreShare = 0.18;
        lHa *= LINE_GAIN;
        out.add(f.blob(new double[3], new double[] {0, 0, 1}, 0.16, 0.16, mix(H_ALPHA, 1, O_III, 1.4 * oiii),
                lHa * coreShare * (1 + 1.4 * oiii), 0));
        int parents = 7, children = 7;
        double[] w = new double[parents * children];
        double[][] pos = new double[w.length][];
        double[][] axis = new double[w.length][];
        double[] sa = new double[w.length], sp = new double[w.length];
        double wsum = 0;
        for (int p = 0; p < parents; p++) {
            double[] pp = gauss3(rng, 0.45);
            for (int ch = 0; ch < children; ch++) {
                int i = p * children + ch;
                double[] g = gauss3(rng, 0.2);
                pos[i] = new double[] {pp[0] + g[0], pp[1] + g[1], pp[2] + g[2]};
                axis[i] = randomUnit(rng);
                sa[i] = rng.logUniform(0.05, 0.17);
                sp[i] = sa[i] / rng.logUniform(1.0, 3.0);
                double r = len(pos[i]);
                w[i] = sa[i] * sp[i] * rng.range(0.4, 1.6) * Math.exp(-r * r / 0.7);
                wsum += w[i];
            }
        }
        for (int i = 0; i < w.length; i++) {
            double r = len(pos[i]);
            double o = oiii * Math.exp(-r / 0.3);
            double l = lHa * (1 - coreShare) * w[i] / wsum;
            out.add(f.blob(pos[i], axis[i], sa[i], sp[i], mix(H_ALPHA, 1, O_III, o), l * (1 + o), 0));
        }
        // Dust filaments around and inside the region, from the parent cloud.
        int fil = 6;
        for (int k = 0; k < fil; k++) {
            double[] p = randomUnit(rng);
            double r0 = rng.range(0.35, 0.95);
            double[] q = {p[0] * r0, p[1] * r0, p[2] * r0};
            double[] dir = randomUnit(rng);
            for (int s = 0; s < 5; s++) {
                double a = dustAv * rng.range(0.3, 0.9);
                out.add(f.blob(q.clone(), dir, 0.10, 0.025, NONE, 0, a));
                double[] t = randomUnit(rng);
                for (int j = 0; j < 3; j++) dir[j] = dir[j] * 0.8 + t[j] * 0.35;
                dir = DeepMath.normalize(dir);
                for (int j = 0; j < 3; j++) q[j] += dir[j] * 0.14;
            }
        }
        // Large lanes crossing the face of the region (like the Lagoon's or the Keyhole's).
        for (int k = 0; k < lanes; k++) {
            double[] dir = randomUnit(rng);
            double[] off = gauss3(rng, 0.2);
            for (int s = -2; s <= 2; s++) {
                double[] q = {off[0] + dir[0] * s * 0.2, off[1] + dir[1] * s * 0.2, off[2] + dir[2] * s * 0.2};
                out.add(f.blob(q, dir, 0.16, 0.045, NONE, 0, dustAv * rng.range(0.7, 1.2)));
            }
        }
        return out;
    }

    /**
     * Shell or partial shell of ionised gas: limb-brightened rings like the Rosette, Barnard's Loop
     * or the λ Orionis ring. Elements are sheets tangent to the shell.
     *
     * @param arcDir if non-null, only directions within {@code arcCos} of it are populated (an arc)
     */
    public static List<Blob> shell(long seed, Frame f, double thickness, double lHa, double oiii, int n,
                                   double[] arcDir, double arcCos, double dustAv) {
        SeedRng rng = new SeedRng(seed);
        List<Blob> out = new ArrayList<>();
        lHa *= LINE_GAIN;
        double[] col = mix(H_ALPHA, 1, O_III, oiii);
        int placed = 0, guard = 0;
        while (placed < n && guard++ < n * 50) {
            double[] d = randomUnit(rng);
            if (arcDir != null && DeepMath.dot(d, arcDir) < arcCos) continue;
            double r = 1 + thickness * rng.gaussian();
            double[] p = {d[0] * r, d[1] * r, d[2] * r};
            out.add(f.blob(p, d, thickness, rng.range(0.10, 0.22), col, lHa / n * rng.range(0.5, 1.5) * (1 + oiii), 0));
            if (dustAv > 0 && rng.nextDouble() < 0.6) {
                double ro = 1 + 2.5 * thickness;
                out.add(f.blob(new double[] {d[0] * ro, d[1] * ro, d[2] * ro}, d, thickness * 0.8, rng.range(0.18, 0.3), NONE, 0,
                        dustAv * rng.range(0.12, 0.3)));
            }
            placed++;
        }
        return out;
    }

    // ------------------------------------------------------------------ supernova remnants

    /**
     * Filamentary shock shell of an evolved supernova remnant (Cygnus Loop, Vela): thin sheets
     * wrinkled around the blast wave seen edge-on as filaments, with Hα+[S II] radiative shocks and
     * [O III]-bright faster shocks.
     */
    public static List<Blob> snrShell(long seed, Frame f, double lum, double oiii, int n) {
        SeedRng rng = new SeedRng(seed);
        lum *= LINE_GAIN;
        List<Blob> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] d = randomUnit(rng);
            double r = 1 + 0.05 * rng.gaussian();
            double[] p = {d[0] * r, d[1] * r, d[2] * r};
            // A filament is a sheet seen edge-on: thin along the normal, long in one tangent direction.
            double[] t = DeepMath.normalize(DeepMath.cross(d, randomUnit(rng)));
            boolean o3 = rng.nextDouble() < oiii;
            double[] col = o3 ? mix(O_III, 1, H_ALPHA, 0.25) : mix(H_ALPHA, 1, S_II, 0.6);
            double sa = rng.logUniform(0.05, 0.16), sp = rng.range(0.006, 0.014);
            Blob a = f.blob(p, t, sa, sp, col, lum / n * rng.range(0.4, 1.6), 0);
            out.add(a);
        }
        // Faint diffuse interior emission.
        out.add(f.blob(new double[3], new double[] {0, 0, 1}, 0.45, 0.45, mix(H_ALPHA, 1, O_III, oiii), lum * 0.08, 0));
        return out;
    }

    /**
     * Crab-like pulsar wind nebula: an ellipsoidal synchrotron continuum (bluish white) inside a
     * cage of Hα/[S II]/[O III] filaments (Hester 2008, ARA&amp;A 46, 127).
     */
    public static List<Blob> crab(long seed, Frame f, double lumSynch, double lumLines, int n) {
        SeedRng rng = new SeedRng(seed);
        lumLines *= LINE_GAIN;
        List<Blob> out = new ArrayList<>();
        out.add(f.blob(new double[3], new double[] {1, 0, 0}, 0.45, 0.33, SYNCHROTRON, lumSynch * 0.7, 0));
        out.add(f.blob(new double[3], new double[] {1, 0, 0}, 0.22, 0.16, SYNCHROTRON, lumSynch * 0.3, 0));
        for (int i = 0; i < n; i++) {
            double[] d = randomUnit(rng);
            double r = rng.range(0.55, 0.95);
            double[] p = {d[0] * r * 1.3, d[1] * r, d[2] * r};
            double[] t = DeepMath.normalize(DeepMath.cross(d, randomUnit(rng)));
            double[] col = rng.nextDouble() < 0.35 ? mix(O_III, 1, H_ALPHA, 0.4) : mix(H_ALPHA, 1, S_II, 0.8);
            out.add(f.blob(p, t, rng.range(0.08, 0.22), 0.02, col, lumLines / n * rng.range(0.4, 1.6), 0));
        }
        return out;
    }

    // ------------------------------------------------------------------ planetary nebulae

    /**
     * Planetary nebula seen along {@code axis}: an [O III] inner cavity, an Hα/[N II] ring
     * (a thick torus, as in the Ring and Helix nebulae, O'Dell et al. 2004/2013), and a faint halo.
     */
    public static List<Blob> planetary(long seed, Frame f, double lum, double oiii) {
        SeedRng rng = new SeedRng(seed);
        lum *= LINE_GAIN;
        List<Blob> out = new ArrayList<>();
        out.add(f.blob(new double[3], new double[] {0, 0, 1}, 0.55, 0.42, O_III, lum * oiii / (1 + oiii) * 0.8, 0));
        int n = 48;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * (i + rng.nextDouble() * 0.6) / n;
            double r = 0.82 + 0.06 * rng.gaussian();
            double[] p = {r * Math.cos(a), r * Math.sin(a), 0.12 * rng.gaussian()};
            double[] t = {-Math.sin(a), Math.cos(a), 0};
            out.add(f.blob(p, t, 0.22, 0.12, mix(H_ALPHA, 1, S_II, 0.5),
                    lum / (1 + oiii) * 0.8 / n * rng.range(0.6, 1.4), 0));
        }
        out.add(f.blob(new double[3], new double[] {0, 0, 1}, 1.6, 1.6, mix(H_ALPHA, 1, O_III, 0.3), lum * 0.2, 0));
        return out;
    }

    // ------------------------------------------------------------------ dark clouds

    /**
     * Dark molecular cloud: filaments (random walks of elongated dust Gaussians, the dominant
     * structure of molecular clouds, André et al. 2014) inside a diffuse envelope.
     *
     * @param peakAv peak visual extinction through a filament, mag
     */
    public static List<Blob> darkCloud(long seed, Frame f, double peakAv, int filaments, double envelope) {
        SeedRng rng = new SeedRng(seed);
        List<Blob> out = new ArrayList<>();
        if (envelope > 0) {
            out.add(f.blob(new double[3], new double[] {0, 0, 1}, 0.5, 0.5, NONE, 0, peakAv * envelope));
            out.add(f.blob(gauss3(rng, 0.12), randomUnit(rng), 0.3, 0.22, NONE, 0, peakAv * envelope));
        }
        // Hierarchical clumps (Larson 1981; Elmegreen & Falgarone 1996) strung along a shared axis
        // per filament: soft-edged streaks rather than hard silhouettes.
        for (int k = 0; k < filaments; k++) {
            double[] q = gauss3(rng, 0.28);
            double[] dir = randomUnit(rng);
            double len = rng.range(0.25, 0.6);
            int clumps = 6 + rng.nextInt(5);
            for (int c = 0; c < clumps; c++) {
                double along = (rng.nextDouble() - 0.5) * len;
                double[] g = gauss3(rng, 0.04);
                double[] p = {q[0] + dir[0] * along + g[0], q[1] + dir[1] * along + g[1], q[2] + dir[2] * along + g[2]};
                double sa = rng.logUniform(0.07, 0.16);
                out.add(f.blob(p, dir, sa, sa / rng.range(1.2, 2.2), NONE, 0, peakAv * rng.range(0.2, 0.55)));
            }
        }
        return out;
    }

    /** A compact dark globule (Bok globule): one nearly Gaussian core (Alves, Lada &amp; Lada 2001). */
    public static List<Blob> globule(Frame f, double peakAv) {
        return List.of(f.blob(new double[3], new double[] {0, 0, 1}, 0.4, 0.4, NONE, 0, peakAv));
    }

    /**
     * Reflection nebulosity: dust lit by nearby stars, here as parallel striations (the Pleiades'
     * interstellar cloud is streaming through the cluster, Gibson &amp; Nordsieck 2003).
     */
    public static List<Blob> reflection(long seed, Frame f, double lum, int n) {
        SeedRng rng = new SeedRng(seed);
        List<Blob> out = new ArrayList<>();
        double[] stream = {1, 0.15, 0.1};
        stream = DeepMath.normalize(stream);
        for (int i = 0; i < n; i++) {
            double[] p = gauss3(rng, 0.45);
            double[] a = DeepMath.normalize(new double[] {stream[0] + 0.15 * rng.gaussian(), stream[1] + 0.15 * rng.gaussian(), stream[2] + 0.15 * rng.gaussian()});
            double r = len(p);
            out.add(f.blob(p, a, rng.range(0.2, 0.4), rng.range(0.06, 0.12), REFLECTION,
                    lum / n * rng.range(0.4, 1.6) * Math.exp(-r * r), 0.0));
        }
        return out;
    }

    // ------------------------------------------------------------------ galaxies

    /** Exponential surface profile as a 2D multi-Gaussian expansion, in units of the scale length. */
    private static final Mge EXP_DISK = Mge.fit(r -> Math.exp(-r), 0.05, 6.0, 6, 2);
    /** de Vaucouleurs r^{1/4} profile in units of the effective radius (Sérsic b₄ = 7.669). */
    private static final Mge DE_VAUCOULEURS = Mge.fit(r -> Math.exp(-7.669 * (Math.pow(r, 0.25) - 1)), 0.01, 5.0, 7, 2);

    /**
     * Disk galaxy: exponential disk (scale length h, thickness ratio q), optional bulge, star-forming
     * knots (blue clusters and pink H II regions) and a dust ring/lanes in the disk plane.
     *
     * @param normal disk normal (galactocentric, unit)
     */
    public static List<Blob> diskGalaxy(long seed, double[] c, double[] normal, double h, double q, double lDisk,
                                        double reBulge, double lBulge, int knots, double lKnots, double dustAv, double dustRingR) {
        SeedRng rng = new SeedRng(seed);
        List<Blob> out = new ArrayList<>();
        Frame f = Frame.axes(c, normal, 1, 1);
        double[] z = {0, 0, 1};
        for (int k = 0; k < EXP_DISK.sigma.length; k++) {
            double s = EXP_DISK.sigma[k] * h;
            out.add(f.blob(new double[3], z, Math.max(q * h, 0.05 * s), s, OLD_STARS, lDisk * EXP_DISK.weight[k], 0));
        }
        if (lBulge > 0) {
            for (int k = 0; k < DE_VAUCOULEURS.sigma.length; k++) {
                double s = DE_VAUCOULEURS.sigma[k] * reBulge;
                out.add(f.blob(new double[3], z, s * 0.7, s, BULGE_STARS, lBulge * DE_VAUCOULEURS.weight[k], 0));
            }
        }
        for (int i = 0; i < knots; i++) {
            // Radius from the exponential disk (Gamma(2) sampling), uniform azimuth.
            double r = -h * Math.log(rng.nextDoubleOpen() * rng.nextDoubleOpen());
            double a = 2 * Math.PI * rng.nextDouble();
            double[] p = {r * Math.cos(a), r * Math.sin(a), 0.03 * h * rng.gaussian()};
            boolean hii = rng.nextDouble() < 0.5;
            double s = h * rng.logUniform(0.02, 0.07);
            out.add(f.blob(p, z, s * 0.4, s, hii ? H_ALPHA : YOUNG_STARS, lKnots / knots * rng.logUniform(0.3, 3) * (hii ? LINE_GAIN : 1), 0));
        }
        if (dustAv > 0) {
            int n = 70;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * rng.nextDouble();
                double r = dustRingR * (1 + 0.25 * rng.gaussian());
                double[] p = {r * Math.cos(a), r * Math.sin(a), 0.01 * h * rng.gaussian()};
                double[] t = {-Math.sin(a), Math.cos(a), 0};
                double sa = h * rng.range(0.15, 0.35), sp = h * 0.05;
                double[][] mm = f.m;
                // Lane along the ring tangent, flat in the disk plane.
                double[] tw = {mm[0][0] * t[0] + mm[0][1] * t[1], mm[1][0] * t[0] + mm[1][1] * t[1], mm[2][0] * t[0] + mm[2][1] * t[1]};
                double[] pw = f.point(p);
                double[] nrm = normal;
                double[] side = DeepMath.cross(nrm, tw);
                out.add(Blob.triaxial(pw, tw, sa, side, sp, nrm, 0.02 * h, NONE, 0, Blob.dustFor(dustAv * rng.range(0.5, 1.3), sa, sp)));
            }
        }
        return out;
    }

    /** Irregular galaxy as one or more triaxial stellar bodies plus knots (Magellanic Clouds). */
    public static Blob stars(double[] c, double[] u, double su, double[] v, double sv, double[] w, double sw, double[] rgb, double lum) {
        return Blob.triaxial(c, u, su, v, sv, w, sw, rgb, lum, 0);
    }
}
