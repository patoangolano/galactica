package dev.galactica.deepsky;

/**
 * One volumetric element of a deep-sky object: a 3D Gaussian of emissivity and/or dust.
 *
 * <pre>  j(r) = L · G(r),   a_V(r) = D · G(r),   G(r) = exp(−½ rᵀΣ⁻¹r) / ((2π)^{3/2} √det Σ) </pre>
 *
 * so {@code L} is the luminosity emitted by the blob (L☉ per linear-sRGB channel) and {@code D} is
 * its integrated extinction (mag · pc²: the column A_V through the blob summed over its area).
 * Any profile can be written as a sum of such Gaussians (multi-Gaussian expansion, Emsellem,
 * Monnet &amp; Bacon 1994, A&amp;A 285, 723), and the line integral of one along a ray is analytic,
 * so nebulae, cluster glows and whole external galaxies are integrated exactly and without
 * aliasing, however small they are on the sky.
 *
 * @param glowOf for the unresolved-starlight glow of a cluster: the cluster, whose resolved
 *               fraction (stars drawn individually by the starfield) is removed per observer.
 */
public record Blob(double x, double y, double z,
                   double sxx, double sxy, double sxz, double syy, double syz, double szz,
                   double er, double eg, double eb, double dust, StarCluster glowOf) {

    /** Largest standard deviation bound (√trace ≥ √λ_max), pc. */
    public double maxSigma() {
        return Math.sqrt(sxx + syy + szz);
    }

    public boolean emits() {
        return er > 0 || eg > 0 || eb > 0;
    }

    /** Isotropic Gaussian of standard deviation σ. */
    public static Blob sphere(double[] c, double sigma, double[] rgb, double lum, double dust) {
        double v = sigma * sigma;
        return new Blob(c[0], c[1], c[2], v, 0, 0, v, 0, v, rgb[0] * lum, rgb[1] * lum, rgb[2] * lum, dust, null);
    }

    /**
     * Axisymmetric Gaussian: σ_axis along unit vector {@code a}, σ_perp across it. σ_axis &gt; σ_perp
     * gives a filament, σ_axis &lt; σ_perp a sheet (pancake) with {@code a} as its normal.
     */
    public static Blob axial(double[] c, double[] a, double sigmaAxis, double sigmaPerp, double[] rgb, double lum, double dust) {
        double p = sigmaPerp * sigmaPerp, k = sigmaAxis * sigmaAxis - p;
        return new Blob(c[0], c[1], c[2],
                p + k * a[0] * a[0], k * a[0] * a[1], k * a[0] * a[2],
                p + k * a[1] * a[1], k * a[1] * a[2],
                p + k * a[2] * a[2],
                rgb[0] * lum, rgb[1] * lum, rgb[2] * lum, dust, null);
    }

    /** Triaxial Gaussian with orthonormal axes u, v, w and standard deviations su, sv, sw. */
    public static Blob triaxial(double[] c, double[] u, double su, double[] v, double sv, double[] w, double sw,
                                double[] rgb, double lum, double dust) {
        double a = su * su, b = sv * sv, d = sw * sw;
        return new Blob(c[0], c[1], c[2],
                a * u[0] * u[0] + b * v[0] * v[0] + d * w[0] * w[0],
                a * u[0] * u[1] + b * v[0] * v[1] + d * w[0] * w[1],
                a * u[0] * u[2] + b * v[0] * v[2] + d * w[0] * w[2],
                a * u[1] * u[1] + b * v[1] * v[1] + d * w[1] * w[1],
                a * u[1] * u[2] + b * v[1] * v[2] + d * w[1] * w[2],
                a * u[2] * u[2] + b * v[2] * v[2] + d * w[2] * w[2],
                rgb[0] * lum, rgb[1] * lum, rgb[2] * lum, dust, null);
    }

    /** Same shape, tagged as the unresolved glow of {@code cluster}. */
    public Blob asGlowOf(StarCluster cluster) {
        return new Blob(x, y, z, sxx, sxy, sxz, syy, syz, szz, er, eg, eb, dust, cluster);
    }

    /**
     * Integrated extinction D (mag · pc²) of a dust Gaussian whose peak column, seen across its two
     * largest axes σ₁, σ₂, is {@code peakAv} magnitudes: A_peak = D / (2π σ₁ σ₂).
     */
    public static double dustFor(double peakAv, double sigma1, double sigma2) {
        return peakAv * 2 * Math.PI * sigma1 * sigma2;
    }
}
