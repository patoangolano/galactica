package dev.galactica.science.spectra;

import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The spectrum of a planet or moon, isolated from its star (as a coronagraph or a resolved image
 * does):
 * <pre>
 *   F_λ ∝ p·(R★/a)²·B_λ(T★)·r(λ) + B_λ(T_eq)          (both terms per unit planet area)
 * </pre>
 * reflected starlight with geometric albedo p ≈ the body's albedo and a surface/cloud reflectance
 * shape r(λ), plus thermal emission at the equilibrium temperature, all multiplied by the
 * transmission of the atmosphere. Thermal light dominates the K band of hot Jupiters and nothing
 * else at these wavelengths, as observed (e.g. WASP-33b, Deming et al. 2012).
 *
 * <p>Band centres and relative strengths: Earth from the Galileo flyby and earthshine spectra
 * (Sagan et al. 1993, Nature 365, 715; Turnbull et al. 2006, ApJ 644, 551) and Des Marais et al.
 * (2002, Astrobiology 2, 153); Titan's and the giant planets' CH₄ windows from Karkoschka (1994,
 * Icarus 111, 174); Venus CO₂ from Pollack et al. (1993); hot-Jupiter Na/K/H₂O/CO from Seager &amp;
 * Sasselov (2000, ApJ 537, 916) and Sing et al. (2016, Nature 529, 59); ice bands from Clark
 * (1981) and Grundy &amp; Schmitt (1998); the vegetation red edge from Seager et al. (2005,
 * Astrobiology 5, 372). Depths scale with surface pressure.
 *
 * <p><b>Biosignature.</b> O₂ (or O₃) together with CH₄ is flagged: the two react, so seeing both
 * means something keeps replenishing them out of equilibrium (Lovelock 1965, Nature 207, 568;
 * Sagan et al. 1993; Krissansen-Totton et al. 2018, Sci. Adv. 4, eaao5747).
 */
public final class PlanetSpectrum {
    private PlanetSpectrum() {}

    /**
     * @param spectrum      the synthetic spectrum
     * @param gases         species whose absorption is detected (depth ≥ 5 %), in band order
     * @param biosignature  O₂ and CH₄ detected together
     */
    public record Result(Spectrum spectrum, List<String> gases, boolean biosignature) {}

    public static final String O2 = "O₂", O3 = "O₃", H2O = "H₂O", CO2 = "CO₂", CH4 = "CH₄", NA = "Na", K = "K",
            CO = "CO", NH3 = "NH₃", H2 = "H₂";

    /**
     * Spectrum of {@code body}, lit by {@code host} at {@code orbitMetres} from it (for a moon, its
     * planet's distance), seen with radial velocity {@code rvKms}.
     */
    public static Result of(Star host, Body body, double orbitMetres, double rvKms) {
        List<Absorber> abs = absorbers(body);
        double tStar = Math.max(0, host.temperature());
        double ratio = host.radiusMetres() / Math.max(orbitMetres, host.radiusMetres() * 1.01);
        double reflect = Math.max(0.01, body.albedo()) * ratio * ratio;
        double teq = body.equilibriumTemp();
        Spectrum s = Spectrum.build(l -> reflect * Spectrum.planck(l, tStar) * reflectance(body, l) + Spectrum.planck(l, teq), abs, rvKms);

        List<String> gases = gases(abs);
        return new Result(s, gases, biosignature(gases));
    }

    /** Species detected in a body's spectrum (features ≥ 5 % deep), without building the spectrum. */
    public static List<String> gases(Body b) {
        return gases(absorbers(b));
    }

    /** True when O₂ (or O₃) and CH₄ are detected together. */
    public static boolean biosignature(Body b) {
        return biosignature(gases(b));
    }

    private static List<String> gases(List<Absorber> abs) {
        Set<String> gases = new LinkedHashSet<>();
        for (Absorber a : abs) if (a.depth() >= 0.05 && isGas(a.gas())) gases.add(a.gas());
        return List.copyOf(gases);
    }

    private static boolean biosignature(List<String> gases) {
        return (gases.contains(O2) || gases.contains(O3)) && gases.contains(CH4);
    }

    /** Distance from the host star that sets a body's illumination: the planet's semi-major axis (moons share it). */
    public static double illuminationDistance(Body planet) {
        return planet.orbit().semiMajorAxis();
    }

    private static boolean isGas(String g) {
        return switch (g) {
            case O2, O3, H2O, CO2, CH4, NA, K, CO, NH3, H2 -> true;
            default -> false;
        };
    }

