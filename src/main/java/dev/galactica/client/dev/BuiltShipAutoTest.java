package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.ship.ShipCamera;
import dev.galactica.client.ship.ShipClient;
import dev.galactica.nav.NavState;
import dev.galactica.ship.HyperjumpPayload;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.build.ShipAssembler;
import dev.galactica.ship.build.ShipTemplate;
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
        AutoTest.cmd(0, "galactica kit");
        AutoTest.cmd(5, "execute in minecraft:overworld run forceload add -16 -8 16 40");
        AutoTest.cmd(80, "execute in minecraft:overworld run tp @s 0.5 201 0.5 0 15");
        AutoTest.cmd(5, "kill @e[type=galactica:built_ship,distance=..300]");
        AutoTest.cmd(5, "fill -14 199 -4 14 199 34 minecraft:stone");
        AutoTest.cmd(5, "fill -14 200 -4 14 212 34 minecraft:air");
        AutoTest.cmd(5, "setblock 0 199 0 minecraft:stone");
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
        AutoTest.shot(10, "30_built_ship_blocks");
        AutoTest.cmd(5, "galactica shipassemble " + HX + " " + HY + " " + HZ);
        AutoTest.step(40, BuiltShipAutoTest::checkAssembled);
        AutoTest.shot(5, "31_built_ship_assembled");
        AutoTest.cmd(5, "ride @s mount @e[type=galactica:built_ship,sort=nearest,limit=1]");
        AutoTest.server(10, "fuel the built ship", sp -> {
            if (sp.getVehicle() instanceof Starship s) {
                s.setHydrogen(s.maxHydrogen());
                s.setExotic(16);
            }
        });
        AutoTest.camera(20, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(20, "32_built_ship_boarded");
        // Hover: a short lift burst off the pad, then hands off with flight assist on: it holds altitude.
        AutoTest.key(5, "keyJump", true);
        AutoTest.key(12, "keyJump", false);
        AutoTest.step(30, () -> hoverY = ship() != null ? ship().getY() : Double.NaN);
        AutoTest.step(200, () -> {
            ShipStructure s = ship();
            double drift = s == null ? Double.NaN : Math.abs(s.getY() - hoverY);
            boolean ok = s != null && drift < 0.5 && s.getY() > 203 && s.coupled();
            log(ok, "hover", String.format(Locale.ROOT, "altitude %.2f -> %.2f over 10 s (drift %.3f, limit 0.5), assist %s",
                    hoverY, s == null ? Double.NaN : s.getY(), drift, s != null && s.coupled()));
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
        AutoTest.shot(20, "33_built_ship_cockpit");
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
        AutoTest.step(180, OriginShiftAutoTest::finish);
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 20, 14F));
        AutoTest.shot(20, "34_built_ship_space");
        AutoTest.step(5, ShipCamera::clearOrbit);
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        // Hyperjump with the ship's own core (tier 10 ly), to the nearest star.
        AutoTest.server(5, "no map target: the drive aims at the nearest star", sp -> sp.removeAttached(dev.galactica.nav.JumpTargets.TYPE));
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
        AutoTest.shot(20, "35_built_ship_mars_cockpit");
        AutoTest.landedExterior("36_built_ship_mars_exterior");
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
            boolean helmBack = sp.level().getBlockState(helm).is(dev.galactica.ship.build.ShipPartBlocks.HELM);
            log(done && helmBack && s.isRemoved(), "disassemble", String.format(Locale.ROOT,
                    "%d blocks set down on Mars, helm back at %s: %s", blocks, helm.toShortString(), helmBack));
        });
        AutoTest.camera(10, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(20, "37_built_ship_disassembled_mars");
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        prefabs();
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
            AutoTest.step(5, () -> {
                ShipStructure s = ship();
                float d = s == null ? 20F : 2.2F * s.spec().hullReach() + 4F;
                ShipCamera.orbit(145, 22, d);
            });
            AutoTest.shot(30, "38_prefab_" + name + "_exterior");
            AutoTest.step(5, ShipCamera::clearOrbit);
            AutoTest.camera(5, CameraType.FIRST_PERSON);
            AutoTest.shot(20, "38_prefab_" + name + "_cockpit");
            AutoTest.server(5, "remove the prefab", sp -> {
                if (sp.getVehicle() instanceof ShipStructure s) {
                    sp.stopRiding();
                    s.discard();
                }
            });
        }
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
