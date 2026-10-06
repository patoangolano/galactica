package dev.galactica.planet;

import dev.galactica.system.Body;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.function.ToDoubleFunction;

/**
 * Surface gravity of the level an entity is in, for everything that is not a player: items, arrows,
 * boats, minecarts, mounts and ships (players get the GRAVITY attribute in {@link PlanetEntry}, which
 * the client already predicts with). Read from {@code Entity.getGravity()} by
 * {@code EntityGravityMixin}, so it must be cheap: an identity scan of 16 interned keys.
 *
 * <p>Server levels read the slot table; client levels ask {@link #client}, which the client side
 * fills from the local player's synced {@link SurfaceState} (a dedicated server's slot table never
 * reaches the client). Both sides must agree or item and vehicle prediction rubber-bands.
 */
public final class PlanetGravity {
    private PlanetGravity() {}

    private static final ResourceKey<?>[] KEYS = new ResourceKey<?>[PlanetSlots.COUNT];

    static {
        for (int i = 0; i < KEYS.length; i++) KEYS[i] = PlanetSlots.key(i);
    }

    /** Client-side surface g of a client level, or 1. Set by the client initialiser. */
    public static volatile ToDoubleFunction<Level> client = level -> 1.0;

    public static int slotOf(Level level) {
        ResourceKey<Level> dim = level.dimension();
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == dim) return i;
        return -1;
    }

    /** Factor on vanilla gravity in this level: the body's g on a surface, 1 anywhere else. */
    public static double scale(Level level) {
        int slot = slotOf(level);
        if (slot < 0) return 1.0;
        if (level.isClientSide()) return client.applyAsDouble(level);
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        return rt != null ? SurfaceGravity.factor(rt.body().surfaceGravityG()) : 1.0;
    }

    public static double scale(Body body) {
        return SurfaceGravity.factor(body.surfaceGravityG());
    }
}
