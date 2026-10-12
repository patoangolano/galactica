[@aigoatsamuel]

I re-ran every check against the real jars: the compile, shaderc, javap and bytecode. Most of the sheet holds up. One Iris claim was wrong and has been reversed. Smaller corrections are tagged FIXED inline.

# MC 26.3 Fabric: world-space VFX, full-screen effects, particles (adversarially re-verified)

**What I re-ran**
- **Compile.** JDK 25.0.4 `javac --release 25` against `vfxcheck/cp.txt` (Loom jar `minecraft-merged-7e9a32a5b8-26.3.jar` + fabric-api 0.161.0 modules). Exit 0, 6 classes.
- **Raw-jar control.** Against the `apicheck_entity/cp.txt` recipe (raw `minecraft-merged-deobf-26.3.jar`) it fails with exactly 4 errors:
  - `RenderType.create` ×2 not public
  - `PARTICLE_SNIPPET` private
  - `OIT_PARTICLE_SNIPPET` private
- **Access widener.** The four lines are present in `fabric-transitive-access-wideners-v1-8.1.16` (lines 128, 423, 424). Line 434 also widens `RenderPipelines.register(OitPipelineSet)`.
- **Shaders.** I extracted the vanilla shaders from the Loom jar (`scratchpad/vshaders/assets/minecraft/shaders`).
  - I re-ran ShaderCheck (lwjgl-shaderc 3.4.3) in a copy at `scratchpad/vfxre_tool/`. It covers the non-OIT variant plus 3 OIT stages with `OIT_ADDITIVE`, and adds a new variant with `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`. ALL OK in both runs.
  - The options match `GlslCompiler.createBaseShaderOptions`: env `4202496`, auto-bind, no preserve-bindings, `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`.
  - The OIT defines match `LevelRenderer`: `OIT_COEFF_COUNT=8`, `ATTACHMENT=2`, `WAVELET_RANK=2`.
- **Signatures.** Checked with javap on the Loom jar. Engine claims were checked in the Vineflower decompiles, or in bytecode where no decompile existed (`EntityRenderer.shouldRender`, Iris mixin).
- **Not run in game.** Nothing has been run in the game. Every "looks right" item stays UNCONFIRMED.

---

## 0. OIT rule

**Improved-transparency toggle**
- CONFIRMED: `GameRenderer.useImprovedTransparency()` is `optionsRenderState.improvedTransparency && !levelRenderState.renderWireframeTerrain`.

**OIT routing**
- CONFIRMED: when the toggle is on, `SubmitNodeCollection` aliases these to `oitTranslucent`:
  - `shadows`, `nameTags`, `texts`, `shapeOutlines`
  - `translucentBlocksAndItems`, `translucentModels`, `translucentCustomGeometry`
  - `breakingOverlay`, `afterTerrain`

**Error messages**
- CONFIRMED: `PreparedRenderType.drawFromBufferOit` throws `"Render type <name> does not have OIT pipelines set up."`.
- CONFIRMED: `QuadParticleFeatureRenderer.getOitPipeline` throws `"OIT pipeline set for particle layer not specified."`.

**`OitPipelineSet`**
- CONFIRMED: it is a record `(depthBoundsPipeline, transmittancePipeline, accumulatePipeline)`.
- CONFIRMED: `static Builder builder(String, RenderPipeline.Builder)`.
- CONFIRMED: Builder methods are `withDepthBoundsModifier`, `withTransmittanceModifier`, `withAccumulateModifier`, `withoutDepthTest()` and `build()`.
- CONFIRMED: locations are `pipeline/oit_{depth_bounds|transmittance|accumulate}_<suffix>`, built with `withLocation(String)`, which is `Identifier.withDefaultNamespace`. So they are `minecraft:`.

**OIT snippet targets**
- CONFIRMED, from `RenderPipelines` source:
  - depth-bounds: RGBA32F with `BlendFunction.MAX`
  - transmittance: RGBA16F with `ADDITIVE`
  - both use `DepthStencilState(GREATER_THAN_OR_EQUAL,false)`
  - all of them extend `OIT_SNIPPET`, which defines `OIT` and the coefficient defines and includes `GLOBALS`

