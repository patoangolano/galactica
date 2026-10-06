package dev.galactica.deepsky;

/**
 * An H II region as a continuous volume around its ionising star (field centre).
 *
 * <p><b>Ionised gas.</b> Density is a core profile n ∝ 1/(1 + (r/r_c)²) times a log-normal
 * turbulent field, n = n̄ · exp(σ·fBm) (supersonic turbulence gives log-normal densities,
 * Vázquez-Semadeni 1994; σ ≈ 1 for H II regions). Recombination emission scales as n², so the
 * densest clumps and the core dominate, as in the Huygens region of M42.
 *
 * <p><b>Ionisation front.</b> Ionising photons run out at the Strömgren surface; the transition
 * from ionised to neutral gas is thin (a few photon mean free paths, ≪ 0.01 pc) compared with the
 * region, so the front is a sharp surface r = R_f(direction). R_f is perturbed by low-order noise
 * (density inhomogeneity on large scales) and dented inward along a ridged pattern: the dents are
 * neutral fingers pointing at the star, the pillars and elephant trunks of shadowed dense gas
 * (Hester et al. 1996, AJ 111, 2349). The front width is never narrower than the level of detail,
 * so it stays anti-aliased.
 *
 * <p><b>Blister.</b> Most visible H II regions are blisters on the face of their parent molecular
 * cloud: M42 is a thin (~0.1–0.3 pc) layer of ionised gas on the concave surface of OMC-1, about
 * 0.3 pc behind θ¹ Ori C, open towards the Sun (Wen &amp; O'Dell 1995, ApJ 438, 784; O'Dell 2001,
 * ARA&amp;A 39, 99). With {@link Spec#blister} the cloud fills local z &gt; z_c(x, y) (local +z points
 * away from the Sun), the ionised layer hugs that surface with scale height {@link Spec#layer},
 * and the cloud behind is opaque dust. A raised fold of the surface seen edge-on is the Orion Bar.
 *
 * <p><b>Lines.</b> Hα+Hβ everywhere; [O III] 500.7 nm from the high-ionisation zone near the star
 * (O'Dell 2001: the inner Huygens region is [O III]-bright), which is why the core of M42 is
 * teal-white in colour images and the outer nebula Hα-red.
 *
 * <p><b>Dust.</b> Neutral gas outside the front (clumps around the region, the cloud behind a
 * blister) and foreground lanes (ridged sheets in front of the region, like the Dark Bay that
 * separates M42 from M43) carry A_V; ionised gas holds a little dust too.
 *
 * <p>The emission is normalised so the whole field emits {@code lineLum} (L☉, display gain
 * included), the same convention as the Gaussian {@link Morphology#hiiRegion}.
 */
public final class HiiField extends NebulaField {
    /** Shape parameters, in local units (the frame's unit sphere). */
    public static final class Spec {
        /** Mean ionisation-front radius. */
        public double front = 1.0;
        /** Relative amplitude of the large-scale front distortion. */
        public double frontRough = 0.3;
        /** Front radius grows by dot(direction, frontBias): a region opening to one side. */
        public double[] frontBias = null;
        /**
         * Fraction of R_f over which the emission fades before the front. 0 keeps a sharp,
         * limb-brightened front; large blister regions seen face-on (M42's Extended Orion Nebula)
         * fade out instead, the front there being far behind the bright layer.
         */
        public double edgeFade = 0.0;
        /** Offset of the density peak from the star (local), e.g. the Huygens region's south-west bias. */
        public double[] coreOffset = null;
        /** Depth of the neutral fingers pushed into the front (fraction of R_f). */
        public double fingers = 0.18;
        /** Core radius and outer logarithmic slope of the ionised density profile n ∝ 1/(1 + (r/core)^slope). */
        public double core = 0.3, slope = 2.0;
        /** Curvature of a blister's bowl: the cloud surface recedes by bowl·(x² + y²). */
        public double bowl = 0.25;
        /** Length (σ) of the Orion-Bar fold along its ridge line. */
        public double barLength = 0.3;
        /** σ of ln n (log-normal turbulence). */
        public double turbulence = 1.0;
        /** Base wavelength of the turbulence. */
        public double turbScale = 0.45;
        /**
         * Fraction of the emission concentrated into wisps (ridged, domain-warped sheets: the
         * filamentary, striated texture of photographs) rather than smooth turbulent clumps.
         */
        public double wisps = 0.0;
        /** [O III] / Hα emissivity ratio at the star. */
        public double oiii = 0.3;
        /** Radius of the [O III] zone. */
        public double oiiiRadius = 0.45;
        /** Peak A_V through a neutral clump around the front. */
        public double neutralAv = 3.0;
        /** Fraction of the front covered by neutral clumps (0–1). */
        public double neutralCover = 0.45;
        /** Blister geometry (see the class comment). */
        public boolean blister = false;
        public double blisterDepth = 0.15, layer = 0.15, wrinkle = 0.08;
        /** A_V through the parent cloud behind a blister (per local unit of depth). */
        public double cloudAv = 30;
        /** Lateral radius and e-folding depth of the parent cloud behind a blister. */
        public double cloudRadius = 1.0, cloudDepth = 0.5;
        /** Orion-Bar-like fold of the blister surface: offset of the ridge line, its direction (local x, y), height and width. */
        public double[] barPoint = null, barDir = null;
        public double barHeight = 0.12, barWidth = 0.05;
        /** Foreground dust lanes: count, peak A_V and depth range in front of the star (local z &lt; 0). */
        public int lanes = 0;
        public double laneAv = 2.0;
        /**
         * Explicit dark lanes (bays), each {x0, y0, z0, x1, y1, z1, radius, A_V}: a tapered tube
         * from the first point (radius × 0.7) to the second (× 1.3) with a noisy edge.
         */
        public double[][] bays = null;
        /** Fraction of the line light that is diffuse (smooth, filling the region) rather than turbulent. */
        public double diffuse = 0.15;
        /** Bounding radius, local units. */
        public double bound = 1.5;
    }

