# Ship ecosystem for Galactica on Minecraft 26.3 (Fabric), and a plan to expand the ships

Recon date: 2026-10-07. Target: MC 26.3, Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 (the repo pin).
Request (translated): "https://www.curseforge.com/minecraft/mc-mods/create-adapted — now expand the ships; look for other ports to focus integration with ships for the mod."

## How this was checked

- **CurseForge.** WebFetch worked on the project page this time. The full description, file list and file pages were read through the reader proxy `https://r.jina.ai/<curseforge url>`. File lists for other projects come from `https://api.cfwidget.com/minecraft/mc-mods/<slug>`, which returns every file with its game versions and loader tags.
- **Modrinth API.** Every "26.3 Fabric build" claim was checked with `GET https://api.modrinth.com/v2/project/<slug>/version?game_versions=["26.3"]&loaders=["fabric"]`. Licence, loaders and source URL come from `GET /v2/project/<slug>`. Search used `/v2/search` with facets `versions:26.3` + `categories:fabric`, and again with no filter.
- **GitHub.** Repository metadata, branches, releases, `gradle.properties` and `fabric.mod.json` were read through `api.github.com` and `raw.githubusercontent.com`.
- **Galactica code.** Read `README.md`, `MODLOG.md`, `docs/recon/mod_ecosystem_26_3.md`, `ship/*` (Starship 1 117 lines, ShipFlightModel, ShipDimensions, ShipHullProbes, DriveRules, Hyperdrive), `nav/FloatingOrigin`, `nav/OrbitalFrame`, `planet/PlanetEntry`, `planet/transfer/*`, `client/ship/*` and `client/dev/AutoTest`.
- **Not checked.** No jar was run. "Runs on 26.3" means the loader metadata allows it, not that it was tested in game.

---

## 1. What "Create: Adapted" is

| Field | Fact | Source |
|---|---|---|
| Name | Create: Adapted | https://www.curseforge.com/minecraft/mc-mods/create-adapted |
| Author | BugsGify on CurseForge, AlexSensey on GitHub (`mod_author = AlexSensey (adaptation); The Creators of Create (original)`) | CF page; https://github.com/AlexSensey/create-adapted (`gradle.properties`) |
| Licence | MIT for the code. Create's original assets are All Rights Reserved and are **not** redistributed. | CF sidebar "License MIT"; GitHub API `license.spdx_id = MIT`; README "External assets and licensing" |
| Loader | **NeoForge only.** The CF "Mod Loaders" list shows only NeoForge, and every one of the 48 files is tagged NeoForge. The GitHub description reads "Unofficial NeoForge adaptation of Create…". | https://www.curseforge.com/minecraft/mc-mods/create-adapted/files/all ; https://api.cfwidget.com/minecraft/mc-mods/create-adapted |
| MC versions | 26.3, 26.2, 26.1.2 | CF "Game Versions" |
| 26.3 build | `create-adapted-26.3-1.31-public-external-assets.jar`, file ID 9075351, 11.3 MB, Release, 2026-10-06, **NeoForge**, MC 26.3. Curse Maven: `curse.maven:create-adapted-1651484:9075351`. Earlier 26.3 files: 1.30 and 1.29 (2026-10-04), 9 in all. | https://www.curseforge.com/minecraft/mc-mods/create-adapted/files/9075351 |
| **26.3 Fabric build** | **None.** No file is tagged Fabric. | same |
| What it is | An unofficial port of **Create 6.0.x** (the asset source must be `create-1.21.1-6.0.10.jar`; `mod_compat_version = 6.0.11-adapted-0.996` on GitHub `main`) to MC 26.x. It keeps mod ID `create`. It includes kinetics, moving contraptions, trains, processing, tools and decoration, with Flywheel, Ponder, Catnip and Registrate embedded. The player must put the official Create 1.21.1 jar in `config/create-adapted/external-assets`, and the mod mounts that jar's resources at runtime. A dedicated server does not need the asset jar. | CF description; GitHub README |
| Maturity | Early. 7.8 K downloads and 3 GitHub stars. The project was created about a month ago. GitHub `main` still targets 26.2 (`neo_version_range = [26.2.0.57,26.3)`, last push 2026-09-12); the 26.3 jars exist only on CF. Mods that integrate with Create "may require separate updates". | CF details; GitHub API |

