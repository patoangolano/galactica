# Galactica Design Brief (mod id `galactica`, Fabric 26.3, Java 25)

## 1. Reference mods: what to borrow and what to avoid

**TizioSpaceProject (TSP)**
- *Borrow:*
  - Planets drawn as analytic ray-sphere volumes on proxy cubes, with sample counts that drop with distance (`VolumeRenderUtil`).
  - Back-to-front merge of transparent bodies, and scissor rects to limit fragment cost.
  - An infinite far plane.
  - A surface sky driven by real orbital geometry through celestial-to-sky quaternions.
  - Multi-sun block and entity shading (`NearestSunSelector`).
  - Muffling sound in vacuum with an OpenAL filter.
  - Gravity as a scale factor on vanilla constants.
  - Data-driven JSON with server-to-client sync.
- *Avoid:*
  - Miniature scale and float-only positions.
  - A single hand-authored system.
  - Wall-clock orbits, which ignore game time.
  - The fixed Y=550 teleport.
  - Custom `ShaderInstance`s that ignore Iris.
  - One single-biome dimension per planet.

**Cosmic Horizons**
- *Borrow:*
  - A separate celestial projection (near plane 0.05, infinite far plane).
  - A procedural galaxy skybox that is baked once and cached.
  - A blackbody color LUT.
  - A per-planet sky LUT bake.
  - Screen-space black-hole lensing.
  - Distance-sorted bodies.
  - A 6DOF EVA capability and a quaternion ship entity.
  - Escape-radius transitions.
- *Avoid:*
  - Hand-placed bodies and static orbits.
  - Cube planets with box atmospheres.
  - A hard dependency on the closed library Shade, plus MRT writes into foreign buffers.
  - Client-authoritative teleport packets.
  - Disconnected Y=500 landings.
  - No survival loop.

**CH-Spheromatic**
- *Borrow:*
  - Planets in unit-sphere model space (R=0.5) with an inverse model matrix. Shadows, eclipses, occlusion and atmosphere all become analytic, so no depth precision is needed.
  - Cube-sphere meshes instead of UV spheres.
  - Flow-map gas giants.
  - "Shape as data" through a registry.
- *Avoid:*
  - Monkey-patching another mod's statics and assets.
  - Rayleigh-only atmospheres with 6×3 samples.
  - Fixed tessellation.

## 2. Coordinates, scale and procedural galaxy

**Real units everywhere** (`dev.galactica.math.Units`):
- m, km, AU = 1.495978707e11 m, ly, pc = 3.0857e16 m.
- M☉, L☉, R☉.

**Three-tier position.** A single double at galactic scale (about 1e21 m) only resolves to about 100 km, so positions are split:
- **`GalacticPos`**:
  - `long sx, sy, sz` is a 10 pc sector index.
  - `double ox, oy, oz` is metres from the sector centre. At 3e17 m, the precision is about 64 m, which is fine in interstellar cruise.
- **`SystemFrame`**: double metres relative to the host star, used inside the star's sphere of influence. Precision is about 2 mm at 100 AU.
- **Render-local**: float, camera-relative, built per frame by `CameraRelativeTransform`.

**Floating origin.** `dev.galactica.space.FloatingOriginManager` keeps every space-dimension entity within ±4096 blocks of (0,0,0), with 1 block = 1 m. When the player crosses that bound, the manager:
1. folds the offset into the ship's `GalacticPos` / `SystemFrame`;
2. shifts all space entities;
3. sends `OriginShiftPayload` to the client.

**Galaxy model** (`dev.galactica.galaxy.GalaxyDensityModel`, analytic, `StrictMath` only for cross-JVM determinism):
- **Thin disk:** exponential, scale length 2.6 kpc, scale height 300 pc.
- **Thick disk:** scale height 900 pc, about 12% normalisation.
- **Bulge/bar:** about 1.5 kpc, triaxial, 27° bar angle.
- **Arms** (`SpiralArmModel`): 4 logarithmic arms (Perseus, Scutum-Centaurus, Sagittarius, Norma), pitch about 12°, density-wave contrast about 1.6×, and young OB-star enhancement inside the arms.
- **Dust** (`DustModel`): scale height 100 pc. It drives extinction and reddening for both the skybox and individual star colours.
- **Sun:** placed at R = 8.2 kpc, z = +20 pc, with Sol hand-authored as `data/galactica/systems/sol.json` from JPL elements.

