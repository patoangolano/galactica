[@aigoatsamuel]

I checked the 26.3 rendering API against the merged jar, decompiled with Vineflower. Everything below was copied from source or javap unless it is marked UNCONFIRMED.

# MC 26.3 Fabric: custom sky rendering cheat-sheet (confirmed against the jars)

**Sources used**
- The decompiled sources dir is **partial**: 667 files, with no RenderPipelines, SkyRenderer, Minecraft, GameRenderer or LevelRenderer.
- Authoritative jar: `C:/Users/Manec/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar`
- Decompiled copies (Vineflower 1.11.2): `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/dec/` (SkyRenderer, RenderPipelines, RenderSystem, DynamicGpuData, DynamicTexture, LevelRenderer, GameRenderer, Camera, GuiGraphicsExtractor, ShaderManager, PipelineCache in `dec/pc/`)
- Fabric rendering-v1 inside fabric-api 0.161.0+26.3 is version 27.0.14: `C:/Users/Manec/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-rendering-v1/27.0.14+901a437c5d/b7b356249f2c16fa58410e8c7694c75f0468a403/fabric-rendering-v1-27.0.14+901a437c5d.jar`
- `ResourceLocation` is now **`net.minecraft.resources.Identifier`**. Factories: `fromNamespaceAndPath(String,String)`, `withDefaultNamespace(String)`, `parse(String)`, `tryParse(String)`.

---

## 1. RenderPipeline (`com.mojang.renderpearl.api.pipeline.RenderPipeline`)

**Builder**
```
public static RenderPipeline.Builder builder(RenderPipeline.Snippet... snippets)
Builder withLocation(String)            // -> Identifier.withDefaultNamespace
Builder withLocation(Identifier)
Builder withVertexShader(String|Identifier)
Builder withFragmentShader(String|Identifier)
Builder withShaderDefine(String) / (String,int) / (String,float)
Builder withBindGroupLayout(BindGroupLayout)
Builder withPolygonMode(PolygonMode)
Builder withCull(boolean)               // default true
Builder withColorTargetState(ColorTargetState)          // index 0
Builder withColorTargetState(int index, ColorTargetState)
Builder withColorTargetStates(int start, int end, Supplier<ColorTargetState>)
Builder withUnusedColorTargetState(int)
Builder withDepthStencilState(DepthStencilState) / (Optional<DepthStencilState>)
Builder withVertexBinding(int bindingIndex, VertexFormat)   // com.mojang.renderpearl.api.vertex.VertexFormat
Builder withPrimitiveTopology(PrimitiveTopology)
Builder withPushConstantSize(int)       // max 128
Builder withSnippet(Snippet)
Snippet buildSnippet()
RenderPipeline build()   // throws if location, vertex shader, fragment shader or topology is missing; max 16 vertex attribs
```

**Pipeline state rules**
- If no depth-stencil state is set, the pipeline has `getDepthStencilState() == null`.
- All vanilla sky pipelines use null. This means no depth test or write. The meaning of null is inferred from vanilla usage: UNCONFIRMED in the backend.
- With no color target the pipeline has 0 attachments, so always set one.

**Related types**
- `ColorTargetState(Optional<BlendFunction> blendFunction, GpuFormat format, int writeMask)`
  - `new ColorTargetState(BlendFunction)` gives RGBA8_UNORM with mask 15.
  - `ColorTargetState.DEFAULT` means no blend.
  - Write mask constants: `WRITE_COLOR=7`, `WRITE_ALL=15`, `WRITE_NONE=0`.
- `DepthStencilState(CompareOp depthTest, boolean writeDepth[, float biasScale, float biasConst])`
  - `DepthStencilState.DEFAULT = (GREATER_THAN_OR_EQUAL, true)`.
  - **The engine uses REVERSED-Z.** `Projection.getMatrix` swaps near and far, so "closer" means GREATER.
