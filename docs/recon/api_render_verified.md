[@aigoatsamuel]

I checked every claim against the merged 26.3 jar (javap), Vineflower decompiles of the jar (vanilla, the GL/Vulkan backends and the frontend), the fabric-rendering-v1 27.0.14 jar and Iris 1.11.7+mc26.3. Three things in the old sheet were wrong: `PROJECTION_Z_NEAR` is public, a sky shell of 100–400 is not always safe, and the HEAD-cancel mixin does interact with Iris. Everything else held, with a few details added.

# MC 26.3 Fabric: custom sky rendering cheat-sheet (verified)

**Sources used**
- CONFIRMED: the decompiled sources dir `C:/Users/Manec/minecraft-claude/data/mcdev-mcp/tmp/mcdev-mcp/26.3-vanilla-26.3-sources` is partial. It has 667 .java files and none of RenderPipelines, SkyRenderer, Minecraft, GameRenderer or LevelRenderer.
- CONFIRMED: authoritative jar is `C:/Users/Manec/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar`.
- CONFIRMED: Vineflower 1.11.2 decompiles are in `.../scratchpad/dec/`.
  - New backend decompiles from this pass are in `.../scratchpad/check/xo/`: FrontendRenderPass, GlRenderPipeline, GlCommandEncoder, GlTransientMemory, Camera, Projection.
  - Vulkan: `.../scratchpad/check/xo2/VulkanRenderPipeline.java`.
  - Iris: `.../scratchpad/check/iro/`.
- CONFIRMED: fabric-api 0.161.0+26.3 pins fabric-rendering-v1 `27.0.14+901a437c5d` (checked in its .pom).
- CONFIRMED: `net.minecraft.resources.Identifier` has `fromNamespaceAndPath(String,String)`, `withDefaultNamespace(String)`, `parse(String)` and `tryParse(String)`. It also has `tryBuild(String,String)`, `withPath`, `withPrefix` and `withSuffix`.
- NEW: 26.3 ships both an OpenGL backend (`com.mojang.renderpearl.backend.opengl`) and a Vulkan backend (`...backend.vulkan`). Everything goes through `com.mojang.renderpearl.frontend.FrontendRenderPass` / `FrontendRenderPipeline`.

---

## 1. RenderPipeline (`com.mojang.renderpearl.api.pipeline.RenderPipeline`)

**Builder: CONFIRMED (javap)**
```
public static RenderPipeline.Builder builder(RenderPipeline.Snippet... snippets)
Builder withLocation(String)            // -> Identifier.withDefaultNamespace  (CONFIRMED)
Builder withLocation(Identifier)
Builder withVertexShader(String|Identifier)   // String -> withDefaultNamespace (CONFIRMED)
Builder withFragmentShader(String|Identifier)
Builder withShaderDefine(String) / (String,int) / (String,float)
Builder withBindGroupLayout(BindGroupLayout)  // stored in a HashSet (order not preserved)
Builder withPolygonMode(PolygonMode)          // default FILL
Builder withCull(boolean)                     // default true
Builder withColorTargetState(ColorTargetState)          // index 0
Builder withColorTargetState(int index, ColorTargetState)
Builder withColorTargetStates(int start, int endInclusive, Supplier<ColorTargetState>)
Builder withUnusedColorTargetState(int)
Builder withDepthStencilState(DepthStencilState) / (Optional<DepthStencilState>)
Builder withVertexBinding(int bindingIndex, VertexFormat)   // com.mojang.renderpearl.api.vertex.VertexFormat
Builder withPrimitiveTopology(PrimitiveTopology)
Builder withPushConstantSize(int)       // build() throws if > 128 ("Maximum push constant size is 128 bytes")
Builder withSnippet(Snippet)
Snippet buildSnippet()
RenderPipeline build()
```
- CONFIRMED: `build()` throws on a missing location, vertex shader, fragment shader or topology. It also throws when more than 16 vertex attributes are bound across all bindings, or when push constants exceed 128.
- NEW: `build()` also throws "Blend functions must currently be the same for all color targets" when two color targets have different BlendFunctions.

**Pipeline state rules**
- CONFIRMED: if no depth-stencil state is set, the pipeline gets `depthStencilState.orElse(null)`, so `getDepthStencilState() == null`.
- CONFIRMED: all vanilla sky pipelines (SKY, END_SKY, SUNRISE_SUNSET, STARS, CELESTIAL) leave depth-stencil null.
- FIXED (was UNCONFIRMED): a null depth state really does mean no depth test and no depth write, in both backends.
  - GL: `GlRenderPipeline` sets `depthEnabled = createInfo.depthStencilState() != null`. When it is false, `bind()` disables the depth test and uses `writeDepth=false`.
  - Vulkan: `VulkanRenderPipeline` only calls `depthTestEnable(true)` / `depthWriteEnable` inside `if (depthStencilState != null)`. The create-info is `calloc`'d, so both are false otherwise.
