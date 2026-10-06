package dev.galactica.science.spectra;

/**
 * One absorption feature: an atomic line or a molecular band, with a rest wavelength (nm, in air
 * for λ &lt; 2 µm as tabulated by NIST ASD) and a profile. Transmission multiplies the continuum.
 *
 * @param label    what to print next to the feature, e.g. "Hα", "O₂ A", "TiO"
 * @param restNm   line centre or band head, nm
 * @param depth    central depth, 0 (none) … 1 (black)
 * @param widthNm  Gaussian σ for {@link Shape#LINE} and {@link Shape#BAND}, e-folding length for {@link Shape#BANDHEAD}
 * @param shape    profile family
 * @param gas      molecule or element responsible (used for the planet "gases detected" list)
 */
public record Absorber(String label, double restNm, double depth, double widthNm, Shape shape, String gas) {

    public enum Shape {
        /** Symmetric Gaussian: atomic lines at the plot's resolution, Doppler + pressure broadened. */
        LINE,
        /** Broad symmetric molecular band (unresolved rotational structure). */
        BAND,
        /** Band head degraded to the red: sharp blue edge, exponential tail (TiO, CO overtone). */
        BANDHEAD
    }

    public Absorber {
        depth = Math.max(0, Math.min(0.98, depth));
        widthNm = Math.max(1e-3, widthNm);
    }

    /** Transmission (0..1) at wavelength λ when the feature is centred at {@code centreNm}. */
    public double transmission(double lambdaNm, double centreNm) {
        double x = lambdaNm - centreNm;
        double p = switch (shape) {
            case LINE, BAND -> {
                double u = x / widthNm;
                yield u * u > 50 ? 0 : Math.exp(-0.5 * u * u);
            }
            case BANDHEAD -> {
                if (x >= 0) yield x / widthNm > 25 ? 0 : Math.exp(-x / widthNm);
                double u = x / (0.12 * widthNm);
                yield u * u > 50 ? 0 : Math.exp(-0.5 * u * u);
            }
        };
        return 1 - depth * p;
    }

    /** Wavelength range (nm) outside which the feature is negligible, relative to its centre. */
    public double reachNm() {
        return shape == Shape.BANDHEAD ? 25 * widthNm : 8 * widthNm;
    }
}