- `CompareOp`: ALWAYS_PASS, LESS_THAN, LESS_THAN_OR_EQUAL, EQUAL, NOT_EQUAL, GREATER_THAN_OR_EQUAL, GREATER_THAN, NEVER_PASS.
- `PrimitiveTopology`: LINES, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS.
  - `indexCount(v)` returns v/4*6 for QUADS.
- `PolygonMode` exists, with default FILL.

**BlendFunction constants** (`BlendFunction(BlendEquation color, BlendEquation alpha)`)

| Constant | Factors |
|---|---|
| `LIGHTNING` | (SRC_ALPHA, ONE). Alpha-weighted additive. |
| `ADDITIVE` | (ONE, ONE). Yes, it exists. |
| `OVERLAY` | color (SRC_ALPHA, ONE), alpha (ONE, ZERO). Used by vanilla STARS and CELESTIAL. |
| `TRANSLUCENT` | (SRC_ALPHA, 1-SRC_ALPHA, ONE, 1-SRC_ALPHA) |
| `TRANSLUCENT_PREMULTIPLIED_ALPHA` | |
| `GLINT` | |
| `ENTITY_OUTLINE_BLIT` | |
| `INVERT` | |
| `MAX` | (ONE, ONE, BlendOp.MAX) |

Constructors: `(BlendFactor src, BlendFactor dst)`, `(src, dst, BlendOp)`, `(srcC, dstC, srcA, dstA)`, `(srcC, dstC, opC, srcA, dstA, opA)`, `(BlendEquation)`.

**Snippets in `net.minecraft.client.renderer.RenderPipelines`**
- Only these are PUBLIC: `OIT_DEPTH_BOUNDS_SNIPPET`, `OIT_TRANSMITTANCE_SNIPPET`, `OIT_ACCUMULATE_SNIPPET`, `POST_PROCESSING_SNIPPET`, `GLINT_SNIPPET`, `GLINT_SPECIAL_SNIPPET`, `ANIMATE_SPRITE_SNIPPET`.
- `GLOBALS_SNIPPET` and `MATRICES_FOG_SNIPPET` are **private**. Rebuild them from `net.minecraft.client.renderer.BindGroupLayouts` (all public static final BindGroupLayout).

| BindGroupLayout | Uniform name and type |
|---|---|
| `GLOBALS` | "Globals" (UBO) |
| `PROJECTION` | "Projection" (UBO) |
| `DYNAMIC_TRANSFORMS` | "DynamicTransforms" (UBO) |
| `FOG` | "Fog" (UBO) |
| `LIGHTING` | "Lighting" (UBO) |
| `SAMPLER0` / `SAMPLER1` / `SAMPLER2` | "Sampler0" / "Sampler1" / "Sampler2" (COMBINED_IMAGE_SAMPLER) |

Also available: `SAMPLER0_SAMPLER1`, `SAMPLER0_SAMPLER2`, `SAMPLER0_SAMPLER1_SAMPLER2`, `IN_SAMPLER`, `CLOUD_INFO`, and others.

Custom layout: `BindGroupLayout.builder().withUniform("MyUbo", UniformType.UNIFORM_BUFFER).build()`. `UniformType` values are COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER and TEXEL_BUFFER (TEXEL_BUFFER needs the GpuFormat overload).

**Vanilla sky pipelines (verbatim pattern)**
```
SKY:  builder(MATRICES_FOG_SNIPPET).withLocation("pipeline/sky").withVertexShader("core/sky").withFragmentShader("core/sky")
      .withVertexBinding(0, DefaultVertexFormat.POSITION).withPrimitiveTopology(TRIANGLE_FAN).withColorTargetState(ColorTargetState.DEFAULT)
STARS: builder(GLOBALS_SNIPPET)+PROJECTION+DYNAMIC_TRANSFORMS, "core/stars", new ColorTargetState(BlendFunction.OVERLAY), POSITION, QUADS
CELESTIAL: ...+SAMPLER0, "core/position_tex", OVERLAY, POSITION_TEX, QUADS
END_SKY: ...+SAMPLER0, "core/position_tex_color", TRANSLUCENT, POSITION_TEX_COLOR, QUADS
```

