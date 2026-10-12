# Iris / shaderpack compatibility research (2026-10-06)

Status: deferred until the mod is feature-complete. Decision record from 4 agents.



## Iris dimension mapping

[@aigoatsamuel]

I traced the dimension path through Iris and Complementary. Switching galactica:space to the End profile will not fix the overlap: Complementary's End profile overwrites every sky pixel itself.

## TASK 1: how Iris 1.11.7 picks the dimension profile

Decompiles are in `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/irisdec2/src` and `src2`. The full extracted jar is in `irisdec2/all`.

**The resolver.** `net.irisshaders.iris.Iris.getCurrentDimension()`, descriptor `()Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;` (Iris.java:528):
```java
NamespacedId dimensionId = new NamespacedId(level.dimension().identifier().getNamespace(), ...getPath());
if (pack != null && pack.getDimensionMap().containsKey(dimensionId)) return dimensionId;
Skybox skybox = level.dimensionType().skybox();
if (skybox == Skybox.END) return DimensionId.END;
return skybox == Skybox.OVERWORLD ? DimensionId.OVERWORLD : dimensionId;
```
- `DimensionId` holds three constants: OVERWORLD, NETHER (`minecraft:the_nether`) and END (`minecraft:the_end`).
- `NamespacedId.equals` and `hashCode` compare namespace and name.
- Three callers use this result:
  - `MixinLevelRenderer`: `preparePipeline(Iris.getCurrentDimension())` on every frame.
  - `MixinMinecraft_PipelineManagement`: `setLevel`, `clearClientLevel` and `updateLevelInEngines`. The last one compares with `!=` against `Iris.lastDimension` (identity, not equals).
  - `WorldTimeUniforms.getWorldDayTime`: compares with `!= DimensionId.END` and `!= DimensionId.NETHER` (identity).

**The folder lookup.** `ShaderPack.getProgramSet(NamespacedId)` (ShaderPack.java:528) looks the id up in `dimensionMap`. If the id is not in the map it falls back to `this.base`, which is built from `dimensionMap.getOrDefault(new NamespacedId("*","*"), "")` (line 304). The map comes from `parseDimensionMap` (line 363), where a value of `*` becomes the key `NamespacedId("*","*")`. If the pack has no `dimension.properties`, Iris builds a default map (lines 127-142): world0 for overworld and `*:*`, world-1 for the nether, world1 for the end.

### (a) Which profile galactica:space gets with Complementary

Complementary r5.9.3 ships `shaders/dimension.properties`:
```
dimension.world0=*
dimension.world-1=minecraft:the_nether minecraft:nether
dimension.world1=minecraft:the_end minecraft:end
```
The pack has three world folders: `world0`, `world-1` and `world1`.

galactica:space is not in the map, so Iris goes to the skybox branch. The skybox is OVERWORLD, so it returns `DimensionId.OVERWORLD`, which loads `world0`, the OVERWORLD profile. Even without the skybox branch, the `*` fallback would also give world0.

The OVERWORLD profile, plus Iris itself, explains what you see in game:
- **`IrisRenderingPipeline.onBeginClear()` (line 1341).** When `skyType == Skybox.OVERWORLD || hasSkyLight()`, it draws `horizonRenderer.renderHorizon(..., fogColor)`, the fog-coloured horizon disc. This runs even though we cancel `SkyRenderer.render`.
- **`program/gbuffers_skytextured.glsl`, OVERWORLD branch.** Anything that is not sun or moon goes through `// Custom Sky`:
  - `color.rgb *= color.rgb * smoothstep1(sqrt1(max0(VdotU)))`, which squares our colours and blacks out everything below the horizon.
  - `color.a *= 1.0 - rainFactor`.
- **`deferred1.glsl` (around line 362 and line 396).** Sky pixels keep their colour and get aurora and nebula added. Then volumetric clouds are mixed in when `z0 > 0.56`, which includes the sky.

### (b) Is there a public API or clean injection point?

- **Public API:** none. `net.irisshaders.iris.api.v0.IrisApi` has nothing about dimensions.
- **Zero-mixin route:** set `"skybox": "end"` in galactica:space's `dimension_type`. Iris line 536 then returns `DimensionId.END` (world1) automatically, and the `onBeginClear` horizon is skipped because skybox is not OVERWORLD and `has_skylight` is false. On the vanilla side, `skybox` only feeds `SkyRenderer` (we cancel it), the `!= NONE` sky-pass gate in `LevelRenderer`, and `DimensionType.hasEndFlashes()` (no caller in the decompiled 26.3 source).
- **Mixin route,** if you want it in code:
  - Target: `net.irisshaders.iris.Iris`
  - Method: `getCurrentDimension`, descriptor `()Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;` (public static)
  - Injection: `@Inject(at = @At("RETURN"), cancellable = true)` with `cir.setReturnValue(DimensionId.END)` when `level.dimension()` is galactica:space, or MixinExtras `@ModifyReturnValue`. Mark it `remap = false` and pseudo/optional, since Iris is a soft dependency.
  - Return the `DimensionId.END` constant itself, never `new NamespacedId(...)`. `WorldTimeUniforms` and the reload check compare with `!=`, so a new instance would break them.

**Why world1 does not help with Complementary:**
- `gbuffers_skytextured` END branch outputs a flat `vec4(endSkyColor, 1.0)`. Every END_SKY draw we make (Milky Way, stars, planets) would come out as a flat End colour.
- `deferred1` END branch (line 382) sets `color.rgb = endSkyColor; color.rgb += GetEnderStars(...)` on every sky pixel. That replaces our sky in colortex0.
- world-1 is no better: skytextured outputs `vec4(0.0)` and deferred1 sets `netherColor`.

Recommendation: keep world0 and do not remap. Instead:
- Set `skybox` to `"none"` or `"end"` so the Iris horizon disc stops drawing. With `"none"`, `LevelRenderer` skips the sky pass and our HEAD-cancel hook on `SkyRenderer.render` never runs, so the sky would need its own draw point.
- Deal with the world0 skytextured custom-sky squaring and the deferred1 clouds separately. One option is to draw our sky after deferred1, or outside the Iris SKY_TEXTURED mapping.

