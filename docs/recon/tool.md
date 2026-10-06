# 26.3 Fabric toolchain report

## a) MultiLoader-Template
- **Path and branch:** `C:/Users/Manec/minecraft-claude/repos/MultiLoader-Template`. The checked-out branch is `26.3`, up to date with `origin/26.3`. HEAD is `bc8c34e`, "Port to 26.3", dated 2026-09-16.
- **Other branches:** 1.17 through 1.21.11, plus 26.1, 26.1.1, 26.1.2, 26.2 and 26.3. `origin/HEAD` points to `origin/26.3`.
- **gradle.properties:**
  - `minecraft_version=26.3`, `minecraft_version_range=[26.3, 26.4)`, `java_version=25`, `version=26.3.0.0`
  - `fabric_version=0.160.6+26.3`, `fabric_loader_version=0.19.5`
  - `neo_form_version=26.3-1`, `neoforge_version=26.3.0.1-beta`, `neoforge_loader_version_range=[4,)`
  - `org.gradle.jvmargs=-Xmx3G`, `org.gradle.daemon=false`
- **Root build.gradle:** `id 'net.fabricmc.fabric-loom' version '1.18.1' apply false` and `id 'net.neoforged.moddev' version '2.0.147' apply false`.
  - The Loom plugin ID is the new `net.fabricmc.fabric-loom`, not `fabric-loom`.
- **settings.gradle:**
  - Plugin repos: gradlePluginPortal, mavenCentral, and maven.fabricmc.net set as exclusiveContent for `net.fabricmc`.
  - Plugin `org.gradle.toolchains.foojay-resolver-convention` 1.0.0.
  - `includeBuild('build-logic')`, then include common, fabric and neoforge.
- **Gradle wrapper:** `gradle-9.7.1-bin.zip`.
- **build-logic:**
  - `multiloader-common.gradle`: java-library and maven-publish, `toolchain.languageVersion = JavaLanguageVersion.of(java_version)`. The `expandProps` map is used for fabric.mod.json, `*.mixins.json` and pack.mcmeta, so any new property must be added to it.
  - `multiloader-loader.gradle`: wires in the `commonJava` and `commonResources` configurations.
- **common/build.gradle:**
  - Uses `net.neoforged.moddev` with `neoFormVersion`. Access transformers are read from `common/src/main/resources/META-INF/accesstransformer.cfg`.
  - `compileOnly 'net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7'`
  - `compileOnly(annotationProcessor('io.github.llamalad7:mixinextras-common:0.5.3'))`
- **fabric/build.gradle:**
  - Dependencies: `minecraft("com.mojang:minecraft:${minecraft_version}")`, `implementation fabric-loader` and `implementation fabric-api`. It uses `implementation`, not `modImplementation`, and has no `mappings` line. That fits the unobfuscated, Mojang-named builds.
  - The access widener is read from `common/src/main/resources/${mod_id}.accesswidener` if that file exists.
  - Run configs: client (runDir `runs/client`) and server (runDir `runs/server`).
- **fabric.mod.json template:**
  - `schemaVersion` 1, `environment "*"`, main entrypoint `com.example.examplemod.ExampleMod`.
  - Mixin configs: `${mod_id}.mixins.json` and `${mod_id}.fabric.mixins.json`.
  - depends: `fabricloader >=${fabric_loader_version}`, `fabric-api *`, `minecraft ~${minecraft_version}`, `java >=${java_version}`.
- **Mixin JSONs:** `"compatibilityLevel": "JAVA_25"`, `minVersion` 0.8, `defaultRequire` 1.
- **Fabric-only project option:** a single-loader project can drop the `common` and `neoforge` modules.

## b) Fabric CLI (`@fabricmc/cli` 0.1.466)
- **Commands:** `init [dir]`, `versions [mc]`, `completions`.
- **`fabric versions 26.3` returns:**
  - `loader_version=0.19.5`
  - `loom_version=1.18-SNAPSHOT`
  - `fabric_api_version=0.162.0+26.3`
- **Differences from the template:** the template pins fabric-api `0.160.6+26.3` and Loom `1.18.1`. The CLI recommends the newer `0.162.0+26.3`. The Gradle cache only holds `0.161.0+26.3`.

