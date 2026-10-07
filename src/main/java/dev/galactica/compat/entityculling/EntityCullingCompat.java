package dev.galactica.compat.entityculling;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipEntities;
import dev.galactica.ship.Starship;
import dev.tr7zw.entityculling.EntityCullingModBase;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * EntityCulling (tr7zw) hides entities whose hitbox it cannot see past blocks, and skips their client ticks. A
 * starship's hull, wings and exhaust reach well outside its hitbox, and its client tick runs the flight prediction
 * and the crew's seats, so ships and everyone aboard are whitelisted from both: a ship landed behind a ridge kept
 * its wings in view while the mod hid the whole hull, and a culled ship's crew stopped following it. Only loaded
 * when EntityCulling is ({@code OptionalMods.ENTITY_CULLING}).
 */
public final class EntityCullingCompat {
    private EntityCullingCompat() {}

    private static boolean dynamic, types;

    public static void install() {
        // EntityCulling fills its type sets from its config on its first client tick: add ours after that.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            EntityCullingModBase ec = EntityCullingModBase.instance;
            if (ec == null || dynamic && types) return;
            try {
                if (!dynamic) {
                    ec.addDynamicEntityWhitelist(e -> e instanceof Starship || e.getRootVehicle() instanceof Starship);
                    dynamic = true;
                }
                if (!types && ec.entityWhitelist != null && ec.tickCullWhitelists != null && mc.level != null) {
                    ec.entityWhitelist.add(ShipEntities.SHIP);
                    ec.tickCullWhitelists.add(ShipEntities.SHIP);
                    types = true;
                    Galactica.LOG.info("EntityCulling: starships and their crews are never culled");
                }
            } catch (RuntimeException e) {
                // An EntityCulling version with other internals: leave it alone rather than fail every tick.
                dynamic = types = true;
                Galactica.LOG.warn("EntityCulling whitelist failed; starships may be culled behind terrain", e);
            }
        });
    }
}