**Custom pipeline (compiles)**
```java
import net.minecraft.client.renderer.BindGroupLayouts;
public static final RenderPipeline GALAXY_SKY = RenderPipeline.builder()
    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
    .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
    .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
    .withLocation(Identifier.fromNamespaceAndPath("galactica", "pipeline/galaxy_sky"))
    .withVertexShader(Identifier.fromNamespaceAndPath("galactica", "core/galaxy_sky"))   // assets/galactica/shaders/core/galaxy_sky.vsh
    .withFragmentShader(Identifier.fromNamespaceAndPath("galactica", "core/galaxy_sky")) // .fsh
    .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
    .withPrimitiveTopology(PrimitiveTopology.QUADS)
    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
    .withCull(false)
    .build();
```

**Registration: none needed**
- `RenderSystem.getCompiledPipeline(RenderPipeline)` checks `currentPipelineCache`, then `fallbackPipelineCache`.
- `PipelineCache.get()` **compiles lazily on a miss**: `device.compilePipeline(...).join()`. Any pipeline object works, and the first use blocks briefly.
- `RenderPipelines.register` is private and only feeds `requiredPipelines()`, which ShaderManager precompiles on reload.
- There is no Fabric registration hook. Fabric only has `FabricRenderPipeline.usePipelineDrawModeForGui()`.
- To precompile, add a mixin that appends to the result of `RenderPipelines.requiredPipelines()`. This is optional and UNCONFIRMED as a hook.
- Use `getCompiledPipelineNullable` to avoid an exception on a shader error.

