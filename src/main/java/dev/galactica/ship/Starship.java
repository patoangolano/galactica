package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.science.Units;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.function.ToDoubleFunction;

/**
 * Single-seat starship with a passenger seat (spec §2): a non-living vehicle flown with Newtonian 6-DOF
 * physics ({@link ShipFlightModel}).
 *
 * <p><b>Authority.</b> Like a vanilla boat, the pilot's client simulates the ship and the server replays its
 * moves ({@code ServerboundMoveVehiclePacket}). Attitude and velocity travel separately in
 * {@link ShipControlPayload}, where the server clamps them to what the engines could have done since the
 * last report. Fuel, hull and exotic matter are server state; the client only reads them.
 *
 * <p><b>Controls</b> (vanilla input record): W/S main engine, A/D yaw, jump/sneak dorsal RCS, mouse pitch and
 * yaw; Q/E roll, C flight assist and J hyperjump come from the client's key mappings. Sneak doubles as "exit"
 * only while the ship rests on the ground (the client strips it from the input packet otherwise), so the
 * down-thruster does not throw the pilot into vacuum.
 */
public class Starship extends VehicleEntity {
    private static final EntityDataAccessor<Quaternionfc> DATA_ATTITUDE = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Float> DATA_HYDROGEN = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_EXOTIC = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_HULL = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_COUPLED = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_THRUST = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_SPOOL = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.INT);
    /** World-frame gravity, m/s²: computed by the server (only it knows the pilot's galactic frame), used by the pilot's prediction. */
    private static final EntityDataAccessor<Vector3fc> DATA_GRAVITY = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.VECTOR3);
    /** RCS command bits ({@link ShipAnimator#rcsMask}) for the puffs other players see. */
    private static final EntityDataAccessor<Byte> DATA_RCS = SynchedEntityData.defineId(Starship.class, EntityDataSerializers.BYTE);

    public static final float MAX_HYDROGEN = 400F, MAX_HULL = 100F;
    public static final int MAX_EXOTIC = 64;
    /** A freshly deployed ship carries a shakedown load: 50 cells, ~17 min of main engine. */
    public static final float DEPLOY_HYDROGEN = 50F;
    /** Hull points per point of incoming damage: a ship survives ~20 unarmed punches, a creeper twice. */
    private static final float HULL_PER_DAMAGE = 5F;
    private static final float HULL_PER_IRON = 10F;
    /** Seats in the body frame, blocks from the model pivot ({@link ShipDimensions}: tandem, both under the canopy). */
    private static final Vector3fc PILOT_SEAT = ShipDimensions.PILOT_SEAT, PASSENGER_SEAT = ShipDimensions.PASSENGER_SEAT;
    private static final double PIVOT = ShipDimensions.PIVOT;
    /** Dev autotest run: crew changes are logged. */
    private static final boolean AUTOTEST = Boolean.getBoolean("galactica.autotest");

    /**
     * Downward gravity, m/s², for levels other than space (space uses {@link ShipGravity} in the pilot's frame).
     * Defaults to Earth; the planet area plugs in each world's surface gravity.
     */
    private static ToDoubleFunction<Level> gravityModel = l -> Units.EARTH_GRAVITY;

    private final Quaternionf attitude = new Quaternionf(), attitudeO = new Quaternionf();
    /** Client, ships this client does not control: synced attitude handed over on tick boundaries. */
    private final AttitudeSync attitudeSync = new AttitudeSync();
    private final Quaternionf turnScratch = new Quaternionf();
    // Pilot-side prediction inputs, fed by the client every tick before the entity ticks.
    private Input pilotInput = Input.EMPTY;
    private float rollAxis, mouseYaw, mousePitch;
    private boolean coupled = true;
    private float localThrottle;
    // Server-side bookkeeping.
    private @Nullable JumpTargetInfo pendingJump;
    private int lastReportTick, lastMainBurnTick = Integer.MIN_VALUE / 2;
    // Pilot's client: reports awaiting a possible server clamp.
    private final PilotReconciler reconciler = new PilotReconciler();
    /** Pilot's client: velocity (m/s, world frame) handed to the space frame since the last report (Krakensbane). */
    private final Vector3d pendingRebase = new Vector3d();
    // Client-side visual state (gear, canopy, gimbal), ticked for every ship the client sees.
    private float gear = 1F, gearO = 1F, canopy, canopyO, gimbalYaw, gimbalYawO, gimbalPitch, gimbalPitchO;
    private boolean gearDown = true;

    public Starship(EntityType<? extends Starship> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    public static void setGravityModel(ToDoubleFunction<Level> model) {
        gravityModel = model;
    }

    /** Surface gravity outside space, m/s². */
    public static double gravityAt(Level level) {
        return gravityModel.applyAsDouble(level);
    }

    /** Gravity acting on this ship, m/s², world (block) frame, as last computed by the server. */
    public Vector3fc gravity() {
        return entityData.get(DATA_GRAVITY);
    }

    /**
     * Server: the level's surface gravity outside space. In space the block velocity feels none: the dominant body's
     * GM/r² acts on the craft's whole free-falling frame ({@link dev.galactica.nav.OrbitalFrame}), which the server
     * integrates alone, so the pilot's prediction and the server never disagree about it.
     */
    private Vector3f computeGravity(@Nullable ServerPlayer pilot) {
        if (level().dimension() != Galactica.SPACE) return new Vector3f(0F, (float) -gravityAt(level()), 0F);
        return new Vector3f();
    }

    // ---------------------------------------------------------------- synced state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(DATA_ATTITUDE, new Quaternionf());
        b.define(DATA_HYDROGEN, 0F);
        b.define(DATA_EXOTIC, 0);
        b.define(DATA_HULL, MAX_HULL);
        b.define(DATA_COUPLED, true);
        b.define(DATA_THRUST, 0F);
        b.define(DATA_SPOOL, 0);
        b.define(DATA_GRAVITY, new Vector3f(0F, (float) -Units.EARTH_GRAVITY, 0F));
        b.define(DATA_RCS, (byte) 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (!level().isClientSide() || isLocalInstanceAuthoritative()) return;
        if (DATA_ATTITUDE.equals(key)) attitudeSync.receive(entityData.get(DATA_ATTITUDE), attitude, attitudeO);
        else if (DATA_COUPLED.equals(key)) coupled = entityData.get(DATA_COUPLED);
    }

    /**
     * The server's entity tracker broadcasts this ship's velocity to every tracking player, the pilot included.
     * On the pilot's client that velocity is only an echo of an earlier report, at least a tick old and clamped by
     * {@link #acceptPilotReport}: applying it would overwrite the prediction, so the ship would keep coasting on
     * a stale heading after a turn or a stop and lose part of every burn. The pilot's client owns the velocity,
     * as it owns the position (vanilla already drops position packets for a locally authoritative vehicle).
     * When the server does refuse part of a report, it says so with a {@link ShipCorrectionPayload}.
     */
    @Override
    public void lerpMotion(Vec3 movement) {
        if (level().isClientSide() && isLocalInstanceAuthoritative()) {
            NetProbe probe = netProbe;
            if (probe != null) probe.droppedEcho(this, movement);
            return;
        }
        super.lerpMotion(movement);
    }

    /**
     * Pilot's client: numbers the velocity (blocks/tick) about to be reported, so a later
     * {@link ShipCorrectionPayload} can say which report the server clamped.
     */
    public int recordReport(Vec3 perTick) {
        return reconciler.record(toMetres(perTick));
    }

    /** Pilot's client: the velocity (m/s) moved into the space frame since the last report, then cleared. */
    public Vector3f takeRebase() {
        Vector3f r = new Vector3f((float) pendingRebase.x, (float) pendingRebase.y, (float) pendingRebase.z);
        pendingRebase.zero();
        return r;
    }

    /**
     * Pilot's client: the server accepted {@code accepted} (m/s) for report {@code seq} instead of what was
     * reported. The refused part is removed from the current prediction; everything predicted since stays.
     */
    public void applyCorrection(int seq, Vector3fc accepted) {
        if (!level().isClientSide() || !isLocalInstanceAuthoritative()) return;
        Vector3d excess = reconciler.correct(seq, new Vector3d(accepted.x(), accepted.y(), accepted.z()));
        if (excess == null) return;
        Vec3 removed = new Vec3(excess.x * ShipFlightModel.TICK, excess.y * ShipFlightModel.TICK, excess.z * ShipFlightModel.TICK);
        setDeltaMovement(getDeltaMovement().subtract(removed));
        NetProbe probe = netProbe;
        if (probe != null) probe.corrected(this, removed);
    }

    /**
     * Replaces the velocity (blocks/tick) outside the flight model, e.g. from a dev test. On the pilot's client,
     * corrections still on their way for earlier reports are dropped: they were computed against a velocity that
     * no longer exists.
     */
    public void overrideVelocity(Vec3 perTick) {
        setDeltaMovement(perTick);
        if (level().isClientSide()) reconciler.reset();
    }

    /** Dev diagnostics (autotest): what the pilot's client does with the server's velocity messages. */
    public interface NetProbe {
        /** A vanilla velocity broadcast for the locally piloted ship, ignored; blocks/tick. */
        void droppedEcho(Starship ship, Vec3 echo);

        /** A server clamp applied to the prediction; the velocity removed, blocks/tick. */
        void corrected(Starship ship, Vec3 removed);
    }

    public static volatile @Nullable NetProbe netProbe;

    public float hydrogen() { return entityData.get(DATA_HYDROGEN); }
    public int exotic() { return entityData.get(DATA_EXOTIC); }
    public float hull() { return entityData.get(DATA_HULL); }
    public boolean coupled() { return coupled; }
    public int spoolTicks() { return entityData.get(DATA_SPOOL); }
    public boolean isSpooling() { return spoolTicks() > 0; }

    public void setHydrogen(float h) { entityData.set(DATA_HYDROGEN, Math.clamp(h, 0F, MAX_HYDROGEN)); }
    public void setExotic(int x) { entityData.set(DATA_EXOTIC, Math.clamp(x, 0, MAX_EXOTIC)); }
    public void setHull(float h) { entityData.set(DATA_HULL, Math.clamp(h, 0F, MAX_HULL)); }

    /** Main-engine throttle for the exhaust glow, −1 … 1: predicted for the local pilot, synced for everyone else. */
    public float throttle() {
        return level().isClientSide() && isLocalInstanceAuthoritative() ? localThrottle : entityData.get(DATA_THRUST);
    }

    public Quaternionfc attitude() { return attitude; }

    /** RCS command bits ({@link ShipAnimator}): predicted for the local pilot (with roll), synced for everyone else. */
    public int rcsMask() {
        if (level().isClientSide() && isLocalInstanceAuthoritative()) {
            Input in = pilotInput;
            return ShipAnimator.rcsMask((in.jump() ? 1 : 0) - (in.shift() ? 1 : 0),
                    (in.right() ? 1 : 0) - (in.left() ? 1 : 0), rollAxis);
        }
        return entityData.get(DATA_RCS);
    }

    /** Landing-gear extension, 0 stowed … 1 down, interpolated. */
    public float gearExtension(float partialTick) { return gearO + (gear - gearO) * partialTick; }

    /** Canopy opening, 0 shut … 1 open, interpolated. */
    public float canopyOpening(float partialTick) { return canopyO + (canopy - canopyO) * partialTick; }

    /** Main-engine gimbal, degrees about the body Y (yaw) and X (pitch) axes, interpolated. */
    public float gimbalYaw(float partialTick) { return gimbalYawO + (gimbalYaw - gimbalYawO) * partialTick; }

    public float gimbalPitch(float partialTick) { return gimbalPitchO + (gimbalPitch - gimbalPitchO) * partialTick; }

    /** Whether the gear is commanded down (for the GeckoLib controller, which plays whole clips). */
    public boolean gearCommandedDown() { return gearDown; }

    /** Client: advances gear, canopy and gimbal one tick. */
    private void visualTick() {
        gearO = gear;
        canopyO = canopy;
        gimbalYawO = gimbalYaw;
        gimbalPitchO = gimbalPitch;
        gearDown = ShipAnimator.gearDown(gearDown, onGround(), heightAboveGround(16));
        gear = ShipAnimator.approach(gear, gearDown ? 1F : 0F, (float) ShipFlightModel.TICK, ShipAnimator.GEAR_SECONDS);
        boolean open = ShipAnimator.canopyOpen(getPassengers().size());
        canopy = ShipAnimator.approach(canopy, open ? 1F : 0F, (float) ShipFlightModel.TICK, ShipAnimator.CANOPY_SECONDS);
        // Body-frame turn this tick: q_prev⁻¹ · q_now, as a rotation vector in degrees.
        Quaternionf d = turnScratch.set(attitudeO).conjugate().mul(attitude);
        if (d.w < 0) d.set(-d.x, -d.y, -d.z, -d.w);
        float angle = 2F * (float) Math.acos(Math.min(1F, d.w));
        float s = (float) Math.sqrt(Math.max(1e-12F, 1F - d.w * d.w));
        float deg = (float) Math.toDegrees(angle);
        float yawRate = d.y / s * deg, pitchRate = d.x / s * deg;
        boolean burning = Math.abs(throttle()) > 0.01F;
        gimbalYaw = ShipAnimator.gimbalStep(gimbalYaw, burning ? ShipAnimator.gimbalTarget(yawRate) : 0F);
        gimbalPitch = ShipAnimator.gimbalStep(gimbalPitch, burning ? ShipAnimator.gimbalTarget(pitchRate) : 0F);
    }

    /** Blocks between the hull's underside (the gear feet) and the first collidable block below, up to {@code max}. */
    private double heightAboveGround(int max) {
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
        int x = net.minecraft.util.Mth.floor(getX()), z = net.minecraft.util.Mth.floor(getZ()), y0 = net.minecraft.util.Mth.floor(getY());
        for (int i = 0; i <= max; i++) {
            p.set(x, y0 - i, z);
            if (!level().isInWorldBounds(p)) break;
            if (!level().getBlockState(p).getCollisionShape(level(), p).isEmpty()) return Math.max(0, getY() - (y0 - i + 1));
        }
        return Double.POSITIVE_INFINITY;
    }

    public Quaternionf getAttitude(float partialTick, Quaternionf dest) {
        return attitudeO.slerp(attitude, partialTick, dest);
    }

    /** Sets the attitude everywhere at once (deploy, commands). */
    public void setAttitude(Quaternionfc q) {
        attitude.set(q).normalize();
        attitudeO.set(attitude);
        attitudeSync.reset(attitude);
        entityData.set(DATA_ATTITUDE, new Quaternionf(attitude));
        syncRotationFields();
    }

    // ---------------------------------------------------------------- pilot (client) inputs

    public void setPilotInput(Input input, float rollAxis) {
        this.pilotInput = input;
        this.rollAxis = rollAxis;
    }

    public void addMouseInput(float yawDeg, float pitchDeg) {
        mouseYaw += yawDeg;
        mousePitch += pitchDeg;
    }

    public void toggleCoupled() {
        coupled = !coupled;
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        if (getHurtTime() > 0) setHurtTime(getHurtTime() - 1);
        attitudeO.set(attitude);
        attitudeSync.apply(attitude, level().isClientSide() && !isLocalInstanceAuthoritative());
        super.tick();
        if (isLocalInstanceAuthoritative()) {
            if (level().isClientSide()) pilotStep();
            else coastStep();
            Vec3 start = position();
            move(MoverType.SELF, getDeltaMovement());
            resolveHullContact(start);
            if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.7, 1.0, 0.7)); // landing-skid friction
        }
        if (!level().isClientSide()) serverStep();
        else visualTick();
        // A powered ship lands under thrust, so height lost while descending is not a fall: without this the
        // vanilla vehicle rules hand the accumulated fall distance to the crew on touchdown and kill them.
        resetFallDistance();
        for (Entity e : getPassengers()) e.resetFallDistance();
    }

    // ---------------------------------------------------------------- hull beyond the box

    /**
     * True if none of the hull's outer probe points ({@link ShipHullProbes}), at entity origin {@code origin} and
     * attitude {@code q}, lies inside a block's collision shape. The vanilla box covers the fuselage and pods; the
     * nose, wings, fins and tail reach up to 7 blocks out and are only held out of terrain by this test.
     */
    public static boolean hullClear(Level level, Vec3 origin, Quaternionfc q) {
        Vector3f v = new Vector3f();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (float[] p : ShipHullProbes.POINTS) {
            q.transform(v.set(p[0], p[1], p[2]));
            double x = origin.x + v.x, y = origin.y + PIVOT + v.y, z = origin.z + v.z;
            pos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double lx = x - pos.getX(), ly = y - pos.getY(), lz = z - pos.getZ();
            for (AABB b : shape.toAabbs()) {
                if (lx >= b.minX && lx < b.maxX && ly >= b.minY && ly < b.maxY && lz >= b.minZ && lz < b.maxZ) return false;
            }
        }
        return true;
    }

    /**
     * After the box move, keeps the hull beyond the box out of terrain. If the probes ended up inside a block, the
     * move is redone from {@code start} one axis at a time (vertical first, as vanilla resolves the box), keeping
     * each axis that leaves the probes clear and zeroing the velocity along the others; a probe stopping a descent,
     * or holding the hull sideways while it settles, counts as touching down (the ship rests on its wing or nose like
     * on its gear). If the turn this tick is what
     * pushed the hull into the ground, the turn is undone first; if even that does not free it (a block placed into
     * a wing), the ship steps up by up to two blocks, and is otherwise left to the box alone so it never wedges.
     */
    private void resolveHullContact(Vec3 start) {
        Level level = level();
        Vec3 end = position();
        if (hullClear(level, end, attitude)) return;
        Vec3 base = start;
        if (!hullClear(level, base, attitude)) {
            if (hullClear(level, base, attitudeO)) {
                attitude.set(attitudeO);
                syncRotationFields();
            } else {
                Vec3 freed = null;
                for (int i = 1; i <= 8 && freed == null; i++) {
                    Vec3 c = base.add(0, 0.25 * i, 0);
                    if (hullClear(level, c, attitude) && level.noCollision(this, getBoundingBox().move(c.subtract(end)))) freed = c;
                }
                if (freed == null) return;
                base = freed;
            }
        }
        Vec3 d = end.subtract(start), v = getDeltaMovement();
        double vx = v.x, vy = v.y, vz = v.z;
        boolean touchdown = false, scraped = false;
        Vec3 p = base, t = p.add(0, d.y, 0);
        if (hullClear(level, t, attitude)) p = t;
        else {
            touchdown = d.y < 0;
            vy = 0;
        }
        t = p.add(d.x, 0, 0);
        if (hullClear(level, t, attitude)) p = t;
        else {
            vx = 0;
            scraped = true;
        }
        t = p.add(0, 0, d.z);
        if (hullClear(level, t, attitude)) p = t;
        else {
            vz = 0;
            scraped = true;
        }
        // A hull held sideways by a slope while settling (not climbing) rests on it too, so skid friction applies
        // and it comes to rest instead of creeping down the face.
        if (scraped && d.y <= 0) touchdown = true;
        if (!level.noCollision(this, getBoundingBox().move(p.subtract(end)))) p = base;
        setPos(p);
        setDeltaMovement(vx, vy, vz);
        if (touchdown) {
            verticalCollision = true;
            setOnGround(true);
        }
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    /** Client-side prediction for the local pilot. */
    private void pilotStep() {
        Input in = pilotInput;
        float yawKeys = (in.right() ? 1F : 0F) - (in.left() ? 1F : 0F);
        var c = new ShipFlightModel.Controls(
                (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0),
                (in.jump() ? 1 : 0) - (in.shift() ? 1 : 0),
                mouseYaw + yawKeys * ShipFlightModel.KEY_YAW_RATE * ShipFlightModel.TICK,
                mousePitch,
                rollAxis * ShipFlightModel.KEY_ROLL_RATE * ShipFlightModel.TICK,
                coupled);
        mouseYaw = mousePitch = 0;
        Quaterniond q = new Quaterniond(attitude.x, attitude.y, attitude.z, attitude.w);
        Vector3d v = toMetres(getDeltaMovement());
        Vector3fc g = gravity();
        ShipFlightModel.step(q, v, c, new Vector3d(g.x(), g.y(), g.z()), hydrogen(), ShipFlightModel.TICK, assistReference());
        // Speed beyond the block cap goes to the frame (reported with the velocity, ShipClient), not lost.
        if (level().dimension() == Galactica.SPACE) pendingRebase.add(ShipFlightModel.krakensbane(v));
        attitude.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
        localThrottle = hydrogen() > 0 ? (float) c.thrust() : 0F;
        setDeltaMovement(v.x * ShipFlightModel.TICK, v.y * ShipFlightModel.TICK, v.z * ShipFlightModel.TICK);
        syncRotationFields();
    }

    /**
     * Pilot's client: the velocity (m/s, world frame) of the block frame relative to the dominant body, against which
     * flight assist measures (the synced orbital-frame velocity plus what was folded into it since the last report).
     * Zero outside space.
     */
    private Vector3d assistReference() {
        if (level().dimension() != Galactica.SPACE || !(getControllingPassenger() instanceof Player p)) return new Vector3d();
        NavState nav = p.getAttached(Galactica.NAV);
        if (nav == null) return new Vector3d(pendingRebase);
        double[] f = nav.frameVelocity();
        double[] mc = Frames.galacticToMc(f[0], f[1], f[2]);
        Vector3d r = new Vector3d(mc[0], mc[1], mc[2]).add(pendingRebase);
        return r.isFinite() ? r : new Vector3d();
    }

    /** Nobody at the controls (server): free fall plus whatever velocity it had. */
    private void coastStep() {
        Vec3 d = getDeltaMovement();
        Vector3fc g = gravity();
        double k = ShipFlightModel.TICK * ShipFlightModel.TICK;
        double vy = onGround() && g.y() < 0 ? Math.max(0, d.y) : d.y;
        setDeltaMovement(d.x + g.x() * k, vy + g.y() * k, d.z + g.z() * k);
        entityData.set(DATA_THRUST, 0F);
    }

    private void serverStep() {
        ServerPlayer pilot = getControllingPassenger() instanceof ServerPlayer sp ? sp : null;
        entityData.set(DATA_GRAVITY, computeGravity(pilot));
        if (pilot != null) {
            Input in = pilot.getLastClientInput();
            float throttle = (in.forward() ? 1F : 0F) - (in.backward() ? 1F : 0F);
            if (hydrogen() <= 0) throttle = 0;
            if (throttle != 0) lastMainBurnTick = tickCount;
            if (throttle != 0) setHydrogen(hydrogen() - Math.abs(throttle) * (float) (ShipFlightModel.TICK / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN));
            if (entityData.get(DATA_THRUST) != throttle) entityData.set(DATA_THRUST, throttle);
            byte rcs = (byte) ShipAnimator.rcsMask((in.jump() ? 1 : 0) - (in.shift() ? 1 : 0),
                    (in.right() ? 1 : 0) - (in.left() ? 1 : 0), 0F);
            if (entityData.get(DATA_RCS) != rcs) entityData.set(DATA_RCS, rcs);
        } else if (entityData.get(DATA_RCS) != 0) {
            entityData.set(DATA_RCS, (byte) 0);
        }
        int spool = spoolTicks();
        if (spool > 0) {
            if (pilot == null || pendingJump == null) {
                cancelSpool();
            } else if (spool == 1) {
                entityData.set(DATA_SPOOL, 0);
                JumpTargetInfo t = pendingJump;
                pendingJump = null;
                Hyperdrive.complete(this, pilot, t);
            } else {
                entityData.set(DATA_SPOOL, spool - 1);
            }
        }
    }

    void beginSpool(JumpTargetInfo target) {
        pendingJump = target;
        entityData.set(DATA_SPOOL, DriveRules.SPOOL_TICKS);
    }

    void cancelSpool() {
        pendingJump = null;
        entityData.set(DATA_SPOOL, 0);
    }

    /** Yaw/pitch fields follow the nose so vanilla consumers (sounds, tracking, warp heading) see a sane facing. */
    private void syncRotationFields() {
        Vector3f f = attitude.transform(new Vector3f(0, 0, 1));
        setYRot((float) Math.toDegrees(Math.atan2(-f.x, f.z)));
        setXRot((float) Math.toDegrees(-Math.asin(Math.clamp(f.y, -1F, 1F))));
    }

    private static Vector3d toMetres(Vec3 perTick) {
        return new Vector3d(perTick.x, perTick.y, perTick.z).div(ShipFlightModel.TICK);
    }

    // ---------------------------------------------------------------- server validation of pilot reports

    /**
     * Accepts the pilot's predicted attitude and velocity, clamped to the turn-rate cap and to what the engines
     * plus gravity could do in the ticks since the last report. The main engine only counts while the server is
     * burning hydrogen for it (W/S held within that window), so a modified client gets no free thrust. The
     * server keeps the velocity so vanilla's "moved too quickly" check compares the reported move against the
     * ship's real speed, not against zero.
     */
    void acceptPilotReport(ServerPlayer pilot, int seq, Quaternionfc q, Vector3fc velocity, boolean assist, Vector3fc rebase) {
        if (!q.isFinite() || !velocity.isFinite() || q.lengthSquared() < 1e-6) return;
        int now = tickCount;
        int ticks = Math.clamp(now - lastReportTick, 1, 20);
        lastReportTick = now;

        Vector3d v = new Vector3d(velocity.x(), velocity.y(), velocity.z());
        // Krakensbane: the part of the velocity the client moved into the frame still counts against the engines.
        Vector3d moved = new Vector3d();
        if (rebase.isFinite() && level().dimension() == Galactica.SPACE) {
            moved.set(rebase.x(), rebase.y(), rebase.z());
            double cap = 2 * ShipFlightModel.MAX_SPEED;
            if (moved.length() > cap) moved.mul(cap / moved.length());
        }
        Vector3d prev = toMetres(getDeltaMovement());
        // W/S held right now counts too: the input packet arrives before this report, but serverStep (which stamps
        // lastMainBurnTick) only runs in the next entity tick, so the first tick of every burn would be clamped.
        Input in = pilot.getLastClientInput();
        boolean mainBurning = hydrogen() > 0 && (in.forward() || in.backward() || tickCount - lastMainBurnTick <= ticks + 1);
        double maxDv = ShipFlightModel.maxDeltaVPerTick(mainBurning, gravity().length()) * ticks * 1.1 + 0.5;
        Vector3d total = new Vector3d(v).add(moved);
        Vector3d dv = new Vector3d(total).sub(prev);
        if (dv.length() > maxDv) {
            total.set(prev).fma(maxDv / dv.length(), dv);
            // Split what is left the way the client splits it.
            v.set(total);
            moved = level().dimension() == Galactica.SPACE ? ShipFlightModel.krakensbane(v) : new Vector3d();
        }
        if (v.length() > ShipFlightModel.MAX_SPEED) v.mul(ShipFlightModel.MAX_SPEED / v.length());
        if (moved.lengthSquared() > 0) {
            double[] g = Frames.mcToGalactic(moved.x, moved.y, moved.z);
            NavState nav = pilot.getAttachedOrCreate(Galactica.NAV);
            double[] f = nav.frameVelocity();
            NavState next = nav.withFrame(new double[] {f[0] + g[0], f[1] + g[1], f[2] + g[2]}, nav.anchor());
            pilot.setAttached(Galactica.NAV, next);
            carryCrew(pilot, nav.origin(), next);
        }
        // The pilot ignores the tracker's velocity echo, so a clamp has to be told explicitly.
        if (v.distance(velocity.x(), velocity.y(), velocity.z()) >= PilotReconciler.EPSILON)
            ServerPlayNetworking.send(pilot, new ShipCorrectionPayload(getId(), seq, new Vector3f((float) v.x, (float) v.y, (float) v.z)));

        Quaterniond turned = ShipFlightModel.clampAttitude(new Quaterniond(attitude.x, attitude.y, attitude.z, attitude.w),
                new Quaterniond(q.x(), q.y(), q.z(), q.w()), ticks);
        attitude.set((float) turned.x, (float) turned.y, (float) turned.z, (float) turned.w).normalize();
        entityData.set(DATA_ATTITUDE, new Quaternionf(attitude));
        coupled = assist;
        entityData.set(DATA_COUPLED, assist);
        setDeltaMovement(v.x * ShipFlightModel.TICK, v.y * ShipFlightModel.TICK, v.z * ShipFlightModel.TICK);
        syncRotationFields();
    }

    // ---------------------------------------------------------------- riding

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        // A rider's eye sits inside the hull box, where vanilla's pick finds this ship at distance 0.
        if (player.getVehicle() == this) return InteractionResult.PASS;
        InteractionResult r = super.interact(player, hand, location);
        if (r != InteractionResult.PASS) return r;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty() && service(player, stack)) return InteractionResult.SUCCESS;
        if (player.isSecondaryUseActive() || !canAddPassenger(player)) return InteractionResult.PASS;
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        return player.startRiding(this) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
    }

    /** Refuel and repair by hand: hydrogen cells, exotic matter, iron ingots. One item per click. */
    private boolean service(Player player, ItemStack stack) {
        Item hydrogenCell = item("hydrogen_cell"), exoticMatter = item("exotic_matter");
        boolean ok;
        if (hydrogenCell != Items.AIR && stack.is(hydrogenCell)) ok = hydrogen() <= MAX_HYDROGEN - 1;
        else if (exoticMatter != Items.AIR && stack.is(exoticMatter)) ok = exotic() < MAX_EXOTIC;
        else if (stack.is(Items.IRON_INGOT)) ok = hull() < MAX_HULL;
        else return false;
        if (!ok) return false;
        if (!level().isClientSide()) {
            if (stack.is(Items.IRON_INGOT)) setHull(hull() + HULL_PER_IRON);
            else if (stack.is(hydrogenCell)) setHydrogen(hydrogen() + 1F);
            else setExotic(exotic() + 1);
            stack.consume(1, player);
            player.sendOverlayMessage(Component.translatable("hud.galactica.ship.serviced",
                    String.format("%.1f", hydrogen()), exotic(), Math.round(hull())));
        }
        return true;
    }

    /** Content items are looked up by id so this area compiles without the content area's classes. */
    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Galactica.id(path));
    }

    /** Crew never hit their own hull: their eye is inside its box. */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        return hasPassenger(attacker);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 2;
    }

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player p ? p : null;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        boolean pilot = getFirstPassenger() == passenger;
        Vector3f seat = attitude.transform(new Vector3f(pilot ? PILOT_SEAT : PASSENGER_SEAT));
        Vec3 attach = passenger.getVehicleAttachmentPoint(this);
        move.accept(passenger, getX() + seat.x - attach.x, getY() + PIVOT + seat.y - attach.y, getZ() + seat.z - attach.z);
        if (pilot) {
            // The pilot looks down the nose: keeps warp heading (look vector) and the rider model aligned.
            passenger.setYRot(getYRot());
            passenger.setXRot(getXRot());
            if (passenger instanceof LivingEntity l) {
                l.setYHeadRot(getYRot());
                l.setYBodyRot(getYRot());
            }
        }
    }

    /**
     * Where a rider steps out, on the ship's level heading whatever its roll or pitch (a ship landed on its back still
     * lets the crew out beside it, not under it), in order: port and starboard beside the cockpit (ahead of the wing
     * roots, clear of the box), ahead of the nose, behind the tail. On a planet each spot must have a floor
     * ({@link DismountHelper}, a block up or down); in space just room for the rider. Otherwise on top of the hull.
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        double yaw = Math.toRadians(getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double side = ShipDimensions.BOX_WIDTH / 2.0 + 0.8, nose = ShipDimensions.HULL_REACH + 1.0;
        double[][] spots = {{side, 1.0}, {-side, 1.0}, {0, nose}, {0, -nose}};
        boolean space = level().dimension() == Galactica.SPACE;
        for (double[] s : spots) {
            // Body +X (port) is up × nose = (fz, 0, -fx) on the level heading.
            double x = getX() + s[0] * fz + s[1] * fx, z = getZ() - s[0] * fx + s[1] * fz;
            if (space) {
                Vec3 at = new Vec3(x, getY(), z);
                if (DismountHelper.canDismountTo(level(), at, passenger, Pose.STANDING)) return at;
                continue;
            }
            BlockPos base = BlockPos.containing(x, getY(), z);
            for (int dy : new int[] {0, 1, -1, 2, -2}) {
                Vec3 at = DismountHelper.findSafeDismountLocation(passenger.getType(), level(), base.above(dy), true);
                if (at != null) return at;
            }
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        boolean wasPilot = getControllingPassenger() == passenger;
        super.removePassenger(passenger);
        if (AUTOTEST) Galactica.LOG.info("[autotest] ship {} lost {} {} ({})", getId(), wasPilot ? "pilot" : "passenger",
                passenger.getName().getString(), level().isClientSide() ? "client" : "server");
        // The jump is the pilot's order: it dies with their leaving, even if the passenger slides into the seat.
        if (!level().isClientSide() && wasPilot && pendingJump != null) {
            cancelSpool();
            if (passenger instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.aborted"));
        }
    }

    /**
     * Server side, in space, a ship is "always ticking" for the entity manager, exactly like a player
     * ({@code PersistentEntitySectionManager.getEffectiveStatus}). Vanilla stops tracking an entity the moment it
     * moves into a chunk that is not loaded to FULL yet ({@code Callback.updateStatus} -> {@code stopTracking} ->
     * {@code ChunkMap.removeEntity} -> remove packet to every watcher): a ship flying 12-15 blocks per tick
     * through a freshly recentred, still-loading area was removed from its own pilot's client, which dropped the
     * pilot out and re-added both a tick later (the blinking hull and the camera jumping to the bare player and
     * back). The flag is fixed for the lifetime of the entity (one instance never changes level), so the manager's
     * bookkeeping stays consistent; ticking itself is still gated by the entity-ticking range in ServerLevel, and a
     * parked ship is still saved and unloaded with its chunk.
     */
    @Override
    public boolean isAlwaysTicking() {
        return !level().isClientSide() && level().dimension() == Galactica.SPACE;
    }

    public static boolean isPiloting(Player p) {
        return p.getVehicle() instanceof Starship s && s.getControllingPassenger() == p;
    }

    // ---------------------------------------------------------------- integration hooks (NavServer)

    /**
     * Warp-drive fuel gate, called by NavServer each tick the drive is above idle. Warp is only for pilots (or
     * creative players on EVA); above 1 000 c it burns 1 hydrogen per 10 s.
     *
     * @return false if the drive must cut out this tick
     */
    public static boolean consumeWarpFuel(ServerPlayer p, double speed) {
        if (!(p.getVehicle() instanceof Starship s) || s.getControllingPassenger() != p) return p.isCreative();
        if (p.isCreative()) return true;
        double cost = DriveRules.warpHydrogenPerTick(speed);
        if (cost == 0) return true;
        if (s.hydrogen() < cost) return false;
        s.setHydrogen(s.hydrogen() - (float) cost);
        return true;
    }

    /** A player aboard a ship but not at its controls: their galactic frame is driven by the pilot's. */
    public static boolean isCrew(Player p) {
        return p.getVehicle() instanceof Starship s && s.getControllingPassenger() != p;
    }

    /**
     * The player whose frame the ship's floating origin follows: the pilot, or, when nobody (or no player) is at
     * the controls, the first player aboard, so a drifting ship with only crew aboard still recentres.
     */
    public static boolean leadsCraft(Player p) {
        if (!(p.getVehicle() instanceof Starship s)) return false;
        if (s.getControllingPassenger() instanceof Player pilot) return pilot == p;
        for (Entity e : s.getPassengers()) if (e instanceof Player first) return first == p;
        return false;
    }

    /**
     * Keeps everyone aboard in the pilot's frame. NavServer calls this right after writing the pilot's NavState
     * (warp step, floating-origin recentre, see {@code FloatingOrigin}): every other player aboard gets the same origin displacement plus
     * the pilot's warp speed and clock, so the crew sees the same sky and arrives together. NavServer skips crew
     * in its own drive logic ({@link #isCrew}), so in space this is their only frame update.
     */
    public static void carryCrew(ServerPlayer pilot, GalacticPos oldOrigin, NavState pilotNow) {
        if (!(pilot.getVehicle() instanceof Starship ship) || !leadsCraft(pilot)) return;
        double[] d = pilotNow.origin().metresFrom(oldOrigin);
        for (Entity e : ship.getPassengers()) {
            if (e instanceof ServerPlayer crew && crew != pilot) {
                NavState nav = crew.getAttachedOrCreate(Galactica.NAV);
                crew.setAttached(Galactica.NAV, new NavState(nav.origin().plusMetres(d[0], d[1], d[2]),
                        pilotNow.speed(), nav.maxSpeed(), pilotNow.timeSeconds(),
                        pilotNow.frameVx(), pilotNow.frameVy(), pilotNow.frameVz(), pilotNow.anchor()));
            }
        }
    }

    /**
     * A rider's eye in world space: the seat, and the eye's height above it (eye height less the attachment
     * height), both rotate with the hull, so a rolled or inverted ship keeps the first-person view inside the canopy
     * (the renderer turns the rider's body about the seat the same way).
     */
    public Vec3 riderEyePosition(Entity rider, float partialTick, float eyeHeight) {
        Vec3 attach = rider.getVehicleAttachmentPoint(this);
        Vector3f seat = new Vector3f(getFirstPassenger() == rider ? PILOT_SEAT : PASSENGER_SEAT);
        Vector3f eye = getAttitude(partialTick, new Quaternionf()).transform(seat.add((float) -attach.x, eyeHeight - (float) attach.y, (float) -attach.z));
        return getPosition(partialTick).add(eye.x, PIVOT + eye.y, eye.z);
    }

    /** The pilot's eye in world space ({@link #riderEyePosition}). */
    public Vec3 pilotEyePosition(Entity pilot, float partialTick, float eyeHeight) {
        return riderEyePosition(pilot, partialTick, eyeHeight);
    }

    // ---------------------------------------------------------------- damage

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved()) return true;
        if (isInvulnerableToBase(source)) return false;
        setHurtDir(-getHurtDir());
        setHurtTime(10);
        markHurt();
        if (source.getEntity() instanceof Player p && p.getAbilities().instabuild) {
            discard();
            return true;
        }
        setHull(hull() - amount * HULL_PER_DAMAGE);
        if (hull() <= 0) destroy(level, source);
        return true;
    }

    @Override
    protected Item getDropItem() {
        return ShipEntities.SHIP_BEACON;
    }

    // ---------------------------------------------------------------- physics plumbing

    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return LinearInterpolationHandler.create(this, 3);
    }

    /** Gravity is applied by the flight model and {@link #coastStep}, never by vanilla's per-tick pull. */
    @Override
    protected double getDefaultGravity() {
        return 0.0;
    }

    @Override
    public boolean isFlyingVehicle() {
        return true;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // ---------------------------------------------------------------- persistence

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.store("attitude", ExtraCodecs.QUATERNIONF, new Quaternionf(attitude));
        out.putFloat("hydrogen", hydrogen());
        out.putInt("exotic", exotic());
        out.putFloat("hull", hull());
        out.putBoolean("coupled", coupled);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        in.read("attitude", ExtraCodecs.QUATERNIONF).ifPresent(this::setAttitude);
        setHydrogen(in.getFloatOr("hydrogen", 0F));
        setExotic(in.getIntOr("exotic", 0));
        setHull(in.getFloatOr("hull", MAX_HULL));
        coupled = in.getBooleanOr("coupled", true);
        entityData.set(DATA_COUPLED, coupled);
    }
}
