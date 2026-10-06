package dev.galactica.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod ids of every optional integration, read from each 26.3 jar's own {@code fabric.mod.json}
 * (2026-10-06), plus presence checks. Code that touches an optional mod's classes must sit in a
 * class that is only loaded after the matching check returns true.
 */
public final class OptionalMods {
    /** DistantHorizons-3.3.4-26.3-fabric-neoforge.jar. */
    public static final String DISTANT_HORIZONS = "distanthorizons";
    /** modmenu-21.0.0.jar. */
    public static final String MOD_MENU = "modmenu";
    /** yet_another_config_lib_v3-3.9.7+26.3-fabric.jar: the id is not "yacl". */
    public static final String YACL = "yet_another_config_lib_v3";
    /** bobby-5.2.16+mc26.3.jar. */
    public static final String BOBBY = "bobby";
    /** geckolib-fabric-26.3-5.5.7.jar. */
    public static final String GECKOLIB = "geckolib";
    public static final String SODIUM = "sodium";
    public static final String IRIS = "iris";

    private OptionalMods() {
    }

    public static boolean loaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean distantHorizons() {
        return loaded(DISTANT_HORIZONS);
    }

    public static boolean yacl() {
        return loaded(YACL);
    }

    public static boolean bobby() {
        return loaded(BOBBY);
    }

    /**
     * GeckoLib presence. The ship renderer may pick an animated GeckoLib model when this is true and
     * fall back to the ModelPart {@code StarshipModel} otherwise; GeckoLib is compileOnly, never
     * required.
     */
    public static boolean geckolib() {
        return loaded(GECKOLIB);
    }
}
