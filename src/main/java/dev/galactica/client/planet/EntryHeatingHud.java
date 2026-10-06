package dev.galactica.client.planet;

import dev.galactica.planet.EntryHeating;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.system.Body;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The HUD half of entry heating (spec §5): an orange plasma glow creeping in from the screen edges
 * and a caption, fading with {@link EntryHeating#intensity} after arriving on a body with more than
 * 0.1 bar. The server shows the particle half to everyone. Arrival is a change of dimension into a
 * surface slot; joining a world already on the surface is not an entry and stays dark.
 */
public final class EntryHeatingHud {
    private EntryHeatingHud() {}

    private static final int BANDS = 10;
    private static ResourceKey<Level> lastDimension;
    private static long arrivedAt = Long.MIN_VALUE;

    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            lastDimension = null;
            return;
        }
        ResourceKey<Level> dim = mc.level.dimension();
        if (dim != lastDimension) {
            if (lastDimension != null && PlanetSlots.slotOf(dim) >= 0) arrivedAt = mc.level.getGameTime();
            lastDimension = dim;
        }
        Body body = SurfaceSkyOverlay.currentBody();
        if (body == null || arrivedAt == Long.MIN_VALUE) return;
        double intensity = EntryHeating.intensity(body.surfacePressureBar(), mc.level.getGameTime() - arrivedAt);
        if (intensity <= 0) return;

        int w = g.guiWidth(), h = g.guiHeight();
        int depth = (int) (Math.min(w, h) * 0.22 * intensity);
        for (int i = 0; i < BANDS; i++) {
            // Outer bands brightest: the shock layer wraps the vehicle, not the view's centre.
            int a = (int) (110 * intensity * (1 - (double) i / BANDS) / BANDS * 2.2);
            int colour = (Math.min(255, a) << 24) | 0xFF6A1E;
            int in = depth * i / BANDS, next = depth * (i + 1) / BANDS;
            g.fill(0, in, w, next, colour);
            g.fill(0, h - next, w, h - in, colour);
            g.fill(in, next, next, h - next, colour);
            g.fill(w - next, next, w - in, h - next, colour);
        }
        int text = ((int) (255 * Math.min(1, intensity * 2)) << 24) | 0xFFC080;
        if ((text >>> 24) > 8) g.centeredText(mc.font, Component.translatable("galactica.planet.heating"), w / 2, h / 4, text);
    }
}
