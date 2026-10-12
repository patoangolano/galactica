[@aigoatsamuel]

Two lines were wrong in the earlier sheet: the module list and two package paths. `fabric-dimensions-v1` does ship in 0.161.0, and it changes three conclusions.

**Compile check: PASSED.** javac 25.0.4 with `--release 25 -proc:none` exited 0. It compiled 8 source files (10 classes) against the merged-deobf 26.3 jar, the fabric-api 0.161.0 module jars at the versions in its pom (dimensions-v1 included), fabric-loader 0.19.5, sponge-mixin and mixinextras, and the vanilla libraries (DFU 10.0.21, brigadier, joml, guava, fastutil, gson, authlib, logging, slf4j, netty, log4j).
- Directory: `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/apicheck_worldgen/`
- Classpath list: `libs.txt`
- Sources: `src/dev/galactica/surface/{PlanetChunkGenerator,PlanetBiomeSource,PlanetParams,SurfaceSlots,SurfaceSlotData,SurfaceWipe,Probe,Probe2}.java` and `src/dev/galactica/mixin/client/ClientLevelAttributesMixin.java`
- `Probe.java` covers the signatures not in the main snippets: teleport, heightmaps, section fast path, SavedData, DimensionArgument, attributes, DimensionEvents. `Probe2.java` covers event phase ordering.
- Decompiled Fabric sources: `dimdec/` and `rl/`.

---

# Procedural planet surfaces as dimensions: MC 26.3 / Fabric 0.161.0 (re-verified)

## 1. Custom ChunkGenerator

### Registration: CONFIRMED (compiles)
```java
Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("galactica","planet"), PlanetChunkGenerator.CODEC);
Registry.register(BuiltInRegistries.BIOME_SOURCE,    Identifier.fromNamespaceAndPath("galactica","planet"), PlanetBiomeSource.CODEC);
```
- The registries are typed `Registry<MapCodec<? extends ChunkGenerator>>` and `Registry<MapCodec<? extends BiomeSource>>`. CONFIRMED.
- Call both in `onInitialize`. CONFIRMED.
- `LevelStem` and the generator codec are server-side only. UNCONFIRMED; this was carried over and not re-traced.

### `net.minecraft.world.level.chunk.ChunkGenerator`: CONFIRMED (javap)
Abstract methods:
- `codec()`
- `buildTerrain(ChunkAccess, Blender, RandomState, StructureManager, BiomeManager, WorldGenRegion, Set<Holder<Biome>>)` returning `CompletableFuture<ChunkAccess>`
- `spawnOriginalMobs(WorldGenRegion)`
- `getGenDepth()`, `getSeaLevel()`, `getMinY()`
- `getBaseHeight(int, int, Heightmap.Types, LevelHeightAccessor, RandomState)`
- `getBaseColumn(int, int, LevelHeightAccessor, RandomState)`
- `addDebugScreenInfo(List<String>, RandomState, BlockPos, levelgen.densityfunction.SamplerContext)`

Other facts:
- Constructors: `(BiomeSource)` and `(BiomeSource, Function<Holder<Biome>, BiomeGenerationSettings>)`.
- Field: `protected final BiomeSource biomeSource`.
- Overridable methods: `getSpawnHeight` (the bytecode is `bipush 64; ireturn`), `getOrigin`, `createBiomes`, `applyBiomeDecoration`, `createStructures`, `createState`, `getBiomeSource`.
- `fillFromNoise`, `applyCarvers` and `buildSurface` are absent from the class. CONFIRMED.
- The call-site behaviour (vanilla passes the region and primes `MOTION_BLOCKING*`, `OCEAN_FLOOR` and `WORLD_SURFACE` afterwards, so the generator only updates the `_WG` maps) is carried over from the decompile. The `Heightmap.primeHeightmaps(ChunkAccess, Set<Types>)` signature compiles.
- **FIXED:** the async executor is `Util.backgroundExecutor()`, which returns **`net.minecraft.TracingExecutor`** (not `net.minecraft.util`). `.forName(String)` returns `Executor`.

