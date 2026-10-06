[@aigoatsamuel]

I checked the cheat-sheet against the real 26.3 classes and Fabric API 0.161.0 jars. Most of it holds. Six things needed fixing: where Fabric attachments can live, the snippet that pushes a player's velocity to their own client, `/time` with no `default_clock`, the void-platform condition, `MOVEMENT_SPEED`'s default, and which receiver thread network handlers run on.

Sources checked:
- **Vanilla:** `javap -p` against `minecraft-merged-deobf-26.3.jar`. I also decompiled the classes I needed with vineflower 1.11.2 into `scratchpad\dec`, `dec2`, `dec3` and `dec4`; `dec4` is new and holds Biome, LevelStem, the flat generator classes, ServerEntity, Entity, Level, BedRule and BackgroundMusic.
- **Fabric API:** `javap` and `javap -c` on the module jars in `scratchpad\fab\META-INF\jars`, plus the `.classtweaker` files.
- **Data:** the files under `26.3-vanilla-26.3-sources\data\minecraft`.
- **Brigadier:** `brigadier-1.1.8.jar` from the gradle cache, checked with javap.
- **Iris:** the cheat-sheet makes no claims about Iris, so there was nothing in that jar to check.

The four corrections in the preamble are all CONFIRMED:
- `clientboundPlay()` and `serverboundPlay()` exist, and `playS2C`/`playC2S` are gone.
- `END_LEVEL_TICK` exists.
- `ResourceLocation` is now `net.minecraft.resources.Identifier`.
- `Commands.hasPermission(PermissionCheck)` works with the `LEVEL_*` constants.

---

## 1. Datapack dimension

### DimensionType codec — CONFIRMED (every key, type, range and default matches `createDirectCodec` byte for byte)

| JSON key | Type / range | Required? | Status |
|---|---|---|---|
| `has_fixed_time` | bool | optional, default false | CONFIRMED |
| `has_skylight` | bool | **required** | CONFIRMED |
| `has_ceiling` | bool | **required** | CONFIRMED |
| `has_ender_dragon_fight` | bool | **required** | CONFIRMED |
| `coordinate_scale` | double in [1e-5, 3e7] | **required** | CONFIRMED |
| `min_y` | int in [MIN_Y, MAX_Y]; multiple of 16 | **required** | CONFIRMED (MIN_Y = -2032, MAX_Y = 2031, from PACKED_Y_LENGTH = 12) |
| `height` | int in [16, Y_SIZE]; multiple of 16; `min_y + height` ≤ MAX_Y+1 | **required** | CONFIRMED (Y_SIZE = 4064) |
| `logical_height` | int in [0, Y_SIZE]; ≤ height | **required** | CONFIRMED |
| `infiniburn` | block HolderSet (`"#minecraft:infiniburn_end"`) | **required** | CONFIRMED (the tag exists) |
| `ambient_light` | float | **required** | CONFIRMED |
| `monster_spawn_light_level` | `IntProviders.codec(0,15)` | **required** (inlined MonsterSettings) | CONFIRMED |
| `monster_spawn_block_light_limit` | int 0..15 | **required** | CONFIRMED |
| `skybox` | `"none"`, `"overworld"`, `"end"` | optional, default `overworld` | CONFIRMED |
| `cardinal_light` | `"default"`, `"nether"` | optional, default `default` | CONFIRMED |
| `attributes` | EnvironmentAttributeMap | optional, default EMPTY | CONFIRMED |
| `timelines` | Timeline HolderSet (tag string or list) | optional, default empty | CONFIRMED |
| `default_clock` | world_clock holder (`minecraft:overworld`, `minecraft:the_end`) | optional | CONFIRMED |

The removed keys (`fixed_time`, `natural`, `ultrawarm`, `piglin_safe`, `bed_works`, `has_raids`, `effects`) are gone, and their replacements now live in `attributes`. CONFIRMED.

### Environment attributes (`net.minecraft.world.attribute.EnvironmentAttributes`) — CONFIRMED, with blank defaults now filled in