- FIXED (stronger than before): set a color target, and give it the same format as the target. With no color target the pipeline has 0 attachments.
  - `FrontendRenderPass.setPipeline` throws "Render pass color attachment count must match pipeline color target state count." It also throws "...format doesn't match pipeline format." when a target's `format()` differs from the attachment texture's format.
  - The main target is `MainTarget("Main", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)`, so RGBA8_UNORM is correct for the sky pass.

**Related types**
- CONFIRMED: `ColorTargetState(Optional<BlendFunction> blendFunction, GpuFormat format, int writeMask)` is a record.
  - `new ColorTargetState(BlendFunction)` gives RGBA8_UNORM with mask 15.
  - `ColorTargetState.DEFAULT` is `(Optional.empty(), RGBA8_UNORM, 15)`, meaning no blend.
  - Write-mask constants: `WRITE_RED=1`, `WRITE_GREEN=2`, `WRITE_BLUE=4`, `WRITE_ALPHA=8`, `WRITE_COLOR=7`, `WRITE_ALL=15`, `WRITE_NONE=0`. Also `MAX_COLOR_TARGETS=8`.
- CONFIRMED: `DepthStencilState(CompareOp depthTest, boolean writeDepth[, float depthBiasScaleFactor, float depthBiasConstant])`.
  - `DEFAULT = (GREATER_THAN_OR_EQUAL, true)`.
- CONFIRMED: the engine uses REVERSED-Z.
  - `Projection.getMatrix` uses `near = this.zFar; far = this.zNear`.
  - `RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE = 0.0`, and the LevelRenderer "clear" pass clears depth to `0.0`.
- CONFIRMED: `CompareOp` values are ALWAYS_PASS, LESS_THAN, LESS_THAN_OR_EQUAL, EQUAL, NOT_EQUAL, GREATER_THAN_OR_EQUAL, GREATER_THAN, NEVER_PASS.
- CONFIRMED: `PrimitiveTopology` values are LINES, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS.
  - `indexCount(v)` returns `v/4*6` for LINES and QUADS, and `v` for the others.
- CONFIRMED: `PolygonMode` is {FILL, WIREFRAME}, default FILL.

**BlendFunction constants: CONFIRMED (source)**

`BlendFunction` is a record: `BlendFunction(BlendEquation color, BlendEquation alpha)`.

| Constant | Factors |
|---|---|
| `LIGHTNING` | (SRC_ALPHA, ONE). Alpha-weighted additive. |
| `ADDITIVE` | (ONE, ONE) |
| `OVERLAY` | color (SRC_ALPHA, ONE), alpha (ONE, ZERO). Used by vanilla STARS and CELESTIAL. |
| `TRANSLUCENT` | (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA) |
| `TRANSLUCENT_PREMULTIPLIED_ALPHA` | (ONE, 1-SRC_ALPHA, ONE, 1-SRC_ALPHA) |
| `GLINT` | (SRC_COLOR, ONE, ZERO, ONE) |
| `ENTITY_OUTLINE_BLIT` | (SRC_ALPHA, 1-SRC_ALPHA, ZERO, ONE) |
| `INVERT` | (1-DST_COLOR, 1-SRC_COLOR, ONE, ZERO) |
| `MAX` | (ONE, ONE, BlendOp.MAX) |

- CONFIRMED constructors: `(BlendFactor src, BlendFactor dst)`, `(src, dst, BlendOp)`, `(srcC, dstC, srcA, dstA)`, `(srcC, dstC, opC, srcA, dstA, opA)`, `(BlendEquation)` and `(BlendEquation, BlendEquation)`.
- `BlendOp` values: ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX.

**Snippets in `net.minecraft.client.renderer.RenderPipelines`: CONFIRMED (javap)**
- PUBLIC snippets: `OIT_DEPTH_BOUNDS_SNIPPET`, `OIT_TRANSMITTANCE_SNIPPET`, `OIT_ACCUMULATE_SNIPPET`, `POST_PROCESSING_SNIPPET`, `GLINT_SNIPPET`, `GLINT_SPECIAL_SNIPPET`, `ANIMATE_SPRITE_SNIPPET`.
- `GLOBALS_SNIPPET` and `MATRICES_FOG_SNIPPET` are private.
  - `GLOBALS_SNIPPET = builder().withBindGroupLayout(GLOBALS)`.
  - `MATRICES_FOG_SNIPPET = GLOBALS + PROJECTION + DYNAMIC_TRANSFORMS + FOG`.
  - Rebuild them from `net.minecraft.client.renderer.BindGroupLayouts`, where every field is `public static final BindGroupLayout`.

CONFIRMED by disassembling the `BindGroupLayouts` static initializer:

