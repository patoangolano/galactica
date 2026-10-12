# Minecraft 26.3 / Fabric: sky mixins and Iris compatibility cheat-sheet

**Sources checked**
- **Minecraft.** The decompiled sources folder `26.3-vanilla-26.3-sources` is incomplete. It has only 288 `.java` files and no `SkyRenderer` or `LevelRenderer`. I decompiled from `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/minecraft-clientonly-deobf-26.3.jar` with Vineflower 1.10.1 and javap from JDK 25 (`C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot`). JDK 21's javap cannot read these classes.
- **Iris.** The scratchpad `iris/iris.jar` has the same MD5 as `~/.gradle/caches/modules-2/files-2.1/maven.modrinth/iris/1.11.7+26.3-fabric/.../iris-1.11.7+26.3-fabric.jar`. Its version is `1.11.7+mc26.3`, and it depends on `sodium 0.9.x`.
- **Decompiled output.** It is in `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/mixdec/out/minecraft/client/renderer/{SkyRenderer,LevelRenderer,RenderPipelines}.java`.

**Package changes in 26.3**
- GPU API classes are now `com.mojang.renderpearl.api.*`, not `com.mojang.blaze3d.*`:
  - `com.mojang.renderpearl.api.buffers.GpuBufferSlice`
  - `com.mojang.renderpearl.api.commands.RenderPass`
  - `com.mojang.renderpearl.api.pipeline.RenderPipeline`
  - `com.mojang.renderpearl.api.textures.GpuTextureView`
- These stay in blaze3d: `RenderSystem`, `PoseStack`, `DefaultVertexFormat` and `FrameGraphBuilder`.
- `ResourceLocation` is now `net.minecraft.resources.Identifier`. Confirmed: `Identifier.withDefaultNamespace("sun")`.

---

## 1. Vanilla sky path

### `net.minecraft.client.renderer.SkyRenderer` (`implements AutoCloseable`)
```java
public SkyRenderer(final TextureManager textureManager, final AtlasManager atlasManager, final RenderTarget renderTarget)
public void extractRenderState(final ClientLevel level, final float partialTicks, final Camera camera, final SkyRenderState state)
public void render(final GpuBufferSlice skyFog, final SkyRenderState state)
// descriptor: (Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V
private void renderSkyDisc(RenderPass, Vector3fc)
private void renderDarkDisc(RenderPass)
private void renderSunMoonAndStars(RenderPass, PoseStack, float sunAngle, float moonAngle, float starAngle, MoonPhase, float rainBrightness, float starBrightness)
private void renderSun(RenderPass, float rainBrightness, PoseStack)
private void renderMoon(RenderPass, MoonPhase, float rainBrightness, PoseStack)
private void renderEndFlash(RenderPass, PoseStack, float intensity, float xAngle, float yAngle)
private void renderStars(RenderPass, float starBrightness, PoseStack)
private void renderSunriseAndSunset(RenderPass, PoseStack, float sunAngle, Vector4fc)
private void renderEndSky(RenderPass)
private void drawCelestialBody(Supplier<String>, RenderPass, GpuBufferSlice, GpuBuffer indexBuf, GpuBuffer vertexBuf, int baseVertex)
```

**What `render()` does (verbatim logic)**
1. It calls `RenderSystem.setShaderFog(skyFog)`.
2. It opens a pass on the main target's color and depth: `createRenderPass(() -> "Sky", colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty())`, then `RenderSystem.bindDefaultUniforms(pass)`.
3. If `state.skybox == Skybox.END`, it calls `renderEndSky`, then `renderEndFlash` if `endFlashIntensity > 1e-5`, and returns.
4. Otherwise it calls `renderSkyDisc`, then `renderSunriseAndSunset`, then `renderSunMoonAndStars`, and `renderDarkDisc` if `shouldRenderDarkDisc`.

**Pipelines used by each part**

| Part | Pipeline |
|---|---|
| Sky disc, dark disc | `RenderPipelines.SKY` |
| Sun, moon, end flash | `RenderPipelines.CELESTIAL` (`POSITION_TEX`, blend `OVERLAY`) |
| Stars | `RenderPipelines.STARS` |
| Sunrise / sunset | `RenderPipelines.SUNRISE_SUNSET` |
| End sky | `RenderPipelines.END_SKY` (`POSITION_TEX_COLOR`, blend `TRANSLUCENT`, location `pipeline/end_sky`) |

