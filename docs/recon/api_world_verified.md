# Minecraft 26.3 / Fabric: sky mixins and Iris compatibility cheat-sheet (verified)

Every claim below was checked against the real files:

- **Minecraft 26.3:** javap from JDK 25 on `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/minecraft-clientonly-deobf-26.3.jar` and `minecraft-common-deobf/26.3`.
- **Iris:** Vineflower 1.10.1 and javap on `iris.jar`, extracted to `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/check/irisx`. Decompiled output is in `.../scratchpad/check/irisdec`.
- **Mixin and Loom:** sponge-mixin `0.17.4+mixin.0.8.7`, fabric-loader `0.19.5` and fabric-loom `1.18.1/1.18.2` jars.

Each item is marked **CONFIRMED**, **FIXED** (with the fix) or **UNCONFIRMED**.

**Sources checked**
- **Decompiled sources folder (FIXED).** `26.3-vanilla-26.3-sources` now has **667** `.java` files, not 288. It still has no `SkyRenderer`, `LevelRenderer` or `SkyRenderState`.
- **Decompiling from the jar (CONFIRMED).** Use the `minecraft-clientonly-deobf-26.3.jar` path with Vineflower 1.10.1 and JDK 25's javap.
- **Class version (CONFIRMED).** The classes are major version **69** (Java 25), so JDK 21's javap (max version 65) cannot read them. JDK 21 is not installed here; only JDK 17 and JDK 25 are.
- **Iris jar identity (CONFIRMED).** Both jars have MD5 `6e390346ffb4f04e670d7f0ffc04887f`. The version is `1.11.7+mc26.3`, and it depends on `"sodium":["0.9.x"]`.
- **Decompiled Minecraft output (CONFIRMED).** It is in `scratchpad/mixdec/out/minecraft/client/renderer/{SkyRenderer,LevelRenderer,RenderPipelines}.java` and `state/level/SkyRenderState.java`. It matches javap of the jar.

**Package changes in 26.3**
- **GPU API classes are `com.mojang.renderpearl.api.*` (CONFIRMED):** `buffers.GpuBufferSlice` (a record), `commands.RenderPass`, `pipeline.RenderPipeline` and `textures.GpuTextureView`.
  - `GpuBuffer` is `com.mojang.renderpearl.api.buffers.GpuBuffer`.
  - `VertexFormat` is `com.mojang.renderpearl.api.vertex.VertexFormat`.
  - `PrimitiveTopology` is `com.mojang.renderpearl.api.pipeline.PrimitiveTopology`.
- **Still in blaze3d (CONFIRMED):**
  - `com.mojang.blaze3d.systems.RenderSystem`
  - `com.mojang.blaze3d.vertex.PoseStack`
  - `com.mojang.blaze3d.vertex.DefaultVertexFormat`
  - `com.mojang.blaze3d.framegraph.FrameGraphBuilder`
  - `com.mojang.blaze3d.pipeline.RenderTarget`
- **`Identifier` (CONFIRMED).** It is `net.minecraft.resources.Identifier`. `public static Identifier withDefaultNamespace(String)` exists, and `SkyRenderer` uses `Identifier.withDefaultNamespace("sun")`.

---

## 1. Vanilla sky path

### `net.minecraft.client.renderer.SkyRenderer` (`implements AutoCloseable`) — CONFIRMED (javap)
```java
public SkyRenderer(TextureManager, AtlasManager /* net.minecraft.client.resources.model.sprite */, RenderTarget)
public void extractRenderState(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state)
public void render(GpuBufferSlice skyFog, SkyRenderState state)
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
private Matrix4f applyCelestialBodyTransform(PoseStack, float height, float scale)   // added
```
The only lambda in `render` is the static `lambda$render$0()`, which is the `"Sky"` label.

**What `render()` does — CONFIRMED (verbatim)**
1. It calls `RenderSystem.setShaderFog(skyFog)`.
   - If you HEAD-cancel `render`, this call is skipped. Your replacement must call `RenderSystem.setShaderFog(skyFog)` itself. (Added.)
2. It runs `try (RenderPass p = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Sky", colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty()))`, then `RenderSystem.bindDefaultUniforms(p)`.
   - The color and depth textures come from the `renderTarget` passed to the constructor. That is `gameRenderer.mainRenderTarget()`.
