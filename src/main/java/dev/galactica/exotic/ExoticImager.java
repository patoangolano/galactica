package dev.galactica.exotic;

import dev.galactica.science.Blackbody;
import dev.galactica.star.StarKind;

/**
 * CPU imager for compact remnants, the counterpart of {@code sky.PlanetImager}. It paints one
 * camera-facing sprite into straight-alpha ARGB (gamma-encoded sRGB). Row 0 is the −up edge and
 * column 0 the −right edge; pixel (px, py) looks along forward + a·right + b·up with
 * a = ((px+½)/size·2−1)·tan(halfAngle), i.e. a gnomonic projection that lands exactly on a flat quad
 * of half-width tan(halfAngle) at unit distance.
 *
 * <h2>Black holes (Schwarzschild)</h2>
 * <ul>
 *   <li><b>Shadow</b>: rays with impact parameter b &lt; b<sub>c</sub> = √27/2 · R<sub>s</sub>
 *       ≈ 2.598 R<sub>s</sub> are captured (Synge 1966; Luminet 1979, A&amp;A 75, 228).</li>
 *   <li><b>Thin disk</b>: each pixel is traced back to the disk plane with Beloborodov's
 *       light-bending approximation 1 − cos α = (1 − cos ψ)(1 − R<sub>s</sub>/r) (2002, ApJ 566, L85),
 *       which with b = r sin α / √(1 − R<sub>s</sub>/r) gives the closed form
 *       (1 − c²) r² + R<sub>s</sub>(1 − c)² r − b² = 0, c = cos ψ. The far side of the disk is
 *       therefore lifted over the shadow. Temperature follows Shakura &amp; Sunyaev (1973, A&amp;A 24,
 *       337) with the zero-torque inner edge, T ∝ r<sup>−3/4</sup>(1 − √(r<sub>in</sub>/r))<sup>1/4</sup>.</li>
 *   <li><b>Redshift and beaming</b>: for Keplerian circular orbits the photon energy ratio is
 *       g = √(1 − 3M/r) / (1 − Ω L<sub>z</sub>), Ω = √(M/r³), L<sub>z</sub> the photon's angular
 *       momentum about the disk axis (Luminet 1979; Cunningham &amp; Bardeen 1973). Because I<sub>ν</sub>/ν³
 *       is invariant, a blackbody of temperature T is seen as a blackbody of temperature gT: the
 *       colour is {@link Blackbody} at gT and the brightness is the bolometric intensity
 *       σ(gT)⁴/π ∝ g⁴T⁴, the quantity Luminet imaged, so Doppler beaming and gravitational redshift
 *       both enter exactly. (In the optical Rayleigh–Jeans tail of a 10⁶–10⁷ K disk the contrast
 *       would only be ∝ g; the bolometric view is the one that shows the physics.) A camera-like
 *       tone curve 1 − exp(−1.5 √I) compresses the T⁴ dynamic range.</li>
 *   <li><b>Photon ring</b>: the higher-order images pile up just outside b<sub>c</sub>
 *       (Johnson et al. 2020, Sci. Adv. 6, eaaz1310); drawn as a thin ring lit like the inner disk.</li>
 *   <li><b>RIAF</b> (Sgr A*): optically thin hot flow, a dim glow ∝ (b<sub>c</sub>/b)³ peaking on the
 *       photon ring, with faint foreground emission across the shadow and g³ beaming, the morphology
 *       of the EHT 2022 image (ApJL 930, L12). Synchrotron has no blackbody colour; it is tinted as
 *       a 3000 K source and kept dim (Sgr A* is invisible in the optical behind ~30 mag of extinction).</li>
 *   <li><b>Lensing of the background</b> (no shaders, so it is painted in): each ray is bent by
 *       α(b) = max(2R<sub>s</sub>/b + (15π/16)(R<sub>s</sub>/b)², −ln(b/b<sub>c</sub> − 1) − 0.4002), the
 *       weak-field series (Keeton &amp; Petters 2005, PRD 72, 104006) joined to Bozza's strong-deflection
 *       limit (2002, PRD 66, 103001), and the sky is sampled at the unlensed direction θ − α.
 *       Surface brightness is conserved by lensing, so the diffuse sky only distorts; the fraction of
 *       sky light that is in point stars is boosted by the point-lens magnification
 *       μ(u) = (u² + 2)/(u√(u² + 4)), u = β/θ<sub>E</sub> (Paczyński 1986), which brightens the Einstein
 *       ring θ<sub>E</sub> = √(2R<sub>s</sub>/D) where star images merge into arcs.</li>
 * </ul>
 *
 * <h2>Neutron stars and white dwarfs</h2>
 * Both are physically sub-pixel; the sprite is a point-spread image in the blackbody colour of the
 * surface. Pulsars add their two emission cones (see {@link Pulsar}) rendered by a short ray march
 * through each cone, and flash when a cone sweeps over the observer.
 *
 * <p>Thread-safe: no shared mutable state.
 */
