package dev.galactica.star;

/** Harvard spectral classification from effective temperature (Pecaut &amp; Mamajek 2013 boundaries). */
public final class SpectralClass {
    private SpectralClass() {}

    private static final char[] LETTERS = {'O', 'B', 'A', 'F', 'G', 'K', 'M', 'L', 'T'};
    // Temperature at subclass 0 of each letter, and at subclass 0 of the next letter.
    private static final double[] T0 = {50_000, 31_400, 9_700, 7_220, 5_920, 5_280, 3_850, 2_270, 1_300, 500};

    public static String of(double teff) {
        for (int i = 0; i < LETTERS.length; i++) {
            if (teff >= T0[i + 1] || i == LETTERS.length - 1) {
                double hi = T0[i], lo = T0[i + 1];
                double sub = 10.0 * (hi - Math.min(hi, teff)) / (hi - lo);
                sub = Math.max(0, Math.min(9.5, Math.floor(sub * 2) / 2));
                String s = sub == Math.floor(sub) ? Integer.toString((int) sub) : Double.toString(sub);
                return LETTERS[i] + s;
            }
        }
        return "T9";
    }

    public static char letter(double teff) {
        return of(teff).charAt(0);
    }

    /** V-band bolometric correction (Flower 1996 polynomial fit, Torres 2010 coefficients). */
    public static double bolometricCorrection(double teff) {
        double lt = Math.log10(Math.max(2000, Math.min(60_000, teff)));
        double[] c;
        if (lt < 3.70) {
            c = new double[] {-0.190537291496456e5, 0.155144866764412e5, -0.421278819301717e4, 0.381476328422343e3};
        } else if (lt < 3.90) {
            c = new double[] {-0.370510203809015e5, 0.385672629965804e5, -0.150651486316025e5,
                    0.261724637119416e4, -0.170623810323864e3};
        } else {
            c = new double[] {-0.118115450538963e6, 0.137145973583929e6, -0.636233812100225e5,
                    0.147412923562646e5, -0.170587278406872e4, 0.788731721804990e2};
        }
        double bc = 0, p = 1;
        for (double k : c) {
            bc += k * p;
            p *= lt;
        }
        return bc;
    }
}
