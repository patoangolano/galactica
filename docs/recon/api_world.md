[@aigoatsamuel]

The cheat-sheet for Fabric 26.3 dimensions and server gameplay is below. Vanilla classes were decompiled from `minecraft-merged-deobf-26.3.jar` with vineflower 1.11.2. Fabric API was checked with javap against the jars nested inside `fabric-api-0.161.0+26.3.jar`: command-api-v2 3.1.2, data-attachment-api-v1 2.2.30, networking-api-v1 6.3.8, lifecycle-events-v1 4.1.9. The decompiled-sources folder you pointed to holds only 667 .java files and no DimensionType.java, but its `data/` folder is complete.

Corrections to the computed task:
- The networking methods are `PayloadTypeRegistry.clientboundPlay()` and `serverboundPlay()`. The old `playS2C()` and `playC2S()` no longer exist.
- The world tick event is `END_LEVEL_TICK`. `END_WORLD_TICK` no longer exists.
- `ResourceLocation` is now `net.minecraft.resources.Identifier`.
- Command permissions use `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`. The old int level argument is gone.

## 1. Datapack dimension

### DimensionType codec (`net.minecraft.world.level.dimension.DimensionType`)

`DimensionType` is a record. These are all of its codec fields, from `createDirectCodec`:

| JSON key | Type / range | Required? |
|---|---|---|
| `has_fixed_time` | bool | optional, default false |
| `has_skylight` | bool | **required** |
| `has_ceiling` | bool | **required** |
| `has_ender_dragon_fight` | bool | **required** |
| `coordinate_scale` | double in [1e-5, 3e7] | **required** |
| `min_y` | int in [MIN_Y, MAX_Y]; must be a multiple of 16 | **required** |
| `height` | int in [16, Y_SIZE]; must be a multiple of 16; `min_y + height` ≤ MAX_Y+1 | **required** |
| `logical_height` | int in [0, Y_SIZE]; must be ≤ height | **required** |
| `infiniburn` | block HolderSet (`"#minecraft:infiniburn_end"`) | **required** |
| `ambient_light` | float | **required** |
| `monster_spawn_light_level` | IntProvider in 0..15 (int, or `{"type":"minecraft:uniform",...}`) | **required** (inlined MonsterSettings) |
| `monster_spawn_block_light_limit` | int in 0..15 | **required** |
| `skybox` | `"none"`, `"overworld"` or `"end"` | optional, default `overworld` |
| `cardinal_light` | `"default"` or `"nether"` | optional, default `default` |
| `attributes` | EnvironmentAttributeMap | optional, default empty |
| `timelines` | Timeline HolderSet (`"#minecraft:in_overworld"`) | optional, default empty |
| `default_clock` | world_clock id (`"minecraft:overworld"` or `"minecraft:the_end"`) | optional |

There is no `fixed_time` long and no `natural`, `ultrawarm`, `piglin_safe`, `bed_works`, `has_raids` or `effects`. Those moved into `attributes`, for example `minecraft:gameplay/bed_rule`, `.../respawn_anchor_works`, `.../can_start_raid`, `.../water_evaporates`, `.../fast_lava`.

### Environment attributes (`net.minecraft.world.attribute.EnvironmentAttributes`)

In JSON, `attributes` is a map from `"minecraft:<id>"` to a value. Colours are `"#RRGGBB"`, or `"#AARRGGBB"` for ARGB types.

Visual attributes:

| Attribute | Type | Default |
|---|---|---|
| `visual/fog_color` | RGB | |
| `visual/fog_start_distance` | float | 0 |
| `visual/fog_end_distance` | float | 1024 |
| `visual/sky_fog_end_distance` | float | 512 |
| `visual/cloud_fog_end_distance` | float | 2048 |
| `visual/water_fog_color` | RGB | |
| `visual/water_fog_start_distance` | float | |
| `visual/water_fog_end_distance` | float | |
| `visual/sky_color` | RGB | |
| `visual/sunrise_sunset_color` | ARGB | |
| `visual/cloud_color` | ARGB | 0, i.e. alpha 0 = no clouds |
| `visual/cloud_height` | float | |
| `visual/sun_angle` | degrees | 0 |
| `visual/moon_angle` | degrees | |
| `visual/star_angle` | degrees | |
| `visual/moon_phase` | MoonPhase | |
| `visual/star_brightness` | float in 0..1 | **0** |
| `visual/block_light_tint` | RGB | |
| `visual/sky_light_color` | RGB | |
| `visual/sky_light_factor` | float in 0..1 | 1 |
| `visual/night_vision_color` | RGB | |
| `visual/ambient_light_color` | RGB | |
| `visual/default_dripstone_particle` | particle | |
| `visual/ambient_particles` | list | |

