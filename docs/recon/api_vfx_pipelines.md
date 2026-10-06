[@aigoatsamuel]

Every item below was re-run against the 26.3 jars. Most of the sheet holds. Five items are corrected and four nuances are added.

# MC 26.3 Fabric VFX cheat-sheet: adversarial verification

## How it was re-checked
- **javap** on the Loom jar `minecraft-merged-7e9a32a5b8-26.3.jar`, which was also fully extracted to `vfxverify/jar/`, on fabric-api 0.161.0+26.3 and on `iris-1.11.7+26.3-fabric.jar`.
  - The decompiled sources in `vfxdec/out*` and `iris_out*` were cross-read, but every load-bearing claim was confirmed against bytecode.
- **Java compile:** I recompiled `probe/src` from scratch with JDK 25.0.4 `javac --release 25` against `cpL.txt`, giving **EXIT 0**, 7 classes, only joml warnings.
  - Output is in `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxverify/cls/`.
- **Runtime:** I re-ran `BuildCheck` with commons-lang3 added to the classpath (it is missing from `cpL.txt`, so the class init needs it). All three pipelines built and `SIZE=48`. Observed states:
  - `STAR_PLASMA`: depth=null.
  - `PLASMA_PLUME`: GEQUAL with no write.
  - `PLASMA_PLUME_STANDARD_Z`: LEQUAL with no write.
  - All three: blend ONE,ONE with writeMask 15.
- **GLSL:** I re-ran `ShaderHarness` on the 4 real shaders and the 7 negative/positive probes, and re-diffed `sh/.../include` against the client jar's `assets/minecraft/shaders/include` (**identical**).
- **Not checked:** the minecraft-modding MCP; javap gave the same information.

---

## 1. Custom RenderPipeline with our own shaders

**Files and IDs: CONFIRMED**
- `ShaderManager.loadConfigs` calls `manager.listResources("shaders", _ -> true)` across all namespaces.
- The include ID is `SHADER_INCLUDE_CONVERTER.fileToId(loc).withSuffix(".glsl")`, with the converter being `FileToIdConverter("shaders/include", ".glsl")`.

**Compile path: CONFIRMED**
- `GlslCompiler.createBaseShaderOptions` sets:
  - `set_target_env(opts, 0, 4202496)`, where 4202496 = VK_MAKE_VERSION(1,2,0)
  - auto-bind on, preserve-bindings off, `generate_debug_info`, opt level 0
  - the 3 `RENDERPEARL_*` macros, conditionally
- `GlPipelineRecompiler` runs SPIRV-Cross with GLSL version 330.

**Rules: CONFIRMED (harness re-run, same errors byte-for-byte)**
- `#version 330` + `#extension GL_ARB_separate_shader_objects : require` OK. `#version 450` OK. `#version 150` fails on "location".
- A missing `layout(location)` fails with "SPIR-V requires location for user input/output".
- A loose `uniform float` fails with "non-opaque uniforms outside a block".
- `#moj_import` fails with "invalid directive: moj_import".
- `#include "globals.glsl"` compiles and resolves to `minecraft:`.
  - This matches the game: `GlslCompiler.processInclude` uses only `Identifier.tryParse(requested)` and ignores the quote style and the requesting file.
  - A missing include yields "not found". A bad ID yields "malformed id".
- Vanilla core shaders in the jar use exactly `#version 330`, the extension line and `#include <minecraft:fog.glsl>` (checked in `rendertype_lightning.vsh`).
- `PORTAL_LAYERS` is used in the jar's `rendertype_end_portal.fsh`.
- **Harness caveat (FIXED):** the harness does not set spvc option `33554437` (draw parameters), and it skips `GlPipelineRecompiler`'s interface-variable renaming.
  - Its SPIR-V stage is identical to the game's. Its GLSL330 output is close to the game's but not byte-identical.

**Vanilla include contents: CONFIRMED.** All five blocks match the jar exactly. The helper list is also right: `mat2_rotate_z`, `sampleNearest`, `sampleRGSS` and the `oit*` files are present.

**Name matching: CONFIRMED** for names and formats. From `DefaultVertexFormat`:

| Element | Format |
|---|---|
| Position | RGB32_FLOAT |
| Color | RGBA8_UNORM |
| UV0 | RG32_FLOAT |
| UV1, UV2 | RG16_SINT |
| UV3 | RG32_FLOAT |
| Normal | RGBA8_SNORM |
| LineWidth | R32_FLOAT |

- Our shaders use `Position`/`UV0`/`Color` at locations 0, 1 and 2 against `POSITION_TEX_COLOR`, and they compile.
- The "Unable to find shader defined uniform" and 1D/3D rejection details come from the PipelineBuilder decompile only. They were not re-traced in bytecode, so treat them as CONFIRMED (decompile).

