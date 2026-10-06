package dev.galactica.deepsky;

/**
 * A nebula as a continuous volume: emissivity and dust density defined at every point by
 * procedural structure (multi-scale noise, ionisation fronts, filaments, scattered starlight),
 * ray-marched inside its bounding sphere instead of summed from Gaussians ({@link Blob}).
 *
 * <pre>  I_c = ∫ j_c(s) e^{−τ_c(s)} ds / 4π,   τ_c(s) = 0.921 · k_c ∫ a_V(s') ds' </pre>
 *
 * <p>Units match the galaxy baker: {@link #sample} gives the emission j per linear-sRGB channel in
 * L☉ pc⁻³ (a volume emitting L☉ in total integrates to L☉, display gains such as
 * {@link Morphology#LINE_GAIN} included) and the dust as V-band extinction per parsec a_V
 * (mag pc⁻¹); k_c is the Cardelli, Clayton &amp; Mathis (1989) ratio A_λ/A_V for R_V = 3.1 at the
 * channel centres, the same {0.80, 1.00, 1.30} as {@code GalaxySkyBaker}.
 *
 * <p><b>Level of detail.</b> {@link #sample} takes the smallest length worth resolving (pc), from
 * the pixel footprint at that distance and the march step; the noise drops the octaves below it
 * (with a smooth fade and their mean put back, {@link NebulaNoise}), and fronts widen to it, so a
 * field is band-limited to the image and seen without aliasing from any distance.
 *
 * <p>The march ({@link #march}) is adaptive: the step follows the field's own finest scale and the
 * pixel footprint, between {@value #MIN_STEPS} and {@value #MAX_STEPS} samples per crossing, each
 * ray offset by a per-pixel jitter (stratified sampling) so any residual undersampling is fine grain
 * instead of banding. The samples are folded into at most {@value #SEGMENTS} distance-ordered
 * segments, which merge with the Gaussian volumes and the galaxy march exactly like a Gaussian event.
 */
public abstract class NebulaField {
    /** Bounding sphere, galactocentric pc: nothing is emitted or absorbed outside it. */
    public final double cx, cy, cz, radius;

    static final int MIN_STEPS = 6, MAX_STEPS = 56, SEGMENTS = 6;
    /** A_λ/A_V at the R, G, B channel centres, CCM89 with R_V = 3.1 (as {@code GalaxySkyBaker}). */
    static final double KR = 0.80, KG = 1.00, KB = 1.30;
    static final double MAG_TO_TAU = 0.4 * Math.log(10);
    private static final double INV_4PI = 1.0 / (4 * Math.PI);

    protected NebulaField(double[] centre, double radius) {
        this.cx = centre[0];
        this.cy = centre[1];
        this.cz = centre[2];
        this.radius = radius;
    }

    /** Finest structural length worth stepping through, pc (a front's width, a filament's thickness). */
    public abstract double detailPc();

    /**
     * Emission {@code out[0..2]} (L☉ pc⁻³ per channel) and dust {@code out[3]} (A_V per pc) at a
     * galactocentric point, resolving structure down to {@code lodPc}.
     */
    public abstract void sample(double x, double y, double z, double lodPc, double[] out);

    /** True when the field absorbs anywhere (it then dims the stars behind it). */
    public abstract boolean hasDust();

    /** True when the field emits anywhere. */
    public boolean emits() {
        return true;
    }

    /** Dust alone (A_V per pc). Subclasses with a cheaper dust-only path override it. */
    public double dust(double x, double y, double z, double lodPc) {
        double[] o = TMP.get();
        sample(x, y, z, lodPc, o);
        return o[3];
    }

    /**
     * The field as seen from an observer. Scattered light depends on the angle between the star,
     * the dust and the eye ({@link ReflectionField}); emission-line and dust fields do not.
     */
    public NebulaField bind(double ox, double oy, double oz) {
        return this;
    }

    private static final ThreadLocal<double[]> TMP = ThreadLocal.withInitial(() -> new double[4]);

    // ------------------------------------------------------------------ ray integration

    /** Distances {t0, t1} where the ray from o along unit d is inside the bounding sphere, or null. */
    final boolean chord(double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        double vx = cx - ox, vy = cy - oy, vz = cz - oz;
        double tc = vx * dx + vy * dy + vz * dz;
        double d2 = vx * vx + vy * vy + vz * vz - tc * tc;
        double r2 = radius * radius;
        if (d2 >= r2) return false;
        double h = Math.sqrt(r2 - d2);
        double t1 = tc + h;
        if (t1 <= 0) return false;
        t[0] = Math.max(0, tc - h);
        t[1] = t1;
        return true;
    }