Audio attributes: `audio/background_music`, `audio/music_volume` (0..1), `audio/ambient_sounds`, `audio/firefly_bush_sounds`.

Gameplay attributes:
- `gameplay/sky_light_level` (0..15)
- `gameplay/can_start_raid`, `gameplay/water_evaporates`
- `gameplay/bed_rule`, `gameplay/straw_bed_rule`
- `gameplay/respawn_anchor_works`, `gameplay/nether_portal_spawns_piglin`
- `gameplay/fast_lava`, `gameplay/increased_fire_burnout`, `gameplay/eyeblossom_open`, `gameplay/turtle_egg_hatch_chance`
- `gameplay/piglins_zombify`, `gameplay/snow_golem_melts`, `gameplay/creaking_active`
- `gameplay/surface_slime_spawn_chance`, `gameplay/cat_waking_up_gift_chance`, `gameplay/bees_stay_in_hive`
- `gameplay/monsters_burn`, `gameplay/can_pillager_patrol_spawn`
- `gameplay/natural_mob_spawns`, `gameplay/creature_world_gen_spawn_probability`
- `gameplay/villager_activity`, `gameplay/baby_villager_activity`

### How the layers are applied

From `EnvironmentAttributeSystem` and `SkyRenderer`:
- **Layer order:** dimension `attributes` (constant), then **biome `attributes` (override, positional)**, then timelines, then weather.
- **Weather layer:** added only if `Level.canHaveWeather()`, which is `hasSkyLight && !hasCeiling && dim != END`. So `has_skylight:false` means no rain, thunder or weather darkening.
- **Stars:** drawn only when `skybox == overworld` and `star_brightness > 0`. The `overworld` skybox also draws the sun and moon at `sun_angle` and `moon_angle`; no attribute hides them. Setting `sun_angle` to about 180 to put the sun below you is **UNCONFIRMED**.
- **Biome trap:** vanilla `minecraft:the_void` biome sets `"minecraft:visual/sky_color": "#7ba4ff"`. Because biome attributes override the dimension, a black sky needs a custom biome (below).
- **Platform trap:** the `void_start_platform` feature only places if the flat settings have `"features": true`.

### `data/<ns>/dimension_type/space.json`

```json
{
  "has_fixed_time": true,
  "has_skylight": false,
  "has_ceiling": false,
  "has_ender_dragon_fight": false,
  "coordinate_scale": 1.0,
  "min_y": -2032,
  "height": 4064,
  "logical_height": 4064,
  "infiniburn": "#minecraft:infiniburn_end",
  "ambient_light": 0.0,
  "monster_spawn_light_level": 0,
  "monster_spawn_block_light_limit": 0,
  "skybox": "overworld",
  "cardinal_light": "default",
  "attributes": {
    "minecraft:visual/sky_color": "#000000",
    "minecraft:visual/fog_color": "#000000",
    "minecraft:visual/fog_start_distance": 4096.0,
    "minecraft:visual/fog_end_distance": 8192.0,
    "minecraft:visual/sky_fog_end_distance": 8192.0,
    "minecraft:visual/cloud_fog_end_distance": 8192.0,
    "minecraft:visual/cloud_color": "#00000000",
    "minecraft:visual/sunrise_sunset_color": "#00000000",
    "minecraft:visual/star_brightness": 1.0,
    "minecraft:visual/sky_light_factor": 0.0,
    "minecraft:visual/ambient_light_color": "#0a0a0a",
    "minecraft:gameplay/sky_light_level": 0.0,
    "minecraft:gameplay/can_start_raid": false,
    "minecraft:gameplay/can_pillager_patrol_spawn": false,
    "minecraft:gameplay/water_evaporates": true,
    "minecraft:gameplay/respawn_anchor_works": false,
    "minecraft:gameplay/bed_rule": { "can_set_spawn": "never", "can_sleep": "never", "destroy_on_use": true },
    "minecraft:audio/background_music": {}
  },
  "timelines": []
}
```

