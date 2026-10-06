package dev.galactica.sky;

import dev.galactica.science.Blackbody;
import dev.galactica.science.Noise;
import dev.galactica.science.SeedRng;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;

/**
 * CPU imager for the camera-facing planet quad. Orthographic ray cast along the view direction
 * against a unit sphere, an optional Saturn-like ring plane and a thin limb atmosphere, composited
 * front to back into straight-alpha ARGB in gamma-encoded sRGB.
 *
 * <p>Photometry: Lambert for bodies with an atmosphere, a Lommel–Seeliger/Lambert blend for airless
 * regolith (the flat-looking full Moon, Hapke 1981), Minnaert (k ≈ 1.1) limb darkening for cloud
 * decks. Rings use the single-scattering thin-layer solution (Chandrasekhar 1960) for the lit and
 * unlit faces, with optical depth τ(r) following Saturn's C/B/A rings, Cassini division and Encke
 * gap (Cuzzi et al. 2009). Limb haze uses Rayleigh plus Henyey–Greenstein phase functions, so a
 * backlit hazy world shows a bright forward-scattering ring.
 *
 * <p>Thread-safe; all per-render state lives in a thread-local scratch, nothing is allocated per pixel.
 */
public final class PlanetImager {
    private PlanetImager() {}

    /** Saturn's C-ring inner edge and A-ring outer edge (74 658 and 136 775 km over 60 268 km). */
    public static final double RING_INNER = 1.24, RING_OUTER = 2.27;
    private static final double PLAIN_EXTENT = 1.35;
    private static final int RING_BINS = 512;
    /** Camera exposure: a planet of albedo ~0.6 under a white star reaches display white. */
    private static final double EXPOSURE = 1.6;
    private static final int ENCODE_BINS = 4096;
    /** linear → 8-bit sRGB, indexed by sqrt(linear) so the dark end keeps full precision. */
    private static final int[] ENCODE = new int[ENCODE_BINS];
    private static final float[] LAVA_RGB = new float[3];
    private static final double RING_SCALE = RING_BINS / (RING_OUTER - RING_INNER);
    /**
     * Fixed rotation (axis (0.48, 0.60, 0.64), 1.1 rad) applied before every noise octave, so the
     * value-noise lattice never lines up with the body axes or with the previous octave: no square
     * cloud corners or rectangular coastlines under steep thresholds.
     */
    private static final double M00, M01, M02, M10, M11, M12, M20, M21, M22;

    static {
        for (int i = 0; i < ENCODE_BINS; i++) {
            double s = i / (double) (ENCODE_BINS - 1);
            ENCODE[i] = (int) Math.round(255 * Blackbody.encode(s * s));
        }
        Blackbody.linearRgb(1400, LAVA_RGB); // basaltic lava, ~1100–1200 °C
        double ax = 0.48, ay = 0.60, az = 0.64, c = Math.cos(1.1), s = Math.sin(1.1), k = 1 - c;
        M00 = c + ax * ax * k;      M01 = ax * ay * k - az * s; M02 = ax * az * k + ay * s;
        M10 = ay * ax * k + az * s; M11 = c + ay * ay * k;      M12 = ay * az * k - ax * s;
        M20 = az * ax * k - ay * s; M21 = az * ay * k + ax * s; M22 = c + az * az * k;
    }

    /**
     * Camera and illumination for one render. All vectors are unit length, Minecraft axes, with
     * {@code right = forward × up} so the image is not mirrored and rotation reads prograde.
     */
    public static final class View {
        /** Eye → planet. */
        public double[] forward = {0, 0, 1};
        /** forward × up. */
        public double[] right = {-1, 0, 0};
        public double[] up = {0, 1, 0};
        /** Planet → star. */
        public double[] toStar = {0, 0, -1};
        /** Linear sRGB of the star, max component ~1. */
        public float starR = 1, starG = 1, starB = 1;
        public double rotationRad;
        public double[] spinAxis = {0, 1, 0};
    }

    /** Half-size of the quad in planet radii. */
    public static double extent(Body b) {
        return b.rings() ? RING_OUTER * 1.05 : PLAIN_EXTENT;
    }

    private static final class Scratch {
        final float[] ringTau = new float[RING_BINS], ringR = new float[RING_BINS], ringG = new float[RING_BINS],
                ringB = new float[RING_BINS];
        /** ringCum[i] = Σ τ over bins [0, i): prefix sums for the box-filtered radial lookup. */
        final double[] ringCum = new double[RING_BINS + 1];
        long ringKey = 0x7FFF_FFFF_FFFF_FFFFL;
        // shade() outputs: linear albedo, emission (already exposed), specular water mask
        double ar, ag, ab, er, eg, eb, water;
        // hot-Jupiter thermal emission at full band weight, constant per render
        final float[] bb = new float[3];
        double hjR, hjG, hjB;
        // up to three layers per pixel: depth, premultiplied rgb, alpha
        final double[] ld = new double[3], lr = new double[3], lg = new double[3], lb = new double[3], la = new double[3];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private enum Photometry { LAMBERT, LUNAR, MINNAERT }