3. If `state.skybox == Skybox.END`, it calls `renderEndSky`, then `renderEndFlash(new PoseStack(), ...)` if `endFlashIntensity > 1.0E-5F`, and returns.
4. Otherwise it calls `renderSkyDisc`, `renderSunriseAndSunset`, `renderSunMoonAndStars`, and `renderDarkDisc` if `state.shouldRenderDarkDisc`.

**Pipelines used by each part — FIXED (formats and blends filled in from `RenderPipelines`)**

| Part | Pipeline | Vertex format | Blend | Topology |
|---|---|---|---|---|
| Sky disc, dark disc | `RenderPipelines.SKY` (`pipeline/sky`, shader `core/sky`) | `POSITION` | none (`ColorTargetState.DEFAULT`) | TRIANGLE_FAN |
| Sun, moon, end flash | `RenderPipelines.CELESTIAL` (`pipeline/celestial`, `core/position_tex`) | `POSITION_TEX` | `OVERLAY` | QUADS |
| Stars | `RenderPipelines.STARS` (`pipeline/stars`, `core/stars`) | `POSITION` | `OVERLAY` | QUADS |
| Sunrise / sunset | `RenderPipelines.SUNRISE_SUNSET` (`pipeline/sunrise_sunset`, `core/position_color`) | `POSITION_COLOR` | `TRANSLUCENT` | TRIANGLE_FAN |
| End sky | `RenderPipelines.END_SKY` (`pipeline/end_sky`, `core/position_tex_color`) | `POSITION_TEX_COLOR` | `TRANSLUCENT` | QUADS |

**Constants — CONFIRMED.** Sky disc radius 512, 10 sky vertices, star count 1500, sun size 30 at height 100, moon size 20 at height 100, end flash at height 100 with scale 60, 16 sunrise steps, 6 end sky quads.

### `net.minecraft.client.renderer.state.level.SkyRenderState` — CONFIRMED
```java
public Skybox skybox = Skybox.NONE;          // net.minecraft.world.level.dimension.DimensionType$Skybox
public boolean shouldRenderDarkDisc;
public float sunAngle, moonAngle, starAngle;  // radians
public float rainBrightness, starBrightness;
public Vector4fc sunriseAndSunsetColor;
public MoonPhase moonPhase = MoonPhase.FULL_MOON;
public Vector3fc skyColor;
public float endFlashIntensity, endFlashXAngle, endFlashYAngle;
public void reset() { this.skybox = Skybox.NONE; }
```

- **`DimensionType$Skybox` (CONFIRMED).** It is in the **common** jar, not the client jar. It is `public final enum ... implements StringRepresentable` with the values `NONE("none")`, `OVERWORLD("overworld")` and `END("end")`, plus `Codec<Skybox> CODEC`.
  - The dimension_type JSON key is `"skybox"`; the `ldc "skybox"` string appears in `DimensionType`'s codec.
- **`extractRenderState` (CONFIRMED).** It sets `state.skybox = level.dimensionType().skybox()`.
  - If the skybox is `NONE`, it fills nothing else.
  - For `END`, it fills only the endFlash fields, and only when `level.endFlashState() != null`.
  - Otherwise it reads `camera.attributeProbe()`: `SUN_ANGLE`, `MOON_ANGLE` and `STAR_ANGLE` multiplied by π/180, then `STAR_BRIGHTNESS`, `SUNRISE_SUNSET_COLOR`, `MOON_PHASE` and `SKY_COLOR`. It also sets `rainBrightness = 1 - rainLevel` and `shouldRenderDarkDisc`.
- **Caller (CONFIRMED).** `net.minecraft.client.renderer.extract.LevelExtractor.extract(DeltaTracker, Camera, float)` calls `LevelRenderer.skyRenderer()`, skips on `ifnull`, then calls `SkyRenderer.extractRenderState(...)`.

