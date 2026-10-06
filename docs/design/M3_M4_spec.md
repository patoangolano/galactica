# Galactica M3 + M4 — implementation spec

Status: contract for implementers. Science stays in the pure-Java packages (`science`, `galaxy`, `star`, `system`, `sky`, `nav`). Minecraft glue lives in `dev.galactica.{content,ship,survival,planet,client.*}`. Every number below has a source or a stated game-design reason.

## 0. Principles
- **Real physics, compressed time.** Distances, masses, gravity, Δv and atmospheres are real. Only *time* is compressed, through the drives. No fake numbers.
- **The server is authoritative for state.** Ship flight is client-predicted, the same way vanilla boats are.
- **Shader-safe rendering.** Draw only with vanilla pipelines. Compatibility comes later.
- **Every mechanic gets an autotest step** in `client/dev/AutoTest.java` and a JUnit test for its pure logic.

## 1. Content (package `dev.galactica.content`)

### Items

| id | what | notes |
|---|---|---|
| `galactica:ship_beacon` | deploys a Starship entity where you look (≤ 8 blocks) | consumed in survival |
| `galactica:eva_helmet`, `eva_suit`, `eva_leggings`, `eva_boots` | space suit; a full set enables life support | armour stats ≈ iron |
| `galactica:oxygen_tank` | O₂ capacity 2 700 s (45 min); refills the suit | component `galactica:oxygen` (int seconds) |
| `galactica:hydrogen_cell` | 1 unit of drive fuel | stack 64 |
| `galactica:exotic_matter` | hyperjump fuel; 1 unit per jump-light-year (see §2) | stack 64, rare |
| `galactica:spectrometer` | scanner (M5) | use = scan target |
| `galactica:star_chart` | opens the Galaxy Map screen | |

Recipes (vanilla ingredients, shaped):
- beacon = iron blocks + glass + redstone + compass;
- suit = leather + iron + glass;
- tank = iron + glass bottle;
- hydrogen cell = glass bottle + water bucket + redstone (electrolysis);
- exotic matter = ender eye + amethyst + nether star ×1 → 16;
- spectrometer = spyglass + amethyst + redstone;
- star chart = map + amethyst.

### Data components
- `galactica:oxygen` (int seconds)
- `galactica:fuel` (double, hydrogen units, stored on the ship entity, not on items)

## 2. Ship (package `dev.galactica.ship`)

`Starship` is a non-living vehicle entity, 3 × 2 × 5 blocks, with 1 pilot and 1 passenger seat. Its persisted and synced state:
- **hydrogen** (units, max 400)
- **exotic matter** (units, max 64)
- **hull** (0–100)
- **attitude** quaternion (x, y, z, w), synced for rendering

**Flight model (block scale, Newtonian 6DOF, client-predicted like a boat):**
- Mass 50 t. Main thrust 2.5 MN (5 g), RCS 0.5 MN.
- Controls:
  - W/S: main thrust forward/back.
  - A/D: yaw.
  - Mouse: pitch and yaw.
  - Q/E: roll (new key mappings).
  - Space/Ctrl: up/down translate.
- **Coupled mode** (default, toggle with C): flight assist damps lateral velocity, like Star Citizen. Decoupled mode is pure inertia.
- Velocity is in m/s, and 1 block = 1 m. Hydrogen burn: 1 unit per 20 s of main thrust (game-balance value).
- In space, gravity follows `Neighbourhood`: GM/r² toward the dominant body, with the sphere of influence from `Orbit.sphereOfInfluence`. This is "real" gravity, scaled by the 1 block = 1 m frame. It is negligible in deep space.

**Drives:**
- **Warp:** the existing NavServer drive, but only while piloting (or in creative EVA). It costs 1 hydrogen per 10 s while above 1 000 c. The governor is unchanged.
- **Hyperjump:**
  1. Pick a target in the Galaxy Map, or use `/galactica jump`.
  2. Range = min(exotic × 1 ly, tier range 10 ly). Exotic cost = ceil(distance in ly).
  3. A 5 s spool, then arrival through `GalacticaCommands.arriveAt` logic. That logic moves to `ship.Hyperdrive`.

The ship moves with the floating origin: `NavServer` shifts ship entities the same way it shifts players. Teleport them together, passengers included.

**Model:** cuboid `ModelPart`s built in code, with a texture generated procedurally at runtime (hull panels, windows, engine glow) and registered as a DynamicTexture. There are no asset files to license; fal can replace this later.

## 3. Survival (package `dev.galactica.survival`)