**Per-sector generation** (`dev.galactica.galaxy.SectorGenerator`):
- Seed: `SectorHasher.seed(galaxySeed, sx, sy, sz)` using SplitMix64.
- Star count: Poisson(ρ(x)·V).
- Each star gets a stable 64-bit `StarId(sectorKey, index)`.

**Star physics:**
- Masses come from the Kroupa IMF (`Imf.kroupa`).
- Age and metallicity depend on the galactic component.
- `StellarPhysics` gives L from a piecewise mass-luminosity relation, R from the mass-radius relation, and T_eff from Stefan-Boltzmann.
- Evolved remnants (white dwarfs, neutron stars, about 1e8 black holes) are produced by age vs main-sequence lifetime.
- `Blackbody.rgb(T)` integrates Planck × CIE 1931 into a 1024-entry LUT.

**Planets** (`dev.galactica.system.SystemGenerator`, `PlanetGenerator`):
- Occurrence follows Kepler/RV rates: about 1 planet per star on average, hot Jupiters about 1%, and η⊕ about 0.2 for FGK stars.
- Spacing is log-uniform and Hill-stable.
- Composition depends on the frost line.
- Equilibrium temperature comes from L and a.
- Atmospheres are retained according to escape velocity vs thermal velocity.

**LOD and caching:**

| Tier | Range | Method |
|---|---|---|
| L0 | ≤50 pc | Enumerated stars, as instanced point sprites |
| L1 | 50 pc–2 kpc | Aggregated sector luminosity |
| L2 | Galaxy | Line-of-sight integrals baked into a cubemap |

`SectorCache` is an LRU of about 4096 sectors. Generation runs on a dedicated executor and never on the render thread.

## 3. Rendering in 26.3 with shader compatibility

**Constraints:**
- `DimensionSpecialEffects` and `DimensionRenderingRegistry` no longer exist.
- The GPU layer is `com.mojang.renderpearl` (OpenGL and Vulkan backends), so the mod makes no raw GL calls.

**Pipeline:**
- `dimension_type/space.json` sets `"skybox": "none"`, no skylight and fixed time.
- Celestial drawing happens in a MixinExtras `@Inject` at the tail of `SkyRenderer.render`, plus `LevelRenderEvents.StartMain` for bodies that need depth. There are no `@Overwrite`s and no cancels on the methods Iris's `MixinSkyRenderer` touches.

**Pipelines** (`dev.galactica.client.render.GalacticaPipelines`, built with `FabricRenderPipeline.Builder`):
- `GALAXY_SKYBOX` (textured cubemap)
- `STAR_SPRITES` (additive, instanced)
- `BODY_IMPOSTOR` (textured quad)
- `BODY_RAYMARCH` (proxy cube)
- `ATMOSPHERE_RAYMARCH`

**Scaled space.** A body is drawn at d′ = min(d, 60,000 blocks) with radius r·d′/d. This preserves angular size and lighting with no z-fighting.

**Two paths, chosen by `IrisCompat`** (reflection-guarded `IrisApi`):
- **Vanilla / no pack:**
  - Direct ray-march: analytic sphere in unit model space.
  - Atmosphere uses Hillaire-style transmittance and multi-scatter LUTs, with Rayleigh, Mie and ozone. LUTs are baked once per planet by `AtmosphereLutBaker`.
  - Tone mapping uses physical exposure (EV100).
- **Shader pack in use** (`isShaderPackInUse()`):
  - Bodies and atmospheres are ray-marched into a mod-owned `TextureTarget` (`ImpostorCompositor`), never into the pack's G-buffers.
  - The result is composited as `BODY_IMPOSTOR` quads.
  - `assignPipeline(GALAXY_SKYBOX | BODY_IMPOSTOR, IrisProgram.SKY_TEXTURED)` and `assignPipeline(STAR_SPRITES, SKY_BASIC)`, so packs fog, bloom and grade them like vanilla sky.