### (c) Can a mod ship its own mapping, and does Iris have rules for modded dimensions?

- **Shipping a mapping:** no. Iris reads `dimension.properties` only from the pack root, via `loadProperties(root, "dimension.properties", ...)` in the `ShaderPack` constructor (around line 122). It does not read any mod resource or registry.
- **Built-in rule for modded dimensions:** exactly one. An id that is not in the pack's map follows the `DimensionType.skybox()` branch shown above: END gives the End profile, OVERWORLD gives the overworld profile, and NONE keeps the raw id, which falls back to the `*` / base set. That is the only place Iris reads the skybox for profile choice.
- **Other DimensionType reads:** `IrisRenderingPipeline.onBeginClear` checks `skybox` and `hasSkyLight` for the horizon disc only.
- **Pack wildcards:** the `*` wildcard in `parseDimensionMap` is the only wildcard. It also inserts a stray `minecraft:*` key, which has no effect.

Default to keep: world0 profile, with the skybox set to `"none"` or `"end"` to kill the horizon disc.

## Complementary GLSL

[@aigoatsamuel]

Complementary hides our sky in two places. `gbuffers_skytextured` reshapes what we draw, and `deferred1` paints its own content over the sky pixels afterwards. world0 is the only profile that keeps any of our output.

Pack unzipped read-only to `.../scratchpad/compl/shaders`. I extracted three Iris classes to `.../scratchpad/irisdec2/t2` and read them with javap; the jar was never run. Paths below are relative to `compl/shaders`.

## Facts that apply to every case

**Which profile we get.** `dimension.properties` has `dimension.world0=*`, so `galactica:space` loads the world0 (OVERWORLD) programs. world1 is only `minecraft:the_end minecraft:end` and world-1 only `minecraft:the_nether minecraft:nether`.

**Depth.** Our sky leaves depth at 1.0 (the recon doc says vanilla sky pipelines have a null depth-stencil state). So every pass treats our pixels as sky (`z0 == 1.0`).

**Where colortex0 starts.** `IrisRenderingPipeline.beginLevelRendering` (bytecode 702–771) clears it to the captured fog colour:
```
CapturedRenderingState.INSTANCE.getFogColor() -> new Vector4f(x,y,z,1) -> ClearPass.execute(...)
```
`ClearPassCreator.lambda$createClearPasses$0` gives colortex0 a null colour, so this fog colour is used. A black fog attribute gives a black base.

**Pack settings.**
- `lib/pipelineSettings.glsl:19` sets `colortex0Clear = true`.
- `shaders.properties` has `alphaTest.gbuffers_skytextured=GREATER 0.0001`.
- There is no `blend.gbuffers_skytextured`, so our pipeline's TRANSLUCENT blend is kept.
- It sets `clouds=off` unless `CLOUD_STYLE_DEFINE == 50`, so vanilla clouds never draw.

## (a) Which programs write the sky colour

**`program/gbuffers_skytextured.glsl`, world0.** Our draws carry renderStage CUSTOM_SKY. That is not SUN or MOON (Iris ≥ 1.8.14 skips the side-check hack), so they take the "Custom Sky" branch:
```glsl
} else { // Custom Sky
    color.rgb *= color.rgb * smoothstep1(sqrt1(max0(VdotU)));
...
if (isEyeInWater == 1) color.rgb *= 0.25;
color.a *= 1.0 - rainFactor;   // or 1-0.8*rainFactor with SUN_MOON_DURING_RAIN
/* DRAWBUFFERS:0 */
```
- Colours are squared, so a 0.3 Milky Way becomes 0.09.
- Everything below the horizon (VdotU ≤ 0) becomes black, and output fades towards the horizon.
- Rain removes it.
- If a planet were drawn in SUN or MOON stage it would get sun or moon tinting instead, and with `SUN_MOON_STYLE >= 2` it is `discard`ed.

**`program/gbuffers_skybasic.glsl`.** Line 83 is `color.rgb = GetSky(...)`. It only runs for vanilla SKY/STARS geometry, which our HEAD-cancel removes, so it is not the culprit.

**`program/deferred1.glsl`, sky branch for `z0 == 1` (lines ~362–390).**
- OVERWORLD keeps colortex0 and only adds to it: `color.rgb += auroraBorealis` (AURORA_STYLE > 0) and `+= nightNebula` (`NIGHT_NEBULAE == 1`, default -1).
- NETHER overwrites it: `color.rgb = netherColor * (1.0 - maxBlindnessDarkness);`
- END overwrites it: `color.rgb = endSkyColor; color.rgb += GetEnderStars(viewPos.xyz, VdotU);`

After that branch, still in deferred1:
- Lines 397–405, any pixel with `z0 > 0.56` (which includes sky): `clouds = GetClouds(...); color = mix(color, vec4(clouds.rgb,0), clouds.a);`
- Lines 418–433, OVERWORLD with ATMOSPHERIC_FOG and (SPECIAL_BIOME_WEATHER or RAIN_STYLE==2): `color = mix(color, atmFogColor, 0.5*rainFactor*factor*sqrt1(skyFade))`. This only matters in rain.

**Border and atmospheric fog.** `lib/atmospherics/fog/mainFog.glsl` (`DoBorderFog` uses `GetSky`) is reached through `DoFog` only inside `if (z0 < 1.0)`. It does not touch our sky pixels.

**`program/composite1.glsl`.**
- Line 238: `GetVolumetricLight(...)`. `volumetricLight.glsl:77` has `bool sky = z1 == 1.0;`, so light shafts are added on top of sky (`color += volumetricEffect.rgb`, line 325).
- `LIGHTSHAFTS_ACTIVE` is defined at `common.glsl:649` as `LIGHTSHAFT_QUALI > 0 && defined OVERWORLD && SHADOW_QUALITY > -1 || defined END`.
- Line 268: `if (isEyeInWater==1 && z0==1.0) color.rgb = waterFogColor;`
- Line 278: lava sets `fogColor*5`.

