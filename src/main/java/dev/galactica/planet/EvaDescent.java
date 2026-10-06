package dev.galactica.planet;

/**
 * When a suited (shipless) survival landing loses its descent flight. Flight is lent only to come
 * down from Y 600: it ends on touchdown, as soon as the player climbs more than {@link #CLIMB_ALLOWANCE}
 * above the lowest point of the descent (so it cannot be used to fly across the planet), or after
 * {@link #MAX_TICKS}, longer than a straight drop from Y 600 at creative descent speed (≈ 70 s).
 */
public final class EvaDescent {
    private EvaDescent() {}

    public static final double CLIMB_ALLOWANCE = 2.0;
    public static final int MAX_TICKS = 3 * 60 * 20;

    public static boolean ends(boolean onGround, double y, double lowestY, long ticksSinceEntry) {
        return onGround || y > lowestY + CLIMB_ALLOWANCE || ticksSinceEntry > MAX_TICKS;
    }
}
