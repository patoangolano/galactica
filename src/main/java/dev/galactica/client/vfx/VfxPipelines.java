package dev.galactica.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Shared factory and safe lookup for Galactica's custom render pipelines.
 *
 * <ul>
 *   <li>Every pipeline made here is passed to {@link RenderPipelines#registerOptional} (public via
 *       fabric-api's transitive access widener), so ShaderManager precompiles it on each resource
 *       reload instead of hitching on the first draw.</li>
 *   <li>{@link #compiledOrNull} memoises failures. {@code PipelineCache} does not cache a failed
 *       compile, so without the memo a broken shader would recompile and log every frame. It also
 *       never asks again while Iris may be active: Iris 1.11.7 {@code MixinShaderManager_Overrides}
 *       dereferences a null result of {@code RenderSystem.getCompiledPipelineNullable} and throws.</li>
 *   <li>The memo is cleared by a reload listener ordered after the vanilla shader reloader, so a
 *       fixed resource pack gets a fresh attempt.</li>
 * </ul>
 */
public final class VfxPipelines {
    private VfxPipelines() {}

    private static final Set<RenderPipeline> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<RenderPipeline> ALL = new ArrayList<>();
    private static boolean initialised;

    /** Projection + DynamicTransforms: what a sky sprite vertex shader reads. */
    private static final RenderPipeline.Snippet SKY_MATRICES = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .buildSnippet();

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("galactica", path);
    }

    /** A layout with one std140 uniform block; {@code blockName} must equal the GLSL block name. */
    public static BindGroupLayout uniformBlock(String blockName) {
        return BindGroupLayout.builder().withUniform(blockName, UniformType.UNIFORM_BUFFER).build();
    }

    /**
     * A camera-facing sprite drawn in a sky pass: POSITION_TEX_COLOR quads, no culling, no depth
     * state (no test, no write: sky shells sit inside the far plane and are ordered by draw order).
     *
     * @param name      pipeline and shader name: {@code galactica:pipeline/<name>}, {@code galactica:core/<name>}
     * @param blockName the shader's own uniform block
     * @param blend     colour blending
     */
    public static RenderPipeline skySprite(String name, String blockName, BlendFunction blend) {
        RenderPipeline p = RenderPipeline.builder(SKY_MATRICES)
                .withBindGroupLayout(uniformBlock(blockName))
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id("core/" + name))
                .withFragmentShader(id("core/" + name))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(blend))
                .withCull(false)
                .build();
        return register(p);
    }

    /** Adds a pipeline to the precompile list. Call from client init, before the first resource reload. */
    public static synchronized RenderPipeline register(RenderPipeline pipeline) {
        if (!ALL.contains(pipeline)) {
            ALL.add(pipeline);
            RenderPipelines.registerOptional(pipeline);
        }
        return pipeline;
    }

    /** Registers the failure-memo reset listener. Idempotent; call once from the client initializer. */
    public static synchronized void initClient() {
        if (initialised) return;
        initialised = true;
        Identifier listener = id("vfx_pipeline_retry");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(listener, (ResourceManagerReloadListener) rm -> FAILED.clear());
        loader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, listener);
    }

    /** The compiled pipeline, or null if its shaders failed (then it is never queried again until a reload). */
    public static CompiledRenderPipeline compiledOrNull(RenderPipeline pipeline) {
        if (FAILED.contains(pipeline)) return null;
        CompiledRenderPipeline c;
        try {
            c = RenderSystem.getCompiledPipelineNullable(pipeline);
        } catch (NullPointerException irisNpe) {
            // Iris casts the null result while a shader pack is active: same meaning, compile failed.
            c = null;
        } catch (IllegalStateException noFallbackCache) {
            // "Fallback pipeline cache not yet set": too early in startup; retry next time.
            return null;
        }
        if (c == null) FAILED.add(pipeline);
        return c;
    }

    /** True once the pipeline is known to have failed (callers fall back to their textured sprite). */
    public static boolean failed(RenderPipeline pipeline) {
        return FAILED.contains(pipeline);
    }
}