**Shader side**
- CONFIRMED against the real `include/oit.glsl`, `include/oit_sample.glsl`, `core/rendertype_lightning.fsh` and `core/particle.fsh`.
  - `executeAlphaOnlyPhase`: DEPTH_BOUNDS still runs for additive, since it is checked first. With `OIT_ADDITIVE` the transmittance stage does `discard`.
  - `sampleColorForAccumulation` with `OIT_ADDITIVE` uses `transmittance=1` and `accumAlpha=0` and returns `vec4(rgb*a, 0) * sampledTransmittance`. So the classic `(SRC_ALPHA, ONE)` blend and OIT accumulate both contribute `rgb*a`.
  - Vanilla uses this exact pattern: `OIT_LIGHTNING` / `OIT_DRAGON_RAYS` = `OitPipelineSet.builder(..., builder(LIGHTNING_SNIPPET).withShaderDefine("OIT_ADDITIVE"))`.
- CONFIRMED (extra): vanilla `particle.fsh` also discards when `a < 0.1`. The probe shaders use `0.004`. That is fine for additive blending.

**sortOnUpload**
- CONFIRMED: `RenderTypeFeatureRenderer` does `sortOnUpload() && !useImprovedTransparency`.

**GLSL style**
- CONFIRMED: every vanilla core shader is `#version 330` + `GL_ARB_separate_shader_objects`, uses explicit locations and std140 blocks, and keeps samplers outside blocks.

**GameTime**
- CONFIRMED: `GlobalSettingsUniform` writes `((gameTime % 24000) + worldPartialTicks) / 24000`.
- FIXED (wording): the value is `LevelRenderState.gameTime` (game time), not day time. The comment in `vfx_plume.fsh` says "dayTime", which is wrong but harmless.

**NEW: pipeline registration**
- Unregistered custom pipelines still work. `PipelineCache.get` compiles on a miss with `compilePipeline(...).join()`, which is synchronous on the render thread, so expect a one-time hitch the first time each pipeline draws. Each OIT set is 3 more pipelines.
- To precompile at resource reload, `RenderPipelines.register(OitPipelineSet)` is AW-accessible. It feeds `requiredPipelines()`, which `ShaderManager.reload` compiles.
- `register(RenderPipeline)` is not widened.
- Calling it from mod init, before the first reload: UNCONFIRMED.

---

## 1. Entity-attached VFX via submit

**Interfaces (CONFIRMED, javap)**
- `SubmitNodeCollector extends OrderedSubmitNodeCollector`, with `order(int)` and `CustomGeometryRenderer`.
- `OrderedSubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType, SubmitNodeCollector.CustomGeometryRenderer)`.
- FIXED (precision): `OrderedSubmitNodeCollector` extends two Fabric interfaces:
  - `net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector` declares `<T extends SubmitNode> submitCustom(SubmitRenderPhase<T>, T)`.
  - `net.fabricmc.fabric.api.client.renderer.v1.render.FabricOrderedSubmitNodeCollector` holds the block/item mesh submits.

**Routing (CONFIRMED, source)**
- The pose is copied with `poseStack.last().copy()`.
- `isOutline()` goes to `outline`. `hasBlending()` (any color target with a blend function) goes to `translucentCustomGeometry`. Everything else goes to `solid`.

**Draw (CONFIRMED)**
- `RenderType.prepare()` writes `writeTransform(getModelViewMatrixCopy() [+layering modifier], textureTransform matrix)`.
- `DynamicGpuData.writeTransform(Matrix4f, Matrix4f)` uses WHITE and NO_OFFSET, so ColorModulator is white and ModelOffset is 0.
- `PreparedRenderType.draw` then does: `setPipeline`, `bindDefaultUniforms`, set `DynamicTransforms`, set the vertex buffer, set each texture by name, `drawIndexed`.

