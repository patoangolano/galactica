package dev.galactica.client.hud;

import dev.galactica.client.nav.TargetReadout;
import dev.galactica.nav.autopilot.AutopilotMath;
import dev.galactica.nav.autopilot.AutopilotState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.joml.Vector3fc;

/**
 * The locked target on the HUD: a small panel at the top centre (name, kind, distance, ETA, the autopilot's phase,
 * the pilot's for a passenger) and, in space, a marker where the target lies on screen, or an arrow on the screen's
 * edge pointing toward it when it is behind or off to a side.
 */
public final class TargetHud {
    private TargetHud() {}

    private static final int TARGET = 0xFFFFD54F, TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, AUTO = 0xFF7FFFB0;

    public static void extract(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        TargetReadout r = TargetReadout.current();
        if (r == null) return;
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();

        Component l1 = Component.translatable("hud.galactica.target.line", r.target().name(),
                Component.translatable(r.target().kind().translationKey()), TargetReadout.distanceText(Math.max(0, r.surface())));
        Component l2 = r.eta() >= 0 ? Component.translatable(r.viaJump() ? "hud.galactica.target.eta_jump" : "hud.galactica.target.eta",
                AutopilotMath.formatDuration(r.eta())) : null;
        AutopilotState ap = r.state();
        Component l3 = ap.engaged() ? Component.translatable(r.fromPilot() ? "hud.galactica.autopilot.line.pilot" : "hud.galactica.autopilot.line",
                Component.translatable(ap.phase().translationKey()), ap.hops() > 0
                        ? Component.translatable("hud.galactica.autopilot.hops", ap.hops()) : Component.empty())
                : Component.translatable("hud.galactica.autopilot.line.off", dev.galactica.client.nav.AutopilotClient.toggleKey.getTranslatedKeyMessage());
        int pw = Math.max(font.width(l1), Math.max(l2 == null ? 0 : font.width(l2), font.width(l3))) + 12;
        int lines = l2 == null ? 2 : 3;
        int x0 = w / 2 - pw / 2, y0 = 4;
        g.fill(x0, y0, x0 + pw, y0 + lines * 11 + 6, 0x90000814);
        g.fill(x0, y0, x0 + pw, y0 + 1, TARGET);
        int y = y0 + 4;
        g.text(font, l1, x0 + 6, y, TARGET, false);
        y += 11;
        if (l2 != null) {
            g.text(font, l2, x0 + 6, y, TEXT, false);
            y += 11;
        }
        g.text(font, l3, x0 + 6, y, ap.engaged() ? AUTO : DIM, false);

        double[] d = r.dirMc();
        if (d != null) marker(g, font, mc, d, w, h, r);
    }

    /** Projects the target's direction through the main camera; on screen a diamond, off screen an edge arrow. */
    private static void marker(GuiGraphicsExtractor g, Font font, Minecraft mc, double[] d, int w, int h, TargetReadout r) {
        Camera cam = mc.gameRenderer.mainCamera();
        Vector3fc f = cam.forwardVector(), up = cam.upVector(), left = cam.leftVector();
        double z = d[0] * f.x() + d[1] * f.y() + d[2] * f.z();
        double x = -(d[0] * left.x() + d[1] * left.y() + d[2] * left.z());
        double y = d[0] * up.x() + d[1] * up.y() + d[2] * up.z();
        double tanY = Math.tan(Math.toRadians(cam.getFov()) / 2), aspect = (double) w / h;
        boolean onScreen = false;
        double sx = 0, sy = 0;
        if (z > 1e-3) {
            double nx = x / (z * tanY * aspect), ny = y / (z * tanY);
            sx = w / 2.0 * (1 + nx);
            sy = h / 2.0 * (1 - ny);
            onScreen = Math.abs(nx) < 0.95 && Math.abs(ny) < 0.95;
        }
        if (onScreen) {
            int cx = (int) sx, cy = (int) sy;
            for (int i = 0; i <= 5; i++) {
                g.fill(cx - 5 + i, cy - i, cx - 4 + i, cy - i + 1, TARGET);
                g.fill(cx + 5 - i, cy - i, cx + 6 - i, cy - i + 1, TARGET);
                g.fill(cx - 5 + i, cy + i, cx - 4 + i, cy + i + 1, TARGET);
                g.fill(cx + 5 - i, cy + i, cx + 6 - i, cy + i + 1, TARGET);
            }
            String label = r.target().name() + "  " + TargetReadout.distanceText(Math.max(0, r.surface()));
            g.text(font, label, cx + 9, cy - 4, TARGET, true);
            return;
        }
        // Edge arrow: the direction on the screen plane (behind the camera, mirrored so it still points the short way).
        double ax = x, ay = y;
        if (Math.abs(ax) + Math.abs(ay) < 1e-6) ay = -1;
        double len = Math.sqrt(ax * ax + ay * ay);
        ax /= len;
        ay /= len;
        double rx = w / 2.0 - 24, ry = h / 2.0 - 24;
        double k = Math.min(rx / Math.max(1e-6, Math.abs(ax)), ry / Math.max(1e-6, Math.abs(ay)));
        double px = w / 2.0 + ax * k, py = h / 2.0 - ay * k;
        g.pose().pushMatrix();
        g.pose().translate((float) px, (float) py);
        g.pose().rotate((float) Math.atan2(-ay, ax));
        for (int i = 0; i < 7; i++) g.fill(-i, -i, -i + 1, i + 1, TARGET);
        g.fill(-12, 0, -6, 1, TARGET);
        g.pose().popMatrix();
        g.centeredText(font, r.target().name(), (int) px, (int) py + (ay > 0 ? 10 : -16), TARGET);
    }
}