    /** Pressure scaling of band depths: 0.16 at Mars (6 mbar), 0.6 at 1 bar, 1 at ≥ 100 bar. */
    static double pressureFactor(double bar) {
        if (bar <= 0) return 0;
        return Math.max(0.1, Math.min(1.0, 0.6 + 0.2 * Math.log10(bar)));
    }

    /** Surface/cloud reflectance shape r(λ), order unity. */
    static double reflectance(Body b, double nm) {
        double r = 1.0;
        boolean thick = b.surfacePressureBar() > 0.3;
        switch (b.atmosphere()) {
            // Rayleigh scattering brightens the blue of thick, clear atmospheres (σ ∝ λ⁻⁴).
            case N2_O2, CO2_THICK, H2_HE, H2O_STEAM -> r *= 1 + (thick ? 0.35 : 0.1) * Math.pow(450.0 / nm, 4);
            // Tholin haze absorbs the blue: Titan and Triton look orange.
            case N2_CH4 -> r *= 1 - 0.55 * Math.exp(-(nm - 350) / 160.0);
            default -> {
            }
        }
        if (!thick) {
            switch (b.type()) {
                // Ferric oxides: steep blue drop of Mars-like dust.
                case DESERT -> r *= (1 - 0.6 * Math.exp(-(nm - 350) / 120.0)) * (0.8 + 0.2 * Math.min(1.5, nm / 1000.0));
                // Silicate regolith: reddened slope (space weathering, Hapke 2001).
                case BARREN, LAVA -> r *= 0.7 + 0.3 * Math.min(2.0, nm / 1000.0);
                default -> {
                }
            }
        }
        if (b.life() == Body.Life.COMPLEX && (b.type() == BodyType.TERRAN || b.type() == BodyType.OCEAN)) {
            // Chlorophyll red edge at ~0.72 µm, diluted by ocean and cloud cover.
            double cover = b.type() == BodyType.TERRAN ? 0.5 : 0.15;
            r *= 1 + cover / (1 + Math.exp(-(nm - 715) / 12.0));
        }
        return r;
    }

    /** All features of a body's surface and atmosphere, rest wavelengths. */
    public static List<Absorber> absorbers(Body b) {
        List<Absorber> out = new ArrayList<>();
        double f = pressureFactor(b.surfacePressureBar());
        boolean hot = b.equilibriumTemp() > 1000;
        switch (b.atmosphere()) {
            case N2_O2 -> {
                out.add(band("O₂ A", 760.5, 0.9 * f, 3, O2));
                out.add(band("O₂ B", 687.0, 0.5 * f, 3, O2));
                out.add(band("O₂", 1268.0, 0.3 * f, 6, O2));
                out.add(band("O₃", 600.0, 0.12 * f, 60, O3));
                water(out, f, 1.0);
                out.add(band("CO₂", 1600.0, 0.1 * f, 10, CO2));
                out.add(band("CO₂", 2010.0, 0.35 * f, 15, CO2));
                if (b.life() != Body.Life.NONE) {
                    // Biogenic methane: 1.9 ppm on Earth, visible at 1.66 and 2.32 µm.
                    out.add(band("CH₄", 1666.0, 0.12 * f, 15, CH4));
                    out.add(band("CH₄", 2320.0, 0.2 * f, 25, CH4));
                }
            }
            case N2_CH4 -> methane(out, f, 1.0);
            case CO2_THICK -> {
                out.add(band("CO₂", 1050.0, 0.2 * f, 8, CO2));
                out.add(band("CO₂", 1210.0, 0.3 * f, 10, CO2));
                out.add(band("CO₂", 1430.0, 0.6 * f, 20, CO2));
                out.add(band("CO₂", 1600.0, 0.6 * f, 15, CO2));
                out.add(band("CO₂", 2010.0, 0.9 * f, 25, CO2));
                out.add(band("H₂O", 1380.0, 0.2 * f, 40, H2O));
            }
            case TRACE_CO2 -> {
                // Thin CO₂ is strong enough for its 2 µm and 1.43 µm bands even at Mars' 6 mbar.
                double g = Math.max(f, 0.25);
                out.add(band("CO₂", 1430.0, 0.4 * g, 20, CO2));
                out.add(band("CO₂", 1600.0, 0.3 * g, 15, CO2));
                out.add(band("CO₂", 2010.0, 0.8 * g, 25, CO2));
            }
            case H2O_STEAM -> {
                water(out, Math.max(f, 0.8), 1.2);
                out.add(band("CO₂", 2010.0, 0.4 * f, 25, CO2));
            }
            case H2_HE -> {
                if (hot) {
                    out.add(line("Na D", 589.0, 0.6, 1.5, NA));
                    out.add(line("Na D", 589.6, 0.55, 1.5, NA));
                    out.add(line("K I", 766.5, 0.5, 2.0, K));
                    out.add(line("K I", 769.9, 0.45, 2.0, K));
                    out.add(band("H₂O", 1150.0, 0.4, 40, H2O));
                    out.add(band("H₂O", 1400.0, 0.6, 60, H2O));
                    out.add(band("H₂O", 1900.0, 0.5, 70, H2O));
                    out.add(new Absorber("CO", 2293.5, 0.4, 15, Absorber.Shape.BANDHEAD, CO));
                } else if (b.equilibriumTemp() > 600) {
                    // Warm envelopes: methane converted to CO, water dominant.
                    water(out, 0.8, 1.0);
                    out.add(new Absorber("CO", 2293.5, 0.3, 15, Absorber.Shape.BANDHEAD, CO));
                } else {
                    methane(out, 1.0, b.type() == BodyType.ICE_GIANT ? 1.1 : 0.9);
                    if (b.equilibriumTemp() < 200 && b.type() == BodyType.GAS_GIANT) {
                        out.add(band("NH₃", 645.0, 0.15, 4, NH3));
                        out.add(band("NH₃", 1500.0, 0.3, 40, NH3));
                    }
                    // Collision-induced H₂ absorption (Birnbaum et al. 1996) shapes the K band.
                    out.add(band("H₂ CIA", 2120.0, 0.5, 150, H2));
                }
            }
            case NONE -> {
            }
        }
        // Surface ices show through thin or absent atmospheres.
        if (b.type() == BodyType.ICE && b.surfacePressureBar() < 0.3) {
            out.add(band("H₂O ice", 1040.0, 0.1, 20, "ice"));
            out.add(band("H₂O ice", 1250.0, 0.15, 25, "ice"));
            out.add(band("H₂O ice", 1500.0, 0.5, 60, "ice"));
            out.add(band("H₂O ice", 2000.0, 0.6, 70, "ice"));
        }
        if ((b.type() == BodyType.BARREN || b.type() == BodyType.LAVA || b.type() == BodyType.DESERT) && b.surfacePressureBar() < 0.3) {
            out.add(band("Fe²⁺", 1000.0, 0.15, 100, "pyroxene"));
            out.add(band("Fe²⁺", 2000.0, 0.10, 150, "pyroxene"));
        }
        return out;
    }