**Other composites.** composite, composite2–7 and final only do reflection, DoF, bloom, tonemap, TAA and FXAA. None of them replaces sky pixels.

**Where the "blue-grey gradient" comes from.** It is not a sky overwrite. It is the clouds tinted by `GetSky`, plus light shafts:
- `lib/atmospherics/clouds/reimaginedClouds.glsl:123–129`: `cloudSkyColor = GetSky(...)` and `colorSample = mix(cloudSkyColor, colorSample, cloudFogFactor*0.707)`. Distant clouds become the pack's sky gradient.
- `lib/colors/skyColors.glsl:51` hard-codes a floor that stays even when `skyColor` is black: `nightColFactor = 0.9*vec3(0.07,0.14,0.24)*(...) + skyColor;`
- Line 44: `sunsetDownSkyColorP = vec3(1.45,0.86,0.5)...`

So `sky_color = black` does not make GetSky black, especially at night or sunset.

## (b) world1 (End) and our skytextured output

`gbuffers_skytextured.glsl`:
```glsl
#ifdef END
    vec4 color = vec4(endSkyColor, 1.0);   // texture never sampled
#endif
```
`endSkyColor` is the constant `vec3(0.095,0.07,0.15)*1.65` (`common.glsl:819`).
- Our texture is replaced by a flat purple.
- deferred1 then overwrites every `z0==1` pixel with `endSkyColor + GetEnderStars`.

Clouds and fog in world1:
- Clouds are off: `VL_CLOUDS_ACTIVE` is only defined inside `#ifdef OVERWORLD` (`common.glsl:579–581`), and `clouds=off` covers vanilla clouds.
- `BLOOM_FOG` is undefined for END.
- `LIGHTSHAFTS_ACTIVE` is forced on for END, so End beams are added over the sky in composite1.

Our sky is lost entirely.

**world-1 (Nether).**
- skytextured writes `vec4(0.0)`. The alpha test (> 0.0001) discards it, so nothing of ours is written.
- deferred1 sets sky to `netherColor` (`common.glsl:803–815`, derived from `fogColor`).
- `NETHER_STORM` can be added on top.

Also lost entirely.

## (c) How clouds are drawn and what gates them

They are volumetric clouds ray-marched in deferred1 (`GetClouds` in `lib/atmospherics/clouds/mainClouds.glsl`) and mixed into colortex0.

Gate (`common.glsl:579–585`):
```glsl
#ifdef OVERWORLD
  #if CLOUD_STYLE > 0 && CLOUD_STYLE != 50 && CLOUD_QUALITY > 0
    #define VL_CLOUDS_ACTIVE   // style 1 = CLOUDS_REIMAGINED, 3 = CLOUDS_UNBOUND
```
- `CLOUD_STYLE_DEFINE` options are `[-1 0 1 3 50]`. The default -1 maps to `CLOUD_STYLE_DEFAULT`: 1 for SHADER_STYLE 1, 3 for SHADER_STYLE 4.
- Cloud altitude: `common.glsl:834–841`, `cloudHeightM = isnan(cloudHeight) ? 192.0 : cloudHeight;`, with `cloudAlt1i = CLOUD_ALT1(192) + offset` and `CLOUD_ALT2(288)`.
- The only early-out is in `reimaginedClouds.glsl:41`: `if (maxPlaneDistance < 0.0) return vec4(0)`. This only skips rays that point away from the cloud slab. A camera far above the planes still sees the clouds below it.

Nothing on our side switches them off:
- `has_skylight=false` and the skybox type do not reach the GLSL. The profile comes only from the dimension id through `dimension.properties`.
- `cloud_color` alpha 0 does nothing, because vanilla clouds are already off.
- Our `cloud_height`, if Iris passes it, only moves the slab.
- `sky_color` black does not zero `GetSky`, because of the hard-coded floors above.
- `isEyeInWater` only tints.

What does remove them: the pack option `CLOUD_STYLE_DEFINE=0` (user setting), `CLOUD_QUALITY=0`, or not being in the OVERWORLD profile.

## (d) Pack-side switches that would preserve a custom sky

There is no "mod dimension" path, and nothing honours skytextured alpha. The only hooks:
- `dimension.properties` maps dimension ids to world folders, with world0 as the catch-all.
- In world0, deferred1 keeps whatever is already in colortex0 for sky pixels and only adds to it. This is the one preserving path.
- User options that reduce damage: `CLOUD_STYLE_DEFINE=0`, `AURORA_STYLE=0`, `NIGHT_NEBULAE=-1` (the default), `LIGHTSHAFT_QUALI=0` or `SHADOW_QUALITY=-1`, and `SUN_MOON_STYLE` (only matters if we draw in SUN/MOON stage).
- A mod cannot set these through Iris. The user has to set them, or edit the pack.

## Conclusion

**world0 (the current profile, via `*`)** best preserves our skytextured output. It is the only profile where deferred1 does not replace `z0==1` pixels.

What we still lose in world0:
1. The skytextured fragment squares our RGB, so the Milky Way and stars come out dark.
2. It fades to black at the horizon and turns everything below it black (bad in space, where half the view is below the horizon).
3. Alpha drops with rain.
4. Reimagined or Unbound volumetric clouds at y≈192/288 cover the sky, tinted with `GetSky` night or sunset colours that `sky_color` black does not remove. This is the blue-grey wash in the screenshot.
5. Volumetric light shafts are added over sky pixels in composite1.
6. Aurora is added in snowy biomes.
7. Rain/biome atmospheric fog is mixed in with rain.
8. Tonemap and bloom are applied.

world1 replaces our texture with a flat colour plus End stars, and world-1 discards our output and paints `netherColor`. Neither is usable.

The levers left on our side:
- Pre-compensate brightness for the squaring: output `sqrt(color)`, so `color*color` gives back the original.
- Keep the sky drawn in CUSTOM_SKY stage, never SUN or MOON.
- Clouds can only be removed by the user's pack setting, or by drawing our sky after deferred1 (outside the gbuffers path).

