package dev.galactica.gametest;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * Server GameTest smoke test ({@code ./gradlew runGameTest}, headless).
 *
 * <p>Vanilla 26.3 {@code GameTestServer.create} bakes its world from the FLAT_ALL_DIMENSIONS preset
 * against an empty {@code noDatapackDimensions} registry, so datapack {@code dimension/*.json}
 * levels (galactica:space, galactica:surface_N) are never created on the test server. What it can
 * check is everything those levels are built from: the datapack dimension types, the
 * {@code galactica:planet} chunk generator codec, and the /galactica command tree. Arrival in
 * galactica:space is covered by the client GameTest, which runs a real world.
 */
public final class GalacticaServerGameTest {

    @GameTest
    public void datapackAndCommandsLoad(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var dimensionTypes = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        for (String type : new String[]{"space", "surface"}) {
            helper.assertTrue(dimensionTypes.containsKey(Galactica.id(type)), "dimension_type galactica:" + type + " did not load");
        }
        helper.assertTrue(BuiltInRegistries.CHUNK_GENERATOR.containsKey(Galactica.id("planet")),
                "chunk generator galactica:planet is not registered");

        var root = server.getCommands().getDispatcher().getRoot().getChild("galactica");
        helper.assertTrue(root != null, "/galactica is not registered");
        for (String sub : new String[]{"launch", "land", "where", "maxwarp"}) {
            helper.assertTrue(root.getChild(sub) != null, "/galactica " + sub + " is not registered");
        }
        helper.succeed();
    }
}
