package dev.galactica.client.planet;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The HUD half of entry heating (spec §5, M7). Strength is continuous across both dimensions: it is the
 * visible plasma glow of {@link TransferClient} (Sutton–Graves heat flux from real altitude and speed, held by
 * the heat shield's thermal lag), not a timer since arrival. The smooth vignette, flow lines and switch veil are
 * drawn by the shader pass in {@link TransferFx}; this element adds the caption, and only if that pass is
 * unavailable (shader failed, or Iris refused it) does it paint a banded edge glow and the switch veil itself.
 */
public final class EntryHeatingHud {
    private EntryHeatingHud() {}

    private static final int BANDS = 16;

    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        TransferClient.frame();
        double intensity = TransferClient.glow();
        double mask = TransferClient.mask();
        int w = g.guiWidth(), h = g.guiHeight();

        if (!TransferFx.screenActive()) fallback(g, w, h, intensity, mask);
        if (intensity <= 0.05) return;
        int text = ((int) (255 * Math.min(1, intensity * 2)) << 24) | 0xFFC080;
        if ((text >>> 24) > 8) g.centeredText(mc.font, Component.translatable("galactica.planet.heating"), w / 2, h / 4, text);
    }

    private static void fallback(GuiGraphicsExtractor g, int w, int h, double intensity, double mask) {
        int rgb = intensity > 0.05 ? plasmaRgb(TransferClient.temperatureNorm()) : 0xDCE6F5;
        if (intensity > 0) {
            int depth = (int) (Math.min(w, h) * 0.22 * intensity);
            for (int i = 0; i < BANDS; i++) {
                // Outer bands brightest: the shock layer wraps the vehicle, not the view's centre.
                int a = (int) (180 * intensity * (1 - (double) i / BANDS) / BANDS * 2.2);
                int colour = (Math.min(255, a) << 24) | rgb;
                int in = depth * i / BANDS, next = depth * (i + 1) / BANDS;
                g.fill(0, in, w, next, colour);
                g.fill(0, h - next, w, h - in, colour);
                g.fill(in, next, next, h - next, colour);
                g.fill(w - next, next, w - in, h - next, colour);
            }
        }
        if (mask > 0.01) g.fill(0, 0, w, h, ((int) (255 * mask) << 24) | rgb);
    }

    /** The shader's colour ramp on the CPU: orange-red → yellow-white → white → white-violet. */
    private static int plasmaRgb(double tn) {
        double[][] stops = {{1.0, 0.22, 0.04}, {1.0, 0.48, 0.12}, {1.0, 0.86, 0.62}, {1.0, 0.97, 0.95}, {0.86, 0.78, 1.0}};
        double[] at = {0.0, 0.25, 0.55, 0.8, 1.0};
        int k = 0;
        while (k < at.length - 2 && tn > at[k + 1]) k++;
        double f = Math.clamp((tn - at[k]) / (at[k + 1] - at[k]), 0.0, 1.0);
        int r = (int) (255 * (stops[k][0] + (stops[k + 1][0] - stops[k][0]) * f));
        int gr = (int) (255 * (stops[k][1] + (stops[k + 1][1] - stops[k][1]) * f));
        int b = (int) (255 * (stops[k][2] + (stops[k + 1][2] - stops[k][2]) * f));
        return (r << 16) | (gr << 8) | b;
    }
}