**Oxygen (`LifeSupport`):**
- A player is "in vacuum" when in `galactica:space` (outside a ship) or on a planet whose atmosphere is not breathable.
- With a full suit and O₂ > 0, the suit drains 1 O₂ per second. Otherwise vanilla air supply drains as if underwater, and at 0 the player takes drowning damage.
- The suit refills from a tank in the inventory automatically.

**Temperature:** if surface T < 200 K or > 330 K and the player has no full suit, apply freeze damage (cold) or fire damage (hot), 1 per 2 s.

**Pressure:** above 10 bar without a suit, 1 damage per s (Venus).

**HUD:** O₂ bar, ship fuel and hull while piloting, and suit temperature status.

## 4. Galaxy map (`client.map.GalaxyMapScreen`)
- **Background:** a face-on render of the galaxy, a 1024² bake of `GalaxySkyBaker` from (0, 0, 30 kpc) with `SkyTonemap`. It is cached to disk under `config/galactica/map_faceon.png` on first open (~2 s).
- **Layers:** a "you are here" marker, Sol, catalog stars, and the 200 nearest procedural stars when zoomed in.
- **Zoom:** levels 30 kpc → 1 kpc → 50 pc (log scroll).
- **Interaction:** clicking a star sets the hyperjump target. Range circles are drawn from the ship's exotic matter.
- **Data from the client:** the client's own `Galaxy` (deterministic, same seed). The target is sent with a C2S payload `SetJumpTarget(starId, sector, index)`.

## 5. Planet surfaces (package `dev.galactica.planet`)

**Dimension pool:**
- 8 datapack dimensions `galactica:surface_0..7`. The generator type is `galactica:planet`, with `"slot": N` in its codec.
- `PlanetSlots` SavedData (overworld storage) maps slot → body id plus galaxy address (star sector/index or catalog id, planet index, moon index). It also holds the last-used tick.
- When a planet is visited, it gets a free slot, or the LRU slot is evicted.
- **Eviction:** delete that dimension's region folder while no player is in it, then reuse the slot. Do this from the server thread between ticks, with the level saved and unloaded. If unloading a datapack level is not possible at runtime, keep 32 slots and evict only on server start.

**Surface generator (`PlanetChunkGenerator`):**
- Heightmap from `Noise.fbm(terrainSeed)`. Relief amplitude scales as ∝ 1/g (low gravity means taller mountains), clamped to 20–250 blocks. Sea level is used for OCEAN and TERRAN.
- Block palette by `BodyType`:

| BodyType | top block | filler | under |
|---|---|---|---|
| BARREN | gravel | tuff | stone |
| DESERT | red sand | terracotta | – |
| ICE | packed ice / snow | blue ice | – |
| LAVA | basalt / blackstone | magma | lava lakes at sea level |
| TERRAN | grass / dirt | stone | water |
| OCEAN | mostly water | sand islands | – |
| GREENHOUSE | yellow terracotta / smooth basalt | – | – |

- Gas giants are not landable.
- Craters on airless bodies come from a cellular-noise stamp.

**Biomes:** one per type (`galactica:planet_<type>`). The biome `attributes` set the sky and fog colours:
- Rayleigh blue for N2_O2, pale orange for N2_CH4, yellow-white for CO2_THICK, black for NONE / TRACE.

**Dimension type `galactica:surface`:** skylight on, normal day cycle, `skybox` overworld. M4.1 accepts one shared day length; real rotation periods come later through a custom clock.

**Gravity:** an attribute modifier on GRAVITY, = g / 9.81 relative to the default 0.08, reusing the `NavServer.applyZeroG` pattern.

**Entry and exit:**
- In space, the ship or player crossing `body.atmosphereTopMetres()` (or 100 km for airless bodies) of a landable body triggers entry.
- **Entry:** claim a slot, then teleport the ship and its passengers to the matching lat/long position at Y = 600 in the slot dimension. Entry heating is a particle and HUD effect when the atmosphere is > 0.1 bar.
- **Exit:** flying above Y 1 200 in a surface dimension returns you to space, 2 radii out from the body on the same radial.
- The sky on the surface keeps the vanilla sky renderer plus our overlay: the system's star as the sun, at its real angular size and colour; other planets and moons via `PlanetImager`; and the galaxy at night.

## 6. Commands
Keep the existing `/galactica` commands and add these, for creative and debug use:
- `/galactica ship give`
- `/galactica land <body>`: direct surface entry
- `/galactica fuel <h> <x>`

## 7. Autotest additions
In order:
1. Deploy a ship.
2. Mount it and fly 5 s at thrust.
3. Hyperjump with fuel.
4. Open the Galaxy Map and screenshot it.
5. Land on Mars, take a surface screenshot, and check that gravity reads 0.38 g.
6. Land on Earth.
7. Take off back to space.