    /**
     * Renders {@code size × size} pixels into {@code argbOut}. Row 0 is the −up edge, column 0 the
     * −right edge; pixel (px, py) samples ((px+½)/size·2−1)·extent along right and likewise along up.
     */
    public static void render(Body b, View v, int size, int[] argbOut) {
        if (size <= 0 || argbOut.length < size * size) throw new IllegalArgumentException("argbOut too small for " + size);
        Scratch s = SCRATCH.get();
        BodyType type = b.type();
        long seed = b.terrainSeed();
        double ext = extent(b), ps = 2 * ext / size;
        double[] F = v.forward, R = v.right, U = v.up, L = v.toStar, S = v.spinAxis;

        // Body-fixed basis (b1, b2, S): rotating it by +θ carries surface features prograde.
        double rx = Math.abs(S[1]) < 0.9 ? 0 : 1, ry = Math.abs(S[1]) < 0.9 ? 1 : 0;
        double d0 = rx * S[0] + ry * S[1];
        double e1x = rx - d0 * S[0], e1y = ry - d0 * S[1], e1z = -d0 * S[2];
        double inv = 1 / Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z);
        e1x *= inv; e1y *= inv; e1z *= inv;
        double e2x = S[1] * e1z - S[2] * e1y, e2y = S[2] * e1x - S[0] * e1z, e2z = S[0] * e1y - S[1] * e1x;
        double cr = Math.cos(v.rotationRad), sr = Math.sin(v.rotationRad);
        double b1x = cr * e1x + sr * e2x, b1y = cr * e1y + sr * e2y, b1z = cr * e1z + sr * e2z;
        double b2x = -sr * e1x + cr * e2x, b2y = -sr * e1y + cr * e2y, b2z = -sr * e1z + cr * e2z;
        // n·X = a·XR + b·XU − z·XF for the surface point seen at image (a, b) with depth z.
        double b1R = dot(b1x, b1y, b1z, R), b1U = dot(b1x, b1y, b1z, U), b1F = dot(b1x, b1y, b1z, F);
        double b2R = dot(b2x, b2y, b2z, R), b2U = dot(b2x, b2y, b2z, U), b2F = dot(b2x, b2y, b2z, F);
        double sR = dot(S, R), sU = dot(S, U), sF = dot(S, F);
        double lR = dot(L, R), lU = dot(L, U), lF = dot(L, F), lS = dot(L, S);
        // Half vector between star and eye (eye direction is −F), for ocean glint.
        double hx = L[0] - F[0], hy = L[1] - F[1], hz = L[2] - F[2];
        double hn = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double hR = 0, hU = 0, hF = 0;
        if (hn > 1e-9) { hR = dot(hx, hy, hz, R) / hn; hU = dot(hx, hy, hz, U) / hn; hF = dot(hx, hy, hz, F) / hn; }
        double starR = v.starR, starG = v.starG, starB = v.starB;

        Photometry photo;
        double minnaertK = 1.0;
        // Visual weight of the air: 0 below ~1 mbar (Triton, Pluto: no visible limb), ~0.26 for Mars, 1 from 1 bar.
        double atmStrength = b.atmosphere() == Body.Atmosphere.NONE || !(b.surfacePressureBar() > 0) ? 0
                : clamp(Math.log10(b.surfacePressureBar() / 1e-3) / 3, 0, 1);
        boolean hasAtm = atmStrength >= 0.02 && b.radiusMetres() > 0;
        switch (type) {
            case GAS_GIANT, HOT_JUPITER, ICE_GIANT, SUB_NEPTUNE -> { photo = Photometry.MINNAERT; minnaertK = 1.1; }
            case GREENHOUSE -> { photo = Photometry.MINNAERT; minnaertK = 1.0; }
            default -> photo = hasAtm && b.surfacePressureBar() > 0.05 ? Photometry.LAMBERT : Photometry.LUNAR;
        }

        // Atmosphere: visual thickness from the scale height, tint and phase function per composition.
        double atmThick = 0, tauVeil = 0, tr = 0, tg = 0, tb = 0, phase = 1;
        boolean rayleigh = false;
        if (hasAtm) {
            atmThick = clamp(6 * b.scaleHeight() / b.radiusMetres(), 0.02, 0.08);
            double g, wRay;
            switch (b.atmosphere()) {
                case N2_O2 -> { tr = 0.30; tg = 0.55; tb = 1.0; g = 0.7; wRay = 0.75; tauVeil = 0.15; rayleigh = true; }
                // Titan's tholin haze has visible τ ≳ 5 (Tomasko et al. 2005): the surface is invisible.
                case N2_CH4 -> { tr = 1.0; tg = 0.58; tb = 0.22; g = 0.6; wRay = 0.1; tauVeil = 4.5; }
                case CO2_THICK -> { tr = 1.0; tg = 0.92; tb = 0.65; g = 0.5; wRay = 0.2; tauVeil = 0.0; }
                case TRACE_CO2 -> { tr = 0.95; tg = 0.70; tb = 0.48; g = 0.65; wRay = 0.0; tauVeil = 0.06; }
                case H2O_STEAM -> { tr = 1.0; tg = 1.0; tb = 1.0; g = 0.45; wRay = 0.2; tauVeil = 0.5; }
                default -> {
                    g = 0.3; wRay = 0.4; tauVeil = 0.08;
                    switch (type) {
                        case HOT_JUPITER -> { tr = 1.0; tg = 0.62; tb = 0.42; }
                        case ICE_GIANT -> { tr = 0.55; tg = 0.82; tb = 1.0; }
                        case SUB_NEPTUNE -> { tr = 0.62; tg = 0.92; tb = 0.95; }
                        default -> { tr = 0.85; tg = 0.88; tb = 1.0; }
                    }
                }
            }
            tauVeil *= atmStrength;
            double c = lF; // cosine of the scattering angle: +1 when the star is behind the planet
            double hg = (1 - g * g) / Math.pow(1 + g * g - 2 * g * c, 1.5);
            phase = wRay * 0.75 * (1 + c * c) + (1 - wRay) * hg;
        }