Constants: sky disc radius 512, star count 1500, sun size 30 at height 100, moon size 20 at height 100.

### `net.minecraft.client.renderer.state.level.SkyRenderState`
```java
public Skybox skybox = Skybox.NONE;          // net.minecraft.world.level.dimension.DimensionType.Skybox
public boolean shouldRenderDarkDisc;
public float sunAngle, moonAngle, starAngle;  // radians
public float rainBrightness, starBrightness;
public Vector4fc sunriseAndSunsetColor;
public MoonPhase moonPhase = MoonPhase.FULL_MOON;
public Vector3fc skyColor;
public float endFlashIntensity, endFlashXAngle, endFlashYAngle;
public void reset() { this.skybox = Skybox.NONE; }
```
- `DimensionType$Skybox` has the values `NONE`, `OVERWORLD` and `END`, plus `Codec<Skybox> CODEC`. It implements `StringRepresentable`.
- `extractRenderState` sets `state.skybox = level.dimensionType().skybox()`. For `END` it fills only the endFlash fields. For `OVERWORLD` it fills the sun, moon and star fields from `camera.attributeProbe()` (`EnvironmentAttributes.SUN_ANGLE` and so on), multiplied by π/180.
- The caller is `net.minecraft.client.renderer.extract.LevelExtractor.extract(DeltaTracker, Camera, float)`. It calls `levelRenderer.skyRenderer()` and skips if that is null, then calls `skyRenderer.extractRenderState(level, partialTick, camera, levelRenderState.skyRenderState)`.

### `net.minecraft.client.renderer.LevelRenderer`
```java
public void render(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState,
                   GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired)
private void addSkyPass(final FrameGraphBuilder frame, final CameraRenderState cameraState, final GpuBufferSlice skyFog)
private void lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)   // body: this.skyRenderer.render(skyFog, state)
public SkyRenderer skyRenderer()
```
`render(...)` calls `if (shouldRenderSky) this.addSkyPass(frame, cameraState, terrainFog);`.

Body of `addSkyPass`:
```java
FogType fogType = cameraState.fogType;
if (fogType != FogType.POWDER_SNOW && fogType != FogType.LAVA && !cameraState.entityRenderState.doesMobEffectBlockSky) {
   if (this.levelRenderState.shouldResetSkyRenderer || this.skyRenderer == null) { ...new SkyRenderer(...) }
   SkyRenderState state = this.levelRenderState.skyRenderState;
   if (state.skybox != Skybox.NONE) {
      FramePass pass = frame.addPass("sky");
      this.targets.main = pass.readsAndWrites(this.targets.main);
      pass.executes(() -> this.skyRenderer.render(skyFog, state));
   }
}
```

**Answers to question 1**
- **Is the sky pass skipped when the skybox is `NONE`?** Yes. No frame pass is added, and `SkyRenderer.render` is never called.
- **Is `SkyRenderer.render` called for `END`?** Yes. `render` runs, takes the END branch, draws `END_SKY` plus the end flash, and returns early.
- **Consequence for a custom space dimension.** It needs `skybox` set to `"overworld"` or `"end"` in the dimension_type JSON, or a HEAD hook on `SkyRenderer.render` never fires. Alternatively, hook `LevelRenderer.addSkyPass` or `render` instead.

### Mixin: HEAD-cancel of `SkyRenderer.render`
```java
// src/client/java/com/example/galactica/mixin/client/SkyRendererMixin.java
package com.example.galactica.mixin.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void galactica$replaceSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
        if (com.example.galactica.client.SpaceSky.isActive()) {
            com.example.galactica.client.SpaceSky.render(skyFog, state); // own RenderPass on main target
            ci.cancel();
        }
    }
}
```
A full method descriptor also works: `method = "render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V"`.

### Client mixins JSON
The format matches the local smoke-test project `smoketest/fabric263/smoke-fabric/src/client/resources/smokefabric.client.mixins.json`, which builds with no refmap.
```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.example.galactica.mixin.client",
  "compatibilityLevel": "JAVA_25",
  "client": [
    "SkyRendererMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  },
  "overwrites": {
    "requireAnnotations": true
  }
}
```
- `JAVA_25` exists in `MixinEnvironment$CompatibilityLevel` in sponge-mixin `0.17.4+mixin.0.8.7`, which is the version fabric-loader 0.19.5 uses.
- Register the file in `fabric.mod.json` with `"mixins": [{"config":"galactica.client.mixins.json","environment":"client"}]`.

