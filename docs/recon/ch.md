CosmicHorizons-0.1.0.0.jar: design-reference report

Extracted to `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/ref/cosmichorizons`. Inspection was read-only, using `javap -p/-c` and the text assets. Nothing from the jar was executed.

**1. Loader, version, ID and dependencies**
- Forge (`javafml`, loaderVersion `[47,)`), Minecraft `[1.20.1]`. modId is `cosmos`, display name "Cosmic Horizons", author GHO__ST, license All Rights Reserved. Source: `META-INF/mods.toml`.
- The only declared dependency is minecraft. The bytecode also hard-depends on an undeclared render library, **Shade** (`net.soul.shade.*`: shard, sampler, uniform, post effects, framebuffer, `ShadeRenderEvent$Level`, `SoulProjectionMatrixManager`, `SoulBloomRenderer`). It also uses **imgui** and **imguizmo**, probably through Shade, for an in-game universe editor.
- `CH-Spheromatic` is an addon. Planet JSON has `"shard": {"cosmos":"cosmos:shape/cube","spheromatic":"spheromatic:shape/sphere"}`, so base planets are cubes and real spheres come from the addon.
- Mixins are listed in `mixins.cosmos.json`; refmap in `mixins.cosmos.refmap.json`.

**2. Architecture** (`net/distant/cosmos/...`)
- `api/universe/Cosmos` is the registry of StarScapes. It provides `findStarScapeByDimension` and `getAllStarScapes`.
- `api/universe/data/{StarScape, Object, object/Planet(Atmosphere, Clouds, Ring, Aurora), Star, BlackHole, DataConverter}` are the data model, loaded from JSON.
- `core/universe/CosmosUniverseDataLoader` is a reload listener for `data/cosmos/universe/system/*.json` and `data/cosmos/universe/object/**`.
- `core/universe/CosmosEscapeHandler` handles leaving a planet into the system dimension.
- `core/client/render/universe/*`:
  - `RenderEngine`: the main per-frame driver.
  - `SkyRenderer`: planets, stars and black holes seen from a planet's sky.
  - `SkyboxRenderer`: procedural galaxy skybox, shooting stars, and a "sun gaze" exposure timer.
  - `RenderSorter`: depth-sorts bodies by distance.
  - `object/{PlanetRenderer, StarRenderer, BlackholeRenderer, planet/BodyRenderer, AtmosphereRenderer, CloudRenderer, RingRenderer}`.
  - `effect/{BloomRenderer, LensFlareRenderer, ShadowRenderer, CosmosShadowMapUtils, SkyAtmosphereRenderer}`.
  - `ship/{ShipRenderer, ThrustRenderer}` and `weather/{Aurora, Meteor, Tornado}Renderer`.
- `core/client/editor/*`: ImGui editor windows for planets, stars and black holes, plus `UniverseExporter`, which exports JSON.
- `core/entity/ship/{Spaceship, Nodule, controller/Advanced|Legacy|SpaceshipController}`.
- `core/capability/{PlayerRotations, SpaceSuitMovementCapability}`.
- `core/weather/{CosmosWeatherManager, FallingMeteorEntity, FallingCometEntity, MeteorShardEntity, CosmosStructurePlacer}`.
- `core/network/*`: sync, teleport request, ship input, suit input, and comet/meteor packets.
- `core/world/dimension/{Universe, SolarSystem, AlphaCentauri, B1400Centauri, GaiaBH1, Moon}`: these register DimensionSpecialEffects.
- `core/world/function/MoonCraterDensityFunction` is a custom density function. `feature/` holds the moon-spike and bubble features.
- `util/{CosmosGravity, CosmosTeleport}`.

**3. How it renders space**
- **No LevelRenderer mixin.** All rendering hooks into Shade's `ShadeRenderEvent$Level` at stages `AFTER_SKY` and `AFTER_PARTICLES` (`RenderEngine.renderLevel` / `renderFog`).
- It builds its own projection with `SoulProjectionMatrixManager.builder().nearPlane(0.05f).farPlane(Infinity)`. The infinite far plane is what allows huge, distant bodies without z-clipping.
- The only client mixin related to rendering is `FogRendererMixin.setupFog` (`m_234172_`), which removes fog in cosmos dimensions. Other client mixins are the title-screen panorama and particle gravity.
- **Planets** are a mesh "shard" (`soul/buffers/shards/shape/cube.shard`, or the Spheromatic sphere) scaled by `model.scale`. Shaders are `soul/shaders/core/universe/planet/{base, normal, gas, cloud, atmosphere}.fsh`. Texture maps are Basic, Night, Emissive, Specular, Normal and Flow; lighting is Phong with constants ambient 0.1, specular 1.2, shininess 128, flow intensity 0.28.
- **Atmosphere** (`atmosphere.fsh`) is a 6-step ray-march Rayleigh scattering pass. It uses per-planet wavelengths (Earth: 710/540/440 nm), `coeff = (400/λ)^4 * intensity`, exponential height falloff and a terminator mask.
  - The shell is a box shell (`sizedBoxIntersect`, `shellMetric`), so the atmosphere is cubic for cube planets.
  - `include/intersect.glsl` provides ray–AABB intersection.