    /**
     * Marches the ray through the field and appends up to {@value #SEGMENTS} events to
     * {@code ev}. Each event carries the light emitted in its segment, already dimmed by the
     * segment's own dust in front of each sample, divided by the half-segment transmittance the
     * compositor multiplies back in ({@code GalaxySkyBaker}'s midpoint convention), plus the
     * segment's extinction; the composite is then exact for in-segment ordering.
     *
     * @param pixelAngle angular size of one output pixel (rad), sets the level of detail
     * @param jitter     per-ray offset in [0, 1) of the samples within their steps
     * @param scale      emission weight (procedural H II cross-fade), dust is never scaled
     */
    final void march(double ox, double oy, double oz, double dx, double dy, double dz, double pixelAngle,
                     double jitter, double scale, DeepSkyView.RayEvents ev) {
        double[] t = CHORD.get();
        if (!chord(ox, oy, oz, dx, dy, dz, t)) return;
        int m = intervals(ox, oy, oz, dx, dy, dz, t);
        for (int i = 0; i < m; i++) {
            double t0 = t[2 * i], t1 = t[2 * i + 1];
            if (t1 > t0) marchInterval(ox, oy, oz, dx, dy, dz, t0, t1, pixelAngle, jitter, scale, ev);
        }
    }

    /**
     * The parts of the bounding chord {@code t[0]..t[1]} where the field can be non-zero, written
     * back into {@code t} as up to {@value #MAX_INTERVALS} pairs (t[2i], t[2i+1]); returns their
     * count. The default is the whole chord; thin shells skip their empty interior.
     */
    int intervals(double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        return 1;
    }

    static final int MAX_INTERVALS = 3;

    /** Cuts the chord in {@code t} to the local ball of radius {@code r} (an ellipsoid); returns 0 or 1. */
    static int clipToEllipsoid(Local frame, double r, double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        double[] h = HIT.get();
        if (!frame.hit(ox, oy, oz, dx, dy, dz, r, h)) return 0;
        double a0 = Math.max(t[0], h[0]), a1 = Math.min(t[1], h[1]);
        if (a1 <= a0) return 0;
        t[0] = a0;
        t[1] = a1;
        return 1;
    }

    private static final ThreadLocal<double[]> HIT = ThreadLocal.withInitial(() -> new double[2]);

    private void marchInterval(double ox, double oy, double oz, double dx, double dy, double dz, double t0, double t1,
                               double pixelAngle, double jitter, double scale, DeepSkyView.RayEvents ev) {
        double len = t1 - t0;
        double tMid = 0.5 * (t0 + t1);
        double target = Math.max(0.6 * detailPc(), 0.5 * pixelAngle * tMid);
        int n = (int) Math.ceil(len / target);
        n = Math.max(MIN_STEPS, Math.min(MAX_STEPS, n));
        double ds = len / n;
        int segs = Math.min(SEGMENTS, n);
        double[] s = MARCH_TMP.get();
        boolean emit = scale > 0 && emits();
        double k = ds * INV_4PI * scale;
        int k0 = 0;
        for (int seg = 0; seg < segs; seg++) {
            int k1 = (int) Math.round((double) n * (seg + 1) / segs);
            double r = 0, g = 0, b = 0, av = 0;
            double tr = 1, tg = 1, tb = 1;
            for (int i = k0; i < k1; i++) {
                double tt = t0 + (i + jitter) * ds;
                double lod = Math.max(0.5 * pixelAngle * tt, 0.4 * ds);
                sample(ox + dx * tt, oy + dy * tt, oz + dz * tt, lod, s);
                double a = s[3] * ds;
                if (a > 0) {
                    double tau = a * MAG_TO_TAU;
                    double hr = Math.exp(-0.5 * tau * KR), hg = Math.exp(-0.5 * tau * KG), hb = Math.exp(-0.5 * tau * KB);
                    if (emit) {
                        r += s[0] * k * tr * hr;
                        g += s[1] * k * tg * hg;
                        b += s[2] * k * tb * hb;
                    }
                    tr *= hr * hr;
                    tg *= hg * hg;
                    tb *= hb * hb;
                    av += a;
                } else if (emit) {
                    r += s[0] * k * tr;
                    g += s[1] * k * tg;
                    b += s[2] * k * tb;
                }
            }
            if (r > 0 || g > 0 || b > 0 || av > 0) {
                // Undo the half-segment transmittance the compositor applies to an event's own light.
                double tau = Math.min(400, av * MAG_TO_TAU);
                double ur = Math.exp(0.5 * tau * KR), ug = Math.exp(0.5 * tau * KG), ub = Math.exp(0.5 * tau * KB);
                double tSeg = t0 + 0.5 * (k0 + k1) * ds;
                ev.add(tSeg, r * ur, g * ug, b * ub, av);
            }
            k0 = k1;
        }
    }