Notes on this file:
- `-2032` and `4064` are the extreme legal values (MIN_Y = -2032, Y_SIZE = 4064). Use `"min_y": -64, "height": 384` if a smaller dimension is enough.
- `timelines: []` together with `has_fixed_time` freezes the sky: no day cycle, so sun angle 0 and stars static.
- Omitting `default_clock` is allowed by the codec. Its effect on `/time` is UNCONFIRMED; add `"default_clock": "minecraft:the_end"` to be safe.
- The `"background_music": {}` and `"bed_rule"` value shapes are copied from the_end.json but this exact combination is untested. Drop those keys if parsing fails.

### `data/<ns>/worldgen/biome/space.json`

A custom biome that keeps the sky black and has no precipitation. The keys match `the_void.json` exactly.

```json
{
  "attributes": {
    "minecraft:visual/sky_color": "#000000",
    "minecraft:visual/fog_color": "#000000",
    "minecraft:gameplay/natural_mob_spawns": {
      "argument": { "spawn_costs": {}, "spawns_by_category": {
        "ambient": [], "axolotls": [], "creature": [], "misc": [], "monster": [],
        "underground_water_creature": [], "water_ambient": [], "water_creature": [] } },
      "modifier": "overlay"
    }
  },
  "carvers": [],
  "downfall": 0.0,
  "effects": { "water_color": "#3f76e4" },
  "features": [],
  "has_precipitation": false,
  "temperature": 0.5
}
```

### `data/<ns>/dimension/space.json`

LevelStem codec keys are `type` and `generator`. FlatLevelSource takes `settings`. FlatLevelGeneratorSettings takes `structure_overrides`, `layers` (required, a list of `{height, block}`), `lakes`, `features` and `biome`.

```json
{
  "type": "<ns>:space",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "<ns>:space",
      "features": false,
      "lakes": false,
      "layers": [ { "block": "minecraft:air", "height": 1 } ],
      "structure_overrides": []
    }
  }
}
```

## 2. Getting the ServerLevel

```java
// net.minecraft.resources.Identifier: static Identifier fromNamespaceAndPath(String,String); parse(String); withDefaultNamespace(String)
// net.minecraft.resources.ResourceKey: static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>>, Identifier); Identifier identifier()
// net.minecraft.core.registries.Registries.DIMENSION : ResourceKey<Registry<Level>>
// MinecraftServer: ServerLevel getLevel(ResourceKey<Level>); ServerLevel overworld(); Iterable<ServerLevel> getAllLevels(); PlayerList getPlayerList()
public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("galactica", "space"));
ServerLevel space = server.getLevel(SPACE);          // returns null if the datapack dimension is missing
boolean inSpace = player.level().dimension() == SPACE; // ServerPlayer.level() returns ServerLevel; Level.OVERWORLD also exists
```

## 3. Teleporting across dimensions (`net.minecraft.world.level.portal.TeleportTransition`)

```java
// record TeleportTransition(ServerLevel newLevel, Vec3 position, Vec3 deltaMovement, float yRot, float xRot,
//     boolean missingRespawnBlock, boolean asPassenger, Set<Relative> relatives, PostTeleportTransition postTeleportTransition)
// ctor (ServerLevel, Vec3 pos, Vec3 speed, float yRot, float xRot, PostTeleportTransition)
// ctor (ServerLevel, Vec3 pos, Vec3 speed, float yRot, float xRot, Set<Relative>, PostTeleportTransition)
// static createDefault(ServerPlayer, PostTeleportTransition); missingRespawnBlock(ServerPlayer, PostTeleportTransition)
// withRotation(float,float); withPosition(Vec3); transitionAsPassenger()
// PostTeleportTransition constants: DO_NOTHING, PLAY_PORTAL_SOUND, PLACE_PORTAL_TICKET; interface method onTransition(Entity); default then(PostTeleportTransition)
// ServerPlayer: public @Nullable ServerPlayer teleport(TeleportTransition)
ServerLevel dest = server.getLevel(SPACE);
if (dest != null) {
    player.teleport(new TeleportTransition(dest, new Vec3(0.5, 100, 0.5), Vec3.ZERO,
        player.getYRot(), player.getXRot(),
        TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
}
```