| BindGroupLayout | Uniform name and type |
|---|---|
| `GLOBALS` | "Globals" (UNIFORM_BUFFER) |
| `PROJECTION` | "Projection" (UNIFORM_BUFFER) |
| `DYNAMIC_TRANSFORMS` | "DynamicTransforms" (UNIFORM_BUFFER) |
| `FOG` | "Fog" (UNIFORM_BUFFER) |
| `LIGHTING` | "Lighting" (UNIFORM_BUFFER) |
| `SAMPLER0` / `SAMPLER1` / `SAMPLER2` | "Sampler0" / "Sampler1" / "Sampler2" (COMBINED_IMAGE_SAMPLER) |

- Also available: `SAMPLER0_SAMPLER1`, `SAMPLER0_SAMPLER2`, `SAMPLER0_SAMPLER1_SAMPLER2`, `IN_SAMPLER` ("InSampler"), `CLOUD_INFO` ("CloudInfo" UBO + "CloudFaces" TEXEL_BUFFER), `CHUNK_SECTION`, `TERRAIN_INFO`, `LIGHTMAP_INFO`, `SPRITE`, `GLINT_SAMPLER`, `DEPTH_BOUNDS_SAMPLER`, and the OIT variants.
- CONFIRMED custom layout: `BindGroupLayout.builder().withUniform("MyUbo", UniformType.UNIFORM_BUFFER).build()`.
  - `UniformType` values: COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER.
  - The `withUniform(String, UniformType, GpuFormat)` overload exists for TEXEL_BUFFER.

**Vanilla sky pipelines: CONFIRMED (source)**
```
SKY:  builder(MATRICES_FOG_SNIPPET).withLocation("pipeline/sky").withVertexShader("core/sky").withFragmentShader("core/sky")
      .withVertexBinding(0, DefaultVertexFormat.POSITION).withPrimitiveTopology(TRIANGLE_FAN).withColorTargetState(ColorTargetState.DEFAULT)
STARS: builder(GLOBALS_SNIPPET)+PROJECTION+DYNAMIC_TRANSFORMS, "core/stars", new ColorTargetState(BlendFunction.OVERLAY), POSITION, QUADS
CELESTIAL: ...+SAMPLER0, "core/position_tex", OVERLAY, POSITION_TEX, QUADS
END_SKY: ...+SAMPLER0, "core/position_tex_color", TRANSLUCENT, POSITION_TEX_COLOR, QUADS
SUNRISE_SUNSET: GLOBALS+PROJECTION+DYNAMIC_TRANSFORMS, "core/position_color", TRANSLUCENT, POSITION_COLOR, TRIANGLE_FAN
```

**Custom pipeline: CONFIRMED.** Every call exists, and `build()`'s checks pass: location, both shaders and topology are set, 3 attributes (≤16), and the RGBA8_UNORM target matches the main target.
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
- CONFIRMED: `RenderSystem.getCompiledPipelineNullable` tries `currentPipelineCache.get()` first, then `fallbackPipelineCache.get()`. It throws `IllegalStateException` if the fallback cache is not set yet.
- CONFIRMED: `PipelineCache.get()` compiles lazily on a miss with `device.compilePipeline(pipeline, shaderSource, Util.backgroundExecutor()).join().finishCompile()`. It returns null if the compile fails.
- CONFIRMED: `getCompiledPipeline` wraps the nullable version and throws "Failed to find or load pipeline <loc>" when it gets null. Use `getCompiledPipelineNullable` to tolerate shader errors.
- CONFIRMED: `RenderPipelines.register` is private and only fills `PIPELINES_BY_LOCATION`, which backs the public `requiredPipelines()`.
- FIXED: Fabric has no registration hook, but the API is a little wider than the old sheet said.
  - `FabricRenderPipeline.usePipelineDrawModeForGui()`.
  - `FabricRenderPipeline.Builder.withUsePipelineDrawModeForGui(boolean)` and `withoutUsePipelineDrawModeForGui()`, mixed into `RenderPipeline.Builder`.
- UNCONFIRMED (optional): precompiling through a mixin that appends to `RenderPipelines.requiredPipelines()`. The method exists and is public static; its use by ShaderManager was not traced in this pass.

**Shaders: CONFIRMED (ShaderType, ShaderManager, vanilla assets)**
- Path: `assets/<ns>/shaders/<path>.vsh` / `.fsh`.
  - `ShaderType.VERTEX("vertex", ".vsh")`, `FRAGMENT("fragment", ".fsh")`, `idConverter = new FileToIdConverter("shaders", ext)`.
  - Includes live in `shaders/include/*.glsl`.
