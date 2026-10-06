package dev.galactica.sky;

import dev.galactica.science.Blackbody;

import java.util.Arrays;

/**
 * Maps baked HDR sky radiance to 8-bit sRGB the way a dark-adapted eye or a long camera exposure
 * sees it: exposure is anchored to the bright end of the band (99th percentile), a black point
 * at the sky's median keeps the inter-arm sky dark, and a logarithmic shoulder keeps the bulge
 * from clipping. Colour ratios are preserved by tonemapping luminance and rescaling RGB.
 */
public final class SkyTonemap {
    private final double exposure;
    private final double blackPoint;

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
        double p99 = Math.max(1e-12, lum[Math.min(n - 1, (int) (n * 0.99))]);
        return new SkyTonemap(1.0 / p99, 0.75 * median);
    }

    /** Returns 0xAARRGGBB, alpha = 255. */
    public int toArgb(double r, double g, double b) {
        double y = luminance(r, g, b);
        if (y <= blackPoint) return 0xFF000000;
        double lifted = (y - blackPoint) * exposure;
        // Logarithmic shoulder: the bulge is ~30× the band's faint edge, the eye compresses that ratio.
        double mapped = Math.min(1.0, Math.log1p(6.0 * lifted) / Math.log1p(6.0) * 0.85);
        mapped *= mapped; // contrast: keep the faint inter-arm sky near black
        double k = mapped / y;
        int ri = (int) Math.round(255 * Blackbody.encode(Math.min(1, r * k)));
        int gi = (int) Math.round(255 * Blackbody.encode(Math.min(1, g * k)));
        int bi = (int) Math.round(255 * Blackbody.encode(Math.min(1, b * k)));
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    public static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
}
