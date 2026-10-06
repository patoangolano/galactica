package dev.galactica.planet.transfer;

import dev.galactica.science.Units;
import dev.galactica.system.Body;

/**
 * Atmospheric entry physics for the hand-off and the plasma effect.
 *
 * <ul>
 *   <li><b>Density.</b> Isothermal barometric law ρ(h) = ρ₀·e^(−h/H), ρ₀ = p·μ·m_u/(k·T) from the body's
 *       surface pressure, mean molecular mass and temperature; H is {@link Body#scaleHeight()}.</li>
 *   <li><b>Stagnation heating.</b> Sutton–Graves convective heat flux q = k·√(ρ/r_n)·v³ (W/m², SI), with the
 *       gas constant k from K. Sutton &amp; R. A. Graves, "A general stagnation-point convective heating
 *       equation for arbitrary gas mixtures", NASA TR R-376 (1971): 1.7415×10⁻⁴ for Earth air,
 *       1.9027×10⁻⁴ for CO₂ (Mars, Venus), 1.7407×10⁻⁴ for N₂-dominated air (Titan), 0.6556×10⁻⁴ for
 *       H₂/He (giant planets; Tauber &amp; Sutton, J. Spacecraft 28(1), 1991).</li>
 *   <li><b>Glow.</b> The heat shield radiates at its radiative-equilibrium temperature
 *       T_w = (q/(ε·σ))^¼ (Anderson, <i>Hypersonic and High-Temperature Gas Dynamics</i>, 2nd ed., §6.9).</li>
 *   <li><b>Deceleration.</b> Allen–Eggers ballistic entry: v(h) = v_E·exp(−B·e^(−h/H)),
 *       B = ρ₀H/(2β·sin γ) (H. J. Allen &amp; A. J. Eggers, NACA Report 1381, 1958). It only holds while drag
 *       dominates gravity, so below terminal speed v_t = √(2βg/ρ) the craft keeps its own speed.
 *       Sutton–Graves along that trajectory peaks where d/dρ[√ρ·e^(−3ρH/(2β sin γ))] = 0, i.e. at
 *       ρ* = β·sin γ/(3H) ({@link #peakHeatingDensity}).</li>
 *   <li><b>Bow shock.</b> Billig's sphere–cone correlations (F. S. Billig, "Shock-wave shapes around
 *       spherical- and cylindrical-nosed bodies", J. Spacecraft 4(6), 1967): stand-off Δ/R = 0.143·e^(3.24/M²),
 *       shock nose radius R_c/R = 1.143·e^(0.54/(M−1)^1.2), hyperbolic shape approaching the Mach angle.</li>
 * </ul>
 */
public final class EntryAerothermo {
    private EntryAerothermo() {}

    /** Atomic mass constant, kg. */
    public static final double AMU = 1.66053907e-27;

    /** Ballistic coefficient m/(C_D·A) of the starship, kg/m²: the Apollo command module's ≈ 5500 kg / (1.3 × 12 m²). */
    public static final double SHIP_BALLISTIC = 350;
    /** Ballistic coefficient of a suited astronaut, kg/m²: 110 kg / (1.0 × 0.75 m²), a stable skydiver. */
    public static final double EVA_BALLISTIC = 150;
    /** Effective nose radius of the starship's heat shield, m. */
    public static final double SHIP_NOSE_RADIUS = 1.0;
    /** Effective nose radius of a suited astronaut (helmet and shoulders), m. */
    public static final double EVA_NOSE_RADIUS = 0.3;
    /** Total hemispherical emissivity of a reinforced carbon–carbon / ablator heat shield. */
    public static final double EMISSIVITY = 0.85;

    /** Heat flux where the shield starts to glow visibly (T_w ≈ 650 K, dull red), W/m². */
    public static final double Q_VISIBLE = 1.0e4;
    /** Heat flux treated as full plasma (Apollo 4 peak, ≈ 4 MW/m² total), W/m². */
    public static final double Q_FULL = 4.0e6;

    // ---------------------------------------------------------------- atmosphere