public final class ExoticImager {
    private ExoticImager() {}

    /** Critical impact parameter of a Schwarzschild hole in units of Rs: √27 / 2. */
    public static final double SHADOW_RS = Math.sqrt(27) / 2;
    /** Bozza (2002) strong-deflection coefficients for Schwarzschild: ā = 1, b̄ = −0.4002. */
    private static final double BOZZA_B = -0.4002;
    /** Fraction of sky light assumed to be in point sources when brightening the Einstein ring. */
    public static final double POINT_FRACTION = 0.35;
    /** Peak of x^(−3/4)(1 − x^(−1/2))^(1/4), reached at x = 49/36. */
    private static final double SS_PEAK = Math.pow(36.0 / 49.0, 0.75) * Math.pow(1 - 6.0 / 7.0, 0.25);

    private static final int ENCODE_BINS = 4096;
    private static final int[] ENCODE = new int[ENCODE_BINS];

    static {
        for (int i = 0; i < ENCODE_BINS; i++) {
            double s = i / (double) (ENCODE_BINS - 1);
            ENCODE[i] = (int) Math.round(255 * Blackbody.encode(s * s));
        }
    }

    /** Background sky lookup: linear RGB seen along unit direction (x, y, z) in the view's frame. */
    @FunctionalInterface
    public interface SkySampler {
        void sample(double x, double y, double z, float[] linearRgbOut);
    }

    /** Camera and object state for one sprite. All vectors are unit and share one frame. */
    public static final class View {
        /** From the eye to the object. */
        public double[] forward = {0, 0, -1};
        public double[] right = {1, 0, 0};
        public double[] up = {0, 1, 0};
        /** Spin / disk angular-momentum axis of the object. */
        public double[] axis = {0, 1, 0};
        public double distanceMetres = 1e12;
        /** Half-width of the sprite, radians. */
        public double halfAngle = 0.05;
        /** Screen pixels per radian, used to fade lensing out below a pixel. */
        public double pxPerRad = 885;
        public double timeSeconds;
        /** For pulsars spinning faster than the frame rate: draw the beams averaged over a turn. */
        public boolean timeAveraged;
        /**
         * Angular radius (rad) beyond which the lensed background is left transparent, so the real
         * star layer behind the sprite shows through; see {@link #lensExtent}. Infinite = no limit.
         */
        public double lensOuter = Double.POSITIVE_INFINITY;
        /**
         * Pulsars: paint the steady surface point. Off when the star layer already draws the
         * star, so the sprite only adds the beams and the flash.
         */
        public boolean steadyCore = true;
    }

    /** Deflection angle (radians) of a ray with impact parameter b (in Rs); infinite when captured. */
    public static double deflection(double bRs) {
        if (bRs <= SHADOW_RS) return Double.POSITIVE_INFINITY;
        double weak = 2 / bRs + (15 * Math.PI / 16) / (bRs * bRs);
        double strong = -Math.log(bRs / SHADOW_RS - 1) + BOZZA_B;
        return Math.max(weak, strong);
    }

    /** Einstein angle (radians) for a background at infinity: √(2Rs/D). */
    public static double einsteinAngle(double rsMetres, double distanceMetres) {
        return Math.sqrt(2 * rsMetres / distanceMetres);
    }

    /**
     * Angular radius (rad) inside which the painted lens is worth drawing: the smaller of 3 θ<sub>E</sub>
     * (outside it the point-lens magnification is below 1.02, μ(3) ≈ 1.017) and the radius where the
     * weak-field deflection 2R<sub>s</sub>/b drops to one screen pixel, θ = 2 (R<sub>s</sub>/D)·pxPerRad.
     */
    public static double lensExtent(double rsOverD, double pxPerRad) {
        return Math.min(3 * Math.sqrt(2 * rsOverD), 2 * rsOverD * pxPerRad);
    }

    /** Point-lens magnification μ(u) = (u² + 2)/(u√(u² + 4)). */
    public static double magnification(double u) {
        u = Math.max(1e-6, u);
        return (u * u + 2) / (u * Math.sqrt(u * u + 4));
    }

