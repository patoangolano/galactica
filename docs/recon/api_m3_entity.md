[@aigoatsamuel]

The compile check passed, and two items in the sheet needed fixing. All 10 ship classes compile with JDK 25 (`--release 25`) against `minecraft-merged-deobf-26.3.jar` plus a fresh classpath (exit 0, one deprecation warning). A second probe file for the remaining API claims also compiles (exit 0). One more probe covers the two Loom-only APIs. It fails on the raw jar, as expected, and passes on the project's Loom-processed jar, where all 12 classes compile.

**What I did differently from the task text:** I did not use "every jar under net.fabricmc.fabric-api". The cache holds several versions of some modules, for example `fabric-rendering-v1` 3.0.8, which is for 1.20.1. So I pinned the 46 module jars to the versions in the `fabric-api-0.161.0+26.3` pom. The rest of the classpath is the newest cached jar of each Minecraft library, plus fabric-loader 0.19.5 and sponge-mixin 0.17.4. That makes 74 entries in total.

**Files** (all in `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/apicheck_entity/`):
- `cp.txt` — the classpath
- `src/` — the 10 ship sources
- `probe/dev/galactica/probe/ApiProbe.java` — checks the remaining API claims
- `probe_loom/dev/galactica/probe/LoomProbe.java` — checks the two Loom-only APIs
- `cls/` — output against the raw jar
- `clsL/` — output against the Loom jar

The Loom-processed jar is `~/MODS CLAUDE/galactica/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7e9a32a5b8/26.3/minecraft-merged-7e9a32a5b8-26.3.jar`.

---

# Minecraft 26.3 / Fabric 0.161.0+26.3 — rideable, pilotable ship entity (verified)

**Compile check: PASSED**
- **Ship sources against the raw merged jar:** exit 0, 10 classes. The only warning is that `EntityRendererRegistry` is deprecated (see §4).
- **`ApiProbe.java` against the raw jar:** exit 0. It covers every API in §§1–5 that is not already in the ship sources.
- **`LoomProbe.java` against the raw jar:** fails, as expected. `EntityRenderers.register` is private there and `alwaysUpdateVelocity` cannot be found.
- **Everything against the Loom-processed jar:** exit 0, 12 classes. This proves that the Fabric access widener (makes a private member public) and the injected interface (adds methods to a vanilla class) are both applied there.
- **Not done:** nothing has been run in game. Mixin targets are confirmed by javap and by source, not by running Mixin.

## 1. EntityType registration

`EntityType$Builder<T>` — CONFIRMED (javap):
- `static <T extends Entity> Builder<T> of(EntityFactory<T>, MobCategory)`
- `sized(float,float)`, `eyeHeight(float)`
- `passengerAttachments(float...)` and `passengerAttachments(Vec3...)`
- `vehicleAttachment(Vec3)`, `ridingOffset(float)`
- `attach(EntityAttachment, float, float, float)`
- `noSummon()`, `noSave()`, `fireImmune()`, `noLootTable()`, `notInPeaceful()`
- `clientTrackingRange(int)`, `updateInterval(int)`, `noUpdateInterval()`, `dontTrackDeltas()`
- `EntityType<T> build(ResourceKey<EntityType<?>>)`

FIXED (the list was incomplete). The Builder also has:
- `createNothing(MobCategory)`
- `spawnDimensionsScale(float)`
- `nameTagOffset(float)`
- `attach(EntityAttachment, Vec3)`
- `immuneTo(TagKey<Block>)`
- `canSpawnFarFromPlayer()`
- `requiredFeatures(FeatureFlag...)`

`build(key)` — CONFIRMED (source):
- When `serialize` is set, it calls `Util.fetchChoiceType(References.ENTITY_TREE, key.identifier().toString())`.
- The description id and the loot table are derived from the key.
- A data-fixer warning for a modded id: UNCONFIRMED (not run).

`EntityType.EntityFactory<T>`: `T create(EntityType<T>, Level)` — CONFIRMED.

`EntityTypes.register(ResourceKey, Builder)` — CONFIRMED (private static). Its body is `Registry.register(BuiltInRegistries.ENTITY_TYPE, id, builder.build(id))`.