## Post-composite overlay

[@aigoatsamuel]

Main finding: on the Iris path, depth is not reversed. During `LevelRenderer.render`, Iris rewrites the depth clear from 0.0 to 1.0. So after Iris finishes, empty sky in the main depth texture reads **1.0**, not 0.0. The overlay below is built around that.

Decompiles are in `~/AppData/Local/Temp/claude/<claude-project>/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/irisdec2/`. Iris classes are in `out/`, `out2/` and `out3/`, Fabric rendering-v1 27.0.14 is in `fab/out`, and the raw Iris classes are in `all/`. Vanilla 26.3 is from `scratchpad/dec` and the client jar.

## (a) Where the final pass writes, and whether depth survives

**Call order.** Iris's `MixinLevelRenderer.iris$endLevelRender` injects in `LevelRenderer.render` at `INVOKE Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;`. That call comes after `frame.execute(...)`, so the whole frame graph (sky, terrain, translucents) has already run. It then does:
- `HandRenderer.renderTranslucent`
- `pipeline.finalizeLevelRendering()`, which calls `compositeRenderer.renderAll()` and then `finalPassRenderer.renderFinalPass()` (`IrisRenderingPipeline` around line 1206).

**Where the final pass writes.** `FinalPassRenderer.renderFinalPass` writes to the main colour target only:
```java
createRenderPass(() -> "Final pass", mainRenderTarget().getColorTextureView(), Optional.empty())
```
There is no depth attachment. When the pack has no `final` program, it does `copyTexSubImage2D` into `main.getColorTexture()` instead.

**Composite passes leave depth alone.** They use `renderTargets.createColorFramebuffer(...)` (`CompositeRenderer` lines 160 and 266), which is colour-only.

**`depthtex0` is the vanilla main depth texture itself, not a copy.**
- `IrisRenderingPipeline` constructor, line 237: `new RenderTargets(..., main.getDepthTexture(), ...)`.
- `RenderTargets.getDepthTexture()` returns `currentDepthTexture`.
- The gbuffer framebuffers do `addDepthAttachment(this.currentDepthTexture)`.

So after `finalizeLevelRendering`, `mainRenderTarget().getDepthTexture()` still holds the full world depth: opaque terrain, translucents, and the hand. Iris draws the hand inside the level pass through `HandRenderer` (`beginHand` and `renderTranslucent`) into `mainRenderTarget().getDepthTextureView()`, and it disables the vanilla hand with `MixinGameRenderer.iris$disableVanillaHandRendering`.

**Two more Iris depth textures exist:**
- `RenderTargets.noTranslucents` ("Depth / Opaque", `depthtex1`), copied in `beginTranslucents()` → `copyPreTranslucentDepth()`.
- `noHand` ("Depth / Before Hand", `depthtex2`).
- Both are created with usage 5 (copy-dst plus texture-binding), so they can be sampled.

**Depth convention with a pack active.** `UndoReverseZFive` (a mixin on `GlCommandEncoder`) wraps `clearColorAndDepthTextures`, `clearDepthTexture` and `createRenderPass`. It replaces the clear value with `1.0 - clearDepth` whenever `Iris.isPackInUseQuick() && ImmediateState.isRenderingLevel`.
- `ImmediateState.isRenderingLevel` is set to true at `LevelRenderer.render` HEAD and false at RETURN (`mixin/vertices/immediate/MixinLevelRenderer`).
- So vanilla's `clearColorAndDepthTextures(..., depth, 0.0)` becomes **1.0** with a pack.
- **With a pack, far = 1.0. Without a pack, far = 0.0** (vanilla reversed-Z, `DepthStencilState.DEFAULT = GREATER_THAN_OR_EQUAL`).
- `GameRenderer.render3dHud` later clears depth to 0.0 again (line 724), after `isRenderingLevel` has gone false.

**The main depth texture can be sampled.** `RenderTarget.createBuffers` creates it with usage `15`, which includes `USAGE_TEXTURE_BINDING=4`. Vanilla already samples a depth target the same way in `INTEGRATE_DEPTH` (`core/integrate_depth.fsh`: `texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).r; if (depth == 0.0) discard;`).

## (b) Hook point

**`LevelRenderEvents.END_MAIN` does not work.** `fabric-rendering-v1 27.0.14`'s `LevelRendererMixin.endMainRender` is `@Inject(method="lambda$addMainPass$0", at=@At("RETURN"))`. That fires inside the frame graph, before `popMatrix`, so before Iris's composite and final passes. Anything drawn there gets overwritten exactly like the current sky.

