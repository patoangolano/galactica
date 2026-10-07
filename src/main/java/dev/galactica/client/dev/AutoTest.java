package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.star.Star;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Development-only visual regression run, enabled with {@code -Dgalactica.autotest=true}.
 * Once a world is loaded it drives the mod through commands and in-game key states (never OS
 * input), aims the camera, and saves screenshots to {@code screenshots/galactica_*.png}, then quits.
 * {@code -Dgalactica.autotest.tag=iris} suffixes the file names so shader-pack runs can be compared.
 */
public final class AutoTest {
    private AutoTest() {}

    private record Step(int waitTicks, Runnable action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static final String TAG = System.getProperty("galactica.autotest.tag", "");
    private static int index, countdown = 200;
    private static Consumer<Minecraft> perTick = mc -> {};

    public static void install() {
        if (!Boolean.getBoolean("galactica.autotest")) return;
        Galactica.LOG.info("[autotest] enabled{}", TAG.isEmpty() ? "" : " (" + TAG + ")");
        if ("venus".equals(System.getProperty("galactica.autotest.mode"))) {
            cmd(0, "galactica kit");
            cmd(10, "galactica launch");
            cmd(40, "galactica goto Sol");
            STEPS.add(new Step(10, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Venus")));
            STEPS.add(new Step(40, () -> Minecraft.getInstance().player.connection.sendCommand("galactica ground")));
            for (int yaw = 0; yaw < 360; yaw += 60) {
                look(yaw == 0 ? 80 : 10, yaw, -20);
                shot(5, "venus_yaw" + yaw);
            }
            look(10, 30, 5); shot(5, "venus_repro");
            STEPS.add(new Step(20, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("showcase".equals(System.getProperty("galactica.autotest.mode"))) {
            showcase();
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        // M6: the real night sky over the Overworld (Milky Way from Sol, real bright stars).
        cmd(0, "time set midnight");
        look(60, 0, -60);   shot(5, "00a_earth_night_up");
        look(20, 90, -25);  shot(5, "00b_earth_night_east");
        look(20, 270, -25); shot(5, "00c_earth_night_west");
        cmd(5, "galactica kit"); // unsuited, life support (correctly) kills the test pilot in seconds
        cmd(10, "galactica launch");
        look(160, 90, 0);   shot(5, "01_earth_orbit_towards_gc");
        look(20, -90, 0);   shot(5, "02_earth_orbit_anticentre");
        lookAtNearest(20);  shot(5, "03_earth_orbit_sun");
        cmd(20, "galactica orbit Earth");
        lookAtBody(60, "Earth");   shot(5, "04_earth_close");
        cmd(20, "galactica orbit Moon");
        lookAtBody(60, "Moon");    shot(5, "05_moon_close");
        cmd(20, "galactica orbit Jupiter");
        lookAtBody(60, "Jupiter"); shot(5, "06_jupiter_close");
        cmd(20, "galactica orbit Saturn");
        lookAtBody(60, "Saturn");  shot(5, "07_saturn_close");
        cmd(20, "galactica goto Betelgeuse");
        lookAtNearest(200); shot(5, "08_betelgeuse");
        // M5: spectrum of the target in the crosshair, then the codex.
        lookAtNearestExact(5);
        STEPS.add(new Step(5, () -> {
            Galactica.LOG.info("[autotest] spectrometer scan");
            dev.galactica.discovery.SpectrometerItem.scanHook.run();
        }));
        shot(60, "08b_spectrum_betelgeuse");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(new dev.galactica.client.discovery.CodexScreen())));
        shot(20, "08c_codex");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(null)));
        // M6: deep sky up close.
        visitGalactic(20, 209.01, -19.38, 360, "08d_orion_nebula", 412);
        visitGalactic(20, 166.57, -23.52, 118, "08e_pleiades", 136);
        cmd(20, "galactica goto Sagittarius A*");
        STEPS.add(new Step(200, () -> aimAt(SkyManager.get().viewer(), GalacticPos.ofParsecs(0, 0, 0), 0f)));
        shot(5, "08f_sgr_a_star");
        cmd(20, "galactica coords 4000 -2500 15");
        look(200, 90, 0);   shot(5, "09_inner_disk_towards_gc");
        cmd(20, "galactica coords 300 200 400");
        look(200, 90, 40);  shot(5, "10_bulge_inside");
        cmd(20, "galactica coords 0 0 30000");
        look(240, 0, 89.9f); shot(5, "11_face_on_from_30kpc_above");
        cmd(20, "galactica coords -45000 0 3000");
        look(240, -90, 2);  shot(5, "12_edge_on_from_45kpc");
        // Real warp: hold sprint through the game's own key mapping while flying towards the Galactic Centre.
        cmd(20, "galactica goto Sol");
        look(60, 90, 0);
        warp(5, true);
        shot(200, "13_warp_spooling");
        logNav(5);
        shot(100, "14_warp_full");
        logNav(5);
        warp(5, false);
        shot(60, "15_warp_dropout");
        cmd(5, "galactica where");
        cmd(20, "galactica jump 1");
        lookAtNearest(200); shot(5, "16_random_neighbour");
        cmd(20, "galactica system");
        // M3: starship. Deploy and board near Earth, burn the main engine for 5 s, then a hyperjump.
        cmd(20, "galactica goto Sol");
        cmd(40, "galactica ship");
        // The ship, not the pilot, sets the view while flying: turn the hull so the Sun is where each shot wants it
        // (azimuth toward port from the nose, elevation above the ship's horizon).
        clearChat(40);
        orientShip(5, 35, 30);       shot(10, "17_ship_boarded");
        // Exteriors without the HUD: the chase view with the Sun behind and above the camera, then a 3/4 front orbit.
        hud(5, false);
        camera(5, net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        orientShip(5, 150, 35);      shot(10, "17b_ship_exterior");
        orientShip(5, -60, 40);
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(145, 18, 7.5F)));
        shot(10, "17c_ship_exterior_front");
        STEPS.add(new Step(5, dev.galactica.client.ship.ShipCamera::clearOrbit));
        hud(5, true);
        // Main engine for 5 s in the chase view: the plume streams back toward the camera.
        orientShip(5, 150, 35);
        clearChat(5);
        key(5, "keyUp", true);
        shot(100, "18_ship_thrust");
        key(5, "keyUp", false);
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
        cmd(20, "galactica where");
        cmd(20, "galactica nearest 3");
        STEPS.add(new Step(20, () -> Minecraft.getInstance().player.connection.sendCommand("galactica fuel 400 16")));
        STEPS.add(new Step(20, () -> {
            Galactica.LOG.info("[autotest] opening galaxy map");
            dev.galactica.client.map.GalaxyMapClient.open();
        }));
        shot(200, "19_galaxy_map");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(null)));
        // M4: planets. Land on Mars and Earth, check gravity, then climb back to space.
        cmd(20, "galactica goto Sol");
        STEPS.add(new Step(1, dev.galactica.client.planet.PlanetAutoTest::markDeaths));
        STEPS.add(new Step(10, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Mars")));
        look(200, 0, -10);  shot(5, "20_mars_descent");
        STEPS.add(new Step(5, () -> dev.galactica.client.planet.PlanetAutoTest.checkLanded("Mars", 0.38)));
        cmd(5, "galactica ground");
        look(80, 30, 8);    shot(5, "21_mars_ground");
        look(20, 210, 5);   shot(5, "21b_mars_ground_back");
        cmd(20, "galactica goto Sol");
        STEPS.add(new Step(10, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Earth")));
        look(200, 0, -10);  shot(5, "22_earth_descent");
        STEPS.add(new Step(5, () -> dev.galactica.client.planet.PlanetAutoTest.checkLanded("Earth", 1.0)));
        cmd(5, "galactica ground");
        look(80, 30, 8);    shot(5, "22b_earth_ground");
        cmd(20, "galactica goto Sol");
        STEPS.add(new Step(10, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Moon")));
        look(200, 0, -15);  shot(5, "23_moon_descent");
        STEPS.add(new Step(5, () -> dev.galactica.client.planet.PlanetAutoTest.checkLanded("Moon", 0.165)));
        cmd(5, "galactica ground");
        look(80, 30, -12);  shot(5, "23b_moon_ground");
        cmd(20, "galactica goto Sol");
        STEPS.add(new Step(10, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Venus")));
        STEPS.add(new Step(40, () -> Minecraft.getInstance().player.connection.sendCommand("galactica ground")));
        look(80, 30, 5);    shot(5, "24_venus_ground");
        STEPS.add(new Step(60, () -> Minecraft.getInstance().stop()));
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
    }

    /** Empties the chat and the action-bar line, so earlier command output does not cover the next shot. */
    private static void clearChat(int wait) {
        STEPS.add(new Step(wait, () -> {
            var gui = Minecraft.getInstance().gui;
            gui.hud.getChat().clearMessages(false);
            gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }));
    }

    /** Shows or hides the whole HUD (F1). */
    private static void hud(int wait, boolean visible) {
        STEPS.add(new Step(wait, () -> {
            var hud = Minecraft.getInstance().gui.hud;
            if (hud.isHidden() == visible) hud.toggle();
        }));
    }

    private static void camera(int wait, net.minecraft.client.CameraType type) {
        STEPS.add(new Step(wait, () -> Minecraft.getInstance().options.setCameraType(type)));
    }

    /**
     * Turns the piloted ship (yaw and pitch, no roll) so the host star lies at {@code sunAzDeg} toward port from the
     * nose and {@code sunElDeg} above the ship's horizon, as close as a 1° search gets. The pilot's client is
     * authoritative for the attitude, so setting it here is what the server receives next tick.
     */
    private static void orientShip(int wait, float sunAzDeg, float sunElDeg) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            var ship = dev.galactica.client.ship.ShipClient.piloted(mc);
            if (ship == null) {
                Galactica.LOG.warn("[autotest] FAIL orient ship: not piloting");
                return;
            }
            var state = new dev.galactica.client.ship.StarshipRenderState();
            state.x = ship.getX();
            state.y = ship.getY();
            state.z = ship.getZ();
            dev.galactica.client.ship.ShipEnvironment.sample(ship, state);
            if (state.toStar.lengthSquared() < 0.5F) {
                Galactica.LOG.warn("[autotest] FAIL orient ship: no host star (direct {})", state.direct);
                return;
            }
            double az = Math.toRadians(sunAzDeg), el = Math.toRadians(sunElDeg);
            // Body frame: +Z nose, +X port, +Y up.
            org.joml.Vector3f body = new org.joml.Vector3f((float) (Math.sin(az) * Math.cos(el)), (float) Math.sin(el),
                    (float) (Math.cos(az) * Math.cos(el)));
            org.joml.Quaternionf best = new org.joml.Quaternionf(), q = new org.joml.Quaternionf();
            org.joml.Vector3f v = new org.joml.Vector3f();
            float bestDot = -2F;
            for (int yaw = 0; yaw < 360; yaw++) {
                for (int pitch = -85; pitch <= 85; pitch++) {
                    q.rotationYXZ((float) Math.toRadians(yaw), (float) Math.toRadians(pitch), 0F);
                    float d = q.transform(v.set(body)).dot(state.toStar);
                    if (d > bestDot) {
                        bestDot = d;
                        best.set(q);
                    }
                }
            }
            ship.setAttitude(best);
            Galactica.LOG.info("[autotest] ship oriented: Sun at az {} el {} (miss {} deg), direct light {}, toStar {}",
                    sunAzDeg, sunElDeg, String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(Math.acos(Math.min(1F, bestDot)))),
                    state.direct, state.toStar);
        }));
    }

    /** Presses or releases a key mapping inside the game (no OS input). */
    private static void key(int wait, String mapping, boolean down) {
        STEPS.add(new Step(wait, () -> {
            var o = Minecraft.getInstance().options;
            var k = switch (mapping) {
                case "keyUp" -> o.keyUp;
                case "keyDown" -> o.keyDown;
                case "keyJump" -> o.keyJump;
                default -> throw new IllegalArgumentException(mapping);
            };
            Galactica.LOG.info("[autotest] key {} {}", mapping, down ? "down" : "up");
            if (down) {
                perTick = perTick.andThen(m -> k.setDown(true));
            } else {
                k.setDown(false);
                perTick = m -> {};
            }
        }));
    }

    /**
     * A ~60 s take for the showcase video: slow pans only, no screenshots, HUD hidden. Recorded from
     * outside the game with um win record.
     */
    private static void showcase() {
        cmd(0, "galactica kit");
        STEPS.add(new Step(5, () -> {
            var hud = Minecraft.getInstance().gui.hud;
            if (!hud.isHidden()) hud.toggle();
        }));
        cmd(5, "galactica launch");
        look(140, 60, -5);
        pan(5, 160, 0.25f, 0f);                 // the Milky Way from Earth orbit
        cmd(5, "galactica orbit Earth");
        lookAtBody(20, "Earth");
        pan(5, 120, 0.04f, 0f);
        cmd(5, "galactica orbit Saturn");
        lookAtBody(20, "Saturn");
        pan(5, 120, 0.04f, 0f);
        cmd(5, "galactica goto Betelgeuse");
        lookAtNearest(120);
        pan(5, 100, 0.08f, 0f);
        cmd(5, "galactica goto Sol");
        look(60, 90, 0);
        warp(5, true);
        pan(5, 260, 0.05f, 0f);                 // warp spool towards the Galactic Centre
        warp(5, false);
        cmd(40, "galactica coords 300 200 400");
        look(140, 90, 30);
        pan(5, 140, 0.2f, 0f);                  // inside the bulge
        cmd(5, "galactica coords 0 0 30000");
        look(160, 0, 89.9f);
        pan(5, 160, 0.15f, 0f);                 // face-on
        cmd(5, "galactica coords -45000 0 3000");
        look(160, -90, 2);
        pan(5, 140, 0.05f, 0f);                 // edge-on
        STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
    }

    /** Rotates the camera by the given degrees per tick for {@code ticks} ticks. */
    private static void pan(int wait, int ticks, float yawPerTick, float pitchPerTick) {
        STEPS.add(new Step(wait, () -> {
            Consumer<Minecraft> previous = perTick;
            int[] left = {ticks};
            perTick = m -> {
                previous.accept(m);
                if (left[0]-- > 0) aim(m.player.getYRot() + yawPerTick, m.player.getXRot() + pitchPerTick);
            };
        }));
        STEPS.add(new Step(ticks, () -> {}));
    }

    private static void tick(Minecraft mc) {
        mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
        mc.options.pauseOnLostFocus = false; // the run must not stall when the window is in the background
        if (mc.player == null || mc.level == null || index >= STEPS.size()) return;
        perTick.accept(mc);
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
        STEPS.add(new Step(wait, () -> aim(yaw, pitch)));
    }

    private static void aim(float yaw, float pitch) {
        var p = Minecraft.getInstance().player;
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
    }

    /** Holds sprint (and keeps the player flying) through the in-game key mapping, not OS input. */
    private static void warp(int wait, boolean on) {
        STEPS.add(new Step(wait, () -> {
            Galactica.LOG.info("[autotest] warp {}", on ? "engaged" : "released");
            Minecraft mc = Minecraft.getInstance();
            if (on) {
                Consumer<Minecraft> previous = perTick;
                perTick = m -> {
                    previous.accept(m);
                    m.player.getAbilities().flying = true;
                    m.options.keySprint.setDown(true);
                    m.player.setSprinting(true);
                };
            } else {
                perTick = m -> {};
                mc.options.keySprint.setDown(false);
                mc.player.setSprinting(false);
            }
        }));
    }

    private static void logNav(int wait) {
        STEPS.add(new Step(wait, () -> {
            var nav = SkyManager.get().nav();
            GalacticPos eye = SkyManager.get().viewer();
            Galactica.LOG.info("[autotest] nav speed {} at {}", nav == null ? "?" : NavServer.formatSpeed(nav.speed()),
                    eye == null ? "?" : String.format("(%.4f, %.4f, %.4f) pc", eye.xPc(), eye.yPc(), eye.zPc()));
        }));
    }

    private static void lookAtNearest(int wait) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null) return;
            Star s = mgr.neighbourhood().nearestStar(eye);
            if (s == null) return; // intergalactic space: nothing to aim at
            aimAt(eye, Neighbourhood.starPos(s), 2f);
            Galactica.LOG.info("[autotest] aiming at {} ({})", s.name(), s.spectralType());
        }));
    }

    private static void lookAtBody(int wait, String name) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null || mgr.nav() == null) return;
            for (Neighbourhood.Located l : mgr.neighbourhood().locateBodies(eye, mgr.nav().timeSeconds())) {
                if (l.body().name().equalsIgnoreCase(name)) {
                    aimAt(eye, l.pos(), 0f);
                    Galactica.LOG.info("[autotest] aiming at {}", name);
                    return;
                }
            }
            Galactica.LOG.warn("[autotest] body {} not found", name);
        }));
    }

    /** Like lookAtNearest but dead on target (the spectrometer needs the crosshair on it). */
    private static void lookAtNearestExact(int wait) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null) return;
            Star s = mgr.neighbourhood().nearestStar(eye);
            if (s != null) aimAt(eye, Neighbourhood.starPos(s), 0f);
        }));
    }

    /** Flies to {@code standPc} from Sol along galactic (l, b) and looks at the object at {@code objectPc}. */
    private static void visitGalactic(int wait, double l, double b, double standPc, String shotName, double objectPc) {
        double[] stand = dev.galactica.galaxy.StarCatalog.galacticToGalactocentric(l, b, standPc);
        double[] obj = dev.galactica.galaxy.StarCatalog.galacticToGalactocentric(l, b, objectPc);
        cmd(wait, String.format(java.util.Locale.ROOT, "galactica coords %.3f %.3f %.3f", stand[0], stand[1], stand[2]));
        STEPS.add(new Step(220, () -> aimAt(SkyManager.get().viewer(), GalacticPos.ofParsecs(obj[0], obj[1], obj[2]), 0f)));
        shot(5, shotName);
    }

    /** Aims at a galactic position; {@code offsetDeg} keeps small targets out from under the crosshair. */
    private static void aimAt(GalacticPos eye, GalacticPos target, float offsetDeg) {
        double[] g = target.metresFrom(eye);
        double[] m = Frames.galacticToMc(g[0], g[1], g[2]);
        double h = Math.sqrt(m[0] * m[0] + m[2] * m[2]);
        aim((float) Math.toDegrees(Math.atan2(-m[0], m[2])) + offsetDeg, (float) -Math.toDegrees(Math.atan2(m[1], h)));
    }

    private static void shot(int wait, String name) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            GalacticPos eye = SkyManager.get().viewer();
            String file = "galactica_" + name + (TAG.isEmpty() ? "" : "_" + TAG) + ".png";
            Galactica.LOG.info("[autotest] screenshot {} at {}", file,
                    eye == null ? "?" : String.format("(%.1f, %.1f, %.1f) pc", eye.xPc(), eye.yPc(), eye.zPc()));
            Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> Galactica.LOG.info("[autotest] {}", msg.getString()));
        }));
    }
}
