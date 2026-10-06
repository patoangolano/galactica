package dev.galactica.science.spectra;

import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic low-resolution stellar spectra: a Planck continuum at T_eff times the absorption
 * features that define the MK classification, with strengths that follow temperature the way the
 * classification sequence does (Gray &amp; Corbally 2009, <i>Stellar Spectral Classification</i>,
 * ch. 2–7; Jaschek &amp; Jaschek 1987; Kirkpatrick et al. 1991, ApJS 77, 417 for M dwarfs):
 * <ul>
 *   <li><b>Balmer/Paschen/Brackett H I</b> peak at A0–A2 (~9 500 K) where n = 2 is most populated,
 *       fading towards O (ionised) and M (n = 2 empty). Low-gravity giants and supergiants have
 *       narrower wings (the luminosity criterion of A and B stars).</li>
 *   <li><b>He I</b> peaks at B2 (~20 000 K); <b>He II</b> appears only in O stars (&gt; 29 000 K).</li>
 *   <li><b>Ca II H&amp;K</b> and the near-IR triplet grow from late A and dominate G–K; <b>Fe I</b>,
 *       <b>Mg b</b> and the CH <b>G band</b> peak in G–K; <b>Na D</b> and <b>K I</b> in K–M. Metal
 *       lines scale as 10^(0.4·[Fe/H]) (square-root regime of the curve of growth).</li>
 *   <li><b>TiO</b> band heads (476–886 nm) define the M class; <b>FeH</b> 989.6 nm (Wing–Ford band)
 *       marks M dwarfs; <b>H₂O</b> 1.4/1.9 µm late M; the <b>CO</b> first overtone 2.29 µm cool
 *       stars, strongest in giants.</li>
 *   <li>White dwarfs: DA show only hugely pressure-broadened Balmer lines, DC are featureless;
 *       neutron stars show a bare Rayleigh–Jeans tail; black holes have no photosphere.</li>
 * </ul>
 * Rest wavelengths are NIST ASD air values (Kramida et al. 2023) and Pickles (1998, PASP 110, 863)
 * band heads. Depths are central depths at this instrument's ~1 nm resolution, tuned so equivalent
 * widths have the right size (Hγ ≈ 2 nm at A0, Ca II K ≈ 2 nm in the Sun).
 */
public final class StellarSpectrum {
    private StellarSpectrum() {}

    /** Synthetic spectrum of {@code s} seen with radial velocity {@code rvKms}. */
    public static Spectrum of(Star s, double rvKms) {
        double t = s.kind() == StarKind.BLACK_HOLE ? 0 : s.temperature();
        return Spectrum.build(l -> Spectrum.planck(l, t), absorbers(s), rvKms);
    }

