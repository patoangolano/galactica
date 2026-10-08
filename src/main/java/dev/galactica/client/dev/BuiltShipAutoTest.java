package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.ship.ShipCamera;
import dev.galactica.client.ship.ShipClient;
import dev.galactica.nav.NavState;
import dev.galactica.ship.HyperjumpPayload;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.yard.ShipAssembler;
import dev.galactica.ship.yard.ShipTemplate;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Locale;

/**
 * Block-built ships end to end ({@code runAutotestShips}, and inside the default run): a small ship built from blocks
 * by commands on a stone pad over the Overworld spawn, assembled from its helm, boarded, hovered, flown, climbed out
 * to space through the take-off switch, flown through floating-origin recentres, hyperjumped, landed on Mars and
 * settled, taken off, landed again and disassembled back into blocks; then every prefab deployed in space and
 * photographed outside and from the helm. One {@code [autotest] PASS|FAIL built ship …} line per check.
 */
final class BuiltShipAutoTest {
    private BuiltShipAutoTest() {}

    /** The test ship's helm (facing south: the nose is +Z, so ship-grid offsets are world offsets). */
    private static final int HX = 0, HY = 202, HZ = 13;
    /** Blocks the test ship is built from (counted from the commands below). */
    private static final int TEST_SHIP_BLOCKS = 33;

    private static double hoverY, takeOffY;
    private static Vec3 flyStart;
    private static int exoticBefore, shipIdBefore;
    private static dev.galactica.nav.GalacticPos originBefore;