        if (type == BodyType.HOT_JUPITER) { // ≥1500 K thermal glow, visible on the night side
            double temp = Math.max(b.surfaceTemp(), b.equilibriumTemp());
            double heat = sq(clamp((temp - 900) / 1600, 0, 1)) * 0.06;
            Blackbody.linearRgb(temp, s.bb);
            s.hjR = s.bb[0] * heat; s.hjG = s.bb[1] * heat; s.hjB = s.bb[2] * heat;
        }

        boolean rings = b.rings();
        double cEff = 0, ringMu0 = 0, kR = 0, kU = 0, shadowW = 0;
        boolean ringLitFace = false;
        if (rings) {
            buildRings(b, s);
            // Edge-on rings would vanish; a one-pixel minimum apparent opening keeps them a thin line.
            cEff = Math.copySign(Math.sqrt(sF * sF + ps * ps), sF);
            ringMu0 = Math.max(Math.abs(lS), 0.02);
            ringLitFace = lS * -cEff > 0;
            kR = -sR / cEff; kU = -sU / cEff; // ∂u/∂a, ∂u/∂b of the ring-plane hit
            // Shadow footprint: one pixel on the disc moves the shadow ray's ring hit by ~ps/μ0.
            shadowW = Math.min(0.5 * ps / ringMu0, 0.1);
        }
        double atmOuter = 1 + atmThick;

