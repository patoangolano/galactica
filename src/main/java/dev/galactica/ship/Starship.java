package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import dev.galactica.nav.NavState;
import dev.galactica.science.Units;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
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

    public static final float MAX_HYDROGEN = 400F, MAX_HULL = 100F;
    public static final int MAX_EXOTIC = 64;
    /** A freshly deployed ship carries a shakedown load: 50 cells, ~17 min of main engine. */
    public static final float DEPLOY_HYDROGEN = 50F;
    /** Hull points per point of incoming damage: a ship survives ~20 unarmed punches, a creeper twice. */
    private static final float HULL_PER_DAMAGE = 5F;
    private static final float HULL_PER_IRON = 10F;
    /** Seats in the body frame, blocks from the model pivot (0.75 above the entity origin). */
    private static final Vector3fc PILOT_SEAT = new Vector3f(0F, -0.45F, 0.75F), PASSENGER_SEAT = new Vector3f(0F, -0.45F, -0.55F);
    private static final double PIVOT = 0.75;

    /**
     * Gravity at the ship, m/s², per level. Space is free fall (the Neighbourhood's GM/r² is ~10⁻³ m/s² even
     * in low orbit at block scale and is left out); other levels default to Earth. Planet surfaces replace this.
     */
    private static ToDoubleFunction<Level> gravityModel = l -> l.dimension() == Galactica.SPACE ? 0.0 : Units.EARTH_GRAVITY;

    private final Quaternionf attitude = new Quaternionf(), attitudeO = new Quaternionf();
    // Pilot-side prediction inputs, fed by the client every tick before the entity ticks.
    private Input pilotInput = Input.EMPTY;
    private float rollAxis, mouseYaw, mousePitch;
    private boolean coupled = true;
    private float localThrottle;
    // Server-side bookkeeping.
    private @Nullable JumpTargetInfo pendingJump;
    private int lastReportTick;

    public Starship(EntityType<? extends Starship> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    public static void setGravityModel(ToDoubleFunction<Level> model) {
        gravityModel = model;
    }

    public static double gravityAt(Level level) {
        return gravityModel.applyAsDouble(level);
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
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (!level().isClientSide() || isLocalInstanceAuthoritative()) return;
        if (DATA_ATTITUDE.equals(key)) attitude.set(entityData.get(DATA_ATTITUDE));
        else if (DATA_COUPLED.equals(key)) coupled = entityData.get(DATA_COUPLED);
    }

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

    public Quaternionf getAttitude(float partialTick, Quaternionf dest) {
        return attitudeO.slerp(attitude, partialTick, dest);
    }

    /** Sets the attitude everywhere at once (deploy, commands). */
    public void setAttitude(Quaternionfc q) {
        attitude.set(q).normalize();
        attitudeO.set(attitude);
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
        super.tick();
        if (isLocalInstanceAuthoritative()) {
            if (level().isClientSide()) pilotStep();
            else coastStep();
            move(MoverType.SELF, getDeltaMovement());
            if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.7, 1.0, 0.7)); // landing-skid friction
        }
        if (!level().isClientSide()) serverStep();
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
        ShipFlightModel.step(q, v, c, new Vector3d(0, -gravityAt(level()), 0), hydrogen(), ShipFlightModel.TICK);
        attitude.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
        localThrottle = hydrogen() > 0 ? (float) c.thrust() : 0F;
        setDeltaMovement(v.x * ShipFlightModel.TICK, v.y * ShipFlightModel.TICK, v.z * ShipFlightModel.TICK);
        syncRotationFields();
    }

    /** Nobody at the controls (server): free fall plus whatever velocity it had. */
    private void coastStep() {
        Vec3 d = getDeltaMovement();
        double g = gravityAt(level()) * ShipFlightModel.TICK * ShipFlightModel.TICK;
        setDeltaMovement(d.x, onGround() ? Math.max(0, d.y) - g : d.y - g, d.z);
        entityData.set(DATA_THRUST, 0F);
    }

    private void serverStep() {
        ServerPlayer pilot = getControllingPassenger() instanceof ServerPlayer sp ? sp : null;
        if (pilot != null) {
            Input in = pilot.getLastClientInput();
            float throttle = (in.forward() ? 1F : 0F) - (in.backward() ? 1F : 0F);
            if (hydrogen() <= 0) throttle = 0;
            if (throttle != 0) setHydrogen(hydrogen() - Math.abs(throttle) * (float) (ShipFlightModel.TICK / ShipFlightModel.MAIN_SECONDS_PER_HYDROGEN));
            if (entityData.get(DATA_THRUST) != throttle) entityData.set(DATA_THRUST, throttle);
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
     * Accepts the pilot's predicted attitude and velocity, clamped to what 2.5 MN + RCS + gravity could do in
     * the ticks since the last report. The server keeps the velocity so vanilla's "moved too quickly" check
     * compares the reported move against the ship's real speed, not against zero.
     */
    void acceptPilotReport(ServerPlayer pilot, Quaternionfc q, Vector3fc velocity, boolean assist) {
        if (!q.isFinite() || !velocity.isFinite() || q.lengthSquared() < 1e-6) return;
        int now = tickCount;
        int ticks = Math.clamp(now - lastReportTick, 1, 20);
        lastReportTick = now;

        Vector3d v = new Vector3d(velocity.x(), velocity.y(), velocity.z());
        Vector3d prev = toMetres(getDeltaMovement());
        double maxDv = ShipFlightModel.maxDeltaVPerTick(hydrogen() > 0, gravityAt(level())) * ticks * 1.1 + 0.5;
        Vector3d dv = new Vector3d(v).sub(prev);
        if (dv.length() > maxDv) v.set(prev).fma(maxDv / dv.length(), dv);
        if (v.length() > ShipFlightModel.MAX_SPEED) v.mul(ShipFlightModel.MAX_SPEED / v.length());

        attitude.set(q).normalize();
        entityData.set(DATA_ATTITUDE, new Quaternionf(attitude));
        coupled = assist;
        entityData.set(DATA_COUPLED, assist);
        setDeltaMovement(v.x * ShipFlightModel.TICK, v.y * ShipFlightModel.TICK, v.z * ShipFlightModel.TICK);
        syncRotationFields();
    }

    // ---------------------------------------------------------------- riding

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
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

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // Step out beside the hull (port side), not into the cockpit glass.
        Vector3f side = attitude.transform(new Vector3f(2.2F, 0F, 0F));
        return new Vec3(getX() + side.x, getY() + Math.max(0, side.y), getZ() + side.z);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && getControllingPassenger() == null) cancelSpool();
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

    /**
     * Floating-origin recentre for a pilot: NavServer folds {@code -delta} into the pilot's galactic origin and
     * calls this instead of teleporting the player (a plain player teleport would dismount them). The ship moves
     * by {@code delta} with its riders, and every other player aboard gets the same origin shift so the crew
     * stays in one frame.
     */
    public static void shiftWithOrigin(ServerPlayer p, Vec3 delta) {
        if (!(p.getVehicle() instanceof Starship ship) || !(ship.level() instanceof ServerLevel level)) return;
        double[] g = Frames.mcToGalactic(-delta.x, -delta.y, -delta.z);
        for (Entity e : ship.getPassengers()) {
            if (e instanceof ServerPlayer crew && crew != p) {
                NavState nav = crew.getAttachedOrCreate(Galactica.NAV);
                crew.setAttached(Galactica.NAV, nav.withOrigin(nav.origin().plusMetres(g[0], g[1], g[2])));
            }
        }
        ship.teleport(new TeleportTransition(level, ship.position().add(delta), ship.getDeltaMovement(),
                ship.getYRot(), ship.getXRot(), TeleportTransition.DO_NOTHING));
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
