Spheromatic is not a space mod on its own. It is a small Forge add-on that turns Cosmic Horizons (`cosmos`) planets from rounded cubes into spheres. It contains one Java class and works by overriding assets: same-path resource files (shaders, mesh "shards", textures) plus a few static fields set at startup. Nothing from the jar was executed; the one class was only disassembled with `javap`.

Extracted to: `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/ref/spheromatic`

## 1. Loader, version, ID, dependencies
- **Source:** `META-INF/mods.toml`.
- **Loader:** Forge (`modLoader="javafml"`, `loaderVersion="[47,)"`), MC `[1.20.1]`, `pack.mcmeta` pack_format 15, Java 17 (class major version 61).
- **Mod:** modId `spheromatic`, display name "CH Spheromatic", version 1.0.0 (jar is named 0.0.1), author GHO__ST.
- **Dependencies:** `minecraft` and `cosmos` (Cosmic Horizons, any version), both mandatory, loaded after.
- **Mixins:** `mixins.spheromatic.json` is declared (package `net.cosmos.spheromatic.mixins`), but its `mixins` and `client` lists are empty, and there is no refmap or mixin class. In practice there are no mixins.
- **Lang:** `assets/spheromatic/lang/en_us.json` is `{}`.

## 2. Architecture
There is one class, `net/cosmos/spheromatic/Spheromatic.class`.
- **Constructor:** registers itself on the Forge `EVENT_BUS` and adds a `clientSetup` listener on the mod bus.
- **`clientSetup(FMLClientSetupEvent)`** only writes public static fields inside Cosmic Horizons:
  - `net.distant.cosmos.core.client.render.universe.object.planet.BodyRenderer.activeNamespace = "spheromatic"`
  - `...planet.CloudRenderer.activeNamespace = "spheromatic"`
  - `BodyRenderer.useSphericalCollision = true`
  - `net.distant.cosmos.core.client.render.panorama.PanoramaRenderer.setErebusModel("spheromatic", "spheromatic:shape/sphere")`

The host mod therefore has a "mesh namespace" hook: body and cloud renderers resolve their shape from `<activeNamespace>:shape/...`. Everything else is resource-pack shadowing of `assets/cosmos/...`.

## 3. How it renders space (all in the host; this add-on swaps pieces)
Cosmic Horizons runs its own render framework, "soul". It has a text DSL called `.shard` under `soul/buffers/shards/` that builds vertex buffers, and core shaders under `soul/shaders/`.

**Mesh files**
- **`assets/spheromatic/soul/buffers/shards/shape/sphere.shard`:** `~Normal(Position) ~Tangent(Auto) connect("spheromatic:models/universe/sphere.obj")`. It loads an OBJ and derives normals from position.
- **`assets/spheromatic/models/universe/sphere.obj`:** a Blender cube-sphere (subdivided cube projected onto a sphere), radius 0.5, 1682 v / 1538 vn / 1536 f. It has no seams or pole pinching.
- **`assets/spheromatic/soul/buffers/shards/shape/sphere_uv.shard`:** a procedural UV sphere in the DSL. It loops 9° steps (40×20 quads, 1600 triangles) with sin/cos, has equirect UVs (i/360, j/180), and radius 0.5. This shows the DSL supports loops, variables, trig and `begin(TRIANGLES)`.
- **`assets/cosmos/soul/buffers/shards/shape/ring.shard`:** override of the host ring. It is a flat annulus `TRIANGLE_STRIP`, front and back, outer half-size s=0.5, inner h=0.1442, with planar UVs equal to position.

**Shaders**
- **`assets/cosmos/soul/shaders/include/sdf.glsl`:** the host shapes planets with SDFs (`roundBoxSDF`, `super16ellipsoidSDF`, `super32ellipsoidSDF`), which give Minecraft-style rounded cubes. The override replaces all three with `length(p)`, i.e. a perfect sphere. This file is the core of the add-on.
- **`include/shadow.glsl`:** analytic ray–sphere shadow. The planet is a unit sphere (center 0, R 0.5) in model space. The world fragment is moved into planet space with `planetInvModel`, cast toward the light, and the chord length gives a smoothstep penumbra. Constants: `SHADOW_INTENSITY 0.2`, `TRANSITION_SHARPNESS 0.5`.
- **`include/occlusion.glsl`:** `isOccludedByPlanet`, the same ray–sphere test between the fragment and the camera. It hides objects behind planets without depth-buffer reliance.
- **`core/universe/planet/atmosphere.fsh`** (GLSL 330):
  - Single-scattering Rayleigh only, no Mie.
  - Coefficients are `(400/λ)^4 · intensity`, and density is `exp(-h·falloff)·(1-h)`.
  - 6 view steps and 3 light steps, run on the outer-shell mesh in local space.
  - Has a `horizonMask` terminator fade.
  - Writes **MRT** `location=0` color and `location=4 fragSky`. That second target feeds the host's sky tint when standing on a planet (`Bsky`, `SkyBlend`).
  - Uniforms: `PlanetRadius`, `AtmosphereThickness`, `WaveLength`, `HeightFalloff`, `ScatterIntensity`.

