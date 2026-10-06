package dev.galactica.sky;

import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.science.Noise;

/**
 * Bakes the diffuse light of the Milky Way as seen from an arbitrary point in the galaxy into an
 * HDR cubemap, by ray-marching the {@link GalaxyModel} along every pixel direction:
 *
 * <pre>  I_c(dir) = ∫ j_c(s) · exp(−τ_c(s)) ds,   τ_c(s) = ∫ k_c·A_V(s') ds' </pre>
 *
 * where j is the luminosity density of the old disk, young arm population, bulge and halo (each
 * with its own colour), plus Hα emission from H II regions, and τ is dust optical depth with the
 * Cardelli (1989) R_V = 3.1 extinction law per channel (blue dimmed more than red → reddening).
 * Dust density is modulated by fBm noise, which carves filaments and rifts like the Great Rift.
 *
 * <p>Individually rendered stars (closer than {@link #START_PC}) are excluded so light is not
 * counted twice. Output is linear sRGB in L☉ pc⁻² sr⁻¹ (exposure is applied by the renderer).
 * Runs off-thread (~45 µs per ray): a 2048 × 1024 equirectangular bake is ~2 s across 8 cores.
 */
public final class GalaxySkyBaker {
    public static final double START_PC = 25.0;
    public static final double END_PC = 60_000.0;
    private static final int STEPS = 160;
    private static final int MAX_STEPS = 700;

    /** A_λ / A_V for the R, G, B channel centres (~610, 550, 465 nm), CCM89 with R_V = 3.1. */
    private static final double[] EXTINCTION_RATIO = {0.80, 1.00, 1.30};
    private static final double MAG_TO_TAU = 0.4 * Math.log(10); // τ = 0.921 · A

    // Linear-sRGB colours of each population (blackbody of the population's light-weighted Teff).
    private static final double[] OLD_DISK = {1.00, 0.86, 0.72};   // ~4 800 K
    private static final double[] YOUNG = {0.70, 0.80, 1.00};      // ~12 000 K
    private static final double[] BULGE = {1.00, 0.78, 0.58};      // ~4 200 K
    private static final double[] H_ALPHA = {1.00, 0.18, 0.30};    // 656 nm with a little Hβ

    /** Mean V-band luminosity per star (L☉) for each population. Local disk: ~0.05 L☉ pc⁻³ at 0.095 stars pc⁻³. */
    private static final double L_OLD = 0.55, L_BULGE = 0.4, L_YOUNG = 60.0, L_HALPHA = 4.0;

    private final long seed;

    public GalaxySkyBaker(long seed) {
        this.seed = seed;
    }

    /**
     * Fills {@code out} (6 faces × size × size × 3 floats, face order +X, −X, +Y, −Y, +Z, −Z in
     * Minecraft axes) for an observer at galactocentric (ox, oy, oz) parsecs.
     */
    public void bake(double ox, double oy, double oz, int size, float[] out) {
        double[] dir = new double[3];
        double[] rgb = new double[3];
        for (int face = 0; face < 6; face++) {
            for (int v = 0; v < size; v++) {
                for (int u = 0; u < size; u++) {
                    CubeMath.faceDirection(face, (u + 0.5) / size * 2 - 1, (v + 0.5) / size * 2 - 1, dir);
                    // Minecraft (x, y, z) with y up → galactic frame (x, −z, y) with z towards the NGP.
                    march(ox, oy, oz, dir[0], -dir[2], dir[1], rgb);
                    int i = ((face * size + v) * size + u) * 3;
                    out[i] = (float) rgb[0];
                    out[i + 1] = (float) rgb[1];
                    out[i + 2] = (float) rgb[2];
                }
            }
        }
    }

    /**
     * Equirectangular bake in Minecraft axes ({@link #equirectDirection}), rows in parallel.
     * {@code out} holds width × height × 3 floats.
     */
    public void bakeEquirect(double ox, double oy, double oz, int width, int height, float[] out) {
        java.util.stream.IntStream.range(0, height).parallel().forEach(j -> {
            double[] dir = new double[3];
            double[] rgb = new double[3];
            for (int i = 0; i < width; i++) {
                equirectDirection((i + 0.5) / width, (j + 0.5) / height, dir);
                march(ox, oy, oz, dir[0], -dir[2], dir[1], rgb);
                int k = (j * width + i) * 3;
                out[k] = (float) rgb[0];
                out[k + 1] = (float) rgb[1];
                out[k + 2] = (float) rgb[2];
            }
        });
    }

