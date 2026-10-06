package dev.galactica.science.spectra;

import java.util.ArrayList;
import java.util.List;

/**
 * A sampled spectrum from {@link #MIN_NM} to {@link #MAX_NM} (the optical + near-IR window of a
 * ground-like CCD/HgCdTe instrument), flux normalised to a peak of 1, plus the features to label.
 *
 * @param lambdaNm  sample wavelengths, nm, increasing
 * @param flux      F_λ at each sample, max 1 (all zero for a source with no photosphere)
 * @param lines     labelled features at their observed (Doppler-shifted) wavelengths
 * @param rvKms     radial velocity applied, km/s
 */
public record Spectrum(double[] lambdaNm, double[] flux, List<Marked> lines, double rvKms) {

    public static final double MIN_NM = 350, MAX_NM = 2500, STEP_NM = 1.0;
    /** Features shallower than this are drawn but not labelled. */
    public static final double LABEL_DEPTH = 0.07;

    /** A labelled feature: the label, the rest and observed wavelengths, and its depth in this spectrum. */
    public record Marked(String label, double restNm, double observedNm, double depth) {}

    public static double[] grid() {
        int n = (int) Math.round((MAX_NM - MIN_NM) / STEP_NM) + 1;
        double[] l = new double[n];
        for (int i = 0; i < n; i++) l[i] = MIN_NM + i * STEP_NM;
        return l;
    }

    /** Flux at λ by linear interpolation (0 outside the range). */
    public double fluxAt(double nm) {
        if (nm < MIN_NM || nm > MAX_NM) return 0;
        double f = (nm - MIN_NM) / STEP_NM;
        int i = Math.min(flux.length - 2, (int) f);
        double t = f - i;
        return flux[i] * (1 - t) + flux[i + 1] * t;
    }

    /** Index of the brightest sample: Wien's law makes this ≈ 2.898×10⁶ nm·K / T for a blackbody. */
    public double peakNm() {
        int best = 0;
        for (int i = 1; i < flux.length; i++) if (flux[i] > flux[best]) best = i;
        return lambdaNm[best];
    }

    public boolean dark() {
        for (double f : flux) if (f > 0) return false;
        return true;
    }

    /**
     * Builds a spectrum: continuum × Π transmission of every absorber, centres shifted by the
     * relativistic Doppler factor of {@code rvKms}, normalised to peak 1. {@code continuum} gives
     * F_λ in any unit at a rest wavelength.
     */
    public static Spectrum build(java.util.function.DoubleUnaryOperator continuum, List<Absorber> absorbers, double rvKms) {
        double[] l = grid();
        double[] f = new double[l.length];
        double k = Kinematics.dopplerFactor(rvKms);
        for (int i = 0; i < l.length; i++) f[i] = Math.max(0, continuum.applyAsDouble(l[i] / k));
        for (Absorber a : absorbers) {
            double c = a.restNm() * k, reach = a.reachNm();
            int lo = Math.max(0, (int) Math.floor((c - reach - MIN_NM) / STEP_NM));
            int hi = Math.min(l.length - 1, (int) Math.ceil((c + reach - MIN_NM) / STEP_NM));
            for (int i = lo; i <= hi; i++) f[i] *= a.transmission(l[i], c);
        }
        double max = 0;
        for (double v : f) max = Math.max(max, v);
        if (max > 0) for (int i = 0; i < f.length; i++) f[i] /= max;

        List<Marked> marks = new ArrayList<>();
        for (Absorber a : absorbers) {
            double c = a.restNm() * k;
            if (a.depth() >= LABEL_DEPTH && c >= MIN_NM && c <= MAX_NM) marks.add(new Marked(a.label(), a.restNm(), c, a.depth()));
        }
        marks.sort((x, y) -> Double.compare(x.observedNm(), y.observedNm()));
        return new Spectrum(l, f, List.copyOf(marks), rvKms);
    }

    /** Planck spectral radiance B_λ(T) in W·sr⁻¹·m⁻³ (λ in nm). Zero for T ≤ 0. */
    public static double planck(double lambdaNm, double t) {
        if (t <= 0) return 0;
        double lam = lambdaNm * 1e-9;
        double h = 6.62607015e-34, c = 299_792_458.0, kb = 1.380649e-23;
        double x = h * c / (lam * kb * t);
        if (x > 700) return 0;
        return 2 * h * c * c / Math.pow(lam, 5) / Math.expm1(x);
    }
}