## c) JDK and Gradle properties
- **Temurin JDKs:** `C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot` and `C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot`.
- **`~/.gradle/gradle.properties`:**
  - `org.gradle.java.installations.paths` lists five JDKs: Temurin 17, `C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot`, Temurin 25, `C:/Users/Manec/scoop/apps/intellij-jbr21-sdk/current` and `C:/Users/Manec/scoop/apps/intellij-jbr25-sdk/current`.
  - `org.gradle.java.installations.auto-download=true`, `org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.jvmargs=-Xmx4G -Dfile.encoding=UTF-8`.
  - JBR 25 is available for hotswap.

## d) Prism Launcher
- **Instances:** `1.20.1`, `Attack On Titan - Awakening Of The Titans`, `RTX ON`, `Voxyris`. None of them is 26.3.
- **Voxyris (`mmc-pack.json`):** MC 26.2, Fabric Loader 0.19.5, LWJGL 3.4.1, intermediary 26.2.
- **Voxyris mods, rendering-relevant:**
  - `iris-fabric-1.11.2+mc26.2.jar`, `sodium-fabric-0.9.1+mc26.2.jar`, `sodium-extra-fabric-0.9.4+mc26.2.jar`
  - `voxy-0.2.19-beta.jar` plus `Voxy World Gen V2-fabric-26.2-2.4.3.jar` (LoD renderer)
  - `ImmediatelyFast-Fabric-1.16.5+26.2.jar`, `entityculling-fabric-1.11.2-mc26.2.jar`, `moreculling-fabric-26.2-1.8.1.jar`
  - `EuphoriaPatcher-1.10.5-r5.9.3-fabric.jar`, `IrisSearch-1.8.1-fabric.jar`, `ScalableLux-fabric-0.3.0-alpha.0.3-all.jar`
  - `c2me-fabric-mc26.2-0.4.2-alpha.0.52.jar` and `c2me-fabric-opts-accel-opencl-mc26.2-0.4.2-alpha.0.52.jar`
  - `lithium-fabric-0.25.3+mc26.2.jar`, `ferritecore-9.0.0-fabric.jar`, `modernfix-5.27.19-build.1.jar`, `fabric-api-0.161.0+26.2.jar`
- **Voxyris mods, everything else:** AsyncParticles, BadOptimizations, ConfigManager, Controlling, DeleteWorldsToTrash, FastServerPings (`+26.3` build), Gnetum, Ixeris, PacketFixer, ResourcefulConfig, Searchables, Stfu, alternate-current, async-fabric, asynclogger, betterbiomeblend, cerulean, cloth-config, collective, controlify, cwb, debugify, dynamic-fps, fabric-language-kotlin 1.14.1 (Kotlin 2.4.20), jasione, krypton, logarithmic-volume-control, modmenu 20.0.2, obe, placeholder-api, screenshot_viewer, simplemenu, simpleupdatechecker, structure_layout_optimizer, YACL 3.9.7, zconfig, zfastnoise, zfastsurface, zoomify.
- **Voxyris shaderpacks:**
  - BSL_v10.1.8, Bloop-1.8.0-Alpha-3
  - ComplementaryReimagined_r5.9.3 and ComplementaryUnbound_r5.9.3, each also present as a +EuphoriaPatches_1.10.5 folder
  - Glimmer-v1.5.2, I Like Vanilla v1.4.4, Just Colored Lighting 1.0.1, MakeUp-UltraFast-9.5e, Mellow v3.4.1a, Noble v1.9.8 hotfix
  - photon_v1.3b, RenderPearl v2.8.0-beta.4, Reverie Beta v0.9, Sildur's Basic/Enhanced/Vibrant, Solas V3.7b, Steadfast-0.8.0, Sundial Lite v1.1.0, E-LITE 5.1.1, 春 v2 260618
- **Cached MC versions:** `PrismLauncher/libraries/com/mojang/minecraft` has 1.20.1, 1.21.1 and 26.2. `meta/net.minecraft` has `26.2.json`. There is no 26.3 in Prism.
- **To test on 26.3, one of these is needed:** a new Prism instance (MC 26.3, Loader 0.19.5, Iris 26.3, Sodium 26.3), or Loom `runClient`.

## e) Shaders and the 26.3 render API
These are verified against the decompiled 26.3 sources, cached jars and the Modrinth API.

