# Mod ecosystem for Galactica on Minecraft 26.3 (Fabric)

Recon date: 2026-10-07. Target: MC 26.3, Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 (the repo pin).

## How this was checked

- **Modrinth API.** Every "26.3 Fabric build" claim comes from `GET https://api.modrinth.com/v2/project/<slug>/version?game_versions=["26.3"]&loaders=["fabric"]`, queried 2026-10-07. When that returned nothing, I also queried `game_versions=["26.3"]` with any loader, and then the unfiltered version list, to find the newest supported version. Licence, loaders and source URL come from `GET /v2/project/<slug>`. Dependencies come from `GET /v2/version/<id>`. Every version below links to its Modrinth version page (`https://modrinth.com/<type>/<slug>/version/<id>`).
- **GitHub.** I read repository metadata, branches and `gradle.properties` through `api.github.com` and `raw.githubusercontent.com`. I read the Tectonic and Lithostitched sources and wikis directly.
- **Vanilla 26.3.** The height limits were read from the decompiled 26.3 jar with the minecraft-modding MCP (`DimensionType`, `NoiseSettings`, `BlockPos`).
- **Not checked.** CurseForge could not be checked: the file pages returned 404 to WebFetch, and their API needs a key. Where a mod exists only on CurseForge, this report does not cover it. "None yet" means none on Modrinth or on the project's GitHub/GitLab.
- **Earlier recon.** This report builds on `docs/recon/research_m7_all.md` (2026-10-06) and corrects it where newer builds have appeared.

Integration labels used below:

| Label | Meaning |
|---|---|
| **hard** | Hard dependency. |
| **optional** | Optional compat: `compileOnly`, `suggests`, and the code guarded by `OptionalMods`. |
| **dev** | Dev runtime or tooling only. |
| **reference** | Study the technique; do not depend on the mod. |
| **avoid** | Do not use. |

---

## 0. Facts that frame everything

### 0.1 Vanilla 26.3 height limits (verified in the 26.3 jar)

From `net.minecraft.world.level.dimension.DimensionType`:

- `BlockPos.PACKED_Y_LENGTH` is 12, so `Y_SIZE` is 4096 − 32 = **4064**.
- `MAX_Y` is **2031** and `MIN_Y` is **−2032**.
- The constructor and codec enforce these rules:
  - `height` is between 16 and 4064 and is a multiple of 16;
  - `min_y` is between −2032 and 2031 and is a multiple of 16;
  - `min_y + height ≤ 2032`;
  - `logical_height ≤ height`.
- `NoiseSettings` (the `noise` block of `noise_settings`) applies the same guard. `NoiseSettings.clampToHeightAccessor` clamps the noise range to the level's range.