---

## 2. Iris 1.11.7+mc26.3 sky mixins

The config is `mixins.iris.json` with package `net.irisshaders.iris.mixin`, plugin `IrisMixinPlugin`, `"mixinextras": {"minVersion": "0.5.2"}` and `"refmap": "iris.refmap.json"`. The refmap file is not in the jar.

### `net.irisshaders.iris.mixin.MixinSkyRenderer` (`@Mixin(SkyRenderer.class)`)

| Iris handler | Target method | At | Cancellable | Effect |
|---|---|---|---|---|
| `iris$renderSky$beginNormalSky(RenderPass, Vector3fc, CI)` | `renderSkyDisc` | HEAD | no | `setPhase(WorldRenderingPhase.SKY)` |
| `iris$beforeDrawSun(RenderPass, float, PoseStack, CI)` | `renderSun` | HEAD | **yes** | cancels if the pack disables the sun |
| `iris$beforeDrawMoon(RenderPass, MoonPhase, float, PoseStack, CI)` | `renderMoon` | HEAD | **yes** | cancels if the pack disables the moon |
| `iris$setSunRenderStage` | `renderSun` | HEAD | no | phase `SUN` |
| `iris$setMoonRenderStage` | `renderMoon` | HEAD | no | phase `MOON` |
| `iris$setSunsetRenderStage` | `renderSunriseAndSunset` | HEAD | no | phase `SUNSET` |
| `iris$setStarRenderStage` | `renderStars` | HEAD | no | phase `STARS` |
| `iris$setVoidRenderStage(CI)` | `renderDarkDisc` | HEAD | no | phase `VOID` |
| `iris$renderSky$tiltSun(RenderPass, PoseStack, float×3, MoonPhase, float×2, CI)` | `renderSunMoonAndStars` | INVOKE `PoseStack.rotateDegrees(Axis;F)V`, shift AFTER | no | applies `Axis.ZP` sunPathRotation |

**Iris does not inject into `SkyRenderer.render` itself.**

### `net.irisshaders.iris.mixin.MixinLevelRenderer` (`@Mixin(LevelRenderer.class)`)
- `iris$beginSky(CI)`, a static handler: `method={"lambda$addSkyPass$0","lambda$addSkyPass$8"}`, HEAD, `require=1`. It calls `pipeline.setPhase(...)`, which begins the sky phase.
- `iris$endSky(CI)`, a static handler: same lambdas, `RETURN`, `require=1`. It calls `setPhase(...)` to end the sky phase.
- Other hooks:
  - `iris$setupPipeline`: `render` at HEAD.
  - `iris$beginLevelRender`: `render` at INVOKE `FramePass.executes(Runnable)V`, ordinal 0, shift AFTER.
  - `iris$endLevelRender`: `render` at INVOKE `Matrix4fStack.popMatrix()`.
  - `iris$renderTerrainShadows`: INVOKE `addMainPass`, `require=0`.
  - `iris$beginTerrainLayer` and `skipRenderChunks`: in `executeSolid`.
  - Weather and border hooks: in `executeClassicTransparency`.
  - `iris$beginTranslucents`: a WrapOperation in `lambda$addMainPass$0`.
- `iris$alwaysRenderSky(boolean)` exists but carries no visible injector annotation. Treat it as inert (UNCONFIRMED how it is wired).

### `net.irisshaders.iris.mixin.MixinLevelRenderer_Sky`
- `preRenderSky(CI)` is static: `method={"lambda$addSkyPass$0","lambda$addSkyPass$8"}`, HEAD, `cancellable=true`.
- It acts only when `Iris.getCurrentPack().isEmpty()`, that is, when no shaderpack is loaded. In that case it cancels when the camera fluid is not `FogType.NONE`, when `doesMobEffectBlockSky` is set, or when `BossHealthOverlay.shouldCreateWorldFog()` is true.

`MixinLevelRenderer_SkipRendering` targets `LevelExtractor.extractVisibleEntities` with a WrapOperation on `ClientLevel.entitiesForRendering()`. It is not sky-related.