## 4. Gravity and fall attributes

All of these are `Holder<Attribute>` constants in `net.minecraft.world.entity.ai.attributes.Attributes`, and all are synced.

| Attribute | Default | Range |
|---|---|---|
| `GRAVITY` | 0.08 | -1.0..1.0 |
| `SAFE_FALL_DISTANCE` | 3.0 | -1024..1024 |
| `FALL_DAMAGE_MULTIPLIER` | 1.0 | 0..100 |
| `JUMP_STRENGTH` | 0.42 | 0..32 |
| `FLYING_SPEED` | 0.4 | 0..1024 |
| `MOVEMENT_SPEED` | | |

`AttributeModifier`:
- `record AttributeModifier(Identifier id, double amount, AttributeModifier.Operation operation)`
- `Operation` values: `ADD_VALUE` ("add_value"), `ADD_MULTIPLIED_BASE` ("add_multiplied_base"), `ADD_MULTIPLIED_TOTAL` ("add_multiplied_total")

`LivingEntity`:
- `AttributeInstance getAttribute(Holder<Attribute>)` (nullable)
- `double getAttributeValue(Holder<Attribute>)`

`AttributeInstance`:
- `addOrUpdateTransientModifier(AttributeModifier)`, `addTransientModifier(...)`
- `addOrReplacePermanentModifier(...)`, `addPermanentModifier(...)`
- `boolean removeModifier(Identifier)`, `removeModifier(AttributeModifier)`
- `boolean hasModifier(Identifier)`, `@Nullable AttributeModifier getModifier(Identifier)`
- `getValue()`, `getBaseValue()`, `setBaseValue(double)`

```java
static final Identifier GRAV_ID = Identifier.fromNamespaceAndPath("galactica", "body_gravity");
void applyGravity(ServerPlayer p, double gSurface /* in units of Earth g */) {
    AttributeInstance inst = p.getAttribute(Attributes.GRAVITY);
    if (inst == null) return;
    // ADD_MULTIPLIED_TOTAL with (g-1) scales 0.08 to 0.08*g; g=0 gives exactly 0
    inst.addOrUpdateTransientModifier(new AttributeModifier(GRAV_ID, gSurface - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
}
void clearGravity(ServerPlayer p) { AttributeInstance i = p.getAttribute(Attributes.GRAVITY); if (i != null) i.removeModifier(GRAV_ID); }
```

`Entity` also has `setNoGravity(boolean)`, `isNoGravity()` and `resetFallDistance()`, plus the public field `double fallDistance`.

## 5. Commands

```java
// net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT
//   void register(CommandDispatcher<CommandSourceStack>, CommandBuildContext, Commands.CommandSelection /* ALL | DEDICATED | INTEGRATED */)
// Commands: static LiteralArgumentBuilder<CommandSourceStack> literal(String);
//           static <T> RequiredArgumentBuilder<CommandSourceStack,T> argument(String, ArgumentType<T>);
//           static <T extends PermissionSetSupplier> PermissionProviderCheck<T> hasPermission(PermissionCheck)
//           PermissionCheck constants: LEVEL_ALL, LEVEL_MODERATORS, LEVEL_GAMEMASTERS, LEVEL_ADMINS, LEVEL_OWNERS
// CommandSourceStack: void sendSuccess(Supplier<Component>, boolean broadcastToOps); void sendFailure(Component);
//   ServerPlayer getPlayerOrException() throws CommandSyntaxException; @Nullable ServerPlayer getPlayer(); MinecraftServer getServer(); ServerLevel getLevel()
// Component: static MutableComponent literal(String); translatable(String)
CommandRegistrationCallback.EVENT.register((dispatcher, buildCtx, selection) ->
    dispatcher.register(Commands.literal("galactica")
        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.literal("gravity")
            .then(Commands.argument("g", DoubleArgumentType.doubleArg(0.0, 10.0))
                .executes(ctx -> {
                    double g = DoubleArgumentType.getDouble(ctx, "g");
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    applyGravity(p, g);
                    ctx.getSource().sendSuccess(() -> Component.literal("g = " + g), false);
                    return 1;
                })))
        .then(Commands.literal("goto")
            .then(Commands.argument("system", StringArgumentType.word())
                .then(Commands.argument("seed", LongArgumentType.longArg())
                    .executes(ctx -> { String s = StringArgumentType.getString(ctx, "system"); long seed = LongArgumentType.getLong(ctx, "seed"); return 1; }))))));
```

