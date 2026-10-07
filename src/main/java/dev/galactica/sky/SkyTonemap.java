package dev.galactica.sky;

import dev.galactica.science.Blackbody;

import java.util.Arrays;

/**
 * Maps baked HDR sky radiance to 8-bit sRGB the way a dark-adapted eye or a long camera exposure
 * sees it: exposure is anchored to the bright end of the band (99th percentile), a black point
 * at the sky's median keeps the inter-arm sky dark, and a logarithmic shoulder keeps the bulge
 * from clipping. Colour ratios are preserved by tonemapping luminance and rescaling RGB.
 *
 * <p><b>Highlights.</b> Nebulae reach far above the band: the Huygens region of M42 is
 * ~16 mag arcsec⁻², about 100× the Milky Way's brightest star clouds (~21 mag arcsec⁻²). Above
 * {@link #KNEE} the curve leaves the logarithm for a hyperbolic roll-off in log-luminance, matched
 * in value and slope, that approaches white only asymptotically: 10× the band's p99 still sits
 * ~8 % below white and 100× ~4 %, so a nebula keeps its internal structure instead of clipping to
 * a flat disc. A colour pushed past white in one channel keeps its hue and gives the excess
 * luminance back as white (as an over-exposed photograph's core), instead of shifting hue.
 */
public final class SkyTonemap {
    private final double exposure;
    private final double blackPoint;

    /** Exposure-normalised luminance where the highlight roll-off takes over from the logarithm. */
    static final double KNEE = 0.6;
    private static final double LOG7 = Math.log1p(6.0);
    private static final double M_KNEE = 0.85 * Math.log1p(6.0 * KNEE) / LOG7;
    /** dm / d ln x at the knee, so the roll-off continues the logarithm's slope. */
    private static final double S_KNEE = 0.85 * 6.0 * KNEE / ((1 + 6.0 * KNEE) * LOG7);

    private SkyTonemap(double exposure, double blackPoint) {
        this.exposure = exposure;
        this.blackPoint = blackPoint;
    }

    /** Derives exposure from an RGB float buffer (luminance of every third sample). */
    public static SkyTonemap fit(float[] rgb) {
        int n = rgb.length / 3;
        double[] lum = new double[n];
        for (int i = 0; i < n; i++) lum[i] = luminance(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]);
        Arrays.sort(lum);
        double median = lum[n / 2];
        return new SkyTonemap(1.0 / Math.max(1e-12, anchor(lum, n)), 0.75 * median);
    }

    /**
     * The luminance exposure is anchored to, from {@code n} sorted samples: the sky's 99th
     * percentile, or the 95th percentile of the pixels the galaxy actually covers, whichever is
     * brighter. Inside the disk the band covers most of the sky and the two agree (the p99 wins).
     * Seen from outside, from the halo or intergalactic space, the galaxy is a small patch of a
     * black sky: the sky's p99 then falls on its faint outskirts and the whole disc burned out to a
     * flat white lens. Surface brightness does not depend on distance, so the covered pixels'
     * own bright end keeps the exposure of an observer inside the disk. "Covered" means brighter
     * than 10⁻³ of the near-maximum (p99.9).
     */
    public static double anchor(double[] sortedLum, int n) {
        if (n <= 0) return 0;
        double p99 = sortedLum[Math.min(n - 1, (int) (n * 0.99))];
        double top = sortedLum[Math.min(n - 1, (int) (n * 0.999))];
        double floor = 1e-3 * top;
        // First covered sample (binary search in the sorted array).
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedLum[mid] < floor) lo = mid + 1;
            else hi = mid;
        }
        int covered = n - lo;
        double p95Covered = covered > 0 ? sortedLum[Math.min(n - 1, lo + (int) (covered * 0.95))] : 0;
        return Math.max(p99, p95Covered);
    }

    /** A tonemap with explicit parameters (exposure = 1 / the luminance shown near the knee). */
    public static SkyTonemap of(double exposure, double blackPoint) {
        return new SkyTonemap(exposure, blackPoint);
    }

    public double exposure() {
        return exposure;
    }

    public double blackPoint() {
        return blackPoint;
    }

    /**
     * Display value (before the contrast square) of exposure-normalised luminance x ≥ 0: the
     * logarithmic eye response below {@link #KNEE}, the hyperbolic highlight roll-off above.
     * Continuous with a continuous first derivative, strictly increasing, below 1 everywhere.
     */
    static double curve(double x) {
        if (x <= KNEE) return 0.85 * Math.log1p(6.0 * x) / LOG7;
        double head = 1 - M_KNEE;
        double l = Math.log(x / KNEE);
        return 1 - head / (1 + l * S_KNEE / head);
    }

    /** Returns 0xAARRGGBB, alpha = 255. */
    public int toArgb(double r, double g, double b) {
        double y = luminance(r, g, b);
        if (y <= blackPoint) return 0xFF000000;
        double lifted = (y - blackPoint) * exposure;
        double mapped = curve(lifted);
        mapped *= mapped; // contrast: keep the faint inter-arm sky near black
        double k = mapped / y;
        double rr = r * k, gg = g * k, bb = b * k;
        double mx = Math.max(rr, Math.max(gg, bb));
        if (mx > 1) {
            // Keep the hue at full brightness and return the clipped luminance as white.
            rr /= mx;
            gg /= mx;
            bb /= mx;
            double deficit = mapped - luminance(rr, gg, bb);
            if (deficit > 0) {
                double head = 1 - luminance(rr, gg, bb);
                double t = head > 1e-9 ? Math.min(1, deficit / head) : 0;
                rr += (1 - rr) * t;
                gg += (1 - gg) * t;
                bb += (1 - bb) * t;
            }
        }
        int ri = (int) Math.round(255 * Blackbody.encode(Math.min(1, rr)));
        int gi = (int) Math.round(255 * Blackbody.encode(Math.min(1, gg)));
        int bi = (int) Math.round(255 * Blackbody.encode(Math.min(1, bb)));
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    public static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
}