### Iris and Sodium for 26.3 (Modrinth)
| Mod | Latest 26.3 Fabric build | Date | Type | Earlier |
|---|---|---|---|---|
| Iris | `1.11.7+26.3-fabric` | 2026-09-30 | release | 1.11.6 and 1.11.5, 2026-09-15 |
| Sodium | `mc26.3-0.9.3-alpha.1-fabric` | 2026-09-20 | alpha | `mc26.3-0.9.2-fabric`, release, 2026-09-15 |

### Big change in 26.3: the GPU layer is now `com.mojang.renderpearl`, with an OpenGL and a Vulkan backend
The GPU abstraction is no longer in `com.mojang.blaze3d.systems`.
- `com.mojang.renderpearl.api.device.GpuDevice` and `GpuDeviceLossException`
- `com.mojang.renderpearl.api.commands.CommandEncoder` and `RenderPass`
- `com.mojang.renderpearl.api.pipeline.RenderPipeline` and `CompiledRenderPipeline`
- `com.mojang.renderpearl.api.buffers.GpuBuffer`
- Backends:
  - `com.mojang.renderpearl.backend.opengl.*`: `GlCommandEncoder`, `GlRenderPass`, `GlRenderPipeline`
  - `com.mojang.renderpearl.backend.vulkan.*`: `VulkanBackend`, `VulkanDevice`, `VulkanRenderPipeline` and others
  - `backend.api.GpuDeviceBackend`

### What is still in blaze3d
- `com.mojang.blaze3d.systems.RenderSystem`:
  - `getDevice()`, `getDynamicUniforms().writeTransform(...)`, `getCompiledPipeline(RenderPipeline)`
  - `bindDefaultUniforms(renderPass)`, `setShaderFog(GpuBufferSlice)`, `getModelViewStack()`, `getModelViewMatrixCopy()`
- `com.mojang.blaze3d.vertex`: `BufferBuilder`, `ByteBufferBuilder`, `MeshData`, `DefaultVertexFormat`, `UberGpuBuffer`
- `com.mojang.blaze3d.pipeline`: `RenderTarget`, `MainTarget`, `TextureTarget`, `PipelineCache`
- `com.mojang.blaze3d.framegraph`: `FrameGraphBuilder`, `FramePass`
- Not checked: whether a `Tesselator` class still exists.

### Client renderer classes
- `net.minecraft.client.renderer.RenderPipelines` (for example `RenderPipelines.SKY`), `ShaderManager`, `UniformValue`, `GlobalSettingsUniform`
- `net.minecraft.client.renderer.rendertype`: `RenderType`, `RenderTypes`, `RenderSetup`, `PreparedRenderType`, `LayeringTransform`, `TextureTransform`
- `net.minecraft.client.renderer.oit`: `OitPipelineSet`, `OitRenderPassProvider` (order-independent transparency)

### How the vanilla sky works
- **`net.minecraft.client.renderer.SkyRenderer`:**
  - Constructor: `SkyRenderer(TextureManager, AtlasManager, RenderTarget)`
  - `extractRenderState(ClientLevel, float, Camera, SkyRenderState)` and `render(GpuBufferSlice skyFog, SkyRenderState)`
  - `render` opens `RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Sky", colorTextureView, Optional.empty(), depthTextureView, OptionalDouble.empty())`.
  - Private draw methods: `renderSkyDisc`, `renderSunriseAndSunset`, `renderSunMoonAndStars`, `renderSun`, `renderMoon`, `renderDarkDisc`, `renderEndSky`, `renderEndFlash`.
- **`net.minecraft.client.renderer.state.level.SkyRenderState`:**
  - Fields: `skybox`, `sunAngle`, `moonAngle`, `starAngle`, `starBrightness`, `skyColor`, `sunriseAndSunsetColor`, `moonPhase`, `rainBrightness`, `shouldRenderDarkDisc`, `endFlash*`.
- **There is no `DimensionSpecialEffects` class in 26.3.** Sky type now comes from data.
  - `DimensionType` is a record with a `skybox` field of type `DimensionType.Skybox`: NONE, OVERWORLD, END. The JSON key is `"skybox"`.
  - It also has `cardinal_light` (`CardinalLighting.Type`) and an `EnvironmentAttributeMap`.
  - The sky reads its values from environment attributes in `net.minecraft.world.attribute`: `EnvironmentAttributes`, `EnvironmentAttributeProbe` (via `camera.attributeProbe()`), `EnvironmentAttributeMap` and `EnvironmentAttributeSystem`. The attributes used are `SUN_ANGLE`, `MOON_ANGLE`, `STAR_ANGLE`, `STAR_BRIGHTNESS`, `SKY_COLOR`, `SUNRISE_SUNSET_COLOR` and `MOON_PHASE`.
  - Fog code lives in `net.minecraft.client.renderer.fog.environment.*FogEnvironment`.