        for (int py = 0; py < size; py++) {
            double bb = ((py + 0.5) / size * 2 - 1) * ext;
            for (int px = 0; px < size; px++) {
                double a = ((px + 0.5) / size * 2 - 1) * ext;
                double r2 = a * a + bb * bb, r = Math.sqrt(r2);
                int nl = 0;

                // Disc, antialiased over one pixel at the limb.
                if (r < 1 + 0.5 * ps) {
                    double cover = clamp((1 - r) / ps + 0.5, 0, 1);
                    double k = r > 0.9999 ? 0.9999 / r : 1;
                    double na = a * k, nb = bb * k, z = Math.sqrt(Math.max(0, 1 - (na * na + nb * nb)));
                    double qx = na * b1R + nb * b1U - z * b1F;
                    double qy = na * b2R + nb * b2U - z * b2F;
                    double qz = na * sR + nb * sU - z * sF;
                    shade(type, b, seed, qx, qy, qz, s);
                    double mu0 = na * lR + nb * lU - z * lF, mu = z;
                    double direct;
                    if (mu0 <= 0) direct = 0;
                    else direct = switch (photo) {
                        case LAMBERT -> mu0;
                        case LUNAR -> 0.65 * 2 * mu0 / (mu0 + Math.max(mu, 1e-3)) + 0.35 * mu0;
                        case MINNAERT -> Math.pow(mu0, minnaertK) * Math.pow(Math.max(mu, 0.02), minnaertK - 1);
                    };
                    double spec = 0;
                    if (s.water > 0 && mu0 > 0) {
                        double nh = Math.max(0, na * hR + nb * hU - z * hF);
                        nh *= nh; nh *= nh; nh *= nh; nh *= nh; nh *= nh; nh *= nh; // ^64
                        spec = 0.45 * s.water * nh;
                    }
                    if (rings && direct > 0 && Math.abs(lS) > 1e-6) {
                        double t = -qz / lS;
                        if (t > 0) {
                            double tau = ringTau(s, Math.sqrt(1 + 2 * t * mu0 + t * t), shadowW);
                            if (tau > 0) {
                                double tr2 = Math.exp(-tau / ringMu0);
                                direct *= tr2;
                                spec *= tr2;
                            }
                        }
                    }
                    double cR = (s.ar * direct * EXPOSURE + spec) * starR;
                    double cG = (s.ag * direct * EXPOSURE + spec) * starG;
                    double cB = (s.ab * direct * EXPOSURE + spec) * starB;
                    if (hasAtm) {
                        double veil = Math.exp(-tauVeil / Math.max(mu, 0.05));
                        double ill = smooth(-0.2, 0.25, mu0) * (0.3 + 0.7 * Math.max(mu0, 0));
                        // Inscatter grows toward the limb (longer path) and is weighted by the phase function.
                        double in = (1 - veil) * ill * phase * EXPOSURE * 0.6
                                + atmStrength * 0.25 * smooth(0.45, 0.0, mu) * ill * phase;
                        cR = cR * veil + tr * starR * in;
                        cG = cG * veil + tg * starG * in;
                        cB = cB * veil + tb * starB * in;
                    }
                    cR += s.er; cG += s.eg; cB += s.eb;
                    s.ld[nl] = -z; s.lr[nl] = cR * cover; s.lg[nl] = cG * cover; s.lb[nl] = cB * cover; s.la[nl] = cover;
                    nl++;
                }

                // Limb glow: line-of-sight column through an exponential shell above the disc.
                if (hasAtm && r > 1 - 0.5 * ps && r < atmOuter) {
                    double alt = Math.max(0, r - 1);
                    double dens = Math.exp(-alt / (atmThick * 0.3)) * (1 - smooth(0.7 * atmThick, atmThick, alt));
                    double col = atmStrength * (1 - Math.exp(-3 * dens));
                    double mL = (a * lR + bb * lU) / Math.max(r, 1e-9);
                    double ill = smooth(-0.35, 0.3, mL) * (0.35 + 0.65 * clamp(mL, 0, 1));
                    double gr = tr, gg = tg, gb = tb;
                    if (rayleigh) { // grazing light has lost its blue: sunset-red rim near the terminator
                        double red = smooth(0.35, -0.05, mL);
                        gr = lerp(red, gr, 1.0); gg = lerp(red, gg, 0.45); gb = lerp(red, gb, 0.15);
                    }
                    double amt = col * ill * phase * 1.2;
                    double pr = gr * starR * amt, pg = gg * starG * amt, pb = gb * starB * amt;
                    double al = Math.min(1, Math.max(pr, Math.max(pg, pb)));
                    if (al > 1e-4) {
                        double sc = Math.min(1, al / Math.max(pr, Math.max(pg, pb)));
                        s.ld[nl] = 0; s.lr[nl] = pr * sc; s.lg[nl] = pg * sc; s.lb[nl] = pb * sc; s.la[nl] = al;
                        nl++;
                    }
                }

                // Ring plane: X = P + uF with X·S = 0, in-plane radius ρ² = r² + u².
                if (rings) {
                    double pS = a * sR + bb * sU;
                    double u = -pS / cEff;
                    double rho = Math.sqrt(r2 + u * u);
                    // Radial footprint of this pixel in the ring plane: |∇ρ| · ps.
                    double w = 0.5 * ps * Math.hypot(a + u * kR, bb + u * kU) / Math.max(rho, 1e-9);
                    double tau = ringTau(s, rho, Math.max(w, 1e-6));
                    if (tau > 1e-6) {
                        int bin = Math.min(RING_BINS - 1, Math.max(0, (int) Math.floor((rho - RING_INNER) * RING_SCALE)));
                        double mu = Math.abs(cEff), mu0 = ringMu0;
                        double alpha = 1 - Math.exp(-tau / mu);
                        double i;
                        if (ringLitFace) i = 2 * mu0 / (mu0 + mu) * (1 - Math.exp(-tau * (1 / mu0 + 1 / mu)));
                        else if (Math.abs(mu - mu0) < 1e-4) i = 2 * mu0 * tau / (mu * mu) * Math.exp(-tau / mu);
                        else i = 2 * mu0 / (mu - mu0) * (Math.exp(-tau / mu) - Math.exp(-tau / mu0));
                        double xL = a * lR + bb * lU + u * lF;
                        if (xL < 0) i *= smooth(0.97, 1.03, Math.sqrt(Math.max(0, r2 + u * u - xL * xL)));
                        i *= EXPOSURE * 0.6; // ring particles are rough: no opposition-surge boost
                        if (alpha > 1e-4) {
                            s.ld[nl] = u; s.la[nl] = alpha;
                            s.lr[nl] = Math.min(alpha, s.ringR[bin] * i * starR);
                            s.lg[nl] = Math.min(alpha, s.ringG[bin] * i * starG);
                            s.lb[nl] = Math.min(alpha, s.ringB[bin] * i * starB);
                            nl++;
                        }
                    }
                }

                argbOut[py * size + px] = composite(s, nl);
            }
        }
    }