    private final Local frame;
    private final Spec sp;
    private final NebulaNoise noise;
    private final double ox, oy, oz;
    private double norm = 1;
    private final double neutralA, laneA, cloudA;

    /**
     * @param lineLum total line luminosity, L☉ (Hα + Hβ + [O III], display gain included)
     */
    public HiiField(long seed, Morphology.Frame f, Spec spec, double lineLum) {
        super(f.c, f.scale > 0 ? spec.bound * maxAxis(f) : 1);
        this.frame = new Local(f);
        this.sp = spec;
        this.noise = NebulaNoise.shared(seed);
        double[] o = NebulaNoise.offset(seed);
        ox = o[0];
        oy = o[1];
        oz = o[2];
        double unit = frame.scalePc;
        // A_V per pc from peak columns: a clump ~0.25 local thick, a lane ~0.15, the bay its diameter.
        neutralA = spec.neutralAv / (0.25 * unit);
        laneA = spec.laneAv / (0.15 * unit);
        cloudA = spec.cloudAv / unit;
        double raw = integrateLines(0.15 * frame.scalePc);
        norm = raw > 0 ? lineLum / raw : 0;
    }

    /**
     * ∫ (Hα + [O III]) dV over the local bounding ball by stratified Monte Carlo (20³ cells) at a
     * coarse level of detail, before normalisation.
     */
    private double integrateLines(double lodPc) {
        double[] o = new double[3];
        dev.galactica.science.SeedRng rng = new dev.galactica.science.SeedRng(0x1E7E6A1L);
        int m = 20;
        double bound = sp.bound, cell = 2.0 / m, sum = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                for (int l = 0; l < m; l++) {
                    double ux = -1 + (i + rng.nextDouble()) * cell, uy = -1 + (j + rng.nextDouble()) * cell, uz = -1 + (l + rng.nextDouble()) * cell;
                    if (ux * ux + uy * uy + uz * uz > 1) continue;
                    raw(ux * bound, uy * bound, uz * bound, lodPc, o);
                    sum += o[0] * (1 + o[1]);
                }
            }
        }
        // (2·bound)³ local volume over m³ cells, times |det M| = scale³ pc³ per local unit³.
        double vol = Math.pow(2 * bound, 3) * Math.pow(frame.scalePc, 3);
        return sum * vol / ((double) m * m * m);
    }

    private static double maxAxis(Morphology.Frame f) {
        double mx = 0;
        for (int j = 0; j < 3; j++) mx = Math.max(mx, Math.sqrt(f.m[0][j] * f.m[0][j] + f.m[1][j] * f.m[1][j] + f.m[2][j] * f.m[2][j]));
        return mx;
    }

    @Override
    int intervals(double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        return clipToEllipsoid(frame, sp.bound, ox, oy, oz, dx, dy, dz, t);
    }

    @Override
    public double detailPc() {
        return 0.035 * frame.scalePc;
    }

    @Override
    public boolean hasDust() {
        return true;
    }

    @Override
    public void sample(double x, double y, double z, double lodPc, double[] out) {
        double[] p = LOCAL.get();
        frame.toLocal(x, y, z, p);
        double[] q = RAW.get();
        raw(p[0], p[1], p[2], lodPc, q);
        double ha = q[0] * norm, ox3 = q[0] * q[1] * norm;
        out[0] = ha * Morphology.H_ALPHA[0] + ox3 * Morphology.O_III[0];
        out[1] = ha * Morphology.H_ALPHA[1] + ox3 * Morphology.O_III[1];
        out[2] = ha * Morphology.H_ALPHA[2] + ox3 * Morphology.O_III[2];
        out[3] = q[2];
    }

    /** Unnormalised Hα emissivity, [O III]/Hα ratio and dust (A_V pc⁻¹) at local point p. */
    void raw(double px, double py, double pz, double lodPc, double[] out) {
        double r2 = px * px + py * py + pz * pz;
        out[0] = out[1] = out[2] = 0;
        if (r2 > sp.bound * sp.bound) return;
        double r = Math.sqrt(r2);
        double unit = frame.scalePc;
        double lod = lodPc / unit;
        double w = Math.max(0.012, 0.7 * lod);
        double inv = 1 / Math.max(r, 1e-6);
        double dx = px * inv, dy = py * inv, dz = pz * inv;

        // Ionisation front: low-order distortion, then neutral fingers dented into it.
        double rf = sp.front * (1 + sp.frontRough * noise.fbm(dx * 1.6 + ox, dy * 1.6 + oy, dz * 1.6 + oz, 3));
        if (sp.frontBias != null) rf *= Math.max(0.2, 1 + dx * sp.frontBias[0] + dy * sp.frontBias[1] + dz * sp.frontBias[2]);
        if (sp.fingers > 0) {
            double fo = NebulaNoise.octavesFor(0.25 * rf * unit, lodPc / Math.max(r, 0.05), 3);
            double rd = noise.ridged(dx * 4.5 + oy, dy * 4.5 + oz, dz * 4.5 + ox, fo);
            rf *= 1 - sp.fingers * rd * rd * rd;
        }

        // Blister: cloud surface z_c(x, y), the ionised layer in front of it.
        double front = 1, cloud = 0, zc = 0;
        if (sp.blister) {
            zc = sp.blisterDepth + sp.wrinkle * noise.fbm(px * 2.2 + oz, py * 2.2 + ox, 0.3, NebulaNoise.octavesFor(0.45 * unit, lodPc, 4));
            // Curvature of the surface: bowl > 0 recedes from the star, bowl < 0 curves towards the Sun (a cup).
            zc += sp.bowl * (px * px + py * py);
            if (sp.barPoint != null) {
                // The surface rises towards the Sun on the right-hand side of the bar direction:
                // seen edge-on along the ridge, the front is a bright straight bar.
                double bx = px - sp.barPoint[0], by = py - sp.barPoint[1];
                double along = bx * sp.barDir[0] + by * sp.barDir[1];
                double across = -bx * sp.barDir[1] + by * sp.barDir[0];
                double lenFall = Math.exp(-along * along / (sp.barLength * sp.barLength));
                zc -= sp.barHeight * lenFall / (1 + Math.exp(across / Math.max(sp.barWidth, w)));
            }
            front = 1 - smoothstep(zc - w, zc + w, pz);
            cloud = 1 - front;
        }

        double ion = 1 - smoothstep(rf - w, rf + w, r);
        double em = 0, o3 = 0, ionN = 0;
        if (ion * front > 0) {
            double oct = NebulaNoise.octavesFor(sp.turbScale * unit, lodPc, 7);
            double s = sp.turbScale;
            double t = noise.fbm(px / s + ox, py / s + oy, pz / s + oz, oct);
            double rc2 = r2;
            if (sp.coreOffset != null) {
                double cx0 = px - sp.coreOffset[0], cy0 = py - sp.coreOffset[1], cz0 = pz - sp.coreOffset[2];
                rc2 = cx0 * cx0 + cy0 * cy0 + cz0 * cz0;
            }
            double prof = sp.slope == 2 ? rc2 / (sp.core * sp.core) : Math.pow(Math.sqrt(rc2) / sp.core, sp.slope);
            double n = Math.exp(sp.turbulence * t) / (1 + prof);
            if (sp.edgeFade > 0) n *= smoothstep(rf, rf * (1 - sp.edgeFade), r);
            ionN = n * ion * front;
            if (sp.blister) n *= Math.exp(-Math.max(0, zc - pz) / sp.layer);
            em = (n * n + sp.diffuse * n) * ion * front;
            if (sp.wisps > 0 && em > 0) {
                double wo = NebulaNoise.octavesFor(0.6 * s * unit, lodPc, 6);
                double wx = px / s + 0.5 * t, wy = py / s - 0.5 * t, wz = pz / s + 0.3 * t;
                double rd = noise.ridged(wx * 1.7 + oz, wy * 1.7 + ox, wz * 1.7 + oy, wo);
                em *= (1 - sp.wisps) + sp.wisps * 2.6 * rd * rd;
            }
            o3 = sp.oiii * Math.exp(-r2 / (sp.oiiiRadius * sp.oiiiRadius));
        }

        // Dust: neutral clumps outside the front, the cloud behind a blister, foreground lanes.
        double a = 0;
        if (r > rf - 2 * w && r < rf + 0.6) {
            double oct = NebulaNoise.octavesFor(0.4 * unit, lodPc, 6);
            double c = noise.fbm(px * 2.5 + oz, py * 2.5 + ox, pz * 2.5 + oy, oct);
            double clump = smoothstep(0.55 - 1.1 * sp.neutralCover, 0.85 - 1.1 * sp.neutralCover, c);
            a += neutralA * (1 - ion) * clump * Math.exp(-Math.max(0, r - rf) / 0.18);
        }
        if (cloud > 0) {
            // The parent cloud: opaque right behind the surface, thinning with depth and laterally
            // (beyond it the region's own molecular cloud, if any, is a separate dark field).
            double rho = Math.sqrt(px * px + py * py) + 1e-9;
            double edge = 1 + 0.4 * noise.fbm(px / rho * 1.4 + oy, py / rho * 1.4 + oz, 0.5 * pz + ox, 3);
            double lat = (px * px + py * py) / (sp.cloudRadius * sp.cloudRadius * edge * edge);
            a += cloudA * cloud * Math.exp(-lat * lat) * Math.exp(-Math.max(0, pz - zc) / sp.cloudDepth);
        }
        if (sp.lanes > 0 && pz < 0.15) {
            double depth = smoothstep(0.15, -0.1, pz) * Math.exp(-r2 / 1.2);
            if (depth > 0.01) {
                double oct = NebulaNoise.octavesFor(0.5 * unit, lodPc, 6);
                // Sheets stretched along local x: lanes run across the face of the region.
                double rd = noise.ridged(px * 1.1 + oy, py * 2.6 + oz, pz * 2.6 + ox, oct);
                double mask = smoothstep(0.1, 0.6, noise.fbm(px * 1.3 + 7, py * 1.3 + 3, pz * 1.3 + 5, 2) + 0.15 * sp.lanes);
                a += laneA * depth * mask * Math.pow(rd, 6);
            }
        }
        if (sp.bays != null) {
            for (double[] bay : sp.bays) {
                double ex = bay[3] - bay[0], ey = bay[4] - bay[1], ez = bay[5] - bay[2];
                double l2 = ex * ex + ey * ey + ez * ez;
                double u = Math.max(0, Math.min(1, ((px - bay[0]) * ex + (py - bay[1]) * ey + (pz - bay[2]) * ez) / l2));
                double qx = px - bay[0] - u * ex, qy = py - bay[1] - u * ey, qz = pz - bay[2] - u * ez;
                double d2 = qx * qx + qy * qy + qz * qz;
                double rad = bay[6] * (0.7 + 0.6 * u);
                if (d2 < 4 * rad * rad) {
                    double edge = noise.fbm(px * 5 + ox, py * 5 + oy, pz * 5 + oz, NebulaNoise.octavesFor(0.2 * unit, lodPc, 5));
                    // Peak A_V across the tube's diameter.
                    a += bay[7] / (2 * rad * unit) * (1 - smoothstep(0.4 * rad, rad * (1.15 + 0.6 * edge), Math.sqrt(d2)));
                }
            }
        }
        // Dust mixed with the ionised gas, partly destroyed close to the star.
        a += 0.02 * neutralA * Math.min(1, ionN) * (1 - Math.exp(-r2 / 0.05));

        out[0] = em;
        out[1] = o3;
        out[2] = a;
    }

    private static final ThreadLocal<double[]> RAW = ThreadLocal.withInitial(() -> new double[3]);
    private static final ThreadLocal<double[]> LOCAL = ThreadLocal.withInitial(() -> new double[3]);
}
