package dev.galactica.client.map;

import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;

import java.util.stream.IntStream;

/**
 * The Galaxy Map background: the Milky Way seen face-on from the north galactic pole. Each pixel
 * is an orthographic ray cast straight down through the disk with {@link GalaxySkyBaker#march}, so
 * it holds the surface brightness of that column (emission attenuated by the dust in front of it,
 * which is what darkens the inner arms) and the map is linear in parsecs, unlike a perspective
 * view from a single point.
 *
 * <p>Tonemapping uses {@link SkyTonemap}, but fitted on the galaxy's own pixels: a face-on square
 * is mostly the faint outer disk, and a black point at the median of every pixel would erase
 * everything beyond the solar circle.
 *
 * <p>Layout: column i ↔ galactocentric y, row j ↔ x, both from −{@link MapView#BACKGROUND_HALF_PC}
 * (see {@link MapView}). A vertical ray crosses the disk in ~150 steps: measured 384² in 0.46 s
 * on 8 cores, so the 1024² bake is ~3 s, done once on a worker and cached as a PNG.
 */
public final class FaceOnBake {
    private FaceOnBake() {}

    public static final int SIZE = 1024;
    /**
     * Height the rays start from, pc. Above ~6 kpc only the halo remains, whose column through
     * the disk is ~10⁻³ of the disk's own, so starting higher only costs steps.
     */
    static final double TOP_PC = 6_000.0;

    public static double pixelCentrePc(int i, int size) {
        return -MapView.BACKGROUND_HALF_PC + (i + 0.5) * (2 * MapView.BACKGROUND_HALF_PC / size);
    }

    /** Linear HDR column brightness, size × size × 3 floats, rows in parallel. */
    public static float[] bakeHdr(long seed, int size) {
        GalaxySkyBaker baker = new GalaxySkyBaker(seed);
        float[] out = new float[size * size * 3];
        IntStream.range(0, size).parallel().forEach(j -> {
            double[] rgb = new double[3];
            double gx = pixelCentrePc(j, size);
            for (int i = 0; i < size; i++) {
                baker.march(gx, pixelCentrePc(i, size), TOP_PC, 0, 0, -1, rgb);
                int k = (j * size + i) * 3;
                out[k] = (float) rgb[0];
                out[k + 1] = (float) rgb[1];
                out[k + 2] = (float) rgb[2];
            }
        });
        return out;
    }

    /** 0xAARRGGBB pixels, row-major. */
    public static int[] tonemap(float[] hdr) {
        int n = hdr.length / 3;
        // Fit on the disk only: columns brighter than 0.2 % of the peak are "the galaxy".
        double peak = 0;
        for (int i = 0; i < n; i++) peak = Math.max(peak, SkyTonemap.luminance(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]));
        // The disk samples are followed by as many black ones, so SkyTonemap's black point
        // (0.75 × median) lands at the disk's faint end instead of mid-disk, and its 99th
        // percentile is the disk's 98th: the bulge saturates, the arms keep their contrast.
        float[] fit = new float[hdr.length * 2];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (SkyTonemap.luminance(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]) > 0.002 * peak) {
                System.arraycopy(hdr, i * 3, fit, m * 3, 3);
                m++;
            }
        }
        SkyTonemap tm = SkyTonemap.fit(java.util.Arrays.copyOf(fit, Math.max(6, m * 6)));
        int[] argb = new int[n];
        for (int i = 0; i < n; i++) argb[i] = tm.toArgb(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]);
        return argb;
    }

    public static int[] bake(long seed, int size) {
        return tonemap(bakeHdr(seed, size));
    }
}