**Use `GameRenderer.render3dHud` at HEAD.** In `GameRenderer.renderLevel()` (dec line 706), `levelRenderer.render(...)` returns and `render3dHud(...)` is called next. At that moment:
- Iris has finished composite and final.
- Main depth still holds world depth; the `clearDepthTexture(depth, 0.0)` comes later, at line 724.
- `RenderSystem` projection is still the level projection (set at line 699; Iris's `HandRenderer` backs it up and restores it).
- `IrisRenderingPipeline.shouldOverrideShaders()` returns `isRenderingWorld && isMainBound`, and `isRenderingWorld` is now false. So `MixinShaderManager_Overrides` does not swap our program, and `MixinRenderPipeline`'s vertex-format swaps are off.
- Iris's colour-space pass runs later, at `renderLevel` TAIL (`MixinGameRenderer.iris$runColorSpace`), so the overlay goes through the user's colour-space setting like everything else.

Descriptors (verified with javap):
- Target: `net.minecraft.client.renderer.GameRenderer.render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V`, which is private.
- Equivalent alternative: `renderLevel()V`, `@At(value="INVOKE", target="Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V", shift=AFTER)`.

## (c) Overlay pipeline

**Don't use a hardware depth test.** Our shells sit at r ≤ 100. A `GREATER/LESS` test against geometry depth would let the sky draw over terrain farther than 100 blocks. Making a fragment's depth land exactly on far depends on the clip-space convention.

**Sample depth and discard instead**, the same way vanilla's `integrate_depth` does. Use a colour-only pass (no depth attachment, so there is no feedback loop):

```java
public static final RenderPipeline SPACE_OVERLAY = RenderPipeline.builder()
    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
    .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
    .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
    .withLocation(Identifier.fromNamespaceAndPath("galactica", "pipeline/space_overlay"))
    .withVertexShader("core/position_tex_color")
    .withFragmentShader(Identifier.fromNamespaceAndPath("galactica", "core/space_overlay"))
    .withShaderDefine("FAR_DEPTH", 1.0f)          // Iris un-reverses Z; vanilla variant would be 0.0f
    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
    .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
    .withPrimitiveTopology(PrimitiveTopology.QUADS)
    .build();                                      // no depth-stencil state, same as END_SKY
```

Build a second pipeline with `FAR_DEPTH 0.0f` only if you ever want the overlay running without a pack.

`assets/galactica/shaders/core/space_overlay.fsh`:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; };
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;               // scene depth (main target)
layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;
void main() {
    if (texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r != FAR_DEPTH) discard;
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) discard;
    fragColor = color * ColorModulator;
}
```

Draw call:
```java
try (RenderPass pass = encoder.createRenderPass(() -> "Galactica space overlay", rt.getColorTextureView(), Optional.empty())) {
    RenderSystem.bindDefaultUniforms(pass);
    pass.setUniform("Sampler1", rt.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    // then the existing layer draws: setPipeline(getCompiledPipeline(SPACE_OVERLAY)), "DynamicTransforms", "Sampler0", ...
}
```

`PipelineCache.get` compiles unknown pipelines on demand (`device.compilePipeline(pipeline, shaderSource, ...)`), so no registration is needed. Warm it up on the first frame anyway.

## (d) Tone and brightness

After the final pass the image is display-referred. The overlay shows our LDR sky with no pack grading, which is the same look as with shaders off, so it stays consistent. The pack's bloom and TAA don't touch it.

## Implementation plan

1. **Split `SpaceSkyRenderer.render()`** into two parts:
   - `prepareFrame()`: `mgr.update`, `takeSky`/`takeStars`, `rebuildNear`/`rebuildPlanets`, `PlanetSprites.beginFrame`/`evict`, and resolving the texture views. Guard it with a frame counter so it runs once per frame.
   - `drawLayers(RenderPass, RenderPipeline, Matrix4f modelView)`: the existing `draw(...)` calls plus the `RESOLVED` loop, with `END_SKY` replaced by a parameter.
   - The overlay must call `prepareFrame()` itself, because the sky pass can be skipped when `shouldRenderSky` is false (boss fog).
2. **Keep the current CUSTOM_SKY draw.** It's cheap and feeds pack reflections. Add `renderOverlay(CameraRenderState cs)`, which uses `modelView = new Matrix4f(cs.viewRotationMatrix)`. The sky pass got the same matrix from `RenderSystem.getModelViewMatrixCopy()` after the push in `LevelRenderer.render`, and that push has already been popped by now.
3. **New `GameRendererMixin`** (add it to `galactica.mixins.json` under `client`):
   ```java
   @Inject(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V", at = @At("HEAD"))
   private void galactica$spaceOverlay(CameraRenderState cam, PlayerRenderState p, OptionsRenderState o, boolean consistentDepth, CallbackInfo ci) {
       if (SpaceSkyRenderer.active() && IrisCompat.packInUse()) SpaceSkyRenderer.renderOverlay(cam);
   }
   ```
   `IrisCompat.packInUse()` should be `FabricLoader.getInstance().isModLoaded("iris") && IrisApi.getInstance().isShaderPackInUse()`. Iris is already `compileOnly` in `build.gradle`, so keep this in a separate class so it never loads without Iris.
4. **Optional see-through-glass mode.**
   - Problem: with only the `depthtex0` test, pixels behind glass or water show the pack's sky, which matters for ship windows.
   - Fix: also sample Iris's `depthtex1`. Reach it with a `@Pseudo` accessor on `net.irisshaders.iris.pipeline.IrisRenderingPipeline`, private final field `renderTargets`, then call `RenderTargets.getDepthTextureNoTranslucents()`.
   - Cache a `createTextureView` for it and recreate the view when the texture object changes, because a resize recreates it.
   - Shader rule: if `depth0 == FAR` draw the full sky. Otherwise, if `depth1 == FAR`, draw the sky at about 0.7 alpha.
   - This depends on Iris internals, so treat it as fragile.
5. **Verify in game.** Add a debug flag that sets `fragColor = vec4(1,0,0,1)` wherever depth equals far. This confirms the 1.0 convention on the user's Iris/Complementary setup before shipping.

## Risks
- **Vulkan backend:** Iris's `MixinShaderManager_Overrides` casts to `GlDevice` and runs GL calls everywhere, so packs only work on GL. On Vulkan, `isShaderPackInUse()` is false and the overlay stays off. The shader uses vanilla's dialect, so it would compile on Vulkan anyway.
- **Depth convention:** the 1.0 value comes from `UndoReverseZFive`. A future Iris build could change it, so keep it behind the `FAR_DEPTH` define.
- **TAA:** Complementary jitters its gbuffers, so the depth mask is sub-pixel jittered while the final image is resolved. Expect a thin, shimmering fringe of the pack's sky colour along silhouettes. If that bothers us, soften it with a 3×3 "fraction of far" neighbourhood alpha.
- **What the overlay covers:**
  - The pack's sky and its volumetric clouds are drawn colour-only in deferred/composite, so they get covered. That's the point.
  - Clouds drawn as real geometry that writes depth would not be covered. Disable clouds through the dimension attributes.
  - LODs from Distant Horizons or Voxy live in their own depth textures (`dhDepthTex`, `vxDepthTex*`). Main depth reads far there, so the overlay would paint over LODs. In the void space dimension that's acceptable.
- **No pack effects on our sky:** no bloom on bright stars and no grading. The PSF sprites already fake glow. The pack's fog on distant blocks still uses its overworld colour; that's a separate problem.
- **Hand and HUD:** they stay correct. Iris writes hand depth before finalize, and the vanilla hand and HUD draw after our injection.

## DECISION / implementation plan

[@aigoatsamuel]

Three investigations agree, and the bytecode backs them. The fix is a post-composite overlay drawn at `GameRenderer.render3dHud` HEAD, masked by scene depth. The dimension stays on world0. No remap.

# Spec: Galactica space sky under Iris shader packs

## 0. Decision

**Why not a remap.**
- Remapping to the End profile (world1) wipes our sky in Complementary.
  - `gbuffers_skytextured` outputs a flat `endSkyColor`.
  - `deferred1` then sets `color.rgb = endSkyColor + GetEnderStars` on every pixel with `z0==1`.
- world-1 (Nether) discards our output and paints `netherColor`.
- Keep `skybox: "overworld"`, which loads world0. Do not add an `Iris.getCurrentDimension` mixin.

**Why not fix it in the gbuffers path.**
- Complementary's world0 squares our colours (`color.rgb *= color.rgb * smoothstep1(...)`) and blacks out everything below the horizon.
- Then `deferred1` mixes in volumetric clouds wherever `z0 > 0.56`, sky included. `composite1` adds light shafts on sky.
- None of this can be switched off from the mod side. Only the user's pack options can.

**What we do.**
1. Keep the existing CUSTOM_SKY draw. It is cheap, still feeds pack reflections, and is the only path when no pack is active.
2. Add an overlay. After Iris has run composite and final, redraw the same sky layers into the main colour target. Draw only where the scene depth reads "far".

**Why this works across packs.** Complementary, BSL, Photon and Sildur's all composite their sky and clouds colour-only into colortex, then present through `final` or a copy into the main colour target. None of them writes main depth during composite. So a depth mask taken after the final pass is pack-agnostic.

**How it degrades.**
- No Iris, or no pack: the overlay is off and the current behaviour stays.
- Vulkan backend: Iris packs are GL-only, `isShaderPackInUse()` is false, and the overlay is off.

## 1. Facts verified with javap

Minecraft classes come from `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7e9a32a5b8/26.3/minecraft-merged-7e9a32a5b8-26.3.jar`. Iris classes come from `iris-1.11.7+26.3-fabric.jar`.

**`GameRenderer`**
- Hook target: `private void render3dHud(CameraRenderState, PlayerRenderState, OptionsRenderState, boolean)`, descriptor `(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V`.
- `renderLevel()V` bytecode:
  - offset 385: `invokevirtual LevelRenderer.render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V`
  - offset 396: `invokevirtual render3dHud`. It is unconditional; offset 399 is `return`.
- Decompiled `render3dHud` (dec line 712) starts by setting up `hudProjection`. At HEAD the level projection is still bound.
- `mainRenderTarget()` has descriptor `()Lcom/mojang/blaze3d/pipeline/RenderTarget;`.

**`LevelRenderer`**
- The sky pass model-view is `modelViewStack.mul(cameraState.viewRotationMatrix)` (dec line 217), popped at line 311.
- So the overlay uses `new Matrix4f(cameraState.viewRotationMatrix)`. `CameraRenderState` exposes `public Matrix4f viewRotationMatrix` and `projectionMatrix`.

**`RenderPipeline$Builder`** (`com.mojang.renderpearl.api.pipeline`) has:
- `withLocation(Identifier)`
- `withVertexShader(String)`
- `withFragmentShader(Identifier)`
- `withShaderDefine(String, float)`
- `withBindGroupLayout(BindGroupLayout)`
- `withDepthStencilState(...)`
- `build()`

**`BindGroupLayouts`** has `GLOBALS`, `PROJECTION`, `DYNAMIC_TRANSFORMS` and `SAMPLER0_SAMPLER1`.

**`ColorTargetState`**
- Constructor `ColorTargetState(BlendFunction)`.
- `DEFAULT` is `new ColorTargetState(Optional.empty(), RGBA8_UNORM, 15)`, which means no blend.

**`CommandEncoder`** has `createRenderPass(Supplier<String>, GpuTextureView, Optional<Vector4fc>)`, a colour-only pass.

**`RenderPass`** has `setUniform(String, GpuTextureView, GpuSampler)`.

**`RenderTarget`** has `getDepthTextureView()` and `getColorTextureView()`.

**`RenderSystem`** has:
- `getSamplerCache()`
- `getCompiledPipeline(RenderPipeline)`
- `bindDefaultUniforms(RenderPass)`
- `getProjectionMatrixBuffer()`

**Vanilla precedent.** `RenderPipelines.INTEGRATE_DEPTH` samples the main depth texture through a plain `sampler2D` with `texelFetch(...).r`. That proves depth is bindable as a sampler.

**Iris facts**
- `net.irisshaders.iris.api.v0.IrisApi.getInstance()` and `isShaderPackInUse()Z`.
- `Iris.isPackInUseQuick()Z`.
- `UndoReverseZFive`, a mixin on the GL command encoder (out3 decompile, lines 27/52/66/73): when `Iris.isPackInUseQuick() && ImmediateState.isRenderingLevel`, every depth clear inside `LevelRenderer.render` becomes `1.0 - clear`.
  - So with a pack active, empty sky reads **1.0** in main depth.
  - `isRenderingLevel` is false again at `render3dHud`, so our colour-only pass is untouched.
- Iris's `MixinGameRenderer` injects into `renderLevel` only at TAIL (`iris$runColorSpace`). Nothing in Iris touches `render3dHud`. A grep of the decompiled Iris sources finds no reference.

**Vanilla clouds** (`LevelRenderer` dec lines 421/607/632/815) are skipped when `ARGB.alpha(cloudColor) == 0`. `space.json` already sets `cloud_color` to `#00000000`, so no geometry clouds write depth. Only the pack's composite clouds exist, and the overlay covers those.

## 2. Files

### 2.1 New: `src/main/java/dev/galactica/client/sky/SpacePipelines.java`

```java
// SpacePipelines.java
package dev.galactica.client.sky;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

public final class SpacePipelines {
    private SpacePipelines() {}

    private static RenderPipeline.Builder base(String path, float farDepth) {
        return RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
                .withLocation(Identifier.fromNamespaceAndPath("galactica", path))
                .withVertexShader("core/position_tex_color")
                .withFragmentShader(Identifier.fromNamespaceAndPath("galactica", "core/space_overlay"))
                .withShaderDefine("FAR_DEPTH", farDepth)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS);
        // No depth-stencil state, same as END_SKY: no depth test, no depth write.
    }

    /** Milky Way sphere: writes opaque so nothing of the pack's sky or clouds bleeds through. */
    public static final RenderPipeline OVERLAY_BASE = base("pipeline/space_overlay_base", 1.0f)
            .withShaderDefine("OPAQUE_BASE")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    /** Stars, near bodies, planets: blended on top of the base. */
    public static final RenderPipeline OVERLAY = base("pipeline/space_overlay", 1.0f)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();
}
```

- `FAR_DEPTH = 1.0` follows from `UndoReverseZFive`. Leave the 0.0 variant out; nothing uses it.
- Unverified: whether `withVertexShader("core/position_tex_color")` resolves to the `minecraft` namespace. It does for `END_SKY`, which passes the same string. `withLocation(Identifier)` overload: verified.
- Compile on first use through `RenderSystem.getCompiledPipeline`. Warm both pipelines on the first space frame, outside any open pass.

### 2.2 New: `src/main/resources/assets/galactica/shaders/core/space_overlay.fsh`

```glsl
// space_overlay.fsh
#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;   // layer texture
uniform sampler2D Sampler1;   // main-target depth, written by Iris (far = FAR_DEPTH)

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

void main() {
    // Pixels with any scene geometry (ship blocks, hand, entities) keep the pack's shading.
    if (texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r < FAR_DEPTH) discard;
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
#ifdef OPAQUE_BASE
    fragColor = vec4(color.rgb, 1.0);
#else
    if (color.a == 0.0) discard;
    fragColor = color;
#endif
#ifdef GALACTICA_OVERLAY_DEBUG
    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
#endif
}
```

- The test is `< FAR_DEPTH` rather than `!=`. That tolerates 24-bit and 32-bit-float rounding on a clear value of 1.0.
- `GALACTICA_OVERLAY_DEBUG`: when the system property `-Dgalactica.overlay.debug=true` is set, define it through a third pipeline built with `.withShaderDefine("GALACTICA_OVERLAY_DEBUG")`.

### 2.3 Change: `src/main/java/dev/galactica/client/sky/SpaceSkyRenderer.java`

Refactor `render()` (currently lines 83-135) into three parts.

**`prepareFrame()`** (private static)
- Holds everything from `mgr.update(mc)` through the texture-view resolution:
  - `takeSky`/`uploadGalaxy`, `takeStars`/`rebuildStars`, `buildSphere`
  - `PlanetSprites.beginFrame`, `rebuildNear`, `rebuildPlanets`, `PlanetSprites.evict`
  - resolving `galaxyView`, `psfView` and `discView` into static fields
- Guard it with `private static long preparedFrame = -1`, keyed on a per-frame counter such as `mc.getFrameTimeNs()` or a counter incremented in the mixin. Unverified: the exact accessor; use whichever exists in 26.3.
- It runs at most once per frame, and every upload happens before any pass opens.
- The overlay calls it too, because the sky pass is skipped when `shouldRenderSky` is false (dec line 274).

**`drawLayers(RenderPass pass, Matrix4f modelView, RenderPipeline basePipe, RenderPipeline layerPipe, Consumer<RenderPass> extraBinds)`**
- Holds the current body of the try block:
  - the Milky Way draw uses `basePipe`
  - the stars, near-bodies and `RESOLVED` planet draws use `layerPipe`
- Change `draw(...)` to take the pipeline as a parameter instead of hard-coding `RenderPipelines.END_SKY` (current line 141), and do the same for the planet loop (line 126).
- Call `extraBinds.accept(pass)` after every `setPipeline`, because uniform bindings are per pipeline. It binds `Sampler1` for the overlay and does nothing for the sky pass.

**`render()`**, unchanged behaviour:

```java
public static void render() {
    prepareFrame();
    // existing pass: color + depth attachments, END_SKY for every layer
    drawLayers(pass, RenderSystem.getModelViewMatrixCopy(), RenderPipelines.END_SKY, RenderPipelines.END_SKY, p -> {});
}
```

**`renderOverlay(CameraRenderState cam)`**, new:

```java
public static void renderOverlay(CameraRenderState cam) {
    prepareFrame();
    RenderTarget rt = Minecraft.getInstance().gameRenderer.mainRenderTarget();
    GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    Matrix4f modelView = new Matrix4f(cam.viewRotationMatrix);
    try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass(() -> "Galactica space overlay", rt.getColorTextureView(), Optional.empty())) {
        RenderSystem.bindDefaultUniforms(pass);
        drawLayers(pass, modelView, SpacePipelines.OVERLAY_BASE, SpacePipelines.OVERLAY,
                p -> p.setUniform("Sampler1", rt.getDepthTextureView(), nearest));
    }
}
```

- The pass has no depth attachment, so the depth texture is never both bound and attached.
- Brightness stays as is. After the final pass the target is display-referred, the same domain as the no-shader path. The `encode()` tonemap already in the file applies, and no sqrt pre-compensation is needed.

### 2.4 New: `src/main/java/dev/galactica/client/compat/IrisCompat.java`

```java
// IrisCompat.java
package dev.galactica.client.compat;

import net.fabricmc.loader.api.FabricLoader;

public final class IrisCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("iris");
    private IrisCompat() {}

    public static boolean packInUse() {
        return LOADED && Inner.packInUse();
    }

    // Separate holder: IrisApi is resolved only when Iris is present (compileOnly dependency).
    private static final class Inner {
        static boolean packInUse() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
```

### 2.5 New: `src/main/java/dev/galactica/mixin/client/GameRendererMixin.java`

```java
// GameRendererMixin.java
package dev.galactica.mixin.client;

import dev.galactica.client.compat.IrisCompat;
import dev.galactica.client.sky.SpaceSkyRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs after LevelRenderer.render has returned, which means after Iris composite and final,
 * and before the hand/HUD depth clear. The shader pack's sky and clouds are already in the
 * main colour target, and scene depth is still intact.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
            at = @At("HEAD"))
    private void galactica$spaceOverlay(CameraRenderState cam, PlayerRenderState player,
                                        OptionsRenderState options, boolean consistentDepth, CallbackInfo ci) {
        if (SpaceSkyRenderer.active() && IrisCompat.packInUse()) SpaceSkyRenderer.renderOverlay(cam);
    }
}
```

**Guard.** `SpaceSkyRenderer.active()` covers `level != null && dimension == galactica:space`. `IrisCompat.packInUse()` covers Iris loaded and a pack in use. The target is vanilla, so `@Pseudo` and `remap=false` are not needed. `defaultRequire: 1` applies, which is fine because the descriptor is verified.

### 2.6 Change: `src/main/resources/galactica.client.mixins.json`

Change `"client"` to `["SkyRendererMixin", "GameRendererMixin"]`.

### 2.7 Unchanged

- `data/galactica/dimension_type/space.json` keeps `skybox: "overworld"`, which keeps world0. The Iris horizon disc (`IrisRenderingPipeline.onBeginClear`) is covered by the overlay, so it does not matter.
- No mixin on `Iris.getCurrentDimension`.
- `SkyRendererMixin` is untouched.

## 3. Optional phase 2: sky through glass and water (fragile)

- **Problem.** Behind translucent geometry, `depthtex0` is not far, so ship windows show the pack's sky.
- **Fix.**
  - Use a `@Pseudo @Mixin(targets="net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap=false)` accessor on private final field `renderTargets`.
  - Call `RenderTargets.getDepthTextureNoTranslucents()` to get `depthtex1`, "Depth / Opaque".
  - Bind it as `Sampler2`. That needs `SAMPLER0_SAMPLER1_SAMPLER2`, which exists in `BindGroupLayouts`.
  - Rule: if `d0` is far, draw full. Otherwise, if `d1` is far, draw the layers at alpha 0.7.
  - Cache the `GpuTextureView` and recreate it when the texture identity changes, because a resize recreates it.
- **Unverified:**
  - field name and type against javap
  - that `getDepthTextureNoTranslucents` exists in 1.11.7
- Gate it behind config, default off.

## 4. Known limits

- **TAA fringe.** Complementary jitters the projection, so a 1 px band of the pack's sky colour may shimmer along block silhouettes against the sky. Mitigation if visible: a 3×3 `texelFetch` count of far taps, with alpha = fraction. Apply it only to the `OVERLAY_BASE` draw.
- **The sky gets no pack grading**, no bloom and no TAA. It looks exactly like the shaders-off path, which keeps it consistent.
- **The pack still lights blocks.** Blocks, ships and the hand keep the pack's overworld shading, fog and light shafts. That is separate work, and the black fog attributes already limit it.
- **LOD mods.** Distant Horizons and Voxy LODs read far in main depth and would be painted over. That is acceptable in a void dimension.
- **Depth convention.** It depends on `UndoReverseZFive`. If a future Iris changes the convention, the debug run in §5.3 catches it.

## 5. Test plan (`runAutotestIris`, run dir `runs/client-iris`, pack `ComplementaryReimagined_r5.9.3.zip` already set in `config/iris.properties`)

1. **Baseline.** Copy the current `runs/client-iris/screenshots/galactica_*_iris.png` (01-16) to `screenshots/before/`.
2. **Build and run.** `./gradlew runAutotestIris`. In `runs/client-iris/logs/latest.log`, check:
   - no `space_overlay` shader compile or link errors
   - no mixin apply failure for `GameRendererMixin`
   - `[autotest] screenshot` lines for all shots
3. **Mask check.**
   - Re-run with `-Dgalactica.overlay.debug=true`. Add it to `vmArgs` temporarily, or use a new `autotestIrisDebug` run config.
   - Expect solid red everywhere the sky is visible, and the pack-shaded ship, blocks and hand intact.
   - If the frame is entirely red or entirely unchanged, the far value is wrong. Log a `texelFetch` probe, or flip `FAR_DEPTH` to 0.0 and retest.
4. **Visual check.** Compare the new shots against `before/` and against `runs/client/screenshots` (the non-Iris autotest):
   - Milky Way, stars and planets match the non-Iris shots in colour and brightness.
   - No blue-grey wash and no volumetric clouds over the sky.
   - Warp shots 13-15 still render correctly.
5. **Regressions.**
   - `./gradlew runAutotest` (no Iris): pixel-identical to the previous non-Iris screenshots, because the overlay guard is false.
   - Iris with shaders disabled (`enableShaders=false`): same expectation.
   - Overworld with the pack on: unchanged. The guard is false outside `galactica:space`.
6. **Cross-pack (manual).** Drop BSL v10, Photon and Sildur's Vibrant into `runs/client-iris/shaderpacks`, switch `shaderPack=` and repeat step 2. Expect the sky to show in all four. Expect a weak TAA fringe in Photon, which has strong TAA.
7. **Performance.** Compare F3 frame time in space with the overlay on and off, using a temporary toggle. The expected cost is one extra pass that reuses the same buffers, below 0.3 ms.

## 6. Unverified items

- the per-frame counter accessor used to guard `prepareFrame`
- the default namespace for `withVertexShader(String)`, by analogy with `END_SKY`
- the §3 Iris internals (field name and method)
- the TAA fringe size, until the in-game screenshots exist
- the claim that BSL, Photon and Sildur's do not write main depth during composite (inferred from how Iris works; only Complementary was read)

Everything else in §1 was checked with javap or read in the decompiled sources.