**Contraption API.** It is Create's own API, unchanged in name. The adapted source has `com.simibubi.create.content.contraptions.{Contraption, AbstractContraptionEntity, ControlledContraptionEntity, OrientedContraptionEntity, TranslatingContraption}`, plus `bearing/{BearingContraption, ClockworkContraption, StabilizedContraption}`, `mounted/MountedContraption`, `gantry/*`, `piston/*`, `pulley/*`, `elevator/*` and `render/{ClientContraption, ContraptionEntityRenderer, OrientedContraptionEntityRenderer}`. A `Contraption` is a block map (`StructureBlockInfo` keyed by local `BlockPos`) carried by an `AbstractContraptionEntity`. It is rendered through Flywheel and collides through Create's `ContraptionCollider`, which runs OBB-vs-voxel SAT against entities. Source: the GitHub tree (`git/trees/main?recursive=1`).

**Can the contraptions fly?** Not in Create itself. Contraptions move only by a controller: a bearing rotates them, a piston, gantry or pulley translates them, a minecart or train carries a `MountedContraption` or `CarriageContraption`, and an `OrientedContraptionEntity` follows its carrier. Create has no free 6-DOF rigid body and no thrust. Free flight comes from **Create Aeronautics / Simulated** on top of the **Sable** physics library (§2). Both are 1.21.1-only and NeoForge-first. An addon could subclass `AbstractContraptionEntity` and drive it with Galactica's flight model, but only on NeoForge.

