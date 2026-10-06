package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.star.Star;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Development-only visual regression run, enabled with {@code -Dgalactica.autotest=true}.
 * Once a world is loaded it drives the mod through commands (no keyboard or mouse input), aims
 * the camera, and saves screenshots to {@code screenshots/galactica_*.png}, then quits.
 */
public final class AutoTest {
    private AutoTest() {}

    private record Step(int waitTicks, Runnable action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index, countdown = 200;

    public static void install() {
        if (!Boolean.getBoolean("galactica.autotest")) return;
        Galactica.LOG.info("[autotest] enabled");
        cmd(0, "galactica launch");
        look(160, 90, 0);   shot(5, "01_earth_orbit_towards_gc");
        look(20, -90, 0);   shot(5, "02_earth_orbit_anticentre");
        lookAtNearest(20);  shot(5, "03_earth_orbit_sun");
        look(20, 0, -85);   shot(5, "04_earth_orbit_zenith");
        cmd(20, "galactica where");
        cmd(20, "galactica goto Betelgeuse");
        lookAtNearest(200); shot(5, "05_betelgeuse");
        cmd(20, "galactica goto Sirius A");
        lookAtNearest(200); shot(5, "06_sirius");
        cmd(20, "galactica coords 4000 -2500 15");
        look(200, 90, 0);   shot(5, "07_inner_disk_towards_gc");
        cmd(20, "galactica coords 300 200 400");
        look(200, 90, 40);  shot(5, "08_bulge_inside");
        cmd(20, "galactica coords 0 0 30000");
        look(240, 0, 89.9f); shot(5, "09_face_on_from_30kpc_above");
        cmd(20, "galactica coords -45000 0 3000");
        look(240, -90, 2);  shot(5, "10_edge_on_from_45kpc");
        cmd(20, "galactica jump 1");
        lookAtNearest(200); shot(5, "11_random_neighbour");
        STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
    }

    private static void tick(Minecraft mc) {
        mc.options.pauseOnLostFocus = false; // the run must not stall when the window is in the background
        if (mc.player == null || mc.level == null || index >= STEPS.size()) return;
        if (--countdown > 0) return;
        Step s = STEPS.get(index++);
        try {
            s.action().run();
        } catch (Throwable t) {
            Galactica.LOG.error("[autotest] step {} failed", index, t);
        }
        countdown = index < STEPS.size() ? Math.max(1, STEPS.get(index).waitTicks()) : 0;
    }

    private static void cmd(int wait, String command) {
        STEPS.add(new Step(wait, () -> {
            Galactica.LOG.info("[autotest] /{}", command);
            Minecraft.getInstance().player.connection.sendCommand(command);
        }));
    }

    private static void look(int wait, float yaw, float pitch) {
        STEPS.add(new Step(wait, () -> {
            var p = Minecraft.getInstance().player;
            p.setYRot(yaw);
            p.setXRot(pitch);
        }));
    }

    private static void lookAtNearest(int wait) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null) return;
            Star s = mgr.neighbourhood().nearestStar(eye);
            if (s == null) return; // intergalactic space: nothing to aim at
            double[] g = Neighbourhood.starPos(s).metresFrom(eye);
            double[] m = Frames.galacticToMc(g[0], g[1], g[2]);
            double h = Math.sqrt(m[0] * m[0] + m[2] * m[2]);
            var p = Minecraft.getInstance().player;
            // 2° off-axis so the target is not hidden behind the crosshair.
            p.setYRot((float) Math.toDegrees(Math.atan2(-m[0], m[2])) + 2f);
            p.setXRot((float) -Math.toDegrees(Math.atan2(m[1], h)));
            Galactica.LOG.info("[autotest] aiming at {} ({})", s.name(), s.spectralType());
        }));
    }

    private static void shot(int wait, String name) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            GalacticPos eye = SkyManager.get().viewer();
            Galactica.LOG.info("[autotest] screenshot {} at {}", name,
                    eye == null ? "?" : String.format("(%.1f, %.1f, %.1f) pc", eye.xPc(), eye.yPc(), eye.zPc()));
            Screenshot.grab(mc.gameDirectory, "galactica_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> Galactica.LOG.info("[autotest] {}", msg.getString()));
        }));
    }
}
