package dev.galactica.gametest;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Client GameTest smoke test (./gradlew runClientGameTest, opens a real window in
 * build/run/clientGameTest). Creates a fresh singleplayer world, launches the player with
 * {@code /galactica launch},
 * waits until the player is in galactica:space and the sky has had time to bake, and saves a
 * screenshot to build/run/clientGameTest/screenshots/NNNN_galactica_space_smoke.png, then force-lands
 * on Earth and saves NNNN_galactica_surface_smoke.png.
 */
public final class GalacticaClientGameTest implements FabricClientGameTest {
    /** Ticks for the first sky bake after arrival (SkyManager bakes off-thread, ~2–4 s at 2048 px). */
    private static final int SKY_SETTLE_TICKS = 200;
    /** Ticks for surface chunks (and, with -Pgalactica.dh=true, the first DH LOD rings) to appear. */
    private static final int SURFACE_SETTLE_TICKS = 400;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            // /galactica launch needs a player source; the test server runs commands as the console.
            world.getServer().runCommand("execute as @a run galactica launch");
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Galactica.SPACE, 20 * 30);
            context.waitTicks(SKY_SETTLE_TICKS);
            context.takeScreenshot("galactica_space_smoke");

            // Second leg: force-land on Earth and wait out the terrain (and DH LODs when DH is on the run).
            world.getServer().runCommand("execute as @a run galactica land Earth");
            context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().getPath().startsWith("surface_"), 20 * 60);
            context.waitTicks(SURFACE_SETTLE_TICKS);
            context.takeScreenshot("galactica_surface_smoke");
        }
    }
}
