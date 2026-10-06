package dev.galactica.ship;

/**
 * Gas dynamics of the Starship's main-engine plume, free of Minecraft types: what the exhaust renderer draws is
 * computed here from the throttle and the ambient pressure, so it can be unit-tested.
 *
 * <p><b>Engine.</b> A hydrogen–oxygen bell nozzle: chamber pressure {@value #CHAMBER_PRESSURE_BAR} bar at full
 * throttle, chamber temperature {@value #CHAMBER_TEMPERATURE_K} K (H₂/O₂ at O/F ≈ 6), exhaust ratio of specific
 * heats γ = {@value #GAMMA}, exit Mach {@value #EXIT_MACH}. Isentropic relations (Anderson, <i>Modern Compressible
 * Flow</i>, 3rd ed., §3.4 and §5.4):
 * <ul>
 *   <li>p₀/p = (1 + (γ−1)/2·M²)^(γ/(γ−1)), so full throttle exits at ≈ 2.1 bar;</li>
 *   <li>T₀/T = 1 + (γ−1)/2·M², so the gas leaves at ≈ 1 840 K;</li>
 *   <li>A/A* = (1/M)·[2/(γ+1)·(1 + (γ−1)/2·M²)]^((γ+1)/(2(γ−1))).</li>
 * </ul>
 *
 * <p><b>In an atmosphere</b> a jet whose exit pressure differs from ambient forms a train of shock cells (Mach
 * diamonds). The fully expanded Mach number follows from the nozzle pressure ratio p₀/pₐ, the fully expanded
 * diameter from the area ratio, and the cell spacing from the Prandtl–Pack relation L = 1.306·β·Dⱼ with
 * β = √(Mⱼ² − 1) (Tam &amp; Tanna 1982, J. Sound Vib. 81, 337). The cells are drawn only while ambient pressure exceeds
 * {@value #DIAMOND_MIN_PRESSURE_BAR} bar; below that the shocks are too weak and too far apart to see.
 *
 * <p><b>In vacuum</b> the jet over-expands without bound: the Prandtl–Meyer limit turns the edge flow by
 * ν_max − ν(Mₑ) (≈ 145° for these numbers), and the density falls off with angle as
 * cos^(2/(γ−1))(πθ/2θ_max) (Simons 1972, AIAA J. 10, 1534). The visible plume is the quarter-density contour of
 * that law: wide and faint, as on photographs of upper stages firing in orbit.
 */
public final class ExhaustPhysics {
    private ExhaustPhysics() {}

    public static final double GAMMA = 1.2;
    public static final double EXIT_MACH = 3.0;
    public static final double CHAMBER_PRESSURE_BAR = 100.0;
    public static final double CHAMBER_TEMPERATURE_K = 3500.0;
    /** Below this ambient pressure the plume is drawn as a vacuum plume (no shock diamonds). */
    public static final double DIAMOND_MIN_PRESSURE_BAR = 0.05;
    /** Turbulent mixing-layer spreading of a jet in still air: half-angle ≈ 6° (Pope, <i>Turbulent Flows</i>, §5.4). */
    public static final double MIXING_HALF_ANGLE_RAD = Math.toRadians(6.0);
    /**
     * Visual cap on the vacuum plume's half-angle. The Simons quarter-density contour lies near 47°, but beyond ~25°
     * the gas is too thin to glow visibly at game distances, so the drawn cone stops there.
     */
    public static final double MAX_VISIBLE_HALF_ANGLE_RAD = Math.toRadians(25.0);
    /** Below this throttle the engine is treated as shut down. */
    public static final double MIN_THROTTLE = 0.02;

    /** Which flow regime the plume is drawn in. */
    public enum Regime { OFF, VACUUM, UNDER_EXPANDED, OVER_EXPANDED }

