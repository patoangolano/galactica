package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.ship.DeckClient;
import dev.galactica.client.ship.ShipCamera;
import dev.galactica.client.ship.ShipClient;
import dev.galactica.ship.ShipDocking;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.deck.Deck;
import dev.galactica.ship.deck.DeckPos;
import dev.galactica.ship.deck.ShipPose;
import dev.galactica.ship.yard.ShipTemplates;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Deck walking and ship-to-ship collision in a real client ({@code runAutotestShips}, and inside the default run), one
 * {@code [autotest] PASS|FAIL deck …} line per check, with screenshots:
 * <ol>
 *   <li>Overworld: a landed freighter boarded on foot up its boarding steps and through its door, the walk to the
 *       helm, a click on the helm, a shuttle parked on the freighter's roof, and the take-off carrying it;</li>
 *   <li>space: walking on the deck of a freighter coasting at 285 m/s (through floating-origin recentres) and through a
 *       turn, then out of the door into space (EVA keeping the ship's velocity);</li>
 *   <li>space: walking into the hangar to a docked shuttle and boarding it;</li>
 *   <li>space: a shuttle and an explorer colliding head-on.</li>
 * </ol>
 */
final class DeckAutoTest {
    private DeckAutoTest() {}

    private static int freighterId = -1, shuttleId = -1, explorerId = -1;
    private static double startZ, shipStartZ, minDeckY, yawBefore, headingBefore, takeOffY, shuttleRelY;
    private static int recentresBefore;
    private static float hullShuttle, hullExplorer;
    private static boolean monitoring;

    static void run() {
        overworld();
        cruise();
        hangar();
        collision();
    }

    // ------------------------------------------------------------------ helpers

    private static void log(boolean ok, String what, String detail) {
        Galactica.LOG.info("[autotest] {} deck {}: {}", ok ? "PASS" : "FAIL", what, detail);
    }

    private static Entity client(int id) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getEntity(id);
    }

    private static ShipStructure clientShip(int id) {
        return client(id) instanceof ShipStructure s ? s : null;
    }

    private static ShipStructure serverShip(ServerPlayer sp, int id) {
        return sp.level().getEntity(id) instanceof ShipStructure s ? s : null;
    }

    /** The local walker's deck position, or null. */
    private static DeckPos deck() {
        return Deck.clientState(Minecraft.getInstance().player);
    }

    private static String fmt(DeckPos p) {
        return p == null ? "not on a deck" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", p.x(), p.y(), p.z());
    }

    /** Faces the player along a direction of the ship's frame: yaw 0 its nose (+Z), −90 its port side (+X). */
    private static void faceOnShip(ShipStructure ship, double localYawDeg, float pitch) {
        var p = Minecraft.getInstance().player;
        float yaw = (float) (localYawDeg + Math.toDegrees(ShipPose.heading(Deck.pose(ship).attitude())));
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot(pitch);
    }

    /** Looks from the player's eye at a cell of the ship (its centre). */
    private static void lookAtCell(ShipStructure ship, double x, double y, double z) {
        var p = Minecraft.getInstance().player;
        Vector3d w = Deck.pose(ship).toWorld(new Vector3d(x + 0.5, y + 0.5, z + 0.5));
        lookAt(new Vec3(w.x, w.y, w.z));
    }

    private static void lookAt(Vec3 target) {
        var p = Minecraft.getInstance().player;
        Vec3 d = target.subtract(p.getEyePosition());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot(pitch);
    }

    private static void click() {
        net.minecraft.client.KeyMapping.click(Minecraft.getInstance().options.keyUse.getDefaultKey());
    }

    /** Puts the player on a ship's deck at a cell position (server: board as a walker). */
    private static void boardAt(int wait, String label, java.util.function.IntSupplier ship, double x, double y, double z) {
        AutoTest.server(wait, label, sp -> {
            ShipStructure s = serverShip(sp, ship.getAsInt());
            if (s == null) {
                log(false, label, "no ship");
                return;
            }
            sp.stopRiding();
            Vector3d at = new Vector3d(x, y, z);
            Vector3d w = Deck.pose(s).toWorld(at);
            sp.snapTo(w.x, w.y, w.z);
            Deck.board(s, sp, at, 1 + sp.getRandom().nextInt(1 << 20), s.getDeltaMovement());
        });
    }

    // ------------------------------------------------------------------ 1. Overworld: on foot to the helm, parked shuttle, take-off

    private static void overworld() {
        AutoTest.cmd(10, "galactica target clear");
        AutoTest.cmd(5, "execute in minecraft:overworld run tp @s 0.5 260 0.5");
        AutoTest.cmd(20, "execute in minecraft:overworld run forceload add -32 -16 32 48");
        AutoTest.cmd(60, "execute in minecraft:overworld run fill -12 199 -6 24 199 36 minecraft:stone");
        AutoTest.cmd(5, "execute in minecraft:overworld run fill -12 200 -6 24 219 36 minecraft:air");
        AutoTest.cmd(5, "kill @e[type=galactica:built_ship,distance=..400]");
        // The freighter on the pad: helm at (0, 203, 30), nose south, its gear on the stone; the boarding steps on its
        // port side (+X) run down to the pad at x 14.
        AutoTest.server(10, "a freighter landed on the pad", sp -> {
            ShipStructure f = ShipTemplates.spawn(sp.level(), "freighter", new Vec3(0.5, 203.02, 30.5), Direction.SOUTH);
            freighterId = f == null ? -1 : f.getId();
        });
        AutoTest.cmd(60, "execute in minecraft:overworld run tp @s 16.5 200 27.5 90 0");
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(10, "deck_01_freighter_steps");
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        // Walk up the steps and in through the door (west, −X).
        AutoTest.step(5, () -> {
            var f = clientShip(freighterId);
            if (f != null) faceOnShip(f, 90, 10);
            DeckClient.boards = 0;
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.await(1, 200, () -> deck() != null && deck().x() < 5.0);
        AutoTest.key(1, "keyUp", false);
        AutoTest.step(10, () -> {
            DeckPos d = deck();
            Minecraft mc = Minecraft.getInstance();
            boolean ok = d != null && d.x() < 6 && Math.abs(d.y()) < 0.05 && mc.player.getVehicle() != null && mc.player.getVehicle().getId() == freighterId;
            log(ok, "boarded on foot up the steps and through the door", String.format(Locale.ROOT, "deck position %s, boardings %d", fmt(d), DeckClient.boards));
        });
        AutoTest.shot(5, "deck_02_inside_the_door");
        // To the bridge: along the deck to x 0.5, then forward to the helm.
        AutoTest.step(5, () -> {
            var f = clientShip(freighterId);
            if (f != null) faceOnShip(f, 90, 0);
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.await(1, 120, () -> deck() != null && deck().x() < 0.7);
        AutoTest.key(1, "keyUp", false);
        AutoTest.step(5, () -> {
            var f = clientShip(freighterId);
            if (f != null) faceOnShip(f, 0, 0);
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.await(1, 60, () -> deck() != null && deck().z() > -1.7);
        AutoTest.key(1, "keyUp", false);
        AutoTest.step(10, () -> {
            var f = clientShip(freighterId);
            if (f != null) lookAtCell(f, 0, 0, 0);
        });
        AutoTest.shot(5, "deck_03_at_the_helm");
        AutoTest.step(5, DeckAutoTest::click);
        AutoTest.await(1, 40, () -> ShipClient.piloted(Minecraft.getInstance()) != null);
        AutoTest.step(10, () -> {
            var piloted = ShipClient.piloted(Minecraft.getInstance());
            log(piloted != null && piloted.getId() == freighterId, "walked to the helm and took it by a click",
                    String.format(Locale.ROOT, "piloting %s, deck uses %d", piloted == null ? "nothing" : "#" + piloted.getId(), DeckClient.uses));
        });
        // A shuttle set down on the freighter's roof, forward of the hatch: it settles and parks there.
        AutoTest.server(5, "a shuttle over the freighter's roof", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f == null) return;
            Vector3d at = Deck.pose(f).toWorld(new Vector3d(5.5, 7.4 + 2, -0.5));
            ShipStructure s = ShipTemplates.spawn(sp.level(), "shuttle", new Vec3(at.x, at.y, at.z), Direction.EAST);
            shuttleId = s == null ? -1 : s.getId();
        });
        AutoTest.await(20, 200, () -> client(shuttleId) instanceof Starship s && s.isDocked());
        AutoTest.server(5, "the shuttle parked on the roof", sp -> {
            ShipStructure f = serverShip(sp, freighterId), s = serverShip(sp, shuttleId);
            boolean ok = f != null && s != null && s.getVehicle() == f && s.dockKind() == ShipDocking.Kind.DECK;
            Vector3d rel = f == null || s == null ? new Vector3d() : ShipDocking.toBody(f, ShipDocking.pivotWorld(s));
            shuttleRelY = rel.y;
            log(ok, "parked ship resting on another", String.format(Locale.ROOT, "shuttle on the freighter's roof: rides %s (%s), %.2f blocks over the freighter's pivot",
                    s == null || s.getVehicle() == null ? "nothing" : "#" + s.getVehicle().getId(), s == null ? "?" : s.dockKind(), rel.y));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.step(5, () -> ShipCamera.orbit(150, 30, 60F));
        AutoTest.shot(30, "deck_04_shuttle_parked_on_the_roof");
        // Take off on the lift jets: the parked shuttle goes up with the freighter.
        AutoTest.step(5, () -> takeOffY = client(freighterId) == null ? Double.NaN : client(freighterId).getY());
        AutoTest.key(1, "keyJump", true);
        AutoTest.key(60, "keyJump", false);
        AutoTest.server(5, "take-off", sp -> {
            ShipStructure f = serverShip(sp, freighterId), s = serverShip(sp, shuttleId);
            double climbed = f == null ? 0 : f.getY() - takeOffY;
            Vector3d rel = f == null || s == null ? new Vector3d() : ShipDocking.toBody(f, ShipDocking.pivotWorld(s));
            boolean ok = climbed > 3 && s != null && s.getVehicle() == f && Math.abs(rel.y - shuttleRelY) < 0.05;
            log(ok, "take-off from the helm carrying the parked shuttle", String.format(Locale.ROOT,
                    "climbed %.1f blocks in 3 s, shuttle still parked %s (pose drift %.3f)", climbed, s != null && s.getVehicle() == f, Math.abs(rel.y - shuttleRelY)));
        });
        AutoTest.shot(20, "deck_05_take_off");
        AutoTest.step(5, ShipCamera::clearOrbit);
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.server(10, "clear the Overworld ships", sp -> {
            sp.stopRiding();
            for (int id : new int[] {shuttleId, freighterId}) {
                Entity e = sp.level().getEntity(id);
                if (e != null) e.discard();
            }
        });
    }

    // ------------------------------------------------------------------ 2. space: 285 m/s, a turn, EVA

    private static void cruise() {
        AutoTest.cmd(10, "galactica launch");
        AutoTest.cmd(60, "galactica goto Sol");
        AutoTest.server(10, "a freighter in space, the player on its deck", sp -> {
            ShipStructure f = ShipTemplates.spawn(sp.level(), "freighter", sp.position().add(0, -2, 20), Direction.SOUTH);
            freighterId = f == null ? -1 : f.getId();
        });
        boardAt(40, "board the freighter's deck", () -> freighterId, -2.5, 0, -12.5);
        AutoTest.await(5, 100, () -> deck() != null);
        AutoTest.step(20, () -> {
            DeckPos d = deck();
            var f = clientShip(freighterId);
            log(d != null && f != null && f.artificialGravityOn() && Math.abs(d.y()) < 0.05, "artificial gravity on deck in space",
                    String.format(Locale.ROOT, "standing at %s, generator powered %s", fmt(d), f != null && f.artificialGravityOn()));
        });
        // 285 m/s along the nose: the walk is in the ship's frame, the ship crosses recentres.
        AutoTest.server(5, "the freighter to 285 m/s", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f != null) f.setDeltaMovement(new Vec3(0, 0, 14.25));
        });
        AutoTest.step(20, () -> {
            var f = clientShip(freighterId);
            DeckPos d = deck();
            if (f != null) faceOnShip(f, 0, 10);
            startZ = d == null ? Double.NaN : d.z();
            shipStartZ = f == null ? Double.NaN : f.getZ();
            minDeckY = Double.MAX_VALUE;
            recentresBefore = dev.galactica.client.nav.OriginShiftClient.applied;
            monitor();
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.key(40, "keyUp", false);
        AutoTest.step(20, () -> {
            DeckPos d = deck();
            var f = clientShip(freighterId);
            double walked = d == null ? 0 : d.z() - startZ;
            double speed = f == null ? 0 : f.getDeltaMovement().length() * 20;
            boolean ok = d != null && walked > 6 && walked < 11 && minDeckY > -0.05 && speed > 250;
            log(ok, "walk on the deck at 285 m/s", String.format(Locale.ROOT,
                    "walked %.2f blocks forward in 2 s on a deck at %.0f m/s, lowest deck height %.3f, recentres %d, now %s",
                    walked, speed, minDeckY, dev.galactica.client.nav.OriginShiftClient.applied - recentresBefore, fmt(d)));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(10, "deck_06_walking_at_285_ms");
        // A turn: the ship yaws ~57° while the player walks across the deck.
        AutoTest.step(5, () -> {
            var f = clientShip(freighterId);
            if (f != null) faceOnShip(f, -90, 10);
            yawBefore = Minecraft.getInstance().player.getYRot();
            headingBefore = f == null ? 0 : Math.toDegrees(ShipPose.heading(Deck.pose(f).attitude()));
            DeckPos d = deck();
            startZ = d == null ? Double.NaN : d.x();
            minDeckY = Double.MAX_VALUE;
        });
        AutoTest.server(1, "turn the freighter", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f != null) f.addSpin(new Vector3d(0, 1.0, 0));
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.key(25, "keyUp", false);
        AutoTest.shot(1, "deck_07_walking_in_a_turn");
        AutoTest.step(30, () -> {
            var f = clientShip(freighterId);
            DeckPos d = deck();
            double turned = f == null ? 0 : wrap(Math.toDegrees(ShipPose.heading(Deck.pose(f).attitude())) - headingBefore);
            double playerTurned = wrap(Minecraft.getInstance().player.getYRot() - yawBefore);
            double walked = d == null ? 0 : d.x() - startZ;
            boolean ok = d != null && Math.abs(turned) > 20 && minDeckY > -0.05 && walked > 2 && Math.abs(playerTurned - turned) < 8;
            log(ok, "walk across the deck during a turn", String.format(Locale.ROOT,
                    "ship turned %.1f deg, the player turned %.1f deg with it, walked %.2f blocks to port, lowest deck height %.3f",
                    turned, playerTurned, walked, minDeckY));
        });
        // EVA: out of the port door (cells x 7, z −3) and off the steps into space, keeping the ship's velocity. At 50 m/s:
        // the freighter (nobody aboard once the player is out) is not recentred with the player, so the check stays
        // clear of a recentre (one every 1 024 blocks).
        AutoTest.server(5, "the freighter to 50 m/s", sp -> {
            ShipStructure f = serverShip(sp, freighterId);
            if (f != null) f.setDeltaMovement(new Vec3(0, 0, 2.5));
        });
        boardAt(5, "back to the door", () -> freighterId, 5.5, 0, -2.5);
        AutoTest.await(5, 60, () -> deck() != null && deck().x() > 5);
        AutoTest.step(5, () -> {
            var f = clientShip(freighterId);
            if (f != null) faceOnShip(f, -90, 0);
            DeckClient.leaves = 0;
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.await(1, 200, () -> !Minecraft.getInstance().player.isPassenger());
        AutoTest.key(1, "keyUp", false);
        AutoTest.step(40, () -> {
            Minecraft mc = Minecraft.getInstance();
            var f = clientShip(freighterId);
            double dist = f == null ? Double.NaN : mc.player.position().distanceTo(f.position());
            double drift = DeckClient.drift().length() * 20;
            boolean ok = !mc.player.isPassenger() && f != null && dist < 40 && drift > 40;
            log(ok, "EVA off the ship in space keeps its velocity", String.format(Locale.ROOT,
                    "off the deck (%d step-offs), 2 s later %.1f blocks from the freighter's origin, drifting at %.0f m/s with it",
                    DeckClient.leaves, dist, drift));
        });
        AutoTest.step(1, () -> {
            var f = clientShip(freighterId);
            if (f != null) lookAt(f.position().add(0, 3, 0));
        });
        AutoTest.shot(5, "deck_08_eva_beside_the_freighter");
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.step(1, () -> monitoring = false);
        AutoTest.server(10, "remove the freighter", sp -> {
            Entity e = sp.level().getEntity(freighterId);
            if (e != null) e.discard();
        });
    }

    private static double wrap(double d) {
        d %= 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    private static boolean monitorInstalled;

    /** Tracks the lowest deck height of the walker every tick (a fall through the deck would show). */
    private static void monitor() {
        monitoring = true;
        if (monitorInstalled) return;
        monitorInstalled = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!monitoring || mc.player == null) return;
            DeckPos d = Deck.clientState(mc.player);
            if (d != null) minDeckY = Math.min(minDeckY, d.y());
        });
    }

    // ------------------------------------------------------------------ 3. into the hangar, aboard the docked shuttle

    private static void hangar() {
        AutoTest.server(10, "a freighter with a shuttle in its hangar", sp -> {
            ShipStructure f = ShipTemplates.spawn(sp.level(), "freighter", sp.position().add(0, -2, 20), Direction.SOUTH);
            if (f == null) return;
            freighterId = f.getId();
            var h = f.systems().hangar();
            var com = f.centreOfMass();
            ShipStructure s = ShipTemplates.spawn(sp.level(), "shuttle", f.position().add(0, 40, 0), Direction.SOUTH);
            if (s == null) return;
            shuttleId = s.getId();
            Vector3d off = new Vector3d((h.minX() + h.maxX() + 1) / 2.0 - com.x(), h.floorY() + 1 - com.y() + s.pivot(), h.minZ() + 1.5 + 6.5 - com.z());
            ShipDocking.dockAt(f, s, ShipDocking.Kind.HANGAR, new org.joml.Vector3f((float) off.x, (float) off.y, (float) off.z), new org.joml.Quaternionf());
        });
        // On the deck forward of the hangar, facing aft.
        boardAt(40, "board the freighter's deck by the bridge", () -> freighterId, 0.5, 0, -3.5);
        AutoTest.await(5, 100, () -> deck() != null);
        AutoTest.step(10, () -> {
            var f = clientShip(freighterId);
            if (f != null) faceOnShip(f, 180, 5);
        });
        AutoTest.key(1, "keyUp", true);
        AutoTest.await(1, 200, () -> {
            DeckPos d = deck();
            var s = client(shuttleId);
            if (d == null || !(s instanceof ShipStructure sh)) return false;
            // Blocked by the shuttle's nose, or within reach of it.
            Vector3d nose = dev.galactica.ship.ShipDocking.toWorld(sh, new Vector3d(0, 0, sh.spec().hullReach() * 0.6));
            return Minecraft.getInstance().player.getEyePosition().distanceTo(new Vec3(nose.x, nose.y, nose.z)) < 3.5 || d.z() < -9.3;
        });
        AutoTest.key(1, "keyUp", false);
        AutoTest.step(10, () -> {
            if (client(shuttleId) instanceof ShipStructure sh) {
                var com = sh.centreOfMass();
                Vector3d helm = dev.galactica.ship.ShipDocking.toWorld(sh, new Vector3d(0.5 - com.x(), 1.5 - com.y(), 0.5 - com.z()));
                lookAt(new Vec3(helm.x, helm.y, helm.z));
            }
        });
        AutoTest.shot(5, "deck_09_hangar_to_the_shuttle");
        AutoTest.step(5, DeckAutoTest::click);
        AutoTest.await(1, 60, () -> Minecraft.getInstance().player.getVehicle() != null && Minecraft.getInstance().player.getVehicle().getId() == shuttleId);
        AutoTest.step(10, () -> {
            var v = Minecraft.getInstance().player.getVehicle();
            var s = client(shuttleId);
            log(v != null && v.getId() == shuttleId && s instanceof Starship st && st.isDocked(), "walked into the hangar and boarded the docked shuttle",
                    String.format(Locale.ROOT, "aboard %s, the shuttle docked %s", v == null ? "nothing" : "#" + v.getId(), s instanceof Starship st && st.isDocked()));
        });
        AutoTest.camera(5, CameraType.THIRD_PERSON_BACK);
        AutoTest.shot(20, "deck_10_aboard_the_docked_shuttle");
        AutoTest.camera(5, CameraType.FIRST_PERSON);
        AutoTest.server(10, "clear the hangar test", sp -> {
            sp.stopRiding();
            for (int id : new int[] {shuttleId, freighterId}) {
                Entity e = sp.level().getEntity(id);
                if (e != null) e.discard();
            }
        });
    }

    // ------------------------------------------------------------------ 4. two ships colliding

    private static void collision() {
        AutoTest.cmd(10, "galactica goto Sol");
        AutoTest.server(10, "a shuttle and an explorer on a collision course", sp -> {
            Vec3 c = sp.position().add(0, 0, 40);
            ShipStructure a = ShipTemplates.spawn(sp.level(), "shuttle", c.add(-30, 0, 0), Direction.SOUTH);
            ShipStructure b = ShipTemplates.spawn(sp.level(), "explorer", c.add(30, 0, 0), Direction.SOUTH);
            if (a == null || b == null) return;
            shuttleId = a.getId();
            explorerId = b.getId();
            a.setDeltaMovement(new Vec3(0.75, 0, 0));
            b.setDeltaMovement(new Vec3(-0.25, 0, 0));
            hullShuttle = a.hull();
            hullExplorer = b.hull();
        });
        AutoTest.step(10, () -> {
            var p = Minecraft.getInstance().player;
            lookAt(p.position().add(0, 0, 40));
        });
        AutoTest.await(5, 200, () -> client(shuttleId) != null && client(explorerId) != null
                && client(explorerId).getX() - client(shuttleId).getX() < 14);
        AutoTest.shot(1, "deck_11_collision_impact");
        AutoTest.server(40, "the collision", sp -> {
            ShipStructure a = serverShip(sp, shuttleId), b = serverShip(sp, explorerId);
            if (a == null || b == null) {
                log(false, "ship-to-ship collision", "ships gone");
                return;
            }
            double va = a.getDeltaMovement().x * 20, vb = b.getDeltaMovement().x * 20;
            double ma = a.spec().massKg(), mb = b.spec().massKg();
            double p0 = ma * 15 - mb * 5, p1 = ma * va + mb * vb;
            var c = dev.galactica.ship.collide.VoxelContact.find(dev.galactica.ship.collide.ShipCollisions.posed(a), dev.galactica.ship.collide.ShipCollisions.posed(b));
            boolean ok = a.impacts > 0 && a.getX() < b.getX() && va < 10 && a.hull() < hullShuttle && b.hull() < hullExplorer
                    && Math.abs(p1 - p0) < 0.1 * ma * 15 && (c == null || c.depth() < 1.0);
            log(ok, "ship-to-ship collision", String.format(Locale.ROOT,
                    "%d impacts; shuttle 15 -> %.1f m/s, explorer -5 -> %.1f m/s (momentum %.0f -> %.0f t m/s); hull %.0f -> %.0f and %.0f -> %.0f; "
                            + "still apart %s (overlap %s)", a.impacts, va, vb, p0 / 1000, p1 / 1000, hullShuttle, a.hull(), hullExplorer, b.hull(),
                    a.getX() < b.getX(), c == null ? "none" : String.format(Locale.ROOT, "%.2f", c.depth())));
        });
        AutoTest.shot(5, "deck_12_collision_after");
        AutoTest.server(10, "remove the collided ships", sp -> {
            for (int id : new int[] {shuttleId, explorerId}) {
                Entity e = sp.level().getEntity(id);
                if (e != null) e.discard();
            }
        });
    }
}