    static void run() {
        // Out of wherever the last run left the player (it suffocated once inside a planet's terrain), into open air.
        AutoTest.cmd(0, "execute in minecraft:overworld run tp @s 0.5 260 0.5");
        AutoTest.cmd(20, "galactica kit");
        AutoTest.cmd(5, "execute in minecraft:overworld run forceload add -16 -8 16 40");
        // The pad first, then the player onto it (teleported first, the player fell past the pad into the sea).
        AutoTest.cmd(80, "execute in minecraft:overworld run fill -14 199 -4 14 199 34 minecraft:stone");
        AutoTest.cmd(5, "execute in minecraft:overworld run fill -14 200 -4 14 212 34 minecraft:air");
        AutoTest.cmd(5, "execute in minecraft:overworld run tp @s 0.5 200 0.5 0 15");
        AutoTest.cmd(20, "kill @e[type=galactica:built_ship,distance=..300]");
        // The test ship: a 3 × 6 deck on three gear legs, helm and a crew seat, two tanks, a core, an aft thruster, two
        // lift jets in the deck and two RCS quads.
        AutoTest.cmd(5, "fill -1 201 9 1 201 14 minecraft:white_concrete");
        AutoTest.cmd(2, "setblock -1 200 10 galactica:ship_landing_gear");
        AutoTest.cmd(2, "setblock 1 200 10 galactica:ship_landing_gear");
        AutoTest.cmd(2, "setblock 0 200 14 galactica:ship_landing_gear");
        AutoTest.cmd(2, "setblock -1 201 11 galactica:ship_thruster[facing=down]");
        AutoTest.cmd(2, "setblock 1 201 11 galactica:ship_thruster[facing=down]");
        AutoTest.cmd(2, "setblock 0 201 8 galactica:ship_thruster[facing=north]");
        AutoTest.cmd(2, "setblock 0 202 13 galactica:ship_helm[facing=south]");
        AutoTest.cmd(2, "setblock 0 202 11 galactica:ship_crew_seat[facing=south]");
        AutoTest.cmd(2, "setblock 0 202 9 galactica:ship_hydrogen_tank");
        AutoTest.cmd(2, "setblock 1 202 9 galactica:ship_hydrogen_tank");
        AutoTest.cmd(2, "setblock -1 202 9 galactica:ship_hyperdrive_core");
        AutoTest.cmd(2, "setblock -1 202 13 galactica:ship_rcs");
        AutoTest.cmd(2, "setblock 1 202 13 galactica:ship_rcs");
        AutoTest.cmd(2, "setblock 0 202 14 minecraft:light_blue_stained_glass");
        AutoTest.cmd(2, "setblock 1 202 11 minecraft:sea_lantern");
        AutoTest.cmd(2, "setblock -1 202 11 minecraft:orange_concrete");
        AutoTest.cmd(2, "setblock 1 202 12 minecraft:barrel");
        AutoTest.clearChat(20);
        AutoTest.shot(10, "ships_30_built_ship_blocks");
        AutoTest.cmd(5, "galactica shipassemble " + HX + " " + HY + " " + HZ);
        AutoTest.step(40, BuiltShipAutoTest::checkAssembled);
        AutoTest.shot(5, "ships_31_built_ship_assembled");
        AutoTest.cmd(5, "ride @s mount @e[type=galactica:built_ship,sort=nearest,limit=1]");
        AutoTest.server(10, "fuel the built ship", sp -> {
            if (sp.getVehicle() instanceof Starship s) {
                s.setHydrogen(s.maxHydrogen());
                s.setExotic(16);
            }
        });
        AutoTest.camera(20, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(20, "ships_32_built_ship_boarded");
        // Hover: a short lift burst off the pad, then hands off with flight assist on: it holds altitude. Level first,
        // as a pilot would: flight assist nulls drift across the nose and along the dorsal axis, not along the nose,
        // so a hull pitched down would slide down its own nose under gravity.
        AutoTest.levelShip(5);
        AutoTest.key(5, "keyJump", true);
        AutoTest.key(12, "keyJump", false);
        AutoTest.levelShip(1);
        AutoTest.step(40, () -> {
            hoverY = ship() != null ? ship().getY() : Double.NaN;
            Starship.traceTicks = 6;
        });
        AutoTest.step(200, () -> {
            ShipStructure s = ship();
            double drift = s == null ? Double.NaN : Math.abs(s.getY() - hoverY);
            boolean ok = s != null && drift < 0.5 && s.getY() > 203 && s.coupled();
            float pitch = s == null ? Float.NaN : s.getXRot();
            log(ok, "hover", String.format(Locale.ROOT, "altitude %.2f -> %.2f over 10 s (drift %.3f, limit 0.5), assist %s, pitch %.1f deg",
                    hoverY, s == null ? Double.NaN : s.getY(), drift, s != null && s.coupled(), pitch));
        });
        // Fly: main engine for 1.5 s.
        AutoTest.step(5, () -> flyStart = ship() != null ? ship().position() : null);
        AutoTest.key(1, "keyUp", true);
        AutoTest.key(30, "keyUp", false);
        AutoTest.step(5, () -> {
            ShipStructure s = ship();
            double moved = s == null || flyStart == null ? 0 : s.position().subtract(flyStart).horizontalDistance();
            double speed = s == null ? 0 : s.getDeltaMovement().horizontalDistance() * 20;
            log(moved > 10 && speed > 10, "fly", String.format(Locale.ROOT, "moved %.1f blocks, speed %.1f m/s after a 1.5 s burn (main %.1f m/s^2)",
                    moved, speed, s == null ? 0 : s.spec().mainAccel()));
        });
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.shot(20, "ships_33_built_ship_cockpit");
        // Out to space through the take-off switch: ten rows under it, nose up, main engine.
        AutoTest.step(5, () -> {
            Minecraft mc = Minecraft.getInstance();
            ShipStructure s = ship();
            if (s == null) return;
            shipIdBefore = s.getId();
            stop(mc, s);
            move(mc, s, dev.galactica.planet.PlanetEntry.exitY(mc.level) - 10);
            attitude(mc, s, new Quaternionf().rotationX((float) Math.toRadians(-90)));
        });
        AutoTest.key(5, "keyUp", true);
        AutoTest.await(1, 800, () -> Minecraft.getInstance().level != null && Minecraft.getInstance().level.dimension() == Galactica.SPACE);
        AutoTest.key(1, "keyUp", false);
        AutoTest.step(40, () -> {
            ShipStructure s = ship();
            boolean ok = s != null && Minecraft.getInstance().level.dimension() == Galactica.SPACE && s.flightReady()
                    && s.blocks().size() == TEST_SHIP_BLOCKS && s.getId() != shipIdBefore;
            log(ok, "take-off to space", String.format(Locale.ROOT, "in %s, piloting %s, blocks %s after the dimension change (a new entity: %s)",
                    Minecraft.getInstance().level.dimension().identifier(), s != null, s == null ? -1 : s.blocks().size(),
                    s != null && s.getId() != shipIdBefore));
        });
        AutoTest.cmd(5, "galactica goto Sol");
        // Floating origin: ~14 blocks per tick through at least three recentres, pilot on the same instance.
        AutoTest.step(40, () -> OriginShiftAutoTest.start("built-ship", new Vec3(0, 0, 14)));
        AutoTest.step(270, OriginShiftAutoTest::finish);
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 20, 14F));
        AutoTest.shot(20, "ships_34_built_ship_space");
        AutoTest.step(5, ShipCamera::clearOrbit);
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        // Hyperjump with the ship's own core (tier 10 ly), to the nearest star.
        AutoTest.server(5, "target the nearest other star 1-9 ly away", BuiltShipAutoTest::targetNearestStar);
        AutoTest.step(5, () -> {
            Minecraft mc = Minecraft.getInstance();
            ShipStructure s = ship();
            if (s == null) return;
            exoticBefore = s.exotic();
            shipIdBefore = s.getId();
            NavState nav = mc.player.getAttached(Galactica.NAV);
            originBefore = nav == null ? null : nav.origin();
            ClientPlayNetworking.send(new HyperjumpPayload(s.getId()));
        });
        AutoTest.step(160, () -> {
            Minecraft mc = Minecraft.getInstance();
            ShipStructure s = ship();
            NavState nav = mc.player.getAttached(Galactica.NAV);
            double ly = nav == null || originBefore == null ? 0 : nav.origin().distanceMetres(originBefore) / dev.galactica.science.Units.LIGHT_YEAR;
            boolean ok = s != null && s.getId() == shipIdBefore && s.exotic() < exoticBefore && ly > 1 && s.flightReady();
            log(ok, "hyperjump", String.format(Locale.ROOT, "%.2f ly, exotic %d -> %d, still aboard the same ship %s",
                    ly, exoticBefore, s == null ? -1 : s.exotic(), s != null && s.getId() == shipIdBefore));
        });
        // Mars: entry, descent on the RCS, touchdown, settled on the gear.
        AutoTest.cmd(20, "galactica goto Sol");
        AutoTest.cmd(10, "galactica land Mars");
        AutoTest.levelShip(20);
        AutoTest.key(5, "keyShift", true);
        AutoTest.awaitShipOnGround(1, 2400);
        AutoTest.key(1, "keyShift", false);
        AutoTest.traceShip(40);
        AutoTest.shipOnGround(1, "Mars (built ship)");
        AutoTest.shot(20, "ships_35_built_ship_mars_cockpit");
        AutoTest.landedExterior("ships_36_built_ship_mars_exterior");
        // Take off on the lift jets, then come back down and settle again.
        AutoTest.step(5, () -> takeOffY = ship() != null ? ship().getY() : Double.NaN);
        AutoTest.key(1, "keyJump", true);
        AutoTest.key(40, "keyJump", false);
        AutoTest.step(5, () -> {
            ShipStructure s = ship();
            double climbed = s == null ? 0 : s.getY() - takeOffY;
            log(climbed > 10, "take-off from Mars", String.format(Locale.ROOT, "climbed %.1f blocks in 2 s on the lift jets (lift %.1f m/s^2, g %.2f)",
                    climbed, s == null ? 0 : s.spec().liftAccel(), s == null ? 0 : -s.gravity().y()));
        });
        AutoTest.key(20, "keyShift", true);
        AutoTest.awaitShipOnGround(1, 1200);
        AutoTest.key(1, "keyShift", false);
        AutoTest.step(60, () -> { });
        // Disassemble: the pilot steps out, the ship goes back to blocks on the grid.
        AutoTest.server(5, "disassemble the built ship", sp -> {
            if (!(sp.getVehicle() instanceof ShipStructure s)) {
                Galactica.LOG.warn("[autotest] FAIL built ship disassemble: not aboard a built ship");
                return;
            }
            sp.stopRiding();
            s.setDeltaMovement(Vec3.ZERO);
            int blocks = s.blocks().size();
            var facing = net.minecraft.core.Direction.getApproximateNearest(
                    s.attitude().transform(new org.joml.Vector3f(0, 0, 1)).x, 0, s.attitude().transform(new org.joml.Vector3f(0, 0, 1)).z);
            BlockPos helm = ShipAssembler.helmCell(s, facing);
            boolean done = ShipAssembler.disassemble(s, sp);
            boolean helmBack = sp.level().getBlockState(helm).is(dev.galactica.ship.yard.ShipPartBlocks.HELM);
            log(done && helmBack && s.isRemoved(), "disassemble", String.format(Locale.ROOT,
                    "%d blocks set down on Mars, helm back at %s: %s", blocks, helm.toShortString(), helmBack));
        });
        AutoTest.camera(10, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(20, "ships_37_built_ship_disassembled_mars");
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        prefabs();
        systems();
        autopilot();
        // Deck walking and ship-to-ship collision (DeckAutoTest).
        DeckAutoTest.run();
    }