    /**
     * Radius on the disk (in Rs) hit by a ray of impact parameter b whose photon plane meets the
     * disk at angle ψ from the line of sight (c = cos ψ), from Beloborodov's approximation.
     */
    public static double diskRadius(double bRs, double c) {
        double a = 1 - c * c, k = (1 - c) * (1 - c);
        if (a < 1e-9) return Double.NaN; // straight along the line of sight
        return (-k + Math.sqrt(k * k + 4 * a * bRs * bRs)) / (2 * a);
    }

    /** Shakura–Sunyaev effective temperature at r (Rs units) for a disk peaking at {@code peakK}. */
    public static double diskTemperature(double r, double rIn, double peakK) {
        if (r <= rIn) return 0;
        double x = r / rIn;
        return peakK / SS_PEAK * Math.pow(x, -0.75) * Math.pow(1 - 1 / Math.sqrt(x), 0.25);
    }

    /** g = √(1 − 1.5/r) / (1 − Ω Lz), with r and Lz in Rs units (M = ½ Rs). */
    public static double redshiftFactor(double r, double lz) {
        double omega = Math.sqrt(0.5 / (r * r * r));
        double grav = Math.sqrt(Math.max(0, 1 - 1.5 / r));
        return grav / Math.max(0.05, 1 - omega * lz);
    }

    /** Linear colour (max 1) of an unresolved remnant, for drawing it as a point. */
    public static void unresolvedColour(ExoticObject o, float[] rgb) {
        double t = switch (o.kind()) {
            case BLACK_HOLE -> o.accretion() == ExoticObject.Accretion.RIAF ? 3000 : Math.max(1000, o.diskPeakTempK());
            default -> Math.max(1000, o.surfaceTempK());
        };
        Blackbody.linearRgb(t, rgb);
    }

    /** Radius (Rs) the black-hole core sprite must reach to hold its disk / flow and photon ring. */
    public static double coreExtentRs(ExoticObject o) {
        return switch (o.accretion()) {
            case THIN_DISK -> o.diskOuterRs() * 1.1;
            case RIAF -> 14;
            case NONE -> 9;
        };
    }

    public static void render(ExoticObject o, View v, int size, int[] argbOut, SkySampler sky) {
        if (size <= 0 || argbOut.length < size * size) throw new IllegalArgumentException("argbOut too small for " + size);
        if (o.kind() == StarKind.BLACK_HOLE) renderBlackHole(o, v, size, argbOut, sky);
        else if (o.kind() == StarKind.NEUTRON_STAR) renderNeutronStar(o, v, size, argbOut);
        else renderWhiteDwarf(o, v, size, argbOut);
    }

    // ------------------------------------------------------------------ black holes