    private static void water(List<Absorber> out, double f, double k) {
        out.add(band("H₂O", 720.0, 0.3 * f * k, 8, H2O));
        out.add(band("H₂O", 820.0, 0.35 * f * k, 10, H2O));
        out.add(band("H₂O", 940.0, 0.6 * f * k, 20, H2O));
        out.add(band("H₂O", 1130.0, 0.6 * f * k, 25, H2O));
        out.add(band("H₂O", 1380.0, 0.9 * f * k, 40, H2O));
        out.add(band("H₂O", 1870.0, 0.9 * f * k, 50, H2O));
    }

    private static void methane(List<Absorber> out, double f, double k) {
        out.add(band("CH₄", 619.0, 0.4 * f * k, 6, CH4));
        out.add(band("CH₄", 727.0, 0.5 * f * k, 8, CH4));
        out.add(band("CH₄", 890.0, 0.85 * f * k, 12, CH4));
        out.add(band("CH₄", 1000.0, 0.6 * f * k, 15, CH4));
        out.add(band("CH₄", 1150.0, 0.9 * f * k, 30, CH4));
        out.add(band("CH₄", 1400.0, 0.95 * f * k, 50, CH4));
        out.add(band("CH₄", 1700.0, 0.9 * f * k, 40, CH4));
        out.add(band("CH₄", 2300.0, 0.9 * f * k, 60, CH4));
    }

    private static Absorber band(String label, double nm, double depth, double width, String gas) {
        return new Absorber(label, nm, depth, width, Absorber.Shape.BAND, gas);
    }

    private static Absorber line(String label, double nm, double depth, double width, String gas) {
        return new Absorber(label, nm, depth, width, Absorber.Shape.LINE, gas);
    }

    /** Equilibrium temperature a body at {@code orbitMetres} would have, for display: T★·√(R★/2a)·(1−A)^¼. */
    public static double equilibriumTemperature(Star host, double albedo, double orbitMetres) {
        return host.temperature() * Math.sqrt(host.radiusMetres() / (2 * orbitMetres)) * Math.pow(1 - albedo, 0.25);
    }

    /** Orbital distance in AU, for display. */
    public static double au(double metres) {
        return metres / Units.AU;
    }
}