    /** V extinction (mag) of the field's dust from the observer to distance {@code d} along unit u. */
    final double extinction(double ox, double oy, double oz, double ux, double uy, double uz, double d) {
        double[] t = CHORD.get();
        if (!chord(ox, oy, oz, ux, uy, uz, t)) return 0;
        int m = intervals(ox, oy, oz, ux, uy, uz, t);
        double av = 0;
        double lod = Math.max(0.05 * radius, 2 * detailPc());
        for (int j = 0; j < m; j++) {
            double t0 = t[2 * j], t1 = Math.min(t[2 * j + 1], d);
            if (t1 <= t0) continue;
            int n = 12;
            double ds = (t1 - t0) / n;
            for (int i = 0; i < n; i++) {
                double tt = t0 + (i + 0.5) * ds;
                av += dust(ox + ux * tt, oy + uy * tt, oz + uz * tt, lod) * ds;
            }
        }
        return av;
    }

    private static final ThreadLocal<double[]> MARCH_TMP = ThreadLocal.withInitial(() -> new double[4]);
    private static final ThreadLocal<double[]> CHORD = ThreadLocal.withInitial(() -> new double[2 * MAX_INTERVALS]);

    // ------------------------------------------------------------------ helpers for subclasses

    static double smoothstep(double e0, double e1, double v) {
        double t = (v - e0) / (e1 - e0);
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return t * t * (3 - 2 * t);
    }

    /**
     * A placed local frame: local = M⁻¹ (world − c), so that the unit sphere of local space is the
     * (possibly ellipsoidal, rotated) body of the nebula.
     */
    static final class Local {
        final double cx, cy, cz;
        final double i00, i01, i02, i10, i11, i12, i20, i21, i22;
        /** Geometric-mean length of a local unit, pc. */
        final double scalePc;
        /** Longest local axis, pc (bounding radius of the unit sphere). */
        final double maxAxisPc;

        Local(Morphology.Frame f) {
            this(f.c, f.m);
        }

        Local(double[] c, double[][] m) {
            cx = c[0];
            cy = c[1];
            cz = c[2];
            double a = m[0][0], b = m[0][1], cc = m[0][2], d = m[1][0], e = m[1][1], ff = m[1][2], g = m[2][0], h = m[2][1], k = m[2][2];
            double det = a * (e * k - ff * h) - b * (d * k - ff * g) + cc * (d * h - e * g);
            double inv = 1 / det;
            i00 = (e * k - ff * h) * inv;
            i01 = (cc * h - b * k) * inv;
            i02 = (b * ff - cc * e) * inv;
            i10 = (ff * g - d * k) * inv;
            i11 = (a * k - cc * g) * inv;
            i12 = (cc * d - a * ff) * inv;
            i20 = (d * h - e * g) * inv;
            i21 = (b * g - a * h) * inv;
            i22 = (a * e - b * d) * inv;
            scalePc = Math.cbrt(Math.abs(det));
            double mx = 0;
            for (int j = 0; j < 3; j++) mx = Math.max(mx, Math.sqrt(m[0][j] * m[0][j] + m[1][j] * m[1][j] + m[2][j] * m[2][j]));
            maxAxisPc = mx;
        }

        /**
         * Distances along the ray o + t·d (world, d unit) where it is inside the local sphere of
         * radius {@code r} (an ellipsoid in the world): {@code out = {tIn, tOut}}; false if missed.
         */
        boolean hit(double ox, double oy, double oz, double dx, double dy, double dz, double r, double[] out) {
            double px = ox - cx, py = oy - cy, pz = oz - cz;
            double lx = i00 * px + i01 * py + i02 * pz, ly = i10 * px + i11 * py + i12 * pz, lz = i20 * px + i21 * py + i22 * pz;
            double ex = i00 * dx + i01 * dy + i02 * dz, ey = i10 * dx + i11 * dy + i12 * dz, ez = i20 * dx + i21 * dy + i22 * dz;
            double a = ex * ex + ey * ey + ez * ez, b = lx * ex + ly * ey + lz * ez, c = lx * lx + ly * ly + lz * lz - r * r;
            double disc = b * b - a * c;
            if (disc <= 0) return false;
            double sq = Math.sqrt(disc);
            out[0] = (-b - sq) / a;
            out[1] = (-b + sq) / a;
            return out[1] > 0;
        }

        void toLocal(double x, double y, double z, double[] out) {
            double px = x - cx, py = y - cy, pz = z - cz;
            out[0] = i00 * px + i01 * py + i02 * pz;
            out[1] = i10 * px + i11 * py + i12 * pz;
            out[2] = i20 * px + i21 * py + i22 * pz;
        }
    }
}
