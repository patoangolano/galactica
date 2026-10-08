package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.ship.yard.ShipSystems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Docking and hangars: "a ship is a passenger of a ship" (docs/recon/ship_ecosystem_26_3.md §3.3.6). The lighter craft
 * rides the heavier one as a vanilla passenger at a fixed pose in its body frame ({@link Starship#dockOffset},
 * {@link Starship#dockRotation}, synced), so everything that already carries a root vehicle with all its indirect
 * passengers carries docked craft too: floating-origin recentres ({@code FloatingOrigin.apply}), planet entry and exit
 * (the vanilla cross-dimension teleport re-mounts passengers, and the pose is in the saved data it copies), the
 * orbital frame and the hyperjump ({@code Starship.carryCrew} and {@code Hyperdrive.complete} walk the whole tree).
 *
 * <ul>
 *   <li><b>Docking port to docking port</b>: the pilot of either ship presses the dock key (G) with both ports'
 *       mating faces within {@link #PORT_RANGE} blocks and the ships within {@link #MAX_RELATIVE_SPEED} m/s of each
 *       other; the lighter ship is pulled the last metres so the faces meet, and rides.</li>
 *   <li><b>Hangar</b>: a lighter craft (a Kestrel too) whose pivot drifts into a built ship's hangar volume
 *       ({@link ShipSystems.Hangar}) slower than {@link #MAX_RELATIVE_SPEED} is clamped onto the bay floor, level
 *       with the carrier, and carried.</li>
 *   <li><b>Undocking</b>: the dock key again; the craft leaves with its carrier's velocity plus a gentle push clear
 *       ({@link #releasePush}) and is not caught by a hangar for {@link #RECAPTURE_TICKS} ticks.</li>
 * </ul>
 * A docked craft does not fly or report; its pilot stays seated and is the carrier's crew for the frame. Its crew can
 * still step out (sneak) inside a hangar.
 */
public final class ShipDocking {
    private ShipDocking() {}

    /**
     * How a craft is held: at a docking port, in a hangar, or parked on another craft's top ({@link #DECK}: it settled
     * there and stays, carried, until its pilot lifts off or presses the dock key).
     */
    public enum Kind { NONE, PORT, HANGAR, DECK }

    /** A craft resting on another's top this many server ticks, slower than {@link #PARK_SPEED}, is parked on it. */
    public static final int PARK_TICKS = 40;
    /** Relative speed below which a craft resting on another counts as parked, m/s. */
    public static final double PARK_SPEED = 0.5;

    /** Largest gap between two ports' mating faces that still docks, blocks. */
    public static final double PORT_RANGE = 4.0;
    /** Largest relative speed for docking or a hangar capture, m/s. */
    public static final double MAX_RELATIVE_SPEED = 4.0;
    /** A released craft is left alone by hangars for this long, ticks (5 s to fly out). */
    public static final int RECAPTURE_TICKS = 100;
    /** Undocking push, m/s. */
    static final double PUSH = 1.5;
    private static final boolean AUTOTEST = Boolean.getBoolean("galactica.autotest");

    // ------------------------------------------------------------------ frames

    /** The craft's pivot in the world. */
    public static Vector3d pivotWorld(Starship s) {
        return new Vector3d(s.getX(), s.getY() + s.pivot(), s.getZ());
    }

    /** A world point in {@code s}'s body frame (blocks from its pivot). */
    public static Vector3d toBody(Starship s, Vector3d world) {
        Vector3d d = new Vector3d(world).sub(pivotWorld(s));
        return new Quaternionf(s.attitude()).conjugate().transform(d);
    }

    /** A body-frame point of {@code s} in the world. */
    public static Vector3d toWorld(Starship s, Vector3d body) {
        return new Quaternionf(s.attitude()).transform(new Vector3d(body)).add(pivotWorld(s));
    }

    /** A port's mating face in the world. */
    public static Vector3d portWorld(ShipStructure s, ShipSystems.Port port) {
        double[] f = port.faceCentre();
        var com = s.centreOfMass();
        return toWorld(s, new Vector3d(f[0] - com.x(), f[1] - com.y(), f[2] - com.z()));
    }

    // ------------------------------------------------------------------ dock and undock

    /**
     * Mounts {@code child} on {@code carrier} at its current pose relative to it (shifted by {@code nudge}, world
     * blocks, first), recorded as {@code kind}. Server only.
     */
    public static boolean dock(Starship carrier, Starship child, Kind kind, Vec3 nudge) {
        if (carrier.level().isClientSide() || child == carrier || child.isPassenger() || carrier.getRootVehicle() == child) return false;
        if (child.getRootVehicle() == carrier.getRootVehicle()) return false;
        child.setPos(child.position().add(nudge));
        Vector3d off = toBody(carrier, pivotWorld(child));
        Quaternionf rot = new Quaternionf(carrier.attitude()).conjugate().mul(child.attitude()).normalize();
        return dockAt(carrier, child, kind, new Vector3f((float) off.x, (float) off.y, (float) off.z), rot);
    }

    /** Mounts {@code child} on {@code carrier} at an explicit pose (body frame of the carrier). Server only. */
    public static boolean dockAt(Starship carrier, Starship child, Kind kind, Vector3fc offset, Quaternionfc rotation) {
        child.setDockPose(kind, offset, rotation);
        if (!child.startRiding(carrier, true, true)) return false;
        carrier.positionRider(child);
        for (Entity e : child.getPassengers()) child.positionRider(e);
        Galactica.LOG.info("{}ship {} docked to ship {} ({}) at {}", AUTOTEST ? "[autotest] " : "", child.getId(), carrier.getId(),
                kind.name().toLowerCase(Locale.ROOT), offset);
        return true;
    }

    /** Releases a docked craft (server). True if it was docked. */
    public static boolean undock(Starship child) {
        if (!(child.getVehicle() instanceof Starship carrier)) return false;
        child.stopRiding();
        child.clearDockPose();
        Galactica.LOG.info("{}ship {} undocked from ship {}", AUTOTEST ? "[autotest] " : "", child.getId(), carrier.getId());
        return true;
    }

    /**
     * Velocity (blocks/tick) a craft leaving {@code carrier} gets on top of the carrier's: out of a hangar, straight up
     * the carrier's dorsal axis; from a port, away from the carrier's pivot. The same on every side: it reads only
     * synced state.
     */
    public static Vec3 releasePush(Starship carrier, Starship child) {
        Vector3f dir;
        if (child.dockKind() == Kind.HANGAR || child.dockKind() == Kind.DECK) {
            dir = new Vector3f(0, 1, 0);
        } else {
            dir = new Vector3f(child.dockOffset());
            if (dir.lengthSquared() < 1e-6) dir.set(0, 1, 0);
            dir.normalize();
        }
        new Quaternionf(carrier.attitude()).transform(dir);
        double k = PUSH * ShipFlightModel.TICK;
        return new Vec3(dir.x * k, dir.y * k, dir.z * k);
    }

    // ------------------------------------------------------------------ the pilot's dock key

    /** The dock key from {@code player}: undocks their craft, or docks it port to port with the best match in range. */
    public static void toggle(ServerPlayer player) {
        if (!(player.getVehicle() instanceof Starship ship) || ship.getControllingPassenger() != player) {
            Galactica.LOG.info("Dock key from {} ignored: not at a ship's helm (vehicle {})", player.getName().getString(),
                    player.getVehicle() == null ? "none" : player.getVehicle().getId());
            return;
        }
        if (ship.isDocked()) {
            undock(ship);
            player.sendOverlayMessage(Component.translatable("hud.galactica.dock.released"));
            return;
        }
        // The carrier's pilot releases whatever it carries (port and hangar alike).
        if (!ship.dockedCraft().isEmpty()) {
            for (Starship child : ship.dockedCraft()) undock(child);
            player.sendOverlayMessage(Component.translatable("hud.galactica.dock.released"));
            return;
        }
        Match m = bestPortMatch(ship);
        Galactica.LOG.info("Dock key from {} in ship {}: {}", player.getName().getString(), ship.getId(), m == null ? "no port in range"
                : String.format(Locale.ROOT, "ship %d carries %d, gap %.2f, closing %.2f m/s", m.carrier.getId(), m.child.getId(), m.gap, m.speed));
        if (m == null) {
            player.sendOverlayMessage(Component.translatable("hud.galactica.dock.no_port", (int) PORT_RANGE));
            return;
        }
        if (m.speed > MAX_RELATIVE_SPEED) {
            player.sendOverlayMessage(Component.translatable("hud.galactica.dock.too_fast",
                    String.format(Locale.ROOT, "%.1f", m.speed), (int) MAX_RELATIVE_SPEED));
            return;
        }
        if (dock(m.carrier, m.child, Kind.PORT, m.nudge)) {
            player.sendOverlayMessage(Component.translatable("hud.galactica.dock.docked", m.carrier.getDisplayName()));
        }
    }

    /** The closest port pair between {@code ship} and another built ship, with who carries whom. */
    record Match(Starship carrier, Starship child, Vec3 nudge, double gap, double speed) {}

    static @Nullable Match bestPortMatch(Starship ship) {
        if (!(ship instanceof ShipStructure self) || self.systems().ports().isEmpty()) return null;
        double reach = self.spec().hullReach() + 64;
        AABB box = self.getBoundingBox().inflate(reach);
        Match best = null;
        for (ShipStructure other : self.level().getEntitiesOfClass(ShipStructure.class, box,
                o -> o != self && !o.isPassenger() && o.getRootVehicle() != self && !o.systems().ports().isEmpty())) {
            for (ShipSystems.Port a : self.systems().ports()) {
                Vector3d pa = portWorld(self, a);
                for (ShipSystems.Port b : other.systems().ports()) {
                    Vector3d pb = portWorld(other, b);
                    double gap = pa.distance(pb);
                    if (gap > PORT_RANGE || best != null && gap >= best.gap) continue;
                    boolean selfCarries = self.spec().massKg() > other.spec().massKg();
                    Starship carrier = selfCarries ? self : other, child = selfCarries ? other : self;
                    // The lighter ship moves the last metres so its port meets the carrier's.
                    Vector3d delta = selfCarries ? new Vector3d(pa).sub(pb) : new Vector3d(pb).sub(pa);
                    double speed = carrier.getDeltaMovement().subtract(child.getDeltaMovement()).length() / ShipFlightModel.TICK;
                    best = new Match(carrier, child, new Vec3(delta.x, delta.y, delta.z), gap, speed);
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ parked on another craft's top

    /**
     * Server, every tick for a free craft: one that has rested on top of a heavier craft ({@link
     * dev.galactica.ship.collide.ShipCollisions#supporter}) for {@link #PARK_TICKS} ticks, at rest relative to it and
     * with its pilot (if any) not thrusting, is parked there: it rides the other craft at its pose, so it stays put
     * whatever the carrier does (turns, climbs, recentres, jumps).
     */
    public static void parkOnTop(Starship ship) {
        if (ship.level().isClientSide() || ship.isPassenger() || ship.tickCount - ship.undockedTick < RECAPTURE_TICKS) {
            ship.parkedTicks = 0;
            return;
        }
        Starship under = dev.galactica.ship.collide.ShipCollisions.supporter(ship);
        boolean still = under != null && under.spec().massKg() > ship.spec().massKg()
                && ship.getDeltaMovement().subtract(under.getDeltaMovement()).length() / ShipFlightModel.TICK < PARK_SPEED;
        if (still && ship.getControllingPassenger() instanceof ServerPlayer pilot) {
            var in = pilot.getLastClientInput();
            if (in.forward() || in.backward() || in.jump() || in.left() || in.right()) still = false;
        }
        if (!still) {
            ship.parkedTicks = 0;
            return;
        }
        if (++ship.parkedTicks < PARK_TICKS) return;
        ship.parkedTicks = 0;
        if (dock(under, ship, Kind.DECK, Vec3.ZERO) && ship.getControllingPassenger() instanceof ServerPlayer p)
            p.sendOverlayMessage(Component.translatable("hud.galactica.dock.parked", under.getDisplayName()));
    }

    /** Server: a craft parked on another's top lifts off as soon as its pilot asks for lift or thrust. */
    public static void liftOffIfAsked(Starship child, ServerPlayer pilot) {
        if (child.dockKind() != Kind.DECK) return;
        var in = pilot.getLastClientInput();
        if (in.jump() || in.forward() || in.backward()) undock(child);
    }

    // ------------------------------------------------------------------ hangars

    /**
     * Server, every few ticks for a ship with a hangar: clamps any lighter craft whose pivot is inside the hangar
     * volume and that moves with the carrier (within {@link #MAX_RELATIVE_SPEED}) onto the bay floor.
     */
    public static void captureIntoHangar(Starship carrierShip) {
        if (!(carrierShip instanceof ShipStructure carrier) || carrier.level().isClientSide()) return;
        ShipSystems.Hangar h = carrier.systems().hangar();
        if (h == null) return;
        AABB box = carrier.getBoundingBox().inflate(carrier.spec().hullReach() + 2);
        for (Starship child : carrier.level().getEntitiesOfClass(Starship.class, box,
                c -> c != carrier && !c.isPassenger() && c.getRootVehicle() != carrier && !c.isRemoved())) {
            if (child.spec().massKg() >= carrier.spec().massKg()) continue;
            if (child.tickCount - child.undockedTick < RECAPTURE_TICKS) continue;
            var com = carrier.centreOfMass();
            Vector3d b = toBody(carrier, pivotWorld(child)).add(com.x(), com.y(), com.z()); // cell coordinates
            double bottom = b.y - child.pivot();
            boolean inside = b.x >= h.minX() && b.x < h.maxX() + 1 && b.z >= h.minZ() && b.z < h.maxZ() + 1
                    && bottom > h.floorY() + 0.5 && bottom < h.topY() + 1;
            if (!inside) continue;
            double speed = carrier.getDeltaMovement().subtract(child.getDeltaMovement()).length() / ShipFlightModel.TICK;
            if (speed > MAX_RELATIVE_SPEED) continue;
            // Level on the floor: keep the craft's heading relative to the carrier, drop pitch and roll.
            Quaternionf rel = new Quaternionf(carrier.attitude()).conjugate().mul(child.attitude());
            Vector3f nose = rel.transform(new Vector3f(0, 0, 1));
            float yaw = (float) Math.atan2(nose.x, nose.z);
            Quaternionf level = new Quaternionf().rotationY(yaw);
            double x = Math.clamp(b.x, h.minX() + 0.5, h.maxX() + 0.5), z = Math.clamp(b.z, h.minZ() + 0.5, h.maxZ() + 0.5);
            Vector3f off = new Vector3f((float) (x - com.x()), (float) (h.floorY() + 1 - com.y() + child.pivot()), (float) (z - com.z()));
            if (dockAt(carrier, child, Kind.HANGAR, off, level)) {
                if (child.getControllingPassenger() instanceof ServerPlayer p)
                    p.sendOverlayMessage(Component.translatable("hud.galactica.dock.hangar", carrier.getDisplayName()));
            }
        }
    }
}
