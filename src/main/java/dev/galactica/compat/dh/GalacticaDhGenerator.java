package dev.galactica.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.planet.SurfaceAtmosphere;
import dev.galactica.planet.TerrainSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Distant Horizons world generator for one {@code galactica:surface_N} level. It returns
 * {@link EDhApiWorldGeneratorReturnType#API_DATA_SOURCES}: every LOD column is filled straight from
 * {@link TerrainSampler#height}/{@link TerrainSampler#column} through {@link LodColumnPlan}, with no
 * vanilla chunk generation, so a 64×64-column LOD costs ~4,200 height samples whatever its
 * detail level. That is what makes the planet's terrain reach the horizon while descending.
 *
 * <p>DH contract (API 7.2.0 sources, DH 3.3.4 {@code WorldGenerationQueue}): {@code detailLevel} is
 * the data detail level, so one column spans 2^detailLevel blocks starting at block
 * {@code chunkPosMin × 16}; the pooled data source is completed when the returned future
 * completes (the consumer is ignored by DH but called for API symmetry). Runs on DH's world-gen
 * pool; {@link TerrainSampler} is immutable and its noise is stateless, so this is thread-safe.
 */
final class GalacticaDhGenerator implements IDhApiWorldGenerator {
    private static final Logger LOG = LoggerFactory.getLogger("Galactica/DH");
    /** Coarsest data detail we generate: 2^12 = 4096 blocks per column, beyond any DH render radius. */
    private static final byte LARGEST_DETAIL = 12;

    private final IDhApiLevelWrapper level;
    private final int slot;
    private final int minY, height;
    private final ThreadLocal<LodColumnPlan> plans = ThreadLocal.withInitial(LodColumnPlan::new);
    private final ThreadLocal<ArrayList<DhApiTerrainDataPoint>> points =
            ThreadLocal.withInitial(() -> new ArrayList<>(LodColumnPlan.MAX_SPANS));

    /** Block wrappers resolved once per level, by material ordinal; biome resolved per body. */
    private volatile IDhApiBlockStateWrapper[] blocks;
    private volatile String biomeFor;
    private volatile IDhApiBiomeWrapper biome;

    GalacticaDhGenerator(IDhApiLevelWrapper level, int slot) {
        this.level = level;
        this.slot = slot;
        this.minY = level.getMinHeight();
        this.height = level.getMaxHeight();
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return LARGEST_DETAIL;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    /** DH's validation is cheap next to noise sampling and catches span bugs as a clear log line. */
    @Override
    public boolean runApiValidation() {
        return true;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
                                               IDhApiFullDataSource pooled, EDhApiDistantGeneratorMode mode,
                                               ExecutorService pool, Consumer<IDhApiFullDataSource> resultConsumer) {
        return CompletableFuture.runAsync(() -> {
            PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
            if (rt == null) {
                // Free slot: nothing real to show yet. Cancelling (rather than writing placeholder
                // ground) keeps DH from persisting terrain that the next body would contradict.
                throw new CancellationException("surface_" + slot + " has no body assigned");
            }
            fill(rt, chunkPosMinX * 16, chunkPosMinZ * 16, 1 << detailLevel, pooled);
            resultConsumer.accept(pooled);
        }, pool);
    }

    private void fill(PlanetSlots.Runtime rt, int originX, int originZ, int step, IDhApiFullDataSource out) {
        TerrainSampler t = rt.terrain();
        IDhApiBlockStateWrapper[] bs = blocks();
        IDhApiBiomeWrapper bio = biome(rt);
        int w = out.getWidthInDataColumns();
        // Heights on a (w+1)² lattice so each column's slope uses its +x/+z neighbour without resampling.
        int[] hs = new int[(w + 1) * (w + 1)];
        for (int j = 0; j <= w; j++) {
            for (int i = 0; i <= w; i++) {
                hs[j * (w + 1) + i] = t.height(originX + i * step, originZ + j * step);
            }
        }
        LodColumnPlan plan = plans.get();
        ArrayList<DhApiTerrainDataPoint> col = points.get();
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int h = hs[j * (w + 1) + i];
                int slope = Math.max(Math.abs(hs[j * (w + 1) + i + 1] - h), Math.abs(hs[(j + 1) * (w + 1) + i] - h));
                int x = originX + i * step, z = originZ + j * step;
                plan.plan(t, x, z, h, slope, step, minY, height);
                col.clear();
                for (int k = 0; k < plan.count(); k++) {
                    LodColumnPlan.Span s = plan.span(k);
                    col.add(DhApiTerrainDataPoint.create((byte) 0, LodColumnPlan.blockLight(s.material), s.skyLight,
                            s.bottom, s.top, bs[s.material.ordinal()], bio));
                }
                out.setApiDataPointColumn(i, j, EDhApiWorldGenerationStep.SURFACE, col);
            }
        }
    }

    private IDhApiBlockStateWrapper[] blocks() {
        IDhApiBlockStateWrapper[] b = blocks;
        if (b != null) return b;
        IDhApiWrapperFactory f = DhApi.Delayed.wrapperFactory;
        TerrainSampler.Material[] ms = TerrainSampler.Material.values();
        b = new IDhApiBlockStateWrapper[ms.length];
        for (TerrainSampler.Material m : ms) {
            if (m == TerrainSampler.Material.AIR) {
                b[m.ordinal()] = f.getAirBlockStateWrapper();
                continue;
            }
            try {
                b[m.ordinal()] = f.getDefaultBlockStateWrapper(LodColumnPlan.blockId(m), level);
            } catch (IOException e) {
                // A renamed vanilla block must not blank the whole LOD: fall back to stone.
                LOG.warn("DH has no block {}: {}; using stone", LodColumnPlan.blockId(m), e.getMessage());
                b[m.ordinal()] = null;
            }
        }
        IDhApiBlockStateWrapper stone = b[TerrainSampler.Material.STONE.ordinal()];
        for (int i = 0; i < b.length; i++) if (b[i] == null) b[i] = stone;
        blocks = b;
        return b;
    }

    private IDhApiBiomeWrapper biome(PlanetSlots.Runtime rt) {
        String want = "galactica:" + SurfaceAtmosphere.biomePath(rt.body());
        IDhApiBiomeWrapper b = biome;
        if (b != null && want.equals(biomeFor)) return b;
        IDhApiWrapperFactory f = DhApi.Delayed.wrapperFactory;
        for (String id : List.of(want, "galactica:planet_barren_black", "minecraft:plains")) {
            try {
                b = f.getBiomeWrapper(id, level);
                if (b != null) break;
            } catch (IOException e) {
                LOG.warn("DH could not resolve biome {}: {}", id, e.getMessage());
            }
        }
        biome = b;
        biomeFor = want;
        return b;
    }

    @Override
    public void preGeneratorTaskStart() {
        // No per-task state to reset: every generateLod call is self-contained.
    }

    @Override
    public void close() {
        blocks = null;
        biome = null;
    }
}