The Brigadier `*ArgumentType` classes live in `com.mojang.brigadier.arguments`. They come from the standard Brigadier jar and were not javap'd. The `.requires(Commands.hasPermission(...))` type inference is UNCONFIRMED: `PermissionProviderCheck<T>` must be a `Predicate<CommandSourceStack>`. If it does not compile, use `.requires(src -> src.permissions()...)`.

## 6. Data attachments (`net.fabricmc.fabric.api.attachment.v1`)

`AttachmentRegistry`:
- `static <A> AttachmentType<A> create(Identifier, Consumer<AttachmentRegistry.Builder<A>>)`
- `create(Identifier)`
- `createDefaulted(Identifier, Supplier<A>)`
- `createPersistent(Identifier, Codec<A>)`
- `static <A> Builder<A> builder()` (deprecated style)

`AttachmentRegistry.Builder<A>`:
- `persistent(Codec<A>)`
- `copyOnDeath()`
- `initializer(Supplier<A>)`
- `syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A>, AttachmentSyncPredicate)`
- `syncWith(StreamCodec, AttachmentSyncPredicate, int)`
- `AttachmentType<A> buildAndRegister(Identifier)`

`AttachmentSyncPredicate` (a `BiPredicate<AttachmentTarget, ServerPlayer>`) has the factories `all()`, `targetOnly()` and `allButTarget()`.

`AttachmentTarget` is interface-injected into Entity, BlockEntity, ServerLevel and LevelChunk. It provides:
- `getAttached`, `getAttachedOrThrow`, `getAttachedOrSet(type, A)`
- `getAttachedOrCreate(type[, Supplier])`, `getAttachedOrElse(type, A)`, `getAttachedOrGet(type, Supplier)`
- `A setAttached(type, A)` (returns the previous value; passing null removes)
- `boolean hasAttached`, `removeAttached`
- `modifyAttached(type, UnaryOperator<A>)`
- `onAttachedSet(type)` (an Event)

```java
public record ShipState(double fuel, long systemSeed) {
    public static final Codec<ShipState> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.DOUBLE.fieldOf("fuel").forGetter(ShipState::fuel),
        Codec.LONG.fieldOf("system_seed").forGetter(ShipState::systemSeed)).apply(i, ShipState::new));
    public static final StreamCodec<ByteBuf, ShipState> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.DOUBLE, ShipState::fuel, ByteBufCodecs.LONG, ShipState::systemSeed, ShipState::new);
}
public static final AttachmentType<ShipState> SHIP = AttachmentRegistry.create(
    Identifier.fromNamespaceAndPath("galactica", "ship"),
    b -> b.persistent(ShipState.CODEC).copyOnDeath().initializer(() -> new ShipState(100, 0L))
          .syncWith(ShipState.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));
ShipState s = player.getAttachedOrCreate(SHIP);
player.setAttached(SHIP, new ShipState(s.fuel() - 1, s.systemSeed()));
```

## 7. Networking

`net.minecraft.network.protocol.common.custom.CustomPacketPayload`:
- `Type<? extends CustomPacketPayload> type()`
- `record Type<T>(Identifier id)`
- `static createType(String)` (vanilla namespace only)
- `static codec(StreamMemberEncoder, StreamDecoder)`

`PayloadTypeRegistry<B>` (Fabric):
- static `clientboundPlay()` and `serverboundPlay()`, both `PayloadTypeRegistry<RegistryFriendlyByteBuf>`
- static `clientboundConfiguration()` and `serverboundConfiguration()`
- `register(Type<T>, StreamCodec<? super B, T>)`
- `registerLarge(Type<T>, StreamCodec<? super B, T>, int maxSize)` and an IntSupplier variant