**Galaxy skybox** (`GalaxySkyBaker`):
- Async CPU line-of-sight integration with dust extinction, written to a 6×1024² HDR cubemap.
- Re-baked when the viewer moves more than 100 pc.

**Stars** (`StarfieldRenderer`):
- Apparent magnitude comes from L and distance after extinction.
- Sprite size and brightness come from a photometric PSF, so faint stars do not flicker.

**Other:** an infinite-far-plane celestial projection (`CelestialProjection`), and lens flare and black-hole lensing as optional post passes that are disabled under packs.

## 4. Physical travel

**Space dimension `galactica:space`:**
- A void (`EmptyChunkGenerator`).
- The player pilots `dev.galactica.space.ShipEntity`, with a quaternion attitude synced over `ShipStatePayload`.
- Input goes through `ShipInputPayload`. The server is authoritative and validates input and positions.

**Flight modes:**
1. **Newtonian 6DOF** (`FlightModel`):
   - Real thrust and mass, giving Δv = Isp·g₀·ln(m₀/m₁).
   - Optional "coupled" assist, in the style of Star Citizen.
2. **Quantum drive** (`QuantumDrive`):
   - In-system only; spools, aligns, then cruises at 0.2c.
   - Gravity wells interdict: the drive is blocked inside 1.5 × the Hill sphere of a body unless the body is the destination.
   - Earth to Mars takes about 20–60 min of real travel compressed by time compression.
