package dev.galactica.deepsky;

/**
 * A large ionised shell or ring: Barnard's Loop, the λ Orionis ring, the Rosette. These are the
 * walls of bubbles blown by winds and supernovae of an OB association, ionised from inside
 * (Ochsendorf et al. 2015, ApJ 808, 111 for Orion's Barnard's Loop and λ Ori).
 *
 * <p>The emission is a thick shell whose brightness is modulated on large scales (the wall is
 * thicker and denser on one side, partial arcs) and broken into filaments by ridged noise
 * (compressed sheets seen edge-on), plus an optional diffuse interior (λ Ori's ring is an H II
 * region filled with ionised gas, Dolan &amp; Mathieu 2002). Outside the ionised wall an optional
 * ring of neutral clumps carries dust: the dark clouds B30, B35 and the molecular ring around
 * λ Ori (Lang et al. 2000), the globules around the Rosette's cavity.
 *
 * <p>Seen from far away the limb-brightened wall reads as a ring; from inside, as patchy glowing
 * sheets all around. Normalised to {@code lineLum} like {@link HiiField}.
 */
public final class ShellField extends NebulaField {
    public static final class Spec {
        /** Wall thickness (σ, local units, shell radius 1). */
        public double thickness = 0.08;
        /** Relative brightness of the diffuse interior. */
        public double fill = 0.0;
        /** Large-scale patchiness of the wall (0 uniform – 1 broken into patches). */
        public double patchiness = 0.6;
        /** Fraction of the wall's light in ridged filaments. */
        public double filaments = 0.5;
        /** Only directions within acos(arcCos) of arcDir (local) are lit, or all when null. */
        public double[] arcDir = null;
        public double arcCos = -1;
        /** [O III]/Hα ratio. */
        public double oiii = 0.0;
        /** Peak A_V of the neutral clumps outside the wall, 0 for none; their radius range. */
        public double dustAv = 0.0;
        public double dustInner = 1.08, dustOuter = 1.45;
        /** Fraction of the outer ring covered by clumps. */
        public double dustCover = 0.35;
        /**
         * Half-thickness (local z) of the dust when it is a ring rather than a shell, 0 for a
         * spherical shell. λ Ori's molecular ring is a torus seen nearly face-on.
         */
        public double dustRing = 0.0;
        public double bound = 1.5;
    }

    private final Local frame;
    private final Spec sp;
    private final NebulaNoise noise;
    private final double ox, oy, oz;
    private final double dustA;
    private double norm = 1;

    public ShellField(long seed, Morphology.Frame f, Spec spec, double lineLum) {
        super(f.c, spec.bound * new Local(f).maxAxisPc);
        this.frame = new Local(f);
        this.sp = spec;
        this.noise = NebulaNoise.shared(seed);
        double[] o = NebulaNoise.offset(seed);
        ox = o[0];
        oy = o[1];
        oz = o[2];
        dustA = spec.dustAv / (0.12 * frame.scalePc);
        double raw = integrate();
        norm = raw > 0 ? lineLum / raw : 0;
    }

    /**
     * Only the wall (and the dust ring) can be non-zero when the shell has no diffuse interior:
     * the chord is cut to the parts outside the inner ellipsoid and inside the outer one.
     */
    @Override
    int intervals(double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        double reach = 0.08 + 4 * sp.thickness;
        double rOut = Math.min(sp.bound, Math.max(1 + reach, sp.dustAv > 0 ? sp.dustOuter + 0.1 : 0));
        double rIn = sp.fill > 0 ? 0 : Math.max(0, Math.min(1 - reach, sp.dustAv > 0 ? sp.dustInner - 0.1 : 1));
        double c0 = t[0], c1 = t[1];
        double[] h = HIT.get();
        if (!frame.hit(ox, oy, oz, dx, dy, dz, rOut, h)) return 0;
        double a0 = Math.max(c0, h[0]), a1 = Math.min(c1, h[1]);
        if (a1 <= a0) return 0;
        if (rIn <= 0 || !frame.hit(ox, oy, oz, dx, dy, dz, rIn, h) || h[1] <= a0 || h[0] >= a1) {
            t[0] = a0;
            t[1] = a1;
            return 1;
        }
        int m = 0;
        if (h[0] > a0) {
            t[2 * m] = a0;
            t[2 * m + 1] = h[0];
            m++;
        }
        if (h[1] < a1) {
            t[2 * m] = Math.max(a0, h[1]);
            t[2 * m + 1] = a1;
            m++;
        }
        return m;
    }

    private static final ThreadLocal<double[]> HIT = ThreadLocal.withInitial(() -> new double[2]);

    @Override
    public double detailPc() {
        return Math.max(0.6 * sp.thickness, 0.02) * frame.scalePc;
    }

    @Override
    public boolean hasDust() {
        return sp.dustAv > 0;
    }

