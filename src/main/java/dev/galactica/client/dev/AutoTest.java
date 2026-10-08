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
        if ("ships".equals(MODE) || "prefabs".equals(MODE)) {
            // Block-built ships alone (runAutotestShips), or only the prefab deploys and screenshots.
            cmd(0, "clear");
            if ("ships".equals(MODE)) BuiltShipAutoTest.run();
            else {
                cmd(5, "galactica kit");
                BuiltShipAutoTest.prefabs();
            }
            STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("soi".equals(MODE)) {
            // The orbital frame alone (OrbitalFrame): the four SOI checks, from a fresh login.
            cmd(0, "clear");
            soi();
            STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("nav".equals(MODE)) {
            // The 3D map and the autopilot alone (NavTarget, AutopilotServer), from a fresh login.
            cmd(0, "clear");
            nav();
            STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("terrain".equals(MODE) || "tectonic".equals(MODE)) {
            // Tall planet terrain tour, or Earth under Tectonic (TerrainAutoTest): screenshots and PASS/FAIL lines.
            if ("terrain".equals(MODE)) TerrainAutoTest.tour(TAG);
            else TerrainAutoTest.tectonic(TAG);
            STEPS.add(new Step(40, () -> Minecraft.getInstance().stop()));
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        if ("showcase".equals(MODE)) {
            showcase();
            ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
            return;
        }
        // The test world keeps the player wherever the last run ended (often a planet slot): start on Earth's Overworld.
        // On a glass block 200 high over spawn (an ocean in this world): underwater vanilla draws no sky at all.
        cmd(0, "execute in minecraft:overworld run forceload add 0 0");
        cmd(100, "execute in minecraft:overworld run setblock 0 199 0 minecraft:glass");
        cmd(5, "execute in minecraft:overworld run tp @s 0.5 200 0.5");
        cmd(1, "setblock ~ ~-1 ~ minecraft:glass");
        cmd(40, "execute in minecraft:overworld run forceload remove 0 0");
        // M6: the real night sky over the Overworld (Milky Way from Sol, real bright stars).
        cmd(20, "time set midnight");
        clearChat(5);
        look(60, 0, -60);   shot(5, "00a_earth_night_up");
        look(20, 90, -25);  shot(5, "00b_earth_night_east");
        look(20, 270, -25); shot(5, "00c_earth_night_west");
        earthSun();
        cmd(5, "galactica kit"); // unsuited, life support (correctly) kills the test pilot in seconds
        cmd(10, "galactica launch");
        look(160, 90, 0);   shot(5, "01_earth_orbit_towards_gc");
        look(20, -90, 0);   shot(5, "02_earth_orbit_anticentre");
        lookAtNearest(20);  shot(5, "03_earth_orbit_sun");
        evaArm();
        cmd(20, "galactica orbit Earth");
        lookAtBody(60, "Earth");   shot(5, "04_earth_close");
        cmd(20, "galactica orbit Moon");
        lookAtBody(60, "Moon");    shot(5, "05_moon_close");
        probeSpaceLit(20, "Moon", "05_moon_close");
        // The same 3 040 km altitude at quarter phase: the terminator through the disc, crater relief along it.
        orbitAtPhase(20, "Moon", 90);
        lookAtBody(60, "Moon");    shot(5, "05b_moon_3040km_quarter");
        probeSpaceLit(20, "Moon", "05b_moon_3040km_quarter");
        cmd(20, "galactica orbit Mercury");
        lookAtBody(60, "Mercury"); shot(5, "05c_mercury_close");
        probeSpaceLit(20, "Mercury", "05c_mercury_close");
        // Mars counts as airless for the relief (6 mbar) but its dust still filters the sunlight.
        cmd(20, "galactica orbit Mars");
        lookAtBody(60, "Mars");    shot(5, "05d_mars_close");
        probeSpaceLit(20, "Mars", "05d_mars_close");
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
        STEPS.add(new Step(5, () -> aimAt(SkyManager.get().viewer(), GalacticPos.ofParsecs(0, 0, 0), 0f)));
        shot(5, "12b_galaxy_from_45kpc_centred");
        probeGalaxyExposure(1, "45 kpc in the halo");
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
        // Vertical floating origin on EVA, nothing near: recentred every ~LIMIT_Y (192) blocks of climb.
        STEPS.add(new Step(20, () -> OriginShiftAutoTest.start("eva-vertical", new net.minecraft.world.phys.Vec3(0, 14, 0), 150, 300)));
        STEPS.add(new Step(150, OriginShiftAutoTest::finish));
        // A block 40 blocks away: a recentre now would make it jump ~192 blocks within sight, so the vertical
        // recentre waits until HARD_LIMIT_Y (704) and every shift is that large.
        server(20, "guard block 40 blocks east", sp -> {
            guardBlock = sp.blockPosition().east(40);
            sp.level().setBlockAndUpdate(guardBlock, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        });
        STEPS.add(new Step(20, () -> OriginShiftAutoTest.start("eva-vertical-guarded", new net.minecraft.world.phys.Vec3(0, -14, 0), 600, 800)));
        STEPS.add(new Step(260, OriginShiftAutoTest::finish));
        server(5, "remove guard block", sp -> {
            if (guardBlock != null) sp.level().setBlockAndUpdate(guardBlock, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        });
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
        // The same ship climbing: vertical recentres every ~192 blocks, pilot aboard the same ship instance.
        STEPS.add(new Step(5, () -> OriginShiftAutoTest.start("ship-vertical", new net.minecraft.world.phys.Vec3(0, 14, 0), 150, 300)));
        STEPS.add(new Step(150, OriginShiftAutoTest::finish));
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
        traceShip(40);
        shipOnGround(1, "Mars");
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
        soi();
        nav();
        // Block-built ships (BuiltShipAutoTest): build, assemble, fly, space, jump, Mars, disassemble, prefabs.
        BuiltShipAutoTest.run();
        if (PERF) perfScenes(true);
        STEPS.add(new Step(60, () -> Minecraft.getInstance().stop()));
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
    }

    /**
     * The patched-conic orbital frame near Earth ({@link SoiAutoTest}): (c) a minute on a circular orbit at 400 km,
     * (b) flying at Earth from 2,000 km on the main engine, (a) a ship's take-off from the Overworld left alone, (d) a
     * suited player at rest 1,000 km up, then (b) again on the warp drive. Each logs one PASS/FAIL line.
     */
    static void soi() {
        cmd(5, "galactica kit");
        cmd(10, "galactica launch");
        cmd(40, "galactica goto Sol");
        cmd(20, "galactica ship");
        clearChat(20);
        // Assist off before the orbit is set: on, it brakes the orbital speed relative to Earth from the first tick.
        STEPS.add(new Step(5, () -> {
            var ship = dev.galactica.client.ship.ShipClient.piloted(Minecraft.getInstance());
            if (ship != null && ship.coupled()) ship.toggleCoupled();
        }));
        server(20, "park 400 km over Earth on a circular orbit", sp -> {
            parkOverBody(sp, "Earth", 400e3);
            var d = NavServer.circularize(sp);
            Galactica.LOG.info("[autotest] circularized around {}", d == null ? "nothing" : d.name());
        });
        STEPS.add(new Step(20, () -> SoiAutoTest.start(SoiAutoTest.orbit(400e3, 1200))));
        awaitSoi(1, 1400, "orbit");
        server(5, "park 2000 km over Earth", sp -> parkOverBody(sp, "Earth", 2000e3));
        STEPS.add(new Step(40, () -> SoiAutoTest.start(SoiAutoTest.approach("main engine", false, 6000))));
        awaitSoi(1, 6300, "approach");
        STEPS.add(new Step(5, () -> SoiAutoTest.start(SoiAutoTest.takeOff(1600))));
        awaitSoi(1, 3000, "take-off");
        // In one server step: a take-off that ended low (a taller Overworld moves the switch up) would otherwise fall
        // back into Earth between leaving the ship and the park below.
        server(20, "leave the ship, out of the frame's fall", sp -> {
            sp.stopRiding();
            NavServer.launch(sp);
            parkOverBody(sp, "Earth", 1000e3);
        });
        cmd(10, "galactica launch");
        server(40, "park 1000 km over Earth on EVA", sp -> parkOverBody(sp, "Earth", 1000e3));
        STEPS.add(new Step(40, () -> SoiAutoTest.start(SoiAutoTest.evaFall(200))));
        awaitSoi(1, 400, "EVA fall");
        cmd(5, "gamemode creative");
        server(5, "park 2000 km over Earth on EVA", sp -> parkOverBody(sp, "Earth", 2000e3));
        aimNadir(40, "Earth", 0);
        STEPS.add(new Step(5, () -> SoiAutoTest.start(SoiAutoTest.approach("warp drive", true, 1200))));
        awaitSoi(1, 1500, "warp approach");
        cmd(5, "gamemode survival");
        // The world is saved on exit: leave the space frame in high Earth orbit, not where the entry switched.
        cmd(20, "galactica goto Sol");
    }

    // ---------------------------------------------------------------- 3D map and autopilot

    /** Server tick when the running autopilot check was engaged, and the exotic matter aboard then. */
    private static volatile int navStartTick, navExoticBefore;

    /**
     * The 3D Galaxy Map at five scales (whole galaxy face-on and edge-on, the region and the stars around the Sun, the
     * Solar System, Earth and the Moon) with a check of what each frame drew; search "Mars", select and lock it; the
     * manual override; the autopilot from high Earth orbit into a stable Mars orbit (and then a landing); and a star
     * of another system reached through a chain of hyperjumps. Each check logs one {@code [autotest] PASS|FAIL} line.
     */
    private static void nav() {
        cmd(5, "gamemode survival");
        cmd(5, "galactica kit");
        cmd(10, "galactica launch");
        cmd(40, "galactica goto Sol");
        cmd(20, "galactica ship");
        cmd(10, "galactica fuel 400 64");
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
        hud(5, true);
        clearChat(20);
        STEPS.add(new Step(5, () -> {
            Galactica.LOG.info("[autotest] opening the 3D map");
            dev.galactica.client.map.GalaxyMapClient.open();
        }));
        mapView(40, "galaxy", GalacticPos.ofParsecs(0, 0, 0), 48_000 * dev.galactica.science.Units.PARSEC, 200, 62, 160,
                s -> s.autotestSummary().contains("disk 1.00"), "30_map3d_galaxy");
        mapView(5, "galaxy edge-on", GalacticPos.ofParsecs(0, 0, 0), 45_000 * dev.galactica.science.Units.PARSEC, 200, 6, 60,
                s -> true, "30b_map3d_galaxy_edge");
        mapView(5, "region", Frames.SOL_POS, 220 * dev.galactica.science.Units.PARSEC, 150, 30, 400,
                s -> s.autotestFieldStars() > 2_000, "31_map3d_region");
        mapView(5, "neighbourhood", Frames.SOL_POS, 9 * dev.galactica.science.Units.PARSEC, 150, 25, 300,
                s -> s.autotestFieldStars() > 500 && s.autotestDrawn("Alpha Centauri A"), "31b_map3d_neighbourhood");
        mapView(5, "Solar System", Frames.SOL_POS, 14 * dev.galactica.science.Units.AU, 120, 38, 80,
                s -> s.autotestOrbitLines() >= 8 && s.autotestDrawn("Jupiter"), "32_map3d_solar_system");
        mapView(5, "inner system", Frames.SOL_POS, 3.2 * dev.galactica.science.Units.AU, 100, 50, 60,
                s -> s.autotestDrawn("Earth") && s.autotestDrawn("Mars"), "32b_map3d_inner_system");
        STEPS.add(new Step(5, () -> withMap(s -> {
            boolean ok = s.searchAndFocus("Earth");
            Galactica.LOG.info("[autotest] map search Earth: {}", ok);
        })));
        STEPS.add(new Step(100, () -> withMap(s -> {
            boolean ok = s.autotestDrawn("Moon") && s.autotestDrawn("Earth");
            Galactica.LOG.info("[autotest] {} map Earth+Moon: {}", ok ? "PASS" : "FAIL", s.autotestSummary());
        })));
        shot(1, "33_map3d_earth_moon");
        STEPS.add(new Step(5, () -> withMap(s -> {
            boolean ok = s.searchAndFocus("Mars");
            var sel = s.selected();
            boolean mars = ok && sel != null && "Mars".equals(sel.name());
            Galactica.LOG.info("[autotest] {} map search and select Mars (selected {})", mars ? "PASS" : "FAIL", sel == null ? "nothing" : sel.name());
        })));
        STEPS.add(new Step(80, () -> withMap(s -> Galactica.LOG.info("[autotest] map at Mars: {}", s.autotestSummary()))));
        shot(1, "34_map3d_mars_selected");
        STEPS.add(new Step(5, () -> withMap(s -> Galactica.LOG.info("[autotest] lock Mars sent: {}", s.lockSelection()))));
        STEPS.add(new Step(20, () -> {
            var t = dev.galactica.nav.target.NavTargets.get(Minecraft.getInstance().player);
            boolean ok = t != null && "Mars".equals(t.name()) && t.kind() == dev.galactica.nav.target.NavTarget.Kind.PLANET;
            Galactica.LOG.info("[autotest] {} lock target Mars (synced target {})", ok ? "PASS" : "FAIL", t == null ? "none" : t.name());
        }));
        shot(5, "34b_map3d_mars_locked");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(null)));

        // Assist off and a circular orbit 30 000 km over Earth: "high Earth orbit".
        STEPS.add(new Step(10, () -> {
            var ship = dev.galactica.client.ship.ShipClient.piloted(Minecraft.getInstance());
            if (ship != null && ship.coupled()) ship.toggleCoupled();
        }));
        server(10, "park 30 000 km over Earth on a circular orbit", sp -> {
            parkOverBody(sp, "Earth", 30_000e3);
            NavServer.circularize(sp);
        });
        // Manual override: any key the pilot presses hands control back.
        STEPS.add(new Step(40, () -> dev.galactica.client.nav.AutopilotClient.engage(dev.galactica.nav.autopilot.AutopilotState.Mode.ORBIT)));
        key(40, "keyUp", true);
        key(3, "keyUp", false);
        STEPS.add(new Step(20, () -> {
            var st = dev.galactica.nav.autopilot.AutopilotServer.state(Minecraft.getInstance().player);
            Galactica.LOG.info("[autotest] {} autopilot manual override (engaged after a key press: {})", st.engaged() ? "FAIL" : "PASS", st.engaged());
        }));
        // The same with a deliberate mouse movement: OS input is gated in the autotest, so this is the script turning
        // the pilot through Entity.turn, the path MouseHandler.turnPlayer takes for a real mouse.
        STEPS.add(new Step(20, () -> dev.galactica.client.nav.AutopilotClient.engage(dev.galactica.nav.autopilot.AutopilotState.Mode.ORBIT)));
        STEPS.add(new Step(40, () -> {
            var pl = Minecraft.getInstance().player;
            if (pl != null) for (int i = 0; i < 4; i++) pl.turn(60, 0);
        }));
        STEPS.add(new Step(20, () -> {
            var st = dev.galactica.nav.autopilot.AutopilotServer.state(Minecraft.getInstance().player);
            Galactica.LOG.info("[autotest] {} autopilot manual override by mouse (engaged after a turn: {}, desk events dropped {})",
                    st.engaged() ? "FAIL" : "PASS", st.engaged(), AutotestInput.dropped);
        }));
        server(10, "re-park 30 000 km over Earth on a circular orbit", sp -> {
            parkOverBody(sp, "Earth", 30_000e3);
            NavServer.circularize(sp);
        });
        clearChat(20);
        autopilot(5, dev.galactica.nav.autopilot.AutopilotState.Mode.ORBIT, 20 * 360, "35_autopilot_hud_warp");
        verdictOrbit(5, "Mars");
        shot(20, "35b_autopilot_mars_orbit");
        STEPS.add(new Step(5, () -> {
            dev.galactica.client.map.GalaxyMapClient.open();
        }));
        STEPS.add(new Step(10, () -> withMap(s -> s.searchAndFocus("Mars"))));
        STEPS.add(new Step(90, () -> withMap(s -> Galactica.LOG.info("[autotest] map around Mars in orbit: {}", s.autotestSummary()))));
        shot(1, "36_map3d_mars_orbit_trajectory");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(null)));

        // From that orbit, down to the surface.
        clearChat(10);
        autopilot(5, dev.galactica.nav.autopilot.AutopilotState.Mode.LAND, 20 * 600, "37_autopilot_mars_descent");
        verdictLanded(5, "Mars");
        shot(20, "37b_autopilot_mars_landed");

        // A star of another system, 10.5 ly away: beyond one jump, so a chain of hyperjumps.
        server(10, "leave the ship", net.minecraft.server.level.ServerPlayer::stopRiding);
        cmd(10, "galactica launch");
        cmd(40, "galactica goto Sol");
        cmd(20, "galactica ship");
        cmd(10, "galactica fuel 400 64");
        cmd(10, "galactica target Epsilon Eridani");
        clearChat(20);
        autopilot(5, dev.galactica.nav.autopilot.AutopilotState.Mode.ORBIT, 20 * 240, "38_autopilot_jump_spool");
        verdictStar(5, "Epsilon Eridani");
        lookAtNearest(20);
        shot(5, "39_autopilot_arrived_eps_eridani");
        STEPS.add(new Step(5, dev.galactica.client.map.GalaxyMapClient::open));
        STEPS.add(new Step(10, () -> withMap(dev.galactica.client.map.GalaxyMapScreen::home)));
        STEPS.add(new Step(90, () -> withMap(s -> Galactica.LOG.info("[autotest] map at Epsilon Eridani: {}", s.autotestSummary()))));
        shot(1, "40_map3d_eps_eridani_system");
        STEPS.add(new Step(5, () -> Minecraft.getInstance().gui.setScreen(null)));
        server(10, "leave the ship", net.minecraft.server.level.ServerPlayer::stopRiding);
        cmd(10, "galactica target clear");
        // The world is saved on exit: leave the space frame in high Earth orbit.
        cmd(20, "galactica goto Sol");
    }

    private static void withMap(Consumer<dev.galactica.client.map.GalaxyMapScreen> action) {
        if (Minecraft.getInstance().gui.screen() instanceof dev.galactica.client.map.GalaxyMapScreen s) action.accept(s);
        else Galactica.LOG.warn("[autotest] FAIL the 3D map is not open");
    }

    /** One map view: camera placed at once, then after {@code settle} ticks a check of what was drawn and a screenshot. */
    private static void mapView(int wait, String label, GalacticPos focus, double distance, float yaw, float pitch, int settle,
                                java.util.function.Predicate<dev.galactica.client.map.GalaxyMapScreen> check, String shotName) {
        STEPS.add(new Step(wait, () -> withMap(s -> s.autotestView(focus, distance, yaw, pitch))));
        STEPS.add(new Step(settle, () -> withMap(s -> {
            boolean ok = dev.galactica.client.map.MapRenderer.framesDrawn > 0 && check.test(s);
            Galactica.LOG.info("[autotest] {} map view {}: {}", ok ? "PASS" : "FAIL", label, s.autotestSummary());
        })));
        shot(1, shotName);
    }

    /**
     * Engages the autopilot the way the map's button does, takes a screenshot of the HUD 8 s in, then holds the script
     * until the server hands control back (at most {@code maxTicks}), logging the phase every 5 s.
     */
    static void autopilot(int wait, dev.galactica.nav.autopilot.AutopilotState.Mode mode, int maxTicks, String shotName) {
        server(wait, "autopilot start", sp -> {
            navStartTick = sp.level().getServer().getTickCount();
            navExoticBefore = sp.getVehicle() instanceof dev.galactica.ship.Starship s ? s.exotic() : 0;
        });
        STEPS.add(new Step(5, () -> {
            Galactica.LOG.info("[autotest] autopilot engage {}", mode);
            dev.galactica.client.nav.AutopilotClient.engage(mode);
        }));
        shot(160, shotName);
        STEPS.add(new Step(5, () -> {
            holdLeft = maxTicks;
            int[] n = {0};
            hold = () -> {
                var mc = Minecraft.getInstance();
                if (mc.player == null) return false;
                var st = dev.galactica.nav.autopilot.AutopilotServer.state(mc.player);
                if (++n[0] % 100 == 0) {
                    var r = dev.galactica.client.nav.TargetReadout.current();
                    Galactica.LOG.info("[autotest] autopilot {} eta {} s, {} to go; {}", st.phase(), Math.round(st.eta()),
                            r == null ? "?" : dev.galactica.client.nav.TargetReadout.distanceText(Math.max(0, r.surface())),
                            dev.galactica.client.nav.AutopilotClient.debug);
                }
                if (!st.engaged() && holdLeft == 0) return true;
                if (!st.engaged()) return true;
                if (holdLeft <= 1) Galactica.LOG.warn("[autotest] FAIL autopilot still engaged after {} ticks ({})", maxTicks, st.phase());
                return false;
            };
        }));
    }

    /** The craft is in a near-circular orbit of {@code body} above its entry shell, still at the stick. */
    static void verdictOrbit(int wait, String body) {
        server(wait, "verdict orbit " + body, sp -> {
            int secs = (sp.level().getServer().getTickCount() - navStartTick) / 20;
            var t = dev.galactica.nav.target.NavTargets.get(sp);
            if (sp.level().dimension() != Galactica.SPACE || !(sp.getVehicle() instanceof dev.galactica.ship.Starship ship) || t == null) {
                Galactica.LOG.info("[autotest] FAIL autopilot {} orbit: not piloting in space ({})", body, sp.level().dimension().identifier());
                return;
            }
            var nav = sp.getAttachedOrCreate(Galactica.NAV);
            GalacticPos here = Frames.playerPos(nav, ship.getX(), ship.getY(), ship.getZ());
            var r = dev.galactica.nav.target.TargetGeometry.resolve(Galactica.GALAXY, t, nav.timeSeconds());
            var dm = ship.getDeltaMovement();
            double[] own = Frames.mcToGalactic(dm.x * 20, dm.y * 20, dm.z * 20);
            double[][] rv = dev.galactica.nav.autopilot.AutopilotKinematics.relative(NavServer.neighbourhood(sp), here, nav, own, r, nav.timeSeconds());
            double[] aps = dev.galactica.nav.OrbitalFrame.apsides(rv[0], rv[1], r.mu());
            double floor = r.radius() + 1.05 * dev.galactica.nav.autopilot.AutopilotMath.entryAltitude(r.body());
            boolean ok = body.equals(r.body().name()) && dev.galactica.nav.autopilot.AutopilotMath.orbitReached(aps, floor)
                    && dev.galactica.nav.autopilot.AutopilotServer.state(sp).phase() == dev.galactica.nav.autopilot.AutopilotState.Phase.IDLE;
            Galactica.LOG.info(String.format(java.util.Locale.ROOT,
                    "[autotest] %s autopilot %s orbit from high Earth orbit in %d s: Pe %.0f km Ap %.0f km e %.3f (floor %.0f km), H2 %.1f",
                    ok ? "PASS" : "FAIL", body, secs, (aps[0] - r.radius()) / 1e3, (aps[1] - r.radius()) / 1e3,
                    dev.galactica.nav.autopilot.AutopilotMath.eccentricity(aps[0], aps[1]), (floor - r.radius()) / 1e3, ship.hydrogen()));
        });
    }

    /** The ship rests on the gear on {@code body}'s surface level with the pilot aboard. */
    static void verdictLanded(int wait, String body) {
        server(wait, "verdict landed " + body, sp -> {
            int secs = (sp.level().getServer().getTickCount() - navStartTick) / 20;
            boolean onPlanet = sp.level().dimension() != Galactica.SPACE && sp.level().dimension() != net.minecraft.world.level.Level.OVERWORLD;
            boolean ok = onPlanet && sp.getVehicle() instanceof dev.galactica.ship.Starship ship && (ship.onGround() || ship.restingContact())
                    && ship.getDeltaMovement().length() * 20 < 1.0 && sp.isAlive() && ship.hull() > 0;
            Galactica.LOG.info("[autotest] {} autopilot {} landing in {} s ({}, vehicle {}, alive {})", ok ? "PASS" : "FAIL", body, secs,
                    sp.level().dimension().identifier(), sp.getVehicle() == null ? "none" : sp.getVehicle().getType().toShortString(), sp.isAlive());
        });
    }

    /** Arrived in the star's system at a safe distance, through more than one hyperjump (the matter spent shows it). */
    private static void verdictStar(int wait, String name) {
        server(wait, "verdict star " + name, sp -> {
            int secs = (sp.level().getServer().getTickCount() - navStartTick) / 20;
            Star s = dev.galactica.galaxy.StarCatalog.byName(name);
            var nav = sp.getAttachedOrCreate(Galactica.NAV);
            var root = sp.getRootVehicle();
            GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
            Star sys = NavServer.neighbourhood(sp).systemStar(here);
            double d = here.distanceMetres(Neighbourhood.starPos(s));
            double safe = dev.galactica.nav.autopilot.AutopilotMath.starArrivalDistance(s, s.radiusMetres());
            int spent = sp.getVehicle() instanceof dev.galactica.ship.Starship ship ? navExoticBefore - ship.exotic() : -1;
            boolean ok = sys == s && d <= 3 * safe && d >= 0.5 * safe && spent >= 11;
            Galactica.LOG.info(String.format(java.util.Locale.ROOT,
                    "[autotest] %s autopilot hyperjump chain to %s in %d s: %.3f AU from it (safe %.3f AU), exotic spent %d, in system %s",
                    ok ? "PASS" : "FAIL", name, secs, d / dev.galactica.science.Units.AU, safe / dev.galactica.science.Units.AU, spent,
                    sys == null ? "none" : sys.name()));
        });
    }

    /** Holds the script (after {@code wait}) until the running {@link SoiAutoTest} phase has logged its verdict. */
    private static void awaitSoi(int wait, int maxTicks, String label) {
        STEPS.add(new Step(wait, () -> {
            holdLeft = maxTicks;
            hold = () -> {
                if (!SoiAutoTest.done() && holdLeft == 0) Galactica.LOG.warn("[autotest] FAIL soi {}: no verdict within {} ticks", label, maxTicks);
                return SoiAutoTest.done();
            };
        }));
    }

    // ---------------------------------------------------------------- performance benchmark

    /**
     * The benchmark scenes, each held still for 10 s after it settles ({@link PerfBench}): the Overworld by day
     * and by night under the real sky, space near Earth, warp, near Jupiter and Saturn, inside the bulge, a planet
     * surface and fresh terrain being generated there, the ship's cockpit (parked, then accelerating through
     * floating-origin recentres) and the galaxy map.
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
        // Fresh terrain: a spectator dropped over Martian ground no run has generated yet (a new offset every run),
        // so the server generates, lights and sends the whole view area while it is measured (PLANET_CHUNK, mspt).
        cmd(5, "gamemode spectator");
        // 60 blocks above the new ground (the tall slots put Martian ground anywhere from Y −400 to 1200).
        long offset = 40_000 + (System.currentTimeMillis() / 1000 % 500) * 2_000;
        server(5, "hover over fresh terrain", sp -> TerrainAutoTest.hoverOverTerrain(sp, offset, 60));
        look(1, 30, 30);
        measure(1, "planet_chunkgen");
        cmd(5, "gamemode creative");
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
    static void measure(int settle, String name) {
        STEPS.add(new Step(settle, () -> {
            Galactica.LOG.info("[perf] measuring {}", name);
            PerfBench.begin(name);
        }));
        STEPS.add(new Step(200, PerfBench::end));
    }

    /** Empties the chat and the action-bar line, so earlier command output does not cover the next shot. */
    static void clearChat(int wait) {
        STEPS.add(new Step(wait, () -> {
            var gui = Minecraft.getInstance().gui;
            gui.hud.getChat().clearMessages(false);
            gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }));
    }

    /** Shows or hides the whole HUD (F1). */
    static void hud(int wait, boolean visible) {
        STEPS.add(new Step(wait, () -> {
            var hud = Minecraft.getInstance().gui.hud;
            if (hud.isHidden() == visible) hud.toggle();
        }));
    }

    static void camera(int wait, net.minecraft.client.CameraType type) {
        STEPS.add(new Step(wait, () -> Minecraft.getInstance().options.setCameraType(type)));
    }

    /**
     * Turns the piloted ship (yaw and pitch, no roll) so the host star lies at {@code sunAzDeg} toward port from the
     * nose and {@code sunElDeg} above the ship's horizon, as close as a 1° search gets. The pilot's client is
     * authoritative for the attitude, so setting it here is what the server receives next tick.
     */
    static void orientShip(int wait, float sunAzDeg, float sunElDeg) {
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
    static void shipOnGround(int wait, String body) {
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
            boolean hullClear = ship.hullClearAt(mc.level, ship.position(), ship.attitude());
            // The gap under the box centre only says how rough the site is: on a slope the hull rests on a probe (nose or
            // wing) over a ledge, so it is reported but does not fail the landing; resting, upright and clear does.
            // Settled: at rest now, and it has not crept since the pilot let go (the trace window, 40 ticks).
            double drift = traceStart == null ? Double.NaN : Math.hypot(ship.getX() - traceStart.x, ship.getZ() - traceStart.z);
            boolean ok = ship.onGround() && gap > -0.05 && !inside && hullClear && tilt < 35 && !onShip && mc.player.isAlive()
                    && ship.getDeltaMovement().length() * 20 < 0.05 && !(drift > 0.05);
            Galactica.LOG.info("[autotest] {} ship on ground on {}: onGround {}, box bottom {} over the footprint's top block {}, embedded {}, "
                            + "hull probes clear {}, tilt {} deg, on another ship {}, speed {} m/s, horizontal drift since release {} blocks",
                    ok ? "PASS" : "FAIL", body, ship.onGround(), String.format(java.util.Locale.ROOT, "%.2f", gap), top, inside, hullClear,
                    String.format(java.util.Locale.ROOT, "%.0f", tilt), onShip,
                    String.format(java.util.Locale.ROOT, "%.2f", ship.getDeltaMovement().length() * 20),
                    String.format(java.util.Locale.ROOT, "%.3f", drift));
        }));
    }

    /** Logs the ridden ship's velocity, position, ground contact and sneak every 2 ticks for {@code ticks} ticks, then moves on. */
    /** The ridden ship's position when {@link #traceShip} began (the pilot had just let go). */
    private static net.minecraft.world.phys.Vec3 traceStart;

    static void traceShip(int ticks) {
        STEPS.add(new Step(1, () -> {
            var mc0 = Minecraft.getInstance();
            traceStart = mc0.player != null && mc0.player.getVehicle() instanceof dev.galactica.ship.Starship s0 ? s0.position() : null;
            Consumer<Minecraft> previous = perTick;
            int[] n = {0};
            perTick = m -> {
                previous.accept(m);
                if (n[0] > ticks) return;
                if (n[0]++ % 2 == 0 && m.player != null && m.player.getVehicle() instanceof dev.galactica.ship.Starship s) {
                    var d = s.getDeltaMovement().scale(20);
                    Galactica.LOG.info(String.format(java.util.Locale.ROOT,
                            "[autotest] ship trace t=%d v=(%.2f, %.2f, %.2f) m/s onGround %s pos (%.2f, %.2f, %.2f) shift %s",
                            n[0] - 1, d.x, d.y, d.z, s.onGround(), s.getX(), s.getY(), s.getZ(), m.options.keyShift.isDown()));
                }
            };
        }));
        STEPS.add(new Step(ticks, () -> perTick = m -> {}));
    }

    /**
     * The Overworld Sun drawn by the mod (Sol's disc and glare, {@link dev.galactica.client.earthsky.EarthSun}) instead
     * of vanilla's square: at noon overhead, low over the western horizon (reddened by airmass), and hidden by rain.
     */
    private static void earthSun() {
        cmd(5, "weather clear");
        cmd(5, "time set noon");
        // The saved world may still be raining: vanilla fades rain out over ~100 ticks after "weather clear".
        STEPS.add(new Step(1, () -> {
            holdLeft = 300;
            hold = () -> Minecraft.getInstance().level == null || Minecraft.getInstance().level.getRainLevel(1F) < 0.01F;
        }));
        clearChat(5);
        look(40, 0, -89.9f);
        shot(5, "00d_earth_sun_noon");
        earthSunProbe(1, "noon");
        look(5, 0, -60);
        shot(5, "00d2_earth_sun_noon_glare");
        // Sunset: advance the clock until vanilla's sun is ~2 degrees above the horizon, then look at it.
        cmd(5, "time set 11000");
        STEPS.add(new Step(20, () -> {
            holdLeft = 400;
            hold = () -> {
                Minecraft mc = Minecraft.getInstance();
                Float deg = mc.gameRenderer.mainCamera().attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, 1F);
                if (deg == null) return true;
                double a = Math.toRadians(deg);
                double alt = Math.toDegrees(Math.asin(Math.cos(a)));
                // Vanilla's sun direction R_y(-90) R_x(a) (0, 1, 0) = (-sin a, cos a, 0).
                double x = -Math.sin(a), y = Math.cos(a);
                aim((float) Math.toDegrees(Math.atan2(-x, 0)), (float) -Math.toDegrees(Math.atan2(y, Math.abs(x))) - 4f);
                if (alt <= 2.0) {
                    Galactica.LOG.info("[autotest] sunset: sun angle {} deg, altitude {} deg", deg, String.format(java.util.Locale.ROOT, "%.2f", alt));
                    return true;
                }
                mc.player.connection.sendCommand("time add " + (alt > 6 ? 40 : 8));
                return false;
            };
        }));
        clearChat(1);
        shot(20, "00e_earth_sun_sunset");
        earthSunProbe(1, "sunset");
        cmd(5, "time set noon");
        cmd(5, "weather rain");
        clearChat(5);
        look(200, 0, -60);
        shot(5, "00f_earth_sun_rain");
        earthSunProbe(1, "rain");
        cmd(5, "weather clear");
    }

    private static void earthSunProbe(int wait, String label) {
        STEPS.add(new Step(wait, () -> {
            boolean drawn = dev.galactica.client.earthsky.EarthSun.lastDrawn();
            float[] f = dev.galactica.client.earthsky.EarthSun.lastFilter();
            float fade = dev.galactica.client.earthsky.EarthSun.lastFade();
            boolean ok = switch (label) {
                case "noon" -> drawn && f[0] > 0.95f && f[1] > 0.95f && f[2] > 0.95f && fade > 0.95f;
                case "sunset" -> drawn && f[0] > 0.5f && f[2] < 0.7f * f[0];
                default -> fade < 0.1f; // rain: vanilla hides its sun with rainBrightness, and so does ours
            };
            Galactica.LOG.info("[autotest] {} Earth sun {}: drawn {} ({})", ok ? "PASS" : "FAIL", label, drawn,
                    dev.galactica.client.earthsky.EarthSun.lastProbe());
        }));
    }

    /**
     * EVA in space: the first-person arm (empty hand) and the body in third person lit by the Sun
     * ({@link dev.galactica.client.ship.ShipEnvironment#evaLight}, {@link dev.galactica.client.eva.SpaceLighting}).
     */
    private static void evaArm() {
        STEPS.add(new Step(5, () -> Minecraft.getInstance().player.getInventory().setSelectedSlot(8)));
        lookAtNearest(5);
        STEPS.add(new Step(5, () -> {
            var p = Minecraft.getInstance().player;
            aim(p.getYRot() + 25f, p.getXRot() + 25f); // the Sun up and to the left, the arm lit from ahead
        }));
        shot(10, "03a_eva_arm_sunlit");
        STEPS.add(new Step(5, () -> {
            var p = Minecraft.getInstance().player;
            int light = dev.galactica.client.ship.ShipEnvironment.evaLight(p, 1F);
            var dir = dev.galactica.client.eva.SpaceLighting.appliedDirection();
            boolean ok = light >= 0 && (light >>> 20 & 15) >= 10 && dir != null;
            Galactica.LOG.info("[autotest] {} EVA starlight: block {} sky {}, level lights toward {}", ok ? "PASS" : "FAIL",
                    light < 0 ? -1 : light >>> 4 & 15, light < 0 ? -1 : light >>> 20 & 15, dir);
        }));
        STEPS.add(new Step(5, () -> {
            var p = Minecraft.getInstance().player;
            aim(p.getYRot() + 155f, 10f); // the Sun behind: the arm's night side, ambient floor only
        }));
        shot(10, "03b_eva_arm_shadow");
        lookAtNearest(5);
        camera(5, net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        shot(20, "03c_eva_third_person_sunlit");
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
        STEPS.add(new Step(5, () -> Minecraft.getInstance().player.getInventory().setSelectedSlot(0)));
    }

    /** Levels the piloted ship (no pitch or roll, heading kept), on both sides like {@link #aimShipAtNadir}. */
    static void levelShip(int wait) {
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
    static void landedExterior(String name) {
        camera(40, net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        STEPS.add(new Step(5, () -> dev.galactica.client.ship.ShipCamera.orbit(150, 20, 18F)));
        shot(40, name);
        STEPS.add(new Step(40, dev.galactica.client.ship.ShipCamera::clearOrbit));
        camera(5, net.minecraft.client.CameraType.FIRST_PERSON);
    }

    /** Holds the script (after {@code wait}) until the ridden ship moves under 0.5 m/s, for at most {@code maxTicks}. */
    private static void awaitShipSettled(int wait, int maxTicks) {
        STEPS.add(new Step(wait, () -> {
            holdLeft = maxTicks;
            hold = () -> {
                var mc = Minecraft.getInstance();
                return mc.player == null || !(mc.player.getVehicle() instanceof dev.galactica.ship.Starship s)
                        || s.getDeltaMovement().length() * 20 < 0.5;
            };
        }));
    }

    /**
     * Holds the script (after {@code wait}) until the ridden ship is on the ground, for at most {@code maxTicks}
     * (then logs FAIL). Meanwhile keeps the wings level and clears derelict ships from earlier runs around the landing
     * site every second.
     */
    static void awaitShipOnGround(int wait, int maxTicks) {
        STEPS.add(new Step(wait, () -> {
            holdLeft = maxTicks;
            int[] ticks = {0};
            boolean[] traced = {false};
            hold = () -> {
                var mc = Minecraft.getInstance();
                if (ticks[0]++ % 20 == 0) clearDerelicts(mc);
                // Per-tick movement phases (Starship.traceTicks) from 40 blocks above the terrain through touchdown.
                if (!traced[0] && mc.player != null && mc.player.getVehicle() instanceof dev.galactica.ship.Starship t
                        && t.getY() - mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        net.minecraft.util.Mth.floor(t.getX()), net.minecraft.util.Mth.floor(t.getZ())) < 40) {
                    traced[0] = true;
                    dev.galactica.ship.Starship.traceTicks = 80;
                }
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
    static void shipDiagnostics(int wait, String label) {
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
    static void key(int wait, String mapping, boolean down) {
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

    /** Appends a raw step (for the scripts in {@link TerrainAutoTest}). */
    static void step(int wait, Runnable action) {
        STEPS.add(new Step(wait, action));
    }

    /** Holds the script until {@code done} is true or {@code maxTicks} pass. */
    static void await(int wait, int maxTicks, java.util.function.BooleanSupplier done) {
        STEPS.add(new Step(wait, () -> {
            holdLeft = maxTicks;
            hold = done;
        }));
    }

    /** The block the guarded vertical recentre phase keeps in sight (server side). */
    private static volatile net.minecraft.core.BlockPos guardBlock;

    static void cmd(int wait, String command) {
        STEPS.add(new Step(wait, () -> {
            Galactica.LOG.info("[autotest] /{}", command);
            Minecraft.getInstance().player.connection.sendCommand(command);
        }));
    }

    static void look(int wait, float yaw, float pitch) {
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

    static void shot(int wait, String name) {
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
    static void server(int wait, String label, Consumer<net.minecraft.server.level.ServerPlayer> action) {
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
    static void parkOverBody(net.minecraft.server.level.ServerPlayer sp, String name, double altitude) {
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

    /**
     * Moves to the altitude {@code /galactica orbit} uses (the body 20° in angular radius) on the side
     * that puts the star {@code phaseDeg} away from the eye, as seen from the body.
     */
    private static void orbitAtPhase(int wait, String name, double phaseDeg) {
        STEPS.add(new Step(wait, () -> {
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            if (eye == null || mgr.nav() == null) return;
            Star star = mgr.neighbourhood().nearestStar(eye);
            if (star == null) return;
            for (Neighbourhood.Located l : mgr.neighbourhood().locateBodies(eye, mgr.nav().timeSeconds())) {
                if (!l.body().name().equalsIgnoreCase(name)) continue;
                double[] u = Neighbourhood.starPos(star).metresFrom(l.pos());
                double un = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
                for (int k = 0; k < 3; k++) u[k] /= un;
                // A unit vector perpendicular to the star direction, then the view direction at the phase angle.
                double[] w = {-u[1], u[0], 0};
                double wn = Math.sqrt(w[0] * w[0] + w[1] * w[1]);
                double a = Math.toRadians(phaseDeg), d = l.body().radiusMetres() / Math.tan(Math.toRadians(20));
                double[] v = new double[3];
                for (int k = 0; k < 3; k++) v[k] = (u[k] * Math.cos(a) + w[k] / wn * Math.sin(a)) * d;
                GalacticPos at = l.pos().plusMetres(v[0], v[1], v[2]);
                String c = String.format(java.util.Locale.ROOT, "galactica coords %.13f %.13f %.13f", at.xPc(), at.yPc(), at.zPc());
                Galactica.LOG.info("[autotest] /{}", c);
                Minecraft.getInstance().player.connection.sendCommand(c);
                return;
            }
            Galactica.LOG.warn("[autotest] body {} not found", name);
        }));
    }

    /**
     * The airless body is drawn by the near renderer from its sun-lit images, lit from the side the
     * star is on. Reads the screenshot back and compares the disc's brightness 0.6 radii towards the
     * star's projected direction (worked out here from the body and star positions and the
     * player's view, independently of the renderer's body-fixed frame) with the point opposite.
     * Past 60 deg of phase the far side is night: it must be under a third of the sunward side;
     * nearer full phase both are lit and the sunward side must not be the darker one by more than
     * 20 %. A sign error in the light's frame or in the relief puts the bright side opposite.
     */
    private static void probeSpaceLit(int wait, String name, String shotName) {
        STEPS.add(new Step(wait, () -> {
            Minecraft mc = Minecraft.getInstance();
            String claimed = dev.galactica.client.sky.NearPlanetRenderer.claimedName();
            boolean lit = dev.galactica.client.sky.NearPlanetRenderer.claimedLit();
            SkyManager mgr = SkyManager.get();
            GalacticPos eye = mgr.viewer();
            double phase = Double.NaN, sun = Double.NaN, anti = Double.NaN;
            Star star = eye == null ? null : mgr.neighbourhood().nearestStar(eye);
            if (star != null && mgr.nav() != null && mc.player != null) {
                for (Neighbourhood.Located l : mgr.neighbourhood().locateBodies(eye, mgr.nav().timeSeconds())) {
                    if (!l.body().name().equalsIgnoreCase(name)) continue;
                    double[] s = Neighbourhood.starPos(star).metresFrom(l.pos()), e = eye.metresFrom(l.pos());
                    double c = dot(s, e) / Math.sqrt(dot(s, s) * dot(e, e));
                    phase = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
                    // Eye to centre, and the star's direction across the line of sight, in Minecraft axes.
                    double[] toBody = Frames.galacticToMc(-e[0], -e[1], -e[2]), toStar = Frames.galacticToMc(s[0], s[1], s[2]);
                    double dist = Math.sqrt(dot(toBody, toBody));
                    double[] los = {toBody[0] / dist, toBody[1] / dist, toBody[2] / dist};
                    double along = dot(toStar, los);
                    double[] across = {toStar[0] - along * los[0], toStar[1] - along * los[1], toStar[2] - along * los[2]};
                    double an = Math.sqrt(dot(across, across));
                    if (!(an > 0)) break;
                    double off = 0.6 * l.body().radiusMetres() / an;
                    java.io.File f = new java.io.File(new java.io.File(mc.gameDirectory, "screenshots"),
                            "galactica_" + shotName + (TAG.isEmpty() ? "" : "_" + TAG) + ".png");
                    try {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                        double fov = Math.toRadians(mc.options.fov().get());
                        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
                        sun = discLuma(img, fov, yaw, pitch, toBody, across, off);
                        anti = discLuma(img, fov, yaw, pitch, toBody, across, -off);
                    } catch (java.io.IOException | RuntimeException ex) {
                        Galactica.LOG.warn("[autotest] cannot read {}: {}", f, ex.toString());
                    }
                }
            }
            boolean sided = phase > 60 ? sun > 25 && anti < sun / 3 : sun > 25 && sun >= 0.8 * anti;
            boolean ok = name.equalsIgnoreCase(claimed) && lit && sided;
            Galactica.LOG.info("[autotest] {} sun-lit relief on {}: drawn body {}, lit images {}, phase angle {} deg, "
                            + "disc luma {} towards the star vs {} opposite", ok ? "PASS" : "FAIL", name, claimed, lit,
                    String.format(java.util.Locale.ROOT, "%.1f", phase), String.format(java.util.Locale.ROOT, "%.0f", sun),
                    String.format(java.util.Locale.ROOT, "%.0f", anti));
        }));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Mean luma (0-255) over 9 x 9 screenshot pixels around the point {@code toBody + k * across}
     * (Minecraft axes, metres from the eye), projected for the player's yaw/pitch and vertical FOV.
     */
    private static double discLuma(java.awt.image.BufferedImage img, double fov, float yaw, float pitch,
                                   double[] toBody, double[] across, double k) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double[] f = {-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr)};
        double[] r = {-f[2], 0, f[0]}; // f x (0, 1, 0)
        double rn = Math.sqrt(dot(r, r));
        for (int m = 0; m < 3; m++) r[m] /= rn;
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double[] p = {toBody[0] + k * across[0], toBody[1] + k * across[1], toBody[2] + k * across[2]};
        double z = dot(p, f), half = img.getHeight() / 2.0, t = Math.tan(fov / 2);
        int cx = (int) Math.round(img.getWidth() / 2.0 + dot(p, r) / z / t * half);
        int cy = (int) Math.round(half - dot(p, u) / z / t * half);
        double sum = 0;
        int n = 0;
        for (int y = cy - 4; y <= cy + 4; y++) {
            for (int x = cx - 4; x <= cx + 4; x++) {
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) continue;
                int c = img.getRGB(x, y);
                sum += 0.2126 * (c >> 16 & 255) + 0.7152 * (c >> 8 & 255) + 0.0722 * (c & 255);
                n++;
            }
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    /**
     * The Milky Way seen from outside keeps its structure: of the sky texture's lit pixels (green
     * channel > 20) fewer than 5 % are white (≥ 250) and fewer than 10 % reach 200, where the
     * highlight shoulder flattens the disc into a plateau. The old exposure, anchored to the empty
     * sky's 99th percentile, burned the whole disc out; anchoring to the covered pixels' p95 still
     * left the bulge and inner disc on the shoulder (17 % at 200 or more in the offline bake).
     */
    private static void probeGalaxyExposure(int wait, String where) {
        STEPS.add(new Step(wait, () -> {
            var snap = dev.galactica.client.sky.SkyTextures.galaxySnapshot();
            int lit = 0, white = 0, bright = 0;
            if (snap != null) {
                for (int p : snap.argb()) {
                    int g = p >> 8 & 255;
                    if (g > 20) lit++;
                    if (g >= 200) bright++;
                    if (g >= 250) white++;
                }
            }
            boolean ok = lit > 500 && white < 0.05 * lit && bright < 0.10 * lit;
            Galactica.LOG.info("[autotest] {} galaxy exposure from {}: {} lit pixels, {} white ({}%), {} at >= 200 ({}%)", ok ? "PASS" : "FAIL",
                    where, lit, white, String.format(java.util.Locale.ROOT, "%.1f", lit > 0 ? 100.0 * white / lit : 0), bright,
                    String.format(java.util.Locale.ROOT, "%.1f", lit > 0 ? 100.0 * bright / lit : 0));
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
