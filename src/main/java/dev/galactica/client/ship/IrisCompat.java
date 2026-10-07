package dev.galactica.client.ship;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether an Iris shader pack is drawing the world. Iris is optional: its API class is only touched through
 * {@link Api}, which the JVM loads on the first call made while Iris is installed.
 */
public final class IrisCompat {
    private IrisCompat() {}

    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

    public static boolean shaderPackActive() {
        return IRIS && Api.inUse();
    }

    private static final class Api {
        static boolean inUse() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