**Bottom line for Galactica.** Create: Adapted does **not** run on 26.3 Fabric, and Galactica cannot load it. Sinytra Connector runs Fabric mods on NeoForge, not the other way round, and its newest build is `3.0.0-beta.6+26.1.2` (NeoForge, MC 26.1.2 only; https://modrinth.com/mod/connector, `/v2/project/connector/version`). No bridge exists on 26.3 in either direction today.

---

## 2. Other ports and ship or vehicle mods (26.3 Fabric availability)

"26.3 Fabric" is the exact Modrinth or CF file, or **none**.

### 2.1 Create ports and Create addons

| Mod | Licence | 26.3 Fabric | Notes / integration | Source |
|---|---|---|---|---|
| **Create Fly** (ZurrTum), the only living Fabric port of Create for 26.x | CC0-1.0 (code) | **None.** The newest file is `create-fly-26.2-rc-2-6.0.9-1.jar` (MC 26.2, 2026-06-14). `fabric.mod.json` on branch `26.2` declares `"minecraft": ">=26.2- <26.3-"`, so Fabric Loader will **refuse** it on 26.3. Branch `26.2` was still active on 2026-09-29 (model updates), and there is no `26.3` branch. | A Create 6.0.9 port, repackaged as `com.zurrtum.create.*` (`content/contraptions/AbstractContraptionEntity`, `OrientedContraptionEntity`). It does not need Fabric API. It is the realistic future Fabric Create for Galactica: watch it. Risk: the package names differ from upstream, so compat code is per-port. | https://modrinth.com/mod/create-fly ; https://github.com/ZurrTum/Create-Fly ; CF id 1346281 (cfwidget: 0 files for 26.3) |
| Create Fabric (Fabricators of Create) | MIT | **None.** Latest is `create-fabric-6.0.8.1+build.1744-mc1.20.1.jar` (MC 1.20.1). | Dead on 26.x. | https://modrinth.com/mod/create-fabric ; cfwidget `create-fabric` |
| Create (official, simibubi) | `LicenseRef-Create-Mod-License` (code MIT, assets ARR) | **None.** Latest is `create-1.21.1-6.0.10.jar` (NeoForge 1.21.1). | Asset source for Create: Adapted. | https://modrinth.com/mod/create ; cfwidget `create` |
| Create: Adapted | MIT | **None** (NeoForge 26.3 only, see §1) | — | §1 |
| Create Aeronautics / Simulated / Offroad | Code MIT, assets ARR (`LICENSE.md`); Modrinth shows `LicenseRef-Simulated-Project-License` | **None.** NeoForge 1.21.1 only (`minecraft_version=1.21.1`, `mod_version=1.3.2`). | "Build anything from airships to planes and cars". Physics contraptions on Sable, with hot-air lift, propellers and levitite. It is the best design reference for block-built flight. Not usable on 26.3. | https://modrinth.com/mod/create-aeronautics ; https://github.com/Creators-of-Aeronautics/Simulated-Project |
| Create Big Cannons | `LicenseRef-Create-Big-Cannons-License` | **None.** Latest `createbigcannons-5.11.7+mc.1.21.1.jar` (NeoForge). | Weapons reference only. | https://modrinth.com/mod/create-big-cannons ; cfwidget |
| Create: Interiors | GPL-3.0-or-later | **None.** Latest MC 1.21.1. | Seats and furniture on contraptions. | https://modrinth.com/mod/interiors |
| VS: Clockwork (Create + Valkyrien Skies) | Apache-2.0 | **None.** `clockwork-0.5.6.jar` (Fabric/Forge 1.20.1). | — | https://modrinth.com/mod/create-clockwork ; cfwidget `create-clockwork` |
| Create addons that *do* list 26.3 Fabric | various | Create: Synthetic Pressure `26.X-1.0.2`, Create: Nerfad `1.2.6` ("recipes for Create Fly"), Create: Fluid Burner `1.26.6.2` (requires `create-fly`), Alloyed `3.0.12+26.3-fabric` (bronze/steel, no Create dependency) | They are tagged 26.3, but the Create Fly they depend on refuses 26.3 (above). They do not give ships. | https://modrinth.com/mod/create-synthetic-pressure ; /create-nerfad ; /create-fluid-burner ; /create-alloyed |

### 2.2 Physics-ship libraries (moving block structures as real bodies)

| Mod | Licence | 26.3 Fabric | What it gives / integration / risk | Source |
|---|---|---|---|---|
| **Sable** (RyanHCode) | **PolyForm Shield 1.0.0.** Using it is allowed, but it forbids products that compete with Sable. | **None.** `sable-fabric-1.21.1-2.0.6.jar` (2026-10-03). `minecraft_version_range=[1.21.1]`, Java 21. No 26.x branch (branches: `main`, `feature/*`, `fix/*`). | "Sub-levels": real chunks with block entities and entities, at a dynamic pose, simulated by **Rapier** (Rust natives). The README calls it "incredibly intrusive", with extensive mixins. Sub-levels are chunks, so each floating-origin shift and dimension transfer would have to move whole plots, and it fights `SpaceChunkSaveMixin` and C2ME. High risk even once ported. | https://modrinth.com/mod/sable ; https://github.com/ryanhcode/sable |
| Sable Beyond | MIT | **None** (1.21.1) | Sable addon. | https://modrinth.com/mod/sable_beyond |
| **Valkyrien Skies 2** | LGPL-3.0 | **None.** `valkyrienskies-120-2.4.11.jar` (1.20.1, 2026-04-10). The highest branch is `1.21.1/main`. | Ships in a "ship yard" region of the same level, with a Krunch physics backend. The same chunk-plot problems as Sable, plus a global ship-yard coordinate space that collides with our floating origin. | https://modrinth.com/mod/valkyrien-skies ; https://github.com/ValkyrienSkies/Valkyrien-Skies-2 |
| Eureka! Ships (official) | Apache-2.0 | **None** (`eureka-1201-1.6.3.jar`, 1.20.1) | VS2 helm, balloons, engines. | https://modrinth.com/mod/eureka |
| Eureka! [UNOFFICIAL PORT] | Apache-2.0 | **None.** The newest is MC 26.2 (Fabric/NeoForge). It needs a VS2 build for 26.2 that is not on Modrinth or CF. | Not usable. | https://modrinth.com/mod/eureka!-(unofficial-port) |
| VS Tournament | Apache-2.0 | **None** (≤1.20.6) | VS2 thrusters and seats. | https://modrinth.com/mod/vs-tournament |
| Starlance | GPL-3.0-only | **None** (`Starlance-2.0.1.jar`, Forge 1.20.1) | VS2 space and ship mod. Design reference only. | https://modrinth.com/mod/starlance ; cfwidget `starlance` |
| Davinci's Vessels | — | **None** (`davincisvessels-1.12-6.355-full.jar`, 1.12.2, 2020) | Dead. | cfwidget `davincis-vessels` |
| Archimedes' Ships | — | **None** (1.7.10, 2014) | Dead. | cfwidget `archimedes-ships` |

### 2.3 Ship and vehicle mods that do exist on 26.3 Fabric

| Mod | Licence | 26.3 Fabric file | What it gives | Integration / risk | Source |
|---|---|---|---|---|---|
| **Sails and Airships: Block-Based Ships** | **All Rights Reserved**, closed source (no `source_url`) | `sailsandairships-1.0.5+26.3.jar` (2026-09-28). Needs only Fabric API. | The **only** 26.3 Fabric mod that turns ordinary blocks into a moving ship. Flood-fill assembly from a helm block, a 10 000-block cap, 84 blocks max length, a per-block density table for buoyancy, disassembly snapped to a cardinal direction, deck walking, working furnaces, chests and beds aboard, cannons, and airships with campfire lift and ghast drives. | Coexistence only. There is no API, and its ARR licence forbids copying code. Its ships would not get Galactica's floating origin, orbital frame or planet transfer, so warn or deny assembly in `galactica:space` and `surface_N` if both are installed. It is a good **UX reference** (helm, flood fill, density table, cardinal snap). Medium conflict risk: both mods move entities with riders. | https://modrinth.com/mod/sails-and-airships |
| Shippy Ships | Custom "Limited Rights Granted" | `shippy-ships-1.0.23-FABRIC-MC-26.3.jar` (2026-09-27), requires Ingenium API | Prefab sailing-ship entities. | No block building. Soft compat at most. | https://modrinth.com/mod/shippy-ships ; https://modrinth.com/mod/ingeniumapi |
| Automobility: Unofficial Port | MIT | `automobility-1.0.0-beta.1+26.3-fabric.jar` (2026-10-04) | Cars. | Rovers on planets; optional compat: let them use planet gravity. Low risk. | https://modrinth.com/mod/automobility-unofficial-port |
| Physics Mod (Haubna) | ARR | `physics-mod-3.2.5-mc-26.3-fabric.jar` (2026-09-17) | Debris and ragdolls. | Not ships. Test only for conflicts. | https://modrinth.com/mod/physicsmod |
| Physics Toys (Patbox) | LGPL-3.0 | `physics_toys-0.11.0+26.3-rc-1.jar` (requires Polymer) | Server-side rigid-body toys. It bundles **Libbulletjme 21.2.1** and Rayon (MIT). | Proof that Bullet runs on 26.x Fabric. Technique reference. | https://modrinth.com/mod/physics-toys ; https://github.com/Patbox/PhysicsToys |
| MC-26 GX | ARR, datapack | `mc-26-gx-2.5.jar` | A drivable display-entity spaceship (F-Zero style). | None. | https://modrinth.com/mod/mc-26-gx |
| Immersive Aircraft | GPL-3.0 | **None.** Latest `immersive_aircraft-fabric-1.5.2+26.2.jar`; the highest branch is `26.2`, last push 2026-10-07, so 26.3 is likely soon. | Prefab planes. | When it ships: optional compat that lets its aircraft enter and leave planets the way EVA does. | https://modrinth.com/mod/immersive-aircraft ; https://github.com/Luke100000/ImmersiveAircraft |
| Small Ships | ARR | **None** (≤1.21.4) | — | — | https://modrinth.com/mod/small-ships |
| Ad Astra | Terrarium licence | **None** (≤1.21.1) | Prefab rockets. | — | https://modrinth.com/mod/ad-astra |

### 2.4 Physics engines usable from our own code (no mod dependency)

| Library | Licence | Version | Note | Source |
|---|---|---|---|---|
| Libbulletjme (Bullet via JNI) | BSD-3 | 22.0.1 (Maven Central `com.github.stephengold:Libbulletjme-Windows64` etc.) | Already proven on 26.x Fabric by Physics Toys (21.2.1). Compound boxes give voxel ship hulls. | https://search.maven.org (g:com.github.stephengold) |
| jolt-jni (Jolt via JNI) | MIT | 1.0.0, natives per platform (`jolt-jni-Windows64`, `…_avx2`, Linux64, MacOSX_ARM64…) | Faster than Bullet and deterministic per platform. | same |

Neither one is needed for the first milestones (§3.4). Galactica's flight model already integrates 6-DOF by hand, so a native engine only pays off for ship–ship contact.

### 2.5 Summary of the landscape

On **26.3 Fabric** there is **no Create**: Create: Adapted is NeoForge, and Create Fly is capped below 26.3. There is no physics-ship library either: Sable, VS2 and Aeronautics are all 1.21.1. The only block-built ship mod is the closed, ARR **Sails and Airships**. Nothing exists that Galactica can integrate with to "build ships from blocks" today.

---

## 3. Design: expanding Galactica's ships

### 3.1 Decision

**Path B. Galactica builds its own ship system** ("ShipStructure"): a block-built craft that lives entirely inside one entity. It is designed so that a Create contraption can later be one of its block sources. Path A (integrating a contraption mod) is blocked on 26.3 Fabric (§2.5). Path C, porting Galactica to NeoForge or multiloader to sit next to Create: Adapted, would mean rewriting every Fabric API use (networking, attachments, events, Loom tasks, `verifyPerfStack`, autotests) for an early, single-author port. It is not justified now; revisit it if Create: Adapted matures or Create Fly never reaches 26.3.

**Why one entity and not a sub-level.** Sable and VS2 store a ship as real chunks. Galactica moves crafts by (a) floating-origin recentres every 1 024 blocks horizontally and 192 vertically, (b) `Entity.teleport(TeleportTransition)` between `galactica:space`, `surface_N` and the Overworld, and (c) orbital-frame integration of the root vehicle. All three already work for any root entity with riders (`FloatingOrigin.apply`, `PlanetEntry` lines 504–521 and 784–797, `Starship.carryCrew`). A ship whose blocks live in the entity rides on all of that for free. A ship whose blocks live in chunks would need every one of those paths rewritten to copy chunk plots across levels. The existing Kestrel is already "an entity with a custom hull", so the new system generalises it rather than replacing it.

### 3.2 Architecture

```
CraftEntity (abstract, from today's Starship: attitude, pilot reconciliation, DeltaVBudget, Krakensbane,
             hull probes, gear contact, fuel/exotic/hull data, crew carry, dismount, hurt)
 ├─ Starship        Kestrel: hand-modelled hull (BakedBones), ShipSpec.KESTREL, ShipHullProbes (generated)
 └─ ShipStructure   block-built: ShipBlocks (palette + local positions), ShipSpec computed, probes computed
ShipSpec            mass, centre of mass, inertia diagonal, main thrust + direction, RCS per axis, turn-rate
                    limits, H2 capacity, exotic capacity, jump tier, hull HP, seats[], probes[], box size
ShipFlightModel     unchanged maths; constants become ShipSpec fields (MASS_KG, MAIN_THRUST_N, RCS_THRUST_N…)
```

**Blocks a player builds with.** Each one has a data-driven role, set by tag plus a JSON `galactica:ship_part` registry so addons can add parts:

| Block | Role |
|---|---|
| **Helm** (the assembly anchor) | Pilot seat, cockpit display (reuses `CockpitDisplay`), defines "forward". Right-click: assemble or disassemble. |
| **Crew seat** | Passenger attachment point. |
| **Thruster** (small, large) | Thrust N, direction = block facing, consumes H2. The position gives torque. The exhaust reuses `ExhaustPhysics` and `ExhaustRenderer`. |
| **RCS quad** | RCS thrust about the centre of mass. |
| **Hydrogen tank** | Raises H2 capacity. Refuelled like the Kestrel today. |
| **Hyperdrive core** | Enables `Hyperdrive`, and raises the exotic capacity and jump tier (`DriveRules`). |
| **Landing gear** | Ground contact points with friction (`GEAR_FRICTION`). Without gear, the hull probes rest on the ground. |
| **Hull plating** | Normal blocks. Mass comes from a density table (as Sails and Airships does); armour blocks add hull HP. |
| **Cargo** | Chests and barrels keep their item NBT and stay openable while parked or underway (§3.3.6). |
| **Docking port** | §3.3.6. |
| **Shield emitter, scanner, weapon mount** (optional) | §3.3.6. |

**Assembly.** A flood fill from the helm through face-connected, non-air blocks, stopping at terrain. Terrain is ignored above a "ship frame" boundary if a frame block marks one, else the fill fails when it touches more than N world-generated blocks, as Sails and Airships does. Caps: 4 096 blocks at first (tunable), 64 × 32 × 64 bounds. Assembly requires a helm, at least one thruster and a tank. The fill copies `BlockState` + block-entity NBT into `ShipBlocks` (palette `List<BlockState>` + `short[] packed local pos` + `Map<Integer, CompoundTag>`), then removes the blocks without drops or updates (`Block.UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS`).

**Disassembly** happens only when landed and still (`restingContact()` + zero velocity). It snaps yaw to the nearest 90° (with `BlockState.rotate`) and the pose to the block grid, and checks every target cell is air or replaceable. Otherwise it refuses and names the blocking position.

**Prefab classes are just templates.** Shuttle (≈300 blocks, 2 seats), Freighter (≈2 500 blocks, cargo bay, hangar), Explorer (≈1 200 blocks, scanner, big tanks, tier-2 hyperdrive). They are `.nbt` structure templates in `data/galactica/ship_templates/` and are spawned by a beacon item exactly like `ShipBeaconItem` spawns the Kestrel. One code path covers prefab and player-built ships, and players can disassemble a prefab and modify it.

### 3.3 The hard problems and how each is solved

#### 3.3.1 Terrain collision of a rotated multi-block body
- **Today:** a vanilla AABB (6 × 3.2) plus 52 generated hull probes, a swept probe test with bisection to contact (`sweptClear`, `resolveHullContact`), and gear friction.
- **ShipStructure:** the same algorithm with **computed probes**:
  - take the exposed block corners and face centres, keep the outermost per azimuth/elevation sector (as `gen_starship.py` does now), then decimate to ≤ 256 points;
  - the AABB stays as the broad phase, sized to the ship's convex core (the probes cover the rest);
  - per-tick cost is O(probes) block lookups, about 256 `getBlockState` calls.
- **Large ships (> 256 probes needed):** use a hierarchical body-frame occupancy grid (an 8³ brick bitmap). Transform the world blocks under the ship's swept OBB into ship space and test them against the bitmap. This is the dual of Create's `ContraptionCollider` approach.
- **Ship-vs-ship contact:** treated as an OBB-vs-probe test both ways, with an impulse damping both. A native engine (Bullet or Jolt, §2.4) is only for a later "physics contact" milestone.

#### 3.3.2 Entities on and inside a moving ship (deck walking)
- **Phase 1:** everyone aboard is **seated**, so they are passengers and move with the existing crew code. Standing players inside a parked ship collide with a **static proxy**. While parked, the ship's blocks can be "ghosted" as a real block-shaped collision: `Entity.collide` mixin → add the ship's voxel shapes transformed by the snapped pose. This is valid only when attitude ≈ grid-aligned, which is true when parked.
- **Phase 2 (in flight):** walking on deck uses a ship-local frame. A `ShipFrameAttachment` on the player holds the local position. Each tick the player AABB is transformed into body space and collided against the occupancy grid as an OBB (SAT vs voxels, the way Create's collider does it). The result is then transformed back. Movement packets from walkers are interpreted relative to the ship. This extends `ServerGamePacketListenerOriginMixin`, which already translates positions during origin shifts.
- **Gravity aboard:** in space, a "gravity plating" flag gives ship-down gravity in the ship frame. Otherwise walkers float (EVA rules).

#### 3.3.3 Rendering cost
- **Mesh build:** bake the captured block set once into a vertex list with the vanilla block model tessellator. This happens off-thread, like `NearPlanetRenderer`'s mesh plan, and the mesh is rebuilt only when the block set changes. Each frame draws the baked list with one transform, the same path as `BakedBones` (37.2 → 24.8 µs for the Kestrel hull). Cull hidden faces between ship blocks during the bake.
- **Dynamic parts:** thruster glow, gear and doors are a small separate list, as the Kestrel's gear, canopy and nozzles are now.
- **LOD:** past 96 blocks, draw a greedy-merged coarse mesh. Past the tracking range the ship is not drawn.
- **Lighting:** `ShipLighting` / `SpaceLighting` (star direction). Block light inside the ship comes from a light map baked at assembly from the light-emitting blocks in the set.
- **Compat:** this is the entity render path, so Sodium does not touch it. Iris treats it as an entity (reuse `ShipRenderTypes` and `IrisCompat`). Keep the `EntityCulling` exemption that the Kestrel already has.
- **Block entities** (chests, furnaces) render as static models. No BER animation in flight at first.

#### 3.3.4 Chunk loading during floating-origin shifts and dimension transfer
- The blocks are entity data, so a recentre moves one entity plus its riders. `FloatingOrigin.apply` is unchanged, and the `CRAFT` and `LANDING` tickets already hold the path. Galactica already pays the full cost of a horizontal recentre: the chunks are regenerated and the empty ones are not saved (`SpaceChunkSaveMixin`). A large ship adds no chunk cost.
- **Vertical recentre guard:** `foreignNearby` must also treat other ShipStructures as "foreign", as it does Starships now.
- **Dimension transfer:** `root.teleport(TeleportTransition)` recreates the entity through `copyWithoutData` + `restoreFrom` in memory, so the block set is copied with the rest of the entity's data. The payload problem is the **client**. A 4 096-block ship cannot travel in `SynchedEntityData` or the add-entity packet. Instead:
  - a `ShipBlocksPayload` (palette + positions, deflated, ≤ ~64 KB, chunked when bigger) is sent from `EntityTrackingEvents.START_TRACKING` and on each re-track after a teleport;
  - the client renders a placeholder box until the payload arrives;
  - the existing `hasClientLoaded()` hold (MODLOG, "Decolagem travando o servidor") must also wait until the block payload has been received before the pilot's ship moves.
- **Saving:** blocks are written in `addAdditionalSaveData(ValueOutput)` as a compact int array. The cap keeps entity-chunk NBT small (≈ 4 096 × 4 B + palette ≈ 20 KB).
- **Planet entry and exit and the orbital frame** need no change beyond using `ShipSpec` for mass and thrust. `EntryAerothermo` / `EntryHeating` should scale with the ship's frontal area (computed from the occupancy projection) instead of the Kestrel constant.

#### 3.3.5 Multiplayer and authority
- The pilot stays **client-authoritative**, with the server clamps already in place: `DeltaVBudget` credited per server tick, `ShipFlightModel.maxDeltaVPerTick`, `clampAttitude` and the Krakensbane `rebase` with ack. These must read `ShipSpec` (the thrust/mass of the ship being flown) and **never values sent by the client**. The server computes the spec at assembly and syncs it.
- **Assembly and disassembly are server-only, one at a time per ship.** Lock the helm while a fill runs. The fill is bounded, so it runs in one tick.
- **Block edits aboard while assembled** (players placing or breaking ship blocks in flight) go through server packets that edit `ShipBlocks` and broadcast a delta. Phase 1: edits only while parked and disassembled.
- **Damage:** explosions and projectiles subtract hull HP. Past a threshold, they remove blocks (each removal is a delta payload) and the spec is recomputed. Removing the helm, or the last thruster, makes the ship a drifting hulk.
- **Unpiloted ships** keep the existing parked behaviour (`keepParkedInReach`, PARKED_LIMIT_Y).

#### 3.3.6 Ship systems
- **Cargo:** container blocks keep their NBT. Interacting with a ship block raycasts into body space, finds the block and opens a menu backed by the entity's stored container. The container is marked dirty on close.
- **Docking and hangar:** the simple, robust version is "a ship is a passenger of a ship". A hangar bay block defines a passenger slot. A Kestrel or shuttle that flies into the bay under a speed limit is attached as a passenger at the bay pose. `FloatingOrigin`, `PlanetEntry` and `carryCrew` already move root vehicles with **all indirect passengers**. Undocking restores independent flight and inherits the parent's velocity. Docking-port to docking-port links between two large ships (rigid joint) come later.
- **Shields:** an energy pool (emitter count × capacity) absorbs damage before the hull. It is drawn as a translucent ellipsoid with a `CELESTIAL`-style additive pass. It drains H2.
- **Scanners:** reuse the star chart, planet and system data (`/galactica system`, `nearest`). The scanner block raises the range and gives cockpit-display pages.
- **Weapons (optional, off by default in config):** a hitscan or projectile entity fired along the mount facing. This is out of scope until combat is wanted.

#### 3.3.7 Staying compatible with Create later
- Define a `ShipBlockSource` interface: `Map<BlockPos, BlockState + NBT>` → `ShipBlocks`.
- A compat module, compiled only when a Fabric Create for 26.3 exists (`compileOnly`, behind `OptionalMods`), would convert an assembled Create `Contraption` into a ShipStructure. It reads `Contraption.getBlocks()`, which is `com.zurrtum.create…` for Create Fly or `com.simibubi.create…` for Create Fabric, and maps Create propellers and blaze burners to thruster parts through the `galactica:ship_part` JSON.
- Kinetic animation is lost while the contraption is a ShipStructure. This is acceptable: it is a starship, not a machine.
- Do not mixin into Create internals.

### 3.4 Milestones

| # | Milestone | Content | Exit criteria |
|---|---|---|---|
| S0 | Refactor | Extract `CraftEntity` from `Starship`. Add `ShipSpec` (Kestrel preset = current constants); `ShipFlightModel`, `DeltaVBudget`, `maxDeltaVPerTick`, `DriveRules` and `EntryAerothermo` take the spec. The probe list becomes a spec field. | `build` green (365 tests), `runAutotest` 24/24 and `runAutotestSoi` 5/5 unchanged. A perf scene `ship_cruise` within ±5 %. |
| S1 | Assemble and render | Helm, thruster and tank blocks; flood fill; `ShipBlocks`; NBT save; `ShipBlocksPayload`; baked-mesh renderer; disassembly with cardinal snap. No flight. | Assemble → save/quit → reload → disassemble gives an identical block set. Render matches the blocks (screenshot compare). |
| S2 | Flight | Computed mass, centre of mass and inertia; thruster torque; computed probes; gear; server clamps from the spec. | Hover, landing and take-off at Overworld and Mars gravity. No terrain clipping in the probe sweep. |
| S3 | Space integration | Floating origin (horizontal and vertical guard), `PlanetEntry` exit and entry, orbital frame, warp, hyperdrive core, payload re-send on re-track, `hasClientLoaded` + payload hold. | SOI orbit and approach tests pass with a built ship. A built ship lands on Mars and takes off back to orbit. |
| S4 | Prefabs | Shuttle, Freighter and Explorer templates and beacons; HUD and cockpit pages per class. | Each spawns, flies, jumps and lands in the autotest. |
| S5 | Crew and cargo | Seats; parked deck collision proxy; cargo menus; block edits while parked. | Two players aboard through a planet entry; cargo survives a dimension transfer. |
| S6 | Hangar and docking | Ship-as-passenger in a hangar bay; undock. | A Kestrel docked in a Freighter crosses a recentre and a hyperjump and undocks intact. |
| S7 | Deck walking in flight | Ship-frame attachment; OBB-vs-voxel collision; packet translation. | Walking on deck at 285 m/s and through a recentre, with no rubber-band (traceShip drift < 0.05 block). |
| S8 | Systems and perf | Shields, scanner, damage removing blocks, LOD; `built_ship_cruise` perf scene. | 1% low within 15 % of `ship_cruise` for a 2 500-block freighter. |
| S9 (conditional) | Create compat | `ShipBlockSource` for Create Fly once it publishes a 26.3 build; Sails and Airships coexistence guard. | Contraption → ShipStructure round-trip test in a dev run. |

### 3.5 Test strategy

**JUnit (pure Java, run in `./gradlew build`):**
- `ShipSpecTest`:
  - mass, centre of mass and inertia of known shapes (a 1×1×N rod, a cube, an L shape) against the analytic values;
  - thrust → torque for off-centre thrusters;
  - the Kestrel preset equals today's constants (`MASS_KG` 50 000, `MAIN_THRUST_N` 2.5e6, `RCS_THRUST_N` 0.5e6).
- `AssemblyFillTest`: flood fill on a grid abstraction (no Minecraft): caps, the terrain-touch rule, the helm requirement, and disconnected parts left behind.
- `ProbeGenTest`: computed probes for the Kestrel's voxelisation lie within 0.5 block of the generated `ShipHullProbes` envelope. There are ≤ 256 of them, and every exposed corner is within one sector of a probe.
- `ShipBlocksCodecTest`: NBT and payload round-trip. Payload size stays ≤ 64 KB for 4 096 blocks. Rotations by 90° are reversible.
- `CardinalSnapTest`: disassembly pose is chosen from the attitude, and the yaw error is ≤ 45°.

**Autotest (`client/dev/AutoTest`, one PASS/FAIL line each), added as `runAutotestShips` and appended to `runAutotest`:**
1. `ship-assemble`: place a test template on the glass platform at y 200, right-click the helm, check the entity's block count equals the template, then disassemble and compare per-position states.
2. `ship-hover`: assist on and throttle for hover. Altitude drift < 0.5 block over 10 s at g = 9.81.
3. `ship-recentre`: fly 1 200 blocks in `galactica:space`. Exactly one `OriginShiftPayload` acked, no position jump > 0.05 block on the client trace, and the block payload not re-sent (same entity).
4. `ship-mars`: entry, landing and resting contact (the `traceShip` drift check from the Mars Kestrel test), then take-off to orbit (the decel-vs-g check from SOI).
5. `ship-jump`: hyperjump with a core block, arriving with the payload re-received after re-track and the crew in seats.
6. `ship-hangar` (S6): docked Kestrel survives a recentre, a jump and an undock.
7. Perf scene `built_ship_cruise` in `PerfBench` (`runAutotestPerf`, `runAutotestPerfStack`).

**Server-side gametests (Fabric gametest API):** assembly and disassembly in a headless server, including a dedicated-server run to catch client-class leaks.

**Mod-stack checks:** run `runAutotestPerfStack` with C2ME, which changes chunk saving (gotcha 5). Do one manual run with Sails and Airships installed to confirm the coexistence guard.

---

## 4. Recommendations (short)

1. **Build ShipStructure in Galactica (Path B), starting with S0.** It is the only path that works on 26.3 Fabric, and it keeps floating origin, orbital frame and planet transfer intact because the ship stays one entity.
2. **Do not depend on Create: Adapted.** It is NeoForge-only, there is no Fabric bridge on 26.3, and the port is early and single-author.
3. **Watch Create Fly** (https://github.com/ZurrTum/Create-Fly) for a `26.3` branch. When it lands, do S9 as an optional `ShipBlockSource`.
4. **Use Sails and Airships and Create Aeronautics as UX references only.** Take ideas such as the helm-driven flood fill, the density table and the cardinal snap, but no code: one is ARR, and the other is 1.21.1 and its assets are ARR.
5. **Keep native physics (Bullet or Jolt) out until ship-vs-ship contact is wanted.** If it is, Libbulletjme is already proven on 26.x Fabric (Physics Toys).
6. **Optional soft compat later:** Immersive Aircraft (when its 26.3 build appears) and Automobility (26.3 now) on planet surfaces.
