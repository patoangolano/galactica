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
    /**
     * Run mode from -Dgalactica.autotest.mode only (e.g. "m7"). No environment fallback: a stale
     * variable in the shell would silently replace the default and Iris runs.
     */
    private static final String MODE = System.getProperty("galactica.autotest.mode", "");
    /** -Dgalactica.autotest.perf=true: the default run ends with the {@link PerfBench} scenes. */
    private static final boolean PERF = Boolean.getBoolean("galactica.autotest.perf");
    private static int index, countdown = 200;
    private static Consumer<Minecraft> perTick = mc -> {};
    /** While set and false, the script waits (up to {@link #holdLeft} ticks) before counting down to the next step. */
    private static java.util.function.BooleanSupplier hold;
    private static int holdLeft;

    public static void install() {
        if (!Boolean.getBoolean("galactica.autotest")) return;
        Galactica.LOG.info("[autotest] enabled{}{}", TAG.isEmpty() ? "" : " (" + TAG + ")", MODE.isEmpty() ? "" : " mode " + MODE);
        PerfBench.install();
        if ("perf".equals(MODE)) {
            cmd(0, "galactica kit");
            perfScenes(false);
            STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("m7".equals(MODE)) {
            // The test world persists between runs: empty the inventory so every run starts with one kit.
            cmd(0, "clear");
            cmd(5, "galactica kit");
            cmd(10, "galactica launch");
            cmd(40, "galactica goto Sol");
            m7();
            STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("venus".equals(MODE)) {
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
        if ("showcase".equals(MODE)) {
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
        // Floating origin on EVA: ~14 blocks/tick through at least 3 recentres, camera and sky continuous.
        STEPS.add(new Step(20, () -> OriginShiftAutoTest.start("eva", new net.minecraft.world.phys.Vec3(0, 0, 14))));
        STEPS.add(new Step(330, OriginShiftAutoTest::finish));
        cmd(40, "galactica ship");
        // The ship, not the pilot, sets the view while flying: turn the hull so the Sun is where each shot wants it
        // (azimuth toward port from the nose, elevation above the ship's horizon).
        clearChat(40);
        orientShip(5, 35, 30);       shot(10, "17_ship_boarded");
        // The cockpit interior in first person, close to the Sun (2 degrees in radius), the Sun high ahead-left
        // through the canopy; then rolled almost inverted, so the seat, glass and display ride the roll.
        // With the HUD on, as a player flies: the display must read above the hotbar, hearts and armour.
        server(5, "park near the Sun (2 deg)", sp -> parkNearStar(sp, null, 2));
        clearChat(40);
        orientShip(5, 30, 14);       shot(10, "17a_cockpit_sun");
        rollShip(5, 150);            shot(10, "17a2_cockpit_rolled");
        // The night side: the Sun behind and below, the cabin lit by its instrument glow.
        orientShip(5, 180, -40);     shot(10, "17a3_cockpit_shadow");
        hud(5, false);
        cmd(5, "galactica goto Sol");
        // Exteriors without the HUD: the chase view with the Sun behind and above the camera, then a 3/4 front orbit.
        camera(40, net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        orientShip(5, 150, 35);      shot(10, "17b_ship_exterior");
        orientShip(5, -60, 40);
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(145, 18, 17F)));
        shot(10, "17c_ship_exterior_front");
        // The pilot inside the canopy: close orbits aimed at the cockpit (1.7 blocks ahead of the hull centre), from
        // the port side and from ahead and above the nose; the Sun lights the canopy from above.
        orientShip(5, 60, 60);
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(-75, 25, 5F, 1.7F)));
        shot(20, "17e_pilot_in_canopy_side");
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(160, 40, 5.5F, 1.7F)));
        shot(20, "17f_pilot_in_canopy_front");
        // Pitched and rolled: the crew turns with the hull and stays under the glass.
        rollShip(5, 35);
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(-70, 20, 6F, 1.7F)));
        shot(20, "17g_pilot_in_canopy_rolled");
        STEPS.add(new Step(5, dev.galactica.client.ship.ShipCamera::clearOrbit));
        // The mirrored front view (F5 twice) with the same tilt and lift as the chase view.
        camera(5, net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        orientShip(5, 30, 35);       shot(10, "17d_ship_front_mirrored");
        camera(5, net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        hud(5, true);
        // Main engine for 5 s in the chase view: the plume streams back toward the camera.
        orientShip(5, 150, 35);
        clearChat(5);
        key(5, "keyUp", true);
        STEPS.add(new Step(0, ShipBurnAutoTest::start));
        shot(100, "18_ship_thrust");
        key(5, "keyUp", false);
        STEPS.add(new Step(20, ShipBurnAutoTest::finish));
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
        // Floating origin while piloting: the ship at ~14 blocks/tick through at least 3 recentres, straight
        // from the burn above (no reset: the ship still coasts at the burn's velocity when the phase takes over).
        STEPS.add(new Step(5, () -> OriginShiftAutoTest.start("ship", new net.minecraft.world.phys.Vec3(0, 0, 14))));
        shot(150, "18b_ship_recentre");
        STEPS.add(new Step(180, OriginShiftAutoTest::finish));
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
        // The entry leaves the hull at whatever attitude it had in space's frame: level it (wings level, nose on the
        // horizon) so it comes down on its gear, as a pilot would.
        levelShip(20);
        look(180, 0, -10);  shot(5, "20_mars_descent");
        STEPS.add(new Step(5, () -> dev.galactica.client.planet.PlanetAutoTest.checkLanded("Mars", 0.38)));
        shipDiagnostics(1, "Mars descent");
        // Flight assist holds altitude with no lift input, so descend the way a pilot does: hold sneak (RCS down)
        // through the ~1 400 blocks from the entry height and hold the script until it rests (at most 120 s).
        key(5, "keyShift", true);
        awaitShipOnGround(1, 2400);
        key(1, "keyShift", false);
        shipOnGround(40, "Mars");
        shipDiagnostics(1, "Mars landed");
        shot(20, "21a_mars_cockpit");
        landedExterior("21c_mars_ship_landed");
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
        if (PERF) perfScenes(true);
        STEPS.add(new Step(60, () -> Minecraft.getInstance().stop()));
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
    }

    // ---------------------------------------------------------------- performance benchmark

    /**
     * The benchmark scenes, each held still for 10 s after it settles ({@link PerfBench}): the Overworld by day
     * and by night under the real sky, space near Earth, warp, near Jupiter and Saturn, inside the bulge, a planet
     * surface, the ship's cockpit (parked, then accelerating through floating-origin recentres) and the galaxy map.
     * {@code fromSpace}: the run starts wherever the autotest left the player and first lands on Earth.
     */
    private static void perfScenes(boolean fromSpace) {
        STEPS.add(new Step(5, () -> PerfBench.unthrottle(Minecraft.getInstance())));
        hud(5, true);
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
        clearChat(5);
        // Creative: the EVA warp drive and the ship's burn are not limited by fuel, so every scene runs the same way.
        cmd(5, "gamemode creative");
        if (fromSpace) {
            cmd(5, "galactica launch");
            cmd(60, "galactica goto Sol");
            STEPS.add(new Step(20, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Earth")));
            cmd(60, "galactica ground");
        }
        cmd(20, "time set noon");
        look(5, 90, -20);
        measure(200, "overworld_day");
        cmd(5, "time set midnight");
        look(5, 0, -60);
        measure(60, "overworld_night");
        cmd(5, "galactica launch");
        cmd(60, "galactica orbit Earth");
        lookAtBody(80, "Earth");
        measure(60, "space_near_earth");
        cmd(5, "galactica goto Sol");
        look(80, 90, 0);
        warp(5, true);
        measure(40, "warp");
        warp(5, false);
        cmd(60, "galactica goto Sol");
        cmd(60, "galactica orbit Jupiter");
        lookAtBody(80, "Jupiter");
        measure(60, "near_jupiter");
        cmd(5, "galactica orbit Saturn");
        lookAtBody(80, "Saturn");
        measure(60, "near_saturn");
        cmd(5, "galactica coords 300 200 400");
        look(200, 90, 40);
        measure(40, "galaxy_bulge");
        cmd(5, "galactica goto Sol");
        STEPS.add(new Step(60, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Mars")));
        cmd(60, "galactica ground");
        look(100, 30, 8);
        measure(60, "mars_surface");
        cmd(5, "galactica launch");
        cmd(60, "galactica goto Sol");
        cmd(60, "galactica ship");
        camera(40, net.minecraft.client.CameraType.FIRST_PERSON);
        clearChat(5);
        orientShip(5, 30, 14);
        measure(60, "ship_cockpit");
        // Full thrust for 10 s: the hull speeds up past the floating-origin limit, so chunks around it keep loading.
        key(5, "keyUp", true);
        measure(5, "ship_cruise");
        key(5, "keyUp", false);
        STEPS.add(new Step(20, dev.galactica.client.map.GalaxyMapClient::open));
        measure(60, "galaxy_map");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(null)));
        STEPS.add(new Step(5, PerfBench::summary));
    }

    /** Waits {@code settle} ticks, then measures {@code name} for 200 ticks (10 s). */
    private static void measure(int settle, String name) {
        STEPS.add(new Step(settle, () -> {
            Galactica.LOG.info("[perf] measuring {}", name);
            PerfBench.begin(name);
        }));
        STEPS.add(new Step(200, PerfBench::end));
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

    /** Rolls the piloted ship about its nose by {@code deg} (right wing down), keeping its heading and pitch. */
    private static void rollShip(int wait, float deg) {
        STEPS.add(new Step(wait, () -> {
            var ship = dev.galactica.client.ship.ShipClient.piloted(Minecraft.getInstance());
            if (ship == null) return;
            ship.setAttitude(new org.joml.Quaternionf(ship.attitude()).rotateZ((float) Math.toRadians(deg)));
            Galactica.LOG.info("[autotest] ship rolled {} deg", deg);
        }));
    }

    /**
     * After a landing: the piloted ship rests on the terrain (on the ground, its box bottom within a block and a half
     * above the highest column of its 6×6 footprint, and not embedded in blocks), with the crew alive and aboard.
     * Logs PASS or FAIL.
     */
    private static void shipOnGround(int wait, String body) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            var ship = mc.player != null && mc.player.getVehicle() instanceof dev.galactica.ship.Starship s ? s : null;
            if (ship == null) {
                Galactica.LOG.warn("[autotest] FAIL ship on ground on {}: not aboard (alive {})", body, mc.player != null && mc.player.isAlive());
                return;
            }
            // The box rests on the highest column of its footprint (on a slope, well above the one under the centre).
            var box = ship.getBoundingBox();
            int top = Integer.MIN_VALUE;
            for (int x = net.minecraft.util.Mth.floor(box.minX); x <= net.minecraft.util.Mth.floor(box.maxX - 1e-3); x++) {
                for (int z = net.minecraft.util.Mth.floor(box.minZ); z <= net.minecraft.util.Mth.floor(box.maxZ - 1e-3); z++) {
                    top = Math.max(top, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
                }
            }
            double gap = ship.getY() - top;
            boolean inside = !mc.level.noCollision(ship, ship.getBoundingBox().deflate(0.05));
            // Upright: the hull's up axis within 35 degrees of the vertical (a slope, not a ship on its back).
            org.joml.Vector3f up = ship.getAttitude(1F, new org.joml.Quaternionf()).transform(new org.joml.Vector3f(0, 1, 0));
            double tilt = Math.toDegrees(Math.acos(Math.clamp(up.y, -1F, 1F)));
            // Resting on terrain, not on another ship (the heightmap does not see entities).
            boolean onShip = !mc.level.getEntitiesOfClass(dev.galactica.ship.Starship.class, box.expandTowards(0, -2, 0),
                    s -> s != ship).isEmpty();
            boolean hullClear = dev.galactica.ship.Starship.hullClear(mc.level, ship.position(), ship.attitude());
            // The gap under the box centre only says how rough the site is: on a slope the hull rests on a probe (nose or
            // wing) over a ledge, so it is reported but does not fail the landing; resting, upright and clear does.
            boolean ok = ship.onGround() && gap > -0.05 && !inside && hullClear && tilt < 35 && !onShip && mc.player.isAlive()
                    && ship.getDeltaMovement().length() * 20 < 0.5;
            Galactica.LOG.info("[autotest] {} ship on ground on {}: onGround {}, box bottom {} over the footprint's top block {}, embedded {}, "
                            + "hull probes clear {}, tilt {} deg, on another ship {}, speed {} m/s",
                    ok ? "PASS" : "FAIL", body, ship.onGround(), String.format(java.util.Locale.ROOT, "%.2f", gap), top, inside, hullClear,
                    String.format(java.util.Locale.ROOT, "%.0f", tilt), onShip,
                    String.format(java.util.Locale.ROOT, "%.2f", ship.getDeltaMovement().length() * 20));
        }));
    }

    /** Levels the piloted ship (no pitch or roll, heading kept), on both sides like {@link #aimShipAtNadir}. */
    private static void levelShip(int wait) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !(mc.player.getVehicle() instanceof dev.galactica.ship.Starship ship)) return;
            org.joml.Quaternionf q = new org.joml.Quaternionf().rotationY((float) Math.toRadians(-ship.getYRot()));
            ship.setAttitude(q);
            var srv = mc.getSingleplayerServer();
            if (srv != null) {
                java.util.UUID id = ship.getUUID();
                srv.execute(() -> {
                    for (var lvl : srv.getAllLevels()) {
                        if (lvl.getEntity(id) instanceof dev.galactica.ship.Starship s) s.setAttitude(q);
                    }
                });
            }
            Galactica.LOG.info("[autotest] ship levelled (heading {})", ship.getYRot());
        }));
    }

    /**
     * Server side: removes unmanned starships within 96 blocks (horizontally) of the player's ship, so a landing
     * cannot come to rest on a hull an earlier run left in this copy of the world.
     */
    private static void clearDerelicts(Minecraft mc) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        srv.execute(() -> {
            var sp = srv.getPlayerList().getPlayer(id);
            if (sp == null || !(sp.getVehicle() instanceof dev.galactica.ship.Starship own)) return;
            var area = own.getBoundingBox().inflate(96, 4096, 96);
            for (var s : sp.level().getEntitiesOfClass(dev.galactica.ship.Starship.class, area, s -> s != own && s.getPassengers().isEmpty())) {
                Galactica.LOG.info("[autotest] removed a derelict ship at {} (left by an earlier run)", s.position());
                s.discard();
            }
        });
    }

    /** A third-person shot of the landed ship from ahead and above, then back to first person. */
    private static void landedExterior(String name) {
        camera(40, net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(150, 20, 18F)));
        shot(40, name);
        STEPS.add(new Step(40, dev.galactica.client.ship.ShipCamera::clearOrbit));
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
    }

    /**
     * Holds the script (after {@code wait}) until the ridden ship is on the ground, for at most {@code maxTicks}
     * (then logs FAIL). Meanwhile keeps the wings level and clears derelict ships from earlier runs around the landing
     * site every second.
     */
    private static void awaitShipOnGround(int wait, int maxTicks) {
        STEPS.add(new Step(wait, () -> {
            holdLeft = maxTicks;
            int[] ticks = {0};
            hold = () -> {
                var mc = Minecraft.getInstance();
                if (ticks[0]++ % 20 == 0) clearDerelicts(mc);
                // Wings-level autopilot until touchdown: the pilot's client owns the attitude, so a stray mouse move
                // during the long fall (the machine is shared) cannot leave the hull on its side or back.
                if (dev.galactica.client.ship.ShipClient.piloted(mc) instanceof dev.galactica.ship.Starship p && !p.onGround()) {
                    p.setAttitude(new org.joml.Quaternionf().rotationY((float) Math.toRadians(-p.getYRot())));
                }
                boolean done = mc.player == null || !(mc.player.getVehicle() instanceof dev.galactica.ship.Starship s) || s.onGround();
                if (!done && holdLeft == 0) Galactica.LOG.warn("[autotest] FAIL ship did not touch down within {} ticks", maxTicks);
                return done;
            };
        }));
    }

    /**
     * Logs which Starship instances the client and the server know (ids, positions, riders), and whether the
     * player's vehicle is the instance the client level holds under its id: a stale copy would leave the camera
     * and the cockpit on a hull nobody draws.
     */
    private static void shipDiagnostics(int wait, String label) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            var v = mc.player.getVehicle();
            String vehicle = v == null ? "none" : String.format(java.util.Locale.ROOT, "%s#%d at %.1f %.1f %.1f removed %s, level holds %s",
                    v.getClass().getSimpleName(), v.getId(), v.getX(), v.getY(), v.getZ(), v.isRemoved(),
                    mc.level.getEntity(v.getId()) == v ? "this instance" : String.valueOf(mc.level.getEntity(v.getId())));
            StringBuilder ships = new StringBuilder();
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof dev.galactica.ship.Starship s) {
                    ships.append(String.format(java.util.Locale.ROOT, " [#%d %.1f %.1f %.1f riders %d ground %s]", s.getId(), s.getX(), s.getY(), s.getZ(),
                            s.getPassengers().size(), s.onGround()));
                }
            }
            Galactica.LOG.info("[autotest] ships ({}) player {} at {} rides {}; client ships:{}", label, mc.player.getId(),
                    mc.player.position(), vehicle, ships);
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
                case "keyShift" -> o.keyShift;
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
        if (hold != null) {
            if (!hold.getAsBoolean() && holdLeft-- > 0) return;
            Galactica.LOG.info("[autotest] hold released ({} ticks to spare)", Math.max(0, holdLeft));
            hold = null;
        }
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

    // ---------------------------------------------------------------- M7 effects

    /**
     * Shots that exercise the M7 renderers: the GPU star plasma (a star several degrees wide),
     * near-planet terrain from low orbit, entry plasma through the heating window and the dimension
     * switch, the sun disc in a surface sky, and the nebula close-ups. Starts in galactica:space at Sol.
     * Each probe logs one {@code [autotest] PASS|FAIL} line next to its screenshot.
     */
    private static void m7() {
        // Star plasma: the Sun at 4 and 15 degrees of angular radius, its limb, then Betelgeuse at 10 degrees.
        server(5, "park near the Sun (4 deg)", sp -> parkNearStar(sp, null, 4));
        lookAtNearestExact(60); shot(5, "m7_01_sun_4deg");
        probeStarPlasma(1, "Sun 4 deg");
        server(5, "park near the Sun (15 deg)", sp -> parkNearStar(sp, null, 15));
        lookAtNearestExact(60); shot(5, "m7_02_sun_15deg");
        probeStarPlasma(1, "Sun 15 deg");
        lookAtNearestOffset(5, 14f); shot(10, "m7_03_sun_limb");
        cmd(10, "galactica goto Betelgeuse");
        server(200, "park near Betelgeuse (10 deg)", sp -> parkNearStar(sp, "Betelgeuse", 10));
        lookAtNearestExact(60); shot(5, "m7_04_betelgeuse_10deg");
        probeStarPlasma(1, "Betelgeuse 10 deg");
        cmd(10, "galactica goto Sol");

        // Near-planet terrain from low orbit (the clipmap needs a few seconds to bake).
        server(40, "park 400 km over Earth", sp -> parkOverBody(sp, "Earth", 400e3));
        aimNadir(200, "Earth", 0); shot(5, "m7_10_earth_low_orbit_nadir");
        probeNearPlanet(1, "Earth");
        aimNadir(5, "Earth", -12); shot(20, "m7_11_earth_low_orbit_horizon");
        server(10, "park 300 km over Mars", sp -> parkOverBody(sp, "Mars", 300e3));
        aimNadir(200, "Mars", 0); shot(5, "m7_12_mars_low_orbit_nadir");
        probeNearPlanet(1, "Mars");
        aimNadir(5, "Mars", -12); shot(20, "m7_13_mars_low_orbit_horizon");

        // Entry plasma: warp straight down from 300 km, shots through the heating window and the switch.
        // EVA on Mars (the warp drive only runs without a ship in creative, Starship.consumeWarpFuel),
        // then a fuelled starship into Earth's atmosphere.
        cmd(5, "gamemode creative");
        entry("Mars", "m7_20_mars_entry");
        cmd(5, "gamemode survival");
        cmd(20, "galactica ground");
        // The touchdown can take a few seconds while the column generates.
        // Aimed 4 degrees under the sun, so the disc sits just above the crosshair instead of under it.
        STEPS.add(new Step(140, () -> {
            double[] sun = dev.galactica.client.planet.SurfaceSkyOverlay.lastSunDir();
            if (sun != null) {
                double h = Math.sqrt(sun[0] * sun[0] + sun[2] * sun[2]);
                aim((float) Math.toDegrees(Math.atan2(-sun[0], sun[2])), (float) -Math.toDegrees(Math.atan2(sun[1], h)) + 4f);
            }
            Galactica.LOG.info("[autotest] surface sun direction {} ({})", sun == null ? "unknown" : java.util.Arrays.toString(sun),
                    dev.galactica.client.planet.SurfaceSkyOverlay.lastSunProbe());
        }));
        shot(10, "m7_30_mars_surface_sun");
        STEPS.add(new Step(1, () -> {
            double[] sun = dev.galactica.client.planet.SurfaceSkyOverlay.lastSunDir();
            boolean ok = sun != null && sun[1] > 0 && dev.galactica.client.planet.SurfaceSkyOverlay.lastSunDrawn();
            Galactica.LOG.info("[autotest] {} surface sun disc on Mars ({})", ok ? "PASS" : "FAIL",
                    dev.galactica.client.planet.SurfaceSkyOverlay.lastSunProbe());
        }));
        cmd(10, "galactica launch");
        cmd(40, "galactica goto Sol");
        server(40, "park 300 km over Earth", sp -> parkOverBody(sp, "Earth", 300e3));
        cmd(20, "galactica ship");
        entry("Earth", "m7_40_earth_entry");
        cmd(20, "galactica launch");
        cmd(40, "galactica goto Sol");

        // Nebula close-ups (the stands of 08d/08e), with a probe of the patches drawn.
        visitGalactic(20, 209.01, -19.38, 360, "m7_50_orion_closeup", 412);
        probeNebula(1, "Orion");
        visitGalactic(20, 166.57, -23.52, 118, "m7_51_pleiades_closeup", 136);
        probeNebula(1, "Pleiades");
        cmd(20, "galactica goto Sol");
    }

    /**
     * From orbit over {@code body}: aims at the nadir and warps down, then watches the entry. The glow
     * is logged every 5 ticks; screenshots are taken when the plasma first shows, at a fixed time, and
     * 2, 20 and 40 ticks after the dimension switch.
     */
    private static void entry(String body, String prefix) {
        aimNadir(80, body, 0);
        aimShipAtNadir(5, body);
        shot(5, prefix + "_a_start");
        STEPS.add(new Step(5, () -> {
            Galactica.LOG.info("[autotest] entry {} begins", body);
            Object startLevel = Minecraft.getInstance().level;
            int[] t = {0};
            int[] switchedAt = {-1};
            double[] peak = {0};
            boolean[] firstGlow = {false};
            double[] drive = {0};
            Consumer<Minecraft> previous = perTick;
            perTick = m -> {
                previous.accept(m);
                int now = t[0]++;
                double glow = dev.galactica.client.planet.TransferClient.glow();
                peak[0] = Math.max(peak[0], glow);
                // Fixed heading (aimed once): re-aiming every tick fights the starship's mouse steering
                // and sent the ship away from Earth in a test run.
                if (switchedAt[0] < 0) driveWarp(m, drive);
                if (now % 5 == 0) {
                    Galactica.LOG.info("[autotest] entry {} t={} glow={} mach={} dim={}", body, now,
                            String.format(java.util.Locale.ROOT, "%.3f", glow),
                            String.format(java.util.Locale.ROOT, "%.1f", dev.galactica.client.planet.TransferClient.mach()),
                            m.level.dimension().identifier());
                }
                if (!firstGlow[0] && glow > 0.15) {
                    firstGlow[0] = true;
                    grab(prefix + "_b_first_glow");
                }
                if (switchedAt[0] < 0 && m.level != startLevel) switchedAt[0] = now;
                if (switchedAt[0] >= 0) {
                    int since = now - switchedAt[0];
                    if (since == 2) grab(prefix + "_c_switch");
                    // Third person for one shot: the sheath and embers around the craft.
                    if (since == 6) m.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    if (since == 9) grab(prefix + "_c2_exterior");
                    if (since == 11) m.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    if (since == 20) grab(prefix + "_d_after_1s");
                    if (since == 40) grab(prefix + "_e_after_2s");
                }
                if (now == 400) {
                    boolean ok = switchedAt[0] >= 0 && peak[0] > 0.2;
                    Galactica.LOG.info("[autotest] {} entry plasma on {}: peak glow {}, switched {}", ok ? "PASS" : "FAIL", body,
                            String.format(java.util.Locale.ROOT, "%.3f", peak[0]),
                            switchedAt[0] >= 0 ? "at t=" + switchedAt[0] : "never");
                }
            };
        }));
        shot(60, prefix + "_t60");
        STEPS.add(new Step(345, () -> perTick = m -> {}));
        // If the entry never happened, land by command so the surface shots that follow still run.
        STEPS.add(new Step(5, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.dimension() == Galactica.SPACE) {
                Galactica.LOG.warn("[autotest] entry {} did not switch; landing by command", body);
                mc.player.connection.sendCommand("galactica land " + body);
            }
        }));
    }

    /**
     * The warp drive as the sprint key would spool it, set on the server: 15 %/tick from 1 km/s,
     * capped at nav.maxSpeed() and at half the distance to the surface ahead per second (NavServer's
     * governor) plus the rate at which the body's own orbital motion opens that distance. NavServer
     * lacks that last term, so a real dive can level off above the switch altitude when the body
     * recedes along the line of flight (Mars, some orbital phases: see the M7 report); the term keeps
     * this visual test independent of that flight bug. Set on the
     * server rather than by holding the sprint mapping, so the dive does not depend on the client
     * input reaching the server. Fuel rules still apply: a survival EVA cannot warp
     * (Starship.consumeWarpFuel). The server decays an unpowered drive by 0.8 before it moves the
     * player, hence the division.
     */
    private static void driveWarp(Minecraft mc, double[] drive) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        srv.execute(() -> {
            var sp = srv.getPlayerList().getPlayer(id);
            if (sp == null || sp.level().dimension() != Galactica.SPACE) return;
            var nav = sp.getAttachedOrCreate(Galactica.NAV);
            GalacticPos here = serverHere(sp);
            var look = sp.getLookAngle();
            double[] dir = Frames.mcToGalactic(look.x, look.y, look.z);
            Neighbourhood nb = NavServer.neighbourhood(sp);
            double t = nav.timeSeconds();
            double ahead = nb.distanceToNearestSurfaceAhead(here, t, dir);
            double later = nb.distanceToNearestSurfaceAhead(here, t + 1, dir);
            double recede = ahead < 1e15 && later < 1e15 ? Math.max(0, later - ahead) : 0;
            double governor = Math.max(1e3, 0.5 * ahead + recede);
            drive[0] = Math.min(Math.min(nav.maxSpeed(), governor), Math.max(1e3, drive[0] * 1.15));
            sp.setAttached(Galactica.NAV, nav.withSpeed(drive[0] / 0.8));
        });
    }

    /** Runs an action on the integrated server thread with the local player's server entity. */
    private static void server(int wait, String label, Consumer<net.minecraft.server.level.ServerPlayer> action) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            var srv = mc.getSingleplayerServer();
            if (srv == null || mc.player == null) return;
            java.util.UUID id = mc.player.getUUID();
            Galactica.LOG.info("[autotest] server: {}", label);
            srv.execute(() -> {
                var sp = srv.getPlayerList().getPlayer(id);
                if (sp == null) return;
                try {
                    action.accept(sp);
                } catch (Throwable e) {
                    Galactica.LOG.error("[autotest] server action '{}' failed", label, e);
                }
            });
        }));
    }

    private static GalacticPos serverHere(net.minecraft.server.level.ServerPlayer sp) {
        return Frames.playerPos(sp.getAttachedOrCreate(Galactica.NAV), sp.getX(), sp.getY(), sp.getZ());
    }

    /** Moves the player towards a star (the system star when {@code name} is null) until it is {@code angDeg} in radius. */
    private static void parkNearStar(net.minecraft.server.level.ServerPlayer sp, String name, double angDeg) {
        GalacticPos here = serverHere(sp);
        Star s = name == null ? NavServer.neighbourhood(sp).systemStar(here) : dev.galactica.galaxy.StarCatalog.byName(name);
        if (s == null) {
            Galactica.LOG.warn("[autotest] no star {} to park at", name);
            return;
        }
        GalacticPos at = Neighbourhood.starPos(s);
        double[] u = here.metresFrom(at);
        double n = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        if (n < 1) {
            u = new double[] {1, 0, 0};
        } else {
            for (int i = 0; i < 3; i++) u[i] /= n;
        }
        double d = s.radiusMetres() / Math.sin(Math.toRadians(angDeg));
        NavServer.moveTo(sp, at.plusMetres(u[0] * d, u[1] * d, u[2] * d));
        Galactica.LOG.info("[autotest] parked {} km from {} ({} deg radius)", Math.round(d / 1e3), s.name(), angDeg);
    }

    /** Parks the player {@code altitude} metres over the sunlit side of a body (same geometry as /galactica orbit). */
    private static void parkOverBody(net.minecraft.server.level.ServerPlayer sp, String name, double altitude) {
        GalacticPos here = serverHere(sp);
        Neighbourhood nb = NavServer.neighbourhood(sp);
        Star s = nb.systemStar(here);
        if (s == null) return;
        double t = sp.getAttachedOrCreate(Galactica.NAV).timeSeconds();
        for (Neighbourhood.Located l : nb.locateBodies(here, t)) {
            if (!l.body().name().equalsIgnoreCase(name)) continue;
            double d = l.body().radiusMetres() + altitude;
            double[] toStar = Neighbourhood.starPos(s).metresFrom(l.pos());
            double len = Math.sqrt(toStar[0] * toStar[0] + toStar[1] * toStar[1] + toStar[2] * toStar[2]);
            double ux = toStar[0] / len, uy = toStar[1] / len, uz = toStar[2] / len;
            double a = Math.toRadians(25), ca = Math.cos(a), sa = Math.sin(a);
            double vx = ux * ca - uy * sa, vy = ux * sa + uy * ca, vz = uz + 0.15;
            double vn = Math.sqrt(vx * vx + vy * vy + vz * vz);
            NavServer.moveTo(sp, l.pos().plusMetres(vx / vn * d, vy / vn * d, vz / vn * d));
            Galactica.LOG.info("[autotest] parked {} km over {}", Math.round(altitude / 1e3), name);
            return;
        }
        Galactica.LOG.warn("[autotest] body {} not found to park over", name);
    }

    /**
     * Aims at a body: {@code elevDeg} 0 looks straight down at the nadir; otherwise the aim is that
     * many degrees from the horizon (negative = below it, so the limb sits above the screen centre).
     */
    /**
     * Points a piloted starship's nose at the centre of {@code name}. The pilot's view follows the nose
     * (EntityTurnShipMixin), so {@link #aimNadir} alone leaves the ship on its deploy heading. Set on
     * both sides: the client's predicted attitude is what it sends back, and the server turns towards
     * that at a capped rate. No-op on foot.
     */
    private static void aimShipAtNadir(int wait, String name) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !(mc.player.getVehicle() instanceof dev.galactica.ship.Starship ship)) return;
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null || mgr.nav() == null) return;
            for (Neighbourhood.Located l : mgr.neighbourhood().locateBodies(eye, mgr.nav().timeSeconds())) {
                if (!l.body().name().equalsIgnoreCase(name)) continue;
                double[] g = l.pos().metresFrom(eye);
                double[] n = Frames.galacticToMc(g[0], g[1], g[2]);
                double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                org.joml.Quaternionf q = new org.joml.Quaternionf().rotationTo(0, 0, 1,
                        (float) (n[0] / len), (float) (n[1] / len), (float) (n[2] / len));
                ship.setAttitude(q);
                var srv = mc.getSingleplayerServer();
                if (srv != null) {
                    java.util.UUID id = ship.getUUID();
                    srv.execute(() -> {
                        for (var lvl : srv.getAllLevels()) {
                            if (lvl.getEntity(id) instanceof dev.galactica.ship.Starship s) s.setAttitude(q);
                        }
                    });
                }
                Galactica.LOG.info("[autotest] ship nose aimed at the centre of {}", name);
                return;
            }
        }));
    }

    private static void aimNadir(int wait, String name, float elevDeg) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null || mgr.nav() == null) return;
            for (Neighbourhood.Located l : mgr.neighbourhood().locateBodies(eye, mgr.nav().timeSeconds())) {
                if (!l.body().name().equalsIgnoreCase(name)) continue;
                double[] g = l.pos().metresFrom(eye);
                double[] n = Frames.galacticToMc(g[0], g[1], g[2]);
                double dist = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                for (int i = 0; i < 3; i++) n[i] /= dist;
                // Angle from the nadir: the horizon is at asin(R/d).
                double theta = elevDeg == 0 ? 0 : Math.asin(Math.min(1, l.body().radiusMetres() / dist)) + Math.toRadians(elevDeg);
                // Tilt towards Minecraft up, projected off the nadir.
                double[] u = {-n[1] * n[0], 1 - n[1] * n[1], -n[1] * n[2]};
                double un = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
                if (un < 1e-6) {
                    u = new double[] {1, 0, 0};
                } else {
                    for (int i = 0; i < 3; i++) u[i] /= un;
                }
                double c = Math.cos(theta), s = Math.sin(theta);
                aimDir(n[0] * c + u[0] * s, n[1] * c + u[1] * s, n[2] * c + u[2] * s);
                Galactica.LOG.info("[autotest] aiming {} deg from the nadir of {} (altitude {} km)",
                        Math.round(Math.toDegrees(theta)), name, Math.round((dist - l.body().radiusMetres()) / 1e3));
                return;
            }
            Galactica.LOG.warn("[autotest] body {} not found", name);
        }));
    }

    private static void aimDir(double x, double y, double z) {
        double h = Math.sqrt(x * x + z * z);
        aim((float) Math.toDegrees(Math.atan2(-x, z)), (float) -Math.toDegrees(Math.atan2(y, h)));
    }

    private static void lookAtNearestOffset(int wait, float offsetDeg) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null) return;
            Star s = mgr.neighbourhood().nearestStar(eye);
            if (s != null) aimAt(eye, Neighbourhood.starPos(s), offsetDeg);
        }));
    }

    private static void probeStarPlasma(int wait, String what) {
        STEPS.add(new Step(wait, () -> {
            boolean ok = dev.galactica.client.sky.SpaceSkyRenderer.starPlasmaDrawn();
            Galactica.LOG.info("[autotest] {} star plasma for {}: angular radius {} deg", ok ? "PASS" : "FAIL", what,
                    String.format(java.util.Locale.ROOT, "%.2f",
                            Math.toDegrees(dev.galactica.client.sky.SpaceSkyRenderer.starPlasmaAngularRadius())));
        }));
    }

    private static void probeNearPlanet(int wait, String body) {
        STEPS.add(new Step(wait, () -> {
            String claimed = dev.galactica.client.sky.NearPlanetRenderer.claimedName();
            boolean ok = body.equalsIgnoreCase(claimed);
            Galactica.LOG.info("[autotest] {} near-planet terrain for {}: drawn body {}", ok ? "PASS" : "FAIL", body, claimed);
        }));
    }

    private static void probeNebula(int wait, String what) {
        STEPS.add(new Step(wait, () -> {
            int n = dev.galactica.client.exotic.NebulaCloseUp.drawnCount();
            Galactica.LOG.info("[autotest] {} nebula close-up at {}: {} patch(es) drawn", n > 0 ? "PASS" : "FAIL", what, n);
        }));
    }

    /** Takes a screenshot at once (from a tick hook). */
    private static void grab(String name) {
        Minecraft mc = Minecraft.getInstance();
        String file = "galactica_" + name + (TAG.isEmpty() ? "" : "_" + TAG) + ".png";
        Galactica.LOG.info("[autotest] screenshot {}", file);
        Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
                msg -> Galactica.LOG.info("[autotest] {}", msg.getString()));
    }
}