**Shaders**
- Path: `assets/<ns>/shaders/<path>.vsh` / `.fsh` (ShaderType VERTEX ".vsh", FRAGMENT ".fsh").
- Vanilla style:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>  // uniform DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; };
#include <minecraft:projection.glsl>         // uniform Projection { mat4 ProjMat; };
#include <minecraft:globals.glsl>            // Globals { ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime; vec2 ScreenSize; int MenuBlurRadius; int UseRgss; }
layout(location = 0) in vec3 Position; layout(location = 1) in vec2 UV0; layout(location=2) in vec4 Color; // order = VertexFormat element order
uniform sampler2D Sampler0;
layout(location = 0) out vec4 fragColor;
```
Varyings use `layout(location=N) out/in`.

---

## 2. Textures from CPU pixels

**`com.mojang.blaze3d.platform.NativeImage`**
```
NativeImage(int w, int h, boolean zero)                 // RGBA
NativeImage(NativeImage.Format, int, int, boolean)
void setPixel(int x, int y, int argb)    // converts ARGB->ABGR
void setPixelABGR(int x, int y, int abgr)
int getPixel(int,int); void fillRect(int,int,int,int,int); ByteBuffer getPixelBytes(); void close()
```

**`net.minecraft.client.renderer.texture.DynamicTexture extends AbstractTexture`**
```
DynamicTexture(Supplier<String> label, NativeImage image)      // creates and uploads immediately
DynamicTexture(String label, int w, int h, boolean zero)       // no upload
DynamicTexture(Supplier<String> label, int w, int h, boolean zero)
void upload(); NativeImage getPixels(); void setPixels(NativeImage)
```
- Internally it calls `device.createTexture(label, 5 /*COPY_DST|TEXTURE_BINDING*/, GpuFormat.RGBA8_UNORM, w, h, 1, 1)`.
- Its sampler is `getSamplerCache().getRepeat(FilterMode.NEAREST)`.
- The `sampler` field is **protected**. To get linear filtering, either subclass it or pass your own sampler to setUniform.

**`AbstractTexture`**: `GpuTexture getTexture()`, `GpuTextureView getTextureView()`, `GpuSampler getSampler()`, `close()`.

**`TextureManager`** (`Minecraft.getInstance().getTextureManager()`, accessor name UNCONFIRMED): `void register(Identifier, AbstractTexture)`, `AbstractTexture getTexture(Identifier)`, `void release(Identifier)`.

**Samplers**
- `com.mojang.blaze3d.systems.SamplerCache` via `RenderSystem.getSamplerCache()`:
  - `getSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, boolean mipmaps)`
  - `getClampToEdge(FilterMode[, boolean])`
  - `getRepeat(FilterMode[, boolean])`
- `com.mojang.renderpearl.api.textures.AddressMode`: {REPEAT, CLAMP_TO_EDGE}
- `FilterMode`: {NEAREST, LINEAR}
- Raw sampler: `GpuDevice.createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod)`

**Raw texture route (for example HDR)**
- `GpuDevice.createTexture(Supplier<String>|String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels)` and `createTextureView(GpuTexture[, baseMip, mipLevels])`.
- `GpuTexture` usage flags: COPY_DST=1, COPY_SRC=2, TEXTURE_BINDING=4, RENDER_ATTACHMENT=8, CUBEMAP_COMPATIBLE=16.
- `GpuFormat` includes RGBA8_UNORM, RGBA16_FLOAT, RGBA32_FLOAT, RG11B10_FLOAT, R16_FLOAT, and others.
- Upload with `CommandEncoder.writeToTexture(GpuTexture dst, ByteBuffer src, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height)`.

```java
NativeImage img = new NativeImage(2048, 1024, false);
for (int y = 0; y < 1024; y++) for (int x = 0; x < 2048; x++) img.setPixel(x, y, 0xFF000000 | rgb(x, y));
DynamicTexture tex = new DynamicTexture(() -> "galactica:milkyway", img);   // uploads
Identifier id = Identifier.fromNamespaceAndPath("galactica", "dynamic/milkyway");
Minecraft.getInstance().getTextureManager().register(id, tex);
GpuSampler lin = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
renderPass.setUniform("Sampler0", tex.getTextureView(), lin);
// later edits: tex.getPixels().setPixel(...); tex.upload();
```

---

## 3. Vertex and index buffers

**Builders**
- `com.mojang.blaze3d.vertex.ByteBufferBuilder`: `static exactlySized(int)`, `ByteBufferBuilder(int)`, `(int,long)`, `close()`.
- `com.mojang.blaze3d.vertex.BufferBuilder(ByteBufferBuilder, PrimitiveTopology, VertexFormat)`. It implements `VertexConsumer`:
  - `addVertex(float,float,float)`, `addVertex(Vector3fc)`, `addVertex(Matrix4fc,float,float,float)`
  - `setUv(float,float)`, `setColor(int argb)`, `setColor(int r,int g,int b,int a)`, `setColor(float,float,float,float)`, `setNormal`, `setLight`
  - `MeshData build()` (nullable) and `buildOrThrow()`
- `MeshData` (AutoCloseable): `ByteBuffer vertexBuffer()`, `indexBuffer()`, `MeshData.DrawState drawState()` (`.indexCount()`).
- `DefaultVertexFormat`: POSITION, POSITION_COLOR, POSITION_TEX, POSITION_TEX_COLOR, POSITION_COLOR_TEX_LIGHTMAP, POSITION_TEX_COLOR_NORMAL, POSITION_COLOR_NORMAL, and others. Use `.getVertexSize()`.

**GPU buffers**
- `GpuDevice.createBuffer(Supplier<String> label, int usage, ByteBuffer data)` / `createBuffer(Supplier<String>, int usage, long size)`.
- `GpuBuffer` USAGE flags:

| Flag | Value |
|---|---|
| MAP_READ | 1 |
| MAP_WRITE | 2 |
| HINT_CLIENT_STORAGE | 4 |
| COPY_DST | 8 |
| COPY_SRC | 16 |
| VERTEX | 32 |
| INDEX | 64 |
| UNIFORM | 128 |
| UNIFORM_TEXEL_BUFFER | 256 |
| INDIRECT_PARAMETERS | 512 |

  - **32 = VERTEX** (static sky discs). **40 = VERTEX|COPY_DST** (vanilla stars buffer, so it can be rewritten).
- `GpuBuffer.slice()`, `slice(long offset, long len)`, `map(boolean read, boolean write)` (needs the MAP_* usage), `size()`, `close()`.
- `GpuBufferSlice` record `(GpuBuffer buffer, long offset, long length)`.
- `MappedView` record `(slice, ByteBuffer data, Runnable onClose)` is AutoCloseable.

**Quad indices**
- `RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)` returns `RenderSystem.AutoStorageIndexBuffer`.
- Its methods: `GpuBuffer getBuffer(int indexCount)` (grows as needed), `IndexType type()`.

**Per-frame updates**
- Buffer created with usage 40: `RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buf.slice(), byteBuffer)`.
- Or, buffer created with MAP_WRITE: `try (var v = buf.map(false, true)) { v.data().put(...); }`.
- Or transient per-frame memory: `encoder.transientMemory().uploadGpu(ByteBuffer data, long alignment, int usage)`, which returns a GpuBufferSlice. Also `allocateGpuMapped(size, alignment, usage)`. Lifetime is presumably per frame: UNCONFIRMED.

```java
int n = 4 * starCount;
GpuBuffer vb; int idxCount;
try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * n)) {
    BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    for (Star s : stars) { /* 4x */ b.addVertex(x, y, z).setUv(u, v).setColor(argb); }
    try (MeshData mesh = b.buildOrThrow()) {
        idxCount = mesh.drawState().indexCount();
        vb = RenderSystem.getDevice().createBuffer(() -> "galactica stars", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertexBuffer());
    }
}
```

---

## 4. Transforms, projection and the sky pass

**`RenderSystem.getDynamicUniforms()`** returns `net.minecraft.client.renderer.DynamicGpuData`, which is not named DynamicUniforms.
```
GpuBufferSlice writeTransform(Matrix4f modelView)
GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator)
GpuBufferSlice writeTransform(Matrix4f modelView, Matrix4f textureMatrix)
GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator, Vector3f modelOffset, Matrix4f textureMatrix)
GpuBufferSlice writeTransform(DynamicGpuData.Transform)  // record (Matrix4fc modelView, Vector4fc colorModulator, Vector3fc modelOffset, Matrix4fc textureMatrix)
```

**Other RenderSystem methods**
- `static Matrix4f getModelViewMatrixCopy()`
- `static Matrix4fStack getModelViewStack()`
- `static GpuBufferSlice getProjectionMatrixBuffer()`
- `setProjectionMatrix(GpuBufferSlice, ProjectionType)`, `backupProjectionMatrix()`, `restoreProjectionMatrix()`
- `static void bindDefaultUniforms(RenderPass)` sets "Projection", "Fog", "Globals" and "Lighting" when present.
- `static GpuDevice getDevice()`

**Projection facts**
- `LevelRenderer` pushes the model-view stack and multiplies in `cameraState.viewRotationMatrix`. This is **rotation only, no translation**, so sky geometry is camera-centred.
- The projection is the main world projection: `Camera.update` calls `setupPerspective(0.05F, depthFar, fov, w, h)`.
- `depthFar = max(effectiveRenderDistanceChunks*16*4, cloudRange*16)`.
- `Projection.getMatrix` passes `near=zFar, far=zNear` (**reversed-Z**), and `isZZeroToOne` comes from DeviceInfo.
- **Geometry farther than depthFar is clipped.** Vanilla keeps the sky at a radius of 100 to 512. Draw celestial objects on a shell well inside depthFar, for example 100 to 400 units, and encode the real distance via scale and direction. Do not use real distances in vertex positions.
- The sky pass has a depth attachment, but the sky pipelines have no DepthStencilState, so they ignore depth.

**SkyRenderer (`net.minecraft.client.renderer.SkyRenderer`)**
- Entry point: `public void render(GpuBufferSlice skyFog, SkyRenderState state)`.
- It is invoked from `LevelRenderer.addSkyPass`, inside `frame.addPass("sky")`, only if `state.skybox != DimensionType.Skybox.NONE` and the fog type is not lava or powder snow.
- Skybox values: {NONE, OVERWORLD, END}. A custom dimension type needs skybox "overworld" (or "end") for the pass to run.
- **Fabric rendering-v1 27.0.14 has NO sky hook.** LevelRenderEvents has START_MAIN, AFTER_OPAQUE_TERRAIN, COLLECT_SUBMITS, AFTER_SOLID_FEATURES, AFTER_TRANSLUCENT_FEATURES, BEFORE_BLOCK_OUTLINE, BEFORE_GIZMOS, BEFORE/AFTER_TRANSLUCENT_TERRAIN, END_MAIN, plus extraction events.
- Use a mixin `@Inject(method="render", at=@At("HEAD"), cancellable=true)` on SkyRenderer. It has a public `skyRenderer()` getter on LevelRenderer.
- `SkyRenderer.renderTarget` is a private field, so take the target from `Minecraft.getInstance().gameRenderer.mainRenderTarget()`. The `mainRenderTarget()` name comes from LevelRenderer usage, and the target type is `com.mojang.blaze3d.pipeline.RenderTarget`.

**Render pass (`com.mojang.renderpearl.api.commands`)**
- `CommandEncoder.createRenderPass(Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor, @Nullable GpuTextureView depth, OptionalDouble clearDepth)`
- `RenderPass` methods:
  - `setPipeline(CompiledRenderPipeline)`
  - `setUniform(String, GpuBufferSlice|GpuBuffer)`, `setUniform(String, GpuTextureView, GpuSampler)`
  - `setVertexBuffer(int, GpuBufferSlice)`, `setIndexBuffer(GpuBuffer, IndexType)`
  - `drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance)`, `draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance)`
  - `pushDebugGroup(Supplier<String>)` / `popDebugGroup()`, `pushConstants(ByteBuffer)`, `close()`
  - The draw parameter names follow vanilla calls: UNCONFIRMED exact names.

```java
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
  @Inject(method = "render", at = @At("HEAD"), cancellable = true)
  private void galactica$sky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.dimension().equals(GalacticaDims.SPACE)) return;
    RenderSystem.setShaderFog(skyFog);
    RenderTarget rt = mc.gameRenderer.mainRenderTarget();
    try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
          .createRenderPass(() -> "galactica sky", rt.getColorTextureView(), Optional.empty(), rt.getDepthTextureView(), OptionalDouble.empty())) {
      RenderSystem.bindDefaultUniforms(pass);
      RenderSystem.AutoStorageIndexBuffer q = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
      pass.setPipeline(RenderSystem.getCompiledPipeline(GALAXY_SKY));
      pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1, 1, 1, 1)));
      pass.setUniform("Sampler0", tex.getTextureView(), linearSampler);
      pass.setVertexBuffer(0, vb.slice());
      pass.setIndexBuffer(q.getBuffer(idxCount), q.type());
      pass.drawIndexed(idxCount, 1, 0, 0, 0);
    }
    ci.cancel();
  }
}
```
- `RenderTarget.getColorTextureView()` and `getDepthTextureView()` are used verbatim in SkyRenderer.
- Iris 1.11.7+26.3 is in the cache. Whether Iris shader packs replace or wrap SkyRenderer.render or custom pipelines is UNCONFIRMED. Test with a pack active.

---

## 5. Level and camera

- `Minecraft.getInstance()`. Public fields: `level` (ClientLevel), `player`, `gameRenderer`, `levelRenderer`, `font` (Font).
- `Level.dimension()` returns `ResourceKey<Level>`. Vanilla keys: `Level.OVERWORLD`, `NETHER`, `END`.
- Camera:
  - `Minecraft.getInstance().gameRenderer.mainCamera()` (there is no `getMainCamera`).
  - `Camera` (`net.minecraft.client.Camera`): `Vec3 position()`, `BlockPos blockPosition()`, `float xRot()`, `float yRot()`, `float yaw()`, `Quaternionf rotation()`, `Matrix4f getViewRotationMatrix(Matrix4f dest)`, `Vector3fc forwardVector()`, `upVector()`, `leftVector()`, `float getFov()`.
  - The constant `PROJECTION_Z_NEAR = 0.05F` and the `depthFar` field are private. `CameraRenderState.depthFar` is public.

```java
public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("galactica", "space"));
ClientLevel lvl = Minecraft.getInstance().level;
boolean inSpace = lvl != null && lvl.dimension() == SPACE || (lvl != null && lvl.dimension().equals(SPACE));
Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
Vec3 p = cam.position(); Quaternionf q = cam.rotation();
```
`Registries` is `net.minecraft.core.registries.Registries`, and `Registries.DIMENSION` is a `ResourceKey<Registry<Level>>`.

---

## 6. HUD (Fabric) and GUI text

- `net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement`
  - Functional interface: `void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker)`.
- `HudElementRegistry` (static methods):
  - `addFirst(Identifier, HudElement)`, `addLast(Identifier, HudElement)`
  - `attachElementBefore(Identifier before, Identifier id, HudElement)`, `attachElementAfter(Identifier after, Identifier id, HudElement)`
  - `removeElement(Identifier)`, `replaceElement(Identifier, Function<HudElement,HudElement>)`
- `VanillaHudElements` IDs: MISC_OVERLAYS, CROSSHAIR, HOTBAR, HEALTH_BAR, CHAT, PLAYER_LIST, SUBTITLES, SCOREBOARD, BOSS_BAR, and others.
- **GuiGraphics is now `net.minecraft.client.gui.GuiGraphicsExtractor`.**
  - `text(Font, String|Component|FormattedCharSequence, int x, int y, int argb[, boolean dropShadow])`. The default is shadow=true, and the call is **skipped if alpha==0**, so always pass 0xFF in alpha.
  - `centeredText(Font, String|Component|FormattedCharSequence, int, int, int)`, `textWithWordWrap(Font, FormattedText, x, y, width, color[, shadow])`, `textWithBackdrop(Font, Component, x, y, width?, color)` (UNCONFIRMED param meaning).
  - `fill(int x0,int y0,int x1,int y1,int argb)`, `guiWidth()`, `guiHeight()`, `Matrix3x2fStack pose()`.
  - `blit(GpuTextureView, GpuSampler, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)` (UNCONFIRMED param order).
  - `blit(RenderPipeline, Identifier, int x, int y, float u, float v, int w, int h, int texW, int texH)`.

```java
HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("galactica", "nav_hud"),
    (g, dt) -> {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 p = mc.gameRenderer.mainCamera().position();
        g.fill(4, 4, 160, 30, 0x80000000);
        g.text(mc.font, String.format("%.1f ly", ly), 8, 8, 0xFFE0E8FF, false);
        g.centeredText(mc.font, Component.literal("GALACTICA"), g.guiWidth() / 2, 8, 0xFFFFFFFF);
    });
```

---

## Still UNCONFIRMED
- The backend's handling of a null DepthStencilState (inferred: depth disabled).
- How Iris interacts with a SkyRenderer mixin.
- `Minecraft.getTextureManager()` accessor name. The `register`/`getTexture` methods were verified, but the accessor was not.
- Lifetime of `TransientMemory` slices.
- Exact parameter names for `draw` and `drawIndexed`, and parameter order for `GuiGraphicsExtractor.blit(GpuTextureView, …)` and `textWithBackdrop`.