    /** Mean molecular mass of an atmosphere class, in atomic mass units (same table as {@link Body#scaleHeight()}). */
    public static double meanMolecularMass(Body.Atmosphere a) {
        return switch (a) {
            case H2_HE -> 2.3;
            case CO2_THICK, TRACE_CO2 -> 44.0;
            case H2O_STEAM -> 18.0;
            case N2_CH4 -> 28.6;
            default -> 28.97;
        };
    }

    /** Ratio of specific heats: 1.4 for diatomic air and N₂, 1.3 for CO₂ and steam, 1.45 for H₂/He. */
    public static double heatCapacityRatio(Body.Atmosphere a) {
        return switch (a) {
            case CO2_THICK, TRACE_CO2, H2O_STEAM -> 1.3;
            case H2_HE -> 1.45;
            default -> 1.4;
        };
    }

    /** Sutton–Graves constant k, SI (kg^½ m⁻¹ … giving W/m² with ρ in kg/m³, r_n in m, v in m/s). */
    public static double suttonGravesConstant(Body.Atmosphere a) {
        return switch (a) {
            case CO2_THICK, TRACE_CO2 -> 1.9027e-4;
            case N2_CH4 -> 1.7407e-4;
            case H2_HE -> 0.6556e-4;
            default -> 1.7415e-4;
        };
    }

    /** Surface density ρ₀ = p·μ·m_u/(k·T), kg/m³. */
    public static double surfaceDensity(double pressureBar, double molecularMassAmu, double temperatureK) {
        if (!(pressureBar > 0) || !(temperatureK > 0)) return 0;
        return pressureBar * 1e5 * molecularMassAmu * AMU / (Units.K_BOLTZMANN * temperatureK);
    }

    /** Surface density of a body's atmosphere, kg/m³ (0 for airless bodies). */
    public static double surfaceDensity(Body b) {
        return surfaceDensity(b.surfacePressureBar(), meanMolecularMass(b.atmosphere()), Math.max(30, b.surfaceTemp()));
    }

    /** ρ(h) = ρ₀·e^(−h/H), kg/m³. */
    public static double density(double rho0, double scaleHeight, double altitude) {
        if (!(rho0 > 0) || !(scaleHeight > 0)) return 0;
        return rho0 * Math.exp(-Math.max(0, altitude) / scaleHeight);
    }

    /** Speed of sound √(γ·k·T/(μ·m_u)), m/s. */
    public static double speedOfSound(double gamma, double temperatureK, double molecularMassAmu) {
        return Math.sqrt(gamma * Units.K_BOLTZMANN * Math.max(30, temperatureK) / (molecularMassAmu * AMU));
    }

    // ---------------------------------------------------------------- heating

    /** Sutton–Graves stagnation-point convective heat flux q = k·√(ρ/r_n)·v³, W/m². */
    public static double heatFlux(double k, double rho, double noseRadius, double speed) {
        if (!(rho > 0) || !(noseRadius > 0)) return 0;
        return k * Math.sqrt(rho / noseRadius) * speed * speed * speed;
    }

    /** Radiative-equilibrium wall temperature (q/(ε·σ))^¼, K. */
    public static double wallTemperature(double heatFlux, double emissivity) {
        return heatFlux > 0 ? Math.pow(heatFlux / (emissivity * Units.SIGMA_SB), 0.25) : 0;
    }

    /**
     * Visual plasma strength in [0, 1]: logarithmic in q between {@link #Q_VISIBLE} and {@link #Q_FULL}, as
     * the eye sees brightness (Weber–Fechner) and the glow spans three decades of heat flux.
     */
    public static double intensity(double heatFlux) {
        if (!(heatFlux > Q_VISIBLE)) return 0;
        double t = Math.log(heatFlux / Q_VISIBLE) / Math.log(Q_FULL / Q_VISIBLE);
        return Math.min(1, t);
    }

    // ---------------------------------------------------------------- deceleration

    /** Allen–Eggers parameter B = ρ₀·H/(2β·sin γ); γ below 3° is clamped (the closed form fails for skipping). */
    public static double allenEggersB(double rho0, double scaleHeight, double ballistic, double sinGamma) {
        double s = Math.max(Math.sin(Math.toRadians(3)), Math.min(1, sinGamma));
        return rho0 * scaleHeight / (2 * ballistic * s);
    }

