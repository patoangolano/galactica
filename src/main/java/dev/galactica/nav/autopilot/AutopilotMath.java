package dev.galactica.nav.autopilot;

import dev.galactica.science.Units;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The arithmetic of the autopilot, shared by the server (phase decisions, arrival checks) and the pilot's client
 * (guidance), free of Minecraft types so it is unit-tested.
 *
 * <p><b>Arrival.</b> A planet or moon is reached at {@link #orbitAltitude}: well above its entry shell, where the
 * autopilot inserts into a circular orbit. A star is reached where the hyperdrive also drops out
 * ({@link #starArrivalDistance}), a deep-sky object 1.5 radii from its centre.
 *
 * <p><b>Guidance.</b> Every powered phase is "velocity to be gained": the phase names the velocity it wants relative to
 * the body ({@link #circularVelocity}, {@link #descentVelocity}), the nose is turned along the difference and the main
 * engine fires once it points within {@link #BURN_CONE_DEG}. Gravity simply shows up in the difference, so the same law
 * circularises, corrects a sagging orbit and flies a powered descent.
 */
public final class AutopilotMath {
    private AutopilotMath() {}

    /** Entry shell of a body with no air (PlanetEntry.AIRLESS_ENTRY_M). */
    public static final double AIRLESS_ENTRY_M = 100_000;
    /** The main engine fires when the nose is within this angle of the wanted velocity change. */
    public static final double BURN_CONE_DEG = 6.0;
    /** Velocity error below which a burn ends (a tick of main engine is 2.5 m/s). */
    public static final double BURN_DONE_MS = 2.0;
    /** Largest turn the autopilot commands per tick, per axis (the ship allows {@link ShipFlightModel#MAX_TURN_PER_TICK}). */
    public static final double MAX_STEER_DEG = 8.0;
    /** The warp is engaged only when the nose is within this angle of the aim point. */
    public static final double WARP_CONE_DEG = 2.0;
    /** The warp is released once the surface ahead is this many arrival distances away (the drop-out coasts ~10 %). */
    public static final double WARP_RELEASE_FACTOR = 1.5;
    /** An orbit counts as reached below this eccentricity, with the periapsis clear of the entry shell. */
    public static final double ORBIT_MAX_ECCENTRICITY = 0.08;
    /** Bodies this heavy are orbited, not landed on: the ship's RCS (10 m/s²) needs a margin over gravity to hover. */
    public static final double MAX_LANDING_GRAVITY = 0.6 * ShipFlightModel.RCS_ACCEL;

    /** Altitude where the approach to a body begins (PlanetEntry): the atmosphere's 1 µbar level, or 100 km airless. */
    public static double entryAltitude(Body b) {
        double top = b.atmosphereTopMetres();
        return top > 0 ? top : AIRLESS_ENTRY_M;
    }

    /** Parking altitude for a body: 2.5 entry shells, and at least 15 % of its radius. */
    public static double orbitAltitude(Body b) {
        return Math.max(2.5 * entryAltitude(b), 0.15 * b.radiusMetres());
    }

    /** Where the hyperdrive drops out near a star: where it looks ~10° across, never closer than 0.05 AU; 40 rs from a black hole. */
    public static double starArrivalDistance(Star s, double radiusMetres) {
        if (s.kind() == StarKind.BLACK_HOLE) return 40 * radiusMetres;
        return Math.max(0.05 * Units.AU, radiusMetres / Math.tan(Math.toRadians(5)));
    }

    /** Distance from a deep-sky object's centre where the autopilot stops: outside it, with the whole object in view. */
    public static double deepSkyArrivalDistance(double radiusMetres) {
        return 1.5 * radiusMetres;
    }

    /** Whether the bodies' surface gravity lets the autopilot land on it. */
    public static boolean landable(Body b) {
        return b.type().solidSurface && b.surfaceGravity() <= MAX_LANDING_GRAVITY;
    }

    // ---------------------------------------------------------------- vectors (galactic frame, plain arrays)

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /**
     * Velocity of the circular orbit through {@code r} (metres from the centre) in the plane the craft already moves in
     * (r × v), so insertion never wastes Δv on a plane change; when the craft is (nearly) at rest or falling radially the
     * plane is the one perpendicular to {@code fallbackNormal} (or galactic x if that is parallel to r).
     */
    public static double[] circularVelocity(double[] r, double[] v, double mu, double[] fallbackNormal) {
        double rm = len(r);
        double[] n = cross(r, v);
        double nl = len(n);
        if (nl < 0.05 * rm * Math.max(1.0, len(v))) {
            n = fallbackNormal;
            if (len(cross(n, r)) < 0.1 * rm * len(n)) n = new double[] {1, 0, 0};
            nl = len(n);
        }
        double[] dir = cross(n, r);
        double dl = len(dir);
        double s = Math.sqrt(mu / rm);
        return new double[] {dir[0] / dl * s, dir[1] / dl * s, dir[2] / dl * s};
    }

    /** Below this altitude a descent is held to {@link #DESCENT_ENTRY_SPEED}; above it the cap rises with height. */
    public static final double DESCENT_SLOW_ALTITUDE = 20_000;
    /** Descent speed at and below {@link #DESCENT_SLOW_ALTITUDE}: under the 300 m/s block cap the entry switch requires. */
    public static final double DESCENT_ENTRY_SPEED = 250;

    /**
     * Wanted speed of a powered descent at {@code altitude} over the surface: straight down, as fast as a braking burn at
     * 40 % of the engine's spare acceleration ({@code MAIN_ACCEL − g}) can still cancel above {@code floorAltitude}, at
     * least 20 m/s, and at most {@link #DESCENT_ENTRY_SPEED} plus 2 % of the height above
     * {@link #DESCENT_SLOW_ALTITUDE} (a deceleration the engine meets easily), so the craft reaches the entry switch,
     * a few km up, under the block cap while the long fall from orbit altitude takes minutes, not a quarter hour.
     */
    public static double descentSpeed(double altitude, double floorAltitude, double gravity) {
        double spare = Math.max(1.0, ShipFlightModel.MAIN_ACCEL - gravity);
        double h = Math.max(0, altitude - floorAltitude);
        double cap = DESCENT_ENTRY_SPEED + 0.02 * Math.max(0, altitude - DESCENT_SLOW_ALTITUDE);
        return Math.clamp(Math.sqrt(2 * 0.4 * spare * h), 20.0, cap);
    }

    /** {@link #descentSpeed} as a velocity: along −r̂, no tangential part (a vertical descent). */
    public static double[] descentVelocity(double[] r, double altitude, double floorAltitude, double gravity) {
        double rm = len(r), s = descentSpeed(altitude, floorAltitude, gravity);
        return new double[] {-r[0] / rm * s, -r[1] / rm * s, -r[2] / rm * s};
    }

    /** Wanted vertical speed (m/s, negative down) in the last part of a landing, from the height over the ground. */
    public static double touchdownSpeed(double heightAboveGround) {
        return -Math.clamp(0.08 * heightAboveGround, 2.5, 60.0);
    }

    /** Eccentricity from periapsis and apoapsis radii; 1 for an open conic. */
    public static double eccentricity(double rp, double ra) {
        if (!Double.isFinite(ra) || ra <= 0) return 1;
        return (ra - rp) / (ra + rp);
    }

    /** True when the conic through (r, v) is a near-circular orbit whose periapsis clears {@code floorRadius}. */
    public static boolean orbitReached(double[] apsides, double floorRadius) {
        return Double.isFinite(apsides[1]) && apsides[0] > floorRadius
                && eccentricity(apsides[0], apsides[1]) < ORBIT_MAX_ECCENTRICITY;
    }

    // ---------------------------------------------------------------- steering

    /**
     * Turn commands (degrees this tick: {yaw, pitch}, the ship's {@code Controls} conventions: yaw positive nose right,
     * pitch positive nose down) that bring the nose of a ship at {@code attitude} (body to world, body +Z nose,
     * +X port, +Y dorsal) toward the world direction {@code want}. Proportional with gain {@code gain}, each axis capped
     * at {@link #MAX_STEER_DEG}; a target behind the ship is turned toward at full rate in yaw.
     */
    public static double[] steer(Quaterniond attitude, Vector3d want, double gain) {
        Vector3d b = new Quaterniond(attitude).conjugate().transform(new Vector3d(want).normalize());
        double yaw = Math.toDegrees(Math.atan2(-b.x, b.z));
        double pitch = Math.toDegrees(Math.atan2(-b.y, Math.sqrt(b.x * b.x + b.z * b.z)));
        if (b.z < 0 && Math.abs(b.x) < 1e-3 && Math.abs(b.y) < 1e-3) yaw = 180; // dead astern
        return new double[] {Math.clamp(yaw * gain, -MAX_STEER_DEG, MAX_STEER_DEG),
                Math.clamp(pitch * gain, -MAX_STEER_DEG, MAX_STEER_DEG)};
    }

    /** Angle in degrees between the nose of {@code attitude} and the world direction {@code want}. */
    public static double noseError(Quaterniond attitude, Vector3d want) {
        Vector3d nose = attitude.transform(new Vector3d(0, 0, 1));
        double c = nose.dot(new Vector3d(want).normalize());
        return Math.toDegrees(Math.acos(Math.clamp(c, -1.0, 1.0)));
    }

    /**
     * Roll command (−1 … 1, the roll keys' axis: positive = right wing down) that levels the wings against the world's
     * up (+Y), for a landing on the gear. Zero once within a degree.
     */
    public static double levelRoll(Quaterniond attitude) {
        Vector3d up = attitude.transform(new Vector3d(0, 1, 0)), right = attitude.transform(new Vector3d(-1, 0, 0));
        double roll = Math.toDegrees(Math.atan2(-right.y, up.y));
        if (Math.abs(roll) < 1) return 0;
        // A key at 90°/s turns 4.5° a tick: take the full rate only while far from level.
        return Math.clamp(-roll / 4.5, -1.0, 1.0);
    }

    // ---------------------------------------------------------------- travel-time estimates

    /**
     * Time (s) for the warp drive to cover {@code distance} metres to a surface it must stop {@code arrival} metres
     * short of, under the real drive rules (NavServer): speed grows 15 % a tick from 1 km/s up to {@code maxSpeed}, and
     * the governor holds it to half the remaining distance per second. Walked in closed form per regime.
     */
    public static double warpSeconds(double distance, double arrival, double maxSpeed) {
        double d = distance, end = Math.max(1.0, arrival * WARP_RELEASE_FACTOR);
        if (d <= end) return 0;
        double t = 0, v = 1.0e3, tick = ShipFlightModel.TICK;
        // Spool: per tick v ← min(1.15 v, maxSpeed, d/2); walk ticks while the cap is the spool (at most ~200 ticks).
        for (int i = 0; i < 400 && d > end; i++) {
            double cap = Math.min(maxSpeed, 0.5 * d);
            if (v * 1.15 >= cap) break;
            v *= 1.15;
            d -= v * tick;
            t += tick;
        }
        if (d <= end) return t;
        // Cruise at maxSpeed while the governor allows it.
        double cruiseEnd = 2 * maxSpeed;
        if (d > cruiseEnd) {
            t += (d - cruiseEnd) / maxSpeed;
            d = cruiseEnd;
        }
        // Governor: d' = −d/2, so d(t) = d₀ e^{−t/2}.
        return t + 2 * Math.log(d / end);
    }

    /** Sublight flight time (s) on the main engine: accelerate half way, flip, brake (brachistochrone). */
    public static double sublightSeconds(double distance) {
        return 2 * Math.sqrt(Math.max(0, distance) / ShipFlightModel.MAIN_ACCEL);
    }

    /** Hyperjump time (s) for a chain of {@code hops}: each spools for {@link DriveRules#SPOOL_TICKS}. */
    public static double hyperjumpSeconds(int hops) {
        return hops * DriveRules.SPOOL_TICKS * ShipFlightModel.TICK;
    }

    /** "42 s", "3 min 05 s", "2 h 10 min", "5.2 d", "1,234 yr". */
    public static String formatDuration(double s) {
        if (!Double.isFinite(s)) return "∞";
        if (s < 60) return String.format(java.util.Locale.ROOT, "%.0f s", s);
        if (s < 3600) return String.format(java.util.Locale.ROOT, "%d min %02d s", (int) (s / 60), (int) (s % 60));
        if (s < 86400) return String.format(java.util.Locale.ROOT, "%d h %02d min", (int) (s / 3600), (int) (s % 3600 / 60));
        double days = s / 86400;
        if (days < 365.25) return String.format(java.util.Locale.ROOT, "%.1f d", days);
        return String.format(java.util.Locale.ROOT, "%,.0f yr", days / 365.25);
    }
}