**`RenderType` (CONFIRMED)**
- `public static RenderType create(String, RenderSetup)`: public only after the transitive AW.
- Also: `hasBlending()`, `format()` (= `pipeline.getVertexFormatBinding(0)`), `primitiveTopology()`, `sortOnUpload()`, `pipeline()`, `outline()` (returns `Optional<RenderType>`), `isOutline()`.

**`RenderSetup.builder(RenderPipeline)` → `RenderSetupBuilder` (CONFIRMED)**
- Every method listed in the original sheet exists with those signatures.
- `useLightmap()` gives `Sampler2` with `getClampToEdge(LINEAR)`. `useOverlay()` gives `Sampler1` with `LINEAR`.
- A texture with a null sampler supplier falls back to `texture.getSampler()`.

**Snippets**
- COMPILED: `VfxPipelines` (PLUME / PLUME_OIT / PLUME_TYPE / GLOW*) is unchanged from the original sheet.
- `PoseStack.rotate(Quaternionfc)`, `CameraRenderState.orientation` (a `Quaternionf`) and `EntityRenderer.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)`: CONFIRMED.

**Culling**
- FIXED, now CONFIRMED from bytecode. `EntityRenderer.shouldRender` order:
  1. `entity.shouldRender(x,y,z)` (the distance check) first
  2. then `!affectedByCulling(e)` returns true
  3. then `getBoundingBoxForCulling(e,pt).inflate(0.5)` → `frustum.isVisible`
- So overriding `getBoundingBoxForCulling` / `affectedByCulling` keeps a long plume in the frustum. A huge ship can still be distance-culled by `Entity.shouldRender` / `shouldRenderAtSqrDistance`, so override that too.

**Shaders**
- `vfx_plume.vsh/.fsh`: SHADERC-OK in all 4 variants plus the explicit-depth variant.
- The vsh outputs at locations 0–3 match the fsh inputs. Blocks used: Fog, DynamicTransforms, Projection, Globals, `Sampler0`. They match the bind-group layouts `GLOBALS`, `PROJECTION`, `DYNAMIC_TRANSFORMS`, `FOG`, `SAMPLER0`: CONFIRMED.

---

## 2. Fabric LevelRenderEvents (fabric-rendering-v1 27.0.14+901a437c5d)

**Package**
- FIXED: the package is `net.fabricmc.fabric.api.client.rendering.v1.level` for `LevelRenderEvents`, `LevelRenderContext`, `LevelTerrainRenderContext` and `AbstractLevelRenderContext`.

**Events (CONFIRMED, javap)**
- `START_MAIN`, `AFTER_OPAQUE_TERRAIN`, `COLLECT_SUBMITS`, `AFTER_SOLID_FEATURES`, `AFTER_TRANSLUCENT_FEATURES`, `BEFORE_BLOCK_OUTLINE`, `BEFORE_GIZMOS`, `BEFORE_TRANSLUCENT_TERRAIN`, `AFTER_TRANSLUCENT_TERRAIN`, `END_MAIN`.
- FIXED (missing): it also has `AFTER_BLOCK_OUTLINE_EXTRACTION` and `END_EXTRACTION`, typed `LevelExtractionEvents$…`.

**Contexts (CONFIRMED)**
- `LevelRenderContext` has `submitNodeCollector()` and `poseStack()`.
- `LevelTerrainRenderContext` has `sectionsToRender()`.
- `AbstractLevelRenderContext` has `gameRenderer()`, `levelRenderer()` and `levelState()`.
- `LevelRenderState` has public `cameraRenderState`, `gameTime` and `worldPartialTicks`.
- `CameraRenderState` has public `pos` (Vec3), `orientation`, `projectionMatrix`, `viewRotationMatrix`, `depthFar` and `fogType` (plus `cullFrustum`, `isFirstPerson` and others).

**Injection points (CONFIRMED, `LevelRendererMixin`)**
- `COLLECT_SUBMITS` fires at `submitFeatures` RETURN. Its PoseStack comes from a `@ModifyExpressionValue` on `NEW PoseStack`.
- `AFTER_TRANSLUCENT_FEATURES` fires after `PreparedFrame.executeTranslucent` inside `executeClassicTransparency` (classic mode), and at `executeOit` RETURN (OIT mode).
- `END_MAIN` fires at `lambda$addMainPass$0` RETURN.