`EntityTypeIds` builds keys with `ResourceKey.create(Registries.ENTITY_TYPE, Identifier.withDefaultNamespace(name))` — CONFIRMED.

The boat uses `.noLootTable().sized(1.375F, 0.5625F).eyeHeight(0.5625F).clientTrackingRange(10)` — CONFIRMED.

Fabric (object-builder 24.1.9):
- **`FabricEntityTypeBuilder` does not exist** — CONFIRMED.
- **`FabricEntityType$Builder<T>`** — FIXED (clarified). It provides the defaults `alwaysUpdateVelocity(boolean)` and `canPotentiallyExecuteCommands(boolean)`, plus the statics `createLiving(factory, category, UnaryOperator<Builder.Living<T>>)` and `createMob(factory, category, UnaryOperator<Builder.Mob<T>>)`.
  - It reaches the vanilla `EntityType.Builder` through a `transitive-inject-interface` line in the classtweaker.
  - That interface exists only in the Loom-processed jar. There the Builder is declared `implements FabricEntityType$Builder<T>`, and `LoomProbe` compiles.
  - The raw `minecraft-merged-deobf` jar does not have it.
- **`FabricEntityDataRegistry.register(Identifier, EntityDataSerializer<?>)`** — CONFIRMED. It also has `get(Identifier)` and `getId(EntityDataSerializer<?>)`.

```java
// dev/galactica/ship/ShipEntities.java  (COMPILED)
public static final ResourceKey<EntityType<?>> SHIP_KEY =
    ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("galactica", "ship"));
public static final EntityType<ShipEntity> SHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, SHIP_KEY,
    EntityType.Builder.<ShipEntity>of(ShipEntity::new, MobCategory.MISC)
        .noLootTable().sized(3.0F, 1.5F).eyeHeight(1.0F).clientTrackingRange(10).build(SHIP_KEY));
public static void init() {}   // touch from Galactica.onInitialize
```

Spawning from code: `T create(Level, EntitySpawnReason)` with `EntitySpawnReason.SPAWN_ITEM_USE` or `COMMAND` — CONFIRMED. FIXED (addition): there are also the overloads `create(Level, EntitySpawnRequest)` and `create(ServerLevel, PostSpawnProcessor<T>, BlockPos, EntitySpawnReason, boolean, boolean)`.

`/summon galactica:ship` and the lang key `entity.galactica.ship`: UNCONFIRMED (not run). They follow from no `noSummon()` and from `descriptionId` being derived from the key.

## 2. Vehicle entity

### `Entity` abstract methods — CONFIRMED (javap)
```
protected abstract void defineSynchedData(SynchedEntityData.Builder)
protected abstract void readAdditionalSaveData(ValueInput)
protected abstract void addAdditionalSaveData(ValueOutput)
public abstract boolean hurtServer(ServerLevel, DamageSource, float)
```

### `VehicleEntity extends Entity` — CONFIRMED
- Constructor: `VehicleEntity(EntityType<?>, Level)`.
- Synched data, all `protected static final`:
  - `DATA_ID_HURT` (Integer)
  - `DATA_ID_HURTDIR` (Integer)
  - `DATA_ID_DAMAGE` (Float)
- `defineSynchedData` is overridden here, so a subclass must call `super`.
- Accessors: `setHurtTime`, `getHurtTime`, `setHurtDir`, `getHurtDir`, `setDamage`, `getDamage`.
- `public destroy(ServerLevel, Item)`.
- `protected abstract Item getDropItem()`.
- `getDimensionChangingDelay()` returns 10.
- `hurtClient` returns true.

FIXED (more precise). In `hurtServer`:
- Damage accumulates as `damage * 10`.
- Above 40 the vehicle is destroyed with `destroy(level, source)`, which drops the item. `shouldSourceDestroy(source)` also triggers a destroy.
- A creative-mode attacker causes `discard()` instead, with no drop.
- Also present: `protected shouldSourceDestroy(DamageSource)`, `public ignoreExplosion(Explosion)` and `protected destroy(ServerLevel, DamageSource)`.