    private static void renderBlackHole(ExoticObject o, View v, int size, int[] out, SkySampler sky) {
        double[] F = v.forward, R = v.right, U = v.up, A = v.axis;
        double tanH = Math.tan(Math.min(1.2, v.halfAngle));
        double rsOverD = o.rsMetres() / v.distanceMetres;
        double pixRs = 2 * tanH / size / rsOverD;
        double thetaE = Math.sqrt(2 * rsOverD);
        ExoticObject.Accretion acc = o.accretion();
        double emitScale = acc == ExoticObject.Accretion.THIN_DISK ? o.diskOuterRs() : 12;
        boolean emit = acc != ExoticObject.Accretion.NONE && pixRs < 0.5 * emitScale;

        // Disk axis in image coordinates: x right, y up, z towards the observer.
        double jx = dot(A, R), jy = dot(A, U), jz = -dot(A, F);
        double nz = Math.abs(jz) < 1e-4 ? 1e-4 : jz;
        double sgn = Math.signum(nz);
        double rIn = o.diskInnerRs(), rOut = o.diskOuterRs(), tPeak = o.diskPeakTempK();
        float[] rgb = new float[3], bg = new float[3];
        float[] ringRgb = new float[3];

        for (int py = 0; py < size; py++) {
            double bb = ((py + 0.5) / size * 2 - 1) * tanH;
            for (int px = 0; px < size; px++) {
                double a = ((px + 0.5) / size * 2 - 1) * tanH;
                double rho = Math.sqrt(a * a + bb * bb);
                double edge = Math.max(0, Math.min(1, (1 - rho / tanH) / 0.12));
                if (edge <= 0) {
                    out[py * size + px] = 0;
                    continue;
                }
                double theta = Math.atan(rho);
                double ex = rho > 0 ? a / rho : 1, ey = rho > 0 ? bb / rho : 0;
                double b = Math.sin(theta) / rsOverD;
                double X = b * ex, Y = b * ey;
                boolean shadow = b < SHADOW_RS;
                double lz = Y * jx - X * jy;

                // Emission, premultiplied linear colour.
                double er = 0, eg = 0, eb = 0, ea = 0;
                if (emit && acc == ExoticObject.Accretion.THIN_DISK) {
                    double q = (ex * jx + ey * jy) * sgn;
                    double psi = Math.atan2(Math.abs(nz), -q);
                    double r = diskRadius(b, Math.cos(psi));
                    if (r >= rIn && r <= rOut && !(shadow && psi > Math.PI / 2)) {
                        double g = Math.min(5, redshiftFactor(r, lz));
                        double tObs = g * diskTemperature(r, rIn, tPeak);
                        double lum = tone(tObs / tPeak);
                        Blackbody.linearRgb(tObs, rgb);
                        double alpha = Math.min(1, lum * 4);
                        er = rgb[0] * lum;
                        eg = rgb[1] * lum;
                        eb = rgb[2] * lum;
                        ea = alpha;
                    }
                    double w = ringWeight(b);
                    if (w > 0.01 && ea < 1) {
                        double rr = 1.4 * rIn;
                        double tObs = Math.min(5, redshiftFactor(rr, lz)) * diskTemperature(rr, rIn, tPeak);
                        double lum = w * 0.8 * tone(tObs / tPeak);
                        Blackbody.linearRgb(tObs, ringRgb);
                        double t = 1 - ea;
                        er += t * ringRgb[0] * lum;
                        eg += t * ringRgb[1] * lum;
                        eb += t * ringRgb[2] * lum;
                        ea += t * Math.min(1, lum * 3);
                    }
                } else if (emit) { // RIAF
                    double base = shadow ? 0.05 : Math.pow(SHADOW_RS / b, 3);
                    double rr = Math.max(3, b);
                    double g = Math.min(3, redshiftFactor(rr, lz) / Math.sqrt(1 - 1.5 / rr));
                    double lumI = (0.6 * base + 2.5 * ringWeight(b)) * g * g * g;
                    double lum = 0.35 * (1 - Math.exp(-lumI));
                    Blackbody.linearRgb(3000, rgb);
                    er = rgb[0] * lum;
                    eg = rgb[1] * lum;
                    eb = rgb[2] * lum;
                    ea = Math.min(1, lum * 2.5);
                }

                // Background: black inside the shadow, otherwise the lensed sky.
                double br = 0, bgG = 0, bbB = 0, ba = 0;
                if (shadow) {
                    ba = 1;
                } else if (sky != null) {
                    double alpha = deflection(b);
                    // Opaque only where the bending moves the sky by more than a pixel, and only
                    // inside lensOuter (fading over its outer third), so the real stars beyond it
                    // stay visible; the bake behind holds only the diffuse Milky Way.
                    double band = Double.isInfinite(v.lensOuter) ? 1
                            : Math.max(0, Math.min(1, (v.lensOuter - theta) / (v.lensOuter / 3)));
                    ba = Math.max(0, Math.min(1, alpha * v.pxPerRad - 1)) * band;
                    if (ba > 0) {
                        double ts = theta - alpha;
                        double cs = Math.cos(ts), ss = Math.sin(ts);
                        double dx = cs * F[0] + ss * (ex * R[0] + ey * U[0]);
                        double dy = cs * F[1] + ss * (ex * R[1] + ey * U[1]);
                        double dz = cs * F[2] + ss * (ex * R[2] + ey * U[2]);
                        sky.sample(dx, dy, dz, bg);
                        double mu = Math.min(8, magnification(Math.abs(ts) / thetaE));
                        double boost = 1 + POINT_FRACTION * (mu - 1);
                        br = bg[0] * boost;
                        bgG = bg[1] * boost;
                        bbB = bg[2] * boost;
                    }
                }
                double t = 1 - ea;
                double cr = er + t * br * ba, cg = eg + t * bgG * ba, cb = eb + t * bbB * ba;
                double ca = (ea + t * ba) * edge;
                cr *= edge;
                cg *= edge;
                cb *= edge;
                out[py * size + px] = pack(cr, cg, cb, ca);
            }
        }
    }

    /** Bolometric intensity (T/T_peak)⁴ through the camera tone curve. */
    private static double tone(double tRatio) {
        double i = tRatio * tRatio * tRatio * tRatio;
        return 1 - Math.exp(-1.5 * Math.sqrt(i));
    }

    /** Thin photon-ring profile just outside the critical curve. */
    private static double ringWeight(double b) {
        double d = (b - 1.025 * SHADOW_RS) / (0.035 * SHADOW_RS);
        return b < SHADOW_RS ? 0 : Math.exp(-d * d);
    }