**Pass rules (CONFIRMED)**
- `FrontendGpuDevice.createCommandEncoder()` returns `this.encoder`.
- `FrontendCommandEncoder` throws `"Close the existing render pass before creating a new one!"` and `"Close the existing render pass before performing additional commands"`.

**END_MAIN matrices**
- CONFIRMED: the model-view stack holds `mul(viewRotationMatrix)` for the whole `frame.execute` and is popped after it. So `getModelViewMatrixCopy()` inside END_MAIN is the view rotation.
- The level projection is set in `GameRenderer.renderLevel` before the level renders. It changes to `hud3dProjection` only before `renderItemInHand`, which comes after the level.

**Snippets**
- The raw END_MAIN pass and the COLLECT_SUBMITS variant are COMPILED and unchanged.
- FIXED (type name): `RenderSystem.getDynamicUniforms()` returns `net.minecraft.client.renderer.DynamicGpuData`. The overload used, `writeTransform(Matrix4f, Vector4f, Vector3f, Matrix4f)`, exists.
- The `createRenderPass(Supplier<String>, GpuTextureView, Optional<Vector4fc>, GpuTextureView, OptionalDouble)` overload exists, with a `+RenderArea` overload as well.

---

## 3a. Full-screen pass with scene copy (COMPILED + SHADERC-OK)

**Pipeline and parameters**
- CONFIRMED: vanilla `core/screenquad.vsh` derives the triangle from `gl_VertexIndex`, outputs `out vec2 texCoord` at location 0 and takes no vertex input.
- CONFIRMED: `entry_screen.fsh` uses Globals (`ScreenSize`, `GameTime`), DynamicTransforms (`ColorModulator`) and `InSampler`.

**Copy**
- CONFIRMED: `copyTextureToTexture(GpuTexture src, GpuTexture dst, int mip, int dstX, int dstY, int srcX, int srcY, int w, int h)`.
- Its checks: no open pass, mip in range, rectangle in bounds, neither texture closed, `src.usage & 2` (COPY_SRC), `dst.usage & 1` (COPY_DST), and `depthOrLayers ≤ 1` for both.
- CONFIRMED: `MainTarget` color and depth use usage 15, and so does `RenderTarget.createBuffers`. MainTarget is `RGBA8_UNORM` / `D32_FLOAT`.
- CONFIRMED: `TextureTarget(String, int, int, GpuFormat, GpuFormat)`.
- FIXED (robustness): the frontend never checks that the formats match, but the backend copy needs compatible formats. Create the copy target with `main.getColorTexture().getFormat()` (or the same format main uses) instead of hard-coding `RGBA8_UNORM`, in case a mod or Iris swaps the main target format.

**Hook**
- Hook it on `END_MAIN` with `ctx.gameRenderer().mainRenderTarget()`. The hook is CONFIRMED. The visual result is UNCONFIRMED.

## 3b. Post-effect JSON chains

**Schema and blocks**
- CONFIRMED: the schema matches vanilla `post_effect/invert.json`.
- CONFIRMED: `SamplerInfo {vec2 OutSize; vec2 InSize;}` matches vanilla `post/box_blur.fsh`.
- CONFIRMED: `POST_PROCESSING_SNIPPET` = `GLOBALS_SNIPPET` + TRIANGLES.
- CONFIRMED: `minecraft:post/blit` needs a `BlitConfig {vec4 ColorModulate}` uniform. `entry_heat.json` supplies it.

**Activation**
- CONFIRMED: `GameRenderer.update()` builds `[END_OF_FRAME_POST_EFFECT] + player.getActivePostEffects() + spectated` every frame.
- CONFIRMED: `failedPostEffects` is cleared on reload.
- CONFIRMED: `LocalPlayer.setActivePostEffects(List<Identifier>)` / `getActivePostEffects()`. The backing field is `protected final List<Identifier> postEffects` on `Player`.
- FIXED (package): `net.minecraft.network.protocol.common.ClientboundPostEffectsPacket(List<Identifier>)`. It is not in `.game`. Send it with `serverPlayer.connection.send(...)`.
- CONFIRMED: `ShaderManager.getPostChain(Identifier, Set<Identifier>)` and `isPostEffectValid(Identifier, Set<Identifier>)`.
- JSON uniforms are static, so a per-frame intensity needs 3a. CONFIRMED.