`ServerPlayNetworking`:
- `static void send(ServerPlayer, CustomPacketPayload)`
- `registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>)`, where the handler is `receive(T, Context)` and `Context` has `server()`, `player()`, `responseSender()`
- `canSend(ServerPlayer, Type<?>)`

`ClientPlayNetworking` (in `net.fabricmc.fabric.api.client.networking.v1`):
- `registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>)`, where `Context` has `client()` and `player()` (LocalPlayer)
- `static void send(CustomPacketPayload)`
- `canSend(Type<?>)`

`ByteBufCodecs` constants: `DOUBLE`, `FLOAT`, `LONG`, `VAR_LONG`, `VAR_INT`, `BOOL`, `STRING_UTF8`. `Vec3.STREAM_CODEC` also exists. `StreamCodec.composite(...)` has overloads with 1 to N fields.

```java
public record SyncNavPayload(double x, double y, double z, long sector) implements CustomPacketPayload {
    public static final Type<SyncNavPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("galactica", "sync_nav"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncNavPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.DOUBLE, SyncNavPayload::x, ByteBufCodecs.DOUBLE, SyncNavPayload::y,
        ByteBufCodecs.DOUBLE, SyncNavPayload::z, ByteBufCodecs.LONG, SyncNavPayload::sector, SyncNavPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
// common init (both sides):
PayloadTypeRegistry.clientboundPlay().register(SyncNavPayload.TYPE, SyncNavPayload.CODEC);
// server:
ServerPlayNetworking.send(player, new SyncNavPayload(gx, gy, gz, sector));
// client init:
ClientPlayNetworking.registerGlobalReceiver(SyncNavPayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> ClientNav.update(payload)));
```

The 4-field `StreamCodec.composite` overload exists by pattern; javap only listed 1 to 3 in the head output. Whether `receive` already runs on the main thread is UNCONFIRMED; the `execute()` wrapper is harmless either way.

## 8. Ticks and player state

`net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents`:
- `START_SERVER_TICK` and `END_SERVER_TICK`, whose `EndTick` is `onEndTick(MinecraftServer)`
- `START_LEVEL_TICK` and `END_LEVEL_TICK`, whose `EndLevelTick` is `onEndTick(ServerLevel)`

`Entity`:
- `Vec3 position()`, `final double getX()`, `getY()`, `getZ()`
- `Vec3 getDeltaMovement()`, `setDeltaMovement(Vec3)`, `setDeltaMovement(double,double,double)`, `addDeltaMovement(Vec3)`
- `Level level()` (ServerPlayer overrides it to return `ServerLevel`)
- `public boolean needsSync` (replaces `hurtMarked`), `protected void markHurt()`

`Player`:
- `boolean isCreative()`, `isSpectator()`
- `Abilities getAbilities()`, whose `Abilities` has the public fields `flying` and `mayfly`

`ServerPlayer`:
- `void onUpdateAbilities()` (sends the abilities packet)
- `public ServerGamePacketListenerImpl connection`, whose `send(Packet<?>)` is used below

`ServerLevel` has `List<ServerPlayer> players()`. `ClientboundSetEntityMotionPacket` has a constructor taking `(Entity)` and one taking `(int, Vec3)`.

```java
ServerTickEvents.END_LEVEL_TICK.register(level -> {
    if (level.dimension() != SPACE) return;
    for (ServerPlayer p : level.players()) {
        if (p.isCreative() || p.isSpectator() || p.getAbilities().flying) continue;
        Vec3 v = p.getDeltaMovement().add(0.0, 0.0, 0.0); // add thrust/orbital accel here
        p.setDeltaMovement(v);
        p.connection.send(new ClientboundSetEntityMotionPacket(p)); // server-set velocity reaches the client only via this packet (or needsSync=true)
        p.resetFallDistance();
    }
});
```

That `needsSync=true` triggers the velocity packet the way `hurtMarked` used to is UNCONFIRMED. The explicit packet is confirmed to exist.

The decompiled classes are in `~\AppData\Local\Temp\claude\<claude-project>\00f1c288-802c-470c-9e12-011d6cf62811\scratchpad\dec`, `dec2` and `dec3`, and the extracted Fabric module jars are in `...\scratchpad\fab\META-INF\jars`.