    /** Front-to-back "over" of up to three premultiplied layers, then straight-alpha sRGB. */
    private static int composite(Scratch s, int n) {
        if (n == 0) return 0;
        double cr = 0, cg = 0, cb = 0, ca = 0;
        int used = 0; // bitmask of consumed layers
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int j = 0; j < n; j++) if ((used & (1 << j)) == 0 && (best < 0 || s.ld[j] < s.ld[best])) best = j;
            used |= 1 << best;
            double t = 1 - ca;
            cr += t * s.lr[best]; cg += t * s.lg[best]; cb += t * s.lb[best]; ca += t * s.la[best];
        }
        if (ca <= 1e-4) return 0;
        int alpha = (int) Math.round(Math.min(1, ca) * 255);
        return alpha << 24 | enc(cr / ca) << 16 | enc(cg / ca) << 8 | enc(cb / ca);
    }

    private static int enc(double linear) {
        if (!(linear > 0)) return 0;
        if (linear >= 1) return 255;
        return ENCODE[(int) (Math.sqrt(linear) * (ENCODE_BINS - 1) + 0.5)];
    }

    /**
     * Procedural surface at body-fixed unit vector (x, y, z), z along the spin axis. Writes linear
     * albedo, exposed emission and a specular water mask into {@code o}.
     */
    private static void shade(BodyType t, Body b, long seed, double x, double y, double z, Scratch o) {
        double r, g, bl, er = 0, eg = 0, eb = 0, water = 0;
        double lat = Math.abs(z);
        switch (t) {
            case TERRAN, OCEAN -> {
                double h = fbmRot(seed, x * 1.6, y * 1.6, z * 1.6, 6);
                double m = fbmRot(seed ^ 0x5EED_0001L, x * 3.1, y * 3.1, z * 3.1, 3);
                double sea = t == BodyType.OCEAN ? 0.30 : 0.05;
                if (h < sea) {
                    double dep = smooth(0, 0.14, sea - h);
                    r = lerp(dep, 0.020, 0.008); g = lerp(dep, 0.075, 0.025); bl = lerp(dep, 0.110, 0.075);
                    water = 1;
                } else {
                    double e = h - sea;
                    if (b.life() != Body.Life.NONE) {
                        // Hadley-cell desert belts near ±25°, wetter tropics and mid-latitudes.
                        double arid = 0.9 * Math.exp(-sq((lat - 0.42) / 0.13)) - 1.5 * m + 0.05;
                        double dry = smooth(0.15, 0.6, arid);
                        r = lerp(dry, 0.040, 0.33); g = lerp(dry, 0.080, 0.25); bl = lerp(dry, 0.028, 0.15);
                        double boreal = smooth(0.55, 0.75, lat);
                        r = lerp(boreal, r, 0.05); g = lerp(boreal, g, 0.07); bl = lerp(boreal, bl, 0.045);
                    } else {
                        double w = smooth(-0.3, 0.3, m);
                        r = lerp(w, 0.20, 0.12); g = lerp(w, 0.15, 0.10); bl = lerp(w, 0.10, 0.08);
                    }
                    // Bare rock and snow only on the highest ~10 % / ~5 % of the surface (h spans ±0.75).
                    double rock = smooth(0.24, 0.40, e);
                    r = lerp(rock, r, 0.15); g = lerp(rock, g, 0.13); bl = lerp(rock, bl, 0.11);
                    double snow = smooth(0.48, 0.58, e + lat * 0.25);
                    r = lerp(snow, r, 0.72); g = lerp(snow, g, 0.75); bl = lerp(snow, bl, 0.80);
                }
                double ice = smooth(0.80 + 0.07 * m, 0.86 + 0.07 * m, lat);
                r = lerp(ice, r, 0.70); g = lerp(ice, g, 0.74); bl = lerp(ice, bl, 0.78);
                water *= 1 - ice;
                // Domain-warped clouds (curling fronts, no lattice blocks), stretched along latitude by
                // zonal winds: lower frequency across the spin axis.
                double wx = fbmRot(seed ^ 0xC10D_0003L, x * 2.2, y * 2.2, z * 2.2, 2);
                double wy = fbmRot(seed ^ 0xC10D_0004L, x * 2.2, y * 2.2, z * 2.2, 2);
                double wz = fbmRot(seed ^ 0xC10D_0005L, x * 2.2, y * 2.2, z * 2.2, 2);
                double cx = x + 0.35 * wx + 0.25 * m, cy = y + 0.35 * wy, cz = z + 0.35 * wz;
                double c = fbmRot(seed ^ 0xC10D_0002L, cx * 2.6, cy * 2.6, cz * 5.2, 5);
                double cov = smooth(0.0, 0.55, c) * 0.78;
                r = lerp(cov, r, 0.78); g = lerp(cov, g, 0.78); bl = lerp(cov, bl, 0.80);
                water *= 1 - cov;
            }
            case DESERT -> {
                double h = fbmRot(seed, x * 2.0, y * 2.0, z * 2.0, 5);
                double big = fbmRot(seed ^ 0xDE5E_0003L, x * 0.9, y * 0.9, z * 0.9, 3);
                double w = smooth(-0.3, 0.3, h);
                r = lerp(w, 0.26, 0.44); g = lerp(w, 0.11, 0.26); bl = lerp(w, 0.05, 0.13);
                double maria = 0.75 * smooth(-0.02, -0.25, big);
                r = lerp(maria, r, 0.10); g = lerp(maria, g, 0.065); bl = lerp(maria, bl, 0.04);
                double k = 1 + 0.6 * craters(seed ^ 0xC4A7_0004L, x * 7, y * 7, z * 7);
                r *= k; g *= k; bl *= k;
                double cap = smooth(0.92, 0.95, lat + 0.02 * h);
                r = lerp(cap, r, 0.75); g = lerp(cap, g, 0.73); bl = lerp(cap, bl, 0.70);
            }
            case GREENHOUSE -> {
                double w = fbmRot(seed, x * 2, y * 2, z * 2, 3);
                double band = 0.5 + 0.5 * Math.sin(z * 10 + w * 2.5);
                double k = 0.94 + 0.08 * band;
                double streak = 0.25 * smooth(0.1, 0.4, fbmRot(seed ^ 0x6EE0_0005L, x * 4, y * 4, z * 1.5, 2));
                r = lerp(streak, 0.80, 0.70) * k; g = lerp(streak, 0.70, 0.58) * k; bl = lerp(streak, 0.46, 0.36) * k;
            }
            case ICE -> {
                // Low-albedo ice moons (Callisto, Ganymede) are dirty: blend toward dark silicate.
                double dirt = clamp((0.62 - b.albedo()) / 0.4, 0, 1);
                double tone = fbmRot(seed, x * 1.5, y * 1.5, z * 1.5, 3);
                double k = 0.9 + 0.2 * tone;
                r = lerp(dirt, 0.72, 0.26) * k; g = lerp(dirt, 0.77, 0.23) * k; bl = lerp(dirt, 0.82, 0.21) * (k + 0.05);
                double r1 = 1 - Math.abs(fbmRot(seed ^ 0x1CE0_0006L, x * 5, y * 5, z * 5, 1));
                double r2 = 1 - Math.abs(fbmRot(seed ^ 0x1CE0_0007L, x * 11, y * 11, z * 11, 1));
                double crack = 0.75 * smooth(0.90, 0.985, Math.max(r1, r2 * 0.98)) * (1 - 0.6 * dirt);
                r = lerp(crack, r, 0.38); g = lerp(crack, g, 0.24); bl = lerp(crack, bl, 0.15);
                if (dirt > 0) {
                    double kc = 1 + dirt * 1.2 * craters(seed ^ 0xC4A7_0008L, x * 6, y * 6, z * 6);
                    r *= kc; g *= kc; bl *= kc;
                }
            }
            case BARREN -> {
                double mare = smooth(-0.05, -0.30, fbmRot(seed, x * 1.2, y * 1.2, z * 1.2, 4));
                r = lerp(mare, 0.17, 0.075); g = lerp(mare, 0.16, 0.072); bl = lerp(mare, 0.15, 0.070);
                double k = (0.92 + 0.16 * fbmRot(seed ^ 0xBA4E_0009L, x * 8, y * 8, z * 8, 2))
                        * Math.max(0.2, 1 + craters(seed ^ 0xC4A7_000AL, x * 4, y * 4, z * 4)
                        + 0.6 * craters(seed ^ 0xC4A7_000BL, x * 11, y * 11, z * 11));
                r *= k; g *= k; bl *= k;
            }
            case LAVA -> {
                double tone = fbmRot(seed, x * 2.5, y * 2.5, z * 2.5, 3);
                double glow;
                if (b.albedo() > 0.4) { // Io: sulfur plains, SO₂ frost, dark hot paterae
                    double w = smooth(-0.3, 0.3, tone);
                    r = lerp(w, 0.62, 0.78); g = lerp(w, 0.40, 0.66); bl = lerp(w, 0.14, 0.32);
                    double frost = smooth(0.25, 0.45, fbmRot(seed ^ 0x50F2_000CL, x * 3, y * 3, z * 3, 2));
                    r = lerp(frost, r, 0.80); g = lerp(frost, g, 0.80); bl = lerp(frost, bl, 0.74);
                    double pat = smooth(-0.05, -0.25, craters(seed ^ 0xC4A7_000DL, x * 5, y * 5, z * 5));
                    r = lerp(pat, r, 0.04); g = lerp(pat, g, 0.03); bl = lerp(pat, bl, 0.025);
                    glow = 0.6 * pat * pat;
                } else {
                    double k = 0.8 + 0.4 * tone;
                    r = 0.040 * k; g = 0.035 * k; bl = 0.032 * k;
                    double c1 = 1 - Math.abs(fbmRot(seed ^ 0x1A7A_000EL, x * 4, y * 4, z * 4, 1));
                    double c2 = 1 - Math.abs(fbmRot(seed ^ 0x1A7A_000FL, x * 9, y * 9, z * 9, 1));
                    double crack = smooth(0.93, 0.995, Math.max(c1, c2 * 0.985));
                    double lake = smooth(0.38, 0.52, tone);
                    glow = Math.max(crack, lake);
                }
                er = LAVA_RGB[0] * glow * 0.9; eg = LAVA_RGB[1] * glow * 0.9; eb = LAVA_RGB[2] * glow * 0.9;
                r *= 1 - 0.7 * glow; g *= 1 - 0.7 * glow; bl *= 1 - 0.7 * glow;
            }
            case GAS_GIANT, HOT_JUPITER, ICE_GIANT, SUB_NEPTUNE -> {
                double w = fbmRot(seed, x * 2.2, y * 2.2, z * 3.0, 3);
                double f1 = 7 + (seed & 7), f2 = 15 + ((seed >>> 3) & 7), p2 = ((seed >>> 6) & 0xFF) / 40.0;
                double band = 0.5 + 0.5 * Math.sin(z * f1 + w * 1.6);
                double band2 = 0.5 + 0.5 * Math.sin(z * f2 + w * 2.6 + p2);
                double streak = 0.5 + 0.5 * Noise.value(seed ^ 0x57AE_0010L, z * 45 + w * 4, x * 0.8, y * 0.8);
                double tt = clamp(band * 0.75 + band2 * 0.28 + streak * 0.22 - 0.12, 0, 1);
                if (t == BodyType.GAS_GIANT) { // cream zones → tan → brown belts (Jupiter's NEB/SEB)
                    r = lerp3(tt, 0.82, 0.60, 0.34); g = lerp3(tt, 0.74, 0.40, 0.19); bl = lerp3(tt, 0.60, 0.24, 0.10);
                    double pole = 0.35 * smooth(0.75, 0.98, lat);
                    r = lerp(pole, r, 0.36); g = lerp(pole, g, 0.36); bl = lerp(pole, bl, 0.38);
                    double spot = ((seed >>> 20) & 3) != 0 ? oval(seed, x, y, z) : 0;
                    r = lerp(spot, r, 0.62); g = lerp(spot, g, 0.30); bl = lerp(spot, bl, 0.18);
                } else if (t == BodyType.HOT_JUPITER) {
                    r = lerp3(tt, 0.30, 0.20, 0.12); g = lerp3(tt, 0.15, 0.09, 0.05); bl = lerp3(tt, 0.08, 0.06, 0.035);
                    double belt = 0.7 + 0.3 * tt; // deeper, hotter layers show through the dark belts
                    er = o.hjR * belt; eg = o.hjG * belt; eb = o.hjB * belt;
                } else {
                    double k = t == BodyType.ICE_GIANT ? 0.92 + 0.08 * tt : 0.95 + 0.05 * tt;
                    if (t == BodyType.ICE_GIANT) { // Uranus cyan ↔ Neptune azure
                        double hue = ((seed >>> 24) & 0xFF) / 255.0;
                        r = lerp(hue, 0.40, 0.16) * k; g = lerp(hue, 0.68, 0.33) * k; bl = lerp(hue, 0.75, 0.75) * k;
                    } else {
                        r = 0.50 * k; g = 0.64 * k; bl = 0.62 * k;
                    }
                }
            }
            default -> { r = 0.3; g = 0.3; bl = 0.3; }
        }
        o.ar = r; o.ag = g; o.ab = bl;
        o.er = er; o.eg = eg; o.eb = eb;
        o.water = water;
    }

    /**
     * fBm of value noise with the lattice rotated by M before every octave (Quilez's rotated-octave
     * trick): kills the axis-aligned blockiness of trilinear value noise under steep thresholds.
     */
    private static double fbmRot(long seed, double x, double y, double z, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int i = 0; i < octaves; i++) {
            double nx = M00 * x + M01 * y + M02 * z, ny = M10 * x + M11 * y + M12 * z, nz = M20 * x + M21 * y + M22 * z;
            sum += amp * Noise.value(seed + i * 31L, nx, ny, nz);
            norm += amp;
            amp *= 0.5;
            x = nx * 2.03; y = ny * 2.03; z = nz * 2.03;
        }
        return sum / norm;
    }

    /**
     * Mean ring τ over radii [rho − w, rho + w], zero outside the rings: a box prefilter from the
     * prefix sums, so ringlets, gaps and fine structure narrower than a pixel average out instead of
     * aliasing into dotted dashes, and the edges antialias.
     */
    private static double ringTau(Scratch s, double rho, double w) {
        double lo = (rho - w - RING_INNER) * RING_SCALE, hi = (rho + w - RING_INNER) * RING_SCALE;
        if (hi <= 0 || lo >= RING_BINS) return 0;
        return (tauCum(s, hi) - tauCum(s, lo)) / (hi - lo);
    }

    private static double tauCum(Scratch s, double x) {
        if (x <= 0) return 0;
        if (x >= RING_BINS) return s.ringCum[RING_BINS];
        int i = (int) x;
        return s.ringCum[i] + (x - i) * s.ringTau[i];
    }

    /** Great-Red-Spot-like anticyclone at a seeded southern latitude: weight in [0, 1]. */
    private static double oval(long seed, double x, double y, double z) {
        long h = SeedRng.mix(seed ^ 0x6E5D_0011L);
        double sz = -0.30 - 0.15 * ((h & 0xFFFF) / 65535.0);
        double lon = ((h >>> 16) & 0xFFFF) / 65535.0 * 2 * Math.PI, c = Math.sqrt(1 - sz * sz);
        double sx = c * Math.cos(lon), sy = c * Math.sin(lon);
        double dz = z - sz;
        double horiz2 = Math.max(0, 2 * (1 - (x * sx + y * sy + z * sz)) - dz * dz);
        double ell = dz * dz / (0.055 * 0.055) + horiz2 / (0.12 * 0.12);
        return 0.8 * smooth(1.2, 0.5, ell);
    }

    /**
     * Worley-cell craters: albedo modulation, dark bowl floor (−0.3), bright raised rim (+0.25) and
     * faint ejecta. Radii up to 0.42 cell so a 3×3×3 neighbourhood covers every crater.
     */
    private static double craters(long seed, double x, double y, double z) {
        long cx = (long) Math.floor(x), cy = (long) Math.floor(y), cz = (long) Math.floor(z);
        double acc = 0;
        for (long i = cx - 1; i <= cx + 1; i++)
            for (long j = cy - 1; j <= cy + 1; j++)
                for (long k = cz - 1; k <= cz + 1; k++) {
                    long h = SeedRng.mix(seed ^ (i * 0x8DA6B343L) ^ (j * 0xD8163841L) ^ (k * 0xCB1AB31FL));
                    if ((h & 3) == 0) continue; // a quarter of cells empty: irregular spacing
                    double rad = 0.10 + 0.32 * sq(((h >>> 2) & 0x3F) / 63.0); // many small, few large
                    double dx = x - (i + ((h >>> 8) & 0xFFFF) / 65536.0);
                    double dy = y - (j + ((h >>> 24) & 0xFFFF) / 65536.0);
                    double dz = z - (k + ((h >>> 40) & 0xFFFF) / 65536.0);
                    double d2 = dx * dx + dy * dy + dz * dz, reach = rad * 2.2;
                    if (d2 > reach * reach) continue;
                    double t = Math.sqrt(d2) / rad;
                    if (t < 0.85) acc -= 0.30 * (1 - sq(t / 0.85));
                    acc += 0.25 * Math.exp(-sq((t - 1) / 0.12));
                    if (t > 1) acc += 0.06 * (2.2 - t) / 1.2;
                }
        return acc;
    }

    /** Radial τ and albedo profile, cached per body in the thread's scratch. */
    private static void buildRings(Body b, Scratch s) {
        long seed = b.terrainSeed(), key = seed * 31 + b.type().ordinal();
        if (s.ringKey == key) return;
        s.ringKey = key;
        SeedRng rng = new SeedRng(SeedRng.hash(seed, 0x4155L));
        boolean dark = b.type() == BodyType.ICE_GIANT;
        double j = 0.96 + 0.08 * rng.nextDouble();
        double cInner = 1.53 * j, cassini = 1.95 * j, aInner = 2.03 * j, encke = 2.214 * j;
        // Uranian-style narrow ringlets: radii and depths drawn once.
        double[] ringlet = new double[dark ? 9 : 0];
        for (int i = 0; i < ringlet.length; i++) ringlet[i] = RING_INNER + 0.05 + (RING_OUTER - RING_INNER - 0.1) * rng.nextDouble();
        for (int i = 0; i < RING_BINS; i++) {
            double rho = RING_INNER + (i + 0.5) / RING_BINS * (RING_OUTER - RING_INNER);
            double tau, cr, cg, cb;
            if (dark) { // Uranus: τ ~ 1e-3 dust between ringlets (Esposito 2002)
                tau = 0.001;
                for (double rl : ringlet) tau += 0.9 * Math.exp(-sq((rho - rl) / 0.004));
                cr = 0.05; cg = 0.05; cb = 0.055;
            } else if (rho < cInner) { // C ring
                tau = 0.08 + 0.05 * smooth(RING_INNER, cInner, rho);
                cr = 0.30; cg = 0.27; cb = 0.24;
            } else if (rho < cassini) { // B ring, densest
                tau = 1.1 + 1.0 * smooth(cInner, cInner + 0.12, rho);
                cr = 0.62; cg = 0.54; cb = 0.42;
            } else if (rho < aInner) { // Cassini division
                tau = 0.10;
                cr = 0.35; cg = 0.32; cb = 0.30;
            } else { // A ring with the Encke gap
                tau = 0.55 * smooth(0.002, 0.006, Math.abs(rho - encke)) * (1 - smooth(RING_OUTER - 0.03, RING_OUTER, rho));
                cr = 0.52; cg = 0.48; cb = 0.42;
            }
            double mod = 1 + 0.45 * Noise.value(seed ^ 0x41B6_0012L, rho * 55, 0.5, 0.5)
                    + 0.25 * Noise.value(seed ^ 0x41B6_0013L, rho * 260, 0.5, 0.5);
            s.ringTau[i] = (float) (tau * Math.max(0.05, mod));
            s.ringR[i] = (float) cr; s.ringG[i] = (float) cg; s.ringB[i] = (float) cb;
            s.ringCum[i + 1] = s.ringCum[i] + s.ringTau[i];
        }
    }

    private static double dot(double x, double y, double z, double[] v) {
        return x * v[0] + y * v[1] + z * v[2];
    }

    private static double dot(double[] u, double[] v) {
        return u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
    }

    private static double sq(double x) {
        return x * x;
    }

    /** NaN maps to {@code lo}. */
    private static double clamp(double x, double lo, double hi) {
        return x > hi ? hi : x > lo ? x : lo;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** Piecewise-linear through three stops at t = 0, ½, 1. */
    private static double lerp3(double t, double a, double b, double c) {
        return t < 0.5 ? lerp(t * 2, a, b) : lerp(t * 2 - 1, b, c);
    }

    /** Hermite smoothstep; edges may be given in either order. */
    private static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