---

## 4. Particles (fabric-particles-v1 5.0.24)

**Fabric API (CONFIRMED)**
- `FabricParticleTypes.simple()` and `simple(boolean)`, plus four `complex` overloads.
- `ParticleProviderRegistry.getInstance().register(ParticleType<T>, ParticleProvider<T>)` and `register(ParticleType<T>, PendingParticleProvider<T>)`.
- FIXED (name): the pending type is the nested `ParticleProviderRegistry.PendingParticleProvider<T>`, with `create(FabricSpriteSet)`.
- `FabricSpriteSet extends SpriteSet { getAtlas(); getSprites(); }`.
- `ParticleGroupRegistry.register(ParticleRenderType, Function<ParticleEngine, ParticleGroup<?>>)` plus four `registerOrdering` overloads.
- There is no `ParticleFactoryRegistry` and no `TextureSheetParticle` in the jar.

**Vanilla (CONFIRMED)**
- `ParticleProvider.createParticle(T, ClientLevel, 6×double, RandomSource)`.

**`SingleQuadParticle` (CONFIRMED)**
- Two protected constructors: `(ClientLevel, xyz, TextureAtlasSprite)` and `(ClientLevel, xyz, dxdydz, TextureAtlasSprite)`.
- abstract `getLayer()`; protected `getU0`/`getU1`/`getV0`/`getV1`.
- fields `quadSize`, `rCol`/`gCol`/`bCol`, `alpha`, `roll`.
- `FacingCameraMode.LOOKAT_XYZ` / `LOOKAT_Y`.
- `Particle` has protected `hasPhysics`, `gravity`, `friction`, `lifetime`, `random` and `getLightCoords(float)`.

**`Layer` (CONFIRMED)**
- Constructors with 3 and 4 arguments.
- Built-ins: `OPAQUE[_TERRAIN|_ITEMS]`, `TRANSLUCENT[_TERRAIN|_ITEMS]`, plus `bySprite(TextureAtlasSprite)`.

**`ParticleRenderType` (CONFIRMED)**
- A record with `SINGLE_QUADS`, `ITEM_PICKUP`, `ELDER_GUARDIANS` and `NO_RENDER`.

**Renderer (CONFIRMED, `QuadParticleFeatureRenderer`)**
- Per group: `bindDefaultUniforms`, `DynamicTransforms` = `writeTransform(getModelViewMatrixCopy())`, and `Sampler2` = lightmap with a LINEAR clamp sampler.
- Per layer: `Sampler0` = `textureManager.getTexture(layer.textureAtlasLocation())` with `texture.getSampler()`.
- `submitQuadParticleGroup` submits to `solid` and to `afterTerrain`, which is OIT under improved transparency.
- `FrontendRenderPass` throws `"Missing uniform X (should be T)"`.

**Samplers (CONFIRMED)**
- `TextureAtlas` uses `getClampToEdge(NEAREST)`.
- `ReloadableTexture.setSampler` picks LINEAR when `blur` is set and CLAMP when `clamp` is set. `SimpleTexture` is created with usage 5 and no mips.

**Snippets (CONFIRMED)**
- `PARTICLE_SNIPPET` = `MATRICES_FOG` + `particle` vsh/fsh + `SAMPLER0_SAMPLER2` + `PARTICLE` format + QUADS + `DepthStencilState.DEFAULT`.
- `OIT_PARTICLE_SNIPPET` has the same shaders and format plus DYNAMIC_TRANSFORMS and FOG, with `DepthStencilState(GEQ,false)` and no Globals/Projection. The OIT stage snippets add those.
- Vanilla `OIT_PARTICLE` = `OitPipelineSet.builder("particle", builder(OIT_PARTICLE_SNIPPET))`.

