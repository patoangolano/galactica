package dev.galactica.deepsky;

import java.util.function.DoubleUnaryOperator;

/**
 * Multi-Gaussian expansion of a radial profile (Emsellem, Monnet &amp; Bacon 1994; Cappellari 2002,
 * MNRAS 333, 400): the profile is approximated by Σ_k w_k G_k(r) with normalised Gaussians of
 * fixed, logarithmically spaced widths and non-negative weights. The weights are found by
 * non-negative least squares with Lee–Seung multiplicative updates, on a relative-error metric so
 * the faint outskirts matter as much as the core.
 */
public final class Mge {
    /** Gaussian widths (same unit as the profile radius). */
    public final double[] sigma;
    /** Fraction of the total (luminosity or mass) carried by each Gaussian; sums to 1. */
    public final double[] weight;

    private Mge(double[] sigma, double[] weight) {
        this.sigma = sigma;
        this.weight = weight;
    }

    /**
     * Fits {@code profile(r)} on (rMin, rMax) with {@code n} Gaussians.
     *
     * @param dims 3 for a volume density ρ(r) (Gaussians normalised in 3D), 2 for a surface density
     */
    public static Mge fit(DoubleUnaryOperator profile, double rMin, double rMax, int n, int dims) {
        double[] sig = new double[n];
        for (int k = 0; k < n; k++) sig[k] = rMin * Math.pow(rMax / rMin, (k + 0.5) / n) * 0.8;
        int m = 72;
        double[] r = new double[m], b = new double[m];
        for (int i = 0; i < m; i++) {
            r[i] = rMin * 0.3 * Math.pow(rMax / (rMin * 0.3), i / (m - 1.0));
            b[i] = Math.max(0, profile.applyAsDouble(r[i]));
        }
        double[][] a = new double[m][n];
        double norm = dims == 3 ? Math.pow(2 * Math.PI, 1.5) : 2 * Math.PI;
        for (int i = 0; i < m; i++) {
            // Relative-error weighting: divide each row by its target (rows with zero target keep a floor).
            double w = 1.0 / Math.max(b[i], 1e-6 * b[0] + 1e-300);
            for (int k = 0; k < n; k++) {
                double s = sig[k];
                a[i][k] = Math.exp(-0.5 * r[i] * r[i] / (s * s)) / (norm * Math.pow(s, dims)) * w;
            }
            b[i] = b[i] > 0 ? 1.0 : 0.0;
        }
        double[] x = new double[n];
        // Start from a sensible scale: each Gaussian alone matching the profile at r ≈ σ.
        for (int k = 0; k < n; k++) x[k] = 1.0;
        double[] atb = new double[n];
        double[][] ata = new double[n][n];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < m; i++) atb[k] += a[i][k] * b[i];
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int i = 0; i < m; i++) s += a[i][k] * a[i][j];
                ata[k][j] = s;
            }
        }
        // Rescale the start so the fitted curve has the right overall level.
        double num = 0, den = 0;
        for (int k = 0; k < n; k++) {
            num += atb[k];
            for (int j = 0; j < n; j++) den += ata[k][j];
        }
        for (int k = 0; k < n; k++) x[k] = num / den;
        for (int it = 0; it < 4000; it++) {
            for (int k = 0; k < n; k++) {
                double ax = 0;
                for (int j = 0; j < n; j++) ax += ata[k][j] * x[j];
                x[k] = ax > 0 ? x[k] * atb[k] / ax : x[k];
            }
        }
        double sum = 0;
        for (double v : x) sum += v;
        double[] wgt = new double[n];
        for (int k = 0; k < n; k++) wgt[k] = x[k] / sum;
        return new Mge(sig, wgt);
    }

    /** Value of the normalised expansion at radius r (integrates to 1). */
    public double value(double r, int dims) {
        double norm = dims == 3 ? Math.pow(2 * Math.PI, 1.5) : 2 * Math.PI;
        double v = 0;
        for (int k = 0; k < sigma.length; k++) {
            double s = sigma[k];
            v += weight[k] * Math.exp(-0.5 * r * r / (s * s)) / (norm * Math.pow(s, dims));
        }
        return v;
    }
}