- **Ground sky** is a post pass, `program/sky/lut.fsh`, that bakes a sky-color LUT from day, night, glow and haze colors against sun height. `program/sky/atmosphere.fsh` and fog passes (`program/fog/{sky, terrain, veil}.fsh`) follow, all through `SkyAtmosphereRenderer.bakeLut`.
- **Skybox** is a procedural galaxy fragment shader with a seed, a 3D lattice gradient noise, and core/middle/edge colors: `program/skybox/galaxy.fsh`, `galaxy_twist.fsh`, `galaxy_quasar.fsh`. It is baked once per skybox (`bakeIfNeeded`) and cached. There are also shooting stars and a twinkle star shader (`core/universe/star.fsh`).
- **Star color** comes from a blackbody temperature texture (`include/temperature.glsl`; e.g. Sun `temperature: 5800`). Lens flare, bloom and vignette are post effects (`post/effects/visuals/*`).
- **Black hole** is a deferred, screen-space lensing and accretion-disk shader (`core/universe/deffered/blackhole.fsh`, 265 lines).
- **Rings** use a band shader and a particle-ring shader. Shadows use a custom shadow map (`ShadowRenderer`, `CosmosShadowMapUtils`, `include/shadow.glsl`), with "Space Light", colored lighting and shadow-quality settings in `assets/shade/settings/cosmic_horizons.json`.
- **Iris/Oculus:** there is no compatibility code (no Iris classes, no shaderpack hooks). Everything is custom GL through Shade with MRT outputs (`layout(location=4) fragSky`). Shaderpack conflicts are likely.

**4. Dimensions and worldgen**
- **One dimension per star system:**
  - `cosmos:solar_system`, `alpha_centauri`, `b1400_centauri` and `gaia_bh_1` each use a flat generator with zero layers (an empty void) and biome `cosmos:space`.
  - `cosmos:universe` is the interstellar void.
  - dimension_type has min_y -496, height 1200, no skylight and ambient light 0.15.
- **Inside a system dimension**, bodies exist only as render objects at fixed block coordinates in a compressed scale. Earth sits at (-13750, 0, 73500) with scale 915; the Sun has scale 8000; the black hole sits at (80000, 2000, 4500) with scale 24000. These are not real distances, and orbits do not move in system space.
- **Planet surfaces are separate dimensions.** Earth maps to `minecraft:overworld`, plus `cosmos:moon` and `cosmos:mars`, both noise generators with custom noises, crater density functions and biomes. The other planets (Venus, Mercury, Titan, Pluto) only have block sets.
- **Transition up:** `CosmosEscapeHandler.onPlayerTick` runs server-side. When the player's Y exceeds the planet's `escape_radius` (Earth: 1000), it teleports them to the system dimension at `planet.position + (0, scale/2 + 250, 0)` (`SPACE_CLEARANCE = 250`).
- **Transition down:** `RenderEngine` detects client-side that the camera has entered a planet's bounds and sends `CosmosTeleportRequestPacket(dimId)`. The server teleports the player to Y=500 in that dimension. This is client-authoritative and spoofable.
- **No floating origin.** It uses camera-relative matrices (`ShaderUtils.getCamPos`) at float precision, so precision limits stay within about ±100k blocks.
- **Sky from the ground** is per-planet `dimension.sky` entries (object, scale, rotation, orbit distance and rotation speed), which fake orbital motion. A skybox fade by day handles the transition. `RenderEngine` also has ground-planet recede/fade constants for when you ascend.

**5. Gameplay mechanics**
- **Gravity:** per-dimension `gravity` multiplier (`CosmosGravity.getMultiplier`), applied through mixins:
  - `LivingEntity.travel`, `Entity.tick`, `FallingBlockEntity.tick`
  - `AbstractArrow`, `FishingHook` and `Boat.floatBoat`
  - `getGravity` on ThrowableProjectile, ThrownPotion and ThrownExperienceBottle
  - client `Particle.tick`
