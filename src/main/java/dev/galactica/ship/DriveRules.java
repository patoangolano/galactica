package dev.galactica.ship;

import dev.galactica.science.Units;

/**
 * Fuel economics of the two FTL drives (spec §2). Pure arithmetic, shared by the server and the HUD.
 *
 * <ul>
 *   <li><b>Hyperjump</b>: range = min(exotic × 1 ly, tier range 10 ly); cost = ⌈distance in ly⌉ exotic.
 *       The drive spools for 5 s first, which is the window to abort by leaving the pilot seat.</li>
 *   <li><b>Warp</b>: free in the sublight-ish regime, 1 hydrogen per 10 s above 1 000 c, so crossing a
 *       system is cheap and crossing the disk is a logistics problem.</li>
 * </ul>
 */
public final class DriveRules {
    private DriveRules() {}

    public static final double TIER_RANGE_LY = 10.0;
    public static final double LY_PER_EXOTIC = 1.0;
    public static final int SPOOL_TICKS = 100;

    public static final double WARP_FREE_SPEED = 1_000.0 * Units.C;
    public static final double WARP_SECONDS_PER_HYDROGEN = 10.0;
    public static final double WARP_HYDROGEN_PER_TICK = ShipFlightModel.TICK / WARP_SECONDS_PER_HYDROGEN;

    public static double rangeLy(int exotic) {
        return Math.min(Math.max(0, exotic) * LY_PER_EXOTIC, TIER_RANGE_LY);
    }

    /** Exotic matter consumed by a jump of {@code distanceLy}; any jump costs at least one unit. */
    public static int exoticCost(double distanceLy) {
        return (int) Math.max(1, Math.ceil(distanceLy / LY_PER_EXOTIC - 1e-9));
    }

    public static boolean canJump(double distanceLy, int exotic) {
        return Double.isFinite(distanceLy) && distanceLy <= rangeLy(exotic) && exoticCost(distanceLy) <= exotic;
    }

    /** Hydrogen one tick of warp at {@code speed} m/s costs. */
    public static double warpHydrogenPerTick(double speed) {
        return speed > WARP_FREE_SPEED ? WARP_HYDROGEN_PER_TICK : 0.0;
    }
}
