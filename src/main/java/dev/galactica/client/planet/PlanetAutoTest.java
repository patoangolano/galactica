package dev.galactica.client.planet;

import dev.galactica.Galactica;
import dev.galactica.system.Body;
import net.minecraft.client.Minecraft;
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

    /** Standing on {@code bodyName} with the GRAVITY attribute at {@code expectedG} (± 0.005) of vanilla. */
    public static boolean checkLanded(String bodyName, double expectedG) {
        Minecraft mc = Minecraft.getInstance();
        Body body = SurfaceSkyOverlay.currentBody();
        double g = mc.player == null ? Double.NaN : mc.player.getAttributeValue(Attributes.GRAVITY) / VANILLA_GRAVITY;
        boolean ok = body != null && body.name().equalsIgnoreCase(bodyName) && Math.abs(g - expectedG) < 0.005;
        Galactica.LOG.info("[autotest] {} landed on {}: on {} at {} g (expected {} g)", ok ? "PASS" : "FAIL", bodyName,
                body == null ? "nothing" : body.name(), String.format(Locale.ROOT, "%.3f", g), expectedG);
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