**Our own UBO: CONFIRMED (compiled and run), with one FIX**
- `SIZE` = 48 at runtime.
- `setUniform(name, GpuBufferSlice)` always throws `"Uniform buffer offset must be aligned to N"`. There is no strict-mode gate.
- `GpuBuffer` constants: `USAGE_UNIFORM`=128 and `USAGE_COPY_DST`=8, so 136 is correct.
- **Route A: CONFIRMED.** `FrontendCommandEncoder.writeToBuffer` throws `"Close the existing render pass before performing additional commands"` while a pass is open, so the upload must happen before the pass.
- **Route B: FIXED.** The block size is rounded to `minUniformOffsetAlignment` **only when `usage == 128` exactly**:
  ```java
  usage == 128 ? Mth.roundToward(dataSize, align) : dataSize
  ```
  - If you OR any other flag in, there is no rounding, and the second `writeData` slice will fail the `setUniform` alignment check. Pass `GpuBuffer.USAGE_UNIFORM` alone.
  - The ring adds `MAP_WRITE` (|2) itself.
  - `writeData` reuses the previous slice when `lastData.equals(gpuData)`. A record makes that work: identical params in a row share one slot.
  - The capacity doubles inside a single frame when exceeded and logs at INFO.
- **Route C: FIXED (UNCONFIRMED at runtime).** "Vanilla never uses it for UBOs" is wrong.
  - `GlRenderPass.pushConstants` calls exactly `transientMemory().uploadGpu(buf, minUniformOffsetAlignment, 128)`.
  - That is backend-internal, though. Calling it from the frontend encoder for our own UBO is still not run in game.
- **Transient lifetime:** not re-verified. Kept from the decompile.

**Shader compile errors: CONFIRMED, with a clarification**
- `PipelineCache.get` does not cache a null result.
- `getCompiledPipelineNullable` lives in **`RenderSystem`**, not ShaderManager.
  - It tries `currentPipelineCache.get`, then `fallbackPipelineCache.get`, and throws `IllegalStateException("Fallback pipeline cache not yet set")` if the fallback is absent.
- The fallback is built in `GameRenderer.preloadUiShader(minecraft.vanillaPackResources.asResourceManager())`, confirmed in `Minecraft` bytecode.
  - It logs `"Couldn't preload shader {}"`.
  - No fabric-resource-loader or fabric-rendering class references `VanillaPackResources`, so mod files are absent from it.
- The `FAILED` memo in `VfxRenderer.compiledOrNull` is therefore needed.

**Iris NPE: CONFIRMED.** `MixinShaderManager_Overrides` is `@Mixin(RenderSystem.class)` and targets `getCompiledPipelineNullable` at RETURN.
- It runs `((FrontendRenderPipeline)cir.getReturnValue()).backendRenderPipeline()` **before** the `program != null` branch.
- So any null result while `shouldOverrideShaders()` is true throws an NPE, whether or not the pipeline is mapped.

---

## 2. Animation time

**GameTime: CONFIRMED.**
- `LevelExtractor` writes `levelRenderState.gameTime = ClientLevel.getGameTime()`.
- `GlobalSettingsUniform` computes `((float)(gameTime % 24000L) + worldPartialTicks) / 24000.0F`.
- `GameRenderer` passes 0 and 0.0 when `!shouldRenderLevel`.
- **Pause and `/tick` behaviour: CONFIRMED (decompile)** via `getGameTimeDeltaPartialTick(false)`. Not run in game.

**Own clock: CONFIRMED (compiled).** It is `(float)((System.nanoTime()/1e9) % 3600.0)`.

---

## 3. Glow in the world

**State: CONFIRMED, with one FIX**
- `DepthStencilState.DEFAULT` is `(GREATER_THAN_OR_EQUAL, true)`, which confirms reversed-Z.
- `new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)` appears **14 times** in `RenderPipelines` (FIXED; the sheet said more than 15).
- `BlendFunction.LIGHTNING` is `(SRC_ALPHA, ONE)` in the clinit.
- `ADDITIVE` is ONE/ONE on RGBA8_UNORM with mask 15, per the runtime BuildCheck.
- Vanilla `LIGHTNING_SNIPPET` uses `DepthStencilState.DEFAULT`, so it **does** write depth.
- **CONFIRMED (javap)** for these members:
  - `ColorTargetState(Optional<BlendFunction>, GpuFormat, int)`
  - `ColorTargetState.WRITE_COLOR = 7` (also `WRITE_ALL=15`, `WRITE_ALPHA=8`)
