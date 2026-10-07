package dev.galactica.client.hud;

import dev.galactica.Galactica;
import dev.galactica.content.OxygenTankItem;
import dev.galactica.survival.LifeSupport;
import dev.galactica.survival.LifeSupportModel;
import dev.galactica.survival.LifeSupportStatus;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Suit and environment readout in the bottom-right corner (spec §3 HUD). Shown whenever the air is not
 * breathable, a full suit is worn, a hazard applies or the player is in a ship cabin; hidden in ordinary
 * overworld play. Data comes from the server-synced {@link LifeSupport#STATUS}, because only the server
 * knows a planet's atmosphere.
 */
public final class LifeSupportHud {
    private LifeSupportHud() {}

    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, O2 = 0xFF4FC3FF, OK = 0xFF7FE08F, ALERT = 0xFFFF5A4F,
            WARN = 0xFFFFC86E, PANEL = 0x90000814;

    /**
     * Extra lines for the vehicle the player rides (ship fuel and hull), installed by the ship area; called with
     * the vehicle, returns {@code null} or an empty list for vehicles it does not know.
     */
    public static volatile Function<Entity, List<String>> vehicleReadout = v -> null;

    public static void initClient() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("life_support_hud"), LifeSupportHud::extract);
    }

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
        if (mc.player == null) return;
        LifeSupportStatus s = mc.player.getAttached(LifeSupport.STATUS);
        if (s == null) return;
        boolean hazard = s.has(LifeSupportStatus.F_SUFFOCATING) || s.has(LifeSupportStatus.F_COLD)
                || s.has(LifeSupportStatus.F_HOT) || s.has(LifeSupportStatus.F_CRUSHED);
        boolean cabin = s.has(LifeSupportStatus.F_CABIN);
        if (s.has(LifeSupportStatus.F_BREATHABLE) && !s.has(LifeSupportStatus.F_FULL_SUIT) && !hazard && !cabin) return;

        List<Line> lines = new ArrayList<>();
        String place = cabin ? I18n.get("hud.galactica.cabin")
                : s.pressureBar() < 1e-6f ? I18n.get("hud.galactica.vacuum")
                : I18n.get(s.has(LifeSupportStatus.F_BREATHABLE) ? "hud.galactica.breathable" : "hud.galactica.toxic");
        lines.add(new Line(String.format("%s  %s  %.0f K", place, pressure(s.pressureBar()), s.tempK()), TEXT));

        if (s.has(LifeSupportStatus.F_FULL_SUIT) || s.suitO2() > 0) {
            int col = s.has(LifeSupportStatus.F_SUIT_BREATHING) ? O2 : DIM;
            lines.add(new Line(I18n.get("hud.galactica.o2", OxygenTankItem.clock(s.suitO2()), OxygenTankItem.clock(s.reserveO2())), col));
            lines.add(new Line(I18n.get(s.has(LifeSupportStatus.F_FULL_SUIT) ? "hud.galactica.suit_sealed" : "hud.galactica.suit_open"),
                    s.has(LifeSupportStatus.F_FULL_SUIT) ? OK : WARN));
        }
        Entity vehicle = mc.player.getVehicle();
        if (vehicle != null) {
            List<String> extra = vehicleReadout.apply(vehicle);
            if (extra != null) for (String l : extra) lines.add(new Line(l, TEXT));
        }
        boolean blink = (mc.player.tickCount / 10) % 2 == 0;
        if (s.has(LifeSupportStatus.F_SUFFOCATING)) lines.add(new Line(I18n.get("hud.galactica.suffocating"), blink ? ALERT : WARN));
        if (s.has(LifeSupportStatus.F_COLD)) lines.add(new Line(I18n.get("hud.galactica.freezing"), ALERT));
        if (s.has(LifeSupportStatus.F_HOT)) lines.add(new Line(I18n.get("hud.galactica.overheating"), ALERT));
        if (s.has(LifeSupportStatus.F_CRUSHED)) lines.add(new Line(I18n.get("hud.galactica.overpressure"), ALERT));

        // Bottom-right, beside the hotbar: the scaled panel is kept right of the hotbar (182 wide, centred)
        // so a large hudScale does not spread it over the hotbar, hearts and armour.
        double room = g.guiWidth() - (g.guiWidth() / 2.0 + 91 + 4);
        float scale = HudScale.pushFitted(g, panelWidth(mc.font, lines) + 14, room / g.guiWidth());
        try {
            // The 40 px clearance above the hotbar is in screen pixels, so it is not scaled with the panel.
            draw(g, mc.font, lines, s, hazard, HudScale.width(g, scale), HudScale.height(g, scale) - Math.round(40 / scale));
        } finally {
            HudScale.pop(g);
        }
    }

    /** The panel in HUD units: right edge at {@code sw}, bottom edge at {@code bottom}. */
    private static int panelWidth(Font font, List<Line> lines) {
        int w = 90;
        for (Line l : lines) w = Math.max(w, font.width(l.text));
        return w;
    }

    private static void draw(GuiGraphicsExtractor g, Font font, List<Line> lines, LifeSupportStatus s, boolean hazard, int sw, int bottom) {
        int w = panelWidth(font, lines);
        int h = lines.size() * 11 + 9;
        int x0 = sw - w - 14, y0 = bottom - h;
        g.fill(x0, y0, sw - 4, y0 + h, PANEL);
        g.fill(sw - 6, y0, sw - 4, y0 + h, hazard ? ALERT : O2);
        int yy = y0 + 4;
        for (Line l : lines) {
            g.text(font, l.text, x0 + 5, yy, l.colour, false);
            yy += 11;
        }
        // Suit buffer gauge along the bottom edge of the panel.
        float f = Math.min(1f, s.suitO2() / (float) LifeSupportModel.SUIT_CAPACITY);
        g.fill(x0 + 5, y0 + h - 4, x0 + 5 + (int) (f * (w - 2)), y0 + h - 2, f < 0.2f ? ALERT : O2);
    }

    private record Line(String text, int colour) {}

    static String pressure(float bar) {
        if (bar < 1e-6f) return "0 bar";
        if (bar < 0.1f) return String.format("%.1f mbar", bar * 1000);
        return String.format("%.2f bar", bar);
    }
}