### Verdict: is a HEAD-cancel of `SkyRenderer.render` compatible with Iris?
- **Yes.** Iris hooks only the private sub-methods of `SkyRenderer` and the `addSkyPass` lambda in `LevelRenderer`.
- Cancelling at the HEAD of `render` skips all of Iris's `SkyRenderer` hooks: the SUN, MOON, STARS and VOID phases and the sun tilt. That is harmless.
- The lambda-level `iris$beginSky` and `iris$endSky` still run around our replacement draw. Our draws therefore happen inside Iris's sky phase.
- Our replacement must set the phase to SKY itself if it wants `gbuffers_skybasic` semantics. Otherwise the phase is whatever `beginSky` set; the exact phase value is UNCONFIRMED.
- The END branch also runs inside the same lambda, so the same reasoning applies there.

**Sodium:** Iris's `fabric.mod.json` sets `custom."sodium:options"."mixin.features.render.world.sky": false`, which disables Sodium's sky mixin when Iris is installed. Whether Sodium 0.9 alone touches the sky is UNCONFIRMED.

### Pipeline override by identity: `net.irisshaders.iris.pipeline.IrisPipelines`
```java
private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMap;        // Object2ObjectArrayMap
private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMapShadow;
public static ShaderKey getPipeline(IrisRenderingPipeline, RenderPipeline)   // map.getOrDefault(pipeline, FAKE_FUNCTION).apply(...)
public static void assignPipeline(RenderPipeline, ShaderKey)   // throws IllegalStateException if already present
public static void assignPipelineShadow(RenderPipeline, ShaderKey)
public static void copyPipeline(RenderPipeline, RenderPipeline)
```
- `RenderPipeline` does not override `equals` or `hashCode`, so lookup is **by object identity**.
- Vanilla sky mappings from the static init:

| Vanilla pipeline | Iris ShaderKey | Vertex format |
|---|---|---|
| `SKY` | `SKY_BASIC` | `POSITION` |
| `STARS` | `SKY_BASIC` | `POSITION` |
| `SUNRISE_SUNSET` | `SKY_BASIC_COLOR` | `POSITION_COLOR` |
| `CELESTIAL` | `SKY_TEXTURED` | `POSITION_TEX` |
| `END_SKY` | `SKY_TEXTURED` | `POSITION_TEX` |

  A `SKY_TEXTURED_COLOR` key (`POSITION_TEX_COLOR`) also exists.
- The hook point is `net.irisshaders.iris.mixin.MixinShaderManager_Overrides`, `@Mixin(RenderSystem.class)`. It injects at `getCompiledPipelineNullable` RETURN with `cancellable=true`. When a pack is active and `shouldOverrideShaders()` is true, it calls `IrisPipelines.getPipeline`. A non-null result swaps the compiled pipeline for a `GlRenderPipeline` built from the pack's `GlProgram`; a null result keeps vanilla.
- **Answer:** another mod that draws with the exact static objects `RenderPipelines.END_SKY`, `CELESTIAL`, `SKY` or `STARS` through `RenderSystem.getCompiledPipeline(...)` automatically gets the matching Iris program, including `gbuffers_skytextured` for `CELESTIAL` and `END_SKY`.
- A **new** `RenderPipeline` the mod builds itself, even a copy of `END_SKY`'s builder, gets no mapping. The fake function returns null (UNCONFIRMED but consistent with the `ifnonnull` fallback), so Iris draws it with the vanilla shader. Use `IrisApi.assignPipeline` for those.

---

