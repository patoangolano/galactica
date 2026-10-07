package dev.galactica.client.hud;

import dev.galactica.client.sky.SkyManager;
import dev.galactica.client.sky.SpaceSkyRenderer;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Navigation readout in the top-left corner while in space. */
public final class NavHud {
    private NavHud() {}

    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, ACCENT = 0xFF7FE0FF, WARP = 0xFFFFC86E;
    private static final String[] ARMS = {"Norma Arm", "Scutum–Centaurus Arm", "Sagittarius–Carina Arm", "Perseus Arm"};

    public static void extract(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SpaceSkyRenderer.active()) return;
        SkyManager mgr = SkyManager.get();
        GalacticPos p = mgr.viewer();
        NavState nav = mgr.nav();
        if (p == null || nav == null) return;

        double x = p.xPc(), y = p.yPc(), z = p.zPc();
        double r = Math.sqrt(x * x + y * y);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("GALACTICA  ·  " + region(x, y, z), ACCENT));
        lines.add(new Line(String.format("R %.3f kpc   z %+.1f pc   %s", r / 1000, z, p.sector().code()), TEXT));
        lines.add(new Line(String.format("Sol %s   ρ★ %.3g pc⁻³", dist(p.distanceMetres(Frames.SOL_POS)),
                GalaxyModel.starDensity(x, y, z)), DIM));

        Neighbourhood nb = mgr.neighbourhood();
        Star s = nb.nearestStar(p);
        if (s != null) {
            int c = 0xFF000000 | Blackbody.srgbPacked(Math.max(1000, s.temperature()));
            lines.add(new Line(String.format("★ %s  %s  %s", s.name(), s.spectralType(),
                    dist(Neighbourhood.starPos(s).distanceMetres(p))), c));
            Neighbourhood.Located best = null;
            double bestD = Double.MAX_VALUE;
            for (Neighbourhood.Located l : nb.locateBodies(p, nav.timeSeconds())) {
                double d = l.pos().distanceMetres(p) - l.body().radiusMetres();
                if (d < bestD) {
                    bestD = d;
                    best = l;
                }
            }
            if (best != null) {
                lines.add(new Line(String.format("◉ %s  %s  %s  (%.2f g)", best.body().name(), best.body().type().label,
                        dist(Math.max(0, bestD)), best.body().surfaceGravityG()), TEXT));
            }
        }
        lines.add(new Line("Warp " + NavServer.formatSpeed(nav.speed()), nav.speed() > 0 ? WARP : DIM));
        if (HudScale.legend()) lines.add(new Line("[Sprint] warp   [Sneak] brake   /galactica", DIM));

        // The ship HUD sits unscaled on the right (from about 3/4 of the width), so the scaled nav panel is
        // kept left of it instead of running off screen at large hudScale values.
        int panelRight = 4 + panelWidth(mc.font, lines) + 10;
        HudScale.pushFitted(g, panelRight, MAX_RIGHT);
        try {
            draw(g, mc.font, lines, nav);
        } finally {
            HudScale.pop(g);
        }
    }

    /** Fraction of the screen width the scaled nav panel may reach (the ship HUD starts near 0.75). */
    private static final double MAX_RIGHT = 0.72;

    private static int panelWidth(Font font, List<Line> lines) {
        int w = 0;
        for (Line l : lines) w = Math.max(w, font.width(l.text));
        return w;
    }

    private static void draw(GuiGraphicsExtractor g, Font font, List<Line> lines, NavState nav) {
        int w = panelWidth(font, lines);
        int h = lines.size() * 11 + 6;
        g.fill(4, 4, 4 + w + 10, 4 + h, 0x90000814);
        g.fill(4, 4, 6, 4 + h, ACCENT);
        int yy = 8;
        for (Line l : lines) {
            g.text(font, l.text, 10, yy, l.colour, false);
            yy += 11;
        }
        // Warp bar: log-scaled from 1 km/s to the drive limit.
        if (nav.speed() > 0) {
            double f = Math.log(nav.speed() / 1e3) / Math.log(Math.max(1e3 * 1.0001, nav.maxSpeed()) / 1e3);
            int bw = (int) (Math.max(0, Math.min(1, f)) * (w + 6));
            g.fill(8, 4 + h - 3, 8 + bw, 4 + h - 1, WARP);
        }
    }

    private record Line(String text, int colour) {}

    static String region(double x, double y, double z) {
        double r = Math.sqrt(x * x + y * y);
        if (Math.sqrt(r * r + z * z) < 300) return "Galactic Centre";
        if (GalaxyModel.bulgeDensity(x, y, z) > 0.5) return "Galactic Bulge";
        if (Math.abs(z) > 3000 || r > 25_000) return "Stellar Halo";
        if (Math.abs(z) > 900) return "Thick Disk";
        return GalaxyModel.armStrength(x, y) > 0.4 ? ARMS[GalaxyModel.nearestArm(x, y)] : "Inter-arm Disk";
    }

    static String dist(double m) {
        if (m < 1e6) return String.format("%.0f m", m);
        if (m < 1e9) return String.format("%,.0f km", m / 1e3);
        if (m < 0.05 * Units.LIGHT_YEAR) return String.format("%.3f AU", m / Units.AU);
        if (m < 1000 * Units.LIGHT_YEAR) return String.format("%.2f ly", m / Units.LIGHT_YEAR);
        return String.format("%.2f kly", m / Units.LIGHT_YEAR / 1000);
    }
}