- `STAR_PLASMA` has **no depth state**, so it has no test and no write. That suits a sky impostor, but it will draw over terrain if you render it in the world pass.

**Iris CompareOp remap: CONFIRMED.**
- `UndoReverseZThree` hooks `GlConst.toGl(CompareOp)` and maps GEQUAL to 515 (`GL_LEQUAL`), only when `Iris.isPackInUseQuick() && ImmediateState.isRenderingLevel`.

**Where to draw: CONFIRMED**
- Fabric `LevelRendererMixin.endMainRender` is `@Inject(method="lambda$addMainPass$0", at=@At("RETURN"))`.
- Inside that lambda, the "Solid"/"Main" pass is closed before `executeOit`, `executeOutline`, `executeSeeThrough` and `executeAlwaysOnTop`.
- `afterRenderSolidFeatures` and `afterRenderClassicTranslucentFeatures` fire inside `executeSolid`/`executeClassicTransparency`, both of which receive the open `RenderPass`.
- `FrontendCommandEncoder.isInRenderPass()` is public.
- **Nuance (added):** END_MAIN fires **after** the outline, see-through and always-on-top gizmo passes, so the plume draws over those overlays.
- **Signatures: CONFIRMED (javap)**
  - `RenderPass.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)`
  - `DynamicGpuData.writeTransform(Matrix4f, Vector4f)`
  - `RenderSystem.getSequentialBuffer(PrimitiveTopology)`
  - `RenderSystem.getModelViewMatrixCopy()`
- `bindDefaultUniforms` sets Projection, Fog and Globals when they are non-null, which covers `PLASMA_PLUME`'s FOG layout.

**Correction to `api_render_verified.md`: CONFIRMED**
- `FrontendGpuDevice.STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE`.
- `IS_RUNNING_IN_IDE` is a non-final static field. There is **no `putstatic` to it** in any of the 31 MC classes that reference it, in any fabric-api module on `cpL.txt`, or in fabric-loader 0.19.5.
  - fabric-debug-api and fabric-networking only read it.
- In `validateDraw`, the whole block (missing uniform, push constants, index and vertex buffer checks) sits under `STRICT_VALIDATION`.
- The `setUniform` alignment check sits outside it and is always on.

**Realistic shaders: CONFIRMED (compile only).** shaderc and spvc report:

| Shader | SPIR-V | GLSL330 |
|---|---|---|
| star_plasma.vsh | 3452 B | OK |
| star_plasma.fsh | 15832 B | OK |
| plasma_plume.vsh | 6936 B | OK |
| plasma_plume.fsh | 19280 B | OK |

- No real GL driver compile was run.
- `DynamicTexture` default sampler is `getRepeat(FilterMode.NEAREST)`: CONFIRMED (javap).

---

## 4. Precompile and reload

**Reload behaviour: CONFIRMED**
- `ShaderManager.apply` builds a new `PipelineCache`.
- A failed required pipeline closes the optional ones and throws `RuntimeException("Failed to load required shader programs...")`.
- A failed optional pipeline logs a WARN and is not inserted, so a lazy compile retries later.
- The old cache is closed after `setCurrentPipelineCache`.

**FIXED: no mixin needed.**
- `RenderPipelines.registerOptional(RenderPipeline)` is **public static** in the Loom jar. fabric-api's `fabric-transitive-access-wideners-v1.classtweaker` declares `transitive-accessible method net/minecraft/client/renderer/RenderPipelines registerOptional (...)`, so it is also public at runtime with Fabric API installed.
- `OPTIONAL_PIPELINES_BY_LOCATION` is a mutable `HashMap`.
- Compiled and **run**: after the calls, `optionalPipelines()` lists all three `galactica:pipeline/*`.
  ```java
  // COMPILED + RUN. Call from ClientModInitializer (before the first resource reload)
  VfxPipelines.ALL.forEach(RenderPipelines::registerOptional);
  ```
- The old `RenderPipelinesPrecompileMixin` still compiles and its target exists (`optionalPipelines()Ljava/util/List;`, public static). It is only a fallback now.

**Reload listener: CONFIRMED (compiled).** `ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(id, (ResourceManagerReloadListener) ...)` plus `addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, id)`.

---

## 5. Iris 1.11.7+26.3

**Unmapped pipeline: CONFIRMED.**
- `IrisPipelines.getPipeline` falls back to `FAKE_FUNCTION = p -> null`.
- With no program, the original pipeline is kept and logged once (`missingShaders.add`) with "This is not a critical problem..." for non-minecraft namespaces.

