package dev.galactica.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distant Horizons hookup. Only ever class-loaded from {@code CompatBootstrap} after
 * {@code isModLoaded("distanthorizons")} returned true, so the compileOnly API never has to exist
 * at runtime without DH.
 *
 * <p>On each DH level load, a server-side {@code galactica:surface_N} level gets a
 * {@link GalacticaDhGenerator}. Client-side levels (multiplayer without DH on the server) are left
 * to DH's own chunk-based LODs: the client has no {@code PlanetSlots} runtime to sample from.
 */
public final class GalacticaDh {
    private static final Logger LOG = LoggerFactory.getLogger("Galactica/DH");
    private static boolean installed;

    private GalacticaDh() {
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        LOG.info("Distant Horizons {} (API {}.{}.{}) detected: LOD terrain for galactica:surface_* enabled",
                DhApi.getModVersion(), DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), DhApi.getApiPatchVersion());
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> input) {
                try {
                    onLoad(input.value.levelWrapper);
                } catch (RuntimeException | LinkageError e) {
                    // Never let our hook break DH's level load: worst case the planet uses DH's default LODs.
                    LOG.error("Registering the Galactica LOD generator failed", e);
                }
            }
        });
    }

    private static void onLoad(IDhApiLevelWrapper level) {
        String dim = level.getDimensionName();
        if (DhLevels.isSpace(dim)) {
            LOG.info("DH loaded galactica:space; it has no terrain. Add it to DH's ignoredDimensionCsv to save memory.");
            return;
        }
        int slot = DhLevels.surfaceSlot(dim);
        if (slot < 0 || level.getLevelType() != EDhApiLevelType.SERVER_LEVEL) return;
        DhApiResult<Void> r = DhApi.worldGenOverrides.registerWorldGeneratorOverride(level, new GalacticaDhGenerator(level, slot));
        if (r.success) {
            LOG.info("DH LOD generator registered for {}", dim);
        } else {
            LOG.warn("DH refused the Galactica generator for {}: {}", dim, r.message);
        }
    }
}