### Supporting signatures: CONFIRMED (compiles)
- `ChunkAccess`:
  - `setBlockState`, `getOrCreateHeightmapUnprimed`, `getPos`
  - `getMinY`, `getMaxY`, `getHeight`
  - `getSection(int)`, `getSectionIndex(int)`
- `LevelChunkSection`: `acquire()`, `setBlockState(int, int, int, BlockState, boolean)`, `release()`
- `Heightmap`: `boolean update(int, int, int, BlockState)`
- `ChunkPos`: `x()`, `getMinBlockX()`, `getMinBlockZ()`
- `NoiseColumn(int, BlockState[])`
- `WorldGenRegion`: `getLevel()`, `getSeed()`, `getCenter()`
- `FixedBiomeSource.CODEC` is a `MapCodec`; `BiomeSource.CODEC` is a `Codec<BiomeSource>`.

### Complete minimal generator: CONFIRMED (compiles unchanged)
The class text is the same as in the previous sheet. `PlanetParams` is a record `(name, seed, baseY, amplitude, stone, top)` with an `int height(x, z)` method.

**FIXED (consistency):** the compiled `SurfaceSlots` has `N = 8`, while the design text says 16. Pick one value. The codec ranges `intRange(0, 63)` must cover `N - 1`.

The `LevelChunkSection` fast path: the API is CONFIRMED; the speed-up is UNCONFIRMED.

### Dimension JSON: key shape CONFIRMED against the codec. Not loaded in game.
```json
{ "type": "galactica:surface_3",
  "generator": { "type": "galactica:planet", "slot": 3,
    "biome_source": { "type": "minecraft:fixed", "biome": "galactica:planet_rock" } } }
```
**FIXED:** the stem now points at a **per-slot** `dimension_type` (`galactica:surface_3`), not a shared `galactica:surface`. This enables the server-side attribute lever in §4b. A shared type still works if you skip §4b.

Variant with the per-slot runtime biome:
```json
"biome_source": { "type": "galactica:planet", "slot": 3, "biomes": ["galactica:planet_rock","galactica:planet_desert","galactica:planet_ice","galactica:planet_lava"] }
```

### `dimension_type/surface_<k>.json`: keys CONFIRMED (existing codec table). Values modelled on vanilla `overworld.json`.
The JSON is the same as before. Rules:
- `has_skylight: true` turns on weather. Use `has_precipitation: false` in the biome to suppress it. CONFIRMED (existing doc).
- `default_clock` and `timelines` share the overworld day. A custom `world_clock` is UNCONFIRMED.

## 2. Slot pool: N datapack dimensions with runtime parameters

- **Use the slot codec field.** Each stem decodes its own generator instance. CONFIRMED. `getBaseHeight` and `getBaseColumn` only get a `LevelHeightAccessor`, so the slot field is the only route that covers every method. CONFIRMED.
- **The datapack beats the save.** `WorldDimensions.bake(Registry<LevelStem>)` uses `Optional.or(...)`, so the datapack entry wins. CONFIRMED (javap). Fabric wraps `bake` (`@WrapMethod`) only to provide a `ScopedValue`, and precedence is unchanged.
- **FIXED: removing the mod no longer breaks the world.**
  - With Fabric API installed, `fabric-dimensions-v1` swaps the `dimensions` codec of `WorldDimensions` for `FailSoftMapCodec` (`@Redirect` on `RecordCodecBuilder.Instance.group`). This is CONFIRMED from the decompile.
  - A stem that fails to decode (`galactica:planet` unknown) is logged with `LOGGER.error` and skipped. The world loads.
  - The region files under `dimensions/galactica/` stay on disk, orphaned.
  - Where a player standing in a removed dimension ends up is UNCONFIRMED.
