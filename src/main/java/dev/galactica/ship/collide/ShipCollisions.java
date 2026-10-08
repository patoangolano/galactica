package dev.galactica.ship.collide;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.deck.ShipPose;
import dev.galactica.ship.yard.ShipOccupancy;
import dev.galactica.ship.yard.ShipStats;
import dev.galactica.ship.yard.ShipTemplate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Ship-to-ship collision. Every craft (built ships and the Kestrel, whose voxels come from its block template) is a
 * set of cells at a pose ({@link VoxelContact.Posed}).
 *
 * <ul>
 *   <li><b>Gentle contact</b> (closing slower than {@link #RESTING_SPEED}): to the side that moves a craft (the pilot's
 *       client, or the server for an unpiloted one) other craft are terrain. {@code Starship.hullClearAt} includes
 *       {@link #clearOfShips}, so the existing contact machinery (swept probes, touchdown, skid friction, the gear hold)
 *       lands a ship on another's deck and keeps it parked there, with no impulses at all.</li>
 *   <li><b>Impacts</b> are resolved by the server alone, at the end of every server tick: a broad phase on distance, a
 *       swept narrow phase (each pair's move this tick cut so no hull point travels more than {@link #SWEEP_STEP}
 *       blocks between voxel tests, so a fast ship cannot pass through a thin hull), then an impulse with restitution
 *       and friction through both crafts' mass and inertia ({@link RigidImpulse}), a positional push out of the overlap,
 *       a spin, and hull damage from the impact energy above {@link RigidImpulse#DAMAGE_SPEED}. A piloted craft's
 *       pilot gets the change as a {@link ShipImpulsePayload}; until it is acknowledged, the server adds it to that
 *       pilot's older reports and moves, so the {@code DeltaVBudget} clamp sees no velocity it did not grant and
 *       nothing is applied twice. A client that ignores impulses gains nothing: its reports are clamped by the same
 *       budget, and a hull that stays deep inside another is put back by the server ({@link #FORCE_DEPTH}).</li>
 * </ul>
 * Craft docked to each other (one rides the other) never collide with each other.
 */
public final class ShipCollisions {
    private ShipCollisions() {}

    /** Closing speed below which craft are each other's terrain (no impulse), m/s. */
    public static final double RESTING_SPEED = 3.0;
    /** Largest hull-point travel between two voxel tests of a sweep, blocks. */
    public static final double SWEEP_STEP = 0.5;
    public static final int MAX_SUBSTEPS = 24;
    /** Restitution of an impact, and Coulomb friction between hulls. */
    public static final double RESTITUTION = 0.35, FRICTION = 0.4;
    /** Overlap left alone (resting hulls touch this deep through the sampling), blocks. */
    public static final double SLOP = 0.5;
    /** A pushed hull moves at most this far per tick, blocks. */
    public static final double MAX_PUSH = 2.0;
    /** A piloted hull this deep in another is put back by the server whatever its client says, blocks. */
    public static final double FORCE_DEPTH = 2.5;
    /** Most spin an impact gives, rad/s. */
    public static final double MAX_SPIN = 1.0;
    /** An unacknowledged impulse stops being added to a pilot's reports after this, ticks. */
    private static final int ACK_TIMEOUT = 100;
    private static final boolean AUTOTEST = Boolean.getBoolean("galactica.autotest");

    /** Dev counters: impacts resolved and hull points taken. */
    public static int impacts;
    public static double damageDealt;

    // ------------------------------------------------------------------ registry

    private static final Map<Level, List<Starship>> CRAFT = new WeakHashMap<>();

    /** Records a live craft of its level (called from its tick). */
    public static void track(Starship s) {
        synchronized (CRAFT) {
            List<Starship> list = CRAFT.computeIfAbsent(s.level(), l -> new ArrayList<>());
            if (!list.contains(s)) list.add(s);
        }
    }

    /** The live craft of a level (a copy). */
    public static List<Starship> craft(Level level) {
        synchronized (CRAFT) {
            List<Starship> list = CRAFT.get(level);
            if (list == null || list.isEmpty()) return List.of();
            list.removeIf(s -> s.isRemoved() || s.level() != level);
            return new ArrayList<>(list);
        }
    }

    // ------------------------------------------------------------------ craft as voxels

    private static volatile @Nullable ShipOccupancy kestrelGrid;
    private static volatile @Nullable Vector3dc kestrelCom;

    /** The craft's cells: a built ship's own, the Kestrel's from its block template. Null before a built ship's blocks arrive. */
    public static @Nullable ShipOccupancy grid(Starship s) {
        if (s instanceof ShipStructure b) return b.grid();
        if (kestrelGrid == null) {
            var cells = ShipTemplate.kestrel().cells();
            kestrelCom = ShipStats.compute(cells).com();
            kestrelGrid = new ShipOccupancy(cells);
        }
        return kestrelGrid;
    }

    /** Where the craft's pivot sits in its cells. */
    public static Vector3dc com(Starship s) {
        if (s instanceof ShipStructure b) return b.centreOfMass();
        grid(s);
        return kestrelCom;
    }

    public static VoxelContact.@Nullable Posed posed(Starship s, Vec3 origin, Quaternionfc q) {
        ShipOccupancy g = grid(s);
        if (g == null || g.size() == 0) return null;
        return new VoxelContact.Posed(g, new ShipPose(new Vector3d(origin.x, origin.y + s.pivot(), origin.z),
                new Quaterniond(q.x(), q.y(), q.z(), q.w()), com(s)));
    }

    public static VoxelContact.@Nullable Posed posed(Starship s) {
        return posed(s, s.position(), s.attitude());
    }

    private static VoxelContact.@Nullable Posed prevPosed(Starship s) {
        return posed(s, new Vec3(s.xo, s.yo, s.zo), s.attitudeO());
    }

    /** The farthest hull point from the pivot, blocks, plus a margin. */
    public static double reach(Starship s) {
        return s.spec().hullReach() + 1.5;
    }

    private static boolean related(Starship a, Starship b) {
        return a == b || a.getRootVehicle() == b.getRootVehicle();
    }

    // ------------------------------------------------------------------ gentle contact: other craft as terrain

    /**
     * True if {@code self} at {@code origin} and attitude {@code q} overlaps no other craft that closes on it slower than
     * {@link #RESTING_SPEED} (faster ones are the server's impact business).
     */
    public static boolean clearOfShips(Starship self, Level level, Vec3 origin, Quaternionfc q) {
        List<Starship> all = craft(level);
        if (all.size() < 2) return true;
        VoxelContact.Posed me = null;
        double myReach = reach(self);
        for (Starship o : all) {
            if (related(self, o) || o.isRemoved()) continue;
            double r = myReach + reach(o);
            if (o.position().add(0, o.pivot(), 0).distanceToSqr(origin.add(0, self.pivot(), 0)) > r * r) continue;
            double closing = self.getDeltaMovement().subtract(o.getDeltaMovement()).length() / dev.galactica.ship.ShipFlightModel.TICK;
            if (closing > RESTING_SPEED) continue;
            if (me == null) {
                me = posed(self, origin, q);
                if (me == null) return true;
            }
            VoxelContact.Posed other = posed(o);
            if (other != null && VoxelContact.overlaps(me, other)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ impacts (server)

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(ShipImpulsePayload.TYPE, ShipImpulsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShipImpulsePayload.Ack.TYPE, ShipImpulsePayload.Ack.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ShipImpulsePayload.Ack.TYPE, (msg, ctx) -> {
            if (ctx.player().level().getEntity(msg.entityId()) instanceof Starship s) acknowledge(s, msg.id());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerLevel level : server.getAllLevels()) tick(level);
        });
    }

    /** Server, end of tick: finds and resolves every impact between root craft of the level. */
    public static void tick(ServerLevel level) {
        List<Starship> all = craft(level);
        if (all.size() < 2) return;
        List<Starship> roots = new ArrayList<>(all.size());
        for (Starship s : all) if (!s.isPassenger() && grid(s) != null && grid(s).size() > 0) roots.add(s);
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                Starship a = roots.get(i), b = roots.get(j);
                if (related(a, b)) continue;
                double r = reach(a) + reach(b) + a.getDeltaMovement().length() + b.getDeltaMovement().length() + 1;
                if (a.position().add(0, a.pivot(), 0).distanceToSqr(b.position().add(0, b.pivot(), 0)) > r * r) continue;
                collide(level, a, b);
            }
        }
    }

    /** The swept narrow phase and the response for one pair. */
    static void collide(ServerLevel level, Starship a, Starship b) {
        VoxelContact.Posed a0 = prevPosed(a), a1 = posed(a), b0 = prevPosed(b), b1 = posed(b);
        if (a0 == null || a1 == null || b0 == null || b1 == null) return;
        double travel = a0.pose().maxTravel(a1.pose(), reach(a)) + b0.pose().maxTravel(b1.pose(), reach(b));
        int n = (int) Math.clamp(Math.ceil(travel / SWEEP_STEP), 1, MAX_SUBSTEPS);
        VoxelContact.Contact contact = null;
        int hit = -1;
        for (int k = 1; k <= n && contact == null; k++) {
            double t = (double) k / n;
            VoxelContact.Posed pa = k == n ? a1 : new VoxelContact.Posed(a1.grid(), a0.pose().lerp(a1.pose(), t));
            VoxelContact.Posed pb = k == n ? b1 : new VoxelContact.Posed(b1.grid(), b0.pose().lerp(b1.pose(), t));
            contact = VoxelContact.find(pa, pb);
            if (contact != null) hit = k;
        }
        if (contact == null) return;
        double tPrev = (double) (hit - 1) / n;
        respond(level, a, b, contact, hit < n ? tPrev : 1.0, a0, a1, b0, b1);
    }

    private static void respond(ServerLevel level, Starship a, Starship b, VoxelContact.Contact c, double rewindTo,
                                VoxelContact.Posed a0, VoxelContact.Posed a1, VoxelContact.Posed b0, VoxelContact.Posed b1) {
        double tick = dev.galactica.ship.ShipFlightModel.TICK;
        Vector3d va = toVec(a.getDeltaMovement()).div(tick), vb = toVec(b.getDeltaMovement()).div(tick);
        Vector3d wa = new Vector3d(a.spin()), wb = new Vector3d(b.spin());
        double ma = a.spec().massKg(), mb = b.spec().massKg();
        RigidImpulse.Body ba = RigidImpulse.Body.of(ma, a.spec().inertia(), a1.pose().attitude(), a1.pose().pivot(), va, wa);
        RigidImpulse.Body bb = RigidImpulse.Body.of(mb, b.spec().inertia(), b1.pose().attitude(), b1.pose().pivot(), vb, wb);
        double closingBefore = -ba.pointVelocity(c.point()).sub(bb.pointVelocity(c.point())).dot(c.normal());
        if (closingBefore < 1.0 && c.depth() <= SLOP) return; // resting: the movers' own contact handles it
        double e = closingBefore > 2.0 ? RESTITUTION : 0.0;
        RigidImpulse.Result res = RigidImpulse.resolve(ba, bb, c.point(), c.normal(), e, FRICTION);
        // Positional: back to just before the contact this tick (a fast hit), then out of any overlap left, shared by
        // inverse mass, at most MAX_PUSH a tick.
        double ia = 1 / ma, ib = 1 / mb, share = ia + ib;
        Vector3d nudgeA = new Vector3d(), nudgeB = new Vector3d();
        if (rewindTo < 1.0) {
            nudgeA.set(a0.pose().pivot()).lerp(a1.pose().pivot(), rewindTo).sub(a1.pose().pivot());
            nudgeB.set(b0.pose().pivot()).lerp(b1.pose().pivot(), rewindTo).sub(b1.pose().pivot());
        }
        double push = Math.min(MAX_PUSH, Math.max(0, c.depth() - SLOP * 0.5));
        if (push > 0) {
            nudgeA.fma(push * ia / share, c.normal());
            nudgeB.fma(-push * ib / share, c.normal());
        }
        Vector3d dvA = new Vector3d(ba.velocity).sub(va), dvB = new Vector3d(bb.velocity).sub(vb);
        Vector3d dwA = clampSpin(new Vector3d(ba.omega).sub(wa)), dwB = clampSpin(new Vector3d(bb.omega).sub(wb));
        apply(a, dvA, nudgeA, dwA);
        apply(b, dvB, nudgeB, dwB);
        double closing = res == null ? 0 : res.closingSpeed();
        double damage = RigidImpulse.hullDamage(ma, mb, closing);
        impacts++;
        if (damage > 0) {
            damageDealt += 2 * damage;
            a.collisionDamage(level, (float) damage);
            b.collisionDamage(level, (float) damage);
            for (Starship s : new Starship[] {a, b}) {
                for (Entity rider : s.getIndirectPassengers()) {
                    if (rider instanceof ServerPlayer p)
                        p.sendOverlayMessage(Component.translatable("hud.galactica.ship.collision", String.format(Locale.ROOT, "%.0f", damage)));
                }
            }
        }
        Galactica.LOG.info("{}ship collision {} x {}: closing {} m/s, normal ({}), depth {}, contacts {}, dv {} / {} m/s, push {}, damage {} each",
                AUTOTEST ? "[autotest] " : "", a.getId(), b.getId(), fmt(closing > 0 ? closing : closingBefore),
                fmt(c.normal().x) + ", " + fmt(c.normal().y) + ", " + fmt(c.normal().z), fmt(c.depth()), c.count(),
                fmt(dvA.length()), fmt(dvB.length()), fmt(push), fmt(damage));
    }

    private static Vector3d clampSpin(Vector3d w) {
        double l = w.length();
        return l > MAX_SPIN ? w.mul(MAX_SPIN / l) : w;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static Vector3d toVec(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------ applying a response

    /** An impulse a pilot has been sent and not acknowledged yet. */
    record Pending(int id, Vector3d dv, Vector3d nudge, int sentTick) {}

    private static final Map<Starship, ArrayDeque<Pending>> PENDING = new WeakHashMap<>();
    private static int nextId = 1;

    /** Server: changes a craft's velocity (m/s), position (blocks) and spin (rad/s); tells its pilot. */
    public static void apply(Starship s, Vector3d dv, Vector3d nudge, Vector3d dw) {
        double tick = dev.galactica.ship.ShipFlightModel.TICK;
        s.setDeltaMovement(s.getDeltaMovement().add(dv.x * tick, dv.y * tick, dv.z * tick));
        if (nudge.lengthSquared() > 0) s.setPos(s.getX() + nudge.x, s.getY() + nudge.y, s.getZ() + nudge.z);
        s.addSpin(dw);
        if (s.getControllingPassenger() instanceof ServerPlayer pilot) {
            int id = nextId++;
            synchronized (PENDING) {
                PENDING.computeIfAbsent(s, k -> new ArrayDeque<>()).addLast(new Pending(id, new Vector3d(dv), new Vector3d(nudge), s.tickCount));
            }
            ServerPlayNetworking.send(pilot, new ShipImpulsePayload(s.getId(), id, new Vector3f((float) dv.x, (float) dv.y, (float) dv.z),
                    new Vector3f((float) nudge.x, (float) nudge.y, (float) nudge.z), new Vector3f((float) dw.x, (float) dw.y, (float) dw.z)));
        }
        if (s.level() instanceof ServerLevel sl) dev.galactica.ship.Starship.seatRiders(s, sl);
    }

    private static void acknowledge(Starship s, int id) {
        synchronized (PENDING) {
            ArrayDeque<Pending> q = PENDING.get(s);
            if (q == null) return;
            while (!q.isEmpty() && q.peekFirst().id() <= id) q.removeFirst();
        }
    }

    /** Velocity (m/s) the craft's pilot has not acknowledged yet: their reports sent before it lack it. */
    public static Vector3d pendingVelocity(Starship s) {
        Vector3d sum = new Vector3d();
        synchronized (PENDING) {
            ArrayDeque<Pending> q = PENDING.get(s);
            if (q == null) return sum;
            q.removeIf(p -> s.tickCount - p.sentTick() > ACK_TIMEOUT);
            for (Pending p : q) sum.add(p.dv());
        }
        return sum;
    }

    /** Position change (blocks) the pilot of {@code player}'s craft has not acknowledged: their moves sent before it lack it. */
    public static @Nullable Vec3 pendingNudge(ServerPlayer player) {
        if (!(player.getVehicle() instanceof Starship s) || s.getControllingPassenger() != player) return null;
        Vector3d sum = new Vector3d();
        synchronized (PENDING) {
            ArrayDeque<Pending> q = PENDING.get(s);
            if (q == null || q.isEmpty()) return null;
            for (Pending p : q) sum.add(p.nudge());
        }
        return sum.lengthSquared() == 0 ? null : new Vec3(sum.x, sum.y, sum.z);
    }

    /**
     * Server: a piloted hull left deep inside another (a client that ignored the push) is set back out by the server
     * through vanilla's vehicle correction. Called after the pilot's move.
     */
    public static void enforce(ServerPlayer pilot, Starship s) {
        if (!(s.level() instanceof ServerLevel level)) return;
        VoxelContact.Posed me = posed(s);
        if (me == null) return;
        for (Starship o : craft(level)) {
            if (related(s, o) || o.isPassenger()) continue;
            double r = reach(s) + reach(o);
            if (o.position().distanceToSqr(s.position()) > r * r) continue;
            VoxelContact.Posed other = posed(o);
            if (other == null) continue;
            VoxelContact.Contact c = VoxelContact.find(me, other);
            if (c == null || c.depth() < FORCE_DEPTH) continue;
            s.setPos(s.position().add(c.normal().x * c.depth(), c.normal().y * c.depth(), c.normal().z * c.depth()));
            pilot.connection.send(net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket.fromEntity(s));
            Galactica.LOG.info("Ship {} was {} blocks inside ship {}: set back out", s.getId(), fmt(c.depth()), o.getId());
            return;
        }
    }
}
