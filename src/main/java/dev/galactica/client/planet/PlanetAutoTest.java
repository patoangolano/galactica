package dev.galactica.client.planet;

import dev.galactica.Galactica;
import dev.galactica.system.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Locale;

/**
 * Checks for the autotest's landing steps (spec §7 items 5–7), run on the client after a
 * {@code /galactica land} has settled. Each logs one {@code [autotest] PASS|FAIL} line and returns
 * the verdict, so a failing landing is visible in the log without stopping the run.
 */
public final class PlanetAutoTest {
    private PlanetAutoTest() {}

    /** Vanilla's player gravity, blocks/tick². */
    private static final double VANILLA_GRAVITY = 0.08;

    /** Player deaths counted when the landing run started; -1 until {@link #markDeaths()}. */
    private static int deathsAtStart = -1;

    /** Deaths recorded by the integrated server for the local player, or -1 off a singleplayer world. */
    private static int deaths() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return -1;
        ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
        return sp == null ? -1 : sp.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
    }

    /** Starts counting deaths: every later landing fails if the player died since (a respawn lands too). */
    public static void markDeaths() {
        deathsAtStart = deaths();
    }

    /**
     * Standing on {@code bodyName} with the GRAVITY attribute at {@code expectedG} (± 0.005) of vanilla,
     * without having died since {@link #markDeaths()}: a fatal fall respawns the player on the same
     * planet, which the gravity check alone would pass.
     */
    public static boolean checkLanded(String bodyName, double expectedG) {
        Minecraft mc = Minecraft.getInstance();
        Body body = SurfaceSkyOverlay.currentBody();
        double g = mc.player == null ? Double.NaN : mc.player.getAttributeValue(Attributes.GRAVITY) / VANILLA_GRAVITY;
        int died = deathsAtStart < 0 ? 0 : deaths() - deathsAtStart;
        boolean ok = body != null && body.name().equalsIgnoreCase(bodyName) && Math.abs(g - expectedG) < 0.005 && died == 0;
        Galactica.LOG.info("[autotest] {} landed on {}: on {} at {} g (expected {} g), {} death(s)", ok ? "PASS" : "FAIL", bodyName,
                body == null ? "nothing" : body.name(), String.format(Locale.ROOT, "%.3f", g), expectedG, died);
        return ok;
    }

    /** Back in galactica:space with vanilla gravity restored by the take-off. */
    public static boolean checkInSpace() {
        Minecraft mc = Minecraft.getInstance();
        boolean ok = mc.level != null && mc.level.dimension() == Galactica.SPACE && SurfaceSkyOverlay.currentBody() == null;
        Galactica.LOG.info("[autotest] {} took off to space", ok ? "PASS" : "FAIL");
        return ok;
    }
}