A subclass still owes `readAdditionalSaveData`, `addAdditionalSaveData` and `getDropItem` — CONFIRMED.

### `Entity` signatures — CONFIRMED (javap)
```
public InteractionResult interact(Player, InteractionHand, Vec3)                 // 3 args
public LivingEntity getControllingPassenger()
protected boolean canAddPassenger(Entity)
protected void positionRider(Entity, Entity.MoveFunction); public final void positionRider(Entity)
  Entity.MoveFunction: void accept(Entity, double, double, double)
protected Vec3 getPassengerAttachmentPoint(Entity, EntityDimensions, float)
public Vec3 getVehicleAttachmentPoint(Entity)
public void onPassengerTurned(Entity)
public boolean isPickable(); public boolean isPushable(); public boolean canBeCollidedWith(Entity)
protected double getDefaultGravity(); public final double getGravity(); protected void applyGravity()
public void setNoGravity(boolean); public boolean isNoGravity()
public boolean isFlyingVehicle()
protected InterpolationHandler createInterpolationHandler()
public final boolean isLocalInstanceAuthoritative(); public boolean isClientAuthoritative(); protected boolean isLocalClientAuthoritative()
public final boolean canSimulateMovement(); public MoveSimulationType getMoveSimulationType()   // SERVER_AND_CLIENT | AUTHORITATIVE_SIDE | AUTHORITATIVE_SIDE_AND_SERVER
public void onSyncedDataUpdated(EntityDataAccessor<?>)   (+ overload taking List<DataValue<?>>)
public final void moveOrInterpolateTo(Vec3, float, float)   (+ PositionPath overloads, (float,float))
public void tick(); public void baseTick(); public final void commonTick()
public void setXRot(float)          // source: Math.clamp(xRot % 360, -90, 90); non-finite values are discarded
public void absSnapTo(double,double,double,float,float); public void absSnapRotationTo(float,float)
public final Vec3 getPosition(float); public float getYRot(float); public float getXRot(float)
public boolean blocksBuilding
public void turn(double, double)
```

`getGravity()` returns `isNoGravity() ? 0 : getDefaultGravity()`. `applyGravity()` adds `-getGravity()` to the y of deltaMovement. CONFIRMED (source).

### Authority model — CONFIRMED (source and bytecode)
- `isLocalInstanceAuthoritative()` returns `level.isClientSide() ? isLocalClientAuthoritative() : !isClientAuthoritative()`.
- `Entity.isLocalClientAuthoritative()` returns `passenger != null && passenger.isLocalClientAuthoritative()`.
- `Entity.isClientAuthoritative()` returns `passenger != null && passenger.isClientAuthoritative()`.
- `Player.isLocalClientAuthoritative()` (protected) returns `isLocalPlayer()`.
- `Player.isClientAuthoritative()` returns `true`.

AbstractBoat — CONFIRMED:
- `createInterpolationHandler()` returns `LinearInterpolationHandler.create(this, 3)`. `LinearInterpolationHandler` also has `create(Entity)`.
- `getDefaultGravity()` returns `0.04`.
- `isPickable()` returns `!isRemoved()`.
- `canAddPassenger` returns `size < getMaxPassengers() && !isEyeInFluid(WATER)`.
- `clampRotation` (protected float) caps the passenger's head turn at ±105°.

FIXED (more precise): `AbstractBoat.interact` also returns PASS when `outOfControlTicks >= 60`.

### Input path — CONFIRMED
- **Client:** `LocalPlayer.rideTick()` calls `boat.setInput(keyPresses.left(), right(), forward(), backward())` when `getControlledVehicle() instanceof AbstractBoat`.
- **Types:**
  - `LocalPlayer.input` is a `public ClientInput`.
  - `ClientInput.keyPresses` is a `public Input`.
  - `Input` is a record with `forward`, `backward`, `left`, `right`, `jump`, `shift`, `sprint`; `Input.EMPTY` is the empty value.