### Fabric API custom-sky support
`DimensionRenderingRegistry` is absent from 26.3 Fabric API. I checked the cached `fabric-api 0.161.0+26.3` and its `fabric-rendering-v1 27.0.14+901a437c5d`; neither has it. What `fabric-rendering-v1` does provide:
- `FabricRenderPipeline` (with `Builder` and `Snippet`), `FabricRenderState`, `RenderStateDataKey`, `SubmitRenderPhase` and `SubmitRenderPhases`, `FabricOrderedSubmitNodeCollector`, `InvalidateRenderStateCallback`
- `level.LevelRenderEvents`: `StartMain`, `AfterOpaqueTerrain`, `AfterSolidFeatures`, `BeforeTranslucentTerrain`, `AfterTranslucentTerrain`, `AfterTranslucentFeatures`, `CollectSubmits`, `BeforeBlockOutline`, `BeforeGizmos`, `EndMain`
- `level.LevelExtractionEvents` (`AfterBlockOutlineExtraction`, `EndExtraction`), `LevelRenderContext`, `LevelTerrainRenderContext`
- `hud.HudElementRegistry`, `hud.VanillaHudElements`, `PictureInPictureRendererRegistry`
- `SkyRenderStateMixin`, which makes `SkyRenderState` a `FabricRenderState`. That means custom data can be attached through a `RenderStateDataKey`.

Custom sky therefore has to be done with a Mixin on `SkyRenderer.render` or its private draw methods, or with a custom dimension type using `"skybox": "none"` plus drawing in `LevelRenderEvents.StartMain` / `AfterOpaqueTerrain`.

### Iris API (`net.irisshaders.iris.api.v0.IrisApi`, read from the 26.3 jar with javap)
- **Methods:**
  - `getInstance()`, `getMinorApiRevision()`, `isShaderPackInUse()`, `isRenderingShadowPass()`
  - `getSunPathRotation()`, `isReverseZDuringShaders()`
  - `assignPipeline(com.mojang.renderpearl.api.pipeline.RenderPipeline, IrisProgram)`
  - `assignPipelineShadow(RenderPipeline, IrisShadowProgram)`
  - `registerShadowRenderCallback(IrisShadowRenderCallback)`. The callback signature is `renderShadow(Matrix4f, Matrix4f, double, double, double, float)`.
  - `createTextVertexSink(int, IntFunction<ByteBuffer>)`, `getConfig()`
- **`IrisProgram` values:** BASIC, TEXTURED, TERRAIN, TERRAIN_SOLID, TERRAIN_CUTOUT, TRANSLUCENT, SKY_BASIC, SKY_TEXTURED, ARMOR_GLINT, ENTITIES, ENTITIES_TRANSLUCENT, CLOUDS, BLOCK, BLOCK_TRANSLUCENT, HAND, HAND_TRANSLUCENT, PARTICLES, PARTICLES_TRANSLUCENT, EMISSIVE_ENTITIES, BEACON_BEAM, LINES.
- **Recommended pattern:**
  1. Define your own `RenderPipeline`s, built from a `RenderPipelines` snippet or `FabricRenderPipeline.Builder`.
  2. Draw them through `RenderPass` / `RenderType`.
  3. Call `IrisApi.getInstance().assignPipeline(myPipeline, IrisProgram.SKY_TEXTURED)` (or `SKY_BASIC`, `TRANSLUCENT`, and so on) in client init, but only when the `iris` mod is loaded.
  4. Use `isShaderPackInUse()` to fall back to simpler or HDR-free paths.
- **Iris's own sky Mixins (avoid clashing with these):**
  - `MixinSkyRenderer` injects into `renderSkyDisc`, `renderSun`, `renderMoon` and `renderSunriseAndSunset`, and also into star rendering.
  - Also `MixinLevelRenderer_Sky`, `mixin/sky/MixinClientLevelData_DisableVoidPlane` and `mixin/sky/MixinDimensionSpecialEffects`.
  - Use `@Inject` or MixinExtras rather than `@Overwrite`, and avoid cancelling those methods outright.
  - `MixinDimensionSpecialEffects` targets a class that is missing in 26.3. It is presumably a leftover or a pseudo mixin. This is unverified.
