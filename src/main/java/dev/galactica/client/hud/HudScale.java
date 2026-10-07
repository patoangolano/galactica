package dev.galactica.client.hud;

import dev.galactica.config.ConfigEffects;
import dev.galactica.config.GalacticaConfigs;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Applies the {@code hudScale} setting to a Galactica HUD element. Usage:
 * <pre>{@code
 * float s = HudScale.push(g);
 * int sw = HudScale.width(g, s), sh = HudScale.height(g, s); // anchor right/bottom panels to these
 * ... draw ...
 * HudScale.pop(g);
 * }</pre>
 * The pose is scaled about the top-left corner, so coordinates inside are in HUD units.
 */
public final class HudScale {
    private HudScale() {}

    /** Pushes the pose scaled by the current setting and returns the factor. */
    public static float push(GuiGraphicsExtractor g) {
        float s = (float) GalacticaConfigs.get().hudScale();
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        return s;
    }

    /**
     * Pushes the pose scaled by the setting, lowered so a panel {@code panelWidth} GUI units wide
     * (unscaled) spans at most the fraction {@code maxShare} of the screen width; returns the factor used.
     */
    public static float pushFitted(GuiGraphicsExtractor g, int panelWidth, double maxShare) {
        double setting = GalacticaConfigs.get().hudScale();
        float s = (float) ConfigEffects.fitScale(setting, panelWidth, g.guiWidth() * maxShare);
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        return s;
    }

    public static void pop(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
    }

    /** Screen width in HUD units under scale {@code s}. */
    public static int width(GuiGraphicsExtractor g, float s) {
        return ConfigEffects.scaledExtent(g.guiWidth(), s);
    }

    /** Screen height in HUD units under scale {@code s}. */
    public static int height(GuiGraphicsExtractor g, float s) {
        return ConfigEffects.scaledExtent(g.guiHeight(), s);
    }

    /** Whether key/colour legends should be drawn ({@code showLegend}). */
    public static boolean legend() {
        return GalacticaConfigs.get().showLegend();
    }
}