**Shader interface (CONFIRMED)**
- `plasma_particle.fsh` inputs (locations 0–3) match the outputs of vanilla `particle.vsh`. The vsh declares `Sampler2`, which the renderer binds.

**PlasmaParticle snippet**
- COMPILED, including `Provider::new` resolving to `PendingParticleProvider` and `s.first()`.

**Assets (CONFIRMED)**
- `assets/minecraft/atlases/particles.json` is a `directory` source with `"source":"particle"`.
- `minecraft:glow` exists: `particles/glow.json` and `textures/particle/glow.png`.

**Spawning (CONFIRMED)**
- `ServerLevel.sendParticles(T, boolean, boolean, x, y, z, int, dx, dy, dz, speed)` returns int. The 9-argument form exists too.
- `ClientLevel.addParticle(opts, boolean, boolean, 6×double)`, `addParticle(opts, 6×double)` and `addAlwaysVisibleParticle(...)` (2 overloads).

---

## Iris 1.11.7+mc26.3

**Improved transparency**
- FIXED. The old claim was "Iris never references improved transparency". That is false.
- `net.irisshaders.iris.mixin.fabulous.MixinDisableFabulousGraphics`, on resource reload and on LevelRenderer reload: if `IrisConfig.areShadersEnabled()` and `options.improvedTransparency()` is true, it sets improved transparency to false and `graphicsPreset` to `CUSTOM`.
- So with a shader pack active the OIT path is never used and only the classic pipelines render. The OIT sets are still required for vanilla and for Iris with shaders off.

**Override log messages**
- CONFIRMED (`MixinShaderManager_Overrides`):
  - non-`minecraft` namespace → ERROR "…This is not a critical problem…"
  - `minecraft` namespace → FATAL "…This is likely an Iris bug!!!"
- With shaders on, OIT pipelines are never requested (see above), so the `minecraft:pipeline/oit_*_<suffix>` FATAL should not fire in practice. UNCONFIRMED.

**Pipeline assignment**
- CONFIRMED: `IrisPipelines.assignPipeline(RenderPipeline, ShaderKey)` (and `assignPipelineShadow`).
- CONFIRMED: `ShaderKey.LIGHTNING`, `PARTICLES_TRANS` and `ENTITIES_EYES_TRANS` exist.

**GlDevice cast**
- The claim that the override path casts to `GlDevice` was not re-checked: SOURCE only.

## Gap against the user request (realistic stars)
- The sheet does not cover stars.
- Vanilla `RenderPipelines.STARS`: Globals + Projection + DynamicTransforms, POSITION, QUADS, `BlendFunction.OVERLAY`.
- `core/stars.fsh` is just `fragColor = ColorModulator`: flat quads with no per-star shading. That is why vanilla stars look flat.
- Realistic animated plasma stars need a custom sky/star pipeline (POSITION_TEX_COLOR plus a procedural fsh, the same pattern as `vfx_plume`). Possible approaches are an END_MAIN raw pass with depth test or a sky-pass hook; none of them has been checked.

## Still UNCONFIRMED (nothing run in game)
- What OIT accumulation looks like for the additive layers.
- END_MAIN or the scene copy with an Iris pack active.
- The copy on the Vulkan backend.
- Whether the culling plus distance overrides keep a long plume visible.
- Whether the GameTime wrap every 20 minutes shows in the noise.
- When to call `RenderPipelines.register(OitPipelineSet)` from mod init.
- The custom-UBO alternative, which was not compiled.

## Files
- Probe sources (recompiled, unchanged): `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxcheck/src/dev/galactica/probe/vfx/`
- Recompiled classes: `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxre/`
- Raw-jar control output: `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxraw/`
- Extended shader checker (adds `EXPL=1`): `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vfxre_tool/ShaderCheck.java`
- Extracted vanilla shaders: `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/vshaders/assets/minecraft/shaders/`
- Iris mixin evidence: `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/irisx/net/irisshaders/iris/mixin/fabulous/MixinDisableFabulousGraphics.class`