**`IrisApi.assignPipeline(RenderPipeline, IrisProgram)`: CONFIRMED (javap).**
- It calls `IrisPipelines.assignPipeline(p, ShaderKey.findBestMatch(p, ProgramId.fromAPI(program)))` and throws `IllegalStateException("Shader already assigned: ...")`.
- **Added:** `findBestMatch` also branches on an `ALPHA_CUTOUT` shader define, not only on program and vertex format 0.
- `IrisProgram` values per javap: BASIC, TEXTURED, TERRAIN, TERRAIN_SOLID, TERRAIN_CUTOUT, TRANSLUCENT, SKY_BASIC, SKY_TEXTURED, ARMOR_GLINT, ENTITIES, ENTITIES_TRANSLUCENT, CLOUDS, BLOCK, BLOCK_TRANSLUCENT, HAND, HAND_TRANSLUCENT, PARTICLES, PARTICLES_TRANSLUCENT, EMISSIVE_ENTITIES, BEACON_BEAM, LINES. There is no LIGHTNING.
- `LIGHTNING` and `DRAGON_RAYS` map to `ShaderKey.LIGHTNING` (main) and `SHADOW_LIGHTNING` (shadow).

**GL-only replacement: CONFIRMED.** The mixin casts to `GlDevice` and `GlRenderPipeline`.

**Z under a pack: CONFIRMED, with one FIX (scope)**
- `UndoReverseZTwo` makes `GlHeuristics.createDeviceInfo` read `GL_ARB_clip_control` as false **unconditionally** whenever Iris is loaded.
- `UndoReverseZFour` swaps near and far in `Projection.getMatrix`, and `UndoReverseZThree` flips CompareOp.
- **FIXED: the scope is narrow.** All of these (except Two) apply only while `ImmediateState.isRenderingLevel`, which Iris sets true at `LevelRenderer.render` HEAD and false at RETURN. Consequences:
  - **END_MAIN** is inside `LevelRenderer.render`, so `PLASMA_PLUME` (GEQUAL) is flipped to LEQUAL automatically. Write it in reversed-Z terms. This is consistent with the sheet.
  - **`GameRenderer.render3dHud`** runs after `LevelRenderer.render` returns, so `isRenderingLevel` is false there.
    - CompareOp is **not** flipped, and `Projection.getMatrix` returns the **vanilla reversed-Z** matrix.
    - So `PLASMA_PLUME_STANDARD_Z` (LEQUAL) at that hook needs **its own standard-Z ProjMat**, built with the same near/far swap Iris uses and uploaded to the "Projection" binding. Without it, the fragment depth is reversed-Z, tested LEQUAL against a standard-Z buffer.
- **Added nuance:** `IrisApi.isReverseZDuringShaders()` exists and returns `!ShadowRenderingState.areShadowsCurrentlyBeingRendered()`. Its name contradicts the mixins.
  - Rely on the mixin behaviour above, not on this API, until it is checked in game.

**Recommendations per effect: UNCONFIRMED** (reasoned from code). They are unchanged, except that the `PLASMA_PLUME_STANDARD_Z` post-composite route must also bind a custom standard-Z projection, as in the Z section above.

---

## Still UNCONFIRMED (needs the game)
- In-game drawing of every pipeline, routes A, B and C, the END_MAIN draw, and `registerOptional` precompile actually landing in the reload.
- Visibility of the plume over or behind OIT translucents. It also now draws over outline and gizmo passes.
- Under Iris with a pack:
  - whether an END_MAIN pass is lost to composite,
  - what depth content and attachment are bound at `render3dHud`,
  - which program `findBestMatch` picks for POSITION_TEX_COLOR.
- How the reload pipeline handles the required-pipeline `RuntimeException` beyond `apply()`.
- A real GL driver compile of the SPIRV-Cross output. The harness also lacks the drawParameters option and the interface renaming.
- Iris NPE catch path at runtime. It is compiled only.

## Files
- Recompiled probe classes: `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxverify/cls/`
- New verified register helper: `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxverify/reg/dev/galactica/vfx/VfxRegister.java`
- Runtime checks: `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxverify/rt/RegCheck.java` and `.../vfxdec/probe/rt/BuildCheck.java`
  - Run them with `commons-lang3-3.12.0.jar` added to `cpL.txt`.
- Extracted jars: `.../scratchpad/vfxverify/jar/` (MC 26.3 merged) and `.../scratchpad/vfxverify/iris/` (Iris 1.11.7)
- The original probe sources and shaders are unchanged: `.../scratchpad/vfxdec/probe/src/dev/galactica/...` and `.../scratchpad/vfxdec/probe/assets/galactica/shaders/...`