### `net.minecraft.client.renderer.LevelRenderer` — CONFIRMED, with one fix
```java
public void render(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState,
                   GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired)
// (Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V
private void addSkyPass(FrameGraphBuilder frame, CameraRenderState cameraState, GpuBufferSlice skyFog)
private void lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)   // FIXED: instance method (not static); body: this.skyRenderer.render(skyFog, state)
public SkyRenderer skyRenderer()
```
- `render(...)` contains `if (shouldRenderSky) this.addSkyPass(frame, cameraState, terrainFog);` (CONFIRMED).
- **The body of `addSkyPass` matches the original cheat-sheet exactly (CONFIRMED).**
  - It checks `fogType != POWDER_SNOW && != LAVA && !doesMobEffectBlockSky`.
  - It rebuilds the `SkyRenderer` when `shouldResetSkyRenderer` is set or the renderer is null.
  - Only when `state.skybox != Skybox.NONE` does it call `frame.addPass("sky")`, `readsAndWrites(targets.main)` and `executes(() -> skyRenderer.render(skyFog, state))`.

**Answers to question 1 — CONFIRMED**
- **`NONE`.** No frame pass is added, and `SkyRenderer.render` is never called.
- **`END`.** `render` runs, draws `END_SKY` plus the end flash, and returns early.
- **Custom space dimension.** Set `"skybox": "overworld"` or `"end"` in the dimension_type JSON. Otherwise a HEAD hook on `SkyRenderer.render` never fires. The alternative is to hook `LevelRenderer.addSkyPass` or `render`.
- **Powder snow, lava and blindness (added).** Vanilla also skips the whole sky pass under `POWDER_SNOW` or `LAVA` fog, or when `doesMobEffectBlockSky` is set, even with a non-NONE skybox.