- **Sync, in `LocalPlayer.sendChanges()`:**
  - Sends `ServerboundPlayerInputPacket` when the input changes.
  - If `isPassenger()`, sends `Rot(yRot, xRot, onGround, horizontalCollision)`.
  - Then, if `vehicle != this && vehicle.isLocalInstanceAuthoritative()`, sends `ServerboundMoveVehiclePacket.fromEntity(vehicle)`. That packet is the record `(PositionAndRotation movingTo, boolean onGround)`.
- **View rotation:** FIXED (addition). `LocalPlayer.getViewYRot(pt)` is overridden to return `getYRot(pt)`.
- **Server input:** `ServerPlayer.getLastClientInput()` returns `Input` (with `setLastClientInput`).

`handleMoveVehicle` — CONFIRMED (source), with these FIXES:
- **Controller check:** it requires `vehicle.getControllingPassenger() == player && vehicle == lastVehicle`.
- **"moved too quickly":** rejects when `movedDistSq - vehicle.getDeltaMovement().lengthSqr() > 100 && !isSingleplayerOwner()`.
- **Move:** calls `vehicle.move(MoverType.PLAYER, target - lastGood)`.
- **"moved wrongly" (FIXED):**
  - The threshold is the *squared* error `> 0.0625`, which is 0.25 blocks.
  - The vertical error is always zeroed, because the check `yDist > -0.5 || yDist < 0.5` is always true. Only the horizontal error counts.
  - The snap-back happens when `(fail && level.noCollision(vehicle, oldAABB)) || isEntityCollidingWithAnythingNew(...)`.
- **Success:** calls `absSnapTo(x, y, z, yRot, xRot)`, so xRot is clamped to ±90.
- **Anti-fly (FIXED):**
  - The float check is `clientVehicleIsFloating = yDist >= -0.03125 && !restsOnSomething && !allowFlight && !isFlyingVehicle() && !isNoGravity() && noBlocksAround`.
  - The vehicle is kicked once `aboveGroundVehicleTickCount > getMaximumFlyingTicks(vehicle)`.
  - `getMaximumFlyingTicks` returns `Integer.MAX_VALUE` when `getGravity() < 1e-5`. So with `getDefaultGravity() = 0` the kick can never fire.
  - The `isFlyingVehicle() = true` override is therefore redundant. Keep it in case gravity changes later.

### Recommended 6-DOF pattern — COMPILED
The sheet's sketch did not match the compiled source in three places, which I fixed:
- The clamp is written out in full.
- An `orientation()` accessor is added; `ShipClient` uses it.
- `readAdditionalSaveData` also writes the synched-data field.