    private double integrate() {
        double[] q = new double[3];
        dev.galactica.science.SeedRng rng = new dev.galactica.science.SeedRng(0x5E11L);
        int m = 22;
        double b = sp.bound, cell = 2.0 / m, sum = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                for (int l = 0; l < m; l++) {
                    double ux = -1 + (i + rng.nextDouble()) * cell, uy = -1 + (j + rng.nextDouble()) * cell, uz = -1 + (l + rng.nextDouble()) * cell;
                    if (ux * ux + uy * uy + uz * uz > 1) continue;
                    raw(ux * b, uy * b, uz * b, 0.1 * frame.scalePc, q);
                    sum += q[0];
                }
            }
        }
        return sum * Math.pow(2 * b * frame.scalePc, 3) / ((double) m * m * m);
    }

    @Override
    public void sample(double x, double y, double z, double lodPc, double[] out) {
        double[] p = LOCAL.get();
        frame.toLocal(x, y, z, p);
        double[] q = RAW.get();
        raw(p[0], p[1], p[2], lodPc, q);
        double l = q[0] * norm, o = sp.oiii;
        double[] h = Morphology.H_ALPHA, t = Morphology.O_III;
        out[0] = l * (h[0] + o * t[0]) / (1 + o);
        out[1] = l * (h[1] + o * t[1]) / (1 + o);
        out[2] = l * (h[2] + o * t[2]) / (1 + o);
        out[3] = q[1];
    }

    /** Unnormalised emissivity and dust (A_V pc⁻¹) at local p. */
    private void raw(double px, double py, double pz, double lodPc, double[] out) {
        out[0] = out[1] = 0;
        double r2 = px * px + py * py + pz * pz;
        if (r2 > sp.bound * sp.bound) return;
        double r = Math.sqrt(r2);
        double unit = frame.scalePc;
        double lod = lodPc / unit;
        double inv = 1 / Math.max(r, 1e-6);
        double dx = px * inv, dy = py * inv, dz = pz * inv;
        double arc = 1;
        if (sp.arcDir != null) {
            double c = dx * sp.arcDir[0] + dy * sp.arcDir[1] + dz * sp.arcDir[2];
            arc = smoothstep(sp.arcCos - 0.12, sp.arcCos + 0.08, c);
            if (arc <= 0) return;
        }
        // The wall's radius wanders by a few per cent, its thickness never below the level of detail.
        double rw = 1 + 0.06 * noise.fbm(dx * 2 + ox, dy * 2 + oy, dz * 2 + oz, 2);
        double th = Math.max(sp.thickness, 0.6 * lod);
        double u = (r - rw) / th;
        double em = 0;
        if (u > -4 && u < 4) {
            double wall = Math.exp(-0.5 * u * u) * sp.thickness / th;
            double patch = smoothstep(-0.6, 0.6, noise.fbm(dx * 2.6 + oy, dy * 2.6 + oz, dz * 2.6 + ox, 3) + 0.6 - sp.patchiness);
            double fo = NebulaNoise.octavesFor(0.3 * unit, lodPc, 6);
            double fil = noise.ridged(px * 3.3 + oz, py * 3.3 + ox, pz * 3.3 + oy, fo);
            em = wall * patch * ((1 - sp.filaments) + sp.filaments * 2.5 * fil * fil * fil);
        }
        if (sp.fill > 0 && r < rw) {
            double fo = NebulaNoise.octavesFor(0.5 * unit, lodPc, 5);
            double n = noise.fbm(px * 1.8 + ox, py * 1.8 + oy, pz * 1.8 + oz, fo);
            em += sp.fill * Math.exp(1.1 * n) * smoothstep(rw, rw - 0.25, r) * (0.4 + 0.6 * r2);
        }
        out[0] = em * arc;
        if (sp.dustAv > 0 && r > sp.dustInner - 0.1 && r < sp.dustOuter + 0.1) {
            double band = smoothstep(sp.dustInner - 0.1, sp.dustInner + 0.05, r) * smoothstep(sp.dustOuter + 0.1, sp.dustOuter - 0.1, r);
            if (sp.dustRing > 0) band *= Math.exp(-0.5 * pz * pz / (sp.dustRing * sp.dustRing));
            if (band > 0) {
                double fo = NebulaNoise.octavesFor(0.35 * unit, lodPc, 6);
                double c = noise.fbm(px * 2.2 + oz + 9, py * 2.2 + ox + 9, pz * 2.2 + oy + 9, fo);
                double clump = smoothstep(0.6 - 1.2 * sp.dustCover, 0.95 - 1.2 * sp.dustCover, c);
                double fil = noise.ridged(px * 4 + 3, py * 4 + 5, pz * 4 + 7, Math.min(fo, 4));
                out[1] = dustA * band * clump * (0.35 + 0.65 * fil);
            }
        }
    }

    private static final ThreadLocal<double[]> RAW = ThreadLocal.withInitial(() -> new double[2]);
    private static final ThreadLocal<double[]> LOCAL = ThreadLocal.withInitial(() -> new double[3]);
}
