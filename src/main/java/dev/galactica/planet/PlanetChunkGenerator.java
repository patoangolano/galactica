package dev.galactica.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code galactica:planet}: terrain of whatever body currently owns slot {@code slot}, from its
 * {@link TerrainSampler}. One JSON stem per slot; the slot field is the only route to per-dimension
 * parameters that reaches every generator method (height queries get no level, only a
 * {@link LevelHeightAccessor}). Blocks go straight into the chunk sections, as vanilla's noise
 * filler does; lighting sources are found by the later light stage scanning the sections.
 */
public final class PlanetChunkGenerator extends ChunkGenerator {
    public static final MapCodec<PlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
            Codec.intRange(0, PlanetSlots.COUNT - 1).fieldOf("slot").forGetter(PlanetChunkGenerator::slot)
    ).apply(i, i.stable(PlanetChunkGenerator::new)));

    private static final BlockState[] STATES = new BlockState[TerrainSampler.Material.values().length];

    static {
        for (TerrainSampler.Material m : TerrainSampler.Material.values()) {
            STATES[m.ordinal()] = (switch (m) {
                case AIR -> Blocks.AIR;
                case BEDROCK -> Blocks.BEDROCK;
                case STONE -> Blocks.STONE;
                case GRAVEL -> Blocks.GRAVEL;
                case TUFF -> Blocks.TUFF;
                case RED_SAND -> Blocks.RED_SAND;
                case TERRACOTTA -> Blocks.TERRACOTTA;
                case PACKED_ICE -> Blocks.PACKED_ICE;
                case SNOW_BLOCK -> Blocks.SNOW_BLOCK;
                case BLUE_ICE -> Blocks.BLUE_ICE;
                case ICE -> Blocks.ICE;
                case BASALT -> Blocks.BASALT;
                case BLACKSTONE -> Blocks.BLACKSTONE;
                case MAGMA_BLOCK -> Blocks.MAGMA_BLOCK;
                case GRASS_BLOCK -> Blocks.GRASS_BLOCK;
                case DIRT -> Blocks.DIRT;
                case SAND -> Blocks.SAND;
                case SANDSTONE -> Blocks.SANDSTONE;
                case YELLOW_TERRACOTTA -> Blocks.DYED_TERRACOTTA.yellow();
                case SMOOTH_BASALT -> Blocks.SMOOTH_BASALT;
                case WATER -> Blocks.WATER;
                case LAVA -> Blocks.LAVA;
                case COARSE_DIRT -> Blocks.COARSE_DIRT;
            }).defaultBlockState();
        }
    }

    /** Block state a palette material generates as; also read by the client clipmap for map colours. */
    public static BlockState state(TerrainSampler.Material m) {
        return STATES[m.ordinal()];
    }

    /** Map colour (0xRRGGBB) of a palette material's block, as a filled map would draw it. */
    public static int mapColour(TerrainSampler.Material m) {
        return state(m).getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
    }

    private final int slot;

    public PlanetChunkGenerator(BiomeSource biomeSource, int slot) {
        super(biomeSource);
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                                       StructureManager structureManager, BiomeManager biomeManager,
                                                       @Nullable WorldGenRegion region, Set<Holder<Biome>> possibleBiomes) {
        final TerrainSampler t = PlanetSlots.terrain(slot); // one body per chunk even if the slot changes mid-flight
        return CompletableFuture.supplyAsync(() -> fill(chunk, t), Util.backgroundExecutor().forName("galactica_planet"));
    }

    private static ChunkAccess fill(ChunkAccess chunk, TerrainSampler t) {
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        // Heights with a one-column border, for slopes across chunk edges. Inner columns keep their
        // full macro sample for the materials pass; the border needs only the elevation.
        int[] h = new int[18 * 18];
        PlanetMacro.Sample[] macro = new PlanetMacro.Sample[16 * 16];
        for (int dz = 0; dz < 18; dz++) {
            for (int dx = 0; dx < 18; dx++) {
                boolean inner = dx >= 1 && dx <= 16 && dz >= 1 && dz <= 16;
                if (inner) {
                    PlanetMacro.Sample m = new PlanetMacro.Sample();
                    macro[(dz - 1) * 16 + dx - 1] = m;
                    h[dz * 18 + dx] = t.height(bx + dx - 1, bz + dz - 1, m);
                } else {
                    h[dz * 18 + dx] = t.height(bx + dx - 1, bz + dz - 1);
                }
            }
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = Math.max(chunk.getMinY(), TerrainSampler.MIN_Y), maxY = chunk.getMaxY();
        LevelChunkSection[] acquired = new LevelChunkSection[chunk.getSectionsCount()];
        TerrainSampler.Column c = new TerrainSampler.Column();
        try {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = (z + 1) * 18 + (x + 1), hc = h[i];
                    int slope = Math.max(Math.max(Math.abs(hc - h[i - 1]), Math.abs(hc - h[i + 1])),
                            Math.max(Math.abs(hc - h[i - 18]), Math.abs(hc - h[i + 18])));
                    int wx = bx + x, wz = bz + z;
                    t.column(wx, wz, hc, slope, c, macro[z * 16 + x]);
                    int top = Math.min(maxY, Math.max(hc, c.fluid != TerrainSampler.Material.AIR ? c.fluidTop : hc));
                    BlockState topSolid = null, topAny = null;
                    for (int y = minY; y <= top; y++) {
                        TerrainSampler.Material m = t.materialAt(c, y, wx, wz);
                        if (m == TerrainSampler.Material.AIR) continue;
                        BlockState s = state(m);
                        int si = chunk.getSectionIndex(y);
                        LevelChunkSection sec = acquired[si];
                        if (sec == null) {
                            sec = chunk.getSection(si);
                            sec.acquire();
                            acquired[si] = sec;
                        }
                        sec.setBlockState(x, y & 15, z, s, false);
                        if (y == Math.min(hc, maxY)) topSolid = s;
                        topAny = s;
                    }
                    // Heightmaps only ever rise, so updating with the topmost blocks is enough.
                    if (topSolid != null) {
                        oceanFloor.update(x, Math.min(hc, maxY), z, topSolid);
                        surface.update(x, Math.min(hc, maxY), z, topSolid);
                    }
                    if (topAny != null && top > hc) surface.update(x, top, z, topAny);
                }
            }
        } finally {
            for (LevelChunkSection s : acquired) if (s != null) s.release();
        }
        return chunk;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        TerrainSampler t = PlanetSlots.terrain(slot);
        int h = t.height(x, z);
        boolean fluidCounts = type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG;
        if (fluidCounts && t.hasSea()) h = Math.max(h, TerrainSampler.SEA_LEVEL);
        return Math.min(h, level.getMaxY()) + 1; // first free Y above the column, as FlatLevelSource answers
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        TerrainSampler t = PlanetSlots.terrain(slot);
        TerrainSampler.Column c = new TerrainSampler.Column();
        t.column(x, z, t.height(x, z), 0, c);
        BlockState[] col = new BlockState[level.getHeight()];
        for (int i = 0; i < col.length; i++) col[i] = state(t.materialAt(c, level.getMinY() + i, x, z));
        return new NoiseColumn(level.getMinY(), col);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        return TerrainSampler.MAX_Y - TerrainSampler.MIN_Y + 1;
    }

    @Override
    public int getSeaLevel() {
        return TerrainSampler.SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return TerrainSampler.MIN_Y;
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return Math.max(PlanetSlots.terrain(slot).height(0, 0), TerrainSampler.SEA_LEVEL) + 1;
    }

    @Override
    public void addDebugScreenInfo(List<String> out, RandomState randomState, BlockPos pos, SamplerContext ctx) {
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        if (rt == null) {
            out.add("Galactica surface_" + slot + ": free");
            return;
        }
        SurfaceGrid g = rt.grid();
        out.add(String.format("Galactica surface_%d: %s (%s) %.2f g, %.1f m/block, lat %.2f° lon %.2f°", slot,
                rt.body().name(), rt.body().type().label, rt.body().surfaceGravityG(), g.metresPerBlock(),
                Math.toDegrees(g.latitude(pos.getZ())), Math.toDegrees(g.longitude(pos.getX()))));
    }
}