- **FIXED: no experimental-settings warning.**
  - `fabric-dimensions-v1` 5.1.19 **is** part of 0.161.0. It is in the fabric-api pom and on the compile classpath.
  - `WorldDimensionsMixin.betterModdedStabilityCheck` cancels `checkStability` at HEAD for non-`minecraft` stems whose `RegistrationInfo` has `knownPackInfo`. It returns that registration's lifecycle instead.
  - Mod-jar data packs (`ModNioPackResources`) carry `new KnownPack("vanilla", packId, modVersion)`.
  - `ResourceManagerRegistryLoadTask` maps a present `knownPackInfo` to `Lifecycle.stable()`, and maps an absent one to `experimental()`.
  - So `galactica:surface_k` and the existing `galactica:space` load as stable, with no warning screen. The mechanism is CONFIRMED from bytecode; not seen in game.
- **Storage path.** `DimensionType.getStorageFolder(key, root)` returns `root/dimensions/<ns>/<path>`. The signature compiles; the path layout is carried over. `MinecraftServer.getWorldPath(LevelResource.ROOT)` is CONFIRMED.
- **Wipe timing.** Fabric's `SERVER_STARTING` is injected at `@At(INVOKE, "MinecraftServer.initServer()Z")` in `runServer`, and `createLevels` runs later. `savedDataStorage` is assigned in the `MinecraftServer` constructor. CONFIRMED (javap of the Fabric mixin and MC).
- **Live wipe.** Don't do it. UNCONFIRMED in detail.

`SurfaceWipe.init/runPending/deleteTree` compiles unchanged. **NEW (needed for §4b):** register the slot loader in an earlier event phase, so `SurfaceSlots` is filled before Fabric's own `SERVER_STARTING` listener (default phase) fires `MODIFY_ATTRIBUTES`:
```java
static final Identifier EARLY = Identifier.fromNamespaceAndPath("galactica", "slots_first");
ServerLifecycleEvents.SERVER_STARTING.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);   // CONFIRMED (compiles)
ServerLifecycleEvents.SERVER_STARTING.register(EARLY, SurfaceWipe::runPending);
```

Recommended design (unchanged, a design opinion):
1. N slots with LRU eviction.
2. Evict with `pendingWipe` and wipe on the next restart.
3. On restart: wipe, rebuild `PlanetParams` from `planetId`, then the dimension attributes (§4b) are applied.
4. Keep terrain a pure function of the params.
5. Alternative: one tiled dimension.

## 3. SavedData: CONFIRMED (javap + compile)
- `record SavedDataType<T>(Identifier, Supplier<T>, Codec<T>, DataFixTypes)`. CONFIRMED.
- **FIXED:** the class is **`net.minecraft.world.level.storage.SavedDataStorage`**.
  - It has `computeIfAbsent(SavedDataType<T>)`, `get(SavedDataType<T>)`, `set(SavedDataType<T>, T)`, `readTagFromDisk(Path, DataFixTypes, int)`, `scheduleSave()` and `saveAndJoin()`.
  - `readTagFromDisk` calls `DataFixTypes.update(...)` on the type, so a null `DataFixTypes` throws inside a `catch (Exception)` block. **Keep the type non-null.** CONFIRMED.
- `MinecraftServer.getDataStorage()` and `ServerLevel.getDataStorage()` both return `SavedDataStorage`. CONFIRMED.
- `SavedData.setDirty()`, `setDirty(boolean)` and `isDirty()` CONFIRMED.
- **FIXED:** `server.getTickCount()` was listed as unchecked; it is now CONFIRMED (compiles, returns int).
- Using `DataFixTypes.SAVED_DATA_COMMAND_STORAGE` compiles. Its behaviour across future versions is UNCONFIRMED.

## 4. Biomes per planet type
- Biome JSON rules carry over from the existing doc. Positional `minecraft:visual/*` keys are fine in a biome; `sky_light_level` and `fast_lava` are rejected.
- `EnvironmentAttributes.SKY_COLOR`, `FOG_COLOR` and `WATER_FOG_COLOR` are typed `EnvironmentAttribute<Vector3fc>`. CONFIRMED (compiles).
- `PlanetBiomeSource` compiles unchanged:
  - Codec: `Biome.LIST_CODEC.fieldOf("biomes")` plus `slot`.
  - `collectPossibleBiomes` returns `biomes.stream()`.
  - The resolver returns a constant holder. The compiled file picks the index with `Math.floorMod(seed, biomes.size())`.