3. **Jump drive** (`JumpDrive`, in the No Man's Sky style):
   - Star-to-star hops with range ∝ fuel·drive tier, up to 5–50 ly per jump.
   - Fuel is exotic matter refined from scooped hydrogen.
   - A cinematic tunnel lands the ship at 10 AU from the target star.

**Orbits:**
- `OrbitalElements` and `KeplerSolver` (Newton on E, with a hyperbolic branch) are evaluated from universal time. Universal time is the world tick × 0.05 s × the time-warp factor, with a J2000 epoch.
- Ships use patched conics (`PatchedConics`, SOI = a(m/M)^0.4).
- The HUD shows Ap/Pe, an orbit trace and burn planning (`ManeuverNode`).

**Atmosphere entry** (`AtmosphereEntryHandler`):
- Below 10× the scale height, density, drag and heating are computed.
- Heating follows the Sutton-Graves relation q ∝ √(ρ/rₙ)·v³ and drives the plasma visuals and hull heat.
- At 100 km altitude the server moves the ship and passengers into a surface dimension at the matching lat/long and Y=600. A 1 s camera fade blends the two views, so there is no visible pop.

**Surfaces:**
- **Dimension pool:** `SurfaceDimensionPool` pre-registers `galactica:surface_0..15` and leases them to planets on demand. A lease is released after 5 min empty, and the persistent region is stored under `galactica/planets/<StarId>/<planet>`.
- **Real-size rocky planets:** `CubeSphereMapper` unfolds a cube-sphere into a 3×2 face atlas. Earth's face edge is about 10,000 km = 10M blocks, so the atlas fits inside the ±30M world border at real scale up to about 2 R⊕. Larger worlds are scaled down, and the factor is shown on the HUD.
- **Face seams:** crossing a seam teleports the player with a rotated heading (`FaceSeamHandler`).
- **Terrain:** `PlanetChunkGenerator` builds terrain from the planet's composition, temperature and atmosphere, with craters for airless bodies (density function borrowed from the Cosmic Horizons approach).
- **Gas giants:** not landable. Atmospheric descent ends in crush depth.

## 5. New mechanics

**Survival and physics:**
- **Life support** (`LifeSupport`): O₂/CO₂ partial pressures, suit thermal balance against stellar flux and the planet's equilibrium temperature, and radiation dose (stellar wind, belts, flares).
- **Real gravity:** g = GM/r², applied as a scale on vanilla constants (TSP pattern). Tidally locked worlds have permanent day and night sides; eclipses are real and computed analytically.

**Ship systems:**
- **Fuel economy:** hydrogen scooping in a star's corona, risking heat. Deuterium from gas giants. Delta-v budgeting.

**Science loop:**
- **Spectrograph** (`ScannerItem`): classifies stars (OBAFGKM, luminosity class).
- **Planet discovery:** transit and RV detection minigames reveal planets before you visit.
- **Survey data:** a tradeable currency (`SurveyLedger`).
- **First-discovery naming:** shared via the server.
- **Bookmarks:** a `Codex` of known systems.

**Navigation:**
- **Galaxy map screen** (`GalaxyMapScreen`): zoom from the system view to the full spiral, with route plotting by jump range (A* over the sector graph).
- **Habitability score:** combines habitable-zone position, pressure, temperature and magnetosphere. Rare living worlds have biomes seeded from it.

**Multiplayer and resources:**
- **Light-lag comms:** an optional chat delay between systems for multiplayer immersion.
- **Resource distribution:** follows composition (metal-rich inner rocks, ices beyond the frost line). Ship tiers are crafted from these.

## 6. Milestones

**M1 — vertical slice, one session:**
- Fabric-only project from MultiLoader-Template `26.3`: Loom 1.18.1, fabric-api 0.161.0+26.3 (cached offline), and `compileOnly maven.modrinth:iris:1.11.7+26.3-fabric`.
- `Units`, `GalacticPos`, `SectorHasher`, `GalaxyDensityModel`, `Imf`, `StellarPhysics`, `Blackbody`, `SectorGenerator`, with JUnit determinism and statistics tests: local stellar density ≈ 0.1 pc⁻³ and the IMF slope.
- `space` dimension with `"skybox":"none"`.
- `StarfieldRenderer` (nearby real-colour stars) plus a first CPU-baked `GalaxySkyBaker` band, with the Iris `assignPipeline` hook.
- Commands: `/galactica where`, `/galactica nearest`, `/galactica jump <starId>`, `/galactica sol`.
- A HUD with galactic coordinates, sector, nearest star and its spectral type.
- Creative jetpack flight with `FloatingOriginManager`.

**M2 — systems:**
- `SystemGenerator`, Kepler orbits, scaled-space ray-marched bodies with Hillaire atmospheres, the impostor path under packs, and Sol from JSON.

**M3 — ship:**
- `ShipEntity`, `FlightModel`, `QuantumDrive`, patched-conic HUD, `JumpDrive`, fuel and life support.

**M4 — surfaces:**
- `SurfaceDimensionPool`, cube-sphere terrain, atmosphere entry, gravity and oxygen.

**M5 — science and economy:**
- Scanner, transit minigame, galaxy map, survey ledger.

**M6 — polish:**
- Black holes and lensing, nebulae and clusters, audio muffling, performance pass, multiplayer hardening.

## 7. Risks and mitigations

- **26.3 render API churn (renderpearl, possible Vulkan):** go only through `RenderPipeline`/`RenderPass`, isolate everything in `client.render.backend`, and test both backends.
- **Iris overriding pipeline shaders:** custom shading never depends on the pack. Ray-marching goes to a mod-owned target, and packs only see textured sky quads. If something breaks, `IrisCompat` falls back to the skybox plus sprites.
- **Precision:** the three-tier position, floating origin, scaled space and analytic unit-sphere tests. Fuzz tests cover origin shifts.
- **Determinism:** `StrictMath`, SplitMix64, no `HashMap` iteration order in generation, and a versioned generator (`GEN_VERSION` in the seed).
- **Performance:**
  - Generation runs async with the LRU sector cache.
  - Star sprites are instanced and capped at 20k.
  - Bakes are amortised over several frames.
  - Atmosphere sample counts scale with distance.
  - Budget: under 1.5 ms per frame in space.
- **Dynamic dimensions:** use a fixed leased pool instead of runtime registration.
- **Multiplayer time warp:** only allowed when every player in the system agrees and none is in an atmosphere. Otherwise it is capped at 1×.
- **Fun vs realism:** time compression, quantum and jump drives, and auto-landing assist keep travel minutes, not hours, without faking the underlying physics.

Existing code location: `~\MODS CLAUDE\galactica\src\main\java\dev\galactica\system`.