In JSON, `attributes` maps `"minecraft:<id>"` to a value. RGB is written `"#RRGGBB"` and ARGB is written `"#AARRGGBB"`; vanilla uses `"#ccffffff"` for `cloud_color`, which shows the ARGB form is accepted. CONFIRMED.

Visual attributes:

| Attribute | Type | Default | Status |
|---|---|---|---|
| `visual/fog_color` | RGB | #000000 | FIXED (default filled in) |
| `visual/fog_start_distance` | float | 0 | CONFIRMED |
| `visual/fog_end_distance` | float ≥ 0 | 1024 | CONFIRMED |
| `visual/sky_fog_end_distance` | float ≥ 0 | 512 | CONFIRMED |
| `visual/cloud_fog_end_distance` | float ≥ 0 | 2048 | CONFIRMED |
| `visual/water_fog_color` | RGB | #050533 | FIXED (default filled in) |
| `visual/water_fog_start_distance` | float | -8 | FIXED (default filled in) |
| `visual/water_fog_end_distance` | float ≥ 0 | 96 | FIXED (default filled in) |
| `visual/sky_color` | RGB | #000000 | FIXED (default filled in) |
| `visual/sunrise_sunset_color` | ARGB | #00000000 | FIXED (default filled in) |
| `visual/cloud_color` | ARGB | #00000000 (alpha 0 = no clouds) | CONFIRMED |
| `visual/cloud_height` | float | 192.33 | FIXED (default filled in) |
| `visual/sun_angle` | degrees | 0 | CONFIRMED |
| `visual/moon_angle` | degrees | 0 | FIXED (default filled in) |
| `visual/star_angle` | degrees | 0 | FIXED (default filled in) |
| `visual/moon_phase` | MoonPhase | full_moon | FIXED (default filled in) |
| `visual/star_brightness` | float 0..1 | **0** | CONFIRMED |
| `visual/block_light_tint` | RGB | #ffd88c | FIXED (default filled in) |
| `visual/sky_light_color` | RGB | #ffffff | FIXED (default filled in) |
| `visual/sky_light_factor` | float 0..1 | 1 | CONFIRMED |
| `visual/night_vision_color` | RGB | #999999 | FIXED (default filled in) |
| `visual/ambient_light_color` | RGB | #000000 | FIXED (default filled in) |
| `visual/default_dripstone_particle` | particle | dripping_dripstone_water | FIXED (default filled in) |
| `visual/ambient_particles` | list | [] | FIXED (default filled in) |

Audio attributes — CONFIRMED:
- `audio/background_music`: every field (`default`, `creative`, `underwater`) is optional, so `{}` decodes to EMPTY.
- `audio/music_volume`: 0..1, default 1.
- `audio/ambient_sounds`.
- `audio/firefly_bush_sounds`.

Gameplay attributes — CONFIRMED (all ids exist):
- `gameplay/sky_light_level` is a float 0..15 with default 15. It is **notPositional**, so it is legal in a dimension but rejected in a biome, because biomes use `CODEC_ONLY_POSITIONAL`. `gameplay/fast_lava` is also notPositional.
- `can_start_raid`, `water_evaporates`, `bed_rule`, `straw_bed_rule`, `respawn_anchor_works`, `nether_portal_spawns_piglin`, `fast_lava`, `increased_fire_burnout`, `eyeblossom_open`, `turtle_egg_hatch_chance`, `piglins_zombify`, `snow_golem_melts`, `creaking_active`, `surface_slime_spawn_chance`, `cat_waking_up_gift_chance`, `bees_stay_in_hive`, `monsters_burn`, `can_pillager_patrol_spawn`, `natural_mob_spawns`, `creature_world_gen_spawn_probability`, `villager_activity`, `baby_villager_activity`.

`bed_rule` shape — CONFIRMED: `can_sleep` and `can_set_spawn` are required, each `always`, `when_dark` or `never`. `destroy_on_use`, `destroy_on_leave` and `error_message` are optional.

### How the layers are applied

