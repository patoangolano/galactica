package dev.galactica.planet;

/**
 * How a body's surface gravity scales vanilla's movement and fall rules.
 *
 * <p>Falling from h blocks lands with kinetic energy m·g·h. Vanilla deals
 * {@code (h − safe) × multiplier} damage at 1 g with safe = 3 blocks, so keeping the same damage for the
 * same impact energy means safe distance 3/g and multiplier g: damage = g·h − 3, exactly the Earth
 * damage of a g·h-block drop. A standing jump (0.42 blocks/tick launch, untouched, since leg power
 * does not depend on the planet) reaches at most ≈ 1.25/g blocks (drag keeps it lower), always inside 3/g, so jumping never hurts.
 */
public final class SurfaceGravity {
    private SurfaceGravity() {}

    /** Vanilla's SAFE_FALL_DISTANCE base value, blocks. */
    public static final double VANILLA_SAFE_FALL = 3.0;
    /** Floor on g: below it the attribute ranges (safe fall ≤ 1024, multiplier ≥ 0) stop meaning anything. */
    public static final double MIN_G = 0.003;

    /** Gravity multiplier on vanilla's 0.08 blocks/tick² (players) or each entity's default gravity. */
    public static double factor(double surfaceG) {
        return Math.max(MIN_G, surfaceG);
    }

    /** Fall height that does no damage, blocks: the drop with the impact energy of 3 blocks at 1 g. */
    public static double safeFallDistance(double surfaceG) {
        return Math.min(1024, VANILLA_SAFE_FALL / factor(surfaceG));
    }

    /** Fall damage per block beyond {@link #safeFallDistance}: impact energy per metre scales with g. */
    public static double fallDamageMultiplier(double surfaceG) {
        return Math.min(100, factor(surfaceG));
    }

    /**
     * Apex of a vanilla standing jump under g, blocks, simulated tick by tick as LivingEntity does it
     * (launch 0.42, gravity before drag, drag 0.98).
     */
    public static double jumpApex(double surfaceG) {
        double gravity = 0.08 * factor(surfaceG), vy = 0.42, y = 0, apex = 0;
        for (int tick = 0; tick < 20_000 && vy > 0; tick++) {
            y += vy;
            apex = Math.max(apex, y);
            vy = (vy - gravity) * 0.98;
        }
        return apex;
    }
}
