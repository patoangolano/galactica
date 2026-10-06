package dev.galactica.survival;

import dev.galactica.Galactica;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Decides which {@link Environment} a player breathes, in priority order:
 * <ol>
 *   <li>riding a {@code galactica:ship} (pilot or passenger): the pressurised {@link Environment#CABIN};</li>
 *   <li>in {@code galactica:space}: {@link Environment#VACUUM};</li>
 *   <li>whatever {@link #provider} answers (the planet area installs one for its surface dimensions);</li>
 *   <li>otherwise {@link Environment#EARTH}, which covers the overworld and every vanilla dimension.</li>
 * </ol>
 * The ship is matched by entity type id so survival does not depend on the ship classes.
 */
public final class SurvivalEnvironment {
    private SurvivalEnvironment() {}

    /** Answers the environment for a player, or {@code null} for "not one of mine" (falls through to Earth). */
    @FunctionalInterface
    public interface Provider {
        Environment environment(ServerPlayer player);
    }

    public static final Identifier SHIP_TYPE = Galactica.id("ship");

    /** Pluggable hook. Replace it; to chain, capture the previous value and delegate to it on {@code null}. */
    public static volatile Provider provider = p -> null;

    public static Environment of(ServerPlayer p) {
        if (insideShip(p)) return Environment.CABIN;
        if (p.level().dimension() == Galactica.SPACE) return Environment.VACUUM;
        Environment e = provider.environment(p);
        return e != null ? e : Environment.EARTH;
    }

    public static boolean insideShip(ServerPlayer p) {
        for (Entity v = p.getVehicle(); v != null; v = v.getVehicle()) {
            if (SHIP_TYPE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()))) return true;
        }
        return false;
    }
}