## 3. IrisApi
Exact signatures from javap of `iris.jar`:
```java
public interface net.irisshaders.iris.api.v0.IrisApi {
  static IrisApi getInstance();
  int getMinorApiRevision();
  boolean isShaderPackInUse();
  boolean isRenderingShadowPass();
  Object openMainIrisScreenObj(Object);
  String getMainScreenLanguageKey();
  IrisApiConfig getConfig();
  IrisTextVertexSink createTextVertexSink(int, java.util.function.IntFunction<java.nio.ByteBuffer>);
  float getSunPathRotation();
  void assignPipeline(com.mojang.renderpearl.api.pipeline.RenderPipeline, net.irisshaders.iris.api.v0.IrisProgram);
  void assignPipelineShadow(com.mojang.renderpearl.api.pipeline.RenderPipeline, net.irisshaders.iris.api.v0.IrisShadowProgram);
  void registerShadowRenderCallback(net.irisshaders.iris.api.v0.IrisShadowRenderCallback);
  boolean isReverseZDuringShaders();
}
```
`public enum net.irisshaders.iris.api.v0.IrisProgram` has these values: `BASIC`, `TEXTURED`, `TERRAIN`, `TERRAIN_SOLID`, `TERRAIN_CUTOUT`, `TRANSLUCENT`, `SKY_BASIC`, `SKY_TEXTURED`, `ARMOR_GLINT`, `ENTITIES`, `ENTITIES_TRANSLUCENT`, `CLOUDS`, `BLOCK`, `BLOCK_TRANSLUCENT`, `HAND`, `HAND_TRANSLUCENT`, `PARTICLES`, `PARTICLES_TRANSLUCENT`, `EMISSIVE_ENTITIES`, `BEACON_BEAM`, `LINES`.

**How the implementation behaves** (`IrisApiV0Impl.assignPipeline`)
- It calls `IrisPipelines.assignPipeline(p, ShaderKey.findBestMatch(p, ProgramId.fromAPI(program)))`.
- `findBestMatch` matches on the ProgramId plus the pipeline's `getVertexFormatBinding(0)` and the `ALPHA_CUTOUT` define. If nothing matches it logs a warning and falls back to a key with the same ProgramId.
- The vertex format should therefore match one of the formats in the table in section 2. For a textured custom sky that means `POSITION_TEX`, or `POSITION_TEX_COLOR` (which matches `SKY_TEXTURED_COLOR`).
- Assign each pipeline exactly once. A second assignment throws `IllegalStateException`.

**Guard: keep every Iris reference in a separate class**
```java
// client init
if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
    com.example.galactica.client.compat.IrisCompat.register();
}

// com/example/galactica/client/compat/IrisCompat.java  (only class touching Iris)
public final class IrisCompat {
    public static void register() {
        net.irisshaders.iris.api.v0.IrisApi api = net.irisshaders.iris.api.v0.IrisApi.getInstance();
        api.assignPipeline(GalacticaPipelines.STARFIELD, net.irisshaders.iris.api.v0.IrisProgram.SKY_TEXTURED);
        api.assignPipeline(GalacticaPipelines.NEBULA,    net.irisshaders.iris.api.v0.IrisProgram.SKY_TEXTURED);
    }
    public static boolean packActive() { return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse(); }
}
```
Gradle setup: `modCompileOnly "maven.modrinth:iris:1.11.7+26.3-fabric"`. That artifact is already in the gradle cache. With `fabric.mod.json` → `"suggests": {"iris": "*"}`.

---

## 4. Fabric Loader 0.19.5: MixinExtras and refmaps
- **MixinExtras is bundled.** `fabric-loader-0.19.5.jar` contains `META-INF/jars/mixinextras-fabric-0.5.5.jar` (727,864 bytes).
- **Installer libraries.** `fabric-installer.json` lists `net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7`, `io.github.llamalad7:mixinextras-fabric:0.5.5` and ASM `9.10.1`.
- **Compile classpath.** `mixinextras-fabric-0.5.5.jar` is in the gradle cache, so `com.llamalad7.mixinextras.*` is available: `@WrapOperation`, `Operation<T>`, `@ModifyExpressionValue`, `@Local`, `LocalRef`. Iris itself uses `@WrapOperation`, `Operation` and `LocalRef` against 26.3. No `include` or initialization call is needed.
- **Refmap: none needed.** 26.x ships with Mojang names, so the game's names are the names you write in source.
  - The local 26.3 smoke-test build produces no refmap and has no `"refmap"` key in its mixin JSON.
  - Iris declares `"refmap": "iris.refmap.json"` but the jar contains no such file, and it still loads.
  - Leave `"refmap"` out.
- **Mixins into lambdas.** Use the javac synthetic name, for example `"lambda$addSkyPass$0"`, which javap confirms is the only `addSkyPass` lambda in 26.3. Iris lists both `"lambda$addSkyPass$0"` and `"lambda$addSkyPass$8"`; the `$8` name is not in this build's class.