package dev.galactica.science;

/**
 * Colour of a blackbody of temperature T as seen by a human eye, in linear sRGB.
 * Planck's law is integrated against the CIE 1931 2° observer (analytic multi-lobe fit of
 * Wyman, Sloan &amp; Shirley 2013), converted XYZ → linear sRGB (D65) and normalised to max = 1.
 * Results are cached per 50 K bucket because stars query this constantly.
 */
public final class Blackbody {
    private Blackbody() {}

    private static final int BUCKET_K = 50;
    private static final int MAX_K = 60_000;
    private static final float[] CACHE = new float[(MAX_K / BUCKET_K + 1) * 3];

    static {
        for (int i = 0; i <= MAX_K / BUCKET_K; i++) {
            double[] rgb = computeLinearRgb(Math.max(500, i * BUCKET_K));
            CACHE[i * 3] = (float) rgb[0];
            CACHE[i * 3 + 1] = (float) rgb[1];
            CACHE[i * 3 + 2] = (float) rgb[2];
        }
    }

    /** Linear sRGB, max component 1. Writes into {@code out} (length ≥ 3). */
    public static void linearRgb(double kelvin, float[] out) {
        int i = (int) Math.round(Math.min(MAX_K, Math.max(500, kelvin)) / BUCKET_K);
        out[0] = CACHE[i * 3];
        out[1] = CACHE[i * 3 + 1];
        out[2] = CACHE[i * 3 + 2];
    }

    /** Packed 0xRRGGBB in gamma-encoded sRGB, for UI and debug output. */
    public static int srgbPacked(double kelvin) {
        float[] c = new float[3];
        linearRgb(kelvin, c);
        int r = (int) Math.round(255 * encode(c[0]));
        int g = (int) Math.round(255 * encode(c[1]));
        int b = (int) Math.round(255 * encode(c[2]));
        return (r << 16) | (g << 8) | b;
    }

    public static double encode(double linear) {
        double v = Math.max(0, Math.min(1, linear));
        return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }

    static double[] computeLinearRgb(double t) {
        double x = 0, y = 0, z = 0;
        for (int nm = 380; nm <= 780; nm += 5) {
            double p = planck(nm * 1e-9, t);
            x += p * cieX(nm);
            y += p * cieY(nm);
            z += p * cieZ(nm);
        }
        double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        // Very cool/hot bodies fall outside the sRGB gamut; clip negatives, keep chromaticity.
        r = Math.max(0, r);
        g = Math.max(0, g);
        b = Math.max(0, b);
        double m = Math.max(r, Math.max(g, b));
        return m > 0 ? new double[] {r / m, g / m, b / m} : new double[] {1, 0, 0};
    }

    /** Spectral radiance B(λ, T); only the shape matters here. */
    static double planck(double lambda, double t) {
        double a = 2.0 * Units.H_PLANCK * Units.C * Units.C / Math.pow(lambda, 5);
        double e = Units.H_PLANCK * Units.C / (lambda * Units.K_BOLTZMANN * t);
        return a / Math.expm1(Math.min(e, 700));
    }

    private static double lobe(double nm, double mu, double s1, double s2) {
        double t = (nm - mu) / (nm < mu ? s1 : s2);
        return Math.exp(-0.5 * t * t);
    }

    static double cieX(double nm) {
        return 1.056 * lobe(nm, 599.8, 37.9, 31.0) + 0.362 * lobe(nm, 442.0, 16.0, 26.7)
                - 0.065 * lobe(nm, 501.1, 20.4, 26.2);
    }

    static double cieY(double nm) {
        return 0.821 * lobe(nm, 568.8, 46.9, 40.5) + 0.286 * lobe(nm, 530.9, 16.3, 31.1);
    }

    static double cieZ(double nm) {
        return 1.217 * lobe(nm, 437.0, 11.8, 36.0) + 0.681 * lobe(nm, 459.0, 26.0, 13.8);
    }
}
