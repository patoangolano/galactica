package dev.galactica.compat.dh;

import dev.galactica.planet.TerrainSampler;
import dev.galactica.planet.TerrainSampler.Material;

/**
 * Pure-Java layering of one Distant Horizons LOD column, straight from {@link TerrainSampler}: the
 * same height, layers and fluids {@code PlanetChunkGenerator} places, collapsed to at most seven
 * vertical spans (bedrock, under, filler, top, fluid, ice, air) instead of 768 blocks.
 *
 * <p>Spans are bottom-up, contiguous, and in DH's coordinates: Y relative to the level's minimum
 * build height, {@code [bottom, top)}, every height ≥ 1. That is exactly what DH 3.3.4's
 * {@code LodDataBuilder.validateOrThrowApiDataColumn} accepts (read from the decompiled jar).
 *
 * <p><b>Sky light.</b> Each span carries the sky light at its top block: 15 minus one per
 * light-dampening block above it. Open air and an open fluid surface get 15. Water and ice both
 * dampen sky light by 1 per block (vanilla {@code BlockBehaviour.getLightDampening}: neither is a
 * solid render shape nor propagates sky light down), the same rule DH's own rough-surface generator
 * uses. So under an ice cap the fluid gets 14, and a submerged floor gets 15 − (water + ice depth);
 * the ice block is already counted in that depth. Solid spans below the surface get 0.
 *
 * <p><b>Slope.</b> {@code TerrainSampler.column} bares rock where the height step to a neighbour is
 * ≥ 3 blocks. A coarse LOD column spans {@code step} blocks, so the slope is the neighbour
 * difference divided by {@code step} and rounded to the nearest block: the mean per-block gradient
 * across the column. Rounding up instead would bare rock on every coarse column steeper than
 * 2 blocks/block, more rock than the full-detail terrain it fades into.
 */
public final class LodColumnPlan {
    /** Upper bound of spans per column: bedrock, under, filler, top, fluid, ice, air. */
    public static final int MAX_SPANS = 7;

    /** One output span. Reused between columns to keep generation allocation-free. */
    public static final class Span {
        public Material material;
        public int bottom, top;
        public int skyLight;

        void set(Material m, int bottom, int top, int skyLight) {
            this.material = m;
            this.bottom = bottom;
            this.top = top;
            this.skyLight = skyLight;
        }
    }

    private final TerrainSampler.Column column = new TerrainSampler.Column();
    private final Span[] spans = new Span[MAX_SPANS];
    private int count;

    public LodColumnPlan() {
        for (int i = 0; i < MAX_SPANS; i++) spans[i] = new Span();
    }

    public int count() {
        return count;
    }

    public Span span(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException(i);
        return spans[i];
    }

    /**
     * Plans the column at block (x, z) whose surface height is {@code h}.
     *
     * @param slope     larger height step to the +x / +z neighbour LOD column, in blocks
     * @param step      LOD column width in blocks (2^detailLevel)
     * @param minY      level minimum build Y (−256 for galactica:surface)
     * @param height    level build height (768 for galactica:surface)
     */
    public void plan(TerrainSampler t, int x, int z, int h, int slope, int step, int minY, int height) {
        count = 0;
        int maxY = minY + height; // exclusive
        h = Math.max(minY + 1, Math.min(maxY - 2, h));
        int perBlockSlope = perBlockSlope(slope, step);
        t.column(x, z, h, perBlockSlope, column);
        TerrainSampler.Column c = column;

        int surfaceTop = h + 1; // exclusive top of the solid column
        boolean wet = c.fluid != Material.AIR && c.fluidTop > h;
        int waterDepth = wet ? c.fluidTop - h : 0;
        int surfaceLight = Math.max(0, 15 - waterDepth);

        // Solid layers, bottom-up. Depths follow TerrainSampler.materialAt: top occupies depths
        // [0, topDepth), filler [topDepth, topDepth + fillerDepth), under below, bedrock floor.
        int bedrockTop = Math.min(surfaceTop, minY + 1 + t.bedrockDepth(x, z));
        int fillerBottom = Math.max(bedrockTop, surfaceTop - c.topDepth - c.fillerDepth);
        int topBottom = Math.max(bedrockTop, surfaceTop - c.topDepth);
        add(Material.BEDROCK, minY, bedrockTop, 0, minY);
        add(c.under, bedrockTop, fillerBottom, 0, minY);
        add(c.filler, fillerBottom, topBottom, 0, minY);
        add(c.top, topBottom, surfaceTop, surfaceLight, minY);

        int airBottom = surfaceTop;
        if (wet) {
            int fluidTop = Math.min(maxY - 1, c.fluidTop + 1); // exclusive
            if (c.iceCap) {
                add(c.fluid, surfaceTop, fluidTop - 1, 14, minY); // one ice block above
                add(Material.ICE, Math.max(surfaceTop, fluidTop - 1), fluidTop, 15, minY);
            } else {
                add(c.fluid, surfaceTop, fluidTop, 15, minY);
            }
            airBottom = fluidTop;
        }
        add(Material.AIR, airBottom, maxY, 15, minY);
    }

    /**
     * Mean per-block height gradient of a column {@code step} blocks wide whose neighbour differs
     * by {@code slope} blocks, rounded half up to whole blocks (the unit {@code TerrainSampler.column}
     * compares against its bare-rock threshold).
     */
    public static int perBlockSlope(int slope, int step) {
        return step <= 1 ? slope : (int) Math.round(slope / (double) step);
    }

    /** Appends a span if it has positive height; Y converted to DH's level-relative coordinates. */
    private void add(Material m, int bottomAbs, int topAbs, int skyLight, int minY) {
        if (topAbs <= bottomAbs) return;
        spans[count++].set(m, bottomAbs - minY, topAbs - minY, skyLight);
    }

    /** Vanilla light emission of the block (lava 15, magma block 3), so lava worlds glow in the LOD. */
    public static int blockLight(Material m) {
        return switch (m) {
            case LAVA -> 15;
            case MAGMA_BLOCK -> 3;
            default -> 0;
        };
    }

    /** Registry id of the vanilla block each material maps to (mirrors PlanetChunkGenerator's table). */
    public static String blockId(Material m) {
        return switch (m) {
            case AIR -> "minecraft:air";
            case BEDROCK -> "minecraft:bedrock";
            case STONE -> "minecraft:stone";
            case GRAVEL -> "minecraft:gravel";
            case TUFF -> "minecraft:tuff";
            case RED_SAND -> "minecraft:red_sand";
            case TERRACOTTA -> "minecraft:terracotta";
            case PACKED_ICE -> "minecraft:packed_ice";
            case SNOW_BLOCK -> "minecraft:snow_block";
            case BLUE_ICE -> "minecraft:blue_ice";
            case ICE -> "minecraft:ice";
            case BASALT -> "minecraft:basalt";
            case BLACKSTONE -> "minecraft:blackstone";
            case MAGMA_BLOCK -> "minecraft:magma_block";
            case GRASS_BLOCK -> "minecraft:grass_block";
            case DIRT -> "minecraft:dirt";
            case SAND -> "minecraft:sand";
            case SANDSTONE -> "minecraft:sandstone";
            case YELLOW_TERRACOTTA -> "minecraft:yellow_terracotta";
            case SMOOTH_BASALT -> "minecraft:smooth_basalt";
            case WATER -> "minecraft:water";
            case LAVA -> "minecraft:lava";
            case COARSE_DIRT -> "minecraft:coarse_dirt";
        };
    }
}
