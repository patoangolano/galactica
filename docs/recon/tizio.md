**TizioSpaceProject v3.4: design reference report**

Extracted to `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/ref/tizio`. I only disassembled class signatures and bytecode constants with `javap`; nothing from the jar was run.

**1. Loader and metadata**
- Forge, loader `javafml [47.,)`, Forge `[47.4.20,)`, MC `[1.20.1,1.21)`.
- modId `tsp`, version `3.4-BETA`, All Rights Reserved.
- No other mod dependencies.
- Mixins are in `tsp.mixins.json`, using Searge names from `tsp.refmap.json`.

**2. Architecture** (root package `tizio.dev.tsp`)
- `MainClass` is the entry point. It calls `Utils$ModLoadingCheck.ensureSafeLoad()`, which only logs a warning when `iris` or `oculus` is loaded. It disables nothing.
- `core.data`:
  - `CelestialJsonLoader` loads `data/tsp/solar_systems/*.json`. It keeps a J2000 epoch, refreshes body positions every 1000 ms, and caps radii (planet 10000, star 100000).
  - `CelestialDataReloadListener` / `CelestialClientReloadListener` handle reloads; `CelestialSyncListener` with `network/SyncSystemDataPacket` syncs to clients.
  - `CelestialJsonExporter` writes to `config/tsp/exported_solar_systems/`.
- `core.celestial.instance.elements`:
  - `SolarSystemData` holds id, space dimension, origin XYZ, globalScale, gravity, star and bodies.
  - `PlanetInstance` (a record) holds `Config`: radius, texture/nightTexture, spinHours, gravity, oxygen, temperature, orbit, atmosphere, ring, clouds, sky, fog.
  - Also `SunInstance`, `BlackHoleInstance` and `OrbitCollisionUtil`.
- `engine.celestial`: `SpaceRenderer` plus per-element renderers for planet surface, atmosphere, rings, ring rocks, sun, black hole and orbit paths. Environment classes: `SkyEnvironmentRenderer`, `PlanetSkyRenderer`, `FogRenderer`, `SkyRenderContext`.
- `engine.volume`:
  - `VolumeRenderUtil` does frustum checks, back-to-front `mergeSorted` of render tasks, screen-space scissor rects, proxy cubes and uniform helpers.
  - Also `OrientedVolumeInstance`, `PreparedVolume`.
- `engine.lighting.LightShadeManager` sets custom block-face shading and entity diffuse light from the nearest one or two suns (`NearestSunSelector`).
- `engine.postprocess` and `engine.camera.post`: lens flare, film grain, colour correction and aspect bars. Photo-mode camera via `CameraPlanetOrbit`.
- Gameplay: `core.handlers` for gravity, oxygen, temperature, teleport and sfx (`OpenALMuffleFilter` muffles sound in vacuum or inside a helmet).
- GUI: `SystemEditor` (an in-game solar-system editor), `SuitHudOverlay`, `PhotoModeScreen`.
- `registry`, `datagen`, `world` (crater structure and features, rock feature).

**3. Rendering**
- **Hook:** Forge `RenderLevelStageEvent` at `AFTER_SKY` inside `SpaceRenderer.onRenderLevelStage`, plus custom `DimensionSpecialEffects` (`CFDimension$SkySetup$TspDimensionEffects`) for the per-dimension sky. It does not mixin into `LevelRenderer.renderSky`.
- **Planets are ray-marched volumes, not meshes.** Each body is drawn as an oriented proxy cube (`VolumeRenderUtil.renderCube`). A fragment shader does ray-sphere intersection: `planet_surface.fsh`, `planet_atmosphere.fsh`, `sun.fsh`, `black_hole.fsh`, `planet_ring*.fsh`, all through vertex shader `projection.vsh`.
- **Atmosphere:** single-scattering Rayleigh. Uniforms: PlanetRadius, AtmosphereRadius, WaveLengths (default 780/540/440), RayleighScaleHeight, RayleighStrength, Exposure, and up to 4 light directions. It takes 6 view and 6 light samples, halved when camera distance exceeds 25 × atmosphere radius, and applies scale compensation clamped to 0.05–20.
- **Shader files:** vanilla-style core shaders in `assets/tsp/shaders/core/define/*.json` with GLSL 150. Post chains are in `shaders/post/*.json` and `shaders/program/*`.
- **Iris/Oculus:** no shader-pack compatibility code beyond the warning. These are custom `ShaderInstance`s, which Iris normally bypasses or breaks.
- **Surface sky:** `SkyEnvironmentRenderer` and `PlanetSkyRenderer` draw a sky dome (skybox texture `milky_way`, procedural stars with `starsAmount`/`starsSeed`, comets, deep sky). They draw other bodies at angular size via `computeSkyRadius` and rotate the sky with `buildCelestialToSky` quaternions, so the real orbital geometry appears in the surface sky. Volumetric sky atmosphere and clouds come from `sky_atmosphere.fsh` and `sky_clouds.fsh`.
- **Mixins:**
  - `DepthMixin`: `GameRenderer.getDepthFar` returns `Float.POSITIVE_INFINITY` (infinite far plane).
  - `CameraMixin`: tail of `Camera.setup`, used for photo-mode orbit.
  - `LevelRendererCloudsMixin`: `renderClouds` hides vanilla clouds per body.
  - `GameRendererMixin`: `renderLevel` sets lighting state.
  - `DiffuseLightingMixin`: `setupLevelDiffuseLighting` uses sun directions.
  - `ShadingLevelMixin`: block face shade.
  - Others: `FPArmRenderMixin`, `OverlayKiller` (`Minecraft.setScreen`), and accessors for `LevelRenderer` post chains, `ViewArea` and `Window`.

