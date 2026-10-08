package dev.galactica.ship;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Everything the flight, fuel, drive, collision and riding code needs to know about one craft, free of Minecraft
 * types. The Kestrel's values are {@link #KESTREL} (today's constants); a block-built ship computes its own from its
 * parts ({@link ShipStats}). Body frame as {@link ShipFlightModel}: +Z nose, +Y dorsal, +X port, blocks from the
 * pivot (the attitude centre), which sits {@link #pivot} above the entity origin.
 *
 * @param massKg          total mass
 * @param mainThrustN     main engines along +Z (nose)
 * @param reverseThrustN  engines along −Z (braking, "S")
 * @param rcsThrustN      RCS per translation axis (flight-assist side nulling, ventral)
 * @param liftThrustN     dorsal (+Y) thrust: RCS plus engines firing downward (VTOL lift); never below the RCS
 * @param maxHydrogen     hydrogen capacity, cells
 * @param maxExotic       exotic-matter capacity, units
 * @param maxHull         hull points
 * @param jumpRangeLy     hyperdrive tier range, light years; 0 = no hyperdrive
 * @param pivot           pivot above the entity origin, blocks
 * @param boxWidth        vanilla collision box width (square), blocks
 * @param boxHeight       vanilla collision box height, blocks
 * @param hullReach       farthest hull point from the pivot, blocks
 * @param seats           seat (hip) positions, body frame; the first is the pilot's
 * @param probes          hull collision probes, body frame
 * @param centreOfMass    body frame, blocks from the pivot
 * @param inertia         principal moments about the centre of mass (diagonal, kg·m²: x pitch, y yaw, z roll)
 * @param turnScale       share (0..1] of the Kestrel's commanded turn rates the RCS can deliver against the inertia
 */
public record ShipSpec(double massKg, double mainThrustN, double reverseThrustN, double rcsThrustN, double liftThrustN,
                       float maxHydrogen, int maxExotic, float maxHull, double jumpRangeLy,
                       float pivot, float boxWidth, float boxHeight, float hullReach,
                       List<Vector3fc> seats, float[][] probes, Vector3dc centreOfMass, Vector3dc inertia, double turnScale) {

    /** The hand-modelled Kestrel: the constants every test and autotest was tuned on. */
    public static final ShipSpec KESTREL = new ShipSpec(ShipFlightModel.MASS_KG, ShipFlightModel.MAIN_THRUST_N,
            ShipFlightModel.MAIN_THRUST_N, ShipFlightModel.RCS_THRUST_N, ShipFlightModel.RCS_THRUST_N,
            Starship.MAX_HYDROGEN, Starship.MAX_EXOTIC, Starship.MAX_HULL, DriveRules.TIER_RANGE_LY,
            ShipDimensions.PIVOT, ShipDimensions.BOX_WIDTH, ShipDimensions.BOX_HEIGHT, ShipDimensions.HULL_REACH,
            List.of(new Vector3f(ShipDimensions.PILOT_SEAT), new Vector3f(ShipDimensions.PASSENGER_SEAT)),
            ShipHullProbes.POINTS, new Vector3d(), new Vector3d(4.0e5, 6.0e5, 2.5e5), 1.0);

    public double mainAccel() {
        return massKg > 0 ? mainThrustN / massKg : 0;
    }

    public double reverseAccel() {
        return massKg > 0 ? reverseThrustN / massKg : 0;
    }

    public double rcsAccel() {
        return massKg > 0 ? rcsThrustN / massKg : 0;
    }

    public double liftAccel() {
        return massKg > 0 ? Math.max(rcsThrustN, liftThrustN) / massKg : 0;
    }

    /** Dorsal authority with or without fuel for the lift engines: dry, only the RCS (cold gas) pushes up. */
    public double liftAccel(boolean fuelled) {
        return fuelled ? liftAccel() : rcsAccel();
    }

    /** Thrust of the engines firing downward (VTOL lift), N: the dorsal thrust beyond the RCS. They burn hydrogen. */
    public double liftEngineN() {
        return Math.max(0, liftThrustN - rcsThrustN);
    }

    /** Acceleration the lift engines alone give, m/s². */
    public double liftEngineAccel() {
        return massKg > 0 ? liftEngineN() / massKg : 0;
    }

    /** Hydrogen cells per second with every lift engine at full thrust: the same flow per newton as the main engine. */
    public double liftHydrogenPerSecond() {
        return liftEngineN() / ShipFlightModel.MAIN_THRUST_N / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN;
    }

    /**
     * Share (0..1) of the lift engines a dorsal acceleration {@code accel} (m/s²) needs: the RCS gives the first
     * {@link #rcsAccel()} for free, the engines the rest.
     */
    public double liftEngineShare(double accel) {
        double e = liftEngineAccel();
        if (e <= 0 || accel <= rcsAccel()) return 0;
        return Math.min(1, (accel - rcsAccel()) / e);
    }

    /**
     * Hydrogen cells per second at full main throttle: the Kestrel's 2.5 MN burns one cell per
     * {@link ShipFlightModel#MAIN_SECONDS_PER_HYDROGEN} s, and fuel flow scales with thrust.
     */
    public double hydrogenPerSecond() {
        return Math.max(mainThrustN, reverseThrustN) / ShipFlightModel.MAIN_THRUST_N / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN;
    }

    public boolean hasHyperdrive() {
        return jumpRangeLy > 0;
    }

    public int seatCount() {
        return seats.size();
    }
}