    // ------------------------------------------------------------------ neutron stars

    private static final int BEAM_STEPS = 12, AVG_PHASES = 8;

    private static void renderNeutronStar(ExoticObject o, View v, int size, int[] out) {
        Pulsar p = o.pulsar();
        double[] F = v.forward, R = v.right, U = v.up, spin = v.axis;
        double[] toObs = {-F[0], -F[1], -F[2]};
        int phases = v.timeAveraged ? AVG_PHASES : 1;
        double[][] m = new double[phases][3];
        double flash = 0;
        for (int k = 0; k < phases; k++) {
            double t = v.timeAveraged ? v.timeSeconds + p.periodS() * k / phases : v.timeSeconds;
            double[] mw = new double[3];
            p.magneticAxis(spin, t, mw);
            m[k][0] = dot(mw, R);
            m[k][1] = dot(mw, U);
            m[k][2] = -dot(mw, F);
            flash += p.beamIntensity(spin, toObs, t) / phases;
        }
        double cosRho = Math.cos(p.beamHalfRad());
        float[] surf = new float[3], beam = new float[3];
        Blackbody.linearRgb(Math.max(1000, o.surfaceTempK()), surf);
        Blackbody.linearRgb(40_000, beam);
        double sigma = 0.05 + 0.10 * flash;
        double coreGain = (v.steadyCore ? 0.7 : 0) + 2.5 * flash;
        double dz = 2.0 / BEAM_STEPS;

        for (int py = 0; py < size; py++) {
            double y = (py + 0.5) / size * 2 - 1;
            for (int px = 0; px < size; px++) {
                double x = (px + 0.5) / size * 2 - 1;
                double r2 = x * x + y * y;
                double edge = Math.max(0, Math.min(1, (1 - Math.sqrt(r2)) / 0.15));
                if (edge <= 0) {
                    out[py * size + px] = 0;
                    continue;
                }
                double core = coreGain * Math.exp(-r2 / (2 * sigma * sigma));
                double beamI = 0;
                for (int k = 0; k < phases; k++) {
                    double[] mk = m[k];
                    for (int s = 0; s < BEAM_STEPS; s++) {
                        double z = -1 + (s + 0.5) * dz;
                        double len = Math.sqrt(r2 + z * z);
                        if (len >= 1 || len < 1e-6) continue;
                        double c = Math.abs(x * mk[0] + y * mk[1] + z * mk[2]) / len;
                        if (c > cosRho) beamI += (1 - len) * (c - cosRho) / (1 - cosRho) * dz;
                    }
                }
                beamI = 2.5 * beamI / phases;
                double cr = surf[0] * core + beam[0] * beamI;
                double cg = surf[1] * core + beam[1] * beamI;
                double cb = surf[2] * core + beam[2] * beamI;
                double lum = Math.max(cr, Math.max(cg, cb));
                double alpha = Math.min(1, lum) * edge;
                double k = lum > 1 ? 1 / lum : 1;
                out[py * size + px] = pack(cr * k * edge, cg * k * edge, cb * k * edge, alpha);
            }
        }
    }

    // ------------------------------------------------------------------ white dwarfs

    private static void renderWhiteDwarf(ExoticObject o, View v, int size, int[] out) {
        float[] c = new float[3];
        Blackbody.linearRgb(Math.max(1000, o.surfaceTempK()), c);
        for (int py = 0; py < size; py++) {
            double y = (py + 0.5) / size * 2 - 1;
            for (int px = 0; px < size; px++) {
                double x = (px + 0.5) / size * 2 - 1;
                double r2 = x * x + y * y;
                double i = Math.exp(-r2 / (2 * 0.08 * 0.08)) + 0.03 / (1 + r2 * 60);
                i *= Math.max(0, 1 - r2);
                double a = Math.min(1, i);
                out[py * size + px] = pack(c[0] * a, c[1] * a, c[2] * a, a);
            }
        }
    }

    // ------------------------------------------------------------------ encoding

    /** Premultiplied linear → straight-alpha sRGB ARGB. */
    private static int pack(double r, double g, double b, double a) {
        if (a <= 1e-4) return 0;
        a = Math.min(1, a);
        return (int) Math.round(a * 255) << 24 | enc(r / a) << 16 | enc(g / a) << 8 | enc(b / a);
    }

    private static int enc(double linear) {
        if (!(linear > 0)) return 0;
        if (linear >= 1) return 255;
        return ENCODE[(int) (Math.sqrt(linear) * (ENCODE_BINS - 1) + 0.5)];
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