So the tallest possible dimension is `min_y −2032, height 4064` (Y −2032 to 2031). There are no "2032 or 4064 build height" mods beyond this: they are just datapacks or config at these numbers. The `4064` mod (https://modrinth.com/mod/4064, Unlicense) stops at 1.20.6 and has no 26.3 build. In 26.3 a plain datapack `dimension_type` reaches the same limit.

### 0.2 Galactica today

- `galactica:surface_N` uses `min_y −256, height 768`. Its source is `src/main/resources/data/galactica/dimension_type/surface.json`.
- `TerrainSampler` is a **2.5D heightmap generator**:
  - `MIN_Y = −256`, `MAX_Y = 511`, `SEA_LEVEL = 96`;
  - relief amplitude `60/g` blocks, clamped to `[20, 250]`;
  - `PlanetChunkGenerator` fills columns from it.
- There are no density functions, no noise router and no 3D noise. This is why the noise-router mods below cannot run *inside* our planets: they only change `NoiseBasedChunkGenerator` and its `noise_settings`. They are useful to us as **techniques**, and for the **Overworld (Earth)**, which is vanilla `NoiseBasedChunkGenerator`.
- **Evidence of what height costs, from this repo** (README, "Desempenho"). `galactica:space` was cut from 4064 to 1536 blocks because each floating-origin recenter generated, sent, saved and meshed about 1,100 columns of 254 sections. That ended in `OutOfMemoryError` at `-Xmx4G`. Taller planet slots pay the same per-section cost on every chunk load, though not on a recenter loop.

---

## 1. Terrain generation and editing

### 1.1 Candidates

| Mod | Link | Licence | 26.3 Fabric build (exact) | What it would do for Galactica | Approach | Risks |
|---|---|---|---|---|---|---|
| **Lithostitched** | https://modrinth.com/mod/lithostitched | MIT | **`2.0.4-fabric-26.3`**, `lithostitched-2.0.4-fabric-26.3.jar`, 2026-09-26 ([version](https://modrinth.com/mod/lithostitched/version/FhKHD2LJ)). Requires only fabric-api. | The library under Tectonic and Terralith. Data-driven worldgen modifiers (list in 1.2), extra density functions, biome injectors. Lets Earth be reshaped compatibly. | **optional** (Earth only); **reference** for planets | Its modifiers act on registries (`noise_settings`, `density_function`, `dimension_type`), not on our custom `ChunkGenerator`. Applied after registry load, so it changes `minecraft:overworld` for every world. |
| **Tectonic** | https://modrinth.com/mod/tectonic | MIT ([GitHub](https://github.com/Apollounknowndev/tectonic)) | **`3.0.31-fabric-26.3`**, `tectonic-3.0.31-fabric-26.3.jar`, 2026-09-27 ([version](https://modrinth.com/mod/tectonic/version/j2jmkU85)). Requires lithostitched and fabric-api. | Continents thousands to tens of thousands of blocks wide, mountain ranges "high enough to approach the build limit", deeper oceans, underground rivers. Configurable Overworld height. The best single answer to "make Earth bigger and taller". | **optional** (suggest for Earth) | Its Modrinth page still says "supported versions right now are 1.21.1 and 26.1", so 26.3 fixes may lag. It mutates the overworld `DimensionType` record in place with an accessor mixin (see 1.3), so any Galactica code that caches Overworld heights must read them live. |
| **Terralith** | https://modrinth.com/mod/terralith | Stardust Labs License (`LicenseRef-Stardust-Labs-License`) | **`2.6.5+26.3`**, `Terralith_26.3_v2.6.5+26.3.jar`, 2026-09-27 ([version](https://modrinth.com/mod/terralith/version/aGE3hYAA)). Requires lithostitched. | 95+ biomes, terrain types (canyons, shattered, floating islands, trenches). Works with the Tectonic mod, which has Terratonic built in. | **optional** (Earth) | Proprietary licence: never bundle or copy its JSON. It cannot be removed from an existing world. The mod and datapack versions are not interchangeable. |
| **Terratonic** (datapack) | https://modrinth.com/datapack/terratonic | MIT | No Fabric jar (datapack only). Datapack **`3.0.31`**, `terratonic-datapack-3.0.31.zip`, 2026-10-06 ([version](https://modrinth.com/datapack/terratonic/version/RUBbKGx8)). | Tectonic + Terralith for datapack users. | **avoid** (use the Tectonic mod, which includes it) | Duplicates the mod. |
| **Continents** | https://modrinth.com/mod/continents | Stardust Labs License | **`1.2.1+26.3`**, `Continents_26.3_v1.2.1+26.3.jar`, 2026-09-28, no deps ([version](https://modrinth.com/mod/continents/version/JfG5Jj69)) | Overworld landmasses further apart; spawn on a ~1k-block continent. | **optional** (Earth; alternative to Tectonic) | It overlaps Tectonic's continents, so pick one. Proprietary licence. |
| **JJThunder To The Max** | https://modrinth.com/datapack/jjthunder-to-the-max | All Rights Reserved | **None yet.** Newest is `0.9.0` for 26.2, datapack, 2026-07-20. | Extreme tall terrain (reference only). | **avoid** (ARR, no 26.3) | No 26.3 build. ARR. |
| **Larion World Generation** | https://modrinth.com/mod/larion-worldgen | Apache-2.0 | **None yet.** Newest supports 1.21.2–1.21.4. | Huge "disc world" style terrain; good technique reference (Apache). | **reference** | Stale. |
| **World Blender** | https://modrinth.com/mod/worldblender-fabric | LGPL-3.0 | **None yet.** Stops at 1.17.1. | — | **avoid** | Dead. |
| **Big Globe** | https://modrinth.com/mod/big-globe | All Rights Reserved | **None yet.** Newest `6.2.1` for 26.1–26.1.2, 2026-09-27. GitHub branch `V6` is on 26.1.2 ([gradle.properties](https://github.com/Builderb0y/BigGlobe/blob/V6/gradle.properties)). | A 2048-tall Overworld with its own script-driven generator. Its description says vanilla density functions "can't replicate the equations Big Globe needs". | **reference** (architecture only: a custom generator, like ours) | ARR. It replaces the world type, which conflicts with "Earth = vanilla Overworld". |
| **Lithosphere** | https://modrinth.com/mod/lithosphere | All Rights Reserved | **None yet.** Newest `1.8.2+mod` for 26.1.1–26.2, 2026-08-28. | Terrain overhaul. | **avoid** | ARR, no 26.3. |
| **Geophilic** | https://modrinth.com/mod/geophilic | All Rights Reserved | **`3.7`**, `Geophilic v3.7.mod.jar`, 2026-09-24 ([version](https://modrinth.com/mod/geophilic/version/5kFV8CEz)) | Vanilla biome features polish; compatible with Tectonic. | **optional** (player choice, nothing to code) | ARR; cosmetic only. |
| **TerraBlender** | https://modrinth.com/mod/terrablender | LGPL-3.0 | **`26.3.0.0.9`** (beta), `TerraBlender-fabric-26.3-26.3.0.0.9.jar`, 2026-10-02 ([version](https://modrinth.com/mod/terrablender/version/xirJYe1V)) | Biome-region API for Overworld biome mods. Tectonic is compatible with it. | **avoid** for us (our biomes come from `PlanetBiomeSource`) | Beta. Not needed. |
| **Biolith** | https://modrinth.com/mod/biolith | LGPL-3.0 | **`3.8.0-beta.1`**, `biolith-fabric-3.8.0-beta.1.jar`, 2026-09-23 ([version](https://modrinth.com/mod/biolith/version/K3CS4Akl)) | The same role as TerraBlender. | **avoid** | Beta. Not needed. |
| **Fast Noise (zfastnoise)** | https://modrinth.com/mod/zfastnoise | MPL-2.0 ([Codeberg](https://codeberg.org/ZenXArch/FastNoise)) | **`1.1.1+26.3`**, `zfastnoise-1.1.1+26.3.jar`, 2026-10-01 ([version](https://modrinth.com/mod/zfastnoise/version/cCGI5KLE)). Requires **zconfig `1.0.0+26.x`** ([version](https://modrinth.com/mod/zconfig/version/tsgt79sG)); optional zfastsurface. | Replaces `populateNoise`, `populateBiomes` and the surface builder of `NoiseChunkGenerator`, with vanilla parity. It claims 10–18% faster Overworld worldgen. | **dev** (test in the perf stack for Earth) | It only touches `NoiseBasedChunkGenerator`, so there is no gain on `surface_N`. Declared incompatible with anti-xray and moonrise-opt. Interaction with C2ME is untested. |
| **Fast Surface (zfastsurface)** | https://modrinth.com/mod/zfastsurface | MPL-2.0 | **`1.0.0+26.3`**, `zfastsurface-1.0.0+26.3.jar`, 2026-09-28 ([version](https://modrinth.com/mod/zfastsurface/version/yOBmJfgG)) | Faster surface rules (Overworld). | **dev** | Same as zfastnoise. |
| **Noisium / NoisiumForked** | https://modrinth.com/mod/noisium, https://modrinth.com/mod/noisiumforked | LGPL-3.0 | **None yet.** Noisium stops at 1.21.6. NoisiumForked's newest is `2.8.5+mc26.2`, 2026-05-30. | Worldgen speed-ups. | **avoid** (use zfastnoise and C2ME) | No 26.3. |
| **C2ME** | https://modrinth.com/mod/c2me-fabric | MIT | **`0.4.2-alpha.0.90+26.3`**, `c2me-fabric-mc26.3-0.4.2-alpha.0.90.jar`, 2026-10-07 ([version](https://modrinth.com/mod/c2me-fabric/version/ODMLK8M9)). The repo pins `0.89` (`FXjQDzq7`). | Parallel chunk generation and I/O, plus vectorised noise math. Already in the perf stack. | **dev** (already) | Alpha. `@Overwrite`s `ChunkMap.save` (MODLOG gotcha 5). |
| **ScalableLux** | https://modrinth.com/mod/scalablelux | LGPL-3.0 | **`0.3.0-alpha.0.6+26.3`**, `ScalableLux-fabric-mc26.3-0.3.0-alpha.0.6-all.jar`, 2026-09-16 ([version](https://modrinth.com/mod/scalablelux/version/g4eqNSKd)) | Parallel light engine (the Starlight successor from the C2ME team). Lighting is a major cost of tall dimensions. | **dev** (benchmark with tall planet slots) | Alpha. Starlight itself stops at 1.20.4 ([page](https://modrinth.com/mod/starlight)). |
| **Structure Layout Optimizer** | https://modrinth.com/mod/structure-layout-optimizer | MIT | **`1.1.4+26.3-fabric`**, `structure_layout_optimizer-1.1.4+26.3-fabric.jar`, 2026-09-16 ([version](https://modrinth.com/mod/structure-layout-optimizer/version/crWm7jXS)) | Faster jigsaw structure placement (Overworld). | **dev** (perf-stack candidate) | None significant. |
| **More Density Functions** | https://modrinth.com/mod/more-density-functions | MIT | **None yet.** Newest `2.3.0` for 1.21.10 (NeoForge), 2026-03-22. | Extra density-function types. | **reference** | Stale for 26.x. Lithostitched covers most of it. |
| **Worldgen Devtools** (jacobsjo) | https://modrinth.com/mod/worldgen-devtools | MIT | **`1.4.2+26.3`**, 2026-10-03. The file is named `worldgenDevtools-1.4.1+26.3.jar` ([version](https://modrinth.com/mod/worldgen-devtools/version/Ovlis4GJ)). | In-game density-function and noise visualisation, plus a worldgen reload. Ideal for tuning Earth with Tectonic. | **dev** | Mismatch between the version label and the jar name (cosmetic). |
| **Chunky** | https://modrinth.com/mod/chunky | GPL-3.0 | **`1.5.3`**, `Chunky-Fabric-1.5.3.jar`. One jar is tagged 26.1 to 26.3 ([version](https://modrinth.com/mod/chunky/version/4Eotm6ov)). | Pre-generation. A repeatable benchmark for `surface_N` with taller slots, and for Earth with Tectonic. | **dev** | GPL: runtime tool only, never linked. |
| **WorldEdit** | https://modrinth.com/mod/worldedit | GPL-3.0 | **`7.4.6-beta-02`**, `worldedit-mod-7.4.6-beta-02.jar`, 2026-09-24 ([version](https://modrinth.com/mod/worldedit/version/E7sbeRFx)) | Terrain editing and test fixtures in dev worlds. | **dev** | Beta; GPL (runtime only). |
| **Axiom** | https://modrinth.com/mod/axiom | All Rights Reserved | **`6.1.3`**, `Axiom-6.1.3-for-MC26.3.jar`, 2026-09-25 ([version](https://modrinth.com/mod/axiom/version/CrfjL7cY)) | Sculpt and paint terrain editor (player-side). | **dev** (optional) | ARR. Never bundle it. |
| **Cubic Chunks–style vertical extension** | https://modrinth.com/mod/cubicchunks | MIT | **None.** Stops at 1.12.2. Nothing comparable exists for 26.x on Modrinth (searched "cubic chunks", "height extender", "build height"). | Unlimited vertical extent. | **avoid** | It does not exist for 26.3. The vanilla 4064 ceiling is the hard limit. |
| **Height-extension mods** (`4064`, `buildheightlimit`, `incendium-build-height`) | https://modrinth.com/mod/4064 | Various | `4064`: none (1.20.6). `buildheightlimit` 1.2 is a Paper/Spigot plugin, not Fabric. `incendium-build-height` is Nether-only. | — | **avoid** (a `dimension_type` JSON does the same) | — |

### 1.2 How these mods achieve large and tall terrain

**The vanilla mechanism they all build on.** `noise_settings` holds:

- a `noise` block (`min_y`, `height`, `size_horizontal`, `size_vertical`), and
- a `noise_router` of named density functions: `continents`, `erosion`, `ridges`, `depth`, `initial_density_without_jaggedness`, `final_density`, `preliminary_surface_level` and others.

Terrain is solid where `final_density > 0`. Height comes from four places:

1. **A vertical gradient.** In vanilla, `depth` is a `y_clamped_gradient` plus an `offset` spline over continentalness, erosion and ridges.
2. **A `factor` spline.** It divides the gradient: low factor gives tall, steep terrain.
3. **A `jaggedness` spline** for peaks.
4. **3D noise** for overhangs and caves.

`final_density` is computed on a coarse cell grid (4 × 8 × 4 blocks for the overworld's `size_horizontal 1`, `size_vertical 2`) and trilinearly `interpolated`. That is why vanilla can afford 3D noise.

**Tectonic.** Read from https://github.com/Apollounknowndev/tectonic, branch `rewrite-squared`.

- **The whole noise router is replaced.** It ships its own `data/minecraft/worldgen/noise_settings/overworld.json` and its own `density_function/overworld/noise_router/*.json`.
- **`final_density`** is `add(min(squeeze(mul(0.64, interpolated(blend_density(... slope_lower/slope_upper ... min(tectonic:base_terrain, tectonic:caves))))), ...)`.
- **`tectonic:depth`** is `cache_once(add(y_clamped_gradient(from_y −2048, to_y 2048, from_value 17, to_value −15), tectonic:terrain_spline/offset/final))`. The gradient is defined over the whole ±2048 range, so terrain keeps a sensible shape at any configured height.
- **Config knobs** (from [the Tectonic wiki, Config page](https://github.com/Apollounknowndev/tectonic/wiki/Config)) are baked into constant density functions such as `tectonic:__constants/*`. They are:
  - **Vertical Scale**: stretches terrain above sea level;
  - **Continents Scale**, **Erosion Scale** and **Ridge Scale**: horizontal noise scales (lower values give bigger features);
  - **Ocean Depth**, **Deep Ocean Depth** and **Ocean Offset**;
  - **Ultrasmooth**.
- **Height limits.**
  - `HeightLimits.java` accepts either the legacy `increased_height` flag (sets `min_y −64`, `max_y 640`) or explicit **`min_y`/`max_y`**, both multiples of 16.
  - A Lithostitched worldgen modifier, `SetHeightLimitsModifier`, writes them into the `minecraft:overworld` `DimensionType` record and its `NoiseSettings` via accessor mixins (`DimensionTypeAccessor`, `NoiseSettingsAccessor`). It then calls `WorldgenModifier.resetRegistrationInfo` so that clients receive the new dimension type.
- **Other mixins** cover heightmaps, carvers, blending and structure pieces, so that features cope with taller worlds.
- **Biomes** are still placed by the vanilla multi-noise climate. Tectonic only changes the climate parameters (continentalness, erosion, ridges) and the terrain they produce, which keeps it compatible with TerraBlender, Biolith and Terralith.

**Lithostitched.** Its worldgen modifiers are listed at https://github.com/Apollounknowndev/lithostitched/wiki/Worldgen-Modifier-Types. Exact type IDs:

- **Terrain**
  - `lithostitched:wrap_density_function`: fields `priority` (ascending, default 1000), `target_function`, and `wrapper_function`, which contains a `lithostitched:wrapped_marker`.
  - `lithostitched:wrap_noise_router`: wraps a single noise-router field.
  - These two are how several packs stack terrain changes without overwriting each other's JSON.
- **Surface and dimension**
  - `lithostitched:add_surface_rule`, with `levels`, `surface_rule` and `injection_type` (`prepend` or `append`);
  - `lithostitched:set_dimension_attributes`;
  - `lithostitched:set_timeline_tracks`.
- **Biomes and features**
  - `add_biome_spawns`, `remove_biome_spawns`, `replace_climate`, `replace_effects`, `set_biome_attributes`;
  - `add_features`, `remove_features`, `stack_feature`.
- **Structures**
  - `add_structure_set_entries`, `remove_structure_set_entries`, `set_structure_spawn_condition`, `add_structure_templates`;
  - `add_template_pool_elements`, `set_pool_element_processors`, `set_pool_aliases`;
  - `add_processor_list_processors`.
- **Misc**: `no_op`.

Density-function types (https://github.com/Apollounknowndev/lithostitched/wiki/Density-Function-Types):

- `lithostitched:sin`, `cos`, `floor`, `ceil`, `sqrt`;
- `axis`;
- `mix`, with `input`, `argument1` and `argument2`;
- `shift`, with `input` and `shift_x/y/z`;
- `select`, with `input`, `fallback` and `selections`;
- `fast_noise`, with `config`, `xz_scale`, `y_scale`, `shift_x` and `shift_z`.

**Big Globe.** It sets the Overworld to 2048 blocks tall (Nether and End to 1024) and generates with its own script language rather than density functions, as its Modrinth description says. It is the closest analogue to our approach: a custom generator.

**Continents and Terralith** stay inside vanilla height. They change the climate-noise layout and biome placement, not vertical range.

### 1.3 (a) Letting these mods shape Earth (the Overworld)

1. **What to declare.** Put `tectonic`, `lithostitched`, `terralith` and `continents` under `suggests` in `fabric.mod.json`. Add no `compileOnly` dependency: we need no API from them.
2. **What to audit.** Every place Galactica assumes Overworld heights. From a grep of `src/main/java`, no literal `320` or `−64` is used for the Overworld, and `FloatingOrigin` and `PlanetChunkGenerator` read `level.getMinY()`/`getMaxY()`. Still audit two areas:
   - **`planet/transfer/EarthOverworld` and `TransferAltitude`.** When a ship enters Earth from orbit, the hand-off altitude must use the live `level.getMaxY()`. Tectonic can raise it to anything up to 2031.
   - **The Earth DH generator path.** If we ever override DH generation for the Overworld (`compat/dh`), we must defer to vanilla chunks (`EDhApiWorldGeneratorReturnType.VANILLA_CHUNKS`) when Tectonic is present. Otherwise our LOD shows a different Earth from the real chunks.
3. **What to tell players.** Tectonic's Overworld height and the sky need a check. `EarthSun`, clouds (`cloud_height`) and our orbit/approach thresholds assume an atmosphere top. A 1,000+ block Overworld puts mountain tops into what our entry logic may treat as "high atmosphere".
4. **What to test.** Add an autotest tag, `-Dgalactica.autotest.tag=tectonic`, run with `localRuntime` Tectonic and Lithostitched behind `-Pgalactica.tectonic=true` (the same pattern as `galactica.dh` and `galactica.bobby`). Run the `overworld_day` PerfBench scene and the take-off and landing scenarios.

### 1.4 (b) Reusing their techniques inside `PlanetChunkGenerator`

Our generator is not noise-router based, so Lithostitched modifiers cannot target `surface_N`. These are the techniques worth porting into `TerrainSampler` and `PlanetMacro`, in order of payoff:

1. **Taller slots.** Change `surface.json` and `TerrainSampler.MIN_Y`/`MAX_Y` together.
   - The limits are multiples of 16, with `min_y + height ≤ 2032` (section 0.1).
   - Example: `min_y −512, height 2048` (Y −512 to 1535) raises the ceiling about 3×.
   - Raise `MAX_RELIEF` (today 250) to match, for example 900 for low-gravity bodies, and keep the `60/g` law.
   - Column sections go from 48 to 128.
2. **A Tectonic-style vertical scale.** Separate a *vertical scale* from the horizontal noise scales: one constant that multiplies elevation above sea level only. This is exactly Tectonic's "Vertical Scale". It gives taller mountains without deeper oceans, and is cheap because it acts on the macro elevation per column.
3. **Continent and erosion scales.** Tectonic gets continents "thousands to tens of thousands of blocks wide" by sampling continentalness at a very low frequency, with erosion and ridges at separate, independently scaled frequencies. `PlanetMacro` already samples on the unit sphere; expose per-body `continentsScale`, `erosionScale` and `ridgeScale` the same way.
4. **3D density only in a band.** For overhangs, cliffs, arches and caves, use vanilla's cell trick:
   - evaluate a 3D density only for `y ∈ [h − A₃, h + A₃]` around the heightmap surface `h`, on a 4 × 8 × 4 grid;
   - trilinearly interpolate, as vanilla's `interpolated` does;
   - make everything below the band solid and everything above it air.
   This keeps the 2.5D speed for 95%+ of the column. Gate it behind `BodyType`, so only rocky worlds with an atmosphere get overhangs. The LOD and DH path (`GalacticaDhGenerator`, `LodColumnPlan`) keeps using the 2.5D `height()`. That matches what DH can show anyway.
5. **A full-range gradient.** Define the base gradient over the whole possible Y range, as Tectonic's `y_clamped_gradient` −2048 to 2048 does. Re-tuning the slot height then does not reshape the terrain.
6. **Fast noise.** `lithostitched:fast_noise` wraps the FastNoiseLite-style config. In plain Java we can sample OpenSimplex2 or cellular noise directly; FastNoiseLite is MIT and a single file. That is cheaper than vanilla `NormalNoise` octaves for ridged and cellular terrain.

**Performance and lighting costs of taller slots** (estimates; measure with the existing `planet_chunkgen` PerfBench scene and Chunky):

- **Sections.** Each column holds `height/16` `LevelChunkSection`s: 48 now, 128 at 2048, 254 at 4064. Sections that are all air are cheap: a single-value palette, and they are skipped by meshing and lighting propagation. They are still allocated, serialised and sent. The space-dimension OOM in this repo came from exactly this multiplier, at about 1,100 columns × 254 sections.
- **Fill cost.** Our fill cost is linear in the *solid* blocks. A deeper `min_y` adds stone to every column. Height above the surface adds almost nothing to generation, but it does add to sky-light initialisation and storage.
- **Lighting.** Sky light must be propagated down through every column, and cliffs and overhangs taller than vanilla increase light-update work at chunk load. Benchmark ScalableLux (alpha) in the perf stack, after C2ME.
- **Rendering.** Sodium meshes only non-empty sections, so tall air is free. Tall *mountains* add real geometry, and DH LOD carries the far view.

---

## 2. Rendering and LOD

| Mod | Link | Licence | 26.3 Fabric build (exact) | What it would do | Approach | Risks |
|---|---|---|---|---|---|---|
| **Distant Horizons** | https://modrinth.com/mod/distanthorizons | LGPL-3.0 | **`3.3.4-26.3`**, `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`, 2026-09-29 ([version](https://modrinth.com/mod/distanthorizons/version/TGgEbP9A)) | LOD terrain to the horizon on Earth and planets. Galactica already registers `GalacticaDhGenerator` (`API_DATA_SOURCES`) per planet slot. | **optional** (already) | It renders with its own pipeline (OpenGL or Vulkan); shaders need DH support. One generator per level. |
| **Distant Horizons API** | https://modrinth.com/mod/distanthorizonsapi | LGPL-3.0 | **`7.2.0`**, `DistantHorizonsApi-7.2.0.jar`, 2026-09-21 ([version](https://modrinth.com/mod/distanthorizonsapi/version/LTP8vW3B)) | `compileOnly` API (already pinned). | **optional** (already) | — |
| **Voxy** | https://modrinth.com/mod/voxy | All Rights Reserved | **None yet** on Modrinth. Newest `0.2.19-beta` (26.2, 2026-08-29). A GitHub branch [`263`](https://github.com/MCRcortex/voxy/tree/263) targets `minecraft_version=26.3` with Sodium `mc26.3-0.9.2` (last commit 2026-10-04, "Fixed the remapper"). | An alternative LOD with OpenGL 4.6. | **avoid for now** (watch) | ARR. Its Modrinth page forbids unofficial builds, so no dev builds from the branch. I found no public world-generator API, so our planets would be LOD'd only after exploration. |
| **Voxy WorldGen** | https://modrinth.com/mod/voxy-worldgen | Custom licence | **None yet** (newest `2.4.3`, 26.2) | Pre-gen for Voxy. | **avoid** | — |
| **Bobby** | https://modrinth.com/mod/bobby | LGPL-3.0 | **`5.2.16+mc26.3`**, `bobby-5.2.16+mc26.3.jar`, 2026-10-06 ([version](https://modrinth.com/mod/bobby/version/J7YnfCss)) | A client chunk cache beyond the server view distance. | **optional** (already opt-in) | It caches `galactica:space` chunks that the floating origin keeps invalidating. Exclude the space dimension. |
| **Nvidium** | https://modrinth.com/mod/nvidium | LGPL-3.0 | **`0.4.4-beta7-26.3`**, `nvidium-0.4.4-beta7-26.3.jar`, 2026-09-23. Requires Sodium ([version](https://modrinth.com/mod/nvidium/version/8mVK1zbk)). | NVIDIA mesh-shader terrain: huge render distances on Turing and newer. | **optional** (player choice; just test that nothing breaks) | NVIDIA only, beta. It replaces Sodium's terrain renderer, so it must be retested with our sky passes and `NearPlanetRenderer`. |
| **Sodium** | https://modrinth.com/mod/sodium | PolyForm Shield 1.0.0 | **`mc26.3-0.9.2-fabric`**, `sodium-fabric-0.9.2+mc26.3.jar` ([version](https://modrinth.com/mod/sodium/version/bAZQdGpg)); `0.9.3-alpha.1` exists | Already in the perf stack. | **optional** (already) | PolyForm Shield: do not fork or redistribute. |
| **Sodium Extra** | https://modrinth.com/mod/sodium-extra | LGPL-3.0 | **`mc26.3-0.9.4+fabric`**, `sodium-extra-fabric-0.9.4+mc26.3.jar`, 2026-09-16 ([version](https://modrinth.com/mod/sodium-extra/version/te2y9qZn)) | Fog, sky and cloud toggles. | **optional** (test only) | Its sky and fog toggles may hide our `END_SKY`-based sky. Test it. |
| **Reese's Sodium Options** | https://modrinth.com/mod/reeses-sodium-options | MIT | **`mc26.3-2.2.5+fabric`**, `reeses-sodium-options-fabric-2.2.5+mc26.3.jar`, 2026-09-24 ([version](https://modrinth.com/mod/reeses-sodium-options/version/EfchygKx)) | Options UI. | **avoid** (no value to us) | — |
| **Iris** | https://modrinth.com/mod/iris | LGPL-3.0 | **`1.11.7+26.3-fabric`**, `iris-fabric-1.11.7+mc26.3.jar`, 2026-09-30 ([version](https://modrinth.com/mod/iris/version/vTN4NRGW)) | Already a `compileOnly` dependency. | **optional** (already) | — |
| **Veil** | https://modrinth.com/mod/veil | LGPL-3.0 | **None yet.** Newest `4.5.1`, 1.21.1 only. The GitHub default branch is `1.21`. | Deferred render pipeline, post-processing, lights. | **avoid** (blocker) | No 26.x port. |
| **Satin API** | https://modrinth.com/mod/satin-api | LGPL-3.0 | **None yet.** Newest `3.0.0-alpha.1`, 1.21.2–1.21.4. | Post-process shader helper. | **avoid** | Dead; renderpearl replaced the GL layer anyway. |
| **Flywheel** | https://github.com/Engine-Room/Flywheel | MIT | **None.** The Modrinth slug `flywheel` is the legacy 1.19 project; the GitHub default branch is `26.1.2/dev`. | Instanced rendering for many moving objects (ship fleets). | **reference** | No 26.3. |
| **Lodestone** (Team Lodestar) | https://modrinth.com/mod/lodestone-lib is a *different* project | ARR (Team Lodestar) | **None yet.** Earlier recon found the newest is 26.1.2. The Modrinth slug `lodestone-lib` is an unrelated Jaxydog library (1.21.8). | VFX, screen shake, world-space particles. | **avoid** | ARR, no 26.3. |
| **Effective** | https://modrinth.com/mod/effective | All Rights Reserved | **None yet** (newest `2.4.8-1.21.1`) | Splash and ambience particles. | **avoid** | — |
| **Photon** (Low-Drag VFX) | https://github.com/Low-Drag-MC/Photon | GitHub `NOASSERTION` | **None.** Branches reach `26.2`, and its `gradle.properties` on `26.2` targets **NeoForge** `26.2.0.67`. (The Modrinth slug `photon` is an unrelated Paper plugin.) | A node-based particle and VFX editor. | **avoid** | NeoForge, no 26.3. |
| **LDLib (2)** | https://modrinth.com/mod/ldlib | LGPL-3.0 | **No Fabric build.** 26.3 exists only for NeoForge: `mc26.3-26.3.2.41-neoforge`, 2026-09-25. | UI and editor framework behind Photon and Multiblocked2. | **avoid** | No Fabric. |
| **Particle Core** | https://modrinth.com/mod/particle-core | MIT | **None yet** (newest `0.3.3+26.2`) | Particle optimisation. | **avoid** | — |
| **Immersive Portals** | https://modrinth.com/mod/immersiveportals | Apache-2.0 | **None.** Modrinth newest is `5.2.0` (1.20.1). GitHub `iPortalTeam/ImmersivePortalsMod` releases stop at `v6.0.6-mc1.21.1` (2024-11-29), and its newest branch is `1.21.3`. | Seamless cross-dimension rendering (space to surface). | **reference** (blocker) | Effectively abandoned for 26.x. Our own seamless-entry plan in `research_m7_all.md` B.3–B.4 stands. |
| **Valkyrien Skies 2** | https://modrinth.com/mod/valkyrien-skies | LGPL-3.0 | **None.** Modrinth newest is 1.20.1; GitHub has `1.21.1/main`. | Block-built ships with physics. | **reference** | No 26.x. Kotlin plus a native physics engine (Krunch). |
| **Sable** | https://modrinth.com/mod/sable | PolyForm Shield 1.0.0 | **None.** Newest `2.0.6+mc1.21.1` (2026-10-03); `gradle.properties` on `main` is `minecraft_version=1.21.1`. | "Interactive physics moving block structures" (used by Create Aeronautics). | **avoid** | PolyForm Shield forbids competing use. 1.21.1 only. |
| **Cosmos** (HollowedWanderer) | https://modrinth.com/mod/cosmos-mod | All Rights Reserved | **`1.0.3-26.3`**, `cosmos-1.0.3-26.3.jar`, 2026-09-23 ([version](https://modrinth.com/mod/cosmos-mod/version/ptSxOyVP)) | A vanilla-sky star renderer. | **avoid** (conflict test only) | It overrides the Overworld stars that our `realNightSky`, `EarthSun` and Yale catalogue draw. Expect both skies to draw. Detect it and warn, or turn our Earth night sky off. |

**Distant Horizons API for custom generators** (read from GitLab `distant-horizons-team/distant-horizons-core`, `main`, `api/`):

- `DhApi.worldGenOverrides.registerWorldGeneratorOverride(IDhApiLevelWrapper, IDhApiWorldGenerator)` returns `DhApiResult<Void>`. "Only one world generator can be registered for a specific level at a given time." On a clash it returns `success = false` with the name of the existing generator. There is no priority system ([source](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/api/src/main/java/com/seibel/distanthorizons/api/interfaces/override/worldGenerator/IDhApiWorldGeneratorOverrideRegister.java)). "Per dimension" therefore means per level wrapper, which is what `GalacticaDh` already does for each `surface_N`.
- `IDhApiWorldGenerator` ([source](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/api/src/main/java/com/seibel/distanthorizons/api/interfaces/override/worldGenerator/IDhApiWorldGenerator.java)) has:
  - `getSmallestDataDetailLevel()` and `getLargestDataDetailLevel()` (API 1.0.0);
  - `runApiValidation()` (4.0.0);
  - `generateChunks(...)`, which returns `Object[]` vanilla chunks (1.0.0);
  - `generateApiChunks(...)`, which returns `DhApiChunk` (2.0.0);
  - `generateLod(chunkPosMinX, chunkPosMinZ, lodPosX, lodPosZ, detailLevel, IDhApiFullDataSource pooled, EDhApiDistantGeneratorMode, ExecutorService, Consumer<IDhApiFullDataSource>)` (4.0.0);
  - `getReturnType()`, defaulting to `VANILLA_CHUNKS` (2.0.0);
  - `preGeneratorTaskStart()` and `close()`.
- For Earth with Tectonic, keep DH's default generator (no override). It runs the real `NoiseBasedChunkGenerator`, so Tectonic terrain shows in LOD automatically.
- Unverified: how DH handles a level 2048–4064 blocks tall in `API_DATA_SOURCES`. Test it before raising `surface.json`.

---

## 3. Core and library mods

| Mod | Link | Licence | 26.3 Fabric build (exact) | Use for Galactica | Approach | Risks |
|---|---|---|---|---|---|---|
| **Fabric API** | https://modrinth.com/mod/fabric-api | Apache-2.0 | **`0.162.0+26.3`**, `fabric-api-0.162.0+26.3.jar`, 2026-10-06 ([version](https://modrinth.com/mod/fabric-api/version/v2j28coa)). The repo pins `0.161.0+26.3`. | Networking payloads, data attachments, datagen, GameTest, `DynamicRegistries`, events. | **hard** (already). Bump to 0.162.0 after a full autotest. | Minor. |
| **YACL** | https://modrinth.com/mod/yacl | LGPL-3.0+ | **`3.9.7+26.3-fabric`**, `yet_another_config_lib_v3-3.9.7+26.3-fabric.jar` ([version](https://modrinth.com/mod/yacl/version/s9SjoFu1)) | Config screen. | **optional** (already) | — |
| **Cloth Config** | https://modrinth.com/mod/cloth-config | LGPL-3.0 | **`26.3.159+fabric`**, `cloth-config-fabric-26.3.159.jar` ([version](https://modrinth.com/mod/cloth-config/version/fg2uyxOW)) | Alternative to YACL. | **avoid** (YACL chosen) | — |
| **owo-lib / owo-ui** | https://modrinth.com/mod/owo-lib | MIT | **None yet.** Newest `0.13.1+26.2` (2026-08-19). GitHub has branch `26.2`, no `26.3`. | A declarative UI (XML) for a galaxy map and codex screens. | **avoid now** (blocker) | — |
| **Modern UI** | https://modrinth.com/mod/modern-ui | LGPL-3.0+ | **None yet** (newest `3.13.0.5`, 26.1.x) | — | **avoid** | It replaces vanilla text and GUI rendering globally. |
| **Mod Menu** | https://modrinth.com/mod/modmenu | MIT | **`21.0.0`**, `modmenu-21.0.0.jar` ([version](https://modrinth.com/mod/modmenu/version/kyy7dbrZ)) | Config entry point. | **optional** (already) | — |
| **GeckoLib** | https://modrinth.com/mod/geckolib | MIT | **`5.5.7`**, `geckolib-fabric-26.3-5.5.7.jar` ([version](https://modrinth.com/mod/geckolib/version/kSxHvs99)) | Animated ships. | **optional** (already) | — |
| **Player Animation Library** | https://modrinth.com/mod/player-animation-library | MIT | **`1.2.8`** (beta), `PlayerAnimationLibMerged-1.2.8+mc.26.3.jar`, 2026-10-06 ([version](https://modrinth.com/mod/player-animation-library/version/xJ30QmpE)) | Seated pilot and EVA poses. | **optional** | Beta. |
| **Sound Physics Remastered** | https://modrinth.com/mod/sound-physics-remastered | GPL-3.0 | **`fabric-1.5.1+26.3`** (beta), `sound-physics-remastered-fabric-1.5.1+26.3.jar` ([version](https://modrinth.com/mod/sound-physics-remastered/version/9loJgQlO)) | Reverb and occlusion. A vacuum would need a hook of our own. | **optional** (soft detection only) | GPL: never link against it. Beta. |
| **Controlify** | https://modrinth.com/mod/controlify | LGPL-3.0+ | **`3.5.3+mc26.3`**, `controlify-3.5.3+mc26.3-universal.jar` ([version](https://modrinth.com/mod/controlify/version/fo76vL20)) | Gamepad and HOTAS-style ship flight bindings. | **optional** | — |
| **spark** | https://modrinth.com/mod/spark | GPL-3.0 | **`1.10.187-fabric`** ([version](https://modrinth.com/mod/spark/version/e3hsPc1o)) | Profiler. | **dev** (already) | — |
| **Debugify** | https://modrinth.com/mod/debugify | LGPL-3.0 | **`26.3.0.0`**, `debugify-26.3.0.0.jar` ([version](https://modrinth.com/mod/debugify/version/FMaS2nZn)) | Vanilla bug fixes. | **dev** | Fixes can mask bugs that players without it will hit. |
| **mcpfabric** | https://modrinth.com/mod/mcpfabric | MIT ([GitHub](https://github.com/denfry/mcpfabric)) | **`0.5.1+26.3`**, `mcpfabric-0.5.1+26.3.jar`, 2026-10-05 ([version](https://modrinth.com/mod/mcpfabric/version/YoxCdwC5)). The repo SHA pin is for `0.5.0`. | Agent bridge. | **dev** (already, SHA-gated) | Provenance gate (research C.3). |
| **Jade** | https://modrinth.com/mod/jade | CC-BY-NC-SA-4.0 | **`26.3.5+fabric`**, `Jade-mc26.3-Fabric-26.3.5.jar` ([version](https://modrinth.com/mod/jade/version/71CTWqdE)) | Tooltips for ships and blocks. | **optional** | NC licence. API use only. |
| **Cardinal Components** | https://modrinth.com/mod/cardinal-components-api | MIT | **`8.1.0`** ([version](https://modrinth.com/mod/cardinal-components-api/version/TPEXA0ew)) | — | **avoid** (Fabric data attachments already cover this) | — |
| **Architectury API** | https://modrinth.com/mod/architectury-api | LGPL-3.0 | **`22.0.4+fabric`**, `architectury-fabric-22.0.4.jar`, 2026-10-06 ([version](https://modrinth.com/mod/architectury-api/version/WYttntbU)) | Only if a NeoForge port is ever wanted. | **avoid** | — |
| **Fabric Language Kotlin** | https://modrinth.com/mod/fabric-language-kotlin | Apache-2.0 | **`1.14.1+kotlin.2.4.20`** ([version](https://modrinth.com/mod/fabric-language-kotlin/version/eRRZzGMc)) | — | **avoid** | — |
| **KubeJS** | https://modrinth.com/mod/kubejs | LGPL-3.0 | **None yet.** Newest `26.1.2-8.0.6+neoforge`. | Pack-maker scripting. | **avoid** | — |
| **Polymer** | https://modrinth.com/mod/polymer | LGPL-3.0 | **`0.18.2+26.3`**, `polymer-bundled-0.18.2+26.3.jar` ([version](https://modrinth.com/mod/polymer/version/REBDssAz)) | Server-side-only content. | **avoid** | Galactica needs a client mod (sky, renderers). |
| **Multiblocked2** | https://modrinth.com/mod/multiblocked2 | LGPL-3.0 | **None** (Forge and NeoForge 1.20.1 / 1.21.1) | Multiblock machines. | **avoid** | Write our own multiblock checks: a small structure template plus block-state matchers. |
| **Accessories** | https://modrinth.com/mod/accessories | MIT | **None yet** (1.21.x) | Spacesuit slots. | **avoid now** | Use data attachments and armor slots. |
| **Sinytra Connector** + **Forgified Fabric API** | https://modrinth.com/mod/connector, https://modrinth.com/mod/forgified-fabric-api | MIT / Apache-2.0 | **N/A.** They run Fabric mods *on NeoForge* (newest `3.0.0-beta.6+26.1.2`, and FFAPI `0.155.3+26.1.2`). There is nothing for 26.3. | NeoForge players running Galactica. | **avoid** | It is the reverse direction, and it lacks 26.3. |
| **Chunky** | see section 1 | GPL-3.0 | `1.5.3` | Pre-gen benchmarks. | **dev** | — |

**Space mods to learn from** (design only; no code or asset reuse):

| Mod | Link | Licence | 26.3 | Worth studying |
|---|---|---|---|---|
| Galacticraft 5 | https://github.com/TeamGalacticraft/Galacticraft | MIT | **None.** `main` is `minecraft.version=1.21.1`, `mod.version=5.0.0-prealpha` ([gradle.properties](https://github.com/TeamGalacticraft/Galacticraft/blob/main/gradle.properties)). | Fabric-native oxygen and sealing, rockets, machine lib. Branches include `feature/sable-compat` and `feature/venus`. |
| Ad Astra | https://modrinth.com/mod/ad-astra | Terrarium Licence | **None** (newest `1.16.26`, 1.21.1) | Planet datapack format, oxygen distributor, space-station UX. |
| Stellaris | https://modrinth.com/mod/stellaris | CC-BY-NC-SA-4.0 | **None** (newest `2.0.6`, 26.1.2, NeoForge) | Ad Astra–style planets on 26.x. |
| Create: Northstar (Redux) | https://modrinth.com/mod/northstar-redux | MIT | **None** (1.20.1 / 1.21.1, Create-dependent) | Create-integrated rocketry. |
| Galacticraft-Legacy | https://modrinth.com/mod/galacticraft-legacy | MIT | 1.12.2 | Classic planet progression. |

---

## 4. Prioritised recommendations (top 10)

1. **Lithostitched `2.0.4-fabric-26.3`** ([FhKHD2LJ](https://modrinth.com/mod/lithostitched/version/FhKHD2LJ)) and **Tectonic `3.0.31-fabric-26.3`** ([j2jmkU85](https://modrinth.com/mod/tectonic/version/j2jmkU85)).
   - Approach: optional (`suggests`), plus a `-Pgalactica.tectonic=true` `localRuntime` test run.
   - This is the way to a bigger and taller Earth, up to `max_y` 2031.
   - Gate before shipping: audit `EarthOverworld` and `TransferAltitude` for live `getMaxY()`.
2. **Port Tectonic's techniques into `TerrainSampler` and `PlanetMacro`** (no dependency): a vertical scale, separate continent/erosion/ridge scales, and banded 3D density with 4 × 8 × 4 interpolation. Then raise `surface.json` to `min_y −512, height 2048` and `MAX_RELIEF`, measured with PerfBench `planet_chunkgen`.
3. **Fabric API `0.162.0+26.3`** ([v2j28coa](https://modrinth.com/mod/fabric-api/version/v2j28coa)). A hard-dependency bump.
4. **Distant Horizons `3.3.4-26.3`** ([TGgEbP9A](https://modrinth.com/mod/distanthorizons/version/TGgEbP9A)) and **DH API `7.2.0`** ([LTP8vW3B](https://modrinth.com/mod/distanthorizonsapi/version/LTP8vW3B)). Keep both. Verify `API_DATA_SOURCES` with taller slots. Never override the Overworld generator.
5. **Terralith `2.6.5+26.3`** ([aGE3hYAA](https://modrinth.com/mod/terralith/version/aGE3hYAA)). `suggests` only (Stardust licence); tested together with Tectonic.
6. **Worldgen Devtools `1.4.2+26.3`** ([Ovlis4GJ](https://modrinth.com/mod/worldgen-devtools/version/Ovlis4GJ)) and **Chunky `1.5.3`** ([4Eotm6ov](https://modrinth.com/mod/chunky/version/4Eotm6ov)). Dev only: tune Earth and benchmark pre-generation of tall slots.
7. **C2ME `0.4.2-alpha.0.90+26.3`** ([ODMLK8M9](https://modrinth.com/mod/c2me-fabric/version/ODMLK8M9)). Bump the perf-stack pin from 0.89, and update the SHA-512 in `verifyPerfStack`.
8. **ScalableLux `0.3.0-alpha.0.6+26.3`** ([g4eqNSKd](https://modrinth.com/mod/scalablelux/version/g4eqNSKd)). Perf-stack trial for the lighting cost of taller slots (alpha).
9. **zfastnoise `1.1.1+26.3`** ([cCGI5KLE](https://modrinth.com/mod/zfastnoise/version/cCGI5KLE)), **zconfig `1.0.0+26.x`** ([tsgt79sG](https://modrinth.com/mod/zconfig/version/tsgt79sG)) and **zfastsurface `1.0.0+26.3`** ([yOBmJfgG](https://modrinth.com/mod/zfastsurface/version/yOBmJfgG)). Perf-stack trial for Earth worldgen with Tectonic; no effect on `surface_N`.
10. **Controlify `3.5.3+mc26.3`** ([fo76vL20](https://modrinth.com/mod/controlify/version/fo76vL20)) and **Player Animation Library `1.2.8`** ([xJ30QmpE](https://modrinth.com/mod/player-animation-library/version/xJ30QmpE)). Optional: ship flight on a controller, and pilot poses.

**Blockers: wanted, but no 26.3 Fabric build** (verified 2026-10-07):

| Mod | Newest version |
|---|---|
| Voxy | 26.2 (an unreleased GitHub branch `263` exists) |
| Immersive Portals | 1.21.1 |
| Valkyrien Skies 2 | 1.20.1 on Modrinth, 1.21.1 on GitHub |
| Sable | 1.21.1, PolyForm |
| Veil | 1.21.1 |
| Satin | 1.21.4 |
| Flywheel | 26.1.2 on GitHub |
| Lodestone | 26.1.2, ARR |
| Effective | 1.21.1, ARR |
| Photon | 26.2, NeoForge |
| LDLib | 26.3 NeoForge only |
| owo-lib / owo-ui | 26.2 |
| Modern UI | 26.1.x |
| Big Globe | 26.1.2, ARR |
| JJThunder To The Max | 26.2, ARR |
| Lithosphere | 26.2, ARR |
| NoisiumForked | 26.2 |
| More Density Functions | 1.21.10 |
| KubeJS | 26.1.2, NeoForge |
| Multiblocked2 | 1.21.1, Forge/NeoForge |
| Accessories | 1.21.x |
| Galacticraft 5 | 1.21.1 |
| Ad Astra | 1.21.1 |
| Stellaris | 26.1.2, NeoForge |
| Cubic Chunks | 1.12.2 |
| Sinytra Connector | 26.1.2, NeoForge |

None of these blocks Galactica's roadmap. Each has an in-house or already-integrated substitute (DH for LOD, our own seamless entry and ship physics, YACL for config).