- **Layer order** — CONFIRMED. The dimension `attributes` form a constant layer, then the biome forms a positional layer per attribute, then timelines, then weather. Biomes apply through `applyModifier` and are spatially blended for interpolated attributes.
- **Weather layer** — CONFIRMED. `canHaveWeather()` is `hasSkyLight() && !hasCeiling() && dimension() != END`.
  - Side effect: `has_skylight:false` also means no skylight at all in that dimension, so only block light shows.
- **Stars** — CONFIRMED. They draw only when `skybox == overworld` and `star_brightness > 0`.
- **Sun and moon** — CONFIRMED. The overworld skybox always draws them at `sun_angle` and `moon_angle`, with alpha `1 - rainLevel`. No attribute hides them.
  - FIXED clarification: `sun_angle` 0 (the default) puts the sun **straight overhead**, not hidden.
  - Setting `sun_angle` to about 180 to hide it remains **UNCONFIRMED**. The dark disc only draws when `shouldRenderDarkDisc`.
  - The only vanilla way to get a sky with no sun is `skybox:"none"`. That draws nothing at all, not even stars, so the mod would render its own sky. CONFIRMED (`SkyRenderer.extractRenderState` and `LevelRenderer` skip NONE).
- **Biome trap** — CONFIRMED. `the_void.json` sets `"minecraft:visual/sky_color": "#7ba4ff"`.
- **Platform trap** — FIXED. In a void flat world (every layer air, or `layers: []`), biome features only generate if `features:true` **and** the biome is `minecraft:the_void`. With the custom `<ns>:space` biome, `void_start_platform` never places, whatever `features` is set to.

### `data/<ns>/dimension_type/space.json` — CONFIRMED (every key and value shape validated against the codecs)

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
  "default_clock": "minecraft:the_end",
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
- `-2032` and `4064` — CONFIRMED as the extreme legal values. A smaller dimension can use `"min_y": -64, "height": 384`.
- `timelines: []` plus `has_fixed_time` gives no time-driven layers — CONFIRMED. `sun_angle` stays at 0, so the sun sits at the zenith and the stars stay static. FIXED: the original note did not say where the sun ends up.
- `default_clock` — FIXED, and now included in the file above. Without it, `TimeCommand.getDefaultClock` calls `orElseThrow`, so `/time` with no explicit clock argument fails with a command error in that dimension.
- `background_music: {}` — CONFIRMED valid, because every BackgroundMusic field is optional.
- `bed_rule` shape — CONFIRMED by the BedRule codec.

### `data/<ns>/worldgen/biome/space.json` — CONFIRMED (codec-valid)

`fog_color` and `sky_color` are positional, so they are allowed in a biome. `features: []` is accepted by `LIST_OF_LISTS_CODEC`. ClimateSettings and `effects.water_color` match `the_void.json`.

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

FIXED wording: the original said "the keys match `the_void.json` exactly". Precisely, the keys are those of `the_void.json` plus `visual/fog_color`, which is legal. Do **not** put `gameplay/sky_light_level` or `gameplay/fast_lava` here, because both are notPositional and the biome codec rejects them.

### `data/<ns>/dimension/space.json` — CONFIRMED

- LevelStem has `type` and `generator`.
- FlatLevelSource has `settings`.
- FlatLevelGeneratorSettings has `structure_overrides` (lenient optional), `layers` (required list of `{height 0..4064, block}`), `lakes`, `features` and `biome` (lenient; an unknown biome falls back to plains with an error log).

```json
{
  "type": "<ns>:space",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "<ns>:space",
      "features": false,
      "lakes": false,
      "layers": [],
      "structure_overrides": []
    }
  }
}
```

FIXED improvement: `"layers": []` is valid, because the sum of 0 is ≤ Y_SIZE and `allMatch` on an empty list sets `voidGen = true`. The vanilla-style `[{"block":"minecraft:air","height":1}]` also works, but each non-opaque layer adds a `FillLayerFeature` to every chunk. The empty list avoids that.

## 2. Getting the ServerLevel — CONFIRMED

