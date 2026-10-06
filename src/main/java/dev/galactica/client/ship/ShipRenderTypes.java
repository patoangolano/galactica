package dev.galactica.client.ship;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.galactica.Galactica;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The ship's own pipelines and render types (docs/recon/api_vfx_pipelines.md, api_vfx_entity.md):
 * <ul>
 *   <li>{@link #HULL_LIT}: vanilla {@code core/entity} shaders compiled with {@code NO_CARDINAL_LIGHTING} and
 *       {@code EMISSIVE}, so neither vanilla's two fixed lights nor the lightmap touch the hull: the vertex colour,
 *       which {@link ShadedVertexConsumer} computes per face from the host star, is the whole lighting.</li>
 *   <li>{@link #EXHAUST}: our {@code core/exhaust_plume} shaders, additive ({@code SRC_ALPHA, ONE}), depth-tested
 *       without depth write (reversed Z: GEQUAL), with the matching {@code OIT_ADDITIVE} set for improved
 *       transparency.</li>
 * </ul>
 * A pipeline whose shaders fail to compile is remembered in {@link #FAILED} and never queried again until the
 * next resource reload: asking {@code RenderSystem.getCompiledPipelineNullable} again while Iris is active would hit
 * Iris's null cast, and asking a render type to draw would throw.
 */
public final class ShipRenderTypes {
    private ShipRenderTypes() {}

    private static Identifier id(String path) {
        return Galactica.id(path);
    }

    private static final DepthStencilState TEST_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    public static final RenderPipeline HULL_LIT = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withLocation(id("pipeline/ship_hull_lit"))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withShaderDefine("EMISSIVE")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    public static final RenderType HULL_LIT_TYPE = RenderType.create("galactica_ship_hull_lit",
            RenderSetup.builder(HULL_LIT)
                    .withTexture("Sampler0", StarshipRenderer.TEXTURE)
                    .useOverlay()
                    .affectsCrumbling()
                    .createRenderSetup());

    private static RenderPipeline.Builder exhaustBase() {
        return RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withVertexShader(id("core/exhaust_plume"))
                .withFragmentShader(id("core/exhaust_plume"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
    }

    public static final RenderPipeline EXHAUST = exhaustBase()
            .withLocation(id("pipeline/exhaust_plume"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(TEST_NO_WRITE)
            .build();

    public static final OitPipelineSet EXHAUST_OIT =
            OitPipelineSet.builder("galactica_exhaust_plume", exhaustBase().withShaderDefine("OIT_ADDITIVE")).build();

    public static final RenderType EXHAUST_TYPE = RenderType.create("galactica_exhaust_plume",
            RenderSetup.builder(EXHAUST).setOitPipelines(EXHAUST_OIT).createRenderSetup());

    private static final Set<RenderPipeline> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Client init: precompile at resource reload, and forget failures after every reload. */
    public static void init() {
        RenderPipelines.registerOptional(HULL_LIT);
        RenderPipelines.registerOptional(EXHAUST);
        Identifier listener = id("ship_shader_retry");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(listener, (ResourceManagerReloadListener) rm -> FAILED.clear());
        loader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, listener);
    }

    /** True if the lit hull can draw; false falls back to the vanilla entity render type. */
    public static boolean hullUsable() {
        return compiles(HULL_LIT);
    }

    /** True if the exhaust can draw in the current transparency mode. */
    public static boolean exhaustUsable() {
        if (!compiles(EXHAUST)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || !mc.gameRenderer.useImprovedTransparency()) return true;
        return compiles(EXHAUST_OIT.depthBoundsPipeline()) && compiles(EXHAUST_OIT.transmittancePipeline())
                && compiles(EXHAUST_OIT.accumulatePipeline());
    }

    private static boolean compiles(RenderPipeline pipeline) {
        if (FAILED.contains(pipeline)) return false;
        CompiledRenderPipeline compiled;
        try {
            compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        } catch (NullPointerException irisNpe) {
            // Iris 1.11.7 MixinShaderManager_Overrides dereferences a null result while a pack is active.
            compiled = null;
        } catch (IllegalStateException noFallbackCache) {
            // "Fallback pipeline cache not yet set": too early in startup to draw anything; try again next frame.
            return false;
        }
        if (compiled == null) {
            FAILED.add(pipeline);
            Galactica.LOG.warn("Galactica ship pipeline {} failed to compile; drawing without it until the next resource reload",
                    pipeline.getLocation());
            return false;
        }
        return true;
    }
}