## 4b. Varying dimension_type attributes
- **FIXED: there is a server-side lever, applied once per server start.**
  - `net.fabricmc.fabric.api.dimension.v1.DimensionEvents.MODIFY_ATTRIBUTES` has the signature `(Holder<DimensionType>, EnvironmentAttributeMap.Builder, HolderLookup.Provider) -> void`.
  - It fires from Fabric's `SERVER_STARTING` listener (`DimensionModificationImpl.finalizeWorldGen`), before the levels exist.
  - Fabric rebuilds the map, writes it into the `DimensionType` through an accessor, and **clears `knownPackInfo`** on any changed entry. That makes registry sync send the modified type to clients instead of the client's own pack copy.
  - Unlike biomes, the dimension-type map also takes non-positional attributes.
  - With one `dimension_type` per slot, each slot gets its planet's sky, fog and gameplay attributes at every restart. That fits the restart-wipe scheme.
  - Mechanism CONFIRMED (decompile + compile). Not run in game.
  ```java
  DimensionEvents.MODIFY_ATTRIBUTES.register((dim, attrs, regs) -> {
      for (int k = 0; k < SurfaceSlots.N; k++)
          if (dim.is(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath("galactica", "surface_" + k))))
              attrs.set(EnvironmentAttributes.FOG_COLOR, (Vector3fc) SurfaceSlots.params(k).fogColour());  // add fogColour() to PlanetParams
  });
  ```
  This requires the early-phase registration from §2.
- **Lever 1:** per-biome attributes (§4).
- **Lever 2: client-only mixin. CONFIRMED (javap + compile).**
  - `ClientLevel` builds the attribute system as `EnvironmentAttributeSystem.builder()` → `addEnvironmentAttributeLayers(Builder)` (private, returns `Builder`) → `build()`.
  - Layer order:
    1. `addDefaultLayers`, which calls `addStaticLayers` and then `addDynamicLayers`.
    2. Two client `addTimeBasedLayer` calls.
    3. Your layer, added at RETURN, so it is last.
  - `Builder.addConstantLayer(EnvironmentAttribute<V>, EnvironmentAttributeLayer.Constant<V>)`, where `Constant.applyConstant(V)` returns `V`.
  - The mixin compiles unchanged. It is rebuilt per `ClientLevel`, so the params must reach the client before the teleport.
  - With the §4b lever you only need this for changes during a session.

## 5. Entering the dimensions: CONFIRMED (compiles)
- **Command:** `DimensionArgument.getDimension(CommandContext<CommandSourceStack>, String)` returns `ServerLevel`, so `/execute in galactica:surface_3 run tp ...` works with no extra code.
- **Mod teleport:**
  - `server.getLevel(ResourceKey<Level>)` (nullable)
  - `Level.getHeight(Heightmap.Types, int, int)`; whether it loads the chunk synchronously is inferred, not traced
  - `player.teleport(new TeleportTransition(ServerLevel, Vec3, Vec3, float, float, TeleportTransition.PLAY_PORTAL_SOUND))`
- **Cheaper height check:** `dest.getChunkSource().getGenerator().getBaseHeight(x, z, MOTION_BLOCKING, dest, dest.getChunkSource().randomState())`.

## Still UNCONFIRMED
- Running any snippet in game. They only compile.
- That the `MODIFY_ATTRIBUTES` change actually shows up on the client after registry sync. The code path is confirmed; the result was not observed.
- Whether a custom `world_clock` works.
- Where players end up after the mod is removed. The fail-soft decode itself is confirmed.
- Long-term behaviour of `SAVED_DATA_COMMAND_STORAGE`.
- The speed gain from the `LevelChunkSection` path.
- The vanilla call-site details of `ChunkStatusTasks.buildTerrain` and the `getStorageFolder` path layout. Both are carried over from earlier decompiles and were not re-run this pass.

Your relayed request about a mod-exclusive shader was not part of this task, so nothing was checked against it.