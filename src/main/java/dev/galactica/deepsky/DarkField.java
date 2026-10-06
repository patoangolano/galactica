package dev.galactica.deepsky;

/**
 * A dark molecular cloud as a continuous dust volume.
 *
 * <p>Molecular clouds are networks of filaments ~0.1 pc wide (André et al. 2014, PPVI; Arzoumanian
 * et al. 2011, A&amp;A 529, L6) inside a lower-density envelope, with a log-normal column density
 * distribution (Kainulainen et al. 2009). Here the envelope is a smooth ellipsoid modulated by
 * fBm, and the filaments are the crests of domain-warped ridged noise, sharpened by a power so most
 * of the volume is clear and the crests are opaque. Seen from afar they read as dark lanes and
 * rifts, as the Pipe or Taurus look in wide-field photographs, never as round specks.
 *
 * <p>{@code peakAv} is the extinction through a filament crest; {@code envelopeAv} through the
 * envelope centre (both along the cloud's shortest axis). Emits nothing.
 */
public final class DarkField extends NebulaField {
    private final Local frame;
    private final NebulaNoise noise;
    private final double ox, oy, oz;
    private final double filA, envA, filamentWidth;
    private final double bound = 1.3;

    /**
     * @param peakAv        A_V through a filament crest
     * @param envelopeAv    A_V through the envelope centre
     * @param filamentWidth crest width relative to the cloud (local units, ~0.06–0.2)
     */
    public DarkField(long seed, Morphology.Frame f, double peakAv, double envelopeAv, double filamentWidth) {
        super(f.c, 1.3 * new Local(f).maxAxisPc);
        this.frame = new Local(f);
        this.noise = NebulaNoise.shared(seed);
        double[] o = NebulaNoise.offset(seed);
        ox = o[0];
        oy = o[1];
        oz = o[2];
        this.filamentWidth = filamentWidth;
        double minAxis = minAxis(f);
        // A crest of local width w is ~w·unit thick; the envelope ~1.2 shortest axes deep.
        filA = peakAv / Math.max(1e-3, filamentWidth * 2 * frame.scalePc);
        envA = envelopeAv / Math.max(1e-3, 1.2 * minAxis);
    }

    private static double minAxis(Morphology.Frame f) {
        double mn = Double.MAX_VALUE;
        for (int j = 0; j < 3; j++) mn = Math.min(mn, Math.sqrt(f.m[0][j] * f.m[0][j] + f.m[1][j] * f.m[1][j] + f.m[2][j] * f.m[2][j]));
        return mn;
    }

    @Override
    int intervals(double ox, double oy, double oz, double dx, double dy, double dz, double[] t) {
        return clipToEllipsoid(frame, bound, ox, oy, oz, dx, dy, dz, t);
    }

    @Override
    public double detailPc() {
        return 0.4 * filamentWidth * frame.scalePc;
    }

    @Override
    public boolean hasDust() {
        return true;
    }

    @Override
    public boolean emits() {
        return false;
    }

    @Override
    public void sample(double x, double y, double z, double lodPc, double[] out) {
        out[0] = out[1] = out[2] = 0;
        out[3] = dust(x, y, z, lodPc);
    }

    @Override
    public double dust(double x, double y, double z, double lodPc) {
        double[] p = LOCAL.get();
        frame.toLocal(x, y, z, p);
        double px = p[0], py = p[1], pz = p[2];
        double r2 = px * px + py * py + pz * pz;
        if (r2 > bound * bound) return 0;
        double unit = frame.scalePc;
        double edge = smoothstep(bound * bound, 0.35, r2);
        if (edge <= 0) return 0;
        // Domain warp: bends the filaments into the curved, branching shapes of real clouds.
        double wo = NebulaNoise.octavesFor(0.6 * unit, lodPc, 3);
        double wx = px + 0.35 * noise.fbm(px * 1.2 + ox, py * 1.2 + oy, pz * 1.2 + oz, wo);
        double wy = py + 0.35 * noise.fbm(px * 1.2 + oy + 11, py * 1.2 + oz + 11, pz * 1.2 + ox + 11, wo);
        double wz = pz + 0.35 * noise.fbm(px * 1.2 + oz + 23, py * 1.2 + ox + 23, pz * 1.2 + oy + 23, wo);
        double fo = NebulaNoise.octavesFor(0.5 * unit, lodPc, 6);
        // Patchy envelope: a thresholded fBm field, so the cloud breaks into irregular lobes and
        // never reads as a round blob, fading over the outer half of the ellipsoid.
        double lobes = smoothstep(-0.35, 0.45, noise.fbm(wx * 1.3 + ox, wy * 1.3 + oy, wz * 1.3 + oz, Math.min(fo, 4)));
        double env = Math.exp(-1.2 * r2) * 1.6 * lobes;
        // Ridged crests ~filamentWidth wide at the first octave; the power keeps them narrow and the space between clear.
        double sharp = Math.max(1.5, 0.35 / filamentWidth);
        double crest = noise.ridged(wx * 1.4 + oy, wy * 1.4 + oz, wz * 1.4 + ox, fo);
        double fil = Math.pow(crest, sharp);
        return edge * (envA * env + filA * fil * Math.min(1.5, env * 1.4));
    }

    private static final ThreadLocal<double[]> LOCAL = ThreadLocal.withInitial(() -> new double[3]);
}
