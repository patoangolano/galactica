package dev.galactica.deepsky;

import java.util.List;

/**
 * A reflection nebula: interstellar dust lit by nearby stars, computed as single scattering.
 *
 * <pre>  j_c(x) = ω · 0.921 a_V(x) · s_c · Σ_i L_i,c Φ_HG(cos θ_i) / |x − x_i|²  </pre>
 *
 * so the radiance along a ray, j ds / 4π, is the starlight flux L/(4πr²) times the scattering
 * coefficient times the phase function per steradian. a_V is the field's own dust (the same dust
 * dims the stars behind it), ω = 0.6 the V-band albedo and Φ the Henyey–Greenstein phase function
 * with g = 0.6 (Draine 2003, ARA&amp;A 41, 241: forward-scattering grains), θ_i the angle between
 * the light from star i and the direction to the observer — which is why a reflection nebula
 * brightens when seen with its stars behind it. s_c = (λ_V/λ_c)^1.5 makes the scattered light bluer
 * than the star (small-grain scattering efficiency in the optical, Draine 2003); with B-star light
 * it is the blue of the Pleiades' nebulosity.
 *
 * <p>The Pleiades' dust is a cloud passing through the cluster, not its birth cloud (Gibson &amp;
 * Nordsieck 2003, ApJ 589, 362; White &amp; Bally 1993), and it is streaked into parallel striations
 * by the encounter (the wisps of the Merope nebula, NGC 1435). The density here is a sheet,
 * clumpy on large scales and striated along the stream (local x) by anisotropic ridged noise.
 *
 * <p>{@link #REFLECTION_GAIN} plays the role {@link Morphology#LINE_GAIN} plays for emission
 * lines: the nebulosity is ~22 mag arcsec⁻² (too faint for the eye at any distance, since surface
 * brightness does not depend on distance) and the sky is shown with a long-exposure
 * photographic response.
 */
public final class ReflectionField extends NebulaField {
    /** Display gain of scattered light (photographic stretch), see the class comment. */
    public static final double REFLECTION_GAIN = 14.0;
    static final double ALBEDO = 0.6, G = 0.6;
    /** (λ_V / λ_c)^1.5 for the R, G, B channel centres 610, 550, 465 nm. */
    static final double SR = Math.pow(550.0 / 610, 1.5), SB = Math.pow(550.0 / 465, 1.5);

    /** A lighting star: galactocentric position, luminosity per channel (L☉, max-normalised colour × L_V). */
    public record Light(double x, double y, double z, double lr, double lg, double lb) {}

    private final Local frame;
    private final NebulaNoise noise;
    private final double ox, oy, oz;
    private final double sheetA, thickness;
    private final List<Light> lights;
    private final double softPc;
    // Observer (NaN when unbound: isotropic scattering).
    private final double eyeX, eyeY, eyeZ;

    /**
     * @param peakAv    A_V through the sheet's densest part, along local z
     * @param thickness sheet half-thickness (σ, local units)
     */
    public ReflectionField(long seed, Morphology.Frame f, double peakAv, double thickness, List<Light> lights) {
        this(new Local(f), f.c, seed, peakAv, thickness, lights, Double.NaN, 0, 0);
    }

    private ReflectionField(Local frame, double[] c, long seed, double peakAv, double thickness, List<Light> lights,
                            double ex, double ey, double ez) {
        super(c, 1.25 * frame.maxAxisPc);
        this.frame = frame;
        this.noise = NebulaNoise.shared(seed);
        double[] o = NebulaNoise.offset(seed);
        ox = o[0];
        oy = o[1];
        oz = o[2];
        this.thickness = thickness;
        this.sheetA = peakAv / (Math.sqrt(2 * Math.PI) * thickness * frame.scalePc);
        this.lights = List.copyOf(lights);
        this.softPc = 0.03 * frame.scalePc;
        eyeX = ex;
        eyeY = ey;
        eyeZ = ez;
        this.seed = seed;
        this.peakAv = peakAv;
    }

