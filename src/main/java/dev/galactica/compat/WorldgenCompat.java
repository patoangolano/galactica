package dev.galactica.compat;

import dev.galactica.planet.PlanetEntry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detection and logging for the Overworld terrain mods (Tectonic, Lithostitched, Terralith, Continents) and the client
 * rendering mods tested with Galactica (Sodium Extra, Bobby, Nvidium, Cosmos). Nothing here links against them:
 * Galactica never needs their APIs. What makes them work is elsewhere and generic: the Earth hand-off rows follow the
 * Overworld's live build height ({@link PlanetEntry#entryY}), Distant Horizons keeps its own generator for the
 * Overworld (Galactica registers LOD generators only for {@code galactica:surface_*}), and the Earth sky does not depend
 * on terrain heights.
 */
public final class WorldgenCompat {
    private static final Logger LOG = LoggerFactory.getLogger("Galactica/Compat");

    /** Overworld terrain overhauls, by mod id: {@code tectonic-3.0.31-fabric-26.3.jar} and friends. */
    public static final String TECTONIC = "tectonic", LITHOSTITCHED = "lithostitched", TERRALITH = "terralith", CONTINENTS = "continents";
    /** Client rendering mods: {@code sodium-extra-fabric-0.9.4+mc26.3.jar}, {@code nvidium-0.4.4-beta7-26.3.jar}, {@code cosmos-1.0.3-26.3.jar}. */
    public static final String SODIUM_EXTRA = "sodium-extra", NVIDIUM = "nvidium", COSMOS = "cosmos";

    private WorldgenCompat() {
    }

    /** True when a mod reshaping the Overworld (and so Earth) is loaded. */
    public static boolean overworldReshaped() {
        return OptionalMods.loaded(TECTONIC) || OptionalMods.loaded(TERRALITH) || OptionalMods.loaded(CONTINENTS);
    }

    static void install() {
        for (String id : new String[] {TECTONIC, LITHOSTITCHED, TERRALITH, CONTINENTS}) {
            FabricLoader.getInstance().getModContainer(id).ifPresent(m ->
                    LOG.info("{} {} detected: Earth (the Overworld) uses its terrain; entry and exit rows follow the live Overworld height",
                            m.getMetadata().getName(), m.getMetadata().getVersion().getFriendlyString()));
        }
        if (overworldReshaped()) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                ServerLevel ow = server.overworld();
                LOG.info("Overworld build height Y [{}, {}], sea level {}: Earth entry row {}, take-off row {}",
                        ow.getMinY(), ow.getMaxY(), ow.getSeaLevel(), PlanetEntry.entryY(ow), PlanetEntry.exitY(ow));
            });
        }
        if (OptionalMods.loaded(SODIUM_EXTRA)) {
            LOG.info("Sodium Extra detected: keep its Sky and Stars toggles on, or the Overworld's real night sky and the planet skies vanish");
        }
        if (OptionalMods.loaded(NVIDIUM)) {
            // Nvidium switches itself off without NV_mesh_shader (non-NVIDIA, pre-Turing) and while an Iris pack is on;
            // Galactica draws its sky and near-planet terrain in its own passes, so either renderer is fine.
            LOG.info("Nvidium detected: terrain meshes go through mesh shaders on supported NVIDIA GPUs; Galactica's sky passes are unaffected");
        }
        if (OptionalMods.loaded(COSMOS)) {
            LOG.warn("Cosmos detected: it draws its own Overworld stars next to Galactica's real night sky. "
                    + "Turn off realNightSky in Galactica's config, or Cosmos, to keep one sky.");
        }
    }
}