```java
public class ShipEntity extends VehicleEntity {
  private static final EntityDataAccessor<Quaternionfc> DATA_ORIENTATION =
      SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.QUATERNION);
  private static final float DEG = (float) (Math.PI / 180.0);
  private final Quaternionf orientation = new Quaternionf(), orientationO = new Quaternionf(); // local fwd=+Z, up=+Y
  private Input input = Input.EMPTY; private float mouseYawDeg, mousePitchDeg;

  public ShipEntity(EntityType<? extends ShipEntity> t, Level l) { super(t, l); this.blocksBuilding = true; }
  @Override protected void defineSynchedData(SynchedEntityData.Builder b) { super.defineSynchedData(b); b.define(DATA_ORIENTATION, new Quaternionf()); }
  public void setInput(Input in) { input = in; }
  public void addMouseInput(float yawDeg, float pitchDeg) { mouseYawDeg += yawDeg; mousePitchDeg += pitchDeg; }
  public void acceptPilotOrientation(Quaternionfc q) { if (!q.isFinite()) return; orientation.set(q).normalize(); entityData.set(DATA_ORIENTATION, new Quaternionf(orientation)); }
  @Override public void onSyncedDataUpdated(EntityDataAccessor<?> a) { super.onSyncedDataUpdated(a);
    if (DATA_ORIENTATION.equals(a) && level().isClientSide() && !isLocalInstanceAuthoritative()) orientation.set(entityData.get(DATA_ORIENTATION)); }
  public Quaternionfc orientation() { return orientation; }
  public Quaternionf getOrientation(float pt, Quaternionf dest) { return orientationO.slerp(orientation, pt, dest); }

  @Override public void tick() {
    if (getHurtTime() > 0) setHurtTime(getHurtTime() - 1); if (getDamage() > 0) setDamage(getDamage() - 1);
    orientationO.set(orientation);
    super.tick();
    if (isLocalInstanceAuthoritative()) { if (level().isClientSide()) pilot(); else setDeltaMovement(getDeltaMovement().scale(0.9)); move(MoverType.SELF, getDeltaMovement()); }
    else setDeltaMovement(Vec3.ZERO);
  }
  private void pilot() {
    float roll = (input.right() ? 3F : 0F) - (input.left() ? 3F : 0F);
    orientation.rotateY(-mouseYawDeg * DEG).rotateX(mousePitchDeg * DEG).rotateZ(roll * DEG).normalize(); mouseYawDeg = mousePitchDeg = 0;
    float thrust = (input.forward() ? .05F : 0) - (input.backward() ? .03F : 0), lift = (input.jump() ? .03F : 0) - (input.sprint() ? .03F : 0);
    Vector3f f = orientation.transform(new Vector3f(0, 0, 1)), u = orientation.transform(new Vector3f(0, 1, 0));
    setDeltaMovement(getDeltaMovement().scale(0.95).add(f.x*thrust+u.x*lift, f.y*thrust+u.y*lift, f.z*thrust+u.z*lift));
    setYRot((float) Math.toDegrees(Math.atan2(-f.x, f.z)));
    setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1F, Math.min(1F, f.y)))));
  }
  @Override public InteractionResult interact(Player p, InteractionHand h, Vec3 loc) { var r = super.interact(p, h, loc); if (r != InteractionResult.PASS) return r;
    if (p.isSecondaryUseActive()) return InteractionResult.PASS;
    if (!level().isClientSide()) return p.startRiding(this) ? InteractionResult.SUCCESS : InteractionResult.PASS; return InteractionResult.SUCCESS; }
  @Override protected boolean canAddPassenger(Entity e) { return getPassengers().isEmpty(); }
  @Override public LivingEntity getControllingPassenger() { return getFirstPassenger() instanceof Player p ? p : null; }
  @Override protected void positionRider(Entity e, Entity.MoveFunction mf) { Vector3f s = orientation.transform(new Vector3f(0, .5F, .5F)); Vec3 o = e.getVehicleAttachmentPoint(this);
    mf.accept(e, getX()+s.x-o.x, getY()+s.y-o.y, getZ()+s.z-o.z); }
  @Override protected InterpolationHandler createInterpolationHandler() { return LinearInterpolationHandler.create(this, 3); }
  @Override protected double getDefaultGravity() { return 0.0; }
  @Override public boolean isFlyingVehicle() { return true; }   // redundant at gravity 0 (see anti-fly), harmless
  @Override public boolean isPickable() { return !isRemoved(); }
  @Override public boolean canBeCollidedWith(Entity o) { return true; }
  @Override public boolean isPushable() { return false; }
  @Override protected Item getDropItem() { return Items.IRON_BLOCK; }
  @Override protected void addAdditionalSaveData(ValueOutput out) { out.putFloat("qx", orientation.x); out.putFloat("qy", orientation.y); out.putFloat("qz", orientation.z); out.putFloat("qw", orientation.w); }
  @Override protected void readAdditionalSaveData(ValueInput in) {
    orientation.set(in.getFloatOr("qx",0F), in.getFloatOr("qy",0F), in.getFloatOr("qz",0F), in.getFloatOr("qw",1F)).normalize();
    orientationO.set(orientation); entityData.set(DATA_ORIENTATION, new Quaternionf(orientation)); }
}
```

`ValueInput` / `ValueOutput` — CONFIRMED (javap and probe):
- `getFloatOr(String, float)`, `getIntOr(String, int)`, `putFloat`, `putInt`
- `<T> store(String, Codec<T>, T)` and `<T> Optional<T> read(String, Codec<T>)`, plus MapCodec overloads

`ExtraCodecs.QUATERNIONF` and `VECTOR3F` are `Codec<...fc>` — CONFIRMED (`QUATERNIONF_COMPONENTS` also exists).