### Mixin: HEAD-cancel of `SkyRenderer.render` — CONFIRMED (signature and imports match javap)
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
            // must call RenderSystem.setShaderFog(skyFog) itself; open own RenderPass on main target color+depth
            com.example.galactica.client.SpaceSky.render(skyFog, state);
            ci.cancel();
        }
    }
}
```
The full descriptor `render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V` is also CONFIRMED.

### Client mixins JSON — CONFIRMED, with a note
- **Smoke-test difference (FIXED).** The smoke-test file `smokefabric.client.mixins.json` has **no** `minVersion` key. Its keys are `required`, `package`, `compatibilityLevel: JAVA_25`, `client`, `injectors.defaultRequire: 1` and `overwrites.requireAnnotations: true`.
- Adding `"minVersion": "0.8"` is harmless, and Iris uses it.
- The smoke jar `build/libs/smokefabric-1.0.0.jar` contains no refmap (CONFIRMED).
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
- **`JAVA_25` (CONFIRMED).** It exists in `MixinEnvironment$CompatibilityLevel` in sponge-mixin `0.17.4+mixin.0.8.7`, which fabric-loader 0.19.5's `fabric-installer.json` lists.
- **Registration (CONFIRMED).** Use `"mixins": [{"config":"galactica.client.mixins.json","environment":"client"}]`. This is the standard fabric.mod.json format; the smoke-test uses the same split client source set.

---

## 2. Iris 1.11.7+mc26.3 sky mixins

- **Mixin config (CONFIRMED).** `mixins.iris.json` has package `net.irisshaders.iris.mixin`, plugin `net.irisshaders.iris.mixin.IrisMixinPlugin`, `"mixinextras": {"minVersion": "0.5.2"}` and `"refmap": "iris.refmap.json"`. No refmap file is in the jar.
- **Compatibility level (added).** Iris declares `"compatibilityLevel": "JAVA_8"`.

### `net.irisshaders.iris.mixin.MixinSkyRenderer` (`@Mixin(SkyRenderer.class)`) — CONFIRMED, with one fix

| Iris handler | Target method | At | Cancellable | Effect |
|---|---|---|---|---|
| `iris$renderSky$beginNormalSky(RenderPass, Vector3fc, CI)` | `renderSkyDisc` | HEAD | no | `setPhase(SKY)` |
| `iris$beforeDrawSun(RenderPass, float, PoseStack, CI)` | `renderSun` | HEAD | **yes** | cancels if `!pipeline.shouldRenderSun()` |
| `iris$beforeDrawMoon(RenderPass, MoonPhase, float, PoseStack, CI)` | `renderMoon` | HEAD | **yes** | cancels if `!pipeline.shouldRenderMoon()` |
| `iris$setSunRenderStage` | `renderSun` | HEAD | no | phase `SUN` |
| `iris$setMoonRenderStage` | `renderMoon` | HEAD | no | phase `MOON` |
| `iris$setSunsetRenderStage` | `renderSunriseAndSunset` | HEAD | no | phase `SUNSET` |
| `iris$setStarRenderStage` | `renderStars` | HEAD | no | phase `STARS` |
| `iris$setVoidRenderStage(CI)` | `renderDarkDisc` | HEAD | no | phase `VOID` |
| `iris$renderSky$tiltSun(RenderPass, PoseStack, float×3, MoonPhase, float×2, CI)` | `renderSunMoonAndStars` | INVOKE `PoseStack.rotateDegrees(Lcom/mojang/math/Axis;F)V`, **ordinal 0** (FIXED: ordinal was missing), shift AFTER | no | `poseStack.rotateDegrees(Axis.ZP, sunPathRotation)` |

**Missing Iris mixin: `net.irisshaders.iris.mixin.sky.MixinDimensionSpecialEffects` (FIXED — it was not listed)**
- Despite its name, it is `@Mixin(SkyRenderer.class)` and is registered in `mixins.iris.json` as `"sky.MixinDimensionSpecialEffects"`.
- Its handler `iris$getSunriseColor(RenderPass, PoseStack, float, Vector4fc, CI)` injects at the HEAD of `renderSunriseAndSunset` with `cancellable=true`.
- It cancels when `doesMobEffectBlockSky` is set or the camera fluid is not `FogType.NONE`.

**Related mixin (added): `sky.MixinClientLevelData_DisableVoidPlane`**
- It targets `ClientLevelData.getHorizonHeight` at HEAD, cancellable.
- It returns `-∞` when the camera is in a fluid, which affects `shouldRenderDarkDisc`.

**Iris does not inject into `SkyRenderer.render` itself (CONFIRMED).** Only `MixinSkyRenderer` and `sky.MixinDimensionSpecialEffects` reference `SkyRenderer`.

### `net.irisshaders.iris.mixin.MixinLevelRenderer` (`@Mixin(LevelRenderer.class)`)
- **`iris$beginSky(CI)` (FIXED: the phase is now known).** It is `private static`, with `method={"lambda$addSkyPass$0","lambda$addSkyPass$8"}`, HEAD and `require=1`. It sets `WorldRenderingPhase.CUSTOM_SKY` through `getPipeline().ifPresent(...)`.
- **`iris$endSky(CI)` (FIXED: the phase is now known).** It is `private static` and targets the same lambdas at `RETURN` with `require=1`. It sets `WorldRenderingPhase.NONE`.
- **Static handler on an instance lambda (added).** The handlers are static while `lambda$addSkyPass$0` is an instance method. Mixin allows this because `CallbackInjector` calls `checkTargetModifiers(target, false)`. Only a non-static handler on a static target is rejected.
- **Other hooks (CONFIRMED):**
  - `iris$setupPipeline`: `render` at HEAD.
  - `iris$beginLevelRender`: `render` at INVOKE `Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V`, ordinal 0, shift AFTER.
  - `iris$endLevelRender`: `render` at INVOKE `Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;`.
  - `iris$renderTerrainShadows`: `render` at INVOKE `addMainPass(...)`, `require=0`, plus `@Group(name="that", min=1, max=1)`.
  - In `executeSolid`, both hooks target `ChunkSectionsToRender.renderGroup(...)`. `iris$beginTerrainLayer` is a `@WrapOperation` and `skipRenderChunks` is a `@WrapWithCondition` (FIXED: the kinds were not given).
  - In `executeClassicTransparency`: `iris$beginWeather` at HEAD, `iris$beginWorldBorder` at INVOKE `WorldBorderRenderer.render`, and `iris$endWeather` at RETURN.
  - `iris$beginTranslucents`: a `@WrapOperation` in `lambda$addMainPass$0` on `executeClassicTransparency(...)`.
  - `iris$beginBlockOutline`: a `@ModifyArg` in `submitBlockOutline`. (Added.)
- **`iris$alwaysRenderSky(boolean)` is inert (CONFIRMED).** It is a private method with no annotations in javap -v, and its body is `return false`. Mixin merges it as a plain method that is never called.

### `net.irisshaders.iris.mixin.MixinLevelRenderer_Sky` — CONFIRMED
- `private static preRenderSky(CI)` targets `method={"lambda$addSkyPass$0","lambda$addSkyPass$8"}` at HEAD with `require=1` and `cancellable=true`.
- It acts only when `Iris.getCurrentPack().isEmpty()`, that is, when no shaderpack is loaded. In that case it cancels if any of these holds:
  - `camera.getFluidInCamera() != FogType.NONE`
  - `doesMobEffectBlockSky` is set
  - `gui.hud.getBossOverlay().shouldCreateWorldFog()` is true
- **Effect on our HEAD-cancel (added).** When there is no pack and the camera is underwater, our `SkyRenderer.render` hook is never reached.

**`MixinLevelRenderer_SkipRendering` (CONFIRMED).** It is `@Mixin(LevelExtractor.class)` and targets `extractVisibleEntities` with a WrapOperation on `ClientLevel.entitiesForRendering()Ljava/lang/Iterable;`. It is not sky-related.

### Verdict: is a HEAD-cancel of `SkyRenderer.render` compatible with Iris? — CONFIRMED, with refinements
- **Yes.** Iris hooks only the private sub-methods of `SkyRenderer` and the `addSkyPass` lambda.
- **What cancelling skips.** A HEAD-cancel of `render` skips every Iris `SkyRenderer` hook: SKY, SUN, MOON, SUNSET, STARS, VOID, the sun tilt and `MixinDimensionSpecialEffects`.
- **What still runs.** `iris$beginSky` and `iris$endSky` still wrap our draw. Our draws run in phase **`CUSTOM_SKY`** (FIXED: previously UNCONFIRMED).
  - To get `SKY`, `SUN` and the other per-body phase semantics, set the phase yourself.
  - There is no public API for that in `IrisApi` v0, so the phase stays `CUSTOM_SKY` unless you call Iris internals.
- **No `sunPathRotation` (added).** Because tiltSun is skipped, apply `IrisApi.getSunPathRotation()` yourself if you want packs' sun tilt.
- **END branch (CONFIRMED).** It runs inside the same lambda, so the same reasoning applies.

**Sodium:**
- **Iris disables Sodium's sky mixin (CONFIRMED).** Iris's `fabric.mod.json` sets `custom."sodium:options"."mixin.features.render.world.sky": false`.
- **Sodium on its own (UNCONFIRMED for 26.3).** No Sodium 26.3 jar is on disk. The only Sodium 0.9 build is `sodium-fabric-0.9.1+mc26.2.jar`, in the Voxyris PrismLauncher instance.
  - In that build, `mixin/features/render/world/sky/LevelRendererMixin` is `@Mixin(Camera.class)`.
  - It wraps `extractRenderState` → `LivingEntity.hasEffect` (ordinal 0) to return true when the camera is in a fluid. This blocks the sky the way blindness does.
  - It does not touch `SkyRenderer.render`. This is likely the same in 26.3 but not verified.

### Pipeline override by identity: `net.irisshaders.iris.pipeline.IrisPipelines` — CONFIRMED, with fixes
```java
private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMap;        // Object2ObjectArrayMap
private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMapShadow;
private static final Function<IrisRenderingPipeline, ShaderKey> FAKE_FUNCTION = p -> null;
public static ShaderKey getPipeline(IrisRenderingPipeline, RenderPipeline)  // sodium-namespace special case, else map.getOrDefault(pipeline, FAKE_FUNCTION).apply(...)
public static void assignPipeline(RenderPipeline, ShaderKey)        // IllegalStateException("Shader already assigned: ...") if present
public static void assignPipelineShadow(RenderPipeline, ShaderKey)
public static void copyPipeline(RenderPipeline from, RenderPipeline to)
```
- **Lookup is by object identity (CONFIRMED).** `RenderPipeline` declares no `equals` or `hashCode` in javap.

**Vanilla sky mappings — FIXED.** The original "vertex format" column showed the ShaderKey's format and was mislabelled for `END_SKY`.

| Vanilla pipeline | Vanilla vertex format | Iris ShaderKey | ShaderKey format |
|---|---|---|---|
| `SKY` | `POSITION` | `SKY_BASIC` | `POSITION` |
| `STARS` | `POSITION` | `SKY_BASIC` | `POSITION` |
| `SUNRISE_SUNSET` | `POSITION_COLOR` | `SKY_BASIC_COLOR` | `POSITION_COLOR` |
| `CELESTIAL` | `POSITION_TEX` | `SKY_TEXTURED` | `POSITION_TEX` |
| `END_SKY` | **`POSITION_TEX_COLOR`** | `SKY_TEXTURED` | `POSITION_TEX` |

- A key `SKY_TEXTURED_COLOR(ProgramId.SkyTextured, POSITION_TEX_COLOR)` exists (CONFIRMED). Vanilla `END_SKY` is mapped to `SKY_TEXTURED`, not to it.
- **Format mismatch is tolerated (added).** The override builds attribute bindings from the **pipeline's actual** vertex formats. For each element it looks up `glGetAttribLocation` by `"iris_"+name`, then by `name`.
- **Hook point (CONFIRMED).** `MixinShaderManager_Overrides` is `@Mixin(RenderSystem.class)` and injects at `getCompiledPipelineNullable` RETURN with `cancellable=true`.
  - `RenderSystem.getCompiledPipeline` calls `getCompiledPipelineNullable` (CONFIRMED).
  - The hook is active when the pipeline is an `IrisRenderingPipeline`, `shouldOverrideShaders()` is true and `!ImmediateState.bypass`.
  - It then resolves `IrisPipelines.getPipeline` → `shaderMap.getShader(key)`. A non-null program becomes a cached replacement `FrontendRenderPipeline` wrapping a `GlRenderPipeline`.
  - It skips `CompositeRenderer.COMPOSITE_PIPELINE`, `ANIMATE_SPRITE_BLIT` and `ANIMATE_SPRITE_INTERPOLATE`.
- **Answer: reusing the static vanilla objects works (CONFIRMED).** Drawing with `RenderPipelines.END_SKY`, `CELESTIAL`, `SKY` or `STARS` through `RenderSystem.getCompiledPipeline(...)` automatically gets the matching Iris program, so `CELESTIAL` and `END_SKY` get `gbuffers_skytextured`.
- **A new `RenderPipeline` with no mapping (FIXED: previously UNCONFIRMED; now verified).**
  - `FAKE_FUNCTION` returns null, so the program is null and the vanilla, or your own, compiled pipeline is kept.
  - Iris also logs an error **once per pipeline**, with a stack trace: `"Missing program <loc> in override list. This is not a critical problem..."`. If the pipeline's namespace is `minecraft`, it logs at `fatal` level instead.
  - Register custom pipelines with `IrisApi.assignPipeline` to avoid this.

---

## 3. IrisApi — CONFIRMED (javap of `iris.jar`)
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
- **`IrisProgram` values (CONFIRMED, exact order).** `BASIC`, `TEXTURED`, `TERRAIN`, `TERRAIN_SOLID`, `TERRAIN_CUTOUT`, `TRANSLUCENT`, `SKY_BASIC`, `SKY_TEXTURED`, `ARMOR_GLINT`, `ENTITIES`, `ENTITIES_TRANSLUCENT`, `CLOUDS`, `BLOCK`, `BLOCK_TRANSLUCENT`, `HAND`, `HAND_TRANSLUCENT`, `PARTICLES`, `PARTICLES_TRANSLUCENT`, `EMISSIVE_ENTITIES`, `BEACON_BEAM`, `LINES`.
- **`isShaderPackInUse()` (added).** It returns `pipeline != null && !(pipeline instanceof VanillaRenderingPipeline)`.

**How the implementation behaves (`IrisApiV0Impl.assignPipeline`) — CONFIRMED, with fixes**
- It calls `IrisPipelines.assignPipeline(p, ShaderKey.findBestMatch(p, ProgramId.fromAPI(program)))`. `SKY_BASIC` maps to `ProgramId.SkyBasic` and `SKY_TEXTURED` to `ProgramId.SkyTextured`.
- **`findBestMatch` order (FIXED: more precise).**
  1. If the pipeline defines `ALPHA_CUTOUT`, it looks for a key with the same ProgramId, `pipeline.getVertexFormatBinding(0) == key.vertexFormat` (reference equality) and an alpha test.
  2. A key with the same ProgramId and the same vertex format.
  3. If `ALPHA_CUTOUT` is set, a key with the same ProgramId and an alpha test.
  4. Any key with the same ProgramId.
  5. `null`, after logging "Somehow couldn't find any match".
- **Logging (added).** Every successful match also logs at `warn` level ("Found perfect/okay/fine/*decent* program match"), so expect one warning per assigned pipeline.
- **Use the `DefaultVertexFormat` constants (FIXED).** Formats are compared with `==`, so your pipeline must use the `DefaultVertexFormat.*` constant objects.
  - `POSITION_TEX` with `SKY_TEXTURED` gives `ShaderKey.SKY_TEXTURED`.
  - `POSITION_TEX_COLOR` with `SKY_TEXTURED` gives `ShaderKey.SKY_TEXTURED_COLOR`.
  - `POSITION` with `SKY_BASIC` gives `SKY_BASIC`.
  - `POSITION_COLOR` with `SKY_BASIC` gives `SKY_BASIC_COLOR`.
- **Assign each pipeline once (CONFIRMED).** A second assignment throws `IllegalStateException`.

**Guard: keep every Iris reference in a separate class — CONFIRMED (the API calls exist as written)**
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

**Gradle setup — FIXED**
- **`modCompileOnly` will not work.** 26.3 uses the no-remap Loom plugin `net.fabricmc.fabric-loom` (loom `1.18-SNAPSHOT`, which resolves to 1.18.x).
  - In that mode `RemapConfigurations` returns early when `LoomGradleExtension.disableObfuscation()` is true, so no `mod*` configurations are created.
  - The smoke-test uses plain `implementation` for fabric-loader and fabric-api.
- **Use instead:**
  ```groovy
  repositories { maven { url = "https://api.modrinth.com/maven" } }
  dependencies { compileOnly "maven.modrinth:iris:1.11.7+26.3-fabric" }
  ```
- The artifact is already in the gradle cache under `modules-2/files-2.1/maven.modrinth/iris/1.11.7+26.3-fabric/` (CONFIRMED).
- `fabric.mod.json` gets `"suggests": {"iris": "*"}` (CONFIRMED).

---

## 4. Fabric Loader 0.19.5: MixinExtras and refmaps
- **MixinExtras is bundled (CONFIRMED).** `fabric-loader-0.19.5.jar` contains `META-INF/jars/mixinextras-fabric-0.5.5.jar`, which is 727,864 bytes.
- **Installer libraries (CONFIRMED).** `fabric-installer.json` lists `net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7`, `io.github.llamalad7:mixinextras-fabric:0.5.5`, and ASM `9.10.1` (asm, asm-analysis, asm-commons, asm-tree, asm-util).
- **Compile classpath (CONFIRMED).** `mixinextras-fabric-0.5.5.jar` is in the gradle cache, so `com.llamalad7.mixinextras.*` is available.
  - Iris itself uses `@WrapOperation`, `Operation`, `@WrapWithCondition`, `@ModifyArg` and `LocalRef` against 26.3.
  - No `include` or initialization call is needed.
- **Refmap: none needed (CONFIRMED).**
  - The 26.3 smoke-test jar contains no refmap, and its mixin JSON has no `"refmap"` key.
  - Iris declares `"refmap": "iris.refmap.json"` but ships no such file, and still loads.
  - Leave `"refmap"` out.
- **Mixins into lambdas (CONFIRMED).**
  - `lambda$addSkyPass$0` is the only `addSkyPass` lambda in the 26.3 `LevelRenderer`. It is `private void lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)`, an **instance** method.
  - `lambda$addSkyPass$8` does not exist in this build. Iris lists both and relies on `require=1` across the pair.
  - Iris also ships `net.irisshaders.iris.MojLambdas` and `NeoLambdas` classes that reference `addSkyPass`.