```java
// Identifier: static fromNamespaceAndPath(String,String); parse(String); withDefaultNamespace(String); tryParse; tryBuild     CONFIRMED
// ResourceKey: static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>>, Identifier); Identifier identifier()      CONFIRMED
// Registries.DIMENSION : ResourceKey<Registry<Level>>                                                                          CONFIRMED
// MinecraftServer: @Nullable ServerLevel getLevel(ResourceKey<Level>); final ServerLevel overworld(); Iterable<ServerLevel> getAllLevels(); PlayerList getPlayerList()   CONFIRMED
public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("galactica", "space"));
ServerLevel space = server.getLevel(SPACE);            // @Nullable — CONFIRMED
boolean inSpace = player.level().dimension() == SPACE; // ServerPlayer.level() returns ServerLevel; ResourceKeys are interned, so == is safe — CONFIRMED
```

## 3. Teleporting across dimensions — CONFIRMED

```java
// record TeleportTransition(ServerLevel newLevel, Vec3 position, Vec3 deltaMovement, float yRot, float xRot,
//     boolean missingRespawnBlock, boolean asPassenger, Set<Relative> relatives, PostTeleportTransition postTeleportTransition)   CONFIRMED (the 9-arg canonical ctor is also public)
// ctor (ServerLevel, Vec3, Vec3, float, float, PostTeleportTransition)                                                       CONFIRMED
// ctor (ServerLevel, Vec3, Vec3, float, float, Set<Relative>, PostTeleportTransition)                                        CONFIRMED
// static createDefault(ServerPlayer, PostTeleportTransition); static missingRespawnBlock(ServerPlayer, PostTeleportTransition)  CONFIRMED
// withRotation(float,float); withPosition(Vec3); transitionAsPassenger()                                                     CONFIRMED
// DO_NOTHING, PLAY_PORTAL_SOUND, PLACE_PORTAL_TICKET are static fields of TeleportTransition (not of the interface);
//   PostTeleportTransition: void onTransition(Entity); default then(PostTeleportTransition)                                  CONFIRMED (location clarified)
// ServerPlayer: public @Nullable ServerPlayer teleport(TeleportTransition)                                                   CONFIRMED
ServerLevel dest = server.getLevel(SPACE);
if (dest != null) {
    player.teleport(new TeleportTransition(dest, new Vec3(0.5, 100, 0.5), Vec3.ZERO,
        player.getYRot(), player.getXRot(),
        TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
}
```

## 4. Gravity and fall attributes

All of these are `Holder<Attribute>` in `Attributes`, and all are `setSyncable(true)` — CONFIRMED.

| Attribute | Default | Range | Status |
|---|---|---|---|
| `GRAVITY` | 0.08 | -1.0..1.0 | CONFIRMED |
| `SAFE_FALL_DISTANCE` | 3.0 | -1024..1024 | CONFIRMED |
| `FALL_DAMAGE_MULTIPLIER` | 1.0 | 0..100 | CONFIRMED |
| `JUMP_STRENGTH` | 0.42 | 0..32 | CONFIRMED |
| `FLYING_SPEED` | 0.4 | 0..1024 | CONFIRMED |
| `MOVEMENT_SPEED` | 0.7 | 0..1024 | FIXED: the registry default is 0.7; the player's base is set separately to 0.1 |

`AttributeModifier` — CONFIRMED:
- `record AttributeModifier(Identifier id, double amount, Operation operation)`
- `Operation` values: `ADD_VALUE`, `ADD_MULTIPLIED_BASE`, `ADD_MULTIPLIED_TOTAL`

`LivingEntity` — CONFIRMED:
- `AttributeInstance getAttribute(Holder<Attribute>)` (nullable)
- `double getAttributeValue(Holder<Attribute>)`

`AttributeInstance`:
- `addOrUpdateTransientModifier(AttributeModifier)`, `addTransientModifier`, `addOrReplacePermanentModifier`, `addPermanentModifier` — CONFIRMED.
- `boolean removeModifier(Identifier)` — CONFIRMED.
- `removeModifier(AttributeModifier)` — FIXED: it returns `void`, not `boolean`.
- `hasModifier(Identifier)`, `@Nullable getModifier(Identifier)`, `getValue()`, `getBaseValue()`, `setBaseValue(double)` — CONFIRMED.