C2S payload — COMPILED:

```java
public record ShipOrientationPayload(int entityId, Quaternionfc orientation) implements CustomPacketPayload {
  public static final Type<ShipOrientationPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("galactica", "ship_orientation"));
  public static final StreamCodec<RegistryFriendlyByteBuf, ShipOrientationPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, ShipOrientationPayload::entityId, ByteBufCodecs.QUATERNIONF, ShipOrientationPayload::orientation, ShipOrientationPayload::new);
  @Override public Type<? extends CustomPacketPayload> type() { return TYPE; } }
// common init:
PayloadTypeRegistry.serverboundPlay().register(ShipOrientationPayload.TYPE, ShipOrientationPayload.CODEC);
ServerPlayNetworking.registerGlobalReceiver(ShipOrientationPayload.TYPE, (p, ctx) -> {
  if (ctx.player().level().getEntity(p.entityId()) instanceof ShipEntity s && s.getControllingPassenger() == ctx.player()) s.acceptPilotOrientation(p.orientation()); });
```

`ByteBufCodecs.QUATERNIONF` and `VECTOR3F` are `StreamCodec<ByteBuf, ...>` — CONFIRMED; they compose into a `RegistryFriendlyByteBuf` codec. The server thread the handler runs on: UNCONFIRMED.

Client ticks — CONFIRMED: `ClientTickEvents.START_CLIENT_TICK` / `END_CLIENT_TICK` call `onStartTick(Minecraft)` / `onEndTick(Minecraft)`. COMPILED:

```java
ClientTickEvents.START_CLIENT_TICK.register(mc -> { LocalPlayer p = mc.player;
  if (p != null && p.getVehicle() instanceof ShipEntity s && s.getControllingPassenger() == p) s.setInput(p.input.keyPresses); });
ClientTickEvents.END_CLIENT_TICK.register(mc -> { LocalPlayer p = mc.player;
  if (p != null && p.getVehicle() instanceof ShipEntity s && s.getControllingPassenger() == p && ClientPlayNetworking.canSend(ShipOrientationPayload.TYPE))
    ClientPlayNetworking.send(new ShipOrientationPayload(s.getId(), new Quaternionf(s.orientation()))); });
```

Mouse mixin — COMPILED; target CONFIRMED. `Entity.turn(DD)V` does:
- `xRot += (float) yo * 0.15F`
- `yRot += (float) xo * 0.15F`
- clamps xRot to ±90
- updates `xRotO` and `yRotO`
- calls `vehicle.onPassengerTurned(this)`

`MouseHandler` calling `turnPlayer` and then `LocalPlayer.turn` is UNCONFIRMED in this pass (not re-read).

```java
@Mixin(Entity.class) public abstract class EntityTurnShipMixin {
  @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
  private void galactica$steerShip(double xo, double yo, CallbackInfo ci) {
    if ((Object) this instanceof LocalPlayer p && p.getVehicle() instanceof ShipEntity s && s.getControllingPassenger() == p) {
      s.addMouseInput((float) xo * 0.15F, (float) yo * 0.15F); ci.cancel(); } } }
```

## 3. SynchedEntityData — CONFIRMED
```
static <T> EntityDataAccessor<T> SynchedEntityData.defineId(Class<? extends SyncedDataHolder>, EntityDataSerializer<T>)
SynchedEntityData.Builder: <T> Builder define(EntityDataAccessor<T>, T)
get(acc) / set(acc, v) / set(acc, v, boolean)
EntityDataSerializers: BYTE, INT, FLOAT, BOOLEAN, OPTIONAL_UNSIGNED_INT (OptionalInt), VECTOR3 (Vector3fc), QUATERNION (Quaternionfc)
```

Data flows from server to client only. That this is why orientation has to go through the C2S payload is reasoned, not run.

## 4. Rendering