    private final long seed;
    private final double peakAv;

    @Override
    public NebulaField bind(double ex, double ey, double ez) {
        return new ReflectionField(frame, new double[] {cx, cy, cz}, seed, peakAv, thickness, lights, ex, ey, ez);
    }

    @Override
    int intervals(double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        return clipToEllipsoid(frame, 1.23, ox, oy, oz, dx, dy, dz, t);
    }

    @Override
    public double detailPc() {
        return 0.03 * frame.scalePc;
    }

    @Override
    public boolean hasDust() {
        return true;
    }

    @Override
    public double dust(double x, double y, double z, double lodPc) {
        double[] p = LOCAL.get();
        frame.toLocal(x, y, z, p);
        double px = p[0], py = p[1], pz = p[2];
        double rr = px * px + py * py;
        if (rr + pz * pz > 1.5) return 0;
        double unit = frame.scalePc;
        // Lateral extent with a ragged edge: the cloud is a patch of a larger sheet, not a rectangle.
        double edge = 1 + 0.6 * noise.fbm(px * 0.9 + oz, py * 0.9 + ox, 0.4, 3);
        double lat = Math.exp(-rr / (0.5 * edge * edge));
        double sh = Math.exp(-0.5 * pz * pz / (thickness * thickness));
        if (lat * sh < 1e-3) return 0;
        double fo = NebulaNoise.octavesFor(0.5 * unit, lodPc, 4);
        double clump = Math.exp(1.2 * noise.fbm(px * 1.6 + ox, py * 1.6 + oy, pz * 1.6 + oz, fo));
        // Striations: ridges stretched ~5× along the stream (local x).
        double so = NebulaNoise.octavesFor(0.12 * unit, lodPc, 5);
        double st = noise.ridged(px * 0.9 + oy, py * 5.5 + oz, pz * 5.5 + ox, so);
        return sheetA * lat * sh * clump * (0.25 + 1.5 * st * st);
    }

    @Override
    public void sample(double x, double y, double z, double lodPc, double[] out) {
        double a = dust(x, y, z, lodPc);
        out[3] = a;
        out[0] = out[1] = out[2] = 0;
        if (a <= 0) return;
        double k = ALBEDO * MAG_TO_TAU * a * REFLECTION_GAIN;
        boolean bound = !Double.isNaN(eyeX);
        double vx = 0, vy = 0, vz = 0;
        if (bound) {
            vx = eyeX - x;
            vy = eyeY - y;
            vz = eyeZ - z;
            double n = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (n > 0) {
                vx /= n;
                vy /= n;
                vz /= n;
            }
        }
        double r = 0, g = 0, b = 0;
        double s2 = softPc * softPc;
        for (int i = 0, n = lights.size(); i < n; i++) {
            Light l = lights.get(i);
            double lx = x - l.x(), ly = y - l.y(), lz = z - l.z();
            double d2 = lx * lx + ly * ly + lz * lz + s2;
            double phase;
            if (bound) {
                double inv = 1 / Math.sqrt(d2);
                phase = henyeyGreenstein((lx * vx + ly * vy + lz * vz) * inv);
            } else {
                phase = 1 / (4 * Math.PI);
            }
            double f = phase / d2;
            r += l.lr() * f;
            g += l.lg() * f;
            b += l.lb() * f;
        }
        out[0] = k * r * SR;
        out[1] = k * g;
        out[2] = k * b * SB;
    }

    /** Henyey–Greenstein phase function per steradian (∫ Φ dΩ = 1), cos θ between incoming and outgoing directions. */
    static double henyeyGreenstein(double cosTheta) {
        double g2 = G * G;
        double d = 1 + g2 - 2 * G * cosTheta;
        return (1 - g2) / (4 * Math.PI * d * Math.sqrt(d));
    }

    private static final ThreadLocal<double[]> LOCAL = ThreadLocal.withInitial(() -> new double[3]);
}