- **Downloaded jar:** `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/iris/iris.jar` (1.11.7+26.3). For a compile dependency, use Modrinth Maven: `maven.modrinth:iris:1.11.7+26.3-fabric` as compileOnly.

## f) Decompiled or remapped 26.3 sources available locally
- **Full decompiled 26.3 sources (Vineflower 1.12.0, Mojang names, NeoForm pipeline):**
  - `C:/Users/Manec/minecraft-claude/data/mcdev-mcp/home/.neoformruntime/intermediate_results/decompile_215c1b7e56e36fa1381dc449b2a38522641cd49d_output.jar` (23.7 MB)
  - Also unpacked at `C:/Users/Manec/minecraft-claude/data/mcdev-mcp/tmp/mcdev-mcp/26.3-vanilla-26.3-sources/` (com, net, assets, data)
  - Raw jars: `.../mcdev-mcp/home/.neoformruntime/artifacts/minecraft_26.3_client.jar` and `minecraft_26.3_server.jar`
  - These sources carry NeoForge `@OnlyIn` annotations from NeoForm; the code is otherwise vanilla.
- **Extracted key files:** `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/src263/`. Contents: `SkyRenderer.java`, `SkyRenderState.java`, `RenderPipeline.java`, `GpuDevice.java`, `RenderSystem.java`, `RenderPipelines.java`, `DimensionType.java`, `LevelRenderer.java`.
- **Loom cache:**
  - `C:/Users/Manec/.gradle/caches/fabric-loom/26.3/`: `minecraft-client.jar`, `minecraft-client-only.jar`, `minecraft-common.jar`, `minecraft-server.jar`, `minecraft-extracted_server.jar`, `mojang_minecraft_info.json`
  - `.../fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.3/` and `minecraft-clientonly-deobf/26.3/`
  - No Loom `genSources` output was found.
- **Gradle dependency cache:**
  - `fabric-api 0.161.0+26.3`, `fabric-loader 0.19.5`, `fabric-loom` plugin
  - Gradle distributions 8.11, 9.2.0, 9.2.1, 9.5.0, 9.6.0 and 9.7.1. The wrapper's 9.7.1 is cached.
- **minecraft-modding-mcp:** decompile cache at `C:/Users/Manec/minecraft-claude/data/minecraft-modding-mcp/cache/decompiled/` with 9 hash directories. Hash `15845ed7…` is Tizio Space Project (it contains `tizio/` and `tsp.mixins.json`). The other 8 were not identified and may include CosmicHorizons and CH-Spheromatic.
- **mcpfabric (in-game MCP bridge mod):** `C:/Users/Manec/minecraft-claude/data/mcpfabric/mods/mcpfabric-0.5.0+26.3.jar`, plus 26.2 and 26.1.2 builds and NeoForge variants.
- **Other MCP data folders:** `mcmodding-mcp` and `modlens` have no 26.x data. I did not load the mcdev and minecraft-modding MCP tools themselves; only their data folders were checked.

## Uncertain or worth flagging
1. **Fabric API version:** use `0.162.0+26.3` (CLI recommendation) or keep the template's `0.160.6+26.3`. The cache has `0.161.0+26.3`, so that one works offline.
2. **Loom version:** the CLI says `1.18-SNAPSHOT` and the template pins `1.18.1`. Keep `1.18.1`.
3. **Sodium:** the newest 26.3 build is an alpha. The stable release is `mc26.3-0.9.2`.
4. **Possible Vulkan backend:** custom pipelines must go only through the `renderpearl` API (RenderPipeline, RenderPass, GpuBuffer). Raw GL calls are not safe. Whether Vulkan is on by default in 26.3 was not verified.
5. **Two separate GPU-buffer classes (not verified):** `com.mojang.blaze3d.vertex.UberGpuBuffer` and `com.mojang.renderpearl.api.buffers.GpuBuffer`. Confirm which one RenderPass expects before writing buffer code.
6. **No 26.3 test instance in Prism.** Voxyris is 26.2, so its mods and shaders are reference only. Mod jars there are mostly `+26.2`.

Sources: [Iris on Modrinth](https://modrinth.com/shader/iris), [irisshaders.dev](https://irisshaders.dev/), Modrinth API (`api.modrinth.com/v2/project/{iris,sodium}/version`)