Registration:
- **`EntityRendererRegistry.register(EntityType<? extends E>, EntityRendererProvider<E>)`** — FIXED. It exists and compiles, but the class is annotated **`@Deprecated`** in rendering-v1 27.0.14. Its impl forwards to a handler.
  - The intended route is vanilla `EntityRenderers.register(EntityType<? extends T>, EntityRendererProvider<T>)`.
  - That method is private in the raw jar, but `fabric-transitive-access-wideners-v1.classtweaker` makes it public: `transitive-accessible method net/minecraft/client/renderer/entity/EntityRenderers register ...`.
  - Confirmed public in the Loom jar, where `LoomProbe` compiles. Use `EntityRenderers.register(ShipEntities.SHIP, ShipRenderer::new)`.
- **`ModelLayerRegistry.registerModelLayer(ModelLayerLocation, TexturedLayerDefinitionProvider)`** — CONFIRMED. The provider is `LayerDefinition createLayerDefinition()`. There is no `EntityModelLayerRegistry` — CONFIRMED.
- **`EntityRendererProvider<T>`**: `EntityRenderer<T,?> create(Context)` — CONFIRMED.
- **`Context`** — CONFIRMED: `bakeLayer(ModelLayerLocation)` returns `ModelPart`, plus `getModelSet()`, `getFont()`, `getAtlas(Identifier)`, `getSprites()`, `getEntityRenderDispatcher()`, `getResourceManager()` and others.

`EntityRenderer<T extends Entity, S extends EntityRenderState>` — CONFIRMED:
```
protected EntityRenderer(Context); public abstract S createRenderState(); public final S createRenderState(T, float)
public void extractRenderState(T, S, float)
public void submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)
protected float shadowRadius, shadowStrength
public boolean shouldRender(T, Frustum, double, double, double, float); protected AABB getBoundingBoxForCulling(T, float); protected boolean affectedByCulling(T)
```

`EntityRenderState` public fields:
- CONFIRMED: `entityType`, `x`, `y`, `z`, `ageInTicks`, `boundingBoxWidth`, `boundingBoxHeight`, `eyeHeight`, `distanceToCameraSq`, `isInvisible`, `isDiscrete`, `displayFireAnimation`, `lightCoords`, `outlineColor`, `shadowRadius`.
- FIXED (addition): `nameTag` and `scoreText` (Component).

Submitting — CONFIRMED. `SubmitNodeCollector extends OrderedSubmitNodeCollector`:
```
default <S> void submitModel(Model<? super S>, S, PoseStack, Identifier, int light, int overlay, int outline)
default <S> void submitModel(Model<? super S>, S, PoseStack, RenderType, int, int, int)
abstract <S> void submitModel(Model<? super S>, S, PoseStack, RenderType, int, int, int, UvMapping, int)   // FIXED: added
```

`AbstractBoatRenderer.submit` — CONFIRMED (source):
1. `pushPose`, `translate(0, .375, 0)`, `rotateDegrees(YP, 180 - yRot)`
2. hurt wobble on XP (`* damageTime / 10 * hurtDir`)
3. bubble rotation
4. `scale(-1, -1, 1)`, `rotateDegrees(YP, 90)`
5. `submitModel(..., texture, lightCoords, NO_OVERLAY, outlineColor)`
6. `submitTypeAdditions`
7. `popPose`, then `super.submit`

`PoseStack` — CONFIRMED:
- `rotate(Quaternionfc)`, `rotate(Axis, float)`, `rotateDegrees(Axis, float)`, `rotateAround(...)`
- `translate` (d/f/Vec3), `scale(f,f,f)`
- `mulPose(Matrix4fc)`, `mulPose(Transformation)`
- `pushPose`, `popPose`

Models — CONFIRMED:
- **`Model<S>`:** the constructor `Model(ModelPart, Function<Identifier, RenderType>)` is **public**, which is a FIX. It also has `setupAnim(S)`, `root()` and `renderType(Identifier)`.
- **`EntityModel`:** two protected constructors, `(ModelPart)` and `(ModelPart, Function)`.
- **`RenderTypes`:** `entityCutout(Identifier)` (also `(Identifier, boolean)`), `entitySolid`, `entityTranslucent` (also a `boolean` variant), `entityCutoutCull`, `entityTranslucentEmissive`.
- **Builders:**
  - `ModelLayerLocation(Identifier model, String layer)` (record)
  - `PartDefinition.addOrReplaceChild(String, CubeListBuilder, PartPose)`
  - `CubeListBuilder.create().texOffs(int,int).addBox(6 floats)`
  - `PartPose.ZERO`, `offset`, `offsetAndRotation`
  - `LayerDefinition.create(MeshDefinition, int, int)`