**4. Dimensions and worldgen**
- `data/tsp/dimension/{space,moon,mars,venus}.json`.
- `space` is a flat dimension with empty layers, biome `tsp:space_void`, no skylight, ambient light 1.0, and custom effects `tsp:space`.
- Planets are ordinary noise-based dimensions with one biome each, for example `tsp:mars_plains`, `default_block` `mars_sand`, no fluid.
- **The whole solar system lives inside the `space` dimension** at origin (0, 500, 0) with `globalScale` 5.5.
- **The scale is tiny and normalised**, not real. Sun radius 0.1, Mercury radius 0.111 and orbit radius 0.072, all multiplied by globalScale. Orbits use real periods (`periodDays`), inclination, ascending node and an epoch in UTC, and are computed from wall-clock `Instant.now()`.
- **Transitions** (`SpaceTransitionHandler`):
  - At Y > 550 (`ESCAPE_ALTITUDE`) on a planet, the entity is teleported into `tsp:space` near that body's live position, outside `getSpaceSpawnRadius`.
  - Flying into a body's `getEntryRadius` in space teleports back to the planet dimension. The last X/Z/dim are stored in entity NBT (`tsp_last_p_*`), with a cooldown tag and `REENTRY_ALTITUDE` 490.
  - Vehicles and passengers move together via `teleportEntityTree`.
- There is no floating origin; it relies on the miniature scale.
- `NoVoidDamage` cancels `Entity.checkBelowWorld`.

**5. Gameplay**
- **Gravity:** per-body m/s² ÷ 9.80665 scales vanilla constants (living 0.08, item 0.04, falling block 0.04, arrow 0.05, TNT 0.04) through tick mixins on each entity type. Fall damage is scaled too. The space dimension has gravity 0.
- **Oxygen:** NBT `tsp_oxygen` from 0 to 1. Depletes at 0.05/tick-step without oxygen, recovers at 0.005, deals 2.0 suffocation damage. A full space suit is required, using the per-body `oxygen` flag.
- **Temperature:** NBT-based, with suit regulation, freezing and heat thresholds, solar heat from distance to the sun, and armor damage from heat.
- **Weather:** disabled through `LWeatherMixin` and `SLWeatherHandlerMixin`.
- **Content:** no ships, fuel or rockets. Travel means flying or climbing above Y 550. Items are space suits (9 colours), titanium, aluminium and lead ores/ingots/blocks for Moon, Mars and Venus, `circuit_board` and `debris`. Craters are worldgen structures.

**6. Worth re-implementing**
- A data-driven JSON system with live editor, export and server-to-client sync.
- Keplerian-ish orbits keyed to a real UTC epoch.
- Ray-marched analytic spheres on proxy cubes: cheap, resolution-independent planets, with LOD on sample counts by distance.
- Painter's-sort merge of transparent volume tasks.
- Scissor rects to limit fragment cost.
- An infinite far plane for large distances.
- Surface sky that matches the real orbital geometry through celestial-to-sky quaternions.
- Multi-sun lighting injected into block and entity shading.
- Sound muffling via an OpenAL filter.
- Gravity applied as a scale factor on vanilla constants.

**Weaknesses to avoid**
- No real scale and no floating origin; float precision would break at real distances.
- One fixed solar system; nothing procedural; no galaxy.
- No Iris compatibility. Its custom core shaders will fight shader packs. A Fabric port should go through Iris's DimensionSpecialEffects/sky passes, or render into the shader pack's sky buffer, with a fallback.
- Hard-coded escape at Y 550 and an instant teleport, with no seamless descent.
- No ships, fuel or orbital mechanics.
- Wall-clock orbits ignore game time and tick rate, so there is no time warp.
- Single-biome planet dimensions, one per planet, which does not scale.
- Very low sample counts (6/6) in the atmosphere.

**Files and classes inspected**
- Metadata and data: `META-INF/mods.toml`, `tsp.mixins.json`, `tsp.refmap.json`, `data/tsp/solar_systems/solar_system.json`, `data/tsp/dimension/*.json`, `data/tsp/dimension_type/space.json`.
- Shaders: `assets/tsp/shaders/core/define/planet_atmosphere.json`, `.../fragment/celestial/planet_atmosphere.fsh`.
- Rendering and lighting classes: `SpaceRenderer`, `PlanetSkyRenderer`, `SkyEnvironmentRenderer`, `VolumeRenderUtil`, `PostProcessRenderer`, `LightShadeManager`, `NearestSunSelector`.
- Gameplay and data classes: `SpaceTransitionHandler`, `GravityManager`, `OxygenManager`, `TemperatureManager`, `SolarSystemData`, `PlanetInstance` and `PlanetInstance$Config`, `CelestialJsonLoader`.
- Mixins and setup: `DepthMixin`, `CameraMixin`, `LevelRendererCloudsMixin`, `ShadingLevelMixin`, `DiffuseLightingMixin`, `Utils$ModLoadingCheck`, `MainClass`, `CFDimension$SkySetup`.