```java
static final Identifier GRAV_ID = Identifier.fromNamespaceAndPath("galactica", "body_gravity");
void applyGravity(ServerPlayer p, double gSurface) {
    AttributeInstance inst = p.getAttribute(Attributes.GRAVITY);
    if (inst == null) return;
    // calculateValue: base + ADD_VALUE, then MULTIPLIED_BASE, then *= (1+amount) per TOTAL, so 0.08*g. g=0 gives 0. Clamped to [-1,1], so g ≤ 12.5. CONFIRMED
    inst.addOrUpdateTransientModifier(new AttributeModifier(GRAV_ID, gSurface - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
}
void clearGravity(ServerPlayer p) { AttributeInstance i = p.getAttribute(Attributes.GRAVITY); if (i != null) i.removeModifier(GRAV_ID); }
```

`Entity` has `setNoGravity(boolean)`, `isNoGravity()`, `resetFallDistance()` and `public double fallDistance` — CONFIRMED.

## 5. Commands — CONFIRMED (the type inference is now confirmed too)

```java
// CommandRegistrationCallback.EVENT: void register(CommandDispatcher<CommandSourceStack>, CommandBuildContext, Commands.CommandSelection{ALL,DEDICATED,INTEGRATED})   CONFIRMED
// Commands.literal / argument / static <T extends PermissionSetSupplier> PermissionProviderCheck<T> hasPermission(PermissionCheck)                                   CONFIRMED
// LEVEL_ALL, LEVEL_MODERATORS, LEVEL_GAMEMASTERS, LEVEL_ADMINS, LEVEL_OWNERS                                                                                       CONFIRMED
// CommandSourceStack: sendSuccess(Supplier<Component>, boolean); sendFailure(Component); getPlayerOrException() throws CommandSyntaxException;
//   @Nullable getPlayer(); getServer(); getLevel(); PermissionSet permissions()                                                                                    CONFIRMED
// Component.literal(String) / translatable(String) -> MutableComponent                                                                                           CONFIRMED
// Brigadier 1.1.8: DoubleArgumentType.doubleArg(double,double)/getDouble; LongArgumentType.longArg()/getLong; StringArgumentType.word()/getString                 CONFIRMED (javap)
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

The `.requires(Commands.hasPermission(...))` inference is CONFIRMED, so the fallback is no longer needed:
- `PermissionProviderCheck<T>` is a record that implements `Predicate<T>`.
- `CommandSourceStack` implements `PermissionSetSupplier` through `SharedSuggestionProvider` and `ExecutionCommandSource`.
- Vanilla `TimeCommand` compiles exactly this pattern.

## 6. Data attachments (`net.fabricmc.fabric.api.attachment.v1`, data-attachment-api-v1 2.2.30)

`AttachmentRegistry` — CONFIRMED:
- `create(Identifier, Consumer<Builder<A>>)`
- `create(Identifier)`
- `createDefaulted(Identifier, Supplier<A>)`
- `createPersistent(Identifier, Codec<A>)`
- `builder()`

`AttachmentRegistry.Builder<A>` — CONFIRMED:
- `persistent(Codec<A>)`
- `copyOnDeath()`
- `initializer(Supplier<A>)`
- `syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A>, AttachmentSyncPredicate)`
- `syncWith(..., int)`
- `buildAndRegister(Identifier)`

`AttachmentSyncPredicate` extends `BiPredicate<AttachmentTarget, ServerPlayer>` and has `all()`, `targetOnly()` and `allButTarget()` — CONFIRMED.

`AttachmentTarget` — FIXED. The classtweaker injects it transitively into `Entity`, `BlockEntity`, `Level` and `ChunkAccess`. That means every Level, client levels included, and every chunk, including proto chunks. It is not limited to ServerLevel and LevelChunk. `GlobalAttachmentsProvider` is injected into `MinecraftServer` and `Level`.

Methods — CONFIRMED:
- `getAttached`, `getAttachedOrThrow`, `getAttachedOrSet(type, A)`
- `getAttachedOrCreate(type)`, `getAttachedOrCreate(type, Supplier)`
- `getAttachedOrElse(type, A)`, `getAttachedOrGet(type, Supplier)`
- `A setAttached(type, A)`
- `boolean hasAttached(type)`, `A removeAttached(type)`
- `modifyAttached(type, UnaryOperator<A>)`
- `Event<OnAttachedSet<A>> onAttachedSet(type)`

```java
public record ShipState(double fuel, long systemSeed) {
    public static final Codec<ShipState> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.DOUBLE.fieldOf("fuel").forGetter(ShipState::fuel),
        Codec.LONG.fieldOf("system_seed").forGetter(ShipState::systemSeed)).apply(i, ShipState::new));
    // StreamCodec<ByteBuf, ...> satisfies `? super RegistryFriendlyByteBuf` — CONFIRMED
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

