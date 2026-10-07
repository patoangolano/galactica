package dev.galactica.compat;

import dev.galactica.config.GalacticaConfigs;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second {@code main} entrypoint (declared in fabric.mod.json next to {@code dev.galactica.Galactica}),
 * so config loading and optional-mod hookups need no edit to the core initializer. Runs on both
 * physical sides. Each integration lives in its own class, loaded only after its mod-id check.
 */
public final class CompatBootstrap implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("Galactica/Compat");

    @Override
    public void onInitialize() {
        init();
    }

    /** Idempotent; may also be called directly from another initializer. */
    public static void init() {
        GalacticaConfigs.init();
        if (OptionalMods.distantHorizons()) {
            dev.galactica.compat.dh.GalacticaDh.install();
        }
        WorldgenCompat.install();
        if (OptionalMods.bobby()) {
            // Bobby caches chunks per dimension id. Landing slots are recycled between bodies, and the
            // slot wipe only clears server files, so a cached client copy of an old body could show.
            LOG.info("Bobby detected: if a landing site shows another planet's terrain, clear Bobby's cache for galactica:surface_*.");
        }
    }
}