**Textures** (`assets/cosmos/textures/universe/system/...`)
- Gas giants `solar_system/{jupiter,saturn,uranus,neptune}` and `b1400_centauri/j1407b` (the exoplanet J1407b, with its huge ring system).
- Each has `basic.png`, a 2048×1024 equirect albedo replacing the host's cube-face texture, and `flow.png`, a 2048×2048 flow map that animates the cloud bands.

**Not present in this jar:** no starfield or skybox code, no Iris/Oculus compat, no LevelRenderer mixins. Those live in the host and should be checked in the CosmicHorizons jar analysis.

## 4. Dimensions and worldgen
None in this jar: no dimension JSON, worldgen, coordinate or scale data. `useSphericalCollision=true` shows the host has collision against a planet's visual body that switches between box/SDF and a radius test. Whether a floating origin is used has to come from the host jar.

## 5. Gameplay mechanics
None: no items, blocks, entities, ships, fuel, oxygen or gravity.

## 6. Worth re-implementing
- **Shape as data:** a mesh DSL or OBJ, switched through a namespace field. Renderers stay shape-agnostic, and add-ons swap shapes without code.
- **Unit-sphere model space** (R=0.5) plus an inverse model matrix for all analytic tests: shadows, occlusion, eclipses and atmosphere. This is cheap and needs no depth precision, which makes it a good fit for real-scale distances.
- **Cube-sphere mesh** instead of a UV sphere: even vertex density and no pole artifacts. For real-scale planets, extend it into per-face quadtree LOD (chunked LOD) with double-precision camera-relative origins.
- **Atmosphere** as a ray-marched shell over a proxy mesh, with a second render target driving the surface sky colour. That lets the same parameters drive space and ground views.
- **Flow-map gas giants:** strong look for only two textures.

## 6. Weaknesses and limits to avoid
- **Monkey-patching:** it overwrites another mod's assets and statics. It is fragile, breaks on host updates, and has no API or version pin (`cosmos` range `*`). Expose a proper registry or API instead.
- **Atmosphere model:** Rayleigh only, no Mie, no ozone absorption, no multiple scattering, no precomputed LUTs. 6×3 samples band at grazing angles. Use the Bruneton or Hillaire transmittance/multi-scatter LUT approach for scientific accuracy and better performance.
- **Missing equirect handling:** no mip/seam handling for the equirect textures, and gas-giant albedo only covers 5 bodies, all hand-painted.
- **Wrong MRT target:** it writes to MRT location 4 of the host's own framebuffer. That conflicts with Iris shader packs, which own the G-buffers. A shader-compatible design needs a separate pass or an Iris pipeline integration.
- **Fixed tessellation:** about 1.5k faces at every distance, which is fine for orbital views but wrong for a surface approach.
- **Rings:** the shader-only-sphere `sdf.glsl` stubs ignore their parameters, and the ring is a flat square annulus using alpha masking only, with no particle/forward-scatter lighting.

**Files inspected:**
- `META-INF/mods.toml`, `META-INF/MANIFEST.MF`, `mixins.spheromatic.json`, `pack.mcmeta`
- `net/cosmos/spheromatic/Spheromatic.class` (`javap -p -c -v`)
- `assets/spheromatic/soul/buffers/shards/shape/sphere.shard`, `.../sphere_uv.shard`
- `assets/spheromatic/models/universe/sphere.obj`
- `assets/cosmos/soul/buffers/shards/shape/ring.shard`
- `assets/cosmos/soul/shaders/core/universe/planet/atmosphere.fsh`
- `assets/cosmos/soul/shaders/include/{sdf,shadow,occlusion}.glsl`
- `assets/cosmos/textures/universe/system/**/{basic,flow}.png`