- **Ships:** `Spaceship` entity with quaternion rotation synced through EntityData, lerp smoothing, and Advanced or Legacy controllers fed by `CosmosSyncSpaceshipInputPacket`. Gravity is a fixed -0.04 × multiplier. There is one model, `Nodule` (`ship/steel.json` → `t1.obj`, tier 1). Thrust shaders exist (`thrust/{raptor, hawk, ion, frontier}.fsh`).
- **Space suit:** armor set plus `SpaceSuitMovementCapability` (velocity and body quaternion) for 6DOF EVA, driven by `SpaceSuitInputPacket`.
- **Not present:** fuel, oxygen, temperature or radiation damage. Planet `info` values (life %, temperature) are display-only.
- **Weather:** meteor showers and crashes that place meteor `.nbt` structures (`CosmosStructurePlacer`), comets, twisters, auroras gated by biome list, dust storms, re-entry flame and shock shaders.
- **Items:** steel, titanium and nickel ingots, charged quartz, colored steel balls with physics, a joke marshmallow/roasting stick, and around 170 decorative planet stone blocks.
- **Commands:** `CosmosCommands` provides teleport-to-object with a direction.

**6. What to re-implement and what to avoid**

*Worth re-implementing:*
- A data-driven universe: system and object JSON hierarchy with physical parameters (temperature K, Rayleigh wavelengths, scatter intensity, height falloff). This maps directly to a procedural generator's output.
- An infinite-far-plane, near-0.05 custom projection for celestial passes, kept separate from the vanilla terrain projection.
- Mesh-agnostic body shaders that ray-march the atmosphere in object-local space: cheap at 6 steps and resolution-independent.
- A baked procedural galaxy skybox, cached per seed, and a sky LUT baked per planet atmosphere.
- Blackbody temperature to color, deferred black-hole lensing, distance-sorted transparent bodies, lens flare from star sources.
- Gravity as a per-dimension multiplier through a few targeted mixins (travel, tick, `getGravity`).
- Escape-radius altitude for the planet-to-space transition.
- An ImGui in-game editor that exports JSON, which suits authoring.

*Weaknesses to avoid:*
- **Hand-placed bodies:** only 4 systems and around 20 bodies. Use procedural, seed-addressed generation instead (octree/sector hashing of the galaxy, analytic density model).
- **No real scale and no floating origin**, with float precision. Use double or 64-bit sector coordinates plus a camera-relative origin and logarithmic depth or scaled-space rendering.
- **Static orbits.** Use Keplerian elements evaluated from world time.
- **Cube planets and box atmospheres** by default. Use a cube-sphere with LOD quadtree.
- **No Iris/Oculus compatibility,** plus a fog-kill mixin and Shade MRT outputs. Render through Iris-aware hooks and use the vanilla sky stage.
- **A hard dependency on an undeclared closed library.**
- **Client-authoritative planet entry** (teleport packet holding only a dimension ID). Validate position server-side.
- **Gameplay is thin:** no fuel, oxygen, delta-v or life support, and survival integration is limited to a space suit.
- **Planet surfaces are disconnected vanilla-height dimensions,** so there is no seamless orbit-to-surface transition and the landing point is always Y=500.

Paths inspected:
- `META-INF/mods.toml`, `mixins.cosmos.json`, `mixins.cosmos.refmap.json`, `assets/shade/settings/cosmic_horizons.json`, `assets/cosmos/ship/steel.json`
- `data/cosmos/dimension/*.json`, `data/cosmos/dimension_type/universe.json`
- `data/cosmos/universe/system/{solar_system,gaia_bh_1}.json`, `data/cosmos/universe/object/solar_system/{planet/earth,star/sun}.json`, `data/cosmos/universe/object/gaia_bh_1/blackhole/gaia.json`
- `assets/cosmos/soul/shaders/core/universe/planet/{atmosphere.fsh,base.json}`, `program/sky/lut.fsh`, `program/skybox/galaxy.fsh`, `include/intersect.glsl`
- Classes: `RenderEngine`, `SkyRenderer`, `SkyboxRenderer`, `RenderSorter`, `PlanetRenderer`, `BodyRenderer`, `AtmosphereRenderer`, `SkyAtmosphereRenderer`, `CosmosEscapeHandler`, `CosmosTeleportRequestPacket`, `CosmosTeleport`, `CosmosGravity`, `FogRendererMixin`, `LivingEntityGravityMixin`, `GravityEntityMixin`, `Spaceship`, `AdvancedController`, `Nodule`, `SpaceSuitMovementCapability`, `SpaceSuitItem`