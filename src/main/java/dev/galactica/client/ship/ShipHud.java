package dev.galactica.client.ship;

import dev.galactica.ship.DriveRules;
import dev.galactica.ship.Starship;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Ship readout on the right edge for anyone aboard, outside the cockpit view: mode, fuel, exotic range, hull, speed, spool. */
public final class ShipHud {
    private ShipHud() {}

    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, ACCENT = 0xFFF07A1A, WARN = 0xFFFF5A4A, CYAN = 0xFF7FE0FF;

    public static void extract(GuiGraphicsExtractor g, DeltaTracker dt) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            extractTimed(g, dt);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.HUD, perf);
        }
    }

    private static void extractTimed(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof Starship ship)) return;
        // In the cockpit the panel display shows all of this (and more); the overlay would only cover the console.
        if (mc.options.getCameraType().isFirstPerson() && CockpitDisplay.ready()) return;
        Font font = mc.font;

        // Displacement over the last tick works for pilot and passenger alike (a passenger's copy only interpolates).
        double speed = Math.sqrt(sq(ship.getX() - ship.xo) + sq(ship.getY() - ship.yo) + sq(ship.getZ() - ship.zo)) * 20.0;
        // In space, relative to the body whose frame the ship flies in (the frame carries most of an orbit's speed).
        var orbit = dev.galactica.client.hud.OrbitReadout.current(mc);
        if (orbit != null) speed = orbit.speed();
        float h = ship.hydrogen(), hull = ship.hull();
        String[] lines = {
                tr("hud.galactica.ship.title", tr(ship.coupled() ? "hud.galactica.ship.coupled" : "hud.galactica.ship.decoupled")),
                tr("hud.galactica.ship.hydrogen", String.format("%.1f", h), String.format("%.0f", ship.maxHydrogen())),
                tr("hud.galactica.ship.exotic", ship.exotic(), String.format("%.0f", DriveRules.rangeLy(ship.exotic(), ship.jumpTierLy()))),
                tr("hud.galactica.ship.hull", Math.round(hull)),
                tr("hud.galactica.ship.speed", String.format("%.1f", speed)),
        };
        int[] colours = {ACCENT, h < 5 ? WARN : TEXT, DIM, hull < 25 ? WARN : TEXT, TEXT};
        String spool = ship.isSpooling() ? tr("hud.galactica.ship.spooling", String.format("%.1f", ship.spoolTicks() / 20.0)) : null;

        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        if (spool != null) w = Math.max(w, font.width(spool));
        int rows = lines.length + (spool != null ? 1 : 0);
        // Right edge, below the status-effect icons that own the top-right corner.
        int hgt = rows * 11 + 8, x1 = g.guiWidth() - 4, x0 = x1 - w - 12, y0 = Math.max(56, g.guiHeight() / 2 - hgt);
        g.fill(x0, y0, x1, y0 + hgt, 0x90000814);
        g.fill(x1 - 2, y0, x1, y0 + hgt, ACCENT);
        int y = y0 + 4;
        for (int i = 0; i < lines.length; i++) {
            g.text(font, lines[i], x0 + 5, y, colours[i], false);
            y += 11;
        }
        if (spool != null) g.text(font, spool, x0 + 5, y, CYAN, false);
        // Hydrogen gauge under the panel.
        int bw = (int) ((x1 - x0 - 8) * Math.min(1F, h / ship.maxHydrogen()));
        g.fill(x0 + 4, y0 + hgt - 3, x0 + 4 + bw, y0 + hgt - 1, h < 5 ? WARN : CYAN);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static double sq(double x) {
        return x * x;
    }
}