    // ------------------------------------------------------------------ ship systems (S7+)

    private static int shuttleId = -1, freighterId = -1;
    private static org.joml.Vector3d dockedOffset;

    /** The server copy of an entity by id in the player's level (the integrated server). */
    private static net.minecraft.world.entity.Entity serverEntity(net.minecraft.server.level.ServerPlayer sp, int id) {
        return sp.level().getEntity(id);
    }

    private static ShipStructure serverShip(net.minecraft.server.level.ServerPlayer sp, int id) {
        return serverEntity(sp, id) instanceof ShipStructure s ? s : null;
    }

    /** The dock key, cargo key: a click on the mapping, as a key press makes (OS input is gated in the autotest). */
    private static void click(net.minecraft.client.KeyMapping k) {
        net.minecraft.client.KeyMapping.click(k.getDefaultKey());
    }

    /**
     * Hangar, docking ports, the docked pair through recentres and through planet entry and exit, the systems page of
     * the cockpit display and the scanner on the map, cargo opened in flight, shields and the wreck.
     */
    static void systems() {
        AutoTest.cmd(20, "galactica goto Sol");
        AutoTest.cmd(20, "galactica ship freighter");
        AutoTest.step(20, () -> {
            ShipStructure f = ship();
            freighterId = f != null ? f.getId() : -1;
            log(f != null && f.systems().hangar() != null && f.maxShield() > 0 && f.systems().scannerRange() > 0, "freighter systems",
                    f == null ? "not aboard" : String.format(Locale.ROOT, "hangar %s, %d ports, shield %.0f (+%.0f/s), scanner %.0f m, %d containers, occupancy grid %s",
                            f.systems().hangar(), f.systems().ports().size(), f.maxShield(), f.shieldRecharge(), f.systems().scannerRange(),
                            dev.galactica.ship.ShipCargo.containers(f.blocks()).length, f.usesOccupancyGrid()));
        });
        // A shuttle settles into the hangar (placed there, moving with the freighter): clamped on the bay floor.
        AutoTest.server(5, "a shuttle in the freighter's hangar", sp -> {
            ShipStructure f = serverShip(sp, sp.getVehicle() != null ? sp.getVehicle().getId() : -1);
            if (f == null || f.systems().hangar() == null) return;
            var h = f.systems().hangar();
            var com = f.centreOfMass();
            ShipStructure s = dev.galactica.ship.yard.ShipTemplates.spawn(sp.level(), "shuttle", f.position().add(0, 40, 0), net.minecraft.core.Direction.SOUTH);
            if (s == null) return;
            org.joml.Vector3d pivot = dev.galactica.ship.ShipDocking.toWorld(f, new org.joml.Vector3d((h.minX() + h.maxX() + 1) / 2.0 - com.x(),
                    h.floorY() + 1.5 + s.pivot() - com.y(), (h.minZ() + h.maxZ() + 1) / 2.0 - com.z()));
            s.setAttitude(f.attitude());
            s.snapTo(pivot.x, pivot.y - s.pivot(), pivot.z);
            s.setDeltaMovement(f.getDeltaMovement());
            shuttleId = s.getId();
        });
        AutoTest.step(40, () -> {
            Minecraft mc = Minecraft.getInstance();
            var s = mc.level.getEntity(shuttleId);
            boolean ok = s instanceof Starship st && st.getVehicle() == ship() && st.dockKind() == dev.galactica.ship.ShipDocking.Kind.HANGAR;
            log(ok, "hangar capture", s == null ? "no shuttle on the client" : String.format(Locale.ROOT, "shuttle rides %s, %s",
                    s.getVehicle() == null ? "nothing" : s.getVehicle().getType().toShortString(), s instanceof Starship st ? st.dockKind() : "?"));
        });
        AutoTest.server(1, "remember the docked pose", sp -> {
            ShipStructure f = serverShip(sp, freighterId), s = serverShip(sp, shuttleId);
            if (f != null && s != null) dockedOffset = dev.galactica.ship.ShipDocking.toBody(f, dev.galactica.ship.ShipDocking.pivotWorld(s));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.orientShip(5, -60, 40);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 35, 70F));
        AutoTest.shot(30, "ships_40_hangar_shuttle_carried");
        AutoTest.step(5, ShipCamera::clearOrbit);
        // Recentres with the shuttle in the hangar: the freighter at ~14 blocks a tick through three of them.
        AutoTest.step(5, () -> OriginShiftAutoTest.start("hangar-carrier", new Vec3(0, 0, 14)));
        AutoTest.step(270, OriginShiftAutoTest::finish);
        AutoTest.step(5, () -> stop(Minecraft.getInstance(), ship()));
        AutoTest.server(20, "hangar pose after the recentres", sp -> {
            ShipStructure f = serverShip(sp, freighterId), s = serverShip(sp, shuttleId);
            double drift = f == null || s == null || dockedOffset == null ? Double.NaN
                    : dev.galactica.ship.ShipDocking.toBody(f, dev.galactica.ship.ShipDocking.pivotWorld(s)).distance(dockedOffset);
            log(s != null && s.getVehicle() == f && drift < 0.01, "hangar ship carried through recentres",
                    String.format(Locale.ROOT, "still in the hangar %s, pose drift %.4f blocks", s != null && s.getVehicle() == f, drift));
        });
        // The freighter's pilot releases the shuttle with the dock key: it leaves with the freighter's velocity, pushed up.
        AutoTest.step(5, () -> click(dev.galactica.client.ship.ShipSystemsClient.DOCK));
        AutoTest.step(30, () -> {
            var s = Minecraft.getInstance().level.getEntity(shuttleId);
            boolean free = s != null && !s.isPassenger();
            double up = s == null || ship() == null ? 0 : (s.getY() - ship().getY());
            log(free, "hangar release by the dock key", String.format(Locale.ROOT, "free %s, %.2f blocks above the freighter's origin", free, up));
        });
        AutoTest.server(40, "remove the released shuttle", sp -> {
            var s = serverEntity(sp, shuttleId);
            if (s != null) s.discard();
        });
        // Port to port: a second shuttle hovers with its belly port 2 blocks over the freighter's dorsal port; the player
        // moves to its helm and presses the dock key.
        AutoTest.server(5, "a shuttle over the dorsal port, the player at its helm", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f == null) return;
            ShipStructure s = dev.galactica.ship.yard.ShipTemplates.spawn(sp.level(), "shuttle", f.position().add(0, 30, 0), net.minecraft.core.Direction.SOUTH);
            if (s == null) return;
            s.setAttitude(f.attitude());
            var fp = f.systems().ports().getFirst();
            var spPort = s.systems().ports().getFirst();
            org.joml.Vector3d want = dev.galactica.ship.ShipDocking.portWorld(f, fp).add(new org.joml.Quaternionf(f.attitude()).transform(new org.joml.Vector3d(0, 2, 0)));
            org.joml.Vector3d now = dev.galactica.ship.ShipDocking.portWorld(s, spPort);
            s.snapTo(s.getX() + want.x - now.x, s.getY() + want.y - now.y, s.getZ() + want.z - now.z);
            s.setDeltaMovement(f.getDeltaMovement());
            shuttleId = s.getId();
        });
        // Boarded only once the client has the shuttle's pose: at the helm its client owns the attitude and position,
        // and a pose still in flight would be reported back over the placed one.
        AutoTest.server(30, "the player to the shuttle's helm", sp -> {
            ShipStructure s = serverShip(sp, shuttleId);
            if (s == null) return;
            sp.stopRiding();
            sp.startRiding(s, true, true);
        });
        AutoTest.step(30, () -> click(dev.galactica.client.ship.ShipSystemsClient.DOCK));
        AutoTest.step(30, () -> {
            Minecraft mc = Minecraft.getInstance();
            var v = mc.player.getVehicle();
            boolean ok = v instanceof Starship s && s.getId() == shuttleId && s.isDocked() && s.dockKind() == dev.galactica.ship.ShipDocking.Kind.PORT
                    && s.getVehicle() != null && s.getVehicle().getId() == freighterId;
            log(ok, "port docking by the dock key", v == null ? "not aboard" : String.format(Locale.ROOT, "aboard #%d, docked %s (%s) to #%s",
                    v.getId(), v instanceof Starship s && s.isDocked(), v instanceof Starship s ? s.dockKind() : "?",
                    v.getVehicle() == null ? "-" : String.valueOf(v.getVehicle().getId())));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 25, 60F));
        AutoTest.shot(30, "ships_41_port_docked");
        AutoTest.step(5, ShipCamera::clearOrbit);
        // The cockpit display's systems page in the docked shuttle (the freighter's scanner serves everyone aboard).
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.step(5, () -> click(dev.galactica.client.ship.ShipSystemsClient.PAGE));
        AutoTest.step(80, () -> {
            var scan = dev.galactica.client.ship.ShipSystemsClient.scan();
            var lines = dev.galactica.client.ship.ShipSystemsClient.lines(Minecraft.getInstance(), 5);
            log(dev.galactica.client.ship.ShipSystemsClient.page() == 1 && scan != null && !lines.isEmpty(), "scanner on the cockpit systems page",
                    String.format(Locale.ROOT, "page %d, scan %s, contacts %s", dev.galactica.client.ship.ShipSystemsClient.page(),
                            scan == null ? "none" : String.format(Locale.ROOT, "%.0f m", scan.range()), lines));
        });
        AutoTest.shot(5, "ships_42_cockpit_systems_page");
        AutoTest.step(5, () -> dev.galactica.client.ship.ShipSystemsClient.setPage(0));
        AutoTest.step(5, dev.galactica.client.map.GalaxyMapClient::open);
        AutoTest.step(60, () -> {
            if (Minecraft.getInstance().gui.screen() instanceof dev.galactica.client.map.GalaxyMapScreen s) {
                log(s.scannerRows > 0, "scanner in the map panel", "panel rows with the scanner section: " + s.scannerRows);
            } else log(false, "scanner in the map panel", "the map did not open");
        });
        AutoTest.shot(1, "ships_43_map_scanner_panel");
        AutoTest.step(5, () -> Minecraft.getInstance().gui.setScreen(null));
        // Planet entry with the pair docked: the freighter (nobody at its helm; the player, docked, leads) is carried
        // down to Mars with the shuttle on its port, then lifted back over the exit row to space.
        AutoTest.server(5, "remember the port pose", sp -> {
            ShipStructure f = serverShip(sp, freighterId), s = serverShip(sp, shuttleId);
            if (f != null && s != null) dockedOffset = dev.galactica.ship.ShipDocking.toBody(f, dev.galactica.ship.ShipDocking.pivotWorld(s));
        });
        AutoTest.cmd(5, "galactica land Mars");
        AutoTest.await(1, 600, () -> Minecraft.getInstance().level != null && Minecraft.getInstance().level.dimension() != Galactica.SPACE);
        AutoTest.server(60, "the docked pair on Mars", sp -> {
            var v = sp.getVehicle();
            boolean ok = v instanceof Starship s && s.isDocked() && s.dockKind() == dev.galactica.ship.ShipDocking.Kind.PORT
                    && s.getVehicle() instanceof ShipStructure f && f.blocks().size() == ShipTemplate.freighter().size();
            double drift = Double.NaN;
            if (ok) {
                shuttleId = v.getId();
                freighterId = v.getVehicle().getId();
                drift = dev.galactica.ship.ShipDocking.toBody((Starship) v.getVehicle(), dev.galactica.ship.ShipDocking.pivotWorld((Starship) v)).distance(dockedOffset);
                ok = drift < 0.01;
            }
            log(ok, "docked pair through planet entry", String.format(Locale.ROOT, "in %s aboard %s, pose drift %.4f",
                    sp.level().dimension().identifier(), v == null ? "nothing" : v.getType().toShortString(), drift));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 25, 70F));
        AutoTest.shot(40, "ships_44_docked_pair_mars");
        AutoTest.step(5, ShipCamera::clearOrbit);
        AutoTest.server(5, "lift the pair over the exit row", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f == null) return;
            f.setPos(f.getX(), dev.galactica.planet.PlanetEntry.exitY(sp.level()) + 10, f.getZ());
            f.setDeltaMovement(new Vec3(0, 0.5, 0));
        });
        AutoTest.await(1, 600, () -> Minecraft.getInstance().level != null && Minecraft.getInstance().level.dimension() == Galactica.SPACE);
        AutoTest.server(40, "the docked pair back in space", sp -> {
            var v = sp.getVehicle();
            boolean ok = sp.level().dimension() == Galactica.SPACE && v instanceof Starship s && s.isDocked()
                    && s.getVehicle() instanceof ShipStructure;
            double drift = Double.NaN;
            if (ok) {
                shuttleId = v.getId();
                freighterId = v.getVehicle().getId();
                drift = dev.galactica.ship.ShipDocking.toBody((Starship) v.getVehicle(), dev.galactica.ship.ShipDocking.pivotWorld((Starship) v)).distance(dockedOffset);
                ok = drift < 0.01;
            }
            log(ok, "docked pair through planet exit", String.format(Locale.ROOT, "in %s, pose drift %.4f", sp.level().dimension().identifier(), drift));
        });
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.step(5, () -> click(dev.galactica.client.ship.ShipSystemsClient.DOCK));
        AutoTest.step(30, () -> {
            var v = Minecraft.getInstance().player.getVehicle();
            log(v instanceof Starship s && !s.isDocked() && ShipClient.piloted(Minecraft.getInstance()) == s, "undock by the dock key",
                    v == null ? "not aboard" : "aboard #" + v.getId() + ", docked " + (v instanceof Starship s && s.isDocked()));
        });
        // Cargo in flight: back at the freighter's helm, a stack in its first container, the cargo key opens it.
        AutoTest.server(5, "the player to the freighter's helm, diamonds in its cargo", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            var old = sp.getVehicle();
            if (f == null) return;
            sp.stopRiding();
            if (old != null) old.discard();
            sp.startRiding(f, true, true);
            int box = dev.galactica.ship.ShipCargo.containers(f.blocks())[0];
            dev.galactica.ship.ShipCargo.store(f, box, java.util.List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5)));
        });
        AutoTest.step(20, () -> click(dev.galactica.client.ship.ShipSystemsClient.CARGO));
        AutoTest.step(20, () -> {
            var screen = Minecraft.getInstance().gui.screen();
            boolean open = screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c
                    && c.getMenu().slots.getFirst().getItem().is(net.minecraft.world.item.Items.DIAMOND);
            log(open, "cargo opened in flight by the cargo key", screen == null ? "no screen" : screen.getTitle().getString());
        });
        AutoTest.shot(1, "ships_45_cargo_in_flight");
        AutoTest.step(5, () -> Minecraft.getInstance().player.closeContainer());
        // Shields: a hit drains the shield, not the hull; the ship HUD (third person) shows it.
        AutoTest.server(10, "hit the freighter", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f == null) return;
            f.setShield(f.maxShield());
            float hull = f.hull();
            f.hurtServer(sp.level(), sp.level().damageSources().generic(), 6F);
            log(f.hull() == hull && f.shield() < f.maxShield(), "shield absorbs a hit", String.format(Locale.ROOT, "hull %.1f -> %.1f, shield %.1f/%.0f",
                    hull, f.hull(), f.shield(), f.maxShield()));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(20, "ships_46_shield_hud");
        // The wreck: everyone set down safely in space (EVA), blocks and cargo scattered.
        AutoTest.server(5, "destroy the freighter", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f == null) return;
            f.hurtServer(sp.level(), sp.level().damageSources().generic(), 1e6F);
            var drops = sp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, sp.getBoundingBox().inflate(80));
            log(f.isRemoved() && !sp.isPassenger() && sp.isAlive() && drops.size() > 5, "ship destroyed at hull 0",
                    String.format(Locale.ROOT, "removed %s, player aboard %s alive %s, %d item stacks scattered", f.isRemoved(), sp.isPassenger(), sp.isAlive(), drops.size()));
        });
        AutoTest.step(5, () -> ShipCamera.orbit(150, 20, 30F));
        AutoTest.shot(30, "ships_47_wreck");
        AutoTest.step(5, ShipCamera::clearOrbit);
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.server(10, "clear the wreck", sp -> {
            for (var e : sp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, sp.getBoundingBox().inflate(120))) e.discard();
        });
    }

    /** Every prefab, deployed and boarded in space near the Sun: outside in the chase view and from the helm. */
    static void prefabs() {
        AutoTest.cmd(20, "galactica launch");
        AutoTest.cmd(60, "galactica goto Sol");
        for (String name : ShipTemplate.NAMES) {
            AutoTest.cmd(20, "galactica ship " + name);
            AutoTest.step(20, () -> {
                ShipStructure s = ship();
                boolean ok = s != null && s.flightReady() && s.blocks().size() == ShipTemplate.byName(name).size();
                log(ok, "prefab " + name, s == null ? "not aboard" : String.format(Locale.ROOT,
                        "%d blocks, %.0f t, main %.1f m/s^2, lift %.1f m/s^2, H2 %.0f, jump %.0f ly, %d seats, %d probes, turn x%.2f",
                        s.blocks().size(), s.spec().massKg() / 1000, s.spec().mainAccel(), s.spec().liftAccel(), s.maxHydrogen(),
                        s.spec().jumpRangeLy(), s.spec().seatCount(), s.spec().probes().length, s.spec().turnScale()));
            });
            AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
            // The Sun ahead-left and high, so the orbit camera (from ahead and to port) sees the lit side.
            AutoTest.orientShip(5, -60, 40);
            AutoTest.step(5, () -> {
                ShipStructure s = ship();
                float d = s == null ? 20F : 2.2F * s.spec().hullReach() + 4F;
                ShipCamera.orbit(145, 22, d);
            });
            AutoTest.shot(30, "ships_38_prefab_" + name + "_exterior");
            AutoTest.step(5, ShipCamera::clearOrbit);
            AutoTest.camera(5, CameraType.FIRST_PERSON);
            AutoTest.shot(20, "ships_38_prefab_" + name + "_cockpit");
            AutoTest.server(5, "remove the prefab", sp -> {
                if (sp.getVehicle() instanceof ShipStructure s) {
                    sp.stopRiding();
                    s.discard();
                }
            });
        }
    }

    /** Server: sets the pilot's jump target to the nearest star between 1 and 9 ly (Sol itself is a few AU away). */
    private static void targetNearestStar(net.minecraft.server.level.ServerPlayer sp) {
        var g = dev.galactica.nav.Frames.playerPos(sp.getAttachedOrCreate(Galactica.NAV), sp.getX(), sp.getY(), sp.getZ());
        double pcPerLy = dev.galactica.science.Units.LIGHT_YEAR / dev.galactica.science.Units.PARSEC;
        var best = dev.galactica.nav.NavServer.neighbourhood(sp).nearbyStars(g).stream()
                .filter(s -> {
                    double ly = s.distanceTo(g.xPc(), g.yPc(), g.zPc()) / pcPerLy;
                    return ly > 1 && ly < 9;
                })
                .min(java.util.Comparator.comparingDouble(s -> s.distanceTo(g.xPc(), g.yPc(), g.zPc()))).orElse(null);
        if (best == null) {
            Galactica.LOG.warn("[autotest] FAIL built ship hyperjump: no star within 1-9 ly");
            return;
        }
        var p = dev.galactica.nav.Neighbourhood.starPos(best);
        sp.setAttached(dev.galactica.nav.JumpTargets.TYPE, new dev.galactica.nav.JumpTarget(best.name(), p.xPc(), p.yPc(), p.zPc()));
        Galactica.LOG.info("[autotest] built ship jump target {}", best.name());
    }

    /**
     * The autopilot flying a block-built ship (the shuttle) through the same pilot control path as the Kestrel: from a
     * high Earth orbit to a Mars orbit (warp, then powered circularisation), then down to the surface (its landing
     * margin read from the shuttle's own lift).
     */
    static void autopilot() {
        AutoTest.cmd(20, "galactica launch");
        AutoTest.cmd(60, "galactica goto Sol");
        AutoTest.cmd(20, "galactica ship shuttle");
        AutoTest.cmd(10, "galactica target Mars");
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.step(10, () -> {
            ShipStructure s = ship();
            if (s != null && s.coupled()) s.toggleCoupled();
        });
        AutoTest.server(10, "park the shuttle 30 000 km over Earth on a circular orbit", sp -> {
            AutoTest.parkOverBody(sp, "Earth", 30_000e3);
            dev.galactica.nav.NavServer.circularize(sp);
        });
        AutoTest.clearChat(20);
        AutoTest.step(1, () -> watch(true));
        AutoTest.autopilot(5, dev.galactica.nav.autopilot.AutopilotState.Mode.ORBIT, 20 * 900, "ships_39_built_autopilot_hud");
        AutoTest.verdictOrbit(5, "Mars");
        AutoTest.autopilot(5, dev.galactica.nav.autopilot.AutopilotState.Mode.LAND, 20 * 900, "ships_39b_built_autopilot_descent");
        AutoTest.verdictLanded(5, "Mars");
        AutoTest.verdictTargetHere(10, "Mars");
        AutoTest.step(5, () -> {
            Minecraft mc = Minecraft.getInstance();
            ShipStructure s = ship();
            boolean ok = s != null && mc.level.dimension() != Galactica.SPACE && (s.onGround() || s.restingContact())
                    && s.blocks().size() == ShipTemplate.shuttle().size();
            log(ok, "autopilot (shuttle) Mars orbit and landing", String.format(Locale.ROOT, "in %s, aboard a built ship %s, resting %s, max landing gravity %.1f m/s^2",
                    mc.level.dimension().identifier(), s != null, s != null && (s.onGround() || s.restingContact()),
                    s == null ? 0 : dev.galactica.nav.autopilot.AutopilotMath.maxLandingGravity(s.spec())));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 20, 24F));
        AutoTest.shot(30, "ships_39c_built_autopilot_landed_mars");
        AutoTest.step(5, ShipCamera::clearOrbit);
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.server(10, "leave the shuttle", sp -> {
            if (sp.getVehicle() instanceof ShipStructure s) {
                sp.stopRiding();
                s.discard();
            }
        });
        AutoTest.cmd(10, "galactica target clear");
        AutoTest.cmd(10, "galactica launch");
        AutoTest.cmd(40, "galactica goto Sol");
    }

    private static boolean watching, watchInstalled;
    private static int watchTicks;
    private static String lastVehicle = "";

    /** Logs the local player's vehicle whenever it changes, and the ship's state every 2 s, while the autopilot runs. */
    static void watch(boolean on) {
        watching = on;
        if (watchInstalled) return;
        watchInstalled = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!watching || mc.player == null) return;
            var v = mc.player.getVehicle();
            String now = v == null ? "none" : v.getClass().getSimpleName() + "#" + v.getId();
            if (!now.equals(lastVehicle)) {
                Galactica.LOG.info("[autotest] built ship watch: vehicle {} -> {} (player at {})", lastVehicle, now, mc.player.position());
                lastVehicle = now;
            }
            if (++watchTicks % 40 == 0 && v instanceof ShipStructure s) {
                Galactica.LOG.info(String.format(Locale.ROOT, "[autotest] built ship watch: ship #%d y %.1f v %.1f m/s piloted %s flightReady %s",
                        s.getId(), s.getY(), s.getDeltaMovement().length() * 20, ship() == s, s.flightReady()));
            }
        });
    }

    private static ShipStructure ship() {
        return ShipClient.piloted(Minecraft.getInstance()) instanceof ShipStructure s ? s : null;
    }

    private static void checkAssembled() {
        Minecraft mc = Minecraft.getInstance();
        ShipStructure nearest = null;
        for (var e : mc.level.entitiesForRendering()) if (e instanceof ShipStructure s && s.distanceTo(mc.player) < 40) nearest = s;
        boolean helmGone = mc.level.getBlockState(new BlockPos(HX, HY, HZ)).isAir();
        boolean ok = nearest != null && nearest.blocks().size() == TEST_SHIP_BLOCKS && helmGone && nearest.flightReady();
        log(ok, "assemble", nearest == null ? "no ship entity" : String.format(Locale.ROOT,
                "%d blocks (expected %d) on the client, world cleared %s, %.1f t, main %.1f m/s^2, lift %.1f m/s^2, H2 %.0f, "
                        + "jump %.0f ly, %d seats, %d probes, box %.1f x %.1f",
                nearest.blocks().size(), TEST_SHIP_BLOCKS, helmGone, nearest.spec().massKg() / 1000, nearest.spec().mainAccel(),
                nearest.spec().liftAccel(), nearest.maxHydrogen(), nearest.spec().jumpRangeLy(), nearest.spec().seatCount(),
                nearest.spec().probes().length, nearest.spec().boxWidth(), nearest.spec().boxHeight()));
    }

    private static void log(boolean ok, String what, String detail) {
        Galactica.LOG.info("[autotest] {} built ship {}: {}", ok ? "PASS" : "FAIL", what, detail);
    }

    private static void attitude(Minecraft mc, Starship ship, Quaternionf q) {
        ship.setAttitude(q);
        var srv = mc.getSingleplayerServer();
        if (srv == null) return;
        java.util.UUID id = ship.getUUID();
        srv.execute(() -> {
            for (var lvl : srv.getAllLevels()) if (lvl.getEntity(id) instanceof Starship s) s.setAttitude(q);
        });
    }

    private static void move(Minecraft mc, Starship ship, double y) {
        double x = ship.getX(), z = ship.getZ();
        ship.setPos(x, y, z);
        ship.xo = x;
        ship.yo = y;
        ship.zo = z;
        var srv = mc.getSingleplayerServer();
        if (srv == null) return;
        java.util.UUID id = ship.getUUID();
        srv.execute(() -> {
            for (var lvl : srv.getAllLevels()) if (lvl.getEntity(id) instanceof Starship s) s.setPos(x, y, z);
        });
    }

    private static void stop(Minecraft mc, Starship ship) {
        ship.overrideVelocity(Vec3.ZERO);
        var srv = mc.getSingleplayerServer();
        if (srv == null) return;
        java.util.UUID id = ship.getUUID();
        srv.execute(() -> {
            for (var lvl : srv.getAllLevels()) if (lvl.getEntity(id) instanceof Starship s) s.setDeltaMovement(Vec3.ZERO);
        });
    }

    @SuppressWarnings("unused")
    private static boolean inOverworld(Minecraft mc) {
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }
}