    /** Every absorption feature the star shows, rest wavelengths. */
    public static List<Absorber> absorbers(Star s) {
        List<Absorber> out = new ArrayList<>();
        double t = s.temperature();
        switch (s.kind()) {
            case BLACK_HOLE, NEUTRON_STAR -> {
                return out;
            }
            case WHITE_DWARF -> {
                if (t > 12_000) {
                    // DA: surface gravity 10^8 cm/s² Stark-broadens Balmer lines to tens of Å.
                    String[] n = {"Hα", "Hβ", "Hγ", "Hδ"};
                    double[] w = {656.28, 486.13, 434.05, 410.17};
                    for (int i = 0; i < n.length; i++) out.add(new Absorber(n[i], w[i], 0.55, 3.5, Absorber.Shape.LINE, "H"));
                }
                return out;
            }
            default -> {
            }
        }
        double gScale = switch (s.kind()) {
            case SUPERGIANT -> 0.5;
            case GIANT -> 0.7;
            default -> 1.0;
        };
        double metals = Math.max(0.05, Math.min(3.0, Math.pow(10, 0.4 * s.metallicity())));

        // Hydrogen.
        double h = peak(t, 9_500, 0.13, 0.20);
        if (h > 0.003) {
            double d = 0.75 * Math.sqrt(h), w = (0.25 + 1.1 * h) * gScale;
            out.add(line("Hα", 656.28, d, w, "H"));
            out.add(line("Hβ", 486.13, d, w, "H"));
            out.add(line("Hγ", 434.05, d, w, "H"));
            out.add(line("Hδ", 410.17, d * 0.95, w, "H"));
            out.add(line("Hε", 397.01, d * 0.85, w, "H"));
            double dp = 0.5 * Math.sqrt(h), wp = (0.4 + 1.5 * h) * gScale;
            out.add(line("Paδ", 1004.94, dp * 0.8, wp, "H"));
            out.add(line("Paγ", 1093.81, dp * 0.9, wp, "H"));
            out.add(line("Paβ", 1281.81, dp, wp, "H"));
            out.add(line("Brγ", 2166.12, 0.4 * Math.sqrt(h), (0.5 + 1.5 * h) * gScale, "H"));
        }

        // Helium.
        if (t > 9_000) {
            double he = peak(t, 20_000, 0.07, 0.10);
            double d = 0.35 * Math.sqrt(he), w = 0.2 + 0.2 * he;
            out.add(line("He I", 447.15, d, w, "He"));
            out.add(line("He I", 587.56, d, w, "He"));
            out.add(line("He I", 667.82, d * 0.8, w, "He"));
            out.add(line("He I", 1083.03, d * 1.1, w * 1.5, "He"));
        }
        double heII = clamp((t - 29_000) / 15_000);
        if (heII > 0) {
            out.add(line("He II", 468.57, 0.30 * heII, 0.3, "He"));
            out.add(line("He II", 541.15, 0.22 * heII, 0.3, "He"));
            out.add(line("He II", 1012.36, 0.18 * heII, 0.4, "He"));
        }

        // Ionised calcium: H & K and the infrared triplet.
        double ca = peak(t, 4_800, 0.12, 0.12) * metals;
        if (ca > 0.003) {
            double d = Math.min(0.95, 0.9 * Math.sqrt(ca)), w = 0.3 + 0.9 * Math.min(1, ca);
            out.add(line("Ca II K", 393.37, d, w, "Ca"));
            out.add(line("Ca II H", 396.85, d * 0.95, w, "Ca"));
            double dt = 0.6 * Math.sqrt(ca), wt = 0.25 + 0.4 * Math.min(1, ca);
            out.add(line("Ca II", 849.80, dt * 0.7, wt, "Ca"));
            out.add(line("Ca II", 854.21, dt, wt, "Ca"));
            out.add(line("Ca II", 866.21, dt * 0.9, wt, "Ca"));
        }

        // Neutral metals.
        double fe = peak(t, 4_800, 0.10, 0.10) * metals;
        if (fe > 0.003) {
            double d = 0.5 * Math.sqrt(fe);
            out.add(line("Fe I", 438.35, d, 0.2, "Fe"));
            out.add(line("Fe I", 495.76, d * 0.8, 0.2, "Fe"));
            out.add(line("Fe I", 527.04, d * 0.9, 0.2, "Fe"));
            double mg = peak(t, 4_500, 0.09, 0.10) * metals;
            out.add(line("Mg b", 517.27, 0.6 * Math.sqrt(mg), 0.25, "Mg"));
            out.add(line("Mg b", 518.36, 0.6 * Math.sqrt(mg), 0.25, "Mg"));
            double ch = peak(t, 5_000, 0.06, 0.07) * metals;
            out.add(new Absorber("G band", 430.5, 0.45 * Math.sqrt(ch), 0.8, Absorber.Shape.BAND, "CH"));
        }
        double na = peak(t, 3_800, 0.10, 0.10) * metals;
        if (na > 0.003) {
            double d = 0.8 * Math.sqrt(na), w = 0.15 + 0.3 * Math.min(1, na);
            out.add(line("Na D", 589.00, d, w, "Na"));
            out.add(line("Na D", 589.59, d * 0.9, w, "Na"));
            double k = peak(t, 3_000, 0.08, 0.08) * metals;
            out.add(line("K I", 766.49, 0.7 * Math.sqrt(k), 0.4 + 1.2 * Math.min(1, k), "K"));
            out.add(line("K I", 769.90, 0.65 * Math.sqrt(k), 0.4 + 1.2 * Math.min(1, k), "K"));
        }

        // Molecules of cool atmospheres.
        double tio = Math.pow(clamp((4_000 - t) / 1_000), 0.8);
        if (tio > 0) {
            for (double head : new double[] {476.1, 495.4, 516.7, 544.8, 615.9, 705.3, 843.2, 886.0}) {
                out.add(new Absorber("TiO", head, 0.75 * tio, 12, Absorber.Shape.BANDHEAD, "TiO"));
            }
            if (s.kind() == StarKind.MAIN_SEQUENCE) {
                out.add(new Absorber("FeH", 989.6, 0.3 * tio, 8, Absorber.Shape.BANDHEAD, "FeH"));
            }
        }
        double water = clamp((3_300 - t) / 800);
        if (water > 0) {
            out.add(new Absorber("H₂O", 1400, 0.5 * water, 60, Absorber.Shape.BAND, "H₂O"));
            out.add(new Absorber("H₂O", 1900, 0.6 * water, 70, Absorber.Shape.BAND, "H₂O"));
        }
        double co = clamp((5_000 - t) / 2_000) * (s.kind() == StarKind.MAIN_SEQUENCE ? 0.5 : 1.0);
        if (co > 0) {
            out.add(new Absorber("CO", 2293.5, 0.5 * co, 15, Absorber.Shape.BANDHEAD, "CO"));
            out.add(new Absorber("CO", 2322.7, 0.45 * co, 15, Absorber.Shape.BANDHEAD, "CO"));
            out.add(new Absorber("CO", 2352.4, 0.4 * co, 15, Absorber.Shape.BANDHEAD, "CO"));
        }
        return out;
    }

    private static Absorber line(String label, double nm, double depth, double width, String gas) {
        return new Absorber(label, nm, depth, width, Absorber.Shape.LINE, gas);
    }

    /** Log-normal strength curve in T: 1 at {@code peakK}, falling with log-width {@code wCool}/{@code wHot} either side. */
    static double peak(double t, double peakK, double wCool, double wHot) {
        if (t <= 0) return 0;
        double x = Math.log10(t / peakK) / (t < peakK ? wCool : wHot);
        return Math.exp(-0.5 * x * x);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