    /**
     * u ∈ [0,1) is longitude around +Y starting at −Z (north in Minecraft), v ∈ [0,1] runs from the
     * zenith (+Y) to the nadir. The sky sphere mesh uses the same mapping for its UVs.
     */
    public static void equirectDirection(double u, double v, double[] out) {
        double phi = u * 2 * Math.PI;
        double lat = Math.PI / 2 - v * Math.PI;
        double c = Math.cos(lat);
        out[0] = c * Math.sin(phi);
        out[1] = Math.sin(lat);
        out[2] = -c * Math.cos(phi);
    }

    /**
     * Integrates one ray. Steps grow geometrically (fine nearby, coarse far away) but are also capped
     * so the ray never moves more than a quarter of the local disk height per step: without that, an
     * observer outside the disk crosses the ~600 pc slab in one or two samples and the image breaks
     * into concentric aliasing rings.
     */
    public void march(double ox, double oy, double oz, double dx, double dy, double dz, double[] rgb) {
        double tauR = 0, tauG = 0, tauB = 0, r = 0, g = 0, b = 0;
        double growth = Math.pow(END_PC / START_PC, 1.0 / STEPS) - 1;
        double adz = Math.max(1e-6, Math.abs(dz));
        double s0 = START_PC;
        for (int i = 0; i < MAX_STEPS && s0 < END_PC; i++) {
            double zNow = oz + dz * s0;
            double ds = Math.min(s0 * growth, 0.25 * Math.max(Math.abs(zNow), 100.0) / adz);
            ds = Math.max(ds, 2.0);
            double s = s0 + 0.5 * ds;
            s0 += ds;
            double x = ox + dx * s, y = oy + dy * s, z = oz + dz * s;
            if (Math.abs(z) > 12_000 || x * x + y * y > 9.0e8) {
                if (dz * z > 0) break; // leaving the galaxy for good
                continue;
            }
            double total = GalaxyModel.starDensity(x, y, z);
            double bulge = GalaxyModel.bulgeDensity(x, y, z);
            double halo = GalaxyModel.haloDensity(x, y, z);
            double disk = Math.max(0, total - bulge - halo);
            double young = GalaxyModel.youngFraction(x, y, z) * disk;

            // Molecular clouds: dust is filamentary, so fBm on ~120 pc scales is cubed into thin dark lanes
            // (mean factor ≈ 1, but most of the volume is clear and a few clouds are opaque).
            double av = GalaxyModel.dustExtinctionPerPc(x, y, z);
            if (av > 1e-7) {
                double n = 0.5 + 0.5 * Noise.fbm(seed, x / 120.0, y / 120.0, z / 45.0, 5);
                av *= 0.08 + 6.5 * n * n * n;
            }
            // Star clouds: the disk's light is clumpy on ~400 pc scales (Scutum and Sagittarius star clouds).
            if (disk > 1e-6) {
                double c = 0.5 + 0.5 * Noise.fbm(seed ^ 0xC10DL, x / 400.0, y / 400.0, z / 200.0, 3);
                disk *= 0.45 + 1.1 * c;
                young *= 0.2 + 1.6 * c;
            }
            double hii = 0;
            if (young > 1e-6) {
                double n = Noise.fbm(seed ^ 0x4811L, x / 90.0, y / 90.0, z / 40.0, 3);
                hii = young * Math.max(0, n - 0.15) * 4.0;
            }

            double jr = disk * L_OLD * OLD_DISK[0] + (bulge + halo) * L_BULGE * BULGE[0] + young * L_YOUNG * YOUNG[0] + hii * L_HALPHA * H_ALPHA[0];
            double jg = disk * L_OLD * OLD_DISK[1] + (bulge + halo) * L_BULGE * BULGE[1] + young * L_YOUNG * YOUNG[1] + hii * L_HALPHA * H_ALPHA[1];
            double jb = disk * L_OLD * OLD_DISK[2] + (bulge + halo) * L_BULGE * BULGE[2] + young * L_YOUNG * YOUNG[2] + hii * L_HALPHA * H_ALPHA[2];

            // Emission inside the step is attenuated by half the step's own dust (midpoint rule).
            double dt = av * ds * MAG_TO_TAU;
            double fR = Math.exp(-(tauR + 0.5 * dt * EXTINCTION_RATIO[0]));
            double fG = Math.exp(-(tauG + 0.5 * dt * EXTINCTION_RATIO[1]));
            double fB = Math.exp(-(tauB + 0.5 * dt * EXTINCTION_RATIO[2]));
            // Surface brightness per steradian: j·ds / (4π) — the 1/s² of distance cancels the s² of the beam.
            double k = ds / (4 * Math.PI);
            r += jr * k * fR;
            g += jg * k * fG;
            b += jb * k * fB;
            tauR += dt * EXTINCTION_RATIO[0];
            tauG += dt * EXTINCTION_RATIO[1];
            tauB += dt * EXTINCTION_RATIO[2];
            if (tauR > 12) break;
        }
        rgb[0] = r;
        rgb[1] = g;
        rgb[2] = b;
    }
}