- **Boat model:** `net.minecraft.client.model.object.boat.BoatModel(ModelPart)` with `createBoatModel()` and `createChestBoatModel()`. The 128x64 texture size is UNCONFIRMED (not read).

The `ShipModel`, `ShipRenderState` and `ShipRenderer` code is COMPILED and unchanged from the sheet. Client init, FIXED:
```java
ModelLayerRegistry.registerModelLayer(ShipModel.LAYER, ShipModel::createLayer);
EntityRenderers.register(ShipEntities.SHIP, ShipRenderer::new);   // Loom (transitive AW); deprecated alternative: EntityRendererRegistry.register
```

## 5. Camera — CONFIRMED (source and javap)
- `Camera.update(DeltaTracker)` calls, in order:
  1. `alignWithEntity(pt)`
  2. `calculateFov` and `calculateHudFov`
  3. `prepareCullFrustum(getViewRotationMatrix(...), ...)`
  4. `setupPerspective`
- `alignWithEntity(F)V` is private and has a single return, so a TAIL inject is safe. It does `setRotation(entity.getViewYRot(pt), entity.getViewXRot(pt))` and `setPosition(...)`.
  - It has a special case for minecarts.
  - When detached: mirrored view gives `setRotation(yRot + 180, -xRot)`, then `move(-getMaxZoom(...), 0, 0)`.
  - When sleeping: a bed rotation.
- `protected setRotation(FF)V` does `rotation.rotationYXZ(PI - yRot*DEG, -xRot*DEG, 0F)`, rotates `FORWARDS`, `UP` and `LEFT`, and sets `matrixPropertiesDirty |= 3`. Roll is hard-coded to 0.
- **Shadows** (types match the mixin):
  - `private static final Vector3fc FORWARDS, UP, LEFT`
  - `private final Quaternionf rotation`
  - `private final Vector3f forwards, up, left`
  - `private int matrixPropertiesDirty`
  - `private Entity entity`
  - `private boolean detached`
  - `private Vec3 position`
  - `protected void move(float, float, float)`
  - `protected void setPosition(Vec3)` and `setPosition(double, double, double)`
  - `private float getMaxZoom(float)`
- **`CameraRenderState`:** public `xRot`, `yRot`, `Quaternionf orientation` and `Matrix4f viewRotationMatrix`.
- **Hand rendering:** `GameRenderer.renderItemInHand` applies `viewRotationMatrix.invert` through `mulPose`, then `mul(viewRotationMatrix)`.
- **Bobbing:** `bobHurt` and `bobView` both use `Axis.ZP`.

`CameraShipMixin` — COMPILED (same as the sheet; the compiled copy also shadows `position`, unused). Its detached branch ignores the mirrored camera mode.

Register the mixins in `galactica.client.mixins.json`. Its `client` array currently holds only `"SkyRendererMixin"`; add `"CameraShipMixin"` and `"EntityTurnShipMixin"`.

Still UNCONFIRMED:
- which consumers of `CameraRenderState.xRot`/`yRot` will ignore the roll
- that the custom sky rolls correctly

## Still UNCONFIRMED / not run
- **In-game behavior:** none of the ship code has run in game. That includes how smooth the interpolation is and how the rider looks while the ship is rolled.
- **Data-fixer warning:** whether `build(key)` logs one for the modded id.
- **Payload handler thread:** which thread the server-side handler runs on.
- **Chase cam:** there is no wall clipping. Adding it needs `@Shadow private float getMaxZoom(float)`.
- **Placing the ship from an item:** it would follow `BoatItem`, which was not read.
- **`MouseHandler.turnPlayer`:** that it calls `LocalPlayer.turn` was not re-read in this pass.