package dev.galactica.ship.deck;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipCargo;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.yard.ShipBlocks;
import dev.galactica.ship.yard.ShipCell;
import dev.galactica.ship.yard.ShipPart;
import dev.galactica.ship.yard.ShipPartBlocks;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Walking on a block-built ship (milestone S7): a body standing on, or inside, a {@link ShipStructure} rides it as a
 * vanilla passenger in <em>walker</em> mode instead of a seat. Everything that already carries a craft with all its
 * riders (floating-origin recentres, the orbital frame, warp, the hyperjump, planet entry and exit, docking, tracking)
 * therefore carries the walker too, at any speed.
 *
 * <p>The walker's position lives in the ship's frame ({@link DeckPos}, cell coordinates) and is simulated there
 * ({@link DeckWalker}): walking, jumping, steps, gravity along the deck, and collision against the ship's own block
 * shapes, so neither the ship's speed nor its turn ever enters the collision. The world position is the ship's pose
 * applied to it, every tick, on every side alike. A landed ship's walkers also collide with the terrain, and step off
 * onto it.
 *
 * <p><b>Authority.</b> A player's own client simulates their walk (like vanilla walking) and reports the deck
 * position ({@link DeckPayloads.Move}); the server bounds the step and the volume, stores it in the synced attachment,
 * and every client places the walker on its own view of the ship from it, so nobody sees a walker lag behind a
 * 285 m/s hull. Items (and mock players in GameTests) are simulated by the server.
 *
 * <p><b>Gravity on deck:</b> the planet's own gravity when landed or in an atmosphere (stuck to the deck while it
 * tilts less than {@link #STICK_ANGLE_DEG}, sliding beyond); in space one ship-down g with a powered
 * {@link ShipPart#GRAVITY_GENERATOR}, otherwise weightless, unless the walker wears EVA boots with their magnets on.
 */
public final class Deck {
    private Deck() {}

    public static final AttachmentType<DeckPos> DECK = AttachmentRegistry.create(Galactica.id("deck"),
            b -> b.persistent(DeckPos.CODEC).syncWith(DeckPos.STREAM_CODEC, AttachmentSyncPredicate.all()));

    /** The deck still holds a walker while it tilts less than this from level; beyond, they slide (planets). */
    public static final double STICK_ANGLE_DEG = 50;
    /** Vanilla gravity of players and mobs, and of items, blocks per tick². */
    public static final double LIVING_G = 0.08, ITEM_G = 0.04;
    /** Most walkers one ship carries. */
    public static final int MAX_WALKERS = 64;
    /** Largest deck step a client may report in one tick, cells (a fall at terminal speed is ~3.9). */
    public static final double MAX_REPORTED_STEP = 4.5;
    /** Reach for using a ship block (helm, seat, container) from the deck, blocks. */
    public static final double USE_REACH = 5.0;
    private static final boolean AUTOTEST = Boolean.getBoolean("galactica.autotest");

    // ------------------------------------------------------------------ side hooks

    /** Client: whether this client simulates the entity's walk (its own player). Set by the client half. */
    public static Predicate<Entity> clientSimulates = e -> false;
    /** Client: the local player's walking intent. Set by the client half. */
    public static Function<Entity, DeckWalker.Input> clientInput = e -> DeckWalker.Input.NONE;
    /** Client: whether the local player's magnetic boots are switched on. */
    public static Predicate<Entity> clientMagboots = e -> true;
    /** Client: called after the local player's walk step (sends the deck position). */
    public static java.util.function.BiConsumer<ShipStructure, Entity> clientAfterStep = (s, e) -> { };
    /** Client: the local player stepped off ({@code world} position, {@code velocity} blocks per tick, the ship's own). */
    public static ClientLeave clientLeave = (s, e, world, velocity, shipVelocity) -> { };
    /**
     * Server: players whose walk the server simulates itself (no client reports their deck position): GameTest mock
     * players. Real players' clients simulate their own.
     */
    public static Predicate<ServerPlayer> serverSimulatesPlayer = p -> false;

    public interface ClientLeave {
        void leave(ShipStructure ship, Entity e, Vec3 world, Vec3 velocity, Vec3 shipVelocity);
    }

    /** Walk state per entity, one map per side (the client and server threads never share an entity). */
    static final class State {
        final DeckWalker walker;
        int lastTick = Integer.MIN_VALUE;
        int epoch;
        boolean terrainSupported, confirmed;
        Vector3d lastLocal;
        final int created;

        State(DeckWalker w, int epoch, int tick) {
            this.walker = w;
            this.epoch = epoch;
            this.created = tick;
            this.lastLocal = new Vector3d(w.x, w.y, w.z);
        }
    }

    private static final Map<Entity, State> CLIENT = new WeakHashMap<>(), SERVER = new WeakHashMap<>();
    /** Where a walker leaving the deck is set down (consulted by {@code Starship.getDismountLocationForPassenger}). */
    private static final Map<Entity, Vec3> LEAVE_TO = new WeakHashMap<>();
    /** Horizontal distance walked on the deck last tick, for the walk animation, per entity (client). */
    private static final Map<Entity, float[]> WALKED = new WeakHashMap<>();

    private static Map<Entity, State> states(Level level) {
        return level.isClientSide() ? CLIENT : SERVER;
    }

    /** Call once from {@code Galactica.onInitialize}. */
    public static void init() {
        if (DECK == null) throw new IllegalStateException("deck attachment not registered");
        DeckPayloads.register();
        ServerPlayNetworking.registerGlobalReceiver(DeckPayloads.Board.TYPE, (msg, ctx) -> handleBoard(ctx.player(), msg));
        ServerPlayNetworking.registerGlobalReceiver(DeckPayloads.Move.TYPE, (msg, ctx) -> handleMove(ctx.player(), msg));
        ServerPlayNetworking.registerGlobalReceiver(DeckPayloads.Leave.TYPE, (msg, ctx) -> handleLeave(ctx.player(), msg));
        ServerPlayNetworking.registerGlobalReceiver(DeckPayloads.Use.TYPE, (msg, ctx) -> handleUse(ctx.player(), msg));
    }

    // ------------------------------------------------------------------ who walks

    /** True while {@code e} walks on (rather than sits in) the ship it rides. */
    public static boolean isWalker(Entity e) {
        if (!(e.getVehicle() instanceof ShipStructure)) return false;
        boolean attached = e.hasAttached(DECK);
        if (!e.level().isClientSide()) return attached;
        synchronized (Deck.class) {
            State st = CLIENT.get(e);
            if (st == null) return attached;
            // The local walker's own state counts until the server's attachment confirms it; once confirmed, the
            // attachment going away (the server seated them) ends the walk.
            if (attached) {
                st.confirmed = true;
                return true;
            }
            if (st.confirmed || e.tickCount - st.created > 100) {
                CLIENT.remove(e);
                return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ frames and blocks

    /** The ship's pose now. */
    public static ShipPose pose(ShipStructure s) {
        var q = s.attitude();
        return new ShipPose(new Vector3d(s.getX(), s.getY() + s.pivot(), s.getZ()), new Quaterniond(q.x(), q.y(), q.z(), q.w()),
                s.centreOfMass());
    }

    /** The ship's pose at the start of its last tick. */
    public static ShipPose prevPose(ShipStructure s) {
        var q = s.attitudeO();
        return new ShipPose(new Vector3d(s.xo, s.yo + s.pivot(), s.zo), new Quaterniond(q.x(), q.y(), q.z(), q.w()), s.centreOfMass());
    }

    /** The ship's interpolated pose for rendering. */
    public static ShipPose pose(ShipStructure s, float partialTick) {
        var q = s.getAttitude(partialTick, new org.joml.Quaternionf());
        Vec3 p = s.getPosition(partialTick);
        return new ShipPose(new Vector3d(p.x, p.y + s.pivot(), p.z), new Quaterniond(q.x, q.y, q.z, q.w), s.centreOfMass());
    }

    /** Velocity (blocks per tick, world) of the ship's material point at {@code local} over its last tick. */
    public static Vec3 pointVelocity(ShipStructure s, Vector3d local) {
        Vector3d a = prevPose(s).toWorld(local), b = pose(s).toWorld(local);
        return new Vec3(b.x - a.x, b.y - a.y, b.z - a.z);
    }

    private static final Map<BlockState, List<Aabb>> SHAPES = new ConcurrentHashMap<>();

    /** A block state's collision boxes in its own cell (0..1), cached. */
    static List<Aabb> shape(BlockState state) {
        return SHAPES.computeIfAbsent(state, st -> {
            List<Aabb> out = new ArrayList<>();
            for (AABB b : st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs())
                out.add(new Aabb(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
            return List.copyOf(out);
        });
    }

    /** The ship's block collision boxes, cell coordinates. */
    public static DeckWalker.Blocks blocks(ShipStructure s) {
        ShipBlocks blocks = s.blocks();
        int[] lo = s.cellMin(), hi = s.cellMax();
        return (r, out) -> {
            int x0 = Math.max(lo[0], (int) Math.floor(r.minX())), x1 = Math.min(hi[0] - 1, (int) Math.ceil(r.maxX()) - 1);
            int y0 = Math.max(lo[1], (int) Math.floor(r.minY())), y1 = Math.min(hi[1] - 1, (int) Math.ceil(r.maxY()) - 1);
            int z0 = Math.max(lo[2], (int) Math.floor(r.minZ())), z1 = Math.min(hi[2] - 1, (int) Math.ceil(r.maxZ()) - 1);
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 40_000) return; // a hostile or broken query
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                int i = blocks.indexAt(x, y, z);
                if (i < 0) continue;
                for (Aabb a : shape(blocks.state(i))) out.add(a.move(x, y, z));
            }
        };
    }

    /** Whether a deck point (feet) lies in the ship's volume grown by {@code side} sideways and below and {@code up} above. */
    public static boolean inVolume(ShipStructure s, double x, double y, double z, double side, double up) {
        int[] lo = s.cellMin(), hi = s.cellMax();
        return x >= lo[0] - side && x <= hi[0] + side && z >= lo[2] - side && z <= hi[2] + side && y >= lo[1] - side && y <= hi[1] + up;
    }

    // ------------------------------------------------------------------ gravity on deck

    /** Gravity in the ship's axes for a body on its deck, cells per tick². */
    public static Vector3d gravity(ShipStructure s, Entity e, DeckWalker w) {
        boolean living = e instanceof LivingEntity;
        if (s.level().dimension() == Galactica.SPACE) {
            double base = living ? LIVING_G : ITEM_G;
            if (s.artificialGravityOn()) return new Vector3d(0, -base, 0);
            if (e instanceof Player p && magbootsWorn(p) && magbootsOn(p) && w.floorWithin(0.6, blocks(s))) return new Vector3d(0, -LIVING_G, 0);
            return new Vector3d();
        }
        double g = e.getGravity();
        if (g <= 0) return new Vector3d();
        Vector3d local = pose(s).dirToLocal(new Vector3d(0, -g, 0));
        // Friction holds a walker on a deck that tilts gently: the pull stays along the deck's own down.
        if (-local.y >= g * Math.cos(Math.toRadians(STICK_ANGLE_DEG))) return new Vector3d(0, -g, 0);
        return local;
    }

    public static boolean magbootsWorn(Player p) {
        return p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).is(dev.galactica.content.GalacticaItems.EVA_BOOTS);
    }

    private static boolean magbootsOn(Player p) {
        return !p.level().isClientSide() || clientMagboots.test(p);
    }

    // ------------------------------------------------------------------ the walk

    /**
     * {@code Starship.positionRider} for a walker: runs this side's walk step once per tick if this side simulates
     * the walker, then puts it where its deck position is on the ship as this side sees the ship.
     */
    public static void positionWalker(ShipStructure ship, Entity e, Entity.MoveFunction move) {
        Level level = e.level();
        boolean client = level.isClientSide();
        boolean simulated = client ? clientSimulates.test(e) : serverSimulates(e);
        Vector3d local;
        if (simulated) {
            State st;
            synchronized (Deck.class) {
                st = states(level).get(e);
            }
            DeckPos synced = e.getAttached(DECK);
            if (st == null || synced != null && synced.epoch() != st.epoch) {
                Vector3d at = synced != null ? new Vector3d(synced.x(), synced.y(), synced.z()) : pose(ship).toLocal(new Vector3d(e.getX(), e.getY(), e.getZ()));
                st = new State(new DeckWalker(sizeOf(e), at.x, at.y, at.z), synced != null ? synced.epoch() : 0, e.tickCount);
                st.confirmed = synced != null;
                synchronized (Deck.class) {
                    states(level).put(e, st);
                }
            }
            if (st.lastTick != e.tickCount) {
                st.lastTick = e.tickCount;
                if (!step(ship, e, st)) return; // stepped off
            }
            local = new Vector3d(st.walker.x, st.walker.y, st.walker.z);
        } else {
            DeckPos p = e.getAttached(DECK);
            if (p == null) {
                synchronized (Deck.class) {
                    State st = states(level).get(e);
                    local = st != null ? new Vector3d(st.walker.x, st.walker.y, st.walker.z) : pose(ship).toLocal(new Vector3d(e.getX(), e.getY(), e.getZ()));
                }
            } else {
                local = new Vector3d(p.x(), p.y(), p.z());
            }
        }
        if (client) noteWalked(e, local);
        Vector3d w = pose(ship).toWorld(local);
        move.accept(e, w.x, w.y, w.z);
    }

    static DeckWalker.Size sizeOf(Entity e) {
        if (e instanceof ItemEntity) return DeckWalker.Size.ITEM;
        return new DeckWalker.Size(e.getBbWidth(), e.getBbHeight(), e.maxUpStep());
    }

    private static boolean serverSimulates(Entity e) {
        return !(e instanceof Player) || e instanceof ServerPlayer sp && serverSimulatesPlayer.test(sp);
    }

    /** One walk step; false if the walker stepped off (already dismounted). */
    private static boolean step(ShipStructure ship, Entity e, State st) {
        DeckWalker w = st.walker;
        DeckWalker.Blocks blocks = blocks(ship);
        ShipPose pose = pose(ship);
        // The walker turns with the ship (its look is kept in the ship's frame).
        if (e instanceof LivingEntity l && !(e instanceof ItemEntity)) {
            double turn = prevPose(ship).headingDelta(pose);
            if (turn != 0) {
                e.setYRot((float) (e.getYRot() + turn));
                l.setYHeadRot((float) (l.getYHeadRot() + turn));
                l.setYBodyRot((float) (l.yBodyRot + turn));
            }
        }
        DeckWalker.Input in = input(ship, e);
        Vector3d g = gravity(ship, e, w);
        double px = w.x, py = w.y, pz = w.z;
        w.step(in, g.x, g.y, g.z, blocks);
        st.terrainSupported = terrain(ship, e, w, px, py, pz, pose);
        // Stepping off: out of the ship's volume, a fall with no deck below, or onto the ground beside it.
        boolean weighted = g.lengthSquared() > 0;
        boolean leave = !inVolume(ship, w.x, w.y, w.z, 1.0, 3.0)
                || weighted && !w.floorWithin(3.0, blocks) && !w.inBlock(blocks)
                || st.terrainSupported && !w.floorWithin(0.6, blocks);
        if (leave) {
            leaveDeck(ship, e, st);
            return false;
        }
        if (e.level().isClientSide()) clientAfterStep.accept(ship, e);
        else if (serverSimulates(e)) {
            DeckPos now = new DeckPos(w.x, w.y, w.z, st.epoch);
            if (!now.equals(e.getAttached(DECK))) e.setAttached(DECK, now);
        }
        return true;
    }

    /** The walking intent of {@code e} in the ship's frame. */
    private static DeckWalker.Input input(ShipStructure ship, Entity e) {
        if (e.level().isClientSide()) return clientInput.apply(e);
        if (e instanceof ServerPlayer sp) {
            Input k = sp.getLastClientInput();
            double fwd = (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0), strafe = (k.left() ? 1 : 0) - (k.right() ? 1 : 0);
            return new DeckWalker.Input(fwd, strafe, localYaw(ship, sp.getYRot()), k.jump(), k.sprint(), k.shift(), 0.1, 0.42);
        }
        return DeckWalker.Input.NONE;
    }

    /** A world yaw (Minecraft degrees) in the ship's frame. */
    public static double localYaw(ShipStructure ship, float worldYaw) {
        return worldYaw - Math.toDegrees(ShipPose.heading(pose(ship).attitude()));
    }

    /**
     * A landed ship's walkers walk on the ground beside it too: the step just taken (in the world) is collided with
     * the terrain, and a step the ground stopped from below counts as standing on the ground. True if so.
     */
    private static boolean terrain(ShipStructure ship, Entity e, DeckWalker w, double px, double py, double pz, ShipPose pose) {
        Level level = e.level();
        Vector3d from = pose.toWorld(new Vector3d(px, py, pz)), to = pose.toWorld(new Vector3d(w.x, w.y, w.z));
        Vec3 d = new Vec3(to.x - from.x, to.y - from.y, to.z - from.z);
        double half = w.size().width() / 2;
        AABB box = new AABB(from.x - half, from.y, from.z - half, from.x + half, from.y + w.size().height(), from.z + half);
        if (level.noCollision(e, box.expandTowards(d))) return false;
        Vec3 c = Entity.collideBoundingBox(e, d, box, level, List.of());
        if (c.distanceToSqr(d) < 1e-12) return false;
        Vector3d back = pose.toLocal(new Vector3d(from.x + c.x, from.y + c.y, from.z + c.z));
        w.x = back.x;
        w.y = back.y;
        w.z = back.z;
        boolean landed = d.y < 0 && c.y > d.y + 1e-7;
        if (landed) {
            w.onGround = true;
            w.vy = Math.max(0, w.vy);
        }
        if (Math.abs(c.x - d.x) > 1e-7 || Math.abs(c.z - d.z) > 1e-7) {
            w.vx = 0;
            w.vz = 0;
        }
        return landed;
    }

    private static void noteWalked(Entity e, Vector3d local) {
        synchronized (WALKED) {
            float[] last = WALKED.computeIfAbsent(e, k -> new float[] {(float) local.x, (float) local.z, 0, e.tickCount});
            if (last[3] != e.tickCount) {
                last[2] = (float) Math.hypot(local.x - last[0], local.z - last[1]);
                last[0] = (float) local.x;
                last[1] = (float) local.z;
                last[3] = e.tickCount;
            }
        }
    }

    /** Horizontal distance a walker moved on the deck last tick (client), for its walk animation. */
    public static float walkedDistance(Entity e) {
        synchronized (WALKED) {
            float[] last = WALKED.get(e);
            return last == null ? 0 : last[2];
        }
    }

    // ------------------------------------------------------------------ boarding and leaving

    /**
     * Whether a body standing at world {@code feet} would board {@code ship}'s deck: inside the ship's volume (or just
     * above its top, standing on it) with the deck under its feet, or weightless anywhere inside the hull.
     */
    public static @Nullable Vector3d boardingSpot(ShipStructure ship, Entity e, Vec3 feet, boolean onGround) {
        if (!ship.flightReady() && ship.blocks().isEmpty()) return null;
        Vector3d local = pose(ship).toLocal(new Vector3d(feet.x, feet.y, feet.z));
        if (!inVolume(ship, local.x, local.y, local.z, 0.3, 2.0)) return null;
        DeckWalker probe = new DeckWalker(sizeOf(e), local.x, local.y, local.z);
        DeckWalker.Blocks blocks = blocks(ship);
        boolean weightless = gravity(ship, e, probe).lengthSquared() == 0;
        boolean supported = onGround ? probe.floorWithin(0.3, blocks) : probe.floorWithin(1.5, blocks);
        if (weightless && inVolume(ship, local.x, local.y, local.z, 0, 0)) supported = true;
        if (!supported) return null;
        return local;
    }

    /** Client: the local walker's deck position and epoch (what it reports), or null if it does not walk. */
    public static @Nullable DeckPos clientState(Entity e) {
        synchronized (Deck.class) {
            State st = CLIENT.get(e);
            return st == null ? null : new DeckPos(st.walker.x, st.walker.y, st.walker.z, st.epoch);
        }
    }

    /** Client: the local player just mounted {@code ship}'s deck at {@code local} (velocity {@code v}, world, blocks per tick). */
    public static void clientBoarded(ShipStructure ship, Entity e, Vector3d local, int epoch, Vec3 v) {
        DeckWalker w = new DeckWalker(sizeOf(e), local.x, local.y, local.z);
        Vec3 sv = pointVelocity(ship, local);
        Vector3d rel = pose(ship).dirToLocal(new Vector3d(v.x - sv.x, v.y - sv.y, v.z - sv.z));
        w.vx = rel.x;
        w.vy = rel.y;
        w.vz = rel.z;
        synchronized (Deck.class) {
            CLIENT.put(e, new State(w, epoch, e.tickCount));
        }
    }

    /** Server: makes {@code e} walk on {@code ship} at deck position {@code local}. True if it boarded. */
    public static boolean board(ShipStructure ship, Entity e, Vector3d local, int epoch, Vec3 velocity) {
        if (e.level().isClientSide() || ship.isRemoved() || e == ship || e instanceof Starship) return false;
        if (e.isPassenger() && e.getVehicle() != ship) e.stopRiding();
        if (walkers(ship) >= MAX_WALKERS) return false;
        e.setAttached(DECK, new DeckPos(local.x, local.y, local.z, epoch));
        if (e.getVehicle() != ship && !e.startRiding(ship, true, true)) {
            e.removeAttached(DECK);
            return false;
        }
        if (serverSimulates(e)) {
            DeckWalker w = new DeckWalker(sizeOf(e), local.x, local.y, local.z);
            Vector3d v = pose(ship).dirToLocal(new Vector3d(velocity.x, velocity.y, velocity.z));
            Vec3 ship0 = pointVelocity(ship, local);
            Vector3d sv = pose(ship).dirToLocal(new Vector3d(ship0.x, ship0.y, ship0.z));
            w.vx = v.x - sv.x;
            w.vy = v.y - sv.y;
            w.vz = v.z - sv.z;
            synchronized (Deck.class) {
                SERVER.put(e, new State(w, epoch, e.tickCount));
            }
        }
        ship.positionRider(e);
        if (AUTOTEST) Galactica.LOG.info("[autotest] {} boards the deck of ship {} at ({}, {}, {})", e.getName().getString(), ship.getId(),
                String.format(java.util.Locale.ROOT, "%.2f", local.x), String.format(java.util.Locale.ROOT, "%.2f", local.y),
                String.format(java.util.Locale.ROOT, "%.2f", local.z));
        return true;
    }

    private static int walkers(ShipStructure ship) {
        int n = 0;
        for (Entity p : ship.getPassengers()) if (p.hasAttached(DECK)) n++;
        return n;
    }

    /** The walker's velocity in the world on leaving (ship point velocity plus its own), blocks per tick. */
    private static void leaveDeck(ShipStructure ship, Entity e, State st) {
        DeckWalker w = st.walker;
        Vector3d local = new Vector3d(w.x, w.y, w.z);
        Vector3d world = pose(ship).toWorld(local);
        Vec3 shipV = pointVelocity(ship, local);
        Vector3d own = pose(ship).dirToWorld(new Vector3d(w.vx, w.vy, w.vz));
        Vec3 v = shipV.add(own.x, own.y, own.z);
        Vec3 at = new Vec3(world.x, world.y, world.z);
        synchronized (Deck.class) {
            states(e.level()).remove(e);
        }
        if (e.level().isClientSide()) {
            clientLeave.leave(ship, e, at, v, shipV);
        } else {
            leaveServer(ship, e, at, v);
        }
    }

    /** Server: sets a walker down off the deck at {@code at} with velocity {@code v} (blocks per tick). */
    public static void leaveServer(ShipStructure ship, Entity e, Vec3 at, Vec3 v) {
        synchronized (Deck.class) {
            SERVER.remove(e);
            LEAVE_TO.put(e, at);
        }
        e.removeAttached(DECK);
        if (e.getVehicle() == ship) e.stopRiding();
        synchronized (Deck.class) {
            LEAVE_TO.remove(e);
        }
        e.setPos(at);
        e.setDeltaMovement(v);
        e.resetFallDistance();
        if (e instanceof ServerPlayer sp) {
            sp.connection.resetPosition();
            dev.galactica.ship.deck.EvaDrift.start(sp, ship, v);
        }
        if (AUTOTEST) Galactica.LOG.info("[autotest] {} steps off ship {} at {} with {} m/s", e.getName().getString(), ship.getId(), at,
                String.format(java.util.Locale.ROOT, "%.2f", v.length() * 20));
    }

    /** Where a passenger leaving now is set down, if it is a walker stepping off (null otherwise). */
    public static @Nullable Vec3 dismountTarget(Entity passenger) {
        synchronized (Deck.class) {
            Vec3 at = LEAVE_TO.get(passenger);
            if (at != null) return at;
        }
        return passenger.hasAttached(DECK) ? passenger.position() : null;
    }

    // ------------------------------------------------------------------ server: the clients' reports

    private static void handleBoard(ServerPlayer p, DeckPayloads.Board msg) {
        if (!(p.level().getEntity(msg.shipId()) instanceof ShipStructure ship) || ship.isRemoved()) return;
        Vector3d local = new Vector3d(msg.x(), msg.y(), msg.z());
        // The claimed spot must be on this ship, near where the server has the player.
        Vector3d here = pose(ship).toLocal(new Vector3d(p.getX(), p.getY(), p.getZ()));
        double slack = 6 + ship.getDeltaMovement().length() * 6;
        if (!inVolume(ship, local.x, local.y, local.z, 1.0, 3.0) || here.distance(local) > slack || p.getVehicle() != null && p.getVehicle() != ship) {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(ship));
            return;
        }
        board(ship, p, local, msg.epoch(), p.getDeltaMovement());
    }

    private static void handleMove(ServerPlayer p, DeckPayloads.Move msg) {
        if (!(p.getVehicle() instanceof ShipStructure ship) || ship.getId() != msg.shipId()) return;
        DeckPos was = p.getAttached(DECK);
        if (was == null || was.epoch() != msg.epoch()) return; // a report from before the server moved the walker
        double d = Math.sqrt(sq(msg.x() - was.x()) + sq(msg.y() - was.y()) + sq(msg.z() - was.z()));
        if (!Float.isFinite(msg.x() + msg.y() + msg.z()) || d > MAX_REPORTED_STEP || !inVolume(ship, msg.x(), msg.y(), msg.z(), 1.5, 3.5)) {
            // Refused: the server's position stands, under a new epoch so the client takes it over.
            p.setAttached(DECK, new DeckPos(was.x(), was.y(), was.z(), was.epoch() + 1));
            Galactica.LOG.debug("Deck move from {} refused: {} cells", p.getName().getString(), d);
            return;
        }
        p.setAttached(DECK, new DeckPos(msg.x(), msg.y(), msg.z(), was.epoch()));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void handleLeave(ServerPlayer p, DeckPayloads.Leave msg) {
        if (!(p.getVehicle() instanceof ShipStructure ship) || !p.hasAttached(DECK)) return;
        DeckPos was = p.getAttached(DECK);
        Vec3 server = toWorld(ship, was);
        Vec3 at = new Vec3(msg.x(), msg.y(), msg.z());
        Vec3 v = new Vec3(msg.vx(), msg.vy(), msg.vz());
        double slack = 6 + ship.getDeltaMovement().length() * 10;
        if (!at.isFinite() || at.distanceTo(server) > slack) at = server;
        if (!v.isFinite() || v.length() > 20) v = ship.getDeltaMovement();
        leaveServer(ship, p, at, v);
    }

    /** A deck position in the world, on the server's ship. */
    public static Vec3 toWorld(ShipStructure ship, DeckPos p) {
        Vector3d w = pose(ship).toWorld(new Vector3d(p.x(), p.y(), p.z()));
        return new Vec3(w.x, w.y, w.z);
    }

    /** A walker uses a ship block: the helm (fly), a crew seat (sit), a container (open it). */
    private static void handleUse(ServerPlayer p, DeckPayloads.Use msg) {
        if (!(p.getVehicle() instanceof ShipStructure ship) || ship.getId() != msg.shipId() || !p.hasAttached(DECK)) return;
        use(p, ship, msg.block());
    }

    /** Server: what a walker's click on ship block {@code index} does. True if something happened. */
    public static boolean use(ServerPlayer p, ShipStructure ship, int index) {
        ShipBlocks blocks = ship.blocks();
        if (index < 0 || index >= blocks.size()) return false;
        DeckPos at = p.getAttached(DECK);
        if (at == null) return false;
        int c = blocks.cell(index);
        Vector3d centre = new Vector3d(ShipCell.unpackX(c) + 0.5, ShipCell.unpackY(c) + 0.5, ShipCell.unpackZ(c) + 0.5);
        if (centre.distance(at.x(), at.y() + p.getEyeHeight(), at.z()) > USE_REACH + 1) return false;
        ShipPart part = ShipPartBlocks.partOf(blocks.state(index));
        if (part == ShipPart.HELM || part == ShipPart.CREW_SEAT) return sit(p, ship, part == ShipPart.HELM);
        if (ShipCargo.isContainer(blocks, index)) {
            ShipCargo.open(p, ship, index);
            return true;
        }
        return false;
    }

    /** A walker takes the helm (if free) or the next free crew seat. */
    private static boolean sit(ServerPlayer p, ShipStructure ship, boolean helm) {
        int crew = ship.crew().size();
        if (helm && crew > 0) {
            p.sendOverlayMessage(Component.translatable("hud.galactica.deck.helm_taken"));
            return false;
        }
        if (crew >= ship.spec().seatCount()) {
            p.sendOverlayMessage(Component.translatable("hud.galactica.deck.no_seat"));
            return false;
        }
        p.removeAttached(DECK);
        synchronized (Deck.class) {
            SERVER.remove(p);
        }
        ship.positionRider(p);
        Galactica.LOG.info("{}{} sits {} of ship {}", AUTOTEST ? "[autotest] " : "", p.getName().getString(), helm ? "at the helm" : "in a crew seat", ship.getId());
        return true;
    }

    /**
     * Server: a seated rider of a built ship who wants out stands up on its deck instead, beside their seat, when
     * there is room to stand there. False if there is none (they leave the ship the usual way).
     */
    public static boolean standUp(ServerPlayer p, ShipStructure ship) {
        if (p.hasAttached(DECK) || ship.blocks().isEmpty()) return false;
        int index = ship.crew().indexOf(p);
        if (index < 0) return false;
        var seat = ship.seat(index);
        var com = ship.centreOfMass();
        // The seat block: the rider's hips are in it.
        int cx = (int) Math.floor(seat.x() + com.x()), cy = (int) Math.floor(seat.y() + com.y()), cz = (int) Math.floor(seat.z() + com.z());
        DeckWalker.Blocks blocks = blocks(ship);
        // In front of the seat first (the ship's nose), then beside it, behind it, and on it.
        int[][] spots = {{0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 2}, {1, 0, 1}, {-1, 0, 1}, {0, 1, 0}};
        for (int[] s : spots) {
            DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, cx + s[0] + 0.5, cy + s[1], cz + s[2] + 0.5);
            if (w.inBlock(blocks) || !w.floorWithin(0.6, blocks)) continue;
            DeckPos was = p.getAttached(DECK);
            p.setAttached(DECK, new DeckPos(w.x, w.y, w.z, (was == null ? 0 : was.epoch()) + 1 + p.getRandom().nextInt(1000)));
            ship.positionRider(p);
            Galactica.LOG.info("{}{} stands up on the deck of ship {}", AUTOTEST ? "[autotest] " : "", p.getName().getString(), ship.getId());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ server: bodies near a moving hull

    /**
     * Server, every tick for a built ship: items and mobs near it that do not ride it are swept against its move this
     * tick ({@link HullSweep}: pushed clear when it reaches them, carried on from there) and board the deck when they
     * rest on it, so a cargo item dropped on the deck stays aboard when the ship lifts off. Players are left to their
     * own clients.
     */
    public static void serverTick(ShipStructure ship) {
        if (!(ship.level() instanceof ServerLevel level) || ship.blocks().isEmpty()) return;
        ShipPose from = prevPose(ship), to = pose(ship);
        double reach = ship.spec().hullReach() + 2;
        boolean moved = from.maxTravel(to, reach) > 1e-4;
        AABB area = new AABB(to.pivot().x() - reach, to.pivot().y() - reach, to.pivot().z() - reach,
                to.pivot().x() + reach, to.pivot().y() + reach, to.pivot().z() + reach).expandTowards(
                from.pivot().x() - to.pivot().x(), from.pivot().y() - to.pivot().y(), from.pivot().z() - to.pivot().z());
        for (Entity e : level.getEntities((Entity) null, area, Deck::sweepable)) {
            if (e.getRootVehicle() == ship.getRootVehicle()) continue;
            Vec3 feet = e.position();
            if (moved) {
                HullSweep.Result r = HullSweep.sweep(new Vector3d(feet.x, feet.y, feet.z), sizeOf(e), from, to, reach, blocks(ship));
                if (r.hit()) {
                    Vec3 v = pointVelocity(ship, r.local());
                    e.setPos(r.world().x, r.world().y, r.world().z);
                    e.setDeltaMovement(v);
                    feet = e.position();
                }
            }
            Vector3d spot = boardingSpot(ship, e, feet, e.onGround() || e.getDeltaMovement().y <= 0);
            if (spot != null && (moved || e.onGround())) board(ship, e, spot, 0, e.getDeltaMovement());
        }
    }

    private static boolean sweepable(Entity e) {
        return !(e instanceof Starship) && !(e instanceof Player) && !e.isPassenger() && !e.noPhysics && !e.isRemoved()
                && (e instanceof ItemEntity || e instanceof net.minecraft.world.entity.Mob);
    }
}