## 7. Networking (networking-api-v1 6.3.8)

`CustomPacketPayload` — CONFIRMED:
- `Type<? extends CustomPacketPayload> type()`
- `record Type<T>(Identifier id)`
- `static createType(String)`, which uses `Identifier.withDefaultNamespace`, so it is vanilla-namespace only
- `static codec(StreamMemberEncoder<B,T>, StreamDecoder<B,T>)`

`PayloadTypeRegistry<B extends FriendlyByteBuf>` — CONFIRMED:
- `clientboundPlay()` and `serverboundPlay()` return `PayloadTypeRegistry<RegistryFriendlyByteBuf>`.
- `clientboundConfiguration()` and `serverboundConfiguration()` return `PayloadTypeRegistry<FriendlyByteBuf>`.
- `register(Type<T>, StreamCodec<? super B,T>)`, which returns `TypeAndCodec`.
- `registerLarge(..., int)` and `registerLarge(..., IntSupplier)`.

`ServerPlayNetworking` — CONFIRMED:
- `send(ServerPlayer, CustomPacketPayload)`
- `boolean registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>)`, where the handler is `receive(T, Context)`
- `Context` has `server()`, `player()`, `responseSender()` and `packetContext()`
- `canSend(ServerPlayer, Type<?>)` and an `Identifier` overload

`ClientPlayNetworking` (`net.fabricmc.fabric.api.client.networking.v1`) — CONFIRMED:
- `registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>)`
- `Context` has `client()` (Minecraft), `player()` (LocalPlayer) and `responseSender()`
- `send(CustomPacketPayload)`
- `canSend(Type<?>)`

`ByteBufCodecs` has `DOUBLE`, `FLOAT`, `LONG`, `VAR_LONG`, `VAR_INT`, `BOOL` and `STRING_UTF8`, all `StreamCodec<ByteBuf, …>`. `Vec3` has `STREAM_CODEC` and `LP_STREAM_CODEC`. CONFIRMED.

`StreamCodec.composite` — CONFIRMED. There are 14 overloads, covering 1 to 14 fields, so the 4-field form exists.

```java
public record SyncNavPayload(double x, double y, double z, long sector) implements CustomPacketPayload {
    public static final Type<SyncNavPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("galactica", "sync_nav"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncNavPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.DOUBLE, SyncNavPayload::x, ByteBufCodecs.DOUBLE, SyncNavPayload::y,
        ByteBufCodecs.DOUBLE, SyncNavPayload::z, ByteBufCodecs.LONG, SyncNavPayload::sector, SyncNavPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
PayloadTypeRegistry.clientboundPlay().register(SyncNavPayload.TYPE, SyncNavPayload.CODEC);   // common init
ServerPlayNetworking.send(player, new SyncNavPayload(gx, gy, gz, sector));                   // server
ClientPlayNetworking.registerGlobalReceiver(SyncNavPayload.TYPE, (payload, ctx) -> ClientNav.update(payload)); // client init
```