    /** v(h)/v_E = exp(−B·e^(−h/H)) of a ballistic entry. */
    public static double allenEggersFactor(double b, double scaleHeight, double altitude) {
        if (!(scaleHeight > 0)) return 1;
        return Math.exp(-b * Math.exp(-Math.max(0, altitude) / scaleHeight));
    }

    /** Terminal speed √(2βg/ρ), m/s; infinite in vacuum. */
    public static double terminalVelocity(double ballistic, double gravity, double rho) {
        return rho > 0 ? Math.sqrt(2 * ballistic * gravity / rho) : Double.POSITIVE_INFINITY;
    }

    /** Density of peak Sutton–Graves heating on an Allen–Eggers trajectory, ρ* = β·sin γ/(3H), kg/m³. */
    public static double peakHeatingDensity(double ballistic, double sinGamma, double scaleHeight) {
        return ballistic * sinGamma / (3 * scaleHeight);
    }

    /**
     * Speed a craft that crossed the atmosphere top at {@code entrySpeed} has left at {@code altitude}:
     * Allen–Eggers drag down to, never below, terminal speed. A craft already slower than terminal speed is
     * flying under power and keeps its speed. Airless bodies return {@code entrySpeed}.
     */
    public static double speedAt(double entrySpeed, double sinGamma, double rho0, double scaleHeight,
                                 double ballistic, double gravity, double altitude) {
        if (!(rho0 > 0) || !(entrySpeed > 0)) return Math.max(0, entrySpeed);
        double vt = terminalVelocity(ballistic, gravity, density(rho0, scaleHeight, altitude));
        if (entrySpeed <= vt) return entrySpeed;
        double f = allenEggersFactor(allenEggersB(rho0, scaleHeight, ballistic, sinGamma), scaleHeight, altitude);
        return vt + (entrySpeed - vt) * f;
    }

    // ---------------------------------------------------------------- bow shock

    /** Billig stand-off distance of the shock ahead of a sphere of radius R at Mach M, same units as R. */
    public static double shockStandoff(double radius, double mach) {
        double m = Math.max(1.05, mach);
        return radius * 0.143 * Math.exp(3.24 / (m * m));
    }

    /** Billig radius of curvature of the shock nose, same units as R. */
    public static double shockNoseRadius(double radius, double mach) {
        double m = Math.max(1.05, mach);
        return radius * 1.143 * Math.exp(0.54 / Math.pow(m - 1, 1.2));
    }

    /**
     * Billig shock shape: axial position (positive upstream of the body centre) of the shock at radial
     * distance y from the axis, x = R + Δ − R_c·cot²β·[√(1 + y²·tan²β/R_c²) − 1], β = asin(1/M).
     */
    public static double shockAxial(double radius, double mach, double y) {
        double m = Math.max(1.05, mach);
        double beta = Math.asin(1 / m);
        double tan = Math.tan(beta), cot2 = 1 / (tan * tan);
        double rc = shockNoseRadius(radius, m);
        return radius + shockStandoff(radius, m) - rc * cot2 * (Math.sqrt(1 + y * y * tan * tan / (rc * rc)) - 1);
    }

    // ---------------------------------------------------------------- one state for the effect

    /**
     * Everything the plasma effect needs at one instant.
     *
     * @param heatFlux    stagnation heat flux, W/m²
     * @param intensity   {@link #intensity} of it, 0..1
     * @param temperature wall temperature, K
     * @param mach        flight Mach number
     */
    public record Plasma(double heatFlux, double intensity, double temperature, double mach) {
        public static final Plasma NONE = new Plasma(0, 0, 0, 0);
    }

    /** Plasma state of a craft at {@code altitude} m and ground-relative {@code speed} m/s above body b. */
    public static Plasma plasma(Body b, double altitude, double speed, double noseRadius) {
        double rho = density(surfaceDensity(b), b.scaleHeight(), altitude);
        if (!(rho > 0)) return Plasma.NONE;
        double q = heatFlux(suttonGravesConstant(b.atmosphere()), rho, noseRadius, speed);
        double a = speedOfSound(heatCapacityRatio(b.atmosphere()), b.surfaceTemp(), meanMolecularMass(b.atmosphere()));
        return new Plasma(q, intensity(q), wallTemperature(q, EMISSIVITY), speed / a);
    }
}