    /**
     * Everything the renderer needs for one engine at one instant. Lengths are in the same unit as the exit radius
     * passed to {@link #plume}.
     *
     * @param length           visible plume length
     * @param exitRadius       nozzle exit radius
     * @param halfAngleRad     half-angle of the visible plume boundary beyond the exit
     * @param jetRadius        fully expanded jet radius (the radius the shock-cell train settles to)
     * @param shockSpacing     distance between Mach diamonds (0 in vacuum)
     * @param shockCells       how many diamonds fit in the visible plume (0 in vacuum)
     * @param jetMach          fully expanded Mach number (exit Mach in vacuum)
     * @param exitPressureBar  static pressure at the nozzle exit
     * @param exitTemperatureK static temperature at the nozzle exit
     * @param intensity        relative brightness 0 … 1 (mass flow ∝ chamber pressure ∝ throttle)
     */
    public record Plume(Regime regime, double length, double exitRadius, double halfAngleRad, double jetRadius,
                        double shockSpacing, double shockCells, double jetMach, double exitPressureBar,
                        double exitTemperatureK, double intensity) {
        public static final Plume OFF = new Plume(Regime.OFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** Radius of the visible plume at a distance {@code x} downstream of the exit. */
        public double radiusAt(double x) {
            double d = Math.max(0, x);
            if (regime == Regime.OFF) return 0;
            if (regime == Regime.VACUUM) return exitRadius + d * Math.tan(halfAngleRad);
            // The jet reaches its fully expanded radius within half a shock cell, then spreads by mixing.
            double settle = shockSpacing > 0 ? Math.min(1, d / (0.5 * shockSpacing)) : 1;
            return Math.max(0.2 * exitRadius, exitRadius + (jetRadius - exitRadius) * settle + d * Math.tan(halfAngleRad));
        }
    }

    /** p₀/p for isentropic flow at Mach M. */
    public static double pressureRatio(double mach) {
        return Math.pow(1 + (GAMMA - 1) / 2 * mach * mach, GAMMA / (GAMMA - 1));
    }

    /** T₀/T for isentropic flow at Mach M. */
    public static double temperatureRatio(double mach) {
        return 1 + (GAMMA - 1) / 2 * mach * mach;
    }

    /** A/A* for isentropic flow at Mach M. */
    public static double areaRatio(double mach) {
        double g = GAMMA;
        return Math.pow(2 / (g + 1) * (1 + (g - 1) / 2 * mach * mach), (g + 1) / (2 * (g - 1))) / mach;
    }

    /** Mach number of a jet expanded isentropically from p₀ to pₐ (the "fully expanded" Mach number). */
    public static double fullyExpandedMach(double chamberBar, double ambientBar) {
        double npr = chamberBar / ambientBar;
        if (npr <= 1) return 0;
        return Math.sqrt(2 / (GAMMA - 1) * (Math.pow(npr, (GAMMA - 1) / GAMMA) - 1));
    }

    /** Prandtl–Meyer function ν(M), radians. */
    public static double prandtlMeyer(double mach) {
        if (mach <= 1) return 0;
        double k = Math.sqrt((GAMMA + 1) / (GAMMA - 1));
        double b = Math.sqrt(mach * mach - 1);
        return k * Math.atan(b / k) - Math.atan(b);
    }

    /** Largest angle the exit flow can turn into vacuum: ν_max − ν(Mₑ). */
    public static double vacuumTurningLimit() {
        double numax = Math.PI / 2 * (Math.sqrt((GAMMA + 1) / (GAMMA - 1)) - 1);
        return numax - prandtlMeyer(EXIT_MACH);
    }

    /**
     * Half-angle where the Simons vacuum-plume density falls to {@code fraction} of its centreline value:
     * cos^k(πθ/2θ_max) = fraction with k = 2/(γ−1).
     */
    public static double simonsHalfAngle(double fraction) {
        double k = 2 / (GAMMA - 1);
        double x = Math.acos(Math.pow(fraction, 1 / k));
        return x * 2 * vacuumTurningLimit() / Math.PI;
    }

    /**
     * The plume for one engine.
     *
     * @param throttle    |main-engine throttle|, 0 … 1
     * @param ambientBar  ambient static pressure at the nozzle, bar (0 in space)
     * @param exitRadius  nozzle exit radius in the caller's length unit (blocks for the renderer)
     */
    public static Plume plume(double throttle, double ambientBar, double exitRadius) {
        double t = Math.clamp(Double.isFinite(throttle) ? Math.abs(throttle) : 0, 0, 1);
        if (t < MIN_THROTTLE) return Plume.OFF;
        double pa = Math.max(0, Double.isFinite(ambientBar) ? ambientBar : 0);
        double p0 = CHAMBER_PRESSURE_BAR * t;
        double pe = p0 / pressureRatio(EXIT_MACH);
        double te = CHAMBER_TEMPERATURE_K / temperatureRatio(EXIT_MACH);
        double diameter = 2 * exitRadius;

        if (pa < DIAMOND_MIN_PRESSURE_BAR) {
            // Vacuum: long, wide, faint. Brightness falls with the spread, length grows with the throttle.
            double half = Math.min(MAX_VISIBLE_HALF_ANGLE_RAD, simonsHalfAngle(0.25));
            double length = diameter * (6 + 12 * t);
            return new Plume(Regime.VACUUM, length, exitRadius, half, exitRadius, 0, 0, EXIT_MACH, pe, te, 0.45 * t);
        }

        double mj = Math.max(1.0001, fullyExpandedMach(p0, pa));
        double jetRadius = exitRadius * Math.sqrt(areaRatio(mj) / areaRatio(EXIT_MACH));
        double spacing = 1.306 * Math.sqrt(mj * mj - 1) * 2 * jetRadius;
        // The more under-expanded the jet, the more it bulges before the first cell: blend toward the vacuum fan.
        double under = Math.clamp(Math.log10(pe / pa) / 2, 0, 1);
        double half = MIXING_HALF_ANGLE_RAD + under * (Math.min(MAX_VISIBLE_HALF_ANGLE_RAD, simonsHalfAngle(0.25)) - MIXING_HALF_ANGLE_RAD) * 0.5;
        double length = diameter * (4 + 10 * t);
        // Shock cells survive over the supersonic core; beyond ~8 cells mixing has erased them.
        double cells = Math.min(8, length * 0.7 / spacing);
        Regime r = pe >= pa ? Regime.UNDER_EXPANDED : Regime.OVER_EXPANDED;
        return new Plume(r, length, exitRadius, half, jetRadius, spacing, cells, mj, pe, te, t);
    }

    /**
     * Barometric pressure at height {@code h} above a reference level with surface pressure {@code p0} and scale
     * height {@code H}: p = p₀·e^(−h/H) (isothermal atmosphere).
     */
    public static double barometric(double surfaceBar, double heightAboveSurface, double scaleHeight) {
        if (surfaceBar <= 0 || scaleHeight <= 0) return 0;
        return surfaceBar * Math.exp(-heightAboveSurface / scaleHeight);
    }
}