Thread question — FIXED, previously UNCONFIRMED. `AbstractChanneledNetworkAddon.handle` checks `isOnReceiveThread()`. If the call is off that thread it throws `RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD`, which re-queues the packet on the main thread.
- The client check is `Minecraft.packetProcessor().isSameThread()`, so client handlers already run on the client main thread.
- The server addon's `isOnReceiveThread` was not decompiled. The server handler is likewise assumed to run on the server thread, but that side is UNCONFIRMED.
- The `ctx.client().execute(...)` wrapper is redundant but harmless.

## 8. Ticks and player state

`ServerTickEvents` — CONFIRMED:
- `START_SERVER_TICK` and `END_SERVER_TICK`, where `EndTick.onEndTick(MinecraftServer)`
- `START_LEVEL_TICK` and `END_LEVEL_TICK`, where `EndLevelTick.onEndTick(ServerLevel)`

`Entity`:
- `Vec3 position()`, `final getX()`, `getY()`, `getZ()` — CONFIRMED.
- `getDeltaMovement()`, `setDeltaMovement(Vec3)`, `setDeltaMovement(double,double,double)`, `addDeltaMovement(Vec3)` — CONFIRMED.
- `Level level()`, which ServerPlayer overrides to return ServerLevel — CONFIRMED.
- `hurtMarked` is gone — CONFIRMED.
- FIXED: the real replacement for `hurtMarked` is **`public boolean syncVelocity`**.
  - `protected void markHurt()` sets `syncVelocity = true`.
  - `ServerEntity.sendChanges` then calls `sendToTrackingPlayersAndSelf(new ClientboundSetEntityMotionPacket(entity))`, which reaches the player's own client.
- FIXED: `public boolean needsSync` (set by `push()` and by loading) does something narrower. It forces a position/rotation update and a motion packet only through `sendToTrackingPlayers`, which does **not** include the player's own client. Do not use it to push velocity to the player.
- `syncPosition` also exists.

`Player`: `isCreative()`, `isSpectator()` and `Abilities getAbilities()` exist, and `Abilities` has `public boolean flying, mayfly` — CONFIRMED.

`ServerPlayer`:
- `onUpdateAbilities()` — CONFIRMED.
- `public ServerGamePacketListenerImpl connection` — CONFIRMED.
- `send(Packet<?>)` — CONFIRMED; it is inherited from `ServerCommonPacketListenerImpl`.

`ServerLevel.players()` returns `List<ServerPlayer>`. `ClientboundSetEntityMotionPacket` has the constructors `(Entity)` and `(int, Vec3)`. CONFIRMED.

```java
ServerTickEvents.END_LEVEL_TICK.register(level -> {
    if (level.dimension() != SPACE) return;
    for (ServerPlayer p : level.players()) {
        if (p.isCreative() || p.isSpectator() || p.getAbilities().flying) continue;
        Vec3 v = p.getDeltaMovement().add(0.0, 0.0, 0.0); // add thrust/orbital accel here
        p.setDeltaMovement(v);
        p.syncVelocity = true; // FIXED: ServerEntity sends ClientboundSetEntityMotionPacket to tracking players AND self at end of entity tracking
        // alternative, immediate, self only: p.connection.send(new ClientboundSetEntityMotionPacket(p));  CONFIRMED
        p.resetFallDistance();
    }
});
```

Still UNCONFIRMED:
- Whether `sun_angle` set to about 180 hides the sun.
- The exact frame timing of `syncVelocity` against `END_LEVEL_TICK`. Entity tracking runs in the chunk-map tick, so the packet goes out the same tick or the next one.
- Whether server-side play handlers run on the server thread (the server addon's `isOnReceiveThread` was not decompiled).

Files are in `C:\Users\Manec\AppData\Local\Temp\claude\C--Users-Manec\00f1c288-802c-470c-9e12-011d6cf62811\scratchpad\`:
- `check\mc.txt` holds the vanilla javap dump.
- `dec4\` holds the new decompiled classes.