- Vanilla style, copied from `core/stars.vsh`, `core/position_tex.fsh` and the include files:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>  // layout(std140) uniform DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; };
#include <minecraft:projection.glsl>         // layout(std140) uniform Projection { mat4 ProjMat; };  (+ projection_from_position())
#include <minecraft:globals.glsl>            // layout(std140) uniform Globals { ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime; vec2 ScreenSize; int MenuBlurRadius; int UseRgss; };
layout(location = 0) in vec3 Position; layout(location = 1) in vec2 UV0; layout(location = 2) in vec4 Color; // order = VertexFormat element order
uniform sampler2D Sampler0;
layout(location = 0) out vec4 fragColor;
```
- Varyings use `layout(location=N) out/in` (seen in position_tex).

---

## 2. Textures from CPU pixels

**`com.mojang.blaze3d.platform.NativeImage`: CONFIRMED**
```
NativeImage(int w, int h, boolean zero)                 // RGBA
NativeImage(NativeImage.Format, int, int, boolean) / (Format,int,int,boolean,long)
void setPixel(int x, int y, int argb)    // = setPixelABGR(x,y,ARGB.toABGR(argb))  (source-confirmed)
void setPixelABGR(int x, int y, int abgr)
int getPixel(int,int); void fillRect(int,int,int,int,int); ByteBuffer getPixelBytes(); long getPointer(); void close()
```

**`net.minecraft.client.renderer.texture.DynamicTexture extends AbstractTexture implements Dumpable`: CONFIRMED**
```
DynamicTexture(Supplier<String> label, NativeImage image)      // createTexture + upload() immediately
DynamicTexture(String label, int w, int h, boolean zero)       // no upload
DynamicTexture(Supplier<String> label, int w, int h, boolean zero)  // no upload
void upload(); NativeImage getPixels(); void setPixels(NativeImage)
```
- Internally it calls `device.createTexture(label, 5 /*COPY_DST|TEXTURE_BINDING*/, GpuFormat.RGBA8_UNORM, w, h, 1, 1)`, then `createTextureView(texture)`.
- Its sampler is `RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)`.
- `AbstractTexture.sampler`, `texture` and `textureView` are all **protected**. For linear filtering, subclass it or pass your own sampler to `setUniform`.

**`AbstractTexture`: CONFIRMED.** `GpuTexture getTexture()`, `GpuTextureView getTextureView()`, `GpuSampler getSampler()`, `close()`.

**`TextureManager`: FIXED (was UNCONFIRMED).**
- `public TextureManager getTextureManager()` exists on `net.minecraft.client.Minecraft`.
- Methods: `void register(Identifier, AbstractTexture)`, `AbstractTexture getTexture(Identifier)`, `void release(Identifier)`.

**Samplers: CONFIRMED**
- `com.mojang.blaze3d.systems.SamplerCache` via `RenderSystem.getSamplerCache()`:
  - `getSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, boolean mipmaps)`
  - `getClampToEdge(FilterMode[, boolean])`
  - `getRepeat(FilterMode[, boolean])`
- `com.mojang.renderpearl.api.textures.AddressMode`: {REPEAT, CLAMP_TO_EDGE}
- `FilterMode`: {NEAREST, LINEAR}
- Raw sampler: `GpuDevice.createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod)`.

**Raw texture route (for example HDR): CONFIRMED**
- Create:
  - `GpuDevice.createTexture(@Nullable Supplier<String>|String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels)`
  - `createTextureView(GpuTexture[, int baseMip, int mipLevels])`
- `GpuTexture` usage constants: `USAGE_COPY_DST=1`, `USAGE_COPY_SRC=2`, `USAGE_TEXTURE_BINDING=4`, `USAGE_RENDER_ATTACHMENT=8`, `USAGE_CUBEMAP_COMPATIBLE=16`.
- `GpuFormat` includes RGBA8_UNORM, RGBA16_FLOAT, RGBA32_FLOAT, RG11B10_FLOAT, R16_FLOAT, RGB10A2_UNORM, D32_FLOAT, and others.
- Upload with `CommandEncoder.writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height)`.
  - NEW overloads: `writeToTexture(GpuTexture, NativeImage)` and `writeToTexture(GpuTexture, NativeImage, int mipLevel, int depthOrLayer, int destX, int destY)`.

CONFIRMED example (every call exists; `AddressMode`/`FilterMode` are in `com.mojang.renderpearl.api.textures`):
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

**Builders: FIXED (some methods live on VertexConsumer, not BufferBuilder)**
- CONFIRMED `com.mojang.blaze3d.vertex.ByteBufferBuilder`: `static exactlySized(int)`, `ByteBufferBuilder(int)`, `(int,long)`, `close()`.
- CONFIRMED `com.mojang.blaze3d.vertex.BufferBuilder(ByteBufferBuilder, PrimitiveTopology, VertexFormat)` implements `VertexConsumer`.
  - BufferBuilder itself declares: `addVertex(float,float,float)`, `setUv(float,float)`, `setColor(int argb)`, `setColor(int r,int g,int b,int a)`, `setNormal(float,float,float)`, `setLight(int)`, `setOverlay(int)`, `setUv1/2/3`, `setLineWidth`, `MeshData build()` (nullable) and `buildOrThrow()`.
  - Default methods inherited from `VertexConsumer`: `addVertex(Vector3fc)`, `addVertex(Matrix4fc,float,float,float)`, `addVertex(PoseStack.Pose, ...)`, `setColor(float,float,float,float)`.
  - Every setter returns `VertexConsumer`, so chaining works.
- CONFIRMED `MeshData` (AutoCloseable): `ByteBuffer vertexBuffer()`, `indexBuffer()`, `MeshData.DrawState drawState()`.
  - `DrawState` is a record `(VertexFormat format, int vertexCount, int indexCount, PrimitiveTopology, IndexType)`.
- CONFIRMED `DefaultVertexFormat`: POSITION, POSITION_COLOR, POSITION_TEX, POSITION_TEX_COLOR, POSITION_COLOR_TEX_LIGHTMAP, POSITION_TEX_LIGHTMAP_COLOR, POSITION_TEX_COLOR_NORMAL, POSITION_COLOR_NORMAL, POSITION_COLOR_LIGHTMAP, BLOCK, ENTITY, PARTICLE, and others. `VertexFormat.getVertexSize()` exists.

**GPU buffers: CONFIRMED**
- `GpuDevice.createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data)` / `createBuffer(Supplier<String>, int usage, long size)`.
- `GpuBuffer` usage constants (the real names carry a `USAGE_` prefix):

| Constant | Value |
|---|---|
| USAGE_MAP_READ | 1 |
| USAGE_MAP_WRITE | 2 |
| USAGE_HINT_CLIENT_STORAGE | 4 |
| USAGE_COPY_DST | 8 |
| USAGE_COPY_SRC | 16 |
| USAGE_VERTEX | 32 |
| USAGE_INDEX | 64 |
| USAGE_UNIFORM | 128 |
| USAGE_UNIFORM_TEXEL_BUFFER | 256 |
| USAGE_INDIRECT_PARAMETERS | 512 |

  - CONFIRMED: **32 = VERTEX** (static sky discs and the sun/moon/sunrise buffers).
  - CONFIRMED: **40 = VERTEX|COPY_DST** (vanilla stars and end-sky buffers).
- `GpuBuffer.slice()`, `slice(long offset, long length)`, `map(boolean read, boolean write)` (needs the MAP_* usage), `map(long offset, long length, boolean read, boolean write)`, `size()`, `usage()`, `close()`.
- `GpuBufferSlice` record `(GpuBuffer buffer, long offset, long length)`, plus `slice(long,long)` and `map(boolean,boolean)`.
- `GpuBufferSlice.MappedView` record `(GpuBufferSlice slice, ByteBuffer data, Runnable onClose)` is AutoCloseable.

**Quad indices: CONFIRMED**
- `RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)` returns `RenderSystem.AutoStorageIndexBuffer`.
- Methods: `GpuBuffer getBuffer(int indexCount)` (grows as needed), `getBuffer()`, `IndexType type()`, `requestIndexCount(int)`, `hasStorage(int)`.
- NEW: `validateDraw` throws unless the index buffer has `USAGE_INDEX`. AutoStorageIndexBuffer satisfies this.

**Per-frame updates**
- CONFIRMED: for a buffer created with usage 40, use `RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buf.slice(), byteBuffer)`.
- CONFIRMED: for a buffer created with `USAGE_MAP_WRITE`, use `try (var v = buf.map(false, true)) { v.data().put(...); }`.
- CONFIRMED: for transient per-frame memory, `encoder.transientMemory().uploadGpu(ByteBuffer data, long alignment, int usage)` returns a GpuBufferSlice. `allocateGpuMapped(long size, long alignment, int usage)` returns a MappedView. There are also `uploadStaging`, `allocateGpu` and `allocateCpu`.
- FIXED (was UNCONFIRMED) lifetime, GL backend: `GlCommandEncoder.submit()` fences the frame, waits on submit N-2, then calls `transientMemory.rotate()`. Slices are therefore only valid until the next `submit()`, which is effectively one frame (double-buffered). The Vulkan backend's lifetime was not traced: UNCONFIRMED.

CONFIRMED example:
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

**`RenderSystem.getDynamicUniforms()`: CONFIRMED.** It returns `net.minecraft.client.renderer.DynamicGpuData`.
```
GpuBufferSlice writeTransform(Matrix4f modelView)
GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator)
GpuBufferSlice writeTransform(Matrix4f modelView, Matrix4f textureMatrix)
GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator, Vector3f modelOffset, Matrix4f textureMatrix)
GpuBufferSlice writeTransform(DynamicGpuData.Transform)  // record (Matrix4fc modelView, Vector4fc colorModulator, Vector3fc modelOffset, Matrix4fc textureMatrix)
GpuBufferSlice[] writeTransforms(DynamicGpuData.Transform...)
```

**Other RenderSystem methods: CONFIRMED**
- `static Matrix4f getModelViewMatrixCopy()`
- `static Matrix4fStack getModelViewStack()`
- `static GpuBufferSlice getProjectionMatrixBuffer()`
- `setProjectionMatrix(GpuBufferSlice, ProjectionType)`, `backupProjectionMatrix()`, `restoreProjectionMatrix()`
- `static GpuDevice getDevice()`, `setShaderFog(GpuBufferSlice)`, `getShaderFog()`
- FIXED wording for `static void bindDefaultUniforms(RenderPass)`: it sets "Projection", "Fog", "Globals" and "Lighting" whenever RenderSystem holds a non-null value for each. The pass silently ignores a name the bound pipeline does not declare, because `uniformIndices.getOrDefault(name,-1) == -1`.

**Projection facts**
- CONFIRMED: `LevelRenderer` does `modelViewStack.pushMatrix(); modelViewStack.mul(cameraState.viewRotationMatrix)` before the frame graph runs, and pops after `frame.execute`. The matrix is rotation only, so sky geometry is camera-centred.
- CONFIRMED: `Camera.update` calls `setupPerspective(0.05F, this.depthFar, this.fov, w, h)`.
- CONFIRMED: `depthFar = max(effectiveRenderDistance*16*4, cloudRange*16)`.
- CONFIRMED: `Projection.getMatrix` passes `near=zFar, far=zNear` (reversed-Z), and `isZZeroToOne` comes from `getDeviceInfo()`.
- FIXED: geometry beyond depthFar is clipped. At render distance 2, depthFar can drop to 128 when cloudRange is small.
  - Vanilla sky disc radius is 512; sun, moon, stars and end-flash all sit at distance 100 (`SUN_HEIGHT`, `MOON_HEIGHT`, `starDistance`).
  - Use a shell of **about 100 units**, which matches vanilla celestials and is safe at every setting. Or clamp the radius to `CameraRenderState.depthFar * 0.9`. A shell of 100–400 is not safe at low render distance.
  - Encode real distance through scale and direction. Never put real distances in vertex positions.
- CONFIRMED: the sky pass has a depth attachment, and it was cleared to 0.0 (far, under reversed-Z) by the "clear" pass. Sky pipelines have no DepthStencilState, so they ignore depth.

**SkyRenderer (`net.minecraft.client.renderer.SkyRenderer`)**
- CONFIRMED: entry point is `public void render(GpuBufferSlice skyFog, SkyRenderState state)`.
- FIXED: it is invoked from `LevelRenderer.addSkyPass` inside `frame.addPass("sky")`. All of these must hold:
  - `shouldRenderSky` is true,
  - the fog type is not POWDER_SNOW or LAVA,
  - **`!cameraState.entityRenderState.doesMobEffectBlockSky`** (blindness or darkness),
  - and `state.skybox != DimensionType.Skybox.NONE`.
- The SkyRenderer is recreated when `levelRenderState.shouldResetSkyRenderer` is set.
- CONFIRMED: `DimensionType.Skybox` is {NONE, OVERWORLD, END}. A custom dimension type needs skybox "overworld" or "end" for the pass to run.
- CONFIRMED: Fabric rendering-v1 27.0.14 has no sky hook.
  - `LevelRenderEvents` has START_MAIN, AFTER_OPAQUE_TERRAIN, COLLECT_SUBMITS, AFTER_SOLID_FEATURES, AFTER_TRANSLUCENT_FEATURES, BEFORE_BLOCK_OUTLINE, BEFORE_GIZMOS, BEFORE_TRANSLUCENT_TERRAIN, AFTER_TRANSLUCENT_TERRAIN, END_MAIN, plus AFTER_BLOCK_OUTLINE_EXTRACTION and END_EXTRACTION.
  - No module in fabric-api 0.161.0+26.3 has a sky class except the internal `SkyRenderStateMixin`.
- CONFIRMED: use a mixin `@Inject(method="render", at=@At("HEAD"), cancellable=true)` on SkyRenderer.
  - The getter is `public @Nullable SkyRenderer skyRenderer()` on LevelRenderer.
- CONFIRMED: `SkyRenderer.renderTarget` is private, but the renderer is constructed with `this.gameRenderer.mainRenderTarget()`. So `Minecraft.getInstance().gameRenderer.mainRenderTarget()` is the same target.
  - `GameRenderer.mainRenderTarget()` returns `com.mojang.blaze3d.pipeline.RenderTarget` (javap).

**Render pass (`com.mojang.renderpearl.api.commands`): CONFIRMED, parameter names FIXED**
- `CommandEncoder.createRenderPass(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor, @Nullable GpuTextureView depthTexture, OptionalDouble clearDepth)`.
  - There is also a 3-arg form without depth and a 6-arg form with `RenderPass.RenderArea`.
- `RenderPass` methods:
  - `setPipeline(CompiledRenderPipeline)`
  - `setUniform(String, GpuBufferSlice|GpuBuffer)`, `setUniform(String, @Nullable GpuTextureView, @Nullable GpuSampler)` (both or neither may be null)
  - `setVertexBuffer(int, GpuBufferSlice)`, `setIndexBuffer(GpuBuffer, IndexType)`
  - `drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance)`. The fourth parameter is named `vertexOffset`, not `baseVertex`.
  - `draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance)`
  - `pushDebugGroup(Supplier<String>)` / `popDebugGroup()`, `pushConstants(ByteBuffer)` (needs a bound pipeline), `enableScissor`, `close()`
- NEW, from FrontendRenderPass:
  - Uniforms set before `setPipeline` are kept in a map and re-applied on `setPipeline`, so call order does not matter.
  - A draw throws "Missing uniform X" for any uniform the pipeline declares but the pass never set.
  - A UBO slice offset must be a multiple of `minUniformOffsetAlignment`.
  - A non-zero `firstInstance` needs the `nonZeroFirstInstance` device feature.

FIXED example: a `null` guard on the compiled pipeline and a simpler dimension check.
```java
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
  @Inject(method = "render", at = @At("HEAD"), cancellable = true)
  private void galactica$sky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.dimension().equals(GalacticaDims.SPACE)) return;
    CompiledRenderPipeline pipe = RenderSystem.getCompiledPipelineNullable(GALAXY_SKY);
    if (pipe == null) return;                       // shader failed: fall back to vanilla sky
    RenderSystem.setShaderFog(skyFog);
    RenderTarget rt = mc.gameRenderer.mainRenderTarget();
    try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
          .createRenderPass(() -> "galactica sky", rt.getColorTextureView(), Optional.empty(), rt.getDepthTextureView(), OptionalDouble.empty())) {
      RenderSystem.AutoStorageIndexBuffer q = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
      pass.setPipeline(pipe);
      RenderSystem.bindDefaultUniforms(pass);
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
- CONFIRMED: `RenderTarget.getColorTextureView()` and `getDepthTextureView()` are public, and SkyRenderer.render uses them verbatim.

**Iris 1.11.7+mc26.3: FIXED (was UNCONFIRMED), from decompiled mixins**
- Iris does not replace `SkyRenderer.render`.
  - `MixinSkyRenderer` injects at HEAD of the private `renderSkyDisc`, `renderSun`, `renderMoon`, `renderSunriseAndSunset`, `renderStars` and `renderDarkDisc` to call `setPhase(...)`.
  - It can cancel sun and moon, and it tilts `renderSunMoonAndStars` by the sun-path rotation.
  - A HEAD-cancel of `render()` skips all of these, so Iris never enters the SKY, STARS or other sky phases for the custom sky.
- `MixinLevelRenderer_Sky` injects at HEAD of the `addSkyPass` lambda (`lambda$addSkyPass$0` / `$8`). It runs before `render()`, so it is unaffected.
- Iris swaps shaders in `MixinShaderManager_Overrides`, which is `@Inject(RETURN)` on `RenderSystem.getCompiledPipelineNullable`.
  - It looks the pipeline up in `IrisPipelines.coreShaderMap`. Vanilla maps SKY and STARS to `SKY_BASIC`, CELESTIAL and END_SKY to `SKY_TEXTURED`, and SUNRISE_SUNSET to `SKY_BASIC_COLOR`.
  - An unmapped non-minecraft pipeline keeps **its own shader**. Iris logs one error: "Missing program <loc> in override list. This is not a critical problem...".
  - Optional soft-dependency hook to route through the pack's skytextured program: `net.irisshaders.iris.pipeline.IrisPipelines.assignPipeline(GALAXY_SKY, ShaderKey.SKY_TEXTURED)`. It throws if the pipeline is already assigned.
- The Iris override path casts the device to `GlDevice`, so it assumes the GL backend.
- Iris depends on `sodium` 0.9.x.
- Still UNCONFIRMED: whether the custom sky's writes to `mainRenderTarget` survive the pack's gbuffer/composite passes. Test with a pack active.

---

## 5. Level and camera

- CONFIRMED: `Minecraft.getInstance()`. Public fields: `level` (ClientLevel), `player` (LocalPlayer), `gameRenderer` (final), `levelRenderer` (final), `font` (final Font). Also `getTextureManager()`.
- CONFIRMED: `Level.dimension()` returns `ResourceKey<Level>`. Vanilla keys: `Level.OVERWORLD`, `NETHER`, `END`.
  - `ResourceKey.create` interns its keys (weak-value map), so `==` works, but `.equals` is the safe choice.
- Camera:
  - CONFIRMED: `Minecraft.getInstance().gameRenderer.mainCamera()`. There is no `getMainCamera`.
  - CONFIRMED `Camera` (`net.minecraft.client.Camera`): `Vec3 position()`, `BlockPos blockPosition()`, `float xRot()`, `float yRot()`, `float yaw()`, `Quaternionf rotation()`, `Matrix4f getViewRotationMatrix(Matrix4f dest)`, `Matrix4f getViewRotationProjectionMatrix(Matrix4f)`, `Vector3fc forwardVector()`, `upVector()`, `leftVector()`, `float getFov()`, `Entity entity()`, `FogType getFluidInCamera()`.
  - FIXED: `PROJECTION_Z_NEAR = 0.05F` is **public static final** (`BASE_HUD_FOV = 70.0F` is also public). Only the `depthFar` field is private. `CameraRenderState.depthFar` is public, as are `projectionMatrix`, `viewRotationMatrix`, `pos` and `orientation`.

FIXED example: the redundant boolean is gone.
```java
public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("galactica", "space"));
ClientLevel lvl = Minecraft.getInstance().level;
boolean inSpace = lvl != null && lvl.dimension().equals(SPACE);
Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
Vec3 p = cam.position(); Quaternionf q = cam.rotation();
```
- CONFIRMED: `Registries` is `net.minecraft.core.registries.Registries`. `Registries.DIMENSION` is a `ResourceKey<Registry<Level>>`, and `ResourceKey.create(ResourceKey<? extends Registry<T>>, Identifier)` exists.

---

## 6. HUD (Fabric) and GUI text

- CONFIRMED: `net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement` is a functional interface with `void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker)`.
- CONFIRMED: `HudElementRegistry` static methods:
  - `addFirst(Identifier, HudElement)`, `addLast(Identifier, HudElement)`
  - `attachElementBefore(Identifier before, Identifier id, HudElement)`, `attachElementAfter(Identifier after, Identifier id, HudElement)`
  - `removeElement(Identifier)`, `replaceElement(Identifier, Function<HudElement,HudElement>)`
- CONFIRMED: `VanillaHudElements` IDs are MISC_OVERLAYS, CROSSHAIR, SPECTATOR_MENU, HOTBAR, ARMOR_BAR, HEALTH_BAR, FOOD_BAR, AIR_BAR, MOUNT_HEALTH, INFO_BAR, EXPERIENCE_LEVEL, HELD_ITEM_TOOLTIP, SPECTATOR_TOOLTIP, MOB_EFFECTS, BOSS_BAR, SLEEP, DEMO_TIMER, SCOREBOARD, OVERLAY_MESSAGE, TITLE_AND_SUBTITLE, CHAT, PLAYER_LIST, SUBTITLES.
- **GuiGraphics is now `net.minecraft.client.gui.GuiGraphicsExtractor`.**
  - CONFIRMED: `text(Font, String|Component|FormattedCharSequence, int x, int y, int color[, boolean dropShadow])`.
    - The 5-arg form defaults to `dropShadow=true`.
    - The call is skipped when `ARGB.alpha(color) == 0`, so always pass 0xFF in alpha.
  - CONFIRMED: `centeredText(Font, String|Component|FormattedCharSequence, int, int, int)`.
  - CONFIRMED: `int textWithWordWrap(Font, FormattedText, x, y, width, color[, boolean shadow])`.
  - FIXED: `textWithBackdrop(Font font, Component str, int textX, int textY, int textWidth, int textColor)`. `textWidth` is required, not optional; it is the backdrop width. The backdrop is `fill(textX-2, textY-2, textX+textWidth+2, textY+9+2, ...)`.
  - CONFIRMED: `fill(int x0,int y0,int x1,int y1,int col)`, plus `fill(RenderPipeline, ...)` overloads, `guiWidth()`, `guiHeight()`, `Matrix3x2fStack pose()`.
  - FIXED (was UNCONFIRMED): `blit(GpuTextureView textureView, GpuSampler sampler, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)`. The order is confirmed, and it draws with `RenderPipelines.GUI_TEXTURED`.
  - CONFIRMED: `blit(RenderPipeline, Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight[, int color])`. There are also overloads with srcWidth/srcHeight, and `blit(Identifier, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)`.

CONFIRMED example:
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
- Iris with a shader pack active: whether sky pixels written straight to `mainRenderTarget` survive the pack's gbuffer and composite passes. Test in game. `IrisPipelines.assignPipeline(GALAXY_SKY, ShaderKey.SKY_TEXTURED)` is the soft hook to try.
- How long Vulkan-backend `TransientMemory` slices live. GL rotates them on `submit()`.
- Whether ShaderManager actually precompiles everything in `RenderPipelines.requiredPipelines()` on reload, and so whether a mixin appending to it is a usable precompile hook. Lazy compile works regardless.

Resolved since the last version:
- Null depth state means depth off: confirmed in both the GL and Vulkan backends.
- The `getTextureManager()` accessor exists.
- Draw parameter names: `drawIndexed` uses `vertexOffset`.
- `blit(GpuTextureView, …)` parameter order and `textWithBackdrop` parameters are verified.